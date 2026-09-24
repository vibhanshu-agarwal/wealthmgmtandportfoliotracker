"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchFxRates } from "@/lib/api/fxRates";

/** Rates into the base currency for the given quote currencies (display-only estimates). */
export function useFxRates(currencies: string[], token: string) {
  const sorted = [...new Set(currencies)].sort();
  return useQuery({
    queryKey: ["asset-picker", "fx-rates", sorted],
    queryFn: () => fetchFxRates(sorted, token),
    enabled: sorted.length > 0,
    staleTime: 60_000,
    retry: false,
  });
}
