package com.wealth.gateway;

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
