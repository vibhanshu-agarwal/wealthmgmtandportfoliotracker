package com.wealth.market.seed;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-service parity tests for {@link DeterministicPriceCalculator}.
 * Portfolio-service carries an equivalent test class — keep formulas in sync.
 */
class DeterministicPriceCalculatorTest {

    private static final String E2E_USER = "00000000-0000-0000-0000-000000000e2e";
    private static final BigDecimal CURRENT = new BigDecimal("1000.0000");

    @Nested
    class ComputeHistory {

        @Test
        void sameInputsOnSameDayYieldIdenticalHistoryPrices() {
            BigDecimal first = DeterministicPriceCalculator.computeHistory(CURRENT, "AAPL", E2E_USER);
            BigDecimal second = DeterministicPriceCalculator.computeHistory(CURRENT, "AAPL", E2E_USER);
            assertThat(first).isEqualByComparingTo(second);
        }

        @Test
        void differentTickersYieldDifferentHistoryPrices() {
            BigDecimal aapl = DeterministicPriceCalculator.computeHistory(CURRENT, "AAPL", E2E_USER);
            BigDecimal msft = DeterministicPriceCalculator.computeHistory(CURRENT, "MSFT", E2E_USER);
            assertThat(aapl).isNotEqualByComparingTo(msft);
        }

        @Test
        void forwardPercentChangeStaysWithinPlusMinusThreePercent() {
            String[] tickers = {
                    "AAPL", "MSFT", "GOOGL", "NVDA", "TSLA",
                    "BTC-USD", "ETH-USD", "RELIANCE.NS", "AVGO", "LLY"
            };
            for (String ticker : tickers) {
                BigDecimal history = DeterministicPriceCalculator.computeHistory(CURRENT, ticker, E2E_USER);
                BigDecimal pctChange = CURRENT.subtract(history)
                        .divide(history, 6, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                assertThat(pctChange)
                        .as("24h forward %% change for %s", ticker)
                        .isGreaterThanOrEqualTo(new BigDecimal("-3.0"))
                        .isLessThanOrEqualTo(new BigDecimal("3.0"));
            }
        }
    }
}
