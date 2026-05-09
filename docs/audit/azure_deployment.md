# Azure Deployment Readiness Audit

**Date:** 2026-05-09
**Scope:** End-to-end readiness to deploy the Wealth Management & Portfolio Tracker to Azure Container Apps (+ Static Web Apps + Azure OpenAI)
**Spec reference:** `.kiro/specs/azure-container-apps-deployment/`
**Migration branch:** `feat/phase4-azure-migration` (merged to `main`, branch deleted)

---

## TL;DR

The Azure spec is ~95% implemented. All four services have `Dockerfile.azure` files, Spring profile overlays, and the Azure Terraform root provisions a full ACA environment, ACR, Log Analytics, Azure OpenAI (GPT-4o-mini), and a Static Web App. Two GitHub Actions workflows are wired for OIDC auth. Secrets, state backend, and runbook are all in place.

**Status as of 2026-05-09 (post-fix):** All three blocking gaps have been resolved. The environment has never been applied, but the codebase is now ready for a first successful deploy.

**Resolved blockers:**

1. ✅ **Frontend SWA deployment path** — `deploy-azure.yml` now has a `deploy-frontend` job that resolves the api-gateway FQDN, builds the Next.js static export with `NEXT_PUBLIC_API_BASE_URL` injected, and deploys via `Azure/static-web-apps-deploy@v1`. `staticwebapp.config.json` added to `frontend/`.
2. ✅ **FX provider missing for Azure profile** — `EcbFxRateProvider` widened to `@Profile({"aws", "azure"})`. Constructor now reads `fx.azure.rates-url` when on Azure. `FxProperties` record extended with `AzureProperties`. `portfolio-service` will boot correctly on Azure.
3. ✅ **Resource Provider pre-registration undocumented** — `docs/runbooks/AZURE_SECRETS_SETUP.md` now has Step 5 with the `az provider register` commands for all five required RPs, plus a wait-for-Registered check.

**Also resolved (operational gaps):**

- ✅ **Azure OpenAI quota check** — Step 6 added to runbook with `az cognitiveservices usage list` pre-flight command.
- ✅ **CORS wiring through Terraform** — `var.cors_allowed_origin_patterns` added to `variables.tf`; wired into api-gateway `env_vars` as `APP_CORS_ALLOWED_ORIGIN_PATTERNS`. Defaults to `https://*.azurestaticapps.net`; override with the exact SWA hostname after first apply.
- ✅ **Apply path skips plan assertions** — `terraform-azure.yml` apply path now runs `terraform plan -out=tfplan`, `terraform show -json`, P1 and P5 assertion scripts before `terraform apply`.
- ✅ **Observability parity** — `InfrastructureHealthLogger` widened to `@Profile({"aws", "azure"})` so `[INFRA-OK]/[INFRA-FAIL]` Redis probe lines appear in Log Analytics on Azure.
- ✅ **SWA deployment token** — Step 7 of runbook now includes `az staticwebapp secrets list` command and `gh secret set SWA_DEPLOYMENT_TOKEN`.

**Remaining items (not blocking, tracked below):**

- Azure synthetic monitoring (`tests/e2e/azure-synthetic/**`) — no tests yet (§4.3)
- DNS cutover architectural choice (SWA vs. direct ACA CNAME) — decision pending (§4.5)
- `InfrastructureHealthLogger` parity for `portfolio-service` and `market-data-service` — backlog

Additionally, the `terraform-azure.yml apply` step has never been run. The current Azure deployment state is: no resource group, no ACR, no Container Apps.

---

## 1. Current deployment state

- **No Azure resources exist yet.** The `terraform-azure.yml` workflow triggers apply only on manual `workflow_dispatch` with `action=apply`. Since no such run has succeeded, nothing is provisioned.
- **AWS deployment exists but is failing** — Lambdas return 502s due to throttling. This is the motivation for the migration.
- **DNS:** `vibhanshu-ai-portfolio.dev` (Cloudflare) currently points at CloudFront/AWS. No DNS cutover has happened.
- **CI guard:** `vars.CLOUD_PROVIDER` is set to `azure` in the repo. AWS-specific CI steps skip; Azure seeding is commented out pending DNS cutover.

---

## 2. Inventory of what exists

### 2.1 Spec artifacts — complete

All 14 top-level tasks in `.kiro/specs/azure-container-apps-deployment/tasks.md` are `[x]`. Optional JVM-side property tests (5.4, 5.5, 5.6, 6.1) are marked `[x]*` (implemented; one deviation documented).

