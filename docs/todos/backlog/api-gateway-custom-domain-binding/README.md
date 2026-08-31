# Backlog: `api.vibhanshu-ai-portfolio.dev` has no Container Apps custom-domain binding

**Status:** Open — found 2026-08-31; source-only recovery PR prepared, not executed
**Owner:** unassigned
**Blocks:** B1 Task 5.7 / G5
**Tracked in:** Surfaced by the Spec A 9.14 live read-back
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../../../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md), apply run
[33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)).
Pre-existing; not caused by 9.14.

---

## What is wrong

Checkpoint 9.14 reopened external ingress on `api-gateway`, and the **default ACA endpoint**
`api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` now serves healthy traffic
(`200`, `status: UP`).

The **custom domain does not serve**. `api.vibhanshu-ai-portfolio.dev` resolves correctly — it is a
CNAME alias onto the gateway's ACA hostname — but the Container App reports:

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

## Source-only recovery prepared

A source-only PR adds:

- Terraform ownership of hostname presence via `azurerm_container_app_custom_domain.api_gateway`
- workflow profiles `api-gateway-custom-domain-restore` and `api-gateway-custom-domain-remove`
- read-only preflight, universal plan guards, post-apply bind, and read-back wiring
- runbook [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../../../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)
  marked **NOT EXECUTED / SOURCE ONLY**

**This backlog item stays open** until a separately authorized apply plus live read-back evidence
closes it. Source merge alone does not restore TLS or unblock G5.

## Why it matters

`api.vibhanshu-ai-portfolio.dev` is the configured endpoint for the frontend and the synthetic
workflows (`NEXT_PUBLIC_API_BASE_URL` in `.github/workflows/synthetic-monitoring.yml`). Until the
binding is restored, that endpoint is unusable even though the gateway itself is healthy.

This is why **9.14 does not unblock G5**. The original G5 diagnosis in
[`B1_G5_INGRESS_BLOCKER.md`](../../../runbooks/B1_G5_INGRESS_BLOCKER.md) attributed the TLS reset to
disabled ingress. Ingress is now enabled and the same host still fails — so that attribution was
incomplete. There were two independent causes; 9.14 cleared one.

## What to do (after source merge and separate authorization)

1. Run `api-gateway-custom-domain-restore` remote-plan and obtain senior review of the exact-scope
   plan (one create on `azurerm_container_app_custom_domain.api_gateway[0]` only).
2. Apply through the `production` Environment gate, then allow the workflow bind step to attach the
   existing managed certificate `mc-wealth-prod-ac-api-vibhanshu-ai-5159` by explicit ID.
3. Verify post-bind read-back: bound hostname, unchanged revision, default and custom `/actuator/health`
   both return `200` with normal TLS verification.
4. Only then re-evaluate the G5 resume condition.

Before any future ingress close (`spec-a-9.14-close-ingress`), run
`api-gateway-custom-domain-remove` first so ingress close remains a one-resource operation.

## Non-claims

- Does **not** authorize a custom-domain change, deployment, Terraform apply, bind, or G5 dispatch
  from the source-only PR alone.
- Does **not** claim TLS is restored, G5 is unblocked, or Writer_Convergence is achieved.
- Does **not** assert the binding was ever present before checkpoint 9.5; the Resource Graph record
  documents the binding loss at that checkpoint.
