package com.wealth.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AssetPrice#recordNewObservation} — the reference roll-forward logic.
 *
 * <p>Validates Task 2.1 / Property 4 (reference honesty): when a new observation is recorded,
 * the prior price/timestamp are rolled into the reference fields regardless of whether the
 * price changed. When no prior observation exists, reference fields remain null.
 */
class AssetPriceReferenceRollForwardTest {

    // ── Roll-forward: price changed ───────────────────────────────────────────

    @Test
    void recordNewObservation_withPriorPrice_rollsPriorToReference() {
        AssetPrice price = new AssetPrice("AAPL", new BigDecimal("190.00"));
        Instant originalTime = price.getUpdatedAt();

        Instant newObservedAt = Instant.now().plusSeconds(3600);
        price.recordNewObservation(new BigDecimal("195.00"), newObservedAt);

        assertThat(price.getCurrentPrice()).isEqualByComparingTo("195.00");
        assertThat(price.getUpdatedAt()).isEqualTo(newObservedAt);
        assertThat(price.getPreviousReferencePrice()).isEqualByComparingTo("190.00");
        assertThat(price.getPreviousReferenceAt()).isNotNull();
        // Reference time should be close to the original updatedAt.
        assertThat(price.getPreviousReferenceAt()).isBefore(newObservedAt);
    }

    // ── Roll-forward: price unchanged (new snapshot same value = still a new row) ──

    @Test
    void recordNewObservation_unchangedPrice_stillRollsReference() {
        AssetPrice price = new AssetPrice("BTC-USD", new BigDecimal("67000.00"));
        Instant originalTime = price.getUpdatedAt();

        Instant newObservedAt = Instant.now().plusSeconds(86400);
        price.recordNewObservation(new BigDecimal("67000.00"), newObservedAt);

        // Price unchanged, but reference is still rolled forward.
        assertThat(price.getCurrentPrice()).isEqualByComparingTo("67000.00");
        assertThat(price.getPreviousReferencePrice()).isEqualByComparingTo("67000.00");
        assertThat(price.getPreviousReferenceAt()).isNotNull();
        assertThat(price.getUpdatedAt()).isEqualTo(newObservedAt);
    }

    // ── No prior price: reference remains null ────────────────────────────────

    @Test
    void recordNewObservation_noPriorPrice_referenceRemainsNull() {
        // Shell document with no price (e.g. just inserted by BaselineSeeder).
        AssetPrice price = new AssetPrice("ETH-USD", null);

        Instant newObservedAt = Instant.now();
        price.recordNewObservation(new BigDecimal("3400.00"), newObservedAt);

        assertThat(price.getCurrentPrice()).isEqualByComparingTo("3400.00");
        // previousReferencePrice must stay null — there was no prior value to roll.
        assertThat(price.getPreviousReferencePrice()).isNull();
        assertThat(price.getPreviousReferenceAt()).isNull();
    }

    // ── Multiple observations: chain of roll-forwards ─────────────────────────

    @Test
    void recordNewObservation_multipleObservations_latestReferenceIsImmediate() {
        AssetPrice price = new AssetPrice("TSLA", new BigDecimal("170.00"));

        Instant t1 = Instant.now().plusSeconds(1000);
        price.recordNewObservation(new BigDecimal("175.00"), t1);

        Instant t2 = Instant.now().plusSeconds(2000);
        price.recordNewObservation(new BigDecimal("180.00"), t2);

        // After second observation: reference should be the value set at t1.
        assertThat(price.getCurrentPrice()).isEqualByComparingTo("180.00");
        assertThat(price.getPreviousReferencePrice()).isEqualByComparingTo("175.00");
        assertThat(price.getPreviousReferenceAt()).isEqualTo(t1);
    }

    // ── quoteCurrency survives roll-forward ───────────────────────────────────

    @Test
    void recordNewObservation_quoteCurrencyPreserved() {
        AssetPrice price = new AssetPrice("RELIANCE.NS", new BigDecimal("2800.00"));
        price.setQuoteCurrency("INR");

        price.recordNewObservation(new BigDecimal("2850.00"), Instant.now().plusSeconds(500));

        assertThat(price.getQuoteCurrency()).isEqualTo("INR");
    }
}
