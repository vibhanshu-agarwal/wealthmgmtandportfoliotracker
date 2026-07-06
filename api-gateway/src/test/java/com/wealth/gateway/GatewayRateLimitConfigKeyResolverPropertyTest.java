package com.wealth.gateway;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * jqwik property test for {@link GatewayRateLimitConfig}'s key-resolution logic.
 *
 * <p><b>Property 1: Key derivation is correct and spoof-resistant</b> — for any
 * {@code X-Forwarded-For} value of the form {@code "<spoofed prefix...>, <trustedHop>"},
 * {@code resolveTrustedHopKey} returns exactly {@code trustedHop} regardless of the spoofed
 * prefix, falling back to the remote address (else {@code "anonymous"}) only when no XFF hop is
 * present. Pure unit property — no Redis or Spring context required.
 *
 * <p>Validates Requirements 3.6, 3.9 (see {@code .kiro/specs/production-rate-limiting}).
 */
class GatewayRateLimitConfigKeyResolverPropertyTest {

    // ── Property 1: trusted hop wins regardless of spoofed prefix ────────────

    @Property(tries = 100)
    void trustedHopIsReturnedRegardlessOfSpoofedPrefix(
            @ForAll("spoofedPrefixesAndTrustedHop") SpoofedXff spoofedXff) {
        String key = GatewayRateLimitConfig.resolveTrustedHopKey(spoofedXff.header(), "203.0.113.254");

        assertThat(key).isEqualTo(spoofedXff.trustedHop());
    }

    @Provide
    Arbitrary<SpoofedXff> spoofedPrefixesAndTrustedHop() {
        Arbitrary<String> ipSegment = Arbitraries.integers().between(1, 254).map(String::valueOf);
        Arbitrary<String> ip = Combinators.combine(ipSegment, ipSegment, ipSegment, ipSegment)
                .as((a, b, c, d) -> a + "." + b + "." + c + "." + d);

        // 1 to 4 spoofed prefix hops, plus exactly one trusted (ingress-appended) trailing hop.
        Arbitrary<Integer> prefixHopCount = Arbitraries.integers().between(1, 4);

        return Combinators.combine(prefixHopCount, ip.list().ofMinSize(1).ofMaxSize(4), ip)
                .as((count, prefixIps, trustedHop) -> {
                    java.util.List<String> hops = prefixIps.subList(0, Math.min(count, prefixIps.size()));
                    String header = String.join(", ", hops) + ", " + trustedHop;
                    return new SpoofedXff(header, trustedHop);
                });
    }

    // ── Property 1: authenticated sub claim always wins (documented via pure resolveKey/resolveTrustedHopKey
    //    equivalence — the JWT-sub branch itself is covered by GatewayRateLimitConfigTest's Mockito-based
    //    bean tests, since minting arbitrary JWTs here would duplicate that Spring-typed setup) ──────────

    @Property(tries = 100)
    void noXffHopFallsBackToRemoteAddressThenAnonymous(
            @ForAll("blankOrNullXff") String blankXff, @ForAll("remoteHosts") String remoteHost) {
        String key = GatewayRateLimitConfig.resolveTrustedHopKey(blankXff, remoteHost);

        if (remoteHost != null && !remoteHost.isBlank()) {
            assertThat(key).isEqualTo(remoteHost);
        } else {
            assertThat(key).isEqualTo("anonymous");
        }
    }

    @Provide
    Arbitrary<String> blankOrNullXff() {
        return Arbitraries.of("", "   ", (String) null);
    }

    @Provide
    Arbitrary<String> remoteHosts() {
        Arbitrary<String> ipSegment = Arbitraries.integers().between(1, 254).map(String::valueOf);
        Arbitrary<String> ip = Combinators.combine(ipSegment, ipSegment, ipSegment, ipSegment)
                .as((a, b, c, d) -> a + "." + b + "." + c + "." + d);
        return ip.injectNull(0.3);
    }

    record SpoofedXff(String header, String trustedHop) {
    }
}
