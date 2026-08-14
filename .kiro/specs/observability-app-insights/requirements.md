# Requirements Document

## Introduction

All four services (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) already carry `spring-boot-starter-opentelemetry` on Spring Boot 4.1 / Java 21, with identical configuration in each `application.yml`: W3C propagation, sampling probability `1.0`, and OTLP export gated **off** behind `MANAGEMENT_TRACING_EXPORT_ENABLED:false`, `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED:false`, and `OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces`. `spring.kafka.{template,listener}.observation-enabled: true` is set in main configuration on the three messaging services. HTTP trace continuity across the reactive gateway boundary is genuinely proven by `HttpTraceContextPropagationIT`, which asserts trace-ID equality. So instrumentation is not the gap.

The gap is that this telemetry has nowhere to go and nothing stopping it from costing money. `infrastructure/terraform/azure/main.tf` provisions one Log Analytics workspace (`wealth-prod-la`, `PerGB2018`, 30-day retention) wired to the ACA environment as its container-log plane. There is no Application Insights resource, no `OTEL_*` or `MANAGEMENT_TRACING_*` environment variable in any Terraform, and `workspaceCapping.dailyQuotaGb = -1.0` — no ingestion ceiling of any kind. Consequently ACA exports container logs only; no trace or metric ever leaves a service.

Two structural facts about the deployment shape this spec more than the instrumentation does. First, the producer of the event this feature most needs to trace is **not one of the four services**: `PriceUpdatedEvent` is published by `market_data_refresh`, an `azurerm_container_app_job` (`main.tf:281`) whose `env` block carries no tracing configuration. Second, the `container-app` module declares only `min_replicas`/`max_replicas` with **no scale rules of any kind** — no KEDA Kafka scaler — and `min_replicas` defaults to `0` for both `portfolio-service` and `insight-service`. A Kafka message therefore cannot wake a consumer; consumption happens only while a consumer is awake for unrelated HTTP reasons. Any verification that depends on observing a producer and consumer span in the same trace must account for both facts explicitly or it will be non-deterministic.

This feature turns on trace export and gives it a destination, under a hard monthly ceiling of **₹1100** for *total* spend in `wealth-azure-prod-rg`. Measured baseline (Cost Analysis, August 2026): actual month-to-date ₹234.07 against a full-month forecast of **₹551.78**, of which Container Registry is ₹234.06 — approximately all of it. Log Analytics and Azure Monitor both bill **₹0.00**, because a standing 5 GB/month per-billing-account Analytics ingestion allowance absorbs all 26 MB of current ingestion. Container Apps and Azure OpenAI do not appear in the breakdown at all, meaning ACA consumption sits entirely inside the monthly free grant.

The cost requirements exist to bound a *runaway*, not a steady state — the failure mode this project has already experienced twice, as a 3–4× cost spike in May 2026 and as a crash-loop producing 5–7 failed context refreshes per deploy in August 2026. They are deliberately sized so that the ceiling holds **even if the shared allowance is entirely consumed elsewhere**: both workspace caps sit at the 0.023 GB/day platform minimum, giving `31 × (0.023 + 0.023) = 1.426 GB/month`. Priced at the authoritative Central India INR meter of **₹303.9479/GB** that is **₹433.43**; added to the ₹551.78 forecast, **₹985.21** against a ₹1100 ceiling.

All cost arithmetic in this spec SHALL be derived from `currencyCode='INR'` retail meters, never from USD meters multiplied by an assumed exchange rate. An earlier draft did exactly that and understated worst-case ingestion by roughly 8%. No exchange rate is quoted anywhere in this document, deliberately: publishing one invites the conversion this rule exists to prevent.

Two consequences follow. First, the remaining margin is **₹114.79 (10.4%)**, which exists to absorb documented cap overshoot. The ceiling was raised from ₹1000 to ₹1100 for precisely this reason: at ₹1000 the same caps left ₹14.79, so allowance-independence held arithmetically but had no shock absorption, and 0.023 GB/day is Azure's floor so no cap-side lever remained.

Second, recurring controls are all free ones — the caps, the Cost Management Budget_Alert, the manual Allowance_Audit, and the Export_Kill_Switch as the response. This is a **judgment, not an affordability constraint**: at ₹1100 the documented cap-alert mechanism — a Log Analytics scheduled-query rule at ~₹47.20/month at 15-minute frequency (₹94.39 at 10-minute, ₹283.18 at 1-minute) — does fit, bringing the total to ₹1,032.41.

The trade must be stated accurately, because a daily cap is weaker than it first appears. **It stops ingestion only until its daily reset; a persistent storm resumes the next day, and every day after.** An alert is therefore not merely faster awareness of something already handled — it could trigger the Export_Kill_Switch before another cycle begins, and so genuinely reduce spend. Excluding it means accepting repeated daily cap cycles, with detection latency bounded only by the Audit_Cadence, in exchange for preserving the ₹114.79 margin. That is the owner's call, and it is coherent with the arithmetic: the worst case assumes both caps are hit on all 31 days, which is precisely what a 31-day Audit_Cadence permits. The bound and the cadence are the same assumption, so the calculation already prices the behaviour the alert would prevent.

