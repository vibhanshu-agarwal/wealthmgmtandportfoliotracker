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

---

## PR #82 — Code Review Follow-up (commit `eafd783`)

**Date:** 2026-07-06
**PR:** [#82](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/82) — merged, approved
**Commit:** `eafd783` (on top of `66d4b31` feature commit and `b88f362` flaky-test fix)

A code review of PR #82 surfaced one real resource leak plus several maintainability nits.
All six review items were resolved in a single follow-up commit before merge.

### Fixes

1. **Redis connection leak (🟠 performance/resource leak)** —
   `RedisRateLimitStateLogger.pingSucceeded()` called `getReactiveConnection()` every 30s but
   never released it — a slow, unbounded connection leak for the life of the gateway process.
   Fixed by wrapping the probe in `Mono.usingWhen(...)`, calling `ReactiveRedisConnection
   ::closeLater()` on complete, error, and cancel.
2. **Over-broad frontend retry gate (🟡 correctness)** — `QueryProvider.tsx`'s default retry
   predicate used `error.message.includes("4")`, which suppresses retries for *any* error
   whose message happens to contain the digit `4` (e.g. a legitimate `500` on a path like
   `/portfolio/4567`). Extracted a named, directly-testable `defaultQueryRetry(failureCount,
   error)` that checks `error instanceof RateLimitError` first, then a proper `/\(4\d{2}\)/`
   status-code regex for other 4xx errors. Added `QueryProvider.test.ts` (4 tests) including a
   regression guard reproducing the original bug.
3. **String-matching retry policies (🟡 maintainability)** — `useInsights.ts` and
   `usePortfolio.ts` each detected throttling via `message.includes("429")`, which breaks
   silently if wording changes. Both now check `error instanceof RateLimitError` and export
   `retryPolicy` for direct unit testing. Added `useInsights.test.ts` (new, 4 tests) and 3 new
   tests in `usePortfolio.test.ts`.
4. **Loose Retry-After parsing (🔵 minor)** — `fetchWithAuth.ts`'s `parseRetryAfterSeconds`
   treated an empty header string as a valid `0` (`Number("") === 0`) and accepted decimal
   values. Replaced with a strict `RETRY_AFTER_DELAY_SECONDS_PATTERN = /^\d+$/` check. Added 2
   tests to `fetchWithAuth.test.ts` (empty string, decimal value).
5. **Unpinned `setComplete()` assumption (🔵 minor)** — `RateLimitDenialResponseCustomizer`
   only overrides `setComplete()`, which is correct against the current
   `RequestRateLimiterGatewayFilterFactory` (denies via `setComplete()`, never `writeWith`),
   but would silently stop attaching the `Retry-After` header/JSON body if a future Spring
   Cloud Gateway version changed that. Added a class-level Javadoc comment pinning the
   assumption and noting that `ProductionRateLimitingIntegrationTest` would fail (not silently
   degrade) if the upstream behavior changes.
6. **`retry-after-seconds` metadata drift (🔵 minor)** — the `retry-after-seconds` route
   metadata in `application-prod.yml` must stay manually in sync with
   `ceil(requestedTokens / replenishRate)` per route's assigned limiter bean; nothing enforced
   this. Added `RateLimitConfigurationGuardrailTest
   .retryAfterSecondsMatchesLimiterMathForEveryRoute`, which parses the YAML with SnakeYAML and
   asserts consistency for every route.

### Verification (re-run against `eafd783`, not cached)

| Suite | Result |
|---|---|
| `./gradlew :api-gateway:test` (incl. `RedisRateLimitStateLoggerTest`, `RateLimitConfigurationGuardrailTest`) | ✅ BUILD SUCCESSFUL |
| `./gradlew :api-gateway:integrationTest` (`ProductionRateLimitingIntegrationTest`, Testcontainers) | ✅ 8/8 pass |
| `./gradlew :api-gateway:jacocoRateLimitingCoverageCheck` | ✅ pass |
| `npx vitest run` (frontend, full suite) | ✅ 202/202 pass across 25 files |
| `npx tsc --noEmit` / `eslint .` (frontend) | ✅ no errors (13 pre-existing unrelated warnings) |

Source-file and test-report timestamps were cross-checked to confirm the Gradle run genuinely
re-executed against the fixed code rather than serving stale `UP-TO-DATE` results.

### Non-blocking follow-ups tracked from review (see `docs/todos/TODOS_2026-04-07.md`)

- Fail-open removes the strict AI-route limit during a Redis outage — the exact cost exposure
  the feature protects. Candidate mitigation: billing alert or a cheap in-process fallback cap
  on cost routes.
- `resolveTrustedHopKey` assumes exactly one trusted proxy (ACA ingress) appends the XFF hop;
  revisit if a CDN/WAF is ever added in front of the gateway.
- `REDIS_URL` falling back to `localhost:6379` when unset in production silently fails open
  rather than erroring — intentional per Req 1.5, flagged as an operational watch-item.

### Outcome

PR #82 approved and merged into `main`. Qodana findings noted in review are pre-existing and
out of scope for this change.
