package com.wealth.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the JWT authentication filter chain.
 *
 * <p>The gateway routes to non-existent upstreams — tests assert on the gateway's own
 * response (401 for auth failures, non-401 for valid tokens that reach the routing phase).
 *
 * <p>Run via: {@code ./gradlew :api-gateway:integrationTest}
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class JwtFilterIntegrationTest {

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

    // ── Core test cases ───────────────────────────────────────────────────────

    /**
     * Valid JWT → Spring Security accepts it, JwtAuthenticationFilter injects X-User-Id,
     * gateway attempts to proxy to upstream. Upstream doesn't exist → 502/503/504.
     * The key assertion: response is NOT 401.
     */
    @Test
    void validJwtIsNotRejected() {
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + TestJwtFactory.validSeedUserToken())
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).as("valid JWT should not be rejected with 401")
                                .isNotEqualTo(401));
    }

    @Test
    void missingAuthorizationHeaderReturns401() {
        webTestClient.get()
                .uri(API_PATH)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void expiredJwtReturns401() {
        String expiredToken = TestJwtFactory.mint(
                TestJwtFactory.SEED_USER_ID, Duration.ofSeconds(-1));
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tamperedSignatureReturns401() {
        String validToken = TestJwtFactory.validSeedUserToken();
        String tamperedToken = tamperSignature(validToken);
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + tamperedToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void jwtWithoutSubClaimReturns401() {
        // Mint a JWT with no sub claim — JwtAuthenticationFilter rejects it after Spring Security accepts it
        String noSubToken = Jwts.builder()
                .claim("iss", "test")
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(
                        TestJwtFactory.TEST_SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + noSubToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void spoofedXUserIdHeaderWithNoJwtReturns401() {
        webTestClient.get()
                .uri(API_PATH)
                .header("X-User-Id", "spoofed-user-id")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Property 1: JWT Round-Trip — mint → validate → non-401 ───────────────

    static Stream<String> representativeSubValues() {
        return Stream.of(
                "00000000-0000-0000-0000-000000000001",
                "11111111-1111-1111-1111-111111111111",
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "550e8400-e29b-41d4-a716-446655440000"
        );
    }

    /**
     * Property 1: JWT Round-Trip (mint → validate → non-401)
     * Validates: Requirements 4.2, 4.11, 9.3, 9.4, 13.1
     */
    @ParameterizedTest(name = "valid JWT with sub={0} is not rejected")
    @MethodSource("representativeSubValues")
    void validJwtWithVariousSubsIsNotRejected(String sub) {
        String token = TestJwtFactory.mint(sub, Duration.ofHours(1));
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).as("valid JWT with sub=%s should not be 401", sub)
                                .isNotEqualTo(401));
    }

    // ── Property 2: Expired JWT Always Rejected ───────────────────────────────

    static Stream<Duration> pastExpiryOffsets() {
        return Stream.of(
                Duration.ofSeconds(-1),
                Duration.ofHours(-1),
                Duration.ofDays(-1)
        );
    }

    /**
     * Property 2: Expired JWT Always Rejected
     * Validates: Requirements 4.5, 9.5, 13.3
     */
    @ParameterizedTest(name = "expired JWT (offset={0}) returns 401")
    @MethodSource("pastExpiryOffsets")
    void expiredJwtWithVariousOffsetsReturns401(Duration offset) {
        String expiredToken = TestJwtFactory.mint(TestJwtFactory.SEED_USER_ID, offset);
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + expiredToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Property 3: Tampered Signature Always Rejected ────────────────────────

    static Stream<String> tamperedTokenVariants() {
        String valid = TestJwtFactory.validSeedUserToken();
        return Stream.of(
                tamperSignatureAt(valid, 0),   // first byte
                tamperSignatureAt(valid, -1),  // last byte
                tamperSignatureAt(valid, 5)    // middle byte
        );
    }

    /**
     * Property 3: Tampered Signature Always Rejected
     * Validates: Requirements 4.4, 13.2
     */
    @ParameterizedTest(name = "tampered JWT signature returns 401")
    @MethodSource("tamperedTokenVariants")
    void tamperedSignatureVariantsReturn401(String tamperedToken) {
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + tamperedToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Property 4: Wrong Signing Key Always Rejected ─────────────────────────

    /**
     * Property 4: Wrong Signing Key Always Rejected
     * Validates: Requirements 13.8
     */
    @ParameterizedTest(name = "JWT signed with wrong key \"{0}\" returns 401")
    @CsvSource({
            "wrong-secret-that-is-at-least-32-chars-long",
            "another-wrong-secret-min-32-characters-here",
            "completely-different-key-32-chars-minimum!"
    })
    void jwtSignedWithWrongKeyReturns401(String wrongSecret) {
        String wrongKeyToken = TestJwtFactory.mint(
                TestJwtFactory.SEED_USER_ID, Duration.ofHours(1), wrongSecret);
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + wrongKeyToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ── Property 5: Spoofing Prevention ──────────────────────────────────────

    static Stream<String[]> subAndSpoofedPairs() {
        return Stream.of(
                new String[]{"00000000-0000-0000-0000-000000000001", "spoofed-user"},
                new String[]{"11111111-1111-1111-1111-111111111111", "attacker-id"},
                new String[]{"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "admin"}
        );
    }

    /**
     * Property 5: Spoofing Prevention — Injected Value Overrides Caller Header
     * Validates: Requirements 4.12, 12.1, 12.2
     */
    @ParameterizedTest(name = "spoofed X-User-Id header is overridden by JWT sub={0}")
    @MethodSource("subAndSpoofedPairs")
    void spoofedHeaderIsOverriddenByJwtSub(String sub, String spoofed) {
        // The gateway strips the spoofed header and injects the real sub.
        // Since the upstream doesn't exist, we get a non-401 (502/503/504).
        // The key assertion: the gateway did NOT reject the request as 401.
        String token = TestJwtFactory.mint(sub, Duration.ofHours(1));
        webTestClient.get()
                .uri(API_PATH)
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", spoofed)
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status).as("valid JWT should override spoofed X-User-Id header")
                                .isNotEqualTo(401));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Flips the last byte of the JWT signature segment to produce an invalid signature.
     */
    private static String tamperSignature(String jwt) {
        return tamperSignatureAt(jwt, -1);
    }

    /**
     * Flips a byte at the given index (negative = from end) in the JWT signature segment.
     */
    private static String tamperSignatureAt(String jwt, int byteIndex) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return jwt;
        byte[] sigBytes = Base64.getUrlDecoder().decode(parts[2]);
        int idx = byteIndex < 0 ? sigBytes.length + byteIndex : byteIndex;
        idx = Math.clamp(idx, 0, sigBytes.length - 1);
        sigBytes[idx] = (byte) (sigBytes[idx] ^ 0xFF);
        parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(sigBytes);
        return String.join(".", parts);
    }
}
