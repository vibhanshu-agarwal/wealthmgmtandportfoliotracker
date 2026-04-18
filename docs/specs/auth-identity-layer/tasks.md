# Implementation Plan: Authentication & Identity Layer (Phase 1: Backend)

## Overview

Implement the "Bouncer" pattern: the API Gateway becomes the sole JWT validation point,
injecting a verified `X-User-Id` header that downstream services consume without parsing JWTs.
Changes span two modules — `api-gateway` (Spring Security + JWT filter + rate-limiter update)
and `portfolio-service` (controller header migration + user existence check + seed data).

Tasks are ordered so that build/dependency changes compile first, core security infrastructure
comes before the filter that depends on it, and tests follow the production code they exercise.
The portfolio-service track is independent of the gateway track and can proceed in parallel.

---

## Tasks

- [x] 1. Update `api-gateway/build.gradle` — add OAuth2 resource server and jjwt test dependencies
  - Add `implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'`
    to the `dependencies` block (brings in spring-security-oauth2-resource-server, Nimbus JOSE+JWT,
    and WebFlux security integration — no separate spring-boot-starter-security needed)
  - Add `testImplementation 'io.jsonwebtoken:jjwt-api:0.12.6'`
  - Add `testRuntimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'`
  - Add `testRuntimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'`
  - Verify no JWT library appears under `implementation` or `runtimeOnly` scope (Req 11.3)
  - _Requirements: 4.8, 4.9, 9.2, 11.3, 11.4_

- [x] 2. Add auth property placeholders to `api-gateway/src/main/resources/application.yml`
  - Append the `auth` block with `jwt.secret: ${AUTH_JWT_SECRET:}` and `jwk-uri: ${AUTH_JWK_URI:}`
  - Both use empty-string defaults so the file parses under all profiles; blank-checks are
    enforced at bean construction time in `JwtDecoderConfig`
  - _Requirements: 8.1, 8.3_

- [x] 3. Create `SecurityConfig.java` — Spring Security reactive filter chain
  - Create `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java`
  - Annotate with `@Configuration` and `@EnableWebFluxSecurity`
  - Declare a `SecurityWebFilterChain` bean accepting `ServerHttpSecurity` and `ReactiveJwtDecoder`
  - Disable CSRF, form login, and HTTP Basic (stateless JWT API)
  - Permit `/actuator/**`; require authentication for `/api/**` and all other exchanges
  - Configure `oauth2ResourceServer` with `jwt(jwt -> jwt.decoder(jwtDecoder))`
  - Spring Security handles 401 for missing/invalid/expired tokens before any `GlobalFilter` runs
  - _Requirements: 4.1, 4.3, 4.4, 4.5, 4.8, 4.9, 4.10_

- [x] 4. Create `JwtDecoderConfig.java` — profile-conditional JWT decoder beans
  - Create `api-gateway/src/main/java/com/wealth/gateway/JwtDecoderConfig.java`
  - `@Bean @Profile("local")` method `localJwtDecoder(@Value("${auth.jwt.secret}") String secret)`:
    - Throw `IllegalStateException` with a descriptive message if `secret` is null or blank
    - Build `NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build()`
  - `@Bean @Profile("aws")` method `awsJwtDecoder(@Value("${auth.jwk-uri}") String jwkUri)`:
    - Build `NimbusReactiveJwtDecoder.withJwkSetUri(jwkUri).jwsAlgorithm(SignatureAlgorithm.RS256).build()`
  - The secret value must never be logged at any level
  - _Requirements: 4.8, 4.9, 4.10, 8.1, 8.3, 8.4, 8.5_

- [x] 5. Update `application-local.yml` (main) — add JWT secret fallback and rename key-resolver ref
  - Add `auth.jwt.secret: ${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}` under the `auth` block
  - Rename `key-resolver: "#{@ipKeyResolver}"` → `"#{@userOrIpKeyResolver}"` in the `default-filters` block
  - _Requirements: 8.2, 5.1_

- [x] 6. Create `application-aws.yml` — AWS profile JWT configuration
  - Create `api-gateway/src/main/resources/application-aws.yml`
  - Add only `auth.jwk-uri: ${AUTH_JWK_URI}` (no default — must be explicitly set in AWS env)
  - _Requirements: 8.3_

