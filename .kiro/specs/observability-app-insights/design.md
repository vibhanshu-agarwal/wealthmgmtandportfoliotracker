# Design Document

## Overview

This design turns on OTLP trace export from five workloads, routes it through the Azure Container Apps managed OpenTelemetry agent into a workspace-based Application Insights resource, and bounds the cost of doing so within a ₹1100/month resource-group ceiling. It also completes Task 11.2 of the Spring Boot 4.1 migration spec by proving Kafka producer→consumer trace continuity at production fidelity.

Almost all of the instrumentation already exists. `spring-boot-starter-opentelemetry` is on the classpath of all four services with W3C propagation, sampling `1.0`, and Kafka observation enabled; HTTP continuity across the reactive gateway boundary is already proven by `HttpTraceContextPropagationIT`. What does not exist is a destination, any ingestion ceiling, a producer that exports, or any redaction of what gets exported. The work is weighted toward Terraform, configuration, and verification rather than application code.

Three findings from the design investigation shape everything below and are not obvious from the code:

1. **The event producer is not one of the four services.** `PriceUpdatedEvent` is published in production by `market_data_refresh`, an `azurerm_container_app_job` (`main.tf:281`) running the `market-data-service` image. Any design phrased as "the four services" omits the one workload whose spans the Kafka work depends on.
2. **A standing 5 GB/month Analytics ingestion allowance exists** at billing-account scope (`tierMinimumUnits` 0 → ₹0.00, 5 → ₹303.9479/GB, same meter ID). It is why Log Analytics bills ₹0.00 against 26 MB/month today. It is shared and invisible from inside this project, so the cost design treats it as an observed input and does not rely on it.
3. **No Kafka scale rule exists.** Both consumers default to `min_replicas = 0` with no KEDA scaler, so a message cannot wake a consumer. Any naive producer→consumer verification is therefore non-deterministic.

### Key design decisions

#### D1 — Managed agent over the Application Insights Java agent

The ACA managed OpenTelemetry agent consumes standard OTLP, so no Azure SDK, exporter dependency, or Java agent enters any module's `build.gradle`. Azure specificity lives in ACA environment configuration.

The Java agent was the GA alternative, rejected on coherence rather than support status. It supplies its own Kafka instrumentation, and Microsoft does not support the Micrometer Tracing API as custom Java telemetry — so production would trace through a different mechanism than the Spring Observation tests exercise, and Task 11.2 would stop validating the production path. The managed agent is the only option that keeps the existing instrumentation and this spec's Kafka work load-bearing. Sources: [S1], [S4].

#### D2 — Accepted preview exposure

Traces but not metrics to Application Insights; OTLP over gRPC only; environment-wide configuration of *destinations and routing* (source-side settings such as sampling remain per-workload); a single non-HA replica with no exposed health metrics and no separate compute charge; agent secrets cannot currently use Key Vault. Telemetry loss during agent restart, upgrade, or scale-from-zero is acceptable and non-blocking. Source: [S1].

#### D3 — Accepted authentication exposure

Application Insights local authentication is required by the managed agent. Anyone holding the connection string can inject telemetry, poisoning diagnostics and consuming the telemetry cap. It grants no read or query access; the instrumentation key is an identifier, not an authentication secret. Exposure is ingestion-side only. Sources: [S1], [S5].

#### D4 — Two independent exit criteria

The GA migration trigger (native Azure Monitor OTLP ingestion reaching GA) and the Entra ingestion trigger (the selected route supporting Entra authenticated ingestion) are **separate conditions, in either order**. Collapsing them into one "when it's GA" clause would let the D3 exception outlive its justification. Sources: [S2], [S5].

#### D5 — Portability posture

The application boundary stays vendor-neutral OTLP. Reactivating the AWS standby changes the exporter destination, not application code.

#### D6 — Compression: `gzip`, provisional

Set `gzip`, but **provisionally, pending the representative run**. Span batches are repetitive text and compress well, and a shorter flush window matters for the Producer_Job, which exits promptly and must flush before termination. Two caveats keep this from being settled: it compresses only the workload→agent hop, not the agent→Azure Monitor hop, which is the agent's business and not configurable here; and ₹0.00 ACA billing reflects the free grant, not spare CPU headroom on a 0.25 vCPU container. The representative run (§Testing) records cold-start and response-time impact; if either regresses, revert to `none`. Source: [S6].

#### D7 — Kafka consumer wake behaviour is out of scope

Consumption happens only while a consumer is awake for unrelated HTTP reasons, so producer and consumer spans may be separated by arbitrary delay in production. Predates this spec; tracked at `docs/todos/backlog/kafka-consumers-have-no-scale-rule/`. The design accommodates it (Consumer_Wake_Step) rather than fixing it, because fixing it means KEDA scale rules with their own cost and behavioural consequences.

---

## Architecture

### Trace export path

```
┌──────────────────────────────────────────────────────────────┐
│ ACA Environment  (managed OTel agent — AzAPI-configured)     │
│                                                              │
│  api-gateway ──┐                                             │
│  portfolio ────┤                                             │
│  market-data ──┼── OTLP/gRPC ──▶ Managed OTel Agent          │
│  insight ──────┤                 (single replica, preview)   │
│  refresh Job ──┘                          │                  │
└───────────────────────────────────────────┼──────────────────┘
                                            │ local auth
                                            ▼
                              App Insights (workspace-based)
                                            │
                                            ▼
                              Telemetry_Workspace  [cap 0.023 GB/day]

  container console/system logs ──────▶ Platform_Workspace [cap 0.023 GB/day]
```

Two workspaces, deliberately. The *separation* — not asymmetric cap values — is what stops a telemetry storm suppressing the container diagnostics needed to diagnose it. Cap values are symmetric at Azure's 0.023 GB/day floor because that is what allowance-independence requires.

The OTLP **endpoint is injected by ACA** and consumed as provided; the existing placeholder `${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}` already reads it, so no per-workload endpoint wiring is required and local/CI runs are unaffected.

Transport is nonetheless set explicitly. Not because it would otherwise be wrong — Spring Boot 4.1 maps `OTEL_EXPORTER_OTLP_PROTOCOL` onto `management.opentelemetry.tracing.export.otlp.transport` through `OpenTelemetryEnvironmentVariableEnvironmentPostProcessor`, and ACA injects that variable as `grpc` — but so the transport is visible in Terraform and cannot change silently if the injected variable or the mapping changes. Sources: [S1], [S7].

### Cost control architecture

Four controls, each with a different job, none substituting for another:

| Control | What it does | What it cannot do |
|---|---|---|
| Workspace daily caps | **Stops** ingestion | Only until the daily reset — a persistent storm resumes next day |
| Budget_Alert | Notifies on total spend | Lags 8–24 h; non-enforcing |
| Allowance_Audit | Refetches meters/forecast, revalidates the bound | Manual; latency bounded by the 31-day cadence |
| Export_Kill_Switch | Stops export at source | Requires a human decision and a redeploy |

