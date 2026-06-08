# Requirements Document

Dashboard Data Accuracy & Freshness.

## Introduction

The deployed dashboard renders a mix of static seed data, client-truncated prices, structural placeholders, and hardcoded mocks, so values such as 24h change, unrealized P&L, asset allocation, and portfolio totals are wrong or frozen. The root causes are catalogued in `docs/audit/DATA_STALENESS_AND_CALCULATION_AUDIT_2026-06-07.md` (rev2).

This spec defines the target behavior for making the dashboard **correct, internally consistent, and honestly labelled** for its data freshness — **without changing the infrastructure**. The Azure Container Apps `min_replicas = 0` scale-to-zero setting is an intentional cost control and is explicitly **out of scope**; all requirements here must be satisfiable with the existing daily-refresh / seed-and-hydrate data lifecycle.

The guiding principle: where a value can be computed correctly from data that already exists, compute it correctly; where the data is genuinely unavailable or intentionally stale, present that state honestly (timestamps, "not available") rather than rendering a misleading `+$0.00` / `+0.00%` or a fabricated figure.

**In scope** (no infrastructure change): complete price retrieval across all holdings; real 24h change (price history + previous close on the market-data path); real unrealized P&L (cost-basis source); correct, currency-consistent asset allocation and valuations; fresh analytics on Azure (cache expiry); accurate AI-insights trend; removal of mock/placeholder UI values and honest freshness labelling.

**Out of scope** (deferred — require separate impact analysis per owner): changing `min_replicas`, adding KEDA scalers, or any infra/Terraform change; introducing a continuously-running price poller or external scheduled trigger; replacing the daily refresh cadence.

## Glossary

- **Golden-state seed** — the 160-ticker portfolio created by `PortfolioSeedService` + `SeedTickerRegistry`.
- **Quote currency** — the native currency of an asset's price (e.g. INR for NSE tickers).
- **Base currency** — the user's reporting currency (USD), per `FxProperties.baseCurrency()`.
- **As-of timestamp** — the time the underlying price/calculation was last updated, surfaced to the user.
- **Daily-refresh cadence** — the at-most-once-per-day price refresh on Azure (`application-azure.yml` cron), an intentional cost control.

## Requirements

### Requirement 1: Complete price retrieval for all holdings

**User Story:** As an investor, I want every holding in my portfolio to show its current price, so that my totals, allocation, and per-row values are complete and trustworthy.

Context: `GET /api/market/prices` caps filtered requests at 25 tickers while the frontend sends up to 160 in one call, so ~135 holdings fall back to `currentPrice = 0` (audit §4.7).

#### Acceptance Criteria

1. WHEN the frontend requests prices for a portfolio of N holdings (N up to at least 160) THEN the system SHALL return a price (or an explicit "no price available" marker) for every requested ticker, with none silently dropped.
2. WHEN more tickers are requested than a single backend call permits THEN the client SHALL batch requests so that all tickers are covered, OR the backend SHALL accept the full set, and in either case no holding SHALL be assigned a price of 0 solely because it exceeded a request cap.
3. WHEN a ticker genuinely has no price in the data store THEN the system SHALL represent it as "price unavailable" (distinct from a real `$0.00`) and SHALL NOT set its `lastUpdatedAt` to the current time.
4. WHEN the dashboard renders the Portfolio Total and the Asset Allocation total for the same portfolio at the same time THEN the two totals SHALL be derived from the same complete price set and SHALL reconcile within rounding tolerance.
5. WHEN a market-price request exceeds any server-side limit THEN the backend SHALL reject the request with an explicit error OR return explicit truncation/availability metadata, and SHALL NOT silently omit requested tickers from the response.

### Requirement 2: Accurate 24h price change

**User Story:** As an investor, I want to see a real 24-hour change for each holding and ticker, so that I can tell what actually moved.

Context: 24h change reads from `market_price_history`, which is only seeded for AAPL/TSLA/legacy `BTC`; nothing writes it at runtime, and the market-data path (`AssetPrice`/`MarketPriceDto`/`PriceUpdatedEvent`) carries no previous close (audit §3.1, §3.3).

#### Acceptance Criteria

