# Wave 5 — Dashboard Data Accuracy: Frontend Rendering & Wiring

**Date:** 2026-06-10  
**Branch:** `feature/wave5-dashboard-data-accuracy`  
**Spec:** `.kiro/specs/dashboard-data-accuracy/tasks.md` — Task 9 (all sub-tasks)

---

## Summary

Wave 5 completes the frontend half of the dashboard data accuracy spec. All mock/synthetic values are removed and replaced with real backend-sourced data. Every metric that can be genuinely unavailable now renders "—" rather than a misleading `$0.00` or `0.00%`.

---

## Changes

### `frontend/src/lib/utils/format.ts` — Task 9.1
Added four new nullable/unavailable helpers:
- `formatCurrencyOrDash(value)` — returns "—" for null/undefined, `$X.XX` otherwise
- `formatRelativeAge(isoString)` — "5 min ago" / "3 hr ago" / "2 days ago" / "—" for null
- `formatDateOrDash(isoString)` — returns "—" for null/undefined, formatted date otherwise
- These join the existing `formatSignedCurrencyOrDash` and `formatPercentOrDash`

**Requirement:** R8 AC3, R9 AC1, Property 2 (No silent zero)

---

### `frontend/src/lib/api/portfolio.ts` — Tasks 9.2, 9.3, 9.5
- **Batched `loadMarketPrices`** (exported): chunks ≤25 tickers, fetches all batches concurrently via `Promise.allSettled`, merges by ticker. Never drops holdings when N > 25.
- `BackendMarketPrice` type updated: `currentPrice: number | null`, `observedAt` preferred over `updatedAt`, `priceUnavailable` flag.
- `fetchPortfolio`: uses `observedAt` as `lastUpdatedAt`; falls back to `new Date(0).toISOString()` sentinel (not now()) for missing prices; uses backend portfolio `name` field (falls back to "My Portfolio" neutral label).
- **`buildAllocationDtoFromAnalytics`** (new): derives allocation from analytics holdings using `displayAssetClass` (canonical backend class) and `currentValueBase` (FX-converted). "OTHER" bucket for unknown classes. Percentages reconcile to `analytics.totalValue`.
- `buildAllocationDtoFromPortfolio` updated to include `OTHER` bucket color/label support.

**Requirements:** R1 AC1–3, R4 AC1–4, R5 AC1–2, R8 AC4, Property 1, Property 3

---

### `frontend/src/lib/hooks/usePortfolio.ts` — Task 9.5
- Added `useAssetAllocationFromAnalytics` hook: derives allocation from `GET /api/portfolio/analytics` using `buildAllocationDtoFromAnalytics`. Preferred over `useAssetAllocation` for canonical asset classes and FX-converted values.
- `useAssetAllocation` updated with clarifying comment pointing to the new preferred hook.

**Requirements:** R4 AC1, R4 AC2

---

### `frontend/src/components/charts/AllocationChart.tsx` — Task 9.5
- Switched from `useAssetAllocation` to `useAssetAllocationFromAnalytics`.
- Allocation now uses backend canonical asset class (including "OTHER") and FX-converted base-currency values.

**Requirements:** R4 AC1–4, Property 1

---

### `frontend/src/components/charts/PerformanceChart.tsx` — Task 9.6
- Never renders a synthetic series as real portfolio data (`synthetic: true` → unavailable state).
- Shows `AlertCircle` partial coverage indicator with "Partial (N/M holdings)" label when `performanceCoverage.partial === true`.
- Renders "No performance data available yet." when no non-synthetic data exists.

**Requirements:** R2 AC7, R8 AC3, Property 2

---

### `frontend/src/components/portfolio/SummaryCards.tsx` — Task 9.4
- **"24h Profit/Loss" card** now bound to analytics: aggregates `change24hAbsolute` across holdings where available. Renders "—" when no reference data exists — never the synthetic `+$0.00` placeholder from `fetchPortfolio`.
- **"All-time return"** unchanged — already used `totalUnrealizedPnLPercent` from analytics.
- Removed dependency on `formatSignedCurrency` (replaced with `formatSignedCurrencyOrDash`).
- Card border and gradient now only color-coded when real change data is available.

