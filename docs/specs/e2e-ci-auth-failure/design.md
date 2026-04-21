# E2E CI Auth Failure Bugfix Design

## Overview

E2E integration tests fail with 401 Unauthorized in CI because the API Gateway container does not receive the `AUTH_JWT_SECRET` environment variable from the CI runner. The E2E test helper mints HS256 JWTs with the CI secret, but the gateway validates them against the hardcoded fallback default — the signatures never match. A secondary issue is the absence of CORS headers on gateway responses, which causes browsers to misreport 401s as CORS errors.

The fix has two parts: (1) pass `AUTH_JWT_SECRET` through `docker-compose.yml` into the `api-gateway` container, and (2) add a restrictive, profile-aware CORS configuration to `SecurityConfig.java` via Spring Security's `.cors()` DSL.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the bug — the `api-gateway` Docker container runs with a different JWT signing secret than the one used by the E2E test helper to mint tokens
- **Property (P)**: The desired behavior — JWTs minted by the E2E helper are accepted by the gateway, and cross-origin responses include correct CORS headers
- **Preservation**: Existing behaviors that must remain unchanged — local dev without explicit `AUTH_JWT_SECRET`, AWS profile RS256 decoding, unauthenticated request rejection, actuator access, `X-User-Id` spoofing prevention
- **`SecurityConfig`**: The Spring Security configuration class in `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java` that defines the `SecurityWebFilterChain`
- **`JwtDecoderConfig`**: The configuration class that provides profile-specific `ReactiveJwtDecoder` beans — `localJwtDecoder` (HS256, `local` profile) and `awsJwtDecoder` (RS256, `aws` profile)
- **`application-local.yml`**: The Spring profile config that sets `auth.jwt.secret` with fallback `${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}`
- **`mintJwt()`**: The E2E test helper function in `frontend/tests/e2e/helpers/auth.ts` that signs HS256 JWTs using `process.env.AUTH_JWT_SECRET` (falling back to the same hardcoded default)

## Bug Details

### Bug Condition

The bug manifests when the CI workflow starts the Docker Compose stack with `AUTH_JWT_SECRET` set as a job-level environment variable (from GitHub Secrets), but `docker-compose.yml` does not forward that variable into the `api-gateway` container's environment block. The gateway's `application-local.yml` resolves `${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}` to the fallback default because the env var is absent inside the container. Meanwhile, the E2E test helper (running on the CI host) reads the real `AUTH_JWT_SECRET` and mints JWTs with it. The signature mismatch causes every authenticated request to fail with 401.

The secondary condition is that `SecurityConfig.java` has no `.cors()` configuration, so 401 responses lack `Access-Control-Allow-Origin` headers. Browsers interpret this as a CORS error, masking the real auth failure.

**Formal Specification:**

```
FUNCTION isBugCondition(input)
  INPUT: input of type { hostEnv: Map<String,String>, containerEnv: Map<String,String>, requestOrigin: String }
  OUTPUT: boolean

  secretMismatch :=
    hostEnv.get("AUTH_JWT_SECRET") IS NOT NULL
    AND hostEnv.get("AUTH_JWT_SECRET") != ""
    AND containerEnv.get("AUTH_JWT_SECRET") IS NULL

  missingCors :=
    requestOrigin IS NOT NULL
    AND requestOrigin != ""
    AND securityConfig.cors IS NOT CONFIGURED

  RETURN secretMismatch OR missingCors
END FUNCTION
```

### Examples

