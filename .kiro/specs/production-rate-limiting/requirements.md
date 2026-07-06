# Requirements Document

## Introduction

The `api-gateway` module already contains a working, tested, Redis-backed distributed rate limiter (Spring Cloud Gateway's built-in `RedisRateLimiter`, token-bucket algorithm). Today this limiter is wired only under the `local` Spring profile via `application-local.yml`. No deployed environment (`prod,azure`, `prod,aws`, or their AI-provider variants) enforces any request rate limit, even though production is already connected to the same Upstash Redis instance the limiter needs.

This gap leaves every deployed route — most critically the Azure OpenAI–backed AI Insight endpoints (`/api/insights/**`, `/api/chat/**`) — exposed to unbounded request volume from a single client, with direct metered-cost exposure on `gpt-4o-mini`.

This feature extends production-grade, profile-aware rate limiting to deployed environments by activating the existing Redis-backed limiter under production profiles, introducing cost-sensitive per-route limits, preserving the mandatory fail-open behavior, and adding graceful client-side handling of throttled (`429`) responses. It deliberately reuses the existing tested implementation and its architectural guardrails rather than introducing new rate-limiting infrastructure or custom Redis client code.

Implementation order: this feature MUST be implemented before the `new-user-signup-profile` spec. That spec's auth-endpoint rate limiter reuses the shared `RedisRateLimiter` wiring and the trusted-hop key resolver (`resolveTrustedHopKey` / the `app.rate-limit.trust-xff-last-hop` toggle) introduced here, and both features modify the frontend `fetchWithAuth.ts`, so this spec is a prerequisite and lands first.

## Glossary

- **API_Gateway**: The `api-gateway` Spring Cloud Gateway (WebFlux) service; the only externally reachable deployable and the enforcement point for rate limiting.
- **Rate_Limiter**: Spring Cloud Gateway's built-in `RedisRateLimiter` (token-bucket) as configured through the `RequestRateLimiter` gateway filter.
- **Key_Resolver**: The `userOrIpKeyResolver` bean in `GatewayRateLimitConfig.java` that derives a per-client bucket key from the JWT `sub` claim, else a client-address IP, else the literal `"anonymous"`. Under a Production_Profile, when no JWT `sub` claim is present, the Key_Resolver derives the IP from a trusted client-address source (the ingress-appended `X-Forwarded-For` hop or the remote socket address) rather than the client-supplied first `X-Forwarded-For` value, so that a client cannot obtain a fresh bucket by rotating spoofed `X-Forwarded-For` prefixes.
- **Rate_Limit_Key**: The value produced by the Key_Resolver that identifies a single token bucket.
- **Replenish_Rate**: The steady-state number of tokens (requests) added per second per bucket (`redis-rate-limiter.replenishRate`).
- **Burst_Capacity**: The maximum number of tokens a bucket can hold, defining the largest allowed burst (`redis-rate-limiter.burstCapacity`).
- **Requested_Tokens**: The number of tokens a single request consumes (`redis-rate-limiter.requestedTokens`).
- **Production_Profile**: A deployed Spring profile combination that includes `prod` (e.g., `prod,azure` or `prod,aws`), as opposed to the `local` profile.
- **Cost_Sensitive_Route**: A route backed by a metered AI provider — `/api/insights/**` and `/api/chat/**`.
- **Standard_Route**: A non-AI route — `/api/portfolio/**` and `/api/market/**`.
- **Standard_Limit**: The limit applied to a Standard_Route — `replenishRate` 10, `burstCapacity` 20, `requestedTokens` 1 (half of the `local` 20/40 values; comfortably fits the dashboard's parallel fetch fan-out).
- **Strict_Limit**: The limit applied to a Cost_Sensitive_Route — `replenishRate` 1, `requestedTokens` 6, `burstCapacity` 30, which yields effectively ~10 requests/minute with a burst of ~5 (per-minute granularity via the `requestedTokens` trick — generous for a human, hostile to a script).
- **Exempt_Route**: The `/api/internal/**` routes (the E2E seeder routes), which are already API-key gated.
- **Fail_Open**: The behavior in which the API_Gateway continues to start and proxy requests (logging a WARN) when Redis is unreachable, rather than rejecting traffic.
- **Redis_Backend**: The Upstash Redis instance (reached over TLS via `REDIS_URL` and `RedisSslConfig`) that stores token-bucket state.
- **Throttled_Response**: An HTTP `429 Too Many Requests` response returned when a request exceeds its bucket's available tokens.
- **Frontend_Client**: The Next.js frontend's authenticated fetch layer (`frontend/src/lib/api/fetchWithAuth.ts`).
- **Integration_Test_Suite**: The Testcontainers-backed tests following the `RateLimitingIntegrationTest` pattern, annotated `@Tag("integration")`.

## Requirements

### Requirement 1: Activate rate limiting under production profiles

**User Story:** As a platform operator, I want the existing Redis-backed rate limiter to be active in deployed environments, so that production routes are protected from unbounded request volume.

#### Acceptance Criteria

1. WHERE a Production_Profile is active, THE API_Gateway SHALL apply the Rate_Limiter to every incoming proxied request using the existing `userOrIpKeyResolver` Key_Resolver, enforcing the Standard_Limit (`replenishRate` 10, `burstCapacity` 20, `requestedTokens` 1) on a Standard_Route and the Strict_Limit (`replenishRate` 1, `requestedTokens` 6, `burstCapacity` 30) on a Cost_Sensitive_Route.
2. THE API_Gateway SHALL keep `application.yml` free of any rate-limit or Redis configuration so that no `RequestRateLimiter` reference triggers Redis autoconfiguration in environments without configured production rate limiting.
3. WHERE the `local` profile is active, THE API_Gateway SHALL continue to apply the Rate_Limiter with its existing `local` configuration values.
4. WHERE a Production_Profile is active, THE API_Gateway SHALL connect to the Redis_Backend using the existing `spring.data.redis.url` configuration and `RedisSslConfig` TLS setup.
5. IF a Production_Profile is active AND the `spring.data.redis.url` configuration is absent OR a connection to the Redis_Backend cannot be established during startup, THEN THE API_Gateway SHALL complete startup and proxy requests without enforcing rate limiting.
6. WHILE a Production_Profile is active, WHEN an incoming request would exceed the applicable Rate_Limiter threshold, THE API_Gateway SHALL reject the request with a response indicating the rate limit has been exceeded and SHALL NOT forward the request to the downstream service.
7. IF a Production_Profile is active AND the Redis_Backend becomes unreachable after startup, THEN THE API_Gateway SHALL continue to proxy incoming requests without enforcing rate limiting.

### Requirement 2: Preserve architectural guardrails

**User Story:** As an architect, I want rate limiting to reuse the existing abstractions, so that the gateway remains portable and free of vendor-specific client code.

#### Acceptance Criteria

1. THE API_Gateway SHALL implement rate limiting exclusively through Spring Cloud Gateway's built-in `RedisRateLimiter` and `RequestRateLimiter` filter, and SHALL NOT include any custom or in-memory rate-limiting implementation in the deployed artifact.
2. THE `com.wealth.gateway` package SHALL contain no source file that imports any type from the `software.amazon.awssdk.*` namespace or the `io.lettuce.*` namespace.
3. WHILE the `aws` Spring profile or the `azure` Spring profile is active, THE API_Gateway SHALL set the `management.health.redis.enabled` property to `false` so that the readiness probe does not evaluate Redis health during cold start.
4. THE API_Gateway SHALL define all production rate-limit parameters (replenish rate, burst capacity, and requested tokens per request) only within profile-specific configuration files and SHALL NOT define any of these parameters in the profile-neutral `application.yml`.
5. IF a production-active profile is loaded with one or more required rate-limit parameters absent from its profile-specific configuration file, THEN THE API_Gateway SHALL fail startup and produce an error indicating the missing rate-limit configuration.
6. THE API_Gateway SHALL treat the missing/invalid Redis URL condition of Requirement 1.5 (fail open — complete startup and proxy without rate limiting) and the missing required limit parameters condition of Requirement 2.5 (fail startup with an error) as intentionally distinct failure modes, and these two behaviors SHALL NOT be reconciled to behave the same.

### Requirement 3: Cost-sensitive per-route limits

**User Story:** As a product owner, I want AI-backed routes to have a stricter limit than standard routes, so that a single client cannot drive up Azure OpenAI cost through the insight and chat endpoints.

#### Acceptance Criteria

1. WHERE a Production_Profile is active AND a request targets a Cost_Sensitive_Route, THE API_Gateway SHALL apply the Strict_Limit (`replenishRate` 1, `requestedTokens` 6, `burstCapacity` 30).
2. WHERE a Production_Profile is active AND a request targets a Standard_Route, THE API_Gateway SHALL apply the Standard_Limit (`replenishRate` 10, `burstCapacity` 20, `requestedTokens` 1).
3. WHEN a Cost_Sensitive_Route request exceeds its bucket's available tokens, THE API_Gateway SHALL return a Throttled_Response and SHALL NOT proxy the request to the upstream service.
4. WHEN a Cost_Sensitive_Route request is within its bucket's available tokens, THE API_Gateway SHALL proxy the request to the upstream service.
5. WHEN a single Rate_Limit_Key sends requests to both a Cost_Sensitive_Route and a Standard_Route, THE API_Gateway SHALL account for those requests in separate token buckets.
6. THE API_Gateway SHALL derive each Rate_Limit_Key for both route classes using the existing Key_Resolver so that a JWT-authenticated client is limited by `sub` claim and an unauthenticated client is limited by IP.
7. WHERE a Production_Profile is active, THE API_Gateway SHALL apply the Rate_Limiter to each route via an explicit per-route `RequestRateLimiter` filter and SHALL NOT simultaneously apply a global `default-filters` `RequestRateLimiter`, so that no request consumes tokens from more than one bucket.
8. WHERE a request targets an Exempt_Route (`/api/internal/**`), THE API_Gateway SHALL NOT apply rate limiting to that request, because these routes are API-key gated and used for seeding bursts.
9. WHERE a request is unauthenticated (no JWT `sub` claim) under a Production_Profile, THE API_Gateway SHALL derive the Rate_Limit_Key from a trusted client-address source (the ingress-appended `X-Forwarded-For` hop or the remote socket address) rather than the client-supplied first `X-Forwarded-For` value, so that a client cannot obtain a fresh bucket by rotating spoofed `X-Forwarded-For` prefixes.

> Resolved: per-route limits are confirmed in scope for phase 1. The Standard_Limit (`replenishRate` 10, `burstCapacity` 20, `requestedTokens` 1) and Strict_Limit (`replenishRate` 1, `requestedTokens` 6, `burstCapacity` 30) values are set as above. The double-bucket pitfall is avoided by using explicit per-route `RequestRateLimiter` filters instead of `default-filters` in production profiles.

### Requirement 4: Fail-open behavior in production

**User Story:** As a platform operator, I want the gateway to keep serving traffic when Redis is unavailable, so that a Redis outage never causes a full production outage.

#### Acceptance Criteria

1. IF the Redis_Backend is unreachable at startup under a Production_Profile, THEN THE API_Gateway SHALL complete startup within 30 seconds and begin proxying requests without applying rate limiting.
2. IF a rate-limit check against the Redis_Backend does not complete within the configured Redis client command/connection timeout (an approximate ~1-second bound achieved via the Lettuce / `spring.data.redis.timeout` command timeout, not a hard per-request SLA) during a live request under a Production_Profile, THEN THE API_Gateway SHALL proxy the request without applying rate limiting rather than reject it.
3. IF the Redis_Backend is unreachable during a live request AND proxying the request subsequently fails, THEN THE API_Gateway SHALL return an error response containing an error indication describing the failure, and SHALL NOT apply rate limiting to that request.
4. WHILE the Redis_Backend is unreachable, THE API_Gateway SHALL log a WARN-level message describing the degraded rate-limiting state at most once per 60 seconds, regardless of whether the API_Gateway is actively serving traffic.
5. WHEN the Redis_Backend connection succeeds, THE API_Gateway SHALL log an INFO-level message confirming that the rate-limiter backend is ready.

### Requirement 5: Rate-limit response semantics

**User Story:** As an API consumer, I want throttled responses to be clearly identifiable and informative, so that clients can react appropriately.

#### Acceptance Criteria

1. WHEN a request is allowed under a Production_Profile, THE API_Gateway SHALL include the `X-RateLimit-Remaining` header set to a non-negative integer equal to the number of tokens remaining in the request's bucket, ranging from 0 to Burst_Capacity.
2. IF a request exceeds its bucket's available tokens, THEN THE API_Gateway SHALL reject the request with HTTP status `429`, SHALL NOT forward the request to any downstream service, and SHALL NOT consume a token from the bucket.
3. WHEN two distinct Rate_Limit_Key values send requests, THE API_Gateway SHALL maintain independent token buckets for each key, such that token consumption attributed to one Rate_Limit_Key does not reduce the available tokens of any other Rate_Limit_Key.
4. WHEN a single Rate_Limit_Key sends a number of requests whose count is less than or equal to its Burst_Capacity before any tokens are replenished, THE API_Gateway SHALL allow all of those requests.
5. IF a request is rejected with HTTP status `429`, THEN THE API_Gateway SHALL include a `Retry-After` header whose value is a non-negative integer indicating the number of seconds until at least one token becomes available in the request's bucket, and SHALL return a response body indicating that the rate limit was exceeded.

### Requirement 6: Frontend handling of throttled responses

**User Story:** As a user of the dashboard, I want a clear indication when I am being rate limited, so that I understand why a request did not complete and what to do next.

#### Acceptance Criteria

1. WHEN the Frontend_Client receives a Throttled_Response, THE Frontend_Client SHALL reject the pending call with an error that identifies the failure as a rate-limit (`429`) condition and that is distinguishable by the caller from the generic non-`ok` request-failure error used for other statuses.
2. WHEN the Frontend_Client receives a Throttled_Response, THE Frontend_Client SHALL surface to the user a rate-limit indication as a toast or banner that is distinguishable from the indication shown for a generic request failure, and SHALL NOT degrade silently.
3. WHEN the Frontend_Client receives a Throttled_Response, THE Frontend_Client SHALL retain the current authentication session and SHALL NOT redirect the user to `/login`.
4. WHEN the Frontend_Client receives a non-`ok` response whose status is neither `429` nor `401`, THE Frontend_Client SHALL reject the pending call via the existing generic request-failure path unchanged.
5. WHERE the Frontend_Client already handles status `401` by clearing the session and redirecting to `/login`, THE Frontend_Client SHALL preserve that `401` behavior unchanged.
6. WHEN the Frontend_Client receives a Throttled_Response for a chat or insight submission AND a `Retry-After` value is present, THE Frontend_Client SHALL disable the submit action and display a countdown for the indicated number of seconds before re-enabling the submit action.
7. THE Frontend_Client SHALL NOT automatically retry a Throttled_Response.

> Resolved: the `429` UX is a toast/banner plus a `Retry-After`-driven submit countdown on the chat/insight surface; no automatic retry; no silent degradation.

### Requirement 7: Production-profile integration test coverage

**User Story:** As a developer, I want automated tests that exercise rate limiting under a production-like profile, so that regressions in production behavior are caught in CI.

#### Acceptance Criteria

1. THE Integration_Test_Suite SHALL include a test, annotated `@Tag("integration")`, that runs under a Production_Profile with a real Redis_Backend provided via Testcontainers.
2. WHEN a single Rate_Limit_Key sends a number of requests less than or equal to the configured Burst_Capacity within the burst window under a Production_Profile, THE Integration_Test_Suite SHALL assert that every such request is allowed and that the `X-RateLimit-Remaining` header decrements from Burst_Capacity minus one down to 0 across the sequence, in a test annotated `@Tag("integration")`.
3. IF a single Rate_Limit_Key sends more requests than the configured Burst_Capacity within the burst window under a Production_Profile, THEN THE Integration_Test_Suite SHALL assert that the first request exceeding available tokens receives HTTP status `429` and is not proxied downstream, in a test annotated `@Tag("integration")`.
4. WHEN two distinct Rate_Limit_Key values each send requests under a Production_Profile, THE Integration_Test_Suite SHALL assert that exhausting the bucket of one Rate_Limit_Key leaves the other Rate_Limit_Key able to receive an allowed (non-`429`) response, in a test annotated `@Tag("integration")`.
5. WHEN the Redis_Backend is unreachable under a Production_Profile, THE Integration_Test_Suite SHALL assert, in a Fail_Open test annotated `@Tag("integration")`, that the API_Gateway proxies the request to the downstream service and returns the downstream response rather than a rate-limiter error.
6. WHEN a Cost_Sensitive_Route receives requests under a Production_Profile, THE Integration_Test_Suite SHALL assert, in a test annotated `@Tag("integration")`, that requests up to the route's configured lower threshold are allowed and that the first request exceeding that threshold receives HTTP status `429`.
7. THE Integration_Test_Suite tests SHALL be annotated `@Tag("integration")` so that they run via the `integrationTest` task rather than the `test` task.

### Requirement 8: AWS scope alignment

**User Story:** As a project maintainer, I want the rate-limiting change scoped correctly across clouds, so that effort matches the active deployment target while the standby cloud stays protected.

#### Acceptance Criteria

1. WHERE the `prod,azure` profile is active (Azure is the active production cloud), THE API_Gateway SHALL enforce production rate limiting using the Redis-backed distributed mechanism, exhibiting allow-within-burst, deny-when-exceeded with a `429` response, per-client independent buckets, and the `X-RateLimit-Remaining` header.
2. WHERE the `prod,aws` profile is active, THE API_Gateway SHALL enforce production rate limiting using the same Redis-backed mechanism as `prod,azure`, because the rate-limit configuration (limit values and per-route `RequestRateLimiter` filters) resides in the cloud-agnostic `application-prod.yml` that both cloud profiles inherit; no AWS-profile-specific rate-limit configuration is required for enforcement to be active.
3. THE API_Gateway SHALL NOT add rate-limit values to `application-aws.yml`; the `prod,aws` profile SHALL inherit the rate-limit configuration from `application-prod.yml` rather than redefining it.
4. WHERE this feature defers AWS-specific work, the deferral SHALL be limited to (a) no committed `prod,aws` integration-test coverage in this phase and (b) no platform-native throttling (Option B — AWS API Gateway usage plans) in this phase; the deferral SHALL NOT reduce or disable rate-limit enforcement on the `prod,aws` profile.
5. THE API_Gateway SHALL keep rate limiting profile- and config-driven such that a later switch to platform-native throttling via the documented `app.rate-limiter.backend` escape hatch in `application-aws.yml` requires configuration and profile changes only, with no changes to domain or business-layer code.

> Resolved: Option A chosen — the existing Redis-backed limiter is extended via cloud-agnostic `application-prod.yml`, so enforcement is active on both `prod,azure` (the active cloud per the README) and `prod,aws` (the soft-disabled standby), which stays protected from minute one because it inherits the prod configuration; Upstash Redis is reachable over TLS identically from both clouds. "AWS deferred" means only (a) no `prod,aws` integration-test commitment in this phase and (b) no platform-native throttling (Option B) yet — not the absence of enforcement on AWS. Option B (Azure APIM / Front Door / AWS API Gateway usage plans) remains a complementary future edge/DDoS layer, switchable via the config-only `app.rate-limiter.backend` escape hatch with no domain code changes.
