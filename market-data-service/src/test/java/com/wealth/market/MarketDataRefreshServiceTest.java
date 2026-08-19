package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.wealth.catalog.CatalogEntry;
import com.wealth.catalog.LifecycleStatus;
import com.wealth.catalog.SupportedCatalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataRefreshServiceTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private AssetPriceRepository repo;
    private ExternalMarketDataClient client;
    private SupportedCatalog supportedCatalog;
    private KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;
    private MarketDataRefreshService refreshService;

    @BeforeEach
    void setUp() {
        repo = mock(AssetPriceRepository.class);
        client = mock(ExternalMarketDataClient.class);
        supportedCatalog = mock(SupportedCatalog.class);
        when(supportedCatalog.active()).thenReturn(
                List.of(
                        new CatalogEntry(
                                "AAPL",
                                "Apple",
                                List.of(),
                                "US_EQUITY",
                                "USD",
                                LifecycleStatus.ACTIVE)));
        when(repo.findById("AAPL")).thenReturn(Optional.empty());

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> template = mock(KafkaTemplate.class);
        kafkaTemplate = template;

        refreshService =
                new MarketDataRefreshService(repo, client, supportedCatalog, kafkaTemplate, meterRegistry);
    }

    @Test
    void refreshThrowsWhenSendFailsSynchronously() {
        when(client.getLatestPrices(List.of("AAPL"))).thenReturn(Map.of("AAPL", BigDecimal.valueOf(150)));
        when(kafkaTemplate.send(eq("market-prices"), eq("AAPL"), any(PriceUpdatedEvent.class)))
                .thenThrow(new IllegalStateException("serialization failure"));

        assertThatThrownBy(refreshService::refresh)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("serialization failure");

        verify(kafkaTemplate).flush();
    }

    @Test
    void refreshThrowsWhenSendFutureCompletesExceptionally() {
        when(client.getLatestPrices(List.of("AAPL"))).thenReturn(Map.of("AAPL", BigDecimal.valueOf(150)));
        when(kafkaTemplate.send(anyString(), anyString(), any(PriceUpdatedEvent.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker rejected")));

        assertThatThrownBy(refreshService::refresh)
                .isInstanceOf(java.util.concurrent.CompletionException.class)
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("broker rejected");

        verify(kafkaTemplate).flush();
    }

    @Test
    void refreshFlushesAfterSendAndBeforeReturning() {
        when(client.getLatestPrices(List.of("AAPL"))).thenReturn(Map.of("AAPL", BigDecimal.valueOf(150)));
        when(kafkaTemplate.send(eq("market-prices"), eq("AAPL"), any(PriceUpdatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        refreshService.refresh();

        var inOrder = inOrder(kafkaTemplate);
        inOrder.verify(kafkaTemplate).send(eq("market-prices"), eq("AAPL"), any(PriceUpdatedEvent.class));
        inOrder.verify(kafkaTemplate).flush();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void resolveTrackedTickers_returnsSupportedCatalogActive_onlyAndDoesNotTouchMongo() {
        SupportedCatalog catalog = SupportedCatalog.load();

        // Ensure we have at least one deprecated symbol to validate exclusion.
        String deprecatedTicker =
                catalog.all().stream()
                        .filter(e -> e.lifecycleStatus() == LifecycleStatus.DEPRECATED)
                        .map(CatalogEntry::ticker)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("expected at least one deprecated ticker"));

        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        ExternalMarketDataClient external = mock(ExternalMarketDataClient.class);
        KafkaTemplate<String, PriceUpdatedEvent> template = mock(KafkaTemplate.class);

        MarketDataRefreshService service =
                new MarketDataRefreshService(repo, external, catalog, template, meterRegistry);

        var tickers = service.resolveTrackedTickers();
        var expected =
                catalog.active().stream()
                        .map(CatalogEntry::ticker)
                        .filter(t -> t != null && !t.isBlank())
                        .map(String::trim)
                        .toList();

        assertThat(tickers).containsExactlyElementsOf(expected);
        assertThat(tickers).doesNotContain(deprecatedTicker);
        org.mockito.Mockito.verifyNoInteractions(repo);
    }

    @Test
    void refreshProcessesCatalogTickerWhenMongoDocumentMissing() {
        String ticker = "AAPL";

        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        ExternalMarketDataClient external = mock(ExternalMarketDataClient.class);
        KafkaTemplate<String, PriceUpdatedEvent> template = mock(KafkaTemplate.class);

        var entry =
                new CatalogEntry(
                        ticker, "Apple", List.of(), "US_EQUITY", "USD", LifecycleStatus.ACTIVE);
        SupportedCatalog supportedCatalog = mock(SupportedCatalog.class);
        when(supportedCatalog.active()).thenReturn(List.of(entry));

        when(repo.findById(ticker)).thenReturn(Optional.empty());
        when(external.getLatestPrices(List.of(ticker)))
                .thenReturn(Map.of(ticker, BigDecimal.valueOf(150)));
        when(template.send(eq("market-prices"), eq(ticker), org.mockito.ArgumentMatchers.any(PriceUpdatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        MarketDataRefreshService service =
                new MarketDataRefreshService(repo, external, supportedCatalog, template, meterRegistry);

        service.refresh();

        org.mockito.Mockito.verify(repo).save(org.mockito.ArgumentMatchers.any(AssetPrice.class));
        org.mockito.Mockito.verify(template).send(
                eq("market-prices"),
                eq(ticker),
                org.mockito.ArgumentMatchers.any(PriceUpdatedEvent.class));
    }
}
