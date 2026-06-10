/**
 * Wave 6 Task 10.1 — End-to-end verification of the dashboard data accuracy pipeline.
 *
 * Validates the frontend contract layer against a realistic backend analytics payload:
 * enriched event semantics (change/P&L/coverage) → analytics DTO → allocation reconciliation.
 *
 * Requirements validated:
 * - R1 AC4: Total = Σ holdings = allocation total (Property 1)
 * - R2 AC1/R3 AC1: backend-sourced change and P&L consumed without client-side mixing
 * - R10 AC6: nullable/unavailable fields preserved (not coerced to 0)
 */

import { describe, it, expect } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/msw/server";
import {
  buildAllocationDtoFromAnalytics,
  fetchPortfolioAnalytics,
} from "./portfolio";
import type { PortfolioAnalyticsDTO } from "@/types/portfolio";

// ── Realistic backend analytics fixture (mirrors PortfolioAnalyticsDto contract) ──

function makeBackendAnalyticsFixture(): PortfolioAnalyticsDTO {
  return {
    totalValue: 48128.75,
    totalCostBasis: 44000.0,
    totalUnrealizedPnL: 4128.75,
    totalUnrealizedPnLPercent: 9.38,
    baseCurrency: "USD",
    partialValuation: false,
    bestPerformer: { ticker: "AAPL", change24hPercent: 5.26 },
    worstPerformer: { ticker: "BTC-USD", change24hPercent: -2.14 },
    holdings: [
      {
        ticker: "AAPL",
        quantity: 10,
        currentPrice: 212.5,
        currentValueBase: 2125.0,
        avgCostBasis: 190.0,
        costBasisCurrency: "USD",
        unrealizedPnL: 225.0,
        unrealizedPnLPercent: 11.84,
        change24hAbsolute: 10.6,
        change24hPercent: 5.26,
        change24hReferenceAt: new Date(Date.now() - 24 * 3600_000).toISOString(),
        changeBasis: "WITHIN_24H_WINDOW",
        quoteCurrency: "USD",
        displayAssetClass: "STOCK",
      },
      {
        ticker: "BTC-USD",
        quantity: 0.65,
        currentPrice: 70775.0,
        currentValueBase: 46003.75,
        avgCostBasis: 64000.0,
        costBasisCurrency: "USD",
        unrealizedPnL: 4003.75,
        unrealizedPnLPercent: 9.38,
        change24hAbsolute: -1543.5,
        change24hPercent: -2.14,
        change24hReferenceAt: new Date(Date.now() - 24 * 3600_000).toISOString(),
        changeBasis: "WITHIN_24H_WINDOW",
        quoteCurrency: "USD",
        displayAssetClass: "CRYPTO",
      },
    ],
    performanceSeries: [
      { date: "2026-06-03", value: 44000.0, change: 0 },
      { date: "2026-06-04", value: 45200.0, change: 1200 },
      { date: "2026-06-05", value: 46500.0, change: 1300 },
      { date: "2026-06-06", value: 47100.0, change: 600 },
      { date: "2026-06-07", value: 47800.0, change: 700 },
      { date: "2026-06-08", value: 47950.0, change: 150 },
      { date: "2026-06-09", value: 48128.75, change: 178.75 },
    ],
    performanceCoverage: {
      holdingsWithHistory: 2,
      totalHoldings: 2,
      partial: false,
      synthetic: false,
    },
  };
}

// ── Wave 6 end-to-end contract verification ───────────────────────────────────

