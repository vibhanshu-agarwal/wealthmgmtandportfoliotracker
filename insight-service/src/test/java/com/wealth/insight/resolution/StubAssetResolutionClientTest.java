package com.wealth.insight.resolution;

import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.CompactCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StubAssetResolutionClient} — the deterministic test double for
 * {@link AssetResolutionClient} (Task 5 / Req 9.3, 11.2).
 *
 * <p>Verifies that canned responses, custom handlers, throw mode, and factory helpers
 * all behave as documented, so downstream orchestration tests can rely on the stub.
 */
class StubAssetResolutionClientTest {

    private StubAssetResolutionClient stub;
    private CompactCatalog catalog;

    @BeforeEach
    void setUp() {
        stub = new StubAssetResolutionClient();
        // Minimal catalog — stub tests don't require the real seed file
        CatalogEntry aapl = new CatalogEntry("AAPL", "Apple", List.of("Apple", "Apple Inc"),
                "US_EQUITY", "USD");
        catalog = new CompactCatalog(List.of(aapl), "test-ver");
    }

    // ── Canned response lookup ────────────────────────────────────────────────────────────

    @Test
    void resolve_registeredMessage_returnsCannedResponse() {
        LlmResolution canned = StubAssetResolutionClient.assetQuery("AAPL", "Apple");
        stub.whenMessage("Tell me about Apple", canned);

        LlmResolution result = stub.resolve("Tell me about Apple", catalog);

        assertThat(result.intent()).isEqualTo(Intent.ASSET_QUERY);
        assertThat(result.resolvedTickers()).containsExactly("AAPL");
        assertThat(result.entities()).containsExactly("Apple");
    }

    @Test
    void resolve_unregisteredMessage_returnsDefaultUnknown() {
        LlmResolution result = stub.resolve("some unknown message", catalog);

        assertThat(result.intent()).isEqualTo(Intent.UNKNOWN);
        assertThat(result.resolvedTickers()).isEmpty();
    }

    // ── Custom handler ────────────────────────────────────────────────────────────────────

    @Test
    void resolve_withCustomHandler_invokesHandler() {
        stub.withHandler((msg, cat) ->
                new LlmResolution(Intent.DISCOVERY, List.of(), List.of(), List.of(), "CRYPTO", null));

        LlmResolution result = stub.resolve("which cryptos do you track?", catalog);

        assertThat(result.intent()).isEqualTo(Intent.DISCOVERY);
        assertThat(result.categoryFilter()).isEqualTo("CRYPTO");
    }

    @Test
    void resolve_exactMatchTakesPrecedenceOverHandler() {
        LlmResolution canned = StubAssetResolutionClient.assetQuery("AAPL", "Apple");
        stub.whenMessage("Apple", canned)
            .withHandler((msg, cat) -> StubAssetResolutionClient.unknown("handler"));

        LlmResolution result = stub.resolve("Apple", catalog);

        assertThat(result.intent()).isEqualTo(Intent.ASSET_QUERY);
    }

    // ── Throw mode (LLM unavailability simulation) ────────────────────────────────────────

    @Test
    void resolve_alwaysThrow_throwsOnEveryCall() {
        RuntimeException ex = new RuntimeException("LLM unavailable");
        stub.alwaysThrow(ex);

        assertThatThrownBy(() -> stub.resolve("anything", catalog))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("LLM unavailable");
    }

    // ── Call counting ─────────────────────────────────────────────────────────────────────

    @Test
    void callCount_incrementsOnEachResolveCall() {
        stub.resolve("msg1", catalog);
        stub.resolve("msg2", catalog);

        assertThat(stub.callCount()).isEqualTo(2);
    }

    @Test
    void reset_clearsCannedResponsesAndCallCount() {
        LlmResolution canned = StubAssetResolutionClient.assetQuery("AAPL", "Apple");
        stub.whenMessage("Apple", canned);
        stub.resolve("Apple", catalog);

        stub.reset();

        assertThat(stub.callCount()).isZero();
        assertThat(stub.resolve("Apple", catalog).intent()).isEqualTo(Intent.UNKNOWN);
    }

    // ── Factory helpers ───────────────────────────────────────────────────────────────────

    @Test
    void factory_assetQuery_buildsCorrectResolution() {
        LlmResolution r = StubAssetResolutionClient.assetQuery("HDFCBANK.NS", "HDFC Bank");

        assertThat(r.intent()).isEqualTo(Intent.ASSET_QUERY);
        assertThat(r.resolvedTickers()).containsExactly("HDFCBANK.NS");
        assertThat(r.entities()).containsExactly("HDFC Bank");
        assertThat(r.candidateTickers()).isEmpty();
    }

    @Test
    void factory_discovery_buildsCorrectResolution() {
        LlmResolution r = StubAssetResolutionClient.discovery("NSE");

        assertThat(r.intent()).isEqualTo(Intent.DISCOVERY);
        assertThat(r.categoryFilter()).isEqualTo("NSE");
    }

    @Test
    void factory_comparison_buildsCorrectResolution() {
        LlmResolution r = StubAssetResolutionClient.comparison("AAPL", "MSFT");

        assertThat(r.intent()).isEqualTo(Intent.COMPARISON);
        assertThat(r.candidateTickers()).containsExactlyInAnyOrder("AAPL", "MSFT");
    }

    @Test
    void factory_greetingHelp_buildsCorrectResolution() {
        LlmResolution r = StubAssetResolutionClient.greetingHelp();
        assertThat(r.intent()).isEqualTo(Intent.GREETING_HELP);
    }

    @Test
    void factory_unknown_buildsCorrectResolution() {
        LlmResolution r = StubAssetResolutionClient.unknown("no idea");
        assertThat(r.intent()).isEqualTo(Intent.UNKNOWN);
        assertThat(r.clarificationReason()).isEqualTo("no idea");
    }
}
