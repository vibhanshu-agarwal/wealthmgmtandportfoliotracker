/**
 * B2 Task 1.7 — derives the Browse list's rows from the catalog and the current draft.
 *
 * requirements.md 2.2: only `Active_Asset` entries are offered for NEW selection; a
 * `Deprecated_Asset` not already held is never offered.
 * requirements.md 2.3: a held `Retained_Deprecated_Position` stays in the list.
 */
import type { AssetLifecycleStatus, CatalogAsset, DraftHoldings } from "@/types/assetPicker";

export interface BrowseRow {
  ticker: string;
  name: string;
  assetClass: string;
  quantity: string;
  checked: boolean;
  lifecycleStatus: AssetLifecycleStatus;
}

/** Undrafted default quantity for a newly-selectable catalog asset. */
const UNDRAFTED_QUANTITY = "";

export function buildBrowseRows(
  catalog: CatalogAsset[],
  draft: DraftHoldings,
  search: string,
): BrowseRow[] {
  const query = search.trim().toLowerCase();
  const matches = (ticker: string, name: string) =>
    query === "" || ticker.toLowerCase().includes(query) || name.toLowerCase().includes(query);

  const rows: BrowseRow[] = [];
  const seen = new Set<string>();

  // Every drafted ticker is shown — active or a retained deprecated position — since a
  // held position must stay visible regardless of the catalog's current offer state.
  for (const holding of draft.values()) {
    if (!matches(holding.ticker, holding.name)) continue;
    rows.push({
      ticker: holding.ticker,
      name: holding.name,
      assetClass: holding.assetClass,
      quantity: holding.quantity,
      checked: true,
      lifecycleStatus: holding.lifecycleStatus,
    });
    seen.add(holding.ticker);
  }

  // Active, undrafted catalog assets are offered for new selection. A deprecated,
  // unheld asset is never offered (requirements.md 2.2) — it simply doesn't appear.
  for (const asset of catalog) {
    if (seen.has(asset.ticker)) continue;
    if (asset.lifecycleStatus !== "ACTIVE") continue;
    if (!matches(asset.ticker, asset.name)) continue;
    rows.push({
      ticker: asset.ticker,
      name: asset.name,
      assetClass: asset.assetClass,
      quantity: UNDRAFTED_QUANTITY,
      checked: false,
      lifecycleStatus: asset.lifecycleStatus,
    });
  }

  return rows;
}
