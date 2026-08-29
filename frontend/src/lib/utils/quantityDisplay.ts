/**
 * B2 GC.2 display boundary — the single, explicitly named place where a domain quantity
 * string becomes a JavaScript number.
 *
 * Quantity is a string end-to-end (requirements.md 8.1): from the moment it is read until
 * it is either formatted for a human or submitted verbatim. Arithmetic is permitted only
 * after an explicit conversion here, and the converted number is never fed back into draft
 * state or a submit payload.
 *
 * `src/lib/utils/quantityDisplay.arch.test.ts` fails the build if arithmetic is applied to
 * a `quantity` field anywhere outside this module.
 */
import { formatQuantity } from "@/lib/utils/format";

/**
 * Converts a domain quantity string to a number for display-only arithmetic.
 *
 * Returns `null` — never `NaN` and never a fabricated `0` — when the string is not a
 * finite decimal, so callers render "unavailable" the same way an absent price does.
 */
export function quantityToDisplayNumber(quantity: string): number | null {
  const trimmed = quantity.trim();
  if (trimmed === "") return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

/**
 * Formats a domain quantity string for display, using the same locale formatting the
 * holdings table already applies to numeric values.
 *
 * A value that is not a finite decimal is rendered verbatim rather than as `NaN`.
 */
export function formatQuantityForDisplay(quantity: string): string {
  const parsed = quantityToDisplayNumber(quantity);
  return parsed === null ? quantity : formatQuantity(parsed);
}

/**
 * Numeric comparator for domain quantity strings.
 *
 * A plain `<`/`>` comparison on strings is lexicographic — it orders "10" before "9" —
 * so any sort over quantity has to come through this display boundary too. Values that
 * are not finite decimals sort last, in both directions.
 */
export function compareQuantityStrings(a: string, b: string): number {
  const left = quantityToDisplayNumber(a);
  const right = quantityToDisplayNumber(b);
  if (left === null && right === null) return 0;
  if (left === null) return 1;
  if (right === null) return -1;
  return left < right ? -1 : left > right ? 1 : 0;
}

/**
 * B2 Task 1.10 — computes a display-only estimated value (`quantity × price`) at the
 * GC.2 display boundary. Never fed back into draft state or a submit payload.
 *
 * Returns `null` — never a fabricated `0` — when the price is unavailable or the
 * quantity is not a finite decimal, matching how the rest of the app treats an
 * unavailable price (`@/lib/api/portfolio`'s `BackendMarketPrice.currentPrice`).
 */
export function computeEstimatedValue(quantity: string, price: number | null): number | null {
  if (price === null) return null;
  const quantityValue = quantityToDisplayNumber(quantity);
  if (quantityValue === null) return null;
  return quantityValue * price;
}
