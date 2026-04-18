# Risk & Mitigation Plan — Wealth Management & Portfolio Tracker

This revision reflects the current repository state as of 2026-04-15 and distinguishes controls already implemented from open risk gaps.

## Audit snapshot (current state)

- Implemented controls:
  - Kafka keying by ticker for market events and DLT handling with retries/non-retryable classification.
  - Idempotent projection upsert logic in portfolio read-model updates.
  - Gateway JWT authentication (profile-aware decoder) and local Redis-backed request rate limiting.
  - CI verification pipeline includes unit tests, integration tests, Pact consumer/provider checks, Docker Compose health checks, and Playwright E2E.
  - Actuator and service healthchecks are present across gateway/services and Docker Compose.
- Major gaps:
  - No event schema registry or explicit topic/DTO version strategy.
  - No explicit event-id dedup store/processed-event ledger at consumers.
  - Limited event-flow observability (no clear lag dashboards/alerts/distributed tracing baseline).
  - Production parity is partial (single-node Kafka in local/CI; no evidence of production-like failover staging).

## High priority risks

1) Schema evolution and shared DTO breaking changes
- Risk: `common-dto` event shape changes can break consumers at runtime.
- Impact: Message deserialization failures, silent drops, inconsistent downstream state.
- Current state:
  - Pact contract testing exists for HTTP provider/consumer boundaries.
  - No schema registry, compatibility gate, or topic versioning convention for Kafka events.
- Mitigation plan:
  - Introduce explicit event versioning strategy (topic versioning or versioned payload contracts).
  - Add compatibility checks for event contracts in CI (producer + consumer fixtures).
  - Define and enforce additive-only evolution rules for shared event DTOs.

2) Event correctness under reordering/late arrival (valuation consistency)
- Risk: Older price events may arrive after newer values and overwrite projections.
- Impact: Incorrect portfolio valuations and reporting drift.
- Current state:
  - Producer sends with ticker key (partition ordering per key).
  - Consumer projection writes are idempotent for duplicates, but not sequence-aware for stale events.
- Mitigation plan:
  - Add event metadata (`eventId`, `eventTime`, `sourceSequence`) and enforce monotonic update rules per ticker/aggregate.
  - Add reconciliation jobs comparing projections to source-of-truth snapshots.
  - Add integration/property tests for out-of-order and late-arrival scenarios.

3) Duplicate processing and side-effects across services
- Risk: At-least-once delivery can reprocess the same event multiple times.
- Impact: Duplicate writes/cached-state churn and potential downstream side effects.
- Current state:
  - Portfolio projection is idempotent at value level (`INSERT ... ON CONFLICT ... IS DISTINCT FROM`).
  - No dedicated processed-event store keyed by event-id.
- Mitigation plan:
  - Add event-id dedup persistence for consumers with bounded retention.
  - Ensure all consumers follow deterministic idempotent handlers (including insight cache updates).
  - Prefer transactional publish patterns where DB write and event publish must be atomic.

4) Observability gaps for asynchronous event flows
- Risk: Consumer lag, stuck partitions, and poison-message patterns may go undetected.
- Impact: Delayed data freshness, difficult incident triage, increased MTTR.
- Current state:
  - Actuator and logs exist; DLT path is implemented and tested.
  - No documented lag SLOs, alert thresholds, end-to-end trace/correlation standards, or dashboards.
- Mitigation plan:
  - Instrument Kafka lag/error/retry/DLT metrics and expose dashboards.
  - Define alerting thresholds (lag duration, DLT growth rate, consumer error burst).
  - Adopt correlation IDs/trace propagation standards across gateway and services.

## Medium priority risks

5) Operational parity gap between local/CI and production
- Risk: Single-node/local defaults can hide cluster/failover/security issues.
- Current state:
  - Docker Compose and Testcontainers provide solid local/CI confidence.
  - Kafka topology is still primarily single-broker in local workflows.
- Mitigation plan:
  - Stand up production-like staging (multi-broker Kafka, realistic topic/retention/security settings).
  - Run periodic failover and recovery drills.
  - Keep an explicit environment parity checklist.

6) Gateway bottleneck and resilience under load
- Risk: API gateway failure or saturation degrades the entire platform.
- Current state:
  - Central auth and rate limiting are implemented.
  - No explicit load/perf baseline, autoscaling policy evidence, or circuit-breaker strategy at the edge.
- Mitigation plan:
  - Add gateway load tests and define performance SLO/error budgets.
  - Configure autoscaling and backpressure policies in runtime platform.
  - Add resilient fallback/error classification for downstream dependency failures.

7) Cross-service data integrity and consistency boundaries
- Risk: Distributed updates across service-owned datastores diverge over time.
- Current state:
  - Event-driven propagation exists; local correctness protections are present.
  - No explicit invariants/reconciliation playbook documented for all cross-service aggregates.
- Mitigation plan:
  - Document invariants and ownership boundaries per aggregate.
  - Add scheduled consistency checks and compensating workflows.
  - Track reconciliation outcomes with measurable SLOs.

