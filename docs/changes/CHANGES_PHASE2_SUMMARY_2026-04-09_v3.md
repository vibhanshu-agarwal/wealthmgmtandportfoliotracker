# Changes Summary — 2026-04-09 (v3)

**Branch:** `architecture/cloud-native-extraction`
**Scope:** `api-gateway` module + `portfolio-service` module. No changes to AWS CDK scripts,
`infrastructure/`, `frontend/`, or `market-data-service`.

---

## Feature: Redis-Backed Distributed Rate Limiting

Resolved TODO: `api-gateway/.../RequestRateLimitFilter.java:81` — replaced partial, per-route
in-memory rate limiting with a fully distributed, Redis-backed `RequestRateLimiter` applied
globally to all routes.

### Files Changed

#### `api-gateway/build.gradle`

- Added `testImplementation 'org.testcontainers:testcontainers'` for Testcontainers Redis
  support in integration tests.
- Wired the `integrationTest` Gradle task's `testClassesDirs` and `classpath` to the module's
  test source set (the root `build.gradle` registers the task but does not auto-wire source sets
  per-module).

#### `api-gateway/src/main/resources/application.yml`

- **Removed** the entire `spring.data.redis.*` block.
- **Removed** the per-route `RequestRateLimiter` filter from the `market-data-service` route.
- **No `default-filters` block added** — any `RequestRateLimiter` reference in the
  profile-neutral YAML triggers Spring Boot's Redis autoconfiguration on startup, which crashes
  deployments where no Redis is present (e.g. AWS without ElastiCache).

#### `api-gateway/src/main/resources/application-local.yml` _(new file)_

- Created as the **sole owner** of all Redis and rate-limiting configuration.
- Sets `spring.data.redis.host: localhost` and `spring.data.redis.port: 6379`.
- Declares the `default-filters` block with `RequestRateLimiter` applying to all routes:
  - `key-resolver: "#{@ipKeyResolver}"` (later updated to `#{@userOrIpKeyResolver}` — see auth section)
  - `redis-rate-limiter.replenishRate: 20`, `burstCapacity: 40`, `requestedTokens: 1`

#### `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java`

- Refactored `resolveClientIp` into a package-private static method
  `resolveKey(String forwardedFor, String remoteHost)` to enable direct unit testing.
- `ipKeyResolver()` bean and all external behaviour unchanged at this stage.

#### `api-gateway/src/test/java/com/wealth/gateway/GatewayRateLimitConfigTest.java` _(new file)_

- 8 unit tests covering `resolveKey()` directly — no Spring context required.
- Uses `@ParameterizedTest` + `@CsvSource` for multi-hop XFF chains, fallback paths, and
  anonymous key generation.
- Bean smoke test: `ipKeyResolver()` returns non-null.

#### `api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java` _(new file)_

- `@Tag("integration")` — runs under `./gradlew :api-gateway:integrationTest` only.
- Spins up `redis:7-alpine` via Testcontainers `GenericContainer`.
- 5 test cases: context loads, requests within burst allowed, burst exceeded → 429, independent
  IP buckets, `X-RateLimit-Remaining` header present.

#### `api-gateway/src/test/resources/application-local.yml` _(new file)_

- Test-scoped profile overlay: `replenishRate:1`, `burstCapacity:3` for fast throttling tests.

### Root Build Fix

#### `build.gradle` (root)

- **Removed** three stale Jackson 2 dependency pins (`jackson-core`, `jackson-databind`,
  `jackson-annotations` at `2.18.2`) left over from the OpenRewrite Spring Boot 4 migration.
  Spring Boot 4 requires Jackson 3 (`tools.jackson`); the pins were overriding the BOM-managed
  Jackson 3 jars and causing `tools.jackson.databind.json.JsonMapper$Builder` class
  initialization failures across all submodules.

### Test Results (Redis Rate Limiting)

```
./gradlew :api-gateway:test            → BUILD SUCCESSFUL (8 unit tests)
./gradlew :api-gateway:integrationTest → BUILD SUCCESSFUL (5 integration tests)
```

---

## Feature: Kafka Dead-Letter Queue (DLQ) — portfolio-service

Resolved TODO: `portfolio-service/.../PriceUpdatedEventListener.java:29` — route
malformed/poison Kafka events to a Dead-Letter Topic (`market-prices.DLT`) after retries.
Implementation is pure Spring Kafka — no cloud-vendor SDKs introduced.

### Files Changed

#### `portfolio-service/build.gradle`

- Added `testImplementation 'org.testcontainers:testcontainers-kafka'`.

#### `portfolio-service/src/main/resources/application-local.yml` _(new file)_

- Configures `ErrorHandlingDeserializer` delegating to `JsonDeserializer` for the local profile,
  ensuring unparseable bytes are captured as a `DeserializationException` header and routed to
  the DLT rather than crashing the consumer.

