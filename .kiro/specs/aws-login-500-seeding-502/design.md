# Design: Production Demo-Ready Auth and Seeding Fix

## Overview

The live AWS deployment currently has two visible failures:

1. `POST /api/auth/login` returns HTTP 500 in production.
2. AWS synthetic E2E seeding can fail with HTTP 502 before tests run.

Both failures block demo readiness. The required end state is not only that login succeeds, but that the logged-in demo user receives seeded data on the dashboard.

## Goals

- Production login succeeds with a configured demo username and password.
- The JWT issued by `AuthController` is accepted by the same API Gateway on protected requests.
- The production demo user ID matches the user ID seeded by the golden-state seeder.
- AWS synthetic monitoring can seed and verify dashboard data reliably despite Lambda cold starts.
- Frontend login errors distinguish invalid credentials from backend/configuration failures.

## Non-Goals

- Migrating to Cognito or another external identity provider.
- Changing the single-user demo auth model.
- Replacing the existing golden-state seeder with application startup seeding.
- Hardcoding production demo secrets in the repository.

## Current Problems

### 1. API Gateway Lambda lacks `AUTH_JWT_SECRET`

`AuthController.login()` calls `JwtSigner.hasValidSecret()` before issuing a token. On Lambda, `auth.jwt.secret` resolves from `AUTH_JWT_SECRET`, but Terraform does not currently inject that environment variable into `wealth-api-gateway`.

Result: valid login credentials return HTTP 500.

### 2. AWS JWT decoder does not match the local token issuer

`AuthController` issues HS256 JWTs using `JwtSigner`. Under the `aws` profile, `JwtDecoderConfig` validates RS256 tokens from `AUTH_JWK_URI`.

Result: after fixing token signing, protected API calls would still reject the gateway's own issued token.

### 3. Demo login identity can differ from seeded data identity

The frontend stores the login response `userId`, the JWT `sub` becomes `X-User-Id`, and `portfolio-service` filters portfolios by `X-User-Id`.

The portfolio seeder uses the fixed seeded user ID:

- `00000000-0000-0000-0000-000000000e2e`

If production auth returns a different `APP_AUTH_USER_ID`, login can succeed but the dashboard can still be empty.

### 4. Seeding retry/warm-up ownership is split

The workflow pre-warms Lambda endpoints in shell before Playwright, and `global-setup.ts` also warms dependencies and retries seed calls.

Result: failures are harder to diagnose and retry behavior is harder to reason about.

### 5. Frontend masks backend login failures

The login page currently shows `Invalid username or password.` for any failed login request, including HTTP 500 misconfiguration.

Result: production configuration failures look like user error during a demo.

## Target Design

### Production demo identity

Use one canonical demo identity across auth, seeding, and synthetic tests.

Recommended values:

| Setting | Source | Value |
| --- | --- | --- |
| `APP_AUTH_EMAIL` | GitHub secret via Terraform | demo/test email |
| `APP_AUTH_PASSWORD` | GitHub secret via Terraform | demo/test password |
| `APP_AUTH_USER_ID` | Terraform variable/default | `00000000-0000-0000-0000-000000000e2e` |
| `APP_AUTH_NAME` | Terraform variable/default | `Demo User` |
| `E2E_TEST_USER_ID` | workflow env/default | `00000000-0000-0000-0000-000000000e2e` |

The exact email/password should be supplied through repository secrets. They must not be committed.

### JWT signing and validation

For the current single-user auth model, production should use HS256 end-to-end:

1. `AuthController` issues HS256 JWTs using `AUTH_JWT_SECRET`.
2. `JwtDecoderConfig` validates HS256 JWTs using the same `AUTH_JWT_SECRET` under both `local` and `aws` profiles.
3. `AUTH_JWT_SECRET` must be at least 32 bytes.
4. Blank or too-short secrets should fail fast or return a clear configuration error.

The current RS256/JWK configuration should be removed from the active AWS path or isolated behind a future external-IdP profile.

### Terraform environment ownership

Terraform should own all runtime auth configuration for Lambda:

- `AUTH_JWT_SECRET`
- `APP_AUTH_EMAIL`
- `APP_AUTH_PASSWORD`
- `APP_AUTH_USER_ID`
- `APP_AUTH_NAME`
- existing runtime secrets such as `INTERNAL_API_KEY`

The API Gateway Lambda environment should be validated by Terraform plan assertions where practical.

### Seeding behavior

`frontend/tests/e2e/global-setup.ts` should be the single owner of live synthetic seeding resilience:

1. Resolve `E2E_TEST_USER_ID` separately from `E2E_TEST_USER_EMAIL`.
2. Seed portfolio, market data, and insight cache using that user ID.
3. Retry transient seed failures: `429`, `500`, `502`, `503`, `504`.
4. Treat `internal_api_key_not_configured` as non-transient.
5. Log attempt count, final status, and response body excerpt when seeding fails.

The separate GitHub shell pre-warm step should be removed or reduced to a non-authoritative diagnostic step. Playwright seeding should remain responsible for retrying cold-start failures.

### Frontend login errors

Frontend login should map errors by status:

| Status | Message |
| --- | --- |
| `401` | Invalid username or password. |
| `500` / `503` | Login service is temporarily unavailable. Please try again shortly. |
| network error | Unable to reach the login service. Please try again. |
| malformed success body | Login response was invalid. Please try again. |

This preserves user-friendly behavior while exposing production service failures during demos.

## Correctness Properties

- P1: `POST /api/auth/login` with valid production demo credentials returns HTTP 200.
- P2: Login response contains a non-empty JWT and `userId = 00000000-0000-0000-0000-000000000e2e`.
- P3: The JWT issued by login is accepted by the API Gateway on protected routes.
- P4: `GET /api/portfolio` with the returned JWT is not rejected with 401.
- P5: The returned portfolio data contains seeded holdings for the demo user.
- P6: AWS synthetic seeding retries at least once after an initial 502.
- P7: The frontend does not show `Invalid username or password.` for backend 500/503 failures.

## Validation Strategy

### Local/CI validation

- Run API Gateway unit/integration tests for JWT signing and decoding.
- Run frontend unit tests for login error handling.
- Run Playwright auth preflight locally against Docker Compose.
- Run Terraform format, validate, plan, and plan assertions.

### Production smoke validation

After deployment:

1. Login with the configured demo credentials.
2. Confirm HTTP 200 and JWT response.
3. Confirm `userId` is the seeded demo user ID.
4. Use the JWT against `/api/portfolio`.
5. Confirm portfolio data includes seeded holdings.
6. Run the AWS synthetic Playwright project against `https://vibhanshu-ai-portfolio.dev`.

## Risks and Mitigations

| Risk | Mitigation |
| --- | --- |
| Production secrets missing in GitHub Actions | Add Terraform variable validation and plan assertions. |
| JWT secret mismatch between build/test/deploy paths | Use one repository secret, `AUTH_JWT_SECRET`, for all paths. |
| Demo account logs in but sees empty data | Force `APP_AUTH_USER_ID` and `E2E_TEST_USER_ID` to the seeded user ID. |
| Lambda cold starts still produce 502 | Keep seed-level retries and improve final error diagnostics. |
| Future Cognito work needs RS256/JWK | Move JWK support to a future explicit external-IdP profile instead of current `aws`. |