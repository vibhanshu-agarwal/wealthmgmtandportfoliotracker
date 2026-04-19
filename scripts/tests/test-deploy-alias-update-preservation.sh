#!/usr/bin/env bash
# =============================================================================
# Preservation Property Test — Non-Alias Workflow Behavior Unchanged
# =============================================================================
# Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6
#
# This script asserts baseline behaviors of the deploy.yml workflow and Terraform
# config that MUST remain unchanged after the bugfix is applied.
# Run on UNFIXED code: all tests should PASS (confirms baseline to preserve).
# Run AFTER fix: all tests should still PASS (confirms no regressions).
# =============================================================================

set -euo pipefail

DEPLOY_YML=".github/workflows/deploy.yml"
TERRAFORM_MAIN="infrastructure/terraform/modules/compute/main.tf"

PASS=0
FAIL=0

pass() {
  PASS=$((PASS + 1))
  echo "  ✅ PASS: $1"
}

fail() {
  FAIL=$((FAIL + 1))
  echo "  ❌ FAIL: $1"
}

# =============================================================================
# deploy.yml assertions
# =============================================================================

echo ""
echo "═══════════════════════════════════════════════════════════════════════════"
echo " Property 2: Preservation — Non-Alias Workflow Behavior Unchanged"
echo "═══════════════════════════════════════════════════════════════════════════"
echo ""
echo "── deploy.yml assertions ──────────────────────────────────────────────────"
echo ""

# ---------------------------------------------------------------------------
# 1. Dual tagging: all 4 image build steps tag with both 'latest' and SHA
# ---------------------------------------------------------------------------
echo "  [1] Dual tagging (latest + SHA) for all 4 image build steps"

SERVICES=("api-gateway" "portfolio-service" "market-data-service" "insight-service")

for svc in "${SERVICES[@]}"; do
  # Find the docker buildx build block for this service by looking for its Dockerfile reference
  # Each build step uses -f <service>/Dockerfile
  if grep -q "\-f ${svc}/Dockerfile" "$DEPLOY_YML"; then
    # Check that the build block contains both :latest and :${IMAGE_TAG_SHA} tags
    # We look for -t lines near the Dockerfile reference
    BLOCK=$(grep -A 10 "\-f ${svc}/Dockerfile" "$DEPLOY_YML")
    if echo "$BLOCK" | grep -q ':latest'; then
      if echo "$BLOCK" | grep -q ':\${IMAGE_TAG_SHA}'; then
        pass "$svc: dual tagging (latest + \${IMAGE_TAG_SHA}) present"
      else
        fail "$svc: missing \${IMAGE_TAG_SHA} tag"
      fi
    else
      fail "$svc: missing :latest tag"
    fi
  else
    fail "$svc: Dockerfile build step not found"
  fi
done

# ---------------------------------------------------------------------------
# 2. Pre-update polling loop exists for all 4 service update steps
# ---------------------------------------------------------------------------
echo ""
echo "  [2] Pre-update polling loop (wait for LastUpdateStatus = Successful BEFORE update-function-code)"

# The pattern: a for loop checking LastUpdateStatus followed by update-function-code
# We verify each update step has the polling loop BEFORE update-function-code
LAMBDA_NAMES=("api-gateway" "portfolio-service" "market-data-service" "insight-service")
STEP_NAMES=("Update api-gateway Lambda" "Update portfolio-service Lambda" "Update market-data-service Lambda" "Update insight-service Lambda")

