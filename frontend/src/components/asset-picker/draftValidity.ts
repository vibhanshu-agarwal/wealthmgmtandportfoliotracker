/**
 * B2 Task 1.8 (review-fix) — "Blocks progression/submission on failure" against B1's
 * Quantity_Domain and the retained-deprecated reduce-or-remove-only rule.
 *
 * Recomputes validity directly from the draft's own data rather than trusting
 * BrowseStep's local error-display state, so Review/Save can never be reached with an
 * invalid entry no matter which UI path produced it.
 */
import type { DraftHoldings } from "@/types/assetPicker";
import { validateDraftQuantity, validateRetainedDeprecatedQuantity } from "./quantityValidator";

export function isDraftValid(
  draft: DraftHoldings,
  initialQuantities: Map<string, string>,
): boolean {
  for (const entry of draft.values()) {
    const ceiling = initialQuantities.get(entry.ticker);
    const isRetainedDeprecated = entry.lifecycleStatus === "DEPRECATED" && ceiling !== undefined;

    const result = isRetainedDeprecated
      ? validateRetainedDeprecatedQuantity(entry.quantity, ceiling)
      : validateDraftQuantity(entry.quantity);

    if (!result.valid) return false;
  }
  return true;
}
