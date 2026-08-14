# Kickoff Note: Observability & App Insights Implementation

**For whoever picks this up.** Read this first — it's a 5-minute orientation, not a
substitute for the spec. Nothing in this feature has been implemented yet; this note
exists because the spec took an unusually long review cycle to land, and most of the
*why* behind its decisions lives only in `design.md`'s prose and its 33 cited sources —
the checkpoint log the review happened in is gitignored and does not exist in this repo.
If a decision here looks arbitrary, check `design.md` before revisiting it; it probably
isn't.

## Start here, in order

1. [`requirements.md`](requirements.md) — what must be true when this is done, plus a
   **Global Constraints** section for the negative/prohibition requirements.
2. [`design.md`](design.md) — how, and why. 33 numbered sources at the bottom. If you're
   about to second-guess the AzAPI provider, the redaction mechanism, or the auto-config
   ordering, the rationale is already there.
3. [`tasks.md`](tasks.md) — the executable plan. 16 top-level tasks, 68 sub-tasks, a
   15-wave dependency graph. **No task is marked optional.** Every test task verifies a
   named Correctness Property or is the sole proof of a mandatory requirement.

Start at wave 0. Don't skip the dependency graph — it encodes real constraints found the
hard way during review (see "Where the risk actually is," below).

## Settled — don't relitigate these

| Decision | One-line why | Depth |
|---|---|---|
| ACA **managed OpenTelemetry agent**, not the GA App Insights Java agent | The Java agent doesn't support Micrometer Tracing as custom telemetry — choosing it would orphan the Kafka continuity work and make the existing Spring instrumentation decorative | design.md D1, [S1] |
| **AzAPI** provider added alongside AzureRM | AzureRM doesn't model the managed-agent config block (`openTelemetryConfiguration`) as of this writing — verified against the pinned `2025-10-02-preview` schema | design.md Component 3, [S13] |
| `₹1100/month` total ceiling for `wealth-azure-prod-rg` | Owner-set hard limit, raised once from ₹1000 specifically to give the cost bound real overshoot margin (₹114.79 vs ₹14.79) | requirements.md Introduction |
| Both workspace daily caps at `0.023 GB/day` (Azure's floor) | Sized so the ceiling holds even if the shared 5 GB/month allowance is exhausted elsewhere (`Allowance_Independence`) — this is *not* a tunable value, it's derived | requirements.md §4 |
| Sampling starts at `1.0` | Reversed an earlier "aggressive sampling" instruction once the allowance math showed ingestion volume was never the real constraint | requirements.md §3 |
| Redaction is a `SpanExporter` wrapper, **not** just an `ObservationFilter` | The filter runs before a span's exception event is recorded — by the time it runs, `recordException()` has already fired. Only the exporter layer can actually redact exception content | design.md Component 9(c) |

## Where the risk actually is

Three things in this spec are harder than they look. Don't budget them like the rest:

1. **`common-observability`'s auto-configuration (tasks 6.2, 6.5).** Getting the custom
   `BatchSpanProcessor` bean to reliably preempt Boot's own default — without silently
   double-exporting a raw *and* a sanitized copy of every span — took six rounds of
   design review to get right, including a correction on Spring Boot's own
   `@ConditionalOnEnabledTracingExport` default-enabled behavior. **Task 6.5's five-case
   activation matrix is the actual specification for this mechanism**, not a
   nice-to-have regression test. If you're tempted to simplify it, don't — each case
   exists because an earlier draft got it wrong in exactly that spot.
2. **The exception-redaction lifecycle (task 6.1).** `ExceptionEventData` exposes the
   original `Throwable` via `getException()` even after its attributes are rewritten —
   sanitizing the attributes without replacing the event *type* leaves the raw exception
   fully reachable. The design requires replacing every `ExceptionEventData` with a plain
   `EventData`, and the sentinel tests (task 10.2) specifically check for this, not just
   for redacted content.
3. **Deployment sequencing (tasks 15.1–15.10).** This is a strictly sequential chain, not
   a set of parallel steps: path filters → image rebuild → per-workload SHA verification
   → *then* Terraform apply. Getting this order wrong means the export toggle goes live
   against images that predate the sanitizer, i.e., raw unsanitized telemetry leaving the
   system. The dependency graph in `tasks.md` already encodes this as 8 separate waves
   (7–14) for exactly this reason — don't collapse them back together for convenience.

## Before you trust any number in these docs

The cost arithmetic depends on two inputs that were current *when the spec was written*,
not invariants:

- **The Central India Analytics ingestion meter rate**, cited throughout as
  `₹303.9479/GB`.
- **The `wealth-azure-prod-rg` cost forecast**, cited as `₹551.78`.

Requirement 4.7 exists specifically because these drift. **Re-fetch both before trusting
the Allowance_Independence bound** — the check itself is task 12.1
(`infrastructure/terraform/azure/scripts/allowance_independence_check.py`), which takes
both as CLI arguments precisely so a stale hardcoded figure can't silently pass. If it's
been more than a few weeks since this was written, assume the cached figures in the docs
are stale and pull current ones before starting task 1.

## Operational gotchas specific to this repo

- **Terraform apply is manual.** `terraform-azure.yml` only runs `plan` on a PR;
  `apply` requires a `workflow_dispatch` with `action=apply`. A merged Terraform change
  is **not live** until someone runs that — this exact gap caused a production incident
  in this repo before (see
  [`docs/todos/backlog/terraform-apply-not-automatic-on-merge/`](../../../docs/todos/backlog/terraform-apply-not-automatic-on-merge/README.md)).
  Task 15.4 depends on this being done deliberately, not assumed.
- **The Producer_Job's Azure name differs from its Terraform label.** The Terraform
  resource is `azurerm_container_app_job.market_data_refresh`; the actual Azure resource
  `name` is `market-data-refresh-job`. Use the latter for any `az containerapp job show`
  command (task 15.3) — the former will 404.
- **Branch off updated `main`, not off this spec's branch.** This repo's convention:
  follow-up work gets a fresh branch off current `main`, not more commits on an
  already-merged feature branch. If `codex/observability-app-insights-spec` has already
  merged by the time you start, don't reopen it.

## Related, but explicitly not in scope here

[`docs/todos/backlog/kafka-consumers-have-no-scale-rule/`](../../../docs/todos/backlog/kafka-consumers-have-no-scale-rule/README.md) —
`portfolio-service` and `insight-service` both default to `min_replicas = 0` with no
Kafka scale rule, so nothing wakes them when a message arrives. This spec **works around
it** (task 13.6's `Consumer_Wake_Step`, an explicit HTTP request before checking for
consumer spans) rather than fixing it — fixing it means adding KEDA scale rules with
their own cost and behavioral consequences, which is a separate decision. Don't try to
fold that fix in here.

## How to execute

`tasks.md`'s structure is written for either `superpowers:subagent-driven-development`
(if working task-by-task in this session) or `superpowers:executing-plans` (if handing
it off with review checkpoints). Either way:

- Tasks `9`, `14`, and `16` are deliberate pause points — application-side wiring
  complete, pre-deployment verification green, and final full-suite check. Don't push
  past a checkpoint with failing tests to "come back to it later."
- No task is optional. If time pressure forces a cut, that's a scope conversation with
  the person who owns this, not a quiet skip — the Notes section in `tasks.md` explains
  why each verification task is load-bearing.
