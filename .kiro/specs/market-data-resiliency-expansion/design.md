# Design Document: Market Data Resiliency & Expansion

## Overview

This design ensures that `market-data-service` reliably hydrates downstream caches on startup,
integrates a delayed external Market Data Provider (e.g. Yahoo Finance) for real prices, broadens
the baseline ticker universe, and periodically refreshes data in MongoDB and Kafka. The
implementation stays within Spring Boot and Kafka primitives and reuses the existing
`PriceUpdatedEvent` contract consumed by `insight-service`.

---

## Architecture

At a high level, the feature introduces three cooperating flows inside `market-data-service`:

1. **Baseline Seeder**: Seeds/expands the baseline ticker set in Mongo if missing.
2. **Startup Hydration**: On each application boot, republishes `PriceUpdatedEvent` for all active
   tickers, independent of whether seeding occurred.
3. **Scheduled Refresh**: Periodically calls the external provider to update prices in Mongo and
   publish `PriceUpdatedEvent` events.

```mermaid
graph TD
    subgraph market-data-service
        S[Baseline Seeder\n(baseline tickers)]
        H[Startup Hydration\n(republish cached prices)]
        CRON[Scheduled Refresh Job\n(@Scheduled)]
        EMC[ExternalMarketDataClient]
        MDB[(MongoDB\nmarket data)]
        KP[Kafka Producer\nPriceUpdatedEvent]
    end

    subgraph External Provider
        YF[Yahoo Finance / Delayed API]
    end

    subgraph insight-service
        C[Kafka Consumer\nPriceUpdatedEventListener]
        R[Redis Cache\nMarket Prices]
    end

    S --> MDB
    H --> MDB
    H --> KP
    CRON --> EMC
    EMC --> YF
    CRON --> MDB
    CRON --> KP
    KP --> C
    C --> R
```

---

## Startup Flow: Seeder + Hydration

### Baseline Seeder

- Runs on application startup (e.g. via `ApplicationRunner`).
- Loads the configured baseline ticker list from a dedicated configuration component
  (e.g. `BaselineTickerProperties` or a static list in `BaselineTickerConfig`).
- For each configured ticker:
  - Checks if the ticker already exists in Mongo as an active document.
  - Inserts a new document only if missing (idempotent insert).

### Startup Hydration

- After the seeder completes (or independently, but in the same startup sequence), a
  `StartupHydrationService`:
  - Queries Mongo for all active tickers (baseline + any dynamic ones).
  - Reads the latest known price per ticker (e.g. via a `findAllActiveWithLatestPrice` repository
    method or equivalent aggregation).
  - For each ticker+price pair, publishes a `PriceUpdatedEvent` via the existing Kafka producer.
- The hydration step:
  - Does not modify Mongo state.
  - Is safe to run on every restart — it simply replays the latest known prices into Kafka so that
    `insight-service` and other consumers rebuild their caches.

---

## External Market Data Integration

### ExternalMarketDataClient

**Purpose**: Encapsulate calls to the delayed external provider and hide provider-specific details
from the rest of the domain.

**Interface (conceptual):**

```java
public interface ExternalMarketDataClient {
    Map<String, BigDecimal> getLatestPrices(Collection<String> tickers);
}
```

**Behaviour:**

- Accepts a collection of provider-formatted ticker symbols.
- Splits the collection into batches (e.g. 50–100 symbols) aligned with provider limits.
- For each batch:
  - Issues one HTTP/SDK call to fetch quotes.
  - Retries on timeouts, 5xx, and 429 responses using Spring Retry or resilience4j decorate
    patterns with limited attempts and backoff.
  - Maps provider responses into a `Map<String, BigDecimal>` of ticker → price.
- Returns a merged map across all batches; missing tickers are simply absent from the map.

### Resilience & Fallback

- External calls are only made from:
  - The scheduled refresh job.
  - Optionally, a non-blocking startup warmup path (but not from synchronous HTTP handlers).
- If a ticker is missing from the external response or all retries fail:
  - The caller (e.g. scheduled job) falls back to the last known Mongo price for that ticker.
  - No exception is thrown that would abort the batch; failures are logged at ticker granularity.

---

## Scheduled Refresh Job

### Responsibilities

- Resolve the set of **tracked tickers** (baseline configuration plus any extra tickers that users
  may have introduced, if supported).
- Fetch a map of latest delayed prices from `ExternalMarketDataClient` in batches.
- Upsert the latest price per ticker into MongoDB.
- For each successfully persisted update, publish a `PriceUpdatedEvent` to Kafka.

### Algorithm (high-level)

