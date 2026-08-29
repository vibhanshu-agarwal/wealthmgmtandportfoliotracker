/**
 * B2 GC.3 — the `PUT /api/portfolio/holdings` payload always carries the complete
 * desired set, never a diff (design.md D2).
 */
import type { DraftHoldings } from "@/types/assetPicker";

export interface SaveHolding {
  ticker: string;
  quantity: string;
}

export interface SavePayload {
  expectedVersion: number;
  holdings: SaveHolding[];
}

/**
 * Builds the save payload from the full draft — never a subset of it. `expectedVersion`
 * is the value observed when the modal opened (or last reconciled with the server),
 * never re-read here (GC.6).
 */
export function buildSavePayload(draft: DraftHoldings, expectedVersion: number): SavePayload {
  return {
    expectedVersion,
    holdings: Array.from(draft.values(), (entry) => ({
      ticker: entry.ticker,
      quantity: entry.quantity,
    })),
  };
}
