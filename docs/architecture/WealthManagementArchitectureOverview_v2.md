# Wealth Management & Portfolio Tracker — Architecture (v2)

## Executive summary (v2)
This repo hosts a modular wealth management platform: a **Next.js 16 / React 19** frontend (TypeScript) and a **Spring Boot 4.x on Java 25** backend split across four services. The platform runs in production as a serverless AWS stack provisioned by **Terraform** — the legacy AWS CDK code under `infrastructure/lib/` is deprecated and retained only for historical reference.

Multi-module Gradle layout (`settings.gradle`):

| Module | Role |
| ------ | ---- |
| `api-gateway` | Spring Cloud Gateway: JWT validation, CloudFront origin verification, Redis-backed distributed rate limiting, request routing |
| `portfolio-service` | Postgres-backed portfolios, holdings, analytics, FX conversion, Kafka projection of `market-prices` (with DLT) |
| `market-data-service` | MongoDB-backed market ingestion, Yahoo Finance adapter, Resilience4j retries, Kafka producer for `PriceUpdatedEvent` |
| `insight-service` | Redis ticker cache, Bedrock/mock AI insight + chat surface |
| `common-dto` | Shared event contracts, JKS truststore, `TruststoreExtractor` |

Key updates since v1
- **Infrastructure shift:** Migrated from AWS CDK to a Terraform-managed serverless stack. The four Spring Boot microservices are deployed as **AWS Lambda functions on arm64 / Graviton2** using the **Lambda Web Adapter** (`/opt/extensions/lambda-adapter`) as a sidecar, fronted by a single **Amazon CloudFront** distribution.
- **Event reliability:** Idempotent projection writes (`ON CONFLICT … IS DISTINCT FROM`), Kafka keying by ticker, and a Dead-Letter Topic (`market-prices.DLT`) for poison messages in `portfolio-service`.
- **Schema governance:** `common-dto` is the canonical home for inter-service contracts; Pact consumer/provider tests enforce HTTP contracts; an event-schema registry and topic versioning remain on the backlog.
- **Observability:** Spring Boot Actuator health endpoints on every service, EventBridge synthetic warming with an SNS alarm on Lambda concurrency (`infrastructure/terraform/modules/warming`), structured logs in CloudWatch.
- **Testing & CI:** Strengthened pipelines (`ci-verification.yml`, `frontend-e2e-integration.yml`, `synthetic-monitoring.yml`) with Testcontainers (Postgres, Mongo, Kafka, Redis), Pact consumer/provider tests, Playwright E2E against Docker Compose and live AWS, and Qodana quality checks.
- **Operational hardening (late April 2026):** Redis-backed distributed rate limiting promoted to active, Kafka DLQ shipped, Lambda permission state-drift fixed via root-module `import` blocks (PR #26), and synthetic monitoring parked for cost (see `docs/changes/CHANGES_CACHE_WARMING_2026-04-30.md`).

Why v2 matters
Financial correctness and eventual consistency are first-class concerns: the platform favors idempotent processing, deterministic resilience, and reproducible IaC so that valuations and derived insights can be relied on at scale even when downstream APIs (Yahoo Finance, Bedrock) misbehave.

Primary value
- Safe, auditable, and testable event-driven updates
- Clear service ownership behind a single public API surface (CloudFront → api-gateway Lambda → downstream Lambdas)
- Smooth path from local development (Docker Compose + LocalStack) to live AWS (Terraform `apply`)

Reference materials: rendered PlantUML in `docs/architecture/architecture.puml`; CI gating checklist in `docs/architecture/IntegrationTestCases.md`; warming/cost runbook in `docs/changes/CHANGES_CACHE_WARMING_2026-04-30.md`.
