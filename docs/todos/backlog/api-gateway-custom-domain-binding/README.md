# Backlog: API Gateway custom-domain binding recovery

**Status:** Open — hostname restored 2026-08-31; live-read-back evidence PR pending independent review
**Owner:** unassigned
**Blocks:** B1 Task 5.7 / G5
**Tracked in:** Surfaced by the Spec A 9.14 live read-back
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../../../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md), apply run
[33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)).
Pre-existing; not caused by 9.14.

---

## What is wrong

> **Historical incident; resolved in live state.** This section records the condition found before
> the guarded restore. The backlog remains open until the live-read-back evidence PR is reviewed;
> it does not permit G5 or B1 Task 5.7 completion.

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

**This backlog item stays open** until the live-read-back evidence PR is independently reviewed.
The completed restore does not itself unblock G5 or complete B1 Task 5.7.

## Why it matters

`api.vibhanshu-ai-portfolio.dev` is the configured endpoint for the frontend and the synthetic
workflows (`NEXT_PUBLIC_API_BASE_URL` in `.github/workflows/synthetic-monitoring.yml`). The restored
endpoint now returns healthy traffic, but synthetic/G5 evidence has not been re-run or authorized.

The completed restore still does **not** unblock G5. The original G5 diagnosis in
[`B1_G5_INGRESS_BLOCKER.md`](../../../runbooks/B1_G5_INGRESS_BLOCKER.md) attributed the TLS reset to
disabled ingress. That was incomplete: hostname-binding loss was a second independent cause. Both
ingress and the hostname binding are now healthy; G5 remains blocked by the evidence-review and
separate-authorization boundary, not by an assertion that the host still fails.

## What remains

1. Independently review this live-read-back evidence PR, including the red immediate default-host
   health observation in run `33380356530` and the subsequent healthy read-back.
2. Decide G5 resumption separately after the evidence review. Do not infer authorization from a
   source merge, remote plan, apply, certificate bind, or HTTP health result.

Before any future ingress close (`spec-a-9.14-close-ingress`), run
`api-gateway-custom-domain-remove` first so ingress close remains a one-resource operation.

## Non-claims

- Does **not** authorize G5 dispatch, backlog closure, B1 Task 5.7 completion, an ingress close,
  a hostname remove, or another Terraform operation.
- Does **not** claim G5 is unblocked or Writer_Convergence is achieved.
- Does **not** assert the binding was ever present before checkpoint 9.5; the Resource Graph record
  documents the binding loss at that checkpoint.
