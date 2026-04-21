package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.wealth.infrastructure.KafkaTruststoreExtractor;

@SpringBootApplication
public class InsightApplication {
    public static void main(String[] args) {
        KafkaTruststoreExtractor.extract();
        SpringApplication.run(InsightApplication.class, args);
    }
}
