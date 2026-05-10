# Phase 4 — Azure Deployment Readiness Fixes, First Successful Deploy & DNS Cutover
**Date:** 2026-05-10  
**Branch:** `main`  
**Audit reference:** `docs/audit/azure_deployment.md`  
**DNS spec:** `.kiro/specs/dns-routing-azure.md`

---

## Overview

This session resolved all three blocking gaps and six operational gaps identified in the post-implementation Azure deployment audit (`docs/audit/azure_deployment.md`), drove the first end-to-end successful deployment to Azure Container Apps, and completed the DNS cutover from AWS CloudFront to Azure.

The work split into three tracks:

1. **Code and infrastructure fixes** — Java profile widening, `FxProperties` extension, `staticwebapp.config.json`, Terraform CORS wiring, plan-assertion parity on the apply path, and observability parity for `InfrastructureHealthLogger`.
2. **Live deployment debugging** — iterative resolution of IAM, RP registration, Terraform backend auth, deprecated model version, orphaned resource state, Container App provisioning timeouts, AcrPull role assignment, and SWA config validation errors.
3. **DNS cutover + Terraform stabilisation** — custom domain provisioning on SWA and ACA, Cloudflare DNS changes, CORS update, frontend rebuild, and two Terraform fixes that produced the first clean `terraform apply` with no errors.

**Final state:**

| URL | Service |
|-----|---------|
| `https://vibhanshu-ai-portfolio.dev` | Frontend (Azure Static Web Apps — custom domain) |
| `https://www.vibhanshu-ai-portfolio.dev` | Frontend alias |
| `https://api.vibhanshu-ai-portfolio.dev` | API Gateway (ACA — custom domain) |
| `https://api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` | API Gateway (default ACA hostname, still active) |
| `https://salmon-sand-00357bb10.7.azurestaticapps.net` | Frontend (default SWA hostname, still active) |

---

## Blocking Gap Fixes (committed 2026-05-09)

### Blocker 1 — Frontend has no Azure SWA deployment path

**`frontend/staticwebapp.config.json`** (new file)
- SPA routing fallback to `/index.html` for Next.js App Router
- Excludes `/_next/*` and individual static asset extensions from the fallback (one pattern per extension — SWA does not support brace-expansion globs)
- Security headers: `X-Content-Type-Options`, `X-Frame-Options`, `X-XSS-Protection`, `Referrer-Policy`

**`.github/workflows/deploy-azure.yml`** — added `deploy-frontend` job:
- Resolves the api-gateway FQDN at deploy time via `az containerapp show`
- Builds the Next.js static export with `NEXT_PUBLIC_API_BASE_URL` injected at build time
- Deploys via `Azure/static-web-apps-deploy@v1` using `SWA_DEPLOYMENT_TOKEN` secret
- Added `frontend/**` to the push path filter
- Added `needs: [preflight, deploy]` so the frontend job waits for backend services

### Blocker 2 — FX provider missing for Azure profile

**`EcbFxRateProvider.java`**
- `@Profile("aws")` → `@Profile({"aws", "azure"})`
- Constructor now resolves the rates URL from `fx.azure.rates-url` first, then `fx.aws.rates-url`, then falls back to the hardcoded `open.er-api.com` URL
- `@Scheduled` cron reads the profile-neutral `${fx.refresh-cron:0 0 6 * * *}` key

**`FxProperties.java`**
- Added `AzureProperties` record with `ratesUrl` and `refreshCron` fields
- Record signature: `FxProperties(String baseCurrency, LocalProperties local, AwsProperties aws, AzureProperties azure)`

**`application-aws.yml`** and **`application-azure.yml`** (portfolio-service)
- Added `fx.refresh-cron: "0 0 6 * * *"` as a profile-neutral key consumed by `@Scheduled`

**Test fixes** — three test classes constructed `FxProperties` with the old 3-arg canonical constructor; updated to 4-arg:
- `PortfolioServiceFxTest.java`
- `StaticFxRateProviderTest.java`
- `EcbFxRateProviderIntegrationTest.java`

### Blocker 3 — Resource Provider pre-registration undocumented

