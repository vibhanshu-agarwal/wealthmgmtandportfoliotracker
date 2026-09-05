# B2 Task 9.3 — Cursor implementation handoff

**Request:** authorize push / open PR when ready. No merge or deployment is authorized by this handoff.

## Baseline / head

| | SHA |
|---|---|
| Baseline (`origin/main` at branch creation) | `4f1a0428adc2d1cb4ce5d1b94deb7b55a8a1902f` |
| Head (local commit) | `6809562999cf00b10169eff990dbf58baf91a9ed` |
| Branch | `feat/b2-task-9-3-drafted-price-integration` |

Run `git log --oneline origin/main..HEAD` for the commit list (do not hardcode a count).

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
- `frontend/playwright.draft-prices.real.config.ts`
- `frontend/tests/e2e/asset-picker-prices.integration.spec.ts`
- `.kiro/specs/asset-picker-composition/tasks.md`
- `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`
- `docs/superpowers/plans/2026-09-05-b2-task-9-3-cursor-kickoff.md`
- this handoff note

## Test commands / results

From `frontend/` (with `NEXT_PUBLIC_API_BASE_URL` unset — a leftover absolute base from the
real-stack Playwright run sends Vitest fetches to the live gateway and bypasses MSW):

```text
npx vitest run src/lib/hooks/useDraftPrices.test.tsx src/lib/api/portfolio.batching.test.ts src/components/asset-picker
→ 177 passed (earlier scoped asset-picker run)

npx vitest run src/lib/hooks/useDraftPrices.test.tsx src/lib/api/portfolio.batching.test.ts src/components/asset-picker/BrowseStep.test.tsx
→ 25 passed (after clearing NEXT_PUBLIC_API_BASE_URL)

npx tsc --noEmit
→ exit 0

npx playwright test --config playwright.draft-prices.real.config.ts
→ 2 passed (real Docker stack; Golden-State + market-data seeded;
   NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 only for that command)

npm test (full suite) with NEXT_PUBLIC_API_BASE_URL still set to the local gateway:
→ 75 failed — MSW handlers never saw /api/* (absolute URL bypass). Not a product regression.
  Re-run of the Task 9.3 scoped files after unsetting the var: 25/25 passed.
```

## Real browser evidence

- Ordinary E2E login; no internal key in browser requests.
- No `page.route` fulfillment for `/api/market/prices`.
- Controlled draft set `AAPL,BTC-USD` (browser portfolio GET narrowed for attribution only).
- Captured successful real price response; quantity edited to `7`; estimate matched `formatCurrency(computeEstimatedValue(...))`.
- Empty draft dispatched no blank-ticker price request.
- No composition `PUT` during the proof.

Unavailable/failed-batch paths: Vitest+MSW only (called out in the integration spec header).

## Task 9.3 status

**Complete for local/source verification** (checkbox updated with durable wording). Not deployed. Remaining Wave 9 and Production E2E stay open. Task 9.1 (PR #228) was still open at implementation time; this branch did not copy its changes. Integrated catalog+prices browser proof can be re-run after 9.1 lands if desired.

## PR body declaration (when authorized)

```text
Master-plan impact: updated — B2
```
