"use client";

import { useQuery } from "@tanstack/react-query";
import { apiPath } from "@/lib/config/api";

/**
 * B2 Task 1.15/design.md D4 — queries `GET /api/presence/demo` once, per mount.
 * `staleTime: Infinity` plus no `refetchInterval` gives the "queried once on open"
 * behavior requirements.md 6.3 requires — no polling, no acquire/release semantics.
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

export function usePresence(token: string, enabled: boolean): { anotherSessionActive: boolean } {
  const query = useQuery({
    queryKey: ["asset-picker", "presence"],
    queryFn: () => fetchPresence(token),
    enabled,
    staleTime: Infinity,
    retry: false,
  });
  return { anotherSessionActive: query.data?.anotherSessionActive ?? false };
}