```
31 × (0.023 + 0.023) = 1.426 GB/month
1.426 × ₹303.9479    = ₹433.43
₹551.78 + ₹433.43    = ₹985.21   vs ₹1100  →  ₹114.79 margin (10.4%)
```

The 31-day worst case and the 31-day audit cadence are **the same assumption by construction**. This is why no charged alert rule is provisioned — not because one is unaffordable at ₹1100 (a 15-minute scheduled-query rule fits at ₹1,032.41), but because it would spend 41% of the overshoot margin to prevent recurrence the bound already prices. Sources: [S3], [S8], [S9].

A self-hosted Collector was rejected outright: roughly ₹405–₹1,861/month for a continuously provisioned `0.25 vCPU / 0.5 GiB` container against ₹548 of headroom, and it would be the first always-on compute in a stack that otherwise scales to zero.

### Kafka trace continuity path

```
market-data-service (or refresh Job)
   auto-configured KafkaTemplate  [observation-enabled]
        │  injects W3C traceparent into record headers
        ▼
   Kafka topic  market-prices
        │
        ├──▶ portfolio-service listener ─▶ same traceId, new spanId
        └──▶ insight-service   listener ─▶ same traceId, new spanId
```

The contract is the **trace ID**. Span IDs must differ — Spring Kafka creates distinct producer-send and consumer spans, so asserting span-ID equality would assert the framework is broken.

Neither existing test exercises this. Both hand-build `new KafkaTemplate<>(producerFactory)`; `KafkaTraceContextPropagationIT` autowires `ObservationRegistry` and never reads it, `PriceUpdatedEventKafkaRoundTripIT` names its fixture `marketDataLikeProducer`. A hand-built template is unaffected by `spring.kafka.template.observation-enabled`, so removing the manual header would produce no header at all. The fix is the auto-configured bean, not an assertion change. Source: [S10].

### Trace–log correlation path

Logs are **not** exported over OTLP — they already reach the Platform_Workspace as container console logs, and dual shipping would duplicate ingestion. Correlation is a cross-workspace join on trace ID → `OperationId`, which makes trace/span IDs in log output load-bearing in this increment.

---

## Components and Interfaces

### 1. `Telemetry_Workspace` (new) — Terraform, AzureRM

`azurerm_log_analytics_workspace`, `PerGB2018`, `retention_in_days = 30`, `daily_quota_gb = 0.023`.

### 2. `App_Insights_Resource` (new) — Terraform, AzureRM

`azurerm_application_insights`, `application_type = "java"`, `workspace_id` → component 1, and **`local_authentication_enabled = true`**. The inverse `local_authentication_disabled` is deprecated in AzureRM 4.x; the positive form is current. An inline comment names D3 and the Entra exit trigger. Source: [S11].

The connection string must not appear in `outputs.tf`, images, application configuration, or logs. It will exist in Terraform state; protection is the existing remote backend with encryption at rest and access limited to the deployment identity.

### 3. Managed agent configuration (new) — Terraform, **AzAPI**

**This cannot be done with AzureRM.** `azurerm_container_app_environment` exposes no managed-OpenTelemetry block, and the repository currently declares only `azurerm ~> 4.0`. Microsoft documents the Terraform path as `azapi_update_resource` against the managed environment. Sources: [S1], [S12], [S13].

Required changes:

- **Provider registration** — add to `versions.tf`:
  ```hcl
  azapi = {
    source  = "Azure/azapi"
    version = "~> 2.0"
  }
  ```
  and a corresponding `provider "azapi" {}` block in `providers.tf`, authenticating via the existing OIDC/Workload Identity configuration.
- **API version — `2025-10-02-preview`, decided here.** Requirement 2.10 requires a pinned literal, and leaving a placeholder would defer the decision into implementation. This version carries both properties the body needs. Source: [S13].
- **Resource and body** — `azapi_update_resource` with `resource_id = azurerm_container_app_environment.main.id`. The connection string and the traces destination are **two sibling properties**; the connection string is *not* nested inside `openTelemetryConfiguration`:

```hcl
resource "azapi_update_resource" "aca_otel_agent" {
  type        = "Microsoft.App/managedEnvironments@2025-10-02-preview"
  resource_id = azurerm_container_app_environment.main.id

  body = {
    properties = {
      appInsightsConfiguration = {
        connectionString = azurerm_application_insights.telemetry.connection_string
      }
      openTelemetryConfiguration = {
        tracesConfiguration = {
          destinations = ["appInsights"]
        }
      }
    }
  }
}
```

Logs and metrics destinations are deliberately unset — logs would duplicate container console logs, and the App Insights destination carries no metrics. Sources: [S1], [S18].

- **Dependency ordering** — the Application Insights dependency is **inferred** by Terraform from the `connection_string` expression in the body; no explicit `depends_on` is required for it. The `resource_id` reference likewise orders this after the environment. An explicit `depends_on` would be harmless but is not needed, and claiming otherwise would misrepresent how Terraform builds its graph.
- **State handling** — `azapi_update_resource` patches rather than owns the environment. AzureRM continues to own the environment resource, so the two must not both manage `openTelemetryConfiguration`. Because AzureRM does not model that block, there is no drift-fight today; if a future AzureRM version adds it, this component must be migrated rather than duplicated. A comment records this.

### 4. `Platform_Cap` (new) — Terraform

`daily_quota_gb = 0.023` added to `wealth-prod-la`, which today has `-1`. Bounds a pre-existing unbounded exposure.

### 5. `Budget_Alert` (new) — Terraform

`azurerm_consumption_budget_resource_group` on `wealth-azure-prod-rg`, `amount = 1100`, `time_grain = "Monthly"`, notifications `Actual` at 70% and `Forecasted` at 100%, delivered to Cost Management's own free email contacts — not an Azure Monitor action group, which would be a charged rule. Resource-group scope, not subscription: the subscription also holds an unrelated certification environment. Source: [S8].

### 6. Per-workload trace configuration — Terraform (4 Container Apps)

| Variable | Value |
|---|---|
| `MANAGEMENT_TRACING_EXPORT_ENABLED` | `true` |
| `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT` | `grpc` |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | `1.0` |
| `SERVICE_VERSION` | image tag (consumed by component 8) |
| `DEPLOYMENT_ENVIRONMENT_NAME` | `prod` (consumed by component 8) |

The OTLP endpoint and `OTEL_RESOURCE_ATTRIBUTES` are **deliberately absent** — ACA injects both.

The sampler is also pinned explicitly in `application.yml` rather than left to prose:

```yaml
management:
  opentelemetry:
    tracing:
      sampler: parent-based-trace-id-ratio
```

