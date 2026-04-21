package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.wealth.infrastructure.TruststoreExtractor;

@SpringBootApplication
public class InsightApplication {
    public static void main(String[] args) {
        TruststoreExtractor.extract("truststore.jks", "KAFKA_TRUSTSTORE_PATH");
        TruststoreExtractor.extract("truststore.jks", "REDIS_TRUSTSTORE_PATH");
        SpringApplication.run(InsightApplication.class, args);
    }
}
