# Wealth Management & Portfolio Tracker — Architecture (v2)

## Executive summary (v2)
This repo hosts a modular wealth management platform: a Next.js frontend (React + TypeScript) and a Spring Boot microservices backend. Since inception, the project matured from a local proof-of-concept into a production-ready architecture emphasizing correctness, observability, and operational safety for financial workloads.

Key updates since v1
- Event reliability: Added event-id deduplication, consumer idempotency patterns, and an outbox pattern for reliable publication.
- Schema governance: Adopted schema versioning and a schema registry (JSON Schema/Avro/Protobuf depending on topic) to prevent breaking changes across services.
- Observability: Added OpenTelemetry traces, Prometheus metrics, and structured logs with correlation IDs for event tracing and alerting.
- Deployability: Added Helm manifests / Kubernetes readiness (infrastructure/), enabling staging and production deployments beyond Docker Compose.
- Testing & CI: Strengthened CI with contract tests, Testcontainers-backed integration tests, Playwright E2E, and Qodana quality checks.
- Operational tools: Added consumer lag monitoring, alerting, and reconciliation jobs for valuation correctness.

Why v2 matters
Financial correctness and eventual consistency are now first-class concerns: the platform favors idempotent processing, clear schema management, and observability so that valuations and derived insights can be relied on at scale.

Primary value
- Safe, auditable, and testable event-driven updates
- Clear service ownership and well-defined public API surface via the API Gateway
- Smooth path from local development (Docker Compose) to Kubernetes-based staging/production

If you want, next deliverables can include: rendered PlantUML PNG, a CI gating checklist, or a short runbook for incident response.
