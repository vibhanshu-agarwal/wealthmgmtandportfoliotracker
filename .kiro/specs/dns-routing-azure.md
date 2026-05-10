# DNS Routing — Cloudflare → Azure Cutover Plan

**Status:** Reviewed, amended (awaiting execution)
**Date:** 2026-05-10
**Related:**
- `docs/changes/CHANGES_PHASE4_SUMMARY_2026-05-10.md` — Known Remaining Items → DNS cutover pending
- `docs/audit/azure_deployment.md` §4.5 — Architectural inconsistency: SWA vs. direct ACA CNAME

## Amendment log

| # | Amendment | Source |
|---|---|---|
| 1 | Apex CNAME coexistence: Cloudflare cannot hold two apex CNAMEs. Rename/soft-disable the AWS apex record **before** creating the new Azure apex record, or edit-in-place. | Technical review pass 1 |
| 2 | ACM validation CNAME (`_641b3d12a68e7041cb0cc89474dd7130`) stays **active** until AWS decommissioning starts — it preserves rollback integrity and doesn't conflict with Azure records. | Technical review pass 1 |
| 3 | Phase 4b wording: the `deploy-frontend` job already exists in `deploy-azure.yml`. The change is a single step within that job, not a new job. | Repo-state verification |
| 4 | Rollback wording: "restore renamed records" rather than "re-add deleted records"; Azure apex must be removed before restoring the AWS apex CNAME. | Technical review pass 1 |
| 5 | ACA cert bind sequencing: `az containerapp hostname add` runs in Phase 1b (to capture the `asuid` token), but `az containerapp hostname bind` is deferred to Phase 3b — it requires the CNAME + TXT records from Phase 2c to be publicly resolvable. | Technical review pass 2 |

---

## Context

Phase 4 deployed all services to Azure Container Apps + Static Web Apps (Central India), but `vibhanshu-ai-portfolio.dev` still resolves to AWS CloudFront. Two Cloudflare records are live today:

| Record | Type | Target |
|---|---|---|
| `vibhanshu-ai-portfolio.dev` | CNAME | `d1t9eh6t95r2m3.cloudfront.net` |
| `_641b3d12a68e7041cb0cc89474dd7130` | CNAME | `_89107a5015a36445a01353dd69831680.jkddzztszm.acm-validations.aws` |

Live Azure URLs (default hostnames):

| Service | URL |
|---|---|
| Frontend (SWA) | `https://salmon-sand-00357bb10.7.azurestaticapps.net` |
| API Gateway (ACA) | `https://api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` |

The audit flagged an architectural inconsistency: Terraform provisions an SWA (implies SWA hosts the frontend + proxies `/api/*` to ACA), but `ci-verification.yml` comments describe a direct ACA CNAME plan. This doc resolves that inconsistency.

---

## Recommendation — Split domains (Option A)

```
vibhanshu-ai-portfolio.dev       → Static Web App   (frontend)
www.vibhanshu-ai-portfolio.dev   → Static Web App   (frontend)
api.vibhanshu-ai-portfolio.dev   → ACA api-gateway  (backend)
```

### Rationale

The frontend already calls the gateway via absolute `NEXT_PUBLIC_API_BASE_URL` (see `frontend/src/lib/config/api.ts`), so there's no reverse-proxy dependency to preserve. Split domains also match the existing `frontend/tests/e2e/aws-synthetic/README.md` pattern (`api.vibhanshu-ai-portfolio.dev`) — so no e2e test changes are needed.

### Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| **A. Split domains (SWA + ACA subdomain)** | ✅ **Recommended** | Clean CORS, matches existing test patterns, managed TLS free on both, minimal code change |
| B. SWA-only with `/api/*` proxied to ACA (Linked Backends) | ❌ | Requires SWA **Standard tier (~$9/mo)** — breaks the Free-tier constraint in `.kiro/steering/tech.md` |
| C. Direct ACA CNAME, drop SWA | ❌ | Throws away the already-provisioned SWA; forces re-hosting `frontend/out/` on Blob `$web` or Cloudflare Pages. More work, no upside. |
| D. Keep AWS CloudFront, hybrid | ❌ | Contradicts the Phase 4 migration goal. AWS Lambdas are already failing per the audit. |

---

## Implementation plan

### Phase 1 — Provision custom domains on Azure (~20 min, reversible)

