package com.wealth.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtSigner {

    private final byte[] jwtSecretBytes;

    public JwtSigner(@Value("${auth.jwt.secret}") String jwtSecret) {
        this.jwtSecretBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean hasValidSecret() {
        return jwtSecretBytes.length >= 32;
    }

    public String signHs256(String userId, String email, String name) throws JOSEException {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject(userId)
                        .claim("email", email)
                        .claim("name", name)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(3600)))
                        .build());
        jwt.sign(new MACSigner(jwtSecretBytes));
        return jwt.serialize();
    }
}
