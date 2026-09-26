# Architecture Risk and Mitigation Register

**Reconciled:** 2026-09-26 UTC against `main@8aa4035b`. This replaces stale April controls
and fixed 30/60/90-day promises with source-supported controls and unscheduled residual work.
It is not a fresh cloud/security audit or independent runtime proof. The later price-write closure
below reconciles separately recorded owner-authorized deploy/probe evidence.

The [demo dashboard](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md) retains
`PASS_WITH_EXPECTED_DEFECTS` and owner-accepted limitations. The
[backlog index](../todos/backlog/README.md) governs open/fixed/superseded task status;
this register mirrors those dispositions and creates no new required demo gate.

## 1. Controls actually present

- Gateway-owned bcrypt credentials, HS256 JWT signing/validation, spoofed user-header stripping
  and subject injection, distributed rate limits and guarded B2 composition/reset paths.
- Versioned atomic holdings replacement, decimal/catalog validation and conflict rejection.
- Portfolio timestamp-aware latest-price projection, conflicting tuple rejection, history
  deduplication and daily chart selection; portfolio retry/DLT handling.
- Catalog/provider-symbol mapping and stored-price reads separated from the scheduled Yahoo
  refresh Job. Provider misses retain unavailable/stale state rather than inventing a price.
- HTTP Pact, property/integration/architecture tests and assembled Compose E2E in their selected
  workflow scopes; source-linked inventory is in [IntegrationTestCases](IntegrationTestCases.md).
- W3C/OTLP instrumentation and shared observation/trace sanitization. Azure Terraform configures
  an environment agent, Application Insights and budget controls; logs alone are not the only
  tracing implementation, but live exporter/allowance behavior is not reverified by this audit.

Details and limitations are in [detailed architecture](WealthManagementArchitectureDocumentation_v2.md)
and the [service flows](../e2e-flows/).

## 2. Residual risks and bounded future treatment

