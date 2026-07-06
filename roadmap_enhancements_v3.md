# Wealth Management & Portfolio Tracker
## High-Level Architecture Design & Backlog Refinement (v3)

This document supersedes `roadmap_enhancements_v2.md`. It corrects two factual errors in v2's
architecture description, restores three high-priority items from `roadmap_enhancements_v1.md`
that v2 dropped, and expands the two items v1 flagged as highest priority
(**Production Rate-Limiting** and **New User Signup & Profile**) into dedicated, code-grounded
specs:

- [`production-rate-limiting-spec.md`](./production-rate-limiting-spec.md)
- [`new-user-signup-profile-spec.md`](./new-user-signup-profile-spec.md)

Everything below is checked against the actual repository state (source files, Flyway
migrations, Terraform, `README.md`, `ROADMAP.md`) as of 2026-07-05, with file paths cited so each
claim can be re-verified. Where the real state is ambiguous or requires a product decision rather
than a technical one, that is called out explicitly instead of assumed.

---

## 0. What Changed From v2

| # | v2 said | Reality | Fix in this doc |
|---|---|---|---|
| 1 | "Designed as a **Modular Monolith** built on Spring Boot... prepares the system for eventual cloud-native deployment" | The modular monolith was **Phase 1** and is **superseded**. The system is *already* four independently-deployable Spring Boot 4.1 microservices plus a shared contract module, running as four separate Azure Container Apps today. | Section 1 rewritten below. |
| 2 | "Split into two primary bounded contexts": Global Market Data + User Portfolio | There are **three** bounded contexts in production, not two — the AI Insight/compute domain (`insight-service`) is a first-class, separately-deployed service with its own datastore (Redis) and its own AI-provider abstraction (Azure OpenAI / Bedrock / mock). | Section 1.2 adds the third domain. |
| 3 | No mention of Production Rate-Limiting, New User Signup & Profile, or User Settings (Personalization) | All three are real, currently-unaddressed gaps confirmed in code (details below). | Added as Sections 3–4. |
| 4 | Item #5 ("User-Defined Portfolio Composition & Asset Picker") implicitly reads as the only asset-related backlog item | v1's separate "Custom Asset & Portfolio Management" item (fully custom/untracked assets) is a **distinct, larger-scope** item that v2's Item #5 (picking from the curated ~160) does not cover. Neither is built yet. | Section 3.3 keeps them distinct. |

Everything else in v2 (Item #3 Observability, Item #5 Asset Picker, the proposed relational schema,
the API contracts, the analytics formulas) was verified against the code and is **not** contradicted
by anything found — those sections are carried forward with light corrections noted inline.

---

## 1. Core Architectural Reality (replaces v2 §1)

### 1.1 This is a microservices system today, not a monolith

The repository root contains five Gradle modules with independent `build.gradle` files:
`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`, and `common-dto`
(shared DTOs/event contracts only — not a runtime service). Per `README.md`
("Architectural Philosophy: Evolutionary Design") and `ROADMAP.md` ("Completed Phases"):

- **Phase 1 (superseded):** a strictly modular monolith on **Spring Modulith**, single deployable,
  single Postgres instance, in-process events via a JDBC outbox. Tagged `v1.0-modular-monolith`.
  Spring Modulith is **no longer a runtime dependency**.
- **Phase 2:** the market-data domain was extracted into `market-data-service`; in-process events
  were replaced by **Apache Kafka** (`PriceUpdatedEvent` on `market-prices`, with a
  `market-prices.DLT` dead-letter topic on the `portfolio-service` consumer).
- **Phase 3:** the AI/insight domain was extracted into `insight-service`, packaged as an
  independent container image.
- **Today:** four services are deployed as four separate **Azure Container Apps** (Central India,
  scale-to-zero), each independently scaled, with `api-gateway` as the only externally-reachable
  app. The same four services also exist as four separate **AWS Lambda functions** (arm64/Graviton2)
  on the soft-disabled standby path. Cloud selection is DNS/config-driven; no domain code changes
  between clouds.

| Module | Role | Datastore |
|---|---|---|
| `api-gateway` | Spring Cloud Gateway (WebFlux) — JWT validation, origin verification, rate limiting, routing | none (Redis only for rate-limit token buckets, see §4) |
| `portfolio-service` | Holdings, valuations, analytics, FX conversion, Kafka projection, DLT handling | PostgreSQL + Flyway |
| `market-data-service` | Ingests from Yahoo Finance, persists snapshots, publishes `PriceUpdatedEvent` | MongoDB |
| `insight-service` | AI chat / market summary / natural-language asset resolution | Redis (cache) + Azure OpenAI / Bedrock / mock |
| `common-dto` | Shared DTOs, event contracts, truststore extractor — **not a deployed service** | n/a |