**Requirements:** R8 AC2, R8 AC3, Property 2

---

### `frontend/src/components/layout/PortfolioTicker.tsx` — Task 9.7
- `MOCK_TICKER` removed entirely.
- Wired to real data: prefers `usePortfolioAnalytics` holdings (top 8 by `currentValueBase`); falls back to `useMarketSummary` (insight service).
- Hides completely (`return null`) when no real data is available — never shows mock financial values.
- `change24hPercent: null` renders "—", not `+0.00%`.

**Requirements:** R8 AC1

---

### `frontend/src/components/market/MarketDataPageContent.tsx` — Task 9.8
- **"Last Updated"** column now uses `formatRelativeAge` ("5 min ago") with ISO tooltip.
- Sentinel timestamp `new Date(0).toISOString()` renders as "—" (never fabricates current time for missing prices).

**Requirements:** R8 AC4, R9 AC1

---

### `frontend/src/test/msw/handlers.ts` — Test infrastructure
- `GET /api/market/prices` handler updated to parse `?tickers=` param and return explicit price rows (with `priceUnavailable: true` for unknown tickers). Enables realistic batch tests.

---

## Tests Added (Task 9.9)

| File | Tests | What they cover |
|---|---|---|
| `src/lib/utils/format.test.ts` | 31 | All nullable helpers: null → "—", never $0.00/0.00%; `formatRelativeAge` relative/absolute; `formatDateOrDash` |
| `src/lib/api/portfolio.batching.test.ts` | 7 | >25-ticker batching (25/63/160 tickers); dedup; graceful degradation on batch failure; empty list |
| `src/lib/api/portfolio.allocation.test.ts` | 9 | `buildAllocationDtoFromAnalytics`: canonical class mapping, OTHER bucket, FX values, sum-to-100%, total reconciliation; all 7 display classes; fallback `buildAllocationDtoFromPortfolio` |
| `src/components/portfolio/SummaryCards.wave5.test.tsx` | 9 | 24h P&L from analytics; null → "—"; never +$0.00; all-time return; performers |
| `src/components/layout/PortfolioTicker.test.tsx` | 9 | Hidden when no data; real analytics tickers; real market summary tickers; null change → "—"; no old mock values |
| `src/components/charts/PerformanceChart.wave5.test.tsx` | 7 | Complete/partial/synthetic coverage; partial label with count; synthetic → unavailable state |

**Total new tests: 72** (all passing)  
**Total suite: 160 tests passing, 0 failing**

---

## Requirements Traceability

| Requirement | Task | Status |
|---|---|---|
| R1 AC1–3, 5 (complete price retrieval) | 9.2 | ✅ |
| R2 AC4, 6, 7 (24h change, performance labelling) | 9.3, 9.6 | ✅ |
| R3 AC1–3 (P&L, cost basis display) | 9.3, 9.4 | ✅ |
| R4 AC1–4 (canonical asset class, allocation) | 9.3, 9.5 | ✅ |
| R5 AC1–2 (FX-converted values) | 9.5 | ✅ |
| R8 AC1–5 (no mock/misleading values) | 9.4, 9.7, 9.8 | ✅ |
| R9 AC1 (freshness timestamps) | 9.8 | ✅ |
| R10 AC6 (typed unavailable) | 9.1 | ✅ |

---

## Correctness Properties Verified

- **Property 1 (Total reconciliation):** `buildAllocationDtoFromAnalytics` uses `currentValueBase` values that sum to `analytics.totalValue`; percentages reconcile to ~100%.
- **Property 2 (No silent zero):** All nullable metrics render "—" not `$0.00`/`0.00%` when null.
- **Property 3 (Completeness of pricing):** `loadMarketPrices` batches all tickers; no holdings dropped for N > 25.
- **Property 8 (Bounded staleness):** Not frontend-side — covered by Wave 4 `CacheConfig` azure profile.
