# Requirements Document

## Introduction

The Authentication & Identity Layer introduces end-to-end user authentication and identity propagation
across the Wealth Management & Portfolio Tracker platform. Currently, all TanStack Query hooks in the
frontend default to the hard-coded fallback `userId = "user-001"`, and backend controllers accept
`userId` as an unenforced path variable or query parameter with the same default. This feature
replaces those stubs with a real, profile-aware auth flow.

The design follows the "Bouncer" pattern: the API Gateway is the single JWT validation point.
Downstream services (portfolio-service, market-data-service, insight-service) receive the verified
user identity via an injected `X-User-Id` HTTP header and never parse JWTs themselves. The frontend
uses Auth.js (NextAuth v5) to manage sessions and forwards a Bearer JWT to the gateway on every
API call.

Two runtime profiles are supported:

- **local** — Auth.js Credentials provider with a symmetric HMAC-SHA256 signing key; no external
  OAuth dependency; suitable for Docker Compose development.
- **aws** — Auth.js OIDC provider backed by any OIDC-compatible IdP (e.g. AWS Cognito, Auth0,
  Google OAuth); asymmetric RS256 JWT validation via a JWK URI; swappable via Spring profile config
  only, with zero code changes.

---

## Glossary

- **Auth_System**: The combined authentication subsystem spanning the Next.js frontend (Auth.js),
  the API Gateway JWT filter, and the identity propagation contract used by downstream services.
- **Frontend**: The Next.js 16 application running in the user's browser.
- **Auth_Provider**: The Auth.js (NextAuth v5) library embedded in the Frontend that manages
  session creation, token issuance, and session refresh.
- **API_Gateway**: The Spring Cloud Gateway WebFlux service (`api-gateway` module) that acts as
  the sole entry point for all backend API calls and is the single JWT validation point.
- **JWT_Filter**: A Spring WebFlux `GlobalFilter` inside the API_Gateway that validates incoming
  Bearer JWTs and injects the `X-User-Id` header before forwarding requests downstream.
- **Downstream_Service**: Any backend microservice behind the API_Gateway
  (portfolio-service, market-data-service, insight-service) that consumes `X-User-Id` but never
  parses JWTs.
- **JWT**: A JSON Web Token issued by the Auth_Provider and forwarded by the Frontend as a Bearer
  token in the `Authorization` header.
- **sub_claim**: The `sub` field inside the JWT payload; its value MUST equal the UUID primary key
  of the authenticated user's row in the PostgreSQL `users` table.
- **X-User-Id**: An HTTP request header injected by the JWT_Filter after successful JWT validation;
  its value is the `sub_claim` of the validated JWT.
- **Local_Profile**: The Spring profile `local` and the Next.js `.env.local` configuration used
  during Docker Compose development.
- **AWS_Profile**: The Spring profile `aws` and the corresponding Next.js environment used during
  cloud deployment.
- **Credentials_Provider**: The Auth.js provider used under the Local_Profile that authenticates
  users against the PostgreSQL `users` table using email and password.
- **OIDC_Provider**: An external OpenID Connect-compatible identity provider (e.g. AWS Cognito,
  Auth0, Google OAuth) used under the AWS_Profile.
- **JWK_URI**: A URL that exposes the public key set used to verify RS256-signed JWTs issued by
  an OIDC_Provider.
- **Session_Cookie**: An httpOnly, Secure, SameSite=Lax cookie set by Auth.js that stores the
  encrypted session token on the client.
- **Rate_Limit_Key**: The value used to bucket requests for rate limiting in the API_Gateway;
  currently the client IP address, to be replaced with the authenticated user's `sub_claim`.
- **Test_JWT_Factory**: A test utility that mints valid, locally-signed JWTs for use in
  Testcontainers-based integration tests without requiring a running Auth.js instance.
- **User**: A record in the PostgreSQL `users` table with a UUID primary key, a unique email
  address, and a creation timestamp. Users are created by administrators or seed scripts; no
  self-registration flow exists.

---

## Requirements

### Requirement 1: Frontend Login Flow

**User Story:** As an investor, I want to log in with my email and password so that I can access
my personal portfolio dashboard securely.

#### Acceptance Criteria

1. WHEN an unauthenticated user navigates to any protected dashboard route, THE Frontend SHALL
   redirect the user to `/login` before rendering any dashboard content.

2. WHEN a user submits valid credentials on the login page, THE Auth_Provider SHALL create a
   session and redirect the user to the `/overview` dashboard route.

