package com.wealth.portfolio.freshness;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 9.44/9.45: freshness is a pure function of row presence, observation
 * timestamp, threshold, and evaluation time — no database.
 */
class AssetPriceFreshnessTest {

    private static final Duration THRESHOLD_50H = Duration.ofHours(50);
    private static final Instant NOW = Instant.parse("2026-08-19T08:00:00Z");

    @Test
    void missingWhenNoPriceRow() {
        assertThat(AssetPriceFreshness.evaluate(false, null, THRESHOLD_50H, NOW))
                .isEqualTo(FreshnessState.MISSING);
        assertThat(AssetPriceFreshness.evaluate(false, NOW, THRESHOLD_50H, NOW))
                .isEqualTo(FreshnessState.MISSING);
    }

    @Test
    void unknownWhenRowPresentWithNullTimestamp() {
        assertThat(AssetPriceFreshness.evaluate(true, null, THRESHOLD_50H, NOW))
                .isEqualTo(FreshnessState.UNKNOWN);
    }

    @Test
    void freshWhenAgeIsASingleDailyRefresh() {
        Instant observedAt = NOW.minus(Duration.ofHours(24));
        assertThat(AssetPriceFreshness.evaluate(true, observedAt, THRESHOLD_50H, NOW))
                .isEqualTo(FreshnessState.FRESH);
    }

    @Test
    void freshAtExactThresholdBoundary() {
        Instant observedAt = NOW.minus(THRESHOLD_50H);
        assertThat(AssetPriceFreshness.evaluate(true, observedAt, THRESHOLD_50H, NOW))
                .isEqualTo(FreshnessState.FRESH);
    }

    @Test
    void staleWhenOlderThanThreshold() {
        Instant observedAt = NOW.minus(THRESHOLD_50H).minusNanos(1);
        assertThat(AssetPriceFreshness.evaluate(true, observedAt, THRESHOLD_50H, NOW))
                .isEqualTo(FreshnessState.STALE);
    }

    @Test
    void defaultThresholdIsFiftyHours() {
        assertThat(AssetPriceFreshnessProperties.defaults().threshold())
                .isEqualTo(Duration.ofHours(50));
    }

    @Test
    void portfolioPrecedenceIsMissingThenUnknownThenStaleThenFresh() {
        assertThat(FreshnessState.mostSevere(FreshnessState.FRESH, FreshnessState.STALE))
                .isEqualTo(FreshnessState.STALE);
        assertThat(FreshnessState.mostSevere(FreshnessState.STALE, FreshnessState.UNKNOWN))
                .isEqualTo(FreshnessState.UNKNOWN);
        assertThat(FreshnessState.mostSevere(FreshnessState.UNKNOWN, FreshnessState.MISSING))
                .isEqualTo(FreshnessState.MISSING);
        assertThat(FreshnessState.mostSevere(FreshnessState.FRESH, FreshnessState.MISSING))
                .isEqualTo(FreshnessState.MISSING);
    }
}
