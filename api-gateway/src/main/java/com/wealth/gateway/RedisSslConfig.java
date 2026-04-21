package com.wealth.gateway;

import io.lettuce.core.SslOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;

/**
 * Customizes Lettuce Redis client to support SSL with a custom truststore for API Gateway rate limiting.
 * Only active in the 'prod' profile.
 */
@Configuration
@Profile("prod")
public class RedisSslConfig {

    @Value("${REDIS_TRUSTSTORE_PATH:}")
    private String truststorePath;

    @Value("${REDIS_TRUSTSTORE_PASSWORD:changeit}")
    private String truststorePassword;

    @Bean
    public LettuceClientConfigurationBuilderCustomizer sslCustomizer() {
        return builder -> {
            if (truststorePath != null && !truststorePath.isBlank()) {
                String path = truststorePath.startsWith("file:") ? truststorePath.substring(5) : truststorePath;
                File truststoreFile = new File(path);

                if (truststoreFile.exists()) {
                    SslOptions sslOptions = SslOptions.builder()
                            .truststore(truststoreFile, truststorePassword)
                            .build();

                    builder.clientOptions(io.lettuce.core.ClientOptions.builder()
                            .sslOptions(sslOptions)
                            .build());
                    
                    builder.useSsl();
                }
            }
        };
    }
}
