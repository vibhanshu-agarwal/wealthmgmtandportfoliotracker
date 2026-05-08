package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.advisor.InsightAdvisor;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Property P1: Profile Mutual Exclusion (JVM-side).
 *
 * <p>Validates: Requirements 1.1, 1.2, 15.1
 *
 * <p>For any combination of profiles drawn from
 * {@code {local, prod, aws, azure, bedrock, azure-ai}}, the
 * {@link AiProviderProfileValidator} must:
 * <ul>
 *   <li>throw {@link IllegalStateException} naming both {@code bedrock} and {@code azure-ai}
 *       when both are present in the active set, and</li>
 *   <li>complete without error for all other combinations.</li>
 * </ul>
 *
 * <p>Two complementary test strategies are used:
 * <ol>
 *   <li><b>Fast path (jqwik {@code @Property})</b> — constructs {@link AiProviderProfileValidator}
 *       directly with a {@link MockEnvironment}. No Spring context boot required; exercises
 *       the validator's mutual-exclusion logic in isolation across 200 random profile
 *       combinations.</li>
 *   <li><b>Context path ({@link ApplicationContextRunner})</b> — boots a minimal Spring
 *       context with all six AI adapter beans registered. Asserts that the context fails
 *       to start when both {@code bedrock} and {@code azure-ai} are active (validator fires
 *       via {@code @PostConstruct}), and that exactly one {@link AiInsightService} bean and
 *       exactly one {@link InsightAdvisor} bean are registered when startup succeeds.
 *       This satisfies the bean-uniqueness assertion prescribed by Task 5.4.</li>
 * </ol>
 */
class AiProviderProfileValidatorPropertyTest {

    private static final String[] KNOWN_PROFILES =
            {"local", "prod", "aws", "azure", "bedrock", "azure-ai"};

    // ── Minimal configuration for ApplicationContextRunner ───────────────────

