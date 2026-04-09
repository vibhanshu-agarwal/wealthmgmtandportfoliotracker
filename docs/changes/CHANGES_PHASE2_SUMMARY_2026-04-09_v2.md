# Changes Summary — 2026-04-09

**Branch:** `architecture/cloud-native-extraction`  
**Scope:** `api-gateway` module (v1 changes) + `portfolio-service` module (v2 additions). No changes to AWS CDK scripts, `infrastructure/`, `frontend/`, or `market-data-service`.

---

## Feature: Redis-Backed Distributed Rate Limiting

Resolved TODO: `api-gateway/.../RequestRateLimitFilter.java:81` — replaced partial, per-route in-memory rate limiting with a fully distributed, Redis-backed `RequestRateLimiter` applied globally to all routes.

---

### Files Changed

#### `api-gateway/build.gradle`

- Added `testImplementation 'org.testcontainers:testcontainers'` for Testcontainers Redis support in integration tests.
- Wired the `integrationTest` Gradle task's `testClassesDirs` and `classpath` to the module's test source set (the root `build.gradle` registers the task but does not auto-wire source sets per-module).

#### `api-gateway/src/main/resources/application.yml`

- **Removed** the entire `spring.data.redis.*` block (including env-var placeholder forms such as `${SPRING_DATA_REDIS_HOST:localhost}`).
- **Removed** the per-route `RequestRateLimiter` filter from the `market-data-service` route.
- **No `default-filters` block added** — any `RequestRateLimiter` reference in the profile-neutral YAML triggers Spring Boot's Redis autoconfiguration on startup, which crashes deployments where no Redis is present (e.g. AWS without ElastiCache). Rate limiting config must not exist in this file.

#### `api-gateway/src/main/resources/application-local.yml` _(new file)_

- Created as the **sole owner** of all Redis and rate-limiting configuration.
- Sets `spring.data.redis.host: localhost` and `spring.data.redis.port: 6379`.
- Declares the `default-filters` block with `RequestRateLimiter` applying to all routes:
  - `key-resolver: "#{@ipKeyResolver}"`
  - `redis-rate-limiter.replenishRate: 20`
  - `redis-rate-limiter.burstCapacity: 40`
  - `redis-rate-limiter.requestedTokens: 1`
- Activated via `SPRING_PROFILES_ACTIVE=local` (Docker Compose / IDE run configs).

#### `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java`

- Refactored `resolveClientIp` into a package-private static method `resolveKey(String forwardedFor, String remoteHost)` to enable direct unit testing without requiring a Spring context or codec stack.
- `ipKeyResolver()` bean and all external behaviour are unchanged.
- No `@Profile` annotation, no AWS SDK imports, no Lettuce imports — guardrails confirmed.

#### `api-gateway/src/test/java/com/wealth/gateway/GatewayRateLimitConfigTest.java` _(new file)_

- 8 unit tests covering `resolveKey()` directly — no Spring context required.
- Uses `@ParameterizedTest` + `@CsvSource` (no jqwik dependency) for:
  - Multi-hop `X-Forwarded-For` chain → first segment trimmed
  - Single IP `X-Forwarded-For` → returned as-is
  - Leading whitespace on first segment → trimmed correctly
  - Three-hop chain → first segment returned
  - No `X-Forwarded-For` → remote address returned
  - Blank `X-Forwarded-For` → remote address fallback
  - No address information → `"anonymous"`
  - Blank header + null remote → `"anonymous"`
- Bean smoke test: `ipKeyResolver()` returns non-null.

#### `api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java` _(new file)_

- `@Tag("integration")` — runs under `./gradlew :api-gateway:integrationTest` only.
- Spins up `redis:7-alpine` via Testcontainers `GenericContainer`.
- Overrides `spring.data.redis.host` / `port` via `@DynamicPropertySource`.
- Activates `local` profile via `@ActiveProfiles("local")`.
- Rate-limiter params for tests set to `replenishRate:1`, `burstCapacity:3` via `src/test/resources/application-local.yml` to keep tests fast.
- 5 test cases:
  1. `contextLoadsWithRedis` — context starts successfully with Testcontainers Redis.
  2. `requestsWithinBurstAreAllowed` — first 3 requests from same IP are non-429.
  3. `requestsExceedingBurstAreThrottled` — burst+5 requests yields at least one 429.
  4. `differentIpsHaveIndependentBuckets` — IP-B not throttled after IP-A exhausts its bucket.
  5. `rateLimitHeadersPresent` — `X-RateLimit-Remaining` header present on allowed responses.

