/**
 * Shared TypeScript interfaces mirroring insight-service backend DTOs.
 * Keep in sync with: insight-service/src/main/java/com/wealth/insight/dto/
 *
 * Backend BigDecimal fields are serialised as JSON numbers by Jackson.
 */

// ── Market Summary ───────────────────────────────────────────────────────────

/**
 * Mirrors {@code com.wealth.insight.dto.TickerSummary}.
 *
 * @field ticker       asset ticker symbol (e.g. "AAPL")
 * @field latestPrice  most recent price (BigDecimal → number)
 * @field priceHistory last N price points, newest first (List<BigDecimal> → number[])
 * @field trendPercent percentage change oldest→newest, null when < 2 data points
 * @field aiSummary    2-sentence AI sentiment, null when AI unavailable
 */
export interface TickerSummary {
  ticker: string;
  latestPrice: number;
  priceHistory: number[];
  trendPercent: number | null;
  aiSummary: string | null;
}

/**
 * Response shape for GET /api/insights/market-summary.
 * The backend returns a Map<String, TickerSummary> which Jackson serialises
 * as a JSON object keyed by ticker symbol.
 */
export type MarketSummaryResponse = Record<string, TickerSummary>;

// ── Chat ─────────────────────────────────────────────────────────────────────

/**
 * Mirrors {@code com.wealth.insight.dto.ChatRequest}.
 *
 * @field message user's natural-language question (required)
 * @field ticker  explicit ticker symbol (optional — overrides extraction from message)
 */
export interface ChatRequest {
  message: string;
  ticker?: string;
}

/**
 * Mirrors {@code com.wealth.insight.dto.ChatResponse}.
 *
 * @field response conversational plain-text wrapping the insight data
 */
export interface ChatResponse {
  response: string;
}

// ── Client-side chat message (not a backend DTO) ─────────────────────────────

/**
 * Represents a single message in the chat conversation history.
 * Used by ChatInterface to track the local message list.
 */
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: Date;
}
