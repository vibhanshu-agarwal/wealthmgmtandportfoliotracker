package com.wealth.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link RedisRateLimitStateLogger}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>The degraded WARN fires at most once per 60s while Redis stays down (throttling gate).
 *   <li>A single recovery INFO fires on a down→up transition.
 *   <li>No WARN fires while Redis stays up.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RedisRateLimitStateLoggerTest {

    @Mock
    private ReactiveRedisConnectionFactory redisConnectionFactory;

    @Mock
    private ReactiveRedisConnection redisConnection;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger targetLogger;

    @BeforeEach
    void setUp() {
        targetLogger = (Logger) LoggerFactory.getLogger(RedisRateLimitStateLogger.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        targetLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        targetLogger.detachAppender(logAppender);
    }

    private static Clock fixedClockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** Mutable test double so a single logger instance can be probed across advancing time. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant initial) {
            this.instant = initial;
        }

        void advanceTo(Instant next) {
            this.instant = next;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private void stubPingFailure() {
        when(redisConnectionFactory.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn(Mono.error(new RuntimeException("Connection refused")));
    }

    private void stubPingSuccess() {
        when(redisConnectionFactory.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn(Mono.just("PONG"));
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel().equals(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private List<String> infoMessages() {
        return logAppender.list.stream()
                .filter(event -> event.getLevel().equals(Level.INFO))
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // WARN throttling: at most once per 60s while down
    // -------------------------------------------------------------------------

    @Test
    void firstProbeFailureLogsWarn() {
        stubPingFailure();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        RedisRateLimitStateLogger logger = new RedisRateLimitStateLogger(redisConnectionFactory, fixedClockAt(t0));

        logger.probe();

        assertThat(warnMessages()).hasSize(1)
                .anyMatch(msg -> msg.contains("[INFRA-DEGRADED]") && msg.contains("Redis"));
    }

    @Test
    void secondProbeWithin60sDoesNotLogAnotherWarn() {
        stubPingFailure();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(t0);

        RedisRateLimitStateLogger logger = new RedisRateLimitStateLogger(redisConnectionFactory, clock);
        logger.probe(); // t0 -> WARN #1

        // 30s later — still within the 60s throttle window
        clock.advanceTo(t0.plusSeconds(30));
        logger.probe();

        assertThat(warnMessages()).hasSize(1);
    }

    @Test
    void probeAfter60sLogsAnotherWarn() {
        stubPingFailure();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(t0);

        RedisRateLimitStateLogger logger = new RedisRateLimitStateLogger(redisConnectionFactory, clock);
        logger.probe(); // t0 -> WARN #1

        clock.advanceTo(t0.plus(Duration.ofSeconds(61)));
        logger.probe();

        assertThat(warnMessages()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Recovery INFO: fires once on down->up transition
    // -------------------------------------------------------------------------

    @Test
    void recoveryTransitionLogsSingleInfo() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        RedisRateLimitStateLogger logger = new RedisRateLimitStateLogger(redisConnectionFactory, fixedClockAt(t0));

        stubPingFailure();
        logger.probe(); // down

        stubPingSuccess();
        logger.probe(); // down -> up transition

        assertThat(infoMessages()).hasSize(1)
                .anyMatch(msg -> msg.contains("[INFRA-OK]") && msg.contains("recovered"));
    }

    @Test
    void noWarnWhileRedisStaysUp() {
        stubPingSuccess();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        RedisRateLimitStateLogger logger = new RedisRateLimitStateLogger(redisConnectionFactory, fixedClockAt(t0));

        logger.probe();
        logger.probe();

        assertThat(warnMessages()).isEmpty();
        // First probe with previouslyUp already true does not count as a "transition".
        assertThat(infoMessages()).isEmpty();
    }

    @Test
    void repeatedFailureDoesNotLogRecoveryInfo() {
        stubPingFailure();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        RedisRateLimitStateLogger logger = new RedisRateLimitStateLogger(redisConnectionFactory, fixedClockAt(t0));

        logger.probe();
        logger.probe();

        assertThat(infoMessages()).isEmpty();
    }
}
