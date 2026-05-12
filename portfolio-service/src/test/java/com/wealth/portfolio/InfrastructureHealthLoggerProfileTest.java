package com.wealth.portfolio;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
 * {@link SpringBootTest} context with Flyway running against a Testcontainers
 * Postgres — matching the project convention (see {@link FlywayPreservationTest})
 * that every {@code @SpringBootTest} with real Spring Data connectivity belongs
 * to the {@code integrationTest} task and must never run in the fast unit-test
 * lane.
 *
 * <p>Run via: {@code ./gradlew :portfolio-service:integrationTest}
 */
class InfrastructureHealthLoggerProfileTest {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("portfolio_db")
                    .withUsername("wealth_user")
                    .withPassword("wealth_pass");

    static {
        POSTGRES.start();
    }

    private static void registerSharedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Prevent listener containers from auto-starting — avoids any broker
        // connection attempt while keeping KafkaProperties in the context.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // -------------------------------------------------------------------------
    // Test 1: Component should NOT be loaded under 'local' profile
    // -------------------------------------------------------------------------
    @Tag("integration")
    @Testcontainers
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("local")
    static class LocalProfileTest {

        @DynamicPropertySource
        static void localProperties(DynamicPropertyRegistry registry) {
            registerSharedProperties(registry);
        }

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
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("aws")
    static class AwsProfileTest {

        @DynamicPropertySource
        static void awsProperties(DynamicPropertyRegistry registry) {
            registerSharedProperties(registry);
        }

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
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    @ActiveProfiles("azure")
    static class AzureProfileTest {

        @DynamicPropertySource
        static void azureProperties(DynamicPropertyRegistry registry) {
            registerSharedProperties(registry);
        }

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        void shouldLoadUnderAzureProfile() {
            InfrastructureHealthLogger bean = applicationContext.getBean(InfrastructureHealthLogger.class);
            assertThat(bean).isNotNull();
        }
    }
}
