# Requirements Document

## Introduction

This document defines the requirements for the Portfolio Analytics API feature. The feature adds a
`GET /api/portfolio/analytics` endpoint to `portfolio-service` that returns a unified analytics
payload — including per-holding unrealized P&L, 24h price change, best/worst performers, and a
historical performance series — to power the frontend dashboard. All monetary values are
FX-converted to a single base currency. The endpoint is backed by a single SQL query (no N+1),
profile-aware caching (Caffeine locally, Redis on AWS), and a local-profile resilience fallback
that synthesises a 7-day performance series when real history data is sparse.

---

## Glossary

- **PortfolioAnalyticsController**: The Spring REST controller that exposes `GET /api/portfolio/analytics` in `portfolio-service`.
- **PortfolioAnalyticsService**: The Spring service that orchestrates the SQL query, FX conversion, and DTO assembly.
- **PortfolioAnalyticsDto**: The top-level JSON response record containing all analytics fields.
- **HoldingAnalyticsDto**: A nested record within `PortfolioAnalyticsDto` representing per-holding analytics.
- **PerformancePointDto**: A nested record representing a single date-value-change point in the performance series.
- **PerformerDto**: A nested record holding a ticker symbol and its 24h change percentage, used for `bestPerformer` and `worstPerformer`.
- **FxRateProvider**: The domain port that returns an exchange rate between two currency codes.
- **baseCurrency**: The target currency for all monetary aggregates, sourced from `FxProperties`.
- **price24hAgo**: The market price of an asset closest to 24 hours before the request time, sourced from `market_price_history`.
- **change24hPercent**: The percentage change in an asset's price over the last 24 hours: `((currentPrice − price24hAgo) / price24hAgo) × 100`.
- **performanceSeries**: An ascending-date list of `PerformancePointDto` entries representing total portfolio value over time.
- **syntheticSeries**: A fallback 7-day performance series generated deterministically from `totalValue` when real history data has fewer than 7 distinct dates.
- **AnalyticsQueryRow**: An internal projection (not serialised) returned by the single analytics SQL query, carrying both `HOLDING` and `HISTORY` row types.
- **X-User-Id**: The HTTP request header injected by the API Gateway JWT filter, carrying the authenticated user's UUID.
- **Caffeine**: An in-memory cache library used under the `local` Spring profile.
- **Redis**: A distributed cache used under the `aws` Spring profile.
- **usePortfolioAnalytics**: The TanStack Query hook on the frontend that fetches and caches the analytics payload.
- **fetchPortfolioAnalytics**: The frontend API function that calls `GET /api/portfolio/analytics` with a Bearer token.

---

## Requirements

### Requirement 1: Analytics HTTP Endpoint

**User Story:** As an investor, I want to retrieve a complete analytics snapshot of my portfolio
via a single API call, so that the dashboard can display valuations, P&L, performers, and
performance history without multiple round-trips.

#### Acceptance Criteria

1. WHEN a `GET /api/portfolio/analytics` request is received with a valid `X-User-Id` header,
   THE `PortfolioAnalyticsController` SHALL delegate to `PortfolioAnalyticsService` and return
   `200 OK` with a `PortfolioAnalyticsDto` JSON body.
2. IF the `X-User-Id` header is absent from the request, THEN THE `PortfolioAnalyticsController`
   SHALL return `400 Bad Request`.
3. IF the `userId` extracted from `X-User-Id` does not correspond to a record in the `users`
   table, THEN THE `PortfolioAnalyticsService` SHALL throw `UserNotFoundException`, and THE
   `PortfolioAnalyticsController` SHALL return `404 Not Found`.
4. THE `PortfolioAnalyticsController` SHALL extract the user identity exclusively from the
   `X-User-Id` header and SHALL NOT accept a `userId` query parameter.

---

### Requirement 2: Single-Query Data Retrieval

