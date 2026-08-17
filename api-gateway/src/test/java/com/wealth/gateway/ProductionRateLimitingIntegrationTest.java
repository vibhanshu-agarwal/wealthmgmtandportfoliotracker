package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.wealth.gateway.ratelimit.BurstRunner;
import com.wealth.gateway.ratelimit.ProvenWindowRunner;
import com.wealth.gateway.ratelimit.RawAttempt;
import com.wealth.gateway.ratelimit.RedisTimeParser;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Production-profile integration tests for Redis-backed distributed rate limiting
 * (.kiro/specs/production-rate-limiting, Requirement 7).
 *
 * <p>Runs under {@code @ActiveProfiles({"prod","azure"})} with a real Redis instance provided by
 * Testcontainers, and injects small burst capacities via {@code @DynamicPropertySource} so the
 * suite runs quickly while still exercising the actual per-route {@code standardRateLimiter} /
 * {@code strictRateLimiter} beans, the production route list (with per-route
 * {@code RequestRateLimiter} filters and no {@code default-filters}), and
 * {@code RateLimitDenialResponseCustomizer}.
 *
 * <p>Following the {@code RateLimitingIntegrationTest} pattern: the gateway routes to
 * non-existent upstreams (default {@code localhost:808x}), so an "allowed" assertion checks for
 * a non-{@code 429} status rather than a specific downstream response body/status — proxying was
 * attempted, which is exactly what fail-open and allow-within-burst require.
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "azure"})
class ProductionRateLimitingIntegrationTest {

    private static final int REDIS_PORT = 6379;
    private static final String XFF = "X-Forwarded-For";
    private static final String PORTFOLIO_PATH = "/api/portfolio/holdings";
    private static final String MARKET_PATH = "/api/market/prices";
    private static final String INSIGHTS_PATH = "/api/insights/market-summary";
    private static final String INTERNAL_PATH = "/api/internal/portfolio/seed";

    // Small burst capacities keep the suite fast while still exercising the real limiter beans.
    private static final int STANDARD_BURST = 3;
    private static final int STRICT_BURST = 2;
    private static final int MAX_WINDOW_PROOF_ATTEMPTS = 30;
    private static final Duration MAX_WINDOW_PROOF_SOFT_ELAPSED = Duration.ofSeconds(10);

    private static HttpServer portfolioStub;
    private static int portfolioStubPort;
    private static final AtomicLong downstreamRequestCount = new AtomicLong();

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    // This test activates the "prod" profile, and application-prod.yml declares
    // spring.datasource.url/username/password as ${SPRING_DATASOURCE_URL}/etc with NO default
    // (Req 2.5 — missing values must fail startup loudly). GatewayAuthDataConfig's
    // @ConditionalOnProperty gate must resolve that property during context refresh, so an
    // ephemeral real Postgres is required here purely to make that resolution succeed — this
    // test exercises rate limiting, not auth/datasource logic. Same pattern portfolio-service
    // already uses for its own always-on datasource requirement (see e.g.
    // portfolio-service's AzureCacheConfigTest).
    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static final org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(TestContainerImages.POSTGRES)
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    @DynamicPropertySource
    static void redisAndRateLimitProperties(DynamicPropertyRegistry registry) throws IOException {
        startPortfolioStub();
        registry.add("app.routes.portfolio-url", () -> "http://127.0.0.1:" + portfolioStubPort);
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        // Bound fail-open latency for the failOpenWhenRedisDown test, but generous enough to
        // avoid spurious fail-open under CI/Docker-Desktop scheduling jitter in the other tests.
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
        registry.add("app.rate-limit.strict.burst-capacity", () -> STRICT_BURST);
        registry.add("app.rate-limit.strict.requested-tokens", () -> 1);
    }

    @LocalServerPort
    int port;

