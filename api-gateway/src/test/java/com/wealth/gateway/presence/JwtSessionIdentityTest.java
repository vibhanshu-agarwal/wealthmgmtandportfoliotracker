package com.wealth.gateway.presence;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSessionIdentityTest {

    @Test
    void blankSub_isRejectedForAuthentication() {
        JwtAuthenticationToken token = jwtToken("", "jti-1");

        assertThat(JwtSessionIdentity.fromPrincipal(token)).isEmpty();
    }

    @Test
    void missingJti_isLegacyNoPresencePath() {
        JwtAuthenticationToken token = jwtToken(DemoPresenceService.DEMO_USER_ID, null);

        Optional<JwtSessionIdentity> identity = JwtSessionIdentity.fromPrincipal(token);

        assertThat(identity).isPresent();
        assertThat(identity.get().hasPresenceSession()).isFalse();
    }

    @Test
    void demoAccountWithJti_hasPresenceSession() {
        JwtAuthenticationToken token = jwtToken(DemoPresenceService.DEMO_USER_ID, "jti-abc");

        JwtSessionIdentity identity = JwtSessionIdentity.fromPrincipal(token).orElseThrow();

        assertThat(identity.hasPresenceSession()).isTrue();
    }

    @Test
    void blankJti_isLegacyNoPresencePath() {
        JwtAuthenticationToken token = jwtToken(DemoPresenceService.DEMO_USER_ID, "");

        JwtSessionIdentity identity = JwtSessionIdentity.fromPrincipal(token).orElseThrow();

        assertThat(identity.hasPresenceSession()).isFalse();
    }

    @Test
    void nonDemoAccountWithJti_skipsPresenceSession() {
        JwtAuthenticationToken token = jwtToken("00000000-0000-0000-0000-000000000001", "jti-abc");

        JwtSessionIdentity identity = JwtSessionIdentity.fromPrincipal(token).orElseThrow();

        assertThat(identity.hasPresenceSession()).isFalse();
    }

    private static JwtAuthenticationToken jwtToken(String sub, String jti) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .claim("sub", sub)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600));
        if (jti != null) {
            builder.claim("jti", jti);
        }
        return new JwtAuthenticationToken(builder.build());
    }
}
