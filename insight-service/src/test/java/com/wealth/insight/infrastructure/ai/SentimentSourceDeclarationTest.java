package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.SentimentSource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.cache.annotation.Cacheable;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Rehearsal defect #5 (F4): each sentiment implementation declares its own source, the source is
 * part of its cache key (so a cached text is never read back under another implementation's
 * label), and the model prompts ask for an assessment grounded only in the data given.
 */
class SentimentSourceDeclarationTest {

    private final MarketDataService marketData = mock(MarketDataService.class);

    @Test
    void mockAdapter_declaresRuleBased() {
        assertThat(new MockAiInsightService().sentimentSource()).isEqualTo(SentimentSource.RULE_BASED);
    }

    @Test
    void azureAdapter_declaresAzureOpenAi_andKeysItsCacheByThatSource() throws Exception {
        AzureOpenAiInsightService service =
                new AzureOpenAiInsightService(mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS), marketData);

        assertThat(service.sentimentSource()).isEqualTo(SentimentSource.AZURE_OPENAI);
        assertThat(cacheKey(AzureOpenAiInsightService.class)).isEqualTo("'AZURE_OPENAI:' + #ticker");
    }

    @Test
    void bedrockAdapter_declaresBedrock_andKeysItsCacheByThatSource() throws Exception {
        BedrockAiInsightService service =
                new BedrockAiInsightService(mock(ChatClient.Builder.class, RETURNS_DEEP_STUBS), marketData);

        assertThat(service.sentimentSource()).isEqualTo(SentimentSource.BEDROCK);
        assertThat(cacheKey(BedrockAiInsightService.class)).isEqualTo("'BEDROCK:' + #ticker");
    }

    @Test
    void modelPrompts_askForAnAssessmentGroundedOnlyInTheData() throws Exception {
        for (Class<?> type : new Class<?>[] {AzureOpenAiInsightService.class, BedrockAiInsightService.class}) {
            assertThat(systemPrompt(type))
                    .as(type.getSimpleName())
                    .contains("Base the assessment only on the prices and change given")
                    .contains("do not describe investor behaviour, confidence, or anything the data does not show");
        }
    }

    private static String cacheKey(Class<? extends AiInsightService> type) throws Exception {
        Cacheable cacheable = type.getMethod("getSentiment", String.class).getAnnotation(Cacheable.class);
        assertThat(cacheable).as("%s.getSentiment must be @Cacheable", type.getSimpleName()).isNotNull();
        return cacheable.key();
    }

    private static String systemPrompt(Class<?> type) throws Exception {
        Field field = type.getDeclaredField("SYSTEM_PROMPT");
        field.setAccessible(true);
        return ((String) field.get(null)).replaceAll("\\s+", " ");
    }
}
