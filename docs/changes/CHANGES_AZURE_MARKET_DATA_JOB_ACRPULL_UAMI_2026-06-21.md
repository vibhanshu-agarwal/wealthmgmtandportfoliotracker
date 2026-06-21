# Changes — Break the market-data-refresh Job AcrPull bootstrap cycle (UAMI)

**Date:** 2026-06-21
**Area:** `infrastructure/terraform/azure`, `.github/workflows/terraform-azure.yml`
**Related:** spec `azure-market-data-feed-broken` (task 3.3), run
`actions/runs/27878697669` (failed recovery apply)

---

## Symptom

`terraform apply` for the Azure stack repeatedly failed on the
`market-data-refresh-job`. The `AcrPull` role assignment for the Job was never
created — live Azure returned `[]` for the Job principal on the `wealthprodacr`
scope — and even a targeted apply
(`terraform apply -target=azurerm_role_assignment.market_data_refresh_job_acr_pull`)
stalled because Terraform first tried to update
`azurerm_container_app_job.market_data_refresh`, which timed out provisioning its
ACA Job revision.

State was **not** corrupted. This was an IaC dependency / bootstrap problem, not
a state problem.

## Root cause — a circular bootstrap dependency

The Job used a **system-assigned** managed identity, and the role assignment
referenced that identity:

```hcl
# before
identity { type = "SystemAssigned" }

resource "azurerm_role_assignment" "market_data_refresh_job_acr_pull" {
  principal_id = azurerm_container_app_job.market_data_refresh.identity[0].principal_id
}
```

A system-assigned identity's `principal_id` only exists **after** the Job is
created. So the grant cannot be made until the Job exists — but the Job needs the
grant to pull its image. Terraform therefore always has to touch the Job before
the role assignment (even under `-target`, because the role depends on the Job),
and that update stalls on ACA Job revision provisioning. Classic chicken-and-egg.

## Fix — user-assigned managed identity (UAMI)

Switched the Job to a **user-assigned** identity. A UAMI's `principal_id` is known
as soon as the identity resource is created, independent of the Job, so the
`AcrPull` grant can be made **before** the Job is provisioned. The dependency
chain becomes linear and acyclic:

```
azurerm_user_assigned_identity.market_data_refresh_job   (no deps)
        │
        ├─► azurerm_role_assignment.market_data_refresh_job_acr_pull  (principal_id = UAMI)
        │            │
        └─►──────────┴─► azurerm_container_app_job.market_data_refresh
                              identity      = UserAssigned [UAMI]
                              registry.identity = UAMI resource id
                              depends_on    = [role assignment]
```

`main.tf` changes:

- Added `azurerm_user_assigned_identity.market_data_refresh_job`
  (`wealth-${var.environment}-mdrefresh-job-id`).
- Job `identity` → `type = "UserAssigned"`, `identity_ids = [<uami>.id]`.
- Job `registry.identity` → the UAMI **resource id** (not `"system"`).
- `azurerm_role_assignment.market_data_refresh_job_acr_pull.principal_id` →
  `<uami>.principal_id`.
- Job `depends_on = [azurerm_role_assignment.market_data_refresh_job_acr_pull]`
  so the grant precedes the Job.

The four long-running Container Apps are **unchanged** — they keep their
system-assigned identities and `registry.identity = "system"`, so the P5
(`test_acr_pull_property.py`) and P1 (`assert_plan.py`) plan-assertion gates,
which inspect only `azurerm_container_app` resources, stay green.

Note: a scheduled ACA Job pulls its image only when an execution is triggered
(the 08:00 UTC cron or a manual start), not at `terraform apply` time. The real
image is rolled separately by `deploy-azure.yml`
(`az containerapp job update --image …`). Combined with the Job-only seed
bootstrap, `terraform apply` never blocks on an ACR pull, and the UAMI's grant has
long since propagated by the time the first execution runs.

## Workflow change — clean recovery path

`terraform-azure.yml`:

- **Removed** the broken `recover_market_data_job_acr_pull` input and its
  "Recover market-data refresh Job AcrPull (targeted)" step (the targeted apply
  that re-triggered the Job update and the ad-hoc `sleep 120`).
- **Added** a `recreate_market_data_job` input. When `true`, the apply plan is
  generated with `-replace=azurerm_container_app_job.market_data_refresh`, so the
  stuck partial Job is destroyed and recreated cleanly **after** the UAMI and its
  grant exist.
- The idempotent "Import existing market-data refresh Job" safety-net step is
  retained.

## One-time recovery procedure

Run the Azure Terraform workflow with recreate ONLY:

```bash
gh workflow run terraform-azure.yml --ref main \
  -f action=apply \
  -f recreate_market_data_job=true
```