Neither the Budget_Alert nor the caps make this self-correcting. Cost Management data lags 8–24 hours and the alert is non-enforcing; the caps stop ingestion but reset daily. Enforcement therefore rests on the Allowance_Independence check and the Export_Kill_Switch, which is why the check has explicit periodic and event-driven triggers rather than firing only on design change.

Scoped differently, the exposure this feature *adds* is the Telemetry_Workspace alone: `31 × 0.023 = 0.713 GB`, or ₹216.71 worst case, leaving **₹331.51** of margin. The ₹985.21 figure additionally counts Platform_Workspace ingestion, which is pre-existing and today entirely **unbounded** — capping it is a net improvement to a risk this spec did not create.

The transport is the ACA **managed OpenTelemetry agent**, selected deliberately over the GA Application Insights Java agent. The managed agent consumes standard OTLP, so no Azure SDK, exporter dependency, or Java agent enters any service — Azure specificity lives entirely in ACA environment configuration, preserving the multi-cloud posture that keeps AWS a viable standby. It also preserves the existing Spring/Micrometer observation instrumentation, which the Java agent would displace: Microsoft does not support the Micrometer Tracing API as custom Java telemetry, so adopting that agent would orphan the Kafka continuity work this spec completes. The trade accepted is that the managed agent is **Public Preview**, carries traces but not metrics to Application Insights, accepts OTLP over gRPC only, requires Application Insights local authentication, and runs as a single non-HA replica.

This spec also completes **Task 11.2** of `.kiro/specs/springboot-41-springai-2-migration/`, the only incomplete sub-task of that spec. That task names **both** consumers — `market-data-service` → `portfolio-service` / `insight-service` — so both must be proven, and both already have probes (`KafkaTracePropagationProbe`, `InsightKafkaTracePropagationProbe`). The existing tests do not merely under-assert; they hand-build `new KafkaTemplate<>(producerFactory)` fixtures never given the application `ObservationRegistry` (`KafkaTraceContextPropagationIT` autowires it and never reads it; `PriceUpdatedEventKafkaRoundTripIT` names its fixture `marketDataLikeProducer`). Those templates are unobserved, so `spring.kafka.template.observation-enabled=true` configures a bean they never touch, and removing the manual `traceparent` header would today produce no header at all.

Scope is deliberately narrow: **traces only**. Application logs continue to reach the Platform_Workspace as ACA container console logs and are not double-shipped over OTLP; ACA platform metrics remain the metrics story. Custom and JVM metrics, OTLP log export, archive tiers, an always-on OpenTelemetry Collector, KEDA scale rules, and any allowance-polling service are out of scope.

## Glossary