No service shares another's database; cross-service references are plain identifiers, never JPA
relationships. Boundaries are enforced structurally (separate datastores, contract-first Kafka
events pinned by wire-contract tests), not by in-process module verification — because there is no
longer a single process to verify.

### 1.2 Three bounded contexts, not two

v2 described two bounded contexts. The actual domain split (per `README.md` §"Bounded Contexts"
and the module list above) is three, each owned by a separately-deployed service:

1. **`com.wealth.portfolio`** (Core Domain) — user holdings, valuations, transactional updates.
   PostgreSQL, ACID, Flyway-migrated. Owned by `portfolio-service`.
2. **`com.wealth.market`** (Anti-Corruption Layer) — ingests/normalizes/broadcasts external
   pricing data. MongoDB. Owned by `market-data-service`.
3. **`com.wealth.insight`** (Compute Domain) — AI-driven insights and natural-language portfolio
   Q&A; CPU/IO-bound, operates asynchronously off the Kafka stream and a Redis cache. Owned by
   `insight-service`.

`api-gateway` is the fourth deployable but is an edge/routing layer, not a domain.

**Identity is still handled at the edge, and more narrowly than either v1 or v2 assumed** — see
Section 3.1 and `new-user-signup-profile-spec.md` for the full, verified picture. In short: there
is no separate user-management *service*, and — more specifically than v1's framing — today there
is exactly **one** working login (a hardcoded demo credential), not a general JWT-minting-without-
persistence flow for arbitrary users.

### 1.3 Ingestion & scaling efficiency (carried forward from v2, verified accurate)

