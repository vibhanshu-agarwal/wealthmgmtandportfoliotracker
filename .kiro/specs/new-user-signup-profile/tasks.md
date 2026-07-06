# Implementation Plan: New User Signup & Profile

## Overview

This plan turns the `api-gateway` into the real authentication server while keeping the frontend a
static export. Work is sequenced so the Postgres schema (Flyway migrations owned by
`portfolio-service`), the pure validation logic, and the credential data layer land first; the
authentication/signup services and the gateway filters (read-only enforcement + auth rate limiting)
land next; the `AuthController` rewrite and the frontend signup/session pieces follow their
dependencies; and the Testcontainers integration tests (Requirement 10) run last against the fully
wired stack.

Languages/stack are taken directly from the design: **Java 21 / Spring Boot** for `api-gateway` and
`portfolio-service` (Flyway SQL migrations, `NamedParameterJdbcTemplate`, WebFlux, jqwik for
property tests), and **TypeScript / Next.js (static export)** for the frontend (Vitest + Testing
Library + MSW, fast-check for the client validator).

## Tasks

- [ ] 1. Database schema migrations (portfolio-service — Schema_Owner)
  - [ ] 1.1 Add `V14__Add_User_Credentials_And_Account_Flags.sql`
    - Create `user_credentials` (`user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE`, `email VARCHAR(254) NOT NULL`, `password_hash VARCHAR(255) NOT NULL`, `created_at`, `updated_at`)
    - Add functional unique index `ux_user_credentials_email_lower ON user_credentials (lower(email))` for case-insensitive email uniqueness (concurrency guard for duplicate signups)
    - `ALTER TABLE users ADD COLUMN IF NOT EXISTS name VARCHAR(100)` and `read_only BOOLEAN NOT NULL DEFAULT FALSE`
    - Place file in `portfolio-service/src/main/resources/db/migration/`
    - _Requirements: 1, 4.1, 7.4, 9.1, 9.4, 9.5, 2.6_

  - [ ] 1.2 Confirm holdings/valuation-history ownership model against the live schema
    - Inspect existing migrations (`V1`–`V13`) and the `holdings`/valuation-history tables to determine whether they carry `user_id` directly or reference the portfolio only via `portfolio_id` (FK)
    - Record the finding as an inline comment block that the V15 reassignment SQL will follow (drives whether V15 updates `user_id` columns directly or reassigns only `portfolios.user_id`)
    - _Requirements: 7.8, 7.10_

  - [ ] 1.3 Add `V15__Reconcile_Auth_Seed_Users.sql` (idempotent seed + showcase reassignment)
    - Seed the Demo_Account under dedicated UUID `00000000-0000-0000-0000-0000000d3110`, email `demo@wealthtracker.dev`, `read_only = TRUE`, with a fresh bcrypt(cost=12) hash (NOT a legacy scrypt `ba_account` hash); use `INSERT ... ON CONFLICT DO NOTHING`
    - Seed dev + E2E `user_credentials` rows with `ON CONFLICT (user_id) DO NOTHING`; dev user stays `read_only = FALSE`
    - Reassign the showcase portfolio and its holdings/history from dev `...0001` to the demo UUID using `UPDATE`s guarded on the current owner (dev `...0001`) so re-running never re-assigns away from demo — follow the ownership model confirmed in 1.2
    - _Requirements: 7.1, 7.8, 7.9, 7.10, 7.11, 8.4, 8.5_

  - [ ] 1.4 Add `V16__Drop_Better_Auth_Tables.sql` (versioned LAST in the release)
    - `DROP TABLE ba_verification, ba_account, ba_session, ba_user`
    - Assign the highest version number in the release so Flyway applies it last; ships in the same release as the frontend Better Auth code removal
    - _Requirements: 8.3_

  - [ ]* 1.5 Write migration test for the release
    - Assert after `migrate` that `V16` is the highest applied version and the `ba_*` tables are absent
    - Assert the demo/dev/E2E users each resolve to exactly one `users` row + one `user_credentials` entry
    - _Requirements: 8.3, 8.4, 8.5, 8.7_

