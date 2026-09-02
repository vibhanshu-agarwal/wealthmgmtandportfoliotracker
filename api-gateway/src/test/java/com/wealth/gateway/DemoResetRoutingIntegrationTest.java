package com.wealth.gateway;

import static com.wealth.gateway.DemoResetGatewayFixtures.EXPECTED_REPLICA_TOKEN;
import static com.wealth.gateway.DemoResetGatewayFixtures.HOLDINGS_PATH;
import static com.wealth.gateway.DemoResetGatewayFixtures.INTERNAL_KEY_HEADER;
import static com.wealth.gateway.DemoResetGatewayFixtures.INTERNAL_RESET_PATH;
import static com.wealth.gateway.DemoResetGatewayFixtures.PORTFOLIO_ROUTE_ID;
import static com.wealth.gateway.DemoResetGatewayFixtures.REPLICA_TOKEN_HEADER;
import static com.wealth.gateway.DemoResetGatewayFixtures.RESET_BODY;
import static com.wealth.gateway.DemoResetGatewayFixtures.RESET_PATH;
import static com.wealth.gateway.DemoResetGatewayFixtures.RESET_ROUTE_ID;
import static com.wealth.gateway.DemoResetGatewayFixtures.TEST_INTERNAL_KEY;
import static com.wealth.gateway.DemoResetGatewayFixtures.USER_ID_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.gateway.DemoResetGatewayFixtures.Capture;
import com.wealth.gateway.DemoResetGatewayFixtures.RecordingPortfolioStub;
import com.wealth.gateway.DemoResetGatewayFixtures.RouteProbe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-transport proof for the B2 manual demo-reset gateway path under the {@code local} profile
 * (Tasks 5.2, 5.3, 5.4, 5.5).
 *
 * <p>This exercises the actual route list from {@code application.yml}, the real Spring Security
 * chain, and the real global filter chain — only the downstream destination, the JWT secret and
 * the two provider values are overridden. Requests go over real HTTP to a recording stub, so
 * "the rewrite happened", "the credentials were swapped" and "the replica token is authoritative
 * at commit" are observed rather than assumed.
 *
 * <p>Scope boundary: this proves TRANSPORT, ROUTING and AUTHORIZATION. It proves nothing about
 * demo-portfolio persistence — portfolio-service's own integration tests own that, and the Task
 * 4.5 evidence owns the live no-op proof.
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(DemoResetGatewayFixtures.ProviderOverrides.class)
@ActiveProfiles({"local", "demo-reset-local"})
class DemoResetRoutingIntegrationTest {

    private static final int REDIS_PORT = 6379;

