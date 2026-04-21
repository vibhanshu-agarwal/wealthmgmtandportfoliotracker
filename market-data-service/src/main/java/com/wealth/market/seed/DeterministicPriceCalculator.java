package com.wealth.market.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Deterministic, ticker-and-user-specific price jitter.
 *
 * <p>Computes {@code basePrice \u00d7 (1 + jitter)} where {@code jitter} is
 * {@code floorMod(hash(ticker + ":" + userId), 500) / 10000.0} \u2014 i.e. [0.00%, 4.99%].
 *
 * <p>Uses {@link String#hashCode()} which is specified by the JLS and stable across JVMs,
 * so the portfolio-service (Postgres) and market-data-service (MongoDB) compute identical
 * prices for the same {@code (ticker, userId)} pair.
 */
public final class DeterministicPriceCalculator {

    private static final int JITTER_RANGE_BPS = 500;
    private static final int BPS_SCALE = 4;
    private static final int OUTPUT_SCALE = 4;

    private DeterministicPriceCalculator() {}

    public static BigDecimal compute(BigDecimal basePrice, String ticker, String userId) {
        int seed = (ticker + ":" + userId).hashCode();
        int jitterBps = Math.floorMod(seed, JITTER_RANGE_BPS);
        BigDecimal multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(jitterBps, BPS_SCALE));
        return basePrice.multiply(multiplier).setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }
}
