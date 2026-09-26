# Asset Picker — E2E Master Plan to Production

**Roadmap/root README process reconciliation — 2026-09-26 UTC:**
[ROADMAP](../../ROADMAP.md), [README](../../README.md) and
[enhancements v5](../../roadmap_enahancements_v5.md) are prepared against `main@5d559478`.
They distinguish delivered picker/analytics capabilities from open residuals and three deferred
owner requests: per-user Sharpe/Sortino, richer FA/TA chat and additional charts. v1–v4 remain
historical snapshots. No feature implementation, new operational proof or Spec A/B1/B2 task
completion is claimed. Filing requires independent review and owner-authorized publication/
merge; an unmerged copy is a candidate. Runtime/demo acceptance remains governed by the
demo dashboard; this documentation candidate does not advance it.

**Runbook process reconciliation — 2026-09-26 UTC:** the
[runbook index](../runbooks/README.md) classifies all 24 existing runbooks; the 2 reusable
procedures are corrected against source, while the 3 legacy helpers and 19 historical records
remain unchanged. [Current operations](../runbooks/CURRENT_OPERATIONS.md) records dispatch,
demo startup and restart boundaries. No new operational proof or Spec A/B1/B2 completion is
claimed. This independently reviewed reconciliation is filed through #325 (`5d559478`).
The [demo dashboard](ASSET_PICKER_DEMO_PREPARATION_PLAN.md) retains current acceptance and the
remaining freeze work; the roadmap/README/v5 filing is separate.

**Backlog process reconciliation — 2026-09-26 UTC:** the
[backlog index](../todos/backlog/README.md) records 8 fixed/completed closures, 2 superseded
closures and 20 open items against `main@d515aa5b`. No Spec A/B1/B2 task completion box or
historical proof is changed. Current runtime/demo status is governed by the
[demo preparation dashboard](ASSET_PICKER_DEMO_PREPARATION_PLAN.md), including #323's published
post-#320 suite/cleanup result. Older baseline paragraphs below retain their historical dates;
they are not fresh serving-state assertions. The backlog index records the independent review;
the audit is filed through #324 (`6f1e5700`).

**Last verified:** 2026-09-21 at local candidate
`19fb82d552c57fe8b619a34fe7fb8b1f526797ea`, based on `main@07faf2c6`, for independently accepted
Demo Preparation Phase 2 product-SemVer source completion. The candidate establishes product
version `0.9.0`; at this reconciliation snapshot it remained local. Publication and merge require
separate owner authorization and green pull-request CI. The final uncontended Phase 2 visual/browser
verification and independent exit review must then pass before the separately authorized `v0.9.0`
tag and non-deploying GitHub pre-release rehearsal. Phase 3 remains a later, separate owner decision.
The owner accepted the Portfolio and Overview narrow-width overflows as open backlog debt outside
the desktop-only demo path.

**Current Production/runtime program-state code baseline:** 2026-09-20 at
`main@91f40bd0126f15fc87a6d6beb2ffa6bcd01e76d4` for the completed Wave 10.2 Step B exposure.
Frontend-only deploy run
[35489160653](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35489160653)
completed at `2026-09-20T04:30:28Z`; both repository-scoped flags are `true`. The accepted 5b
verifier returned `GO` at `2026-09-20T04:40:54Z`, within the 1,800-second bound, with all L0-L9
legs passed, golden-state cleanup confirmed, and pre/post backend revisions and digests unchanged.
Task 10.2 and Demo Preparation Phase 1 are complete. See the
[Production E2E record](../evidence/b2-wave-10-2/WAVE_10_2_STEP_B_5B_PRODUCTION_E2E_GO_2026-09-20.md)
and [demo preparation plan](ASSET_PICKER_DEMO_PREPARATION_PLAN.md).

