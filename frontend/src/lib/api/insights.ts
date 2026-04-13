import { fetchWithAuthClient } from "@/lib/api/fetchWithAuth";
import type { MarketSummaryResponse, TickerSummary } from "@/types/insights";

/**
 * Fetches the full market summary map from the insight-service.
 * GET /api/insights/market-summary → Record<string, TickerSummary>
 */
export async function fetchMarketSummary(
  token: string,
): Promise<MarketSummaryResponse> {
  return fetchWithAuthClient<MarketSummaryResponse>(
    "/api/insights/market-summary",
    token,
  );
}

/**
 * Fetches a single ticker's summary from the insight-service.
 * GET /api/insights/market-summary/{ticker} → TickerSummary
 */
export async function fetchTickerSummary(
  ticker: string,
  token: string,
): Promise<TickerSummary> {
  return fetchWithAuthClient<TickerSummary>(
    `/api/insights/market-summary/${encodeURIComponent(ticker)}`,
    token,
  );
}
