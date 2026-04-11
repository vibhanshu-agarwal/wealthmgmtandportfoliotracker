# Wealth Management & Portfolio Tracker

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4+-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring Modulith](https://img.shields.io/badge/Spring-Modulith-blue.svg)](https://spring.io/projects/spring-modulith)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Docker-336791.svg)](https://www.postgresql.org/)

An enterprise-grade platform for managing investment portfolios, ingesting real-time market data, and generating AI-driven financial insights.

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

### 📍 Phase 2: Cloud-Native Target Architecture (In Progress)

- **View the Migration:** [Link to Pull Request #1: "Cloud-Native Extraction"] _(Note: Add your PR link here when ready)_
- **The Shift:** The `market` and `insight` modules are extracted due to high-throughput scaling and CPU demands, respectively.
- **Target AWS Deployment:** \* Services containerized and orchestrated via Amazon EKS / ECS.
  - Internal Spring Events replaced with **Amazon MSK (Apache Kafka)** for decoupled, high-throughput message streaming.
  - The `insight` batch jobs offloaded to **AWS Lambda** triggered by **Amazon EventBridge** to optimize compute costs.
  - Polyglot persistence introduced (migrating `market` time-series data to DynamoDB).

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
