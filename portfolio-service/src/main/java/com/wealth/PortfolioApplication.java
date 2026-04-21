package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.wealth.infrastructure.KafkaTruststoreExtractor;

@SpringBootApplication
@EnableAsync
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class PortfolioApplication {
    public static void main(String[] args) {
        KafkaTruststoreExtractor.extract();
        SpringApplication.run(PortfolioApplication.class, args);
    }
}
