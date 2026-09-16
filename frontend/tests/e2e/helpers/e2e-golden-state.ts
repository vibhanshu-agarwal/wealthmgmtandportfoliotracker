import assert from "node:assert/strict";
import { assertExactHoldings, loadDemoGoldenOracle } from "./demo-reset";
import type { DemoApi, ExactHolding } from "./demo-reset";
import { FIXED_E2E_USER_ID, selectPortfolioVersion } from "./portfolio-seed-version";

/**
 * Independent, wire-level Golden-State verifier for the fixed E2E identity
 * (`00000000-0000-0000-0000-000000000e2e`).
 *
 * SCOPE — WIRE-TUPLE PARITY, NOT A FULL PERSISTED-TUPLE CHECK.
 * This asserts that `GET /api/portfolio` returns exactly one E2E portfolio whose
 * complete holding set exactly equals the derived active-catalog golden set,
 * matching `{assetTicker, quantity}` with decimal-string fidelity (no missing,
 * extra, duplicate or altered tuples). Off-wire cost basis is DELIBERATELY OUT OF
 * SCOPE: it is never serialized on the portfolio wire (`PortfolioResponse.java`),
 * so it is unreachable in the HTTP-only Azure prod proof. Cost-basis restoration
 * is deterministic server-side and is covered by server-side tests. Do not mistake
 * this for a full persisted-tuple check.
 *
 * WHY THE DEMO ORACLE IS REUSED VERBATIM — the wire tuple `{assetTicker,
 * quantity}` is IDENTITY-INDEPENDENT: the quantity is
 * `floorMod(ticker.hashCode(),50)+1` over the active catalog, a function of the
 * ticker alone with no user input. Only the off-wire cost basis is identity-
 * specific, and that is out of scope here. So the demo oracle's `wireHoldings`
 * ARE the correct expected set for the E2E portfolio, and this verifier reuses
 * `loadDemoGoldenOracle` (the unchanged, deliberately fixed-demo oracle) rather
 * than re-deriving for the E2E identity. The E2E identity matters only for
 * selecting WHICH portfolio to assert against, never for the expected values.
 *
 * INDEPENDENCE — the expected set is derived from checked-in inputs
 * (`config/seed-tickers.json` + `scripts/derive_demo_golden_state.py`, via
 * `loadDemoGoldenOracle`), never by importing or invoking
 * `GoldenStateTuplePreparer` or any production Java helper.
 */

export type { ExactHolding } from "./demo-reset";

/**
 * The complete expected E2E wire set: the unchanged demo oracle's `wireHoldings`.
 *
 * Because the wire quantity is identity-independent (see the module docblock),
 * the demo-derived wire set is exactly the set the E2E portfolio must restore.
 * `loadDemoGoldenOracle` already asserts the oracle targets `DEMO_USER_ID`, is
 * non-empty, covers every active catalog entry, and has unique, scale-8 tickers.
 */
export function deriveExpectedE2eGoldenSet(): ExactHolding[] {
  return loadDemoGoldenOracle();
}

/**
 * Select the single E2E portfolio's holdings from a `GET /api/portfolio` payload.
 *
 * Enforces exactly one portfolio for `FIXED_E2E_USER_ID` (0 or >1 throws; a
 * payload carrying only a foreign userId throws too) — it never falls back to
 * `portfolios[0]`.
 */
function selectE2eHoldings(payload: unknown): unknown {
  selectPortfolioVersion(payload, FIXED_E2E_USER_ID);
  const selected = (payload as Record<string, unknown>[]).find(
    (entry) => entry?.userId === FIXED_E2E_USER_ID,
  )!;
  assert(
    typeof selected.id === "string" && selected.id.length > 0,
    "e2e portfolio id is missing",
  );
  return selected.holdings;
}

function apiUrl(api: DemoApi, route: string): string {
  return `${api.apiBaseUrl.replace(/\/+$/, "")}${route}`;
}

/**
 * Assert that a fresh `GET /api/portfolio` restores the exact E2E golden wire set.
 *
 * Does a no-cache GET, selects the single E2E portfolio, then delegates to
 * `assertExactHoldings`, which validates each `quantity` is a string matching
 * `/^\d+\.\d{8}$/` (rejecting numbers, exponent form and wrong scale), builds the
 * canonical `{assetTicker, quantity}` set sorted by ticker, and deep-equals it to
 * the derived/expected set. Deep-equal of the sorted unique-ticker sets enforces
 * no missing / extra / duplicate / altered tuples.
 */
export async function assertE2eGoldenStateRestored(
  api: DemoApi,
  expected?: ExactHolding[],
): Promise<void> {
  const golden = expected ?? deriveExpectedE2eGoldenSet();
  // A caller-supplied `expected` bypasses the non-empty/unique guards baked into
  // deriveExpectedE2eGoldenSet(); refuse an empty set so an empty portfolio can
  // never vacuously satisfy the check.
  assert(golden.length > 0, "expected e2e golden set must not be empty");
  const response = await api.request.get(apiUrl(api, "/api/portfolio"), {
    headers: { Authorization: `Bearer ${api.token}`, "Cache-Control": "no-cache" },
  });
  assert.equal(response.status(), 200, "e2e portfolio GET must return 200");
  const holdings = selectE2eHoldings(await response.json());
  assertExactHoldings(holdings, golden);
}
