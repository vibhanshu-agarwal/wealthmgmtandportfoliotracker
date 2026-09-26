# Wealth Management & Portfolio Tracker — Roadmap

**Reconciled:** 2026-09-26 UTC against source at `main@5d559478` and the accepted records linked
from the [demo preparation dashboard](docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).
This is a portfolio/demo project, not a commercial service with production customers. No fresh
cloud, database, price-feed or billing verification was performed for this reconciliation.

**Publication boundary:** this documentation grants no implementation, deployment or live-operation
authority. Push/PR and merge require owner approval. An unmerged revision is a candidate; filing
requires independent review and an owner-authorized merge into `main`.

This document outlines the strategic architectural evolution and feature expansion of the project.

The system progressed from a single deployment unit to distributed services with Azure and AWS
deployment paths. The delivered milestones below are not a claim that every capability has current
live acceptance on both clouds. The enhancements section is unscheduled future work; the project
pause starts after the remaining documentation, media and maintenance handoff work is complete.

## 📦 Current State (September 2026)

- **Architecture:** Four deployable Spring Boot services (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) and three shared libraries (`common-dto`, `common-observability`, `common-catalog`); seven Gradle modules, not seven runtime services.
- **Platform:** **Spring Boot 4.1.0 GA / Spring AI 2.0.0 GA** on **Java 21**, Jackson 3 (`tools.jackson`), Next.js 16 / React 19 frontend.
- **Live deployment:** **Azure** — Container Apps (Central India, scale-to-zero) + Static Web Apps + **Azure OpenAI** (`gpt-4o-mini`, Entra ID auth), with Upstash Redis and Aiven Kafka, fronted by Cloudflare DNS at [vibhanshu-ai-portfolio.dev](https://vibhanshu-ai-portfolio.dev/).
- **Standby cloud:** **AWS** (Lambda arm64 + CloudFront + Amazon Bedrock) is a retained, parked deployment path. Reactivation needs fresh identity, infrastructure, cost and compatibility checks; it is not an accepted immediate rollback for the current Azure build.
- **Rate limiting:** Redis-backed per-route `RedisRateLimiter` filters (standard + strict + auth tiers), fail-open behavior and `Retry-After` handling. Fail-open behavior trades availability for unthrottled traffic when Redis is unavailable.
- **Identity:** Real per-user authentication owned by the `api-gateway` — bcrypt-hashed credentials in PostgreSQL (`user_credentials`), self-service signup at `POST /api/auth/signup`, gateway-minted HS256 JWTs, and a read-only demo account enforced at the edge. There is still no separate user-management service.
- **Observability:** Accepted tracing delivery covers the four services and refresh Job, via the ACA managed OpenTelemetry agent into Application Insights. `common-observability` sanitizes export. Workspace ingestion caps and a budget alert are configured in source; neither is a guarantee of a total monthly bill ceiling.
- **Portfolio experience:** Signup creates an empty portfolio; the delivered asset picker supports versioned save, validation and conflict rejection over the active catalog. Analytics exposes valuations, FX conversion, coverage/freshness and a performance chart. Sharpe/Sortino and richer FA/TA chat are not delivered features.
- **Demo acceptance:** The post-#320 multi-user suite is accepted as `PASS_WITH_EXPECTED_DEFECTS`, not clean PASS. Rehearsal, targeted checks and cleanup are separate records. Inconclusive/unverified checks and accepted defects remain qualified in the dashboard; this roadmap does not close them.
- **Freeze documentation:** Backlog reconciliation filed through #324 (`6f1e5700`); runbook reconciliation filed through #325 (`5d559478`). This roadmap/README/v5 revision is filed only when its reviewed carrying PR merges. The media package and wider documentation/maintenance handoff remain separate.

See [enhancements v5](roadmap_enahancements_v5.md) for resumable feature statuses. Versions
[v1](roadmap_enhancements_v1.md), [v2](roadmap_enhancements_v2.md),
[v3](roadmap_enhancements_v3.md) and [v4](roadmap_enhancements_v4.md) remain historical snapshots;
their old "not started" and "next" statements are not the current work queue.

---

## ✅ Completed Phases

### Phase 1 — The Modular Monolith *(superseded)*

- **Tag:** `v1.0-modular-monolith`
- Single Spring Boot deployable, single PostgreSQL instance with logically separated schemas, and **Spring Modulith** enforcing in-process bounded contexts.
- Messaging via in-memory Spring Application Events backed by a JDBC outbox (Event Publication Registry).
- *This stage has been fully decomposed into the microservices described below; Spring Modulith is no longer a runtime dependency.*

### Phase 2 — Event-Driven Data Extraction (Market Domain) ✅

- The `market` anti-corruption layer was extracted into the standalone `market-data-service`.
- Internal Spring Events for price updates were replaced with **Apache Kafka** (`PriceUpdatedEvent` on the `market-prices` topic) to handle high-throughput, append-only market data streams.
- A **dead-letter topic** (`market-prices.DLT`) routes poison/malformed records on the `portfolio-service` consumer (`MalformedEventException` registered as non-retryable).
- *Accepted deployment baseline:* managed **Aiven** Kafka rather than Amazon MSK. Current provider pricing and resource state were not checked here.

### Phase 3 — AI Integration (Insight Domain) ✅

- The `insight` compute domain was extracted into the standalone `insight-service`, packaged as a **container image** and deployed serverlessly (AWS Lambda and, now, Azure Container Apps).
- **LLM-grounded natural-language asset resolution:** a catalog-validated, multi-step pipeline exists for resolving asset names to canonical tickers, with correctness-property tests. Broader natural-language resolution and model-text reliability were not established by the final demo checks; these remain explicitly unverified/non-blocking, not a general live correctness guarantee.
- **AI providers (pluggable via profile):**
  - `azure-ai` → `AzureOpenAiInsightService` (**Azure OpenAI `gpt-4o-mini`**, Entra ID / Managed Identity) — **active in production**.
  - `bedrock` → `BedrockAiInsightService` (**Amazon Bedrock**, Anthropic Claude Haiku) — standby AWS path.
  - default → `MockAiInsightService` (deterministic) for local development and CI.

### Multi-Cloud Expansion (Azure) ✅

- The platform retains **AWS and Azure** deployment paths under `infrastructure/terraform/{aws,azure}`, with Spring profile isolation (`aws` vs `azure`); this is not a claim of simultaneous live service.
- Azure is the **active demo** cloud; AWS is preserved as a parked alternative, not a current tested rollback path.
- Provider adapters and Spring profiles support both paths. A future switch requires fresh compatibility and operational acceptance, not only a DNS flip.

### Phase 5 — Production Rate Limiting Enforcement ✅

- **Named per-route limiter beans:** Two profile-gated (`@Profile("prod")`) `RedisRateLimiter` instances — `standardRateLimiter` (10 req/s, burst 20) for portfolio/market-data routes and `strictRateLimiter` (~10 req/min sustained, burst 5) for cost-sensitive AI routes — wired via explicit SpEL references per route, never a blanket `default-filters`.
- **Trusted-hop XFF key derivation:** Unauthenticated requests key off the right-most (ingress-appended) `X-Forwarded-For` hop when `app.rate-limit.trust-xff-last-hop=true` (prod only), preventing bucket-spoofing.
- **Fail-open semantics:** rate limiting is designed to allow unthrottled requests when Redis is unavailable; this does not guarantee that unrelated downstream dependencies are available.
- **429 response ergonomics:** A `GlobalFilter` (`RateLimitDenialResponseCustomizer`) decorates 429 responses with a `Retry-After` header and a JSON body (`{"error":"rate_limited","retryAfterSeconds":n}`).
- **Degraded-state observability:** A scheduled Redis probe (`RedisRateLimitStateLogger`) logs `[INFRA-DEGRADED]` / `[INFRA-OK]` transitions independently of request traffic.
- **Frontend handling:** `fetchWithAuthClient` throws a distinct `RateLimitError` on 429 (no session clear, no redirect to login); `ChatInterface` shows a countdown timer and disables input for the `Retry-After` duration; `MarketSummaryGrid` renders a distinguishable rate-limited card.
- **Spec:** `.kiro/specs/production-rate-limiting/` — **Changelog:** `docs/changes/CHANGES_PRODUCTION_RATE_LIMITING_2026-07-05.md`

### Phase 6 — Self-Service Signup & Per-User Authentication ✅

- **Real credential store:** The placeholder single-hardcoded-credential login (`app.auth.email` / `app.auth.password`) was replaced by a `user_credentials` table in PostgreSQL (bcrypt, cost 12), added in Flyway migrations **V14–V16** alongside `users.name` and `users.read_only`.
- **Identity moved to the gateway:** A new `com.wealth.gateway.auth` package (`AuthenticationService`, `SignupService`, `SignupValidator`, `UserCredentialRepository`) **mints** HS256 JWTs after verifying credentials — the frontend no longer mints tokens. Claims carry `email`, `name`, and a `ro` (read-only) flag.
- **Self-service signup:** `POST /api/auth/signup` provisions the user and their credential row in a single transaction and returns 201 with a JWT; the frontend gained a `/signup` page and login↔signup navigation.
- **Uniform-failure login:** credential rejection uses a uniform 401 response and a dummy bcrypt hash on unknown-email/malformed-hash paths to reduce timing differences; this is a mechanism description, not proof of indistinguishability under every deployment condition.
- **Read-only demo account:** `ReadOnlyEnforcementFilter` (order `HIGHEST_PRECEDENCE + 3`) blocks portfolio/market writes from the demo account, with an allowlist for AI routes (`/api/chat/**`, `/api/insights/generate/**`). Flyway V15 reassigns the seeded showcase portfolio to the demo account so a recruiter login lands on populated data.
- **Auth-endpoint throttling:** `AuthRateLimitFilter` (order `HIGHEST_PRECEDENCE + 1`) throttles login/signup through a shared `Auth_Bucket` via a third named `authRateLimiter` bean, since `/api/auth/**` is a controller endpoint rather than a proxied route.
- **Graceful degradation:** `GatewayAuthDataConfig` is gated on `spring.datasource.url`; `GatewayAuthFallbackAutoConfiguration` supplies fail-closed 503 beans so a profile without a datasource cannot prevent the whole gateway from booting.
- **Better Auth retired:** V16 drops the `ba_*` tables; the frontend dependency, config, dev-seed script, and an orphaned chat Server Action were all removed.
- **Spec:** `.kiro/specs/new-user-signup-profile/` — **Changelog:** `docs/changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md`

### Phase 7 — Observability & Application Insights ✅

- **Accepted trace export:** delivery routed OTLP traces from the four services and refresh Job through the ACA managed agent into workspace-based Application Insights. The recorded design chose that path to preserve the existing Spring/Micrometer instrumentation; this is not a new provider-capability or live-ingestion check.
- **Vendor-neutral telemetry boundary:** OTLP instrumentation/export does not require an Azure-specific telemetry SDK or Java agent. Cloud-specific model/credential adapters still exist; the telemetry design alone does not establish current AWS serving compatibility.
- **Span redaction (`common-observability`, new module):** a sanitizing `SpanExporter` replaces every `ExceptionEventData` with a plain `EventData` — necessary because `ExceptionEventData` exposes the original `Throwable` via `getException()`, so redacting only its attributes would leave message and stack trace reachable. Paired with a Micrometer `ObservationFilter` for deny-set keys, query-string stripping, and normalized route templates.
- **Kafka producer→consumer trace continuity** — completes **Task 11.2** of `.kiro/specs/springboot-41-springai-2-migration/`, the last incomplete sub-task of that spec. Root-caused as a test-fixture defect, not a framework gap: both pre-existing tests hand-built an unobserved `KafkaTemplate` instead of using the auto-configured bean. Verified live at **159/159** and **158/158** dual-consumer joins on a shared `OperationId`.
- **Cost controls:** source configures 0.023 GB/day ingestion caps on both workspaces and a ₹1100/month budget alert (Actual 70% / Forecasted 100%). The historical allowance-independence calculation passed for its recorded meter rate/forecast; a fresh cost assessment must use current inputs. Caps/alerts do not enforce a total spend ceiling or remove non-telemetry costs during a project pause.
- **AzAPI provider added:** source uses an `azapi_update_resource` pinned to `Microsoft.App/managedEnvironments@2025-10-02-preview` for the ACA managed environment's `openTelemetryConfiguration`. The workspaces and Application Insights resource are AzureRM-provisioned; later native-provider support should be reassessed at restart.
- **Accepted preview exposure at delivery:** traces-only managed-agent limitations and local-authentication ingestion were recorded. The native Azure Monitor OTLP GA and Entra-authenticated-ingestion exit criteria remain separate; reassess their availability at restart rather than assuming this historical provider status is current.
- **Spec:** `.kiro/specs/observability-app-insights/` — **Changelog:** `docs/changes/CHANGES_OBSERVABILITY_APP_INSIGHTS_2026-08-15.md` — **Runbook:** `docs/runbooks/OBSERVABILITY.md`

---

### Later Delivery — Supported Catalog, Asset Picker and Demo Analytics ✅

- **Supported catalog:** root `config/seed-tickers.json` is packaged by the consuming services;
  `common-catalog` provides catalog behavior. Source currently contains 159 ACTIVE entries and one
  DEPRECATED entry. Catalog membership does not guarantee that Yahoo returns a fresh price.
- **Composition:** `/api/assets`, the picker/edit dialog, quantity validation, versioned composition
  writes, isolation and demo reset were delivered through the Spec A/B1/B2 work. Do not reimplement
  v4's obsolete "no picker or endpoint" gap.
- **Analytics/UI:** the #320 fixes and later demo evidence cover their stated scope. Quote currencies,
  portfolio base-currency values, freshness/partial coverage, chart deduplication and chat source
  wording are delivered; natural-language/model reliability and named fallback/FX edges remain
  unverified or accepted limitations, not universally tested features.
- **Evidence:** [demo dashboard](docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md),
  [supported-asset ledger](.kiro/specs/supported-asset-integrity/tasks.md) and
  [composition master plan](docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md). Roadmap milestones and
  demo-preparation phase numbers are separate numbering systems.

---

## 🕒 Deferred Product Enhancements — Owner Requests (2026-09-26)

All three requests are **DEFERRED / NOT STARTED**, for a much later revisit—months or a year away.
They have no committed priority, delivery date, detailed design or implementation authorization.

- **R1 — Portfolio Sharpe Ratio and Sortino Ratio:** add risk-adjusted performance analysis for
  each user's portfolio, distinguishing total volatility from downside risk. At restart, first
  assess usable return history and agree lookback, annualization, risk-free/target return, FX and
  cash-flow/holdings-history conventions; incomplete data must not produce misleading ratios.
- **R2 — More intelligent FA/TA chatbot:** move beyond a ticker snapshot to selectable
  fundamental analysis (FA) and technical analysis (TA), with useful explanations and portfolio
  context where available. Revisit data-source coverage, price/indicator versus model attribution,
  freshness and fallback behavior before selecting indicators or claiming advice-quality output.
- **R3 — More engaging charts and analysis:** explore complementary views such as drawdown,
  benchmark comparison, risk/return and allocation/concentration analysis, with interactive
  exploration. Choose a small useful set after reviewing data availability and user value; these
  are examples to explore, not promised charts.

## 🎯 Future Architectural Goals

- **AI service contract evolution:** `insight-service` already exists. Explore a typed, low-latency boundary (e.g. gRPC) or a managed agent service only when justified by the richer analysis use cases; this is not a missing fourth service or a committed redesign.
- **Multi-Provider Market Data Aggregation:** The current baseline is **Yahoo Finance** (`external-market-data.provider: yahoo`). Add institutional-grade providers (e.g., **Alpha Vantage**, **Polygon.io**) behind an Adapter/Strategy abstraction layered on `ExternalMarketDataClient` to enable high-availability failover, cross-provider price reconciliation/anomaly detection, and vendor-lock-in avoidance.
- **Advanced AI-Driven Wealth Workflows:** Move the AI layer from a conversational assistant toward an autonomous financial agent — predictive rebalancing simulations, real-time sentiment analysis over streaming market/news feeds, and tax-loss-harvesting recommendations aligned with user-specific risk and jurisdictional guardrails.
- **User Settings:** persisted personalization/base-currency/risk preferences remain future work; the Settings page is still a placeholder. Persisted identity is available, but that is not delivery of settings.
- **Custom Assets and Multiple-Portfolio Workflows:** future expansion beyond the active catalog and current composition flow. Reassess coverage, ownership and valuation semantics before permitting unsupported assets.
- **Infrastructure Security Hardening:** Apply least-privilege at the database layer by migrating from the owner role to a scoped `app_user` role (`CONNECT`, `SELECT`, `INSERT`, `UPDATE`, `DELETE` only).

These existing goals remain open/unscheduled, not the automatic next implementation queue. The
[audited backlog](docs/todos/backlog/README.md) owns exact engineering residuals—including provider
risk, idle Kafka consumer policy, corporate-action decisions and verification gaps. A delivered
feature may still have open follow-ups; accepted demo debt is not "fixed" or "no longer relevant".

## Restart and Freeze Handoff

Before picking up feature development, read the dashboard, v5 and the backlog index; verify the
actual deployed/configuration baseline under approved scope and revalidate dependencies/data
contracts. Do not replay historical repair packets or reuse cleaned-up certification accounts.
Use [current operations](docs/runbooks/CURRENT_OPERATIONS.md) for the operational boundaries.
The LinkedIn/resume/PPT/video package still requires the owner/Claude/Codex brainstorming session
before drafting. This roadmap revision does not declare the entire demo-preparation/freeze plan complete.
