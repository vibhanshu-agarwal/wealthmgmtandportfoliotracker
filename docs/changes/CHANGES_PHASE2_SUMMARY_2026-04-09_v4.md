# Changes Summary — 2026-04-09 (v4)

**Branch:** `architecture/cloud-native-extraction`
**Scope:** `api-gateway`, `portfolio-service`, `frontend`. No changes to AWS CDK scripts,
`infrastructure/`, or `market-data-service`.

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
  test source set.

#### `api-gateway/src/main/resources/application.yml`

- Removed the entire `spring.data.redis.*` block.
- Removed the per-route `RequestRateLimiter` filter from the `market-data-service` route.

#### `api-gateway/src/main/resources/application-local.yml` _(new file)_

- Created as the sole owner of all Redis and rate-limiting configuration.
- Sets `spring.data.redis.host: localhost` and `spring.data.redis.port: 6379`.
- Declares the `default-filters` block with `RequestRateLimiter` applying to all routes:
  `replenishRate: 20`, `burstCapacity: 40`, `requestedTokens: 1`.

#### `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java`

- Refactored `resolveClientIp` into a package-private static method
  `resolveKey(String forwardedFor, String remoteHost)` to enable direct unit testing.

#### `api-gateway/src/test/java/com/wealth/gateway/GatewayRateLimitConfigTest.java` _(new file)_

- 8 unit tests covering `resolveKey()` directly — no Spring context required.
- Bean smoke test: `ipKeyResolver()` returns non-null.

#### `api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java` _(new file)_

- `@Tag("integration")` — Testcontainers `redis:7-alpine`.
- 5 test cases: context loads, requests within burst allowed, burst exceeded → 429, independent
  IP buckets, `X-RateLimit-Remaining` header present.

#### `api-gateway/src/test/resources/application-local.yml` _(new file)_

- Test-scoped profile overlay: `replenishRate: 1`, `burstCapacity: 3` for fast throttling tests.

### Root Build Fix

#### `build.gradle` (root)

- Removed three stale Jackson 2 dependency pins left over from the OpenRewrite Spring Boot 4
  migration. Spring Boot 4 requires Jackson 3 (`tools.jackson`); the pins were overriding the
  BOM-managed Jackson 3 jars and causing class initialization failures across all submodules.

### Test Results (Redis Rate Limiting)

```
./gradlew :api-gateway:test            → BUILD SUCCESSFUL (8 unit tests)
./gradlew :api-gateway:integrationTest → BUILD SUCCESSFUL (5 integration tests)
```

---

## Feature: Kafka Dead-Letter Queue (DLQ) — portfolio-service

Resolved TODO: `portfolio-service/.../PriceUpdatedEventListener.java:29` — route
malformed/poison Kafka events to a Dead-Letter Topic (`market-prices.DLT`) after retries.

### Files Changed

#### `portfolio-service/build.gradle`

- Added `testImplementation 'org.testcontainers:testcontainers-kafka'`.

#### `portfolio-service/src/main/resources/application-local.yml` _(new file)_

- Configures `ErrorHandlingDeserializer` delegating to `JsonDeserializer` for the local profile.

#### `portfolio-service/src/main/java/com/wealth/portfolio/kafka/MalformedEventException.java` _(new file)_

- Typed signal for business-level validation failures. Registered as non-retryable in
  `DefaultErrorHandler` — routes directly to the DLT on first failure.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioKafkaConfig.java`

- `DefaultErrorHandler` wired with `DeadLetterPublishingRecoverer` (routing to `{topic}.DLT`)
  and `FixedBackOff(1000L, 3L)` — 3 retry attempts at 1-second intervals.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PriceUpdatedEventListener.java`

- Added explicit validation guard: throws `MalformedEventException` for null event, null/blank
  ticker, null/zero/negative price.
- Completed the `onDlt()` stub with structured error-level logging.

#### Test files _(new)_