- [x] 7. Create `JwtAuthenticationFilter.java` — GlobalFilter that injects `X-User-Id`
  - Create `api-gateway/src/main/java/com/wealth/gateway/JwtAuthenticationFilter.java`
  - Implement `GlobalFilter` and `Ordered`; `getOrder()` returns `Ordered.HIGHEST_PRECEDENCE + 1`
  - Step 1: Strip any caller-supplied `X-User-Id` header unconditionally via `exchange.mutate()`
    (spoofing prevention — applies even to unauthenticated requests)
  - Step 2: Read `Authentication` from `ReactiveSecurityContextHolder.getContext()`
  - Step 3: Cast to `JwtAuthenticationToken`; extract `sub` via `token.getToken().getClaimAsString("sub")`
  - Step 4: If `sub` is null or blank → set 401 and complete; otherwise mutate request to add
    `X-User-Id: <sub>` and call `chain.filter(mutated)`
  - `onErrorResume(ClassCastException.class, ...)` → 401
  - `onErrorResume(Exception.class, ...)` → log at ERROR level, set 500
  - The raw JWT token value must never be logged; `sub` may be logged at DEBUG only
  - _Requirements: 4.2, 4.3, 4.6, 4.7, 4.11, 4.12, 12.1, 12.2, 8.5_

- [x] 8. Update `GatewayRateLimitConfig.java` — rename bean and read from `exchange.getPrincipal()`
  - Rename bean method from `ipKeyResolver()` to `userOrIpKeyResolver()`
  - Replace the bean body: call `exchange.getPrincipal()` and map the result
    - If principal is a `JwtAuthenticationToken` with a non-blank `sub` → return `sub.trim()`
    - Otherwise fall back to `resolveClientIp(exchange)`
  - Chain `.defaultIfEmpty(resolveClientIp(exchange))` for the no-principal (unauthenticated) case
  - The `resolveKey(String forwardedFor, String remoteHost)` static method signature is UNCHANGED
  - The `resolveClientIp` private helper is unchanged
  - _Requirements: 5.1, 5.2, 5.3, 5.4_

- [x] 9. Update `api-gateway/src/test/resources/application-local.yml` — rename key-resolver ref and add test JWT secret
  - Rename `key-resolver: "#{@ipKeyResolver}"` → `"#{@userOrIpKeyResolver}"`
  - Add `auth.jwt.secret: test-secret-for-integration-tests-min-32-chars` under the `auth` block
  - _Requirements: 8.2, 9.2_

- [x] 10. Create `TestJwtFactory.java` — test-only JWT minting utility
  - Create `api-gateway/src/test/java/com/wealth/gateway/TestJwtFactory.java`
  - `public final class` with private constructor (utility class)
  - `public static String mint(String sub, Duration expiry, String secret)`:
    - Use `Jwts.builder().subject(sub).issuedAt(Date.from(now)).expiration(Date.from(now.plus(expiry)))`
    - Sign with `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` and `Jwts.SIG.HS256`
    - Return `.compact()`
  - Negative `expiry` durations produce expired tokens (used in property tests)
  - Must reside exclusively in `src/test/java` — never in production artifact
  - _Requirements: 9.2, 9.3, 9.6_

- [x] 11. Update `GatewayRateLimitConfigTest.java` — rename bean smoke test and add principal-based cases
  - Rename the bean smoke test: `ipKeyResolver()` → `userOrIpKeyResolver()`
  - Add test: mock `ServerWebExchange` with a `JwtAuthenticationToken` principal carrying a valid
    `sub` → `userOrIpKeyResolver` returns that `sub` value
  - Add test: `JwtAuthenticationToken` principal with blank `sub` → falls back to IP resolution
  - Add test: no principal (unauthenticated exchange) → falls back to IP resolution
  - Add `@ParameterizedTest @CsvSource` for `resolveKey` idempotence: same `(xff, ip)` inputs
    called twice return the same key (Property 8)
  - All existing `resolveKey` test cases remain valid — the static method signature is unchanged
  - _Requirements: 5.1, 5.2, 5.3, 13.4, 13.6_

