package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Isolated branch, ordering and subscription tests for {@link DemoResetAuthorizationFilter}
 * (B2 Tasks 5.1, 5.3a, 5.5).
 *
 * <p>These exercise the filter in isolation against a mock exchange — they prove the
 * authorization decision, the credential replacement it performs on the outbound request, the
 * pinned failure envelopes and the replica-token response header. They deliberately do NOT prove
 * routing, the real security chain, or downstream persistence: {@link
 * DemoResetRoutingIntegrationTest} and {@link DemoResetProductionRoutingIntegrationTest} own the
 * real transport proof, and portfolio-service's own integration tests own persistence.
 */
class DemoResetAuthorizationFilterTest {

    private static final String RESET_PATH = "/api/portfolio/demo-reset";
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";
    private static final String REPLICA_TOKEN_HEADER = "X-Gateway-Replica-Token";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROUTE_ID = "demo-reset-manual";

    /** Fixed synthetic replica vector from Task 5.1b: hashes to {@link #EXPECTED_TOKEN}. */
    private static final String REPLICA_NAME = "api-gateway--0000000-abcdefg";

    private static final String EXPECTED_TOKEN = "95ca17821ade";

    private static final String CONFIGURED_KEY = "test-internal-key";

    private static final String FORBIDDEN_BODY =
            "{\"error\":\"demo_reset_forbidden\","
                    + "\"message\":\"Only the demo account may reset the demo portfolio.\"}";

    private static final String UNAVAILABLE_BODY =
            "{\"error\":\"internal_api_key_not_configured\","
                    + "\"message\":\"The demo reset feature is temporarily unavailable.\"}";

    private static DemoResetAuthorizationFilter filterWith(String internalKey, String replicaName) {
        return new DemoResetAuthorizationFilter(
                new InternalApiKeyProvider(internalKey), new ReplicaTokenProvider(replicaName));
    }

