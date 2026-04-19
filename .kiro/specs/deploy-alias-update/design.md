# Deploy Alias Update Bugfix Design

## Overview

The `deploy.yml` workflow updates Lambda function code (`$LATEST`) but never publishes a new version or updates the `live` alias. Since Function URLs are attached to the `live` alias, deploys have zero effect on live traffic. The fix adds `publish-version` + `update-alias` steps after each `update-function-code` call, plus a post-update wait loop to ensure code propagation before publishing. A complementary Terraform change adds `lifecycle { ignore_changes = [function_version] }` to alias resources so that `terraform apply` doesn't revert deploy.yml's alias updates.

## Glossary

- **Bug_Condition (C)**: A deploy step where `update-function-code` succeeds but no `publish-version` + `update-alias` follows — the `live` alias remains stale
- **Property (P)**: After `update-function-code` succeeds, a new version is published and the `live` alias is pointed at it, so Function URLs serve the new code
- **Preservation**: Existing behaviors (ECR tagging, pre-update polling, skip-if-not-exists, no `update-function-configuration`) remain unchanged
- **`deploy.yml`**: GitHub Actions workflow at `.github/workflows/deploy.yml` that builds container images and updates Lambda function code on push to main
- **`live` alias**: Lambda alias attached to Function URLs; determines which version serves traffic
- **`$LATEST`**: Mutable Lambda pointer updated by `update-function-code`; not served by Function URLs in this architecture
- **`LastUpdateStatus`**: Lambda API field indicating whether a code/config update has fully propagated

## Bug Details

### Bug Condition

The bug manifests when `deploy.yml` successfully calls `aws lambda update-function-code` for any of the 4 Lambda services but does not subsequently publish a new version or update the `live` alias. The Function URL continues serving the previously published version indefinitely.

**Formal Specification:**

```
FUNCTION isBugCondition(input)
  INPUT: input of type DeployStep
  OUTPUT: boolean

  RETURN input.updateFunctionCodeSucceeded = true
         AND input.publishVersionCalled = false
END FUNCTION
```

### Examples

- **api-gateway deploy**: `update-function-code` succeeds with new image digest → `live` alias still points at version 3 → Function URL serves stale code from version 3
- **portfolio-service deploy**: `update-function-code` succeeds → no `publish-version` called → `$LATEST` has new code but `live` alias unchanged → users see old behavior
- **Multiple deploys accumulate**: 5 pushes to main each update `$LATEST` → `live` alias still at version 1 → 5 releases of unreleased code invisible to users
- **Edge case — first deploy**: Lambda doesn't exist yet → step skips gracefully → no bug (skip-if-not-exists path is unaffected)

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**

