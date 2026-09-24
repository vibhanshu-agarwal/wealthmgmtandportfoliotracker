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

    /**
     * Rehearsal defect #2: Yahoo moved Uniswap to UNI7083-USD and now serves another token under
     * UNI-USD. The job asks for the provider symbol and publishes under the catalog ticker; a
     * price the provider returns for the old symbol is never used.
     */
    @Test
    void requestsProviderSymbolsAndPublishesUnderTheCatalogTicker() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        ExternalMarketDataClient external = mock(ExternalMarketDataClient.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> template = mock(KafkaTemplate.class);
        SupportedCatalog catalog = mock(SupportedCatalog.class);
        when(catalog.active()).thenReturn(List.of(
                new CatalogEntry("UNI-USD", "Uniswap", List.of(), "CRYPTO", "USD", LifecycleStatus.ACTIVE),
                new CatalogEntry("AAPL", "Apple", List.of(), "US_EQUITY", "USD", LifecycleStatus.ACTIVE)));
        when(catalog.providerSymbol("UNI-USD")).thenReturn("UNI7083-USD");
        when(catalog.providerSymbol("AAPL")).thenReturn("AAPL");
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        when(external.getLatestPrices(List.of("UNI7083-USD", "AAPL"))).thenReturn(Map.of(
                "UNI7083-USD", new BigDecimal("9.2372"),
                "UNI-USD", new BigDecimal("0.0002"), // another token under the old symbol
                "AAPL", BigDecimal.valueOf(150)));
        when(template.send(eq("market-prices"), anyString(), any(PriceUpdatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        new MarketDataRefreshService(repo, external, catalog, template, meterRegistry).refresh();

        org.mockito.ArgumentCaptor<PriceUpdatedEvent> events = org.mockito.ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(template, org.mockito.Mockito.times(2)).send(eq("market-prices"), anyString(), events.capture());
        Map<String, BigDecimal> published = new java.util.HashMap<>();
        events.getAllValues().forEach(e -> published.put(e.ticker(), e.newPrice()));
        assertThat(published).containsOnlyKeys("UNI-USD", "AAPL");
        assertThat(published.get("UNI-USD")).isEqualByComparingTo("9.2372");
        assertThat(published.get("AAPL")).isEqualByComparingTo("150");
    }

    @Test
    void aTickerWhoseProviderSymbolIsNotQuotedIsSkipped() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        ExternalMarketDataClient external = mock(ExternalMarketDataClient.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> template = mock(KafkaTemplate.class);
        SupportedCatalog catalog = mock(SupportedCatalog.class);
        when(catalog.active()).thenReturn(List.of(
                new CatalogEntry("UNI-USD", "Uniswap", List.of(), "CRYPTO", "USD", LifecycleStatus.ACTIVE)));
        when(catalog.providerSymbol("UNI-USD")).thenReturn("UNI7083-USD");
        // Only the old symbol is quoted: that is another token, so nothing is published.
        when(external.getLatestPrices(List.of("UNI7083-USD")))
                .thenReturn(Map.of("UNI-USD", new BigDecimal("0.0002")));

        new MarketDataRefreshService(repo, external, catalog, template, meterRegistry).refresh();

        org.mockito.Mockito.verifyNoInteractions(template);
        assertThat(meterRegistry.counter("market.data.refresh.tickers", "outcome", "skipped").count()).isEqualTo(1.0);
    }
}
