package com.wealth.insight.resolution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Untrusted LLM proposal returned by {@code AssetResolutionClient}.
 *
 * <p><strong>Trust boundary:</strong> every field in this record is LLM-generated
 * and must be treated as untrusted input. {@code ChatResolutionService} validates
 * all proposed tickers against the catalog before any Redis lookup
 * (design Property 1: catalog-bounded resolution).
 *
 * <p>Uses {@code @JsonIgnoreProperties(ignoreUnknown = true)} so that future
 * LLM output fields do not break deserialization.
 *
 * @param intent               the LLM's best guess at the user's {@link Intent};
 *                             may be null if the LLM response was malformed — treated
 *                             as {@link Intent#UNKNOWN} by the orchestrator
 * @param entities             raw entity strings the LLM extracted from the message
 *                             (e.g., {@code ["Apple", "AAPL"]}); used for logging only
 * @param resolvedTickers      ticker symbols the LLM believes uniquely identify assets
 *                             (e.g., {@code ["AAPL"]}); must be validated against catalog
 * @param candidateTickers     tickers proposed by the LLM when the intent is ambiguous
 *                             or comparison-like (e.g., {@code ["AAPL", "MSFT"]}); must
 *                             be validated against catalog
 * @param categoryFilter       optional asset-class filter for discovery responses
 *                             (e.g., {@code "CRYPTO"}, {@code "NSE"}); null means no filter
 * @param clarificationReason  optional hint from the LLM explaining why clarification
 *                             was needed; used only for logging, never shown to the user
 *                             verbatim (prompt-injection resistance)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmResolution(
        Intent intent,
        List<String> entities,
        List<String> resolvedTickers,
        List<String> candidateTickers,
        String categoryFilter,
        String clarificationReason
) {
    /**
     * Compact constructor that defensively copies all list fields and normalises
     * nulls to empty lists so callers never need to null-check.
     */
    public LlmResolution {
        entities           = (entities == null)           ? List.of() : List.copyOf(entities);
        resolvedTickers    = (resolvedTickers == null)    ? List.of() : List.copyOf(resolvedTickers);
        candidateTickers   = (candidateTickers == null)   ? List.of() : List.copyOf(candidateTickers);
        // intent, categoryFilter, clarificationReason may legitimately be null
    }
}
