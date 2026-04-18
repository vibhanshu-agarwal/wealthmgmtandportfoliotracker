# API Gateway Integration Test Failures — Bugfix Design

## Overview

Ten out of 27 api-gateway integration tests fail because the JWT signing secret used by `TestJwtFactory` does not match the secret resolved by `JwtDecoderConfig.localJwtDecoder()` at runtime. The fix aligns the two secrets by having each integration test class explicitly set `auth.jwt.secret` via `@DynamicPropertySource` to the value of `TestJwtFactory.TEST_SECRET`. This guarantees the gateway's `NimbusReactiveJwtDecoder` accepts test-minted tokens regardless of environment variables, CI configuration, or Spring Boot property source ordering.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the bug — a valid JWT minted with `TestJwtFactory.TEST_SECRET` is sent to the gateway, but the gateway's `localJwtDecoder` resolves a different HMAC secret, causing signature verification to fail
- **Property (P)**: The desired behavior — JWTs minted with `TestJwtFactory.TEST_SECRET` are accepted by the gateway (status ≠ 401) when the gateway is configured with the same secret
- **Preservation**: Existing rejection behavior for invalid JWTs (expired, tampered, wrong key, missing sub, missing header) and local development `bootRun` behavior must remain unchanged
- **`TestJwtFactory`**: Test utility in `src/test/java` that mints HMAC-SHA256 JWTs using `TEST_SECRET` ("test-secret-for-integration-tests-min-32-chars")
- **`JwtDecoderConfig.localJwtDecoder()`**: Spring `@Bean` activated under the `local` profile that creates a `NimbusReactiveJwtDecoder` from the `auth.jwt.secret` property
- **Property source ordering**: Spring Boot loads `application.yml` first, then `application-{profile}.yml` overrides. Test resources on the classpath can shadow main resources, but `${ENV_VAR:default}` placeholders in the main config can resolve to unexpected values when the env var is set (e.g., in CI)

## Bug Details

### Bug Condition

The bug manifests when an integration test mints a JWT with `TestJwtFactory.TEST_SECRET` and sends it to the gateway running under the `local` profile. The `JwtDecoderConfig.localJwtDecoder()` bean resolves `auth.jwt.secret` via Spring's property resolution chain, which may not yield the same value as `TEST_SECRET` due to:

1. The main `application-local.yml` uses `${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}` — if `AUTH_JWT_SECRET` is set in CI, it overrides the test config's literal value
2. Spring Boot property source ordering may not reliably place `src/test/resources/application-local.yml` above `src/main/resources/application-local.yml` in all build tool configurations
3. The base `application.yml` sets `auth.jwt.secret: ${AUTH_JWT_SECRET:}` which participates in the resolution chain

**Formal Specification:**

```
FUNCTION isBugCondition(request)
  INPUT: request of type HTTP request with Authorization header
  OUTPUT: boolean

  jwt := extractBearerToken(request.headers["Authorization"])
  IF jwt IS NULL THEN RETURN false

  signingSecret := secretUsedToSign(jwt)
  gatewaySecret := springPropertyResolve("auth.jwt.secret")

  RETURN signingSecret == TestJwtFactory.TEST_SECRET
         AND gatewaySecret != TestJwtFactory.TEST_SECRET
         AND jwtIsOtherwiseValid(jwt)  // not expired, has sub claim, not tampered
END FUNCTION
```

### Examples

- **Example 1**: `validJwtIsNotRejected()` — mints JWT with `TEST_SECRET`, gateway resolves `auth.jwt.secret` to `"local-dev-secret-change-me-min-32-chars"` → HMAC mismatch → 401 (expected: non-401)
- **Example 2**: `validJwtWithVariousSubsIsNotRejected("550e8400-...")` — parameterized test, same secret mismatch → 401 for all sub values (expected: non-401)
- **Example 3**: `spoofedHeaderIsOverriddenByJwtSub("00000000-...", "spoofed-user")` — valid JWT rejected before `JwtAuthenticationFilter` can strip the spoofed header → 401 (expected: non-401 with spoofed header replaced)
- **Example 4**: `requestsExceedingBurstAreThrottled()` — valid JWT rejected at security layer, request never reaches rate limiter → no 429 produced (expected: at least one 429)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- Requests with no `Authorization` header must continue to return 401
- Requests with expired JWTs must continue to return 401
- Requests with tampered JWT signatures must continue to return 401
- Requests with JWTs signed by a wrong/unknown secret must continue to return 401
- Requests with JWTs missing the `sub` claim must continue to return 401
- Local development via `bootRun` must continue to use `auth.jwt.secret` from the main `application-local.yml` with `AUTH_JWT_SECRET` env var support
- The `aws` profile RS256 JWK URI decoder must remain completely unaffected
- Rate limiting behavior (429 responses, `X-RateLimit-Remaining` headers, independent buckets) must work correctly once valid JWTs are accepted

**Scope:**
All inputs that do NOT involve a valid JWT minted with `TestJwtFactory.TEST_SECRET` should be completely unaffected by this fix. This includes:

