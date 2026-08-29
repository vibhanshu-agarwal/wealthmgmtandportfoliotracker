/**
 * B2 Task 1.8 — the string-domain quantity validator, enforcing B1's Quantity_Domain
 * client-side so a doomed request never reaches the server.
 *
 * Table-driven per the task: valid boundary values at exactly 11/8 digits, one over on
 * each, non-numeric text, empty, zero, and negative.
 */
import { describe, expect, it } from "vitest";
import {
  QUANTITY_REQUIRED_MESSAGE,
  validateDraftQuantity,
  validateRetainedDeprecatedQuantity,
} from "./quantityValidator";

describe("validateDraftQuantity — accepted values", () => {
  it.each([
    ["1"],
    ["10"],
    ["12.5"],
    ["0.00000001"],
    ["99999999999"],
    ["99999999999.99999999"],
    ["0.75000000"],
    ["007"],
  ])("accepts %j", (raw) => {
    expect(validateDraftQuantity(raw)).toEqual({ valid: true });
  });
});

describe("validateDraftQuantity — domain boundaries", () => {
  it("accepts exactly 11 integer digits", () => {
    expect(validateDraftQuantity("99999999999").valid).toBe(true);
  });

  it("rejects 12 integer digits", () => {
    const result = validateDraftQuantity("999999999999");
    expect(result.valid).toBe(false);
    expect(result.valid === false && result.message).toMatch(/11 digits before/i);
  });

  it("accepts exactly 8 fractional digits", () => {
    expect(validateDraftQuantity("1.12345678").valid).toBe(true);
  });

  it("rejects 9 fractional digits", () => {
    const result = validateDraftQuantity("1.123456789");
    expect(result.valid).toBe(false);
    expect(result.valid === false && result.message).toMatch(/8 digits after/i);
  });
});

describe("validateDraftQuantity — rejected values", () => {
  it.each([["", QUANTITY_REQUIRED_MESSAGE], ["   ", QUANTITY_REQUIRED_MESSAGE]])(
    "treats %j as missing",
    (raw, message) => {
      expect(validateDraftQuantity(raw)).toEqual({ valid: false, message });
    },
  );

  it.each([["0"], ["0.0"], ["0.00000000"], ["-1"], ["-0.5"]])(
    "rejects %j as not strictly positive",
    (raw) => {
      const result = validateDraftQuantity(raw);
      expect(result.valid).toBe(false);
      expect(result.valid === false && result.message).toMatch(/greater than zero/i);
    },
  );

  it.each([["abc"], ["1.2.3"], ["1e5"], ["1,000"], ["1 0"], [" 10"], ["10 "], ["10."], [".5"], ["+5"]])(
    "rejects %j as malformed decimal text",
    (raw) => {
      const result = validateDraftQuantity(raw);
      expect(result.valid).toBe(false);
      expect(result.valid === false && result.message).toMatch(/plain decimal/i);
    },
  );
});

describe("validateRetainedDeprecatedQuantity — reduce or remove only", () => {
  it("allows the same quantity", () => {
    expect(validateRetainedDeprecatedQuantity("10", "10")).toEqual({ valid: true });
  });

  it("allows a reduction", () => {
    expect(validateRetainedDeprecatedQuantity("9.5", "10")).toEqual({ valid: true });
  });

  it("allows a reduction expressed with different trailing zeros", () => {
    expect(validateRetainedDeprecatedQuantity("10.00000000", "10")).toEqual({ valid: true });
  });

  it("rejects an increase client-side, before submission", () => {
    const result = validateRetainedDeprecatedQuantity("11", "10");
    expect(result.valid).toBe(false);
    expect(result.valid === false && result.message).toMatch(/no longer offered/i);
  });

  it("still applies the base domain rules", () => {
    expect(validateRetainedDeprecatedQuantity("0", "10").valid).toBe(false);
    expect(validateRetainedDeprecatedQuantity("abc", "10").valid).toBe(false);
  });
});
