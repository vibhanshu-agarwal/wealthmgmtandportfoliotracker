# Claude Kickoff — B2 Task 4.5 demo-reset STOP/GO

- **Prepared:** 2026-09-01
- **Prepared by:** Codex, senior architect/reviewer
- **Operator:** Claude
- **Scope:** production preflight, immutable candidate build, digest deployment, and one controlled
  demo-reset probe
- **Durable documentation owner:** Codex after evidence review

## 1. Role boundary

Claude is the operator for this task, not the author of the lasting program documentation.

Claude SHALL:

1. perform the read-only and local preflight exactly as written;
2. stop and return the prescribed authorization packet;
3. perform production mutations only after the repository owner explicitly authorizes the exact
   candidate build, digest deployment, single demo-reset probe, and conditional rollback described
   in that packet;
4. return the prescribed execution evidence to Codex; and
5. stop without editing or opening a pull request.

Claude SHALL NOT draft or modify `tasks.md`, the Asset Picker master plan, a runbook, source code,
tests, workflows, Terraform, Dockerfiles, or any other repository file. Codex will review the raw
evidence and, if the gate is satisfied, author the separate governed evidence/status PR.

This kickoff document's merge authorizes only local verification and read-only preflight. It does
not authorize an ACR build, a deployment, a secret read, or a production reset.

## 2. Governing acceptance contract

Task 4.5 is owned by
`.kiro/specs/asset-picker-composition/tasks.md` under **Wave 4 — Demo-reset, portfolio-service
side**. The required live sequence is fixed:

1. authenticate as the demo user;
2. call the real deployed `GET /api/portfolio` once;
3. select the demo user's own portfolio and capture its numeric `version`;
4. call `PUT /api/internal/portfolio/demo-reset` with `X-Internal-Api-Key` and that exact observed
   version; and
5. assert the `200` response preserves the portfolio identity, returns `version + 1`, and carries
   exactly the wire-visible golden holdings derived by
   `scripts/derive_demo_golden_state.py`.

The live call must never obtain `expectedVersion` from a dedicated endpoint, database query,
cached value, previous run, or a second pre-reset portfolio read. A `409` is terminal. Do not read a
new version and retry.

## 3. Why the deploy uses an immutable historical cut

The currently serving `portfolio-service` image was built from
`0887a309fe12f49ca37585e5a594661727cf4936`. B2 Wave 4 Tasks 4.1–4.4a merged later through PR #180
at `63fc0584ad307af7f50e9500f4911ac5999d6b76`. Later `main` commits also contain independent B2
runtime work, including Task 8.1's additive `updatedAt` contract.

Task 4.5 must prove the Wave 4 demo-reset endpoint without silently deploying unrelated later B2
runtime work. Therefore:

- candidate source is pinned to
  `63fc0584ad307af7f50e9500f4911ac5999d6b76`;
- the candidate is built once in ACR from a clean detached worktree at that exact commit;
- the resulting immutable manifest digest is deployed through the current `main` version of
  `.github/workflows/deploy.yml` using `deployment_mode=digest`; and
- a normal scoped/tag build from current `main` is forbidden for this task.

The pinned cut contains the already-live B1 numeric portfolio-version contract plus B2 Wave 4's
demo-reset source and independent oracle. It excludes Task 8.1 and later unrelated B2 runtime work.

## 4. Verified planning snapshot — re-read, never assume

Codex observed the following read-only state on 2026-09-01:

| Resource | Observed state |
|---|---|
| `portfolio-service` | `portfolio-service--0000092`, Healthy, Single mode, 100% traffic |
| Serving image | `wealthprodacr.azurecr.io/portfolio-service@sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a` |
| Serving image provenance | tag `spec-a-912-provenance-0887a309fe12-20260829`; source cut `0887a309fe12f49ca37585e5a594661727cf4936` |
| Portfolio ingress | internal only |
| `api-gateway` | `api-gateway--0000077`, Single mode, 100% traffic, external ingress enabled |
| Gateway image | `wealthprodacr.azurecr.io/api-gateway:63fc0584ad307af7f50e9500f4911ac5999d6b76` |
| Internal-key configuration | both apps expose an `INTERNAL_API_KEY` environment-variable name; no value was read |
| Azure resource group / registry | `wealth-azure-prod-rg` / `wealthprodacr` |