#### `portfolio-service/src/main/java/com/wealth/portfolio/kafka/MalformedEventException.java` _(new file)_

- Typed signal for business-level validation failures on a `PriceUpdatedEvent`.
- Registered as non-retryable in `DefaultErrorHandler` — bypasses the retry loop and routes
  directly to the DLT on first failure.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioKafkaConfig.java`

- Added `handler.addNotRetryableExceptions(MalformedEventException.class)`.
- `DefaultErrorHandler` wired with `DeadLetterPublishingRecoverer` (routing to `{topic}.DLT`)
  and `FixedBackOff(1000L, 3L)` — 3 retry attempts at 1-second intervals.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PriceUpdatedEventListener.java`

- Added explicit validation guard: throws `MalformedEventException` for null event, null/blank
  ticker, null/zero/negative price.
- Completed the `onDlt()` stub with structured error-level logging.

#### `portfolio-service/src/test/java/com/wealth/portfolio/PriceUpdatedEventListenerTest.java` _(new file)_

- 8 unit tests covering all validation branches using Mockito.

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioKafkaConfigTest.java` _(new file)_

- 1 unit test verifying `MalformedEventException` is registered as non-retryable.

#### `portfolio-service/src/test/java/com/wealth/portfolio/DlqIntegrationTest.java` _(new file)_

- `@Tag("integration")` — Testcontainers Kafka (KRaft) + PostgreSQL.
- 3 scenarios: blank ticker → DLT, valid event → projection updated, deserialization failure →
  DLT + consumer survives.

### Test Results (Kafka DLQ)

```
./gradlew :portfolio-service:test            → BUILD SUCCESSFUL (9 unit tests)
./gradlew :portfolio-service:integrationTest → BUILD SUCCESSFUL (3 integration tests)
```

---

## Feature: Authentication & Identity Layer (Phase 1: Backend)

Resolved hard-coded user TODOs across the stack:

- `frontend/src/lib/hooks/usePortfolio.ts:27` — `userId = "user-001"` fallback (backend
  contract resolved; frontend wiring deferred to Phase 2)
- `portfolio-service/.../PortfolioSummaryController.java` — `defaultValue = "user-001"`
- `portfolio-service/.../PortfolioController.java` — unenforced `{userId}` path variable

Implements the **"Bouncer" pattern**: the API Gateway is the sole JWT validation point.
Downstream services receive the verified user identity via an injected `X-User-Id` HTTP header
and never parse JWTs or import Spring Security.

Two runtime profiles supported with zero code changes to switch:

- **`local`** — HMAC-SHA256 symmetric JWT validation via `AUTH_JWT_SECRET` env var
- **`aws`** — RS256 asymmetric JWT validation via JWK URI from `AUTH_JWK_URI` env var

### Files Changed — `api-gateway`

#### `api-gateway/build.gradle`

- Added `implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'`
  — brings in `spring-security-oauth2-resource-server`, Nimbus JOSE+JWT, and WebFlux security
  integration. No separate `spring-boot-starter-security` needed.
- Added `testImplementation 'io.jsonwebtoken:jjwt-api:0.12.6'`
- Added `testRuntimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'`
- Added `testRuntimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'`
- jjwt is **test-only** — confirmed absent from `runtimeClasspath`.

#### `api-gateway/src/main/resources/application.yml`

- Added `auth.jwt.secret: ${AUTH_JWT_SECRET:}` and `auth.jwk-uri: ${AUTH_JWK_URI:}` property
  placeholders. Both use empty-string defaults so the file parses under all profiles; blank
  values trigger `IllegalStateException` at bean construction time.

#### `api-gateway/src/main/resources/application-local.yml`

- Added `auth.jwt.secret: ${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}` fallback
  for Docker Compose development without manual env var injection.
- Updated `key-resolver` reference from `#{@ipKeyResolver}` → `#{@userOrIpKeyResolver}`.

#### `api-gateway/src/main/resources/application-aws.yml` _(new file)_

- Contains only `auth.jwk-uri: ${AUTH_JWK_URI}` — no default, must be explicitly set in the
  AWS deployment environment.

#### `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java` _(new file)_

- `@EnableWebFluxSecurity` — reactive Spring Security filter chain.
- CSRF, form login, and HTTP Basic disabled (stateless JWT API).
- `/actuator/**` permitted; `/api/**` and all other exchanges require authentication.
- `oauth2ResourceServer` with `jwt(withDefaults())` — Spring Security handles 401 for
  missing/invalid/expired tokens before any `GlobalFilter` runs.

#### `api-gateway/src/main/java/com/wealth/gateway/JwtDecoderConfig.java` _(new file)_

