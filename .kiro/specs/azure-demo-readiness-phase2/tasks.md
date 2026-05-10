# Implementation Plan: Azure Demo Readiness Phase 2

## Overview

This plan implements six discrete improvements to the Azure deployment pipeline and observability posture: a Playwright-based Azure synthetic monitoring suite, workflow integration for continuous monitoring, dead FQDN resolution step removal, legacy workflow disablement, Spring `@Profile` widening for infrastructure health logging, and Terraform import block cleanup. All changes assume Phase 1 is merged.

## Tasks

- [ ] 1. Create Azure Synthetic Monitoring Suite
  - [ ] 1.1 Create `azure-api-smoke.spec.ts` with API-level HTTP assertions
    - Create `frontend/tests/e2e/azure-synthetic/azure-api-smoke.spec.ts`
    - Implement health check test: `GET /actuator/health` returns HTTP 200 within 70s timeout
    - Implement login test: `POST /api/auth/login` with credentials from `APP_AUTH_EMAIL`/`E2E_TEST_USER_EMAIL` and `APP_AUTH_PASSWORD`/`E2E_TEST_USER_PASSWORD` env vars, assert non-empty `token` string in response
    - Implement portfolio seed test: `POST /api/internal/portfolio/seed` with `X-Internal-Api-Key` header, assert `holdingsInserted >= 160` or existing data (HTTP 200)
    - Implement market data seed test: `POST /api/internal/market-data/seed` with `X-Internal-Api-Key` header, assert `pricesUpserted >= 160` or existing data (HTTP 200)
    - Implement portfolio data test: `GET /api/portfolio` with Bearer token, assert at least one portfolio with non-empty `holdings` array
    - Implement portfolio summary test: `GET /api/portfolio/summary` with Bearer token, assert total value > 0
    - Use Playwright `request` API context (not `page`) for all API assertions
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [ ] 1.2 Create `azure-frontend-smoke.spec.ts` with frontend page load assertion
    - Create `frontend/tests/e2e/azure-synthetic/azure-frontend-smoke.spec.ts`
    - Implement page load test: `GET https://vibhanshu-ai-portfolio.dev` returns HTTP 200
    - Use `page` context for the frontend page load test
    - _Requirements: 1.7_

  - [ ] 1.3 Create `README.md` for the Azure synthetic suite
    - Create `frontend/tests/e2e/azure-synthetic/README.md`
    - Mirror structure and content style of `frontend/tests/e2e/aws-synthetic/README.md`
    - Document environment variables, test descriptions, and execution instructions
    - _Requirements: 1.8_

  - [ ] 1.4 Update Playwright configuration to add `azure-synthetic` project
    - Add `azure-synthetic` project definition in `frontend/playwright.config.ts`
    - Set `testDir` to `./tests/e2e/azure-synthetic`
    - Set `baseURL` to `https://api.vibhanshu-ai-portfolio.dev`
    - Set test timeout to 120,000ms
    - Ensure serial execution mode (workers: 1)
    - No `webServer` configuration (tests run against live deployment)
    - Update `testIgnore` on `chromium` project to also ignore `azure-synthetic/`
    - Do NOT modify the `aws-synthetic` project configuration
    - _Requirements: 1.9, 1.10, 1.11_

- [ ] 2. Checkpoint - Verify synthetic suite structure
  - Ensure `npx playwright test --list --project=azure-synthetic` lists all expected tests
  - Ensure no files in `frontend/tests/e2e/aws-synthetic/` were modified
  - Ask the user if questions arise.

- [ ] 3. Integrate Synthetic Monitoring into Workflows
  - [ ] 3.1 Add Azure synthetic job to `synthetic-monitoring.yml`
    - Add `run-azure-synthetic-tests` job gated on `vars.CLOUD_PROVIDER == 'azure'`
    - Target `https://api.vibhanshu-ai-portfolio.dev` (API) and `https://vibhanshu-ai-portfolio.dev` (frontend)
    - Support both `workflow_dispatch` and `schedule` (cron `0 * * * *`) triggers
    - Upload Playwright HTML report as artifact (7-day retention) on failure
    - Leave existing `run-synthetic-tests` job unchanged with `if: vars.CLOUD_PROVIDER == 'aws'`
    - _Requirements: 2.1, 2.2, 2.3, 2.6_

  - [ ] 3.2 Add Azure synthetic step to `ci-verification.yml`
    - Add Azure synthetic step in `docker-build-verify` job after Docker Compose E2E tests
    - Gate on: `github.event_name == 'push' && github.ref == 'refs/heads/main' && vars.CLOUD_PROVIDER == 'azure'`
    - Set environment variables: `NEXT_PUBLIC_API_BASE_URL=https://api.vibhanshu-ai-portfolio.dev`, `BASE_URL=https://vibhanshu-ai-portfolio.dev`, `SKIP_BACKEND_HEALTH_CHECK="true"`
    - Run: `npx playwright test --project=azure-synthetic --reporter=list`
    - _Requirements: 2.4, 2.5_

