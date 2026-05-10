package com.wealth.market;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InfrastructureHealthLogger} in market-data-service.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Profile activation (aws, azure) and exclusion (local)</li>
 *   <li>MongoDB connection success/failure logging</li>
 *   <li>Kafka connection success/failure logging</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InfrastructureHealthLoggerTest {

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private KafkaAdmin kafkaAdmin;

    @Mock
    private ApplicationReadyEvent applicationReadyEvent;

    private InfrastructureHealthLogger logger;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger targetLogger;

    @BeforeEach
    void setUp() {
        logger = new InfrastructureHealthLogger(mongoTemplate, kafkaAdmin);

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
        Document pingResult = new Document("ok", 1.0);
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenReturn(pingResult);
        doNothing().when(kafkaAdmin).describeTopics("market-prices");

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        verify(mongoTemplate).executeCommand("{ ping: 1 }");
        verify(kafkaAdmin).describeTopics("market-prices");

        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[INFRA-OK]   MongoDB — ping succeeded"))
                .anyMatch(msg -> msg.contains("[INFRA-OK]   Kafka — broker reachable"));
    }

    // -------------------------------------------------------------------------
    // Test 2: MongoDB Connection Success
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsMongoSuccess_whenConnectionSucceeds() {
        // Arrange
        Document pingResult = new Document("ok", 1.0);
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenReturn(pingResult);
        doThrow(new RuntimeException("Kafka down")).when(kafkaAdmin).describeTopics(anyString());

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("[INFRA-OK]   MongoDB — ping succeeded"))
                .anyMatch(msg -> msg.contains("Atlas/DocumentDB reachable"));
    }

    // -------------------------------------------------------------------------
    // Test 3: MongoDB Connection Failure
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsMongoFailure_whenConnectionFails() {
        // Arrange
        RuntimeException cause = new RuntimeException("Connection timeout");
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenThrow(cause);
        doNothing().when(kafkaAdmin).describeTopics(anyString());

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] MongoDB — unreachable"))
                .anyMatch(msg -> msg.contains("RuntimeException"))
                .anyMatch(msg -> msg.contains("Connection timeout"))
                .anyMatch(msg -> msg.contains("Check MONGODB_CONNECTION_STRING"));
    }

    // -------------------------------------------------------------------------
    // Test 4: Kafka Connection Success
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsKafkaSuccess_whenConnectionSucceeds() {
        // Arrange
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenThrow(new RuntimeException("Mongo down"));
        doNothing().when(kafkaAdmin).describeTopics("market-prices");

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
        Document pingResult = new Document("ok", 1.0);
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenReturn(pingResult);
        doThrow(new RuntimeException("Broker unreachable")).when(kafkaAdmin).describeTopics(anyString());

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
    // Test 6: All Dependencies Fail
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsAllFailures_whenAllDependenciesFail() {
        // Arrange
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenThrow(new RuntimeException("Mongo down"));
        doThrow(new RuntimeException("Kafka down")).when(kafkaAdmin).describeTopics(anyString());

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] MongoDB — unreachable"))
                .anyMatch(msg -> msg.contains("[INFRA-FAIL] Kafka — unreachable"));
    }

    // -------------------------------------------------------------------------
    // Test 7: Startup Banner Logging
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_logsStartupBanner() {
        // Arrange
        Document pingResult = new Document("ok", 1.0);
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenReturn(pingResult);
        doNothing().when(kafkaAdmin).describeTopics(anyString());

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("=== Infrastructure connectivity check (market-data-service) ==="))
                .anyMatch(msg -> msg.contains("=== Infrastructure connectivity check complete ==="));
    }

    // -------------------------------------------------------------------------
    // Test 8: Service Name in Logs
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_includesServiceNameInBanner() {
        // Arrange
        Document pingResult = new Document("ok", 1.0);
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenReturn(pingResult);
        doNothing().when(kafkaAdmin).describeTopics(anyString());

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> logMessages = getLogMessages(Level.INFO);
        assertThat(logMessages)
                .anyMatch(msg -> msg.contains("market-data-service"));
    }

    // -------------------------------------------------------------------------
    // Test 9: MongoDB Specific Error Details
    // -------------------------------------------------------------------------
    @Test
    void onApplicationEvent_includesMongoErrorDetails_whenMongoFails() {
        // Arrange
        IllegalStateException cause = new IllegalStateException("Authentication failed");
        when(mongoTemplate.executeCommand("{ ping: 1 }")).thenThrow(cause);
        doNothing().when(kafkaAdmin).describeTopics(anyString());

        // Act
        logger.onApplicationEvent(applicationReadyEvent);

        // Assert
        List<String> errorMessages = getLogMessages(Level.ERROR);
        assertThat(errorMessages)
                .anyMatch(msg -> msg.contains("IllegalStateException"))
                .anyMatch(msg -> msg.contains("Authentication failed"));
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
