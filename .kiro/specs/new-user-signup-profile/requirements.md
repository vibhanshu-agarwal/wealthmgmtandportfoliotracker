# Requirements Document

## Introduction

Today the platform supports exactly one login: a single hardcoded credential pair (`app.auth.{email,password,user-id,name}`) checked by plain string equality in the `api-gateway`'s `AuthController`. On success the gateway's `JwtSigner` mints an HS256 JWT (Nimbus JOSE, 1-hour expiry, claims `sub`/`email`/`name`) that every downstream service and the Redis rate limiter key off. There is no signup path, no per-user record, and no way for a second distinct credential pair to succeed. A previously integrated Better Auth instance (frontend `auth.ts`, Postgres tables `ba_user`/`ba_session`/`ba_account`/`ba_verification` from Flyway V8) is dead code because the frontend is a static export (`next.config.ts` `output: "export"`) with no runtime Next.js server to host its routes.

This feature makes the `api-gateway` the real authentication server while keeping the frontend a static export. It replaces the hardcoded string-equality login with per-user database lookups and expensive password-hash verification, adds a self-service signup flow (new UI plus a gateway signup endpoint), and provisions each new account atomically so that both the login-capable credential and the `users` row that `PortfolioService.requireUserExists()` checks are created in a single transaction. The gateway continues to mint the existing HS256 JWT unchanged, and the frontend continues to store the session in `localStorage` (`wmpt.auth.session`), so downstream services and the frontend keep working without modification. Because `/api/auth/**` is a controller endpoint on the gateway (not a proxied route), it is not covered by the route-filter rate limiter from the production-rate-limiting spec; this feature therefore throttles login and signup via a `WebFilter` that reuses the existing `RedisRateLimiter` and trusted-hop IP resolver. Implementation order: the `production-rate-limiting` spec is a hard prerequisite for this feature and MUST be implemented first. This feature's `Auth_Rate_Limiter` reuses the shared `RedisRateLimiter` wiring and the trusted-hop key resolver (`resolveTrustedHopKey` / the `app.rate-limit.trust-xff-last-hop` toggle) created by that spec, and both features modify the frontend `fetchWithAuth.ts`; implementing this spec first would force those shared components to be built out of band. The obsolete Better Auth code, npm dependency, and `ba_*` tables are retired as an explicit cleanup.

This document scopes authentication, signup, atomic provisioning, password hashing, auth-endpoint throttling, the preserved demo account, cleanup of Better Auth, and a minimal `name` profile. Broader personalization (risk tolerance, preferences, base currency) is a separate downstream feature (roadmap 3.2) that depends on this one; the user model must not preclude it, but it is not specified here.

## Glossary

