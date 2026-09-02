# Backlog: API Gateway custom-domain binding recovery

**Status:** Open for separate backlog disposition — hostname restored 2026-08-31; evidence reviewed/merged via PR #194 (`main@98371587`); three-caller run [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) succeeded and its evidence merged via PR #197; B1 Task 5.7 / G5 closed by owner decision on 2026-09-02. No unresolved G5 blocker is claimed here.
**Owner:** unassigned
**Blocks:** None for B1 Task 5.7 / G5; that gate is closed
**Tracked in:** Surfaced by the Spec A 9.14 live read-back
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../../../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md), apply run
[33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)).
Pre-existing; not caused by 9.14.

---

## What is wrong

> **Historical incident; resolved in live state.** This section records the condition found before
> the guarded restore. The owner closed B1 Task 5.7 / G5 on 2026-09-02 using the reviewed
> three-caller evidence. This backlog's separately reserved closure has not been recorded;
> its open status no longer represents a G5 blocker.

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

**G5 is now closed** by the owner's 2026-09-02 decision. This item retains its existing separate
backlog-closure decision; its open status does not invalidate G5 or claim a current binding fault.

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

Cold-start note (separate from failure): independent verification showed all four pre-warm probes
printed `HTTP 000000` (`000000` is the workflow’s timeout/transport output form). That remains
distinct from the later successful holdings-only seed and Playwright suite. Full sanitized record:
[`B1_G5_INGRESS_BLOCKER.md`](../../../runbooks/B1_G5_INGRESS_BLOCKER.md).

**Executed live evidence** is the workflow run above. Documentation PRs that record it are
**source-only**. PR #197 did not itself check Task 5.7; the owner's later G5 close-out did.
That decision satisfies Wave 6's G5 prerequisite and does not close this backlog, authorize an
R-B3 deployment, or activate public `PUT`.

## Why it matters

`api.vibhanshu-ai-portfolio.dev` is the configured endpoint for the frontend and the synthetic
workflows (`NEXT_PUBLIC_API_BASE_URL` in `.github/workflows/synthetic-monitoring.yml`). The restored
endpoint served the authorized three-caller synthetic. Its evidence was reviewed and merged,
and Task 5.7 / G5 closed under the owner's 2026-09-02 decision. This backlog remains separately
open for disposition; no new live reachability observation is made here.

Unattended synthetics remain suspended in `synthetic-monitoring.yml`.

## What remains

1. Record the separately reserved disposition of this backlog using the recovery and G5 evidence.
   The G5 prerequisite itself is complete; no additional synthetic is required by this item.
2. Preserve the historical failed apply-time health observation and later independent `200`
   read-back. G5 closure does not authorize R-B3 deployment, public `PUT`, or further cloud work.

Before any future ingress close (`spec-a-9.14-close-ingress`), run
`api-gateway-custom-domain-remove` first so ingress close remains a one-resource operation.

## Non-claims

- This cross-reference update does **not** close the backlog or authorize Wave 6 implementation,
  R-B3 deployment, public `PUT`, ingress closure, hostname removal, or another Terraform operation.
- B1 Task 5.7 is checked under its own owner decision; Writer_Convergence is not achieved.
- Does **not** assert the binding was ever present before checkpoint 9.5; the Resource Graph record
  documents the binding loss at that checkpoint.
- Does **not** enable or claim any market-data writer path.
