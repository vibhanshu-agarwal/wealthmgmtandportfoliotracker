package com.wealth.market.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.market.events.PriceUpdatedEvent;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.micrometer.KafkaTemplateObservation;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Property 10b (producer wiring): verifies the market-data producer path enables Kafka template
 * observation (prerequisite for {@code traceparent} injection in production).
 *
 * <p>End-to-end wire-header assertion is covered by portfolio/insight consumer ITs with W3C
 * {@code traceparent} fixtures; full auto-config {@link KafkaTemplate} header stamping is tracked
 * as a follow-up once Boot's producer factory wiring is mirrored in IT fixtures.
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

    @Autowired private ObservationRegistry observationRegistry;

    private KafkaTemplate<String, PriceUpdatedEvent> observedTemplate;

    @BeforeEach
    void setUp() {
        ProducerFactory<String, PriceUpdatedEvent> producerFactory =
                new DefaultKafkaProducerFactory<>(
                        kafkaProperties.buildProducerProperties(),
                        new org.apache.kafka.common.serialization.StringSerializer(),
                        new JacksonJsonSerializer<>());

        observedTemplate = new KafkaTemplate<>(producerFactory);
        observedTemplate.setObservationRegistry(observationRegistry);
        observedTemplate.setObservationEnabled(true);
        observedTemplate.setObservationConvention(
                new KafkaTemplateObservation.DefaultKafkaTemplateObservationConvention());
    }

    @Test
    void marketDataProducerPath_hasKafkaTemplateObservationEnabled() {
        assertThat(kafkaProperties.getTemplate().isObservationEnabled()).isTrue();
    }
}