    /**
     * Provides mock dependencies so the AI adapter beans can be instantiated
     * without real infrastructure (no Redis, Kafka, or AI starters needed).
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

    /**
     * Runner pre-loaded with all six AI adapter beans + the validator.
     * Profile selection is applied per-test via {@code withPropertyValues}.
     */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    MockDepsConfig.class,
                    AiProviderProfileValidator.class,
                    MockAiInsightService.class,
                    MockInsightAdvisor.class,
                    BedrockAiInsightService.class,
                    BedrockInsightAdvisor.class,
                    AzureOpenAiInsightService.class,
                    AzureOpenAiInsightAdvisor.class
            );

    // ── Fast path: mutual-exclusion logic in isolation ────────────────────────

    /**
     * P1 (fast path): When both {@code bedrock} and {@code azure-ai} are active,
     * {@link AiProviderProfileValidator#validate()} must throw {@link IllegalStateException}
     * whose message names both conflicting profiles.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @Property(tries = 200)
    void p1_bothBedrockAndAzureAi_validatorThrows(
            @ForAll("profileCombinations") Set<String> profiles) {

        org.junit.jupiter.api.Assumptions.assumeTrue(
                profiles.contains("bedrock") && profiles.contains("azure-ai"));

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles.toArray(new String[0]));

        AiProviderProfileValidator validator = new AiProviderProfileValidator(env);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bedrock")
                .hasMessageContaining("azure-ai");
    }

    /**
     * P1 (fast path): When at most one of {@code bedrock} / {@code azure-ai} is active,
     * {@link AiProviderProfileValidator#validate()} must complete without error.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @Property(tries = 200)
    void p1_atMostOneAiProvider_validatorSucceeds(
            @ForAll("profileCombinations") Set<String> profiles) {

        org.junit.jupiter.api.Assumptions.assumeFalse(
                profiles.contains("bedrock") && profiles.contains("azure-ai"));

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles.toArray(new String[0]));

        AiProviderProfileValidator validator = new AiProviderProfileValidator(env);
        validator.validate(); // must not throw
    }

    // ── Context path: Spring context boot + bean-uniqueness assertions ────────

    /**
     * P1 (context path): When both {@code bedrock} and {@code azure-ai} are active,
     * the Spring context must fail to start because {@link AiProviderProfileValidator}
     * fires its {@code @PostConstruct} and throws {@link IllegalStateException}.
     *
     * <p>The startup failure is wrapped in a {@link org.springframework.beans.factory.BeanCreationException}
     * by Spring; we assert on the root cause to reach the {@link IllegalStateException}.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @org.junit.jupiter.api.Test
    void p1_bothBedrockAndAzureAi_contextStartupFails() {
        runner.withPropertyValues("spring.profiles.active=bedrock,azure-ai")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // Spring wraps @PostConstruct exceptions in BeanCreationException;
                    // the root cause is the IllegalStateException thrown by the validator.
                    Throwable rootCause = ctx.getStartupFailure();
                    while (rootCause.getCause() != null) {
                        rootCause = rootCause.getCause();
                    }
                    assertThat(rootCause)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("bedrock")
                            .hasMessageContaining("azure-ai");
                });
    }

    /**
     * P1 (context path, local profile): Context starts cleanly; exactly one
     * {@link AiInsightService} (Mock) and one {@link InsightAdvisor} (Mock) are registered.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @org.junit.jupiter.api.Test
    void p1_localProfile_contextStartsWithExactlyOneMockBean() {
        runner.withPropertyValues("spring.profiles.active=local")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(AiInsightService.class)).hasSize(1);
                    assertThat(ctx.getBean(AiInsightService.class))
                            .isInstanceOf(MockAiInsightService.class);
                    assertThat(ctx.getBeansOfType(InsightAdvisor.class)).hasSize(1);
                    assertThat(ctx.getBean(InsightAdvisor.class))
                            .isInstanceOf(MockInsightAdvisor.class);
                });
    }

    /**
     * P1 (context path, bedrock profile): Context starts cleanly; exactly one
     * {@link AiInsightService} (Bedrock) and one {@link InsightAdvisor} (Bedrock) are registered.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @org.junit.jupiter.api.Test
    void p1_bedrockProfile_contextStartsWithExactlyOneBedrockBean() {
        runner.withPropertyValues("spring.profiles.active=bedrock")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(AiInsightService.class)).hasSize(1);
                    assertThat(ctx.getBean(AiInsightService.class))
                            .isInstanceOf(BedrockAiInsightService.class);
                    assertThat(ctx.getBeansOfType(InsightAdvisor.class)).hasSize(1);
                    assertThat(ctx.getBean(InsightAdvisor.class))
                            .isInstanceOf(BedrockInsightAdvisor.class);
                });
    }

    /**
     * P1 (context path, azure-ai profile): Context starts cleanly; exactly one
     * {@link AiInsightService} (Azure) and one {@link InsightAdvisor} (Azure) are registered.
     *
     * <p>Validates: Requirements 1.1, 1.2, 15.1
     */
    @org.junit.jupiter.api.Test
    void p1_azureAiProfile_contextStartsWithExactlyOneAzureBean() {
        runner.withPropertyValues("spring.profiles.active=azure-ai")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBeansOfType(AiInsightService.class)).hasSize(1);
                    assertThat(ctx.getBean(AiInsightService.class))
                            .isInstanceOf(AzureOpenAiInsightService.class);
                    assertThat(ctx.getBeansOfType(InsightAdvisor.class)).hasSize(1);
                    assertThat(ctx.getBean(InsightAdvisor.class))
                            .isInstanceOf(AzureOpenAiInsightAdvisor.class);
                });
    }

    @Provide
    Arbitrary<Set<String>> profileCombinations() {
        return Arbitraries.of(KNOWN_PROFILES)
                .set()
                .ofMinSize(0)
                .ofMaxSize(KNOWN_PROFILES.length);
    }
}
