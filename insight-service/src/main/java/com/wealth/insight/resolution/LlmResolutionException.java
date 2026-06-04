package com.wealth.insight.resolution;

/**
 * Typed exception thrown by {@link AssetResolutionClient} implementations when the LLM
 * call fails, times out, or returns malformed/empty output (Task 6 / Req 6.1, 6.6).
 *
 * <p>{@code ChatResolutionService} catches this exception and falls back to the
 * deterministic resolution path ({@code source="fallback-exact"}), logging the
 * {@link #getReason()} for observability (Req 9.1).
 *
 * <p>Three sub-conditions are distinguished via {@link Kind}:
 * <ul>
 *   <li>{@link Kind#UNAVAILABLE} — the LLM endpoint was unreachable or returned an HTTP
 *       error (e.g. 503, connection refused).</li>
 *   <li>{@link Kind#TIMEOUT} — the request exceeded the configured timeout.</li>
 *   <li>{@link Kind#MALFORMED} — the LLM responded but the output could not be parsed
 *       into a valid {@link LlmResolution} (e.g. invalid JSON, wrong schema).</li>
 * </ul>
 */
public class LlmResolutionException extends RuntimeException {

    /** Distinguishes the root cause category for structured logging. */
    public enum Kind { UNAVAILABLE, TIMEOUT, MALFORMED }

    private final Kind kind;
    private final String reason;

    public LlmResolutionException(Kind kind, String reason) {
        super("[LLM-" + kind + "] " + reason);
        this.kind = kind;
        this.reason = reason;
    }

    public LlmResolutionException(Kind kind, String reason, Throwable cause) {
        super("[LLM-" + kind + "] " + reason, cause);
        this.kind = kind;
        this.reason = reason;
    }

    /** The category of failure — used in structured logs as {@code llmStatus}. */
    public Kind getKind() {
        return kind;
    }

    /**
     * Short description of the failure cause — safe to log, must never contain
     * secrets, user data, or full LLM output.
     */
    public String getReason() {
        return reason;
    }

    /** Returns {@code "unavailable"}, {@code "timeout"}, or {@code "malformed"}. */
    public String llmStatus() {
        return kind.name().toLowerCase();
    }
}