1. WHEN a price exists for a ticker along with a reference price from approximately 24 hours earlier THEN the system SHALL compute and expose `change24hAbsolute` and `change24hPercent` from those two values.
2. WHEN price data is persisted or refreshed for a ticker THEN the system SHALL also persist a timestamped historical observation for that ticker so a 24h-ago reference becomes available over time, for all tracked tickers (not only AAPL/TSLA/BTC) and using the canonical symbol (`BTC-USD`, not `BTC`).
3. WHEN no approximately-24h-ago reference price exists for a ticker THEN the system SHALL surface the 24h change as "not available" rather than `+0.00%`.
4. WHEN the Market Data page displays the 24h change column THEN it SHALL use the backend-computed change values and SHALL NOT render a hardcoded `0`.
5. WHERE the deployment refreshes prices at most daily THE displayed change SHALL be accompanied by an as-of timestamp so the user can see the data's age.
6. WHEN the displayed change is computed against a reference that is not within an explicit ~24h tolerance window (e.g. 18–36h) THEN it SHALL be labelled according to the actual reference used (e.g. "since previous daily snapshot" / "since previous observation"), and SHALL only be called "24h change" when the reference falls within that tolerance window; the API SHALL return both the current and reference timestamps.
7. WHEN portfolio performance history is displayed THEN the series SHALL be computed from complete FX-converted historical values for the portfolio's holdings, OR SHALL be explicitly marked partial/unavailable with coverage metadata; it SHALL NOT present a partial subset of holdings (e.g. only tickers that happen to have history) or a synthetic curve as full-portfolio performance.

### Requirement 3: Accurate unrealized P&L and cost basis

**User Story:** As an investor, I want unrealized P&L to reflect my actual cost basis, so that the figure is meaningful rather than always zero.

Context: `avgCostBasis` is a placeholder equal to `currentPrice`, so `unrealizedPnL` is always 0 on both backend and frontend (audit §3.4).

#### Acceptance Criteria

1. WHEN a holding has a recorded cost basis distinct from its current price THEN the system SHALL compute `unrealizedPnL = currentValue − costBasis` (FX-converted to base currency) and the corresponding percentage.
2. WHEN no cost basis is available for a holding THEN the system SHALL present P&L as "not available" rather than `+$0.00`, and SHALL NOT imply a real zero gain/loss.
3. WHEN cost basis is derived (from a seed, an add-time capture, or a trade record) THEN the same source SHALL be used consistently for per-holding P&L, aggregate P&L, and the "all-time return" indicator.
4. IF a cost-basis source is introduced via seeding for the demo THEN it SHALL produce non-trivial, deterministic values so the dashboard demonstrates real P&L behavior.
5. WHEN cost basis is persisted THEN it SHALL be modelled as a per-holding average cost basis with source metadata (e.g. `avg_cost_basis`, `cost_basis_currency`, `cost_basis_source`, optionally `cost_basis_as_of`) as nullable fields; a full trade/transaction ledger is deferred to a separate feature.

### Requirement 4: Correct asset allocation

**User Story:** As an investor, I want the allocation donut to reflect the true asset-class mix of my portfolio, so that it isn't misreported as "100% Stocks."

Context: Frontend `TICKER_META` knows only 3 symbols and defaults everything else to `STOCK` (audit §4.4); allocation also runs on truncated/un-converted values (§4.7, §4.8).

#### Acceptance Criteria

1. WHEN the portfolio contains assets of multiple classes (equity, crypto, FX, etc.) THEN the allocation SHALL reflect each holding's actual asset class as defined by the backend catalogue/registry, not a frontend default.
2. WHEN allocation percentages are computed THEN they SHALL be based on FX-converted, complete holding values (see Requirements 1 and 5) and SHALL sum to 100% within rounding tolerance.
3. WHEN a holding's asset class or metadata is unknown THEN it SHALL be bucketed into an explicit "Other/Unknown" category rather than silently defaulted to "Stocks."
4. WHEN asset class is exposed to the frontend THEN the backend SHALL provide a canonical display asset-class value or an explicit mapping from seed/catalogue values (`US_EQUITY`, `NSE`, `CRYPTO`, `FOREX`, …) to display values (`STOCK`, `ETF`, `CRYPTO`, `BOND`, `CASH`, `COMMODITY`, `OTHER`), and the frontend SHALL NOT infer or remap unknown classes silently.

### Requirement 5: Currency-consistent valuations

**User Story:** As an investor holding assets priced in different currencies, I want all totals shown in my base currency, so that values aren't silently mixed.

Context: The frontend portfolio adapter computes `quantity × currentPrice` in quote currency and labels the total USD with no FX step, unlike the backend (audit §4.8). Note an existing inconsistency: `EcbFxRateProvider` (aws/azure) returns `BigDecimal.ONE` on a missing rate — and falls back to a USD-only map on API failure, silently converting all non-USD pairs 1:1 — whereas `StaticFxRateProvider` (local) throws `FxRateUnavailableException`. This requirement resolves that inconsistency in favor of explicit handling.

