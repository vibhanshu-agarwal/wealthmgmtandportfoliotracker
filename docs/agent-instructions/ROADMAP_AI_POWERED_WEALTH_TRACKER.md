# Project Plan: AI-Powered Wealth Management & Portfolio Tracker

**Target Domain:** `vibhanshu-ai-portfolio.dev`
**Stack:** Java 25 (LTS), Spring Boot 4.x, PostgreSQL, MongoDB, Redis, Apache Kafka, Next.js 16 (React 19)
**Core Goal:** Demonstrate a Senior-level Cloud Architecture using modern Java features, multi-model persistence, event-driven messaging, and Enterprise AI (Amazon Bedrock) while maintaining an ultra-low-cost footprint.

> **Status snapshot (late April 2026):** Phases 1–3 are implemented and live. Phase 3 (Insight-Service Frontend Integration) is complete — the Next.js 16 AI Insights surface (`/ai-insights`) talks to the deployed `insight-service` Lambda via the api-gateway. The infrastructure was migrated off AWS CDK to a Terraform-managed serverless stack and hardened in PRs #24–#26 (warming module reactivation, Lambda permission state-drift fix, style hardening). Phase 4 (Architect Demo / scale-out) remains optional.

---

## ## Phase 1: Infrastructure & Networking — ✅ Implemented
*Goal: Secure, global entry point with nearly zero fixed monthly costs.*

### Components (current state)
* **Domain & DNS:** `vibhanshu-ai-portfolio.dev` served via **Amazon CloudFront**; DNS is delegated through Route 53.
* **SSL/TLS:** Free certificate via **AWS Certificate Manager (ACM)**.
* **CDN / Edge:** **Amazon CloudFront** distribution fronts both the static frontend (S3) and the api-gateway Function URL. CloudFront injects an `X-Origin-Verify` secret header that the Spring `CloudFrontOriginVerifyFilter` validates to block direct Function URL access.
* **Compute:** **AWS Lambda** with **Function URLs**, packaged as **container images** (ECR) running on **arm64 / Graviton2**. The **AWS Lambda Web Adapter** sidecar is loaded from `/opt/extensions/` so the Spring Boot HTTP servers run unmodified.
* **IaC:** **Terraform** (`infrastructure/terraform/`) is the single source of truth. The legacy AWS CDK code under `infrastructure/lib/` is deprecated.
  * Bootstrap module provisions the S3 state backend and DynamoDB lock table.
  * Modules: `compute` (Lambda + Function URLs + aliases), `cdn` (CloudFront), `database` (free-tier guarded), `warming` (EventBridge + API Destinations).
  * Two-phase apply pattern wires downstream Function URLs back into api-gateway env vars.

### Architectural Workflow
1. **CloudFront** terminates HTTPS at the edge and forwards `/api/*` to the api-gateway Function URL with the origin-verify header.
2. **api-gateway Lambda** validates JWT + origin header, applies Redis-backed rate limiting, and forwards requests to the downstream service Function URLs (`portfolio`, `market-data`, `insight`).
3. **Downstream service Lambdas** execute the Spring Boot 4 logic, talk to RDS-compatible Postgres, MongoDB Atlas, Aiven Kafka, and Upstash Redis.

---

## ## Phase 2: Application Layer (Java 25 & Spring Boot 4.x) — ✅ Implemented
*Goal: Leverage the latest LTS features for performance and modularity.*

