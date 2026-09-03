# Asset Picker — E2E Master Plan to Production

**Last verified:** 2026-09-03

**OWNER APPROVAL — Task 6.6 execution recorded:** The owner approved the exact
[Task 6.6 bundle](../runbooks/B1_TASK_6_6_G2B_EXECUTION_PACKET.md#10-approved-preflight--credential-prerequisite)
with “Please proceed” on 2026-09-03. Secure preflight, one cu4 digest deployment, one fixed-E2E
seed and conditional pre-seed rollback are authorized subject to the packet's checks. All eight
required process variables are absent, so application/database preflight and deployment have not
started. Existing approval remains; secure credentials are the immediate prerequisite.
Publication of local records/tag and Task 6.7 closure remain separate owner decisions. Approval
would publish those records or decide R-B3; without it they remain local/R-B3 open. PR #218's
publication authorization is already fulfilled.

**Runtime baseline:** unchanged; the approved 2026-09-03 metadata read-back confirms the existing
portfolio revision 0000093/digest 9a1d5533 at 100% internal traffic. This is not new application or
G2b serving proof.


**Parallel source assignment (2026-09-03):** the owner requested implementation work be assigned
before Codex starts Task 6.5. **Cursor is assigned B1 Tasks 7.1–7.2**, the public composition
controller and HTTP tests, using the [bounded kickoff](../agent-instructions/CURSOR_KICKOFF_B1_WAVE_7_PUBLIC_COMPOSITION.md).
Status is **assigned / ready for handoff**, reconfirmed by Cursor; no execution evidence yet.
The permitted implementation start is origin/main@9c2ebc1233801253a3e54b6e930e28e1a00ebf3d;
its only delta from the original main@6a171558 baseline is PR #218's four documentation files. The dependency graph explicitly allows this
source work alongside Wave 6 release preparation. The implementation stays on Cursor's branch:
the Wave 7 controller must not enter R-B3's source/image, and no merge or exposure is authorized.
PR #218 published that assignment and readiness preparation; its authorization is fulfilled.
Cursor's existing local implementation/test authorization stands. Preserve its prior branch and
untracked files; implementation publication/merge and production operations retain their gates.
The earlier owner decision changed only Task 6.5's checkbox. The subsequent build and Task 6.6
packet preparation introduce no further completion ticks.


**Task 6.5 GO — owner decision recorded 2026-09-03:** the owner approved GO and read-only preflight
following the existing technical recommendation. The
[readiness record](../runbooks/B1_TASK_6_5_PRE_DEPLOY_READINESS.md) and
[sanitized metadata](../evidence/b1-task-6-5/preflight-20260903.json) confirm the existing portfolio
revision/digest, internal traffic and disabled startup/diagnostic flags; ACR build cu3 and manifest
metadata agree with the reviewed B2 4.5 provenance. That digest is the proposed compatibility
rollback target, not an authorized rollback. The separately approved single build cu4 succeeded
from frozen source 6a171558, producing candidate digest 2be727ea…; the run output and registry
tag/manifest read-backs agree. [Build evidence](../evidence/b1-task-6-5/candidate-build-20260903.json)
records the exact digest and local cut tag. The prior 0000093/9a1d5533 image remains active at
100% traffic. Execution stopped after digest capture; deployment/6.6 proof stays separate. Tasks
6.6/6.7 remain unchecked; no application probe was replayed.

**Task 6.6 preparation — 2026-09-03:** The [execution packet](../runbooks/B1_TASK_6_6_G2B_EXECUTION_PACKET.md)
and [offline E2E reference](../evidence/b1-task-6-6/e2e-golden-reference-6a171558.json) are ready for
review. The reference covers all ACTIVE entries using the E2E identity, not the existing demo
oracle's identity. The packet specifies complete read-only SQL snapshots, application readiness,
one frozen-version seed, exact digest deployment and conditional rollback before seed transmission.
The owner subsequently approved the bundle. Preliminary metadata/source checks passed, but all
eight required process variables were absent. Application/database preflight, deployment and seed
have not begun. The [preflight result](../evidence/b1-task-6-6/approved-preflight-20260903.json)
records this prerequisite; it is not G2b or R-B3 evidence.

**B1 Wave 6 source completion (verified 2026-09-03):** Tasks **6.1–6.4 are source-complete**
after owner-approved [PR #217](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/217)
merged at `main@d66bb23d5ef3606373c15d9ee02fda27c62df5c2` on `2026-09-02T19:28:09Z`.
Codex ACCEPT applies to reviewed head `1bdb1d31c5f775983a78b892ea8fec4871ec1f41`; R1/R2 were
closed by test fix `b1d33171` and governed close-out `1bdb1d31`. The merge parents are
`1f3eaf5834cb1a5e0c065d9c4d316100bdea837d` and that reviewed head. The only tree difference
from the accepted head is the 17-line `AGENTS.md` update already merged through PR #216;
application source and tests are identical. This reconciliation changes no runtime baseline.

The [accepted-head PR-event run](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33669373190)
completed successfully: 15 successful checks, one neutral Qodana alias, `ci-required=success`,
and explicit `docs_only=false` (12 of 14 paths outside the documentation allowlist).
Azure image smoke actually executed the blank/nonblank key and replica-token cases. Inspected
local reports contained 516 unit and 189 integration tests, zero failures/errors/skips, including
5 collision tests; the boot jar was 97,882,843 bytes. The caller inventory remains exactly three;
9 caller-guard and 33 governance self-tests passed.

The seed requires the caller's strict version and fixed E2E target, delegates once to the shared
replacement transaction, and preserves identity and complete no-op semantics. Both forced races
compare the full winning tuple and require exactly two attempts. The absent-creation loser is
asserted unresolved by user before real post-rollback advice reports the committed version.
The initializer forwards its own observation; global-price snapshot/sentinel coverage is retained.

Tasks 6.1–6.4 are checked for merged source completion. **Task 6.5 is GO by owner decision on
2026-09-03; Tasks 6.6/6.7 remain unchecked.** Read-only metadata preflight is recorded in the
[6.5 readiness record](../runbooks/B1_TASK_6_5_PRE_DEPLOY_READINESS.md). The separately approved candidate build is recorded in §6.1 of that record. Deployment,
G2b/R-B3 serving proof, Wave 7 activation and Writer_Convergence remain open. No live seed, schedule restoration, B2 gate
decision or feature exposure follows from the 6.5 decision.

**Post-merge verification:** [PR #212](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/212) merged at
`main@d8fa499de05fa1370a0271c4822230a6ea113695` on 2026-09-02 at 10:03:53Z.
Its parents are the reviewed base `a2c402db` and final head `01917e16`; the merged tree is
identical to that head. B2 Tasks 5.1, 5.2, 5.3, 5.3a, 5.4, and 5.5 are source-complete.
Task 5.6's seven technical conditions are met; its separate owner GO decision remains pending.
This reconciliation does not advance a runtime baseline or claim a new production read-back.

**B2 source completion:** [PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214)
merged at `main@48d0aba8468325b91e1bf9b84bd43cbeaacdf74a` on 2026-09-02 at 15:53:57Z.
The merged tree is identical to final head `b918ff09`, whose CI is green. B2 Wave 6 Tasks
6.1/6.2 are source-complete and checked; source/visual ACCEPT at `970b637b` and R1–R4 closure
carry forward. Committed flags remain disabled, final placement and Task 6.3 remain open,
and the owner-deferred [sidebar backlog](../todos/backlog/responsive-dashboard-sidebar/README.md)
remains separate. No fresh runtime attestation is made.

**Owner G5 close-out (2026-09-02):** “Please do the G5 close out.” B1 Task 5.7 / G5 is
complete using the reviewed three-caller evidence below. This satisfies B1 Wave 6's G5
prerequisite. Tasks 6.1–6.4 subsequently merged through PR #217, and Task 6.5 has its separate
2026-09-03 owner GO. R-B3 deployment, Wave 7 activation and B2 Tasks 5.6/6.3 remain open.

**Program-state code baselines (runtime):** the cross-program baseline remains
`main@e221662b6c891639a56894289e150ee01fb537f6`; B2 Wave 4 now serves the deliberately pinned
historical portfolio-service cut `63fc0584ad307af7f50e9500f4911ac5999d6b76`, deployed by the
current workflow at `main@67e55cf2c3b90d60149a79b084686d348ab9ba5e`. Process-control and
documentation merges do not themselves advance runtime behavior.
B1 R-A additionally serves Wave 2 gateway provisioning at revision `api-gateway--0000076` /
digest `sha256:2da5b303…` (image tag `18693d2…`). B1 R-B applied Artifact 2 / V20 (cut `25aa730`).
B1 R-B2 additionally serves Artifact 2a `portfolio-service` at revision
`portfolio-service--0000081` / digest `sha256:d544649f…` (cut `f22e2ff`); that deploy serves the
version-bearing read and read-only asset catalog without caller migration, public `PUT`, or Spec A
fence changes. It has now been superseded for portfolio-service traffic by B2 Task 4.5 revision
`portfolio-service--0000093` / digest `sha256:9a1d5533…`, which retains that B1 contract and adds
only the pinned B2 Wave 4 internal demo-reset cut.

**Historical Task 4.5 repository evidence baseline:**
`main@67e55cf2c3b90d60149a79b084686d348ab9ba5e` (merged Task 4.5 operator kickoff), independent of
the mixed runtime cuts above. Task 5.1a source merged via PR #202 at
`main@64761dc2e58bb2249089f2af5b1dee3e06a3dc4a`; Task 5.1b source merged via PR #208 at the
`main@f954b5a7aa7b490e32b8a2e8a99a1e9397888c2a`; Task 8.2a source merged via PR #203 at
`main@addd8049aa082bdfbd7e5bf19c6840e531a9cfb4` — none of those later standalone tasks is deployed;
the pinned Spec A
9.14 plan-evidence baseline is `main@66bbee0bf438706146ac9975bf5f0c923b3d43cb`. A docs-only
audit changes the living record, not the runtime baseline.

**Program state:** Spec A checkpoints 9.1–9.13 are operationally complete. Checkpoint 9.11 persisted
`MARKET_DATA_JOB_RUNNER_ENABLED=true` through Terraform apply on `main@e7fad7cb` (source PR #164;
evidence [`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md)).
Checkpoint 9.12 source merged via PRs #167, #169, #170, and #172; the first authorized enable apply ran but failed
to converge (startup transaction PostgreSQL read-only) and was rolled back. Provenance, connection-origin,
and statement-history probes remain on the record. The authorized 2026-08-30 retry at
`main@d29f67083109086de4ed00d38589267609e24265` succeeded: enable apply created
`portfolio-service--0000090` (159 holdings, one seed event); restoring apply created
`portfolio-service--0000091` with both flags `false`. Operational checkpoint verdict: PASS.
Historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` — evidence
[`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md).
Checkpoint 9.13 completed on `portfolio-service--0000092`, `market-data-service--0000079`, and
`insight-service--0000079` ([`SPEC_A_9_13_SCALE_RESTORE.md`](../runbooks/SPEC_A_9_13_SCALE_RESTORE.md)).
The portfolio-service revision was later superseded by the scoped B2 Task 4.5 digest deployment;
the other two revisions remain unchanged.
Checkpoint 9.14 source merged via PR #184 at `main@66bbee0`; the authorized read-only remote-plan
[33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724)
passed the exact-scope 9.14 guard, while the apply job was skipped. Senior plan review returned
**ACCEPT** on 2026-08-31 against acceptance ids A1-A4, B1-B7, and C1-C3, with no apply blocker;
acceptance authorizes no write. Checkpoint 9.14 is now **complete**: apply
[33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)
enabled ACA external ingress on the existing `api-gateway--0000077` revision, insecure connections
remain disabled, and the default ACA endpoint is healthy
([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)). B1 Wave 2 /
R-A, Wave 3 / R-B (V20), and Wave 5 Tasks 5.2–5.3 / R-B2 (G2a) are complete; caller migration Tasks
**5.4–5.6 merged on `main@0b5d60d1`** (PR #161, caller migration); **G5/5.7 is complete**
by the owner's 2026-09-02 close-out decision. Authorized public Azure synthetic
[33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) from
`main@f66d7ab6a4db1a327fd030ba9897bfc431104945` succeeded with all three `[b1-g5]` markers,
a holdings-only seed, and 9 passing tests. PR #197 merged the reviewed evidence at
`main@b6c0da3`; the present owner decision closes the gate. At close-out, caller/helper/workflow
wiring and focused tests have no source drift through `main@48d0aba8`, and the inventory guard
still passes with exactly three callers. Unattended synthetics remain suspended; further
manual dispatch and schedule restoration require separate authorization. See the
[G5 decision and evidence](../runbooks/B1_G5_INGRESS_BLOCKER.md). B1 Wave 6's prerequisite is
satisfied; no R-B3 deployment or public `PUT` activation is authorized here.
B2 Wave 1 (Tasks 1.1-1.19) and Wave 2 Tasks 2.1-2.5 are merged source-only through PR #178 at `main@38e3d95`; they remain entirely mock-backed and disabled by default. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209`; Task 3.7 deploy/live proof remains open (not deployed, not activated, not live-probed). Wave 4 Tasks 4.1–4.4a merged via PR #180 at `main@63fc058`; that exact cut is now deployed only to the internal portfolio-service endpoint, and Task 4.5 completed with a reviewed live GO on `portfolio-service--0000093` / `sha256:9a1d5533…` ([evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md)). Tasks 5.1a and 5.1b merged source-only via PRs #202 and #208 at `main@64761dc2` and `main@f954b5a7`; Wave 8 Tasks 8.1 and 8.2a merged source-only via PRs #185 and #203 at `main@198c878d` and `main@addd8049`. Those later standalone tasks are not deployed. Tasks 2.6–2.7, 3.7, Task 5.6's owner decision, remaining Wave 8 work, Wave 6 Task 6.3, and Waves 9 and 10 remain open; Wave 7 is informational. Wave 6 Tasks 6.1/6.2 merged source-only via PR #214 at `main@48d0aba8`. Wave 5 Tasks 5.1, 5.2, 5.3, 5.3a, 5.4, and 5.5 merged source-only via PR #212 at `main@d8fa499d`; they are not deployed.

**User-visible state:** there is no functional Asset Picker in the application today.

**Handoff state:** Spec A 9.12 is **operationally complete**, and Spec A 9.13's scale restoration
remains effective. B2 Task 4.5 is now **GO** on `portfolio-service--0000093` /
`sha256:9a1d5533…`; the owner-authorized one-call probe returned a correct already-golden no-op with
159 holdings and version `0`. `market-data-service--0000079` and `insight-service--0000079` are
unchanged. Both demo/diagnostic flags remain `false`, and historical setter
attribution remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`. The guarded 9.14 source and its pinned
read-only remote-plan are green, senior plan review returned **ACCEPT** on 2026-08-31 with no apply
blocker, and the authorized apply completed. Spec A's production cutover checkpoints 9.1–9.14 are
now all complete. Gateway ingress is open on both the default ACA endpoint and the restored
`api.vibhanshu-ai-portfolio.dev` custom domain; the three catalog consumers remain at
`min_replicas=0`. B1 G5 closed by owner decision on 2026-09-02 using the successful reviewed
three-caller run. Wave 5's Wave 4 prerequisite is satisfied; its source bundle merged via PR #212
at `main@d8fa499d` after Codex ACCEPT and final-head CI success. Task 5.6's seven technical
conditions are met; its owner GO remains pending. Wave 6 frontend Tasks 6.1/6.2 merged via
PR #214 at `main@48d0aba8`, identical to CI-green final head `b918ff09`. The flags remain
disabled in source; final placement, backend readiness, and exposure remain open. No new
production read-back or deployment operation is part of this close-out.

This is the living, human-facing status document for the Asset Picker program. It is not a
historical snapshot. Detailed requirements, designs, task mechanics, and operational evidence live
in the linked owning documents; this file summarizes their current state, dependencies, blockers,
active work, and next decisions.

## 0. Status governance

### 0.1 Authority

- This master plan owns cross-spec status, dependency ordering, active work, blockers, and the next
  decision requiring owner authorization.
- Each spec's `requirements.md` owns behavior, `design.md` owns architecture, and `tasks.md` owns
  detailed implementation and verification mechanics.
- Runbooks own durable operational evidence. They are linked from task records rather than copied
  here.
- Git history preserves chronology. Historical status paragraphs do not remain in the living view
  after they become false.

### 0.2 Required update rule

Every pull request must include **exactly one** canonical declaration in the PR body:

1. `Master-plan impact: updated — <tracks>` where `<tracks>` is a comma-separated list from
   `{Spec A, B1, B2, process}`; or
2. `Master-plan impact: none: <same-line rationale>` explaining why program status, dependencies,
   blockers, and next actions are unchanged.

For `updated`, the same change must update this master plan and every owning `tasks.md` ledger for
the declared Spec A/B1/B2 tracks (`process` has no ledger). Declared tracks must also cover every
Spec A/B1/B2 specification directory the PR touches; `process` cannot substitute for an inferred
spec track. For `none`, the rationale must be on the same line, must not be an HTML
placeholder/checklist/stub, and must not accompany edits to this master plan or an owning ledger
(that is a conflict — use `updated` instead).

A checkbox is marked complete only when its owning acceptance evidence exists. Work implemented on
an unmerged branch is described as **implemented but unmerged**, never as complete on `main`.

**Process-control enforcement:**

- Contract tests run in required `static-guard` (`.github/workflows/ci-verification.yml`).
- The live PR-body check runs in the dedicated lightweight workflow
  `.github/workflows/master-plan-status-propagation.yml` on `opened` / `synchronize` /
  `reopened` / `edited`, so body edits are revalidated without folding `edited` into the heavy CI
  chain. Script: `scripts/check_master_plan_status_propagation.py`.
- When those paths exist in the revision being read, the guard is part of that revision's process
  controls. Runtime/application Asset Picker capability is unaffected.
- PRs #198 and #199 added the fail-closed docs-only CI fast path. The `changes` classifier uses a
  skip allowlist, and the required `ci-required` aggregate accepts only the exact job-result shape
  declared by that classifier. Probe PR #200 completed the docs-only path in 67 seconds with the
  original four expensive jobs skipped; PR #199 separately proved the full-suite path. PR #202
  added `azure-image-smoke-test` to the aggregate dependency graph, and docs-only PR #204 proved the
  current five-job skip shape. This is process control only and does not advance any Asset Picker
  task or runtime baseline.

### 0.3 Update checklist

At every meaningful merge or live checkpoint:

- update `Last verified` and reassess the program-state code baseline, advancing that runtime
  baseline only when runtime/application behavior or operational evidence changes;
- update the program snapshot and affected track row;
- update the affected current-status and handoff tables;
- update blockers and the next authorization boundary;
- update the owning `tasks.md` evidence;
- remove or rewrite statements that have become false; and
- keep secrets and raw operational artifacts out of tracked documentation.

## 1. Executive program snapshot

| Track | Delivered | Current position | Remaining outcome |
|---|---|---|---|
| **A — Spec A catalog/data cutover** | Shared catalog, Postgres/Mongo repair, R4 rollout, enforcement, one reconciled controlled refresh, persisted refresh enablement, demo portfolio activation, and scale-to-zero restoration | **All 14 cutover checkpoints complete.** 9.13 completed on `portfolio-service--0000092`, `market-data-service--0000079`, and `insight-service--0000079`; B2 Task 4.5 later superseded only the portfolio revision with `portfolio-service--0000093`. 9.14 completed via apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603), reopening ACA external ingress on `api-gateway--0000077` with `allowInsecure=false` ([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)); the later custom-domain restore has independent `200` read-back, and PR #194 independently reviewed and merged that evidence ([`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)); historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` | Spec A's production cutover is done. B1 G5 closed by owner decision on 2026-09-02 using the reviewed three-caller run. B1 Wave 6 Tasks 6.1–6.4 are source-complete through PR #217; the four filed process follow-ups remain open |
| **B — B1 portfolio composition backend** | Deployment prerequisites, fixture identity migration, legacy writer retirement, gateway provisioning, V20, and version-bearing read | **R-A/G2, R-B/G3, and R-B2/G2a complete**; Wave 4 Tasks 4.1–4.21 merged on `main@2673f40` (PR #153; mechanisms unexposed); Task 5.1 merged on `main@f22e2ff` (PR #155); caller Tasks 5.4–5.6 merged on `main@0b5d60d1` (PR #161); **5.7/G5 complete by owner decision on 2026-09-02**, with all three caller markers in [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) and reviewed evidence PR #197 | B1 Tasks 6.1–6.4 merged via PR #217 at `main@d66bb23d`; 6.5 owner GO and read-only preflight are recorded; candidate cu4 is packaged; G2b/R-B3 and Wave 7 remain gated; no Writer_Convergence yet |
| **C — B2 Asset Picker product** | Requirements, design, task plan, five-screen visual mockup, Wave 1 / partial Wave 2 frontend source, Wave 3 presence source, Wave 4 demo-reset source and live internal proof, and additional source Tasks 5.1a, 5.1b, 8.1, and 8.2a | Wave 1 (1.1-1.19) + Wave 2 Tasks 2.1-2.5 merged source-only on `main@38e3d95` via PR #178; Wave 3 Tasks 3.1–3.6 merged source-only on `main@cc97a209` via PR #179; Wave 4 Tasks 4.1–4.4a merged via PR #180 at `main@63fc058`, and Task 4.5 is live-GO on `portfolio-service--0000093` / `sha256:9a1d5533…`; Tasks 5.1a and 5.1b merged via PRs #202 and #208 at `main@64761dc2` and `main@f954b5a7`; Tasks 8.1 and 8.2a merged via PRs #185 and #203 at `main@198c878d` and `main@addd8049`. The later standalone tasks remain undeployed | Wave 5 source tasks merged via PR #212 / `main@d8fa499d`; Task 5.6 technical conditions met, owner GO pending; Wave 6 Tasks 6.1/6.2 merged via PR #214 / `main@48d0aba8`; Task 6.3, Task 3.7, Wave 8, live integration, and exposure remain open |
| **D — Demo credibility** | Canonical prices refreshed and reconciled; demo initializer exists; authorized 9.12 retry activated the Active_Asset set | Demo portfolio holds the exact 159-holding golden set after Task 4.5's one-call live proof on `portfolio-service--0000093`; version remained `0` under the valid same-state no-op; both flags remain `false`; historical pooled-session setter remains unidentified | 9.14 and the custom-domain restore are complete; B1 G5 closed by owner decision on 2026-09-02 using run 33411410271. Operational 9.12 success does not close historical RCA |

### What is actually usable today

| Capability | Status |
|---|---|
| Canonical Active Asset catalog inside services | ✅ Shipped |
| Repaired and reconciled price data | ✅ Shipped and verified |
| Enforcement against unsupported holdings/events | ✅ Enabled |
| `GET /api/assets` serving catalog data | ✅ Wave 2 gateway `/api/assets/**` route served with R-A; Wave 4b controller served with R-B2 Artifact 2a (`portfolio-service--0000081`) |
| Version-bearing portfolio read | ✅ G2a/R-B2 originally green on `portfolio-service--0000081` / `sha256:d544649f…`; contract retained on the recorded B2 Task 4.5 revision `portfolio-service--0000093`; caller migration 5.4–5.6 on `main@0b5d60d1`; **5.7/G5 complete by owner decision on 2026-09-02**, using the three-caller [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) and reviewed evidence PR #197 |
| `PUT /api/portfolio/holdings` safe composition write | ❌ Not implemented |
| Asset Picker button/modal/browse/review/conflict UI | 🟡 Source merged behind a disabled-by-default flag; mock-backed only, not deployed/live |
| Asset Picker full-stack E2E proof | ❌ Not implemented |
| Asset Picker exposed to production users | ❌ Not implemented |

The recent flakiness fixes were incidental blockers. The main delivered work was production data,
catalog, enforcement, and deployment safety. That foundation is necessary, but it is not the
user-facing picker.

## 2. Track A — Spec A catalog/data foundation

Authority: [`.kiro/specs/supported-asset-integrity/tasks.md`](../../.kiro/specs/supported-asset-integrity/tasks.md)

| Checkpoint | Status | Durable outcome |
|---|---|---|
| 9.1–9.5 | ✅ Complete | R1/R2 deployed, refresh fenced, Kafka drained, writes/ingress quiesced |
| 9.6 | ✅ Complete | V17–V19 Postgres repair applied; integrity assertion and repair audit verified |
| 9.7 | ✅ Complete | Mongo repair Job completed and verified |
| 9.8 | ✅ Complete | R4 deployed with catalog identity confirmed; actual chronology recorded |
| 9.9 | ✅ Complete | Catalog enforcement enabled; three services held at `min_replicas=1` |
| 9.10 | ✅ Complete | One controlled refresh succeeded and was reconciled across Kafka, Mongo, and Postgres |
| 9.11 | ✅ Complete | Persisted `MARKET_DATA_JOB_RUNNER_ENABLED=true` via Terraform; live read-back and standard no-op plan green ([`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md)) |
| 9.12 | ✅ Operationally complete on `portfolio-service--0000091` | Authorized retry at `main@d29f670`: enable [33295859015](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33295859015) (`0000090`, 159 holdings, one seed event); restoring [33296204759](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33296204759) (`0000091`, both flags `false`); Neon tuple MD5 `6e436f24fa2b31d14aff77fe5d1a05c9`; historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md)) |
| 9.13 | ✅ Complete | Guarded `spec-a-9.13-restore-scale` restored `min_replicas=0` on the three catalog consumers; completion-time revisions `portfolio-service--0000092`, `market-data-service--0000079`, `insight-service--0000079`; B2 Task 4.5 later superseded only the portfolio revision; evidence [`SPEC_A_9_13_SCALE_RESTORE.md`](../runbooks/SPEC_A_9_13_SCALE_RESTORE.md) |
| 9.14 | ✅ Complete | PR #184 merged the guarded `spec-a-9.14-reopen-ingress` / `spec-a-9.14-close-ingress` profiles at `main@66bbee0`; read-only remote-plan [33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724) passed the exact-scope guard and skipped apply; senior review returned **ACCEPT** on 2026-08-31 (A1-A4, B1-B7, C1-C3; no apply blocker); authorized apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603) at `main@743c9b97` enabled external ingress on the existing `api-gateway--0000077` revision with `allowInsecure=false` and a healthy default ACA endpoint ([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)). That checkpoint alone did not unblock B1 G5; the later custom-domain recovery and successful synthetic removed the network blocker, and Task 5.7 closed under the separate owner decision on 2026-09-02 |

Additional unfinished Spec A implementation task: **8.8**, replacing remaining hard-coded
catalog-size assertions. Tasks 8.1–8.7, including the aggregate `assetPriceFreshness` contract,
are complete.

### Current production safety boundary

- Persisted refresh runner: `true` (checkpoint 9.11 complete; scheduled Job may run at `0 8 * * *`).
- Refresh retry limit: `0`.
- Gateway ingress: **open** on live `api-gateway--0000077` after checkpoint 9.14, with
  `allowInsecure=false`, `targetPort=8080`, `transport=Auto`, and a single 100% `latestRevision`
  traffic weight. No new revision was cut. Reversal profile `spec-a-9.14-close-ingress` is now
  usable.
- Custom domain: **restored.** `api.vibhanshu-ai-portfolio.dev` has the exact `SniEnabled` binding
  to the existing succeeded managed certificate. The guarded apply/bind workflow's immediate default
  health observation was non-`200`, but independent read-back then found both default and custom health
  endpoints at `200`. See [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md).
  Restoration alone did not close G5. The later three-caller synthetic [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
  succeeded, its evidence merged via PR #197, and the owner closed Task 5.7 on 2026-09-02.
  Unattended synthetics remain suspended in `synthetic-monitoring.yml`; the process backlog item
  [`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md)
  remains open under its own acceptance criteria.
- `SERVICE_VERSION` drift: `api-gateway` and `portfolio-service` advertise a `SERVICE_VERSION`
  that is not the image they run. Pre-existing, untouched by 9.14 — backlog item
  [`service-version-image-drift`](../todos/backlog/service-version-image-drift/README.md).
- `portfolio-service`, `market-data-service`, and `insight-service`: enforcement enabled,
  `min_replicas=0` after checkpoint 9.13.
- Controlled refresh: exactly one authorized one-off execution completed at 9.10; 9.11 did not start
  an additional execution.
- Demo portfolio activation: operationally complete on `portfolio-service--0000091`; production
  gate `APP_DEMO_SEED_ON_STARTUP` is `false`; demo holds 159 Active_Asset holdings; diagnostics
  flag also `false`.
- Checkpoint 9.12 is operationally complete; historical RCA remains
  `MECHANISM_REPRODUCED_SETTER_UNPROVEN`. Checkpoints 9.13 and 9.14 are live-green
  ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md)).
- **B1 G5 / Task 5.7 complete** — owner decision recorded 2026-09-02; authorized three-caller
  run [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
  succeeded from `main@f66d7ab6` with all three markers, and PR #197 merged the reviewed evidence.
  Unattended synthetics remain suspended; no additional live operation follows from this decision.

Checkpoint 9.10 evidence:
[`docs/runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md`](../runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md).

Checkpoint 9.11 evidence:
[`docs/runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md).

Checkpoint 9.12 RCA evidence (operationally complete; historical setter unproven):
[`docs/runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md).

## 3. Track B — B1 portfolio composition backend

Authority: [`.kiro/specs/portfolio-composition-contract/tasks.md`](../../.kiro/specs/portfolio-composition-contract/tasks.md)

| Wave | Status | Meaning |
|---|---|---|
| P — deployment prerequisites | ✅ Complete | Scoped service deployment and immutable portfolio digest path live |
| 0 — fixture identity migration | ✅ Complete | E2E fixture paths moved to the correct identity |
| 1 — legacy writer retirement | ✅ Complete | Old portfolio creator and versionless holding writer removed and kept retired |
| 2 — gateway provisioning + asset route | ✅ R-A complete (G2 served) | PR #131 tasks 2.1–2.6 complete; serving revision `api-gateway--0000076`, digest `sha256:2da5b303…`; evidence [`B1_R_A_G2_SERVING_PROOF.md`](../runbooks/B1_R_A_G2_SERVING_PROOF.md) |
| 3 – V20 schema | ✅ R-B complete (G3 served) | Tasks 3.1–3.7 complete; Artifact 2 cut `25aa730` applied V20; prior serving evidence [`B1_R_B_G3_SERVING_PROOF.md`](../runbooks/B1_R_B_G3_SERVING_PROOF.md); superseded for portfolio traffic by R-B2 |
| 4 – contract implementation | Source on Artifact 2a serving cut; mechanisms unexposed | Wave 4a–4c (4.1–4.21) merged on `main@2673f40` (PR #153) and included in Artifact 2a serving digest. Public `PUT` still Wave 7. Replacement orchestrator + preparers remain unexposed; `GET /api/assets` controller is now served with R-B2; candidate packaging (7.5/R-C) still pending |
| 5 — version-bearing read | ✅ Tasks 5.1–5.3 / R-B2 complete; 5.4–5.6 merged via PR #161 at `main@0b5d60d1`; **5.7/G5 complete by owner decision on 2026-09-02** | [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) from `main@f66d7ab6` executed all three callers with version markers, holdings-only seed, and 9 passing tests; evidence reviewed/merged via PR #197. Historical failures and close-out: [G5 record](../runbooks/B1_G5_INGRESS_BLOCKER.md) |
| 6 — version-required seed | 🟡 Tasks 6.1–6.4 source-complete via PR #217 / `main@d66bb23d`; Codex ACCEPT, R1/R2 closed | Strict version boundary and identity-preserving replacement are on main. 6.5 owner GO recorded; read-only preflight confirms the existing cut. Approved candidate build cu4 succeeded; Tasks 6.6/6.7 remain open; no deployment |
| 7 — activation | 🟡 Tasks 7.1–7.2 assigned to Cursor, handoff ready; implementation not yet reported | Isolated controller + HTTP tests may proceed alongside 6.5; excluded from R-B3 source. Candidate packaging, merge and activation remain gated |

Spec A V17–V19 were applied at checkpoint 9.6; **V20 is applied under R-B** and unchanged by R-B2.
**R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete. Wave 4 composition write mechanisms
remain unexposed; public `PUT` and candidate packaging (7.5/R-C) remain incomplete. Caller migration
source **Tasks 5.4–5.6 are on `main@0b5d60d1`** (PR #161); **5.7/G5 is complete** under the
owner's 2026-09-02 decision. Run [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
from `main@f66d7ab6` recorded all three callers; PR #197 merged the reviewed evidence at
`main@b6c0da3`. No caller/helper/workflow/test drift exists through `main@48d0aba8`, and the
inventory guard passes. Unattended synthetics remain suspended. B1 Wave 6 source is complete and
6.5 has owner GO and the approved candidate build cu4 succeeded; R-B3 deployment/serving proof and Wave 7 activation remain separate gates. The seed is still
version-tolerant, so Writer_Convergence is not claimed. **Do not treat a current-`main`
portfolio deploy as a substitute for an authorized Artifact cut.**

### Spec A checkpoint record

| Item | Current state | Required before relying on it |
|---|---|---|
| Checkpoint 9.11 | **Complete** — apply run [33091163222](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33091163222); live runner `true`; standard no-op [33093260896](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33093260896); evidence [`SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md) | 9.12 operationally complete; 9.13 live-green |
| 9.12 enable / rollback / retry | **Operationally complete** — first enable failed and rolled back; authorized 2026-08-30 retry succeeded on `portfolio-service--0000090`/`0000091`; historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` ([`SPEC_A_9_12_POOLED_READONLY_RCA.md`](../runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md)) | Do not treat retry success as named-setter attribution; keep `pg_stat_statements` installation separately gated |
| 9.13 restore scale | **Complete** — remote-plan [33306477527](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33306477527); apply [33306874697](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33306874697); completion-time revisions `portfolio-service--0000092`, `market-data-service--0000079`, `insight-service--0000079` ([`SPEC_A_9_13_SCALE_RESTORE.md`](../runbooks/SPEC_A_9_13_SCALE_RESTORE.md)); B2 Task 4.5 later superseded only the portfolio revision | Superseded by 9.14 for gateway ingress; scale policy remains in effect |
| 9.14 reopen ingress | **Complete** — PR #184 / `main@66bbee0`; guarded read-only plan [33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724) passed; apply skipped; reviewer orientation merged via [PR #187](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/187) | Plan review **ACCEPTed** 2026-08-31; apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603) passed the `production` gate and all twelve assertions. Live: external ingress on `api-gateway--0000077`, `allowInsecure=false`, default ACA endpoint healthy. The later custom-domain recovery and synthetic succeeded; Task 5.7 closed under its own owner decision on 2026-09-02. Four process follow-ups remain in `docs/todos/backlog/` |

### B1 delivery record

| Item | Current state | Required before relying on it |
|---|---|---|
| PR #131 / R-A serving | **Complete** — Wave 2 tasks 2.1–2.6; G2 green on `api-gateway--0000076` / `sha256:2da5b303…` ([run 32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627); [`B1_R_A_G2_SERVING_PROOF.md`](../runbooks/B1_R_A_G2_SERVING_PROOF.md)) | Signup provisioning remains live on the serving gateway revision |
| `cursor/b1-wave3-v20-schema` / PR #152 + R-B | **Complete** — tasks 3.1–3.7 / R-B; V20 applied; G3 green ([`B1_R_B_G3_SERVING_PROOF.md`](../runbooks/B1_R_B_G3_SERVING_PROOF.md)); portfolio traffic superseded by R-B2 | Forward-only after V20; do not roll back migration or gateway |
| `cursor/b1-wave4a-composition-core` / PR #153 | **Merged** on `main@2673f40` – Wave 4a–4c tasks 4.1–4.21. Read-only catalog path is served via Artifact 2a; composition write mechanisms remain **unexposed**; no public `PUT` | Do not start Wave 6–7 or candidate attestation without separate authorization |
| PR #155 / R-B2 | **Complete for 5.1–5.3** — Task 5.1 on `main@f22e2ff`; Artifact 2a serving on `portfolio-service--0000081` / `sha256:d544649f…` ([run 32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866); [`B1_R_B2_G2A_SERVING_PROOF.md`](../runbooks/B1_R_B2_G2A_SERVING_PROOF.md)); G2a green | Tasks 5.4–5.6 subsequently merged source-only via PR #161; any future portfolio rollout invalidates G2a until re-proven |
| PR #161 / G5 | **Caller source merged on `main@0b5d60d1`; 5.7/G5 complete by owner decision on 2026-09-02.** [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) succeeded from `main@f66d7ab6` with all three markers; PR #197 merged the reviewed evidence at `main@b6c0da3`. [Close-out record](../runbooks/B1_G5_INGRESS_BLOCKER.md) | G5 prerequisite satisfied for B1 Wave 6; implementation, R-B3 deploy/proof, public `PUT`, and schedule restoration remain separate |
| PR #217 / B1 Wave 6 source | **Merged on `main@d66bb23d`**; Tasks 6.1–6.4 checked, Codex ACCEPT at `1bdb1d31`, R1/R2 closed, final PR-event CI successful | 6.5 owner GO and approved candidate build cu4 recorded; 6.6 G2b serving proof and 6.7 R-B3 remain open; packaging is not deployment |
| [`proof/b1-wave-2-g1-v20@e6a98c5`](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/tree/proof/b1-wave-2-g1-v20) | Historical dependent dual-schema proof branch; superseded for Wave 3 delivery by `cursor/b1-wave3-v20-schema` | Remain unmerged; no release action |

### Process-control status

| Item | Current state | Required before relying on it |
|---|---|---|
| Status-propagation CI guard | Contract tests in `static-guard`; live PR-body check in dedicated `master-plan-status-propagation` workflow (`opened`/`synchronize`/`reopened`/`edited`) | Process-control only; does not advance the runtime baseline or create user-facing Asset Picker capability |
| Docs-only CI fast path | **Complete** via PRs #198–#199 on `main@3396ec45`; `ci-required` is the eighth required context, while all seven previous contexts remain required. PR #202 added `azure-image-smoke-test` as an eighth `ci-required` dependency (transitive, not branch-protection); probe PR #200 proved the original four-job docs-only skip shape, PR #199 proved the full-suite shape, and PR #204 proved the current five-job docs-only shape | Keep the classifier fail-closed and preserve declared-versus-observed equality. DAG de-serialization and broader path selection stay deferred unless CI latency begins blocking delivery |

The temporary product state is intentional but incomplete: the unsafe legacy writer is gone, while
the safe versioned replacement has not yet been built. A frontend picker cannot save holdings until
B1 Wave 7 activates the new endpoint.

## 4. Track C — B2 Asset Picker product

Authorities:

- [requirements](../../.kiro/specs/asset-picker-composition/requirements.md)
- [design](../../.kiro/specs/asset-picker-composition/design.md)
- [implementation tasks](../../.kiro/specs/asset-picker-composition/tasks.md)
- [visual mockup](../../.kiro/specs/asset-picker-composition/mockup/asset-picker-design.html)

All four artifacts are tracked. Wave 1 (1.1-1.19) and Wave 2 Tasks 2.1-2.5 merged source-only on `main@38e3d95` through PR #178 after two external review rounds and regression fixes. That frontend remains entirely mock-backed and disabled by default; the merge did not authorize live endpoint wiring, deployment, or production exposure. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209` with a configurable 150-second default TTL; Task 3.7 deploy/live probe remains a separate owner-gated production step (not deployed, not activated, not live-probed). Wave 4 Tasks 4.1–4.4a merged via PR #180 at `main@63fc058`; that exact historical cut was built immutably and digest-deployed to internal-only `portfolio-service--0000093`, and Task 4.5's controlled live proof is GO ([evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md)). Standalone Task 5.1a (`InternalApiKeyProvider`) merged source-only via PR #202 at `main@64761dc2`, and standalone Task 5.1b (`ReplicaTokenProvider`) merged source-only via PR #208 at `main@f954b5a7`. Wave 8 Task 8.1 (the additive `updatedAt` read contract) merged source-only via PR #185 at `main@198c878d`, and standalone Task 8.2a (`CloudFrontOriginSecretProvider`) merged source-only via PR #203 at `main@addd8049`; these later standalone tasks are not deployed.

| Wave | Status | Dependency note |
|---|---|---|
| 1 — mock-backed picker shell | ✅ Source merged (1.1-1.19), mock-backed only; not deployed/live | Feature flags, modal, browse/draft/review/conflict UX, mocked save/freshness/presence; PR #178 / `main@38e3d95` |
| 2 — decimal adapter | 🟡 Tasks 2.1-2.5 source merged; 2.6-2.7 not started | PR #178 / `main@38e3d95`; rollout sequencing with B1 Wave 4/5 remains an explicit open coordination decision |
| 3 — Redis-backed presence | 🟡 Tasks 3.1–3.6 source merged via PR #179 / `main@cc97a209`; Task 3.7 open | Default TTL **150s** via `APP_DEMO_PRESENCE_TTL`; not deployed/live-verified |
| 4 — portfolio-service demo reset | ✅ Complete — Tasks 4.1–4.4a merged via PR #180 / `main@63fc058`; Task 4.5 live GO | Exact cut serves internally on `portfolio-service--0000093` / `sha256:9a1d5533…`; one authorized same-state reset returned `200`, exact golden 159/159, unchanged version `0` per B1; [evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md) |
| 5 — manual-reset gateway bundle | 🟡 Tasks 5.1a and 5.1b source merged via [PR #202](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/202) / `main@64761dc2` and [PR #208](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/208) / `main@f954b5a7`; neither is deployed. Wave 4's prerequisite is satisfied. Remaining source Tasks 5.1, 5.2, 5.3, 5.3a, 5.4, and 5.5 merged via [PR #212](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/212) / `main@d8fa499d` | Filter, both routes, exact read-only exceptions, tests, and identity guard accepted; final CI passed on the identical reviewed tree. Task 5.6: 7/7 technical conditions met, owner GO pending; no deployment |
| 6 — manual reset frontend | ✅ Tasks 6.1/6.2 merged via [PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214) at `main@48d0aba8`, identical to CI-green head `b918ff09`; ACCEPT, R1–R4 closed | Committed flags off, placement open, Task 6.3 still gated; no new runtime attestation. The owner-deferred [sidebar backlog](../todos/backlog/responsive-dashboard-sidebar/README.md) remains open |
| 7 — decimal rollout note | ℹ Informational | No independent release gate |
| 8 — login-orchestrated reset | 🟡 Task 8.1 (`updatedAt` read contract) source merged via [PR #185](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/185) / `main@198c878d`; not deployed. Task 8.2a (`CloudFrontOriginSecretProvider`) source merged via [PR #203](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/203) / `main@addd8049`; not deployed. Task 8.2's open decisions and Tasks 8.3 and later not started | Requires B1/V20/version read (met, satisfied by 8.1); open idle/timeouts, Tasks 8.3 and later, 5.1b (merged source-only via PR #208; not deployed), and its own deployment evidence |
| 9 — live integration | ⬜ Blocked | Requires B1 catalog/read/write endpoints and relevant B2 Waves 1–6 |
| 10 — production exposure | ⬜ Blocked | Convergence gate after all required live evidence and open decisions close |

The aggregate `assetPriceFreshness` backend dependency is now **closed**: Spec A task 8.6 is
complete and `PortfolioSummaryDto.assetPriceFreshness` exists. B2 still must implement its frontend
adapter and UI wiring in Waves 1 and 9.

### Open B2 decisions

1. Demo reset idle threshold; 30 minutes is provisional.
2. Manual reset control placement in the UI.
3. Login self-call timeouts; 2 seconds per leg and 4 seconds overall are provisional.
4. Decimal-adapter deployment sequencing relative to B1 Wave 4/5.

These do not block starting the mock-backed picker shell. They do block the affected reset/presence
behavior and final production exposure.

## 5. Dependency path to a production Asset Picker

```text
Track A: 9.12 complete -> 9.13 complete -> 9.14 complete (ingress reopened)
                                       (Spec A production cutover checkpoints all complete)

Track B: Wave 2 -> Wave 3 -> Wave 5 -> Wave 6 -> Wave 7
                    ^          ^                   |
                    |          |                   v
              Wave 4 implementation --------> real read/write APIs

Track C: Wave 1 mock UI + Wave 2 adapter + independent presence work
                    |                              |
                    +---- relevant Waves 3-6 ------+
                                                   v
                                    Wave 9 live integration
                                                   v
                                    Wave 10 production exposure
```

Remaining B1 and B2 source tasks may proceed according to their explicit dependency graphs; they do
not need to be serialized with unrelated operational work. Production transitions retain their
individual approval gates.

## 6. Next meaningful work and authorization boundary

### Current cutoff

Spec A 9.14 remains the last completed Spec A checkpoint. Historical setter attribution remains
`MECHANISM_REPRODUCED_SETTER_UNPROVEN`. B2 Task 4.5 subsequently completed on
`portfolio-service--0000093` / `sha256:9a1d5533…`; production remains on
`market-data-service--0000079` and `insight-service--0000079`. The demo has the exact 159-holding
golden state after the valid no-op reset proof, and both demo/diagnostic flags remain `false`. The
three catalog consumers are restored to
`min_replicas=0`; gateway ingress is open after the 9.14 apply, and the
`api.vibhanshu-ai-portfolio.dev` custom-domain binding has since been restored with independent
public `200` read-back.

- historical setter remains unidentified; statement-history probe executed once on 2026-08-29 at
  `main@cdf23737` and returned `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE`;
- production demo and diagnostics gates remain `false` after the restoring apply;
- scale has been restored to `min_replicas=0`; ingress is reopened on the default ACA endpoint;
- B2 Task 4.5 is GO after the owner-authorized immutable digest deployment and one same-state reset
  probe; Wave 5's Wave 4 prerequisite is satisfied. A later owner-selected source task produced
  PR #212, now merged at `main@d8fa499d`; the Task 4.5 GO itself authorized no further operation;
- 9.14 is complete and live-verified; **B1 G5 / Task 5.7 closed by owner decision on
  2026-09-02**, using reviewed three-caller run [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271);
  unattended synthetics remain suspended and four process follow-ups remain open; and
- B1/B2 implementation status is cleanly separable from the remaining production cutover.

### Selected priority and remaining lanes

**Owner priority decision (2026-09-01): resume Asset Picker delivery before further CI
optimization.** The docs-only fast path is sufficient for now. DAG de-serialization, broader
frontend/backend job selection, and direct `integration-tests` runtime optimization remain deferred;
DAG work may be promoted if CI latency begins blocking delivery.

This priority does not waive any release or production gate. B1 Task 5.7/G5 closed under the
owner's 2026-09-02 decision; B1 Wave 6's G5 prerequisite is satisfied. Wave 7 still requires
R-B3 and its activation gates. B1 Tasks 6.1–6.4 are merged and 6.5 has owner GO. Candidate cu4 is packaged; the deployment/6.6 proof bundle is approved and awaits secure credentials. Tasks
5.1a (`InternalApiKeyProvider`) and 8.2a (`CloudFrontOriginSecretProvider`) have merged source-only
via PRs #202 and #203 at `main@64761dc2` and `main@addd8049`; neither is deployed. Task 5.1b
(`ReplicaTokenProvider`) merged source-only via
[PR #208](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/208) at
`main@f954b5a7`; it is not deployed. **Wave 4 Task 4.5 completed with a reviewed GO on 2026-09-01.**
The owner-authorized immutable build, scoped digest deployment, one valid same-state reset, corrected
B1 version interpretation, and cutover read-readiness observation are recorded in
[`B2_TASK_4_5_DEMO_RESET_STOP_GO.md`](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md). Wave 5's Wave 4
prerequisite is satisfied, but no Wave 5 implementation or deployment is authorized by that GO.
**Current B2 status (2026-09-02): Wave 6 source complete.**
[PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214) merged on
`main@48d0aba8468325b91e1bf9b84bd43cbeaacdf74a` at `2026-09-02T15:53:57Z`. Its parents are
`06b352502c14f6d34662b30ff6f0b0a3047c80e7` and final head
`b918ff093a5c6abdb246db5eee044e3c10458b6d`; the merged tree is identical to that head.
All 15 reported checks passed; the
[final-head backend pipeline](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33647195283)
concluded success, including `ci-required` and executed Azure image smoke.

Source/visual ACCEPT at `970b637b` carries forward with R1–R4 closed. The
[12-image evidence set](../evidence/b2-wave-6-manual-reset/README.md) and independent contrast
calculations passed: light success 5.11:1, light alerts 6.03:1, dark alerts 6.95:1, enabled hover
5.48:1, and dark success about 10:1. The version explanation is corrected. The
[sidebar issue](../todos/backlog/responsive-dashboard-sidebar/README.md) remains owner-deferred.
[Frontend CI at accepted source](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33646074169)
passed **526 tests / 62 files**, lint, typecheck, build, and static login-HTML smoke.

Tasks 6.1/6.2 are checked for source completion. Committed flags stay off; final placement,
Task 5.6's owner GO, Task 6.3, and exposure remain separate. No new runtime attestation is made.
**Current B1 priority (2026-09-03): Task 6.5 owner GO/read-only preflight recorded; Cursor 7.1–7.2 remains ready for local implementation.** Tasks 6.1–6.4
merged through PR #217 after source ACCEPT and final-head CI. The historical Claude kickoff
is complete as a source assignment. Any release handoff must separately specify the authorized
artifact, serving-proof scope, and rollback conditions; this record authorizes no production operation.

**Wave 5 source completion:** [PR #212](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/212) merged at
`main@d8fa499de05fa1370a0271c4822230a6ea113695`, with reviewed base `a2c402db` and final head
`01917e16` as its two parents. The merged tree is identical to the reviewed head. Source commit
`d4e98459` contains Tasks 5.1, 5.2, 5.3, 5.3a, 5.4, and 5.5 together; `01917e16` adds their
review documentation. All six source tasks are now checked in the owning ledger.

Final-head [CI run 33613233150, attempt 2](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33613233150)
passed, including `ci-required`, `static-guard`, and an executed `azure-image-smoke-test`.
The PR classified `docs_only=false`. All three smoke cases matched exact stdout with empty
stderr: blank/nonblank internal-key probes and the replica-token tool vector. The first attempt
failed before compilation on a Maven Central HTTP 429; the retry required no source change.
Codex inspected the 276 unit / 190 integration gateway reports and independently reran the
24 identity-checker tests and real-source check on the merged tree.

**Task 5.6 technical assessment: 7/7 conditions met; recommend GO.** The owning ledger records
the condition-by-condition evidence. Its checkbox remains open pending the separately reserved
owner decision. This is gateway transport/authorization and image-packaging proof; Task 4.4 owns
mutating persistence tests and Task 4.5 remains the historical same-state live no-op.
The [original Claude kickoff](../agent-instructions/CLAUDE_KICKOFF_B2_WAVE_5_MANUAL_RESET_GATEWAY.md)
is retained as a historical scope reference. Deployment, live reset, frontend exposure, and
further implementation remain separate decisions. B1 G5 closed under its own owner decision on
2026-09-02; Wave 5's technical assessment did not close it.

1. **Operational lane:** Spec A's production cutover is **complete through 9.14**. The plan was
   reviewed and ACCEPTed (2026-08-31), and the authorized apply
   [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603)
   reopened ACA external ingress on the existing `api-gateway--0000077` revision with insecure
   connections still disabled
    ([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)). The separately
    authorized custom-domain plan and apply/bind have restored and independently read back
    `api.vibhanshu-ai-portfolio.dev`; PR #194 independently reviewed and merged that evidence
    ([`api-gateway-custom-domain-binding`](../todos/backlog/api-gateway-custom-domain-binding/README.md)).
    Authorized three-caller synthetic
    [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
    succeeded from `main@f66d7ab6`; its evidence merged via PR #197, and the owner closed Task 5.7
    on 2026-09-02;
    unattended synthetics remain suspended in `synthetic-monitoring.yml`. Three further process follow-ups are filed alongside it:
   [`service-version-image-drift`](../todos/backlog/service-version-image-drift/README.md),
   [`deployed-image-tags-json-validation`](../todos/backlog/deployed-image-tags-json-validation/README.md),
   and [`b5-image-equality-assurance-claim`](../todos/backlog/b5-image-equality-assurance-claim/README.md).
   Installing
   `pg_stat_statements`, claiming a named historical setter, or any other production action
   remains separately gated.
2. **Backend lane:** **R-A/G2, R-B/G3, and R-B2/G2a are complete**; caller Tasks 5.4–5.6
   merged via PR #161 at `main@0b5d60d1`. **Task 5.7/G5 closed by owner decision on
   2026-09-02**, using run [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
   and reviewed evidence PR #197. **B1 Tasks 6.1–6.4 merged through PR #217** at `main@d66bb23d`: the
   source requires `expectedVersion` and delegates replacement through the shared service. Task
   6.5 is GO and the separately approved candidate build cu4 succeeded. R-B3 deployment/serving proof, Wave 7 activation, and candidate
   packaging (7.5/R-C) remain separate work. The deployed seed remains on the prior serving cut;
   merged source alone does not establish Writer_Convergence.
3. **B2 product lane:** Wave 1 (1.1-1.19) and Wave 2 Tasks 2.1-2.5 are merged source-only through
   PR #178 on `main@38e3d95`, with two rounds of external review findings fixed. Wave 3 Tasks
   3.1–3.6, Wave 4 Tasks 4.1–4.4a, and standalone Tasks 5.1a, 5.1b, 8.1, and 8.2a are also merged.
   The exact Wave 4 cut is deployed internally on `portfolio-service--0000093`, and Task 4.5 is GO;
   the other listed B2 source remains undeployed. Task 5.1b merged via
   [PR #208](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/208) at
   `main@f954b5a7`. Wave 5's Task 4.5 prerequisite is satisfied; Tasks 5.1, 5.2, 5.3, 5.3a,
   5.4, and 5.5 merged via PR #212 at `main@d8fa499d`. Final-head CI passed and the merged tree
   matches the reviewed head. Task 5.6's seven technical conditions are met; its owner GO decision
   remains open. Wave 6 Tasks 6.1/6.2 merged via PR #214 at `main@48d0aba8`, identical to
   final CI-green head `b918ff09`, and are checked for source completion. Flags remain off
   in committed source; final placement and Task 6.3 remain open. No new runtime attestation is made.
   Tasks 2.6–2.7, live proofs, remaining bundles, deployment, and exposure retain their own gates.
4. **Process lane:** keep the status-propagation CI guard healthy in required `static-guard`; it is
   process-control only and does not advance the runtime baseline.

No item above is authorized merely by being listed. The implementation handoff must name the chosen first
task, its exact scope, predecessor evidence, stop condition, and whether it is documentation,
implementation, or a production operation.

**Completed source handoff (reconciled 2026-09-03):**
[Claude kickoff — B1 Wave 6 Tasks 6.1–6.4](../agent-instructions/CLAUDE_KICKOFF_B1_WAVE_6_VERSION_REQUIRED_SEED.md)
is retained as the historical execution plan for merged PR #217. Its implementation and final
review are complete; it is not a new assignment. Task 6.5 has owner GO and candidate build cu4
has succeeded. The deployment/6.6 proof packet and offline E2E reference are now prepared;
the exact live bundle is owner-approved and awaits secure credentials after preliminary metadata
checks. R-B3 and B2 gates remain open.

## 7. Handoff requirements

Any new implementation handoff must be self-contained and anchored to the current `main` SHA. It
must include:

- this master plan as the first-read program dashboard;
- the exact owning requirements/design/tasks documents for the chosen task;
- current production fences and explicit non-authorizations;
- active/draft PRs and whether their code is on `main`;
- commands and tests required to verify the chosen task;
- known stale-branch/rebase hazards;
- the four unresolved B2 decisions without silently choosing values;
- the status-governance rule from §0; and
- an instruction to update this plan and the owning task ledger in every status-changing PR.

AWS-only work remains deferred while AWS production is disabled. Azure is the current delivery
target; shared behavior and cross-cloud contracts must not be weakened.
