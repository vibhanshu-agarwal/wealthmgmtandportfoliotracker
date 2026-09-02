package com.wealth.gateway;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: new-user-signup-profile, Property 6: Read-only enforcement is exactly "block
 * portfolio/market writes, allow AI routes and reads". Validates Requirements 7.4-7.7.
 *
 * <p>B2 (Tasks 5.3, GC.7) adds exactly two further exemptions — {@code PUT
 * /api/portfolio/holdings} (composition write) and {@code PUT /api/portfolio/demo-reset} (manual
 * reset) — as exact method/path pairs, not prefixes, and independent of the configurable AI
 * allowlist.
 */
class ReadOnlyEnforcementFilterPropertyTest {

    private static final List<String> DEFAULT_AI_ALLOWLIST =
            List.of("/api/chat/**", "/api/insights/generate/**");

    private final ReadOnlyEnforcementFilter filter =
            new ReadOnlyEnforcementFilter(DEFAULT_AI_ALLOWLIST);

    @Property(tries = 100)
    void blocksIffReadOnlyAndMutatingAndProtectedAndNotAiAllowlistedAndNotB2Exempt(
            @ForAll("booleans") boolean ro,
            @ForAll("methods") HttpMethod method,
            @ForAll("paths") String path) {
        boolean blocked = filter.decide(ro, method, path);

        boolean expectedMutating = List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
                .contains(method);
        boolean expectedProtected = path.startsWith("/api/portfolio/") || path.startsWith("/api/market/");
        boolean expectedAiAllowlisted = path.startsWith("/api/chat/") || path.startsWith("/api/insights/generate/");
        boolean expectedB2Exempt = method == HttpMethod.PUT
                && (path.equals("/api/portfolio/holdings") || path.equals("/api/portfolio/demo-reset"));
        boolean expected = ro && expectedMutating && expectedProtected
                && !expectedAiAllowlisted && !expectedB2Exempt;

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
                "/api/portfolio/demo-reset", "/api/portfolio/holdings/123", "/api/portfolio/demo-reset/",
                "/api/chat/message", "/api/insights/generate/summary", "/api/insights/history",
                "/api/auth/login", "/api/settings/profile");
    }

    // ── B2 exact exemptions (Tasks 5.3, GC.7) ────────────────────────────────

    @ParameterizedTest(name = "PUT {0} is exempt for a read-only account")
    @ValueSource(strings = {"/api/portfolio/holdings", "/api/portfolio/demo-reset"})
    void b2ExemptionsAllowExactlyThoseTwoPutPaths(String path) {
        assertThat(filter.decide(true, HttpMethod.PUT, path)).isFalse();
    }

    /**
     * The exemptions are exact method/path pairs. A prefix-shaped exemption would silently open
     * every child path under them — this pins the boundary on both sides.
     */
    @ParameterizedTest(name = "PUT {0} is still blocked")
    @ValueSource(
            strings = {
                "/api/portfolio/holdings/",
                "/api/portfolio/holdings/123",
                "/api/portfolio/holdings/123/lots",
                "/api/portfolio/holding",
                "/api/portfolio/holdingsx",
                "/api/portfolio/demo-reset/",
                "/api/portfolio/demo-reset/extra",
                "/api/portfolio/demo-resets",
                "/api/portfolio/demo-rese",
                "/api/market/holdings",
                "/api/market/demo-reset",
            })
    void neighbouringPathsAreNotExempt(String path) {
        assertThat(filter.decide(true, HttpMethod.PUT, path)).isTrue();
    }

    @ParameterizedTest(name = "{0} /api/portfolio/holdings is still blocked")
    @CsvSource({
        "POST,/api/portfolio/holdings",
        "PATCH,/api/portfolio/holdings",
        "DELETE,/api/portfolio/holdings",
        "POST,/api/portfolio/demo-reset",
        "PATCH,/api/portfolio/demo-reset",
        "DELETE,/api/portfolio/demo-reset",
    })
    void otherMutatingMethodsOnTheExemptPathsAreNotExempt(String method, String path) {
        assertThat(filter.decide(true, HttpMethod.valueOf(method), path)).isTrue();
    }

    /**
     * The B2 exemptions must not depend on the operator-configurable AI allowlist: an operator
     * narrowing or clearing {@code app.read-only.ai-allowlist} must not close the composition or
     * manual-reset routes.
     */
    @ParameterizedTest(name = "B2 exemptions survive AI allowlist {0}")
    @ValueSource(strings = {"", "/api/nowhere/**", "/api/chat/**"})
    void b2ExemptionsAreIndependentOfTheConfiguredAiAllowlist(String allowlist) {
        List<String> patterns = allowlist.isEmpty() ? List.of() : List.of(allowlist);
        ReadOnlyEnforcementFilter reconfigured = new ReadOnlyEnforcementFilter(patterns);

        assertThat(reconfigured.decide(true, HttpMethod.PUT, "/api/portfolio/holdings")).isFalse();
        assertThat(reconfigured.decide(true, HttpMethod.PUT, "/api/portfolio/demo-reset")).isFalse();
        assertThat(reconfigured.decide(true, HttpMethod.POST, "/api/portfolio/holdings")).isTrue();
    }

    @ParameterizedTest(name = "a writable account is unaffected on {0}")
    @ValueSource(strings = {"/api/portfolio/holdings", "/api/portfolio/demo-reset", "/api/portfolio/manual"})
    void writableAccountsAreUnaffectedByTheExemptions(String path) {
        assertThat(filter.decide(false, HttpMethod.PUT, path)).isFalse();
    }

    // ── Pre-existing behaviour that must survive (Requirements 7.4-7.7) ───────

    @ParameterizedTest(name = "{0} /api/portfolio/manual is blocked for a read-only account")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void protectedWritesOutsideTheExemptionsRemainBlocked(String method) {
        assertThat(filter.decide(true, HttpMethod.valueOf(method), "/api/portfolio/manual")).isTrue();
        assertThat(filter.decide(true, HttpMethod.valueOf(method), "/api/market/prices")).isTrue();
    }

    @ParameterizedTest(name = "read {0} is always allowed")
    @ValueSource(
            strings = {
                "/api/portfolio/holdings",
                "/api/portfolio/demo-reset",
                "/api/portfolio/manual",
                "/api/market/prices",
            })
    void ordinaryReadsRemainAllowed(String path) {
        assertThat(filter.decide(true, HttpMethod.GET, path)).isFalse();
        assertThat(filter.decide(true, HttpMethod.HEAD, path)).isFalse();
    }

    @Test
    void nullMethodIsNeverBlocked() {
        assertThat(filter.decide(true, null, "/api/portfolio/manual")).isFalse();
    }

    /**
     * The property above's {@code paths()} generator only supplies paths that are either
     * protected ({@code /api/portfolio/**}, {@code /api/market/**}) OR AI-allowlisted
     * ({@code /api/chat/**}, {@code /api/insights/generate/**}) — never both at once, since those
     * two path sets are structurally disjoint in the default allowlist. That means {@code
     * decide()}'s AI-allowlist check never actually changes the outcome of any generated case
     * above: deleting it entirely would still pass all 100 generated cases identically.
     *
     * <p>This test closes that gap directly: it configures an allowlist pattern that DOES overlap
     * a protected path ({@code /api/portfolio/ai/**}, itself under the protected
     * {@code /api/portfolio/**} prefix) and asserts the exemption actually fires for a mutating
     * request to that overlapping sub-path, while an equivalent non-overlapping mutating request
     * under the same protected prefix is still blocked.
     *
     * <p>Extended for B2: the exemption must hold for EVERY mutating method, not just POST. The
     * two new B2 exemptions are PUT-only exact paths, so a naive implementation that folded them
     * into the same code path as the configured allowlist could narrow this configured pattern to
     * PUT, or to an exact path, without any other test noticing.
     */
    @ParameterizedTest(name = "{0} on an overlapping configured AI path is exempt")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void aiAllowlistExemptionAppliesEvenWhenItOverlapsAProtectedPrefix(String methodName) {
        HttpMethod method = HttpMethod.valueOf(methodName);
        ReadOnlyEnforcementFilter overlappingFilter =
                new ReadOnlyEnforcementFilter(List.of("/api/portfolio/ai/**"));

        assertThat(overlappingFilter.decide(true, method, "/api/portfolio/ai/rebalance"))
                .as("AI-allowlisted sub-path must be allowed even though it's under a protected prefix")
                .isFalse();
        assertThat(overlappingFilter.decide(true, method, "/api/portfolio/manual"))
                .as("a non-allowlisted mutation under the same protected prefix must still be blocked")
                .isTrue();
    }
}