- **API_Gateway**: The `api-gateway` Spring Cloud Gateway (WebFlux) service; the only externally reachable deployable and the host of the authentication endpoints.
- **Auth_Controller**: The Spring `@RestController` on the API_Gateway that handles `/api/auth/**` requests, including login and signup.
- **Login_Endpoint**: `POST /api/auth/login` on the API_Gateway.
- **Signup_Endpoint**: `POST /api/auth/signup` on the API_Gateway.
- **Signup_Page**: The frontend registration page (a new page under `frontend/src/app/(auth)/`) through which a person creates an account.
- **Login_Page**: The existing frontend login page at `frontend/src/app/(auth)/login/page.tsx`.
- **Users_Table**: The legacy Postgres `users` table (Flyway `V1`), the authoritative identity store; `portfolios.user_id` must match a row here and `PortfolioService.requireUserExists()` queries it.
- **Credential_Store**: The Postgres store of per-user login credentials (email plus password hash), which the design realizes either as new columns on the Users_Table or as a separate `user_credentials` table.
- **User_Account**: The combination of a Users_Table row and its associated Credential_Store entry that together represent one login-capable user.
- **Password_Hash**: The one-way hash of a user's password stored in the Credential_Store, produced by the Password_Hasher.
- **Password_Hasher**: The Spring Security `PasswordEncoder` implementation using bcrypt (cost factor >= 10) or argon2id.
- **Dummy_Password_Hash**: A fixed, valid Password_Hash used only to equalize verification time on the unknown-email login path; it never matches any submitted password.
- **Password_Policy**: The set of rules a submitted password must satisfy: minimum length of 12 characters and a maximum length of 72 bytes when encoded as UTF-8 (the BCrypt input limit; note this is a byte limit, not a character limit — a multibyte passphrase can exceed 72 bytes at well under 72 characters).
- **Email_Format_Rule**: The rule that an email address must be a syntactically valid address of the form `local@domain` with a non-empty local part and a domain containing at least one dot.
- **Jwt_Signer**: The existing `JwtSigner` component on the API_Gateway that mints an HS256 JWT with claims `sub` (user id), `email`, and `name`, a 1-hour expiry, using the `auth.jwt.secret` key.
- **Session_Contract**: The client-side authentication contract in which the API_Gateway returns a token, user id, email, and name, and the frontend persists them in `localStorage` under key `wmpt.auth.session`.
- **Provisioning_Transaction**: The single database transaction in which the API_Gateway creates both the Users_Table row and the Credential_Store entry for a new User_Account.
- **Schema_Owner**: The `portfolio-service` module, which owns and runs all Flyway migrations for the Postgres schema; the API_Gateway reads and writes these tables but does not own or run migrations.
- **Auth_Rate_Limiter**: A `WebFilter` on the API_Gateway that throttles requests to the Login_Endpoint and Signup_Endpoint by reusing the existing `RedisRateLimiter` programmatically.
- **Trusted_Hop_Resolver**: The shared client-address resolution logic (governed by `app.rate-limit.trust-xff-last-hop`) from the production-rate-limiting spec that derives a client IP from a trusted source rather than a client-supplied first `X-Forwarded-For` value.
- **Auth_Rate_Limit_Key**: The value produced by the Trusted_Hop_Resolver identifying a single client for auth-endpoint throttling; keyed by IP.
- **Auth_Bucket**: The shared per-IP token bucket for the auth endpoints, configured to an effective 5 requests per minute with a burst of 5 (`RedisRateLimiter` with `replenishRate` 1, `requestedTokens` 12, and `burstCapacity` 60).
- **Throttled_Response**: An HTTP `429 Too Many Requests` response returned when a client exceeds the Auth_Rate_Limiter threshold.
- **Uniform_Auth_Error**: A single, non-distinguishing authentication failure response (HTTP `401`) that does not reveal whether the email exists, the password was wrong, or the account is otherwise unusable.
- **Demo_Account**: The recruiter/demo User_Account seeded into the Users_Table and Credential_Store under a dedicated UUID with email `demo@wealthtracker.dev`, whose credentials the Login_Page pre-fills via `NEXT_PUBLIC_DEMO_EMAIL`/`NEXT_PUBLIC_DEMO_PASSWORD` for one-click login; the Demo_Account owns the reassigned showcase portfolio and its holdings and history rows.
- **Read_Only_Account**: A User_Account flagged server-side so that it cannot mutate portfolio data (writes to `/api/portfolio/**` and `/api/market/**`), while the AI routes (`/api/chat/**` and insight-generation endpoints under `/api/insights/**`) remain allowed; the Demo_Account is a Read_Only_Account.
- **Better_Auth_Code**: The unused Node-side Better Auth artifacts in the frontend (`auth.ts`, `auth-client.ts`, `mintToken.ts`, `fetchWithAuth.server.ts`) and the Better Auth npm dependency.
- **Better_Auth_Tables**: The Postgres tables `ba_user`, `ba_session`, `ba_account`, and `ba_verification` created by Flyway `V8`.
- **Integration_Test_Suite**: The Testcontainers-backed tests annotated `@Tag("integration")` that run via the `integrationTest` Gradle task.

## Requirements

### Requirement 1: Self-service signup endpoint

**User Story:** As a prospective user, I want to create an account with my email and a password, so that I can access the portfolio dashboard without a pre-shared credential.

#### Acceptance Criteria

