/**
 * Task 9.9 — Allocation reconciliation and backend asset-class mapping tests.
 *
 * Requirements validated:
 * - R4 AC1: allocation uses backend canonical asset class
 * - R4 AC2: percentages from FX-converted values, sum to ~100%
 * - R4 AC3: unknown → "Other" bucket
 * - Property 1: Total reconciliation — allocation total = portfolio total
 */

import { describe, it, expect } from "vitest";
import {
  buildAllocationDtoFromAnalytics,
  buildAllocationDtoFromPortfolio,
} from "./portfolio";
import type { PortfolioAnalyticsDTO, PortfolioResponseDTO } from "@/types/portfolio";

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeSampleAnalytics(overrides?: Partial<PortfolioAnalyticsDTO>): PortfolioAnalyticsDTO {
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
        change24hReferenceAt: null,
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
        change24hReferenceAt: null,
        changeBasis: "WITHIN_24H_WINDOW",
        quoteCurrency: "USD",
        displayAssetClass: "CRYPTO",
      },
    ],
    performanceSeries: [],
    performanceCoverage: { holdingsWithHistory: 2, totalHoldings: 2, partial: false, synthetic: false },
    ...overrides,
  };
}

// ── buildAllocationDtoFromAnalytics ──────────────────────────────────────────

describe("buildAllocationDtoFromAnalytics", () => {
  it("uses backend canonical displayAssetClass (Requirement 4.1)", () => {
    const analytics = makeSampleAnalytics();
    const result = buildAllocationDtoFromAnalytics(analytics, "p1");

    const assetClasses = result.slices.map((s) => s.assetClass);
    expect(assetClasses).toContain("STOCK");
    expect(assetClasses).toContain("CRYPTO");
  });

  it("maps unknown displayAssetClass to OTHER bucket (Requirement 4.3)", () => {
    const analytics = makeSampleAnalytics({
      holdings: [
        {
          ticker: "XYZ",
          quantity: 100,
          currentPrice: 10.0,
          currentValueBase: 1000.0,
          avgCostBasis: null,
          costBasisCurrency: null,
          unrealizedPnL: null,
          unrealizedPnLPercent: null,
          change24hAbsolute: null,
          change24hPercent: null,
          change24hReferenceAt: null,
          changeBasis: null,
          quoteCurrency: "USD",
          displayAssetClass: "OTHER",
        },
      ],
      totalValue: 1000.0,
    });

    const result = buildAllocationDtoFromAnalytics(analytics, "p1");
    const otherSlice = result.slices.find((s) => s.assetClass === "OTHER");
    expect(otherSlice).toBeDefined();
    expect(otherSlice?.label).toBe("Other");
    expect(otherSlice?.value).toBe(1000.0);
  });

  it("uses FX-converted currentValueBase values, not mixed-currency price×qty", () => {
    const analytics = makeSampleAnalytics();
    const result = buildAllocationDtoFromAnalytics(analytics, "p1");

    // STOCK slice should equal AAPL currentValueBase = 2125.0
    const stockSlice = result.slices.find((s) => s.assetClass === "STOCK");
    expect(stockSlice?.value).toBe(2125.0);

    // CRYPTO slice should equal BTC-USD currentValueBase = 46003.75
    const cryptoSlice = result.slices.find((s) => s.assetClass === "CRYPTO");
    expect(cryptoSlice?.value).toBe(46003.75);
  });

  it("allocation slices sum to ~100% (Property 1: Total reconciliation)", () => {
    const analytics = makeSampleAnalytics();
    const result = buildAllocationDtoFromAnalytics(analytics, "p1");

    const totalPercent = result.slices.reduce((sum, s) => sum + s.percentage, 0);
    expect(totalPercent).toBeCloseTo(100, 1);
  });

  it("allocation totalValue matches analytics totalValue (Property 1)", () => {
    const analytics = makeSampleAnalytics();
    const result = buildAllocationDtoFromAnalytics(analytics, "p1");

    expect(result.totalValue).toBe(analytics.totalValue);
  });

  it("sum of slice values equals totalValue (Property 1: Total reconciliation)", () => {
    const analytics = makeSampleAnalytics();
    const result = buildAllocationDtoFromAnalytics(analytics, "p1");

    const sliceSum = result.slices.reduce((sum, s) => sum + s.value, 0);
    expect(sliceSum).toBeCloseTo(analytics.totalValue, 2);
  });

  it("handles portfolio with all 7 asset classes including OTHER", () => {
    const displayClasses = ["STOCK", "ETF", "CRYPTO", "BOND", "CASH", "COMMODITY", "OTHER"] as const;
    const analytics = makeSampleAnalytics({
      holdings: displayClasses.map((cls, i) => ({
        ticker: `T${i}`,
        quantity: 1,
        currentPrice: 1000.0,
        currentValueBase: 1000.0,
        avgCostBasis: null,
        costBasisCurrency: null,
        unrealizedPnL: null,
        unrealizedPnLPercent: null,
        change24hAbsolute: null,
        change24hPercent: null,
        change24hReferenceAt: null,
        changeBasis: null,
        quoteCurrency: "USD",
        displayAssetClass: cls,
      })),
      totalValue: 7000.0,
    });

    const result = buildAllocationDtoFromAnalytics(analytics, "p1");
    expect(result.slices.length).toBe(7);

    const otherSlice = result.slices.find((s) => s.assetClass === "OTHER");
    expect(otherSlice?.label).toBe("Other");

    const totalPercent = result.slices.reduce((sum, s) => sum + s.percentage, 0);
    expect(totalPercent).toBeCloseTo(100, 1);
  });

  it("returns portfolio id correctly", () => {
    const analytics = makeSampleAnalytics();
    const result = buildAllocationDtoFromAnalytics(analytics, "portfolio-xyz");
    expect(result.portfolioId).toBe("portfolio-xyz");
  });
});

