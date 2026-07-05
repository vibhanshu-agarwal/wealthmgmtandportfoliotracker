# Implementation Plan: Production Rate Limiting

## Overview

This plan activates the api-gateway's existing Redis-backed `RedisRateLimiter` under production
profiles (`prod,azure` now; `prod,aws` config-only later), introduces cost-sensitive per-route
limits via two named limiter beans, hardens the unauthenticated key against `X-Forwarded-For`
spoofing, preserves fail-open behavior, adds recurring degraded-state logging, and adds graceful
client-side `429` handling in the Next.js frontend.

The work is sequenced so backend limiter wiring and profile configuration land before the
production integration tests that exercise them, and the frontend `RateLimitError` type lands
before the toast/banner and countdown UI that consume it. Each task cites the requirement numbers
and/or design correctness properties it implements.

Implementation language: **Java 21** (backend) and **TypeScript** (frontend), matching the design
document — no language selection was needed.

## Tasks

- [ ] 1. Wire named limiter beans and production-aware key resolver
  - [ ] 1.1 Add two named `RedisRateLimiter` beans and make the key resolver production-aware in `GatewayRateLimitConfig`
    - In `api-gateway/.../com/wealth/gateway/GatewayRateLimitConfig.java`, add `standardRateLimiter` (`@Bean @Profile("prod")`, values 10/20/1) and `strictRateLimiter` (`@Bean @Profile("prod")`, values 1/30/6), each reading `app.rate-limit.standard.*` / `app.rate-limit.strict.*` via `@Value` with **no default** so a missing param fails startup
    - Keep the `userOrIpKeyResolver` bean profile-neutral (no `@Profile`); inject `@Value("${app.rate-limit.trust-xff-last-hop:false}") boolean trustLastHop`
    - Preserve JWT `sub` keying; add `resolveTrustedHopKey` (right-most / ingress-appended XFF hop) selected when `trustLastHop` is true, and keep existing `resolveKey` (first XFF token) for local/dev; fall back to remote address, else `"anonymous"`
    - Add no `software.amazon.awssdk.*` or `io.lettuce.*` imports
    - _Requirements: 1.1, 2.5, 3.6, 3.9_

  - [ ]* 1.2 Write jqwik property test for the key resolver
    - **Property 1: Key derivation is correct and spoof-resistant**
    - Generate arbitrary spoofed XFF prefixes plus a trusted hop; assert `resolveTrustedHopKey` returns the trusted hop regardless of prefix, falls back to remote address then `"anonymous"`; assert a present non-blank JWT `sub` wins
    - Pure unit property, no Redis; min 100 iterations
    - **Validates: Requirements 3.6, 3.9**

- [ ] 2. Define production route configuration and limit properties
  - [ ] 2.1 Redefine the production route list with per-route `RequestRateLimiter` filters in `application-prod.yml`
    - Redefine `spring.cloud.gateway.server.webflux.routes` in `api-gateway/src/main/resources/application-prod.yml` with the complete route set (replace-not-merge)
    - Attach per-route `RequestRateLimiter` referencing `#{@standardRateLimiter}` for `/api/portfolio/**` and `/api/market/**`, and `#{@strictRateLimiter}` for `/api/insights/**` and `/api/chat/**`
    - Add per-route `metadata: { retry-after-seconds: 1 }` to the standard routes (`/api/portfolio/**`, `/api/market/**`) and `metadata: { retry-after-seconds: 6 }` to the strict routes (`/api/insights/**`, `/api/chat/**`) so the denial customizer (task 2.3) can read the correct value from `route.getMetadata()`
    - Attach NO rate-limit filter to `/api/internal/**` (API-key gated exempt route); route downstream URIs via the shared `${app.routes.*-url}` placeholders
    - Remove any global `default-filters` `RequestRateLimiter` from the prod profile so no request consumes from two buckets
    - _Requirements: 3.1, 3.2, 3.7, 3.8_

  - [ ] 2.2 Add rate-limit property block, XFF toggle, and Redis timeout to `application-prod.yml`; verify neutral/aws/azure config
    - Add `app.rate-limit.standard.*` (10/20/1), `app.rate-limit.strict.*` (1/30/6), and `app.rate-limit.trust-xff-last-hop: true` to `application-prod.yml`
    - Add `spring.data.redis.timeout` (~1s) to `application-prod.yml` as the fail-open per-command timeout bound, and `spring.data.redis.connect-timeout` (larger, ~3-5s) to cover cold DNS resolution + TLS handshake on the first request(s) after a scale-to-zero cold start without loosening the steady-state command bound
    - Confirm `application.yml` stays free of any `spring.data.redis` and any `RequestRateLimiter` reference; confirm `management.health.redis.enabled: false` on `application-azure.yml` and `application-aws.yml`; add NO `app.rate-limit.*` values to `application-aws.yml`
    - _Requirements: 1.2, 2.3, 2.4, 8.3_

  - [ ] 2.3 Implement `RateLimitDenialResponseCustomizer` for the `429` `Retry-After` header and JSON body
    - **Implements Property 9: Gateway always emits a parseable non-negative `Retry-After` and a body on 429**
    - Create `api-gateway/.../com/wealth/gateway/RateLimitDenialResponseCustomizer.java` as a `GlobalFilter` ordered to run **before** `RequestRateLimiter` (higher precedence) — because on denial `RequestRateLimiter` short-circuits with `exchange.getResponse().setComplete()` and never calls `chain.filter(exchange)`, so a filter ordered after it would never run on the `429`s it must decorate — that executes on the way in and installs the response hooks below, which fire only when the limiter commits a `429` denial
    - Set `Retry-After = ceil(requestedTokens / replenishRate)` seconds (1 for standard, 6 for strict); register the header via `exchange.getResponse().beforeCommit(...)` because stock `RequestRateLimiter` denies via `setComplete()` with an empty body and headers stay mutable until commit
    - Write a JSON error body (e.g. `{"error":"rate_limited","message":"Rate limit exceeded","retryAfterSeconds":<n>}`) with `Content-Type: application/json` via a `ServerHttpResponseDecorator` that overrides `setComplete()` (and/or `writeWith`) to emit the body when the status is `429`
    - Read the per-route value from route metadata `retry-after-seconds` (`ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR` → `route.getMetadata().get("retry-after-seconds")`), with a route-class fallback map that defaults to the strict (larger, safer) value when the metadata is missing or mis-typed
    - Add no `software.amazon.awssdk.*` or `io.lettuce.*` imports
    - _Requirements: 5.5, 6.6_

