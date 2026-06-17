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

An enterprise-grade platform for managing investment portfolios, ingesting real-time market data, and generating AI-driven financial insights.

🌐 **Live demo:** [vibhanshu-ai-portfolio.dev](https://vibhanshu-ai-portfolio.dev/) — running on Azure Container Apps + Azure Static Web Apps, with Azure OpenAI powering the AI Insights experience.

## 🧱 Enterprise Resilience & Event-Driven Data

- **Background Market Data Ingestion:** Market prices are fetched in the background from delayed external providers (e.g., Yahoo Finance) via a hardened `ExternalMarketDataClient`. All outbound calls are wrapped with **Resilience4j** retry policies to guard against 429 rate limits, 5xx outages, and transient network failures.
- **Kafka-Backed Price Propagation:** Fresh prices are published as `PriceUpdatedEvent` messages on **Kafka**, which in turn hydrate downstream services (like `insight-service`) and their **Redis** caches without coupling user requests to external APIs.
- **Poison-Message Handling (DLT):** The `portfolio-service` consumer registers `MalformedEventException` as non-retryable and routes poison/malformed records to a dead-letter topic (`market-prices.DLT`) via Spring Kafka's `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`, so a single bad event never stalls the consumer.
- **Fallback Strategy:** If the external market data API is unavailable, the system **never** blocks user-facing HTTP requests. Instead, it seamlessly serves **last-known-good prices** from MongoDB and Redis, keeping the AI Insights chat and dashboards responsive even during upstream outages.

## 🏗️ Architectural Philosophy: Evolutionary Design

This repository demonstrates an **Evolutionary Architecture** approach.

The system started life as a strictly modular monolith (Spring Modulith, single deployable, JDBC outbox) and has since been decomposed into a **multi-module Gradle build** of four independently-deployable Spring Boot microservices plus a shared `common-dto` contract module:

| Module | Role |
| ------ | ---- |
| `api-gateway` | Spring Cloud Gateway (WebFlux) — JWT validation, origin verification, rate limiting, request routing |
| `portfolio-service` | Portfolio domain (PostgreSQL + Flyway) — holdings, valuations, analytics, FX conversion, Kafka projection of `market-prices`, DLT handling |
| `market-data-service` | Market ingestion (MongoDB) — pulls from Yahoo Finance, persists snapshots, publishes `PriceUpdatedEvent` to Kafka |
| `insight-service` | AI insights (Redis + Azure OpenAI / Bedrock / mock) — chat, market summary, LLM-grounded natural-language asset resolution |
| `common-dto` | Shared DTOs, event contracts (`PriceUpdatedEvent`), truststore extractor |

Services are extracted into independent deployable units only when their scaling profiles or deployment lifecycles explicitly demand it — the market and insight domains were the first to warrant extraction.

### 🗺️ Bounded Contexts

The system is divided into distinct business domains, each owning its top-level package and its own datastore:

1. **`com.wealth.portfolio` (Core Domain):** Manages user asset holdings, calculates real-time valuations, and handles transactional updates. Backed by PostgreSQL for ACID compliance and Flyway migrations.
2. **`com.wealth.market` (Anti-Corruption Layer):** Ingests, normalizes, and broadcasts pricing data from external market APIs. Backed by MongoDB for flexible tick/snapshot storage.
3. **`com.wealth.insight` (Compute Domain):** Generates AI-driven investment insights and answers natural-language portfolio questions. CPU/IO-bound and operates asynchronously off the Kafka stream and a Redis cache.

Identity is handled at the edge: the frontend mints HS256 JWTs (Better Auth) and the `api-gateway` validates them before routing — there is no separate user-management service.

### 🛡️ Enforcing Boundaries

Now that the domains are physically separated, boundaries are enforced structurally rather than by in-process module verification:

- **No shared database:** Each service owns its own datastore (PostgreSQL, MongoDB, Redis). Cross-domain references are carried as plain identifiers, never JPA relationships.
- **Contract-first events:** All inter-service event contracts live in `common-dto` and are pinned with wire-contract tests on both producer and consumer sides (`PriceUpdatedEventProducerWireContractTest`, `PriceUpdatedEventConsumerPathTest`), plus a Testcontainers producer→consumer round-trip (`PriceUpdatedEventKafkaRoundTripIT`).
- **Cloud-agnostic core:** Domain logic depends on Spring abstractions (Spring Data, Spring Kafka, Spring AI), not vendor SDKs, so the same code runs unmodified on Azure and AWS.

## ☁️ Production Deployment — Multi-Cloud, Azure-Active

The platform is provisioned entirely as code with **Terraform** (the legacy AWS CDK under `infrastructure/lib/` is deprecated and retained only as historical reference). Two parallel cloud targets are maintained under `infrastructure/terraform/`, with exactly one active at a time, switched via Spring profiles and DNS:

### 🟢 Azure — Active (Live)

This is the cloud currently serving [vibhanshu-ai-portfolio.dev](https://vibhanshu-ai-portfolio.dev/).

- **Compute:** All four Spring Boot services run as **Azure Container Apps (ACA)** in Central India, scale-to-zero (`min_replicas = 0`) to stay within budget. The internal services listen on port 8080; the `api-gateway` is the only externally-reachable app.
- **Frontend:** The Next.js app is statically exported and hosted on **Azure Static Web Apps** (Free tier).
- **AI:** **Azure OpenAI** (`gpt-4o-mini` deployment) via the consolidated Spring AI `spring-ai-starter-model-openai` starter, authenticated with **Entra ID / Managed Identity** (`DefaultAzureCredential`) rather than static keys. Activated by the `azure-ai` profile.
- **Managed dependencies:** **Upstash** (Redis) and **Aiven** (Kafka) free tiers; topic auto-creation and TLS are wired for both.
- **DNS / Edge:** **Cloudflare** holds the zone — apex/`www` flatten to Static Web Apps and `api.` points at the ACA `api-gateway`. TLS is managed by Azure on both hostnames.
- **CI/CD:** GitHub Actions (`deploy-azure.yml`, `terraform-azure.yml`) build images, push to ACR, run `terraform apply`, and deploy. Hourly/daily `synthetic-monitoring.yml` and `ci-verification.yml` validate the live stack. `CLOUD_PROVIDER=azure` gates the Azure-specific steps.

### 🟡 AWS — Soft-Disabled Standby

The original AWS serverless stack is fully built and remains a rollback target, but is **currently disabled** — its apex record in Cloudflare is renamed to `_disabled-apex` (no traffic), and its deploy workflows are `workflow_dispatch`-only.

- **Compute:** All four services packaged as container images and deployed as **AWS Lambda on arm64 / Graviton2**, fronted by the **AWS Lambda Web Adapter** so each Spring Boot app runs unmodified.
- **Edge:** A single **Amazon CloudFront** distribution fronts the api-gateway Function URL and the static frontend bucket, injecting an `X-Origin-Verify` header validated by `CloudFrontOriginVerifyFilter`.
- **AI:** **Amazon Bedrock** (Anthropic Claude Haiku) via the `bedrock` profile.
- **State backend:** S3 + DynamoDB lock table provisioned via `infrastructure/terraform/aws/bootstrap`.

> The AWS path was soft-disabled in favour of Azure to resolve Lambda cold-start/throttling under the demo's free-tier constraints. It is intentionally **not decommissioned** — the standby DNS records and Terraform state are preserved so traffic can be cut back to AWS by reversing the Cloudflare apex rename.

Local development and CI use a deterministic `MockAiInsightService` so no cloud LLM is required to run or test the stack.

---

## 🚀 Future Roadmap

The architectural roadmap continues to evolve as we expand the multi-cloud and advanced-AI capabilities. See [ROADMAP.md](ROADMAP.md) for what's next, including a dedicated gRPC AI microservice, multi-provider market-data aggregation, and production-grade rate limiting.

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
- Terraform 1.6+ (a pinned binary is checked in under `infrastructure/terraform-bin`)

**To start the application:**

```bash
./gradlew bootRun
```

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
npm install
npm test
```

3. Frontend E2E smoke test (Playwright standalone build check)

```bash
cd frontend
npx playwright install chromium
npm run test:e2e
```

## 🎬 Demo / Evaluation Guide

Use this section as a quick runbook to evaluate the platform's resilience and AI-driven insights.

### Supported Baseline Tickers (Examples)

The system seeds and tracks a curated baseline of ~160 popular instruments (provider-formatted), including but not limited to:

- **US Tech Equities:** `AAPL`, `MSFT`, `TSLA`, `AMZN`, `GOOG`, `META`, `NVDA`
- **Indian Equities (NSE):** `RELIANCE.NS`, `TCS.NS`, `HDFCBANK.NS`, `INFY.NS`
- **Crypto:** `BTC-USD`, `ETH-USD`, `SOL-USD`, `DOGE-USD`
- **Forex Pairs:** `EURUSD=X`, `USDINR=X`, `GBPUSD=X`, `USDJPY=X`, `AUDUSD=X`

You can build portfolios using these symbols and immediately see valuations and AI Insights powered by delayed but realistic market prices. The AI Insights chat resolves natural-language names (e.g. "Apple", "Bitcoin", "HDFC Bank") to the correct tickers via a catalog-grounded LLM pipeline.

### Chaos Test: Prove the Fallback Strategy

To validate the **enterprise resilience** of the Market Data + AI Insights flow:

1. Start the full stack locally (backend services, Redis, Kafka, and the frontend).
2. Navigate to the AI Insights / chat experience and ask a market-related question that depends on portfolio prices (e.g., "How is my tech-heavy portfolio performing?").
3. **Disconnect your machine from the internet** (disable Wi‑Fi/ethernet) so outbound calls to Yahoo Finance fail.
4. Ask the same or a similar question again.

Expected behaviour:

- The system continues to serve responses backed by **cached database prices** (MongoDB + Redis) and previously fetched market data.
- The UI and APIs remain responsive; no user-facing request blocks on external HTTP calls or crashes due to upstream outages.
- Logs will show messages such as:
  `"Yahoo Finance API failed, falling back to cached database prices."`

This demonstrates that the event-driven market data pipeline is **resilient by design**: external API failures degrade gracefully, while Kafka + Redis ensure the AI Insights layer continues to operate on a consistent snapshot of market data.
