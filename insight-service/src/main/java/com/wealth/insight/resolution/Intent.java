package com.wealth.insight.resolution;

/**
 * Classified user intent as proposed by the LLM in {@link LlmResolution} and
 * validated/used by {@code ChatResolutionService} to branch the response path.
 *
 * <p>Values are mapped from the untrusted LLM JSON output and are therefore
 * always validated before use — an unknown string defaults to {@link #UNKNOWN}.
 *
 * <ul>
 *   <li>{@link #ASSET_QUERY} – user is asking about a specific asset (price, trend,
 *       sentiment). The single-ticker resolved path.</li>
 *   <li>{@link #DISCOVERY} – user wants to know what assets are available
 *       (e.g. "which stocks can you tell me about?", "list your cryptos").</li>
 *   <li>{@link #COMPARISON} – user is comparing two or more assets
 *       (e.g. "compare Apple and Microsoft"). Full multi-asset comparison is
 *       out of scope; the service redirects with names and tickers.</li>
 *   <li>{@link #GREETING_HELP} – greeting or help request
 *       (e.g. "hello", "what can you do?").</li>
 *   <li>{@link #UNKNOWN} – intent could not be determined, or the LLM returned
 *       an unrecognised value. Falls back to clarification.</li>
 * </ul>
 */
public enum Intent {

    /** User is asking about a specific tracked asset. */
    ASSET_QUERY,

    /** User wants a list of available/trackable assets, optionally filtered by category. */
    DISCOVERY,

    /** User wants to compare two or more assets (redirected, not fully executed). */
    COMPARISON,

    /** Greeting or general help / capability inquiry. */
    GREETING_HELP,

    /** Intent undetermined or unrecognised LLM output. */
    UNKNOWN
}
