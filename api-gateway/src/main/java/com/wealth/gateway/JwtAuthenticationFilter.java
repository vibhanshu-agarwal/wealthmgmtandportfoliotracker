package com.wealth.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String X_USER_ID = "X-User-Id";

    /**
     * Runs after CloudFront origin verification and Spring Security (which validates the JWT)
     * but before routing. HIGHEST_PRECEDENCE + 2 ensures CloudFrontOriginVerifyFilter runs
     * first, followed by Spring Security's WebFilter.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip JWT processing for paths that are permitAll() in SecurityConfig.
        // These paths have no principal — the filter must not reject them.
        if (path.startsWith("/actuator") || path.equals("/api/portfolio/health")) {
            // Still strip X-User-Id to prevent spoofing on public endpoints.
            ServerWebExchange sanitised = exchange.mutate()
                    .request(r -> r.headers(h -> h.remove(X_USER_ID)))
                    .build();
            return chain.filter(sanitised);
        }

        // Step 1: Strip any caller-supplied X-User-Id unconditionally (spoofing prevention).
        // This applies even to unauthenticated requests.
        ServerWebExchange sanitised = exchange.mutate()
                .request(r -> r.headers(h -> h.remove(X_USER_ID)))
                .build();

        // Step 2: Extract the principal from the exchange (populated by Spring Security's
        // WebFilter). Using exchange.getPrincipal() instead of ReactiveSecurityContextHolder
        // because the Reactor Context is not reliably propagated to GlobalFilter instances
        // in Spring Cloud Gateway.
        return sanitised.getPrincipal()
                .flatMap(principal -> {
                    if (!(principal instanceof JwtAuthenticationToken jwtToken)) {
                        // Non-JWT authentication type — treat as unauthenticated.
                        log.debug("Principal is not a JwtAuthenticationToken — rejecting");
                        sanitised.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return sanitised.getResponse().setComplete();
                    }
                    // Step 3: Extract sub claim — never log the raw token value.
                    String sub = jwtToken.getToken().getClaimAsString("sub");
                    if (sub == null || sub.isBlank()) {
                        log.debug("JWT accepted but sub claim is missing or blank");
                        sanitised.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return sanitised.getResponse().setComplete();
                    }
                    log.debug("JWT validated for sub={}", sub);
                    // Step 4: Inject X-User-Id header and forward.
                    ServerWebExchange mutated = sanitised.mutate()
                            .request(r -> r.headers(h -> h.set(X_USER_ID, sub)))
                            .build();
                    return chain.filter(mutated);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // No principal at all — Spring Security should have rejected this
                    // already, but guard against misconfiguration.
                    log.debug("No principal found on exchange — rejecting request");
                    sanitised.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return sanitised.getResponse().setComplete();
                }));
    }
}
