# Integration Test Cases — Wealth Management & Portfolio Tracker

This document lists integration and end-to-end test cases to validate core flows across services, gateway, and infrastructure. Use Testcontainers, Kafka test harnesses, and Docker Compose for local runs; use Playwright for UI E2E.

## Test strategy
- Use Testcontainers for Postgres/Mongo/Kafka when running backend integration tests.
- Use contract tests (Pact/consumer-driven or simple JSON contract tests) for common-dto-based interactions.
- Execute E2E with the full Docker Compose stack for smoke tests.

---

## 1. Gateway routing and basic auth

Test: Gateway routes requests correctly to services and enforces rate limit
- Objective: Ensure API Gateway forwards HTTP requests to correct backend paths and enforces rate limits via Redis.
- Setup: Gateway + Redis + stubbed service (or actual service)
- Steps:
  1. Send HTTP GET /api/portfolios/{id} to Gateway
  2. Assert request forwarded to portfolio-service and response returned
  3. Flood Gateway with requests > rate limit, assert 429 responses
- Expected: Successful forwarding under limit; 429 when exceeded.

---

## 2. Market ingestion -> Kafka publish

Test: Market service ingests price update and publishes Kafka event
- Objective: Verify market-data-service stores price in Mongo and emits a price-change event.
- Setup: Market service wired to Testcontainers Mongo and embedded Kafka (or Testcontainers Kafka)
- Steps:
  1. POST /api/market/prices { symbol, price, timestamp }
  2. Verify document persisted in Mongo
  3. Consume from Kafka price-change topic and validate payload matches contract
- Expected: DB persistence and correct event published.

---

## 3. Portfolio consumes price-change -> valuation update

Test: Portfolio service consumes price-change event and recalculates valuations
- Objective: End-to-end event flow from market update to portfolio valuation change
- Setup: Portfolio service, Testcontainers Postgres, Kafka broker available; pre-seed a portfolio and holdings
- Steps:
  1. Publish a price-change event to Kafka (or POST to Market which publishes)
  2. Wait/consume until portfolio-service processes event
  3. Query portfolio endpoint and assert valuation updated accordingly
- Expected: Portfolio valuation reflects new market price (allow eventual consistency with retry windows).

---

## 4. Insight generation flow

Test: Insight-service consumes events and writes derived insights
- Objective: Validate that insight-service consumes relevant Kafka events and persists or emits insight outputs
- Steps:
  1. Trigger events (price-change, trade-events)
  2. Assert insight-service writes expected output (DB record or emits to topic)
- Expected: Correct insight records produced.

---

## 5. Consumer idempotency and retries

Test: Ensure event consumers are idempotent and handle duplicate messages
- Objective: Verify portfolio-service and insight-service handle duplicate Kafka deliveries and transient failures
- Steps:
  1. Send same price-change event twice (same event-id)
  2. Observe no double-application of valuation changes
  3. Simulate transient failure (throw exception on first processing), ensure consumer retries and eventually succeeds exactly-once-ish (idempotent)
- Expected: Idempotent behavior; no duplicated side-effects.

---

## 6. Schema/contract compatibility (common-dto contract tests)

Test: Contract tests for DTOs used across services
- Objective: Prevent breaking changes to shared DTOs
- Steps:
  1. Run consumer-driven contract tests (or snapshot tests) to assert producer payloads match consumer expectations
  2. Fail CI if contract mismatch found
- Expected: Contracts remain compatible or are versioned/flagged.

---

## 7. Data persistence and migration check

Test: Backend persistence correctness and migration scripts
- Objective: Validate JPA mappings and migrations (if using Flyway/Liquibase)
- Steps:
  1. Start service with fresh DB and run migrations
  2. Seed data, run CRUD operations, verify constraints and indexes
- Expected: Migrations succeed and data operations behave as expected.

---

## 8. Full E2E UI flow (Playwright)

Test: Login -> View portfolio -> see updated valuation after market update
- Objective: Validate full user journey from UI to backend event-driven update
- Steps:
  1. Start Docker Compose stack (Postgres, Mongo, Kafka, Redis, services)
  2. Use Playwright to load UI, authenticate (or use test token), open portfolio view
  3. Inject market price change (API or Kafka) and wait for portfolio API to reflect update
  4. Assert UI shows updated valuation/graphs
- Expected: UI reflects updated valuations within acceptable delay.

---

## 9. Performance / load smoke tests

Test: Basic load on gateway and event throughput
- Objective: Validate system stability for typical expected load
- Steps:
  1. Run a load script that simulates N concurrent users fetching portfolios and market updates
  2. Monitor broker lag, service errors, DB connections
- Expected: Acceptable latency, no critical errors; plan scaling if limits reached.

---

## 10. Security / auth tests

Test: Auth and authorization enforcement
- Objective: Ensure gateway or services enforce authentication, scopes, and tenant/isolation rules
- Steps:
  1. Call endpoints without token -> 401
  2. Call endpoints with insufficient scope -> 403
  3. Call endpoints with valid token -> 200
- Expected: Proper status codes.

---

## Test-data and tooling recommendations
- Use Testcontainers for DBs and Kafka in backend CI for repeatable tests.
- Use a small, dedicated Docker Compose profile for E2E that can be run in CI for nightly/blocked runs.
- Add a lightweight fake market-data producer for deterministic tests.
- Automate contract tests as part of CI with failure gating.



