# Implementation Plan: Insight Frontend Integration

## Overview

Integrate the insight-service backend with the Next.js 16 frontend by building TypeScript types, API/Server Action layers, TanStack Query hooks, presentational and client components, and the composed AI Insights page. Each task builds incrementally — types first, then data fetching, then UI components, then page composition, and finally MSW handlers and tests.

## Tasks

- [x] 1. Define TypeScript type interfaces
  - [x] 1.1 Create `frontend/src/types/insights.ts` with `TickerSummary`, `MarketSummaryResponse`, `ChatRequest`, `ChatResponse`, and `ChatMessage` interfaces
    - `TickerSummary`: `ticker` (string), `latestPrice` (number), `priceHistory` (number[]), `trendPercent` (number | null), `aiSummary` (string | null)
    - `MarketSummaryResponse`: `Record<string, TickerSummary>`
    - `ChatRequest`: `message` (string), `ticker?` (string)
    - `ChatResponse`: `response` (string)
    - `ChatMessage`: `id` (string), `role` ("user" | "assistant"), `content` (string), `timestamp` (Date)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

- [x] 2. Implement data fetching layer
  - [x] 2.1 Create `frontend/src/lib/api/insights.ts` with `fetchMarketSummary` and `fetchTickerSummary` functions
    - Use the existing `fetchWithAuthClient` pattern from `lib/api/fetchWithAuth.ts`
    - `fetchMarketSummary(token)` → `GET /api/insights/market-summary` → `MarketSummaryResponse`
    - `fetchTickerSummary(ticker, token)` → `GET /api/insights/market-summary/{ticker}` → `TickerSummary`
    - _Requirements: 2.1, 2.2_

  - [x] 2.2 Create `frontend/src/lib/api/insights-actions.ts` Server Action for chat
    - `"use server"` module using `fetchWithAuth` from `lib/api/fetchWithAuth.server.ts`
    - Export `ChatActionState` type: `{ response: string | null; error: string | null; status: number | null }`
    - Export `sendChatMessage(_prevState, formData)` following the `useActionState` contract
    - Return 503-specific error message vs generic error message based on status code
    - _Requirements: 3.1, 3.4_

  - [x] 2.3 Create `frontend/src/lib/hooks/useInsights.ts` with TanStack Query hooks for market summary
    - `"use client"` module following the `usePortfolio.ts` pattern
    - Export `insightKeys` object for centralized query key management
    - `useMarketSummary()`: `staleTime: 30_000`, `refetchInterval: 60_000`, enabled only when authenticated
    - `useTickerSummary(ticker)`: enabled only when authenticated and ticker is non-empty
    - _Requirements: 2.3, 2.4, 2.5_

- [x] 3. Checkpoint — Verify data layer compiles
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Build presentational components
  - [x] 4.1 Create `frontend/src/components/insights/ChatBubble.tsx`
    - Presentational component accepting a `ChatMessage` prop
    - User messages: right-aligned, primary background; Assistant messages: left-aligned, muted background
    - Display relative timestamp below message content
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 4.2 Create `frontend/src/components/insights/MarketSummaryCard.tsx`
    - Presentational component accepting a `TickerSummary` prop
    - Display ticker symbol, `latestPrice` as USD currency, `trendPercent` as signed percentage
    - Trend indicator: green upward arrow (positive), red downward arrow (negative), neutral dash (null)
    - Sentiment badge: visible with `aiSummary` text when non-null; muted info icon with "Sentiment Unavailable" tooltip when null
    - Recharts `LineChart` sparkline from `priceHistory` — no axes/labels, stroke color matches trend direction, hidden when `priceHistory.length < 2`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 10.1, 10.2, 10.3, 10.4_

  - [x] 4.3 Write unit tests for MarketSummaryCard
    - **Property 1: Trend indicator correctness** — positive → green up arrow, negative → red down arrow, null → neutral dash
    - **Property 2: Sentiment badge visibility** — non-null `aiSummary` → badge visible, null → "Sentiment Unavailable" tooltip
    - **Property 3: Sparkline rendering edge case** — hidden when `priceHistory.length < 2`, rendered when ≥ 2
    - **Validates: Requirements 4.2, 4.3, 4.4, 4.5, 4.6, 10.1, 10.2**

