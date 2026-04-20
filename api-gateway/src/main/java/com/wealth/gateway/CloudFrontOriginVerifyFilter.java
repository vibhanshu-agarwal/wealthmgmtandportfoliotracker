package com.wealth.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * LURL (Lambda URL) Security Filter.
 *
 * <p>CloudFront injects the {@code X-Origin-Verify} header on every request to the
 * api-gateway Lambda Function URL. This filter validates that header against the
 * {@code CLOUDFRONT_ORIGIN_SECRET} environment variable, rejecting any request that
 * bypasses CloudFront and hits the Function URL directly.
 *
 * <p>This filter runs at {@link Ordered#HIGHEST_PRECEDENCE} — before JWT authentication —
 * so unauthenticated probes are rejected cheaply without touching the JWT validation path.
 *
 * <p>On successful validation the header is stripped from the forwarded request so the
 * secret never leaks to downstream services or appears in application logs.
 *
 * <p>When {@code CLOUDFRONT_ORIGIN_SECRET} is not set (local development), the filter
 * is a no-op and all requests pass through.
 */
@Component
public class CloudFrontOriginVerifyFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CloudFrontOriginVerifyFilter.class);
    private static final String ORIGIN_VERIFY_HEADER = "X-Origin-Verify";

    private final String expectedSecret;

    public CloudFrontOriginVerifyFilter() {
        String secret = System.getenv("CLOUDFRONT_ORIGIN_SECRET");
        this.expectedSecret = (secret != null && !secret.isBlank()) ? secret : null;
    }

    /**
     * Highest precedence — runs before all other filters including JWT authentication.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // No-op in local development (secret not configured)
        if (expectedSecret == null) {
            return chain.filter(exchange);
        }

        // Bypass for the Golden-State E2E seeder. CI callers reach /api/internal/** without
        // going through CloudFront, so X-Origin-Verify is never set; downstream services gate
        // these paths on X-Internal-Api-Key instead (design doc \u00a7 7).
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/api/internal/")) {
            return chain.filter(exchange);
        }

        String headerValue = exchange.getRequest().getHeaders().getFirst(ORIGIN_VERIFY_HEADER);

        if (!expectedSecret.equals(headerValue)) {
            log.debug("CloudFront origin verification failed — rejecting request");
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        // Secret matches — strip the header before forwarding (prevent leakage)
        ServerWebExchange sanitised = exchange.mutate()
                .request(r -> r.headers(h -> h.remove(ORIGIN_VERIFY_HEADER)))
                .build();

        return chain.filter(sanitised);
    }
}
