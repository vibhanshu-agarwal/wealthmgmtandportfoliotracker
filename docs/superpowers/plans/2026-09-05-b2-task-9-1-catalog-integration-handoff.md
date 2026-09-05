# B2 Task 9.1 — real catalog integration: implementation handoff

**Owner approval needed before any of the following:** push this branch, open a PR,
dispatch or trigger any CI workflow, merge, deploy, or run any production probe.
Nothing below has been pushed anywhere. This note only records local, reviewable
work completed in Claude's assigned worktree
(`D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`), per
the kickoff note's authorization boundary
(`docs/superpowers/plans/2026-09-05-b2-task-9-1-claude-kickoff.md`, authored in
Codex's worktree).

## Review round (source/docs review, no independent test run by the reviewer)

The reviewer read source and documentation only and flagged four gaps, all
accepted as valid and addressed:

1. **Revalidation assertion checked the UI before the second response actually
   completed, and never compared the sent `If-None-Match` against the first
   `ETag`.** Fixed — see "Real-stack browser proof" below for the exact new
   assertion order and a re-verified RED/GREEN pair against the live stack.
2. **The spec claimed `docker compose up -d` needed "nothing else," but the
   Golden-State seeder needs a real `INTERNAL_API_KEY` and an explicit seed
   step this config doesn't run itself.** Fixed — the exact reproducible
   sequence (env vars, seeding command, working directory) is now in both the
   spec's header comment and this note.
3. **The full-suite flake claim ("resource contention from competing builds")
   was asserted on the strength of one isolated retry passing, which doesn't
   establish what caused the full-run failure.** The reviewer was right that
   this wasn't established — re-investigated properly with a controlled `C:`
   (clean, fast disk) vs. `D:` (this worktree) comparison; see "Full validation
   run" below for the actual finding, which is narrower and more honest than
   the original claim.
4. **The branch has five (now six, after this round) commits, not four, as an
   earlier version of this note stated.** Corrected below.

The reviewer's note is accurate: no production verification is established by
this change, and none was claimed.

## Branch and baseline

- Branch: `claude/b2-task-9-1-real-catalog-integration`, ahead of `origin/main`
  at `4f1a0428`. **Deliberately not stating a fixed commit count here**: an
  earlier version of this note said "4 commits," which was already stale by the
  time it was written (a 5th, documentation-only commit landed immediately
  after), and a corrected "6 commits" in a later version went stale the same way
  the moment *that* correction was itself committed as a 7th — any commit that
  states how many commits exist is, by construction, undercounting itself by
  one. Run `git log --oneline origin/main..HEAD` for the true count; the
  four implementation/test commits are `fix(gateway): allow If-None-Match…`,
  `fix(asset-picker): show a visible error…`, `test(e2e): add real-stack
  catalog…`, and `docs(b2): record Task 9.1…`, followed by documentation and
  review-response commits on top.
- Originally branched from `origin/main` at `c8fc407c` (PR #226, Task 9.6's
  fixture), and initially also carried a cherry-picked, previously-authored,
  previously-unpushed commit correcting Task 9.6's master-plan status from "open
  at PR #226, unmerged" to "merged via PR #226" (`a2df6d55` on the sibling branch
  `claude/b2-task-9-6-master-plan-status-correction`) — that same correction was
  independently pushed and merged upstream via PR #227 while this task's work was
  in progress. `git rebase origin/main` was then run on this (unpushed, so safe to
  rewrite) branch; it automatically detected and dropped the now-duplicate
  cherry-pick ("skipped previously applied commit"), leaving the four Task 9.1
  commits rebased cleanly onto the current `origin/main`, followed by two more
  commits addressing this review round (see below). The master-plan status
  propagation guard was re-run against the final `origin/main`/`HEAD` pair after
  the rebase and still passes.
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

**Reproducible fresh-stack setup** (this is the exact recipe now written into both
the spec's own header comment and the dedicated config's comment — a first pass
of this note claimed `docker compose up -d` needed "nothing else," which was
wrong: the Golden-State seeder needs a real `INTERNAL_API_KEY`, the config has no
`globalSetup` to run it, and this host specifically had a port conflict). From the
repository root, then from `frontend/`:

```powershell
$env:INTERNAL_API_KEY = "<any non-empty local value>"
docker compose up -d --build
# Wait for every service healthy: docker compose ps
# This host's Docker daemon already had an unrelated project's `optimus-redis`
# container bound to the default host port 6379 — worked around with a local,
# untracked compose override (services.redis.ports: !override, replacing the
# port entirely — a plain merge-append keeps both and still conflicts) passed as
# an extra -f to both `build` and `up`. Not needed on a host without that
# conflict; not part of this diff.

cd frontend
$env:INTERNAL_API_KEY = "<the same value as above>"
$env:NEXT_PUBLIC_API_BASE_URL = "http://localhost:8080"
npx ts-node --compiler-options '{"module":"commonjs"}' tests/e2e/global-setup.ts

npx playwright test --config playwright.asset-catalog.real.config.ts --reporter=list
```

**What the spec proved, actually run (not assumed):**

1. Navigated to `/portfolio` as the real E2E user, opened "Edit Holdings" — the
   real catalog rendered (checked against a live, sampled ticker from
   `config/seed-tickers.json`, never a hardcoded ticker or count). The first
   `GET /api/assets` returned `200` with an `ETag` on the wire, and the request
   carried no `If-None-Match` (nothing cached yet).
