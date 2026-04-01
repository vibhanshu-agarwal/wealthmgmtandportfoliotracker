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
  all:         (id: string)             => ["portfolio", id]         as const,
  performance: (id: string, days: number) => ["portfolio", id, "performance", days] as const,
  allocation:  (id: string)             => ["portfolio", id, "allocation"]  as const,
  summary:     (userId: string)         => ["portfolio", "summary", userId] as const,
};

// ── Hooks ─────────────────────────────────────────────────────────────────────

/**
 * Full portfolio data: summary + holdings list.
 */
export function usePortfolio(portfolioId = "p-001") {
  return useQuery({
    queryKey: portfolioKeys.all(portfolioId),
    queryFn: () => fetchPortfolio(portfolioId),
    staleTime: 30_000,      // treat data as fresh for 30 s
    refetchInterval: 60_000, // background refetch every 60 s
  });
}

/**
 * Historical performance data for the area chart.
 * @param days Number of calendar days to fetch (default 30)
 */
export function usePortfolioPerformance(portfolioId = "p-001", days = 30) {
  return useQuery({
    queryKey: portfolioKeys.performance(portfolioId, days),
    queryFn: () => fetchPortfolioPerformance(portfolioId, days),
    staleTime: 60_000,
  });
}

/**
 * Asset-class allocation breakdown for the donut chart.
 */
export function useAssetAllocation(portfolioId = "p-001") {
  return useQuery({
    queryKey: portfolioKeys.allocation(portfolioId),
    queryFn: () => fetchAssetAllocation(portfolioId),
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
