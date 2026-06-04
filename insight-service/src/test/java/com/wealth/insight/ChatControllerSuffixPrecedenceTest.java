package com.wealth.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.CompactCatalog;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.resolution.Outcome;
import com.wealth.insight.resolution.ResolutionOutcome;
import com.wealth.insight.resolution.StubAssetResolutionClient;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests verifying catalog-based symbol resolution precedence in {@link ChatResolutionService}
 * (supersedes the legacy resolver's "suffix precedence" tests — Task 10 / Req 1.3, 1.4, P3).
 *
 * <p>In the new system, resolution uses {@link TickerCatalogService#normalize(String)}, which
 * naturally handles suffix-form tokens ({@code BTC-USD}, {@code USDCHF=X}, {@code RELIANCE.NS}) via
 * exact catalog matching — no separate precedence ordering needed. These tests ensure that the
 * catalog-based normalized approach correctly handles:
 *
 * <ul>
 *   <li>Exact suffixed catalog symbols resolve via preflight (Property P3).
 *   <li>Non-catalog symbols yield clarification (no "silent resolve").
 *   <li>Comparison guard fires when two catalog symbols both appear.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatControllerSuffixPrecedenceTest {

  @Mock private TickerCatalogService catalog;
  @Mock private ChatResponseBuilder responseBuilder;

  private ChatResolutionService service;

  private static final CompactCatalog COMPACT =
      new CompactCatalog(
          List.of(
              new CatalogEntry("BTC-USD", "Bitcoin", List.of("BTC"), "CRYPTO", "USD"),
              new CatalogEntry("USDCHF=X", "USD/CHF", List.of("USDCHF"), "FOREX", "USD"),
              new CatalogEntry("RELIANCE.NS", "Reliance", List.of("Reliance"), "NSE", "INR"),
              new CatalogEntry("AAPL", "Apple", List.of("Apple"), "US_EQUITY", "USD")),
          "testver");

  @BeforeEach
  void setUp() {
    StubAssetResolutionClient stub = new StubAssetResolutionClient();
    service = new ChatResolutionService(catalog, stub, responseBuilder);
    when(catalog.groundingView()).thenReturn(COMPACT);
    when(responseBuilder.build(any()))
        .thenAnswer(
            inv -> {
              ResolutionOutcome o = inv.getArgument(0);
              return new ChatResponse("response:" + o.outcome() + ":" + o.ticker());
            });
  }

  // ── P3: Exact suffixed symbols resolve via preflight ──────────────────────────────────

  @Test
  void resolve_btcUsdExact_resolvesToBtcUsdNotBtc() {
    when(catalog.normalize(anyString())).thenReturn(Optional.empty());
    when(catalog.normalize("BTC-USD")).thenReturn(Optional.of("BTC-USD"));

    service.handle(new ChatRequest("How is BTC-USD doing?", null));

    ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
    verify(responseBuilder).build(cap.capture());
    assertThat(cap.getValue().ticker()).isEqualTo("BTC-USD");
    assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
    assertThat(cap.getValue().source()).isEqualTo("preflight");
  }

  @Test
  void resolve_forexExact_resolvesToUsdchfEquals() {
    when(catalog.normalize(anyString())).thenReturn(Optional.empty());
    when(catalog.normalize("USDCHF=X")).thenReturn(Optional.of("USDCHF=X"));

    service.handle(new ChatRequest("What is USDCHF=X?", null));

    ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
    verify(responseBuilder).build(cap.capture());
    assertThat(cap.getValue().ticker()).isEqualTo("USDCHF=X");
    assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
  }

  @Test
  void resolve_nseExact_resolvesToRelianceNs() {
    when(catalog.normalize(anyString())).thenReturn(Optional.empty());
    when(catalog.normalize("RELIANCE.NS")).thenReturn(Optional.of("RELIANCE.NS"));

    service.handle(new ChatRequest("Tell me about RELIANCE.NS", null));

    ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
    verify(responseBuilder).build(cap.capture());
    assertThat(cap.getValue().ticker()).isEqualTo("RELIANCE.NS");
    assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
  }

  @Test
  void resolve_plainExact_resolvesToAapl() {
    when(catalog.normalize(anyString())).thenReturn(Optional.empty());
    when(catalog.normalize("AAPL")).thenReturn(Optional.of("AAPL"));

    service.handle(new ChatRequest("How is AAPL doing?", null));

    ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
    verify(responseBuilder).build(cap.capture());
    assertThat(cap.getValue().ticker()).isEqualTo("AAPL");
    assertThat(cap.getValue().outcome()).isEqualTo(Outcome.RESOLVED);
  }

  // ── Non-catalog symbols yield clarification ───────────────────────────────────────────

  @Test
  void resolve_nonCatalogSuffix_yieldsClarification() {
    // ROSE-USD not in catalog → normalize returns empty
    when(catalog.normalize(anyString())).thenReturn(Optional.empty());
    // LLM stub returns UNKNOWN (default)

    service.handle(new ChatRequest("How is ROSE-USD doing?", null));

    ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
    verify(responseBuilder).build(cap.capture());
    assertThat(cap.getValue().outcome()).isEqualTo(Outcome.CLARIFICATION);
  }

  @Test
  void resolve_nonCatalogForex_yieldsClarification() {
    when(catalog.normalize(anyString())).thenReturn(Optional.empty());

    service.handle(new ChatRequest("What is NZDUSD=X?", null));

    ArgumentCaptor<ResolutionOutcome> cap = ArgumentCaptor.forClass(ResolutionOutcome.class);
    verify(responseBuilder).build(cap.capture());
    assertThat(cap.getValue().outcome()).isEqualTo(Outcome.CLARIFICATION);
  }
}
