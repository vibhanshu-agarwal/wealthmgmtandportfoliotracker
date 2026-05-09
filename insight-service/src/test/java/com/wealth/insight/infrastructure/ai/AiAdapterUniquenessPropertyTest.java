package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.advisor.InsightAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Property P3: AI Adapter Uniqueness.
 *
 * <p>Validates: Requirements 3.6, 3.7, 3.8, 15.3, 15.6
 *
 * <p>For each active profile in {@code {local, bedrock, azure-ai}}, exactly one
 * {@link AiInsightService} bean and exactly one {@link InsightAdvisor} bean must be
 * registered, and the resolved bean class must match the expected adapter variant.
 *
 * <p>Uses {@link ApplicationContextRunner} with a minimal configuration that registers
 * only the AI adapter beans — no Redis, Kafka, or full Spring Boot context required.
 * The {@link org.springframework.ai.chat.client.ChatClient.Builder} dependency is
 * satisfied by a mock bean in the test configuration.
 */
class AiAdapterUniquenessPropertyTest {

    /**
     * Minimal configuration that provides a mock {@code ChatClient.Builder} and
     * a mock {@code MarketDataService} so the AI adapters can be instantiated
     * without real infrastructure.
     */
    @Configuration
    static class MockDepsConfig {
        @Bean
        org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder() {
            return mock(org.springframework.ai.chat.client.ChatClient.Builder.class, RETURNS_DEEP_STUBS);
        }

        @Bean
        com.wealth.insight.MarketDataService marketDataService() {
            return mock(com.wealth.insight.MarketDataService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MockDepsConfig.class,
                    MockAiInsightService.class,
                    MockInsightAdvisor.class,
                    BedrockAiInsightService.class,
                    BedrockInsightAdvisor.class,
                    AzureOpenAiInsightService.class,
                    AzureOpenAiInsightAdvisor.class
            );

    /**
     * P3a: Under the {@code local} profile (no AI provider profile active),
     * exactly one {@link AiInsightService} bean (Mock variant) and exactly one
     * {@link InsightAdvisor} bean (Mock variant) must be registered.
     *
     * <p>Validates: Requirements 3.6, 15.3
     */
    @Test
    void p3_localProfile_exactlyOneMockAiInsightServiceAndAdvisor() {
        runner.withPropertyValues("spring.profiles.active=local")
                .run(ctx -> {
                    assertThat(ctx.getBeansOfType(AiInsightService.class)).hasSize(1);
                    assertThat(ctx.getBean(AiInsightService.class))
                            .isInstanceOf(MockAiInsightService.class);

                    assertThat(ctx.getBeansOfType(InsightAdvisor.class)).hasSize(1);
                    assertThat(ctx.getBean(InsightAdvisor.class))
                            .isInstanceOf(MockInsightAdvisor.class);
                });
    }

    /**
     * P3b: Under the {@code bedrock} profile, exactly one {@link AiInsightService} bean
     * (Bedrock variant) and exactly one {@link InsightAdvisor} bean (Bedrock variant)
     * must be registered.
     *
     * <p>Validates: Requirements 3.7, 15.3
     */
    @Test
    void p3_bedrockProfile_exactlyOneBedrockAiInsightServiceAndAdvisor() {
        runner.withPropertyValues("spring.profiles.active=bedrock")
                .run(ctx -> {
                    assertThat(ctx.getBeansOfType(AiInsightService.class)).hasSize(1);
                    assertThat(ctx.getBean(AiInsightService.class))
                            .isInstanceOf(BedrockAiInsightService.class);

                    assertThat(ctx.getBeansOfType(InsightAdvisor.class)).hasSize(1);
                    assertThat(ctx.getBean(InsightAdvisor.class))
                            .isInstanceOf(BedrockInsightAdvisor.class);
                });
    }

    /**
     * P3c: Under the {@code azure-ai} profile, exactly one {@link AiInsightService} bean
     * (Azure variant) and exactly one {@link InsightAdvisor} bean (Azure variant)
     * must be registered.
     *
     * <p>Validates: Requirements 3.8, 15.3
     */
    @Test
    void p3_azureAiProfile_exactlyOneAzureAiInsightServiceAndAdvisor() {
        runner.withPropertyValues("spring.profiles.active=azure-ai")
                .run(ctx -> {
                    assertThat(ctx.getBeansOfType(AiInsightService.class)).hasSize(1);
                    assertThat(ctx.getBean(AiInsightService.class))
                            .isInstanceOf(AzureOpenAiInsightService.class);

                    assertThat(ctx.getBeansOfType(InsightAdvisor.class)).hasSize(1);
                    assertThat(ctx.getBean(InsightAdvisor.class))
                            .isInstanceOf(AzureOpenAiInsightAdvisor.class);
                });
    }
}
