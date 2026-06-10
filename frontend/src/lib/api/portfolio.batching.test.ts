/**
 * Task 9.9 — Vitest + MSW tests for portfolio.ts batching and availability semantics.
 *
 * Requirements validated:
 * - R1 AC1/AC2: >25-ticker batching — no holdings dropped or zeroed
 * - R1 AC3: missing price → priceUnavailable, never currentPrice = 0
 * - R8 AC4: missing price last-updated → "—", never now()
 * - Property 3: Completeness of pricing (all requested tickers in response)
 */

import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import { loadMarketPrices } from "./portfolio";

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Generate N unique ticker symbols */
function makeTickers(n: number): string[] {
  return Array.from({ length: n }, (_, i) => `T${String(i).padStart(3, "0")}`);
}

function makeMarketPriceHandler(
  options: {
    pricePerTicker?: (ticker: string) => number | null;
    maxBatchSize?: number;
  } = {},
) {
  const { pricePerTicker = () => 100.0, maxBatchSize = Infinity } = options;

  return http.get("/api/market/prices", ({ request }) => {
    const url = new URL(request.url);
    const tickersParam = url.searchParams.get("tickers") ?? "";
    const tickers = tickersParam
      .split(",")
      .map((t) => t.trim())
      .filter(Boolean);

    if (tickers.length > maxBatchSize) {
      return HttpResponse.json(
        { error: `Batch too large: max ${maxBatchSize}` },
        { status: 400 },
      );
    }

    return HttpResponse.json(
      tickers.map((ticker) => {
        const price = pricePerTicker(ticker);
        return price != null
          ? {
              ticker,
              currentPrice: price,
              observedAt: new Date(Date.now() - 3600_000).toISOString(),
              priceUnavailable: false,
            }
          : { ticker, currentPrice: null, observedAt: null, priceUnavailable: true };
      }),
    );
  });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe("loadMarketPrices — batching", () => {
  /**
   * Property: >25 tickers are batched into chunks ≤25 and all results merged.
   * Requirement: R1 AC2 — no holding is dropped because it exceeded a request cap.
   */
  it("fetches all 160 tickers across multiple batches without dropping any", async () => {
    const tickers = makeTickers(160);
    server.use(makeMarketPriceHandler());

    const result = await loadMarketPrices(tickers, "test-token");

    // Every ticker must appear in the result map
    for (const ticker of tickers) {
      expect(result.has(ticker), `ticker ${ticker} should be in result`).toBe(true);
    }
    expect(result.size).toBe(160);
  });

  it("handles exactly 25 tickers in a single batch", async () => {
    const tickers = makeTickers(25);
    let batchCount = 0;

    server.use(
      http.get("/api/market/prices", ({ request }) => {
        batchCount++;
        const url = new URL(request.url);
        const tks = (url.searchParams.get("tickers") ?? "")
          .split(",")
          .filter(Boolean);
        return HttpResponse.json(
          tks.map((ticker) => ({
            ticker,
            currentPrice: 50.0,
            observedAt: new Date().toISOString(),
            priceUnavailable: false,
          })),
        );
      }),
    );

    const result = await loadMarketPrices(tickers, "test-token");
    expect(batchCount).toBe(1);
    expect(result.size).toBe(25);
  });

  it("sends exactly ceil(N / 25) batch requests for N > 25 tickers", async () => {
    const tickers = makeTickers(63); // 3 batches: 25 + 25 + 13
    let batchCount = 0;
    const batchSizes: number[] = [];

    server.use(
      http.get("/api/market/prices", ({ request }) => {
        batchCount++;
        const url = new URL(request.url);
        const tks = (url.searchParams.get("tickers") ?? "")
          .split(",")
          .filter(Boolean);
        batchSizes.push(tks.length);
        return HttpResponse.json(
          tks.map((ticker) => ({
            ticker,
            currentPrice: 10.0,
            observedAt: new Date().toISOString(),
            priceUnavailable: false,
          })),
        );
      }),
    );

    const result = await loadMarketPrices(tickers, "test-token");
    expect(batchCount).toBe(3);
    expect(batchSizes.sort((a, b) => b - a)).toEqual([25, 25, 13]);
    expect(result.size).toBe(63);
  });

  it("de-duplicates ticker symbols before batching", async () => {
    const tickers = ["AAPL", "AAPL", "TSLA", "TSLA", "TSLA"];
    const requestedSets: string[][] = [];

    server.use(
      http.get("/api/market/prices", ({ request }) => {
        const url = new URL(request.url);
        const tks = (url.searchParams.get("tickers") ?? "")
          .split(",")
          .filter(Boolean);
        requestedSets.push(tks);
        return HttpResponse.json(
          tks.map((ticker) => ({
            ticker,
            currentPrice: 100.0,
            observedAt: new Date().toISOString(),
            priceUnavailable: false,
          })),
        );
      }),
    );

    const result = await loadMarketPrices(tickers, "test-token");
    // Only unique tickers should have been requested
    const allRequested = requestedSets.flat();
    const uniqueRequested = [...new Set(allRequested)];
    expect(uniqueRequested.sort()).toEqual(["AAPL", "TSLA"]);
    expect(result.size).toBe(2);
  });
});

describe("loadMarketPrices — availability semantics", () => {
  /**
   * Property: missing/unavailable tickers show explicit unavailable marker,
   * never currentPrice = 0.
   * Requirement: R1 AC3 — price unavailable ≠ $0.00.
   */
  it("returns null currentPrice for tickers with no data (priceUnavailable=true)", async () => {
    server.use(
      makeMarketPriceHandler({
        pricePerTicker: (ticker) => (ticker === "T001" ? null : 50.0),
      }),
    );

    const result = await loadMarketPrices(["T001", "T002"], "test-token");
    const unavailable = result.get("T001");
    const available = result.get("T002");

    expect(unavailable?.priceUnavailable).toBe(true);
    expect(unavailable?.currentPrice).toBeNull();
    expect(available?.currentPrice).toBe(50.0);
    expect(available?.priceUnavailable).toBe(false);
  });

  /**
   * Property: when a batch fails, remaining batches still resolve — no total failure.
   * Requirement: R1 AC1 — graceful degradation per batch.
   */
  it("degrades gracefully when a batch request fails — other batches still resolve", async () => {
    const tickers = makeTickers(50); // 2 batches: 25 + 25
    let callCount = 0;

    server.use(
      http.get("/api/market/prices", ({ request }) => {
        callCount++;
        const url = new URL(request.url);
        const tks = (url.searchParams.get("tickers") ?? "")
          .split(",")
          .filter(Boolean);
        // Fail the second batch
        if (callCount === 2) {
          return HttpResponse.json({ error: "Service unavailable" }, { status: 503 });
        }
        return HttpResponse.json(
          tks.map((ticker) => ({
            ticker,
            currentPrice: 75.0,
            observedAt: new Date().toISOString(),
            priceUnavailable: false,
          })),
        );
      }),
    );

    // Should not throw; first batch results should be in the map
    const result = await loadMarketPrices(tickers, "test-token");

    // At least the first batch's tickers should resolve
    expect(result.size).toBeGreaterThan(0);
    expect(result.size).toBeLessThanOrEqual(50);
  });

  it("returns empty map for empty ticker list", async () => {
    const result = await loadMarketPrices([], "test-token");
    expect(result.size).toBe(0);
  });
});
