package com.wealth.market;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalMarketDataClientWireMockTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        // Default Yahoo session handshake stubs so the cookie+crumb fetch resolves against
        // WireMock (no real network) for every test. Individual tests can override these.
        stubFor(get(urlPathEqualTo("/cookie"))
                .willReturn(aResponse()
                        .withStatus(404) // fc.yahoo.com answers 404 but still sets the cookie
                        .withHeader("Set-Cookie", "A1=test-cookie; Path=/; Domain=.yahoo.com")));
        stubFor(get(urlPathEqualTo("/v1/test/getcrumb"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("test-crumb")));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    /** Builds a client whose base, cookie, and crumb endpoints all point at the WireMock server. */
    private YahooFinanceExternalMarketDataClient newClient(ExternalMarketDataProperties props) {
        String base = "http://localhost:" + wireMockServer.port();
        props.setBaseUrl(base);
        props.setCookieUrl(base + "/cookie");
        return new YahooFinanceExternalMarketDataClient(props, meterRegistry);
    }

    @Test
    void fetchesPricesSuccessfully() {
        String responseBody = """
            {
              "quoteResponse": {
                "result": [
                  {"symbol": "AAPL", "regularMarketPrice": 150.0},
                  {"symbol": "MSFT", "regularMarketPrice": 300.0}
                ]
              }
            }
            """;

        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("AAPL,MSFT"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        YahooFinanceExternalMarketDataClient client = newClient(new ExternalMarketDataProperties());

        Map<String, BigDecimal> prices = client.getLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(prices).containsEntry("AAPL", BigDecimal.valueOf(150.0));
        assertThat(prices).containsEntry("MSFT", BigDecimal.valueOf(300.0));
    }

    @Test
    void propagatesServerErrorsAfterWireMockReturns503() {
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .willReturn(aResponse().withStatus(503)));

        YahooFinanceExternalMarketDataClient client = newClient(new ExternalMarketDataProperties());

        assertThatThrownBy(() -> client.getLatestPrices(List.of("AAPL")))
                .isInstanceOf(WebClientResponseException.class);

        WireMock.verify(getRequestedFor(urlPathEqualTo("/v7/finance/quote")));
    }

    @Test
    void splitsQuoteRequestsWhenBatchSizeIsOne() {
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"quoteResponse":{"result":[{"symbol":"AAPL","regularMarketPrice":1.0}]}}
                                """)));
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("MSFT"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"quoteResponse":{"result":[{"symbol":"MSFT","regularMarketPrice":2.0}]}}
                                """)));

        ExternalMarketDataProperties props = new ExternalMarketDataProperties();
        props.setBatchSize(1);
        YahooFinanceExternalMarketDataClient client = newClient(props);

        Map<String, BigDecimal> prices = client.getLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(prices).containsEntry("AAPL", BigDecimal.valueOf(1.0));
        assertThat(prices).containsEntry("MSFT", BigDecimal.valueOf(2.0));
        WireMock.verify(2, getRequestedFor(urlPathEqualTo("/v7/finance/quote")));
        // The crumb handshake runs once and is cached across batches.
        WireMock.verify(1, getRequestedFor(urlPathEqualTo("/v1/test/getcrumb")));
    }

    @Test
    void sendsUserAgentCookieAndCrumbOnQuoteAfterHandshake() {
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"quoteResponse":{"result":[{"symbol":"AAPL","regularMarketPrice":42.0}]}}
                                """)));

        YahooFinanceExternalMarketDataClient client = newClient(new ExternalMarketDataProperties());

        Map<String, BigDecimal> prices = client.getLatestPrices(List.of("AAPL"));

        assertThat(prices).containsEntry("AAPL", BigDecimal.valueOf(42.0));

        // The quote request must carry the acquired crumb, the session cookie, and a browser UA.
        WireMock.verify(getRequestedFor(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("crumb", equalTo("test-crumb"))
                .withHeader("Cookie", containing("A1=test-cookie"))
                .withHeader("User-Agent", containing("Mozilla/5.0")));
        // The crumb fetch must itself present the cookie obtained from the cookie URL.
        WireMock.verify(getRequestedFor(urlPathEqualTo("/v1/test/getcrumb"))
                .withHeader("Cookie", containing("A1=test-cookie")));
        // The browser User-Agent must be sent on every leg of the handshake, not just the quote.
        WireMock.verify(getRequestedFor(urlPathEqualTo("/cookie"))
                .withHeader("User-Agent", containing("Mozilla/5.0")));
        WireMock.verify(getRequestedFor(urlPathEqualTo("/v1/test/getcrumb"))
                .withHeader("User-Agent", containing("Mozilla/5.0")));
    }

    @Test
    void refreshesCrumbAndRetriesOnceWhenQuoteReturns401() {
        // First quote attempt 401s (stale/absent crumb); after a re-handshake the retry succeeds.
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .inScenario("crumb-expiry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("recovered"));
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .inScenario("crumb-expiry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"quoteResponse":{"result":[{"symbol":"AAPL","regularMarketPrice":99.0}]}}
                                """)));

        YahooFinanceExternalMarketDataClient client = newClient(new ExternalMarketDataProperties());

        Map<String, BigDecimal> prices = client.getLatestPrices(List.of("AAPL"));

        assertThat(prices).containsEntry("AAPL", BigDecimal.valueOf(99.0));
        // Quote attempted twice (401 then success); crumb re-fetched for the retry.
        WireMock.verify(2, getRequestedFor(urlPathEqualTo("/v7/finance/quote")));
        WireMock.verify(2, getRequestedFor(urlPathEqualTo("/v1/test/getcrumb")));
    }

    @Test
    void propagatesUnauthorizedWhenRetryAlsoFails() {
        // Persistent 401 (e.g. Yahoo IP block) — after one refresh+retry the error propagates
        // so the caller (MarketDataRefreshService) can fall back to cached prices.
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .willReturn(aResponse().withStatus(401)));

        YahooFinanceExternalMarketDataClient client = newClient(new ExternalMarketDataProperties());

        assertThatThrownBy(() -> client.getLatestPrices(List.of("AAPL")))
                .isInstanceOf(WebClientResponseException.Unauthorized.class);

        WireMock.verify(2, getRequestedFor(urlPathEqualTo("/v7/finance/quote")));
    }

    @Test
    void proceedsWithoutCrumbWhenHandshakeIncomplete() {
        // Cookie endpoint yields no Set-Cookie: the handshake is intentionally non-fatal.
        // The client must proceed without a crumb (and not even attempt the crumb fetch),
        // leaving the existing cached-price/401 fallback as the only failure path.
        stubFor(get(urlPathEqualTo("/cookie"))
                .willReturn(aResponse().withStatus(404)));
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"quoteResponse":{"result":[{"symbol":"AAPL","regularMarketPrice":7.0}]}}
                                """)));

        YahooFinanceExternalMarketDataClient client = newClient(new ExternalMarketDataProperties());

        Map<String, BigDecimal> prices = client.getLatestPrices(List.of("AAPL"));

        assertThat(prices).containsEntry("AAPL", BigDecimal.valueOf(7.0));
        // No cookie => crumb fetch is short-circuited, and the quote carries no crumb param.
        WireMock.verify(0, getRequestedFor(urlPathEqualTo("/v1/test/getcrumb")));
        WireMock.verify(getRequestedFor(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("crumb", absent()));
    }
}