- Invalid JWTs (expired, tampered, wrong key, missing claims)
- Requests without JWTs
- Non-test runtime contexts (local `bootRun`, AWS deployment)
- The `aws` profile decoder configuration

## Hypothesized Root Cause

Based on the bug description and code analysis, the root cause is a **property resolution mismatch**:

1. **`${AUTH_JWT_SECRET:...}` placeholder in main config**: The main `application-local.yml` sets `auth.jwt.secret: ${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}`. If the `AUTH_JWT_SECRET` environment variable is set in CI, it overrides the test `application-local.yml`'s literal value `"test-secret-for-integration-tests-min-32-chars"`. Spring resolves `${...}` placeholders after merging property sources, so an env var always wins over a literal YAML value in a shadowed file.

2. **Default value mismatch when env var is unset**: Even when `AUTH_JWT_SECRET` is not set, the main config's default `"local-dev-secret-change-me-min-32-chars"` differs from `TestJwtFactory.TEST_SECRET` (`"test-secret-for-integration-tests-min-32-chars"`). If the test `application-local.yml` does not fully shadow the main one (due to classpath ordering), the wrong default is used.

3. **No explicit test-time property override**: Neither `JwtFilterIntegrationTest` nor `RateLimitingIntegrationTest` uses `@DynamicPropertySource` or `@TestPropertySource` to explicitly set `auth.jwt.secret`. They rely entirely on the test `application-local.yml` shadowing the main one, which is fragile.

**Most likely scenario**: The `AUTH_JWT_SECRET` env var is set in CI (or the test resource shadowing is unreliable), causing `JwtDecoderConfig.localJwtDecoder()` to receive a secret that differs from `TestJwtFactory.TEST_SECRET`. The `NimbusReactiveJwtDecoder` then rejects all test-minted JWTs with an HMAC signature verification failure, returning 401.

## Correctness Properties

Property 1: Bug Condition — Valid Test JWTs Are Accepted

_For any_ HTTP request carrying a JWT minted by `TestJwtFactory` with `TEST_SECRET` where the JWT is not expired, has a valid `sub` claim, and has not been tampered with, the gateway SHALL accept the JWT (return status ≠ 401) because the `localJwtDecoder` bean uses the same signing secret as `TestJwtFactory.TEST_SECRET`.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4**

Property 2: Preservation — Invalid JWTs Continue To Be Rejected

_For any_ HTTP request carrying an invalid JWT (expired, tampered signature, wrong signing key, missing `sub` claim) or no JWT at all, the gateway SHALL continue to return HTTP 401 Unauthorized, preserving the existing security behavior unchanged by the fix.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**

Property 3: Preservation — Local Development Configuration Unchanged

_For any_ non-test execution of the gateway under the `local` profile (e.g., `bootRun`), the `auth.jwt.secret` property SHALL continue to resolve from the main `application-local.yml` with `AUTH_JWT_SECRET` env var support, and the `aws` profile SHALL continue to use the RS256 JWK URI decoder.

**Validates: Requirements 3.6, 3.7**

## Fix Implementation

### Changes Required

Assuming our root cause analysis is correct:

**File**: `api-gateway/src/test/java/com/wealth/gateway/JwtFilterIntegrationTest.java`

**Change**: Add `auth.jwt.secret` to the existing `@DynamicPropertySource` method so the test explicitly sets the JWT secret to `TestJwtFactory.TEST_SECRET`, bypassing any property source ordering or env var interference.

**Specific Changes**:

1. **Add JWT secret to `@DynamicPropertySource`**: In the existing `redisProperties` method, add `registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);` so the secret is set programmatically at test startup, overriding all other property sources including environment variables.

---

**File**: `api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java`

**Change**: Same as above — add `auth.jwt.secret` to the existing `@DynamicPropertySource` method.

**Specific Changes**:

1. **Add JWT secret to `@DynamicPropertySource`**: In the existing `redisProperties` method, add `registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);` to ensure the rate-limiting tests also use the correct JWT secret.

---

**Files NOT changed** (preservation):

- `api-gateway/src/main/resources/application-local.yml` — the main config's `${AUTH_JWT_SECRET:local-dev-secret-change-me-min-32-chars}` default is preserved for local `bootRun` development (requirement 3.6)
- `api-gateway/src/main/resources/application.yml` — base config unchanged
- `api-gateway/src/main/java/com/wealth/gateway/JwtDecoderConfig.java` — production bean unchanged
- `api-gateway/src/test/java/com/wealth/gateway/TestJwtFactory.java` — test utility unchanged
- `api-gateway/src/test/resources/application-local.yml` — test config unchanged (still useful as documentation, but no longer the sole mechanism for secret alignment)

### Why `@DynamicPropertySource` over alternatives

