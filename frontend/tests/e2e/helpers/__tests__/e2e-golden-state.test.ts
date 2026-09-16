// @vitest-environment node
import type { APIRequestContext } from "@playwright/test";
import { describe, expect, it } from "vitest";
import { FIXED_E2E_USER_ID } from "../portfolio-seed-version";
import { loadDemoGoldenOracle } from "../demo-reset";
import {
  assertE2eGoldenStateRestored,
  deriveExpectedE2eGoldenSet,
} from "../e2e-golden-state";

// A small, fully-inline expected set (never imported from production Java): the
// wire tuple is {assetTicker, quantity} with decimal-string fidelity at scale 8.
const expected = [
  { assetTicker: "AAPL", quantity: "37.00000000" },
  { assetTicker: "BTC-USD", quantity: "15.00000000" },
];

const e2ePortfolio = (
  holdings: Array<{ assetTicker: string; quantity: unknown }> = expected,
  userId = FIXED_E2E_USER_ID,
) => ({
  id: "e2e-portfolio",
  userId,
  createdAt: "2020-01-01T00:00:00Z",
  version: 3,
  // Extra `id` fields on each holding must be ignored; only the tuple matters.
  holdings: holdings.map((h) => ({ id: `holding-${h.assetTicker}`, ...h })),
});

type Call = { method: string; url: string; options: { headers?: Record<string, string> } };
// Only the external HTTP boundary (GET /api/portfolio) is doubled; identity
// selection, decimal validation and exact deep-equal stay real.
function transport(reads: unknown[]) {
  const calls: Call[] = [];
  const request = {
    get: async (url: string, options: Call["options"]) => {
      calls.push({ method: "GET", url, options });
      if (!reads.length) throw new Error("unexpected GET");
      const body = reads.shift();
      return { status: () => 200, json: async () => body };
    },
  } as unknown as APIRequestContext;
  return { calls, api: { request, apiBaseUrl: "http://gateway.test", token: "e2e-test-token" } };
}

describe("e2e golden-state complete-tuple verifier", () => {
  it("passes on the exact wire set regardless of row order or extra id fields", async () => {
    const shuffled = [expected[1], expected[0]];
    const { api, calls } = transport([[e2ePortfolio(shuffled)]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).resolves.toBeUndefined();
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({
      method: "GET",
      url: "http://gateway.test/api/portfolio",
      options: { headers: { Authorization: "Bearer e2e-test-token", "Cache-Control": "no-cache" } },
    });
  });

  // The four set-mismatch cases pin the exact-oracle deep-equal failure
  // specifically, not the unrelated "holdings must be an array" shape error.
  it("detects a missing tuple", async () => {
    const { api } = transport([[e2ePortfolio([expected[0]])]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/complete exact oracle/);
  });

  it("detects an extra tuple", async () => {
    const withExtra = [...expected, { assetTicker: "MSFT", quantity: "12.00000000" }];
    const { api } = transport([[e2ePortfolio(withExtra)]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/complete exact oracle/);
  });

  it("detects a duplicated ticker", async () => {
    const withDup = [...expected, { assetTicker: "AAPL", quantity: "37.00000000" }];
    const { api } = transport([[e2ePortfolio(withDup)]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/complete exact oracle/);
  });

  it("detects an altered quantity", async () => {
    const altered = [{ assetTicker: "AAPL", quantity: "38.00000000" }, expected[1]];
    const { api } = transport([[e2ePortfolio(altered)]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/complete exact oracle/);
  });

  it.each(["37.0000000", "37"])("rejects a wrong decimal scale (%j)", async (quantity) => {
    const bad = [{ assetTicker: "AAPL", quantity }, expected[1]];
    const { api } = transport([[e2ePortfolio(bad)]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/quantity/);
  });

  it("rejects exponent-form quantity", async () => {
    const bad = [{ assetTicker: "AAPL", quantity: "3.7E+1" }, expected[1]];
    const { api } = transport([[e2ePortfolio(bad)]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/quantity/);
  });

  it("rejects a non-string (numeric) quantity", async () => {
    const bad = [{ assetTicker: "AAPL", quantity: 37 }, expected[1]];
    const { api } = transport([[e2ePortfolio(bad)]]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/quantity/);
  });

  it.each([
    ["zero", []],
    ["two", [e2ePortfolio(expected), e2ePortfolio(expected)]],
    ["foreign-only", [e2ePortfolio(expected, "ordinary-user")]],
  ])("enforces exactly one E2E portfolio (%s case throws, never portfolios[0])", async (_label, payload) => {
    const { api } = transport([payload]);
    await expect(assertE2eGoldenStateRestored(api, expected)).rejects.toThrow(/exactly one portfolio/);
  });

  it("rejects a vacuously empty expected set instead of passing", async () => {
    // An empty `expected` against an empty portfolio must not pass: the
    // non-empty guard belongs in the assertion, not only in the derivation.
    const { api } = transport([[e2ePortfolio([])]]);
    await expect(assertE2eGoldenStateRestored(api, [])).rejects.toThrow(/empty/);
  });
});

describe("E2E expected set is the identity-independent demo-derived wire set", () => {
  it("returns the complete active-catalog wire set (>=150, unique, scale-8)", () => {
    const set = deriveExpectedE2eGoldenSet();
    expect(set.length).toBeGreaterThanOrEqual(150);
    expect(new Set(set.map((h) => h.assetTicker)).size).toBe(set.length);
    const aapl = set.find((h) => h.assetTicker === "AAPL");
    expect(aapl).toEqual({ assetTicker: "AAPL", quantity: "37.00000000" });
    expect(aapl!.quantity).toMatch(/^\d+\.\d{8}$/);
  });

  it("reuses the unchanged demo oracle verbatim (wire quantity is identity-independent)", () => {
    // The wire tuple {assetTicker, quantity} is a function of the active catalog
    // and the ticker hash only (floorMod(hashCode(ticker),50)+1) — no user input.
    // So the demo oracle's wireHoldings ARE the correct expected set for the E2E
    // portfolio; only the off-wire, identity-specific cost basis differs, and it
    // is outside this HTTP proof. Reusing the deliberately fixed-demo oracle
    // avoids re-parametrising it by identity. This pins that contract: if a future
    // change made deriveExpectedE2eGoldenSet diverge from the demo wire set, it fails.
    expect(deriveExpectedE2eGoldenSet()).toEqual(loadDemoGoldenOracle());
  });
});
