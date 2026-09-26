# Core Architectural Guardrails & Project State (v2)

**Reconciled:** 2026-09-26 UTC against `main@8aa4035b`. These are source/design boundaries
for the portfolio demo, not fresh deployment proof or an execution approval. Repository
[agent instructions](../../AGENTS.md) and current owner decisions govern authority; this document
does not replace them.

## 1. Authoritative status and scope

- Use [ROADMAP](../../ROADMAP.md), [enhancements v5](../../roadmap_enhancements_v5.md) and the
  [backlog index](../todos/backlog/README.md), not the older AI-roadmap snapshot as current state.
- The [demo dashboard](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) retains accepted runtime
  evidence and `PASS_WITH_EXPECTED_DEFECTS`. Source completeness is not a new live pass.
- [Architecture index](README.md) separates current references from historical AWS/LocalStack
  records. Their old approvals, cost limits, cloud inventories and TODOs are not renewed here.
- Future Sharpe/Sortino, richer FA/TA chat and additional charts remain unscheduled. This audit
  adds no implementation commitment or requirement for another demo run.

## 2. Deployment and cost discipline

Azure Static Web Apps and Container Apps are the accepted demo target. AWS code is retained,
not a verified immediate rollback. Infrastructure is Terraform-managed in separate Azure/AWS
roots; legacy CDK is not the active deployment authority.

Do not impose the old AWS ten-slot quota or "zero-cost forever" claims on Azure. Budgets,
free-tier assumptions and source caps are not a billing ceiling. Revalidate costs/quotas under
approved access before changes. No always-on replicas, warming schedules, native-image switch,
resource deletion or cloud migration follows from this audit.

Application deploy goes through the reviewed `deploy.yml` dispatcher; Azure Terraform
`plan`, `remote-plan` and `apply` have different state/authority scopes. A merge does not apply
Azure infrastructure. Use [Current operations](../runbooks/CURRENT_OPERATIONS.md) for exact
inputs/gates. Never bypass them with a historical command or an unreviewed environment overwrite.

## 3. Module, adapter and contract boundaries

[settings.gradle](../../settings.gradle) lists four services and three shared modules.
Java 21 is the current toolchain/runtime basis. The gateway uses WebFlux, other domain HTTP
services MVC. Application DTO serialization uses Jackson 3; Jackson 2 remains isolated for
third-party runtime dependencies ([build.gradle](../../build.gradle)).

Keep domain entities/service transactions in their owner; share wire contracts, catalog and
sanitizing observation utilities through their respective common modules. Provider SDKs belong
at integration adapters. Spring Kafka is the implemented messaging integration; do not claim
Spring Cloud Stream, a schema registry, versioned topics or an outbox is shipped without source.

Pact is HTTP contract coverage, not automatic event-schema compatibility. Changes to shared
events need producer/consumer fixture and evolution review; this is not permission to change
topics or replay production data.

## 4. Correctness and security invariants

- Holdings are complete desired-set, version-checked transactions. Preserve decimal fidelity,
  canonical catalog identities, no-op/version behavior and conflict rejection.
- Treat missing prices, timestamps and FX as unavailable/partial, not silently complete zeroes.
  Stale means older than 50 hours, not exactly 50 hours.
- Mongo write plus Kafka publish is not one transaction. Preserve the distinction between
  accepted observation, completed publication and consumer catch-up.
- Portfolio monotonic projection/history guards do not establish insight Redis atomicity,
  universal event-ID dedup or exactly-once delivery.
- JWT subject injection is not proof of every downstream endpoint's ownership checks.
  The path-ID advisor has an OPEN, source-confirmed
  [IDOR](../todos/backlog/portfolio-advisor-cross-user-authorization/README.md); do not conflate it
  with accepted chat. Missing Azure URL wiring is not authorization; live reachability is unverified.
- Preserve B2's exact demo write exceptions; do not claim `ro` blocks all saves.
  Manual reset has additional identity/guard checks, and presence is advisory, not a lock.
  The reset response exposes an opaque replica token, not the shared internal key.
- The public market price POST lacks operator authorization for ordinary signup users;
  [its OPEN defect](../todos/backlog/public-market-price-write-authorization/README.md) is not
  mitigated by blocking only `ro=true`. Direct internal seed/reset routes are publicly routed,
  shared-key-gated and lack JWT/origin/rate-limit layers; do not call them private network APIs.
- User-ID injection has a unit assertion, but named spoofing cases only check non-401 status.
  Keep [direct sanitization regression proof](../todos/backlog/gateway-user-header-spoofing-regression-proof/README.md)
  OPEN; source stripping is not a tested exploit or complete regression guard.
- Logout does not revoke an existing one-hour JWT. Keep its accepted defect status visible.
- Profile-specific origin checks and internal ingress are distinct controls. Do not promise
  global direct-origin blocking from the gateway filter alone.

## 5. Validation and operational boundaries

Use unit/property/architecture, Testcontainers, HTTP contracts and assembled Compose E2E at
their actual scopes ([test inventory](IntegrationTestCases.md)). Live synthetics and the
separate frontend full-stack workflow are manual-only in current source. A skipped job, mock
response or uncollected scenario is not runtime evidence. LocalStack is not required for
normal application tests.

Health `UP` does not prove auth/database/consumer/model readiness. For an authorized demo,
use the reviewed warm-up/operator kit, wait for `GO` and keep it alive. No old AWS warming
schedule is thereby enabled. Never recover by arbitrary database repair, Kafka offset reset,
cache deletion or reseeding; obtain a bounded current packet and approval.

Do not publish credentials, JWTs, raw private authenticated artifacts or private kit paths.
Telemetry sanitization is a source control, not a guarantee every trace/artifact is safe.
Review published evidence independently; preserve sealed evidence and known limitations.