- [ ] 2. Gateway auth foundation — validation, data layer, hashing, signer (com.wealth.gateway)
  - [ ] 2.1 Implement `SignupValidator` (pure, no I/O)
    - Email present, ≤ 254 chars, matches Email_Format_Rule (`local@domain`, non-empty local, domain with ≥ 1 dot) → else `ValidationException("email", ...)`
    - Password present, ≥ 12 characters AND UTF-8 byte length ≤ 72 (`password.getBytes(StandardCharsets.UTF_8).length`, NOT char count) → else `ValidationException("password", ...)`
    - Name trimmed length 1..100; the trimmed value is what gets persisted → else `ValidationException("name", ...)`
    - _Requirements: 1.4, 1.5, 1.6, 1.7, 1.8, 9.2_

  - [ ]* 2.2 Write property test for `SignupValidator` (jqwik)
    - **Property 1: Signup input validation is exact and side-effect-free**
    - Accepts iff all three rules hold; for a request violating exactly one rule, the rejection names that specific field; ≥ 100 iterations
    - **Validates: Requirements 1.4, 1.5, 1.6, 1.7, 1.8, 9.2**

  - [ ] 2.3 Implement `UserCredentialRepository` + gateway JDBC datasource config
    - `NamedParameterJdbcTemplate` (no JPA, no Spring Data): `findByEmailIgnoreCase` (join `user_credentials` → `users` returning `userId, email, name, read_only, passwordHash`), `insertUser`, `insertCredential`; `CredentialRow` record
    - Add a small HikariCP pool (2–4 connections) via a `@Configuration` datasource bean + profile YAML props (`application-local.yml` / `application-aws.yml`), never `localhost` in base `application.yml`
    - _Requirements: 2.6_

  - [ ] 2.4 Add `PasswordEncoder` bean + `DUMMY_PASSWORD_HASH` constant
    - `BCryptPasswordEncoder` at cost 12 as a Spring bean (separate `@Configuration` from 2.3)
    - `DUMMY_PASSWORD_HASH` compile-time constant: a valid bcrypt(cost=12) hash used only to equalize unknown-email timing (never matches a submitted password)
    - _Requirements: 4.3, 3.4_

  - [ ] 2.5 Extend `JwtSigner` with a `ro` claim overload
    - Add `signHs256(userId, email, name, boolean readOnly)` adding `.claim("ro", readOnly)`; keep the 3-arg method as a back-compat overload delegating with `ro=false`
    - Leave algorithm (HS256), `auth.jwt.secret`, 1-hour expiry, and `sub`/`email`/`name` claims unchanged
    - _Requirements: 3.7, 9.3_

  - [ ]* 2.6 Write example test for `JwtSigner`
    - Assert the `ro` claim is present/correct and that `sub`, `email`, `name`, expiry (1h), and algorithm (HS256) are unchanged
    - _Requirements: 3.7, 9.3_

- [ ] 3. Gateway authentication and signup services (com.wealth.gateway.auth)
  - [ ] 3.1 Implement `AuthenticationService`
    - Blank/missing email or password guard → `InvalidCredentialsException` BEFORE any hashing (no hasher call, no mint)
    - Lookup via repository; on unknown email run `passwordEncoder.matches(pwd, DUMMY_PASSWORD_HASH)` then fail uniformly; on absent/malformed stored hash fail uniformly (still run a match for timing)
    - Verify against the stored hash with `passwordEncoder.matches` (never string equality); on success mint via `JwtSigner.signHs256(..., row.readOnly())` with `ro` sourced from `users.read_only`
    - Run blocking work with `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`; map `DataAccessException` → `CredentialStoreUnavailableException` (503); login does NOT enforce the 72-byte password max
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.9, 3.10, 3.11, 4.4, 4.5, 4.6, 7.4, 2.5_

  - [ ] 3.2 Implement `SignupService` + `Provisioning_Transaction`
    - Call `SignupValidator.validate` first; hash password with `passwordEncoder.encode` (never store plaintext); generate `UUID` that becomes both `users.id` and the JWT `sub`
    - Wrap `insertUser` + `insertCredential` in a single `TransactionTemplate.execute` on `Schedulers.boundedElastic()`; `DuplicateKeyException` → rollback + `DuplicateEmailException` (409); any other failure → rollback + `ProvisioningFailedException`
    - Mint the session token on success (`ro=false` for new signups); return `LoginResponse { token, userId, email, name }`
    - _Requirements: 1.2, 1.3, 1.9, 2.1, 2.2, 2.3, 2.4, 2.5, 2.7, 2.8, 4.1, 4.2, 9.1_

