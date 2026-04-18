package com.wealth.insight.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient.Builder chatClientBuilder(ObjectProvider<ChatModel> chatModelProvider) {
        return ChatClient.builder(chatModelProvider.getIfAvailable());
    }
}
