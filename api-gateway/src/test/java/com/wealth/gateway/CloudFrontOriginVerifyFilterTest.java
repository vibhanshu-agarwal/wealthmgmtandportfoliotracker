package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Full security regression matrix for {@link CloudFrontOriginVerifyFilter}, written as part of
 * Task 8.2a because no prior test exercised this filter at all. Guards both the filter's existing
 * security behavior (matching/missing/wrong header, the {@code /api/internal/} bypass, the
 * unconfigured no-op, header stripping, ordering) and the constructor-injection refactor that
 * moves the {@code CLOUDFRONT_ORIGIN_SECRET} read into {@link CloudFrontOriginSecretProvider}.
 * Assertions run after reactive subscription (via {@link StepVerifier}), not merely after
 * constructing the {@code Mono}, and capture the actual exchange delivered downstream rather than
 * inferring mutation from the original exchange, since the filter forwards a mutated exchange on
 * the successful configured path.
 */
class CloudFrontOriginVerifyFilterTest {

  private static final String HEADER = "X-Origin-Verify";
  private static final String SECRET = "test-origin-secret-fixture";

  @Test
  void configuredMatchingHeaderSubscribesChainOnceAndStripsHeader() {
    AtomicInteger subscriptions = new AtomicInteger();
    AtomicReference<ServerWebExchange> delivered = new AtomicReference<>();
    CloudFrontOriginVerifyFilter filter = filterWith(SECRET);
    ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio")
                .header(HEADER, SECRET)
                .header("X-Unrelated", "keep-me")
                .build());

    StepVerifier.create(filter.filter(exchange, capturingChain(subscriptions, delivered)))
        .verifyComplete();

    assertThat(subscriptions).hasValue(1);
    ServerWebExchange forwarded = delivered.get();
    assertThat(forwarded.getRequest().getHeaders().get(HEADER)).isNull();
    assertThat(forwarded.getRequest().getHeaders().getFirst("X-Unrelated")).isEqualTo("keep-me");
    assertThat(forwarded.getRequest().getURI().getPath()).isEqualTo("/api/portfolio");
  }

  @Test
  void configuredMissingHeaderReturns403AndNeverSubscribesChain() {
    AtomicInteger subscriptions = new AtomicInteger();
    CloudFrontOriginVerifyFilter filter = filterWith(SECRET);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(exchange.getResponse().isCommitted()).isTrue();
  }

  @Test
  void configuredWrongHeaderReturns403AndNeverSubscribesChain() {
    AtomicInteger subscriptions = new AtomicInteger();
    CloudFrontOriginVerifyFilter filter = filterWith(SECRET);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio")
                .header(HEADER, "wrong-value")
                .build());

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void configuredInternalPathBypassesCheckAndLeavesHeaderUntouched() {
    AtomicInteger subscriptions = new AtomicInteger();
    AtomicReference<ServerWebExchange> delivered = new AtomicReference<>();
    CloudFrontOriginVerifyFilter filter = filterWith(SECRET);
    ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/api/internal/portfolio/demo-reset")
                .header(HEADER, "irrelevant-because-bypassed")
                .build());

    StepVerifier.create(filter.filter(exchange, capturingChain(subscriptions, delivered)))
        .verifyComplete();

    assertThat(subscriptions).hasValue(1);
    assertThat(delivered.get().getRequest().getHeaders().getFirst(HEADER))
        .isEqualTo("irrelevant-because-bypassed");
  }

  @Test
  void configuredInternalPathBypassWithNoHeaderAtAllStillSubscribesChainOnce() {
    AtomicInteger subscriptions = new AtomicInteger();
    CloudFrontOriginVerifyFilter filter = filterWith(SECRET);
    ServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.GET, "/api/internal/portfolio"));

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(1);
  }

  @Test
  void configuredConfusablePrefixDoesNotBypassAndMissingHeaderReturns403() {
    AtomicInteger subscriptions = new AtomicInteger();
    CloudFrontOriginVerifyFilter filter = filterWith(SECRET);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.GET, "/api/internality/x"));

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void configuredConfusablePrefixDoesNotBypassAndWrongHeaderReturns403() {
    AtomicInteger subscriptions = new AtomicInteger();
    CloudFrontOriginVerifyFilter filter = filterWith(SECRET);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.GET, "/api/internality/x")
                .header(HEADER, "wrong-value")
                .build());

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @ParameterizedTest
  @MethodSource("blankSecrets")
  void unconfiguredProviderSubscribesChainOnceWithoutInspectingHeader(String blankSecret) {
    AtomicInteger subscriptions = new AtomicInteger();
    CloudFrontOriginVerifyFilter filter = filterWith(blankSecret);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(1);
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @ParameterizedTest
  @MethodSource("blankSecrets")
  void unconfiguredProviderPassesRequestEvenWithWrongHeaderPresent(String blankSecret) {
    AtomicInteger subscriptions = new AtomicInteger();
    CloudFrontOriginVerifyFilter filter = filterWith(blankSecret);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio")
                .header(HEADER, "anything")
                .build());

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(1);
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void orderIsHighestPrecedence() {
    assertThat(filterWith(SECRET).getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
  }

  private static Stream<Arguments> blankSecrets() {
    // U+2003 EM SPACE is not ASCII but is recognized by String.isBlank() (Character.isWhitespace).
    return Stream.of(
        Arguments.of((Object) null),
        Arguments.of(""),
        Arguments.of("   "),
        Arguments.of("\u2003"));
  }

  private static CloudFrontOriginVerifyFilter filterWith(String secret) {
    return new CloudFrontOriginVerifyFilter(new CloudFrontOriginSecretProvider(secret));
  }

  private static GatewayFilterChain countingChain(AtomicInteger subscriptions) {
    return exchange -> Mono.<Void>empty().doOnSubscribe(s -> subscriptions.incrementAndGet());
  }

  private static GatewayFilterChain capturingChain(
      AtomicInteger subscriptions, AtomicReference<ServerWebExchange> delivered) {
    return exchange ->
        Mono.<Void>empty()
            .doOnSubscribe(
                s -> {
                  subscriptions.incrementAndGet();
                  delivered.set(exchange);
                });
  }
}
