# Spec A Checkpoint 9.9 Execution Plan

> For agentic workers: execute this plan one task at a time, preserving every stop/go gate. Checkpoint 9.9 is not authorized by this document; the apply requires a separate explicit approval after the remote plan is reviewed.

**Goal:** Enable supported-asset enforcement on the already-deployed R4 artifact by removing the two temporary false overrides and setting `min_replicas = 1` on the three catalog-loading services, while ingress and the refresh producer remain closed.

**Architecture:** Treat 9.9 as an exact-scope Terraform cutover. First harden the Terraform apply path so it is main-only, commit-bound, approval-gated, remote-state-aware, and able to preserve the R4 image/version identity. Then merge the six environment-variable removals and three scale changes. Generate and review a live-state plan, apply that exact saved plan, and prove the three new active revisions have one catalog tuple: version `a00b32ac0267e1a9`, `rejectUnsupportedEvents=true`, and `enforceHoldingInvariant=true`. Do not open ingress or run the refresh Job.

**Tech Stack:** GitHub Actions, Terraform/AzureRM, Azure Container Apps, Azure Container Registry, Azure CLI, Log Analytics/KQL, PowerShell, Gradle/JUnit, Python contract tests.

**Primary specification:** `.kiro/specs/supported-asset-integrity/tasks.md` Task 9 and `.kiro/specs/supported-asset-integrity/design.md` checkpoint 9.9.

---

## Non-negotiable gates

This plan is deliberately not directly executable yet. The current Terraform workflow has three safety gaps that must be closed first:

1. A manual apply can be dispatched from a non-`main` ref and has no expected-SHA guard or `production` Environment approval.
2. Every run sets `TF_VAR_image_tag` to the workflow SHA. A Terraform-only 9.9 commit would therefore leave the ignored container images on R4 while changing `SERVICE_VERSION` on all applications and Jobs to the IaC commit. That creates unrelated configuration revisions and false telemetry.
3. The existing `action=plan` path uses a local backend. It validates structure but does not preview the production-state delta.

There is also known unrelated plan noise from `azurerm_static_web_app.frontend.repository_url` changing from Azure's computed repository URL to `null`. Close that drift before requiring a three-resource-only 9.9 plan.

No production operation may begin until the preparatory workflow/drift PR and the 9.9 desired-state PR have each passed required CI and merged normally.

## Fixed identities and expected live baseline

Use these values as invariants, not as mutable deployment inputs:

| Item | Expected value |
|---|---|
| R4 image tag | `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900` |
| Catalog version | `a00b32ac0267e1a9` |
| Portfolio R4 digest | `sha256:abbb9d133df23f3ac2f17baa608ac87bc8805aed30b3cfffaacf81338a1c929b` |
| Market-data R4 digest | `sha256:ad61144b2e747a5dd1b1fc9f5b5a091916559adf7c30117beae3563123aa2256` |
| Insight R4 digest | `sha256:f7db159d5b07085e471d784d50456a1eb384abe178a8fbcf7f4b5b17e916a46b` |
| API-gateway R4 digest | `sha256:ff80395eecaef731a089697dda50f34064612d478ac329e872631364082b7d0a` |
| Resource group | `wealth-azure-prod-rg` |
| Registry | `wealthprodacr` |
| Log Analytics workspace | `wealth-prod-la` |

The three services must initially have both explicit overrides equal to `false`, minimum replicas `0`/unset in the live revision, and R4 images. API-gateway ingress must be absent/disabled. `market-data-refresh-job` must have `MARKET_DATA_JOB_RUNNER_ENABLED=false` and no running execution. Kafka lag must be zero immediately before the apply.

---

## Task 1: Harden the Azure Terraform execution path

**Files:**