// ── buildAllocationDtoFromPortfolio — fallback ───────────────────────────────

describe("buildAllocationDtoFromPortfolio", () => {
  it("falls back to portfolio holdings assetClass when analytics unavailable", () => {
    const portfolio: PortfolioResponseDTO = {
      portfolioId: "p1",
      ownerId: "u1",
      name: "My Portfolio",
      currency: "USD",
      summary: {
        totalValue: 3125.0,
        totalCostBasis: 3125.0,
        totalUnrealizedPnL: 0,
        totalUnrealizedPnLPercent: 0,
        change24hAbsolute: 0,
        change24hPercent: 0,
        bestPerformer: { ticker: "AAPL", name: "Apple", change24hPercent: 0 },
        worstPerformer: { ticker: "AAPL", name: "Apple", change24hPercent: 0 },
      },
      holdings: [
        {
          id: "h1",
          ticker: "AAPL",
          name: "Apple",
          assetClass: "STOCK",
          quantity: 10,
          currentPrice: 212.5,
          totalValue: 2125.0,
          avgCostBasis: null,
          unrealizedPnL: null,
          unrealizedPnLPercent: null,
          change24hPercent: null,
          change24hAbsolute: null,
          portfolioWeight: 68.0,
          lastUpdatedAt: new Date(0).toISOString(),
        },
        {
          id: "h2",
          ticker: "ETF1",
          name: "VTSAX",
          assetClass: "ETF",
          quantity: 5,
          currentPrice: 200.0,
          totalValue: 1000.0,
          avgCostBasis: null,
          unrealizedPnL: null,
          unrealizedPnLPercent: null,
          change24hPercent: null,
          change24hAbsolute: null,
          portfolioWeight: 32.0,
          lastUpdatedAt: new Date(0).toISOString(),
        },
      ],
      asOfDate: new Date().toISOString(),
    };

    const result = buildAllocationDtoFromPortfolio(portfolio);
    const assetClasses = result.slices.map((s) => s.assetClass);
    expect(assetClasses).toContain("STOCK");
    expect(assetClasses).toContain("ETF");
    expect(result.totalValue).toBe(3125.0);
  });
});
