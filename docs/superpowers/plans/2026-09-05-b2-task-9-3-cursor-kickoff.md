# B2 Task 9.3 — Drafted-asset price integration: Cursor implementation kickoff

> **For agentic workers:** Use superpowers:executing-plans to implement the steps below. Track progress with checkbox syntax.

> **Owner authorization boundary:** Implement and verify Task 9.3 locally, prepare scoped local commits and a review handoff. Push, PR creation, merge, workflow dispatch, deployment, cloud/secret access, and production probes require separate authorization. Complete local preparation before requesting publication; authorization for PR #228 does not cover this task.

**Goal:** Prove that the Asset Picker fetches real market prices only for its drafted tickers and correctly displays estimates or unavailable values without changing quantities or save payloads.

**Architecture:** Preserve `BrowseStep → useDraftPrices → loadMarketPrices → apiPath('/market/prices')`. This real URL path already exists. Identify actual integration defects and fix them narrowly; if it already works, deliver evidence and appropriate tests rather than rewriting working code. Reuse the existing batching and partial-failure semantics.

**Tech stack:** Repository-pinned React/Next.js, TanStack Query, TypeScript, Vitest/MSW, Playwright, Spring gateway and market-data-service. No new dependency is expected.

**Spec:** `.kiro/specs/asset-picker-composition/tasks.md`, Tasks 1.10 and 9.3; `requirements.md` Requirement 3.1; `design.md` D3; GC.2 string-quantity/display-only conversion constraint. Read these and repository AGENTS.md before implementation.

## Scope and workspace

- Cursor implements in its assigned `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-cursor`. Confirm with `git rev-parse --show-toplevel`. Use that existing worktree, not a new sibling. Preserve unrelated changes and coordinate if another Cursor task is writing there.
- Inspect current main and branch state before creating a descriptive task branch. Never discard/stash another task's work or blindly stage all files.
- No layout redesign, CSS restyling, new screens, or product-design work. Correct existing loading/unavailable behavior only where the price integration requires it.
- No composition PUT, demo reset, production feature exposure, freshness-status wiring (9.5), presence wiring (9.4), or catalog implementation duplication (9.1).
- Task 9.3 has no explicit B1 Wave 7 gate. PR #228 contains Task 9.1 catalog/CORS work: inspect its actual merge status before choosing a baseline. If unmerged, continue independent price work; do not copy its changes into this branch. Arrange final integrated browser verification after the needed catalog changes land, or state the remaining proof dependency.

## Source map and existing interfaces

| File | Responsibility |
|---|---|
| `frontend/src/components/asset-picker/BrowseStep.tsx` | Builds `draftTickers` from draft map keys and displays estimated values |
| `frontend/src/lib/hooks/useDraftPrices.ts` | Sorts tickers; query key `['asset-picker', 'prices', sortedTickers]`; disables empty drafts; 15-second staleTime |
| `frontend/src/lib/hooks/useDraftPrices.test.tsx` | Existing hook behavior coverage |
| `frontend/src/lib/api/portfolio.ts` | `loadMarketPrices(tickers: string[], token: string): Promise<Map<string, BackendMarketPrice>>`; deduplication, batching, Bearer requests, allSettled partial failures |
| `frontend/src/lib/api/portfolio.batching.test.ts` | Batch and availability contracts; protect other callers of this shared client |
| `frontend/src/lib/utils/quantityDisplay.ts` | Display-only estimated-value conversion; do not mutate canonical quantities |
| `frontend/src/lib/config/api.ts` | API base and prefix normalization |
| `market-data-service/src/main/java/com/wealth/market/MarketPriceController.java` | Filtered GET, request-size limit, explicit unavailable rows; blank filter returns all prices |
| `market-data-service/src/main/java/com/wealth/market/MarketPriceDto.java` | Actual price/availability/currency/timestamp wire fields |
| `frontend/tests/e2e/helpers/browser-auth.ts` | Ordinary E2E gateway login session |
| `frontend/tests/e2e/global-setup.ts` | Explicit local Golden-State/market-data setup; inspect required environment before running |

Locate and extend the existing BrowseStep and quantity-display tests through `rg --files`; do not create a parallel client. Resolve `MARKET_PRICE_BATCH_SIZE`, backend request limits, DTO nullability, and existing formatting directly from source rather than inventing new values.

## Step 1 — Pin actual gaps with contract tests

- [ ] Record baseline SHA, branch, and unrelated dirty paths. Trace the runtime path and determine whether any production mock or adapter gap actually exists.
- [ ] Confirm requested tickers come from the current draft, never the full catalog, search results, or viewport. Empty draft must make no price request: an empty query would request the server's entire price set.
- [ ] Test adding/removing draft tickers, sorting/deduplication, and cache reuse without changing the current freshness policy merely to force test traffic. A request already in flight when a ticker is removed is not a new-scope request; distinguish dispatch-time state from current state.
- [ ] Preserve canonical punctuation and URL encoding (including applicable crypto, exchange-suffixed and FX tickers selected from the canonical catalog). Cover batch boundaries using the existing client tests.
- [ ] Verify valid prices produce the existing estimate; missing/null prices stay unavailable, never zero or a fabricated timestamp. Exercise a failed batch alongside a successful batch. Price failure must not clear the auth session, mutate the draft, or prevent editing for reasons unrelated to existing quantity validation.
- [ ] Confirm changing a quantity only affects the displayed estimate and retains its exact draft string. Do not use floating-point display results as save payload values.
- [ ] For a demonstrated defect, add the regression first, run it RED for the intended reason, implement the smallest fix, then run GREEN. Label tests that already pass as added coverage, not defect regressions.

