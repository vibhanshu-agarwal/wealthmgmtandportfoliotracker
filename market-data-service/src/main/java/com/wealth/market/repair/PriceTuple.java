package com.wealth.market.repair;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * The five operational fields of a {@code market_prices} document, excluding {@code _id}
 * and the temporary {@code repairGeneration} fence.
 */
public record PriceTuple(
        BigDecimal currentPrice,
        String quoteCurrency,
        Instant updatedAt,
        BigDecimal previousReferencePrice,
        Instant previousReferenceAt) {

    public boolean sameValues(PriceTuple other) {
        if (other == null) {
            return false;
        }
        return decimalEquals(currentPrice, other.currentPrice)
                && Objects.equals(quoteCurrency, other.quoteCurrency)
                && Objects.equals(updatedAt, other.updatedAt)
                && decimalEquals(previousReferencePrice, other.previousReferencePrice)
                && Objects.equals(previousReferenceAt, other.previousReferenceAt);
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }
}
