# Implementation Plan: UI Polish — Overview & Market Data Pages

## Overview

Replace the placeholder stubs on `/overview` and `/market-data` with session-gated client components. Both follow the established `PortfolioPageContent` pattern. No new hooks, API calls, or backend changes. The Overview page composes existing summary/chart components; the Market Data page derives a price table from `usePortfolio()` holdings.

## Tasks

- [x] 1. Create OverviewPageContent component
  - [x] 1.1 Create `frontend/src/components/overview/OverviewPageContent.tsx`
    - Add `"use client"` directive
    - Implement `OverviewPageSkeleton` internal component (3 summary card skeletons, 2+1 chart skeletons, link placeholder skeleton)
    - Implement session gate using `useSession()` + `useRouter()` + `useEffect` redirect to `/login`
    - Render skeleton when session is pending, `null` when unauthenticated
    - When authenticated, render:
      - Row 1: `<SummaryCards />` in `grid-cols-1 sm:grid-cols-3`
      - Row 2: `<PerformanceChart />` (col-span-2) + `<AllocationChart />` (col-span-1) in `grid-cols-1 lg:grid-cols-3`
      - Row 3: `<Link href="/portfolio">` "View Portfolio →" inside a Card
    - Do NOT render `HoldingsTable`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 1.2 Update `frontend/src/app/(dashboard)/overview/page.tsx`
    - Import and render `<OverviewPageContent />`
    - Keep "Overview" heading as page title
    - Remove placeholder "coming soon" text
    - _Requirements: 3.1, 3.2_

- [x] 2. Create MarketDataPageContent component
  - [x] 2.1 Create `frontend/src/components/market/MarketDataPageContent.tsx`
    - Add `"use client"` directive
    - Implement `MarketDataPageSkeleton` (session-pending skeleton: card with header + row placeholders)
    - Implement `MarketDataTableSkeleton` (data-loading skeleton: table rows inside a Card)
    - Implement session gate using `useSession()` + `useRouter()` + `useEffect` redirect to `/login`
    - When authenticated, call `usePortfolio()` and handle states:
      - Loading: render `MarketDataTableSkeleton`
      - Empty holdings: render "No market data available" fallback inside a Card
      - Error: render "Unable to load market data" fallback inside a Card
      - Data: render `Card` > `Table` with columns:
        - Ticker (`holding.ticker` in a Badge)
        - Current Price (`formatCurrency(holding.currentPrice)`)
        - 24h Change (`formatPercent(holding.change24hPercent)` + `formatSignedCurrency(holding.change24hAbsolute)`), green text for `>= 0`, red text for `< 0`
        - Last Updated (`formatDate(holding.lastUpdatedAt)`)
    - Use existing `Card`, `Table`, `Badge`, `Skeleton` UI primitives
    - Use `formatCurrency`, `formatPercent`, `formatSignedCurrency`, `formatDate` from `@/lib/utils/format`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2, 6.3_

  - [x] 2.2 Update `frontend/src/app/(dashboard)/market-data/page.tsx`
    - Import and render `<MarketDataPageContent />`
    - Keep "Market Data" heading as page title
    - Remove placeholder "coming soon" text
    - _Requirements: 7.1, 7.2_

- [x] 3. Checkpoint — Verify components render correctly
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Unit tests for OverviewPageContent
  - [x] 4.1 Write unit tests for OverviewPageContent in `frontend/src/components/overview/OverviewPageContent.test.tsx`
    - Mock `useSession` from `@/lib/auth-client` and `useRouter` from `next/navigation`
    - Test: renders skeleton when session is pending
    - Test: redirects to `/login` when unauthenticated
    - Test: renders `null` after redirect when unauthenticated
    - Test: renders SummaryCards, PerformanceChart, AllocationChart when authenticated
    - Test: does NOT render HoldingsTable
    - Test: renders "View Portfolio →" link with `href="/portfolio"`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 5. Unit tests for MarketDataPageContent
  - [x] 5.1 Write unit tests for MarketDataPageContent in `frontend/src/components/market/MarketDataPageContent.test.tsx`
    - Mock `useSession`, `useRouter`, and `usePortfolio`
    - Test: renders skeleton when session is pending
    - Test: redirects to `/login` when unauthenticated
    - Test: renders table skeleton when portfolio is loading
    - Test: renders "No market data" fallback when holdings array is empty
    - Test: renders error fallback when `usePortfolio` returns an error
    - Test: renders table with correct column headers (Ticker, Current Price, 24h Change, Last Updated)
    - Test: renders correct number of rows matching holdings count
    - Test: applies green styling for positive `change24hPercent`
    - Test: applies red styling for negative `change24hPercent`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2, 6.3_

- [x] 6. Property-based tests for market data table
  - [x] 6.1 Write property test for holdings-to-rows data integrity in `frontend/src/components/market/MarketDataPageContent.property.test.tsx`
    - **Property 1: Holdings-to-rows data integrity**
    - **Validates: Requirements 5.2**
    - Use `fast-check` to generate random arrays of `AssetHoldingDTO` objects (length 1–50, random tickers, prices, change values)
    - Mock `useSession` as authenticated and `usePortfolio` returning generated holdings
    - Assert: number of table body rows equals number of holdings
    - Assert: each row contains the holding's formatted ticker, price, and change values
    - Minimum 100 iterations

  - [x] 6.2 Write property test for change indicator color correctness in `frontend/src/components/market/MarketDataPageContent.property.test.tsx`
    - **Property 2: Change indicator color correctness**
    - **Validates: Requirements 5.3, 5.4**
    - Use `fast-check` to generate random `AssetHoldingDTO` objects with `change24hPercent` drawn from full numeric range (negative, zero, positive)
    - Assert: when `change24hPercent >= 0`, the change cell contains the profit color class (green indicator)
    - Assert: when `change24hPercent < 0`, the change cell contains the loss color class (red indicator)
    - Minimum 100 iterations

- [x] 7. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- No modifications to existing hooks (`usePortfolio.ts`, `useAuthenticatedUserId.ts`), API functions, or reusable components (`SummaryCards.tsx`, `PerformanceChart.tsx`, `AllocationChart.tsx`, `HoldingsTable.tsx`) per Requirement 8
- All existing `data-testid` attributes must be preserved
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific scenarios and edge cases
