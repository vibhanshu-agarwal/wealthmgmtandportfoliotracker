# Architecture Documentation (v2)

This document captures the current, detailed architecture and operational guidance for the Wealth Management & Portfolio Tracker. It reflects the late-April 2026 state of the codebase: a Terraform-managed serverless stack on AWS Lambda (arm64/Graviton2) fronted by CloudFront, with all four Spring Boot services packaged as container images and run via the Lambda Web Adapter.

1. High-level components
- Frontend: **Next.js 16** with **React 19** (TypeScript) — client-facing UI, uses TanStack Query for server state and Playwright/Vitest/MSW/Pact for tests. The frontend communicates only with the API Gateway via CloudFront.
- API Gateway: Spring Cloud Gateway — central public API, routing, JWT validation, CloudFront origin verification (`X-Origin-Verify`), Redis-backed distributed rate-limiting, and `X-User-Id` injection for downstream services.
- Services (all built from a single multi-module Gradle root: `settings.gradle` includes `api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`, `common-dto`):
  - **portfolio-service**: PostgreSQL, Spring Data JPA. Responsible for portfolios, holdings, positions, transaction history, and on-demand valuation endpoints. Applies business rules and exposes REST endpoints under `/api/portfolio`. Consumes `PriceUpdatedEvent` and projects the latest price into `market_prices`.
  - **market-data-service**: MongoDB for raw market ticks and aggregated snapshots. Pulls from Yahoo Finance via a Resilience4j-wrapped client and publishes `PriceUpdatedEvent` to Kafka.
  - **insight-service**: Consumes `PriceUpdatedEvent` from Kafka into a Redis cache (`market:latest:*`, `market:history:*`), enriches market summaries with AI sentiment (Bedrock or mock), and exposes `/api/insights/**` and `/api/chat`.
  - **common-dto**: Shared module for message contracts, DTOs, the canonical `truststore.jks`, and the `TruststoreExtractor` utility (extracts to `/tmp` for Lambda compatibility).

2. Messaging and event design
- Broker: Apache Kafka for asynchronous communication. The primary topic in flight is `market-prices` (carrying `PriceUpdatedEvent`); a Dead-Letter Topic (`market-prices.DLT`) is used by `portfolio-service` to isolate poison messages.
- Schema governance: Pact consumer/provider tests gate HTTP contracts; an event-schema registry and topic versioning convention remain on the backlog. Until then, `common-dto` is the canonical home for event DTOs and additive evolution rules apply.
- Partitioning strategy: `PriceUpdatedEvent` records are keyed by ticker symbol so partition ordering is preserved per asset. Consumers must use matching partitioning keys.
- Consumer behavior:
  - Idempotency: Portfolio projection writes are idempotent at value level (`INSERT ... ON CONFLICT ... IS DISTINCT FROM`). Event-id–based dedup ledgers are still on the backlog for high-value topics.
  - Retries: Transient errors trigger controlled retries via Spring Kafka's `DefaultErrorHandler`. Records that exhaust retries — and any record raising `MalformedEventException` — are routed to `market-prices.DLT`.
  - Outbox: Spring Modulith's Event Publication Registry persists events to Postgres in the same transaction as the business write before they are forwarded to Kafka.

3. Data stores and patterns
- PostgreSQL: Primary transactional store for `portfolio-service`. Migrations are managed via Spring's startup machinery; connection pool sizing is tuned for expected concurrency under Lambda's per-instance limits.
- MongoDB: Append-heavy store for raw market snapshots and seeded fixtures. The `LocalMarketDataSeeder` populates baseline tickers from `config/seed-tickers.json` when the collection is empty.
- Redis: Used by the api-gateway for **distributed rate limiting** (Lettuce + Upstash TLS in production) and by `insight-service` for the ticker cache (`market:latest:*`, `market:history:*`).

4. API design and contracts
- Gateway exposes a stable API surface: `/api/portfolio`, `/api/market`, `/api/insights`, `/api/chat`. Internal services expose `/actuator/health` for warming + CI but are not directly reachable from the public internet — the `CloudFrontOriginVerifyFilter` rejects any request that does not carry the CloudFront-injected `X-Origin-Verify` header.
- Authentication: Centralized at the Gateway via HS256 JWT validation (`AUTH_JWT_SECRET`). The gateway strips spoofed `X-User-Id` headers and re-injects them from the verified `sub` claim.
- Versioning: API routes and event topics are versioned independently. Consumers should declare supported topic versions.