- [ ] 3. Add recurring degraded-state logging for the rate-limiter backend
  - [ ] 3.1 Implement `RedisRateLimitStateLogger`
    - Create `api-gateway/.../com/wealth/gateway/RedisRateLimitStateLogger.java` under `@Profile({"aws","azure"})`
    - Schedule a short-timeout reactive `PING` (~every 30s); on failure log a WARN describing the degraded rate-limiting state throttled to at most once per 60s via a monotonic-clock gate; on down→up transition log a single recovery INFO
    - Leave the existing `InfrastructureHealthLogger` startup probe intact (do not duplicate the one-shot `[INFRA-OK]`/`[INFRA-FAIL]` behavior)
    - Add no `software.amazon.awssdk.*` or `io.lettuce.*` imports
    - _Requirements: 4.4, 4.5_

  - [ ]* 3.2 Write unit test for `RedisRateLimitStateLogger` throttling and recovery
    - Assert the WARN fires at most once per 60s while down, and a single INFO fires on down→up transition
    - _Requirements: 4.4, 4.5_

- [ ] 4. Checkpoint - backend wiring and configuration
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 5. Add the frontend rate-limit error type
  - [ ] 5.1 Add `RateLimitError` and the `429` branch to `fetchWithAuthClient`
    - In `frontend/src/lib/api/fetchWithAuth.ts`, add an exported `RateLimitError` class carrying `status = 429` and a parsed `retryAfterSeconds` (non-negative integer or `null`)
    - Insert the `429` branch **before** the generic `!response.ok` throw: parse `Retry-After`, throw `RateLimitError`, do not clear session, do not redirect, do not auto-retry
    - Preserve the existing `401` branch (clear session + redirect to `/login`) and the generic non-`ok` path for all other statuses unchanged
    - _Requirements: 6.1, 6.3, 6.4, 6.5, 6.7_

  - [ ]* 5.2 Write fast-check property test for response classification
    - **Property 7: Frontend response classification**
    - Generate arbitrary status codes; assert `401` redirects/clears, `429` throws `RateLimitError` without redirect, other non-`ok` throws the generic error, and no status triggers an automatic retry; min 100 iterations
    - **Validates: Requirements 6.1, 6.3, 6.4, 6.5, 6.7**

- [ ] 6. Surface throttling in the frontend UI
  - [ ] 6.1 Add a distinguishable rate-limit toast/banner via the TanStack Query error path
    - Add a global TanStack Query error handler (or shared error boundary) that inspects `error instanceof RateLimitError` and raises a rate-limit toast/banner visually distinct from the generic request-failure indication; never degrade silently
    - _Requirements: 6.2_

  - [ ] 6.2 Add the `Retry-After`-driven submit-disable countdown on the chat/insight surface
    - On the chat/insight submission component, read `RateLimitError.retryAfterSeconds`; when present, disable the submit control and render a countdown that re-enables it at 0; when absent/unparseable, show the toast but do not leave submit permanently disabled; client-side only, no auto-retry
    - _Requirements: 6.6, 6.7_

  - [ ]* 6.3 Write fast-check property test for the countdown and no-retry behavior
    - **Property 8: Retry-After drives the submit countdown**
    - Generate `429` responses with arbitrary `Retry-After` values; assert countdown initializes to `n` and re-enables at 0, absent/unparseable shows no countdown, and no automatic retry occurs; min 100 iterations
    - **Validates: Requirements 6.6, 6.7**

  - [ ]* 6.4 Write Vitest + Testing Library + MSW UI tests
    - Assert `429` surfaces a rate-limit toast distinguishable from a generic-failure toast and does not redirect; `401` still clears session and redirects; submit disables with a countdown on `Retry-After`
    - _Requirements: 6.2, 6.3, 6.5, 6.6_