- Modify: `.github/workflows/terraform-azure.yml`
- Modify: `.github/workflows/ci-verification.yml`
- Create: `infrastructure/terraform/azure/scripts/validate_dispatch.py`
- Create: `infrastructure/terraform/azure/scripts/assert_spec_a_9_9_plan.py`
- Create focused unit tests beside those scripts, following the existing script-test layout
- Modify: `infrastructure/terraform/azure/main.tf` only for the static-web-app drift suppression; do not make the 9.9 service changes in this PR

### Required workflow contract

1. Extend `action` to `plan`, `remote-plan`, and `apply`.
2. Add mandatory dispatch inputs for live-state operations:
   - `expected_main_sha`: full 40-character SHA.
   - `deployed_image_tag`: full 40-character R4 image tag.
   - `change_profile`: `standard`, `spec-a-9.9-enable`, or `spec-a-9.9-abort`.
3. For `remote-plan` and `apply`, fail before Terraform initialization unless:
   - `github.ref == 'refs/heads/main'`;
   - `github.sha == expected_main_sha`;
   - both SHA inputs match `^[0-9a-f]{40}$`;
   - `use_seed_image == false` and `recreate_market_data_job == false` for either 9.9 profile;
   - `deployed_image_tag` resolves to the known R4 images in ACR.
4. Set `TF_VAR_image_tag` from `deployed_image_tag` for live-state operations. Preserve `${{ github.sha }}` only for the existing local structural-plan behavior. This keeps both container image configuration and `SERVICE_VERSION` on R4.
5. Put the apply authorization in a distinct job using `environment: production`. Validation must complete before the reviewer is asked to approve. The Terraform apply job must depend on both validation and authorization.
6. `remote-plan` must use `AZURE_BACKEND_HCL` and the real backend, create `tfplan`, run every existing assertion plus the selected profile assertion, and publish only a sanitized address/action summary. Do not upload raw `tfplan` or `tfplan.json`; both may contain sensitive values.
7. `apply` must create a fresh plan in that same run, run the identical assertions, and apply that saved plan. It must not accept a plan artifact from another run.
8. For `spec-a-9.9-enable`, `assert_spec_a_9_9_plan.py` must require exactly these three non-no-op resource changes:
   - `module.portfolio_service.azurerm_container_app.this`
   - `module.market_data_service.azurerm_container_app.this`
   - `module.insight_service.azurerm_container_app.this`
9. The enable profile must inspect the before/after JSON without printing secrets and prove, for each resource:
   - action is in-place `update`, never replace/create/delete;
   - `min_replicas` changes from `0`/unset to `1`;
   - both catalog override entries are absent afterward;
   - image reference remains the R4 tag;
   - `SERVICE_VERSION` remains the R4 tag.
10. For `spec-a-9.9-abort`, assert the same three-resource-only scope, both overrides restored to `false`, minimum replicas returned to `0`, and the R4 image/version identity unchanged.
11. Add the workflow to the repository's pinned `actionlint` coverage and add contract tests for all dispatch guards, job dependencies, Environment binding, and profile-assertion invocation.
12. Suppress only the documented Azure-computed `repository_url` drift on `azurerm_static_web_app.frontend`; add a focused Terraform/plan test proving that no broader lifecycle ignore was introduced.

### Verification commands

Run the script tests, the complete deploy-workflow contract suite, Terraform formatting/validation, and the repository's spec-reference coverage command documented in Task 9. Then open a normal PR and wait for all required checks. Do not push directly to `main` and do not use an administrative bypass.

Minimum local checks:

```powershell
python -m unittest discover infrastructure/terraform/azure/scripts -p "test_*.py"
terraform -chdir=infrastructure/terraform/azure fmt -check -recursive
terraform -chdir=infrastructure/terraform/azure init -backend=false
terraform -chdir=infrastructure/terraform/azure validate
```

Acceptance: a deliberately wrong ref, main SHA, deployed image SHA, profile scope, image change, `SERVICE_VERSION` change, or fourth changed resource is rejected by a focused test. The PR must merge through fresh green CI before Task 2.

---

