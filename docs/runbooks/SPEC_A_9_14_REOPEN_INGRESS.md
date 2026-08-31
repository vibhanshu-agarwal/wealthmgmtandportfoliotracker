# Spec A checkpoint 9.14 — reopen api-gateway ingress

Durable, sanitized record of checkpoint 9.14: the guarded Terraform reopen of external ingress on
the `api-gateway` Container App, applied through the `production` Environment gate, with
configuration-level and endpoint-level read-back. No secret values, plan JSON, or credentials
appear here.

This record is deliberately **narrow**. Read the [non-claims](#explicit-non-claims) before citing it
for anything beyond ACA ingress state.

---

## Resource map

| Thing | Value |
|---|---|
| Resource group | `wealth-azure-prod-rg` |
| Gateway revision (unchanged) | `api-gateway--0000077` |
| Change profile | `spec-a-9.14-reopen-ingress` |
| Source merge baseline | `main@66bbee0bf438706146ac9975bf5f0c923b3d43cb` (PR #184) |
| Dispatch pin | `main@743c9b971e857d76c659e0e2c40e339c6c2bf4a3` |
| Reviewed remote-plan | [33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724) (at `66bbee0b`) |
| SHA-matched remote-plan | [33330906012](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33330906012) (at `743c9b97`) |
| Apply run | [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603) |
| Superseded apply dispatch | [33330577030](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33330577030) — cancelled at the gate, apply job ran **0 steps** |

---

## Plan provenance

The plan was senior-reviewed and `ACCEPT`ed at `66bbee0b` against acceptance ids A1–A4, B1–B7, and
C1–C3, with no apply blocker
([review orientation](../superpowers/plans/2026-08-30-spec-a-9.14-reopen-ingress-review-orientation.md)).

Because `main` advanced by documentation-only commits after that review, a SHA-matched read-only
plan was run at the dispatch pin. Its plan body is **byte-identical** to the reviewed plan, and the
plan the apply job generated and applied is **byte-identical** to both. All three carry the same
shape:

```
Plan: 0 to add, 1 to change, 0 to destroy
~ module.api_gateway.azurerm_container_app.this   (in-place)
  + ingress { allow_insecure_connections = false
              external_enabled           = true
              target_port                = 8080
              transport                  = "auto"
              + traffic_weight { latest_revision = true, percentage = 100 } }
  # (10 unchanged attributes hidden)   # (17 unchanged blocks hidden)
```

The `infrastructure/terraform/`, `.github/workflows/terraform-azure.yml`, and `scripts/` trees are
byte-identical between `66bbee0b` and `743c9b97` by git object hash.

---

## Apply outcome

All twelve mandatory plan assertions passed in the apply job, including
`PASS spec-a-9.14 plan guard (profile=spec-a-9.14-reopen-ingress)` and the 9.9 / 9.11 / 9.12 / 9.13
guards confirming no `min_replicas`, catalog-override, or `MARKET_DATA_JOB_RUNNER_ENABLED` change.

```
module.api_gateway.azurerm_container_app.this: Modifications complete after 21s
Apply complete! Resources: 0 added, 1 changed, 0 destroyed.
```

---

## Live read-back

| Check | Result |
|---|---|
| `external` | `true` — ACA external ingress enabled |
| `allowInsecure` | `false` — insecure connections remain disabled |
| `targetPort` | `8080` |
| `transport` | `Auto` |
| `traffic` | `[{ latestRevision: true, weight: 100 }]` |
| `latestRevisionName` / `latestReadyRevisionName` | `api-gateway--0000077` |
| Revision identity | Unchanged; created `2026-08-30T03:41:22Z`, before this apply. No new revision cut — ingress is app-scoped config, not revision-scoped |
| Default ACA endpoint health | `200` — `{"groups":["liveness","readiness"],"status":"UP"}` |
| HTTP → HTTPS | `301` redirect, confirming `allowInsecure=false` in practice |

The default ACA endpoint is
`api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io`.

---

## Explicit non-claims

- Does **not** claim the custom domain is reachable. `api.vibhanshu-ai-portfolio.dev` CNAMEs to the
  gateway but the Container App has `customDomains: null`, so TLS fails at handshake
  (`schannel: failed to receive handshake, SSL/TLS connection failed`). Only the default ACA
  endpoint serves.
- Does **not** unblock **B1 G5**. G5's operative blocker is reachability of
  `api.vibhanshu-ai-portfolio.dev`, the endpoint configured for the frontend and synthetic
  workflows — see [`B1_G5_INGRESS_BLOCKER.md`](B1_G5_INGRESS_BLOCKER.md) and backlog item
  [`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md).
- Does **not** assert that `SERVICE_VERSION` matches the running image. It does not, on
  `api-gateway` or `portfolio-service` — pre-existing drift, untouched by this checkpoint. See
  backlog item [`service-version-image-drift`](../todos/backlog/service-version-image-drift/README.md).
- Does **not** authorize deployment, image rollout, custom-domain change, further Terraform apply,
  or any G5 dispatch.
- Historical 9.12 RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`
  ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](SPEC_A_9_12_POOLED_READONLY_RCA.md)).

---

## Reversal path (updated future mechanics)

The guarded reverse profile `spec-a-9.14-close-ingress` remains a **one-resource ingress close** on
`module.api_gateway.azurerm_container_app.this`. Source now adds separate custom-domain profiles:

| Step | Profile | Purpose |
|---|---|---|
| 1 (before close) | `api-gateway-custom-domain-remove` | Delete `azurerm_container_app_custom_domain.api_gateway[0]` from desired state while ingress is still open |
| 2 | `spec-a-9.14-close-ingress` | Close ingress only — a plan that also deletes the domain resource must fail the universal custom-domain guard |
| 3 (after reopen) | `spec-a-9.14-reopen-ingress` | Restore ingress only — no custom-domain preflight or bind |
| 4 | `api-gateway-custom-domain-restore` | Separate reviewed plan/apply/bind for TLS on `api.vibhanshu-ai-portfolio.dev` |

See [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md) for the full
recovery runbook (source-only until separately authorized).

The standing 9.14 rollback rule applies from this checkpoint onward: disabling the holding
validator, **or** rolling back to an R3 artifact whose defaults are permissive, requires quiescing
writes first and keeping them closed until the forward fix deploys.

---

## Process findings raised by this checkpoint

None affected this apply's correctness; all four are recorded as separate follow-ups.

1. [`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md)
2. [`service-version-image-drift`](../todos/backlog/service-version-image-drift/README.md)
3. [`deployed-image-tags-json-validation`](../todos/backlog/deployed-image-tags-json-validation/README.md)
4. [`b5-image-equality-assurance-claim`](../todos/backlog/b5-image-equality-assurance-claim/README.md)