**Previous verification history:** 2026-09-19 at
`main@9a01846f51807dd50917669d8891f435befca88b` (PR #292) for the merged Wave 10.2 Step B 5b
verifier's independent-acceptance record and successful post-merge CI run `35416813684`; the prior
Wave 9 Step A Attempt 3 gate-credit status and Wave 10.2 condition-5 amendment remain recorded.
Since the runtime/program-state code baseline below, `main` changed only documentation, tooling and
test-support files (`scripts/`, `frontend/tests/e2e/helpers/`, `.gitattributes`, `LICENSE`), so that
baseline is not advanced by this check;
2026-09-15 against runtime/program-state code baseline
`main@1f922a89643f5bb406dcdf471e8dc07a229960d6` for B2 Task 8.9's owner-authorized Decision 2
Production proof: the raw execute returned Class 2b because Windows `az.cmd` truncated multiline
KQL before its predicates, timespan, and JSON-output arguments. A first captured recovery retained
the correct success query but incorrectly queried `portfolio-service` for the gateway-owned skip
event and was rejected. One later owner-authorized query replayed the retained, time-bounded
`api-gateway` skip query byte-for-byte and returned zero rows. The combined raw rows reproduce
`go` / `retain_serving_revision`; Fable independently accepted the evidence at
`8544d722433108f0b71a164675271d2f81c7a6a8` (0 Critical, 0 Important, 8 Minor).
Task 8.9 Azure live proof is COMPLETE / GO. Fable independently accepted the documentation delta
at `fab158cb377bb2f6e079dd151bab0f28174eb34e` (0 Critical, 0 Important, 5 Minor). The owner
separately authorized PR #278's merge, completed at `main@6e0467419082de9dea8a1edbbbcb714fdd03b042`
on `2026-09-15T08:05:15Z`; this does not change the runtime/program-state proof baseline;
2026-09-15 at `main@8337d7e8b982245156088719d01f49b3ad1c1be9` for the merged pre-wake correctness hardening
(PR #275; exact-head Windows PowerShell 5.1 suite 286/286 and required aggregate CI green);
2026-09-14 at `main@a28c481a` for the Azure-specific timeout expectation, drift guard, and evidence
corrections (PR #273; Windows PowerShell 5.1 suite 244/244);
2026-09-13 at `main@80b881b5c461e1bebb210ebe1db4b911cf6a336d` for the predecessor cold-start
preflight wrapper (PR #268);
2026-09-13 at `main@a4fa8d076daabe73ce32a22179750e98d32f10bb` for Run A attempt 2's
NON-GO evidence (PR #267), at `main@e73ab8e9fdf6303dd4730171c7d8f28187761a75` for Run A attempt 1's NON-GO evidence (PR #265),
and at `main@6b9c70f6e4476e612d27bd1df320cf68e24dc76b` for the predecessor wrapper (PR #266);
2026-09-11 at `main@45c1275ba306cde19cb344f2954c1a43c3ec4952` for B2 Task 8.9's
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
therefore complete with 8.8. Task 8.9 was open at that deployment boundary; its later Decision 2
evidence is COMPLETE / GO at independently accepted head `8544d722`. Fable accepted documentation head `fab158cb`; PR #278 merged at `main@6e046741`. Wave 10 remains separately owner-gated, and both production
feature flags remain disabled. See the
[completion evidence](../evidence/b2-task-8-8/deployment-completion-20260910.json) and
[owner approval](../evidence/b2-task-8-8/owner-approval-20260910.json).

**TASK 8.9 PROVENANCE RECONCILIATION — PR #262 MERGED; LIVE PROOF ACCEPTED AT `8544d722` BELOW:** PR #248's source-only
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

**TASK 8.9 AZURE LIVE PROOF COMPLETE / GO AT FABLE-ACCEPTED `8544d722`;
DOCUMENTATION DELTA `fab158cb` ACCEPTED; PR #278 MERGED AT `6e046741`:**
[PR #268](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/268) merged at
`main@80b881b5c461e1bebb210ebe1db4b911cf6a336d`. The accepted head
`400ac3c8adebd3dae7a9d085152e280a301ec637` is its second parent and has the identical merge tree
(`e4e7c8ad`); the diff from that accepted head to the merge is empty. Against prior
`main@a4fa8d076daabe73ce32a22179750e98d32f10bb`, the reviewed scope is exactly
`scripts/run_task_8_9_preflight.ps1`, `scripts/tests/stub_curl.cmd`, and
`scripts/tests/test_run_task_8_9_preflight.ps1` (`+1390/-273`). GitHub reports PR #268 merged at
2026-09-13 15:09:08Z.

Two owner-authorized Run A attempts were consumed before PR #268. Attempt 1's
[PR #265 evidence](../evidence/b2-task-8-9/run-a-attempt-20260912.md) records a single wake returning
`503` after 56.374 seconds; the verifier was then run contrary to the packet's non-`200` stop rule
and ended `class_2a` / `non_go`, exit `1`. PR #266 merged the predecessor wrapper at
`main@6b9c70f6`. Attempt 2's [PR #267 evidence](../evidence/b2-task-8-9/run-a-attempt-20260913.md)
and [captured transcript](../evidence/b2-task-8-9/run-a2-operator-transcript-20260913.txt) record one
more wake returning `503` with curl exit `0`; that wrapper failed closed with exit `3` before its
replica wait or verifier, and no rehearsal JSON was written. Both attempts are NON-GO and neither
advances Task 8.9.

The wrapper is a preflight-only cold-start guard: it can issue at most six fixed direct health
probes, each limited to 90 seconds and separated by the production five-second interval. Its curl
vector starts `-q --noproxy '*' -sS`. It retries only when the result is HTTP `503` with curl exit
`0`, or a curl timeout (exit `28` with status `000`). It stops at the first exact `200` and otherwise
exits `3` without invoking the verifier. It invokes only
verifier mode `preflight`; it does not run Task 8.9, use credentials, alter flags, or establish
live serving proof.

**Run A attempt 3 — 2026-09-14, NON-GO:** the third authorized wake succeeded and all 14 read-only
Azure operations completed, including replica resolution, `containerapp exec`, RBAC rehearsal, ACR
manifest/login/pull access, and Log Analytics KQL. The verifier then exited `4` on the stale generic
`45s` eligibility-timeout expectation while the attested Azure deployment carried its intentional
`120s` override. The deployment was current and the verifier expectation was stale, as recorded in
the [Azure Production timeout ratification](../evidence/b2-task-8-9/2026-09-14-azure-timeout-ratification.md).
The attempt consumed the third wake but produced no accepted preflight verdict and does not advance
Task 8.9.

PR #273 merged the Azure-specific `120s/30s/165s` expectation, pre-wake timeout read-back, drift
guard, and evidence corrections at `main@a28c481a`; its Windows PowerShell 5.1 suite passed 244/244.
PR #275 merged the remaining pre-wake response-ceiling, idle-threshold, provider, ordinal comparison,
key-case, and hostile-Unicode sanitisation corrections at
`main@8337d7e8b982245156088719d01f49b3ad1c1be9`. Its exact-head Windows PowerShell 5.1 suite passed
286/286, all five pre-registered Unicode fixtures ran and passed, and the required aggregate CI gate
was green. These merges performed no Production action and granted no live-run authority.

**2026-09-14 Azure Production timeout note:** The application-wide `45s/10s/60s` generic contract
remains unchanged. The attested Azure Production deployment uses `120s/30s/165s` Terraform overrides
under a `150s` route ceiling per the
[ratification](../evidence/b2-task-8-9/2026-09-14-azure-timeout-ratification.md). This pointer changes no
Task 8.9 status or Production authorization.

**Run A attempt 4 — accepted preflight, still not Task 8.9 GO:** the owner-authorized Windows
PowerShell 5.1 run used two bounded probes (`503`, then `200`) and stopped at the first `200`. The
wrapper completed 15 operations, all `mutating:false`, and exited `0`; the evidence records
`preflight_passed`, `go:null`, no errors, both attested serving revisions/digests, and the approved
Azure timeout/response-ceiling/idle/provider values. It used no Task 8.9 application credentials,
performed no login or portfolio mutation, created no revision, and changed no flag or deployment.
See the [attempt record](../evidence/b2-task-8-9/run-a-attempt-20260915.md),
[sanitized transcript](../evidence/b2-task-8-9/run-a-attempt4-operator-transcript-20260915.txt), and
[preflight JSON](../evidence/b2-task-8-9/run-a-attempt4-preflight-20260915.json). The one delayed
replica read still found one Running replica, so the 300-second scale-to-zero assumption remains
unverified without invalidating the accepted preflight. Six accepted non-blocking follow-ups remain in the
[Task 8.9 wake-preflight hardening backlog](../todos/backlog/task-8-9-wake-preflight-hardening/README.md).
The owner separately authorized the credential-using execute proof and evidence publication on
2026-09-15. From clean `main@1f922a89`, the final bounded sequence passed its same-run preflight,
used no threshold override, advanced the demo portfolio from version `4` to deliberate non-golden
version `5`, waited `1800.001` seconds, and performed the traced Production login. The login returned
`200`; the identity-checked read observed golden version `6`; unconditional cleanup succeeded on its
first reset and independently confirmed golden version `6`; and final serving revalidation matched
both attested revisions and digests.

The unchanged raw verifier artifact exited `1` / `class_2b` because Python resolved `az` to
`az.cmd` and passed multiline KQL through the shim's parenthesized `%*` expansion. The first newline
terminated the intended Azure CLI invocation: only the table name reached it, while the predicates,
`--timespan`, and `-o json` did not. The later UTF-8 decoder exception was a downstream symptom. The
first manual recovery summary was independently rejected because it retained no raw stdout, full
`Log_s`, effective argv, timespan, or replay timestamps.

Following fresh owner authorization, two historical queries were issued once each with zero
retries, directly through the same installed `azure.cli` module to bypass the defective `.cmd`
transport. The success query was byte-identical to the retained query and its captured stdout contains
one full-payload `demo_reset_succeeded version=6` row from `portfolio-service--0000096`. The replay
launcher incorrectly derived its skip query by replacing only the event name, leaving the
`portfolio-service` app filter; its `[]` result was rejected and supplies no gateway evidence.

After a second explicit owner authorization, exactly one additional read-only query replayed the
retained `api-gateway` skip query byte-for-byte with the same workspace, trace, UTC window, direct
transport, and raw process-stream capture. It exited `0`, returned `[]`, and recorded one invocation
with zero retries. Across both accepted inputs, the success row and corrected zero-row gateway
result establish `a_success_only`. Cleanup occurred inside the UTC query window but is excluded by
the login trace-id predicate; time is not the exclusion. No recovery performed a login, mutation,
wake, deployment, or cleanup. The tracked parser and classifier reproduce candidate `go` /
`retain_serving_revision`, with rollback false. See the
[Decision 2 record](../evidence/b2-task-8-9/run-b-decision2-20260915.md),
[corrected recovery artifact](../evidence/b2-task-8-9/run-b-decision2-class2b-recovery-20260915.json),
[initial replay transcript](../evidence/b2-task-8-9/run-b-decision2-class2b-replay-operator-transcript-20260915.txt),
[corrected gateway-skip transcript](../evidence/b2-task-8-9/run-b-decision2-class2b-replay-r2-gateway-skip-operator-transcript-20260915.txt),
[unchanged raw execute artifact](../evidence/b2-task-8-9/run-b-decision2-execute-raw-20260915.json),
and [operator transcript](../evidence/b2-task-8-9/run-b-decision2-operator-transcript-20260915.txt).
Fable independently accepted this evidence at `8544d722`: **Task 8.9 Azure live proof is COMPLETE /
GO; retain serving revisions, rollback false**. See the
[acceptance and eight-Minor reconciliation record](../evidence/b2-task-8-9/run-b-decision2-fable-acceptance-20260915.md).
Fable accepted documentation head `fab158cb`; PR #278 merged at `main@6e046741`. Wave 10
remains closed on its other prerequisites and owner exposure decision; both Production flags remain
disabled. No further merge is authorized.

**B2 TASK 10.1 SOURCE WIRING — MERGED THROUGH PR #259; EXPOSURE CLOSED:**
PR #259 merged at `main@03ca63000a16f38484a37cc85ab938a0ad7874c2`. Its two-file Azure-only change
maps both static-export `NEXT_PUBLIC_*` controls to GitHub Actions repository-variable names and
adds a fail-closed structural contract for their exact build-step locality and expression shape.
CI run [34622304228](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34622304228)
passed `static-guard`, `sanitizer-canary`, `deploy-workflow-contract`, `docker-build-verify`, and
`ci-required`; the frontend checks and master-plan-status-propagation also passed. The merge did
not create, read, or change either variable. No workflow dispatch, cloud/secret access, deployment, or feature
exposure occurred. Task 10.1 enables a later owner-controlled build only; Task 8.9 and Wave 10.2
remain separate gates, and Wave 9's Production E2E is Wave 10.2's Step B exit criterion 5b.

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
and the then-deferred [sidebar backlog](../todos/backlog/responsive-dashboard-sidebar/README.md)
remained separate from that delivery. It was later resolved as Demo Preparation Phase 2.1 through
PR #297 without reopening Wave 6. The bounded gateway-read attestation is recorded only for Task 6.3.

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

**User-visible state (2026-09-20):** Asset Picker and the page-level demo-reset control are exposed
in the served Production frontend. The complete bounded Production browser verifier is `GO`; the
demo portfolio was restored to its exact 159-holding golden state, and rollback was not required.

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
  then-current five-job skip shape. PR #271 adds `task-8-9-powershell-tests` (windows-latest) as a
  sixth chain job; its docs-only skip shape has not yet been probe-proven. This is process control
  only and does not advance any Asset Picker task or runtime baseline.

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
| **A — Spec A catalog/data cutover** | Shared catalog, Postgres/Mongo repair, R4 rollout, enforcement, one reconciled controlled refresh, persisted refresh enablement, demo portfolio activation, and scale-to-zero restoration | **All 14 cutover checkpoints complete.** 9.13 completed on `portfolio-service--0000092`, `market-data-service--0000079`, and `insight-service--0000079`; B2 Task 4.5 later superseded only the portfolio revision with `portfolio-service--0000093`. 9.14 completed via apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603), reopening ACA external ingress on `api-gateway--0000077` with `allowInsecure=false` ([`SPEC_A_9_14_REOPEN_INGRESS.md`](../runbooks/SPEC_A_9_14_REOPEN_INGRESS.md)); the later custom-domain restore has independent `200` read-back, and PR #194 independently reviewed and merged that evidence ([`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md)); historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` | Spec A's production cutover is done. B1 G5 closed by owner decision on 2026-09-02 using the reviewed three-caller run. B1 Wave 6 Tasks 6.1–6.4 are source-complete through PR #217; the custom-domain backlog is closed by the 2026-09-26 audit; three other filed process follow-ups remain open |
| **B — B1 portfolio composition backend** | Deployment prerequisites, fixture identity migration, legacy writer retirement, gateway provisioning, V20, version-bearing read, version-required seed, Wave 7 controller/tests, R-C preparation tooling, R3 closure, GC.5 source-governance closure, immutable candidate evidence through Task 7.6, accepted Task 7.7 serving evidence, Task 7.8 owner GO, Task 7.9 exact-digest serving proof, and Task 7.10 owner GO recorded locally | **Tasks 7.1–7.11 complete locally.** `portfolio-service--0000096` serves the exact R-C manifest at 100%; the single authenticated no-op PUT was `SAME_STATE`. P11g-1 is established for the transitional range; Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. The workflow comparator covered unselected app revision/image/traffic and refresh-job image; both Kafka groups were lag zero with DLT 80 | Publication remains separate until explicitly authorized; no additional production operation or rollback occurred during Task 7.11 |
| **C — B2 Asset Picker product** | Requirements, design, task plan, five-screen visual mockup, Waves 1–9 implementation/integration evidence, Tasks 3.7/4.5/5.6/6.3/8.8/8.9 live gates, Task 10.1 build-flag wiring, Task 2.7 audit, B1 Task 4.9 decimal-fidelity proof, Wave 10.2 production exposure, and post-delivery Phase 2.1 responsive-shell source | **Delivered and exposed.** Frontend-only run [35489160653](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35489160653) deployed `main@91f40bd0`; both flags are `true`. The served build changed to `mE3_OA6woqSxKOZS4H13q`. Step B exit criterion 5b returned `GO` with all L0-L9 legs passed, exact persisted readback, golden-state reset, and unchanged backends. PR #297 later merged Phase 2.1 source at `main@4f288c4a`; it is not deployed. At the 2026-09-21 snapshot, the independently accepted `0.9.0` SemVer candidate remained local at `19fb82d5` | Task 10.2 and Demo Preparation Phase 1 are complete. Task 2.6 remains mandatory compatibility debt but does not block the delivered feature. The narrow-width overflows are accepted open backlog debt outside the desktop-only demo path. SemVer publication and merge require separate owner authorization and green PR CI; then complete the Phase 2 exit verification/review before separately authorized Task 7 and Phase 3. No Asset Picker delivery gate is reopened |
| **D — Demo credibility** | Canonical prices refreshed and reconciled; demo initializer exists; Asset Picker is exposed; the deployed picker/save/reset journey passed Production browser E2E; Phase 2.1 shared-shell source is merged | Demo portfolio is back at the exact 159-holding golden set; both controls render in the served frontend; both flags are `true`; backend revisions/digests were unchanged by the frontend-only deployment. The responsive shell is verified locally and in CI but is not yet Production-served. At the 2026-09-21 snapshot, the `0.9.0` SemVer source was locally accepted | Asset Picker is ready for demonstration. Under separate owner authorization, publish and merge SemVer; complete the Phase 2 exit verification/review; rehearse the non-deploying pre-release; then run desktop Phase 3 and remediate or explicitly accept its findings. Documentation/media packaging remains Phases 5–6 |

### What is actually usable today

| Capability | Status |
|---|---|
| Canonical Active Asset catalog inside services | ✅ Shipped |
| Repaired and reconciled price data | ✅ Shipped and verified |
| Enforcement against unsupported holdings/events | ✅ Enabled |
| `GET /api/assets` serving catalog data | ✅ Wave 2 gateway `/api/assets/**` route served with R-A; Wave 4b controller served with R-B2 Artifact 2a (`portfolio-service--0000081`) |
| Version-bearing portfolio read | ✅ G2a/R-B2 originally green on `portfolio-service--0000081` / `sha256:d544649f…`; contract retained on the recorded B2 Task 4.5 revision `portfolio-service--0000093`; caller migration 5.4–5.6 on `main@0b5d60d1`; **5.7/G5 complete by owner decision on 2026-09-02**, using the three-caller [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271) and reviewed evidence PR #197 |
| `PUT /api/portfolio/holdings` safe composition write | ✅ Present and served by Task 7.9's exact R-C digest on `portfolio-service--0000096`; the existing gateway wildcard makes it publicly reachable. Tasks 7.1–7.11 are locally complete; P11g-1 is established for the transitional range, while Writer_Convergence / P11g-2 is established only for the activated exact R-C artifact and rollback artifacts at or above R-B3r. Publication remains separate until explicitly authorized |
| Asset Picker button/modal/browse/review/conflict UI | ✅ Served in Production with both controls rendered; CI retains deterministic conflict coverage |
| Asset Picker full-stack E2E proof | ✅ Disposable Compose proof plus bounded Production browser verifier `GO` on 2026-09-20 |
| Asset Picker exposed to production users | ✅ Frontend-only deploy run `35489160653`; both repository-scoped flags `true` |
| Responsive shared dashboard shell | ✅ Source merged through PR #297 at `main@4f288c4a`; local Chromium matrix and PR CI green; not deployed or Production-verified |

The production data, catalog, enforcement, and deployment-safety foundation now supports the
user-facing picker. The accepted live browser run closes the feature-delivery boundary; broader
demo certification remains separately sequenced in the demo preparation plan.

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
  is closed by the 2026-09-26 fixed-item audit using the accepted recovery/G5 records; no new live observation.
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
| 9.14 reopen ingress | **Complete** — PR #184 / `main@66bbee0`; guarded read-only plan [33313072724](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33313072724) passed; apply skipped; reviewer orientation merged via [PR #187](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/187) | Plan review **ACCEPTed** 2026-08-31; apply [33331130603](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33331130603) passed the `production` gate and all twelve assertions. Live: external ingress on `api-gateway--0000077`, `allowInsecure=false`, default ACA endpoint healthy. The later custom-domain recovery and synthetic succeeded; Task 5.7 closed under its own owner decision on 2026-09-02. The custom-domain backlog is closed; three other process follow-ups remain open in `docs/todos/backlog/` |

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
| Docs-only CI fast path | **Complete** via PRs #198–#199 on `main@3396ec45`; `ci-required` is the eighth required context, while all seven previous contexts remain required. PR #202 added `azure-image-smoke-test` as an eighth `ci-required` dependency (transitive, not branch-protection); probe PR #200 proved the original four-job docs-only skip shape, PR #199 proved the full-suite shape, and PR #204 proved the five-job docs-only shape; PR #271 adds `task-8-9-powershell-tests` as a sixth chain job whose docs-only skip shape is not yet probe-proven | Preserve fail-closed declared-versus-observed equality. PR #306 merged the two-root DAG mechanism; complete experiment acceptance/timing evidence remains open for reconciliation. Broader frontend/backend selection remains deferred |

The temporary product state is intentional but incomplete: the unsafe legacy writer is gone, and the
safe versioned replacement now serves in Task 7.9's exact R-C digest and is publicly reachable
through the existing gateway wildcard. The Asset Picker frontend is now enabled and exposed through
the 2026-09-20 frontend-only deployment; Tasks 7.1–7.11 are locally complete. P11g-1 is established for the transitional range,
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
| 2 — decimal adapter | ✅ Wave 10.2 condition 2 complete — Task 2.7 Astra ACCEPT plus B1 Task 4.9 live GO | PR #178 / `main@38e3d954` remains source provenance, not serving proof. The [Task 2.7 audit](../evidence/b2-task-2-7/historical-containment-audit-20260910.md) still bounds containment only at the documented ACA path; user-visible impact remains unproven, not impossible. The separately owner-operated [B1 Task 4.9 live proof](../evidence/b2-task-4-9/B1_TASK_4_9_LIVE_DECIMAL_FIDELITY_STOP_GO_2026-09-16.md) established exact string fidelity and mandatory fixed-E2E restoration on current serving revisions. Retain mandatory numeric compatibility |
| 3 — Redis-backed presence | ✅ Tasks 3.1–3.7 complete; Azure proof recorded on 2026-09-11 | `api-gateway--0000081` / `sha256:090ad3ba4b7a…`; default TTL **150s**, live key TTL 178–180s |
| 4 — portfolio-service demo reset | ✅ Complete — Tasks 4.1–4.4a merged via PR #180 / `main@63fc058`; Task 4.5 live GO | Exact cut serves internally on `portfolio-service--0000093` / `sha256:9a1d5533…`; one authorized same-state reset returned `200`, exact golden 159/159, unchanged version `0` per B1; [evidence](../runbooks/B2_TASK_4_5_DEMO_RESET_STOP_GO.md) |
| 5 — manual-reset gateway bundle | ✅ Source Tasks 5.1–5.5 plus 5.1a/5.1b are merged, Task 5.6 owner GO is recorded, and the complete bundle was shipped with the approved Task 8.8 scoped gateway deployment | Deployment run `34433715705` placed the bundle on historical `api-gateway--0000079`; Task 6.3 and the Step B pre/post reads bind successor `api-gateway--0000081`. The deployed frontend control returned `200` in the accepted 2026-09-20 browser run |
| 6 — manual reset frontend | ✅ Tasks 6.1–6.3 complete; source merged through PR #214 and the page-level control is now live-verified | Both production flags are `true`; the control rendered and successfully restored the exact golden state in the [Step B Production E2E](../evidence/b2-wave-10-2/WAVE_10_2_STEP_B_5B_PRODUCTION_E2E_GO_2026-09-20.md). The separately deferred [sidebar backlog](../todos/backlog/responsive-dashboard-sidebar/README.md) was resolved later by Phase 2.1 PR #297 without changing Wave 6 evidence |
| 7 — decimal rollout note | ℹ Informational | No independent release gate |
| 8 — login-orchestrated reset | ✅ Tasks 8.1–8.9 complete for Azure at independently accepted evidence head `8544d722`; current provenance binds `api-gateway--0000081` and `portfolio-service--0000096` | Step B pre/post reads reconfirmed both exact revisions and digests with no intervening backend deployment. No rollback was required. Task 8.8a remains AWS-only |
| 9 — live integration | ✅ Source, disposable-stack, and Production browser evidence complete | PR #232 supplied the 5/5 disposable Compose run. Exact-SHA CI run `35450636358` supplied condition 5a; the accepted Step B verifier supplied Production browser exit criterion 5b with all L0-L9 legs passed and golden-state cleanup confirmed |
| 10 — production exposure | ✅ Tasks 10.1 and 10.2 complete; Asset Picker exposed | PR #294 supplied the `frontend-only` path. Run [35489160653](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35489160653) deployed `main@91f40bd0`; both variables are `true`, served build `mE3_OA6woqSxKOZS4H13q` is stable, and the [Step B evidence](../evidence/b2-wave-10-2/WAVE_10_2_STEP_B_5B_PRODUCTION_E2E_GO_2026-09-20.md) is `GO` |

**Wave 8 status correction (2026-09-07; superseded historical snapshot):** Task 8.1 provenance is source `6a171558`, cu4/revision
`0000094`, digest `sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023`; this is
historical provenance rather than fresh serving read-back, and no duplicate deployment is needed.
Tasks 8.3–8.7a, including authentication/cardinality and real-chain evidence, are source-complete and
independently accepted. At that snapshot, remaining Wave 8 gates were deployment/live proof
(8.8/8.9), not source orchestration prerequisites; Task 5.6 GO and B1 R-C deployment/convergence
remained open. **Current status:** B1 R-C Tasks 7.1–7.11 are locally complete, and
Writer_Convergence / P11g-2 is proved only for the activated exact R-C artifact and rollback artifacts
at or above R-B3r. Task 5.6 GO and Task 8.8/8.8b deployment evidence are now complete. Wave 10.2
condition 2 is complete through B1 Task 4.9; the active B2 gates are the B2-specific Wave 10
convergence/exposure gate, including its Step B Production E2E (exit criterion 5b). Task 8.9's live serving evidence is COMPLETE / GO at independently accepted
head `8544d722`, with documentation head `fab158cb` independently accepted and PR #278 merged at `6e046741`. The later 2026-09-20 Step B deployment enabled both flags and completed Wave 10.2.

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
Astra's 2026-09-10 ACCEPT, bounded only at the documented ACA path. The separately
owner-operated [B1 Task 4.9 live proof](../evidence/b2-task-4-9/B1_TASK_4_9_LIVE_DECIMAL_FIDELITY_STOP_GO_2026-09-16.md)
is GO, so Wave 10.2 condition 2 is satisfied. The remaining gates were subsequently satisfied at
decision time, both flags were enabled, and the accepted 2026-09-20 Step B verifier closed 5b and
Task 10.2. Numeric compatibility remains mandatory but non-blocking. The
[2026-09-19 condition-5 decision](../evidence/b2-wave-10-2/WAVE_10_2_CONDITION_5_DECISION_2026-09-19.md)
and [2026-09-16 audit](../evidence/b2-wave-10-2/WAVE_10_2_REMAINING_GATES_AUDIT_2026-09-16.md)
remain dated pre-exposure records; the current result is the
[Production E2E GO](../evidence/b2-wave-10-2/WAVE_10_2_STEP_B_5B_PRODUCTION_E2E_GO_2026-09-20.md).
See the [decision record](../superpowers/plans/2026-09-06-b2-wave8-decision-record.md).

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

The Wave 9 → Wave 10 edge means Wave 10.2 item 5a (source and disposable-stack completion). Wave 9's
real-browser production proof runs inside Wave 10 as Step B exit criterion 5b, not before it.

Remaining B1 and B2 source tasks may proceed according to their explicit dependency graphs; they do
not need to be serialized with unrelated operational work. Production transitions retain their
individual approval gates.

## 6. Next meaningful work and authorization boundary

### Current cutoff

Spec A 9.14 and the custom-domain restoration remain complete. Historical setter attribution remains
`MECHANISM_REPRODUCED_SETTER_UNPROVEN`; that historical RCA does not block the delivered feature.
The 2026-09-20 frontend-only deployment left the serving backends unchanged at
`api-gateway--0000081` and `portfolio-service--0000096`. Both feature variables are `true`, served
build `mE3_OA6woqSxKOZS4H13q` exposes both controls, and the accepted Production browser run restored
the exact 159-holding golden state.

- Wave 10.2 / Task 10.2 is complete; no rollback was required.
- Asset Picker Demo Preparation Phase 1 is complete.
- Demo Preparation Phase 2.1 responsive-shell source is complete on `main@4f288c4a`; it has not
  been deployed. Portfolio and Overview narrow-width overflows remain open but are explicitly
  accepted outside the desktop-only demo path.
- The final pre-Phase-3 enhancement, product SemVer `0.9.0`, is source-complete and independently
  accepted at local candidate `19fb82d5`. At the 2026-09-21 snapshot it remained local. Publication
  and merge require separate owner authorization and green pull-request CI; the final uncontended
  Phase 2 visual/browser verification and independent exit review must then pass before Task 7's
  separately authorized tag/pre-release rehearsal.
- Task 2.6 remains mandatory compatibility debt but does not block current exposure.
- The resolved sidebar/shell work, accepted narrow-width debt, and SemVer gates must not reopen
  Phase 1.
- Broader multi-user application certification remains Phases 3–4; README/ROADMAP and media work
  remain Phases 5–6.

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

The SemVer candidate introduces one separate B1 governance follow-up: root `VERSION` now affects
packaged JAR bytes but is absent from the candidate guard's `_UNIVERSAL_ROOTS`. The
[high-priority backlog item](../todos/backlog/b1-candidate-envelope-product-version-root/README.md)
must land and re-attest the affected policy records before any next `VERSION` change or new B1
candidate-envelope reliance. It does not alter existing R-C evidence and cannot ride the exact
four-file `1.0.0` transition.

**B2 position:** Asset Picker is delivered and exposed. The prerequisite chain through Task 8.9,
Wave 9 source/assembled-stack integration, B1 Task 4.9, Step A, condition 5a, and the accepted 5b
verifier is complete. PR [#294](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/294)
provided the dedicated `frontend-only` route; run
[35489160653](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35489160653)
used it at exact SHA `91f40bd0`. The resulting Production browser evidence is `GO`, both feature
flags remain enabled, cleanup restored the golden portfolio, and no backend redeploy or rollback
occurred. Task 2.6 remains mandatory compatibility debt. The formerly owner-deferred
[sidebar issue](../todos/backlog/responsive-dashboard-sidebar/README.md) is resolved on `main` by
Phase 2.1 PR #297. That source-only merge does not alter the served Production baseline or any Asset
Picker delivery gate. The owner has accepted the Portfolio and Overview narrow-width overflows as
open backlog debt outside the desktop-only demo path. The `0.9.0` SemVer foundation is
source-complete and independently accepted at local candidate `19fb82d5`. At the 2026-09-21
snapshot it remained local. Publication and merge require separate owner
authorization and green PR CI; the final uncontended Phase 2 visual/browser verification and
independent exit review must then pass before the separately authorized Task 7 pre-release rehearsal
and Phase 3.

**Task 4.9 decimal-fidelity live proof (2026-09-16):** the owner authorized and personally ran the
unchanged, attested operator harness at `main@cca7f0d9`. The bounded composition write, exact
string response/readback, versioned restoration, and mandatory fixed-E2E complete wire-tuple
verifier all passed; the [sanitized evidence](../evidence/b2-task-4-9/B1_TASK_4_9_LIVE_DECIMAL_FIDELITY_STOP_GO_2026-09-16.md)
is GO. This satisfies B2 Wave 10.2 condition 2 together with Task 2.7; it does not authorize or
complete production exposure.

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
    unattended synthetics remain suspended in `synthetic-monitoring.yml`. The custom-domain backlog is closed by the 2026-09-26 audit; three other process follow-ups remain open:
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
3. **B2 product lane:** Waves 1–9 retain their recorded source, assembled-stack, deployment, and live
   gate evidence. Wave 10.2 completed on 2026-09-20: exact-SHA frontend-only deployment
   `35489160653` succeeded, both flags are `true`, and the accepted Production browser verifier
   passed every L0-L9 leg with persisted readback and golden-state cleanup. Asset Picker delivery is
   complete. Phase 2.1 responsive-shell source is also complete through PR #297. The narrow-width
   overflows are accepted open debt for the desktop-only demo. The `0.9.0` SemVer foundation is
   independently accepted at local candidate `19fb82d5`. Publication and merge require separate
   owner authorization and green PR CI. Then complete the final uncontended Phase 2 visual/browser
   verification and independent exit review before separately authorizing and verifying its
   non-deploying Task 7 rehearsal and, later, Phase 3. Phases 4–6 follow the demo-preparation plan.
4. **Process lane:** keep the status-propagation CI guard healthy in required `static-guard`; it is
   process-control only and does not advance the runtime baseline. Product SemVer publication,
   merge, Phase 2 exit review, tag/pre-release rehearsal, Phase 3, and deployment remain separate
   gates. The exit review is a verification gate whose result is reported to the owner; each
   owner-controlled action requires its own authorization.
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
