package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GatewayRateLimitConfig.resolveKey().
 * Tests call the package-private static method directly — no Spring context or
 * codec stack required, keeping the suite fast and dependency-free.
 */
class GatewayRateLimitConfigTest {

    // --- X-Forwarded-For extraction ---

    @ParameterizedTest(name = "X-Forwarded-For \"{0}\" -> \"{1}\"")
    @CsvSource({
        // multi-hop chain — returns first segment trimmed
        "'203.0.113.1, 10.0.0.1',          '203.0.113.1'",
        // single IP, no comma
        "'203.0.113.1',                     '203.0.113.1'",
        // leading/trailing whitespace on first segment
        "' 10.0.0.5 , 192.168.1.1',         '10.0.0.5'",
        // three hops
        "'1.2.3.4, 5.6.7.8, 9.10.11.12',   '1.2.3.4'"
    })
    void xForwardedForReturnsFirstSegmentTrimmed(String headerValue, String expectedKey) {
        assertThat(GatewayRateLimitConfig.resolveKey(headerValue, "10.0.0.1"))
                .isEqualTo(expectedKey);
    }

    // --- Remote address fallback ---

    @Test
    void noXForwardedForFallsBackToRemoteAddress() {
        assertThat(GatewayRateLimitConfig.resolveKey(null, "10.0.0.99"))
                .isEqualTo("10.0.0.99");
    }

    @Test
    void blankXForwardedForFallsBackToRemoteAddress() {
        assertThat(GatewayRateLimitConfig.resolveKey("   ", "10.0.0.88"))
                .isEqualTo("10.0.0.88");
    }

    // --- Anonymous fallback ---

    @Test
    void noAddressInformationReturnsAnonymous() {
        assertThat(GatewayRateLimitConfig.resolveKey(null, null))
                .isEqualTo("anonymous");
    }

    @Test
    void blankXForwardedForAndNullRemoteReturnsAnonymous() {
        assertThat(GatewayRateLimitConfig.resolveKey("   ", null))
                .isEqualTo("anonymous");
    }

    // --- Bean smoke test ---

    @Test
    void ipKeyResolverBeanIsNotNull() {
        assertThat(new GatewayRateLimitConfig().ipKeyResolver()).isNotNull();
    }
}
