# Backlog: Add a fail-closed docs-only CI fast path

**Status:** Completed

**Priority:** Delivered item 2 of the original 3-item CI optimization sequence

**Owner:** Historical implementation complete

**Delivered:** PR #198 and PR #199

---

## Outcome

The docs-only fast path shipped in two reviewed stages:

1. PR #198 introduced the changed-path classifier and required `ci-required` aggregate in
   observe-only mode.
2. PR #199 enabled the docs-only skip and fail-closed declared-versus-observed enforcement.

The accepted live evidence reduced a documentation-only pull request from approximately 34 minutes
to 67 seconds. The historical inventory found that roughly 45% of merged pull requests qualified
for the docs-only path.

The implementation kept the existing required contexts and added `ci-required` as the stable
aggregate. A skipped required GitHub Actions job can appear successful, so the aggregate does not
ask only whether jobs passed. It verifies that the observed ran/skipped result for every dependency
exactly matches the classifier's declaration. Missing jobs, unexpected skips or runs, failures,
cancellations, invalid classifier output, and unknown conclusions fail closed.

## Original problem

PR #197 changed four Markdown files but ran the full required pipeline four times. Each run took
approximately 34 minutes, producing about 136 minutes of CI for documentation-only changes.

The original broader diff-aware design received `REQUEST CHANGES`. The smaller docs-only slice
removed the clear waste without attempting frontend-only or backend-only selection.

## Shipped boundary

The fast path applies only to confidently classified documentation-only pull requests. Unknown,
mixed, empty, or unclassifiable changes run the full suite.

It does not:

- restore automatic deployment;
- add workflow-level `paths:` filters to required workflows;
- alter production authorization; or
- implement frontend-only or backend-only selection.

## Explicit `.kiro/specs/**/*.md` decision

Markdown files under `.kiro/specs/**` are eligible for the docs-only slice only while the
separate `master-plan-status-propagation` workflow remains unconditional and continues to enforce
the governed task/master-plan relationships for those files.

If that workflow stops being unconditional, ceases to govern these paths, or is removed from
required protection, this classification becomes invalid and must fail closed to the full suite
until re-reviewed.

## Shipped design

1. A standalone, tested changed-path classifier reuses the repository's three-dot,
   `--no-renames` changed-file behavior.
2. Unknown paths, empty diffs, Git failures, missing base/head data, and classifier errors fail
   closed.
3. An unconditional `changes` job publishes the classification and rationale.
4. Low-cost safety jobs remain unconditional.
5. `unit-tests` contains the graph's single docs-only skip condition.
6. Expensive downstream jobs skip by dependency propagation.
7. `ci-required` is required and uses `if: always()` to inspect every declared dependency.
8. The aggregate requires exact agreement between the declared and observed job results.
9. Pushes to `main` and manual dispatches retain the full suite.

## Regression contract

The classifier and aggregate tests cover documentation-only, `.kiro/specs/**/*.md`-only, mixed,
unknown, deletion-only, rename, workflow/action, Gradle, shared-configuration, missing-SHA, empty
diff, Git failure, push-to-main, and manual-dispatch behavior.

Future CI work must preserve:

- the exact legitimate docs-only result shape;
- fail-closed behavior for missing or unexpected results;
- `ci-required` as the stable aggregate; and
- the rule that no deployment or production authority is inferred from a green CI run.

## Remaining work

This completed item does not reduce the approximately 18-minute integration-test runtime when that
job legitimately runs. It also does not complete:

- the CI critical-path de-serialization experiment; or
- broader frontend/backend diff-aware selection.

Those remain separate backlog decisions.