- [x] 12. Create `JwtFilterIntegrationTest.java` — end-to-end JWT filter integration tests
  - Create `api-gateway/src/test/java/com/wealth/gateway/JwtFilterIntegrationTest.java`
  - Annotate: `@Tag("integration")`, `@Testcontainers`, `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("local")`
  - Provision Redis via Testcontainers (reuse pattern from `RateLimitingIntegrationTest`)
  - Set `auth.jwt.secret` via `@DynamicPropertySource` to match `TestJwtFactory`'s secret
  - Use `WebTestClient` for all HTTP assertions
  - Test cases:
    - Valid JWT with known `sub` → non-401 response
    - No `Authorization` header → 401
    - Expired JWT (minted with negative duration) → 401
    - Tampered signature (flip last byte of the third `.`-delimited segment) → 401
    - Valid JWT with `sub` claim omitted → 401
    - Spoofed `X-User-Id` header with no JWT → 401
  - _Requirements: 4.2, 4.3, 4.4, 4.5, 4.6, 12.1, 12.2, 9.7_

  - [ ]\* 12.1 Write property test for JWT round-trip (Property 1)
    - **Property 1: JWT Round-Trip (mint → validate → X-User-Id == sub)**
    - `@ParameterizedTest @MethodSource` over a list of representative UUID `sub` values
    - For each `sub`: mint a valid JWT, send to gateway, assert `X-User-Id` forwarded equals `sub`
    - **Validates: Requirements 4.2, 4.11, 9.3, 9.4, 13.1**

  - [ ]\* 12.2 Write property test for expired JWT rejection (Property 2)
    - **Property 2: Expired JWT Always Rejected**
    - `@ParameterizedTest @MethodSource` over past expiry offsets (`-1s`, `-1h`, `-1d`)
    - For each offset: mint JWT with that past expiry, assert HTTP 401
    - **Validates: Requirements 4.5, 9.5, 13.3**

  - [ ]\* 12.3 Write property test for tampered signature rejection (Property 3)
    - **Property 3: Tampered Signature Always Rejected**
    - `@ParameterizedTest @MethodSource` flipping first, middle, and last byte of the signature segment
    - For each mutation: assert HTTP 401
    - **Validates: Requirements 4.4, 13.2**

  - [ ]\* 12.4 Write property test for wrong signing key rejection (Property 4)
    - **Property 4: Wrong Signing Key Always Rejected**
    - `@ParameterizedTest @CsvSource` over representative wrong secrets
    - For each wrong secret: mint JWT, send to gateway configured with a different secret, assert HTTP 401
    - **Validates: Requirements 13.8**

  - [ ]\* 12.5 Write property test for spoofing prevention (Property 5)
    - **Property 5: Spoofing Prevention — Injected Value Overrides Caller Header**
    - `@ParameterizedTest @MethodSource` over `(sub, spoofed)` pairs where `sub ≠ spoofed`
    - For each pair: send valid JWT with `sub` plus `X-User-Id: spoofed` header; assert downstream
      receives `X-User-Id = sub`, not `spoofed`
    - **Validates: Requirements 4.12, 12.1, 12.2**

  - [ ]\* 12.6 Write property test for filter idempotence (Property 9)
    - **Property 9: Filter Idempotence — Header Injection Is Stable**
    - `@ParameterizedTest @MethodSource` over representative UUID `sub` values
    - Simulate two sequential applications of the filter logic; assert `X-User-Id = sub` both times
    - **Validates: Requirements 13.7**

- [x] 13. Checkpoint — api-gateway unit and integration tests pass
  - Run `./gradlew :api-gateway:test` (unit tests, no Testcontainers)
  - Run `./gradlew :api-gateway:integrationTest` (Testcontainers Redis)
  - Ensure all tests pass; ask the user if questions arise.

- [x] 14. Add Flyway seed migration `V4__seed_local_dev_user.sql`
  - Create `portfolio-service/src/main/resources/db/migration/V4__seed_local_dev_user.sql`
  - Insert user with `id = '00000000-0000-0000-0000-000000000001'`, `email = 'dev@local'`, `created_at = NOW()`
  - Use `ON CONFLICT DO NOTHING` to make the migration idempotent
  - This UUID is the canonical `sub_claim` for all local dev JWTs and integration tests
  - _Requirements: 9.1, 7.1_

- [x] 15. Update `PortfolioService.java` — add user existence check
  - In `getByUserId(String userId)`: before calling `portfolioRepository.findByUserId`, query the
    `users` table (via `JdbcTemplate` or a `UserRepository`) to verify the UUID exists
  - If not found, throw a `UserNotFoundException` (create this exception class in the same package)
  - In `getSummary(String userId)`: apply the same existence check before the portfolio query
  - Add a `@ExceptionHandler(UserNotFoundException.class)` mapping to HTTP 404 in
    `GlobalExceptionHandler` (created in task 16)
  - _Requirements: 6.6_

- [x] 16. Create `GlobalExceptionHandler.java` — `@RestControllerAdvice` for portfolio-service
  - Create `portfolio-service/src/main/java/com/wealth/portfolio/GlobalExceptionHandler.java`
  - `@ExceptionHandler(MissingRequestHeaderException.class)` → `ResponseEntity.badRequest()` with
    body `{"error": "Required header '<name>' is missing"}`
  - `@ExceptionHandler(UserNotFoundException.class)` → `ResponseEntity.notFound()` (or 404 with body)
  - _Requirements: 6.5, 6.6_

