package com.wealth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.wealth.infrastructure.TruststoreExtractor;

@SpringBootApplication
@EnableAsync
@EnableCaching
@EnableScheduling
@ConfigurationPropertiesScan
public class PortfolioApplication {
    public static void main(String[] args) {
        TruststoreExtractor.extract("truststore.jks", "KAFKA_TRUSTSTORE_PATH");
        SpringApplication.run(PortfolioApplication.class, args);
    }
}
