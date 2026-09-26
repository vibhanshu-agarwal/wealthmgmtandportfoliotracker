# Asset Picker Demo Preparation Implementation Plan

> **For agentic workers:** Execute this plan phase-by-phase. Claude owns implementation, Fable 5.1
> reviews implementation before Codex architecture/status reconciliation and final acceptance. Do
> not let later-phase work expand Phase 1.

**Original draft:** 2026-09-13

**Filed and reconciled:** 2026-09-20

**Latest reconciliation:** 2026-09-26 UTC (roadmap/root README/v5 preparation; backlog and runbooks filed;
post-#320 suite/cleanup status
published through #323 at `d515aa5b`; targeted #3–#6 status published through #322 at `b4e989b3`).

**Owner approval required before push/PR and merge of the roadmap/README/v5 reconciliation:** each
action needs explicit owner authorization; this document grants neither. An unmerged branch copy
is a candidate; filing requires independent review and owner-authorized merge into `main`.
The [roadmap](../../ROADMAP.md), [root README](../../README.md) and
[enhancements v5](../../roadmap_enahancements_v5.md) distinguish delivered capabilities, open
residuals and the owner's three deferred requests. There is no feature implementation approval.
The [backlog audit](../todos/backlog/README.md) is filed through #324 (`6f1e5700`), and the
[runbook reconciliation](../runbooks/README.md) through #325 (`5d559478`). Those approvals do not
authorize this later bundle. If publication approval is withheld, this bundle stays local.
#323 (`d515aa5b`, 2026-09-25) filed the accepted suite and cleanup status. The post-#320 suite and
three-user cleanup are complete under approvals R and C; the verdict remains
`PASS_WITH_EXPECTED_DEFECTS`, not clean PASS.
No new suite, cleanup, deployment, cloud read or Production action is authorized here. #322 merged
the targeted-check status at `b4e989b3`; [PR #321](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/321)
merged at `c4e58f1c`, and [PR #319](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/319)
closed unmerged.

**Required before the eventual project freeze (owner direction, 2026-09-25):** neither item is a
fast-track demo blocker.
- **Reconcile and audit the existing documents.** The [roadmap](../../ROADMAP.md), the
  [operational runbooks](../runbooks/) and the [backlog](../todos/backlog/) already exist; they need
  reconciling with the delivered state. This factual cleanup does not wait for the brainstorming
  session.
- **Create the LinkedIn, resume, PPT and video package.** Its content awaits the agreed
  brainstorming session and is **not yet drafted**.

The A4 test users and the post-#320 suite's three temporary users have been deleted, subject to the
evidence limits below. The existing E2E account was restored after both the 2026-09-24 and the
2026-09-25 rehearsals. The private A4 `pw-output/` remains local pending the previously agreed
after-demo cleanup. The post-#320 run's `pw-output/` is also private and has no deletion decision
yet. Optional A5 Azure capture remains separate and does not gate the fast-track demo. This
plan grants no new push, PR, merge, dispatch, live read, account creation, secret handling or
Production mutation.

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

### Fast-track dashboard — 2026-09-25 UTC

**This is the status page for this plan.** The locally verified `main` is
`b4e989b3b466012f38de775c6a56a1eaebe73633` after the docs-only #322 merge; the serving
application build remains the one deployed from `db51cf5b` after #320. B3 completed on 2026-09-23
at its earlier candidate SHA `cdc51df6643b51fb92dc747a20ac3ca9be4ac2b1`:
[run 35860682429](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35860682429)
proved `portfolio-service--0000097` at digest
`sha256:19a64b25c6c46b0eb7a42774e972dadadbd278d91b726f31d9f12572840d086b` was active,
`Provisioned` and receiving 100% of ingress traffic; then
[run 35862375385](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/35862375385)
proved the origin served uploaded frontend build `R5P3Iw1SRc9jlIX2Wwee8`, with the backend set
unchanged. Both runs were attempt 1 and passed. No A5 capture is recorded.

**A4, accepted 2026-09-24 (Codex decision):** A4 is satisfied by **run 3**,
`p3-20260924T032033Z-52f1`, as **`PASS_WITH_EXPECTED_DEFECTS`**, not `PASS`.
- **Run 3:** all 15 scenarios passed and none were unrun. The ledger holds 535 checks, 0 failed.
  The binding is `BOUND` to candidate `cdc51df6`, suite `1c3a132f` and served build
  `R5P3Iw1SRc9jlIX2Wwee8`. The one expected defect observed was `non-demo-reset-control-visible`.
- **Runs 1 and 2 remain separate FAIL history:** `p3-20260923T174742Z-ee73` (S05) and
  `p3-20260923T182221Z-92cc` (S12). Their checks are not combined with run 3.
- **Cleanup:** the five temporary A4 users (two CERT, three FRESH) were deleted by the owner-run,
  Codex-cleared cleanup. The deletion counts come from the script's own queries and its local
  record, and **the cleanup's historical deletion counts were not independently verified**. A later
  read-only account inventory by Codex found the A4 accounts absent.
- **Full record:** the Fable- and Codex-accepted verdict record was merged through #321 as a sanitized
  copy, [`A4_VERDICT_RECORD_runs-1-3.md`](../evidence/phase3-a4/A4_VERDICT_RECORD_runs-1-3.md). It
  was first published in #319, which #321 supersedes. It has
  roles in place of account addresses, no per-user IDs and no local paths. The private original
  stays with the local run evidence.

**Post-#320 suite, accepted by Codex on 2026-09-25:** one owner-operated, uncontended run
`p3-20260925T174212Z-aff7` against served build `brJCAsidY8FAIgtIzNtSo`, suite commit
`3a1092f29cc4520d7fc58538bee3a2987cafb0a0` and kit manifest
`d60f802467a87a8b1a9a228d63f05fd11cd79d7c742d0ac63e108c5b0fa8eb0c`.
- **Serving identity basis:** the frontend build was observed live before signup and again in S00.
  Backend identity rests on the hash-pinned #320 deploy artifacts and the run-time GitHub check
  for later deployments/workflow runs, **not** a fresh Azure revision read. A change made outside
  GitHub Actions could escape that check; artifact hashes bind bytes, not provenance.
- All 15 scenarios S00–S13 and S99 passed; 535 ledger checks passed, none failed, with no skipped,
  filtered, unrun or unexpected scenario. The verdict is **`PASS_WITH_EXPECTED_DEFECTS`**, not PASS:
  `non-demo-reset-control-visible` was the sole expected defect.
- The binding and check re-derived byte-identically and reported `BOUND`; the owner scan was clean.
  All 90 run files matched manifest `c5f16bf63befdf6037e4a94c29d4dd42b02dc67ad17577108e83f5791604eec1`.
  S99 confirmed both CERT baselines. Keep-alive returned 24/24 HTTP 200s. The three suite users
  had full price coverage; this does not re-check the unheld stale FTM ticker. S12 received a
  chat reply but could not distinguish a model response from fallback. The owner reported no
  other planned Production activity, but public demo traffic could not be excluded. These are
  run-bound findings, not a general Production certification or proof of every #320 UI edge case.
- The owner separately approved cleanup C and ran it once. Its preview identified only this run's
  CERT_A, CERT_B and FRESH users (3 users, 3 credentials, 3 portfolios, 10 holdings). The local
  cleanup record (`p320-cleanup.txt`, SHA-256 `e150f7722889631fe26147f33a0647c62bcd95c0ff7eb556f6e3cd0e8ac9b0fd`)
  reports COMMITTED, zero target users/credentials and zero orphaned holdings on its verify query,
  and both CERT logins rejected with 401. The owner's console output additionally reported zero
  orphaned portfolios. The saved-password file was removed. FRESH has no saved password, so its
  deletion rests on the cleanup script's database query. The historical deletion counts were
  **not independently verified against the database**. Run evidence stayed unchanged.
- The S99 secret scan passed for text artifacts **within its scope**. It excluded screenshots,
  binary files and `pw-output/` traces; those are not certified secret-free and stay private.

| Phase | Status now | Next exit action |
|---|---|---|
| 1 — Asset Picker delivery | **COMPLETE**, deployed and accepted in Production on 2026-09-20 | None; do not reopen for later-phase work |
| 2 — UI and demo-critical fixes | **COMPLETE at its accepted exit**; #320's later build was rehearsed, targeted-checked and suite-tested within the stated coverage | Keep the accepted limitations visible; do not infer every edge case was tested |
| 3 — Broad desktop Production E2E | **Post-#320 suite accepted** as `PASS_WITH_EXPECTED_DEFECTS`; all 15 scenarios passed and this run's three users were cleaned up. A4 run 3 remains old-build history, with runs 1-2 still FAIL history | No further full-suite run is planned; any material new fix would require reassessment |
| 4 — Defect disposition | **Demo disposition recorded:** #320 repairs were deployed, targeted #3–#6 checks ran, and the suite's one expected defect is accepted. Named inconclusive/unverified cases remain non-blocking for this demo, not PASS/fixed/closed | Retain the evidence limits below |
| 5 — Documentation | **IN PROGRESS:** suite/cleanup status filed through #323 (`d515aa5b`), backlog through #324 (`6f1e5700`), and runbooks through #325 (`5d559478`). Roadmap/root README/v5 reconciliation is prepared, including three unscheduled owner requests; it is not filed until its reviewed carrying PR merges. The operator script remains a private reviewed draft | Independently review and file the roadmap/README/v5 bundle under owner approval. Continue the wider documentation/maintenance handoff; no media-brainstorming dependency for factual reconciliation |
| 6 — Demo material | **Post-fix rehearsal COMPLETE (2026-09-25):** the rehearsed script on the #320 build; the E2E account was restored identical to its verified baseline. The later wording-amended draft was not re-rehearsed. The LinkedIn, resume, PPT and video package is required before project freeze and not yet drafted | Run the demo with the script's warm-up; media content waits for the brainstorming session |

**Technical demo evidence is complete within its stated scope:** the post-#320 suite, targeted
checks and final-build rehearsal are distinct records. The suite does not turn accepted
inconclusive or unverified observations into passes or fixes.

**Fast-track status publication gate complete:** #323 merged the qualified suite/cleanup status
at `d515aa5b` on 2026-09-25. No technical or status-publication fast-track gate remains open under
the accepted desktop-demo contract. This does not complete the separately required freeze
documentation or media package, or close accepted/unverified defects.

The operator-script review, final-build rehearsal, targeted #3–#6 demo dispositions, full suite
and cleanup are done. The seven non-USD #3 value checks and listed edge cases remain inconclusive
or unverified despite their non-blocking demo disposition.

**Freeze package, separately incomplete (owner direction):** reconciling and auditing the existing
roadmap, runbooks and backlog, and creating the LinkedIn, resume, PPT and video package. Neither is
a fast-track demo blocker. A5 remains optional.

**Post-#320 evidence snapshot (2026-09-25):** #320 merged at `db51cf5b`; scoped backend deploy
[36092375156](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/36092375156)
and frontend deploy
[36092953727](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/36092953727)
reported success. The refresh job succeeded with 158 updated, one skipped (`FTM-USD`) and zero
failed. The local repair records report verified removal of 474 seed-history rows and 810 obsolete
symbol-history rows; these are repair records, not a new full-suite result. Signed-in spot checks
showed the corrected AI copy, INR-to-USD edit estimates and absence of the 11/13/15 August chart
spikes. `FTM-USD` remains stale; 24-hour portfolio coverage was partial (151/159). A controlled
warm page load showed Edit Holdings after 2.7 s, but the warm-up has ended and must be rerun before
a demo. The signed-in spot checks did not save a holding or prove the chat's model-versus-fallback
path. The detailed deploy/repair evidence remains in the private local evidence folder.

**Post-fix rehearsal (2026-09-25, 07:25–07:36Z; owner-approved; Claude operated, the owner signed
in):** it ran on served build `brJCAsidY8FAIgtIzNtSo` with the revised operator script.
- **Warm-up:** the default warm-up reached `GO` in about 3 minutes, and a 45-minute keep-alive
  followed.
- **Baseline:** 1 portfolio, 159 holdings with 159 distinct tickers, exact quantities, version 4.
  The saved snapshot verified against the API digest.
- **The edit:** one quantity change and one removal, saved with "Holdings saved." in 20.5 s. The
  Overview then showed exactly the arithmetically expected total.
- **AI Insights:** the cards read "Change over last 10 prices". One chat question was answered in
  11.5 s with the scoped source label; that does not prove whether the model or the fallback wrote it.
- **The restore:** re-add and revert, saved in 19.9 s. The after-snapshot was **`IDENTICAL`** to the
  baseline; only the version changed, 4 → 6. Codex independently confirmed that the two local
  holdings files are byte-identical, 159 lines each.
- **Stop rules and sign-out:** no stop rule was triggered, and the session signed out.
- **Not covered:** the rehearsal did not compare a currency-pair dialog with the table (#4).

**Targeted #3–#6 session (2026-09-25, 11:08–11:19Z; separate from the rehearsal and the full
suite):** the owner-approved checks ran on served frontend build `brJCAsidY8FAIgtIzNtSo`.
Backend revision identity was inferred from GitHub Deploy runs, not read from Azure. The
private run is `targeted-3-6-20260925T110544Z`; its 18 manifest entries were re-verified, and the
governing owner-review addendum has SHA-256
`d652a4b4cb3934b77526993498a942e3a84b5c250dfc91ddd93f7fb581b247ea`. Where it differs
from the original record, use the addendum.
- **#3:** sampled price-currency labels PASS and the two USD table-value comparisons PASS. Seven
  non-USD table-value comparisons are **INCONCLUSIVE** because the FX response was not captured;
  the owner accepted them as non-blocking for this demo, not as PASS or fixed.
- **#4:** Edit Holdings estimates matched table values for all nine sampled holdings.
- **#5:** AAPL chat price, trend and window matched the card. The UI label indicates Azure OpenAI
  sentiment, potentially cached; the raw response was not captured, so this does not show that
  this request called the model. The bulk cards' “Sentiment Unavailable” is expected.
- **#6:** the visible August spikes were absent in the tooltips and 45-point rendered chart. This
  is a **UI/code-inferred** result, not direct verification of the analytics response or a saved
  screenshot.
- **Holdings:** the recorded live digests and version matched before and after. The retained
  snapshot files are copies matched to those reported digests, not preserved live API responses.
  No portfolio save occurred. The session used one chat question and signed out.

The owner also accepted the unverified unknown-currency display, non-USD header rendering,
analytics-unavailable fallback, natural-language chat resolution and broader model-text reliability
as **non-blocking demo limitations**, not tested/fixed/closed. FX-pair holding semantics is also
unverified and owner-accepted as non-blocking for this demo; it remains an open product question.
No targeted re-check was requested. The later post-#320 suite is recorded in the dashboard; it
does not resolve these targeted-check evidence limits.

**Fast-track demo-ready exit (not a general-production or `1.0.0` claim):** Phases 1 and 2 are
accepted; B3 proves that the D11 portfolio-service revision and the uploaded frontend build serve
traffic; the owner-operated Phase 3 desktop suite completes once uncontended with no skipped or
uncollected scenario counted as a pass; Production state is restored or its retained state recorded;
and no unresolved finding blocks the agreed live demo. A verdict with expected defects must name
them and be accepted explicitly, not be relabeled PASS. Record accepted non-blocking findings and a
short operator script. Full Phase 5 documentation, the freeze package (reconciling the existing
roadmap, runbooks and backlog; creating the LinkedIn, resume, PPT and video package) and an
independent A5 before/after Azure attestation are **not** fast-track demo blockers. The freeze
package is still required before project freeze. The B3 run-bound revision/digest
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
functional Production acceptance was part of A4, which run 3 satisfied on 2026-09-24.

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
portfolio revision/digest. This was point-in-time serving identity evidence, not authenticated page
or D11 response-content evidence; A4 run 3 subsequently exercised those behaviors on that build.

The accepted 320px/375px overflows remain open backlog. Task 7 is complete. A4 run 3 met the
pre-#320 Phase 3 exit; after #320 and the subsequent data repairs changed the candidate, the
post-fix suite met the new technical gate on 2026-09-25. The final-build rehearsal also ran that
day. Publication of this updated status remains the dashboard's fast-track gate. A5 is optional
independent attestation.

### Immediate fast-track sequence

1. **B3 — COMPLETE and accepted for A4's historical candidate.** Runs 35860682429 and
   35862375385 proved serving identity at `main@cdc51df6`. #320 was deployed later; its scoped
   deploy evidence is recorded in the 2026-09-25 dashboard and is not a new B3 acceptance.
2. **A4 — SATISFIED by run 3 (2026-09-24, Codex decision); runs 1 and 2 are FAIL history.** The
   original instruction is kept below for traceability. Two things changed by owner decision:
   - scale-to-zero cold-start slowness is an accepted cost trade-off, worked around by a gateway
     wake, a backend warm-up and a keep-alive;
   - the refresh-window and clock-time restrictions were agent-chosen and were removed for run 3,
     which then ran outside the refresh job's 08:00Z schedule.

   Original instruction: once both B3 proofs pass, create
   retained `CERT_A` and `CERT_B` once (or use already-approved isolated accounts) and run the
   existing full Phase 3 suite from the owner's machine. Its S02 signup creates one permanent
   `FRESH` account per run; record that lifecycle, keep secrets/private `pw-output/` local, and use
   the deploy run's emitted build ID for S00. Make the D1/D2/D4-D10 choices in the Phase 3
   [execution packet](../superpowers/plans/2026-09-22-phase3-preparation-execution-packet.md) as
   one bounded run decision, not a new source-work program. Pre-disposition the known F2/F3/F5/F9
   behavior as blocking or accepted with an honest expectation before the run. One real chat request
   keeps that scenario complete; skipping it makes the verdict incomplete. Run outside the FX and
   market-refresh windows. No automatic Production rerun or account creation is implied.
3. **#320 repair batch — DEPLOYED, targeted demo checks and dispositions recorded.** The seven
   symbol remaps, historical price repair and #3–#6 session are recorded above and in Phase 4. Do
   not promote them into full multi-user certification or relabel inconclusive cases as PASS.
4. **Post-fix technical gate — MET; status publication pending.**
   - **Rehearsal, COMPLETE:** the operator script's rehearsed copy ran against the #320 build
     on 2026-09-25 and restored the E2E account exactly (dashboard). Its later wording amendment
     was not re-rehearsed.
   - **Suite and cleanup, COMPLETE:** the owner-operated run `p3-20260925T174212Z-aff7` passed
     with one expected defect; Codex accepted its bound evidence. A separately approved cleanup
     removed this run's three temporary users as reported by the script's own preview, commit
     and verify queries; the database result was not independently checked.
   - **Status publication complete:** #323 merged at `d515aa5b`. The later backlog audit has
     its own review/publication step and is freeze documentation, not a reopened technical gate.
   - **Keep in the script:** the chat fallback, the known limitations and the warm-up/keep-alive.
   - **Before project freeze:** reconcile and audit the existing roadmap, runbooks and backlog; this
     factual cleanup need not wait. Create the LinkedIn, resume, PPT and video package once the
     brainstorming session has set its content. A full architecture rewrite and `1.0.0` can follow
     after the demo.

## Owner approval callouts

The owner separately authorized the completed Phase 1/2 work, B3, A4 and its cleanup, the
2026-09-24 and 2026-09-25 rehearsals, the targeted #3–#6 session, the #320 deployment and bounded
repair/validation actions, and post-#320 suite R and cleanup C. Those authorizations are consumed.
They do not automatically authorize another suite, rehearsal, account creation, production/cloud
access, future deployment, ruleset change, publication or merge. Any new live action requires
fresh, named bounds.
- **#319** closed unmerged; **#321** merged at `c4e58f1c`; **#322** merged at `b4e989b3`.
  The owner's conditional publication approval for #322 does not cover this new status change.
  A5 remains optional and requires approval if performed.

## Global constraints

- Phase 1 and 2 are accepted; B3 and A4 run 3 apply to the earlier candidate. The post-#320
  suite separately meets the final technical gate with one expected defect. #320's targeted-check
  evidence limits remain as recorded. Comprehensive documentation and media do not gate the
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

**Status:** B3 serving proof is complete on `main@cdc51df6`. The original A4 Production-suite exit
was **met on that candidate by run 3** (`p3-20260924T032033Z-52f1`,
`PASS_WITH_EXPECTED_DEFECTS`, Codex, 2026-09-24). The materially changed #320 build was then
tested in a separate owner-operated run, `p3-20260925T174212Z-aff7`, accepted by Codex as
**`PASS_WITH_EXPECTED_DEFECTS`**. Its 15/15 scenarios and 535/535 checks passed, with no skipped
or unrun scenarios; the sole expected defect was `non-demo-reset-control-visible`. The post-fix
technical suite requirement is **MET**, within that run's scope. Historical A4 checklist items
remain credited only from run 3's own bound evidence, never from runs 1 or 2, CI, B3 identity
checks or local browser tests. The new post-fix item below is credited only from its own bound
run. The merge and B3 items are credited from their PRs and runs.

- [x] Merge the multi-user suite and the #418/F1 frontend corrections (PR #310).
- [x] Merge D11/F13 position-level 24-hour value and coverage (PR #311).
- [x] Merge the backend revision/traffic binding, frontend served-build proof, S00 Production stop
  gate and its four-arm control (PRs #313-#315).
- [x] B3: deploy portfolio-service, then the frontend, and accept both run-bound serving proofs
  (runs 35860682429 and 35862375385 at `cdc51df6`).
- [x] A4: complete one owner-operated, uncontended Production run. **Run 3,
  `PASS_WITH_EXPECTED_DEFECTS`**; runs 1 (S05) and 2 (S12) are FAIL history. Disposition of its
  findings continues in Phase 4.
- [x] After the #320 material code change, run the affected scenarios and a full uncontended
  Production suite against served build `brJCAsidY8FAIgtIzNtSo`: run
  `p3-20260925T174212Z-aff7`, `PASS_WITH_EXPECTED_DEFECTS`, `BOUND`, accepted by Codex on
  2026-09-25. This is separate from the signed-in spot checks and from A4 run 3's accepted
  historical verdict. The three run-created users were subsequently cleaned up under approval C;
  the database result has the evidence limit stated in the dashboard.

**Browser matrix:** desktop only, at the agreed demo viewport(s). The owner narrowed the
demonstration contract to desktop, so the deferred narrow-width overflows are backlog debt rather
than Phase 3 scope. That narrows the viewports and nothing else: every multi-user, isolation,
persistence, conflict, freshness, evidence, and uncontended-run requirement below applies in full.

**Product version:** Phase 3 runs against a functionally complete `0.y.z` candidate. `1.0.0` stays
blocked until Phase 3 and the remediation or explicit acceptance of its findings are complete; the
full gate is the `1.0.0` boundary in the [versioning policy](../release/SEMANTIC_VERSIONING_POLICY.md).

**Serving blocker closed; functional verification met by A4 run 3:** B3 proved the compatible
backend and frontend identities serve at the accepted SHA. It did not sign in or inspect D11 API
responses. A4 had to check #418, D11/F13 and cross-page 24-hour/freshness presentation against real
user data rather than infer correctness from build and revision identity. Run 3 did so:
- **#418:** zero page errors in every scenario's common checks.
- **D11/F13 and 24-hour presentation:** S11 checked the 24-hour totals, coverage and cross-page
  agreement at three viewports.
- **Partial valuation:** not exercised (Phase 3 item above).

- [x] Before Production mutation, declare the identity lifecycle: retained named certification
  accounts or an explicitly approved cleanup mechanism. The lifecycle was retained `CERT_A` and
  `CERT_B` plus one permanent `FRESH` user per run, with owner-approved deletion afterwards. All five
  users were deleted on 2026-09-24.
- [x] Create or use 2-3 isolated users with distinct holdings and quantities: CERT_A, CERT_B and
  the FRESH user, in run 3.
- [x] Cover signup validation, successful signup, login, logout, and re-login (run 3, S01-S04).
- [x] Cover empty portfolio, add, quantity update, removal, review, save, cancellation, validation
  failure, and persisted readback (run 3, S02 and S05-S08).
- [x] Induce and prove optimistic-concurrency conflict behavior without automatic retry, silent
  discard, or cross-user interference (run 3, S09: 409, no retry).
- [x] Verify Overview, Portfolio, holdings/asset cards, totals, price/freshness presentation,
  Market Data, navigation, and AI Insights/chatbot (run 3, S11 at three viewports and S12).
- [ ] Verify partial-valuation presentation. **Unverified in Production:** in A4 run 3 no holding
  was partially valued, so the check that records D9 (`partial-valuation-not-presented`) was never
  reached. The post-#320 run also had full coverage for its three users and did not reach D9.
  D9 remains a known non-blocking defect (Phase 4); neither run is evidence that it was fixed.
- [x] Prove user and session isolation (run 3, S10).
- [x] Record per-scenario assertions, traces, screenshots, video on failure, network evidence,
  served revision, and final persisted state (run 3: ledger, `network.jsonl`, 53 screenshots,
  `final-state.json`, and private local traces in `pw-output/`). **Identity note:** A4 observed the
  served frontend build ID. The portfolio-service revision and digest come from B3's accepted
  run-bound proof, not from A4.
- [x] Run the full suite once uncontended. Do not count skipped, filtered, or uncollected scenarios
  as passes (run 3: 15 of 15 passed, none unrun, `filtered: false`).

---

## Phase 4: Disposition Phase 3 findings and fix demo blockers

**Status:** A4 run 3's findings have recorded decisions. The 2026-09-24 rehearsal then exposed
additional demo defects; #320 and bounded data repairs addressed several of them. The owner
dispositioned the targeted 2026-09-25 #3–#6 observations and their evidence limits. The later
post-#320 full suite passed with the one known reset-control defect; it did **not** turn
INCONCLUSIVE, unverified or "not triggered" observations into "fixed".

| A4 finding (historical) | Seen in | Recorded decision | Disposition needed |
|---|---|---|---|
| `non-demo-reset-control-visible` (D5/F3): the reset control is shown to a non-demo user | A4 run 3 and post-#320 S13 | Owner D5: known non-blocking defect | None; recorded as a limitation |
| D7: a pre-logout token is still accepted after sign-out | A4 runs 1-3 and post-#320 S04 | Owner: accepted Phase 4 defect | None; recorded as a limitation |
| `partial-valuation-not-presented` (D9) | not triggered in A4 runs 2-3 or post-#320; the latter had full coverage for its three users | Owner D9: known non-blocking defect | None; still unverified in Production |
| `analytics-cache-stale-after-holdings-write` (D10) | not observed in A4 runs 2-3 or post-#320 | Owner D10: known non-blocking defect | None; not fixed by lack of observation |
| Scale-to-zero cold starts: run 1's S05 dialog stayed open past the 20 s bound (best-supported cause: the unbounded post-save price read against a cold `market-data-service`; not directly observed); a ~79 s S00 stall | run 1 | Owner: accepted cost trade-off; no `min_replicas = 1`, no fix; warm up before the demo | Record the warm-up step in the operator script |
| Chat reply exceeded 20 s | run 2, S12 | Codex-accepted demo limitation (150 s wait, `1c3a132f`); run 3's reply took ≤ 19.2 s | Record in the operator script's fallback |
| `empty-portfolio-shows-filter-copy`: an empty portfolio says "No holdings match your filter." | A4 runs 1-3 and post-#320 S02 | Owner, 2026-09-24: **non-blocking for the fast-track demo**; kept as an open **UX defect**, not fixed or closed | None for the demo; stays in the backlog |
| Playwright "1 error was not a part of any test" | runs 1 and 2 (console only); not in run 3 | Owner, 2026-09-24: **non-blocking for the fast-track demo**; kept as an **unexplained console anomaly**, not a proven harmless error, and not fixed or closed | None for the demo; the cause stays unexplained |

Neither 2026-09-24 decision blocks A4 or requires a new run.

**2026-09-24 rehearsal and #320 repair status (with the 2026-09-25 targeted-check addendum):**

| Finding | Status | Still needed for closure/demo |
|---|---|---|
| #1 Slow saves (21.8 s and 20–38 s) | Not repaired; presenter pacing/"Saving…" is a known limitation. Observed in the post-fix rehearsal: 20.5 s (edit) and 19.9 s (restore) | Keep the wait and no-double-submit instruction in the script |
| #2 Stale/incorrect crypto quotes | Seven symbol transitions deployed; old-series repair verified; refresh updated 158/159 holdings. `FTM-USD` still stale | Treat FTM and partial 24-hour coverage as explicit limitations; do not call all prices fresh |
| #3 INR stock prices labeled as USD / edit estimate | Targeted session: representative labels PASS across seven quote currencies; two USD table values PASS. Seven non-USD table-value comparisons **INCONCLUSIVE** without captured FX rates | Owner accepted the seven inconclusive checks, unknown-currency display and non-USD header rendering as non-blocking **for this demo**. They are not PASS, fixed, tested or closed; no targeted re-check requested |
| #4 Currency-pair estimate/table mismatch | Targeted session: dialog estimates matched table values for all nine sampled holdings, including USDJPY=X | Sampled dialog check PASS. Analytics-unavailable fallback remains unverified but owner-accepted as non-blocking. FX-pair holding semantics is unverified and owner-accepted as non-blocking for this demo; it remains an open product question |
| #5 AI cards/chat disagree | Targeted session: card/chat price, trend and 10-price window matched. UI label indicates Azure OpenAI sentiment, possibly cached; raw response not captured | Do not claim a live model call. Natural-language chat resolution and broader model-text reliability remain unverified, owner-accepted non-blocking demo limits; preserve fallback |
| #6 August chart spikes | Historical seed-row repair verified; the targeted session's 9–17 Aug tooltips and 45-point chart showed no spikes | **Visible symptom passes by UI/code inference**, not by a captured analytics response or screenshot. Keep 3 M&M.NS and 3 unheld TATAMOTORS.NS seed rows recorded as intentional exceptions |
| #7 Browser pane narrowed during restore | Tooling/pane issue in the first rehearsal, not a demonstrated application defect. In the post-fix rehearsal, clicks used element references and the restore was `IDENTICAL` | None beyond the script's pane-size note |

The 20–21 August chart rises were attributed in read-only data checks largely to stored BTC-USD
price changes, with one refresh row per ticker per day; this explains the chart arithmetic, not
the market-price provenance. The 2026-09-25 page-load comparison showed cold-start latency with
the short keep-alive expired and a 2.7 s Edit Holdings appearance immediately after default
warm-up `GO`; the demo still needs warm-up plus keep-alive throughout.

**Evidence limitation (not a new defect):** A4 run 3's S12 did not distinguish a model reply from
fallback. The later targeted session's UI label indicates Azure OpenAI sentiment, but the raw chat
response was not captured and the request may have used a cached result. The operator script
retains a fallback.

**Fast-track exit outcome:** no unresolved
finding breaks the agreed live desktop demonstration. Repair demo blockers, and explicitly accept or
defer non-blockers with an honest demo limitation. This is not a claim that every product defect has
been fixed or that the product is generally Production-certified.

- [x] Record and disposition the A4 run-3 findings without pretending untriggered defects were fixed.
- [x] Deploy the bounded #320 repair batch and record the targeted repair/spot-check evidence.
- [x] Record the targeted #3–#6 observations and the owner's non-blocking demo dispositions (the
  table above). Retain seven #3 value checks as INCONCLUSIVE and other unverified cases as such;
  do not mark them PASS, fixed, tested or closed. Keep #1, FTM and other accepted limits visible.
- [x] Rerun affected scenarios and the full Production suite uncontended after the material #320
  code changes. The bound `p3-20260925T174212Z-aff7` run, not the old-build A4 run or
  post-deploy spot checks, satisfies this rule with `PASS_WITH_EXPECTED_DEFECTS`.
- [x] Rehearse on the final served build with exact account restoration and record the final
  artifact identity and outcome: 2026-09-25, served build `brJCAsidY8FAIgtIzNtSo`, restore
  `IDENTICAL` (dashboard).

The earlier "no code change needed" exception no longer applies: #320 made material changes after
the accepted A4 run. A4's historical verdict remains accepted for its own candidate.

No **demo-blocking** defect of any severity may be deferred while calling the fast-track demo ready.
A non-blocking finding may be deferred only with an explicit owner product decision, recorded
limitation and, where relevant, a test expectation that does not claim the defect is fixed.

---

## Phase 5: Reconcile documentation

**Status:** In progress. #321 merged the A4 verdict and earlier status at `c4e58f1c`; #322
published the targeted-check reconciliation at `b4e989b3`; #319 closed unmerged. The operator
script was rehearsed on the #320 build, then amended in wording and Codex-reviewed. The amendment
changes no click, save or restore step, but was not re-rehearsed; it remains a private local draft.
The post-#320 suite/cleanup reconciliation is published through #323 at `d515aa5b`.
The later [backlog audit](../todos/backlog/README.md) records its independent review and is filed
for the project freeze through #324, merged at `6f1e5700`.
The [runbook reconciliation](../runbooks/README.md) is filed through #325 (`5d559478`) at source/
retained-record scope, not fresh operational verification. The [roadmap](../../ROADMAP.md),
[root README](../../README.md) and [enhancements v5](../../roadmap_enahancements_v5.md) are prepared
against that baseline. Their filing requires independent review and owner-authorized merge;
an unmerged copy remains a candidate. The broader documentation pass is not complete.
**Fast-track exit outcome:** keep the published status truthful and the short operator flow
reproducible. The full repository documentation overhaul below is follow-on work, not a
portfolio-demo gate.

- [x] Record B3 run URLs, exact SHA, served revision/digest and frontend build ID in this plan.
- [x] Record the A4 run, verdict, findings and accepted limitations after it occurs. This is
  recorded in this plan and the sanitized
  [A4 verdict record](../evidence/phase3-a4/A4_VERDICT_RECORD_runs-1-3.md), both merged through
  #321 (first published in #319, which closed unmerged). Every A4 defect finding
  has a recorded decision; this does not close later rehearsal findings.
- [x] Publish the earlier 2026-09-25 status through #321, merged at `c4e58f1c`.
- [x] Publish the later targeted-check reconciliation through #322, merged at `b4e989b3`.
  The addendum's evidence distinctions remain intact.
- [x] Publish the accepted post-#320 suite and cleanup status through owner-approved #323,
  merged at `d515aa5b` on 2026-09-25. The expected defect, scan scope and cleanup limits remain.
- [x] Revise and review the short operator script for the current build, evidence location,
  remaining limitations and exact-restore flow; keep it available to the demo operator. The
  rehearsed copy was amended in wording and Codex-reviewed on 2026-09-25 without a second
  rehearsal; publication of the private script is not implied.

**Required before project freeze (owner direction, 2026-09-25).** These documents already exist; the
work is to reconcile and audit them against the delivered state. It is factual cleanup and need not
wait for the brainstorming session, which applies only to the media package (Phase 6).

- [x] Prepare reconciliation of the [roadmap](../../ROADMAP.md), [root README](../../README.md)
  and [enhancements v5](../../roadmap_enahancements_v5.md) against `main@5d559478`; preserve v1–v4
  as history and record Sharpe/Sortino, richer FA/TA chat and exploratory charts as deferred,
  unscheduled requests. Preparation is not independent acceptance or filing.
- [x] Prepare the [runbook reconciliation](../runbooks/README.md): inventory all 24 existing
  files, correct the 2 reusable procedures, classify 3 legacy helpers and 19 historical records,
  and provide [current operations/restart guidance](../runbooks/CURRENT_OPERATIONS.md).
  The 22 retained historical/legacy files, evidence and task-completion ledgers are unchanged.
- [x] Prepare the [backlog audit](../todos/backlog/README.md) and dated TODO dispositions
  against `main@d515aa5b` (2026-09-26 UTC): 8 closed-fixed/completed, 2 closed-superseded and 20
  open directory items. Inline TODO counts are separate; no unverified case is counted as fixed.

**Backlog filing rule:** the index records the independent review and bounded wording follow-up.
The backlog reconciliation is filed through #324 (`6f1e5700`).

**Runbook filing rule:** the independently reviewed reconciliation is filed through #325
(`5d559478`) at source/status scope, not live verification.

**Roadmap/README/v5 filing rule:** this preparation counts as filed only when its independently
reviewed carrying PR merges into `main` under explicit owner publication/merge approval. An
unmerged candidate does not satisfy filing. None of these documentation filings completes the
wider documentation pass, media package, maintenance handoff or private cleanup; the three
new feature requests remain deferred and do not block the freeze.

**Full documentation backlog (not required for fast-track demo-ready):**

- [x] Prepare the root README/ROADMAP/v5 update with delivered capabilities and honest remaining
  limitations; independent review and filing follow the rule above. Other READMEs, architecture,
  release and detailed flow documents remain outside this root-document reconciliation.
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

**Status:** The walkthrough was rehearsed on the final build; the LinkedIn, resume, PPT and video
package is not started.

The first Claude-operated rehearsal ran on 2026-09-24 against the pre-#320 build: the owner signed in, the edit/save/readback/chat/restore journey completed,
all 159 tickers and quantities matched the baseline digest after restore, and the session signed
out. That rehearsal found #1–#7 above. The revised script's full baseline snapshot and pane-size
instructions were not rehearsed; #320 was deployed later. The 2026-09-25 signed-in spot checks and
warm-load comparison were not a rehearsal.

**The second rehearsal, on the #320 build (2026-09-25), was end-to-end:** it used the rehearsed
script copy and ended with an `IDENTICAL` restore (dashboard). The later wording-amended draft was
not re-rehearsed. **Fast-track exit outcome:** a
short walkthrough against the final served build, with exact restoration and a fallback for
chat/market-data unavailability. The LinkedIn, resume, PPT and video package is required before
project freeze, not before the demo.

- [x] Draft the short desktop narrative and rehearse the older build once, restoring the E2E
  account exactly (2026-09-24; historical evidence, not final-build credit).
- [x] Update the operator script to the #320 build, including warm-up/keep-alive, slow-save
  presenter pacing, FTM/partial coverage, chat fallback and stable desktop viewport (2026-09-25;
  local and Codex-reviewed).
- [x] Rehearse the pre-amendment script copy against the final served build and verify the full
  baseline is restored after the reversible edit. Record the outcome and non-sensitive evidence pointers
  (2026-09-25; restore `IDENTICAL`; the evidence is local).

**Media and career package: required before project freeze (owner direction, 2026-09-25).** Its
content awaits the agreed brainstorming session and is not yet drafted.

- [ ] LinkedIn material.
- [ ] Resume material.
- [ ] PPT deck.
- [ ] Video.

**Media production steps:**

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
- **Fast-track portfolio demo ready:** **technical evidence met on the final #320 build; status
  publication pending**. Phases 1-2 are accepted; B3 and A4 certify the older candidate; the
  final-build operator-script review, rehearsal, targeted #3–#6 dispositions and accepted
  post-#320 full suite are recorded with their distinct limits. The three suite-created users were
  cleaned up under separate approval C. The dashboard's remaining fast-track condition is merging
  this latest status update on `main`. The verdict stays `PASS_WITH_EXPECTED_DEFECTS`, and the seven
  non-USD #3 value checks remain INCONCLUSIVE, not PASS. This narrower exit does not imply A5,
  exhaustive backlog closure, polished media or `1.0.0`.
- **Project freeze:** requires first (owner direction, 2026-09-25) reconciling and auditing the
  existing roadmap, runbooks and backlog, which need not wait, and the LinkedIn, resume, PPT and
  video package, whose content awaits the brainstorming session. Do not mislabel those unchecked
  items as completed.
