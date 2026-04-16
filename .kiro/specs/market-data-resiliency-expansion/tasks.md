# Implementation Plan: Market Data Resiliency & Expansion

## Overview

Implement startup cache hydration in `market-data-service`, expand the baseline ticker universe,
integrate an external delayed Market Data Provider, and add a scheduled refresh job that keeps
MongoDB and downstream caches up to date using `PriceUpdatedEvent`.

---

## Tasks

- [ ] 1. Introduce baseline ticker configuration and seeder
  - [ ] 1.1 Define a central baseline ticker list (50–100 symbols) in configuration
    - Add `market-data.baseline-tickers` to Spring configuration (YAML/properties) or a dedicated
      `BaselineTickerProperties` class.
    - Populate with US tech, NSE, Crypto, and Forex tickers formatted for the chosen provider.
    - _Requirements: 3.1, 3.4_
  - [ ] 1.2 Implement or update a `BaselineSeeder` component in `market-data-service`
    - On application startup, query Mongo for existing tickers and insert any missing baseline
      tickers as active.
    - Ensure seeding is idempotent across restarts (no duplicate documents).
    - _Requirements: 3.2, 3.4_

- [ ] 2. Implement startup cache hydration (republish on boot)
  - [ ] 2.1 Create a `StartupHydrationService` in `market-data-service`
    - After application context is ready (e.g. `ApplicationRunner` or `SmartLifecycle`), query
      Mongo for all active tickers and their latest prices.
    - Publish one `PriceUpdatedEvent` per active ticker using the existing Kafka producer.
    - _Requirements: 1.1, 1.2, 1.3, 1.5_
  - [ ] 2.2 Add unit / slice tests for startup hydration
    - Seed an in-memory/Testcontainers Mongo with baseline tickers + prices, start the context, and
      assert that Kafka receives a `PriceUpdatedEvent` for each active ticker.
    - Verify that Mongo is not modified by the hydration flow.
    - _Requirements: 1.4, 4.4_

- [ ] 3. Integrate ExternalMarketDataClient
  - [ ] 3.1 Add an `ExternalMarketDataClient` interface and implementation
    - Implement a Yahoo Finance (or equivalent) backed client using HTTP/REST or a small Java
      wrapper.
    - Support batched quote retrieval with a configurable batch size.
    - _Requirements: 2.1, 2.2, 4.2_
  - [ ] 3.2 Add resilience and configuration
    - Introduce Spring Retry or resilience4j configuration for timeouts, 5xx, and 429 responses.
    - Make base URL, timeouts, retries, and API key (if required) configurable via
      `external-market-data.*` properties.
    - _Requirements: 2.3, 2.6, 4.2_
  - [ ] 3.3 Unit test the client
    - Mock the HTTP layer / provider library to exercise batching and retry logic.
    - Verify that failures for individual tickers do not break the entire batch.
    - _Requirements: 2.2, 2.4_

- [ ] 4. Implement scheduled refresh job
  - [ ] 4.1 Add a Spring `@Scheduled` job in `market-data-service`
    - Read the configured cron expression (default 12 or 24 hours) from properties.
    - Resolve the set of tracked tickers (baseline + any others stored as active).
    - Call `ExternalMarketDataClient.getLatestPrices` in batches.
    - _Requirements: 4.1, 4.2_
  - [ ] 4.2 Persist refreshed prices and publish events
    - For each ticker with a new price, upsert the latest price in Mongo.
    - Publish a corresponding `PriceUpdatedEvent` for each successfully persisted update.
    - Log per-ticker outcomes (updated, skipped, failed).
    - _Requirements: 4.2, 4.3, 4.4_
  - [ ] 4.3 Add integration / slice tests for the scheduled flow
    - Use embedded/Testcontainers Mongo + Kafka and a stub `ExternalMarketDataClient`.
    - Trigger the scheduled method directly (no need to wait for real cron) and assert that Mongo
      is updated and Kafka sees the expected `PriceUpdatedEvent`s.
    - _Requirements: 4.3, 4.5, 4.4_

- [ ] 5. Wiring, observability, and configuration
  - [ ] 5.1 Wire beans and profiles
    - Register `BaselineSeeder`, `StartupHydrationService`, `ExternalMarketDataClient`, and the
      scheduled job beans in existing `market-data-service` configuration packages.
    - Ensure scheduled jobs can be enabled/disabled per profile (e.g. off for tests if noisy).
    - _Requirements: 1.1, 2.5, 4.1_
  - [ ] 5.2 Add structured logging and metrics hooks (optional)
    - Log job start/end, duration, and per-ticker status.
    - Optionally expose counters/timers (Micrometer) for refresh runs and provider calls.
    - _Requirements: Non-Functional 1_
  - [ ] 5.3 Final verification
    - Run the full test suite (unit + integration) and, if applicable, a local end-to-end smoke
      test: start `market-data-service` and `insight-service`, confirm Redis cache is hydrated on
      restart and after a scheduled run.
    - _Requirements: 1.4, 4.4_

---

## Notes

- The startup hydration flow should run quickly and avoid any external provider calls; it should
  only read from Mongo and publish to Kafka.
- The scheduled refresh job is the only place that should routinely call the external provider in
  production; any ad hoc or on-demand refresh endpoints should reuse the same service methods to
  avoid duplicating logic.
