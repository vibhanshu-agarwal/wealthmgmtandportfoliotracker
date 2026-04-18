# Implementation Plan: FX Currency Conversion

## Overview

Introduce FX rate conversion into the portfolio valuation pipeline using hexagonal architecture.
A domain port (`FxRateProvider`) is wired to two profile-scoped adapters: `StaticFxRateProvider`
(local) and `EcbFxRateProvider` (aws). `PortfolioService.getSummary` is updated to apply per-row
FX conversion before aggregation. The `EcbFxRateProvider` fetches the entire rate map in a single
HTTP call, caches it as one entry, and falls back to `BigDecimal.ONE` on any API failure.

## Tasks

- [x] 1. Add build dependencies and enable caching
  - Add `implementation 'org.springframework.boot:spring-boot-starter-cache'` to `portfolio-service/build.gradle`
  - Add `testImplementation 'org.wiremock.integrations:wiremock-spring-boot:3.2.0'` to `portfolio-service/build.gradle`
  - Add `@EnableCaching` and `@EnableScheduling` to `PortfolioApplication.java`
  - _Requirements: 6.6, 6.7_

- [x] 2. Define domain types and the FxRateProvider port
  - [x] 2.1 Create `FxRateUnavailableException` in `com.wealth.portfolio`
    - Unchecked (`RuntimeException`) with fields `fromCurrency`, `toCurrency`, and `Throwable cause`
    - Message format: `"FX rate unavailable: %s → %s".formatted(from, to)`
    - _Requirements: 7.4_
  - [x] 2.2 Create `FxRateProvider` interface in `com.wealth.portfolio`
    - Single method: `BigDecimal getRate(String fromCurrency, String toCurrency)`
    - Must NOT import anything from `com.wealth.portfolio.fx`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_
  - [x] 2.3 Create `HoldingValuationRow` record in `com.wealth.portfolio`
    - Fields: `String assetTicker`, `BigDecimal quantity`, `BigDecimal currentPrice`, `String quoteCurrency`
    - _Requirements: 5.3_

- [x] 3. Add Flyway migration for `quote_currency`
  - Create `V5__Add_Quote_Currency_To_Market_Prices.sql` in `portfolio-service/src/main/resources/db/migration/`
  - SQL: `ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS quote_currency VARCHAR(10) NOT NULL DEFAULT 'USD';`
  - _Requirements: 5.1_

- [x] 4. Create `FxProperties` configuration record
  - Create `FxProperties` in `com.wealth.portfolio.fx` annotated with `@ConfigurationProperties(prefix = "fx")`
  - Fields: `String baseCurrency`, nested `LocalProperties(Map<String,BigDecimal> staticRates, long jitterIntervalMs)`, nested `AwsProperties(String ratesUrl, String refreshCron)`
  - Add `@ConfigurationPropertiesScan` or `@EnableConfigurationProperties(FxProperties.class)` to `PortfolioApplication`
  - _Requirements: 6.3_

- [x] 5. Implement `StaticFxRateProvider` (local adapter)
  - [x] 5.1 Create `StaticFxRateProvider` in `com.wealth.portfolio.fx`
    - Annotate with `@Service @Profile("local")`
    - Constructor accepts `FxProperties`; load `ratesFromUsd` from `props.local().staticRates()`
    - `getRate`: return `BigDecimal.ONE` for same-currency; compute cross-rate as `ratesFromUsd[to] / ratesFromUsd[from]`; throw `FxRateUnavailableException` for missing currencies
    - Use `BigDecimal` division with `MathContext.DECIMAL64` or `scale=10, HALF_UP`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - [x]\* 5.2 Write unit tests for `StaticFxRateProvider`
    - Test same-currency returns `BigDecimal.ONE` (Property 1)
    - Test cross-rate formula: `getRate("EUR","USD")` = `1.0 / 0.92` ≈ `1.0870` (Property 4)
    - Test unknown currency throws `FxRateUnavailableException`
    - _Requirements: 8.4_
  - [ ]\* 5.3 Write property test for inverse round-trip (Property 3)
    - **Property 3: Inverse round-trip — `getRate(A,B) × getRate(B,A) ≈ 1` within 0.0001**
    - **Validates: Requirements 2.6**

- [x] 6. Wire `application-local.yml` and `application-aws.yml`
  - Add `fx` block to `application-local.yml`: `base-currency: USD`, static rates for `EUR`, `GBP`, `JPY`, `CAD`, `AUD`, `CHF`, `USD`
  - Create `portfolio-service/src/main/resources/application-aws.yml` with `fx.aws.rates-url`, `fx.aws.refresh-cron`, and `spring.cache.type: simple`
  - _Requirements: 6.1, 6.2, 6.4, 6.5_

