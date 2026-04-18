package com.wealth.portfolio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Probes PostgreSQL, Kafka, and Redis (aws profile) after startup and logs a
 * structured summary scannable in CloudWatch Logs.
 *
 * <p>Log prefixes:
 * <ul>
 *   <li>{@code [INFRA-OK]}   — dependency reachable</li>
 *   <li>{@code [INFRA-FAIL]} — dependency unreachable; includes root cause</li>
 * </ul>
 *
 * <p>Runs only under the {@code aws} profile. Fires on {@link ApplicationReadyEvent}
 * so it never blocks startup or Flyway migrations.
 */
@Component
@Profile("aws")
class InfrastructureHealthLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureHealthLogger.class);

    private final JdbcTemplate jdbcTemplate;
    private final KafkaAdmin kafkaAdmin;
    private final RedisConnectionFactory redisConnectionFactory;

    InfrastructureHealthLogger(JdbcTemplate jdbcTemplate,
                                KafkaAdmin kafkaAdmin,
                                RedisConnectionFactory redisConnectionFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaAdmin = kafkaAdmin;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("=== Infrastructure connectivity check (portfolio-service) ===");
        probePostgres();
        probeKafka();
        probeRedis();
        log.info("=== Infrastructure connectivity check complete ===");
    }

    private void probePostgres() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            log.info("[INFRA-OK]   PostgreSQL — SELECT 1 succeeded (Neon/RDS reachable)");
        } catch (Exception ex) {
            log.error("[INFRA-FAIL] PostgreSQL — unreachable; portfolio reads/writes will fail. "
                    + "Check POSTGRES_CONNECTION_STRING. cause={}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void probeKafka() {
        try {
            // describeCluster() is a lightweight admin call that verifies broker connectivity.
            kafkaAdmin.describeTopics("market-prices");
            log.info("[INFRA-OK]   Kafka — broker reachable (market-prices topic accessible)");
        } catch (Exception ex) {
            log.error("[INFRA-FAIL] Kafka — unreachable; price update events will not be consumed. "
                    + "Check KAFKA_BOOTSTRAP_SERVERS. cause={}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void probeRedis() {
        try {
            redisConnectionFactory.getConnection().ping();
            log.info("[INFRA-OK]   Redis — PONG received (portfolio-analytics cache ready)");
        } catch (Exception ex) {
            log.error("[INFRA-FAIL] Redis — unreachable; analytics cache will be unavailable. "
                    + "Check REDIS_URL. cause={}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
