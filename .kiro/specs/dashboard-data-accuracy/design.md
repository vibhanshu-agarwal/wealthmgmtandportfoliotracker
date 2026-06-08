# Design Document

Dashboard Data Accuracy & Freshness.

## Overview

This design makes the dashboard render correct, internally consistent, and honestly-labelled data, fixing the root causes in `docs/audit/DATA_STALENESS_AND_CALCULATION_AUDIT_2026-06-07.md` (rev2) against the requirements in `requirements.md`. It works **entirely within the existing infrastructure** — no change to `min_replicas`, Terraform, KEDA, or the refresh cadence.

The design rests on four pillars:

1. **A narrow, backward-compatible contract extension** so the market-data path can carry a quote currency, an observation timestamp, and a reference price — the data needed to compute honest 24h/since-snapshot change and to feed insight trends without cross-service DB access.
2. **Runtime + backfill price history** so `market_price_history` is populated for all 160 tickers and grows forward, making change and performance series real.
3. **Typed "unavailable" semantics** end-to-end (nullable fields / status), so the UI shows "—" instead of a misleading `+$0.00` / `+0.00%`.
4. **Correct calculation ownership**: the backend owns FX conversion, cost-basis-driven P&L, asset-class mapping, and analytics freshness (cache TTL); the frontend stops mocking, stitching, and currency-mixing.

The guiding principle throughout: compute correctly where data exists; label honestly where it does not.

## Architecture

### Current data flow (with break points)

```
Yahoo (daily, dormant under scale-to-zero)
        │
        ▼
market-data-service ──► AssetPrice (Mongo: ticker, currentPrice, updatedAt)   ◄── no quoteCurrency, no reference
        │
        ▼  PriceUpdatedEvent(ticker, newPrice)                                 ◄── no currency/timestamp/reference
   Kafka "market-prices"
        ├──────────────► portfolio-service: market_prices (current only)       ◄── never writes market_price_history
        └──────────────► insight-service:   Redis window (≤1 distinct point)   ◄── trend null/0
```

### Target data flow

```
market-data-service ──► AssetPrice (Mongo: + quoteCurrency, + previousReferencePrice, + previousReferenceAt)
        │
        ▼  PriceUpdatedEvent(ticker, newPrice, quoteCurrency, observedAt, previousReferencePrice, previousReferenceAt)
   Kafka "market-prices"
        ├──► portfolio-service:
        │       market_prices (current, + quote_currency already present)
        │       market_price_history  ◄── APPEND on every projection update  + one-time BACKFILL (160 tickers)
        │       asset_holdings         ◄── + avg_cost_basis, cost_basis_currency, cost_basis_source, cost_basis_as_of
        │
        └──► insight-service:
                Redis window keyed by (ticker, observedAt) ◄── distinct timestamped observations → honest trend
```

### Service responsibilities (unchanged boundaries)

- **market-data-service** — owns market prices + quote currency + reference price. Computes/derives a reference (previous observation) so it can represent its own change. Publishes enriched events.
- **portfolio-service** — owns holdings, cost basis, valuations, FX conversion, analytics, and `market_price_history` (append + backfill). The only place P&L and portfolio performance are computed.
- **insight-service** — owns the Redis trend window; builds trend from distinct timestamped observations carried on the event.
- **frontend** — renders backend-computed values; batches price requests; renders typed-unavailable states; no FX math, no mocks.

## Key Design Decision: Market change contract

### Option A — Extend market-data/event contracts (RECOMMENDED)

Extend the shared `common-dto` event `PriceUpdatedEvent` and the market-data REST DTO `MarketPriceDto` to carry quote currency, observation timestamp, and a previous reference price.

**Pros**
- Lets `GET /api/market/prices` honestly serve change/reference for the Market Data page (R2 AC4).
- Gives `portfolio-service` what it needs to append `market_price_history` with currency + timestamp.
- Gives `insight-service` timestamped observations for honest trend **without cross-service DB access** (R7 AC4).
- Makes reference timestamps explicit; supports "since previous snapshot" labelling (R2 AC6).
- Avoids frontend stitching/calculation; aligns with typed-unavailable contract (R10 AC6).

**Cons**
- Coordinated change across producer + 2 Kafka consumers.
- Requires backward-compatible event evolution (old messages on the topic during rollout).

### Option B — Compute change only in `portfolio-service` from `market_price_history`

