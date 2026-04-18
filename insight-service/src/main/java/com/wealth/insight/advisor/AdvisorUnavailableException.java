package com.wealth.insight.advisor;

/**
 * Thrown when the AI advisor service is unreachable or returns an unparseable response.
 *
 * <p>The {@link com.wealth.insight.GlobalExceptionHandler} maps this to HTTP 503
 * with {@code "retryable": true}.
 */
public class AdvisorUnavailableException extends RuntimeException {

    public AdvisorUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AdvisorUnavailableException(String message) {
        super(message);
    }
}
