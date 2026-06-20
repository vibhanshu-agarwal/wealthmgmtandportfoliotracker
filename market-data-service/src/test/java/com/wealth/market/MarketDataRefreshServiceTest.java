package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    private BaselineTickerProperties baseline;
    private KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;
    private MarketDataRefreshService refreshService;

    @BeforeEach
    void setUp() {
        repo = mock(AssetPriceRepository.class);
        client = mock(ExternalMarketDataClient.class);
        baseline = new BaselineTickerProperties();
        baseline.setTickers(List.of("AAPL"));
        when(repo.findAll()).thenReturn(List.of());

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, PriceUpdatedEvent> template = mock(KafkaTemplate.class);
        kafkaTemplate = template;

        refreshService = new MarketDataRefreshService(repo, client, baseline, kafkaTemplate, meterRegistry);
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
}