The Boot 4.1 metadata confirms this is already the default, so this changes no behaviour. It is set for the same reason as the transport: parent-based propagation is a correctness property that downstream workloads depend on, and a property that matters should be visible in configuration rather than inherited silently from a default that could change.

### 7. Producer_Job configuration (new) — Terraform

The same **five** variables listed in component 6 — the export toggle, transport, sampling probability, `SERVICE_VERSION`, and `DEPLOYMENT_ENVIRONMENT_NAME` — on `azurerm_container_app_job.market_data_refresh`, plus:

```
OTEL_SERVICE_NAME = "market-data-refresh-job"
```

Required because the Job runs the `market-data-service` image (`main.tf:358`) whose `spring.application.name` is `market-data-service`; without the override the Job and the Container App are indistinguishable. Boot honours `OTEL_SERVICE_NAME` through `OpenTelemetryResourceAttributes`, which takes precedence over the application name without editing shared configuration. Source: [S7].

### 8. Resource attributes — `application.yml` (all workloads)

**`OTEL_RESOURCE_ATTRIBUTES` must not be set by Terraform.** ACA injects that variable itself when the managed agent is enabled, and an environment variable has a single value — setting it in Terraform *replaces* ACA's value before Boot ever reads it. There is no merge at the environment-variable layer, so the earlier draft's claim that Boot would merge two versions of the same variable was wrong.

Project attributes are supplied through Boot's resource-attribute **property map**, which Boot merges with the untouched `OTEL_RESOURCE_ATTRIBUTES`:

```yaml
management:
  opentelemetry:
    resource-attributes:
      service.version: ${SERVICE_VERSION:local}
      deployment.environment.name: ${DEPLOYMENT_ENVIRONMENT_NAME:local}
```

Terraform then injects only the two project-specific variables — `SERVICE_VERSION` (image tag) and `DEPLOYMENT_ENVIRONMENT_NAME` (`prod`) — leaving ACA's variable alone. The `:local` defaults keep local and CI runs working.

This is also why the design builds no custom `Resource` bean: that would replace platform-contributed attributes wholesale, the same failure in a different layer. Sources: [S7], [S1], [S19].

### 9. Redaction and cardinality controls (new) — all services

Property 5 requires a mechanism, not a policy statement. Three layers:

**(a) Numeric span limits** — verified property names from the Boot 4.1 configuration metadata (an earlier draft used the wrong namespace and a non-existent `max-span-attributes`):

```yaml
management:
  opentelemetry:
    tracing:
      limits:
        max-attribute-value-length: 512   # default: unset
        max-attributes: 64                # default: 128
        max-events: 16                    # default: 128
        max-links: 8                      # default: 128
```

Limits bound blast radius. They are **not** a redaction mechanism — truncating a value to 512 characters leaves the first 512 characters of whatever it contained. Source: [S14].

**(b) Attribute suppression before span creation — `ObservationFilter`.** A `SpanProcessor` cannot do this: `onEnd` receives a read-only ended span and has no ability to remove attributes, so the earlier draft's mechanism was unimplementable. Filtering must happen while the observation is still mutable.

The mechanism is therefore a Micrometer `ObservationFilter` registered on the `ObservationRegistry`, which rewrites an observation's `KeyValues` **before stop, and before final key-values are tagged onto the span**. The span already exists by that point — the filter changes what is recorded onto it, not whether it is created. It removes keys in a configured deny-set (URL-, query-, token-, and identifier-bearing keys) and strips query strings from any retained URL-shaped value. Paired with a custom HTTP `ObservationConvention` so route values are recorded as normalized templates rather than concrete paths. Sources: [S15], [S20], [S21].

**(c) Sanitizing `SpanExporter` wrapper — the enforcement boundary.** `ObservationFilter` alone cannot satisfy Property 5, for a lifecycle reason: the filter runs immediately before *stop*, but `TracingObservationHandler.onError()` has already called `span.error(Throwable)`, and the OTel bridge immediately calls `recordException(Throwable)` and sets the span **status description** from the raw exception message. By the time any filter runs, the exception event and status description are already recorded. Filtering is therefore the wrong layer for exception content. Source: [S24].

The guarantee is instead a sanitizing `SpanExporter` delegate. `SpanData` is **immutable**, so this is not "the last point at which data is mutable" — it is the last *interception* boundary, and sanitizing means substituting a replacement view rather than editing in place.

**Data model.** Extend OTel's public `DelegatingSpanData` and override exactly three accessors with sanitized immutable values. Source: [S26].

| Override | Sanitization |
|---|---|
| `getAttributes()` | apply the deny-set as a backstop, catching anything (b) missed or that was set outside an observation |
| `getEvents()` | replace each `ExceptionEventData` with an ordinary `EventData` carrying the exception class name plus a redaction marker; sanitize the same `exception.*` semantic-convention keys on any *other* event whose attributes carry them, not only objects already typed as `ExceptionEventData` |
| `getStatus()` | replace the status description on the same basis |

Replacing `ExceptionEventData` with plain `EventData` is **not optional**. `ExceptionEventData` exposes the original `Throwable` via `getException()`, so sanitizing only its attributes would leave the raw exception — message and stack trace — reachable by anything that inspects the event. Rewriting attributes without changing the event type would produce a redaction that looks correct and is not.

This is also why an ordinary event carrying `exception.*` attributes must be sanitized even when it was never an `ExceptionEventData`: nothing stops application code, a library, or a future contributor from calling `Span.addEvent("exception", Attributes.of(...))` directly with manually populated `exception.message`/`exception.stacktrace` keys, bypassing the `recordException(Throwable)` path entirely. A boundary that only recognises the typed case would miss that path silently, so both are checked: **event type** (`ExceptionEventData` → replaced outright) and **attribute keys** (`exception.*` present on any event → attributes redacted regardless of type).

**The fourth test invariant.** The three assertions from the previous round (span attributes, event attributes, status description) are necessary but not sufficient — an implementation that rewrites `ExceptionEventData.getAttributes()` while leaving the original `ExceptionEventData` object in place would pass all three while `getException()` still returns the live `Throwable`. The redaction test suite therefore adds a fourth assertion, and it is the one that actually closes the gap: for every event on the sanitized `SpanData`, either the event is **not** an instance of `ExceptionEventData` at all, or — for defense in depth against a future OTel SDK exposing the same leak through a different type — an explicit attempt to obtain the original exception via `getException()` is asserted to fail or return nothing. The first form is the primary assertion; the type check alone is sufficient given the (b)/(c) design above, but the design does not rely on the type check being the *only* thing standing between a bug and a leaked stack trace.

This applies to **all** exception types unconditionally. An earlier draft limited it to "exception types on a configured list", which would leak every type not on the list — the wrong default for a redaction control, where the safe posture is deny-by-default.

`export`, `flush`, and `shutdown` delegate to the wrapped exporter unchanged.