- `PriceUpdatedEventListenerTest.java` — 8 unit tests covering all validation branches.
- `PortfolioKafkaConfigTest.java` — 1 unit test verifying `MalformedEventException` is
  registered as non-retryable.
- `DlqIntegrationTest.java` — `@Tag("integration")`, Testcontainers Kafka (KRaft) + PostgreSQL.
  3 scenarios: blank ticker → DLT, valid event → projection updated, deserialization failure →
  DLT + consumer survives.

### Test Results (Kafka DLQ)

```
./gradlew :portfolio-service:test            → BUILD SUCCESSFUL (9 unit tests)
./gradlew :portfolio-service:integrationTest → BUILD SUCCESSFUL (3 integration tests)
```

---

## Feature: Authentication & Identity Layer (Phase 1: Backend)

Implements the **"Bouncer" pattern**: the API Gateway is the sole JWT validation point.
Downstream services receive the verified user identity via an injected `X-User-Id` HTTP header
and never parse JWTs or import Spring Security.

Two runtime profiles supported with zero code changes to switch:

- **`local`** — HMAC-SHA256 symmetric JWT validation via `AUTH_JWT_SECRET` env var
- **`aws`** — RS256 asymmetric JWT validation via JWK URI from `AUTH_JWK_URI` env var

### Files Changed — `api-gateway`

- `build.gradle` — added `spring-boot-starter-oauth2-resource-server`; jjwt at
  `testImplementation` only (never on production classpath).
- `application.yml` — added `auth.jwt.secret` and `auth.jwk-uri` empty-string placeholders.
- `application-local.yml` — added `auth.jwt.secret` fallback; updated `key-resolver` ref to
  `#{@userOrIpKeyResolver}`.
- `application-aws.yml` _(new)_ — `auth.jwk-uri: ${AUTH_JWK_URI}` only; no default.
- `SecurityConfig.java` _(new)_ — `@EnableWebFluxSecurity`; CSRF/form/basic disabled;
  `/actuator/**` open; `/api/**` requires authentication.
- `JwtDecoderConfig.java` _(new)_ — profile-conditional beans: `withSecretKey()` for local,
  `withJwkSetUri()` for aws. Fails fast with `IllegalStateException` if secret is blank.
- `JwtAuthenticationFilter.java` _(new)_ — `GlobalFilter` at `HIGHEST_PRECEDENCE + 1`. Strips
  caller `X-User-Id` unconditionally, extracts `sub` claim, injects `X-User-Id: <sub>`.
  Raw JWT never logged.
- `GatewayRateLimitConfig.java` — renamed bean to `userOrIpKeyResolver`; reads
  `exchange.getPrincipal()` for the rate-limit key (race-condition-free).
- `TestJwtFactory.java` _(new, test-only)_ — mints HMAC-SHA256 JWTs; negative expiry produces
  expired tokens; canonical seed user constant.
- `GatewayRateLimitConfigTest.java` — updated bean name; added 3 principal-path test cases and
  idempotence property test.
- `JwtFilterIntegrationTest.java` _(new)_ — 6 core + 5 parameterised property tests covering
  round-trip, expiry, tampered signature, wrong key, and spoofing prevention.

### Files Changed — `portfolio-service`

- `V4__seed_local_dev_user.sql` _(new)_ — seeds UUID `00000000-0000-0000-0000-000000000001`
  with `ON CONFLICT DO NOTHING`.
- `UserNotFoundException.java` _(new)_ — mapped to HTTP 404.
- `GlobalExceptionHandler.java` _(new)_ — `MissingRequestHeaderException` → 400;
  `UserNotFoundException` → 404.
- `PortfolioService.java` — added `requireUserExists` guard before all portfolio queries.
- `PortfolioController.java` — migrated from `GET /api/portfolio/{userId}` to
  `GET /api/portfolio` with `@RequestHeader("X-User-Id")`.
