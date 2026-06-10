package com.wealth.insight;

import com.wealth.insight.catalog.TickerCatalogService;
import com.wealth.insight.chat.ChatResponseBuilder;
import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import com.wealth.insight.resolution.AssetResolutionClient;
import com.wealth.insight.resolution.Intent;
import com.wealth.insight.resolution.LlmResolution;
import com.wealth.insight.resolution.LlmResolutionException;
import com.wealth.insight.resolution.Outcome;
import com.wealth.insight.resolution.ResolutionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical request orchestrator for the LLM-driven natural-language asset resolution feature
 * (Task 8 / Req 1.1, 1.5, 2.1-2.6, 3.1, 3.3, 4.1, 4.3-4.5, 5.1, 5.2, 8.1, 8.2).
 *
 * <p>Owns the full resolution turn and returns a {@link ChatResponse}. The resolution steps are:
 * <ol>
 *   <li>Explicit ticker field — catalog-validated; unsupported → clarification.</li>
 *   <li>Deterministic preflight — {@code normalize()} every whitespace token; comparison guard.</li>
 *   <li>Discovery shortcut — phrase detection + deterministic category extraction.</li>
 *   <li>Single LLM call via {@link AssetResolutionClient}.</li>
 *   <li>Catalog validation of LLM-proposed tickers (drops invented symbols).</li>
 *   <li>Intent branching (ASSET_QUERY / DISCOVERY / COMPARISON / GREETING_HELP / UNKNOWN).</li>
 *   <li>Response building delegated to {@link ChatResponseBuilder} (sole Redis-touch point).</li>
 * </ol>
 *
 * <p>On LLM failure, falls back to deterministic-only resolution (Task 9 / Req 6.1, 6.6).
 * Emits one structured outcome log per request (Task 11 / Req 9.1).
 * Stateless: deictic-only messages with no resolvable asset yield clarification (Task 12).
 *
 * <p><strong>Fallback safety (Property P7 / Task 9):</strong> when {@link AssetResolutionClient}
 * throws {@link LlmResolutionException} (unavailable, timeout, or malformed output), resolution
 * falls back to {@link #fallback} which only resolves:
 * <ul>
 *   <li>Exact canonical symbols already in the catalog (e.g., {@code AAPL}).</li>
 *   <li>Catalog-derived stem/pair forms via {@code normalize()} (e.g., {@code BTC} → {@code BTC-USD},
 *       {@code USDCHF} → {@code USDCHF=X}).</li>
 * </ul>
 * Arbitrary uppercase tokens and natural-language names/aliases are NOT resolved in fallback
 * — they yield a clarification. Logged with {@code source="fallback-exact"}.
 */
@Service
public class ChatResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ChatResolutionService.class);

    private static final Pattern DOLLAR_PATTERN =
            Pattern.compile("\\$([A-Za-z0-9.\\-=]{1,15})");

    /**
     * Comparison-cue keywords that signal multi-asset intent (Task 8 / Req 5.1).
     *
     * <p>"vs" (without dot) is intentionally excluded: it is only 2 characters and is a valid
     * Yahoo Finance ticker suffix (e.g. "VS" for Versus Systems). When both assets are already in
     * the catalog, preflight candidates size {@literal >} 1 handles the redirect without needing
     * the cue. "vs." (with trailing dot) is retained for "AAPL vs. MSFT" written-style queries.
     */
    private static final Set<String> COMPARISON_CUES =
            Set.of("compare", "versus", "vs.");

    /** Discovery trigger phrases detected before invoking the LLM (Task 8 / Req 4.1). */
    private static final List<String> DISCOVERY_TRIGGER_PHRASES = List.of(
            "do you track", "can you track", "do you cover", "do you support",
            "can you tell", "can i ask about", "what do you have",
            "what can you", "do you know about", "show me all", "list all"
    );

    /** Discovery listing words (combined with asset words to detect discovery queries). */
    private static final Set<String> DISCOVERY_LISTING_WORDS =
            Set.of("list", "show", "what", "which", "all", "give", "available");

    /** Asset category words used for discovery category detection. */
    private static final Set<String> ASSET_CLASS_WORDS =
            Set.of("asset", "assets", "stock", "stocks", "crypto", "cryptocurrency",
                    "cryptocurrencies", "forex", "currency", "currencies", "equity",
                    "equities", "ticker", "tickers", "symbol", "symbols", "investment",
                    "investments", "share", "shares");

    private final TickerCatalogService catalog;
    private final AssetResolutionClient resolutionClient;
    private final ChatResponseBuilder responseBuilder;

    /**
     * Internal carrier that bundles the validated {@link ResolutionOutcome} with the LLM
     * entity labels extracted from the LLM response (empty for deterministic paths).
     * Used only within {@link #handle} to populate the structured log (Task 11).
     */
    private record ResolveResult(ResolutionOutcome outcome, List<String> llmEntities) {
        /** Convenience factory for non-LLM paths that produce no entity labels. */
        static ResolveResult of(ResolutionOutcome outcome) {
            return new ResolveResult(outcome, List.of());
        }
    }

    public ChatResolutionService(TickerCatalogService catalog,
                                 AssetResolutionClient resolutionClient,
                                 ChatResponseBuilder responseBuilder) {
        this.catalog = catalog;
        this.resolutionClient = resolutionClient;
        this.responseBuilder = responseBuilder;
    }

    /**
     * Handles a single chat turn: resolves the asset, builds and returns the response.
     * At most one LLM call is made per invocation; deterministic paths make zero (Req 2.1, 8.2).
     *
     * <p>Emits one structured log entry per request covering all fields required by Task 11 /
     * Req 9.1. No secrets, system prompts, or full user-message content are logged.
     */
    public ChatResponse handle(ChatRequest request) {
        long startNs = System.nanoTime();
        String llmStatus = "skipped";
        String fallbackReason = null;
        ResolveResult result;

        try {
            result = resolve(request);
            ResolutionOutcome outcome = result.outcome();
            if (outcome.source().startsWith("llm")) llmStatus = "ok";
        } catch (LlmResolutionException e) {
            llmStatus = e.llmStatus();
            fallbackReason = e.getReason();
            result = ResolveResult.of(fallback(request.message(), e.getReason()));
        }

        long latencyMs = (System.nanoTime() - startNs) / 1_000_000;
        logOutcome(result.outcome(), result.llmEntities(), llmStatus, latencyMs, fallbackReason);
        return responseBuilder.build(result.outcome());
    }

    // ── Resolution steps ──────────────────────────────────────────────────────────────────

    /**
     * Core resolution chain — deterministic fast-paths first, LLM last.
     * Returns a {@link ResolveResult} pairing the validated outcome with any entity labels
     * extracted from the LLM response (empty for all deterministic paths).
     */
    private ResolveResult resolve(ChatRequest request) {
        // Step 1: explicit ticker field — normalize then catalog-validate (Req 1.5, no Redis touch).
        // normalize() resolves crypto/forex stem forms (BTC→BTC-USD, USDCHF→USDCHF=X) and handles
        // case via its internal toUpperCase() path. The uppercase fallback catches plain equity
        // symbols typed in lowercase (aapl→AAPL). Unsupported after both attempts → clarification.
        if (request.ticker() != null && !request.ticker().isBlank()) {
            String t = request.ticker().trim();
            Optional<String> normalizedTicker = catalog.normalize(t);
            if (!normalizedTicker.isPresent()) {
                normalizedTicker = catalog.normalize(t.toUpperCase(Locale.ROOT));
            }
            if (normalizedTicker.isPresent()) {
                return ResolveResult.of(ResolutionOutcome.resolved(normalizedTicker.get(), "explicit"));
            }
            return ResolveResult.of(ResolutionOutcome.clarification(List.of(), "explicit",
                    "unsupported explicit ticker: " + t));
        }

        String message = request.message();
        if (message == null || message.isBlank()) {
            return ResolveResult.of(ResolutionOutcome.clarification(List.of(), "none", "empty message"));
        }

        // Step 2: deterministic preflight — normalize tokens + comparison guard
        List<String> preflightCandidates = extractNormalizedCandidates(message);
        boolean hasComparisonCue = hasComparisonCue(message);

        if (preflightCandidates.size() > 1) {
            return ResolveResult.of(ResolutionOutcome.comparisonRedirect(preflightCandidates, "preflight"));
        }
        if (preflightCandidates.size() == 1 && !hasComparisonCue) {
            return ResolveResult.of(ResolutionOutcome.resolved(preflightCandidates.get(0), "preflight"));
        }
        if (preflightCandidates.size() == 1) {
            // Single candidate but comparison cue present — clarify
            return ResolveResult.of(ResolutionOutcome.clarification(preflightCandidates, "preflight",
                    "comparison cue with single candidate"));
        }

        // Step 3: discovery shortcut
        if (isDiscoveryQuery(message)) {
            String category = extractCategoryFilter(message);
            return ResolveResult.of(ResolutionOutcome.discovery(category, "discovery-shortcut"));
        }

        // Step 4: single LLM call — capture entity labels for structured logging (Task 11)
        LlmResolution llm = resolutionClient.resolve(message, catalog.groundingView());
        List<String> llmEntities = llm.entities() != null ? llm.entities() : List.of();

        // Steps 5+6: validate + intent branching
        return new ResolveResult(handleLlmResolution(llm), llmEntities);
    }

    /** Fallback when LLM is unavailable/timeout/malformed — deterministic resolution only. */
    private ResolutionOutcome fallback(String message, String reason) {
        if (message == null || message.isBlank()) {
            return ResolutionOutcome.clarification(List.of(), "fallback-exact", reason);
        }
        List<String> candidates = extractNormalizedCandidates(message);
        boolean hasComparisonCue = hasComparisonCue(message);

        if (candidates.size() > 1) {
            return ResolutionOutcome.comparisonRedirect(candidates, "fallback-exact");
        }
        if (candidates.size() == 1 && !hasComparisonCue) {
            return ResolutionOutcome.resolved(candidates.get(0), "fallback-exact");
        }
        // No normalizable candidates or comparison cue — clarification
        return ResolutionOutcome.clarification(List.of(), "fallback-exact", reason);
    }

    // ── LLM result processing (Steps 5 + 6) ──────────────────────────────────────────────

    private ResolutionOutcome handleLlmResolution(LlmResolution llm) {
        Intent intent = llm.intent() != null ? llm.intent() : Intent.UNKNOWN;

        // Step 5: validate every proposed ticker against the catalog (drop invented symbols)
        List<String> validResolved   = validateTickers(llm.resolvedTickers());
        List<String> validCandidates = validateTickers(llm.candidateTickers());
        String validCategory         = validateCategory(llm.categoryFilter());

        // Step 6: intent branching
        return switch (intent) {
            case GREETING_HELP -> ResolutionOutcome.greetingHelp("llm");
            case DISCOVERY     -> ResolutionOutcome.discovery(validCategory, "llm");
            case COMPARISON    -> {
                List<String> all = merge(validResolved, validCandidates);
                yield all.isEmpty()
                        ? ResolutionOutcome.clarification(List.of(), "llm",
                                "comparison with no valid candidates")
                        : ResolutionOutcome.comparisonRedirect(all, "llm");
            }
            case ASSET_QUERY   -> handleAssetQuery(validResolved, validCandidates);
            default -> ResolutionOutcome.clarification(List.of(), "llm",
                    llm.clarificationReason());
        };
    }

    private ResolutionOutcome handleAssetQuery(List<String> resolved, List<String> candidates) {
        if (resolved.size() == 1) {
            return ResolutionOutcome.resolved(resolved.get(0), "llm");
        }
        if (resolved.size() > 1) {
            return ResolutionOutcome.comparisonRedirect(resolved, "llm");
        }
        // No validated resolved tickers
        List<String> all = merge(resolved, candidates);
        return all.isEmpty()
                ? ResolutionOutcome.clarification(List.of(), "llm",
                        "no valid tickers in LLM response")
                : ResolutionOutcome.clarification(all, "llm",
                        "ambiguous: multiple candidates");
    }

    // ── Preflight candidate extraction ────────────────────────────────────────────────────

    /**
     * Extracts normalized catalog symbols from the message using {@link TickerCatalogService#normalize}.
     * Handles $TICKER prefix and whitespace-delimited token normalization.
     * Does NOT resolve names/aliases — only symbol-form tokens (Property P8: preflight determinism).
     */
    List<String> extractNormalizedCandidates(String message) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();

        // $TICKER prefix (highest specificity)
        Matcher dollarMatcher = DOLLAR_PATTERN.matcher(message);
        while (dollarMatcher.find()) {
            catalog.normalize(dollarMatcher.group(1)).ifPresent(unique::add);
        }

        // Whitespace-tokenized normalization
        for (String raw : message.split("\\s+")) {
            String token = raw.replaceAll("^[?!,.'\"]+|[?!,.'\"]+$", "");
            if (!token.isBlank()) {
                catalog.normalize(token).ifPresent(unique::add);
            }
        }
        return new ArrayList<>(unique);
    }

    // ── Discovery detection ───────────────────────────────────────────────────────────────

    boolean isDiscoveryQuery(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (String phrase : DISCOVERY_TRIGGER_PHRASES) {
            if (lower.contains(phrase)) return true;
        }
        // Skip the word-combo check when a name-like entity is present.
        // "what is Apple stock?" should fall through to the LLM for AAPL resolution,
        // not return a DISCOVERY listing of US equities (Req 4.1 precision).
        if (containsEntityLikeToken(message)) return false;
        // listing word + asset class word combination (e.g. "show me all crypto")
        // Use HashSet to tolerate repeated words (Set.of() throws on duplicates)
        Set<String> tokens = new java.util.HashSet<>(List.of(lower.split("\\s+")));
        boolean hasListingWord = tokens.stream().anyMatch(DISCOVERY_LISTING_WORDS::contains);
        boolean hasAssetWord   = tokens.stream().anyMatch(ASSET_CLASS_WORDS::contains);
        return hasListingWord && hasAssetWord;
    }

    /**
     * Returns {@code true} if the message contains a mid-sentence capitalized token that is not
     * a known discovery or asset-class keyword, indicating a proper noun / entity name is present.
     *
     * <p>The first word of the message is always skipped (sentence-start capitalization).
     * Capitalized tokens that appear in {@link #DISCOVERY_LISTING_WORDS} or
     * {@link #ASSET_CLASS_WORDS} (e.g. the user wrote "What Stocks…") are not flagged.
     *
     * <p>Example: "what is Apple stock?" → "Apple" is mid-sentence, capitalized, and not a
     * known function word → method returns {@code true} → discovery shortcut skipped.
     */
    private boolean containsEntityLikeToken(String message) {
        String[] words = message.trim().split("\\s+");
        for (int i = 1; i < words.length; i++) {  // i=0 is always skipped (sentence-start)
            String cleaned = words[i].replaceAll("^[?!,.'\"]+|[?!,.'\"]+$", "");
            if (cleaned.isEmpty() || !Character.isUpperCase(cleaned.charAt(0))) continue;
            String lc = cleaned.toLowerCase(Locale.ROOT);
            if (!DISCOVERY_LISTING_WORDS.contains(lc) && !ASSET_CLASS_WORDS.contains(lc)) {
                return true;  // capitalized, non-function word → likely a proper noun / ticker
            }
        }
        return false;
    }

    /**
     * Extracts a category filter from the message. Priority: NSE > CRYPTO > FOREX > US_EQUITY.
     * Returns null (all categories) when no category keyword is present.
     */
    String extractCategoryFilter(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        // Use HashSet to tolerate repeated words (Set.of() throws on duplicates)
        Set<String> tokens = new java.util.HashSet<>(List.of(lower.split("\\s+")));
        // Check in priority order so "indian stocks" → NSE, not US_EQUITY
        if (tokens.contains("indian") || tokens.contains("nse") || tokens.contains("india")) {
            return "NSE";
        }
        if (tokens.contains("crypto") || tokens.contains("cryptocurrency")
                || tokens.contains("cryptocurrencies") || tokens.contains("bitcoin")) {
            return "CRYPTO";
        }
        if (tokens.contains("forex") || tokens.contains("currency")
                || tokens.contains("currencies") || tokens.contains("fx")) {
            return "FOREX";
        }
        if (tokens.contains("stock") || tokens.contains("stocks")
                || tokens.contains("equity") || tokens.contains("equities")) {
            return "US_EQUITY";
        }
        return null;
    }

    // ── Comparison cue detection ──────────────────────────────────────────────────────────

    boolean hasComparisonCue(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (String cue : COMPARISON_CUES) {
            // "vs." must be matched as a whole whitespace-delimited token to avoid false positives
            // on ticker suffixes like VS.NS whose lowercased form "vs.ns" contains the substring
            // "vs.". All other cues ("compare", "versus") are long enough to be safe as substrings.
            if (cue.equals("vs.")) {
                for (String token : lower.split("\\s+")) {
                    if (token.equals("vs.")) return true;
                }
            } else if (lower.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    // ── Catalog validation helpers ────────────────────────────────────────────────────────

    private List<String> validateTickers(List<String> proposed) {
        return proposed.stream()
                .filter(catalog::isSupported)
                .distinct()
                .toList();
    }

    private String validateCategory(String category) {
        if (category == null) return null;
        return Set.of("US_EQUITY", "NSE", "CRYPTO", "FOREX").contains(category)
                ? category : null;
    }

    private static List<String> merge(List<String> a, List<String> b) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return new ArrayList<>(merged);
    }

    // ── Structured logging (Task 11 / Req 9.1) ───────────────────────────────────────────

    /**
     * Emits one structured log entry per request (Task 11 / Req 9.1).
     *
     * <p>Fields logged: {@code intent}, {@code entities} (from LLM only; empty on deterministic
     * paths), {@code resolvedTickers}, {@code candidateTickers}, {@code source},
     * {@code fallbackReason}, {@code resolverLatencyMs}, {@code llmStatus}, {@code responsePath},
     * and {@code catalogVersion}.
     *
     * <p>Safety: no secrets, system prompts, raw catalog payloads, or full user-message content
     * are included. Entity labels come from the LLM's entity-extraction output (not the raw user
     * message). The fallback reason is a short diagnostic string from the resolution exception,
     * never containing user input verbatim.
     */
    private void logOutcome(ResolutionOutcome outcome, List<String> llmEntities,
                            String llmStatus, long latencyMs, String fallbackReason) {
        String intent = inferIntent(outcome.outcome());
        String resolvedTickers = outcome.ticker() != null ? outcome.ticker() : "-";
        // fallbackReason: prefer the caught-exception reason (passed in); fall back to outcome
        // detail which is populated by fallback() for the internal-fallback code path.
        String effectiveFallbackReason = fallbackReason != null ? fallbackReason
                : ("fallback-exact".equals(outcome.source()) && outcome.detail() != null
                   ? outcome.detail() : "-");

        log.info(
            "resolution.outcome intent={} entities={} resolvedTickers={} candidateTickers={} "
            + "source={} fallbackReason={} resolverLatencyMs={} llmStatus={} responsePath={} "
            + "catalogVersion={}",
            intent, llmEntities, resolvedTickers, outcome.candidates(),
            outcome.source(), effectiveFallbackReason, latencyMs, llmStatus,
            outcome.outcome().name(), catalog.catalogVersion());
    }

    /**
     * Infers a log-friendly intent label from the terminal resolution outcome type.
     *
     * <p>The intent for LLM paths is the LLM's declared intent, which is reflected in
     * the outcome type after validation. For deterministic paths (preflight, explicit,
     * discovery-shortcut, fallback-exact), the outcome type is sufficient to name the intent.
     */
    private static String inferIntent(Outcome outcome) {
        return switch (outcome) {
            case RESOLVED, NO_DATA        -> "ASSET_QUERY";
            case DISCOVERY               -> "DISCOVERY";
            case COMPARISON_REDIRECT     -> "COMPARISON";
            case GREETING_HELP           -> "GREETING_HELP";
            case CLARIFICATION           -> "UNKNOWN";
        };
    }
}