- **Static asset pool:** the system tracks a curated, static universe of **~160 core assets**
  (Yahoo Finance tickers — US/Indian equities, crypto, FX pairs; see `README.md` "Supported
  Baseline Tickers"). Background cron jobs refresh this list; there is no dynamic scraper-thread
  scaling or unbounded external rate-limit exposure.
- **Decoupled architecture:** user portfolio composition never triggers external network calls.
  `portfolio-service` reads only from the internally-cached market data projected via Kafka.

---

## 2. Refined Backlog Items Carried Forward From v2 (verified, lightly corrected)

### Item A: Observability & Application Insights Integration *(v2 Item #3 — corresponds to v1's "E2E Tracing & Admin Dashboard")*

- **Status:** Partially done. `ROADMAP.md` confirms OpenTelemetry instrumentation is already wired
  across all four services (W3C propagation, OTLP exporters, Kafka producer/consumer observation,
  verified HTTP `traceparent` continuity across the reactive gateway boundary via
  `HttpTraceContextPropagationIT`) — but **OTLP export is gated off by default**
  (`management.tracing.export.enabled` / `management.otlp.metrics.export.enabled` both default
  `false`), and no Application Insights / Grafana sink is wired for the live Azure cloud yet.
- **Two concrete remaining gaps per `ROADMAP.md`:** (1) Kafka producer→consumer trace-ID
  continuity is not yet verified end-to-end (`PropagatingSenderTracingObservationHandler` under
  `@SpringBootTest` is still pending); (2) no OTLP collector is deployed for the active Azure
  cloud — ACA currently exports only container logs to Log Analytics.
- **Cost mitigation (from v2, unchanged):** Azure Monitor's 5 GB/month free tier plus a mandated
  0.1 GB/day hard cap in the workspace configuration.
- This item is **not** missing from v2 — it is v1's "E2E Tracing & Admin Dashboard" under a
  different name. No further action needed here beyond noting the reconciliation.

### Item B: User-Defined Portfolio Composition & Asset Picker *(v2 Item #5)*

- **Status:** Not started. No `AssetPicker` component, `/api/assets` or `/api/v1/assets` endpoint,
  or related code exists anywhere in `frontend/` or `portfolio-service/` (verified by search).
- The proposed relational schema (`Asset` global table + `Portfolio_Item` join + `Portfolio`) and
  the `GET /api/v1/assets` / `POST /api/v1/portfolios` contracts from v2 are a **target design**
  for this not-yet-built feature — they do not describe the current schema (see note below) and
  are carried forward unchanged as the design proposal.
- **Correction of an implicit assumption:** the *current* schema (`V1__Initial_Schema.sql`) does
  not use a normalized `Asset` table with a numeric ID at all — `asset_holdings.asset_ticker` is a
  plain `VARCHAR(20)` and `market_prices` is keyed directly by `ticker`. v2's proposed schema is a
  reasonable target once this item is implemented, not a description of today's tables.

### Item C: Custom Asset & Portfolio Management *(from v1 — distinct from Item B, not covered by v2)*

- **Status:** Not started (verified — zero matches for `customAsset`/"custom asset" anywhere).
- **Distinction from Item B:** Item B lets a user pick an arbitrary *subset* of the existing
  curated ~160-asset universe. This item would let a user define assets **outside** that universe
  entirely (e.g., real estate, private equity, unlisted tokens) — a materially larger scope
  (no ticker, no automatic pricing source, no catalog membership).
  Carried forward from v1 verbatim on cost risk: allowing `insight-service`'s LLM pipeline to
  reason about free-text custom assets risks bypassing the catalog-grounded validation that
  currently bounds it (the pipeline's correctness-property tests, P1–P8, assume a catalog-bounded
  universe — see `ROADMAP.md` Phase 3), which could raise both hallucination rates and Azure
  OpenAI token costs.
- Kept as **lowest priority** — see the matrix in Section 5.

---

## 3. New: User Experience & Identity *(restored from v1, updated with verified current state)*

### 3.1 Identity Management & New User Signup — **expanded separately**

v1 framed this as "transition from stateless, edge-only JWT minting to a persisted user model."
That framing is now out of date in a specific, important way: a persisted user model
(Better Auth, backed by Postgres) **already exists in the codebase** — but it is not reachable
from the live application, and the login flow that *is* reachable supports exactly one hardcoded
credential pair, not a general user base. This is a more specific and more actionable gap than v1
described. Full detail, code citations, and the deployment constraint that explains *why* it's
stuck (the frontend is a static export with no Next.js server at runtime) are in
[`new-user-signup-profile-spec.md`](./new-user-signup-profile-spec.md).

### 3.2 User Profiles & Personalization Settings *(User Settings — not one of the two items being deep-expanded, grounded here for accuracy)*

Current state, verified against the code:

- The Settings page (`frontend/src/app/(dashboard)/settings/page.tsx`) is a placeholder: "Application
  settings coming soon." No preference of any kind is persisted anywhere today.
- **Dark/light theming already exists** — `next-themes` (`ThemeProvider`/`ThemeToggle`) is wired
  up and working. This is a client-side-only preference; it is not attached to a user account and
  does not sync across devices. v1's ask to add theming should be read as "persist the existing
  toggle to a user profile," not "build theming from scratch."
- **Base currency is a single global setting today, not per-user.** `FxProperties.baseCurrency`
  (Spring `@ConfigurationProperties(prefix = "fx")`, defaults to `"USD"`) is one value shared by
  every portfolio in the system (`portfolio-service/src/main/java/com/wealth/portfolio/fx/FxProperties.java`).
  Making this a per-user preference requires both a UI and a schema change (a column somewhere
  that ties a user to a preferred currency, consumed by `PortfolioAnalyticsService`/`PortfolioService`
  instead of the static config value).
- **Risk tolerance does not exist anywhere** — no field, no UI, no backend concept.
- This item is intentionally **not** expanded into its own spec per the current request (only
  Production Rate-Limiting and New User Signup & Profile are being deep-expanded), but it is
  explicitly dependent on Section 3.1: a per-user settings pane needs a real, persisted per-user
  identity to attach preferences to. Prioritization reflects that dependency (Section 5).

### 3.3 Custom Asset & Portfolio Management

See Item C in Section 2 above (kept there since it's closely related to Item B and both concern
the asset/portfolio domain).

---

## 4. New: Production Rate-Limiting (Elevated Priority) — **expanded separately**

v1 asked to "finalize a production-grade, profile-aware rate-limiting story." Verified current
state: this is further along than v1's framing suggests (a real, Redis-backed, tested
implementation already exists), but it is **wired for local development and integration tests
only** — it does not run in any deployed environment today, despite production already being
connected to the same Redis instance for other reasons. `ROADMAP.md` independently tracks this as
a "high-priority TODO," which matches v1's own elevated-priority assessment.

Full detail, exact file citations, the two candidate implementation paths already identified in
`ROADMAP.md`, and the specific open questions that need a product decision (not a technical one)
are in [`production-rate-limiting-spec.md`](./production-rate-limiting-spec.md).

