# Asset Picker Demo Preparation Implementation Plan

> **For agentic workers:** Execute this plan phase-by-phase. Claude owns implementation, Fable 5.1
> reviews implementation before Codex architecture/status reconciliation and final acceptance. Do
> not let later-phase work expand Phase 1.

**Original draft:** 2026-09-13

**Filed and reconciled:** 2026-09-20

**Latest reconciliation:** 2026-09-23

**Owner approval callout — next blocked action:** B3 is complete and accepted. A4's owner-operated
Production run, the creation or use of retained `CERT_A` and `CERT_B`, and decisions D1/D2/D4-D10
remain unapproved. **If approved**, run the existing Phase 3 desktop suite once against the B3-served
candidate; **if not**, Phase 3 remains open. Optional A5 Azure capture is a separate decision. No
push, PR, merge, dispatch, live read, account creation, secret handling or Production mutation is
authorized by editing this plan.

**Goal:** Make this portfolio project ready for a credible live desktop demo quickly. Deliver the
Asset Picker, deploy the already-merged application fixes, run the existing multi-user browser suite
against the served candidate, fix only demo-blocking findings, and prepare a short repeatable demo
script. Broader hardening, exhaustive documentation and polished media are follow-on work.

**Architecture:** Keep the six phases for traceability, but use the fast-track demo exit below rather
than making every Phase 4-6 backlog item a prerequisite. Phase 1 contains only the dependencies,
deployment, and focused live browser proof needed to call the Asset Picker delivered. The known UI
issue and broad application check follow after that delivery.

**Tech stack:** Java/Spring microservices, Next.js/React frontend, Playwright browser E2E, Azure
Container Apps, Azure frontend deployment, and GitHub Actions.

**Governing sources:** `.kiro/specs/asset-picker-composition/requirements.md`,
`.kiro/specs/asset-picker-composition/design.md`, `.kiro/specs/asset-picker-composition/tasks.md`,
and [`ASSET_PICKER_E2E_MASTER_PLAN.md`](ASSET_PICKER_E2E_MASTER_PLAN.md).

**Execution record:** The completed
[Step B kickoff](../superpowers/plans/2026-09-20-asset-picker-step-b-claude-kickoff.md) records the
bounded production path. The earlier
[offline-CI wiring kickoff](../superpowers/plans/2026-09-19-wave10-2-5b-ci-wiring-claude-kickoff.md)
is retained as superseded decision history and is not an active task.

## Current status

### Fast-track dashboard — 2026-09-23

**This is the status page for this plan.** `main@cdc51df6643b51fb92dc747a20ac3ca9be4ac2b1`
contains PRs #310-#316 and its post-merge CI is green. B3 completed on 2026-09-23 at that exact SHA:
[run 35860682429](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35860682429)
proved `portfolio-service--0000097` at digest
`sha256:19a64b25c6c46b0eb7a42774e972dadadbd278d91b726f31d9f12572840d086b` was active,
`Provisioned` and receiving 100% of ingress traffic; then
[run 35862375385](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35862375385)
proved the origin served uploaded frontend build `R5P3Iw1SRc9jlIX2Wwee8`, with the backend set
unchanged. Both runs were attempt 1 and passed. No A4 Production-suite run or A5 capture is recorded.

| Phase | Status now | Next exit action |
|---|---|---|
| 1 — Asset Picker delivery | **COMPLETE**, deployed and accepted in Production on 2026-09-20 | None; do not reopen for later-phase work |
| 2 — UI and demo-critical fixes | **COMPLETE and serving as the B3 candidate**; functional Production verification remains in Phase 3 | Exercise the served candidate during A4 |
| 3 — Broad desktop Production E2E | **B3 SERVING PROOF COMPLETE; A4 OPEN.** Compatible backend and frontend identities are proven; authenticated behavior and D11 response content are not | One owner-operated A4 run with retained users and an uncontended full suite |
| 4 — Defect disposition | **NOT STARTED**; depends on the Phase 3 run | Fix demo blockers; explicitly accept/defer non-blockers |
| 5 — Documentation | **PARTIAL status filing only**; comprehensive pass not started | Record exact demo status, known limitations and operator steps; defer the larger rewrite |
| 6 — Demo material | **NOT STARTED** | Prepare and rehearse a short live desktop script; slides/video are optional |