**Pros**
- Smaller shared-event change; reuses existing analytics SQL.

**Cons**
- `GET /api/market/prices` still cannot honestly serve change → Market Data page stays wrong or requires frontend stitching.
- Leaves the insight trend path unresolved (no timestamped observations).
- Entrenches `portfolio-service` as a hidden source of market history for a market-facing UI.
- Does not address the audit finding that the market-data path cannot represent its own change/reference.

### Decision

**Choose Option A.** It is the only option that satisfies R2, R7, R8, and R10 cleanly while keeping every service within its own boundary and avoiding frontend-side financial math. Option B is explicitly rejected for this phase because it leaves the market-data API and the insight trend path unsolved.

### Backward-compatibility rules (mandatory for Option A)

- New event fields are **nullable/defaultable**; the existing 2-arg construction path is preserved via a secondary constructor or static factory so current producer call sites compile and behave.
- Consumers (`PriceUpdatedEventListener`, `InsightEventListener`) **tolerate missing** `quoteCurrency`/`observedAt`/reference fields (treat as null → "unavailable"), and SHALL NOT route old-shape events to the DLT for missing new fields.
- **No receive-time fabrication:** when `observedAt` is missing, consumers MAY update the latest/current price, but SHALL NOT append a `market_price_history` row, SHALL NOT add an insight trend observation, and SHALL produce unavailable change/trend metadata. Receive time may be used only for operational logging or current-row update timestamps that make no user-facing freshness claim.
- `JacksonJsonSerializer`/deserialization must accept old-shape JSON (no `FAIL_ON_UNKNOWN`/missing-field failures); validation in `PriceUpdatedEventListener` continues to gate only `ticker` + positive `newPrice`.
- Tests cover deserializing **both** old-shape and new-shape payloads.

## Components and Interfaces

### 1. Shared contract — `common-dto`

`PriceUpdatedEvent` (record) extended with nullable fields; legacy factory retained:

```java
public record PriceUpdatedEvent(
        String ticker,
        BigDecimal newPrice,
        String quoteCurrency,            // nullable during rollout; "USD" default applied by producer
        Instant observedAt,              // nullable during rollout; producer sets it. Consumers treat missing observedAt as unavailable for history/trend/change and MUST NOT synthesize an observation from receive time.
        BigDecimal previousReferencePrice, // nullable; null ⇒ change unavailable
        Instant previousReferenceAt        // nullable
) {
    // Backward-compatible construction for legacy call sites/tests.
    public PriceUpdatedEvent(String ticker, BigDecimal newPrice) {
        this(ticker, newPrice, null, null, null, null);
    }
}
```

Change is intentionally **not** precomputed in the event — consumers derive it from `newPrice` and `previousReferencePrice`. This keeps the event a minimal data carrier and avoids divergent change math.

### 2. market-data-service

- **`AssetPrice` (Mongo)** — add `quoteCurrency`, `previousReferencePrice`, `previousReferenceAt`. When a **new observation** is persisted (a new `observedAt`, regardless of whether the price changed), the prior current price/time roll forward to become the reference — so the service always has a previous-observation reference even under daily refresh, and an unchanged price still produces an honest `0.00%` change rather than "no reference".
- **`MarketPriceDto`** — extended for UI rendering (see Data Models). Nullable `changeAbsolute`/`changePercent` derived from current vs reference; null when no reference.
- **`MarketPriceService` / `MarketDataRefreshJob` / seeders** — populate `quoteCurrency` (from `SeedAsset`/baseline metadata) and roll the reference forward on each update; publish enriched `PriceUpdatedEvent`.
- **`MarketPriceController`** —
  - `GET /api/market/prices`: stop silently dropping over-limit tickers. Either accept the full set (preferred — 160 small rows is a tiny payload) or return `400` with an explicit message. Remove the silent `.limit(25)` truncation; if a cap is retained for payload safety, return explicit truncation metadata (R1 AC5).
  - Response carries change/reference/currency fields with typed-unavailable semantics.

### 3. portfolio-service

