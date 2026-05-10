package com.wealth;

import com.wealth.infrastructure.TruststoreExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

class ApiGatewayApplicationTruststoreWiringTest {

    @Test
    void mainDoesNotExtractRedisTruststoreAndStartsApplication() {
        try (MockedStatic<TruststoreExtractor> extractor = mockStatic(TruststoreExtractor.class);
             MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {

            ApiGatewayApplication.main(new String[0]);

            // Redis (Upstash) uses Let's Encrypt — no custom truststore extraction needed.
            // Lettuce uses the JVM system cacerts (ISRG Root X1/X2 present on both AWS and Azure).
            extractor.verifyNoInteractions();
            springApplication.verify(() ->
                    SpringApplication.run(eq(ApiGatewayApplication.class), eq(new String[0])));
        }
    }
}