**Bean model — the sanitizer must not be a top-level `SpanExporter` bean.** Boot collects every `SpanExporter` bean and composes them (`SpanExporters` in the tracing autoconfiguration). Source: [S25]. Registering the sanitizing delegate *alongside* the OTLP exporter would therefore export **both** a raw copy and a sanitized copy — a redaction control that leaks everything it redacts, while appearing to work in any test that inspects only the sanitized path.

The safe graph is:

```
custom BatchSpanProcessor bean
    └── privately wraps → SanitizingSpanExporter
                              └── delegates to → Boot-created OtlpGrpcSpanExporter
```

**Auto-configuration contract.** `common-observability`'s configuration is itself a `@AutoConfiguration`, imported via `AutoConfiguration.imports`, and its `@ConditionalOnMissingBean` must actually win the race against Boot's own `BatchSpanProcessor`-producing bean (in `OpenTelemetryTracingAutoConfiguration`, which wraps whatever `SpanExporter` beans exist — including the OTLP one from `OtlpTracingAutoConfiguration` — into a `CompositeSpanExporter`). This is an auto-configuration-versus-auto-configuration ordering question, not a user-bean-versus-auto-configuration one, so it does not benefit from Spring's usual "user configuration always wins" guarantee — that guarantee applies to plain `@Configuration` classes, not to another `@AutoConfiguration`. The custom configuration is therefore annotated:

```java
@AutoConfiguration(after = OtlpTracingAutoConfiguration.class, before = OpenTelemetryTracingAutoConfiguration.class)
```

so it registers after the OTLP exporter bean exists (there is something to wrap) and before Boot's own composite-processor bean is defined (so `@ConditionalOnMissingBean` on Boot's side sees this module's bean already present and backs off). Sources: [S25], [S28].

**Activation must match Boot's own exporter-enablement contract, not a coarser global check — and that contract is enabled by default, not disabled.** A plain `@ConditionalOnProperty` on `management.tracing.export.enabled` is insufficient because it does not reflect how Boot itself decides whether the OTLP exporter is active, and in particular gets the *default* wrong. Boot's `OtlpTracingConfigurations.Exporters` guards `otlpGrpcSpanExporter`/`otlpHttpSpanExporter` with `@ConditionalOnEnabledTracingExport("otlp")`, backed by `OnEnabledTracingExportCondition`. That condition first reads `management.tracing.export.otlp.enabled`; if unset, it falls back to `management.tracing.export.enabled`; **if that is also unset, the condition matches** — its own `ConditionMessage` states this outcome literally as `"tracing is enabled by default"`. There is no property state under which the condition's *absence* of configuration means "off"; only an explicit `false` at either level does that. Property enablement is therefore separate from whether anything is actually exported: with nothing configured, the condition matches, but if no `OtlpGrpcSpanExporter` bean and no endpoint are reachable, there is nothing to wrap. This is exactly why the design's second guard exists, and it is why the two guards must not be treated as answering the same question:

```java
@ConditionalOnEnabledTracingExport("otlp")
@ConditionalOnBean(OtlpGrpcSpanExporter.class)
```

