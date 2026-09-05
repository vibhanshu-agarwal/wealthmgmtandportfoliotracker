# B2 Task 9.5 — Real asset-price freshness integration: Cursor kickoff

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Track progress with checkbox syntax.

> **Owner authorization boundary:** Implement and verify Task 9.5 locally, prepare scoped local commits, and write a review handoff. Push, pull-request creation, merge, workflow dispatch, deployment, cloud/secret access, and production probes require separate owner authorization. Complete all local work before requesting publication. Approval for PR #230 or any earlier B2 PR does not carry to this task.

**Goal:** Prove that the Portfolio page consumes the real backend `assetPriceFreshness` object and renders the compact portfolio-level status and details without deriving freshness in the browser.

**Architecture:** Preserve the existing path `GET /api/portfolio/summary → fetchPortfolioSummary → usePortfolioSummary → PortfolioPageContent → FreshnessStatus/FreshnessDetailsPopover`. The full source path already appears connected on `main`; begin by testing the actual path and fix only demonstrated defects. Keep deterministic state/format edge cases in frontend and backend contract tests, and use one focused browser test to prove that an authenticated real summary response drives the visible UI.

**Tech stack:** Repository-pinned React/Next.js, TanStack Query, TypeScript, Vitest/MSW, Playwright, Spring MVC portfolio-service, PostgreSQL and Docker Compose. No new dependency is expected.

**Spec:** `.kiro/specs/asset-picker-composition/tasks.md` Tasks 1.16–1.18 and 9.5; `.kiro/specs/asset-picker-composition/requirements.md` Requirements 3.2, 3.3/3a and 3.4; Spec A's `design.md` freshness response contract. Read these and repository `AGENTS.md` before implementation.

## Global constraints

- Render one aggregate portfolio-level signal. Never derive freshness from holdings or add per-row freshness badges.
- Consume the backend precedence and counts exactly: `MISSING > UNKNOWN > STALE > FRESH` is backend-owned.
- The response contains all five fields: `state`, optional `oldestKnownAssetPriceObservationTimestamp`, `staleHoldings`, `unknownPriceHoldings`, and `missingPriceHoldings`.
- The timestamp key is omitted when there is no known observation. Do not normalize omission into a fabricated timestamp or a “fresh as of now” value.
- A missing, loading, malformed or failed summary must not be presented as known-fresh.
- Task 9.5 has no additional Spec A or B1 Wave 7 blocker. Do not invent a dependency on Task 9.2 for initial page-load integration.
- Task 9.2 still gates a real composition-save round trip. Preserve Task 1.18's existing summary invalidation contract, but do not claim a real post-save E2E result without the public composition endpoint.
- No layout redesign, CSS restyling, new screen, new backend freshness algorithm, database migration, polling policy change, composition PUT, demo reset, CI wiring, deployment or production exposure is in scope.

---

## Baseline, workspace and ownership

- Inspected baseline: `origin/main@1664bb8eb837af6300be39055e1dc5e7ff6fd662`, the merge commit for PR #230. Re-fetch and record the actual `origin/main` before starting.
- Cursor works only in `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-cursor`. Before any mutation, run `git rev-parse --show-toplevel`, `git status --short`, `git branch --show-current`, and `git log -1 --oneline`.
- Use Cursor's persistent assigned worktree. Preserve unrelated changes and coordinate if another Cursor task occupies it. Do not stash, reset, clean, or sweep existing files into this task.
- Create a descriptive task branch from the verified current `origin/main`. Do not copy files from the dirty Codex checkout; this note was prepared by inspecting Git objects at `origin/main`.
- Cursor implements and owns the branch. Codex reviews read-only in Cursor's worktree unless Cursor explicitly authorizes an edit.

## Inspected source map

