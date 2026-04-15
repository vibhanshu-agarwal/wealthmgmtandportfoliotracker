# Requirements Document

## Introduction

The `portfolio-service` currently computes portfolio total value by summing `quantity × current_price` across all holdings in a single SQL query, implicitly assuming every asset is priced in the user's base currency (USD). This breaks for multi-currency portfolios where holdings may be priced in EUR, GBP, JPY, or other currencies.

This feature introduces FX currency conversion into the portfolio valuation pipeline. A domain port (`FxRateProvider`) abstracts rate retrieval; two profile-scoped adapters provide implementations — a zero-network static map for local development and a cached HTTP adapter for the AWS environment. `PortfolioService.getSummary` is updated to apply per-row FX conversion before aggregation, and `PortfolioSummaryDto` is extended with a `baseCurrency` field.

---

## Glossary

- **FxRateProvider**: Domain port (interface) in `com.wealth.portfolio` that abstracts foreign exchange rate retrieval. `PortfolioService` depends only on this interface.
- **StaticFxRateProvider**: Infrastructure adapter implementing `FxRateProvider`, active under the `local` Spring profile. Serves rates from an in-memory map with no network calls.
- **EcbFxRateProvider**: Infrastructure adapter implementing `FxRateProvider`, active under the `aws` Spring profile. Fetches rates from `open.er-api.com` and caches the result.
- **FxRateUnavailableException**: Unchecked exception thrown when a requested currency pair cannot be resolved.
- **HoldingValuationRow**: Internal query projection carrying `assetTicker`, `quantity`, `currentPrice`, and `quoteCurrency` for a single holding.
- **PortfolioSummaryDto**: Response DTO returned by `PortfolioService.getSummary`, extended with a `baseCurrency` field.
- **BaseCurrency**: The target currency into which all holding values are converted before aggregation (default: `USD`), configured via `FxProperties`.
- **QuoteCurrency**: The ISO 4217 currency code in which a holding's `current_price` is denominated, stored in `market_prices.quote_currency`.
- **Cross-Rate**: The exchange rate between two non-USD currencies, derived as `ratesFromUsd[toCurrency] / ratesFromUsd[fromCurrency]`.
- **Cache TTL**: The period during which cached FX rates are considered valid; controlled by the daily `@CacheEvict` cron in `EcbFxRateProvider`.
- **FxProperties**: Typed `@ConfigurationProperties` record binding all FX configuration from `application-local.yml` and `application-aws.yml`.
- **WireMock**: HTTP stubbing library used in integration tests to simulate the external rates API without real network calls.
- **Flyway**: Database migration tool used to evolve the `market_prices` schema.
- **PortfolioService**: Spring service in `com.wealth.portfolio` responsible for computing portfolio summaries.
- **GlobalExceptionHandler**: Spring `@ControllerAdvice` that maps domain exceptions to HTTP responses.

---

## Requirements

### Requirement 1: FxRateProvider Domain Port

**User Story:** As a portfolio service developer, I want a domain-level abstraction for FX rate retrieval, so that business logic remains decoupled from any specific rate source.

#### Acceptance Criteria

1. THE FxRateProvider interface SHALL be defined in the `com.wealth.portfolio` package and SHALL NOT import any class from `com.wealth.portfolio.fx` or any infrastructure package.
2. WHEN `fromCurrency` equals `toCurrency`, THE FxRateProvider SHALL return `BigDecimal.ONE` without performing any lookup or network call.
3. WHEN a supported currency pair is requested, THE FxRateProvider SHALL return a `BigDecimal` value greater than zero.
4. IF a requested currency pair is unsupported or the rate cannot be resolved, THEN THE FxRateProvider SHALL throw `FxRateUnavailableException`.
5. THE FxRateProvider SHALL accept `fromCurrency` and `toCurrency` as non-null, non-blank ISO 4217 currency code strings.

---

### Requirement 2: StaticFxRateProvider (Local Adapter)

**User Story:** As a developer running the service locally, I want a zero-network FX rate provider, so that local development and unit tests work without any external dependencies.

#### Acceptance Criteria

