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

/** Half a unit in the 4th decimal: the wire rounding of `change24hAbsolute`, per unit held. */
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