The current portfolio digest above is the exact conditional rollback target. If any resource,
revision, digest, traffic mode, ingress direction, or source-provenance fact differs during
preflight, Claude must stop. Do not substitute a newer revision or invent a new rollback target.

## 5. Workspace and start-point guard

Use Claude's assigned sibling worktree:

`D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-claude`

Do not use `.claude/worktrees`, `.codex/worktrees`, or any other nested worktree. A transient
candidate worktree is allowed only at:

`C:\worktrees\wealthmgmtandportfoliotracker-claude-b2-45-candidate`

In the assigned worktree:

1. fetch `origin`;
2. verify this exact kickoff file exists on `origin/main`;
3. verify `b42430452be73630b040ecee3adda63cf4554098` is an ancestor of `origin/main`;
4. record the commit that last changed this kickoff file;
5. verify no later commit changed any of these risk paths:
   - `portfolio-service/`
   - `common-dto/`, `common-observability/`, or `common-catalog/`
   - `config/seed-tickers.json`
   - `scripts/derive_demo_golden_state.py`
   - `.github/workflows/deploy.yml`
   - `.github/workflows/deploy-azure.yml`
   - `.github/workflows/scripts/resolve_digest_deploy.py`
   - `.github/workflows/scripts/snapshot_container_apps.py`
6. create local branch `ops/b2-task-4-5-demo-reset-stop-go` at `origin/main`; if that branch
   already exists, inspect it and stop rather than resetting or overwriting it; and
7. verify the assigned worktree is clean.

Docs-only drift after the kickoff commit is acceptable. Any change in a risk path is a hard stop
for renewed Codex review.

## 6. Phase A — local and read-only preflight

No step in this phase may mutate Azure, GitHub settings, production data, or the repository.

### A1. Prove the candidate cut and diff

Verify both pinned commits resolve locally and that the currently served source cut is an ancestor
of the candidate cut. Compare
`0887a309fe12f49ca37585e5a594661727cf4936..63fc0584ad307af7f50e9500f4911ac5999d6b76`
for the portfolio, shared-module, catalog, oracle, Docker, and deploy-workflow paths.

The only production-source additions allowed in that comparison are:

- `portfolio-service/src/main/java/com/wealth/portfolio/demo/DemoResetController.java`
- `portfolio-service/src/main/java/com/wealth/portfolio/demo/DemoResetRequest.java`
- `portfolio-service/src/main/java/com/wealth/portfolio/demo/DemoResetService.java`
- the narrow `PortfolioService.java` projection-access change consumed by the reset service.

`portfolio-service/build.gradle`, tests, CI test wiring, and the independent oracle may differ as
already merged at the pinned cut. There must be no catalog, common-module, Dockerfile, deployment-
workflow, migration, or other production-source difference. If the comparison does not match this
description, stop.

### A2. Create and verify the detached candidate worktree

Create the transient sibling worktree at the exact candidate cut. Refuse to reuse the directory if
it already exists or is registered. In that worktree verify:

- `HEAD` equals the full pinned `63fc0584...` SHA;
- `git status --porcelain` is empty;
- `DemoResetController`, `DemoResetService`, and `scripts/derive_demo_golden_state.py` exist; and
- `PortfolioResponse` exposes numeric `version` but does not yet expose Task 8.1's `updatedAt`.

Do not modify this worktree.

### A3. Re-run the pinned candidate verification

From the detached candidate worktree run:

```powershell
.\gradlew.bat :portfolio-service:test :portfolio-service:integrationTest --no-daemon --rerun-tasks
python scripts/tests/test_derive_demo_golden_state.py -v
python scripts/derive_demo_golden_state.py
```

All tests must pass. Capture test counts and the oracle metadata fields `demoUserId`,
`catalogSha256`, `catalogTotalEntries`, and `activeEntryCount`. Do not paste the full oracle into
chat; retain it in memory for the live comparison.

