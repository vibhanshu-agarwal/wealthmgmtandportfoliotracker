# Azure Demo Readiness — Phase 1 Bugfix Design

## Overview

The live Azure deployment at `https://vibhanshu-ai-portfolio.dev/` ships empty — no portfolio, market, or insight data — while `deploy-azure.yml` reports green and the Flyway `V10` migration correctly provisions the demo user. Four CI/CD defects combine to produce that outcome:

1. The "Seed live Azure environment" step in `.github/workflows/ci-verification.yml` runs `npx ts-node --project tsconfig.json tests/e2e/global-setup.ts`, but the module ends with `export default globalSetup;` and has no `require.main === module` / top-level invocation. Direct execution loads the module and exits without calling `globalSetup()`. The step is silently a no-op.
2. That seed step is owned by `ci-verification.yml`, which fires on every push to `main` (including docs-only) and never on `workflow_dispatch` runs of `deploy-azure.yml`. Seeding races deployment on pushes and is skipped entirely on manual dispatches.
3. `deploy-frontend` in `deploy-azure.yml` uses `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && always()`. The `always()` clause lets the frontend ship even when the backend `deploy` matrix job failed.
4. Nothing verifies, after seeding, that demo data is queryable through the live gateway, so a silent seeding regression still produces a green workflow and an empty demo.

The fix is entirely in the CI/CD layer:

- Add an explicit executable entrypoint so direct-invocation actually runs `globalSetup()`, preserving the Playwright default-export contract.
- Remove the Azure seed step from `ci-verification.yml`.
- Add two new jobs to `deploy-azure.yml`: `seed` (runs after `preflight`, `deploy`, `deploy-frontend` all succeed) and `verify` (runs after `seed` succeeds and asserts four observable invariants through the live gateway).
- Add `needs.deploy.result == 'success'` to `deploy-frontend`'s `if:` and drop `always()`.

No Java, TypeScript application code, Terraform, Flyway migration, env-var contract, or secret name changes. The demo user ID (`00000000-0000-0000-0000-000000000e2e`) remains the single source of truth across Flyway V10, `APP_AUTH_USER_ID`, `E2E_TEST_USER_ID`, and `SEEDED_DEMO_USER_ID`. The internal API key contract enforced by `InternalApiKeyFilter` is unchanged.

## Glossary

- **Bug_Condition (C)**: The set of pipeline runs where the current (unfixed) code permits an empty-demo outcome — specifically: (a) a seed invocation that exits 0 without calling `globalSetup()`, (b) a seeding path that can race or skip `deploy-azure.yml`, (c) a `deploy-frontend` run that proceeds despite a failed backend `deploy` matrix, or (d) a successful-looking run that never confirms the live gateway actually serves seeded data.
- **Property (P)**: For every pipeline run where `preflight`, `deploy`, and `deploy-frontend` succeed on Azure, the seed step SHALL invoke all three internal seed endpoints exactly once and the verify step SHALL prove — through `https://api.vibhanshu-ai-portfolio.dev` — that health is 200, login returns a JWT, portfolio summary total > 0, and at least one portfolio has non-empty holdings.
- **Preservation**: All behavior unrelated to Azure pipeline seeding — Playwright default-export consumption of `globalSetup`, AWS synthetic monitoring (`vars.CLOUD_PROVIDER == 'aws'`), `deploy-frontend` internals (Node setup, `npm ci`, `Resolve API Gateway FQDN`, `npm run build`, SWA upload), env-var contract, demo user ID single-value invariant, `X-Internal-Api-Key` filter semantics — must be bit-identical after the fix.
- **`globalSetup`**: The default export of `frontend/tests/e2e/global-setup.ts`. Playwright calls it automatically for the E2E project; when invoked directly via `ts-node`, nothing calls it under the current code.
- **`isBugCondition(run)`**: The bug predicate defined formally in §3.1. Distinguishes pipeline runs where the fixed code must change behavior from runs where it must not.
- **Seed endpoints**: `POST /api/internal/portfolio/seed`, `POST /api/internal/market-data/seed`, `POST /api/internal/insight/seed` — all gated by `X-Internal-Api-Key` via `InternalApiKeyFilter`.
- **Live gateway**: `https://api.vibhanshu-ai-portfolio.dev` — the custom-domain hostname for the `api-gateway` Container App (DNS cutover completed 2026-05-10).
- **Preflight / deploy / deploy-frontend jobs**: The three existing jobs in `.github/workflows/deploy-azure.yml`. Preflight verifies RPs + RG + ACR; deploy is a 4-service matrix (api-gateway, portfolio-service, market-data-service, insight-service); deploy-frontend builds the Next.js static export and uploads to SWA.
- **Demo user ID**: `00000000-0000-0000-0000-000000000e2e`. Single-value invariant across Flyway V10 (`users`, `ba_user`, `ba_account`), `APP_AUTH_USER_ID` on the api-gateway Container App, `E2E_TEST_USER_ID` GitHub secret, and `SEEDED_DEMO_USER_ID` constant in `global-setup.ts`.

## Bug Details

### Bug Condition

The bug manifests on Azure pipeline runs where `preflight`, `deploy`, and `deploy-frontend` all succeed and the user expects a demo-ready live deployment. Under the current code, the pipeline reports green for any of four distinct reasons while leaving the live demo empty or broken. The unfixed `handleSeedAndVerify` flow (conceptual; the pipeline as it stands today) is either not invoking `globalSetup()`, not running in the correct workflow, not gating the frontend on backend success, or not verifying what was seeded.

**Formal Specification:**

```
FUNCTION isBugCondition(run)
  INPUT: run of type PipelineRun with fields:
    - trigger        : one of {push_main, workflow_dispatch, push_main_docs_only}
    - cloud_provider : one of {azure, aws}
    - preflight      : one of {success, failure, skipped}
    - infra_ready    : one of {true, false}
    - deploy         : one of {success, failure, cancelled, skipped}  -- matrix aggregate
    - deploy_frontend: one of {success, failure, skipped, not_run}
    - seed_invoked   : boolean  -- did any call to /api/internal/*/seed occur?
    - verify_result  : one of {asserted_live_data, not_run, failed}
  OUTPUT: boolean

  -- Defect 1: seed step exits 0 without invoking globalSetup()
  IF run.cloud_provider == azure
     AND seed_step_executed(run)
     AND NOT run.seed_invoked
  THEN RETURN true

  -- Defect 2: seeding in wrong workflow (races or skips)
  IF run.cloud_provider == azure
     AND (run.trigger == workflow_dispatch AND run.seed_invoked == false)
     AND run.preflight == success
     AND run.infra_ready == true
     AND run.deploy == success
     AND run.deploy_frontend == success
  THEN RETURN true

  IF run.cloud_provider == azure
     AND run.trigger == push_main_docs_only
     AND seed_step_executed(run)        -- seed fires on a push with no deploy
  THEN RETURN true

  -- Defect 3: frontend ships on failed backend
  IF run.cloud_provider == azure
     AND run.preflight == success
     AND run.infra_ready == true
     AND run.deploy IN {failure, cancelled}
     AND run.deploy_frontend == success
  THEN RETURN true

  -- Defect 4: no post-seed verification of live data
  IF run.cloud_provider == azure
     AND run.preflight == success
     AND run.infra_ready == true
     AND run.deploy == success
     AND run.deploy_frontend == success
     AND run.seed_invoked == true
     AND run.verify_result != asserted_live_data
  THEN RETURN true

  RETURN false
END FUNCTION
```

