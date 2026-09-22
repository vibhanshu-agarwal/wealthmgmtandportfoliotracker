# D11: position-level 24h value (finding F13) — design

**Date:** 2026-09-22
**Branch:** `claude/d11-24h-position-value`, local only, from `main@9a4603c245529b93942d3a6b9b85e886be08b5e0`
(the merge of PR #310).
**Owner approval, quoted:** "D11: Approved as the next separate bounded change for F13. [...] prepare
D11 from updated main using the additive backend contract and independent arithmetic/coverage tests
described in the reconciliation." The reconciliation is on an unpublished Codex branch; per the owner's
standing instruction this design does not read or depend on it. Codex should confirm the contract
below matches it.
**Not authorized:** publication, merge, deployment, workflow dispatch, Production access.
**Status:** implemented at `3db6321e`; evidence and review dispositions are in
`docs/superpowers/plans/2026-09-22-d11-24h-position-value-packet.md`.

## 1. The defect (F13)

`change24hAbsolute` in `GET /api/portfolio/analytics` is a **per-unit** price change in the holding's
**quote currency** (`currentPrice − price24hAgo`). The frontend treats it as a position amount in the
base currency:

| Surface | Today | Effect |
|---|---|---|
| Overview 24h card | `Σ change24hAbsolute` | 12 AAPL up $126.48 each shows +$126.48, not +$1,517.76 |
| Overview 24h percent | `Σ / (totalValue − Σ)` | Wrong numerator; denominator mixes units |
| Portfolio footer "24h" | `Σ change24hAbsolute` over the rows | Same as the card |
| Portfolio row 24h sub-line | per-unit change, formatted as "$" | Rows cannot add up to a position-level footer; INR shown as "$" |

The per-unit change is correct where it is labelled as a price change: Market Data shows each
ticker's price move. That stays.

## 2. Contract (additive, portfolio-service)

Existing fields keep their meaning. Three fields are added:

| Field | Where | Definition | Null when |
|---|---|---|---|
| `change24hValueBase` | each holding | `quantity × (currentPrice − price24hAgo) × fxRate(quoteCurrency → base)`, scale 4, HALF_UP | no reference price, no current price, or the FX rate is unavailable |
| `totalChange24hBase` | top level | `Σ change24hValueBase` over the holdings counted in `totalValue` | no counted holding has a `change24hValueBase` |
| `totalChange24hPercent` | top level | `totalChange24hBase / Σ(currentValueBase − change24hValueBase) × 100` over those same holdings, scale 4 | `totalChange24hBase` is null, or that prior value is ≤ 0 |

Decisions:
- **Same inclusion set as `totalValue`.** A holding excluded from `totalValue` (missing price, quote
  FX unavailable, or cost-basis FX unavailable) is excluded from both 24h totals, so the 24h figures
  describe the same holdings as the Portfolio Total.
- **Coverage.** A counted holding without a reference contributes to neither the 24h total nor the
  percent's denominator. The percent is the 24h return of the holdings that have a reference, not
  a figure diluted by holdings whose change is unknown. (Treating an unknown change as zero is the
  "invented zero" F1 removed.)
- **FX.** The current rate converts both endpoints, so the value reflects the price move only, not
  the FX move over the window. No historical FX rates exist to do better.
- **Reference basis.** Unchanged: a holding whose reference is older than the 36 h window
  (`changeBasis: SINCE_PREVIOUS_SNAPSHOT`) is still included, as it is today.
- **Totals equal the rows.** The totals sum the rounded per-holding values, so
  `totalChange24hBase == Σ change24hValueBase` exactly, as `totalValue == Σ currentValueBase`.
- **A zero reference price.** The existing per-holding `change24hPercent` reports 0 when the
  reference price is 0; `totalChange24hPercent` is null when the reference value is not positive.
  The per-holding behaviour predates D11 and is left unchanged.

## 3. Frontend

- Types: the three fields are **optional** (`?: number | null`). An older backend omits them, and the
  frontend treats absent as unavailable.
- Overview 24h card: amount = `totalChange24hBase`, percent = `totalChange24hPercent`. No client-side
  summing. Absent or null → "—".
- Portfolio rows: the sub-line under the percent shows the position's `change24hValueBase`. When it is
  unavailable, the row still shows the percent, without a sub-line. When the percent is unavailable,
  the cell shows "—" as today.
- Portfolio footer "24h": `Σ change24hValueBase` over the visible rows; "—" when none is available.
- Market Data: unchanged (per-unit price change).

**Deploy consequence (for the owner, A3/D3).** The frontend needs the new backend fields.
- **Deploy portfolio-service first, or together with the frontend.** A frontend-only deploy of this
  change shows "—" for the Overview 24h card, the Portfolio footer and the row sub-lines, instead of
  today's wrong figures. Row percentages are unaffected.
- **The Phase 3 suite now requires the D11 backend.** From this change on, S11 fails against any
  backend that omits `totalChange24hBase`: the independent recomputation has a value and the backend
  has none. This is deliberate (fail-closed), but it means the owner-operated Production run must not
  start until the D11 portfolio-service is serving in Production.

## 4. Tests

"Independent" means the expected values are computed by hand in the test, never by calling the code
under test or re-implementing its formula over its own output.

- **Backend unit** (`PortfolioAnalyticsServiceTest`), hand-computed fixtures:
  - 12 AAPL, 1,126.48 now, 1,000.00 reference → `change24hAbsolute` 126.4800 (unchanged per-unit
    contract), `change24hValueBase` 1,517.7600.
  - 5 RELIANCE.NS, ₹1,242.30 now, ₹1,200.00 reference, INR→USD 0.012 → 2.5380.
  - FX unavailable, no reference, no current price → null, and excluded from the totals.
  - A mixed portfolio with a holding that has no reference: totals and percent from hand arithmetic.
  - A holding excluded from `totalValue` by cost-basis FX is excluded from the 24h totals.
  - No counted holding with a change, and an empty portfolio → both totals null.
- **Backend integration** (`Wave6DashboardDataAccuracyIT`, real Postgres): 10 units, 100 → 110 →
  `change24hValueBase` 100.0000, and `totalChange24hBase` equals the sum of every counted holding's
  `change24hValueBase` (the dev user can hold other seeded holdings, so not 100 alone). Like the
  existing `totalValue` identity beside it, that sum cannot see the cost-basis-FX exclusion.
- **Frontend component:** the card shows the backend totals where they differ from the per-unit sum;
  "—" when the fields are absent (older backend) or null; row sub-lines and the footer from
  hand-computed position values; percent without a sub-line when the position value is unavailable.
- **Phase 3 suite (S11):**
  - the card must equal `totalChange24hBase`, and `totalChange24hBase` must equal an independent
    recomputation from primitive fields,
    `Σ quantity × change24hAbsolute × (currentValueBase / (quantity × currentPrice))`, within the
    wire rounding of the per-unit change; the ledger records those primitives;
  - each Portfolio row's sub-line must equal its `change24hValueBase` (none when null), and the
    footer must equal their sum;
  - the Portfolio row percent check no longer requires a non-null per-unit change.

## 5. Out of scope

Finding F14 (quote-currency prices labelled "$" on Market Data and the price column), F10 (sub-cent
"$0.00"), the reference-basis labelling, and any deployment.