| Path | Existing responsibility and treatment |
|---|---|
| `frontend/types/portfolio.d.ts` | Defines `AssetPriceFreshnessDTO` and the real `PortfolioSummaryDTO`. Preserve all five fields and the optional timestamp. |
| `frontend/src/lib/apiService.ts` | `fetchPortfolioSummary(userId, token)` performs the authenticated `apiPath('/portfolio/summary')` GET. Do not create a parallel client. |
| `frontend/src/lib/hooks/usePortfolio.ts` | `portfolioKeys.summary(userId)` and `usePortfolioSummary`; preserve auth gating, retry rules and the 30-second stale time unless a reproduced defect requires a narrow change. |
| `frontend/src/components/portfolio/PortfolioPageContent.tsx` | Passes `summary?.assetPriceFreshness` to the portfolio-level status. Confirm this is the actual runtime path. |
| `frontend/src/components/freshness/FreshnessStatus.tsx` | Compact summary and Details trigger; renders nothing without data. No redesign is planned. |
| `frontend/src/components/freshness/FreshnessDetailsPopover.tsx` | Displays nonzero state counts and the absolute observation timestamp with the existing accessible interaction contract. |
| `frontend/src/components/freshness/freshnessFormat.ts` | Formats the backend-owned state/count/timestamp. Do not add state derivation or infer freshness from price values. |
| `frontend/src/components/freshness/*.test.tsx` and `freshnessFormat.test.ts` | Existing deterministic UI/format coverage. Extend gaps rather than creating a second formatter suite. |
| `frontend/src/components/portfolio/PortfolioPageContent.test.tsx` | Existing mocked proof that the page renders `usePortfolioSummary().assetPriceFreshness`. |
| `frontend/src/lib/hooks/usePortfolio.test.ts` | Existing authenticated summary-request coverage. Preserve query-key and retry behavior. |
| `frontend/src/components/asset-picker/postSaveReconciliation.integration.test.tsx` | Existing mocked Task 1.18 proof that a successful save invalidates and re-fetches summary. Keep this separate from Task 9.5's real initial-read evidence. |
| `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioSummaryController.java` | Real summary endpoint; identity comes from the gateway-injected `X-User-Id`, not browser authority over the query parameter. |
| `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioService.java` | Computes the aggregate state, counts, oldest known observation and partial valuation. Treat this as backend-owned. |
| `portfolio-service/src/main/java/com/wealth/portfolio/dto/PortfolioSummaryDto.java` and `AssetPriceFreshnessDto.java` | Authoritative wire shape; `@JsonInclude(NON_NULL)` omits a null oldest-known timestamp. |
| `portfolio-service/src/main/java/com/wealth/portfolio/freshness/AssetPriceFreshness.java` | Pure per-holding classification used by the aggregate. Out of scope unless real evidence demonstrates a backend defect. |
| `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioServiceFreshnessValuationTest.java` | Existing deterministic `FRESH`, `STALE`, `UNKNOWN`, `MISSING`, empty and FX-independence coverage. |
| `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioSerializationBoundarySliceTest.java` | Real Jackson boundary for field presence/omission. |
| `frontend/tests/e2e/global-setup.ts` | Local health gate and Golden-State plus market-data seeding. It requires a nonblank local internal key; do not rely on retained data. |
| `frontend/tests/e2e/helpers/browser-auth.ts` | Ordinary E2E user login/session initialization for the real browser proof. |
| `frontend/playwright.draft-prices.real.config.ts` and `frontend/tests/e2e/asset-picker-prices.integration.spec.ts` | Reference for dedicated real-stack isolation and reproducible setup. Do not copy its price-specific mocks or assertions. |
| `frontend/playwright.config.ts`, `playwright.mocked.config.ts`, `playwright.asset-picker.mocked.config.ts` | Exclude the new local-only real-stack spec from unrelated/default test discovery. |

Expected new files are `frontend/playwright.asset-freshness.real.config.ts`, `frontend/tests/e2e/asset-price-freshness.integration.spec.ts`, and `docs/superpowers/plans/2026-09-05-b2-task-9-5-freshness-integration-handoff.md`. Production source changes are conditional on a reproduced defect.

## Task 1: Pin the current frontend and wire contracts

**Files:**
- Inspect: all source-map paths above
- Modify conditionally: the smallest existing frontend adapter/component file implicated by a failing test
- Test: existing freshness, portfolio-page, hook and post-save reconciliation tests

**Interfaces:**
- Consumes: `PortfolioSummaryDTO.assetPriceFreshness: AssetPriceFreshnessDTO`
- Produces: one portfolio-level `FreshnessStatus` driven by the authenticated summary response

