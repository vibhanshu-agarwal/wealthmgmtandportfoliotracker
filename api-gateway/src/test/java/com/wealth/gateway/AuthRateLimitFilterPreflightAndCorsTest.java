package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Regression tests for the two defects that made a rate limit indistinguishable from an outage,
 * observed on scheduled synthetic-monitoring runs 2026-08-15 and 2026-08-16.
 *
 * <p><b>Defect 1 — preflights were charged to the auth bucket.</b> The filter matched on path with
 * no method guard, so the {@code OPTIONS} preflight a browser issues before every cross-origin
 * {@code POST /api/auth/login} consumed a token of its own. The bucket therefore drained at twice
 * the intended rate, and a suite performing a handful of logins exhausted it.
 *
 * <p><b>Defect 2 — the 429 was unreadable by the browser.</b> This filter runs at
 * {@code HIGHEST_PRECEDENCE + 1}, ahead of Spring's CORS handling, and short-circuits. Without CORS
 * headers on that response the browser blocks it and surfaces a generic network error, so the
 * frontend reported "Unable to reach the login service" — a reachability failure — for what was
 * actually a throttle. That is why three synthetic tests looked like an outage while the API,
 * another browser login, and portfolio hydration were all demonstrably healthy.
 */
class AuthRateLimitFilterPreflightAndCorsTest {

    /** Mirrors SecurityConfig's bean; the filter consumes the same type in production. */
    private static CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("https://vibhanshu-ai-portfolio.dev"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private AuthRateLimitFilter filter(RedisRateLimiter limiter) {
        return new AuthRateLimitFilter(limiter, false, 12, 1, corsSource());
    }

    /** A limiter that always denies, so any consulted request is throttled. */
    private RedisRateLimiter denyingLimiter() {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.isAllowed(anyString(), anyString()))
                .thenReturn(Mono.just(new RateLimiter.Response(false, java.util.Map.of())));
        return limiter;
    }

    @Test
    void corsPreflightIsNotChargedToTheAuthBucket() {
        RedisRateLimiter limiter = denyingLimiter();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/auth/login")
                        .header("Origin", "https://vibhanshu-ai-portfolio.dev")
                        .header("Access-Control-Request-Method", "POST"));

        StepVerifier.create(filter(limiter).filter(exchange, e -> {
            chainCalled.set(true);
            return Mono.empty();
        })).verifyComplete();

        // The limiter must not even be consulted: consulting it is what spent the token.
        verify(limiter, never()).isAllowed(anyString(), anyString());
        assertThat(chainCalled).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void throttledLoginCarriesCorsHeadersSoTheBrowserCanReadTheStatus() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("Origin", "https://vibhanshu-ai-portfolio.dev"));

        StepVerifier.create(filter(denyingLimiter()).filter(exchange, e -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("https://vibhanshu-ai-portfolio.dev");
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("12");
    }

    @Test
    void throttledLoginFromAnUnknownOriginIsNotGrantedCorsAccess() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("Origin", "https://attacker.example"));

        StepVerifier.create(filter(denyingLimiter()).filter(exchange, e -> Mono.empty()))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Echoing an arbitrary Origin would turn a throttle response into a CORS hole.
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isNull();
    }

    @Test
    void getToAnAuthPathIsNotChargedBecauseTheEndpointsArePostOnly() {
        RedisRateLimiter limiter = denyingLimiter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login"));

        StepVerifier.create(filter(limiter).filter(exchange, e -> Mono.empty())).verifyComplete();

        verify(limiter, never()).isAllowed(anyString(), anyString());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void throttledLoginExposesRetryAfterToJavaScript() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/login")
                        .header("Origin", "https://vibhanshu-ai-portfolio.dev"));

        StepVerifier.create(filter(denyingLimiter()).filter(exchange, e -> Mono.empty()))
                .verifyComplete();

        // Retry-After is not on the CORS safelist: without this header the client is sent a
        // back-off value it is not permitted to read.
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Expose-Headers"))
                .contains("Retry-After");
    }

    @Test
    void nonAuthPreflightStillBypassesTheFilterEntirely() {
        RedisRateLimiter limiter = denyingLimiter();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.OPTIONS, "/api/portfolio")
                        .header("Origin", "https://vibhanshu-ai-portfolio.dev")
                        .header("Access-Control-Request-Method", "GET"));

        StepVerifier.create(filter(limiter).filter(exchange, e -> Mono.empty())).verifyComplete();

        verify(limiter, never()).isAllowed(anyString(), anyString());
    }
}
