# Tasks

## Task Dependency Graph

```
Task 1 (exploration test) → Task 2 (entrypoint guard) → Task 8 (preservation tests)
                                                       ↗
Task 3 (remove ci-verification seed) ─────────────────
Task 4 (fix deploy-frontend gate) ────────────────────→ Task 5 (seed job) → Task 6 (verify script) → Task 7 (verify job) → Task 8
```

---

- [x] 1. Write bug condition exploration test
  - [x] 1.1. Create a test script (`frontend/tests/e2e/global-setup-entrypoint.test.ts`) that spawns `npx ts-node --project tsconfig.json tests/e2e/global-setup.ts` as a child process with `NEXT_PUBLIC_API_BASE_URL=http://localhost:9999` (a non-existent server), `INTERNAL_API_KEY=test-key`, `SKIP_BACKEND_HEALTH_CHECK=true`, `E2E_TEST_USER_ID=00000000-0000-0000-0000-000000000e2e` and asserts that the process exits 0 WITHOUT making any HTTP requests (confirming the seed is never invoked). Capture stdout/stderr and verify no seeding log lines appear (no `Starting Golden State seeding` or `seedFetch` output).
  - [x] 1.2. Run the test against the UNFIXED code and confirm it passes (i.e. the bug condition is confirmed — the script exits 0 without seeding). Document the counterexample: "ts-node global-setup.ts exits 0 with no seed invocations."

- [x] 2. Add `require.main === module` entrypoint guard to `global-setup.ts`
  - [x] 2.1. Read the current `frontend/tests/e2e/global-setup.ts` to understand its structure and locate the `export default globalSetup;` line.
  - [x] 2.2. Append the entrypoint guard code AFTER `export default globalSetup;`: a `require.main === module` check that awaits `globalSetup()` and exits non-zero on error, with `typeof require !== "undefined"` outer defensive check. Exact code:
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
  - [x] 2.3. Verify the file still compiles cleanly: run `npx tsc --noEmit --project tsconfig.json` from `frontend/`.
  - [x] 2.4. Write a unit test (`frontend/tests/e2e/global-setup-export.test.ts`) that imports `globalSetup` from `./global-setup` and asserts `typeof globalSetup === 'function'` and `globalSetup.length === 0` — confirming the Playwright default-export contract is preserved.

- [x] 3. Remove Azure seed step from `ci-verification.yml`
  - [x] 3.1. Read `.github/workflows/ci-verification.yml` and locate the step named "Seed live Azure environment (vibhanshu-ai-portfolio.dev)" (gated on `vars.CLOUD_PROVIDER == 'azure'`) within the `docker-build-verify` job.
  - [x] 3.2. Delete that entire step block (the `- name: Seed live Azure environment ...` block including its `if:`, `working-directory:`, `env:`, and `run:` keys, plus the preceding comment `# ── Azure: Seed live domain after successful deployment ──`). Do NOT modify any other step, job-level env vars, or triggers.
  - [x] 3.3. Verify the YAML is still valid by parsing it with `python -c "import yaml; yaml.safe_load(open(...))"`.

- [x] 4. Fix `deploy-frontend` gate in `deploy-azure.yml`
  - [x] 4.1. Read `.github/workflows/deploy-azure.yml` and locate the `deploy-frontend` job's `if:` line.
  - [x] 4.2. Replace the existing gate `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && always()` with `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && needs.deploy.result == 'success'`. This drops `always()` and adds an explicit check that the backend deploy succeeded, so a failed backend never ships a frontend.
  - [x] 4.3. Verify the YAML is still valid after the change.

- [x] 5. Add `seed` job to `deploy-azure.yml`
  - [x] 5.1. Read the current `deploy-azure.yml` to understand the existing job structure and indentation style (two-space indent).
  - [x] 5.2. Add the `seed` job after `deploy-frontend` with:
    - `runs-on: ubuntu-latest`
    - `needs: [preflight, deploy, deploy-frontend]`
    - `if:` gate requiring all three needs to report `success` AND `needs.preflight.outputs.infra_ready == 'true'`
    - Steps: Checkout (`actions/checkout@v4`), Setup Node.js 22 (`actions/setup-node@v4` with npm cache for `frontend/package-lock.json`), `npm ci` in `frontend/`, and the seed step with env vars: `NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev`, `SKIP_BACKEND_HEALTH_CHECK: "true"`, `INTERNAL_API_KEY: ${{ secrets.TF_VAR_INTERNAL_API_KEY }}`, `E2E_TEST_USER_ID: ${{ secrets.E2E_TEST_USER_ID || '00000000-0000-0000-0000-000000000e2e' }}`
    - The seed step runs: `npx ts-node --project tsconfig.json tests/e2e/global-setup.ts`
  - [x] 5.3. Verify the YAML is still valid after the addition.

