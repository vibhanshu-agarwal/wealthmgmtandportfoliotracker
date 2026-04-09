package com.wealth.portfolio.kafka;

/**
 * Thrown by {@code PriceUpdatedEventListener} when a {@code PriceUpdatedEvent} fails
 * business-level validation (null/blank ticker, null/zero/negative newPrice).
 *
 * <p>Registered as a non-retryable exception in {@code DefaultErrorHandler} so that
 * poison-pill records are routed directly to {@code market-prices.DLT} on the first
 * failure without consuming any retry budget.
 */
public final class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
