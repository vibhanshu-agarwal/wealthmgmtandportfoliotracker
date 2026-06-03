package com.wealth.insight.resolution;

import com.wealth.insight.catalog.CompactCatalog;

/**
 * Port for the LLM-powered asset resolution step (Task 5 / Req 2.2, 9.3, 11.2).
 *
 * <p>A single call per chat turn resolves the user's natural-language message into a
 * structured {@link LlmResolution} proposal (intent, entities, proposed tickers). The
 * caller (orchestrator) is responsible for validating every proposed ticker against the
 * catalog before any downstream use — the LLM output is entirely untrusted (design
 * Property 1: catalog-bounded resolution).
 *
 * <p>The {@code catalog} parameter is the price-free {@link CompactCatalog} grounding
 * payload (ticker, name, aliases, assetClass, quoteCurrency — no prices). It is built
 * once and cached by {@code TickerCatalogService} (Req 7.4, 8.3).
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code AzureOpenAiAssetResolutionClient} — production adapter (Task 6,
 *       {@code @Profile("azure-ai")}).</li>
 *   <li>{@code StubAssetResolutionClient} — deterministic test double (Task 5),
 *       returning canned responses without any live LLM call.</li>
 * </ul>
 */
public interface AssetResolutionClient {

    /**
     * Sends the user {@code message} together with the compact {@code catalog} grounding
     * payload to the LLM and returns its structured proposal.
     *
     * <p>Implementations must not swallow errors silently — they should throw a typed
     * exception (e.g. {@code LlmResolutionException}) that the orchestrator can catch
     * and map to the deterministic fallback path (Req 6.1, 6.6).
     *
     * @param message the raw user message (as received from the client)
     * @param catalog the compact, price-free catalog used for LLM grounding
     * @return the (untrusted) LLM proposal; never {@code null}
     */
    LlmResolution resolve(String message, CompactCatalog catalog);
}
