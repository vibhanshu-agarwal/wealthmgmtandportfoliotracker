import assert from "node:assert/strict";
import { isDeepStrictEqual } from "node:util";
import { execFileSync } from "node:child_process";
import path from "node:path";
import type { APIRequestContext } from "@playwright/test";
import type { SavePayload } from "../../../src/components/asset-picker/savePayload";
import { DEMO_USER_ID } from "./demo-auth";
import { selectPortfolioVersion } from "./portfolio-seed-version";

export type ExactHolding = { assetTicker: string; quantity: string };
export type DemoPortfolio = { id: string; userId: string; version: number; holdings: ExactHolding[] };
export type DemoApi = { request: APIRequestContext; apiBaseUrl: string; token: string };

function canonicalHoldings(payload: unknown): ExactHolding[] {
  assert(Array.isArray(payload), "holdings must be an array");
  return payload.map((entry: unknown) => {
    assert(entry !== null && typeof entry === "object", "holdings must contain objects");
    const holding = entry as Record<string, unknown>;
    assert(typeof holding.assetTicker === "string" && holding.assetTicker.length > 0, "holdings must carry a ticker");
    assert(typeof holding.quantity === "string" && /^\d+\.\d{8}$/.test(holding.quantity), "quantity must retain the exact persisted decimal string");
    return { assetTicker: holding.assetTicker, quantity: holding.quantity };
  }).sort((a, b) => a.assetTicker.localeCompare(b.assetTicker));
}

/** Every observation is an exact identity selection; never pick portfolios[0]. */
export function selectDemoPortfolio(payload: unknown): DemoPortfolio {
  const version = selectPortfolioVersion(payload, DEMO_USER_ID);
  const selected = (payload as Record<string, unknown>[]).find((entry) => entry?.userId === DEMO_USER_ID)!;
  assert(typeof selected.id === "string" && selected.id.length > 0, "demo portfolio id is missing");
  return { id: selected.id, userId: DEMO_USER_ID, version, holdings: canonicalHoldings(selected.holdings) };
}

export function assertAdvancedVersion(before: number, after: number): void {
  assert(Number.isSafeInteger(before) && before >= 0 && Number.isSafeInteger(after) && after > before,
    `portfolio version must strictly advance (${before} -> ${after})`);
}

/** Only IDs are incidental: ticker membership, multiplicity and decimal bytes are exact. */
export function assertExactHoldings(actual: unknown, expected: ExactHolding[]): void {
  assert.deepStrictEqual(canonicalHoldings(actual), canonicalHoldings(expected), "holdings must match the complete exact oracle");
}

/** Execute Task 4.4a itself, never production Java or a captured reset response. */
export function loadDemoGoldenOracle(): ExactHolding[] {
  const repo = path.resolve(__dirname, "../../../..");
  const document = JSON.parse(execFileSync(process.env.PYTHON ?? (process.platform === "win32" ? "python" : "python3"), [
    path.join(repo, "scripts/derive_demo_golden_state.py"), "--catalog", path.join(repo, "config/seed-tickers.json"),
  ], { encoding: "utf8", timeout: 30_000 }));
  assert.equal(document.metadata.demoUserId, DEMO_USER_ID, "golden oracle must target the demo identity");
  const holdings = canonicalHoldings(document.wireHoldings);
  assert(holdings.length > 0, "golden oracle must not be empty");
  assert.equal(holdings.length, document.metadata.activeEntryCount, "golden oracle must cover all active catalog entries");
  assert.equal(new Set(holdings.map((h) => h.assetTicker)).size, holdings.length, "golden oracle must not duplicate tickers");
  return holdings;
}

/** Two candidates ensure the setup differs from both golden and the current state. */
export function nonGoldenHoldings(current: ExactHolding[], golden: ExactHolding[]): ExactHolding[] {
  const anchor = canonicalHoldings(golden)[0];
  assert(anchor && /^\d+\.0{8}$/.test(anchor.quantity), "golden setup anchor must be an integer quantity");
  const whole = BigInt(anchor.quantity.split(".")[0]);
  const first = [{ assetTicker: anchor.assetTicker, quantity: `${whole + BigInt(1)}.00000000` }];
  const next = isDeepStrictEqual(canonicalHoldings(current), first)
    ? [{ assetTicker: anchor.assetTicker, quantity: `${whole + BigInt(2)}.00000000` }]
    : first;
  assert(!isDeepStrictEqual(next, canonicalHoldings(golden)), "setup must differ from golden");
  return next;
}

