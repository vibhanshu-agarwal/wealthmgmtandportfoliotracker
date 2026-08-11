package com.wealth.gateway.auth;

/** Thrown by SignupValidator when a signup field fails validation (Req 1.4-1.8, 9.2). */
public class ValidationException extends RuntimeException {

    private final String field;

    public ValidationException(String field, String reason) {
        super(reason);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
