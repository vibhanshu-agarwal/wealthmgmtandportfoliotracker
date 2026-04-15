# Bugfix Requirements Document

## Introduction

10 out of 27 api-gateway integration tests are failing in CI. All 8 JWT-related failures occur in tests that assert valid JWTs are **not** rejected (status ≠ 401), while tests asserting invalid JWTs **are** rejected (status = 401) all pass. The 2 rate-limiting failures are a downstream consequence — valid JWTs are rejected at the security layer before reaching the rate limiter, so no 429 responses or rate-limit headers are produced.

The root cause is a JWT signing secret mismatch between the test JWT minting utility (`TestJwtFactory.TEST_SECRET`) and the secret resolved by `JwtDecoderConfig.localJwtDecoder()` at runtime. The test `application-local.yml` sets `auth.jwt.secret: test-secret-for-integration-tests-min-32-chars`, but the main `application-local.yml` sets `auth.jwt.secret: ${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}`. During Spring Boot property resolution, the `${AUTH_JWT_SECRET:...}` placeholder in the main config resolves to the default value `local-dev-secret-change-me-min-32-chars` (when the env var is unset) or to whatever `AUTH_JWT_SECRET` is set to in CI — either way, a different secret than what `TestJwtFactory` uses to sign tokens. This causes NimbusReactiveJwtDecoder to reject all test-minted JWTs with a signature verification failure, resulting in 401 for every valid-JWT test case.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a valid JWT is minted by `TestJwtFactory` using `TEST_SECRET` ("test-secret-for-integration-tests-min-32-chars") and sent to the gateway running under the `local` profile THEN the system returns HTTP 401 Unauthorized because `JwtDecoderConfig.localJwtDecoder()` resolves `auth.jwt.secret` to a different value ("local-dev-secret-change-me-min-32-chars" or the CI `AUTH_JWT_SECRET` env var), causing HMAC-SHA256 signature verification to fail

1.2 WHEN valid JWTs with various `sub` claims are sent to the gateway in parameterized tests THEN the system returns HTTP 401 for all of them, regardless of the `sub` value, because the signing secret mismatch affects all tokens minted by `TestJwtFactory`

1.3 WHEN a valid JWT is sent alongside a spoofed `X-User-Id` header THEN the system returns HTTP 401 instead of overriding the spoofed header with the JWT's `sub` claim, because the JWT is rejected before the `JwtAuthenticationFilter` can extract the `sub`

1.4 WHEN rate-limiting integration tests send requests with valid JWTs expecting to reach the rate limiter THEN the system returns HTTP 401 instead of allowing the request through to the rate-limiting layer, so no 429 responses or `X-RateLimit-Remaining` headers are ever produced

### Expected Behavior (Correct)

2.1 WHEN a valid JWT is minted by `TestJwtFactory` using `TEST_SECRET` and sent to the gateway running under the `local` profile THEN the system SHALL accept the JWT (status ≠ 401) because the gateway's `JwtDecoderConfig.localJwtDecoder()` SHALL use the same signing secret as `TestJwtFactory.TEST_SECRET`

2.2 WHEN valid JWTs with various `sub` claims are sent to the gateway in parameterized tests THEN the system SHALL accept all of them (status ≠ 401) and route the requests to the upstream

2.3 WHEN a valid JWT is sent alongside a spoofed `X-User-Id` header THEN the system SHALL accept the JWT (status ≠ 401), strip the spoofed header, and inject the JWT's `sub` claim as the `X-User-Id` header

2.4 WHEN rate-limiting integration tests send requests with valid JWTs THEN the system SHALL pass the requests through the security layer to the rate limiter, producing 429 responses when burst capacity is exceeded and including `X-RateLimit-Remaining` headers on allowed responses

### Unchanged Behavior (Regression Prevention)

3.1 WHEN a request has no `Authorization` header THEN the system SHALL CONTINUE TO return HTTP 401 Unauthorized

3.2 WHEN a request carries an expired JWT THEN the system SHALL CONTINUE TO return HTTP 401 Unauthorized

3.3 WHEN a request carries a JWT with a tampered signature THEN the system SHALL CONTINUE TO return HTTP 401 Unauthorized

3.4 WHEN a request carries a JWT signed with a wrong/unknown secret THEN the system SHALL CONTINUE TO return HTTP 401 Unauthorized

3.5 WHEN a request carries a JWT without a `sub` claim THEN the system SHALL CONTINUE TO return HTTP 401 Unauthorized

3.6 WHEN the gateway runs under the `local` profile in non-test contexts (e.g., local development via `bootRun`) THEN the system SHALL CONTINUE TO use the `auth.jwt.secret` property from the main `application-local.yml` (with `AUTH_JWT_SECRET` env var support)

3.7 WHEN the gateway runs under the `aws` profile THEN the system SHALL CONTINUE TO use the RS256 asymmetric JWK URI decoder, unaffected by any changes to the local profile secret configuration
