# B2 Task 9.4 — Real presence integration: Claude → Codex review handoff

> ## ⛔ OWNER APPROVAL REQUIRED — READ FIRST
>
> **Blocked action:** pushing branch `claude/b2-task-9-4-real-presence-integration` to
> `origin` and opening a pull request against `main`.
>
> **Decision requested:** may Claude push this branch and open a PR?
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
> were the intentionally public local demo values and a throwaway
> `INTERNAL_API_KEY` invented for this run.
>
> Approval for earlier B2 PRs (#226, #228, #229) does **not** carry to this task.

## Completion status, stated separately

| Dimension | State |
|---|---|
| Source integration (frontend → real gateway route) | ✅ Complete |
| Local assembled-stack proof (real gateway + real Redis, browser) | ✅ Complete |
| Backend real-Redis coverage | ✅ Complete (pre-existing suite, re-run: 13 tests) |
| Defect found and fixed | ✅ One, reproduced RED → GREEN |
| CI wiring | ❌ Not done — Task 9.9 owns Wave 9 CI |
| Deployment | ❌ Not done, not requested |
| Production E2E / live proof | ❌ Open — Wave 3 Task 3.7 remains open |

## Baseline and scope

- Branch `claude/b2-task-9-4-real-presence-integration`, cut from
  `main` at `d3e10f18a0e1f67ec18cc9e86a3499f4a9f93b52` (verified against
  `origin/main` at cut time; contains merged #228 and #229).
- Worktree: `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-claude`
  (Claude's assigned worktree per AGENTS.md). Two unrelated untracked handoff
  documents that were already present were left untouched and unstaged.
- Resolve the range dynamically rather than from a hardcoded SHA:

```powershell
git log --oneline origin/main..HEAD
git diff --stat origin/main...HEAD
```

## The one reproduced defect

**Symptom.** Every *first* opening of the picker issued **two**
`GET /api/presence/demo` requests instead of one, against requirements.md 6.3
("query presence **once**, on open").

**Why the existing tests missed it.** `usePresence.test.tsx` drives `openKey` by
hand. That proves the hook refetches when the key changes; it cannot observe how
many times `AssetPicker` changes that key for one opening. The contract is a
property of the composed component, so it needed a component-level test.

**Root cause.** `AssetPicker` advanced its presence key inside the draft-seeding
branch. Seeding happens at least one render *after* the modal opens — later still
on a cold catalog — while `usePresence` was already enabled from the first render.
React Query therefore fetched under the pre-seed key and again under the seeded
key. My first hypothesis (a cold-catalog-only race) was **wrong**: instrumenting
the hook showed both keys fetching on a warm catalog too, so the fix addresses the
general lifecycle rather than the catalog timing.

**Fix** (`frontend/src/components/asset-picker/AssetPicker.tsx`, ~15 lines): the
key advances on the `open` transition itself, and the query stays disabled for the
render still carrying the superseded key. Presence is now independent of the
catalog — an advisory signal on open should not wait on, or be cancelled by, a slow
or failed catalog load.

**Evidence.** In `AssetPicker.presence.integration.test.tsx`, the two
one-GET-per-opening cases failed on unchanged `main` with `expected 2 to be 1`
(cold *and* warm) and pass after the fix.

### What is added coverage, not a defect regression

Eight of the eleven new component tests passed on unchanged `main`. They are
coverage, not proof of a bug: closed-modal silence, the reopen delta, no re-query
on draft edits / rerender / window focus, false→true→false across separate
openings, a persistent banner with no polling, three injected fail-open modes, and
unchecking a seeded holding while the banner shows.

## Local real-stack proof

Real gateway, real Redis, real demo logins. **Nothing fulfills or mocks
`/api/presence/demo`** — every boolean asserted is read out of an actual response
body, and each banner assertion follows the response that caused it.

| Leg | Real response | UI |
|---|---|---|
| Lone demo session opens the picker | `anotherSessionActive: false` | no banner |
| Second tab, **same issued token** | `false` | no banner (two tabs = one session, req. 6.1) |
| Independently logged-in session B active | `true` | exactly **one** advisory banner |
| Later opening after B ages out of the ZSET | `false` | no banner |

Per opening: **exactly one** browser `GET`, asserted as one new request followed by
a 3s quiet window, then re-asserted. `OPTIONS` preflight is excluded by matching on
the GET method. Session B is driven through an `APIRequestContext`, which never
touches the page, so deliberate setup probes cannot inflate a per-opening count.

Advisory behaviour was verified with the banner showing: a held row unchecks, the
Review step is reachable, presence is not re-queried, and
`PUT /api/portfolio/holdings` fired **zero** times. No composition was submitted.

Sessions A and B are two independent `POST /api/auth/login` calls, never one storage
state copied twice. Distinctness is asserted as a boolean and proved functionally by
B's own read seeing A; no token or raw `jti` is printed.

### Reproducing it

```powershell
# 1. repository root — stack WITH the presence overlay
$env:INTERNAL_API_KEY = "<any non-blank throwaway local value>"
docker compose -f docker-compose.yml -f docker-compose.presence-e2e.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.presence-e2e.yml ps

# 2. frontend/
$env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
$env:DEMO_TEST_EMAIL = "demo@wealthtracker.dev"
$env:DEMO_TEST_PASSWORD = "demo-wealthtracker-2026"
$env:APP_DEMO_PRESENCE_TTL = "20s"   # must match the overlay
npx playwright test --config playwright.presence.real.config.ts

# teardown — this stack only
docker compose -f docker-compose.yml -f docker-compose.presence-e2e.yml down
```

### What the overlay changes, and why (`docker-compose.presence-e2e.yml`)

1. **`APP_DEMO_PRESENCE_TTL` → `20s`** (production default 150s). The spec observes
   a session *ageing out*; at 150s that leg is unusable. The shortened TTL is the
   only way expiry is observed — the spec never deletes another session's member to
   fake it, and never issues `FLUSHALL`. Production config is untouched; this file
   is never deployed.
2. **`APP_DEMO_SEED_ON_STARTUP` → `true`.** `DemoPortfolioInitializer` ships gated
   off, so a stock local stack brings up the V15-seeded demo *user* with no
   portfolio behind it. **The suite's Playwright global setup does not cover this**
   — it seeds the Golden-State E2E subject `…000e2e`, a different account from the
   demo subject `…0d3110`. Enabling the gate is what makes the run reproducible from
   an empty volume rather than from retained data. Verified: 159 holdings on a fresh
   database.
3. **Redis host port → 6380.** An unrelated `optimus-redis` container already held
   the host's 6379 on this machine. Only the *host* side moves; every service still
   reaches Redis at `redis:6379` over the compose network, so nothing in the
   application is reconfigured and no shared service was stopped or flushed.

### Deliberately not proved here

Requirement 6.5's fail-open path is **not** proved against the assembled stack. The
only Redis in this compose file is shared with insight-service and the gateway's
other consumers, and breaking it to obtain outage coverage would take out unrelated
services. It is covered instead by three injected-failure Vitest cases (HTTP non-OK,
rejected fetch, invalid JSON) and by the gateway's own real-Redis
`DemoPresenceIntegrationTest`. Those two kinds of evidence are kept distinct: neither
is browser/assembled-stack evidence.

## Verification runs

All commands below were executed. Results as observed:

| Command | Result |
|---|---|
| `npx vitest run src/lib/hooks/usePresence.test.tsx src/components/asset-picker` | 22 files / **181 passed** (baseline before changes: 21 / 171) |
| `npm test` (full suite, clean) | 64 files / **590 passed** |
| `npx tsc --noEmit` | clean |
| `npx tsc --noEmit -p tests/e2e/tsconfig.e2e-test.json` | **1 error, pre-existing** — see below |
| `npx eslint` on the five changed/added TS files | clean |
| `npx playwright test --config playwright.presence.real.config.ts` | **3 passed** (1.6m) |
| `./gradlew.bat --no-daemon :api-gateway:integrationTest --tests '…DemoPresenceIntegrationTest'` | **13 tests, 0 failures, 0 skipped** |

### Failed and superseded runs, reported rather than hidden

1. **First real-stack run: 1 passed, 1 failed, 1 did not run.** The failure was in
   the draft-edit leg, *after* that test's presence assertions, and was my test's
   fault, not the product's: I used a positional `.first()` checkbox locator, but
   `buildBrowseRows` lists drafted rows before undrafted ones, so with ~159 holdings
   the list reorders under the locator. I did not weaken the assertion — I first
   proved at component level that unchecking a seeded holding works with the banner
   showing (that test is now permanent), then replaced the locator with a named
   ticker resolved from the demo portfolio. Re-run: 3 passed.
2. **First full `npm test`: 1 failed / 590.** `capture-suppression.test.ts` failed
   while the Gradle suite was running concurrently (7.9s for a test that takes
   ~0.9s alone). It passes in isolation, but an isolated retry does not establish
   full-suite success, so the suite was re-run with nothing else competing: 64
   files / 590 passed. Both runs are reported.
3. **The kickoff note's Gradle command cannot run this evidence.** It gives
   `:api-gateway:test --tests '…DemoPresenceIntegrationTest'`, but the class is
   `@Tag("integration")` and the root `test` task carries
   `excludeTags 'integration', 'slim-image'`. That command fails with *"No tests
   found for given includes"*. The task that runs it is `:api-gateway:integrationTest`.
   Worth correcting in the note so a future reader does not record a green `test`
   run as presence evidence.

### The pre-existing E2E TypeScript error

`tests/e2e/global-setup-entrypoint.test.ts(23,34): error TS1343` (`import.meta`).
It is **not** exempted by filename. I removed my new spec, re-ran the same check,
and got the byte-identical single error, then restored the spec — so the baseline
already carries it and my spec/config add zero new diagnostics.

## Files changed

| Path | Change |
|---|---|
| `frontend/src/components/asset-picker/AssetPicker.tsx` | Defect fix — presence key advances on the open transition, not on seeding |
| `frontend/src/components/asset-picker/AssetPicker.presence.integration.test.tsx` | New — 11 component tests (2 are the defect regression) |
| `frontend/tests/e2e/asset-picker-presence.integration.spec.ts` | New — real-stack proof |
| `frontend/playwright.presence.real.config.ts` | New — dedicated flag-on, 1-worker, 0-retry config |
| `frontend/playwright.config.ts` | Adds the new spec to the chromium project's `testIgnore` |
| `docker-compose.presence-e2e.yml` | New — local, disposable overlay (TTL, demo seed, Redis host port) |
| `.kiro/specs/asset-picker-composition/tasks.md` | 9.4 marked complete for the satisfied scope |
| `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` | Three status locations updated to match |
| `docs/superpowers/plans/2026-09-05-b2-task-9-4-presence-integration-handoff.md` | This document |

Test discovery is isolated: `--list` collects the new spec in **0** of
`playwright.config.ts`, `playwright.mocked.config.ts`,
`playwright.asset-picker.mocked.config.ts`, and
`playwright.draft-prices.real.config.ts`, and **3** in
`playwright.presence.real.config.ts`.

## Status propagation

Ledger and master plan were updated together. The PR body declaration must be one
plain unbolded line at line start:

```text
Master-plan impact: updated — B2
```

Guard results are recorded in the commit that accompanies this handoff. After any
separately authorized publication, re-fetch the live PR body and the actual
base/head SHAs and run the guard again — unit tests and a local draft do not
validate a published PR.

## Limitations

- Local only. Not deployed, not exercised in CI, no Production E2E, no live probe.
- Each of Tasks 9.1, 9.3 and 9.4 was proved on its own stack run; there is still no
  *combined* assembled-stack proof covering them together.
- The `true` leg depends on session B being touched inside the TTL. It is touched
  immediately before the opening to keep that deterministic, but the margin is a
  short TTL by construction — a heavily loaded machine could narrow it.
- The demo portfolio's size (159 holdings) comes from the catalog-derived seed; the
  spec resolves its edit target from the live portfolio rather than assuming a
  ticker.

Claude retains implementation ownership in the assigned worktree; Codex reviews
read-only.
