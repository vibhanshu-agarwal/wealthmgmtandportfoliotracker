# B2 Task 9.5 — Real asset-price freshness integration: Cursor → Codex review handoff

> ## ⛔ OWNER APPROVAL REQUIRED — READ FIRST
>
> **Blocked action:** pushing branch `feat/b2-task-9-5-asset-price-freshness-integration` to
> `origin` and opening a pull request against `main`.
>
> **Decision requested:** may Cursor push this branch and open a PR?
>
> - **If yes:** the branch is pushed and a PR is opened. That is *all* it permits.
>   Merge, deployment, workflow dispatch, production feature exposure, and any
>   cloud/production secret or probe access remain separately unapproved.
> - **If no:** the work stays local on the assigned worktree. Nothing is lost;
>   every result below was produced locally and is reproducible from the commands
>   given here.
>
> Nothing has been pushed. No PR exists. No deployment or production operation was
> performed, and no production or cloud secret was read. The only credentials used
> were the Golden-State E2E login defaults (`E2E_TEST_USER_EMAIL` /
> `E2E_TEST_USER_PASSWORD` env names; values not recorded here) and a throwaway
> `INTERNAL_API_KEY` invented for this run.
>
> Approval for PR #230 or any earlier B2 PR does **not** carry to this task.

## Completion status, stated separately

| Dimension | State |
|---|---|
| Source integration (Portfolio page → real summary `assetPriceFreshness`) | ✅ Already wired on `main`; path confirmed, no production defect reproduced |
| Local assembled-stack proof (real gateway + seeded summary → UI) | ✅ Complete |
| Deterministic edge-state coverage (Vitest + portfolio-service) | ✅ Extended / re-confirmed |
| Real post-save E2E | ❌ Not done — Task 9.2 still gated on B1 Wave 7; mocked Task 1.18 preserved |
| CI wiring | ❌ Not done — Task 9.9 owns Wave 9 CI |
| Deployment | ❌ Not done, not requested |
| Production E2E / live proof | ❌ Open |

## Baseline and scope

