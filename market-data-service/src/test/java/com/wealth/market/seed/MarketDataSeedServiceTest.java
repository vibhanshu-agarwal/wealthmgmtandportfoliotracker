package com.wealth.market.seed;

import com.mongodb.bulk.BulkWriteResult;
import com.wealth.market.events.PriceUpdatedEvent;
import com.wealth.market.seed.SeedTickerRegistry.SeedTicker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for dual-observation seed publishing (24h Delta Hybrid Fix — Layer A3).
 */
@ExtendWith(MockitoExtension.class)
class MarketDataSeedServiceTest {

    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";
    private static final String TOPIC = "market-prices";
    private static final SeedTicker AAPL = new SeedTicker(
            "AAPL", "US_EQUITY", "USD", new BigDecimal("190.00"), "Apple Inc.", null);

    @Mock private MongoTemplate mongoTemplate;
    @Mock private SeedTickerRegistry registry;
    @Mock private KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

    private MarketDataSeedService service;

    @BeforeEach
    void setUp() {
        service = new MarketDataSeedService(mongoTemplate, registry, kafkaTemplate);
        when(registry.all()).thenReturn(List.of(AAPL));

        BulkOperations bulk = mock(BulkOperations.class);
        when(mongoTemplate.bulkOps(any(BulkOperations.BulkMode.class), anyString())).thenReturn(bulk);
        BulkWriteResult bulkResult = mock(BulkWriteResult.class);
        when(bulk.execute()).thenReturn(bulkResult);
        when(bulkResult.getUpserts()).thenReturn(List.of());

        when(kafkaTemplate.send(anyString(), anyString(), any(PriceUpdatedEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void seed_publishesExactlyTwoEventsForSingleTicker() {
        service.seed(E2E_USER);

        verify(kafkaTemplate, times(2)).send(eq(TOPIC), eq("AAPL"), any(PriceUpdatedEvent.class));
    }

    @Test
    void seed_publishesDistinctObservationsTwentyFiveHoursApart() {
        ArgumentCaptor<PriceUpdatedEvent> captor = ArgumentCaptor.forClass(PriceUpdatedEvent.class);

        service.seed(E2E_USER);

        verify(kafkaTemplate, times(2)).send(eq(TOPIC), eq("AAPL"), captor.capture());
        List<PriceUpdatedEvent> events = captor.getAllValues();
        assertThat(events).hasSize(2);

        Instant earlier = events.stream().map(PriceUpdatedEvent::observedAt).min(Instant::compareTo).orElseThrow();
        Instant later = events.stream().map(PriceUpdatedEvent::observedAt).max(Instant::compareTo).orElseThrow();
        assertThat(Duration.between(earlier, later)).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void seed_historyEventPriceDiffersFromCurrentSeededPrice() {
        ArgumentCaptor<PriceUpdatedEvent> captor = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        BigDecimal seededPrice = DeterministicPriceCalculator.compute(AAPL.basePrice(), AAPL.ticker(), E2E_USER);
        BigDecimal expectedHistory = DeterministicPriceCalculator.computeHistory(seededPrice, AAPL.ticker(), E2E_USER);

        service.seed(E2E_USER);

        verify(kafkaTemplate, times(2)).send(eq(TOPIC), eq("AAPL"), captor.capture());
        List<PriceUpdatedEvent> events = captor.getAllValues();

        PriceUpdatedEvent historyEvent = events.stream()
                .filter(e -> e.newPrice().compareTo(seededPrice) != 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a history-priced event"));
        PriceUpdatedEvent currentEvent = events.stream()
                .filter(e -> e.newPrice().compareTo(seededPrice) == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a current-priced event"));

        assertThat(historyEvent.newPrice()).isEqualByComparingTo(expectedHistory);
        assertThat(currentEvent.newPrice()).isEqualByComparingTo(seededPrice);
        assertThat(historyEvent.newPrice()).isNotEqualByComparingTo(currentEvent.newPrice());
    }
}
