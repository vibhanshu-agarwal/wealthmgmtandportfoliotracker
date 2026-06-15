package com.wealth.market.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Property 10b (producer config binding): {@code spring.kafka.template.observation-enabled=true}
 * binds on the market-data producer path.
 *
 * <p>Wire-level {@code traceparent} injection via auto-configured {@code KafkaTemplate} is deferred
 * (see migration spec task 11.2 partial note).
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(
        properties = {
            "spring.data.mongodb.uri=mongodb://localhost:27017/market_db",
            "management.tracing.export.enabled=false",
            "management.otlp.metrics.export.enabled=false"
        })
@EmbeddedKafka(partitions = 1, topics = "market-prices")
@TestPropertySource(properties = "spring.kafka.template.observation-enabled=true")
class KafkaTemplateTracePropagationIT {

    @Autowired private KafkaProperties kafkaProperties;

    @Test
    void marketDataProducerPath_hasKafkaTemplateObservationEnabled() {
        assertThat(kafkaProperties.getTemplate().isObservationEnabled()).isTrue();
    }
}