- **Managed_Otel_Agent**: The Azure Container Apps managed OpenTelemetry agent, configured at the ACA environment level, which receives OTLP from the Traced_Workloads and forwards traces to the App_Insights_Resource. Public Preview; single non-HA replica; no separate compute charge.
- **Traced_Workloads**: The five compute units that must export traces — the four Container Apps (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) **and** the `market_data_refresh` Container App Job, which is a distinct `azurerm_container_app_job` resource and the production publisher of `PriceUpdatedEvent`.
- **Producer_Job**: The `market_data_refresh` Container App Job specifically, called out separately wherever the four-service phrasing would otherwise exclude it.
- **App_Insights_Resource**: A workspace-based Azure Application Insights component backed by the Telemetry_Workspace; the Managed_Otel_Agent's traces destination.
- **Telemetry_Workspace**: A **new**, dedicated Log Analytics workspace backing the App_Insights_Resource, separate from the Platform_Workspace so a telemetry ceiling cannot suppress container diagnostics.
- **Platform_Workspace**: The existing `wealth-prod-la` workspace, receiving ACA container console and system logs; remains the application's log source.
- **Injected_Otlp_Endpoint**: The OTLP endpoint value that ACA injects into workload containers when the Managed_Otel_Agent is enabled. Consumed as provided; never overridden in Terraform.
- **Otlp_Transport**: The wire protocol used by the exporter, governed by the Spring property `management.opentelemetry.tracing.export.otlp.transport` (values `http` or `grpc`; Boot 4.1 property default `http`). Spring Boot 4.1 **does** map the standard `OTEL_EXPORTER_OTLP_PROTOCOL` environment variable onto this property via `OpenTelemetryEnvironmentVariableEnvironmentPostProcessor`, and ACA injects that variable as `grpc`, so the effective value would be correct without intervention. It is nevertheless set explicitly — see Requirement 1.5.
- **Trace_Export_Toggle**: The `MANAGEMENT_TRACING_EXPORT_ENABLED` environment variable gating whether spans leave a workload; the Export_Kill_Switch's mechanism.
- **Export_Kill_Switch**: The operational ability to stop all span export by setting the Trace_Export_Toggle to `false` and redeploying, with no code change.
- **Root_Sampler**: The parent-based sampler at each trace root, whose probability is `management.tracing.sampling.probability`.
- **Sampling_Ratio**: The Root_Sampler's probability. Initial value `1.0`, deployment-configurable by environment variable.
- **Sampling_Review_Trigger**: A measured condition requiring a documented review: a rolling seven-day mean above 50% of the Telemetry_Cap, or any single non-deliberate day above 80% of it. Because the Telemetry_Cap is already at the platform minimum and cannot be lowered, and raising it would forfeit Allowance_Independence, the Sampling_Ratio is the only routine **ratio** control — but it is not the only available response. Root-cause remediation of whatever is producing the volume, tightening the Cardinality_Bound or span/attribute filtering, and the Export_Kill_Switch all remain available and may be preferable.
- **Ingestion_Allowance**: The standing 5 GB/month per-**billing-account** free tier on the Analytics Logs ingestion meter in `centralindia` (`tierMinimumUnits` 0 → ₹0.00, 5 → **₹303.9479/GB**). Shared across eligible subscriptions and workspaces; observable but not controllable from this project.
- **Telemetry_Cap**: The Telemetry_Workspace daily ingestion quota, set to the platform minimum **0.023 GB/day**.
- **Platform_Cap**: The Platform_Workspace daily ingestion quota, set to the platform minimum **0.023 GB/day** — approximately 30× current console-log volume.
- **Allowance_Independence**: The property that worst-case ingestion at both caps, priced at the full paid rate with **zero** Ingestion_Allowance, still leaves total resource-group spend below the Budget_Amount.
- **Cost_Circuit_Breaker**: The role the caps play — an emergency ceiling with documented overshoot and a daily reset, *not* a guaranteed monetary limit.
- **Alert_Channel**: The Budget_Alert's own Cost Management email notification list. Free, and independent of Log Analytics, so delivery does not depend on a workspace that may be capped or storming. It is **not** an Azure Monitor alert rule.
- **Free_Controls_Constraint**: The rule that a recurring cost control may be adopted only if its INR price is folded into the Allowance_Independence calculation and the result still leaves overshoot margin. Charged Log Analytics scheduled-query alert rules (~₹47.20/month at 15-minute frequency, ₹94.39 at 10-minute, ₹283.18 at 1-minute in Central India) are affordable at the ₹1100 Budget_Amount but excluded by judgment: one would consume 41% of the ₹114.79 overshoot margin, in exchange for accepting repeated daily cap cycles and detection latency bounded by the Audit_Cadence. The caps stop ingestion only until their daily reset, so an alert would genuinely reduce recurrence rather than merely duplicate them — the exclusion trades prevention for margin, and should not be defended on the grounds that the caps already handle it. A cheaper `Alerts Metric Monitored` meter exists, recorded here for price accuracy: it is tiered, with the first ten monitored time series included at ₹0 and ₹9.4394/month applying only beyond that. It is not adopted, and the decision does not turn on its price — no metric exposing Log Analytics workspace cap state has been verified to exist.
- **Cap_Observation**: Detection of cap proximity or cap-reached state by Observability_Runbook KQL executed during the Allowance_Audit, rather than by a charged alert rule. Slower than alerting, and accepted as such.
- **Audit_Cadence**: **No more than 31 days between completed Allowance_Audit runs.** Expressed as a maximum interval rather than "monthly", which could permit a longer gap between two calendar-month audits, and measured on *completion* so a started-but-abandoned audit does not reset the clock. It is the binding constraint on how long a persistent cap-cycling storm can continue undetected, and on how stale the meter and forecast inputs may become. It is deliberately the same 31-day span the Allowance_Independence worst case assumes, so the bound and the detection latency are consistent rather than independently optimistic.
- **Budget_Alert**: An `azurerm_consumption_budget_resource_group` on `wealth-azure-prod-rg`; the only control here observing total spend rather than log ingestion.
- **Budget_Amount**: **₹1100/month**, the owner-confirmed ceiling, expressed in the subscription's billing currency. Raised from ₹1000 on 2026-08-14 to give the Allowance_Independence bound real overshoot margin (₹114.79 rather than ₹14.79).
- **Workspace_Retention**: **30 days**, configured on the Telemetry_Workspace, matching the existing Platform_Workspace and incurring no retention charge (the first 31 days are included).
- **App_Table_Retention**: **90 days**, the interactive retention Microsoft includes at no additional retention charge for `App*` tables in workspace-based Application Insights. Set and verified explicitly rather than assumed, because it differs from Workspace_Retention and the difference is easy to misread as a misconfiguration.
- **Service_Identity**: Resource attributes making a span attributable to one workload — `service.name` from `spring.application.name`, `service.version` from image tag or commit, `deployment.environment.name`.
- **Trace_Correlation_Fields**: Trace ID and span ID in each workload's structured console log output, enabling a KQL join from a Platform_Workspace log line to the corresponding App_Insights_Resource record.
- **Redaction_Policy**: Allowlist-and-redaction rules barring query strings, authorization headers, tokens, credentials, portfolio monetary values, user-identifying values, and AI prompt/completion text from any exported span attribute, exception message, or Kafka header.
- **Cardinality_Bound**: Limits on span attribute count and value length, and the prohibition on raw URLs, Kafka keys, user IDs, or exception text as span or metric dimensions.
- **Kafka_Continuity_Contract**: A `PriceUpdatedEvent` published by `market-data-service` and consumed by `portfolio-service` **and** `insight-service` shares one W3C trace ID across producer and each consumer, with distinct span IDs and no new root span at either consumer.
- **Producer_Wire_Test**: A test proving the **auto-configured** `KafkaTemplate` injects a valid W3C `traceparent`, with no hand-stamped header on the happy path.
- **Consumer_Wire_Test**: A test proving a production listener extracts that header and continues the same trace ID under a distinct span ID. Required **once per consuming service**.
- **Consumer_Wake_Step**: An explicit HTTP request issued to each consumer as part of the Sink_Smoke_Check, compensating for the absence of a Kafka scale rule by ensuring a replica is running to consume the message.
- **Sink_Smoke_Check**: A post-deployment, non-continuous verification that a real Kafka producer dependency and its consumer requests arrive in `AppDependencies` / `AppRequests` sharing one `OperationId` with intact parent linkage.
- **Observability_Runbook**: Documented procedures and KQL for daily volume by table, cap and overshoot detection, the Sink_Smoke_Check, and periodic Allowance_Audit.
- **Ga_Migration_Trigger**: Native Azure Monitor OTLP ingestion reaching General Availability, retiring the Managed_Otel_Agent preview dependency.
- **Entra_Ingestion_Trigger**: The **independent** condition retiring Application Insights local authentication — the selected ingestion route supporting Microsoft Entra authenticated ingestion. May occur before or after the Ga_Migration_Trigger.
- **Allowance_Audit**: A periodic, manual billing-scope Cost Details reconciliation estimating remaining Ingestion_Allowance. An audit input, never a runtime signal or polled service.

