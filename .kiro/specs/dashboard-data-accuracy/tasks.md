# Implementation Plan

## Overview

Ordered so each phase builds on the previous. Backend contract first, then producers, then persistence/calculation, then consumers, then frontend, then end-to-end verification. Every behavioral change ships with tests. No task changes infrastructure (`min_replicas`, Terraform, KEDA, cadence).

## Task Dependency Graph

```json
{
  "waves": [
    { "wave": 1, "tasks": ["1"], "dependsOn": [] },
    { "wave": 2, "tasks": ["2", "3", "7"], "dependsOn": ["1"] },
    { "wave": 3, "tasks": ["4", "6", "8"], "dependsOn": ["2", "3"] },
    { "wave": 4, "tasks": ["5"], "dependsOn": ["4", "6"] },
    { "wave": 5, "tasks": ["9"], "dependsOn": ["2", "5", "6", "7"] },
    { "wave": 6, "tasks": ["10"], "dependsOn": ["8", "9"] }
  ]
}
```

- Task 1 gates everything (shared event shape).
- Tasks 2, 3, and 7 can run in parallel after 1 (7 is an independent cache-profile fix, not dependent on calculation work).
- **FX before calculations:** Task 6 (no-implicit-1.0 FX) lands before Task 5, so P&L/valuation/performance tests encode the corrected FX semantics rather than the legacy `BigDecimal.ONE` behavior.
- Task 8 depends on 2 (enriched event); Task 4 depends on 2→3; Task 5 depends on 4 and 6.
- Task 9 (frontend) depends on backend contracts from 2 and 5–7; task 10 is last.

## Tasks

- [x] 1. Extend the shared price contract (back-compatible)
  - [x] 1.1 Add nullable fields to `common-dto` `PriceUpdatedEvent`: `quoteCurrency`, `observedAt`, `previousReferencePrice`, `previousReferenceAt`; retain a 2-arg constructor/factory so existing call sites compile unchanged.
    - _Requirements: 2.2, 10.4_
  - [x] 1.2 Add back-compat (de)serialization tests: old-shape 2-field JSON and new-shape JSON both deserialize; missing new fields ⇒ null; assert no `FAIL_ON_UNKNOWN`/missing-field failure.
    - _Requirements: 10.4_
  - _Design: Key Design Decision (Option A), Property 9_

- [ ] 2. Enrich market-data-service price model and publishing
  - [ ] 2.1 Add `quoteCurrency`, `previousReferencePrice`, `previousReferenceAt` to `AssetPrice`; on persisting a **new observation** (new `observedAt`, price-changed or not) roll the prior current price/time into the reference fields.
    - _Requirements: 2.1, 2.2, 5.1_
  - [ ] 2.2 Populate `quoteCurrency` from seed/baseline metadata in `MarketPriceService`, `MarketDataRefreshJob`, and seeders; publish the enriched `PriceUpdatedEvent` (currency, `observedAt`, reference) from all publish sites.
    - _Requirements: 2.2, 5.1_
  - [ ] 2.3 Extend `MarketPriceDto` with `quoteCurrency`, `observedAt`, `previousReferencePrice`, `previousReferenceAt`, nullable `changeAbsolute`/`changePercent`, and `changeBasis`; derive change from current vs reference, null when no reference.
    - _Requirements: 2.1, 2.3, 2.4, 9.1, 10.6_
  - [ ] 2.4 Fix the `MarketPriceController` price cap: accept the full requested set (preferred) or return `400`/explicit truncation metadata; remove the silent `.limit(25)` drop. For requested tickers absent from storage, return an explicit unavailable DTO row / availability marker (do not omit them). Use true `observedAt`, never now() for missing data.
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 8.4_
  - [ ] 2.5 Unit/WireMock tests: reference roll-forward (incl. unchanged price), currency populated, no silent truncation, change null without reference.
    - _Requirements: 1.1, 1.5, 2.1, 2.3_
  - _Design: Components §2, Property 3, Property 4_

- [ ] 3. Portfolio-service schema migrations (additive, idempotent)
  - [ ] 3.1 `V11__Add_Cost_Basis_To_Holdings.sql`: nullable `avg_cost_basis`, `cost_basis_currency`, `cost_basis_source`, `cost_basis_as_of` on `asset_holdings`.
    - _Requirements: 3.5, 10.5_
  - [ ] 3.2 `V12__Backfill_Market_Price_History.sql`: backfill history for all 160 canonical tickers (registry base prices, canonical symbols incl. `BTC-USD`, `quote_currency`, windowed points), idempotent, without rewriting `V2`. **Data source must be explicit:** either (a) generate the SQL migration from `config/seed-tickers.json` with the 160 ticker rows + quote currencies embedded, or (b) implement a Java-based Flyway migration that reads the copied seed resource. Prefer (a) generated-SQL to match the repo's SQL-migration convention.
    - _Requirements: 2.2, 10.5, 10.7_
  - [ ] 3.3 `V13__Market_Price_History_Dedup.sql`: uniqueness/index on `(ticker, observed_at)` to keep forward-append idempotent.
    - _Requirements: 2.2, 10.5_
  - [ ] 3.4 Migration tests: apply cleanly on a seeded DB; re-running backfill is a no-op.
    - _Requirements: 10.5, 10.7_
  - _Design: Data Models, Property 6_