1. THE API_Gateway SHALL expose a Signup_Endpoint at `POST /api/auth/signup` that accepts an email of at most 254 characters, a password, and a name of 1 to 100 characters after trimming leading and trailing whitespace.
2. WHEN a signup request is received with an email that satisfies the Email_Format_Rule and is at most 254 characters, a password that satisfies the Password_Policy, a name of 1 to 100 characters after trimming, and an email not already present in the Credential_Store, THE API_Gateway SHALL create a new User_Account and return an HTTP `201` response.
3. WHEN the API_Gateway successfully creates a User_Account through the Signup_Endpoint, THE API_Gateway SHALL return a token minted by the Jwt_Signer together with the user id, email, and name, in the same response shape defined by the Session_Contract.
4. IF a signup request omits the email field, omits the password field, or omits the name field, THEN THE API_Gateway SHALL reject the request with HTTP `400` and a response body identifying the missing field, and SHALL NOT create a User_Account.
5. IF a signup request contains an email that does not satisfy the Email_Format_Rule or exceeds 254 characters, THEN THE API_Gateway SHALL reject the request with HTTP `400` and a response body identifying the email as invalid, and SHALL NOT create a User_Account.
6. IF a signup request contains a password whose UTF-8 encoding exceeds 72 bytes or whose length is shorter than 12 characters (violating the Password_Policy), THEN THE API_Gateway SHALL reject the request with HTTP `400` and a response body identifying the password as non-compliant, and SHALL NOT create a User_Account.
7. IF a signup request contains a name that is empty or whitespace-only after trimming, THEN THE API_Gateway SHALL reject the request with HTTP `400` and a response body identifying the name as required, and SHALL NOT create a User_Account.
8. IF a signup request contains a name that exceeds 100 characters after trimming, THEN THE API_Gateway SHALL reject the request with HTTP `400` and a response body identifying the name as too long, and SHALL NOT create a User_Account.
9. IF a signup request contains an email that already exists in the Credential_Store, THEN THE API_Gateway SHALL reject the request with HTTP `409` and a response body indicating the email is already registered, and SHALL NOT create a User_Account.

### Requirement 2: Atomic account provisioning

**User Story:** As a platform operator, I want new-account creation to be atomic, so that a new user never ends up with a login credential but no portfolio-visible identity row (or vice versa).

#### Acceptance Criteria

1. WHEN the API_Gateway provisions a new User_Account, THE API_Gateway SHALL create the Users_Table row and the Credential_Store entry within a single Provisioning_Transaction that either commits both writes or commits neither.
2. IF any step of the Provisioning_Transaction fails, THEN THE API_Gateway SHALL roll back the Provisioning_Transaction so that neither the Users_Table row nor the Credential_Store entry persists, AND THE API_Gateway SHALL return an error response to the caller indicating that account provisioning failed.
3. WHEN the Provisioning_Transaction commits, THE API_Gateway SHALL ensure the created Users_Table row uses the same user id that the Jwt_Signer places in the token `sub` claim for that User_Account.
4. WHEN a User_Account has been provisioned, THE Users_Table SHALL contain a row that `PortfolioService.requireUserExists()` matches for that user id.
5. WHILE performing the Provisioning_Transaction from the WebFlux-based API_Gateway, THE API_Gateway SHALL execute the blocking database work on a scheduler dedicated to blocking operations so that the reactive event-loop threads remain available to serve other requests during the blocking work.
6. THE portfolio-service SHALL remain the Schema_Owner of every table the API_Gateway reads or writes, and THE API_Gateway SHALL NOT define or run any Flyway migration.
7. IF a User_Account with the same user id already exists in the Users_Table or Credential_Store when provisioning is requested, THEN THE API_Gateway SHALL reject the request with an error response indicating the account already exists and SHALL NOT modify the existing Users_Table row or Credential_Store entry.
8. WHILE two Provisioning_Transactions for the same user id execute concurrently, THE API_Gateway SHALL allow at most one Provisioning_Transaction to commit and SHALL roll back the other.

### Requirement 3: Per-user login with password verification

**User Story:** As a registered user, I want to log in with my own email and password, so that I can access my portfolio securely.

#### Acceptance Criteria

