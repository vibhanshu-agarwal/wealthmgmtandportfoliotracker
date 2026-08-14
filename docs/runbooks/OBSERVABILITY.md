# Observability — Operator Runbook

Manual procedures for the Azure observability stack: daily volume, cap-proximity,
trace↔log correlation, exporter drop counts, the Allowance_Audit, the
Sink_Smoke_Check, and the Sampling_Review_Trigger.

These are **operator-run, copy-paste procedures**. They are not alerts, not a
polling service, and not a scheduled job.

---

## Prerequisites

- Azure CLI installed and logged in (`az login`)
- Access to resource group `wealth-azure-prod-rg`
- Log Analytics reader on both workspaces (to run KQL)
- Python 3 (Allowance_Audit only), from the repo root or
  `infrastructure/terraform/azure/scripts/`
- GitHub CLI (`gh`) if you need to apply the Export_Kill_Switch via
  `terraform-azure.yml`

---

## Resource map (prod)

| Thing | Name |
|---|---|
| Resource group | `wealth-azure-prod-rg` |
| Platform_Workspace | `wealth-prod-la` |
| Telemetry_Workspace | `wealth-prod-telemetry-la` |
| App Insights | `wealth-prod-ai` |
| Producer_Job (Azure resource name) | `market-data-refresh-job` |
| Apps | `api-gateway`, `portfolio-service`, `market-data-service`, `insight-service` |

Caps (both workspaces): **0.023 GB/day**. Budget: **₹1100/month** on
`wealth-azure-prod-rg`. Sampling_Ratio starts at **1.0** via
`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`.

The Producer_Job's Terraform label is `azurerm_container_app_job.market_data_refresh`.
Use the Azure name `market-data-refresh-job` in every `az` command — the Terraform
label 404s.

---

## Standing constraints

Read these once; they apply to every section below.

- **No OTLP log export.** Console logs are the sole log source. They land in
  Platform_Workspace as `ContainerAppConsoleLogs_CL`. Traces land in
  Telemetry_Workspace / App Insights. Correlation is a cross-workspace join
  (section 13.3), not dual shipping.
- **No custom or JVM metrics** in this increment. Do not look for a meter
  pipeline in production; drop counts come from console-log warnings (section 13.4).
- **No always-on health probe, sidecar, or OpenTelemetry Collector.** The
  Sink_Smoke_Check wake step is a one-shot HTTP request, not a standing probe.
- **Absence of telemetry is never an alerting signal.** These workloads
  legitimately have no traffic for long periods. A zero is a recorded
  measurement only after a bounded drain/flush; it is not an alert.
- **Head sampling does not retain errors by type.** A sampled-out failed
  request leaves no trace. Do not treat a missing error span as proof that
  the error did not happen.
- **Do not add a KEDA or Kafka scale rule** to make the smoke check
  deterministic. The wake step is the sanctioned workaround.
- **Allowance_Audit is manual only.** Never implement it as a polling
  service, scheduled job, or runtime input.
- **Never raise the Telemetry_Cap.** It is not a response option.

---

## How to run KQL

**Portal (preferred for copy-paste):** Azure Portal → the workspace named in
the procedure → **Logs** → paste the query → Run.

**CLI:** resolve the workspace customer ID, then query.

```bash
# Platform_Workspace
az monitor log-analytics workspace show \
  --resource-group wealth-azure-prod-rg \
  --workspace-name wealth-prod-la \
  --query customerId -o tsv

# Telemetry_Workspace
az monitor log-analytics workspace show \
  --resource-group wealth-azure-prod-rg \
  --workspace-name wealth-prod-telemetry-la \
  --query customerId -o tsv

az monitor log-analytics query \
  --workspace <customer-id> \
  --analytics-query "<kql>"
```

`Usage` is **workspace-scoped**. A query against one workspace does not include
the other. Never add the two GB totals together and treat that as a single
figure.

---

<a id="13.1"></a>

## 13.1 Daily volume by table, both workspaces

`Usage` covers only the workspace you run it against. Run this query **twice**,
with the two result sets **explicitly labeled**. Do not present them as one
combined total.

### Run 1 — Platform_Workspace (`wealth-prod-la`)

Open Logs on **`wealth-prod-la`**. Label the output
`Platform_Workspace daily volume`.