**User Story:** As a platform engineer, I want the analytics endpoint to retrieve all required
data in a single SQL round-trip, so that the endpoint remains performant regardless of the number
of holdings.

#### Acceptance Criteria

1. WHEN `PortfolioAnalyticsService.getAnalytics` is called, THE `PortfolioAnalyticsService`
   SHALL execute exactly one SQL query that retrieves current holdings, 24h-ago prices, and
   historical performance series data in a single round-trip using a CTE + `UNION ALL` strategy.
2. THE SQL query SHALL scope all joins to the authenticated user's holdings via
   `WHERE portfolios.user_id = ?`, ensuring no cross-user data is returned.
3. THE SQL query SHALL use `DISTINCT ON (ticker) ORDER BY ticker, observed_at DESC` against
   `market_price_history` to retrieve the single price row closest to 24 hours ago per ticker.
4. THE SQL query SHALL retrieve historical series rows for all of the user's tickers within the
   configured period (default 50 days) in the same query using a range predicate on `observed_at`.

---

### Requirement 3: FX Conversion

**User Story:** As an investor with holdings in multiple currencies, I want all monetary values
expressed in a single base currency, so that I can compare holdings and see accurate totals.

#### Acceptance Criteria

1. WHEN a holding's `quoteCurrency` differs from `baseCurrency`, THE `PortfolioAnalyticsService`
   SHALL call `FxRateProvider.getRate(quoteCurrency, baseCurrency)` and multiply the holding's
   value by the returned rate to produce `currentValueBase`.
2. WHEN a holding's `quoteCurrency` equals `baseCurrency`, THE `PortfolioAnalyticsService` SHALL
   use a rate of `1` and SHALL NOT call `FxRateProvider`.
3. IF `FxRateProvider.getRate` throws `FxRateUnavailableException`, THEN THE
   `PortfolioAnalyticsService` SHALL propagate the exception, resulting in a `500 Internal Server
Error` response.
4. THE `PortfolioAnalyticsService` SHALL call `FxRateProvider` at most once per distinct
   `quoteCurrency` per request.

---

### Requirement 4: Portfolio Aggregates

**User Story:** As an investor, I want to see total portfolio value, total cost basis, and
unrealized P&L in my base currency, so that I can assess my overall portfolio performance at a
glance.

#### Acceptance Criteria

1. THE `PortfolioAnalyticsDto` SHALL set `totalValue` equal to the sum of
   `HoldingAnalyticsDto.currentValueBase` across all holdings.
2. THE `PortfolioAnalyticsDto` SHALL set `totalUnrealizedPnL` equal to `totalValue` minus
   `totalCostBasis`.
3. WHEN `totalCostBasis` is greater than zero, THE `PortfolioAnalyticsDto` SHALL set
   `totalUnrealizedPnLPercent` equal to `(totalUnrealizedPnL / totalCostBasis) × 100`.
4. IF `totalCostBasis` equals zero, THEN THE `PortfolioAnalyticsDto` SHALL set
   `totalUnrealizedPnLPercent` to zero.
5. THE `PortfolioAnalyticsDto` SHALL set `totalValue` to a value greater than or equal to zero.

---

### Requirement 5: Best and Worst Performer Identification

**User Story:** As an investor, I want to see which holding gained the most and which lost the
most over the last 24 hours, so that I can quickly identify the biggest movers in my portfolio.

#### Acceptance Criteria

1. THE `PortfolioAnalyticsDto` SHALL set `bestPerformer` to the `HoldingAnalyticsDto` with the
   highest `change24hPercent` among all holdings.
2. THE `PortfolioAnalyticsDto` SHALL set `worstPerformer` to the `HoldingAnalyticsDto` with the
   lowest `change24hPercent` among all holdings.
3. THE `PortfolioAnalyticsDto` SHALL satisfy the invariant:
   `bestPerformer.change24hPercent >= worstPerformer.change24hPercent`.