---

## 5. Updated Prioritization Matrix

This reconciles v1's five-row matrix with what the code actually shows today. "Ease of
Implementation" is revised where a partial implementation already exists (rate limiting) or where
a hidden dependency was found (personalization settings depend on identity).

| Feature | Source | Importance | Usability | Ease of Implementation | Priority | Rationale |
|---|---|---|---|---|---|---|
| **Production Rate-Limiting** | v1 / ROADMAP.md | High | Low (invisible to users when working) | **Medium** *(revised from v1's "High" — a tested Redis-backed limiter already exists for local/CI; the work is extending it to production profiles and choosing production-sized limits, not building it from scratch)* | **1** | Protects Azure OpenAI (`gpt-4o-mini`) spend and provides noisy-neighbor isolation; production Redis (Upstash) connectivity already exists, only the enforcement filter is missing in prod profiles. |
| **New User Signup & Profile** | v1 | High | High | **Medium-High** *(revised from v1's "Medium" — Better Auth's schema/library exist, but the live login path is single-hardcoded-credential and the frontend's static-export deployment blocks Better Auth's server routes from running; a real architectural decision is needed first, see spec)* | **2** | Foundational for any multi-user usage of the product; currently blocks everything in 3.2 and Item C. |
| **Observability & App Insights** *(= v1's "E2E Tracing & Admin Dashboard")* | v2 Item #3 | Medium | Low | Medium | **3** | OTel instrumentation already exists; remaining work is the OTLP sink/collector and Kafka trace-continuity verification. |
| **User Settings (Personalization)** | v1 | Medium | Medium | Medium *(revised from v1's "High" — blocked on 3.1; theming already exists client-side, so the remaining lift is schema + a settings UI + wiring currency/risk-tolerance once identity exists)* | **4** | Depends on Section 3.1 landing first; theming is largely reusable, currency/risk-tolerance are new. |
| **Asset Picker (curated universe)** *(v2 Item #5)* | v2 | High | High | Low | **5** | Not started; static ~160-asset universe keeps this tractable once prioritized — no external blockers found. |
| **Custom Asset & Portfolio Management** | v1 (Item C) | Medium | High | Low | **6 (Do Last)** | Same LLM-cost/catalog-validation risk v1 identified; distinct from and larger than the Asset Picker above. |

> **Implementation order (fixed, one-directional):** Implement **Production Rate-Limiting**
> (`.kiro/specs/production-rate-limiting`) end-to-end before **New User Signup & Profile**
> (`.kiro/specs/new-user-signup-profile`). Signup's auth-endpoint limiter reuses the shared
> `RedisRateLimiter` wiring and the trusted-hop resolver (`resolveTrustedHopKey` /
> `app.rate-limit.trust-xff-last-hop`) that the rate-limiting spec creates, both specs modify
> `fetchWithAuth.ts`, and rate-limiting closes a live AI-route cost exposure while signup carries
> no production risk in waiting. This ordering is a prerequisite, not a preference.

---

## 6. Sources Cited

- `README.md` — "Architectural Philosophy: Evolutionary Design", "Bounded Contexts", "Production
  Deployment — Multi-Cloud, Azure-Active"
- `ROADMAP.md` — "Current State (June 2026)", "Completed Phases", "Future Architectural Goals"
- `api-gateway/src/main/resources/{application,application-local,application-prod,application-azure,application-aws}.yml`
- `api-gateway/src/main/java/com/wealth/gateway/{GatewayRateLimitConfig,AuthController,RedisSslConfig}.java`
- `api-gateway/src/test/java/com/wealth/gateway/RateLimitingIntegrationTest.java`
- `docs/specs/redis-rate-limiting/{requirements,design,tasks}.md`
- `docs/e2e-flows/api-gateway-service-e2e.md`
- `frontend/src/lib/auth.ts`, `auth-client.ts`, `auth/{mintToken,session}.ts`, `api/fetchWithAuth{,.server}.ts`
- `frontend/src/app/(auth)/login/page.tsx`, `frontend/src/app/(dashboard)/settings/page.tsx`
- `frontend/next.config.ts`
- `portfolio-service/src/main/resources/db/migration/V1, V6, V7, V8, V9, V10__*.sql`
- `portfolio-service/src/main/java/com/wealth/portfolio/fx/FxProperties.java`
- `.kiro/specs/auth-identity-layer/`, `.kiro/specs/better-auth-migration/` (superseded historical specs, useful for context on how the current state was reached)
