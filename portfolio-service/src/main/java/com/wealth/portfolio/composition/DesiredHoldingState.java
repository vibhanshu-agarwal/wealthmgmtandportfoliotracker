package com.wealth.portfolio.composition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Complete persisted holding tuple the replacement primitive owns: ticker, quantity, and cost-basis
 * fields.
 */
public record DesiredHoldingState(
        String ticker,
        BigDecimal quantity,
        BigDecimal avgCostBasis,
        String costBasisCurrency,
        String costBasisSource,
        Instant costBasisAsOf) {

    public boolean samePersistedTuple(DesiredHoldingState other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(ticker, other.ticker)
                && QuantityDomain.canonicalQuantity(quantity)
                        .compareTo(QuantityDomain.canonicalQuantity(other.quantity))
                        == 0
                && costBasisEqual(avgCostBasis, other.avgCostBasis)
                && Objects.equals(costBasisCurrency, other.costBasisCurrency)
                && Objects.equals(costBasisSource, other.costBasisSource)
                && Objects.equals(costBasisAsOf, other.costBasisAsOf);
    }

    private static boolean costBasisEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }
}
