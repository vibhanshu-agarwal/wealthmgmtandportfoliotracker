# Wealth Management & Portfolio Tracker

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4+-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Modulith](https://img.shields.io/badge/Spring-Modulith-blue.svg)](https://spring.io/projects/spring-modulith)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Docker-336791.svg)](https://www.postgresql.org/)
[![Kafka](https://img.shields.io/badge/Apache-Kafka-231F20.svg)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-Cache-D82C20.svg)](https://redis.io/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-Retry%20Policies-5C6BC0.svg)](https://resilience4j.readme.io/)
[![WireMock](https://img.shields.io/badge/WireMock-HTTP%20Stubs-4D9DE0.svg)](https://wiremock.org/)

An enterprise-grade platform for managing investment portfolios, ingesting real-time market data, and generating AI-driven financial insights.

## 🧱 Enterprise Resilience & Event-Driven Data

- **Background Market Data Ingestion:** Market prices are fetched in the background from delayed external providers (e.g., Yahoo Finance) via a hardened `ExternalMarketDataClient`. All outbound calls are wrapped with **Resilience4j** retry policies to guard against 429 rate limits, 5xx outages, and transient network failures.
- **Kafka-Backed Price Propagation:** Fresh prices are published as `PriceUpdatedEvent` messages on **Kafka**, which in turn hydrate downstream services (like `insight-service`) and their **Redis** caches without coupling user requests to external APIs.
- **Fallback Strategy:** If the external market data API is unavailable, the system **never** blocks user-facing HTTP requests. Instead, it seamlessly serves **last-known-good prices** from MongoDB and Redis, keeping the AI Insights chat and dashboards responsive even during upstream outages.

## 🏗️ Architectural Philosophy: Evolutionary Design

This repository demonstrates an **Evolutionary Architecture** approach.

Rather than adopting a premature microservices architecture—which introduces unnecessary operational complexity, network latency, and distributed tracing overhead on day one—this system begins as a **Strictly Modular Monolith**.

This strategy allows us to safely validate domain boundaries and business logic first. Services are only extracted into independent, deployable units when their scaling profiles or deployment lifecycles explicitly demand it.

### 🗺️ Bounded Contexts

The system is divided into four distinct business domains, isolated as top-level packages:

1. **`com.wealth.portfolio` (Core Domain):** Manages user asset holdings, calculates real-time valuations, and handles transactional trade executions. Needs high ACID compliance.
2. **`com.wealth.market` (Anti-Corruption Layer):** Ingests, normalizes, and broadcasts high-throughput pricing data from external market APIs.
3. **`com.wealth.insight` (Compute Domain):** Generates weekly AI-driven investment recommendations. CPU-intensive and operates asynchronously.
4. **`com.wealth.user` (Generic Subdomain):** Handles identity, profiles, and billing tiers.

### 🛡️ Enforcing Boundaries with Spring Modulith

To prevent the architecture from degrading into a "Big Ball of Mud," this project utilizes **Spring Modulith**.

- **Zero Cross-Domain Coupling:** ArchUnit tests (`@ApplicationModuleTest`) run during the build phase to mathematically guarantee that no domain directly imports classes from another domain.
- **The Transactional Outbox Pattern:** Inter-domain communication is handled entirely via Spring Application Events. Modulith's Event Publication Registry automatically writes events (e.g., `PriceUpdatedEvent`) to a PostgreSQL event log within the exact same database transaction as the business logic, ensuring guaranteed **at-least-once delivery** without needing an external message broker in Phase 1.

---

## 🚀 Architectural Evolution Roadmap

Reviewing the code history will reveal the system's deliberate journey from a single deployment unit to a distributed cloud architecture.

### 📍 Phase 1: The Modular Monolith (Current State)

- **Checkout Tag:** `v1.0-modular-monolith`
- **Infrastructure:** Single Spring Boot JAR, single PostgreSQL instance with logically separated schemas (`schema_portfolio`, `schema_market`).
- **Messaging:** In-memory Spring Events backed by JDBC Outbox.

### 📍 Phase 2: Event-Driven Data Extraction (Market Domain)

- **Focus:** Extract the `market` anti-corruption layer into its own deployable service while the rest of the system remains a modular monolith.
- **Messaging Shift:** Replace internal Spring Events for price updates with **Amazon MSK (Apache Kafka)** to handle high-throughput, append-only market data streams.
- **Target AWS Deployment:** Containerize the market data ingestion pipeline and run it on **Amazon ECS / AWS Fargate**, enabling independent scaling, blue/green deploys, and autoscaling tuned to ticker volume.

### 📍 Phase 3: Serverless AI Integration (Insight Domain)

- **Focus:** Extract the `insight` compute domain (AI Insights engine) into a fully serverless, on-demand compute layer.
- **AWS Lambda + Docker:** Package the Java-based AI Insights engine as a **Docker container image** and deploy it as an **AWS Lambda** function, simplifying CI/CD and bypassing traditional Lambda deployment size limits.
- **Planned Bedrock Integration (Future Roadmap):** The target state is for the containerized Lambda to integrate with **Amazon Bedrock (Claude 3)** to generate financial insights, allowing the platform to scale AI workloads elastically and pay only for actual inference usage. In the current codebase this integration is either not yet wired end-to-end or may be represented via mocks and test doubles, and is therefore explicitly tracked as part of the forward-looking roadmap rather than the implemented baseline.

### 🎯 Future Architectural Goals

- **Dedicated AI Microservice (Microsoft AI Foundry):** Evolve the local AI inference engine into a dedicated, high-performance microservice backed by **Microsoft AI Foundry**, initially integrated from the Java-based platform via hardened, REST-based contracts. Over time, this service is expected to surface a strongly typed, low-latency interface (e.g., gRPC or equivalent service-mesh abstraction), while preserving strict resource isolation between transactional workloads and AI compute, and enabling a cost profile that approaches near-zero marginal cost per additional AI request through elastic, right-sized capacity.
- **Multi-Provider Market Data Aggregation:** Extend the current Yahoo Finance integration with additional institutional-grade data providers (e.g., **Alpha Vantage**, **Polygon.io**) using an Adapter/Strategy abstraction layered behind the `ExternalMarketDataClient`. This enables high-availability failover, cross-provider price reconciliation and anomaly detection, and prevents long-term lock-in to any single market data vendor.
- **Advanced AI-Driven Wealth Workflows:** Transform the AI layer from a primarily conversational assistant into an autonomous financial agent capable of orchestrating end-to-end wealth management workflows. Roadmap scenarios include predictive portfolio rebalancing simulations, real-time sentiment analysis over streaming market and news feeds, and automated tax-loss harvesting recommendations aligned with user-specific risk, jurisdictional constraints, and regulatory guardrails.

---

## 🛠️ Local Development

### Environment Matrix

| Environment         | Spring Profile | AI Advisor                | Infrastructure          | Notes                       |
| ------------------- | -------------- | ------------------------- | ----------------------- | --------------------------- |
| CI (GitHub Actions) | `default`      | `MockPortfolioAdvisor`    | Testcontainers          | Fast, no LLM download       |
| Local Dev           | `local`        | `MockPortfolioAdvisor`    | Docker Compose          | Zero-latency mock responses |
| Local AI            | `local,ollama` | `OllamaPortfolioAdvisor`  | Docker Compose + Ollama | Requires `phi3` model pull  |
| AWS Production      | `bedrock`      | `BedrockPortfolioAdvisor` | ECS / Lambda            | Anthropic Claude 3 Haiku    |

### Local AI Cold Start

To use the Ollama advisor locally, pull the model after starting Docker Compose:

```bash
docker compose up -d
docker exec -it ollama ollama pull phi3
```

This downloads ~2.3 GB for the Phi-3 Mini model (optimized for 4GB VRAM).

This project heavily utilizes `spring-boot-docker-compose` and Testcontainers for a frictionless developer experience. You do not need to install PostgreSQL locally or manage credentials.

**Prerequisites:**

- Java 25+
- Docker Desktop running

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
