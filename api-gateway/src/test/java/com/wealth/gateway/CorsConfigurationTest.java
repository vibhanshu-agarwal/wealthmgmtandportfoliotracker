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
 * Bug Condition Exploration Test — Property 1b: CORS Headers on Gateway Responses.
 *
 * <p>Boots the full Spring context with the {@code local} profile and sends cross-origin
 * requests to verify that the API Gateway returns proper CORS headers.
 *
 * <p><b>Expected outcome on unfixed code:</b> Both tests FAIL because
 * {@code SecurityConfig} has no {@code .cors()} configuration, so responses lack
 * {@code Access-Control-Allow-Origin} headers. This confirms the bug condition where
 * browsers misreport 401 errors as CORS errors.
 *
 * <p><b>Validates: Requirements 1.4, 2.4</b>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class CorsConfigurationTest {

    private static final int REDIS_PORT = 6379;
    private static final String FRONTEND_ORIGIN = "http://localhost:3000";
    private static final String API_PATH = "/api/portfolio";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
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

    /**
     * CORS Preflight: An OPTIONS request with CORS preflight headers should receive
     * {@code Access-Control-Allow-Origin: http://localhost:3000} in the response.
     *
     * <p>On unfixed code, the gateway has no CORS configuration, so the preflight
     * response will NOT include the {@code Access-Control-Allow-Origin} header.
     *
     * <p><b>Validates: Requirements 1.4, 2.4</b>
     */
    @Test
    void preflightRequestReturnsCorsHeaders() {
        webTestClient.options()
                .uri(API_PATH)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization")
                .exchange()
                .expectHeader().value("Access-Control-Allow-Origin", origin ->
                        assertThat(origin)
                                .as("preflight response must include Access-Control-Allow-Origin "
                                    + "for the frontend origin (bug condition: SecurityConfig has "
                                    + "no .cors() configuration → no CORS headers → browser "
                                    + "misreports 401 as CORS error)")
                                .isEqualTo(FRONTEND_ORIGIN));
    }

    /**
     * CORS on authenticated GET: A regular GET request with a valid JWT and an
     * {@code Origin} header should receive CORS headers in the response.
     *
     * <p>On unfixed code, the gateway has no CORS configuration, so the response
     * will NOT include the {@code Access-Control-Allow-Origin} header.
     *
     * <p><b>Validates: Requirements 1.4, 2.4</b>
     */
    @Test
    void authenticatedGetWithOriginReturnsCorsHeaders() {
        String token = TestJwtFactory.validSeedUserToken();

        webTestClient.get()
                .uri(API_PATH)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectHeader().value("Access-Control-Allow-Origin", origin ->
                        assertThat(origin)
                                .as("authenticated GET response must include "
                                    + "Access-Control-Allow-Origin for the frontend origin "
                                    + "(bug condition: no .cors() in SecurityConfig)")
                                .isEqualTo(FRONTEND_ORIGIN));
    }
}
