/**
 * B2 Task 1.8 (review-fix) — Task 1.8 requires the validator to "block
 * progression/submission on failure." Recomputes validity directly from the draft's
 * own data (not from BrowseStep's local error-display state), so Review/Save can
 * never be reached with an invalid entry regardless of which UI path produced it.
 */
import { describe, expect, it } from "vitest";
import type { DraftHoldings } from "@/types/assetPicker";
import { isDraftValid } from "./draftValidity";

function entry(
  ticker: string,
  quantity: string,
  lifecycleStatus: "ACTIVE" | "DEPRECATED" = "ACTIVE",
) {
  return {
    ticker,
    name: ticker,
    assetClass: "STOCK",
    quantity,
    source: "held" as const,
    lifecycleStatus,
  };
}

describe("isDraftValid", () => {
  it("is true for an empty draft (the valid remove-everything submit)", () => {
    expect(isDraftValid(new Map(), new Map())).toBe(true);
  });

  it("is true when every entry passes the base domain rules", () => {
    const draft: DraftHoldings = new Map([
      ["AAPL", entry("AAPL", "10")],
      ["BTC", entry("BTC", "0.5")],
    ]);
    expect(isDraftValid(draft, new Map())).toBe(true);
  });

  it("is false when any entry is malformed", () => {
    const draft: DraftHoldings = new Map([["AAPL", entry("AAPL", "abc")]]);
    expect(isDraftValid(draft, new Map())).toBe(false);
  });

  it("is false when any entry is zero or negative", () => {
    expect(isDraftValid(new Map([["AAPL", entry("AAPL", "0")]]), new Map())).toBe(false);
    expect(isDraftValid(new Map([["AAPL", entry("AAPL", "-1")]]), new Map())).toBe(false);
  });

  it("is false when any entry is empty (in-progress, not yet typed)", () => {
    expect(isDraftValid(new Map([["AAPL", entry("AAPL", "")]]), new Map())).toBe(false);
  });

  it("is false when a retained-deprecated entry exceeds its open-time ceiling", () => {
    const draft: DraftHoldings = new Map([["OLDCO", entry("OLDCO", "11", "DEPRECATED")]]);
    const initialQuantities = new Map([["OLDCO", "10"]]);
    expect(isDraftValid(draft, initialQuantities)).toBe(false);
  });

  it("is true when a retained-deprecated entry is reduced or unchanged", () => {
    const initialQuantities = new Map([["OLDCO", "10"]]);
    expect(
      isDraftValid(new Map([["OLDCO", entry("OLDCO", "10", "DEPRECATED")]]), initialQuantities),
    ).toBe(true);
    expect(
      isDraftValid(new Map([["OLDCO", entry("OLDCO", "5", "DEPRECATED")]]), initialQuantities),
    ).toBe(true);
  });

  it("catches the precision-loss boundary case (review-fix)", () => {
    const draft: DraftHoldings = new Map([
      ["OLDCO", entry("OLDCO", "99999999999.00000001", "DEPRECATED")],
    ]);
    const initialQuantities = new Map([["OLDCO", "99999999999.00000000"]]);
    expect(isDraftValid(draft, initialQuantities)).toBe(false);
  });

  it("is false when one of several entries is invalid, even if the rest are fine", () => {
    const draft: DraftHoldings = new Map([
      ["AAPL", entry("AAPL", "10")],
      ["BTC", entry("BTC", "abc")],
    ]);
    expect(isDraftValid(draft, new Map())).toBe(false);
  });
});
