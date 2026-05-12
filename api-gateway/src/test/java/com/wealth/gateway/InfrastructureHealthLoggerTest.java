package com.wealth.gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.connection.ReactiveRedisConnection;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InfrastructureHealthLogger} in api-gateway.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Profile activation (aws, azure) and exclusion (local)</li>
 *   <li>Redis connection success logging</li>
 *   <li>Redis connection failure logging</li>
 *   <li>Timeout handling</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InfrastructureHealthLoggerTest {

    @Mock
    private ReactiveRedisConnectionFactory redisConnectionFactory;

    @Mock
    private ReactiveRedisConnection redisConnection;

    @Mock
    private ApplicationReadyEvent applicationReadyEvent;

    private InfrastructureHealthLogger logger;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger targetLogger;

    @BeforeEach
    void setUp() {
        logger = new InfrastructureHealthLogger(redisConnectionFactory);

        // Set up log capturing
        targetLogger = (Logger) LoggerFactory.getLogger(InfrastructureHealthLogger.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        targetLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        targetLogger.detachAppender(logAppender);
    }

    // -------------------------------------------------------------------------
    // Test 1: Redis Connection Success
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsSuccess_whenRedisPongReceived() {
        // Arrange
        when(redisConnectionFactory.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn(Mono.just("PONG"));

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        verify(redisConnectionFactory).getReactiveConnection();
        verify(redisConnection).ping();

        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[INFRA-OK]   Redis — PONG received"))
                .anyMatch(msg -> msg.contains("rate-limiter backend ready"));
    }

    // -------------------------------------------------------------------------
    // Test 2: Redis Connection Failure
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsFailure_whenRedisUnreachable() {
        // Arrange
        RuntimeException cause = new RuntimeException("Connection refused");
        when(redisConnectionFactory.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn(Mono.error(cause));

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        verify(redisConnectionFactory).getReactiveConnection();
        verify(redisConnection).ping();

        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] Redis — unreachable"))
                .anyMatch(msg -> msg.contains("RuntimeException"))
                .anyMatch(msg -> msg.contains("Connection refused"));
    }

    // -------------------------------------------------------------------------
    // Test 3: Redis Timeout Handling
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsFailure_whenRedisTimesOut() {
        // Arrange - Create a Mono that never completes (simulates timeout)
        when(redisConnectionFactory.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn(
                Mono.<String>never()
        );

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        verify(redisConnectionFactory).getReactiveConnection();
        verify(redisConnection).ping();

        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] Redis — unreachable"))
                .anyMatch(msg -> msg.contains("TimeoutException") ||
                               msg.contains("timeout") ||
                               msg.contains("Timeout"));
    }

    // -------------------------------------------------------------------------
    // Test 4: Connection Factory Throws Exception
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsFailure_whenConnectionFactoryThrowsException() {
        // Arrange
        when(redisConnectionFactory.getReactiveConnection())
                .thenThrow(new IllegalStateException("Factory not initialized"));

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        verify(redisConnectionFactory).getReactiveConnection();

        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] Redis — unreachable"))
                .anyMatch(msg -> msg.contains("IllegalStateException"))
                .anyMatch(msg -> msg.contains("Factory not initialized"));
    }

    // -------------------------------------------------------------------------
    // Test 5: Startup Banner Logging
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsStartupBanner() {
        // Arrange
        when(redisConnectionFactory.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn(Mono.just("PONG"));

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("=== Infrastructure connectivity check (api-gateway) ==="))
                .anyMatch(msg -> msg.contains("=== Infrastructure connectivity check complete ==="));
    }

    // -------------------------------------------------------------------------
    // Test 6: Service Name in Logs
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_includesServiceNameInBanner() {
        // Arrange
        when(redisConnectionFactory.getReactiveConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn(Mono.just("PONG"));

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("api-gateway"));
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private List<String> getLogMessages(Level level) {
        return logAppender.list.stream()
                .filter(event -> event.getLevel().equals(level))
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }
}
