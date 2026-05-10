package com.wealth.market.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares Kafka topics owned by market-data-service.
 *
 * <p>Spring Kafka's {@code KafkaAdmin} calls {@code CreateTopics} on startup for every
 * {@link NewTopic} bean it finds. The broker silently ignores the request when the topic
 * already exists (idempotent). This means:
 *
 * <ul>
 *   <li>First deploy: topics are created automatically — no manual Aiven Console step needed.
 *   <li>Subsequent deploys: no-op — existing topics and their data are untouched.
 *   <li>Aiven free-tier topic deletion (after 24 h inactivity): topics are recreated on the
 *       next service startup, so the demo recovers automatically without operator intervention.
 * </ul>
 *
 * <p>Partition and replication counts are configurable via env vars so they can be tuned
 * per environment without a code change. Defaults are safe for Aiven free tier (1 broker,
 * replication factor 1 is the maximum allowed).
 */
@Configuration
public class KafkaTopicConfig {

    /** Number of partitions for the main price-update topic. */
    @Value("${kafka.topics.market-prices.partitions:3}")
    private int marketPricesPartitions;

    /**
     * Replication factor. Aiven free tier has 1 broker, so the maximum is 1.
     * Override to 2+ on paid tiers with multiple brokers.
     */
    @Value("${kafka.topics.market-prices.replication-factor:1}")
    private short marketPricesReplicationFactor;

    /**
     * Main price-update topic. Produced by market-data-service on every price refresh
     * and startup hydration. Consumed by portfolio-service and insight-service.
     */
    @Bean
    public NewTopic marketPricesTopic() {
        return TopicBuilder.name("market-prices")
                .partitions(marketPricesPartitions)
                .replicas(marketPricesReplicationFactor)
                .build();
    }

    /**
     * Dead-letter topic for malformed or unprocessable price events.
     * Spring Kafka's {@link org.springframework.kafka.listener.DeadLetterPublishingRecoverer}
     * routes failed events here after retries are exhausted. Pre-creating it avoids a race
     * where the first malformed event fails because the DLT doesn't exist yet.
     */
    @Bean
    public NewTopic marketPricesDltTopic() {
        return TopicBuilder.name("market-prices.DLT")
                .partitions(marketPricesPartitions)
                .replicas(marketPricesReplicationFactor)
                .build();
    }
}
