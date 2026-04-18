package com.wealth.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Probes Redis and Kafka after startup and logs a structured summary
 * scannable in CloudWatch Logs.
 *
 * <p>Log prefixes:
 * <ul>
 *   <li>{@code [INFRA-OK]}   — dependency reachable</li>
 *   <li>{@code [INFRA-FAIL]} — dependency unreachable; includes root cause</li>
 * </ul>
 *
 * <p>Runs only under the {@code aws} profile. Fires on {@link ApplicationReadyEvent}
 * so it never blocks startup.
 */
@Component
@Profile("aws")
class InfrastructureHealthLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureHealthLogger.class);

    private final RedisConnectionFactory redisConnectionFactory;
    private final KafkaAdmin kafkaAdmin;

    InfrastructureHealthLogger(RedisConnectionFactory redisConnectionFactory,
                                KafkaAdmin kafkaAdmin) {
        this.redisConnectionFactory = redisConnectionFactory;
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("=== Infrastructure connectivity check (insight-service) ===");
        probeRedis();
        probeKafka();
        log.info("=== Infrastructure connectivity check complete ===");
    }

    private void probeRedis() {
        try {
            redisConnectionFactory.getConnection().ping();
            log.info("[INFRA-OK]   Redis — PONG received (insight cache ready)");
        } catch (Exception ex) {
            log.error("[INFRA-FAIL] Redis — unreachable; insight caching will be unavailable. "
                    + "Check REDIS_URL. cause={}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void probeKafka() {
        try {
            kafkaAdmin.describeTopics("market-prices");
            log.info("[INFRA-OK]   Kafka — broker reachable (market-prices topic accessible)");
        } catch (Exception ex) {
            log.error("[INFRA-FAIL] Kafka — unreachable; insight events will not be received. "
                    + "Check KAFKA_BOOTSTRAP_SERVERS. cause={}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
