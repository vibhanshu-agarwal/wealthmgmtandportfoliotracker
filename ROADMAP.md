This document outlines the strategic architectural evolution and upcoming feature expansions for the Wealth Management & Portfolio Tracker.

## 🚀 Future Roadmap

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
- **Azure Multi-Cloud Expansion:** Extend the platform toward a pragmatic multi-cloud topology where AI and data workflows can operate across AWS and Azure boundaries, reducing provider concentration risk while preserving clear service contracts and operational resilience.
- **Multi-Provider Market Data Aggregation:** Extend the current Yahoo Finance integration with additional institutional-grade data providers (e.g., **Alpha Vantage**, **Polygon.io**) using an Adapter/Strategy abstraction layered behind the `ExternalMarketDataClient`. This enables high-availability failover, cross-provider price reconciliation and anomaly detection, and prevents long-term lock-in to any single market data vendor.
- **Advanced AI-Driven Wealth Workflows:** Transform the AI layer from a primarily conversational assistant into an autonomous financial agent capable of orchestrating end-to-end wealth management workflows. Roadmap scenarios include predictive portfolio rebalancing simulations, real-time sentiment analysis over streaming market and news feeds, and automated tax-loss harvesting recommendations aligned with user-specific risk, jurisdictional constraints, and regulatory guardrails.
- **Infrastructure Security Hardening:** Implement the Principle of Least Privilege at the database layer by migrating from the default owner role to a scoped `app_user` role with strictly limited schema permissions (`CONNECT`, `SELECT`, `INSERT`, `UPDATE`, `DELETE`).
