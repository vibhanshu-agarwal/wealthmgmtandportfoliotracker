# Feature Spec: Production Rate-Limiting

Referenced from [`roadmap_enhancements_v3.md`](./roadmap_enhancements_v3.md), Section 4.
Priority 1 in the updated matrix.

## 1. Problem Statement

The `api-gateway` has a working, tested, Redis-backed distributed rate limiter — but it only runs
under the `local` Spring profile. No deployed environment (`prod,azure`, `prod,azure,azure-ai`,
`prod,aws`, `prod,aws,bedrock`) currently enforces any request rate limit, even though production
is already connected to the Redis instance (Upstash) that the limiter needs. This exposes the
Azure OpenAI–backed AI Insight endpoints (and every other route) to unbounded request volume from
any single client, with direct cost exposure since Azure OpenAI (`gpt-4o-mini`) is metered per call.

This spec documents the exact current implementation, why production was deliberately left
unprotected (it's a documented trade-off, not an oversight), the two directions already identified
in `ROADMAP.md` for closing the gap, and the open questions that need a decision before
implementation can start.

## 2. Current State (verified against source)

### 2.1 What exists and works today

- **`api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java`** defines
  `userOrIpKeyResolver`, a `KeyResolver` bean that:
  1. Reads `exchange.getPrincipal()` (populated by Spring Security *before* any `GatewayFilter`
     runs, avoiding a filter-ordering race).
  2. If the principal is a `JwtAuthenticationToken`, uses the JWT's `sub` claim as the rate-limit
     key.
  3. Otherwise falls back to the client IP: first IP in `X-Forwarded-For` (trimmed, before the
     first comma), else the remote socket address, else the literal string `"anonymous"`.
- **Spring Cloud Gateway's built-in `RedisRateLimiter`** (token-bucket algorithm) is used exclusively
  — there is no custom Redis client code in `com.wealth.gateway` (this is an explicit architecture
  guardrail — see `docs/specs/redis-rate-limiting/requirements.md` Requirement 8: no
  `software.amazon.awssdk.*` or `io.lettuce.*` imports in this package, and `GatewayRateLimitConfig`
  carries no `@Profile` annotation).
- **`api-gateway/src/main/resources/application-local.yml`** wires the limiter globally via
  `spring.cloud.gateway.server.webflux.default-filters`:
  ```yaml
  default-filters:
    - name: RequestRateLimiter
      args:
        key-resolver: "#{@userOrIpKeyResolver}"
        redis-rate-limiter.replenishRate: 20
        redis-rate-limiter.burstCapacity: 40
        redis-rate-limiter.requestedTokens: 1
  ```
  This applies to **every route** (portfolio, market-data, insights/chat) — there is no per-route
  override today.
- **`api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java`** proves, via
  a real Testcontainers Redis instance under the `local` profile: requests within burst capacity
  are allowed; requests exceeding it return `429`; independent IPs/users get independent buckets;
  allowed responses carry `X-RateLimit-Remaining`.
- **Fail-open is a hard requirement**, not an assumption: `docs/specs/redis-rate-limiting/requirements.md`
  Requirement 5 states that if Redis is unreachable at startup or during a live request, the
  gateway must still start and proxy requests (logging a WARN), rather than rejecting all traffic.
  `InfrastructureHealthLogger` logs `[INFRA-OK] Redis — PONG received (rate-limiter backend ready)`
  when the connection succeeds.

### 2.2 Why production doesn't have it (deliberate, documented trade-off)

`api-gateway/src/main/resources/application.yml` (profile-neutral, loaded in every environment)
contains this comment verbatim:

> `# No default-filters here — any RequestRateLimiter reference triggers Redis autoconfiguration`
> `# on startup, which crashes deployments where no Redis is present (e.g. AWS without ElastiCache)`

`docs/specs/redis-rate-limiting/tasks.md` confirms the same constraint was the explicit design
goal: keep `application.yml` at **zero** rate-limit/Redis config, and confine the entire
`default-filters` + Redis connection block to `application-local.yml` — "the ONLY file that
contains any rate-limiting or Redis configuration." This was a correct decision at the time it was
made (it avoided crashing AWS Lambda deployments that had no guaranteed Redis reachability at
cold start), but it also means the limiter was never extended to `prod`/`azure`/`aws` profiles once
the reason for the original constraint (uncertain Redis availability) stopped applying uniformly.

### 2.3 Production Redis connectivity already exists — for a different reason

`api-gateway/src/main/resources/application-prod.yml` already configures:

```yaml
spring:
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
```

`REDIS_URL` is wired as a Terraform-managed environment variable on every Container App / Lambda
function (`infrastructure/terraform/azure/main.tf`, `infrastructure/terraform/aws/modules/compute/main.tf`),
pointing at **Upstash Redis** over TLS (`rediss://`). SSL is configured programmatically in
`RedisSslConfig.java` (a `LettuceClientConfigurationBuilderCustomizer` that reads
`REDIS_TRUSTSTORE_PATH`). So the Redis *connection* is already live in production — what's missing
is exclusively the `default-filters: RequestRateLimiter` block that would actually use it for rate
limiting.

**Operational constraint that any change must respect:** both `application-azure.yml` and
`application-aws.yml` explicitly set `management.health.redis.enabled: false`. The comment in both
files explains why: on scale-to-zero compute (Azure Container Apps / Lambda cold start), Lettuce
can attempt to resolve the Upstash hostname before the platform's DNS stack is ready, causing
`DataRedisReactiveHealthIndicator` to mark the app `DOWN` and block the readiness probe — on AWS
this previously caused ~50s of Lambda Web Adapter retry delay on cold start. This health-indicator
suppression must remain in place regardless of what rate-limiting change is made; it is unrelated
to whether the limiter itself works (the comment notes "Redis still works normally for rate
limiting — this only suppresses the health indicator").

### 2.4 Gaps not addressed by the current design

- **No per-route limits.** The key resolver produces one bucket per user/IP shared across *all*
  routes. v1's specific concern — protecting the AI Insight endpoints (`/api/insights/**`,
  `/api/chat/**`) from driving up Azure OpenAI cost — is not addressed by a single global bucket;
  a user could exhaust their budget on cheap portfolio reads and never get flagged, or conversely
  a chat-heavy user could be throttled at the same threshold as a read-only user.
- **No frontend handling for `429`.** `frontend/src/lib/api/fetchWithAuth.ts`
  (`fetchWithAuthClient`) only special-cases HTTP `401` (clears session, redirects to `/login`).
  A `429` falls through to the generic `throw new Error(\`Request failed (${response.status}) for ${path}\`)`
  path — no retry, backoff, or user-facing "you're being rate limited" messaging exists today.
- **No production-sized limits exist anywhere in the repo.** The only numbers on record
  (`replenishRate: 20`, `burstCapacity: 40`) live in `application-local.yml` and
  `api-gateway/src/test/resources/application-local.yml` (lower values there for fast tests) —
  both chosen for local development/test speed, not derived from expected production traffic or
  an Azure OpenAI cost budget.

## 3. Candidate Directions (from `ROADMAP.md`, not invented here)

`ROADMAP.md` → "Future Architectural Goals" → "Production Rate Limiting Strategy" already frames
two options under evaluation:

**Option A — Extend the existing Redis-backed limiter to cloud profiles.**
Add a `default-filters: RequestRateLimiter` block (same shape as `application-local.yml`) to
`application-prod.yml`, so it activates uniformly under `prod,azure` and `prod,aws` without
cloud-specific duplication (Redis/Upstash connectivity is already cloud-agnostic and shared by
both cloud targets). This is the smaller change: the connection, the key resolver, and the
enforcement mechanism (`RedisRateLimiter`) all already exist and are already tested; the only new
work is choosing production-appropriate `replenishRate`/`burstCapacity` values, deciding whether to
override per-route (see open question below), and extending `RateLimitingIntegrationTest`-style
coverage to run against a profile combination closer to production.

**Option B — Delegate coarse-grained throttling to a platform-native mechanism.**
AWS API Gateway usage plans on the AWS standby path, or an Azure-side equivalent (e.g. Azure API
Management or Front Door rate limiting) in front of the Container App, with the Redis-backed
limiter demoted to a fallback/secondary layer or removed. `ROADMAP.md` frames this explicitly as
making "the backing store swappable by config alone" — i.e., keeping `GatewayRateLimitConfig` and
`com.wealth.gateway` free of any specific cloud vendor's SDK (already a hard requirement per
Requirement 8 in the redis-rate-limiting spec), so the enforcement point could live outside the
application entirely.

