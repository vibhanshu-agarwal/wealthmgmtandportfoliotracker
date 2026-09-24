package com.wealth.insight;

/**
 * Which implementation produced a sentiment text. Each {@link AiInsightService} declares its own,
 * so the source is known by construction rather than inferred from the text (rehearsal defect #5).
 */
public enum SentimentSource {
    /** Azure OpenAI chat completion ({@code azure-ai} profile). */
    AZURE_OPENAI,
    /** Amazon Bedrock chat completion ({@code bedrock} profile). */
    BEDROCK,
    /** The deterministic, non-LLM adapter used when no model profile is active. */
    RULE_BASED
}
