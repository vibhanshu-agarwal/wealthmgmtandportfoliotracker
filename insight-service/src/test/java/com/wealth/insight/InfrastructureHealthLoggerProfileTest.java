package com.wealth.insight;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
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
 * {@link SpringBootTest} context with a Testcontainers Redis — matching the
 * project convention (see {@link MarketSummaryIntegrationTest}) that every
 * {@code @SpringBootTest} with real Spring Data connectivity belongs to the
 * {@code integrationTest} task and must never run in the fast unit-test lane.
 *
 * <p>Run via: {@code ./gradlew :insight-service:integrationTest}
 */
class InfrastructureHealthLoggerProfileTest {

    private static final int REDIS_PORT = 6379;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    static {
        REDIS.start();
    }

    private static void registerSharedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
        // Prevent listener containers from auto-starting — avoids any broker
        // connection attempt while keeping KafkaProperties in the context.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    // -------------------------------------------------------------------------
    // Test 1: Component should NOT be loaded under 'local' profile
    // -------------------------------------------------------------------------
    @Tag("integration")
    @Testcontainers
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    // Disable AI ChatModel auto-config so neither bedrock nor azure-openai
                    // is wired — the default mock path does not require a ChatModel bean.
                    "spring.ai.model.chat=none",
                    "spring.ai.azure.openai.endpoint=https://placeholder.openai.azure.com/"
            }
    )
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
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "spring.ai.model.chat=none",
                    "spring.ai.azure.openai.endpoint=https://placeholder.openai.azure.com/"
            }
    )
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
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "spring.ai.model.chat=none",
                    "spring.ai.azure.openai.endpoint=https://placeholder.openai.azure.com/"
            }
    )
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