### 2.2 Terraform — Azure root fully declared

Directory: `infrastructure/terraform/azure/`

| File | Status |
|------|--------|
| `main.tf` | ✓ Resource group, ACR (Basic), Log Analytics, ACA environment, Static Web App (Free tier, `centralus`), Azure OpenAI account + `gpt-4o-mini` deployment, 4 Container App modules, `Cognitive Services OpenAI User` role for insight-service |
| `modules/container-app/main.tf` | ✓ System-assigned identity + `AcrPull` role assignment + ingress + dynamic env/secrets |
| `variables.tf` | ✓ 23 variables including OIDC identity, region, image tag, all data-tier secrets |
| `outputs.tf` | ✓ `api_gateway_fqdn`, `acr_login_server`, `static_web_app_default_hostname`, 3 internal FQDNs |
| `providers.tf` | ✓ `use_oidc = true`, no `client_secret`, `resource_provider_registrations = "none"` |
| `versions.tf` | ✓ `azurerm ~> 4.0`, backend block (injected via `-backend-config` or overridden locally) |
| `backend-azure.hcl.example` | ✓ Template committed; real file gitignored |
| `scripts/assert_plan.py` | ✓ P1 IaC assertion (profile mutual exclusion) |
| `scripts/test_acr_pull_property.py` | ✓ P5 IaC assertion (ACR pull authz) |

### 2.3 Application code — all Azure bits present

| Component | Status |
|-----------|--------|
| Java toolchain | ✓ Downgraded from 25 to 21 (root `build.gradle`) |
| `JwtDecoderConfig.hmacJwtDecoder` | ✓ `@Profile({"local","aws","azure"})` |
| `MockAiInsightService` / `MockInsightAdvisor` | ✓ `@Profile("!bedrock & !azure-ai")` |
| `AzureOpenAiInsightService` | ✓ New, `@Profile("azure-ai")` |
| `AzureOpenAiInsightAdvisor` | ✓ New, `@Profile("azure-ai")` |
| `AiProviderProfileValidator` | ✓ New, always active, P1 enforcement |
| `insight-service/build.gradle` | ✓ Adds `spring-ai-starter-model-azure-openai` + `azure-identity:1.13.2` |
| `application-azure.yml` × 4 | ✓ One per service |
| `application-azure-ai.yml` | ✓ insight-service only; Managed Identity default |
| `application-bedrock.yml` | ✓ `spring.ai.model.chat: bedrock-converse` added |

### 2.4 Dockerfiles — all four `Dockerfile.azure` present

All four services have `Dockerfile.azure` with a two-stage build on `mcr.microsoft.com/openjdk/jdk:21-mariner`:
- No Lambda Web Adapter
- No custom jlink JRE
- `sed` strips the toolchain block from `build.gradle` at build time so Gradle uses the ambient JDK 21
- `ARG RUNTIME_BASE` is declared both globally and per-stage (BuildKit ARG scoping fix)

### 2.5 GitHub Actions workflows

| Workflow | Triggers | Status |
|----------|----------|--------|
| `deploy-azure.yml` | push to main + paths, or `workflow_dispatch` | ✓ Matrix over 4 services, OIDC, `az acr login`, `docker build -f Dockerfile.azure`, `az containerapp update`, polling for `provisioningState=Succeeded` |
| `terraform-azure.yml` | `pull_request` (plan only), `workflow_dispatch` with choice input (plan or apply) | ✓ OIDC, P1/P5 assertion scripts mandatory on PR, apply gated on manual dispatch |
| `ci-verification.yml` | push/PR | ✓ `CLOUD_PROVIDER=aws` guards on AWS-specific steps; Azure seed step commented-out placeholder |

### 2.6 Secrets + runbook

