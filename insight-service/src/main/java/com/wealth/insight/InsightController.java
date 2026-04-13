package com.wealth.insight;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.advisor.AnalysisResult;
import com.wealth.insight.dto.TickerSummary;

/**
 * REST controller for AI-powered portfolio analysis and market summaries.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightController {

    private static final Logger log = LoggerFactory.getLogger(InsightController.class);

    private final InsightService insightService;
    private final MarketDataService marketDataService;
    private final AiInsightService aiInsightService;

    public InsightController(InsightService insightService,
                             MarketDataService marketDataService,
                             AiInsightService aiInsightService) {
        this.insightService = insightService;
        this.marketDataService = marketDataService;
        this.aiInsightService = aiInsightService;
    }

    @GetMapping("/{userId}/analyze")
    public ResponseEntity<AnalysisResult> analyzePortfolio(@PathVariable String userId) {
        return ResponseEntity.ok(insightService.analyzePortfolio(userId));
    }

    /**
     * Returns a map of all tracked tickers enriched with AI sentiment summaries.
     */
    @GetMapping("/market-summary")
    public ResponseEntity<Map<String, TickerSummary>> getMarketSummary() {
        try {
            Map<String, TickerSummary> raw = marketDataService.getMarketSummary();
            Map<String, TickerSummary> enriched = new LinkedHashMap<>();
            for (var entry : raw.entrySet()) {
                enriched.put(entry.getKey(), enrichWithAiSummary(entry.getValue()));
            }
            return ResponseEntity.ok(enriched);
        } catch (Exception e) {
            log.error("market-summary endpoint failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Returns a single ticker's summary enriched with AI sentiment, or 404 if no data exists.
     */
    @GetMapping("/market-summary/{ticker}")
    public ResponseEntity<?> getTickerSummary(@PathVariable String ticker) {
        try {
            TickerSummary summary = marketDataService.getTickerSummary(ticker);
            if (summary == null || summary.latestPrice() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Ticker not found"));
            }
            return ResponseEntity.ok(enrichWithAiSummary(summary));
        } catch (Exception e) {
            log.error("per-ticker summary failed for {}", ticker, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Enriches a TickerSummary with an AI sentiment summary.
     * On AI failure, returns the summary with aiSummary set to null.
     */
    private TickerSummary enrichWithAiSummary(TickerSummary summary) {
        String aiSummary = null;
        try {
            aiSummary = aiInsightService.getSentiment(summary.ticker());
        } catch (AdvisorUnavailableException e) {
            log.warn("AI sentiment unavailable for {}: {}", summary.ticker(), e.getMessage());
        }
        return new TickerSummary(
                summary.ticker(),
                summary.latestPrice(),
                summary.priceHistory(),
                summary.trendPercent(),
                aiSummary
        );
    }
}