4. IF the user has no holdings, THEN THE `PortfolioAnalyticsDto` SHALL set both `bestPerformer`
   and `worstPerformer` to a sentinel `PerformerDto` with `ticker = "N/A"` and
   `change24hPercent = 0`.

---

### Requirement 6: 24h Price Change Computation

**User Story:** As an investor, I want each holding to show its 24-hour price change in both
absolute and percentage terms, so that I can evaluate recent momentum for each asset.

#### Acceptance Criteria

1. WHEN `price24hAgo` is available and greater than zero, THE `PortfolioAnalyticsService` SHALL
   compute `change24hPercent` as `((currentPrice − price24hAgo) / price24hAgo) × 100` scaled to
   4 decimal places.
2. WHEN `price24hAgo` is available and greater than zero, THE `PortfolioAnalyticsService` SHALL
   compute `change24hAbsolute` as `currentPrice − price24hAgo`.
3. IF `price24hAgo` is null, THEN THE `PortfolioAnalyticsService` SHALL substitute `currentPrice`
   as the fallback value for `price24hAgo`, resulting in `change24hPercent = 0` and
   `change24hAbsolute = 0`.
4. IF `price24hAgo` is zero, THEN THE `PortfolioAnalyticsService` SHALL set `change24hPercent`
   to zero to avoid division by zero.

---

### Requirement 7: Historical Performance Series

**User Story:** As an investor, I want to see a time-series chart of my total portfolio value
over the past 50 days, so that I can visualise the trajectory of my portfolio's performance.

#### Acceptance Criteria

1. THE `PortfolioAnalyticsDto.performanceSeries` SHALL be a non-null list ordered strictly
   ascending by `date`.
2. WHEN `performanceSeries` contains two or more points, THE `PortfolioAnalyticsService` SHALL
   set each point's `change` equal to its `value` minus the immediately preceding point's `value`.
3. WHEN `performanceSeries` contains at least one point, THE `PortfolioAnalyticsService` SHALL
   set the first point's `change` to zero.
4. THE `PortfolioAnalyticsService` SHALL compute each `PerformancePointDto.value` as the sum of
   `(holding.quantity × historyPrice × fxRate)` across all holdings that have a history row for
   that date, converted to `baseCurrency`.
5. IF the number of distinct dates in `market_price_history` for the user's tickers is fewer than
   7, THEN THE `PortfolioAnalyticsService` SHALL invoke `generateSyntheticSeries` and return its
   result as `performanceSeries`.

---

### Requirement 8: Synthetic Series Fallback

**User Story:** As a developer running the application locally, I want the performance chart to
always display data even when the local database has sparse history, so that I can develop and
test the frontend without seeding a full 50-day history.

#### Acceptance Criteria

1. WHEN `generateSyntheticSeries(anchorValue, days)` is called, THE `PortfolioAnalyticsService`
   SHALL return a list containing exactly `days` `PerformancePointDto` entries.
2. WHEN `generateSyntheticSeries` is called, THE `PortfolioAnalyticsService` SHALL set the last
   entry's `value` equal to `anchorValue`.
3. WHEN `generateSyntheticSeries` is called, THE `PortfolioAnalyticsService` SHALL return entries
   ordered ascending by date, spanning from `today − (days − 1)` to `today`.
4. WHEN `generateSyntheticSeries` is called, THE `PortfolioAnalyticsService` SHALL set the first
   entry's `change` to zero and each subsequent entry's `change` equal to its `value` minus the
   preceding entry's `value`.

---

### Requirement 9: Profile-Aware Caching

**User Story:** As a platform engineer, I want the analytics endpoint to be cached with a
profile-appropriate backend, so that repeated dashboard refreshes do not re-execute the expensive
SQL query, while keeping local and cloud environments independently configurable.

#### Acceptance Criteria

1. WHILE the `local` Spring profile is active, THE `PortfolioAnalyticsService` SHALL cache
   analytics results using Caffeine in-memory cache with a TTL of 30 seconds.
