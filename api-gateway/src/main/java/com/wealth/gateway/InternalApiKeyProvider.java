package com.wealth.gateway;

import org.springframework.stereotype.Component;

@Component
public final class InternalApiKeyProvider {

    private final String value;

    public InternalApiKeyProvider() {
        this(System.getenv("INTERNAL_API_KEY"));
    }

    InternalApiKeyProvider(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }

    boolean isConfigured() {
        return value != null && !value.isBlank();
    }
}
