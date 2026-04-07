package com.wealth.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_BUCKETS_BEFORE_CLEANUP = 10_000;

    private final int maxRequests;
    private final long windowMs;

    private final Map<String, CounterWindow> counters = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong(0);

    RequestRateLimitFilter(
            @Value("${gateway.rate-limit.max-requests:120}") int maxRequests,
            @Value("${gateway.rate-limit.window-seconds:60}") int windowSeconds
    ) {
        this.maxRequests = maxRequests;
        this.windowMs = windowSeconds * 1000L;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String key = resolveClientIp(request);
        CounterWindow window = counters.computeIfAbsent(key, ignored -> new CounterWindow(now));

        int currentCount;
        long retryAfterSeconds = 1L;
        synchronized (window) {
            long elapsed = now - window.windowStartMs;
            if (elapsed >= windowMs) {
                window.windowStartMs = now;
                window.count.set(0);
                elapsed = 0;
            }

            currentCount = window.count.incrementAndGet();
            if (currentCount > maxRequests) {
                retryAfterSeconds = Math.max(1L, (windowMs - elapsed + 999L) / 1000L);
            }
        }

        maybeCleanup(now);

        if (currentCount > maxRequests) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.getWriter().write("{\"error\":\"rate_limit_exceeded\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            return (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
        }
        return request.getRemoteAddr();
    }

    private void maybeCleanup(long now) {
        long n = calls.incrementAndGet();
        if (counters.size() < MAX_BUCKETS_BEFORE_CLEANUP || n % 1000 != 0) {
            return;
        }

        counters.entrySet().removeIf(entry -> (now - entry.getValue().windowStartMs) >= (windowMs * 2));
    }

    private static final class CounterWindow {
        private volatile long windowStartMs;
        private final AtomicInteger count = new AtomicInteger(0);

        private CounterWindow(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}
