/**
 * B2 Task 1.7 — derives the Browse list's rows from the catalog and the current draft.
 *
 * requirements.md 2.2: only Active_Asset entries are offered for NEW selection; a
 * Deprecated_Asset not already held is never offered. requirements.md 2.3: a held
 * Retained_Deprecated_Position stays in the list, rendered distinctly.
 */
import { describe, expect, it } from "vitest";
import type { CatalogAsset, DraftHoldings } from "@/types/assetPicker";
import { buildBrowseRows } from "./browseRows";

function catalog(overrides: Partial<CatalogAsset> = {}): CatalogAsset {
  return {
    ticker: "AAPL",
    name: "Apple Inc.",
    aliases: [],
    assetClass: "STOCK",
    quoteCurrency: "USD",
    lifecycleStatus: "ACTIVE",
    ...overrides,
  };
}

function draftOf(entries: Array<[string, Partial<DraftHoldings extends Map<string, infer V> ? V : never>]>): DraftHoldings {
  const map: DraftHoldings = new Map();
  for (const [ticker, overrides] of entries) {
    map.set(ticker, {
      ticker,
      name: ticker,
      assetClass: "STOCK",
      quantity: "1",
      source: "held",
      lifecycleStatus: "ACTIVE",
      ...overrides,
    });
  }
  return map;
}

describe("buildBrowseRows", () => {
  it("offers an active, undrafted catalog asset as unchecked", () => {
    const rows = buildBrowseRows([catalog({ ticker: "AAPL" })], new Map(), "");
    expect(rows).toEqual([
      expect.objectContaining({ ticker: "AAPL", checked: false, lifecycleStatus: "ACTIVE" }),
    ]);
  });

  it("excludes a deprecated, unheld catalog asset entirely", () => {
    const rows = buildBrowseRows(
      [catalog({ ticker: "OLDCO", lifecycleStatus: "DEPRECATED" })],
      new Map(),
      "",
    );
    expect(rows.find((r) => r.ticker === "OLDCO")).toBeUndefined();
  });

  it("includes a held deprecated position, rendered distinctly (checked, DEPRECATED)", () => {
    const draft = draftOf([["OLDCO", { lifecycleStatus: "DEPRECATED", quantity: "3" }]]);
    const rows = buildBrowseRows([], draft, "");

    expect(rows).toEqual([
      expect.objectContaining({
        ticker: "OLDCO",
        checked: true,
        lifecycleStatus: "DEPRECATED",
        quantity: "3",
      }),
    ]);
  });

  it("marks a held active catalog asset as checked with the draft's quantity", () => {
    const draft = draftOf([["AAPL", { quantity: "7" }]]);
    const rows = buildBrowseRows([catalog({ ticker: "AAPL" })], draft, "");

    expect(rows).toEqual([
      expect.objectContaining({ ticker: "AAPL", checked: true, quantity: "7" }),
    ]);
  });

  it("never lists a ticker twice, even when both catalog and draft carry it", () => {
    const draft = draftOf([["AAPL", {}]]);
    const rows = buildBrowseRows([catalog({ ticker: "AAPL" })], draft, "");
    expect(rows.filter((r) => r.ticker === "AAPL")).toHaveLength(1);
  });

  it("filters by ticker or name, case-insensitively", () => {
    const rows = buildBrowseRows(
      [catalog({ ticker: "AAPL", name: "Apple Inc." }), catalog({ ticker: "GOOGL", name: "Alphabet" })],
      new Map(),
      "apple",
    );
    expect(rows.map((r) => r.ticker)).toEqual(["AAPL"]);
  });

  it("returns an empty list when the search matches nothing", () => {
    const rows = buildBrowseRows([catalog({ ticker: "AAPL" })], new Map(), "zzz");
    expect(rows).toEqual([]);
  });
});