    private static RecordingPortfolioStub stub;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) throws IOException {
        if (stub == null) {
            stub = RecordingPortfolioStub.start();
        }
        registry.add("app.routes.portfolio-url", stub::baseUrl);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.close();
            stub = null;
        }
    }

    @LocalServerPort int port;

    @Autowired RouteProbe routeProbe;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        stub.reset();
        routeProbe.reset();
        webTestClient =
                WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port)
                        .responseTimeout(Duration.ofSeconds(10))
                        .build();
    }

    // ── Happy path: route, rewrite, credential swap, token ────────────────────

    @Test
    void demoResetIsRoutedRewrittenAndCredentialSwapped() {
        EntityExchangeResult<byte[]> result =
                putReset(
                                DemoResetGatewayFixtures.demoToken(),
                                headers -> {
                                    headers.add(INTERNAL_KEY_HEADER, "attacker-key-one");
                                    headers.add(INTERNAL_KEY_HEADER, "attacker-key-two");
                                    headers.add(USER_ID_HEADER, "spoofed-user");
                                })
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult();

        assertThat(routeProbe.onlyMatchedRouteId())
                .as("the reset must win the dedicated route, not /api/portfolio/**")
                .isEqualTo(RESET_ROUTE_ID);

        Capture capture = stub.onlyCapture();
        assertThat(capture.path()).isEqualTo(INTERNAL_RESET_PATH);
        assertThat(capture.method()).isEqualTo("PUT");
        assertThat(capture.body())
                .as("the request body, including expectedVersion, is forwarded byte-for-byte")
                .isEqualTo(RESET_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(capture.headers().get(INTERNAL_KEY_HEADER))
                .as("exactly one trusted key, replacing every caller-supplied value")
                .containsExactly(TEST_INTERNAL_KEY);
        assertThat(capture.headers().get(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(capture.headers().get(USER_ID_HEADER)).isNull();

        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                .containsExactly(EXPECTED_REPLICA_TOKEN);
    }

    // ── Authorization ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "non-demo subject {0} (ro={1}) is forbidden")
    @CsvSource({
        TestJwtFactory.SEED_USER_ID + ",true",
        TestJwtFactory.SEED_USER_ID + ",false",
        "00000000-0000-0000-0000-000000000e2e,true",
        "00000000-0000-0000-0000-000000000e2e,false",
    })
    void authenticatedNonDemoSubjectIsForbiddenAndNeverReachesTheStub(String sub, boolean readOnly) {
        String token =
                readOnly
                        ? DemoResetGatewayFixtures.readOnlyToken(sub)
                        : DemoResetGatewayFixtures.writableToken(sub);

        EntityExchangeResult<byte[]> result =
                putReset(token, headers -> {})
                        .expectStatus()
                        .isForbidden()
                        .expectHeader()
                        .contentType(MediaType.APPLICATION_JSON)
                        .expectBody()
                        .returnResult();

        assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"error\":\"demo_reset_forbidden\","
                                + "\"message\":\"Only the demo account may reset the demo portfolio.\"}");
        assertThat(stub.callCount()).isZero();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                .containsExactly(EXPECTED_REPLICA_TOKEN);
    }

    @Test
    void missingJwtIsRejectedByTheSecurityChainBeforeThisFilterRuns() {
        EntityExchangeResult<byte[]> result =
                webTestClient
                        .put()
                        .uri(RESET_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(RESET_BODY)
                        .exchange()
                        .expectStatus()
                        .isUnauthorized()
                        .expectBody()
                        .returnResult();

        assertThat(stub.callCount()).isZero();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                .as("a request the reset filter never reaches owes no replica token")
                .isNull();
    }

    @Test
    void invalidJwtIsRejectedByTheSecurityChainBeforeThisFilterRuns() {
        String expired =
                TestJwtFactory.mint(TestJwtFactory.DEMO_USER_ID, Duration.ofSeconds(-1));

        EntityExchangeResult<byte[]> result =
                putReset(expired, headers -> {})
                        .expectStatus()
                        .isUnauthorized()
                        .expectBody()
                        .returnResult();

        assertThat(stub.callCount()).isZero();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER)).isNull();
    }

    // ── Downstream pass-through and header ownership ─────────────────────────

    @ParameterizedTest(name = "downstream {0} is passed through verbatim")
    @ValueSource(ints = {409, 503})
    void downstreamFailuresArePassedThroughWithoutRetryOrNormalisation(int downstreamStatus) {
        // A single-field upstream 503 must stay upstream output — it must NOT be rewritten into
        // the gateway's own two-field internal_api_key_not_configured envelope, which Wave 8
        // diagnostics rely on to mean "the gateway has no key".
        String downstreamBody = "{\"error\":\"version_conflict\"}";
        stub.respondWith(downstreamStatus, downstreamBody);

        EntityExchangeResult<byte[]> result =
                putReset(DemoResetGatewayFixtures.demoToken(), headers -> {})
                        .expectStatus()
                        .isEqualTo(downstreamStatus)
                        .expectBody()
                        .returnResult();

        assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                .isEqualTo(downstreamBody);
        assertThat(stub.callCount())
                .as("no retry, and no extra version read")
                .isEqualTo(1);
        assertThat(stub.onlyCapture().path()).isEqualTo(INTERNAL_RESET_PATH);
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                .containsExactly(EXPECTED_REPLICA_TOKEN);
    }

    @ParameterizedTest(name = "conflicting downstream replica headers lose on a {0}")
    @ValueSource(ints = {200, 409})
    void gatewayReplicaTokenWinsOverConflictingDownstreamHeaders(int downstreamStatus) {
        stub.respondWith(downstreamStatus, "{\"downstream\":true}");
        stub.addResponseHeader(REPLICA_TOKEN_HEADER, "downstream-a", "downstream-b");

        EntityExchangeResult<byte[]> result =
                putReset(DemoResetGatewayFixtures.demoToken(), headers -> {})
                        .expectStatus()
                        .isEqualTo(downstreamStatus)
                        .expectBody()
                        .returnResult();

        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                .as("the gateway token is authoritative at commit, exactly once")
                .containsExactly(EXPECTED_REPLICA_TOKEN);
    }

    // ── Everything else is untouched ─────────────────────────────────────────

    @Test
    void compositionPutIsAllowedForTheDemoAccountButGetsNoInternalKey() {
        EntityExchangeResult<byte[]> result =
                webTestClient
                        .put()
                        .uri(HOLDINGS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + DemoResetGatewayFixtures.demoToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"holdings\":[]}")
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult();

        assertThat(routeProbe.onlyMatchedRouteId()).isEqualTo(PORTFOLIO_ROUTE_ID);
        Capture capture = stub.onlyCapture();
        assertThat(capture.path())
                .as("the composition write is not rewritten onto an internal path")
                .isEqualTo(HOLDINGS_PATH);
        assertThat(capture.headers().get(INTERNAL_KEY_HEADER))
                .as("the reset filter must not hand an internal key to any other route")
                .isNull();
        assertThat(capture.headers().get(USER_ID_HEADER))
                .as("ordinary identity injection is unchanged")
                .containsExactly(TestJwtFactory.DEMO_USER_ID);
        assertThat(capture.headers().get(HttpHeaders.AUTHORIZATION)).isNotNull();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER)).isNull();
    }

    @Test
    void postToTheResetPathIsStillBlockedByReadOnlyEnforcement() {
        EntityExchangeResult<byte[]> result =
                webTestClient
                        .post()
                        .uri(RESET_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + DemoResetGatewayFixtures.demoToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(RESET_BODY)
                        .exchange()
                        .expectStatus()
                        .isForbidden()
                        .expectBody()
                        .returnResult();

        assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                .as("the B2 exemption is PUT-only — POST still hits read-only enforcement")
                .isEqualTo(
                        "{\"error\":\"read_only_account\","
                                + "\"message\":\"The demo account is read-only.\"}");
        assertThat(stub.callCount()).isZero();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER)).isNull();
    }

    /**
     * Near-miss paths must not inherit the reset treatment. For the demo account they are simply
     * protected writes again, so read-only enforcement — which runs ahead of routing — stops them
     * with its own envelope. Whether the Path predicate would have tolerated the trailing slash
     * is therefore not load-bearing, and this pins that independently of it.
     */
    @ParameterizedTest(name = "PUT {0} is not treated as a reset")
    @ValueSource(strings = {"/api/portfolio/demo-reset/", "/api/portfolio/demo-reset/extra"})
    void neighbouringResetPathsGetNoResetTreatment(String path) {
        EntityExchangeResult<byte[]> result =
                webTestClient
                        .put()
                        .uri(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + DemoResetGatewayFixtures.demoToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(RESET_BODY)
                        .exchange()
                        .expectStatus()
                        .isForbidden()
                        .expectBody()
                        .returnResult();

        assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"error\":\"read_only_account\","
                                + "\"message\":\"The demo account is read-only.\"}");
        assertThat(stub.callCount()).isZero();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER)).isNull();
    }

    /**
     * The same near-miss paths for a WRITABLE account get past read-only enforcement, so this is
     * the case that would actually expose an over-broad rewrite or key injection: they must be
     * proxied as ordinary portfolio traffic, with no internal key attached.
     */
    @ParameterizedTest(name = "writable PUT {0} is proxied without an internal key")
    @ValueSource(strings = {"/api/portfolio/demo-reset/", "/api/portfolio/demo-reset/extra"})
    void neighbouringResetPathsNeverReceiveAnInternalKey(String path) {
        EntityExchangeResult<byte[]> result =
                webTestClient
                        .put()
                        .uri(path)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + DemoResetGatewayFixtures.writableToken(TestJwtFactory.SEED_USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(RESET_BODY)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult();

        Capture capture = stub.onlyCapture();
        assertThat(capture.path())
                .as("a near-miss path must not be rewritten onto the internal endpoint")
                .isEqualTo(path);
        assertThat(capture.headers().get(INTERNAL_KEY_HEADER)).isNull();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER)).isNull();
    }

    @Test
    void getOnTheResetPathTakesTheOrdinaryPortfolioRoute() {
        EntityExchangeResult<byte[]> result =
                webTestClient
                        .get()
                        .uri(RESET_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + DemoResetGatewayFixtures.demoToken())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .returnResult();

        assertThat(routeProbe.onlyMatchedRouteId()).isEqualTo(PORTFOLIO_ROUTE_ID);
        assertThat(stub.onlyCapture().path())
                .as("only PUT is rewritten onto the internal path")
                .isEqualTo(RESET_PATH);
        assertThat(stub.onlyCapture().headers().get(INTERNAL_KEY_HEADER)).isNull();
        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER)).isNull();
    }

    // ── Unconfigured internal key (separate context, same containers) ────────

    /**
     * The gateway's own "no internal key" failure, proven through the real chain rather than only
     * in isolation: it must be the pinned two-field envelope, must not reach the stub, and must
     * still carry the authoritative replica token.
     */
    @Nested
    @TestPropertySource(properties = "test.internal-api-key=")
    class UnconfiguredInternalKey {

        @LocalServerPort int nestedPort;

        WebTestClient nestedClient;

        @BeforeEach
        void setUpNested() {
            stub.reset();
            nestedClient =
                    WebTestClient.bindToServer()
                            .baseUrl("http://localhost:" + nestedPort)
                            .responseTimeout(Duration.ofSeconds(10))
                            .build();
        }

        @Test
        void missingInternalKeyYieldsThePinnedTwoFieldEnvelope() {
            EntityExchangeResult<byte[]> result =
                    nestedClient
                            .put()
                            .uri(RESET_PATH)
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    "Bearer " + DemoResetGatewayFixtures.demoToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(RESET_BODY)
                            .exchange()
                            .expectStatus()
                            .isEqualTo(503)
                            .expectHeader()
                            .contentType(MediaType.APPLICATION_JSON)
                            .expectBody()
                            .returnResult();

            assertThat(new String(result.getResponseBodyContent(), StandardCharsets.UTF_8))
                    .isEqualTo(
                            "{\"error\":\"internal_api_key_not_configured\","
                                    + "\"message\":\"The demo reset feature is temporarily unavailable.\"}");
            assertThat(stub.callCount()).isZero();
            assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                    .containsExactly(EXPECTED_REPLICA_TOKEN);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WebTestClient.ResponseSpec putReset(
            String token, java.util.function.Consumer<HttpHeaders> extraHeaders) {
        return webTestClient
                .put()
                .uri(RESET_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(extraHeaders)
                .bodyValue(RESET_BODY)
                .exchange();
    }
}
