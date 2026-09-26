# Azure Secrets Setup — One-Time Runbook

**Owner approval required before execution:** identity/RBAC changes, cloud or secret access,
secret publication and workflow dispatch each need the applicable approval. Reconciliation of
this document grants none; do not rerun bootstrap against the existing demo environment.

**Source reconciliation:** 2026-09-26 UTC, deployment/workflow source at `main@d515aa5b`.
This is a setup/recovery reference, not evidence that credentials, grants or cloud resources
are currently present. Start with the [runbook inventory](README.md) and
[current operating guidance](CURRENT_OPERATIONS.md).

This runbook covers one-time prerequisites for `deploy.yml` (the dispatch entry point), its
reusable Azure deployment workflows, and `terraform-azure.yml`. The existing demo environment
already has recorded successful deployments; create identities/resources only for an explicitly
approved new setup or a diagnosed missing prerequisite.

Azure workflow login uses **OIDC (Workload Identity Federation)**, not an Azure client-secret
password. This does not mean the application has no database/API credentials. The three OIDC
identifier keys are `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` and `AZURE_SUBSCRIPTION_ID`.
An authorized operator may resolve them from the private `.env.secrets` or existing GitHub
configuration; this audit did not read either. Do not print or commit private configuration.

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

The historical bootstrap used the two subscription-level roles below. They are **not** an
instruction to grant broad rights again. Before creating assignments, approve the exact identity
and scopes and reconcile existing assignments with the resources the selected workflow manages.

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

Match the subject to the job that actually requests an OIDC token. A PR job uses the
`pull_request` subject; main-ref login jobs use `ref:refs/heads/main`; Terraform's apply job
declares `environment: production` and uses the environment subject. The deployment dispatcher's
separate production approval job does not make every downstream login environment-scoped.
Do not add federated credentials for arbitrary feature branches to bypass the main-only gates.

```bash
# For pull requests (terraform-azure.yml plan path)
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters '{
    "name": "github-pull-request",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:vibhanshu-agarwal/wealthmgmtandportfoliotracker:pull_request",
    "audiences": ["api://AzureADTokenExchange"]
  }'

# For main-ref Azure login jobs (including reusable deploy workflows and remote-plan)
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters '{
    "name": "github-main",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:vibhanshu-agarwal/wealthmgmtandportfoliotracker:ref:refs/heads/main",
    "audiences": ["api://AzureADTokenExchange"]
  }'

# For the production GitHub Environment gate (terraform-azure.yml apply job only)
# Required because the apply job has `environment: production` — GitHub issues an
# environment-scoped OIDC token (subject: …:environment:production) instead of a
# branch-scoped one. The main-branch credential above does NOT cover this case.
# Added 2026-08-23 as an out-of-band prerequisite for checkpoint 9.9 apply.
az ad app federated-credential create \
  --id "$APP_ID" \
  --parameters '{
    "name": "github-production-environment",
    "issuer": "https://token.actions.githubusercontent.com",
    "subject": "repo:vibhanshu-agarwal/wealthmgmtandportfoliotracker:environment:production",
    "audiences": ["api://AzureADTokenExchange"]
  }'
```

---

## Step 4 — Provision the Terraform State Backend

> **Historical setup recorded on 2026-05-09.** Skip resource creation for the existing
> environment. If a prerequisite is missing, diagnose it under approved reads before proposing
> recovery; the list below is not a fresh cloud inventory.
>
> - Resource group: `wealth-tf-state-rg` (centralindia)
> - Storage account: `wealthtfstate` (Standard LRS)
> - Container: `tfstate`
> - `backend-azure.hcl` is at `infrastructure/terraform/azure/backend-azure.hcl` (gitignored)

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

## Step 5 — Pre-register Azure Resource Providers

> **Required before `terraform apply`.** The Terraform provider is configured with
> `resource_provider_registrations = "none"`. The historical setup used manual
> registration rather than granting CI subscription-scope auto-registration rights;
> this source audit did not re-check the principal's current RBAC. Confirm all five
> required RPs are registered before apply; register a missing RP only under approved scope.
>
> If any RP is missing, `terraform apply` fails with a cryptic API-version error.

```bash
# Register all five required Resource Providers
for rp in Microsoft.App Microsoft.OperationalInsights Microsoft.ContainerRegistry Microsoft.CognitiveServices Microsoft.Web; do
  az provider register --namespace $rp
  echo "Registered $rp"
done

# Wait for all to reach "Registered" state (may take 1–5 minutes each)
az provider list \
  --query "[?namespace=='Microsoft.App' || namespace=='Microsoft.OperationalInsights' || namespace=='Microsoft.ContainerRegistry' || namespace=='Microsoft.CognitiveServices' || namespace=='Microsoft.Web'].{namespace:namespace, state:registrationState}" \
  --output table
```

**PowerShell equivalent (Windows):**

