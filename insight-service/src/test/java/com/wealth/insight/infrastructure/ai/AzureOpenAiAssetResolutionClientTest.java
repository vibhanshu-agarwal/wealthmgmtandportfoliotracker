package com.wealth.insight.infrastructure.ai;

import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.CompactCatalog;
import com.wealth.insight.resolution.Intent;
import com.wealth.insight.resolution.LlmResolution;
import com.wealth.insight.resolution.LlmResolutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AzureOpenAiAssetResolutionClient} (Task 6 / Req 2.2, 2.6, 2.7, 6.6,
 * 8.3, 8.4, 9.3).
 *
 * <p>Uses a mocked {@link ChatClient} so no live Azure OpenAI calls are made (Req 9.3).
 * Tests the observable contract: correct {@link LlmResolution} returned on success,
 * correct {@link LlmResolutionException} sub-type thrown on failure, and that the
 * catalog and message are both present in the user prompt.
 */
@ExtendWith(MockitoExtension.class)
class AzureOpenAiAssetResolutionClientTest {

    private ChatClient clientMock;
    private AzureOpenAiAssetResolutionClient adapter;
    private CompactCatalog catalog;

    @BeforeEach
    void setUp() {
        // Deep stubs let us chain .prompt().system().user().call().entity()
        clientMock = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClient.Builder builderMock = mock(ChatClient.Builder.class);
        when(builderMock.build()).thenReturn(clientMock);

        adapter = new AzureOpenAiAssetResolutionClient(builderMock);

        CatalogEntry aapl = new CatalogEntry("AAPL", "Apple", List.of("Apple", "Apple Inc"), "US_EQUITY", "USD");
        CatalogEntry btc  = new CatalogEntry("BTC-USD", "Bitcoin", List.of("Bitcoin", "BTC"), "CRYPTO", "USD");
        catalog = new CompactCatalog(List.of(aapl, btc), "test01");
    }

    // ── Happy path ────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_validAssetQueryResponse_returnsLlmResolution() {
        LlmResolution expected = new LlmResolution(
                Intent.ASSET_QUERY, List.of("Apple"), List.of("AAPL"), List.of(), null, null);
        stubEntity(expected);

        LlmResolution result = adapter.resolve("Tell me about Apple", catalog);

        assertThat(result.intent()).isEqualTo(Intent.ASSET_QUERY);
        assertThat(result.resolvedTickers()).containsExactly("AAPL");
        assertThat(result.entities()).containsExactly("Apple");
    }

    @Test
    void resolve_validDiscoveryResponse_returnsDiscoveryResolution() {
        LlmResolution expected = new LlmResolution(
                Intent.DISCOVERY, List.of(), List.of(), List.of(), "CRYPTO", null);
        stubEntity(expected);

        LlmResolution result = adapter.resolve("which cryptos do you support?", catalog);

        assertThat(result.intent()).isEqualTo(Intent.DISCOVERY);
        assertThat(result.categoryFilter()).isEqualTo("CRYPTO");
    }

    // ── Null entity → MALFORMED ───────────────────────────────────────────────────────────

    @Test
    void resolve_nullEntityFromLlm_throwsMalformedLlmResolutionException() {
        stubEntity(null);

        assertThatThrownBy(() -> adapter.resolve("something", catalog))
                .isInstanceOf(LlmResolutionException.class)
                .satisfies(e -> {
                    LlmResolutionException ex = (LlmResolutionException) e;
                    assertThat(ex.getKind()).isEqualTo(LlmResolutionException.Kind.MALFORMED);
                    assertThat(ex.llmStatus()).isEqualTo("malformed");
                });
    }

    // ── LLM throws → UNAVAILABLE ─────────────────────────────────────────────────────────

    @Test
    void resolve_llmThrowsRuntimeException_throwsUnavailableLlmResolutionException() {
        when(clientMock.prompt().system(anyString()).user(anyString()).call()
                .entity(LlmResolution.class))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThatThrownBy(() -> adapter.resolve("Apple", catalog))
                .isInstanceOf(LlmResolutionException.class)
                .satisfies(e -> {
                    LlmResolutionException ex = (LlmResolutionException) e;
                    assertThat(ex.getKind()).isEqualTo(LlmResolutionException.Kind.UNAVAILABLE);
                    assertThat(ex.llmStatus()).isEqualTo("unavailable");
                });
    }

    @Test
    void resolve_llmResolutionExceptionIsNotWrapped() {
        LlmResolutionException original = new LlmResolutionException(
                LlmResolutionException.Kind.TIMEOUT, "request timed out");
        when(clientMock.prompt().system(anyString()).user(anyString()).call()
                .entity(LlmResolution.class))
                .thenThrow(original);

        // LlmResolutionException must be re-thrown as-is, not double-wrapped
        assertThatThrownBy(() -> adapter.resolve("Apple", catalog))
                .isSameAs(original);
    }

    // ── Catalog + message included in user prompt ─────────────────────────────────────────
    // Test buildUserContent directly (package-private) to avoid coupling to the Spring AI
    // ChatClient fluent API inner class hierarchy.

    @Test
    void buildUserContent_containsBothCatalogAndMessage() {
        String content = adapter.buildUserContent("Tell me about Bitcoin", catalog);

        assertThat(content).contains("Tell me about Bitcoin");   // user message present
        assertThat(content).contains("BTC-USD");                 // catalog ticker present
        assertThat(content).contains("Bitcoin");                 // catalog name present
        assertThat(content).contains("AAPL");                    // other entries present
    }

    @Test
    void buildUserContent_doesNotIncludePriceData() {
        String content = adapter.buildUserContent("Apple?", catalog);

        // Must never embed prices or financial facts (design Property 2)
        assertThat(content).doesNotContain("basePrice");
        assertThat(content).doesNotContain("latestPrice");
    }

    @Test
    void systemPrompt_containsInjectionResistanceLanguage() {
        // Inspect the constant via the adapter's exposed accessor
        String sysPrompt = AzureOpenAiAssetResolutionClient.SYSTEM_PROMPT;

        assertThat(sysPrompt.toLowerCase()).containsAnyOf("catalog", "provided");
        assertThat(sysPrompt.toLowerCase()).containsAnyOf("ignore", "never", "must not");
        assertThat(sysPrompt.toLowerCase()).containsAnyOf("json");
    }

    // ── Helper ────────────────────────────────────────────────────────────────────────────

    private void stubEntity(LlmResolution response) {
        when(clientMock.prompt().system(anyString()).user(anyString()).call()
                .entity(LlmResolution.class))
                .thenReturn(response);
    }
}
