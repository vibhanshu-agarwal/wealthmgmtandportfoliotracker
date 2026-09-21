# Backlog: Experiment with CI critical-path de-serialization

**Status:** Authorized for a bounded Cursor experiment

**Priority:** 1 of 3 CI optimization items

**Owner:** Cursor (implementation), Codex (architecture/documentation), independent reviewer required

**Authorization date:** 2026-09-22

**Implementation authority:** Local workflow/test changes and validation only. Publication of an implementation PR, merge, deployment, Production access, and release activity require separate owner authorization.

---

## Problem

The required CI jobs currently retain this expensive serial path:

`unit-tests -> integration-tests -> pact-consumer -> docker-build-verify`

The original measurements came from PR #197. That run took approximately 34 minutes on the
serial path: 2,026 seconds, or 33m46s, from measured job durations. The historical model estimated
that about 10.1 minutes could be removed from successful-run wall time by running
`pact-consumer -> docker-build-verify` in parallel with `unit-tests -> integration-tests`. The
resulting historical critical-path estimate was 1,422 seconds, or 23m42s.

Those measurements are decision history, not current guarantees. Cursor must refresh the current
workflow graph, job commands, artifacts, caches, and recent timing evidence before changing the
DAG.

GitHub Actions jobs run on isolated runners. `docker-build-verify` downloads `pact-contracts`
from `pact-consumer`, but the original audit found no artifact or state dependency from either job
to `integration-tests`. The existing dependency therefore appeared to provide fail-fast cost
control rather than correctness. That hypothesis must be revalidated against current `main`.

## Timebox and stop rule

This task is approved only as work that can be completed within one working day.

- Spend no more than 90 minutes refreshing the evidence and dependency model before deciding
  whether implementation is safe and still fits the remaining timebox.
- If the current graph reveals a correctness dependency, the evidence is insufficient, or the
  implementation plus verification is likely to exceed one day, stop and recommend deferral.
- Do not broaden the task into integration-test optimization, cache redesign, general path-based
  selection, workflow consolidation, deployment changes, or production work.

An evidence-backed `DEFER` is an acceptable successful result.

## Decision to make

De-serialization historically traded:

- approximately 10 minutes less wall-clock latency on successful runs; and
- approximately 10.1 additional runner-minutes when `unit-tests` or `integration-tests` fails
  after the new parallel branch has started: 604 seconds from `pact-consumer` (32s) plus
  `docker-build-verify` (572s).

Wall-clock latency and runner consumption are different metrics. Present both separately. The
owner, not the implementer, decides whether the trade-off is acceptable.

## Evidence required before implementation

1. Record the exact current `main` SHA and authoritative workflow file.
2. Reconstruct the actual `needs` graph, including every job consumed by `ci-required`.
3. Re-derive transitive inputs from current commands; do not rely on the historical path list.
4. Identify the artifacts and state consumed by `pact-consumer` and `docker-build-verify`.
5. Determine a homogeneous historical epoch by inspecting changes that materially affected the
   measured jobs' topology, commands, dependencies, environment, caching, artifacts, runner
   selection, test workload, or timeouts.
6. Use workflow run attempts, not only run IDs, as observations. Record
   `{run_id, run_attempt, head_sha}` and query attempt-specific jobs.
7. Include completed pull-request attempts where `static-guard` succeeded. Exclude
   workflow-level cancelled runs.
8. Assert that every qualifying attempt contains the expected job names.
9. Compute the joint first-failure event directly: `unit-tests` failed/timed out, or
   `unit-tests` succeeded and `integration-tests` failed/timed out.
10. Validate every Actions conclusion against the known enum and reconcile all buckets to the
    queried population. Unexpected conclusions require inspection.
11. Record sample size, failure rate, uncertainty, and timing distributions. `Insufficient
    evidence` is an acceptable conclusion.
12. Recover runner image/version and resolved action SHAs from retained setup logs where
    possible. Record environmental comparability as unknown where evidence has expired.

The epoch boundary is intentionally undetermined until activation. Candidate commits must be
verified by their actual effect on the measured chain, not accepted from commit titles or file
categories alone.

## Bounded experiment

If the evidence supports proceeding within the one-day timebox, use Cursor's assigned worktree:

1. Make `pact-consumer` a second classifier-gated root with
   `needs: [static-guard, changes]` and the existing
   `needs.changes.outputs.docs_only != 'true'` predicate.
2. Leave `docker-build-verify` dependent only on `pact-consumer`.
3. Keep `unit-tests` as the other classifier-gated root. Preserve the exact docs-only skip shape:
   the jobs downstream of each root must continue to skip by propagation.
4. Update workflow contract tests before changing the workflow so the old graph fails the new
   assertion. Replace the old single-gated-root assertion with one that permits exactly
   `unit-tests` and `pact-consumer`, both consuming the existing classifier output.
5. Make no classifier-policy, branch-protection, deployment, or production changes.
6. Confirm the required jobs retain their existing failure behavior.
7. Measure successful-run latency and failing-run runner consumption.
8. Run one deliberate negative case with an intentionally failing unit test. Confirm that after
   re-parenting, `pact-consumer` and `docker-build-verify` still run. The deliberate failure must
   stay isolated to the experiment branch and must never be merged.

## Safety invariants

- Unknown or missing job results fail closed.
- `ci-required` remains the stable required aggregate; do not weaken its exact-result matrix.
- Docs-only pull requests retain their exact legitimate skip set.
- Do not add a new classifier or change classifier policy; both gated roots consume `changes`.
- Pushes to `main` and manual dispatches retain full-suite behavior.
- No required check is removed, renamed, or made advisory.
- No deployment workflow, schedule, secret, cloud resource, or Production system is touched.
- The experiment does not authorize broader frontend/backend selection.

## Required verification

At minimum:

```text
python scripts/tests/test_classify_changed_paths.py -v
python scripts/tests/test_master_plan_status_propagation.py -v
reuse `deploy-workflow-contract`'s pinned Actionlint setup, then run:
./actionlint -shellcheck= .github/workflows/ci-verification.yml
git diff --check
```

Also execute the smallest local or branch-level workflow harness that proves the revised `needs`
graph, legitimate docs-only shape, full-suite shape, missing-job behavior, unexpected-skip
behavior, and deliberate failing-unit-test behavior.

## Exit criteria

Return one of two outcomes:

1. **READY FOR OWNER REVIEW** — the bounded change is implemented locally, all required
   verification is green, timings and runner-cost evidence are recorded, and the branch is ready
   for an independently reviewed implementation PR once the owner authorizes publication; or
2. **DEFER** — evidence is insufficient, a correctness dependency blocks the change, or the work
   cannot be completed safely within one day.

Do not merge the experiment merely because one green run is faster.
