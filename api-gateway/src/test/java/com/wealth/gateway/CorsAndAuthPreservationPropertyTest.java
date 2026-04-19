package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preservation Property Tests — capture CURRENT baseline behavior on unfixed code.
 *
 * <p>These tests MUST PASS now (on unfixed code) and continue to pass after the fix
 * is applied. They guard against regressions in existing behavior that is NOT affected
 * by the bug.
 *
 * <p><b>Preservation A</b>: Localhost CORS origins still accepted (Requirements 3.1, 3.2, 3.8)
 * <p><b>Preservation B</b>: Disallowed origins still rejected (Requirement 3.3)
 * <p><b>Preservation C</b>: Authenticated endpoint behavior unchanged (Requirements 3.4, 3.5)
 * <p><b>Preservation D</b>: Actuator and health skip preserved (Requirement 3.6)
 * <p><b>Preservation E</b>: X-User-Id stripping on all paths (Requirement 3.7)
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8</b>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class CorsAndAuthPreservationPropertyTest {

    private static final int REDIS_PORT = 6379;
    private static final String API_PATH = "/api/portfolio";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(REDIS_PORT));
        registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);
    }

    @LocalServerPort
    int port;

    WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ── Preservation A — Localhost CORS origins still accepted ────────────────
    // Validates: Requirements 3.1, 3.2, 3.8

    /**
     * Preflight from {@code http://localhost:3000} must return matching CORS headers
     * and {@code Access-Control-Allow-Credentials: true}.
     *
     * <p><b>Validates: Requirements 3.1, 3.8</b>
     */
    @Test
    void localhostOriginPreflightReturnsCorsHeaders() {
        webTestClient.options()
                .uri(API_PATH)
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectHeader().value("Access-Control-Allow-Origin", origin ->
                        assertThat(origin)
                                .as("preflight for localhost:3000 must return matching Access-Control-Allow-Origin")
                                .isEqualTo("http://localhost:3000"))
                .expectHeader().value("Access-Control-Allow-Credentials", creds ->
                        assertThat(creds)
                                .as("preflight must include Access-Control-Allow-Credentials: true")
                                .isEqualTo("true"));
    }

    /**
     * Preflight from {@code http://127.0.0.1:3000} must return matching CORS headers
     * and {@code Access-Control-Allow-Credentials: true}.
     *
     * <p><b>Validates: Requirements 3.2, 3.8</b>
     */
    @Test
    void loopbackOriginPreflightReturnsCorsHeaders() {
        webTestClient.options()
                .uri(API_PATH)
                .header("Origin", "http://127.0.0.1:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectHeader().value("Access-Control-Allow-Origin", origin ->
                        assertThat(origin)
                                .as("preflight for 127.0.0.1:3000 must return matching Access-Control-Allow-Origin")
                                .isEqualTo("http://127.0.0.1:3000"))
                .expectHeader().value("Access-Control-Allow-Credentials", creds ->
                        assertThat(creds)
                                .as("preflight must include Access-Control-Allow-Credentials: true")
                                .isEqualTo("true"));
    }

    // ── Preservation B — Disallowed origins still rejected ───────────────────
    // Validates: Requirement 3.3

    /**
     * Preflight from disallowed origins must NOT return {@code Access-Control-Allow-Origin}.
     *
     * <p><b>Validates: Requirement 3.3</b>
     */
    @ParameterizedTest(name = "disallowed origin \"{0}\" preflight returns no CORS headers")
    @ValueSource(strings = {
            "https://evil.com",
            "https://attacker.io",
            "https://not-allowed.org",
            "http://localhost:9999",
            "https://vibhanshu-ai-portfolio.dev.evil.com"
    })
    void disallowedOriginPreflightReturnsNoCorsHeaders(String origin) {
        webTestClient.options()
                .uri(API_PATH)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectHeader().doesNotExist("Access-Control-Allow-Origin");
    }

    // ── Preservation C — Authenticated endpoint behavior unchanged ────────────
    // Validates: Requirements 3.4, 3.5

    /**
     * A valid JWT on a protected endpoint must NOT be rejected with 401.
     *
     * <p><b>Validates: Requirement 3.4</b>
     */
    @Test
    void validJwtOnProtectedEndpointIsNotRejected() {
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + TestJwtFactory.validSeedUserToken())
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("valid JWT on /api/portfolio should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    /**
     * Protected endpoints without a JWT must return 401.
     *
     * <p><b>Validates: Requirement 3.5</b>
     */
    @ParameterizedTest(name = "protected endpoint \"{0}\" without JWT returns 401")
    @ValueSource(strings = {"/api/portfolio", "/api/market", "/api/insights"})
    void protectedEndpointWithoutJwtReturns401(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Preservation D — Actuator and health skip preserved ──────────────────
    // Validates: Requirement 3.6

    /**
     * {@code GET /actuator/health} without JWT must return 200.
     *
     * <p><b>Validates: Requirement 3.6</b>
     */
    @Test
    void actuatorHealthWithoutJwtReturns200() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * {@code GET /api/portfolio/health} without JWT must NOT return 401.
     *
     * <p><b>Validates: Requirement 3.6</b>
     */
    @Test
    void portfolioHealthWithoutJwtIsNotRejected() {
        webTestClient.get()
                .uri("/api/portfolio/health")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("/api/portfolio/health without JWT should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    // ── Preservation E — X-User-Id stripping on all paths ────────────────────
    // Validates: Requirement 3.7

    /**
     * A spoofed {@code X-User-Id} header with a valid JWT must NOT cause rejection.
     * The filter strips the spoofed header and injects the real {@code sub} claim.
     *
     * <p><b>Validates: Requirement 3.7</b>
     */
    @Test
    void spoofedXUserIdWithValidJwtIsNotRejected() {
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + TestJwtFactory.validSeedUserToken())
                .header("X-User-Id", "attacker")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("valid JWT with spoofed X-User-Id should not be rejected with 401")
                                .isNotEqualTo(401));
    }
}