- `@Bean @Profile("local")` — `NimbusReactiveJwtDecoder.withSecretKey()` using HMAC-SHA256.
  Throws `IllegalStateException` at startup if `AUTH_JWT_SECRET` is blank.
- `@Bean @Profile("aws")` — `NimbusReactiveJwtDecoder.withJwkSetUri()` using RS256. JWK set
  is cached and refreshed automatically on key rotation.
- The raw secret value is never logged at any level.

#### `api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java` _(new file)_

- Implements `GlobalFilter + Ordered` at `Ordered.HIGHEST_PRECEDENCE + 1` — runs after Spring
  Security's `WebFilter` but before routing.
- **Step 1:** Strips any caller-supplied `X-User-Id` header unconditionally (spoofing
  prevention — applies even to unauthenticated requests).
- **Step 2:** Reads `Authentication` from `ReactiveSecurityContextHolder`.
- **Step 3:** Casts to `JwtAuthenticationToken`, extracts `sub` claim.
- **Step 4:** If `sub` is null/blank → 401. Otherwise mutates request to add `X-User-Id: <sub>`
  and forwards.
- `ClassCastException` → 401; unexpected `Exception` → ERROR log + 500.
- Raw JWT token value is never logged; `sub` may be logged at DEBUG only.

#### `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java`

- Renamed bean from `ipKeyResolver` to `userOrIpKeyResolver`.
- **Key change:** `KeyResolver` now reads `exchange.getPrincipal()` and casts to
  `JwtAuthenticationToken` to extract the `sub` claim — **not** the `X-User-Id` header.
  This eliminates any filter-ordering race condition: Spring Security's `WebFilter` populates
  the principal before any `GatewayFilter` (including `RequestRateLimiter`) executes.
- Falls back to `resolveClientIp(exchange)` for unauthenticated requests.
- The `resolveKey(String forwardedFor, String remoteHost)` static method signature is
  **unchanged** — all existing unit tests remain valid.

#### `api-gateway/src/test/resources/application-local.yml`

- Updated `key-resolver` reference from `#{@ipKeyResolver}` → `#{@userOrIpKeyResolver}`.
- Added `auth.jwt.secret: test-secret-for-integration-tests-min-32-chars`.

#### `api-gateway/src/test/java/com/wealth/gateway/TestJwtFactory.java` _(new file)_

- Test-only utility (`src/test/java` only — never in production artifact).
- `mint(String sub, Duration expiry, String secret)` — builds HMAC-SHA256 signed compact JWTs
  using jjwt. Negative `expiry` durations produce expired tokens.
- Convenience overloads: `mint(sub, expiry)` using the default test secret,
  `validSeedUserToken()` for the canonical seed user with 1-hour expiry.
- Constants: `SEED_USER_ID = "00000000-0000-0000-0000-000000000001"`,
  `TEST_SECRET = "test-secret-for-integration-tests-min-32-chars"`.

#### `api-gateway/src/test/java/com/wealth/gateway/GatewayRateLimitConfigTest.java`

- Updated bean smoke test: `ipKeyResolver()` → `userOrIpKeyResolver()`.
- Added 3 new test cases for the `getPrincipal()` path using Mockito-mocked
  `ServerWebExchange`:
  - Authenticated principal with valid `sub` → returns `sub`
  - Authenticated principal with blank `sub` → falls back to IP (`"anonymous"` in mock)
  - No principal (unauthenticated) → falls back to IP (`"anonymous"` in mock)
- Added `@ParameterizedTest @CsvSource` for `resolveKey` idempotence (Property 8).
- All existing `resolveKey` test cases unchanged (static method signature unchanged).

#### `api-gateway/src/test/java/com/wealth/gateway/JwtFilterIntegrationTest.java` _(new file)_

- `@Tag("integration")`, `@Testcontainers`, `@SpringBootTest(RANDOM_PORT)`,
  `@ActiveProfiles("local")`.
- Provisions Redis via Testcontainers (reuses pattern from `RateLimitingIntegrationTest`).
- 6 core test cases:
  1. Valid JWT → non-401 (gateway proxies to upstream)
  2. No `Authorization` header → 401
  3. Expired JWT → 401
  4. Tampered signature (last byte flipped) → 401
  5. JWT with no `sub` claim → 401
  6. Spoofed `X-User-Id` header with no JWT → 401
- 5 parameterised property tests (JUnit 5 `@ParameterizedTest @MethodSource` / `@CsvSource`):
  - **P1 Round-trip:** 4 representative UUID sub values → all non-401
  - **P2 Expired:** 3 past offsets (`-1s`, `-1h`, `-1d`) → all 401
  - **P3 Tampered:** first, middle, last byte flipped → all 401
  - **P4 Wrong key:** 3 wrong secrets → all 401
  - **P5 Spoofing:** 3 `(sub, spoofed)` pairs → gateway accepts valid JWT, ignores spoofed header