#### Acceptance Criteria

1. WHEN any monetary total or per-holding value is displayed THEN it SHALL be expressed in the user's base currency after FX conversion from the asset's quote currency.
2. WHEN holdings, totals, allocation, P&L, or performance are displayed THEN base-currency values SHALL be sourced from backend valuation/analytics contracts that use the shared `FxRateProvider`; frontend FX conversion SHALL be used only for purely presentational cases and SHALL use the same availability semantics, and SHALL NOT sum mixed currencies as if they were one.
3. WHEN an FX rate cannot be resolved for a holding's quote currency THEN the system SHALL surface the affected valuation as unavailable (or return a typed error / partial-availability status) and SHALL NOT substitute `1.0` except when source and target currencies are equal — this behavior SHALL be consistent across all `FxRateProvider` implementations.

### Requirement 6: Fresh analytics on Azure

**User Story:** As an operator, I want analytics responses to reflect the latest underlying data within a bounded staleness window, so that corrected data appears without a container restart.

Context: On `prod,azure`, `@Cacheable` analytics uses a no-TTL `simple` cache manager (no `azure` cache bean), so a computed payload can persist for the warm life of the container (audit §3.6).

#### Acceptance Criteria

1. WHEN the analytics cache is active on the Azure profile THEN cached entries SHALL expire after a bounded TTL comparable to the other profiles (approximately 30s), not persist indefinitely.
2. WHEN underlying prices, history, or cost-basis are updated THEN a subsequent analytics request after the TTL window SHALL reflect the updated data without requiring a container restart.
3. WHEN the cache backend differs by profile THEN every supported runtime profile (`local`, `aws`, `azure`) SHALL have a defined cache manager with an explicit expiry policy.

### Requirement 7: Accurate AI-insights trend

**User Story:** As an investor, I want the AI Insights cards to show a real trend (or none), so that they don't all read `+0.00%`.

Context: The Redis sliding window typically holds at most 1 distinct point per ticker (only startup hydration publishes events under scale-to-zero), trend is event-count based not time-based, and stale tickers are pruned only on write (audit §3.5).

#### Acceptance Criteria

