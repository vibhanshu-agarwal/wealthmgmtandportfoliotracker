package com.wealth.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Throttles /api/auth/login and /api/auth/signup by programmatically invoking the shared
 * RedisRateLimiter with the Auth_Bucket config (Req 6). A WebFilter, not a route
 * RequestRateLimiter, because /api/auth/** is a controller endpoint, not a proxied route (Req
 * 6.1, 6.8). Ordered to run before AuthController's handler.
 */
@Component
@Profile("prod")
public class AuthRateLimitFilter implements WebFilter, Ordered {

    private static final String AUTH_ROUTE_ID = "auth-bucket"; // shared by login+signup (Req 6.5)
    private static final byte[] THROTTLED_BODY = ("{\"error\":\"rate_limited\","
            + "\"message\":\"Too many requests. Please try again later.\"}")
            .getBytes(StandardCharsets.UTF_8);

    private final RateLimiter<?> authRateLimiter;
    private final KeyResolver authKeyResolver;
    private final int retryAfterSeconds;

    public AuthRateLimitFilter(
            // @Qualifier is required: under the "prod" profile, GatewayRateLimitConfig declares
            // THREE RedisRateLimiter beans (standardRateLimiter, strictRateLimiter,
            // authRateLimiter), and standardRateLimiter is marked @Primary so
            // RequestRateLimiterGatewayFilterFactory's factory-level default can resolve
            // unambiguously (see GatewayRateLimitConfig javadoc). Spring resolves an @Primary
            // candidate BEFORE matching by parameter name, so an unqualified parameter here —
            // even one literally named "authRateLimiter" — would silently receive
            // standardRateLimiter's instance instead, enforcing the wrong (10x more permissive)
            // limits while Retry-After still looked correct (it's computed independently from the
            // @Value primitives below, not from this bean). See
            // AuthRateLimitFilterBeanWiringTest for the regression test.
            @Qualifier("authRateLimiter")
            org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter authRateLimiter,
            @Value("${app.rate-limit.trust-xff-last-hop:false}") boolean trustXffLastHop,
            @Value("${app.rate-limit.auth.requested-tokens:12}") int requestedTokens,
            @Value("${app.rate-limit.auth.replenish-rate:1}") int replenishRate) {
        this.authRateLimiter = authRateLimiter;
        this.authKeyResolver = exchange -> Mono.just(
                GatewayRateLimitConfig.resolveTrustedHopKey(
                        exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"),
                        exchange.getRequest().getRemoteAddress() != null
                                && exchange.getRequest().getRemoteAddress().getAddress() != null
                                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                                : null));
        this.retryAfterSeconds = (int) Math.ceil((double) requestedTokens / replenishRate);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    /**
     * Package-visible for {@code AuthRateLimitFilterBeanWiringTest} to assert, by reference
     * identity, that this filter is wired to the {@code authRateLimiter} bean and not
     * {@code standardRateLimiter} (the {@code @Primary} candidate an unqualified constructor
     * parameter would otherwise silently resolve to).
     */
    RateLimiter<?> authRateLimiterForTesting() {
        return authRateLimiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.equals("/api/auth/login") && !path.equals("/api/auth/signup")) {
            return chain.filter(exchange);
        }
        return authKeyResolver.resolve(exchange)
                .flatMap(key -> authRateLimiter.isAllowed(AUTH_ROUTE_ID, key)
                        .flatMap(resp -> {
                            if (resp.isAllowed()) {
                                return chain.filter(exchange);
                            }
                            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                            exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
                            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(THROTTLED_BODY);
                            return exchange.getResponse().writeWith(Mono.just(buffer));
                        }))
                .onErrorResume(ex -> chain.filter(exchange)); // Req 6.7: fail open
    }
}