- [x] 17. Update `PortfolioController.java` — migrate from path variable to `X-User-Id` header
  - Remove `@GetMapping("/{userId}")` and `@PathVariable String userId`
  - Change to `@GetMapping` (no path segment) with `@RequestHeader("X-User-Id") String userId`
  - Route becomes `GET /api/portfolio` — the gateway predicate `Path=/api/portfolio/**` is unchanged
  - Update Javadoc to reflect the new contract (400 on missing header, 404 on unknown user)
  - _Requirements: 6.1, 6.5_

- [x] 18. Update `PortfolioSummaryController.java` — migrate from `@RequestParam` to `X-User-Id` header
  - Remove `@RequestParam(defaultValue = "user-001") String userId`
  - Add `@RequestHeader("X-User-Id") String userId`
  - The hard-coded `"user-001"` default is eliminated
  - _Requirements: 6.2, 6.5_

- [x] 19. Write unit tests for portfolio-service controller and exception handler changes
  - [x]\* 19.1 Write unit tests for `PortfolioController` header migration
    - Remove tests for `GET /api/portfolio/{userId}` path variable
    - Add test: `GET /api/portfolio` with `X-User-Id` header → 200 with portfolio list
    - Add test: `GET /api/portfolio` with missing `X-User-Id` header → 400 with structured error body
    - Add test: `GET /api/portfolio` with unknown UUID in `X-User-Id` → 404
    - _Requirements: 6.1, 6.5, 6.6_

  - [x]\* 19.2 Write unit tests for `PortfolioSummaryController` header migration
    - Remove tests for `@RequestParam userId` with default `"user-001"`
    - Add test: `GET /api/portfolio/summary` with `X-User-Id` header → 200 with summary DTO
    - Add test: missing `X-User-Id` header → 400
    - _Requirements: 6.2, 6.5_

  - [x]\* 19.3 Write property test for missing header always returns 400 (Property 7)
    - **Property 7: Missing X-User-Id Header in Downstream Always Returns 400**
    - `@ParameterizedTest @MethodSource` over both `/api/portfolio` and `/api/portfolio/summary`
    - For each endpoint: send request without `X-User-Id` header; assert HTTP 400 with error body
      containing the header name
    - **Validates: Requirements 6.5, 13 (Property 7)**

- [x] 20. Final checkpoint — all portfolio-service tests pass
  - Run `./gradlew :portfolio-service:test`
  - Run `./gradlew :portfolio-service:integrationTest`
  - Ensure all tests pass; ask the user if questions arise.

- [x] 21. Full build verification
  - Run `./gradlew check` to execute unit + integration tests across all modules
  - Confirm no JWT library appears in portfolio-service, market-data-service, or insight-service
    compile/runtime classpaths (Req 11.3)
  - Confirm `spring-boot-starter-security` is absent from downstream service build files (Req 11.2)
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Tasks 1–13 are the api-gateway track; tasks 14–20 are the portfolio-service track — both tracks
  can be executed in parallel after task 1 compiles
- Property tests use standard JUnit 5 `@ParameterizedTest` with `@CsvSource` / `@MethodSource` only —
  no jqwik or other PBT library is added
- The `resolveKey(String forwardedFor, String remoteHost)` static method signature in
  `GatewayRateLimitConfig` is unchanged; all existing unit tests in `GatewayRateLimitConfigTest`
  remain valid without modification
- The canonical local dev UUID is `00000000-0000-0000-0000-000000000001` — use this as the
  `sub_claim` in all integration test JWTs
- The `AUTH_JWT_SECRET` value must never appear in logs, stack traces, or HTTP responses

---

## Phase 2: Frontend Auth Implementation

### Overview

Wire Auth.js (NextAuth v5) into the Next.js 16 App Router so every browser session is backed
by a real HS256-signed JWT that the API Gateway can validate. Replace all hardcoded
`userId = "user-001"` defaults with session-derived identity. All four portfolio hooks gain
`enabled: status === "authenticated"` to prevent unauthenticated API calls.

---

- [x] 22. Install `next-auth` and add environment variables to `.env.local`
  - Add `next-auth` to `frontend/package.json` dependencies: `npm install next-auth` in `frontend/`
  - Append to `frontend/.env.local`:
    ```
    AUTH_JWT_SECRET=local-dev-secret-change-me-min-32-chars
    NEXTAUTH_SECRET=replace-with-output-of-openssl-rand-base64-32
    ```
  - `AUTH_JWT_SECRET` MUST match the value in `api-gateway/src/main/resources/application-local.yml`
    (`local-dev-secret-change-me-min-32-chars`)
  - `NEXTAUTH_SECRET` is NextAuth's internal CSRF/encryption secret — distinct from `AUTH_JWT_SECRET`
  - Neither value should be committed to source control; `.env.local` is already gitignored
  - _Requirements: 8.1, 8.2, 10.1, 10.4_