> [!WARNING]
> Do NOT also pass `use_seed_image=true`. That global flag flips all four
> long-running Container Apps' ingress `target_port` from `8080` to `80`
> (their `target_port` is not in `ignore_changes`), which would break live
> apps still serving on `8080`. `recreate_market_data_job=true` already
> enables a **Job-only** seed image automatically. The workflow now fails
> fast if both flags are set, and a recovery-mode plan assertion rejects the
> run if any Container App would change.

Order of operations during this apply:

1. UAMI is created.
2. `AcrPull` is granted to the UAMI on `wealthprodacr` (no dependency on the Job;
   `principal_type = "ServicePrincipal"` avoids AAD-replication PrincipalNotFound).
3. The stuck Job is destroyed and recreated with the **Job-only** public seed image
   and the user-assigned identity — no ACR pull at create, so it cannot stall, and
   the four Container Apps are untouched.

Then a normal `deploy-azure.yml` run rolls the real
`market-data-service:<sha>` image onto the Job via `az containerapp job update`,
authenticated by the UAMI's now-propagated `AcrPull` grant.

Subsequent applies need no special flags (both seed flags default to `false`;
the Job image is covered by `lifecycle.ignore_changes`).

## Review hardening (2026-06-21)

A design/implementation audit of the first cut surfaced a production-risk gap and
several smaller issues, all addressed here:

- **Job-only seed flag (was the critical gap).** Added
  `market_data_refresh_job_use_seed_image`, used only in the Job's image expression
  (`var.use_seed_image || var.market_data_refresh_job_use_seed_image`). Recovery no
  longer needs the global `use_seed_image`, so it can never repoint the live apps'
  ingress port. The workflow auto-sets this Job-only flag from
  `recreate_market_data_job`.
- **Fail-fast guard** rejecting `recreate_market_data_job=true` + `use_seed_image=true`.
- **Recovery-mode plan assertion** (`scripts/assert_recovery_plan.py`) that fails the
  run if any `azurerm_container_app` would be created/updated/replaced/deleted during a
  Job recovery (flags ingress `target_port` diffs explicitly).
- **Workflow timeout** raised `30m -> 90m` so GitHub does not kill an apply before
  Terraform's own 60m resource timeouts.
- **`principal_type = "ServicePrincipal"`** on the Job's AcrPull role assignment to
  reduce RBAC/AAD propagation flakiness right after the UAMI is created.
- **Stale comment fixed**: the apply path *does* re-run the P1/P5 plan assertions.

### Second review pass

- **In-place identity-migration guard (`scripts/assert_job_identity_migration.py`),
  runs on EVERY apply.** Because the stuck Job is in state with its old
  `SystemAssigned` shape, a plain `action=apply` (no recovery flag) would diff it
  against the new `UserAssigned` config and plan an **in-place** identity/registry
  update — which can stall on ACA Job provisioning, the very failure this change
  fixes. The guard fails the run if the Job plan is an in-place `update` that changes
  `identity.type` or `registry.identity`, and instructs the operator to rerun with
  `recreate_market_data_job=true` (forced `-replace`). Forced replacement, fresh
  create, and no-op all pass; benign in-place Job updates that don't touch
  identity/registry also pass. The guard compares `identity.type`, `registry.identity`,
  and `identity_ids` (the last as a set, so a harmless reordering is not flagged), making
  the invariant explicit and catching a UAMI swap even if `registry.identity` is left
  unchanged. Smoke-tested: System→User migration and UAMI swap fail; forced replace,
  benign update, and identity_ids reorder pass.

> [!IMPORTANT]
> Operational consequence: the **first** apply after this change merges must be the
> recovery apply (`-f recreate_market_data_job=true`). A plain apply will be
> blocked by the migration guard until the Job has been replaced once into the
> `UserAssigned` shape. After that, normal applies are no-ops on the Job and pass.

Not changed (acknowledged, lower priority): a retry/backoff around the
`az containerapp job update` step in `deploy-azure.yml`. That step runs in a separate
workflow long after the AcrPull grant has propagated, and `principal_type` covers the
apply-time replication window; ACA also retries the image pull at execution. Add a
retry there if a propagation flake is ever observed in practice.


## Verification performed

- `terraform fmt -check -recursive` — clean (re-run after the hardening edits).
- `terraform validate` — success (re-run after the hardening edits).
- Dummy local-backend `terraform plan` — graph builds with **no `Cycle` error**
  (it only failed later at Azure authorizer configuration), empirically confirming
  the UAMI → role → Job chain is acyclic.
- `scripts/assert_recovery_plan.py` — smoke-tested both ways: exits `1` and prints the
  `target_port 8080 -> 80` diff when a Container App would change; exits `0` when only
  the Job changes.
- `scripts/assert_job_identity_migration.py` — smoke-tested three ways: exits `1` on an
  in-place `SystemAssigned -> UserAssigned` update; exits `0` on a forced replace and on a
  benign in-place update that does not change identity/registry.

Full plan-assertion (`assert_plan.py`, `test_acr_pull_property.py`) and the real
apply run in CI, which has the Azure credentials and backend state.
