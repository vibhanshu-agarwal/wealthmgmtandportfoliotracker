package com.wealth.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Probes infrastructure dependencies after startup and logs a structured summary
 * that is easy to scan in CloudWatch Logs.
 *
 * <p>Each line uses a fixed prefix so you can filter by keyword:
 * <ul>
 *   <li>{@code [INFRA-OK]}   — dependency reachable</li>
 *   <li>{@code [INFRA-FAIL]} — dependency unreachable; includes the root cause</li>
 * </ul>
 *
 * <p>Runs only under the {@code aws} profile — local Docker Compose is assumed healthy.
 * Fires on {@link ApplicationReadyEvent} so it never blocks startup.
 */
@Component
@Profile("aws")
class InfrastructureHealthLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureHealthLogger.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private final ReactiveRedisConnectionFactory redisConnectionFactory;

    InfrastructureHealthLogger(ReactiveRedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("=== Infrastructure connectivity check (api-gateway) ===");
        probeRedis();
        log.info("=== Infrastructure connectivity check complete ===");
    }

    private void probeRedis() {
        try {
            redisConnectionFactory
                    .getReactiveConnection()
                    .ping()
                    .timeout(PROBE_TIMEOUT)
                    .block(PROBE_TIMEOUT);
            log.info("[INFRA-OK]   Redis — PONG received (rate-limiter backend ready)");
        } catch (Exception ex) {
            log.error("[INFRA-FAIL] Redis — unreachable; rate limiting will degrade. "
                    + "Check REDIS_URL. cause={}: {}", ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
