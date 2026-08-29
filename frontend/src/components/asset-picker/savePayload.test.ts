/**
 * B2 GC.3 — the `PUT /api/portfolio/holdings` payload always carries the complete
 * desired set, never a diff.
 */
import { describe, expect, it } from "vitest";
import type { DraftHoldings } from "@/types/assetPicker";
import { buildSavePayload } from "./savePayload";

function entry(ticker: string, quantity: string) {
  return {
    ticker,
    name: ticker,
    assetClass: "STOCK",
    quantity,
    source: "held" as const,
    lifecycleStatus: "ACTIVE" as const,
  };
}

describe("buildSavePayload", () => {
  it("carries every drafted ticker, not only the ones that changed", () => {
    const draft: DraftHoldings = new Map([
      ["AAPL", entry("AAPL", "10")],
      ["BTC", entry("BTC", "0.5")],
      ["GOOGL", entry("GOOGL", "3")],
    ]);

    const payload = buildSavePayload(draft, 7);

    expect(payload.expectedVersion).toBe(7);
    expect(payload.holdings).toHaveLength(3);
    expect(payload.holdings).toEqual(
      expect.arrayContaining([
        { ticker: "AAPL", quantity: "10" },
        { ticker: "BTC", quantity: "0.5" },
        { ticker: "GOOGL", quantity: "3" },
      ]),
    );
  });

  it("carries an empty holdings array for an empty draft — a valid 'remove everything' submit", () => {
    const payload = buildSavePayload(new Map(), 7);
    expect(payload.holdings).toEqual([]);
    expect(payload.expectedVersion).toBe(7);
  });

  it("carries expectedVersion 0 for the first-time-save case", () => {
    const payload = buildSavePayload(new Map(), 0);
    expect(payload.expectedVersion).toBe(0);
  });

  it("carries the quantity string verbatim, never a parsed number", () => {
    const draft: DraftHoldings = new Map([["AAPL", entry("AAPL", "10.00000000")]]);
    const payload = buildSavePayload(draft, 1);
    expect(payload.holdings[0].quantity).toBe("10.00000000");
  });
});
