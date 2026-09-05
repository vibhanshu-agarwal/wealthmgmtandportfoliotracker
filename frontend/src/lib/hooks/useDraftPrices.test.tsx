import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";
import { server } from "@/test/msw/server";
import { useDraftPrices } from "./useDraftPrices";

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

describe("useDraftPrices (Task 1.10 / Task 9.3)", () => {
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

  // Added coverage (already-working behavior): sorting/dedupe for the query key and request.
  it("sorts draft tickers before requesting so order changes reuse the same query", async () => {
    const requested: string[] = [];
    server.use(
      http.get("/api/market/prices", ({ request }) => {
        requested.push(new URL(request.url).searchParams.get("tickers") ?? "");
        const tickers = (new URL(request.url).searchParams.get("tickers") ?? "")
          .split(",")
          .filter(Boolean);
        return HttpResponse.json(
          tickers.map((ticker) => ({
            ticker,
            currentPrice: 10,
            observedAt: "2026-01-01T00:00:00Z",
            priceUnavailable: false,
          })),
        );
      }),
    );

    const { result, rerender } = renderHook(
      ({ tickers }: { tickers: string[] }) => useDraftPrices(tickers, "token"),
      { wrapper, initialProps: { tickers: ["TSLA", "AAPL"] } },
    );

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(requested).toEqual(["AAPL,TSLA"]);

    rerender({ tickers: ["AAPL", "TSLA"] });
    await act(async () => {
      await Promise.resolve();
    });
    // Same sorted set → no second network call.
    expect(requested).toEqual(["AAPL,TSLA"]);
  });

  // Added coverage: canonical punctuation must survive into the query string.
  it("preserves exchange-suffixed and FX tickers through the request URL", async () => {
    let requestedTickers: string | null = null;
    const draftTickers = ["BTC-USD", "BRK.B", "EURUSD=X"];
    server.use(
      http.get("/api/market/prices", ({ request }) => {
        requestedTickers = new URL(request.url).searchParams.get("tickers");
        return HttpResponse.json(
          draftTickers.map((ticker) => ({
            ticker,
            currentPrice: 1,
            observedAt: "2026-01-01T00:00:00Z",
            priceUnavailable: false,
          })),
        );
      }),
    );

    const { result } = renderHook(() => useDraftPrices(draftTickers, "token"), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const requested = (requestedTickers ?? "").split(",").sort();
    expect(requested).toEqual([...draftTickers].sort());
  });

  it("refetches when a ticker is added and stops when the draft becomes empty", async () => {
    const requested: string[] = [];
    server.use(
      http.get("/api/market/prices", ({ request }) => {
        requested.push(new URL(request.url).searchParams.get("tickers") ?? "");
        return HttpResponse.json([
          { ticker: "AAPL", currentPrice: 100, observedAt: "2026-01-01T00:00:00Z", priceUnavailable: false },
          { ticker: "GOOGL", currentPrice: 50, observedAt: "2026-01-01T00:00:00Z", priceUnavailable: false },
        ]);
      }),
    );

    const { result, rerender } = renderHook(
      ({ tickers }: { tickers: string[] }) => useDraftPrices(tickers, "token"),
      { wrapper, initialProps: { tickers: ["AAPL"] } },
    );
    await waitFor(() => expect(result.current.data?.get("AAPL")?.currentPrice).toBe(100));

    rerender({ tickers: ["AAPL", "GOOGL"] });
    await waitFor(() => expect(result.current.data?.get("GOOGL")?.currentPrice).toBe(50));
    expect(requested).toContain("AAPL,GOOGL");

    const callsBeforeEmpty = requested.length;
    rerender({ tickers: [] });
    await act(async () => {
      await Promise.resolve();
    });
    expect(requested.length).toBe(callsBeforeEmpty);
  });
});