- [x] 23. Create `frontend/src/auth.config.ts` — base NextAuth configuration (Edge-safe)
  - Create `frontend/src/auth.config.ts`
  - Import `NextAuthConfig` from `"next-auth"` and `Credentials` from `"next-auth/providers/credentials"`
  - Export `authConfig: NextAuthConfig` with:
    - `pages: { signIn: "/login" }` — routes unauthenticated users to the existing login page
    - `callbacks.authorized({ auth, request: { nextUrl } })`:
      - Always allow `/api/auth/**` routes through
      - Redirect unauthenticated users to `/login` for all non-login routes
      - Redirect already-authenticated users away from `/login` to `/overview`
      - Return `true` otherwise
    - `providers: [Credentials({ ... })]` with `authorize(credentials)`:
      - Mock success for `username === "user-001"` and `password === "password"` — returns
        `{ id: "user-001", name: "Dev User", email: "dev@local" }`
      - Returns `null` for all other credentials
  - This file MUST NOT import any Node.js-only modules (it runs on the Edge runtime via middleware)
  - _Requirements: 1.1, 1.2, 1.3, 1.7, 10.1, 10.2_

- [x] 24. Create `frontend/src/auth.ts` — NextAuth init with custom HS256 JWT encode/decode
  - Create `frontend/src/auth.ts`
  - Import `NextAuth` from `"next-auth"`, `{ authConfig }` from `"./auth.config"`, and
    `{ SignJWT, jwtVerify }` from `"jose"` (transitive dep of `next-auth` — no new install)
  - Derive `secret` as `new TextEncoder().encode(process.env.AUTH_JWT_SECRET)`
  - Call `NextAuth({ ...authConfig, session: { strategy: "jwt" }, callbacks: { jwt, session }, jwt: { encode, decode } })`
  - `callbacks.jwt({ token, user })`: if `user?.id` exists, set `token.sub = user.id`; return `token`
  - `callbacks.session({ session, token })`: set `session.user.id = token.sub ?? ""`; set
    `(session as { accessToken?: string }).accessToken = token.__rawJwt as string | undefined`; return `session`
  - `jwt.encode({ token })`:
    - Build `new SignJWT({ ...token }).setProtectedHeader({ alg: "HS256" }).setIssuedAt().setExpirationTime("1h").sign(secret)`
    - Stash the resulting JWS string as `token.__rawJwt = jwt` (the `__rawJwt` stash pattern)
    - Return the JWS string
  - `jwt.decode({ token })`:
    - Return `null` if `token` is falsy
    - Call `jwtVerify(token, secret, { algorithms: ["HS256"] })` and return `payload as JWT`
    - Catch any error and return `null`
  - Export `{ handlers, auth, signIn, signOut }` from the `NextAuth(...)` call
  - _Requirements: 1.4, 1.6, 7.2, 8.1, 10.1, 13.5_

- [x] 25. Create `frontend/src/types/next-auth.d.ts` — TypeScript module augmentation
  - Create `frontend/src/types/next-auth.d.ts`
  - Augment `"next-auth"` module: extend `Session` to add `user: { id: string } & DefaultSession["user"]`
    and `accessToken?: string`
  - Augment `"next-auth/jwt"` module: extend `JWT` to add `sub?: string` and `__rawJwt?: string`
  - Import `DefaultSession` from `"next-auth"` for the `Session` augmentation
  - _Requirements: 3.1, 3.5_

- [x] 26. Create `frontend/src/app/api/auth/[...nextauth]/route.ts` — NextAuth route handler
  - Create `frontend/src/app/api/auth/[...nextauth]/route.ts`
  - Import `{ handlers }` from `"@/auth"`
  - Export `const { GET, POST } = handlers`
  - No custom logic — all configuration lives in `auth.ts`
  - _Requirements: 1.2, 1.3_

- [x] 27. Create `frontend/src/middleware.ts` — Next.js route protection middleware
  - Create `frontend/src/middleware.ts`
  - Import `{ auth }` from `"@/auth"` and export it as the default export
  - Export `config` with `matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"]`
  - The `authorized` callback in `auth.config.ts` handles all redirect logic — no additional
    logic needed here
  - NOTE: middleware imports `auth` from `auth.ts`, NOT `auth.config.ts` directly; the Edge
    runtime constraint is satisfied because `auth.config.ts` (which is Edge-safe) is composed
    into `auth.ts` via spread
  - _Requirements: 1.1, 1.5, 2.3_

