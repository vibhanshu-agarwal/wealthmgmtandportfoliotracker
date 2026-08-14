package com.wealth.observability;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeDenySetTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "url.query",
            "http.query",
            "query",
            "authorization",
            "cookie",
            "password",
            "password.hash",
            "password_hash",
            "token",
            "access.token",
            "access_token",
            "refresh.token",
            "refresh_token",
            "bearer",
            "api.key",
            "api_key",
            "api-key",
            "user.id",
            "userid",
            "user_id",
            "enduser.id",
            "x-user-id",
            "gen_ai.prompt",
            "gen_ai.completion",
            "genai.prompt",
            "genai.completion",
            "ai.prompt",
            "ai.completion",
            "portfolio.value",
            "holding.value",
            "current_price",
            "valuation",
            "holdings",
            "http.request.header.authorization",
            "HTTP.REQUEST.HEADER.AUTHORIZATION",
            "set-cookie",
            "db.password"
    })
    void deniesConfiguredKeys(String key) {
        assertThat(AttributeDenySet.isDenied(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "traceparent",
            "trace.id",
            "span.id",
            "http.route",
            "http.method",
            "http.status_code",
            "http.url",
            "url.full",
            "url.path",
            "http.target"
    })
    void retainsSafeKeys(String key) {
        assertThat(AttributeDenySet.isDenied(key)).isFalse();
    }
}
