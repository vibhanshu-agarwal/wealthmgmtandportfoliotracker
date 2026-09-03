# B1 Task 6.5 — Pre-deployment Readiness Record

**OWNER DECISION RECORDED — R-B3 GO, 2026-09-03:** The owner approved local completion
of Task 6.6 and Task 6.7 GO after reviewing the successful G2b proof: “Approved. Please go ahead.”
Both tasks are now checked locally. The [decision record](../evidence/b1-task-6-6/r-b3-owner-go-20260903.json)
binds this approval to the unchanged proof and cu4 serving image. Publication, further production
operations, Wave 7 activation and Writer_Convergence closure remain outside this approval.

**Prepared:** 2026-09-03 by Codex, architecture/review owner.
**State:** Task 6.5 GO recorded by owner decision; read-only metadata preflight completed on
2026-09-03 at approximately 02:45–02:47 UTC. The existing portfolio revision/digest is confirmed.
The single owner-approved candidate build subsequently succeeded as cu4 at 03:35:20 UTC.
Its immutable registry digest is verified in §6.1. This is packaging evidence, not a deployment-ready
or serving-proof attestation by itself. The subsequent 6.6 serving proof is now technically
ACCEPT in the linked report; the owner subsequently approved Task 6.7 GO and local 6.6/6.7 closure.

## 1. Decision and authority

[Task 6.5](../../.kiro/specs/portfolio-composition-contract/tasks.md) says:
- Go: 5.7/G5 green.
- Abort: do not deploy the version-required seed while any caller remains unmigrated.

G5's owner close-out is already on main via PR #215. The additional preparation below makes a
subsequent operational handoff concrete; it does not redefine 6.5 or invent extra source tasks.
Read the [master plan](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md), B1 requirements 8.32/8.39, B1
design's R-B3/Artifact 2b boundary, and the [G5 record](B1_G5_INGRESS_BLOCKER.md).

## 2. Parallel assignment happened first

Cursor owns [Tasks 7.1–7.2](../agent-instructions/CURSOR_KICKOFF_B1_WAVE_7_PUBLIC_COMPOSITION.md)
on a separate implementation branch. Its original baseline is main@6a171558; the permitted start
is now origin/main@9c2ebc1233801253a3e54b6e930e28e1a00ebf3d, whose only delta is PR #218's four
documentation files. Cursor reported assignment readiness, not execution. Its prior B2 branch and
untracked audit/stdout/stderr files remain untouched. The reply to start local work is a handoff,
not evidence that a Cursor process has started. The source dependency graph permits parallel development; the artifact manifest
forbids the new controller in R-B3.

The implementation branch must not merge or be cherry-picked into the frozen R-B3 cut. A future
Cursor source ACCEPT/draft PR does not lift that hold. Codex's documentation branch does not own
application implementation. Neither activity changes a production flag or dispatches a workflow.

## 3. Repository preflight completed

| Check | Fresh result / source of evidence |
|---|---|
| main baseline | Fetched origin/main = 6a171558a0f802eadd5d7ed5bf28545ca5c91905; no open PR at assignment time |
| G5 run | 33411410271 still completed/success, head f66d7ab6a4db1a327fd030ba9897bfc431104945 |
| G5 live proof | Existing reviewed record: actual Azure run, holdings-only seed, 9 passing tests, all three caller version markers; owner closed 5.7 on 2026-09-02 |
| Caller applicability | Zero diff from f66d7ab6 to frozen baseline across the shell helper, synthetic workflow, deploy-azure workflow, global setup and Azure API smoke caller |
| Inventory | Fresh scripts/check-b1-seed-version-callers.py result: exactly synthetic-shell, global-setup and azure-api-smoke |
| Guard tests | Fresh caller-guard self-tests: 9 passed |
| Accepted source CI | Run 33669373190 remains completed/success, event pull_request, accepted head 1bdb1d31c5f775983a78b892ea8fec4871ec1f41 |
| Source identity | Zero diff from accepted head to frozen candidate across portfolio-service, common-catalog, common-dto, common-observability, config and root Gradle inputs |
| Deployment safeguard tests | Fresh 90/90 across the seven existing suites listed below; no workflow dispatched |
| Wave 7 exclusion | Production source at the frozen cut has no CompositionController/public holdings PUT; the existing portfolio collection controller exposes reads/health, and reset remains its distinct internal endpoint |

The accepted source's earlier full reports remain 516 unit + 189 integration, zero failures/errors/
skips; those were not rerun for this documentation task. The accepted PR run actually executed Azure
image smoke and classified docs_only=false. These source/container-startup checks are not an R-B3
serving proof. G5's earlier caller proof also does not establish new endpoint behaviour in production.

