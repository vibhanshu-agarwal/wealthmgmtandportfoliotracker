# Implementation Plan: Redis-Backed Distributed Rate Limiting

## Overview

Migrate the `api-gateway` module from a partial, per-route in-memory rate limiter to a fully
distributed, Redis-backed `RequestRateLimiter` confined entirely to `application-local.yml`.
`application.yml` carries zero rate-limiting or Redis config to prevent Spring Boot's Redis
autoconfiguration from triggering in AWS deployments. Unit tests cover `GatewayRateLimitConfig`
using `@ParameterizedTest`/`@CsvSource` (no jqwik), and a Testcontainers integration test
validates end-to-end throttling behaviour.

Scope: `api-gateway` module only. No changes to AWS CDK scripts, `infrastructure/`, `frontend/`,
or any other service module.

## Tasks

- [ ] 1. Update `api-gateway/build.gradle` with test dependencies and Gradle tasks
  - Add `testImplementation 'org.testcontainers:testcontainers'` (version managed by Spring Boot BOM)
  - Add `testImplementation 'org.testcontainers:junit-jupiter'`
  - Add `testImplementation 'org.testcontainers:redis'`
  - Remove `net.jqwik:jqwik` — NOT added (use standard JUnit 5 parameterized tests instead)
  - Register `integrationTest` task: `useJUnitPlatform { includeTags 'integration' }`, `jvmArgs '-Duser.timezone=Asia/Kolkata'`, `shouldRunAfter test`
  - Configure `tasks.withType(Test).configureEach` to include `jvmArgs '-Duser.timezone=Asia/Kolkata'`
  - Configure the default `test` task: `useJUnitPlatform { excludeTags 'integration' }`
  - _Requirements: 6.1, 6.3, 6.4, 6.5_

- [x] 2. Restructure YAML configuration for profile isolation
  - [ ] 2.1 Update `api-gateway/src/main/resources/application.yml`
    - Remove the entire `spring.data.redis.*` block (including env-var placeholder forms)
    - Remove the `RequestRateLimiter` filter from the `market-data-service` per-route `filters` block
    - Do NOT add a `default-filters` block — leaving any `RequestRateLimiter` reference here would
      trigger Spring Boot's Redis autoconfiguration on startup in AWS, crashing the app
    - `application.yml` must contain zero rate-limiting or Redis config after this change
    - _Requirements: 2.1, 8.4_

  - [ ] 2.2 Create `api-gateway/src/main/resources/application-local.yml`
    - Add `spring.data.redis.host: localhost` and `spring.data.redis.port: 6379`
    - Add the complete `default-filters` block with `RequestRateLimiter` including `key-resolver`,
      `redis-rate-limiter.replenishRate: 20`, `redis-rate-limiter.burstCapacity: 40`,
      `redis-rate-limiter.requestedTokens: 1`
    - This is the ONLY file that contains any rate-limiting or Redis configuration
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.2, 2.3_

- [ ] 3. Verify `GatewayRateLimitConfig` meets architecture guardrails
  - Confirm no `@Profile` annotation is present on the class
  - Confirm no `software.amazon.awssdk.*` imports exist
  - Confirm no `io.lettuce.*` imports exist
  - No code changes expected — this is a read-and-verify step that produces no file modifications unless a violation is found
  - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [ ] 4. Write unit tests for `GatewayRateLimitConfig`
  - [ ] 4.1 Create `api-gateway/src/test/java/com/wealth/gateway/GatewayRateLimitConfigTest.java`
    - Load a minimal Spring context (`@ExtendWith(SpringExtension.class)` with `@Import(GatewayRateLimitConfig.class)`)
    - Test: `ipKeyResolver` bean is present and non-null in the application context
    - Use `@ParameterizedTest` + `@CsvSource` (no jqwik) for the following cases:
      - `X-Forwarded-For: "203.0.113.1, 10.0.0.1"` → resolved key `"203.0.113.1"`
      - `X-Forwarded-For: "203.0.113.1"` (single IP, no comma) → resolved key `"203.0.113.1"`
      - `X-Forwarded-For: " 10.0.0.5 , 192.168.1.1"` (leading whitespace) → resolved key `"10.0.0.5"`
      - No `X-Forwarded-For`, remote address `"10.0.0.1"` → resolved key `"10.0.0.1"`
      - Blank `X-Forwarded-For`, remote address `"10.0.0.2"` → resolved key `"10.0.0.2"`
      - No `X-Forwarded-For`, no remote address → resolved key `"anonymous"`
    - Use `MockServerWebExchange` / `MockServerHttpRequest` from `spring-test` to construct exchanges
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 5. Checkpoint — unit tests pass
  - Ensure all unit tests pass via `./gradlew :api-gateway:test`. Ask the user if any questions arise.

- [ ] 6. Write `RateLimitingIntegrationTest`
  - Create `api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java`
  - Annotate with `@Tag("integration")`, `@Testcontainers`, `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@ActiveProfiles("local")`
  - Declare a `@Container` field using `org.testcontainers.containers.GenericContainer` with image `"redis:7-alpine"` and exposed port 6379
  - Override `spring.data.redis.host` and `spring.data.redis.port` via a `@DynamicPropertySource` static method
  - Inject `WebTestClient` for reactive request assertions
  - [ ] 6.1 Implement `contextLoadsWithRedis` test
    - Assert the application context starts successfully with the Testcontainers Redis instance
    - _Requirements: 7.3_

  - [ ] 6.2 Implement `requestsWithinBurstAreAllowed` test
    - Send N requests (N ≤ burstCapacity) from the same IP in rapid succession
    - Assert all responses have a non-429 HTTP status
    - _Requirements: 4.1, 7.4_

  - [ ] 6.3 Implement `requestsExceedingBurstAreThrottled` test
    - Send `burstCapacity + 1` rapid requests from the same IP
    - Assert at least one response has HTTP status 429
    - _Requirements: 4.2, 7.5_

  - [ ] 6.4 Implement `differentIpsHaveIndependentBuckets` test
    - Exhaust IP-A's token bucket, then send a request with IP-B via `X-Forwarded-For`
    - Assert IP-B's request is not throttled (non-429)
    - _Requirements: 4.3, 7.6_

  - [ ] 6.5 Implement `rateLimitHeadersPresent` test
    - Send an allowed request and assert the response includes the `X-RateLimit-Remaining` header
    - _Requirements: 4.4, 7.7_

- [x] 7. Final checkpoint — all tests pass
  - Ensure all unit tests pass via `./gradlew :api-gateway:test`
  - Ensure integration tests pass via `./gradlew :api-gateway:integrationTest`
  - Ask the user if any questions arise.

## Notes

- `GatewayRateLimitConfig.java` requires no structural changes — task 3 is a verification-only step
- **Critical**: `application.yml` must contain zero rate-limiting or Redis config — any `RequestRateLimiter` reference there triggers Spring Boot's Redis autoconfiguration on startup, crashing AWS deployments
- The complete `default-filters` + Redis connection block lives exclusively in `application-local.yml`
- Testcontainers and Lettuce versions are managed by the Spring Boot BOM — no explicit versions needed in `build.gradle`
- `net.jqwik:jqwik` is NOT used — key-resolver cases are covered by `@ParameterizedTest` + `@CsvSource`