- [ ] 4. Remove Dead FQDN Resolution Step from Deploy Workflow
  - [ ] 4.1 Clean up `deploy-azure.yml` deploy-frontend job
    - Remove the `Resolve API Gateway FQDN` step (step id `api_fqdn`) from the `deploy-frontend` job
    - Ensure `NEXT_PUBLIC_API_BASE_URL` in the `Build Next.js static export` step is set to hardcoded `https://api.vibhanshu-ai-portfolio.dev`
    - Add YAML comment on the line immediately above the `NEXT_PUBLIC_API_BASE_URL` assignment: `# Source of truth: custom domain https://api.vibhanshu-ai-portfolio.dev (permanent post-DNS-cutover)`
    - Do NOT modify the `deploy` job, `preflight` job, or any other job
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [ ] 5. Rationalize Duplicate and Legacy Workflows
  - [ ] 5.1 Disable `ci.yml` workflow
    - Set `on:` trigger to `workflow_dispatch` only with required `reason` input of type string
    - Add YAML comment block before `on:` key stating superseded by `ci-verification.yml` with one-sentence reason
    - Remove all other trigger events (push, PR, schedule)
    - Do NOT delete the file
    - _Requirements: 4.1, 4.4, 4.6_

  - [ ] 5.2 Disable `frontend-cd.yml` workflow
    - Set `on:` trigger to `workflow_dispatch` only with required `reason` input of type string
    - Add YAML comment block before `on:` key stating superseded by `ci-verification.yml` with one-sentence reason
    - Remove all other trigger events
    - Do NOT delete the file
    - _Requirements: 4.2, 4.4, 4.6_

  - [ ] 5.3 Disable `frontend-e2e-integration.yml` workflow
    - Set `on:` trigger to `workflow_dispatch` only with required `reason` input of type string
    - Add YAML comment block before `on:` key stating superseded by `ci-verification.yml` with one-sentence reason
    - Remove all other trigger events
    - Do NOT delete the file
    - _Requirements: 4.3, 4.4, 4.6_

  - [ ] 5.4 Verify `cd.yml` is already disabled and unchanged
    - Confirm `cd.yml` already has `workflow_dispatch`-only trigger with required `reason` input
    - Do NOT modify `cd.yml`
    - _Requirements: 4.5_

- [ ] 6. Checkpoint - Verify workflow changes
  - Ensure all workflow YAML files are valid
  - Ensure disabled workflows only have `workflow_dispatch` trigger
  - Ensure `deploy-azure.yml` no longer contains the `Resolve API Gateway FQDN` step
  - Ask the user if questions arise.

- [ ] 7. Widen InfrastructureHealthLogger Profile Annotations
  - [ ] 7.1 Update `portfolio-service` InfrastructureHealthLogger
    - Change `@Profile("aws")` to `@Profile({"aws", "azure"})` in `portfolio-service/src/main/java/com/wealth/portfolio/InfrastructureHealthLogger.java`
    - Update Javadoc to reflect the bean activates under both `aws` and `azure` profiles for Log Analytics parity
    - Do NOT make any other code changes beyond annotation and Javadoc
    - _Requirements: 5.1, 5.3, 5.4_

  - [ ] 7.2 Update `market-data-service` InfrastructureHealthLogger
    - Change `@Profile("aws")` to `@Profile({"aws", "azure"})` in `market-data-service/src/main/java/com/wealth/market/InfrastructureHealthLogger.java`
    - Update Javadoc to reflect the bean activates under both `aws` and `azure` profiles for Log Analytics parity
    - Do NOT make any other code changes beyond annotation and Javadoc
    - _Requirements: 5.2, 5.3, 5.4_

  - [ ] 7.3 Write unit tests verifying InfrastructureHealthLogger bean instantiation under `azure` profile
    - Add or update Spring Boot context tests with `@ActiveProfiles("azure")` for both services
    - Verify bean is instantiated and executes infrastructure connectivity probes
    - Run `./gradlew :portfolio-service:test :market-data-service:test` to confirm
    - _Requirements: 5.3_

- [ ] 8. Remove No-Op Terraform Import Blocks
  - [ ] 8.1 Remove import blocks and associated comments from `main.tf`
    - Remove 4 Container App import blocks (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`) from `infrastructure/terraform/azure/main.tf`
    - Remove 4 AcrPull role assignment import blocks (same four services)
    - Remove section separator comments and descriptive comments that pertain solely to the import blocks
    - Do NOT alter any `resource`, `module`, `variable`, `output`, `locals`, `data`, or `provider` blocks
    - Ensure the file remains valid HCL that passes `terraform fmt` and `terraform validate`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [ ] 9. Final checkpoint - Verify all changes
  - Ensure `npx playwright test --list --project=azure-synthetic` lists expected tests
  - Ensure `terraform fmt` and `terraform validate` pass in `infrastructure/terraform/azure/`
  - Ensure `./gradlew :portfolio-service:test :market-data-service:test` passes
  - Ensure all workflow YAML files are syntactically valid
  - Ensure no files in `frontend/tests/e2e/aws-synthetic/` were modified
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- No property-based tests are included — the design explicitly states PBT is not applicable (feature is IaC, CI/CD config, and integration tests with no pure functions)
- The `aws-synthetic` project and its files must remain completely untouched
- `cd.yml` is already disabled and must not be modified

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "5.1", "5.2", "5.3", "5.4", "7.1", "7.2", "8.1"] },
    { "id": 1, "tasks": ["1.4", "4.1", "7.3"] },
    { "id": 2, "tasks": ["3.1", "3.2"] }
  ]
}
```