- **CI secret mismatch**: CI sets `AUTH_JWT_SECRET=prod-ci-secret-abc123...`. The E2E helper mints a JWT signed with that value. The gateway container never receives it, validates against `local-dev-secret-change-me-min-32-chars`, and returns 401. **Expected**: 200. **Actual**: 401.
- **CORS masking**: Playwright's Chromium sends `GET /api/portfolio` with `Origin: http://localhost:3000`. The gateway returns 401 without `Access-Control-Allow-Origin`. The browser reports a CORS error instead of showing the 401 status. **Expected**: 401 with CORS headers (so diagnostics show the real status). **Actual**: Network error / CORS failure.
- **Local dev (no bug)**: Developer runs `docker compose up` without setting `AUTH_JWT_SECRET`. Both the gateway and the E2E helper fall back to `local-dev-secret-change-me-min-32-chars`. JWTs validate successfully. **Expected**: 200. **Actual**: 200 (no bug).
- **Direct gateway call in CI**: Test 5 in `dashboard-data.spec.ts` calls `http://127.0.0.1:8080/api/portfolio` directly (not through the browser). The 401 is returned without CORS masking, but the root cause is the same secret mismatch. **Expected**: 200. **Actual**: 401.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- Local development without explicit `AUTH_JWT_SECRET` must continue to work — the gateway and E2E helper both fall back to `local-dev-secret-change-me-min-32-chars`
- Unauthenticated requests to `/api/**` must continue to be rejected with 401
- Requests to `/actuator/**` must continue to be permitted without authentication
- The AWS profile (`aws`) must continue to use the RS256 JWK URI decoder, completely unaffected by local profile CORS or secret changes
- The `JwtAuthenticationFilter` must continue to strip caller-supplied `X-User-Id` headers and inject the `sub` claim from validated JWTs
- Mouse/keyboard interactions, page navigation, and all non-auth-related frontend behavior must remain unchanged
- The `mintToken()` server-side function in `frontend/src/lib/auth/mintToken.ts` must continue to read `AUTH_JWT_SECRET` from `process.env` with the same fallback chain

**Scope:**
All inputs that do NOT involve the Docker Compose environment variable forwarding or cross-origin browser requests should be completely unaffected by this fix. This includes:

