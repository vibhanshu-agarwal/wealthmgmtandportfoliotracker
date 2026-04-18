#!/usr/bin/env bash
# Usage: ./scripts/sync-secrets.sh .env.secrets

set -euo pipefail

ENV_FILE="${1:-}"

if [ -z "$ENV_FILE" ]; then
  echo "Usage: $0 .env.secrets"
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Error: secret file '$ENV_FILE' not found."
  exit 1
fi

echo "Checking GitHub CLI authentication..."
gh auth status >/dev/null || {
  echo "Please run 'gh auth login' first."
  exit 1
}

echo "Syncing secrets from $ENV_FILE to GitHub..."
gh secret set -f "$ENV_FILE"

echo "Success! All secrets from $ENV_FILE have been updated in GitHub Actions."
