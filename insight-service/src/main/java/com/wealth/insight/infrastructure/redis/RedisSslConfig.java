package com.wealth.insight.infrastructure.redis;

import io.lettuce.core.SslOptions;
import io.lettuce.core.resource.ClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

import java.io.File;

/**
 * Customizes Lettuce Redis client to support SSL with a custom truststore.
 * Only active in the 'prod' profile where we connect to Upstash Redis over TLS.
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
                // Remove "file:" prefix if present for physical file access
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