- [ ] Record the current base SHA, branch and dirty paths. Read the owning B2 requirements/design/task entries and Spec A response shape before changing tests.
- [ ] Run the focused existing tests unchanged and record their baseline. Classify every new test that passes on unchanged `main` as coverage, not a regression.
- [ ] Trace the runtime import/type path. Confirm `fetchPortfolioSummary` returns the root `frontend/types/portfolio.d.ts` shape and that no other adapter trims `assetPriceFreshness` before `PortfolioPageContent` renders it.
- [ ] Verify the browser request is an authenticated GET to the real API path. The `userId` query parameter is not authorization; do not add client-side trust or identity behavior.
- [ ] Add or extend deterministic tests for the complete five-field object reaching the page, including mixed nonzero counts, the backend-selected state, a known timestamp, and the omitted-timestamp case.
- [ ] Assert that undefined/loading, HTTP failure, rejected fetch, malformed body, and a response without usable freshness do not show “All prices fresh” or a current timestamp. Preserve the app's existing error surface; Task 9.5 must not introduce a separate blocking page error unless the specification already requires one.
- [ ] Assert the UI never recomputes state from holdings, current prices, `partialValuation`, or timestamps. Use deliberately contradictory fixture values where useful: the displayed state/count must follow `assetPriceFreshness`, not a parallel client calculation.
- [ ] Re-run the existing post-save reconciliation test. It must still invalidate `portfolioKeys.summary(userId)` and consume the re-fetched response rather than assume `FRESH`; do not replace its mocked save with a public PUT while Task 9.2 is blocked.
- [ ] If production code is defective, first create a focused failing regression, run it RED for the intended reason, implement the smallest correction, then run GREEN. If the source already works, do not rewrite it merely to manufacture an implementation diff.

## Task 2: Prove the real authenticated summary drives the UI

**Files:**
- Create: `frontend/playwright.asset-freshness.real.config.ts`
- Create: `frontend/tests/e2e/asset-price-freshness.integration.spec.ts`
- Modify: `frontend/playwright.config.ts` only as needed to exclude this local-only spec

**Interfaces:**
- Consumes: real `GET /api/portfolio/summary` through the gateway and the Golden-State/market-data seed
- Produces: attributable browser evidence tying the real JSON object to the compact status and Details content

- [ ] Use a disposable local Docker Compose stack with a throwaway nonblank `INTERNAL_API_KEY`. Inspect active compose projects and host-port conflicts first; never stop or flush an unrelated service.
- [ ] Start the current stack from the repository root, wait for health, and explicitly run `frontend/tests/e2e/global-setup.ts` with the same local internal key and `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`. Record `E2E_TEST_USER_EMAIL` and `E2E_TEST_USER_PASSWORD` as required environment names without recording their values. Do not read production secrets.
- [ ] Make fresh-stack setup reproducible: seed both the Golden-State portfolio and current market data, then wait until the summary endpoint has consumed the published price events. Persistent database/cache state from an earlier run is not a prerequisite.
- [ ] Create a dedicated config with one worker, zero retries, a narrow test match, `reuseExistingServer: false`, and the real gateway base. No Asset Picker feature flag is required merely to render the existing portfolio-level status; do not enable unrelated flags.
- [ ] Use the ordinary E2E authentication helper. Install the browser response listener before navigating to `/portfolio`; capture the actual successful GET whose normalized path is `/api/portfolio/summary`, its status, and parsed `assetPriceFreshness` body. Do not fulfill, abort, rewrite or mock that route.
- [ ] Validate the captured object has a recognized state, integer nonnegative counts, and the timestamp's exact optionality. Fail if the object or a required count is absent. Do not let a TypeScript cast serve as runtime validation.
- [ ] Derive only the expected presentation text from the captured backend object using the production formatter. Await the compact status after that response so a retained DOM value cannot satisfy the assertion. Assert the displayed affected count corresponds to the backend-selected state.
- [ ] Open Details and compare every nonzero Missing/Unknown/Stale row with the captured counts, verify zero rows are absent, and compare the absolute timestamp display when present. If the real seeded response omits the timestamp, assert the specified “No price observation on record” copy instead.
- [ ] Prove attribution: record the request method/path and that it carries Bearer authentication without logging the token. Distinguish the browser response from any API setup probe and from `/api/portfolio` or `/api/market/prices` traffic.
- [ ] Keep `STALE`, `UNKNOWN`, `MISSING`, mixed-count precedence, malformed response and transport failure deterministic in frontend/backend tests unless a safe, reproducible local fixture already creates them. Do not mutate shared database rows, shorten production freshness policy, or add a product endpoint solely to force browser states.
- [ ] Use `--list` to prove that the new spec is collected by its dedicated config and by none of the default, mocked, catalog, prices or presence configurations. Keep broader Wave 9 CI changes for Task 9.9.
- [ ] Record root/frontend working directories, environment variable names, exact compose/seed/build/test/teardown commands, fixture source and health observations. Tear down only the compose project started for this task.