### A4. Re-read production without secret values

Using Azure CLI read-only queries, capture:

- subscription name and enabled state;
- the portfolio app's provisioning state, latest/latest-ready revision, active-revision mode,
  traffic, image digest, ingress direction, and scale;
- the gateway's same fields;
- the active portfolio revision list;
- the ACR metadata and tag for the serving portfolio digest; and
- only the `name` and `secretRef` fields for each app's `INTERNAL_API_KEY` environment entry.

Never run `az containerapp secret list`, never query an environment `value`, and never print an
internal key, password, JWT, connection string, or complete environment block.

The values must match Section 4, both apps must have a non-empty key `secretRef`, and exactly one
healthy portfolio revision must serve 100% traffic. Otherwise stop.

### A5. Verify current-main deployment controls

From the assigned worktree at current `origin/main`, verify:

- `deploy.yml` is `workflow_dispatch` only;
- it validates the exact full `expected_main_sha` and requires `refs/heads/main`;
- it has a `production` Environment approval gate and non-cancelling production concurrency;
- digest mode accepts only an immutable `wealthprodacr.azurecr.io/portfolio-service@sha256:`
  reference followed by exactly 64 lowercase hexadecimal characters;
- digest mode skips build and push;
- frontend, seed, and verify jobs are skipped in digest/scoped mode; and
- `assert-scoped-non-interference` compares unselected apps and the requested digest.

Run the exact deploy-workflow contract suite used by CI:

```powershell
python scripts/tests/test_resolve_deploy_selection.py
python scripts/tests/test_deploy_azure_service_allowlist.py
python scripts/tests/test_snapshot_container_apps.py
python scripts/tests/test_resolve_digest_deploy.py
python scripts/tests/test_deploy_azure_prebuilt_digest.py
python scripts/tests/test_validate_deploy_dispatch.py
python scripts/tests/test_deploy_pipeline_hardening.py
```

Record counts. Any failure or structural mismatch is a hard stop.

### A6. Verify probe credentials are present without reading them

The owner must make these variables available securely in Claude's local shell before execution:

- `B2_DEMO_EMAIL`
- `B2_DEMO_PASSWORD`
- `B2_INTERNAL_API_KEY`

Claude may test only whether each value is non-blank. Do not print lengths, prefixes, hashes, or
values. Do not retrieve a value from Azure or GitHub. If any variable is absent, report the missing
variable name and stop.

### A7. Read-only endpoint preflight

Against `https://api.vibhanshu-ai-portfolio.dev`:

1. authenticate with the two demo variables;
2. verify login returns HTTP `200`, a token, and demo `userId`
   `00000000-0000-0000-0000-0000000d3110`;
3. call authenticated `GET /api/portfolio` once;
4. verify HTTP `200`, exactly one portfolio for that user, and a non-negative integer `version`;
5. discard the JWT after the check; and
6. do not call any `/api/internal/**` endpoint in preflight.

This read is connectivity evidence only. Its version must not be reused for the later reset.

## 7. Mandatory stop and authorization packet

After Phase A, stop. Do not build or push an image and do not dispatch a workflow.

Return one compact packet headed `READY_FOR_OWNER_AUTHORIZATION` containing:

- kickoff commit and current `origin/main` SHA;
- candidate source cut and successful test counts;
- oracle catalog digest and active-entry count;
- current portfolio revision and immutable digest;
- current gateway revision;
- proposed ACR tag, constructed as
  `b2-task-4-5-63fc0584-` plus the UTC execution timestamp;
- proposed deployment inputs: `deployment_mode=digest`, `services=portfolio-service`, current full
  `main` SHA, and the digest field marked `to be captured from the approved candidate build`;
- exact rollback digest from Section 4;
- confirmation that the only planned data mutation is one `PUT` reset of the compiled-in demo
  identity;
- confirmation that no secret value was read or exposed; and
- this exact question:

