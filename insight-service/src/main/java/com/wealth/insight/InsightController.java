package com.wealth.insight;

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
     * Returns a map of all tracked tickers with price/trend data.
     *
     * <p>AI sentiment is intentionally <em>not</em> included here. With 50+ baseline tickers,
     * issuing one sequential Bedrock call per ticker would consume 30–100 s and reliably exceed
     * both the Lambda 60 s timeout and the CloudFront 30 s origin timeout, causing 502s.
     *
     * <p>Callers that need AI sentiment for a specific ticker should use the per-ticker endpoint:
     * {@code GET /api/insights/market-summary/{ticker}}, which calls Bedrock for a single ticker
     * and caches the result in Redis for 60 minutes.
     */
    @GetMapping("/market-summary")
    public ResponseEntity<Map<String, TickerSummary>> getMarketSummary() {
        try {
            return ResponseEntity.ok(marketDataService.getMarketSummary());
        } catch (Exception e) {
            log.error("market-summary endpoint failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Returns a single ticker's summary enriched with AI sentiment, or 404 if no data exists.
     *
     * <p>This is the only endpoint that calls Bedrock. The result is cached in Redis for 60 minutes
     * (see {@code BedrockAiInsightService}), so repeated lookups for the same ticker are fast.
     */
    @GetMapping("/market-summary/{ticker}")
    public ResponseEntity<?> getTickerSummary(@PathVariable String ticker) {
        try {
            TickerSummary summary = marketDataService.getTickerSummary(ticker);
            if (summary == null || summary.latestPrice() == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Ticker not found"));
            }
            String aiSummary = null;
            try {
                aiSummary = aiInsightService.getSentiment(summary.ticker());
            } catch (AdvisorUnavailableException e) {
                log.warn("AI sentiment unavailable for {}: {}", ticker, e.getMessage());
            }
            return ResponseEntity.ok(new TickerSummary(
                    summary.ticker(),
                    summary.latestPrice(),
                    summary.priceHistory(),
                    summary.trendPercent(),
                    aiSummary
            ));
        } catch (Exception e) {
            log.error("per-ticker summary failed for {}", ticker, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