- Direct `bootRun` execution outside Docker
- AWS Lambda deployments using the `aws` profile
- Non-browser API clients (curl, Postman, Playwright's `request` fixture for non-CORS calls)
- All downstream service routing (portfolio-service, market-data-service, insight-service)

## Hypothesized Root Cause

Based on the bug description and code analysis, the root causes are:

1. **Missing environment variable forwarding in `docker-compose.yml`** (primary): The `api-gateway` service's `environment` block does not include `AUTH_JWT_SECRET`. Docker Compose does not automatically forward host environment variables into containers — they must be explicitly declared. The gateway's `application-local.yml` uses `${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}`, which resolves to the fallback inside the container because the env var is absent.
   - The CI workflow sets `AUTH_JWT_SECRET` at the job level (line 14 of `frontend-e2e-integration.yml`)
   - The E2E helper reads `process.env.AUTH_JWT_SECRET` on the host (line 5 of `helpers/auth.ts`)
   - The container never sees the host's env var → secret mismatch → 401

2. **No CORS configuration in `SecurityConfig.java`** (secondary): The `SecurityWebFilterChain` bean configures CSRF, form login, HTTP basic, authorization rules, and OAuth2 resource server — but never calls `.cors()`. Without CORS configuration, Spring Security does not add `Access-Control-Allow-Origin` or other CORS headers to responses. When the browser sends a cross-origin request and receives a response without CORS headers, it blocks the response and reports a network/CORS error regardless of the actual HTTP status.

3. **No preflight (OPTIONS) handling**: Without `.cors()`, the gateway also does not handle CORS preflight `OPTIONS` requests. If the browser sends a preflight for requests with `Authorization` headers, the gateway would reject it (no route match or 401), preventing the actual request from ever being sent.

## Correctness Properties

Property 1: Bug Condition - JWT Secret Consistency Across Docker Compose

_For any_ CI environment where `AUTH_JWT_SECRET` is set as a host environment variable and Docker Compose starts the `api-gateway` container, the container SHALL receive the same `AUTH_JWT_SECRET` value, ensuring JWTs minted by the E2E test helper are validated successfully by the gateway (HTTP 200, not 401).

**Validates: Requirements 2.1, 2.2, 2.3**

Property 2: Bug Condition - CORS Headers on Gateway Responses

_For any_ HTTP response from the API Gateway to a request with `Origin: http://localhost:3000`, the response SHALL include `Access-Control-Allow-Origin: http://localhost:3000`, `Access-Control-Allow-Methods` (including GET, POST, PUT, DELETE, OPTIONS), and `Access-Control-Allow-Headers` (including Authorization, Content-Type), so that browser-based requests are not blocked by CORS policy.

**Validates: Requirements 2.4**

Property 3: Preservation - Local Dev Secret Fallback

_For any_ environment where `AUTH_JWT_SECRET` is NOT set (local dev without explicit config), the gateway SHALL continue to fall back to `local-dev-secret-change-me-min-32-chars` and the E2E helper SHALL continue to use the same fallback, preserving the existing local development experience where tokens validate without explicit secret configuration.

**Validates: Requirements 3.1, 3.2**

Property 4: Preservation - Security Posture Unchanged

_For any_ unauthenticated request to `/api/**`, the gateway SHALL continue to reject it with 401. For any request to `/actuator/**`, the gateway SHALL continue to permit it without authentication. The `X-User-Id` header stripping and injection behavior SHALL remain unchanged.

**Validates: Requirements 3.3, 3.4, 3.6**

Property 5: Preservation - AWS Profile Unaffected

_For any_ deployment using the `aws` Spring profile, the JWT decoder SHALL continue to use the RS256 JWK URI configuration. CORS configuration SHALL be scoped to the `local` profile only, leaving the AWS profile's security chain unmodified.

**Validates: Requirements 3.5**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `docker-compose.yml`

**Service**: `api-gateway` environment block

**Specific Changes**:

1. **Add `AUTH_JWT_SECRET` to the api-gateway environment**: Add `AUTH_JWT_SECRET: ${AUTH_JWT_SECRET:-local-dev-secret-change-me-min-32-chars}` to the `api-gateway` service's `environment` block. The `:-` syntax provides the same fallback default used in `application-local.yml`, so local dev without the env var continues to work identically.

---

**File**: `api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java`

**Function**: `springSecurityFilterChain(ServerHttpSecurity http)`

**Specific Changes**: 2. **Add `.cors()` with a `CorsConfigurationSource` bean**: Configure Spring Security's `.cors(cors -> cors.configurationSource(corsConfigurationSource()))` in the filter chain. This ensures CORS headers are added to all responses processed by the security chain, including 401 error responses.

3. **Define a restrictive `CorsConfigurationSource` bean**: Create a `CorsConfigurationSource` bean that:
   - Sets `allowedOrigins` to `http://localhost:3000` only (the frontend origin for local/CI)
   - Sets `allowedMethods` to `GET, POST, PUT, DELETE, OPTIONS`
   - Sets `allowedHeaders` to `Authorization, Content-Type, X-Requested-With`
   - Sets `allowCredentials` to `true` (needed for cookie-based auth flows)
   - Sets `maxAge` to `3600` seconds (cache preflight responses for 1 hour)
   - Applies to all paths (`/**`)

4. **Make CORS configuration profile-aware**: Annotate the `CorsConfigurationSource` bean with `@Profile("local")` so it only activates under the local profile. The AWS profile uses CloudFront as the entry point, which handles CORS at the CDN layer — the gateway should not add conflicting CORS headers in production.

---

**File**: `api-gateway/src/main/resources/application-local.yml` (optional enhancement)

**Specific Changes**: 5. **Externalize CORS allowed origins** (optional): Add a `cors.allowed-origins` property to `application-local.yml` so the allowed origin can be overridden without code changes:

```yaml
cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000}
```

The `SecurityConfig` would read this via `@Value("${cors.allowed-origins}")`. This keeps the configuration flexible for environments that use a different frontend URL, but the hardcoded `http://localhost:3000` default is sufficient for the immediate fix.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, surface counterexamples that demonstrate the bug on unfixed code, then verify the fix works correctly and preserves existing behavior.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Run the existing E2E test suite (`dashboard-data.spec.ts` and `golden-path.spec.ts`) in a simulated CI-like environment where `AUTH_JWT_SECRET` is set on the host but not forwarded to the Docker container. Observe 401 failures on all authenticated requests. Additionally, inspect gateway logs for JWT validation errors to confirm the secret mismatch hypothesis.

**Test Cases**:

1. **Secret Mismatch Test**: Set `AUTH_JWT_SECRET` to a non-default value on the host, start Docker Compose (unfixed), mint a JWT with the host secret, send it to the gateway. Expect 401. (will fail on unfixed code)
2. **CORS Header Absence Test**: Send a request with `Origin: http://localhost:3000` to the gateway, inspect response headers. Expect `Access-Control-Allow-Origin` to be absent. (will fail on unfixed code)
3. **Preflight Rejection Test**: Send an `OPTIONS` request with CORS preflight headers to `/api/portfolio`. Expect the gateway to not return proper preflight response headers. (will fail on unfixed code)
4. **Diagnostic Test 5 (Direct JWT)**: Run test 5 from `dashboard-data.spec.ts` which calls the gateway directly with a synthetic JWT. Expect 401 when secrets mismatch. (will fail on unfixed code)

**Expected Counterexamples**:

- All authenticated API calls return 401 Unauthorized
- Gateway logs show `Failed to validate the token` or similar JWT signature verification errors
- Browser-based requests show CORS errors instead of 401 status codes
- Possible causes confirmed: missing env var forwarding in docker-compose.yml, missing `.cors()` in SecurityConfig

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed function produces the expected behavior.

**Pseudocode:**

```
FOR ALL input WHERE isBugCondition(input) DO
  // Secret consistency check
  IF input.hostEnv.has("AUTH_JWT_SECRET") THEN
    containerSecret := dockerCompose.resolveEnv("api-gateway", "AUTH_JWT_SECRET")
    ASSERT containerSecret == input.hostEnv.get("AUTH_JWT_SECRET")
    jwt := mintJwt(containerSecret)
    response := gateway.send(jwt)
    ASSERT response.status == 200
  END IF

  // CORS header check
  IF input.requestOrigin IS NOT NULL THEN
    response := gateway.sendWithOrigin(input.requestOrigin)
    ASSERT response.headers.get("Access-Control-Allow-Origin") == "http://localhost:3000"
  END IF
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed function produces the same result as the original function.

**Pseudocode:**

```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT gateway_original(input) == gateway_fixed(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:

- It generates many test cases automatically across the input domain
- It catches edge cases that manual unit tests might miss
- It provides strong guarantees that behavior is unchanged for all non-buggy inputs

**Test Plan**: Observe behavior on UNFIXED code first for unauthenticated requests, actuator endpoints, and local dev secret fallback, then write property-based tests capturing that behavior.

**Test Cases**:

1. **Local Dev Fallback Preservation**: Start Docker Compose WITHOUT `AUTH_JWT_SECRET` set. Verify the gateway falls back to the default secret and JWTs minted with the default secret are accepted (200). This must work identically before and after the fix.
2. **Unauthenticated Rejection Preservation**: Send requests to `/api/portfolio` without an `Authorization` header. Verify 401 is returned both before and after the fix.
3. **Actuator Access Preservation**: Send requests to `/actuator/health`. Verify 200 is returned without authentication both before and after the fix.
4. **X-User-Id Stripping Preservation**: Send a request with a spoofed `X-User-Id` header and a valid JWT. Verify the gateway strips the spoofed header and injects the JWT's `sub` claim, both before and after the fix.

### Unit Tests

- Test that `docker-compose.yml` includes `AUTH_JWT_SECRET` in the `api-gateway` environment block (YAML parsing test)
- Test that `SecurityConfig` produces a `SecurityWebFilterChain` with CORS configured (Spring context test)
- Test that the CORS configuration only allows `http://localhost:3000` and rejects other origins
- Test that `OPTIONS` preflight requests receive proper CORS headers
- Test that the CORS bean is only active under the `local` profile

### Property-Based Tests

- Generate random `AUTH_JWT_SECRET` values, mint JWTs with them, configure the gateway decoder with the same secret, and verify all tokens validate successfully (fix checking)
- Generate random origins and verify only `http://localhost:3000` receives CORS headers while other origins are rejected (CORS restrictiveness)
- Generate random request paths and verify the authorization rules (authenticated vs. permitAll) are unchanged after the fix (preservation)

### Integration Tests

- Full E2E test with Docker Compose where `AUTH_JWT_SECRET` is explicitly set on the host — verify all 5 diagnostic tests pass
- Full E2E test with Docker Compose where `AUTH_JWT_SECRET` is NOT set — verify local dev fallback works
- Test that CORS headers appear on both successful (200) and error (401) responses from the gateway
- Test that preflight `OPTIONS` requests to `/api/**` return proper CORS headers without requiring authentication