#### `api-gateway/src/test/resources/application-local.yml` _(new file)_

- Test-scoped profile overlay setting low rate-limiter values (`replenishRate:1`, `burstCapacity:3`) so throttling tests complete quickly without waiting for token refill.

---

### Root Build Fix

#### `build.gradle` (root)

- **Removed** the three stale Jackson 2 dependency pins from `dependencyManagement`:
  ```groovy
  // Removed:
  dependency 'com.fasterxml.jackson.core:jackson-core:2.18.2'
  dependency 'com.fasterxml.jackson.core:jackson-databind:2.18.2'
  dependency 'com.fasterxml.jackson.core:jackson-annotations:2.18.2'
  ```
- These were left over from the OpenRewrite Spring Boot 4 migration. Spring Boot 4 requires Jackson 3 (`tools.jackson`), and the Jackson 2 pins were overriding the BOM-managed Jackson 3 jars, causing `tools.jackson.databind.json.JsonMapper$Builder` to fail class initialization across all submodules.
- Spring Boot 4's `spring-boot-dependencies` BOM now manages Jackson 3 versions without interference.

---

### Architectural Guardrails Enforced (Redis Rate Limiting)

- **Profile isolation** — `application.yml` contains zero Redis or rate-limiting config. All such config lives exclusively in `application-local.yml`. This prevents Spring Boot's Redis autoconfiguration from triggering in AWS deployments.
- **No cloud lock-in** — `GatewayRateLimitConfig` has no `@Profile`, no AWS SDK imports, no direct Lettuce imports. The rate-limiting abstraction is swappable via profile for AWS API Gateway usage plans or DynamoDB.
- **Framework abstraction** — Spring Cloud Gateway's built-in `RedisRateLimiter` used exclusively; no custom Redis client code.
- **Local testing first** — all throttling behaviour verified locally via Testcontainers before any cloud deployment.

---

### Test Results (Redis Rate Limiting)

```
./gradlew :api-gateway:test          → BUILD SUCCESSFUL (8 unit tests)
./gradlew :api-gateway:integrationTest → BUILD SUCCESSFUL (5 integration tests)
```

---

---

## Feature: Kafka Dead-Letter Queue (DLQ) — portfolio-service

Resolved TODO: `portfolio-service/.../PriceUpdatedEventListener.java:29` — route malformed/poison Kafka events to a Dead-Letter Topic (`market-prices.DLT`) after retries, preventing a single bad message from blocking partition progress indefinitely. Implementation is pure Spring Kafka — no cloud-vendor SDKs introduced.

---

### Files Changed

#### `portfolio-service/build.gradle`

- Added `testImplementation 'org.testcontainers:testcontainers-kafka'` to support Testcontainers Kafka in integration tests.
- No version pin — BOM is managed at root `build.gradle`.

#### `portfolio-service/src/main/resources/application-local.yml` _(new file)_

- Created as the **sole owner** of Kafka deserializer configuration for the local profile.
- Configures `value-deserializer` as `ErrorHandlingDeserializer` delegating to `JsonDeserializer`:
  ```yaml
  spring:
    kafka:
      consumer:
        value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
        properties:
          spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
          spring.json.trusted.packages: "com.wealth.market.events"
  ```
- Ensures that unparseable bytes are captured as a `DeserializationException` header and routed to the DLT rather than crashing the consumer (Requirement 5.1 / architectural nuance).

#### `portfolio-service/src/main/java/com/wealth/portfolio/kafka/MalformedEventException.java` _(new file)_

- New `final` class extending `RuntimeException` in the `com.wealth.portfolio.kafka` sub-package.
- Two constructors: `(String message)` and `(String message, Throwable cause)`.
- Typed signal for business-level validation failures on a `PriceUpdatedEvent` — distinguishes a known-bad poison pill from a transient infrastructure failure.
- Registered as non-retryable in `DefaultErrorHandler` so it bypasses the retry loop and routes directly to the DLT on first failure.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioKafkaConfig.java`

- Added `handler.addNotRetryableExceptions(MalformedEventException.class)` to the existing `priceUpdatedErrorHandler()` bean.
- `DefaultErrorHandler` is already wired with `DeadLetterPublishingRecoverer` (routing to `{topic}.DLT` with partition affinity) and `FixedBackOff(1000L, 3L)` — 3 retry attempts at 1-second intervals.
- `ErrorHandlingDeserializer` wrapping `JacksonJsonDeserializer` is already configured programmatically in `priceUpdatedConsumerFactory()` — the new `application-local.yml` mirrors this for self-documentation and profile consistency.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PriceUpdatedEventListener.java`