- [x] 28. Create `frontend/src/lib/api/fetchWithAuth.ts` — authenticated fetch wrapper
  - Create `frontend/src/lib/api/fetchWithAuth.ts`
  - Implement `fetchWithAuth<T>(path: string, init?: RequestInit): Promise<T>` (server-side):
    - Call `await auth()` from `"@/auth"` to get the session
    - Extract `token` from `(session as { accessToken?: string } | null)?.accessToken`
    - Build headers merging `Content-Type: application/json`, any `init.headers`, and
      `Authorization: Bearer ${token}` (only if token is truthy)
    - Call `fetch(path, { method: "GET", ...init, headers, cache: "no-store" })`
    - Throw `Error` on non-ok response; return `(await response.json()) as T` on success
  - Implement `fetchWithAuthClient<T>(path: string, token: string, init?: RequestInit): Promise<T>` (client-side):
    - Always attach `Authorization: Bearer ${token}` header (token is required, not optional)
    - Same error handling and JSON parsing as `fetchWithAuth`
    - Used by TanStack Query hooks in Client Components where `auth()` cannot be called
  - _Requirements: 1.4, 3.2, 3.3_

- [x] 29. Update `frontend/src/lib/api/portfolio.ts` — replace `fetchJson` with `fetchWithAuthClient`, remove `userId` defaults
  - Import `{ fetchWithAuthClient }` from `"@/lib/api/fetchWithAuth"`
  - Replace the private `fetchJson<T>(path: string)` helper with
    `fetchJson<T>(path: string, token: string)` that delegates to `fetchWithAuthClient<T>(path, token)`
  - Update `fetchPortfolio(userId: string, token: string)`:
    - Remove the `= "user-001"` default from `userId`
    - Add required `token: string` parameter
    - Pass `token` through to all internal `fetchJson` / `loadBackendPortfolio` / `loadMarketPrices` calls
    - Update `loadBackendPortfolio` to accept and forward `token`
  - Update `fetchPortfolioPerformance(userId: string, days: number, token: string)`:
    - Remove the `= "user-001"` default from `userId`
    - Add required `token: string` parameter; pass through to `fetchPortfolio`
  - Update `fetchAssetAllocation(userId: string, token: string)`:
    - Remove the `= "user-001"` default from `userId`
    - Add required `token: string` parameter; pass through to `fetchPortfolio`
  - The internal `loadBackendPortfolio` and `loadMarketPrices` helpers must also accept and
    forward `token` to `fetchJson`
  - _Requirements: 3.2, 3.3_

- [x] 30. Update `frontend/src/lib/apiService.ts` — replace `getJson` with `fetchWithAuthClient`, remove `userId` default
  - Import `{ fetchWithAuthClient }` from `"@/lib/api/fetchWithAuth"`
  - Update `fetchPortfolioSummary(userId: string, token: string)`:
    - Remove the `= "user-001"` default from `userId`
    - Add required `token: string` parameter
    - Replace `getJson<PortfolioSummaryDTO>(...)` call with `fetchWithAuthClient<PortfolioSummaryDTO>(..., token)`
  - Remove the private `getJson` helper (no longer needed)
  - _Requirements: 3.2, 3.3_

- [x] 31. Create `frontend/src/lib/hooks/useAuthenticatedUserId.ts` — session identity hook
  - Create `frontend/src/lib/hooks/useAuthenticatedUserId.ts` with `"use client"` directive
  - Import `{ useSession }` from `"next-auth/react"`
  - Export interface `AuthenticatedUser { userId: string; token: string; status: "authenticated" | "loading" | "unauthenticated" }`
  - Export `useAuthenticatedUserId(): AuthenticatedUser`:
    - Call `const { data: session, status } = useSession()`
    - If `status === "authenticated"` AND `session?.user?.id` AND `session?.accessToken`:
      return `{ userId: session.user.id, token: session.accessToken, status: "authenticated" }`
    - Otherwise return `{ userId: "", token: "", status }`
  - _Requirements: 3.1, 3.5, 13.5_

