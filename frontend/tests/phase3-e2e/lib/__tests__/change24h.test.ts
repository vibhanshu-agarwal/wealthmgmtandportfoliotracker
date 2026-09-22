import { describe, expect, it } from "vitest";
import {
  cardCoverageLabel,
  expectedCardCoverageLabel,
  expectedChange24hCoverage,
  expectedFooterCoverageLabel,
  parseChangeCellMoney,
  recomputeTotalChange24h,
  sumPositionChanges,
} from "../change24h";

describe("expectedChange24hCoverage (counted from the holdings, not read from coverage)", () => {
  it("counts holdings with a value and a position change against every holding", () => {
    // CERT_A locally: three priced with a change, RELIANCE.NS without an FX rate → 3 of 4, partial.
    expect(
      expectedChange24hCoverage([
        { currentValueBase: 13517.76, change24hValueBase: 1517.76 },
        { currentValueBase: 1900, change24hValueBase: 360 },
        { currentValueBase: 5000, change24hValueBase: -2119.54 },
        { currentValueBase: null, change24hValueBase: null },
      ]),
    ).toEqual({ holdingsWithChange: 3, totalHoldings: 4, partial: true });
  });

  it("treats a missing history, price or older-backend field as not contributing", () => {
    expect(
      expectedChange24hCoverage([
        { currentValueBase: 6000, change24hValueBase: null },
        { currentValueBase: 6000 },
        { currentValueBase: 100, change24hValueBase: 10 },
      ]),
    ).toEqual({ holdingsWithChange: 1, totalHoldings: 3, partial: true });
  });

  it("is complete only when every holding contributes, and for an empty portfolio", () => {
    expect(expectedChange24hCoverage([{ currentValueBase: 100, change24hValueBase: 10 }])).toEqual({ holdingsWithChange: 1, totalHoldings: 1, partial: false });
    expect(expectedChange24hCoverage([])).toEqual({ holdingsWithChange: 0, totalHoldings: 0, partial: false });
  });
});

describe("coverage labels", () => {
  it("expects the card label only for a partial total that is shown", () => {
    const partial = { holdingsWithChange: 3, countedHoldings: 3, totalHoldings: 4, partial: true };
    expect(cardCoverageLabel(3, 4)).toBe("Partial: 3 of 4 holdings");
    expect(expectedCardCoverageLabel(-241.8078, partial)).toBe("Partial: 3 of 4 holdings");
    expect(expectedCardCoverageLabel(null, partial)).toBeNull();
    expect(expectedCardCoverageLabel(10, { ...partial, holdingsWithChange: 4, partial: false })).toBeNull();
    expect(expectedCardCoverageLabel(10, null)).toBeNull();
  });

  it("expects the footer label only when some, not all or none, of the visible rows contribute", () => {
    expect(expectedFooterCoverageLabel(2, 3)).toBe("Partial: 2 of 3");
    expect(expectedFooterCoverageLabel(3, 3)).toBeNull();
    expect(expectedFooterCoverageLabel(0, 3)).toBeNull();
  });
});

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
