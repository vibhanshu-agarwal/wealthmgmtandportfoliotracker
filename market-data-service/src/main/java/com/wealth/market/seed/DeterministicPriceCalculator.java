package com.wealth.market.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

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

    /** Signed delta range for 24h history: ±3.00%, excluding |delta| &lt; 0.10% to avoid scale-4 collisions. */
    private static final int HISTORY_DELTA_BUCKETS = 582;
    private static final int HISTORY_NEGATIVE_COUNT = 291;

    private DeterministicPriceCalculator() {}

    public static BigDecimal compute(BigDecimal basePrice, String ticker, String userId) {
        int seed = (ticker + ":" + userId).hashCode();
        int jitterBps = Math.floorMod(seed, JITTER_RANGE_BPS);
        BigDecimal multiplier = BigDecimal.ONE.add(BigDecimal.valueOf(jitterBps, BPS_SCALE));
        return basePrice.multiply(multiplier).setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Computes a deterministic 25h-ago reference price such that the forward change from
     * history → {@code currentPrice} lies in [−3%, +3%] and varies by ticker and calendar day.
     *
     * <p>Formula: {@code delta = signedBps / 10000} where signedBps is derived from
     * {@code hash(ticker + ":" + userId + ":" + LocalDate.now())} in [−300, +300];
     * {@code historyPrice = currentPrice / (1 + delta)}.
     */
    public static BigDecimal computeHistory(BigDecimal currentPrice, String ticker, String userId) {
        int seed = (ticker + ":" + userId + ":" + LocalDate.now()).hashCode();
        int raw = Math.floorMod(seed, HISTORY_DELTA_BUCKETS);
        // Maps to [-300, -10] ∪ [10, 300] bps — never 0 and never too small for numeric(18,4).
        int deltaBps = raw < HISTORY_NEGATIVE_COUNT ? raw - 300 : raw - 281;
        BigDecimal delta = BigDecimal.valueOf(deltaBps, BPS_SCALE);
        BigDecimal divisor = BigDecimal.ONE.add(delta);
        BigDecimal history = currentPrice.divide(divisor, OUTPUT_SCALE, RoundingMode.HALF_UP);
        if (history.compareTo(currentPrice) == 0) {
            // Sub-scale assets (e.g. SHIB-USD @ 0.0000 at scale 4): nudge by one tick.
            BigDecimal tick = new BigDecimal("0.0001");
            history = currentPrice.compareTo(BigDecimal.ZERO) == 0
                    ? tick
                    : currentPrice.subtract(tick.multiply(BigDecimal.valueOf(currentPrice.signum())));
            if (history.compareTo(BigDecimal.ZERO) <= 0) {
                history = currentPrice.add(tick);
            }
            history = history.setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);
        }
        return history;
    }
}
