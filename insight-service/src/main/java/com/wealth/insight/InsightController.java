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
import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.dto.TickerSummary;

/**
 * REST controller for market summaries and per-ticker AI sentiment.
 *
 * <p>It deliberately has no portfolio-analysis route. The removed
 * {@code GET /api/insights/{userId}/analyze} took its target user from the path and forwarded it to
 * portfolio-service, so any signed-in caller could read another user's analysis.
 * {@code AdvisorAnalyzeRemovalIT} pins that no insights route takes a user from the path or holds
 * {@link InsightService}.
 */
@RestController
@RequestMapping("/api/insights")
public class InsightController {

    private static final Logger log = LoggerFactory.getLogger(InsightController.class);

    private final MarketDataService marketDataService;
    private final AiInsightService aiInsightService;
    private final TickerCatalogService catalog;

    public InsightController(MarketDataService marketDataService,
                             AiInsightService aiInsightService,
                             TickerCatalogService catalog) {
        this.marketDataService = marketDataService;
        this.aiInsightService = aiInsightService;
        this.catalog = catalog;
    }

    /** The ticker's catalog quote currency, or null when it is not in the catalog. */
    private String quoteCurrencyOf(String ticker) {
        return catalog.find(ticker).map(CatalogEntry::quoteCurrency).orElse(null);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "insight-service"));
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
            Map<String, TickerSummary> summaries = new LinkedHashMap<>();
            marketDataService.getMarketSummary().forEach((ticker, summary) ->
                    summaries.put(ticker, summary.withQuoteCurrency(quoteCurrencyOf(summary.ticker()))));
            return ResponseEntity.ok(summaries);
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
            SentimentSource aiSummarySource = null;
            try {
                aiSummary = aiInsightService.getSentiment(summary.ticker());
                if (aiSummary != null) {
                    aiSummarySource = aiInsightService.sentimentSource();
                }
            } catch (AdvisorUnavailableException e) {
                log.warn("AI sentiment unavailable for {}: {}", ticker, e.getMessage());
            }
            return ResponseEntity.ok(new TickerSummary(
                    summary.ticker(),
                    summary.latestPrice(),
                    summary.priceHistory(),
                    summary.trendPercent(),
                    aiSummary,
                    quoteCurrencyOf(summary.ticker()),
                    aiSummarySource
            ));
        } catch (Exception e) {
            log.error("per-ticker summary failed for {}", ticker, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