1. WHEN a login request is received at the Login_Endpoint, THE API_Gateway SHALL look up the submitted email in the Credential_Store rather than comparing against a single configured credential pair.
2. WHEN a login request presents an email that exists in the Credential_Store AND a password that the Password_Hasher verifies against the stored Password_Hash, THE API_Gateway SHALL return a token minted by the Jwt_Signer together with the user id, email, and name, in the shape defined by the Session_Contract.
3. WHEN the API_Gateway verifies a submitted password, THE API_Gateway SHALL use the Password_Hasher to compare the submitted password against the stored Password_Hash and SHALL NOT use string equality.
4. WHEN a login request presents an email that is absent from the Credential_Store, THE API_Gateway SHALL perform a Password_Hasher verification of the submitted password against the Dummy_Password_Hash before returning the Uniform_Auth_Error, so that the unknown-email path and the wrong-password path take approximately the same amount of time.
5. IF a login request presents an email absent from the Credential_Store, THEN THE API_Gateway SHALL return the Uniform_Auth_Error with HTTP `401` after the Dummy_Password_Hash verification completes and SHALL NOT mint a token.
6. IF a login request presents an email present in the Credential_Store but a password that the Password_Hasher does not verify, THEN THE API_Gateway SHALL return the Uniform_Auth_Error with HTTP `401` and SHALL NOT mint a token.
7. THE API_Gateway SHALL continue to mint tokens through the existing Jwt_Signer with unchanged claims (`sub`, `email`, `name`), 1-hour expiry, and HS256 algorithm.
8. THE API_Gateway SHALL remove the `app.auth.{email,password,user-id,name}` single-credential configuration binding and its string-equality check from the login path.
9. IF a login request omits the email field, omits the password field, or submits either field as an empty or whitespace-only value, THEN THE API_Gateway SHALL return the Uniform_Auth_Error with HTTP `401` and SHALL NOT invoke the Password_Hasher or mint a token.
10. IF the Credential_Store cannot be reached or returns an error during email lookup, THEN THE API_Gateway SHALL return an error response indicating the service is temporarily unavailable, SHALL NOT mint a token, and SHALL NOT reveal whether the submitted email exists.
11. THE Login_Endpoint SHALL verify the submitted password against the stored Password_Hash only and SHALL NOT enforce the Password_Policy maximum length, so that legacy or seed passwords remain usable; only the Signup_Endpoint validates password length.

### Requirement 4: Password hashing policy

**User Story:** As a security-conscious operator, I want passwords stored only as strong one-way hashes, so that a database disclosure does not expose usable passwords.

#### Acceptance Criteria

1. WHEN the API_Gateway stores a password during signup, THE API_Gateway SHALL store only a Password_Hash produced by the Password_Hasher and SHALL NOT store the plaintext password.
2. WHEN the API_Gateway processes a password during signup or login, THE API_Gateway SHALL NOT write the plaintext password to logs, error responses, or any persistent store other than as a Password_Hash.
3. THE Password_Hasher SHALL use bcrypt with a cost factor of at least 10 or argon2id.
4. WHEN the API_Gateway verifies a login password, THE API_Gateway SHALL perform a Password_Hasher verification against the stored Password_Hash and SHALL retain the stored Password_Hash unchanged.
5. IF the Password_Hasher verification does not match the stored Password_Hash, THEN THE API_Gateway SHALL return the Uniform_Auth_Error with HTTP `401` and SHALL NOT establish an authenticated session.
6. WHERE the stored Password_Hash for a matched email is absent or malformed, THE API_Gateway SHALL return the Uniform_Auth_Error with HTTP `401` and SHALL NOT establish an authenticated session.

### Requirement 5: Signup and login UI

**User Story:** As a new visitor, I want a registration page and a working login page, so that I can create an account and sign in from the browser.

#### Acceptance Criteria

