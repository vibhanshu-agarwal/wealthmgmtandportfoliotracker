package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
            new GenericContainer<>(TestContainerImages.REDIS)
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

    // ── B2 Task 9.1 — GET /api/assets conditional-revalidation CORS boundary ──
    //
    // `AssetCatalogController` (B1 task 4.11) answers with an `ETag` and honours
    // `If-None-Match` for 304s (fetchCatalog, Task 1.11). A cross-origin browser can
    // only use that contract if the gateway's CORS policy both admits `If-None-Match`
    // as a request header and exposes `ETag` on the response — neither of which
    // `corsConfigurationSource()` did before this task (allowedHeaders lacked
    // `If-None-Match`; exposedHeaders was never set at all).

    private static final String ASSETS_PATH = "/api/assets";

    /**
     * Preflight for a conditional catalog GET must allow the browser to send
     * {@code If-None-Match}.
     *
     * <p>On unfixed code, {@code corsConfigurationSource()}'s {@code allowedHeaders}
     * omits {@code If-None-Match}, so the browser's own preflight check would refuse
     * to send it and every "revalidate" request degrades to a full, uncached GET.
     */
    @Test
    void preflightForAssetsAllowsIfNoneMatchHeader() {
        webTestClient.options()
                .uri(ASSETS_PATH)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization,if-none-match")
                .exchange()
                .expectHeader().value("Access-Control-Allow-Headers", allowed ->
                        assertThat(allowed)
                                .as("preflight for " + ASSETS_PATH + " must allow the browser to "
                                    + "send If-None-Match, or conditional revalidation can never "
                                    + "reach the gateway cross-origin")
                                .containsIgnoringCase("If-None-Match"));
    }

    /**
     * An authenticated catalog GET must expose {@code ETag} to browser JavaScript.
     *
     * <p>On unfixed code, {@code exposedHeaders} is never configured, so
     * {@code Access-Control-Expose-Headers} is absent and {@code fetchCatalog}'s
     * {@code response.headers.get("ETag")} reads {@code null} cross-origin even
     * though the header is present on the wire — permanently defeating conditional
     * revalidation without ever surfacing as a visible error.
     */
    @Test
    void authenticatedGetOnAssetsExposesETagHeader() {
        String token = TestJwtFactory.validSeedUserToken();

        webTestClient.get()
                .uri(ASSETS_PATH)
                .header("Origin", FRONTEND_ORIGIN)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectHeader().value("Access-Control-Expose-Headers", exposed ->
                        assertThat(exposed)
                                .as("GET " + ASSETS_PATH + " must expose ETag so the browser's "
                                    + "fetch() can read it cross-origin")
                                .containsIgnoringCase("ETag"));
    }
}
