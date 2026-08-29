/**
 * B2 Task 1.5 — data-integrity preflight for `quantityFidelityUnverified`.
 *
 * Strict preflight, immutable provenance: if any holding carries
 * `quantityFidelityUnverified: true`, the picker SHALL NOT open at all. This is the sole
 * enforcement point (no submit-time recheck, per Task 1.13's own note) and is
 * independent of the feature flag.
 */
import { describe, expect, it } from "vitest";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { hasUnverifiedFidelity } from "./fidelityPreflight";

function holding(overrides: Partial<AssetHoldingDTO> = {}): AssetHoldingDTO {
  return {
    id: "h1",
    ticker: "AAPL",
    name: "Apple Inc.",
    assetClass: "STOCK",
    quantity: "10",
    currentPrice: 100,
    totalValue: 1000,
    avgCostBasis: null,
    unrealizedPnL: null,
    unrealizedPnLPercent: null,
    change24hPercent: null,
    change24hAbsolute: null,
    portfolioWeight: 100,
    lastUpdatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("hasUnverifiedFidelity", () => {
  it("is false for an empty holdings list", () => {
    expect(hasUnverifiedFidelity([])).toBe(false);
  });

  it("is false when every holding is verified (flag absent)", () => {
    expect(hasUnverifiedFidelity([holding(), holding({ ticker: "BTC" })])).toBe(false);
  });

  it("is false when the flag is explicitly false", () => {
    expect(hasUnverifiedFidelity([holding({ quantityFidelityUnverified: false })])).toBe(false);
  });

  it("is true when any single holding is unverified", () => {
    const holdings = [
      holding({ ticker: "AAPL" }),
      holding({ ticker: "BTC", quantityFidelityUnverified: true }),
    ];
    expect(hasUnverifiedFidelity(holdings)).toBe(true);
  });

  it("is true when every holding is unverified", () => {
    expect(hasUnverifiedFidelity([holding({ quantityFidelityUnverified: true })])).toBe(true);
  });
});
