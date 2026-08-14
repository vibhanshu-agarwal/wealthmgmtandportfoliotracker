This document outlines the strategic architectural evolution and feature expansion of the Wealth Management & Portfolio Tracker.

The system has deliberately progressed from a single deployment unit to a distributed, multi-cloud architecture. Phases 1–3, the multi-cloud expansion, and Phases 5–6 are **implemented and live**; the remaining items below are forward-looking.

## 📦 Current State (August 2026)

- **Architecture:** Four independently-deployable Spring Boot microservices (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) plus a shared `common-dto` contract module.
- **Platform:** **Spring Boot 4.1.0 GA / Spring AI 2.0.0 GA** on **Java 21**, Jackson 3 (`tools.jackson`), Next.js 16 / React 19 frontend.
- **Live deployment:** **Azure** — Container Apps (Central India, scale-to-zero) + Static Web Apps + **Azure OpenAI** (`gpt-4o-mini`, Entra ID auth), with Upstash Redis and Aiven Kafka, fronted by Cloudflare DNS at [vibhanshu-ai-portfolio.dev](https://vibhanshu-ai-portfolio.dev/).
- **Standby cloud:** **AWS** (Lambda arm64 + CloudFront + Amazon Bedrock) — fully provisioned via Terraform but soft-disabled at the DNS layer.
- **Rate limiting:** Production-grade Redis-backed rate limiting enforced across all production profiles via per-route `RedisRateLimiter` filters (standard + strict + auth tiers), with fail-open semantics and `Retry-After` response ergonomics.
- **Identity:** Real per-user authentication owned by the `api-gateway` — bcrypt-hashed credentials in PostgreSQL (`user_credentials`), self-service signup at `POST /api/auth/signup`, gateway-minted HS256 JWTs, and a read-only demo account enforced at the edge. There is still no separate user-management service.
- **Observability:** OpenTelemetry instrumentation across all services (OTLP export feature-flagged off by default). A full implementation spec for turning export on — managed OTel agent, Application Insights sink, cost-bounded ingestion caps, and span redaction — is complete at `.kiro/specs/observability-app-insights/`, not yet implemented.

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
- *Deployment note:* in production, Kafka runs as a managed **Aiven** cluster (free tier) rather than Amazon MSK, keeping the cost footprint near zero.

### Phase 3 — AI Integration (Insight Domain) ✅

- The `insight` compute domain was extracted into the standalone `insight-service`, packaged as a **container image** and deployed serverlessly (AWS Lambda and, now, Azure Container Apps).
- **LLM-grounded natural-language asset resolution:** a catalog-validated, multi-step pipeline resolves free-text asset names to canonical tickers, with correctness-property tests (P1–P8) guarding catalog-bounded, Redis-only, never-empty, and determinism invariants.
- **AI providers (pluggable via profile):**
  - `azure-ai` → `AzureOpenAiInsightService` (**Azure OpenAI `gpt-4o-mini`**, Entra ID / Managed Identity) — **active in production**.
  - `bedrock` → `BedrockAiInsightService` (**Amazon Bedrock**, Anthropic Claude Haiku) — standby AWS path.
  - default → `MockAiInsightService` (deterministic) for local development and CI.

### Multi-Cloud Expansion (Azure) ✅

- The platform now runs across **AWS and Azure** with a single Terraform-managed codebase under `infrastructure/terraform/{aws,azure}` and Spring profile isolation (`aws` vs `azure`).
- Azure is the **active** cloud; AWS is preserved as a soft-disabled standby, reducing provider-concentration risk while keeping a tested rollback path.
- Cloud selection is config/DNS-driven — no domain logic changes are required to switch providers.

### Phase 5 — Production Rate Limiting Enforcement ✅

- **Named per-route limiter beans:** Two profile-gated (`@Profile("prod")`) `RedisRateLimiter` instances — `standardRateLimiter` (10 req/s, burst 20) for portfolio/market-data routes and `strictRateLimiter` (~10 req/min sustained, burst 5) for cost-sensitive AI routes — wired via explicit SpEL references per route, never a blanket `default-filters`.
- **Trusted-hop XFF key derivation:** Unauthenticated requests key off the right-most (ingress-appended) `X-Forwarded-For` hop when `app.rate-limit.trust-xff-last-hop=true` (prod only), preventing bucket-spoofing.
- **Fail-open semantics:** Redis unreachability never blocks gateway startup or rejects traffic — requests pass through unthrottled during outages.
- **429 response ergonomics:** A `GlobalFilter` (`RateLimitDenialResponseCustomizer`) decorates 429 responses with a `Retry-After` header and a JSON body (`{"error":"rate_limited","retryAfterSeconds":n}`).
- **Degraded-state observability:** A scheduled Redis probe (`RedisRateLimitStateLogger`) logs `[INFRA-DEGRADED]` / `[INFRA-OK]` transitions independently of request traffic.
- **Frontend handling:** `fetchWithAuthClient` throws a distinct `RateLimitError` on 429 (no session clear, no redirect to login); `ChatInterface` shows a countdown timer and disables input for the `Retry-After` duration; `MarketSummaryGrid` renders a distinguishable rate-limited card.
- **Spec:** `.kiro/specs/production-rate-limiting/` — **Changelog:** `docs/changes/CHANGES_PRODUCTION_RATE_LIMITING_2026-07-05.md`

### Phase 6 — Self-Service Signup & Per-User Authentication ✅

- **Real credential store:** The placeholder single-hardcoded-credential login (`app.auth.email` / `app.auth.password`) was replaced by a `user_credentials` table in PostgreSQL (bcrypt, cost 12), added in Flyway migrations **V14–V16** alongside `users.name` and `users.read_only`.
- **Identity moved to the gateway:** A new `com.wealth.gateway.auth` package (`AuthenticationService`, `SignupService`, `SignupValidator`, `UserCredentialRepository`) **mints** HS256 JWTs after verifying credentials — the frontend no longer mints tokens. Claims carry `email`, `name`, and a `ro` (read-only) flag.
- **Self-service signup:** `POST /api/auth/signup` provisions the user and their credential row in a single transaction and returns 201 with a JWT; the frontend gained a `/signup` page and login↔signup navigation.
- **Uniform-failure login:** `POST /api/auth/login` returns a byte-identical 401 for every failure reason, and burns equivalent CPU against a fixed dummy bcrypt hash on the "unknown email" and "malformed stored hash" paths so no branch is distinguishable by timing.
- **Read-only demo account:** `ReadOnlyEnforcementFilter` (order `HIGHEST_PRECEDENCE + 3`) blocks portfolio/market writes from the demo account, with an allowlist for AI routes (`/api/chat/**`, `/api/insights/generate/**`). Flyway V15 reassigns the seeded showcase portfolio to the demo account so a recruiter login lands on populated data.
- **Auth-endpoint throttling:** `AuthRateLimitFilter` (order `HIGHEST_PRECEDENCE + 1`) throttles login/signup through a shared `Auth_Bucket` via a third named `authRateLimiter` bean, since `/api/auth/**` is a controller endpoint rather than a proxied route.
- **Graceful degradation:** `GatewayAuthDataConfig` is gated on `spring.datasource.url`; `GatewayAuthFallbackAutoConfiguration` supplies fail-closed 503 beans so a profile without a datasource cannot prevent the whole gateway from booting.
- **Better Auth retired:** V16 drops the `ba_*` tables; the frontend dependency, config, dev-seed script, and an orphaned chat Server Action were all removed.
- **Spec:** `.kiro/specs/new-user-signup-profile/` — **Changelog:** `docs/changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md`

---

## 🎯 Future Architectural Goals

- **Dedicated AI Microservice (typed, low-latency contract):** Evolve the AI inference layer toward a dedicated high-performance service exposing a strongly typed, low-latency interface (e.g., **gRPC** or an equivalent service-mesh abstraction), while preserving strict resource isolation between transactional workloads and AI compute. A **Microsoft AI Foundry**-backed agent service is a candidate direction.
- **Multi-Provider Market Data Aggregation:** The current baseline is **Yahoo Finance** (`external-market-data.provider: yahoo`). Add institutional-grade providers (e.g., **Alpha Vantage**, **Polygon.io**) behind an Adapter/Strategy abstraction layered on `ExternalMarketDataClient` to enable high-availability failover, cross-provider price reconciliation/anomaly detection, and vendor-lock-in avoidance.
- **Advanced AI-Driven Wealth Workflows:** Move the AI layer from a conversational assistant toward an autonomous financial agent — predictive rebalancing simulations, real-time sentiment analysis over streaming market/news feeds, and tax-loss-harvesting recommendations aligned with user-specific risk and jurisdictional guardrails.
- **End-to-End Distributed Tracing — spec complete, implementation pending:** OpenTelemetry instrumentation is already wired across all four services — W3C propagation, OTLP trace/metrics exporters (gated off by default), Kafka producer/consumer observation, and verified HTTP `traceparent` continuity across the reactive gateway boundary (`HttpTraceContextPropagationIT`). A full spec addressing both previously-tracked gaps is at `.kiro/specs/observability-app-insights/`, entirely unimplemented so far — `tasks.md` has not been executed: (1) **Kafka producer→consumer trace-ID continuity** — root-caused as a test fixture defect (both existing tests hand-build an unobserved `KafkaTemplate` instead of using the auto-configured bean, not a framework limitation); the fix is planned (`tasks.md` 7.1–7.7) but not yet applied, so Task 11.2 of `.kiro/specs/springboot-41-springai-2-migration/` remains open; and (2) **a telemetry sink for the active (Azure) cloud**, planned to route through the ACA managed OpenTelemetry agent (chosen over the GA Application Insights Java agent to keep the existing Spring/Micrometer instrumentation load-bearing) into a new, dedicated Application Insights workspace — the workspace and the Application Insights resource itself would be provisioned via AzureRM as usual; a newly-added AzAPI Terraform provider is needed only to patch the ACA managed environment's OpenTelemetry-agent configuration to point at it, since AzureRM does not yet model that block. Would be sized to a ₹1100/month total ceiling for `wealth-azure-prod-rg` with allowance-independent ingestion caps and a new Cost Management budget alert, plus a `common-observability` module that would redact span content before export.
- **Infrastructure Security Hardening:** Apply least-privilege at the database layer by migrating from the owner role to a scoped `app_user` role (`CONNECT`, `SELECT`, `INSERT`, `UPDATE`, `DELETE` only).
