package com.wealth.insight;

import com.wealth.market.events.PriceUpdatedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
class InsightKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(InsightKafkaConfig.class);

    /**
     * Configures resilient Kafka consumer factory for PriceUpdatedEvent deserialization
     */
    @Bean
    ConsumerFactory<String, PriceUpdatedEvent> insightConsumerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, org.apache.kafka.common.serialization.StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JacksonJsonDeserializer.class);
        props.put(JacksonJsonDeserializer.TRUSTED_PACKAGES, "*");
        props.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, PriceUpdatedEvent.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    DefaultErrorHandler insightErrorHandler() {
        // Retry up to 3 times with exponential backoff (1s → 2s → 4s), then log and skip.
        // This handles record-processing errors (deserialization failures, downstream errors).
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(3);

        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) ->
                log.error("[INSIGHT-DLQ] Skipping unrecoverable record from {} offset={}: {}",
                        record.topic(), record.offset(), ex.getMessage()),
                backOff
        );
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PriceUpdatedEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, PriceUpdatedEvent> insightConsumerFactory,
            DefaultErrorHandler insightErrorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, PriceUpdatedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(insightConsumerFactory);
        factory.setCommonErrorHandler(insightErrorHandler);
        return factory;
    }
}
