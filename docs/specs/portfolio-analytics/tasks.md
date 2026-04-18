# Implementation Plan: Portfolio Analytics API

## Overview

Implement the `GET /api/portfolio/analytics` endpoint in `portfolio-service` using a single SQL
query (CTE + `UNION ALL`), FX conversion, profile-aware caching (Caffeine / Redis), and a
synthetic series fallback. Migrate the frontend dashboard components to consume the new
`usePortfolioAnalytics` TanStack Query hook.

---

## Tasks

- [x] 1. Add Caffeine dependency and Flyway V6 migration
  - Add `com.github.ben-manes.caffeine:caffeine` to `portfolio-service/build.gradle`
    (note: `spring-boot-starter-cache` is already present)
  - Create `V6__Add_Portfolios_User_Id_Index.sql` in
    `portfolio-service/src/main/resources/db/migration/` with:
    `CREATE INDEX IF NOT EXISTS idx_portfolios_user_id ON portfolios(user_id);`
  - _Requirements: 9.1, 9.2_

- [x] 2. Define DTOs and internal projection record
  - [x] 2.1 Create `PortfolioAnalyticsDto` record in
        `portfolio-service/src/main/java/com/wealth/portfolio/dto/PortfolioAnalyticsDto.java`
    - Top-level record with fields: `totalValue`, `totalCostBasis`, `totalUnrealizedPnL`,
      `totalUnrealizedPnLPercent`, `baseCurrency`, `bestPerformer`, `worstPerformer`,
      `holdings`, `performanceSeries`
    - Nested records: `PerformerDto(ticker, change24hPercent)`,
      `HoldingAnalyticsDto` (all 9 fields from design), `PerformancePointDto(date, value, change)`
    - _Requirements: 1.1, 4.1, 4.2, 4.3, 5.1, 5.2, 6.1, 7.1_

  - [x] 2.2 Create `AnalyticsQueryRow` internal projection record in
        `portfolio-service/src/main/java/com/wealth/portfolio/AnalyticsQueryRow.java`
    - Fields: `rowType`, `assetTicker`, `quantity`, `currentPrice`, `quoteCurrency`,
      `price24hAgo`, `historyDate`, `historyPrice` — all nullable where appropriate
    - _Requirements: 2.1_

- [x] 3. Implement `PortfolioAnalyticsService`
  - [x] 3.1 Create `PortfolioAnalyticsService.java` in
        `portfolio-service/src/main/java/com/wealth/portfolio/`
    - Inject `JdbcTemplate`, `UserRepository`, `FxRateProvider`, `FxProperties`
    - Define `ANALYTICS_SQL` constant (CTE + `UNION ALL` query from design)
    - Implement `requireUserExists` guard (reuse pattern from `PortfolioService`)
    - Implement `getAnalytics(String userId)`: execute SQL, partition rows by `row_type`,
      compute per-holding FX conversion and 24h change, aggregate totals, select
      `bestPerformer` / `worstPerformer`, build performance series or invoke synthetic fallback
    - Annotate with `@Cacheable("portfolio-analytics")` using key `#userId`
    - _Requirements: 1.1, 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.4, 4.1, 4.2, 4.3, 4.4, 4.5,
      5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 6.3, 6.4, 7.1, 7.2, 7.3, 7.4, 7.5, 10.1, 10.2_

  - [x] 3.2 Implement `computeChange24hPercent(BigDecimal currentPrice, BigDecimal price24hAgo)`
        helper method inside `PortfolioAnalyticsService`
    - Return `ZERO` when `price24hAgo` is null or zero
    - Otherwise return `((currentPrice - price24hAgo) / price24hAgo) × 100` scaled to 4 d.p.
    - _Requirements: 6.1, 6.3, 6.4_

  - [x] 3.3 Implement `generateSyntheticSeries(BigDecimal anchorValue, int days)` method
        inside `PortfolioAnalyticsService`
    - Start at `anchorValue × 0.92`, apply deterministic drift + sine wave per day,
      pin the final entry's `value` to `anchorValue`
    - Return exactly `days` `PerformancePointDto` entries ordered ascending by date
      (today − (days−1) … today); first entry's `change = 0`, subsequent `change = value[i] − value[i−1]`
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [x] 3.4 Implement `buildPerformanceSeries(...)` helper method inside `PortfolioAnalyticsService`
    - Group `historyRows` by `historyDate`, iterate dates ascending, sum
      `quantity × historyPrice × fxRate` per date, compute `change` as `value − previousValue`
      (first point `change = 0`)
    - If distinct date count < 7, delegate to `generateSyntheticSeries(totalValue, 7)` instead
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 4. Configure profile-aware caching
  - [x] 4.1 Create `CacheConfig.java` in
        `portfolio-service/src/main/java/com/wealth/portfolio/`
    - `@Profile("local")` bean: `CaffeineCacheManager` with cache name `"portfolio-analytics"`,
      TTL 30 seconds, using `Caffeine.newBuilder().expireAfterWrite(30, SECONDS)`
    - `@Profile("aws")` bean: `RedisCacheManager` with the same cache name and TTL,
      using `RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofSeconds(30))`
    - _Requirements: 9.1, 9.2, 9.3, 9.4_

  - [x] 4.2 Add Redis dependency for the `aws` profile cache manager
    - Add `implementation 'org.springframework.boot:spring-boot-starter-data-redis'` to
      `portfolio-service/build.gradle` (needed for `RedisCacheManager` on the aws profile)
    - _Requirements: 9.2_