`@ConditionalOnEnabledTracingExport("otlp")` answers *is OTLP tracing export enabled by property configuration* (default: **yes**, per Boot's own condition). `@ConditionalOnBean(OtlpGrpcSpanExporter.class)` answers the separate question *does a gRPC OTLP exporter actually exist to wrap* — which depends on Boot having successfully constructed one, which in turn depends on an endpoint being resolvable. This module is active only when both are true, and the design must not describe the second guard as "handling the export-is-off case" — the first guard already handles that whenever export is explicitly disabled; the second guard's actual job is handling the case where export is enabled (by explicit property or by default) but no gRPC exporter bean exists to sanitize, which is a distinct condition from "off." Sources: [S29], [S30], [S33] — [S29] documents the `@ConditionalOnEnabledTracingExport` contract; [S33] is `OnEnabledTracingExportCondition` itself, whose `ConditionMessage` states `"tracing is enabled by default"` verbatim and is the actual source of that behaviour, not [S30], which shows only where the annotation is *applied* to the exporter beans; [S28] establishes only that `OtlpTracingAutoConfiguration` exists and imports these configurations, not the condition semantics themselves.

**This repository's own `application.yml` does not rely on Boot's default.** Every service sets `management.tracing.export.enabled: ${MANAGEMENT_TRACING_EXPORT_ENABLED:false}` (`api-gateway/src/main/resources/application.yml:61` and equivalently elsewhere), which resolves the global property to the literal string `false` whenever the environment variable is absent — precisely the local/CI posture Requirement 1.6 describes. So while Boot's *own* default is "enabled," this codebase's default is explicitly "disabled," and the two must not be conflated: a context test asserting "no properties set → inactive" would be testing a state this repository never actually produces once `application.yml` is loaded, and would pass for the wrong reason if it somehow did.

**Context tests — five cases, separating property enablement from exporter existence**, because the earlier four-case matrix conflated them and got case 1 backwards:

1. **No enablement properties set, but an endpoint is configured and the transport is gRPC** — `OnEnabledTracingExportCondition` matches (Boot's own "enabled by default"), a gRPC exporter bean is constructible, so the module is **active**, with exactly one sanitized processor.
2. exporter-specific `otlp` property `true`, global property unset or `false` — active, proving the exporter-specific override of a globally-off setting is honoured.
3. exporter-specific `otlp` property `false`, global property `true` — inactive and the context still starts, proving the more-specific `false` overrides the coarser `true`.
4. the enablement condition matches (by property or by default), but no endpoint is configured and no `OtlpGrpcSpanExporter` bean exists — inactive, context starts without error, proving the bean condition is a genuine second gate rather than a formality, and that "enabled" and "active" are not the same thing.
5. **this repository's actual `application.yml` defaults, no environment override** — inactive, specifically *because* the global property explicitly resolves to `false`, not because Boot's condition would otherwise be off. This is the case that matters for local and CI runs and must be tested against the real resolved configuration, not a hand-constructed property set that happens to omit the key.

Every case where the module is active must additionally assert there is exactly one sanitized processor and no raw one — reusing context test assertions 2 and 3 from the auto-configuration verification above, run against each activating combination rather than a single hardcoded one.

**Property parity with the bean it replaces.** Boot's own processor is built from `OpenTelemetryTracingProperties`/`OtlpTracingProperties`; the custom builder must read the same properties and produce the same effective configuration, or the test-only tiny queue/batch settings used by the saturation test — and the production bounds required by Requirement 6.2 — would configure a bean that is not actually in the export path:

| Setting | Source property |
|---|---|
| `includeUnsampled` | `management.opentelemetry.tracing.export.include-unsampled` |
| exporter timeout | `management.opentelemetry.tracing.export.timeout` |
| max batch size | `management.opentelemetry.tracing.export.max-batch-size` |
| max queue size | `management.opentelemetry.tracing.export.max-queue-size` |
| schedule delay | `management.opentelemetry.tracing.export.schedule-delay` |
| `MeterProvider` | optional `ObjectProvider<MeterProvider>`, same as Boot's own bean |
| internal telemetry version | `InternalTelemetryVersion.LATEST` (component 9's addition, not present in Boot's default) |

The sanitizer remains a **privately constructed delegate inside this configuration**, never its own `@Bean` of type `SpanExporter` — that constraint from the previous round is unchanged; it is restated here because the auto-configuration wiring above is where it would be easiest to violate by accident.

**Verification — an application-context test**, since the wiring above is exactly the kind of thing that looks correct in isolation and silently duplicates or drops exporters in practice:

1. Export off (`management.tracing.export.enabled=false`) — context starts with no `OtlpGrpcSpanExporter` bean present and no error.
2. Export on with gRPC transport — exactly **one** effective `BatchSpanProcessor` is active in the context (not Boot's default plus this module's).
3. The sanitizer is **not** collected by `SpanExporters` — i.e. `ObjectProvider<SpanExporter>` used elsewhere in the context does not see it as an independent bean.
4. A bound override (e.g. a reduced `max-queue-size`) set via test properties reaches the custom builder's configured processor, not a Boot-default one running alongside it.

Test 2 and test 3 are the direct regression tests for the "raw and sanitized copies both exported" failure mode identified in the previous round.

Layers (b) and (c) are complementary rather than redundant: (b) is cheap and prevents high-cardinality keys ever being created, (c) is the boundary that makes the property provable regardless of what produced the span.

**(d) Kafka headers** — no record header other than `traceparent` is copied into span attributes.

**Ownership — a new `common-observability` Gradle module.** Not `common-dto`: that module is deliberately a dependency-free DTO and contract library with no Spring or auto-configuration machinery, and adding a Spring dependency to it would change its role and leak Spring onto every consumer of the shared contracts. `common-observability` carries the filter, the convention, and an auto-configuration, and is depended on by the four services and the Job. Requirement 7 applies to all five workloads, so a single shared module is the only way to avoid four divergent copies.

### 10. Log pattern and correlation fields — `application.yml` (all workloads)

The console log pattern is extended with a **fixed, parseable** trace/span prefix, because the runbook KQL parses it:

```
%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [${OTEL_SERVICE_NAME:${spring.application.name:}},%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n
```

Note the placeholder syntax: Spring uses `${name:default}`, so the fallback is written `${OTEL_SERVICE_NAME:${spring.application.name:}}`. Writing `:-` (Docker/shell syntax) would make `-` the first character of the fallback *value*. The `%X{traceId:-}` and `%X{spanId:-}` portions are Logback's own default syntax and are correct as written — two different placeholder systems appear in the same line. Source: [S23].

The service field resolves the **effective OpenTelemetry service name** with `spring.application.name` as fallback. Using `spring.application.name` alone would make the Producer_Job log as `market-data-service` while its telemetry reports `market-data-refresh-job` — the log field would then fail to distinguish all five workloads, which is the field's whole purpose, and a correlation query filtering on it would silently conflate the Job with the service.

The contract the KQL depends on is the bracketed triple `[service,traceId,spanId]` in that order, with empty MDC values rendering as empty strings rather than being omitted — so field positions are stable whether or not a trace is active. Any change to this pattern is a breaking change to the correlation query and must update both.

### 11. Producer_Job flush on exit (new) — `market-data-service`

The Job runs with `SPRING_MAIN_WEB_APPLICATION_TYPE=none` and can terminate the JVM before the exporter's scheduled 5-second flush.

**The existing lifecycle must be preserved.** `MarketDataRefreshJobRunner.run()` currently ends with `exitHandler.accept(SpringApplication.exit(context, () -> finalExitCode))`, where `exitHandler` is an overridable `IntConsumer` defaulting to `System::exit` — that seam is what makes its forked-process tests possible. Removing `System.exit`, as an earlier draft proposed, would change Job termination behaviour and invalidate those tests. The flush is therefore **inserted into**, not substituted for, the existing sequence:

```
refresh()
  → forceFlush().join(bounded timeout)     ← new, in the finally block
  → SpringApplication.exit(context, …)
  → exitHandler.accept(exitCode)           ← unchanged (System::exit by default)
```

Constraints on the flush step:

- It runs inside the existing `finally`, so it executes on both the success and failure paths.
- The timeout is bounded and short relative to the Job's runtime.
- The `SdkTracerProvider` is resolved as an **optional** dependency for defensive reasons only. Boot 4.1 creates it independently of the OTLP exporter, so it is normally **present even when export is disabled** — `forceFlush()` then simply has no exporting processor to flush and returns immediately. Neither the rationale nor the tests may assume absence; optional resolution guards against a context that genuinely lacks tracing autoconfiguration, not against the export toggle.
- **Flush failure or timeout is logged and discarded.** It must not alter `exitCode` and must not throw, or telemetry would determine the Job's exit status and break the "telemetry never affects application behaviour" property that Requirement 6 exists to guarantee.

Verification is the Sink_Smoke_Check: a Job-produced dependency span in `AppDependencies` is the only end-to-end proof the flush occurred, and its absence is the specific failure signature.

### 12. Kafka test fixtures (rework)

Producer wire test uses the auto-configured `KafkaTemplate`; consumer wire tests exist for both `portfolio-service` and `insight-service`; existing hand-built fixtures either use the auto-configured bean (preferred, production-faithful) or are given `setObservationEnabled(true)` and `setObservationRegistry(...)`.

### 13. `Observability_Runbook` (new) — `docs/runbooks/`

Contents specified in §Operational Procedures below.

---

## Data Models

### Resource attributes

| Attribute | Source |
|---|---|
| `service.name` | `spring.application.name`; Producer_Job overrides via `OTEL_SERVICE_NAME` |
| `service.version` | `management.opentelemetry.resource-attributes`, from the `SERVICE_VERSION` variable (image tag) |
| `deployment.environment.name` | `management.opentelemetry.resource-attributes`, from the `DEPLOYMENT_ENVIRONMENT_NAME` variable (`prod`) |

Neither is sourced from `OTEL_RESOURCE_ATTRIBUTES` — that variable belongs to ACA and must not be set by Terraform (component 8).

### OpenTelemetry span → Application Insights mapping

| OTel span kind | App Insights table |
|---|---|
| `SERVER`, `CONSUMER` | `AppRequests` |
| `CLIENT`, `PRODUCER`, `INTERNAL` | `AppDependencies` |

| W3C / OTel field | App Insights field |
|---|---|
| trace ID | `OperationId` |
| span ID | `Id` |
| parent span ID | `ParentId` |

`OperationId` is the sink-side expression of the Kafka_Continuity_Contract. Source: [S4].

### Table plans

All `App*` tables stay on **Analytics**. `AppRequests`, `AppDependencies`, `AppExceptions`, `AppMetrics` support neither Basic nor Auxiliary; only `AppTraces` supports Basic, and moving it would restrict queries for negligible saving. Auxiliary is excluded entirely — those tables are **not subject to the workspace daily cap**, so one would create an uncapped ingestion path. Source: [S16].

### Retention

| Scope | Value | Charge |
|---|---|---|
| Telemetry_Workspace | 30 days | none (first 31 days included) |
| `App*` tables | 90 days interactive | none (included for workspace-based App Insights) |

The two differ, which is easily misread as misconfiguration; both are set and verified. Source: [S17].

---

## Correctness Properties

### Property 1: Export failure never affects request handling or startup
With the agent unreachable, every workload starts and serves requests normally; spans drop when the queue is full; nothing blocks.

### Property 2: Kafka trace continuity holds to both consumers
A `PriceUpdatedEvent` from the auto-configured `KafkaTemplate` carries a valid `traceparent`; both consumers continue the same trace ID under distinct span IDs, with no new root span.

### Property 3: Malformed or absent `traceparent` degrades safely
A new valid trace starts and the message is still processed.

### Property 4: Every workload is distinguishable
All five emit a distinct, stable `service.name`, including the Producer_Job.

### Property 5: Exported telemetry contains no forbidden values
No query strings, authorization headers, tokens, credentials, portfolio monetary values, per-user identifiers, or AI prompt/completion text in span attributes, `AppRequests.Url`, `AppDependencies.Data`, exception messages, or Kafka headers. Routes are normalized templates.

### Property 6: The cost bound holds with zero allowance
`31 × (Telemetry_Cap + Platform_Cap) × current INR meter`, plus forecast, plus every recurring control charge, stays below ₹1100 — with the allowance assumed zero, from meters fetched at evaluation time.

### Property 7: The kill switch fully stops export
`MANAGEMENT_TRACING_EXPORT_ENABLED=false` plus redeploy stops all export, with no residual path.

### Property 8: Trace and log records join
A trace ID in a `ContainerAppConsoleLogs_CL` line resolves to the corresponding `OperationId` across the two workspaces.

---

## Error Handling

| Condition | Behaviour | Detection |
|---|---|---|
| Agent unreachable / restarting | Spans dropped; requests unaffected; no startup failure | exporter drop count |
| Exporter queue full | Drop, never block | drop count |
| Producer_Job exits before flush | Explicit `forceFlush()` then graceful close (component 11) | absent Job span in smoke check |
| Workspace cap reached | Ingestion stops until daily reset | Cap_Observation at audit |
| Cap reached on >1 day since last audit | Evaluate Export_Kill_Switch | Cap_Observation |
| Allowance exhausted elsewhere | Bound still holds by construction; revalidate | Allowance_Audit |
| Budget 70% actual / 100% forecast | Email notification | Budget_Alert |
| Allowance-independence check fails | Export toggle set/left `false` until it passes | scheduled revalidation |
| Malformed `traceparent` | New trace; message processed | Property 3 test |

Deliberately **not** an alerting signal: absence of telemetry. The workloads legitimately have no traffic for long periods.

---

## Operational Procedures (Observability_Runbook)

### Sampling review

The `Sampling_Review_Trigger` fires when either holds:

- rolling **seven-day mean** ingestion above **50%** of the Telemetry_Cap, or
- any single **non-deliberate** day above **80%** of it.

On firing, the operator conducts a documented review and selects from a **response menu** — lowering the Sampling_Ratio is *not* mandatory:

1. root-cause remediation of whatever is producing the volume;
2. tightening the Cardinality_Bound or the component-9 attribute filter;
3. lowering the Sampling_Ratio;
4. the Export_Kill_Switch.

Raising the Telemetry_Cap is **not** on the menu — it would forfeit allowance-independence.

The sampler is parent-based with a configurable root probability: downstream workloads honour the root decision rather than re-deciding, so a sampled trace is complete end-to-end or absent entirely, never partial.

### Allowance audit

At most 31 days between completed runs. Each run refetches current INR meters and the current resource-group forecast — never reuses recorded figures — re-evaluates allowance-independence, and records cap-proximity and cap-reached state via KQL. If the check fails, the export toggle goes to `false` until it passes.

### Queries

Daily volume by table for both workspaces; cap-proximity and overshoot detection; the cross-workspace trace↔log join; and the Sink_Smoke_Check query.

**Production drop-count extraction**, the executable form of the procedure in §Error Handling and §Testing Strategy — sums the `N` from `BatchSpanProcessor dropped N span(s) … because the queue is full` warnings, scoped to the bounded run-and-drain interval of the representative run:

```kql
ContainerAppConsoleLogs_CL
| where TimeGenerated between (datetime(<run_start>) .. datetime(<drain_end>))
| where Log_s contains_cs "BatchSpanProcessor dropped"
| extend DroppedCount = toint(extract(@"dropped (\d+) span", 1, Log_s))
| summarize TotalDropped = sum(DroppedCount)
```

`has` is deliberately **not** used here: it is a term-boundary operator matching whole tokens, not a substring operator, and `"BatchSpanProcessor dropped"` is a multi-word phrase rather than a single term — `has` would not reliably match it. `contains_cs` is a case-sensitive substring match, which is both correct for this fixed, code-authored warning string and cheaper to reason about than a case-insensitive match would be for a string whose casing never varies. Source: [S31]. The count itself is pulled by `extract()`, a distinct function from the string operators above with its own reference: source [S32].

The consequence of getting this wrong is not a query error but a **silent false negative**: a query that fails to match returns zero rows, `summarize` over zero rows still produces a row with `TotalDropped = 0`, and that reads identically to "genuinely no drops occurred." Requirement 3.7's acceptance procedure is therefore only trustworthy if the query is verified against both directions before it is relied on:

- run once against an interval **known** to contain at least one `BatchSpanProcessor dropped` warning (the saturation test's log output, or a deliberately induced one) and confirm `TotalDropped` matches the known count;
- run once against an interval **known** to contain none and confirm `TotalDropped` is `0` for the right reason — absence of the phrase — not because the match expression silently failed to fire.

`<run_start>` and `<drain_end>` bound the interval named in §Testing Strategy: the run itself plus the wait for the final scheduled flush, so a query issued before that flush completes reads as zero for the wrong reason — a second, independent way this measurement can under-report that the query text alone does not protect against.

---

## Testing Strategy

### Java tests

- **Producer wire test** (`market-data-service`) — auto-configured `KafkaTemplate` injects `traceparent`; no hand-stamped header on the happy path.
- **Consumer wire tests** (`portfolio-service`, `insight-service`) — same trace ID, distinct span ID. One per consuming service, matching the existing producer/consumer wire-contract split rather than a cross-module harness.
- **Malformed-header test** — Property 3.
- **Redaction sentinel tests** — Property 5, one per surface named in requirement 7. Each injects a recognisable sentinel (a token-shaped string, a query string, a monetary value, a user identifier, a prompt fragment) through the surface under test, then asserts the sentinel is absent from the exported `SpanData`. Surfaces: HTTP URL/query, exception message, exception stack trace, Kafka headers, and custom attributes.

  The assertion must cover **four** things, not attributes alone: span attributes, exception event attributes, the span status description, and — per component 9(c) — that no event on the sanitized output remains an `ExceptionEventData` (or, as a defense-in-depth check, that `getException()` is unreachable on any event that is). Checking only attributes would pass while the raw exception message sat in the status description, or while the original `Throwable` remained reachable through an untouched `ExceptionEventData` whose attributes had merely been overwritten — the exact gaps that made the earlier `ObservationFilter`-only design appear adequate across two successive rounds. Tests assert against the output of the component-9(c) exporter wrapper, since that is where the guarantee is enforced.
- **Span limit test** — asserts the configured attribute count and value-length limits are in effect on a deliberately oversized span.

Kafka and redaction tests keep **export disabled** and sampling `1.0`, proving behaviour without network export.

### Exporter isolation tests (export **enabled**)

Property 1 cannot be proven with export disabled — no connection or queue path executes. These two tests are the deliberate exception, and they prove *different* halves of the property.

**(a) Unreachable exporter — proves startup and request isolation.**

- `MANAGEMENT_TRACING_EXPORT_ENABLED=true`;
- OTLP endpoint pointed at a **dead local gRPC port** (nothing listening);
- `connect-timeout` and `timeout` shortened so the test does not inherit the 10s/30s production values;
- asserts the context starts successfully and a request completes normally while export fails in the background.

**(b) Queue saturation — proves drop-not-block.**

Test (a) does not establish Requirement 6.3. A single request against an unavailable endpoint exercises connection failure, not queue-full behaviour; the queue never fills, so "drops rather than blocks" is untested. This test:

- sets test-only tiny bounds (`max-queue-size` and `max-batch-size` reduced to a handful of spans);
- generates enough spans to exceed the queue while export is stalled against the dead endpoint;
- asserts **request latency stays bounded** while spans are being discarded — the actual claim of Requirement 6.3 — rather than merely asserting no exception;
- requires `io.opentelemetry:opentelemetry-sdk-testing` on the test classpath for the in-memory metric reader (new dependency, see below).

### Exporter drop count — defined source

Requirement 3.7 requires the representative run to **record** a drop count. Requirement 6.3 does not reference a count — it requires drop-not-block behaviour, and motivates the saturation test rather than the measurement. The two need different mechanisms, in different environments, and the design must supply both.

**A `SpanExporter` wrapper cannot supply either.** Queue-full spans are discarded *inside* `BatchSpanProcessor` and never reach any exporter, so comparing spans handed to the exporter against spans accepted by it counts a boundary the drops never cross. An earlier draft proposed exactly that and would have measured nothing.

#### Test measurement (saturation test)

The metric family depends on a setting Boot does not configure. OTel's `BatchSpanProcessorBuilder` defaults to `InternalTelemetryVersion.LEGACY`, and Boot 4.1 does not override it, so an unmodified Boot pipeline emits (source: [S27]):

```
processedSpans{processorType="BatchSpanProcessor", dropped="true"}
```

**not** `otel.sdk.processor.span.processed{error.type="queue_full"}`. Registering a `MeterProvider` does not change which family is emitted — an earlier draft assumed it did.

Because component 9(c) already introduces a custom `BatchSpanProcessor` bean, the design resolves this by calling **`setInternalTelemetryVersion(LATEST)`** on that builder, making `otel.sdk.processor.span.processed{error.type="queue_full"}` the emitted family. Had the custom processor not been required for the sanitizer, the alternative would have been to assert the legacy `processedSpans{dropped="true"}` family instead; the design chooses the modern family because the processor exists either way.

The saturation test registers a test `MeterProvider` with an in-memory reader for the duration of the test. **This requires a new test dependency**, `io.opentelemetry:opentelemetry-sdk-testing`, which is not currently resolved in this build and must be added to the affected modules' `testImplementation`.

#### Production measurement (representative run)

The test surfaces above are unavailable in production: metrics export is disabled by design, so no meter pipeline exists to read. Requirement 3.7's run therefore uses a different source — the SDK's own warning, emitted regardless of metrics configuration:

```
BatchSpanProcessor dropped N span(s) … because the queue is full
```

The acceptance procedure sums `N` across these warnings in the container console logs (already flowing to the Platform_Workspace) over the **bounded run-and-drain interval**. Absence of warnings may be recorded as zero **only after** that interval and the final flush/export attempt have completed — a run sampled too early would report zero because the drops have not happened yet, not because there were none.

Neither surface becomes a production control, and neither may add a network or blocking path. Source: [S22].

### Terraform validation

- Both workspaces carry `daily_quota_gb = 0.023`.
- Budget exists at resource-group scope with the correct amount and both notification types.
- All five workloads carry the export toggle and transport; **none** carries an OTLP endpoint override.
- The Producer_Job carries `OTEL_SERVICE_NAME`.
- The `azapi` provider is declared and the managed-agent resource pins a literal preview API version.
- No `azurerm_monitor_scheduled_query_rules_alert*` resource exists — the Free_Controls_Constraint made executable.

### Cost calculation test

Property 6 as an executable check taking the current INR meter and forecast as inputs and asserting the bound. It fails if a recurring charge is added without re-deriving the ceiling.

### Representative pre-production run

Required by requirement 3 before production acceptance. One representative HTTP → Kafka run at Sampling_Ratio `1.0`, recording:

| Measurement | Purpose |
|---|---|
| span count | volume basis |
| **ingested bytes** | confirms or refutes the ₹0.00 marginal-cost projection |
| exporter drop count | queue sizing adequacy — measured from console-log warnings over the bounded run-and-drain interval, **not** from the test-only meter surfaces |
| cold-start impact | scale-from-zero regression |
| response-time impact | request-path regression |

Two decisions depend on its output: whether the projected marginal cost stands (requirement 11.4/11.5), and whether `gzip` is retained or reverted to `none` (D6).

### Deployed verification (Sink_Smoke_Check)

1. Trigger the Producer_Job.
2. **Wake step** — issue an HTTP request to `portfolio-service` and `insight-service`. Without this the check is non-deterministic (D7).
3. Query `AppDependencies`/`AppRequests` for a shared `OperationId` with intact `ParentId`.
4. Bounded wait; explicit failure on timeout, never a silent pass.

Run after observability deployment and after any agent or API-version change. Never scheduled or continuous.

### Retention and deployment verification

Confirm effective `App*` interactive retention is 90 days and Telemetry_Workspace retention 30, neither incurring a retention charge. Confirm the Budget_Alert exists **in Azure**, not merely in Terraform — a merged change is not live until a manual `workflow_dispatch` apply, the failure mode behind the 2026-08-12 incident.

---

## Sources

Primary references for the decisions above. Recorded here because the brainstorm checkpoint that produced them is gitignored and will not survive.

- **[S1]** ACA managed OpenTelemetry agent — https://learn.microsoft.com/en-us/azure/container-apps/opentelemetry-agents
- **[S2]** Azure Monitor OpenTelemetry options — https://learn.microsoft.com/en-us/azure/azure-monitor/containers/opentelemetry-options
- **[S3]** Log Analytics daily cap behaviour — https://learn.microsoft.com/en-us/azure/azure-monitor/logs/daily-cap
- **[S4]** OpenTelemetry spans in Application Insights — https://learn.microsoft.com/en-us/azure/azure-monitor/app/opentelemetry-add-modify
- **[S5]** Application Insights connection strings — https://learn.microsoft.com/en-us/azure/azure-monitor/app/connection-strings
- **[S6]** ACA billing — https://learn.microsoft.com/en-us/azure/container-apps/billing
- **[S7]** Spring Boot observability / OTEL environment variable mapping — https://docs.spring.io/spring-boot/reference/actuator/observability.html
- **[S8]** Cost Management budgets (delay and non-enforcing behaviour) — https://learn.microsoft.com/en-us/azure/cost-management-billing/costs/tutorial-acm-create-budgets
- **[S9]** Azure Retail Prices API — https://learn.microsoft.com/en-us/rest/api/cost-management/retail-prices/azure-retail-prices
- **[S10]** Spring Kafka observation — https://docs.spring.io/spring-kafka/reference/kafka/micrometer.html
- **[S11]** AzureRM `application_insights` schema — https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/application_insights
- **[S12]** AzureRM `container_app_environment` schema — https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs/resources/container_app_environment
- **[S13]** `Microsoft.App/managedEnvironments` API change log — https://learn.microsoft.com/en-us/azure/templates/microsoft.app/change-log/managedenvironments
- **[S14]** Spring Boot tracing span limits — https://docs.spring.io/spring-boot/reference/actuator/tracing.html
- **[S15]** Spring Framework observability defaults — https://docs.spring.io/spring-framework/reference/integration/observability.html
- **[S16]** Azure Monitor Logs data platform / table plans — https://learn.microsoft.com/en-us/azure/azure-monitor/logs/data-platform-logs
- **[S17]** Log Analytics data retention configuration — https://learn.microsoft.com/en-us/azure/azure-monitor/logs/data-retention-configure
- **[S18]** AzAPI `azapi_update_resource` schema — https://registry.terraform.io/providers/Azure/azapi/latest/docs/resources/update_resource
- **[S19]** Spring Boot application properties appendix — https://docs.spring.io/spring-boot/appendix/application-properties/index.html
- **[S20]** OpenTelemetry SDK specification (span processor semantics) — https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/sdk.md
- **[S21]** Micrometer observation components (`ObservationFilter`) — https://docs.micrometer.io/micrometer/reference/observation/components.html
- **[S22]** OpenTelemetry Java — `BatchSpanProcessor` queue-full signalling — https://github.com/open-telemetry/opentelemetry-java/issues/7103
- **[S23]** Spring Boot property placeholder syntax — https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.files.property-placeholders
- **[S24]** Micrometer observation lifecycle — https://docs.micrometer.io/micrometer/reference/observation/introduction.html
- **[S25]** Spring Boot 4.1 `spring-boot-micrometer-tracing-opentelemetry` — `OpenTelemetryTracingAutoConfiguration` / `SpanExporters` source — https://github.com/spring-projects/spring-boot/blob/v4.1.0/module/spring-boot-micrometer-tracing-opentelemetry/src/main/java/org/springframework/boot/micrometer/tracing/opentelemetry/autoconfigure/OpenTelemetryTracingAutoConfiguration.java
- **[S26]** OpenTelemetry Java SDK 1.62 — `DelegatingSpanData` — https://github.com/open-telemetry/opentelemetry-java/blob/v1.62.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/data/DelegatingSpanData.java
- **[S27]** OpenTelemetry Java SDK 1.62 — `BatchSpanProcessorBuilder` / `InternalTelemetryVersion` default — https://github.com/open-telemetry/opentelemetry-java/blob/v1.62.0/sdk/trace/src/main/java/io/opentelemetry/sdk/trace/export/BatchSpanProcessorBuilder.java
- **[S28]** Spring Boot 4.1 `spring-boot-micrometer-tracing-opentelemetry` — `otlp/OtlpTracingAutoConfiguration` source — https://github.com/spring-projects/spring-boot/blob/v4.1.0/module/spring-boot-micrometer-tracing-opentelemetry/src/main/java/org/springframework/boot/micrometer/tracing/opentelemetry/autoconfigure/otlp/OtlpTracingAutoConfiguration.java
- **[S29]** Spring Boot 4.1 `spring-boot-micrometer-tracing` — `ConditionalOnEnabledTracingExport` — https://github.com/spring-projects/spring-boot/blob/v4.1.0/module/spring-boot-micrometer-tracing/src/main/java/org/springframework/boot/micrometer/tracing/autoconfigure/ConditionalOnEnabledTracingExport.java
- **[S30]** Spring Boot 4.1 `spring-boot-micrometer-tracing-opentelemetry` — `otlp/OtlpTracingConfigurations` (`Exporters` inner class applying `@ConditionalOnEnabledTracingExport("otlp")` to `otlpGrpcSpanExporter`/`otlpHttpSpanExporter`) — https://github.com/spring-projects/spring-boot/blob/v4.1.0/module/spring-boot-micrometer-tracing-opentelemetry/src/main/java/org/springframework/boot/micrometer/tracing/opentelemetry/autoconfigure/otlp/OtlpTracingConfigurations.java
- **[S31]** Kusto Query Language — `has` and `contains_cs` string operators — https://learn.microsoft.com/en-us/kusto/query/datatypes-string-operators
- **[S32]** Kusto Query Language — `extract()` function reference — https://learn.microsoft.com/en-us/kusto/query/extract-function
- **[S33]** Spring Boot 4.1 `spring-boot-micrometer-tracing` — `OnEnabledTracingExportCondition` (source of the "tracing is enabled by default" match message) — https://github.com/spring-projects/spring-boot/blob/v4.1.0/module/spring-boot-micrometer-tracing/src/main/java/org/springframework/boot/micrometer/tracing/autoconfigure/OnEnabledTracingExportCondition.java
