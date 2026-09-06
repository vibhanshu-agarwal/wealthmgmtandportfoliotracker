# B2 Wave 9 Assembled-Stack E2E Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task.

**Goal:** Complete B2 Tasks 9.2, 9.7, 9.8, and 9.9 on one reviewable branch by proving the real composition save and demo reset paths against the assembled Docker stack and making both E2E specifications required in the active CI workflow.

**Architecture:** Keep production behavior behind the existing build-time flags. Reuse the existing ordinary E2E identity for Asset Picker composition tests and the isolated demo-auth fixture for manual reset tests. Set up and clean up state through authenticated public APIs, except for the explicitly required independent internal reset cleanup. Run real gateway/backend requests without Playwright route fulfillment. Keep three implementation lanes isolated in sibling worktrees and consolidate their commits only after task-level review.

**Tech Stack:** Next.js 15, React 19, TypeScript, TanStack Query, Vitest, Playwright 1.54, Docker Compose, Spring Boot services, GitHub Actions.

**Spec:** `.kiro/specs/asset-picker-composition/tasks.md` Tasks 9.2 and 9.7-9.9; `.kiro/specs/asset-picker-composition/requirements.md` Requirements 1.1, 4.1-4.4, 7.3a-7.3b; `.kiro/specs/asset-picker-composition/design.md` D1 and D5; `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`.

## Global Constraints

- Baseline is `origin/main@b4c68253b99a796d6301ef79b5aa5a47d5cbd962`.
- Use strict TDD for production or helper changes: add a discriminating failing test, observe the expected RED, implement the smallest change, and observe GREEN.
- Treat an already-correct production path as a valid result. Do not manufacture a source defect to make a task look substantive; add only the evidence required to prove the contract.
- Do not fulfill, mock, or intercept the route under test. Passive request/response observation is allowed.
- Every write must use a freshly observed, identity-checked version. Assert that deliberate setup writes advance version so same-state no-ops cannot satisfy the oracle.
- Cleanup is unconditional and order-independent. A cleanup `409` may be retried with a fresh observation to restore hygiene, but any observed cleanup conflict still fails the test.
- Do not touch, stop, or reconfigure unrelated host services or containers, including `optimus-redis`.
- Do not run multiple assembled Docker stacks against the same ports concurrently. The coordinator serializes real-stack execution after lane-local deterministic checks.
- Do not weaken flags, authorization, conflict handling, retry policy, identity selection, golden-state comparison, or CI requiredness.
- Do not push, open a PR, merge, deploy, dispatch workflows, access production, or expose either UI control. Local commits are allowed.
- Update `.kiro/specs/asset-picker-composition/tasks.md` and `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` together only after the corresponding evidence exists. Preserve all production and Wave 10 gates.
- Each implementation lane must return: commit SHA(s), changed-file list, exact RED/GREEN commands and outputs, real-stack commands attempted, limitations, and a self-review against its task wording.

---

## Task 1: Prove Task 9.2 and implement the real Asset Picker E2E in Task 9.7

**Owner:** Terra lane

**Files:**

- Inspect: `frontend/src/lib/api/assetPickerSave.ts`
- Inspect: `frontend/src/components/asset-picker/AssetPicker.tsx`
- Inspect: `frontend/src/components/asset-picker/EditHoldingsButton.tsx`
- Modify: `frontend/tests/e2e/asset-picker.spec.ts`
- Modify or add only when needed: `frontend/tests/e2e/helpers/api.ts`
- Modify or add only when needed: `frontend/tests/e2e/helpers/portfolio-seed-version.ts`
- Modify or add only when needed: focused helper/component tests beside changed helpers
- Modify or add only when needed: Playwright configuration used by the real assembled-stack run

### Step 1: Audit the existing transport before editing

Trace the actual save call from the UI through `saveComposition` to `PUT /api/portfolio/holdings`. Confirm the request body is the existing `SavePayload`, the bearer token is sent, cache is disabled, `200` returns the persisted response, and `409` remains a typed non-retried conflict. Record whether Task 9.2 needs production source changes.

### Step 2: Replace the mocked Asset Picker proof with two real cases

Write or restructure `frontend/tests/e2e/asset-picker.spec.ts` so it runs under the ordinary E2E session with the picker flag enabled and performs no route fulfillment for composition APIs.

Success case requirements:

1. Identity-check the ordinary E2E portfolio and record version `N`.
2. Make an authenticated direct composition write to a known-different valid holding set and require returned version `N1 > N`.
3. Open the page and assert `Edit Holdings` renders.
4. Open the picker, record the version it loaded, and make a pinned quantity edit relative to the loaded state.
5. Complete review/save and passively observe exactly one real composition `PUT` from the picker.
6. Require HTTP `200`, response version greater than the picker-open version, and a subsequent identity-checked read whose holdings equal the edited draft exactly.

Conflict case requirements:

