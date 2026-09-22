/**
 * D11 (finding F13): an independent recomputation of the analytics 24h total from primitive
 * payload fields, so S11 does not merely compare the card with the backend's own number.
 *
 * `change24hAbsolute` is a per-unit, quote-currency price change. `currentValueBase / currentPrice`
 * is quantity × FX rate, so each holding's base-currency change is
 * `change24hAbsolute × currentValueBase / currentPrice`. The backend's `change24hValueBase` is
 * deliberately not read.
 *
 * Limit: the backend also excludes a holding whose cost-basis currency has no FX rate (its value
 * and change are still on the wire). The payload does not mark that case, so such a holding makes
 * the recomputation disagree and the check fail, never pass.
 */

import { parseDisplayedMoney } from "./values";

/**
 * Half a unit in the 4th decimal: the wire rounding of `change24hAbsolute`, per unit held.
 * `currentValueBase` is also rounded to 4 decimals, which moves each holding's recomputed change
 * by at most 0.00005 × |change24hAbsolute| / currentPrice (larger than 0.00005 × |change24hPercent| / 100
 * when the price fell); the caller's extra cent of tolerance absorbs that for any realistic portfolio.
 */
const PER_UNIT_ROUNDING = 0.00005;

export interface Change24hInputs {
  readonly currentPrice: number | null;
  readonly currentValueBase: number | null;
  readonly change24hAbsolute: number | null;
}

export interface RecomputedChange24h {
  /** Null when no holding has a change, a value and a price. */
  readonly expected: number | null;
  /** How far `expected` can drift from the backend's exact figure through wire rounding. */
  readonly tolerance: number;
}

export function recomputeTotalChange24h(holdings: readonly Change24hInputs[]): RecomputedChange24h {
  let expected = 0;
  let tolerance = 0;
  let counted = 0;
  for (const h of holdings) {
    if (h.change24hAbsolute == null || h.currentValueBase == null || h.currentPrice == null || h.currentPrice === 0) continue;
    const unitsInBase = h.currentValueBase / h.currentPrice;
    expected += h.change24hAbsolute * unitsInBase;
    tolerance += PER_UNIT_ROUNDING * Math.abs(unitsInBase);
    counted += 1;
  }
  return { expected: counted === 0 ? null : expected, tolerance };
}

/**
 * Reads the position-level sub-line of a Portfolio 24h cell ("+12.65%+$1,517.76" → 1517.76).
 * Null when the cell shows only a percent, or the unavailable dash.
 */
export function parseChangeCellMoney(cellText: string): number | null {
  const text = cellText.trim();
  const percentEnd = text.indexOf("%");
  if (text === "—" || percentEnd === -1) return null;
  const subLine = text.slice(percentEnd + 1).trim();
  return subLine === "" ? null : parseDisplayedMoney(subLine);
}

export interface Change24hCoverageCounts {
  readonly holdingsWithChange: number;
  readonly totalHoldings: number;
  readonly partial: boolean;
}

/**
 * The 24h coverage the analytics payload must report, counted from its holdings rather than read
 * from its coverage field: a holding contributes when it has both a value and a position change.
 * The backend also leaves out a holding excluded for its cost-basis FX; the payload does not mark
 * that, so such a holding makes this disagree and the check fail, never pass.
 */
export function expectedChange24hCoverage(
  holdings: readonly { readonly currentValueBase: number | null; readonly change24hValueBase?: number | null }[],
): Change24hCoverageCounts {
  const holdingsWithChange = holdings.filter((h) => h.currentValueBase != null && h.change24hValueBase != null).length;
  return { holdingsWithChange, totalHoldings: holdings.length, partial: holdingsWithChange < holdings.length };
}

/** The Overview card's partial-coverage label. */
export function cardCoverageLabel(holdingsWithChange: number, totalHoldings: number): string {
  return `Partial: ${holdingsWithChange} of ${totalHoldings} holdings`;
}

/** The card label to expect: only for a shown total whose coverage is partial. */
export function expectedCardCoverageLabel(
  shownTotal: number | null,
  coverage: { readonly holdingsWithChange: number; readonly totalHoldings: number; readonly partial: boolean } | null | undefined,
): string | null {
  return shownTotal !== null && coverage?.partial ? cardCoverageLabel(coverage.holdingsWithChange, coverage.totalHoldings) : null;
}

/** The footer label to expect: only when some, but not all, visible rows have a position change. */
export function expectedFooterCoverageLabel(rowsWithChange: number, visibleRows: number): string | null {
  return rowsWithChange > 0 && rowsWithChange < visibleRows ? `Partial: ${rowsWithChange} of ${visibleRows}` : null;
}

/** The Portfolio footer's expected 24h total: Σ change24hValueBase, or null when none exists. */
export function sumPositionChanges(holdings: readonly { readonly change24hValueBase?: number | null }[]): number | null {
  const values = holdings.map((h) => h.change24hValueBase).filter((v): v is number => v != null);
  return values.length === 0 ? null : values.reduce((sum, v) => sum + v, 0);
}
