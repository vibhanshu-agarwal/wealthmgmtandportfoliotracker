# Requirements Document

## Introduction

This feature replaces the placeholder "coming soon" stubs on the Overview (`/overview`) and Market Data (`/market-data`) dashboard pages with fully functional, session-gated client components. Both pages reuse existing data hooks (`usePortfolio`, `usePortfolioAnalytics`, `usePortfolioSummary`, `useAssetAllocation`, `usePortfolioPerformance`) and existing UI components (`SummaryCards`, `PerformanceChart`, `AllocationChart`, `Card`, `Table`, `Badge`, `Skeleton`) — no new API calls, hooks, or backend changes are introduced.

## Glossary

- **Overview_Page**: The client-rendered dashboard page at `/overview` that composes summary cards, performance chart, and allocation chart into a high-level portfolio snapshot
- **Market_Data_Page**: The client-rendered dashboard page at `/market-data` that displays a tabular view of per-holding market prices and 24-hour changes
- **OverviewPageContent**: The React client component (`frontend/src/components/overview/OverviewPageContent.tsx`) that implements the Overview_Page layout and session gate
- **MarketDataPageContent**: The React client component (`frontend/src/components/market/MarketDataPageContent.tsx`) that implements the Market_Data_Page layout and session gate
- **Session_Gate**: The authentication guard pattern using `useSession()` and `useRouter()` that redirects unauthenticated users to `/login` and shows a skeleton while the session is resolving
- **SummaryCards**: The existing `frontend/src/components/portfolio/SummaryCards.tsx` component rendering portfolio total, 24h P&L, and best performer cards
- **PerformanceChart**: The existing `frontend/src/components/charts/PerformanceChart.tsx` area chart component showing historical portfolio value
- **AllocationChart**: The existing `frontend/src/components/charts/AllocationChart.tsx` donut chart component showing asset-class allocation
- **HoldingsTable**: The existing `frontend/src/components/portfolio/HoldingsTable.tsx` component (must NOT appear on the Overview_Page)
- **AssetHoldingDTO**: The TypeScript interface representing a single portfolio holding, including `ticker`, `currentPrice`, `change24hPercent`, `change24hAbsolute`, and `lastUpdatedAt` fields
- **Skeleton**: The shadcn/ui `Skeleton` component used for loading placeholder UI
- **usePortfolio**: The existing TanStack Query hook returning `PortfolioResponseDTO` with `holdings: AssetHoldingDTO[]`
- **usePortfolioAnalytics**: The existing TanStack Query hook returning `PortfolioAnalyticsDTO` with `holdings: HoldingAnalyticsDTO[]`

## Requirements

### Requirement 1: Overview Page Session Gate

**User Story:** As an authenticated user, I want the Overview page to be protected behind a session check, so that only logged-in users can view portfolio summary data.

#### Acceptance Criteria

1. WHEN the session is still resolving, THE OverviewPageContent SHALL render a skeleton loading state that mirrors the Overview_Page layout (summary cards row, charts row, and navigation link placeholder)
2. WHEN the session resolves as unauthenticated, THE OverviewPageContent SHALL redirect the user to `/login` using client-side navigation
3. WHEN the session resolves as unauthenticated, THE OverviewPageContent SHALL render nothing after initiating the redirect
4. WHEN the session resolves as authenticated, THE OverviewPageContent SHALL render the portfolio summary components

### Requirement 2: Overview Page Layout Composition

**User Story:** As an authenticated user, I want the Overview page to display a high-level portfolio snapshot with summary cards, performance chart, and allocation chart, so that I can quickly assess my portfolio status without navigating to the full Portfolio page.

#### Acceptance Criteria

1. THE OverviewPageContent SHALL render SummaryCards in a top row using a responsive grid (single column on mobile, three columns on `sm` breakpoint and above)
2. THE OverviewPageContent SHALL render PerformanceChart and AllocationChart in a middle row using a responsive grid (single column on mobile, PerformanceChart spanning two columns and AllocationChart spanning one column on `lg` breakpoint and above)
3. THE OverviewPageContent SHALL NOT render HoldingsTable
4. THE OverviewPageContent SHALL render a "View Portfolio →" navigation element at the bottom that routes the user to `/portfolio`
5. WHEN the user activates the "View Portfolio →" navigation element, THE OverviewPageContent SHALL navigate the user to the `/portfolio` route using client-side routing