**`docs/runbooks/AZURE_SECRETS_SETUP.md`**
- New **Step 5**: `az provider register` commands for all five required RPs (`Microsoft.App`, `Microsoft.OperationalInsights`, `Microsoft.ContainerRegistry`, `Microsoft.CognitiveServices`, `Microsoft.Web`) with both bash and PowerShell equivalents, plus a wait-for-Registered verification query
- New **Step 6**: `az cognitiveservices usage list` pre-flight quota check for `gpt-4o-mini` in `eastus`
- New **Step 7 item 5**: `az staticwebapp secrets list` + `gh secret set SWA_DEPLOYMENT_TOKEN` after first apply
- Updated Secrets Reference table with `SWA_DEPLOYMENT_TOKEN`

---

## Operational Gap Fixes (committed 2026-05-09)

### §4.6 — Apply path skipped plan assertions

**`.github/workflows/terraform-azure.yml`** — apply path now runs:
1. `terraform plan -out=tfplan`
2. `terraform show -json tfplan > tfplan.json`
3. `python3 scripts/assert_plan.py tfplan.json` (P1, MANDATORY)
4. `python3 scripts/test_acr_pull_property.py tfplan.json` (P5, MANDATORY)
5. `terraform apply -auto-approve tfplan`

Previously the apply path ran `terraform apply -auto-approve` directly, bypassing both assertion scripts.

### §4.2 — CORS allowed origins not wired through Terraform

**`infrastructure/terraform/azure/variables.tf`**
- New `var.cors_allowed_origin_patterns` (type `string`, default updated to explicit custom domains — see DNS Cutover section)

**`infrastructure/terraform/azure/main.tf`**
- `APP_CORS_ALLOWED_ORIGIN_PATTERNS` wired into api-gateway `env_vars` from `var.cors_allowed_origin_patterns`

**`infrastructure/terraform/azure/outputs.tf`**
- Updated `static_web_app_default_hostname` description to prompt setting the CORS variable after first apply

### §4.4 — Observability parity incomplete

**`api-gateway/.../InfrastructureHealthLogger.java`**
- `@Profile("aws")` → `@Profile({"aws", "azure"})`
- Redis probe (`[INFRA-OK]`/`[INFRA-FAIL]`) now fires on Azure startup, making Log Analytics useful from day one

---

## Live Deployment Fixes (2026-05-10)

These issues were discovered and resolved iteratively during the first end-to-end deployment run.

### deploy-azure.yml — Preflight job added

**Problem:** The workflow fired on every push to `main` but failed immediately with `Subscription not registered for Microsoft.App` because Terraform had never been applied.

**Fix:** Added a `preflight` job that runs before both deploy jobs:
- Verifies all 5 required Azure Resource Providers are registered (read-only `az provider show` check; emits `::error::` with the exact `az provider register` remediation command if any are missing — CI does not attempt registration because the SP lacks `*/register/action` permission)
- Checks whether the resource group and ACR exist (`az group show` + `az acr show`, both with `|| true` so the step never hard-fails)
- Outputs `infra_ready=true/false`; both deploy jobs gate on `needs.preflight.result == 'success' && needs.preflight.outputs.infra_ready == 'true'`

**Iterations:**
- `az provider register` → `AuthorizationFailed` (SP lacks permission) → switched to read-only check
- `az group exists` → `Forbidden` → switched to `az group show`
- `az acr check-name` → `Forbidden` → switched to `az acr show`

### Terraform backend — OIDC auth + Reader role

**Problem 1:** `AZURE_BACKEND_HCL` secret had unquoted HCL values, causing `Variables not allowed` on `terraform init`.

**Fix:** Re-set the secret with properly quoted string values. Updated the workflow to write the secret via an env var (`printf '%s' "$BACKEND_HCL_CONTENT"`) to avoid shell interpolation issues.

**Problem 2:** `terraform init` failed with `Microsoft.Storage/storageAccounts/read` Forbidden.

**Fix:** Assigned `Reader` role on `wealth-tf-state-rg` to the SP.

