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

import java.math.BigDecimal;
import java.util.List;

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
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
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

        ExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of());

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mock(KafkaTemplate.class);

        MarketDataRefreshJob job = new MarketDataRefreshJob(repo, client, baseline, kafkaTemplate, meterRegistry);

        job.refreshAllTrackedTickers();

        ArgumentCaptor<AssetPrice> assetCaptor = ArgumentCaptor.forClass(AssetPrice.class);
        verify(repo).save(assetCaptor.capture());
        assertThat(assetCaptor.getValue().getTicker()).isEqualTo("AAPL");
        assertThat(assetCaptor.getValue().getCurrentPrice()).isEqualByComparingTo("150.0");

        ArgumentCaptor<PriceUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(kafkaTemplate).send(eq("market-prices"), eq("AAPL"), eventCaptor.capture());
        assertThat(eventCaptor.getValue().ticker()).isEqualTo("AAPL");
        assertThat(eventCaptor.getValue().newPrice()).isEqualByComparingTo("150.0");
    }

    @Test
    void providerFailureDoesNotPublishEventsOrOverwriteData() {
        stubFor(get(urlPathEqualTo("/v7/finance/quote"))
                .withQueryParam("symbols", equalTo("AAPL"))
                .willReturn(aResponse().withStatus(503)));

        ExternalMarketDataProperties props = new ExternalMarketDataProperties();
        props.setBaseUrl("http://localhost:" + wireMockServer.port());

        ExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        AssetPrice existing = new AssetPrice("AAPL", BigDecimal.valueOf(100.0));
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        when(repo.findById("AAPL")).thenReturn(java.util.Optional.of(existing));

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mock(KafkaTemplate.class);

        MarketDataRefreshJob job = new MarketDataRefreshJob(repo, client, baseline, kafkaTemplate, meterRegistry);

        job.refreshAllTrackedTickers();

        // Existing price should not be overwritten with null, and no Kafka events published.
        verify(repo, never()).save(any());
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void resolveTrackedTickersUnionsBaselineWithMongo() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of(new AssetPrice("MSFT", BigDecimal.ONE)));

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> kafka = mock(KafkaTemplate.class);

        MarketDataRefreshJob job = new MarketDataRefreshJob(
                repo, mock(ExternalMarketDataClient.class), baseline, kafka, meterRegistry);

        assertThat(job.resolveTrackedTickers()).containsExactly("AAPL", "MSFT");
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

        ExternalMarketDataClient client = new YahooFinanceExternalMarketDataClient(props, meterRegistry);

        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        when(repo.findAll()).thenReturn(List.of(new AssetPrice("MSFT", BigDecimal.TEN)));

        BaselineTickerProperties baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate = mock(KafkaTemplate.class);

        MarketDataRefreshJob job = new MarketDataRefreshJob(repo, client, baseline, kafkaTemplate, meterRegistry);

        job.refreshAllTrackedTickers();

        verify(repo, times(2)).save(any(AssetPrice.class));
        verify(kafkaTemplate).send(eq("market-prices"), eq("AAPL"), any(PriceUpdatedEvent.class));
        verify(kafkaTemplate).send(eq("market-prices"), eq("MSFT"), any(PriceUpdatedEvent.class));
    }
}