1. WHILE the `local` Spring profile is active, THE StaticFxRateProvider SHALL be the sole active implementation of `FxRateProvider`.
2. THE StaticFxRateProvider SHALL serve all rates from an in-memory map loaded from `FxProperties.local().staticRates()` without making any network calls.
3. WHEN `getRate(fromCurrency, toCurrency)` is called with two currencies present in the static map, THE StaticFxRateProvider SHALL return the cross-rate computed as `ratesFromUsd[toCurrency] / ratesFromUsd[fromCurrency]`.
4. WHEN `getRate(fromCurrency, toCurrency)` is called with `fromCurrency` equal to `toCurrency`, THE StaticFxRateProvider SHALL return `BigDecimal.ONE`.
5. IF either `fromCurrency` or `toCurrency` is absent from the static map, THEN THE StaticFxRateProvider SHALL throw `FxRateUnavailableException`.
6. THE StaticFxRateProvider SHALL satisfy the inverse property: `getRate(A, B) × getRate(B, A)` SHALL equal `1` within a rounding tolerance of `0.0001` for any two supported currencies A and B.

---

### Requirement 3: EcbFxRateProvider (AWS Adapter)

**User Story:** As a portfolio service running in AWS, I want daily-refreshed FX rates fetched from a free public API, so that holdings are converted using up-to-date rates without incurring API costs or risking IP bans.

#### Acceptance Criteria

1. WHILE the `aws` Spring profile is active, THE EcbFxRateProvider SHALL be the sole active implementation of `FxRateProvider`.
2. WHEN rates are not yet cached, THE EcbFxRateProvider SHALL fetch the entire rate map in a single HTTP GET request to the configured `fx.aws.rates-url` (default: `https://open.er-api.com/v6/latest/USD`).
3. WHEN `getRate` is called multiple times within the same cache TTL window, THE EcbFxRateProvider SHALL serve all calls after the first from the cache and SHALL NOT make additional HTTP requests.
4. WHEN the daily cache eviction cron fires, THE EcbFxRateProvider SHALL evict all entries from the `fx-rates` cache so that the next `getRate` call triggers a fresh HTTP fetch.
5. IF the external HTTP call fails for any reason, THEN THE EcbFxRateProvider SHALL catch the exception, log the error, and return `BigDecimal.ONE` as a fallback without propagating an exception to the caller.
6. WHEN a valid rate map is fetched, THE EcbFxRateProvider SHALL derive cross-rates using the formula `ratesFromUsd[toCurrency] / ratesFromUsd[fromCurrency]`.
7. IF a requested currency code is absent from the fetched rate map, THEN THE EcbFxRateProvider SHALL throw `FxRateUnavailableException`.

---

### Requirement 4: FX-Normalised Portfolio Summary

**User Story:** As an investor with multi-currency holdings, I want my portfolio total value expressed in my configured base currency, so that I can see a single accurate aggregate across all assets regardless of their pricing currency.

#### Acceptance Criteria

1. WHEN `PortfolioService.getSummary` is called, THE PortfolioService SHALL query each holding's `quantity`, `current_price`, and `quote_currency` from the database.
2. WHEN computing the total value, THE PortfolioService SHALL multiply each holding's `quantity × current_price × fxRate` where `fxRate` is obtained from `FxRateProvider.getRate(quoteCurrency, baseCurrency)`.
3. WHEN a holding's `quoteCurrency` equals the configured `baseCurrency`, THE PortfolioService SHALL use `BigDecimal.ONE` as the rate and SHALL NOT call `FxRateProvider.getRate` for that holding.
4. WHEN all holdings share the same currency as the `baseCurrency`, THE PortfolioService SHALL produce a `totalValue` numerically equal to the result of the legacy `quantity × price` sum.
5. THE PortfolioSummaryDto returned by `getSummary` SHALL include a `baseCurrency` field set to the value of `FxProperties.baseCurrency()`.
6. IF `FxRateProvider.getRate` throws `FxRateUnavailableException` for any holding, THEN THE PortfolioService SHALL propagate the exception to the caller without partially accumulating a total.
7. THE PortfolioService SHALL NOT import or reference any class from the `com.wealth.portfolio.fx` infrastructure package.

---

### Requirement 5: Database Schema Migration

**User Story:** As a developer evolving the data model, I want the `market_prices` table to carry a `quote_currency` column, so that each asset's pricing currency is stored and available for FX conversion.

#### Acceptance Criteria