- [x] 5. Implement `PortfolioAnalyticsController`
  - Create `PortfolioAnalyticsController.java` in
    `portfolio-service/src/main/java/com/wealth/portfolio/`
  - `@RestController @RequestMapping("/api/portfolio")`
  - Single `@GetMapping("/analytics")` method: extract `@RequestHeader("X-User-Id") String userId`,
    delegate to `PortfolioAnalyticsService.getAnalytics(userId)`, return `ResponseEntity<PortfolioAnalyticsDto>`
  - Missing header → Spring returns `400` automatically; `UserNotFoundException` → `404` via
    existing `GlobalExceptionHandler`
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 6. Checkpoint — backend compiles and unit tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Unit tests for `PortfolioAnalyticsService`
  - [x] 7.1 Create `PortfolioAnalyticsServiceTest.java` in
        `portfolio-service/src/test/java/com/wealth/portfolio/`
    - Single holding, same currency as base → no `FxRateProvider` call, values pass through unchanged
    - Single holding, foreign currency → FX rate applied correctly to `currentValueBase`
    - Empty holdings → sentinel performers `("N/A", 0)`, empty series, zero totals
    - Null `price24hAgo` → `change24hPercent == 0`, no NPE
    - `performanceSeries` sorted ascending by date
    - `performanceSeries[0].change == 0`
    - Fewer than 7 distinct history dates → `generateSyntheticSeries` result returned
    - `totalUnrealizedPnL == totalValue - totalCostBasis` (always 0 with placeholder cost basis)
    - _Requirements: 3.1, 3.2, 4.2, 5.4, 6.3, 7.1, 7.3, 7.5, 10.1, 10.2_

  - [ ]\* 7.2 Write `@ParameterizedTest @MethodSource` test for Property 1 — Performer Ordering Invariant
    - **Property 1: `bestPerformer.change24hPercent >= worstPerformer.change24hPercent`**
    - `@MethodSource` supplies: single holding, two holdings equal change, two with different
      change, many holdings in random order
    - **Validates: Requirements 5.3**

  - [ ]\* 7.3 Write `@ParameterizedTest @MethodSource` test for Property 2 — P&L Identity
    - **Property 2: `totalUnrealizedPnL == totalValue - totalCostBasis`; when `totalCostBasis > 0`,
      `totalUnrealizedPnLPercent == (totalUnrealizedPnL / totalCostBasis) × 100`**
    - `@MethodSource` supplies varied `(totalValue, totalCostBasis)` pairs: zero, positive, negative
    - **Validates: Requirements 4.2, 4.3**

  - [ ]\* 7.4 Write `@ParameterizedTest @MethodSource` test for Property 3 — Value Decomposition
    - **Property 3: `totalValue == Σ(holding.currentValueBase)` for all holdings**
    - `@MethodSource` supplies lists of holdings with varying quantities, prices, and FX rates
    - **Validates: Requirements 3.1, 4.1**

  - [ ]\* 7.5 Write `@ParameterizedTest @MethodSource` test for Property 4 — Performance Series Ordering
    - **Property 4: `∀ i ∈ [1, |S|-1]: S[i].date > S[i-1].date`**
    - `@MethodSource` supplies: already sorted, reverse-sorted input, single element, 50 elements
    - **Validates: Requirements 7.1**

  - [ ]\* 7.6 Write `@ParameterizedTest @MethodSource` test for Property 5 — Series Change Consistency
    - **Property 5: `S[0].change == 0`; `∀ i >= 1: S[i].change == S[i].value - S[i-1].value`**
    - `@MethodSource` supplies series with known value sequences to assert change arithmetic
    - **Validates: Requirements 7.2, 7.3**

  - [ ]\* 7.7 Write `@ParameterizedTest @MethodSource` test for Property 6 — 24h Change Formula
    - **Property 6: when `price24hAgo > 0`, `change24hPercent == ((currentPrice - price24hAgo) / price24hAgo) × 100`**
    - `@MethodSource` supplies: equal prices (0%), doubled (100%), halved (-50%), `price24hAgo = 0` (returns 0)
    - **Validates: Requirements 6.1, 6.2**

  - [ ]\* 7.8 Write `@ParameterizedTest @MethodSource` test for Property 7 — Synthetic Series Anchor and Length
    - **Property 7: `generateSyntheticSeries(anchorValue, days)` returns exactly `days` entries,
      last entry's `value == anchorValue`, entries ordered ascending by date**
    - `@MethodSource` supplies: `days=1`, `days=7`, `days=30`; various `anchorValue` amounts
    - **Validates: Requirements 8.1, 8.2, 8.3**