- [x] 5. Build client components
  - [x] 5.1 Create `frontend/src/components/insights/MarketSummaryGrid.tsx`
    - `"use client"` component using `useMarketSummary()` hook
    - Render responsive grid of `MarketSummaryCard` components, one per ticker
    - Loading state: skeleton placeholder cards matching card dimensions
    - Error state: error card with "Unable to load market data. Please try again later." and retry button calling `refetch`
    - Empty state: "No market data available yet." message
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 5.2 Write unit tests for MarketSummaryGrid loading/error/empty states
    - **Property 6: MarketSummaryGrid loading/error/empty states** — skeleton during loading, error card with retry on failure, empty message for empty map
    - **Validates: Requirements 5.2, 5.3, 5.4, 5.5**

  - [x] 5.3 Create `frontend/src/components/insights/ChatInterface.tsx`
    - `"use client"` component using `useActionState(sendChatMessage, initialState)`
    - `<form action={formAction}>` with named input for `message`
    - Local `useState<ChatMessage[]>` for conversation history
    - On form submit: append user `ChatBubble`, submit via `formAction`
    - `useEffect` on `state` changes: append assistant bubble on success, error bubble on failure
    - Typing indicator while `isPending` is true
    - Auto-scroll to latest message via `useRef` + `scrollIntoView`
    - Disable send button and input while `isPending` to prevent duplicate submissions
    - 503 error → "AI service is temporarily unavailable. Please try again later."
    - Other errors → "Something went wrong. Please try again." with retry link
    - _Requirements: 3.2, 3.3, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10_

  - [x] 5.4 Write unit tests for ChatInterface
    - **Property 4: Chat submission lifecycle** — user bubble appears, input disabled, typing indicator shown, assistant bubble on success
    - **Property 5: Chat error handling — 503 vs generic** — 503 → specific unavailable message, other → generic error message
    - **Validates: Requirements 3.2, 3.3, 3.4, 6.2, 6.3, 6.4, 6.5, 6.6, 6.9**

- [x] 6. Checkpoint — Verify components compile and render
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Compose the AI Insights page
  - [x] 7.1 Update `frontend/src/app/(dashboard)/ai-insights/page.tsx` to a Server Component with session gating
    - Convert to `async` Server Component
    - Call `auth.api.getSession({ headers: await headers() })` and redirect to `/login` if unauthenticated
    - Import and render `MarketSummaryGrid` (market grid section) above `ChatInterface` (chat panel section)
    - Page title: "AI Insights" with `text-2xl font-semibold tracking-tight`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 8. Add MSW test handlers and API-layer unit tests
  - [x] 8.1 Extend `frontend/src/test/msw/handlers.ts` with insight-service endpoint handlers
    - `GET /api/insights/market-summary` → realistic `MarketSummaryResponse` fixture with 2-3 tickers
    - `GET /api/insights/market-summary/:ticker` → single `TickerSummary` fixture
    - `POST /api/chat` → success handler returning `ChatResponse` fixture
    - 503 error variant handler for `POST /api/chat` returning `{ error: "AI advisor unavailable", retryable: true }`
    - _Requirements: 9.1, 9.2_

  - [x] 8.2 Write unit tests for `fetchMarketSummary` with MSW
    - **Property 8: fetchMarketSummary returns correct structure** — call with MSW success handler, assert `MarketSummaryResponse` shape with expected `TickerSummary` fields
    - **Validates: Requirements 2.1, 9.3**

  - [x] 8.3 Write unit tests for `sendChatMessage` Server Action error propagation
    - **Property 9: sendChatMessage Server Action error propagation** — invoke with mocked 503 response, assert `ChatActionState` has `status: 503` and correct error message
    - **Validates: Requirements 3.4, 9.4**

  - [x] 8.4 Write unit test for MarketSummaryCard null aiSummary tooltip
    - Render with `aiSummary: null`, assert "Sentiment Unavailable" tooltip is present and Sentiment_Badge is hidden
    - **Validates: Requirements 4.6, 9.5**

  - [x] 8.5 Write unit test for ChatInterface loading indicator
    - Render ChatInterface, submit a message, assert typing indicator appears while pending
    - **Validates: Requirements 6.3, 9.6**

- [x] 9. Final checkpoint — Ensure all tests pass
  - Run `npm run test` from `frontend/` and `npm run lint` to verify everything compiles and passes.
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- The design uses TypeScript throughout — all implementations follow existing project patterns (`usePortfolio.ts`, `portfolio.ts`, `fetchWithAuth.ts`)
- Chat uses Server Action + `useActionState` (not TanStack Query mutation) per the design
- Market Summary uses TanStack Query hooks per the existing portfolio hooks pattern
- Checkpoints ensure incremental validation at the data layer and component layer
