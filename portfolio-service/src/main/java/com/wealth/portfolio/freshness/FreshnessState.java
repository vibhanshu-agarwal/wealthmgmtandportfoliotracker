package com.wealth.portfolio.freshness;

/**
 * Per-holding or portfolio-level asset-price freshness. Scoped to asset prices only;
 * FX rate age is out of scope (Requirement 9.46).
 *
 * <p>Portfolio reduction uses {@link #mostSevere} under precedence
 * {@code MISSING > UNKNOWN > STALE > FRESH}.
 */
public enum FreshnessState {
    FRESH(0),
    STALE(1),
    UNKNOWN(2),
    MISSING(3);

    private final int severity;

    FreshnessState(int severity) {
        this.severity = severity;
    }

    public static FreshnessState mostSevere(FreshnessState a, FreshnessState b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.severity >= b.severity ? a : b;
    }
}
