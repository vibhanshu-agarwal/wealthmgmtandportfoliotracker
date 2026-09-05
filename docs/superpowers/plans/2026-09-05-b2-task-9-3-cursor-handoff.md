# B2 Task 9.3 — Cursor implementation handoff

**Request:** authorize push / open PR when ready. No merge or deployment is authorized by this handoff.

## Baseline / head

| | |
|---|---|
| Baseline | `origin/main` at branch creation (`git merge-base origin/main HEAD`) |
| Branch | `feat/b2-task-9-3-drafted-price-integration` |
| Head / commits | `git rev-parse HEAD` and `git log --oneline origin/main..HEAD` (do not hardcode) |

## Demonstrated defect vs already-working behavior

**Already working (Task 1.10 path preserved):**
`BrowseStep → useDraftPrices → loadMarketPrices → apiPath('/market/prices')` already targeted the real URL, batched at 25, used plain `fetch` (no session clear on 401), and skipped empty drafts.

**Defect fixed:**
`BrowseStep` used `currentPrice` alone for estimates. An explicit `priceUnavailable: true` row with `currentPrice: 0` rendered `$0.00`. Aligned with `enrichWireHoldings` via `draftUnitPrice()` so unavailable markers never fabricate a displayable zero.

**Added coverage (not defect regressions):** draft sorting/query reuse, exchange-suffixed/FX ticker encoding, null/missing price rows, quantity-edit string retention, failed-batch editability, empty-draft no unfiltered price request.

## Scoped changed paths (expected)

- `frontend/src/components/asset-picker/BrowseStep.tsx`
- `frontend/src/components/asset-picker/BrowseStep.test.tsx`
- `frontend/src/lib/hooks/useDraftPrices.test.tsx`
- `frontend/src/lib/api/portfolio.batching.test.ts`
- `frontend/playwright.config.ts` (Chromium `testIgnore` for the local-only price spec)
- `frontend/playwright.draft-prices.real.config.ts`
- `frontend/tests/e2e/asset-picker-prices.integration.spec.ts`
- `.kiro/specs/asset-picker-composition/tasks.md`
- `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`
- `docs/superpowers/plans/2026-09-05-b2-task-9-3-cursor-kickoff.md`
- this handoff note

## Test commands / results

From `frontend/` (Vitest with `NEXT_PUBLIC_API_BASE_URL` unset; Docker Compose down for the full suite):

```text
npx vitest run src/lib/hooks/useDraftPrices.test.tsx src/lib/api/portfolio.batching.test.ts src/components/asset-picker/BrowseStep.test.tsx
→ 25 passed (clean env)

npx tsc --noEmit
→ exit 0

npx playwright test --config playwright.draft-prices.real.config.ts
→ 2 passed (Docker stack up + Golden-State/market-data seeded;
   NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 only for that command;
   picker-attributed three-ticker request + third-ticker estimate)

npm test (full suite) — NEXT_PUBLIC_API_BASE_URL cleared; docker compose down (no competing stack)
→ UNRESOLVED: 3 failed | 572 passed (575); 2 failed files | 61 passed (63)
  Failures (all 5s timeouts in pre-existing e2e helper unit tests, not Task 9.3 product files):
  - capture-suppression.test.ts › installGatewaySessionInitScript › logs in with the E2E credentials…
  - capture-suppression.test.ts › installGatewaySessionInitScript › resolves on a 200 login response
  - global-setup-seed-version.test.ts › global-setup frozen portfolio seed sequence › logs in, reads portfolio once…
  An earlier isolated retry of one of these files is not suite green. Full suite remains unresolved.
```

## Real browser evidence

- Ordinary E2E login; no internal key in browser requests.
- No `page.route` fulfillment for `/api/market/prices`.
- Browser portfolio GET narrowed to AAPL+BTC-USD for a known open-time draft (Portfolio page may also request that two-ticker set).
- Picker attribution: select a third ACTIVE catalog ticker inside Browse; assert the real price request is exactly that three-ticker set and assert that ticker's estimate **on its `data-ticker` row** after quantity `7`.
- Empty draft dispatched no blank-ticker price request.
- No composition `PUT` during the proof.
- Default `playwright.config.ts` Chromium project ignores this local-only spec.

Unavailable/failed-batch paths: Vitest+MSW only (called out in the integration spec header).

## Task 9.3 status

**Local/source price integration evidence is in place** (checkbox wording remains durable). Not deployed.
Remaining Wave 9 tasks and Production E2E stay open. This branch does not include Task 9.1
catalog/CORS changes; integrated catalog+prices browser proof remains a separate follow-up when
that work is on the same baseline.

**Publication caveat:** the full frontend `npm test` suite is still unresolved at 572/575 under a
clean env with no competing stack (see above). Do not treat Task 9.3 as merge-ready until that
suite result is closed or accepted by review.

## PR body declaration (when authorized)

```text
Master-plan impact: updated — B2
```
