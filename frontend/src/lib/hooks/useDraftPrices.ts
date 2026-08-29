"use client";

import { useQuery } from "@tanstack/react-query";
import { loadMarketPrices } from "@/lib/api/portfolio";

/**
 * B2 Task 1.10 — prices only for the draft's current tickers, never the full browse
 * list (requirements.md 3.1). Reuses the existing `/api/market/prices` batching in
 * `loadMarketPrices` rather than duplicating it.
 */
export function useDraftPrices(tickers: string[], token: string) {
  const sortedTickers = [...tickers].sort();
  return useQuery({
    queryKey: ["asset-picker", "prices", sortedTickers],
    queryFn: () => loadMarketPrices(sortedTickers, token),
    enabled: sortedTickers.length > 0,
    staleTime: 15_000,
  });
}