describe("Wave 6 — dashboard data accuracy end-to-end contract", () => {
  it("fetchPortfolioAnalytics returns backend analytics with change and P&L fields", async () => {
    const fixture = makeBackendAnalyticsFixture();
    server.use(
      http.get("/api/portfolio/analytics", () => HttpResponse.json(fixture)),
    );

    const analytics = await fetchPortfolioAnalytics("test-token");

    expect(analytics.totalValue).toBe(fixture.totalValue);
    expect(analytics.totalUnrealizedPnL).toBe(fixture.totalUnrealizedPnL);
    expect(analytics.holdings).toHaveLength(2);

    const aapl = analytics.holdings.find((h) => h.ticker === "AAPL");
    expect(aapl?.unrealizedPnL).toBe(225.0);
    expect(aapl?.change24hPercent).toBe(5.26);
    expect(aapl?.changeBasis).toBe("WITHIN_24H_WINDOW");
    expect(aapl?.displayAssetClass).toBe("STOCK");

    expect(analytics.performanceCoverage.partial).toBe(false);
    expect(analytics.performanceCoverage.synthetic).toBe(false);
  });

  it("analytics holdings sum to totalValue (Property 1: total reconciliation)", async () => {
    const fixture = makeBackendAnalyticsFixture();
    server.use(
      http.get("/api/portfolio/analytics", () => HttpResponse.json(fixture)),
    );

    const analytics = await fetchPortfolioAnalytics("test-token");
    const holdingSum = analytics.holdings.reduce(
      (sum, h) => sum + h.currentValueBase,
      0,
    );

    expect(holdingSum).toBeCloseTo(analytics.totalValue, 2);
  });

  it("P&L identity holds when cost basis is present (Property 2)", async () => {
    const fixture = makeBackendAnalyticsFixture();
    server.use(
      http.get("/api/portfolio/analytics", () => HttpResponse.json(fixture)),
    );

    const analytics = await fetchPortfolioAnalytics("test-token");

    expect(analytics.totalUnrealizedPnL).not.toBeNull();
    const expectedPnL = analytics.totalValue - analytics.totalCostBasis;
    expect(analytics.totalUnrealizedPnL).toBeCloseTo(expectedPnL, 2);
  });

  it("allocation derived from analytics reconciles with portfolio total (Requirement 1.4)", async () => {
    const fixture = makeBackendAnalyticsFixture();
    server.use(
      http.get("/api/portfolio/analytics", () => HttpResponse.json(fixture)),
    );

    const analytics = await fetchPortfolioAnalytics("test-token");
    const allocation = buildAllocationDtoFromAnalytics(analytics, "portfolio-wave6");

    expect(allocation.totalValue).toBe(analytics.totalValue);

    const sliceSum = allocation.slices.reduce((sum, s) => sum + s.value, 0);
    expect(sliceSum).toBeCloseTo(analytics.totalValue, 2);

    const totalPercent = allocation.slices.reduce((sum, s) => sum + s.percentage, 0);
    expect(totalPercent).toBeCloseTo(100, 1);
  });

  it("nullable change fields are preserved — not coerced to 0 (Requirement 10.6)", async () => {
    const fixture = makeBackendAnalyticsFixture();
    const noChangeHolding = {
      ticker: "STALE",
      quantity: 5,
      currentPrice: 50.0,
      currentValueBase: 250.0,
      avgCostBasis: 48.0,
      costBasisCurrency: "USD",
      unrealizedPnL: 10.0,
      unrealizedPnLPercent: 4.17,
      change24hAbsolute: null,
      change24hPercent: null,
      change24hReferenceAt: null,
      changeBasis: null,
      quoteCurrency: "USD",
      displayAssetClass: "OTHER",
    };

    server.use(
      http.get("/api/portfolio/analytics", () =>
        HttpResponse.json({
          ...fixture,
          holdings: [...fixture.holdings, noChangeHolding],
          totalValue: fixture.totalValue + 250.0,
        }),
      ),
    );

    const analytics = await fetchPortfolioAnalytics("test-token");
    const stale = analytics.holdings.find((h) => h.ticker === "STALE");

    expect(stale?.change24hPercent).toBeNull();
    expect(stale?.change24hAbsolute).toBeNull();
    expect(stale?.changeBasis).toBeNull();
    expect(stale?.displayAssetClass).toBe("OTHER");
  });
});
