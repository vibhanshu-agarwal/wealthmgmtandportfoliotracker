# Integration Test Cases — Wealth Management & Portfolio Tracker

This document lists integration and end-to-end test cases to validate core flows across services, gateway, and infrastructure. The pipeline runs on **GitHub Actions** with the following workflows:

- **`ci-verification.yml`** — fanout pipeline: unit tests → integration tests (Testcontainers) → Pact consumer tests (frontend) → Docker build + Pact provider verification + Playwright E2E against a fully-booted Docker Compose stack.
- **`frontend-e2e-integration.yml`** — Playwright E2E focused on the Next.js 16 surface, with the backend stack booted via Docker Compose and seeded through the Golden-State seeder.
- **`synthetic-monitoring.yml`** — Playwright `aws-synthetic` project run hourly (currently parked, runnable on demand) against the live CloudFront origin `https://vibhanshu-ai-portfolio.dev`. Pre-warms each Lambda Function URL via `/actuator/health` before seeding.
- **`terraform.yml`** — `terraform fmt`/`validate`/`plan`/`apply` against the AWS account; gates infrastructure changes.
- **`gitleaks.yml`**, **`qodana_code_quality.yml`**, **`frontend-ci.yml`**, **`ci.yml`** — lint, secret-scanning, and type-check guard rails.

## Test strategy
- **Unit:** JUnit + Mockito (backend); Vitest + RTL + MSW (frontend).
- **Integration (backend):** Testcontainers for Postgres, MongoDB, Kafka, and Redis. Tagged with JUnit `@Tag("integration")` and executed by the `integrationTest` Gradle task (see root `build.gradle`).
- **Contract tests:** Pact consumer tests in `frontend/` (`vitest.pact.config.ts` → `frontend/pacts/`) and Pact provider verification in `portfolio-service` and `insight-service` (`*PactVerification*` JUnit classes).
- **E2E:** Playwright runs against Docker Compose locally and in `ci-verification.yml`/`frontend-e2e-integration.yml`. The same Playwright project (`aws-synthetic`) targets the live CloudFront deployment from `synthetic-monitoring.yml`.
- **Infrastructure:** Terraform `fmt`/`validate` in `terraform.yml`. Functional verification of Lambda + Function URLs is implicit — the synthetic-monitoring suite is the canary against the deployed stack.

---

## 1. Gateway routing, JWT auth, and Redis rate limit

Test: Gateway routes requests correctly to services and enforces distributed rate limits
- Objective: Ensure API Gateway forwards HTTP requests to correct backend paths, validates JWTs via `AUTH_JWT_SECRET`, rejects requests missing the CloudFront `X-Origin-Verify` header (in production), and enforces **Redis-backed distributed rate limits** (`GatewayRateLimitConfig`, default 20 r/s, burst 40).
- Setup: api-gateway + Testcontainers Redis + downstream stubs (WireMock) or real services.
- Steps:
  1. Send HTTP GET `/api/portfolio/{id}` to Gateway with a valid HS256 JWT.
  2. Assert request forwarded to portfolio-service with the spoofed `X-User-Id` stripped and re-injected from the verified `sub` claim.
  3. Flood Gateway with requests > rate limit, assert 429 responses keyed by user `sub` (or client IP for anonymous).
  4. Send a request without `X-Origin-Verify` while the production filter is active and assert 403.
- Expected: Successful forwarding under limit; 429 when exceeded; 403 when origin header is missing.

---

## 2. Market ingestion -> Kafka publish

Test: Market service ingests a price update and publishes a `PriceUpdatedEvent`
- Objective: Verify `market-data-service` persists the latest price in MongoDB and emits a `PriceUpdatedEvent` keyed by ticker on the `market-prices` Kafka topic.
- Setup: `market-data-service` wired to Testcontainers MongoDB + Testcontainers Kafka (`org.testcontainers:kafka`).
- Steps:
  1. `POST /api/market/prices/{ticker}` with `{ price, timestamp }`.
  2. Assert document persisted in `market_prices` Mongo collection.
  3. Consume from `market-prices` Kafka topic and validate payload matches the `common-dto` `PriceUpdatedEvent` contract; assert message key equals the ticker.
- Expected: DB persistence and correctly-keyed event published.

---

## 3. Portfolio consumes price update -> valuation projection

Test: `portfolio-service` consumes `PriceUpdatedEvent` and updates the read model
- Objective: End-to-end event flow from market update to portfolio valuation change via the idempotent `MarketPriceProjectionService`.
- Setup: `portfolio-service`, Testcontainers Postgres, Testcontainers Kafka; pre-seed a portfolio and holdings.
- Steps:
  1. Publish a `PriceUpdatedEvent` to the `market-prices` topic.
  2. Wait until `PriceUpdatedEventListener` processes the event.
  3. Query `GET /api/portfolio/{userId}` and assert the valuation reflects the new price; assert the `market_prices` table reflects the upsert.
- Expected: Portfolio valuation reflects the new market price; duplicate deliveries are no-ops thanks to `INSERT ... ON CONFLICT ... IS DISTINCT FROM`.

---

## 4. Insight enrichment flow

Test: `insight-service` consumes price events and serves enriched market summary
- Objective: Validate that `insight-service` consumes `PriceUpdatedEvent` into Redis and that `/api/insights/market-summary` returns the enriched `TickerSummary`.
- Setup: `insight-service` wired to Testcontainers Redis + Testcontainers Kafka; mock `AiInsightService` (`@Profile("!bedrock")`).
- Steps:
  1. Publish `PriceUpdatedEvent` records for several tickers.
  2. Assert `market:latest:{ticker}` and `market:history:{ticker}` keys are populated in Redis.
  3. Call `GET /api/insights/market-summary` and assert the response shape matches `MarketSummaryResponse` (per `frontend/src/types/insights.ts`).
