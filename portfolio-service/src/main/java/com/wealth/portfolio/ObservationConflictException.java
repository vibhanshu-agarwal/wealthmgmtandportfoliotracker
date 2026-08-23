package com.wealth.portfolio;

/**
 * Equal-timestamp or history-key payload conflict. Last-write-wins would conceal an upstream fault
 * (Requirements 9.16, 9.23). Non-retryable so Kafka routes the record to {@code market-prices.DLT}.
 */
public final class ObservationConflictException extends RuntimeException {

    private final String ticker;

    public ObservationConflictException(String ticker, String detail) {
        super("Observation conflict for " + ticker + ": " + detail);
        this.ticker = ticker;
    }

    public String ticker() {
        return ticker;
    }
}