- [ ] 4. Gateway filters — read-only enforcement and auth rate limiting (com.wealth.gateway)
  - [ ] 4.1 Implement `ReadOnlyEnforcementFilter` (`GlobalFilter`, HIGHEST_PRECEDENCE + 3)
    - Ordered after `JwtAuthenticationFilter` so the validated `ro` claim is available on the principal
    - Extract the decision into a pure `decide(ro, method, path)`: block (403 + JSON `read_only_account` body) iff `ro == true` AND method ∈ {POST,PUT,PATCH,DELETE} AND path matches `/api/portfolio/**` or `/api/market/**` AND path is not AI-allowlisted; reads/HEAD and AI routes pass
    - AI allowlist as a configurable list of `AntPathMatcher` patterns (default `/api/chat/**`, `/api/insights/generate/**`)
    - _Requirements: 7.4, 7.5, 7.6, 7.7_

  - [ ]* 4.2 Write property test for `ReadOnlyEnforcementFilter.decide` (jqwik)
    - **Property 6: Read-only enforcement is exactly "block portfolio/market writes, allow AI routes and reads"**
    - Generate over `(ro, method, path)`; assert block iff the exact conjunction holds, forward otherwise; ≥ 100 iterations
    - **Validates: Requirements 7.4, 7.5, 7.6, 7.7**

  - [ ] 4.3 Implement `AuthRateLimitFilter` (`WebFilter`, HIGHEST_PRECEDENCE + 1) + Auth_Bucket bean
    - Add a dedicated `@Bean RedisRateLimiter authRateLimiter(...)` with `replenishRate = 1`, `requestedTokens = 12`, `burstCapacity = 60` (≈ 5/min, burst 5); reuse the shared `RedisRateLimiter` type and `Trusted_Hop_Resolver` key resolver from the production-rate-limiting spec — do NOT add a second limiter implementation
    - Apply only to `/api/auth/login` and `/api/auth/signup`, both keyed to a single shared route id (`"auth-bucket"`) so login+signup share one bucket per IP; on exhaustion return 429 with `Retry-After` = `ceil(requestedTokens/replenishRate)` = 12; fail open (`chain.filter`) on any Redis error/timeout
    - _Requirements: 6.1, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8_

  - [ ]* 4.4 Write property test reusing trusted-hop key derivation
    - Assert the Auth_Rate_Limiter keys through the shared Trusted_Hop_Resolver (reuse the production-rate-limiting key-derivation property); a spoofed leading `X-Forwarded-For` cannot mint a fresh bucket
    - _Requirements: 6.2_

- [ ] 5. Gateway controller wiring (com.wealth.gateway)
  - [ ] 5.1 Rewrite `AuthController` for reactive login/signup
    - Reactive `Mono<ResponseEntity<?>>` `login` and `signup` delegating to `AuthenticationService` / `SignupService`
    - Remove the `app.auth.{email,password,user-id,name}` binding and the string-equality check (also strip the `app.auth.*` keys from `application.yml`)
    - Add a shared, pre-serialized `uniformAuthError()` constant used on every 401 path (byte-identical body); wire the error-mapping table: 400 (+field) for validation, 409 for duplicate, 401 uniform for auth failure, 503 for credential-store unavailable, 500/503 for provisioning failure
    - _Requirements: 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 3.5, 3.6, 3.8, 3.10, 2.2, 10.6_

  - [ ]* 5.2 Write example test for `Uniform_Auth_Error` constant identity
    - Assert the unknown-email and wrong-password 401 bodies are byte-for-byte identical and contain no token
    - _Requirements: 3.5, 3.6, 10.6_

