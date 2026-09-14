# B2 Task 8.9 — Azure Production timeout ratification (addendum)

**Decision date:** 2026-09-14
**Status:** ADDENDUM — does not supersede or rewrite the 2026-09-09 decision
**Applies to:** the attested Azure Production deployment only
(`infrastructure/terraform/azure/main.tf`, `module "api_gateway"`). The application-wide generic
defaults are unaffected.

> **This is an addendum, not a rewrite.** The 2026-09-09 owner decision
> (`docs/superpowers/plans/2026-09-06-b2-wave8-decision-record.md`, annotated 2026-09-09 and
> recorded in `docs/evidence/b2-task-8-2/owner-decision-20260909.json`) approved the generic values
> **eligibility 45s / reset 10s / overall 60s** as the fallback every non-Azure deployment inherits
> (`api-gateway/src/main/resources/application.yml` lines 136-138). That decision is preserved
> unchanged: this document does not alter it, its file, or its evidence record. It ratifies a
> **separate, Azure-specific set of deployment overrides**, already live via PR #251 (2026-09-10,
> "extend gateway cold-start timeouts"), that apply only to the attested Azure Production Container
> App deployment, and records why they are internally coherent against the Azure-specific 150s
> route ceiling.

## Why this addendum exists

Task 8.9's live Production preflight ran on 2026-09-14 and reached NON-GO on its last check. All 14
read-only Azure operations succeeded (replica list, containerapp exec, ACR manifests, acr login,
docker pulls, Log Analytics KQL). The only failure:

```
serving APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT does not equal the approved value
```

The root cause was drift between two sources of truth, not a deployment problem:

- `infrastructure/terraform/azure/main.tf` (PR #251, commit `83c8802`, 2026-09-10) sets the
  Azure-specific overrides ratified below, and has done so since that merge.
- `scripts/verify_demo_reset_azure.py` and the `scripts/run_task_8_9_preflight.ps1` wrapper were
  still asserting the older generic `45s/10s/60s` values from the 2026-09-09 decision.

The deployment was current; the proof's expectation was stale. This document ratifies the
Azure-specific values the deployment already carries, so the wrapper and verifier can be brought
into agreement with it (see the companion changes to
`scripts/run_task_8_9_preflight.ps1` and `scripts/verify_demo_reset_azure.py` landed alongside this
addendum).

## What this ratifies

The attested Azure Production `api-gateway` Container App is deployed with these environment
variable overrides, set in `infrastructure/terraform/azure/main.tf` inside `module "api_gateway"`'s
`env_vars` block:

| Environment variable | Azure Production value | `main.tf` line |
|---|---|---|
| `APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT` | `120s` | 252 |
| `APP_DEMO_LOGIN_RESET_RESET_TIMEOUT` | `30s` | 253 |
| `APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT` | `165s` | 254 |
| `SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT` | `150s` | 255 |

These are **Azure deployment overrides, not application-wide defaults**:

- They exist only as `env_vars` on the Terraform-managed `api-gateway` Container App module
  instance in `infrastructure/terraform/azure/main.tf` — nowhere else.
- `api-gateway/src/main/resources/application.yml` line 136
  (`eligibility-timeout: ${APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT:45s}`, and the sibling
  `reset-timeout`/`overall-timeout` bindings on lines 137-138) is unchanged by this addendum and
  remains the fallback every non-Azure deployment (local, CI, any other environment) inherits.
- `AzureGatewayTimeoutTerraformBindingTest`
  (`api-gateway/src/test/java/com/wealth/gateway/AzureGatewayTimeoutTerraformBindingTest.java`)
  already pins these override values on the Terraform side; this document is the operational
  rationale for them, not a second copy of that assertion.

## Why they are internally coherent

- **120s eligibility < 150s route ceiling.** The Azure-specific
  `SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT=150s` (`main.tf` line 255) is the
  downstream route ceiling for the Azure profile — the same structural role the 2026-09-09 decision
  checked the generic 45s eligibility timeout against the 55s `response-timeout` in
  `api-gateway/src/main/resources/application-prod.yml`. Here, 120s eligibility fires strictly
  before the 150s ceiling (120 < 150, 30 seconds of margin), so a Wave 8 eligibility timeout on
  Azure stays attributable to Wave 8 rather than to the route.
- **165s overall > 120s + 30s = 150s nominal leg sum.** The overall backstop (`main.tf` line 254)
  exceeds the nominal eligibility-plus-reset leg sum by 15 seconds (165 - 150 = 15), preserved as
  orchestration-overhead margin — the same `overall > eligibility + reset` relationship the
  2026-09-09 decision established for the generic values (60s > 45s + 10s = 55s, a 5-second margin),
  here with a wider 15-second margin.
- **Why the Azure margins are wider than the generic decision's.** PR #251 widened all three values
  for the Azure Production deployment specifically, where the recorded cold-start behavior against
  Azure Container Apps at `min_replicas = 0` warranted more headroom than the generic 45s/10s/60s
  budget assumed. The generic values remain correct as the fallback for every deployment that is
  not this attested Azure Production target.

## Scope boundary

This ratification covers the Azure Production deployment overrides only. It authorizes no
Production action by itself — Production wake, credential access, and execute-mode preflight each
remain separately owner-gated per `AGENTS.md`'s Owner Approval Callouts.