for i in "${!STEP_NAMES[@]}"; do
  step_name="${STEP_NAMES[$i]}"
  svc="${LAMBDA_NAMES[$i]}"

  # Extract the step block: from "- name: <step_name>" to the next "- name:" or end
  STEP_BLOCK=$(sed -n "/- name: ${step_name}/,/^      - name:/p" "$DEPLOY_YML" | head -n -1)
  if [ -z "$STEP_BLOCK" ]; then
    # Try without the trailing delimiter (last step in job)
    STEP_BLOCK=$(sed -n "/- name: ${step_name}/,\$p" "$DEPLOY_YML")
  fi

  if [ -z "$STEP_BLOCK" ]; then
    fail "$svc: update step not found"
    continue
  fi

  # Check for polling loop pattern: "for attempt" + "LastUpdateStatus" before "update-function-code"
  # The pre-update loop should appear before update-function-code
  PRE_UPDATE=$(echo "$STEP_BLOCK" | sed -n '1,/update-function-code/p')
  if echo "$PRE_UPDATE" | grep -q "for attempt"; then
    if echo "$PRE_UPDATE" | grep -q "LastUpdateStatus"; then
      pass "$svc: pre-update polling loop present"
    else
      fail "$svc: polling loop found but missing LastUpdateStatus check"
    fi
  else
    fail "$svc: no pre-update polling loop found before update-function-code"
  fi
done

# ---------------------------------------------------------------------------
# 3. Skip-if-not-exists check for all 4 service update steps
# ---------------------------------------------------------------------------
echo ""
echo "  [3] Skip-if-not-exists check (get-function-configuration + early exit)"

for i in "${!STEP_NAMES[@]}"; do
  step_name="${STEP_NAMES[$i]}"
  svc="${LAMBDA_NAMES[$i]}"

  STEP_BLOCK=$(sed -n "/- name: ${step_name}/,/^      - name:/p" "$DEPLOY_YML" | head -n -1)
  if [ -z "$STEP_BLOCK" ]; then
    STEP_BLOCK=$(sed -n "/- name: ${step_name}/,\$p" "$DEPLOY_YML")
  fi

  if [ -z "$STEP_BLOCK" ]; then
    fail "$svc: update step not found"
    continue
  fi

  # Check for the skip-if-not-exists pattern:
  # "if ! aws lambda get-function-configuration" + "exit 0"
  if echo "$STEP_BLOCK" | grep -q "get-function-configuration"; then
    if echo "$STEP_BLOCK" | grep -q "exit 0"; then
      pass "$svc: skip-if-not-exists check present"
    else
      fail "$svc: get-function-configuration found but no early exit"
    fi
  else
    fail "$svc: no get-function-configuration existence check found"
  fi
done

# ---------------------------------------------------------------------------
# 4. Zero occurrences of update-function-configuration in deploy.yml
#    (excluding comments — lines starting with #)
# ---------------------------------------------------------------------------
echo ""
echo "  [4] No update-function-configuration calls in deploy.yml (excluding comments)"

# Only count non-comment lines that contain update-function-configuration
COUNT=$(grep -v '^\s*#' "$DEPLOY_YML" | grep -c "update-function-configuration" || true)
if [ "$COUNT" -eq 0 ]; then
  pass "zero non-comment occurrences of update-function-configuration (env vars owned by Terraform)"
else
  fail "found $COUNT non-comment occurrence(s) of update-function-configuration — env vars must be Terraform-only"
fi

# ---------------------------------------------------------------------------
# 5. Docker build commands include platform and provenance/sbom flags
#    Note: --platform appears BEFORE -f Dockerfile in the multi-line command
# ---------------------------------------------------------------------------
echo ""
echo "  [5] Docker build flags: --platform linux/amd64, --provenance=false, --sbom=false"

for svc in "${SERVICES[@]}"; do
  # Capture more context before the -f line since --platform precedes it
  BLOCK=$(grep -B 6 -A 6 "\-f ${svc}/Dockerfile" "$DEPLOY_YML")
  MISSING=""
  if ! echo "$BLOCK" | grep -q -- "--platform linux/amd64"; then
    MISSING="--platform linux/amd64"
  fi
  if ! echo "$BLOCK" | grep -q -- "--provenance=false"; then
    MISSING="${MISSING:+$MISSING, }--provenance=false"
  fi
  if ! echo "$BLOCK" | grep -q -- "--sbom=false"; then
    MISSING="${MISSING:+$MISSING, }--sbom=false"
  fi

  if [ -z "$MISSING" ]; then
    pass "$svc: all Docker build flags present"
  else
    fail "$svc: missing Docker build flags: $MISSING"
  fi
