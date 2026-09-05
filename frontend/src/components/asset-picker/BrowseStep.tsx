"use client";

import { useMemo, useState } from "react";
import { useDraftPrices } from "@/lib/hooks/useDraftPrices";
import { computeEstimatedValue } from "@/lib/utils/quantityDisplay";
import type { CatalogAsset, DraftHoldings } from "@/types/assetPicker";
import { AssetSearchBar } from "./AssetSearchBar";
import { DraftRow } from "./DraftRow";
import { addOrUpdateDraftTicker, removeDraftTicker } from "./draftState";
import { buildBrowseRows } from "./browseRows";
import {
  validateDraftQuantity,
  validateRetainedDeprecatedQuantity,
} from "./quantityValidator";

/** Resolves a drafted ticker's unit price for display-only estimates (Task 9.3). */
function draftUnitPrice(
  price:
    | {
        currentPrice: number | null;
        priceUnavailable?: boolean;
      }
    | undefined,
): number | null {
  if (!price || price.priceUnavailable || price.currentPrice == null) {
    return null;
  }
  return price.currentPrice;
}

export interface BrowseStepProps {
  catalog: CatalogAsset[];
  draft: DraftHoldings;
  onDraftChange: (next: DraftHoldings) => void;
  /**
   * The quantity each held ticker carried when the modal opened — the reduce-or-
   * remove-only ceiling for a `Retained_Deprecated_Position` (requirements.md 2.4).
   * Deliberately distinct from `draft`'s own (mutating) quantity, so a user can't
   * ratchet a value back up by reducing then increasing within the same session.
   */
  initialQuantities: Map<string, string>;
  /** For Task 1.10's selected-asset pricing — prices are fetched only for these tickers. */
  token: string;
}

/**
 * B2 Task 1.7 — Browse: search, the asset list, and draft-row control semantics.
 * Task 1.8's validator and Task 1.9's duplicate prevention are wired in here.
 */
export function BrowseStep({
  catalog,
  draft,
  onDraftChange,
  initialQuantities,
  token,
}: BrowseStepProps) {
  const [search, setSearch] = useState("");
  const [errorsByTicker, setErrorsByTicker] = useState<Record<string, string>>({});

  const rows = useMemo(() => buildBrowseRows(catalog, draft, search), [catalog, draft, search]);

  // Task 1.10: prices fetched only for the draft's current tickers, never the full
  // browse list.
  const draftTickers = useMemo(() => Array.from(draft.keys()), [draft]);
  const pricesQuery = useDraftPrices(draftTickers, token);

  function handleToggle(ticker: string) {
    if (draft.has(ticker)) {
      onDraftChange(removeDraftTicker(draft, ticker));
      setErrorsByTicker((prev) => {
        if (!(ticker in prev)) return prev;
        const next = { ...prev };
        delete next[ticker];
        return next;
      });
      return;
    }

    const asset = catalog.find((entry) => entry.ticker === ticker);
    if (!asset) return;
    onDraftChange(
      addOrUpdateDraftTicker(draft, {
        ticker: asset.ticker,
        name: asset.name,
        assetClass: asset.assetClass,
        quantity: "",
        lifecycleStatus: asset.lifecycleStatus,
      }),
    );
  }

  function handleQuantityChange(ticker: string, value: string) {
    const row = rows.find((r) => r.ticker === ticker);
    const ceiling = initialQuantities.get(ticker);
    const isRetainedDeprecated = row?.lifecycleStatus === "DEPRECATED" && ceiling !== undefined;

    const result = isRetainedDeprecated
      ? validateRetainedDeprecatedQuantity(value, ceiling)
      : validateDraftQuantity(value);

    setErrorsByTicker((prev) => {
      if (result.valid) {
        if (!(ticker in prev)) return prev;
        const next = { ...prev };
        delete next[ticker];
        return next;
      }
      return { ...prev, [ticker]: result.message };
    });

    // Task 1.9: editing an already-drafted row updates it in place — never a second row.
    const catalogAsset = catalog.find((entry) => entry.ticker === ticker);
    onDraftChange(
      addOrUpdateDraftTicker(draft, {
        ticker,
        name: catalogAsset?.name ?? row?.name ?? ticker,
        assetClass: catalogAsset?.assetClass ?? row?.assetClass ?? "",
        quantity: value,
        lifecycleStatus: row?.lifecycleStatus ?? "ACTIVE",
      }),
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <AssetSearchBar value={search} onChange={setSearch} />
      {/* role="group", not "list" — each row is a compound control (checkbox +
          quantity input), not a role="listitem"; "list" requires only listitem
          children (axe aria-required-children). */}
      <div className="flex flex-col gap-2" role="group" aria-label="Assets">
        {rows.map((row) => (
          <DraftRow
            key={row.ticker}
            ticker={row.ticker}
            name={row.name}
            quantity={row.quantity}
            checked={row.checked}
            lifecycleStatus={row.lifecycleStatus}
            onToggle={handleToggle}
            onQuantityChange={handleQuantityChange}
            errorMessage={errorsByTicker[row.ticker]}
            estimatedValue={
              row.checked
                ? computeEstimatedValue(
                    row.quantity,
                    draftUnitPrice(pricesQuery.data?.get(row.ticker)),
                  )
                : null
            }
          />
        ))}
      </div>
    </div>
  );
}
