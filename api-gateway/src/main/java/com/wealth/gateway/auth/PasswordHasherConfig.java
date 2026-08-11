package com.wealth.gateway.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordHasherConfig {

    private static final int BCRYPT_COST = 12;

    /**
     * A fixed, valid bcrypt(cost=12) hash that never matches any submitted password — used only
     * to equalize verification time on the unknown-email login path (Req 3.4). Generated once
     * offline via `new BCryptPasswordEncoder(12).encode(UUID.randomUUID().toString())` and pasted
     * here as a constant; it is never regenerated at runtime.
     */
    public static final String DUMMY_PASSWORD_HASH =
            "$2a$12$qVTZ3mciEmQsdeyJxGKSHOxOkjktE.3bpOxDWCNiFxXOvHtLnw95.";

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }
}