## Requirements

### Requirement 1: Trace export enabled across every traced workload

**User Story:** As the operator of this platform, I want spans from every workload that participates in a request or event to reach Application Insights, so that traces are complete rather than missing their producer.

#### Acceptance Criteria

1. THE Terraform configuration SHALL set the Trace_Export_Toggle to `true` for **all five** Traced_Workloads, including the Producer_Job.
2. IF the Producer_Job's environment lacks the Trace_Export_Toggle, THEN the Kafka_Continuity_Contract SHALL be considered unverifiable in production, because the Producer_Job is the production publisher of `PriceUpdatedEvent`.
3. THE Traced_Workloads SHALL consume the Injected_Otlp_Endpoint as provided by ACA.
4. THE Terraform configuration SHALL NOT set or override the OTLP endpoint value for any Traced_Workload.
5. THE Terraform configuration SHALL set the Otlp_Transport to `grpc` explicitly, for determinism and visibility in Terraform, **not** because it would otherwise be unset — Spring Boot 4.1 maps `OTEL_EXPORTER_OTLP_PROTOCOL` onto the property and ACA injects it as `grpc`. The explicit value ensures the transport cannot change silently if the injected variable changes or the mapping is altered.
6. WHEN a Traced_Workload starts with no injected endpoint present, THE workload SHALL start successfully and SHALL NOT export, so local and CI runs are unaffected.
7. THE Traced_Workloads SHALL export traces without adding any Azure SDK, Azure Monitor exporter dependency, or Java agent to any module's `build.gradle`.
8. THE `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` setting SHALL remain `false`.
9. THE Traced_Workloads SHALL NOT export application logs over OTLP.

### Requirement 2: Sink provisioning, retention, and secret handling

**User Story:** As the operator, I want the telemetry sink provisioned as code with its retention and secrets handled deliberately, so that it is reproducible and does not leak.

#### Acceptance Criteria

