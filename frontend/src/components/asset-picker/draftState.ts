/**
 * B2 Task 1.6 — draft state: `Map<ticker, DraftHolding>`, seeded per GC.1 at open time.
 *
 * Pure functions only; the modal owns the actual `useState`. No global picker store
 * (per the kickoff's scope boundary) — state lives with the modal instance.
 */
import type { AssetHoldingDTO } from "@/types/portfolio";
import type { CatalogAsset, DraftHolding, DraftHoldings } from "@/types/assetPicker";

/**
 * GC.1 — the draft always opens fully seeded: every held ticker present and selected
 * from the start, never an empty set the user builds up.
 *
 * Precondition (enforced by the caller, Task 1.5): every holding passed in has already
 * been confirmed verified — this function does not itself check
 * `quantityFidelityUnverified`, since 1.5's preflight refuses to open the modal at all
 * when any holding is unverified, making an unverified seed unreachable here.
 */
export function seedDraftFromHoldings(
  holdings: AssetHoldingDTO[],
  catalog: CatalogAsset[],
): DraftHoldings {
  const catalogByTicker = new Map(catalog.map((asset) => [asset.ticker, asset]));
  const draft: DraftHoldings = new Map();

  for (const holding of holdings) {
    const catalogEntry = catalogByTicker.get(holding.ticker);
    draft.set(holding.ticker, {
      ticker: holding.ticker,
      name: catalogEntry?.name ?? holding.name,
      assetClass: catalogEntry?.assetClass ?? holding.assetClass,
      quantity: holding.quantity,
      source: "held",
      // A held ticker missing from the catalog is unknown, not confirmed deprecated —
      // default to ACTIVE rather than asserting a lifecycle status the catalog never sent.
      lifecycleStatus: catalogEntry?.lifecycleStatus ?? "ACTIVE",
    });
  }

  return draft;
}

interface DraftTickerInput {
  ticker: string;
  name: string;
  assetClass: string;
  quantity: string;
  lifecycleStatus: DraftHolding["lifecycleStatus"];
}

/**
 * Task 1.9 — duplicate-ticker prevention: selecting an already-drafted ticker edits its
 * existing row rather than adding a second one. Pure — returns a new Map.
 *
 * An edit to an existing row keeps that row's original `source` (a held row being
 * re-quantified is still "held", not reclassified as newly "added").
 */
export function addOrUpdateDraftTicker(
  draft: DraftHoldings,
  input: DraftTickerInput,
): DraftHoldings {
  const next = new Map(draft);
  const existing = next.get(input.ticker);
  next.set(input.ticker, {
    ticker: input.ticker,
    name: input.name,
    assetClass: input.assetClass,
    quantity: input.quantity,
    lifecycleStatus: input.lifecycleStatus,
    source: existing?.source ?? "added",
  });
  return next;
}

/**
 * requirements.md 1.3 — deselecting a held asset means removing it from the desired set
 * entirely; there is no separate "remove" affordance beyond deselection. Pure — returns
 * a new Map; a ticker not present is a no-op, not an error.
 */
export function removeDraftTicker(draft: DraftHoldings, ticker: string): DraftHoldings {
  if (!draft.has(ticker)) return draft;
  const next = new Map(draft);
  next.delete(ticker);
  return next;
}
