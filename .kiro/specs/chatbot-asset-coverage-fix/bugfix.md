# Bugfix Requirements Document

## Introduction

The insight-service AI chatbot effectively "sees" only a small number of assets (the user observes roughly 3, e.g. AAPL/MSFT/NVDA), even though the system has been seeded with the full 160-ticker asset set. This severely limits the chatbot's scope and usefulness: most market queries return either "no data" or "I couldn't identify a single ticker symbol from your message," and the market grid shows a handful of flat (+0.00%) tickers.

Investigation identified **two independent root causes**, both of which must be fixed for the chatbot to cover the full asset set:

1. **Seeding does not propagate to Redis.** The chatbot reads tickers exclusively from Redis (`market:latest:{ticker}`, `market:history:{ticker}`, `market:tracked-tickers`), and Redis is only populated by the Kafka listener consuming `PriceUpdatedEvent` from the `market-prices` topic. The golden-state seeder (`MarketDataSeedService.seed()`) bulk-upserts all 160 tickers into MongoDB but publishes **zero** Kafka events, so seeded prices never reach the chatbot's Redis cache. Redis stays frozen at whatever was last published at boot/refresh — matching the user's "Redis only shows the initial state" symptom.

2. **The chatbot cannot parse the symbol formats it displays.** The registry is dominated by suffixed symbols — crypto `-USD` (e.g. `ROSE-USD`, `BTC-USD`), forex `=X` (e.g. `USDCHF=X`, `NZDUSD=X`), and NSE `.NS` (e.g. `RELIANCE.NS`). The ticker resolver in `ChatController` uses patterns (`\$([A-Za-z]{1,5})`, `\b([A-Z]{1,5})\b`) and a conversational extractor that strips non-letters and rejects tokens longer than 5 characters. As a result, suffixed symbols are unresolvable — even when their data is present in Redis — so the chatbot can only answer for plain ≤5-letter symbols.

This document captures the observable behavior to be corrected and the behavior that must be preserved. It does not prescribe implementation.

## Bug Analysis

### Current Behavior (Defect)

What currently happens when the bug is triggered:

1.1 WHEN the golden-state seed endpoint (`POST /api/internal/market-data/seed`) is invoked and successfully upserts all 160 registry tickers into MongoDB THEN the system does not publish any `PriceUpdatedEvent` to the `market-prices` Kafka topic, so insight-service's Redis structures (`market:latest:*`, `market:history:*`, `market:tracked-tickers`) are not updated for the seeded tickers.

1.2 WHEN a user requests the market summary after the database has been seeded with 160 tickers (without a market-data-service restart or refresh) THEN the system returns only the small set of tickers last published to Kafka at boot/refresh, not the full seeded set.

1.3 WHEN a user asks the chatbot about a crypto symbol in `-USD` format (e.g. "Can you tell me about ROSE-USD pair?") THEN the system responds "I couldn't identify a single ticker symbol from your message" because the symbol is stripped to `ROSEUSD` (7 chars, rejected) or split into ambiguous candidates (`ROSE` + `USD`).

1.4 WHEN a user asks the chatbot about a forex symbol in `=X` format (e.g. `USDCHF=X`, `NZDUSD=X`) THEN the system fails to resolve the symbol and returns the "couldn't identify a single ticker symbol" clarification response.

1.5 WHEN a user asks the chatbot about an NSE symbol in `.NS` format (e.g. `RELIANCE.NS`) THEN the system fails to resolve the symbol and returns the "couldn't identify a single ticker symbol" clarification response.

1.6 WHEN a suffixed symbol's data is present in Redis but the user references it by its full symbol THEN the system cannot match it against the tracked set because resolution discards the suffix and/or rejects the token, so the lookup against the Redis key (e.g. `market:latest:ROSE-USD`) never occurs.

### Expected Behavior (Correct)

What should happen instead:

2.1 WHEN the golden-state seed endpoint is invoked and successfully upserts the 160 registry tickers into MongoDB THEN the system SHALL publish a `PriceUpdatedEvent` for each seeded ticker (with its seeded price) to the `market-prices` Kafka topic so the data propagates to insight-service's Redis without requiring a restart.

2.2 WHEN a user requests the market summary after the database has been seeded and events have propagated THEN the system SHALL return summaries for all seeded tickers that have a non-null price (subject to the existing stale-ticker window), not just the small boot/refresh subset.

2.3 WHEN a user asks the chatbot about a crypto symbol in `-USD` format that is tracked (e.g. "Can you tell me about ROSE-USD pair?") THEN the system SHALL resolve it to the full tracked symbol (`ROSE-USD`) and return that ticker's market summary.

2.4 WHEN a user asks the chatbot about a forex symbol in `=X` format that is tracked (e.g. `USDCHF=X`) THEN the system SHALL resolve it to the full tracked symbol and return that ticker's market summary.