```text
ALGORITHM refreshAllTrackedTickers()
INPUT: none

1. tickers ← repository.findAllActiveTickers()
2. IF tickers is empty THEN return
3. priceMap ← externalMarketDataClient.getLatestPrices(tickers)
4. FOR EACH ticker IN tickers DO
     a. newPrice ← priceMap.get(ticker)
     b. IF newPrice is null THEN
          i.  lastPrice ← repository.findLatestPrice(ticker)
          ii. IF lastPrice is null THEN continue  // nothing to update
          iii. use lastPrice as fallback; do not call external again
        ELSE
          i.  persist newPrice as latest price in Mongo
     c. publish PriceUpdatedEvent(ticker, effectivePrice) to Kafka
     d. log success/failure per ticker
```

### Rate Limiting and Batching

- The job uses provider-specific batch size limits and sleeps briefly between batches when necessary
  to avoid rate limiting (e.g. small `Thread.sleep` or resilience4j rate-limiter).
- Job frequency (cron) and maximum batch size are configurable via Spring properties.

---

## Data Model & Reuse

- Baseline tickers live in a single configuration:
  - For example, `BaselineTickerProperties` bound to a YAML list, or a static `List<String>`
    constant in a dedicated class.
  - This list is reused by both the seeder and the scheduled job to ensure a single source of
    truth.
- Mongo collections:
  - Existing structures are reused; the feature only updates the latest price field and does not
    introduce new collections.
- Kafka:
  - Existing `PriceUpdatedEvent` DTO and topic (e.g. `market-prices`) are reused without schema
    change.

---

## Configuration

Recommended configuration properties (example):

```yaml
market-data:
  baseline-tickers:
    - AAPL
    - MSFT
    - GOOG
    - AMZN
    - NVDA
    - RELIANCE.NS
    - TCS.NS
    - BTC-USD
    - ETH-USD
    - EURUSD=X
    # ... expand to 50–100 tickers total
  refresh:
    enabled: true
    cron: "0 0 */12 * * *"   # every 12 hours
    batch-size: 50

external-market-data:
  provider: yahoo
  base-url: https://query1.finance.yahoo.com
  timeout-ms: 5000
  max-retries: 3
  backoff-ms: 500
  api-key: ${EXTERNAL_MARKET_DATA_API_KEY:}
```

- Profiles (`local`, `aws`, etc.) can override cron expressions or disable the scheduled job
  entirely (e.g. only run locally or only in non-prod).

---

## Error Handling & Observability

- **Startup Hydration**:
  - If publishing a `PriceUpdatedEvent` fails (e.g. transient Kafka issue), the error is logged with
    the affected ticker and exception details.
  - The hydration loop continues for other tickers; this avoids a single failure aborting the
    entire startup.
- **Scheduled Refresh**:
  - Logs per-ticker outcome (updated, skipped due to missing provider data, failed due to
    persistence error).
  - Emits a summary log with counts of successes/failures and duration per job run.
- **External Provider**:
  - Resilience4j/Spring Retry logs retries with backoff so rate-limit or outage behaviour is
    visible.
  - Failures attach the provider error code/message to logs for later inspection.

---

## Testing Strategy

### Unit Tests

- **StartupHydrationServiceTest**:
  - Given pre-seeded Mongo with N tickers and prices, assert that N `PriceUpdatedEvent`s are
    published on startup.
  - Verify that no Mongo writes occur during hydration.
- **BaselineSeederTest**:
  - When Mongo is empty, all baseline tickers are inserted once.
  - When some tickers already exist, only missing ones are inserted; existing ones are untouched.
- **ExternalMarketDataClientTest**:
  - Batching logic: large ticker list is split into provider-sized batches.
  - Resilience: timeouts and 5xx are retried up to the configured limit.
- **ScheduledRefreshJobTest**:
  - Given a mocked `ExternalMarketDataClient` map and repository, assert that Mongo is updated and
    `PriceUpdatedEvent`s are published per ticker.

### Integration / Slice Tests

- Spring Boot test with:
  - Embedded Mongo or Testcontainers MongoDB.
  - Kafka test utilities or Testcontainers Kafka.
  - A stub external provider bean that returns deterministic prices.
- Scenarios:
  - Pre-seeded Mongo + application startup → all prices republished to Kafka.
  - Scheduled job run → updated Mongo values and corresponding Kafka events.

---

## Dependencies

- Optional new libraries:
  - `spring-retry` or `resilience4j-spring-boot3` for external call resilience (depending on
    existing stack).
  - Third-party Yahoo Finance wrapper if preferred over raw REST (only if it fits existing
    dependency policies).
- Existing:
  - Spring Boot, Spring Data MongoDB, Spring Kafka remain the primary runtime stack.

---

## Security & Performance Considerations

- **Security**:
  - Any external API credentials are injected via environment variables and not logged.
  - Outbound HTTP connections honour corporate proxy and TLS configuration.
- **Performance**:
  - Startup hydration is a bounded loop over the number of active tickers; for 100–200 symbols this
    completes in milliseconds to seconds, depending on Kafka I/O.
  - Scheduled refresh runs infrequently (12–24h) and is batched; it should not materially impact
    system throughput.
  - External provider usage is rate-limited and batched to avoid being throttled.