- Expected: Redis cache hydrated; HTTP response consistent with the Pact contract.

---

## 5. Consumer idempotency, retries, and Dead-Letter Topic

Test: Portfolio Kafka consumer handles duplicates, retries, and routes poison messages to `market-prices.DLT`
- Objective: Verify Spring Kafka's `DefaultErrorHandler` retries transient failures, that idempotent projection writes tolerate duplicates, and that `MalformedEventException` (registered as non-retryable) routes records to `market-prices.DLT`.
- Steps:
  1. Send the same `PriceUpdatedEvent` twice (same event payload + key); assert no double-application of valuation changes.
  2. Inject a transient failure on first processing; assert the consumer retries and eventually succeeds.
  3. Publish a malformed record that triggers `MalformedEventException`; assert no retries and the record lands on `market-prices.DLT` with the original key.
- Expected: Idempotent behavior; bounded retries on transient errors; immediate DLT routing for malformed records.

---

## 6. Pact contract compatibility

Test: Pact consumer + provider verification
- Objective: Prevent breaking changes between the Next.js frontend and the `portfolio-service` / `insight-service` HTTP contracts.
- Steps:
  1. `npm run test:pact` from `frontend/` generates contracts under `frontend/pacts/`.
  2. `ci-verification.yml` uploads the contracts as an artifact and consumes them in the `docker-build-verify` job.
  3. `./gradlew :portfolio-service:test --tests '*PactVerification*' :insight-service:test --tests '*PactVerification*'` re-plays the contracts against the providers using MockMvc.
- Expected: Provider verification passes; contract drift fails the pipeline.

---

## 7. Data persistence and migration check

Test: Backend persistence correctness across Postgres + MongoDB
- Objective: Validate JPA mappings, Mongo collections, and seeded fixtures.
- Steps:
  1. Start each service with a fresh Testcontainers DB; run startup migrations.
  2. Confirm `LocalMarketDataSeeder` populates `market_prices` from `config/seed-tickers.json` when the collection is empty.
  3. Seed data, run CRUD operations, verify constraints and indexes.
- Expected: Migrations succeed; seeded data is present; CRUD operations behave as expected.

---

## 8. Full E2E UI flow (Playwright — Docker Compose)

Test: Login → View portfolio → AI Insights → see updated valuation after market update
- Objective: Validate the full user journey across the Next.js 16 frontend and the four-service backend.
- Setup: `docker compose up -d` (Postgres, Mongo, Kafka, Redis, all four services); Golden-State seeder run via `INTERNAL_API_KEY` to populate the test user.
- Steps:
  1. Run `npx playwright test tests/e2e/golden-path.spec.ts tests/e2e/dashboard-data.spec.ts` from `frontend/` (mirrors `ci-verification.yml`).
  2. Authenticate as the test user, navigate through `/portfolio` and `/ai-insights`.
  3. Inject a market price change (via `POST /api/market/prices/{ticker}` or Kafka) and wait for the portfolio API to reflect the update.
  4. Assert UI shows updated valuation, charts, and AI sentiment.
- Expected: UI reflects updated valuations within the configured polling/`refetchInterval` window (60s for `useMarketSummary`).

---

## 9. Synthetic monitoring (Playwright — live AWS)

Test: `aws-synthetic` Playwright project against `https://vibhanshu-ai-portfolio.dev`
- Objective: Continuously verify that the live CloudFront → Lambda stack is healthy.
- Setup: `synthetic-monitoring.yml` (cron `0 * * * *`, currently parked / `workflow_dispatch` only).
- Steps:
  1. Pre-warm Lambda Function URLs by hitting `/actuator/health`, `/api/portfolio/health`, `/api/market/health`, `/api/insights/health` (3 passes, 10s back-off on 5xx/timeout).
  2. Run `npx playwright test --project=aws-synthetic`.
  3. `SKIP_BACKEND_HEALTH_CHECK=true` keeps Playwright from racing Lambda cold starts in `global-setup`.
- Expected: All synthetic flows pass; report uploaded as a workflow artifact.

---

## 10. Security / auth tests

Test: Auth and origin verification enforcement
- Objective: Ensure gateway enforces JWT, CloudFront origin secret, and rate limits.
- Steps:
  1. Call protected endpoints without token → 401.
  2. Call endpoints with malformed/expired token → 401.
  3. Call protected endpoints with a valid HS256 JWT → 200; `X-User-Id` injected from `sub`.
  4. Call Lambda Function URL directly without `X-Origin-Verify` → 403 (production filter).
  5. `auth-jwt-health.spec.ts` Playwright preflight asserts mint/verify round-trip with `AUTH_JWT_SECRET`.
- Expected: Status codes match the table; Gitleaks (`gitleaks.yml`) blocks any secret exfiltration in PRs.

---

## Test-data and tooling recommendations
- Use Testcontainers for Postgres/Mongo/Kafka/Redis in backend CI for repeatable tests.
- Reuse the Docker Compose stack for E2E in `ci-verification.yml`; reuse the `aws-synthetic` Playwright project for live monitoring.
- Drive Golden-State seeding from `INTERNAL_API_KEY` (sourced from `TF_VAR_INTERNAL_API_KEY` in CI, `.env.secrets` locally).
- Automate Pact contract tests as part of CI with failure gating.