Each clause corresponds to one Current Behavior clause in `bugfix.md` §1: the four clauses map to defects 1.1, {1.2, 1.3}, 1.4, and 1.5 respectively.

### Examples

- **Defect 1 counterexample (seed no-op)**: A push to `main` with `vars.CLOUD_PROVIDER=azure`. `ci-verification.yml`'s "Seed live Azure environment" step runs `npx ts-node --project tsconfig.json tests/e2e/global-setup.ts`. Node loads the module, evaluates `export default globalSetup;` as a statement, has no top-level `await globalSetup()` or `require.main === module` check, and exits 0. No POST hits `api.vibhanshu-ai-portfolio.dev/api/internal/*/seed`. The step is green. Expected: the three seed endpoints are each called exactly once and the step exits non-zero on any non-transient error.
- **Defect 2 counterexample (wrong workflow — dispatch)**: A developer runs `gh workflow run deploy-azure.yml` after a hotfix. `preflight`, `deploy`, and `deploy-frontend` all succeed. `ci-verification.yml` is not triggered by this event, so no seeding runs and the live demo reflects whatever state the DB was in before. Expected: seeding runs as part of the same workflow run and produces a deterministic preflight → deploy → deploy-frontend → seed → verify sequence.
- **Defect 2 counterexample (wrong workflow — docs-only push)**: A push to `main` touching only `README.md` triggers `ci-verification.yml`. `deploy-azure.yml`'s `paths:` filter excludes this push, so no deploy runs, but `ci-verification.yml` still fires the Azure seed step in the `docker-build-verify` job. Seeding hits the live gateway against whatever version is currently deployed, unrelated to the push. Expected: seeding runs only as part of `deploy-azure.yml`, never from `ci-verification.yml`.
- **Defect 3 counterexample (frontend on broken backend)**: `preflight` succeeds with `infra_ready=true`. The `deploy` matrix fails for `portfolio-service` (e.g. revision never reaches Succeeded within the 10-minute poll window). `deploy-frontend`'s gate `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && always()` evaluates true regardless of `needs.deploy.result`, so `deploy-frontend` runs and ships a frontend pointing at a gateway whose downstream portfolio-service is on the previous revision. Expected: `deploy-frontend` is skipped when `deploy` failed or was cancelled.
- **Defect 4 counterexample (silent seeding regression)**: Seeding hits HTTP 200 on all three endpoints but a bug in `PortfolioSeedService` inserts zero holdings. The step is green. The workflow is green. The live demo is empty. Nothing fires to tell the operator. Expected: a post-seed verify step that fails red when `GET /api/portfolio/summary` returns total ≤ 0 or `GET /api/portfolio` returns no portfolios with holdings.
- **Edge case (infra not provisioned)**: `preflight` sets `infra_ready=false`. `deploy` and `deploy-frontend` skip cleanly today; the new `seed` and `verify` jobs must skip cleanly under the same condition — not run against empty ACA, not hard-fail the workflow beyond the existing warning.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- Playwright's invocation of `globalSetup` as a default export during the `Run Playwright E2E tests` step in `ci-verification.yml` (local Docker Compose stack) must produce bit-identical behavior: health-check polling, `runSeeding()` against `http://localhost:8080`, `SKIP_GOLDEN_STATE_SEEDING` / `SKIP_BACKEND_HEALTH_CHECK` honored with current semantics. No changes to the local E2E execution path.
- Playwright's invocation of `globalSetup` during the AWS synthetic project (gated on `github.event_name == 'push' && github.ref == 'refs/heads/main' && vars.CLOUD_PROVIDER == 'aws'`) must continue honoring `SKIP_GOLDEN_STATE_SEEDING=true` and `SKIP_BACKEND_HEALTH_CHECK=true` so AWS seeding continues via the local Docker Compose E2E run, not the live AWS stack.
- The `Pre-warm AWS Lambda stack` and `Run AWS synthetic monitoring` steps in `ci-verification.yml` must keep their existing triggers, secrets, and environment variables — no textual change.
- The `InternalApiKeyFilter` contract in portfolio-service, market-data-service, and insight-service remains unchanged: blank configured key ⇒ 503 `internal_api_key_not_configured`, missing or wrong `X-Internal-Api-Key` header ⇒ 403 `invalid_internal_api_key`, matching key ⇒ chain continues. No filter code is modified.
- The demo user ID `00000000-0000-0000-0000-000000000e2e` remains the single source of truth across Flyway V10 (`users`, `ba_user`, `ba_account`), the `APP_AUTH_USER_ID` Container App env var, the `E2E_TEST_USER_ID` GitHub secret, and the `SEEDED_DEMO_USER_ID` default in `global-setup.ts`. No values change.
- The env-var contract for Azure seeding is bit-identical to the current (broken) step: `NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev`, `INTERNAL_API_KEY=${{ secrets.TF_VAR_INTERNAL_API_KEY }}`, `E2E_TEST_USER_ID=${{ secrets.E2E_TEST_USER_ID || '00000000-0000-0000-0000-000000000e2e' }}`, `SKIP_BACKEND_HEALTH_CHECK: "true"`. No secret names or values change.
- `deploy-frontend` internals are unchanged: Node.js v20 setup, `npm ci`, the existing `Resolve API Gateway FQDN` step (non-blocking log), `npm run build` with `NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev`, and SWA upload via `Azure/static-web-apps-deploy@v1` with `skip_app_build: true`, `app_location: "frontend/out"`, `output_location: ""`. The only modification to `deploy-frontend` is its `if:` gate.
- When `preflight` reports `infra_ready=false`, the existing `deploy` and `deploy-frontend` skip-cleanly-with-warning behavior is preserved, and the new `seed` and `verify` jobs skip under the same condition via `needs.preflight.outputs.infra_ready == 'true'`.
- The Gateway login contract is unchanged: the verify step authenticates via `POST /api/auth/login` with email/password compared by `AuthController` against `APP_AUTH_EMAIL`/`APP_AUTH_PASSWORD`, producing a JWT signed for `APP_AUTH_USER_ID`. The verify step must NOT attempt to validate the Better Auth `ba_account` scrypt hash directly.

