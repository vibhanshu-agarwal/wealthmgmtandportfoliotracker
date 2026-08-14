# Implementation Plan: Observability & Application Insights

## Overview

This plan wires OTLP trace export from five workloads (`api-gateway`, `portfolio-service`,
`market-data-service`, `insight-service`, and the `market_data_refresh` Container App Job) through
the ACA managed OpenTelemetry agent into workspace-based Application Insights, under a hard
₹1100/month resource-group ceiling, and completes Task 11.2 of
`.kiro/specs/springboot-41-springai-2-migration/` (Kafka producer→consumer trace continuity).

Work is sequenced so the Terraform foundation (workspaces, App Insights resource, budget, caps,
and the AzAPI-provisioned managed agent) lands first, since the redaction module and the
per-workload configuration both depend on an App Insights connection string existing. The
`common-observability` module — the only genuinely novel piece of application code, spanning a
`SpanExporter` wrapper, a custom `BatchSpanProcessor` auto-configuration, and an `ObservationFilter`
— is built and verified in isolation before it is wired into the four services and the Job, because
its auto-configuration ordering and activation conditions are exactly the kind of thing that looks
correct and silently double-exports or fails to activate. Kafka fixture rework and the flush
sequencing for both the Job and the four long-running services are independent of the redaction
module and can proceed in parallel with it. Cost verification, the runbook, and the representative
pre-production run all depend on a fully deployed stack and run last — and deployment itself is
gated so that the images the Terraform export toggle turns on are guaranteed to already contain the
sanitizer, never the reverse.

Stack: **Java 21 / Spring Boot 4.1** for all five workloads and the new `common-observability`
Gradle module (JUnit 5, `opentelemetry-sdk-testing` for meter-based assertions); **Terraform**
(`azurerm ~> 4.0` plus a newly added `azapi ~> 2.0`) for infrastructure; **Python** for the cost
calculation, matching the existing `infrastructure/terraform/azure/scripts/` convention
(`assert_plan.py`, `test_acr_pull_property.py`); **KQL** for the runbook.

## Global Constraints

Negative requirements with no dedicated implementation task — each is either a passive constraint
(nothing to build, only something to not do) or already enforced by a task's own definition. Listed
here so they stay traceable rather than silently dropped; reviewed at the checkpoints (tasks 9, 14,
16), not built.

- **1.7** — no Azure SDK, Application Insights exporter dependency, or Java agent added to any
  module's `build.gradle`.
- **1.9 / 12.2** — no OTLP log export; console logs remain the sole log source.
- **2.9** — the connection string's only home is the existing encrypted remote Terraform backend,
  access restricted to the deployment identity, never committed. Already true of the existing
  backend (`backend-azure.hcl` is gitignored); this constraint is to keep it true, not to build it.
- **3.4** — no acceptance criterion or runbook text claims errors are always retained under head
  sampling.
- **3.6** — the Sampling_Review_Trigger response menu (task 13.7) must never include raising the
  Telemetry_Cap as an option.
- **4.10, 4.11** — the Budget_Alert and the daily caps are each individually insufficient
  substitutes for the Allowance_Independence check (task 12.1); neither is self-correcting.
- **4.12** — the caps must never be sized against the Ingestion_Allowance.
- **5.7** — this spec's cost claims are scoped to `wealth-azure-prod-rg`; never asserted as
  subscription-wide control.
- **6.6 / 11.6** — no always-on health probe, sidecar, or OpenTelemetry Collector, in any topology.
- **10.9** — no KEDA or Kafka scale rule introduced to make the Sink_Smoke_Check (task 13.6)
  deterministic; the Consumer_Wake_Step (10.3) is the sanctioned workaround, not a scaling fix.
- **10.10** — absence of telemetry is never treated as an alerting signal.
- **11.3** — the Allowance_Audit (task 13.5) is a manual, documented reconciliation; it must never
  become a polling service, scheduled job, or runtime input.
- **12.1** — no custom or JVM metrics in this increment.
- **12.3** — no archive or long-term-retention tier.
- **12.4** — no Container Registry configuration or image-supply architecture change, even though
  ACR is approximately all current project spend — that carries independent security,
  availability, and deployment consequences and is a separate decision from this spec.
- **12.5** — never degrade the Sampling_Ratio or telemetry quality to offset the (unrelated)
  Container Registry charge.
- **12.6** — no dashboard-refresh cost control; querying Analytics-plan tables is included.

## Tasks

