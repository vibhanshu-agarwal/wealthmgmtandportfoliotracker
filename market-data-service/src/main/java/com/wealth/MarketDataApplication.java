package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.wealth.infrastructure.TruststoreExtractor;

@SpringBootApplication
public class MarketDataApplication {
    public static void main(String[] args) {
        TruststoreExtractor.extract("kafka-truststore.jks", "KAFKA_TRUSTSTORE_PATH");
        SpringApplication.run(MarketDataApplication.class, args);
    }
}
