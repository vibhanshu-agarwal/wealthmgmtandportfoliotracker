# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** — Valid Test JWTs Are Rejected On Unfixed Code
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the signing secret mismatch causes 401 for valid JWTs
  - **Scoped PBT Approach**: Generate random valid UUID `sub` claims, mint JWTs with `TestJwtFactory.TEST_SECRET`, send to gateway, assert status ≠ 401
  - Create a new test class `api-gateway/src/test/java/com/wealth/gateway/JwtSecretAlignmentPropertyTest.java`
  - Use `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("local")`, `@Testcontainers`, `@Tag("integration")`
  - Set up Redis via `@DynamicPropertySource` with Testcontainers (same pattern as existing tests) but do NOT add `auth.jwt.secret` — this test must run against the unfixed property resolution
  - Write a parameterized/property-style test: for a set of randomly generated UUID sub values, mint a JWT with `TestJwtFactory.mint(sub, Duration.ofHours(1))` and assert the response status is NOT 401
  - The bug condition from design: `isBugCondition(request)` holds when `signingSecret == TestJwtFactory.TEST_SECRET AND gatewaySecret != TestJwtFactory.TEST_SECRET AND jwtIsOtherwiseValid(jwt)`
  - The expected behavior assertion: `response.status != 401` for all valid test-minted JWTs
  - Run test on UNFIXED code via `./gradlew :api-gateway:integrationTest --tests "com.wealth.gateway.JwtSecretAlignmentPropertyTest"`
  - **EXPECTED OUTCOME**: Test FAILS with 401 responses — this proves the bug exists (secret mismatch causes valid JWTs to be rejected)
  - Document counterexamples: e.g., "JWT minted with TEST_SECRET for sub=<uuid> returned 401 instead of non-401 because gateway resolved auth.jwt.secret to a different value"
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** — Invalid JWTs Continue To Be Rejected
  - **IMPORTANT**: Follow observation-first methodology
  - Create a new test class `api-gateway/src/test/java/com/wealth/gateway/JwtRejectionPreservationPropertyTest.java`
  - Use `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("local")`, `@Testcontainers`, `@Tag("integration")`
  - Set up Redis via `@DynamicPropertySource` with Testcontainers and explicitly set `auth.jwt.secret` to `TestJwtFactory.TEST_SECRET` (preservation tests need a working baseline to verify rejection behavior)
  - Observe behavior on UNFIXED code: invalid JWTs already return 401 — this is the behavior to preserve
  - Write property-based tests for the following invalid JWT categories:
    - **Expired JWTs**: For random past durations (e.g., -1s to -30d), mint expired JWTs and assert status = 401
    - **Wrong signing key**: For random 32+ char strings different from `TEST_SECRET`, mint JWTs and assert status = 401
    - **Tampered signatures**: Take valid JWTs, flip random bytes in the signature segment, assert status = 401
    - **Missing Authorization header**: Send request with no Bearer token, assert status = 401
    - **Missing sub claim**: Mint JWT without `sub` claim, assert status = 401
  - These tests capture the preservation requirements from design: all inputs where `NOT isBugCondition(request)` must return 401
  - Run tests on UNFIXED code via `./gradlew :api-gateway:integrationTest --tests "com.wealth.gateway.JwtRejectionPreservationPropertyTest"`
  - **EXPECTED OUTCOME**: Tests PASS — confirms baseline rejection behavior exists and is working
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_

- [x] 3. Fix JWT signing secret mismatch in integration tests
  - [x] 3.1 Add `auth.jwt.secret` to `@DynamicPropertySource` in `JwtFilterIntegrationTest`
    - Open `api-gateway/src/test/java/com/wealth/gateway/JwtFilterIntegrationTest.java`
    - In the existing `redisProperties(DynamicPropertyRegistry registry)` method, add: `registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);`
    - This ensures `JwtDecoderConfig.localJwtDecoder()` receives the same secret that `TestJwtFactory` uses to sign JWTs, regardless of env vars or property source ordering
    - _Bug_Condition: isBugCondition(request) where signingSecret == TestJwtFactory.TEST_SECRET AND gatewaySecret != TestJwtFactory.TEST_SECRET_
    - _Expected_Behavior: For all valid test-minted JWTs, gateway accepts them (status ≠ 401) because localJwtDecoder now uses TestJwtFactory.TEST_SECRET_
    - _Preservation: Invalid JWTs (expired, tampered, wrong key, missing sub, missing header) must still return 401; main application-local.yml unchanged; aws profile unaffected_
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7_

  - [x] 3.2 Add `auth.jwt.secret` to `@DynamicPropertySource` in `RateLimitingIntegrationTest`
    - Open `api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java`
    - In the existing `redisProperties(DynamicPropertyRegistry registry)` method, add: `registry.add("auth.jwt.secret", () -> TestJwtFactory.TEST_SECRET);`
    - This ensures rate-limiting tests can pass valid JWTs through the security layer to reach the rate limiter
    - _Bug_Condition: isBugCondition(request) where valid JWTs are rejected before reaching rate limiter_
    - _Expected_Behavior: Valid JWTs pass security layer, requests reach rate limiter producing 429 and X-RateLimit-Remaining headers_
    - _Preservation: Rate limiting behavior (429 responses, independent buckets, headers) works correctly once JWTs are accepted_
    - _Requirements: 1.4, 2.4, 3.6, 3.7_

  - [x] 3.3 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** — Valid Test JWTs Are Accepted
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior: valid JWTs minted with `TestJwtFactory.TEST_SECRET` should return status ≠ 401
    - Run: `./gradlew :api-gateway:integrationTest --tests "com.wealth.gateway.JwtSecretAlignmentPropertyTest"`
    - **EXPECTED OUTCOME**: Test PASSES — confirms the fix resolves the signing secret mismatch
    - _Requirements: 2.1, 2.2_

  - [x] 3.4 Verify preservation tests still pass
    - **Property 2: Preservation** — Invalid JWTs Continue To Be Rejected
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run: `./gradlew :api-gateway:integrationTest --tests "com.wealth.gateway.JwtRejectionPreservationPropertyTest"`
    - **EXPECTED OUTCOME**: Tests PASS — confirms no regressions in JWT rejection behavior
    - Confirm all preservation tests still pass after fix (no regressions)

- [x] 4. Checkpoint — Ensure all tests pass
  - Run the full integration test suite: `./gradlew :api-gateway:integrationTest`
  - Verify all 27 existing tests pass (10 previously-failing + 17 previously-passing)
  - Verify the new property tests from tasks 1 and 2 also pass
  - Confirm no files outside `src/test/java` were modified (preservation of production code and main config)
  - Ensure all tests pass, ask the user if questions arise
