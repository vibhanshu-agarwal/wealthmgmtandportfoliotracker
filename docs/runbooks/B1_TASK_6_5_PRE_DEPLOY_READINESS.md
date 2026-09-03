# B1 Task 6.5 — Pre-deployment Readiness Record

**OWNER APPROVAL — next boundaries:** On 2026-09-03 the owner approved Task 6.5 GO and the
read-only Azure/ACR preflight: “Yes Task 6.5 GO and read-only preflight approved.” Both are recorded
below. The next proposed cloud action is one candidate build from the frozen source in §6.1;
approval would create its registry artifact, while withholding approval leaves it unbuilt.
Publication of this new documentation update also needs approval; PR #218's earlier approval was
fulfilled by its merge. No candidate build, deployment dispatch, live seed, rollback, implementation
publication/merge, exposure or schedule restoration is authorized by the present GO.

**Prepared:** 2026-09-03 by Codex, architecture/review owner.
**State:** Task 6.5 GO recorded by owner decision; read-only metadata preflight completed on
2026-09-03 at approximately 02:45–02:47 UTC. The existing portfolio revision/digest is confirmed.
The candidate remains unbuilt. This is not a deployment-ready artifact attestation and does not
close Tasks 6.6 or 6.7.

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
**Candidate registry digest:** not yet built or captured. Source CI is not a digest attestation.
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
| Candidate | Source remains 6a171558; no candidate registry build or digest yet |

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

### 6.1 Concrete next proposal — one candidate build, no deployment

Owner/operator roles: Codex owns this reviewed packet and its status record; the operator must be
named when the build is authorized. Cursor continues only its isolated 7.1–7.2 source assignment.

Proposed input and procedure:
1. Use a clean detached sibling checkout at full source SHA
   6a171558a0f802eadd5d7ed5bf28545ca5c91905, never moving main or Cursor's branch. Verify that the
   source is controller-free and that the build inputs match the accepted Wave 6 source recorded
   in §3. Record the source tree hash and a local artifact-cut tag under AM.1; publishing any tag
   is a separately approved GitHub action.
2. Reuse portfolio-service/Dockerfile.azure from that cut, root build context and linux/amd64.
   One ACR build creates a unique portfolio-service tag of the form
   b1-r-b3-6a171558-<UTC timestamp>. Do not substitute a tag or rebuild automatically on failure.
3. Capture build run ID/result, registry tag, manifest digest, creation time, platform and size.
   Require the run's output digest to equal the manifest behind that tag, and retain the source
   checkout evidence with it. A source timestamp or SERVICE_VERSION field is not image identity.
4. Stop after packaging. Return the exact immutable image reference for review. The existing
   Dockerfile builds bootJar; it does not implement the future R-C verification graph or prove
   that accepted source tests ran against this packaged image.

This proposal is limited to packaging the already-reviewed source. It neither implements Wave 7
candidate automation nor authorizes deploy.yml. A failed or ambiguous build stops without a
replacement build. The candidate digest stays unrecorded until an approved build actually succeeds.

The later dispatch packet must set deployment_mode=digest, services=portfolio-service,
prebuilt_digest to the reviewed immutable ACR reference, and expected_main_sha to the freshly
verified workflow commit on main. Current repository/workflow baseline is 9c2ebc12; it is distinct
from the candidate source. Production-environment approval and non-cancelling concurrency remain.

Before seeking deployment/Task 6.6 authorization, complete the probe packet with an approved
credential/execution channel, exact one-call E2E identity/version protocol, complete persisted
tuple/price evidence capture, and conditional rollback scope. PortfolioResponse omits cost-basis
fields and global price tables, so authenticated HTTP holdings alone cannot supply that full proof.
No database/secret access or fixture alteration is implied by this read-only metadata preflight.

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

**Owner decision: GO, 2026-09-03.** Task 6.5 is checked for the recorded decision; Tasks 6.6/6.7
and 7.1/7.2 remain unchecked. The approved read-only preflight is complete with the limitations in
§5. Next is the build-only decision in §6.1, followed by review of the exact artifact and a separate
deployment/proof packet. No build, dispatch, seed, rollback, exposure, schedule change, G2b/R-B3
closure or Writer_Convergence claim follows from this decision.
