package com.wealth.insight.resolution;

import java.util.List;

/**
 * Catalog-validated, trusted resolution result produced by {@code ChatResolutionService}
 * after applying all deterministic fast-path and LLM-proposal validation steps.
 *
 * <p>All tickers in this record have been confirmed against the catalog —
 * no LLM-invented symbol can appear here (design Property 1).
 * {@code ChatResponseBuilder} consumes this record to produce the final user-facing text.
 *
 * @param outcome        the terminal resolution result type (never null)
 * @param ticker         the single canonical ticker for {@link Outcome#RESOLVED} and
 *                       {@link Outcome#NO_DATA} outcomes; null for all others
 * @param candidates     catalog-validated candidate tickers for
 *                       {@link Outcome#CLARIFICATION} and
 *                       {@link Outcome#COMPARISON_REDIRECT} outcomes; empty otherwise
 * @param categoryFilter optional asset-class filter surfaced for
 *                       {@link Outcome#DISCOVERY} outcomes (e.g. {@code "CRYPTO"});
 *                       null means all categories
 * @param source         resolution path label for structured logging
 *                       (e.g., {@code "explicit"}, {@code "preflight"}, {@code "llm"},
 *                       {@code "discovery-shortcut"}, {@code "fallback-exact"})
 * @param detail         optional free-text detail for logs (e.g. fallback reason,
 *                       normalisation applied); never shown to the user verbatim
 */
public record ResolutionOutcome(
        Outcome outcome,
        String ticker,
        List<String> candidates,
        String categoryFilter,
        String source,
        String detail
) {
    /**
     * Compact constructor that validates invariants and defensively copies the
     * candidates list.
     */
    public ResolutionOutcome {
        if (outcome == null) {
            throw new IllegalArgumentException("ResolutionOutcome.outcome must not be null");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("ResolutionOutcome.source must not be blank");
        }
        candidates = (candidates == null) ? List.of() : List.copyOf(candidates);
    }

    // -------------------------------------------------------------------------
    // Factory helpers — keep construction sites readable
    // -------------------------------------------------------------------------

    /** Creates a RESOLVED outcome for a single confirmed ticker. */
    public static ResolutionOutcome resolved(String ticker, String source) {
        return new ResolutionOutcome(Outcome.RESOLVED, ticker, List.of(), null, source, null);
    }

    /** Creates a NO_DATA outcome when the ticker is known but Redis has no price. */
    public static ResolutionOutcome noData(String ticker, String source) {
        return new ResolutionOutcome(Outcome.NO_DATA, ticker, List.of(), null, source, null);
    }

    /** Creates a CLARIFICATION outcome with an optional candidate list and detail. */
    public static ResolutionOutcome clarification(List<String> candidates, String source, String detail) {
        return new ResolutionOutcome(Outcome.CLARIFICATION, null, candidates, null, source, detail);
    }

    /** Creates a DISCOVERY outcome, optionally scoped to a category. */
    public static ResolutionOutcome discovery(String categoryFilter, String source) {
        return new ResolutionOutcome(Outcome.DISCOVERY, null, List.of(), categoryFilter, source, null);
    }

    /** Creates a COMPARISON_REDIRECT outcome with the validated candidate list. */
    public static ResolutionOutcome comparisonRedirect(List<String> candidates, String source) {
        return new ResolutionOutcome(Outcome.COMPARISON_REDIRECT, null, candidates, null, source, null);
    }

    /** Creates a GREETING_HELP outcome. */
    public static ResolutionOutcome greetingHelp(String source) {
        return new ResolutionOutcome(Outcome.GREETING_HELP, null, List.of(), null, source, null);
    }
}
