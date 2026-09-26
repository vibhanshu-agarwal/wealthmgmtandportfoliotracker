# Wealth Management & Portfolio Tracker — Detailed Architecture (v2)

**Source audit:** 2026-09-26 UTC at `main@8aa4035b`. The April AWS description is replaced
by the current demo architecture. This is not a live Azure revision read or a new test report.
Accepted build/evidence identity and `PASS_WITH_EXPECTED_DEFECTS` stay in the
[demo dashboard](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md).

## 1. Components, framework and deployment boundaries

The [overview](WealthManagementArchitectureOverview_v2.md) lists the seven Gradle modules.
Java 21 / Boot 4.1.0 are selected in [build.gradle](../../build.gradle); Azure images use
Microsoft OpenJDK 21 ([gateway Dockerfile](../../api-gateway/Dockerfile.azure)).
The static Next.js 16 / React 19 frontend uses TanStack Query, Radix UI and Recharts.
Static export means API origins are configured at frontend build time; there is no Next.js
server proxy or frontend-owned credential store.

[Azure Terraform](../../infrastructure/terraform/azure/main.tf) defines Static Web Apps,
ACR, a Container Apps environment, four service Container Apps, scheduled refresh and manual repair Jobs,
Azure OpenAI and telemetry/budget resources. The gateway ingress is external; downstream
ingresses are internal. Non-seed application target ports are 8080; internal service URLs
use ingress port 80, not bare-host port 8081/8082/8083.
Profiles are `prod,azure`, with `azure-ai` additionally selected for insight.
Scale-to-zero makes service wake-up a demo prerequisite, not proof of permanent availability.

AWS Terraform/Lambda image paths remain retained alternatives. Local Compose uses local
ports and stores; do not copy Azure ingress assumptions into Compose. Neither retained AWS
code nor LocalStack examples prove current rollback feasibility.

## 2. Data ownership

| Store | Writers/readers and meaning |
|---|---|
| PostgreSQL | Gateway authentication reads/writes user/credential data; portfolio owns portfolio/holding transactions, versions, projected market prices and history; Flyway migrations live with portfolio |
| MongoDB | Market service/refresh persist shared prices/references; the separate fenced repair runner operates on legacy `MM.NS` documents; user page loads read this data |
| Kafka | `market-prices` carries keyed `PriceUpdatedEvent` observations to independent consumers; portfolio uses `market-prices.DLT` for rejected/exhausted records |
| Redis | Gateway limiter and presence state; insight prices/observation window, tracking and AI caches; portfolio cache backend depends on profile |

Gateway and portfolio share PostgreSQL identity tables: this is not strict one-database-per-service
isolation. Holdings are a composition/cost-basis model, not a delivered transaction/trade ledger.
Domain entities stay service-owned; shared wire/catalog/observability types belong in their
respective common modules.

[portfolio application configuration](../../portfolio-service/src/main/resources/application.yml)
enables Flyway startup migrations and JPA schema validation. This is not a separate automatically
approved deploy-time migration phase. Operations must account for schema compatibility before
rollback or waking an old image.

## 3. Identity, routes and guarded writes

See the [gateway flow](../e2e-flows/api-gateway-service-e2e.md) and its source links.

- Gateway-owned signup/login replaces the retired frontend auth stacks. Signup transactionally
  creates the user, credential and empty portfolio. Passwords use bcrypt.
- HS256 JWTs have a one-hour lifetime and a session identifier. Browser logout clears local
  state but does not revoke a still-valid JWT; this remains an accepted demo defect.
- Spring Security WebFilters authenticate; routing GlobalFilters verify configured origin,
  strip spoofed user headers and inject the verified subject. Origin verification is conditional
  and is not a blanket guarantee that every direct service URL/controller is blocked.
- CORS is not authorization. Internal endpoints have separate shared-key/guard contracts.
  Public health paths do not require login, and liveness is not credential-store readiness.
- Named Redis rate limiters serve ordinary, AI and auth traffic. Limiter fail-open behavior
  does not make a failed Redis-dependent business read succeed.
- The `ro` claim blocks protected mutations **except** exact B2 PUT paths
  `/api/portfolio/holdings` and `/api/portfolio/demo-reset`. It is not "all saves forbidden".
  Manual reset additionally enforces its fixed showcase identity and internal authorization.
  Its response exposes an opaque `X-Gateway-Replica-Token`, not the internal API key; missing
  configured internal key returns 503.
