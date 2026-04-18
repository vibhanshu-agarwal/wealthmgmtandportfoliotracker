package com.wealth.gateway;

public final class LoginDtos {

    private LoginDtos() {
    }

    public record LoginRequest(String email, String password) { }

    public record LoginResponse(String token, String userId, String email, String name) { }

    public record ErrorResponse(String error) { }
}
