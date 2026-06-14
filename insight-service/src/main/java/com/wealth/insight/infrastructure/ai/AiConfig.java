package com.wealth.insight.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    // Spring AI 2.0: when setting chat options programmatically, pass ChatOptions.Builder
    // (not .build()) into ChatClient — the builder must remain mutable for option merging.

    /**
     * Provides a {@link ChatClient.Builder} only when a {@link ChatModel} bean is
     * available in the context. Under the mock profile (neither {@code bedrock} nor
     * {@code azure-ai} active) no ChatModel is registered, so this bean is skipped —
     * the mock adapters do not require a ChatClient.
     */
    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
