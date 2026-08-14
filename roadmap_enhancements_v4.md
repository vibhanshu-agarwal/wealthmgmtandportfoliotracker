# Wealth Management & Portfolio Tracker
## High-Level Architecture Design & Backlog Refinement (v4)

This document supersedes `roadmap_enhancements_v3.md`. It reflects the closure of the
**Production Rate-Limiting** item (PR #82, merged 2026-07-05), adds a **Status** column to the
prioritization matrix, and updates spec references to their canonical `.kiro/specs/` locations.

> **Revised 2026-08-13** — corrected in place (deliberately not forked to a v5) to close the
> **New User Signup & Profile** item, which shipped on 2026-08-12 via PRs
> [#85](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/85) and
> [#88](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/88). Sections 1.1,
> 3.1, 3.2, 5, and 6 were updated. No backlog entries were added or removed — this is a staleness
> correction, not a re-scoping.

> **Revised 2026-08-14** — corrected in place again, same non-negotiable: no backlog entries added
> or removed. The **Observability & App Insights** item (Section 2, Item A) moves from
> `NOT STARTED` to `READY` now that a full Kiro spec exists at
> `.kiro/specs/observability-app-insights/` (requirements → design → tasks, PR
> [#91](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/91)). This is a
> spec landing, not an implementation — `tasks.md` has not been executed and no infrastructure has
> changed. Item A's status text and the Section 5 matrix were updated accordingly.

Everything below is checked against the actual repository state as of 2026-08-13.

---

## 0. What Changed From v3

| # | Change | Detail |
|---|---|---|
| 1 | Production Rate-Limiting is now **CLOSED** | Fully implemented and merged (PR #82). Changelog: `docs/changes/CHANGES_PRODUCTION_RATE_LIMITING_2026-07-05.md`. |
| 2 | Added **Status** column to the prioritization matrix (Section 5) | Values: `CLOSED`, `READY`, `NOT STARTED`. |
| 3 | Updated spec references to `.kiro/specs/` paths | The Kiro specs at `.kiro/specs/production-rate-limiting/` and `.kiro/specs/new-user-signup-profile/` were based on the root-level spec files (`production-rate-limiting-spec.md`, `new-user-signup-profile-spec.md`). |

---

## 1. Core Architectural Reality (unchanged from v3)

### 1.1 This is a microservices system today, not a monolith

The repository root contains five Gradle modules with independent `build.gradle` files:
`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`, and `common-dto`
(shared DTOs/event contracts only — not a runtime service).

- **Phase 1 (superseded):** a strictly modular monolith on Spring Modulith.
- **Phase 2:** `market-data-service` extracted; in-process events replaced by Apache Kafka.
- **Phase 3:** `insight-service` extracted as an independent container image.
- **Phase 5:** Production rate limiting enforced across all prod profiles.
- **Phase 6 (NEW):** Self-service signup and per-user authentication owned by `api-gateway`;
  Better Auth retired.
- **Today:** four services deployed as four separate Azure Container Apps (Central India,
  scale-to-zero), with `api-gateway` as the only externally-reachable app.

| Module | Role | Datastore |
|---|---|---|
| `api-gateway` | Spring Cloud Gateway (WebFlux) — login/signup, JWT minting + validation, origin verification, rate limiting, read-only enforcement, routing | Redis (rate-limit token buckets) + PostgreSQL (read-only credential access, opt-in per profile) |
| `portfolio-service` | Holdings, valuations, analytics, FX conversion, Kafka projection, DLT handling | PostgreSQL + Flyway |
| `market-data-service` | Ingests from Yahoo Finance, persists snapshots, publishes `PriceUpdatedEvent` | MongoDB |
| `insight-service` | AI chat / market summary / natural-language asset resolution | Redis (cache) + Azure OpenAI / Bedrock / mock |
| `common-dto` | Shared DTOs, event contracts, truststore extractor — **not a deployed service** | n/a |

### 1.2 Three bounded contexts, not two

1. **`com.wealth.portfolio`** (Core Domain) — user holdings, valuations, transactional updates.
2. **`com.wealth.market`** (Anti-Corruption Layer) — ingests/normalizes/broadcasts external pricing data.
3. **`com.wealth.insight`** (Compute Domain) — AI-driven insights and natural-language portfolio Q&A.

### 1.3 Ingestion & scaling efficiency (unchanged from v3)

- **Static asset pool:** ~160 core assets (Yahoo Finance tickers). Background cron jobs refresh; no dynamic scraper-thread scaling.
- **Decoupled architecture:** user portfolio composition never triggers external network calls. `portfolio-service` reads only from internally-cached market data projected via Kafka.

---

## 2. Refined Backlog Items Carried Forward (verified, unchanged from v3)

### Item A: Observability & Application Insights Integration — READY

**Status: READY** — spec complete at `.kiro/specs/observability-app-insights/`
(requirements.md → design.md → tasks.md), not yet implemented.

OpenTelemetry instrumentation is already wired across all four services (unchanged from prior
status) but OTLP export stays gated off by default until the spec's Terraform lands. The spec
closes both previously-named gaps and adds the operational scaffolding neither v2 nor v3
anticipated:

- **Sink:** the ACA **managed OpenTelemetry agent** (chosen over the GA Application Insights Java
  agent so the existing Spring/Micrometer instrumentation stays load-bearing) routes traces to a
  new, dedicated Application Insights workspace — provisioned via a newly-added **AzAPI** Terraform
  provider, since AzureRM does not yet model this resource.
- **Kafka producer→consumer trace-ID continuity** (the gap named in v2/v3): root-caused as a test
  fixture defect, not a framework gap — both existing tests hand-build an unobserved
  `KafkaTemplate` instead of using the auto-configured bean. Fixed as part of the spec, completing
  Task 11.2 of `.kiro/specs/springboot-41-springai-2-migration/`.
- **Cost control**, under the owner's non-negotiable **₹1100/month total** ceiling for
  `wealth-azure-prod-rg`: both workspace daily ingestion caps at Azure's 0.023 GB/day floor, sized
  so the ceiling holds even if the shared 5 GB/month Analytics allowance is exhausted elsewhere
  (`Allowance_Independence`), plus a new Cost Management budget alert — a gap open since it was
  first recommended on 2026-05-17.
- **Redaction:** a new `common-observability` module sanitizes exported spans (query strings,
  tokens, portfolio values, exception content) before they leave the process, since this platform
  handles financial data.
- Verified against the live subscription during design: ACR is ≈100% of current project spend
  (~553 INR/month); this feature's own projected marginal cost is ₹0.00 at current traffic.

### Item B: User-Defined Portfolio Composition & Asset Picker

- **Status:** Not started. No `AssetPicker` component or `/api/assets` endpoint exists.
- v2's proposed relational schema is carried forward as the target design.

### Item C: Custom Asset & Portfolio Management

- **Status:** Not started. Distinct from Item B — allows defining assets outside the curated ~160-asset universe.
- Kept as lowest priority due to LLM-cost/catalog-validation risk.

---

## 3. User Experience & Identity

### 3.1 Identity Management & New User Signup — CLOSED

**Status: CLOSED** (PRs #85 and #88, merged 2026-08-12)

**Spec:** `.kiro/specs/new-user-signup-profile/` (based on root-level `new-user-signup-profile-spec.md`)
**Changelog:** `docs/changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md`

The architectural decision this item was blocked on was resolved by moving identity **out of the
frontend and into `api-gateway`**, which sidesteps the static-export constraint entirely: there
are no server routes to run, because the gateway now owns login, signup, and JWT minting.

What was delivered:

- `user_credentials` table (bcrypt, cost 12) plus `users.name` / `users.read_only`, via Flyway
  migrations V14–V16 in `portfolio-service`.
- A `com.wealth.gateway.auth` package — `AuthenticationService`, `SignupService`,
  `SignupValidator`, `UserCredentialRepository`, `PasswordHasherConfig` and typed exceptions.
- `POST /api/auth/signup` (201 + JWT, users + credentials inserted in one transaction) and a
  rewritten `POST /api/auth/login` returning a byte-identical 401 for every failure reason, with
  timing equalized against a dummy bcrypt hash.
- `ReadOnlyEnforcementFilter` for the read-only demo account (AI routes allowlisted) and
  `AuthRateLimitFilter` throttling the auth endpoints via a third `authRateLimiter` bean.
- `GatewayAuthFallbackAutoConfiguration` so a datasource-less profile fails auth closed with a
  503 instead of preventing the gateway from booting.
- Better Auth fully retired — `ba_*` tables dropped (V16), dependency and dead frontend code
  removed.

Verified live on `https://vibhanshu-ai-portfolio.dev/` (fresh signup, demo login, dev login) after
a `terraform apply` that the merge itself did not trigger — that process gap is open as
`docs/todos/backlog/terraform-apply-not-automatic-on-merge/`.

### 3.2 User Profiles & Personalization Settings

- Settings page is a placeholder ("coming soon").
- Dark/light theming works client-side (not persisted to a user account).
- Base currency is a single global setting, not per-user.
- Risk tolerance does not exist anywhere.
- **No longer blocked** — Section 3.1 delivered the persisted per-user identity this depended on.
  `users` now carries `name` and `read_only`, but no preference columns exist yet, so the
  remaining work is the settings surface and its schema.

### 3.3 Custom Asset & Portfolio Management

See Item C in Section 2 above.

---

## 4. Production Rate-Limiting — CLOSED

**Status: CLOSED** (PR #82, merged 2026-07-05)

**Spec:** `.kiro/specs/production-rate-limiting/` (based on root-level `production-rate-limiting-spec.md`)
**Changelog:** `docs/changes/CHANGES_PRODUCTION_RATE_LIMITING_2026-07-05.md`

### What was delivered

- Two named, profile-gated `RedisRateLimiter` beans: `standardRateLimiter` (10 req/s, burst 20)
  and `strictRateLimiter` (~10 req/min, burst 5) for cost-sensitive AI routes.
- Per-route explicit filter wiring in `application-prod.yml` (never global `default-filters`).
- Trusted-hop XFF key derivation (`app.rate-limit.trust-xff-last-hop`) to prevent bucket-spoofing.
- Fail-open semantics: Redis unreachability never blocks gateway startup or rejects traffic.
- `RateLimitDenialResponseCustomizer` GlobalFilter: `Retry-After` header + JSON body on 429.
- `RedisRateLimitStateLogger`: recurring degraded-state logging (`[INFRA-DEGRADED]` / `[INFRA-OK]`).
- Frontend `RateLimitError` class, `useRetryAfterCountdown` hook, countdown UI in `ChatInterface`,
  distinguishable rate-limited card in `MarketSummaryGrid`.

### Non-blocking follow-up watch-items (tracked, not required for closure)

- Fail-open cost exposure on AI routes: if Redis is down for an extended period, AI-route
  traffic is unthrottled, which could spike Azure OpenAI spend.
- XFF hop-count assumption: the trusted-hop resolver takes the last entry, which assumes
  exactly one reverse proxy layer (valid for both ACA and CloudFront today).
- `REDIS_URL` fallback watch-item: if Upstash's free tier is deprecated, the gateway must
  gracefully degrade or switch to an alternative backing store.

---

## 5. Updated Prioritization Matrix

| # | Feature | Source | Importance | Usability | Ease | Priority | Status |
|---|---|---|---|---|---|---|---|
| 1 | **Production Rate-Limiting** | v1 / ROADMAP.md | High | Low | Medium | **1** | **CLOSED** |
| 2 | **New User Signup & Profile** | v1 | High | High | Medium-High | **2** | **CLOSED** |
| 3 | **Observability & App Insights** | v2 Item #3 | Medium | Low | Medium | **3** | **READY** |
| 4 | **User Settings (Personalization)** | v1 | Medium | Medium | Medium | **4** | NOT STARTED |
| 5 | **Asset Picker (curated universe)** | v2 Item #5 | High | High | Low | **5** | NOT STARTED |
| 6 | **Custom Asset & Portfolio Management** | v1 (Item C) | Medium | High | Low | **6** | NOT STARTED |

**Status definitions:**
- `CLOSED` — Fully implemented, merged, and deployed.
- `READY` — Spec complete at `.kiro/specs/`, ready for implementation in a dedicated session.
- `NOT STARTED` — No spec or implementation exists yet.

> **Implementation order:** Production Rate-Limiting and New User Signup & Profile are both done —
> the latter's `authRateLimiter` bean and `AuthRateLimitFilter` did reuse the shared
> `RedisRateLimiter` wiring and trusted-hop resolver the rate-limiting spec created, as planned.
> **Observability & App Insights** (#3) now has a complete spec (Section 2, Item A) and is next up
> for implementation. **User Settings** (#4) is unblocked by the persisted identity from #2 but
> still needs a spec.

---

## 6. Sources Cited

- `README.md` — "Enterprise Resilience & Event-Driven Data", "Architectural Philosophy", "Production Deployment"
- `ROADMAP.md` — "Current State (July 2026)", "Completed Phases" (including Phase 5), "Future Architectural Goals"
- `docs/changes/CHANGES_PRODUCTION_RATE_LIMITING_2026-07-05.md` — detailed changelog for the rate-limiting implementation
- `docs/changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md` — detailed changelog for the signup/per-user-auth implementation
- `.kiro/specs/production-rate-limiting/` — requirements.md, design.md, tasks.md (based on `production-rate-limiting-spec.md`)
- `.kiro/specs/new-user-signup-profile/` — requirements.md, design.md, tasks.md (based on `new-user-signup-profile-spec.md`)
- `api-gateway/src/main/resources/{application,application-local,application-prod,application-azure,application-aws}.yml`
- `api-gateway/src/main/java/com/wealth/gateway/{GatewayRateLimitConfig,RateLimitDenialResponseCustomizer,RedisRateLimitStateLogger}.java`
- `api-gateway/src/main/java/com/wealth/gateway/{AuthController,AuthRateLimitFilter,ReadOnlyEnforcementFilter,JwtSigner}.java` and the `com.wealth.gateway.auth` package
- `portfolio-service/src/main/resources/db/migration/V14–V16` — credential schema, auth seed reconciliation, Better Auth table drop
- `frontend/src/lib/api/fetchWithAuth.ts`, `frontend/src/lib/hooks/useRetryAfterCountdown.ts`, `frontend/src/lib/auth/{session,signupValidator}.ts`
- `roadmap_enhancements_v3.md` (superseded by this document)
