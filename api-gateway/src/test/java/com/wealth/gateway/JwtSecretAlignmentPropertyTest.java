package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Contract test — the gateway's configured JWT decoder is aligned with {@link TestJwtFactory}.
 *
 * <p>The contract has two halves, and only asserting the first proves nothing: tokens minted with
 * {@link TestJwtFactory#TEST_SECRET} must decode with their {@code sub} intact, <b>and</b> tokens
 * minted with any other secret must be rejected. A decoder that accepted everything would satisfy
 * the first half alone.
 *
 * <p>The secret under test is not hard-coded here. It is whatever {@code application-local.yml}
 * binds to {@code auth.jwt.secret} under the {@code local} profile, resolved through the real
 * {@link JwtDecoderConfig#hmacJwtDecoder} bean — so this fails if that configuration drifts away
 * from what the test factory signs with.
 *
 * <p><b>Deliberately narrow.</b> The context is restricted to {@link JwtDecoderConfig}, with no web
 * environment. Secret alignment is a property of the decoder and its configuration; it does not
 * depend on Redis, the rate limiter, HTTP routing, or a downstream service being reachable. An
 * earlier revision of this test asserted alignment by sending proxied HTTP requests through the
 * full filter chain and accepting any status other than 401 — which passes on a 429 or a 5xx, and
 * which failed in CI on a five-second timeout that had nothing to do with JWT secrets.
 *
 * <p>Full filter-chain behaviour — valid, missing, expired, tampered, and sub-less tokens over
 * real HTTP — is covered by {@link JwtFilterIntegrationTest}, which is where it belongs.
 *
 * <p>Validates: Requirements 1.1, 1.2
 */
@Tag("integration")
@SpringBootTest(
        classes = JwtDecoderConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class JwtSecretAlignmentPropertyTest {

    /** A valid HS256 secret that is *not* the configured one. Must be >= 32 bytes. */
    private static final String WRONG_SECRET = "a-different-secret-of-at-least-32-characters";

    @Autowired
    private ReactiveJwtDecoder jwtDecoder;

    /**
     * Random {@code sub} values, to show alignment is a property of the secret rather than of any
     * particular subject.
     */
    static Stream<String> randomUuidSubValues() {
        return Stream.generate(() -> UUID.randomUUID().toString()).limit(10);
    }

    @ParameterizedTest(name = "token minted for sub={0} decodes with its subject intact")
    @MethodSource("randomUuidSubValues")
    void tokenMintedWithConfiguredSecretDecodesWithSubjectIntact(String sub) {
        String token = TestJwtFactory.mint(sub, Duration.ofHours(1));

        Jwt decoded = jwtDecoder.decode(token).block();

        assertThat(decoded)
                .as("token minted with TestJwtFactory.TEST_SECRET should decode against the "
                    + "gateway's configured decoder")
                .isNotNull();
        assertThat(decoded.getSubject())
                .as("decoded sub must round-trip unchanged")
                .isEqualTo(sub);
    }

    @Test
    void tokenMintedWithADifferentSecretIsRejected() {
        String token = TestJwtFactory.mint(
                UUID.randomUUID().toString(), Duration.ofHours(1), WRONG_SECRET);

        assertThatThrownBy(() -> jwtDecoder.decode(token).block())
                .as("a token signed with a secret other than the configured one must not decode — "
                    + "without this, the positive case above proves only that something accepted "
                    + "the token, not that the secrets are aligned")
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenMintedWithConfiguredSecretIsRejected() {
        String token = TestJwtFactory.mint(
                UUID.randomUUID().toString(), Duration.ofMinutes(-5));

        assertThatThrownBy(() -> jwtDecoder.decode(token).block())
                .as("signature alignment must not make the decoder accept an expired token")
                .isInstanceOf(JwtException.class);
    }
}
