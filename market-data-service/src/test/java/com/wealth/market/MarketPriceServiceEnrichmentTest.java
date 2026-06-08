package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MarketPriceService} — Wave 2 enrichment.
 *
 * <p>Validates:
 * <ul>
 *   <li>Task 2.2: enriched event carries quoteCurrency, observedAt, and reference fields.</li>
 *   <li>Task 2.5: change null when no prior reference exists (first update).</li>
 *   <li>Task 2.5: reference populated on subsequent update.</li>
 *   <li>Legacy 2-arg overload still works (backward compat).</li>
 * </ul>
 */
class MarketPriceServiceEnrichmentTest {

    private AssetPriceRepository assetPriceRepository;
    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;
    private MarketPriceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        assetPriceRepository = mock(AssetPriceRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        service = new MarketPriceService(assetPriceRepository, kafkaTemplate);
    }

    // ── First update: no prior price → reference fields null ─────────────────

    @Test
    void firstUpdate_noPriorPrice_eventHasNullReference() {
        AssetPrice shell = new AssetPrice("AAPL", null);
        shell.setQuoteCurrency("USD");
        when(assetPriceRepository.findById("AAPL")).thenReturn(Optional.of(shell));
        when(assetPriceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePrice("AAPL", new BigDecimal("195.00"), "USD");

        ArgumentCaptor<PriceUpdatedEvent> cap = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(kafkaTemplate).send(eq("market-prices"), eq("AAPL"), cap.capture());
        PriceUpdatedEvent event = cap.getValue();

        assertThat(event.ticker()).isEqualTo("AAPL");
        assertThat(event.newPrice()).isEqualByComparingTo("195.00");
        assertThat(event.quoteCurrency()).isEqualTo("USD");
        assertThat(event.observedAt()).isNotNull();
        // No prior price → reference must be null (not fabricated).
        assertThat(event.previousReferencePrice()).isNull();
        assertThat(event.previousReferenceAt()).isNull();
    }

    // ── Subsequent update: prior price rolls to reference ────────────────────

    @Test
    void subsequentUpdate_priorPriceExists_eventHasReference() {
        AssetPrice existing = new AssetPrice("AAPL", new BigDecimal("190.00"));
        existing.setQuoteCurrency("USD");
        when(assetPriceRepository.findById("AAPL")).thenReturn(Optional.of(existing));
        when(assetPriceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePrice("AAPL", new BigDecimal("195.00"), "USD");

        ArgumentCaptor<PriceUpdatedEvent> cap = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(kafkaTemplate).send(eq("market-prices"), eq("AAPL"), cap.capture());
        PriceUpdatedEvent event = cap.getValue();

        assertThat(event.previousReferencePrice()).isEqualByComparingTo("190.00");
        assertThat(event.previousReferenceAt()).isNotNull();
    }

    // ── Currency is populated from argument ──────────────────────────────────

    @Test
    void updatePrice_withCurrency_eventCarriesCurrency() {
        when(assetPriceRepository.findById("RELIANCE.NS")).thenReturn(Optional.empty());
        when(assetPriceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePrice("RELIANCE.NS", new BigDecimal("2850.00"), "INR");

        ArgumentCaptor<PriceUpdatedEvent> cap = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(kafkaTemplate).send(eq("market-prices"), eq("RELIANCE.NS"), cap.capture());

        assertThat(cap.getValue().quoteCurrency()).isEqualTo("INR");
    }

    // ── Unchanged price: still publishes a new observation (idempotency by timestamp) ──

    @Test
    void unchangedPrice_stillPublishesEvent() {
        AssetPrice existing = new AssetPrice("BTC-USD", new BigDecimal("67000.00"));
        existing.setQuoteCurrency("USD");
        when(assetPriceRepository.findById("BTC-USD")).thenReturn(Optional.of(existing));
        when(assetPriceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePrice("BTC-USD", new BigDecimal("67000.00"), "USD");

        ArgumentCaptor<PriceUpdatedEvent> cap = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(kafkaTemplate).send(eq("market-prices"), eq("BTC-USD"), cap.capture());
        PriceUpdatedEvent event = cap.getValue();

        // Even with unchanged price, reference is rolled and event is published.
        assertThat(event.previousReferencePrice()).isEqualByComparingTo("67000.00");
        assertThat(event.newPrice()).isEqualByComparingTo("67000.00");
    }

    // ── Legacy 2-arg overload compiles and works ──────────────────────────────

    @Test
    void legacyOverload_withoutCurrency_stillPublishesEvent() {
        when(assetPriceRepository.findById("MSFT")).thenReturn(Optional.empty());
        when(assetPriceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Legacy call site — no currency.
        service.updatePrice("MSFT", new BigDecimal("415.00"));

        ArgumentCaptor<PriceUpdatedEvent> cap = ArgumentCaptor.forClass(PriceUpdatedEvent.class);
        verify(kafkaTemplate).send(eq("market-prices"), eq("MSFT"), cap.capture());

        assertThat(cap.getValue().ticker()).isEqualTo("MSFT");
        assertThat(cap.getValue().newPrice()).isEqualByComparingTo("415.00");
    }
}
