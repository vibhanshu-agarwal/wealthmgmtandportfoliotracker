package com.wealth.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GatewayRateLimitConfig.
 *
 * <p>Tests for the pure {@code resolveKey} static method call the package-private method
 * directly — no Spring context or codec stack required.
 *
 * <p>Tests for the {@code userOrIpKeyResolver} bean use Mockito to simulate authenticated
 * and unauthenticated exchanges without a full Spring context.
 */
class GatewayRateLimitConfigTest {

    // ── resolveKey: X-Forwarded-For extraction ────────────────────────────────

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

    // ── resolveKey: Remote address fallback ───────────────────────────────────

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

    // ── resolveKey: Anonymous fallback ────────────────────────────────────────

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

    // ── resolveKey: Idempotence (Property 8) ─────────────────────────────────

    @ParameterizedTest(name = "resolveKey(\"{0}\", \"{1}\") is idempotent")
    @CsvSource({
        "'203.0.113.1, 10.0.0.1', '10.0.0.1'",
        "'', '192.168.1.5'",
        ", '172.16.0.1'",
        ", "
    })
    void resolveKeyIsIdempotent(String xff, String remoteHost) {
        String first  = GatewayRateLimitConfig.resolveKey(xff, remoteHost);
        String second = GatewayRateLimitConfig.resolveKey(xff, remoteHost);
        assertThat(first).isEqualTo(second);
    }

    // ── resolveTrustedHopKey: right-most (ingress-appended) XFF hop ─────────

    @ParameterizedTest(name = "X-Forwarded-For \"{0}\" -> \"{1}\" (trusted hop)")
    @CsvSource({
        // spoofed prefix, trusted ingress-appended hop is right-most
        "'203.0.113.1, 10.0.0.1',          '10.0.0.1'",
        // single IP, no comma — the only hop is trusted
        "'203.0.113.1',                     '203.0.113.1'",
        // multi-hop chain — right-most wins regardless of spoofed prefixes
        "'1.1.1.1, 2.2.2.2, 9.10.11.12',   '9.10.11.12'",
        // whitespace around the trusted hop is trimmed
        "'1.2.3.4 ,  10.0.0.5 ',            '10.0.0.5'"
    })
    void trustedHopReturnsRightMostSegmentTrimmed(String headerValue, String expectedKey) {
        assertThat(GatewayRateLimitConfig.resolveTrustedHopKey(headerValue, "10.0.0.1"))
                .isEqualTo(expectedKey);
    }

    @Test
    void trustedHopNoXffFallsBackToRemoteAddress() {
        assertThat(GatewayRateLimitConfig.resolveTrustedHopKey(null, "10.0.0.99"))
                .isEqualTo("10.0.0.99");
    }

    @Test
    void trustedHopBlankXffFallsBackToRemoteAddress() {
        assertThat(GatewayRateLimitConfig.resolveTrustedHopKey("   ", "10.0.0.88"))
                .isEqualTo("10.0.0.88");
    }

    @Test
    void trustedHopNoAddressInformationReturnsAnonymous() {
        assertThat(GatewayRateLimitConfig.resolveTrustedHopKey(null, null))
                .isEqualTo("anonymous");
    }

    @Test
    void trustedHopTrailingCommaFallsBackToRemoteAddress() {
        // "1.2.3.4," -> last hop is blank after the trailing comma -> fall through to remote host
        assertThat(GatewayRateLimitConfig.resolveTrustedHopKey("1.2.3.4,", "10.0.0.7"))
                .isEqualTo("10.0.0.7");
    }

    // ── Bean smoke test ───────────────────────────────────────────────────────

    @Test
    void userOrIpKeyResolverBeanIsNotNull() {
        assertThat(new GatewayRateLimitConfig().userOrIpKeyResolver(false)).isNotNull();
    }

    // ── userOrIpKeyResolver: authenticated principal uses sub claim ───────────

