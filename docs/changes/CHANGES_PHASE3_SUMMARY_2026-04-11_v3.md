# Phase 3 Changes — 2026-04-11 v3

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

## Changes Since v2: CI Fixes — All Green

### Fix 1: Remove Unused LocalStack Service from CI

The `integration-tests` job had a `localstack/localstack:latest` service container. LocalStack 2026.x requires `LOCALSTACK_AUTH_TOKEN` which wasn't configured, causing exit code 55. No integration tests actually use LocalStack — removed it entirely.

### Fix 2: Remove AWS OIDC Credentials Step

The `configure-aws-credentials` step used `secrets.AWS_ROLE_ARN` for OIDC federation, but the secret isn't set in the repo. With LocalStack removed, nothing in CI needs AWS credentials. Removed the step and dropped the `id-token: write` permission.

### Fix 3: Update Rate-Limit Test Path

`RateLimitingIntegrationTest.rateLimitHeadersPresent()` was hitting `/api/insight/summary` — a path that no longer matches any gateway route after the v1 rename from `/api/insight/**` to `/api/insights/**`. Updated to `/api/insights/market-summary`.

### Fix 4: DlqIntegrationTest — `@ActiveProfiles("local")`

The test had no `@ActiveProfiles` annotation. CI sets `SPRING_PROFILES_ACTIVE=default`, so the `CacheConfig` Caffeine bean (`@Profile("local")`) was never created, causing `NoSuchBeanDefinitionException` → context load failure → all 3 DLQ tests failed. Added `@ActiveProfiles("local")` to ensure the Caffeine `CacheManager` is available.

Also added `"default"` to `CacheConfig`'s Caffeine `@Profile` as a safety net: `@Profile({"local", "default"})`.

---

## Files Changed (Since v2)

| File                                                        | Change                                                                         |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `.github/workflows/ci.yml`                                  | Removed LocalStack service, AWS credentials step, `id-token: write` permission |
| `api-gateway/src/test/.../RateLimitingIntegrationTest.java` | Updated test path: `/api/insight/summary` → `/api/insights/market-summary`     |
| `portfolio-service/src/main/java/.../CacheConfig.java`      | Added `"default"` to Caffeine `@Profile`                                       |
| `portfolio-service/src/test/.../DlqIntegrationTest.java`    | Added `@ActiveProfiles("local")`                                               |

---

## Verification

- GitHub Actions CI: all jobs green (unit-tests + integration-tests)
- `curl -i http://localhost:8083/api/insights/market-summary` → HTTP 200, `{}`
- `./gradlew :insight-service:compileJava` → BUILD SUCCESSFUL
- `./gradlew :portfolio-service:integrationTest` → BUILD SUCCESSFUL (3/3 DLQ tests pass)
- `./gradlew :api-gateway:integrationTest` → BUILD SUCCESSFUL (27/27 tests pass)