- [x] 32. Update `frontend/src/lib/hooks/usePortfolio.ts` — remove all `userId` defaults, use `useAuthenticatedUserId`, add `enabled` guard
  - Import `{ useAuthenticatedUserId }` from `"@/lib/hooks/useAuthenticatedUserId"`
  - Remove ALL `userId = "user-001"` default parameters from all four hooks
  - Remove `userId` as a parameter from all four hook function signatures entirely — hooks
    derive identity internally via `useAuthenticatedUserId()`
  - Update `usePortfolio()`:
    - Call `const { userId, token, status } = useAuthenticatedUserId()`
    - Add `enabled: status === "authenticated"` to the `useQuery` options
    - Pass `token` to `fetchPortfolio(userId, token)`
  - Update `usePortfolioPerformance(days = 30)`:
    - Call `useAuthenticatedUserId()` internally
    - Add `enabled: status === "authenticated"`
    - Pass `token` to `fetchPortfolioPerformance(userId, days, token)`
  - Update `useAssetAllocation()`:
    - Call `useAuthenticatedUserId()` internally
    - Add `enabled: status === "authenticated"`
    - Pass `token` to `fetchAssetAllocation(userId, token)`
  - Update `usePortfolioSummary()`:
    - Call `useAuthenticatedUserId()` internally
    - Add `enabled: status === "authenticated"`
    - Pass `token` to `fetchPortfolioSummary(userId, token)`
  - `portfolioKeys` factory functions are unchanged in shape — they still accept `userId` as a
    parameter, ensuring TanStack Query cache entries remain scoped per user
  - _Requirements: 3.2, 3.3, 3.4_

- [x] 33. Audit and update all call sites — remove `userId` arguments passed to hooks
  - Search all files under `frontend/src/` that import from `@/lib/hooks/usePortfolio`
  - For each call site, remove any `userId` argument (hooks now derive it internally):
    - `frontend/src/components/charts/PerformanceChart.tsx`:
      - Change `usePortfolioPerformance("user-001", days)` → `usePortfolioPerformance(days)`
    - `frontend/src/components/portfolio/SummaryCards.tsx`:
      - Verify `usePortfolio()` and `usePortfolioSummary()` are called without arguments (already correct if no userId was passed)
    - `frontend/src/components/portfolio/HoldingsTable.tsx`:
      - Verify `usePortfolio()` is called without arguments
    - `frontend/src/components/charts/AllocationChart.tsx`:
      - Verify `useAssetAllocation()` is called without arguments
  - Confirm no component passes `"user-001"` or any userId string to any portfolio hook
  - _Requirements: 3.2, 3.3_

- [x] 34. Checkpoint — TypeScript compilation and lint pass
  - Run `npm run build` in `frontend/` to verify no TypeScript errors across all modified files
  - Run `npm run lint` in `frontend/` to verify no ESLint errors
  - Pay particular attention to: `auth.ts`, `fetchWithAuth.ts`, `portfolio.ts`, `apiService.ts`,
    `usePortfolio.ts`, `useAuthenticatedUserId.ts`, and `next-auth.d.ts`
  - Ensure all tests pass, ask the user if questions arise.

- [x] 35. Update `frontend/src/test/msw/handlers.ts` — assert `Authorization` header in MSW handlers
  - Update the existing `GET /api/portfolio/summary` handler:
    - Extract `Authorization` header from `request.headers.get("Authorization")`
    - Return `HttpResponse(null, { status: 401 })` if header is missing or does not start with `"Bearer "`
    - Otherwise return the existing JSON response
  - Add a new `GET /api/portfolio` handler:
    - Assert `Authorization: Bearer` header present (same 401 guard as above)
    - Return `HttpResponse.json({ portfolioId: "p-001", holdings: [] })` on success
  - _Requirements: 1.4, 3.3_

- [x] 36. Create `frontend/src/lib/hooks/useAuthenticatedUserId.test.ts` — unit tests for identity hook
  - Create `frontend/src/lib/hooks/useAuthenticatedUserId.test.ts`
  - Mock `"next-auth/react"` with `vi.mock` — control `useSession` return value per test
  - Wrap renders in a minimal React wrapper (no `SessionProvider` needed since `useSession` is mocked)
  - Test cases:
    - Returns `{ userId: "user-001", token: "eyJ...", status: "authenticated" }` when session is active
    - Returns `{ userId: "", token: "", status: "loading" }` when `useSession` returns `status: "loading"`
    - Returns `{ userId: "", token: "", status: "unauthenticated" }` when `useSession` returns `status: "unauthenticated"`
    - `userId` equals `session.user.id` (sub claim round-trip — Property P14)
    - `token` equals `session.accessToken`

  - [ ]\* 36.1 Write property test for `useAuthenticatedUserId` sub round-trip (Property P14)
    - **Property P14: useAuthenticatedUserId returns sub from session**
    - Parameterise over multiple `{ id, accessToken }` pairs; assert `userId === session.user.id`
      and `token === session.accessToken` for each
    - **Validates: Requirements 3.1, 3.5, 13.5**

