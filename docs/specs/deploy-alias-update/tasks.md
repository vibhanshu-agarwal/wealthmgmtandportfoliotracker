# Implementation Plan

- [x] 1. Write bug condition exploration test
  - **Property 1: Bug Condition** - Alias Stale After Code Deploy
  - **CRITICAL**: This test MUST FAIL on unfixed code — failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior — it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the bug exists (deploy succeeds but alias remains stale)
  - **Scoped PBT Approach**: Scope the property to the concrete failing case — the 4 "Update <service> Lambda" steps in deploy.yml that call `update-function-code` but never call `publish-version` or `update-alias`
  - **Bug Condition from design**: `isBugCondition(X) = X.updateFunctionCodeSucceeded = true AND X.publishVersionCalled = false`
  - **Test approach**: Write a shell-based validation script that parses the deploy.yml workflow file and asserts that each "Update <service> Lambda" step contains `publish-version` AND `update-alias` commands after `update-function-code`
  - For each of the 4 service update steps (api-gateway, portfolio-service, market-data-service, insight-service):
    - Assert the step contains `aws lambda publish-version` after `update-function-code`
    - Assert the step contains `aws lambda update-alias --function-name` after `publish-version`
    - Assert a post-update wait loop exists between `update-function-code` and `publish-version`
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (confirms the bug — no publish-version or update-alias present)
  - Document counterexamples: "All 4 update steps call update-function-code but lack publish-version and update-alias"
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2_