1. Open the picker and capture its version.
2. Use an independent authenticated composition write to advance the same portfolio to a deliberately different valid state; require its returned version to be greater than the picker-open version.
3. Save the stale picker draft and require one real `409`.
4. Assert the draft remains visible and read-only, editing and resubmission are disabled, and no automatic second `PUT` occurs.

### Step 3: Make cleanup unconditional and discriminating

In `finally` or `afterEach`, freshly re-read and identity-check the fixed E2E user, then call the version-bearing internal seed endpoint to restore golden state. Require `200`. Bound cleanup retries to three attempts, with a fresh observation before each. Restore hygiene after a `409`, then still fail the case because the conflict is evidence of an unexpected writer.

### Step 4: Add helper tests only where they discriminate

If new setup, selection, exact-holdings, request-count, or cleanup helpers are introduced, write focused tests first. Include negative cases for zero/multiple identity matches, same-state setup, stale cleanup version, or a second automatic PUT as applicable. Avoid tests that merely mirror implementation.

### Step 5: Verify locally

Run the focused Vitest/helper tests, TypeScript E2E configuration, and ESLint for changed files. Use Playwright `--list` to prove the spec is collected by the intended assembled-stack configuration and excluded from mocked-only configurations where applicable. Defer the shared real-stack run to the coordinator if it would collide with another lane.

### Step 6: Prove the oracle discriminates

Temporarily introduce the smallest local mutation that would replay a stale version, skip the picker write, permit a second PUT, or compare only HTTP status. Confirm the intended assertion fails at the claimed boundary, restore the source byte-for-byte, and rerun GREEN. Do not commit the mutation.

### Step 7: Commit the lane

Commit Task 9.2/9.7 changes in one or more reviewable commits without ledger completion claims. Return the evidence packet to the coordinator.

---

## Task 2: Implement demo reset assembled-stack E2E in Task 9.8

**Owner:** Astra lane

**Files:**

- Inspect: `frontend/tests/e2e/helpers/demo-auth.ts`
- Inspect: `frontend/src/components/asset-picker/ManualResetControl.tsx`
- Inspect: `frontend/src/lib/api/demoReset.ts`
- Add: `frontend/tests/e2e/demo-reset.spec.ts`
- Modify or add only when needed: focused helpers and tests under `frontend/tests/e2e/helpers/`
- Modify or add only when needed: Playwright configuration used by the real assembled-stack run

### Step 1: Build exact demo identity and golden-state oracles

Reuse Task 9.6's isolated demo-auth fixture. Every `GET /api/portfolio` selection must match exactly one entry whose `userId` equals `DEMO_USER_ID`; zero or multiple matches fail immediately. Derive or reuse the independently governed Task 4.4a golden oracle rather than copying a convenient subset from the implementation response.

### Step 2: Implement deterministic success setup and proof

Before loading the UI, observe demo version `N` and issue a demo-authenticated public composition `PUT` with a valid holding set known to differ from golden. Require returned version `N1 > N`.

Load the UI in `demoPage`, assert `Reset Demo Portfolio` renders, record its observed portfolio version, click once, and passively observe exactly one real public demo-reset request traversing the gateway. Require `200`, exact golden response holdings, and UI/cache state reflecting the returned fresh golden state.

### Step 3: Implement deterministic conflict setup and proof

Repeat the known-non-golden setup. Load the UI and freeze the version it observed. Perform another deliberately different demo-authenticated public composition write and require its returned version to exceed the UI's frozen version. Click reset once; require exactly one `409`, no automatic retry, and the existing conflict presentation with the control frozen until explicit re-observation.

### Step 4: Implement independent unconditional cleanup

Cleanup must not use `/api/internal/portfolio/seed` and must not depend on the public reset route under test. Freshly select the demo portfolio by `DEMO_USER_ID`, then call `POST /api/internal/portfolio/demo-reset` through the gateway with `INTERNAL_API_KEY` and that version. Require `200` and exact golden holdings. Bound conflict cleanup retries to three fresh-observation attempts, restore state if possible, then fail if any cleanup `409` occurred.

### Step 5: Add focused tests before helper changes

If common helpers are necessary, first cover identity ambiguity, version advancement, exact holding comparison, internal cleanup headers/body, retry boundaries, and conflict preservation. Do not relax the fixture's isolated-context or no-credential-fallback contracts.

### Step 6: Verify and mutation-check

Run focused Vitest, E2E TypeScript, ESLint, and Playwright collection checks. Mutate one critical oracle locally, such as accepting a no-op setup, selecting the first portfolio, accepting `200 | 409`, omitting the internal key, or forgiving a cleanup conflict. Confirm the relevant test fails, restore byte-for-byte, and rerun GREEN.

### Step 7: Commit the lane

Commit Task 9.8 changes without ledger completion claims. Return the evidence packet to the coordinator.

---

