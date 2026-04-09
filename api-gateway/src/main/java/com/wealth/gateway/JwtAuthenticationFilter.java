package com.wealth.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
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
        // Step 1: Strip any caller-supplied X-User-Id unconditionally (spoofing prevention).
        // This applies even to unauthenticated requests.
        ServerWebExchange sanitised = exchange.mutate()
                .request(r -> r.headers(h -> h.remove(X_USER_ID)))
                .build();

        // Step 2: Extract Authentication from the reactive security context.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .cast(JwtAuthenticationToken.class)
                .flatMap(token -> {
                    // Step 3: Extract sub claim — never log the raw token value.
                    String sub = token.getToken().getClaimAsString("sub");
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
                .onErrorResume(ClassCastException.class, ex -> {
                    // Non-JWT authentication type — treat as unauthenticated.
                    sanitised.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return sanitised.getResponse().setComplete();
                })
                .onErrorResume(Exception.class, ex -> {
                    // Unexpected exception — log at ERROR, return 500.
                    log.error("Unexpected error in JwtAuthenticationFilter", ex);
                    sanitised.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    return sanitised.getResponse().setComplete();
                });
    }
}
