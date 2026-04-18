package com.wealth.market;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
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

        ExternalMarketDataProperties props = new ExternalMarketDataProperties();
        props.setBaseUrl("http://localhost:" + wireMockServer.port());

        YahooFinanceExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        Map<String, BigDecimal> prices = client.getLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(prices).containsEntry("AAPL", BigDecimal.valueOf(150.0));
        assertThat(prices).containsEntry("MSFT", BigDecimal.valueOf(300.0));
    }

    @Test
    void propagatesServerErrorsAfterWireMockReturns503() {
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .willReturn(aResponse().withStatus(503)));

        ExternalMarketDataProperties props = new ExternalMarketDataProperties();
        props.setBaseUrl("http://localhost:" + wireMockServer.port());

        YahooFinanceExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

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
        props.setBaseUrl("http://localhost:" + wireMockServer.port());
        props.setBatchSize(1);

        YahooFinanceExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        Map<String, BigDecimal> prices = client.getLatestPrices(List.of("AAPL", "MSFT"));

        assertThat(prices).containsEntry("AAPL", BigDecimal.valueOf(1.0));
        assertThat(prices).containsEntry("MSFT", BigDecimal.valueOf(2.0));
        WireMock.verify(2, getRequestedFor(urlPathEqualTo("/v7/finance/quote")));
    }
}