1. THE Terraform configuration SHALL provision a Telemetry_Workspace distinct from the Platform_Workspace.
2. THE Terraform configuration SHALL provision a workspace-based App_Insights_Resource backed by the Telemetry_Workspace.
3. THE Terraform configuration SHALL configure the Managed_Otel_Agent on the ACA environment with the App_Insights_Resource as its traces destination.
4. THE Terraform configuration SHALL set Workspace_Retention to 30 days on the Telemetry_Workspace explicitly, and SHALL NOT rely on a provider or service default.
5. THE deployment verification SHALL confirm the effective interactive retention on the `App*` tables is App_Table_Retention (90 days) and that it incurs no retention charge.
6. IF the effective `App*` retention differs from App_Table_Retention, THEN the discrepancy SHALL be resolved before export is enabled, because retention above the included period is a per-GB charge that the cost calculation does not budget for.
7. THE Terraform configuration SHALL set `DisableLocalAuth = false` explicitly on the App_Insights_Resource.
8. THE App_Insights_Resource connection string SHALL NOT appear in Terraform outputs, container images, application configuration files, or log output.
9. THE Terraform state containing the connection string SHALL be held in the existing remote backend with encryption at rest and access restricted to the deployment identity, and SHALL NOT be committed to the repository.
10. THE Terraform configuration SHALL pin the preview API version used for the Managed_Otel_Agent.

### Requirement 3: Sampling policy

**User Story:** As someone demonstrating this system, I want a failed or slow request to reliably produce a complete trace, so that the demo shows real diagnostics rather than a sampled fragment.

#### Acceptance Criteria

1. THE Root_Sampler SHALL be parent-based, so a downstream workload honours the root's decision rather than re-deciding.
2. THE Sampling_Ratio SHALL be `1.0` initially.
3. THE Sampling_Ratio SHALL be settable per deployment by environment variable, with no code change or image rebuild.
4. THE acceptance criteria SHALL NOT require that errors are always retained, because head sampling cannot know in advance that a request will fail.
5. WHEN a Sampling_Review_Trigger is met, THE operator SHALL conduct a documented review and select a response from: root-cause remediation of whatever is producing the volume, tightening the Cardinality_Bound or span/attribute filtering, lowering the Sampling_Ratio, or the Export_Kill_Switch. Lowering the Sampling_Ratio SHALL NOT be mandatory, because it is the only routine *ratio* control but not necessarily the correct response.
6. THE Sampling_Review_Trigger SHALL NOT propose raising the Telemetry_Cap as a remedy, because that would forfeit Allowance_Independence.
7. BEFORE production acceptance, THE operator SHALL execute one representative HTTP → Kafka run at Sampling_Ratio `1.0` and record span count, ingested bytes, exporter drop count, and cold-start and response-time impact.
8. THE measured bytes from that run SHALL be used to confirm or refute the projected marginal cost in Requirement 11.

### Requirement 4: Ingestion ceilings with allowance-independence

**User Story:** As the owner of a fixed monthly budget, I want a runaway to hit an automatic ceiling that holds even if the free allowance disappears, so that my budget is safe without depending on something I cannot control.

#### Acceptance Criteria

1. THE Terraform configuration SHALL set the Telemetry_Cap to `0.023` GB/day on the Telemetry_Workspace.
2. THE Terraform configuration SHALL set the Platform_Cap to `0.023` GB/day on the Platform_Workspace, which today has no cap.
3. THE caps SHALL satisfy Allowance_Independence: `31 × (Telemetry_Cap + Platform_Cap) × ₹303.9479`, added to the current forecast **and to every recurring control charge**, SHALL remain below the Budget_Amount **with the Ingestion_Allowance assumed to be zero**.
4. THE Allowance_Independence calculation SHALL use `currencyCode='INR'` retail meters directly, and SHALL NOT use USD meters multiplied by an assumed exchange rate.
5. THE Allowance_Independence calculation SHALL be re-evaluated, and SHALL pass, before the Trace_Export_Toggle is first enabled.
6. THE Allowance_Independence calculation SHALL additionally be re-evaluated at **every** Allowance_Audit; before any cost-bearing infrastructure change; whenever a recurring charge is added to the design; and whenever the operator obtains updated INR meter or forecast data.
7. EACH scheduled re-evaluation SHALL fetch the current Central India INR retail meter values and the current resource-group cost forecast, rather than reusing previously recorded figures.
8. THE re-evaluation SHALL NOT be triggered by meter or forecast *movement*, because detecting movement would require monitoring or polling that Requirement 11 forbids. Freshness is instead obtained by criterion 7 refetching on a fixed cadence, which bounds staleness to the Audit_Cadence.
9. IF the Allowance_Independence calculation fails at any re-evaluation, THEN THE Trace_Export_Toggle SHALL be set to `false`, or remain `false`, until the calculation passes again.
10. THE Budget_Alert SHALL NOT be treated as a substitute for this check, because Cost Management data lags 8–24 hours and the alert is non-enforcing.
11. THE caps SHALL NOT be treated as a substitute for this check, because a daily cap stops ingestion only until its daily reset and a persistent storm resumes on the following day.
12. THE acceptance criteria SHALL NOT size the caps against the Ingestion_Allowance, because it is billing-account scoped and can be consumed by another subscription without any signal in this project.
13. THE caps SHALL be described as a Cost_Circuit_Breaker and SHALL NOT be described as a guaranteed monetary ceiling, because Azure documents that ingestion can overshoot a daily cap and the excess is billed.
14. THE Allowance_Audit SHALL run at least once per Audit_Cadence.
15. THE design SHALL NOT provision any charged Log Analytics scheduled-query alert rule, per the Free_Controls_Constraint. THE stated trade SHALL be that the ₹114.79 overshoot margin is preserved in exchange for accepting repeated daily cap cycles and detection latency bounded by the Audit_Cadence — **not** that the rule is unaffordable, which it no longer is at ₹1100, and **not** that it would only duplicate the caps.
16. THE cap-proximity and cap-reached states SHALL be detected by Cap_Observation during the Allowance_Audit, and the resulting detection latency SHALL be stated in the Observability_Runbook as an accepted trade.
17. THE caps SHALL be recognised as the only control here that **stops** consumption, and SHALL also be recognised as stopping it only until the daily reset; the Budget_Alert notifies against delayed cost data and stops nothing.
18. IF the Allowance_Audit shows the Ingestion_Allowance exhausted or a cap reached on more than one day since the previous audit, THEN THE operator SHALL evaluate the Export_Kill_Switch as the documented first response, because repeated cap cycling is the failure mode no free control prevents.
19. THE Terraform configuration SHALL NOT introduce Auxiliary-plan tables, which are not subject to the workspace daily cap.
20. THE App_Insights_Resource tables SHALL remain on the Analytics plan, because `AppRequests`, `AppDependencies`, `AppExceptions`, and `AppMetrics` support neither the Basic nor the Auxiliary plan.

