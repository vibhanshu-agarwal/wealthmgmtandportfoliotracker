# Implementation Plan: Market Data Resiliency & Expansion

## Overview

Implement startup cache hydration in `market-data-service`, expand the baseline ticker universe,
integrate an external delayed Market Data Provider, and add a scheduled refresh job that keeps
MongoDB and downstream caches up to date using `PriceUpdatedEvent`.

---

## Tasks

- [x] 1. Introduce baseline ticker configuration and seeder
  - [x] 1.1 Define a central baseline ticker list (50–100 symbols) in configuration
    - Add `market.baseline.tickers` to Spring configuration (YAML/properties) via
      `BaselineTickerProperties`.
    - Populate with US tech, NSE, Crypto, and Forex tickers formatted for the chosen provider.
    - _Requirements: 3.1, 3.4_
  - [x] 1.2 Implement or update a `BaselineSeeder` component in `market-data-service`
    - On application startup, query Mongo for existing tickers and insert any missing baseline
      tickers as active.
    - Ensure seeding is idempotent across restarts (no duplicate documents).
    - _Requirements: 3.2, 3.4_

- [x] 2. Implement startup cache hydration (republish on boot)
  - [x] 2.1 Create a `StartupHydrationService` in `market-data-service`
    - After application context is ready (e.g. `ApplicationRunner` or `SmartLifecycle`), query
      Mongo for all active tickers and their latest prices.
    - Publish one `PriceUpdatedEvent` per active ticker **that has a non-null latest price** using
      the existing Kafka producer.
    - Explicitly skip newly seeded tickers that do not yet have any stored price in Mongo; those
      will be hydrated once the scheduled refresh job obtains their first real price.
    - _Requirements: 1.1, 1.2, 1.3, 1.5_
  - [x] 2.2 Add unit / slice tests for startup hydration
    - Implemented as **`StartupHydrationServiceTest`**: mocks `AssetPriceRepository` and
      `KafkaTemplate` (no embedded Mongo/Kafka) and asserts one `PriceUpdatedEvent` per priced asset,
      none for null-priced rows, and no repository writes from the service under test.
    - _Requirements: 1.4, 4.4_

- [x] 3. Integrate ExternalMarketDataClient
  - [x] 3.1 Add an `ExternalMarketDataClient` interface and implementation
    - Implement a Yahoo Finance (or equivalent) backed client using HTTP/REST or a small Java
      wrapper.
    - Support batched quote retrieval with a configurable batch size.
    - _Requirements: 2.1, 2.2, 4.2_
  - [x] 3.2 Add resilience and configuration
    - Introduce Spring Retry or resilience4j configuration for timeouts, 5xx, and 429 responses.
    - Make base URL, timeouts, retries, and API key (if required) configurable via
      `external-market-data.*` properties.
    - _Requirements: 2.3, 2.6, 4.2_
  - [x] 3.3 Unit test the client
    - **`ExternalMarketDataClientWireMockTest`**: happy-path quotes, **503** handling, and **batch
      splitting** when `batch-size` is 1 (no real Yahoo traffic). Resilience4j **`@Retry`** and YAML
      retry rules apply in the **running Spring context**; dedicated WireMock tests for **429** and
      **read timeouts** are not in the suite yet.
    - Partial-symbol failures are covered implicitly when the JSON `result` omits a symbol (empty map
      for that ticker); per-symbol HTTP failure inside one batch is provider-specific.
    - _Requirements: 2.2, 2.4_

- [x] 4. Implement scheduled refresh job
  - [x] 4.1 Add a Spring `@Scheduled` job in `market-data-service`
    - Read the configured cron expression (default **every 1 hour**) from properties.
    - Resolve the set of tracked tickers (baseline + any others stored as active).
    - Call `ExternalMarketDataClient.getLatestPrices` in batches.
    - _Requirements: 4.1, 4.2_
  - [x] 4.2 Persist refreshed prices and publish events
    - For each ticker with a new price, upsert the latest price in Mongo.
    - Publish a corresponding `PriceUpdatedEvent` for each successfully persisted update.
    - Log per-ticker outcomes (updated, skipped, failed).
    - _Requirements: 4.2, 4.3, 4.4_
  - [x] 4.3 Add integration / slice tests for the scheduled flow
    - **`MarketDataRefreshJobWireMockTest`**: calls `refreshAllTrackedTickers()` directly with mocked
      `AssetPriceRepository` / `KafkaTemplate` and a real `YahooFinanceExternalMarketDataClient` against
      WireMock (not a Testcontainers Mongo+Kafka `@SpringBootTest` slice).
    - _Requirements: 4.3, 4.5, 4.4_

- [x] 5. Wiring, observability, and configuration
  - [x] 5.1 Wire beans and profiles
    - `com.wealth.market.config.MarketDataPropertiesConfiguration` registers
      `@EnableConfigurationProperties` for `MarketSeedProperties`, `BaselineTickerProperties`, and
      `ExternalMarketDataProperties`; `MarketDataSchedulingConfiguration` enables `@Scheduled`.
    - `market-data.refresh.enabled`, `market-data.hydration.enabled`, and
      `market-data.baseline-seed.enabled` gate the refresh job, startup Kafka hydration, and baseline
      Mongo inserts respectively (`application.yml`, `application-aws.yml`, `application-prod.yml`,
      and `src/test/resources/application.yml` + `LocalMarketDataSeederIntegrationTest` dynamic props).
    - _Requirements: 1.1, 2.5, 4.1_
  - [x] 5.2 Add structured logging and metrics hooks (optional)
    - Refresh + startup hydration use MDC keys `marketDataRefreshJobId` and
      `marketDataStartupHydrationId` (see commented `logging.pattern.console` hint in `application.yml`).
    - Micrometer: timers `market.data.refresh.job`, `market.data.startup.hydration`,
      `market.data.provider.quote.batch`; counters for refresh outcomes, per-ticker results, provider
      HTTP outcomes, baseline inserts, and hydration publish count. Actuator exposes `metrics` (see
      `management.endpoints.web.exposure.include`).
    - _Requirements: Non-Functional 1_
  - [x] 5.3 Final verification
    - `./gradlew :market-data-service:test` and `:market-data-service:integrationTest` pass after
      Task 5 wiring. Full-stack smoke (insight-service + Redis) remains a manual follow-up when those
      services are running locally or in a shared environment.
    - _Requirements: 1.4, 4.4_

- [x] 6. Align specification text with repository and CI reality
  - [x] 6.1 **`tasks.md`**: corrected sub-bullets for 2.2, 3.3, and 4.3 so they describe the tests and
    toggles that actually exist (see class names above), not aspirational-only Testcontainers wording.
  - [x] 6.2 **`design.md`**: rewrote the “Testing strategy” section to list real test classes, call out
    intentional test defaults (`market-data.*` off under `src/test/resources/application.yml`), and
    document follow-ups (429/timeout WireMock, optional full slice).
  - [x] 6.3 **`requirements.md`**: glossary and wording sanity pass (e.g. Baseline Seeder description).
  - [x] 6.4 **Build verification** (authoritative for this feature branch):
    - `./gradlew :market-data-service:test`
    - `./gradlew :market-data-service:integrationTest`

---

## Notes

- The startup hydration flow should run quickly and avoid any external provider calls; it should
  only read from Mongo and publish to Kafka.
- The scheduled refresh job is the only place that should routinely call the external provider in
  production; any ad hoc or on-demand refresh endpoints should reuse the same service methods to
  avoid duplicating logic.
