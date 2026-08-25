package com.wealth.portfolio.composition;

/**
 * Marker cause attached to Jackson decode failures so {@code GlobalExceptionHandler} can map to a
 * stable {@link ContractErrorCode} without string-matching exception messages.
 */
public final class ContractTokenException extends RuntimeException {

    private final ContractErrorCode code;

    public ContractTokenException(ContractErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ContractErrorCode code() {
        return code;
    }
}
