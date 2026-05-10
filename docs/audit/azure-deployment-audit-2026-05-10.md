# Azure Deployment Audit — 2026-05-10

## Executive Summary

This audit reviewed the Azure migration and demo-readiness state for the Wealth Management and Portfolio Tracker application.

The Azure infrastructure and deployment workflows are mostly functional: recent `terraform-azure.yml` and `deploy-azure.yml` runs succeeded, including all four backend Container Apps and Azure Static Web Apps deployment.

The main demo-readiness blocker is the live Azure seeding path. The current CI seed command appears to run successfully but likely does not invoke the exported Playwright `globalSetup()` function, so no portfolio/market/insight seed data is created. This explains why the E2E/demo user may exist but no visible demo data appears.

## Scope

Reviewed areas:

- `docs/changes/CHANGES_PHASE4_SUMMARY_2026-05-09.md`
- `docs/audit/azure_deployment.md`
- `.github/workflows/deploy-azure.yml`
- `.github/workflows/terraform-azure.yml`
- `.github/workflows/ci-verification.yml`
- `.github/workflows/synthetic-monitoring.yml`
- `frontend/tests/e2e/global-setup.ts`
- Azure Terraform runtime env/secret wiring
- Golden-state seed controllers/services
- API gateway auth and internal-route handling
- Recent GitHub Actions workflow status

## 1. CI/CD Workflow Audit

### 1.1 Workflow status

| Workflow | Observed status |
|---|---|
| `deploy-azure.yml` | Latest runs succeeded |
| `terraform-azure.yml` | Latest run succeeded |
| `ci-verification.yml` | Latest run was in progress during audit; early jobs had passed |
| `synthetic-monitoring.yml` | AWS-only and stale; recent historical runs failed |

The latest successful `deploy-azure.yml` run completed `preflight`, all four backend deploy jobs, and `deploy-frontend`.

### 1.2 Critical finding: Azure live seed step likely does nothing

In `.github/workflows/ci-verification.yml`, the Azure seed step runs:

```bash
npx ts-node --project tsconfig.json tests/e2e/global-setup.ts
```

But `frontend/tests/e2e/global-setup.ts` exports the setup function instead of invoking it directly:

```ts
export default globalSetup;
```

When Playwright runs, Playwright invokes this exported function. When `ts-node tests/e2e/global-setup.ts` runs directly, it loads the module and exits without calling `globalSetup()`.

Impact: the workflow step can be green while making zero calls to:

- `POST /api/internal/portfolio/seed`
- `POST /api/internal/market-data/seed`
- `POST /api/internal/insight/seed`

Recommendation: create a dedicated executable seeding entrypoint or make `global-setup.ts` invoke `globalSetup()` when executed directly.

### 1.3 Azure seeding is in the wrong workflow

Azure seeding currently lives in `ci-verification.yml`, a general CI workflow, not the Azure deployment workflow.

Impact:

- A docs-only push can trigger live seeding.
- A manual `deploy-azure.yml` run does not trigger seeding.
- `ci-verification.yml` and `deploy-azure.yml` can run in parallel, so seeding can race deployment.
- Demo readiness depends on timing rather than a deterministic deploy sequence.

Recommendation: move live Azure seeding into `deploy-azure.yml` after backend and frontend deployment succeed, or create a `workflow_run` workflow triggered by successful `deploy-azure.yml` completion.

### 1.4 No Azure synthetic monitoring exists

`synthetic-monitoring.yml` is AWS-only and gated by `vars.CLOUD_PROVIDER == 'aws'`. There is no Azure-specific live smoke/synthetic suite.

Recommendation: add an Azure smoke test that validates API health, login, seed endpoints, portfolio data, summary data, and frontend render.

### 1.5 Frontend deploy resolves API FQDN but does not use it

`deploy-azure.yml` resolves the API Gateway FQDN from Azure, but the frontend build hard-codes:

```bash
NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev
```

This may be intentional after DNS cutover, but the resolved FQDN step is currently unused.

Recommendation: choose one source of truth: the custom domain, the resolved ACA FQDN, or a GitHub variable such as `AZURE_PUBLIC_API_BASE_URL`.

### 1.6 Frontend deploy can run after backend deploy failure

`deploy-frontend` uses `always()` but does not require `needs.deploy.result == 'success'`.

Recommendation: require backend success before frontend deployment:

```yaml
if: always() &&
    needs.preflight.result == 'success' &&
    needs.preflight.outputs.infra_ready == 'true' &&
    needs.deploy.result == 'success'
```

### 1.7 Duplicate/legacy workflows add noise

| Workflow | Observation |
|---|---|
| `ci.yml` | Duplicates unit/integration coverage from `ci-verification.yml` |
| `frontend-e2e-integration.yml` | Separate full-stack E2E path on push |
| `frontend-cd.yml` | Publishes frontend Docker image, while Azure uses SWA/static export |
| `cd.yml` | Marked disabled but still present |
| `synthetic-monitoring.yml` | AWS-only |

