package com.wealth.gateway;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * .kiro/specs/new-user-signup-profile, Requirement 10.8, 10.9 (Property 7, Property 8).
 *
 * <p>Redis-backed integration tests for {@link AuthRateLimitFilter}'s Auth_Bucket, following
 * {@link ProductionRateLimitingIntegrationTest}'s exact container / {@code @DynamicPropertySource}
 * pattern, targeting {@code /api/auth/login} (and {@code /api/auth/signup}, which shares the same
 * bucket) with small burst-capacity numbers so the suite runs fast.
 *
 * <p>A Postgres Testcontainers instance is required too — not because these tests exercise auth
 * logic (they don't; only rate-limiting), but because {@code application-prod.yml} declares
 * {@code spring.datasource.url: ${SPRING_DATASOURCE_URL}} with no default, and
 * {@code GatewayAuthDataConfig}'s {@code @ConditionalOnProperty} gate must resolve that property
 * during context refresh under the {@code prod} profile (Req 2.5 — missing values fail startup
 * loudly) — the exact same reason {@link ProductionRateLimitingIntegrationTest} carries one.
 * Flyway is run against it (same {@code filesystem:} location as {@code AuthIntegrationTest})
 * so the real {@code AuthenticationService}/{@code SignupService} beans that
 * {@code GatewayAuthDataConfig} wires up (spring.datasource.url present -> no fallback stubs) hit
 * actual {@code users}/{@code user_credentials} tables instead of failing with a
 * relation-does-not-exist {@code DataAccessException} — which {@code AuthenticationService} maps
 * to {@code CredentialStoreUnavailableException} -> HTTP 503, a 5xx that would otherwise
 * spuriously break {@link #loginReturnsNonServerErrorWhenRedisIsUnreachable}'s
 * {@code isLessThan(500)} assertion for reasons unrelated to Redis.
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"prod", "azure"})
class AuthRateLimitIntegrationTest {

    private static final int REDIS_PORT = 6379;
    // Deliberately DIFFERENT from standard/strict's burst-capacity (3) below, not merely "small".
    // standardRateLimiter is @Primary; if AuthRateLimitFilter's @Qualifier("authRateLimiter") were
    // ever dropped, an unqualified constructor parameter would silently resolve to
    // standardRateLimiter instead (see AuthRateLimitFilter's own javadoc on this exact hazard,
    // and AuthRateLimitFilterBeanWiringTest, which already asserts correct wiring by reference
    // identity). Giving the auth bucket a distinguishable burst capacity means that regression
    // would also make exceedingTheAuthBucketReturns429WithPositiveRetryAfter's burst-capacity loop
    // fail at request #4 instead of #6 — a second, independent signal at the integration level,
    // rather than this test coincidentally still passing because the numbers happen to match.
    private static final int SMALL_BURST = 5;

    /**
     * Pinned by {@code @DynamicPropertySource} below rather than inherited from a profile.
     *
     * <p>These tests run under {@code prod,azure}, and both profiles set
     * {@code app.cors.allowed-origin-patterns} — so which one wins is a property-precedence
     * question that has nothing to do with the behaviour under test. Registering the value
     * explicitly makes the CORS assertions depend only on the filter.
     */
    private static final String ALLOWED_ORIGIN = "https://wealth-demo.azurestaticapps.net";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(TestContainerImages.REDIS).withExposedPorts(REDIS_PORT);