### Requirement 5: Total-spend budget alert

**User Story:** As the owner, I want an automated signal when total project spend approaches my ceiling, so that a cost regression surfaces before the invoice.

#### Acceptance Criteria

1. THE Terraform configuration SHALL provision a Budget_Alert scoped to the `wealth-azure-prod-rg` resource group.
2. THE Budget_Alert SHALL NOT be scoped to the subscription, which contains resource groups unrelated to this project.
3. THE Budget_Alert SHALL use the Budget_Amount in INR.
4. THE Budget_Alert SHALL notify at 70% of the Budget_Amount on `Actual` cost and at 100% on `Forecasted` cost.
5. THE Budget_Alert SHALL deliver through the Alert_Channel, which is Cost Management's own free email notification and not an Azure Monitor alert rule.
6. THE deployment verification SHALL confirm the Budget_Alert exists **in Azure**, not merely in Terraform source, because a merged Terraform change is not live until a manual `workflow_dispatch` apply runs — the failure mode that caused the 2026-08-12 incident and left this alert missing since it was recommended on 2026-05-17.
7. THE spec's cost claims SHALL be scoped to `wealth-azure-prod-rg` and SHALL NOT assert control over total subscription spend.

### Requirement 6: Export must never affect application behaviour

**User Story:** As a user of the platform, I want telemetry problems to be invisible to me, so that an observability outage never becomes an application outage.

#### Acceptance Criteria

1. IF the Managed_Otel_Agent is unreachable, restarting, upgrading, or failing, THEN THE Traced_Workloads SHALL continue serving requests normally and SHALL NOT fail startup.
2. THE exporter SHALL use explicitly configured bounded queues and timeouts via `management.opentelemetry.tracing.export.{max-queue-size,max-batch-size,schedule-delay,timeout}` and `…otlp.connect-timeout`.
3. WHEN the exporter queue is full, THE workload SHALL drop spans rather than block request handling.
4. THE Traced_Workloads SHALL flush pending spans on `SIGTERM` where the platform permits.
5. THE Producer_Job SHALL flush pending spans before exit, because a Job terminates promptly after its run and would otherwise discard its final batch.
6. THE deployment SHALL NOT add an always-on health probe, sidecar, or Collector to compensate for the agent's single non-HA replica.

### Requirement 7: Span content safety and bounds

**User Story:** As the owner of a system holding financial data, I want telemetry safe to show in a demo, so that displaying a trace cannot leak portfolio or credential data.

#### Acceptance Criteria

1. THE Redaction_Policy SHALL bar query strings, authorization headers, bearer tokens, passwords, and password hashes from every exported span attribute.
2. THE Redaction_Policy SHALL bar portfolio monetary values, holdings, and per-user identifying values from span attributes.
3. THE Redaction_Policy SHALL bar AI prompt and completion text, consistent with `insight-service`'s existing `log-prompt=false` posture.
4. THE Redaction_Policy SHALL apply to `AppRequests.Url`, `AppDependencies.Data`, exception messages and stack traces, and Kafka headers — not only custom attributes.
5. THE Cardinality_Bound SHALL cap span attribute count and value length.
6. THE Traced_Workloads SHALL NOT use raw URLs, Kafka record keys, user IDs, or exception text as span or metric dimensions.
7. WHEN a route is recorded on a span, THE workload SHALL use a normalized route template rather than a concrete path containing identifiers.

