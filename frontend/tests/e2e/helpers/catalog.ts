import { readFileSync } from "node:fs";
import path from "node:path";

/**
 * Reads the canonical supported-asset manifest — the single source of truth for
 * what the product declares it supports (Spec A, Supported_Catalog).
 *
 * Spec A Requirement 8.10 forbids any test, monitor, or verification step that
 * exercises the canonical or live catalog from encoding that catalog's size as a
 * literal. `api-live-smoke.spec.ts` previously asserted `holdingsInserted >= 160`,
 * which both contradicted Requirement 3.4 (no fixed catalog size) and would pass
 * silently if the seeder over-seeded. It began failing outright once
 * `TATAMOTORS.NS` was deprecated and the seed dropped to 159.
 *
 * Fixture-based tests may still assert their own known size — 8.10's prohibition
 * covers the canonical and live catalog only.
 */

export type CatalogEntry = {
  ticker: string;
  name: string;
  aliases: string[];
  assetClass: string;
  quoteCurrency: string;
  lifecycleStatus: "ACTIVE" | "DEPRECATED";
};

const MANIFEST_PATH = path.resolve(__dirname, "../../../../config/seed-tickers.json");

let cached: CatalogEntry[] | null = null;

function loadCatalog(): CatalogEntry[] {
  if (cached === null) {
    cached = JSON.parse(readFileSync(MANIFEST_PATH, "utf8")) as CatalogEntry[];
  }
  return cached;
}

/** Every entry the manifest declares, active or deprecated. */
export function allEntries(): CatalogEntry[] {
  return loadCatalog();
}

/** Entries with `lifecycleStatus: "ACTIVE"` — the set both seed paths enumerate. */
export function activeEntries(): CatalogEntry[] {
  return loadCatalog().filter((e) => e.lifecycleStatus === "ACTIVE");
}

/**
 * Cardinality of the Active_Asset set. This is what a seeded portfolio holds and
 * what the refresh set targets — derived, never hard-coded.
 */
export function activeAssetCount(): number {
  return activeEntries().length;
}

/** Active tickers, for sampling assertions that must not name a deprecated symbol. */
export function activeTickers(): string[] {
  return activeEntries().map((e) => e.ticker);
}