## Task 2: Prepare the 9.9 desired state and rollback state

**Files:**

- Modify: `infrastructure/terraform/azure/main.tf`
- Modify: `.kiro/specs/supported-asset-integrity/tasks.md` only to record planning/review state; do not mark 9.9 complete
- Add/update exact-plan fixtures only if required by Task 1's profile tests

In a dedicated PR:

1. Change `min_replicas = 0` to `min_replicas = 1` in `module.portfolio_service`, `module.market_data_service`, and `module.insight_service`.
2. Remove—not set to `true`—both entries from each module:
   - `APP_CATALOG_REJECT_UNSUPPORTED_EVENTS`
   - `APP_CATALOG_ENFORCE_HOLDING_INVARIANT`
3. Remove/update the obsolete 9.8 override comments.
4. Do not change API-gateway, either Container App Job, any image tag, ingress, secrets, Kafka settings, or application code.
5. Prepare a reviewed rollback patch or branch from the enable PR head that restores the six `false` entries and the three `min_replicas = 0` values. Do not merge it. Its only permitted use is the abort gate below while writes remain closed.

CI evidence for this PR must include the portfolio-service tests that cover the two behaviorally meaningful gates (`MarketPriceProjectionCurrencyTest`, `SupportedAssetValidatorTest`, and the enforcement-default contract), plus the complete required repository CI. Market-data and insight need startup-binding evidence but no fabricated behavioral probe; they do not enforce the flags today.

Acceptance: the PR diff is the nine desired-state edits plus accurate documentation/test fixtures. Merge normally only after the exact-scope assertion exists on `main`.

---

## Task 3: Establish the execution lock and immutable evidence bundle

This task is read-only. Stop if any value differs from the expected baseline.

```powershell
$Repo = "vibhanshu-agarwal/wealthmgmtandportfoliotracker"
$ResourceGroup = "wealth-azure-prod-rg"
$Registry = "wealthprodacr"
$Workspace = "wealth-prod-la"
$R4Tag = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
$ResolvedR4Tag = gh api "repos/$Repo/commits/9b2cf0d" --jq .sha
$MainSha = gh api "repos/$Repo/commits/main" --jq .sha
$EvidenceDir = Join-Path $env:TEMP ("spec-a-9.9-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Path $EvidenceDir | Out-Null

if ($ResolvedR4Tag -ne $R4Tag) { throw "R4 commit identity no longer resolves as expected" }
if ($MainSha -notmatch '^[0-9a-f]{40}$') { throw "main did not resolve to a full SHA" }
```

Record `git show --no-patch`, the merged PR URL and green check list, `$MainSha`, `$R4Tag`, UTC time, operator identity, and Azure subscription/tenant IDs. Do not record secret values.

For each service, capture the active revision and full configuration:

```powershell
$Services = @("portfolio-service", "market-data-service", "insight-service")
$Before = @{}
foreach ($Service in $Services) {
  $Before[$Service] = az containerapp show --name $Service --resource-group $ResourceGroup `
    --query properties.latestReadyRevisionName -o tsv
  az containerapp show --name $Service --resource-group $ResourceGroup -o json |
    Set-Content -LiteralPath (Join-Path $EvidenceDir "$Service-before-app.json")
  az containerapp revision list --name $Service --resource-group $ResourceGroup -o json |
    Set-Content -LiteralPath (Join-Path $EvidenceDir "$Service-before-revisions.json")
}

az containerapp show --name api-gateway --resource-group $ResourceGroup -o json |
  Set-Content -LiteralPath (Join-Path $EvidenceDir "api-gateway-before.json")
az containerapp job show --name market-data-refresh-job --resource-group $ResourceGroup -o json |
  Set-Content -LiteralPath (Join-Path $EvidenceDir "refresh-job-before.json")
az containerapp job execution list --name market-data-refresh-job --resource-group $ResourceGroup -o json |
  Set-Content -LiteralPath (Join-Path $EvidenceDir "refresh-job-executions-before.json")