- [x] 7. Implement `EcbFxRateProvider` (aws adapter)
  - [x] 7.1 Create `EcbFxRateProvider` in `com.wealth.portfolio.fx`
    - Annotate with `@Service @Profile("aws")`
    - Constructor accepts `RestClient.Builder` and `FxProperties`; build `RestClient` with base URL from `props.aws().ratesUrl()`
    - Add a private method `fetchRateMap()` annotated with `@Cacheable(value = "fx-rates", key = "'all'")` that performs the single HTTP GET and returns `Map<String, BigDecimal>`
    - Wrap the `RestClient` call in try-catch; on any exception log at ERROR level and return `Map.of("USD", BigDecimal.ONE)` as fallback (satisfies fault-tolerance requirement)
    - `getRate`: call `fetchRateMap()` to get the cached map; return `BigDecimal.ONE` for same-currency; compute `ratesFromUsd[to] / ratesFromUsd[from]`; throw `FxRateUnavailableException` if either key is absent
    - Add `@Scheduled(cron = "${fx.aws.refresh-cron:0 0 6 * * *}") @CacheEvict(value = "fx-rates", allEntries = true)` method `evictDailyRates()`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 7.2_
  - [x]\* 7.2 Write integration test for `EcbFxRateProvider` using WireMock
    - Annotate with `@Tag("integration")`
    - Stub `GET /v6/latest/USD` to return `{ "rates": { "EUR": 0.92, "GBP": 0.79, "USD": 1.0 } }`
    - Assert `getRate("EUR","USD")` ≈ `1.0870`
    - Call `getRate` twice; verify exactly 1 HTTP request was made (Property 5)
    - _Requirements: 8.5, 8.6_
  - [ ]\* 7.3 Write unit test for fault-tolerant fallback (Property 6)
    - **Property 6: If HTTP call throws, `getRate` returns `BigDecimal.ONE` and does not propagate**
    - **Validates: Requirements 3.5, 7.2**

- [x] 8. Checkpoint — unit tests for adapters pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Update `PortfolioService.getSummary` with FX conversion
  - [x] 9.1 Inject `FxRateProvider` and `FxProperties` into `PortfolioService`
    - Add constructor parameters; remove the old single-value SQL aggregation
    - _Requirements: 4.7_
  - [x] 9.2 Replace the raw SQL sum with a per-row `HoldingValuationRow` query
    - New SQL: `SELECT h.asset_ticker, h.quantity, COALESCE(mp.current_price,0) AS current_price, COALESCE(mp.quote_currency,'USD') AS quote_currency FROM asset_holdings h JOIN portfolios p ON p.id = h.portfolio_id LEFT JOIN market_prices mp ON mp.ticker = h.asset_ticker WHERE p.user_id = ?`
    - Map results to `HoldingValuationRow` via `RowMapper`
    - _Requirements: 4.1, 5.2_
  - [x] 9.3 Implement the FX conversion loop
    - For each row: if `quoteCurrency.equals(baseCurrency)` use `BigDecimal.ONE`, else call `fxRateProvider.getRate(quoteCurrency, baseCurrency)`
    - Compute `quantity × currentPrice × rate` with `setScale(4, HALF_UP)`; accumulate into `totalValue`
    - Let `FxRateUnavailableException` propagate uncaught (fail-fast for unrecognised currencies)
    - _Requirements: 4.2, 4.3, 4.6_
  - [x] 9.4 Update `PortfolioSummaryDto` to include `baseCurrency`
    - Add `String baseCurrency` field to the record in `com.wealth.portfolio.dto`
    - Return `new PortfolioSummaryDto(userId, portfolios.size(), totalHoldings, totalValue, baseCurrency)`
    - _Requirements: 4.5_
  - [x]\* 9.5 Write unit tests for `PortfolioService.getSummary` (Mockito, no Spring context)
    - Test: 10 shares × 100 EUR × 1.08 rate = 1080.0000 USD (Property 7)
    - Test: all-USD holdings produce same result as legacy sum and do NOT call `FxRateProvider` (Property 8)
    - Test: `FxRateUnavailableException` from provider propagates to caller
    - Test: `baseCurrency` field on returned DTO equals `FxProperties.baseCurrency()` (Property 9)
    - _Requirements: 8.1, 8.2, 8.3_

- [x] 10. Update `GlobalExceptionHandler` for `FxRateUnavailableException`
  - Add `@ExceptionHandler(FxRateUnavailableException.class)` returning HTTP 503
  - Response body: `{ "error": "FX rate unavailable: {from} → {to}", "retryable": true }`
  - _Requirements: 7.1_

- [x] 11. Fix any compilation breaks caused by the new `baseCurrency` field
  - Update `PortfolioSummaryControllerTest` and any other test that constructs `PortfolioSummaryDto` directly to pass the new `baseCurrency` argument
  - _Requirements: 4.5_

- [x] 12. Final checkpoint — all tests pass
  - Ensure all unit tests pass (`./gradlew :portfolio-service:test`)
  - Ensure all integration tests pass (`./gradlew :portfolio-service:integrationTest`)
  - Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- `EcbFxRateProvider` caches the full `Map<String,BigDecimal>` via `fetchRateMap()` — NOT per currency-pair — to prevent IP-banning from one HTTP call per `getRate` invocation
- On any HTTP failure, `fetchRateMap()` returns `Map.of("USD", BigDecimal.ONE)` so `getRate` always returns `1` (graceful degradation) and the portfolio summary endpoint never throws a 500
- `PortfolioService` must NOT import anything from `com.wealth.portfolio.fx` — only the `FxRateProvider` port
- Property tests validate universal correctness properties; unit tests validate specific examples and edge cases
- Integration tests must carry `@Tag("integration")` to run under `integrationTest` Gradle task only