- `PortfolioSummaryController.java` — removed `@RequestParam(defaultValue = "user-001")`;
  replaced with `@RequestHeader("X-User-Id")`.
- Controller unit tests rewritten with `standaloneSetup` + `setControllerAdvice`.

### Test Results (Auth & Identity — Phase 1)

```
./gradlew :api-gateway:test            → BUILD SUCCESSFUL (16 unit tests)
./gradlew :api-gateway:integrationTest → BUILD SUCCESSFUL (27 integration tests)
./gradlew :portfolio-service:test      → BUILD SUCCESSFUL (16 unit tests)
./gradlew check                        → BUILD SUCCESSFUL (all modules)
```

---

## Feature: Authentication & Identity Layer (Phase 2: Frontend)

Wired Auth.js (NextAuth v5) into the Next.js 16 App Router frontend. Every browser session is
now backed by a real HS256-signed JWT that the API Gateway validates directly. All hardcoded
`userId = "user-001"` fallbacks have been eliminated from the frontend.

### JWT Strategy

NextAuth encrypts session tokens as JWE by default, which the Spring `NimbusReactiveJwtDecoder`
cannot read. The custom `jwt.encode` / `jwt.decode` callbacks in `auth.ts` bypass JWE and
produce a plain HS256-signed JWS using `jose.SignJWT` — the exact format the API Gateway
expects. The `AUTH_JWT_SECRET` env var is shared between Next.js and the API Gateway, so no
key distribution is required.

The `__rawJwt` stash pattern threads the signed token string from `encode()` through to the
`session()` callback (where it is exposed as `session.accessToken`) without a second signing
operation.

### New Files

#### `frontend/src/auth.config.ts`

- Edge-safe NextAuth base config (no Node.js-only imports).
- Credentials provider with mock `authorize()` for `username: "user-001"` / `password: "password"` — frictionless local dev.
- `authorized` callback: redirects unauthenticated users to `/login`; redirects authenticated
  users away from `/login` to `/overview`.

#### `frontend/src/auth.ts`

- NextAuth initialization with `session: { strategy: "jwt" }`.
- `jwt.encode`: `jose.SignJWT` with `alg: "HS256"`, 1-hour expiry, signed with
  `AUTH_JWT_SECRET`. Stashes the JWS string as `token.__rawJwt`.
- `jwt.decode`: `jose.jwtVerify` with `algorithms: ["HS256"]`; returns `null` on any error.
- `callbacks.jwt`: sets `token.sub = user.id` on sign-in.
- `callbacks.session`: sets `session.user.id = token.sub` and
  `session.accessToken = token.__rawJwt`.
- Exports `{ handlers, auth, signIn, signOut }`.

#### `frontend/src/app/api/auth/[...nextauth]/route.ts`

- Standard NextAuth v5 App Router route handler — re-exports `{ GET, POST }` from `handlers`.

#### `frontend/src/middleware.ts`

- Exports `auth` from `@/auth` as the default Next.js middleware export.
- Matcher covers all routes except `_next/static`, `_next/image`, and `favicon.ico`.

#### `frontend/src/lib/api/fetchWithAuth.ts`

- `fetchWithAuth<T>` (server-side): calls `auth()` to retrieve the session; attaches
  `Authorization: Bearer <accessToken>` header.
- `fetchWithAuthClient<T>` (client-side): accepts `token: string` as a required parameter;
  used by TanStack Query `queryFn` callbacks in Client Components where `auth()` cannot be
  called.

#### `frontend/src/lib/hooks/useAuthenticatedUserId.ts`

- `useAuthenticatedUserId(): AuthenticatedUser` — calls `useSession()` and returns
  `{ userId, token, status }`.
- Returns `{ userId: "", token: "", status }` for `"loading"` and `"unauthenticated"` states.

#### `frontend/src/types/next-auth.d.ts`

- Module augmentation: `Session.user.id: string`, `Session.accessToken?: string`,
  `JWT.__rawJwt?: string`.