#### 1a. Add custom domain to SWA (apex + www)

SWA Free tier supports 2 custom domains, enough for apex + `www`.

```powershell
# Apex — register the hostname (--no-wait prevents blocking on validation)
az staticwebapp hostname set `
  --name wealth-prod-swa `
  --resource-group wealth-azure-prod-rg `
  --hostname vibhanshu-ai-portfolio.dev `
  --validation-method "dns-txt-token" `
  --no-wait

# Retrieve the TXT validation token
az staticwebapp hostname show `
  --name wealth-prod-swa `
  --resource-group wealth-azure-prod-rg `
  --hostname vibhanshu-ai-portfolio.dev `
  --query "validationToken" -o tsv

# www — same sequence
az staticwebapp hostname set `
  --name wealth-prod-swa `
  --resource-group wealth-azure-prod-rg `
  --hostname www.vibhanshu-ai-portfolio.dev `
  --validation-method "dns-txt-token" `
  --no-wait

az staticwebapp hostname show `
  --name wealth-prod-swa `
  --resource-group wealth-azure-prod-rg `
  --hostname www.vibhanshu-ai-portfolio.dev `
  --query "validationToken" -o tsv
```

Capture both TXT tokens for the Cloudflare step.

#### 1b. Add custom hostname to ACA api-gateway (bind deferred to Phase 3)

The ACA hostname binding splits into two commands. The first (`hostname add`) registers the intent and returns a validation token. The second (`hostname bind`) issues the managed certificate — and that step needs the `api` CNAME and `asuid.api` TXT records to be publicly resolvable, so it runs in Phase 3 after Cloudflare DNS is live.

```powershell
# Register the hostname and capture the asuid validation token.
# Run this now; DO NOT run `hostname bind` yet.
az containerapp hostname add `
  --hostname api.vibhanshu-ai-portfolio.dev `
  --name api-gateway `
  --resource-group wealth-azure-prod-rg
```

The command prints the `asuid` token (a GUID) needed for the Cloudflare `asuid.api` TXT record in Phase 2c. Capture it now.

The managed-cert bind command moves to **Phase 3** — see below.

### Phase 2 — Cloudflare DNS changes (~15 min)

**Ordering is critical.** Cloudflare does not allow two CNAME records with the same name, so the existing apex CNAME (pointing at CloudFront) must be removed or renamed *before* the new Azure apex CNAME is added.

#### 2a. Soft-disable the AWS apex CNAME (preserves rollback)

In the Cloudflare UI, rename the existing record:

| Before | After |
|---|---|
| Name: `vibhanshu-ai-portfolio.dev` → `d1t9eh6t95r2m3.cloudfront.net` | Name: `_disabled-apex` → `d1t9eh6t95r2m3.cloudfront.net` |

Cautions:
- In Cloudflare's DNS editor the `Name` field is a relative label — enter `_disabled-apex`, not `_disabled-apex.vibhanshu-ai-portfolio.dev`, or the zone will silently concatenate.
- After saving, confirm the FQDN shown is `_disabled-apex.vibhanshu-ai-portfolio.dev`.

**Alternative (simpler, destructive):** edit the existing apex record in place, changing the target from `d1t9eh6t95r2m3.cloudfront.net` to `salmon-sand-00357bb10.7.azurestaticapps.net`. This skips the rename dance but leaves no fast rollback — the old target must be re-entered from memory / this doc.

The soft-disable approach is preferred.

#### 2b. Leave the ACM validation CNAME active

Do **not** touch `_641b3d12a68e7041cb0cc89474dd7130 → _89107a5015a36445a01353dd69831680.jkddzztszm.acm-validations.aws`.

Reasons:
- It does not route user traffic and does not conflict with any Azure record.
- Disabling it breaks ACM renewal on the existing CloudFront certificate, which weakens longer-term rollback (if rollback happens after the ACM cert's next renewal window, the cert won't re-validate).
- There is no operational cost to leaving it in place.

Remove this record only after AWS decommissioning (CloudFront distribution + Lambda stack teardown) is committed.

#### 2c. Add the new Azure records

| Name | Type | Target | Proxy | Purpose |
|---|---|---|---|---|
| `vibhanshu-ai-portfolio.dev` | CNAME | `salmon-sand-00357bb10.7.azurestaticapps.net` | **DNS only (grey cloud)** | Apex → SWA (Cloudflare CNAME flattening) |
| `www` | CNAME | `salmon-sand-00357bb10.7.azurestaticapps.net` | DNS only | www → SWA |
| `api` | CNAME | `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` | DNS only | api → ACA |
| `_dnsauth` | TXT | `<SWA apex token from 1a>` | — | SWA apex validation |
| `_dnsauth.www` | TXT | `<SWA www token from 1a>` | — | SWA www validation |
| `asuid.api` | TXT | `<ACA token from 1b>` | — | ACA validation |

Set TTL to `300` (5 min) on the new records before cutover; raise to 3600 once stable.

#### 2d. What stays, what goes

| Record | Action | When |
|---|---|---|
| `vibhanshu-ai-portfolio.dev → d1t9eh6t95r2m3.cloudfront.net` | Rename to `_disabled-apex` | Phase 2a (before new apex CNAME) |
| `_641b3d12a68e7041cb0cc89474dd7130 → ...acm-validations.aws` | **Leave active** | Until AWS decommissioning |
| New Azure CNAMEs + TXTs | Add | Phase 2c |

### Phase 3 — DNS propagation + ACA cert bind (~5–15 min)

With DNS records live from Phase 2, Azure can now complete validation and issue managed certificates.

#### 3a. Wait for DNS to propagate (~1–3 min)

```powershell
# Confirm Cloudflare is serving the new records before kicking off cert issuance.
nslookup vibhanshu-ai-portfolio.dev
nslookup api.vibhanshu-ai-portfolio.dev
nslookup -type=TXT asuid.api.vibhanshu-ai-portfolio.dev
```

All three should return the expected Azure targets / tokens.

#### 3b. Bind the ACA managed certificate

This is the command deferred from Phase 1b. It depends on `api.vibhanshu-ai-portfolio.dev` (CNAME) and `asuid.api.vibhanshu-ai-portfolio.dev` (TXT) being publicly resolvable, which Phase 2c arranged.

```powershell
az containerapp hostname bind `
  --hostname api.vibhanshu-ai-portfolio.dev `
  --name api-gateway `
  --resource-group wealth-azure-prod-rg `
  --environment wealth-prod-aca-env `
  --validation-method CNAME
```

