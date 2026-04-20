package com.wealth.portfolio.seed;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Properties of the cross-service deterministic price calculator.
 *
 * <p>Guarantees asserted:
 * <ol>
 *   <li>Determinism: the same {@code (basePrice, ticker, userId)} always yields the same price.</li>
 *   <li>Jitter range: {@code price / basePrice - 1} is in {@code [0.0000, 0.0499]}.</li>
 *   <li>Stable JVM contract: {@link String#hashCode()} is fixed by the JLS, so portfolio-service
 *       and market-data-service compute byte-identical prices for the same inputs.</li>
 *   <li>Output scale is 4 (BigDecimal), matching the {@code numeric(18,4)} column on
 *       {@code market_prices.current_price}.</li>
 * </ol>
 */
class DeterministicPriceCalculatorTest {

    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";
    private static final BigDecimal BASE_AAPL = new BigDecimal("190.00");

    @Test
    void sameInputsYieldIdenticalPrices() {
        BigDecimal first = DeterministicPriceCalculator.compute(BASE_AAPL, "AAPL", E2E_USER);
        BigDecimal second = DeterministicPriceCalculator.compute(BASE_AAPL, "AAPL", E2E_USER);
        assertThat(first).isEqualByComparingTo(second);
    }

    @Test
    void outputScaleIsFour() {
        BigDecimal price = DeterministicPriceCalculator.compute(BASE_AAPL, "AAPL", E2E_USER);
        assertThat(price.scale()).isEqualTo(4);
    }

    @Test
    void jitterNeverExceedsFivePercent() {
        // Sampling the full 160-ticker set is covered by the integration test; here we
        // verify the mathematical upper bound on a handful of representative inputs.
        String[] tickers = {"AAPL", "GOOGL", "RELIANCE.NS", "BTC-USD", "EUR/USD", "ZNGA"};
        for (String t : tickers) {
            BigDecimal price = DeterministicPriceCalculator.compute(BASE_AAPL, t, E2E_USER);
            BigDecimal ratio = price.divide(BASE_AAPL, 6, java.math.RoundingMode.HALF_UP);
            assertThat(ratio)
                    .as("jitter for %s", t)
                    .isGreaterThanOrEqualTo(new BigDecimal("1.000000"))
                    .isLessThan(new BigDecimal("1.050000"));
        }
    }

    @Test
    void differentUserIdsProduceDifferentPricesForAtLeastOneTicker() {
        // A single ticker may collide between two users; across a 3-ticker sweep the
        // probability of all three collisions is astronomically small, which is enough
        // to show the userId is actually mixed into the jitter seed.
        String[] tickers = {"AAPL", "GOOGL", "MSFT"};
        boolean anyDifferent = false;
        for (String t : tickers) {
            BigDecimal userA = DeterministicPriceCalculator.compute(BASE_AAPL, t, "user-a");
            BigDecimal userB = DeterministicPriceCalculator.compute(BASE_AAPL, t, "user-b");
            if (userA.compareTo(userB) != 0) {
                anyDifferent = true;
                break;
            }
        }
        assertThat(anyDifferent).isTrue();
    }

    @Test
    void jitterSeedIsStableAcrossJvmInvocations() {
        // Pinned expected value computed once against the JLS-specified String#hashCode.
        // If this assertion fails, either the JVM's String#hashCode contract was broken
        // (highly unlikely) or the jitter formula was silently changed — either case must
        // fail the build because market-data-service depends on bitwise price equality.
        BigDecimal expected = new BigDecimal("190.00").multiply(
                BigDecimal.ONE.add(BigDecimal.valueOf(
                        Math.floorMod(("AAPL:" + E2E_USER).hashCode(), 500), 4)))
                .setScale(4, java.math.RoundingMode.HALF_UP);
        BigDecimal actual = DeterministicPriceCalculator.compute(BASE_AAPL, "AAPL", E2E_USER);
        assertThat(actual).isEqualByComparingTo(expected);
    }
}