- [ ] 4. Portfolio-service history append and cost-basis capture
  - [ ] 4.1 Update `MarketPriceProjectionService` to append `market_price_history` for every new `(ticker, observed_at)` (including unchanged price), keyed/deduped by observation identity, not price value.
    - _Requirements: 2.2_
  - [ ] 4.2 Capture cost basis at add-time in `addHolding` when a current price exists; seed deterministic non-trivial `avg_cost_basis` per holding and ensure 160-ticker history coverage in `PortfolioSeedService`.
    - _Requirements: 3.1, 3.3, 3.4, 3.5_
  - [ ] 4.3 Integration tests (Testcontainers Postgres): idempotent append (no dup rows on replay; new timestamp same price = new row); seed produces non-trivial basis + full history coverage.
    - _Requirements: 2.2, 3.4_
  - _Design: Components §3, Property 6, Property 7_

- [ ] 5. Portfolio-service calculation correctness (depends on Task 6 FX semantics)
  - [ ] 5.1 Compute unrealized P&L from real `avg_cost_basis` — converting cost basis from `cost_basis_currency` to base currency and current value from quote currency to base currency (do not assume they are equal). Return nullable/"unavailable" when basis absent — never 0. Apply consistently to per-holding, aggregate, and all-time return.
    - _Requirements: 3.1, 3.2, 3.3, 10.6_
  - [ ] 5.2 Compute 24h/since-snapshot change from `market_price_history` using an explicit tolerance window (≈18–36h); return nullable change + reference timestamp + `changeBasis` label.
    - _Requirements: 2.1, 2.3, 2.6, 9.1_
  - [ ] 5.3 Build performance series from complete FX-converted history across all holdings; when some holdings lack history, return coverage metadata and mark partial — never present a partial subset or synthetic curve as full-portfolio.
    - _Requirements: 2.7, 10.6_
  - [ ] 5.4 Expose canonical display asset class (mapping `US_EQUITY/NSE/CRYPTO/FOREX/…` → `STOCK/ETF/CRYPTO/BOND/CASH/COMMODITY/OTHER`) on the analytics/holdings contract.
    - _Requirements: 4.1, 4.3_
  - [ ] 5.5 Unit tests for P&L (real/null), change (window/null/label), performance (complete/partial+coverage), and asset-class mapping.
    - _Requirements: 2.6, 2.7, 3.2, 4.1_
  - _Design: Components §3, Property 2, Property 4_

- [ ] 6. Consistent FX behavior (no implicit 1.0) — land before Task 5
  - [ ] 6.1 Make `EcbFxRateProvider` stop returning `BigDecimal.ONE` / USD-only 1:1 fallback for non-equal currencies; surface unavailable (typed result / `FxRateUnavailableException`) consistent with `StaticFxRateProvider`.
    - _Requirements: 5.2, 5.3_
  - [ ] 6.2 Ensure valuations/aggregates expose partial-availability when a rate is unavailable rather than silently undercounting; equal-currency still returns 1.
    - _Requirements: 5.1, 5.3, 10.6_
  - [ ] 6.3 Tests proving no 1.0 substitution for non-equal currencies on the aws/azure provider; equal-currency = 1; aggregate partial-availability surfaced.
    - _Requirements: 5.3_
  - _Design: Error Handling, Property 5_

- [ ] 7. Analytics cache freshness on Azure
  - [ ] 7.1 Add an `azure`-profile Caffeine cache-manager bean (30s TTL) in `CacheConfig`; ensure every runtime profile (`local`, `aws`, `azure`) has an explicit-expiry manager.
    - _Requirements: 6.1, 6.3_
  - [ ] 7.2 Test: on the azure profile, cached analytics expires within TTL and reflects updated underlying data after the window.
    - _Requirements: 6.1, 6.2_
  - _Design: Components §3, Property 8_