- [ ] 6. Checkpoint — backend auth path wired
  - Ensure all unit/property tests pass and the `api-gateway` + `portfolio-service` modules compile. Ask the user if questions arise.

- [ ] 7. Frontend — session, signup page, navigation (Next.js static export)
  - [ ] 7.1 Extend `session.ts` with `signupWithBackend(email, password, name)`
    - Mirror `loginWithBackend`: `POST` to `apiPath("/auth/signup")`, coerce via existing `coerceSession`, persist via `saveAuthSession`, return `AuthSession`; leave the `Session_Contract` (`wmpt.auth.session`, `{ token, userId, email, name }`) unchanged
    - _Requirements: 5.4, 5.7_

  - [ ] 7.2 Create `frontend/src/app/(auth)/signup/page.tsx`
    - Client component collecting email/password/name with client-side validation: email syntax + ≤ 254 chars, password ≥ 12 chars AND UTF-8 byte length ≤ 72 (`new TextEncoder().encode(pw).length`), name 1..100
    - On invalid input show a field-specific message, do NOT call the endpoint, retain email + name; on 201 persist session (via 7.1) and navigate to `/overview`; on 400/409 show server message and retain email + name; 10s `AbortController` timeout or other non-2xx → generic error, stay on page, retain email + name
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.9_

  - [ ]* 7.3 Write property test for the client-side signup validator (fast-check)
    - **Property 1 (frontend companion): client validator mirrors `SignupValidator`**
    - Generate email/password/name inputs; assert accept/reject and field identification match the server rules (byte-length password check); ≥ 100 iterations
    - **Validates: Requirements 5.1, 5.2, 5.3 (mirrors 1.4–1.8, 9.2)**

  - [ ]* 7.4 Write Vitest + Testing Library tests for the Signup_Page (MSW)
    - Field-specific validation messages, no endpoint call on invalid input, email/name retained on error, session persisted + navigation on 201, 400/409 message surfaced, timeout/other-error handling, login↔signup links
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.8_

  - [ ] 7.5 Add login↔signup navigation controls
    - Add a link to `/signup` on the login page (keep the existing demo pre-fill) and a link back to `/login` on the signup page
    - _Requirements: 5.8, 7.2_

- [ ] 8. Retire Better Auth (frontend)
  - [ ] 8.1 Remove Better Auth code and dependency
    - Delete `auth.ts`, `auth-client.ts`, `mintToken.ts`, `fetchWithAuth.server.ts` (keep the client `fetchWithAuth.ts`); remove the `better-auth` dependency from `package.json` and the lockfile
    - _Requirements: 8.1, 8.2_

  - [ ]* 8.2 Add static-export build + no-import guard checks
    - Assert `npm run build` produces the static export with zero errors after removal; grep/ESLint check that no source imports the removed files or `better-auth`
    - _Requirements: 8.1, 8.2, 8.6, 5.9_

- [ ] 9. Checkpoint — frontend signup path wired
  - Ensure frontend unit tests and the static-export build pass. Ask the user if questions arise.

