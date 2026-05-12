# Requirements Document

## Introduction

Phase 2 of the Azure deployment audit remediation addresses the remaining medium and low-priority items from the Azure deployment audit (`docs/audit/azure-deployment-audit-2026-05-10.md`). The goal is to improve observability via live synthetic monitoring, reduce CI noise by rationalizing duplicate workflows, establish a single source of truth for the frontend API URL in the deploy workflow, widen remaining `@Profile` annotations for Azure Log Analytics parity, and remove no-op Terraform import blocks.

Phase 1 (`azure-demo-readiness-phase1`) handles the critical seeding fix, deploy gating, and post-seed verification. Phase 2 assumes Phase 1 is already merged.

## Glossary

- **Synthetic_Suite**: A Playwright test project containing assertions that run against the live Azure deployment to verify end-to-end system health.
- **Workflow_Disablement**: Changing a GitHub Actions workflow trigger from automatic (push/PR/schedule) to `workflow_dispatch` only, with a required `reason` input, while retaining the file for historical reference.
- **FQDN_Resolution_Step**: The `Resolve API Gateway FQDN` step in `deploy-azure.yml` that queries Azure for the Container App ingress hostname.
- **Import_Block**: A Terraform `import {}` declarative block used to bring existing cloud resources into Terraform state management.
- **InfrastructureHealthLogger**: A Spring Boot component that probes infrastructure dependencies on startup and logs structured `[INFRA-OK]`/`[INFRA-FAIL]` lines for observability.
- **CI_Verification_Workflow**: The canonical CI pipeline defined in `.github/workflows/ci-verification.yml`.
- **Deploy_Azure_Workflow**: The Azure deployment pipeline defined in `.github/workflows/deploy-azure.yml`.
- **Synthetic_Monitoring_Workflow**: The scheduled/manual monitoring pipeline defined in `.github/workflows/synthetic-monitoring.yml`.

## Requirements

### Requirement 1: Azure Live Synthetic Monitoring Suite

**User Story:** As a platform engineer, I want a Playwright-based synthetic monitoring suite for the Azure deployment, so that I can detect regressions in the live Azure environment with the same coverage as the existing AWS synthetic suite.

#### Acceptance Criteria

1. WHEN the Synthetic_Suite is executed against the live Azure deployment, THE Synthetic_Suite SHALL assert that `GET https://api.vibhanshu-ai-portfolio.dev/actuator/health` returns HTTP status 200 within a per-request timeout of 70 seconds.
2. WHEN the Synthetic_Suite is executed against the live Azure deployment, THE Synthetic_Suite SHALL assert that `POST /api/auth/login` with valid E2E credentials (sourced from `APP_AUTH_EMAIL`/`E2E_TEST_USER_EMAIL` and `APP_AUTH_PASSWORD`/`E2E_TEST_USER_PASSWORD` environment variables) returns a response containing a non-empty `token` string field.
3. WHEN the Synthetic_Suite is executed against the live Azure deployment, THE Synthetic_Suite SHALL assert that `POST /api/internal/portfolio/seed` with the `X-Internal-Api-Key` header returns a response indicating `holdingsInserted >= 160` or confirms that portfolio data already exists (HTTP 200 with existing data).
4. WHEN the Synthetic_Suite is executed against the live Azure deployment, THE Synthetic_Suite SHALL assert that `POST /api/internal/market-data/seed` with the `X-Internal-Api-Key` header returns a response indicating `pricesUpserted >= 160` or confirms that market data already exists (HTTP 200 with existing data).
5. WHEN the Synthetic_Suite is executed against the live Azure deployment, THE Synthetic_Suite SHALL assert that `GET /api/portfolio` with a valid Bearer token returns at least one portfolio object with a non-empty `holdings` array.
6. WHEN the Synthetic_Suite is executed against the live Azure deployment, THE Synthetic_Suite SHALL assert that `GET /api/portfolio/summary` with a valid Bearer token returns a response with a total value greater than zero.
7. WHEN the Synthetic_Suite is executed against the live Azure deployment, THE Synthetic_Suite SHALL assert that a page load against `https://vibhanshu-ai-portfolio.dev` returns HTTP status 200.
8. THE Synthetic_Suite SHALL be located at `frontend/tests/e2e/azure-synthetic/` and follow the same directory structure as `frontend/tests/e2e/aws-synthetic/`.
9. THE Synthetic_Suite SHALL be configured as a Playwright project named `azure-synthetic` in the Playwright configuration, with a test timeout of 120 seconds per test and serial execution mode.
10. THE Synthetic_Suite SHALL reuse the `globalSetup` function from `frontend/tests/e2e/global-setup.ts` (or the Phase 1 dedicated seed entrypoint) for seeding, with `SKIP_GOLDEN_STATE_SEEDING=true` supported when seeding is handled externally by the deploy workflow's seed job.
11. THE Synthetic_Suite SHALL NOT modify any files within `frontend/tests/e2e/aws-synthetic/` or alter the `aws-synthetic` Playwright project configuration.

