# Wave 4: Dashboard Data Accuracy — Calculation Correctness

**Date:** 2026-06-10  
**Branch:** `feature/wave4-dashboard-data-accuracy`  
**Spec:** `.kiro/specs/dashboard-data-accuracy/tasks.md` — Task 5 (depends on Tasks 4 & 6)

---

## Summary

Wave 4 implements Task 5 of the dashboard-data-accuracy spec: **portfolio-service calculation correctness**. This wave makes the analytics endpoint return real P&L, honest 24h change with reference metadata, performance series coverage metadata, and canonical asset-class labels — replacing placeholders and misleading zeros throughout.

---

## Changes

### `portfolio-service`

#### New: `DisplayAssetClassMapper`
Maps raw seed/catalogue asset-class values (`US_EQUITY`, `NSE`, `CRYPTO`, `FOREX`, …) to canonical UI display values (`STOCK`, `CRYPTO`, `CASH`, `BOND`, `COMMODITY`, `OTHER`). Never silently defaults to `STOCK` — unknown classes map to `OTHER`.

#### `AnalyticsQueryRow` (internal projection)
Extended with two new fields:
- `price24hReferenceAt: Instant` — the `observed_at` of the history row that provided the 24h-ago reference, so callers can label the change basis accurately.
- `avgCostBasis: BigDecimal` + `costBasisCurrency: String` — nullable cost-basis fields fetched from `asset_holdings` so real P&L can be computed per holding.

#### `PortfolioAnalyticsDto` — contract changes
- **`HoldingAnalyticsDto`**: added `unrealizedPnLPercent` (nullable), `change24hReferenceAt` (nullable ISO-8601 string), `changeBasis` (nullable label), `displayAssetClass` (never null), `costBasisCurrency` (nullable). `unrealizedPnL` is now truly nullable (was always 0).
- **Top-level**: `totalUnrealizedPnL` and `totalUnrealizedPnLPercent` are now nullable (null when no holdings have a recorded cost basis — never coerced to 0). Added `performanceCoverage: PerformanceCoverageDto` with `holdingsWithHistory`, `totalHoldings`, `partial`, and `synthetic` flags.

#### `PortfolioAnalyticsService` — calculation correctness
**Task 5.1 — Real unrealised P&L:**
- Fetches `avg_cost_basis` and `cost_basis_currency` from `asset_holdings` in the SQL query.
- FX-converts cost basis using `cost_basis_currency` (which may differ from `quote_currency`) separately from the current-value conversion.
- Returns `null` P&L when `avg_cost_basis` is absent — never substitutes 0.
- Aggregate P&L is non-null when at least one holding has a basis.

**Task 5.2 — Tolerance-window 24h change:**
- Replaced the hard `now() - 24h` cutoff with an explicit `BETWEEN now() - 36h AND now() - 18h` window in the `price_24h` SQL CTE.
- Returns the reference `observed_at` timestamp alongside the change values.
- Adds `changeBasis` label: `WITHIN_24H_WINDOW` when reference falls in the tolerance band, `SINCE_PREVIOUS_SNAPSHOT` otherwise.
- `computeChange24hPercent` and `computeChange24hAbsolute` now return `null` when no reference exists (previously returned 0 — violating the "no silent zero" property).

**Task 5.3 — Performance series with coverage metadata:**
- Tracks which holdings have at least one history row in the requested period.
- Returns `PerformanceCoverageDto` with `holdingsWithHistory`, `totalHoldings`, `partial` (true when coverage < 100%), and `synthetic` (true when the series is a placeholder).
- A synthetic fallback is still generated when fewer than 7 distinct dates exist, but is now clearly labelled `synthetic=true` and `partial=true` in the coverage metadata.

**Task 5.4 — Canonical display asset class:**
- Injects `SeedTickerRegistry` to look up each holding's raw asset class.
- Maps via `DisplayAssetClassMapper` to the canonical display value.
- Unknown tickers (not in registry) → `OTHER`.

#### `PortfolioAnalyticsServiceTest`
Fully rewritten to match new constructor signatures. Added Task 5.5 test coverage:
- 52 unit tests (0 failures).
- Task 5.1: `noBasis_returnsNullPnL`, `withBasis_sameCurrency`, `negativePnL`, `crossCurrencyBasis_fxApplied`, `pnlIdentity`, `mixedHoldings`.
- Task 5.2: `noReferenceInWindow_changeIsNull`, `referenceInWindow_labeled`, `changeFormula`, `changeBasisLabel` parameterised.
- Task 5.3: `syntheticSeries_markedPartialAndSynthetic`, `realSeries_allCovered_notPartial`, `someHoldingsMissing_markedPartial`.
- Task 5.4: `usEquity_toStock`, `nse_toStock`, `crypto_toCrypto`, `forex_toCash`, `unknown_toOther`, plus a parameterised mapper test covering all values and null.

#### `PortfolioAnalyticsIntegrationTest`
- `performerOrderingInvariant`: guarded for null-change performers (correct for seeded data with no in-window history at test time).
- `pnlIdentity`: guarded for null `totalUnrealizedPnL` (non-null for seeded portfolios with cost basis from Task 4.2).

---

## Tests Run

| Suite | Tests | Failures | Errors |
|---|---|---|---|
| `PortfolioAnalyticsServiceTest` (unit) | 52 | 0 | 0 |
| `PortfolioAnalyticsIntegrationTest` (integration) | 8 | 0 | 0 |
| All other portfolio-service unit tests | passing | 0 | 0 |
| All other portfolio-service integration tests | passing | 0 | 0 |

---

## Non-changes (guardrails respected)

- No `min_replicas`, KEDA, Terraform, or cadence changes.
- No new Flyway migrations (all schema work was Wave 3).
- No vendor SDKs in domain logic.
- `SeedTickerRegistry` is an in-memory bean already present in the service — no new infrastructure.
- Backward-compatible: `PortfolioAnalyticsDto` is a response DTO; the new nullable fields are additive for JSON consumers.
