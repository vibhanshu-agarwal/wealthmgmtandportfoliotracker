# Phase 3 Changes — 2026-04-11 v1

## Market Summarizer (Phase 2.1) — Stateful Redis Aggregation in insight-service

Implements the [MARKET_SUMMARIZER.md](../agent-instructions/MARKET_SUMMARIZER.md) specification: transitions `insight-service` from stateless Kafka logging to a stateful market aggregation engine backed by Redis.

---

## Summary

The Kafka consumer (`InsightEventListener`) now persists every `PriceUpdatedEvent` into Redis via a new `MarketDataService`. Two Redis structures are maintained per ticker:

- `market:latest:{ticker}` — the most recent price (key-value)
- `market:history:{ticker}` — a capped list of the last 10 prices (sliding window, trimmed via `LTRIM`)

A new REST endpoint `GET /api/insights/market-summary` returns all tracked tickers with their latest price, 10-point history, and trend percentage (oldest→newest % change).

The `ErrorHandlingDeserializer` was already configured in `application.yml`, so poison-pill resilience is preserved. Data persists in Redis across service restarts.

---

## Files Changed

| File                                                                      | Change                                                                                    |
| ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `insight-service/build.gradle`                                            | Added `spring-boot-starter-data-redis` dependency                                         |
| `insight-service/src/main/resources/application-local.yml`                | New — Redis connection config (profile-isolated, no localhost in base yml)                |
| `insight-service/src/main/java/.../infrastructure/redis/RedisConfig.java` | New — `StringRedisTemplate` bean configuration                                            |
| `insight-service/src/main/java/.../MarketDataService.java`                | New — Redis-backed aggregation: latest price store, sliding window, trend calculation     |
| `insight-service/src/main/java/.../InsightEventListener.java`             | Refactored — delegates to `MarketDataService.processUpdate()` instead of logging only     |
| `insight-service/src/main/java/.../InsightController.java`                | Added `GET /api/insights/market-summary` endpoint                                         |
| `insight-service/src/main/java/.../dto/TickerSummary.java`                | New — response record (ticker, latestPrice, priceHistory, trendPercent)                   |
| `docker-compose.yml`                                                      | Added `redis` dependency + env vars to insight-service container                          |
| `api-gateway/src/main/resources/application.yml`                          | Fixed route predicate: `/api/insight/**` → `/api/insights/**` to match controller mapping |

---

## Architecture Notes

- Redis config follows the strict profile isolation guardrail — `spring.data.redis.*` lives in `application-local.yml`, not the base `application.yml`
- `MarketDataService` uses `StringRedisTemplate` for simplicity (prices stored as plain strings)
- Trend calculation: `((newest - oldest) / oldest) * 100`, returns `null` when fewer than 2 data points exist
- The gateway route fix (`/api/insight/**` → `/api/insights/**`) was a pre-existing mismatch — corrected as part of this change
