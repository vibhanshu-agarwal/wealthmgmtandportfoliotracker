package com.wealth.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayRateLimitConfig {

    /**
     * Rate-limit key resolver that uses the authenticated user's sub claim when available,
     * falling back to the client IP address for unauthenticated requests.
     *
     * <p>Reads from {@code exchange.getPrincipal()} rather than the {@code X-User-Id} header
     * to avoid any filter-ordering race condition: Spring Security's WebFilter populates the
     * principal before any GatewayFilter (including RequestRateLimiter) executes.
     */
    @Bean
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> {
                    if (principal instanceof JwtAuthenticationToken jwtToken) {
                        String sub = jwtToken.getToken().getClaimAsString("sub");
                        if (sub != null && !sub.isBlank()) {
                            return sub.trim();
                        }
                    }
                    return resolveClientIp(exchange);
                })
                .defaultIfEmpty(resolveClientIp(exchange));
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        var forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        var remoteAddress = exchange.getRequest().getRemoteAddress();
        return resolveKey(
                forwardedFor,
                remoteAddress != null && remoteAddress.getAddress() != null
                        ? remoteAddress.getAddress().getHostAddress()
                        : null);
    }

    /**
     * Pure IP-based key-resolution logic — package-private for unit testing without
     * requiring a full ServerWebExchange / codec stack.
     *
     * <p>Called when no authenticated principal is available (unauthenticated requests).
     *
     * @param forwardedFor value of the X-Forwarded-For header, or null if absent
     * @param remoteHost   remote address host string, or null if unavailable
     * @return non-null, non-empty rate-limit key
     */
    static String resolveKey(String forwardedFor, String remoteHost) {
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            var comma = forwardedFor.indexOf(',');
            return (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
        }
        if (remoteHost != null && !remoteHost.isBlank()) {
            return remoteHost;
        }
        return "anonymous";
    }
}