```

Read back, visibly:

- gateway ingress remains absent/disabled;
- refresh Job fence is exactly `false` and no execution is running;
- all three current revisions use `$R4Tag`, both overrides are `false`, and minimum replicas are `0`/unset;
- no operational repair Job execution is running;
- Kafka consumer lag is zero for every `market-prices` partition for both `portfolio-group` and `insight-group`.

Use exact Azure read-backs rather than interpreting the saved JSON by eye:

```powershell
az containerapp show --name api-gateway --resource-group $ResourceGroup `
  --query "properties.configuration.ingress" -o json
az containerapp job show --name market-data-refresh-job --resource-group $ResourceGroup `
  --query "properties.template.containers[0].env[?name=='MARKET_DATA_JOB_RUNNER_ENABLED']" -o json
az containerapp job execution list --name market-data-refresh-job --resource-group $ResourceGroup `
  --query "[?properties.status=='Running'].{name:name,status:properties.status,start:properties.startTime}" -o json
```

The first command must print `null`, the second exactly one entry with value `false`, and the third `[]`.

The repository records the 9.4 result (topic end offset `24541`; both groups committed at `24541`) but does **not** preserve the command/API query that produced it. That is an execution-plan gap, not permission to invent a replacement at the gate. Before 9.9 can be approved, add the exact credential-safe Aiven/Kafka offset command used by the operator to the runbook and prove it returns, for every partition, topic end offset, committed offset, and computed lag for both groups. Run it immediately before apply and again during GO. If the original procedure cannot be recovered, create and review a separate read-only Kafka-lag script/command first; no Kafka probe may publish, commit, reset, or delete offsets.

Resolve the four R4 tags in ACR and compare to the fixed digest table:

```powershell
$Repositories = @("portfolio-service", "market-data-service", "insight-service", "api-gateway")
foreach ($Repository in $Repositories) {
  az acr manifest list-metadata --registry $Registry --name $Repository `
    --query "[?tags != null && contains(tags, '$R4Tag')].{digest:digest,tags:tags}" -o json
}
```

Stop if the tag is missing, resolves to more than one manifest, or any digest differs. This plan defines “digest matches” as: the configured/logged R4 tag plus identical ACR tag-to-manifest resolution immediately before and after the cutover. Container App console logs expose the tag, not a runtime pull digest; do not claim stronger runtime-digest evidence unless Azure exposes it directly during execution.

### Pre-apply stop conditions

Stop and do not dispatch if any of these is true:

- `main` advances after `$MainSha` is captured;
- either PR bypassed required checks or does not have fresh green checks;
- gateway ingress is enabled;
- refresh fence is not exactly `false`, or any refresh/repair execution is running;
- Kafka lag is non-zero or producer quiescence cannot be established;
- any current service image or ACR digest differs from R4;
- any current catalog startup line has a different catalog version;
- current overrides are not both `false` on all three services;
- the production Environment protection/reviewer cannot be demonstrated.

---

## Task 4: Generate the production-state plan and review it

Dispatch `remote-plan` from the exact `main` SHA:

```powershell
gh workflow run terraform-azure.yml --repo $Repo --ref main `
  -f action=remote-plan `
  -f expected_main_sha=$MainSha `
  -f deployed_image_tag=$R4Tag `
  -f change_profile=spec-a-9.9-enable `
  -f use_seed_image=false `
  -f recreate_market_data_job=false

$PlanRun = gh run list --repo $Repo --workflow terraform-azure.yml --event workflow_dispatch `
  --limit 1 --json databaseId,headSha,status,conclusion --jq '.[0].databaseId'
gh run watch $PlanRun --repo $Repo --exit-status
gh run view $PlanRun --repo $Repo --log
```

Two reviewers must compare the sanitized output with the profile assertion. Required plan result:

