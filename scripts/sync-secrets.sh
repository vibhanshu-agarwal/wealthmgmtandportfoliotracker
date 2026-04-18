#!/usr/bin/env bash
# Sync secrets from a .env file to GitHub Actions AND optionally to a Lambda function.
#
# Usage:
#   ./scripts/sync-secrets.sh .env.secrets
#   ./scripts/sync-secrets.sh .env.secrets --lambda wealth-mgmt-backend-lambda
#
# The --lambda flag pushes the Lambda-relevant env vars directly to the named function
# via `aws lambda update-function-configuration`, in addition to syncing GitHub secrets.
# This is useful when you need the Lambda to pick up new values immediately without
# waiting for a full deploy workflow run.
#
# Lambda env vars pushed (subset of the .env file relevant to the runtime):
#   PORT, SERVER_PORT, SPRING_PROFILES_ACTIVE, JAVA_TOOL_OPTIONS,
#   PORTFOLIO_SERVICE_URL, MARKET_DATA_SERVICE_URL, INSIGHT_SERVICE_URL,
#   AUTH_JWK_URI, CLOUDFRONT_ORIGIN_SECRET,
#   AWS_LWA_ASYNC_INIT, AWS_LWA_READINESS_CHECK_PATH
#
# NOTE: AWS_LWA_ASYNC_INIT and AWS_LWA_READINESS_CHECK_PATH are hardcoded to their
# correct values here because they are infrastructure constants, not user secrets.

set -euo pipefail

ENV_FILE="${1:-}"
LAMBDA_FUNCTION=""

# Parse optional --lambda <function-name> flag
shift || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --lambda)
      LAMBDA_FUNCTION="${2:-}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1"
      exit 1
      ;;
  esac
done

if [ -z "$ENV_FILE" ]; then
  echo "Usage: $0 .env.secrets [--lambda <function-name>]"
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Error: secret file '$ENV_FILE' not found."
  exit 1
fi

# ---------------------------------------------------------------------------
# 1. Sync to GitHub Actions secrets
# ---------------------------------------------------------------------------
echo "Checking GitHub CLI authentication..."
if ! gh auth status >/dev/null 2>&1; then
  echo "Please run 'gh auth login' first."
  exit 1
fi

echo "Syncing secrets from $ENV_FILE to GitHub Actions..."
gh secret set -f "$ENV_FILE"
echo "✓ GitHub Actions secrets updated."

# ---------------------------------------------------------------------------
# 2. Optionally sync Lambda-relevant vars to the Lambda function directly
# ---------------------------------------------------------------------------
if [ -n "$LAMBDA_FUNCTION" ]; then
  echo ""
  echo "Syncing Lambda environment variables to function: $LAMBDA_FUNCTION"

  # Source the env file (skip comment lines and blank lines)
  # shellcheck disable=SC2046
  export $(grep -v '^\s*#' "$ENV_FILE" | grep -v '^\s*$' | xargs)

  # Build the JSON payload for Lambda env vars.
  # AWS_LWA_* are infrastructure constants — always set to correct values.
  # Service URLs come from the env file (localhost for monolith, Function URLs for multi-Lambda).
  LAMBDA_ENV_JSON=$(cat <<EOF
{
  "Variables": {
    "PORT":                        "8080",
    "SERVER_PORT":                 "8080",
    "SPRING_PROFILES_ACTIVE":      "prod,aws",
    "JAVA_TOOL_OPTIONS":           "-XX:+TieredCompilation -XX:TieredStopAtLevel=1",
    "AWS_LWA_ASYNC_INIT":          "true",
    "AWS_LWA_READINESS_CHECK_PATH": "/actuator/health",
    "PORTFOLIO_SERVICE_URL":       "${PORTFOLIO_SERVICE_URL:-http://localhost:8081}",
    "MARKET_DATA_SERVICE_URL":     "${MARKET_DATA_SERVICE_URL:-http://localhost:8082}",
    "INSIGHT_SERVICE_URL":         "${INSIGHT_SERVICE_URL:-http://localhost:8083}",
    "AUTH_JWK_URI":                "${AUTH_JWK_URI:-}",
    "CLOUDFRONT_ORIGIN_SECRET":    "${CLOUDFRONT_ORIGIN_SECRET:-}"
  }
}
EOF
)

  # Write to a temp file (aws cli requires file:// for --environment on complex values)
  TMPFILE="$(mktemp /tmp/lambda-env-XXXXXX.json)"
  trap 'rm -f "$TMPFILE"' EXIT
  echo "$LAMBDA_ENV_JSON" > "$TMPFILE"

  aws lambda update-function-configuration \
    --function-name "$LAMBDA_FUNCTION" \
    --environment "file://$TMPFILE" \
    --query "{LastUpdateStatus:LastUpdateStatus,Timeout:Timeout}" \
    --output json

  echo "✓ Lambda environment variables updated."
  echo ""
  echo "Waiting for Lambda configuration update to complete..."
  for attempt in {1..20}; do
    STATUS="$(aws lambda get-function-configuration \
      --function-name "$LAMBDA_FUNCTION" \
      --query 'LastUpdateStatus' --output text)"
    if [ "$STATUS" = "Successful" ]; then
      echo "✓ Lambda configuration update complete."
      break
    elif [ "$STATUS" = "Failed" ]; then
      echo "✗ Lambda configuration update failed."
      exit 1
    fi
    echo "  Attempt $attempt/20 — status: $STATUS. Waiting 3s..."
    sleep 3
  done
fi

echo ""
echo "Done. All secrets synced successfully."
