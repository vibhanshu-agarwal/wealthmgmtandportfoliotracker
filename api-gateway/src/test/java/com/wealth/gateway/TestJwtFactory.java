package com.wealth.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Test utility for minting HMAC-SHA256 signed JWTs.
 *
 * <p>Resides exclusively in {@code src/test/java} — never included in production artifacts.
 * Uses jjwt (testImplementation scope only) to build compact JWT strings that the
 * {@link JwtAuthenticationFilter} and Spring Security's ReactiveJwtDecoder accept as valid.
 */
public final class TestJwtFactory {

    /** Canonical local dev / integration test sub claim — matches the Flyway seed user UUID. */
    public static final String SEED_USER_ID = "00000000-0000-0000-0000-000000000001";

    /** Signing secret used in test application-local.yml — must be ≥ 32 chars for HS256. */
    public static final String TEST_SECRET = "test-secret-for-integration-tests-min-32-chars";

    private TestJwtFactory() {}

    /**
     * Mints a compact JWT string signed with HMAC-SHA256.
     *
     * @param sub    the {@code sub} claim value (typically a UUID string)
     * @param expiry duration from now until expiry; use a negative duration for expired tokens
     * @param secret the HMAC-SHA256 signing secret (must match the gateway's configured secret)
     * @return compact JWT string ({@code header.payload.signature})
     */
    public static String mint(String sub, Duration expiry, String secret) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(sub)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Convenience overload using the default test secret.
     */
    public static String mint(String sub, Duration expiry) {
        return mint(sub, expiry, TEST_SECRET);
    }

    /**
     * Mints a valid JWT for the seed user with a 1-hour expiry using the default test secret.
     */
    public static String validSeedUserToken() {
        return mint(SEED_USER_ID, Duration.ofHours(1));
    }
}
