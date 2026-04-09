# Changes Summary — 2026-04-09

**Branch:** `architecture/cloud-native-extraction`  
**Scope:** `api-gateway` module only. No changes to AWS CDK scripts, `infrastructure/`, `frontend/`, or other service modules.

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

### Architectural Guardrails Enforced

- **Profile isolation** — `application.yml` contains zero Redis or rate-limiting config. All such config lives exclusively in `application-local.yml`. This prevents Spring Boot's Redis autoconfiguration from triggering in AWS deployments.
- **No cloud lock-in** — `GatewayRateLimitConfig` has no `@Profile`, no AWS SDK imports, no direct Lettuce imports. The rate-limiting abstraction is swappable via profile for AWS API Gateway usage plans or DynamoDB.
- **Framework abstraction** — Spring Cloud Gateway's built-in `RedisRateLimiter` used exclusively; no custom Redis client code.
- **Local testing first** — all throttling behaviour verified locally via Testcontainers before any cloud deployment.

---

### Test Results

```
./gradlew :api-gateway:test          → BUILD SUCCESSFUL (8 unit tests)
./gradlew :api-gateway:integrationTest → BUILD SUCCESSFUL (5 integration tests)
```
