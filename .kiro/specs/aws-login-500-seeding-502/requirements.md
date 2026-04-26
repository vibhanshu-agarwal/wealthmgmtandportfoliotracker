# Bug Report: AWS Login 500 + E2E Seeding 502

## Summary

Two related production bugs prevent the live AWS deployment from functioning:

1. **Login returns HTTP 500** — `POST /api/auth/login` on `https://vibhanshu-ai-portfolio.dev` returns 500 instead of a JWT. The frontend catch block shows "Invalid username or password" for any non-2xx response, masking the real error.

2. **E2E seeding fails with 502** — The Playwright golden-state seeder (`global-setup.ts`) fails with `Portfolio seeding failed: 502 Internal Server Error` in CI, blocking all E2E tests on every push to `main`.

Both bugs share the same root cause: the api-gateway Lambda is misconfigured for the `prod,aws` Spring profile.

---

## Bug 1: Login 500 — `AUTH_JWT_SECRET` not set on Lambda

### Observed behaviour
```
POST https://vibhanshu-ai-portfolio.dev/api/auth/login
→ HTTP 500 Internal Server Error
Frontend shows: "Invalid username or password."
```

### Root cause (confirmed by code trace)

`AuthController.login()` calls `jwtSigner.hasValidSecret()` before signing the token:

```java
// AuthController.java
if (!jwtSigner.hasValidSecret()) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new LoginDtos.ErrorResponse("AUTH_JWT_SECRET must be at least 32 bytes for HS256."));
}
```

`JwtSigner.hasValidSecret()` checks that `auth.jwt.secret` is ≥ 32 bytes:

```java
// JwtSigner.java
public boolean hasValidSecret() {
    return jwtSecretBytes.length >= 32;
}
```

`auth.jwt.secret` resolves from `${AUTH_JWT_SECRET:}` — an empty default. On Lambda, `AUTH_JWT_SECRET` is **never injected** by Terraform. The `api_gateway_container_env` block and the `merge(...)` in `aws_lambda_function.api_gateway` contain no `AUTH_JWT_SECRET` entry. The secret is 0 bytes → `hasValidSecret()` returns `false` → 500.

### Why it was not caught earlier

- Local dev: `application-local.yml` provides a 32-char fallback secret, so login works locally.
- The frontend catch block shows "Invalid username or password" for **any** non-2xx status, including 500, so the 500 was invisible to manual testers.
- The `aws` profile `JwtDecoderConfig` bean uses RS256/JWK (`AUTH_JWK_URI`) for **validating** incoming tokens — a separate concern from **issuing** them. The decoder bean construction does not fail at startup (Nimbus defers JWK fetch), so the Lambda starts up cleanly and the misconfiguration is only revealed at first login.

### Additional design inconsistency

The `aws` profile decoder (`JwtDecoderConfig.awsJwtDecoder`) expects RS256 tokens from a JWK endpoint, but `JwtSigner` always issues HS256 tokens. Even if `AUTH_JWT_SECRET` were set, the issued HS256 token would be rejected by the RS256 decoder on the next authenticated request. There is no external identity provider in this system — `AuthController` is the sole token issuer. The RS256/JWK path was designed for a future Cognito integration that was never implemented.

### Credential mismatch (secondary issue)

The aws-synthetic E2E tests use `E2E_TEST_USER_PASSWORD` (default: `e2e-test-password-2026`), but `app.auth.password` on Lambda defaults to `password` (from `application.yml`). Even if the 500 were fixed, the synthetic login test would fail with 401 unless either:
- `APP_AUTH_PASSWORD` is set to `e2e-test-password-2026` on Lambda via Terraform, or
- The synthetic test is updated to use `password`.

---

## Bug 2: E2E Seeding 502 — Portfolio Lambda cold-start not absorbed

### Observed behaviour (CI log, 2026-04-26)
```
[2026-04-26T11:25:47.284Z] Seeding ERROR: Error: Portfolio seeding failed: 502 Internal Server Error
  at runSeeding (global-setup.ts:159:13)
  at globalSetup (global-setup.ts:216:3)
Error: Process completed with exit code 1.
```

