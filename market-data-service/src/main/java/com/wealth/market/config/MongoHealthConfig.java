package com.wealth.market.config;


import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Replaces Spring Boot's default MongoDB health check with an Atlas-compatible ping.
 *
 * <p>The default indicator can issue a driver/server-status style check that Atlas
 * evaluates against the {@code local} database. The application user is intentionally
 * scoped to the market database, so Atlas returns error 8000 even though ordinary
 * reads/writes and {@code { ping: 1 }} against the configured database succeed.</p>
 */
@Configuration(proxyBeanMethods = false)
class MongoHealthConfig {

    @Bean(name = "mongoHealthIndicator")
    HealthIndicator mongoHealthIndicator(MongoTemplate mongoTemplate) {
        return () -> {
            try {
                mongoTemplate.executeCommand("{ ping: 1 }");
                return org.springframework.boot.health.contributor.Health.up()
                        .withDetail("check", "ping")
                        .build();
            } catch (Exception ex) {
                return Health.down(ex)
                        .withDetail("check", "ping")
                        .build();
            }
        };
    }
}