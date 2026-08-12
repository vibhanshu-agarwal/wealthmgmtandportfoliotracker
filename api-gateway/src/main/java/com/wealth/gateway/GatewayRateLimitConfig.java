package com.wealth.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;

/**
 * Configures Redis-backed distributed rate limiting for the gateway.
 *
 * <p>Under the {@code local} profile, a single {@code default-filters} {@code RequestRateLimiter}
 * (configured in {@code application-local.yml}) uses the implicit default {@link RedisRateLimiter}
 * bean supplied by Spring Cloud Gateway's autoconfiguration together with the
 * {@link #userOrIpKeyResolver} bean below.
 *
 * <p>Under {@code prod} profiles, three named {@link RedisRateLimiter} beans —
 * {@link #standardRateLimiter}, {@link #strictRateLimiter}, and {@link #authRateLimiter} — are
 * wired per-route (the first two in {@code application-prod.yml} via
 * {@code #{@standardRateLimiter}} / {@code #{@strictRateLimiter}} SpEL references; the third
 * programmatically by {@link AuthRateLimitFilter}, since {@code /api/auth/**} is a controller
 * endpoint, not a proxied route) replacing the global {@code default-filters} approach so no
 * request is ever charged against more than one bucket.
 */
@Configuration
public class GatewayRateLimitConfig {

  /**
   * Standard-route limiter (portfolio, market-data) — 10 req/s replenish, burst 20, 1 token per
   * request. Values are read with no default: a missing {@code app.rate-limit.standard.*}
   * property under a {@code prod} profile must fail startup (Req 2.5), not silently degrade.
   *
   * <p>Marked {@code @Primary} only so Spring Cloud Gateway's autoconfigured
   * {@code RequestRateLimiterGatewayFilterFactory} (which requires exactly one autowirable
   * {@code RateLimiter} bean for its unused factory-level default) can resolve unambiguously
   * between this bean and {@link #strictRateLimiter}. Every production route explicitly selects
   * its limiter via {@code rate-limiter: "#{@standardRateLimiter}"} /
   * {@code "#{@strictRateLimiter}"}, so this factory-level default is never actually applied to
   * a request — {@code @Primary} exists purely to satisfy bean-resolution ambiguity at startup.
   */
  @Bean
  @Primary
  @Profile("prod")
  RedisRateLimiter standardRateLimiter(
      @Value("${app.rate-limit.standard.replenish-rate}") int replenishRate,
      @Value("${app.rate-limit.standard.burst-capacity}") int burstCapacity,
      @Value("${app.rate-limit.standard.requested-tokens}") int requestedTokens) {
    return new RedisRateLimiter(replenishRate, burstCapacity, requestedTokens);
  }

  /**
   * Strict-route limiter (AI-backed insights/chat) — 1 req/s replenish, burst 30, 6 tokens per
   * request (~10 req/min sustained, burst ~5). Values are read with no default for the same
   * fail-startup-on-misconfiguration reason as {@link #standardRateLimiter}.
   */
  @Bean
  @Profile("prod")
  RedisRateLimiter strictRateLimiter(
      @Value("${app.rate-limit.strict.replenish-rate}") int replenishRate,
      @Value("${app.rate-limit.strict.burst-capacity}") int burstCapacity,
      @Value("${app.rate-limit.strict.requested-tokens}") int requestedTokens) {
    return new RedisRateLimiter(replenishRate, burstCapacity, requestedTokens);
  }

  /**
   * Auth_Bucket (Req 6.3, 6.5): shared by /api/auth/login and /api/auth/signup via a single
   * route id ("auth-bucket"), so combined login+signup volume from one client draws down one
   * bucket. replenishRate=1, requestedTokens=12, burstCapacity=60 -> 12 tokens accrue every 12s
   * (~5/min), burst allowance 60/12 = 5. Retry-After = ceil(12/1) = 12s.
   */
  @Bean
  @Profile("prod")
  RedisRateLimiter authRateLimiter(
      @Value("${app.rate-limit.auth.replenish-rate:1}") int replenishRate,
      @Value("${app.rate-limit.auth.burst-capacity:60}") int burstCapacity,
      @Value("${app.rate-limit.auth.requested-tokens:12}") int requestedTokens) {
    return new RedisRateLimiter(replenishRate, burstCapacity, requestedTokens);
  }

