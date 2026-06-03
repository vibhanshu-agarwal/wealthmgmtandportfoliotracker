package com.wealth.insight.resolution;

import com.wealth.insight.catalog.CompactCatalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Deterministic test double for {@link AssetResolutionClient} (Task 5 / Req 9.3, 11.2).
 *
 * <p>Returns canned {@link LlmResolution} responses keyed by message prefix, enabling
 * downstream orchestration tests without any live LLM call. Supports three modes:
 * <ol>
 *   <li><b>Exact-match responses</b>: pre-registered message → {@code LlmResolution} map.</li>
 *   <li><b>Custom handler</b>: a {@link BiFunction} that receives the message and catalog
 *       and returns any {@code LlmResolution} (useful for property tests).</li>
 *   <li><b>Throw mode</b>: simulates LLM unavailability for fallback-path tests.</li>
 * </ol>
 *
 * <p>If no match is found the stub returns a default
 * {@code UNKNOWN} resolution with empty ticker lists, simulating a "don't know" response.
 *
 * <p>Usage examples:
 * <pre>{@code
 * // Simple canned response
 * var stub = new StubAssetResolutionClient()
 *         .whenMessage("Apple", LlmResolutions.assetQuery("AAPL", "Apple"))
 *         .whenMessage("HDFC Bank", LlmResolutions.assetQuery("HDFCBANK.NS", "HDFC Bank"));
 *
 * // Simulate LLM failure
 * var failingStub = new StubAssetResolutionClient().alwaysThrow(
 *         new RuntimeException("LLM unavailable"));
 *
 * // Track call count
 * int calls = stub.callCount();
 * }</pre>
 */
public class StubAssetResolutionClient implements AssetResolutionClient {

    /** Default response returned when no registered message matches. */
    public static final LlmResolution DEFAULT_UNKNOWN = new LlmResolution(
            Intent.UNKNOWN, List.of(), List.of(), List.of(), null, "No canned response registered");

    private final Map<String, LlmResolution> cannedResponses = new HashMap<>();
    private BiFunction<String, CompactCatalog, LlmResolution> customHandler;
    private RuntimeException alwaysThrow;
    private int callCount;

    // ── Configuration API ─────────────────────────────────────────────────────────────────

    /**
     * Registers a canned response for an exact message string.
     *
     * @param message  the exact user message to match (case-sensitive)
     * @param response the canned {@code LlmResolution} to return
     * @return {@code this} for fluent chaining
     */
    public StubAssetResolutionClient whenMessage(String message, LlmResolution response) {
        cannedResponses.put(message, response);
        return this;
    }

    /**
     * Registers a custom handler invoked for every {@link #resolve} call when no exact
     * message match is found. Overrides the default {@link #DEFAULT_UNKNOWN} fallback.
     *
     * @param handler a function of {@code (message, catalog) → LlmResolution}
     * @return {@code this} for fluent chaining
     */
    public StubAssetResolutionClient withHandler(BiFunction<String, CompactCatalog, LlmResolution> handler) {
        this.customHandler = handler;
        return this;
    }

    /**
     * Configures the stub to throw the given exception on every {@link #resolve} call,
     * simulating LLM unavailability for fallback-path tests.
     *
     * @param ex the exception to throw
     * @return {@code this} for fluent chaining
     */
    public StubAssetResolutionClient alwaysThrow(RuntimeException ex) {
        this.alwaysThrow = ex;
        return this;
    }

    /** Returns the number of times {@link #resolve} has been called on this stub. */
    public int callCount() {
        return callCount;
    }

    /** Resets the call counter and clears all registered responses. */
    public StubAssetResolutionClient reset() {
        cannedResponses.clear();
        customHandler = null;
        alwaysThrow = null;
        callCount = 0;
        return this;
    }

    // ── AssetResolutionClient contract ────────────────────────────────────────────────────

    @Override
    public LlmResolution resolve(String message, CompactCatalog catalog) {
        callCount++;

        if (alwaysThrow != null) {
            throw alwaysThrow;
        }

        LlmResolution canned = cannedResponses.get(message);
        if (canned != null) {
            return canned;
        }

        if (customHandler != null) {
            return customHandler.apply(message, catalog);
        }

        return DEFAULT_UNKNOWN;
    }

    // ── Factory helpers for common canned responses ───────────────────────────────────────

    /** Creates a single-ticker ASSET_QUERY resolution. */
    public static LlmResolution assetQuery(String resolvedTicker, String entity) {
        return new LlmResolution(Intent.ASSET_QUERY,
                entity == null ? List.of() : List.of(entity),
                List.of(resolvedTicker),
                List.of(), null, null);
    }

    /** Creates a DISCOVERY resolution with optional category filter. */
    public static LlmResolution discovery(String categoryFilter) {
        return new LlmResolution(Intent.DISCOVERY, List.of(), List.of(), List.of(),
                categoryFilter, null);
    }

    /** Creates a COMPARISON resolution with multiple candidate tickers. */
    public static LlmResolution comparison(String... candidates) {
        return new LlmResolution(Intent.COMPARISON, List.of(), List.of(),
                List.copyOf(List.of(candidates)), null, null);
    }

    /** Creates a GREETING_HELP resolution. */
    public static LlmResolution greetingHelp() {
        return new LlmResolution(Intent.GREETING_HELP, List.of(), List.of(), List.of(), null, null);
    }

    /** Creates an UNKNOWN resolution with an optional reason. */
    public static LlmResolution unknown(String reason) {
        return new LlmResolution(Intent.UNKNOWN, List.of(), List.of(), List.of(), null, reason);
    }
}
