# Design Document

## Overview

This feature activates the api-gateway's existing, tested, Redis-backed rate limiter under
production Spring profiles (`prod,azure` is the active cloud; `prod,aws` inherits enforcement from
`application-prod.yml` — only AWS integration-test coverage and platform-native Option B are deferred),
introduces cost-sensitive per-route limits, hardens the unauthenticated key against
`X-Forwarded-For` spoofing, preserves the mandatory fail-open behavior, and adds graceful
client-side handling of `429` responses in the Next.js frontend.

The design deliberately reuses Spring Cloud Gateway's built-in `RedisRateLimiter` (token bucket)
and the existing `userOrIpKeyResolver`, rather than introducing any new rate-limiting
infrastructure or custom Redis client code. It respects the standing architectural guardrails:

- `application.yml` stays free of any Redis / rate-limit configuration (Req 1.2, 2.4) — a bare
  `RequestRateLimiter` reference triggers Redis autoconfiguration on startup, which crashes
  environments without configured production limiting.
- The `com.wealth.gateway` package adds no `software.amazon.awssdk.*` or `io.lettuce.*` imports
  from the new code (Req 2.2 — see the note on the pre-existing `RedisSslConfig` under
  [Architectural guardrails](#architectural-guardrails-req-2)).
- `management.health.redis.enabled: false` remains set on both `azure` and `aws` (Req 2.3).

The central production behavior change is: in production profiles, drop the global
`default-filters` `RequestRateLimiter` and attach `RequestRateLimiter` **per route**, wired to one
of two named `RedisRateLimiter` beans — a `standardRateLimiter` and a `strictRateLimiter`. The
`local` profile is left exactly as it is today.

### Key design decisions

| Decision | Rationale | Requirements |
| --- | --- | --- |
| Reuse built-in `RedisRateLimiter` + `RequestRateLimiter`; no custom limiter | Portability guardrail; already tested | 2.1 |
| Per-route filters in prod, **not** `default-filters` | Avoid the double-bucket pitfall where a global + per-route filter each consume a token | 3.7 |
| Two named `RedisRateLimiter` beans (`standardRateLimiter`, `strictRateLimiter`) | Route classes need different token budgets; SCG resolves named beans via `#{@beanName}` | 1.1, 3.1, 3.2 |
| Limit values read from profile properties with **no defaults** | Missing param under a prod profile must fail startup (Req 2.5), distinct from Redis fail-open (Req 1.5) | 2.4, 2.5, 2.6 |
| Trusted-hop XFF selection for unauthenticated key in prod | The client-supplied first XFF entry is spoofable; ACA ingress appends the real client IP as the right-most hop | 3.9 |
| Fail-open via Lettuce command timeout + `RedisRateLimiter` error handling | ~1s bound is a timeout approximation, not a hard SLA | 1.5, 1.7, 4.1, 4.2 |
| Frontend `RateLimitError` branch before generic error handling | `429` must be distinguishable and must preserve the `401` path | 6.1, 6.4, 6.5 |
| Enforcement rides on cloud-agnostic `application-prod.yml`, active on both `prod,azure` and `prod,aws` | Azure is the active cloud (README); AWS is a soft-disabled standby that stays protected from minute one by inheriting prod config; Upstash Redis is reachable over TLS identically from both. Only the `prod,aws` integration-test commitment and platform-native Option B are deferred, not enforcement | 8.1, 8.2, 8.3 |

## Architecture

The enforcement point is unchanged: every external request enters through the api-gateway, which
is the only externally reachable deployable. The change lives entirely in how the gateway attaches
the rate-limit filter and derives the bucket key under production profiles.

```mermaid
flowchart TD
    client[Browser / API client]
    subgraph aca[Azure Container Apps]
        ingress[ACA ingress / Envoy\nappends real client IP as right-most X-Forwarded-For hop]
        subgraph gw[api-gateway - Spring Cloud Gateway WebFlux]
            sec[Spring Security WebFilter\npopulates principal -> JWT sub]
            kr[userOrIpKeyResolver\nsub | trusted-hop IP | anonymous]
            route{Route match}
            std[RequestRateLimiter\n#@standardRateLimiter\n10 / 20 / 1]
            strict[RequestRateLimiter\n#@strictRateLimiter\n1 / 30 / 6]
            none[No rate-limit filter]
        end
    end
    redis[(Upstash Redis\nrediss:// TLS\ntoken-bucket state)]
    portfolio[portfolio-service]
    market[market-data-service]
    insight[insight-service]

    client --> ingress --> sec --> kr --> route
    route -->|/api/portfolio/**, /api/market/**| std
    route -->|/api/insights/**, /api/chat/**| strict
    route -->|/api/internal/**| none
    std <-->|token check\nfail-open on error/timeout| redis
    strict <-->|token check\nfail-open on error/timeout| redis
    std -->|allowed| portfolio
    std -->|allowed| market
    strict -->|allowed| insight
    none --> portfolio
    std -.->|429 + Retry-After + JSON body| client
    strict -.->|429 + Retry-After + JSON body| client
```

Ordering note: Spring Security's `WebFilter` populates `exchange.getPrincipal()` before any
`GatewayFilter` (including `RequestRateLimiter`) runs, so `userOrIpKeyResolver` can read the JWT
`sub` without a filter-ordering race. This is a property of the existing implementation and is
preserved (Req 3.6).

### Route → limiter mapping (production profiles)

| Route id | Path predicate | Limiter | Values (replenish / burst / requested) | Requirement |
| --- | --- | --- | --- | --- |
| portfolio-service | `/api/portfolio/**` | `standardRateLimiter` | 10 / 20 / 1 | 3.2 |
| market-data-service | `/api/market/**` | `standardRateLimiter` | 10 / 20 / 1 | 3.2 |
| insight-service | `/api/insights/**` | `strictRateLimiter` | 1 / 30 / 6 (~10 req/min, burst ~5) | 3.1 |
| insight-chat | `/api/chat/**` | `strictRateLimiter` | 1 / 30 / 6 (~10 req/min, burst ~5) | 3.1 |
| internal-*-seed | `/api/internal/**` | none (API-key gated) | — | 3.8 |

The strict limit uses the `requestedTokens` trick: with `replenishRate: 1` and
`requestedTokens: 6`, six tokens refill every ~6 seconds, so a bucket sustains roughly one request
per six seconds (~10/min); `burstCapacity: 30` allows an initial burst of `30 / 6 = 5` requests.
This is generous for a human clicking "generate insight" and hostile to a script.

## Components and Interfaces

### 1. `GatewayRateLimitConfig` (modified) — `com.wealth.gateway`

Adds two named `RedisRateLimiter` beans and makes the key resolver production-aware. Stays free of
vendor SDK imports (only Spring Cloud Gateway + Spring types).

```java
@Configuration
public class GatewayRateLimitConfig {

    // --- Named limiters (production per-route wiring) -----------------------

    // Values injected WITHOUT defaults so that a missing property under a prod
    // profile fails startup with a clear placeholder-resolution error (Req 2.5),
    // rather than silently applying a fallback.
    @Bean
    @Profile("prod")
    RedisRateLimiter standardRateLimiter(
            @Value("${app.rate-limit.standard.replenish-rate}") int replenish,
            @Value("${app.rate-limit.standard.burst-capacity}") int burst,
            @Value("${app.rate-limit.standard.requested-tokens}") int requested) {
        return new RedisRateLimiter(replenish, burst, requested);
    }

    @Bean
    @Profile("prod")
    RedisRateLimiter strictRateLimiter(
            @Value("${app.rate-limit.strict.replenish-rate}") int replenish,
            @Value("${app.rate-limit.strict.burst-capacity}") int burst,
            @Value("${app.rate-limit.strict.requested-tokens}") int requested) {
        return new RedisRateLimiter(replenish, burst, requested);
    }

    // --- Key resolver (production-aware, no @Profile on the class) ----------

    @Bean
    KeyResolver userOrIpKeyResolver(
            @Value("${app.rate-limit.trust-xff-last-hop:false}") boolean trustLastHop) {
        return exchange ->
            exchange.getPrincipal()
                .map(principal -> {
                    if (principal instanceof JwtAuthenticationToken jwt) {
                        String sub = jwt.getToken().getClaimAsString("sub");
                        if (sub != null && !sub.isBlank()) return sub.trim();
                    }
                    return resolveClientIp(exchange, trustLastHop);
                })
                .defaultIfEmpty(resolveClientIp(exchange, trustLastHop));
    }

    private String resolveClientIp(ServerWebExchange exchange, boolean trustLastHop) {
        var xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        var remote = exchange.getRequest().getRemoteAddress();
        String remoteHost = (remote != null && remote.getAddress() != null)
                ? remote.getAddress().getHostAddress() : null;
        return trustLastHop
                ? resolveTrustedHopKey(xff, remoteHost)   // prod
                : resolveKey(xff, remoteHost);            // local/dev (unchanged)
    }

    /** Existing local/dev behavior: FIRST XFF token. Unchanged. */
    static String resolveKey(String forwardedFor, String remoteHost) { /* existing body */ }

    /**
     * Production behavior: trust the ingress-appended (right-most) XFF hop, not the
     * client-supplied prefix. Spoof-resistant (Req 3.9).
     */
    static String resolveTrustedHopKey(String forwardedFor, String remoteHost) {
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int lastComma = forwardedFor.lastIndexOf(',');
            String hop = (lastComma >= 0 ? forwardedFor.substring(lastComma + 1) : forwardedFor).trim();
            if (!hop.isBlank()) return hop;
        }
        if (remoteHost != null && !remoteHost.isBlank()) return remoteHost;
        return "anonymous";
    }
}
```

**Why property-flag, not `@Profile`, on the resolver:** the prior spec (redis-rate-limiting,
Requirement 8) requires `GatewayRateLimitConfig` to carry no `@Profile` on the key-resolver path so
the class stays profile-neutral and portable. The trusted-hop behavior is toggled by
`app.rate-limit.trust-xff-last-hop`, which defaults to `false` (preserving local/dev behavior) and
is set to `true` only in `application-prod.yml`. The two named limiter beans do carry `@Profile("prod")`
because they must not exist under `local` (which uses `default-filters`); this is bean-existence
gating, not behavioral branching inside the resolver.

**XFF trusted-hop rationale (ACA ingress):** Azure Container Apps' ingress (Envoy) appends the
observed downstream address to `X-Forwarded-For`. A request arriving as
`X-Forwarded-For: 1.2.3.4` (client-controlled) leaves ingress as
`X-Forwarded-For: 1.2.3.4, <real-client-ip>`. The right-most entry is the one the trusted ingress
added; the left-most is attacker-controllable. Selecting the right-most hop means a client cannot
mint a fresh bucket by rotating a spoofed prefix. The remote socket address is not usable as the
primary key in production because, behind ingress, the socket peer is the ingress itself (all
clients would share one bucket) — so it is only the fallback when no XFF is present. Assumption:
exactly one trusted proxy hop (ACA ingress) fronts the gateway; if a CDN/WAF (Front Door) is later
placed in front, the trusted-hop index must be revisited (documented in Error Handling).

### 2. Production route configuration

**Chosen approach: YAML per-route filters, with the route list redefined in `application-prod.yml`.**

Spring Boot does **not** merge list properties across profile documents — a `List` property
defined in a profile-specific file *replaces* the base list wholesale. This is used deliberately:
`application-prod.yml` redefines `spring.cloud.gateway.server.webflux.routes` with the complete
route set including per-route `filters:`. The base `application.yml` route list (no filters) is
used only by non-prod profiles (`local`), so the two never both apply.

**Alternative considered — a programmatic `@Profile("prod") RouteLocator` bean:** rejected as the
primary approach because Spring Cloud Gateway composes routes from *all* `RouteLocator`s
(`CompositeRouteLocator`). A programmatic locator would coexist with the YAML
`RouteDefinitionRouteLocator`, producing duplicate route ids / ambiguous matches under prod unless
the YAML routes were additionally suppressed. The YAML-redefine approach has clean
replace-not-merge semantics, matches the team's profile-specific-YAML convention, and keeps the
route→limiter mapping declarative and reviewable. The one cost — the route path predicates are
written twice (neutral vs prod) — is mitigated because both reference the same
`${app.routes.*-url}` placeholders, so downstream URIs are single-sourced; only the path predicates
and filter wiring are repeated.

### 3. `RedisRateLimitStateLogger` (new) — `com.wealth.gateway`

A scheduled degraded-state monitor under `@Profile({"aws","azure"})` that satisfies the recurring
WARN/INFO logging requirements independently of request traffic (Req 4.4 says "regardless of
whether the API_Gateway is actively serving traffic", which rules out a purely request-driven
logger).

- Periodically (e.g. every 30s) issues a reactive `PING` with a short timeout.
- On failure: logs a WARN describing the degraded rate-limiting state, throttled to **at most once
  per 60 seconds** via a monotonic-clock gate (Req 4.4).
- On recovery (transition down→up): logs a single INFO `[INFRA-OK]`-style line confirming the
  rate-limiter backend is ready (Req 4.5).

The existing `InfrastructureHealthLogger` remains responsible for the one-shot startup probe
(`[INFRA-OK] Redis — PONG received (rate-limiter backend ready)` on success — Req 4.5;
`[INFRA-FAIL]` on failure). The new component adds only the *recurring, throttled* degraded-state
WARN that the one-shot logger does not provide.

### 4. `fetchWithAuthClient` (modified) — `frontend/src/lib/api/fetchWithAuth.ts`

Adds a `429` branch *before* the generic `!response.ok` throw, and preserves the `401` branch
exactly. Introduces a distinguishable error type. Everything is client-side only (the frontend is a
static export, `output: "export"`), so no server code is involved.

```ts
export class RateLimitError extends Error {
  readonly status = 429 as const;
  readonly retryAfterSeconds: number | null;
  constructor(path: string, retryAfterSeconds: number | null) {
    super(`Rate limited (429) for ${path}`);
    this.name = "RateLimitError";
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

// inside fetchWithAuthClient, after the fetch:
if (response.status === 401) { /* unchanged: clear session + redirect */ }

if (response.status === 429) {
  const header = response.headers.get("Retry-After");
  const parsed = header !== null ? Number.parseInt(header, 10) : NaN;
  const retryAfter = Number.isFinite(parsed) && parsed >= 0 ? parsed : null;
  throw new RateLimitError(path, retryAfter);   // Req 6.1, 6.7 (no auto-retry), 6.3 (no redirect)
}

if (!response.ok) {
  throw new Error(`Request failed (${response.status}) for ${path}`);  // Req 6.4 unchanged
}
```

Consumers:
- A global TanStack Query error handler inspects `error instanceof RateLimitError` and raises a
  distinguishable rate-limit toast/banner (Req 6.2), never silent.
- The chat/insight submission surface reads `retryAfterSeconds` to disable the submit control and
  render a countdown that re-enables it when it reaches 0 (Req 6.6). If `Retry-After` is absent,
  no countdown is shown and the error is still surfaced as a toast.

### 5. `RateLimitDenialResponseCustomizer` (new) — `com.wealth.gateway`

Stock `RequestRateLimiter` short-circuits a throttled request with `response.setComplete()` and an
**empty** body, and does not emit a `Retry-After` header. This component fills that gap so that the
gateway satisfies Req 5.5 (a `Retry-After` header plus a body indicating the limit was exceeded) and
thereby enables the frontend countdown (Req 6.6). It is a gateway-side step implemented as a
`GlobalFilter` **ordered to run before `RequestRateLimiter`** (higher precedence). This ordering is
essential: on denial `RequestRateLimiter` short-circuits with `exchange.getResponse().setComplete()`
and never calls `chain.filter(exchange)`, so any filter ordered *after* it would be skipped for
exactly the `429` responses this component must decorate. By running first, the component executes on
the way **in** and installs the response hooks (below) *before* the limiter runs; those hooks then
fire when `RequestRateLimiter` commits the denial. The hooks act only when the response status is
`429`, leaving allowed requests untouched.

**`Retry-After` value — per-limiter approximation.** The exact seconds-until-next-token is **not**
recoverable from Spring Cloud Gateway's stock `RedisRateLimiter` Lua script: the script returns only
`allowed` and `tokens_left`, not the bucket's refill timestamp, so the true time until the next
whole token cannot be computed at the filter. Instead the component emits a per-limiter constant:

```
Retry-After = ceil(requestedTokens / replenishRate) seconds
```

- Strict route: `ceil(6 / 1) = 6` seconds.
- Standard route: `ceil(1 / 10) = 1` second (integer `ceil` floors at 1 so the value is never `0`).

This is the time for the limiter to accrue enough tokens to admit one more request of that route's
`requestedTokens` size, and it is always a non-negative integer, satisfying Req 5.5's "non-negative
integer ... until at least one token is available." It is an upper-bound-ish approximation, not the
precise remaining time, which is the honest and safe direction (a client waiting slightly longer is
never rejected for being early).

**WebFlux mechanism (concrete).** Running before `RequestRateLimiter`, the component installs both
hooks on the way in and then calls `chain.filter(exchange)`. Because `RequestRateLimiter` later
completes the exchange with `setComplete()`, the body must be written and the header added before the
response commits — which is exactly why the hooks are installed up front:

- **Header:** register `exchange.getResponse().beforeCommit(() -> { ... })` to put the `Retry-After`
  header. Response headers remain mutable up until commit, so `beforeCommit` is the reliable place
  to add it. Because the hook is registered before the limiter runs, it fires when the limiter
  commits the denial.
- **Body:** wrap the response in a `ServerHttpResponseDecorator` that overrides `setComplete()`
  (and/or `writeWith`) so that, when status is `429`, it writes a small JSON error body such as
  `{"error":"rate_limited","message":"Rate limit exceeded","retryAfterSeconds":<n>}` and sets
  `Content-Type: application/json`. The decorator is installed on the way in so it is already in
  place when `RequestRateLimiter` calls `setComplete()` on the denial.

**Knowing which limiter applied.** The component must emit the value for the route class that
actually matched. The recommended mechanism is a **per-route metadata value** — attach
`metadata: { retry-after-seconds: 1 }` to standard routes and `retry-after-seconds: 6` to strict
routes in `application-prod.yml`, and read it via `exchange` route attributes
(`ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR` → `route.getMetadata().get("retry-after-seconds")`).
This keeps the value declarative and co-located with the route/filter wiring that already selects the
limiter, avoids a brittle filter→limiter-bean back-reference, and requires no code change if a third
route class is later added (only YAML). A fallback small route-class→seconds map in the component
handles a missing/mis-typed metadata value by defaulting to the strict (larger, safer) value.

## Data Models

### Token bucket (Redis-side, managed by `RedisRateLimiter`)

The bucket model is owned by Spring Cloud Gateway's built-in Lua script; the design does not
introduce a custom schema. Per (key, limiter) pair, Redis holds two keys:

| Redis key | Meaning |
| --- | --- |
| `request_rate_limiter.{<key>}.tokens` | Current available tokens |
| `request_rate_limiter.{<key>}.timestamp` | Last refill timestamp |

Where `<key>` is the `Rate_Limit_Key` from the resolver. Because each route class uses a distinct
named `RedisRateLimiter` bean, the standard and strict limiters maintain **independent** bucket
namespaces for the same client key (Req 3.5, 5.3).

**`X-RateLimit-Remaining` semantics.** The statement that `X-RateLimit-Remaining` "decrements from
`Burst_Capacity − 1` down to `0` by one per request" holds **only when `requestedTokens = 1`** —
true for the standard route. On the strict route each request consumes `requestedTokens = 6`, so
`X-RateLimit-Remaining` drops by 6 per request, not by 1. Any decrement-by-one assertion must
therefore target the **standard** route. Additionally, under fail-open (Redis unreachable) Spring
Cloud Gateway reports `X-RateLimit-Remaining: -1`; consequently Req 5.1's "non-negative, `0..Burst_Capacity`"
guarantee applies only to genuinely-allowed (non-fail-open) requests, and the `-1` sentinel is the
expected marker that no limiting was applied.

### `Rate_Limit_Key` derivation

| Condition (production) | Key |
| --- | --- |
| Authenticated (JWT `sub` present, non-blank) | `sub` (trimmed) — Req 3.6 |
| Unauthenticated, `X-Forwarded-For` present | right-most (ingress-appended) XFF hop — Req 3.9 |
| Unauthenticated, no XFF | remote socket address host |
| None of the above | `"anonymous"` |

### Configuration model (per profile)

| Property | `local` | `prod` (base) | `azure` | `aws` (standby) |
| --- | --- | --- | --- | --- |
| `spring.data.redis.*` | host/port | `url` (`REDIS_URL`) | inherits prod | inherits prod |
| `spring.data.redis.timeout` | — | ~1s (command / fail-open bound) | inherits | inherits |
| `spring.data.redis.connect-timeout` | — | larger, e.g. 3–5s (cold DNS + TLS handshake) | inherits | inherits |
| `default-filters` RequestRateLimiter | present (20/40/1) | **removed** | — | — |
| per-route `filters` RequestRateLimiter | — | present | inherits | inherits |
| `app.rate-limit.standard.*` (10/20/1) | — | present | inherits | inherits (from prod) |
| `app.rate-limit.strict.*` (1/30/6) | — | present | inherits | inherits (from prod) |
| `app.rate-limit.trust-xff-last-hop` | `false` (default) | `true` | inherits | inherits |
| `management.health.redis.enabled` | default | default | `false` | `false` |

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a
system — essentially, a formal statement about what the system should do. Properties serve as the
bridge between human-readable specifications and machine-verifiable correctness guarantees.*

Property-based testing applies most strongly to the two pure-function surfaces here — the key
resolver and the frontend response classifier / `Retry-After` parser. The token-bucket behavioral
invariants (allow-within-burst, independence, fail-open, exempt-routes) are expressed as properties
below and validated with generated inputs against a real Redis via Testcontainers; where a property
requires a live backend, its per-iteration cost is bounded by using small burst capacities.

### Property 1: Key derivation is correct and spoof-resistant

*For any* request exchange, when a JWT `sub` claim is present and non-blank the resolved
`Rate_Limit_Key` equals that `sub`; and *for any* `X-Forwarded-For` value of the form
`<arbitrary spoofed tokens...>, <ingressHop>` with no JWT present, the production resolver returns
`<ingressHop>` regardless of the spoofed prefix (falling back to the remote address, else
`"anonymous"`, only when no XFF hop is present).

**Validates: Requirements 3.6, 3.9**

### Property 2: Allow-within-burst, then 429, with bounded remaining

*For any* single `Rate_Limit_Key` and route class, given a burst capacity `B` and starting from a
full bucket, the first `k` requests with `k * requestedTokens <= B` are all allowed and each
allowed response carries `X-RateLimit-Remaining` in `[0, B]` decrementing monotonically; the first
request for which insufficient tokens remain returns HTTP `429`, is not proxied downstream, and
consumes no token.

**Validates: Requirements 1.6, 3.3, 3.4, 5.1, 5.2, 5.4**

### Property 3: Each request consumes from exactly one bucket

*For any* single request under a production profile, the total tokens consumed across all buckets
equals exactly the `requestedTokens` of the matched route's limiter (never twice) — i.e. no request
is charged against both a global `default-filters` limiter and a per-route limiter.

**Validates: Requirements 3.7**

### Property 4: Independent buckets per key and per route class

*For any* two distinct `(Rate_Limit_Key, routeClass)` combinations, exhausting the bucket of one
leaves the other able to receive an allowed (non-`429`) response; token consumption attributed to
one combination never reduces the available tokens of another (including the same key across the
standard vs strict route classes).

**Validates: Requirements 3.5, 5.3**

### Property 5: Fail-open when Redis is unreachable

*For any* incoming request under a production profile, when the `Redis_Backend` is unreachable
(at startup or mid-request, including a rate-limit check that exceeds the configured Lettuce
command timeout), the API_Gateway proxies the request to the downstream service rather than
rejecting it with a rate-limiter error.

**Validates: Requirements 1.5, 1.7, 4.1, 4.2**

### Property 6: Exempt routes are never throttled

*For any* number of requests targeting an `Exempt_Route` (`/api/internal/**`), no request receives
a `429` attributable to the rate limiter, regardless of the `Rate_Limit_Key`.

**Validates: Requirements 3.8**

### Property 7: Frontend response classification

*For any* HTTP response status, `fetchWithAuthClient` classifies it deterministically: `401` clears
the session and redirects to `/login` (unchanged); `429` throws a `RateLimitError` distinguishable
from the generic error and does **not** clear the session or redirect; any other non-`ok` status
throws the generic request-failure error; and no status triggers an automatic retry.

**Validates: Requirements 6.1, 6.3, 6.4, 6.5, 6.7**

### Property 8: Retry-After drives the submit countdown

*For any* `429` response to a chat/insight submission, when the `Retry-After` header is a
non-negative integer `n` the submit control is disabled and a countdown is initialized to `n`
seconds before re-enabling; when `Retry-After` is absent or unparseable, no countdown is shown and
the submit control is not left permanently disabled.

**Validates: Requirements 6.6**

### Property 9: Gateway always emits a parseable non-negative `Retry-After` and a body on 429

*For any* request that the gateway rejects with HTTP `429` under a production profile, the response
carries a `Retry-After` header whose value is a parseable non-negative integer (the matched route
class's `ceil(requestedTokens / replenishRate)` — 1 for the standard route, 6 for the strict route)
and a non-empty JSON body indicating the rate limit was exceeded. This is the gateway-side companion
to Property 8, which covers the frontend's consumption of that header.

**Validates: Requirements 5.5, 6.6**

## Error Handling

### Two intentionally distinct failure modes (Req 1.5 vs 2.5 — Req 2.6)

These must never be reconciled to behave the same. A future maintainer "fixing" one to match the
other would reintroduce either a production outage risk or a silent-misconfiguration risk.

| Condition | Detection | Behavior | Requirement |
| --- | --- | --- | --- |
| Missing/invalid Redis URL, or Redis unreachable | Connection attempted lazily on first command; error/timeout surfaced by Lettuce | **Fail open** — startup completes, requests proxied without limiting | 1.5, 1.7, 4.1, 4.2 |
| Required limit parameter absent in a loaded prod profile | Spring `@Value` placeholder without a default cannot resolve → `BeanCreationException` at context refresh | **Fail startup** — clear error naming the missing `app.rate-limit.*` key | 2.5 |

The distinction is enforced structurally: Redis connectivity is a *runtime* concern (never blocks
startup, because the health indicator is disabled and the connection is lazy), whereas limit
parameters are *bean-construction* concerns (resolved eagerly at startup, no fallback). Because the
limiter beans read properties with no defaults, a typo or omission in `application-prod.yml` halts
deployment loudly instead of silently applying a wrong or absent limit.

### Fail-open mechanism detail (Req 4.2)

The ~1-second fail-open bound is **not** a stock `RedisRateLimiter` SLA. It is achieved
approximately by the Lettuce command/connection timeout configured via `spring.data.redis.timeout`
(set to ~1s under prod). When the rate-limit Lua evaluation errors or times out,
`RedisRateLimiter.isAllowed(...)` resolves to an *allowed* response rather than propagating the
error — Spring Cloud Gateway's built-in behavior is to fail open on backend error. Setting a tight
command timeout ensures a stalled Redis surfaces the error quickly (bounded latency) instead of
hanging the request. If the request is allowed-by-failure and the *downstream* proxy then fails,
the client receives the downstream/proxy error (e.g. `504`), and no rate limiting was applied
(Req 4.3).

**Command timeout vs connection timeout.** A single ~1s Lettuce *command* timeout is tight for a
*cold* connection to Upstash over public TLS: the first request(s) after a scale-to-zero cold start
must complete DNS resolution plus a TLS handshake before any command round-trip, which can exceed 1s
and cause a spurious fail-open on those first request(s). That is the safe failure direction
(requests are proxied, not rejected) and is acceptable. To reduce it without loosening the
steady-state command bound, configure the connection establishment budget separately via
`spring.data.redis.connect-timeout` (larger, e.g. 3–5s) and keep `spring.data.redis.timeout` (~1s)
as the per-command timeout only.

### Degraded-state logging (Req 4.4, 4.5)

- Startup: `InfrastructureHealthLogger` (existing, `@Profile({"aws","azure"})`, fires on
  `ApplicationReadyEvent`, never blocks startup) logs `[INFRA-OK]` on ping success (Req 4.5) or
  `[INFRA-FAIL]` on failure.
- Steady state: `RedisRateLimitStateLogger` (new) emits the degraded WARN at most once per 60s
  while Redis is down and a single recovery INFO on down→up transition, driven by a scheduled probe
  so it fires regardless of traffic.

### Health indicator suppression (Req 2.3)

`management.health.redis.enabled: false` stays set on `azure` and `aws`. On scale-to-zero compute,
Lettuce may attempt to resolve the Upstash hostname before the platform DNS stack is ready, which
would otherwise mark the readiness probe `DOWN` and stall cold start. Suppressing the indicator is
unrelated to whether the limiter works — the limiter still runs and fails open if Redis is
genuinely unavailable.

### Pre-existing `RedisSslConfig` and the no-`io.lettuce` guardrail (Req 2.2)

Requirement 2.2 forbids new `software.amazon.awssdk.*` and `io.lettuce.*` imports in
`com.wealth.gateway`. `RedisSslConfig` already imports `io.lettuce.core.*` today and is out of
scope for this feature — none of the new code (limiter beans, key-resolver change, state logger,
route config) introduces such imports. The architecture test that guards this rule must therefore
either scope to the classes changed by this feature or explicitly allow-list the pre-existing
`RedisSslConfig`; the design does not add to the violation surface.

## Testing Strategy

### Property-based tests

Implemented with **jqwik** (JUnit 5 property-based library) for the JVM pure-function properties,
and **fast-check** for the frontend properties. Each property test runs a minimum of 100 iterations
and is tagged referencing its design property.

- **Property 1** (key derivation / spoof-resistance): pure unit property over
  `resolveTrustedHopKey` / `resolveKey` and a stubbed `JwtAuthenticationToken` — generate arbitrary
  spoofed XFF prefixes plus a trusted hop, assert the trusted hop is returned. No Redis needed.
  Tag: `Feature: production-rate-limiting, Property 1`.
- **Property 7** (frontend classification) and **Property 8** (Retry-After countdown): fast-check
  properties over generated status codes and `Retry-After` values against `fetchWithAuthClient` and
  the countdown hook, using MSW to synthesize responses.

### Integration tests (Testcontainers, `@Tag("integration")`)

Extending the existing `RateLimitingIntegrationTest` pattern (real Redis via `GenericContainer`,
`WebTestClient`, unique JWT `sub` per test to isolate buckets), a new
`ProductionRateLimitingIntegrationTest` runs under `@ActiveProfiles({"prod","azure"})` with small
burst capacities injected via `@DynamicPropertySource` for speed. These validate the
backend-dependent properties (2, 3, 4, 5, 6) and the Requirement 7 acceptance criteria:

| Test | Asserts | Requirements | Property |
| --- | --- | --- | --- |
| `burstAllowedThenThrottledWithDecrement` (STANDARD route) | `k<=B` allowed, `X-RateLimit-Remaining` decrements `B-1..0`, `B+1`th → `429` with `Retry-After` header + JSON body, not proxied | 7.2, 7.3, 5.1, 5.4, 5.5 | 2, 9 |
| `standardVsStrictThresholds` | standard route allows ~`B_std`, strict route throttles at its lower threshold | 7.6, 3.1, 3.2 | 2 |
| `singleBucketPerRequest` | exactly `B` requests allowed to one route (not `B/2`) — proves no double-filter | 3.7 | 3 |
| `independentBucketsPerKeyAndRouteClass` | exhausting key A / standard leaves key B and same-key strict allowed | 7.4, 3.5, 5.3 | 4 |
| `failOpenWhenRedisDown` | with Redis stopped, request is proxied (downstream response), not `429`/limiter error | 7.5, 1.5, 1.7, 4.2 | 5 |
| `exemptRouteNeverThrottled` | flood `/api/internal/**` beyond any bucket → no limiter `429` | 3.8 | 6 |
| `missingLimitParamFailsStartup` | context load under prod with an `app.rate-limit.*` property overridden to empty → `BeanCreationException` naming the key | 2.5 | — |

The `burstAllowedThenThrottledWithDecrement` assertion targets the **standard** route deliberately:
the decrement-by-one expectation for `X-RateLimit-Remaining` only holds when `requestedTokens = 1`
(on the strict route remaining drops by 6 per request — see Data Models). Its `429` assertion also
verifies the `Retry-After` header (a non-negative integer, `1` for the standard route) and the JSON
error body emitted by `RateLimitDenialResponseCustomizer` (Property 9, Req 5.5).

**`missingLimitParamFailsStartup` mechanism.** A key defined in `application-prod.yml` cannot be
"deleted" from a test `ApplicationContext` — YAML documents are already merged by the time the
context builds. The practical mechanism is to **override the property to an empty string** (via
`@DynamicPropertySource` or a test property, e.g. `app.rate-limit.standard.replenish-rate=`), which
makes the no-default `@Value` `int` binding fail to parse and raises a `BeanCreationException` during
context refresh. The test asserts the context fails to start and that the error names the offending
`app.rate-limit.*` key.

**Ingress XFF emulation for unauthenticated-key tests.** With `app.rate-limit.trust-xff-last-hop:
true` active under `prod,azure`, the resolver keys unauthenticated requests off the **right-most**
(ingress-appended) `X-Forwarded-For` hop. `WebTestClient` requests originate in-process and carry no
ingress-appended hop, so any test exercising the unauthenticated (IP-keyed) path must **append the
"real" client IP as the right-most `X-Forwarded-For` entry itself** (e.g.
`X-Forwarded-For: <spoofed-prefix>, <realClientIp>`) to reproduce the trusted-hop selection and
isolate buckets (Property 1 / Req 3.9). Tests must not rely on the socket remote address for keying,
since in-process the peer is the loopback client for every request.

The existing `local`-profile `RateLimitingIntegrationTest` remains unchanged and green (Req 1.3).

### Configuration / architecture (smoke) tests

- Assert `application.yml` contains no `spring.data.redis` and no `RequestRateLimiter` (Req 1.2, 2.4).
- Assert `management.health.redis.enabled` resolves to `false` under `azure` and `aws` (Req 2.3).
- Assert `application-aws.yml` contains no `app.rate-limit.*` values (Req 8.3).
- Architecture test: no new `software.amazon.awssdk.*` / `io.lettuce.*` imports in
  `com.wealth.gateway` beyond the pre-existing `RedisSslConfig` (Req 2.2).

### Frontend UI tests

- Vitest + Testing Library + MSW: `429` surfaces a rate-limit toast distinguishable from a generic
  failure toast and does not redirect (Req 6.2, 6.3); `401` still clears session and redirects
  (Req 6.5); chat/insight submit disables with a countdown on `Retry-After` (Req 6.6); no automatic
  retry occurs (Req 6.7).

### Out of scope

Rate-limit **enforcement is active on `prod,aws` automatically**, inherited from the cloud-agnostic
`application-prod.yml` (the `aws` profile only overrides specific keys and does not re-declare the
`app.rate-limit.*` block or per-route filters). What remains out of scope for this phase is only:
(a) committed `prod,aws` integration-test coverage, and (b) platform-native edge throttling
(Option B — Azure APIM / Front Door / AWS API Gateway usage plans) as a complementary future
DDoS/edge layer. Option B is switchable later via the config-only `app.rate-limiter.backend`
escape hatch with no domain/business-layer code changes (Req 8, resolution note; Req 8.2, 8.3).
