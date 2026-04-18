# Requirements Document

## Introduction

Replace the current partial, per-route in-memory rate limiter on `market-data-service` in the
`api-gateway` module with a fully distributed, Redis-backed rate limiter applied globally to all
routes via Spring Cloud Gateway's `default-filters`. All Redis connection details are isolated to
`application-local.yml`; `application.yml` remains profile-neutral. The implementation uses
Spring Cloud Gateway's built-in `RedisRateLimiter` exclusively — no custom Redis client code.
Testcontainers-based integration tests validate the throttling behaviour locally without any
cloud dependency.

Scope is strictly limited to the `api-gateway` module. AWS CDK scripts, infrastructure folders,
and frontend code are not modified.

---

## Glossary

- **API_Gateway**: The Spring Cloud Gateway WebFlux application running in the `api-gateway`
  module on port 8080.
- **Rate_Limiter**: Spring Cloud Gateway's built-in `RedisRateLimiter`, configured via YAML only,
  that enforces a token-bucket algorithm backed by Redis.
- **IP_Key_Resolver**: The `KeyResolver` bean (`ipKeyResolver`) defined in
  `GatewayRateLimitConfig` that derives the rate-limit key from the client's IP address.
- **Token_Bucket**: The per-IP state stored in Redis (token count + timestamp) that tracks
  remaining request allowance.
- **Burst_Capacity**: The maximum number of tokens in a Token_Bucket; controls peak throughput.
- **Replenish_Rate**: The number of tokens added to a Token_Bucket per second; controls sustained
  throughput.
- **Test_Runner**: The Gradle `integrationTest` task that executes tests annotated
  `@Tag("integration")` using Testcontainers.
- **GatewayRateLimitConfig**: The existing `@Configuration` class that provides the
  `IP_Key_Resolver` bean.

---

## Requirements

### Requirement 1: Global Rate Limiting via Default Filters

**User Story:** As a platform operator, I want rate limiting applied to every route through a
single global filter, so that no route is accidentally left unprotected and configuration is
maintained in one place.

#### Acceptance Criteria

1. THE API_Gateway SHALL apply the `RequestRateLimiter` filter globally to all routes via the
   `default-filters` block in `application.yml`.
2. WHEN the `market-data-service` route is defined, THE API_Gateway SHALL NOT include a
   per-route `RequestRateLimiter` filter on that route.
3. THE API_Gateway SHALL reference the `IP_Key_Resolver` bean via the SpEL expression
   `#{@ipKeyResolver}` in the `default-filters` `RequestRateLimiter` args.
4. WHEN the `local` Spring profile is active, THE API_Gateway SHALL apply the
   `redis-rate-limiter.replenishRate` and `redis-rate-limiter.burstCapacity` values defined in
   `application-local.yml`.

---

### Requirement 2: Profile Isolation of Redis Connection Details

**User Story:** As a platform operator, I want all Redis connection details confined to the local
Spring profile, so that the application can be deployed to AWS without any Redis bindings leaking
into the profile-neutral or cloud configuration.

#### Acceptance Criteria

1. THE API_Gateway SHALL NOT contain any `spring.data.redis.*` keys in `application.yml`,
   including environment-variable placeholder forms such as
   `${SPRING_DATA_REDIS_HOST:localhost}`.
2. THE API_Gateway SHALL define `spring.data.redis.host` and `spring.data.redis.port` exclusively
   in `application-local.yml`.
3. WHEN the `local` Spring profile is active, THE API_Gateway SHALL connect to Redis using the
   host and port values defined in `application-local.yml`.
4. WHEN a Spring profile other than `local` is active and no Redis bindings are present, THE
   API_Gateway SHALL start without attempting a Redis connection.

---

### Requirement 3: IP-Based Key Resolution

**User Story:** As a platform operator, I want each client's rate limit tracked by their IP
address, so that one client cannot exhaust the quota of another.

#### Acceptance Criteria

1. WHEN a request includes an `X-Forwarded-For` header, THE IP_Key_Resolver SHALL return the
   trimmed first IP segment before the first comma as the rate-limit key.
2. WHEN a request does not include an `X-Forwarded-For` header, THE IP_Key_Resolver SHALL return
   the remote address host string as the rate-limit key.
3. WHEN neither `X-Forwarded-For` nor a remote address is available, THE IP_Key_Resolver SHALL
   return the string `"anonymous"` as the rate-limit key.
4. THE IP_Key_Resolver SHALL always return a non-null, non-empty `Mono<String>` for any
   well-formed `ServerWebExchange`.

---

### Requirement 4: Token-Bucket Rate Limiting Behaviour

**User Story:** As a platform operator, I want requests throttled per IP using a token-bucket
algorithm, so that clients can absorb short bursts while sustained throughput is bounded.

#### Acceptance Criteria