1. THE frontend SHALL provide a Signup_Page under `frontend/src/app/(auth)/` that collects an email (1 to 254 characters), a password (minimum 12 characters and maximum 72 bytes UTF-8, matching the Password_Policy), and a name (1 to 100 characters).
2. WHEN the user submits the Signup_Page with a syntactically valid email, a password of at least 12 characters whose UTF-8 encoding is at most 72 bytes (matching the Password_Policy), and a name of 1 to 100 characters, THE Signup_Page SHALL send the entered email, password, and name to the Signup_Endpoint.
3. IF the user submits the Signup_Page with a missing or syntactically invalid email, a password shorter than 12 characters or whose UTF-8 encoding exceeds 72 bytes, or a name outside the 1 to 100 character range, THEN THE Signup_Page SHALL display a message identifying the specific invalid field, SHALL NOT call the Signup_Endpoint, and SHALL retain the entered email and name.
4. WHEN the Signup_Endpoint returns a successful response, THE Signup_Page SHALL persist the returned token, user id, email, and name under the Session_Contract (`localStorage` key `wmpt.auth.session`) and navigate the user to the authenticated dashboard.
5. WHEN the Signup_Endpoint returns an HTTP `400` or `409` response, THE Signup_Page SHALL display the corresponding validation or duplicate-email message to the user and SHALL retain the entered email and name.
6. IF the Signup_Endpoint request does not complete within 10 seconds, or returns an unsuccessful response other than HTTP `400` or `409`, THEN THE Signup_Page SHALL display an error message indicating the signup could not be completed, SHALL remain on the Signup_Page, and SHALL retain the entered email and name.
7. WHEN the user submits the Login_Page, THE Login_Page SHALL submit the credentials to the Login_Endpoint and, upon a successful response, SHALL persist the returned token, user id, email, and name under the Session_Contract (`localStorage` key `wmpt.auth.session`).
8. THE frontend SHALL provide a navigation control on the Login_Page that opens the Signup_Page and a navigation control on the Signup_Page that opens the Login_Page.
9. WHERE the frontend is built as a static export (`output: "export"`), THE Signup_Page and Login_Page SHALL function without any runtime Next.js server.

### Requirement 6: Auth-endpoint rate limiting

**User Story:** As a platform operator, I want login and signup requests throttled per client, so that the unauthenticated, hashing-heavy auth endpoints are protected from brute-force and enumeration attacks.

#### Acceptance Criteria

1. THE API_Gateway SHALL apply the Auth_Rate_Limiter to requests targeting the Login_Endpoint and the Signup_Endpoint through a `WebFilter` that reuses the existing `RedisRateLimiter` programmatically.
2. THE Auth_Rate_Limiter SHALL derive the Auth_Rate_Limit_Key using the Trusted_Hop_Resolver (governed by `app.rate-limit.trust-xff-last-hop`) so that a client cannot obtain a fresh bucket by rotating spoofed `X-Forwarded-For` prefixes.
3. WHEN a client exceeds its Auth_Bucket — a token bucket configured to an effective sustained rate of 5 requests per minute with a burst of 5 (`RedisRateLimiter` with `replenishRate` 1, `requestedTokens` 12, and `burstCapacity` 60, so 12 tokens accrue every 12 seconds ≈ 5 per minute and the burst allowance is 60/12 = 5) — for its Auth_Rate_Limit_Key, THE API_Gateway SHALL return a Throttled_Response with HTTP `429` and SHALL NOT execute the login or signup logic for that request.
4. WHILE a client submits requests within the token allowance of its Auth_Bucket for its Auth_Rate_Limit_Key, THE API_Gateway SHALL forward the request to the login or signup logic without applying a Throttled_Response.
5. THE Login_Endpoint and the Signup_Endpoint SHALL share a single Auth_Bucket per Auth_Rate_Limit_Key, so that combined login and signup request volume from one client is throttled against the same token bucket.
6. WHEN the API_Gateway returns a Throttled_Response, THE API_Gateway SHALL include a `Retry-After` header whose value is a non-negative integer number of seconds up to the Auth_Bucket refill bound (the time to accrue enough tokens to admit a request, at most 12 seconds), indicating the seconds until the client may retry.
7. IF the Redis backend does not return a result within the configured backend timeout (default 1000 milliseconds) or the connection fails, THEN THE Auth_Rate_Limiter SHALL allow the request to proceed to the login or signup logic rather than reject it, consistent with the fail-open behavior of the production-rate-limiting spec.
8. THE Auth_Rate_Limiter SHALL reuse the shared `RedisRateLimiter` and Trusted_Hop_Resolver components from the production-rate-limiting spec and SHALL NOT introduce a separate rate-limiting implementation.

### Requirement 7: Demo account preserved

**User Story:** As a recruiter, I want to sign in with one click using pre-filled demo credentials, so that I can explore the product without registering.

#### Acceptance Criteria

