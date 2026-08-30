# Spec A checkpoint 9.13 — restore scale-to-zero

This is the durable, sanitized record of checkpoint 9.13: Terraform restore of
`min_replicas=0` on the three catalog-consumer Container Apps while enforcement overrides
remain absent, production apply through the `production` Environment gate, and
configuration-level read-back. No secret values, plan JSON, or credentials appear here.

Checkpoint 9.13 does **not** reopen gateway ingress. That remains checkpoint 9.14 and is
separately gated after this record is green.

---

## Resource map

| Thing | Value |
|---|---|
| Resource group | `wealth-azure-prod-rg` |
| Portfolio service | `portfolio-service--0000092` |
| Market-data service | `market-data-service--0000079` |
| Insight service | `insight-service--0000079` |
| Gateway (ingress still closed) | `api-gateway--0000077` |
| Change profile | `spec-a-9.13-restore-scale` |
| Remote-plan run | [33306477527](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33306477527) |
| Apply run | [33306874697](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33306874697) |
| Source merge baseline | `main@458813fcd76896f8fa349fa8e3600e0951303b65` (PR #183) |

---

## Outcome

| Check | Result |
|---|---|
| Profile guard | Exactly three in-place `update` actions on portfolio, market-data, and insight Container Apps |
| Scale transition | `min_replicas` `1 -> 0` (or unset) on all three catalog consumers |
| Enforcement overrides | `APP_CATALOG_REJECT_UNSUPPORTED_EVENTS` and `APP_CATALOG_ENFORCE_HOLDING_INVARIANT` remain absent |
| Demo flags | `APP_DEMO_SEED_ON_STARTUP=false`, `APP_DEMO_TX_DIAGNOSTICS=false` on portfolio |
| Gateway ingress | Still closed on `api-gateway--0000077` |
| Catalog overrides | Absent |

---

## Next gate

Checkpoint **9.14** reopens api-gateway ingress via `spec-a-9.14-reopen-ingress`. Do not start
B1 G5 or other public-serving proof until 9.14 is live-green.

Historical 9.12 RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`
([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](SPEC_A_9_12_POOLED_READONLY_RCA.md)).
