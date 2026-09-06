package com.wealth.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;
import io.micrometer.observation.ObservationRegistry;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoLoginResetProperties.class)
public class DemoLoginResetConfiguration {
    @Bean
    WebClient.Builder demoLoginResetWebClientBuilder(ObservationRegistry observationRegistry) {
        return WebClient.builder().observationRegistry(observationRegistry);
    }

    @Bean
    DemoLoginResetClient demoLoginResetClient(WebClient.Builder demoLoginResetWebClientBuilder,
                                              GatewayLoopbackTargetProvider loopbackTargetProvider,
                                              InternalApiKeyProvider internalApiKeyProvider,
                                              CloudFrontOriginSecretProvider originSecretProvider,
                                              DemoLoginResetProperties properties,
                                              ObjectProvider<Clock> clockProvider,
                                              tools.jackson.databind.ObjectMapper objectMapper) {
        return new DemoLoginResetClient(demoLoginResetWebClientBuilder, loopbackTargetProvider,
                internalApiKeyProvider, originSecretProvider, properties,
                clockProvider.getIfUnique(Clock::systemUTC), objectMapper);
    }
}