- **`AssetHolding` + migration** — add nullable `avg_cost_basis`, `cost_basis_currency`, `cost_basis_source`, `cost_basis_as_of`. `addHolding` captures cost basis at add-time when a current price exists.
- **`MarketPriceProjectionService`** — in addition to upserting `market_prices`, **append** a row to `market_price_history` (ticker, quote_currency, price, observed_at) for **every new observation**, i.e. every distinct `(ticker, observed_at)` — *including* observations where the price is unchanged (an unchanged daily snapshot is a real data point). Idempotency is keyed on `(ticker, observed_at)`: a duplicate delivery of the **same** observation is a no-op; a new `observed_at` with an identical price is a valid new row. Dedup is by observation identity, never by price value.
- **`PortfolioAnalyticsService`** —
  - P&L uses real `avg_cost_basis` (FX-converted); when absent → null/unavailable (not 0).
  - 24h/since-snapshot change uses `market_price_history` with an explicit tolerance window (≈18–36h); returns nullable change + the reference timestamp; labels semantics accordingly.
  - Performance series: build from complete FX-converted history across **all** holdings; if some holdings lack history, return coverage metadata and mark partial rather than presenting a partial subset as the whole. Remove/avoid the synthetic series as a "real" representation.
- **Asset-class mapping** — expose canonical display asset class (mapping `US_EQUITY/NSE/CRYPTO/FOREX/…` → `STOCK/ETF/CRYPTO/BOND/CASH/COMMODITY/OTHER`) via the analytics/holdings contract so the frontend never infers it.
- **`CacheConfig`** — add an `azure`-profile cache-manager bean (Caffeine, 30s TTL — in-process, no infra) so analytics expires on Azure. Ensure every runtime profile has an explicit-expiry manager (R6).

### 4. insight-service

- **`MarketDataService` (Redis)** —
  - Key the sliding window by **observation identity `(ticker, observedAt)`**, not by price value. A replayed event with the same `(ticker, observedAt)` is ignored; a new `observedAt` with an identical price is a valid distinct observation (so an unchanged real snapshot can honestly yield `0.00%`). This prevents replayed startup hydration of the *same* stored observation from fabricating a trend, without suppressing legitimate unchanged-price snapshots.
  - `calculateTrend` requires ≥2 observations with **distinct `observedAt`**; otherwise trend = unavailable (null), surfaced as "trend not available" not `+0.00%`.
  - `getMarketSummary` **filters stale tickers by ZSET score on read**, not only pruning on write.
- **`InsightEventListener`** — consume the enriched event; store the timestamped observation. No cross-service DB access; the event is the contract (R7 AC4).

### 5. frontend

- **`loadMarketPrices`** — dedupe tickers, **batch in chunks of ≤25** (or the negotiated cap), fetch concurrently, merge by ticker. Missing tickers → explicit `priceUnavailable`, never `currentPrice = 0`; never set `lastUpdatedAt = now()` for missing prices.
- **`fetchPortfolio`** — stop hardcoding `change/unrealizedPnL/costBasis`. Consume backend analytics for per-holding P&L, change, cost basis, asset class, and FX-converted values. Remove client-side currency-mixing; rely on backend base-currency values.
- **`SummaryCards`** — bind "24h Profit/Loss" and "all-time return" to backend analytics (nullable-aware), not the synthetic summary.
- **Allocation** — use backend canonical asset class; unknown → "Other"; percentages from FX-converted complete values.
- **`PerformanceChart`** — render backend series; if partial/unavailable per coverage metadata, label it (no synthetic curve presented as real).
- **`PortfolioTicker`** — remove `MOCK_TICKER`; wire to real market/portfolio data (holdings by value/daily-move) or hide the component if no real feed is ready.
- **Freshness** — show as-of timestamp/relative age from real `updatedAt`/`observed_at`; show "unavailable"/"—" for typed-null metrics; portfolio name from backend or a neutral label.
- **`format.ts`** — add helpers for nullable/unavailable rendering ("—") distinct from `$0.00` / `0.00%`.

## Data Models

### `MarketPriceDto` (market-data REST)

```java
public record MarketPriceDto(
        String ticker,
        BigDecimal currentPrice,        // nullable ⇒ price unavailable
        String quoteCurrency,
        Instant observedAt,             // true last-update; never now() for missing data
        BigDecimal previousReferencePrice, // nullable
        Instant previousReferenceAt,       // nullable
        BigDecimal changeAbsolute,      // nullable ⇒ change unavailable
        BigDecimal changePercent,       // nullable ⇒ change unavailable
        String changeBasis              // e.g. "SINCE_PREVIOUS_SNAPSHOT" | "WITHIN_24H_WINDOW" | null
) {}
```