1. THE Schema_Owner SHALL seed exactly one Demo_Account as a User_Account under a dedicated UUID with email `demo@wealthtracker.dev` and a read-only flag set to true, consisting of one Users_Table row and one Credential_Store entry whose Password_Hash was produced by the Password_Hasher and verifies successfully against the demo password.
2. WHEN the Login_Page loads, THE Login_Page SHALL pre-fill the email and password input fields with the Demo_Account credentials sourced from `NEXT_PUBLIC_DEMO_EMAIL` and `NEXT_PUBLIC_DEMO_PASSWORD`, such that submitting the pre-filled form requires no additional data entry.
3. WHEN the pre-filled Demo_Account credentials are submitted, THE API_Gateway SHALL authenticate the Demo_Account using the same Credential_Store lookup and Password_Hasher verification applied to any other User_Account.
4. THE API_Gateway SHALL flag the Demo_Account as a Read_Only_Account using a server-side flag conveyed to the enforcement point at the API_Gateway, so that read-only enforcement requires no per-request database lookup.
5. IF the Demo_Account attempts to mutate portfolio data — a write using an HTTP `POST`, `PUT`, `PATCH`, or `DELETE` method to `/api/portfolio/**` or `/api/market/**` — THEN THE API_Gateway SHALL reject the operation with HTTP `403` and a JSON body indicating the demo account is read-only, and SHALL leave all persisted data unchanged.
6. WHERE the Demo_Account targets the AI routes (`/api/chat/**` and the insight-generation endpoints under `/api/insights/**`), THE API_Gateway SHALL allow the request even when it uses a write HTTP method, because these routes are the demo's flagship capabilities, are cost-capped by the strict rate limiter, and persist only data scoped to the Demo_Account itself.
7. WHEN the Demo_Account performs a non-mutating read or targets an allowlisted AI route, THE API_Gateway SHALL process the operation identically to any other authenticated User_Account.
8. THE Schema_Owner SHALL reassign the seeded showcase portfolio and its associated holdings and history rows from the dev user (`00000000-0000-0000-0000-000000000001`) to the Demo_Account's user id, so that the Demo_Account's dashboard is populated on first login, AND THE dev user SHALL remain a writable (non-read-only) account for local development.
9. WHEN the Demo_Account logs in, THE Demo_Account SHALL see the reassigned showcase portfolio populated in its dashboard.
10. THE Schema_Owner SHALL perform the reassignment of the showcase portfolio and its holdings and history rows as an explicit Flyway migration step, AND the reassignment SHALL be idempotent so that re-running it neither duplicates nor mis-assigns rows.
11. THE Demo_Account password SHALL be at least 12 characters (consistent with the Password_Policy minimum), SHALL be intentionally public (wired to `NEXT_PUBLIC_DEMO_PASSWORD` in the built frontend), and SHALL be seeded as a fresh bcrypt Password_Hash produced by the Password_Hasher rather than reusing any legacy scrypt `ba_account` hash.

### Requirement 8: Retire Better Auth and reconcile seed data

**User Story:** As a maintainer, I want the disconnected Better Auth system removed and identity consolidated, so that the repository has one coherent auth path instead of two half-built ones.

#### Acceptance Criteria

1. THE frontend SHALL remove the Better_Auth_Code files (`auth.ts`, `auth-client.ts`, `mintToken.ts`, `fetchWithAuth.server.ts`) such that no remaining frontend source file imports or references any of them.
2. WHEN the Better_Auth_Code files are removed, THE frontend SHALL remove the Better Auth npm dependency such that it is absent from both the package manifest and the dependency lockfile, and the frontend production build completes with zero errors.
3. THE Schema_Owner SHALL add a single Flyway cleanup migration that drops the Better_Auth_Tables (`ba_user`, `ba_session`, `ba_account`, `ba_verification`), this migration SHALL carry the highest (last) version number among the migrations in the release, and the Better_Auth_Code removal and this drop migration SHALL ship in the same release, so that no deployed build references the Better_Auth_Tables after they are dropped.
4. WHEN the Flyway cleanup migration is applied, THE Schema_Owner SHALL ensure the dev user and the E2E test user each exist as exactly one User_Account row in the Users_Table with a corresponding entry in the Credential_Store, creating any that are absent.
5. IF the dev user or the E2E test user already exists as a User_Account row in the Users_Table when the Flyway cleanup migration runs, THEN THE Schema_Owner SHALL NOT create a duplicate row and SHALL leave the existing Users_Table row and Credential_Store entry unchanged.
6. THE API_Gateway SHALL contain no compile-time or runtime dependency on any type from the Better Auth system, verified by a successful build with the Better_Auth_Code and Better_Auth_Tables removed.
7. THE Schema_Owner SHALL retain the Users_Table as the single authoritative identity store and SHALL NOT create or reintroduce any second parallel user table.

