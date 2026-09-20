# B2 Wave 10.2 — Step B 5b offline-CI wiring: Claude kickoff

> **Status (2026-09-20): HISTORICAL / SUPERSEDED.** The owner reprioritized from this proposed
> offline-CI review to the shortest production-proof path. No implementation was authorized or
> performed under this kickoff. Wave 10.2 Step B subsequently completed with Production E2E GO;
> retain this file only as decision history, not as the current Asset Picker task.

> **OWNER APPROVAL CALLOUT — implementation is not authorized.** This kickoff authorizes only
> Claude's read-only technical design review. Do not modify files, commit, push, open a pull request,
> alter a workflow, dispatch a workflow, read live production state, change a flag, or run Step B.
> A later decision must explicitly authorize any bounded CI-wiring implementation and its publication.

## Purpose

Produce an implementation-ready recommendation for the smallest safe CI change that makes the already
accepted Step B 5b verifier's **offline** tests a named, non-skippable CI contract. The result must
close the source-verification wiring gap only; it must not advance Wave 10.2, condition 5a, Step B,
or production exposure.

## Why this is the next task

The 5b verifier is merged and independently ACCEPTed (PR #292; acceptance record
`docs/evidence/b2-wave-10-2/WAVE_10_2_STEP_B_5B_VERIFIER_INDEPENDENT_ACCEPTANCE_2026-09-19.md`).
However, its governing specification explicitly leaves **wiring these tests into CI** for a separate
workflow change. The current required `static-guard` runs several Python contract suites but does not
name `scripts/tests/test_verify_step_b_5b.py`.

The frontend helper tests already sit under the `vitest.config.ts` include path and are expected to be
covered by the existing frontend test command. Confirm this from source; do not assume that existing
coverage alone makes the Python verifier contract a non-skippable CI obligation.

## Baseline and governing sources

- Start from `main@91f40bd0126f15fc87a6d6beb2ffa6bcd01e76d4` (PR #295 documentation merge).
- `docs/superpowers/plans/2026-09-18-wave10-2-step-b-5b-verifier-spec.md` §§2, 8–9: authoritative
  verifier ownership, offline-test scope, and the deliberately open CI-wiring item.
- `scripts/verify_step_b_5b.py` and `scripts/tests/test_verify_step_b_5b.py`: Python verifier and
  offline contract suite. The test header specifies `python -m pytest ...`.
- `frontend/vitest.config.ts` and `frontend/tests/e2e/helpers/__tests__/step-b-5b-*.test.ts`: existing
  helper-test inclusion and the Step B 5b TypeScript tests.
- `.github/workflows/ci-verification.yml`: required `static-guard`, changed-path skip graph, and
  `ci-required` aggregation contract.
- `.kiro/specs/asset-picker-composition/tasks.md` 10.2: gate semantics. In particular, condition 5a
  still needs a future dispatch's exact-SHA, non-skipped `docker-build-verify` evidence; CI wiring by
  itself is not that evidence.

## Required review questions

1. What exact CI command can run `test_verify_step_b_5b.py` deterministically on the hosted runner?
   Identify how `pytest` is supplied or the smallest controlled dependency change required; do not
   rely on an incidental runner preinstallation.
2. Which existing always-run job is the correct home, and why does it remain non-skippable for a
   documentation-only or otherwise path-classified pull request?
3. Are all `step-b-5b-*.test.ts` helper tests collected by the existing frontend CI command? State the
   configuration and command evidence, including whether an additional named frontend command is
   necessary.
4. What is the minimal changed-path set and the exact validation matrix for a subsequent implementation
   PR? Keep it narrow; no refactoring or broad CI reorganisation.
5. Does the proposed change preserve the verifier's offline boundaries: no browser, network, Azure,
   production credentials, workflow dispatch, or feature-flag access?

## Deliverable

Return a concise technical recommendation, not a patch, containing:

1. The proposed CI location and command(s), with the dependency-resolution rationale.
2. The exact files that a later implementation would need to change and why each is necessary.
3. The pre-implementation and PR-CI validation commands, including the non-skippable job evidence.
4. Any ambiguity, unavailable dependency, or unsafe test behaviour as a blocking finding.
5. A single, explicit owner-approval request for the later implementation, push, pull request, and
   merge. Do not treat this kickoff as that approval.

## Hard boundaries and stop conditions

- No repository edits or Git/GitHub publication in this task.
- No Azure, production, feature-flag, repository-variable, secret, or live-service access.
- No disposable-stack rehearsal, test-only origin override, commit-level frontend binding, or real
  browser E2E. Those remain separately governed items in the verifier specification.
- Do not claim condition 5a is satisfied or Wave 10.2 is complete. A future Step B dispatch decides
  its own exact `expected_main_sha` and requires its own non-skipped `docker-build-verify` evidence.
- Stop and report if the minimal deterministic route would require a broader dependency policy,
  workflow-permission change, production contact, or a change outside the stated offline-CI scope.

## Review and handoff

Claude is the implementation-design owner for this task. Codex remains the documentation/status owner.
The recommendation must be independently reviewed before any implementation authority is requested;
the reviewer must be read-only and must not be the author of a future patch.
