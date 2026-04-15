# Phase 3 Changes — 2026-04-15 v3

## E2E CI Auth Failure Bugfix — JWT Secret Forwarding & CORS Configuration

Fixes all 5 failing E2E integration tests in the `frontend-e2e-integration.yml` GitHub Actions workflow. The root cause was a JWT signing secret mismatch between the E2E test helper and the API Gateway when running inside Docker Compose in CI, compounded by missing CORS headers that masked the real 401 errors as browser CORS failures.

Prior changes: [CHANGES_PHASE3_SUMMARY_2026-04-15_v2.md](./CHANGES_PHASE3_SUMMARY_2026-04-15_v2.md)

---

## Summary

### Root Cause Analysis

The CI workflow sets `AUTH_JWT_SECRET` from GitHub Secrets as a job-level environment variable. The E2E test helper (`frontend/tests/e2e/helpers/auth.ts`) reads `process.env.AUTH_JWT_SECRET` to mint HS256 JWTs. However, `docker-compose.yml` did not pass `AUTH_JWT_SECRET` into the `api-gateway` container's environment block, so the gateway fell back to the hardcoded default `local-dev-secret-change-me-min-32-chars` from `application-local.yml`. The test minted tokens with the CI secret; the gateway validated them with the fallback default. Signatures never matched, producing 401 on every authenticated request.

A secondary issue: `SecurityConfig.java` had no `.cors()` configuration. When the browser received a 401 response lacking `Access-Control-Allow-Origin` headers, it reported the failure as a CORS error instead of a 401 — a "diagnostic shadow" that masked the real authentication failure in test output.

### Fix 1: JWT Secret Forwarding (`docker-compose.yml`)

Added `AUTH_JWT_SECRET: ${AUTH_JWT_SECRET:-local-dev-secret-change-me-min-32-chars}` to the `api-gateway` service's environment block. The `:-` syntax provides the same fallback default used in `application-local.yml`, so local dev without the env var continues to work identically. In CI, the GitHub Secrets value is now forwarded into the container.

### Fix 2: CORS Configuration (`SecurityConfig.java`)

Added `.cors(cors -> cors.configurationSource(corsConfigurationSource()))` to the `SecurityWebFilterChain` with a restrictive configuration:

- **Allowed origin**: `http://localhost:3000` only (not wildcard)
- **Allowed methods**: GET, POST, PUT, DELETE, OPTIONS
- **Allowed headers**: Authorization, Content-Type, X-Requested-With
- **Credentials**: enabled
- **Preflight cache**: 3600 seconds (1 hour)
- **Reactive stack**: Uses `org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource`

The CORS config is defined inline in `SecurityConfig` which runs under the `local` profile. In AWS, CloudFront handles CORS at the CDN layer — the gateway does not add conflicting headers in production.

### Bug Condition Exploration Tests (2 tests)

**DockerComposeSecretForwardingTest.java** (unit test):

- Parses `docker-compose.yml` using SnakeYAML
- Asserts `AUTH_JWT_SECRET` exists in the `api-gateway` service environment block
- Failed on unfixed code (key absent), passes after fix

**CorsConfigurationTest.java** (integration test, `@Tag("integration")`):

- Boots Spring context with local profile and Testcontainers Redis
- Sends OPTIONS preflight request with `Origin: http://localhost:3000` — asserts `Access-Control-Allow-Origin` header present
- Sends authenticated GET with Origin header — asserts CORS headers present
- Both failed on unfixed code (no `.cors()` config), pass after fix

### Preservation Property Tests (7 tests)

**PreservationPropertyTest.java** (integration test, `@Tag("integration")`):

- **Property 2a — Local Dev Secret Fallback**: `application-local.yml` contains `${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}` fallback expression
- **Property 2b — Unauthenticated Rejection**: Parameterized test — `/api/portfolio`, `/api/market`, `/api/insights` all return 401 without auth (3 cases)
- **Property 2c — Actuator Permit All**: `/actuator/health` returns 200 without authentication
- **Property 2d — AWS Profile Isolation**: Reflection test — `awsJwtDecoder` has `@Profile("aws")`, `localJwtDecoder` has `@Profile("local")`
- **Property 2e — X-User-Id Stripping**: Valid JWT with spoofed `X-User-Id: attacker-id` header is not rejected (filter strips spoofed header, injects real sub claim)

All 7 tests passed on unfixed code (baseline captured) and continue to pass after the fix (no regressions).

---

## Files Changed

| File                                                                   | Change                                                                |
| ---------------------------------------------------------------------- | --------------------------------------------------------------------- |
| `docker-compose.yml`                                                   | Modified — added `AUTH_JWT_SECRET` env var to api-gateway service     |
| `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java`     | Modified — added `.cors()` with restrictive `CorsConfigurationSource` |
| `api-gateway/src/test/java/.../DockerComposeSecretForwardingTest.java` | New — bug condition exploration test (unit)                           |
| `api-gateway/src/test/java/.../CorsConfigurationTest.java`             | New — bug condition exploration test (integration)                    |
| `api-gateway/src/test/java/.../PreservationPropertyTest.java`          | New — 7 preservation property tests (integration)                     |
| `.kiro/specs/e2e-ci-auth-failure/bugfix.md`                            | New — bugfix requirements document                                    |
| `.kiro/specs/e2e-ci-auth-failure/design.md`                            | New — bugfix design document                                          |
| `.kiro/specs/e2e-ci-auth-failure/tasks.md`                             | New — implementation task list (all complete)                         |
| `.kiro/specs/e2e-ci-auth-failure/.config.kiro`                         | New — spec config                                                     |
| `docs/changes/CHANGES_PHASE3_SUMMARY_2026-04-15_v3.md`                 | New — this changelog                                                  |

---

## Verification

- `./gradlew :api-gateway:test` → 17 tests passed (includes DockerComposeSecretForwardingTest)
- `./gradlew :api-gateway:integrationTest` → 96 tests passed (includes CorsConfigurationTest, PreservationPropertyTest, and all pre-existing tests)
- Total: 113 tests, 0 failures, 0 skipped