- exactly three changed addresses, all in-place updates;
- only the three service Container Apps;
- `min_replicas` after-value `1` on each;
- both overrides absent after-value on each;
- images and `SERVICE_VERSION` remain `$R4Tag`;
- no API-gateway, Job, registry, identity, role, secret, static site, monitoring, or unrelated resource change;
- no destroy/create/replace action.

The local-backend PR plan is not acceptable evidence for this gate. Stop if the remote plan is empty, contains drift, or contains any fourth resource. Resolve drift through a new PR and restart from Task 3; never waive the assertion in the workflow UI.

---

## Task 5: Obtain explicit go and apply the exact profile

Present the evidence bundle, remote-plan run URL, exact three-resource summary, rollback commit/branch, and the following blast radius to the approver:

- Three new single-mode Container App revisions will be created.
- Minimum replicas `1` starts portfolio and insight Kafka consumers even though ingress is closed. Market-data's long-lived app also starts, but its scheduled refresh remains disabled and its Job fence remains false.
- The environment-variable removal makes the R4 defaults effective. Only portfolio behaviorally enforces them; the other two services log the values.
- This does not open ingress, start a refresh Job, deploy a new image, or execute 9.10.

After a separate explicit go, re-read `main`; it must still equal `$MainSha`. Then dispatch:

```powershell
if ((gh api "repos/$Repo/commits/main" --jq .sha) -ne $MainSha) {
  throw "main advanced; restart at Task 3"
}

$ApplyStartUtc = (Get-Date).ToUniversalTime().ToString("o")
gh workflow run terraform-azure.yml --repo $Repo --ref main `
  -f action=apply `
  -f expected_main_sha=$MainSha `
  -f deployed_image_tag=$R4Tag `
  -f change_profile=spec-a-9.9-enable `
  -f use_seed_image=false `
  -f recreate_market_data_job=false
```

Confirm the run pauses at the `production` Environment and that the configured reviewer—other than the initiator where GitHub plan capabilities permit—approves it. Record reviewer and approval time. Monitor to completion:

```powershell
$ApplyRun = gh run list --repo $Repo --workflow terraform-azure.yml --event workflow_dispatch `
  --limit 1 --json databaseId,headSha,status,conclusion --jq '.[0].databaseId'
