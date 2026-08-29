/**
 * B2 GC.2 — the quantity display boundary.
 */
import { describe, expect, it } from "vitest";
import {
  compareQuantityStrings,
  formatQuantityForDisplay,
  quantityToDisplayNumber,
} from "./quantityDisplay";

describe("quantityToDisplayNumber", () => {
  it.each([
    ["10", 10],
    ["0.75000000", 0.75],
    ["0.00000001", 1e-8],
    ["99999999999.99999999", 99999999999.99999999],
    ["  2.5  ", 2.5],
  ])("converts %j to %s", (raw, expected) => {
    expect(quantityToDisplayNumber(raw)).toBe(expected);
  });

  it.each([[""], ["   "], ["abc"], ["1.2.3"], ["NaN"], ["Infinity"]])(
    "returns null rather than NaN or a fabricated 0 for %j",
    (raw) => {
      expect(quantityToDisplayNumber(raw)).toBeNull();
    },
  );
});

describe("formatQuantityForDisplay", () => {
  it("formats a well-formed decimal string", () => {
    expect(formatQuantityForDisplay("1234.5")).toBe("1,234.50");
  });

  it("renders an unparseable value verbatim instead of NaN", () => {
    expect(formatQuantityForDisplay("not-a-number")).toBe("not-a-number");
  });
});

describe("compareQuantityStrings", () => {
  it("orders numerically, not lexicographically", () => {
    // A plain string comparison puts "10" before "9".
    expect(compareQuantityStrings("10", "9")).toBe(1);
    expect(compareQuantityStrings("9", "10")).toBe(-1);
  });

  it("treats trailing zeros as equal in magnitude", () => {
    expect(compareQuantityStrings("0.75", "0.75000000")).toBe(0);
  });

  it("sorts unparseable values last", () => {
    expect(compareQuantityStrings("abc", "1")).toBe(1);
    expect(compareQuantityStrings("1", "abc")).toBe(-1);
    expect(compareQuantityStrings("abc", "xyz")).toBe(0);
  });
});
