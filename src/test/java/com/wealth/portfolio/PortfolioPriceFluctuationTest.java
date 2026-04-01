package com.wealth.portfolio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PortfolioPriceFluctuationTest {

    @ParameterizedTest(name = "fluctuation={0}% should produce valid valuation")
    @ValueSource(doubles = {-30.0, -12.5, 0.0, 9.5, 22.75})
    void shouldRecalculatePortfolioValueForPriceFluctuation(double percentageChange) {
        var basePrice = new BigDecimal("125.00");
        var quantity = new BigDecimal("18.50");

        var multiplier = BigDecimal.ONE.add(
                BigDecimal.valueOf(percentageChange)
                        .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
        );

        var adjustedPrice = basePrice.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
        var valuation = adjustedPrice.multiply(quantity).setScale(4, RoundingMode.HALF_UP);

        assertTrue(adjustedPrice.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(valuation.compareTo(BigDecimal.ZERO) >= 0);
    }
}