**Problem 3:** `terraform init` then failed with `storageAccounts/listKeys/action` Forbidden — the azurerm backend defaults to key-based auth.

**Fix:** Added `use_oidc = true` and `use_azuread_auth = true` to the `AZURE_BACKEND_HCL` secret.

### Terraform apply — Missing subscription-scope roles

**Problem:** `terraform apply` failed with `Microsoft.Resources/subscriptions/resourceGroups/read` Forbidden.

**Fix:** Assigned `Contributor` and `User Access Administrator` at subscription scope to the SP (`object-id: 9ebca111-8e69-4806-ad76-ed239928acd6`).

### Terraform apply — Deprecated Azure OpenAI model

**Problem:** `azurerm_cognitive_deployment.gpt4o_mini` failed with `ServiceModelDeprecated: gpt-4o-mini 2024-07-18 deprecated since 03/31/2026`.

**Fix:** Updated `infrastructure/terraform/azure/main.tf` to use `gpt-4.1-mini` version `2025-04-14`.

### Terraform apply — Orphaned Container Apps not in state

**Problem:** A partial apply created 3–4 Container Apps in Azure but Terraform state was incomplete. Subsequent applies failed with `resource already exists`.

**Fix (first attempt):** Added declarative `import {}` blocks for all 4 Container Apps. Import IDs required camelCase `containerApps`.

**Fix (second attempt):** The import triggered an `update` that also timed out. Deleted all 4 orphaned Container Apps via `az containerapp delete` and let Terraform recreate them cleanly.

### Terraform apply — Container App provisioning timeout

**Problem:** `terraform apply` consistently timed out with `Failed to provision revision for container app. Error details: Operation expired.` The Container Apps were actually Running — this was a Terraform provider polling timeout specific to `centralindia`.

**Fix 1:** Extended `timeouts { create = "60m", update = "60m" }` on `azurerm_container_app` in the module.

**Fix 2:** Added `var.use_seed_image` (bool, default `false`). When `true`, all Container Apps use `mcr.microsoft.com/azuredocs/containerapps-helloworld:latest` to eliminate ACR pull as a failure mode during initial provisioning.

### deploy-azure.yml — AcrPull role not assigned

**Problem:** `az containerapp update` failed with `unable to pull image using Managed identity system for registry wealthprodacr.azurecr.io`. The `azurerm_role_assignment.acr_pull` resources were never applied (apply timed out before reaching them).

**Fix:** Manually assigned `AcrPull` role on the ACR to each Container App's system-assigned managed identity:

| Container App | Principal ID |
|---------------|-------------|
| api-gateway | `6a0e4850-b8ca-42cb-95de-4a57d785803d` |
| portfolio-service | `4978626a-f8e2-440c-b287-924b9bc04231` |
| market-data-service | `d9a5f15f-d648-4cfc-bbe3-1a486d4cff24` |
| insight-service | `9f049b17-ac65-47f1-b87d-5380d8b7d586` |

### deploy-azure.yml — SWA_DEPLOYMENT_TOKEN not set

**Problem:** `deploy-frontend` failed with `deployment_token was not provided`.

**Fix:** Retrieved the SWA deployment token via `az staticwebapp secrets list` and set it as a GitHub Actions secret.

### deploy-azure.yml — staticwebapp.config.json brace-expansion glob

**Problem:** SWA validator rejected `/*.{png,jpg,...}` — SWA does not support brace-expansion syntax.

**Fix:** Replaced with individual per-extension exclude entries (`/*.png`, `/*.jpg`, etc.).

---

## DNS Cutover (2026-05-10)

Full plan: `.kiro/specs/dns-routing-azure.md`

### Architecture decision — split domains (§4.5 resolved)

Chose Option A (split domains) over SWA Linked Backends (requires Standard tier ~$9/mo) and direct ACA CNAME (discards already-provisioned SWA):

```
vibhanshu-ai-portfolio.dev       → Azure Static Web Apps  (frontend)
www.vibhanshu-ai-portfolio.dev   → Azure Static Web Apps  (frontend)
api.vibhanshu-ai-portfolio.dev   → ACA api-gateway        (backend)
```

