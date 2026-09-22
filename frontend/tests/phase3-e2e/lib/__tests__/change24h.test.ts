import { describe, expect, it } from "vitest";
import { parseChangeCellMoney, recomputeTotalChange24h, sumPositionChanges } from "../change24h";

describe("parseChangeCellMoney (Portfolio 24h cell)", () => {
  it("reads the position-level sub-line after the percent", () => {
    expect(parseChangeCellMoney("+12.65%+$1,517.76")).toBeCloseTo(1517.76, 10);
    expect(parseChangeCellMoney("-3.23%-$1,000.00")).toBeCloseTo(-1000, 10);
    expect(parseChangeCellMoney("0.00% +$0.00")).toBe(0);
  });

  it("returns null when the cell has no sub-line, or no change at all", () => {
    expect(parseChangeCellMoney("+1.50%")).toBeNull();
    expect(parseChangeCellMoney("—")).toBeNull();
  });

  it("rejects a sub-line that is not a displayed amount", () => {
    expect(() => parseChangeCellMoney("+1.50%abc")).toThrow();
  });
});

describe("sumPositionChanges (Portfolio footer)", () => {
  it("sums the available position-level changes: 1,517.76 − 1,000.00 = 517.76", () => {
    expect(sumPositionChanges([{ change24hValueBase: 1517.76 }, { change24hValueBase: -1000 }, { change24hValueBase: null }, {}])).toBeCloseTo(517.76, 10);
  });

  it("returns null when no holding has one", () => {
    expect(sumPositionChanges([{ change24hValueBase: null }, {}])).toBeNull();
    expect(sumPositionChanges([])).toBeNull();
  });
});

// Expected values are worked by hand; the recomputation must not read change24hValueBase.
describe("recomputeTotalChange24h (D11 oracle)", () => {
  it("multiplies each per-unit change by quantity × FX rate, derived from value / price", () => {
    // AAPL: 12 units at 1,126.48 → value 13,517.76; 12 × 126.48 = 1,517.76.
    // RELIANCE.NS: 5 units at ₹1,242.30, INR→USD 0.012 → value 74.538; 5 × 42.30 × 0.012 = 2.538.
    const { expected } = recomputeTotalChange24h([
      { currentPrice: 1126.48, currentValueBase: 13517.76, change24hAbsolute: 126.48 },
      { currentPrice: 1242.3, currentValueBase: 74.538, change24hAbsolute: 42.3 },
    ]);
    expect(expected).toBeCloseTo(1520.298, 9);
  });

  it("skips holdings without a change, a value or a price", () => {
    const { expected } = recomputeTotalChange24h([
      { currentPrice: 200, currentValueBase: 2000, change24hAbsolute: 10 },
      { currentPrice: 3000, currentValueBase: 6000, change24hAbsolute: null },
      { currentPrice: 1500, currentValueBase: null, change24hAbsolute: 50 },
      { currentPrice: null, currentValueBase: null, change24hAbsolute: null },
    ]);
    expect(expected).toBeCloseTo(100, 9);
  });

  it("returns null when no holding has all three fields", () => {
    expect(recomputeTotalChange24h([{ currentPrice: 200, currentValueBase: 2000, change24hAbsolute: null }]).expected).toBeNull();
    expect(recomputeTotalChange24h([]).expected).toBeNull();
  });

  it("widens the tolerance by the wire rounding of the per-unit change, per unit held", () => {
    // change24hAbsolute is rounded to 4 decimals: up to 0.00005 per unit.
    // 1,500.5 DOGE at 0.25 → value 375.125 → 1,500.5 units × 0.00005 = 0.075025.
    const { tolerance } = recomputeTotalChange24h([{ currentPrice: 0.25, currentValueBase: 375.125, change24hAbsolute: 0.0123 }]);
    expect(tolerance).toBeCloseTo(0.075025, 9);
  });
});
