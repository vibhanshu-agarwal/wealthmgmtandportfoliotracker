# Promote `deploy-workflow-contract` into the required CI gate

**Status:** Open — outside the Semantic Versioning slice.
**Priority:** Medium
**Implementation owner:** unassigned; not started.
**Origin:** Product Semantic Versioning foundation, plan Task 4
(`docs/superpowers/plans/2026-09-20-product-semantic-versioning.md`), 2026-09-20.

> This record is **not** authority to perform the promotion. Promoting the job changes what can
> block a merge, so it needs its own independently reviewed design and an explicit owner decision.

## Why this is recorded now

The Semantic Versioning slice needed a GitHub Actions schema check on one new workflow,
`.github/workflows/release-tag-validation.yml`, and needed it to be able to block a merge. The
existing `deploy-workflow-contract` job already runs actionlint, but it is advisory, so the slice
added a separate, narrowly scoped actionlint step inside the required `static-guard` job instead
of promoting the whole advisory job. That was deliberate: promoting it is a broader change with
its own risks, recorded here rather than folded into an unrelated slice.

## Current state

**Audit (2026-09-26 UTC):** still OPEN. The job remains absent from `ci-required.needs`,
the expected-result map and classifier `ALL_JOBS`; its actionlint fetch remains unbounded.
The implemented advisory job is not the requested required-gate promotion. No live branch-protection
read or required-check change occurred; all acceptance criteria below still apply.

- `deploy-workflow-contract` in `.github/workflows/ci-verification.yml` has no `if:`, `needs:` or
  `continue-on-error:`, but it is **not** one of the nine jobs `ci-required` depends on, so its
  result cannot block a merge. The nine-job set is pinned by `ci-required.needs`, by its
  expected-results map, and by `ALL_JOBS` in `scripts/tests/test_classify_changed_paths.py`.
- It bundles four unrelated contracts: the Wave P deploy-azure service allowlist, the deploy
  pipeline hardening contract, the Terraform-azure scripts contract, and the Terraform-azure
  workflow hardening contract — followed by an actionlint run over eight workflow files.
- Its actionlint download is `curl -sSLO …` with no `--fail`, no retry, and no connect or total
  timeout. The narrower copy added to `static-guard` by the SemVer slice uses `--fail`,
  `--retry 5 --retry-all-errors`, `--connect-timeout 15` and `--max-time 120`. Both pin the same
  version and SHA-256, and `scripts/tests/test_product_version_ci_wiring.py` asserts the two pins
  stay equal.

## Why promotion is not a one-line change

- **It widens the merge gate.** Four contract suites and an eight-file actionlint run would all
  become merge-blocking at once. Each needs to be confirmed deterministic and fast before that.
- **Its network fetch would become a merge dependency.** With no retry or timeout, one slow or
  failed response from the GitHub release-asset host would fail a required check.
- **Some of the gate's own verification skips locally.** The executable `ci-required` predicate
  tests in `test_classify_changed_paths.py` (`AggregateEnforcementTests`, `EvidenceStepTests`)
  skip when bash or jq is unavailable. Measured on 2026-09-20 on a Windows host without jq:
  `OK (skipped=15)`. They run in CI on ubuntu-latest, where both tools are present. Changing the
  required-job set without those tests running locally makes it easy to believe a change was
  verified when it was not.

## Acceptance criteria when picked up

1. An independently reviewed design that states which of the bundled contracts belong in the
   required gate, and whether they should move as one job or be split.
2. `ci-required.needs`, its expected-results map, and `ALL_JOBS` in
   `test_classify_changed_paths.py` updated together, with the classifier contract tests passing
   **unskipped** — i.e. with bash and jq available.
3. The advisory-job comments in `ci-verification.yml` reconciled with the new status.
4. The actionlint download hardened to the same bounded-retry standard as the `static-guard`
   copy, and the two pins still asserted equal.
5. CI duration and reliability measured across several runs before and after, reported with
   run variance, with no improvement claimed from a single sample.
6. Only then, and only if the owner approves, the job deliberately added to `ci-required`.
