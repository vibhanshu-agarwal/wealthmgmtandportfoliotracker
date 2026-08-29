/**
 * B2 Task 1.12 — `ReviewStep`'s pure derivation: diff(initialHoldings, draftHoldings) →
 * added/changed/removed/unchanged.
 */
import { describe, expect, it } from "vitest";
import type { DraftHoldings } from "@/types/assetPicker";
import type { AssetHoldingDTO } from "@/types/portfolio";
import { diffHoldings } from "./reviewDiff";

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

function draftEntry(ticker: string, quantity: string, source: "held" | "added" = "held") {
  return {
    ticker,
    name: ticker,
    assetClass: "STOCK",
    quantity,
    source,
    lifecycleStatus: "ACTIVE" as const,
  };
}

describe("diffHoldings", () => {
  it("classifies a ticker present only in the draft as added", () => {
    const draft: DraftHoldings = new Map([["GOOGL", draftEntry("GOOGL", "5", "added")]]);
    const diff = diffHoldings([], draft);

    expect(diff.added.map((r) => r.ticker)).toEqual(["GOOGL"]);
    expect(diff.changed).toEqual([]);
    expect(diff.removed).toEqual([]);
    expect(diff.unchanged).toEqual([]);
  });

  it("classifies a ticker present only in initial holdings as removed", () => {
    const diff = diffHoldings([holding({ ticker: "AAPL", quantity: "10" })], new Map());

    expect(diff.removed.map((r) => r.ticker)).toEqual(["AAPL"]);
    expect(diff.added).toEqual([]);
  });

  it("classifies a ticker with a different quantity as changed", () => {
    const draft: DraftHoldings = new Map([["AAPL", draftEntry("AAPL", "15")]]);
    const diff = diffHoldings([holding({ ticker: "AAPL", quantity: "10" })], draft);

    expect(diff.changed).toEqual([
      expect.objectContaining({ ticker: "AAPL", fromQuantity: "10", toQuantity: "15" }),
    ]);
  });

  it("classifies a ticker with the identical quantity string as unchanged", () => {
    const draft: DraftHoldings = new Map([["AAPL", draftEntry("AAPL", "10")]]);
    const diff = diffHoldings([holding({ ticker: "AAPL", quantity: "10" })], draft);

    expect(diff.unchanged.map((r) => r.ticker)).toEqual(["AAPL"]);
    expect(diff.changed).toEqual([]);
  });

  it("treats numerically-equal but differently-formatted strings as changed, not unchanged", () => {
    // GC.2: quantity is a string end-to-end — "10" and "10.00000000" are a real edit
    // to what gets submitted, even though they denote the same magnitude.
    const draft: DraftHoldings = new Map([["AAPL", draftEntry("AAPL", "10.00000000")]]);
    const diff = diffHoldings([holding({ ticker: "AAPL", quantity: "10" })], draft);

    expect(diff.changed).toEqual([
      expect.objectContaining({ ticker: "AAPL", fromQuantity: "10", toQuantity: "10.00000000" }),
    ]);
  });

  it("handles a mixed diff across all four buckets in one call", () => {
    const draft: DraftHoldings = new Map([
      ["AAPL", draftEntry("AAPL", "10")], // unchanged
      ["BTC", draftEntry("BTC", "2", "held")], // changed (was 1)
      ["GOOGL", draftEntry("GOOGL", "3", "added")], // added
    ]);
    const initial = [
      holding({ ticker: "AAPL", quantity: "10" }),
      holding({ ticker: "BTC", quantity: "1" }),
      holding({ ticker: "MSFT", quantity: "4" }), // removed
    ];

    const diff = diffHoldings(initial, draft);

    expect(diff.added.map((r) => r.ticker)).toEqual(["GOOGL"]);
    expect(diff.changed.map((r) => r.ticker)).toEqual(["BTC"]);
    expect(diff.removed.map((r) => r.ticker)).toEqual(["MSFT"]);
    expect(diff.unchanged.map((r) => r.ticker)).toEqual(["AAPL"]);
  });

  it("returns an all-empty diff for an empty draft against no initial holdings", () => {
    const diff = diffHoldings([], new Map());
    expect(diff).toEqual({ added: [], changed: [], removed: [], unchanged: [] });
  });
});