- [x] 6. Create `verify-azure-demo.sh` verification script
  - [x] 6.1. Create the directory `.github/workflows/scripts/` if it doesn't exist.
  - [x] 6.2. Write the verification script (`.github/workflows/scripts/verify-azure-demo.sh`) with the full body as specified in the design's "Verification Script" section. The script must:
    - Use `set -euo pipefail`
    - Require env vars: `API_BASE`, `DEMO_EMAIL`, `DEMO_PASSWORD` (fail fast with `:?` expansion if missing)
    - Assert (a): `GET $API_BASE/actuator/health` returns HTTP 200
    - Assert (b): `POST $API_BASE/api/auth/login` with email/password returns HTTP 200 and a non-empty `.token` in JSON response
    - Assert (c): `GET $API_BASE/api/portfolio/summary` with `Authorization: Bearer <JWT>` returns HTTP 200 and total value > 0 (check both `.totalValue` and `.total` for schema tolerance)
    - Assert (d): `GET $API_BASE/api/portfolio` with `Authorization: Bearer <JWT>` returns HTTP 200 and at least one portfolio whose holdings list is non-empty (tolerate both top-level array and `{portfolios:[...]}` wrapper)
    - Use `curl` with `--silent --show-error --fail-with-body --retry 3 --retry-delay 5 --max-time 30`
    - Use `jq` for JSON parsing
    - Use GitHub Actions `::group::` / `::endgroup::` annotations for collapsible output
    - Exit 1 with `::error::` annotation on any assertion failure
    - Do NOT log secrets (log JWT length but not the token itself)
  - [x] 6.3. Make the script executable: ensure it has a `#!/usr/bin/env bash` shebang and set executable permission.

- [x] 7. Add `verify` job to `deploy-azure.yml`
  - [x] 7.1. Add the `verify` job after `seed` with:
    - `runs-on: ubuntu-latest`
    - `needs: [preflight, seed]`
    - `if:` gate requiring `needs.preflight.result == 'success'`, `needs.preflight.outputs.infra_ready == 'true'`, and `needs.seed.result == 'success'`
    - `env:` block with `API_BASE: https://api.vibhanshu-ai-portfolio.dev`, `DEMO_EMAIL: ${{ secrets.E2E_TEST_USER_EMAIL }}`, `DEMO_PASSWORD: ${{ secrets.E2E_TEST_USER_PASSWORD }}`
    - Steps: Checkout (`actions/checkout@v4`), then run `bash .github/workflows/scripts/verify-azure-demo.sh`
  - [x] 7.2. Verify the YAML is still valid after the addition.

- [x] 8. Write preservation and fix-verification tests
  - [x] 8.1. Create a test that spawns `npx ts-node --project tests/e2e/tsconfig.e2e-test.json tests/e2e/global-setup.ts` with env vars pointing at a non-routable address and asserts that `globalSetup()` IS now invoked (seed requests are made). This confirms the entrypoint guard works on the FIXED code.
  - [x] 8.2. Write a test that programmatically `require()`s `global-setup.ts` (simulating Playwright's import) and asserts that `globalSetup()` is NOT automatically invoked — confirming the guard is a no-op during module import (preservation of Playwright contract).
  - [x] 8.3. Validate the `deploy-azure.yml` YAML structure: parse it and assert the job dependency graph is `preflight → deploy → deploy-frontend → seed → verify` with correct `needs` declarations.
  - [x] 8.4. Validate that `ci-verification.yml` no longer contains any step with "Seed live Azure environment" in its name.
  - [x] 8.5. Run `npx tsc --noEmit --project tsconfig.json` from `frontend/` to confirm no TypeScript compilation errors were introduced.
