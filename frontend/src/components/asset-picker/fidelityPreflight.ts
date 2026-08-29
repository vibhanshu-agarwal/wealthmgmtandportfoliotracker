/**
 * B2 Task 1.5 — data-integrity preflight for `quantityFidelityUnverified`.
 *
 * One policy, chosen explicitly: strict preflight with immutable provenance, not
 * editable in-modal remediation. This check runs entirely inside
 * `EditHoldingsButton`'s own click handler, before `AssetPickerModal` is ever invoked.
 * This is the sole enforcement point — Task 1.13's save path needs no submit-time
 * recheck, because under this policy no unverified value can ever reach the draft.
 *
 * Independent of the feature flag: a backend rollback or stale environment can
 * reintroduce unverified data regardless of flag state, so this is the guard, not the
 * flag.
 */
import type { AssetHoldingDTO } from "@/types/portfolio";

/** True iff any holding in the source read carries `quantityFidelityUnverified: true`. */
export function hasUnverifiedFidelity(holdings: AssetHoldingDTO[]): boolean {
  return holdings.some((holding) => holding.quantityFidelityUnverified === true);
}
