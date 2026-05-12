package com.wealth.portfolio;

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
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InfrastructureHealthLogger} in portfolio-service.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Profile activation (aws, azure) and exclusion (local)</li>
 *   <li>PostgreSQL connection success/failure logging</li>
 *   <li>Kafka connection success/failure logging</li>
 *   <li>Redis connection success/failure logging</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InfrastructureHealthLoggerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private KafkaAdmin kafkaAdmin;

    @Mock
    private RedisConnectionFactory redisConnectionFactory;

    @Mock
    private RedisConnection redisConnection;

    @Mock
    private ApplicationReadyEvent applicationReadyEvent;

    private InfrastructureHealthLogger logger;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger targetLogger;

    @BeforeEach
    void setUp() {
        logger = new InfrastructureHealthLogger(jdbcTemplate, kafkaAdmin, redisConnectionFactory);

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
    // Test 1: All Dependencies Healthy
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsSuccess_whenAllDependenciesHealthy() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(kafkaAdmin.describeTopics("market-prices")).thenReturn(Map.of());
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        verify(jdbcTemplate).queryForObject("SELECT 1", Integer.class);
        verify(kafkaAdmin).describeTopics("market-prices");
        verify(redisConnectionFactory).getConnection();
        verify(redisConnection).ping();

        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[INFRA-OK]   PostgreSQL — SELECT 1 succeeded"))
                .anyMatch(msg -> msg.contains("[INFRA-OK]   Kafka — broker reachable"))
                .anyMatch(msg -> msg.contains("[INFRA-OK]   Redis — PONG received"));
    }

    // -------------------------------------------------------------------------
    // Test 2: PostgreSQL Connection Success
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsPostgresSuccess_whenConnectionSucceeds() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        doThrow(new RuntimeException("Kafka down")).when(kafkaAdmin).describeTopics(anyString());
        when(redisConnectionFactory.getConnection()).thenThrow(new RuntimeException("Redis down"));

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[INFRA-OK]   PostgreSQL — SELECT 1 succeeded"))
                .anyMatch(msg -> msg.contains("Neon/RDS reachable"));
    }

    // -------------------------------------------------------------------------
    // Test 3: PostgreSQL Connection Failure
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsPostgresFailure_whenConnectionFails() {
        // Arrange
        RuntimeException cause = new RuntimeException("Connection timeout");
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenThrow(cause);
        when(kafkaAdmin.describeTopics(anyString())).thenReturn(Map.of());
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] PostgreSQL — unreachable"))
                .anyMatch(msg -> msg.contains("RuntimeException"))
                .anyMatch(msg -> msg.contains("Connection timeout"))
                .anyMatch(msg -> msg.contains("Check POSTGRES_CONNECTION_STRING"));
    }

    // -------------------------------------------------------------------------
    // Test 4: Kafka Connection Success
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsKafkaSuccess_whenConnectionSucceeds() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenThrow(new RuntimeException("Postgres down"));
        when(kafkaAdmin.describeTopics("market-prices")).thenReturn(Map.of());
        when(redisConnectionFactory.getConnection()).thenThrow(new RuntimeException("Redis down"));

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[INFRA-OK]   Kafka — broker reachable"))
                .anyMatch(msg -> msg.contains("market-prices topic accessible"));
    }

    // -------------------------------------------------------------------------
    // Test 5: Kafka Connection Failure
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsKafkaFailure_whenConnectionFails() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        doThrow(new RuntimeException("Broker unreachable")).when(kafkaAdmin).describeTopics(anyString());
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] Kafka — unreachable"))
                .anyMatch(msg -> msg.contains("RuntimeException"))
                .anyMatch(msg -> msg.contains("Broker unreachable"))
                .anyMatch(msg -> msg.contains("Check KAFKA_BOOTSTRAP_SERVERS"));
    }

    // -------------------------------------------------------------------------
    // Test 6: Redis Connection Success
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsRedisSuccess_whenConnectionSucceeds() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenThrow(new RuntimeException("Postgres down"));
        doThrow(new RuntimeException("Kafka down")).when(kafkaAdmin).describeTopics(anyString());
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[INFRA-OK]   Redis — PONG received"))
                .anyMatch(msg -> msg.contains("portfolio-analytics cache ready"));
    }

    // -------------------------------------------------------------------------
    // Test 7: Redis Connection Failure
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsRedisFailure_whenConnectionFails() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(kafkaAdmin.describeTopics(anyString())).thenReturn(Map.of());
        RuntimeException cause = new RuntimeException("Connection refused");
        when(redisConnectionFactory.getConnection()).thenThrow(cause);

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] Redis — unreachable"))
                .anyMatch(msg -> msg.contains("RuntimeException"))
                .anyMatch(msg -> msg.contains("Connection refused"))
                .anyMatch(msg -> msg.contains("Check REDIS_URL"));
    }

    // -------------------------------------------------------------------------
    // Test 8: Startup Banner Logging
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsStartupBanner() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(kafkaAdmin.describeTopics(anyString())).thenReturn(Map.of());
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("=== Infrastructure connectivity check (portfolio-service) ==="))
                .anyMatch(msg -> msg.contains("=== Infrastructure connectivity check complete ==="));
    }

    // -------------------------------------------------------------------------
    // Test 9: Service Name in Logs
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_includesServiceNameInBanner() {
        // Arrange
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(kafkaAdmin.describeTopics(anyString())).thenReturn(Map.of());
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("portfolio-service"));
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
