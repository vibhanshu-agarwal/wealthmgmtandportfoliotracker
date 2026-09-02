package com.wealth.gateway;

import static com.wealth.gateway.DemoResetGatewayFixtures.EXPECTED_REPLICA_TOKEN;
import static com.wealth.gateway.DemoResetGatewayFixtures.INTERNAL_KEY_HEADER;
import static com.wealth.gateway.DemoResetGatewayFixtures.INTERNAL_RESET_PATH;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-transport proof for the manual demo-reset route under {@code prod}/{@code azure}
 * (B2 Tasks 5.2, 5.4, 5.5).
 *
 * <p>The production route list REPLACES the profile-neutral one wholesale, so the base
 * {@code application.yml} entry proven by {@link DemoResetRoutingIntegrationTest} says nothing
 * about production. This suite runs against the real {@code application-prod.yml} list, the real
 * {@code standardRateLimiter} bean and real Redis, and asserts three things that only exist
 * here: the production route is selected and rewritten, it OWNS the standard limiter (it is not
 * accidentally exempt like {@code /api/internal/**}), and the replica-token header stays
 * authoritative even on a limiter-generated denial.
 *
 * <p>Follows {@link ProductionRateLimitingIntegrationTest}'s container and burst-capacity
 * conventions.
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(DemoResetGatewayFixtures.ProviderOverrides.class)
@ActiveProfiles({"prod", "azure"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoResetProductionRoutingIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String INTERNAL_SEED_PATH = "/api/internal/portfolio/seed";

    /** Small burst keeps the throttling proof fast, matching ProductionRateLimitingIntegrationTest. */
    private static final int STANDARD_BURST = 3;

    /**
     * Every authorized reset keys off the same JWT sub — the demo account is the only subject
     * allowed to reset — so the functional tests share one bucket. Bounded retries absorb that,
     * exactly as {@code ProductionRateLimitingIntegrationTest#awaitThrottledResponse} absorbs
     * replenishment drift in the other direction. A denied request never reaches the stub, so
     * retrying leaves the recorded downstream traffic untouched.
     */
    private static final int MAX_ALLOWED_WAIT_ATTEMPTS = 40;

    private static final int MAX_THROTTLE_WAIT_ATTEMPTS = 30;

    private static RecordingPortfolioStub stub;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    // application-prod.yml declares spring.datasource.* with no defaults (Req 2.5 — a missing
    // value must fail startup loudly), and GatewayAuthDataConfig's @ConditionalOnProperty gate
    // resolves it during context refresh. An ephemeral Postgres exists purely to satisfy that
    // resolution — same rationale as ProductionRateLimitingIntegrationTest.
    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static final org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry registry) throws IOException {
        if (stub == null) {
            stub = RecordingPortfolioStub.start();
        }
        registry.add("app.routes.portfolio-url", stub::baseUrl);
        registry.add(
                "spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.timeout", () -> "3s");
        registry.add("spring.data.redis.connect-timeout", () -> "3s");

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);

        registry.add("app.rate-limit.standard.replenish-rate", () -> 1);
        registry.add("app.rate-limit.standard.burst-capacity", () -> STANDARD_BURST);
        registry.add("app.rate-limit.standard.requested-tokens", () -> 1);

        registry.add("app.rate-limit.strict.replenish-rate", () -> 1);
        registry.add("app.rate-limit.strict.burst-capacity", () -> 2);
        registry.add("app.rate-limit.strict.requested-tokens", () -> 1);
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

    // ── Production routing ───────────────────────────────────────────────────

    @Test
    @Order(1)
    void productionRouteRewritesAndSwapsCredentials() {
        EntityExchangeResult<byte[]> result = awaitAllowedReset();

        assertThat(routeProbe.matchedRouteIds())
                .as("the production list must select the dedicated reset route")
                .contains(RESET_ROUTE_ID);

        Capture capture = stub.onlyCapture();
        assertThat(capture.path()).isEqualTo(INTERNAL_RESET_PATH);
        assertThat(capture.method()).isEqualTo("PUT");
        assertThat(capture.body()).isEqualTo(RESET_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(capture.headers().get(INTERNAL_KEY_HEADER)).containsExactly(TEST_INTERNAL_KEY);
        assertThat(capture.headers().get(HttpHeaders.AUTHORIZATION)).isNull();
        assertThat(capture.headers().get(USER_ID_HEADER)).isNull();

        assertThat(result.getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                .containsExactly(EXPECTED_REPLICA_TOKEN);
    }

    @Test
    @Order(2)
    void productionRouteCarriesTheStandardLimiterRetryMetadata() {
        // The reset route resolves to the standardRateLimiter, whose configured math here is
        // ceil(requestedTokens / replenishRate) = 1 — the same Retry-After the other standard
        // routes emit. RateLimitConfigurationGuardrailTest pins the YAML side of that equality;
        // this pins the runtime side for THIS route.
        EntityExchangeResult<String> throttled = awaitThrottledReset().denial();

        assertThat(throttled.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(throttled.getResponseHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(throttled.getResponseBody())
                .contains("\"error\":\"rate_limited\"")
                .contains("\"retryAfterSeconds\":1");
    }

    @Test
    @Order(3)
    void throttledResetNeverReachesTheStubButStillCarriesTheAuthoritativeToken() {
        DrainResult drain = awaitThrottledReset();

        // The oracle is the count of attempts the limiter ADMITTED, tallied independently of the
        // stub. Equality proves every denial stopped at the gateway: had even one 429 been
        // proxied, the stub would have recorded more calls than the limiter admitted.
        assertThat(stub.callCount())
                .as("a rate-limited reset must not be proxied downstream")
                .isEqualTo(drain.allowedAttempts());
        assertThat(drain.deniedAttempts())
                .as("this test is vacuous unless at least one request was actually denied")
                .isPositive();
        assertThat(drain.denial().getResponseHeaders().get(REPLICA_TOKEN_HEADER))
                .as("the filter registered its header before the limiter denied the request")
                .containsExactly(EXPECTED_REPLICA_TOKEN);
    }

    @Test
    @Order(4)
    void internalSeedRoutesRemainExemptFromRateLimiting() {
        List<Integer> statuses = new ArrayList<>();

        // Flood well beyond the standard burst, unauthenticated — /api/internal/** carries no
        // JWT and no RequestRateLimiter filter, and adding the reset route must not change that.
        for (int i = 0; i < STANDARD_BURST * 4; i++) {
            webTestClient
                    .post()
                    .uri(INTERNAL_SEED_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus()
                    .value(statuses::add);
        }

        assertThat(statuses).as("no request to an exempt route should be rate-limited")
                .noneMatch(status -> status == 429);
    }

    @Test
    @Order(5)
    void nonDemoSubjectIsForbiddenUnderProductionToo() {
        EntityExchangeResult<byte[]> result =
                put(RESET_PATH, DemoResetGatewayFixtures.readOnlyToken(TestJwtFactory.SEED_USER_ID))
                        .expectStatus()
                        .isForbidden()
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private WebTestClient.ResponseSpec put(String path, String token) {
        return webTestClient
                .put()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(RESET_BODY)
                .exchange();
    }

    /** Retries until this shared demo bucket admits a request, then returns that response. */
    private EntityExchangeResult<byte[]> awaitAllowedReset() {
        EntityExchangeResult<byte[]> last = null;
        for (int attempt = 0; attempt < MAX_ALLOWED_WAIT_ATTEMPTS; attempt++) {
            last =
                    put(RESET_PATH, DemoResetGatewayFixtures.demoToken())
                            .expectBody()
                            .returnResult();
            if (last.getStatus().value() != 429) {
                return last;
            }
        }
        throw new AssertionError(
                "the demo bucket never admitted a reset within "
                        + MAX_ALLOWED_WAIT_ATTEMPTS
                        + " attempts; last status was "
                        + (last != null ? last.getStatus() : "none"));
    }

    /** The 429 that ended a drain, plus how many attempts the limiter admitted and denied. */
    private record DrainResult(
            EntityExchangeResult<String> denial, int allowedAttempts, int deniedAttempts) {}

    /** Drains the shared demo bucket until the reset route denies with 429. */
    private DrainResult awaitThrottledReset() {
        EntityExchangeResult<String> last = null;
        int allowed = 0;
        int denied = 0;
        for (int attempt = 0; attempt < MAX_THROTTLE_WAIT_ATTEMPTS; attempt++) {
            last =
                    put(RESET_PATH, DemoResetGatewayFixtures.demoToken())
                            .expectBody(String.class)
                            .returnResult();
            if (last.getStatus().value() == 429) {
                denied++;
                return new DrainResult(last, allowed, denied);
            }
            allowed++;
        }
        throw new AssertionError(
                "the reset route never throttled within "
                        + MAX_THROTTLE_WAIT_ATTEMPTS
                        + " attempts — is it missing its RequestRateLimiter, or exempt? "
                        + "last status was "
                        + (last != null ? last.getStatus() : "none"));
    }

}
