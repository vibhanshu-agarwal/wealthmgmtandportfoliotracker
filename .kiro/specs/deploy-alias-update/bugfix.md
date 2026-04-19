# Bugfix Requirements Document

## Introduction

The `deploy.yml` GitHub Actions workflow updates Lambda function code (`$LATEST`) but never publishes a new version or updates the `live` alias. Since Function URLs are attached to the `live` alias (not `$LATEST`), every deploy has no effect on live traffic until someone manually runs `publish-version` and `update-alias`. This means all CI/CD deploys are silently broken — users continue to see stale code after every push to main.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN `deploy.yml` runs `aws lambda update-function-code` for any of the 4 services THEN the system only updates `$LATEST` and does not publish a new immutable version

1.2 WHEN `deploy.yml` completes the `update-function-code` step THEN the system does not call `aws lambda publish-version` to create a new version from `$LATEST`

1.3 WHEN `deploy.yml` completes the `update-function-code` step THEN the system does not call `aws lambda update-alias --name live` to point the `live` alias at the newly published version

1.4 WHEN a deploy completes successfully THEN the `live` alias remains pointed at the previously published version and Function URLs continue serving stale code

1.5 WHEN multiple deploys run without manual intervention THEN the `live` alias drifts further behind `$LATEST`, accumulating unreleased changes invisible to users

### Expected Behavior (Correct)

2.1 WHEN `deploy.yml` runs `aws lambda update-function-code` for any service THEN the system SHALL wait for `LastUpdateStatus` to become `Successful` and then call `aws lambda publish-version` to create a new immutable version from `$LATEST`

2.2 WHEN `publish-version` succeeds THEN the system SHALL extract the new version number from the response and call `aws lambda update-alias --name live --function-version <new-version>` to point the `live` alias at the new version

2.3 WHEN the `live` alias is updated THEN the Function URL (attached to the `live` alias qualifier) SHALL immediately serve the new code without manual intervention

2.4 WHEN `publish-version` or `update-alias` fails THEN the workflow step SHALL fail with a non-zero exit code, halting the deploy and surfacing the error in GitHub Actions

### Unchanged Behavior (Regression Prevention)

3.1 WHEN `deploy.yml` builds and pushes Docker images to ECR THEN the system SHALL CONTINUE TO tag images with both `latest` and the commit SHA

3.2 WHEN `deploy.yml` calls `update-function-code` THEN the system SHALL CONTINUE TO use the existing polling loop that waits for `LastUpdateStatus = Successful` before proceeding

3.3 WHEN the Lambda function does not exist yet (first deploy before `terraform apply`) THEN the system SHALL CONTINUE TO skip the update step gracefully with an informational message

3.4 WHEN `LastUpdateStatus` is `Failed` during the pre-update polling loop THEN the system SHALL CONTINUE TO abort with a non-zero exit code

3.5 WHEN Terraform runs `terraform apply` after deploy.yml has updated the alias THEN the system SHALL CONTINUE TO function correctly — the alias version drift between Terraform state and AWS reality is acceptable and reconciles on next apply

3.6 WHEN `deploy.yml` runs THEN the system SHALL CONTINUE TO NOT call `update-function-configuration` — environment variables remain exclusively owned by Terraform

---

## Bug Condition

```pascal
FUNCTION isBugCondition(X)
  INPUT: X of type DeployStep
  OUTPUT: boolean

  // The bug triggers whenever update-function-code succeeds for any Lambda service
  // but no publish-version + update-alias follows
  RETURN X.updateFunctionCodeSucceeded = true
    AND X.publishVersionCalled = false
END FUNCTION
```

## Fix Checking Property

```pascal
// Property: Fix Checking — Alias Update After Code Deploy
FOR ALL X WHERE isBugCondition(X) DO
  result ← deploy'(X)
  ASSERT result.publishVersionCalled = true
    AND result.newVersionNumber > 0
    AND result.updateAliasCalled = true
    AND result.aliasTarget = result.newVersionNumber
    AND result.functionUrlServesNewCode = true
END FOR
```

## Preservation Checking Property

```pascal
// Property: Preservation Checking — Non-deploy behavior unchanged
FOR ALL X WHERE NOT isBugCondition(X) DO
  ASSERT F(X) = F'(X)
  // Specifically:
  // - ECR image build/push unchanged
  // - Pre-update polling loop unchanged
  // - Skip-if-not-exists behavior unchanged
  // - No update-function-configuration calls added
END FOR
```