| Risk | Present control / corrected state | Residual or later action |
|---|---|---|
| Shared event/schema evolution | Shared DTO/catalog definitions and producer/consumer fixtures; HTTP Pact is separate | No shipped schema registry/general event-ID ledger. Review additive compatibility and both consumers before changing contracts. |
| Reordered/duplicate observations | Portfolio newer-time/tuple/history guards exist; the old "not sequence-aware" claim was wrong | Insight's multi-command Redis write does not establish atomic monotonic latest. Evaluate a bounded structural fix separately. |
| Persist/publish divergence | Mongo save and Kafka publication are distinct; keyed producers and scoped retries exist | No transactional outbox across this boundary. Review recovery/reconciliation before asserting complete propagation or replaying data. |
| Authorization depth | Protected user routes require gateway JWT; direct internal routes use the separate shared-key-only boundary below | The path-ID advisor has a source-confirmed [IDOR](../todos/backlog/portfolio-advisor-cross-user-authorization/README.md), OPEN with owner disposition pending. Azure source omits its portfolio URL; live reachability is unverified and missing wiring is not a security control. No exploit/live probe or fix was performed. |
| Shared market-price mutation | At `8aa4035b`, ordinary logins could POST shared prices. #327 (`9c733f6d`) removed the route; scoped deploy 36259687567 and one owner-run Gate D `REMOVED`, exit 0, with AAPL reads OK confirm removal | **CLOSED [operator-authorization defect](../todos/backlog/public-market-price-write-authorization/README.md), within route-removal scope.** Saved deploy logs and terminal-output transcription, not a new Codex live/cloud read. Optional Gate E historical audit stays open and needs separate design/approval; removal does not undo past writes or prove downstream cleanliness. |
| Public internal routes | Downstream shared-key filters reject missing/wrong keys (403) or blank configured key (503) | `/api/internal/**` seed/reset routes are publicly forwarded without JWT, origin verification or prod route rate limiting. Internal ACA ingress does not hide them from the public gateway. Assess key exposure/rotation, least privilege and abuse controls separately. |
| User-header regression assurance | Source strips spoofed identity and injects JWT subject; #327 merges a direct protected market-route spoof assertion | Older named spoofing integration cases remain status-only. [Broader regression proof](../todos/backlog/gateway-user-header-spoofing-regression-proof/README.md) is OPEN for duplicate headers, permit-all paths and general mutation proof; no current bypass is asserted. |
| Refresh partial/failure outcomes | Provider retries and awaited scheduled Kafka sends exist | Any unrecovered Yahoo batch failure discards accumulated results; provider failure is caught and can exit 0 with no writes. Kafka/flush failure exits 1 after Mongo writes, with no ACA Job retry. Neither exit alone proves coverage/convergence or atomic recovery. |
| Session lifecycle | Gateway mints one-hour JWTs; browser logout clears local state | Already-issued tokens are not revoked. Keep the accepted Phase 4 defect visible; broader revocation design remains separate. |
| Credential-store availability | Auth failures can return 503; health may omit dependency probes | Health UP is not working login. Preserve fail-closed auth and monitor readiness distinctly; do not treat a wake as successful auth. |
| Data freshness and coverage | Daily Azure refresh Job; unavailable/stale/partial contracts; chart dedup and FX conversion | Provider loss, incorrect symbol mapping and consumer lag remain possible. [Supported-provider work](../todos/backlog/market-data-yahoo-unofficial-api/README.md) stays OPEN/mitigated, not fixed by symbol repair. A successful Job/health response is not complete asset coverage. |
| Idle Kafka consumption | Consumers explicitly scale to zero; bounded demo warm-up exists | [Kafka consumer scale-rule work](../todos/backlog/kafka-consumers-have-no-scale-rule/README.md) remains OPEN. No Kafka scaler/autonomous wake or catch-up guarantee is established. |
| Actuator assurance | Gateway hardening is deployed; health-only public access is configured | [Actuator follow-ups](../todos/backlog/unauthenticated-actuator-exposure/README.md) remain OPEN for accepted live-negative evidence, other services and guard disposition. This is not a new claim of public actuator exposure. |
| History/FX interpretation | Latest daily rows avoid duplicate spikes; stored-window trends are labelled | Current quantities/current FX and synthetic fallbacks are not cash-flow-adjusted return history. FX-pair semantics and accepted validation gaps remain open as recorded. |
| Analytics cache after edits | Profile-dependent 30-second cache | Immediate eviction is not established; preserve the known accepted stale-cache limitation instead of declaring it fixed. |
| AI accuracy/latency | Real Azure/Bedrock adapters, source labels, caches and unavailable/fallback paths | Bulk summary does not call AI; source may be cached. Label/number agreement does not establish invocation provenance, resolver quality or model-text truth. |
| Cold starts and capacity | Scale-to-zero plus reviewed demo warm-up/keep-alive; gateway rate limits | Warm evidence is not an SLA or sustained-load baseline. Limiter fail-open can expose costs; no arbitrary replica/schedule change follows. |
| Observability/privacy | Instrumentation, sanitizing shared module and approved manual allowance audit | Source wiring is not verified export, complete sanitization, automatic lag alerting or a hard bill cap. Preserve private raw evidence. |
| Environment parity/recovery | Compose/Testcontainers and retained provider-specific deployment roots | Single-broker local tests do not prove cloud failover or ready AWS rollback. Reattest migrations/identities/revisions before recovery. |

## 3. Corrections to obsolete risk claims

The retired Better Auth/frontend token-minting paths are not current risks. The gateway owns
signing and verification with one secret; this does not remove datasource availability or
post-logout token risks.

The Bedrock sentiment adapter is not the old randomized `MockBedrockAiInsightService`.
Bulk `market-summary` does not serially call a model for every ticker. Per-ticker/chat/advisor
model paths are distinct and can still fail or be slow; consult their actual profile/provider
configuration before changing timeouts or fallback behavior.

AWS warming modules, historical account quotas and April prices are not the active Azure demo
control model. No free-tier guarantee, provisioned-concurrency plan or old apply/offset-reset
recipe is renewed here. The historical analyses are indexed separately in [README](README.md).

## 4. Freeze and later reactivation

No dated maturity programme is committed while the project is parked. The owner deferred
per-user Sharpe/Sortino, richer FA/TA chat and additional charts in
[roadmap v5](../../roadmap_enhancements_v5.md). Keep them as future requirements, not delivered
controls or immediate fixes.

Before later work, read the dashboard/backlog, refresh source and approved live identity, choose
a bounded risk/feature scope, review its design independently and obtain relevant operational
authority. Repairs, offsets/replays, account cleanup, key rotation, deployment and resource
shutdown need current packets and approval. See [Current operations](../runbooks/CURRENT_OPERATIONS.md).


