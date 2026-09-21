# Cursor Kickoff — CI DAG Critical-Path De-serialization

**Date:** 2026-09-22

**Prepared for:** Cursor (evidence refresh and bounded implementation)

**Baseline floor:** main at 8f2c4cf7947cad316e3d063b5f322b4d78dc64d5

**Required start point:** current main containing this handoff; record its exact SHA before branching

**Suggested branch:** ci/dag-critical-path-deserialization

**Timebox:** one working day maximum

> **OWNER APPROVAL BOUNDARY**
>
> This kickoff authorizes read-only evidence gathering and local workflow/test implementation in
> Cursor's assigned worktree. It does not authorize push, pull-request creation, workflow
> dispatch, branch-protection changes, merge, deployment, cloud or secret access, release,
> Production contact, or broader CI redesign. Stop and request explicit owner approval before the
> first publication or live Actions experiment.

---

## 0. Assignment and stop conditions

Determine whether the current CI DAG can safely run
'pact-consumer -> docker-build-verify' in parallel with
'unit-tests -> integration-tests', then implement the smallest local workflow and contract-test
change only if the evidence supports it and the full task remains achievable within one working
day.

The historical opportunity was approximately 10.1 minutes of green-run wall-clock latency. Treat
that as a hypothesis to refresh, not a current guarantee.

Within the first 90 minutes, return a short checkpoint containing:

1. exact main SHA and current job DAG;
2. confirmed artifact/state dependencies;
3. the homogeneous historical epoch and sample size;
4. current green-run timings and joint unit/integration first-failure rate;
5. expected wall-clock benefit and additional red-run runner consumption;
6. implementation file list and remaining estimate; and
7. GO or DEFER.

Stop with DEFER if:

- a correctness dependency requires the current serial edge;
- retained evidence is insufficient to support the trade-off;
- the change plus required verification is likely to exceed one working day;
- the safe change requires classifier-policy, branch-protection, deployment, or Production work;
  or
- current main has materially invalidated the historical design.

Do not stretch the timebox by dropping negative tests or live evidence.

## 1. Single-worktree and ownership rule

Work only in Cursor's assigned worktree:

    C:\worktrees\wealthmgmtandportfoliotracker-worktrees\wealthmgmtandportfoliotracker-cursor

Before any mutation:

~~~text
git rev-parse --show-toplevel
git branch --show-current
git status --short --branch
git worktree list
~~~

The top-level path must resolve to Cursor's assigned worktree. Do not create a nested or additional
worktree, edit another agent's worktree, or disturb unrelated dirty files. If the assigned worktree
is not clean enough to branch safely, stop and report the exact conflict.

## 2. Read before acting

Read current versions from the recorded baseline:

1. [CI DAG backlog](../todos/backlog/ci-dag-critical-path-deserialization/README.md)
2. [Completed docs-only fast path](../todos/backlog/ci-docs-only-fast-path/README.md)
3. [Deferred broader diff-aware selection](../todos/backlog/ci-diff-aware-job-selection/README.md)
4. [CI verification workflow](../../.github/workflows/ci-verification.yml)
5. [Changed-path and aggregate contract tests](../../scripts/tests/test_classify_changed_paths.py)
6. [Master-plan propagation guard tests](../../scripts/tests/test_master_plan_status_propagation.py)

Do not rely on line numbers or cached excerpts in this handoff. Re-read current job commands,
needs, artifacts, conditions, and aggregate result maps.

## 3. Current architecture to verify

At the baseline floor, the relevant serial chain is:

    unit-tests -> integration-tests -> pact-consumer -> docker-build-verify

The adjacent contracts are:

- unit-tests needs static-guard and changes;
- unit-tests contains the docs-only skip condition;
- integration-tests needs unit-tests;
- pact-consumer needs integration-tests;
- docker-build-verify needs pact-consumer;
- pact-consumer uploads pact-contracts;
- docker-build-verify downloads those contracts and performs provider, image, stack, and
  Playwright verification; and
- required ci-required compares every declared result with every observed dependency result.

The candidate design makes pact-consumer a second classifier-gated root:

- pact-consumer needs static-guard and changes;
- pact-consumer runs only when needs.changes.outputs.docs_only is not true; and
- docker-build-verify remains dependent only on pact-consumer.

This explicit second docs-only condition is the minimum exception required to satisfy both goals:
the Pact/Docker branch remains skipped for docs-only pull requests, but it can still run when
unit-tests fails. Do not retain unit-tests or integration-tests in pact-consumer ancestry, because
either edge would suppress the deliberate negative case. Do not add a new classifier or alter
classification policy; both gated roots must consume the existing changes output.

## 4. Evidence refresh

### 4.1 Current dependency proof

For pact-consumer and docker-build-verify, list:

- checked-out repository inputs;
- downloaded artifacts;
- generated artifacts;
- services or state assumed from predecessor jobs;
- environment and secrets;
- cache dependencies;
- current failure/cancellation behavior; and
- every reason the job might require unit-tests or integration-tests.

Remember that Actions jobs use isolated runners. A predecessor's filesystem does not carry over
unless an artifact or cache explicitly carries it.

### 4.2 Historical query hygiene

