package com.wealth.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class GatewayRateLimitConfig {

    @Bean
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(resolveClientIp(exchange));
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        var headers = exchange.getRequest().getHeaders();
        var forwardedFor = headers.getFirst("X-Forwarded-For");

        // Extracts first IP from X-Forwarded-For header
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            var comma = forwardedFor.indexOf(',');
            return (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
        }

        var remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }

        return "anonymous";
    }
}
