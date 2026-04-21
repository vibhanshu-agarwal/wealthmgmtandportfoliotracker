package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.wealth.infrastructure.TruststoreExtractor;

@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        TruststoreExtractor.extract("kafka-truststore.jks", "REDIS_TRUSTSTORE_PATH");
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

}
