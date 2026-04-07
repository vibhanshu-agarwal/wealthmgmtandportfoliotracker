# Phase 2 Completion Summary (2026-04-07) - v3

This file supersedes `CHANGES_PHASE2_SUMMARY_2026-04-07_v2.md`.
It includes all items from v2 plus the Phase 2 infrastructure hotfix implementation completed afterward.

## Included from v2 (unchanged)
1. Phase 2 baseline completion status for Steps 1–5.
2. Frontend API route mismatch fix (`/api/v1/portfolios/*` -> `/api/portfolio/*`).
3. JVM timezone hardening aligned with main (`Asia/Kolkata`).
4. Local market data seeding and runtime verification updates.

## Additional work after v2

### E) Phase 2 Hotfix Step 1: Redis-backed Gateway Throttling
Objective:
- Replace single-instance in-memory limiter with distributed Redis-backed API Gateway throttling.

Implemented:
1. Switched Redis image to lightweight Alpine variant in infrastructure.
2. Migrated gateway stack to reactive Spring Cloud Gateway + reactive Redis driver.
3. Replaced local filter-based limiter with route-level `RequestRateLimiter` using Redis token bucket.
4. Added `KeyResolver` bean to resolve client key by `X-Forwarded-For` or remote address.
5. Removed deprecated in-memory gateway limiter implementation.

Files changed:
- `docker-compose.yml` (Redis image -> `redis:alpine`)
- `api-gateway/build.gradle`
- `api-gateway/src/main/resources/application.yml`
- `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java` (new)
- `api-gateway/src/main/java/com/wealth/gateway/RequestRateLimitFilter.java` (removed)

Verification:
- Gateway compile passed.
- Runtime throttling test on `/api/market/prices` produced expected `429` responses under burst load.
  - Sample result from parallel spam test: `200:40, 429:260`.

### F) Phase 2 Hotfix Step 2: Kafka Dead-Letter Strategy (Portfolio Service)
Objective:
- Prevent malformed Kafka payloads from causing infinite consumer failure loops.

Implemented:
1. Added dedicated Kafka config class with:
   - `DefaultErrorHandler`
   - `DeadLetterPublishingRecoverer`
   - retry policy: 3 retries with short fixed backoff
   - DLT target: `<topic>.DLT` (e.g., `market-prices.DLT`)
2. Wired primary `@KafkaListener` to custom container factory.
3. Added secondary DLT listener to log failed payloads at `ERROR`.
4. Added consumer deserialization safety via `ErrorHandlingDeserializer` delegates so serialization failures are routed via the error handler and DLT path.

Files changed:
- `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioKafkaConfig.java` (new)
- `portfolio-service/src/main/java/com/wealth/portfolio/PriceUpdatedEventListener.java`
- `portfolio-service/src/main/resources/application.yml`

Verification:
- `:portfolio-service:compileJava` passed.
- Injected malformed records into `market-prices`; verified DLT listener logs consumption from `market-prices.DLT`.

### G) Serializer migration to non-deprecated Jackson Kafka serializers
Objective:
- Replace deprecated Spring Kafka JSON serializer/deserializer types.

Implemented:
1. Migrated Java config and YAML entries from:
   - `JsonSerializer` -> `JacksonJsonSerializer`
   - `JsonDeserializer` -> `JacksonJsonDeserializer`
2. Updated both portfolio consumer/producer config and market-data producer config.

Files changed:
- `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioKafkaConfig.java`
- `portfolio-service/src/main/resources/application.yml`
- `market-data-service/src/main/resources/application.yml`

Verification:
- `:portfolio-service:compileJava` passed.
- `:market-data-service:compileJava` passed.

## Consolidated files changed after v2
- `docker-compose.yml`
- `api-gateway/build.gradle`
- `api-gateway/src/main/resources/application.yml`
- `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java` (new)
- `api-gateway/src/main/java/com/wealth/gateway/RequestRateLimitFilter.java` (removed)
- `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioKafkaConfig.java` (new)
- `portfolio-service/src/main/java/com/wealth/portfolio/PriceUpdatedEventListener.java`
- `portfolio-service/src/main/resources/application.yml`
- `market-data-service/src/main/resources/application.yml`

## Notes
- v1/v2 are retained as historical snapshots.
- v3 is the cumulative summary including Phase 2 hotfix work.
