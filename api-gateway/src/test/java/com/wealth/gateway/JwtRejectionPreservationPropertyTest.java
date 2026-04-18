package com.wealth.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Preservation Property Test — Property 2: Invalid JWTs Continue To Be Rejected.
 *
 * <p>This test explicitly sets {@code auth.jwt.secret} to {@link TestJwtFactory#TEST_SECRET}
 * via {@code @DynamicPropertySource} so the gateway has a working baseline. The test then
 * verifies that various categories of invalid JWTs are correctly rejected with HTTP 401.
 *
 * <p>These tests capture the preservation requirements: all inputs where
 * {@code NOT isBugCondition(request)} must return 401. This behavior must be preserved
 * both before and after the fix.
 *
 * <p><b>Expected outcome on UNFIXED code:</b> All tests PASS — confirms baseline rejection
 * behavior exists and is working.
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5</b>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class JwtRejectionPreservationPropertyTest {

    private static final int REDIS_PORT = 6379;
    private static final String API_PATH = "/api/portfolio";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    /**
     * Sets Redis properties AND explicitly aligns {@code auth.jwt.secret} with
     * {@link TestJwtFactory#TEST_SECRET}. Preservation tests need the secret aligned
     * so they verify rejection of genuinely invalid tokens, not just secret-mismatch rejection.
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
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

    // ── Expired JWTs ──────────────────────────────────────────────────────────

    /**
     * Generates random past durations ranging from -1 second to -30 days.
     * Each duration represents how far in the past the JWT expired.
     */
    static Stream<Duration> randomPastDurations() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return Stream.generate(() -> Duration.ofSeconds(-rng.nextLong(1, 30L * 24 * 3600)))
                .limit(10);
    }

    /**
     * Expired JWTs must be rejected with 401 regardless of how long ago they expired.
     *
     * <p><b>Validates: Requirements 3.2</b>
     */
    @ParameterizedTest(name = "expired JWT (offset={0}) returns 401")
    @MethodSource("randomPastDurations")
    void expiredJwtIsRejected(Duration pastOffset) {
        String expiredToken = TestJwtFactory.mint(
                TestJwtFactory.SEED_USER_ID, pastOffset);

        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Wrong signing key ─────────────────────────────────────────────────────

    /**
     * Generates random 32+ character strings that differ from {@link TestJwtFactory#TEST_SECRET}.
     * Each string is a random UUID-based key padded to at least 32 characters.
     */
    static Stream<String> randomWrongSigningKeys() {
        return Stream.generate(() -> {
            String key;
            do {
                key = "wrong-key-" + UUID.randomUUID() + "-padding";
            } while (key.equals(TestJwtFactory.TEST_SECRET));
            return key;
        }).limit(10);
    }

    /**
     * JWTs signed with a wrong/unknown secret must be rejected with 401.
     *
     * <p><b>Validates: Requirements 3.4</b>
     */
    @ParameterizedTest(name = "JWT signed with wrong key returns 401")
    @MethodSource("randomWrongSigningKeys")
    void wrongSigningKeyIsRejected(String wrongSecret) {
        String wrongKeyToken = TestJwtFactory.mint(
                TestJwtFactory.SEED_USER_ID, Duration.ofHours(1), wrongSecret);

        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + wrongKeyToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Tampered signatures ───────────────────────────────────────────────────

    /**
     * Generates valid JWTs and then flips a random byte in the signature segment.
     * Each variant has a different random byte position tampered.
     */
    static Stream<String> randomTamperedTokens() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return Stream.generate(() -> {
            String validToken = TestJwtFactory.mint(
                    UUID.randomUUID().toString(), Duration.ofHours(1));
            return tamperSignatureAtRandom(validToken, rng);
        }).limit(10);
    }

    /**
     * JWTs with tampered signatures must be rejected with 401 regardless of which
     * byte in the signature was modified.
     *
     * <p><b>Validates: Requirements 3.3</b>
     */
    @ParameterizedTest(name = "tampered JWT signature returns 401")
    @MethodSource("randomTamperedTokens")
    void tamperedSignatureIsRejected(String tamperedToken) {
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + tamperedToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Missing Authorization header ──────────────────────────────────────────

    /**
     * Generates random API paths under /api/ to verify that missing Authorization
     * header results in 401 regardless of the requested path.
     */
    static Stream<String> randomApiPaths() {
        return Stream.of(
                "/api/portfolio",
                "/api/portfolio/" + UUID.randomUUID(),
                "/api/market-data",
                "/api/market-data/prices",
                "/api/insights",
                "/api/insights/" + UUID.randomUUID(),
                "/api/portfolio/holdings",
                "/api/portfolio/summary",
                "/api/market-data/" + UUID.randomUUID(),
                "/api/insights/latest"
        );
    }

    /**
     * Requests with no Authorization header must be rejected with 401.
     *
     * <p><b>Validates: Requirements 3.1</b>
     */
    @ParameterizedTest(name = "missing Authorization header on {0} returns 401")
    @MethodSource("randomApiPaths")
    void missingAuthorizationHeaderIsRejected(String path) {
        webTestClient.get()
                .uri(path)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Missing sub claim ─────────────────────────────────────────────────────

    /**
     * Generates JWTs without a {@code sub} claim but with random other claims.
     * Each JWT is valid in every other respect (correct signing key, not expired).
     */
    static Stream<String> jwtsMissingSubClaim() {
        return Stream.generate(() -> {
            Instant now = Instant.now();
            return Jwts.builder()
                    .claim("iss", "test-issuer")
                    .claim("jti", UUID.randomUUID().toString())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plusSeconds(3600)))
                    .signWith(Keys.hmacShaKeyFor(
                            TestJwtFactory.TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                            Jwts.SIG.HS256)
                    .compact();
        }).limit(10);
    }

    /**
     * JWTs without a {@code sub} claim must be rejected with 401 even if they are
     * otherwise valid (correct signature, not expired).
     *
     * <p><b>Validates: Requirements 3.5</b>
     */
    @ParameterizedTest(name = "JWT without sub claim returns 401")
    @MethodSource("jwtsMissingSubClaim")
    void missingSubClaimIsRejected(String noSubToken) {
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + noSubToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Flips a random byte in the JWT signature segment to produce an invalid signature.
     */
    private static String tamperSignatureAtRandom(String jwt, ThreadLocalRandom rng) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return jwt;
        byte[] sigBytes = Base64.getUrlDecoder().decode(parts[2]);
        int idx = rng.nextInt(sigBytes.length);
        sigBytes[idx] = (byte) (sigBytes[idx] ^ 0xFF);
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
        return String.join(".", parts);
    }
}