- [ ] 1. Terraform: telemetry sink foundation (AzureRM)
  - [ ] 1.1 Add `Telemetry_Workspace`
    - `azurerm_log_analytics_workspace`, SKU `PerGB2018`, `retention_in_days = 30`,
      `daily_quota_gb = 0.023`; distinct resource from the existing `wealth-prod-la`
    - _Requirements: 2.1, 2.4, 4.1_

  - [ ] 1.2 Add `App_Insights_Resource`
    - `azurerm_application_insights`, `application_type = "java"`, `workspace_id` → 1.1,
      **`local_authentication_enabled = true`** (positive form; the inverse is deprecated in
      AzureRM 4.x) with an inline comment naming D3 and the Entra exit trigger
    - Connection string must not appear in `outputs.tf`, images, application config, or logs
    - _Requirements: 2.2, 2.7, 2.8_

  - [ ] 1.3 Verify `App_Table_Retention`
    - Confirm `App*` tables resolve to 90-day interactive retention at no additional charge; if
      they diverge from that, resolve it before export is enabled anywhere
    - _Requirements: 2.5, 2.6_

  - [ ] 1.4 Add `Platform_Cap`
    - `daily_quota_gb = 0.023` on the existing `wealth-prod-la` (currently `-1`, uncapped)
    - _Requirements: 4.2_

  - [ ] 1.5 Add `Budget_Alert`
    - `azurerm_consumption_budget_resource_group` scoped to `wealth-azure-prod-rg` (**not**
      subscription scope), `amount = 1100`, `time_grain = "Monthly"`; notifications `Actual` at
      70% and `Forecasted` at 100%, delivered via Cost Management's own free email contact list
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [ ] 1.6 Write Terraform validation checks
    - Assert `daily_quota_gb = 0.023` on both workspaces; assert the budget exists at
      resource-group scope with the correct amount and both notification types; assert no
      `azurerm_monitor_scheduled_query_rules_alert*` resource exists anywhere in the plan
      (Free_Controls_Constraint made executable); assert the App Insights resource introduces no
      Auxiliary-plan table override and its tables remain on the (default) Analytics plan
    - _Requirements: 4.1, 4.2, 4.15, 4.19, 4.20, 5.4_

- [ ] 2. Terraform: AzAPI-provisioned managed OpenTelemetry agent
  - [ ] 2.1 Add the AzAPI provider
    - `azapi ~> 2.0` in `versions.tf`; `provider "azapi" {}` in `providers.tf` authenticating via
      the existing OIDC/Workload Identity configuration
    - _Enables tasks: 2.2 (no dedicated requirement clause for provider registration itself)_

  - [ ] 2.2 Add `azapi_update_resource` for the managed agent
    - Type `Microsoft.App/managedEnvironments@2025-10-02-preview` (pinned literal, not a
      placeholder), `resource_id = azurerm_container_app_environment.main.id`; body with
      `appInsightsConfiguration.connectionString` and `openTelemetryConfiguration.tracesConfiguration.destinations = ["appInsights"]`
      as **sibling** properties (the connection string is not nested inside
      `openTelemetryConfiguration`); logs and metrics destinations left unset
    - Dependency on 1.2 is inferred by Terraform from the `connection_string` expression — no
      explicit `depends_on` required
    - Inline comment recording that AzureRM does not model this block today, so there is no
      drift-fight with 1.1/1.2, but a future AzureRM version adding it would require migrating
      this resource rather than running both
    - _Requirements: 2.3, 2.10_

- [ ] 3. Terraform: per-workload trace export configuration
  - [ ] 3.1 Add the five trace-export variables to all four Container App module invocations
    - `MANAGEMENT_TRACING_EXPORT_ENABLED=true`,
      `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT=grpc`,
      `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0`, `SERVICE_VERSION` (image tag),
      `DEPLOYMENT_ENVIRONMENT_NAME=prod`
    - OTLP endpoint and `OTEL_RESOURCE_ATTRIBUTES` deliberately **not** set — ACA injects both;
      `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` is likewise absent from this list, leaving it at
      its `false` default, since the managed agent's App Insights destination carries no metrics
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 1.8, 3.2, 3.3_

  - [ ] 3.2 Add the same five variables plus `OTEL_SERVICE_NAME` to the Producer_Job
    - `OTEL_SERVICE_NAME=market-data-refresh-job` on `azurerm_container_app_job.market_data_refresh`,
      required because the Job runs the `market-data-service` image and would otherwise be
      telemetry-indistinguishable from that Container App
    - _Requirements: 1.1, 1.2, 8.3, 8.4, 8.5_

