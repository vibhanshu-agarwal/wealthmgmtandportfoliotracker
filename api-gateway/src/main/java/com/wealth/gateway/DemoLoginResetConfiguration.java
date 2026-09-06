package com.wealth.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import io.micrometer.observation.ObservationRegistry;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoLoginResetProperties.class)
public class DemoLoginResetConfiguration {
    @Bean
    Clock demoLoginResetClock() {
        return Clock.systemUTC();
    }

    @Bean
    WebClient.Builder demoLoginResetWebClientBuilder(ObservationRegistry observationRegistry) {
        return WebClient.builder().observationRegistry(observationRegistry);
    }
}
