package com.wealth;

import com.wealth.infrastructure.TruststoreExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

class InsightApplicationTruststoreWiringTest {

    @Test
    void mainExtractsKafkaTruststoreBeforeStartup() {
        try (MockedStatic<TruststoreExtractor> extractor = mockStatic(TruststoreExtractor.class);
             MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {

            InsightApplication.main(new String[0]);

            // Kafka (Aiven) still requires a custom truststore — Aiven CA is not publicly trusted.
            extractor.verify(() ->
                    TruststoreExtractor.extract("kafka-truststore.jks", "KAFKA_TRUSTSTORE_PATH"));
            // Redis (Upstash) uses Let's Encrypt — no custom truststore extraction needed.
            extractor.verifyNoMoreInteractions();
            springApplication.verify(() ->
                    SpringApplication.run(eq(InsightApplication.class), eq(new String[0])));
        }
    }
}
