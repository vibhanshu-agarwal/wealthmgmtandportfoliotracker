package com.wealth.market;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MarketDataRefreshJobWireMockTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        // Yahoo session handshake stubs so the real client's cookie+crumb fetch resolves against
        // WireMock (no real network to fc.yahoo.com). Tests below point cookie-url here too.
        stubFor(get(urlPathEqualTo("/cookie"))
                .willReturn(aResponse()
                        .withStatus(404)
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

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, PriceUpdatedEvent> mockKafkaTemplate() {
        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceUpdatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        return kafkaTemplate;
    }

    private MarketDataRefreshService newRefreshService(AssetPriceRepository repo,
                                                       ExternalMarketDataClient client,
                                                       BaselineTickerProperties baseline,
                                                       KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate) {
        return new MarketDataRefreshService(repo, client, baseline, kafkaTemplate, meterRegistry);
    }

    @Test
    void successfulRefreshPersistsAndPublishesEvents() {
        String responseBody = """
            {
              "quoteResponse": {
                "result": [
                  {"symbol": "AAPL", "regularMarketPrice": 150.0}
                ]
              }
            }
            """;

        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        ExternalMarketDataProperties props = new ExternalMarketDataProperties();
        props.setBaseUrl("http://localhost:" + wireMockServer.port());
        props.setCookieUrl("http://localhost:" + wireMockServer.port() + "/cookie");

        ExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of());

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mockKafkaTemplate();
        MarketDataRefreshService refreshService = newRefreshService(repo, client, baseline, kafkaTemplate);

        refreshService.refresh();

        ArgumentCaptor<AssetPrice> assetCaptor = ArgumentCaptor.forClass(AssetPrice.class);
        verify(repo).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getTicker()).isEqualTo("AAPL");
        assertThat(assetCaptor.getValue().getCurrentPrice()).isEqualByComparingTo("150.0");

        ArgumentCaptor<PriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(kafkaTemplate).send(eq("market-prices"), eq("AAPL"), eventCaptor.capture());
        verify(kafkaTemplate).flush();
        assertThat(eventCaptor.getValue().ticker()).isEqualTo("AAPL");
        assertThat(eventCaptor.getValue().newPrice()).isEqualByComparingTo("150.0");
        assertThat(eventCaptor.getValue().observedAt()).isNotNull();
    }

    @Test
    void providerFailureDoesNotPublishEventsOrOverwriteData() {
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .willReturn(aResponse().withStatus(503)));

        ExternalMarketDataProperties props = new ExternalMarketDataProperties();
        props.setBaseUrl("http://localhost:" + wireMockServer.port());
        props.setCookieUrl("http://localhost:" + wireMockServer.port() + "/cookie");

        ExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        AssetPrice existing = new AssetPrice("AAPL", BigDecimal.valueOf(100.0));
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById("AAPL")).thenReturn(java.util.Optional.of(existing));

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mockKafkaTemplate();
        MarketDataRefreshService refreshService = newRefreshService(repo, client, baseline, kafkaTemplate);

        refreshService.refresh();

        verify(repo, never()).save(any());
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void resolveTrackedTickersUnionsBaselineWithMongo() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of(new AssetPrice("MSFT", BigDecimal.ONE)));

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        KafkaTemplate<String, PriceUpdatedEvent> kafka = mockKafkaTemplate();

        MarketDataRefreshService refreshService = newRefreshService(
                repo, mock(ExternalMarketDataClient.class), baseline, kafka);

        assertThat(refreshService.resolveTrackedTickers()).containsExactly("AAPL", "MSFT");
    }

    @Test
    void refreshUsesUnionOfBaselineAndMongoTickers() {
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
        props.setCookieUrl("http://localhost:" + wireMockServer.port() + "/cookie");

        ExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of(new AssetPrice("MSFT", BigDecimal.TEN)));

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mockKafkaTemplate();
        MarketDataRefreshService refreshService = newRefreshService(repo, client, baseline, kafkaTemplate);

        refreshService.refresh();

        verify(repo, times(2)).save(any(AssetPrice.class));
        verify(kafkaTemplate).send(eq("market-prices"), eq("AAPL"), any(PriceUpdatedEvent.class));
        verify(kafkaTemplate).send(eq("market-prices"), eq("MSFT"), any(PriceUpdatedEvent.class));
        verify(kafkaTemplate).flush();
    }
}
