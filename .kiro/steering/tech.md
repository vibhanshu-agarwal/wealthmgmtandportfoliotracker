# Tech Stack

## Backend

- **Language:** Java 25
- **Framework:** Spring Boot 4.0.5
- **Build:** Gradle (multi-module, Groovy DSL)
- **Spring Cloud:** 2025.1.1 (Gateway WebFlux for api-gateway)
- **Persistence:** Spring Data JPA + PostgreSQL (portfolio-service), Spring Data MongoDB (market-data-service)
- **Migrations:** Flyway (portfolio-service)
- **Messaging:** Apache Kafka (Spring Kafka)
- **Caching:** Redis (api-gateway rate limiting)
- **Testing:** JUnit 5, Testcontainers, Spring Boot Test

## Frontend

- **Framework:** Next.js 16 (App Router, standalone output)
- **Language:** TypeScript 5
- **UI:** React 19, Tailwind CSS 3, shadcn/ui (Radix UI primitives)
- **Data fetching:** TanStack Query v5
- **Charts:** Recharts
- **Unit testing:** Vitest 3 + Testing Library + MSW
- **E2E testing:** Playwright

## Infrastructure

- **Local:** Docker Compose (Postgres 16, MongoDB 7, Kafka KRaft, Redis)
- **Cloud:** AWS CDK v2 (TypeScript)

---

## Common Commands

### Backend (run from repo root)

```bash
# Build all modules
./gradlew build

# Run unit tests only (excludes @Tag("integration"))
./gradlew test

# Run integration tests (Testcontainers)
./gradlew integrationTest

# Run all checks (unit + integration)
./gradlew check

# Run a specific service locally
./gradlew :portfolio-service:bootRun
./gradlew :market-data-service:bootRun
./gradlew :insight-service:bootRun
./gradlew :api-gateway:bootRun
```

### Frontend (run from `frontend/`)

```bash
npm run dev          # Start dev server
npm run build        # Production build
npm run test         # Vitest unit tests (single run)
npm run test:e2e     # Playwright E2E tests
npm run lint         # ESLint
npm run lint:fix     # ESLint with auto-fix
```

### Infrastructure (run from `infrastructure/`)

```bash
npm run build        # Compile CDK TypeScript
npm run test         # Jest CDK tests
npx cdk deploy       # Deploy to AWS
npx cdk diff         # Preview changes
```

### Docker

```bash
# Start all local infrastructure + services
docker compose up -d

# Start only infrastructure dependencies
docker compose up -d postgres mongodb kafka redis
```

---

## Key Conventions

- Integration tests must be annotated with `@Tag("integration")` — they run via `integrationTest` task, not `test`
- All JVM processes run with `-Duser.timezone=Asia/Kolkata`
- Frontend API calls proxy through `/api/*` → `http://localhost:8080/api/*` (Next.js rewrite)
- Jackson version is pinned at 2.18.2 across all modules via dependency management

---

## Architectural Guardrails

### Multi-Cloud Agnosticism

- Core domain logic must remain pure — no AWS-specific SDKs inside business/domain layers
- Use framework abstractions: Spring Cloud Stream (not Kafka-specific APIs), Spring Data interfaces (not vendor SDKs)
- Goal: application code must be portable to Azure or GCP without rewriting domain logic

### Hexagonal Architecture

- Keep domain logic decoupled from infrastructure concerns (Kafka, Redis, DB drivers)
- Infrastructure adapters live at the edges; domain services must not import them directly

### AWS Deployment (Free Tier Strict)

- Do NOT provision: NAT Gateways, Multi-AZ RDS, Provisioned IOPS
- Prefer serverless equivalents when managed services exceed Free Tier (e.g. SQS instead of MSK)
- Active deployment plan: Route 53 → CloudFront → Lambda (Spring Cloud Function) — see `docs/agent-instructions/ROADMAP_AI_POWERED_WEALTH_TRACKER.md`
- Scale-up path: ECR → ECS Fargate + ALB (Phase 4 demo only)

### Spring Profiles — Strict Isolation

- Use `application-local.yml` for local/Docker config and `application-aws.yml` for cloud config
- NEVER put `localhost` URLs, local ports, or local credentials in `application.yml` or AWS profiles
- Profile separation must allow swapping Redis for AWS API Gateway usage plans or DynamoDB if ElastiCache becomes unviable (12-month Free Tier limit)

### Redis / Rate Limiting

- Redis is approved for local dev (Docker Compose / Testcontainers) only
- Rate limiting must be profile-aware so the backing store can be swapped without code changes
- Abstraction must support: Redis (local), AWS API Gateway native usage plans, or DynamoDB (AWS) — switchable via config/profile only

### Cold-Start Mitigation (Serverless)

- For Phase 3/4 Lambda deployments, Spring Boot apps must be optimized for cold starts
- Build configs must be prepared to support GraalVM Native Image or AWS SnapStart
- Avoid startup-time classpath scanning and heavy eager initialization in service beans

### Testing Requirements

- Every layer needs automated tests: Unit, Integration, Architecture
- Local infra testing uses Testcontainers (including LocalStack for AWS services)
- Do not test against real AWS — all integration tests must run locally

---

## Pending TODOs (High Priority)

These are known gaps that should be resolved before adding new features:

1. **Redis-backed rate limiting** — `api-gateway/.../RequestRateLimitFilter.java:81`  
   Replace local in-memory limiter with Redis distributed rate limiting.

2. **Kafka Dead-Letter Queue** — `portfolio-service/.../PriceUpdatedEventListener.java:29`  
   Route malformed/poison Kafka events to a DLQ strategy after retries.

Full TODO list: `docs/todos/TODOS_2026-04-07.md`