### Requirement 8: Service identity and trace–log correlation

**User Story:** As someone debugging a production issue, I want to move from a log line to its trace, so that export is usable rather than an undifferentiated span pile.

#### Acceptance Criteria

1. EACH Traced_Workload SHALL emit a distinct, stable Service_Identity.
2. THE Service_Identity SHALL include `service.name`, `service.version`, and `deployment.environment.name`.
3. THE Producer_Job SHALL emit `service.name` = `market-data-refresh-job`, set by an explicit Job-only environment override.
4. THE Producer_Job's `service.name` SHALL NOT be derived from `spring.application.name`, because the Job runs the `market-data-service` image (`main.tf:358`) whose `spring.application.name` is `market-data-service`; deriving it would make the Job and the `market-data-service` Container App indistinguishable in telemetry and would make criterion 1 unsatisfiable.
5. THE override SHALL apply to the Producer_Job only and SHALL NOT alter the `market-data-service` Container App's identity.
6. THE Traced_Workloads SHALL emit Trace_Correlation_Fields in structured console log output.
7. THE Observability_Runbook SHALL contain a KQL query, verified against real data, joining a Platform_Workspace container log line to its corresponding App_Insights_Resource record.
8. THE correlation capability SHALL be delivered in this increment, because excluding OTLP log export is only safe while console logs remain joinable to traces.

### Requirement 9: Kafka producer→consumer trace continuity (completes Task 11.2)

**User Story:** As an engineer, I want a `PriceUpdatedEvent` to carry one trace to every consumer, so that the asynchronous half of the system is as traceable as the HTTP half.

#### Acceptance Criteria

1. THE Producer_Wire_Test SHALL use the application's **auto-configured** `KafkaTemplate`, not a hand-constructed one.
2. THE Producer_Wire_Test SHALL assert a valid W3C `traceparent` is present on the produced record without any test code writing that header on the happy path.
3. A Consumer_Wire_Test SHALL exist for `portfolio-service`.
4. A Consumer_Wire_Test SHALL exist for `insight-service`, because Task 11.2 names both consumers and both already carry trace probes.
5. EACH Consumer_Wire_Test SHALL assert the consumer's trace ID equals the producer span's trace ID.
6. EACH Consumer_Wire_Test SHALL assert the consumer's span ID **differs** from the producer's, because Spring Kafka creates distinct producer-send and consumer spans.
7. THE tests SHALL NOT assert span-ID equality, and SHALL NOT assert only that a consumer span is non-null — the existing assertion, which proves observation fired but not continuity.
8. IF a `traceparent` header is malformed or absent, THEN THE consumer SHALL start a new valid trace and SHALL continue processing the message rather than failing it.
9. THE hand-built fixtures in `KafkaTraceContextPropagationIT` and `PriceUpdatedEventKafkaRoundTripIT` SHALL be eliminated or given the application `ObservationRegistry`, because a manually constructed `KafkaTemplate` is unaffected by `spring.kafka.template.observation-enabled`.
10. THE tests SHALL keep Sampling_Ratio at `1.0` and Trace_Export_Toggle `false`, proving continuity without network export.
11. THE tests SHALL be focused per boundary rather than one cross-module harness, matching the existing `PriceUpdatedEventProducerWireContractTest` / `PriceUpdatedEventConsumerPathTest` split.

### Requirement 10: Deterministic deployed sink verification

**User Story:** As the operator, I want proof that continuity survives the preview adapter, so that passing in-process tests do not create false confidence about what reaches Azure.

#### Acceptance Criteria

1. THE Sink_Smoke_Check SHALL verify that a Kafka producer dependency and its consumer requests arrive in the App_Insights_Resource sharing one `OperationId` with intact parent linkage.
2. THE Sink_Smoke_Check SHALL trigger production by executing the Producer_Job, which already publishes real `PriceUpdatedEvent` messages.
3. THE Sink_Smoke_Check SHALL include a Consumer_Wake_Step for `portfolio-service` and for `insight-service` before asserting on consumer spans.
4. IF the Consumer_Wake_Step is omitted, THEN THE check SHALL be considered non-deterministic, because both consumers default to `min_replicas = 0` and no Kafka scale rule exists to wake them.
5. THE Sink_Smoke_Check SHALL define a bounded wait and SHALL report an explicit failure on timeout rather than passing silently.
6. THE Sink_Smoke_Check SHALL be documented in the Observability_Runbook together with its KQL.
7. THE Sink_Smoke_Check SHALL be run after observability deployment and after any Managed_Otel_Agent or API-version change.
8. THE Sink_Smoke_Check SHALL NOT run as a continuous or scheduled workload.
9. THE design SHALL NOT introduce KEDA or Kafka scale rules to make this deterministic, because that would change production scaling behaviour and cost for a verification concern.
10. THE monitoring SHALL NOT treat absence of telemetry as an alerting signal, because the workloads legitimately have no traffic for long periods.

