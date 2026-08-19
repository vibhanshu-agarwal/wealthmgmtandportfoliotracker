package com.wealth.portfolio.freshness;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Freshness threshold {@code (N × 24h) + grace}. Defaults N = 2, grace = 2h → 50h
 * so a normal single daily refresh never reports stale (Requirement 9.42, 9.43).
 */
@ConfigurationProperties(prefix = "app.asset-price-freshness")
public record AssetPriceFreshnessProperties(Integer missedCycles, Duration grace) {

    private static final int DEFAULT_MISSED_CYCLES = 2;
    private static final Duration DEFAULT_GRACE = Duration.ofHours(2);
    private static final Duration REFRESH_CYCLE = Duration.ofHours(24);

    public static AssetPriceFreshnessProperties defaults() {
        return new AssetPriceFreshnessProperties(DEFAULT_MISSED_CYCLES, DEFAULT_GRACE);
    }

    public Duration threshold() {
        int n = missedCycles == null || missedCycles <= 0 ? DEFAULT_MISSED_CYCLES : missedCycles;
        Duration g = grace == null ? DEFAULT_GRACE : grace;
        return REFRESH_CYCLE.multipliedBy(n).plus(g);
    }
}