### Phase 1 — Azure custom domain provisioning

- `az staticwebapp hostname set --no-wait` for apex + www (SWA Free tier supports 2 custom domains)
- `az containerapp hostname add` for `api.` subdomain (captures `asuid` validation token)
- Validation tokens captured:

| Domain | Token type | Token |
|---|---|---|
| `vibhanshu-ai-portfolio.dev` | TXT `_dnsauth` | `_dzgwuue8e8ef8buumelo2dcup7swjn2` |
| `www.vibhanshu-ai-portfolio.dev` | TXT `_dnsauth.www` | `_zfcxepkotliu1qeszeswz9bj9t605pa` |
| `api.vibhanshu-ai-portfolio.dev` | TXT `asuid.api` | `45B4856B2D89175C640F00EDA589FF5A504DAD125D98033B40316EE0E62D5E74` |

### Phase 2 — Cloudflare DNS changes (manual)

- Renamed existing apex CNAME `vibhanshu-ai-portfolio.dev → d1t9eh6t95r2m3.cloudfront.net` to `_disabled-apex` (soft-disable, preserves multi-cloud rollback path)
- Left ACM validation CNAME `_641b3d12a68e7041cb0cc89474dd7130` **active** (preserves CloudFront cert renewal)
- Added 6 new Azure records (all DNS only / grey cloud, TTL 300 → raised to 3600 after cutover confirmed):

| Name | Type | Target |
|---|---|---|
| `vibhanshu-ai-portfolio.dev` | CNAME | `salmon-sand-00357bb10.7.azurestaticapps.net` |
| `www` | CNAME | `salmon-sand-00357bb10.7.azurestaticapps.net` |
| `api` | CNAME | `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` |
| `_dnsauth` | TXT | `_dzgwuue8e8ef8buumelo2dcup7swjn2` |
| `_dnsauth.www` | TXT | `_zfcxepkotliu1qeszeswz9bj9t605pa` |
| `asuid.api` | TXT | `45B4856B2D89175C640F00EDA589FF5A504DAD125D98033B40316EE0E62D5E74` |

### Phase 3 — DNS propagation + ACA cert bind

- DNS propagation confirmed via `nslookup` for all three records
- `az containerapp hostname bind --validation-method CNAME` succeeded
- All three custom domains reached `Ready` / cert `Succeeded` state

### Phase 4 — Config updates

**`infrastructure/terraform/azure/variables.tf`**
- `cors_allowed_origin_patterns` default updated to:
  `https://vibhanshu-ai-portfolio.dev,https://www.vibhanshu-ai-portfolio.dev,https://salmon-sand-00357bb10.7.azurestaticapps.net`
  (includes SWA default hostname during transition window)

**`.github/workflows/deploy-azure.yml`** — `deploy-frontend` job
- `NEXT_PUBLIC_API_BASE_URL` changed from dynamic `az containerapp show` resolution to static `https://api.vibhanshu-ai-portfolio.dev`
- `Resolve API Gateway FQDN` step retained as a non-blocking sanity log

**CORS applied directly** via `az containerapp update` (Terraform apply timed out before the env var update landed — see Terraform stabilisation below).

### Phase 5 — Smoke tests (all passed)

| Check | Result |
|---|---|
| `https://vibhanshu-ai-portfolio.dev` | ✅ 200 OK |
| `https://www.vibhanshu-ai-portfolio.dev` | ✅ 200 OK |
| `https://api.vibhanshu-ai-portfolio.dev/actuator/health` | ✅ `{"status":"UP"}` |
| CORS preflight from `vibhanshu-ai-portfolio.dev` | ✅ `Access-Control-Allow-Origin: https://vibhanshu-ai-portfolio.dev` |

---

## Terraform Stabilisation (2026-05-10)

Two fixes that produced the **first clean `terraform apply` with zero errors** (run `25625179245`).

### Fix 1 — `lifecycle { ignore_changes = [template] }` on Container App module

