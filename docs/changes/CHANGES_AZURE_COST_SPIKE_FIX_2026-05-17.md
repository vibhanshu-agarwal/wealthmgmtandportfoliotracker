# Azure Cost-Spike Remediation — 2026-05-17

**Branch:** `fix/azure-cost-spike-may2026`
**Preceding changelog:** `docs/changes/CHANGES_PHASE4_SUMMARY_2026-05-13.md`
**Investigation trigger:** Azure costs on `wealth-azure-prod-rg` running 3-4× the budget estimate since 2026-05-12.

---

## Root cause

PR #36 (Phase 2 Azure Demo Readiness) merged into `main` on 2026-05-13 and introduced three changes that together kept the `wealth-azure-prod-rg` Container Apps pinned at non-zero replicas around the clock.

| # | Change | Impact |
|---|--------|--------|
| 1 | `synthetic-monitoring.yml` — hourly cron with an Azure synthetic job that hits all four ACA health endpoints + runs the full `azure-synthetic` Playwright project | All four Container Apps forced 0→1 every hour, ~24 cold-start cycles/day per service |
| 2 | `ci-verification.yml` — Azure synthetic step + AWS pre-warm step gated on `push` to `main` | Same wake-up pattern triggered on every commit (and there were many during the post-cutover stabilisation window) |
| 3 | `market-data-service/application-azure.yml` enables `market-data.refresh.enabled: true` while inheriting the hourly cron from `application.yml` | `market-data-service` woke from scale-to-zero every hour independently of synthetic monitoring |

ACA billing is a function of vCPU-seconds + memory-GiB-seconds while a revision is active. With four services × 0.5 vCPU × 1 GiB × hourly wake-ups, the active-revision time was an order of magnitude higher than the original capacity plan assumed.

---

## Changes

### 1. `.github/workflows/synthetic-monitoring.yml`

- Cron `'0 * * * *'` (hourly) → `'0 8 * * *'` (daily at 08:00 UTC / 13:30 IST).
- AWS and Azure synthetic suites unchanged in shape; only cadence reduced.
- `workflow_dispatch` still available for ad-hoc verification.

### 2. `.github/workflows/ci-verification.yml`

- Removed the `Pre-warm AWS Lambda stack`, `Run AWS synthetic monitoring`, and `Run Azure synthetic monitoring` steps from the `docker-build-verify` job.
- The job now ends after the local Docker Compose Playwright run, which is the correct scope for CI verification — live-stack synthetics belong in `synthetic-monitoring.yml` only.
- Added an inline comment pointing at the new source of truth.

### 3. `market-data-service/src/main/resources/application-azure.yml`

- Added `market-data.refresh.cron: "0 0 8 * * *"` (daily at 08:00) to override the hourly default in `application.yml`.
- Trade-off documented inline: prices served from `/api/market/prices` will be up to 24h stale between refreshes. The endpoint reads from MongoDB; there is no on-demand provider fetch on cache miss in the current code path.

---

## What this does NOT touch

- **AWS path** — `application-aws.yml` already keeps `market-data.refresh.enabled: false` (Lambda is short-lived). No change.
- **OpenAI / `gpt-4.1-mini` deployment** — `azure_synthetic/ai-insights.spec.ts` deliberately does not submit a chat, and `MarketSummaryGrid` reads cached data from `/api/insights/market-summary` (no fan-out to OpenAI). The deployment capacity (`var.openai_deployment_capacity = 10`) is unchanged.
- **Log Analytics retention** — left at 30 days for now. Consider dropping to 7 days as a follow-up if Free-Tier ingestion is still a concern after these changes.
- **`min_replicas`** — already 0 for portfolio/market-data/insight; api-gateway uses `var.api_gateway_min_replicas` (default 0). No change needed once the wake-up triggers are removed.

---

## Expected impact

- ACA active-revision time across the four services should drop by roughly an order of magnitude (24 wake-ups/day → 1).
- Daily synthetic still gives a real liveness signal; ad-hoc verification is one `workflow_dispatch` click away.
- Market data freshness drops from hourly to daily on Azure. Acceptable for the personal-investor demo profile; revisit if intra-day prices become a hard requirement.

---

## Follow-ups (optional)

1. **Log Analytics retention** — drop `azurerm_log_analytics_workspace.main.retention_in_days` from 30 to 7 in `infrastructure/terraform/azure/main.tf` if ingestion / retention remains a non-trivial line item.
2. **Cost alert** — set an Azure budget alert at 70% of the monthly cap on `wealth-azure-prod-rg` so the next regression surfaces before bill day.
3. **Tighten `cors_allowed_origin_patterns`** — already flagged in `CHANGES_PHASE4_SUMMARY_2026-05-10.md`; orthogonal to cost but pending cleanup.
