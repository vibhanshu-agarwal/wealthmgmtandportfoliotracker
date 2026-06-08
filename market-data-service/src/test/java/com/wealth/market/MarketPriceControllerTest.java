package com.wealth.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class MarketPriceControllerTest {

    @Test
    void health_returnsServiceStatus() {
        var controller = new MarketPriceController(
                mock(AssetPriceRepository.class),
                mock(MarketPriceService.class));

        var response = controller.health();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .containsEntry("service", "market-data-service");
    }

    // ── No silent truncation: cap raised to MAX_TICKERS_PER_REQUEST ───────────

    @Test
    void maxTickersPerRequest_isAtLeast160() {
        // The golden-state portfolio holds 160 tickers; the cap must accommodate them.
        assertThat(MarketPriceController.MAX_TICKERS_PER_REQUEST).isGreaterThanOrEqualTo(160);
    }

    // ── Over-limit request returns 400, not truncated 200 ─────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void overLimitRequest_returns400() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        var controller = new MarketPriceController(repo, mock(MarketPriceService.class));

        // Build a comma-separated list of MAX+1 distinct tickers.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= MarketPriceController.MAX_TICKERS_PER_REQUEST; i++) {
            if (i > 0) sb.append(",");
            sb.append("TICKER").append(i);
        }

        ResponseEntity<?> response = controller.getPrices(sb.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // ── Missing tickers returned as unavailable (null currentPrice), not omitted ──

    @Test
    @SuppressWarnings("unchecked")
    void missingTicker_returnedAsUnavailableRow_notOmitted() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        AssetPrice aapl = new AssetPrice("AAPL", new BigDecimal("195.00"));
        // MSFT is "requested" but not in the data store.
        when(repo.findByTickerIn(List.of("AAPL", "MSFT"))).thenReturn(List.of(aapl));

        var controller = new MarketPriceController(repo, mock(MarketPriceService.class));

        ResponseEntity<?> response = controller.getPrices("AAPL,MSFT");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        List<MarketPriceDto> body = (List<MarketPriceDto>) response.getBody();
        assertThat(body).hasSize(2);

        // MSFT must be present as an unavailable row (null price), not absent.
        MarketPriceDto msft = body.stream().filter(d -> "MSFT".equals(d.ticker())).findFirst()
                .orElseThrow(() -> new AssertionError("MSFT absent from response"));
        assertThat(msft.currentPrice()).isNull();
        // observedAt must NOT be set to now() for missing data.
        assertThat(msft.observedAt()).isNull();
    }

    // ── Found tickers include change fields ───────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void foundTicker_withReference_hasChangeFields() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        AssetPrice aapl = new AssetPrice("AAPL", new BigDecimal("195.00"));
        aapl.setQuoteCurrency("USD");
        // Simulate a prior observation rolled to reference.
        aapl.setPreviousReferencePrice(new BigDecimal("190.00"));
        aapl.setPreviousReferenceAt(java.time.Instant.now().minusSeconds(3600));

        when(repo.findByTickerIn(List.of("AAPL"))).thenReturn(List.of(aapl));

        var controller = new MarketPriceController(repo, mock(MarketPriceService.class));
        ResponseEntity<?> response = controller.getPrices("AAPL");

        @SuppressWarnings("unchecked")
        List<MarketPriceDto> body = (List<MarketPriceDto>) response.getBody();
        MarketPriceDto dto = body.getFirst();

        assertThat(dto.changeAbsolute()).isEqualByComparingTo("5.0000");  // 195 - 190
        assertThat(dto.changePercent()).isNotNull();
        assertThat(dto.changeBasis()).isNotNull();
    }

    // ── Found ticker with no reference: change null, not 0 ───────────────────

    @Test
    @SuppressWarnings("unchecked")
    void foundTicker_noReference_changeFieldsAreNull() {
        AssetPriceRepository repo = mock(AssetPriceRepository.class);
        AssetPrice btc = new AssetPrice("BTC-USD", new BigDecimal("67000.00"));
        btc.setQuoteCurrency("USD");
        // No prior reference (freshly inserted).
        when(repo.findByTickerIn(List.of("BTC-USD"))).thenReturn(List.of(btc));

        var controller = new MarketPriceController(repo, mock(MarketPriceService.class));
        ResponseEntity<?> response = controller.getPrices("BTC-USD");

        @SuppressWarnings("unchecked")
        List<MarketPriceDto> body = (List<MarketPriceDto>) response.getBody();
        MarketPriceDto dto = body.getFirst();

        // Change must be null (unavailable), not 0.
        assertThat(dto.changeAbsolute()).isNull();
        assertThat(dto.changePercent()).isNull();
        assertThat(dto.changeBasis()).isNull();
    }
}