- ECR image build/push continues to tag with both `latest` and commit SHA
- Pre-update polling loop (waits for `LastUpdateStatus = Successful` before calling `update-function-code`) remains intact
- Skip-if-not-exists behavior (graceful skip when Lambda hasn't been created by Terraform yet) remains intact
- `update-function-configuration` is never called — env vars remain exclusively owned by Terraform
- Workflow failure on `LastUpdateStatus = Failed` during pre-update polling remains intact
- Docker build commands, platform flags, and provenance/sbom settings remain unchanged

**Scope:**
All inputs that do NOT involve the post-`update-function-code` path should be completely unaffected by this fix. This includes:

- ECR login and image push steps
- Frontend deployment (S3 sync, CloudFront invalidation)
- Gradle build and JDK setup steps
- The pre-update `LastUpdateStatus` polling loop itself (only a new post-update loop is added)

## Hypothesized Root Cause

Based on the bug description, the root cause is straightforward — missing steps in the workflow:

1. **Missing `publish-version` call**: After `update-function-code` completes, the workflow simply ends the step. No `aws lambda publish-version` is called to create an immutable version from `$LATEST`.

2. **Missing `update-alias` call**: Even if `publish-version` were called, there is no `aws lambda update-alias --name live --function-version <new>` to swing the alias to the new version.

3. **Missing post-update wait loop**: `publish-version` requires the function to be in `LastUpdateStatus = Successful` state. The existing wait loop runs BEFORE `update-function-code` (to ensure no concurrent update is in progress). A second wait loop is needed AFTER `update-function-code` to ensure the code update has fully propagated before publishing.

4. **Missing Terraform lifecycle block on aliases**: Without `lifecycle { ignore_changes = [function_version] }` on `aws_lambda_alias` resources, running `terraform apply` after deploy.yml updates the alias would revert the alias to the version Terraform last published, undoing deploy.yml's work. Currently only the Lambda functions have `ignore_changes = [image_uri]` — the aliases have no lifecycle block.

## Correctness Properties

Property 1: Bug Condition - Alias Updated After Code Deploy

_For any_ deploy step where `update-function-code` succeeds (isBugCondition returns true), the fixed workflow SHALL wait for `LastUpdateStatus = Successful`, then call `publish-version` to create a new immutable version, then call `update-alias --name live --function-version <new-version>` so that the Function URL immediately serves the new code.

**Validates: Requirements 2.1, 2.2, 2.3**

Property 2: Preservation - Non-Alias Workflow Behavior

_For any_ workflow step that is NOT the post-`update-function-code` publish/alias path (isBugCondition returns false), the fixed workflow SHALL produce exactly the same behavior as the original workflow, preserving ECR tagging, pre-update polling, skip-if-not-exists, failure-on-Failed-status, and the absence of `update-function-configuration` calls.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

## Fix Implementation

### Changes Required

**File**: `.github/workflows/deploy.yml`

**Steps**: All 4 "Update <service> Lambda" steps (api-gateway, portfolio-service, market-data-service, insight-service)

**Specific Changes**:

1. **Add post-update wait loop**: After `aws lambda update-function-code`, add a second polling loop that waits for `LastUpdateStatus = Successful` before proceeding to `publish-version`. This ensures the code update has fully propagated. Same pattern as the pre-update loop (30 attempts, 5s sleep, abort on `Failed`):

   ```bash
   # Wait for update-function-code to propagate
   for attempt in {1..30}; do
     STATUS="$(aws lambda get-function-configuration \
       --function-name "$LAMBDA_FUNCTION_NAME" \
       --query 'LastUpdateStatus' --output text)"
     if [ "$STATUS" = "Successful" ]; then echo "Code update propagated."; break; fi
     if [ "$STATUS" = "Failed" ]; then echo "Code update failed — aborting."; exit 1; fi
     echo "Attempt $attempt/30 — status: $STATUS. Waiting 5s..."
     [ "$attempt" -eq 30 ] && echo "Timed out waiting for code update." && exit 1
     sleep 5
   done
   ```

2. **Add `publish-version`**: After the post-update wait loop confirms `Successful`, publish a new immutable version using `--query 'Version' --output text` to extract the version number cleanly (no jq dependency):

   ```bash
   NEW_VERSION=$(aws lambda publish-version --function-name "$LAMBDA_FUNCTION_NAME" --query 'Version' --output text)
   ```

3. **Add `update-alias`**: Point the `live` alias at the newly published version:

   ```bash
   aws lambda update-alias --function-name "$LAMBDA_FUNCTION_NAME" --name live --function-version "$NEW_VERSION"
   ```

4. **Failure propagation**: If `publish-version` or `update-alias` fails, the shell step exits non-zero (default `set -e` behavior in GitHub Actions `run` blocks), halting the workflow and surfacing the error.

---

**File**: `infrastructure/terraform/modules/compute/main.tf`

**Resources**: All 4 `aws_lambda_alias` resources (`api_gateway_live`, `portfolio_live`, `market_data_live`, `insight_live`)

**Specific Changes**:

5. **Add lifecycle block to each alias**: Add `lifecycle { ignore_changes = [function_version] }` to prevent `terraform apply` from reverting the alias to the version Terraform last published. This is the same pattern already used on the Lambda function resources for `image_uri`:

   ```hcl
   resource "aws_lambda_alias" "api_gateway_live" {
     name             = "live"
     function_name    = aws_lambda_function.api_gateway.function_name
     function_version = aws_lambda_function.api_gateway.version

     lifecycle {
       ignore_changes = [function_version]
     }
   }
   ```

   Repeat for `portfolio_live`, `market_data_live`, and `insight_live`.

## Testing Strategy

### Validation Approach

The testing strategy follows a two-phase approach: first, verify the bug exists on unfixed code (deploy completes but alias is stale), then verify the fix correctly publishes versions and updates aliases while preserving all other behavior.

### Exploratory Bug Condition Checking

**Goal**: Confirm the bug exists by examining the current workflow output — after a successful deploy, the `live` alias version does not change.

**Test Plan**: Run the current `deploy.yml` workflow (or simulate the shell commands locally) and observe that `update-function-code` succeeds but no `publish-version` or `update-alias` is called. Verify the `live` alias still points at the old version.

**Test Cases**:

1. **Alias Stale After Deploy**: Push to main → workflow succeeds → check `aws lambda get-alias --name live` → version unchanged (demonstrates bug)
2. **Multiple Deploys Accumulate**: Push twice → both succeed → alias still at original version (demonstrates drift)
3. **$LATEST Updated But Not Served**: After deploy, invoke `$LATEST` → new code; invoke via Function URL (alias) → old code (demonstrates user impact)

**Expected Counterexamples**:

- `live` alias `FunctionVersion` field remains at the pre-deploy value after workflow completes
- Function URL response reflects old code despite successful workflow run

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed workflow publishes a version and updates the alias.

**Pseudocode:**

```
FOR ALL input WHERE isBugCondition(input) DO
  result := deploy_fixed(input)
  ASSERT result.postUpdateWaitCompleted = true
  ASSERT result.publishVersionCalled = true
  ASSERT result.newVersionNumber > 0
  ASSERT result.updateAliasCalled = true
  ASSERT result.aliasTarget = result.newVersionNumber
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed workflow produces the same result as the original.

**Pseudocode:**

```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT deploy_original(input) = deploy_fixed(input)
END FOR
```

**Testing Approach**: Manual inspection and diff-based verification are most appropriate here since the changes are in a GitHub Actions YAML workflow (not unit-testable code). Property-based testing applies conceptually — the "property" is that non-publish/alias lines in the workflow are byte-identical before and after the fix.

**Test Plan**: Diff the workflow file before and after the fix. Verify that only the post-`update-function-code` section is modified in each of the 4 service steps. Verify Terraform plan shows no changes to alias resources after adding the lifecycle block (since `ignore_changes` means Terraform won't try to update `function_version`).

**Test Cases**:

1. **ECR Tagging Preserved**: Verify image push steps still tag with `latest` and `${{ github.sha }}`
2. **Pre-Update Loop Preserved**: Verify the existing wait-for-Successful loop before `update-function-code` is unchanged
3. **Skip-If-Not-Exists Preserved**: Verify the `get-function-configuration` existence check and early exit remain unchanged
4. **No update-function-configuration**: Verify no `update-function-configuration` call is introduced anywhere
5. **Terraform Plan No-Op**: After adding lifecycle blocks, `terraform plan` shows no pending changes to alias resources

### Unit Tests

- Validate the bash script logic in isolation: mock `aws lambda get-function-configuration` responses and verify the wait loop exits correctly on `Successful`, `Failed`, and timeout
- Validate `publish-version` output parsing: ensure `--query 'Version' --output text` produces a clean integer string
- Validate `update-alias` is called with the correct `--function-version` value

### Property-Based Tests

- Generate random sequences of `LastUpdateStatus` responses (InProgress, InProgress, ..., Successful) and verify the wait loop always terminates correctly
- Generate random version numbers from `publish-version` and verify they are correctly passed to `update-alias`
- Verify that for any workflow step outside the 4 update blocks, the diff is empty (preservation)

### Integration Tests

- Run a full deploy workflow against a test Lambda (or LocalStack) and verify:
  - New version is published after code update
  - `live` alias points at the new version
  - Function URL invocation returns new code
- Run `terraform plan` after deploy.yml has updated the alias and verify no alias drift is reported (lifecycle block working)
- Test the skip-if-not-exists path: delete the Lambda, run deploy, verify graceful skip with no publish/alias attempt