**Fast-track demo-ready exit (not a general-production or `1.0.0` claim):** Phases 1 and 2 are
accepted; B3 proves that the D11 portfolio-service revision and the uploaded frontend build serve
traffic; the owner-operated Phase 3 desktop suite completes once uncontended with no skipped or
uncollected scenario counted as a pass; Production state is restored or its retained state recorded;
and no unresolved finding blocks the agreed live demo. A verdict with expected defects must name
them and be accepted explicitly, not be relabeled PASS. Record accepted non-blocking findings and a
short operator script. Full Phase 5 documentation, polished slides/video and an independent A5
before/after Azure attestation are **not** fast-track demo blockers. The B3 run-bound revision/digest
proof supplies the serving identity needed for this narrower exit. A5 remains available as optional
corroboration under its own approval; it is not silently marked complete.

**Portfolio-project scope:** desktop demo only; do not add mobile-browser certification, broad
release hardening, unrelated services, or a `1.0.0` launch to the critical path. This scope change
removes process not truth: a merge or CI pass is not a live observation, and any actual workflow
dispatch, Production/Cloud access, live GET, account creation or secret handling still requires the
owner's bounded approval and the repository's existing GitHub `production` gate. This plan grants
none of those actions.

### Completed Phase 1 and Phase 2 record

**Phase 1 is complete. Asset Picker is deployed and demo-ready under the owner's stated acceptance
criterion: live exposure plus successful Production E2E.**

- Both repository-scoped feature variables are `true`.
- Frontend-only deployment run
  [35489160653](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35489160653)
  completed successfully from `main@91f40bd0126f15fc87a6d6beb2ffa6bcd01e76d4` at
  `2026-09-20T04:30:28Z`.
- The served frontend changed to stable build ID `mE3_OA6woqSxKOZS4H13q`; both controls rendered.
- The accepted Step B verifier completed with `GO` at `2026-09-20T04:40:54Z`, within the
  authorized 1,800-second bound. All production-browser legs L0-L9 passed, including catalog,
  presence, price, save, persisted readback, and demo reset.
- Independent cleanup confirmed the exact 159-holding golden state. No rollback was required.
- Pre/post reads confirmed unchanged `api-gateway--0000081` and
  `portfolio-service--0000096` revisions and digests.
- Durable evidence:
  [`WAVE_10_2_STEP_B_5B_PRODUCTION_E2E_GO_2026-09-20.md`](../evidence/b2-wave-10-2/WAVE_10_2_STEP_B_5B_PRODUCTION_E2E_GO_2026-09-20.md).

The final accepted 5b contract supersedes the draft's illustrative two-save sequence: one real
non-golden composition save with independent persisted readback, followed by the deployed manual
reset with independent golden-state confirmation, earned the Phase 1 live acceptance. Phases 2-6
remain separate and must not be used to reopen Phase 1.