    @Container
    @SuppressWarnings({"resource", "rawtypes"})
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestContainerImages.POSTGRES)
            .withDatabaseName("portfolio_db")
            .withUsername("wealth_user")
            .withPassword("wealth_pass");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                // Path relative to api-gateway/ — see AuthIntegrationTest for the full rationale.
                .locations("filesystem:../portfolio-service/src/main/resources/db/migration")
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.timeout", () -> "3s");
        registry.add("spring.data.redis.connect-timeout", () -> "3s");
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("app.rate-limit.standard.replenish-rate", () -> 1);
        registry.add("app.rate-limit.standard.burst-capacity", () -> 3);
        registry.add("app.rate-limit.standard.requested-tokens", () -> 1);
        registry.add("app.rate-limit.strict.replenish-rate", () -> 1);
        registry.add("app.rate-limit.strict.burst-capacity", () -> 3);
        registry.add("app.rate-limit.strict.requested-tokens", () -> 1);

        // Pin the CORS allow-list so the throttled-response assertions do not depend on which of
        // the two active profiles supplies allowed-origin-patterns.
        registry.add("app.cors.allowed-origin-patterns", () -> ALLOWED_ORIGIN);

        registry.add("app.rate-limit.auth.replenish-rate", () -> 1);
        registry.add("app.rate-limit.auth.burst-capacity", () -> SMALL_BURST);
        registry.add("app.rate-limit.auth.requested-tokens", () -> 1);
    }

    @LocalServerPort int port;

    /**
     * Every test in this class shares one Auth_Bucket: the key derives from the remote address,
     * which is localhost for all of them. Without a reset, each test inherits whatever the previous
     * one left behind and only passes because 1 token/second happens to replenish enough between
     * methods. That made the suite order- and timing-dependent, and adding a test that legitimately
     * drains the bucket surfaced it as failures in unrelated, unmodified tests.
     *
     * <p>Flushing gives each test a full bucket deterministically.
     */
    @BeforeEach
    void resetAuthBucket() throws Exception {
        redis.execInContainer("redis-cli", "FLUSHALL");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Upper bound on retries when waiting for the Auth_Bucket to report {@code 429} right after
     * exhausting its burst capacity. Mirrors {@code ProductionRateLimitingIntegrationTest}'s
     * identically-named/reasoned helper: with {@code replenishRate=1} token/sec, a deterministic
     * single immediate call after the burst-exhausting loop is flaky under scheduling jitter — a
     * fraction of a token can trickle back in before the very next request lands. Retrying a
     * bounded number of times (far faster than the 1 token/sec replenish rate) drains that drift.
     */
    private static final int MAX_THROTTLE_WAIT_ATTEMPTS = 30;

    private EntityExchangeResult<String> awaitThrottledResponse(String uri, Map<String, Object> body) {
        return awaitThrottledResponse(uri, body, null);
    }

    /** {@code origin} non-null exercises the cross-origin path, where CORS headers matter. */
    private EntityExchangeResult<String> awaitThrottledResponse(
            String uri, Map<String, Object> body, String origin) {
        EntityExchangeResult<String> last = null;
        for (int attempt = 0; attempt < MAX_THROTTLE_WAIT_ATTEMPTS; attempt++) {
            var request = client().post().uri(uri);
            if (origin != null) {
                request = request.header("Origin", origin);
            }
            last = request.bodyValue(body).exchange()
                    .expectBody(String.class)
                    .returnResult();
            if (last.getStatus().value() == 429) {
                return last;
            }
        }
        throw new AssertionError(
                "Expected a 429 response within " + MAX_THROTTLE_WAIT_ATTEMPTS
                        + " attempts after exhausting the Auth_Bucket burst capacity for " + uri
                        + "; last observed status was " + (last != null ? last.getStatus() : "none"));
    }

    @Test
    void exceedingTheAuthBucketReturns429WithPositiveRetryAfter() {
        Map<String, Object> body = Map.of("email", "throttle-test@example.com", "password", "wrong-password-1");

        for (int i = 0; i < SMALL_BURST; i++) {
            client().post().uri("/api/auth/login").bodyValue(body).exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }

        EntityExchangeResult<String> throttled = awaitThrottledResponse("/api/auth/login", body);
        assertThat(Integer.parseInt(throttled.getResponseHeaders().getFirst("Retry-After"))).isPositive();
    }

    /**
     * A browser issues an {@code OPTIONS} preflight before every cross-origin
     * {@code POST /api/auth/login}. Charging it to the Auth_Bucket drained the bucket at twice the
     * intended rate, which is what exhausted it during scheduled synthetic monitoring on
     * 2026-08-15 and 2026-08-16.
     *
     * <p>Proved through the running gateway rather than by invoking the filter directly: the unit
     * tests mock the limiter, so they cannot show that a real Redis token was not spent.
     */
    @Test
    void corsPreflightDoesNotConsumeAuthBucketTokens() {
        Map<String, Object> body = Map.of("email", "preflight-test@example.com", "password", "wrong-password-1");

        // Far more preflights than the burst would tolerate if they were charged.
        for (int i = 0; i < SMALL_BURST * 3; i++) {
            client().options().uri("/api/auth/login")
                    .header("Origin", ALLOWED_ORIGIN)
                    .header("Access-Control-Request-Method", "POST")
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }

        // The bucket must still be intact: a real POST burst still succeeds afterwards.
        for (int i = 0; i < SMALL_BURST; i++) {
            client().post().uri("/api/auth/login").bodyValue(body).exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }
    }

    /**
     * The filter runs at {@code HIGHEST_PRECEDENCE + 1}, ahead of Spring's CORS filter, and
     * short-circuits. Without CORS headers the browser blocks the 429 and the frontend maps it to
     * a network error — reporting "Unable to reach the login service" for what is actually a
     * throttle. That is precisely how a rate limit was mistaken for a production outage.
     */
    @Test
    void throttledLoginIsReadableByTheBrowser() {
        Map<String, Object> body = Map.of("email", "cors-throttle@example.com", "password", "wrong-password-1");

        for (int i = 0; i < SMALL_BURST; i++) {
            client().post().uri("/api/auth/login")
                    .header("Origin", ALLOWED_ORIGIN)
                    .bodyValue(body).exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }

        EntityExchangeResult<String> throttled =
                awaitThrottledResponse("/api/auth/login", body, ALLOWED_ORIGIN);

        var headers = throttled.getResponseHeaders();
        assertThat(headers.getFirst("Access-Control-Allow-Origin")).isEqualTo(ALLOWED_ORIGIN);
        assertThat(headers.getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
        // Retry-After is not on the CORS safelist, so without this JavaScript cannot read the
        // value the response is sending it.
        assertThat(headers.getFirst("Access-Control-Expose-Headers")).contains("Retry-After");
        assertThat(Integer.parseInt(headers.getFirst("Retry-After"))).isPositive();
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(throttled.getResponseBody()).contains("rate_limited");
    }

    /** Endpoints are POST-only; a GET to them must not spend an Auth_Bucket token. */
    @Test
    void nonPostRequestsToAuthPathsAreNotCharged() {
        Map<String, Object> body = Map.of("email", "get-not-charged@example.com", "password", "wrong-password-1");

        for (int i = 0; i < SMALL_BURST * 3; i++) {
            client().get().uri("/api/auth/login").exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }

        for (int i = 0; i < SMALL_BURST; i++) {
            client().post().uri("/api/auth/login").bodyValue(body).exchange()
                    .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
        }
    }

    @Test
    void loginAndSignupShareOneBucketPerKey() {
        Map<String, Object> loginBody = Map.of("email", "shared-bucket@example.com", "password", "wrong-password-1");
        Map<String, Object> signupBody = Map.of("email", "shared-bucket-2@example.com", "password", "a-strong-password-123", "name", "N");

        // Interleave login+signup calls up to the shared burst, then confirm the NEXT one (of
        // either kind) is throttled — proving they draw from the same bucket.
        for (int i = 0; i < SMALL_BURST; i++) {
            var uri = i % 2 == 0 ? "/api/auth/login" : "/api/auth/signup";
            var body = i % 2 == 0 ? loginBody : signupBody;
            client().post().uri(uri).bodyValue(body).exchange().expectStatus()
                    .value(status -> assertThat(status).isNotEqualTo(429));
        }

        awaitThrottledResponse("/api/auth/login", loginBody);
    }

    /**
     * Restarting the shared Testcontainers Redis mid-test assigns a new mapped port, which
     * invalidates the {@code spring.data.redis.url} the currently-cached Spring context resolved
     * at startup. {@code @DirtiesContext} forces a fresh context for any subsequent test in this
     * class.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void loginReturnsNonServerErrorWhenRedisIsUnreachable() {
        redis.stop();
        try {
            Map<String, Object> body = Map.of("email", "fail-open-user@example.com", "password", "whatever12345");

            // Even past what would normally be the burst capacity, every request must proceed to
            // the login logic (never 429, since the limiter fails open) and never 5xx (Req 10.9).
            for (int i = 0; i < SMALL_BURST + 3; i++) {
                final int requestNum = i + 1;
                client().post().uri("/api/auth/login").bodyValue(body).exchange()
                        .expectStatus().value(status -> {
                            assertThat(status).as("request %d must not be 429 when Redis is down", requestNum)
                                    .isNotEqualTo(429);
                            assertThat(status).as("request %d must not be 5xx when Redis is down", requestNum)
                                    .isLessThan(500);
                        });
            }
        } finally {
            redis.start();
        }
    }
}
