# Log Analytics retention reduced 30 → 7 days — 2026-05-17

**Branch:** `fix/azure-cost-budget-and-retention`
**Preceding changelog:** `docs/changes/CHANGES_AZURE_COST_SPIKE_FIX_2026-05-17.md`

---

## Why

Follow-up to the May-2026 Azure cost-spike investigation. With the synthetic-monitoring cron dropped to daily and the market-data refresh cron dropped to daily, ingestion volume into `wealth-prod-la` will fall sharply. There's no longer any reason to pay for 30 days of retention; 7 days is enough for last-week incident review and aligns with the new operating cadence.

This is a follow-up to the cost-spike PR, scoped to one knob.

## Change

### `infrastructure/terraform/azure/main.tf`

`azurerm_log_analytics_workspace.main.retention_in_days` now reads from a new `var.log_analytics_retention_days` variable. The default is `7` (was `30`).

### `infrastructure/terraform/azure/variables.tf`

New variable `log_analytics_retention_days` (number, default `7`) with a `7..730` range validation matching Azure's enforced bounds for `PerGB2018` workspaces.

## Trade-off

Logs older than 7 days will no longer be queryable in Log Analytics. If a regression is reported with a > 7-day delay, the trail will be gone. Acceptable for a personal demo deployment; revert to 30 (or higher) if compliance or longer-tail debugging becomes a requirement — just override `log_analytics_retention_days` in `terraform.tfvars` or via `TF_VAR_log_analytics_retention_days`.

## Out of scope

Budget alerts. The user has independent monthly billing notifications already and prefers not to add Terraform-managed budgets at this time.

## Verification

- `terraform validate` clean.
- `terraform fmt -check` clean.
- Apply will register a non-destructive update to the existing Log Analytics workspace (`retention_in_days: 30 → 7`); no recreate.