### Requirement 2: Synthetic Monitoring Workflow Integration

**User Story:** As a platform engineer, I want the Azure synthetic suite to run both on schedule and as part of CI, so that I have continuous observability of the live Azure environment.

#### Acceptance Criteria

1. WHEN `vars.CLOUD_PROVIDER` equals `azure`, THE Synthetic_Monitoring_Workflow SHALL execute the Azure Synthetic_Suite located at `frontend/tests/e2e/azure-synthetic/` against the live Azure deployment at `https://api.vibhanshu-ai-portfolio.dev` (API) and `https://vibhanshu-ai-portfolio.dev` (frontend).
2. WHEN `vars.CLOUD_PROVIDER` equals `aws`, THE Synthetic_Monitoring_Workflow SHALL execute the existing `run-synthetic-tests` job with its current steps, environment variables, and `if: vars.CLOUD_PROVIDER == 'aws'` gating condition unchanged.
3. THE Synthetic_Monitoring_Workflow SHALL support both `workflow_dispatch` and `schedule` (cron `0 * * * *`, hourly) triggers for the Azure job, matching the existing AWS job trigger configuration.
4. WHEN a push to `main` occurs and `vars.CLOUD_PROVIDER` equals `azure`, THE CI_Verification_Workflow SHALL execute the Azure Synthetic_Suite as a step after the Docker Compose E2E tests pass, with environment variables `NEXT_PUBLIC_API_BASE_URL` set to `https://api.vibhanshu-ai-portfolio.dev`, `BASE_URL` set to `https://vibhanshu-ai-portfolio.dev`, and `SKIP_BACKEND_HEALTH_CHECK` set to `"true"`.
5. THE CI_Verification_Workflow SHALL gate the Azure synthetic step on `github.event_name == 'push'` and `github.ref == 'refs/heads/main'` and `vars.CLOUD_PROVIDER == 'azure'`, using the same `if:` conditional pattern as the existing AWS synthetic step.
6. IF the Azure Synthetic_Suite execution fails in the Synthetic_Monitoring_Workflow, THEN THE Synthetic_Monitoring_Workflow SHALL upload the Playwright HTML report as a GitHub Actions artifact with a retention period of 7 days.

### Requirement 3: Remove Dead FQDN Resolution Step

**User Story:** As a developer, I want the `deploy-azure.yml` workflow to have a single, clear source of truth for `NEXT_PUBLIC_API_BASE_URL`, so that there is no confusion about which value is used and no dead code in the pipeline.

#### Acceptance Criteria

1. THE Deploy_Azure_Workflow SHALL NOT contain the `Resolve API Gateway FQDN` step (step id `api_fqdn`) in the `deploy-frontend` job.
2. THE Deploy_Azure_Workflow SHALL set the `NEXT_PUBLIC_API_BASE_URL` environment variable in the `Build Next.js static export` step to the hardcoded value `https://api.vibhanshu-ai-portfolio.dev`.
3. THE Deploy_Azure_Workflow SHALL contain a YAML comment on the line immediately above the `NEXT_PUBLIC_API_BASE_URL` assignment in the `Build Next.js static export` step stating that the custom domain `https://api.vibhanshu-ai-portfolio.dev` is the permanent source of truth post-DNS-cutover.
4. THE Deploy_Azure_Workflow SHALL NOT modify the `deploy` job, the `preflight` job, or any other job outside of `deploy-frontend`.

