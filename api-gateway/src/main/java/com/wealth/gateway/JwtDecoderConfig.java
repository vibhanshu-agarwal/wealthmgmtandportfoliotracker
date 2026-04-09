package com.wealth.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
public class JwtDecoderConfig {

    /**
     * Local profile: HMAC-SHA256 symmetric decoder.
     * Key is read from ${auth.jwt.secret} — injected from AUTH_JWT_SECRET env var.
     * Startup fails fast if the property is blank.
     */
    @Bean
    @Profile("local")
    ReactiveJwtDecoder localJwtDecoder(@Value("${auth.jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must not be blank under the 'local' profile. " +
                    "Set the AUTH_JWT_SECRET environment variable or configure " +
                    "auth.jwt.secret in application-local.yml.");
        }
        SecretKeySpec key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * AWS profile: RS256 asymmetric decoder via JWK URI.
     * NimbusReactiveJwtDecoder caches the JWK set and refreshes on key rotation automatically.
     */
    @Bean
    @Profile("aws")
    ReactiveJwtDecoder awsJwtDecoder(@Value("${auth.jwk-uri}") String jwkUri) {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }
}
