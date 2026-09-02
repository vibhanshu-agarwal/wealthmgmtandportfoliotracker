package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Guards the number of times {@link ReadOnlyEnforcementFilter} subscribes the downstream
 * {@link GatewayFilterChain}.
 *
 * <p>{@code chain.filter(exchange)} returns {@code Mono<Void>}, which always completes empty.
 * Composing it with {@code switchIfEmpty(chain.filter(exchange))} therefore re-subscribes the whole
 * downstream chain on the success path, proxying twice and mutating already-committed response
 * headers — which truncates the HTTP/1.1 chunked response before its terminating chunk. The
 * sibling {@link ReadOnlyEnforcementFilterPropertyTest} only exercises the pure {@code decide()}
 * function, so it cannot catch this.
 */
class ReadOnlyEnforcementFilterChainTest {

  private final ReadOnlyEnforcementFilter filter =
      new ReadOnlyEnforcementFilter(List.of("/api/chat/**", "/api/insights/generate/**"));

  @Test
  void allowedJwtRequestSubscribesDownstreamChainExactlyOnce() {
    AtomicInteger subscriptions = new AtomicInteger();

    StepVerifier.create(
            filter.filter(
                exchangeWithJwt(false, HttpMethod.GET, "/api/portfolio"), countingChain(subscriptions)))
        .verifyComplete();

    assertThat(subscriptions).hasValue(1);
  }

  @Test
  void readOnlyAccountReadSubscribesDownstreamChainExactlyOnce() {
    AtomicInteger subscriptions = new AtomicInteger();

    StepVerifier.create(
            filter.filter(
                exchangeWithJwt(true, HttpMethod.GET, "/api/portfolio"), countingChain(subscriptions)))
        .verifyComplete();

    assertThat(subscriptions).hasValue(1);
  }

  @Test
  void unauthenticatedRequestSubscribesDownstreamChainExactlyOnce() {
    AtomicInteger subscriptions = new AtomicInteger();
    ServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions))).verifyComplete();

    assertThat(subscriptions).hasValue(1);
  }

  /**
   * The B2 exemptions ({@code PUT /api/portfolio/holdings}, {@code PUT
   * /api/portfolio/demo-reset}) are a new "allowed" branch through {@code decide()}. They must
   * reach the downstream chain exactly once, like every other allowed request — an exemption
   * implemented as an extra composed continuation rather than a decision value would double-
   * subscribe here.
   */
  @ParameterizedTest(name = "exempt PUT {0} subscribes downstream exactly once")
  @ValueSource(strings = {"/api/portfolio/holdings", "/api/portfolio/demo-reset"})
  void b2ExemptWriteSubscribesDownstreamChainExactlyOnce(String path) {
    AtomicInteger subscriptions = new AtomicInteger();

    StepVerifier.create(
            filter.filter(exchangeWithJwt(true, HttpMethod.PUT, path), countingChain(subscriptions)))
        .verifyComplete();

    assertThat(subscriptions).hasValue(1);
  }

  @Test
  void blockedWriteNeverSubscribesDownstreamChainAndReturns403() {
    AtomicInteger subscriptions = new AtomicInteger();
    MockServerWebExchange delegate =
        MockServerWebExchange.from(
            MockServerHttpRequest.method(HttpMethod.POST, "/api/portfolio/holdings"));

    StepVerifier.create(
            filter.filter(withJwtPrincipal(delegate, true), countingChain(subscriptions)))
        .verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(delegate.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  private static GatewayFilterChain countingChain(AtomicInteger subscriptions) {
    return exchange -> Mono.<Void>empty().doOnSubscribe(s -> subscriptions.incrementAndGet());
  }

  private static ServerWebExchange exchangeWithJwt(
      boolean readOnly, HttpMethod method, String path) {
    return withJwtPrincipal(
        MockServerWebExchange.from(MockServerHttpRequest.method(method, path)), readOnly);
  }

  private static ServerWebExchange withJwtPrincipal(ServerWebExchange delegate, boolean readOnly) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .claim("sub", "00000000-0000-0000-0000-000000000001")
            .claim("ro", readOnly)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);

    return new ServerWebExchangeDecorator(delegate) {
      @Override
      @SuppressWarnings("unchecked")
      public <T extends Principal> Mono<T> getPrincipal() {
        return (Mono<T>) Mono.just(token);
      }
    };
  }
}
