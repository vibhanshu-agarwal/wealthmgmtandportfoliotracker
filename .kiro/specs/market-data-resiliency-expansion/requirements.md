# Requirements Document

## Introduction

This document defines the requirements for the **Market Data Resiliency & Expansion** feature. The
feature hardens the `market-data-service` startup behaviour to guarantee cache hydration after
restarts, integrates a delayed external Market Data Provider for real prices, expands the baseline
ticker universe across multiple asset classes, and introduces a scheduled refresh job that keeps
Mongo and downstream caches reasonably up to date. The changes are additive and respect existing
event contracts (`PriceUpdatedEvent`) and downstream consumers.

---

## Glossary

- **market-data-service**: Spring Boot service responsible for persisting market prices to MongoDB
  and publishing `PriceUpdatedEvent` messages to Kafka.
- **insight-service**: Spring Boot service that consumes `PriceUpdatedEvent` events and updates a
  Redis cache used by the AI Insights chat and other read paths.
- **Baseline Seeder**: Startup component in `market-data-service` that inserts a baseline set of
  tickers into MongoDB and, after this feature, republish-es prices on boot.
- **PriceUpdatedEvent**: Shared Kafka event (in `common-dto`) containing `ticker` and `newPrice`,
  published by `market-data-service` and consumed by `insight-service` and others.
- **External Market Data Provider**: Free, delayed data API such as Yahoo Finance (REST or Java
  wrapper) providing global equity, forex, and crypto quotes.
- **Tracked Ticker**: Any ticker stored as active in the `market-data` Mongo collections and
  considered in baseline seeding and scheduled refreshes.
- **Startup Republish Job**: Logic executed when `market-data-service` boots that reads active
  tickers from Mongo and publishes `PriceUpdatedEvent` events for cache hydration.
- **Scheduled Refresh Job**: Spring `@Scheduled` task in `market-data-service` that periodically
  fetches updated prices from the external provider and updates Mongo + Kafka.

---

## Requirements

### Requirement 1: Startup State Synchronization (Cache Hydration)

**User Story:** As the system, I must ensure all downstream services have the latest market data in
their caches immediately after a restart.

#### Acceptance Criteria

1. WHEN `market-data-service` starts, THE startup logic SHALL query MongoDB for all active (tracked)
   tickers and their latest known prices.
2. FOR every active ticker returned, THE `market-data-service` SHALL publish a `PriceUpdatedEvent`
   to Kafka, regardless of whether the baseline seeder previously ran.
3. THIS republishing behaviour SHALL execute on every application startup, even when MongoDB is
   already seeded from a previous run.
4. Unit and/or integration tests in `market-data-service` SHALL verify that, given an already
   seeded Mongo database, startup publishes `PriceUpdatedEvent` messages for all active tickers.
5. THE startup hydration logic SHALL be idempotent with respect to MongoDB state (no duplicate
   inserts) and focus solely on republishing events for cache hydration.

---

### Requirement 2: External Market Data Integration

**User Story:** As a user, I want to query real (though slightly delayed) market data rather than
dummy values.

#### Acceptance Criteria

1. THE `market-data-service` SHALL integrate a client component (e.g. `ExternalMarketDataClient`)
   capable of fetching quotes from a free, delayed provider such as Yahoo Finance via REST or a
   Java wrapper library.
2. THE client SHALL support fetching batched quotes for multiple tickers in a single call where the
   provider supports it; otherwise it SHALL internally batch requests to avoid rate limiting.
3. THE external call layer SHALL employ resilience mechanisms (e.g. Spring Retry or resilience4j)
   with sensible defaults (e.g. limited retries, backoff) and SHALL treat HTTP timeouts, 5xx, and
   `429 Too Many Requests` as retryable/transient failures.
4. IF a remote call ultimately fails for a ticker (after retries), THEN `market-data-service` SHALL
   fall back to the last known price cached in MongoDB without failing the entire batch.
5. THE external integration SHALL operate strictly as a background process. Under **no
   circumstances** SHALL any user-facing API call to `insight-service` or `market-data-service`
   trigger a synchronous HTTP call to the external market data provider; all reads to these APIs
   MUST be served from internal storage (MongoDB, Redis, or in-memory caches).
