package com.wealth.gateway.auth;

public record LoginResponse(String token, String userId, String email, String name) {}