- [x] 8. Integration test for analytics endpoint
  - [x]\* 8.1 Create `PortfolioAnalyticsIntegrationTest.java` in
    `portfolio-service/src/test/java/com/wealth/portfolio/`
    - Annotate with `@Tag("integration")`, use Testcontainers PostgreSQL
    - Seed a user + portfolio + holdings using existing `V2__Seed_Market_Data.sql` /
      `V3__Seed_Portfolio_Data.sql` data
    - Call `GET /api/portfolio/analytics` via `MockMvc`, assert `200 OK`
    - Assert `bestPerformer.change24hPercent >= worstPerformer.change24hPercent`
    - Assert `performanceSeries` is non-null and ordered ascending by date
    - Assert FX conversion: seed a holding with `quote_currency = 'EUR'`, verify
      `currentValueBase` differs from raw `currentPrice × quantity`
    - _Requirements: 1.1, 3.1, 5.3, 7.1_

- [x] 9. Checkpoint — all backend tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Add TypeScript interfaces to frontend types
  - Add `PortfolioAnalyticsDTO` and `HoldingAnalyticsDTO` interfaces to
    `frontend/src/types/portfolio.ts`
  - `PortfolioAnalyticsDTO`: `totalValue`, `totalCostBasis`, `totalUnrealizedPnL`,
    `totalUnrealizedPnLPercent`, `baseCurrency`, `bestPerformer`, `worstPerformer`,
    `holdings: HoldingAnalyticsDTO[]`, `performanceSeries: PerformanceDataPoint[]`
    (reuse existing `PerformanceDataPoint`)
  - `HoldingAnalyticsDTO`: `ticker`, `quantity`, `currentPrice`, `currentValueBase`,
    `avgCostBasis`, `unrealizedPnL`, `change24hAbsolute`, `change24hPercent`, `quoteCurrency`
  - _Requirements: 11.5_

- [x] 11. Add `fetchPortfolioAnalytics` API function
  - Add `fetchPortfolioAnalytics(token: string): Promise<PortfolioAnalyticsDTO>` to
    `frontend/src/lib/api/portfolio.ts`
  - Call `fetchWithAuthClient<PortfolioAnalyticsDTO>("/api/portfolio/analytics", token)`
  - _Requirements: 11.1_

- [x] 12. Add `usePortfolioAnalytics` TanStack Query hook
  - Add `analytics` query key to `portfolioKeys` in `frontend/src/lib/hooks/usePortfolio.ts`:
    `analytics: (userId: string) => ["portfolio", userId, "analytics"] as const`
  - Add `usePortfolioAnalytics()` hook: `useQuery` with `queryKey: portfolioKeys.analytics(userId)`,
    `queryFn: () => fetchPortfolioAnalytics(token)`, `enabled: status === "authenticated"`,
    `staleTime: 30_000`, `refetchInterval: 60_000`
  - _Requirements: 11.2, 11.3, 11.4_

- [x] 13. Migrate frontend dashboard components to `usePortfolioAnalytics`
  - [x] 13.1 Migrate `PerformanceChart.tsx` to use
        `usePortfolioAnalytics().data?.performanceSeries` instead of `usePortfolioPerformance`
    - Pass `performanceSeries` as the chart data source; preserve loading/empty states
    - _Requirements: 12.1_

  - [x] 13.2 Migrate `SummaryCards.tsx` to source `bestPerformer` and `worstPerformer` from
        `usePortfolioAnalytics().data?.bestPerformer` / `.worstPerformer`
    - _Requirements: 12.2_

  - [x] 13.3 Migrate `HoldingsTable.tsx` to source `unrealizedPnL` per holding from
        `usePortfolioAnalytics().data?.holdings` matched by ticker
    - Keep `usePortfolio()` for quantity and allocation weight; merge analytics fields by ticker
    - _Requirements: 12.3, 12.4_

- [x] 14. Add MSW handler for analytics endpoint in frontend unit tests
  - Add handler for `GET /api/portfolio/analytics` to `frontend/src/test/msw/handlers.ts`
  - Return a fixture `PortfolioAnalyticsDTO` with at least two holdings and a non-empty
    `performanceSeries` so component tests can assert against real-shaped data
  - _Requirements: 11.1, 11.2_

- [x] 15. Final checkpoint — all frontend tests pass
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Property tests use JUnit 5 `@ParameterizedTest @MethodSource` — no jqwik dependency
- `generateSyntheticSeries` is always present in `PortfolioAnalyticsService`; the synthetic
  fallback triggers when distinct history dates < 7 (not only on the `local` profile)
- Caching is profile-aware: Caffeine on `local`, Redis on `aws` — no code change needed to swap
- The existing `fetchPortfolio` function and `usePortfolio` hook are not removed
- Integration tests run via `./gradlew integrationTest` (not the default `test` task)