### Key Technologies
* **Framework:** **Spring Boot 4.0.x** on **Java 25** (Amazon Corretto 25 base image, custom `jlink` JRE).
* **Build:** **Multi-module Gradle** build (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`, `common-dto`).
* **Frontend:** **Next.js 16** with **React 19**, TanStack Query, Better Auth (HS256 JWT mint), Playwright for E2E, Vitest + MSW for unit tests, Pact for consumer contracts.
* **Persistence (Polyglot):**
    * **PostgreSQL (Relational):** Master record for user profiles, transactions, portfolios, holdings, and the projected `market_prices` read model.
    * **MongoDB (Document):** Flexible storage for raw market ticks and seeded snapshots.
    * **Redis:** Distributed rate limiting (api-gateway) and the insight-service ticker cache (`market:latest:*`, `market:history:*`).
* **Messaging:** **Apache Kafka** (Aiven SASL_SSL in production) carries `PriceUpdatedEvent` from `market-data-service` to `portfolio-service` and `insight-service`. A Dead-Letter Topic (`market-prices.DLT`) is configured on the portfolio consumer for poison messages.

### Implementation Notes
* Java 25 features (Virtual Threads, Scoped Values, Structured Concurrency) are used inside the resilience-wrapped `ExternalMarketDataClient`.
* Spring Data MongoDB and Spring Data JPA coexist in the same build via per-service auto-configuration.

---

## ## Phase 3: AI Integration & Frontend Wire-up — ✅ Implemented
*Goal: Incorporate Enterprise-grade Generative AI and surface it through the Next.js frontend.*

### AI Strategy
* **Model Selection:** **Anthropic Claude Haiku 4.5** on **Amazon Bedrock** (`bedrock` Spring profile). A `MockAiInsightService` covers all non-prod profiles with deterministic responses.
* **Security:** IAM execution roles grant the insight-service Lambda permission to invoke Bedrock — no API keys are stored in code or env vars.
* **RAG (Roadmap):** S3 + Bedrock Knowledge Bases remain on the forward-looking roadmap.

### Frontend Integration (delivered)
* `/ai-insights` page composes `MarketSummaryGrid` (TanStack Query → `GET /api/insights/market-summary`) and `ChatInterface` (Next.js Server Action → `POST /api/chat`).
* `useMarketSummary` and `useTickerSummary` hooks live in `frontend/src/lib/hooks/useInsights.ts`.
* MSW handlers and Vitest coverage gate the data layer in CI; Playwright covers the rendered page in `frontend-e2e-integration.yml` and the live AWS deployment in `synthetic-monitoring.yml`.

---

## ## Phase 4: Operational Hardening — ✅ Implemented (late April 2026)
*Goal: Move the original "Phase 3 backlog" controls into the active deployment.*

* **Redis-backed distributed rate limiting:** `GatewayRateLimitConfig` in `api-gateway` is fully Redis-backed (Lettuce + Upstash TLS), confined to `application-local.yml` / `application-prod.yml` so AWS deployments never trigger a stray autoconfig in non-Redis profiles.
* **Kafka Dead-Letter Queue:** `portfolio-service` registers `MalformedEventException` as non-retryable and routes failures to `market-prices.DLT` via Spring Kafka primitives.
* **Lambda cold-start mitigation:** Terraform `warming` module wires four EventBridge rules + API Destinations to `/actuator/health` per Function URL with an SNS alarm on concurrency. The module is gated by `enable_warming` and currently parked for free-tier cost reasons (see `docs/changes/CHANGES_CACHE_WARMING_2026-04-30.md`).
* **State drift fix:** Lambda `FunctionURLAllowInvokeAction` permissions adopted into Terraform state via root-module `import` blocks, eliminating the recurring 409 conflict on every apply (PR #26).
* **Truststore unification:** `common-dto` ships a canonical `truststore.jks` and `TruststoreExtractor` so Lambda's read-only filesystem cooperates with Aiven Kafka and Upstash Redis at startup.

---

## ## Phase 5: The "Architect Demo" — ⏸️ Optional / Future
*Goal: Demonstrate Enterprise High Availability (HA) and Scalability during interviews.*

This phase is intentionally deferred — the Lambda + CloudFront stack already covers the demo surface area. If invoked:

1. **Containerize for ECS:** Reuse the existing arm64 container images (already published to ECR for Lambda).
2. **Deploy via ECS Express:** Automated deployment creating an **Application Load Balancer (ALB)** and **Fargate** tasks across multiple Availability Zones.
3. **Update DNS:** Change the **Route 53** alias to point from CloudFront to the new **ALB**.
4. **Cleanup:** After the demo, tear down the ECS stack to stop hourly charges and revert to the Lambda setup.

---

## ## Estimated Monthly Cost Summary (Plan 1)

| Service | Cost (Est. / Mo) | Reason |
| :--- | :--- | :--- |
| **Route 53** | ~$1.50 | 1 Hosted Zone + domain amortization |
| **Lambda (arm64)** | $0.00 | Under 1M requests / 400k GB-s per month (Free Tier). EventBridge warming is feature-flagged off by default. |
| **CloudFront** | $0.00 | Under 1 TB data transfer (Free Tier), `PriceClass_100` |
| **Amazon Bedrock** | ~$0.10 – $1.00 | Pay-per-token Claude Haiku 4.5 usage |
| **PostgreSQL / MongoDB** | Variable | RDS Free Tier + MongoDB Atlas Free Tier; Aiven Kafka + Upstash Redis on free tiers |
| **Total** | **~$2.00 – $3.00** | **Production-ready URL with AI capability** |

---

## ## Key Interview "Signal" Points
* **Polyglot Persistence:** "I used PostgreSQL for transactional integrity and MongoDB for the flexible schema required by fast-changing market data."
* **Modern Java:** "Leveraging Java 25 and Spring Boot 4.0 allowed me to use Virtual Threads for non-blocking I/O when fetching news for the AI engine, and a custom `jlink` JRE keeps the Lambda image lean."
* **Serverless via the Lambda Web Adapter:** "The same Spring Boot containers I run in Docker Compose locally execute unmodified on Lambda arm64 — the LWA sidecar translates Lambda invocations into HTTP on port 8080."
* **IaC discipline:** "Terraform replaced the original CDK stack; `import` blocks in the root module reconcile pre-existing AWS resources without recreating them, and bootstrap state lives in S3 + DynamoDB."
* **FinOps Architecture:** "The system is architected to survive at a $3/month baseline, with the ability to scale to a full containerized cluster as needed."