If this fails with "validation record not found", wait 60 s and retry — Cloudflare edge propagation can lag behind the zone edit.

#### 3c. Poll for cert-ready state (~5–15 min total)

```powershell
# SWA — repeat until state shows Ready
az staticwebapp hostname show `
  --name wealth-prod-swa `
  --resource-group wealth-azure-prod-rg `
  --hostname vibhanshu-ai-portfolio.dev

# ACA — repeat until certificateProvisioningState shows Succeeded
az containerapp hostname list `
  --name api-gateway `
  --resource-group wealth-azure-prod-rg `
  --output table
```

### Phase 4 — Update app configuration

#### 4a. CORS — tighten allowed origins via Terraform

Update `infrastructure/terraform/azure/variables.tf`:

```hcl
variable "cors_allowed_origin_patterns" {
  type    = string
  default = "https://vibhanshu-ai-portfolio.dev,https://www.vibhanshu-ai-portfolio.dev"
}
```

For the transition window, include the SWA default hostname so old links keep working:

```
https://vibhanshu-ai-portfolio.dev,https://www.vibhanshu-ai-portfolio.dev,https://salmon-sand-00357bb10.7.azurestaticapps.net
```

Narrow to just the custom domains after a few days.

#### 4b. Frontend build — point at custom API domain

The `deploy-frontend` job already exists in `.github/workflows/deploy-azure.yml` (lines 219–281). It currently resolves `NEXT_PUBLIC_API_BASE_URL` dynamically via `az containerapp show`:

```yaml
- name: Resolve API Gateway FQDN
  id: api_fqdn
  run: |
    FQDN=$(az containerapp show \
      --name api-gateway \
      --resource-group $AZURE_RG \
      --query properties.configuration.ingress.fqdn \
      --output tsv)
    echo "api_gateway_fqdn=https://${FQDN}" >> "$GITHUB_OUTPUT"
...
- name: Build Next.js static export
  env:
    NEXT_PUBLIC_API_BASE_URL: ${{ steps.api_fqdn.outputs.api_gateway_fqdn }}
```