- Presence is advisory, not a lock. Multi-user isolation acceptance applies to the tested
  portfolio/session paths, not automatically to every insight endpoint.

Direct `/api/internal/**` seed/reset routes are publicly forwarded by the gateway without JWT,
origin verification or a prod route rate limiter. The downstream shared internal key is the
application authorization gate (wrong/missing supplied key 403, blank configured key 503), not
private ingress or layered user authorization. CORS does not stop non-browser callers.

At audited baseline `8aa4035b`, public `POST /api/market/prices/{ticker}` sat outside that key filter. Normal `ro=false`
accounts, including public signups, passed the gateway's authentication/read-only checks; no
operator role/key check existed in its controller/service. It changed shared prices and submitted
events, not just caller-owned data. This subsequently fixed
[price-write authorization defect](../todos/backlog/public-market-price-write-authorization/README.md)
has source-wired Azure routing, but was not tested live. The frontend has no caller for it.

That paragraph describes the audited `main@8aa4035b` baseline. Reviewed commit `83607f5d` removes
the route without an alias; it has independent review and source/recorded-evidence acceptance,
and merged through #327 (`9c733f6d`). Scoped deploy 36259687567 and one owner-run Gate D
`REMOVED`, exit 0, with AAPL reads OK close that public-route defect on 2026-09-26 UTC. The service write API
and legitimate refresh/seed paths remain.
Removal does not undo historical writes; a Mongo anomaly audit cannot prove past non-use or
clean downstream history/caches. Optional historical audit Gate E remains open with separate
design/approval required. Closure uses saved deploy logs and the terminal-output transcription,
not a new Codex live or cloud read; no broader security acceptance is implied.

The separate `GET /api/insights/{userId}/analyze` advisor forwards the **path** user ID to
portfolio. Its controller/service do not compare that ID with the authenticated gateway subject.
This is a source-confirmed [IDOR](../todos/backlog/portfolio-advisor-cross-user-authorization/README.md),
not proven caller-owned access. Azure's checked-in insight environment omits the portfolio URL
and the default points to localhost:8081; absent another override it is expected to fail. This
is not live-verified or a security control. Compose/AWS source supplies the URL. No exploit was
tested here; the new OPEN finding has no owner non-blocking disposition or fix/deploy approval.

## 4. Holdings, valuation and analytics

[Portfolio flow](../e2e-flows/portfolio-service-e2e.md) describes the HTTP and transaction details.

`PUT /api/portfolio/holdings` replaces the complete desired holdings set with decimal-string
quantities and an expected portfolio version. Atomic validation/write rejects conflicts with
409, malformed/quantity/duplicate intent with 400 and unsupported/lifecycle intent with 422.
Identical tuples are no-ops; actual changes advance the
version. Client retry must not overwrite another session's changes.

Holdings views enrich portfolio rows using market batches. Summary/analytics use the PostgreSQL
event projection and FX provider. These sources can lag each other; missing/stale observations,
partial coverage and null changes must not be coerced into complete zero-valued results.
Freshness marks a timestamped price stale only when its age **exceeds 50 hours**.

Analytics select the latest observation per ticker/UTC day across a 50-day window. History
uses current quantities and available current FX rates, not historical holdings/cash flows or
historical FX returns. Synthetic fallback paths exist and must be distinguished from observed
market history. Analytics cache TTL is 30 seconds; immediate post-write cache eviction is not
established. Sharpe/Sortino analysis is future work.

The non-local FX implementation named `EcbFxRateProvider` actually reads `open.er-api.com`
USD rates, not an ECB feed. It computes cross rates and may return unavailable conversions;
it does not assume every currency equals USD. Local uses fixed rates. Azure sets FX cache
refresh/eviction to 06:00, without an explicit scheduler timezone. This is not proof a daily
refresh happened while the service was scaled to zero.

## 5. Market refresh and event consistency

[Market flow](../e2e-flows/market-data-service-e2e.md) identifies producer and profile paths.