## Step 2 — Prove the actual browser/stack behavior

- [ ] Use a disposable local Docker stack with a throwaway local internal key, explicit health checks and Golden-State/market-data seeding. Never load production secrets or invoke production refresh workflows. Preserve unrelated containers and document port overrides if required.
- [ ] Reuse Task 9.1's real-stack config only after inspecting the merged version and its exact testMatch/setup. Alternatively create a narrowly scoped `frontend/playwright.draft-prices.real.config.ts` and `frontend/tests/e2e/asset-picker-prices.integration.spec.ts`. Keep the test isolated from default sweeps if its required flag-on build differs; do not edit workflow schedules or broadly wire Wave 9 CI here.
- [ ] Enable the picker only in the local test build. Use ordinary E2E login; no demo-only fixture or internal key in browser requests. The browser must call the real gateway/market-data endpoint without route fulfillment for prices.
- [ ] Distinguish picker-triggered price requests from the Portfolio page's existing price requests/polling. Use a controlled draft transition with a distinct expected ticker set and correlate the triggering action, URL, response, and rendered estimate. A page-wide count or first `/prices` response is not sufficient attribution.
- [ ] Assert each relevant request contains only the dispatch-time draft tickers, with the union across batches matching the expected set. An empty draft dispatches no picker price request. Do not fail on unrelated dashboard traffic.
- [ ] Compare the displayed estimate to the captured successful real response and chosen draft quantity using the existing formatter. Wait for application consumption, not merely response headers or a previously visible value; choose a changed quantity/selection so stale UI cannot satisfy the assertion.
- [ ] Verify that selecting/removing assets and editing quantities performs no holdings save. Do not call public PUT merely to prepare this price-read proof.
- [ ] Use deterministic contract tests for unavailable/failed-batch paths unless a reproducible local real-data setup exercises them safely. Clearly distinguish mocked error coverage from real-stack successful-price evidence.
- [ ] Record reproducible root/frontend working directories, non-secret environment variable names, exact compose/seed/build/test commands, fixture-data source, and teardown. Include required seeding explicitly; persistent data from an earlier run is not a fresh-stack prerequisite check.

## Step 3 — Verification and status

From `frontend`, start with:

```powershell
npx vitest run src/lib/hooks/useDraftPrices.test.tsx src/lib/api/portfolio.batching.test.ts src/components/asset-picker
npm test
npx tsc --noEmit
```

Run ESLint on all changed TypeScript files and the exact local Playwright command for the chosen config. If backend changes are actually necessary, run the targeted backend tests as well. Do not broaden backend scope without documenting the concrete integration need.

- [ ] For the separate E2E tsconfig check, compare full baseline/changed diagnostics. Only the already-recorded TS1343 at `global-setup-entrypoint.test.ts:23` may be excepted if still present. A filename grep cannot prove no regressions in consumers.
- [ ] Report every failed run accurately. An isolated retry or a clean checkout passing does not establish a failure's cause; do not claim a resource-contention explanation without evidence.
- [ ] Update `.kiro/specs/asset-picker-composition/tasks.md` and `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` together. Check off only 9.3 and only after required real integration evidence exists. Otherwise record source progress and keep its outstanding proof explicit.
- [ ] Use durable source/local-verification wording; do not write “open/unmerged” text that becomes stale when the documenting PR merges. Do not invent a future merge SHA. Keep broader Wave 9 and Production E2E open.
- [ ] Include the kickoff/handoff documents deliberately in the scoped file list if carried into the branch. Do not accidentally exclude them or sweep unrelated notes into a commit.
- [ ] Run `git diff --check`, existing status-guard tests, and the actual guard against the proposed committed diff and exact PR body. Stage reviewed paths explicitly and prepare local commits.

PR body must contain this plain, unbolded line:

```text
Master-plan impact: updated — B2
```

Resolve the real base/head SHAs and a UTF-8 body file, then run from repository root:

```powershell
py -3 scripts/check_master_plan_status_propagation.py --base $baseSha --head $headSha --pr-body-file $bodyFile
```

Expected: exit 0. After separately authorized publication, re-fetch the live PR body and actual base/head SHAs and rerun the guard. Its unit tests alone never validate a PR. Recompute the declaration if the eventual scope changes.

## Review handoff

Provide baseline/head SHAs, `git log --oneline origin/main..HEAD` instead of a hardcoded commit count, scoped changed paths, demonstrated defects versus already-working behavior, test commands/results, real browser evidence, and unresolved limitations. State whether Task 9.3 is complete or awaiting integration evidence. Label any request to push/open a PR at the top; no merge or deployment is authorized by this kickoff. Codex will review; Cursor remains the implementation owner in its assigned worktree.
