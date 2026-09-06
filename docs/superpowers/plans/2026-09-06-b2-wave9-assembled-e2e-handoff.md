# B2 Wave 9 assembled E2E — local completion handoff

> ## HISTORICAL APPROVAL BOUNDARY — SUPERSEDED
>
> **Historical approval boundary:** the prior push request for PR #232 is superseded by its merge
> and CI completion. No deployment, workflow dispatch, production access, production E2E, merge,
> or production flag change is authorized by this handoff.
>
> **Recorded outcome:** PR #232 merged at `main@318f28592da6ab2e3bd66bc738aa68d374b180fa`.
> Final CI run `34018608256` passed `docker-build-verify` and `ci-required`; body-edit guard run
> `34020180243` also passed.
> Reviewed PR head: `dba83c3a1cc28212c6aa115db803cbd15858e1e4`.
>
>
> The CI result is assembled-stack evidence only, not deployment or Production E2E evidence.

## Scope and status

- Historical Wave 9 baseline: `origin/main@b4c68253b99a796d6301ef79b5aa5a47d5cbd962`.
- Reconciliation/merged-main baseline: `main@318f28592da6ab2e3bd66bc738aa68d374b180fa`.
- Branch/worktree: `feat/b2-wave9-assembled-e2e` in
  `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-codex-wave9`.
- Locally complete: Tasks 9.2, 9.7, 9.8, and 9.9.
- GitHub CI: [PR #232](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/232) final run [34018608256](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/34018608256) passed `docker-build-verify` and `ci-required`; the
  body-edit guard run `34020180243` also passed. Deployment, production E2E, and production feature
  exposure are not claimed. Production flags remain off. B1 R-C deployment/convergence, Task 3.7,
  Task 6.3, Wave 8, and Wave 10 remain open.

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
| `ab29adeb` | Govern the fourth Asset Picker seed-cleanup caller in the exact-four inventory |
| `aa9789d4` | Pin the governed picker cleanup control flow after CI review |
| `7c2ffaec` | Replace vulnerable brace heuristics with exact LF-normalized module-prefix pinning through `afterEach` |

## Local evidence

The disposable Docker Compose assembled-stack browser run passed **5/5**: setup plus real Asset
Picker success/conflict and real demo-reset success/conflict. It exercised the real gateway and
backend paths; it is not a cloud or production result.

The first assembled run failed at the conflict alert oracle because Next's route announcer also has
`role="alert"`. Astra's `5ac2eb13` changed the assertions to target the conflict notice and added a
locator guard. The final corrected assembled rerun on the branch tip passed 5/5.

| Verification | Result |
|---|---|
| Frontend `npm test` | 69 files / 648 tests |
| Focused CI structural guard | 5/5 |
| Golden-state oracle derivation | 12/12 |
| Focused Wave 9 Vitest before locator fix | 79/79 |
| Locator guard after the fix | 1/1 |
| E2E ESNext/bundler TypeScript checks | clean |
| Changed-file ESLint | clean |
| Real assembled E2E collection | 5 |
| Mocked picker collection | 2 |
| Picker retry-window guard | Full 31s virtual observation passed |
| Focused independent picker/locator guards | 14/14; Terra final focused slice 17/17 |
| Status-propagation guard | passed locally: 21 changed paths with `Master-plan impact: updated — B2` against the baseline and verified then-current head `7c2ffaec61642f87f21890bff7a90fe033a461f3` |

## Reproduction record

Run from `frontend/` with the same disposable Compose stack and CI-only environment values used for
the local run. Do not point these commands at production.

```powershell
npm test
npx tsc --noEmit --module esnext --moduleResolution bundler --incremental false
npx eslint <changed Wave 9 files>
npx playwright test --config playwright.config.ts tests/e2e/asset-picker.spec.ts tests/e2e/demo-reset.spec.ts --project=chromium --reporter=list
npx playwright test --config playwright.asset-picker.mocked.config.ts --project=chromium --reporter=list --list
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

## PR #232 CI discovery and remediation

PR #232's initial CI static-guard failure identified a legitimate fourth seed caller:
`frontend/tests/e2e/asset-picker.spec.ts`. It was absent from the fixed historical inventory.
The failure skipped downstream jobs, including `docker-build-verify`, so GitHub did not run an
assembled E2E. The three historical B1/G5 callers remain unchanged.

PR #232 then exposed a 1/33 status-propagation check failure: the inventory preamble displaced
the existing merge-stable runtime-baseline wording below the header's first 20 lines. The minimal
header correction truthfully states that the inventory changes no production runtime gate; it does
not claim an unmerged or documentation-only update.

Commits `ab29adeb`, `aa9789d4`, and `7c2ffaec` added the exact-four governed inventory and then
replaced the vulnerable brace heuristics with an exact LF-normalized module-prefix pin through
`afterEach`. The focused guard suite passed 14/14 and its direct guard passed; Astra's final review
was **ACCEPT**. The prefix is intentionally maintained as an exact source prefix: a future intended
fixture setup change requires updating the tracked prefix, while later test bodies stay out of this
guard's scope.

The status-propagation guard passed locally with 21 changed paths and the exact declaration
`Master-plan impact: updated — B2`, against
`origin/main@b4c68253b99a796d6301ef79b5aa5a47d5cbd962` and the verified then-current head
`7c2ffaec61642f87f21890bff7a90fe033a461f3`. The changed-path set remains 21 after this handoff-only
commit; the root coordinator will rerun the guard against the new head.

## Publication boundary

Re-resolve the baseline and head, review the docs-only diff, and rerun the repository status guard
against the exact intended PR body. Merge, deployment, workflow dispatch, and production validation
require separate owner approval.
