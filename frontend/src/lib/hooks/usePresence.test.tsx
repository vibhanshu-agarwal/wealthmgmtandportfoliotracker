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

    const { result } = renderHook(() => usePresence("token", true, 1), { wrapper });

    await waitFor(() => expect(result.current.anotherSessionActive).toBe(true));
  });

  it("fails open (false, no error surfaced) when the endpoint errors", async () => {
    server.use(http.get("/api/presence/demo", () => new HttpResponse(null, { status: 500 })));

    const { result } = renderHook(() => usePresence("token", true, 1), { wrapper });

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

    renderHook(() => usePresence("token", false, 1), { wrapper });
    expect(called).toBe(false);
  });
});

describe("usePresence — per-open, not per-mount (review-fix)", () => {
  it("refetches when the caller's openKey changes, even though the component never unmounts", async () => {
    let callCount = 0;
    server.use(
      http.get("/api/presence/demo", () => {
        callCount += 1;
        return HttpResponse.json({ anotherSessionActive: false });
      }),
    );

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrap = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );

    const { rerender } = renderHook(({ openKey }) => usePresence("token", true, openKey), {
      wrapper: wrap,
      initialProps: { openKey: 1 },
    });

    await waitFor(() => expect(callCount).toBe(1));

    // Same open session (openKey unchanged) — re-rendering must NOT refetch.
    rerender({ openKey: 1 });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(callCount).toBe(1);

    // A new open session (modal closed and reopened) — must query again.
    rerender({ openKey: 2 });
    await waitFor(() => expect(callCount).toBe(2));
  });
});
