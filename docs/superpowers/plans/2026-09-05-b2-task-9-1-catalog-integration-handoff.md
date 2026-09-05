# B2 Task 9.1 — real catalog integration: implementation handoff

**Owner approval needed before any of the following:** push this branch, open a PR,
dispatch or trigger any CI workflow, merge, deploy, or run any production probe.
Nothing below has been pushed anywhere. This note only records local, reviewable
work completed in Claude's assigned worktree
(`D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`), per
the kickoff note's authorization boundary
(`docs/superpowers/plans/2026-09-05-b2-task-9-1-claude-kickoff.md`, authored in
Codex's worktree).

## Branch and baseline

- Branch: `claude/b2-task-9-1-real-catalog-integration`, currently 4 commits ahead
  of `origin/main` at `4f1a0428`.
- Originally branched from `origin/main` at `c8fc407c` (PR #226, Task 9.6's
  fixture), and initially also carried a cherry-picked, previously-authored,
  previously-unpushed commit correcting Task 9.6's master-plan status from "open
  at PR #226, unmerged" to "merged via PR #226" (`a2df6d55` on the sibling branch
  `claude/b2-task-9-6-master-plan-status-correction`) — that same correction was
  independently pushed and merged upstream via PR #227 while this task's work was
  in progress. `git rebase origin/main` was then run on this (unpushed, so safe to
  rewrite) branch; it automatically detected and dropped the now-duplicate
  cherry-pick ("skipped previously applied commit"), leaving exactly the four
  Task 9.1 commits below rebased cleanly onto the current `origin/main`. The
  master-plan status propagation guard was re-run against the final
  `origin/main`/`HEAD` pair after the rebase and still passes.
- Working tree was clean apart from two pre-existing untracked plan docs, which
  were left untouched throughout: `docs/superpowers/plans/2026-09-04-b1-rc-pr222-session-handoff.md`
  and `docs/superpowers/plans/2026-09-05-b2-task-9-6-demo-authenticated-playwright-fixture.md`.

## Prerequisite resolved

Task 9.1's own text in `tasks.md` asserted the `GET /api/assets` controller "doesn't
exist… verified directly: no `GET /api/assets` mapping exists anywhere in
`portfolio-service` source today." That is false on this baseline:
`portfolio-service/src/main/java/com/wealth/portfolio/AssetCatalogController.java`
exists, and the master plan's own release ledger
(`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`, the `GET /api/assets serving catalog
data` row and the Wave 4 row) already records it served under **R-B2** (Artifact 2a,
`portfolio-service--0000081`). The stale sentence in `tasks.md` has been corrected
narrowly; no serving/deployment claim was invented, only the existing recorded
evidence was cited.

## What was actually broken (not a rewrite — `BrowseStep`/`AssetPicker` already called
the real `apiPath('/assets')` via `fetchCatalog`, Task 1.11)

Both defects were invisible to the existing mocked contract tests (MSW) because MSW
does not enforce a real browser's CORS behavior — another instance of the
evidence/oracle-mismatch pattern.

### 1. Gateway CORS gap (`api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java`)

`corsConfigurationSource()` allowed `Authorization`/`Content-Type`/`X-Requested-With`
but never allowed `If-None-Match`, and never configured `exposedHeaders` at all.
Cross-origin (any deployment or local stack where the frontend and gateway are on
different origins — this repo's own default local Playwright stack included,
`:3000` vs `:8080`):

- A real browser's preflight would refuse to let `fetchCatalog` send
  `If-None-Match` at all.
- Even on the very first, unconditional request, `response.headers.get("ETag")`
  would read `null` in the browser (the CORS-safelisted response header set does
  not include `ETag`), because `Access-Control-Expose-Headers` was never sent.

Net effect: conditional revalidation was permanently defeated cross-origin, with
**no visible error anywhere** — every catalog load looked like a normal, if
slightly wasteful, full `200` fetch.

**Fix:** added `If-None-Match` to `allowedHeaders` and a new `exposedHeaders:
["ETag"]`. Preserved every existing origin/header/method entry — nothing removed.

### 2. Silent empty-Browse on catalog failure (`frontend/src/components/asset-picker/AssetPicker.tsx`)

There was no `catalogQuery.isError` branch. An initial catalog fetch failure left
`catalogQuery.isLoading` false and `catalogQuery.data` `undefined`; the component
fell straight through to the Browse render with `catalogQuery.data?.assets ?? []`
— an apparently-healthy, empty asset list, with no indication anything had failed.

