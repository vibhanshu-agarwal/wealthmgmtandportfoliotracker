package com.wealth.gateway;

import com.wealth.gateway.presence.DemoPresenceService;
import com.wealth.gateway.presence.JwtSessionIdentity;
import java.security.Principal;
import java.util.Optional;
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

    private final DemoPresenceService demoPresenceService;

    public JwtAuthenticationFilter(DemoPresenceService demoPresenceService) {
        this.demoPresenceService = demoPresenceService;
    }

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
        // /api/auth/** is included to match the permitAll() declaration for auth endpoints.
        // /api/internal/** is the Golden-State E2E seeder — gated on X-Internal-Api-Key
        // by the downstream services, not by JWT at the gateway (design doc \u00a7 7).
        if (path.startsWith("/actuator")
                || path.equals("/api/portfolio/health")
                || path.equals("/api/market/health")
                || path.equals("/api/insights/health")
                || path.equals("/api/auth") || path.startsWith("/api/auth/")
                || path.startsWith("/api/internal/")) {
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
        //
        // Resolve to a value (the sub claim, or empty on any rejection) with .map() before
        // branching. .map() preserves emission cardinality — it only completes empty when
        // getPrincipal() itself was empty — so switchIfEmpty below fires exactly for that "no
        // principal at all" case. Composing switchIfEmpty directly around chain.filter(...)
        // instead (as this used to) re-ran the fallback after every request, successful or not,
        // because chain.filter() returns Mono<Void>, which always completes empty regardless of
        // outcome.
        return sanitised.getPrincipal()
                .map(JwtAuthenticationFilter::extractIdentity)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    // No principal at all — Spring Security should have rejected this
                    // already, but guard against misconfiguration.
                    log.debug("No principal found on exchange — rejecting request");
                    return Optional.<JwtSessionIdentity>empty();
                }))
                .flatMap(identity -> {
                    if (identity.isEmpty()) {
                        sanitised.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return sanitised.getResponse().setComplete();
                    }
                    // Step 4: best-effort demo presence touch, then inject X-User-Id and forward once.
                    JwtSessionIdentity session = identity.get();
                    ServerWebExchange mutated = sanitised.mutate()
                            .request(r -> r.headers(h -> h.set(X_USER_ID, session.sub())))
                            .build();
                    if (session.hasPresenceSession()) {
                        demoPresenceService.scheduleBackgroundTouch(session);
                    }
                    return chain.filter(mutated);
                });
    }

    /**
     * Step 3: validate the principal is a JWT with a usable sub claim, logging why it wasn't when
     * it isn't. Never logs the raw token value, jti, or session hash.
     */
    private static Optional<JwtSessionIdentity> extractIdentity(Principal principal) {
        Optional<JwtSessionIdentity> identity = JwtSessionIdentity.fromPrincipal(principal);
        if (identity.isEmpty()) {
            if (!(principal instanceof JwtAuthenticationToken)) {
                log.debug("Principal is not a JwtAuthenticationToken — rejecting");
            } else {
                log.debug("JWT accepted but sub claim is missing or blank");
            }
            return Optional.empty();
        }
        log.debug("JWT validated for sub={}", identity.get().sub());
        return identity;
    }
}