- Select a homogeneous epoch based on actual workflow effects, not commit titles.
- Treat workflow run attempts as observations; record run_id, run_attempt, and head_sha.
- Use authoritative pull-request attempts for PR latency.
- Exclude workflow-level cancellations.
- Require expected job names in every included attempt.
- Validate all conclusions against the known Actions enum.
- Compute the joint first-failure event directly:
  - unit-tests failed or timed out; or
  - unit-tests succeeded and integration-tests failed or timed out.
- Reconcile every observation into an explicit bucket.
- Report sample size and uncertainty. Do not turn sparse data into a precise claim.

### 4.3 Metrics

Report separately:

- successful-run wall-clock critical path;
- additional runner-minutes spent on the parallel branch when unit/integration tests fail;
- job-duration distributions, not only one representative run; and
- environmental comparability limits.

## 5. Local implementation, only after checkpoint GO

Use test-first workflow:

1. Replace the contract that unit-tests is the only job carrying the docs-only condition with a
   contract proving that unit-tests and pact-consumer are the only two gated roots, both consume
   the existing changes output, and every downstream skip still propagates from one of them.
2. Run the focused test and confirm it fails on the old graph for the expected reason.
3. Change pact-consumer to need static-guard and changes, and give it the same existing docs-only
   predicate used by unit-tests in
   [.github/workflows/ci-verification.yml](../../.github/workflows/ci-verification.yml).
4. Update only the evidence wording or expected result maps that genuinely change.
5. Rerun the focused test, the complete classifier/aggregate suite, master-plan propagation tests,
   reuse the deploy-workflow-contract job's pinned Actionlint setup and explicitly target
   ci-verification.yml.
6. Verify that the docs-only, full-suite, missing-job, unexpected-skip, failure, cancellation, and
   unknown-result matrices remain fail closed.

Expected implementation scope:

- .github/workflows/ci-verification.yml
- scripts/tests/test_classify_changed_paths.py
- this backlog record or a small evidence report, only if needed to capture measured results

If other source files become necessary, stop and re-scope before editing them.

## 6. Deliberate negative case

The cost side of the decision must be observed, not only inferred from the YAML graph.

Prepare one isolated scratch commit that makes a unit test fail deliberately. On an authorized
live pull-request run, verify that:

- unit-tests fails for the intentional reason;
- the re-parented pact-consumer still runs;
- docker-build-verify still runs after pact-consumer;
- integration-tests does not run after the unit failure;
- ci-required fails; and
- the extra runner consumption is measured.

The deliberate failure must never be merged. Creating or pushing the scratch branch and running
this live experiment require separate owner authorization.

## 7. Safety invariants

- Preserve the legitimate docs-only skip shape.
- The only classifier-gated roots are unit-tests and pact-consumer; integration-tests,
  azure-image-smoke-test, task-8-9-powershell-tests, and docker-build-verify skip by propagation.
- Preserve full-suite behavior for push to main and manual dispatch.
- Preserve every required job and the complete ci-required dependency/result matrix.
- Only the final aggregate may use job-level always().
- Unknown, missing, skipped-when-required, failed, cancelled, timed-out, neutral, or future
  unrecognized conclusions fail closed.
- Do not modify changed-path classification policy.
- Do not modify branch protection, workflow triggers, deployment workflows, schedules, caches,
  secrets, releases, cloud resources, or Production.
- Do not begin broader frontend/backend selection or integration-test runtime optimization.

## 8. Required local verification

Run from repository root:

~~~text
python scripts/tests/test_classify_changed_paths.py -v
python scripts/tests/test_master_plan_status_propagation.py -v
reuse deploy-workflow-contract's pinned Actionlint setup, then run:
./actionlint -shellcheck= .github/workflows/ci-verification.yml
git diff --check
~~~

Also run any focused new test separately and show that it failed before the workflow edit and
passed afterwards.

Before requesting publication approval, verify:

- the diff contains only the authorized files;
- no existing required job or aggregate dependency disappeared;
- the docs-only expected result map remains exact;
- no job other than ci-required gained always(); pact-consumer uses the existing docs-only
  predicate without always();
- no workflow trigger, deployment file, or branch-protection setting changed; and
- the task still fits the one-day boundary.

## 9. Independent review and publication boundary

No model may review its own implementation. After local validation:

1. stop and report the local branch, commit, diff, tests, measurements, and unresolved risks;
2. request owner authorization to push and create the implementation PR;
3. obtain an independent review before any merge decision;
4. if publication is authorized, use exactly one PR-body declaration:

   Master-plan impact: none: CI DAG optimization does not change Asset Picker program status.

5. keep merge, deployment, release, Production, and broader CI work closed.

## 10. Cursor completion report

Return:

1. exact baseline SHA and local branch;
2. GO or DEFER, with rationale;
3. historical epoch, query method, sample size, and uncertainty;
4. old and proposed DAGs;
5. proven dependency analysis;
6. estimated and, when authorized, observed green-run wall-clock savings;
7. estimated and, when authorized, observed red-run runner cost;
8. exact files changed;
9. focused red/green test evidence and complete local validation;
10. confirmation that all excluded surfaces were untouched; and
11. the explicit next approval required.
