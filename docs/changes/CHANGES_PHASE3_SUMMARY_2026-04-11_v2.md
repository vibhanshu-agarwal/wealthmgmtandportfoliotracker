# Phase 3 Changes — 2026-04-11 v2

## Market Summarizer — Bugfixes & Hardening

Continues from [v1](CHANGES_PHASE3_SUMMARY_2026-04-11_v1.md).

---

## Changes Since v1

### Root Cause: 500 on `/api/insights/market-summary`

The endpoint was returning HTTP 500 for two reasons:

1. **Redis not reachable** — The Redis connection config (`spring.data.redis.host/port`) was in `application-local.yml`, but the base `application.yml` had no `spring.profiles.active` setting. When running locally, the `local` profile was never activated, so Spring Boot had no Redis connection factory configured.

2. **No parsing safety** — `MarketDataService.buildTickerSummary()` called `new BigDecimal(value)` directly on Redis strings with no try-catch. Any malformed value would crash the entire endpoint.

### Fix 1: Redis Config — Env Var Pattern (Same as Kafka)

Moved Redis config from `application-local.yml` into the base `application.yml` using environment variable overrides:

```yaml
spring:
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
```

This mirrors the existing Kafka pattern (`${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9094}`) — defaults to `localhost` for local dev, overridden by Docker Compose env vars in containers. Deleted `application-local.yml` (no longer needed for Redis).

### Fix 2: Safety Guards in MarketDataService

Three defensive changes to prevent 500s:

- **Parsing safety** — New `parsePrice(String)` method wraps `new BigDecimal(value)` in a try-catch. Malformed Redis values are logged and skipped instead of crashing.
- **Empty check** — If `opsForList().range()` returns null or empty, returns an empty `TickerSummary` with `null` trend instead of proceeding to trend calculation.
- **Per-ticker isolation** — The `getMarketSummary()` loop wraps each ticker in a try-catch so one bad key doesn't take down the whole response.

### Fix 3: Removed Redundant RedisConfig

Deleted `infrastructure/redis/RedisConfig.java` — Spring Boot auto-configures `StringRedisTemplate` when `spring-boot-starter-data-redis` is on the classpath. The custom bean was unnecessary and risked a bean conflict.

### Fix 4: Error Diagnostics

- Added `server.error.include-message: always` and `include-exception: true` to `application.yml` for debugging
- Added try-catch + `log.error()` in the controller's `getMarketSummary()` endpoint

### Known Issue: Gateway 404

Direct calls to `localhost:8083/api/insights/market-summary` return 200. Calls through the API Gateway (`localhost:8080`) return 404 when insight-service runs locally but the gateway runs in Docker — the gateway container can't resolve `localhost` to the host machine. Fix: run both services in the same context (both in Docker or both locally).

---

## Files Changed (Since v1)

| File                                                                      | Change                                                                                          |
| ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `insight-service/src/main/resources/application.yml`                      | Added `spring.data.redis.*` with env var overrides, `spring.profiles.active`, error diagnostics |
| `insight-service/src/main/resources/application-local.yml`                | Deleted — Redis config moved to base yml                                                        |
| `insight-service/src/main/java/.../infrastructure/redis/RedisConfig.java` | Deleted — redundant, Spring Boot auto-configures                                                |
| `insight-service/src/main/java/.../MarketDataService.java`                | Added `parsePrice()`, empty/null guards, per-ticker try-catch                                   |
| `insight-service/src/main/java/.../InsightController.java`                | Added try-catch + logging on market-summary endpoint                                            |

---

## Verification

- `curl -i http://localhost:8083/api/insights/market-summary` → HTTP 200, `{}`
- `./gradlew :insight-service:compileJava` → BUILD SUCCESSFUL
- `./gradlew :insight-service:test` → BUILD SUCCESSFUL
