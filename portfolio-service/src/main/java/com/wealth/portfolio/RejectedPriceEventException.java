package com.wealth.portfolio;

/**
 * Typed rejection of a price event that fails catalog or currency rules (Requirement 9.5, 9.6, 9.12).
 *
 * <p>Registered as non-retryable so Kafka routes the record to {@code market-prices.DLT}.
 */
public final class RejectedPriceEventException extends RuntimeException {

    public enum Reason {
        TICKER_ABSENT,
        CURRENCY_UNRESOLVABLE,
        CURRENCY_MISMATCH
    }

    private final String ticker;
    private final Reason reason;

    public RejectedPriceEventException(String ticker, Reason reason) {
        super("Rejected price event for " + ticker + ": " + reason);
        this.ticker = ticker;
        this.reason = reason;
    }

    public String ticker() {
        return ticker;
    }

    public Reason reason() {
        return reason;
    }
}