3. WHEN a user submits invalid credentials on the login page, THE Auth_Provider SHALL return an
   authentication error and THE Frontend SHALL display a non-technical error message on the login
   page without redirecting.

4. WHILE a user has an active session, THE Frontend SHALL make all API calls with a valid JWT in
   the `Authorization: Bearer <token>` header.

5. WHEN a user's session expires, THE Frontend SHALL redirect the user to `/login` and SHALL NOT
   silently retry the failed API call with stale credentials.

6. THE Frontend SHALL store the session exclusively in a Session_Cookie with the `httpOnly`,
   `Secure`, and `SameSite=Lax` attributes set; session data SHALL NOT be stored in
   `localStorage` or `sessionStorage`.

7. WHERE the Local_Profile is active, THE Auth_Provider SHALL authenticate users via the
   Credentials_Provider against the PostgreSQL `users` table.

8. WHERE the AWS_Profile is active, THE Auth_Provider SHALL authenticate users via the
   OIDC_Provider configured in environment variables; the OIDC_Provider SHALL be swappable by
   changing environment variables only, with no Frontend code changes required.

---

### Requirement 2: Frontend Logout Flow

**User Story:** As an investor, I want to log out so that my session is terminated and my data
is no longer accessible from the browser.

#### Acceptance Criteria

1. WHEN a user triggers the logout action, THE Auth_Provider SHALL invalidate the server-side
   session and clear the Session_Cookie.

2. WHEN logout completes, THE Frontend SHALL redirect the user to `/login`.

3. WHEN a user navigates to a protected route after logout, THE Frontend SHALL redirect to
   `/login` and SHALL NOT render any portfolio data.

4. IF the logout request fails due to a network error, THEN THE Frontend SHALL display an error
   message and SHALL retain the current session rather than leaving the user in an indeterminate
   state.

---

### Requirement 3: Authenticated User Identity in TanStack Query Hooks

**User Story:** As a developer, I want all TanStack Query hooks to derive the `userId` from the
authenticated session so that the hard-coded `"user-001"` fallback is eliminated.

#### Acceptance Criteria

1. THE Frontend SHALL expose an `useAuthenticatedUserId` hook that returns the `sub_claim` from
   the active Auth.js session.

2. WHEN an active session exists, THE `usePortfolio`, `usePortfolioPerformance`,
   `useAssetAllocation`, and `usePortfolioSummary` hooks SHALL use the `sub_claim` from the
   session as the `userId` parameter; the hard-coded default `"user-001"` SHALL be removed.

3. WHEN no active session exists, THE `usePortfolio`, `usePortfolioPerformance`,
   `useAssetAllocation`, and `usePortfolioSummary` hooks SHALL NOT issue any API requests and
   SHALL return a loading or unauthenticated state.

4. THE `portfolioKeys` query-key factory SHALL include the authenticated `userId` so that
   TanStack Query cache entries are scoped per user and SHALL NOT be shared across different
   authenticated users within the same browser session.

5. FOR ALL valid session tokens, the `userId` derived by `useAuthenticatedUserId` SHALL equal
   the `sub_claim` extracted from the JWT embedded in that session (round-trip property).

---

### Requirement 4: API Gateway JWT Validation

**User Story:** As a platform operator, I want the API Gateway to validate every inbound JWT so
that unauthenticated or tampered requests never reach downstream services.

#### Acceptance Criteria

1. THE JWT_Filter SHALL inspect the `Authorization` header of every inbound request routed
   through the API_Gateway.

2. WHEN a request carries a valid, unexpired JWT with a verified signature, THE JWT_Filter SHALL
   extract the `sub_claim` and inject it as the `X-User-Id` header before forwarding the request
   to the Downstream_Service.

3. WHEN a request carries no `Authorization` header, THE JWT_Filter SHALL return HTTP 401 and
   SHALL NOT forward the request to any Downstream_Service.

4. WHEN a request carries a JWT with an invalid signature, THE JWT_Filter SHALL return HTTP 401
   and SHALL NOT forward the request to any Downstream_Service.

5. WHEN a request carries an expired JWT, THE JWT_Filter SHALL return HTTP 401 and SHALL NOT
   forward the request to any Downstream_Service.

6. WHEN a request carries a JWT with a missing or empty `sub_claim`, THE JWT_Filter SHALL return
   HTTP 401 and SHALL NOT forward the request to any Downstream_Service.

7. IF JWT validation throws an unexpected exception, THEN THE JWT_Filter SHALL return HTTP 500,
   log the exception at ERROR level, and SHALL NOT forward the request downstream.