function apiUrl(api: DemoApi, route: string): string {
  return `${api.apiBaseUrl.replace(/\/+$/, "")}${route}`;
}

export async function readDemoPortfolio(api: DemoApi): Promise<DemoPortfolio> {
  const response = await api.request.get(apiUrl(api, "/api/portfolio"), {
    headers: { Authorization: `Bearer ${api.token}`, "Cache-Control": "no-cache" },
  });
  assert.equal(response.status(), 200, "demo portfolio GET must return 200");
  return selectDemoPortfolio(await response.json());
}

export async function writeNonGoldenDemoComposition(api: DemoApi, golden: ExactHolding[]): Promise<DemoPortfolio> {
  const before = await readDemoPortfolio(api);
  const holdings = nonGoldenHoldings(before.holdings, golden);
  const data: SavePayload = {
    expectedVersion: before.version,
    holdings: holdings.map(({ assetTicker, quantity }) => ({ ticker: assetTicker, quantity })),
  };
  const response = await api.request.put(apiUrl(api, "/api/portfolio/holdings"), {
    headers: { Authorization: `Bearer ${api.token}`, "Content-Type": "application/json" },
    data,
  });
  assert.equal(response.status(), 200, "demo composition setup must return 200");
  const written = selectDemoPortfolio([await response.json()]);
  assert.equal(written.id, before.id, "composition must preserve the demo portfolio identity");
  assertAdvancedVersion(before.version, written.version);
  assertExactHoldings(written.holdings, holdings);
  const persisted = await readDemoPortfolio(api);
  assert.equal(persisted.id, written.id, "persisted composition identity must match");
  assert.equal(persisted.version, written.version, "persisted composition version must match");
  assertExactHoldings(persisted.holdings, holdings);
  return written;
}

/** Independent internal transport; conflicts may restore hygiene but never pass. */
export async function restoreDemoGoldenState(api: DemoApi, golden: ExactHolding[], internalApiKey: string): Promise<void> {
  assert(internalApiKey.trim(), "INTERNAL_API_KEY is required for independent demo cleanup");
  let conflicts = 0;
  try {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const observed = await readDemoPortfolio(api);
      const response = await api.request.post(apiUrl(api, "/api/internal/portfolio/demo-reset"), {
        headers: { "X-Internal-Api-Key": internalApiKey, "Content-Type": "application/json" },
        data: { expectedVersion: observed.version },
      });
      if (response.status() === 409) {
        conflicts += 1;
        continue;
      }
      assert.equal(response.status(), 200, `demo cleanup requires 200, received ${response.status()}`);
      const restored = selectDemoPortfolio([await response.json()]);
      assert.equal(restored.id, observed.id, "cleanup must preserve the demo portfolio identity");
      assertExactHoldings(restored.holdings, golden);
      if (isDeepStrictEqual(observed.holdings, canonicalHoldings(golden))) {
        assert(restored.version >= observed.version, "golden cleanup version must not regress");
      } else {
        assertAdvancedVersion(observed.version, restored.version);
      }
      const persisted = await readDemoPortfolio(api);
      assert.equal(persisted.id, restored.id, "persisted cleanup identity must match");
      assert.equal(persisted.version, restored.version, "persisted cleanup version must match");
      assertExactHoldings(persisted.holdings, golden);
      break;
    }
  } catch (error) {
    if (conflicts > 0) {
      throw new AggregateError([error], `demo cleanup observed ${conflicts} unexpected 409 conflict(s); subsequent cleanup failed: ${error instanceof Error ? error.message : "unknown failure"}`);
    }
    throw error;
  }
  assert.equal(conflicts, 0, `demo cleanup observed ${conflicts} unexpected 409 conflict(s), even if golden hygiene was restored`);
}
