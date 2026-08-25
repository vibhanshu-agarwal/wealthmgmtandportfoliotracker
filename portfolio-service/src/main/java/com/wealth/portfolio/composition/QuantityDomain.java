package com.wealth.portfolio.composition;

import java.math.BigDecimal;

/**
 * Application-layer Quantity_Domain: required, strictly positive, at most 11 integer digits and 8
 * fractional digits, maximum {@code 99999999999.99999999}. The database {@code CHECK} is a backstop.
 */
public final class QuantityDomain {

    public static final BigDecimal MAX = new BigDecimal("99999999999.99999999");
    public static final int MAX_INTEGER_DIGITS = 11;
    public static final int MAX_FRACTIONAL_DIGITS = 8;

    private QuantityDomain() {}

    public static boolean isValid(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return false;
        }
        if (quantity.scale() > MAX_FRACTIONAL_DIGITS) {
            return false;
        }
        if (quantity.compareTo(MAX) > 0) {
            return false;
        }
        int integerDigits = quantity.precision() - quantity.scale();
        return integerDigits <= MAX_INTEGER_DIGITS;
    }

    /** Canonical storage scale for no-op equality ({@code NUMERIC(19,8)}). */
    public static BigDecimal canonicalQuantity(BigDecimal quantity) {
        return quantity.setScale(MAX_FRACTIONAL_DIGITS, java.math.RoundingMode.UNNECESSARY);
    }
}