```kql
Usage
| where TimeGenerated > ago(31d)
| where IsBillable == true
| summarize GB = round(sum(Quantity) / 1024.0, 4) by DataType, bin(TimeGenerated, 1d)
| order by TimeGenerated desc, GB desc
```

### Run 2 — Telemetry_Workspace (`wealth-prod-telemetry-la`)

Open Logs on **`wealth-prod-telemetry-la`**. Label the output
`Telemetry_Workspace daily volume`.

```kql
Usage
| where TimeGenerated > ago(31d)
| where IsBillable == true
| summarize GB = round(sum(Quantity) / 1024.0, 4) by DataType, bin(TimeGenerated, 1d)
| order by TimeGenerated desc, GB desc
```

Keep the two tables side by side. A telemetry storm on `wealth-prod-telemetry-la`
must not be mistaken for platform-log volume on `wealth-prod-la`, and the reverse.

---

<a id="13.2"></a>

## 13.2 Cap-proximity and overshoot

Run this query **twice**, labeled, for the same reason as 13.1: each workspace
has its own 0.023 GB/day cap.

### Framing (read before interpreting results)

1. The caps are a **Cost_Circuit_Breaker**, **not** a guaranteed monetary
   ceiling. Azure documents that ingestion can **overshoot** a daily cap; the
   excess is billed.
2. A cap **stops ingestion only until the daily reset**, not permanently. A
   persistent storm resumes the next day, and every day after.
3. This query's detection latency is bounded by the **Audit_Cadence** (at most
   31 days between completed Allowance_Audits). That latency is an **accepted
   trade**, not an oversight — it is the same assumption as the 31-day
   worst-case cost bound. There is no charged scheduled-query alert.

`PctOfCap > 100` is overshoot (billed). `PctOfCap` approaching 100 is proximity.
A value of 100 on more than one day since the previous audit is the
cap-cycling escalation in section 13.5.

### Run 1 — Platform_Workspace (`wealth-prod-la`)

Label: `Platform_Cap proximity`.

```kql
Usage
| where TimeGenerated > ago(2d)
| where IsBillable == true
| summarize DailyGB = round(sum(Quantity) / 1024.0, 4) by bin(TimeGenerated, 1d)
| extend CapGB = 0.023, PctOfCap = round(DailyGB / 0.023 * 100, 1)
| order by TimeGenerated desc
```

### Run 2 — Telemetry_Workspace (`wealth-prod-telemetry-la`)

Label: `Telemetry_Cap proximity`.

```kql
Usage
| where TimeGenerated > ago(2d)
| where IsBillable == true
| summarize DailyGB = round(sum(Quantity) / 1024.0, 4) by bin(TimeGenerated, 1d)
| extend CapGB = 0.023, PctOfCap = round(DailyGB / 0.023 * 100, 1)
| order by TimeGenerated desc
```

For the Allowance_Audit (13.5), extend the `TimeGenerated` window to cover
every day since the previous completed audit (up to 31d) so cap-reached days
are not missed. The query body above is the detection query; only the lookback
changes for that audit.

---

<a id="13.3"></a>

## 13.3 Cross-workspace trace↔log correlation

Logs live in Platform_Workspace. Traces live in Telemetry_Workspace. Join them
with `workspace()` on trace ID → `OperationId`.

**Union `AppRequests` AND `AppDependencies` before joining.** The Producer_Job
never serves an HTTP request, so its spans surface only in `AppDependencies`.
An `AppRequests`-only query silently misses every Job log line — that is
Property 8, not an edge case.

Run this query from **Platform_Workspace** (`wealth-prod-la`), where
`ContainerAppConsoleLogs_CL` exists.

Resolve the Telemetry_Workspace resource ID (not the customer ID):

```bash
az monitor log-analytics workspace show \
  --resource-group wealth-azure-prod-rg \
  --workspace-name wealth-prod-telemetry-la \
  --query id -o tsv
```

Substitute that value for `<telemetry-workspace-resource-id>`.

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

The extraction regex assumes the task-4.5 console prefix
`[service,traceId,spanId]`. Refine it against real log lines once traces are
flowing (task 15.7); exact whitespace/format cannot be fully verified before
then. Name filters are likewise refined in 15.7.

**Verify against at least one Producer_Job (dependency-only) trace**, not only
a request-bearing HTTP trace. If the Job's log lines do not join, the union is
wrong or missing.

---