- [ ] 4. Application config: sampler, resource attributes, exporter bounds, span limits, log pattern (`application.yml`, all four services — the Job shares `market-data-service`'s config)
  - [ ] 4.1 Pin the sampler
    - `management.opentelemetry.tracing.sampler: parent-based-trace-id-ratio` — already Boot's
      default; pinned explicitly because parent-based propagation is a correctness property
      downstream workloads depend on
    - _Requirements: 3.1_

  - [ ] 4.2 Add resource-attribute property map
    - `management.opentelemetry.resource-attributes: { service.version: ${SERVICE_VERSION:local}, deployment.environment.name: ${DEPLOYMENT_ENVIRONMENT_NAME:local} }`
    - No custom `Resource` bean — Boot merges this map with ACA's untouched
      `OTEL_RESOURCE_ATTRIBUTES`; setting that variable directly in Terraform would replace
      ACA's value instead
    - _Requirements: 8.1, 8.2_

  - [ ] 4.3 Add exporter bounds
    - `management.opentelemetry.tracing.export.{max-queue-size: 2048, max-batch-size: 512, schedule-delay: 5s, timeout: 30s}`,
      `…otlp.compression: gzip` (D6, provisional — see task 15.8), `…otlp.connect-timeout: 10s`
    - _Requirements: 6.2, 6.3_

  - [ ] 4.4 Add span limits
    - `management.opentelemetry.tracing.limits: { max-attribute-value-length: 512, max-attributes: 64, max-events: 16, max-links: 8 }`
      — verified property names/namespace; bounds blast radius, is not itself a redaction
      mechanism (that is task 5)
    - _Requirements: 7.5_

  - [ ] 4.5 Extend the console log pattern with the trace/log correlation prefix
    - `[${OTEL_SERVICE_NAME:${spring.application.name:}},%X{traceId:-},%X{spanId:-}]` — Spring
      placeholder syntax for the service fallback (`${name:default}`, not `:-`), Logback's own
      `%X{key:-}` syntax for the MDC fields; bracketed triple order is a breaking-change contract
      with the runbook KQL (task 13.3) if altered
    - _Requirements: 8.6, 8.8_

  - [ ] 4.6 Write the span-limit test
    - Design-mandated (`design.md`, Testing Strategy): a deliberately oversized span — an
      attribute value longer than 512 characters and more than 64 attributes — is asserted to be
      truncated/dropped at export according to the task-4.4 limits, proving both bounds actually
      take effect rather than merely being configured. Lives alongside the other
      `common-observability` verification tests (task group 6/11), since it needs the same
      OTLP-exporting test harness
    - _Requirements: 7.5_

- [ ] 5. New `common-observability` Gradle module: attribute filtering
  - [ ] 5.1 Scaffold the module
    - New Gradle module, added to `settings.gradle`, depended on by `api-gateway`,
      `portfolio-service`, `market-data-service`, `insight-service`. Not `common-dto`: that
      module is deliberately dependency-free with no Spring machinery, and this module needs both
    - _Requirements: 7.4_

  - [ ] 5.2 Implement the `ObservationFilter`
    - Registered on `ObservationRegistry`; rewrites `KeyValues` before stop; removes keys in a
      configured deny-set (URL-, query-, token-, identifier-bearing) and strips query strings
      from retained URL-shaped values
    - _Requirements: 7.1, 7.2, 7.6_

  - [ ] 5.3 Implement a custom HTTP `ObservationConvention`
    - Route values recorded as normalized templates, never concrete paths carrying identifiers
    - _Requirements: 7.7_

  - [ ] 5.4 Write filter/convention unit tests
    - Deny-set keys removed; query strings stripped from retained URL values; route templating
      confirmed distinct from the concrete request path
    - _Requirements: 7.1, 7.2, 7.6, 7.7_

- [ ] 6. `common-observability`: sanitizing exporter and processor auto-configuration
  - [ ] 6.1 Implement the sanitizing `SpanExporter` delegate
    - Extend OTel's `DelegatingSpanData`; override `getAttributes()` (deny-set backstop),
      `getEvents()` (replace every `ExceptionEventData` with plain `EventData` carrying class
      name + redaction marker; also sanitize `exception.*` keys on any *other* event carrying
      them, unconditionally across all exception types), `getStatus()` (redact description on
      the same basis); delegate `export`/`flush`/`shutdown` unchanged
    - _Requirements: 7.1, 7.3, 7.4_

  - [ ] 6.2 Implement the custom `BatchSpanProcessor` auto-configuration
    - `@AutoConfiguration(after = OtlpTracingAutoConfiguration.class, before = OpenTelemetryTracingAutoConfiguration.class)`
    - `@ConditionalOnEnabledTracingExport("otlp")` (mirrors Boot's own condition — enabled by
      default when neither the exporter-specific nor global property is set) plus
      `@ConditionalOnBean(OtlpGrpcSpanExporter.class)` (answers the separate question of whether
      an exporter actually exists to wrap)
    - Property parity with the Boot-default processor it replaces: `includeUnsampled`, exporter
      timeout, max batch size, max queue size, schedule delay, optional `MeterProvider` — all
      read from the same properties Boot's own bean reads
    - `setInternalTelemetryVersion(LATEST)` — Boot leaves this at `LEGACY`, which the drop-count
      mechanism (task 11.2) depends on being overridden
    - The sanitizer from 6.1 is constructed **privately inside this configuration** and wrapped
      by the processor — never registered as its own `SpanExporter` bean, which would let Boot's
      `SpanExporters` composite collect it alongside the raw OTLP exporter and export both a raw
      and a sanitized copy
    - _Requirements: 1.6, 6.1_

  - [ ] 6.3 Register the auto-configuration
    - Add to `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
      in `common-observability`
    - _Requirements: 6.1_

  - [ ] 6.4 Add `opentelemetry-sdk-testing` to `testImplementation`
    - New test dependency, not currently resolved in this build; a prerequisite for tasks 6.5 and
      11.2, not itself the subject of a numbered acceptance criterion
    - _Enables tasks: 6.5, 11.2_

  - [ ] 6.5 Write the application-context activation test suite
    - Five cases distinguishing property enablement from exporter existence: (1) no enablement
      properties set + endpoint/gRPC configured → **active** (Boot's condition matches by
      default — verified via `OnEnabledTracingExportCondition`'s own `"tracing is enabled by
      default"` message); (2) `otlp`-specific `true`, global unset/`false` → active; (3)
      `otlp`-specific `false`, global `true` → inactive, context starts; (4) enablement condition
      matches but no `OtlpGrpcSpanExporter` bean/endpoint → inactive, context starts; (5) this
      repository's actual `application.yml` defaults with no environment override → inactive,
      because the global property explicitly resolves to `false`
    - Every active case additionally asserts exactly one effective `BatchSpanProcessor` and that
      the sanitizer is not independently collected by `SpanExporters`; a bound property override
      (reduced `max-queue-size`) is asserted to reach the custom builder
    - This is the specification for the auto-configuration's activation behaviour, not a
      nice-to-have regression test — see Notes.
    - _Requirements: 1.6, Property 7 (kill switch — cases 3/5 prove disabled means no export attempt, not merely a failed one)_

- [ ] 7. Kafka trace continuity — completes Task 11.2
  - [ ] 7.1 Rework `KafkaTraceContextPropagationIT`'s producer fixture
    - Use the auto-configured `KafkaTemplate` (not `new KafkaTemplate<>(producerFactory)`);
      the previously-autowired-but-unused `ObservationRegistry` becomes load-bearing
    - _Requirements: 9.1, 9.9_

  - [ ] 7.2 Rework `PriceUpdatedEventKafkaRoundTripIT`'s `marketDataLikeProducer` fixture
    - Same fix — either the auto-configured bean, or explicit
      `setObservationEnabled(true)`/`setObservationRegistry(...)` if the auto-configured bean is
      impractical there
    - _Requirements: 9.1, 9.9_

  - [ ] 7.3 Keep sampling `1.0` and export `false` for the fixture rework and the tests in 7.4–7.7
    - Decided before writing the tests below, so implementers do not reach for network export to
      prove continuity — proves it without network export instead
    - _Requirements: 9.10_

  - [ ] 7.4 Write the Producer_Wire_Test (`market-data-service`)
    - Assert a valid W3C `traceparent` is present on the produced record with no test-written
      header on the happy path; kept as its own focused test rather than folded into a
      cross-module harness
    - **Validates Property 2 (producer half)**
    - _Requirements: 9.1, 9.2, 9.11_

  - [ ] 7.5 Write Consumer_Wire_Test for `portfolio-service`
    - Assert consumer trace ID equals producer trace ID; assert span IDs **differ**
    - **Validates Property 2** (continuity + no new root span, both part of Property 2's own
      definition — Property 3 is the separate malformed/absent-header path, tested in 7.7, not
      this happy-path test)
    - _Requirements: 9.3, 9.5, 9.6, 9.7_

  - [ ] 7.6 Write Consumer_Wire_Test for `insight-service`
    - Same assertions as 7.5 — Task 11.2 names both consumers, and both already carry probes
      (`KafkaTracePropagationProbe`, `InsightKafkaTracePropagationProbe`)
    - **Validates Property 2**
    - _Requirements: 9.4, 9.5, 9.6, 9.7_

  - [ ] 7.7 Write the malformed/absent-`traceparent` test
    - A message with a malformed or missing header starts a new valid trace and is still
      processed, not failed
    - **Validates Property 3**
    - _Requirements: 9.8_

- [ ] 8. Span flush on shutdown — Producer_Job and the four long-running services
  - [ ] 8.1 Insert flush into the Job's existing lifecycle (does not replace it)
    - `MarketDataRefreshJobRunner.run()`'s existing `finally` block —
      `refresh() → forceFlush().join(bounded timeout) → SpringApplication.exit(context, …) → exitHandler.accept(exitCode)`
      — the flush step is new; `SpringApplication.exit` and the overridable
      `exitHandler = System::exit` seam (which the existing forked-process tests depend on) are
      unchanged
    - `SdkTracerProvider` resolved as an *optional* dependency defensively — Boot 4.1 creates it
      independently of the OTLP exporter, so it is normally present even with export disabled,
      and `forceFlush()` simply has nothing to flush in that case
    - Flush failure or timeout is logged and discarded; it must never alter `exitCode` or throw
    - _Requirements: 6.5_

  - [ ] 8.2 Extend the Job's forked-process test coverage
    - Assert a flush failure/timeout does not alter the Job's exit code
    - _Requirements: 6.5_

  - [ ] 8.3 Verify (and, if needed, add) `SIGTERM` flush for the four Container Apps
    - Distinct from 8.1: a request-serving service's shutdown path is Spring's own
      `ConfigurableApplicationContext.registerShutdownHook()` reacting to ACA's `SIGTERM`, not a
      Job runner's `finally` block, so it cannot be assumed to behave the same way without
      checking. First **verify**: does the `SdkTracerProvider` bean's destroy callback already
      perform a flush when the context closes on `SIGTERM`, and does it complete within ACA's
      termination grace period given the `schedule-delay`/`timeout` bounds from task 4.3? If not,
      add an explicit `@PreDestroy`/`SmartLifecycle` hook in `common-observability` calling
      `forceFlush()` with a bounded timeout during graceful shutdown, mirroring 8.1's approach but
      triggered by context close rather than by a Job's own exit sequence
    - _Requirements: 6.4_

  - [ ] 8.4 Write the `SIGTERM`-flush test for the four services
    - Close the application context (simulating `SIGTERM`) with pending unflushed spans and
      assert they are exported before the context finishes closing
    - _Requirements: 6.4_

- [ ] 9. Checkpoint — application-side wiring complete
  - Run `common-observability`'s unit and context tests (5.4, 6.5) and the Kafka wire tests
    (7.4–7.7) locally against a local OTLP receiver or mocked endpoint. Ask the user if questions
    arise.

- [ ] 10. Verification: redaction sentinel tests (Requirement 7, Property 5)
  - [ ] 10.1 Write sentinel tests across all five surfaces
    - HTTP URL/query, exception message, exception stack trace, Kafka headers, custom
      attributes — each injects a recognisable sentinel and asserts its absence from the
      sanitized `SpanData` output of the task-6.1 exporter
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.6_

  - [ ] 10.2 Add the fourth invariant
    - For every event on the sanitized output, either it is not an `ExceptionEventData` instance
      at all, or (defense-in-depth) `getException()` is asserted unreachable — closes the gap
      where attributes are rewritten but the original event type, and therefore the raw
      `Throwable`, remains
    - _Requirements: 7.3, 7.4_

  - [ ] 10.3 Assert across all three other locations, not attributes alone
    - Span attributes, exception event attributes, **and** the span status description — an
      attribute-only assertion would pass while the raw exception message sat in the status
      description
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

- [ ] 11. Verification: exporter isolation and queue-saturation tests (Requirement 6, Property 1)
  - [ ] 11.1 Write the unreachable-exporter test
    - Export enabled, OTLP endpoint pointed at a dead local gRPC port, `connect-timeout`/`timeout`
      shortened for the test; asserts context starts and a request completes normally
    - **Validates Property 1 (startup/request isolation half)**
    - _Requirements: 6.1_

  - [ ] 11.2 Write the queue-saturation test
    - **Module and class:** `common-observability/src/test/java/com/wealth/observability/QueueSaturationTest.java`
    - Test-only tiny bounds (`max-queue-size = 8`, `max-batch-size = 4`); create and end 2,000
      spans while export is stalled against the dead endpoint from 11.1
    - **Deterministic latency bound:** the full loop of creating and ending all 2,000 spans
      completes in **under 2 seconds** wall-clock. `BatchSpanProcessor.onEnd()` is non-blocking
      by contract (enqueue-or-drop, no synchronous network I/O), so 2,000 calls should take low
      milliseconds; 2 seconds is generous headroom for CI variance while still failing hard if a
      regression made `onEnd()` block on the stalled exporter, which would instead take
      open-ended time
    - Registers a test `MeterProvider` (via `opentelemetry-sdk-testing`, task 6.4) reading
      `otel.sdk.processor.span.processed{error.type="queue_full"}` — the `LATEST` telemetry
      family the task-6.2 processor emits
    - **Validates Property 1 (drop-not-block half)**
    - _Requirements: 6.2, 6.3_

- [ ] 12. Verification: cost calculation check (Requirement 4, Property 6)
  - [ ] 12.1 Write the Allowance_Independence check as a standalone script
    - **File:** `infrastructure/terraform/azure/scripts/allowance_independence_check.py`,
      matching the existing convention in that directory (`assert_plan.py`)
    - **Interface:** CLI arguments `--meter-rate` (₹/GB), `--forecast` (₹), `--recurring-charges`
      (₹, default `0`), `--budget` (₹, default `1100`); computes
      `31 × (0.023 + 0.023) × meter_rate + forecast + recurring_charges`, compares against
      `budget` with the Ingestion_Allowance assumed zero, prints PASS/FAIL and the margin, exits
      `0`/`1` accordingly — meter rate and forecast are **always arguments, never hardcoded
      constants**, so a stale figure cannot silently pass
    - **Test file:** `infrastructure/terraform/azure/scripts/test_allowance_independence_check.py`
      (matching `test_acr_pull_property.py`'s naming), asserting a known-good case (current
      figures: `--meter-rate 303.9479 --forecast 551.78` → PASS, margin ≈ ₹114.79) and a
      known-bad case (e.g. `--budget 1000` against the same inputs → FAIL)
    - This script is both the CI-verifiable proof of Property 6 and the tool the Allowance_Audit
      (task 13.5) invokes operationally with live-fetched inputs
    - **Validates Property 6**
    - _Requirements: 4.3, 4.4, 4.5, 4.6, 4.7, 4.9_

- [ ] 13. Observability Runbook (`docs/runbooks/`)
  - [ ] 13.1 Daily volume by table, both workspaces
    - `Usage` is workspace-scoped, not cross-workspace like the `workspace()`-based queries in
      13.3/13.6 — a single execution covers only whichever workspace it runs against. The runbook
      must therefore instruct running this query **twice, explicitly labeled**: once against the
      Platform_Workspace (`wealth-prod-la`), once against the Telemetry_Workspace, so the two
      results are never conflated or mistaken for a single combined total:
      ```kql
      Usage
      | where TimeGenerated > ago(31d)
      | where IsBillable == true
      | summarize GB = round(sum(Quantity) / 1024.0, 4) by DataType, bin(TimeGenerated, 1d)
      | order by TimeGenerated desc, GB desc
      ```
    - _Requirements: 11.1_

  - [ ] 13.2 Cap-proximity and overshoot detection, plus the documented framing of the caps
    - ```kql
      Usage
      | where TimeGenerated > ago(2d)
      | where IsBillable == true
      | summarize DailyGB = round(sum(Quantity) / 1024.0, 4) by bin(TimeGenerated, 1d)
      | extend CapGB = 0.023, PctOfCap = round(DailyGB / 0.023 * 100, 1)
      | order by TimeGenerated desc
      ```
    - Accompanying runbook text must state explicitly: the caps are a Cost_Circuit_Breaker, not a
      guaranteed monetary ceiling (documented overshoot exists); they stop ingestion only until
      the daily reset, not permanently; and this query's detection latency is bounded by the
      Audit_Cadence, an accepted trade rather than an oversight
    - _Requirements: 4.13, 4.16, 4.17, 11.2_

  - [ ] 13.3 Cross-workspace trace↔log correlation query
    - Cross-workspace join via the `workspace()` KQL function, since the log line and the trace
      record live in different Log Analytics workspaces. `AppRequests` alone is insufficient: the
      Producer_Job never serves a request, so its spans surface only in `AppDependencies`, and a
      query touching `AppRequests` only would silently fail to correlate any Job log line —
      exactly the case Property 8 must cover, not an edge case to defer. Union both tables,
      normalized to a common shape, before joining:
      ```kql
      let traces = workspace("<telemetry-workspace-resource-id>").AppRequests
      | project OperationId, TimeGenerated, Name
      | union (
          workspace("<telemetry-workspace-resource-id>").AppDependencies
          | project OperationId, TimeGenerated, Name
        );
      ContainerAppConsoleLogs_CL
      | extend traceId = extract(@"\[\S+,([0-9a-f]{32}),[0-9a-f]{16}\]", 1, Log_s)
      | where isnotempty(traceId)
      | join kind=inner (traces) on $left.traceId == $right.OperationId
      ```
      the extraction regex assumes the task-4.5 log prefix `[service,traceId,spanId]`; refine
      against real log lines once traces are flowing (task 15.7), since exact whitespace/format
      cannot be fully verified before then
    - Verified against real data — including at least one Producer_Job (dependency-only) trace,
      not only a request-bearing one — once traces are flowing (task 15)
    - **Validates Property 8**
    - _Requirements: 8.7, 8.8_

  - [ ] 13.4 Production drop-count extraction query
    - `ContainerAppConsoleLogs_CL | where TimeGenerated between (<run_start> .. <drain_end>) | where Log_s contains_cs "BatchSpanProcessor dropped" | extend DroppedCount = toint(extract(@"dropped (\d+) span", 1, Log_s)) | summarize TotalDropped = sum(DroppedCount)`
    - `contains_cs`, not `has` — `has` is a term-boundary operator and would not reliably match a
      multi-word phrase; a silent non-match reads identically to zero drops
    - Verify bidirectionally before relying on it: once against an interval known to contain a
      warning (confirm the count matches), once against an interval known to contain none
      (confirm zero is for the right reason)
    - _Requirements: 3.7, 6.3_

  - [ ] 13.5 Allowance_Audit procedure, including the kill-switch escalation
    - At most 31 days between completed runs (`Audit_Cadence`); each run **refetches** current
      INR meters and forecast — never reuses recorded figures — and invokes the task-12.1 script
      with those live values
    - IF the script reports FAIL, THEN `Trace_Export_Toggle` is set/left `false` until it passes
      again
    - IF the Ingestion_Allowance is found exhausted, **or** a cap was reached on more than one day
      since the previous audit, THEN the operator evaluates the Export_Kill_Switch as the
      documented first response — this is the escalation repeated cap-cycling requires, distinct
      from an ordinary failed check
    - This procedure is a manual, documented reconciliation and must never be implemented as a
      polling service, scheduled job, or runtime input (Global Constraint 11.3)
    - _Requirements: 4.6, 4.7, 4.8, 4.9, 4.14, 4.18, 11.3_

  - [ ] 13.6 Sink_Smoke_Check procedure
    - Trigger the Producer_Job; **wake step** — HTTP request to `portfolio-service` and
      `insight-service` (neither has a Kafka scale rule, so nothing else wakes them — D7); query:
      ```kql
      AppDependencies
      | where TimeGenerated > ago(15m)
      | project OperationId, ProducerTime = TimeGenerated
      | join kind=inner (
          AppRequests
          | where TimeGenerated > ago(15m)
          | project OperationId, ConsumerService = AppRoleName, ConsumerTime = TimeGenerated
        ) on OperationId
      ```
      (dependency/request name filters to be refined against real span names once traces are
      flowing — task 15.7); bounded wait with explicit failure on timeout, never a silent pass;
      run after observability deployment and after any agent/API-version change; never scheduled
      or continuous
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8_

  - [ ] 13.7 Document the Sampling_Review_Trigger and its response menu
    - Thresholds: a rolling seven-day mean above 50% of the Telemetry_Cap, or any single
      non-deliberate day above 80% of it
    - Response menu on trigger: root-cause remediation of the volume source, tightening the
      Cardinality_Bound or span/attribute filtering, lowering the Sampling_Ratio, or the
      Export_Kill_Switch — lowering the ratio is **not** mandatory, and raising the Telemetry_Cap
      is **never** an option (Global Constraint 3.6)
    - _Requirements: 3.5, 3.6_

- [ ] 14. Checkpoint — pre-deployment verification suite green
  - Run all of tasks 4.6 and 10–12 plus the module unit/context tests from 5–8. Ensure everything
    passes before any Terraform apply. Ask the user if questions arise.

- [ ] 15. Deployment and representative run
  - [ ] 15.1 Add `common-observability/**` to the deploy workflow's path filters
    - `.github/workflows/deploy.yml`'s `paths:` list (currently `api-gateway/**`,
      `portfolio-service/**`, `market-data-service/**`, `insight-service/**`, `common-dto/**`,
      `frontend/**`, and the workflow files themselves) does **not** include the new module —
      without this, a `common-observability`-only change would not trigger an image rebuild, and
      task 15.4's Terraform apply would enable export on images that predate the sanitizer,
      exporting unredacted telemetry
    - Add `"common-observability/**"` to the list
    - _Enables tasks: 15.2, 15.3, 15.4_

  - [ ] 15.2 Build and deploy images containing `common-observability`
    - Trigger `deploy.yml` (merge to `main` touching the four services, or manual
      `workflow_dispatch`) so all four Container Apps and the Producer_Job's image are rebuilt
      with the module from tasks 5–8 compiled in
    - _Enables tasks: 15.3, 15.4_

  - [ ] 15.3 Confirm the expected image SHA is live on all five workloads
    - `az containerapp show --name <app> --resource-group wealth-azure-prod-rg --query "properties.template.containers[0].image"`
      for each of the four apps, and the equivalent `az containerapp job show` for
      `market-data-refresh-job` — the Azure resource's actual `name`, **not** the Terraform
      resource label `azurerm_container_app_job.market_data_refresh`, which is a Terraform-local
      identifier and not the name the `az` CLI needs; each must report the image tag/digest built
      in 15.2, not a pre-existing one
    - **Do not proceed to 15.4 until all five confirm.** This is the gate that prevents the export
      toggle from being enabled against old, unsanitized images
    - _Enables tasks: 15.4_

  - [ ] 15.4 Apply the Terraform changes
    - Manual `workflow_dispatch` with `action=apply` on the Azure Terraform workflow — a merge
      alone does not make any of tasks 1–3 live, the exact gap behind the 2026-08-12 incident and
      the reason the budget alert recommended on 2026-05-17 was still missing in August
    - _Requirements: 2.7, 5.6_

  - [ ] 15.5 Verify the Budget_Alert exists in Azure
    - Not merely in Terraform state
    - _Requirements: 5.6_

  - [ ] 15.6 Verify retention in Azure
    - `App*` interactive retention is 90 days, Telemetry_Workspace is 30 days, neither incurs a
      retention charge
    - _Requirements: 2.5, 2.6_

  - [ ] 15.7 Run the representative HTTP → Kafka run
    - At `Sampling_Ratio = 1.0`; record span count, **ingested bytes**, exporter drop count (via
      13.4), cold-start impact, response-time impact; refine the placeholder table/name filters
      in 13.3 and 13.6 against the real span data this run produces
    - _Requirements: 3.6, 3.7, 3.8_

  - [ ] 15.8 Decide D6 (compression) from 15.7's measurements
    - Keep `gzip` if cold-start and response-time impact are acceptable; revert to `none`
      (task 4.3) otherwise. **If changed, rebuild and redeploy** (repeat 15.1–15.3's image-then-SHA
      sequence for the affected workloads) and rerun the live checks in 15.7 that are sensitive to
      compression (ingested bytes, response-time impact) before treating the decision as final
    - _Requirements: 3.8_

  - [ ] 15.9 Confirm or refute the ₹0.00 marginal-cost projection
    - Using 15.7's measured ingested bytes against the task-12.1 script
    - _Requirements: 11.4, 11.5_

  - [ ] 15.10 Run the Sink_Smoke_Check (13.6) against the live deployment
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