### Requirement 9: Minimal profile with room to grow

**User Story:** As a user, I want my display name stored with my account, so that the application can address me by name, while leaving room for later personalization.

#### Acceptance Criteria

1. WHEN a User_Account is created with a name of 1 to 100 characters after trimming leading and trailing whitespace, THE API_Gateway SHALL persist the trimmed name as part of the User_Account.
2. IF a User_Account is created with a name that is null, empty, whitespace-only, or exceeds 100 characters after trimming, THEN THE API_Gateway SHALL reject the creation with an error indicating an invalid name and SHALL NOT persist the User_Account.
3. WHEN the API_Gateway mints a token for a User_Account, THE Jwt_Signer SHALL populate the `name` claim with the exact persisted name of that User_Account.
4. THE Users_Table schema SHALL model the User_Account such that additional profile attributes (for example risk tolerance, preferences, or base currency) can be added later without removing or renaming the name, email, or user id fields.
5. WHEN a User_Account is created, THE Users_Table SHALL record a creation timestamp set to the date and time at which the User_Account is persisted, and SHALL NOT alter that timestamp on any subsequent update to the User_Account.

### Requirement 10: Integration test coverage

**User Story:** As a developer, I want automated tests for signup, login, provisioning, and throttling, so that regressions in the authentication path are caught in CI.

#### Acceptance Criteria

1. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")`, backed by a Testcontainers Postgres instance, that provisions a User_Account through the Signup_Endpoint and asserts that both a Users_Table row and a Credential_Store entry exist for the new user.
2. WHEN a signup succeeds in the Integration_Test_Suite, THE Integration_Test_Suite SHALL assert that the created Users_Table row is matched by `PortfolioService.requireUserExists()` for the token `sub` claim.
3. WHEN a Provisioning_Transaction is forced to fail in the Integration_Test_Suite, THE Integration_Test_Suite SHALL assert that the Signup_Endpoint returns an observable error response AND that neither a Users_Table row nor a Credential_Store entry persists for that email.
4. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")`, backed by a Testcontainers Postgres instance, that asserts a login with a correct email and password returns HTTP `200` with a non-empty token.
5. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")` that asserts a login with a correct email and a wrong password returns the Uniform_Auth_Error with HTTP `401` and no token.
6. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")` that asserts a login with an unknown email returns a response whose body is byte-identical to the wrong-password Uniform_Auth_Error response body.
7. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")` that asserts a duplicate-email signup returns HTTP `409` and that the Users_Table count of rows for that email remains exactly one.
8. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")`, backed by a Testcontainers Redis instance, that asserts requests to the Login_Endpoint exceeding the configured rate-limit threshold within the configured window receive HTTP `429` with a `Retry-After` header whose value is a positive integer number of seconds.
9. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")` that asserts, when Redis is unavailable, requests to the Login_Endpoint and Signup_Endpoint return a non-`5xx` response rather than failing.
10. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")` that asserts a write (`POST`, `PUT`, `PATCH`, or `DELETE`) to `/api/portfolio/**` by the Demo_Account returns HTTP `403` and leaves persisted data unchanged.
11. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")` that asserts a `POST` to `/api/chat/**` by the Demo_Account is allowed rather than rejected with HTTP `403`.
12. THE Integration_Test_Suite SHALL include a test annotated `@Tag("integration")`, backed by a Testcontainers Postgres instance, that asserts, after seeding, the Demo_Account's user id owns the showcase portfolio with non-empty holdings AND the dev user (`00000000-0000-0000-0000-000000000001`) no longer owns that showcase portfolio.