- [x] 37. Create `frontend/src/lib/api/fetchWithAuth.test.ts` — unit tests for fetch wrapper
  - Create `frontend/src/lib/api/fetchWithAuth.test.ts`
  - Use `vi.stubGlobal("fetch", vi.fn())` or MSW to intercept fetch calls
  - Test cases for `fetchWithAuthClient`:
    - Attaches `Authorization: Bearer <token>` header on every call (Property P12)
    - Throws `Error` on 4xx response
    - Throws `Error` on 5xx response
    - Returns parsed JSON on 200 OK
    - Passes through additional `RequestInit` options (e.g. custom method)

  - [ ]\* 37.1 Write property test for Bearer header always present (Property P12)
    - **Property P12: Bearer header always present on authenticated API calls**
    - Parameterise over multiple non-empty token strings; assert `Authorization: Bearer <token>`
      is present on every outgoing fetch call
    - **Validates: Requirement 1.4**

- [x] 38. Create `frontend/src/lib/hooks/usePortfolio.test.ts` — unit tests for portfolio hooks
  - Create `frontend/src/lib/hooks/usePortfolio.test.ts`
  - Mock `"next-auth/react"` with `vi.mock` to control session state
  - Wrap renders in `<QueryClientProvider client={new QueryClient()}>` (fresh client per test)
  - Use MSW (already configured in `frontend/src/test/msw/`) to intercept API calls
  - Test cases:
    - `usePortfolio` does NOT call `/api/portfolio` when `status === "unauthenticated"` (Property P11)
    - `usePortfolio` does NOT call `/api/portfolio` when `status === "loading"` (Property P11)
    - `usePortfolio` calls `/api/portfolio` with `Authorization: Bearer <token>` when authenticated
    - Query key includes `userId` — two renders with different mocked userIds produce distinct cache entries (Property P13)
    - `usePortfolioSummary` does NOT call `/api/portfolio/summary` when unauthenticated

  - [ ]\* 38.1 Write property test for unauthenticated hooks do not fetch (Property P11)
    - **Property P11: Unauthenticated hooks do NOT issue fetch requests**
    - Parameterise over all four hooks (`usePortfolio`, `usePortfolioPerformance`, `useAssetAllocation`, `usePortfolioSummary`)
      and both non-authenticated statuses (`"loading"`, `"unauthenticated"`)
    - Assert no fetch call is made to any `/api/*` endpoint for each combination
    - **Validates: Requirement 3.3**

  - [ ]\* 38.2 Write property test for cache keys are user-scoped (Property P13)
    - **Property P13: Cache keys are user-scoped**
    - Parameterise over pairs of distinct user IDs `(a, b)` where `a ≠ b`
    - Assert `portfolioKeys.all(a)` does not deep-equal `portfolioKeys.all(b)`
    - **Validates: Requirement 3.4**

- [x] 39. Final checkpoint — all frontend tests and lint pass
  - Run `npm run test` in `frontend/` (runs `vitest run` — single pass, no watch)
  - Run `npm run lint` in `frontend/`
  - Ensure all new test files pass and no regressions in existing tests (e.g. `SummaryCards.test.tsx`)
  - Ensure all tests pass, ask the user if questions arise.

---

## Notes (Phase 2)

- `jose` is a transitive dependency of `next-auth` — no additional `npm install` is required;
  import `{ SignJWT, jwtVerify }` from `"jose"` directly
- `AUTH_JWT_SECRET` must be byte-for-byte identical in `frontend/.env.local` and
  `api-gateway/src/main/resources/application-local.yml`; the fallback value
  `local-dev-secret-change-me-min-32-chars` is used in both places for local dev
- The mock `Credentials` provider `authorize()` in `auth.config.ts` is intentionally simple
  for local development; the `aws` profile will replace it with a real OIDC provider by
  changing environment variables only — no code changes required
- All hook tests use `vi.mock("next-auth/react")` to control session state; no real NextAuth
  server is needed for unit tests
- MSW handlers in `frontend/src/test/msw/handlers.ts` assert the `Authorization: Bearer`
  header is present; requests without it return 401, mirroring the API Gateway behaviour
- Tasks marked with `*` are optional and can be skipped for a faster MVP
- The `__rawJwt` stash pattern (writing to `token.__rawJwt` in `encode()`, reading in
  `session()`) is necessary because NextAuth does not expose the encoded token string directly
  in the `session` callback — only the decoded payload is available
