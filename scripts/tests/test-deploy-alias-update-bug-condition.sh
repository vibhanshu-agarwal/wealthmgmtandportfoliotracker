#!/usr/bin/env bash
# =============================================================================
# Bug Condition Exploration Test — Property 1: Alias Stale After Code Deploy
#
# Validates: Requirements 1.1, 1.2, 1.3, 2.1, 2.2
#
# This test encodes the EXPECTED behavior: after update-function-code, each
# service step must contain publish-version and update-alias commands.
#
# On UNFIXED code this test FAILS — confirming the bug exists.
# On FIXED code this test PASSES — confirming the bug is resolved.
#
# Bug Condition from design:
#   isBugCondition(X) = X.updateFunctionCodeSucceeded = true
#                       AND X.publishVersionCalled = false
# =============================================================================
set -euo pipefail

WORKFLOW_FILE=".github/workflows/deploy.yml"
FAILURES=0

STEP_NAMES=(
  "Update api-gateway Lambda"
  "Update portfolio-service Lambda"
  "Update market-data-service Lambda"
  "Update insight-service Lambda"
)

echo "============================================================"
echo "Bug Condition Exploration Test"
echo "Property 1: Alias Stale After Code Deploy"
echo "============================================================"
echo ""
echo "Workflow file: $WORKFLOW_FILE"
echo ""

if [ ! -f "$WORKFLOW_FILE" ]; then
  echo "FATAL: Workflow file not found at $WORKFLOW_FILE"
  exit 1
fi

for STEP_NAME in "${STEP_NAMES[@]}"; do
  echo "------------------------------------------------------------"
  echo "Checking step: $STEP_NAME"
  echo "------------------------------------------------------------"

  # Extract the full step block: from "- name: <step>" to the next "- name:" or EOF
  # Use awk for reliable multi-line extraction
  STEP_BLOCK=$(awk "/- name: ${STEP_NAME}/{found=1} found{print} found && /^      - name:/ && !/- name: ${STEP_NAME}/{exit}" "$WORKFLOW_FILE")

  if [ -z "$STEP_BLOCK" ]; then
    echo "  SKIP: Step '$STEP_NAME' not found in workflow"
    continue
  fi

  # Verify update-function-code is present in this step
  if ! echo "$STEP_BLOCK" | grep -q "update-function-code"; then
    echo "  SKIP: No update-function-code in this step"
    continue
  fi

  # Extract lines AFTER update-function-code within this step block
  POST_UPDATE=$(echo "$STEP_BLOCK" | awk '/update-function-code/{found=1; next} found{print}')

  # Assert 1: publish-version must appear AFTER update-function-code
  if echo "$POST_UPDATE" | grep -q "publish-version"; then
    echo "  PASS: publish-version found after update-function-code"
  else
    echo "  FAIL: publish-version NOT found after update-function-code"
    echo "        Bug condition confirmed: update-function-code succeeds but"
    echo "        no publish-version is called — alias remains stale"
    FAILURES=$((FAILURES + 1))
  fi

  # Assert 2: update-alias must appear AFTER update-function-code
  if echo "$POST_UPDATE" | grep -q "update-alias"; then
    echo "  PASS: update-alias found after update-function-code"
  else
    echo "  FAIL: update-alias NOT found after update-function-code"
    echo "        Bug condition confirmed: no update-alias called —"
    echo "        live alias never points to new version"
    FAILURES=$((FAILURES + 1))
  fi

  # Assert 3: A post-update wait loop (LastUpdateStatus) must exist
  # between update-function-code and publish-version
  if echo "$POST_UPDATE" | grep -q "LastUpdateStatus"; then
    echo "  PASS: post-update wait loop found after update-function-code"
  else
    echo "  FAIL: post-update wait loop NOT found after update-function-code"
    echo "        No wait for code propagation before publish-version"
    FAILURES=$((FAILURES + 1))
  fi

  echo ""
done

echo "============================================================"
echo "RESULTS"
echo "============================================================"

if [ "$FAILURES" -gt 0 ]; then
  echo "FAILED: $FAILURES assertion(s) failed across ${#STEP_NAMES[@]} services"
  echo ""
  echo "Counterexamples demonstrating the bug:"
  echo "  - Update steps call 'aws lambda update-function-code'"
  echo "    but LACK 'aws lambda publish-version' afterward"
  echo "  - Update steps call 'aws lambda update-function-code'"
  echo "    but LACK 'aws lambda update-alias' afterward"
  echo "  - No post-update wait loop exists to ensure code propagation"
  echo "    before publishing a new version"
  echo ""
  echo "Bug condition: isBugCondition(X) = true"
  echo "  X.updateFunctionCodeSucceeded = true"
  echo "  X.publishVersionCalled = false"
  echo ""
  echo "Impact: Function URLs attached to 'live' alias serve stale code"
  echo "        indefinitely after every deploy."
  exit 1
else
  echo "PASSED: All assertions passed — expected behavior confirmed"
  echo "  - publish-version is called after update-function-code"
  echo "  - update-alias is called to point 'live' at new version"
  echo "  - post-update wait loop ensures code propagation"
  exit 0
fi