### Requirement 3: Overview Page Route Integration

**User Story:** As a user navigating to `/overview`, I want to see the full overview dashboard instead of a placeholder, so that the page is functional.

#### Acceptance Criteria

1. WHEN a user navigates to `/overview`, THE Overview_Page SHALL render the OverviewPageContent component instead of the "coming soon" placeholder text
2. THE Overview_Page SHALL display the heading "Overview" as a page title

### Requirement 4: Market Data Page Session Gate

**User Story:** As an authenticated user, I want the Market Data page to be protected behind a session check, so that only logged-in users can view market price information.

#### Acceptance Criteria

1. WHEN the session is still resolving, THE MarketDataPageContent SHALL render a skeleton loading state that mirrors the Market_Data_Page table layout (card with header skeleton and multiple row skeletons)
2. WHEN the session resolves as unauthenticated, THE MarketDataPageContent SHALL redirect the user to `/login` using client-side navigation
3. WHEN the session resolves as unauthenticated, THE MarketDataPageContent SHALL render nothing after initiating the redirect
4. WHEN the session resolves as authenticated, THE MarketDataPageContent SHALL render the market data table

### Requirement 5: Market Data Table Content

**User Story:** As an authenticated user, I want to see a table of current market prices and 24-hour changes for each asset in my portfolio, so that I can monitor market movements at a glance.

#### Acceptance Criteria

1. THE MarketDataPageContent SHALL display a table with columns for: Asset Ticker, Current Price, 24h Change (percentage and absolute), and Last Updated timestamp
2. THE MarketDataPageContent SHALL derive market data rows from the existing usePortfolio hook holdings list (each `AssetHoldingDTO` provides `ticker`, `currentPrice`, `change24hPercent`, `change24hAbsolute`, and `lastUpdatedAt`)
3. WHEN a holding has a positive `change24hPercent`, THE MarketDataPageContent SHALL display the 24h change value with a positive visual indicator (green color styling)
4. WHEN a holding has a negative `change24hPercent`, THE MarketDataPageContent SHALL display the 24h change value with a negative visual indicator (red color styling)
5. THE MarketDataPageContent SHALL render the table using existing Card and Table UI primitives from the shadcn/ui component library

### Requirement 6: Market Data Loading and Empty States

**User Story:** As a user, I want clear feedback when market data is loading or unavailable, so that I understand the current state of the page.

#### Acceptance Criteria

1. WHILE the usePortfolio hook is in a loading state, THE MarketDataPageContent SHALL display a skeleton loading state with placeholder rows inside a Card
2. WHEN the usePortfolio hook returns an empty holdings array, THE MarketDataPageContent SHALL display a "No market data" fallback message
3. IF the usePortfolio hook returns an error, THEN THE MarketDataPageContent SHALL display a user-friendly fallback message instead of crashing

### Requirement 7: Market Data Page Route Integration

**User Story:** As a user navigating to `/market-data`, I want to see the market data table instead of a placeholder, so that the page is functional.

#### Acceptance Criteria

1. WHEN a user navigates to `/market-data`, THE Market_Data_Page SHALL render the MarketDataPageContent component instead of the "coming soon" placeholder text
2. THE Market_Data_Page SHALL display the heading "Market Data" as a page title

### Requirement 8: Guardrails — No Modifications to Existing Files

**User Story:** As a developer, I want the implementation to avoid modifying existing hooks, API functions, and reusable components, so that the existing Portfolio page and its tests remain unaffected.

#### Acceptance Criteria

1. THE implementation SHALL NOT modify `usePortfolio.ts`, `useAuthenticatedUserId.ts`, `fetchWithAuth.ts`, or `apiService.ts`
2. THE implementation SHALL NOT modify `SummaryCards.tsx`, `PerformanceChart.tsx`, `AllocationChart.tsx`, or `HoldingsTable.tsx`
3. THE implementation SHALL preserve all existing `data-testid` attributes across the codebase
4. THE implementation SHALL NOT introduce new API calls or new TanStack Query hooks
