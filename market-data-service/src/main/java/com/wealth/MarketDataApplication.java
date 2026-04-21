package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.wealth.infrastructure.KafkaTruststoreExtractor;

@SpringBootApplication
public class MarketDataApplication {
    public static void main(String[] args) {
        KafkaTruststoreExtractor.extract();
        SpringApplication.run(MarketDataApplication.class, args);
    }
}