- Branch `feat/b2-task-9-5-asset-price-freshness-integration`, cut from
  `origin/main` at `1664bb8eb837af6300be39055e1dc5e7ff6fd662` (merge of PR #230).
- Worktree: `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-cursor`
  (Cursor's assigned worktree per AGENTS.md). Working tree was clean at cut.
- Kickoff: `docs/superpowers/plans/2026-09-05-b2-task-9-5-cursor-kickoff.md`
- Resolve the range dynamically rather than from a hardcoded SHA:

```powershell
git log --oneline origin/main..HEAD
git diff --stat origin/main...HEAD
```

## Existing-working behavior vs defects

**No production source defect was reproduced.** On unchanged `main`, the path
`fetchPortfolioSummary → usePortfolioSummary → PortfolioPageContent → FreshnessStatus`
already passes the real five-field object through. Baseline focused Vitest: 37/37 green
before any Task 9.5 edits. New page/hook assertions that pass against that path are
**coverage**, not RED→GREEN defect fixes. No production TypeScript/TSX/Java source file
was changed.

## What changed

### Deterministic coverage (Task 1)

- `PortfolioPageContent.test.tsx` — mixed nonzero counts + backend-selected `MISSING`,
  omitted timestamp, contradictory holdings vs freshness, loading/error/missing-object
  must not show “All prices fresh”.
- `FreshnessDetailsPopover.test.tsx` — absent-timestamp copy in the popover.
- `usePortfolio.test.ts` — five-field preservation; HTTP 503, rejected-fetch, and
  invalid-JSON leave `data` undefined (no invented freshness).
- `postSaveReconciliation.integration.test.tsx` — post-save refetch still occurs; Harness
  renders `usePortfolioSummary`'s returned `assetPriceFreshness.state` and asserts the
  consumer moves from `FRESH` to the re-fetched `STALE` (save does not imply `FRESH`).
- `tests/e2e/helpers/browser-auth.ts` / `api.ts` — call-time gateway URL; `ensurePortfolioWithHoldings`
  captures base once at entry. Import-first regressions in `capture-suppression.test.ts`.

### Real-stack proof (Task 2)

- `frontend/playwright.asset-freshness.real.config.ts` — dedicated config; one worker;
  zero retries; `reuseExistingServer: false`; no Asset Picker flag.
- `frontend/tests/e2e/asset-price-freshness.integration.spec.ts` — captures real
  authenticated `GET /api/portfolio/summary`, validates `assetPriceFreshness` at runtime,
  derives expected copy via production `describeFreshness` / `buildFreshnessRows` /
  `formatAbsoluteTimestamp`, asserts compact strip + Details. No route mock.
- `frontend/playwright.config.ts` — chromium `testIgnore` for the new local-only spec.

`--list` confirmed the spec is collected only by
`playwright.asset-freshness.real.config.ts` (1 test) and by none of default, mocked,
catalog, prices, or presence configs.

### Status docs (Task 3)

- `.kiro/specs/asset-picker-composition/tasks.md` — Task 9.5 checked with local
  assembled-stack wording.
- `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` — Wave 9 / Track C updated; Task 9.2,
  CI, deployment, Production E2E remain open.

## Local assembled-stack evidence

### Setup (this machine)

Unrelated `optimus-redis` already bound host `6379`. Untracked local override
`docker-compose.redis-host-local.yml` remapped host Redis to `6380` only
(`ports: !override`). That file must **not** be committed.

```powershell
# repository root
$env:INTERNAL_API_KEY = "<any non-blank throwaway local value>"
docker compose -f docker-compose.yml -f docker-compose.redis-host-local.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.redis-host-local.yml ps
# all services healthy; redis host 6380; gateway :8080

# frontend/
$env:INTERNAL_API_KEY = "<same value>"
$env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
$env:TS_NODE_COMPILER_OPTIONS = '{"module":"commonjs"}'
npx ts-node tests/e2e/global-setup.ts
# Golden State + market data seeded; deep health passed

# Probe (values): summary assetPriceFreshness.state=FRESH, counts 0/0/0, timestamp present

npx playwright test --config playwright.asset-freshness.real.config.ts
# 1 passed (2.0m wall including build; test body ~2.6s)

# teardown — this compose project only
docker compose -f docker-compose.yml -f docker-compose.redis-host-local.yml down
```

Attribution in the passing run: browser `GET` to `/api/portfolio/summary` with Bearer
auth (token not logged), parsed `assetPriceFreshness` drove compact status and Details.
Fixture source: Golden-State seed via `global-setup.ts` + ordinary
`installGatewaySessionInitScript` / `ensurePortfolioWithHoldings`.

### Deliberately not proved in the browser

`STALE` / `UNKNOWN` / `MISSING` / mixed-count precedence stay in Vitest and
portfolio-service contract tests. HTTP 503, rejected-fetch, and invalid-JSON summary
failures are covered through `usePortfolioSummary` (no invented freshness). No shared
DB mutation or freshness-policy shortening was used to force browser edge states.

## Verification record

| Command | Result |
|---|---|
| Focused Vitest (baseline, unchanged main) | 37 passed |
| Focused Vitest (after coverage edits) | 46 passed |
| `npx tsc --noEmit` (frontend) | exit 0 |
| `npx tsc --noEmit -p tests/e2e/tsconfig.e2e-test.json` | exit 2 — sole diagnostic `TS1343` at `tests/e2e/global-setup-entrypoint.test.ts:23`; identical with the new spec temporarily removed |
| ESLint on every changed TS/TSX/config file | exit 0 |
| `:portfolio-service:test` freshness/summary/serialization slices | BUILD SUCCESSFUL |
| Playwright real freshness config | 1 passed |
| `npm test` first attempt (historical) | 93 failed — **invalid**: shell still had `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080` from the stack run |
| Focused Vitest (post-save + hook + capture-suppression after review fixes) | 26 passed |
| `npm test` after review fixes (clean env, no concurrent work) | **601 passed / 64 files** |
| Import-first call-time URL regressions (`capture-suppression`) | 12/12 including two new cases that fail against a module-level constant |

### Helper stability (review rounds)

- Call-time `NEXT_PUBLIC_API_BASE_URL` in `browser-auth` / `api`, with import-first
  regressions that would fail against the old module-level constant.
- `ensurePortfolioWithHoldings` captures the base URL once at entry for login + portfolio GET.

## Status-guard result

Proposed PR body (exact UTF-8 file used):

```text
Master-plan impact: updated — B2
```

(plus Summary / Test plan sections; declaration is the sole plain line-start master-plan impact line.)

```powershell
py -3 scripts/check_master_plan_status_propagation.py `
  --base 1664bb8eb837af6300be39055e1dc5e7ff6fd662 `
  --head a2e5572bcc3bf7d8ac3713fdce6451eea9554d9a `
  --pr-body-file $bodyFile
```

**Observed:** exit 0 — `Master-plan status propagation guard passed (14 changed paths).`

Re-run against the live PR body and actual base/head SHAs after authorized publication.

## Review notes for Codex

- Cursor remains implementation owner in this worktree; review is read-only unless Cursor
  authorizes an edit.
- Production source was already correct; the durable deliverable is attributable
  local assembled-stack evidence plus tightened deterministic coverage and ledger updates.
- Task 9.2 / real post-save E2E, Task 9.9 CI, deployment, and Production E2E are explicitly
  out of scope and still open.