### Flyway migrations (portfolio-service, additive + idempotent)

- **`V11__Add_Cost_Basis_To_Holdings.sql`** — add nullable `avg_cost_basis NUMERIC(19,4)`, `cost_basis_currency VARCHAR(10)`, `cost_basis_source VARCHAR(32)`, `cost_basis_as_of TIMESTAMP` to `asset_holdings`.
- **`V12__Backfill_Market_Price_History.sql`** — backfill `market_price_history` for all 160 canonical tickers (from the seed registry's base prices), canonical symbols incl. `BTC-USD`, with `quote_currency`, generated points so a 24h/window reference exists. Idempotent (`WHERE NOT EXISTS`/`ON CONFLICT`); does **not** rewrite `V2`.
- **`V13__Market_Price_History_Dedup.sql`** — add a uniqueness/dedup constraint or supporting index on `(ticker, observed_at)` to keep forward-append idempotent.

**`observedAt` precision:** because `observedAt` flows through Java `Instant` → Postgres `TIMESTAMP` → JSON → Redis identity keys, normalize it to a single consistent precision (milliseconds, or DB-supported microseconds) **before** using `(ticker, observedAt)` as an idempotency/identity key. This applies to `market_price_history` uniqueness, the Redis observation identity, and replay-dedup tests, to avoid false duplicates or false distinctness from precision drift.

Golden-state seed (`PortfolioSeedService`) also: (a) seeds deterministic non-trivial `avg_cost_basis` per holding (e.g. seed price ±5–20% deterministic from `(ticker,userId)` hash), and (b) ensures history coverage for all 160 tickers.

### Cost-basis seed semantics

Deterministic, e.g. `avgCostBasis = basePrice × (1 + signedJitter((ticker,userId), ±0.20))`, reusing the existing `DeterministicPriceCalculator` style so P&L is stable, non-trivial, and reproducible across services.

## Correctness Properties

Invariants the implementation must uphold (and that tests assert):

### Property 1: Total reconciliation
For a given portfolio at a given time, the Portfolio Total, the sum of per-holding base-currency values, and the Asset Allocation total are equal within rounding tolerance (all derived from the same complete, FX-converted price set).

**Validates: Requirements 1.4, 4.2, 5.1**

### Property 2: No silent zero
A metric is `0` only when it is genuinely zero; absence is always represented as `null`/unavailable, never coerced to `0` or `now()`.

**Validates: Requirements 1.3, 2.3, 3.2, 8.3, 8.4, 10.6**

### Property 3: Completeness of pricing
Every requested ticker appears in the price response as either a real price or an explicit unavailable marker; none are silently dropped.

**Validates: Requirements 1.1, 1.2, 1.5**

### Property 4: Reference honesty
A value is labelled "24h change" only if its reference observation falls within the ~24h tolerance window; otherwise it is labelled by the actual reference used, and a reference timestamp is always returned.

**Validates: Requirements 2.1, 2.3, 2.6, 9.1**

### Property 5: FX consistency
`getRate(c, c) == 1`; for `c1 ≠ c2`, the rate is either a resolved market rate or an explicit unavailable result — never an implicit `1.0`. All `FxRateProvider` implementations share this behavior.

**Validates: Requirements 5.1, 5.2, 5.3**

### Property 6: Idempotent history
Appending a price observation that already exists for `(ticker, observed_at)` is a no-op; re-running the backfill migration changes nothing.

**Validates: Requirements 2.2, 10.5, 10.7**

### Property 7: Distinct-observation trend
Insight trend is non-null only when ≥2 observations with **distinct `observedAt`** exist. Dedup is by observation identity `(ticker, observedAt)`, never by price value: a replayed identical observation never adds a point, but a real new snapshot with an unchanged price is a valid point and may honestly yield `0.00%`.

**Validates: Requirements 7.1, 7.2, 7.4**

### Property 8: Bounded analytics staleness
On every runtime profile, a cached analytics entry expires within its TTL, so corrected underlying data surfaces without a restart.

**Validates: Requirements 6.1, 6.2, 6.3**

### Property 9: Event back-compat
Any valid old-shape `PriceUpdatedEvent` deserializes and processes successfully (missing new fields ⇒ null), and is never routed to the DLT solely for missing new fields.

**Validates: Requirements 10.4, 7.3**

## Error Handling

Unavailable semantics and failure handling:

- **Typed unavailable (R10 AC6):** price, change, P&L, FX-converted value, trend, and performance use **nullable** fields (or an explicit status). `null` ≠ `0`. Frontend maps `null` → "—"/"unavailable".
- **FX missing rate (R5 AC3):** `FxRateProvider` contract and **both** implementations behave consistently — return a typed unavailable result / throw `FxRateUnavailableException`, never substitute `1.0` except equal currencies. `EcbFxRateProvider` must stop returning `BigDecimal.ONE` and stop the USD-only-map silent 1:1 fallback; affected holdings' base-currency values become "unavailable" and aggregates expose partial-availability rather than silently undercounting.
  - *Future enhancement (non-normative, out of scope):* a last-known-good FX rate MAY later be used only if it is an actually-resolved prior rate, carries an `asOf` timestamp, and is explicitly labelled stale/estimated; if no previously-resolved rate exists, valuation remains unavailable. No persistence or infrastructure is added for FX fallback in this phase.
- **Event rollout:** missing new event fields ⇒ treated as null/unavailable, not a DLT trigger.
- **Over-limit price request:** explicit `400` or truncation metadata; never silent drop.
- **Stale data is not an error (R9 AC3):** serving last-known/seeded prices is a valid state, surfaced with an as-of timestamp.

## Testing Strategy

- **Contract/back-compat (unit):** serialize/deserialize old-shape (2-field) and new-shape `PriceUpdatedEvent`; listener validation still passes/fails on the right conditions; old-shape events never hit DLT for missing new fields.
- **market-data (unit + WireMock):** reference roll-forward on **new observation, including unchanged price**; `quoteCurrency` populated; `MarketPriceController` returns all requested tickers (incl. explicit unavailable rows for not-found tickers) / explicit over-limit behavior; change nullable when no reference.
- **portfolio (unit + integration `@Tag("integration")` Testcontainers Postgres):** projection appends `market_price_history` idempotently; analytics P&L from real cost basis (and null when absent); change uses tolerance window with reference timestamp; performance series complete vs partial-with-coverage; FX conversion + unavailable-rate behavior; cache TTL active on `azure` profile (no indefinite retention).
- **insight (unit + integration Testcontainers Redis):** distinct-observation trend; ≥2-distinct rule → null otherwise; stale-ticker filtering on read; consumes enriched event.
- **frontend (Vitest + MSW):** batching covers >25 tickers with no dropped/zeroed holdings; allocation total reconciles with portfolio total; typed-unavailable renders "—" not `$0.00`/`0.00%`; allocation uses backend asset class; ticker tape wired/hidden (no mock values); performance chart labels partial/unavailable; missing price shows "—" not now().
- **Migrations:** Flyway migrations apply cleanly and idempotently on a seeded DB; re-running backfill is a no-op.

## Requirements Traceability

| Requirement | Addressed by |
|---|---|
| R1 Complete price retrieval | Frontend batching; controller no-silent-truncation; nullable price (not 0); true `observedAt` |
| R2 Accurate 24h change | Contract extension (reference + timestamps); history backfill + forward-append; tolerance-window labelling; Market Data page uses backend change |
| R3 Unrealized P&L / cost basis | `asset_holdings` cost-basis columns; deterministic seed; analytics P&L from real basis; null when absent |
| R4 Correct allocation | Backend canonical asset-class mapping; FX-converted complete values; "Other" bucket |
| R5 Currency consistency | Backend-owned FX; consistent `FxRateProvider` behavior; no 1.0 substitution |
| R6 Fresh analytics on Azure | `azure` Caffeine cache-manager bean with 30s TTL |
| R7 AI-insights trend | Enriched event observations; distinct-observation trend; read-side stale filtering |
| R8 No mock/placeholder | Bind cards to analytics; remove/hide mock ticker; real names; true timestamps |
| R9 Honest freshness | As-of timestamps; stale ≠ error; typed-unavailable rendering |
| R10 Guardrails | No infra change; profile isolation; `common-dto` contract; additive idempotent migrations; typed unavailable; backfill+append only |

## Out of Scope (deferred — separate impact analysis)

- Changing `min_replicas`, KEDA scalers, ACA Jobs, external pingers, always-on replicas.
- A continuously-running price poller or sub-daily refresh cadence.
- A full trade/transaction ledger (cost basis is per-holding average for now).
