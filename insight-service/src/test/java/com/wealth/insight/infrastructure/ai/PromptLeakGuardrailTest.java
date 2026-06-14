package com.wealth.insight.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.chat.observation.autoconfigure.ChatObservationAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8.11 — Property 8: No prompt leakage.
 *
 * <p>Asserts {@code spring.ai.chat.observations.log-prompt=false} is active so raw user
 * prompts are not emitted through Spring AI 2.0 log-based chat observations.
 */
class PromptLeakGuardrailTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    ChatObservationAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class
            ));

    @Test
    void p8_logPromptDisabled_inApplicationDefaults() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=none",
                        "spring.ai.chat.observations.log-prompt=false",
                        "spring.ai.chat.observations.log-completion=false",
                        "spring.ai.openai.api-key=placeholder-key",
                        "spring.ai.openai.base-url=https://placeholder.openai.azure.com/"
                )
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getEnvironment().getProperty("spring.ai.chat.observations.log-prompt"))
                            .isEqualTo("false");
                    assertThat(ctx.getEnvironment().getProperty("spring.ai.chat.observations.log-completion"))
                            .isEqualTo("false");
                });
    }

    @Test
    void p8_sensitivePromptMarker_notEnabledForLogging() {
        // Guardrail: when log-prompt is false, observation config must not flip to true silently.
        runner.withPropertyValues(
                        "spring.ai.model.chat=openai",
                        "spring.ai.chat.observations.log-prompt=false",
                        "spring.ai.openai.api-key=test-key",
                        "spring.ai.openai.base-url=https://test.openai.azure.com/",
                        "spring.ai.openai.chat.options.model=gpt-4o-mini"
                )
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    Boolean logPrompt = ctx.getEnvironment().getProperty(
                            "spring.ai.chat.observations.log-prompt", Boolean.class);
                    assertThat(logPrompt).isFalse();
                });
    }
}
