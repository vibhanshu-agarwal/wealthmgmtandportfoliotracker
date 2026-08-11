package com.wealth.gateway.auth;

public final class SignupDtos {

    private SignupDtos() {}

    public record SignupRequest(String email, String password, String name) {}

    /** Output of SignupValidator.validate — normalized (email lowercased? NO — email case is
     * preserved as submitted; only the functional index in Postgres is case-insensitive), and
     * the name is trimmed. */
    public record ValidatedSignup(String email, String password, String name) {}

    public record FieldErrorResponse(String error, String field) {}
}