- [ ] 8. Insight-service honest trend
  - [ ] 8.1 Key the Redis window by observation identity `(ticker, observedAt)` (ignore replays of same identity; accept new timestamp with unchanged price); consume enriched event in `InsightEventListener`.
    - _Requirements: 7.1, 7.4_
  - [ ] 8.2 Require ≥2 distinct-`observedAt` observations for trend; otherwise null/"trend not available" (not 0.00%).
    - _Requirements: 7.1, 7.2_
  - [ ] 8.3 Filter stale tickers by ZSET score on read in `getMarketSummary`, not only prune on write.
    - _Requirements: 7.3_
  - [ ] 8.4 Tests (Testcontainers Redis): replay dedup by identity; unchanged-price new snapshot valid; <2 distinct ⇒ null; stale excluded on read.
    - _Requirements: 7.1, 7.2, 7.3_
  - _Design: Components §4, Property 7_

- [ ] 9. Frontend rendering and wiring
  - [ ] 9.1 Add nullable/unavailable rendering helpers in `format.ts` ("—") distinct from `$0.00` / `0.00%`.
    - _Requirements: 8.3, 10.6_
  - [ ] 9.2 Batch `loadMarketPrices` into ≤25-ticker chunks, merge by ticker; missing ⇒ explicit unavailable (never `currentPrice = 0`, never `lastUpdatedAt = now()`).
    - _Requirements: 1.1, 1.2, 1.3, 8.4_
  - [ ] 9.3 Refactor `fetchPortfolio` to consume backend analytics for per-holding P&L, change, cost basis, asset class, and FX-converted base-currency values; remove client-side currency mixing and hardcoded zeros.
    - _Requirements: 2.4, 3.1, 4.1, 5.1, 5.2_
  - [ ] 9.4 Bind `SummaryCards` "24h Profit/Loss" and "all-time return" to backend analytics (nullable-aware), not the synthetic summary.
    - _Requirements: 8.2, 8.3_
  - [ ] 9.5 Allocation: use backend canonical asset class, "Other" for unknown, percentages from FX-converted complete values reconciling to the portfolio total.
    - _Requirements: 1.4, 4.1, 4.2, 4.3_
  - [ ] 9.6 `PerformanceChart`: render backend series; label partial/unavailable per coverage metadata; no synthetic curve as real.
    - _Requirements: 2.7, 8.3_
  - [ ] 9.7 Replace `PortfolioTicker` mock with real market/portfolio data, or hide the component if no real feed is ready; never show mock financial values.
    - _Requirements: 8.1_
  - [ ] 9.8 Freshness + identity: show as-of timestamp/relative age from real `updatedAt`/`observed_at`; portfolio name from backend or neutral label; stale rendered as stale, not error.
    - _Requirements: 8.5, 9.1, 9.2, 9.3_
  - [ ] 9.9 Vitest + MSW tests: >25-ticker batching with no dropped/zeroed holdings; allocation reconciles with total; typed-unavailable renders "—"; ticker tape wired/hidden; performance partial labelling; missing price shows "—".
    - _Requirements: 1.1, 1.4, 8.1, 8.3, 8.4_
  - _Design: Components §5, Property 1, Property 2, Property 3_

- [ ] 10. End-to-end verification
  - [ ] 10.1 Cross-service integration test: enriched event → projection append → analytics change/P&L → frontend contract, covering complete pricing and reconciliation (Total = Σ holdings = allocation total).
    - _Requirements: 1.4, 2.1, 3.1_
  - [ ] 10.2 Verify the full build/test suite (`./gradlew check`, frontend `npm run test`) is green; clean up any temporary artifacts.
    - _Requirements: 10.3_
  - _Design: Correctness Properties (all), Requirements Traceability_

## Notes

- **No infrastructure changes.** Tasks must not alter `min_replicas`, Terraform, KEDA scaling, ACA Jobs, external pingers, or the refresh cadence (deferred per owner; separate impact analysis required).
- **Back-compat is mandatory** for the `PriceUpdatedEvent` change (Task 1): old-shape events on the Kafka topic must continue to process and must not be routed to the DLT for missing new fields.
- **Typed unavailable, not zero:** wherever a metric can be absent, use nullable/status — never coerce to `0` or `now()`.
- **FX behavior change is intentional:** during an FX outage, affected base-currency valuations show "unavailable" rather than converting 1:1. Last-known-good FX is a deferred, non-normative future enhancement (see design Error Handling).
- **Testing conventions:** integration tests use `@Tag("integration")` + Testcontainers; frontend uses Vitest + MSW. Run `./gradlew check` and frontend `npm run test` before marking Task 10 done.
- **Migrations** are additive and idempotent; do not rewrite existing migration history (e.g. `V2`).
- **No receive-time fabrication:** a missing `observedAt` on an old-shape event must NOT create a history row or an insight observation; it yields unavailable change/trend. Receive time is for logging/current-row timestamps only.
- **Normalize `observedAt` precision** (ms or DB-supported µs) before using `(ticker, observedAt)` as an idempotency/identity key — applies to `market_price_history` uniqueness, Redis observation identity, and replay-dedup tests.
