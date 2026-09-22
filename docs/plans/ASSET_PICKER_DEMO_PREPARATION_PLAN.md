# Asset Picker Demo Preparation Implementation Plan

> **For agentic workers:** Execute this plan phase-by-phase. Claude owns implementation, Fable 5.1
> reviews implementation before Codex architecture/status reconciliation and final acceptance. Do
> not let later-phase work expand Phase 1.

**Original draft:** 2026-09-13

**Filed and reconciled:** 2026-09-20

**Latest reconciliation:** 2026-09-22

**Goal:** Deliver the Asset Picker to Production quickly, then stabilize the UI, certify the broader
application through multi-user browser E2E, repair discovered defects, reconcile documentation, and
prepare demo material.

**Architecture:** Use six ordered phases with explicit exit gates. Phase 1 contains only the
dependencies, deployment, and focused live browser proof needed to call the Asset Picker delivered.
The known UI issue and broad application certification deliberately follow after Asset Picker
delivery.

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

React #418 remains a binding Phase 3 blocker and was not suppressed. The accepted 320px/375px
overflows remain open backlog. The review also left two Low-priority presentation/data-consistency
items open for later work: mocked 24-hour/freshness values are not fully consistent across pages, and
the 1280px header ticker's first letter can sit under the fade edge. Phase 2 source has not been
deployed or Production-verified. Task 7 is complete; Phase 3 remains closed pending separate owner
authority.

## Owner approval callouts

The original plan kept merge, production operations, test-user creation, cleanup, and external
publication as separate owner decisions. The owner separately authorized the actions that produced
the Phase 1 outcome, the Phase 2 source merges and exit review, and the completed Task 7 release-only
rehearsal recorded above. Those consumed authorizations do not authorize Phase 3, future deployments,
Production mutations, creation of additional users, cleanup, ruleset changes, or future publication.
This reconciliation records status only and grants none of those authorities.

## Global constraints

- Phase order is fixed: Asset Picker delivery, UI stabilization, broad E2E, bug fixing,
  documentation, demo media.
- Portfolio Settings is deferred and does not gate demo readiness.
- Phase 1 must not absorb the scrollbar fix, general backlog work, the multi-user suite, or the
  documentation overhaul.
- A concurrent test pass is supplementary. Every phase exit requiring tests needs a final
  uncontended pass.
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
scenarios, and independent exit review passed at the level recorded above. React #418 remains an
unsuppressed, binding Phase 3 blocker. The explicitly accepted narrow-width overflows and the two
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

**Browser matrix:** desktop only, at the agreed demo viewport(s). The owner narrowed the
demonstration contract to desktop, so the deferred narrow-width overflows are backlog debt rather
than Phase 3 scope. That narrows the viewports and nothing else: every multi-user, isolation,
persistence, conflict, freshness, evidence, and uncontended-run requirement below applies in full.

**Product version:** Phase 3 runs against a functionally complete `0.y.z` candidate. `1.0.0` stays
blocked until Phase 3 and the remediation or explicit acceptance of its findings are complete; the
full gate is the `1.0.0` boundary in the [versioning policy](../release/SEMANTIC_VERSIONING_POLICY.md).

**Open blocker:** the signed-in React hydration error #418 is binding for Phase 3 and must be fixed
and reverified before Phase 3 can pass. The cross-page 24-hour/freshness presentation must also be
checked against real data rather than inferred from the Phase 2 mock-only discrepancy.

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

## Phase 4: Fix every defect found by Phase 3

**Exit outcome:** All Phase 3 findings are repaired and the complete Production suite passes again.

- [ ] Convert each finding into a bounded defect with reproduction evidence, severity, owner, and
  expected behavior.
- [ ] Fix one coherent defect bundle at a time with a failing regression test first.
- [ ] Require independent review for every implementation bundle.
- [ ] After each fix, rerun the exact failing scenario.
- [ ] After all targeted scenarios pass, rerun the complete Production suite uncontended.
- [ ] Restore or record all Production state and verify the currently served artifact.

No known Phase 3 defect may be deferred while still calling Phase 4 complete. A product decision
that accepts behavior as intended must be explicit and reflected in the test expectation.

---

## Phase 5: Reconcile documentation

**Exit outcome:** Repository documentation describes the system actually demonstrated and provides
reproducible E2E flows.

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

**Exit outcome:** Slides and video are derived from the accepted Phase 5 documentation and final
Production build.

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
- **Demo application technically ready:** Phases 1-4 complete.
- **Demo package ready:** Phases 1-6 complete.