- [x] 2. Write preservation property tests (BEFORE implementing fix)
  - **Property 2: Preservation** - Non-Alias Workflow Behavior Unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - **Test approach**: Write validation scripts that capture baseline properties of the unfixed workflow and Terraform config
  - Observe on UNFIXED code:
    - ECR image push steps tag with both `latest` and `${IMAGE_TAG_SHA}` (commit SHA)
    - Pre-update polling loop (wait for `LastUpdateStatus = Successful` before `update-function-code`) exists in all 4 steps
    - Skip-if-not-exists check (`get-function-configuration` + early exit) exists in all 4 steps
    - No `update-function-configuration` call exists anywhere in deploy.yml
    - Docker build commands include `--platform linux/amd64`, `--provenance=false`, `--sbom=false`
    - Frontend deploy steps (S3 sync, CloudFront invalidation) are present and unchanged
    - Terraform alias resources (`aws_lambda_alias`) exist for all 4 services with correct `function_name` and `function_version` references
  - Write property-based assertions for each observed behavior:
    - For all 4 service steps: pre-update wait loop pattern is present
    - For all 4 service steps: skip-if-not-exists pattern is present
    - For all image build steps: dual tagging (`latest` + SHA) is present
    - For entire deploy.yml: zero occurrences of `update-function-configuration`
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (confirms baseline behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 3. Terraform infrastructure fix — Add lifecycle blocks to alias resources (MUST BE MERGED FIRST)
  - [x] 3.1 Add `lifecycle { ignore_changes = [function_version] }` to all 4 alias resources
    - File: `infrastructure/terraform/modules/compute/main.tf`
    - Add lifecycle block to `aws_lambda_alias.api_gateway_live`
    - Add lifecycle block to `aws_lambda_alias.portfolio_live`
    - Add lifecycle block to `aws_lambda_alias.market_data_live`
    - Add lifecycle block to `aws_lambda_alias.insight_live`
    - Pattern matches existing `lifecycle { ignore_changes = [image_uri] }` on Lambda function resources
    - This prevents `terraform apply` from reverting alias version after deploy.yml updates it
    - **IMPORTANT**: This change MUST be merged and applied via `terraform apply` BEFORE the deploy.yml changes run, to prevent a rollback race condition where Terraform reverts the alias mid-deploy
    - _Bug_Condition: isBugCondition(X) where Terraform reverts alias function_version on next apply_
    - _Expected_Behavior: terraform plan shows no pending alias changes after deploy.yml updates the alias_
    - _Preservation: Existing alias resource attributes (name, function_name, function_version reference) remain unchanged; only lifecycle meta-argument is added_
    - _Requirements: 2.1, 2.2, 2.3, 3.5_

  - [x] 3.2 Validate Terraform changes
    - Run `terraform fmt` to ensure formatting is correct
    - Run `terraform validate` to ensure syntax is valid
    - Verify that `terraform plan` shows only the lifecycle block additions (no destructive changes)
    - Confirm no other resources are affected
    - _Requirements: 3.5_

- [x] 4. Application deployment fix — Add publish-version + update-alias to deploy.yml (runs AFTER Terraform is applied)
  - [x] 4.1 Add post-update wait loop + publish-version + update-alias to api-gateway update step
    - File: `.github/workflows/deploy.yml`
    - After the existing `aws lambda update-function-code` call, add:
      1. Post-update wait loop (30 attempts, 5s sleep, abort on Failed, timeout on 30)
      2. `aws lambda publish-version --function-name "$LAMBDA_FUNCTION_NAME" --query 'Version' --output text`
      3. `aws lambda update-alias --function-name "$LAMBDA_FUNCTION_NAME" --name live --function-version "$NEW_VERSION"`
    - Preserve existing pre-update wait loop and skip-if-not-exists check
    - _Bug_Condition: isBugCondition(X) where X = api-gateway update step with no publish-version_
    - _Expected_Behavior: After update-function-code, wait for propagation, publish version, update live alias_
    - _Preservation: Pre-update loop, skip-if-not-exists, ECR tagging unchanged_
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.2 Add post-update wait loop + publish-version + update-alias to portfolio-service update step
    - File: `.github/workflows/deploy.yml`
    - Same pattern as 4.1 applied to the portfolio-service "Update portfolio-service Lambda" step
    - _Bug_Condition: isBugCondition(X) where X = portfolio-service update step with no publish-version_
    - _Expected_Behavior: After update-function-code, wait for propagation, publish version, update live alias_
    - _Preservation: Pre-update loop, skip-if-not-exists, ECR tagging unchanged_
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.3 Add post-update wait loop + publish-version + update-alias to market-data-service update step
    - File: `.github/workflows/deploy.yml`
    - Same pattern as 4.1 applied to the market-data-service "Update market-data-service Lambda" step
    - _Bug_Condition: isBugCondition(X) where X = market-data-service update step with no publish-version_
    - _Expected_Behavior: After update-function-code, wait for propagation, publish version, update live alias_
    - _Preservation: Pre-update loop, skip-if-not-exists, ECR tagging unchanged_
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.4 Add post-update wait loop + publish-version + update-alias to insight-service update step
    - File: `.github/workflows/deploy.yml`
    - Same pattern as 4.1 applied to the insight-service "Update insight-service Lambda" step
    - _Bug_Condition: isBugCondition(X) where X = insight-service update step with no publish-version_
    - _Expected_Behavior: After update-function-code, wait for propagation, publish version, update live alias_
    - _Preservation: Pre-update loop, skip-if-not-exists, ECR tagging unchanged_
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.5 Verify bug condition exploration test now passes
    - **Property 1: Expected Behavior** - Alias Updated After Code Deploy
    - **IMPORTANT**: Re-run the SAME test from task 1 — do NOT write a new test
    - The test from task 1 encodes the expected behavior (publish-version + update-alias present after update-function-code)
    - When this test passes, it confirms the expected behavior is satisfied for all 4 services
    - Run bug condition exploration test from step 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms bug is fixed — all 4 steps now publish version and update alias)
    - _Requirements: 2.1, 2.2, 2.3_

  - [x] 4.6 Verify preservation tests still pass
    - **Property 2: Preservation** - Non-Alias Workflow Behavior Unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 2 — do NOT write new tests
    - Run preservation property tests from step 2
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions — ECR tagging, pre-update loop, skip-if-not-exists, no update-function-configuration all preserved)
    - Confirm all tests still pass after fix (no regressions)
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 5. Checkpoint — Ensure all tests pass
  - Verify bug condition exploration test passes (alias is updated after deploy)
  - Verify preservation tests pass (no regressions to existing behavior)
  - Verify Terraform validates cleanly with lifecycle blocks
  - Confirm deployment ordering: Terraform changes (task 3) MUST be merged and applied before deploy.yml changes (task 4) run in CI
  - Ensure all tests pass, ask the user if questions arise.