Browser requests read stored Mongo prices. The Azure refresh Job invokes the Yahoo adapter for
active catalog entries at 08:00 UTC; the API's scheduled task is disabled on Azure.
Provider-symbol mapping is separate from canonical catalog identity. There are 159 ACTIVE
entries and one DEPRECATED entry at this source cut; that is not guaranteed feed coverage.
An unrecovered failure in any Yahoo batch discards that fetch's accumulated prices before writes;
the refresh catches it and can exit normally without an update. A Kafka send failure instead
fails the Job (exit 1, no ACA Job retry) after Mongo writes, which are not rolled back. New refresh
documents have null quote currency; manual HTTP writes do not wait for a Kafka acknowledgment.

The separate manual-only `market-data-repair-job` selects
[MarketDataRepairJobRunner](../../market-data-service/src/main/java/com/wealth/market/MarketDataRepairJobRunner.java)
and [MongoMmNsRepairService](../../market-data-service/src/main/java/com/wealth/market/repair/MongoMmNsRepairService.java)
for the fenced legacy `MM.NS` to `M&M.NS` Mongo repair. Terraform enables the repair property,
omits the refresh-runner property, sets one replica/completion, a 300-second timeout and no Job
retry. It is not a scheduled refresh, general price editor or newly approved repair execution.

The shared [event](../../common-dto/src/main/java/com/wealth/market/events/PriceUpdatedEvent.java)
carries observation metadata. Publications are keyed by ticker, which orders records within
a partition, not across independent producers/stores. Mongo persistence and Kafka publish are
separate actions. There is **no Spring Modulith Event Publication Registry/outbox** implemented
for this path, no cross-store transaction and no general exactly-once claim.

Portfolio projection has timestamp/tuple guards: older observations do not overwrite newer
ones, equal-time conflicting payloads are rejected, and history identities are deduplicated.
The DLT/retry handler is a portfolio-consumer control, not proof that every consumer has the
same failure semantics.

Insight retains timestamp-identified observations, but its multi-command cache update is not
an atomic monotonic-latest transaction. Redis loss/restart does not automatically trigger a
full Mongo replay; the earlier startup-hydration design is historical, not current behavior.
Refresh success does not imply every ticker updated or both consumers are caught up.

## 6. Insights and AI attribution

[Insight flow](../e2e-flows/insight-service-e2e.md) distinguishes bulk, per-ticker, chat and advisor.

Bulk market summaries use stored Redis facts, without per-ticker model calls. Trends mean change
over the stored observation window, not necessarily 24 hours. Per-ticker sentiment and chat may
invoke/cache a model. Catalog/explicit resolution precedes optional model-assisted resolution;
chat is not a delivered multi-turn portfolio-context/FA/TA assistant.

Azure uses Azure OpenAI adapters with managed identity as the default source configuration.
The Terraform deployment alias remains `gpt-4o-mini`, while its configured model is
`gpt-4.1-mini` version `2025-04-14`; an alias is not the model identity or live attestation.
Bedrock sentiment/advisor adapters are real retained provider adapters, not the old randomized
mock described in the previous risk document. Bedrock has no asset-resolution adapter:
`!azure-ai` selects the mock resolver returning UNKNOWN, while deterministic preflight still
works. Other profiles can use rule-based sentiment/advisor adapters.

`sentimentSource` identifies the sentiment adapter/output, possibly cached; it does not prove
a new model invocation, the asset resolver's provenance or that all natural-language text is
factually correct. Model failures can produce unavailable sentiment/fallback facts. A visible
label alone is weaker evidence than a preserved response and correlated server traces.

## 7. Operations, tests and future work

The shared observability module sanitizes/limits attributes and templates routes.
Application configs enable W3C tracing; Azure Terraform configures OTLP to the environment agent
and Application Insights. This is not proof of current export delivery, complete redaction or
lag alerting. Source-enabled telemetry and manual allowance audits are not automatic SLOs or
a hard spend ceiling.

Use [Current operations](../runbooks/CURRENT_OPERATIONS.md) and
[Observability](../runbooks/OBSERVABILITY.md) for approved packet boundaries, not old offset-reset,
warming-reactivation or repair recipes. [Test inventory](IntegrationTestCases.md) lists source
coverage and workflow triggers; skipped/manual/unrun suites are not passing evidence.

[Risk register](RiskMitigationPlan.md), [backlog](../todos/backlog/README.md) and
[roadmap v5](../../roadmap_enhancements_v5.md) govern residual/deferred work. This documentation
does not authorize operational access, implement features or change accepted defect dispositions.