## Task 3: Verify, propagate status and prepare review

**Files:**
- Modify: `.kiro/specs/asset-picker-composition/tasks.md`
- Modify: `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`
- Create: `docs/superpowers/plans/2026-09-05-b2-task-9-5-freshness-integration-handoff.md`

**Interfaces:**
- Consumes: reviewed source/contract tests and attributable real-stack evidence
- Produces: durable Task 9.5 status and a reviewable local branch

Run focused frontend verification from `frontend`:

```powershell
npx vitest run src/components/freshness src/components/portfolio/PortfolioPageContent.test.tsx src/lib/hooks/usePortfolio.test.ts src/components/asset-picker/postSaveReconciliation.integration.test.tsx
npm test
npx tsc --noEmit
npx tsc --noEmit -p tests/e2e/tsconfig.e2e-test.json
npx playwright test --config playwright.asset-freshness.real.config.ts
```

Run ESLint on every changed TypeScript/TSX file. From repository root, verify the backend contract that the browser consumes:

```powershell
.\gradlew.bat --no-daemon :portfolio-service:test --tests 'com.wealth.portfolio.PortfolioServiceFreshnessValuationTest' --tests 'com.wealth.portfolio.PortfolioSummaryControllerTest' --tests 'com.wealth.portfolio.PortfolioSerializationBoundarySliceTest'
```

- [ ] Report every failed, skipped, retried and unexecuted run. A focused retry does not establish full-suite success or explain a timeout without controlled evidence.
- [ ] Compare the E2E TypeScript diagnostics before and after adding the spec. The previously observed TS1343 at `tests/e2e/global-setup-entrypoint.test.ts:23` may be reported as baseline only if the complete diagnostic is reproduced unchanged with the new spec removed; do not exempt it by filename alone.
- [ ] Update the B2 ledger and living master plan together only after the real integration proof exists. Mark 9.5 complete for its own scope while keeping Task 9.2's real post-save round trip, Wave 9 combined-stack/CI work, deployment and Production E2E open.
- [ ] Use durable wording such as “local assembled-stack evidence.” Do not write “open/unmerged” statements that become false when a documenting PR merges, and do not invent a future merge SHA.
- [ ] Run `git diff --check`, inspect the complete scoped diff, and stage only owned paths. Include the kickoff/handoff deliberately if they are carried onto the branch; preserve unrelated files.
- [ ] Run the status-guard unit tests and then the actual guard against the proposed committed base/head range and exact UTF-8 PR body file. Unit tests or a local draft body alone do not validate a published PR.

The PR body must contain exactly one plain, unbolded declaration at line start:

```text
Master-plan impact: updated — B2
```

Resolve the real base and head SHAs, then run from repository root:

```powershell
py -3 scripts/check_master_plan_status_propagation.py --base $baseSha --head $headSha --pr-body-file $bodyFile
```

Expected result: exit 0. After separately authorized publication, re-fetch the live PR body and actual base/head SHAs and run the guard again. Re-evaluate the declaration if the final scope changes.

## Review handoff to Codex

Lead with any concrete request to push and open a PR. State that approval covers only those actions unless the owner says otherwise. Provide the actual base/head SHAs, `git log --oneline origin/main..HEAD`, scoped paths, existing-working behavior versus demonstrated defects, RED/GREEN evidence where applicable, exact local-stack setup, real versus mocked results, full failed-run history and unresolved limits.

Report these dimensions separately: source integration, local real-stack proof, deterministic edge-state coverage, real post-save E2E, CI wiring, deployment and Production E2E. Cursor remains the implementation owner in its assigned worktree; Codex reviews read-only.