8. WHERE the Local_Profile is active, THE JWT_Filter SHALL validate JWTs using HMAC-SHA256 with
   a symmetric signing key read from the `AUTH_JWT_SECRET` environment variable.

9. WHERE the AWS_Profile is active, THE JWT_Filter SHALL validate JWTs using RS256 by fetching
   the public key set from the `AUTH_JWK_URI` environment variable; the JWT_Filter SHALL cache
   the JWK set and SHALL refresh it when key rotation is detected.

10. THE JWT validation strategy SHALL be swappable between symmetric (Local_Profile) and
    asymmetric (AWS_Profile) by changing Spring profile configuration only, with no Java code
    changes required.

11. FOR ALL valid JWTs, the `X-User-Id` value injected by the JWT_Filter SHALL equal the
    `sub_claim` of the JWT (round-trip property).

12. FOR ALL requests where the JWT_Filter injects `X-User-Id`, the JWT_Filter SHALL remove any
    pre-existing `X-User-Id` header supplied by the caller before injection, so that callers
    cannot spoof their identity.

---

### Requirement 5: API Gateway Rate Limiting by Authenticated User

**User Story:** As a platform operator, I want rate limiting to be keyed on the authenticated
user's identity rather than the client IP address so that shared NAT or proxy environments do
not cause legitimate users to be incorrectly throttled.

#### Acceptance Criteria

1. WHILE the JWT_Filter has successfully validated a JWT, THE API_Gateway SHALL use the
   `sub_claim` as the Rate_Limit_Key for the request.

2. WHEN a request is unauthenticated (no valid JWT), THE API_Gateway SHALL fall back to the
   client IP address as the Rate_Limit_Key.

3. THE Rate_Limit_Key resolution logic SHALL be a pure function of the `X-User-Id` header value
   and the client IP address, with no side effects.

4. FOR ALL authenticated requests, the Rate_Limit_Key SHALL equal the `sub_claim` of the
   validated JWT (invariant: key derivation is deterministic and identity-preserving).

5. WHERE the Local_Profile is active, THE API_Gateway SHALL back rate limiting with Redis.

6. WHERE the AWS_Profile is active, THE API_Gateway SHALL back rate limiting with a
   profile-configured store (AWS API Gateway usage plans or DynamoDB); the backing store SHALL
   be swappable via `application-aws.yml` with no code changes.

---

### Requirement 6: Downstream Service Identity Consumption

**User Story:** As a developer, I want downstream services to receive the authenticated user's
identity via a trusted header so that they can scope data access without parsing JWTs or
importing Spring Security.

#### Acceptance Criteria

1. THE `PortfolioController` SHALL extract the user identity exclusively from the `X-User-Id`
   request header using `@RequestHeader("X-User-Id") String userId`; the path variable
   `{userId}` SHALL be removed from the route.

2. THE `PortfolioSummaryController` SHALL extract the user identity exclusively from the
   `X-User-Id` request header; the `@RequestParam(defaultValue = "user-001") String userId`
   parameter SHALL be removed.

3. THE portfolio-service, market-data-service, and insight-service modules SHALL NOT declare a
   compile or runtime dependency on `spring-boot-starter-security` or any JWT parsing library.

4. THE portfolio-service, market-data-service, and insight-service modules SHALL NOT import any
   class from the `software.amazon.awssdk:cognitoidentityprovider` artifact.

5. WHEN a request reaches a Downstream_Service without an `X-User-Id` header (indicating the
   request bypassed the API_Gateway), THE Downstream_Service SHALL return HTTP 400 with a
   descriptive error message.

6. IF the `X-User-Id` header value does not correspond to a known user in the PostgreSQL `users`
   table, THEN THE portfolio-service SHALL return HTTP 404 with a descriptive error message.

---

### Requirement 7: JWT-to-User Mapping

**User Story:** As a platform operator, I want the `sub` claim in every JWT to map to a real
user record in the PostgreSQL `users` table so that identity is consistent across the system.

#### Acceptance Criteria

1. THE Auth_System SHALL ensure that the `sub_claim` value embedded in every issued JWT equals
   the UUID primary key of the corresponding row in the `users` table.

2. WHERE the Local_Profile is active, THE Credentials_Provider SHALL look up the user by email
   in the `users` table and SHALL set the `sub_claim` to the user's UUID upon successful
   authentication.