> Authorize (1) one ACR candidate build from pinned source cut `63fc0584`, (2) one digest-mode
> production deployment of that exact candidate to `portfolio-service`, (3) one live demo-reset
> `PUT` using the freshly observed version, and (4) rollback to the exact prior digest only if the
> new revision is unhealthy or the authenticated read regresses?

Only an explicit owner approval to that complete question permits Phase B. A review verdict, green
CI, approval of this documentation PR, or GitHub Environment approval alone is not authorization.

## 8. Phase B — authorized execution

### B1. Recheck drift immediately before the first mutation

Fetch `origin` again. Re-run the start-point risk-path check and verify the production snapshot
still matches Phase A. If `main`, the serving revision, the serving digest, or traffic changed,
authorization is stale: stop and return to Codex.

### B2. Build one immutable candidate

From the clean detached candidate worktree, create the timestamped tag named in the approved
packet and run one server-side ACR build:

```powershell
az acr build --registry wealthprodacr --image "portfolio-service:$candidateTag" --file portfolio-service/Dockerfile.azure .
```

After success, resolve the manifest digest from ACR metadata. Require exactly one lowercase
`sha256:` digest with 64 hexadecimal characters and require the timestamped tag to reference it.
Construct the deployment image as
`wealthprodacr.azurecr.io/portfolio-service@` plus that digest.

Record the ACR build run identifier, tag, digest, creation time, architecture, OS, and image size.
Do not retag an existing manifest and do not rebuild after a failure without new Codex review.

### B3. Dispatch the digest deployment

Resolve the current full SHA of `origin/main` again. Dispatch `.github/workflows/deploy.yml` on
`main` with exactly:

- `deployment_mode=digest`
- `expected_main_sha` set to the resolved full main SHA
- `services=portfolio-service`
- `prebuilt_digest` set to the exact immutable image from B2

Do not use `full` or `scoped`, and do not omit `prebuilt_digest`. Capture the run URL and stop at
the GitHub `production` Environment gate so the repository owner can approve it. Claude must not
self-approve or bypass that gate.

After Environment approval, wait for completion. Require all of the following:

- dispatch validation, authorization, routing, Azure deploy, and
  `assert-scoped-non-interference` succeed;
- build and push steps are explicitly `skipped`;
- only `portfolio-service` is selected;
- frontend, seed, and verify jobs are explicitly `skipped`;
- the new app template resolves to the exact requested digest;
- exactly one healthy latest/latest-ready portfolio revision serves 100% in Single mode; and
- gateway, market-data service, insight service, and refresh job match the preflight snapshot.

If the new revision is unhealthy or an authenticated `GET /api/portfolio` regresses, use only the
pre-authorized exact rollback digest from Section 4 through a second guarded digest-mode dispatch.
Do not use `az containerapp update` directly. After rollback, stop and report `ABORT_ROLLED_BACK`.

### B4. Execute the single live reset probe

Run this only after B3 is fully green and live read-back proves the candidate digest serves.

1. Generate a W3C `traceparent` with a new 32-hex trace id and 16-hex span id. Record the trace id;
   it is not a secret.
2. Authenticate again. Keep the JWT only in process memory; never print or persist it.
3. Call the real authenticated `GET /api/portfolio` exactly once.
4. Require HTTP `200`; select exactly one object whose `userId` is
   `00000000-0000-0000-0000-0000000d3110`; capture its `id`, `createdAt`, numeric non-negative
   `version`, and holdings.
5. Set `expectedVersion` to that exact captured version.
6. Call exactly once:
   `PUT https://api.vibhanshu-ai-portfolio.dev/api/internal/portfolio/demo-reset`.
7. Send only `Content-Type: application/json`, the generated `traceparent`, and
   `X-Internal-Api-Key` from the in-memory environment variable. The JSON body contains only the
   captured `expectedVersion`.
8. Do not send the JWT or any caller-controlled user id to the internal endpoint.
9. Require HTTP `200` and assert:
   - response `id` equals the pre-reset portfolio id;
   - response `userId` equals the compiled-in demo id;
   - response `createdAt` equals the pre-reset value;
   - response `version` equals `expectedVersion + 1`;
   - every response holding quantity is a JSON string; and
   - sorted `{assetTicker, quantity}` pairs equal the independent oracle's `wireHoldings` exactly,
     with no missing or extra holding.