Replace the resolver with the static custom domain:

```yaml
- name: Build Next.js static export
  working-directory: frontend
  env:
    NEXT_PUBLIC_API_BASE_URL: https://api.vibhanshu-ai-portfolio.dev
  run: npm run build
```

The `Resolve API Gateway FQDN` step can be deleted, or retained as a non-blocking sanity log (drop the `>> "$GITHUB_OUTPUT"` line).

`NEXT_PUBLIC_*` is baked into the JS bundle at build time, so a frontend redeploy is required. The `deploy-frontend` job rebuilds on every run, so pushing to `main` (or dispatching the workflow) is enough.

#### 4c. Re-run workflows

```powershell
gh workflow run terraform-azure.yml --field action=apply
gh workflow run deploy-azure.yml
```

### Phase 5 — Smoke test + cleanup

```powershell
# Origin smoke tests
curl -I https://vibhanshu-ai-portfolio.dev
curl -I https://www.vibhanshu-ai-portfolio.dev
curl -I https://api.vibhanshu-ai-portfolio.dev/actuator/health

# CORS preflight
curl -I -X OPTIONS `
  -H "Origin: https://vibhanshu-ai-portfolio.dev" `
  -H "Access-Control-Request-Method: GET" `
  https://api.vibhanshu-ai-portfolio.dev/api/portfolio/health
```

Browser smoke test: log in at `https://vibhanshu-ai-portfolio.dev`, verify Network tab shows `/api/*` requests hitting `api.vibhanshu-ai-portfolio.dev` with 200s and matching `Access-Control-Allow-Origin`.

**Cleanup — cutover complete, not yet decommissioning:**
- Leave `_disabled-apex` CNAME in place (rollback safety net).
- Leave the ACM validation CNAME in place (AWS cert renewal).
- Raise TTL on Azure records from `300` to `3600`.

**Cleanup — full AWS decommissioning (only after cutover has been stable for a few days):**
- Delete `_disabled-apex` CNAME.
- Delete `_641b3d12a68e7041cb0cc89474dd7130` CNAME.
- Tear down the CloudFront distribution, ACM certificate, and Lambda stack.

---

## Codify in Terraform (optional but recommended)

After the manual cutover works end-to-end, move custom domains into IaC so it's reproducible:

**`infrastructure/terraform/azure/main.tf`** additions:

```hcl
# SWA custom domains
resource "azurerm_static_web_app_custom_domain" "apex" {
  static_web_app_id = azurerm_static_web_app.frontend.id
  domain_name       = "vibhanshu-ai-portfolio.dev"
  validation_type   = "dns-txt-token"
}

resource "azurerm_static_web_app_custom_domain" "www" {
  static_web_app_id = azurerm_static_web_app.frontend.id
  domain_name       = "www.vibhanshu-ai-portfolio.dev"
  validation_type   = "dns-txt-token"
}

# ACA custom domain + managed cert
resource "azurerm_container_app_custom_domain" "api" {
  name             = "api.vibhanshu-ai-portfolio.dev"
  container_app_id = module.api_gateway.container_app_id
  # certificate_binding_type + container_app_environment_managed_certificate_id
  # set after first apply triggers managed cert issuance
}
```

(Managed-cert resources require a two-phase apply: first to validate the hostname, then to bind the cert.)

---

## Gotchas

1. **Cloudflare proxy = cert issuance fails.** Grey-cloud (DNS only) is mandatory during first provisioning for both SWA and ACA. After certs are `Ready`, proxy can be enabled on frontend records. Do not proxy the `api.` record — it complicates WebSockets and CORS preflight caching.

2. **Apex needs CNAME flattening.** Cloudflare handles this natively, which is one reason to keep Cloudflare as DNS rather than moving to Azure DNS.

3. **ACA managed cert propagation** takes ~5–10 min after DNS. First `hostname bind` often shows `Provisioning` — wait before retrying.

4. **Frontend env var is a build-time snapshot.** `NEXT_PUBLIC_*` is compiled into the bundle. Flipping the variable without redeploying does nothing.

5. **CORS wildcard → explicit narrowing.** Current default `https://*.azurestaticapps.net` allows any SWA. After switching to explicit origins, the old default SWA hostname will start failing CORS unless included in the list during the transition window.

