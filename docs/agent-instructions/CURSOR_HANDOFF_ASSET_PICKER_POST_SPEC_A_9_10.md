# Cursor Handoff — Asset Picker Program after Spec A 9.10

**Handoff date:** 2026-08-24

**Authoritative documentation revision:** `main@48ef468f2a679f6031d892873eb7f9b185e2958f`

**Runtime/program-state code baseline:** `main@e221662b6c891639a56894289e150ee01fb537f6`.
Commit `48ef468` changes documentation only; it does not change application or production state.

**Implementer transition:** Claude → Cursor

**Cutoff:** Spec A checkpoint 9.10 is complete. Checkpoints 9.11–9.14 have not started and are not
authorized. No Asset Picker implementation is being handed over as complete.

This document is the self-contained entry point for Cursor. It transfers current state and safety
boundaries; it does not grant blanket permission to merge, deploy, or operate production.

## 1. Read these documents in order

1. [`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md) —
   living program dashboard and current source of status visibility.
2. [`.kiro/specs/supported-asset-integrity/tasks.md`](../../.kiro/specs/supported-asset-integrity/tasks.md)
   — Spec A implementation and cutover ledger.
3. [`.kiro/specs/portfolio-composition-contract/requirements.md`](../../.kiro/specs/portfolio-composition-contract/requirements.md),
   [`design.md`](../../.kiro/specs/portfolio-composition-contract/design.md), and
   [`tasks.md`](../../.kiro/specs/portfolio-composition-contract/tasks.md) — B1 safe backend contract.
4. [`.kiro/specs/asset-picker-composition/requirements.md`](../../.kiro/specs/asset-picker-composition/requirements.md),
   [`design.md`](../../.kiro/specs/asset-picker-composition/design.md), and
   [`tasks.md`](../../.kiro/specs/asset-picker-composition/tasks.md) — B2 product behavior and work plan.
5. [`docs/runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md`](../runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md)
   — durable evidence for the last completed production checkpoint.

Do not execute
[`CURSOR_KICKOFF_B1_WAVE_2_GATEWAY_PROVISIONING.md`](CURSOR_KICKOFF_B1_WAVE_2_GATEWAY_PROVISIONING.md).
It is a superseded historical kickoff and is retained only for provenance.

## 2. Current truth in one page

### 2.1 What users can do today

There is **no functional Asset Picker** in the application.

- The canonical Active Asset catalog exists inside the services.
- Historical catalog/data inconsistencies were repaired.
- Unsupported-holding and unsupported-event enforcement is enabled.
- One controlled market-data refresh completed and was fully reconciled.
- There is no `GET /api/assets` controller on `main`.
- Portfolio reads do not yet expose the required version contract.
- There is no safe public `PUT /api/portfolio/holdings` composition endpoint.
- There is no picker button, modal, browse/review/conflict UI, or full-stack picker E2E proof.

Recent flakiness fixes removed delivery blockers; they were not the Asset Picker feature itself.

### 2.2 Program progress

| Track | Complete | Current boundary |
|---|---|---|
| Spec A — catalog/data cutover | Checkpoints 9.1–9.10 | 9.11–9.14 pending and separately gated |
| B1 — composition backend | Waves `P`, `0`, and `1` | Wave 2 partially implemented only in draft PR #131; Waves 3–7 pending |
| B2 — Asset Picker product | Requirements, design, tasks, mockup | No implementation wave complete |
| Demo credibility | Canonical refresh/reconciliation | Activation waits for Spec A 9.12 |

The complete wave-by-wave dashboard is in the master plan. Do not duplicate or independently
reinterpret its status in a new tracker.

### 2.3 Production fences at handoff

- `MARKET_DATA_JOB_RUNNER_ENABLED=false` remains persisted.
- Refresh retry limit is `0`.
- Gateway ingress remains closed.
- `portfolio-service`, `market-data-service`, and `insight-service` remain at `min_replicas=1` for
  the verification window.
- Catalog enforcement is live.
- The controlled refresh override was not persisted.
- Demo portfolio activation has not run.
- The deployed R4 service image tag remains
  `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900`.

Do not change any of these as part of the first Cursor task.

## 3. Active and stale work

### Draft PR #131

- URL: <https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/131>
- Branch: `feat/b1-wave-2-gateway-provisioning`
- Verified head: `1d3bd730b4d13900a687ee448158cc66582728ca`
- State at handoff: open draft; tasks 2.1 and 2.3 implemented but not on `main`.
- Its checks are from 2026-08-21 and are not evidence against current `main`.
- Do not merge it as-is. Rebase it onto current `main`, inspect every conflict against Spec A's
  subsequent changes, finish the remaining Wave 2 gates, and obtain fresh review and CI.

No unmerged branch changes the status of `main`. The master plan deliberately labels such work
“implemented but unmerged.”

## 4. Default first Cursor assignment — visibility guard

If the owner gives this handoff to Cursor without naming a different task, the first assignment is
the **master-plan/status propagation CI guard** described in §0.2 of the master plan.

### Objective

Make the living-status rule mechanically visible so future Spec A/B1/B2 work cannot quietly leave
the program dashboard stale.

### Required behavior

1. Detect PRs that touch governed Asset Picker program surfaces: Spec A, B1, B2, their relevant
   release/infrastructure contracts, or an explicitly tracked Asset Picker blocker.
2. Require one of these two auditable outcomes:
   - the PR updates the living master plan and the owning task ledger; or
   - the PR body contains `Master-plan impact: none` plus a non-empty explanation.
3. Fail closed when impact metadata is absent or malformed.
4. Do not require a false completion update merely because implementation exists on an unmerged
   branch. Status wording must continue to distinguish merged from unmerged work.
5. Add adversarial contract tests for at least: missing master-plan update, missing owning ledger,
   empty “none” rationale, valid status update, and valid no-impact declaration.
6. Wire the guard into required CI without weakening, bypassing, or replacing existing checks.

### Scope and stop condition

- This is a process/CI implementation PR only; no application behavior, Terraform desired state,
  production setting, or Asset Picker feature code belongs in it.
- Update the master plan only to record the guard as implemented-but-unmerged while the PR is open.
- Run the repository's relevant structural/contract tests and `git diff --check`.
- Open a normal PR whose body states its master-plan impact.
- **Stop with the PR open.** Do not merge it and do not start the next product or operational lane
  without owner review.

If repository constraints make PR-body inspection unreliable, stop and document the limitation;
do not silently reduce the rule to a path-only approximation.

## 5. Work after the guard requires an owner choice

These lanes are intentionally parallelizable, but none is implicitly authorized by this handoff.

1. **Backend:** rebase and complete B1 Wave 2 draft PR #131; B1 Wave 4 implementation is also
   independently startable where its declared predecessors are satisfied.
2. **Frontend:** start only the mock-backed, dependency-free subset of B2 Wave 1 against frozen
   contracts. Do not claim live save integration.
3. **Operational:** design/review Spec A 9.11, then request a separate explicit production approval.
   Continue 9.12–9.14 one checkpoint at a time.

A lane kickoff must identify the exact task IDs, predecessor evidence, files in scope, tests, stop
condition, and whether it can affect production.

## 6. Open B2 decisions — do not choose silently

1. Demo reset idle threshold; 30 minutes is provisional.
2. Manual reset control placement in the UI.
3. Presence TTL; 150 seconds is provisional.
4. Login self-call timeouts; 2 seconds per leg and 4 seconds overall are provisional.
5. Decimal-adapter deployment sequencing relative to B1 Wave 4/5.

Mock-backed picker-shell work may begin without resolving these. The affected reset/presence/live
integration work may not treat the provisional values as approved decisions.

## 7. Working protocol for Cursor

1. Start from current `main`; verify the exact SHA and a clean worktree before branching.
2. Read the owning requirements, design, and tasks completely before changing implementation.
3. Treat checked task boxes as evidence claims, not aspirations. Verify the referenced bytes and
   tests before relying on them.
4. Preserve unrelated and untracked user work.
5. Use a normal feature branch and PR. Never push directly to protected `main`, bypass required
   checks, or infer merge/deploy permission from green CI.
6. Keep application changes, evidence-only documentation, and irreversible production operations
   separated when their review/authorization boundaries differ.
7. Every status-changing PR updates both the owning task ledger and the living master plan. If the
   status truly does not change, use the required PR-body declaration and explain why.
8. Verification must run against committed `HEAD`, not merely a dirty working tree.
9. Never commit secrets or raw operational artifacts. `.artifacts/` is local-only and ignored.
10. Stop at every explicit operational gate. A plan, merge, green CI, or environment approval does
    not broaden the owner's authorization.

### Documentation integrity checks

For changes that touch the program specifications, run the repository's existing reference guards
for the affected spec and confirm `git diff --check` is clean. At this handoff revision, the last
documentation review found:

- Spec A: 196 declared references, zero dangling references.
- B1: 293 declared references, zero dangling references.
- B2: direct cross-document link and stale-state scans clean; the generic reference checker does
  not parse B2's current task format and must not be reported as B2 coverage.

Run the task-specific unit, integration, contract, and end-to-end suites declared by the owning task
plan. Do not substitute a generic unit task for a separately configured integration-test source set.

## 8. Status reporting format

Every Cursor checkpoint report should answer, in this order:

1. What user-visible capability changed?
2. What program task/wave changed state, and is it merged or merely on a branch?
3. What evidence was independently verified?
4. What remains pending or blocked?
5. What exact action needs owner authorization next?
6. Was the living master plan updated, or why is the impact explicitly none?

This reporting contract is part of the handoff because owner visibility is a delivery requirement,
not optional project administration.
