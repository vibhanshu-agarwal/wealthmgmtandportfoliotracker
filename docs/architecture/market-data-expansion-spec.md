# Design and Requirements: Market Data Resiliency & Expansion

## Overview
This specification addresses data availability and state synchronization within the `market-data-service` and `insight-service`. It resolves a distributed state bug occurring on container restarts and expands the platform's asset coverage by integrating a real-world, delayed market data provider.

## Problem Statement
1. **Cache Desync on Restart:** When containers recreate, the `insight-service` Redis cache is wiped. Because `market-data-service` sees baseline tickers already exist in Mongo, it skips seeding and emits no `PriceUpdatedEvent`s to Kafka. Downstream services remain empty.
2. **Insufficient Data Depth:** The current system relies on a small set of static/dummy baseline tickers, which limits the utility of the AI Insights chat.
3. **Lack of Real Market Updates:** There is no integration to pull actual market prices for global equities, forex, and crypto.

## Goals
- Ensure downstream caches are fully hydrated upon `market-data-service` startup, regardless of Mongo's prior state.
- Integrate a free, external Market Data Provider (e.g., Yahoo Finance API) for 12h/24h delayed data.
- Expand the seeded baseline to include major assets across NASDAQ, NSE (India), Crypto, and Forex.
- Automate periodic fetching of external prices.

## Requirements

### Requirement 1: Startup State Synchronization (Cache Hydration)
**User Story:** As the system, I must ensure all downstream services have the latest market data in their caches immediately after a restart.

#### Acceptance Criteria
1. THE `market-data-service` startup logic SHALL fetch all active tickers from the MongoDB database on boot.
2. For every active ticker, THE `market-data-service` SHALL publish a `PriceUpdatedEvent` to Kafka.
3. THIS republishing MUST occur even if the Mongo database was already seeded from a previous run.
4. Unit/Integration tests SHALL verify that Kafka events are emitted on startup when baseline data already exists in the repository.

### Requirement 2: External Market Data Integration
**User Story:** As a user, I want to query real (though slightly delayed) market data rather than dummy values.

#### Acceptance Criteria
1. THE `market-data-service` SHALL integrate a client to fetch external market data. (Recommendation: use a Yahoo Finance Java wrapper like `yfinance-api`, or direct REST calls to Yahoo's public endpoints, as it freely supports global markets).
2. The integration MUST handle remote API failures gracefully (e.g., timeouts, 429 Too Many Requests) using Spring Retry or resilience4j, falling back to the last known database price.
3. External API calls SHALL NOT occur on the critical path of user requests; they must be asynchronous or scheduled.

### Requirement 3: Baseline Ticker Expansion
**User Story:** As a user, I want a diverse portfolio covering US tech, Indian markets, Forex, and Crypto.

#### Acceptance Criteria
1. THE startup seeder SHALL include a broadened baseline of at least 50-100 popular tickers formatted correctly for the chosen provider (e.g., `AAPL`, `MSFT`, `RELIANCE.NS`, `TCS.NS`, `BTC-USD`, `EURUSD=X`).
2. The seeder SHALL only insert these if they do not already exist in Mongo, but MUST still satisfy Requirement 1 (republishing) for all of them.

### Requirement 4: Scheduled Data Refresh
**User Story:** As the system, I need to keep the database and downstream caches reasonably up to date.

#### Acceptance Criteria
1. THE `market-data-service` SHALL implement a Spring `@Scheduled` job (e.g., running every 12 or 24 hours).
2. The scheduled job SHALL fetch updated EOD/delayed prices for all tracked tickers from the external provider.
3. Upon successfully saving a new price to Mongo, the job SHALL publish a `PriceUpdatedEvent` to Kafka to update the `insight-service` Redis cache.

## Execution Notes for AI IDE
- **Task 1:** Fix the startup bug first (Req 1). It is a fast win and ensures stability for the following tasks.
- **Task 2:** Update the Seed data list (Req 3) so you have realistic symbols to test against.
- **Task 3:** Implement the Provider Integration (Req 2) and the Cron Job (Req 4) together.
- **Watch out:** Ensure you do not overwhelm the external API on startup. If fetching prices for 100 tickers, process them in batches or use bulk-quote endpoints if the provider supports it.