package com.wealth.gateway;

import com.wealth.gateway.presence.DemoPresenceService;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the number of times {@link JwtAuthenticationFilter} subscribes the downstream {@link
 * GatewayFilterChain}.
 *
 * <p>{@code chain.filter(mutated)} returns {@code Mono<Void>}, which always completes empty.
 * Composing it with {@code switchIfEmpty(...)} directly (as the pre-fix code did) therefore
 * re-runs the fallback branch after every successful request too — logging a spurious "No
 * principal found on exchange — rejecting request" and calling {@code setStatusCode(401)} /
 * {@code setComplete()} a second time after the response may already be committed. Mirrors {@link
 * ReadOnlyEnforcementFilterChainTest}, the sibling regression test for the same defect class.
 */
class JwtAuthenticationFilterChainTest {

  private final DemoPresenceService demoPresenceService = Mockito.mock(DemoPresenceService.class);
  private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(demoPresenceService);

  JwtAuthenticationFilterChainTest() {
    // Background presence dispatch is off the request critical path; chain tests ignore it.
  }

  @Test
  void validJwtSubscribesDownstreamChainExactlyOnceAndInjectsUserId() {
    AtomicInteger subscriptions = new AtomicInteger();
    AtomicHeaderCapture capture = new AtomicHeaderCapture();
    MockServerWebExchange delegate =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));

    StepVerifier.create(
            filter.filter(
                withJwtPrincipal(delegate, "00000000-0000-0000-0000-000000000001"),
                countingChain(subscriptions, capture)))
        .verifyComplete();

    assertThat(subscriptions).hasValue(1);
    assertThat(capture.xUserId).isEqualTo("00000000-0000-0000-0000-000000000001");
    // The pre-fix switchIfEmpty fallback fires unconditionally (chain.filter() returns Mono<Void>,
    // which always completes empty, regardless of which flatMap branch ran) and overwrites the
    // response with 401 even after a successful pass-through. The mock chain above never touches
    // the response itself, so any non-null status here proves the fallback ran a second time.
    assertThat(delegate.getResponse().getStatusCode()).isNull();
  }

  @Test
  void blankSubClaimNeverSubscribesDownstreamChainAndReturns401() {
    AtomicInteger subscriptions = new AtomicInteger();
    MockServerWebExchange delegate =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));

    StepVerifier.create(
            filter.filter(
                withJwtPrincipal(delegate, ""), countingChain(subscriptions, new AtomicHeaderCapture())))
        .verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(delegate.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void nonJwtPrincipalNeverSubscribesDownstreamChainAndReturns401() {
    AtomicInteger subscriptions = new AtomicInteger();
    MockServerWebExchange delegate =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));
    ServerWebExchange exchange = withNonJwtPrincipal(delegate);

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions, new AtomicHeaderCapture())))
        .verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(delegate.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void noPrincipalAtAllNeverSubscribesDownstreamChainAndReturns401() {
    AtomicInteger subscriptions = new AtomicInteger();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions, new AtomicHeaderCapture())))
        .verifyComplete();

    assertThat(subscriptions).hasValue(0);
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void permitAllPathSubscribesDownstreamChainExactlyOnceWithoutPrincipal() {
    AtomicInteger subscriptions = new AtomicInteger();
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.POST, "/api/auth/login"));

    StepVerifier.create(filter.filter(exchange, countingChain(subscriptions, new AtomicHeaderCapture())))
        .verifyComplete();

    assertThat(subscriptions).hasValue(1);
  }

  private static final class AtomicHeaderCapture {
    volatile String xUserId;
  }

  private static GatewayFilterChain countingChain(
      AtomicInteger subscriptions, AtomicHeaderCapture capture) {
    return exchange ->
        Mono.<Void>empty()
            .doOnSubscribe(
                s -> {
                  subscriptions.incrementAndGet();
                  capture.xUserId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
                });
  }

  private static ServerWebExchange withJwtPrincipal(ServerWebExchange delegate, String sub) {
    Jwt jwt =
        Jwt.withTokenValue("test-token")
            .header("alg", "HS256")
            .claim("sub", sub)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
    return decoratedWithPrincipal(delegate, token);
  }

  private static ServerWebExchange withNonJwtPrincipal(ServerWebExchange delegate) {
    TestingAuthenticationToken token = new TestingAuthenticationToken("someone", "n/a");
    return decoratedWithPrincipal(delegate, token);
  }

  private static ServerWebExchange decoratedWithPrincipal(
      ServerWebExchange delegate, Principal principal) {
    return new ServerWebExchangeDecorator(delegate) {
      @Override
      @SuppressWarnings("unchecked")
      public <T extends Principal> Mono<T> getPrincipal() {
        return (Mono<T>) Mono.just(principal);
      }
    };
  }
}
