# Wealth Management & Portfolio Tracker

[![Live](https://img.shields.io/badge/Live-vibhanshu--ai--portfolio.dev-2ea44f.svg)](https://vibhanshu-ai-portfolio.dev/)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring_AI-2.0_GA-6DB33F.svg)](https://spring.io/projects/spring-ai)
[![Spring Cloud Gateway](https://img.shields.io/badge/Spring_Cloud-Gateway-blue.svg)](https://spring.io/projects/spring-cloud-gateway)
[![Next.js](https://img.shields.io/badge/Next.js-16-black.svg)](https://nextjs.org/)
[![React](https://img.shields.io/badge/React-19-61DAFB.svg)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-336791.svg)](https://www.postgresql.org/)
[![MongoDB](https://img.shields.io/badge/MongoDB-47A248.svg)](https://www.mongodb.com/)
[![Kafka](https://img.shields.io/badge/Apache-Kafka-231F20.svg)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-Cache-D82C20.svg)](https://redis.io/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-Retry%20Policies-5C6BC0.svg)](https://resilience4j.readme.io/)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-Tracing-425CC7.svg)](https://opentelemetry.io/)
[![Terraform](https://img.shields.io/badge/Terraform-Multi--Cloud%20IaC-7B42BC.svg)](https://www.terraform.io/)
[![Azure Container Apps](https://img.shields.io/badge/Azure-Container%20Apps-0078D4.svg)](https://azure.microsoft.com/en-us/products/container-apps)
[![Azure OpenAI](https://img.shields.io/badge/Azure-OpenAI-0078D4.svg)](https://azure.microsoft.com/en-us/products/ai-services/openai-service)
[![AWS Lambda](https://img.shields.io/badge/AWS%20Lambda-arm64%20(standby)-FF9900.svg)](https://aws.amazon.com/lambda/)

A portfolio/demo application demonstrating distributed Java services, scheduled market-data
ingestion, user-owned holdings and AI-assisted market insights. It is not a commercial investment
service; displayed data is delayed/stored and the chat is not investment advice.

🌐 **Live demo:** [vibhanshu-ai-portfolio.dev](https://vibhanshu-ai-portfolio.dev/) — running on Azure Container Apps + Azure Static Web Apps, with Azure OpenAI powering the AI Insights experience.

**Demo validation:** the final-build desktop suite was accepted as
`PASS_WITH_EXPECTED_DEFECTS`, not clean PASS. Known limitations and evidence gaps are documented in the
[demo status dashboard](docs/plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).

**Navigation:** [ROADMAP](ROADMAP.md) · [enhancements v5](roadmap_enhancements_v5.md) ·
[audited backlog](docs/todos/backlog/README.md) · [runbooks](docs/runbooks/README.md) ·
[current operations/restart](docs/runbooks/CURRENT_OPERATIONS.md).

## Delivered Portfolio Experience

- Gateway-owned signup/login and a separate read-only showcase account; new users start with an
  empty portfolio and use the delivered asset picker/Edit Holdings flow to compose it.
- Catalog-backed selection, quantity validation, version/conflict handling and user isolation.
- Portfolio valuation and analytics with quote/base-currency distinctions, freshness and partial
  coverage, holdings/performance views and a responsive shared shell.
- Ticker-oriented chat grounded in stored market data with sentiment-source wording. Replies may
  be cached or fall back; a displayed model label does not prove a fresh model call.

Sharpe/Sortino ratios, richer FA/TA chat and additional exploratory charts are **deferred ideas**,
not current capabilities. The evidence covers the accepted demo scope, not every mobile-width,
fallback, currency-pair or model-resolution edge.

## 🧱 Enterprise Resilience & Event-Driven Data

- **Background Market Data Ingestion:** Market prices are fetched in the background from delayed external providers (e.g., Yahoo Finance) via a hardened `ExternalMarketDataClient`. All outbound calls are wrapped with **Resilience4j** retry policies to guard against 429 rate limits, 5xx outages, and transient network failures.
- **Kafka-Backed Price Propagation:** Fresh prices are published as `PriceUpdatedEvent` messages on **Kafka**, which in turn hydrate downstream services (like `insight-service`) and their **Redis** caches without coupling user requests to external APIs.
- **Poison-Message Handling (DLT):** The `portfolio-service` consumer registers `MalformedEventException` as non-retryable and routes poison/malformed records to a dead-letter topic (`market-prices.DLT`) via Spring Kafka's `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, so a single bad event never stalls the consumer.
- **Stored-data degradation:** valuation/market-summary reads use internal PostgreSQL projections, MongoDB and Redis rather than fetching Yahoo prices on each user request. Missing/stale prices and partial coverage remain visible. This does not guarantee availability of every dependency; chat can still wait on an LLM and fall back.
- **Gateway Rate Limiting:** Redis-backed per-route token buckets: standard routes (10 req/s, burst 20), cost-sensitive AI routes (~10 req/min, burst 5), and a separate auth-endpoint tier. Rate limiting is designed to fail open when Redis is unavailable, with the resulting cost exposure. 429 responses carry `Retry-After`; frontend handling distinguishes throttling from session expiry. Trusted-hop XFF derivation assumes the configured ingress topology.
- **Distributed Tracing:** vendor-neutral OTLP export through the ACA managed agent into Application Insights; the accepted delivery verified gateway and Kafka producer/consumer trace continuity. `common-observability` sanitizes spans and uses bounded/non-blocking export behavior. Workspace caps and budget alerts are cost controls, not a total bill ceiling. See the [observability runbook](docs/runbooks/OBSERVABILITY.md) for source scope and maintenance limitations.

## 🏗️ Architectural Philosophy: Evolutionary Design

This repository demonstrates an **Evolutionary Architecture** approach.

The system started as a modular monolith (Spring Modulith, single deployable, JDBC outbox) and
is now a **seven-module Gradle build**: four deployable services and three shared libraries.

| Module | Role |
| ------ | ---- |
| `api-gateway` | Spring Cloud Gateway (WebFlux) — signup/login, JWT minting/validation, origin verification, rate limiting, read-only enforcement and routing |
| `portfolio-service` | Portfolio domain (PostgreSQL + Flyway) — holdings, valuations, analytics, FX conversion, Kafka projection of `market-prices`, DLT handling |
| `market-data-service` | Market ingestion (MongoDB) — pulls from Yahoo Finance, persists snapshots, publishes `PriceUpdatedEvent` to Kafka |
| `insight-service` | AI insights (Redis + Azure OpenAI / Bedrock / mock) — chat, market summary, LLM-grounded natural-language asset resolution |
| `common-dto` | Shared DTOs, event contracts (`PriceUpdatedEvent`), truststore extractor |
| `common-observability` | Shared trace/observation sanitization and export configuration; not a deployed service |
| `common-catalog` | Shared supported-asset catalog model/loading/validation; not a deployed service |

Services are extracted into independent deployable units only when their scaling profiles or deployment lifecycles explicitly demand it — the market and insight domains were the first to warrant extraction.

### 🗺️ Bounded Contexts

The system is divided into distinct business domains, each owning its top-level package and its own datastore:

1. **`com.wealth.portfolio` (Core Domain):** Manages holdings, calculates valuations from stored price/FX data, and handles transactional composition updates. Backed by PostgreSQL and Flyway migrations.
2. **`com.wealth.market` (Anti-Corruption Layer):** Ingests, normalizes, and broadcasts pricing data from external market APIs. Backed by MongoDB for flexible tick/snapshot storage.
3. **`com.wealth.insight` (Compute Domain):** Ticker-oriented chat, market summaries and AI insight adapters, fed by Kafka and Redis. Source includes catalog-grounded natural-language resolution and an advisor path; broader portfolio-aware FA/TA conversation is future work, not established by the demo evidence.

Identity is handled at the edge: the `api-gateway` owns login and self-service signup, verifies bcrypt-hashed credentials against PostgreSQL, mints the HS256 JWT, and validates it on protected subsequent requests before routing — there is no separate user-management service. The showcase's `ro` claim restricts writes through `ReadOnlyEnforcementFilter`, with explicit exceptions for holdings replacement and its own portfolio reset; it is not a blanket ban on saving.

### 🛡️ Enforcing Boundaries

Now that the domains are physically separated, boundaries are enforced structurally rather than by in-process module verification:

- **Domain storage boundaries:** portfolio uses PostgreSQL, market ingestion uses MongoDB and insight caches use Redis. Gateway identity is an explicit PostgreSQL-sharing exception: login reads credentials and signup writes users/credentials/portfolios transactionally. The gateway also uses Redis for rate limiting; there are no cross-service JPA relationships.
- **Contract-first events:** All inter-service event contracts live in `common-dto` and are pinned with wire-contract tests on both producer and consumer sides (`PriceUpdatedEventProducerWireContractTest`, `PriceUpdatedEventConsumerPathTest`), plus a Testcontainers producer→consumer round-trip (`PriceUpdatedEventKafkaRoundTripIT`).
- **Provider isolation:** domain behavior uses Spring abstractions; cloud-specific credentials/model adapters and deployment profiles live at the integration boundary. Retaining both paths does not establish current serving parity or authorize a cloud switch.

## ☁️ Demo Deployment — Azure Active, AWS Parked

Terraform infrastructure and cloud-specific workflows are retained for both providers; the
legacy AWS CDK under `infrastructure/lib/` is historical. Azure is the accepted demo-serving path.
"Production" in workflow/resource names refers to this demo environment, not a commercial SLA.

### 🟢 Azure — Active (Live)

The [Azure demo](https://vibhanshu-ai-portfolio.dev/) uses the following deployment architecture.

- **Compute:** All four Spring Boot services run as **Azure Container Apps (ACA)** in Central India, scale-to-zero (`min_replicas = 0`) to stay within budget. The internal services listen on port 8080; the `api-gateway` is the only externally-reachable app.
- **Frontend:** The Next.js app is statically exported and hosted on **Azure Static Web Apps** (Free tier).
- **AI:** **Azure OpenAI** (`gpt-4o-mini` deployment) via the consolidated Spring AI `spring-ai-starter-model-openai` starter, authenticated with **Entra ID / Managed Identity** (`DefaultAzureCredential`) rather than static keys. Activated by the `azure-ai` profile.
- **Managed dependencies:** **Upstash** (Redis) and **Aiven** (Kafka) free tiers; topic auto-creation and TLS are wired for both.
- **DNS / Edge:** **Cloudflare** holds the zone — apex/`www` flatten to Static Web Apps and `api.` points at the ACA `api-gateway`. TLS is managed by Azure on both hostnames.
- **CI/CD:** `deploy.yml` is the guarded manual dispatch entry point; Azure deploy workflows are reusable children. Terraform structural `plan`, live-state `remote-plan` and `apply` are distinct; merge does not deploy or apply infrastructure. Live synthetics are manual-only, not hourly/daily automatic checks; `ci-verification.yml` validates source on push/PR. Use [current operations](docs/runbooks/CURRENT_OPERATIONS.md) for exact identity/mode and approval boundaries.

### 🟡 AWS — Soft-Disabled Standby

The historical AWS path remains in source and was soft-disabled in favor of Azure. It is not
a current ready rollback target: reactivation requires checking resources, DNS, secrets, model
access and compatibility. Deploys enter through `deploy.yml`, not its reusable AWS child.

- **Compute:** All four services packaged as container images and deployed as **AWS Lambda on arm64 / Graviton2**, fronted by the **AWS Lambda Web Adapter** so each Spring Boot app runs unmodified.
- **Edge:** A single **Amazon CloudFront** distribution fronts the api-gateway Function URL and the static frontend bucket, injecting an `X-Origin-Verify` header validated by `CloudFrontOriginVerifyFilter`.
- **AI:** **Amazon Bedrock** (Anthropic Claude Haiku) via the `bedrock` profile.
- **State backend:** S3 + DynamoDB lock table provisioned via `infrastructure/terraform/aws/bootstrap`.

> Retained AWS configuration is restart context, not standing permission to redirect traffic.
> Reactivation requires fresh compatibility/cost/operational review and owner approval; a DNS
> reversal alone is not sufficient acceptance of the current application.

Local development and CI use a deterministic `MockAiInsightService` so no cloud LLM is required to run or test the stack.

---

## 🚀 Future Roadmap

The picker/composition flow is delivered, not the next feature. [ROADMAP.md](ROADMAP.md) and
[enhancements v5](roadmap_enhancements_v5.md) distinguish delivered milestones, open engineering
residuals and unscheduled future ideas: per-user Sharpe/Sortino analytics, richer fundamental/
technical-analysis chat, and more engaging charts. Settings, custom assets, provider diversification
and AI-contract evolution remain future work. These entries are for a much later revisit, not a
new implementation commitment. v1–v4 are preserved as historical snapshots.

---

## 🛠️ Local Development

### Environment Matrix

| Environment         | Spring Profiles        | AI Advisor                  | Infrastructure                                     | Notes                                          |
| ------------------- | ---------------------- | --------------------------- | -------------------------------------------------- | ---------------------------------------------- |
| CI (GitHub Actions) | `default`              | `MockAiInsightService`      | Testcontainers (Postgres / Mongo / Kafka / Redis)  | Fast, no LLM calls                             |
| Local Dev           | `local`                | `MockAiInsightService`      | Docker Compose (+ optional LocalStack)             | Zero-latency mock responses                    |
| **Azure (Live)**    | `prod,azure,azure-ai`  | `AzureOpenAiInsightService` | Terraform → Azure Container Apps + Static Web Apps  | Azure OpenAI `gpt-4o-mini` via Entra ID        |
| AWS (Standby)       | `prod,aws,bedrock`     | `BedrockAiInsightService`   | Terraform → Lambda (arm64/Graviton2) + CloudFront  | Anthropic Claude Haiku via Amazon Bedrock      |

This project heavily utilizes `spring-boot-docker-compose` and Testcontainers for a frictionless developer experience. You do not need to install PostgreSQL, MongoDB, Kafka, or Redis locally.

**Prerequisites:**

- Java 21+
- Docker Desktop running
- Node.js 24+ (frontend uses **Next.js 16** with **React 19**)
- Terraform only for separately authorized infrastructure work; follow the pinned workflow
  version and [runbooks](docs/runbooks/README.md), not a presumed checked-in executable.

**Local stack example:** root Compose builds the four
services and runs local PostgreSQL/MongoDB/Kafka/Redis. Use local-only configuration; do not load
cloud profiles or production secrets into this example. Start the frontend in another terminal.

```bash
docker compose up --build
```

```bash
cd frontend
npm ci
npm run dev
```

The gateway is on `localhost:8080`; the frontend is on `localhost:3000`. Startup/seed prerequisites
and internal-key-gated operations remain explicit in the local configuration. For an individual
JVM use that service's `:bootRun` task and its local profile rather than assuming unqualified root
`bootRun` starts the complete stack.

## ✅ Testing

Run these commands from the repository root unless noted.

1. Backend test suite

```bash
./gradlew test              # fast unit tests (excludes @Tag("integration"))
./gradlew check             # unit + integration (Testcontainers)
```

Expected output:

```text
BUILD SUCCESSFUL
```

The backend test suite includes:

- **Contract & serialization tests** — Jackson 3 (`tools.jackson`) round-trip and wire-contract tests that pin the `PriceUpdatedEvent` shape across the Kafka producer/consumer boundary.
- **WireMock-based slice tests** that simulate external `503` / `429` API failures from Yahoo Finance to verify Resilience4j fault tolerance and fallback-to-cache behaviour.
- **Kafka DLT integration tests** (`DlqIntegrationTest`, `PriceUpdatedEventKafkaRoundTripIT`) proving poison messages route to `market-prices.DLT` without crashing the consumer.
- **Distributed-tracing tests** verifying W3C `traceparent` propagation across the reactive gateway boundary (OTLP export gated off by default).

2. Frontend unit/component tests (Vitest + RTL + MSW)

```bash
cd frontend
npm ci
npm test
```

3. Static-export smoke only (no authenticated backend acceptance)

The [frontend CI job](.github/workflows/frontend-ci.yml) selects the `static-smoke` project with
`SKIP_BACKEND_HEALTH_CHECK=true`. PowerShell example, with localhost targets explicit:

```powershell
cd frontend
$env:SKIP_BACKEND_HEALTH_CHECK = "true"
$env:SKIP_GOLDEN_STATE_SEEDING = "true"
$env:BASE_URL = "http://localhost:3000"
$env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
npx playwright install chromium
npx playwright test --project=static-smoke
Remove-Item Env:SKIP_BACKEND_HEALTH_CHECK
Remove-Item Env:SKIP_GOLDEN_STATE_SEEDING
```

Do not run unrestricted `npm run test:e2e` as a harmless local smoke: the default configuration
also includes live synthetic projects. Real-stack and live suites need their own configuration,
account/seed protocol and approval.

### Resilience Test Scope

Controlled local provider-failure fixtures test specific retry and fallback behavior; they do not
establish end-to-end availability during an outage. Disconnecting a visitor's internet does not
simulate a cloud dependency failure. The mocked-chaos 429 assertion redesign remains
[open](docs/todos/backlog/mocked-chaos-429-batch-assertion-redesign/README.md).

## 🎬 Demo / Evaluation Guide

Open [the live demo](https://vibhanshu-ai-portfolio.dev/) and sign in using demo access provided
by the project owner. The shared showcase has restricted write access, but holdings edits and
its own portfolio reset are enabled; arrange any changes with the owner.
Backend services scale to zero between visits, so the first load may take longer while they wake.

1. **Overview:** explore portfolio totals, allocations and the available summary metrics.
2. **Portfolio:** inspect holdings, valuation, currency labels, freshness/coverage indicators and
   the performance chart. Edit Holdings provides catalog-backed selection, quantity editing and
   saving, including on the shared showcase account; coordinate changes and restoration with
   the owner.
3. **Market Data:** browse supported tickers and their stored prices. Try US equities, Indian
   equities, crypto and currency pairs from the examples below.
4. **AI Insights:** try a ticker question such as “How is AAPL doing?” and compare the answer with
   the market card. Prices come from stored data; sentiment can be model-generated, cached or
   rule-based, with source wording shown in the response.

Demo operators: see [current operations](docs/runbooks/CURRENT_OPERATIONS.md) for warm-up,
account safety and validation/restore procedures.

### Supported Baseline Tickers (Examples)

The root catalog currently contains 159 ACTIVE entries and one DEPRECATED entry. Provider coverage
and fresh prices are separate facts; some held tickers may be missing/stale even if catalog-valid.
Examples (not a guarantee of today's availability):

- **US Tech Equities:** `AAPL`, `MSFT`, `TSLA`, `AMZN`, `GOOG`, `META`, `NVDA`
- **Indian Equities (NSE):** `RELIANCE.NS`, `TCS.NS`, `HDFCBANK.NS`, `INFY.NS`
- **Crypto:** `BTC-USD`, `ETH-USD`, `SOL-USD`, `DOGE-USD`
- **Forex Pairs:** `EURUSD=X`, `USDINR=X`, `GBPUSD=X`, `USDJPY=X`, `AUDUSD=X`

The picker uses the supported universe; analytics discloses missing/stale/partial data. Source
includes natural-language name resolution, but the demo checks do not prove its broader reliability.
Market-summary change over stored prices is not automatically a 24-hour return. Chat attribution,
cache/fallback behavior and known limitations stay qualified in the status dashboard.
