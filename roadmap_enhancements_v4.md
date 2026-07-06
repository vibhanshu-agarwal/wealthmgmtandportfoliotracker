# Wealth Management & Portfolio Tracker
## High-Level Architecture Design & Backlog Refinement (v4)

This document supersedes `roadmap_enhancements_v3.md`. It reflects the closure of the
**Production Rate-Limiting** item (PR #82, merged 2026-07-05), adds a **Status** column to the
prioritization matrix, and updates spec references to their canonical `.kiro/specs/` locations.

Everything below is checked against the actual repository state as of 2026-07-06.

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
- **Phase 5 (NEW):** Production rate limiting enforced across all prod profiles.
- **Today:** four services deployed as four separate Azure Container Apps (Central India,
  scale-to-zero), with `api-gateway` as the only externally-reachable app.

| Module | Role | Datastore |
|---|---|---|
| `api-gateway` | Spring Cloud Gateway (WebFlux) — JWT validation, origin verification, rate limiting, routing | none (Redis for rate-limit token buckets) |
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

### Item A: Observability & Application Insights Integration

- **Status:** Partially done. OpenTelemetry instrumentation wired across all four services but OTLP export gated off by default. No OTLP collector deployed for the active Azure cloud yet.
- **Remaining gaps:** (1) Kafka producer->consumer trace-ID continuity verification; (2) OTLP collector for Azure.

### Item B: User-Defined Portfolio Composition & Asset Picker

- **Status:** Not started. No `AssetPicker` component or `/api/assets` endpoint exists.
- v2's proposed relational schema is carried forward as the target design.

### Item C: Custom Asset & Portfolio Management

- **Status:** Not started. Distinct from Item B — allows defining assets outside the curated ~160-asset universe.
- Kept as lowest priority due to LLM-cost/catalog-validation risk.

---

## 3. User Experience & Identity (unchanged from v3, with spec reference update)

### 3.1 Identity Management & New User Signup

The spec is complete and ready for implementation at `.kiro/specs/new-user-signup-profile/`
(based on the root-level `new-user-signup-profile-spec.md`).

Current state: Better Auth schema/library exist in the codebase, but the live login path
supports exactly one hardcoded credential pair. The frontend's static-export deployment blocks
Better Auth's server routes from running — an architectural decision is needed first (see spec).

### 3.2 User Profiles & Personalization Settings

- Settings page is a placeholder ("coming soon").
- Dark/light theming works client-side (not persisted to a user account).
- Base currency is a single global setting, not per-user.
- Risk tolerance does not exist anywhere.
- Blocked on Section 3.1 (needs a real persisted user identity).

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
| 2 | **New User Signup & Profile** | v1 | High | High | Medium-High | **2** | **READY** |
| 3 | **Observability & App Insights** | v2 Item #3 | Medium | Low | Medium | **3** | NOT STARTED |
| 4 | **User Settings (Personalization)** | v1 | Medium | Medium | Medium | **4** | NOT STARTED |
| 5 | **Asset Picker (curated universe)** | v2 Item #5 | High | High | Low | **5** | NOT STARTED |
| 6 | **Custom Asset & Portfolio Management** | v1 (Item C) | Medium | High | Low | **6** | NOT STARTED |

**Status definitions:**
- `CLOSED` — Fully implemented, merged, and deployed.
- `READY` — Spec complete at `.kiro/specs/`, ready for implementation in a dedicated session.
- `NOT STARTED` — No spec or implementation exists yet.

> **Implementation order:** Production Rate-Limiting is done. Next is **New User Signup & Profile**
> (`.kiro/specs/new-user-signup-profile/`). Its auth-endpoint limiter reuses the shared
> `RedisRateLimiter` wiring and trusted-hop resolver created by the rate-limiting spec.

---

## 6. Sources Cited

- `README.md` — "Enterprise Resilience & Event-Driven Data", "Architectural Philosophy", "Production Deployment"
- `ROADMAP.md` — "Current State (July 2026)", "Completed Phases" (including Phase 5), "Future Architectural Goals"
- `docs/changes/CHANGES_PRODUCTION_RATE_LIMITING_2026-07-05.md` — detailed changelog for the rate-limiting implementation
- `.kiro/specs/production-rate-limiting/` — requirements.md, design.md, tasks.md (based on `production-rate-limiting-spec.md`)
- `.kiro/specs/new-user-signup-profile/` — requirements.md, design.md, tasks.md (based on `new-user-signup-profile-spec.md`)
- `api-gateway/src/main/resources/{application,application-local,application-prod,application-azure,application-aws}.yml`
- `api-gateway/src/main/java/com/wealth/gateway/{GatewayRateLimitConfig,RateLimitDenialResponseCustomizer,RedisRateLimitStateLogger}.java`
- `frontend/src/lib/api/fetchWithAuth.ts`, `frontend/src/lib/hooks/useRetryAfterCountdown.ts`
- `roadmap_enhancements_v3.md` (superseded by this document)
