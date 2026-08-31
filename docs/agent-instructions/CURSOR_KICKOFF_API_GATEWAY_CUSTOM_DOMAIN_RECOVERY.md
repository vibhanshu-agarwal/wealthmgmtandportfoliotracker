# Cursor Kickoff — API Gateway custom-domain recovery

**Date:** 2026-08-31  
**Prepared for:** Cursor (implementation)  
**Authoritative baseline:** `main@b4b4bd8165940a4b6d371cd1127bfc087d86ee0c` (PR #190) or later  
**Suggested branch:** `feat/api-gateway-custom-domain-recovery` from current `main`  
**Current production authorization:** none — this is a source-only implementation assignment

---

## 0. Assignment and stop condition

Prepare a source-only PR that makes restoration of
`api.vibhanshu-ai-portfolio.dev` controlled, reproducible, and fail-closed. The PR must add the
Terraform ownership boundary, dedicated workflow profiles, preflight/read-back checks, exact-scope
plan assertions, adversarial unit tests, and an unexecuted runbook.

Stop with the PR open for senior review. This kickoff does **not** authorize:

- a remote production plan;
- a Terraform state import or any other backend-state mutation;
- a Terraform apply;
- `az containerapp hostname add`, `bind`, or `delete`;
- creating, replacing, importing, deleting, or renewing a certificate;
- an application/image rollout or new Container App revision;
- a G5 synthetic dispatch; or
- checking B1 Task 5.7.

Do not merge on your own authority. A green structural plan is not production-plan evidence.

## 1. Read these files before editing

1. [`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md),
   especially “Next meaningful work and authorization boundary.”
2. [`docs/runbooks/SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md).
3. [`docs/runbooks/B1_G5_INGRESS_BLOCKER.md`](../runbooks/B1_G5_INGRESS_BLOCKER.md).
4. [`docs/todos/backlog/api-gateway-custom-domain-binding/README.md`](../todos/backlog/api-gateway-custom-domain-binding/README.md).
5. [`infrastructure/terraform/azure/modules/container-app/main.tf`](../../infrastructure/terraform/azure/modules/container-app/main.tf)
   and its [`outputs.tf`](../../infrastructure/terraform/azure/modules/container-app/outputs.tf).
6. [`infrastructure/terraform/azure/main.tf`](../../infrastructure/terraform/azure/main.tf),
   [`variables.tf`](../../infrastructure/terraform/azure/variables.tf), and
   [`versions.tf`](../../infrastructure/terraform/azure/versions.tf).
7. [`.github/workflows/terraform-azure.yml`](../../.github/workflows/terraform-azure.yml).
8. The existing 9.14 guard and tests:
   [`assert_spec_a_9_14_plan.py`](../../infrastructure/terraform/azure/scripts/assert_spec_a_9_14_plan.py)
   and
   [`test_assert_spec_a_9_14_plan.py`](../../infrastructure/terraform/azure/scripts/test_assert_spec_a_9_14_plan.py).
9. The dispatch validator and tests:
   [`validate_dispatch.py`](../../infrastructure/terraform/azure/scripts/validate_dispatch.py)
   and
   [`test_validate_dispatch.py`](../../infrastructure/terraform/azure/scripts/test_validate_dispatch.py).

Re-read current `main` if it has advanced. Do not copy line numbers from this note without checking
the current files.

## 2. Verified production evidence — do not re-run without authorization

The following facts were read-only verified on 2026-08-31. They are inputs to this task, not an
invitation for Cursor to operate production.

| Fact | Verified value |
|---|---|
| Resource group | `wealth-azure-prod-rg` |
| Container Apps environment | `wealth-prod-aca-env` |
| Gateway | `api-gateway` |
| Gateway state | `Running`, provisioning `Succeeded` |
| Gateway revision | `api-gateway--0000077` |
| Default ACA FQDN | `api-gateway.lemonmoss-ecef29d7.centralindia.azurecontainerapps.io` |
| Ingress | external `true`, `allowInsecure=false`, target port `8080`, transport `Auto`, 100% latest revision |
| Custom domains | `null` |
| Required hostname | `api.vibhanshu-ai-portfolio.dev` |
| Existing managed certificate | `mc-wealth-prod-ac-api-vibhanshu-ai-5159` |
| Certificate subject/state | exact hostname / `Succeeded`, CNAME validation |
| DNS CNAME | exact default ACA FQDN |
| DNS TXT | `asuid.api` matches the gateway’s live verification ID |
| Default endpoint | HTTPS health returns `200` |
| Custom endpoint | TLS connection reset / HTTP `000` |

Azure Resource Graph change history proves the root cause. Terraform correlation
`cf0fc22a-595b-9bba-143a-6749888b1998` at checkpoint 9.5 changed all of these from their prior
values to null:

- certificate ID for the managed certificate;
- binding type `SniEnabled`; and
- hostname `api.vibhanshu-ai-portfolio.dev`.

The certificate resource survived. Do not create a replacement certificate to treat a binding
loss.

## 3. Architecture decision — guarded hybrid ownership

Use a deliberate hybrid model:

| Surface | Owner after this change |
|---|---|
| Gateway ingress and hostname-resource presence | Terraform |
| Existing Azure managed certificate lifecycle | Azure/out-of-band; observed and protected, not imported by this task |
| Binding the existing managed certificate to the hostname | The guarded apply workflow, using an explicit certificate ID |
| Cloudflare CNAME and `asuid.api` TXT | Existing DNS owner; validate only |
| Production authorization | Repository owner + GitHub `production` Environment gate |

### Why this is not Terraform-only

The repository used AzureRM 4.81 for checkpoint 9.14. In that provider:

- `azurerm_container_app_custom_domain` can create and track the hostname resource;
- its managed-certificate mode requires omitting
  `container_app_environment_certificate_id` and `certificate_binding_type`, then ignoring those
  two asynchronously populated fields; but
- the input validator for `container_app_environment_certificate_id` accepts uploaded environment
  certificates, not an existing managed-certificate resource ID.

The provider’s create path therefore adds an unbound hostname with binding type `Disabled` when no
uploaded certificate is supplied. The official CLI can then bind a specifically named existing
managed certificate. This assignment must expose that second write honestly in the workflow; do
not hide it in a Terraform provisioner.

Official AzureRM 4.81 references:

- <https://github.com/hashicorp/terraform-provider-azurerm/blob/v4.81.0/website/docs/r/container_app_custom_domain.html.markdown>
- <https://github.com/hashicorp/terraform-provider-azurerm/blob/v4.81.0/internal/services/containerapps/container_app_custom_domain_resource.go>

### Explicitly rejected approaches

1. **Do not declare `azurerm_container_app_environment_managed_certificate` in this task.** The
   matching certificate already exists. Declaring it without a separately authorized import would
   produce `ResourceRequiresImport`; auto-importing would mutate backend state before the reviewed
   plan; attempting creation risks a duplicate; later removal from configuration risks deletion.
2. **Do not call `az containerapp hostname bind` without `--certificate`.** Without an explicit
   certificate, the CLI may look for or create a managed certificate. This task must reuse the
   exact existing certificate.
3. **Do not put a `custom_domain` block inside `azurerm_container_app.ingress`.** In AzureRM v4 it
   is computed state; custom domains are managed by the dedicated resource.
4. **Do not use `local-exec`, `terraform_data`, `null_resource`, or AzAPI to conceal the bind.** The
   post-apply operation needs its own visible preconditions, command, failure, and read-back.
5. **Do not “fix” the TLS symptom by changing the frontend API URL to the default ACA hostname.**
   G5 is explicitly defined against the configured public host and all three real callers.

## 4. Required implementation

### Task 1 — expose the child Container App ID

**Files:**

- Modify `infrastructure/terraform/azure/modules/container-app/outputs.tf`.

Add a non-sensitive `app_id` output whose value is `azurerm_container_app.this.id`. The root module
must consume this output; do not reconstruct the resource ID from subscription/resource-group/name
strings.

Required output contract:

```hcl
output "app_id" {
  value       = azurerm_container_app.this.id
  description = "Resource ID of the Container App."
}
```

### Task 2 — declare hostname presence without declaring the existing certificate

**Files:**

- Modify `infrastructure/terraform/azure/variables.tf`.
- Modify `infrastructure/terraform/azure/main.tf`.

Add `api_gateway_custom_domain_enabled`, type `bool`, default `true`. Its description must state:

- `true` is production steady state while gateway ingress is open;
- `false` is allowed only for the dedicated remove profile or while ingress is deliberately closed;
- changing it does not authorize an apply.

Keep the hostname itself as a repository constant, not an operator-supplied dispatch input:

```hcl
locals {
  api_gateway_custom_domain_name = "api.vibhanshu-ai-portfolio.dev"
}
```

Declare one dedicated resource with this shape:

```hcl
resource "azurerm_container_app_custom_domain" "api_gateway" {
  count = var.api_gateway_ingress_enabled && var.api_gateway_custom_domain_enabled ? 1 : 0

  name             = local.api_gateway_custom_domain_name
  container_app_id = module.api_gateway.app_id

  lifecycle {
    ignore_changes = [
      certificate_binding_type,
      container_app_environment_certificate_id,
    ]
  }
}
```

Load-bearing constraints:

- Do not set either certificate input.
- Do not add a managed-certificate Terraform resource or import block.
- Tie resource presence to ingress presence. Closing ingress must first remove the hostname resource
  from desired state; otherwise the provider can leave stale Terraform state after ACA removes the
  domain with the ingress block.
- The existing certificate must remain outside Terraform’s destroy graph.

### Task 3 — add dedicated workflow profiles and fail-closed dispatch validation

**Files:**

- Modify `.github/workflows/terraform-azure.yml`.
- Modify `infrastructure/terraform/azure/scripts/validate_dispatch.py`.
- Modify `infrastructure/terraform/azure/scripts/test_validate_dispatch.py`.

Add exactly two profiles:

- `api-gateway-custom-domain-restore`
- `api-gateway-custom-domain-remove`

Both profiles are scoped production profiles. They must require:

- dispatch against `refs/heads/main`;
- exact `expected_main_sha` match;
- `use_seed_image=false`;
- `recreate_market_data_job=false`; and
- the existing image-identity inputs required by all live-state operations.

Set `TF_VAR_api_gateway_custom_domain_enabled` as follows:

- `false` for `api-gateway-custom-domain-remove`;
- `false` for `spec-a-9.14-close-ingress`;
- `false` for `spec-a-9.14-reopen-ingress`; and
- `true` for every other profile, including `standard`,
  and `api-gateway-custom-domain-restore`.

This intentionally makes the restored domain part of ordinary desired state. Before the first
restore, an unrelated live `standard` apply must not silently create it: Task 5’s universal guard
must reject a domain change unless the restore/remove/9.14 profile owns it.

Update every existing guard’s known-profile allow-list so the two new names do not fail as unknown:

- `assert_spec_a_9_9_plan.py`
- `assert_spec_a_9_11_plan.py`
- `assert_spec_a_9_12_plan.py`
- `assert_spec_a_9_13_plan.py`
- `assert_spec_a_9_14_plan.py`

Add corresponding known-profile tests. Do not weaken any existing scoped behavior.

### Task 4 — add read-only certificate/DNS preflight

**Files:**

- Modify `.github/workflows/terraform-azure.yml`.
- Create `infrastructure/terraform/azure/scripts/validate_api_gateway_custom_domain.py`.
- Create `infrastructure/terraform/azure/scripts/test_validate_api_gateway_custom_domain.py`.

The helper must expose pure functions that unit tests can call without Azure:

```python
validate_restore_preflight(app: dict, certificates: list[dict], cname_target: str,
                           txt_values: list[str]) -> dict[str, str]
validate_remove_preflight(app: dict, certificates: list[dict]) -> dict[str, str]
validate_post_bind(app: dict, certificates: list[dict], expected: dict[str, str]) -> None
```

Its CLI must accept sanitized JSON projections produced by the workflow, not raw Container App
objects. Use explicit modes `restore-preflight`, `remove-preflight`, and `post-bind`; return nonzero
on every contract violation and emit only the small output fields needed by later steps.

For `api-gateway-custom-domain-restore`, before the production plan:

1. Read `api-gateway`; require external ingress, `allowInsecure=false`, target port 8080, transport
   Auto, and a single 100% latest-revision traffic weight.
2. Require `customDomains` to be null/empty for the initial restore. If a correct binding already
   exists, stop and request a new idempotency/recovery review rather than pretending the expected
   plan is still a create.
3. Resolve certificate name `mc-wealth-prod-ac-api-vibhanshu-ai-5159` in
   `wealth-prod-aca-env`; require exactly one result, subject
   `api.vibhanshu-ai-portfolio.dev`, provisioning state `Succeeded`, and CNAME validation.
4. Capture its full resource ID as a job output for the apply-only bind step. Never accept a
   caller-supplied certificate ID.
5. Read the gateway’s live `customDomainVerificationId`.
6. Require public DNS CNAME to resolve directly to the gateway’s live ACA FQDN.
7. Require `asuid.api.vibhanshu-ai-portfolio.dev` TXT to contain the exact live verification ID.
8. Capture the pre-apply latest revision name and default ACA FQDN as job outputs.

The preflight must print only sanitized facts. Do not print tokens, raw Container App objects,
secrets, raw Terraform plans, or credentials. The domain verification ID is already public in DNS,
but still avoid echoing unnecessary raw payloads.

For the remove profile, require the current binding to exist and match exactly one hostname, the
expected certificate ID, and `SniEnabled`. A removal plan against an absent or different binding is
not the reviewed operation.

`spec-a-9.14-reopen-ingress` remains an ingress-only operation. Do not run custom-domain restore
preflight or binding under that profile; complete the ingress reopen first, then use the separately
reviewed custom-domain restore profile.

### Task 5 — implement a universal exact-scope custom-domain plan guard

**Files:**

- Create `infrastructure/terraform/azure/scripts/assert_api_gateway_custom_domain_plan.py`.
- Create `infrastructure/terraform/azure/scripts/test_assert_api_gateway_custom_domain_plan.py`.
- Modify `.github/workflows/terraform-azure.yml` in both remote-plan and apply assertion sequences.

The guard must run for **every** live-state profile.

Use these resource addresses:

```text
azurerm_container_app_custom_domain.api_gateway[0]
module.api_gateway.azurerm_container_app.this
```

Required behavior:

#### Restore profile

- Require exactly one changed resource: create the custom-domain resource.
- Require exact hostname and the preflight-captured gateway resource ID.
- Require both certificate input fields absent/null.
- Permit only the provider-computed managed-certificate ID to be unknown after creation.
- Reject a gateway change, certificate resource change, replacement, update, delete, extra domain,
  or any unrelated resource change.

#### Remove profile

- Require exactly one changed resource: delete the exact custom-domain resource.
- Validate the before-side hostname and gateway ID.
- Reject any gateway, certificate, or unrelated resource change.

#### Other profiles

- Reject any create/update/delete/replace of the custom-domain resource. In particular, the 9.14
  ingress profiles must remain ingress-only. A future close must run the dedicated domain-remove
  operation first; a future reopen must finish before a separate domain-restore operation begins.
- Reject every create/update/delete/replace of any
  `azurerm_container_app_environment_managed_certificate` or
  `azurerm_container_app_environment_certificate` under all profiles in this task.

The guard must not credit before/after equality rendered non-falsifiable by ignored fields. It is a
resource-action and exact-field guard, not an assertion that Terraform has already bound TLS.

Minimum adversarial unit cases:

1. exact restore create passes;
2. exact remove delete passes;
3. wrong hostname fails;
4. wrong app ID fails;
5. explicit uploaded-certificate ID fails;
6. explicit binding type fails;
7. managed-certificate create/delete/update fails;
8. gateway change during restore/remove fails;
9. extra changed resource fails;
10. replacement action fails;
11. restore under `standard` fails;
12. remove under `standard` fails;
13. profile typo fails closed;
14. malformed/missing `resource_changes` fails closed;
15. secret-like fixture values never appear in error text.

### Task 6 — preserve the 9.14 one-resource reversal path

**Files:**

- Modify `infrastructure/terraform/azure/scripts/assert_spec_a_9_14_plan.py`.
- Modify `infrastructure/terraform/azure/scripts/test_assert_spec_a_9_14_plan.py`.

The completed 9.14 evidence remains historical and valid. Keep its reusable Terraform plan shape
to exactly one gateway ingress update:

- Before a future close, the operator must run and verify `api-gateway-custom-domain-remove`; a
  close plan that also contains a domain delete must fail the universal guard.
- A future reopen restores only ACA ingress. After it is verified, the operator must obtain a
  separate plan/review/apply for `api-gateway-custom-domain-restore`.
- Neither 9.14 profile may create, delete, update, bind, or import a certificate/domain resource.
- No image, environment, scaling, port, transport, traffic, or unrelated drift is allowed beyond
  the already specified 9.14 ingress transition.

Keep all currently falsifiable 9.14 assertions. Do not strengthen or repeat the known
non-falsifiable B5 image-equality claim; that separate backlog item remains out of scope.

### Task 7 — bind the exact existing certificate after Terraform apply

**Files:**

- Modify `.github/workflows/terraform-azure.yml` after `Terraform Apply`.

Run the bind step only for:

- `api-gateway-custom-domain-restore`.

Use the certificate ID produced by Task 4. The command contract is:

```bash
az containerapp hostname bind \
  --name api-gateway \
  --resource-group wealth-azure-prod-rg \
  --environment wealth-prod-aca-env \
  --hostname api.vibhanshu-ai-portfolio.dev \
  --certificate "$EXPECTED_CERTIFICATE_ID" \
  --validation-method CNAME
```

Required safety rules:

- The certificate argument is mandatory and must equal the preflight result.
- Never fall back to a command without `--certificate`.
- Never create or upload a certificate.
- Never auto-import Terraform state.
- If Terraform did not create the exact hostname resource, do not bind.
- If the bind fails, fail the job and stop. Do not delete/recreate the certificate, retry with an
  implicit certificate, dispatch G5, or invent cleanup. The default ACA endpoint was healthy before
  this change; preserve evidence and ask for a separately reviewed recovery.

This is an acknowledged post-plan production write. The eventual apply authorization must name both
the Terraform apply and this explicit CLI bind; approval of one must not be interpreted as approval
of the other.

### Task 8 — add post-apply read-back and TLS proof

**Files:**

- Modify `.github/workflows/terraform-azure.yml`.
- Extend `validate_api_gateway_custom_domain.py` and its tests from Task 4; do not add a second
  parser or embed duplicate JMESPath/string assumptions throughout YAML.

After a successful restore bind, require all of the following:

1. `customDomains` is a one-element collection.
2. Name is exactly `api.vibhanshu-ai-portfolio.dev`.
3. Binding type is `SniEnabled`.
4. Certificate ID equals the preflight-captured existing certificate ID.
5. Certificate resource still reports `Succeeded` and the exact subject.
6. Latest and latest-ready revision names equal the pre-apply revision; no revision was cut.
7. External ingress remains true, `allowInsecure=false`, target port 8080, transport Auto, and
   100% latest-revision traffic.
8. The default ACA `/actuator/health` endpoint returns `200`.
9. `https://api.vibhanshu-ai-portfolio.dev/actuator/health` returns `200` with normal certificate
   verification enabled—no `-k`, `--insecure`, or disabled hostname checking.
10. TLS certificate subject/SAN covers the custom hostname and the certificate is currently valid.

Sanitize the published summary. Record resource names, revision, hostname, status, run URL, and
commit SHA; do not upload raw plan JSON or secret-bearing app configuration.

For remove, verify the domain is absent and the managed certificate still exists. The remove
profile must not dispatch G5. Existing 9.14 ingress read-back remains responsible for close/reopen.

### Task 9 — documentation and status integrity

**Files:**

- Create `docs/runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`.
- Modify `docs/todos/backlog/api-gateway-custom-domain-binding/README.md`.
- Modify `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`.
- Modify `docs/runbooks/SPEC_A_9_14_REOPEN_INGRESS.md` only to describe the updated future reversal
  mechanics; do not rewrite its historical outcome.
- Modify `docs/runbooks/B1_G5_INGRESS_BLOCKER.md` to link the recovery runbook and reiterate that
  source preparation does not satisfy either G5 resume condition.

The new runbook must be marked **NOT EXECUTED / SOURCE ONLY** in this PR and contain:

- verified root cause and existing certificate identity;
- hybrid ownership decision and provider limitation;
- exact restore/remove profiles;
- plan acceptance contract;
- preflight, apply, bind, and read-back boundaries;
- bind-failure stop state;
- rollback behavior;
- explicit non-claims and required future approvals.

Status rules:

- Leave the backlog item open.
- Leave B1 Task 5.7 unchecked.
- Do not say TLS is restored, G5 is unblocked, or Writer_Convergence is achieved.
- While the PR is open, describe the work as source-only and unmerged.
- After a future source merge but before an authorized apply, describe it as merged but unapplied.
- Only a separate evidence PR after live read-back may close the backlog item or change the G5
  resume condition.

The PR body must satisfy the repository’s master-plan status-propagation contract; do not use
`Master-plan impact: none` because this PR changes an explicitly tracked operational blocker.

## 5. Test sequence

Use TDD for each guard/parser change: add the failing fixture first, run it, implement the minimum
behavior, then rerun.

Required local verification before opening the PR:

```powershell
python infrastructure/terraform/azure/scripts/test_validate_dispatch.py -v
python infrastructure/terraform/azure/scripts/test_validate_api_gateway_custom_domain.py -v
python infrastructure/terraform/azure/scripts/test_assert_api_gateway_custom_domain_plan.py -v
python infrastructure/terraform/azure/scripts/test_assert_spec_a_9_14_plan.py -v
python -m unittest discover -s infrastructure/terraform/azure/scripts -p "test_*.py" -v
terraform -chdir=infrastructure/terraform/azure fmt -check -recursive
terraform -chdir=infrastructure/terraform/azure init -backend=false
terraform -chdir=infrastructure/terraform/azure validate
python scripts/tests/test_master_plan_status_propagation.py -v
git diff --check
```

The `-backend=false` initialization may download providers but must not connect to the production
backend. Inspect the PR job’s structural-plan result to confirm the resource graph is valid. Do not
run a remote plan. Structural green proves syntax and graph shape only; say exactly that in the PR.

## 6. Definition of done for Cursor

- Source-only PR opened from current `main`.
- Dedicated custom-domain resource owns hostname presence and is conditional on ingress.
- Existing managed certificate is not declared, imported, created, updated, or destroyed.
- Restore/remove profiles are validated and mutually scoped.
- Universal exact-scope guard is wired into remote-plan and apply.
- 9.14 close/reopen remain one-resource ingress operations; the note and guards enforce separate
  domain remove/restore sequencing.
- Apply workflow can bind only the exact preflight-resolved existing certificate.
- Post-apply proof checks binding, TLS, both endpoints, and unchanged revision.
- Adversarial tests and full Terraform-script unit suite pass.
- Runbook is explicitly unexecuted.
- Master plan and backlog wording distinguish source, merge, apply, and G5 states.
- PR remains open for senior review.

## 7. Escalate instead of deciding

Stop and ask the architect/owner if any of these occurs:

- AzureRM resolves to a version other than the reviewed 4.81 behavior and changes the resource
  schema or create/read semantics.
- The structural plan wants to create or manage a certificate.
- The domain already exists when the future restore preflight runs.
- The existing certificate is missing, duplicated, not `Succeeded`, or has a different subject.
- The DNS CNAME/TXT proof no longer matches the gateway.
- The remote plan includes any Container App, image, revision, env, scaling, traffic, port, DNS,
  certificate, or unrelated resource action outside the accepted profile shape.
- The provider cannot order domain deletion before ingress close without stale state.
- A bind failure leaves the hostname present but unsecured.
- Any proposal needs a state import, AzAPI, provisioner, implicit managed-certificate creation, or
  frontend endpoint change.
- Anyone asks to merge, remote-plan, apply, bind, close the backlog item, or dispatch G5 based only
  on this source PR.

The correct response is a documented blocker and a fresh review, not a broader workaround.