3. WHERE the AWS_Profile is active, THE Auth_System SHALL require that the OIDC_Provider's
   `sub` claim is pre-mapped to the user's UUID in the `users` table before the user can access
   protected resources; the mapping strategy SHALL be documented in the design document.

4. WHEN a JWT's `sub_claim` does not match any UUID in the `users` table, THE API_Gateway SHALL
   return HTTP 401 and SHALL NOT forward the request downstream.

5. FOR ALL authenticated sessions, the `sub_claim` in the JWT SHALL remain stable for the
   lifetime of the session and SHALL NOT change between token refreshes (invariant: identity
   stability).

---

### Requirement 8: JWT Signing Key Management

**User Story:** As a platform operator, I want JWT signing keys to be injected via environment
variables so that secrets are never committed to source control and can be rotated without
redeployment.

#### Acceptance Criteria

1. WHERE the Local_Profile is active, THE Auth_System SHALL read the HMAC-SHA256 signing key
   exclusively from the `AUTH_JWT_SECRET` environment variable; the key SHALL NOT be hard-coded
   in any source file or committed configuration file.

2. WHERE the Local_Profile is active, THE Auth_System SHALL read the `AUTH_JWT_SECRET` value
   from `application-local.yml` when the environment variable is not explicitly set, to support
   Docker Compose development without manual env var injection.

3. WHERE the AWS_Profile is active, THE JWT_Filter SHALL read the JWK URI exclusively from the
   `AUTH_JWK_URI` environment variable; the URI SHALL NOT appear in any committed configuration
   file.

4. IF the `AUTH_JWT_SECRET` environment variable is absent or blank at startup under the
   Local_Profile, THEN THE API_Gateway SHALL fail to start and SHALL log a descriptive error
   message identifying the missing variable.

5. THE `AUTH_JWT_SECRET` value SHALL NOT appear in any log output, stack trace, or HTTP response
   body at any log level.

---

### Requirement 9: Local Development Seed Data and Test JWT Factory

**User Story:** As a developer, I want a Test_JWT_Factory and seed user data so that I can run
integration tests and local development without a real identity provider.

#### Acceptance Criteria

1. THE Auth_System SHALL provide a Flyway migration that seeds at least one user record into the
   `users` table for local development; the seed record SHALL use a fixed, well-known UUID as
   its primary key.

2. THE Auth_System SHALL provide a `TestJwtFactory` utility class in the `api-gateway` test
   source set that mints valid, HMAC-SHA256-signed JWTs using a configurable signing key and
   `sub_claim`.

3. THE `TestJwtFactory` SHALL accept a `sub_claim` string and an expiry duration and SHALL
   return a signed JWT string that the JWT_Filter accepts as valid.

4. FOR ALL `sub_claim` values and expiry durations accepted by `TestJwtFactory`, the JWT
   produced SHALL be accepted by the JWT_Filter when the same signing key is used (round-trip
   property).

5. FOR ALL JWTs produced by `TestJwtFactory` with an expiry duration in the past, THE
   JWT_Filter SHALL reject the JWT with HTTP 401 (error condition property).

6. THE `TestJwtFactory` SHALL NOT be included in any production build artifact; it SHALL reside
   exclusively in `src/test/java`.

7. ALL integration tests that exercise the JWT_Filter SHALL be annotated with `@Tag("integration")`
   and SHALL use Testcontainers to provision the PostgreSQL database; they SHALL NOT connect to
   any real AWS service.

---

### Requirement 10: Auth.js Configuration Isolation

**User Story:** As a developer, I want Auth.js configuration to be fully isolated between local
and AWS profiles so that local development never requires cloud credentials and cloud deployments
never contain local secrets.

#### Acceptance Criteria

1. THE Frontend SHALL read Auth.js configuration (provider type, client ID, client secret, JWT
   secret) exclusively from environment variables; no Auth.js configuration values SHALL be
   hard-coded in source files.

2. WHERE the Local_Profile is active, THE Frontend SHALL configure Auth.js with the
   Credentials_Provider only; no OAuth client ID or secret SHALL be required.

3. WHERE the AWS_Profile is active, THE Frontend SHALL configure Auth.js with the OIDC_Provider
   using `AUTH_OIDC_CLIENT_ID`, `AUTH_OIDC_CLIENT_SECRET`, and `AUTH_OIDC_ISSUER` environment
   variables; the provider SHALL be swappable by changing these variables only.

4. THE `NEXTAUTH_SECRET` environment variable SHALL be set in `.env.local` for local development
   and SHALL be injected via the deployment environment for AWS; it SHALL NOT be committed to
   source control.

