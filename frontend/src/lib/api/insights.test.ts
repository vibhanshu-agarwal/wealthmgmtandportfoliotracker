import { describe, it, expect } from "vitest";
import { fetchMarketSummary, fetchTickerSummary } from "./insights";
import { insightMarketSummaryFixture } from "@/test/msw/handlers";

const TEST_TOKEN = "test-bearer-token";

// ── Property 8: fetchMarketSummary returns correct structure ──────────────────

describe("fetchMarketSummary", () => {
  it("returns a MarketSummaryResponse matching the fixture shape", async () => {
    const result = await fetchMarketSummary(TEST_TOKEN);

    // Should contain all fixture tickers
    expect(Object.keys(result)).toEqual(
      expect.arrayContaining(["AAPL", "MSFT", "GOOG"]),
    );

    // Each entry should have the TickerSummary fields
    for (const [ticker, summary] of Object.entries(result)) {
      expect(summary).toHaveProperty("ticker", ticker);
      expect(typeof summary.latestPrice).toBe("number");
      expect(Array.isArray(summary.priceHistory)).toBe(true);
      // trendPercent can be number or null
      expect(
        summary.trendPercent === null || typeof summary.trendPercent === "number",
      ).toBe(true);
      // aiSummary can be string or null
      expect(
        summary.aiSummary === null || typeof summary.aiSummary === "string",
      ).toBe(true);
    }
  });

  it("returns values matching the fixture data", async () => {
    const result = await fetchMarketSummary(TEST_TOKEN);

    expect(result.AAPL.latestPrice).toBe(
      insightMarketSummaryFixture.AAPL.latestPrice,
    );
    expect(result.MSFT.aiSummary).toBeNull();
    expect(result.GOOG.trendPercent).toBeNull();
  });
});

describe("fetchTickerSummary", () => {
  it("returns a single TickerSummary for a known ticker", async () => {
    const result = await fetchTickerSummary("AAPL", TEST_TOKEN);

    expect(result.ticker).toBe("AAPL");
    expect(result.latestPrice).toBe(178.5);
    expect(result.priceHistory).toEqual([175.0, 176.2, 177.8, 178.5]);
    expect(result.trendPercent).toBe(2.0);
    expect(result.aiSummary).toBe("AAPL is Bullish. Prices are rising steadily.");
  });

  it("throws on unknown ticker (404)", async () => {
    await expect(fetchTickerSummary("ZZZZ", TEST_TOKEN)).rejects.toThrow(
      "Request failed (404)",
    );
  });
});
