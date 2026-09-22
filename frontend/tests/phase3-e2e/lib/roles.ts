/**
 * The three identities and their declared portfolios (design section 4). The ticker sets
 * do not overlap, so any cross-user leak is unambiguous.
 */
import { holdingsEqual, type Holding } from "./values";
import type { ApiClient, AuthSession, PortfolioReadback } from "./api";

export type Role = "CERT_A" | "CERT_B" | "FRESH";

export const DISPLAY_NAMES: Readonly<Record<Role, string>> = {
  CERT_A: "P3 Cert A",
  CERT_B: "P3 Cert B",
  FRESH: "P3 Fresh",
};

export const CERT_A_BASELINE: readonly Holding[] = [
  { ticker: "AAPL", quantity: "12" },
  { ticker: "MSFT", quantity: "4.5" },
  { ticker: "NVDA", quantity: "3" },
  { ticker: "RELIANCE.NS", quantity: "20" },
];

export const CERT_B_BASELINE: readonly Holding[] = [
  { ticker: "TSLA", quantity: "6" },
  { ticker: "BTC-USD", quantity: "0.35" },
  { ticker: "ETH-USD", quantity: "2.75" },
  { ticker: "SOL-USD", quantity: "40.125" },
];

/** CERT_B after S08: ETH-USD changed, SOL-USD removed, ADA-USD added. */
export const CERT_B_AFTER_S08: readonly Holding[] = [
  { ticker: "TSLA", quantity: "6" },
  { ticker: "BTC-USD", quantity: "0.35" },
  { ticker: "ETH-USD", quantity: "3.5" },
  { ticker: "ADA-USD", quantity: "250" },
];

/** CERT_B after S09: context 1's TSLA change wins; context 2's BTC-USD change is rejected. */
export const CERT_B_AFTER_S09: readonly Holding[] = [
  { ticker: "TSLA", quantity: "7" },
  { ticker: "BTC-USD", quantity: "0.35" },
  { ticker: "ETH-USD", quantity: "3.5" },
  { ticker: "ADA-USD", quantity: "250" },
];

export const FRESH_TARGET: readonly Holding[] = [
  { ticker: "GOOGL", quantity: "7" },
  { ticker: "DOGE-USD", quantity: "1500.5" },
];

export const BASELINES: Readonly<Record<"CERT_A" | "CERT_B", readonly Holding[]>> = {
  CERT_A: CERT_A_BASELINE,
  CERT_B: CERT_B_BASELINE,
};

export function tickersOf(holdings: readonly Holding[]): string[] {
  return holdings.map((h) => h.ticker).sort();
}

export type EnsureOutcome = "not_needed" | "written";

/**
 * Makes the account hold exactly `desired` (harness setup through the public API, one
 * version-bearing PUT), then confirms by independent readback. Never retries.
 */
export async function ensureHoldings(
  api: ApiClient,
  session: AuthSession,
  desired: readonly Holding[],
): Promise<{ outcome: EnsureOutcome; before: PortfolioReadback; after: PortfolioReadback }> {
  const before = await api.portfolio(session);
  if (holdingsEqual(before.holdings, desired)) return { outcome: "not_needed", before, after: before };
  const put = await api.putHoldings(session, before.version, desired);
  if (put.status !== 200 && put.status !== 201) {
    throw new Error(`setup PUT returned HTTP ${put.status}`);
  }
  const after = await api.portfolio(session);
  if (!holdingsEqual(after.holdings, desired) || after.version <= before.version) {
    throw new Error("setup PUT did not persist the desired holdings with an advanced version");
  }
  return { outcome: "written", before, after };
}
