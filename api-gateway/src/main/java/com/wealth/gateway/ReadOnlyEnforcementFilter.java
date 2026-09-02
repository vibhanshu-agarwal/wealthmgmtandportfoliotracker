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

    /**
     * B2 exemptions (Tasks 5.2, 5.3): the two protected writes the demo account is deliberately
     * allowed to make — the Asset Picker composition write and the manual demo reset.
     *
     * <p>Exact method/path pairs, deliberately not Ant patterns: a prefix here would also open
     * every child path (e.g. {@code /api/portfolio/holdings/123}), which is a wider hole than the
     * feature needs. Separate from {@link #aiAllowlistPatterns} because these are a fixed part of
     * the B2 contract, not operator-tunable configuration — narrowing
     * {@code app.read-only.ai-allowlist} must not be able to close them.
     */
    private static final Set<String> B2_EXEMPT_WRITES =
            Set.of("PUT /api/portfolio/holdings", "PUT /api/portfolio/demo-reset");
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
        // Resolve the block/allow decision to a Boolean *before* branching, so the terminal
        // flatMap subscribes exactly one of writeForbidden/chain.filter, exactly once.
        // Composing the Mono<Void> continuations directly (e.g. switchIfEmpty(chain.filter(...)))
        // would re-subscribe the whole downstream chain, because Mono<Void> always completes empty.
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(jwt -> decide(
                        Boolean.TRUE.equals(jwt.getToken().getClaims().get("ro")),
                        exchange.getRequest().getMethod(),
                        exchange.getRequest().getURI().getPath()))
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(blocked -> blocked ? writeForbidden(exchange) : chain.filter(exchange));
    }

    /**
     * Pure decision function (Property 6): block iff ro AND mutating method AND protected path
     * AND not AI-allowlisted AND not a B2 exempt write. Package-visible for property testing.
     */
    boolean decide(boolean ro, HttpMethod method, String path) {
        if (!ro || method == null || !MUTATING_METHODS.contains(method)) {
            return false;
        }
        if (B2_EXEMPT_WRITES.contains(method.name() + " " + path)) {
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