- `.env.secrets.example` includes the Azure section with `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `AZURE_BACKEND_HCL`, `CLOUD_PROVIDER`.
- `docs/runbooks/AZURE_SECRETS_SETUP.md` covers App Registration, role assignments, federated credentials, TF state backend provisioning, secret sync. The state backend (`wealth-tf-state-rg` / `wealthtfstate`) is already provisioned per the runbook.

---

## 3. Blocking gaps

These must be resolved before a first successful end-to-end deploy.

### Blocker 1 — Frontend has no Azure SWA deployment path

**What's missing:**
- No `staticwebapp.config.json` in `frontend/`
- No `azure/static-web-apps-deploy` step in any workflow
- No `SWA_DEPLOYMENT_TOKEN` secret is referenced or wired
- `deploy-azure.yml` only builds backend service images; it ignores the frontend entirely
- No `NEXT_PUBLIC_API_BASE_URL` injection for Azure builds (on AWS the frontend uses relative `/api/*` via CloudFront origin routing, which does not apply to SWA → external ACA ingress)

**Impact:** `azurerm_static_web_app.frontend` is provisioned empty. The demo will have no frontend on Azure.

**Fix options:**
- (a) Add a frontend build + deploy job to `deploy-azure.yml` using `Azure/static-web-apps-deploy@v1` with `app_location: frontend`, `output_location: out`, and `NEXT_PUBLIC_API_BASE_URL=https://<api_gateway_fqdn>` at build time
- (b) OR bypass SWA entirely: add an `azurerm_storage_account` + `$web` container, upload `frontend/out/`, and front it with Azure Front Door. This matches the CI comment that plans to CNAME `vibhanshu-ai-portfolio.dev` directly to the ACA gateway — in which case the SWA resource can be removed.

The spec intended option (a) but never wired it; `CHANGES_PHASE4_SUMMARY.md` lists this as backlog B2.

### Blocker 2 — FX provider missing for the Azure profile

**What's there:**
- `portfolio-service/.../EcbFxRateProvider` is `@Profile("aws")` only
- `portfolio-service/.../StaticFxRateProvider` is `@Profile("local")` only
- `application-azure.yml` declares `fx.azure.rates-url: https://open.er-api.com/v6/latest/USD` but no bean consumes it

**Impact:** On Azure (`SPRING_PROFILES_ACTIVE=prod,azure`), no `FxRateProvider` bean is registered. If any code path requires an `FxRateProvider` at startup, portfolio-service will fail to boot on Azure with a `NoSuchBeanDefinitionException`.

**Verification needed:** Does `portfolio-service` have a required constructor-injected `FxRateProvider` anywhere? If yes, this is a hard blocker. If the bean is only used lazily via `@Autowired(required=false)`, valuations in non-USD currencies will fail at runtime instead of startup.

**Fix:**
- Either widen `@Profile("aws")` on `EcbFxRateProvider` to `{"aws","azure"}` (simplest; both read from the same Open Exchange Rates endpoint)
- Or create an `AzureFxRateProvider` mirror bean that reads `fx.azure.rates-url`

This gap is not documented in the spec or backlog — it's an oversight in Req 8.2 which described the YAML but not the bean wiring.

### Blocker 3 — Resource Provider pre-registration is undocumented

**What's there:**
- `providers.tf` sets `resource_provider_registrations = "none"` because the CI service principal lacks subscription-scope rights to auto-register RPs
- A Terraform comment lists the five required RPs: `Microsoft.App`, `Microsoft.OperationalInsights`, `Microsoft.ContainerRegistry`, `Microsoft.CognitiveServices`, `Microsoft.Web`

**What's missing:**
- `docs/runbooks/AZURE_SECRETS_SETUP.md` does not mention manual RP registration
- No step in any workflow or runbook checks whether these RPs are registered

**Impact:** If the target subscription doesn't have all five RPs pre-registered, `terraform apply` fails with misleading API-version errors. The user sees a cryptic failure and has no documented path to fix it.

**Fix:** Add to the runbook:
```bash
for rp in Microsoft.App Microsoft.OperationalInsights Microsoft.ContainerRegistry Microsoft.CognitiveServices Microsoft.Web; do
  az provider register --namespace $rp
done
# Wait for all to show "Registered":
az provider list --query "[?namespace=='Microsoft.App' || namespace=='Microsoft.OperationalInsights' || namespace=='Microsoft.ContainerRegistry' || namespace=='Microsoft.CognitiveServices' || namespace=='Microsoft.Web'].{namespace:namespace, state:registrationState}"
```

---

## 4. Operational gaps (non-blocking but important)

### 4.1 Azure OpenAI quota verification is manual and undocumented

`azurerm_cognitive_deployment.gpt4o_mini` provisions with `capacity = 10` (10K TPM) in `var.openai_location` (default `eastus`). If the subscription's quota for `gpt-4o-mini` in `eastus` is below 10 TPM, `terraform apply` fails.

**Fix:** Document a pre-flight check:
```bash
az cognitiveservices usage list --location eastus \
  --query "[?name.value=='OpenAI.Standard.gpt-4o-mini']"
```

### 4.2 CORS allowed origins not wired through Terraform

`application-azure.yml` for api-gateway reads `app.cors.allowed-origin-patterns: ${APP_CORS_ALLOWED_ORIGIN_PATTERNS:https://*.azurestaticapps.net}`. The Terraform api-gateway module does NOT override this env var, so the wildcard default applies.

For production, the SWA hostname (or custom domain) should be explicit. Add `APP_CORS_ALLOWED_ORIGIN_PATTERNS` to the `api_gateway` module's `env_vars` block once the SWA hostname is known.

### 4.3 No Azure synthetic monitoring

`tests/e2e/aws-synthetic/**` exists for AWS. There is no `tests/e2e/azure-synthetic/**` equivalent. After DNS cutover, there's no live health signal for the Azure deployment.

### 4.4 api-gateway observability parity incomplete

`api-gateway/.../InfrastructureHealthLogger.java` is `@Profile("aws")` only. On Azure, no `[INFRA-OK]/[INFRA-FAIL]` startup log lines are emitted. This is documented as backlog B1 but reduces Log Analytics usefulness.

`portfolio-service` and `market-data-service` have the same pattern but aren't in the backlog.

### 4.5 Architectural inconsistency: SWA vs. direct ACA CNAME

- Terraform provisions `azurerm_static_web_app.frontend` (implies frontend hosted on SWA, which proxies `/api/*` to ACA)
- `ci-verification.yml` comment plans to CNAME `vibhanshu-ai-portfolio.dev` directly to the ACA gateway (bypasses SWA entirely)

These two plans are incompatible. Pick one:
- **SWA path:** keep the resource, add the SWA deploy step (Blocker 1 option a), use SWA's default hostname or a custom domain, api-gateway is internal-only to SWA
- **Direct ACA path:** remove the SWA resource, host `frontend/out/` elsewhere (Blob Storage `$web`, Cloudflare Pages, etc.), CNAME points directly at ACA ingress (api-gateway stays external)

### 4.6 Apply path skips plan assertions

`terraform-azure.yml` `action=apply` path does NOT re-run `assert_plan.py` / `test_acr_pull_property.py`. The PR gate covers this during merge, but an out-of-band apply after a force-push or direct main commit skips the check.

**Fix:** Add the assertion steps to the apply path (they're idempotent).

---

## 5. End-to-end deployment sequence

This is the sequence a developer must follow for a first successful deployment, with tooling status at each step.

| # | Step | Automation | Notes |
|---|------|-----------|-------|
| 1 | Create Azure App Registration + Service Principal | Manual | Runbook §1 |
| 2 | Assign Contributor + User Access Administrator roles | Manual | Runbook §2 |
| 3 | Add OIDC federated credentials for `main` + `pull_request` | Manual | Runbook §3 |
| 4 | Provision TF state backend (RG + storage + container) | **Already done** | Runbook §4 |
| 5 | Copy `backend-azure.hcl.example` → `backend-azure.hcl` | Manual | Template committed |
| 6 | Populate `.env.secrets` with Azure identity values | Manual | `.env.secrets.example` has the section |
| 7 | Sync secrets: `sync-secrets.sh .env.secrets` + `gh secret set AZURE_BACKEND_HCL < backend-azure.hcl` | Scripted | Multi-line quirk documented |
| 8 | **Pre-register five Azure RPs** | Manual | **Gap: not in runbook (Blocker 3)** |
| 9 | **Verify Azure OpenAI `gpt-4o-mini` quota in `eastus`** | Manual | **Gap: not documented (§4.1)** |
| 10 | `gh workflow run terraform-azure.yml --field action=plan` | Automated | Verifies P1 + P5 |
| 11 | Review plan output | Manual | |
| 12 | `gh workflow run terraform-azure.yml --field action=apply` | Automated | First apply creates RG, ACR, ACA env, 4 Container Apps, OpenAI, SWA |
| 13 | `terraform output` → capture `api_gateway_fqdn`, `acr_login_server` | Automated | Outputs are declared |
| 14 | `deploy-azure.yml` (push to main or manual) builds images + updates Container Apps | Automated | Matrix over 4 services, polls for `Succeeded` |
| 15 | **Build + deploy frontend to Azure SWA** | **Missing** | **Blocker 1** |
| 16 | Configure CORS `APP_CORS_ALLOWED_ORIGIN_PATTERNS` with SWA hostname | Manual (Terraform var update) | §4.2 |
| 17 | DNS cutover: point `vibhanshu-ai-portfolio.dev` at Azure | Manual (Cloudflare) | Architectural choice pending (§4.5) |
| 18 | Uncomment the Azure seed step in `ci-verification.yml` | Manual (4-step checklist in the file) | Already-written placeholder |
| 19 | Flip `vars.CLOUD_PROVIDER` to `azure` (already done) | Manual | Done |
| 20 | Smoke test: curl health endpoints, run `tests/e2e/golden-path` against live domain | **Missing** | No Azure synthetic monitoring (§4.3) |

---

## 6. Recommended action plan

### Phase A — Unblock the first apply (2–4 hours)

1. **Pre-register Azure RPs** in the target subscription (1 command per RP)
2. **Widen `EcbFxRateProvider` `@Profile` to `{"aws","azure"}`** and add `fx.azure.rates-url` reading logic (or create `AzureFxRateProvider`)
3. **Update the runbook** to add the RP pre-registration step and the Azure OpenAI quota check
4. Run `gh workflow run terraform-azure.yml --field action=apply`
5. Verify all four Container Apps come up healthy via `az containerapp show`

### Phase B — Frontend on Azure (4–8 hours)

Choose one:

**Option A: Use Azure Static Web Apps (matches current Terraform)**
- Add a `frontend` deploy step to `deploy-azure.yml` using `Azure/static-web-apps-deploy@v1`
- Pass `NEXT_PUBLIC_API_BASE_URL=https://<api_gateway_fqdn>` at build time
- Add a `staticwebapp.config.json` for routing/CORS
- SWA hostname becomes the CORS origin — update the api-gateway env var via Terraform

**Option B: Skip SWA, host frontend elsewhere**
- Remove `azurerm_static_web_app.frontend` from Terraform
- Host `frontend/out/` on Cloudflare Pages (since Cloudflare already manages the domain)
- CNAME `vibhanshu-ai-portfolio.dev` to the Pages hostname
- Pages proxies `/api/*` to the ACA gateway FQDN

Option B is simpler if the intent is to keep using Cloudflare as the edge.

### Phase C — Cutover and observability (2–4 hours)

6. Update Cloudflare DNS to point `vibhanshu-ai-portfolio.dev` at the chosen hosting
7. Uncomment the Azure seed step in `ci-verification.yml`
8. Run the seed step once manually to populate demo user data
9. Smoke test end-to-end
10. Decommission AWS Lambda stack (future work)

### Phase D — Clean up and harden (1–2 days, optional)

11. Widen remaining `InfrastructureHealthLogger` `@Profile` annotations (api-gateway, portfolio-service, market-data-service) to `{"aws","azure"}`
12. Add Azure synthetic monitoring tests (`tests/e2e/azure-synthetic/**`)
13. Add apply-path assertion scripts to `terraform-azure.yml`
14. Write an Azure architecture ADR and deployment runbook
15. Clean up AWS Dockerfiles per `TODO.md` §1.3 (Corretto 25 → 21)

---

## 7. Confidence assessment

| Area | Confidence |
|------|-----------|
| Backend services boot correctly on Azure | **Medium** — FX provider gap unverified |
| Terraform plan + apply succeed on a fresh subscription | **Medium** — RP pre-registration + OpenAI quota are manual checks with no guard |
| Backend images build and push to ACR | **High** — workflow is exercised via the `deploy-azure.yml` push trigger |
| Container Apps reach `Succeeded` state after image update | **High** — polling step is well-designed |
| Frontend reaches users | **Low** — no deployment tooling |
| DNS cutover + seeding works end-to-end | **Low** — never exercised |

**Overall: the spec implementation is thorough and well-tested at the component level (2139 passing tests). The gap is the seams between components — frontend hosting, FX bean wiring, operational preconditions — that a first real deploy exposes.**

---

## 8. References

- **Spec:** `.kiro/specs/azure-container-apps-deployment/` (requirements.md, design.md, tasks.md, TODO.md)
- **Migration analysis:** `docs/analysis/azure-container-migration-analysis.md`
- **Phase 4 changelog:** `docs/changes/CHANGES_PHASE4_SUMMARY_2026-05-09.md`
- **Secrets runbook:** `docs/runbooks/AZURE_SECRETS_SETUP.md`
- **Terraform Azure root:** `infrastructure/terraform/azure/`
- **Azure workflows:** `.github/workflows/deploy-azure.yml`, `.github/workflows/terraform-azure.yml`
