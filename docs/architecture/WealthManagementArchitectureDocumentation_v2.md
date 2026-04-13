# Architecture Documentation (v2)

This document captures the current, detailed architecture and operational guidance for the Wealth Management & Portfolio Tracker.

1. High-level components
- Frontend: Next.js (React, TypeScript) — client-facing UI, uses React Query for server state and Playwright/Vitest/MSW for tests. The frontend communicates only with the API Gateway.
- API Gateway: Spring Cloud Gateway — central public API, routing, auth pass-through, rate-limiting (Redis), and request/response correlation headers for tracing.
- Services:
  - portfolio-service: PostgreSQL, Spring Data JPA. Responsible for portfolios, holdings, positions, transaction history, and on-demand valuation endpoints. Applies business rules and exposes REST endpoints under /api/portfolios.
  - market-data-service: MongoDB for raw market ticks and aggregated snapshots. Accepts market feeds and publishes price-change events to Kafka.
  - insight-service: Consumes events from Kafka to produce derived analytics, signals, and enrichments. Stores results in a service-owned store (Postgres or Mongo depending on insight type).
  - common-dto: Shared module for message contracts and DTOs. Keep domain entities in service boundaries; only message contracts live here.

2. Messaging and event design
- Broker: Apache Kafka for asynchronous communication. Topics include price-change.v1, trade-events.v1, insight-events.v1. Topic names are versioned.
- Schema governance: Use a schema registry or clear versioning rules. Payloads are backward-compatible where possible; breaking changes require publishing a new topic version.
- Partitioning strategy: Partitions are chosen to keep ordering where required (e.g., partition by symbol or portfolio-id for price and trade topics). Ensure consumers use matching partitioning keys.
- Consumer behavior:
  - Idempotency: All consumers persist processed event-ids or offsets per aggregate to prevent duplicate side-effects.
  - Retries: Transient errors should trigger controlled retries with dead-lettering for poison messages.
  - Outbox pattern: Services that emit events do so via a transactional outbox to guarantee event publish-at-commit semantics.

3. Data stores and patterns
- PostgreSQL: Primary transactional store for portfolio-service. Use migrations (Flyway/Liquibase) and connection pool sizing appropriate for expected concurrency.
- MongoDB: Append-heavy store for raw market ticks and time-series-ish documents. Use TTLs for raw tick data; keep aggregated snapshots for fast reads.
- Redis: Used by the Gateway for rate-limiting and optionally for caching frequently accessed aggregates.

4. API design and contracts
- Gateway exposes a stable API surface (e.g., /api/portfolios, /api/market, /api/insights). Internal services expose their own admin/health endpoints but are not public.
- Authentication: Prefer centralized Gateway auth (OIDC/OAuth2). Propagate authenticated identity and scopes via JWT and correlation headers.
- Versioning: API routes and event topics are versioned independently. Consumers should declare supported topic versions.

5. Testing strategy
- Unit tests: JUnit, Mockito (backend); Vitest for frontend components.
- Integration tests: Testcontainers for Postgres, MongoDB, and Kafka. Use consumer harnesses to assert event publication/consumption.
- Contract tests: Consumer-driven contracts or snapshot-based contract checks for common-dto messages. Run in CI to prevent incompatible changes.
- E2E tests: Playwright to validate key user flows, run against Docker Compose or a staging cluster.

6. CI/CD and environments
- CI: GitHub Actions runs linting, unit tests, contract tests, and a pared-down integration stage using Testcontainers.
- CD: Helm charts and Kubernetes manifests in infrastructure/ support staging and production deployments. Docker Compose remains a local developer convenience.
- Gating: Pull requests must pass: build, unit tests, contract tests, and static analysis (Qodana/ESLint/Type checks).

7. Observability and operations
- Metrics: Prometheus metrics exported by services; Gateway and consumers should expose consumer lag, processed-count, and error rates.
- Tracing: OpenTelemetry instrumentation with traces propagated across Gateway -> service -> Kafka flows (include correlation IDs in events).
- Logging: Structured JSON logs with correlation IDs and event metadata; central collection via ELK/Tempo/OTEL collector.
- Alerts: Define alerts for consumer lag, high processing errors, or reconciliation failures.

8. Reliability & data-correctness patterns
- Reconciliation jobs: Periodic jobs validate portfolio valuations vs aggregated market snapshots and emit recon events or create tickets for manual review.
- Exactly-once semantics: Aim for idempotent processing and transactional outbox; if strong exactly-once is required, evaluate Kafka Streams or transactional stores with careful design.
- Backpressure & throttling: Gate high-volume market feeds at ingress, use batching and compaction where possible for storage.

9. Security
- Secrets: Store creds in vault (e.g., HashiCorp Vault or cloud secret manager) for staging/prod. CI secrets limited and rotated.
- Network: In production, services should be internal-only behind load balancers and the API Gateway. Use mTLS within the cluster if required.

10. Migration and versioning guidance
- When changing common-dto: prefer additive fields and default values. If breaking change required, publish to new topic version and migrate consumers gradually.
- Database migrations: Keep reversible or safely forward migrations and run migrations as part of deploy pipeline with health checks.

11. Runbooks & incident response
- Consumer lag: check Kafka consumer-group lag, restart consumer with offset reset if needed, or reprocess with recon job.
- Poison messages: move to dead-letter queue, investigate schema/handler bug, re-publish after fix.
- Valuation discrepancy: run reconciliation job and compare feed vs stored prices; roll-forward corrections or create compensating transactions.

12. Next improvements backlog
- Add schema registry integration and automated compatibility checks in CI.
- Formalize outbox implementation in portfolio-service and market-data-service.
- Add a lightweight staging cluster for full E2E nightly runs.
- Expand contract tests coverage across all message types.

Appendices
- File references: infrastructure/, docs/architecture/*.puml, common-dto/
- Contact: service owners listed in repo README