<a id="13.4"></a>

## 13.4 Production drop-count extraction

Production has no metrics export. Queue-full drops never reach the span
exporter, so the only production signal is the SDK warning in container
console logs on Platform_Workspace:

```
BatchSpanProcessor dropped N span(s) … because the queue is full
```

Run against **Platform_Workspace** (`wealth-prod-la`). Replace `<run_start>`
and `<drain_end>` with KQL datetime literals for the bounded run-and-drain
interval (the run itself plus the wait for the final scheduled flush).

```kql
ContainerAppConsoleLogs_CL
| where TimeGenerated between (<run_start> .. <drain_end>)
| where Log_s contains_cs "BatchSpanProcessor dropped"
| extend DroppedCount = toint(extract(@"dropped (\d+) span", 1, Log_s))
| summarize TotalDropped = sum(DroppedCount)
```

### Why `contains_cs`, not `has`

`has` is a **term-boundary** operator. It matches whole tokens, not a
substring, and would not reliably match the multi-word phrase
`"BatchSpanProcessor dropped"`. A silent non-match returns zero rows;
`summarize` over zero rows still produces `TotalDropped = 0`, which reads
identically to "no drops occurred." `contains_cs` is a case-sensitive
substring match on a fixed, code-authored warning string.

### Bidirectional verify (required before relying on the count)

1. **Known warning interval** — run against an interval known to contain at
   least one `BatchSpanProcessor dropped` warning (saturation-test log output,
   or a deliberately induced one). Confirm `TotalDropped` matches the known
   count.
2. **Known clean interval** — run against an interval known to contain none.
   Confirm `TotalDropped` is `0` because the phrase is absent, not because the
   match expression failed to fire.

### Zero is not an alert

Absence of telemetry is **never** an alerting signal. Record `TotalDropped = 0`
only **after** the drain/flush window (`<drain_end>`) has elapsed. A query
issued before the final flush completes can read as zero because drops have
not been logged yet, not because there were none.

Do not treat a zero (or a missing row) as a production alert.

---

<a id="13.5"></a>

## 13.5 Allowance_Audit

A **manual documented reconciliation**. Cadence: **at most 31 days** between
completed runs (`Audit_Cadence`). Also run before any cost-bearing
infrastructure change, whenever a recurring charge is added, and whenever the
operator obtains updated INR meter or forecast data.

**Never** implement this as a polling service, scheduled job, or runtime
input. Do not add a timer, Logic App, Function, KEDA cron, or CI schedule
that fetches meters and flips the toggle.

### Step 1 — Refetch live inputs (never reuse recorded figures)

Do **not** paste last audit's numbers. Do **not** use the unittest fixture
`551.78`. Fetch both values **now**.

**Meter (₹/GB, INR, Central India Analytics ingestion):** Azure Retail Prices
API, `currencyCode=INR`, region `centralindia`, Log Analytics Analytics
ingestion. Use the unit price at the **5 GB** tier (`tierMinimumUnits` 5), not
the ₹0.00 0-tier (that 0-tier *is* the Ingestion_Allowance, which this check
assumes is already gone).

```bash
# Example fetch — confirm meter name / sku in the JSON; take INR unitPrice at tier 5
curl -sS "https://prices.azure.com/api/retail/prices?currencyCode=INR&\$filter=serviceName eq 'Log Analytics' and armRegionName eq 'centralindia'"
```

**Forecast (₹, resource-group):** Azure Portal → Cost Management → Cost
analysis → scope **`wealth-azure-prod-rg`** → current-month **forecast**.
Do not use subscription-wide forecast.

Historical snapshot **as of 2026-08-14** (illustration only — refetch):
meter ₹303.9479/GB, forecast ₹549.42. The unittest fixture 551.78 is **not**
the operational number.

### Step 2 — Invoke the 12.1 script with those live values

From the repo:

```bash
python infrastructure/terraform/azure/scripts/allowance_independence_check.py \
  --meter-rate <live-inr-per-gb> \
  --forecast <live-inr>
```

Optional: `--recurring-charges <inr>` (default `0`), `--budget <inr>`
(default `1100`).

The script computes `31 × (0.023 + 0.023) × meter_rate + forecast + recurring_charges`
with Ingestion_Allowance assumed **zero**, prints `PASS`/`FAIL` and `margin=…`,
and exits `0`/`1`. Meter rate and forecast have no defaults; omitting them
cannot silently pass.

