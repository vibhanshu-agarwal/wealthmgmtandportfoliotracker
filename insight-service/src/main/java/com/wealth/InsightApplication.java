package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.wealth.infrastructure.TruststoreExtractor;

@SpringBootApplication
public class InsightApplication {
    public static void main(String[] args) {
        // Kafka (Aiven) uses a self-signed CA not in the public trust store — custom JKS required.
        TruststoreExtractor.extract("kafka-truststore.jks", "KAFKA_TRUSTSTORE_PATH");
        // Redis (Upstash) uses a publicly-trusted Let's Encrypt cert (ISRG Root X1/X2).
        // The JVM system cacerts already contains those roots on both AWS (Corretto) and Azure
        // (Mariner JDK 21). Pointing Lettuce at the Aiven-only JKS would exclude the public
        // roots and cause PKIX failures. Let Lettuce use the system truststore instead.
        SpringApplication.run(InsightApplication.class, args);
    }
}