5. Testing strategy
- Unit tests: JUnit + Mockito (backend); Vitest + RTL + MSW for frontend components.
- Integration tests: Testcontainers for Postgres, MongoDB, Kafka, and Redis. Tagged via JUnit `@Tag("integration")` and run by the Gradle `integrationTest` task.
- Contract tests: Pact consumer tests run from `frontend/` (`vitest.pact.config.ts`); Pact provider verification runs in `portfolio-service` and `insight-service` (`*PactVerification*`).
- E2E tests: Playwright runs against the Docker Compose stack in `ci-verification.yml` and `frontend-e2e-integration.yml`, and against the live AWS deployment (`https://vibhanshu-ai-portfolio.dev`) in `synthetic-monitoring.yml` (cron, currently parked).

6. CI/CD and environments
- CI: GitHub Actions — `ci-verification.yml` (unit → integration → Pact consumer → Docker build + Pact provider + Playwright E2E), `ci.yml` (lint/build), `frontend-ci.yml`, `qodana_code_quality.yml`, `gitleaks.yml`.
- CD: `terraform.yml` runs `terraform apply` against the active AWS account; `deploy.yml` orchestrates the end-to-end image build + ECR push + Lambda update; `frontend-cd.yml` publishes the static export to S3+CloudFront.
- Gating: Pull requests must pass: unit + integration tests, Pact consumer + provider verification, Playwright E2E, Qodana, and Gitleaks.

7. Observability and operations
- Health: Spring Boot Actuator `/actuator/health` is exposed by every service and is the readiness signal for the Lambda Web Adapter (`AWS_LWA_READINESS_CHECK_PATH=/actuator/health`).
- Logs: Structured logs flow to CloudWatch Logs. Correlation/trace IDs propagate from CloudFront → api-gateway → downstream services via headers.
- Metrics & alarms: The Terraform `warming` module attaches a CloudWatch alarm on Lambda concurrent executions with SNS email notification (`warming_alarm_email`). Consumer lag dashboards remain on the backlog.
- Synthetic monitoring: `synthetic-monitoring.yml` runs Playwright against the live CloudFront URL hourly when enabled.

8. Reliability & data-correctness patterns
- Idempotent projections: `MarketPriceProjectionService` upserts with `IS DISTINCT FROM` to no-op duplicate deliveries.
- Cold-start mitigation: EventBridge warming rules + API Destinations periodically hit `/actuator/health` on every Function URL to keep at least one container warm; see `docs/architecture/lambda-stopgap-execution-plan.md`.
- Resilience: `ExternalMarketDataClient` is wrapped in Resilience4j retry/circuit-breaker policies. WireMock-backed slice tests assert fallback-to-cache behaviour on 429/503/5xx.
- Reconciliation jobs: planned but not yet scheduled — tracked under the next improvements backlog.

9. Security
- Secrets: GitHub Actions secrets → `TF_VAR_*` → Terraform sensitive variables → Lambda env vars; never written to source-controlled files. `truststore.jks` is bundled in `common-dto` and extracted at startup via `TruststoreExtractor` so Lambda's read-only filesystem can satisfy Lettuce/Kafka clients.
- Edge protection: CloudFront injects `X-Origin-Verify`; the api-gateway `CloudFrontOriginVerifyFilter` returns 403 to any request missing the header. Direct Function URL hits are therefore blocked.
- Pre-commit: Gitleaks runs locally and in CI to prevent secret leakage.

10. Migration and versioning guidance
- When changing `common-dto`: prefer additive fields and default values. If a breaking change is required, publish a new topic version and migrate consumers gradually.
- Database migrations: keep reversible or safely-forward migrations and run them as part of the deploy pipeline behind health checks.

11. Runbooks & incident response
- Consumer lag: check Kafka consumer-group lag (Aiven console), restart consumer with offset reset if needed.
- Poison messages: inspect `market-prices.DLT`, investigate schema/handler bug, re-publish after fix.
- Cold-start spikes: re-enable `var.enable_warming` in `terraform.tfvars` and redeploy; SNS alarm triggers when concurrent executions exceed `warming_concurrent_executions_threshold`.
- Valuation discrepancy: compare `market_prices` projection vs source feed in MongoDB; roll-forward corrections via the manual `POST /api/market/prices/{ticker}` admin endpoint.

12. Next improvements backlog
- Add schema registry integration and automated compatibility checks in CI.
- Add event-id dedup ledger for `portfolio-service` and `insight-service` consumers.
- Restore the synthetic monitoring schedule once free-tier headroom allows.
- Expand contract test coverage across all message types.

Appendices
- File references: `infrastructure/terraform/`, `docs/architecture/*.puml`, `common-dto/`, `docs/changes/CHANGES_INFRA_SUMMARY_2026-04-26.md`, `docs/changes/CHANGES_CACHE_WARMING_2026-04-30.md`.
- Contact: service owners listed in repo README.

