package com.wealth.insight.infrastructure.ai;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Ensures the azure-ai profile reaches Spring AI's Microsoft Foundry path with a rotating
 * Entra bearer token instead of mock-profile placeholder keys or accidental empty-string
 * no-auth mode.
 *
 * <p>{@code OpenAiSetup} treats {@code apiKey == ""} as deliberate no-auth (strips
 * {@code Authorization}). {@code apiKey == null} with a {@link Credential} on the connection
 * properties uses {@code Authorization: Bearer} against Azure OpenAI.
 */
@Configuration
@Profile("azure-ai")
class AzureOpenAiAuthConfig {

    private static final String MOCK_PROFILE_PLACEHOLDER_KEY = "placeholder-key";
    private static final String UNRESOLVED_NULL_PLACEHOLDER = "#{null}";
    private static final String COGNITIVE_SERVICES_SCOPE = "https://cognitiveservices.azure.com/.default";

    @Bean
    static BeanPostProcessor azureOpenAiConnectionNormalizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof OpenAiCommonProperties common) {
                    applyPasswordlessAuth(common);
                }
                if (bean instanceof OpenAiChatProperties chat) {
                    applyPasswordlessAuth(chat);
                }
                return bean;
            }
        };
    }

    private static void applyPasswordlessAuth(OpenAiCommonProperties properties) {
        if (shouldUsePasswordlessAuth(properties.getApiKey())) {
            properties.setApiKey(null);
            properties.setCredential(azureCredential());
        }
    }

    private static void applyPasswordlessAuth(OpenAiChatProperties properties) {
        if (shouldUsePasswordlessAuth(properties.getApiKey())) {
            properties.setApiKey(null);
            properties.setCredential(azureCredential());
        }
    }

    static Credential azureCredential() {
        TokenCredential tokenCredential = new DefaultAzureCredentialBuilder().build();
        TokenRequestContext request = new TokenRequestContext().addScopes(COGNITIVE_SERVICES_SCOPE);
        Supplier<String> tokenSupplier = () -> {
            var accessToken = tokenCredential.getToken(request).block();
            if (accessToken == null) {
                throw new IllegalStateException("Failed to acquire Azure token for " + COGNITIVE_SERVICES_SCOPE);
            }
            return accessToken.getToken();
        };
        return BearerTokenCredential.create(tokenSupplier);
    }

    static boolean shouldUsePasswordlessAuth(@Nullable String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return true;
        }
        return MOCK_PROFILE_PLACEHOLDER_KEY.equals(apiKey) || UNRESOLVED_NULL_PLACEHOLDER.equals(apiKey);
    }
}