    private static DemoResetAuthorizationFilter configuredFilter() {
        return filterWith(CONFIGURED_KEY, REPLICA_NAME);
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    /**
     * Must run after JwtAuthenticationFilter (HIGHEST_PRECEDENCE + 2) so a validated principal is
     * present, and after ReadOnlyEnforcementFilter (HIGHEST_PRECEDENCE + 3) so the read-only
     * exception it depends on has already been applied.
     */
    @Test
    void runsImmediatelyAfterTheReadOnlyEnforcementFilter() {
        assertThat(configuredFilter().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 4);
        assertThat(configuredFilter().getOrder())
                .isGreaterThan(new ReadOnlyEnforcementFilter(java.util.List.of()).getOrder());
    }

    // ── Scope: only (PUT, /api/portfolio/demo-reset) is touched ──────────────

    @Nested
    class OutOfScopeRequests {

        @ParameterizedTest(name = "{0} {1} passes through untouched")
        @CsvSource({
            "GET,/api/portfolio/demo-reset",
            "POST,/api/portfolio/demo-reset",
            "PUT,/api/portfolio/holdings",
            "PUT,/api/portfolio/demo-reset/",
            "PUT,/api/portfolio/demo-reset/extra",
            "PUT,/api/portfolio/demo-resets",
            "PUT,/api/internal/portfolio/demo-reset",
        })
        void passesThroughWithoutAddingCredentialsOrHeaders(String method, String path) {
            AtomicInteger subscriptions = new AtomicInteger();
            AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
            MockServerWebExchange delegate =
                    MockServerWebExchange.from(
                            MockServerHttpRequest.method(HttpMethod.valueOf(method), path)
                                    .header(HttpHeaders.AUTHORIZATION, "Bearer caller-token")
                                    .header(INTERNAL_KEY_HEADER, "caller-supplied"));
            ServerWebExchange exchange = withRoute(withDemoPrincipal(delegate), ROUTE_ID);

            StepVerifier.create(
                            configuredFilter()
                                    .filter(exchange, capturingChain(subscriptions, forwarded)))
                    .verifyComplete();

            assertThat(subscriptions).hasValue(1);
            assertThat(forwarded.get().get(INTERNAL_KEY_HEADER))
                    .as("this filter must not inject or replace the internal key off-target")
                    .containsExactly("caller-supplied");
            assertThat(forwarded.get().getFirst(HttpHeaders.AUTHORIZATION))
                    .as("stripping Authorization is scoped to the reset request only")
                    .isEqualTo("Bearer caller-token");
            assertThat(delegate.getResponse().getHeaders().get(REPLICA_TOKEN_HEADER))
                    .as("the replica token is only owned for outcomes this filter produces")
                    .isNull();
        }
    }

    // ── Route authorization ──────────────────────────────────────────────────

    @Test
    void missingRouteAttributeIsRejectedWithoutSubscribingDownstream() {
        Outcome outcome = run(configuredFilter(), resetRequest(), null, TestJwtFactory.DEMO_USER_ID);

        assertForbidden(outcome);
    }

    @ParameterizedTest(name = "matched route id \"{0}\" is rejected")
    @ValueSource(strings = {"portfolio-service", "internal-portfolio-seed", "demo-reset", ""})
    void wrongMatchedRouteIdIsRejectedWithoutSubscribingDownstream(String routeId) {
        Outcome outcome =
                run(configuredFilter(), resetRequest(), routeId, TestJwtFactory.DEMO_USER_ID);

        assertForbidden(outcome);
    }

    // ── Subject authorization ────────────────────────────────────────────────

    @ParameterizedTest(name = "subject \"{0}\" is rejected")
    @ValueSource(
            strings = {
                TestJwtFactory.SEED_USER_ID,
                "00000000-0000-0000-0000-000000000e2e",
                "00000000-0000-0000-0000-0000000D3110",
                "00000000-0000-0000-0000-0000000d311",
            })
    void authenticatedNonDemoSubjectIsRejected(String subject) {
        Outcome outcome = run(configuredFilter(), resetRequest(), ROUTE_ID, subject);

        assertForbidden(outcome);
    }

    @Test
    void readOnlyClaimValueDoesNotWidenAuthorization() {
        for (Boolean readOnly : new Boolean[] {Boolean.TRUE, Boolean.FALSE}) {
            Outcome outcome =
                    run(
                            configuredFilter(),
                            resetRequest(),
                            ROUTE_ID,
                            TestJwtFactory.SEED_USER_ID,
                            readOnly);

            assertForbidden(outcome);
        }
    }

    @Test
    void missingPrincipalFailsClosed() {
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
        MockServerWebExchange delegate = MockServerWebExchange.from(resetRequest());
        ServerWebExchange exchange = withRoute(delegate, ROUTE_ID);

        StepVerifier.create(
                        configuredFilter().filter(exchange, capturingChain(subscriptions, forwarded)))
                .verifyComplete();

        assertForbidden(new Outcome(delegate, subscriptions, forwarded));
    }

    // ── Provider configuration ───────────────────────────────────────────────

    @Test
    void nullInternalKeyYieldsThePinnedUnavailableEnvelope() {
        assertUnavailable(
                run(
                        filterWith(null, REPLICA_NAME),
                        resetRequest(),
                        ROUTE_ID,
                        TestJwtFactory.DEMO_USER_ID));
    }

    @ParameterizedTest(name = "blank internal key [{index}] yields 503")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " ", "　", " \t\n "})
    void blankInternalKeyYieldsThePinnedUnavailableEnvelope(String blankKey) {
        assertUnavailable(
                run(
                        filterWith(blankKey, REPLICA_NAME),
                        resetRequest(),
                        ROUTE_ID,
                        TestJwtFactory.DEMO_USER_ID));
    }

    // ── Authorized path: credential replacement ──────────────────────────────

    @Test
    void authorizedRequestSubscribesDownstreamExactlyOnceWithReplacedCredentials() {
        Outcome outcome =
                run(
                        configuredFilter(),
                        MockServerHttpRequest.method(HttpMethod.PUT, RESET_PATH)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer demo-token")
                                .header(USER_ID_HEADER, "spoofed-user")
                                .header(INTERNAL_KEY_HEADER, "malicious-one")
                                .header(INTERNAL_KEY_HEADER, "malicious-two"),
                        ROUTE_ID,
                        TestJwtFactory.DEMO_USER_ID);

        assertThat(outcome.subscriptions()).hasValue(1);
        HttpHeaders forwarded = outcome.forwardedHeaders();
        assertThat(forwarded.get(INTERNAL_KEY_HEADER))
                .as("exactly one trusted internal key replaces every caller-supplied value")
                .containsExactly(CONFIGURED_KEY);
        assertThat(forwarded.get(HttpHeaders.AUTHORIZATION))
                .as("the end-user credential must never reach the internal endpoint")
                .isNull();
        assertThat(forwarded.get(USER_ID_HEADER))
                .as("no caller-controlled identity reaches the internal endpoint")
                .isNull();
        assertThat(outcome.replicaTokenValues()).containsExactly(EXPECTED_TOKEN);
    }

    @Test
    void nonBlankInternalKeyBytesArePreservedExactly() {
        String awkwardKey = " kéy-with spaces ";

        Outcome outcome =
                run(
                        filterWith(awkwardKey, REPLICA_NAME),
                        resetRequest(),
                        ROUTE_ID,
                        TestJwtFactory.DEMO_USER_ID);

        assertThat(outcome.forwardedHeaders().get(INTERNAL_KEY_HEADER))
                .containsExactly(awkwardKey);
    }

    @Test
    void authorizedRequestLeavesTheOriginalPathAndBodyForTheRouteToRewrite() {
        Outcome outcome =
                run(
                        configuredFilter(),
                        MockServerHttpRequest.method(HttpMethod.PUT, RESET_PATH),
                        ROUTE_ID,
                        TestJwtFactory.DEMO_USER_ID);

        assertThat(outcome.forwardedRequestPath())
                .as("path rewriting belongs to the route, not this filter")
                .isEqualTo(RESET_PATH);
    }

    // ── Replica-token ownership ──────────────────────────────────────────────

    @Test
    void replicaTokenReplacesAnyDownstreamSuppliedValuesAtCommit() {
        AtomicInteger subscriptions = new AtomicInteger();
        MockServerWebExchange delegate = MockServerWebExchange.from(resetRequest());
        ServerWebExchange exchange =
                withRoute(withDemoPrincipal(delegate, TestJwtFactory.DEMO_USER_ID, true), ROUTE_ID);

        // Emulates NettyRoutingFilter copying downstream response headers onto the gateway
        // response *after* this filter ran, then the response being committed.
        GatewayFilterChain downstreamConflict =
                ex -> {
                    subscriptions.incrementAndGet();
                    ex.getResponse().getHeaders().add(REPLICA_TOKEN_HEADER, "downstream-a");
                    ex.getResponse().getHeaders().add(REPLICA_TOKEN_HEADER, "downstream-b");
                    ex.getResponse().setStatusCode(HttpStatus.CONFLICT);
                    return ex.getResponse().setComplete();
                };

        StepVerifier.create(configuredFilter().filter(exchange, downstreamConflict)).verifyComplete();

        assertThat(subscriptions).hasValue(1);
        assertThat(delegate.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(delegate.getResponse().getHeaders().get(REPLICA_TOKEN_HEADER))
                .as("the gateway token is authoritative at commit, exactly once")
                .containsExactly(EXPECTED_TOKEN);
    }

    /**
     * A replica name the platform never supplied yields {@link ReplicaTokenProvider}'s empty
     * sentinel. The filter must forward that sentinel verbatim rather than inventing a value or
     * leaking the raw replica name.
     */
    @ParameterizedTest(name = "blank replica name [{index}] preserves the empty sentinel")
    @ValueSource(strings = {"", "   "})
    void blankReplicaProviderSentinelIsPreserved(String blankReplicaName) {
        Outcome outcome =
                run(
                        filterWith(CONFIGURED_KEY, blankReplicaName),
                        resetRequest(),
                        ROUTE_ID,
                        TestJwtFactory.DEMO_USER_ID);

        assertThat(outcome.replicaTokenValues()).containsExactly("");
    }

    @Test
    void nullReplicaProviderSentinelIsPreserved() {
        Outcome outcome =
                run(
                        filterWith(CONFIGURED_KEY, null),
                        resetRequest(),
                        ROUTE_ID,
                        TestJwtFactory.DEMO_USER_ID);

        assertThat(outcome.replicaTokenValues()).containsExactly("");
    }

    @Test
    void rawReplicaNameIsNeverEmitted() {
        Outcome outcome =
                run(configuredFilter(), resetRequest(), ROUTE_ID, TestJwtFactory.DEMO_USER_ID);

        assertThat(outcome.replicaTokenValues()).doesNotContain(REPLICA_NAME);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void assertForbidden(Outcome outcome) {
        assertThat(outcome.subscriptions())
                .as("a rejected reset must never subscribe the downstream chain")
                .hasValue(0);
        assertThat(outcome.response().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(outcome.response().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(outcome.bodyAsString()).isEqualTo(FORBIDDEN_BODY);
        assertThat(outcome.replicaTokenValues()).containsExactly(EXPECTED_TOKEN);
    }

    private static void assertUnavailable(Outcome outcome) {
        assertThat(outcome.subscriptions())
                .as("an unconfigured key must never subscribe the downstream chain")
                .hasValue(0);
        assertThat(outcome.response().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(outcome.response().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(outcome.bodyAsString()).isEqualTo(UNAVAILABLE_BODY);
        assertThat(outcome.replicaTokenValues()).containsExactly(EXPECTED_TOKEN);
    }

    private static MockServerHttpRequest.BaseBuilder<?> resetRequest() {
        return MockServerHttpRequest.method(HttpMethod.PUT, RESET_PATH);
    }

    private static Outcome run(
            DemoResetAuthorizationFilter filter,
            MockServerHttpRequest.BaseBuilder<?> request,
            String routeId,
            String subject) {
        return run(filter, request, routeId, subject, Boolean.TRUE);
    }

    private static Outcome run(
            DemoResetAuthorizationFilter filter,
            MockServerHttpRequest.BaseBuilder<?> request,
            String routeId,
            String subject,
            Boolean readOnly) {
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
        AtomicReference<String> forwardedPath = new AtomicReference<>();
        MockServerWebExchange delegate = MockServerWebExchange.from(request);
        ServerWebExchange exchange =
                withRoute(withDemoPrincipal(delegate, subject, readOnly), routeId);

        StepVerifier.create(
                        filter.filter(
                                exchange, capturingChain(subscriptions, forwarded, forwardedPath)))
                .verifyComplete();

        return new Outcome(delegate, subscriptions, forwarded, forwardedPath);
    }

    private record Outcome(
            MockServerWebExchange exchange,
            AtomicInteger subscriptions,
            AtomicReference<HttpHeaders> forwarded,
            AtomicReference<String> forwardedPath) {

        Outcome(
                MockServerWebExchange exchange,
                AtomicInteger subscriptions,
                AtomicReference<HttpHeaders> forwarded) {
            this(exchange, subscriptions, forwarded, new AtomicReference<>());
        }

        org.springframework.mock.http.server.reactive.MockServerHttpResponse response() {
            return exchange.getResponse();
        }

        HttpHeaders forwardedHeaders() {
            return forwarded.get();
        }

        String forwardedRequestPath() {
            return forwardedPath.get();
        }

        java.util.List<String> replicaTokenValues() {
            java.util.List<String> values = response().getHeaders().get(REPLICA_TOKEN_HEADER);
            return values == null ? java.util.List.of() : values;
        }

        String bodyAsString() {
            return response().getBodyAsString().block();
        }
    }

    private static GatewayFilterChain capturingChain(
            AtomicInteger subscriptions, AtomicReference<HttpHeaders> forwarded) {
        return capturingChain(subscriptions, forwarded, new AtomicReference<>());
    }

    /**
     * Records the forwarded request and commits the response, so {@code beforeCommit} callbacks
     * registered by the filter under test actually fire — exactly as they do once
     * NettyWriteResponseFilter writes a real downstream response.
     */
    private static GatewayFilterChain capturingChain(
            AtomicInteger subscriptions,
            AtomicReference<HttpHeaders> forwarded,
            AtomicReference<String> forwardedPath) {
        return exchange ->
                Mono.defer(
                        () -> {
                            subscriptions.incrementAndGet();
                            forwarded.set(exchange.getRequest().getHeaders());
                            forwardedPath.set(exchange.getRequest().getURI().getPath());
                            return exchange.getResponse().setComplete();
                        });
    }

    private static ServerWebExchange withRoute(ServerWebExchange exchange, String routeId) {
        if (routeId != null) {
            exchange
                    .getAttributes()
                    .put(
                            GATEWAY_ROUTE_ATTR,
                            Route.async()
                                    .id(routeId)
                                    .uri("http://localhost:8081")
                                    .predicate(ex -> true)
                                    .build());
        }
        return exchange;
    }

    private static ServerWebExchange withDemoPrincipal(ServerWebExchange delegate) {
        return withDemoPrincipal(delegate, TestJwtFactory.DEMO_USER_ID, true);
    }

    private static ServerWebExchange withDemoPrincipal(
            ServerWebExchange delegate, String subject, Boolean readOnly) {
        Jwt jwt =
                Jwt.withTokenValue("test-token")
                        .header("alg", "HS256")
                        .claim("sub", subject)
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
