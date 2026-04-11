# UI Polish: Overview & Market Data Pages

**Status:** Ready to execute
**Priority:** Medium
**Date:** 2026-04-11

---

## Context

The architectural migration is complete and E2E tests are 10/10 green. The Overview (`/overview`) and Market Data (`/market-data`) pages are stubs rendering "coming soon" placeholders. All data hooks and chart components already exist on the Portfolio page.

## Guardrails

- Zero changes to TanStack Query hooks, `useAuthenticatedUserId`, BFF token exchange, or any API fetching logic
- Visuals only: JSX structure, Tailwind CSS, responsive design, conditional rendering based on existing data states
- Preserve all `data-testid` attributes (especially `data-testid="total-value"`)

---

## Plan

### Overview Page (`/overview`)

Create `frontend/src/components/overview/OverviewPageContent.tsx`:

- Client component with `useSession()` gate (same pattern as `PortfolioPageContent.tsx`)
- Reuses existing components: `SummaryCards` (top row), `PerformanceChart` + `AllocationChart` (middle row)
- No `HoldingsTable` — that stays on the Portfolio page as the detailed view
- Add a "View Portfolio →" link card at the bottom pointing to `/portfolio`
- No new hooks, no new API calls

Modify `frontend/src/app/(dashboard)/overview/page.tsx`:

- Replace stub with `<OverviewPageContent />`

### Market Data Page (`/market-data`)

Create `frontend/src/components/market/MarketDataPageContent.tsx`:

- Session-gated client component
- Reuses `usePortfolio()` to get holdings list (includes `currentPrice`, `change24hPercent`, `change24hAbsolute` from analytics merge)
- Renders a price ticker table: asset ticker, current price, 24h change, last updated
- Uses existing `Card`, `Table`, `Badge` UI primitives
- Skeleton while loading, "No market data" fallback when empty
- No new hooks — derives market data from existing `usePortfolio` + `usePortfolioAnalytics`

Modify `frontend/src/app/(dashboard)/market-data/page.tsx`:

- Replace stub with `<MarketDataPageContent />`

---

## Files to Create

| File                                                       | Purpose                          |
| ---------------------------------------------------------- | -------------------------------- |
| `frontend/src/components/overview/OverviewPageContent.tsx` | Session-gated overview dashboard |
| `frontend/src/components/market/MarketDataPageContent.tsx` | Session-gated market data table  |

## Files to Modify

| File                                                | Change                                        |
| --------------------------------------------------- | --------------------------------------------- |
| `frontend/src/app/(dashboard)/overview/page.tsx`    | Replace stub with `<OverviewPageContent />`   |
| `frontend/src/app/(dashboard)/market-data/page.tsx` | Replace stub with `<MarketDataPageContent />` |

## Zero Changes To

- `usePortfolio.ts`, `useAuthenticatedUserId.ts`, `fetchWithAuth.ts`, `apiService.ts`
- `SummaryCards.tsx`, `PerformanceChart.tsx`, `AllocationChart.tsx`, `HoldingsTable.tsx`
- Any `data-testid` attributes
