package com.wealth.gateway;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: new-user-signup-profile, Property 6: Read-only enforcement is exactly "block
 * portfolio/market writes, allow AI routes and reads". Validates Requirements 7.4-7.7.
 */
class ReadOnlyEnforcementFilterPropertyTest {

    private final ReadOnlyEnforcementFilter filter =
            new ReadOnlyEnforcementFilter(List.of("/api/chat/**", "/api/insights/generate/**"));

    @Property(tries = 100)
    void blocksIffReadOnlyAndMutatingAndProtectedAndNotAiAllowlisted(
            @ForAll("booleans") boolean ro,
            @ForAll("methods") HttpMethod method,
            @ForAll("paths") String path) {
        boolean blocked = filter.decide(ro, method, path);

        boolean expectedMutating = List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
                .contains(method);
        boolean expectedProtected = path.startsWith("/api/portfolio/") || path.startsWith("/api/market/");
        boolean expectedAiAllowlisted = path.startsWith("/api/chat/") || path.startsWith("/api/insights/generate/");
        boolean expected = ro && expectedMutating && expectedProtected && !expectedAiAllowlisted;

        assertThat(blocked).isEqualTo(expected);
    }

    @Provide
    Arbitrary<Boolean> booleans() {
        return Arbitraries.of(true, false);
    }

    @Provide
    Arbitrary<HttpMethod> methods() {
        return Arbitraries.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                HttpMethod.DELETE, HttpMethod.HEAD);
    }

    @Provide
    Arbitrary<String> paths() {
        return Arbitraries.of(
                "/api/portfolio/holdings", "/api/portfolio/analytics", "/api/market/prices",
                "/api/chat/message", "/api/insights/generate/summary", "/api/insights/history",
                "/api/auth/login", "/api/settings/profile");
    }
}