```powershell
foreach ($rp in @("Microsoft.App", "Microsoft.OperationalInsights", "Microsoft.ContainerRegistry", "Microsoft.CognitiveServices", "Microsoft.Web")) {
    Write-Host "Registering $rp..."
    az provider register --namespace $rp --wait
    Write-Host "$rp registered."
}
```

Re-run the query until all five show `Registered`. Do not proceed to Step 7 until all are registered.

---

## Step 6 — Verify Azure OpenAI Quota

> **Required before `terraform apply`.** The Terraform config provisions a
> `gpt-4o-mini` deployment with `capacity = 10` (10K tokens/min) in `eastus`.
> Capacity is in **thousands** of tokens/minute: a value of 10 means 10K TPM, not 10 TPM.
> If available quota in the matching Azure usage units is below the required capacity,
> `terraform apply` fails with a quota error.

```bash
# Check current quota and usage for gpt-4o-mini in eastus
az cognitiveservices usage list \
  --location eastus \
  --query "[?name.value=='OpenAI.Standard.gpt-4o-mini'].{name:name.value, currentValue:currentValue, limit:limit}" \
  --output table
```

If `limit - currentValue < 10`, either:
- Request a quota increase via the Azure portal (Cognitive Services → Quotas)
- Or reduce `openai_deployment_capacity` in `infrastructure/terraform/azure/variables.tf`

---

## Step 7 — Populate `.env.secrets` and Sync to GitHub

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
   ```

   > `AZURE_BACKEND_HCL` cannot be set via `sync-secrets.sh` because it is a
   > multi-line value. Set it directly from the file instead (Step 7.4 below).

3. Sync the three OIDC secrets to GitHub:

   **Scope warning:** `sync-secrets.sh` calls `gh secret set -f` on the **whole input file**;
   it does not filter to these three keys. Approve that complete set before using the script,
   or set only the approved individual secrets. Never publish a private file as evidence.

   ```bash
   ./scripts/sync-secrets.sh .env.secrets
   ```

4. Set `AZURE_BACKEND_HCL` directly from the file (multi-line values must be
   piped, not included in the `.env` file):

   ```bash
   gh secret set AZURE_BACKEND_HCL \
     < infrastructure/terraform/azure/backend-azure.hcl
   ```

5. After the first `terraform apply` succeeds, retrieve the SWA deployment token
   and add it as a GitHub secret. The token is needed by `deploy-azure.yml` to
   upload the Next.js static export to Azure Static Web Apps:

   ```bash
   # Get the SWA deployment token (replace with your resource group and SWA name)
   SWA_TOKEN=$(az staticwebapp secrets list \
     --name wealth-prod-swa \
     --resource-group wealth-azure-prod-rg \
     --query properties.apiKey \
     --output tsv)

   gh secret set SWA_DEPLOYMENT_TOKEN --body "$SWA_TOKEN"
   ```

---

## Step 8 — Verify

After authorized setup, a separately approved **structural** plan can validate workflow wiring:

```bash
gh workflow run terraform-azure.yml \
  --ref main \
  --field action=plan
```

The current `pr-plan` path should:
1. Log in via OIDC (no password prompt)
2. Replace the Azure backend with a temporary local-backend override and run `terraform init`
3. Run `terraform validate`
4. Run `terraform plan`
5. Run the mandatory structural plan assertions (including P1/P5, observability, runner-env,
   ingress and repair-Job contracts).

This path still authenticates to Azure and may perform provider reads. It is not an offline
test and **cannot preview the existing environment's delta**. For live-state preview, use
`action=remote-plan` on `main` with the reviewed `expected_main_sha`, exact four-service
`deployed_image_tags_json`, selected `change_profile`, and any profile-required portfolio digest.
`action=apply` needs its own authorization and the production Environment gate; it regenerates
its plan rather than applying a saved remote-plan artifact. See [current operations](CURRENT_OPERATIONS.md).
Do not dispatch `deploy-azure.yml` directly: it is reusable-workflow-only; dispatch `deploy.yml`.

---

## Secrets Reference

| Secret | Source | Used by |
|--------|--------|---------|
| `AZURE_CLIENT_ID` | Approved App Registration `appId` | Azure login jobs in the deploy workflows and `terraform-azure.yml` |
| `AZURE_TENANT_ID` | Approved tenant identifier | Same Azure login jobs |
| `AZURE_SUBSCRIPTION_ID` | Approved subscription identifier | Same Azure login jobs |
| `AZURE_BACKEND_HCL` | Content of private `backend-azure.hcl` | `terraform-azure.yml` remote-plan **and** apply; not the structural plan |
| `SWA_DEPLOYMENT_TOKEN` | Authorized SWA token retrieval | Frontend upload in `deploy-azure.yml` or `deploy-azure-frontend.yml` |

Application keys such as `AUTH_JWT_SECRET` and `POSTGRES_CONNECTION_STRING` are separate from
OIDC identifiers. Use the checked-in example and the selected workflow's declared inputs to
resolve approved keys; do not assume a private file is present in every worktree or that AWS
standby credentials are current. No secret presence, value or validity was checked in this audit.
