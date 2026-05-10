# Bugfix Requirements Document

## Introduction

The live Azure deployment at `https://vibhanshu-ai-portfolio.dev/` shows no portfolio, market, or insight data even though `deploy-azure.yml` reports green and the Flyway V10 migration correctly provisions the E2E demo user. Four defects in the CI/CD layer combine to produce that outcome:

1. The Azure seed step in `.github/workflows/ci-verification.yml` runs `npx ts-node --project tsconfig.json tests/e2e/global-setup.ts`. That module ends with `export default globalSetup;` and has no top-level invocation or `require.main === module` guard, so running it directly loads the module and exits without calling `globalSetup()`. The step goes green while making zero calls to `/api/internal/portfolio/seed`, `/api/internal/market-data/seed`, or `/api/internal/insight/seed` against the live Azure stack. Audit §1.2.
2. That same seed step lives in `ci-verification.yml`, not `deploy-azure.yml`. It fires on every push to `main` (including docs-only) and can race `deploy-azure.yml`, while manual `workflow_dispatch` runs of `deploy-azure.yml` never seed at all. Audit §1.3.
3. The `deploy-frontend` job in `.github/workflows/deploy-azure.yml` uses `needs: [preflight, deploy]` with the gate `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && always()`. The `always()` clause lets the frontend ship even when the backend `deploy` matrix job failed. Audit §1.6.
4. Nothing verifies after seeding that demo data is actually queryable through the live gateway, so any silent seeding regression would still report a green deploy and an empty demo. Audit §4 (P0/P1 verify).

This spec covers only those four conditions. Explicitly out of scope for Phase 1 and deferred to Phase 2: the hardcoded `NEXT_PUBLIC_API_BASE_URL` and dead `Resolve API Gateway FQDN` step (audit §1.5), Azure synthetic monitoring (§1.4 / §4.3), legacy workflow rationalization (§1.7), `InfrastructureHealthLogger` parity for portfolio-service and market-data-service (§4.4), and Azure log verification runbooks (§3).

Source references:

- `docs/audit/azure-deployment-audit-2026-05-10.md` — audit identifying these defects
- `docs/changes/CHANGES_PHASE4_SUMMARY_2026-05-10.md` — DNS cutover and env-var wiring context
- `.github/workflows/ci-verification.yml` — current (wrong) home of the Azure seed step
- `.github/workflows/deploy-azure.yml` — correct target for seeding and the frontend gate fix
- `frontend/tests/e2e/global-setup.ts` — module whose default export is never invoked by direct `ts-node` execution
- `portfolio-service/src/main/resources/db/migration/V10__Seed_E2E_Test_User.sql` — Flyway seed establishing the demo user ID `00000000-0000-0000-0000-000000000e2e`
- `api-gateway/src/main/java/com/wealth/gateway/AuthController.java` — gateway login signs a JWT for `APP_AUTH_USER_ID` after comparing to `APP_AUTH_EMAIL` / `APP_AUTH_PASSWORD` (it does not validate the V10 `ba_account` scrypt hash, audit §2.4)
- `portfolio-service/src/main/java/com/wealth/portfolio/seed/InternalApiKeyFilter.java` — gates `/api/internal/**` on `X-Internal-Api-Key`; a blank key returns `503 internal_api_key_not_configured`

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN `ci-verification.yml`'s "Seed live Azure environment" step runs `npx ts-node --project tsconfig.json tests/e2e/global-setup.ts` against the live Azure stack THEN the system loads `global-setup.ts`, evaluates `export default globalSetup`, exits cleanly, and reports the step as successful while making zero calls to `POST /api/internal/portfolio/seed`, `POST /api/internal/market-data/seed`, or `POST /api/internal/insight/seed`.