**Scope:**

All inputs outside the Azure pipeline seeding path must be unaffected. Specifically untouched:

- Java application code in `api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`.
- TypeScript application code (the `globalSetup` function body) — the fix adds an entrypoint, it does not modify `globalSetup()` itself.
- Terraform configuration under `infrastructure/terraform/azure/`.
- Flyway migrations in `portfolio-service/src/main/resources/db/migration/`.
- Docker Compose local dev stack.
- `frontend/playwright.config.ts` (Playwright's default-export consumption must keep working).
- All jobs in `ci-verification.yml` other than the Azure seed step (unit-tests, integration-tests, pact-consumer, the rest of docker-build-verify).
- `terraform-azure.yml`, `synthetic-monitoring.yml`, `ci.yml`, `cd.yml`, `frontend-cd.yml`, `frontend-e2e-integration.yml`.

## Hypothesized Root Cause

Based on bug reproduction and source analysis, the four defects are independent but share a single systemic cause — **the Azure seed path was grafted onto `ci-verification.yml` without a dedicated entrypoint, correct workflow placement, a hard dependency on deploy success, or a verification contract**.

1. **Entrypoint mismatch (defect 1, seed no-op)**: `global-setup.ts` is authored for Playwright's globalSetup interface, which imports the module and calls the default export. Direct `ts-node` execution loads the module but has no side-effect to drive `globalSetup()`. The module compiles cleanly under `"module": "esnext"` + `"moduleResolution": "bundler"` with `"esModuleInterop": true` in `tsconfig.json`, so `ts-node` accepts it and exits 0. The author almost certainly assumed `ts-node <file>` would invoke the default export the way `node -e 'require("./x")()'` would; it does not.
2. **Workflow placement (defects 2 & 3 of bugfix's four, i.e. race + dispatch skip)**: Azure seeding was added to `ci-verification.yml`'s `docker-build-verify` job because that job already runs Playwright E2E locally with an `INTERNAL_API_KEY` env var. The env was convenient; the trigger model was not. `ci-verification.yml` fires on every push to `main` (no path filter) and on PRs; `deploy-azure.yml` fires on a path-filtered push to `main` and on `workflow_dispatch`. The two workflow graphs overlap on code pushes but diverge on docs-only pushes and on manual dispatches, giving the three failure modes in the bug condition.
3. **`always()` misuse (defect 3 of four)**: The `deploy-frontend` gate `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && always()` was written to ensure the frontend runs even if `deploy` was skipped (e.g. when `infra_ready=false`). `always()` is overly broad — it short-circuits the implicit "all needs succeeded" rule that GitHub Actions otherwise applies, so any failed `needs` (including `deploy`) is ignored. The author wanted "if infra is ready, try the frontend"; the code reads "run the frontend whenever the workflow is alive and preflight is green". A single explicit clause `needs.deploy.result == 'success'` closes the hole without losing the skip-when-infra-absent behavior.
4. **Absent verification contract (defect 4)**: Seeding returns HTTP 200 on each endpoint, but 200 only says "controller accepted the request" — it does not say "160 holdings landed in Postgres and are queryable via JWT-auth through the live custom domain". The pipeline lacks a post-seed assertion that closes that observability gap. Adding an assertion of the four bugfix §2.5 invariants (health, login, summary > 0, portfolio with non-empty holdings) against `https://api.vibhanshu-ai-portfolio.dev` is the minimum cost that makes seeding regressions visible.

## Correctness Properties

Property 1: Bug Condition — Azure Pipeline Reliably Lands Demo Data and Proves It

_For any_ Azure pipeline run (`vars.CLOUD_PROVIDER == 'azure'`) where the bug condition holds (`isBugCondition(run)` returns true under the pre-fix code), the fixed pipeline SHALL either (a) produce a state where `preflight`, `deploy`, `deploy-frontend`, `seed`, and `verify` all report success and all four bugfix §2.5 invariants hold on the live gateway (HTTP 200 health, non-empty JWT from login, portfolio summary total > 0, at least one portfolio with non-empty holdings), or (b) fail the workflow red with a diagnostic error and NOT report a false green.

Concretely, the fixed pipeline SHALL:
- Invoke `POST /api/internal/portfolio/seed`, `POST /api/internal/market-data/seed`, and `POST /api/internal/insight/seed` exactly once each when seeding runs, with the seed script exiting non-zero on any non-transient error (addresses defect 1).
- Run Azure seeding exclusively from `deploy-azure.yml`, in a dedicated job with `needs: [preflight, deploy, deploy-frontend]`, executing if and only if all three precedents succeed, for both `push` to `main` and `workflow_dispatch` triggers; `ci-verification.yml` SHALL NOT run Azure seeding (addresses defect 2).
- Skip `deploy-frontend` whenever the backend `deploy` matrix does not report `success` (addresses defect 3).
- Run a `verify` job after `seed` succeeds that fails the workflow red if any of the four bugfix §2.5 invariants fail against `https://api.vibhanshu-ai-portfolio.dev` (addresses defect 4).

**Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5**

Property 2: Preservation — Non-Azure-Pipeline Behavior Is Unchanged

_For any_ input where the bug condition does NOT hold (`isBugCondition(run)` returns false) — notably local Docker Compose E2E runs, AWS synthetic monitoring runs (`vars.CLOUD_PROVIDER == 'aws'`), PR builds in `ci-verification.yml`, docs-only pushes, `terraform-azure.yml` runs, and runs where `infra_ready=false` — the fixed pipeline SHALL produce exactly the same observable behavior as the pre-fix pipeline:

- Playwright consuming the default export of `global-setup.ts` executes the same health-check polling, `runSeeding()` logic, and skip-flag semantics as today.
- The AWS synthetic project runs with `SKIP_GOLDEN_STATE_SEEDING=true` and `SKIP_BACKEND_HEALTH_CHECK=true` against `https://vibhanshu-ai-portfolio.dev` unchanged.
- All non-Azure-seed steps in `ci-verification.yml` (unit-tests, integration-tests, pact-consumer, pact provider verification, Docker Compose up, Playwright E2E, AWS pre-warm, AWS synthetic, GHCR publish) retain their triggers, env vars, and behavior.
- `deploy-frontend` internals (Node setup, `npm ci`, `Resolve API Gateway FQDN`, `npm run build` with the hardcoded `NEXT_PUBLIC_API_BASE_URL`, SWA upload with `skip_app_build: true`) are unchanged.
- The demo user ID, `APP_AUTH_*` env var contract, `X-Internal-Api-Key` filter semantics, and Flyway V10 row content are unchanged.
- When `infra_ready=false`, the new `seed` and `verify` jobs skip cleanly alongside the existing `deploy` and `deploy-frontend` skips.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9**

## Fix Implementation

### Changes Required

Assuming the root cause analysis holds, Phase 1 makes four bounded edits across three files, plus one new file. No application code, Terraform, migration, or secret changes.

**File 1**: `frontend/tests/e2e/global-setup.ts` — add self-executing entrypoint guard.

**Function**: module top-level, after `export default globalSetup;`.

**Specific Change**:

Append a CommonJS-compatible `require.main === module` guard that awaits `globalSetup()` and exits non-zero on error. Keep the existing `export default globalSetup;` unchanged so Playwright's contract is preserved.

```ts
// Direct-execution entrypoint (e.g. `npx ts-node tests/e2e/global-setup.ts`).
// Playwright imports this module and calls the default export; direct ts-node
// execution does not. This guard ensures CLI invocation actually runs seeding
// and propagates non-zero exit codes on failure.
// eslint-disable-next-line @typescript-eslint/no-require-imports
if (typeof require !== "undefined" && require.main === module) {
  globalSetup().catch((err) => {
    console.error(err);
    process.exit(1);
  });
}
```

Rationale: `frontend/tsconfig.json` uses `"module": "esnext"` + `"moduleResolution": "bundler"` + `"esModuleInterop": true`, and `frontend/package.json` does NOT set `"type": "module"`, so `ts-node` without an explicit `--esm` flag transpiles this file to CommonJS and `require.main` is defined. An ESM-equivalent guard (`import.meta.url === ...`) is not needed because the seed step never passes `--esm` and never runs under Node's native ESM loader. The `typeof require !== "undefined"` outer check is defensive for any future callsite that loads the module under ESM — the guard becomes a harmless no-op instead of throwing.

**Alternative considered and rejected**: Create a new file `frontend/tests/e2e/seed-azure.ts` that does `import globalSetup from "./global-setup"; globalSetup().catch(...)`. Rejected because it duplicates the invocation contract across two files, has to re-import all of `global-setup`'s environment bindings indirectly, and still leaves the original `global-setup.ts` confusing to read. Adding a guarded entrypoint to the same file is smaller and the conventional pattern.

**File 2**: `.github/workflows/ci-verification.yml` — remove the Azure seed step.

**Location**: The `Seed live Azure environment (vibhanshu-ai-portfolio.dev)` step in the `docker-build-verify` job, gated on `vars.CLOUD_PROVIDER == 'azure'`.

**Specific Change**:

Delete the entire step (10–15 lines). No other step in `ci-verification.yml` is modified. The job-level env (`INTERNAL_API_KEY`, `TF_VAR_internal_api_key`, `AUTH_JWT_SECRET`, etc.) stays — it is still consumed by the local Docker Compose E2E and the AWS synthetic steps. No change to triggers, path filters, or job names.

**File 3**: `.github/workflows/deploy-azure.yml` — fix the `deploy-frontend` gate and add `seed` + `verify` jobs.

**Change 3a — `deploy-frontend` gate**:

Replace:

```yaml
if: needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && always()
```

with:

```yaml
if: needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && needs.deploy.result == 'success'
```

`always()` is dropped. The new clause `needs.deploy.result == 'success'` ensures `deploy-frontend` skips on any backend failure or cancellation. Preservation: when `infra_ready=false`, `deploy` skips and this gate evaluates false — `deploy-frontend` still skips cleanly, matching today's skip-when-infra-absent semantics.

**Change 3b — new `seed` job**:

Add a job with the following shape (full YAML illustrated here; exact whitespace matches the existing file's two-space indent):

```yaml
  # ---------------------------------------------------------------------------
  # Seeding: populate the demo user's portfolio/market/insight data on the live
  # Azure stack. Runs only after backend and frontend both deploy cleanly.
  # Skipped when infra_ready=false (fresh subscription, terraform never applied).
  # ---------------------------------------------------------------------------
  seed:
    runs-on: ubuntu-latest
    needs: [preflight, deploy, deploy-frontend]
    if: >-
      needs.preflight.result == 'success' &&
      needs.preflight.outputs.infra_ready == 'true' &&
      needs.deploy.result == 'success' &&
      needs.deploy-frontend.result == 'success'

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up Node.js
        uses: actions/setup-node@v4
        with:
          node-version: "22"
          cache: "npm"
          cache-dependency-path: frontend/package-lock.json

      - name: Install frontend dependencies
        working-directory: frontend
        run: npm ci

      - name: Seed live Azure environment (vibhanshu-ai-portfolio.dev)
        working-directory: frontend
        env:
          NEXT_PUBLIC_API_BASE_URL: https://api.vibhanshu-ai-portfolio.dev
          SKIP_BACKEND_HEALTH_CHECK: "true"
          INTERNAL_API_KEY: ${{ secrets.TF_VAR_INTERNAL_API_KEY }}
          E2E_TEST_USER_ID: ${{ secrets.E2E_TEST_USER_ID || '00000000-0000-0000-0000-000000000e2e' }}
        run: npx ts-node --project tsconfig.json tests/e2e/global-setup.ts
```

Env-var contract and command-line are bit-identical to the old `ci-verification.yml` step (per bugfix §3.4). Only the workflow and trigger change. The `npm ci` step is required because `deploy-azure.yml` does not currently run any Node step before `deploy-frontend`, and `seed` runs in a fresh runner.

Dependency note: `ts-node` is currently invoked via `npx`, meaning `npx` fetches it on demand. `frontend/package.json` does NOT list `ts-node` today. The existing broken step in `ci-verification.yml` relies on the same behavior — `npx ts-node` works because npm's registry proxy downloads it. Phase 1 preserves this pattern to keep the scope minimal. **Optional hardening (not required for fix correctness)**: add `ts-node` and `typescript` to `frontend/devDependencies` so `npm ci` installs them deterministically; this removes the runtime network dependency on `npx` fetching `ts-node`. Decision deferred to implementation review — if kept as `npx`, verify the step still works offline-ish by pre-caching via `npm ci`.

**Change 3c — new `verify` job**:

Add a job that runs after `seed` and asserts the four live-gateway invariants from bugfix §2.5:

```yaml
  # ---------------------------------------------------------------------------
  # Verify: post-seed assertion that the demo is actually queryable through the
  # live custom domain. Closes the observability gap (audit §4 P0/P1).
  # Fails the workflow red on any assertion failure.
  # ---------------------------------------------------------------------------
  verify:
    runs-on: ubuntu-latest
    needs: [preflight, seed]
    if: >-
      needs.preflight.result == 'success' &&
      needs.preflight.outputs.infra_ready == 'true' &&
      needs.seed.result == 'success'

    env:
      API_BASE: https://api.vibhanshu-ai-portfolio.dev
      DEMO_EMAIL: ${{ secrets.E2E_TEST_USER_EMAIL }}
      DEMO_PASSWORD: ${{ secrets.E2E_TEST_USER_PASSWORD }}

    steps:
      - name: Verify live demo data
        run: |
          set -euo pipefail
          # (See Testing Strategy → Verification Script below for the full body.)
          bash .github/workflows/scripts/verify-azure-demo.sh
```

The verify logic is extracted into a new shell script `.github/workflows/scripts/verify-azure-demo.sh` so (a) the workflow YAML stays readable, (b) the script is independently runnable for manual operator smoke-checks (`bash .github/workflows/scripts/verify-azure-demo.sh` with the same env vars), and (c) future Phase 2 Azure synthetic monitoring can invoke the same script without duplication.

**File 4 (new)**: `.github/workflows/scripts/verify-azure-demo.sh` — the verification script. Full body in the Testing Strategy section.

### Summary of Pipeline Shape After Fix

```
deploy-azure.yml on push to main OR workflow_dispatch:

  preflight ──> deploy (matrix) ──> deploy-frontend ──> seed ──> verify
               (4 services)         (SWA upload)      (ts-node)  (curl+jq)

  Skip rules:
    - infra_ready=false  →  deploy, deploy-frontend, seed, verify all skip cleanly
    - deploy fails       →  deploy-frontend skips, seed skips, verify skips
    - deploy-frontend fails → seed skips, verify skips
    - seed fails         →  verify skips, workflow fails red
    - verify fails       →  workflow fails red
```

`ci-verification.yml` no longer does any Azure seeding. AWS pre-warm and AWS synthetic steps in `ci-verification.yml` are unchanged (gated on `vars.CLOUD_PROVIDER == 'aws'`).

## Testing Strategy

### Validation Approach

Two phases: first surface counterexamples that demonstrate the four defects on the unfixed pipeline, then verify the fix works and preserves non-Azure-pipeline behavior. Because the fix lives in CI/CD YAML and a TypeScript entrypoint, traditional unit-PBT on domain code is not applicable — "property-based testing" for this spec means **running the seed script against a controlled environment and asserting the same four invariants the pipeline will assert, across a set of generated failure scenarios** (forced seed 4xx, forced gateway 5xx, missing env vars, empty DB, etc.), plus workflow-level scenario matrices.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate each of the four defects on the unfixed code BEFORE applying the fix. Confirm or refute the root-cause hypothesis. If any hypothesis is refuted, re-analyze before proceeding.

**Test Plan**: For each defect, reproduce the observable counterexample either locally (for defect 1, which is a script-level bug) or by inspecting a run of the current workflow (defects 2–4 require CI context).

**Test Cases**:

1. **Defect 1 — Seed step is a no-op (local reproduction)**: With `NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev`, `INTERNAL_API_KEY=<real>`, `SKIP_BACKEND_HEALTH_CHECK=true`, `E2E_TEST_USER_ID=00000000-0000-0000-0000-000000000e2e`, run `npx ts-node --project tsconfig.json frontend/tests/e2e/global-setup.ts` from the `frontend/` directory. Expected observation on unfixed code: the command prints nothing (no `[timestamp] Starting Golden State seeding...` log), exits 0, and network logs show zero requests to `api.vibhanshu-ai-portfolio.dev`. Will fail to invoke seeding on unfixed code, confirming root cause.
2. **Defect 2 — Docs-only push triggers Azure seeding (workflow inspection)**: Locate a recent push to `main` touching only `README.md` or other `docs/**` paths. Confirm `ci-verification.yml` ran and that `docker-build-verify` reached the "Seed live Azure environment" step (step status was green). Confirm `deploy-azure.yml` did NOT run because its path filter excluded the push. Will demonstrate the race/wrong-workflow mode on unfixed code.
3. **Defect 2 — `workflow_dispatch` skips seeding (workflow inspection)**: Trigger `deploy-azure.yml` manually via `gh workflow run deploy-azure.yml`. Confirm `preflight`, `deploy`, `deploy-frontend` all succeed and no seeding runs as part of the workflow. Confirm no other workflow fires as a result. Will demonstrate the dispatch-skips-seeding mode on unfixed code.
4. **Defect 3 — Frontend ships on failed backend (controlled failure injection)**: In a throwaway branch, force one of the `deploy` matrix services to fail by intentionally breaking its `Dockerfile.azure` (e.g. `FROM nonexistent:latest`). Push and observe: `deploy` matrix job fails for that service, but `deploy-frontend` still runs because of `always()`. Will demonstrate the gate bug on unfixed code. Immediately revert.
5. **Defect 4 — Silent seeding regression (simulated)**: Without deploying, manually curl `GET https://api.vibhanshu-ai-portfolio.dev/api/portfolio/summary` with a live-gateway JWT. If the total is 0 or the endpoint returns no portfolios with holdings, note that the most recent `ci-verification.yml` run was green. That contrast (green workflow + empty demo) is the defect 4 counterexample. Will demonstrate the absent-verification mode on unfixed observable state.

**Expected Counterexamples**:

- `ts-node tests/e2e/global-setup.ts` prints no seeding logs, makes no POST calls, exits 0. Confirms: missing `require.main === module` guard.
- Docs-only pushes fire the seed step. Confirms: seeding lives in wrong workflow.
- Manual `workflow_dispatch` never fires seeding. Confirms: seeding lives in wrong workflow.
- Backend matrix failure does not skip `deploy-frontend`. Confirms: `always()` + missing `needs.deploy.result == 'success'`.
- Green workflow coexists with empty demo. Confirms: no post-seed verification.

If any expected counterexample fails to reproduce (e.g. defect 1 actually does seed — perhaps due to an earlier `tsconfig.json` change I missed), re-read the failing assumption, re-hypothesize, and do not apply the corresponding fix unchanged.

### Fix Checking

**Goal**: Verify that for all pipeline runs where the bug condition holds, the fixed pipeline produces the expected behavior (seeds the live stack AND verifies the four invariants, or fails red).

**Pseudocode:**

```
FOR ALL run WHERE isBugCondition(run) DO
  apply fixed pipeline
  result := execute(run)
  ASSERT result.outcome IN {
    (success AND seed_invoked == true AND verify_result == asserted_live_data),
    (failure AND diagnostic_error_logged)
  }
  ASSERT result.outcome != (success AND verify_result != asserted_live_data)
END FOR
```

Concrete fix-check scenarios:

1. **Push to `main` with `vars.CLOUD_PROVIDER=azure`, all services healthy**: the new `seed` job invokes all three endpoints exactly once; the new `verify` job asserts health 200, login → JWT, summary > 0, portfolio with non-empty holdings; workflow green.
2. **`workflow_dispatch`, all services healthy**: same observable result as (1) — seed + verify run as part of the same workflow run.
3. **Push to `main`, backend `portfolio-service` deploy fails**: `deploy-frontend` skips (gate blocks), `seed` skips (requires `deploy-frontend` success), `verify` skips, workflow fails red (on the failed matrix entry).
4. **Seed script can't reach gateway (simulated network failure)**: `seedFetch` exhausts retries, throws; `seed` job exits non-zero (because `globalSetup` now actually awaits the seed and the new entrypoint propagates errors via `process.exit(1)`); `verify` skips; workflow fails red.
5. **Seed succeeds but verification fails (e.g. summary returns 0)**: `verify` script asserts `jq '.total'` > 0, fails, exits non-zero; workflow fails red. This is the previously-silent regression class now surfaced.

### Preservation Checking

**Goal**: Verify that for all pipeline runs where the bug condition does NOT hold, the fixed pipeline produces the same observable behavior as the unfixed pipeline.

**Pseudocode:**

```
FOR ALL run WHERE NOT isBugCondition(run) DO
  ASSERT unfixed_pipeline(run).observable_steps_executed == fixed_pipeline(run).observable_steps_executed
  ASSERT unfixed_pipeline(run).env_vars_set          == fixed_pipeline(run).env_vars_set
  ASSERT unfixed_pipeline(run).exit_status            == fixed_pipeline(run).exit_status
END FOR
```

**Testing Approach**: Scenario-matrix testing against the actual workflow YAML (via `act` or live GitHub Actions runs) is recommended because the preservation space is discrete and small (trigger × cloud_provider × preflight/deploy/deploy-frontend outcomes). Pure property-based testing over random pipeline configurations isn't valuable here; the scenarios are enumerable.

For the `globalSetup` entrypoint change specifically, a Vitest unit test is possible: import `globalSetup` (default) from the module and assert the exported function reference is identical before and after the fix. This validates the Playwright contract is preserved at the module-export level.

**Test Plan**: Observe behavior on the unfixed pipeline for these preservation scenarios, then enumerate the same scenarios after the fix and compare.

**Test Cases**:

1. **Local Docker Compose E2E (`docker-build-verify` Playwright run)**: Observe on unfixed code that `globalSetup()` runs health-check polling and `runSeeding()` against `http://localhost:8080`, printing the expected timestamped log lines. After fix, same behavior — Playwright still consumes `export default globalSetup;` identically. The new `require.main === module` guard is false in this path (Playwright imports the module, it is not `node global-setup.ts`).
2. **AWS synthetic monitoring (`vars.CLOUD_PROVIDER=aws`)**: Observe on unfixed code that the AWS synthetic project runs with `SKIP_GOLDEN_STATE_SEEDING=true` and `SKIP_BACKEND_HEALTH_CHECK=true`, hitting `https://vibhanshu-ai-portfolio.dev` via the AWS path. After fix, same behavior — `ci-verification.yml`'s AWS-gated steps are untouched. Confirm the removed step's deletion is surgical (YAML diff shows only the `- name: Seed live Azure environment (...)` block removed, nothing else).
3. **Docs-only push**: Observe on unfixed code that the Azure seed step in `ci-verification.yml` ran. After fix, the step does not exist — the only visible difference is the absent Azure seed step. Confirm the rest of `ci-verification.yml` (unit tests, integration tests, pact, Docker Compose up, E2E) still runs identically.
4. **PR build**: `pull_request` triggers `ci-verification.yml`. `vars.CLOUD_PROVIDER` is typically unset or `aws`. Observe that the Azure gated step didn't fire before (gate false). After fix, it doesn't exist at all. No behavior change visible.
5. **`infra_ready=false` run**: A push before `terraform-azure.yml` has ever been applied. `preflight` outputs `infra_ready=false`. Observe on unfixed code that `deploy` and `deploy-frontend` skip cleanly. After fix, `seed` and `verify` skip under the same condition — their `if:` gates require `infra_ready == 'true'`. Workflow still reports success (preflight outputs a warning) because skipped jobs don't fail the workflow.
6. **`InternalApiKeyFilter` contract**: Send `POST /api/internal/portfolio/seed` with (a) valid key (expect 200), (b) missing header (expect 403 `invalid_internal_api_key`), (c) wrong key (expect 403 `invalid_internal_api_key`), (d) filter configured with blank key (expect 503 `internal_api_key_not_configured`). All four cases are identical before and after the fix — filter code is untouched.
7. **Demo user ID single-value invariant**: `grep -r '00000000-0000-0000-0000-000000000e2e'` across `portfolio-service/src/main/resources/db/migration/V10__*`, `infrastructure/terraform/azure/**`, `frontend/tests/e2e/global-setup.ts`, and `.github/workflows/**`. Count must be identical before and after fix (fix adds no new occurrences and removes none).
8. **`deploy-frontend` internals**: Diff the job's steps list before/after fix. The only textual change must be the `if:` line; Node setup, `npm ci`, `Resolve API Gateway FQDN`, `npm run build` (with the same `NEXT_PUBLIC_API_BASE_URL`), and SWA upload action parameters (`skip_app_build: true`, `app_location: "frontend/out"`, `output_location: ""`) must be unchanged.

### Unit Tests

- **`globalSetup` export preservation** (`frontend/tests/e2e/global-setup.spec.ts` or similar): `import globalSetup from "./global-setup"; expect(typeof globalSetup).toBe("function"); expect(globalSetup.length).toBe(0);` — asserts Playwright's default-export contract is preserved. This test runs under Vitest as part of `npm run test`.
- **Entrypoint guard does not run under import** (negative test): Programmatically `require("./tests/e2e/global-setup.ts")` (transpiled via ts-node or in a compiled build) and assert no seed request was fired (use MSW or `vi.spyOn(globalThis, "fetch")` with no network). Confirms the guard doesn't invoke `globalSetup()` during module import — only on direct execution.
- **Verify script exit codes** (`.github/workflows/scripts/verify-azure-demo.test.sh` or a Bats test, optional): run the script against a mocked HTTP server and assert exit 0 on all-pass, exit 1 on health ≠ 200, exit 1 on login ≠ 200, exit 1 on summary = 0, exit 1 on portfolio empty. Bats is heavy; if team prefers, fold these into a single manual smoke checklist. Either path is acceptable.

### Property-Based Tests

Property-based testing is a poor fit for CI/CD YAML. For the parts that are scriptable, use scenario enumeration (above). For the JS-level entrypoint change, use `fast-check` (already in `frontend/devDependencies`) to assert the entrypoint guard behavior is stable under a small generator:

- **Property: the guarded entrypoint is a no-op when module is imported**. Generator: random boolean for `isMainModule`. Assertion: `if isMainModule=false`, `globalSetup` is not invoked (monitor via fetch mock). If `true`, `globalSetup` is invoked exactly once. This is narrow but cheap.
- **Property: `seedFetch`'s retry loop is idempotent modulo call count** (already implicit in `global-setup.ts`, not modified by Phase 1; no new test required).

### Integration Tests

The meaningful integration signal is the end-to-end workflow run itself. Execute these scenarios on a feature branch before merging Phase 1 to `main`:

1. **Full happy-path**: Push to feature branch (mirrored to `main` config), let `deploy-azure.yml` run end-to-end. Expect green `preflight` → `deploy` → `deploy-frontend` → `seed` → `verify`. Expect `verify` logs to show HTTP 200 on health, non-empty JWT from login, `summary.total > 0`, and at least one portfolio with non-empty holdings.
2. **`workflow_dispatch` happy-path**: Trigger `deploy-azure.yml` manually. Same expected shape as (1).
3. **Forced backend failure**: Inject a `Dockerfile.azure` error for one service. Expect `deploy` fails for that service, `deploy-frontend` skips, `seed` skips, `verify` skips, workflow red.
4. **Forced seed failure**: Temporarily rotate `TF_VAR_INTERNAL_API_KEY` to a wrong value. Expect `seed` job fails with `403 invalid_internal_api_key` surfaced by `assertSeedOk`, `verify` skips, workflow red. Restore the secret immediately after.
5. **Forced verify failure**: Point `API_BASE` at a non-seeded test environment (if one exists) or deliberately seed only the portfolio endpoint then skip market-data/insight; expect `verify` passes on portfolio/summary but the summary total may still be > 0. Cleaner alternative: run `verify` against a fresh Azure tenant that has Flyway V10 applied (demo user exists) but never ran seeding — expect `verify` fails red on either `summary.total > 0` or portfolio-with-holdings.
6. **`infra_ready=false`**: Rename the resource group temporarily in Azure (or simulate in a test subscription) so `preflight` reports `infra_ready=false`. Expect `deploy`, `deploy-frontend`, `seed`, `verify` all skip cleanly with the existing warning, workflow green.

### Verification Script (`verify-azure-demo.sh`)

The post-seed verification script. Uses `curl` + `jq` (both preinstalled on `ubuntu-latest`). No retries beyond the `--retry` flag on `curl` for transient TLS/DNS issues; the step runs after the seed is known-complete, so the gateway should be responsive.

```bash
#!/usr/bin/env bash
# verify-azure-demo.sh — post-seed live-demo assertion.
# Asserts the four invariants from bugfix.md §2.5 against the live API gateway.
# Exits 0 on all-pass, exits 1 on any assertion failure (with a clear message).
#
# Required env vars:
#   API_BASE        — e.g. https://api.vibhanshu-ai-portfolio.dev
#   DEMO_EMAIL      — demo user email (from secrets.E2E_TEST_USER_EMAIL)
#   DEMO_PASSWORD   — demo user password (from secrets.E2E_TEST_USER_PASSWORD)
set -euo pipefail

: "${API_BASE:?API_BASE must be set}"
: "${DEMO_EMAIL:?DEMO_EMAIL must be set}"
: "${DEMO_PASSWORD:?DEMO_PASSWORD must be set}"

CURL_OPTS=(--silent --show-error --fail-with-body --retry 3 --retry-delay 5 --max-time 30)

# Assertion (a): /actuator/health returns 200
echo "::group::Assertion (a) — /actuator/health == 200"
HEALTH_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/health.json -w '%{http_code}' "${API_BASE}/actuator/health")
echo "HTTP ${HEALTH_STATUS}"
cat /tmp/health.json
if [ "${HEALTH_STATUS}" != "200" ]; then
  echo "::error::Health check failed: expected 200, got ${HEALTH_STATUS}"
  exit 1
fi
echo "::endgroup::"

# Assertion (b): /api/auth/login returns 200 with non-empty JWT
echo "::group::Assertion (b) — /api/auth/login returns JWT"
LOGIN_BODY=$(jq -nc --arg email "${DEMO_EMAIL}" --arg pwd "${DEMO_PASSWORD}" '{email:$email,password:$pwd}')
LOGIN_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/login.json -w '%{http_code}' \
  -H 'Content-Type: application/json' \
  -d "${LOGIN_BODY}" \
  "${API_BASE}/api/auth/login")
echo "HTTP ${LOGIN_STATUS}"
if [ "${LOGIN_STATUS}" != "200" ]; then
  echo "::error::Login failed: expected 200, got ${LOGIN_STATUS}"
  cat /tmp/login.json
  exit 1
fi
JWT=$(jq -r '.token // empty' /tmp/login.json)
if [ -z "${JWT}" ]; then
  echo "::error::Login response missing .token"
  cat /tmp/login.json
  exit 1
fi
echo "JWT length: ${#JWT}"
echo "::endgroup::"

# Assertion (c): /api/portfolio/summary total > 0
echo "::group::Assertion (c) — /api/portfolio/summary total > 0"
SUMMARY_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/summary.json -w '%{http_code}' \
  -H "Authorization: Bearer ${JWT}" \
  "${API_BASE}/api/portfolio/summary")
echo "HTTP ${SUMMARY_STATUS}"
cat /tmp/summary.json
if [ "${SUMMARY_STATUS}" != "200" ]; then
  echo "::error::Summary failed: expected 200, got ${SUMMARY_STATUS}"
  exit 1
fi
# Portfolio summary's total field is `totalValue` (BigDecimal) in the current API;
# guard against schema drift by attempting multiple plausible field names.
TOTAL=$(jq -r '(.totalValue // .total // 0) | tonumber' /tmp/summary.json)
echo "Summary total: ${TOTAL}"
if ! awk -v t="${TOTAL}" 'BEGIN { exit !(t > 0) }'; then
  echo "::error::Summary total is not > 0: ${TOTAL}"
  exit 1
fi
echo "::endgroup::"

# Assertion (d): /api/portfolio returns at least one portfolio with non-empty holdings
echo "::group::Assertion (d) — /api/portfolio has non-empty holdings"
PORTFOLIO_STATUS=$(curl "${CURL_OPTS[@]}" -o /tmp/portfolio.json -w '%{http_code}' \
  -H "Authorization: Bearer ${JWT}" \
  "${API_BASE}/api/portfolio")
echo "HTTP ${PORTFOLIO_STATUS}"
if [ "${PORTFOLIO_STATUS}" != "200" ]; then
  echo "::error::Portfolio list failed: expected 200, got ${PORTFOLIO_STATUS}"
  cat /tmp/portfolio.json
  exit 1
fi
# Accept either a top-level array [ {holdings:[...]}, ... ] or a wrapper { portfolios: [...] }.
PORTFOLIO_COUNT=$(jq -r '(if type=="array" then . else (.portfolios // []) end) | length' /tmp/portfolio.json)
NONEMPTY_COUNT=$(jq -r '
  (if type=="array" then . else (.portfolios // []) end)
  | map(select((.holdings // []) | length > 0))
  | length' /tmp/portfolio.json)
echo "Portfolios returned: ${PORTFOLIO_COUNT}; with non-empty holdings: ${NONEMPTY_COUNT}"
if [ "${PORTFOLIO_COUNT}" = "0" ]; then
  echo "::error::No portfolios returned for demo user"
  exit 1
fi
if [ "${NONEMPTY_COUNT}" = "0" ]; then
  echo "::error::No portfolio with non-empty holdings found"
  exit 1
fi
echo "::endgroup::"

echo "All four verify assertions passed."
```

**Script notes**:

- `set -euo pipefail` ensures any unexpected command failure or unset variable fails the step.
- `curl --fail-with-body` makes curl exit non-zero on 4xx/5xx while still printing the body (better than `--fail`, which drops the body). `--retry 3 --retry-delay 5 --max-time 30` handles transient TLS/DNS without masking persistent failures.
- Assertion (c) uses `awk` for the floating-point comparison because `bash` has no native float arithmetic. `jq` may emit scientific notation for very large values; `awk`'s `tonumber` equivalent (implicit via arithmetic context) handles it.
- Assertion (d) tolerates two plausible response shapes (top-level array or `{portfolios: [...]}` wrapper). This is defensive against schema drift; on the current API, `GET /api/portfolio` returns a top-level array of portfolio DTOs.
- The script does NOT attempt retry loops on assertion failures. If verification fails, the signal is "seeding regressed" and the operator must investigate, not retry blindly. (The seed step already has aggressive retries for transient 5xx via `seedFetch`.)
- No secrets are logged. The JWT length is logged (`${#JWT}`) but not the token itself.

### Pitfalls and Mitigations

Things that could go wrong during implementation and how each is handled by this design:

1. **`ts-node` ESM vs CommonJS**: `frontend/package.json` does NOT set `"type": "module"`, and `frontend/tsconfig.json` has `"module": "esnext"` with `"esModuleInterop": true`. By default, `ts-node` (without `--esm`) transpiles to CommonJS; `require.main === module` is defined. Guard uses `typeof require !== "undefined"` outer check so an unexpected ESM invocation degrades gracefully to a no-op rather than a `ReferenceError`. If the team later adopts `"type": "module"`, add an ESM-equivalent check with `import.meta.url` — not required for Phase 1.
2. **Playwright default-export compatibility**: The guard is appended AFTER `export default globalSetup;`. Playwright imports the module and reads the default export; the side-effect `if (require.main === module) { ... }` evaluates to false during import (the module is not the main entrypoint), so nothing runs. Preservation verified by the `globalSetup` export-preservation unit test.
3. **GitHub Actions `if:` expression semantics**: `needs.X.result` is `'success'`, `'failure'`, `'cancelled'`, or `'skipped'`. A missing `needs` entry evaluates to `null`. `always()` is overly permissive. The new gates use explicit positive assertions (`== 'success'`) for every precedent, which is strictly more conservative than `always()`. This is the correct pattern per GitHub docs.
4. **Race condition between `seed` and `deploy`**: Impossible once seeding lives in `deploy-azure.yml` with `needs: [preflight, deploy, deploy-frontend]` — GitHub Actions' job-ordering rules guarantee `seed` starts only after all three needs complete.
5. **Idempotency of seed endpoints**: Not newly required; the existing seed controllers already handle repeated invocations (wipe-and-reseed semantics per the golden-state seeder design). Phase 1 invokes each endpoint once per run, and a retried workflow (e.g. Re-run-failed-jobs) will re-invoke, which the controllers already tolerate.
6. **ACA cold starts**: Container Apps can scale to zero. After `deploy-frontend` completes, the api-gateway revision may still be cold. The existing `seedFetch` has `SEED_WARMUP_TIMEOUT_MS=60000` and `SEED_REQUEST_TIMEOUT_MS=70000` with 8 retries on non-local gateways, which accommodates ACA cold starts similarly to Lambda. The verify script uses `--max-time 30` with `--retry 3 --retry-delay 5`, giving ~95 s tolerance on a cold actuator/health. If ACA scale-to-zero proves too aggressive, tune `curl --max-time` or add a single pre-warm GET to `/actuator/health` before the assertion block — not required for the initial fix.
7. **Verify runs before seeded data is visible (eventual consistency)**: `/api/internal/portfolio/seed` writes synchronously to Postgres before returning 200; market-data writes to MongoDB; insight evicts the Redis cache. There is no async propagation delay — the seed endpoints' 200 responses are causally ordered with the DB writes. No extra wait is needed between `seed` and `verify`.
8. **Secret drift on `E2E_TEST_USER_EMAIL` / `E2E_TEST_USER_PASSWORD`**: The verify script requires these two secrets, which already exist (used by AWS synthetic). If they're missing on Azure runs, the script's `:?` expansion fails fast with a clear message. No silent fallback.
9. **`npx ts-node` network dependency**: `ts-node` is not in `frontend/package.json` today. `npx` fetches it on first use. If GitHub's npm proxy is transiently unavailable, the seed step will fail with a clear npx error rather than silently no-op. Acceptable for Phase 1; optional hardening is to pin `ts-node` in devDependencies (noted in Fix Implementation §3b).
10. **Removing the Azure seed step from `ci-verification.yml` is not a breaking change for PR builds**: PRs never set `vars.CLOUD_PROVIDER=azure` in typical org configuration, so the step was a no-op on PRs to begin with. Pushing the removal on a PR branch should not change any observable behavior of PR CI.

### Out of Scope (Deferred to Phase 2)

The following issues from the audit are deliberately not addressed in this design, per bugfix.md §Introduction:

- Hardcoded `NEXT_PUBLIC_API_BASE_URL` and the unused `Resolve API Gateway FQDN` step (audit §1.5).
- Dedicated Azure synthetic monitoring workflow (audit §1.4 / §4.3).
- Legacy workflow rationalization — `ci.yml`, `cd.yml`, `frontend-cd.yml`, `frontend-e2e-integration.yml`, `synthetic-monitoring.yml` (audit §1.7).
- `InfrastructureHealthLogger` parity for `portfolio-service` and `market-data-service` (audit §4.4).
- Azure log verification runbooks (audit §3).

These are tracked separately and are not required to resolve the four defects this spec covers.
