package com.wealth.portfolio.freshness;

import java.time.Duration;
import java.time.Instant;

/**
 * Pure asset-price freshness function. No database, no refresh (Requirement 9.44, 9.45).
 */
public final class AssetPriceFreshness {

    private AssetPriceFreshness() {}

    public static FreshnessState evaluate(
            boolean priceRowPresent, Instant observedAt, Duration threshold, Instant now) {
        if (!priceRowPresent) {
            return FreshnessState.MISSING;
        }
        if (observedAt == null) {
            return FreshnessState.UNKNOWN;
        }
        Duration age = Duration.between(observedAt, now);
        if (age.compareTo(threshold) > 0) {
            return FreshnessState.STALE;
        }
        return FreshnessState.FRESH;
    }
}
