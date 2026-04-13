package com.wealth.insight;

import com.wealth.insight.advisor.AdvisorUnavailableException;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.dto.TickerSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Stateless conversational REST endpoint for market insight queries.
 *
 * <p>Accepts a user message and optional ticker, resolves the ticker
 * (explicit field or extracted from message text), fetches market data
 * and AI sentiment, and returns a conversational plain-text response.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final MarketDataService marketDataService;
    private final AiInsightService aiInsightService;

    public ChatController(MarketDataService marketDataService,
                          AiInsightService aiInsightService) {
        this.marketDataService = marketDataService;
        this.aiInsightService = aiInsightService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String ticker = resolveTicker(request);
        if (ticker == null) {
            return ResponseEntity.ok(new ChatResponse(
                    "I couldn't identify a ticker symbol in your message. "
                    + "Could you specify one? (e.g., AAPL, MSFT, GOOG)"));
        }

        TickerSummary summary = marketDataService.getTickerSummary(ticker);
        if (summary == null || summary.latestPrice() == null) {
            return ResponseEntity.ok(new ChatResponse(
                    "I don't have any data for %s right now. It may not be tracked yet.".formatted(ticker)));
        }

        try {
            String sentiment = aiInsightService.getSentiment(ticker);
            return ResponseEntity.ok(new ChatResponse(
                    buildConversationalResponse(ticker, summary, sentiment)));
        } catch (AdvisorUnavailableException e) {
            log.warn("AI unavailable during chat for {}: {}", ticker, e.getMessage());
            return ResponseEntity.ok(new ChatResponse(
                    buildConversationalResponse(ticker, summary, null)
                    + " (AI analysis is temporarily unavailable.)"));
        }
    }

    private String resolveTicker(ChatRequest request) {
        if (request.ticker() != null && !request.ticker().isBlank()) {
            return request.ticker().toUpperCase();
        }
        return extractTicker(request.message());
    }

    /**
     * Extracts the first valid ticker symbol from a natural language message.
     * Filters common English stop words that match the 1-5 uppercase letter pattern.
     *
     * @param message the user's message text
     * @return the first valid ticker found, or null if none identified
     */
    static String extractTicker(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String[] tokens = message.split("\\s+");
        for (String token : tokens) {
            String cleaned = token.replaceAll("[^A-Za-z]", "").toUpperCase();
            if (cleaned.length() >= 1 && cleaned.length() <= 5
                    && cleaned.matches("[A-Z]+")
                    && !STOP_WORDS.contains(cleaned)) {
                return cleaned;
            }
        }
        return null;
    }

    private String buildConversationalResponse(String ticker, TickerSummary summary, String sentiment) {
        var sb = new StringBuilder();
        sb.append("Here's what I found for %s: ".formatted(ticker));
        sb.append("the latest price is $%s".formatted(summary.latestPrice().toPlainString()));

        if (summary.trendPercent() != null) {
            String sign = summary.trendPercent().signum() >= 0 ? "+" : "";
            sb.append(" with a trend of %s%s%%".formatted(sign, summary.trendPercent().toPlainString()));
        }
        sb.append(".");

        if (sentiment != null) {
            sb.append(" ").append(sentiment);
        }
        return sb.toString();
    }

    static final Set<String> STOP_WORDS = Set.of(
            "I", "A", "THE", "HOW", "IS", "IT", "DO", "WHAT", "ARE", "IN",
            "ON", "AT", "TO", "FOR", "OF", "AND", "OR", "MY", "ME", "SO",
            "IF", "UP", "BY", "AN", "AS", "BE", "WE", "HE", "NO", "AM",
            "HAS", "HAD", "WAS", "BUT", "NOT", "YOU", "ALL", "CAN", "HER",
            "HIS", "ONE", "OUR", "OUT", "WHO", "DID", "GET", "HIM", "LET",
            "SAY", "SHE", "TOO", "USE", "DOES", "DOING", "ABOUT", "GOING"
    );
}
