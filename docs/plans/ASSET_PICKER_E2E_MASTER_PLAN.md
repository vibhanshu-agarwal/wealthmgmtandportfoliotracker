# Asset Picker — E2E Master Plan to Production

**Last verified:** 2026-09-11 at `main@45c1275ba306cde19cb344f2954c1a43c3ec4952` for B2 Task 8.9's
current-provenance reconciliation (PR #262; no runtime/program-state baseline change); 2026-09-11 at
`main@03ca63000a16f38484a37cc85ab938a0ad7874c2` for B2 Task 10.1's merged source-only Azure
build-flag wiring (PR #259; no runtime/program-state baseline change);
2026-09-11 for B2 Task 6.3's bounded gateway-read evidence;
2026-09-10 at `main@26148c4be75675613e28d89f713639a0376aaba7` for the merged B2 Task 8.9 source-only provenance correction;
2026-09-10 at `main@ea34c017514532977ce08d07be3344c1c2cb065a` for B2 Task 8.8 deployment evidence;
2026-09-09 at runtime/program-state code baseline `main@5fd1dac6a37513916fd2b80ca3929c4e20ad0de7` for Task 7.9 serving proof;
pre-merge Task 7.7 evidence baseline `main@108addca6d079b08d9d0822d87bfe43812cd5699`; candidate release cut `8f1e8a36f8baa594efa8079190f87b42139fcf10`

**B2 TASK 8.8 DEPLOYMENT COMPLETE — 2026-09-10:** After the recorded Task 5.6 GO and Task 8.8
authorization, protected production
[run 34433715705](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34433715705)
deployed only `api-gateway` from `main@ea34c017514532977ce08d07be3344c1c2cb065a` in scoped Azure
mode. Immutable digest
`sha256:aee44edc12b03175379caf65546e04f5ca1e2cea0d8690c00190b01254f20aa1` was deployed as
`api-gateway--0000079` at 100% latest-revision traffic. The current-attempt manifest comparison and
byte-identical non-interference proof passed; all unselected apps and the refresh Job remained
unchanged. `0000079` was subsequently superseded and purged; its Task 8.8 deployment evidence remains
valid history, while current `api-gateway--0000081` is separately attested below. Task 8.8b is
therefore complete with 8.8. Task 8.9 live serving proof and Wave 10 remain open and separately
owner-gated, and both production feature flags remain disabled. See the
[completion evidence](../evidence/b2-task-8-8/deployment-completion-20260910.json) and
[owner approval](../evidence/b2-task-8-8/owner-approval-20260910.json).

**TASK 8.9 PROVENANCE RECONCILIATION — PR #262 MERGED; LIVE PROOF OPEN:** PR #248's source-only
preflight established separate immutable per-service attestations. PR #262, merged at
`main@45c1275ba306cde19cb344f2954c1a43c3ec4952`, preserves the original 2026-09-10 packet as
history and adds the current [2026-09-11 provenance packet](../evidence/b2-task-8-9/deployment-provenance-20260911.json).
The latter binds the serving `api-gateway--0000081` identity to deployment run `34588465283` while
retaining `portfolio-service--0000096`'s independent run `34328692256` attestation; no common
workflow identity is synthesized. The verifier must receive that current packet through
`--deployment-provenance` and still requires both exact serving digest and revision matches before
any mutation can arm.

The 2026-09-11 read-only re-preflight matched the production subscription, ingress, both attested
serving image/revision pairs, and workspace; it then stopped fail-closed in replica discovery because
the scale-to-zero gateway had no named replica for the non-disclosing exec probe. No gateway wake,
exec, ACR authentication or pull, KQL query, HTTP request, write, cleanup, or rollback occurred.
The bounded gateway wake remains a separate owner decision. This reconciliation does not authorize
the production login, write, KQL, cleanup, or Task 8.9 completion; see the prior
[2026-09-11 re-preflight evidence](../evidence/b2-task-8-9/rehearsal-20260911.json). The
[2026-09-10 rehearsal record](../evidence/b2-task-8-9/rehearsal-20260910.json) remains historical
evidence for its earlier gateway identity.

**B2 TASK 10.1 SOURCE WIRING — MERGED THROUGH PR #259; EXPOSURE CLOSED:**
PR #259 merged at `main@03ca63000a16f38484a37cc85ab938a0ad7874c2`. Its two-file Azure-only change
maps both static-export `NEXT_PUBLIC_*` controls to GitHub Actions repository-variable names and
adds a fail-closed structural contract for their exact build-step locality and expression shape.
CI run [34622304228](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34622304228)
passed `static-guard`, `sanitizer-canary`, `deploy-workflow-contract`, `docker-build-verify`, and
`ci-required`; the frontend checks and master-plan-status-propagation also passed. The merge did
not create, read, or change either variable. No workflow dispatch, cloud/secret access, deployment, or feature
exposure occurred. Task 10.1 enables a later owner-controlled build only; Task 8.9, Wave 10.2, and
Production E2E remain separate gates.

**Current delivery status — Tasks 7.1–7.11 locally complete:**
The final authorized collection binds all four serving apps to exact immutable digests and passes
G2, latest-valid G3, G4, G0a, G2a, G2b and derived G6. Transparent `insight-group` recovery is
limited to the retained `25808–26602` range and is not represented as historical offset evidence.
Exactly one seed returned SAME_STATE with complete byte-identical before/after snapshots; one
required signup and one explicitly approved recovery signup left 12 users/12 portfolios with zero
violations. Astra independently ACCEPTed the [final packet](../runbooks/B1_R_C_TASK_7_7_SERVING_EVIDENCE.md)
with no Critical, Important or Minor findings. The [Task 7.8 owner-GO record](../evidence/b1-r-c/task-7-8-owner-go-20260909.json)
closes the local pre-deploy decision only. The separately approved Task 7.9 digest deployment now
serves `portfolio-service--0000096` at the exact candidate manifest, 100% traffic, with one
authenticated same-state composition PUT and non-interference proof; see the
[Task 7.9 serving proof](../runbooks/B1_R_C_TASK_7_9_SERVING_PROOF.md). Task 7.10 owner GO is
recorded locally. P11g-1 is established for the transitional range; Writer_Convergence / P11g-2
is established for the activated exact R-C artifact and rollback artifacts at or above R-B3r only.
Publication remains separate until explicitly authorized, and no additional production operation or
rollback occurred during Task 7.11. R-C is portfolio-only but not dark: the existing gateway
wildcard makes the public controller reachable immediately.

**Historical context retained below (superseded for current Task 7.7 verdict):**
The program-state code baseline is current `main`; the latest tracked production runtime is R-B3r
portfolio revision `0000095` / digest `sha256:fa060bf0…`.
[PR #222](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/222)
merged the R-C candidate verification, packaging, governance and exact-image smoke tooling.
[PR #234](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/234)
closed R3 with the V21 cleanup and evidence-bound operational proof.
[PR #235](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/235)
closed the 553-subject GC.5 review at `main@0bcc7100`: the exact clean-cut Task A run reported
552 unit and 212 integration tests with zero skips/failures/errors, and the CANDIDATE guard reported
zero findings and zero unverified coverage. [PR #236](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/236)
then merged the Windows checkout/CRLF evidence-identity correction at `main@fecbe651`; its reviewed
head passed the CANDIDATE source-governance guard with zero findings and zero unverified coverage.
[PR #237](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/237) reconciled the
master-plan status at the exact clean release cut `8f1e8a36`. From that unchanged cut, Task A passed
552 unit and 212 integration tests (764 total) with zero skips/failures/errors and staged JAR SHA-256
`441d252939d7333dca134b9cc1a5f6a0632cc274a53f410671212c543a85cda4`. The candidate was built once,
pushed once without rebuild, and bound to the deployable `linux/amd64` ACR manifest
`sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`; registry extraction
reproduced the staged JAR hash. Exact-digest smoke passed A1/A2/A3/A4/A4-db (5/5), and the final
CANDIDATE guard passed with zero findings, zero unverified coverage and all Task A/Task B/build
artifacts verified. The tracked [candidate evidence checkpoint](../evidence/b1-r-c/candidate-evidence-checkpoint-20260908.json)
records the immutable identifiers and local evidence hashes. Tasks 7.3–7.6, including 7.5a, are now
evidenced. Task 7.7 has current read-only serving evidence and direct-revision version capability,
but G2/G0a/G2a/G2b/G4 and derived G6 remain open; Task 7.8 is not ready for an owner STOP/GO. The
zero-mutation G4 evidence-equivalence protocol and explicit Task 7.6-to-R-B3r writer map are now
designed and independently accepted; the map closes its documentation gap but cannot close G6
without fresh G0a/G2a/G2b. No R-C
deployment, workflow dispatch, traffic change, production E2E, public exposure or
Writer_Convergence claim has occurred.

**Task 7.7 read-only collection — 2026-09-08:** the baseline fetch independently resolved PR #238's
merge and remote `main` to `7e752b4183aa25f75adad6e7b363501cd5f23aa5`. Review corrected the
serving baseline from historical cu4 to the later tracked R-B3r revision `0000095` / digest
`sha256:fa060bf0…`. The task host rejected the first read-only request; the owner then approved that
bounded access. Current control-plane, ACR, Log Analytics, direct-revision GET and read-only Neon
evidence now binds all four sole serving revisions. Only portfolio uses an immutable configured
image; the three mutable-tag resolutions do not attest already-running bytes. The direct GET proves
numeric-version backend capability but not G2a's authenticated-read predicate. The database snapshot
is green with `violating_users=0`, but G3 is out of sequence until current G2 is valid. G4 is
partially green. G2, G0a, G2a, G2b and G6 remain unverified. The approved
[G4 protocol and writer map](../runbooks/B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md) use a zero-mutation,
fail-closed evidence-equivalence contract because R-B3r exposes no arbitrary-holdings input.
No production write, dispatch, deployment or traffic/configuration change occurred. The
[Task 7.7 packet](../runbooks/B1_R_C_TASK_7_7_SERVING_EVIDENCE.md) records the exact results and
approval boundary. Task 7.7 remains open and Task 7.8 cannot yet be presented.

**Task 7.7 authorized completion attempt — 2026-09-09:** complete fixed-window startup coverage
passed for all nine observed consumer replicas and the bounded DLT offset stayed `80 → 80`.
`portfolio-group` was at committed/log-end `26602/26602`, lag `0`, but equal pull-byte counts for
the three write-enabled tags were rejected as immutable content attestation and required
`insight-group` was absent from broker metadata (`GroupIdNotFoundException`). The fail-closed
protocol stopped before signup, authenticated read, both legacy-route POSTs, seed and final G3.
No production database/Kafka/configuration/deployment state changed. The
[authorized-attempt record](../evidence/b1-r-c/task-7-7-authorized-execution-20260908.json) is the
current Task 7.7 status; Task 7.7 remains open and Task 7.8 must not be presented.

**Seed caller inventory update — B2 Task 9.7 (2026-09-06):**
The [caller guard](../../scripts/check-b1-seed-version-callers.py) now recognizes exactly four
governed sites: the three historical B1 Wave 5b callers (`synthetic-shell`, `global-setup`,
`azure-api-smoke`) plus `frontend/tests/e2e/asset-picker.spec.ts` cleanup. The historical
three-caller G5 evidence and marker contract are unchanged. Only the Task 9.7 cleanup may retry
an internal seed `409` for hygiene: each of at most three attempts freshly reads the portfolio
with the E2E bearer token, selects the fixed E2E identity, and sends that `expectedVersion` with
the internal key. Any observed cleanup conflict still fails the case, even after a later `200`.
The guard rejects additional caller paths and checks this fourth caller's version, identity,
credentials, and conflict policy. It also requires an exact reviewed source prefix from the
beginning of the module through `afterEach`, including cleanup and fixture registration. Only
LF/CRLF line endings are normalized; any other changes within that prefix, including comments
or whitespace, require review and an explicit canonical update. This bounded contract does not
parse TypeScript or cover the later test bodies. This source inventory update changes no
production runtime gate or historical caller policy.

**Historical R-C tooling checkpoint — before Task 7.8 owner GO:** the
[return packet](../superpowers/plans/2026-09-04-b1-rc-candidate-preparation-return-packet.md)
records the original tooling acceptance and the preserved September 3 LOCAL_DEV graph. Those
historical results remain development evidence only. PRs #234–#236 supersede its R3 and 504-finding
status, while the September 8 [candidate evidence checkpoint](../evidence/b1-r-c/candidate-evidence-checkpoint-20260908.json)
supplies the current-cut Task A/B bundle, registry manifest, exact-digest smoke and final zero-finding
governance result through Task 7.6. The independently accepted final serving packet completes
Task 7.7. Tasks 7.8–7.11, AM.1/AM.2 and Writer_Convergence remain open.

**OWNER APPROVAL RECORDED — Claude R-C preparation kickoff, 2026-09-03:** The owner requested
the Claude kickoff and its docs-only PR. This authorizes publication of this documentation package;
merge and implementation publication/release operations remain separate decisions. The
[Claude kickoff](../agent-instructions/CLAUDE_KICKOFF_B1_R_C_CANDIDATE_PREPARATION.md) defines local
packaging/evidence tooling and tests under Codex's
[architecture review](../superpowers/plans/2026-09-03-b1-r-c-candidate-architecture-review.md).
That documentation event preceded implementation; current local preparation status is recorded
above. Its approval covered the kickoff documentation only and grants no implementation
publication or release authority.

**OWNER DECISION RECORDED — R-B3 GO, 2026-09-03:** The owner approved local completion
of Task 6.6 and Task 6.7 GO after reviewing the successful G2b proof: “Approved. Please go ahead.”
Both tasks are now checked locally. The [decision record](../evidence/b1-task-6-6/r-b3-owner-go-20260903.json)
binds this approval to the unchanged proof and cu4 serving image. Publication, further production
operations, Wave 7 activation and Writer_Convergence closure remain outside this approval.

**Historical Task 7.7/pre-Task-7.9 runtime baseline:** portfolio was R-B3r `0000095` / `fa060bf0…`; image-only
replacement revisions now serve gateway `0000078` / `79a3f253…`, market-data `0000080` /
`ad61144b…`, and insight `0000080` / `f7db159d…`. Each is the sole healthy/latest-ready,
100%-traffic target in `Single` mode. The recovered `insight-group` replayed only the retained range
and reached current lag zero without DLT growth. Authenticated numeric-version and retired-route
probes, exactly one frozen-version seed, projection consistency and final G3 all passed. Astra
independently ACCEPTed the complete packet with no findings.


**B1 Tasks 7.1–7.2 source ACCEPT and merged (2026-09-03):** Cursor implemented the public
composition boundary and HTTP tests under the
[bounded kickoff](../agent-instructions/CURSOR_KICKOFF_B1_WAVE_7_PUBLIC_COMPOSITION.md).
Codex accepted head 2f50120f6a1231a158e3953c13e9ddc2af75cb78, based on main@9c2ebc12.
The [review packet](../../audit/b1-wave7/README.md) retains the resolved findings,
88 focused unit and 19 focused integration tests, and its unavailable original RED-log limitation.
[PR #219](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/219)
merged at main@c0f84045 on 2026-09-03T05:42:58Z. The source is merged; the deployed
R-B3 image remains frozen at 6a171558 / cu4 and excludes this controller. PR #237 reconciled the
7.1–7.2 source checkboxes. The September 8 candidate checkpoint now supports 7.3–7.6, including
7.5a; 7.7 and later gates remain open. PR #220 published the approved Wave 6 release evidence and
6.5–6.7 close-out.

**Task 6.5 GO — owner decision recorded 2026-09-03:** the owner approved GO and read-only preflight
following the existing technical recommendation. The
[readiness record](../runbooks/B1_TASK_6_5_PRE_DEPLOY_READINESS.md) and
[sanitized metadata](../evidence/b1-task-6-5/preflight-20260903.json) confirm the existing portfolio
revision/digest, internal traffic and disabled startup/diagnostic flags; ACR build cu3 and manifest
metadata agree with the reviewed B2 4.5 provenance. That digest is the proposed compatibility
rollback target, not an authorized rollback. The separately approved single build cu4 succeeded
from frozen source 6a171558, producing candidate digest 2be727ea…; the run output and registry
tag/manifest read-backs agree. [Build evidence](../evidence/b1-task-6-5/candidate-build-20260903.json)
records the exact digest and local cut tag. The prior 0000093/9a1d5533 image remained active at
the historical build checkpoint. A separately approved deployment and proof followed, below.
Tasks 6.6/6.7 are now checked under the separate owner R-B3 GO.

**Task 6.6 preparation — 2026-09-03:** The [execution packet](../runbooks/B1_TASK_6_6_G2B_EXECUTION_PACKET.md)
and [offline E2E reference](../evidence/b1-task-6-6/e2e-golden-reference-6a171558.json) are ready for
review. The reference covers all ACTIVE entries using the E2E identity, not the existing demo
oracle's identity. The packet specifies complete read-only SQL snapshots, application readiness,
one frozen-version seed, exact digest deployment and conditional rollback before seed transmission.
The owner approved the bundle and identified the shared .env.secrets. All client parameters
resolved from its six existing keys; the earlier process-only blocker is superseded. Read-only DB
capture and authenticated application readiness passed. The [resumed result](../evidence/b1-task-6-6/resolved-preflight-and-dispatch-20260903.json)
retains the historical waiting checkpoint. The gate was then approved and deployment succeeded.
**Task 6.6 technical ACCEPT:** the [completed report](../runbooks/B1_TASK_6_6_G2B_SERVING_PROOF.md)
records one seed at frozen N=0, HTTP 200, all 159 complete holdings unchanged, and byte-identical
parent/demo/schema/full-price/history captures. No retry or rollback occurred. The owner then
approved Task 6.7 R-B3 GO and local 6.6/6.7 completion; both ticks are now recorded. Nothing
was published or replayed for this documentation close-out.

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
2026-09-03; Tasks 6.6/6.7 are now complete under the separate owner R-B3 GO.** Read-only metadata preflight is recorded in the
[6.5 readiness record](../runbooks/B1_TASK_6_5_PRE_DEPLOY_READINESS.md). The separately approved candidate build is recorded in §6.1 of that record. Deployment and
G2b proof subsequently passed under its separate execution approval; Task 6.6 is technically
ACCEPT and the owner approved R-B3 GO. Wave 7 activation and Writer_Convergence remain open. No schedule
restoration, B2 gate decision or feature exposure follows from the 6.5 decision.

**Post-merge verification:** [PR #212](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/212) merged at
`main@d8fa499de05fa1370a0271c4822230a6ea113695` on 2026-09-02 at 10:03:53Z.
Its parents are the reviewed base `a2c402db` and final head `01917e16`; the merged tree is
identical to that head. B2 Tasks 5.1, 5.2, 5.3, 5.3a, 5.4, and 5.5 are source-complete.
Task 5.6's owner GO is recorded, and Task 6.3 is green on the 2026-09-11 gateway-read evidence.
This backend-readiness GO does not advance exposure or claim any production action beyond its recorded bounded read.

**B2 source completion:** [PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214)
merged at `main@48d0aba8468325b91e1bf9b84bd43cbeaacdf74a` on 2026-09-02 at 15:53:57Z.
The merged tree is identical to final head `b918ff09`, whose CI is green. B2 Wave 6 Tasks
6.1/6.2 are source-complete and checked; source/visual ACCEPT at `970b637b` and R1–R4 closure
carry forward. Committed flags remain disabled; the page-level placement is final and Task 6.3 is green,
and the owner-deferred [sidebar backlog](../todos/backlog/responsive-dashboard-sidebar/README.md)
remains separate. The bounded gateway-read attestation is recorded only for Task 6.3.

**Owner G5 close-out (2026-09-02):** “Please do the G5 close out.” B1 Task 5.7 / G5 is
complete using the reviewed three-caller evidence below. This satisfies B1 Wave 6's G5
prerequisite. Tasks 6.1–6.4 subsequently merged through PR #217, and Task 6.5 has its separate
2026-09-03 owner GO. R-B3 deployment/proof and its separate owner close-out are now complete.
Wave 7 activation remains open; Task 5.6's GO and Task 6.3's backend-readiness GO do not authorize exposure.

**Historical runtime lineage (latest tracked R-B3r portfolio binding is summarized above):** the cross-program baseline was
`main@e221662b6c891639a56894289e150ee01fb537f6`; B2 Wave 4 then served the deliberately pinned
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
only the pinned B2 Wave 4 internal demo-reset cut. That portfolio revision was subsequently
superseded by R-B3 / cu4 revision `0000094`, source `6a171558` and digest `2be727ea`, as recorded in
the completed G2b report. The later V21-only R-B3r remediation then produced revision `0000095` /
digest `fa060bf0…`; Task 7.7 has now read back that exact current binding. The seed requires
expectedVersion, but the current controlled-seed proof remains separately gated.

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

**Cross-lane progress snapshot (historical provenance; use the status tables below for current gates):**
B2 Wave 1 (Tasks 1.1-1.19) and Wave 2 Tasks 2.1-2.5 are merged source-only through PR #178 at `main@38e3d95`; they remain entirely mock-backed and disabled by default. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209`; its later Task 3.7 Azure proof is GO, as recorded in the current evidence below.

Wave 4 Tasks 4.1–4.4a merged via PR #180 at `main@63fc058`; that exact cut is now deployed only to the internal portfolio-service endpoint, and Task 4.5 completed with a reviewed live GO on `portfolio-service--0000093` / `sha256:9a1d5533…` ([evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md)). Tasks 2.6–2.7, Task 5.6's owner decision, Wave 8 Tasks 8.8–8.9, Wave 6 Task 6.3, and Wave 10 remain open. B1 Wave 7 Tasks 7.1–7.2 are complete; R-C Tasks 7.3–7.6 (including 7.5a) are evidenced at cut `8f1e8a36`, while Task 7.7 is complete and Tasks 7.8–7.11 remain open. Wave 6 Tasks 6.1/6.2 merged source-only via PR #214 at `main@48d0aba8`. Wave 5 Tasks 5.1, 5.2, 5.3, 5.3a, 5.4, and 5.5 merged source-only via PR #212 at `main@d8fa499d`; they are not deployed. Wave 9 Task 9.6 (the demo-authenticated Playwright fixture) merged source-only via PR #226 at `main@c8fc407c`; it is not deployed. It is a reusable test helper that Task 9.8 imports, and it establishes no assembled-stack or Production E2E result. Task 9.1 catalog integration merged source-only via PR #228 at `main@3ea9578c`; it is not deployed and proves no Production E2E result. Task 9.3 drafted-price integration has local source evidence in PR #229 and is not deployed. Task 9.4 real presence integration carries local assembled-stack evidence (two independently issued demo logins against the real gateway and Redis, with no presence-route fulfillment) and a fixed duplicate-request defect; it is not deployed; merged CI wiring is covered by PR #232 final run 34018608256, and establishes no Production E2E result. Task 9.5 merged via PR #231 at `main@b4c68253b99a796d6301ef79b5aa5a47d5cbd962`. Wave 9 Tasks 9.2, 9.7, 9.8, and 9.9 are source/assembled-stack complete locally: the disposable Compose real-browser run passed 5/5 (setup plus picker and demo-reset success/conflict), and PR #232 final CI run 34018608256 passed docker-build-verify and ci-required. No deployment or Production E2E is claimed. B1 R-C deployment/convergence, Task 6.3, Wave 8 Tasks 8.8–8.9, and Wave 10 remain open; production flags remain off.

**Current Task 3.7 status (2026-09-11):** Task 5.6's owner GO and Task 6.3's backend-readiness
gate are recorded. Task 3.7 is complete: `api-gateway--0000081` served the proven Wave 3 source,
and the owner-controlled diagnostic/re-verification established the corrected presence ordering
with no application defect. See [Task 3.7 Azure evidence](../evidence/b2-task-3-7/TASK_3_7_AZURE_PRESENCE_STOP_GO_2026-09-11.md). This does not
enable a flag, complete Task 8.9, or close Wave 10.

**Current Task 10.1 status (2026-09-11):** PR #259 completed the Azure source wiring and its
fail-closed CI contract. The merge left both repository variables unset; the existing feature parser
therefore kept both controls disabled in the bundle state evidenced by this change. This is neither
a deployment nor a runtime proof, and it does not close Wave 10.2.

**User-visible state:** there is no production-exposed functional Asset Picker today.

**Wave 9 reconciliation (PR #232):** the assembled-stack CI wiring is merged and final CI run
[34018608256](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34018608256)
passed `docker-build-verify` and `ci-required`; body-edit guard run
[34020180243](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34020180243)
also passed. Wave 9 remains not deployed and has no Production E2E claim.

**Wave 8 Task 8.1 provenance:** source `6a171558`, including `updatedAt` and decimal-string
serialization, is included in the tracked cu4 digest
`sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023`, serving revision
`0000094`. This is recorded provenance, not a fresh read-back; no duplicate 8.1 deployment is
needed.

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
disabled in source; page-level placement is final, while backend readiness and exposure remain open. No new
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
| **B — B1 portfolio composition backend** | Deployment prerequisites, fixture identity migration, legacy writer retirement, gateway provisioning, V20, version-bearing read, version-required seed, Wave 7 controller/tests, R-C preparation tooling, R3 closure, GC.5 source-governance closure, immutable candidate evidence through Task 7.6, accepted Task 7.7 serving evidence, Task 7.8 owner GO, Task 7.9 exact-digest serving proof, and Task 7.10 owner GO recorded locally | **Tasks 7.1–7.11 complete locally.** `portfolio-service--0000096` serves the exact R-C manifest at 100%; the single authenticated no-op PUT was `SAME_STATE`. P11g-1 is established for the transitional range; Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. The workflow comparator covered unselected app revision/image/traffic and refresh-job image; both Kafka groups were lag zero with DLT 80 | Publication remains separate until explicitly authorized; no additional production operation or rollback occurred during Task 7.11 |
| **C — B2 Asset Picker product** | Requirements, design, task plan, five-screen visual mockup, Waves 1–6 source, Wave 8 source/tooling, Task 3.7's Azure presence GO, Task 8.8 scoped gateway deployment, Wave 9 local real-stack integration, Task 6.3's green backend-readiness evidence, Task 10.1's Azure build-flag wiring, and Task 2.7's independently ACCEPTed historical audit | Task 3.7's current Azure proof is complete on `api-gateway--0000081`; Task 8.8/8.8b deployment evidence passed on `api-gateway--0000079` in run `34433715705`; Task 10.1 merged through PR #259 without creating or changing either variable; Task 8.9 live serving proof remains open. Wave 9 Tasks 9.1–9.9 carry their recorded source/local evidence; PR #232 at `main@318f2859` passed the disposable Compose real-browser run 5/5 and required CI wiring | The combined Wave 5/Wave 8 gateway artifact is deployed hidden; no Production E2E or exposure is claimed. Task 2.6, 8.9, and Wave 10.2 remain open; production flags remain off |
| **D — Demo credibility** | Canonical prices refreshed and reconciled; demo initializer exists; authorized 9.12 retry activated the Active_Asset set | Demo portfolio holds the exact 159-holding golden set after Task 4.5's one-call live proof on `portfolio-service--0000093`; version remained `0` under the valid same-state no-op; both flags remain `false`; historical pooled-session setter remains unidentified | 9.14 and the custom-domain restore are complete; B1 G5 closed by owner decision on 2026-09-02 using run 33411410271. Operational 9.12 success does not close historical RCA |

### What is actually usable today

| Capability | Status |
|---|---|
| Canonical Active Asset catalog inside services | ✅ Shipped |
| Repaired and reconciled price data | ✅ Shipped and verified |
| Enforcement against unsupported holdings/events | ✅ Enabled |
| `GET /api/assets` serving catalog data | ✅ Wave 2 gateway `/api/assets/**` route served with R-A; Wave 4b controller served with R-B2 Artifact 2a (`portfolio-service--0000081`) |
| Version-bearing portfolio read | ✅ G2a/R-B2 originally green on `portfolio-service--0000081` / `sha256:d544649f…`; contract retained on the recorded B2 Task 4.5 revision `portfolio-service--0000093`; caller migration 5.4–5.6 on `main@0b5d60d1`; **5.7/G5 complete by owner decision on 2026-09-02**, using the three-caller [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) and reviewed evidence PR #197 |
| `PUT /api/portfolio/holdings` safe composition write | ✅ Present and served by Task 7.9's exact R-C digest on `portfolio-service--0000096`; the existing gateway wildcard makes it publicly reachable. Tasks 7.1–7.11 are locally complete; P11g-1 is established for the transitional range, while Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. Publication remains separate until explicitly authorized |
| Asset Picker button/modal/browse/review/conflict UI | 🟡 Source merged behind a disabled-by-default flag; real disposable-stack integration passed locally/CI, not deployed/live |
| Asset Picker full-stack E2E proof | 🟡 Disposable Compose real-browser proof passed 5/5 through PR #232; Production E2E has not run |
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
| 4 – contract implementation | R-C public composition PUT served under Task 7.9 | Wave 4a–4c (4.1–4.21) merged on `main@2673f40` (PR #153). Task 7.9 deployed the exact candidate digest as `portfolio-service--0000096` and proved one authenticated same-state public composition PUT; the existing gateway wildcard makes it immediately reachable. Tasks 7.1–7.11 are locally complete; P11g-1 is established for the transitional range, while Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. Publication remains separate until explicitly authorized |
| 5 — version-bearing read | ✅ Tasks 5.1–5.3 / R-B2 complete; 5.4–5.6 merged via PR #161 at `main@0b5d60d1`; **5.7/G5 complete by owner decision on 2026-09-02** | [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) from `main@f66d7ab6` executed all three callers with version markers, holdings-only seed, and 9 passing tests; evidence reviewed/merged via PR #197. Historical failures and close-out: [G5 record](../runbooks/B1_G5_INGRESS_BLOCKER.md) |
| 6 — version-required seed | ✅ Tasks 6.1–6.7 complete locally; R-B3 owner GO recorded 2026-09-03 | Strict version boundary and identity-preserving replacement are on main. 6.5 owner GO recorded; read-only preflight confirms the existing cut. cu4 deployed as revision 0000094; 6.6 technical ACCEPT from one same-state seed; 6.6 complete and 6.7 owner GO recorded; publication remains separate |
| 7 — activation | ✅ Tasks 7.1–7.11 complete locally | Exact candidate evidence remains bound to `main@8f1e8a36`; Task 7.9 deployed its exact manifest from `main@5fd1dac6` to `portfolio-service--0000096` at 100% with serving, scoped workflow-comparator, and same-state PUT evidence ([record](../runbooks/B1_R_C_TASK_7_9_SERVING_PROOF.md)). P11g-1 is established for the transitional range; Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. Publication remains separate until explicitly authorized |

Spec A V17–V19 were applied at checkpoint 9.6; **V20 is applied under R-B** and unchanged by R-B2.
**R-A / G2**, **R-B / G3**, and **R-B2 / G2a** are complete. Wave 4 composition write mechanisms
are now served by the R-C portfolio revision; the public `PUT` source is merged, and candidate
execution plus packaging evidence is green through Task 7.6 and Task 7.7 serving recollection is
independently accepted complete. Task 7.8 owner GO is recorded; Task 7.9 exact-digest serving proof is complete,
while Task 7.10 owner GO is recorded locally. Caller migration
source **Tasks 5.4–5.6 are on `main@0b5d60d1`** (PR #161); **5.7/G5 is complete** under the
owner's 2026-09-02 decision. Run [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
from `main@f66d7ab6` recorded all three callers; PR #197 merged the reviewed evidence at
`main@b6c0da3`. No caller/helper/workflow/test drift exists through `main@48d0aba8`, and the
inventory guard passes. Unattended synthetics remain suspended. B1 Wave 6 source is complete and
6.5 has owner GO; cu4 deployment, G2b serving proof and owner R-B3 GO are complete. The deployed
seed requires expectedVersion. R3 and GC.5 are closed on current `main`; Tasks 7.1–7.2 are complete.
Task 7.9 release execution and exact-digest serving proof are complete. Task 7.10 owner GO is
recorded locally. Tasks 7.1–7.11 are locally complete. P11g-1 is established for the transitional
range; Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and
rollback artifacts at or above R-B3r. Publication and later gates remain separately closed. **Do not treat a
current-`main` portfolio deploy as a substitute for an authorized Artifact cut.**

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
| `cursor/b1-wave4a-composition-core` / PR #153 | **Historical merge-time state:** merged on `main@2673f40` – Wave 4a–4c tasks 4.1–4.21. At that merge, the read-only catalog path was served via Artifact 2a and composition write mechanisms were unexposed; no public `PUT` | Superseded by Task 7.9: `portfolio-service--0000096` serves the public composition PUT through the existing gateway wildcard. Tasks 7.1–7.11 are locally complete; P11g-1 is established for the transitional range, while Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. Publication remains separate until explicitly authorized |
| PR #155 / R-B2 | **Complete for 5.1–5.3** — Task 5.1 on `main@f22e2ff`; Artifact 2a serving on `portfolio-service--0000081` / `sha256:d544649f…` ([run 32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866); [`B1_R_B2_G2A_SERVING_PROOF.md`](../runbooks/B1_R_B2_G2A_SERVING_PROOF.md)); G2a green | Tasks 5.4–5.6 subsequently merged source-only via PR #161; any future portfolio rollout invalidates G2a until re-proven |
| PR #161 / G5 | **Caller source merged on `main@0b5d60d1`; 5.7/G5 complete by owner decision on 2026-09-02.** [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) succeeded from `main@f66d7ab6` with all three markers; PR #197 merged the reviewed evidence at `main@b6c0da3`. [Close-out record](../runbooks/B1_G5_INGRESS_BLOCKER.md) | G5 prerequisite satisfied; Wave 6 and R-B3 subsequently completed under separate approvals; public `PUT` and schedule restoration remain separate |
| PR #217 / B1 Wave 6 source | **Merged on `main@d66bb23d`**; Tasks 6.1–6.4 checked, Codex ACCEPT at `1bdb1d31`, R1/R2 closed, final PR-event CI successful | 6.5 owner GO; cu4 deployed and 6.6 G2b technical proof ACCEPT; 6.6/6.7 checked under owner R-B3 GO; unpublished local records |
| PRs #222, #234–#237 / R-C preparation and status reconciliation | **Merged through `main@8f1e8a36`**; candidate tooling accepted, R3 closed, GC.5 closed, Windows checkout identity fixed, and the master plan reconciled to the release cut | The unchanged cut has candidate evidence through Task 7.6 ([checkpoint](../evidence/b1-r-c/candidate-evidence-checkpoint-20260908.json)); PR #240 at `main@5b438aed` merges final Task 7.7 evidence and the owner-GO record closes Task 7.8 locally. The separately approved Task 7.9 proof deployed the exact manifest from `main@5fd1dac6` ([record](../runbooks/B1_R_C_TASK_7_9_SERVING_PROOF.md)); Task 7.10 owner GO is recorded locally in the [close-out runbook](../runbooks/B1_R_C_TASK_7_10_POST_DEPLOY_STOP_GO.md). Tasks 7.1–7.11 are locally complete; P11g-1 is established for the transitional range, while Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. Publication remains separate until explicitly authorized |
| [`proof/b1-wave-2-g1-v20@e6a98c5`](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/tree/proof/b1-wave-2-g1-v20) | Historical dependent dual-schema proof branch; superseded for Wave 3 delivery by `cursor/b1-wave3-v20-schema` | Remain unmerged; no release action |

### Process-control status

| Item | Current state | Required before relying on it |
|---|---|---|
| Status-propagation CI guard | Contract tests in `static-guard`; live PR-body check in dedicated `master-plan-status-propagation` workflow (`opened`/`synchronize`/`reopened`/`edited`) | Process-control only; does not advance the runtime baseline or create user-facing Asset Picker capability |
| Docs-only CI fast path | **Complete** via PRs #198–#199 on `main@3396ec45`; `ci-required` is the eighth required context, while all seven previous contexts remain required. PR #202 added `azure-image-smoke-test` as an eighth `ci-required` dependency (transitive, not branch-protection); probe PR #200 proved the original four-job docs-only skip shape, PR #199 proved the full-suite shape, and PR #204 proved the current five-job docs-only shape | Keep the classifier fail-closed and preserve declared-versus-observed equality. DAG de-serialization and broader path selection stay deferred unless CI latency begins blocking delivery |

The temporary product state is intentional but incomplete: the unsafe legacy writer is gone, and the
safe versioned replacement now serves in Task 7.9's exact R-C digest and is publicly reachable
through the existing gateway wildcard. The Asset Picker frontend remains disabled and unexposed to
users; Tasks 7.1–7.11 are locally complete. P11g-1 is established for the transitional range,
while Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and
rollback artifacts at or above R-B3r. Publication and the remaining B2 live/production gates stay
separately gated.

## 4. Track C — B2 Asset Picker product

Authorities:

- [requirements](../../.kiro/specs/asset-picker-composition/requirements.md)
- [design](../../.kiro/specs/asset-picker-composition/design.md)
- [implementation tasks](../../.kiro/specs/asset-picker-composition/tasks.md)
- [visual mockup](../../.kiro/specs/asset-picker-composition/mockup/asset-picker-design.html)

**Source/deployment baseline:** Wave 1 (1.1-1.19) and Wave 2 Tasks 2.1-2.5 merged source-only on `main@38e3d95` through PR #178 after two external review rounds and regression fixes. That frontend remains entirely mock-backed and disabled by default; the merge did not authorize live endpoint wiring, deployment, or production exposure. Wave 3 presence source Tasks 3.1–3.6 merged source-only via PR #179 at `main@cc97a209` with a configurable 150-second default TTL; Task 3.7's later owner-controlled Azure proof is GO on `api-gateway--0000081` and is recorded below.

Wave 4 Tasks 4.1–4.4a merged via PR #180 at `main@63fc058`; that exact historical cut was built immutably and digest-deployed to internal-only `portfolio-service--0000093`, and Task 4.5's controlled live proof is GO ([evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md)). Standalone Task 5.1a (`InternalApiKeyProvider`) merged source-only via PR #202 at `main@64761dc2`, and standalone Task 5.1b (`ReplicaTokenProvider`) merged source-only via PR #208 at `main@f954b5a7`. Wave 8 Task 8.1 standalone PR #185 was source-only at merge; its behavior is included in tracked cu4 source 6a171558, digest sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023, serving revision 0000094 (provenance only, not fresh read-back; no duplicate deployment needed), and standalone Task 8.2a (`CloudFrontOriginSecretProvider`) merged source-only via PR #203 at `main@addd8049`; Task 8.2a remains standalone/source-only and is not deployed.

**Task 3.7 evidence (2026-09-11):** Task 3.7 is GO on
`api-gateway--0000081` / `sha256:090ad3ba4b7a…`; the full evidence is
[recorded here](../evidence/b2-task-3-7/TASK_3_7_AZURE_PRESENCE_STOP_GO_2026-09-11.md).

| Wave | Status | Dependency note |
|---|---|---|
| 1 — mock-backed picker shell | ✅ Source merged (1.1-1.19), mock-backed only; not deployed/live | Feature flags, modal, browse/draft/review/conflict UX, mocked save/freshness/presence; PR #178 / `main@38e3d95` |
| 2 — decimal adapter | 🟡 Task 2.7 historical audit complete — Astra ACCEPT 2026-09-10 | PR #178 / `main@38e3d954` is source provenance, not serving proof. The [Task 2.7 audit](../evidence/b2-task-2-7/historical-containment-audit-20260910.md) bounds containment only at the documented ACA path; impact remains unproven, not impossible, with frontend artifact, embedded-origin, cache, and rollback identities unresolved. Retain mandatory numeric compatibility; Wave 10.2 item 2 remains unsatisfied |
| 3 — Redis-backed presence | ✅ Tasks 3.1–3.7 complete; Azure proof recorded on 2026-09-11 | `api-gateway--0000081` / `sha256:090ad3ba4b7a…`; default TTL **150s**, live key TTL 178–180s |
| 4 — portfolio-service demo reset | ✅ Complete — Tasks 4.1–4.4a merged via PR #180 / `main@63fc058`; Task 4.5 live GO | Exact cut serves internally on `portfolio-service--0000093` / `sha256:9a1d5533…`; one authorized same-state reset returned `200`, exact golden 159/159, unchanged version `0` per B1; [evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md) |
| 5 — manual-reset gateway bundle | ✅ Source Tasks 5.1–5.5 plus 5.1a/5.1b are merged, Task 5.6 owner GO is recorded, and the complete bundle was shipped hidden with the approved Task 8.8 scoped gateway deployment | Deployment run `34433715705` placed the bundle on `api-gateway--0000079` at `sha256:aee44edc…`; Task 6.3's current gateway-read evidence binds successor `api-gateway--0000081`. The frontend control remains disabled and Wave 10 exposure remains separate |
| 6 — manual reset frontend | ✅ Tasks 6.1/6.2 merged via [PR #214](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/214) at `main@48d0aba8`, identical to CI-green head `b918ff09`; ACCEPT, R1–R4 closed | Committed flags off; the owner finalized the existing page-level placement on 2026-09-06. Task 6.3 is green on [bounded gateway-read evidence](../evidence/b2-task-6-3/backend-readiness-gateway-read-20260911.json); Wave 10 remains closed. The owner-deferred [sidebar backlog](../todos/backlog/responsive-dashboard-sidebar/README.md) remains open |
| 7 — decimal rollout note | ℹ Informational | No independent release gate |
| 8 — login-orchestrated reset | 🟡 Tasks 8.1–8.8 and 8.8b are complete for Azure. Run `34433715705` deployed historical `api-gateway--0000079` at `sha256:aee44edc…`; current-attempt and non-interference proofs passed, and the revision was later superseded and purged. PR #262 reconciled Task 8.9's current [per-service provenance packet](../evidence/b2-task-8-9/deployment-provenance-20260911.json) to `api-gateway--0000081` / run `34588465283`, retaining the independent `portfolio-service--0000096` / run `34328692256` attestation with no common workflow identity. The explicit 2026-09-11 [re-preflight evidence](../evidence/b2-task-8-9/rehearsal-20260911.json) matched both identities and workspace, then stopped fail-closed at the scaled-to-zero gateway before exec, with no wake, HTTP, or write. [Decision record](../superpowers/plans/2026-09-06-b2-wave8-decision-record.md), [deployment evidence](../evidence/b2-task-8-8/deployment-completion-20260910.json), and [prior rehearsal record](../evidence/b2-task-8-9/rehearsal-20260910.json). | Task 8.9 live login/reset/log-correlation proof remains open and separately gated. A bounded gateway wake requires a separate owner decision before rehearsal can continue past the exec probe. Task 8.8a remains AWS-only and does not apply to this Azure deployment. Production flags remain off |
| 9 — live integration | 🟡 Tasks 9.1, 9.3, 9.4, 9.5 (PR #231 at `main@b4c68253b99a796d6301ef79b5aa5a47d5cbd962`), and 9.6 have their recorded source/local evidence. Tasks 9.2/9.7/9.8/9.9 are source/assembled-stack complete: one disposable Compose real-browser run passed 5/5 across setup plus picker and demo-reset success/conflict; PR #232 merged at `main@318f28592da6ab2e3bd66bc738aa68d374b180fa`, with final CI run `34018608256` passing `docker-build-verify` and `ci-required`, and body-edit guard run `34020180243` passing | No deployment or Production E2E is claimed; the B2-specific Wave 10 convergence/exposure gate and Production E2E still gate exposure. This is not the already-proved B1 P11g-2 property; production flags remain off |
| 10 — production exposure | 🟡 Task 10.1 source wiring complete; exposure blocked | PR #259 / `main@03ca6300` passed its CI contract without creating or changing either repository variable. Wave 10.2 still requires B2 live decimal fidelity, Task 8.9 serving proof, explicit owner approval, a new build/deploy, and Production E2E |

**Wave 8 status correction (2026-09-07; superseded historical snapshot):** Task 8.1 provenance is source `6a171558`, cu4/revision
`0000094`, digest `sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023`; this is
historical provenance rather than fresh serving read-back, and no duplicate deployment is needed.
Tasks 8.3–8.7a, including authentication/cardinality and real-chain evidence, are source-complete and
independently accepted. At that snapshot, remaining Wave 8 gates were deployment/live proof
(8.8/8.9), not source orchestration prerequisites; Task 5.6 GO and B1 R-C deployment/convergence
remained open. **Current status:** B1 R-C Tasks 7.1–7.11 are locally complete, and
Writer_Convergence / P11g-2 is proved only for the activated exact R-C artifact and rollback artifacts
at or above R-B3r. Task 5.6 GO and Task 8.8/8.8b deployment evidence are now complete. The active
B2 gates are live decimal-fidelity evidence, Task 8.9's live serving proof, the B2-specific Wave
10 convergence/exposure gate, and Production E2E; production
flags remain off.

The aggregate `assetPriceFreshness` backend dependency is now **closed**: Spec A task 8.6 is
complete and `PortfolioSummaryDto.assetPriceFreshness` exists. B2 Wave 1 delivered the mocked
frontend adapter; Task 9.5 has local assembled-stack evidence that the Portfolio page consumes the
real summary object. Combined Wave 9 assembled-stack evidence is complete, and PR #232 CI run
`34018608256` passed `docker-build-verify` and `ci-required`; deployment and Production E2E remain
open.

### B2 decision status

The owner resolved the Wave 8 product/operational decisions on 2026-09-06: strict 30-minute
persisted idle age, 2s eligibility / 2s reset / 4s overall timeouts, and page-level manual reset.
Task 2.7's historical backend-before-adapter containment/frontend-artifact audit is complete with
Astra's 2026-09-10 ACCEPT, bounded only at the documented ACA path. Numeric compatibility remains
mandatory, and Wave 10.2 item 2 remains unsatisfied. See the
[decision record](../superpowers/plans/2026-09-06-b2-wave8-decision-record.md).

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

**Owner priority: complete the Asset Picker production path before deferred CI optimization.**
The exact `main@8f1e8a36` Task A graph, single candidate build/push, ACR platform digest,
exact-digest smoke and final CANDIDATE artifact binding are complete through Task 7.6. The owner-
authorized Task 7.7 recovery and proof bundle now has all technical predicates green: immutable
serving digests, transparent retained-range recovery/current lag zero with DLT non-growth, projection
consistency, complete startup coverage, provisioning/authenticated/retired-route proofs, exactly one
seed, final G3 and derived G6. Astra independently ACCEPTed the whole packet with no findings.
Task 7.8 owner GO is recorded at `main@5b438aed`; the separately approved Task 7.9 deployment
completed at `main@5fd1dac6` with exact-digest, traffic, non-interference and same-state PUT
evidence. Tasks 7.1–7.11 are locally complete: P11g-1 is established for the transitional range,
and Writer_Convergence / P11g-2 is established for the activated exact R-C artifact and rollback
artifacts at or above R-B3r only. Publication remains separate until explicitly authorized; no
additional production operation or rollback occurred during Task 7.11.

**B1 position:** R-B3 remains the production safety floor at R-B3r revision `0000095` /
digest `fa060bf0…`; the latest Task 7.9 serving release is portfolio revision `0000096` /
digest `1cf372a3…`. Tasks 7.1–7.2, candidate-preparation tooling, R3 and
GC.5 are complete on `main`; Tasks 7.3–7.6, including 7.5a, have current-cut
candidate evidence. Tasks 7.7–7.11 are complete locally. P11g-1 is established for the
transitional range; Writer_Convergence / P11g-2 is established for the activated exact R-C artifact
and rollback artifacts at or above R-B3r only. AM.1/AM.2 remain open. Publication remains separate
until explicitly authorized, and no additional production operation or rollback occurred during
Task 7.11. The R-C artifact is deployed only as the exact attested portfolio manifest; the public
controller is consequently reachable through the existing gateway wildcard.

**B2 position:** Wave 8 source and Azure proof tooling are merged through PR #233, Task 10.1's
Azure-only build-flag wiring is merged through PR #259 without creating or changing either repository variable, and Wave 9's
disposable Compose real-browser integration passed 5/5 through PR #232. These are source/local-CI
claims, except for the separately evidenced Task 8.8 scoped gateway deployment. Task 5.6 owner GO
and Task 8.8/8.8b deployment evidence completed on 2026-09-10 in run `34433715705`. Task 2.7's
historical audit is independently ACCEPTed; Tasks 2.6, 8.9 and Wave 10 remain open;
both production feature flags remain disabled. The owner-deferred
[sidebar issue](../todos/backlog/responsive-dashboard-sidebar/README.md) remains separate.

The documentation fast path is sufficient for this status reconciliation. CI DAG optimization
remains deferred unless delivery latency makes it a product blocker.

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
2. **Backend lane:** **R-A/G2, R-B/G3, R-B2/G2a and R-B3/G2b are complete.** Tasks 7.1–7.2
   are merged and reconciled complete. Candidate preparation tooling, R3 closure and GC.5 closure
   are merged through PRs #222 and #234–#236. The exact `main@8f1e8a36` Task A graph, candidate
   image/ACR manifest, exact-digest smoke and final CANDIDATE binding provide Task 7.3–7.6 evidence
with zero findings/unverified coverage. Task 7.7 is independently accepted complete; Task 7.8
owner GO, Task 7.9 exact-digest serving proof, and Task 7.10 owner GO are recorded locally.
Tasks 7.1–7.11 are locally complete: P11g-1 is established for the transitional range and
Writer_Convergence / P11g-2 is established for the activated exact R-C artifact and rollback
artifacts at or above R-B3r only. Publication remains separate until explicitly authorized; no
additional production operation or rollback occurred during Task 7.11.
3. **B2 product lane:** Waves 1–6 source have the recorded merge and review evidence; the exact
   historical Wave 4 cut remains deployed internally and Task 4.5 is GO. Wave 8 source and Azure
   proof tooling merged through PR #233 at `main@a52ec1ef`. Wave 9 source and disposable-stack
   browser integration are complete through PR #232 at `main@318f2859`, whose run `34018608256`
   passed `docker-build-verify` and `ci-required`. Task 5.6 owner GO and Task 8.8/8.8b deployment
   evidence completed on 2026-09-10 in run `34433715705`; Task 6.3 backend readiness is green, while
   Task 8.9 live serving proof and Wave 10 remain open. Both feature flags remain off, and no
   Production E2E or exposure is claimed.
4. **Process lane:** keep the status-propagation CI guard healthy in required `static-guard`; it is
   process-control only and does not advance the runtime baseline.
5. **Interim review arrangement (process, 2026-09-11):** while Codex plan limits hold, code review
   runs inside Claude Code — Opus 5 orchestrates, Sonnet 5/Opus 5 implement, Fable 5.1 reviews,
   and no model reviews its own work. Codex retains documentation, status tracking and final task
   reconciliation. Repository-scoped, reverts in one commit. No feature, runtime or deployment
   impact.

No item above is authorized merely by being listed. The implementation handoff must name the chosen first
task, its exact scope, predecessor evidence, stop condition, and whether it is documentation,
implementation, or a production operation.

**Completed source handoff (reconciled 2026-09-03):**
[Claude kickoff — B1 Wave 6 Tasks 6.1–6.4](../agent-instructions/CLAUDE_KICKOFF_B1_WAVE_6_VERSION_REQUIRED_SEED.md)
is retained as the historical execution plan for merged PR #217. Its implementation and final
review are complete; it is not a new assignment. Task 6.5 has owner GO and candidate build cu4
has succeeded. The owner-approved cu4 deployment and single-seed proof are now complete;
Task 6.6 has technical ACCEPT and the owner approved local completion of 6.6 and 6.7 R-B3 GO.
Existing credentials were sufficient. Publication, Wave 7 activation and B2 gates remain separate.

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