    @Autowired
    ApplicationContext applicationContext;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static void startPortfolioStub() throws IOException {
        if (portfolioStub != null) {
            return;
        }
        portfolioStub = HttpServer.create(new InetSocketAddress(0), 0);
        portfolioStubPort = portfolioStub.getAddress().getPort();
        portfolioStub.createContext("/", exchange -> {
            downstreamRequestCount.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        portfolioStub.start();
    }

    @AfterAll
    static void stopPortfolioStub() {
        if (portfolioStub != null) {
            portfolioStub.stop(0);
            portfolioStub = null;
        }
    }

    private String redisTimeSeconds() throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
                redis.execInContainer("redis-cli", "TIME");
        return RedisTimeParser.parse(result.getExitCode(), result.getStdout());
    }

    private static String tokenFor(String sub) {
        return TestJwtFactory.mint(sub, Duration.ofHours(1));
    }

    /**
     * Upper bound on retries when waiting for a bucket to report {@code 429} immediately after
     * exhausting its burst capacity.
     *
     * <p>{@code replenishRate=1} token/sec means the bucket replenishes against wall-clock time,
     * not request count. Asserting that the very next HTTP call after exhausting the burst is
     * deterministically a {@code 429} is flaky under CI/slower-runner scheduling jitter: if more
     * than ~1s elapses between the burst-exhausting request and the check, a fraction of a token
     * trickles back in and that one call is allowed. Retrying a bounded number of times drains
     * any such drift — these retries fire far faster than the 1 token/sec replenish rate, so the
     * bucket reliably reaches {@code 429} within a handful of attempts regardless of runner speed.
     */
    private static final int MAX_THROTTLE_WAIT_ATTEMPTS = 30;

    /**
     * Repeats the given authenticated GET until the response is {@code 429} (or the attempt
     * budget is exhausted), returning that response for further assertions. See
     * {@link #MAX_THROTTLE_WAIT_ATTEMPTS} for why this replaces a single deterministic call.
     */
    private EntityExchangeResult<String> awaitThrottledResponse(String path, String token) {
        EntityExchangeResult<String> last = null;
        for (int attempt = 0; attempt < MAX_THROTTLE_WAIT_ATTEMPTS; attempt++) {
            last = webTestClient.get()
                    .uri(path)
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectBody(String.class)
                    .returnResult();
            if (last.getStatus().value() == 429) {
                return last;
            }
        }
        throw new AssertionError(
                "Expected a 429 response within " + MAX_THROTTLE_WAIT_ATTEMPTS
                        + " attempts after exhausting the burst capacity for " + path
                        + "; last observed status was " + (last != null ? last.getStatus() : "none"));
    }

    // -------------------------------------------------------------------------
    // Context sanity
    // -------------------------------------------------------------------------

    @Test
    void contextLoadsWithRedisUnderProdAzureProfile() {
        assertThat(redis.isRunning()).isTrue();
    }

    // -------------------------------------------------------------------------
    // burstAllowedThenThrottledWithDecrement (STANDARD route — Req 7.2, 7.3, 5.1, 5.4, 5.5;
    // Properties 2, 9). Deliberately targets the standard route: X-RateLimit-Remaining decrements
    // by exactly 1 per request only when requestedTokens = 1 (the strict route consumes more).
    // -------------------------------------------------------------------------

    @Test
    void burstAllowedThenThrottledWithDecrement() throws Exception {
        BurstRunner productionBurstRunner = key -> {
            long baseline = downstreamRequestCount.get();
            List<EntityExchangeResult<String>> burst = new ArrayList<>();
            for (int i = 0; i < STANDARD_BURST; i++) {
                burst.add(webTestClient.get().uri(PORTFOLIO_PATH).header("Authorization", "Bearer " + key)
                        .exchange().expectBody(String.class).returnResult());
            }
            EntityExchangeResult<String> excess = webTestClient.get().uri(PORTFOLIO_PATH)
                    .header("Authorization", "Bearer " + key).exchange().expectBody(String.class).returnResult();
            return new RawAttempt(burst, excess, downstreamRequestCount.get() - baseline);
        };

        RawAttempt proven = new ProvenWindowRunner(this::redisTimeSeconds,
                () -> tokenFor("proven-window-" + System.nanoTime()), productionBurstRunner)
                .run(MAX_WINDOW_PROOF_ATTEMPTS, MAX_WINDOW_PROOF_SOFT_ELAPSED);

        List<String> remaining = new ArrayList<>();
        for (int i = 0; i < proven.burstResponses().size(); i++) {
            EntityExchangeResult<String> r = proven.burstResponses().get(i);
            assertThat(r.getStatus().value()).as("burst request %d proxied successfully", i + 1).isEqualTo(200);
            remaining.add(r.getResponseHeaders().getFirst("X-RateLimit-Remaining"));
        }
        assertThat(remaining).containsExactly("2", "1", "0");

        EntityExchangeResult<String> excess = proven.firstExcessResponse();
        assertThat(excess.getStatus().value()).isEqualTo(429);
        assertThat(excess.getResponseHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(excess.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(excess.getResponseBody()).contains("\"error\":\"rate_limited\"").contains("\"retryAfterSeconds\":1");
        assertThat(proven.downstreamDelta()).isEqualTo(STANDARD_BURST);
    }

    // -------------------------------------------------------------------------
    // standardVsStrictThresholds (Req 7.6, 3.1, 3.2; Property 2)
    // -------------------------------------------------------------------------

    @Test
    void standardVsStrictThresholds() {
        String standardToken = tokenFor("prod-standard-vs-strict-standard");
        String strictToken = tokenFor("prod-standard-vs-strict-strict");

        // Standard route allows up to STANDARD_BURST (3), then must throttle (retried to absorb
        // wall-clock replenishment drift under CI scheduling jitter).
        for (int i = 0; i < STANDARD_BURST; i++) {
            webTestClient.get()
                    .uri(MARKET_PATH)
                    .header("Authorization", "Bearer " + standardToken)
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }
        awaitThrottledResponse(MARKET_PATH, standardToken);

        // Strict route's lower threshold (STRICT_BURST = 2) throttles sooner.
        for (int i = 0; i < STRICT_BURST; i++) {
            webTestClient.get()
                    .uri(INSIGHTS_PATH)
                    .header("Authorization", "Bearer " + strictToken)
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }
        EntityExchangeResult<String> throttled = awaitThrottledResponse(INSIGHTS_PATH, strictToken);
        assertThat(throttled.getResponseHeaders().getFirst("Retry-After")).isEqualTo("6");
    }

    // -------------------------------------------------------------------------
    // singleBucketPerRequest (Req 3.7; Property 3) — proves exactly STANDARD_BURST requests are
    // allowed to one route, not STANDARD_BURST/2, which would indicate double-charging against
    // both a (nonexistent) default-filters bucket and the per-route bucket.
    // -------------------------------------------------------------------------

    @Test
    void singleBucketPerRequest() {
        String token = tokenFor("prod-single-bucket-user");
        List<Integer> statuses = new ArrayList<>();

        for (int i = 0; i < STANDARD_BURST; i++) {
            webTestClient.get()
                    .uri(PORTFOLIO_PATH)
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().value(statuses::add);
        }

        assertThat(statuses).as("exactly the full burst capacity should be allowed, not half")
                .hasSize(STANDARD_BURST)
                .noneMatch(status -> status == 429);
    }

    // -------------------------------------------------------------------------
    // independentBucketsPerKeyAndRouteClass (Req 7.4, 3.5, 5.3; Property 4)
    // -------------------------------------------------------------------------

    @Test
    void independentBucketsPerKeyAndRouteClass() {
        String keyA = tokenFor("prod-independent-key-a");
        String keyB = tokenFor("prod-independent-key-b");

        // Exhaust key A's standard-route bucket.
        for (int i = 0; i < STANDARD_BURST + 2; i++) {
            webTestClient.get()
                    .uri(PORTFOLIO_PATH)
                    .header("Authorization", "Bearer " + keyA)
                    .exchange();
        }

        // Key B's standard-route bucket is untouched.
        webTestClient.get()
                .uri(PORTFOLIO_PATH)
                .header("Authorization", "Bearer " + keyB)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).as("key B should not be limited by key A's exhausted bucket")
                                .isNotEqualTo(429));

        // Key A's strict-route (insights) bucket is a separate limiter instance, also untouched.
        webTestClient.get()
                .uri(INSIGHTS_PATH)
                .header("Authorization", "Bearer " + keyA)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).as("key A's strict-route bucket is independent of its standard-route bucket")
                                .isNotEqualTo(429));
    }

    // -------------------------------------------------------------------------
    // exemptRouteNeverThrottled (Req 3.8; Property 6)
    // -------------------------------------------------------------------------

    @Test
    void exemptRouteNeverThrottled() {
        List<Integer> statuses = new ArrayList<>();

        // Flood /api/internal/** well beyond any configured burst capacity, unauthenticated
        // (these routes carry no JWT/rate-limit filter at all).
        for (int i = 0; i < (STANDARD_BURST + STRICT_BURST) * 3; i++) {
            webTestClient.get().uri(INTERNAL_PATH).exchange().expectStatus().value(statuses::add);
        }

        assertThat(statuses).as("no request to an exempt route should be rate-limited")
                .noneMatch(status -> status == 429);
    }

    // -------------------------------------------------------------------------
    // Trusted-hop XFF key resolution for unauthenticated requests (Req 3.9; Property 1)
    //
    // WebTestClient carries no ingress-appended XFF hop (it originates in-process), so the test
    // itself appends the "real" client IP as the right-most entry to reproduce the trusted-hop
    // selection, per design.md's Testing Strategy note on ingress XFF emulation.
    // -------------------------------------------------------------------------

    @Test
    void unauthenticatedRequestsAreKeyedByTrustedRightmostXffHop() {
        // /api/portfolio/health is permitAll in SecurityConfig (no JWT required) yet still
        // matches the portfolio-service route's Path=/api/portfolio/** predicate, so it is both
        // reachable without a token and still rate-limited — exactly the surface needed to
        // exercise the unauthenticated (IP-keyed) path.
        final String unauthenticatedRateLimitedPath = "/api/portfolio/health";

        List<Integer> spoofedClientStatuses = new ArrayList<>();
        List<Integer> realClientStatuses = new ArrayList<>();

        // "Client" A: exhausts its bucket while rotating a spoofed left-most prefix on every
        // request. The trusted (right-most) hop stays constant at 90.90.90.90.
        for (int i = 0; i < STANDARD_BURST + 2; i++) {
            webTestClient.get()
                    .uri(unauthenticatedRateLimitedPath)
                    .header(XFF, "10.0.0." + i + ", 90.90.90.90")
                    .exchange()
                    .expectStatus().value(spoofedClientStatuses::add);
        }

        // A distinct trusted hop (91.91.91.91) must have its own independent bucket.
        webTestClient.get()
                .uri(unauthenticatedRateLimitedPath)
                .header(XFF, "10.0.0.99, 91.91.91.91")
                .exchange()
                .expectStatus().value(realClientStatuses::add);

        assertThat(spoofedClientStatuses).as("the trusted hop's bucket should exhaust despite spoofed prefixes")
                .contains(429);
        assertThat(realClientStatuses).as("a distinct trusted hop is an independent bucket")
                .doesNotContain(429);
    }

    // -------------------------------------------------------------------------
    // failOpenWhenRedisDown (Req 7.5, 1.5, 1.7, 4.2; Property 5)
    // -------------------------------------------------------------------------

    /**
     * Restarting the shared Redis container mid-test assigns a new mapped port, which
     * invalidates the {@code spring.data.redis.url} the currently-cached Spring context resolved
     * at startup. {@code @DirtiesContext} forces a fresh context (and a freshly-resolved URL via
     * the {@code @DynamicPropertySource} supplier lambdas) for any subsequent test, rather than
     * letting other tests in this class silently inherit a stale Redis connection.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void failOpenWhenRedisDown() {
        redis.stop();
        try {
            String token = tokenFor("prod-fail-open-user");

            // Even after exhausting what would normally be the burst capacity, every request
            // must be proxied (non-429) because the rate-limit check itself fails open.
            for (int i = 0; i < STANDARD_BURST + 3; i++) {
                final int requestNum = i + 1;
                webTestClient.get()
                        .uri(PORTFOLIO_PATH)
                        .header("Authorization", "Bearer " + token)
                        .exchange()
                        .expectStatus().value(status ->
                                assertThat(status)
                                        .as("request %d must fail open (not rejected as 429) when Redis is down",
                                                requestNum)
                                        .isNotEqualTo(429));
            }
        } finally {
            redis.start();
        }
    }
}
