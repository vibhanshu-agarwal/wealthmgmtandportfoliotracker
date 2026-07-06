package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling is required for RedisRateLimitStateLogger's @Scheduled probe
// (com.wealth.gateway, @Profile({"aws","azure"})). Harmless under other profiles since no
// @Scheduled bean is registered there.
@SpringBootApplication
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
