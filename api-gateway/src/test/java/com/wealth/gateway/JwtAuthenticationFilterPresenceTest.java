package com.wealth.gateway;

import com.wealth.gateway.presence.DemoPresenceService;
import com.wealth.gateway.presence.JwtSessionIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterPresenceTest {

    private DemoPresenceService demoPresenceService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        demoPresenceService = mock(DemoPresenceService.class);
        filter = new JwtAuthenticationFilter(demoPresenceService);
    }

    @Test
    void demoTokenWithJti_schedulesBackgroundTouchAndForwardsOnce() {
        AtomicInteger subscriptions = new AtomicInteger();
        MockServerWebExchange delegate =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));
        ServerWebExchange exchange = withJwtPrincipal(
                delegate, DemoPresenceService.DEMO_USER_ID, TestJwtFactory.TEST_JTI_A);

        StepVerifier.create(filter.filter(exchange, countingChain(subscriptions)))
                .verifyComplete();

        assertThat(subscriptions).hasValue(1);
        verify(demoPresenceService).scheduleBackgroundTouch(argThat(JwtSessionIdentity::hasPresenceSession));
    }

    @Test
    void neverCompletingTouchStillForwardsOnceWithoutWaiting() {
        doAnswer(invocation -> {
            demoPresenceService.touch(invocation.getArgument(0))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe();
            return null;
        }).when(demoPresenceService).scheduleBackgroundTouch(any());
        when(demoPresenceService.touch(any())).thenReturn(Mono.never());

        AtomicInteger subscriptions = new AtomicInteger();
        MockServerWebExchange delegate =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));
        ServerWebExchange exchange = withJwtPrincipal(
                delegate, DemoPresenceService.DEMO_USER_ID, TestJwtFactory.TEST_JTI_A);

        StepVerifier.create(filter.filter(exchange, countingChain(subscriptions)))
                .verifyComplete();

        assertThat(subscriptions).hasValue(1);
        assertThat(delegate.getResponse().getStatusCode()).isNull();
    }

    @Test
    void nonDemoTokenNeverSchedulesBackgroundTouch() {
        AtomicInteger subscriptions = new AtomicInteger();
        MockServerWebExchange delegate =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));
        ServerWebExchange exchange = withJwtPrincipal(
                delegate, TestJwtFactory.SEED_USER_ID, TestJwtFactory.TEST_JTI_A);

        StepVerifier.create(filter.filter(exchange, countingChain(subscriptions)))
                .verifyComplete();

        assertThat(subscriptions).hasValue(1);
        verify(demoPresenceService, never()).scheduleBackgroundTouch(any());
    }

    @Test
    void legacyNoJtiTokenNeverSchedulesBackgroundTouch() {
        AtomicInteger subscriptions = new AtomicInteger();
        MockServerWebExchange delegate =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));
        ServerWebExchange exchange = withJwtPrincipal(delegate, DemoPresenceService.DEMO_USER_ID, null);

        StepVerifier.create(filter.filter(exchange, countingChain(subscriptions)))
                .verifyComplete();

        assertThat(subscriptions).hasValue(1);
        verify(demoPresenceService, never()).scheduleBackgroundTouch(any());
    }

    @Test
    void legacyBlankJtiTokenNeverSchedulesBackgroundTouch() {
        AtomicInteger subscriptions = new AtomicInteger();
        MockServerWebExchange delegate =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/api/portfolio"));
        ServerWebExchange exchange = withJwtPrincipal(delegate, DemoPresenceService.DEMO_USER_ID, "");

        StepVerifier.create(filter.filter(exchange, countingChain(subscriptions)))
                .verifyComplete();

        assertThat(subscriptions).hasValue(1);
        verify(demoPresenceService, never()).scheduleBackgroundTouch(any());
    }

    private static GatewayFilterChain countingChain(AtomicInteger subscriptions) {
        return exchange ->
                Mono.<Void>empty().doOnSubscribe(s -> subscriptions.incrementAndGet());
    }

    private static ServerWebExchange withJwtPrincipal(ServerWebExchange delegate, String sub, String jti) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (jti != null) {
            builder.claim("jti", jti);
        }
        JwtAuthenticationToken token = new JwtAuthenticationToken(builder.build());
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