gh run watch $ApplyRun --repo $Repo --exit-status
gh run view $ApplyRun --repo $Repo --log
```

If the workflow fails before `terraform apply`, production is unchanged: inspect and stop. If it fails during/after apply, treat the result as potentially partial; capture live state before deciding on rollback. Terraform apply is not an atomic three-resource transaction.

---

## Task 6: Identify and stabilize the three new revisions

Do not infer success from a green Action. `latestReadyRevisionName` can initially remain the old revision while the new revision starts. Poll until `latestRevisionName` and `latestReadyRevisionName` converge on a revision different from the captured baseline; then require Healthy/Provisioned with at least one running replica:

```powershell
$After = @{}
foreach ($Service in $Services) {
  $Deadline = (Get-Date).AddMinutes(15)
  do {
    $App = az containerapp show --name $Service --resource-group $ResourceGroup -o json | ConvertFrom-Json
    $Latest = $App.properties.latestRevisionName
    $Ready = $App.properties.latestReadyRevisionName
    if ($Latest -and $Ready -and $Latest -eq $Ready -and $Ready -ne $Before[$Service]) { break }
    Start-Sleep -Seconds 15
  } while ((Get-Date) -lt $Deadline)
  if (-not $Ready -or $Latest -ne $Ready -or $Ready -eq $Before[$Service]) {
    throw "$Service did not converge on a new ready revision within 15 minutes"
  }
  $RevisionName = $Ready
  $Revision = az containerapp revision show --name $Service --revision $RevisionName `
    --resource-group $ResourceGroup -o json | ConvertFrom-Json
  $After[$Service] = $RevisionName
  $Revision | ConvertTo-Json -Depth 100 |
    Set-Content -LiteralPath (Join-Path $EvidenceDir "$Service-after-revision.json")
}
$After
```

Use the exact revision query below for the operator-visible gate:

```powershell
foreach ($Service in $Services) {
  $RevisionName = $After[$Service]
  az containerapp revision show --name $Service --revision $RevisionName `
    --resource-group $ResourceGroup `
    --query "{name:name,active:properties.active,healthState:properties.healthState,runningState:properties.runningState,provisioningState:properties.provisioningState,created:properties.createdTime,replicas:properties.replicas,image:properties.template.containers[0].image,minReplicas:properties.template.scale.minReplicas,env:properties.template.containers[0].env[?name=='APP_CATALOG_REJECT_UNSUPPORTED_EVENTS' || name=='APP_CATALOG_ENFORCE_HOLDING_INVARIANT' || name=='SERVICE_VERSION'].{name:name,value:value}}" -o json
}
```

Required per revision:

- `active=true`, `healthState=Healthy`, `provisioningState=Provisioned`;
- `runningState` is running and replica count is at least one;
- image ends with `:$R4Tag`;
- `minReplicas=1`;
- `SERVICE_VERSION=$R4Tag`;
- neither catalog override exists in the revision environment.

Also require a non-empty live replica list for the exact revision:

```powershell
foreach ($Service in $Services) {
  az containerapp replica list --name $Service --revision $After[$Service] `
    --resource-group $ResourceGroup -o json
}
```

Wait only long enough for normal startup and Log Analytics ingestion. If a revision never becomes ready, is crash-looping, or scales back to zero, enter the abort decision; do not proceed to 9.10.

---

## Task 7: Prove one consistent startup tuple from Log Analytics

Get the workspace customer ID and query only the exact new revision names and the post-dispatch window:

```powershell
$WorkspaceId = az monitor log-analytics workspace show --resource-group $ResourceGroup `
  --workspace-name $Workspace --query customerId -o tsv
$PortfolioRevision = $After["portfolio-service"]
$MarketRevision = $After["market-data-service"]
$InsightRevision = $After["insight-service"]

$Kql = @"
let CutoverStart = datetime($ApplyStartUtc);
let ExpectedVersion = "a00b32ac0267e1a9";
let ExpectedImageTag = "$R4Tag";
let Expected = datatable(ContainerAppName_s:string, RevisionName_s:string)
[
  "portfolio-service", "$PortfolioRevision",
  "market-data-service", "$MarketRevision",
  "insight-service", "$InsightRevision"
];
Expected
| join kind=leftouter (
    ContainerAppConsoleLogs_CL
    | where TimeGenerated >= CutoverStart
    | where Log_s contains_cs "catalog_loaded"
    | extend CatalogVersion = extract(@"version=([0-9a-f]+)", 1, Log_s)
    | extend RejectUnsupported = extract(@"rejectUnsupportedEvents=(true|false)", 1, Log_s)
    | extend EnforceHolding = extract(@"enforceHoldingInvariant=(true|false)", 1, Log_s)
  ) on ContainerAppName_s, RevisionName_s
| summarize
    StartupLines=countif(isnotempty(Log_s)),
    ReplicaIdentities=dcount(ContainerGroupName_s),
    TupleCount=dcount(strcat(CatalogVersion, "|", RejectUnsupported, "|", EnforceHolding)),
    Versions=make_set(CatalogVersion),
    RejectValues=make_set(RejectUnsupported),
    EnforceValues=make_set(EnforceHolding),
    Images=make_set(ContainerImage_s)
  by ContainerAppName_s, RevisionName_s
| order by ContainerAppName_s asc
"@

az monitor log-analytics query --workspace $WorkspaceId --analytics-query $Kql -o table
```

Go requires exactly three rows. Every row must have `StartupLines >= 1`, `TupleCount = 1`, version set only to `a00b32ac0267e1a9`, both Boolean sets only `true`, and image set only to the R4 tag. `ReplicaIdentities` may exceed one; every startup line from every observed replica under the exact revision must have the same tuple.

