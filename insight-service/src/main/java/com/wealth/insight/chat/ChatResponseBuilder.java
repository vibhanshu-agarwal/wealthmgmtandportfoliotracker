package com.wealth.insight.chat;

import com.wealth.insight.AiInsightService;
import com.wealth.insight.MarketDataService;
import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.catalog.CatalogEntry;
import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.dto.TickerSummary;
import com.wealth.insight.resolution.Outcome;
import com.wealth.insight.resolution.ResolutionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the final user-facing {@link ChatResponse} from a validated {@link ResolutionOutcome}
 * (Task 7 / Req 3.1, 3.2, 3.4, 3.5, 4.2, 5.1, 6.2, 6.5).
 *
 * <p><strong>Currency convention (Req 3.5):</strong> prices are formatted in the asset's
 * {@code quoteCurrency} from the catalog — INR for {@code *.NS}, pair convention for FOREX
 * (e.g. "EUR/USD"), USD for US equities and crypto. The dollar sign ({@code $}) is never
 * hardcoded.
 *
 * <p><strong>Facts from Redis only (design Property 2):</strong> all numeric values come from
 * {@link MarketDataService} ({@code TickerSummary}); the LLM never supplies numbers.
 *
 * <p><strong>Never empty (design Property 6):</strong> the {@code switch} in {@link #build} is
 * exhaustive over all {@link Outcome} values, so every code path produces a non-blank response.
 */
@Service
public class ChatResponseBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatResponseBuilder.class);

    /** Maximum assets shown in a discovery listing (all categories combined). */
    private static final int DISCOVERY_MAX_TOTAL = 20;
    /** Maximum assets shown per category in a discovery listing. */
    private static final int DISCOVERY_MAX_PER_CATEGORY = 5;

    private final TickerCatalogService catalog;
    private final MarketDataService marketData;
    private final AiInsightService aiInsight;

    public ChatResponseBuilder(TickerCatalogService catalog,
                               MarketDataService marketData,
                               AiInsightService aiInsight) {
        this.catalog = catalog;
        this.marketData = marketData;
        this.aiInsight = aiInsight;
    }

    /**
     * Builds the final user-facing response from the validated {@link ResolutionOutcome}.
     *
     * <p>For {@link Outcome#RESOLVED}: fetches the {@link TickerSummary} from Redis, formats
     * the price in the asset's quote currency, and appends sentiment if available.
     * A null {@code latestPrice} from Redis is treated as no-data (naming the ticker).
     *
     * @param outcome the catalog-validated resolution result (never null)
     * @return a non-empty {@link ChatResponse} (Property 6)
     */
    public ChatResponse build(ResolutionOutcome outcome) {
        return switch (outcome.outcome()) {
            case RESOLVED  -> buildResolved(outcome);
            case NO_DATA   -> buildNoData(outcome.ticker());
            case CLARIFICATION  -> buildClarification(outcome);
            case DISCOVERY      -> buildDiscovery(outcome);
            case COMPARISON_REDIRECT -> buildComparisonRedirect(outcome);
            case GREETING_HELP  -> buildGreetingHelp();
        };
    }

    // ── RESOLVED ─────────────────────────────────────────────────────────────

    private ChatResponse buildResolved(ResolutionOutcome outcome) {
        String ticker = outcome.ticker();
        TickerSummary summary = marketData.getTickerSummary(ticker);

        if (summary == null || summary.latestPrice() == null) {
            return buildNoData(ticker);
        }

        CatalogEntry entry = catalog.find(ticker).orElse(null);
        String currency   = entry != null ? entry.quoteCurrency() : "USD";
        String name       = entry != null ? entry.name() : ticker;
        String assetClass = entry != null ? entry.assetClass() : "";

        StringBuilder sb = new StringBuilder();
        sb.append("Here's what I found for ").append(name).append(" (").append(ticker).append("): ");
        sb.append("the latest price is ").append(formatPrice(summary.latestPrice(), currency, assetClass));

        if (summary.trendPercent() != null) {
            String sign = summary.trendPercent().signum() >= 0 ? "+" : "";
            sb.append(" with a trend of ").append(sign)
              .append(summary.trendPercent().toPlainString()).append("%");
        }
        sb.append(".");

        // Sentiment — Req 3.4, 6.2
        try {
            String sentiment = aiInsight.getSentiment(ticker);
            if (sentiment != null && !sentiment.isBlank()) {
                sb.append(" ").append(sentiment);
            }
        } catch (AdvisorUnavailableException e) {
            log.warn("Sentiment unavailable for {}: {}", ticker, e.getMessage());
            sb.append(" (AI analysis is temporarily unavailable.)");
        }

        return new ChatResponse(sb.toString());
    }

    // ── NO_DATA ───────────────────────────────────────────────────────────────

    private ChatResponse buildNoData(String ticker) {
        return new ChatResponse(
                "I don't have any live data for " + ticker + " right now. "
                + "It may not be actively tracked yet or data may be temporarily unavailable.");
    }

    // ── CLARIFICATION ─────────────────────────────────────────────────────────

    private ChatResponse buildClarification(ResolutionOutcome outcome) {
        List<String> candidates = outcome.candidates();
        if (candidates.isEmpty()) {
            return new ChatResponse(
                    "I couldn't identify a specific asset from your message. "
                    + "Could you be more specific? For example, try AAPL, BTC-USD, or HDFCBANK.NS.");
        }

        String candidateList = candidates.stream()
                .map(t -> {
                    Optional<CatalogEntry> e = catalog.find(t);
                    return e.map(ce -> ce.name() + " (" + t + ")").orElse(t);
                })
                .collect(Collectors.joining(", "));

        return new ChatResponse(
                "I found a few possible matches: " + candidateList
                + ". Could you clarify which one you meant?");
    }

    // ── DISCOVERY ─────────────────────────────────────────────────────────────

    private ChatResponse buildDiscovery(ResolutionOutcome outcome) {
        String categoryFilter = outcome.categoryFilter();

        // Prefer active market data (non-null latestPrice)
        Map<String, TickerSummary> marketSummary = marketData.getMarketSummary();
        boolean marketDataAvailable = !marketSummary.isEmpty();

        List<CatalogEntry> universe;
        String preamble;

        if (marketDataAvailable) {
            // Filter to entries with live data AND in the requested category
            universe = marketSummary.entrySet().stream()
                    .filter(e -> e.getValue().latestPrice() != null)
                    .map(e -> catalog.find(e.getKey()))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .filter(e -> categoryFilter == null || categoryFilter.equals(e.assetClass()))
                    .toList();
            preamble = "Here are some of the assets I'm currently tracking";
        } else {
            // Fallback to catalog (Req 4.4)
            universe = catalog.byCategory(categoryFilter);
            preamble = "Here are some assets I can tell you about "
                    + "(live data is temporarily unavailable)";
        }

        if (universe.isEmpty()) {
            String cat = categoryFilter != null ? categoryFilter.toLowerCase() : "any category";
            return new ChatResponse(
                    "I don't have live data available for " + cat + " at the moment. "
                    + "Try asking about a specific asset by name or ticker symbol.");
        }

        // Group by asset class (TreeMap for deterministic ordering), bounded per-category and overall
        Map<String, List<CatalogEntry>> byClass = universe.stream()
                .collect(Collectors.groupingBy(CatalogEntry::assetClass, TreeMap::new, Collectors.toList()));

        List<String> lines = new ArrayList<>();
        int totalShown = 0;
        boolean truncated = false;

        for (Map.Entry<String, List<CatalogEntry>> classEntry : byClass.entrySet()) {
            List<CatalogEntry> entries = classEntry.getValue();
            int limit = Math.min(DISCOVERY_MAX_PER_CATEGORY, DISCOVERY_MAX_TOTAL - totalShown);
            if (limit <= 0) { truncated = true; break; }

            List<CatalogEntry> shown = entries.subList(0, Math.min(limit, entries.size()));
            if (shown.size() < entries.size()) truncated = true;

            String assetLine = shown.stream()
                    .map(e -> e.name() + " (" + e.ticker() + ")")
                    .collect(Collectors.joining(", "));

            lines.add("**" + classEntry.getKey() + "**: " + assetLine);
            totalShown += shown.size();
        }

        String body = String.join("\n", lines);
        String suffix = truncated ? "\n...and more. Ask about a specific one for details!" : "";

        if (categoryFilter != null) {
            preamble += " in the " + categoryFilter + " category";
        }
        return new ChatResponse(preamble + ":\n" + body + suffix);
    }

    // ── COMPARISON_REDIRECT ───────────────────────────────────────────────────

    private ChatResponse buildComparisonRedirect(ResolutionOutcome outcome) {
        List<String> candidates = outcome.candidates();
        if (candidates.isEmpty()) {
            return new ChatResponse(
                    "I can summarize one asset at a time. "
                    + "Which asset would you like to know about?");
        }
        String candidateList = candidates.stream()
                .map(t -> {
                    Optional<CatalogEntry> e = catalog.find(t);
                    return e.map(ce -> ce.name() + " (" + t + ")").orElse(t);
                })
                .collect(Collectors.joining(" or "));

        return new ChatResponse(
                "I can summarize one asset at a time for now. "
                + "Which would you like — " + candidateList + "?");
    }

    // ── GREETING_HELP ─────────────────────────────────────────────────────────

    private ChatResponse buildGreetingHelp() {
        return new ChatResponse(
                "Hello! I'm your market insight assistant. I can help you with:\n"
                + "• Price and trend data for stocks, crypto, and forex\n"
                + "• AI-powered market sentiment for tracked assets\n"
                + "• Discovery: ask \"which stocks/crypto/forex do you track?\"\n\n"
                + "Try asking: \"Tell me about Apple\", \"How is BTC doing?\", "
                + "or \"What is the latest on HDFCBANK.NS?\"");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Formats a price value with the correct currency label for the asset class.
     *
     * <ul>
     *   <li>FOREX: just the numeric price — the pair name (e.g. "EUR/USD") already appears in
     *       the response header, so prefixing "USD" would be redundant and misleading.
     *   <li>All others: {@code quoteCurrency + " " + price} — e.g. "INR 1580.00", "USD 178.50".
     * </ul>
     */
    private String formatPrice(BigDecimal price, String quoteCurrency, String assetClass) {
        String priceStr = price.toPlainString();
        if ("FOREX".equals(assetClass)) {
            // Pair convention: bare price; the pair name shown in the header carries the context.
            return priceStr;
        }
        return quoteCurrency + " " + priceStr;
    }
}