10. Make one post-reset authenticated `GET /api/portfolio`. This is verification only, never an
    input to another write. Require the same id, user id, created time, resulting version, and exact
    golden wire holdings as the reset response.
11. Clear the JWT, password, and internal-key variables from process memory.

Do not run a stale-version probe, wrong-key probe, alternate-verb probe, second reset, or any other
negative/fault-injection case against production.

## 9. Abort rules

Stop immediately and do not improvise when any of these occurs:

- this kickoff is absent from `origin/main`;
- a risk path changed after the kickoff commit;
- the pinned candidate diff does not match Section 6;
- local verification is not completely green;
- production state or provenance differs from Section 4;
- a secret variable is unavailable or any secret value appears in output;
- the candidate build yields ambiguous tag/digest provenance;
- the deploy uses any mode or image other than the approved digest path;
- any unselected resource changes;
- login or the one pre-reset portfolio read fails;
- the selected portfolio is absent or ambiguous;
- `version` is missing, quoted, negative, non-integral, or otherwise invalid;
- reset returns `409`, any non-`200`, or an unexpected body; or
- the post-reset read does not match the successful reset response.

Never retry a reset. Never repair production data. Never change secrets, scale, ingress, traffic,
Terraform, or workflows. If a reset returns `200` but a later assertion fails, do not roll back the
image automatically: image rollback cannot undo a committed data mutation. Preserve sanitized
evidence and return `ABORT_POST_COMMIT_MISMATCH` for owner/Codex review.

Task 4.5's normal abort is to leave Wave 5 closed. It authorizes no Wave 5 implementation or
deployment.

## 10. Evidence returned to Codex

Claude's final response must be a compact structured report, not a new document. It must contain
these keys, each populated from the cited phase:

- `status`: one of `BLOCKED_PREFLIGHT`, `READY_FOR_OWNER_AUTHORIZATION`,
  `WAITING_FOR_ENVIRONMENT_APPROVAL`, `ABORT_ROLLED_BACK`, `ABORT_POST_COMMIT_MISMATCH`, or
  `EXECUTED_AWAITING_CODEX_REVIEW`;
- `authorization`: exact owner authorization text and timestamp, or `none`;
- `kickoff_commit`, `dispatch_main_sha`, and `candidate_source_cut`;
- `candidate_build_run`, `candidate_tag`, `candidate_digest`, and ACR metadata;
- `deploy_run_url` and every required job/step conclusion named in B3;
- pre/post revision, digest, traffic, ingress, and scale for all scoped-preservation resources;
- `oracle_catalog_sha256`, `oracle_active_entry_count`, and local test counts;
- login and portfolio-read HTTP statuses without JWT or credentials;
- demo `userId`, portfolio id, observed version, reset method/status, trace id, resulting version,
  holding count, and post-reset verification status;
- `secrets_read_from_control_plane: false`;
- `secret_values_exposed: false`;
- `reset_attempt_count` (must be `0` before authorization and exactly `1` after execution);
- rollback status and exact digest, if used; and
- explicit non-claims: Task 4.5 is not checked by Claude, Wave 5 remains closed, and no unrelated
  B2 source is claimed deployed.

Do not attach raw environment JSON, JWTs, passwords, internal keys, connection strings, complete
HTTP headers, or raw logs that may contain credentials.

## 11. Codex review and closeout

Claude stops after returning the evidence. Codex will independently verify:

- candidate provenance and exact served digest;
- workflow/job conclusions and scoped non-interference;
- the one-read/one-write probe sequence;
- identity, version, and golden-set assertions;
- abort/rollback correctness; and
- absence of secrets.

Only after that review may Codex propose a docs-only PR containing the sanitized operational
record, Task 4.5 checkbox/status reconciliation, and master-plan propagation. Merge of that future
PR remains owner-controlled.