Run the exact negative queries below. Both must return zero rows:

```powershell
$NegativeKql = @"
let CutoverStart = datetime($ApplyStartUtc);
let ExpectedVersion = "a00b32ac0267e1a9";
let ExpectedImageTag = "$R4Tag";
let Expected = datatable(ContainerAppName_s:string, RevisionName_s:string)
[
  "portfolio-service", "$PortfolioRevision",
  "market-data-service", "$MarketRevision",
  "insight-service", "$InsightRevision"
];
ContainerAppConsoleLogs_CL
| where TimeGenerated >= CutoverStart
| where Log_s contains_cs "catalog_loaded"
| join kind=inner Expected on ContainerAppName_s, RevisionName_s
| extend CatalogVersion = extract(@"version=([0-9a-f]+)", 1, Log_s)
| extend RejectUnsupported = extract(@"rejectUnsupportedEvents=(true|false)", 1, Log_s)
| extend EnforceHolding = extract(@"enforceHoldingInvariant=(true|false)", 1, Log_s)
| where CatalogVersion != ExpectedVersion
    or RejectUnsupported != "true"
    or EnforceHolding != "true"
    or ContainerImage_s !endswith_cs ExpectedImageTag
| project TimeGenerated, ContainerAppName_s, RevisionName_s, ContainerGroupName_s,
          ContainerImage_s, Log_s
"@

$DltKql = @"
let CutoverStart = datetime($ApplyStartUtc);
ContainerAppConsoleLogs_CL
| where TimeGenerated >= CutoverStart
| where ContainerAppName_s == "portfolio-service"
| where RevisionName_s == "$PortfolioRevision"
| where Log_s contains_cs "DLT: Failed record received"
| project TimeGenerated, ContainerAppName_s, RevisionName_s, ContainerGroupName_s, Log_s
"@

az monitor log-analytics query --workspace $WorkspaceId --analytics-query $Kql -o json |
  Set-Content -LiteralPath (Join-Path $EvidenceDir "catalog-positive.json")
az monitor log-analytics query --workspace $WorkspaceId --analytics-query $NegativeKql -o json |
  Set-Content -LiteralPath (Join-Path $EvidenceDir "catalog-negative.json")
az monitor log-analytics query --workspace $WorkspaceId --analytics-query $DltKql -o json |
  Set-Content -LiteralPath (Join-Path $EvidenceDir "portfolio-dlt-negative.json")
```

Save the three query texts alongside their JSON results. Historical 9.8 `true` lines are not evidence for this gate. Lines from old revisions are not evidence either.

---

## Task 8: Define the behavioral-enforcement evidence honestly

There is no current portfolio holdings write endpoint, gateway ingress is closed, and the refresh producer is fenced. A live negative Kafka probe would mutate offsets and likely DLT state; a live holding-invariant probe has no supported production path. Do not improvise either probe during an irreversible cutover.

The recommended 9.9 evidence contract is:

1. Exact-R4 CI evidence proves the two portfolio enforcement paths behave correctly under `true` defaults.
2. The live, exact-revision startup tuple proves Spring bound both defaults to `true` in portfolio.
3. Tasks 6–7 prove the same R4 artifact and catalog version are running.
4. Checkpoint 9.10 provides the first controlled supported producer path and its own behavioral observations.

The owner must accept that evidence contract before apply approval. If a live negative rejection is mandatory, stop 9.9 and design a separately reviewed probe with explicit Kafka topic/group/DLT cleanup and rollback semantics. That is not an on-the-fly addition to this plan.

---

## Task 9: Go, stop, and abort decisions

### GO — mark 9.9 successful only when all are true

