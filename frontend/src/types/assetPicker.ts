/**
 * B2 catalog and draft types.
 *
 * These are new domain types for the Asset Picker; they intentionally do not reuse
 * `AssetHoldingDTO` (the read-side dashboard shape), since the picker's draft and the
 * catalog it browses are B2's own wire contracts (design.md D2), not portfolio-service's
 * analytics-enriched holding view.
 */

/** `GET /api/assets` lifecycle status (design.md D2). */
export type AssetLifecycleStatus = "ACTIVE" | "DEPRECATED";

/** One catalog entry from `GET /api/assets`. */
export interface CatalogAsset {
  ticker: string;
  name: string;
  aliases: string[];
  assetClass: string;
  quoteCurrency: string;
  lifecycleStatus: AssetLifecycleStatus;
}

/** `GET /api/assets` response envelope, carrying the ETag-equivalent catalog version. */
export interface CatalogResponse {
  catalogVersion: string;
  assets: CatalogAsset[];
}

/**
 * One row of the picker's draft (design.md D1): `Map<ticker, DraftHolding>`.
 *
 * `quantity` is always a string per GC.2. `source` distinguishes a row seeded from the
 * portfolio the modal opened against (`"held"`) from one added during Browse
 * (`"added"`) — only a `"held"` + `Deprecated_Asset` row is reduce-or-remove-only.
 */
export interface DraftHolding {
  ticker: string;
  name: string;
  assetClass: string;
  quantity: string;
  source: "held" | "added";
  lifecycleStatus: AssetLifecycleStatus;
}

export type DraftHoldings = Map<string, DraftHolding>;
