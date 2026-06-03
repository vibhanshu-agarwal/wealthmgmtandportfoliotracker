package com.wealth.insight.resolution;

/**
 * Terminal outcome of a single resolution turn, carried by {@link ResolutionOutcome}
 * and used by {@code ChatResponseBuilder} to branch the final response format.
 *
 * <ul>
 *   <li>{@link #RESOLVED} – exactly one supported ticker was identified; market data
 *       will be fetched from Redis and a conversational response built.</li>
 *   <li>{@link #CLARIFICATION} – the ticker/asset could not be uniquely resolved
 *       (ambiguous, deictic-only, or empty candidate set after catalog validation);
 *       the response asks the user to be more specific.</li>
 *   <li>{@link #NO_DATA} – the ticker is in the catalog but Redis has no live price
 *       ({@code latestPrice} is null); the response names the ticker and notes that
 *       data is temporarily unavailable.</li>
 *   <li>{@link #DISCOVERY} – the user asked which assets are tracked; the response
 *       lists a bounded, grouped set of catalog entries (names + tickers).</li>
 *   <li>{@link #COMPARISON_REDIRECT} – the user asked to compare two or more assets;
 *       full comparison is out of scope, so the response names all candidates and
 *       redirects them to ask about one at a time.</li>
 *   <li>{@link #GREETING_HELP} – greeting or capability-inquiry intent; the response
 *       gives a brief overview of what the chatbot can do.</li>
 * </ul>
 */
public enum Outcome {

    /** Single ticker resolved; market data fetch will follow. */
    RESOLVED,

    /** Could not uniquely identify an asset; asking the user to clarify. */
    CLARIFICATION,

    /** Asset in catalog but no live price data in Redis. */
    NO_DATA,

    /** User requested asset discovery listing. */
    DISCOVERY,

    /** Multi-asset comparison requested; redirecting (out of scope for full execution). */
    COMPARISON_REDIRECT,

    /** Greeting or help request; capability overview response. */
    GREETING_HELP
}
