/**
 * B2 Task 1.12 — `ReviewStep`'s pure derivation.
 *
 * `diff(initialHoldings, draftHoldings)` → added/changed/removed/unchanged, computed
 * only from the two snapshots — no side effects, no network.
 */
import type { DraftHoldings } from "@/types/assetPicker";
import type { AssetHoldingDTO } from "@/types/portfolio";

export interface AddedRow {
  ticker: string;
  name: string;
  quantity: string;
}

export interface ChangedRow {
  ticker: string;
  name: string;
  fromQuantity: string;
  toQuantity: string;
}

export interface RemovedRow {
  ticker: string;
  name: string;
  quantity: string;
}

export interface UnchangedRow {
  ticker: string;
  name: string;
  quantity: string;
}

export interface HoldingsDiff {
  added: AddedRow[];
  changed: ChangedRow[];
  removed: RemovedRow[];
  unchanged: UnchangedRow[];
}

/**
 * Compares by the raw quantity string, not its numeric value (GC.2): "10" and
 * "10.00000000" denote the same magnitude but are a real edit to what the save
 * payload will carry, so they classify as changed, not unchanged.
 */
export function diffHoldings(
  initialHoldings: AssetHoldingDTO[],
  draft: DraftHoldings,
): HoldingsDiff {
  const initialByTicker = new Map(initialHoldings.map((h) => [h.ticker, h]));
  const diff: HoldingsDiff = { added: [], changed: [], removed: [], unchanged: [] };

  for (const entry of draft.values()) {
    const initial = initialByTicker.get(entry.ticker);
    if (!initial) {
      diff.added.push({ ticker: entry.ticker, name: entry.name, quantity: entry.quantity });
    } else if (initial.quantity !== entry.quantity) {
      diff.changed.push({
        ticker: entry.ticker,
        name: entry.name,
        fromQuantity: initial.quantity,
        toQuantity: entry.quantity,
      });
    } else {
      diff.unchanged.push({ ticker: entry.ticker, name: entry.name, quantity: entry.quantity });
    }
  }

  for (const initial of initialHoldings) {
    if (!draft.has(initial.ticker)) {
      diff.removed.push({
        ticker: initial.ticker,
        name: initial.name,
        quantity: initial.quantity,
      });
    }
  }

  return diff;
}
