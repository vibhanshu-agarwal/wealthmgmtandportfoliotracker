/**
 * B2 Task 1.6 (draft state) / GC.1 (fully seeded on open) / Task 1.9 (duplicate
 * prevention) / Requirement 2.3-2.4 (retained deprecated positions).
 */
import { describe, expect, it } from "vitest";
import type { AssetHoldingDTO } from "@/types/portfolio";
import type { CatalogAsset } from "@/types/assetPicker";
import {
  addOrUpdateDraftTicker,
  removeDraftTicker,
  seedDraftFromHoldings,
} from "./draftState";

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

function catalogAsset(overrides: Partial<CatalogAsset> = {}): CatalogAsset {
  return {
    ticker: "TATAMOTORS.NS",
    name: "Tata Motors",
    aliases: ["Tata Motors"],
    assetClass: "STOCK",
    quoteCurrency: "INR",
    lifecycleStatus: "DEPRECATED",
    ...overrides,
  };
}

describe("seedDraftFromHoldings (GC.1)", () => {
  it("seeds every held ticker, selected, from open — not an empty draft", () => {
    const holdings = [
      holding({ ticker: "AAPL" }),
      holding({ ticker: "BTC", id: "h2" }),
    ];

    const draft = seedDraftFromHoldings(holdings, []);

    expect(draft.size).toBe(2);
    expect(draft.get("AAPL")?.source).toBe("held");
    expect(draft.get("BTC")?.source).toBe("held");
  });

  it("seeds an empty draft from an empty holdings list", () => {
    expect(seedDraftFromHoldings([], []).size).toBe(0);
  });

  it("preserves the held quantity string verbatim, including trailing zeros", () => {
    const draft = seedDraftFromHoldings([holding({ quantity: "10.00000000" })], []);
    expect(draft.get("AAPL")?.quantity).toBe("10.00000000");
  });

  it("marks a held ticker deprecated in the catalog with DEPRECATED lifecycle status", () => {
    const draft = seedDraftFromHoldings(
      [holding({ ticker: "TATAMOTORS.NS" })],
      [catalogAsset({ lifecycleStatus: "DEPRECATED" })],
    );

    expect(draft.get("TATAMOTORS.NS")?.lifecycleStatus).toBe("DEPRECATED");
  });

  it("defaults a held ticker absent from the catalog to ACTIVE (unknown, not deprecated)", () => {
    const draft = seedDraftFromHoldings([holding({ ticker: "ZZZZ" })], []);
    expect(draft.get("ZZZZ")?.lifecycleStatus).toBe("ACTIVE");
  });
});

describe("addOrUpdateDraftTicker (Task 1.9 — duplicate prevention)", () => {
  it("adds a new ticker as source 'added'", () => {
    const draft = seedDraftFromHoldings([], []);
    const next = addOrUpdateDraftTicker(draft, {
      ticker: "GOOGL",
      name: "Alphabet",
      assetClass: "STOCK",
      quantity: "5",
      lifecycleStatus: "ACTIVE",
    });

    expect(next.get("GOOGL")).toMatchObject({ quantity: "5", source: "added" });
  });

  it("selecting an already-drafted ticker edits the existing row, not a second one", () => {
    const draft = seedDraftFromHoldings([holding({ ticker: "AAPL", quantity: "10" })], []);
    const next = addOrUpdateDraftTicker(draft, {
      ticker: "AAPL",
      name: "Apple Inc.",
      assetClass: "STOCK",
      quantity: "15",
      lifecycleStatus: "ACTIVE",
    });

    expect(next.size).toBe(1);
    expect(next.get("AAPL")?.quantity).toBe("15");
    // Editing an existing held row does not fabricate a new "added" source.
    expect(next.get("AAPL")?.source).toBe("held");
  });

  it("does not mutate the input draft (pure update)", () => {
    const draft = seedDraftFromHoldings([], []);
    addOrUpdateDraftTicker(draft, {
      ticker: "GOOGL",
      name: "Alphabet",
      assetClass: "STOCK",
      quantity: "5",
      lifecycleStatus: "ACTIVE",
    });
    expect(draft.size).toBe(0);
  });
});

describe("removeDraftTicker (deselect means delete — requirements.md 1.3)", () => {
  it("removes the ticker entirely, with no separate remove affordance needed", () => {
    const draft = seedDraftFromHoldings([holding({ ticker: "AAPL" })], []);
    const next = removeDraftTicker(draft, "AAPL");
    expect(next.has("AAPL")).toBe(false);
  });

  it("is a no-op, not a throw, for a ticker not in the draft", () => {
    const draft = seedDraftFromHoldings([], []);
    expect(() => removeDraftTicker(draft, "AAPL")).not.toThrow();
    expect(removeDraftTicker(draft, "AAPL").size).toBe(0);
  });
});
