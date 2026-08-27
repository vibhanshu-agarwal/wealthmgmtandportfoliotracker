# Spec A checkpoint 9.11 — persist refresh enablement

This is the durable, sanitized record of checkpoint 9.11: Terraform desired-state flip of
`MARKET_DATA_JOB_RUNNER_ENABLED` from `false` to `true` on `market-data-refresh-job`, production
apply through the `production` Environment gate, live read-back, and a follow-up standard
remote-plan that reported no changes. No secret values, plan JSON, or credentials appear here.

Checkpoint 9.11 does **not** start a refresh execution, seed the demo portfolio, restore
scale-to-zero, or reopen ingress. Those remain 9.12–9.14 and unauthorized after this record.

---

## Resource map

| Thing | Value |
|---|---|
| Resource group | `wealth-azure-prod-rg` |
| Refresh Job | `market-data-refresh-job` |
| Image | `wealthprodacr.azurecr.io/market-data-service:9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900` |
| Image digest (preflight) | `sha256:ad61144b2e747a5dd1b1fc9f5b5a091916559adf7c30117beae3563123aa2256` |
| Job identity | UserAssigned `wealth-prod-mdrefresh-job-id` |
| Source merge (desired-state `true`) | PR #164 → `main@0b857f3c` |
| Docs merge (merged-but-unapplied wording) | PR #165 → `main@e7fad7cb` |
| Change profile (enable) | `spec-a-9.11-enable` |
| Apply `expected_main_sha` | `e7fad7cbab997ae4f31bf2f31f2fe82470d034a5` |
| Apply `deployed_image_tag` | `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900` |

---

## Preflight (immediately before apply)

UTC window ~15:50–16:02 on 2026-08-27 (not near the `0 8 * * *` schedule).

| Check | Result |
|---|---|
| `origin/main` | `e7fad7cbab997ae4f31bf2f31f2fe82470d034a5` |
| Runner env | `MARKET_DATA_JOB_RUNNER_ENABLED=false` |
| Running executions | none (`[]`) |
| Safety tuple | retry `0`, timeout `600`, cron `0 8 * * *`, parallelism `1`, completions `1` |
| Identity / image | UserAssigned `wealth-prod-mdrefresh-job-id`; image tag matches dispatch |
| Gateway ingress | closed (`null` / empty) |
| Catalog services | `portfolio-service--0000081`, `market-data-service--0000078`, `insight-service--0000078` — Running, `min_replicas=1` |
| Kafka lag | `portfolio-group` and `insight-group` on `market-prices` partition 0: CURRENT=LOG-END=`24695`, LAG=`0` |

---

## Remote-plan (authorized earlier; reference)

- Run: [33080741185](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33080741185)
- Profile: `spec-a-9.11-enable`
- Sanitized summary: `azurerm_container_app_job.market_data_refresh ["update"]`
- Human plan: `Plan: 0 to add, 1 to change, 0 to destroy.`
- Delta: `MARKET_DATA_JOB_RUNNER_ENABLED` `"false" -> "true"` (in-place only)
- Spec A 9.11 exact-scope assert: **PASS**

---

## Apply

- Run: [33091163222](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33091163222)
- Dispatch: `action=apply`, `change_profile=spec-a-9.11-enable`, seed/recreate flags `false`
- `Validate live-state dispatch`: success
- `production` Environment: approved after validate (pending deployment env id `20423330960`)
- Independent apply-time plan (before `Terraform Apply`):
  - `Plan: 0 to add, 1 to change, 0 to destroy.`
  - `# azurerm_container_app_job.market_data_refresh will be updated in-place`
  - `MARKET_DATA_JOB_RUNNER_ENABLED` `"false" -> "true"`
- Mandatory asserts before apply: all **PASS**, including Spec A 9.11 exact-scope
- Apply result: `Apply complete! Resources: 0 added, 1 changed, 0 destroyed.`

---

## Live read-back (immediately after apply)

| Check | Result |
|---|---|
| Runner env | `MARKET_DATA_JOB_RUNNER_ENABLED=true` |
| Safety tuple | retry `0`, timeout `600`, cron `0 8 * * *`, parallelism `1`, completions `1` unchanged |
| Identity / image | UserAssigned `wealth-prod-mdrefresh-job-id`; same image tag |
| Running executions | none |
| Unexpected new execution | none (latest remains the scheduled `…-29796960` at `2026-08-27T08:00:00Z` and the 9.10 controlled `…-0i08hio`) |
| Gateway ingress | still closed |
| Peer revisions | unchanged: `portfolio-service--0000081`, `market-data-service--0000078`, `insight-service--0000078`, `api-gateway--0000076` |

---

## Follow-up standard remote-plan

- Run: [33093260896](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33093260896)
- Profile: `standard`
- Result: `No changes. Your infrastructure matches the configuration.`
- Spec A 9.11 standard guard: **PASS** (runner field unchanged)

---

## Explicit non-actions

- No refresh Job start / controlled execution
- No demo portfolio activation (9.12)
- No `min_replicas` restore (9.13)
- No ingress reopen (9.14)
- No B1 G5 retry
- No ad hoc `az containerapp job update` rollback

---

## GO

Checkpoint 9.11 is complete: persisted runner is `true`, safety tuple and identity unchanged,
peers and ingress unchanged, and Terraform state matches configuration under `standard`.