done

# ---------------------------------------------------------------------------
# 6. Frontend deploy steps (S3 sync, CloudFront invalidation) are present
# ---------------------------------------------------------------------------
echo ""
echo "  [6] Frontend deploy steps present (S3 sync + CloudFront invalidation)"

if grep -q "aws s3 sync" "$DEPLOY_YML"; then
  pass "S3 sync step present"
else
  fail "S3 sync step missing"
fi

if grep -q "aws cloudfront create-invalidation" "$DEPLOY_YML"; then
  pass "CloudFront invalidation step present"
else
  fail "CloudFront invalidation step missing"
fi

# =============================================================================
# Terraform assertions
# =============================================================================

echo ""
echo "── Terraform assertions ───────────────────────────────────────────────────"
echo ""

# ---------------------------------------------------------------------------
# 7. All 4 aws_lambda_alias resources exist
# ---------------------------------------------------------------------------
echo "  [7] All 4 aws_lambda_alias resources exist"

ALIAS_RESOURCES=("api_gateway_live" "portfolio_live" "market_data_live" "insight_live")

for alias in "${ALIAS_RESOURCES[@]}"; do
  if grep -q "resource \"aws_lambda_alias\" \"${alias}\"" "$TERRAFORM_MAIN"; then
    pass "aws_lambda_alias.${alias} exists"
  else
    fail "aws_lambda_alias.${alias} NOT found"
  fi
done

# ---------------------------------------------------------------------------
# 8. Each alias has name = "live" and references the correct Lambda function
# ---------------------------------------------------------------------------
echo ""
echo "  [8] Each alias has name = \"live\" and correct function_name reference"

LAMBDA_FUNCTIONS=("api_gateway" "portfolio" "market_data" "insight")

for i in "${!ALIAS_RESOURCES[@]}"; do
  alias="${ALIAS_RESOURCES[$i]}"
  lambda="${LAMBDA_FUNCTIONS[$i]}"

  # Extract the alias resource block
  ALIAS_BLOCK=$(sed -n "/resource \"aws_lambda_alias\" \"${alias}\"/,/^}/p" "$TERRAFORM_MAIN")

  if [ -z "$ALIAS_BLOCK" ]; then
    fail "aws_lambda_alias.${alias}: resource block not found"
    continue
  fi

  # Check name = "live"
  if echo "$ALIAS_BLOCK" | grep -q 'name.*=.*"live"'; then
    pass "aws_lambda_alias.${alias}: name = \"live\""
  else
    fail "aws_lambda_alias.${alias}: name is not \"live\""
  fi

  # Check function_name references the correct Lambda
  if echo "$ALIAS_BLOCK" | grep -q "aws_lambda_function.${lambda}.function_name"; then
    pass "aws_lambda_alias.${alias}: references aws_lambda_function.${lambda}"
  else
    fail "aws_lambda_alias.${alias}: does not reference aws_lambda_function.${lambda}"
  fi
done

# =============================================================================
# Summary
# =============================================================================

echo ""
echo "═══════════════════════════════════════════════════════════════════════════"
TOTAL=$((PASS + FAIL))
echo " Results: ${PASS}/${TOTAL} passed, ${FAIL} failed"
echo "═══════════════════════════════════════════════════════════════════════════"
echo ""

if [ "$FAIL" -gt 0 ]; then
  echo "❌ PRESERVATION TESTS FAILED — baseline behavior is not as expected"
  exit 1
else
  echo "✅ ALL PRESERVATION TESTS PASSED — baseline behavior confirmed"
  exit 0
fi
