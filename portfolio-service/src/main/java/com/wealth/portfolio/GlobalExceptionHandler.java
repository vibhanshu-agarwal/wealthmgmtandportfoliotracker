package com.wealth.portfolio;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles missing required request headers (e.g. X-User-Id not present).
     * This indicates the request bypassed the API Gateway — return 400 with a clear message.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(
            MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(
                Map.of("error", "Required header '" + ex.getHeaderName() + "' is missing"));
    }

    /**
     * Handles requests for a user that does not exist in the users table.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(
            UserNotFoundException ex) {
        return ResponseEntity.notFound().build();
    }

    /**
     * Handles FX rate lookup failures.
     * Returns 503 with a retryable flag so clients know the failure is transient.
     */
    @ExceptionHandler(FxRateUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleFxRateUnavailable(
            FxRateUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "FX rate unavailable: %s → %s".formatted(
                        ex.getFromCurrency(), ex.getToCurrency()),
                "retryable", true
        ));
    }
}
