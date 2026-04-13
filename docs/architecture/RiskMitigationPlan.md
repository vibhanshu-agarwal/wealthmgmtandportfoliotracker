# Risk & Mitigation Plan — Wealth Management & Portfolio Tracker

This document identifies key architectural risks and recommended mitigations for the system. Prioritize high-impact, high-likelihood items first.

## High priority risks

1) Event ordering and correctness (valuation consistency)
- Risk: Kafka partitioning or consumer concurrency may cause out-of-order processing that yields incorrect portfolio valuations.
- Impact: Incorrect portfolio totals, financial reporting errors.
- Mitigations:
  - Partition by symbol (or portfolio id) to keep ordering guarantees where needed.
  - Use deterministic consumer logic that is commutative or idempotent where strict ordering is not possible.
  - Add watermarking/timestamps and reconciliation jobs to detect and repair inconsistencies.
  - Add automated integration tests verifying ordering assumptions.

2) Duplicate event processing and side-effects
- Risk: At-least-once delivery semantics cause duplicate handling and double-application of events.
- Impact: Double calculations, wrong balances
- Mitigations:
  - Implement event id deduplication using persisted processed-event IDs (or a changelog table) with TTL.
  - Design handlers to be idempotent (store last-processed offset/event-id per aggregate)
  - Use transactional outbox patterns if interacting with other storage systems.

3) Schema evolution and shared DTO breaking changes
- Risk: Changes to common-dto break consumers.
- Impact: Runtime errors, silent data loss.
- Mitigations:
  - Adopt explicit schema versioning for DTOs and topics (topic.v1, topic.v2) or use schema registry (Avro/Protobuf/JSON Schema)
  - Run contract tests in CI; gate merges on compatibility checks.
  - Keep backward-compatibility rules documented and enforced.

4) Operational complexity in local dev vs production
- Risk: Testcontainers/Docker Compose differences hide production issues (network, security, broker configs).
- Impact: Failures only in production environments
- Mitigations:
  - Maintain a staging environment that mirrors production (at least for Kafka and DBs)
  - Use realistic broker configs in CI smoke tests (partitions, replication)
  - Document differences and replicate them in nightly integration runs.


## Medium priority risks

5) Gateway single point of failure / performance bottleneck
- Risk: Gateway misconfiguration or overload affects entire platform.
- Mitigations:
  - Load-test gateway; autoscale in production, add health checks and circuit breakers.
  - Cache responses where safe; leverage Redis where appropriate.
  - Use robust routing rules and monitoring.

6) Insufficient monitoring/observability for event flows
- Risk: Hard to diagnose consumer lag, offset issues, or message processing failures.
- Mitigations:
  - Instrument metrics (consumer lag, processed-per-second, errors) and traces (OpenTelemetry).
  - Add alerts for lag thresholds and error rates.
  - Log structured events with correlation IDs.

7) Data integrity and transaction boundaries across services
- Risk: Distributed updates across services may be inconsistent (no distributed transactions).
- Mitigations:
  - Embrace eventual consistency, document invariants, and implement compensating transactions or reconciliation jobs.
  - Use outbox pattern to guarantee event publication alongside DB writes.


## Low priority risks

8) Security and access control
- Risk: Inadequate auth/authorization at gateway or services.
- Mitigations:
  - Centralize auth at gateway, validate scopes at services as necessary.
  - Use OAuth/OIDC with short-lived tokens; rotate secrets via a vault.
  - Harden CI secrets; scan for leaked keys.

9) Testing gaps (UI + backend integration)
- Risk: Missing or flakey tests leave regressions undetected.
- Mitigations:
  - Improve test reliability with Testcontainers and deterministic inputs; use Playwright for critical UI flows.
  - Add contract tests and schedule nightly integration runs.

10) Resource limits (DB connections, broker throughput)
- Risk: Burst traffic may exhaust DB or broker resources.
- Mitigations:
  - Set sensible connection pools and rate limits; configure backpressure on producers.
  - Monitor resource usage and define scaling plans.


## Recommended first actions (30/60/90 days)
- 0–30 days:
  - Add contract tests for critical topics and add CI gating.
  - Implement simple event-id dedupe in consumers.
  - Instrument consumer lag and errors.

- 30–60 days:
  - Add schema registry or versioning for DTOs.
  - Add reconciliation jobs for valuations and periodic consistency checks.
  - Harden gateway with rate limits and health checks.

- 60–90 days:
  - Run production-like staging with multi-node Kafka for failover testing.
  - Add automated E2E nightly runs against Docker Compose or staging.


## Notes and considerations
- Prioritize correctness for valuations and idempotency — financial correctness is critical.
- Keep shared contracts small and stable; prefer additive changes.
- Make reliability and observability part of the CI pipeline.


