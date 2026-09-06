// @vitest-environment node
import type { APIRequestContext } from "@playwright/test";
import { describe, expect, it } from "vitest";
import { DEMO_USER_ID } from "../demo-auth";
import {
  assertAdvancedVersion,
  assertExactHoldings,
  loadDemoGoldenOracle,
  nonGoldenHoldings,
  restoreDemoGoldenState,
  selectDemoPortfolio,
  writeNonGoldenDemoComposition,
} from "../demo-reset";

const golden = [
  { assetTicker: "AAPL", quantity: "37.00000000" },
  { assetTicker: "BTC-USD", quantity: "11.00000000" },
];
const changed = [{ assetTicker: "AAPL", quantity: "38.00000000" }];
const portfolio = (version: number, holdings = golden, userId = DEMO_USER_ID) => ({
  id: "demo-portfolio", userId, createdAt: "2020-01-01T00:00:00Z", version,
  holdings: holdings.map((h) => ({ id: `holding-${h.assetTicker}`, ...h })),
});

type Reply = { status: number; body: unknown };
type Call = { method: string; url: string; options: { headers?: Record<string, string>; data?: unknown } };
// Only the external HTTP boundary is doubled: ordering, fresh observations,
// expectedVersion, independent internal transport, and failure policy stay real.
function transport(reads: unknown[], writes: Reply[]) {
  const calls: Call[] = [];
  const response = (reply: Reply) => ({ status: () => reply.status, json: async () => reply.body });
  const request = {
    get: async (url: string, options: Call["options"]) => {
      calls.push({ method: "GET", url, options });
      if (!reads.length) throw new Error("unexpected GET");
      return response({ status: 200, body: reads.shift() });
    },
    post: async (url: string, options: Call["options"]) => {
      calls.push({ method: "POST", url, options });
      if (!writes.length) throw new Error("unexpected POST");
      return response(writes.shift()!);
    },
    put: async (url: string, options: Call["options"]) => {
      calls.push({ method: "PUT", url, options });
      if (!writes.length) throw new Error("unexpected PUT");
      return response(writes.shift()!);
    },
  } as unknown as APIRequestContext;
  return { calls, api: { request, apiBaseUrl: "http://gateway.test", token: "demo-test-token" } };
}

describe("demo reset evidence oracles", () => {
  it("selects the demo identity even when another portfolio occurs first", () => {
    expect(selectDemoPortfolio([portfolio(100, golden, "ordinary-user"), portfolio(7)]).version).toBe(7);
  });
  it.each([[], [portfolio(1, golden, "ordinary-user")], [portfolio(1), portfolio(2)]].map((payload) => [payload]))(
    "rejects missing or ambiguous demo identity in %j", (payload) => {
      expect(() => selectDemoPortfolio(payload)).toThrow(/exactly one portfolio/);
    },
  );
  it.each([undefined, -1, 1.5, Number.MAX_SAFE_INTEGER + 1])("rejects invalid version %s", (version) => {
    expect(() => selectDemoPortfolio([{ ...portfolio(1), version }])).toThrow(/version/);
  });
  it("rejects a setup that returned 200 without advancing the version", () => {
    expect(() => assertAdvancedVersion(7, 7)).toThrow(/advance/);
    expect(() => assertAdvancedVersion(8, 7)).toThrow(/advance/);
    expect(() => assertAdvancedVersion(7, 8)).not.toThrow();
  });
  it("compares every ticker and exact decimal quantity, independent of row order and IDs", () => {
    expect(() => assertExactHoldings(portfolio(1).holdings.reverse(), golden)).not.toThrow();
    for (const wrong of [golden.slice(0, 1), [...golden, golden[0]], changed,
      [{ assetTicker: "AAPL", quantity: "37.00000001" }, golden[1]],
      [{ assetTicker: "AAPL", quantity: 37 }, golden[1]]]) {
      expect(() => assertExactHoldings(wrong, golden)).toThrow(/holdings|quantity/);
    }
  });
  it("chooses a valid non-golden composition different from either initial candidate", () => {
    expect(nonGoldenHoldings(golden, golden)).toEqual(changed);
    expect(nonGoldenHoldings(changed, golden)).toEqual([{ assetTicker: "AAPL", quantity: "39.00000000" }]);
  });
  it("runs Task 4.4a's governed oracle against the complete tracked catalog", () => {
    const oracle = loadDemoGoldenOracle();
    expect(oracle.length).toBeGreaterThanOrEqual(150);
    expect(oracle.find((h) => h.assetTicker === "AAPL")).toEqual({ assetTicker: "AAPL", quantity: "37.00000000" });
    expect(new Set(oracle.map((h) => h.assetTicker)).size).toBe(oracle.length);
  });
});

