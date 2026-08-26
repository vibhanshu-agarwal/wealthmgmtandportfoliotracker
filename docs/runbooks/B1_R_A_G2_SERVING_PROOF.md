# B1 R-A / G2 — gateway signup provisioning serving proof

This is the durable, sanitized record of B1 Wave 2 tasks **2.5 (G2)** and **2.6 (R-A)**.
No secret values, connection strings, JWTs, or passwords appear here. Local operational
captures used during execution are not committed.

## Decision

**GO — R-A.** G2 is green on the sole serving gateway revision. Tasks 3.5–3.7 / V20 production
application remain **unauthorized** and incomplete.

## Deploy

| Field | Value |
|---|---|
| Authorization | Owner phrase authorizing B1 R-A Tasks 2.5–2.6 in production (2026-08-26) |
| Dispatch | [Deploy run 32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627) |
| Mode | `deployment_mode=scoped`, `services=api-gateway`, empty `prebuilt_digest` |
| Dispatch SHA / image tag | `18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17` |
| Wave 2 source binding | `api-gateway/src/main` byte-identical from merge `fb115898` through the dispatch SHA (only test sources changed afterward) |
| Jobs | `deploy (api-gateway)` success; `deploy-frontend`, `seed`, `verify` **skipped**; `assert-scoped-non-interference` success |
| Prior abort digest (pre-R-A) | `sha256:ff80395eecaef731a089697dda50f34064612d478ac329e872631364082b7d0a` (`api-gateway:9b2cf0d…`, revision `api-gateway--0000075`) |

## Serving evidence (post-deploy)

| Field | Value |
|---|---|
| Revision | `api-gateway--0000076` |
| Image | `wealthprodacr.azurecr.io/api-gateway:18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17` |
| Digest | `sha256:2da5b303fd15772792167f2b26dc62250b2d9858270db315eab1d6d1a1554aec` |
| Revisions mode | `Single` |
| Active revisions | exactly one (`api-gateway--0000076`, `Running`) |
| Traffic weight | `0` (expected while Spec A keeps gateway ingress omitted/`null`) |
| Ingress | still `null` (not reopened) |
| Peer non-interference | `portfolio-service`, `market-data-service`, and `insight-service` revision/image unchanged vs pre-deploy snapshots |

## Controlled signup probe (G2)

Ingress remained closed, so the probe used in-revision loopback via
`az containerapp exec` against the sole serving replica:

- `POST http://127.0.0.1:8080/api/auth/signup` → **HTTP 201 Created**
- Probe email pattern: `b1-ra-g2-<yyyyMMddHHmmss>@example.com`
- Recorded probe identity: `b1-ra-g2-20260826152512@example.com`
- Returned `userId`: `381e8203-1b2c-4c94-99d3-7c1fb365967a`

## Exactly-one proof

Against production Postgres, for that signup:

| Check | Result |
|---|---|
| `users` rows by email | **1** |
| `users` rows by id | **1** |
| `portfolios` rows for that `user_id` | **1** |
| Portfolio id | `1e54a134-6494-4685-9d8d-0f1f76fcc448` |

G2 requires every **serving** revision to pass. Serving set size was **1** under `Single` mode.

## Explicit non-starts

This gate does **not** authorize:

- Spec A 9.11–9.14 (including ingress reopen)
- Tasks 3.5–3.7 / R-B / V20 production migration
- portfolio-service deployment, Wave 4–7 activation, or public `PUT /api/portfolio/holdings`
- Claiming `GET /api/assets` catalog data is live (Wave 4b controller remains undeployed)

## Abort path (not used)

On G2 failure the prior digest would have been restored with:

```text
az containerapp update -n api-gateway -g wealth-azure-prod-rg \
  --image wealthprodacr.azurecr.io/api-gateway@sha256:ff80395eecaef731a089697dda50f34064612d478ac329e872631364082b7d0a
```

Abort was not required.