1. WHEN a client's cumulative request count is within the configured `Burst_Capacity`, THE
   Rate_Limiter SHALL allow each request and return a non-429 HTTP response.
2. WHEN a client's rapid request count exceeds the configured `Burst_Capacity`, THE Rate_Limiter
   SHALL return HTTP 429 Too Many Requests for the excess requests.
3. WHEN requests originate from two distinct client IPs, THE Rate_Limiter SHALL maintain
   independent Token_Buckets so that exhausting one IP's bucket does not throttle the other IP.
4. WHEN a request is allowed, THE Rate_Limiter SHALL include the `X-RateLimit-Remaining` header
   in the HTTP response.

---

### Requirement 5: Error Handling and Resilience

**User Story:** As a platform operator, I want the gateway to remain operational when Redis is
unavailable, so that a Redis outage does not cause a complete service disruption.

#### Acceptance Criteria

1. WHEN Redis is unreachable at gateway startup, THE API_Gateway SHALL start successfully and
   proxy requests without enforcing rate limits (fail-open behaviour).
2. WHEN Redis becomes unreachable during a live request, THE Rate_Limiter SHALL allow the request
   to be proxied and SHALL emit a WARN-level log entry.
3. WHEN the `ipKeyResolver` bean is absent from the application context, THE API_Gateway SHALL
   fail to start and SHALL throw a `BeanCreationException`.
4. WHEN no active Spring profile supplies `replenishRate` or `burstCapacity` values, THE
   Rate_Limiter SHALL apply Spring Cloud Gateway's built-in default values rather than rejecting
   all requests.

---

### Requirement 6: Build Configuration and Test Infrastructure

**User Story:** As a developer, I want Testcontainers Redis dependencies and a dedicated
integration-test Gradle task, so that I can run rate-limiting integration tests locally without
deploying to a real Redis instance.

#### Acceptance Criteria

1. THE API_Gateway build SHALL declare `org.testcontainers:testcontainers`,
   `org.testcontainers:junit-jupiter`, and `org.testcontainers:redis` in `testImplementation`
   scope in `api-gateway/build.gradle`.
2. THE API_Gateway build SHALL declare `net.jqwik:jqwik` in `testImplementation` scope in
   `api-gateway/build.gradle`.
3. THE API_Gateway build SHALL define an `integrationTest` Gradle task that uses JUnit Platform
   and includes only tests tagged `integration`.
4. THE API_Gateway build SHALL configure the default `test` Gradle task to exclude tests tagged
   `integration`.
5. WHEN the `integrationTest` task runs, THE Test_Runner SHALL pass `-Duser.timezone=Asia/Kolkata`
   as a JVM argument.

---

### Requirement 7: Integration Test Coverage

**User Story:** As a developer, I want a JUnit 5 integration test that spins up a real Redis
container and exercises the full rate-limiting path, so that throttling behaviour is verified
before any deployment.

#### Acceptance Criteria

1. THE API_Gateway SHALL contain a `RateLimitingIntegrationTest` class annotated with
   `@Tag("integration")` and `@Testcontainers`.
2. WHEN the integration test suite runs, THE Test_Runner SHALL start a Redis container using
   `org.testcontainers:redis` and override `spring.data.redis.host` and
   `spring.data.redis.port` via `@DynamicPropertySource`.
3. WHEN the integration test context loads with the `local` Spring profile active, THE
   API_Gateway SHALL start successfully with the Testcontainers Redis instance.
4. WHEN the integration test sends requests within burst capacity, THE Rate_Limiter SHALL return
   non-429 responses for all those requests.
5. WHEN the integration test sends requests exceeding burst capacity from the same IP, THE
   Rate_Limiter SHALL return at least one HTTP 429 response.
6. WHEN the integration test sends requests from two distinct IPs where one IP has exhausted its
   bucket, THE Rate_Limiter SHALL continue to allow requests from the other IP.
7. WHEN the integration test receives an allowed response, THE Rate_Limiter SHALL include the
   `X-RateLimit-Remaining` header in that response.

---

### Requirement 8: Architecture and Multi-Cloud Guardrails

**User Story:** As a platform architect, I want the rate-limiting implementation to remain
cloud-agnostic and free of direct infrastructure-library imports, so that the backing store can
be swapped for AWS API Gateway usage plans or DynamoDB without modifying application code.

#### Acceptance Criteria

1. THE GatewayRateLimitConfig class SHALL NOT be annotated with `@Profile`.
2. THE GatewayRateLimitConfig class SHALL NOT import any class from the
   `software.amazon.awssdk.*` package hierarchy.
3. No class in the `com.wealth.gateway` package SHALL directly import any class from the
   `io.lettuce.*` package hierarchy.
4. THE API_Gateway SHALL use only Spring Cloud Gateway's built-in `RedisRateLimiter` — no custom
   Redis client code SHALL be introduced.
