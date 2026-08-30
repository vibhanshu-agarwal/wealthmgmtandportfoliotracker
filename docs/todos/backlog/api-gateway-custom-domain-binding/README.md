# Backlog: `api.vibhanshu-ai-portfolio.dev` has no Container Apps custom-domain binding

**Status:** Open — found 2026-08-31
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

so there is no hostname binding and no managed certificate. TLS therefore fails at handshake:

```
schannel: failed to receive handshake, SSL/TLS connection failed
```

DNS is fine; the binding is missing.

## Why it matters

`api.vibhanshu-ai-portfolio.dev` is the configured endpoint for the frontend and the synthetic
workflows (`NEXT_PUBLIC_API_BASE_URL` in `.github/workflows/synthetic-monitoring.yml`). Until the
binding is restored, that endpoint is unusable even though the gateway itself is healthy.

This is why **9.14 does not unblock G5**. The original G5 diagnosis in
[`B1_G5_INGRESS_BLOCKER.md`](../../../runbooks/B1_G5_INGRESS_BLOCKER.md) attributed the TLS reset to
disabled ingress. Ingress is now enabled and the same host still fails — so that attribution was
incomplete. There were two independent causes; 9.14 cleared one.

## What to do

1. Determine whether the binding was dropped when ingress was disabled at checkpoint 9.5, or was
   never Terraform-managed. Note that the 9.14 acceptance contract's B3 required `custom_domain` to
   be absent from the plan, so Terraform does **not** currently manage it.
2. Decide the owner: bind via Terraform (`azurerm_container_app_custom_domain` or the module's
   ingress block) so it is reproducible, or document it as an explicitly out-of-band resource.
3. Restore the binding **and** the managed certificate, then verify:
   - `az containerapp show … --query properties.configuration.ingress.customDomains` is non-null
   - `curl https://api.vibhanshu-ai-portfolio.dev/actuator/health` returns `200`
   - certificate subject covers the host and TLS verifies
4. Only then re-evaluate the G5 resume condition.

## Non-claims

- Does **not** authorize a custom-domain change, deployment, Terraform apply, or G5 dispatch.
- Does **not** assert the binding was ever present; that is question 1 above.