### Files Changed — `portfolio-service`

#### `portfolio-service/src/main/resources/db/migration/V4__seed_local_dev_user.sql` _(new file)_

- Seeds a fixed local development user: UUID `00000000-0000-0000-0000-000000000001`,
  email `dev@local`.
- Uses `ON CONFLICT DO NOTHING` — idempotent across re-runs.
- This UUID is the canonical `sub_claim` for all local dev JWTs and integration tests.

#### `portfolio-service/src/main/java/com/wealth/portfolio/UserNotFoundException.java` _(new file)_

- `RuntimeException` subclass with message `"User not found: <userId>"`.
- Thrown by `PortfolioService` when the `X-User-Id` UUID does not match any row in the
  `users` table. Mapped to HTTP 404 by `GlobalExceptionHandler`.

#### `portfolio-service/src/main/java/com/wealth/portfolio/GlobalExceptionHandler.java` _(new file)_

- `@RestControllerAdvice` with two handlers:
  - `MissingRequestHeaderException` → HTTP 400 with body
    `{"error": "Required header 'X-User-Id' is missing"}` — indicates request bypassed the
    API Gateway.
  - `UserNotFoundException` → HTTP 404 (no body).

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioService.java`

- Added `UserRepository` constructor dependency.
- Added `requireUserExists(String userId)` private method: parses the string as a UUID,
  calls `userRepository.existsById(uuid)`, throws `UserNotFoundException` if absent or if
  the string is not a valid UUID.
- Both `getByUserId` and `getSummary` call `requireUserExists` before any portfolio query.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioController.java`

- **Before:** `GET /api/portfolio/{userId}` with `@PathVariable String userId`
- **After:** `GET /api/portfolio` with `@RequestHeader("X-User-Id") String userId`
- Route prefix `Path=/api/portfolio/**` in the gateway is unchanged.
- Returns 400 on missing header, 404 on unknown user.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioSummaryController.java`

- **Before:** `@RequestParam(defaultValue = "user-001") String userId`
- **After:** `@RequestHeader("X-User-Id") String userId`
- Hard-coded `"user-001"` default eliminated.

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioControllerTest.java`

- Rewritten to use `MockMvcBuilders.standaloneSetup` with `setControllerAdvice` (no
  `@WebMvcTest` — portfolio-service does not have the web autoconfigure slice on its test
  classpath).
- 4 test cases: valid header → 200, missing header → 400 with error body, unknown UUID → 404,
  parameterised property test for missing header always returning 400 (Property 7).

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioSummaryControllerTest.java`

- Same `standaloneSetup` pattern.
- 3 test cases: valid header → 200 with DTO, missing header → 400, parameterised property test.

### Architectural Guardrails Enforced (Auth & Identity)

- **Hexagonal boundary** — `portfolio-service`, `market-data-service`, and `insight-service`
  have zero `spring-boot-starter-security`, JWT library, or Cognito SDK on any classpath scope.
  Verified via `./gradlew :portfolio-service:dependencies`.
- **jjwt test-only** — confirmed absent from `api-gateway` `runtimeClasspath`; present only on
  `testRuntimeClasspath`.
- **Profile isolation** — `application.yml` contains only empty-string placeholders for auth
  properties. All profile-specific values live in `application-local.yml` or
  `application-aws.yml`. Switching profiles requires zero Java code changes.
- **No AWS lock-in** — `JwtDecoderConfig` uses only Spring Security OAuth2 abstractions
  (`NimbusReactiveJwtDecoder`). No `software.amazon.awssdk:cognitoidentityprovider` anywhere.
- **Race-condition-free rate limiting** — `KeyResolver` reads `exchange.getPrincipal()` (set by
  Spring Security's `WebFilter` before any `GatewayFilter` runs), not the `X-User-Id` header.
- **No testing bloat** — standard JUnit 5 `@ParameterizedTest` only; no jqwik.

### Test Results (Auth & Identity)

```
./gradlew :api-gateway:test            → BUILD SUCCESSFUL (16 unit tests)
./gradlew :api-gateway:integrationTest → BUILD SUCCESSFUL (27 integration tests)
./gradlew :portfolio-service:test      → BUILD SUCCESSFUL (16 unit tests)
./gradlew check                        → BUILD SUCCESSFUL (all modules)
```

---

## Combined Test Totals (2026-04-09)

| Module              | Unit tests | Integration tests |
| ------------------- | ---------- | ----------------- |
| `api-gateway`       | 16         | 27                |
| `portfolio-service` | 16         | 3                 |
| **Total**           | **32**     | **30**            |