### Requirement 4: Rationalize Duplicate and Legacy Workflows

**User Story:** As a platform engineer, I want legacy and duplicate workflows disabled via `workflow_dispatch`-only triggers, so that CI noise is reduced and the canonical pipeline path is unambiguous.

#### Acceptance Criteria

1. THE `ci.yml` workflow file SHALL have its `on:` trigger set to `workflow_dispatch` only, with no other trigger events present, and SHALL include a required `reason` input of type string.
2. THE `frontend-cd.yml` workflow file SHALL have its `on:` trigger set to `workflow_dispatch` only, with no other trigger events present, and SHALL include a required `reason` input of type string.
3. THE `frontend-e2e-integration.yml` workflow file SHALL have its `on:` trigger set to `workflow_dispatch` only, with no other trigger events present, and SHALL include a required `reason` input of type string.
4. WHEN a workflow is disabled, THE disabled workflow file SHALL contain a YAML comment block before the `on:` key that states the name of the superseding workflow file and a one-sentence reason for disabling.
5. THE `cd.yml` workflow SHALL retain its existing `workflow_dispatch`-only trigger with a required `reason` input and SHALL NOT be modified as part of this change.
6. THE disabled workflow files (`ci.yml`, `frontend-cd.yml`, `frontend-e2e-integration.yml`, `cd.yml`) SHALL NOT be deleted from the repository.

### Requirement 5: Widen InfrastructureHealthLogger Profile Annotations

**User Story:** As a platform engineer, I want the `InfrastructureHealthLogger` beans in `portfolio-service` and `market-data-service` to activate under the `azure` profile, so that Azure Log Analytics receives the same structured infrastructure health logs as AWS CloudWatch.

#### Acceptance Criteria

1. THE `portfolio-service` `InfrastructureHealthLogger` class SHALL have its `@Profile` annotation set to `@Profile({"aws", "azure"})`.
2. THE `market-data-service` `InfrastructureHealthLogger` class SHALL have its `@Profile` annotation set to `@Profile({"aws", "azure"})`.
3. WHEN the `azure` Spring profile is active, THE `InfrastructureHealthLogger` bean in each service SHALL be instantiated and execute its infrastructure connectivity probes on `ApplicationReadyEvent`, producing the same `[INFRA-OK]` and `[INFRA-FAIL]` log output as when the `aws` profile is active.
4. THE `InfrastructureHealthLogger` classes SHALL NOT have any code changes beyond the `@Profile` annotation value and corresponding Javadoc updates that reflect the widened profile scope.

### Requirement 6: Remove No-Op Terraform Import Blocks

**User Story:** As a platform engineer, I want the no-op `import {}` blocks removed from `infrastructure/terraform/azure/main.tf`, so that the Terraform configuration is concise and free of stale one-time-use directives.

#### Acceptance Criteria

1. THE `infrastructure/terraform/azure/main.tf` file SHALL NOT contain any `import {}` blocks for Container App resources (`module.api_gateway.azurerm_container_app.this`, `module.portfolio_service.azurerm_container_app.this`, `module.market_data_service.azurerm_container_app.this`, `module.insight_service.azurerm_container_app.this`).
2. THE `infrastructure/terraform/azure/main.tf` file SHALL NOT contain any `import {}` blocks for AcrPull role assignment resources (`module.api_gateway.azurerm_role_assignment.acr_pull`, `module.portfolio_service.azurerm_role_assignment.acr_pull`, `module.market_data_service.azurerm_role_assignment.acr_pull`, `module.insight_service.azurerm_role_assignment.acr_pull`).
3. THE removal SHALL NOT alter any `resource`, `module`, `variable`, `output`, `locals`, `data`, or `provider` blocks, nor any comment lines associated with those blocks, in the file.
4. IF separator comment lines and descriptive comment lines exist immediately above the removed `import {}` blocks and pertain solely to documenting those import blocks, THEN THE system SHALL also remove those separator and descriptive comment lines.
5. WHEN all `import {}` blocks and their associated section comments have been removed, THE file SHALL remain valid HCL that passes `terraform fmt` and `terraform validate` without errors.