### Step 3 — Cap_Observation

Run the 13.2 query against **both** workspaces with lookback covering every
day since the previous completed audit. Record proximity and any
`PctOfCap >= 100` days.

### Step 4 — Ingestion_Allowance state

In Cost Analysis for the billing account / this resource group, check whether
Analytics ingestion is still absorbed at ₹0.00. If Log Analytics / Azure
Monitor ingestion shows a **non-zero** charge, treat the Ingestion_Allowance
as exhausted for this audit.

### Decision table

| Condition | Action |
|---|---|
| Script **FAIL** | Set/leave `Trace_Export_Toggle` (`MANAGEMENT_TRACING_EXPORT_ENABLED`) **`false`** until the script **PASS**es again. See Export_Kill_Switch below. This is an ordinary failed check. |
| Script **PASS**, and no extra escalation | Record the live meter, forecast, margin, cap-proximity, allowance state, and the UTC timestamp of this completed run. Next run due within 31 days. |
| Ingestion_Allowance **exhausted**, **or** a cap was reached on **more than one day** since the previous audit | Evaluate **Export_Kill_Switch as the documented first response**. This is the escalation repeated cap-cycling requires, **distinct** from an ordinary failed check. |

The Budget_Alert (email at 70% actual / 100% forecast) is **not** a substitute
for this audit: Cost Management data lags 8–24 hours and the alert is
non-enforcing. The caps are **not** a substitute either: they reset daily.

---

<a id="export-kill-switch"></a>

## Export_Kill_Switch

Stops export at source: set `MANAGEMENT_TRACING_EXPORT_ENABLED=false` on **all
five** traced workloads and **redeploy**. No code change. No residual export
path once the new revisions/Job execution pick up the env var.

Workloads: `api-gateway`, `portfolio-service`, `market-data-service`,
`insight-service`, `market-data-refresh-job`.

**Source of truth** is Terraform (`infrastructure/terraform/azure/main.tf`):
set `MANAGEMENT_TRACING_EXPORT_ENABLED = "false"` (and the Job's matching
`env { value = "false" }`). Then apply — a merged change is **not live** until
manual apply:

```bash
gh workflow run terraform-azure.yml \
  --ref <branch-with-the-toggle-change> \
  --field action=apply
```

Leave the toggle `false` until Allowance_Independence **PASS**es again (or
until the cap-cycling review decides export may resume).

Confirm:

```bash
az containerapp show --name api-gateway --resource-group wealth-azure-prod-rg \
  --query "properties.template.containers[0].env[?name=='MANAGEMENT_TRACING_EXPORT_ENABLED'].value" -o tsv
az containerapp job show --name market-data-refresh-job --resource-group wealth-azure-prod-rg \
  --query "properties.template.containers[0].env[?name=='MANAGEMENT_TRACING_EXPORT_ENABLED'].value" -o tsv
```

Repeat `az containerapp show` for `portfolio-service`, `market-data-service`,
and `insight-service`.

---

<a id="13.6"></a>

## 13.6 Sink_Smoke_Check

Proves a Kafka producer dependency and its consumer requests share one
`OperationId` in App Insights after a real Producer_Job run.

**When:** after observability deployment, and after any managed-agent or
API-version change. **Never scheduled or continuous.**

**Do not** add a KEDA or Kafka scale rule to make this deterministic
(`portfolio-service` and `insight-service` have `min_replicas = 0` and no
Kafka scale rule — D7). The wake step is the workaround.

### Bound

**15 minutes** from Job start. If the KQL join is empty at timeout, the check
**FAIL**s. Never pass silently.

### Step 1 — Trigger Producer_Job

```bash
az containerapp job start \
  --name market-data-refresh-job \
  --resource-group wealth-azure-prod-rg
```

Record UTC `T0`. Optionally watch execution:

```bash
az containerapp job execution list \
  --name market-data-refresh-job \
  --resource-group wealth-azure-prod-rg \
  -o table
```

### Step 2 — Wake step (D7)

Issue an HTTP request to **`portfolio-service` and `insight-service`**. Without
this, both consumers stay at zero replicas and the check is non-deterministic.
Do **not** add a KEDA or Kafka scale rule.

Both apps are internal-ingress. Wake them through **`api-gateway`** (external),
which forwards to each service:

