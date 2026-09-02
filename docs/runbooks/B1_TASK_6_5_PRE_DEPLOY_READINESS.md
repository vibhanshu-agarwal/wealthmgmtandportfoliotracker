# B1 Task 6.5 — Pre-deployment Readiness Record

**OWNER APPROVAL — remaining boundaries:** The owner authorized assigning parallel implementation
first, then kicking off 6.5. Cursor's local assignment was recorded in commit 01e0664 before this
preparation began. This record covers repository evidence and release planning. On 2026-09-03,
the owner authorized publication of these documentation changes and merge if CI is green.
That approval does not record Task 6.5's release GO or authorize Cursor's implementation merge.
Azure access, registry builds, production workflow dispatch, live seed and rollback execution
retain their own concrete authorization; none was performed here.

**Prepared:** 2026-09-03 by Codex, architecture/review owner.
**State:** preparation started; the stated G5 prerequisite is reverified and supports a technical GO
recommendation. Task 6.5 remains unchecked until the owner records its GO decision. This is not a
deployment-ready artifact attestation and does not close Tasks 6.6 or 6.7.

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
on a separate branch, based on main@6a171558a0f802eadd5d7ed5bf28545ca5c91905.
This is assigned/ready for handoff, not a claim that Cursor has started. Codex cannot launch Cursor
from this session. The source dependency graph permits parallel development; the artifact manifest
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

## 5. Runtime facts still require a separately authorized read-back

The [B2 4.5 serving record](B2_TASK_4_5_DEMO_RESET_STOP_GO.md) is the latest committed runtime
evidence available here, not a fresh Azure observation:
- serving revision: portfolio-service--0000093;
- source cut: 63fc0584ad307af7f50e9500f4911ac5999d6b76;
- digest: sha256:9a1d55335b83b97967e434d374c7f5f5ca79ea2adccad8f8e518b674e9a39f47;
- sole serving revision, 100% traffic, internal ingress at that proof.

Do not copy R-B2's older revision 0000081/digest d544649f into a rollback command mechanically:
B2 4.5 subsequently deployed a newer version-tolerant seed cut with the internal demo-reset endpoint.
The operating plan must reconcile 6.7's "restore version-tolerant R-B2 capability" with preservation
of already-served B2 functionality. The last known 0000093 digest is a proposed compatibility
baseline to verify, not an approved or freshly confirmed rollback target.

Before the release handoff can request production execution, collect a read-only Azure snapshot
under explicit owner authorization: active revision(s), immutable image(s), traffic, ingress,
health, scale and relevant config names/flags. Verify registry provenance and availability for
both candidate and rollback, and account for the additive updatedAt contract if rollback removes it.
Do not read or print secret values. If the serving state differs from this record, reconcile it
before choosing rollback. Never make an unreviewed substitution because a named digest is missing.

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

**Owner decision: not yet recorded.** No checkbox changed. Preparation does not imply permission
to build in ACR, dispatch, seed, roll back, expose the picker, restore synthetics, close G2b/R-B3,
or claim Writer_Convergence. The remaining work is the concrete operational handoff and its
authorized current-state checks, not further 7.1/7.2 work on this release cut.
