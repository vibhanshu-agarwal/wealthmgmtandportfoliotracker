This document outlines the strategic architectural evolution and feature expansion of the Wealth Management & Portfolio Tracker.

The system has deliberately progressed from a single deployment unit to a distributed, multi-cloud architecture. Phases 1–3 (and the multi-cloud expansion) are **implemented and live**; the remaining items below are forward-looking.

## 📦 Current State (June 2026)

- **Architecture:** Four independently-deployable Spring Boot microservices (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) plus a shared `common-dto` contract module.
- **Platform:** **Spring Boot 4.1.0 GA / Spring AI 2.0.0 GA** on **Java 21**, Jackson 3 (`tools.jackson`), Next.js 16 / React 19 frontend.
- **Live deployment:** **Azure** — Container Apps (Central India, scale-to-zero) + Static Web Apps + **Azure OpenAI** (`gpt-4o-mini`, Entra ID auth), with Upstash Redis and Aiven Kafka, fronted by Cloudflare DNS at [vibhanshu-ai-portfolio.dev](https://vibhanshu-ai-portfolio.dev/).
- **Standby cloud:** **AWS** (Lambda arm64 + CloudFront + Amazon Bedrock) — fully provisioned via Terraform but soft-disabled at the DNS layer.
- **Observability:** OpenTelemetry instrumentation across all services (OTLP export feature-flagged off by default).

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

---

## 🎯 Future Architectural Goals

- **Dedicated AI Microservice (typed, low-latency contract):** Evolve the AI inference layer toward a dedicated high-performance service exposing a strongly typed, low-latency interface (e.g., **gRPC** or an equivalent service-mesh abstraction), while preserving strict resource isolation between transactional workloads and AI compute. A **Microsoft AI Foundry**-backed agent service is a candidate direction.
- **Production Rate Limiting Strategy:** Finalise a production-grade, profile-aware rate-limiting story for the active cloud. Options under evaluation include hardening the Redis-backed `RequestRateLimiter` with cloud-profile `default-filters`, or delegating coarse-grained throttling to a platform-native mechanism (AWS API Gateway usage plans / an Azure-side equivalent) so the backing store is swappable by config alone. *(Tracked as a high-priority TODO.)*
- **Multi-Provider Market Data Aggregation:** The current baseline is **Yahoo Finance** (`external-market-data.provider: yahoo`). Add institutional-grade providers (e.g., **Alpha Vantage**, **Polygon.io**) behind an Adapter/Strategy abstraction layered on `ExternalMarketDataClient` to enable high-availability failover, cross-provider price reconciliation/anomaly detection, and vendor-lock-in avoidance.
- **Advanced AI-Driven Wealth Workflows:** Move the AI layer from a conversational assistant toward an autonomous financial agent — predictive rebalancing simulations, real-time sentiment analysis over streaming market/news feeds, and tax-loss-harvesting recommendations aligned with user-specific risk and jurisdictional guardrails.
- **End-to-End Distributed Tracing:** OpenTelemetry instrumentation is already wired across all four services — W3C propagation, OTLP trace/metrics exporters (gated off by default), Kafka producer/consumer observation, and verified HTTP `traceparent` continuity across the reactive gateway boundary (`HttpTraceContextPropagationIT`). Two gaps remain: (1) **Kafka producer→consumer trace-ID continuity** — listener observation fires at consume time, but end-to-end continuity (consumer span sharing the producer's trace-ID, no new root span) is still deferred pending `PropagatingSenderTracingObservationHandler` verification under `@SpringBootTest`; and (2) **an OTLP collector for the active (Azure) cloud** — the ACA stack currently exports only container logs to a Log Analytics workspace, so trace/metric export to a real backend still needs to be wired and toggled on in production.
- **Infrastructure Security Hardening:** Apply least-privilege at the database layer by migrating from the owner role to a scoped `app_user` role (`CONNECT`, `SELECT`, `INSERT`, `UPDATE`, `DELETE` only).
