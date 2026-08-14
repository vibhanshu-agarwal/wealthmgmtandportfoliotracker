# Backlog: Kafka Consumers Scale to Zero With No Kafka Scale Rule

**Status:** Open — 2026-08-14
**Owner:** unassigned
**Tracked in:** [Observability spec, Recorded Decision D7](../../../../.kiro/specs/observability-app-insights/requirements.md)

---

## Status & Decision

**Open, deliberately not fixed by the observability spec.** Found while verifying whether that
spec's deployed sink smoke check could run deterministically. It cannot, for a reason that turns
out to be a pre-existing production characteristic rather than a test problem.

Left open because fixing it means adding KEDA scale rules, which change production scaling
behaviour and cost. That is a separate decision from turning on tracing, and folding it into the
observability work would have made an unrelated architectural change ride on a telemetry feature.

---

## What's Missing (evidence, not speculation)

`infrastructure/terraform/azure/modules/container-app/main.tf` declares only:

```hcl
min_replicas = var.min_replicas
max_replicas = var.max_replicas
```

There is **no** `custom_scale_rule`, `http_scale_rule`, `azure_queue_scale_rule`, or any KEDA
scaler of any kind in the module.

`min_replicas` defaults to `0`
(`modules/container-app/variables.tf:53`, described there as "0 = scale-to-zero (cost-optimal)"),
and neither the `portfolio_service` nor the `insight_service` module invocation in `main.tf`
overrides it.

---

## Consequence

`PriceUpdatedEvent` messages published by the `market_data_refresh` Container App Job accumulate in
Kafka. **Nothing wakes a consumer.** `portfolio-service` and `insight-service` consume only while a
replica happens to be running for unrelated HTTP reasons — i.e. when a user hits the application.

Practical effects:

1. **Price projection latency is a function of user traffic, not of publication.** After an idle
   period, freshly published prices are not projected into the read model until someone loads the
   dashboard, at which point the backlog drains.
2. This plausibly accounts for some data-freshness behaviour previously attributed to cold starts.
   The observed ~35s `portfolio-service` cold start is real and separate; this is an additional,
   unbounded delay *before* the cold start is even triggered.
3. Any verification observing a producer and consumer span in one trace is non-deterministic
   without explicitly waking the consumers first — which is why the observability spec's
   `Sink_Smoke_Check` requires a `Consumer_Wake_Step` (Requirement 10.3/10.4).

**Classification: a freshness and liveness defect with a potential eventual-correctness risk.**

An earlier draft of this entry claimed "no data is lost." That was overstated and is withdrawn.
The projection *is* idempotent (`ON CONFLICT … IS DISTINCT FROM`), so redelivery is safe — but
safety under redelivery is not the same as delivery being guaranteed. Kafka retention is finite,
and the retention configured on the Aiven cluster for `market-prices` has **not been verified**. If
a period of inactivity exceeds the broker's retention, events can expire before either consumer
wakes, and those price updates would never be projected.

That failure is silent: nothing detects it, because a consumer that never woke logs nothing and the
projection simply reflects older prices. Verifying the topic's actual retention against realistic
idle periods is the first step in evaluating any of the options below, and may itself change which
option is correct.

---

## Options (undecided)

1. **Accept as-is.** Cheapest, and arguably defensible for a demo whose data is only observed when
   someone is looking at it. Costs nothing. Note the delay is **not** reliably invisible: loading
   the dashboard is the wake trigger, but the dashboard's own query can complete before the
   consumer has drained its Kafka backlog, so the first view after an idle period may show stale
   prices and only a refresh shows current ones.
2. **Add a KEDA Kafka scaler** to `portfolio-service` and `insight-service`. Correct behaviour —
   consumers wake on lag. Costs ACA compute whenever messages arrive, so the refresh Job's schedule
   becomes a compute driver. Needs assessment against the ₹1100/month ceiling; note that ACA
   consumption currently bills ₹0.00 because it sits inside the monthly free grant, so the marginal
   cost may still be zero at this volume.
3. **Set `min_replicas = 1`** on the consumers. Simplest, but makes them the first always-on
   compute in the system and forfeits scale-to-zero. Almost certainly the wrong trade here.
4. **Trigger consumers from the refresh Job.** Have the Job issue a warm-up request after
   publishing. Crude, but adds no always-on cost and no new scaling machinery.

Option 2 is the conventional answer; option 1 may be the right one for this project's actual
usage pattern. Needs a decision, not just implementation.

---

## Note

Whichever option is chosen, remember a merged Terraform change is **not live** until someone runs
`workflow_dispatch` with `action=apply` — see
[`terraform-apply-not-automatic-on-merge`](../terraform-apply-not-automatic-on-merge/README.md).