2. Closed the modal, fast-forwarded the page's own clock 61 real-seconds-equivalent
   past `useCatalog`'s 60s `staleTime` (`page.clock.fastForward`, installed only
   after the page had already finished its real login/render — not a real 61s
   sleep, not a virtualized clock from page load), reopened the modal, and — this
   is the review-round tightening — **waited for the second `GET /api/assets`
   response to actually land before touching the UI again**: asserted its
   `If-None-Match` request header equals the *exact* `ETag` string the first
   response returned (not just "some 304"), then asserted the status is a genuine
   `304`. Only after that did it re-check the Browse checkbox is still visible and
   that no `role="alert"` is present — proving `fetchCatalog`'s cache-retention
   held after the revalidation actually completed, not merely that
   stale-while-revalidate was still showing the first response's data. A
   first-round version of this test checked the checkbox *before* waiting on the
   second response and never compared the `If-None-Match` value at all — code
   review caught both gaps; fixed and re-verified (see below).
3. **This `If-None-Match`-equals-first-`ETag` assertion is the actual cross-origin
   proof**: it is only satisfiable if `fetchCatalog`'s own
   `response.headers.get("ETag")` — the browser's in-page JS, subject to full CORS
   enforcement — really did read that exact `ETag` value across the CORS boundary
   on the first response and really did get to send it as `If-None-Match` past the
   gateway's preflight on the second. Playwright's own response/request header
   inspection runs at the CDP Network layer and does *not* go through the page's
   CORS-enforced Headers API, so it cannot substitute for this — the spec's header
   comment says so explicitly now, so a future reader doesn't mistake the wire-level
   sanity check for the actual proof.
4. **RED/GREEN captured directly, twice** (once before this review round's test
   tightening, once after, to confirm the tightened assertions still exercise the
   same real defect): reverted `SecurityConfig`'s two new lines, rebuilt only the
   `api-gateway` image, reran the exact same spec against the exact same running
   stack. Before tightening: failed with `304` expected, `200` received. After
   tightening: fails one assertion earlier and more precisely — `If-None-Match`
   expected the real `ETag` string, received `null` — directly showing the browser
   never captured a usable `ETag` to revalidate with at all. Restored the fix,
   rebuilt, reran both times — passed again. Full local command sequence for the
   post-tightening round:

   ```powershell
   npx playwright test --config playwright.asset-catalog.real.config.ts --reporter=list
   # → 1 passed
   # (revert SecurityConfig, docker compose build api-gateway, docker compose up -d api-gateway)
   npx playwright test --config playwright.asset-catalog.real.config.ts --reporter=list
   # → 1 failed: "the second request must revalidate with the exact ETag the first
   #   response returned" — expected the real ETag string, received null
   # (restore SecurityConfig, rebuild, restart)
   npx playwright test --config playwright.asset-catalog.real.config.ts --reporter=list
   # → 1 passed
   docker compose down
   ```

5. The local stack was torn down (`docker compose down`) after each round of
   evidence was captured — nothing was left running between or after.

No credentials, tokens, or response bodies beyond ticker symbols are reproduced
above.

## Full validation run (this branch, after all fixes)

- `npx vitest run src/lib/api/assetPicker.test.ts src/lib/hooks/useCatalog.test.tsx src/components/asset-picker` → 22 files / 168 tests passed
- `npm test` (full frontend suite, from `frontend/`, run on this branch's
  worktree at `D:/…/wealthmgmtandportfoliotracker-claude`) → 570 tests, 569
  passed / 1 failed (`capture-suppression.test.ts`'s
  `installGatewaySessionInitScript` test, a `5000ms` timeout). **A first pass of
  this note claimed this was "a resource-contention flake from the earlier heavy
  Gradle/Docker activity" on the strength of one isolated retry passing — code
  review correctly rejected that as unestablished** (an isolated pass doesn't show
  what actually made the full run fail, and I hadn't re-run the full suite with
  no competing builds at all before writing that). Re-ran `npm test` again with
  nothing else running (no Docker containers, no Gradle process) — **it failed
  the same way again**, directly disproving the original "competing builds"
  explanation. To isolate the real variable, built two disposable, detached
  worktrees on `C:\temp\` (fast NVMe, vs. this repo's `D:` SATA HDD) and ran the
  identical full suite in
  each: `origin/main` unmodified → **567/567 passed**; this branch's exact code
  (same commit) → **570/570 passed**. Both clean, on the same machine, in the
  same session. That is a controlled, direct comparison: identical code passes
  fully on `C:`, and the one file involved
  (`tests/e2e/helpers/__tests__/capture-suppression.test.ts`) is untouched by
  this diff and has no dependency on anything this task edited. **Conclusion,
  properly established this time**: the failure is specific to running the
  Vitest suite from this `D:` worktree in this session — not caused by this
  change, and not shown to be a pre-existing defect in the test itself either,
  since it did not reproduce at all on a clean environment with identical code.
  I have not pinned the exact OS-level mechanism (disk I/O, leftover
  process/scheduler pressure from the earlier Docker/Gradle activity on this
  same drive, or something else `D:`-specific) and am not claiming one. The
  authoritative full-suite result for this task's own code is the clean
  **570/570 pass on `C:`**; the `D:`-specific failure is reported here as an
  observed, unresolved environment artifact, not asserted as fixed or as
  provably unrelated to disk speed.
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
- The `capture-suppression.test.ts` full-suite failure on this `D:` worktree was
  investigated with a controlled comparison (see above) that rules out this
  diff's code as the cause, but does not pin the actual environmental mechanism
  and is not fixed — out of scope for this task (the file is untouched by this
  change) and flagged here as an open, unresolved observation, not asserted as
  understood or resolved.

## Requested next step

Local implementation, regression tests, and real-stack verification are complete
and reviewable at this branch's current (uncommitted-to-remote) commits. Awaiting
owner decision on whether to authorize pushing this branch and opening a PR.