1.2 WHEN a push to `main` matches `ci-verification.yml`'s trigger (including docs-only pushes) AND `vars.CLOUD_PROVIDER == 'azure'` THEN the system runs the Azure seed step from `ci-verification.yml` regardless of whether a deployment is in progress, and because `ci-verification.yml` and `deploy-azure.yml` fire on the same push event, seeding can run in parallel with (or ahead of) the deploy that is supposed to precede it.

1.3 WHEN `deploy-azure.yml` is triggered manually via `workflow_dispatch` THEN the system does not run Azure seeding at all, because seeding is owned by `ci-verification.yml`, which is not triggered by `workflow_dispatch` on `deploy-azure.yml`.

1.4 WHEN the backend `deploy` matrix job in `deploy-azure.yml` fails or is cancelled for any of the four services (api-gateway, portfolio-service, market-data-service, insight-service) AND `preflight` succeeded with `infra_ready == 'true'` THEN the system still runs the `deploy-frontend` job, because the gate is `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true' && always()` with no check on `needs.deploy.result`, so a failed backend deploy can still ship a frontend against a broken backend.

1.5 WHEN Azure seeding completes against the live stack THEN the system reports the workflow as successful without verifying through the live custom domain that `/actuator/health` is 200, that `POST /api/auth/login` returns a JWT, that `GET /api/portfolio` returns at least one portfolio with non-empty holdings, or that `GET /api/portfolio/summary` returns a non-zero total, so a silent seeding regression is indistinguishable from a successful run.

### Expected Behavior (Correct)

2.1 WHEN the Azure seed runs against the live Azure stack THEN the system SHALL actually invoke `globalSetup()` and await it to completion, so that `POST /api/internal/portfolio/seed`, `POST /api/internal/market-data/seed`, and `POST /api/internal/insight/seed` are each called exactly once, and the step SHALL exit with a non-zero status if any non-transient error is returned by those endpoints.

2.2 WHEN a push to `main` matches `ci-verification.yml`'s trigger AND `vars.CLOUD_PROVIDER == 'azure'` THEN the system SHALL NOT run any Azure seeding from `ci-verification.yml`; the Azure seed step SHALL be removed from `ci-verification.yml` (not duplicated), and Azure seeding SHALL run only from `deploy-azure.yml` in a dedicated job that declares `needs: [preflight, deploy, deploy-frontend]` and that executes only when all three of those jobs succeed.

2.3 WHEN `deploy-azure.yml` is triggered manually via `workflow_dispatch` AND `preflight`, `deploy`, and `deploy-frontend` all succeed THEN the system SHALL run Azure seeding as part of that same workflow run, producing a deterministic preflight → deploy → deploy-frontend → seed → verify sequence.

2.4 WHEN the backend `deploy` matrix job in `deploy-azure.yml` fails or is cancelled for any service THEN the system SHALL skip `deploy-frontend` by adding `needs.deploy.result == 'success'` to its gate and removing `always()`, so a failed backend deploy never ships a frontend against a broken backend.

2.5 WHEN Azure seeding completes successfully THEN the system SHALL run a post-seed verification step targeting the live custom domain `https://api.vibhanshu-ai-portfolio.dev` that asserts all of the following, failing the workflow red if any assertion fails:
  (a) `GET https://api.vibhanshu-ai-portfolio.dev/actuator/health` returns HTTP 200.
  (b) `POST https://api.vibhanshu-ai-portfolio.dev/api/auth/login` with the demo email/password returns HTTP 200 and a non-empty JWT in the response body.
  (c) `GET https://api.vibhanshu-ai-portfolio.dev/api/portfolio/summary` with `Authorization: Bearer <JWT>` returns HTTP 200 and a total value greater than zero.
  (d) `GET https://api.vibhanshu-ai-portfolio.dev/api/portfolio` with `Authorization: Bearer <JWT>` returns HTTP 200 and at least one portfolio whose holdings list is non-empty.

### Unchanged Behavior (Regression Prevention)