6. External provider calls SHALL NOT be performed on the synchronous path of user-facing HTTP
   requests; instead they SHALL be invoked from startup hydration logic and/or scheduled jobs.
7. THE external provider API key or configuration (if required) SHALL be sourced from environment
   variables or Spring configuration properties, not hard-coded in code.

---

### Requirement 3: Baseline Ticker Expansion

**User Story:** As a user, I want a diverse portfolio covering US tech, Indian markets, Forex, and
Crypto.

#### Acceptance Criteria

1. THE baseline seeder in `market-data-service` SHALL define a broadened list of at least 50–100
   popular tickers, formatted for the chosen provider, spanning:
   - Major US tech (e.g. `AAPL`, `MSFT`, `GOOG`, `AMZN`, `NVDA`)
   - Prominent Indian equities (e.g. `RELIANCE.NS`, `TCS.NS`, `HDFCBANK.NS`)
   - Crypto pairs (e.g. `BTC-USD`, `ETH-USD`, `SOL-USD`)
   - Key Forex pairs (e.g. `EURUSD=X`, `USDINR=X`, `GBPUSD=X`)
2. ON startup, THE seeder SHALL insert any missing baseline tickers into MongoDB but SHALL NOT
   create duplicate documents for tickers that already exist.
3. EVEN WHEN tickers already exist in MongoDB, THE system SHALL still satisfy Requirement 1 by
   republishing `PriceUpdatedEvent` messages for all active tickers at startup.
4. THE expanded baseline list SHALL be defined in a single, centrally managed configuration (e.g.
   properties class or static list) so that it can be reused by both the seeder and the scheduled
   refresh job.

---

### Requirement 4: Scheduled Data Refresh

**User Story:** As the system, I need to keep the database and downstream caches reasonably up to
date.

#### Acceptance Criteria

1. THE `market-data-service` SHALL define a Spring `@Scheduled` job (cron or fixed delay) that runs
   on a configurable cadence (default every 12 or 24 hours).
2. ON each run, THE scheduled job SHALL:
   - Determine the set of tracked tickers (baseline + any user-added tickers, if supported),
   - Fetch updated end-of-day or delayed prices for those tickers from the external provider in
     batches, and
   - Persist new prices to MongoDB as the latest price for each ticker.
3. WHEN a new price is successfully persisted for a ticker, THE scheduled job SHALL publish a
   corresponding `PriceUpdatedEvent` to Kafka so that `insight-service` and other consumers update
   their caches.
4. IF the scheduled job encounters partial failures (e.g. some tickers fail to update due to
   provider errors), THEN it SHALL log failures with ticker-level granularity while still
   processing and publishing events for successful tickers.
5. THE scheduled job SHALL include basic safeguards against overwhelming the external API: batched
   requests, small concurrency, and/or simple rate-limiting between calls where necessary.
6. IF the scheduled job fails to fetch data from the external provider for any reason (including
   provider downtime, network failures, or rate-limiting such as HTTP 429), THEN the system SHALL
   NOT clear or evict existing data in MongoDB or Redis; it SHALL continue serving the last known
   prices from MongoDB, and the downstream Redis cache MUST remain intact until fresher data is
   successfully written.

---

### Non-Functional Requirements

1. **Observability**: All startup hydration, external provider calls, and scheduled refreshes SHALL
   emit structured logs with correlation IDs or job identifiers to support troubleshooting.
2. **Configuration**: Cadence of the scheduled job, provider base URL, and any API keys SHALL be
   configurable via Spring configuration (YAML/properties + profiles) without code changes.
3. **Backwards Compatibility**: Existing downstream consumers of `PriceUpdatedEvent` SHALL require
   no changes; the event schema and topic naming remain unchanged.
4. **Testing**: New functionality SHALL be covered by unit tests and at least one integration or
   slice test that verifies startup republishing and scheduled refresh behaviour end-to-end against
   MongoDB and an in-memory or mocked external provider.

