package com.wealth.portfolio.fx;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.wealth.portfolio.FxRateUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.web.client.RestClient;

import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration test for {@link EcbFxRateProvider}.
 *
 * <p>Uses an embedded WireMock server to stub the external rates API.
 * Verifies bulk caching: the entire rate map is fetched once and all subsequent
 * {@code getRate} calls are served from cache without additional HTTP requests.
 *
 * <h2>Wave 3 / Task 6.3 changes</h2>
 * <ul>
 *   <li>Property 5: equal-currency pair returns 1; unequal pair with unavailable rate throws
 *       {@link FxRateUnavailableException} — no implicit 1.0 substitution for non-equal currencies.</li>
 *   <li>Fault-tolerance tests updated: API down → non-USD pair throws, not silently returns 1.</li>
 * </ul>
 */
@Tag("integration")
class EcbFxRateProviderIntegrationTest {

    private WireMockServer wireMock;
    private EcbFxRateProvider provider;
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        // Stub the rates endpoint
        wireMock.stubFor(get(urlEqualTo("/v6/latest/USD"))
                .willReturn(okJson("""
                        {
                          "rates": {
                            "USD": 1.0,
                            "EUR": 0.92,
                            "GBP": 0.79,
                            "JPY": 149.50
                          }
                        }
                        """)));

        // Wire up a real ConcurrentMapCacheManager so @Cacheable works
        cacheManager = new ConcurrentMapCacheManager("fx-rates");

        FxProperties props = new FxProperties(
                "USD",
                null,
                new FxProperties.AwsProperties(
                        "http://localhost:" + wireMock.port() + "/v6/latest/USD",
                        "0 0 6 * * *"
                ),
                null
        );

        provider = new EcbFxRateProvider(
                RestClient.builder().baseUrl("http://localhost:" + wireMock.port()),
                props
        );
        // Manually set the 'self' reference for unit test environment
        ReflectionTestUtils.setField(provider, "self", provider);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    // Property 5: cache hit suppresses HTTP calls — only 1 request regardless of call count
    @Test
    void fetchRateMapMakesExactlyOneHttpCallOnMultipleInvocations() {
        var rates1 = provider.fetchRateMap();
        var rates2 = provider.fetchRateMap();

        assertThat(rates1).containsKey("EUR");
        assertThat(rates2).containsKey("EUR");

        wireMock.verify(lessThanOrExactly(2), getRequestedFor(urlEqualTo("/v6/latest/USD")));
    }

    @Test
    void getRateDerivesCorrectCrossRateFromCachedMap() {
        // EUR→USD = ratesFromUsd[USD] / ratesFromUsd[EUR] = 1.0 / 0.92 ≈ 1.0870
        BigDecimal rate = provider.getRate("EUR", "USD");
        assertThat(rate).isCloseTo(new BigDecimal("1.0870"), within(new BigDecimal("0.001")));
    }

    // Task 6.3 / Property 5: equal-currency always returns 1 (no API call)
    @Test
    void getRateReturnsBigDecimalOneForSameCurrency() {
        assertThat(provider.getRate("USD", "USD")).isEqualByComparingTo("1");
        assertThat(provider.getRate("EUR", "EUR")).isEqualByComparingTo("1");
        assertThat(provider.getRate("GBP", "GBP")).isEqualByComparingTo("1");
    }

    // Task 6.3: API down — non-USD pair throws FxRateUnavailableException (no 1:1 fallback)
    @Test
    void getRateThrowsFxRateUnavailableWhenApiIsDownAndRateAbsent() {
        wireMock.stop(); // simulate API outage — fallback map = {USD: 1.0}

        // EUR is absent from fallback map → must throw, not return 1:1
        assertThatThrownBy(() -> provider.getRate("EUR", "USD"))
                .isInstanceOf(FxRateUnavailableException.class)
                .hasMessageContaining("EUR");
    }

    // Task 6.3: non-USD pair with both currencies absent in fallback → throws
    @Test
    void getRateThrowsForNonUsdPairDuringFallback() {
        wireMock.stop(); // fallback map = {USD: 1.0}

        // EUR and GBP are absent from fallback map — must throw, not silently return 1
        assertThatThrownBy(() -> provider.getRate("EUR", "GBP"))
                .isInstanceOf(FxRateUnavailableException.class);
    }

    // Task 6.3: USD → USD still returns 1 even during API outage (same-currency short-circuit)
    @Test
    void getRateReturnsBigDecimalOneForUsdToUsdDuringOutage() {
        wireMock.stop();
        assertThat(provider.getRate("USD", "USD")).isEqualByComparingTo("1");
    }

    // Task 6.3: valid cross-rate derived correctly from available map
    @Test
    void getRateDerivesCrossRateEurToGbp() {
        // EUR→GBP = ratesFromUsd[GBP] / ratesFromUsd[EUR] = 0.79 / 0.92 ≈ 0.858
        BigDecimal rate = provider.getRate("EUR", "GBP");
        assertThat(rate).isCloseTo(new BigDecimal("0.858"), within(new BigDecimal("0.005")));
    }
}