### Requirement 11: Cost verification

**User Story:** As the owner, I want the cost claims verifiable, so that "it is free" is measured rather than assumed.

#### Acceptance Criteria

1. THE Observability_Runbook SHALL contain KQL reporting daily ingestion volume by table for both workspaces.
2. THE Observability_Runbook SHALL contain a procedure for detecting daily-cap overshoot.
3. THE Allowance_Audit SHALL be a documented periodic manual reconciliation, and SHALL NOT be implemented as a polling service, scheduled job, or runtime input.
4. THE projected marginal cost of ₹0.00 SHALL be stated as an **unverified projection** until the Requirement 3 representative run supplies measured trace volume, because no trace has ever been exported from this system.
5. WHEN measured trace volume is available, THE operator SHALL record actual ingested bytes and revise the projection.
6. THE design SHALL NOT introduce an always-on OpenTelemetry Collector in any topology, because a continuously provisioned `0.25 vCPU / 0.5 GiB` container costs approximately **₹405–₹1,861/month** — derived from the Central India ACA per-second vCPU and memory meters, active or idle, with and without the monthly free grant — against headroom of roughly ₹548/month, the upper bound alone exceeding the entire Budget_Amount.

### Requirement 12: Non-goals

**User Story:** As a reviewer, I want boundaries stated, so that scope creep is visible rather than inferred.

#### Acceptance Criteria

1. THE increment SHALL NOT deliver custom or JVM metrics, because the Managed_Otel_Agent's Application Insights destination does not carry metrics.
2. THE increment SHALL NOT deliver OTLP log export.
3. THE increment SHALL NOT introduce an archive or long-term-retention tier.
4. THE increment SHALL NOT alter Container Registry configuration or image-supply architecture, even though it is approximately all current project spend, because that carries independent security, availability, and deployment consequences.
5. THE increment SHALL NOT degrade Sampling_Ratio or telemetry quality to offset a fixed Container Registry charge.
6. THE increment SHALL NOT build a dashboard-refresh cost control, because querying Analytics-plan tables is included and `Analytics Logs Data Analyzed` is a legacy ingestion meter, not a query charge.

## Recorded Decisions and Constraints

These are settled decisions and accepted constraints. They are not acceptance criteria because they describe rationale and posture rather than verifiable system behaviour; the behaviour they imply is covered by the numbered requirements above.

### D1 — Sink selection

The Managed_Otel_Agent was selected over the GA Application Insights Java agent. The Java agent could supply Kafka continuity through its own instrumentation, but Microsoft does not support the Micrometer Tracing API as custom Java telemetry, so production would use a different tracing mechanism from the Spring Observation tests and Task 11.2 would cease to validate the production path. It would also place Azure instrumentation inside each service.

### D2 — Accepted preview exposure

The Managed_Otel_Agent is Public Preview. Accepted consequences: traces but not metrics to Application Insights; OTLP over gRPC only; environment-wide configuration of destinations and routing (source-side settings such as Sampling_Ratio remain per-workload); a single non-HA replica with no exposed health metrics; and Managed_Otel_Agent secrets cannot currently use Key Vault. Telemetry loss during agent restart, upgrade, or application scale-from-zero is acceptable and non-blocking.

### D3 — Accepted authentication exposure

Application Insights local authentication permits anyone holding the connection string to inject telemetry, which can poison diagnostics and consume the Telemetry_Cap. It does not grant read or query access. The instrumentation key is an identifier, not an authentication secret; the exposure is ingestion-side only.

### D4 — Two independent exit criteria

The Ga_Migration_Trigger and the Entra_Ingestion_Trigger are separate conditions and may occur in either order. They must not be combined into a single "when it reaches GA" clause, which would allow the D3 security exception to outlive its justification.

### D5 — Portability posture

The application boundary remains vendor-neutral OTLP. Reactivating the AWS standby requires changing the exporter destination, not application code.

### D6 — Compression

`management.opentelemetry.tracing.export.otlp.compression` defaults to `none`. The design must make an explicit choice, weighing reduced egress and faster batch flush against CPU cost on scale-to-zero workloads.

### D7 — Known production characteristic, out of scope

Because no Kafka scale rule exists and both consumers default to `min_replicas = 0`, Kafka messages are consumed only while a consumer is awake for unrelated HTTP reasons. Producer and consumer spans may therefore be separated by arbitrary delay in production traces. This predates the spec and is not introduced by it; it is tracked separately rather than resolved here, because fixing it means adding KEDA scale rules with their own cost and behavioural consequences.
