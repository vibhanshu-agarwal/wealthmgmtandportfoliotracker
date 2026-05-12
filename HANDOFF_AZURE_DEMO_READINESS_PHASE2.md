
## Handoff — Azure Demo Readiness Phase 2 (Review Resolution)

---

### Branch & Worktree
- **Branch:** `feat/azure-demo-readiness-phase2`
- **Worktree:** `D:\Projects\Development\Java\Spring\wealthmgmt-azure-phase2`
- **Remote tip:** `840cd0d` — fully pushed, no local uncommitted changes
- **Open PR:** `https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/36` (targeting `main`)

---

### Current Status: ✅ All Review Findings Resolved — PR is Merge-Ready

Five review rounds were completed. Every reported blocking, high, and medium finding has been fixed and confirmed by the reviewer. The last reviewer sign-off was on `1504a41`; `840cd0d` is the final incremental fix on top of that.

---

### What Was Done (by round)

**Round 1 — 10 findings (`b417203`–`8cc2d63`)**
- Restored `workflow_call` to `deploy-aws.yml` so `deploy.yml` dispatcher still routes to it
- Fixed `azure-synthetic` Playwright project: domain → `https://vibhanshu-ai-portfolio.dev`, timeout → `120_000`
- Added `azure-synthetic/*` to `chromium` project `testIgnore`
- Added `/actuator/health`, idempotent seed, and `totalValue > 0` assertions to `api-live-smoke.spec.ts`
- Added Azure synthetic step to `ci-verification.yml` (gated on `CLOUD_PROVIDER == 'azure'`, main push only)
- Disabled `ci.yml` and `frontend-e2e-integration.yml` with `workflow_dispatch` + required `reason` input; added `reason` to `frontend-cd.yml`
- Reverted AWS job name/artifact/cron in `synthetic-monitoring.yml` to exact original; fixed Azure job domain and email
- Removed out-of-scope `synthetic-monitoring` job from `deploy-azure.yml`
- Added source-of-truth comment above `NEXT_PUBLIC_API_BASE_URL` in `deploy-azure.yml`

**Round 2 — seed body + response assertions (`383d306`)**
- `POST /api/internal/market-data/seed` now sends `data: { userId: expectedUserId }` — `MarketDataSeedController.SeedRequest` requires it and returns `400` without it
- Both seed responses parsed and asserted: `holdingsInserted >= 160`, `pricesUpserted >= 160` (Requirement 1.4)
- HTTP status tightened from `[200, 204]` to `.toBe(200)`
- Fixed stale domain fallback and `90_000` timeout in `azure-synthetic.spec.ts`

**Round 3 — stale `.azurewebsites.net` cleanup (`d0105df`)**
- `ai-insights.spec.ts`: `page.goto("/insights")` → `page.goto("/ai-insights")` (app route is `/ai-insights`)
- All spec files and README: stale `@wealthmgmt-azure-prod.azurewebsites.net` fallbacks → `@vibhanshu-ai-portfolio.dev`
- Removed hardcoded `"TestPassword123!"` from `ai-insights.spec.ts` and `live-contract.spec.ts`

**Round 4 — `ai-insights.spec.ts` non-existent selectors (`22e4693`)**
- Rewrote test body entirely using real `data-testid` attributes sourced from the actual components:
  - `getByRole("heading", { name: "AI Insights" })` — from `page.tsx`
  - `getByTestId("chat-input")` — from `ChatInterface.tsx`
  - `[data-testid="market-summary-grid|empty|error"]` — from `MarketSummaryGrid.tsx`
- No chat message submitted (avoids Azure OpenAI quota on hourly runs)
- README timeout description updated (30 s → 120 s / 70 s / 20–30 s)

**Round 5 — hardcoded display name (`1504a41` → `840cd0d`)**
- Removed `TEST_USER_NAME` constant from `login.spec.ts`; switched post-login body assertion to `TEST_USER_EMAIL`
- Email is already injected by every workflow, always rendered in the UI, and independent of the optional `E2E_TEST_USER_NAME` secret — no workflow changes required
- Updated README `ai-insights.spec.ts` description to match the rewritten smoke

---

### Key Files Changed in This PR

| File | Nature of change |
|---|---|
| `.github/workflows/deploy-aws.yml` | Restored `workflow_call` |
| `.github/workflows/deploy-azure.yml` | Removed FQDN step + synthetic-monitoring job; source-of-truth comment |
| `.github/workflows/deploy.yml` | Disabled (manual-only + `reason` input) |
| `.github/workflows/ci.yml` | Disabled (manual-only + `reason` input) |
| `.github/workflows/frontend-cd.yml` | Added `reason` input |
| `.github/workflows/frontend-e2e-integration.yml` | Disabled (manual-only + `reason` input) |
| `.github/workflows/terraform.yml` | Disabled (manual-only) |
| `.github/workflows/synthetic-monitoring.yml` | Azure job added; AWS job left unchanged |
| `.github/workflows/ci-verification.yml` | Azure synthetic step added |
| `frontend/playwright.config.ts` | `azure-synthetic` project added; `chromium` excludes it |
| `frontend/tests/e2e/azure-synthetic/` | Entire directory created (6 spec files + README) |
| `infrastructure/terraform/aws/main.tf` | 4 import blocks removed |
| `infrastructure/terraform/azure/main.tf` | 8 import blocks removed |
| `*/InfrastructureHealthLogger.java` (×4) | `@Profile` widened to `{"aws","azure"}` |
| `*/InfrastructureHealthLoggerTest.java` (×8) | Unit tests created |

---

### Immediate Next Steps

1. **Post review comment on PR #36** — GitHub API returns `401` for this repo so it must be done manually in the browser. The full comment text was produced at the end of the previous session; paste it into the PR.

2. **Merge PR #36** into `main` once approved.

3. **Post-merge actions:**
   - Set `CLOUD_PROVIDER=azure` in GitHub repo variables (Settings → Secrets and variables → Actions → Variables)
   - Run `terraform-azure.yml` workflow to apply infrastructure
   - Run `deploy-azure.yml` to deploy services
   - Verify `synthetic-monitoring.yml` runs clean on next hourly tick

---

### Nothing Pending / No Open Questions

The working tree is clean, the remote is up to date, and no reviewer finding remains unresolved. The only outstanding action is the manual PR comment + merge.