6. **Cloudflare DNSSEC.** If enabled on the zone, sanity-check it doesn't interfere with Azure cert validation. Normally fine — `dig +dnssec vibhanshu-ai-portfolio.dev` should confirm.

7. **DNS TTL.** Use `300` during cutover for fast rollback; raise to 3600 once stable.

---

## Rollback plan

If anything fails in Phase 5 (or any earlier phase):

### Rollback sequence

| Step | Action | Notes |
|---|---|---|
| 1 | Set Azure apex/www/api records to DNS only | Skip if they were never proxied |
| 2 | Delete or rename the Azure apex CNAME `vibhanshu-ai-portfolio.dev → salmon-sand-00357bb10.7.azurestaticapps.net` | **Must happen before step 3** — two apex CNAMEs cannot coexist |
| 3 | Rename `_disabled-apex → d1t9eh6t95r2m3.cloudfront.net` back to `vibhanshu-ai-portfolio.dev` | Restores the AWS apex traffic record |
| 4 | Verify `dig vibhanshu-ai-portfolio.dev` resolves through Cloudflare flattening to a CloudFront IP | One TTL cycle (5 min if TTL=300 was set) |
| 5 | Smoke-test the AWS CloudFront frontend | Lambdas may still be 502-ing — same state as before cutover |
| 6 | Leave Azure TXT validation records in place | They don't route traffic; leaving them avoids reissuing tokens on a retry |

### What does NOT need to be restored

- The ACM validation CNAME (`_641b3d12...` → `...acm-validations.aws`) was never disabled per Phase 2b, so CloudFront certificate renewal continues uninterrupted.
- `www` and `api` Azure records can stay in place while disabled or simply be deleted — they don't conflict with anything AWS.

### Rollback window

Rollback integrity is strong as long as:
- The AWS apex record is only *renamed*, never deleted.
- The ACM validation CNAME stays active per Phase 2b.
- The CloudFront distribution and Lambda stack are not torn down.

Once AWS decommissioning starts (distribution deletion, Lambda removal, ACM cert deletion), rollback is no longer available.

---

## Follow-ups for after cutover

- Close out `docs/audit/azure_deployment.md` §4.5 with an ADR in `docs/adr/` documenting the split-domain choice.
- Append a cutover entry to `docs/changes/` with the final state.
- Add Azure synthetic monitoring tests (`frontend/tests/e2e/azure-synthetic/`) — audit §4.3.
- Update `.kiro/steering/tech.md` "Active deployment plan" line to reflect Azure as the active path.
- Narrow CORS to just the two custom domains after a few days of stability (remove `salmon-sand-*.azurestaticapps.net`).
- Raise DNS TTL on all 6 Azure records from 300 → 3600 in Cloudflare.

## Multi-cloud standby notes

AWS is **not being decommissioned** — it is soft-disabled as a standby cloud while Lambda throttling is resolved. The following records must remain in Cloudflare indefinitely:

| Record | Purpose |
|---|---|
| `_disabled-apex → d1t9eh6t95r2m3.cloudfront.net` | Fast re-enable path back to AWS CloudFront |
| `_641b3d12a68e7041cb0cc89474dd7130 → ...acm-validations.aws` | ACM certificate renewal for the CloudFront cert |

**To re-enable AWS (switch active cloud back):**
1. Lower the Azure apex CNAME TTL to `300` in Cloudflare and wait 5 min.
2. Delete (or rename) the Azure apex CNAME `vibhanshu-ai-portfolio.dev → salmon-sand-*.azurestaticapps.net`.
3. Rename `_disabled-apex` back to `vibhanshu-ai-portfolio.dev`.
4. Verify `nslookup vibhanshu-ai-portfolio.dev` resolves to a CloudFront IP.
5. Raise TTL back to `3600` once stable.

---

## Effort estimate

| Phase | Time |
|---|---|
| 1 — Provision custom domains | 20 min |
| 2 — Cloudflare DNS changes (rename + add) | 15 min |
| 3 — Cert issuance wait | 5–15 min (async) |
| 4 — Config updates + workflow runs | 30 min |
| 5 — Smoke test + decommission prep | 20 min |
| **Total active time** | **~90 min** |
| Codify in Terraform (optional) | +60 min |

Low-risk with rollback available until AWS decommissioning — Phase 5 explicitly defers destructive record deletion.
