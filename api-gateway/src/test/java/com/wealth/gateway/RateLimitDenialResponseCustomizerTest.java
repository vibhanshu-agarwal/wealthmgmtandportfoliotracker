package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link RateLimitDenialResponseCustomizer}.
 *
 * <p>Covers Property 9 (design.md): for any request the gateway rejects with {@code 429} under a
 * production profile, the response carries a parseable, non-negative {@code Retry-After} header
 * and a non-empty JSON body indicating the limit was exceeded — while non-429 responses are left
 * untouched.
 */
class RateLimitDenialResponseCustomizerTest {

    private final RateLimitDenialResponseCustomizer filter = new RateLimitDenialResponseCustomizer();

    // ── resolveRetryAfterSeconds / parseRetryAfterSeconds: pure parsing logic ────

    @Test
    void resolveRetryAfterSecondsFallsBackToDefaultWhenNoRouteAttribute() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/portfolio/x"));

        int seconds = RateLimitDenialResponseCustomizer.resolveRetryAfterSeconds(exchange);

        assertThat(seconds).isEqualTo(RateLimitDenialResponseCustomizer.DEFAULT_FALLBACK_RETRY_AFTER_SECONDS);
    }

    @Test
    void resolveRetryAfterSecondsReadsIntegerMetadataFromMatchedRoute() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/portfolio/x"));
        Route route = Route.async()
                .id("portfolio-service")
                .uri("http://localhost:8081")
                .predicate(ex -> true)
                .metadata(RateLimitDenialResponseCustomizer.RETRY_AFTER_METADATA_KEY, 1)
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

        int seconds = RateLimitDenialResponseCustomizer.resolveRetryAfterSeconds(exchange);

        assertThat(seconds).isEqualTo(1);
    }

    @Test
    void resolveRetryAfterSecondsReadsStrictRouteMetadata() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/insights/x"));
        Route route = Route.async()
                .id("insight-service")
                .uri("http://localhost:8083")
                .predicate(ex -> true)
                .metadata(RateLimitDenialResponseCustomizer.RETRY_AFTER_METADATA_KEY, 6)
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

        int seconds = RateLimitDenialResponseCustomizer.resolveRetryAfterSeconds(exchange);

        assertThat(seconds).isEqualTo(6);
    }

    @ParameterizedTest(name = "metadataValue=\"{0}\" -> {1}")
    @CsvSource({
        "1, 1",
        "6, 6",
        "0, 0"
    })
    void parseRetryAfterSecondsAcceptsNonNegativeNumbers(int input, int expected) {
        assertThat(RateLimitDenialResponseCustomizer.parseRetryAfterSeconds(input)).isEqualTo(expected);
    }

    @Test
    void parseRetryAfterSecondsAcceptsNumericString() {
        assertThat(RateLimitDenialResponseCustomizer.parseRetryAfterSeconds("6")).isEqualTo(6);
    }

    @Test
    void parseRetryAfterSecondsFallsBackOnNegativeNumber() {
        assertThat(RateLimitDenialResponseCustomizer.parseRetryAfterSeconds(-1))
                .isEqualTo(RateLimitDenialResponseCustomizer.DEFAULT_FALLBACK_RETRY_AFTER_SECONDS);
    }

    @Test
    void parseRetryAfterSecondsFallsBackOnUnparsableString() {
        assertThat(RateLimitDenialResponseCustomizer.parseRetryAfterSeconds("not-a-number"))
                .isEqualTo(RateLimitDenialResponseCustomizer.DEFAULT_FALLBACK_RETRY_AFTER_SECONDS);
    }

    @Test
    void parseRetryAfterSecondsFallsBackOnNullOrOtherType() {
        assertThat(RateLimitDenialResponseCustomizer.parseRetryAfterSeconds(null))
                .isEqualTo(RateLimitDenialResponseCustomizer.DEFAULT_FALLBACK_RETRY_AFTER_SECONDS);
        assertThat(RateLimitDenialResponseCustomizer.parseRetryAfterSeconds(new Object()))
                .isEqualTo(RateLimitDenialResponseCustomizer.DEFAULT_FALLBACK_RETRY_AFTER_SECONDS);
    }

    // ── filter(): end-to-end decoration behavior via a fake downstream chain ────

    @Test
    void decoratesA429WithRetryAfterHeaderAndJsonBody() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/insights/x"));
        Route route = Route.async()
                .id("insight-service")
                .uri("http://localhost:8083")
                .predicate(ex -> true)
                .metadata(RateLimitDenialResponseCustomizer.RETRY_AFTER_METADATA_KEY, 6)
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);

        // Simulate the downstream RequestRateLimiter filter denying the request.
        Mono<Void> result = filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return ex.getResponse().setComplete();
        });

        // The decorated response's setComplete() call is triggered inside the fake chain above;
        // writeWith publishes the body through the mock response's internal buffer, which we can
        // read back via exchange.getResponse() (MockServerHttpResponse buffers written content).
        result.block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("6");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        MockServerHttpResponse mockResponse = (MockServerHttpResponse) exchange.getResponse();
        String body = mockResponse.getBodyAsString().block();
        assertThat(body).contains("\"error\":\"rate_limited\"")
                .contains("\"retryAfterSeconds\":6");
    }

    @Test
    void doesNotTouchAllowedNonThrottledResponses() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/portfolio/x"));

        Mono<Void> result = filter.filter(exchange, ex -> {
            ex.getResponse().setStatusCode(HttpStatus.OK);
            return ex.getResponse().setComplete();
        });
        result.block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isNull();
    }

    @Test
    void orderIsHighestPrecedenceSoItRunsBeforeRequestRateLimiter() {
        assertThat(filter.getOrder()).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
    }
}
