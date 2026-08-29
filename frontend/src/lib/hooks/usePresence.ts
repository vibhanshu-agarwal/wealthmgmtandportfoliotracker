"use client";

import { useQuery } from "@tanstack/react-query";
import { apiPath } from "@/lib/config/api";

/**
 * B2 Task 1.15/design.md D4 — queries `GET /api/presence/demo` once per open.
 *
 * `AssetPicker` is mounted once by `EditHoldingsButton` and stays mounted across
 * repeated open/close cycles (only its `open` prop toggles) — this hook by itself
 * cannot tell one open session from the next. `openKey` is the caller's own
 * per-open-session identity (AssetPicker's `openGeneration` counter); it is part of
 * the query key precisely so a second open queries again rather than serving the
 * first open's cached (`staleTime: Infinity`) result forever. No `refetchInterval` —
 * no polling, no acquire/release semantics, matching requirements.md 6.3.
 */
async function fetchPresence(token: string): Promise<{ anotherSessionActive: boolean }> {
  const response = await fetch(apiPath("/presence/demo"), {
    method: "GET",
    headers: { Authorization: `Bearer ${token}` },
    cache: "no-store",
  });
  if (!response.ok) {
    // GC.5 / requirements.md 6.5: fail open, never surface a presence error.
    return { anotherSessionActive: false };
  }
  return (await response.json()) as { anotherSessionActive: boolean };
}

export function usePresence(
  token: string,
  enabled: boolean,
  openKey: number,
): { anotherSessionActive: boolean } {
  const query = useQuery({
    queryKey: ["asset-picker", "presence", openKey],
    queryFn: () => fetchPresence(token),
    enabled,
    staleTime: Infinity,
    retry: false,
  });
  return { anotherSessionActive: query.data?.anotherSessionActive ?? false };
}
