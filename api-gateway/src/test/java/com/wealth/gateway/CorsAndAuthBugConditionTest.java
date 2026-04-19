package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
 * Bug Condition Exploration Tests — CORS rejection for production origins
 * and JWT filter rejection for {@code /api/auth/**} paths.
 *
 * <p>These tests encode the EXPECTED behavior (what the code SHOULD do after the fix).
 * They are expected to FAIL on the current unfixed code — failure confirms the bugs exist.
 *
 * <p><b>Bug Condition A</b>: {@code SecurityConfig.corsConfigurationSource()} hard-codes
 * {@code setAllowedOrigins} to localhost values only. Production origins like
 * {@code https://vibhanshu-ai-portfolio.dev} receive no CORS headers → browser blocks
 * the request with a 403.
 *
 * <p><b>Bug Condition B</b>: {@code JwtAuthenticationFilter} skip list only includes
 * {@code /actuator} and {@code /api/portfolio/health}. The {@code /api/auth/**} paths
 * declared as {@code permitAll()} are missing, so the filter's {@code switchIfEmpty}
 * branch returns 401 for requests without a JWT.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.5, 1.6</b>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.cors.allowed-origin-patterns=http://localhost:3000,http://127.0.0.1:3000,https://vibhanshu-ai-portfolio.dev,https://*.vibhanshu-ai-portfolio.dev"
        }
)
@ActiveProfiles("local")
class CorsAndAuthBugConditionTest {

    private static final int REDIS_PORT = 6379;
    private static final String API_PATH = "/api/portfolio";
    private static final String PRODUCTION_ORIGIN = "https://vibhanshu-ai-portfolio.dev";
    private static final String SUBDOMAIN_ORIGIN = "https://app.vibhanshu-ai-portfolio.dev";

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

    // ── Bug Condition A — CORS rejection for production origins ───────────────

    /**
     * Production origin preflight must return CORS headers.
     *
     * <p>On unfixed code, {@code SecurityConfig} hard-codes only localhost origins,
     * so the production origin receives no {@code Access-Control-Allow-Origin} header.
     *
     * <p><b>Validates: Requirements 1.1, 1.3</b>
     */
    @Test
    void productionOriginPreflightReturnsCorsHeaders() {
        webTestClient.options()
                .uri(API_PATH)
                .header("Origin", PRODUCTION_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectHeader().value("Access-Control-Allow-Origin", origin ->
                        assertThat(origin)
                                .as("preflight for production origin must return "
                                    + "Access-Control-Allow-Origin matching the request origin")
                                .isEqualTo(PRODUCTION_ORIGIN));
    }

    /**
     * Subdomain origin preflight must return CORS headers.
     *
     * <p>On unfixed code, subdomain origins like {@code https://app.vibhanshu-ai-portfolio.dev}
     * are not in the hard-coded allow-list and no pattern matching is configured.
     *
     * <p><b>Validates: Requirements 1.2</b>
     */
    @Test
    void subdomainOriginPreflightReturnsCorsHeaders() {
        webTestClient.options()
                .uri(API_PATH)
                .header("Origin", SUBDOMAIN_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectHeader().exists("Access-Control-Allow-Origin");
    }

    /**
     * Authenticated GET with production origin must return CORS headers.
     *
     * <p>On unfixed code, even a valid JWT request from the production origin
     * receives no CORS headers because the origin is not in the allow-list.
     *
     * <p><b>Validates: Requirements 1.1, 1.3</b>
     */
    @Test
    void authenticatedGetWithProductionOriginReturnsCorsHeaders() {
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + TestJwtFactory.validSeedUserToken())
                .header("Origin", PRODUCTION_ORIGIN)
                .exchange()
                .expectHeader().exists("Access-Control-Allow-Origin");
    }

    // ── Bug Condition B — JWT filter rejection for /api/auth/** paths ─────────

    /**
     * POST /api/auth/login without JWT must NOT return 401.
     *
     * <p>On unfixed code, {@code JwtAuthenticationFilter} does not skip
     * {@code /api/auth/**} paths, so the {@code switchIfEmpty} branch fires
     * and returns 401.
     *
     * <p><b>Validates: Requirements 1.5</b>
     */
    @Test
    void authLoginWithoutJwtIsNotRejected() {
        webTestClient.post()
                .uri("/api/auth/login")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("/api/auth/login without JWT should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    /**
     * POST /api/auth/register without JWT must NOT return 401.
     *
     * <p>On unfixed code, same issue as login — the JWT filter rejects the request.
     *
     * <p><b>Validates: Requirements 1.6</b>
     */
    @Test
    void authRegisterWithoutJwtIsNotRejected() {
        webTestClient.post()
                .uri("/api/auth/register")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("/api/auth/register without JWT should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    /**
     * POST /api/auth/login with spoofed X-User-Id and no JWT must NOT return 401.
     *
     * <p>On unfixed code, the request never reaches the header-stripping logic
     * because the JWT filter rejects it with 401 first.
     *
     * <p><b>Validates: Requirements 1.5</b>
     */
    @Test
    void authLoginWithSpoofedXUserIdIsNotRejected() {
        webTestClient.post()
                .uri("/api/auth/login")
                .header("X-User-Id", "attacker")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("/api/auth/login with spoofed X-User-Id should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    /**
     * POST /api/auth (no trailing slash) without JWT must NOT return 401.
     *
     * <p>Edge case: the bare path without trailing slash must also be skipped
     * by the JWT filter.
     *
     * <p><b>Validates: Requirements 1.5</b>
     */
    @Test
    void authBasePathWithoutTrailingSlashIsNotRejected() {
        webTestClient.post()
                .uri("/api/auth")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("/api/auth (no trailing slash) without JWT should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    /**
     * POST /api/authentication without JWT MUST return 401.
     *
     * <p>Negative edge case: {@code /api/authentication} must NOT match the
     * skip list. {@code startsWith("/api/auth/")} does not match this path,
     * and {@code equals("/api/auth")} is an exact match only.
     *
     * <p>This test should PASS on unfixed code — it confirms that the JWT filter
     * correctly rejects paths that are NOT in the skip list.
     *
     * <p><b>Validates: Requirements 1.5 (negative case)</b>
     */
    @Test
    void authenticationPathStillRequiresJwt() {
        webTestClient.post()
                .uri("/api/authentication")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
