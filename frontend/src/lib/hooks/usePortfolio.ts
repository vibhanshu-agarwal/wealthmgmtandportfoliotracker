"use client";

import { useQuery } from "@tanstack/react-query";
import {
  fetchPortfolio,
  fetchPortfolioAnalytics,
  buildPerformanceDtoFromPortfolio,
  buildAllocationDtoFromPortfolio,
} from "@/lib/api/portfolio";
import { fetchPortfolioSummary } from "@/lib/apiService";
import { useAuthenticatedUserId } from "@/lib/hooks/useAuthenticatedUserId";
import type { PortfolioPerformanceDTO, AssetAllocationDTO } from "@/types/portfolio";

// ── Query keys ────────────────────────────────────────────────────────────────
// Centralised here so invalidation is consistent across the app.
// Keys include userId so cache entries are scoped per authenticated user.

export const portfolioKeys = {
  all:       (userId: string)               => ["portfolio", userId]               as const,
  summary:   (userId: string)               => ["portfolio", "summary", userId]    as const,
  analytics: (userId: string)               => ["portfolio", userId, "analytics"]  as const,
};

// ── Shared retry policy ───────────────────────────────────────────────────────
// 429 (Lambda throttle) and 503 (service unavailable / Bedrock degraded) must
// NOT be retried automatically — retrying a throttled request immediately makes
// concurrency pressure worse. Other transient errors get one exponential retry.
const retryPolicy = (failureCount: number, error: unknown): boolean => {
  if (error instanceof Error) {
    const msg = error.message;
    // TanStack Query surfaces HTTP status codes in the error message via fetchWithAuth
    if (msg.includes("429") || msg.includes("503")) return false;
  }
  return failureCount < 1;
};

const retryDelay = (attempt: number) => Math.min(1000 * 2 ** attempt, 8000);

// ── Hooks ─────────────────────────────────────────────────────────────────────

/**
 * Full portfolio data: summary + holdings list.
 *
 * This is the single source of truth for portfolio + market-price data.
 * usePortfolioPerformance and useAssetAllocation derive from this cache entry
 * via `select` to avoid duplicate /api/portfolio + /api/market/prices backend
 * calls on each dashboard mount (was 3× fan-out, now 1×).
 */
export function usePortfolio() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.all(userId),
    queryFn: () => fetchPortfolio(userId, token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    refetchInterval: 60_000,
    retry: retryPolicy,
    retryDelay,
  });
}

/**
 * Historical performance data for the area chart.
 *
 * Derived client-side from the usePortfolio cache via `select` — no extra
 * backend calls. Falls back to a flat zero-point series when data is missing.
 *
 * @param days Number of calendar days (default 30)
 */
export function usePortfolioPerformance(days = 30) {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.all(userId),
    queryFn: () => fetchPortfolio(userId, token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    refetchInterval: 60_000,
    retry: retryPolicy,
    retryDelay,
    select: (portfolio): PortfolioPerformanceDTO =>
      buildPerformanceDtoFromPortfolio(portfolio, days),
  });
}

/**
 * Asset-class allocation breakdown for the donut chart.
 *
 * Derived client-side from the usePortfolio cache via `select` — no extra
 * backend calls.
 */
export function useAssetAllocation() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.all(userId),
    queryFn: () => fetchPortfolio(userId, token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    refetchInterval: 60_000,
    retry: retryPolicy,
    retryDelay,
    select: (portfolio): AssetAllocationDTO =>
      buildAllocationDtoFromPortfolio(portfolio),
  });
}

/**
 * Lightweight portfolio summary (total value, holdings count, etc.).
 */
export function usePortfolioSummary() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.summary(userId),
    queryFn: () => fetchPortfolioSummary(userId, token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    retry: retryPolicy,
    retryDelay,
  });
}

/**
 * Unified portfolio analytics: best/worst performers, unrealized P&L,
 * per-holding 24h change, and historical performance series.
 */
export function usePortfolioAnalytics() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.analytics(userId),
    queryFn: () => fetchPortfolioAnalytics(token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    refetchInterval: 60_000,
    retry: retryPolicy,
    retryDelay,
  });
}
