package com.wealth.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Decorates rate-limiter denial ({@code 429}) responses with a {@code Retry-After} header and a
 * small JSON error body.
 *
 * <p>Stock Spring Cloud Gateway {@code RequestRateLimiterGatewayFilterFactory} short-circuits a
 * throttled request via {@code exchange.getResponse().setComplete()} with an empty body and no
 * {@code Retry-After} header. This {@link GlobalFilter} fills that gap (Req 5.5, 6.6).
 *
 * <p><b>Ordering is essential.</b> This filter must run <em>before</em> the per-route
 * {@code RequestRateLimiter} filter (order {@code Ordered.HIGHEST_PRECEDENCE}, lower than the
 * route filter's order): on denial, {@code RequestRateLimiter} short-circuits and never calls
 * {@code chain.filter(exchange)}, so any filter registered <em>after</em> it in the chain would
 * never run for the 429s it needs to decorate. Running first means this filter installs a response
 * decorator on the way <em>in</em>, before the limiter runs, so the decorator is already in place
 * when the limiter later denies the request.
 *
 * <p>The exact time-to-next-token is not recoverable from the stock {@code RedisRateLimiter} Lua
 * script (it returns only {@code allowed}/{@code tokens_left}, not the bucket refill timestamp), so
 * the {@code Retry-After} value is a per-route-class approximation:
 * {@code ceil(requestedTokens / replenishRate)} seconds — read from the route's
 * {@code retry-after-seconds} metadata (set per route in {@code application-prod.yml}), falling
 * back to the strict (larger, safer) value if the metadata is missing or mis-typed.
 *
 * <p><b>Known assumption.</b> This decorator only overrides {@link ServerHttpResponse#setComplete()}
 * because that is how the stock {@code RequestRateLimiterGatewayFilterFactory} currently denies a
 * request: it sets the {@code 429} status and calls {@code setComplete()} directly, without ever
 * calling {@code writeWith(...)}. If a future Spring Cloud Gateway version changes
 * {@code RequestRateLimiterGatewayFilterFactory} to deny via {@code writeWith(...)} instead (or in
 * addition), this decorator would need a matching {@code writeWith} override, or the
 * {@code Retry-After} header/JSON body would silently stop being attached to 429s.
 * {@code ProductionRateLimitingIntegrationTest} pins today's {@code setComplete()}-based behavior,
 * so a Spring Cloud Gateway upgrade that changes this would fail that test rather than fail
 * silently.
 */
@Component
@Profile("prod")
public class RateLimitDenialResponseCustomizer implements GlobalFilter, Ordered {

  static final String RETRY_AFTER_METADATA_KEY = "retry-after-seconds";

  /**
   * Fallback used when a route is throttled but carries no (or an unparsable)
   * {@code retry-after-seconds} metadata value. Defaults to the strict route's value — the larger,
   * safer number — so a client waiting slightly longer is never told to retry too soon.
   */
  static final int DEFAULT_FALLBACK_RETRY_AFTER_SECONDS = 6;

  /** HTTP header name — not exposed as a constant on Spring's {@link HttpHeaders}. */
  static final String RETRY_AFTER_HEADER = "Retry-After";

  @Override
  public int getOrder() {
    // Must run before the per-route RequestRateLimiter filter (order 1, assigned by
    // RouteDefinitionRouteLocator for the single filter defined per route).
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpResponse originalResponse = exchange.getResponse();
    ServerHttpResponse decoratedResponse =
        new ServerHttpResponseDecorator(originalResponse) {
          @Override
          public Mono<Void> setComplete() {
            HttpStatusCode status = getStatusCode();
            if (status != null && status.value() == 429 && !isCommitted()) {
              return writeRateLimitedBody(exchange, this);
            }
            return super.setComplete();
          }
        };

    return chain.filter(exchange.mutate().response(decoratedResponse).build());
  }

  private Mono<Void> writeRateLimitedBody(ServerWebExchange exchange, ServerHttpResponse response) {
    int retryAfterSeconds = resolveRetryAfterSeconds(exchange);

    response.getHeaders().set(RETRY_AFTER_HEADER, String.valueOf(retryAfterSeconds));
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

    byte[] body =
        String.format(
                Locale.ROOT,
                "{\"error\":\"rate_limited\",\"message\":\"Rate limit exceeded\",\"retryAfterSeconds\":%d}",
                retryAfterSeconds)
            .getBytes(StandardCharsets.UTF_8);
    DataBuffer buffer = response.bufferFactory().wrap(body);
    response.getHeaders().setContentLength(body.length);

    return response.writeWith(Mono.just(buffer));
  }

  /**
   * Resolves the {@code Retry-After} value for the matched route from its
   * {@code retry-after-seconds} metadata, falling back to
   * {@link #DEFAULT_FALLBACK_RETRY_AFTER_SECONDS} when the route/metadata is absent or the value
   * cannot be parsed as a non-negative integer.
   */
  static int resolveRetryAfterSeconds(ServerWebExchange exchange) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (route == null) {
      return DEFAULT_FALLBACK_RETRY_AFTER_SECONDS;
    }
    Map<String, Object> metadata = route.getMetadata();
    Object value = metadata.get(RETRY_AFTER_METADATA_KEY);
    return parseRetryAfterSeconds(value);
  }

  /**
   * Pure parsing logic — package-private for unit testing without a full ServerWebExchange /
   * Route stack. Accepts {@link Number} (as bound from YAML integers) or a numeric {@link String};
   * anything else, or a negative value, falls back to the safe default.
   */
  static int parseRetryAfterSeconds(Object metadataValue) {
    if (metadataValue instanceof Number number) {
      int seconds = number.intValue();
      return seconds >= 0 ? seconds : DEFAULT_FALLBACK_RETRY_AFTER_SECONDS;
    }
    if (metadataValue instanceof String s) {
      try {
        int seconds = Integer.parseInt(s.trim());
        return seconds >= 0 ? seconds : DEFAULT_FALLBACK_RETRY_AFTER_SECONDS;
      } catch (NumberFormatException ex) {
        return DEFAULT_FALLBACK_RETRY_AFTER_SECONDS;
      }
    }
    return DEFAULT_FALLBACK_RETRY_AFTER_SECONDS;
  }
}