## Low priority risks

8) Security depth beyond gateway enforcement
- Risk: Reliance on gateway-only controls can leave internal service paths under-protected.
- Current state:
  - Gateway JWT validation is implemented; secrets are environment-driven.
  - Service-level authorization scope validation is not clearly codified for all endpoints.
- Mitigation plan:
  - Add service-level authorization checks for sensitive operations.
  - Enforce secret rotation and hardened secret sources across environments.
  - Add periodic authz regression/security tests.

9) Resource limits and capacity planning
- Risk: Throughput spikes may exhaust DB/Kafka/Redis capacity.
- Current state:
  - Container memory limits and local rate limits exist.
  - No documented capacity model for connection pools, broker throughput, and burst handling.
- Mitigation plan:
  - Define baseline load profile and capacity thresholds.
  - Tune pools, producer batching/compression, and retry/backoff for target throughput.
  - Add runbooks for scaling triggers and emergency throttling.

10) Test strategy blind spots
- Risk: Existing tests may miss key event-semantics regressions.
- Current state:
  - Strong test pyramid foundation exists (unit, integration, pact, frontend E2E).
  - Limited explicit coverage for event-ordering chaos/failure injection and long-running consistency checks.
- Mitigation plan:
  - Add event-chaos suites (reordering, duplication bursts, delayed retries).
  - Add nightly long-horizon integration runs with seeded production-like data.
  - Track flaky-test rates and enforce failure triage SLAs.

## Recommended actions (30/60/90 days)

- 0–30 days:
  - Define and adopt Kafka event versioning rules for `common-dto` events.
  - Add event metadata (`eventId`, `eventTime`) and consumer-side dedup store for high-value topics.
  - Instrument first-pass Kafka observability (lag, retry, DLT metrics + alert thresholds).
  - Add ordering/late-event integration tests for valuation-critical paths.

- 30–60 days:
  - Introduce compatibility gating in CI for event schemas/contracts.
  - Implement valuation reconciliation jobs and publish reconciliation drift metrics.
  - Execute gateway load tests and publish baseline SLOs.

- 60–90 days:
  - Stand up production-like staging with multi-broker Kafka and failover test plan.
  - Add scheduled chaos/failure drills for event pipelines and recovery runbooks.
  - Move to nightly full-stack reliability runs (compose/staging) with trend reporting.

## Notes

- Financial correctness (ordering + idempotency + reconciliation) remains the top architectural priority.
- Existing CI and DLT foundations are strong; the next maturity step is schema governance and observability.
- Treat this plan as a living document and update after each reliability/security milestone.

## Focus area deep-dive: frequent issue zones

### Authentication (gateway + BFF token exchange)

- Observed risks:
  - `mintToken` can sign with an empty/incorrect secret when environment variables are misaligned, leading to intermittent gateway `401` responses.
  - Secret source ambiguity (`AUTH_JWT_SECRET` vs `BETTER_AUTH_SECRET`) increases drift risk between frontend token minting and gateway validation.
  - `/api/auth/jwt` route has no defensive error mapping for token-mint/session backend failures, which can surface as generic `500` behavior.
- Evidence in code:
  - Gateway validates JWT using profile-based decoder and `auth.jwt.secret` (`api-gateway`).
  - Frontend token mint uses `AUTH_JWT_SECRET ?? BETTER_AUTH_SECRET` fallback (`frontend/src/lib/auth/mintToken.ts`).
  - Existing integration tests already target secret-alignment regressions.
- Mitigations:
  - Enforce a single canonical signing secret variable for all runtimes and fail fast on startup if missing/weak.
  - Add explicit guardrails in token minting (minimum key length and non-blank checks) and return deterministic API error payloads.
  - Add operational check: startup health assertion that mint/verify round-trip succeeds with active config.

### AI integration (insight-service advisor + sentiment paths)

- Observed risks:
  - The `bedrock` profile is currently a mock sentiment implementation (randomized), which can be mistaken for production-grade AI behavior.
  - AI calls are synchronous and per-ticker in market-summary enrichment; latency scales linearly with ticker count and can cause endpoint instability.
  - No explicit timeout/retry/circuit-breaker policy around model calls or portfolio-service fetch path.
  - ChatClient builder depends on optional model availability; profile/config mismatches can fail at runtime in non-obvious ways.
- Evidence in code:
  - `MockBedrockAiInsightService` returns randomized placeholder responses under `bedrock` profile.
  - `InsightController.getMarketSummary()` enriches each ticker serially with AI sentiment.
  - `OllamaAiInsightService` and `OllamaInsightAdvisor` wrap failures but do not enforce bounded latency policies.
- Mitigations:
  - Mark mock profiles as non-production and gate deployment on real provider readiness checks.
  - Add strict timeout budgets and fallback behavior (null sentiment or cached sentiment) for both sentiment and advisor calls.
  - Add bulk/parallel enrichment limits with concurrency caps plus partial-result response strategy.
  - Introduce reliability metrics: model latency percentiles, timeout rates, fallback rate, and provider error rate.


