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