2.5 WHEN a user asks the chatbot about an NSE symbol in `.NS` format that is tracked (e.g. `RELIANCE.NS`) THEN the system SHALL resolve it to the full tracked symbol and return that ticker's market summary.

2.6 WHEN a resolved symbol (including suffixed formats) has data in Redis THEN the system SHALL look it up using the exact tracked symbol as the Redis key so the lookup succeeds.

### Unchanged Behavior (Regression Prevention)

Existing behavior that must be preserved:

3.1 WHEN a user asks the chatbot about a plain ≤5-letter symbol that is tracked (e.g. AAPL, MSFT, NVDA, with or without a leading `$`) THEN the system SHALL CONTINUE TO resolve it and return that ticker's market summary.

3.2 WHEN a user message contains no identifiable ticker symbol THEN the system SHALL CONTINUE TO return the existing clarification response asking the user to specify a ticker.

3.3 WHEN a user message contains common English stop words that match the uppercase pattern (e.g. "IS", "THE", "DO") THEN the system SHALL CONTINUE TO exclude them from ticker candidates.

3.4 WHEN a resolved ticker has no price data in Redis THEN the system SHALL CONTINUE TO return the existing "I don't have any data for {ticker} right now" response.

3.5 WHEN the golden-state seeder runs THEN the system SHALL CONTINUE TO upsert exactly the 160 registry tickers into MongoDB, leave documents outside the registry set untouched, and remain idempotent at the value level across repeated runs.

3.6 WHEN the Kafka listener (`InsightEventListener.onPriceUpdated`) processes a `PriceUpdatedEvent` THEN the system SHALL CONTINUE TO update the latest price, append to the capped 10-item history list, record the ticker in the tracked-tickers ZSET, and prune entries older than the 24h stale-ticker window.

3.7 WHEN `StartupHydrationService`, `LocalMarketDataSeeder`, and `MarketDataRefreshJob` publish price events THEN the system SHALL CONTINUE TO publish to the `market-prices` topic with the existing event contract and behavior.

3.8 WHEN events with a non-null price flow through the existing pipeline THEN the system SHALL CONTINUE TO compute trend percentages and build ticker summaries exactly as before.

---

## Bug Condition Methodology

### Root Cause 1 — Seeding does not emit Kafka events

**Bug Condition** — identifies the inputs that trigger the propagation defect:

```pascal
FUNCTION isBugCondition_Seed(X)
  INPUT: X = a seed invocation that upserts registry tickers into MongoDB
  OUTPUT: boolean

  // The bug occurs whenever the golden-state seeder runs: it writes to the DB
  // but emits no events, so Redis never receives the seeded prices.
  RETURN X.isGoldenStateSeed AND X.upsertedTickerCount > 0
END FUNCTION
```

**Property (Fix Checking)** — desired behavior for buggy inputs:

```pascal
// Property: seeding propagates to Redis via Kafka
FOR ALL X WHERE isBugCondition_Seed(X) DO
  events ← eventsPublishedTo("market-prices", DURING X)
  ASSERT forEach ticker IN X.upsertedTickers WITH nonNullPrice:
           events CONTAINS PriceUpdatedEvent(ticker, seededPrice(ticker))
END FOR
```

### Root Cause 2 — Resolver cannot parse suffixed symbols

**Bug Condition** — identifies the inputs that trigger the resolution defect:

```pascal
FUNCTION isBugCondition_Resolve(X)
  INPUT: X = a chat message referencing a tracked symbol S
  OUTPUT: boolean

  // The bug occurs when the referenced tracked symbol uses a suffix format
  // (-USD, =X, .NS) or otherwise exceeds the plain ≤5-letter shape.
  RETURN isTracked(S) AND hasSuffixFormat(S)   // S matches -USD | =X | .NS
END FUNCTION
```

**Property (Fix Checking)** — desired behavior for buggy inputs:

```pascal
// Property: suffixed tracked symbols resolve to themselves
FOR ALL X WHERE isBugCondition_Resolve(X) DO
  resolved ← resolveTicker'(X)
  ASSERT resolved = X.referencedSymbol            // exact tracked symbol, suffix intact
  ASSERT redisKeyUsed(resolved) = "market:latest:" + X.referencedSymbol
END FOR
```

**Preservation (Preservation Checking)** — non-buggy inputs behave identically before and after the fix:

```pascal
// Property: Preservation Checking (applies to both root causes)
FOR ALL X WHERE NOT isBugCondition_Seed(X) AND NOT isBugCondition_Resolve(X) DO
  ASSERT F(X) = F'(X)
END FOR
```

**Key Definitions:**
- **F**: the original (unfixed) behavior of `MarketDataSeedService.seed()` and `ChatController.resolveTicker()`.
- **F'**: the fixed behavior after seeding publishes events and the resolver recognizes suffixed symbols.
- **Counterexamples**: invoking `POST /api/internal/market-data/seed` leaves Redis unchanged for the 160 tickers; asking "Can you tell me about ROSE-USD pair?" returns the "couldn't identify a single ticker symbol" response.