**Problem:** Every `terraform apply` triggered a new Container App revision (image tag update), which consistently timed out polling in `centralindia` and reported `MANIFEST_UNKNOWN` — a false failure. The Container Apps were actually `Succeeded` and `Running`.

**Root cause:** Terraform was managing the `template` block (including image tag) on every apply. Image updates are owned by `deploy-azure.yml` via `az containerapp update`, so Terraform managing them is redundant and harmful.

**Fix:** Added `lifecycle { ignore_changes = [template] }` to `infrastructure/terraform/azure/modules/container-app/main.tf`. Terraform now owns infrastructure configuration (env vars, secrets, ingress, scaling, identity) but delegates image/revision management to the deploy workflow.

**Commit:** `cb84a83`

### Fix 2 — Import AcrPull role assignments into Terraform state

**Problem:** Every `terraform apply` failed with `409 RoleAssignmentExists` for all four `azurerm_role_assignment.acr_pull` resources. These were created manually during Phase 4 live deploy (when the initial apply timed out before reaching them). Terraform had no record of them in state and tried to recreate them on every run.

**Fix:** Added four declarative `import {}` blocks to `infrastructure/terraform/azure/main.tf` with the real role assignment resource IDs retrieved via `az role assignment list`.

| Container App | Role Assignment ID |
|---|---|
| api-gateway | `ee08a243-ba2f-41e3-93f7-5c39456832b4` |
| portfolio-service | `9b900371-7826-44a1-8670-f5a9ddd83e0d` |
| market-data-service | `0d82add1-b48a-48a1-93e1-340339e89507` |
| insight-service | `54365adf-9188-458a-a5a2-01e43606e3ea` |

**Commit:** `0466325`

---

## Commit Log (2026-05-09 → 2026-05-10)

| Commit | Summary |
|--------|---------|
| `67573b7` | fix: resolve all Azure deployment blockers and operational gaps |
| `45395eb` | fix: add preflight job to deploy-azure.yml to guard against unprovisioned infra |
| `bf6e706` | fix: preflight checks RPs instead of registering them, propagates failure correctly |
| `4f4d9dc` | fix: use az acr show instead of az acr check-name in preflight (Forbidden) |
| `3d202f6` | fix: use az group show instead of az group exists in preflight (Forbidden on exists) |
| `14c5328` | docs: add PowerShell equivalent for RP registration in runbook |
| `fdc5a5c` | fix: write AZURE_BACKEND_HCL via env var to avoid shell interpolation issues |
| `cd95eeb` | fix: update Azure OpenAI model from deprecated gpt-4o-mini 2024-07-18 to gpt-4.1-mini 2025-04-14 |
| `ad32ef6` | fix: add use_seed_image flag for initial terraform apply before ACR images exist |
| `f80782b` | fix: remove empty env block causing YAML parse error in terraform-azure.yml |
| `2086a22` | fix: import existing Container Apps into state + extend provisioning timeout to 60m |
| `1f74576` | fix: correct Container App import IDs to use camelCase containerApps (not containerapps) |
| `810a819` | fix: replace brace-expansion glob in staticwebapp.config.json exclude patterns |
| `0af5b3f` | feat: DNS cutover to custom domains — update CORS and frontend API URL |
| `cb84a83` | fix: ignore container app template changes in Terraform to prevent centralindia polling timeouts |
| `0466325` | fix: import existing AcrPull role assignments into Terraform state |

---

## Manual Azure Operations Performed

The following one-time operations were performed via Azure CLI (not automated in CI — SP lacks the required permissions):

