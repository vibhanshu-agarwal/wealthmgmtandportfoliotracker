# Changes Summary — 2026-04-09 (v7)

**Branch:** `architecture/cloud-native-extraction`
**Scope:** `api-gateway`, `portfolio-service`, `frontend`, `market-data-service`. No changes to AWS CDK scripts or `infrastructure/`.

---

## Feature: FX Currency Conversion — portfolio-service

Resolved TODO: `portfolio-service/.../PortfolioService.java:64` — replaced the raw
`SUM(quantity × price)` SQL aggregation with a per-row FX conversion loop that normalises
every holding's value into the user's configured base currency (default: USD) before
aggregating `totalValue`.

Follows hexagonal architecture: the domain port (`FxRateProvider`) lives in
`com.wealth.portfolio`; the two adapters live in `com.wealth.portfolio.fx` and are wired
exclusively via Spring profiles — no domain code imports any adapter directly.

### Architecture

- **`FxRateProvider` (domain port)** — single method `getRate(from, to): BigDecimal`.
  Same-currency pairs return `BigDecimal.ONE` without any lookup. Unsupported pairs throw
  `FxRateUnavailableException`.
- **`StaticFxRateProvider` (`@Profile("local")")** — zero-network adapter backed by an
  in-memory `ConcurrentHashMap` loaded from `fx.local.static-rates` in
  `application-local.yml`. Cross-rates derived as `ratesFromUsd[to] / ratesFromUsd[from]`.
- **`EcbFxRateProvider` (`@Profile("aws")")** — fetches the entire rate map in a single HTTP
  GET from `open.er-api.com` (free, no API key). The full `Map<String, BigDecimal>` is cached
  under a single key `"all"` via `@Cacheable` on `fetchRateMap()` — never one call per pair.
  Daily `@CacheEvict` cron keeps rates fresh without a restart.

### Enterprise Resilience

- **Bulk caching** — `@Cacheable` is placed on `fetchRateMap()`, not on `getRate()`. One HTTP
  call fetches all rates; all `getRate` calls derive the ratio locally. Prevents IP-banning.
- **Fault tolerance** — `fetchRateMap()` wraps the `RestClient` call in try-catch. On any
  exception it logs at ERROR level and returns `Map.of("USD", BigDecimal.ONE)`. In `getRate`,
  both `rateFrom` and `rateTo` are null-checked before division; if either is absent a WARN is
  logged and `BigDecimal.ONE` is returned. A EUR→GBP request during an API outage (fallback map
  contains only USD) returns `1` cleanly — no NPE, no 500.

### Files Changed

#### `portfolio-service/build.gradle`

- Added `implementation 'org.springframework.boot:spring-boot-starter-cache'`.
- Added `testImplementation 'org.wiremock.integrations:wiremock-spring-boot:3.2.0'`.

#### `portfolio-service/src/main/java/com/wealth/PortfolioApplication.java`

- Added `@EnableCaching`, `@EnableScheduling`, `@ConfigurationPropertiesScan`.

#### `portfolio-service/src/main/java/com/wealth/portfolio/FxRateProvider.java` _(new)_

- Domain port interface. No imports from `com.wealth.portfolio.fx`.

#### `portfolio-service/src/main/java/com/wealth/portfolio/FxRateUnavailableException.java` _(new)_

- Unchecked `RuntimeException` carrying `fromCurrency`, `toCurrency`, and cause.
- Message format: `"FX rate unavailable: %s → %s"`.

#### `portfolio-service/src/main/java/com/wealth/portfolio/HoldingValuationRow.java` _(new)_

- Package-private record: `assetTicker`, `quantity`, `currentPrice`, `quoteCurrency`.
- Internal projection — never serialised to HTTP responses.

#### `portfolio-service/src/main/java/com/wealth/portfolio/fx/FxProperties.java` _(new)_

- `@ConfigurationProperties(prefix = "fx")` record with nested `LocalProperties` and
  `AwsProperties`. `baseCurrency()` defaults to `"USD"` when not configured.

#### `portfolio-service/src/main/java/com/wealth/portfolio/fx/StaticFxRateProvider.java` _(new)_

- `@Service @Profile("local")`. Loads rates from `FxProperties.local().staticRates()`.
- Division uses `MathContext.DECIMAL64`. Throws `FxRateUnavailableException` for missing keys.

#### `portfolio-service/src/main/java/com/wealth/portfolio/fx/EcbFxRateProvider.java` _(new)_

- `@Service @Profile("aws")`. `fetchRateMap()` annotated `@Cacheable(value="fx-rates", key="'all'")`.
- try-catch around `RestClient` call; fallback returns `Map.of("USD", BigDecimal.ONE)`.
- `getRate()` null-checks both map entries before division; logs WARN and returns
  `BigDecimal.ONE` for missing keys (NPE guard for non-USD pairs during fallback).
- `evictDailyRates()` annotated `@Scheduled` + `@CacheEvict(allEntries=true)`.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioService.java`

- Injected `FxRateProvider` and `FxProperties` via constructor.
- Replaced single-value `queryForObject` SQL sum with a `jdbcTemplate.query` returning
  `List<HoldingValuationRow>` including `quote_currency` per row.
- FX conversion loop: same-currency rows short-circuit to `BigDecimal.ONE` (no provider call);
  cross-currency rows call `fxRateProvider.getRate(quoteCurrency, baseCurrency)`.
- Each holding value scaled to 4 decimal places with `HALF_UP`.
- `FxRateUnavailableException` propagates uncaught (fail-fast for unrecognised currencies).

#### `portfolio-service/src/main/java/com/wealth/portfolio/dto/PortfolioSummaryDto.java`

- Added `String baseCurrency` field. Frontend can now display the correct currency symbol.

#### `portfolio-service/src/main/java/com/wealth/portfolio/GlobalExceptionHandler.java`

- Added `@ExceptionHandler(FxRateUnavailableException.class)` returning HTTP 503 with body
  `{ "error": "FX rate unavailable: {from} → {to}", "retryable": true }`.

#### `portfolio-service/src/main/resources/db/migration/V5__Add_Quote_Currency_To_Market_Prices.sql` _(new)_

- `ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS quote_currency VARCHAR(10) NOT NULL DEFAULT 'USD'`.
- Legacy rows default to USD via `COALESCE` in the query; no data migration required.

#### `portfolio-service/src/main/resources/application-local.yml`

- Added `fx` block: `base-currency: USD`, static rates for USD, EUR, GBP, JPY, CAD, AUD, CHF.

#### `portfolio-service/src/main/resources/application-aws.yml` _(new)_

- `fx.aws.rates-url: https://open.er-api.com/v6/latest/USD`, `fx.aws.refresh-cron: "0 0 6 * * *"`.
- `spring.cache.type: simple` (ConcurrentHashMap — no Redis dependency for FX rates).

### Test Files

#### `portfolio-service/src/test/java/com/wealth/portfolio/fx/StaticFxRateProviderTest.java` _(new)_

- 6 unit tests: same-currency identity (Property 1), cross-rate formula EUR→USD (Property 4),
  inverse round-trip EUR/USD and GBP/JPY (Property 3), unknown from/to currency throws.

#### `portfolio-service/src/test/java/com/wealth/portfolio/fx/EcbFxRateProviderIntegrationTest.java` _(new)_

- `@Tag("integration")`. Embedded WireMock server stubs `open.er-api.com`.
- Verifies bulk caching (≤2 HTTP requests for multiple `getRate` calls — Property 5).
- Verifies correct cross-rate derivation.
- Verifies fault-tolerant fallback: API down → `BigDecimal.ONE`, no exception (Property 6).
- Verifies NPE guard: EUR→GBP during fallback (map only has USD) → `BigDecimal.ONE`.

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioServiceFxTest.java` _(new)_

- 5 unit tests (Mockito, no Spring context, no DB, no network):
  - 10 × 100 EUR × 1.08 = 1080.0000 USD (Property 7).
  - All-USD holdings: correct sum, zero `FxRateProvider` calls (Property 8).
  - `baseCurrency` field on DTO equals `FxProperties.baseCurrency()` (Property 9).
  - `FxRateUnavailableException` propagates to caller.
  - Multi-currency portfolio (USD + EUR) aggregates correctly.

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioSummaryControllerTest.java`

- Updated `PortfolioSummaryDto` constructor call to pass new `baseCurrency` argument.

### Test Results (FX Currency Conversion)

```
./gradlew :portfolio-service:test   → BUILD SUCCESSFUL (27 unit tests)
```

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

---

## Feature: Dynamic Configurable Fixture Profiles — market-data-service

Resolved TODO: `market-data-service/.../LocalMarketDataSeeder.java:52` — replaced the
hardcoded `Map<String, BigDecimal>` of six tickers with an externalized JSON fixture file
read at startup via Jackson `ObjectMapper` and Spring `ResourceLoader`. Adding a new ticker
now requires only a JSON edit — no Java recompilation.

### Architecture

- **`MarketSeedProperties` (`@ConfigurationProperties(prefix = "market.seed")`)** — typed
  record binding `boolean enabled` and `String fixturePath`. Allows the fixture path and
  enabled flag to be toggled per-profile without touching Java code.
- **`SeedAsset`** — Jackson deserialization record: `ticker`, `basePrice` (`BigDecimal`),
  `currency`. `currency` included now to align with the `quote_currency` pattern in
  `portfolio-service` and avoid a future breaking schema change.
- **`MarketSeedFixture`** — root wrapper record containing `List<SeedAsset> assets`. The
  wrapper keeps the JSON schema extensible — root-level metadata fields can be added later
  without breaking the asset list.
- **`LocalMarketDataSeeder`** — remains `@Profile("local")`. Implements `ResourceLoaderAware`
  to support both `classpath:` and `file:` resource paths. All fixture I/O is wrapped in a
  try-catch: `FileNotFoundException`, `IllegalArgumentException`, and `JsonProcessingException`
  are caught, logged at ERROR level, and swallowed — the application continues booting normally.

### Profile Isolation (double guard)

- `@Profile("local")` on the bean — primary guard; bean is never instantiated in `aws`.
- `market.seed.enabled=false` in `application-aws.yml` — belt-and-suspenders; prevents
  accidental seeding if the profile annotation were ever removed.

### Resilience Amendment

The original design specified fail-fast behaviour on fixture errors. This was corrected before
implementation: since throwing from `ApplicationRunner.run()` aborts the entire Spring Boot
startup, fixture errors now log at ERROR and return gracefully, allowing the service to start
without seed data rather than crashing.

### Files Changed

#### `market-data-service/src/main/java/com/wealth/market/MarketSeedProperties.java` _(new)_

- `@ConfigurationProperties(prefix = "market.seed")` record with `boolean enabled` and
  `String fixturePath`.

#### `market-data-service/src/main/java/com/wealth/market/SeedAsset.java` _(new)_

- Jackson deserialization record: `String ticker`, `BigDecimal basePrice`, `String currency`.

#### `market-data-service/src/main/java/com/wealth/market/MarketSeedFixture.java` _(new)_

- Root wrapper record: `List<SeedAsset> assets`.

#### `market-data-service/src/main/java/com/wealth/market/LocalMarketDataSeeder.java`

- Removed hardcoded `Map<String, BigDecimal>` and `@Value` field.
- Now implements `ResourceLoaderAware`; injects `ObjectMapper` and `MarketSeedProperties`.
- `run()` checks `props.enabled()`, resolves fixture via `resourceLoader.getResource()`,
  deserializes with `objectMapper.readValue()` (copy with `USE_BIG_DECIMAL_FOR_FLOATS`).
- try-catch covers `IllegalArgumentException`, `IOException` (including
  `JsonProcessingException`): logs ERROR, returns without rethrowing.
- Idempotency: snapshots existing tickers via `assetPriceRepository.findAll()` once before
  the loop; calls `marketPriceService.updatePrice` only for absent tickers.
- Logs "Seeded N missing market prices into MongoDB" or "all baseline tickers already present".

#### `market-data-service/src/main/java/com/wealth/market/MarketDataApplication.java`

- Added `@EnableConfigurationProperties(MarketSeedProperties.class)`.

#### `market-data-service/src/main/resources/application.yml`

- Added `market.seed.fixture-path: classpath:fixtures/market-seed-data.json` under the
  existing `market.seed` block.

#### `market-data-service/src/main/resources/application-aws.yml` _(new)_

- `market.seed.enabled: false` — belt-and-suspenders guard for the aws profile.

#### `market-data-service/src/main/resources/fixtures/market-seed-data.json` _(new)_

- 6 assets: AAPL (212.5), TSLA (276.0), BTC (70775.0), MSFT (425.3), NVDA (938.6),
  ETH (3540.5) — all USD.

#### `market-data-service/src/test/resources/fixtures/test-market-seed-data.json` _(new)_

- 2 synthetic assets: TEST1 (100.0 USD), TEST2 (200.0 USD) — hermetic, never collide with
  real tickers.

### Test Files

#### `market-data-service/src/test/java/com/wealth/market/LocalMarketDataSeederTest.java` _(new)_

- 7 unit tests (Mockito + real `ObjectMapper` with `USE_BIG_DECIMAL_FOR_FLOATS`, no Spring
  context):
  - `run_seedsAllAssets_whenDatabaseIsEmpty` — `updatePrice` called once per fixture asset.
  - `run_skipsExistingTickers_whenAlreadyPresent` — zero `updatePrice` calls when both tickers
    already in MongoDB.
  - `run_seedsOnlyMissingTickers_whenPartiallySeeded` — only the absent ticker is seeded.
  - `run_doesNothing_whenDisabled` — zero interactions with repo and service when
    `enabled=false`.
  - `run_logsErrorAndContinues_whenFixtureFileMissing` — no exception thrown; zero service
    calls.
  - `run_logsErrorAndContinues_whenFixtureMalformed` — no exception thrown; zero service calls.
  - `run_parsesFixtureCorrectly` — asserts correct `ticker`, `basePrice`, `currency` values
    from test fixture.

#### `market-data-service/src/test/java/com/wealth/market/LocalMarketDataSeederIntegrationTest.java` _(new)_

- `@Tag("integration")`, `@SpringBootTest`, `@ActiveProfiles("local")`, Testcontainers MongoDB,
  `@MockBean KafkaTemplate`.
- `contextLoads_andSeedsFixture` — all 6 fixture tickers present in MongoDB after startup.
- `seeder_isIdempotent` — running `ApplicationRunner.run(null)` a second time does not
  duplicate documents.

#### `market-data-service/src/test/java/com/wealth/market/LocalMarketDataSeederAwsProfileIntegrationTest.java` _(new)_

- `@Tag("integration")`, `@SpringBootTest`, `@ActiveProfiles("aws")`, Testcontainers MongoDB,
  `@MockBean KafkaTemplate`.
- `seeder_doesNotActivate_underAwsProfile` — asserts `localMarketDataSeeder` bean is absent
  from the application context.

### Test Results (Dynamic Market Data Seeder)

```
./gradlew :market-data-service:test            → BUILD SUCCESSFUL (7 unit tests)
./gradlew :market-data-service:integrationTest → BUILD SUCCESSFUL (3 integration tests)
```

---

## Combined Test Totals (2026-04-09 v7)

| Module                | Unit tests | Integration tests |
| --------------------- | ---------- | ----------------- |
| `api-gateway`         | 16         | 27                |
| `portfolio-service`   | 35         | 11                |
| `market-data-service` | 7          | 3                 |
| `frontend`            | 19         | —                 |
| **Total**             | **77**     | **41**            |

---

## Feature: Full-Stack Portfolio Analytics API

Resolved TODOs in `frontend/src/lib/api/portfolio.ts` (lines 66, 141, 143, 161) — replaced all
frontend-synthesised placeholder values for `bestPerformer`, `worstPerformer`, `unrealizedPnL`,
`change24hPercent`, `change24hAbsolute`, and `performanceSeries` with a unified backend analytics
contract served by a new `GET /api/portfolio/analytics` endpoint in `portfolio-service`.

### Architecture

```
Frontend (usePortfolioAnalytics)
  → API Gateway (JWT → X-User-Id)
    → PortfolioAnalyticsController
      → PortfolioAnalyticsService
        → JdbcTemplate (single CTE + UNION ALL query)
          → PostgreSQL (asset_holdings + market_prices + market_price_history)
        → FxRateProvider (per-request rate cache)
      → CacheManager (Caffeine local / Redis aws, TTL 30s, key = userId)
```

**Multi-tenant cache safety**: `@Cacheable(value = "portfolio-analytics", key = "#userId")` —
cache entries are scoped per user, preventing cross-user data leakage in the shared cache.

**Local data resilience**: when `market_price_history` has fewer than 7 distinct dates for the
user's tickers, `generateSyntheticSeries(totalValue, 7)` is invoked automatically, ensuring
`PerformanceChart.tsx` is never blank during local development.

### Files Changed — `portfolio-service`

#### `portfolio-service/build.gradle`

- Added `implementation 'com.github.ben-manes.caffeine:caffeine'` for local-profile caching.
- Added `implementation 'org.springframework.boot:spring-boot-starter-data-redis'` for
  aws-profile `RedisCacheManager` (compile-time only; not activated on `local` profile).
- Wired `integrationTest` task's `testClassesDirs` and `classpath` to the test source set
  (fixes `NO-SOURCE` for the custom Gradle task).

#### `portfolio-service/src/main/resources/db/migration/V6__Add_Portfolios_User_Id_Index.sql` _(new)_

- `CREATE INDEX IF NOT EXISTS idx_portfolios_user_id ON portfolios(user_id)` — prevents a
  sequential scan on `portfolios` in the analytics CTE filter.

#### `portfolio-service/src/main/java/com/wealth/portfolio/dto/PortfolioAnalyticsDto.java` _(new)_

- Top-level record: `totalValue`, `totalCostBasis`, `totalUnrealizedPnL`,
  `totalUnrealizedPnLPercent`, `baseCurrency`, `bestPerformer`, `worstPerformer`, `holdings`,
  `performanceSeries`.
- Nested records: `PerformerDto(ticker, change24hPercent)`, `HoldingAnalyticsDto` (9 fields
  including `avgCostBasis` placeholder and `currentValueBase` in `baseCurrency`),
  `PerformancePointDto(date, value, change)`.

#### `portfolio-service/src/main/java/com/wealth/portfolio/AnalyticsQueryRow.java` _(new)_

- Package-private record: `rowType`, `assetTicker`, `quantity`, `currentPrice`,
  `quoteCurrency`, `price24hAgo`, `historyDate`, `historyPrice`.
- Internal projection — never serialised to HTTP responses. `rowType` is either `"HOLDING"` or
  `"HISTORY"` to distinguish the two result sets from the UNION ALL query.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioAnalyticsService.java` _(new)_

- `@Cacheable(value = "portfolio-analytics", key = "#userId")` — per-user cache key prevents
  cross-user leakage.
- `ANALYTICS_SQL` constant: CTE + `UNION ALL` query returning `HOLDING` rows (current price +
  24h-ago price via `DISTINCT ON`) and `HISTORY` rows (full series) in a single round-trip.
  Uses `idx_market_price_history_ticker_observed_at` for both the 24h lookup and the range scan.
- Per-request FX rate cache (`Map<String, BigDecimal>`) — at most one `FxRateProvider.getRate`
  call per distinct `quoteCurrency` per request.
- `computeChange24hPercent` — returns `ZERO` when `price24hAgo` is null or zero (division-by-
  zero guard); otherwise `((current - ago) / ago) × 100` scaled to 4 d.p.
- `buildPerformanceSeries` — groups `HISTORY` rows by date, iterates ascending, sums
  `quantity × historyPrice × fxRate` per date. Falls back to `generateSyntheticSeries` when
  distinct date count < 7.
- `generateSyntheticSeries(anchorValue, days)` — deterministic drift + sine wave starting at
  `anchorValue × 0.92`; pins the final entry to `anchorValue`; satisfies change-consistency
  invariant (`points[0].change == 0`, `points[i].change == value[i] - value[i-1]`).
- `emptyAnalytics` — returns zero totals, empty lists, and sentinel `PerformerDto("N/A", 0)`
  for users with no holdings.

#### `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioAnalyticsController.java` _(new)_

- `@GetMapping("/analytics")` under `@RequestMapping("/api/portfolio")`.
- Extracts `@RequestHeader("X-User-Id") String userId`; delegates to
  `PortfolioAnalyticsService.getAnalytics(userId)`.
- Missing header → Spring returns `400` automatically. `UserNotFoundException` → `404` via
  existing `GlobalExceptionHandler`.

#### `portfolio-service/src/main/java/com/wealth/portfolio/CacheConfig.java` _(new)_

- `@Profile("local")` bean: `CaffeineCacheManager` for `"portfolio-analytics"`, TTL 30s.
- `@Profile("aws")` bean: `RedisCacheManager` for `"portfolio-analytics"`, TTL 30s.
- Switching between backends requires only a Spring profile change — no code modifications.

### Test Files — `portfolio-service`

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioAnalyticsServiceTest.java` _(new)_

- 7 core unit tests (Mockito, no Spring context, no DB):
  - Single holding, same currency → no `FxRateProvider` call, values pass through unchanged.
  - Single holding, foreign currency → FX rate applied correctly to `currentValueBase`.
  - Empty holdings → sentinel performers, empty series, zero totals.
  - Null `price24hAgo` → `change24hPercent == 0`, no NPE.
  - Fewer than 7 history dates → `generateSyntheticSeries` result returned (7 points, last
    value pinned to `totalValue`).
  - P&L identity: `totalUnrealizedPnL == totalValue - totalCostBasis`.
  - Unknown user → `UserNotFoundException` thrown.
- 7 `@ParameterizedTest @MethodSource` correctness property tests (no jqwik):
  - **Property 1** — `bestPerformer.change24hPercent >= worstPerformer.change24hPercent` for
    single, equal, different, and many-holding inputs.
  - **Property 2** — P&L identity across zero, positive, and fractional cost-basis scenarios.
  - **Property 3** — `totalValue == Σ(holding.currentValueBase)` for 1, 2, and 3-holding lists.
  - **Property 4** — `performanceSeries` is strictly ascending by date (real and synthetic paths).
  - **Property 5** — `series[0].change == 0`; `series[i].change == value[i] - value[i-1]`.
  - **Property 6** — 24h change formula: equal (0%), doubled (100%), halved (-50%), zero ago (0).
  - **Property 7** — `generateSyntheticSeries(anchor, days)` returns exactly `days` entries,
    last value equals `anchor`, entries ascending by date.

#### `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioAnalyticsIntegrationTest.java` _(new)_

- `@Tag("integration")`, `@Testcontainers`, `@SpringBootTest(NONE)`, `@ActiveProfiles("local")`.
- Testcontainers `postgres:16-alpine`; Flyway migrations V1–V6 run automatically.
- Kafka auto-configuration excluded via `spring.autoconfigure.exclude` — not needed for
  analytics tests.
- `MockMvc` built via `MockMvcBuilders.standaloneSetup(analyticsController)` — consistent with
  the existing controller test pattern in this project.
- 8 test cases:
  - `validUser_returns200WithDto` — HTTP 200, correct JSON shape.
  - `missingHeader_returns400` — enforces `X-User-Id` requirement.
  - `unknownUser_returns404` — `UserNotFoundException` → 404.
  - `performerOrderingInvariant` — Property 1 against real seeded data.
  - `performanceSeriesAscendingByDate` — series non-null and strictly ascending.
  - `eurHolding_fxConversionApplied` — seeds EUR holding, evicts cache, asserts
    `currentValueBase ≠ raw value` (FX rate EUR→USD ≈ 1.087 from local config).
  - `pnlIdentity` — Property 2 against real seeded data.
  - `totalValueEqualsSumOfHoldingValues` — Property 3 against real seeded data.

### Files Changed — `frontend`

#### `frontend/src/types/portfolio.ts`

- Added `HoldingAnalyticsDTO` interface: `ticker`, `quantity`, `currentPrice`,
  `currentValueBase`, `avgCostBasis`, `unrealizedPnL`, `change24hAbsolute`, `change24hPercent`,
  `quoteCurrency`.
- Added `PortfolioAnalyticsDTO` interface: all top-level analytics fields including
  `performanceSeries: PerformanceDataPoint[]` (reuses existing type).

#### `frontend/src/lib/api/portfolio.ts`

- Added `fetchPortfolioAnalytics(token: string): Promise<PortfolioAnalyticsDTO>` — calls
  `GET /api/portfolio/analytics` via `fetchWithAuthClient`.
- Removed unused `HoldingAnalyticsDTO` import (lint clean).

#### `frontend/src/lib/hooks/usePortfolio.ts`

- Added `analytics` query key: `(userId) => ["portfolio", userId, "analytics"]`.
- Added `usePortfolioAnalytics()` hook: `staleTime: 30_000`, `refetchInterval: 60_000`,
  `enabled: status === "authenticated"`.

#### `frontend/src/components/charts/PerformanceChart.tsx`

- Replaced `usePortfolioPerformance(days)` with `usePortfolioAnalytics()`.
- Chart data source is now `analytics.performanceSeries` (real backend data).
- Period return computed from `series[0].value` → `series[last].value` directly.
- Period badges updated to reflect backend-controlled series length (7D / 30D / 50D).
- Empty series state renders a graceful "No performance data available yet." card.

#### `frontend/src/components/portfolio/SummaryCards.tsx`

- Added `usePortfolioAnalytics()` call alongside existing `usePortfolio()` and
  `usePortfolioSummary()`.
- `bestPerformer` and `worstPerformer` sourced from `analytics?.bestPerformer` /
  `.worstPerformer` with fallback to `summary.bestPerformer` / `.worstPerformer`.
- `unrealizedPnLPercent` sourced from `analytics?.totalUnrealizedPnLPercent` with fallback.
- Removed `// TODO: Source best/worst performer and 24h deltas from backend analytics endpoint.`
  comment.

#### `frontend/src/components/portfolio/HoldingsTable.tsx`

- Added `usePortfolioAnalytics()` call.
- `analyticsByTicker` map built from `analytics?.holdings` for O(1) per-row lookup.
- `unrealizedPnL`, `change24hPercent`, `change24hAbsolute` merged from analytics by ticker in
  the `useMemo` rows computation; `usePortfolio()` retained for `quantity`, `totalValue`, and
  `portfolioWeight`.
- `analyticsByTicker` added to `useMemo` dependency array.

#### `frontend/src/test/msw/handlers.ts`

- Added `GET /api/portfolio/analytics` handler returning a fixture `PortfolioAnalyticsDTO`
  with two holdings (AAPL +5.26%, BTC -2.14%) and a 7-point `performanceSeries`.

#### `frontend/src/components/portfolio/SummaryCards.test.tsx`

- Added `usePortfolioAnalytics` to the `vi.mock` factory for `@/lib/hooks/usePortfolio` —
  returns a fixture analytics payload so the component renders without errors.

### Test Results (Portfolio Analytics API)

```
./gradlew :portfolio-service:test            → BUILD SUCCESSFUL (35 unit tests)
./gradlew :portfolio-service:integrationTest → BUILD SUCCESSFUL (11 integration tests)
npm run test                                 → 19 tests passed (0 failures)
```
