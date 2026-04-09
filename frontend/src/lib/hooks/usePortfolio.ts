"use client";

import { useQuery } from "@tanstack/react-query";
import {
  fetchPortfolio,
  fetchPortfolioPerformance,
  fetchAssetAllocation,
} from "@/lib/api/portfolio";
import { fetchPortfolioSummary } from "@/lib/apiService";
import { useAuthenticatedUserId } from "@/lib/hooks/useAuthenticatedUserId";

// ── Query keys ────────────────────────────────────────────────────────────────
// Centralised here so invalidation is consistent across the app.
// Keys include userId so cache entries are scoped per authenticated user.

export const portfolioKeys = {
  all:         (userId: string)               => ["portfolio", userId]                        as const,
  performance: (userId: string, days: number) => ["portfolio", userId, "performance", days]   as const,
  allocation:  (userId: string)               => ["portfolio", userId, "allocation"]           as const,
  summary:     (userId: string)               => ["portfolio", "summary", userId]              as const,
};

// ── Hooks ─────────────────────────────────────────────────────────────────────

/**
 * Full portfolio data: summary + holdings list.
 * Only fires when the user is authenticated.
 */
export function usePortfolio() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.all(userId),
    queryFn: () => fetchPortfolio(userId, token),
    enabled: status === "authenticated",
    staleTime: 30_000,
    refetchInterval: 60_000,
  });
}

/**
 * Historical performance data for the area chart.
 * @param days Number of calendar days to fetch (default 30)
 */
export function usePortfolioPerformance(days = 30) {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.performance(userId, days),
    queryFn: () => fetchPortfolioPerformance(userId, token, days),
    enabled: status === "authenticated",
    staleTime: 60_000,
  });
}

/**
 * Asset-class allocation breakdown for the donut chart.
 */
export function useAssetAllocation() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: portfolioKeys.allocation(userId),
    queryFn: () => fetchAssetAllocation(userId, token),
    enabled: status === "authenticated",
    staleTime: 60_000,
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
    enabled: status === "authenticated",
    staleTime: 30_000,
    retry: 1,
  });
}