- [ ] 7. Backend integration, fail-startup, and smoke tests
  - [ ] 7.1 Write `ProductionRateLimitingIntegrationTest` (Testcontainers Redis, `@ActiveProfiles({"prod","azure"})`, `@Tag("integration")`)
    - New test in `api-gateway/src/test/...` following the `RateLimitingIntegrationTest` pattern (real Redis via `GenericContainer`, `WebTestClient`, unique JWT `sub` per test); inject small burst capacities via `@DynamicPropertySource`
    - Cover the design Testing Strategy table: `burstAllowedThenThrottledWithDecrement`, `standardVsStrictThresholds`, `singleBucketPerRequest`, `independentBucketsPerKeyAndRouteClass`, `failOpenWhenRedisDown`, `exemptRouteNeverThrottled`
    - `burstAllowedThenThrottledWithDecrement` MUST target the **standard** route: the `X-RateLimit-Remaining` decrement-by-one invariant holds only when `requestedTokens = 1` (standard), whereas the strict route drops remaining by 6 per request
    - On the `429`, additionally assert the `Retry-After` header is a non-negative integer (value `1` for the standard route) and that the JSON error body indicates the limit was exceeded (Property 9)
    - For unauthenticated-key tests, the test must append the "real" client IP as the **right-most** `X-Forwarded-For` entry itself, since `WebTestClient` carries no ingress hop — this exercises the trusted-hop path (Property 1 / Req 3.9)
    - **Properties 1, 2, 3, 4, 5, 6, 9**
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 1.5, 1.6, 1.7, 3.3, 3.4, 3.5, 3.7, 3.8, 3.9, 4.2, 5.1, 5.3, 5.4, 5.5_

  - [ ] 7.2 Write the missing-limit-param fail-startup test
    - `@Tag("integration")` test that loads the context under a prod profile and asserts an `ApplicationContext` startup failure (`BeanCreationException`) naming the missing `app.rate-limit.*` key (distinct from Redis fail-open)
    - Mechanism: **override** the `app.rate-limit.*` property to an **empty string** (via `@DynamicPropertySource` / test property) so the no-default `@Value` int parse fails at bean construction — a YAML-defined key cannot be deleted from a test context, so overriding it to empty is how the missing/unresolvable-param condition is reproduced
    - _Requirements: 2.5, 2.6_

  - [ ]* 7.3 Write configuration and architecture smoke tests
    - Assert `application.yml` has no `spring.data.redis` and no `RequestRateLimiter`; assert `management.health.redis.enabled` resolves to `false` under `azure` and `aws`; assert `application-aws.yml` has no `app.rate-limit.*` values; architecture test that no new `software.amazon.awssdk.*` / `io.lettuce.*` imports exist in `com.wealth.gateway` beyond the pre-existing `RedisSslConfig`
    - _Requirements: 1.2, 2.2, 2.3, 2.4, 8.3_

- [ ] 8. Final checkpoint - full verification
  - Ensure all unit tests (`./gradlew test`, `npm run test`) and integration tests (`./gradlew integrationTest`) pass; ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; core implementation and the Requirement 7 / Requirement 2.5 integration tests are not optional.
- Integration tests are annotated `@Tag("integration")` so they run via the `integrationTest` Gradle task, not `test`.
- Each task references specific requirements for traceability; property test tasks cite their design property number.
- The two failure modes (Redis fail-open per Req 1.5 vs missing-limit-param fail-startup per Req 2.5) are validated by separate tests and must remain distinct.
- The existing local-profile `RateLimitingIntegrationTest` is intentionally left unchanged (Req 1.3).
- Property coverage: Property 1 → tasks 1.2 (unit) and 7.1 (trusted-hop XFF assertion); Properties 2/3/4/5/6 → task 7.1; Property 7 → task 5.2; Property 8 → task 6.3; **Property 9 (gateway-side `Retry-After` + JSON body on 429) is implemented by task 2.3 and validated by the header/body assertions in the integration test 7.1** (Req 5.5, 6.6).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "5.1"] },
    { "id": 1, "tasks": ["1.2", "2.1", "5.2", "6.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "3.1", "6.2"] },
    { "id": 3, "tasks": ["3.2", "6.3", "6.4", "7.1", "7.2", "7.3"] }
  ]
}
```
