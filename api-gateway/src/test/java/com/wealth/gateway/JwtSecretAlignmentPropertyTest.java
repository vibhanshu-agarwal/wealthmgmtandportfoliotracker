package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug Condition Exploration Test — Property 1: Valid Test JWTs Are Rejected On Unfixed Code.
 *
 * <p>This test deliberately does NOT set {@code auth.jwt.secret} via {@code @DynamicPropertySource}.
 * On unfixed code, the gateway resolves a different secret than {@link TestJwtFactory#TEST_SECRET},
 * causing HMAC-SHA256 signature verification to fail and all valid test-minted JWTs to be rejected
 * with HTTP 401.
 *
 * <p><b>Expected outcome on unfixed code:</b> All parameterized cases FAIL with 401 — this confirms
 * the signing secret mismatch bug exists.
 *
 * <p>Validates: Requirements 1.1, 1.2
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class JwtSecretAlignmentPropertyTest {

    private static final int REDIS_PORT = 6379;
    private static final String API_PATH = "/api/portfolio";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    /**
     * Redis and JWT secret properties. The auth.jwt.secret is now explicitly set to
     * TestJwtFactory.TEST_SECRET to verify the fix — the gateway's localJwtDecoder
     * will use the same secret that TestJwtFactory uses to sign JWTs.
     */
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

    // ── Random UUID sub values for property-style exploration ─────────────────

    /**
     * Generates a set of random UUID sub values to demonstrate that the bug affects
     * ALL valid JWTs regardless of the sub claim value.
     */
    static Stream<String> randomUuidSubValues() {
        return Stream.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()
        );
    }

    /**
     * Property 1: Bug Condition — Valid Test JWTs Are Rejected On Unfixed Code.
     *
     * <p>For each randomly generated UUID sub value, mints a valid JWT using
     * {@link TestJwtFactory#TEST_SECRET} and sends it to the gateway. On unfixed code,
     * the gateway's {@code localJwtDecoder} uses a different secret, so HMAC verification
     * fails and the response is 401.
     *
     * <p>The assertion {@code status != 401} encodes the EXPECTED (correct) behavior.
     * On unfixed code this assertion FAILS — proving the bug exists.
     *
     * <p><b>Validates: Requirements 1.1, 1.2</b>
     */
    @ParameterizedTest(name = "valid JWT with random sub={0} should not be rejected with 401")
    @MethodSource("randomUuidSubValues")
    void validJwtWithRandomSubIsNotRejected(String sub) {
        String token = TestJwtFactory.mint(sub, Duration.ofHours(1));

        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("valid JWT minted with TEST_SECRET for sub=%s should not be 401 "
                                    + "(bug condition: gateway resolves a different secret)", sub)
                                .isNotEqualTo(401));
    }
}