1. WHEN insight trend is computed THEN it SHALL be based on at least two distinct observations for the ticker, and duplicate identical hydration values SHALL NOT be presented as a meaningful `0.00%` trend.
2. WHEN fewer than two distinct observations exist for a ticker THEN the card SHALL show "trend not available" rather than `+0.00%`.
3. WHEN the market summary is read THEN stale tickers (beyond the tracked-ticker TTL) SHALL be excluded on read, not only pruned on write.
4. WHERE startup hydration populates insight trends THE hydration SHALL use an application-level contract that provides timestamped recent observations per ticker (e.g. extended events, a market/portfolio history API, or insight-service's own persisted observations), and SHALL NOT rely on direct cross-service database access; a trend SHALL be displayed only when at least two distinct timestamped observations are available.

### Requirement 8: No mock or misleading placeholder values in the UI

**User Story:** As an investor, I want every number on screen to come from real data (or be clearly labelled as illustrative), so that the dashboard doesn't present fiction as fact.

Context: The top ticker tape is 100% hardcoded `MOCK_TICKER`; the "24h Profit/Loss" card is bound to an always-zero synthetic summary; `lastUpdatedAt` defaults to now() for missing prices; portfolio name is hardcoded (audit §4.1, §4.3, §4.8).

#### Acceptance Criteria

1. WHEN the top ticker strip is displayed THEN it SHALL be backed by real market/portfolio data, OR be explicitly labelled as illustrative/sample if real data is not yet wired.
2. WHEN the "24h Profit/Loss" card is displayed THEN it SHALL be bound to the backend analytics values, not to a frontend-synthesised summary that is hardcoded to zero.
3. WHEN any value cannot be sourced from real data THEN the UI SHALL show an explicit empty/unavailable state rather than a fabricated number.
4. WHEN a holding's price is missing THEN its displayed "last updated" SHALL reflect the true last update (or "—"), never the current time.
5. WHEN portfolio identity metadata (e.g. name) is displayed THEN it SHALL come from backend portfolio data OR use a neutral generic label, and SHALL NOT present a hardcoded user-specific portfolio name as factual.

### Requirement 9: Honest freshness presentation

**User Story:** As an investor, I want to see how current the data is, so that intentional daily-refresh staleness is transparent rather than looking like a bug.

Context: Prices are refreshed at most daily and may be frozen at seed time under scale-to-zero; "Last Updated" currently shows a single static date with no context (audit §3.2, §6).

#### Acceptance Criteria

1. WHEN price-derived data is displayed THEN the UI SHALL show an as-of timestamp (or relative age) sourced from the actual `updatedAt`/`observed_at` of the underlying data.
2. WHEN data is older than the expected refresh cadence THEN the UI MAY indicate staleness, but SHALL NOT misrepresent stale data as live.
3. WHEN the system serves last-known/seeded prices because no fresh refresh has occurred THEN this SHALL be treated as a defined, acceptable state (consistent with the cost-control design) and SHALL NOT be treated as an error.

### Requirement 10: Guardrails and non-functional constraints

**User Story:** As the project owner, I want the fixes to respect the project's architecture and cost guardrails, so that solving these bugs does not introduce regressions or unplanned cost.

Context: The project enforces hexagonal architecture, multi-cloud agnosticism, profile isolation, and strict cost control (`.kiro/steering/tech.md`).

#### Acceptance Criteria

1. WHEN any requirement is implemented THEN it SHALL NOT alter `min_replicas`, Terraform, KEDA scaling, or the refresh cadence (deferred items remain deferred pending separate impact analysis).
2. WHEN changes touch configuration THEN they SHALL follow profile isolation (profile-specific config in `application-{profile}.yml`) and SHALL NOT introduce vendor SDKs into domain logic.
3. WHEN a behavioral change is made THEN it SHALL have unit coverage and, where it crosses a service or DB boundary, integration coverage per existing conventions (`@Tag("integration")`, Testcontainers).
4. WHEN a shared contract (`common-dto` event/DTO) changes THEN the change SHALL be made in `common-dto` and reflected in all consumers, never duplicated per-service.
5. WHEN a database change is required THEN new Flyway migrations SHALL be additive and idempotent, and existing seed/migration history SHALL NOT be rewritten.
6. WHEN a financial metric can be unavailable (price, 24h change, unrealized P&L, FX-converted valuation, insight trend, performance series) THEN the shared DTO/API contract SHALL distinguish "unavailable"/null from a numeric zero using nullable fields, explicit availability/status fields, or an equivalent typed representation, applied consistently across all such metrics.
7. WHEN price-history consistency is established THEN it SHALL be achievable via additive seed/backfill and forward-append on seed/hydration/refresh, and SHALL NOT depend on adding ACA Jobs, KEDA scalers, external pingers, or always-on replicas.

## Resolved Decisions

These resolve the original open questions (decisions to carry into design):

1. **Cost basis (R3):** Represent cost basis as a per-holding **average cost basis with source metadata** (nullable `avg_cost_basis`, `cost_basis_currency`, `cost_basis_source`, optionally `cost_basis_as_of`). The golden-state seed populates **deterministic, non-trivial** demo values (e.g. derived ±5–20% from the seed price). New holdings capture cost basis at add-time when a current price exists. A full trade/transaction ledger is **deferred** to a separate feature. Holdings without a cost basis show P&L as "not available."
2. **24h reference under daily refresh (R2):** Display the metric per the **actual reference used** — "since previous daily snapshot" / "since previous observation" — and only label it "24h change" when the reference falls within an explicit tolerance window (≈18–36h). The API returns current and reference timestamps; if no reference exists in-window, change is "not available."
3. **Top ticker tape (R8):** **Remove the hardcoded mock.** Prefer wiring it to real backend market/portfolio data this phase (e.g. holdings ranked by value or by daily move). If a real feed is not ready at implementation time, **hide the component** rather than show mock financial values.
4. **History backfill (R2):** Do **both** — a deterministic one-time **backfill** of `market_price_history` for all 160 canonical seed tickers (canonical symbols incl. `BTC-USD`, with `quote_currency`, idempotent, additive; do not rewrite `V2`), **and** forward-**append** a timestamped observation whenever a real seed/hydration/refresh price is persisted (e.g. `MarketPriceProjectionService` appends on update). Neither path may depend on changing the refresh cadence or replica count.

## Contract impact (informative, for design)

To satisfy R2/R5/R10, the design is expected to extend shared/market contracts — e.g. add fields such as `observedAt`, a reference price + `referenceAt`, `changeAbsolute`/`changePercent` (nullable), and `quoteCurrency`. Shared **event** changes (`PriceUpdatedEvent`) are made in `common-dto` and reflected in every consumer (R10 AC4); service-local REST DTOs such as `MarketPriceDto` are updated at their owning service boundary (market-data-service) and reflected in consumers. This section is informative; concrete contract design belongs in `design.md`.