```bash
GATEWAY=$(az containerapp show \
  --name api-gateway \
  --resource-group wealth-azure-prod-rg \
  --query properties.configuration.ingress.fqdn -o tsv)

# Wakes portfolio-service (and the gateway).
curl -sS -o /dev/null -w "portfolio %{http_code}\n" --max-time 60 \
  "https://${GATEWAY}/api/portfolio/health"

# Wakes insight-service (and the gateway).
curl -sS -o /dev/null -w "insight %{http_code}\n" --max-time 60 \
  "https://${GATEWAY}/api/insights/health"
```

A 5xx/timeout on first hit is a cold start, not a smoke-check pass. Retry the
same two URLs until each returns a non-timeout response or `T0+15m` is reached.
The wake is successful when the request **reached** the service (it scaled from
zero). Do not treat HTTP 401/403 on a health path as a smoke-check failure if
the replica is up.

### Step 3 — Query (Telemetry_Workspace / App Insights)

Run on **`wealth-prod-telemetry-la`** (or App Insights `wealth-prod-ai`, same
`App*` tables). Name filters are refined against real span names in task 15.7.

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

**PASS:** the join returns at least one row (shared `OperationId` between a
producer dependency and a consumer request) inside the 15-minute window.
When `AppRoleName` is identifiable, confirm both `portfolio-service` and
`insight-service` appear. Name filters are refined in task 15.7 — do not
invent extra `Name` predicates until then.

**FAIL:** no joined row by `T0+15m`. Record FAIL and stop. Do not wait
indefinitely. Do not treat an empty result as success.

Absence of rows **outside** this bounded check is not an alert (constraint 10.10).

---

<a id="13.7"></a>

## 13.7 Sampling_Review_Trigger and response menu

### Trigger (either is enough)

On **Telemetry_Workspace** (`wealth-prod-telemetry-la` / Telemetry_Cap):

- rolling **seven-day mean** ingestion **above 50%** of the Telemetry_Cap
  (0.023 GB/day), **or**
- any single **non-deliberate** day **above 80%** of it

"Non-deliberate" means not an intentional representative run or audit
exercise that was expected to be high.

Helper (Telemetry_Workspace only; interpret, do not alert):

```kql
Usage
| where TimeGenerated > ago(7d)
| where IsBillable == true
| summarize DailyGB = round(sum(Quantity) / 1024.0, 4) by bin(TimeGenerated, 1d)
| extend CapGB = 0.023, PctOfCap = round(DailyGB / 0.023 * 100, 1)
| summarize RollingSevenDayMeanPct = round(avg(PctOfCap), 1), MaxDayPct = max(PctOfCap)
| extend Trigger = RollingSevenDayMeanPct > 50 or MaxDayPct > 80
```

A `Trigger` of `true` requires a **documented review**. It is not an automated
remediation.

### Response menu

Select **one or more**. Lowering the Sampling_Ratio is **not** mandatory.

1. **Root-cause remediation** of whatever is producing the volume.
2. **Tighten the Cardinality_Bound** or span/attribute filtering
   (`management.opentelemetry.tracing.limits` in application config:
   `max-attributes` 64, `max-attribute-value-length` 512; redaction /
   attribute filter in `common-observability`). This is an image/config change,
   not an env-only flip.
3. **Lower the Sampling_Ratio** — set `MANAGEMENT_TRACING_SAMPLING_PROBABILITY`
   below `1.0` on all five workloads and apply/redeploy. Optional, not required.
4. **Export_Kill_Switch** (section above).

The sampler is parent-based: downstream workloads honour the root decision, so
a sampled trace is complete end-to-end or absent entirely, never partial.
**Errors are not guaranteed to be retained** under head sampling.

Raising the Telemetry_Cap is **not on this menu**. It is never an option.

---

## Quick reference — env vars

| Variable | Role | Initial prod value |
|---|---|---|
| `MANAGEMENT_TRACING_EXPORT_ENABLED` | Trace_Export_Toggle / Export_Kill_Switch | `true` (set `false` to stop export) |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | Sampling_Ratio | `1.0` |
| `OTEL_SERVICE_NAME` | Job-only identity | `market-data-refresh-job` on the Job only |

Do not add OTLP log export, custom/JVM metrics, an always-on Collector, a
scheduled audit, or a Kafka/KEDA scale rule from this runbook.
