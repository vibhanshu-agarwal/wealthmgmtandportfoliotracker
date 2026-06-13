package com.wealth.insight.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.bedrock.converse.autoconfigure.BedrockConverseProxyChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property P4: ChatModel Primary Selection.
 *
 * <p>Validates: Requirements 4.4, 4.5, 15.4, 15.6
 *
 * <p>With both Spring AI starters ({@code spring-ai-starter-model-bedrock-converse} and
 * {@code spring-ai-starter-model-openai}) on the classpath, the primary
 * {@link ChatModel} bean must match the value of {@code spring.ai.model.chat}.
 *
 * <p>Uses {@link ApplicationContextRunner} with both Spring AI auto-configurations loaded
 * to toggle {@code spring.ai.model.chat} between {@code bedrock-converse} and
 * {@code azure-openai} and assert the resolved primary bean class matches the selected
 * provider.
 */
class ChatModelPrimarySelectionPropertyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    BedrockConverseProxyChatAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class
            ));

    /**
     * P4a: When {@code spring.ai.model.chat=bedrock-converse}, the primary
     * {@link ChatModel} bean must be the Bedrock-backed implementation.
     *
     * <p>Validates: Requirements 4.4, 15.4
     */
    @Test
    void p4_bedrockConverseSelected_primaryChatModelIsBedrock() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=bedrock-converse",
                        "spring.ai.bedrock.aws.region=us-east-1",
                        "spring.ai.bedrock.converse.chat.options.model=us.anthropic.claude-haiku-4-5-20251001-v1:0"
                )
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();

                    String[] beanNames = ctx.getBeanNamesForType(ChatModel.class);
                    assertThat(beanNames)
                            .as("At least one ChatModel bean must be registered under bedrock-converse")
                            .isNotEmpty();

                    // The primary ChatModel bean class name should reference Bedrock
                    ChatModel primaryModel = ctx.getBean(ChatModel.class);
                    String className = primaryModel.getClass().getName();
                    assertThat(className)
                            .as("Primary ChatModel should be Bedrock-backed when spring.ai.model.chat=bedrock-converse, got: " + className)
                            .containsIgnoringCase("bedrock");
                });
    }

    /**
     * P4b: When {@code spring.ai.model.chat=openai}, the primary
     * {@link ChatModel} bean must be the OpenAI-backed implementation (Azure/Foundry via native config).
     *
     * <p>Validates: Requirements 4.5, 15.4
     */
    @Test
    void p4_openAiSelected_primaryChatModelIsOpenAi() {
        runner.withPropertyValues(
                        "spring.ai.model.chat=openai",
                        "spring.ai.openai.base-url=https://test.openai.azure.com/",
                        "spring.ai.openai.api-key=test-key",
                        "spring.ai.openai.chat.options.model=gpt-4o-mini"
                )
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();

                    String[] beanNames = ctx.getBeanNamesForType(ChatModel.class);
                    assertThat(beanNames)
                            .as("At least one ChatModel bean must be registered under openai")
                            .isNotEmpty();

                    ChatModel primaryModel = ctx.getBean(ChatModel.class);
                    String className = primaryModel.getClass().getName();
                    assertThat(className)
                            .as("Primary ChatModel should be OpenAI-backed when spring.ai.model.chat=openai, got: " + className)
                            .containsIgnoringCase("openai");
                });
    }
}