Remote evidence:
- [G5 run](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
- [Accepted source CI](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33669373190)
- [Source merge #217](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/217)
- [Status reconciliation #215](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/215)

## 4. Proposed release scope, fixed before parallel implementation

**Candidate source cut:** 6a171558a0f802eadd5d7ed5bf28545ca5c91905.
**Service:** portfolio-service only.
**Runtime recipe:** existing portfolio-service/Dockerfile.azure.
**Deployment mechanism:** the existing prebuilt-digest path through deploy.yml; no generic main/tag
build, frontend rollout, gateway rollout, seed/verify follow-on job or schedule restoration.
**Candidate registry digest:** sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023 (approved build cu4).
Registry provenance is in §6.1; source CI remains distinct from tests against the packaged image.
**Workflow ref:** main, with expected_main_sha verified at the moment of an authorized dispatch.
It may differ from the candidate source SHA after documentation-only merges; record and review
that distinction rather than rebuilding the candidate from moving main.

Relative to the last recorded serving source cut 63fc0584ad307af7f50e9500f4911ac5999d6b76,
the candidate's portfolio-service changes are:
- the B1 Wave 6 strict version seed boundary, replacement delegation and initializer adaptation;
- B2 Task 8.1's additive updatedAt read projection in PortfolioResponse/PortfolioService;
- their tests and corresponding response-fixture adaptations.

The shared replacement primitive, demo-reset implementation, common modules, catalog/config and
root build inputs in the inspected path set have no additional delta from that serving cut.
B2 8.1 is an explicit additive inclusion; this is not a gateway/login-orchestration or frontend
release. Its existing source tests are in the accepted baseline. No public composition controller
is included. Before packaging, repeat the source fence on the exact detached candidate checkout;
Cursor's branch must never be used as a convenient build directory.

## 5. Approved read-only preflight and rollback proposal

Sanitized [metadata evidence](../evidence/b1-task-6-5/preflight-20260903.json) records the selected
Azure CLI/ARM GET fields and ACR results. Subscription “Azure subscription 1” was Enabled;
resource group is wealth-azure-prod-rg and registry is wealthprodacr.

| Observation | Result |
|---|---|
| Portfolio revision | portfolio-service--0000093; sole active revision; Healthy / Provisioned; ScaledToZero with 0 replicas |
| Portfolio image | wealthprodacr.azurecr.io/portfolio-service@sha256:9a1d55335b83b97967e434d374c7f5f5ca79ea2adccad8f8e518b674e9a39f47 |
| Traffic / ingress | Single revision mode; 100% traffic; internal ingress; allowInsecure=false; target port 8080 |
| Scale | maxReplicas=3; minReplicas returned null in both CLI and direct ARM GET. Do not rewrite null as an explicitly observed configuration value of 0; zero running replicas was observed separately |
| Startup controls | APP_DEMO_SEED_ON_STARTUP=false and APP_DEMO_TX_DIAGNOSTICS=false |
| Internal-key configuration | Portfolio and gateway each reference internal-api-key; no key value read |
| Gateway | api-gateway--0000077; sole active, Healthy / ScaledToZero, 100%; external ingress, insecure connections disabled |
| Other apps | market-data-service--0000079 and insight-service--0000079; each sole active, Healthy / ScaledToZero, 100%, internal ingress |
| Refresh job | Existing 9b2cf0d6 image tag; Schedule trigger 0 8 * * *; replicaRetryLimit=0; no execution or schedule change |
| ACR availability | Exact portfolio manifest exists, readEnabled=true; linux/amd64; 314499296 bytes |
| Registry provenance | Tag b2-task-4-5-63fc0584-20260901T144653Z; created 2026-09-01T14:58:15.7942093Z; successful build cu3 outputs that exact tag/digest |
| Candidate at this preflight | Source 6a171558 was unbuilt at 02:45–02:47 UTC; the later approved build result is in §6.1 |

The portfolio identity, digest, traffic and ingress agree with the
[B2 4.5 record](B2_TASK_4_5_DEMO_RESET_STOP_GO.md). Its reviewed pinned-checkout build record supplies
the source-SHA provenance; ACR's sourceTrigger field is null and does not independently attest that
SHA. Peer app image references remain tags in the returned templates; these are preserved as tag
references in the evidence, not promoted to attested running digests.

**Exact proposed rollback image:** the portfolio digest above, preserving the currently served B2
internal demo-reset and version-tolerant seed. It supersedes old R-B2 revision 0000081/digest
d544649f as the proposed compatibility target. Registry metadata confirms availability, not a new
pull/startup test. Re-read availability and serving state immediately before any approved build or
dispatch; reconcile drift instead of substituting another image.

Rollback would remove the candidate's strict seed and additive updatedAt response. The candidate
introduces no new migration, and this release includes no frontend or gateway consumer rollout.
Thus the proposal restores the currently served contract; it is not permission to roll back after
R-C activation, nor to remove an updatedAt dependency deployed by intervening work.

This was a control-plane/registry preflight only: no login, authenticated portfolio GET, database
read, seed, reset, build or deployment was executed. Application readiness, holdings tuples, price
tables, Spec A steady state and G2b are not re-proven by this snapshot.

## 6. Next operational handoff after preparation

The operator packet must state:
1. The exact candidate source and workflow commit, immutable build procedure, service selection,
   and proof that the Wave 7 controller is excluded.
2. Current serving snapshot and exact rollback image, with version-tolerant behaviour and B2
   compatibility accounted for.
3. Readiness checks after rollout: control-plane health alone is insufficient; B2 4.5 recorded
   that authenticated read readiness lagged behind it.
4. The separately authorized Task 6.6 controlled seed protocol for the compiled-in E2E identity:
   freeze one observed version, capture portfolio identity and complete before/after tuple and
   price-table evidence, call once, and report the exact expected no-op/transition/conflict outcome.
   No latest-version retry, demo-target substitution or restoration disguised as another seed.
5. Evidence for every serving digest/revision and traffic mapping. Mixed old/new replicas cannot
   satisfy G2b merely because one request reached the new image.
6. Task 6.7's acceptance/rollback decision and stop conditions. Wave 7 activation remains closed
   until its own predecessors and release gates are satisfied.

These are handoff requirements for later operational work, not an instruction to execute it now.
A harmless same-state seed can prove the specified no-op, but must not be described as live evidence
of a mutation or an intentional race. Local integration tests and live observations must be labelled
separately. No production fixture alteration is implied to force a desired probe outcome.

### 6.1 Approved candidate build — result and provenance

**Operator: Codex in this Task 6.5 task.** The owner approved the exact one-build request and
digest capture; no further operation was inferred. Cursor's review remains in the separate fork.

**Result: one build submitted, cu4 Succeeded.** The
[sanitized build evidence](../evidence/b1-task-6-5/candidate-build-20260903.json) records the run,
registry read-backs, source identity, local cut tag and unchanged serving revision.

| Binding | Verified value |
|---|---|
| Source checkout | C:/worktrees/wealthmgmtandportfoliotracker-codex-r-b3-candidate; detached and clean before and after submission |
| Source commit | 6a171558a0f802eadd5d7ed5bf28545ca5c91905 |
| Source tree | 4df697ed7605104a304ad08651e21522e32d52db |
| Local annotated cut tag | b1-r-b3-cut-6a171558-20260903T032527Z; object adc9df00788ff19418ec1e33da1590e3ddfafb57; resolves to the frozen commit; unpublished |
| Recipe / context | portfolio-service/Dockerfile.azure, clean repository root, linux/amd64; no secret/custom build argument |
| ACR build | cu4; created 2026-09-03T03:32:53.626947Z; started 03:32:53.977180Z; finished 03:35:20.004647Z; Succeeded |
| Image tag | b1-r-b3-6a171558-20260903T032527Z |
| Immutable manifest | sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023 |
| Manifest metadata | Created 2026-09-03T03:35:18.2892302Z; linux/amd64; 314499695 bytes; readEnabled=true |
| Cross-check | Run outputImages, lookup by approved tag, and lookup by immutable digest agree exactly; the manifest lists the approved tag |
| After the build | Sole portfolio revision 0000093 remains Healthy / ScaledToZero at 100% traffic on the prior 9a1d5533 digest |

Deployable image reference, recorded for later review:

~~~text
wealthprodacr.azurecr.io/portfolio-service@sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023
~~~

The pre-build drift check reconfirmed the clean source/tree, zero build-input difference from
accepted Wave 6 head 1bdb1d31, unused candidate tag, and prior portfolio revision/image at 100%
internal traffic with both startup flags false. The recorded rollback manifest remained readable.
No public CompositionController/holdings PUT mapping exists in this source; the strict seed and
shared replacement delegation are present. The earlier
[source-preparation record](../evidence/b1-task-6-5/candidate-source-20260903.json) intentionally
retains its pre-approval/unbuilt snapshot; this result supersedes it for build status.

The following approved command was submitted exactly once from the candidate checkout:

~~~powershell
az acr build --registry wealthprodacr --resource-group wealth-azure-prod-rg --subscription ee625b3f-7cb1-4482-be3c-4363c5d76d23 --image portfolio-service:b1-r-b3-6a171558-20260903T032527Z --file portfolio-service/Dockerfile.azure --platform linux/amd64 --no-wait --query "{runId:runId,status:status,platform:platform}" --output json --only-show-errors .
~~~

The CLI exited 0 without a run ID in stdout. Read-only run history identified the sole new run,
cu4; its eventual outputImages matched the approved tag and digest. No build retry or retag occurred.
A Windows-incompatible read-only discovery query failed locally; the corrected metadata query
succeeded without another submission. Tag lookup returns tag metadata, whereas digest lookup
returns manifest metadata; OS, architecture and image size above come from the digest lookup.

The immutable binding is the observed clean checkout/context → single ACR run → output image
tag/digest → independent registry lookup. A successful Dockerfile build is not a test run against
the packaged image: the existing recipe runs bootJar, not the future R-C verification graph.
The accepted source test counts in §3 were not rerun. No candidate automation or application change
was made, and the local cut tag alone does not check the aggregate AM.1/AM.2 tasks.

**Historical build stop boundary:** at the end of the one-build operation, no workflow dispatch,
deployment, application login/read, database read, seed or rollback had been executed. The old
image still served then; the later approved deployment/proof is recorded separately below.

The subsequent [Task 6.6 packet](B1_TASK_6_6_G2B_EXECUTION_PACKET.md) now specifies the secure
credential/execution channel, one-call E2E identity/version protocol, complete persisted tuple/price
capture, read readiness and conditional rollback scope. Its offline E2E reference accounts for the
identity-dependent cost basis. PortfolioResponse omits cost-basis fields and global price tables,
so HTTP holdings alone cannot supply that proof. Execution approval is now recorded in the
packet's §10. Section 11 corrects the credential-source assumption and records successful
read-only preflight plus the historical dispatch checkpoint. Section 12 and the
[completed report](B1_TASK_6_6_G2B_SERVING_PROOF.md) now record the successful deployment and
one-seed G2b proof, now accepted under the separately recorded owner R-B3 GO.

The later approved dispatch used deployment_mode=digest, services=portfolio-service, this exact
prebuilt_digest, and verified expected_main_sha 9c2ebc1233801253a3e54b6e930e28e1a00ebf3d. The source cut
stays frozen independently of that workflow commit. Production-environment approval and
non-cancelling concurrency remain. No second build or different digest is authorized.

## 7. Reproduction of completed local checks

~~~powershell
git diff --name-only f66d7ab6a4db1a327fd030ba9897bfc431104945 6a171558a0f802eadd5d7ed5bf28545ca5c91905 -- .github/workflows/scripts/seed-portfolio-with-version.sh .github/workflows/synthetic-monitoring.yml .github/workflows/deploy-azure.yml frontend/tests/e2e/global-setup.ts frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts
git diff --name-only 1bdb1d31c5f775983a78b892ea8fec4871ec1f41 6a171558a0f802eadd5d7ed5bf28545ca5c91905 -- portfolio-service common-catalog common-dto common-observability config build.gradle settings.gradle gradle.properties gradle
python -B scripts/check-b1-seed-version-callers.py
python -B scripts/tests/test_check_b1_seed_version_callers.py
~~~

Both diffs were empty. Deployment safeguard suites (each run locally, each successful):

| Script under scripts/tests | Tests |
|---|---:|
| test_resolve_deploy_selection.py | 9 |
| test_deploy_azure_service_allowlist.py | 9 |
| test_snapshot_container_apps.py | 11 |
| test_resolve_digest_deploy.py | 15 |
| test_deploy_azure_prebuilt_digest.py | 10 |
| test_validate_deploy_dispatch.py | 23 |
| test_deploy_pipeline_hardening.py | 13 |
| Total | 90 |

## 8. Recommendation and recorded status

**Technical recommendation: GO on 6.5's stated caller-readiness criterion.** G5 was owner-closed,
its historical run remains successful, the exact caller paths have no drift, and the current
inventory/guard tests pass. There is no remaining caller implementation prerequisite for 6.5.

**Owner decision: GO, 2026-09-03.** Task 6.5 is checked for its recorded decision. Tasks 6.6/6.7
were subsequently checked under the separate owner R-B3 GO; Tasks 7.1/7.2 remain unchanged. The approved read-only preflight is complete with the limitations in
§5. The separately approved one-build operation is complete: §6.1 records cu4 and the verified
immutable digest. Execution stopped at the approved boundary. The Task 6.6 packet and offline
E2E reference were then prepared, and the owner approved the exact execution bundle. Preliminary
checks passed; the shared .env.secrets supplied the existing credentials. Read-only preflight
passed. The owner then released the production gate; the one guarded cu4 deployment and one
same-state seed proof passed. Task 6.6 is technically ACCEPT in the completed report. No rollback
or schedule change occurred. The owner then approved Task 6.7 GO and the two local completion
ticks. Publication, Wave 7 activation and Writer_Convergence remain outside that approval.
