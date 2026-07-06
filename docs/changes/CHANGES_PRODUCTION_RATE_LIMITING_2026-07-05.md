# Changes Summary — Production Rate Limiting Enforcement

**Date:** 2026-07-05
**Spec:** `.kiro/specs/production-rate-limiting/` (requirements.md, design.md, tasks.md)
**Branch:** `feature/production-rate-limiting`
**Scope:** `api-gateway` (backend), `frontend` (UI), plus test-only additions in both.

---

## Summary

Closed the gap left after the Redis-backed rate limiting feature: the `RedisRateLimiter` /
`RequestRateLimiter` wiring worked under the `local` profile, but no `app.rate-limit.*`
configuration, per-route filters, or 429-response ergonomics existed for `prod` profiles
(`prod,azure` / `prod,aws`). This phase makes enforcement active in production, adds a
distinct "fail startup on misconfiguration" vs "fail open on Redis outage" behavior, and
gives the frontend a way to tell a 429 (rate limited) apart from a 401 (session expired) or
a generic failure.

---

## Changes by Area

### Gateway rate-limit configuration — `com.wealth.gateway.GatewayRateLimitConfig`

- Added two named, profile-gated (`@Profile("prod")`) `RedisRateLimiter` beans:
  `standardRateLimiter` (portfolio/market-data routes) and `strictRateLimiter` (AI-backed
  insights/chat routes) — both read `app.rate-limit.{standard,strict}.*` via `@Value` with
  **no default**, so a missing/blank property fails context startup loudly instead of
  silently falling back.
- `standardRateLimiter` is marked `@Primary` only to satisfy Spring Cloud Gateway's
  autoconfigured `RequestRateLimiterGatewayFilterFactory`, which requires exactly one
  autowirable `RateLimiter`; every production route explicitly selects its own limiter via
  SpEL (`#{@standardRateLimiter}` / `#{@strictRateLimiter}`), so this default is never
  actually applied to a request.
- `userOrIpKeyResolver` gained a `trust-xff-last-hop` toggle (`app.rate-limit.trust-xff-last-hop`,
  default `false`). When `true` (prod only), unauthenticated requests key off the
  right-most (ingress-appended) `X-Forwarded-For` hop instead of the client-supplied first
  entry, closing a spoof-a-fresh-bucket loophole. Local/dev behavior is unchanged.

### Route wiring — `application-prod.yml`

- Added the full production route list (previously only inherited from `application.yml`
  with no rate-limit filters): `portfolio-service`, `market-data-service`, `insight-service`,
  `insight-chat` each carry an explicit per-route `RequestRateLimiter` filter (never a global
  `default-filters` entry, so no request is ever charged against more than one bucket).
  `/api/internal/**` seeder routes carry no rate-limit filter at all (API-key-gated, exempt
  by design).
- Added `app.rate-limit.standard.*` (10 req/s, burst 20, 1 token/request) and
  `app.rate-limit.strict.*` (1 req/s, burst 30, 6 tokens/request ≈ 10 req/min sustained).
- Added `spring.data.redis.timeout` (1s) / `connect-timeout` (5s) to bound the fail-open
  latency on a stalled Redis without penalizing cold-start TLS handshakes to Upstash.
- `application-aws.yml` intentionally does not redeclare any of the above — it inherits
  unchanged from `application-prod.yml`, so rate limiting is active on `prod,aws`
  automatically (verified by a guardrail test).

### 429 response ergonomics — `com.wealth.gateway.RateLimitDenialResponseCustomizer`

- New `@Profile("prod")` `GlobalFilter` (order `HIGHEST_PRECEDENCE`, runs before the
  per-route `RequestRateLimiter`) that decorates any `429` response with a `Retry-After`
  header and a small JSON body (`{"error":"rate_limited", "retryAfterSeconds": n}`).
  The stock `RequestRateLimiterGatewayFilterFactory` short-circuits with an empty body and
  no header, so this fills that gap.
- `Retry-After` value comes from a new `retry-after-seconds` route metadata key (`1` for
  standard routes, `6` for strict routes), matching `ceil(requestedTokens / replenishRate)`
  for the limiter attached to that route.

### Degraded-state observability — `com.wealth.gateway.RedisRateLimitStateLogger`