- [ ] 10. Integration and architecture tests (Testcontainers, `@Tag("integration")`)
  - [ ] 10.1 Provisioning / signup integration tests (Postgres)
    - Signup provisions both a `users` row and a `user_credentials` entry; created `sub` is matched by `PortfolioService.requireUserExists()`; forced provisioning failure returns an observable error and leaves neither row; duplicate-email signup returns 409 with the row count for that email staying exactly 1
    - **Validates Property 4** (atomic provisioning / at most one account per email)
    - _Requirements: 10.1, 10.2, 10.3, 10.7_

  - [ ] 10.2 Login integration tests (Postgres)
    - Correct email+password → 200 with non-empty token; wrong password → 401 uniform, no token; unknown email → body byte-identical to the wrong-password 401 body
    - **Validates Property 2 (round trip) and Property 3 (uniform 401)**
    - _Requirements: 10.4, 10.5, 10.6_

  - [ ] 10.3 Timing-equalization test (Postgres)
    - Primary: a spy/verifying `PasswordEncoder` asserts `matches(..)` is invoked exactly once on the unknown-email path (against the dummy hash), just as on the wrong-password path
    - Secondary (soft, non-blocking): compare median latencies of the two paths within a coarse ratio
    - **Validates Property 3 (timing equalization)**
    - _Requirements: 3.4_

  - [ ] 10.4 Auth rate-limit integration tests (Redis)
    - Login requests exceeding the configured threshold within the window → 429 with a positive-integer `Retry-After`; when Redis is unavailable, login and signup return a non-`5xx` response (fail-open)
    - **Validates Property 7 (bucket allowance + Retry-After) and Property 8 (fail-open)**
    - _Requirements: 10.8, 10.9_

  - [ ] 10.5 Read-only / demo enforcement integration tests (Postgres)
    - With a demo (`ro=true`) token: write (`POST/PUT/PATCH/DELETE`) to `/api/portfolio/**` → 403 with data unchanged on a follow-up read; `POST /api/chat/**` allowed (not 403); demo UUID owns the showcase portfolio with non-empty holdings and dev `...0001` no longer owns it
    - **Validates Property 6 (read-only) and Property 9 (reassignment)**
    - _Requirements: 10.10, 10.11, 10.12_

  - [ ] 10.6 Reconciliation seed idempotency integration test (Postgres)
    - Apply the reconciliation seed/reassignment twice; assert each of demo/dev/E2E resolves to exactly one `users` + one `user_credentials` row (count stays 1) and the showcase portfolio remains owned only by the demo UUID
    - **Validates Property 9 (idempotent seed + reassignment)**
    - _Requirements: 8.4, 8.5, 7.8, 7.9, 7.10_

  - [ ]* 10.7 Add ArchUnit / build architecture checks
    - Assert `com.wealth.gateway` has no Better Auth types and defines no Flyway migration, and that the gateway build succeeds with `ba_*` and the Better Auth code removed
    - _Requirements: 2.6, 8.6, 8.7_

- [ ] 11. Final checkpoint — full suite
  - Run `./gradlew check` (unit + integration) and the frontend test/build. Ensure all tests pass. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP. The Requirement 10 mandated integration tests (10.1, 10.2, 10.4, 10.5) and all migration/core-auth-wiring tasks are intentionally NOT optional.
- Each task references specific requirement clauses (and, where applicable, the design Property number) for traceability.
- Integration tests are annotated `@Tag("integration")` and run via the `integrationTest` Gradle task, backed by Testcontainers Postgres + Redis (no real AWS).
- Property tests use jqwik (Java pure logic) and fast-check (frontend validator) at ≥ 100 iterations; example/integration tests cover wiring and the Req 10 cases.
- Sequencing: schema migrations + validator + repository land before the services and filters; the `AuthController` rewrite and frontend pieces follow their dependencies; integration tests run last against the fully wired stack.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1", "2.3", "2.4", "2.5", "7.1"] },
    { "id": 1, "tasks": ["1.3", "2.2", "2.6", "3.1", "3.2", "4.1", "4.3", "7.2"] },
    { "id": 2, "tasks": ["1.4", "4.2", "4.4", "5.1", "7.3", "7.4", "7.5"] },
    { "id": 3, "tasks": ["5.2", "8.1"] },
    { "id": 4, "tasks": ["1.5", "8.2", "10.1", "10.2", "10.3", "10.4", "10.5", "10.6", "10.7"] }
  ]
}
```
