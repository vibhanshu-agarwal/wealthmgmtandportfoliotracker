# API Gateway custom-domain recovery

**Status:** RESTORE EXECUTED — LIVE READ-BACK EVIDENCE REVIEWED AND MERGED (PR #194) / G5 BLOCKED
**Prepared:** 2026-08-31
**Hostname:** `api.vibhanshu-ai-portfolio.dev`
**Gateway:** `api-gateway` in `wealth-azure-prod-rg`
**Environment:** `wealth-prod-aca-env`

This runbook records the guarded hybrid recovery and its live read-back. It does **not** authorize
G5 dispatch, closing the backlog item, or checking B1 Task 5.7.

**2026-08-31 remote-plan attempt:** Run 33365567672 passed dispatch validation and
stopped during the read-only custom-domain preflight because the hosted Azure CLI requires
`az containerapp env certificate list --name`; the workflow supplied `--environment`.
Terraform produced no plan. No apply, bind, state/certificate/DNS mutation, or G5 action occurred.

**2026-08-31 remote-plan retry:** Run 33372272363 used the corrected certificate-list command,
passed the read-only custom-domain preflight, generated a Terraform plan, and passed the preceding
generic plan guards. It stopped at the final custom-domain exact-scope guard because Azure CLI and
AzureRM serialized the same gateway resource ID with different casing (`containerapps` versus
`containerApps`) and the source guard compared them case-sensitively. No plan was accepted for
review; no apply, bind, state/certificate/DNS mutation, or G5 action occurred.

**2026-08-31 accepted remote plan:** Run
[33379974571](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33379974571)
passed fresh restore preflight, Terraform plan generation, every mandatory generic guard, the
9.9–9.14 guards, and the API Gateway custom-domain exact-scope guard. Its apply job was skipped.

**2026-08-31 guarded apply and bind:** Run
[33380356530](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33380356530)
passed fresh restore preflight, apply-time plan assertions, Terraform apply, and the explicit bind
of `mc-wealth-prod-ac-api-vibhanshu-ai-5159`. The run is red only because its immediate
post-bind default-host health observation was not `200`; it did not retry, roll back, dispatch G5,
or change the backlog/B1 status.

**Independent live read-back:** At `2026-08-31T10:09:24.5519025Z`, both
`https://api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io/actuator/health` and
`https://api.vibhanshu-ai-portfolio.dev/actuator/health` returned HTTP `200`. Control-plane
read-back immediately after the failed workflow showed provisioning `Succeeded`, unchanged latest
and latest-ready revision `api-gateway--0000077`, external HTTPS-only ingress on port `8080` with
transport `Auto`, one exact custom hostname using `SniEnabled`, and the expected existing managed
certificate. The certificate inventory still returned the exact hostname in `Succeeded` state with
CNAME validation. The workflow reached the default-health assertion only after its TLS
subject/SAN/validity validator had completed; no TLS validation error was reported.

**Independent follow-up sampling:** A later first contact with the custom hostname timed out after
25 seconds, then three consecutive probes returned `503`. The next successful probe returned `200`
after 17 seconds, followed by 10 consecutive `200` responses. This is consistent with a
scale-from-zero cold start, not evidence that the restored binding is absent or invalid; steady-state
health and TLS verification were healthy.

**Future read-back rule:** Post-bind verification must not rely on a single health probe or on
incidental default-FQDN warming. The restore workflow performs, in order: (1) an informational
**custom-host warm-up** probe to `https://api.vibhanshu-ai-portfolio.dev/actuator/health` — timeout
or `503` during warm-up is logged but is not a binding failure; (2) a stability loop requiring
**three consecutive TLS-verified HTTP `200`** responses on that same custom hostname, with each
probe bounded by `curl --max-time 30` (no `-k`, `--insecure`, or `curl --retry`); non-`200`
results, including timeout (`000`), reset the consecutive count; (3) only after custom-host
stability, a bounded default ACA FQDN health probe. Each probe logs `http_status`, `curl_exit`, and
`duration_s`. Exhausting the retry budget without three consecutive custom-host `200`s fails the
step with an actionable error.

---

## Verified root cause (historical discovery state)

The table below records the state found before the guarded restore. Current live state is the
post-restore read-back recorded above.

| Fact | Value |
|---|---|
| Default ACA FQDN | `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` (healthy `200` at discovery) |
| Custom domain | CNAME correct; `customDomains: null` on the app at discovery |
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

All three null transitions share the single Terraform correlation from checkpoint 9.5's ingress-close
apply; Azure Container Apps reconciled the binding teardown in two phases (`certificateId` at
`2026-08-22T19:26:41.587Z`, then `bindingType` and `name` together at
`2026-08-22T19:37:00.225Z`), so the loss was not a single atomic property flip and must not be
read as separate operator actions.

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
5. Informational custom-host warm-up probe logged (timeout/`503`/`000` during warm-up alone is not a binding failure)
6. Three consecutive TLS-verified HTTP `200` responses on `https://api.vibhanshu-ai-portfolio.dev/actuator/health`, each probe bounded by `curl --max-time 30`; non-`200` resets the consecutive count; each attempt logs `http_status`, `curl_exit`, and `duration_s`
7. Default ACA `/actuator/health` probed only after custom-host warm-up/stability, also with `--max-time 30`
8. Live TLS proof via `openssl s_client` (no `-k` / `--insecure`): certificate subject matches the custom hostname, SAN covers the hostname, and `notBefore`/`notAfter` bracket the current time

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

- Does **not** claim the failed workflow is a fully green execution record; the immediate default-host
  health observation failed even though the subsequent independent read-back was healthy.
- Does **not** unblock G5, close the backlog item, or complete B1 Task 5.7.
- Does **not** authorize a retry, a remove operation, ingress closure, a state import, or any
  certificate mutation beyond the already recorded explicit bind.
- PR #194 independently reviewed and merged the live read-back evidence at `main@98371587`. That
  review does not unblock G5, close the backlog item, or complete B1 Task 5.7.

## Required future approvals

1. A separately authorized G5 resume decision; source merge, remote-plan success, Terraform apply,
   hostname bind, and the PR #194 evidence review do not themselves authorize it