  /**
   * Rate-limit key resolver that uses the authenticated user's sub claim when available, falling
   * back to the client IP address for unauthenticated requests.
   *
   * <p>Reads from {@code exchange.getPrincipal()} rather than the {@code X-User-Id} header to avoid
   * any filter-ordering race condition: Spring Security's WebFilter populates the principal before
   * any GatewayFilter (including RequestRateLimiter) executes.
   *
   * <p>Stays profile-neutral (no {@code @Profile}) so it remains portable and is reused unchanged
   * by both the {@code local} default-filters wiring and the {@code prod} per-route wiring. The
   * {@code app.rate-limit.trust-xff-last-hop} flag (default {@code false}, set to {@code true} only
   * in {@code application-prod.yml}) toggles which IP-fallback strategy is used for unauthenticated
   * requests — see {@link #resolveKey} (local/dev: first X-Forwarded-For hop) vs
   * {@link #resolveTrustedHopKey} (prod: right-most/ingress-appended hop, spoof-resistant).
   */
  @Bean
  KeyResolver userOrIpKeyResolver(
      @Value("${app.rate-limit.trust-xff-last-hop:false}") boolean trustXffLastHop) {
    return exchange ->
        exchange
            .getPrincipal()
            .map(
                principal -> {
                  if (principal instanceof JwtAuthenticationToken jwtToken) {
                    String sub = jwtToken.getToken().getClaimAsString("sub");
                    if (sub != null && !sub.isBlank()) {
                      return sub.trim();
                    }
                  }
                  return resolveClientIp(exchange, trustXffLastHop);
                })
            .defaultIfEmpty(resolveClientIp(exchange, trustXffLastHop));
  }

  private String resolveClientIp(ServerWebExchange exchange, boolean trustXffLastHop) {
    var forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
    var remoteAddress = exchange.getRequest().getRemoteAddress();
    String remoteHost =
        remoteAddress != null && remoteAddress.getAddress() != null
            ? remoteAddress.getAddress().getHostAddress()
            : null;
    return trustXffLastHop
        ? resolveTrustedHopKey(forwardedFor, remoteHost)
        : resolveKey(forwardedFor, remoteHost);
  }

  /**
   * Pure IP-based key-resolution logic — package-private for unit testing without requiring a full
   * ServerWebExchange / codec stack.
   *
   * <p>Called when no authenticated principal is available (unauthenticated requests) and
   * {@code app.rate-limit.trust-xff-last-hop} is {@code false} (local/dev behavior, unchanged):
   * uses the FIRST X-Forwarded-For hop.
   *
   * @param forwardedFor value of the X-Forwarded-For header, or null if absent
   * @param remoteHost remote address host string, or null if unavailable
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

  /**
   * Trusted-hop IP key-resolution logic for production. Uses the RIGHT-MOST (ingress-appended)
   * X-Forwarded-For hop instead of the client-supplied first entry, so a client cannot mint a
   * fresh rate-limit bucket by rotating a spoofed X-Forwarded-For prefix (Req 3.9).
   *
   * <p>Package-private for unit/property testing without a full ServerWebExchange stack.
   *
   * @param forwardedFor value of the X-Forwarded-For header, or null if absent
   * @param remoteHost remote address host string, or null if unavailable
   * @return non-null, non-empty rate-limit key
   */
  static String resolveTrustedHopKey(String forwardedFor, String remoteHost) {
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      var lastComma = forwardedFor.lastIndexOf(',');
      var hop = (lastComma >= 0 ? forwardedFor.substring(lastComma + 1) : forwardedFor).trim();
      if (!hop.isBlank()) {
        return hop;
      }
    }
    if (remoteHost != null && !remoteHost.isBlank()) {
      return remoteHost;
    }
    return "anonymous";
  }
}
