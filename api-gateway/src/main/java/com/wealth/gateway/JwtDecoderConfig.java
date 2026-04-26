package com.wealth.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtDecoderConfig {

    /**
     * Current single-user auth path: HMAC-SHA256 symmetric decoder for both local and AWS.
     *
     * <p>{@link AuthController} is the token issuer and {@link JwtSigner} signs HS256
     * tokens with {@code auth.jwt.secret}. The active decoder must therefore validate
     * HS256 with the same secret. The previous AWS RS256/JWK decoder was reserved for a
     * future external IdP that is not present in the current system.
     */
    @Bean
    @Profile({"local", "aws"})
    ReactiveJwtDecoder hmacJwtDecoder(@Value("${auth.jwt.secret}") String secret) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must be at least 32 bytes for HS256 under local/aws profiles.");
        }
        SecretKeySpec key = new SecretKeySpec(secretBytes, "HmacSHA256");
        return NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