**This spec does not pick between A and B** — that is a genuine architectural trade-off (operational
simplicity and reuse of tested code vs. offloading enforcement to managed infrastructure and
reducing gateway-level responsibility) that the codebase has not yet resolved, and picking it
without the user's input would be exactly the kind of assumption this task asked to avoid.

## 4. Open Questions Requiring a Decision (not technical unknowns — product/business input needed)

1. **Production `replenishRate` / `burstCapacity` values.** No production traffic data or an
   explicit request-volume/cost budget exists in the repo to derive these from. Needs input on
   expected concurrent users and acceptable Azure OpenAI spend per user per time window.
2. **Should `/api/insights/**` and `/api/chat/**` get a stricter, separate limit than
   `/api/portfolio/**` and `/api/market/**`?** v1 explicitly called out protecting the AI Insight
   endpoints specifically; today's single global bucket does not distinguish by cost. If yes, this
   requires either per-route `RequestRateLimiter` filters (reintroducing the per-route pattern the
   `redis-rate-limiting` spec deliberately removed in favor of `default-filters`) or a second,
   route-scoped `KeyResolver`/limiter pair.
3. **Option A vs. Option B (or both, phased)?** See Section 3.
4. **Priority of AWS.** AWS is a soft-disabled standby (`README.md`); is it in scope for this work
   now, or should effort concentrate on the Azure profile only, with AWS addressed opportunistically
   if/when it's reactivated?
5. **Frontend UX for `429`.** Does the product want a retry-with-backoff, a toast/banner, or
   silent degradation? Currently undefined.

## 5. Testing Expectations (once direction is chosen)

Whatever direction is chosen, the existing test pattern in `RateLimitingIntegrationTest` (real
Redis via Testcontainers, `@ActiveProfiles`, burst/allow/deny/independent-bucket/header assertions)
should be extended rather than replaced — the same fail-open behavior (Redis unreachable →
gateway still proxies, WARN logged) needs an explicit regression test under a production-like
profile combination (`prod,azure` or `prod,aws`), since the current integration test only runs
under `local`. Any new production limit values should also be exercised against the two
LLM-cost-sensitive routes (`/api/insights/**`, `/api/chat/**`) specifically, given the cost
motivation behind this feature in the first place.