Recommendation: document the canonical demo path: `ci-verification.yml`, `terraform-azure.yml`, `deploy-azure.yml`, and a new Azure smoke/synthetic workflow.

## 2. Data Seeding Audit

### 2.1 Two seed layers exist

Layer 1: E2E login/user rows are created by `portfolio-service/src/main/resources/db/migration/V10__Seed_E2E_Test_User.sql` into `users`, `ba_user`, and `ba_account`.

Expected seeded user:

| Field | Value |
|---|---|
| User ID | `00000000-0000-0000-0000-000000000e2e` |
| Email | `e2e-test-user@vibhanshu-ai-portfolio.dev` |
| Documented password | `e2e-test-password-2026` |

Layer 2: Golden-state portfolio/market/insight data is created by `frontend/tests/e2e/global-setup.ts` via internal seed endpoints.

### 2.2 Most likely cause of missing data

The live Azure seed step likely exits without executing `globalSetup()`. Therefore the E2E user may exist from Flyway and gateway login may work, but portfolio/market/insight demo data is never created.

Confirm by checking whether `portfolio-service` logs contain:

```text
Golden-state seed complete
```

Expected success shape:

```text
Golden-state seed complete: userId=00000000-0000-0000-0000-000000000e2e portfolioId=<uuid> holdings=160 marketPrices=160
```

### 2.3 Environment alignment required

Golden-state seeding uses user ID, not email/password. `global-setup.ts` defaults to `00000000-0000-0000-0000-000000000e2e` unless `E2E_TEST_USER_ID` overrides it.

Required alignment:

| Setting | Expected value |
|---|---|
| `APP_AUTH_USER_ID` | `00000000-0000-0000-0000-000000000e2e` |
| `E2E_TEST_USER_ID` | same, or omitted |
| Flyway V10 user ID | same |
| Seeder target user ID | same |

If these drift, login may return a token for one user while seed data exists for another.

### 2.4 Flyway V10 does not update existing credentials

`V10__Seed_E2E_Test_User.sql` uses `ON CONFLICT DO NOTHING`. If rows already exist, changing `.env.secrets` does not update the DB password hash.

However, the current gateway `/api/auth/login` compares against configured `APP_AUTH_EMAIL` and `APP_AUTH_PASSWORD`, then signs a JWT. For this path, Container App env vars matter more than the `ba_account` hash.

## 3. Azure Logging Checks

Direct Azure log retrieval was not performed during this code audit. Run these checks before demo.

Search `portfolio-service` for seed success:

```text
Golden-state seed complete
```

Search all backend logs for seed/auth failures:

```text
internal_api_key_not_configured
invalid_internal_api_key
app.internal.api-key is blank
Seeding ERROR
```

Search `portfolio-service` startup logs for Flyway:

```text
Flyway
V10__Seed_E2E_Test_User
Successfully applied
```

Search all service logs for runtime issues:

```text
ERROR
Exception
PSQLException
MongoTimeoutException
RedisConnectionFailureException
DefaultAzureCredential
AzureOpenAI
```

Example tail command:

```bash
az containerapp logs show \
  --name portfolio-service \
  --resource-group wealth-azure-prod-rg \
  --tail 100
```

## 4. Recommended Demo-Readiness Plan

### P0 — Make Azure seeding actually execute

Fix the seed command so it invokes `globalSetup()` or a dedicated seed function. The current direct `ts-node` execution likely does not seed anything.

### P0 — Move seeding after Azure deploy

Move live Azure seeding from `ci-verification.yml` into `deploy-azure.yml` after successful backend and frontend deployment.

### P0 — Verify data through API

After seeding, verify login succeeds, `GET /api/portfolio` returns at least one portfolio, holdings are non-empty, and `GET /api/portfolio/summary` returns non-zero value.

### P1 — Add Azure live smoke test

Minimum assertions: API health is 200, login returns a token, portfolio seed returns `holdingsInserted=160`, market seed returns `pricesUpserted=160`, portfolio list has holdings, and summary total is greater than zero.

### P1 — Confirm Azure logs

Before demo, confirm seed success and absence of internal API key, database, MongoDB, Redis, and Azure OpenAI credential errors.

### P2 — Rationalize workflows

Reduce ambiguity by documenting or disabling legacy workflows and establishing a canonical Azure demo path.

## Conclusion

The Azure deployment path is close to demo-ready at the infrastructure and deployment level. The most likely explanation for missing seeded data is that the CI seed command loads `global-setup.ts` but does not invoke the exported setup function. Fixing the seed entrypoint, moving seeding into the Azure deploy workflow, and adding a live Azure smoke test should make the demo path reliable and observable.