- Apply run used exact `$MainSha`, R4 `deployed_image_tag`, `spec-a-9.9-enable`, and production approval.
- Applied plan had exactly the three permitted in-place updates.
- Three exact active revisions are Healthy/Provisioned/running at minimum one replica.
- Images and `SERVICE_VERSION` remain R4; ACR tag-to-digest resolution is unchanged when queried again.
- Both override names are absent from all three revision configurations.
- Every exact-revision startup line has the same expected catalog version and both gates `true`.
- Gateway ingress remains closed; refresh fence remains `false`; no refresh or repair Job ran.
- The approved read-only Kafka check still reports zero lag on every partition for both groups, and the exact new portfolio revision has no new `DLT: Failed record received` line during the observation window.
- The owner accepted the non-mutating behavioral-evidence contract in Task 8.

### STOP — investigate without changing state

Stop when evidence is missing but the three apps are healthy and writes remain closed—for example, Log Analytics ingestion delay or an inconclusive ACR lookup. Keep ingress and refresh closed, preserve evidence, and do not advance to 9.10.

### ABORT — restore the 9.8 fence only while writes are closed

Abort if any new revision is unhealthy, tuple values differ, catalog version differs, an image/version changes, enforcement generates unexpected operational errors, or the apply is partially successful.

Before rollback, re-prove gateway ingress closed, refresh fence false, no Job execution running, and producers quiescent. Snapshot all current revisions/config/logs. Then merge the pre-reviewed rollback patch normally, capture the new main SHA, generate a real-backend `spec-a-9.9-abort` plan using the same R4 `deployed_image_tag`, and require exactly three in-place updates. Obtain a new explicit production approval and apply it. The after-state must restore both false overrides and minimum replicas zero on all three services without changing image or `SERVICE_VERSION`.

Do not:

- blindly rerun a failed enable apply;
- manually edit only one Container App in Azure;
- use `az containerapp update` as an undocumented shortcut;
- roll back after writes or refresh have been enabled;
- reuse the enable run's approval or plan;
- proceed to 9.10 on a partial or merely “green workflow” result.

If writes have opened, this rollback procedure is no longer authorized because it would silently reintroduce permissive behavior. Escalate to a new incident plan.

---

## Task 10: Record the checkpoint and stop before 9.10

After GO, update `.kiro/specs/supported-asset-integrity/tasks.md` in a documentation-only PR with:

- workflow/plan/apply run IDs and exact SHAs;
- production approver and UTC chronology;
- sanitized exact plan summary;
- before/after revision names and configurations;
- R4 tag and before/after ACR digest resolutions;
- KQL text/results and the accepted behavioral-evidence contract;
- final ingress, refresh-fence, Job-execution, Kafka-lag, and DLT observations;
- any platform limitation or ambiguity encountered.

Mark only checkpoint 9.9 complete. Explicitly leave 9.10 unchecked. Merge that documentation through normal fresh green CI. Do not open ingress, enable the refresh fence, run a refresh, or restore minimum replicas to zero as part of 9.9.

## Resolved questions and remaining owner decision

- **Comparable automatic triggers:** none found that can apply this Azure Terraform change. Pull-request handling in `terraform-azure.yml` is local-backend plan only; the AWS Terraform workflow is manual; deployment is manual dispatch. The hardened workflow must preserve this property.
- **Effect of `min_replicas=1`:** it intentionally creates startup evidence and wakes the long-lived services. Portfolio and insight Kafka consumers become live; therefore zero lag and producer quiescence are mandatory. It does not start the separate refresh Job or open gateway ingress.
- **Environment removal semantics:** the Container App module uses single revision mode. Removing env entries and changing scale creates new revisions; the image field is lifecycle-ignored and must remain R4. Validate the active revision, not a replica count from an old revision.
- **Remaining prerequisite evidence:** recover and document the exact read-only Kafka-lag command used at 9.4, or land a separately reviewed read-only replacement before approval.
- **Only remaining behavioral decision:** accept CI behavior tests plus live R4 startup binding as the portfolio enforcement proof for 9.9, or pause to design a separately authorized live negative probe.
