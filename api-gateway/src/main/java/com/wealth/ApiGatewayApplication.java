package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling is required for RedisRateLimitStateLogger's @Scheduled probe
// (com.wealth.gateway, @Profile({"aws","azure"})). Harmless under other profiles since no
// @Scheduled bean is registered there.
//
// JDBC autoconfiguration is excluded here and wired explicitly (see
// com.wealth.gateway.auth.GatewayAuthDataConfig) because just having spring-boot-starter-jdbc +
// the Postgres driver on the classpath makes DataSourceAutoConfiguration require a resolvable
// spring.datasource.url for EVERY profile's ApplicationContext, not only the ones (local, prod)
// that actually define it — aws/azure profiles never set spring.datasource.* and never should
// (Req 2.5). GatewayAuthDataConfig is @ConditionalOnProperty-gated so the beans below only get
// created where spring.datasource.url is actually present.
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class
})
@EnableScheduling
public class ApiGatewayApplication {
    public static void main(String[] args) {
        // Redis (Upstash) uses a publicly-trusted Let's Encrypt cert (ISRG Root X1/X2).
        // The JVM system cacerts already contains those roots on both AWS (Corretto) and Azure
        // (Mariner JDK 21). No custom truststore extraction needed — Lettuce uses the system
        // truststore and enables TLS automatically from the rediss:// scheme.
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

}
