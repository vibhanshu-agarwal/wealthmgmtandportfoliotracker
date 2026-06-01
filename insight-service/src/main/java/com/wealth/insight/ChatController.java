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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern DOLLAR_TICKER_PATTERN = Pattern.compile("\\$([A-Za-z]{1,5})");
    private static final Pattern UPPERCASE_TICKER_PATTERN = Pattern.compile("\\b([A-Z]{1,5})\\b");
    // Suffix-aware patterns: crypto (e.g. ROSE-USD), forex (e.g. USDCHF=X), NSE (e.g. RELIANCE.NS).
    // Matched against whitespace-delimited tokens after stripping leading/trailing conversational
    // punctuation (?,. etc.) but preserving the suffix delimiter itself.
    private static final Pattern SUFFIX_CRYPTO_PATTERN = Pattern.compile("^([A-Za-z]{1,15})-USD$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX_FOREX_PATTERN  = Pattern.compile("^([A-Za-z]{3}[A-Za-z]{3})=X$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX_NSE_PATTERN    = Pattern.compile("^([A-Za-z]{1,15})\\.NS$", Pattern.CASE_INSENSITIVE);

    private final MarketDataService marketDataService;
    private final AiInsightService aiInsightService;

    public ChatController(MarketDataService marketDataService,
                          AiInsightService aiInsightService) {
        this.marketDataService = marketDataService;
        this.aiInsightService = aiInsightService;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        ResolutionResult resolution = resolveTicker(request);
        if (resolution.ticker() == null) {
            log.info("chat.response.outcome status=clarification resolutionSource={} candidates={}",
                    resolution.source(), resolution.candidates());
            return ResponseEntity.ok(new ChatResponse(
                    "I couldn't identify a single ticker symbol from your message. "
                    + "Could you specify one? (e.g., AAPL, MSFT, GOOG)"));
        }

        String ticker = resolution.ticker();
        log.info("chat.ticker.resolved ticker={} resolutionSource={}", ticker, resolution.source());

        TickerSummary summary = marketDataService.getTickerSummary(ticker);
        if (summary == null || summary.latestPrice() == null) {
            log.info("chat.response.outcome status=no_data ticker={} resolutionSource={}",
                    ticker, resolution.source());
            return ResponseEntity.ok(new ChatResponse(
                    "I don't have any data for %s right now. It may not be tracked yet.".formatted(ticker)));
        }

        try {
            String sentiment = aiInsightService.getSentiment(ticker);
            log.info("chat.response.outcome status=ok ticker={} resolutionSource={} ai=available",
                    ticker, resolution.source());
            return ResponseEntity.ok(new ChatResponse(
                    buildConversationalResponse(ticker, summary, sentiment)));
        } catch (AdvisorUnavailableException e) {
            log.warn("AI unavailable during chat for {}: {}", ticker, e.getMessage());
            log.info("chat.response.outcome status=ok ticker={} resolutionSource={} ai=unavailable",
                    ticker, resolution.source());
            return ResponseEntity.ok(new ChatResponse(
                    buildConversationalResponse(ticker, summary, null)
                    + " (AI analysis is temporarily unavailable.)"));
        }
    }

    private ResolutionResult resolveTicker(ChatRequest request) {
        if (request.ticker() != null && !request.ticker().isBlank()) {
            return new ResolutionResult(request.ticker().toUpperCase(Locale.ROOT), "explicit", List.of());
        }

        String message = request.message();
        if (message == null || message.isBlank()) {
            return new ResolutionResult(null, "none", List.of());
        }

        List<String> dollarCandidates = extractRegexCandidates(message, DOLLAR_TICKER_PATTERN);
        List<String> uppercaseCandidates = extractRegexCandidates(message, UPPERCASE_TICKER_PATTERN);
        List<String> suffixCandidates = extractSuffixCandidates(message);
        List<String> conversationalCandidates = extractTickerCandidates(message);
        log.info("chat.ticker.candidates source=regex-dollar values={}", dollarCandidates);
        log.info("chat.ticker.candidates source=regex-uppercase values={}", uppercaseCandidates);
        log.info("chat.ticker.candidates source=suffix-aware values={}", suffixCandidates);
        log.info("chat.ticker.candidates source=conversational values={}", conversationalCandidates);

        String trackedDollar = findFirstTrackedTicker(dollarCandidates);
        if (trackedDollar != null) {
            return new ResolutionResult(trackedDollar, "regex-dollar-tracked", dollarCandidates);
        }

        // Suffix candidates are evaluated before plain uppercase candidates so that an exact
        // tracked suffixed symbol (e.g. ABC-USD) cannot lose to a plain stem (e.g. ABC) that
        // the uppercase pattern extracts from the same token.
        String trackedSuffix = findFirstTrackedTicker(suffixCandidates);
        if (trackedSuffix != null) {
            return new ResolutionResult(trackedSuffix, "suffix-aware-tracked", suffixCandidates);
        }

        String trackedUppercase = findFirstTrackedTicker(uppercaseCandidates);
        if (trackedUppercase != null) {
            return new ResolutionResult(trackedUppercase, "regex-uppercase-tracked", uppercaseCandidates);
        }

        String trackedConversational = findFirstTrackedTicker(conversationalCandidates);
        if (trackedConversational != null) {
            return new ResolutionResult(trackedConversational, "conversational-tracked", conversationalCandidates);
        }

        if (dollarCandidates.size() == 1) {
            return new ResolutionResult(dollarCandidates.getFirst(), "regex-dollar-fallback", dollarCandidates);
        }
        if (uppercaseCandidates.size() == 1) {
            return new ResolutionResult(uppercaseCandidates.getFirst(), "regex-uppercase-fallback", uppercaseCandidates);
        }
        // Suffix fallback is intentionally omitted: an untracked suffixed symbol (e.g. ROSE-USD
        // not in Redis) must preserve the clarification response, not produce a no-data response.
        // Only tracked suffixed symbols are resolved (handled above via suffix-aware-tracked).
        if (conversationalCandidates.size() == 1) {
            return new ResolutionResult(conversationalCandidates.getFirst(), "conversational-fallback", conversationalCandidates);
        }

        return new ResolutionResult(null, "ambiguous", conversationalCandidates);
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

        for (String token : message.split("\\s+")) {
            String cleaned = token.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
            if (!cleaned.isEmpty() && cleaned.length() <= 5
                    && cleaned.matches("[A-Z]+")
                    && !STOP_WORDS.contains(cleaned)) {
                return cleaned;
            }
        }
        return null;
    }

    private List<String> extractRegexCandidates(String message, Pattern pattern) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group(1).toUpperCase(Locale.ROOT);
            if (!STOP_WORDS.contains(candidate)) {
                unique.add(candidate);
            }
        }
        return new ArrayList<>(unique);
    }

    private List<String> extractTickerCandidates(String message) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String token : message.split("\\s+")) {
            String cleaned = token.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
            if (!cleaned.isEmpty()
                    && cleaned.length() <= 5
                    && cleaned.matches("[A-Z]+")
                    && !STOP_WORDS.contains(cleaned)) {
                unique.add(cleaned);
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * Extracts suffixed ticker candidates from whitespace-delimited tokens, preserving the
     * suffix delimiter exactly as the registry stores it (e.g. {@code ROSE-USD},
     * {@code USDCHF=X}, {@code RELIANCE.NS}).
     *
     * <p>Only leading/trailing conversational punctuation ({@code ?}, {@code ,}, {@code !})
     * is stripped before matching; the suffix delimiter ({@code -}, {@code =}, {@code .}) is
     * never removed. The alphabetic stem is upper-cased; the suffix token is appended verbatim.
     *
     * <p>Stop-word filtering is intentionally not applied here: the suffix patterns
     * ({@code -USD}, {@code =X}, {@code .NS}) are already highly specific discriminators, and
     * a registry symbol whose stem happens to match a stop word (e.g. a hypothetical
     * {@code BE-USD}) must still be resolvable when tracked. Stop-word exclusion is applied
     * on the plain uppercase and conversational extraction paths where false positives are
     * common (e.g. {@code IS}, {@code DO}, {@code BE} as standalone tokens).
     */
    private List<String> extractSuffixCandidates(String message) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : message.split("\\s+")) {
            // Strip only leading/trailing conversational punctuation, not suffix delimiters.
            String token = raw.replaceAll("^[?!,.'\"]+|[?!,.'\"]+$", "");
            String upper = token.toUpperCase(Locale.ROOT);

            if (SUFFIX_CRYPTO_PATTERN.matcher(upper).matches()
                    || SUFFIX_FOREX_PATTERN.matcher(upper).matches()
                    || SUFFIX_NSE_PATTERN.matcher(upper).matches()) {
                unique.add(upper);
            }
        }
        return new ArrayList<>(unique);
    }

    private String findFirstTrackedTicker(List<String> candidates) {
        for (String candidate : candidates) {
            TickerSummary summary = marketDataService.getTickerSummary(candidate);
            if (summary != null && summary.latestPrice() != null) {
                return candidate;
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

    private record ResolutionResult(String ticker, String source, List<String> candidates) {}
}