- New `@Profile({"aws","azure"})` scheduled probe (`@Scheduled(fixedRate = 30_000)`,
  requires `@EnableScheduling` added to `ApiGatewayApplication`) that pings Redis
  independently of request traffic — the existing `InfrastructureHealthLogger` only fires
  once on `ApplicationReadyEvent`, which would never re-fire on an idle gateway.
- Logs `[INFRA-DEGRADED]` at most once per 60s while Redis is down, and a single
  `[INFRA-OK] ... recovered` on a down→up transition.

### Frontend — distinguishing 429 from 401/generic failures

- `fetchWithAuthClient` (`src/lib/api/fetchWithAuth.ts`) now throws a new `RateLimitError`
  (carrying a parsed, non-negative `retryAfterSeconds` or `null`) on `429`, instead of the
  generic `Error` used for other non-2xx statuses. Unlike `401`, a `429` does **not** clear
  the session or redirect to `/login`.
- New `useRetryAfterCountdown` hook (`src/lib/hooks/`) drives a one-second-tick countdown
  from an initial seconds value down to zero; no-ops on `null`/non-positive input.
- `ChatInterface` catches `RateLimitError` distinctly: shows a rate-limit-specific message,
  starts the countdown (falling back to a 5s default when `Retry-After` is unparseable),
  and disables the input/submit button for the countdown duration. No automatic retry is
  ever issued — the user must resubmit once re-enabled.
- `MarketSummaryGrid` renders a distinguishable rate-limited card (`data-testid=
  "market-summary-rate-limited"`) instead of the generic error card when the query error is
  a `RateLimitError`, with its own retry action.

---

## Tests Run

| Suite | Result |
|---|---|
| `./gradlew :api-gateway:test` (unit, incl. new jqwik property test, ArchUnit guardrail, config smoke tests, fail-startup unit test) | ✅ BUILD SUCCESSFUL |
| `./gradlew :api-gateway:integrationTest` (`ProductionRateLimitingIntegrationTest`, Testcontainers Redis, `@ActiveProfiles({"prod","azure"})`) | ✅ 8/8 pass |
| `./gradlew :api-gateway:jacocoRateLimitingCoverageCheck` (instruction coverage ≥80% for the 3 new/changed classes) | ✅ pass — `GatewayRateLimitConfig` 100%, `RedisRateLimitStateLogger` 100%, `RateLimitDenialResponseCustomizer` 99.2% |
| `npx vitest run` (frontend, full suite) | ✅ 189/189 pass across 23 files |
| `npx tsc --noEmit` / `eslint .` (frontend) | ✅ no errors (pre-existing unrelated warnings only) |

`ProductionRateLimitingIntegrationTest` covers: burst-then-throttle with `X-RateLimit-Remaining`
decrement (standard route), standard-vs-strict threshold divergence, single-bucket-per-request
(no double-charging), independent buckets per key and route class, `/api/internal/**` exemption
under flood, trusted-hop `X-Forwarded-For` keying (spoof-resistance), and fail-open behavior
when Redis is stopped mid-test (`@DirtiesContext` isolates the context afterward since the
container's mapped port changes on restart).

---

## Known Gaps / Follow-ups

- No committed integration-test coverage for `prod,aws` specifically (inherited-config
  behavior is verified via YAML-content guardrail tests, not a live `aws`-profile
  Testcontainers run) — acceptable per design.md's stated scope.
- Platform-native edge throttling (Azure APIM / Front Door, AWS API Gateway usage plans) as
  a complementary DDoS/edge layer remains a future option, switchable via the
  `app.rate-limiter.backend` escape hatch documented in `application-aws.yml` — no code
  changes required to adopt it later.

---

## Guardrails Respected

- Redis unreachability never blocks gateway startup or rejects traffic (fail-open verified
  by integration test); missing rate-limit parameters fail startup loudly instead
  (deliberately distinct failure modes, both covered by tests).
- No new `software.amazon.awssdk.*` / `io.lettuce.*` imports introduced in `com.wealth.gateway`
  (ArchUnit-enforced; pre-existing `RedisSslConfig` explicitly allow-listed).
- `application.yml` (profile-neutral) remains free of Redis/rate-limit config; `local`
  profile behavior is unchanged.