    @Test
    void authenticatedPrincipalWithValidSubReturnsSub() {
        ServerWebExchange exchange = exchangeWithPrincipal("user-uuid-abc-123");
        String key = new GatewayRateLimitConfig().userOrIpKeyResolver(false).resolve(exchange).block();
        assertThat(key).isEqualTo("user-uuid-abc-123");
    }

    @Test
    void authenticatedPrincipalWithBlankSubFallsBackToIp() {
        ServerWebExchange exchange = exchangeWithPrincipal("   ");
        // blank sub → falls back to IP; mocked exchange has no remote address → "anonymous"
        String key = new GatewayRateLimitConfig().userOrIpKeyResolver(false).resolve(exchange).block();
        assertThat(key).isEqualTo("anonymous");
    }

    @Test
    void unauthenticatedExchangeFallsBackToIp() {
        ServerWebExchange exchange = exchangeWithNoPrincipal();
        // no principal → defaultIfEmpty path → "anonymous" (no remote address in mock)
        String key = new GatewayRateLimitConfig().userOrIpKeyResolver(false).resolve(exchange).block();
        assertThat(key).isEqualTo("anonymous");
    }

    // ── userOrIpKeyResolver: trustXffLastHop=true uses trusted-hop path ───────

    @Test
    void trustXffLastHopTrueUsesTrustedHopForUnauthenticated() {
        ServerWebExchange exchange = exchangeWithNoPrincipalAndXff("203.0.113.1, 10.0.0.1");
        String key = new GatewayRateLimitConfig().userOrIpKeyResolver(true).resolve(exchange).block();
        assertThat(key).isEqualTo("10.0.0.1");
    }

    @Test
    void trustXffLastHopFalseUsesFirstHopForUnauthenticated() {
        ServerWebExchange exchange = exchangeWithNoPrincipalAndXff("203.0.113.1, 10.0.0.1");
        String key = new GatewayRateLimitConfig().userOrIpKeyResolver(false).resolve(exchange).block();
        assertThat(key).isEqualTo("203.0.113.1");
    }

    @Test
    void authenticatedPrincipalWinsRegardlessOfTrustXffLastHop() {
        ServerWebExchange exchange = exchangeWithPrincipal("user-uuid-xyz-789");
        String key = new GatewayRateLimitConfig().userOrIpKeyResolver(true).resolve(exchange).block();
        assertThat(key).isEqualTo("user-uuid-xyz-789");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a mock ServerWebExchange pre-populated with a JwtAuthenticationToken principal
     * carrying the given sub claim.
     */
    private static ServerWebExchange exchangeWithPrincipal(String sub) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);

        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.just(token));

        // Stub the request for the IP fallback path (resolveClientIp)
        var request = mock(org.springframework.http.server.reactive.ServerHttpRequest.class);
        var headers = mock(org.springframework.http.HttpHeaders.class);
        when(exchange.getRequest()).thenReturn(request);
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddress()).thenReturn(null);

        return exchange;
    }

    /**
     * Creates a mock ServerWebExchange with no authenticated principal (unauthenticated request).
     */
    private static ServerWebExchange exchangeWithNoPrincipal() {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.empty());

        var request = mock(org.springframework.http.server.reactive.ServerHttpRequest.class);
        var headers = mock(org.springframework.http.HttpHeaders.class);
        when(exchange.getRequest()).thenReturn(request);
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddress()).thenReturn(null);

        return exchange;
    }

    /**
     * Creates a mock ServerWebExchange with no authenticated principal and the given
     * X-Forwarded-For header value (unauthenticated request behind a proxy chain).
     */
    private static ServerWebExchange exchangeWithNoPrincipalAndXff(String xff) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.empty());

        var request = mock(org.springframework.http.server.reactive.ServerHttpRequest.class);
        var headers = mock(org.springframework.http.HttpHeaders.class);
        when(exchange.getRequest()).thenReturn(request);
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getFirst("X-Forwarded-For")).thenReturn(xff);
        when(request.getRemoteAddress()).thenReturn(null);

        return exchange;
    }
}