1. THE Flyway migration `V5__Add_Quote_Currency_To_Market_Prices.sql` SHALL add a `quote_currency VARCHAR(10) NOT NULL DEFAULT 'USD'` column to the `market_prices` table using `ADD COLUMN IF NOT EXISTS`.
2. WHEN `market_prices.quote_currency` is `NULL` for a row (pre-migration data), THE PortfolioService SQL query SHALL treat it as `'USD'` via `COALESCE(mp.quote_currency, 'USD')`.
3. THE `HoldingValuationRow` projection SHALL include a `quoteCurrency` field populated from the `quote_currency` column.

---

### Requirement 6: Configuration and Spring Profile Wiring

**User Story:** As a developer deploying to different environments, I want FX provider selection to be driven entirely by Spring profiles, so that no code changes are needed when switching between local and AWS environments.

#### Acceptance Criteria

1. WHILE the `local` profile is active, THE application SHALL inject `StaticFxRateProvider` wherever `FxRateProvider` is required.
2. WHILE the `aws` profile is active, THE application SHALL inject `EcbFxRateProvider` wherever `FxRateProvider` is required.
3. THE `FxProperties` configuration record SHALL bind all FX settings from the `fx.*` prefix in `application-local.yml` and `application-aws.yml`.
4. THE `application-local.yml` SHALL define static rates for at least `EUR`, `GBP`, `JPY`, `CAD`, `AUD`, `CHF`, and `USD` under `fx.local.static-rates`.
5. THE `application-aws.yml` SHALL define `fx.aws.rates-url` and `fx.aws.refresh-cron` and SHALL set `spring.cache.type=simple`.
6. THE `portfolio-service/build.gradle` SHALL declare `implementation 'org.springframework.boot:spring-boot-starter-cache'` and `testImplementation 'org.wiremock.integrations:wiremock-spring-boot:3.2.0'`.
7. THE `PortfolioApplication` (or a `@Configuration` class) SHALL be annotated with `@EnableCaching`.

---

### Requirement 7: Error Handling and Fault Tolerance

**User Story:** As a system operator, I want FX failures to be handled gracefully, so that transient API outages do not cause 500 errors and data integrity is preserved for genuinely unsupported currencies.

#### Acceptance Criteria

1. WHEN `GlobalExceptionHandler` handles a `FxRateUnavailableException`, THE GlobalExceptionHandler SHALL return HTTP 503 with a JSON body containing `"error": "FX rate unavailable: {from} → {to}"` and `"retryable": true`.
2. IF the external rates API is unreachable during an `EcbFxRateProvider` fetch, THEN THE EcbFxRateProvider SHALL log the error at ERROR level and return `BigDecimal.ONE` as a fallback without throwing any exception.
3. WHEN a holding's `quote_currency` in the database contains an unrecognised currency code, THE PortfolioService SHALL allow `FxRateUnavailableException` to propagate so the entire summary call fails fast rather than silently dropping the holding.
4. THE FxRateUnavailableException SHALL be an unchecked exception (`RuntimeException`) carrying the `fromCurrency`, `toCurrency`, and the originating `Throwable` cause.

---

### Requirement 8: Testing Coverage

**User Story:** As a developer maintaining this feature, I want comprehensive automated tests at unit and integration levels, so that regressions are caught before deployment.

#### Acceptance Criteria

1. THE unit test suite SHALL include tests for `PortfolioService.getSummary` using a Mockito mock of `FxRateProvider` with no Spring context, no database, and no network.
2. THE unit test suite SHALL verify that `PortfolioService.getSummary` correctly converts a multi-currency holding (e.g., 10 shares × 100 EUR × 1.08 rate = 1080.00 USD).
3. THE unit test suite SHALL verify that `PortfolioService.getSummary` does not call `FxRateProvider` when all holdings share the base currency.
4. THE unit test suite SHALL verify that `StaticFxRateProvider` satisfies the inverse property: `getRate(A, B) × getRate(B, A) ≈ 1`.
5. THE integration test for `EcbFxRateProvider` SHALL use WireMock to stub `open.er-api.com` and SHALL verify that exactly one HTTP request is made regardless of how many `getRate` calls are issued within the same cache window.
6. ALL integration tests SHALL be annotated with `@Tag("integration")` so they run under the `integrationTest` Gradle task and not the standard `test` task.
