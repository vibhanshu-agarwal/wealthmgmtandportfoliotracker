"use client";

import { useQuery } from "@tanstack/react-query";
import {
  fetchPortfolio,
  fetchPortfolioPerformance,
  fetchAssetAllocation,
} from "@/lib/api/portfolio";
import { fetchPortfolioSummary } from "@/lib/apiService";

// ── Query keys ────────────────────────────────────────────────────────────────
// Centralised here so invalidation is consistent across the app.

export const portfolioKeys = {
  all:         (userId: string)             => ["portfolio", userId]         as const,
  performance: (userId: string, days: number) => ["portfolio", userId, "performance", days] as const,
  allocation:  (userId: string)             => ["portfolio", userId, "allocation"]  as const,
  summary:     (userId: string)         => ["portfolio", "summary", userId] as const,
};

// ── Hooks ─────────────────────────────────────────────────────────────────────

/**
 * Full portfolio data: summary + holdings list.
 */
export function usePortfolio(userId = "user-001") {
  // TODO: Replace hard-coded fallback user with authenticated user context once auth is integrated.
  return useQuery({
    queryKey: portfolioKeys.all(userId),
    queryFn: () => fetchPortfolio(userId),
    staleTime: 30_000,      // treat data as fresh for 30 s
    refetchInterval: 60_000, // background refetch every 60 s
  });
}

/**
 * Historical performance data for the area chart.
 * @param days Number of calendar days to fetch (default 30)
 */
export function usePortfolioPerformance(userId = "user-001", days = 30) {
  return useQuery({
    queryKey: portfolioKeys.performance(userId, days),
    queryFn: () => fetchPortfolioPerformance(userId, days),
    staleTime: 60_000,
  });
}

/**
 * Asset-class allocation breakdown for the donut chart.
 */
export function useAssetAllocation(userId = "user-001") {
  return useQuery({
    queryKey: portfolioKeys.allocation(userId),
    queryFn: () => fetchAssetAllocation(userId),
    staleTime: 60_000,
  });
}

export function usePortfolioSummary(userId = "user-001") {
  return useQuery({
    queryKey: portfolioKeys.summary(userId),
    queryFn: () => fetchPortfolioSummary(userId),
    staleTime: 30_000,
    retry: 1,
  });
}
