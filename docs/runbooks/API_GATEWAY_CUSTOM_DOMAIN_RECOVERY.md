# API Gateway custom-domain recovery

**Status:** NOT EXECUTED / SOURCE ONLY
**Prepared:** 2026-08-31
**Hostname:** `api.vibhanshu-ai-portfolio.dev`
**Gateway:** `api-gateway` in `wealth-azure-prod-rg`
**Environment:** `wealth-prod-aca-env`

This runbook documents the guarded hybrid recovery model prepared in source. It does **not**
authorize production plan, apply, bind, or G5 dispatch.

---

## Verified root cause

| Fact | Value |
|---|---|
| Default ACA FQDN | `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` (healthy `200`) |
| Custom domain | CNAME correct; `customDomains: null` on the app |
| Existing managed certificate | `mc-wealth-prod-ac-api-vibhanshu-ai-5159` |
| Certificate subject/state | `api.vibhanshu-ai-portfolio.dev` / `Succeeded` / CNAME validation |
| Loss event | Terraform correlation `cf0fc22a-595b-9bba-143a-6749888b1998` at checkpoint 9.5 cleared binding fields |

### Resource Graph evidence

This evidence was collected read-only on `2026-08-31T02:54:48Z` from subscription
`Azure subscription 1`, scoped to resource group `wealth-azure-prod-rg`. The subscription identifier
is intentionally represented as `$SUBSCRIPTION_ID` in the commands and `<subscription-id>` in the
sanitized output. These historical observations establish the loss event and the certificate's
survival at evidence-collection time; they do not replace the mandatory fresh preflight before a
future remote plan or apply.

The exact query below returns only the three custom-domain fields changed on `api-gateway` under the
Terraform correlation. One row per property avoids treating an absent property as a null transition.

```bash
SUBSCRIPTION_ID="$(az account show --query id -o tsv)"

az graph query \
  --subscriptions "$SUBSCRIPTION_ID" \
  --first 10 \
  -q "resourcechanges
| extend correlationId=tostring(properties.changeAttributes.correlationId),
         changeTime=todatetime(properties.changeAttributes.timestamp),
         targetResourceId=tostring(properties.targetResourceId),
         clientType=tostring(properties.changeAttributes.clientType),
         changes=todynamic(properties.changes)
| where correlationId =~ 'cf0fc22a-595b-9bba-143a-6749888b1998'
| where targetResourceId endswith '/resourceGroups/wealth-azure-prod-rg/providers/Microsoft.App/containerApps/api-gateway'
| mv-expand propertyName=bag_keys(changes)
| extend propertyName=tostring(propertyName)
| where propertyName in (
    'properties.configuration.ingress.customDomains[0].certificateId',
    'properties.configuration.ingress.customDomains[0].bindingType',
    'properties.configuration.ingress.customDomains[0].name')
| extend delta=changes[propertyName]
| project changeTime,
          targetResource='wealth-azure-prod-rg/Microsoft.App/containerApps/api-gateway',
          clientType,
          correlationId,
          propertyName,
          previousValue=delta.previousValue,
          newValue=delta.newValue
| order by changeTime asc, propertyName asc" \
  -o json
```

Sanitized result:

```json
{
  "count": 3,
  "data": [
    {
      "changeTime": "2026-08-22T19:26:41.587Z",
      "clientType": "Terraform",
      "correlationId": "cf0fc22a-595b-9bba-143a-6749888b1998",
      "newValue": null,
      "previousValue": "/subscriptions/<subscription-id>/resourceGroups/wealth-azure-prod-rg/providers/Microsoft.App/managedEnvironments/wealth-prod-aca-env/managedCertificates/mc-wealth-prod-ac-api-vibhanshu-ai-5159",
      "propertyName": "properties.configuration.ingress.customDomains[0].certificateId",
      "targetResource": "wealth-azure-prod-rg/Microsoft.App/containerApps/api-gateway"
    },
    {
      "changeTime": "2026-08-22T19:37:00.225Z",
      "clientType": "Terraform",
      "correlationId": "cf0fc22a-595b-9bba-143a-6749888b1998",
      "newValue": null,
      "previousValue": "SniEnabled",
      "propertyName": "properties.configuration.ingress.customDomains[0].bindingType",
      "targetResource": "wealth-azure-prod-rg/Microsoft.App/containerApps/api-gateway"
    },
    {
      "changeTime": "2026-08-22T19:37:00.225Z",
      "clientType": "Terraform",
      "correlationId": "cf0fc22a-595b-9bba-143a-6749888b1998",
      "newValue": null,
      "previousValue": "api.vibhanshu-ai-portfolio.dev",
      "propertyName": "properties.configuration.ingress.customDomains[0].name",
      "targetResource": "wealth-azure-prod-rg/Microsoft.App/containerApps/api-gateway"
    }
  ],
  "total_records": 3
}
```

The separate current-resource query below proves that exactly one matching Azure-managed certificate
remained present when the evidence was collected.

```bash
az graph query \
  --subscriptions "$SUBSCRIPTION_ID" \
  --first 10 \
  -q "resources
| where type =~ 'microsoft.app/managedenvironments/managedcertificates'
| where resourceGroup =~ 'wealth-azure-prod-rg'
| where name =~ 'mc-wealth-prod-ac-api-vibhanshu-ai-5159'
| project targetResource='wealth-azure-prod-rg/Microsoft.App/managedEnvironments/wealth-prod-aca-env/managedCertificates/mc-wealth-prod-ac-api-vibhanshu-ai-5159',
          location,
          name,
          subjectName=tostring(properties.subjectName),
          provisioningState=tostring(properties.provisioningState),
          validationMethod=tostring(coalesce(properties.domainControlValidation, properties.validationMethod))" \
  -o json
```

