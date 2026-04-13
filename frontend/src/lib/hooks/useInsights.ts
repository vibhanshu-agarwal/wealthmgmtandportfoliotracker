"use client";

import { useQuery } from "@tanstack/react-query";
import {
  fetchMarketSummary,
  fetchTickerSummary,
} from "@/lib/api/insights";
import { useAuthenticatedUserId } from "@/lib/hooks/useAuthenticatedUserId";

// ── Query keys ────────────────────────────────────────────────────────────────
// Centralised here so invalidation is consistent across the app.
// Keys include userId so cache entries are scoped per authenticated user.

export const insightKeys = {
  marketSummary: (userId: string) =>
    ["insights", userId, "market-summary"] as const,
  tickerSummary: (userId: string, ticker: string) =>
    ["insights", userId, "ticker", ticker] as const,
};

// ── Hooks ─────────────────────────────────────────────────────────────────────

/**
 * Fetches the full market summary map (all tracked tickers).
 * Polls every 60s with a 30s stale window — same cadence as usePortfolio.
 */
export function useMarketSummary() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: insightKeys.marketSummary(userId),
    queryFn: () => fetchMarketSummary(token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    refetchInterval: 60_000,
  });
}

/**
 * Fetches a single ticker's summary.
 * Enabled only when the user is authenticated and a non-empty ticker is provided.
 */
export function useTickerSummary(ticker: string) {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: insightKeys.tickerSummary(userId, ticker),
    queryFn: () => fetchTickerSummary(ticker, token),
    enabled: status === "authenticated" && !!token && ticker.length > 0,
    staleTime: 30_000,
  });
}
