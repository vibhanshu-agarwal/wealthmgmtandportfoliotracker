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

// ── Shared retry policy ───────────────────────────────────────────────────────
// 429 (Lambda throttle) and 503 (service unavailable / Bedrock degraded) must
// NOT be retried automatically — retrying a throttled request makes concurrency
// pressure worse. Other transient errors get one exponential retry.
const retryPolicy = (failureCount: number, error: unknown): boolean => {
  if (error instanceof Error) {
    const msg = error.message;
    if (msg.includes("429") || msg.includes("503")) return false;
  }
  return failureCount < 1;
};

const retryDelay = (attempt: number) => Math.min(1000 * 2 ** attempt, 8000);

// ── Hooks ─────────────────────────────────────────────────────────────────────

/**
 * Fetches the full market summary map (all tracked tickers, price/trend only).
 * Polls every 60s with a 30s stale window.
 *
 * Note: AI sentiment is not included in the list response (to prevent an
 * unbounded Bedrock fan-out that would exceed Lambda timeouts). Use
 * useTickerSummary for AI-enriched data on a specific ticker.
 */
export function useMarketSummary() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: insightKeys.marketSummary(userId),
    queryFn: () => fetchMarketSummary(token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    refetchInterval: 60_000,
    retry: retryPolicy,
    retryDelay,
  });
}

/**
 * Fetches a single ticker's summary with AI sentiment (via Bedrock, Redis-cached 60 min).
 * Enabled only when the user is authenticated and a non-empty ticker is provided.
 */
export function useTickerSummary(ticker: string) {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: insightKeys.tickerSummary(userId, ticker),
    queryFn: () => fetchTickerSummary(ticker, token),
    enabled: status === "authenticated" && !!token && ticker.length > 0,
    staleTime: 30_000,
    retry: retryPolicy,
    retryDelay,
  });
}
