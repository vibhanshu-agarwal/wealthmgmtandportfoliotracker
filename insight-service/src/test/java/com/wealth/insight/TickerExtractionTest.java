package com.wealth.insight;

import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatResolutionService#extractNormalizedCandidates(String)}
 * (supersedes the legacy {@code ChatController.extractTicker} tests — Task 10 refactor).
 *
 * <p>The new extraction logic delegates to {@link TickerCatalogService#normalize(String)}
 * rather than a regex pattern. Only catalog-normalizable tokens become candidates.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TickerExtractionTest {

    @Mock private TickerCatalogService catalog;
    @Mock private ChatResponseBuilder responseBuilder;

    private ChatResolutionService service;

    @BeforeEach
    void setUp() {
        service = new ChatResolutionService(catalog, new StubAssetResolutionClient(), responseBuilder);
        when(catalog.normalize(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void extractNormalizedCandidates_noTokensNormalize_returnsEmpty() {
        assertThat(service.extractNormalizedCandidates("How is the market")).isEmpty();
    }

    @Test
    void extractNormalizedCandidates_singleCatalogToken_returnsSingle() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

        assertThat(service.extractNormalizedCandidates("How is AAPL doing")).containsExactly("AAPL");
    }

    @Test
    void extractNormalizedCandidates_multipleTokensNormalize_returnsAll() {
        when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));
        when(catalog.normalize("MSFT")).thenReturn(Optional.of("MSFT"));

        assertThat(service.extractNormalizedCandidates("Compare AAPL and MSFT"))
                .containsExactlyInAnyOrder("AAPL", "MSFT");
    }

    @Test
    void extractNormalizedCandidates_blankMessage_returnsEmpty() {
        assertThat(service.extractNormalizedCandidates("   ")).isEmpty();
    }

    @Test
    void extractNormalizedCandidates_emptyMessage_returnsEmpty() {
        assertThat(service.extractNormalizedCandidates("")).isEmpty();
    }

    @Test
    void extractNormalizedCandidates_tokenWithPunctuation_cleanedAndNormalized() {
        when(catalog.normalize("TSLA")).thenReturn(Optional.of("TSLA"));

        assertThat(service.extractNormalizedCandidates("What about TSLA?")).containsExactly("TSLA");
    }

    @Test
    void extractNormalizedCandidates_dollarPrefixedToken_extractsAndNormalizes() {
        when(catalog.normalize("MSFT")).thenReturn(Optional.of("MSFT"));

        assertThat(service.extractNormalizedCandidates("$MSFT outlook")).containsExactly("MSFT");
    }

    @Test
    void extractNormalizedCandidates_suffixedToken_passesThroughIfExactCatalogMatch() {
        when(catalog.normalize("BTC-USD")).thenReturn(Optional.of("BTC-USD"));

        assertThat(service.extractNormalizedCandidates("How is BTC-USD doing?"))
                .containsExactly("BTC-USD");
    }

    @Test
    void extractNormalizedCandidates_noCatalogMatch_returnsEmpty() {
        // All tokens return empty → no candidates

        assertThat(service.extractNormalizedCandidates("I am done")).isEmpty();
    }
}
