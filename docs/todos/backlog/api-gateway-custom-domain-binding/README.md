# Backlog: API Gateway custom-domain binding recovery

**Status:** Open — hostname restored 2026-08-31; live-read-back evidence independently reviewed and merged (PR #194 at `main@98371587`); authorized three-caller synthetic executed (run [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)) with evidence pending independent review
**Owner:** unassigned
**Blocks:** B1 Task 5.7 / G5 (Task 5.7 remains unchecked)
**Tracked in:** Surfaced by the Spec A 9.14 live read-back
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../../../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md), apply run
[33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)).
Pre-existing; not caused by 9.14.

---

## What is wrong

> **Historical incident; resolved in live state.** This section records the condition found before
> the guarded restore. The backlog remains open because neither the restore, its reviewed evidence,
> nor the subsequent three-caller synthetic documentation alone closes G5 or B1 Task 5.7 without
> independent review acceptance.

At discovery, checkpoint 9.14 had reopened external ingress on `api-gateway`, and the **default
ACA endpoint** `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` served healthy
traffic (`200`, `status: UP`).

The **custom domain did not serve**. `api.vibhanshu-ai-portfolio.dev` resolved correctly — it was a
CNAME alias onto the gateway's ACA hostname — but the Container App reported:

```
properties.configuration.ingress.customDomains = null
```

so no hostname — and therefore no certificate — is bound to the app. TLS therefore fails at
handshake:

```
schannel: failed to receive handshake, SSL/TLS connection failed
```

DNS is fine; the binding is missing.

**Verified root cause (2026-08-31).** Azure Resource Graph change history shows Terraform
correlation `cf0fc22a-595b-9bba-143a-6749888b1998` at checkpoint 9.5 cleared the hostname binding,
binding type, and certificate ID while the managed certificate
`mc-wealth-prod-ac-api-vibhanshu-ai-5159` survived.

**Scope of that evidence.** `customDomains: null` proves nothing is *bound* to the app. It does
**not** by itself prove that no managed-certificate resource exists in the Container Apps
environment — inventory may still show an unbound certificate. Here the managed certificate above
survived the binding loss.

## Source recovery and guarded execution

The source-only recovery PR and subsequent narrow guard repairs added:

- Terraform ownership of hostname presence via `azurerm_container_app_custom_domain.api_gateway`
- workflow profiles `api-gateway-custom-domain-restore` and `api-gateway-custom-domain-remove`
- read-only preflight, universal plan guards, post-apply bind, and read-back wiring
- runbook [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../../../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)
  with source-only, failed-safe, and executed-run history

The separately authorized remote plan, production-gated apply, and explicit existing-certificate
bind have now run. The apply workflow's immediate default-host health observation failed after
the bind, but an independent read-back immediately afterward confirmed both default and custom
health endpoints at HTTP `200`, unchanged gateway revision/ingress, the exact `SniEnabled` hostname,
and the expected managed certificate. The execution record and scope are retained in the runbook.

**This backlog item stays open** because Task 5.7 remains unchecked pending independent review of
the three-caller evidence (and backlog closure is not automatic from a synthetic success alone).

## Authorized three-caller synthetic (executed live; docs source-only)

Owner-authorized B1 Task 5.7 / G5 evidence dispatch (exactly one; no retry):

| Field | Value |
|---|---|
| Workflow | `Synthetic Monitoring` |
| SHA | `main@f66d7ab6a4db1a327fd030ba9897bfc431104945` |
| Run | [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) |
| Outcome | Azure suite **success** (AWS skipped); overall **success** |
| Re-seed | succeeded (holdings only; no market-data write path) |
| Markers | `[b1-g5][synthetic-shell] expectedVersion=0`; `[b1-g5][global-setup] expectedVersion=0`; `[b1-g5][azure-api-smoke] expectedVersion=0` |

Cold-start note (separate from failure): pre-warm recorded HTTP `000` / `000000` on all four health
paths; subsequent seed and Playwright suite still succeeded. Full sanitized record:
[`B1_G5_INGRESS_BLOCKER.md`](../../../runbooks/B1_G5_INGRESS_BLOCKER.md).

**Executed live evidence** is the workflow run above. Documentation PRs that record it are
**source-only** and do not check Task 5.7, close this backlog, or unblock Wave 6 / R-B3 / public
`PUT`.

## Why it matters

`api.vibhanshu-ai-portfolio.dev` is the configured endpoint for the frontend and the synthetic
workflows (`NEXT_PUBLIC_API_BASE_URL` in `.github/workflows/synthetic-monitoring.yml`). The restored
endpoint serves traffic, and an authorized three-caller synthetic has now been executed. Task 5.7
and this backlog remain open until independent review accepts that evidence.

Unattended synthetics remain suspended in `synthetic-monitoring.yml`.

## What remains

1. Independent review of the executed three-caller evidence in
   [`B1_G5_INGRESS_BLOCKER.md`](../../../runbooks/B1_G5_INGRESS_BLOCKER.md) / run
   [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271).
   Leave `- [ ] **5.7 G5 evidence.**` unchecked until that review accepts the evidence.
2. Do not infer Task 5.7 completion, backlog closure, Wave 6 / R-B3, or public `PUT` from a source
   merge, remote plan, apply, certificate bind, HTTP health result, or documentation PR alone.

Before any future ingress close (`spec-a-9.14-close-ingress`), run
`api-gateway-custom-domain-remove` first so ingress close remains a one-resource operation.

## Non-claims

- Does **not** authorize backlog closure, B1 Task 5.7 completion, Wave 6 / R-B3, public `PUT`, an
  ingress close, a hostname remove, or another Terraform operation.
- Does **not** claim Task 5.7 is checked or Writer_Convergence is achieved.
- Does **not** assert the binding was ever present before checkpoint 9.5; the Resource Graph record
  documents the binding loss at that checkpoint.
- Does **not** enable or claim any market-data writer path.
