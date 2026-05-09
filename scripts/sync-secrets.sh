#!/usr/bin/env bash
# Sync secrets from a .env file to GitHub Actions secrets.
#
# Usage:
#   ./scripts/sync-secrets.sh .env.secrets
#
# Env vars on live Lambdas (AWS) and Container Apps (Azure) are owned by
# Terraform. Set them as GitHub Actions secrets via this script; they will be
# applied on the next terraform.yml (AWS) or terraform-azure.yml (Azure) run
# from main (via TF_VAR_* environment variable injection).
#
# Do NOT use aws lambda update-function-configuration or
# az containerapp update --set-env-vars directly — both perform a full replace
# and will drift from Terraform-managed state.
#
# Azure-specific secrets required before deploy-azure.yml / terraform-azure.yml
# can run:
#   AZURE_CLIENT_ID        — App Registration client ID (OIDC)
#   AZURE_TENANT_ID        — Azure AD tenant ID
#   AZURE_SUBSCRIPTION_ID  — Azure subscription ID
#   AZURE_BACKEND_HCL      — Full content of backend-azure.hcl (apply path only)
#
# See docs/runbooks/AZURE_SECRETS_SETUP.md for the one-time setup steps.

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

# ---------------------------------------------------------------------------
# Sync to GitHub Actions secrets
# ---------------------------------------------------------------------------
echo "Checking GitHub CLI authentication..."
if ! gh auth status >/dev/null 2>&1; then
  echo "Please run 'gh auth login' first."
  exit 1
fi

echo "Syncing secrets from $ENV_FILE to GitHub Actions..."
gh secret set -f "$ENV_FILE"
echo "✓ GitHub Actions secrets updated."

echo ""
echo "Done. All secrets synced successfully."
echo ""
echo "Note: Lambda environment variables are managed by Terraform."
echo "      They will be updated on the next terraform.yml run from main."
