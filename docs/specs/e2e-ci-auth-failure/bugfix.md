# Bugfix Requirements Document

## Introduction

All 5 E2E integration tests in the `frontend-e2e-integration.yml` GitHub Actions workflow fail with 401 Unauthorized errors. The root cause is a JWT signing secret mismatch between the E2E test helper and the API Gateway when running inside Docker Compose in CI. A secondary issue is the absence of CORS configuration on the API Gateway, which causes browsers to report auth failures as CORS errors.

The CI workflow sets `AUTH_JWT_SECRET` from GitHub Secrets as a job-level environment variable. The E2E test helper (`frontend/tests/e2e/helpers/auth.ts`) reads `process.env.AUTH_JWT_SECRET` to mint HS256 JWTs. However, `docker-compose.yml` does not pass `AUTH_JWT_SECRET` into the `api-gateway` container's environment block, so the gateway falls back to the hardcoded default `local-dev-secret-change-me-min-32-chars` from `application-local.yml`. The test mints tokens with the CI secret; the gateway validates them with the fallback default. The signatures never match, producing 401 on every authenticated request.

A secondary symptom is that the API Gateway's `SecurityConfig.java` has no `.cors()` configuration. When the browser receives a 401 response lacking `Access-Control-Allow-Origin` headers, it reports the failure as a CORS error rather than an auth error. This masks the real problem in test diagnostics and would also block any future direct browser-to-gateway requests.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN the CI workflow starts the Docker Compose stack AND `AUTH_JWT_SECRET` is set as a job-level env var from GitHub Secrets THEN the `api-gateway` container does not receive `AUTH_JWT_SECRET` because `docker-compose.yml` omits it from the service's environment block, causing the gateway to fall back to the hardcoded default secret `local-dev-secret-change-me-min-32-chars`

1.2 WHEN the E2E test helper (`auth.ts`) mints an HS256 JWT using `process.env.AUTH_JWT_SECRET` (the CI secret value) AND sends it to the API Gateway running in Docker THEN the gateway rejects the JWT with 401 Unauthorized because it validates using a different secret (the hardcoded fallback)

1.3 WHEN `ensurePortfolioWithHoldings()` calls `GET /api/portfolio` with a synthetic JWT signed by the CI secret THEN the API Gateway returns 401 and the test helper throws an error, failing golden-path tests before they can exercise any portfolio functionality

1.4 WHEN the browser (Playwright/Chromium) receives a 401 response from the API Gateway AND the response lacks `Access-Control-Allow-Origin` headers (because `SecurityConfig.java` has no CORS configuration) THEN the browser reports the failure as a CORS error instead of a 401, masking the real authentication failure in test diagnostics

1.5 WHEN the dashboard page fetches `/api/portfolio/summary` through the browser AND the request fails due to the JWT secret mismatch THEN the total-value display remains at `$0.00` because no portfolio data is ever loaded

### Expected Behavior (Correct)

2.1 WHEN the CI workflow starts the Docker Compose stack AND `AUTH_JWT_SECRET` is set as a job-level env var THEN the `api-gateway` container SHALL receive the same `AUTH_JWT_SECRET` value via its environment block in `docker-compose.yml`

2.2 WHEN the E2E test helper mints an HS256 JWT using `process.env.AUTH_JWT_SECRET` AND sends it to the API Gateway running in Docker THEN the gateway SHALL validate the JWT successfully because both sides use the same secret, and the request SHALL be authorized

2.3 WHEN `ensurePortfolioWithHoldings()` calls `GET /api/portfolio` with a synthetic JWT THEN the API Gateway SHALL return 200 with the user's portfolio data, allowing golden-path tests to proceed with portfolio creation and verification

2.4 WHEN the API Gateway returns any HTTP response to a cross-origin request from the frontend origin THEN the response SHALL include appropriate CORS headers (`Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`) so that browser-based requests are not blocked by CORS policy and error diagnostics accurately reflect the actual HTTP status

2.5 WHEN the dashboard page fetches `/api/portfolio/summary` through an authenticated request AND the JWT is valid THEN the total-value display SHALL show the computed portfolio value (not `$0.00`)

### Unchanged Behavior (Regression Prevention)

3.1 WHEN running locally outside Docker (e.g. `./gradlew :api-gateway:bootRun`) AND `AUTH_JWT_SECRET` is not set in the environment THEN the gateway SHALL CONTINUE TO fall back to the default secret `local-dev-secret-change-me-min-32-chars` from `application-local.yml`, preserving the existing local development experience

3.2 WHEN the E2E test helper runs locally outside CI AND `AUTH_JWT_SECRET` is not set in the environment THEN the helper SHALL CONTINUE TO fall back to the same default secret `local-dev-secret-change-me-min-32-chars`, ensuring local E2E tests pass without requiring explicit secret configuration

3.3 WHEN unauthenticated requests are made to `/api/**` endpoints THEN the API Gateway SHALL CONTINUE TO reject them with 401 Unauthorized, preserving the existing security posture

3.4 WHEN requests are made to `/actuator/**` endpoints THEN the API Gateway SHALL CONTINUE TO permit them without authentication, preserving health check and monitoring access

3.5 WHEN the API Gateway is deployed to AWS using the `aws` profile THEN the JWT decoder SHALL CONTINUE TO use the RS256 JWK URI configuration, unaffected by changes to the local profile's CORS or secret-passing behavior

3.6 WHEN the `JwtAuthenticationFilter` processes an authenticated request THEN it SHALL CONTINUE TO strip any caller-supplied `X-User-Id` header and inject the `sub` claim from the validated JWT, preserving the spoofing-prevention mechanism
