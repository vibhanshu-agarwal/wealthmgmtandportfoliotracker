# Wealth Management & Portfolio Tracker

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4+-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Modulith](https://img.shields.io/badge/Spring-Modulith-blue.svg)](https://spring.io/projects/spring-modulith)
[![Next.js](https://img.shields.io/badge/Next.js-16-black.svg)](https://nextjs.org/)
[![React](https://img.shields.io/badge/React-19-61DAFB.svg)](https://react.dev/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Docker-336791.svg)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Apache-Kafka-231F20.svg)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-Cache-D82C20.svg)](https://redis.io/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-Retry%20Policies-5C6BC0.svg)](https://resilience4j.readme.io/)
[![WireMock](https://img.shields.io/badge/WireMock-HTTP%20Stubs-4D9DE0.svg)](https://wiremock.org/)
[![Terraform](https://img.shields.io/badge/Terraform-Serverless%20Infra-7B42BC.svg)](https://www.terraform.io/)
[![AWS Lambda](https://img.shields.io/badge/AWS%20Lambda-arm64%2FGraviton2-FF9900.svg)](https://aws.amazon.com/lambda/)
[![CloudFront](https://img.shields.io/badge/AWS-CloudFront-232F3E.svg)](https://aws.amazon.com/cloudfront/)

An enterprise-grade platform for managing investment portfolios, ingesting real-time market data, and generating AI-driven financial insights.

## 🧱 Enterprise Resilience & Event-Driven Data

- **Background Market Data Ingestion:** Market prices are fetched in the background from delayed external providers (e.g., Yahoo Finance) via a hardened `ExternalMarketDataClient`. All outbound calls are wrapped with **Resilience4j** retry policies to guard against 429 rate limits, 5xx outages, and transient network failures.
- **Kafka-Backed Price Propagation:** Fresh prices are published as `PriceUpdatedEvent` messages on **Kafka**, which in turn hydrate downstream services (like `insight-service`) and their **Redis** caches without coupling user requests to external APIs.
- **Fallback Strategy:** If the external market data API is unavailable, the system **never** blocks user-facing HTTP requests. Instead, it seamlessly serves **last-known-good prices** from MongoDB and Redis, keeping the AI Insights chat and dashboards responsive even during upstream outages.

## 🏗️ Architectural Philosophy: Evolutionary Design

This repository demonstrates an **Evolutionary Architecture** approach.

The system started life as a Strictly Modular Monolith and has since been split into a **multi-module Gradle build** with four independently-deployable Spring Boot services plus a shared `common-dto` module:

| Module | Role |
| ------ | ---- |
| `api-gateway` | Spring Cloud Gateway — JWT validation, CloudFront origin verification, Redis-backed distributed rate limiting, request routing |
| `portfolio-service` | Portfolio domain (PostgreSQL) — holdings, valuations, analytics, FX conversion, Kafka projection of `market-prices` |
| `market-data-service` | Market ingestion (MongoDB) — pulls from Yahoo Finance, persists snapshots, publishes `PriceUpdatedEvent` to Kafka |
| `insight-service` | AI insights (Redis + Bedrock/mock) — chat, market summary, AI sentiment enrichment |
| `common-dto` | Shared DTOs, event contracts, truststore extractor |

Services are only extracted into independent deployable units when their scaling profiles or deployment lifecycles explicitly demand it.

### 🗺️ Bounded Contexts

The system is divided into four distinct business domains, isolated as top-level packages:

1. **`com.wealth.portfolio` (Core Domain):** Manages user asset holdings, calculates real-time valuations, and handles transactional trade executions. Needs high ACID compliance.
2. **`com.wealth.market` (Anti-Corruption Layer):** Ingests, normalizes, and broadcasts high-throughput pricing data from external market APIs.
3. **`com.wealth.insight` (Compute Domain):** Generates weekly AI-driven investment recommendations. CPU-intensive and operates asynchronously.
4. **`com.wealth.user` (Generic Subdomain):** Handles identity, profiles, and billing tiers.

### 🛡️ Enforcing Boundaries with Spring Modulith

To prevent the architecture from degrading into a "Big Ball of Mud," this project utilizes **Spring Modulith**.

- **Zero Cross-Domain Coupling:** ArchUnit tests (`@ApplicationModuleTest`) run during the build phase to mathematically guarantee that no domain directly imports classes from another domain.
- **The Transactional Outbox Pattern:** Inter-domain communication is handled via Spring Application Events plus Kafka. Modulith's Event Publication Registry writes events (e.g., `PriceUpdatedEvent`) to a PostgreSQL event log within the same database transaction as the business logic, ensuring guaranteed **at-least-once delivery** before they are forwarded to the broker.

## ☁️ Production Deployment — Terraform-Managed Serverless Stack

The platform runs on AWS as a fully serverless stack provisioned by **Terraform** (the legacy AWS CDK code under `infrastructure/lib/` is deprecated and retained only as historical reference). The active infrastructure lives under `infrastructure/terraform/`.

- **Compute:** All four Spring Boot services (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) are packaged as container images and deployed as **AWS Lambda functions running on arm64 / Graviton2**. The **AWS Lambda Web Adapter** sidecar (loaded from `/opt/extensions/`) translates Lambda's invocation model into standard HTTP on port 8080, so each Spring Boot app runs unmodified.
- **Edge:** A single **Amazon CloudFront** distribution fronts the api-gateway Function URL and the static frontend bucket. CloudFront injects an `X-Origin-Verify` secret header that the api-gateway's `CloudFrontOriginVerifyFilter` validates, blocking direct Function URL access.
- **State backend:** S3 + DynamoDB lock table provisioned via the `infrastructure/terraform/bootstrap` module before the main root.
- **Cold-start mitigation:** An EventBridge-driven warming module (`infrastructure/terraform/modules/warming`) periodically pings `/actuator/health` on every Function URL. The module is feature-flagged via `enable_warming` and currently parked for cost reasons (see `docs/changes/CHANGES_CACHE_WARMING_2026-04-30.md`).
- **CI/CD:** GitHub Actions workflows (`terraform.yml`, `ci-verification.yml`, `frontend-cd.yml`, `synthetic-monitoring.yml`) build images, push to ECR, run `terraform apply`, and validate live deployments.

---

## 🚀 Future Roadmap
The architectural roadmap is highly dynamic as we expand our multi-cloud and advanced AI capabilities. Please see the ROADMAP.md file for the upcoming features, including gRPC AI microservices, Amazon Bedrock integration, and cross-provider data strategies.

---

## 🛠️ Local Development

### Environment Matrix

| Environment         | Spring Profile         | AI Advisor                | Infrastructure                                     | Notes                                          |
| ------------------- | ---------------------- | ------------------------- | -------------------------------------------------- | ---------------------------------------------- |
| CI (GitHub Actions) | `default`              | `MockPortfolioAdvisor`    | Testcontainers (Postgres / Mongo / Kafka / Redis)  | Fast, no LLM download                          |
| Local Dev           | `local`                | `MockPortfolioAdvisor`    | Docker Compose (+ optional LocalStack)             | Zero-latency mock responses                    |
| AWS Production      | `prod,aws,bedrock`     | `BedrockPortfolioAdvisor` | Terraform → Lambda (arm64/Graviton2) + CloudFront  | Anthropic Claude Haiku 4.5 via Amazon Bedrock  |

This project heavily utilizes `spring-boot-docker-compose` and Testcontainers for a frictionless developer experience. You do not need to install PostgreSQL locally or manage credentials.

**Prerequisites:**

- Java 25+
- Docker Desktop running
- Node.js 22+ (frontend uses **Next.js 16** with **React 19**)
- Terraform 1.6+ (a pinned binary is checked in under `infrastructure/terraform-bin`)

**To start the application:**

```bash
./gradlew bootRun
```

## ✅ Testing

Run these commands from the repository root unless noted.

1. Backend test suite

```bash
./gradlew test
```

Expected output:

```text
BUILD SUCCESSFUL
```

This backend test suite includes:

- **Spring Modulith** architectural tests for strict bounded contexts.
- **WireMock-based slice tests** that simulate external `503` / `429` API failures from Yahoo Finance to verify Resilience4j fault tolerance and fallback-to-cache behaviour for the market data pipeline.

2. Frontend unit/component tests (Vitest + RTL + MSW)

```bash
cd frontend
npm install
npm test
```

Expected output:

```text
Test Files  1 passed
Tests       1 passed
```

3. Frontend E2E smoke test (Playwright standalone build check)

```bash
cd frontend
npx playwright install chromium
npm run test:e2e
```

Expected output:

```text
1 passed
```

## 🎬 Demo / Evaluation Guide

Use this section as a quick runbook to evaluate the platform's resilience and AI-driven insights.

### Supported Baseline Tickers (Examples)

The system seeds and tracks a curated baseline of popular instruments (provider-formatted), including but not limited to:

- **US Tech Equities:** `AAPL`, `MSFT`, `TSLA`, `AMZN`, `GOOG`, `META`, `NVDA`
- **Indian Equities (NSE):** `RELIANCE.NS`, `TCS.NS`, `HDFCBANK.NS`, `INFY.NS`
- **Crypto:** `BTC-USD`, `ETH-USD`, `SOL-USD`, `DOGE-USD`
- **Forex Pairs:** `EURUSD=X`, `USDINR=X`, `GBPUSD=X`, `USDJPY=X`, `AUDUSD=X`

You can build portfolios using these symbols and immediately see valuations and AI Insights powered by delayed but realistic market prices.

### Chaos Test: Prove the Fallback Strategy

To validate the **enterprise resilience** of the Market Data + AI Insights flow:

1. Start the full stack locally (backend services, Redis, Kafka, and the frontend).
2. Navigate to the AI Insights / chat experience in the UI and ask a market-related question that depends on portfolio prices (e.g., "How is my tech-heavy portfolio performing?").
3. **Disconnect your machine from the internet** (disable Wi‑Fi/ethernet) so that outbound calls to Yahoo Finance and other external APIs will fail.
4. Ask the same or a similar question in the AI Insights chat.

Expected behaviour:

- The system continues to serve responses backed by **cached database prices** (MongoDB + Redis) and previously fetched market data.
- The UI and APIs remain responsive; no user-facing request should block on external HTTP calls or crash due to upstream outages.
- Logs will show messages such as:  
  `"Yahoo Finance API failed, falling back to cached database prices."` and  
  `"MarketDataRefreshJob: Yahoo Finance API failed, falling back to cached database prices. Continuing to serve last-known prices for all tickers."`

This demonstrates that the event-driven market data pipeline is **resilient by design**: external API failures degrade gracefully, while Kafka + Redis ensure the AI Insights layer continues to operate on a consistent snapshot of market data.