describe("demo-authenticated public composition setup", () => {
  it("reads freshly, writes the demo version to the public API, then verifies persistence", async () => {
    const { api, calls } = transport([[portfolio(7)], [portfolio(8, changed)]], [{ status: 200, body: portfolio(8, changed) }]);
    await expect(writeNonGoldenDemoComposition(api, golden)).resolves.toMatchObject({ version: 8, userId: DEMO_USER_ID });
    expect(calls.map((c) => c.method)).toEqual(["GET", "PUT", "GET"]);
    expect(calls[1]).toMatchObject({
      url: "http://gateway.test/api/portfolio/holdings",
      options: { headers: { Authorization: "Bearer demo-test-token" }, data: { expectedVersion: 7, holdings: [{ ticker: "AAPL", quantity: "38.00000000" }] } },
    });
  });
  it("rejects a no-op setup response", async () => {
    const { api } = transport([[portfolio(7)]], [{ status: 200, body: portfolio(7, changed) }]);
    await expect(writeNonGoldenDemoComposition(api, golden)).rejects.toThrow(/advance/);
  });
  it("rejects a status-only setup response or an unpersisted write", async () => {
    const empty = transport([[portfolio(7)]], [{ status: 200, body: {} }]);
    await expect(writeNonGoldenDemoComposition(empty.api, golden)).rejects.toThrow();
    const unpersisted = transport([[portfolio(7)], [portfolio(8)]], [{ status: 200, body: portfolio(8, changed) }]);
    await expect(writeNonGoldenDemoComposition(unpersisted.api, golden)).rejects.toThrow(/holdings/);
  });
});

describe("independent unconditional demo cleanup", () => {
  it("uses only internal POST with the key and freshly observed version, then confirms golden persistence", async () => {
    const { api, calls } = transport([[portfolio(9, changed)], [portfolio(10)]], [{ status: 200, body: portfolio(10) }]);
    await restoreDemoGoldenState(api, golden, "internal-test-key");
    expect(calls.map((c) => c.method)).toEqual(["GET", "POST", "GET"]);
    expect(calls[1]).toEqual({ method: "POST", url: "http://gateway.test/api/internal/portfolio/demo-reset", options: {
      headers: { "X-Internal-Api-Key": "internal-test-key", "Content-Type": "application/json" }, data: { expectedVersion: 9 },
    } });
    expect(calls[0].options.headers).toMatchObject({ Authorization: "Bearer demo-test-token", "Cache-Control": "no-cache" });
  });
  it("permits a genuine already-golden cleanup no-op", async () => {
    const { api } = transport([[portfolio(9)], [portfolio(9)]], [{ status: 200, body: portfolio(9) }]);
    await expect(restoreDemoGoldenState(api, golden, "key")).resolves.toBeUndefined();
  });
  it("rejects a non-golden cleanup that did not advance", async () => {
    const { api } = transport([[portfolio(9, changed)]], [{ status: 200, body: portfolio(9) }]);
    await expect(restoreDemoGoldenState(api, golden, "key")).rejects.toThrow(/advance/);
  });
  it.each(["", "   "])("fails before requests without a nonblank internal key (%j)", async (key) => {
    const { api, calls } = transport([], []);
    await expect(restoreDemoGoldenState(api, golden, key)).rejects.toThrow(/INTERNAL_API_KEY/);
    expect(calls).toEqual([]);
  });
  it("restores hygiene after a conflict using a fresh version but preserves the failure", async () => {
    const { api, calls } = transport([[portfolio(9, changed)], [portfolio(10, changed)], [portfolio(11)]], [
      { status: 409, body: { currentVersion: 10 } }, { status: 200, body: portfolio(11) },
    ]);
    await expect(restoreDemoGoldenState(api, golden, "key")).rejects.toThrow(/cleanup.*409/);
    expect(calls.map((c) => c.method)).toEqual(["GET", "POST", "GET", "POST", "GET"]);
    expect(calls.filter((c) => c.method === "POST").map((c) => c.options.data)).toEqual([{ expectedVersion: 9 }, { expectedVersion: 10 }]);
  });
  it("bounds cleanup conflicts to three attempts, each with its own observation", async () => {
    const { api, calls } = transport([[portfolio(1)], [portfolio(2)], [portfolio(3)]], Array.from({ length: 3 }, () => ({ status: 409, body: {} })));
    await expect(restoreDemoGoldenState(api, golden, "key")).rejects.toThrow(/cleanup.*409/);
    expect(calls.filter((c) => c.method === "POST").map((c) => c.options.data)).toEqual([{ expectedVersion: 1 }, { expectedVersion: 2 }, { expectedVersion: 3 }]);
  });
  it("identity-checks every retry and stops before writing an ambiguous new observation", async () => {
    const { api, calls } = transport([[portfolio(1)], [portfolio(2), portfolio(3)]], [{ status: 409, body: {} }]);
    await expect(restoreDemoGoldenState(api, golden, "key")).rejects.toThrow(/exactly one portfolio/);
    expect(calls.filter((c) => c.method === "POST")).toHaveLength(1);
  });
  it.each([400, 403, 500])("requires 200 and does not retry HTTP %s", async (status) => {
    const { api, calls } = transport([[portfolio(1)]], [{ status, body: {} }]);
    await expect(restoreDemoGoldenState(api, golden, "key")).rejects.toThrow(new RegExp(String(status)));
    expect(calls).toHaveLength(2);
  });
  it("rejects incomplete golden response holdings and unpersisted golden state", async () => {
    const wrong = transport([[portfolio(1, changed)]], [{ status: 200, body: portfolio(2, golden.slice(0, 1)) }]);
    await expect(restoreDemoGoldenState(wrong.api, golden, "key")).rejects.toThrow(/holdings/);
    const unpersisted = transport([[portfolio(1, changed)], [portfolio(2, changed)]], [{ status: 200, body: portfolio(2) }]);
    await expect(restoreDemoGoldenState(unpersisted.api, golden, "key")).rejects.toThrow(/holdings/);
  });
});