Sanitized result:

```json
{
  "count": 1,
  "data": [
    {
      "location": "centralindia",
      "name": "mc-wealth-prod-ac-api-vibhanshu-ai-5159",
      "provisioningState": "Succeeded",
      "subjectName": "api.vibhanshu-ai-portfolio.dev",
      "targetResource": "wealth-azure-prod-rg/Microsoft.App/managedEnvironments/wealth-prod-aca-env/managedCertificates/mc-wealth-prod-ac-api-vibhanshu-ai-5159",
      "validationMethod": "CNAME"
    }
  ],
  "total_records": 1
}
```

Do not create a replacement certificate. Reuse the existing managed certificate by explicit ID only
after the future workflow independently revalidates it.

## Hybrid ownership

| Surface | Owner |
|---|---|
| Hostname resource presence | Terraform (`azurerm_container_app_custom_domain.api_gateway`) |
| Managed certificate lifecycle | Azure / out-of-band; observed, not imported |
| Certificate bind to hostname | Workflow `az containerapp hostname bind --certificate <preflight id>` |
| Cloudflare CNAME / `asuid.api` TXT | Existing DNS owner; validated only |
| Production authorization | Repository owner + GitHub `production` Environment |

AzureRM 4.81 can declare the hostname without binding when certificate inputs are omitted and
ignored asynchronously. The bind is therefore an explicit post-apply workflow step, not a hidden
provisioner.

## Workflow profiles

| Profile | `TF_VAR_api_gateway_custom_domain_enabled` | Plan shape |
|---|---|---|
| `api-gateway-custom-domain-restore` | `true` | exactly one create on `azurerm_container_app_custom_domain.api_gateway[0]` |
| `api-gateway-custom-domain-remove` | `false` | exactly one delete on the same address |
| `standard` and other live profiles | `true` | must not touch the custom-domain resource |
| `spec-a-9.14-reopen-ingress` / `close-ingress` | `false` | ingress-only; domain remove/restore is separate |

## Plan acceptance contract

Restore create must include:

- hostname `api.vibhanshu-ai-portfolio.dev`
- `container_app_id` equal to preflight-captured gateway ID
- no `certificate_binding_type` or `container_app_environment_certificate_id` inputs

Remove delete must include the same hostname and gateway ID on the before side.

All profiles reject any create/update/delete/replace of managed or uploaded environment
certificate resources.

## Preflight boundaries

**Restore** (before remote-plan/apply):

1. External ingress contract: external `true`, `allowInsecure=false`, port `8080`, transport `Auto`,
   100% latest revision.
2. `customDomains` empty — if a correct binding already exists, stop for idempotency review.
3. Exactly one managed certificate `mc-wealth-prod-ac-api-vibhanshu-ai-5159` in `Succeeded` state.
4. Public CNAME resolves to the live ACA FQDN; `asuid.api` TXT matches live verification ID.
5. Capture certificate ID, gateway ID, revision, and default FQDN as job outputs.

**Remove** (before remote-plan/apply):

1. Exactly one bound hostname with `SniEnabled` and the expected certificate ID.

`spec-a-9.14-reopen-ingress` does **not** run custom-domain preflight or bind.

## Apply and bind boundaries

After a reviewed restore apply:

```bash
az containerapp hostname bind \
  --name api-gateway \
  --resource-group wealth-azure-prod-rg \
  --environment wealth-prod-aca-env \
  --hostname api.vibhanshu-ai-portfolio.dev \
  --certificate "$EXPECTED_CERTIFICATE_ID" \
  --validation-method CNAME
```

- `--certificate` is mandatory and must equal preflight output.
- Never bind without `--certificate`.
- Never create, upload, import, or renew a certificate in this task.
- If bind fails, stop and preserve evidence; do not retry with implicit certificate selection.

Apply authorization must name both Terraform apply **and** the CLI bind.

## Post-bind read-back

Require all of:

1. One `customDomains` entry with exact hostname and `SniEnabled`
2. Certificate ID equals preflight capture; certificate still `Succeeded`
3. Latest and latest-ready revision unchanged from pre-apply
4. Ingress contract unchanged
5. Default ACA `/actuator/health` returns `200`
6. `https://api.vibhanshu-ai-portfolio.dev/actuator/health` returns `200` with normal TLS verification

Remove profile verifies domain absence and surviving managed certificate. Remove does not dispatch G5.

## Bind-failure stop state

If Terraform created the hostname resource but bind failed:

- Do not delete/recreate the certificate.
- Do not dispatch G5.
- Do not change the frontend API URL to the default ACA hostname.
- Escalate for a separately reviewed recovery.

## Rollback

| Direction | Profile | Notes |
|---|---|---|
| Remove hostname from desired state | `api-gateway-custom-domain-remove` | Run before any future `spec-a-9.14-close-ingress` |
| Re-open ingress only | `spec-a-9.14-reopen-ingress` | Does not restore TLS on the custom host |
| Restore hostname + bind | `api-gateway-custom-domain-restore` | Separate review after ingress is open |

## Explicit non-claims

- Does **not** claim this runbook was executed.
- Does **not** claim TLS is restored, G5 is unblocked, or B1 Task 5.7 is complete.
- Does **not** authorize remote production plan, apply, bind, state import, or certificate mutation
  from source merge alone.
- Structural Terraform green proves syntax and graph shape only, not production delta.

## Required future approvals

1. Senior review of source PR
2. Authorized `api-gateway-custom-domain-restore` remote-plan on `main`
3. Authorized apply through `production` Environment (Terraform + bind)
4. Evidence PR with live read-back before closing the backlog item or resuming G5
