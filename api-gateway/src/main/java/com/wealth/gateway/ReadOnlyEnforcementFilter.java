package com.wealth.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Blocks portfolio/market writes from a read-only (demo) account while allowing the AI routes
 * (Req 7.4-7.7). Ordered after JwtAuthenticationFilter (HIGHEST_PRECEDENCE + 2) so the validated
 * `ro` claim is available on the principal.
 */
@Component
public class ReadOnlyEnforcementFilter implements GlobalFilter, Ordered {

    private static final Set<HttpMethod> MUTATING_METHODS =
            Set.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
    private static final List<String> PROTECTED_PATTERNS = List.of("/api/portfolio/**", "/api/market/**");
    private static final byte[] FORBIDDEN_BODY = ("{\"error\":\"read_only_account\","
            + "\"message\":\"The demo account is read-only.\"}").getBytes(StandardCharsets.UTF_8);

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final List<String> aiAllowlistPatterns;

    public ReadOnlyEnforcementFilter(
            @Value("${app.read-only.ai-allowlist:/api/chat/**,/api/insights/generate/**}")
            List<String> aiAllowlistPatterns) {
        this.aiAllowlistPatterns = aiAllowlistPatterns;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(p -> p instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .flatMap(jwt -> {
                    boolean ro = Boolean.TRUE.equals(jwt.getToken().getClaims().get("ro"));
                    String path = exchange.getRequest().getURI().getPath();
                    HttpMethod method = exchange.getRequest().getMethod();
                    if (decide(ro, method, path)) {
                        return writeForbidden(exchange);
                    }
                    return chain.filter(exchange);
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    /**
     * Pure decision function (Property 6): block iff ro AND mutating method AND protected path
     * AND not AI-allowlisted. Package-visible for property testing.
     */
    boolean decide(boolean ro, HttpMethod method, String path) {
        if (!ro || method == null || !MUTATING_METHODS.contains(method)) {
            return false;
        }
        boolean protectedPath = PROTECTED_PATTERNS.stream().anyMatch(p -> matcher.match(p, path));
        if (!protectedPath) {
            return false;
        }
        boolean aiAllowlisted = aiAllowlistPatterns.stream().anyMatch(p -> matcher.match(p, path));
        return !aiAllowlisted;
    }

    private Mono<Void> writeForbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(FORBIDDEN_BODY);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