5. IF any required Auth.js environment variable is absent at Next.js startup, THEN THE Frontend
   SHALL fail to start and SHALL log a descriptive error identifying the missing variable.

---

### Requirement 11: Forbidden Dependencies (Architectural Guardrails)

**User Story:** As a platform architect, I want explicit requirements that forbid AWS-specific
and security-framework dependencies in downstream services so that the hexagonal architecture
and multi-cloud portability are preserved.

#### Acceptance Criteria

1. THE portfolio-service, market-data-service, and insight-service build configurations SHALL NOT
   declare a dependency on `software.amazon.awssdk:cognitoidentityprovider` at any scope
   (compile, runtime, testImplementation).

2. THE portfolio-service, market-data-service, and insight-service build configurations SHALL NOT
   declare a dependency on `spring-boot-starter-security` at any scope.

3. THE portfolio-service, market-data-service, and insight-service build configurations SHALL NOT
   declare a dependency on any JWT parsing library (e.g. `com.auth0:java-jwt`,
   `io.jsonwebtoken:jjwt-api`, `com.nimbusds:nimbus-jose-jwt`) at any scope.

4. THE api-gateway build configuration SHALL NOT declare a dependency on
   `software.amazon.awssdk:cognitoidentityprovider` at any scope.

5. THE JWT_Filter implementation SHALL reside exclusively in the `api-gateway` module; no JWT
   validation logic SHALL be duplicated in any Downstream_Service module.

---

### Requirement 12: Security Headers and Transport

**User Story:** As a platform operator, I want the API Gateway to enforce secure transport and
strip sensitive headers so that the auth contract cannot be bypassed by crafted requests.

#### Acceptance Criteria

1. THE JWT_Filter SHALL strip any `X-User-Id` header present on the inbound request before
   performing JWT validation, ensuring that the injected value originates exclusively from the
   JWT_Filter.

2. THE API_Gateway SHALL reject requests that attempt to set `X-User-Id` directly without a
   valid JWT by returning HTTP 401.

3. WHERE the AWS_Profile is active, THE API_Gateway SHALL enforce HTTPS-only access; requests
   arriving over HTTP SHALL be redirected to HTTPS.

4. THE Session_Cookie set by the Auth_Provider SHALL have the `Secure` flag set in all
   non-local environments.

5. THE JWT_Filter SHALL not log the full JWT token value at any log level; it MAY log the
   `sub_claim` and token expiry at DEBUG level for diagnostics.

---

### Requirement 13: Correctness Properties for Property-Based Testing

**User Story:** As a developer, I want well-defined correctness properties for the auth layer so
that property-based tests can systematically verify invariants across arbitrary inputs.

#### Acceptance Criteria

1. FOR ALL strings `s` that are valid `sub_claim` values (non-null, non-blank UUID strings),
   a JWT minted by `TestJwtFactory` with `sub_claim = s` and a future expiry SHALL be accepted
   by the JWT_Filter, and the `X-User-Id` header injected downstream SHALL equal `s`
   (round-trip: mint → validate → extract).

2. FOR ALL JWTs where the signature byte at any position is flipped, THE JWT_Filter SHALL return
   HTTP 401 (error condition: tampered signature always rejected).

3. FOR ALL JWTs minted with an expiry timestamp strictly in the past, THE JWT_Filter SHALL
   return HTTP 401 regardless of the `sub_claim` value (error condition: expired tokens always
   rejected).

4. FOR ALL pairs of distinct `sub_claim` values `a` and `b`, the Rate_Limit_Key derived for a
   request authenticated as `a` SHALL NOT equal the Rate_Limit_Key derived for a request
   authenticated as `b` (invariant: rate limit keys are user-distinct).

5. FOR ALL valid session tokens produced by Auth.js, the `userId` returned by
   `useAuthenticatedUserId` SHALL equal the `sub_claim` embedded in the session JWT
   (round-trip: session → hook → sub_claim).

6. THE `resolveKey` function in `GatewayRateLimitConfig` SHALL be idempotent: calling it twice
   with the same inputs SHALL return the same key (idempotence property).

7. FOR ALL authenticated requests where the JWT_Filter injects `X-User-Id = v`, a second
   application of the JWT_Filter to the same request SHALL produce `X-User-Id = v` unchanged
   (idempotence: header injection is stable).

8. FOR ALL requests carrying a JWT signed with a key that differs from the configured signing
   key, THE JWT_Filter SHALL return HTTP 401 (error condition: wrong key always rejected).
