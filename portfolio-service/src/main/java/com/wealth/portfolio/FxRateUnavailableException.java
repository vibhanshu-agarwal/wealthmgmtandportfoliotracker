package com.wealth.portfolio;

/**
 * Thrown when an FX rate cannot be resolved for a given currency pair.
 * This is an unchecked exception — callers may choose to handle it or let it
 * propagate to the {@link GlobalExceptionHandler} which maps it to HTTP 503.
 */
public class FxRateUnavailableException extends RuntimeException {

    private final String fromCurrency;
    private final String toCurrency;

    public FxRateUnavailableException(String fromCurrency, String toCurrency, Throwable cause) {
        super("FX rate unavailable: %s → %s".formatted(fromCurrency, toCurrency), cause);
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
    }

    public String getFromCurrency() {
        return fromCurrency;
    }

    public String getToCurrency() {
        return toCurrency;
    }
}
