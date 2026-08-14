package com.wealth.observability;

import java.util.Locale;
import java.util.Set;

/**
 * Shared deny-set for observation attributes and the sanitizing exporter backstop.
 * A key is denied when its lowercase name equals or ends with a listed name, or
 * contains a listed secret-bearing substring.
 */
public final class AttributeDenySet {

    private static final Set<String> EXACT_OR_SUFFIX = Set.of(
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
            "holdings");

    private static final String[] SUBSTRINGS = {
            "authorization",
            "password",
            "set-cookie",
            "bearer"
    };

    private AttributeDenySet() {
    }

    public static boolean isDenied(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String name : EXACT_OR_SUFFIX) {
            if (lower.equals(name) || lower.endsWith(name)) {
                return true;
            }
        }
        for (String substring : SUBSTRINGS) {
            if (lower.contains(substring)) {
                return true;
            }
        }
        return false;
    }
}
