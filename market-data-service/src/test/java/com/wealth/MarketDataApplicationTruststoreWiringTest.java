package com.wealth;

import com.wealth.infrastructure.TruststoreExtractor;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

class MarketDataApplicationTruststoreWiringTest {

    @Test
    void mainExtractsKafkaTruststoreBeforeStartup() {
        try (MockedStatic<TruststoreExtractor> extractor = mockStatic(TruststoreExtractor.class);
             MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {

            MarketDataApplication.main(new String[0]);

            extractor.verify(() ->
                    TruststoreExtractor.extract("kafka-truststore.jks", "KAFKA_TRUSTSTORE_PATH"));
            springApplication.verify(() ->
                    SpringApplication.run(eq(MarketDataApplication.class), eq(new String[0])));
        }
    }
}
