# Asset Picker Demo Preparation Implementation Plan

> **For agentic workers:** Execute this plan phase-by-phase. Claude owns implementation, Fable 5.1
> reviews implementation before Codex architecture/status reconciliation and final acceptance. Do
> not let later-phase work expand Phase 1.

**Original draft:** 2026-09-13

**Filed and reconciled:** 2026-09-20

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

**Phase 2 is in progress. Phase 2.1, the shared responsive dashboard shell, is source-complete on
`main` through [PR #297](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/297)
at merge commit `4f288c4a0e8393e78efd7f7449c114e25db78818`.** The accepted head
`c2d3dc66a5995ea7aafff50c691df7c0f6889cd5` replaces the fixed 240px narrow-screen sidebar with a
64px icon rail below `md`, preserves all five navigation links and their accessible names, and makes
the outer shell a non-scrollable `relative` / `overflow-clip` boundary while leaving `<main>` and
the chat transcript as the intentional scrollers.

The Phase 2.1 proof covered four dashboard pages, five viewports, both themes, 18 focused tests, and
130 browser assertions for the extended-chat and navigation defect paths. PR-head CI was green and
the merge triggered no deployment. The screenshots and browser harness are not tracked in the
repository; Firefox, Safari, real touch devices, and Production hosting remain untested. Portfolio
still has horizontal overflow at 320px and 375px from the holdings action row, and Overview still
has horizontal overflow at 320px from the performance range badges. Those are separate Phase 2
slices, so neither Phase 2 nor whole-application technical readiness is complete.

## Owner approval callouts

The original plan kept merge, production operations, test-user creation, cleanup, and external
publication as separate owner decisions. The owner subsequently authorized and completed the Phase 1
deployment and verification actions recorded above. That consumed authorization does not authorize
future deployments, production mutations, creation of additional users, or external publication.

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

**Status:** In progress. Phase 2.1 shared-shell stabilization is merged on `main`. The Portfolio and
Overview narrow-width horizontal overflows are explicitly accepted and deferred for the
desktop-only demonstration; they are **not fixed** and remain open backlog debt. Final
demo-critical backlog triage, the product Semantic Versioning foundation, and the Phase 2 exit
verification remain open. No Phase 2 source has been deployed or Production-verified.

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
- [ ] Triage the remaining backlog and include only defects genuinely critical to the demo.
- [x] Obtain independent review and an uncontended visual/browser verification run for Phase 2.1.
- [ ] After the remaining Phase 2 slices, run the final uncontended visual/browser verification and
  obtain independent Phase 2 exit review.
- [ ] Establish the product Semantic Versioning foundation at `0.9.0` and rehearse the
  non-deploying `v0.9.0` pre-release. Governed by the
  [design](../superpowers/specs/2026-09-20-product-semantic-versioning-design.md), the
  [implementation plan](../superpowers/plans/2026-09-20-product-semantic-versioning.md), and the
  [versioning policy](../release/SEMANTIC_VERSIONING_POLICY.md). The `0.9.0` validation is
  `scripts/validate_product_version.py` and its contract tests, which required `static-guard` runs
  in branch mode. `.github/workflows/release-tag-validation.yml` re-runs the validator in tag mode
  only once a `v*` tag exists, so no tag-mode evidence exists before plan Task 7. The source
  foundation lands with the SemVer implementation PR; the `v0.9.0` tag and GitHub pre-release
  rehearsal (plan Task 7) each await separate owner authorization, and neither deploys anything.

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
