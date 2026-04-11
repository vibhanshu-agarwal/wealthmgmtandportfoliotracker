package com.wealth.insight;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public InsightController(InsightService insightService, MarketDataService marketDataService) {
        this.insightService = insightService;
        this.marketDataService = marketDataService;
    }

    @GetMapping("/{userId}/analyze")
    public ResponseEntity<AnalysisResult> analyzePortfolio(@PathVariable String userId) {
        return ResponseEntity.ok(insightService.analyzePortfolio(userId));
    }

    /**
     * Returns a map of all tracked tickers with their latest price,
     * 10-point sliding window history, and trend percentage.
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
}