- [ ] 16. Final checkpoint — full suite
  - Run `./gradlew check` across `api-gateway`, `portfolio-service`, `market-data-service`,
    `insight-service`, and `common-observability`. Confirm `terraform plan` is clean (no diff)
    after the apply in 15.4 (and after any rebuild triggered by 15.8). Ensure all tests pass. Ask
    the user if questions arise.

## Notes

- No task is marked optional (`*`). Every test task listed either verifies a named Correctness
  Property or is the sole proof of a mandatory requirement (most directly, task 6.5's five-case
  activation matrix — six rounds of design review were needed to get that mechanism right, and it
  should be read as the actual specification for `common-observability`'s activation behaviour,
  not a nice-to-have regression test; and the Requirement 9 / Task 11.2 tests in 7.4–7.7, which
  are this spec's other named deliverable).
- Each task cites the specific requirement clauses (and, where applicable, the design's
  Correctness Property number) it satisfies. Negative/prohibition requirements that do not
  correspond to buildable work are listed once in Global Constraints rather than forcing an
  artificial task.
- The AzAPI resource (task 2.2) and the Terraform apply (task 15.4) both carry the
  merged-but-not-applied risk documented in `docs/todos/backlog/terraform-apply-not-automatic-on-merge/`.
- `docs/todos/backlog/kafka-consumers-have-no-scale-rule/` is a pre-existing, separate concern
  (D7) — this plan works around it (task 13.6's wake step) rather than fixing it.
- Deployment is deliberately three gated steps (15.1–15.3) before Terraform ever touches Azure
  (15.4): path filters, then a rebuild, then an explicit SHA check on all five workloads. Skipping
  straight to 15.4 risks enabling export against images that predate the sanitizer.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.4", "5.1", "7.1", "7.2", "7.3", "8.1"] },
    { "id": 1, "tasks": ["1.3", "1.5", "2.1", "3.1", "3.2", "4.1", "4.2", "4.3", "4.4", "4.5", "5.2", "5.3", "6.1", "6.4", "8.3"] },
    { "id": 2, "tasks": ["1.6", "2.2", "5.4", "6.2", "7.4", "7.5", "7.6", "7.7", "8.2", "8.4"] },
    { "id": 3, "tasks": ["6.3", "6.5"] },
    { "id": 4, "tasks": ["9"] },
    { "id": 5, "tasks": ["4.6", "10.1", "10.2", "10.3", "11.1", "11.2", "12.1", "13.1", "13.2", "13.3", "13.4", "13.5", "13.6", "13.7"] },
    { "id": 6, "tasks": ["14"] },
    { "id": 7, "tasks": ["15.1"] },
    { "id": 8, "tasks": ["15.2"] },
    { "id": 9, "tasks": ["15.3"] },
    { "id": 10, "tasks": ["15.4"] },
    { "id": 11, "tasks": ["15.5", "15.6", "15.7"] },
    { "id": 12, "tasks": ["15.8"] },
    { "id": 13, "tasks": ["15.9", "15.10"] },
    { "id": 14, "tasks": ["16"] }
  ]
}
```