| Operation | Command / Notes |
|-----------|----------------|
| Register 5 Azure Resource Providers | `az provider register --namespace <RP> --wait` for each of `Microsoft.App`, `Microsoft.OperationalInsights`, `Microsoft.ContainerRegistry`, `Microsoft.CognitiveServices`, `Microsoft.Web` |
| Assign `Reader` on TF state RG | Scope: `/subscriptions/.../resourceGroups/wealth-tf-state-rg` |
| Assign `Contributor` at subscription scope | Scope: `/subscriptions/ee625b3f-7cb1-4482-be3c-4363c5d76d23` |
| Assign `User Access Administrator` at subscription scope | Same scope as above |
| Assign `AcrPull` on ACR for each Container App | 4 role assignments, one per Container App managed identity (now imported into TF state) |
| Set `SWA_DEPLOYMENT_TOKEN` GitHub secret | Retrieved via `az staticwebapp secrets list` |
| Update `AZURE_BACKEND_HCL` GitHub secret | Re-set with quoted HCL values + `use_oidc=true` + `use_azuread_auth=true` |
| Rename CloudFront apex CNAME to `_disabled-apex` | Cloudflare DNS — soft-disable for multi-cloud standby |
| Add 6 Azure DNS records | Cloudflare DNS — 3 CNAMEs + 3 TXT validation records |

---

## Infrastructure State (Post-Cutover)

All resources in resource group `wealth-azure-prod-rg` (centralindia):

| Resource | Name | Status |
|----------|------|--------|
| Container Registry | `wealthprodacr` | Running |
| Log Analytics Workspace | `wealth-prod-la` | Active |
| Container Apps Environment | `wealth-prod-aca-env` | Running |
| Container App — api-gateway | `api-gateway` | Running (external ingress) |
| Container App — portfolio-service | `portfolio-service` | Running (internal) |
| Container App — market-data-service | `market-data-service` | Running (internal) |
| Container App — insight-service | `insight-service` | Running (internal) |
| Azure OpenAI Account | `wealth-prod-aoai` | Active |
| OpenAI Deployment | `gpt-4o-mini` (model: `gpt-4.1-mini 2025-04-14`) | Succeeded |
| Static Web App | `wealth-prod-swa` | Active |

Custom domains (all `Ready`):

| Domain | Target | Status |
|---|---|---|
| `vibhanshu-ai-portfolio.dev` | SWA | Ready |
| `www.vibhanshu-ai-portfolio.dev` | SWA | Ready |
| `api.vibhanshu-ai-portfolio.dev` | ACA api-gateway | Ready (cert Succeeded) |

TF state backend: `wealth-tf-state-rg` / `wealthtfstate` / container `tfstate` / key `azure/terraform.tfstate`

Terraform state: **fully reconciled** as of run `25625179245` (first clean apply with zero errors).

---

## Multi-Cloud Standby

AWS is **not decommissioned** — it is soft-disabled as a standby cloud while Lambda throttling is resolved. The following Cloudflare records remain active indefinitely:

| Record | Purpose |
|---|---|
| `_disabled-apex → d1t9eh6t95r2m3.cloudfront.net` | Fast re-enable path back to AWS CloudFront |
| `_641b3d12a68e7041cb0cc89474dd7130 → ...acm-validations.aws` | ACM certificate renewal for the CloudFront cert |

To re-enable AWS: lower Azure apex CNAME TTL to 300, rename `_disabled-apex` back to apex, verify DNS, raise TTL to 3600.

---

## Known Remaining Items

- **Live environment seeding pending** — the seed step in `ci-verification.yml` is still commented out. All four conditions to uncomment it are now met (Azure live, DNS cutover complete, health endpoint UP, `CLOUD_PROVIDER=azure`).
- **CORS transition window** — `cors_allowed_origin_patterns` includes the SWA default hostname (`salmon-sand-*.azurestaticapps.net`). Narrow to just the two custom domains after a few days of stability.
- **Azure synthetic monitoring** — no `tests/e2e/azure-synthetic/**` equivalent exists yet (audit §4.3).
- **`InfrastructureHealthLogger` parity** — `portfolio-service` and `market-data-service` still have `@Profile("aws")` on their `InfrastructureHealthLogger` beans (audit §4.4, partially fixed for api-gateway only).
- **Node.js 20 deprecation warning** in CI — `actions/checkout@v4`, `azure/login@v2`, `hashicorp/setup-terraform@v3` will need updating before September 2026 when Node.js 20 is removed from GitHub Actions runners.
- **Import blocks in `main.tf`** — the four Container App `import {}` blocks and four AcrPull role assignment `import {}` blocks are now no-ops (resources are in state). They can be removed after the next clean `terraform plan` confirms zero drift.