**Phase 2 status at the 2026-09-22 reconciliation:** complete at its local/browser exit gate. Phase
2.1, the shared responsive dashboard shell, is merged on `main` through
[PR #297](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/297) at merge
commit `4f288c4a0e8393e78efd7f7449c114e25db78818`. The accepted head
`c2d3dc66a5995ea7aafff50c691df7c0f6889cd5` replaces the fixed 240px narrow-screen sidebar with a
64px icon rail below `md`, preserves all five navigation links and their accessible names, and makes
the outer shell a non-scrollable `relative` / `overflow-clip` boundary while leaving `<main>` and
the chat transcript as the intentional scrollers.

The Phase 2.1 proof covered four dashboard pages, five viewports, both themes, 18 focused tests, and
130 browser assertions for the extended-chat and navigation defect paths. PR-head CI was green and
the merge triggered no deployment. The screenshots and browser harness are not tracked in the
repository; Firefox, Safari, real touch devices, and Production hosting remain untested. The owner
subsequently fixed the demonstration contract at desktop-only and explicitly accepted the Portfolio
and Overview narrow-width overflows as open, non-demo-critical backlog debt.

The product Semantic Versioning foundation for `0.9.0` subsequently merged through
[PR #300](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/300) at
`3eca669ccc53be4d56d9d55b79be0b83e4e6b60b`. The remaining demo-correctness fixes merged through
[PR #301](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/301) at exact merge
commit `8f2c4cf7947cad316e3d063b5f322b4d78dc64d5`: allocation colors, the summary-pill truncation,
the Market Data 24-hour change join, and the single AI Insights recommendation badge. Pull-request
CI passed. Post-merge `main` runs CI Verification `35636266318`, Frontend CI `35636266278`, Qodana
`35636266303`, and Gitleaks `35636266381` all succeeded, and no deployment workflow ran.

After post-merge CI was green, a fresh flagged build of that exact merge commit produced build ID
`Qnf6UaWRVM2Df_wgpmuVs`. Negative-control run `negative-controls-20260921-184547Z` made all 21
target oracles fail. In final uncontended run `final-20260921-184656Z`, every functional and visual
oracle passed across 24 page/viewport/theme combinations and six chat/scrolled-navigation scenarios;
the only recorded page-error class was the known signed-in React hydration error #418. An independent
Fable 5.1 exit review returned **ACCEPT WITH MINORS** with zero Critical, zero Important, and four
Minor findings.

Task 7 subsequently completed through the `CHANGELOG.md`-only
[PR #308](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/308), merged at
`main@57910b09f408e819537ca875b51ee150d56d5ea7`. Full exact-merge
[CI Verification](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35679165460)
passed with all 14 jobs successful. Annotated tag object
`50dba1f42229c448381fc22ddf7758452e703932` names `v0.9.0` and points to that merge commit; the
[`0.9.0` GitHub pre-release](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/releases/tag/v0.9.0)
has no assets, and
[Release Tag Validation run 35680891236](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35680891236)
passed. No deployment workflow was triggered by the tag or Release. The `v*` creation ruleset was
absent, and the owner explicitly accepted that residual risk before publication.

React #418 and F1 were corrected and merged through
[PR #310](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/310) at merge commit
`9a4603c245529b93942d3a6b9b85e886be08b5e0`. D11/F13 was then independently accepted and merged
through [PR #311](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/311) at
merge commit `b27fcd077925e1afc5040dd2ff85aaeb787977e1`; its tree is identical to accepted head
`3743bd28fd58b2f4aa9048d3e807e449afea10bb`. All four push-triggered post-merge workflows passed on
that merge SHA. They were later deployed as part of the B3 candidate described below; their
functional Production acceptance remains part of A4.

After that reconciliation, [PR #312](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/312)
merged the D11/Phase 3 status packet at `a297e6c5`. [PR #313](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/313)
merged the scoped backend proof at `4adcf981`: a scoped deploy must bind this run's digest to the
ready revision holding 100% of ingress traffic. [PR #314](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/314)
merged the frontend proof and A4 stop gate at `e8d2f51c`: the frontend-only deploy must observe a
served build ID that changed from before the upload and equals the emitted ID of the uploaded export;
Production S00 must have a declared expected build ID and stops the run before S02's permanent signup
if it fails. PR #314 also removed the packet's circular instruction to read the expected ID from the
served page. [PR #315](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/315)
then merged the four-arm stop-gate control, and PR #316 made this plan the status dashboard.

**B3 execution, accepted 2026-09-23:** scoped run 35860682429 deployed only portfolio-service and
bound its run-produced digest to ready revision `portfolio-service--0000097`, active and
`Provisioned` with 100% traffic; its non-interference proof passed. Frontend-only run 35862375385
then emitted and uploaded build `R5P3Iw1SRc9jlIX2Wwee8`; the unauthenticated origin check observed
that same ID after deployment and a different pre-deploy ID, producing `verdict: PASS`,
`usable_for_a4: true`, matching run/SHA identity and no errors. It used one pre-upload and one
post-upload `/login` GET. The run also proved every backend unchanged and still observed the run-1
portfolio revision/digest. This is point-in-time serving identity evidence, not authenticated page
or D11 response-content evidence; those remain A4 work.

The accepted 320px/375px overflows remain open backlog. F10 and F14 remain separate presentation
issues; F2/F3/F5/F9 need an explicit demo impact disposition, not an automatic repair batch. Task 7
is complete. Phase 3's Production exit remains open pending the owner-run A4 suite and its
identity/evidence decisions. A5 is optional independent attestation for the fast-track demo exit.

### Immediate fast-track sequence

1. **B3 — COMPLETE and accepted.** Runs 35860682429 and 35862375385 deployed the backend then
   frontend at `main@cdc51df6` and passed their run-bound serving and non-interference proofs. The
   serving candidate for A4 is portfolio-service revision `--0000097` at digest `19a64b25…` and
   frontend build `R5P3Iw1SRc9jlIX2Wwee8`. A later deploy or configuration change would require a
   fresh identity check; B3 does not prove signed-in behavior or D11 response content.
2. **A4 — one owner-operated, uncontended desktop Production run.** Once both B3 proofs pass, create
   retained `CERT_A` and `CERT_B` once (or use already-approved isolated accounts) and run the
   existing full Phase 3 suite from the owner's machine. Its S02 signup creates one permanent
   `FRESH` account per run; record that lifecycle, keep secrets/private `pw-output/` local, and use
   the deploy run's emitted build ID for S00. Make the D1/D2/D4-D10 choices in the Phase 3
   [execution packet](../superpowers/plans/2026-09-22-phase3-preparation-execution-packet.md) as
   one bounded run decision, not a new source-work program. Pre-disposition the known F2/F3/F5/F9
   behavior as blocking or accepted with an honest expectation before the run. One real chat request
   keeps that scenario complete; skipping it makes the verdict incomplete. Run outside the FX and
   market-refresh windows. No automatic Production rerun or account creation is implied.
3. **Disposition only demo-relevant findings.** Record all findings. Repair any finding that breaks
   the agreed desktop demonstration, regardless of its severity label, with a focused regression and
   independent review; rerun its scenario and the full suite once after material code changes. An
   observed non-blocking issue may be explicitly accepted/deferred with a truthful demo limitation;
   call out any Critical/Important non-demo issue rather than hiding it. Do not
   hold the portfolio demo for the known 320px/375px overflows or a general backlog sweep.
4. **Prepare the minimum demo handoff in parallel.** Keep this dashboard current; make a short
   desktop walkthrough script with the approved accounts, evidence location, known limitations and
   fallback if chat/market data is unavailable. Rehearse against the final served build. Slides,
   a polished video, a full architecture-documentation rewrite and a `1.0.0` release can follow
   after the demo if useful.

## Owner approval callouts

The original plan kept merge, production operations, test-user creation, cleanup, and external
publication as separate owner decisions. The owner separately authorized the actions that produced
the Phase 1 outcome, the Phase 2 source merges and exit review, the completed Task 7 release-only
rehearsal, and the two B3 deployments and proof reads recorded above. Those consumed authorizations
do not authorize A4, A5, future deployments, Production mutations, creation of additional users,
cleanup, ruleset changes, reruns or future publication. This reconciliation records status only and
grants none of those authorities.

The owner subsequently consumed separate publication and merge approvals for PRs #310-#316 and
separate B3 approvals for backend run 35860682429 and frontend run 35862375385, including each
`production` gate and the bounded frontend GETs. B3 followed the required order: D11 portfolio-service
first, then frontend. Those approvals are exhausted; they do not authorize another dispatch, live
read, Production/cloud access, certification-account creation, A4 or A5. A5's independent Azure
before/after capture is optional for the narrower portfolio-demo exit, but must still be approved if
performed.

## Global constraints

- Phase 1 and 2 are accepted and B3 is complete; A4 is next. Findings are triaged after A4. Minimum demo
  instructions can be drafted in parallel; comprehensive documentation and media do not gate the
  fast-track exit.
- Portfolio Settings is deferred and does not gate demo readiness.
- Phase 1 must not absorb the scrollbar fix, general backlog work, the multi-user suite, or the
  documentation overhaul.
- A concurrent test pass is supplementary. The Phase 3 exit needs one uncontended full suite run;
  after any material demo-blocking fix, rerun the affected scenario and then that full suite.
- Browser evidence must assert observable behavior and persisted values, not only element visibility
  or HTTP success.
- Production mutations must either restore the exact starting state or record the explicitly
  accepted retained state.

---

## Phase 1: Deliver the Asset Picker feature — COMPLETE

**Exit outcome:** The feature is deployed in Production and a focused real-browser journey proves
its core write path and persistence.

### Task 1.1: Close the Task 8.9 execution blocker — COMPLETE

- [x] Keep the Task 8.9 correction bounded rather than turning it into a general certification
  framework.
- [x] Resolve the independent review's blocking findings and rerun its exact-head verification.
- [x] Obtain independent acceptance before the production proof.
- [x] Preserve failed attempts as historical evidence rather than treating them as success.

### Task 1.2: Complete Task 8.9 live serving proof — COMPLETE

- [x] Execute the final proof under the real Production threshold and scale-from-zero configuration.
- [x] Confirm serving revisions, digests, provider, configuration, trace-correlated behavior, and
  cleanup.
- [x] Obtain independent evidence acceptance and retain the serving revisions without rollback.

### Task 1.3: Expose the feature and run focused Production E2E — COMPLETE

- [x] Complete pre-exposure API verification against the serving artifacts.
- [x] Set both repository-scoped flags to `true` and run a new frontend-only build/deployment.
- [x] Verify the new served build and both controls in a fresh Production browser session.
- [x] Open the picker and verify catalog, presence, freshness, and draft-price routes.
- [x] Save a changed composition, observe a strictly advanced version, and independently read back
  the exact persisted draft.
- [x] Invoke the deployed manual reset, confirm HTTP `200`, and independently verify the exact
  159-holding golden state.
- [x] Confirm backend revisions/digests did not change and record that rollback was not required.

---

## Phase 2: Fix the UI issue and other demo-critical backlog items

**Exit outcome:** The application shell is visually usable at supported demo viewports, and no known
critical backlog defect blocks a demonstration.

**Status at the 2026-09-22 reconciliation:** Exit met at
`main@8f2c4cf7947cad316e3d063b5f322b4d78dc64d5`. Phase 2.1 is merged through PR #297, the SemVer
foundation through PR #300, and the final demo-correctness fixes through PR #301. Post-merge CI was
green; the exact-merge flagged build, 21 negative controls, 24-capture matrix, six interaction
scenarios, and independent exit review passed at the level recorded above. At that exit, React #418
remained an unsuppressed Phase 3 blocker; it was later fixed and merged in PR #310 and still requires
Production deployment and re-verification. The explicitly accepted narrow-width overflows and the two
Low-priority exit-review presentation/data-consistency findings remain open backlog. No Phase 2
source has been deployed or Production-verified.

- [x] Reproduce the recorded narrow-screen fixed-sidebar/clipping issue as the leading hypothesis.
- [x] Sweep Overview, Portfolio, Market Data, and AI Insights at mobile, tablet, and agreed
  demo-desktop viewports.
- [x] Capture screenshots and enumerate scrollable containers so a separate page-level
  multiple-scrollbar defect cannot hide behind the existing backlog label.
- [x] Fix the confirmed shell root cause without removing navigation or breaking desktop behavior.
- [x] Allow intentional component scrolling, such as the chatbot transcript, but reject competing
  page/shell vertical scrollbars.
- [x] Fix or explicitly accept the Portfolio horizontal overflow caused by the holdings action row at
  320px and 375px. **Explicitly accepted and deferred for desktop-only demonstration** — not fixed;
  tracked in
  [`responsive-dashboard-narrow-width-overflow`](../todos/backlog/responsive-dashboard-narrow-width-overflow/README.md).
- [x] Fix or explicitly accept the Overview horizontal overflow caused by the performance range
  badges at 320px. **Explicitly accepted and deferred for desktop-only demonstration** — not fixed;
  tracked in the same backlog entry.
- [x] Triage the remaining backlog and include only defects genuinely critical to the demo.
  **Disposition:** approved SemVer
  [design §2](../superpowers/specs/2026-09-20-product-semantic-versioning-design.md#2-context-and-intent)
  selects the SemVer foundation as the final pre-Phase-3 slice and classifies the known narrow-width
  overflows as non-blocking backlog debt. This records the governing disposition rather than
  claiming a separate exhaustive triage artifact.
- [x] Obtain independent review and an uncontended visual/browser verification run for Phase 2.1.
- [x] After the remaining Phase 2 slices, run the final uncontended visual/browser verification and
  obtain independent Phase 2 exit review. Completed against exact merge commit `8f2c4cf7` with
  negative-control run `negative-controls-20260921-184547Z`, final run
  `final-20260921-184656Z`, and an independent **ACCEPT WITH MINORS** verdict (0 Critical,
  0 Important).
- [x] Establish the product Semantic Versioning foundation at `0.9.0` and rehearse the
  non-deploying `v0.9.0` pre-release. Governed by the
  [design](../superpowers/specs/2026-09-20-product-semantic-versioning-design.md), the
  [implementation plan](../superpowers/plans/2026-09-20-product-semantic-versioning.md), and the
  [versioning policy](../release/SEMANTIC_VERSIONING_POLICY.md). The `0.9.0` validation is
  `scripts/validate_product_version.py` and its contract tests. The source foundation merged through
  PR #300 at `3eca669c`, and the Phase 2 exit passed at `8f2c4cf7`. Task 7 completed through PR #308
  at `57910b09`: annotated tag `v0.9.0` points to that commit, the GitHub pre-release has no assets,
  tag validation passed in run `35680891236`, and neither the tag nor Release triggered deployment.

---

## Phase 3: Run broad Production browser E2E

**Exit outcome:** A reviewed browser suite has exercised the application across multiple Production
users and materially different portfolios.

**Status:** B3 serving proof is complete on `main@cdc51df6`; the A4 Production-suite exit is
**OPEN**. None of the unchecked browser-run items below has been credited from CI, B3 identity
checks or local browser tests. Fast-track the existing suite; do not build another one.

- [x] Merge the multi-user suite and the #418/F1 frontend corrections (PR #310).
- [x] Merge D11/F13 position-level 24-hour value and coverage (PR #311).
- [x] Merge the backend revision/traffic binding, frontend served-build proof, S00 Production stop
  gate and its four-arm control (PRs #313-#315).
- [x] B3: deploy portfolio-service, then the frontend, and accept both run-bound serving proofs
  (runs 35860682429 and 35862375385 at `cdc51df6`).
- [ ] A4: complete one owner-operated, uncontended Production run and disposition its findings.

**Browser matrix:** desktop only, at the agreed demo viewport(s). The owner narrowed the
demonstration contract to desktop, so the deferred narrow-width overflows are backlog debt rather
than Phase 3 scope. That narrows the viewports and nothing else: every multi-user, isolation,
persistence, conflict, freshness, evidence, and uncontended-run requirement below applies in full.

**Product version:** Phase 3 runs against a functionally complete `0.y.z` candidate. `1.0.0` stays
blocked until Phase 3 and the remediation or explicit acceptance of its findings are complete; the
full gate is the `1.0.0` boundary in the [versioning policy](../release/SEMANTIC_VERSIONING_POLICY.md).

**Serving blocker closed; functional verification open:** B3 proved the compatible backend and
frontend identities serve at the accepted SHA. It did not sign in or inspect D11 API responses.
A4 must check #418, D11/F13 and cross-page 24-hour/freshness presentation against real user data
rather than infer correctness from build and revision identity.

- [ ] Before Production mutation, declare the identity lifecycle: retained named certification
  accounts or an explicitly approved cleanup mechanism.
- [ ] Create or use 2-3 isolated users with distinct holdings and quantities.
- [ ] Cover signup validation, successful signup, login, logout, and re-login.
- [ ] Cover empty portfolio, add, quantity update, removal, review, save, cancellation, validation
  failure, and persisted readback.
- [ ] Induce and prove optimistic-concurrency conflict behavior without automatic retry, silent
  discard, or cross-user interference.
- [ ] Verify Overview, Portfolio, holdings/asset cards, totals, partial valuation,
  price/freshness presentation, Market Data, navigation, and AI Insights/chatbot.
- [ ] Prove user and session isolation.
- [ ] Record per-scenario assertions, traces, screenshots, video on failure, network evidence,
  served revision, and final persisted state.
- [ ] Run the full suite once uncontended. Do not count skipped, filtered, or uncollected scenarios
  as passes.

---

## Phase 4: Disposition Phase 3 findings and fix demo blockers

**Status:** Not started; Phase 3 has not run in Production. **Fast-track exit outcome:** no unresolved
finding breaks the agreed live desktop demonstration. Repair demo blockers, and explicitly accept or
defer non-blockers with an honest demo limitation. This is not a claim that every product defect has
been fixed or that the product is generally Production-certified.

- [ ] Convert each finding into a bounded defect with reproduction evidence, severity, owner, and
  expected behavior or an explicit non-blocking acceptance.
- [ ] Fix each demo-blocking bundle with a failing regression first; leave unrelated backlog out.
- [ ] Independently review any implementation bundle.
- [ ] After each fix, rerun the exact failing scenario and, after material code changes, the full
  Production suite uncontended. If no code change is needed, the accepted A4 full run is the exit run.
- [ ] Restore or record all Production state and verify the currently served artifact.

If the accepted A4 run finds no demo blocker, record the fix/review/rerun items as **N/A with the A4
evidence**, not as invented completed implementation work.

No **demo-blocking** defect of any severity may be deferred while calling the fast-track demo ready.
A non-blocking finding may be deferred only with an explicit owner product decision, recorded
limitation and, where relevant, a test expectation that does not claim the defect is fixed.

---

## Phase 5: Reconcile documentation

**Status:** Partial status filing only. **Fast-track exit outcome:** this plan states the actual
serving/run status, known limitations and a reproducible short operator flow. The full repository
documentation overhaul below is follow-on work, not a portfolio-demo gate.

- [x] Record B3 run URLs, exact SHA, served revision/digest and frontend build ID in this plan.
- [ ] Record the A4 run, verdict, findings and accepted limitations after it occurs.
- [ ] Keep a short operator script and evidence pointer for the final desktop demo.

**Full documentation backlog (not required for fast-track demo-ready):**

- [ ] Update README and ROADMAP with delivered capabilities and honest remaining limitations.
- [ ] Reconcile Asset Picker requirements, design, task ledger, and master plan with exact Production
  evidence.
- [ ] Update architecture diagrams and component/data-flow descriptions where deployed behavior
  changed.
- [ ] Document signup, authentication, portfolio mutation, conflict, persistence, reset,
  price/freshness, chatbot, rollback, and multi-user E2E flows.
- [ ] Add demo/operator instructions, test-data lifecycle, evidence locations, and known nonblocking
  backlog.
- [ ] Cross-check SHAs, revisions, flags, counts, links, and status claims before publication.

Mandatory factual status records may be updated earlier. The comprehensive documentation pass
belongs here and must not delay Phase 1.

---

## Phase 6: Prepare demo material

**Status:** Not started. **Fast-track exit outcome:** rehearse a short live desktop walkthrough
against the final served build using non-sensitive data, with a fallback for chat/market-data
unavailability. A slide deck and polished video are optional assets, not prerequisites to say the
portfolio demo is ready.

- [ ] Agree a short audience-specific narrative and rehearse it against the final served build.
- [ ] Keep a small non-sensitive screenshot/evidence set and a fallback path.

**Optional media backlog:**

- [ ] Agree the audience, duration, narrative, and live-demo versus recorded-demo balance.
- [ ] Build the slide deck from the accepted architecture and E2E evidence.
- [ ] Record a clean scripted Production walkthrough using non-sensitive demo data.
- [ ] Remove secrets, private user data, transient diagnostics, and misleading historical
  screenshots.
- [ ] Verify every screenshot/video frame matches the final served revision and current
  documentation.
- [ ] Use a separate media-production workflow; do not reopen accepted feature scope unless rehearsal
  finds a genuine product defect.

## Final definitions

- **Asset Picker delivered:** Phase 1 complete — achieved 2026-09-20.
- **Fast-track portfolio demo ready:** the dashboard's fast-track demo-ready exit is evidenced:
  Phases 1-2 accepted, B3 served candidate proven, A4 full desktop Production run accepted,
  demo-blocking findings resolved, Production state accounted for, and a short operator script
  rehearsed. This does not imply A5, exhaustive backlog closure, polished media or `1.0.0`.
- **Full original package:** complete the deferred Phase 5 documentation and optional Phase 6
  media if the portfolio needs them; do not mislabel those unchecked follow-ons as completed.
