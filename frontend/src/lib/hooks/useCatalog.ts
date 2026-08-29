"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchCatalog } from "@/lib/api/assetPicker";

/**
 * B2 Task 1.7 — fetches the asset catalog for the picker's Browse step.
 *
 * Gated by `enabled` so a closed picker never fetches the ~160-asset catalog;
 * `EditHoldingsButton` passes `open` through here.
 */
export function useCatalog(token: string, enabled: boolean) {
  return useQuery({
    queryKey: ["asset-picker", "catalog"],
    queryFn: () => fetchCatalog(token),
    enabled,
    staleTime: 60_000,
  });
}