## Task 3: Wire Tasks 9.7 and 9.8 into required CI in Task 9.9

**Owner:** Luna lane

**Files:**

- Modify: `.github/workflows/ci-verification.yml`
- Add or modify: the repository's structural workflow/parity guard tests, using the existing guard pattern
- Inspect: `.github/workflows/deploy-azure.yml`
- Inspect: `api-gateway/src/main/resources/db/migration/V15__Reconcile_Auth_Seed_Users.sql`
- Inspect: `frontend/tests/e2e/helpers/demo-auth.ts`

### Step 1: Write a failing structural test

Add a static test that reads the active `docker-build-verify` job and fails until all four values exist at job-level `env`:

- `NEXT_PUBLIC_ENABLE_ASSET_PICKER: "true"`
- `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL: "true"`
- `DEMO_TEST_EMAIL: "demo@wealthtracker.dev"`
- `DEMO_TEST_PASSWORD: "demo-wealthtracker-2026"`

The test must distinguish job-level values from step-level values and from the disabled `frontend-e2e-integration.yml` workflow.

### Step 2: Assert the explicit required Playwright invocation

Extend the test so the exact `Run Playwright E2E tests` command in `docker-build-verify` includes both `tests/e2e/asset-picker.spec.ts` and `tests/e2e/demo-reset.spec.ts`. Require ordinary failure propagation: no `continue-on-error`, shell forgiveness, or separate optional command may make either spec advisory.

### Step 3: Assert credential and identity parity

Add a structural assertion that the CI demo email/password literals equal the tracked Azure frontend demo literals, and that demo identity agrees with V15 and the fixture's `DEMO_USER_ID`. The guard must read tracked source; it must not consult or permit a fallback to `frontend/.env.local`.

### Step 4: Make the smallest workflow change

Place the four values in the existing `docker-build-verify` job-level `env` block. Add the two filenames to the existing multiline Playwright invocation. Do not touch production deployment flags, repository variables, workflow dispatch, schedules, or deploy workflows.

### Step 5: Verify and mutation-check

Run the structural guard directly and through its owning test suite. Temporarily move one flag to step scope or delete one spec filename and confirm the guard fails at the intended assertion. Restore byte-for-byte and rerun GREEN. Run a YAML parse or repository workflow validation test if one already exists.

### Step 6: Commit the lane

Commit Task 9.9 changes without claiming CI execution, since no workflow is dispatched in this local run. Return the evidence packet to the coordinator.

---

## Task 4: Consolidate, run the assembled stack serially, and review every task

**Owner:** Coordinator

### Step 1: Review each lane before integration

For each lane, inspect its diff and evidence against the exact task text. Reject status-only assertions, ambiguous identity selection, non-advancing setup, permissive outcomes, route fulfillment, retry on real conflict, cleanup forgiveness, or CI wiring in the wrong workflow/scope.

### Step 2: Integrate reviewed commits

Cherry-pick reviewed commits into `feat/b2-wave9-assembled-e2e`. Resolve overlaps by preserving the stricter oracle. Rerun focused tests after each integration step.

### Step 3: Run one clean assembled stack

Bring up the repository's disposable Docker Compose stack with both frontend flags enabled and the required CI credentials. Seed the ordinary E2E and demo prerequisites using existing governed setup. Run the Task 9.7 and Task 9.8 Playwright specs in one worker, serially if required for deterministic shared state. Capture exact results and request counts. Tear down only the disposable Wave 9 stack.

### Step 4: Run consolidated local verification

Run the frontend full Vitest suite once with a clean environment and no concurrent frontend tests, then TypeScript, ESLint, and build checks appropriate to the final diff. Run the structural CI guard. If broader backend checks are needed because implementation changed beyond frontend/test/workflow files, run the affected service suites.

### Step 5: Commission a fresh whole-branch review

Use a fresh Astra reviewer with no implementation role. Require spec compliance, code quality, isolation, evidence-oracle discrimination, cleanup safety, CI requiredness, and scope review. Send every actionable finding back to the owning worker for a bounded fix round; repeat review until accepted or a real blocker is established.

### Step 6: Update living status documents

Only after the combined assembled-stack proof passes, update `.kiro/specs/asset-picker-composition/tasks.md` and `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` together. Mark only Tasks 9.2, 9.7, 9.8, and 9.9 complete for source/local/CI-wiring scope. State clearly that the workflow has been wired but has not run until PR CI executes, and that deployment, flags-on exposure, production API preflight, browser Production E2E, Wave 8, and B1 R-C gates remain open. Correct the stale Task 9.5 program-state sentence while preserving its source/local-only qualification.

### Step 7: Prepare a reviewable handoff and stop at publication

Write a handoff with baseline, final head, commit map, changed files, focused and full verification, mutation checks, real-stack evidence, cleanup results, review findings, and remaining gates. Run the repository status guard against the exact intended PR body. Stop for owner approval before push and PR creation.