3.1 WHEN Playwright consumes the default export of `frontend/tests/e2e/global-setup.ts` during the `Run Playwright E2E tests` step in `ci-verification.yml` (local Docker Compose stack) THEN the system SHALL CONTINUE TO execute the existing health-check polling and golden-state seeding exactly as today, with no change to behavior when `globalSetup` is consumed as a Playwright default export.

3.2 WHEN Playwright consumes the default export of `frontend/tests/e2e/global-setup.ts` during the AWS synthetic project (gated on `github.event_name == 'push' && github.ref == 'refs/heads/main' && vars.CLOUD_PROVIDER == 'aws'`) THEN the system SHALL CONTINUE TO honor `SKIP_GOLDEN_STATE_SEEDING=true` and `SKIP_BACKEND_HEALTH_CHECK=true` with their current semantics, so AWS seeding continues to happen via the local Docker Compose E2E run rather than against the live AWS stack.

3.3 WHEN `ci-verification.yml` runs AND `vars.CLOUD_PROVIDER == 'aws'` THEN the system SHALL CONTINUE TO run `Pre-warm AWS Lambda stack` and `Run AWS synthetic monitoring` with their existing triggers, secrets, and environment variables unchanged.

3.4 WHEN the new Azure seed job runs THEN the system SHALL CONTINUE TO use the same environment-variable contract as the current (broken) step, with no change to secret names or values: `NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev`, `INTERNAL_API_KEY=${{ secrets.TF_VAR_INTERNAL_API_KEY }}`, `E2E_TEST_USER_ID=${{ secrets.E2E_TEST_USER_ID || '00000000-0000-0000-0000-000000000e2e' }}`, and `SKIP_BACKEND_HEALTH_CHECK: "true"`.

3.5 WHEN the system resolves the demo user ID across Flyway V10 inserts (`users`, `ba_user`, `ba_account`), the `APP_AUTH_USER_ID` Container App env var on api-gateway, the `E2E_TEST_USER_ID` GitHub Actions secret, and the `SEEDED_DEMO_USER_ID` default in `global-setup.ts` THEN the system SHALL CONTINUE TO use the single value `00000000-0000-0000-0000-000000000e2e` across all four sources. Phase 1 SHALL NOT change any of those values.

3.6 WHEN `deploy-azure.yml`'s `preflight` job determines that infrastructure is not provisioned (`infra_ready == 'false'`) THEN the system SHALL CONTINUE TO skip `deploy` and `deploy-frontend` cleanly with the existing warning message, and the new seed job and the new verification step SHALL also skip cleanly under the same condition.

3.7 WHEN `deploy-azure.yml`'s `preflight` and `deploy` jobs both succeed THEN the system SHALL CONTINUE TO run the existing `deploy-frontend` steps — Node.js setup, `npm ci`, the unchanged `Resolve API Gateway FQDN` step, `npm run build` with `NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev`, and SWA upload via `Azure/static-web-apps-deploy@v1` with `skip_app_build: true`, `app_location: "frontend/out"`, and `output_location: ""` — unchanged. The only modification to that job SHALL be its `if:` gate.

3.8 WHEN the post-seed verification step authenticates against the live gateway THEN the system SHALL CONTINUE TO authenticate via `POST /api/auth/login` with email and password (which the gateway compares against `APP_AUTH_EMAIL` / `APP_AUTH_PASSWORD` and signs a JWT for `APP_AUTH_USER_ID`), and SHALL NOT attempt to validate against the Better Auth `ba_account` scrypt hash provisioned by Flyway V10 directly.

3.9 WHEN any internal seed call is issued by the Phase 1 fix THEN the system SHALL CONTINUE TO send the `X-Internal-Api-Key` header with the value sourced from `secrets.TF_VAR_INTERNAL_API_KEY`, preserving the existing contract enforced by `InternalApiKeyFilter` in portfolio-service, market-data-service, and insight-service (blank key ⇒ 503 `internal_api_key_not_configured`; missing or wrong header ⇒ 403 `invalid_internal_api_key`).