### Context

The seeder in `global-setup.ts` runs as part of `ci-verification.yml` → `docker-build-verify` job → "Run AWS synthetic monitoring" step, which targets `https://vibhanshu-ai-portfolio.dev` (live Lambda). The 502 is CloudFront's response when the api-gateway Lambda (or the downstream portfolio-service Lambda) exceeds the 60s origin-read timeout during a cold start.

### Root cause

The `warmSeedDependencies()` function added in the current open PR (`fix/mongo-health-seeding-retries`) polls `/api/portfolio/health`, `/api/market/health`, and `/api/insights/health` before seeding. However:

1. **`/api/portfolio/health` goes through CloudFront → api-gateway → portfolio-service.** A 200 from this endpoint only proves the api-gateway and portfolio-service are warm. It does not prove the api-gateway Lambda itself is warm for the `/api/internal/portfolio/seed` path, which is a different route with different cold-start characteristics.

2. **The warm-up step in `ci-verification.yml` (shell `curl` loop) and the Playwright `warmSeedDependencies()` are redundant and may race.** The CI shell step runs before Playwright starts, but `warmSeedDependencies()` runs again inside Playwright's `globalSetup`. If the shell step already warmed the stack, the Playwright warm-up is wasted time. If the shell step failed silently (`set +e`), the Playwright warm-up may not have enough budget.

3. **The 502 is not retried.** The seeder's `isTransientSeedStatus()` includes 502 as retryable, but the error in the log shows the seeder threw immediately after the first 502 — suggesting the warm-up consumed all the retry budget before the actual seed call, or the retry logic has a bug where the final attempt throws instead of returning the response.

4. **Relationship to Bug 1:** If the login 500 is fixed but the seeder still 502s, the E2E synthetic tests will fail at the login step anyway (because the seeded user's password won't match). Both bugs must be fixed together for the E2E suite to pass end-to-end.

---

## Affected Files

| File | Issue |
|------|-------|
| `infrastructure/terraform/modules/compute/main.tf` | `AUTH_JWT_SECRET` missing from `api_gateway_container_env` |
| `api-gateway/src/main/java/com/wealth/gateway/JwtDecoderConfig.java` | `aws` profile uses RS256/JWK decoder; `JwtSigner` issues HS256 — mismatch |
| `api-gateway/src/main/resources/application-prod.yml` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` set but no JWK endpoint exists |
| `frontend/tests/e2e/global-setup.ts` | 502 retry logic may not cover the seed call after warm-up exhausts retries |
| `.github/workflows/ci-verification.yml` | Shell warm-up and Playwright warm-up are redundant; shell step uses `set +e` so failures are silent |
| `frontend/tests/e2e/aws-synthetic/aws-synthetic.spec.ts` | Uses `E2E_TEST_USER_PASSWORD` which does not match `app.auth.password` default |

---

## Correctness Properties

The following properties must hold after the fix:

1. **P1 — Login succeeds on Lambda:** `POST /api/auth/login` with valid credentials returns HTTP 200 and a JWT on the `prod,aws` Spring profile.

2. **P2 — Issued JWT is accepted by the same gateway:** A JWT issued by `AuthController` on Lambda is accepted by the `JwtDecoder` bean active on the same Lambda for subsequent authenticated requests.

3. **P3 — Credential consistency:** The credentials accepted by `AuthController` on Lambda match the credentials used by the aws-synthetic E2E tests.

4. **P4 — Seeding completes before E2E tests run:** `global-setup.ts` successfully seeds portfolio, market, and insight data on the live Lambda stack before any Playwright test begins.

5. **P5 — 502 is retried:** A 502 from the portfolio seed endpoint is retried at least once before the seeder gives up.

---

## Out of Scope

- Migrating to Cognito or any external IdP (the RS256/JWK path was speculative; removing it is in scope)
- Changing the single-user auth model
- Any changes to downstream services (portfolio, market, insight)
