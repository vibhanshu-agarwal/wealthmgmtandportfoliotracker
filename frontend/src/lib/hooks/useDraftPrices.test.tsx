import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import { useDraftPrices } from "./useDraftPrices";

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useDraftPrices (Task 1.10)", () => {
  it("fetches prices only for the given draft tickers", async () => {
    let requestedTickers: string | null = null;
    server.use(
      http.get("/api/market/prices", ({ request }) => {
        requestedTickers = new URL(request.url).searchParams.get("tickers");
        return HttpResponse.json([
          { ticker: "AAPL", currentPrice: 100, observedAt: "2026-01-01T00:00:00Z", priceUnavailable: false },
        ]);
      }),
    );

    const { result } = renderHook(() => useDraftPrices(["AAPL"], "token"), { wrapper });

    await waitFor(() => expect(result.current.data?.get("AAPL")?.currentPrice).toBe(100));
    expect(requestedTickers).toBe("AAPL");
  });

  it("does not fetch when there are no draft tickers", () => {
    let called = false;
    server.use(
      http.get("/api/market/prices", () => {
        called = true;
        return HttpResponse.json([]);
      }),
    );

    renderHook(() => useDraftPrices([], "token"), { wrapper });
    expect(called).toBe(false);
  });
});
