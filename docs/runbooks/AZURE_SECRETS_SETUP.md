# Azure Secrets Setup — One-Time Runbook

This runbook covers the one-time steps needed before `deploy-azure.yml` and
`terraform-azure.yml` can run successfully in GitHub Actions.

Both workflows use **OIDC (Workload Identity Federation)** — no long-lived
client secrets are stored. The three secrets `AZURE_CLIENT_ID`,
`AZURE_TENANT_ID`, and `AZURE_SUBSCRIPTION_ID` are already present in
`.env.secrets` and will be synced to GitHub by `sync-secrets.sh`.

---

## Prerequisites

- Azure CLI installed and logged in (`az login`)
- GitHub CLI installed and authenticated (`gh auth login`)
- Owner or Contributor access on the target Azure subscription

---

## Step 1 — Create the App Registration and Service Principal

```bash
# Create the App Registration
APP_ID=$(az ad app create --display-name "wealthtracker-github-oidc" \
  --query appId -o tsv)

# Create the Service Principal
az ad sp create --id "$APP_ID"

# Note your subscription and tenant IDs
SUBSCRIPTION_ID=$(az account show --query id -o tsv)
TENANT_ID=$(az account show --query tenantId -o tsv)

echo "APP_ID=$APP_ID"
echo "SUBSCRIPTION_ID=$SUBSCRIPTION_ID"
echo "TENANT_ID=$TENANT_ID"
```

---

## Step 2 — Assign Roles

The service principal needs two roles:

```bash
# Contributor on the subscription (for provisioning all Azure resources)
az role assignment create \
  --assignee "$APP_ID" \
  --role Contributor \
  --scope "/subscriptions/$SUBSCRIPTION_ID"

# User Access Administrator (for creating role assignments in Terraform,
# e.g. AcrPull and Cognitive Services OpenAI User)
az role assignment create \
  --assignee "$APP_ID" \
  --role "User Access Administrator" \
  --scope "/subscriptions/$SUBSCRIPTION_ID"
```

---

## Step 3 — Add Federated Credentials (OIDC)

Add one credential per branch/environment that needs to trigger the workflows.

```bash
# For the feature branch (PR validation)
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters '{
    "name": "github-feat-phase4",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:vibhanshu-agarwal/wealthmgmtandportfoliotracker:ref:refs/heads/feat/phase4-azure-migration",
    "audiences": ["api://AzureADTokenExchange"]
  }'

# For pull requests (terraform-azure.yml plan path)
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters '{
    "name": "github-pull-request",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:vibhanshu-agarwal/wealthmgmtandportfoliotracker:pull_request",
    "audiences": ["api://AzureADTokenExchange"]
  }'

# For main branch (deploy-azure.yml + terraform-azure.yml apply)
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters '{
    "name": "github-main",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:vibhanshu-agarwal/wealthmgmtandportfoliotracker:ref:refs/heads/main",
    "audiences": ["api://AzureADTokenExchange"]
  }'
```

---

## Step 4 — Provision the Terraform State Backend

The Azure Terraform root uses a remote backend (Azure Blob Storage). You need
to create the storage account once before running `terraform apply`.

```bash
# Create a resource group for Terraform state (separate from app resources)
az group create \
  --name wealth-tf-state-rg \
  --location centralindia

# Create a storage account (name must be globally unique, 3-24 chars, lowercase)
az storage account create \
  --name wealthtfstate \
  --resource-group wealth-tf-state-rg \
  --location centralindia \
  --sku Standard_LRS \
  --kind StorageV2 \
  --allow-blob-public-access false

# Create the blob container
az storage container create \
  --name tfstate \
  --account-name wealthtfstate

# Grant the service principal Storage Blob Data Contributor on the container
az role assignment create \
  --assignee "$APP_ID" \
  --role "Storage Blob Data Contributor" \
  --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/wealth-tf-state-rg/storageAccounts/wealthtfstate"
```

---

## Step 5 — Populate `.env.secrets` and Sync to GitHub

1. Copy `infrastructure/terraform/azure/backend-azure.hcl.example` to
   `infrastructure/terraform/azure/backend-azure.hcl` and fill in the real
   values from Step 4:

   ```hcl
   resource_group_name  = "wealth-tf-state-rg"
   storage_account_name = "wealthtfstate"
   container_name       = "tfstate"
   key                  = "azure/terraform.tfstate"
   ```

   > `backend-azure.hcl` is gitignored — never commit it.

2. Add the following to `.env.secrets` (values from Steps 1–4):

   ```dotenv
   AZURE_CLIENT_ID=<APP_ID from Step 1>
   AZURE_TENANT_ID=<TENANT_ID from Step 1>
   AZURE_SUBSCRIPTION_ID=<SUBSCRIPTION_ID from Step 1>

   # Paste the entire content of backend-azure.hcl as the value.
   # Use a single line with \n separators, or a multi-line value if your
   # shell supports it. The workflow writes this to backend-azure.hcl at
   # apply time via: echo "$AZURE_BACKEND_HCL" > backend-azure.hcl
   AZURE_BACKEND_HCL=resource_group_name = "wealth-tf-state-rg"\nstorage_account_name = "wealthtfstate"\ncontainer_name = "tfstate"\nkey = "azure/terraform.tfstate"
   ```

3. Sync all secrets to GitHub:

   ```bash
   ./scripts/sync-secrets.sh .env.secrets
   ```

---

## Step 6 — Verify

After syncing, trigger a manual plan run to confirm everything works:

```bash
gh workflow run terraform-azure.yml \
  --ref feat/phase4-azure-migration \
  --field action=plan
```

The workflow should:
1. Log in via OIDC (no password prompt)
2. Run `terraform init -backend=false`
3. Run `terraform validate`
4. Run `terraform plan`
5. Run both Python assertion scripts (P1 + P5)

---

## Secrets Reference

| Secret | Source | Used by |
|--------|--------|---------|
| `AZURE_CLIENT_ID` | App Registration `appId` | `deploy-azure.yml`, `terraform-azure.yml` |
| `AZURE_TENANT_ID` | `az account show --query tenantId` | `deploy-azure.yml`, `terraform-azure.yml` |
| `AZURE_SUBSCRIPTION_ID` | `az account show --query id` | `deploy-azure.yml`, `terraform-azure.yml` |
| `AZURE_BACKEND_HCL` | Content of `backend-azure.hcl` | `terraform-azure.yml` (apply path only) |

All other secrets (`AUTH_JWT_SECRET`, `POSTGRES_CONNECTION_STRING`, etc.) are
shared with the AWS path and already present in `.env.secrets`.