**Fix:** a `catalogUnavailable = catalogQuery.isError && !catalogQuery.data` guard
renders a `role="alert"` message plus a `Retry` button (`catalogQuery.refetch()`)
instead of Browse, and also gates the once-per-open seeding effect so it doesn't
seed against an empty catalog while in this state. Deliberately excludes a
*background* revalidation failure with an already-held catalog — `catalogQuery.data`
still set — which stays silent and self-heals per the existing `staleTime` policy
(explicit plan constraint: "retain existing valid cached-data behavior
deliberately").

## Regression tests (RED confirmed before each fix, GREEN confirmed after)

- `api-gateway/src/test/java/com/wealth/gateway/CorsConfigurationTest.java` — two
  new tests: `preflightForAssetsAllowsIfNoneMatchHeader`,
  `authenticatedGetOnAssetsExposesETagHeader`. RED before the `SecurityConfig` fix
  (`:api-gateway:integrationTest --tests CorsConfigurationTest`, 2 failed / 4 total).
  GREEN after (`BUILD SUCCESSFUL`), and the full
  `:api-gateway:test :api-gateway:integrationTest` suite re-run clean afterward (no
  regressions, including the pre-existing `AuthRateLimitFilter`'s own independent
  `Access-Control-Expose-Headers: Retry-After` path, which does not read from this
  shared config bean and is unaffected).
- `frontend/src/components/asset-picker/AssetPicker.test.tsx` — two new tests
  under "AssetPicker — catalog failure (Task 9.1)": visible-error-on-failure, and
  retry-recovers-to-Browse. RED before the `AssetPicker.tsx` fix (2 failed / 16 in
  the targeted run). GREEN after — full targeted run (`assetPicker.test.ts`,
  `useCatalog.test.tsx`, `asset-picker/`) is 22 files / 168 tests passed.
- `frontend/src/lib/api/assetPicker.test.ts` — one new coverage test for
  `fetchCatalog` throwing on an initial failure with no cached catalog to fall
  back on (passed immediately; documents existing correct behavior at the client
  level, per the plan's explicit ask to extend this file's failure coverage).

## Real-stack browser proof (the actual point of this task)

New spec `frontend/tests/e2e/asset-catalog.integration.spec.ts` + its own config
`frontend/playwright.asset-catalog.real.config.ts` (local-only — the main
`playwright.config.ts` now excludes this spec by name, since its build needs
`NEXT_PUBLIC_ENABLE_ASSET_PICKER=true`, which the main config's build never sets;
Task 9.9 owns any future CI wiring). Uses the ordinary Golden-State E2E identity
(`helpers/browser-auth.ts` / `helpers/api.ts`) — Task 9.6's demo identity is not
needed for a read-only catalog path. No `page.route` mocking anywhere in the spec.

**Local stack used:** `docker compose up -d --build` (repository root
`docker-compose.yml`), with `INTERNAL_API_KEY` set (a throwaway local value) so
the Golden-State seeder could run, and Redis's host port remapped
`16379:6379` via a local, untracked compose override (`services.redis.ports:
!override`) — this machine's own `optimus-redis` container (an unrelated project)
already held the default `6379`; nothing about that container was touched. Golden
State was seeded via `frontend/tests/e2e/global-setup.ts` run standalone through
`ts-node` (the same script CI's Playwright `globalSetup` uses).

**What the spec proved, actually run (not assumed):**

1. Navigated to `/portfolio` as the real E2E user, opened "Edit Holdings" — the
   real catalog rendered (checked against a live, sampled ticker from
   `config/seed-tickers.json`, never a hardcoded ticker or count).
2. The first `GET /api/assets` returned `200` with an `ETag` on the wire (a wire
   sanity check only — Playwright's own response inspection bypasses the page's
   CORS-enforced Headers API, so it can't by itself prove JS-readability; see next
   point for the proof that actually matters).
3. Closed the modal, fast-forwarded the page's own clock 61 real-seconds-equivalent
   past `useCatalog`'s 60s `staleTime` (`page.clock.fastForward`, installed only
   after the page had already finished its real login/render — not a real 61s
   sleep, not a virtualized clock from page load), reopened the modal: the second
   `GET /api/assets` carried a real `If-None-Match` and received a genuine `304`,
   with the catalog still rendered from the client's retained cache. **This
   assertion is the actual cross-origin proof**: a `304` here is only reachable if
   `fetchCatalog`'s own `response.headers.get("ETag")` — the browser's in-page JS,
   subject to full CORS enforcement — really did read a non-null `ETag` on the
   first response and really did get to send `If-None-Match` past the gateway's
   preflight on the second.
4. **RED/GREEN captured directly, not assumed:** reverted `SecurityConfig`'s two
   new lines, rebuilt only the `api-gateway` image, reran the exact same spec
   against the exact same running stack — it failed exactly as predicted (`304`
   expected, got `200`, because the browser never captured a usable `ETag` to
   revalidate with). Restored the fix, rebuilt, reran — passed again. Full local
   command sequence:

   ```powershell
   docker compose up -d --build
   # (redis port conflict with an unrelated container on this host → local override,
   #  see docker-compose.port-override.yml pattern above; not part of the diff)
   $env:INTERNAL_API_KEY = "local-e2e-internal-key-2026"
   npx ts-node --compiler-options '{"module":"commonjs"}' tests/e2e/global-setup.ts
   npx playwright test --config playwright.asset-catalog.real.config.ts --reporter=list
   # → 1 passed
   # (revert SecurityConfig, docker compose build api-gateway, docker compose up -d api-gateway)
   npx playwright test --config playwright.asset-catalog.real.config.ts --reporter=list
   # → 1 failed: expected 304, got 200
   # (restore SecurityConfig, rebuild, restart)
   npx playwright test --config playwright.asset-catalog.real.config.ts --reporter=list
   # → 1 passed
   docker compose down
   ```

5. The local stack was torn down (`docker compose down`) after evidence was
   captured — nothing was left running.

No credentials, tokens, or response bodies beyond ticker symbols are reproduced
above.

## Full validation run (this branch, after all fixes)

- `npx vitest run src/lib/api/assetPicker.test.ts src/lib/hooks/useCatalog.test.tsx src/components/asset-picker` → 22 files / 168 tests passed
- `npm test` (full frontend suite, from `frontend/`) → 570 tests, 569 passed / 1
  failed on the full run (`capture-suppression.test.ts`'s
  `installGatewaySessionInitScript` test, a `5000ms` timeout); re-run in isolation
  → passed in `1146ms`. Confirmed unrelated to this change (untouched file, no
  dependency on anything edited here) and non-reproducing — a resource-contention
  flake from the earlier heavy Gradle/Docker activity in the same session, not a
  regression.
- `npx tsc --noEmit` (frontend) → clean, no errors.
- `npx tsc -p tests/e2e/tsconfig.e2e-test.json --noEmit` → exactly the recorded
  baseline exception (`TS1343` at `global-setup-entrypoint.test.ts:23`), nothing
  new.
- `npx eslint` on every changed TypeScript file → clean.
- `./gradlew --no-daemon :api-gateway:test :api-gateway:integrationTest` → `BUILD
  SUCCESSFUL`, no regressions.
- `git diff --check` → clean (no whitespace errors).
- `python scripts/tests/test_master_plan_status_propagation.py -v` → 33 tests, all
  passed.

## Files changed

```
.kiro/specs/asset-picker-composition/tasks.md                              (Task 9.1 entry rewritten)
api-gateway/src/main/java/com/wealth/gateway/SecurityConfig.java            (CORS fix)
api-gateway/src/test/java/com/wealth/gateway/CorsConfigurationTest.java     (2 new tests)
docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md                                  (status text + cherry-picked 9.6 correction)
frontend/playwright.config.ts                                               (testIgnore entry for the new local-only spec)
frontend/src/components/asset-picker/AssetPicker.test.tsx                   (2 new tests)
frontend/src/components/asset-picker/AssetPicker.tsx                       (catalogUnavailable error/retry state)
frontend/src/lib/api/assetPicker.test.ts                                    (1 new coverage test)
frontend/playwright.asset-catalog.real.config.ts                           (new — dedicated real-stack config)
frontend/tests/e2e/asset-catalog.integration.spec.ts                       (new — real-stack browser proof)
```

## Remaining limitations / explicitly out of scope here

- Tasks 9.2–9.5 and 9.7–9.9, Wave 9's broader live integration, and Wave 10 remain
  entirely untouched and open, as the kickoff note scoped.
- The new real-stack spec is local-only. It is not wired into any CI workflow —
  Task 9.9 owns that decision and its parity checks; wiring it in unreviewed here
  would be scope expansion beyond this task.
- No production probe, deploy, push, PR, or workflow dispatch has been performed.
  All of that requires separate, explicit owner authorization per the kickoff
  note's boundary — this note deliberately does not request or assume it.
- The `capture-suppression.test.ts` full-suite flake noted above was investigated
  enough to confirm it is pre-existing and unrelated, not fixed (out of scope for
  this task; flagged here for visibility, not left silently unmentioned).

## Requested next step

Local implementation, regression tests, and real-stack verification are complete
and reviewable at this branch's current (uncommitted-to-remote) commits. Awaiting
owner decision on whether to authorize pushing this branch and opening a PR.
