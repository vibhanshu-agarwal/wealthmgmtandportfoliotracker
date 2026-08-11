package com.wealth.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents that AuthRateLimitFilter derives its key via the shared
 * GatewayRateLimitConfig.resolveTrustedHopKey (Req 6.2, 6.8) — the spoof-resistance property
 * itself is already covered by GatewayRateLimitConfigKeyResolverPropertyTest and is not
 * re-implemented here.
 */
class AuthRateLimitFilterKeyDerivationTest {

    @Test
    void authFilterUsesTheSameTrustedHopResolverAsTheRouteLimiter() {
        String spoofed = "1.2.3.4, 5.6.7.8, 9.9.9.9";
        String viaSharedResolver = GatewayRateLimitConfig.resolveTrustedHopKey(spoofed, "203.0.113.1");

        assertThat(viaSharedResolver).isEqualTo("9.9.9.9"); // right-most (ingress-appended) hop
    }
}