#### `frontend/src/components/layout/SessionProvider.tsx`

- Client-side wrapper around `next-auth/react SessionProvider` — added to the root layout.

### Modified Files

#### `frontend/src/app/layout.tsx`

- Added `<SessionProvider>` wrapping `<QueryProvider>` children so `useSession()` is available
  throughout the component tree.

#### `frontend/src/lib/api/portfolio.ts`

- Replaced private `fetchJson(path)` with `fetchJson(path, token)` delegating to
  `fetchWithAuthClient`.
- `fetchPortfolio(userId, token)` — removed `= "user-001"` default; added required `token`.
- `fetchPortfolioPerformance(userId, token, days = 30)` — same treatment; `token` moved before
  the defaulted `days` parameter to satisfy TypeScript parameter ordering rules.
- `fetchAssetAllocation(userId, token)` — same treatment.
- Internal `loadBackendPortfolio` and `loadMarketPrices` helpers updated to accept and forward
  `token`. Portfolio endpoint updated from `/api/portfolio/${userId}` to `/api/portfolio`
  (matching the Phase 1 backend route change).

#### `frontend/src/lib/apiService.ts`

- Removed private `getJson` helper.
- `fetchPortfolioSummary(userId, token)` — removed `= "user-001"` default; delegates to
  `fetchWithAuthClient`.

#### `frontend/src/lib/hooks/usePortfolio.ts`

- All four hooks (`usePortfolio`, `usePortfolioPerformance`, `useAssetAllocation`,
  `usePortfolioSummary`) now call `useAuthenticatedUserId()` internally.
- All `userId = "user-001"` default parameters removed from hook signatures.
- `enabled: status === "authenticated"` added to every `useQuery` call — no API requests are
  issued when the session is loading or absent.

#### `frontend/src/components/charts/PerformanceChart.tsx`

- Removed `"user-001"` argument from `usePortfolioPerformance("user-001", days)` →
  `usePortfolioPerformance(days)`.

#### `frontend/src/test/msw/handlers.ts`

- All handlers now assert `Authorization: Bearer` is present; return HTTP 401 if missing.
- Added `GET /api/portfolio` handler.
- Added `GET /api/market/prices` handler.

#### `frontend/vitest.config.ts`

- Added `"next/server"` alias pointing to `next/server.js` for Vitest jsdom compatibility with
  `next-auth` beta.

### New Test Files

#### `frontend/src/lib/hooks/useAuthenticatedUserId.test.ts`

- 6 tests: authenticated state returns correct `userId` and `token`; loading/unauthenticated
  states return empty strings; `userId` equals `session.user.id`; `token` equals
  `session.accessToken`; missing `accessToken` returns empty strings.

#### `frontend/src/lib/api/fetchWithAuth.test.ts`

- 6 tests: `Authorization: Bearer <token>` header always attached; 200 returns parsed JSON;
  4xx throws; 5xx throws; additional `RequestInit` options passed through; `Content-Type`
  always set.

#### `frontend/src/lib/hooks/usePortfolio.test.ts`

- 6 tests: `usePortfolio` disabled when unauthenticated; disabled when loading; fires with
  Bearer token when authenticated; `portfolioKeys` produces distinct keys for distinct user IDs;
  `usePortfolioSummary` disabled when unauthenticated; returns data when authenticated.

### Test Results (Auth & Identity — Phase 2)

```
npm run test   → 19 tests passed across 4 test files (0 failures)
npm run lint   → 0 errors
npm run build  → Compiled successfully, TypeScript clean
```

---

## Combined Test Totals (2026-04-09 v4)

| Module              | Unit tests | Integration tests |
| ------------------- | ---------- | ----------------- |
| `api-gateway`       | 16         | 27                |
| `portfolio-service` | 16         | 3                 |
| `frontend`          | 19         | —                 |
| **Total**           | **51**     | **30**            |
