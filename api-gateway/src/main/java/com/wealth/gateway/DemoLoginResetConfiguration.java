package com.wealth.gateway;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.reactive.function.client.WebClient;
import io.micrometer.observation.ObservationRegistry;

import java.time.Clock;
import java.util.function.LongSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoLoginResetProperties.class)
public class DemoLoginResetConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "demoLoginResetNanoClock")
    LongSupplier demoLoginResetNanoClock() {
        return System::nanoTime;
    }

    @Bean
    DemoLoginResetDiagnostics demoLoginResetDiagnostics(ReplicaTokenProvider replicaTokenProvider) {
        return new DemoLoginResetDiagnostics(replicaTokenProvider);
    }

    @Bean
    @ConditionalOnMissingBean(DemoLoginResetOrchestrator.CompletionHandler.class)
    DemoLoginResetOrchestrator.CompletionHandler demoLoginResetCompletionHandler() {
        return result -> reactor.core.publisher.Mono.empty();
    }

    @Bean
    DemoLoginResetOrchestrator demoLoginResetOrchestrator(DemoLoginResetClient client,
            DemoLoginResetProperties properties, InternalApiKeyProvider keyProvider,
            CloudFrontOriginSecretProvider originProvider, DemoLoginResetDiagnostics diagnostics,
            @org.springframework.beans.factory.annotation.Qualifier("demoLoginResetNanoClock") LongSupplier nanoClock,
            DemoLoginResetOrchestrator.CompletionHandler completionHandler) {
        return new DemoLoginResetOrchestrator(client, properties, keyProvider, originProvider,
                diagnostics, nanoClock, completionHandler);
    }

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
