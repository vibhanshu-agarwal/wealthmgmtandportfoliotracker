package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.catalog.CompactCatalog;
import com.wealth.insight.resolution.AssetResolutionClient;
import com.wealth.insight.resolution.Intent;
import com.wealth.insight.resolution.LlmResolution;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default mock adapter for {@link AssetResolutionClient} — active when the {@code azure-ai}
 * Spring profile is NOT enabled (local development, CI, and integration tests without a real LLM).
 *
 * <p>Returns a deterministic {@link Intent#UNKNOWN} resolution for every message,
 * so that {@code ChatResolutionService} falls back to the deterministic preflight path.
 * This mirrors the contract of {@link MockAiInsightService} for the AI sentiment port.
 */
@Service
@Profile("!azure-ai")
public class MockAssetResolutionClient implements AssetResolutionClient {

    /** Deterministic UNKNOWN resolution — no entities, no tickers, no category. */
    private static final LlmResolution UNKNOWN = new LlmResolution(
            Intent.UNKNOWN, List.of(), List.of(), List.of(), null,
            "Mock client: no LLM available in this environment");

    @Override
    public LlmResolution resolve(String message, CompactCatalog catalog) {
        return UNKNOWN;
    }
}
