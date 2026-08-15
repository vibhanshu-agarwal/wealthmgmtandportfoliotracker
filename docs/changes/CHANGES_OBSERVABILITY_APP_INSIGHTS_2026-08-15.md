# Changes Summary — Observability & Application Insights (OTLP Trace Export)

**Date:** 2026-08-15
**Spec:** `.kiro/specs/observability-app-insights/` (requirements.md, design.md, tasks.md, KICKOFF.md)
**Plan:** `.kiro/specs/observability-app-insights/tasks.md` (16 top-level tasks / 68 sub-tasks, 15 dependency waves)
**Branches:** `codex/observability-app-insights-spec` (PRs [#91](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/91), [#92](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/92)),
`feat/observability-app-insights` (PRs [#93](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/93), [#94](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/94), [#95](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/95))
**Preceding changelog:** `docs/changes/CHANGES_NEW_USER_SIGNUP_PROFILE_2026-08-12.md`
**Scope:** new `common-observability` Gradle module, all four services plus the `market-data-refresh-job`
Container App Job, `infrastructure/terraform/azure` (AzureRM + newly added AzAPI provider),
`docs/runbooks/OBSERVABILITY.md`, and a live production deployment.

---

## Summary

Turned on OTLP trace export from five workloads into workspace-based Application Insights, routed
through the Azure Container Apps **managed OpenTelemetry agent**, under a hard ₹1100/month ceiling
for `wealth-azure-prod-rg`. Spans are sanitized before export by a new `common-observability`
module, and ingestion is bounded by daily caps sized to hold **even if the shared 5 GB/month
Analytics allowance is exhausted elsewhere**.

This also completed **Task 11.2** of `.kiro/specs/springboot-41-springai-2-migration/` — the only
incomplete sub-task of that spec — by proving Kafka producer→consumer trace continuity to *both*
consumers at production fidelity.

The instrumentation itself already existed: all four services carried
`spring-boot-starter-opentelemetry` with W3C propagation and Kafka observation, and HTTP continuity
across the reactive gateway boundary was already proven. What did not exist was a destination, any
ingestion ceiling, a producer that exported, or any redaction of what got exported.

Work ran in five phases: spec (two PRs, an unusually long review cycle), implementation across
waves 0–5, a plan-assertion fix, deployment including one failed apply, and live verification.

---

## Phase 1 — Specification (PRs #91, #92)

Written to the repo's Kiro convention (`requirements.md` → `design.md` → `tasks.md`), mirroring
`.kiro/specs/new-user-signup-profile/`.

The review cycle was long and substantive: **7 rounds on requirements, 6 on design, 3 on tasks**.
Several conclusions were reversed by evidence found mid-review rather than surviving from the
initial draft:

- **Sink choice.** The GA Application Insights Java agent was rejected in favour of the (Public
  Preview) ACA managed agent, because Microsoft does not support the Micrometer Tracing API as
  custom Java telemetry — adopting the agent would have orphaned the existing Spring/Micrometer
  instrumentation and made Task 11.2's work decorative.
- **Cost model.** A standing **5 GB/month per-billing-account Analytics allowance** was discovered
  mid-review, which inverted the design: ingestion volume was never the binding constraint (26 MB/month
  measured), Container Registry was (~100% of project spend). Sampling was consequently set to
  `1.0`, reversing an earlier "aggressive sampling" direction.
- **Redaction mechanism.** An initial `SpanProcessor` design was unimplementable (`onEnd` receives a
  read-only span) and a follow-up `ObservationFilter`-only design was insufficient (the filter runs
  *after* `recordException` has already written the exception event and status description).
- **Ceiling.** Raised ₹1000 → ₹1100 specifically to give the allowance-independence bound real
  overshoot margin (₹114.79 rather than ₹14.79).

`KICKOFF.md` (PR #92) records the settled decisions, the three highest-risk areas, and the fact
that the cited INR meter rate and cost forecast are point-in-time inputs that must be re-fetched.

---

## Phase 2 — Implementation (PR #93)

### New `common-observability` Gradle module

- **`SanitizingSpanExporter`** — extends OTel's `DelegatingSpanData` and overrides exactly three
  accessors. Replaces every `ExceptionEventData` with a plain `EventData` (class name + redaction
  marker), because `ExceptionEventData` exposes the original `Throwable` via `getException()` —
  sanitizing only its attributes would leave message and stack trace fully reachable. Also
  sanitizes `exception.*` keys on any *other* event carrying them, unconditionally across all
  exception types.
- **`SanitizingBatchSpanProcessorAutoConfiguration`** — replaces Boot's default `BatchSpanProcessor`
  with one that **privately** wraps the gRPC OTLP exporter. The sanitizer is deliberately *not* a
  `SpanExporter` bean: Boot composes all such beans via `SpanExporters`, so registering it would
  have exported a raw copy alongside the sanitized one. Ordered
  `after = OtlpTracingAutoConfiguration, before = OpenTelemetryTracingAutoConfiguration`, gated on
  `@ConditionalOnEnabledTracingExport("otlp")` + `@ConditionalOnBean(OtlpGrpcSpanExporter.class)`,
  with full property parity to the bean it replaces and `InternalTelemetryVersion.LATEST`.
- **`RedactingObservationFilter`** + **`HttpRouteTemplatingObservationConvention`** — deny-set key
  removal, query-string stripping, and normalized route templates.

### Terraform (`infrastructure/terraform/azure`)

- Dedicated `wealth-prod-telemetry-la` Log Analytics workspace (30-day retention) and a
  workspace-based `wealth-prod-ai` Application Insights resource (`application_type = "java"`,
  90-day `App*` interactive retention, `local_authentication_enabled = true` per the accepted
  preview constraint).
- Daily ingestion caps of **0.023 GB/day** on *both* workspaces — the platform minimum. The
  existing `wealth-prod-la` was previously uncapped (`-1`).
- Resource-group **budget** (`₹1100`, Actual 70% / Forecasted 100%) — closing a gap open since it
  was first recommended on 2026-05-17.
- **New AzAPI provider** (`azapi ~> 2.0`) and `azapi_update_resource.aca_otel_agent` pinned to
  `Microsoft.App/managedEnvironments@2025-10-02-preview`. Required because AzureRM does not model
  the managed environment's `openTelemetryConfiguration` block.
- `scripts/assert_observability_plan.py` — mandatory plan assertions (both caps, budget scope and
  thresholds, no charged scheduled-query alert rules, no Auxiliary/Basic table plans).
- `scripts/allowance_independence_check.py` — the cost bound as an executable check taking the INR
  meter rate and forecast as **arguments**, so a stale hardcoded figure cannot silently pass.

### Per-workload configuration

Export toggle, gRPC transport, sampling `1.0`, `SERVICE_VERSION`, and `DEPLOYMENT_ENVIRONMENT_NAME`
on all four Container Apps *and* the Job. The OTLP endpoint and `OTEL_RESOURCE_ATTRIBUTES` are
deliberately **not** set — ACA injects both, and overriding the latter would replace ACA's value
rather than merge with it. The Job additionally sets `OTEL_SERVICE_NAME=market-data-refresh-job`,
because it runs the `market-data-service` image and would otherwise be indistinguishable from that
Container App in telemetry.

`application.yml` across all four services gained the parent-based sampler, resource-attribute
property map, exporter bounds (`gzip` compression), span limits, and the
`[service,traceId,spanId]` console log prefix used by the correlation KQL.

### Kafka trace continuity (completes Task 11.2)

The pre-existing tests did not merely under-assert — they hand-built
`new KafkaTemplate<>(producerFactory)` fixtures that were never given the application
`ObservationRegistry` (`KafkaTraceContextPropagationIT` autowired it and never read it;
`PriceUpdatedEventKafkaRoundTripIT` named its fixture `marketDataLikeProducer`). Those templates
were unobserved, so `spring.kafka.template.observation-enabled=true` configured a bean they never
touched, and removing the hand-stamped `traceparent` header would have produced no header at all.

Reworked to use the auto-configured `KafkaTemplate`, with focused wire tests proving the producer
injects a valid `traceparent` and that **both** `portfolio-service` and `insight-service` continue
the same trace ID under distinct span IDs.

### Job flush on exit

`forceFlush().join(bounded timeout)` inserted into `MarketDataRefreshJobRunner`'s existing `finally`
block, ahead of the unchanged `SpringApplication.exit` → `exitHandler` sequence. Flush failure is
logged and discarded — it must never alter the Job's exit code. `SIGTERM` flush for the four
long-running services was verified rather than assumed (`SigtermSpanFlushIT` holds an in-flight
WebFlux request and asserts a queued span is exported on context close).

---

## Phase 3 — Plan-assertion false positive (commit `d4bbd9e`, PR #93)

The mandatory observability plan assertion failed CI with
`azurerm_consumption_budget_resource_group.main is missing resource_group_id`, despite the
Terraform setting it correctly.

**Root cause:** PR-time `terraform plan` runs against a deliberately empty **local** backend (only
the apply path uses `-backend-config=backend-azure.hcl`). Every resource therefore shows
`will be created`, and `azurerm_resource_group.main.id` lands in `after_unknown`, not `after`. The
new assertion only inspected `after`.

Fixed to accept a concrete value **or** an `after_unknown` flag, failing only when absent from
both. The same generalization was later needed for `after_sensitive` (Phase 4).

---

## Phase 4 — Production apply failure and fix (PR #94)

The first `terraform apply` (task 15.4) failed on the last resource. Azure rejected
`azapi_update_resource.aca_otel_agent` with `LogAnalyticsConfiguration is invalid`.

**Root cause:** `azapi_update_resource` performs a GET-merge-PUT of the ACA environment. The GET
returns `appLogsConfiguration.logAnalyticsConfiguration.sharedKey` as `null` (write-only), and Azure
rejects the resulting PUT unless `customerId` + `sharedKey` are re-supplied.

**Fix:** the body now re-supplies `appLogsConfiguration` pointing at the **Platform** workspace
(`wealth-prod-la`) — the same destination AzureRM already owns — leaving the traces destination
unchanged. A new `check_azapi_otel_log_analytics` assertion prevents recurrence, handling both
`after_unknown` (empty-backend plan) and `after_sensitive` (the workspace key is a sensitive value).
Its test fixture docstring names the failure date, making it a permanent regression test.

A second apply was required because the Container Apps raced the managed environment while it was
still provisioning; the retry completed cleanly once `provisioningState` was `Succeeded`.

---

## Phase 5 — Deployment and live verification

Deployment was deliberately gated in sequence — path filters (`common-observability/**` added to
`deploy.yml`), image rebuild, per-workload SHA verification on all five workloads, and only then
the Terraform apply. This ordering exists so the export toggle cannot be enabled against images
that predate the sanitizer.

All five workloads confirmed on merge SHA `d7d4600` before apply.

### Post-apply state (verified against live Azure, not the plan)

| Check | Result |
|---|---|
| `openTelemetryConfiguration` | non-null; traces → `["appInsights"]` |
| OTel logs / metrics destinations | unset (traces-only scope holds) |
| `appLogsConfiguration.customerId` | `83a9c3a2-…` = Platform `wealth-prod-la`, not telemetry |
| Environment `provisioningState` | `Succeeded` |
| Budget | exists in Azure, ₹1100, Actual 70% / Forecasted 100% |
| Telemetry / Platform workspace retention | 30 days each, caps 0.023 GB/day |
| App Insights `App*` tables | 90 days, **Analytics** plan, no Auxiliary/Basic |

### Representative run (task 15.7) — first traces ever exported from this system

Real Job produce with both consumers awake:

| Measurement | Value |
|---|---|
| Kafka produce → both consumers, same `OperationId` | **159 / 159** |
| Ingested bytes | ~3.63 MB (single deliberate verification run, **not** steady state) |
| Exporter drops | **0** |
| Cold start | insight 52s, portfolio >90s then 335ms — pre-existing ACA scale-from-zero |

Task 15.10 re-ran the pinned query: **158 / 158** dual-consumer operations.

### Runbook false-PASS fix (PR #95)

The smoke-check query as originally written joined `AppDependencies` to `AppRequests` with no name
filter — so the wake HTTP and Redis child spans (which share an `OperationId` with
`market-prices process`) would satisfy it even if the Job never produced a Kafka trace. Pinned to
`Name == "market-prices send"` on the Job and `Name == "market-prices process"` on the consumers,
with PASS now requiring **both** consumers on Kafka-attributable rows. The trace↔log correlation
query was also widened to `union` `AppRequests` with `AppDependencies`, since the Producer_Job never
serves a request and would otherwise never correlate.

### Compression decision (D6)

`gzip` retained. Cold start is attributable to ACA scale-from-zero, and the warm path (108–335 ms)
showed no compression penalty.

---

## Cost outcome

Allowance-independence re-verified against live Cost Analysis immediately before implementation:

```
31 × (0.023 + 0.023) GB × ₹303.9479/GB = ₹433.43
                        + forecast      = ₹549.42
                                          --------
                                          ₹982.85   vs ₹1100  →  margin ₹117.15
```

Log Analytics and Azure Monitor both billed **₹0.00** at the time of implementation, absorbed by the
5 GB/month allowance. The feature's own marginal cost remains an **unverified projection** per
Requirement 11.4 until steady-state volume is measured — the 3.63 MB above is one deliberate burst,
not a daily rate.

---

## Tests Run

| Suite | Result |
|---|---|
| `:common-observability:test` (redaction sentinels, activation matrix, queue saturation, span limits, exporter isolation) | ✅ 80 tests pass |
| `.\gradlew :common-observability:check :api-gateway:check :portfolio-service:check :market-data-service:check :insight-service:check` | ✅ BUILD SUCCESSFUL (15m 52s) |
| Kafka wire ITs (producer + both consumers + malformed header) | ✅ pass |
| `SigtermSpanFlushIT` (api-gateway context-close flush) | ✅ pass |
| `test_allowance_independence_check.py` | ✅ 6/6 |
| `test_assert_observability_plan.py` | ✅ 21/21 (incl. ephemeral-backend and real-missing fixtures) |
| Terraform Azure plan assertions (CI, every PR) | ✅ pass |
| Live smoke check (15.7 / 15.10) | ✅ 159/159 and 158/158 dual-consumer joins |
| `terraform plan` against the **real** backend (checkpoint 16) | ✅ 0 add, 6 change, 0 destroy — no observability drift |

The six in-place changes at checkpoint 16 were **not** observability drift: five are `SERVICE_VERSION`
advancing on the four Container Apps plus the Job (apply always sets `TF_VAR_image_tag=github.sha`),
and one is an Azure-computed Static Web App field. Container images were held at `d7d4600` by
`ignore_changes` throughout.

---

## Known Gaps / Follow-ups

Logged as watch-items rather than described here — this changelog records what changed, not where
open work lives:

- **`SERVICE_VERSION` advances on infra-only applies** while the container image is held by
  `ignore_changes`, so exported `service.version` can disagree with the running image —
  `docs/todos/TODOS_2026-04-07.md`.
- **AzAPI/Container Apps apply ordering** — the managed-environment update can race app
  provisioning, requiring a second apply — `docs/todos/TODOS_2026-04-07.md`.
- **Static Web App `repository_url` perpetual diff** — Azure-computed value plans to `null` on every
  run — `docs/todos/TODOS_2026-04-07.md`.
- **Preview exit criteria** (native Azure Monitor OTLP reaching GA; Entra-authenticated ingestion
  replacing App Insights local auth) are two *independent* triggers recorded in
  `.kiro/specs/observability-app-insights/design.md` (D4).