- Added explicit validation guard in `on()` before any delegation to `MarketPriceProjectionService`:
  - Throws `MalformedEventException` when `event` is `null`
  - Throws `MalformedEventException` when `event.ticker()` is `null` or blank
  - Throws `MalformedEventException` when `event.newPrice()` is `null`, zero, or negative
- Completed the `onDlt()` stub with structured error-level logging including topic, partition, offset, and payload.
- No partial writes to the projection store can occur on validation failure.

#### `portfolio-service/src/test/java/com/wealth/portfolio/PriceUpdatedEventListenerTest.java` _(new file)_

- 8 unit tests using Mockito — no Spring context required.
- Covers all validation branches:
  - Null event → `MalformedEventException`, projection service never called
  - Null ticker → `MalformedEventException`, projection service never called
  - Empty ticker → `MalformedEventException`, projection service never called
  - Blank (whitespace) ticker → `MalformedEventException`, projection service never called
  - Null `newPrice` → `MalformedEventException`, projection service never called
  - Zero `newPrice` → `MalformedEventException`, projection service never called
  - Negative `newPrice` → `MalformedEventException`, projection service never called
  - Valid event → `upsertLatestPrice()` called exactly once

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioKafkaConfigTest.java` _(new file)_

- 1 unit test verifying that `MalformedEventException` is registered as non-retryable.
- Constructs `DefaultErrorHandler` directly (no Spring context), fires a `MalformedEventException`, and asserts the `DeadLetterPublishingRecoverer` is invoked exactly once with zero retry attempts.

#### `portfolio-service/src/test/java/com/wealth/portfolio/DlqIntegrationTest.java` _(new file)_

- `@Tag("integration")` — runs under `./gradlew :portfolio-service:integrationTest` only.
- Spins up `ConfluentKafkaContainer` (KRaft) and `PostgreSQLContainer` via Testcontainers.
- Overrides `spring.kafka.bootstrap-servers`, `spring.datasource.*` via `@DynamicPropertySource`.
- Uses a raw `KafkaProducer<String, byte[]>` to publish test messages and a dedicated `KafkaConsumer<String, byte[]>` (unique group per test) subscribed to `market-prices.DLT` for assertions.
- Uses `Awaitility` for all async assertions; `JdbcTemplate` for projection table state.
- 3 test scenarios:
  1. `blankTicker_routesToDlt_andProjectionNotUpdated` — publishes `{"ticker":"","newPrice":100}`, asserts DLT delivery and no projection row for blank ticker.
  2. `validEvent_updatesProjection_andNothingOnDlt` — publishes a well-formed event, asserts `market_prices` row created and DLT receives nothing for that ticker.
  3. `deserializationFailure_routesToDlt_consumerSurvives` — publishes raw `not-valid-json-bytes`, asserts DLT delivery and consumer remains alive.

---

### Architectural Guardrails Enforced (Kafka DLQ)

- **Zero cloud lock-in** — no AWS SDKs (SQS, EventBridge, SNS) introduced. Dead-letter routing is handled entirely by Spring Kafka's `DeadLetterPublishingRecoverer`.
- **Profile isolation** — `application-local.yml` owns the `ErrorHandlingDeserializer` config; `application.yml` is unchanged and contains no deserializer overrides.
- **Hexagonal boundary** — `MalformedEventException` lives in `com.wealth.portfolio.kafka` (infrastructure adapter layer); domain service `MarketPriceProjectionService` is never called on validation failure.
- **Scope limit** — `infrastructure/`, `market-data-service`, `common-dto`, and AWS CDK scripts are untouched.
- **No testing bloat** — standard JUnit 5 only; no jqwik or property-based testing.

---

### Test Results (Kafka DLQ)

```
./gradlew :portfolio-service:test          → BUILD SUCCESSFUL (9 unit tests)
./gradlew :portfolio-service:integrationTest → BUILD SUCCESSFUL (3 integration tests, requires Docker)
```
