# B1 G5 Synthetic Dispatch Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent the daily synthetic-monitoring cron from generating G5-like public evidence before the owner separately authorizes a G5 run.

**Architecture:** Remove the top-level unattended `schedule` trigger from the live synthetic workflow, retaining manual `workflow_dispatch` as the only trigger. Extend the existing B1 seed-caller guard so a future edit cannot reintroduce a schedule or remove the manual trigger unnoticed; update the B1/process status surfaces to record that PR #194's evidence review is complete while Task 5.7 remains unchecked.

**Tech Stack:** GitHub Actions YAML, Python 3 standard-library `unittest`, existing repository guard scripts, Markdown.

**Spec:** [`docs/runbooks/B1_G5_INGRESS_BLOCKER.md`](../../runbooks/B1_G5_INGRESS_BLOCKER.md), [`docs/runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../../runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md), and B1 ledger [`.kiro/specs/portfolio-composition-contract/tasks.md`](../../../.kiro/specs/portfolio-composition-contract/tasks.md).

## Global Constraints

- Baseline: `main@98371587902c85881c4041c1b96ff7781dfabd8c` (PR #194 merged).
- Scope is source-only. Do not dispatch `synthetic-monitoring.yml`, Terraform, or any Azure workflow.
- Do not re-enable the daily cron, run a G5 caller, close the backlog item, or check B1 Task 5.7.
- Preserve `workflow_dispatch`; manual dispatch still requires separate owner authorization and must not be represented as G5 evidence by source alone.
- Do not alter `terraform-azure.yml` post-bind probing. Its cold-start resilience is a separate hardening follow-up.
- PR body must contain exactly: `Master-plan impact: updated — B1, process`.

---

### Task 1: Lock the synthetic workflow to manual dispatch

**Files:**
- Modify: `.github/workflows/synthetic-monitoring.yml:1-13`
- Modify: `scripts/check-b1-seed-version-callers.py:30-110`
- Modify: `scripts/tests/test_check_b1_seed_version_callers.py:20-74`

**Interfaces:**
- Consumes: the existing `check_synthetic_workflow(text: str | None = None) -> None` guard entry point.
- Produces: a dispatch-policy assertion called from `check_synthetic_workflow`, so both the unit test and CI's `scripts/check-b1-seed-version-callers.py` invocation fail if an unattended schedule returns.

- [ ] **Step 1: Add failing dispatch-policy tests**

  Add two tests to `CheckB1SeedVersionCallersTest`. The schedule test must add the exact top-level block below immediately after the existing `workflow_dispatch:` line and assert `GuardError` includes `schedule`; the manual-trigger test must remove the existing line and assert `GuardError` includes `workflow_dispatch`.

  ```python
  def test_scheduled_synthetic_trigger_fails(self) -> None:
      synthetic = self.guard._read(self.guard.SYNTHETIC_WF).replace(
          "  workflow_dispatch:\n",
          "  workflow_dispatch:\n  schedule:\n    - cron: '0 8 * * *'\n",
          1,
      )
      with self.assertRaises(self.guard.GuardError) as ctx:
          self.guard.check_synthetic_workflow(synthetic)
      self.assertIn("schedule", str(ctx.exception))

  def test_missing_manual_synthetic_trigger_fails(self) -> None:
      synthetic = self.guard._read(self.guard.SYNTHETIC_WF).replace(
          "  workflow_dispatch:\n", "", 1
      )
      with self.assertRaises(self.guard.GuardError) as ctx:
          self.guard.check_synthetic_workflow(synthetic)
      self.assertIn("workflow_dispatch", str(ctx.exception))
  ```

- [ ] **Step 2: Run the focused test before implementation**

  Run:

  ```bash
  python scripts/tests/test_check_b1_seed_version_callers.py -v
  ```

  Expected: the two new tests fail because the guard does not yet inspect workflow triggers.

- [ ] **Step 3: Implement the minimal dispatch-policy guard**

  In `scripts/check-b1-seed-version-callers.py`, define a small helper called by
  `check_synthetic_workflow` before extracting the seed step. It must reject a top-level two-space
  `schedule:` line and require the top-level two-space `workflow_dispatch:` line. Match lines, not
  prose comments, so an explanatory comment cannot satisfy the guard.

  ```python
  SCHEDULE_TRIGGER_RE = re.compile(r"(?m)^  schedule:\s*$")
  MANUAL_TRIGGER_RE = re.compile(r"(?m)^  workflow_dispatch:\s*$")

  def check_synthetic_dispatch_policy(body: str) -> None:
      if SCHEDULE_TRIGGER_RE.search(body):
          raise GuardError(
              "synthetic-monitoring.yml: unattended schedule is forbidden while B1 G5 is blocked"
          )
      if not MANUAL_TRIGGER_RE.search(body):
          raise GuardError(
              "synthetic-monitoring.yml: workflow_dispatch must remain available for separately authorized runs"
          )
  ```

  Call `check_synthetic_dispatch_policy(body)` as the first line of `check_synthetic_workflow`.

- [ ] **Step 4: Remove the unattended trigger without changing either synthetic job**

  Delete only the `schedule:` block and its cadence/cost comments at the top of
  `.github/workflows/synthetic-monitoring.yml`. Retain:

  ```yaml
  on:
    workflow_dispatch:
  ```

  Add one replacement comment stating that live synthetics are manual-only while B1 G5 remains
  blocked and require separate owner authorization. Do not change either job's `if`, `API_BASE`,
  seed step, Playwright configuration, secrets, or pre-warm step.

- [ ] **Step 5: Run the focused tests and guard after implementation**

  Run:

  ```bash
  python scripts/tests/test_check_b1_seed_version_callers.py -v
  python scripts/check-b1-seed-version-callers.py
  ```

  Expected: all focused tests pass and the inventory still reports exactly three version-bearing
  caller sites.

### Task 2: Keep adjacent workflow documentation and B1 status truthful

**Files:**
- Modify: `.github/workflows/ci-verification.yml:443-448`
- Modify: `docs/runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`
- Modify: `docs/runbooks/B1_G5_INGRESS_BLOCKER.md`
- Modify: `docs/runbooks/SPEC_A_9_14_REOPEN_INGRESS.md`
- Modify: `docs/todos/backlog/api-gateway-custom-domain-binding/README.md`
- Modify: `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`
- Modify: `.kiro/specs/portfolio-composition-contract/tasks.md`

**Interfaces:**
- Consumes: PR #194 merge commit `98371587902c85881c4041c1b96ff7781dfabd8c`, its independent review, and the Task 1 workflow contract.
- Produces: one consistent process record: evidence review is complete; unattended synthetics are suspended; G5 is still owner-gated and Task 5.7 is still unchecked.

- [ ] **Step 1: Update the CI comment that calls the daily cron the liveness source of truth**

  Replace the current `ci-verification.yml` comment that says the daily cron is the single source of
  truth. State that live synthetic monitoring is temporarily manual-only to prevent an unattended
  G5-like run; retain the reason that live tests must not execute on every push.

- [ ] **Step 2: Update every live status surface**

  Record these exact facts consistently:

  - PR #194 was independently reviewed and merged; its live read-back evidence is no longer pending review.
  - `synthetic-monitoring.yml` has no unattended schedule while G5 is blocked; any manual run still needs separately recorded owner authorization.
  - The custom-domain restore and healthy steady state do not satisfy Task 5.7.
  - `- [ ] **5.7 G5 evidence.**` must remain literally unchecked.
  - G5 may resume only after a new, separately authorized run exercises all three real callers (or a separately authorized private-reachability equivalent).

  In `API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`, change the evidence-review status from pending to
  reviewed/merged and retain the separate-G5-decision boundary. In the B1 blocker, backlog, Spec A
  record, master plan, and B1 ledger, remove only the now-stale claim that evidence review is still
  pending; do not rewrite the historical TLS failure or claim G5 success.

- [ ] **Step 3: Run status and structural checks**

  Run:

  ```bash
  python scripts/tests/test_master_plan_status_propagation.py -v
  python scripts/tests/test_check_b1_seed_version_callers.py -v
  python scripts/check-b1-seed-version-callers.py
  ./actionlint -shellcheck= .github/workflows/ci-verification.yml .github/workflows/synthetic-monitoring.yml
  git diff --check
  ```

  Expected: all checks pass; the modified workflow is valid; no changed document leaves Task 5.7
  checked or describes the recovered hostname as a current TLS failure.

### Task 3: Produce the source-only PR and stop

**Files:**
- Review: all files changed in Tasks 1-2.

**Interfaces:**
- Consumes: passing Task 1-2 checks.
- Produces: a reviewable source-only PR; no live action.

- [ ] **Step 1: Inspect the final diff for forbidden scope expansion**

  Confirm no file changes `terraform-azure.yml`, Azure resources, DNS, certificates, secrets, B1 Task
  5.7 checkbox state, or workflow-dispatch invocation. Confirm the only workflow behavior change is
  removing the top-level synthetic cron and retaining manual dispatch.

- [ ] **Step 2: Commit in two reviewable units**

  ```bash
  git add .github/workflows/synthetic-monitoring.yml scripts/check-b1-seed-version-callers.py scripts/tests/test_check_b1_seed_version_callers.py .github/workflows/ci-verification.yml
  git commit -m "ci: block unattended B1 synthetic dispatch"

  git add docs/runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md docs/runbooks/B1_G5_INGRESS_BLOCKER.md docs/runbooks/SPEC_A_9_14_REOPEN_INGRESS.md docs/todos/backlog/api-gateway-custom-domain-binding/README.md docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md .kiro/specs/portfolio-composition-contract/tasks.md
  git commit -m "docs: record B1 G5 dispatch gate"
  ```

- [ ] **Step 3: Open the PR with the correct status declaration**

  Use title: `Block unattended B1 synthetic dispatch while G5 is gated`.

  Include this exact line once in the PR body:

  ```text
  Master-plan impact: updated — B1, process
  ```

  Also state: source-only; no G5 dispatch, Terraform, Azure, DNS, certificate change, backlog closure,
  or Task 5.7 completion occurred. Require independent review before merge.

## Self-review

- Spec coverage: Task 1 removes the unapproved cron and prevents its reintroduction; Task 2 keeps
  workflow comments and every B1/process status record accurate; Task 3 preserves source-only review
  and the B1 propagation contract.
- Completeness scan: all files, commands, assertions, and commit boundaries are named; no deferred
  implementation markers remain.
- Scope check: post-bind cold-start retries are deliberately excluded. They need a separate plan because
  they change `terraform-azure.yml` verification behavior rather than preventing an unattended G5-like
  synthetic.
