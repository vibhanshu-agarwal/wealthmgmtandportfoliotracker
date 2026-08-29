package com.wealth.gateway;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtSignerTest {

    private static final String SECRET = "test-secret-for-jwt-signer-min-32-chars-long";

    @Test
    void issuedTokensCarryDistinctNonBlankJtiClaims() throws Exception {
        JwtSigner signer = new JwtSigner(SECRET);

        String first = signer.signHs256("user-a", "a@b.com", "Alice");
        String second = signer.signHs256("user-b", "b@c.com", "Bob");

        String firstJti = SignedJWT.parse(first).getJWTClaimsSet().getJWTID();
        String secondJti = SignedJWT.parse(second).getJWTClaimsSet().getJWTID();

        assertThat(firstJti).isNotBlank();
        assertThat(secondJti).isNotBlank();
        assertThat(firstJti).isNotEqualTo(secondJti);
    }

    @Test
    void fourArgOverloadSetsRoClaimAndLeavesOtherClaimsUnchanged() throws Exception {
        JwtSigner signer = new JwtSigner(SECRET);

        String token = signer.signHs256("user-1", "a@b.com", "Alice", true);
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("user-1");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("email")).isEqualTo("a@b.com");
        assertThat(parsed.getJWTClaimsSet().getStringClaim("name")).isEqualTo("Alice");
        assertThat(parsed.getJWTClaimsSet().getBooleanClaim("ro")).isTrue();

        long expirySeconds = (parsed.getJWTClaimsSet().getExpirationTime().getTime()
                - parsed.getJWTClaimsSet().getIssueTime().getTime()) / 1000;
        assertThat(expirySeconds).isEqualTo(3600);
    }

    @Test
    void threeArgOverloadDefaultsRoToFalse() throws Exception {
        JwtSigner signer = new JwtSigner(SECRET);

        String token = signer.signHs256("user-2", "b@c.com", "Bob");
        SignedJWT parsed = SignedJWT.parse(token);

        assertThat(parsed.getJWTClaimsSet().getBooleanClaim("ro")).isFalse();
    }
}