| Alternative                                                        | Why not                                                                                                                                                                                  |
| ------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Change `TestJwtFactory.TEST_SECRET` to match main config default   | Couples test secret to a specific main config value; breaks if main default changes                                                                                                      |
| Change main `application-local.yml` default to match `TEST_SECRET` | Violates requirement 3.6; changes local dev behavior                                                                                                                                     |
| Use `@TestPropertySource` annotation                               | Works but is static; `@DynamicPropertySource` is already used in both classes for Redis, so adding the JWT secret there is consistent and keeps all test property overrides in one place |
| Remove `${AUTH_JWT_SECRET:...}` placeholder from main config       | Breaks env var support for local development                                                                                                                                             |

`@DynamicPropertySource` is the idiomatic Spring Boot Test approach: it has the highest precedence in the property source hierarchy, overriding environment variables, system properties, and all YAML files. This makes the fix robust against any CI environment configuration.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, confirm the bug exists on unfixed code by observing 401 responses for valid JWTs, then verify the fix resolves all 10 failures while preserving rejection behavior for invalid JWTs.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix. Confirm or refute the root cause analysis. If we refute, we will need to re-hypothesize.

**Test Plan**: Run the existing integration test suite (`./gradlew :api-gateway:integrationTest`) on the unfixed code and observe which tests fail with 401. Cross-reference with the test names to confirm all failures are in tests that expect valid JWTs to be accepted.

**Test Cases**:

1. **`validJwtIsNotRejected`**: Sends a valid seed-user JWT — expects non-401, gets 401 (will fail on unfixed code)
2. **`validJwtWithVariousSubsIsNotRejected` (4 parameterized cases)**: Sends valid JWTs with different sub claims — expects non-401, gets 401 (will fail on unfixed code)
3. **`spoofedHeaderIsOverriddenByJwtSub` (3 parameterized cases)**: Sends valid JWTs with spoofed X-User-Id — expects non-401, gets 401 (will fail on unfixed code)
4. **`requestsExceedingBurstAreThrottled`**: Sends valid JWTs expecting rate limiting — expects 429, gets 401 (will fail on unfixed code)
5. **`rateLimitHeadersPresent`**: Sends valid JWT expecting rate-limit headers — expects non-429 with headers, gets 401 (will fail on unfixed code)

**Expected Counterexamples**:

- All 10 tests return HTTP 401 instead of the expected non-401 or 429 status
- Root cause confirmed: `auth.jwt.secret` resolves to a value different from `TestJwtFactory.TEST_SECRET`

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds (valid JWTs minted with `TEST_SECRET`), the fixed gateway accepts them.

**Pseudocode:**

```
FOR ALL request WHERE isBugCondition(request) DO
  response := gateway_fixed(request)
  ASSERT response.status != 401
  IF request HAS spoofedXUserId THEN
    ASSERT forwardedXUserId == jwt.sub  // spoofed header replaced
  END IF
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold (invalid JWTs, no JWT, non-test runtime), the fixed gateway produces the same result as the original.

**Pseudocode:**

```
FOR ALL request WHERE NOT isBugCondition(request) DO
  ASSERT gateway_original(request) = gateway_fixed(request)
END FOR
```

**Testing Approach**: The existing test suite already contains comprehensive preservation tests (tests that assert 401 for invalid inputs). These tests pass on the unfixed code and must continue to pass after the fix. No new preservation tests are needed — the existing suite covers requirements 3.1–3.5.

**Test Plan**: Run the full integration test suite after applying the fix. Verify that:

- All 10 previously-failing tests now pass (fix checking)
- All 17 previously-passing tests continue to pass (preservation checking)

**Test Cases**:

1. **`missingAuthorizationHeaderReturns401`**: Verify no-JWT requests still return 401 after fix
2. **`expiredJwtReturns401` + parameterized variants**: Verify expired JWTs still return 401 after fix
3. **`tamperedSignatureReturns401` + parameterized variants**: Verify tampered JWTs still return 401 after fix
4. **`jwtSignedWithWrongKeyReturns401` (3 parameterized cases)**: Verify wrong-key JWTs still return 401 after fix
5. **`jwtWithoutSubClaimReturns401`**: Verify missing-sub JWTs still return 401 after fix
6. **`spoofedXUserIdHeaderWithNoJwtReturns401`**: Verify spoofed header without JWT still returns 401 after fix

### Unit Tests

- No new unit tests required — the bug is a configuration issue, not a logic defect
- Existing unit tests for `GatewayRateLimitConfig.resolveKey()` are unaffected

### Property-Based Tests

- Property 1 (Bug Condition): Generate random valid UUID sub claims, mint JWTs with `TEST_SECRET`, send to gateway, assert status ≠ 401
- Property 2 (Preservation): Generate random invalid JWT variants (expired with random past offsets, tampered at random signature positions, signed with random wrong keys), send to gateway, assert status = 401

### Integration Tests

- Run `./gradlew :api-gateway:integrationTest` — all 27 tests must pass (10 previously-failing + 17 previously-passing)
- Verify rate-limiting tests produce 429 responses and `X-RateLimit-Remaining` headers (downstream consequence of JWT fix)
- Verify `contextLoadsWithRedis` continues to pass (application context loads correctly with the property override)
