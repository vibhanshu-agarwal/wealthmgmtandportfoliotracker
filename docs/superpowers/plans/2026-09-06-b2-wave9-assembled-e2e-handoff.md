# B2 Wave 9 assembled E2E — local completion handoff

> ## OWNER APPROVAL REQUIRED — PUSH + PR ONLY
>
> **Blocked action:** push `feat/b2-wave9-assembled-e2e` and open a pull request against `main`.
>
> **Decision requested:** may the coordinator push this documentation-complete branch and create the
> pull request?
>
> - **If yes:** only the push and pull-request creation are authorized.
> - **If no:** all work remains local and reviewable in this worktree.
>
> No deployment, workflow dispatch, production access, production E2E, merge, or production flag
> change is authorized by this decision. The GitHub CI workflow is wired locally but has not run.

## Scope and status

- Baseline: `origin/main@b4c68253b99a796d6301ef79b5aa5a47d5cbd962`.
- Branch/worktree: `feat/b2-wave9-assembled-e2e` in
  `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-codex-wave9`.
- Locally complete: Tasks 9.2, 9.7, 9.8, and 9.9.
- Not claimed: GitHub CI execution, push, pull request, deployment, production E2E, or production
  feature exposure. Production flags remain off. B1 R-C deployment/convergence, Task 3.7, Task 6.3,
  Wave 8, and Wave 10 remain open.

The existing production composition-save transport was already wired. Task 9.2 therefore needed no
production source change; its deliverable is the discriminating real-stack proof.

## Commit map

| Commit | Purpose |
|---|---|
| `c1b9c288` | Real demo-reset success/conflict E2E and helpers |
| `a671ce07` | Resume demo-reset clock before UI reconciliation |
| `2b6cb4fd` | Real Asset Picker composition E2E and collection configuration |
| `ea2e1f49` | Harden picker E2E oracle coverage |
| `f079b481` | Active CI job-level flags, required specs, and structural parity guard |
| `5ac2eb13` | Astra's TDD locator fix for demo-reset conflict assertions |

## Local evidence

The disposable Docker Compose assembled-stack browser run passed **5/5**: setup plus real Asset
Picker success/conflict and real demo-reset success/conflict. It exercised the real gateway and
backend paths; it is not a cloud or production result.

The first assembled run failed at the conflict alert oracle because Next's route announcer also has
`role="alert"`. Astra's `5ac2eb13` changed the assertions to target the conflict notice and added a
locator guard. The corrected assembled run passed 5/5.

| Verification | Result |
|---|---|
| Frontend `npm test` | 68 files / 642 tests |
| Focused CI structural guard | 4/4 |
| Golden-state oracle derivation | 12/12 |
| Focused Wave 9 Vitest before locator fix | 79/79 |
| Locator guard after the fix | 1/1 |
| E2E ESNext/bundler TypeScript checks | clean |
| Changed-file ESLint | clean |
| Real assembled E2E collection | 5 |
| Mocked picker collection | 2 |

## Reproduction record

Run from `frontend/` with the same disposable Compose stack and CI-only environment values used for
the local run. Do not point these commands at production.

```powershell
npm test
npx tsc --noEmit -p tests/e2e/tsconfig.e2e-test.json
npx eslint <changed Wave 9 files>
npx playwright test --list
npx playwright test --config playwright.asset-picker.mocked.config.ts --list
```

Run from the repository root:

```powershell
python scripts/tests/test_ci_e2e_wiring.py -v
python scripts/tests/test_derive_demo_golden_state.py -v
```

The static guard verifies the four exact job-level values in active
`docker-build-verify`, both explicit real E2E paths in its one required Playwright invocation, no
failure forgiveness or disabling condition, and parity with the tracked Azure frontend literals,
V15, and the demo fixture. It does not read `frontend/.env.local`.

## Publication boundary

Before publication, re-resolve the baseline and head, review the docs-only diff, and run the
repository status guard against the exact intended PR body. Stop after creating the pull request;
merge, deployment, workflow dispatch, and production validation require separate owner approval.
