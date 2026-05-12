package com.wealth.market;

import com.wealth.market.events.PriceUpdatedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Profile activation tests for {@link InfrastructureHealthLogger}.
 *
 * <p>Verifies that the component:
 * <ul>
 *   <li>Is NOT loaded under the {@code local} profile</li>
 *   <li>IS loaded under the {@code aws} profile</li>
 *   <li>IS loaded under the {@code azure} profile</li>
 * </ul>
 *
 * <p>Tagged {@code integration} because each nested class spins up a full
 * {@link SpringBootTest} context with a Testcontainers-backed MongoDB — matching
 * the project convention (see {@link LocalMarketDataSeederIntegrationTest}) that
 * every {@code @SpringBootTest} with real Spring Data connectivity belongs to the
 * {@code integrationTest} task and must never run in the fast unit-test lane.
 *
 * <p>Run via: {@code ./gradlew :market-data-service:integrationTest}
 */
class InfrastructureHealthLoggerProfileTest {

    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start();
    }

    private static void registerSharedMongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        // Keep the seeder and background jobs quiet — we only care about context wiring.
        registry.add("market.seed.enabled", () -> false);
        registry.add("market-data.refresh.enabled", () -> false);
        registry.add("market-data.hydration.enabled", () -> false);
        registry.add("market-data.baseline-seed.enabled", () -> false);
        // Keep the Kafka producer from actually connecting; the test mocks KafkaTemplate.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // -------------------------------------------------------------------------
    // Test 1: Component should NOT be loaded under 'local' profile
    // -------------------------------------------------------------------------
    @Tag("integration")
    @Testcontainers
    @SpringBootTest
    @ActiveProfiles("local")
    static class LocalProfileTest {

        @DynamicPropertySource
        static void localProperties(DynamicPropertyRegistry registry) {
            registerSharedMongoProperties(registry);
        }

        @MockitoBean
        @SuppressWarnings("unused")
        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void shouldNotLoadUnderLocalProfile() {
            assertThatThrownBy(() -> applicationContext.getBean(InfrastructureHealthLogger.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining("InfrastructureHealthLogger");
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: Component SHOULD be loaded under 'aws' profile
    // -------------------------------------------------------------------------
    @Tag("integration")
    @Testcontainers
    @SpringBootTest
    @ActiveProfiles("aws")
    static class AwsProfileTest {

        @DynamicPropertySource
        static void awsProperties(DynamicPropertyRegistry registry) {
            registerSharedMongoProperties(registry);
        }

        @MockitoBean
        @SuppressWarnings("unused")
        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void shouldLoadUnderAwsProfile() {
            InfrastructureHealthLogger bean = applicationContext.getBean(InfrastructureHealthLogger.class);
            assertThat(bean).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Component SHOULD be loaded under 'azure' profile
    // -------------------------------------------------------------------------
    @Tag("integration")
    @Testcontainers
    @SpringBootTest
    @ActiveProfiles("azure")
    static class AzureProfileTest {

        @DynamicPropertySource
        static void azureProperties(DynamicPropertyRegistry registry) {
            registerSharedMongoProperties(registry);
        }

        @MockitoBean
        @SuppressWarnings("unused")
        KafkaTemplate<String, PriceUpdatedEvent> kafkaTemplate;

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void shouldLoadUnderAzureProfile() {
            InfrastructureHealthLogger bean = applicationContext.getBean(InfrastructureHealthLogger.class);
            assertThat(bean).isNotNull();
        }
    }
}
