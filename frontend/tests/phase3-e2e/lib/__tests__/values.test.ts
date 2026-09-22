import { describe, expect, it } from "vitest";
import { holdingsEqual, normalizeDecimal, normalizeHoldings, parseDisplayedMoney } from "../values";

describe("normalizeDecimal", () => {
  it.each([
    ["12.00000000", "12"],
    ["12", "12"],
    ["4.50000000", "4.5"],
    ["0.35000000", "0.35"],
    ["1500.5", "1500.5"],
    ["040.125", "40.125"],
    ["0.00000001", "0.00000001"],
  ])("normalizes %s to %s", (input, expected) => {
    expect(normalizeDecimal(input)).toBe(expected);
  });

  it.each(["", "abc", "1.2.3", "-1", "1e3", " 1"])("rejects %j", (input) => {
    expect(() => normalizeDecimal(input)).toThrow();
  });
});

describe("holdingsEqual / normalizeHoldings", () => {
  it("treats wire precision and order as irrelevant but tickers and values as exact", () => {
    const wire = [
      { ticker: "MSFT", quantity: "4.50000000" },
      { ticker: "AAPL", quantity: "12.00000000" },
    ];
    expect(holdingsEqual(wire, [{ ticker: "AAPL", quantity: "12" }, { ticker: "MSFT", quantity: "4.5" }])).toBe(true);
    expect(holdingsEqual(wire, [{ ticker: "AAPL", quantity: "12" }, { ticker: "MSFT", quantity: "4.51" }])).toBe(false);
    expect(holdingsEqual(wire, [{ ticker: "AAPL", quantity: "12" }])).toBe(false);
    expect(holdingsEqual(wire, [...wire, { ticker: "NVDA", quantity: "3" }])).toBe(false);
  });

  it("sorts by ticker and rejects duplicate tickers", () => {
    expect(normalizeHoldings([{ ticker: "b", quantity: "1" }, { ticker: "a", quantity: "2.0" }])).toEqual([
      { ticker: "a", quantity: "2" },
      { ticker: "b", quantity: "1" },
    ]);
    expect(() => normalizeHoldings([{ ticker: "a", quantity: "1" }, { ticker: "a", quantity: "2" }])).toThrow(/duplicate/);
  });
});

describe("parseDisplayedMoney", () => {
  it.each([
    ["$2,550.00", 2550],
    ["$0.00", 0],
    ["-$1,234.56", -1234.56],
    ["+$10.10", 10.1],
    [" $3.50 ", 3.5],
  ])("parses %j", (text, expected) => {
    expect(parseDisplayedMoney(text)).toBeCloseTo(expected, 10);
  });

  it("returns null for the unavailable dash", () => {
    expect(parseDisplayedMoney("—")).toBeNull();
  });

  it.each(["", "USD 5", "$1.2.3", "12"])("rejects %j", (text) => {
    expect(() => parseDisplayedMoney(text)).toThrow();
  });
});
