# Core Architectural Guardrails & Project State

## 1. Project Context

- **Active Roadmap:** Refer to `docs/agent-instructions/ROADMAP_AI_POWERED_WEALTH_TRACKER.md` for current feature planning. Disregard older roadmaps unless explicitly instructed.
- **Documentation:** Always update `docs/changes` after significant implementations. Review `docs/adr` before proposing new system designs.
- **Active / Implemented (late April 2026):**
  1. **Redis-backed distributed rate limiting** in api-gateway (`GatewayRateLimitConfig`) — replaces the previous in-memory limiter; Lettuce + Upstash TLS in production, Testcontainers Redis in tests.
  2. **Kafka Dead-Letter Topic** (`market-prices.DLT`) in portfolio-service — `MalformedEventException` registered as non-retryable on `DefaultErrorHandler`.
  3. **Lambda cold-start mitigation** via the Terraform `warming` module (**EventBridge Rules + API Destinations** at `rate(5 minutes)` hitting `/actuator/health` on each Function URL + CloudWatch alarm on `ConcurrentExecutions ≥ 8` → SNS email). Rules are used instead of EventBridge Scheduler because Scheduler does not accept API Destination ARNs as targets. Currently parked behind `enable_warming = false` for free-tier conservation; see `docs/changes/CHANGES_CACHE_WARMING_2026-04-30.md`.
  4. **Terraform serverless infrastructure** is the single source of truth — the legacy AWS CDK code is deprecated.
- **Open / Backlog:**
  1. Event schema registry + topic versioning convention.
  2. Event-id dedup ledger for high-value consumers.
  3. Consumer-lag dashboards / distributed tracing baseline.

## 2. Infrastructure & Cost (STRICT)

- **Zero-Cost Free Tier:** AWS Free Tier eligible resources only. No NAT Gateways, Multi-AZ RDS, or Provisioned IOPS. The ap-south-1 account-level cap is 10 unreserved concurrent executions, so `reserved_concurrent_executions` is intentionally **omitted** on every Lambda — reserving any value would block other functions from running. CloudFront uses `PriceClass_100`.
- **Terraform-only:** All AWS resources are provisioned via `infrastructure/terraform/`. The four Spring Boot services run as **AWS Lambda functions on arm64 / Graviton2** using container images (ECR) and the **Lambda Web Adapter** sidecar — no ECS, ALB, NAT Gateway, RDS, or ElastiCache resources may appear in `.tf` files.
- **Managed vs. Standard:** Where running a "standard" service (Kafka, Redis) on an EC2 instance would violate Free Tier constraints, use a managed external provider (Aiven Kafka, Upstash Redis, MongoDB Atlas) and keep the application code abstracted behind Spring interfaces.

## 3. Application Code & Multi-Cloud Agnosticism

- **Hexagonal Architecture:** The core domain logic MUST remain pure.
- **No Cloud Lock-in:** Do NOT use AWS-specific SDKs (e.g., `software.amazon.awssdk`) inside the core business logic or domain layers. The Lambda Web Adapter keeps Spring Boot HTTP code identical between local Docker Compose and AWS Lambda.
- **Abstractions:** Use framework-level abstractions (Spring Cloud Stream, Spring Data JPA / Interfaces) so the application can be seamlessly ported to Azure or GCP in the future.

## 4. Testing Strategy

- **Layered Testing:** Every layer must have automated tests (Unit, Integration, Architecture, Contract).
- **Local Validation:** The application must be fully testable locally without deploying to real AWS. Use Testcontainers (Postgres, MongoDB, Kafka, Redis) for integration tests; LocalStack for Terraform-driven AWS resource tests.
- **Pact Contracts:** Pact consumer tests run from `frontend/` (`vitest.pact.config.ts`); provider verification runs in `portfolio-service` and `insight-service`.
- **E2E:** Playwright runs against Docker Compose in `ci-verification.yml`/`frontend-e2e-integration.yml` and against the live CloudFront stack in `synthetic-monitoring.yml`.
- **Infrastructure Testing:** `terraform fmt -check -recursive` and `terraform validate` are gated in `terraform.yml`. Module-level Jest tests under `infrastructure/test/` cover legacy CDK assertions and Lambda config invariants.

## 5. Specific Architectural Nuances to Enforce

- **Rate Limiting & Caching (Redis):** Redis is the approved backend for distributed rate limiting **and** for the insight-service ticker cache. All Redis configuration is confined to `application-local.yml` and `application-prod.yml` so the default `application.yml` cannot trigger Spring Boot's Redis autoconfiguration in non-Redis profiles.
- **Cold-Start Mitigation:** Lambda functions are deployed as container images on **arm64 / Graviton2** with a custom `jlink` JRE (Amazon Corretto 25) to keep cold starts manageable. Function URLs attach to the published `live` alias (not `$LATEST`), which lets us bolt on SnapStart or provisioned concurrency without re-pointing CloudFront. Active runtime mitigation comes from the Terraform `warming` module (EventBridge Rules + API Destinations at `rate(5 minutes)` → `/actuator/health` on each Function URL, with a CloudWatch `ConcurrentExecutions ≥ 8` alarm wired to SNS); `enable_provisioned_concurrency` is available as an optional escalation. **GraalVM Native Images** remain a future lever and are not currently wired.
- **CloudFront Origin Security:** CloudFront injects `X-Origin-Verify` and the api-gateway `CloudFrontOriginVerifyFilter` returns 403 to any request missing the header. Direct Lambda Function URL access is therefore blocked.
- **Strict Profile Isolation:** Never mix local infrastructure credentials or URLs (e.g., `localhost:6379`) into the main or AWS profiles. Production secrets flow only via `TF_VAR_*` → Terraform sensitive variables → Lambda env vars.
