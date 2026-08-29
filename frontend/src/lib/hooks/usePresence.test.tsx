/**
 * B2 Task 1.15/Requirement 6 — presence, queried once on mount, fail-open on error
 * (GC.5): a Redis error or unavailability yields no banner, no delay, no failed
 * request for anything else.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import { usePresence } from "./usePresence";

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("usePresence", () => {
  it("reports another active session when the endpoint says so", async () => {
    server.use(
      http.get("/api/presence/demo", () => HttpResponse.json({ anotherSessionActive: true })),
    );

    const { result } = renderHook(() => usePresence("token", true), { wrapper });

    await waitFor(() => expect(result.current.anotherSessionActive).toBe(true));
  });

  it("fails open (false, no error surfaced) when the endpoint errors", async () => {
    server.use(http.get("/api/presence/demo", () => new HttpResponse(null, { status: 500 })));

    const { result } = renderHook(() => usePresence("token", true), { wrapper });

    await waitFor(() => expect(result.current.anotherSessionActive).toBe(false));
  });

  it("does not query when disabled", () => {
    let called = false;
    server.use(
      http.get("/api/presence/demo", () => {
        called = true;
        return HttpResponse.json({ anotherSessionActive: false });
      }),
    );

    renderHook(() => usePresence("token", false), { wrapper });
    expect(called).toBe(false);
  });
});