2. WHILE the `aws` Spring profile is active, THE `PortfolioAnalyticsService` SHALL cache
   analytics results using a Redis-backed Spring Cache with a TTL of 30 seconds.
3. THE `PortfolioAnalyticsService` SHALL use the cache key `"portfolio-analytics:{userId}"` to
   scope cached results per user.
4. THE caching configuration SHALL be switchable between Caffeine and Redis exclusively via Spring
   profile — no code changes SHALL be required to swap the cache backend.

---

### Requirement 10: Empty Portfolio Handling

**User Story:** As a new investor with no holdings yet, I want the analytics endpoint to return a
valid empty response rather than an error, so that the dashboard renders gracefully before I add
any assets.

#### Acceptance Criteria

1. IF the authenticated user has no portfolios or holdings, THEN THE `PortfolioAnalyticsService`
   SHALL return a `PortfolioAnalyticsDto` with `totalValue = 0`, `totalCostBasis = 0`,
   `totalUnrealizedPnL = 0`, `totalUnrealizedPnLPercent = 0`, an empty `holdings` list, and an
   empty `performanceSeries` list.
2. IF the authenticated user has no holdings, THEN THE `PortfolioAnalyticsDto` SHALL set
   `bestPerformer` and `worstPerformer` to the sentinel `PerformerDto("N/A", 0)`.

---

### Requirement 11: Frontend API Integration

**User Story:** As a frontend developer, I want a typed API function and TanStack Query hook for
the analytics endpoint, so that dashboard components can consume analytics data with automatic
caching, background refresh, and loading/error states.

#### Acceptance Criteria

1. THE Frontend SHALL expose a `fetchPortfolioAnalytics(token: string)` function in
   `frontend/src/lib/api/portfolio.ts` that calls `GET /api/portfolio/analytics` with the
   provided Bearer token and returns a `Promise<PortfolioAnalyticsDTO>`.
2. THE Frontend SHALL expose a `usePortfolioAnalytics()` hook in
   `frontend/src/lib/hooks/usePortfolio.ts` that uses TanStack Query with `staleTime: 30_000`
   and `refetchInterval: 60_000`.
3. WHEN the user is authenticated, THE `usePortfolioAnalytics` hook SHALL enable the query and
   fetch portfolio analytics data using the authenticated user's token.
4. WHEN the user is not authenticated, THE `usePortfolioAnalytics` hook SHALL disable the query
   via `enabled: false`.
5. THE `PortfolioAnalyticsDTO` TypeScript interface SHALL include all fields defined in the Java
   `PortfolioAnalyticsDto` record: `totalValue`, `totalCostBasis`, `totalUnrealizedPnL`,
   `totalUnrealizedPnLPercent`, `baseCurrency`, `bestPerformer`, `worstPerformer`, `holdings`,
   and `performanceSeries`.

---

### Requirement 12: Frontend Dashboard Component Migration

**User Story:** As a frontend developer, I want the dashboard components that currently use
placeholder data to be migrated to consume real analytics data from `usePortfolioAnalytics`, so
that the dashboard displays accurate live values.

#### Acceptance Criteria

1. WHEN `usePortfolioAnalytics` data is available, THE `PerformanceChart` component SHALL source
   its data from `usePortfolioAnalytics().data?.performanceSeries` instead of the placeholder
   `usePortfolioPerformance` hook.
2. WHEN `usePortfolioAnalytics` data is available, THE dashboard summary cards SHALL source
   `bestPerformer` and `worstPerformer` from `usePortfolioAnalytics().data?.bestPerformer` and
   `usePortfolioAnalytics().data?.worstPerformer` respectively.
3. WHEN `usePortfolioAnalytics` data is available, THE holdings table SHALL source
   `unrealizedPnL` per holding from `usePortfolioAnalytics().data?.holdings` matched by ticker.
4. THE existing `fetchPortfolio` function and its associated hook SHALL remain in place and SHALL
   continue to serve holdings quantity and allocation weight data.
