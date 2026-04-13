# Phase 3 Changes — 2026-04-13 v2

## Insight-Service Frontend Integration

Wires the Next.js 16 frontend to the insight-service backend endpoints (`GET /api/insights/market-summary`, `GET /api/insights/market-summary/{ticker}`, `POST /api/chat`) with TypeScript types, TanStack Query hooks, a React 19 Server Action, presentational and client components, and full unit test coverage.

Prior backend changes: [CHANGES_PHASE3_SUMMARY_2026-04-13_v1.md](./CHANGES_PHASE3_SUMMARY_2026-04-13_v1.md)

---

## Summary

### 1. TypeScript Type Definitions

Created `frontend/src/types/insights.ts` mirroring the backend Java DTOs exactly:

- `TickerSummary` — `ticker`, `latestPrice`, `priceHistory` (number[]), `trendPercent` (number | null), `aiSummary` (string | null). Backend `BigDecimal` maps to `number` via Jackson serialization.
- `MarketSummaryResponse` — `Record<string, TickerSummary>` matching the backend `Map<String, TickerSummary>`.
- `ChatRequest` / `ChatResponse` — mirror `com.wealth.insight.dto.ChatRequest` and `ChatResponse` records.
- `ChatMessage` — client-side conversation history type (not a backend DTO).

### 2. Data Fetching Layer

Three files implementing a dual data-fetching strategy:

- `lib/api/insights.ts` — Client-side `fetchMarketSummary(token)` and `fetchTickerSummary(ticker, token)` using the existing `fetchWithAuthClient` pattern. Used by TanStack Query hooks.
- `lib/api/insights-actions.ts` — `"use server"` module with `sendChatMessage` Server Action following the `useActionState` contract (`_prevState, formData → ChatActionState`). Uses server-side `fetchWithAuth` from `fetchWithAuth.server.ts`. Returns structured error states distinguishing 503 ("AI service is temporarily unavailable") from generic failures. Validates empty messages client-side (400).
- `lib/hooks/useInsights.ts` — `"use client"` module with `useMarketSummary()` (30s stale, 60s poll) and `useTickerSummary(ticker)` hooks. Follows the `usePortfolio.ts` pattern with centralized `insightKeys` and auth gating via `useAuthenticatedUserId`.

### 3. Presentational Components

- `components/insights/ChatBubble.tsx` — Role-based alignment (user right/primary, assistant left/muted), relative timestamps ("just now", "2m ago"), `whitespace-pre-wrap` for multiline content.
- `components/insights/MarketSummaryCard.tsx` — Ticker symbol, USD-formatted price, signed trend percentage with color-coded `TrendingUp`/`TrendingDown`/`Minus` icons. Recharts `LineChart` sparkline (no axes, stroke color follows trend, hidden when < 2 data points). Conditional sentiment section: `Badge` when `aiSummary` is present, Radix `Tooltip` with "Sentiment Unavailable" when null.

### 4. Client Components

- `components/insights/MarketSummaryGrid.tsx` — `"use client"` component owning `useMarketSummary()`. Responsive grid layout (`sm:grid-cols-2 lg:grid-cols-3`). Skeleton loading cards, error card with retry button calling `refetch()`, empty state message.
- `components/insights/ChatInterface.tsx` — `"use client"` component using `useActionState` with a wrapped Server Action. Local `useState<ChatMessage[]>` for conversation history. Animated typing indicator (bouncing dots) while pending. Auto-scroll via `useRef` + `scrollIntoView`. Input and send button disabled during submission. 503-specific vs generic error messages rendered as assistant bubbles.

### 5. AI Insights Page Composition

Updated `app/(dashboard)/ai-insights/page.tsx` from a placeholder to an async Server Component:

- Server-side session gating via `auth.api.getSession({ headers: await headers() })` with redirect to `/login` if unauthenticated.
- Imports `MarketSummaryGrid` and `ChatInterface` as `"use client"` leaf components — page itself has no `"use client"` directive.
- Layout: page title + subtitle, market summary grid above chat panel.

### 6. MSW Test Handlers

Extended `test/msw/handlers.ts` with insight-service endpoints:

- `GET /api/insights/market-summary` — returns 3-ticker fixture (AAPL, MSFT, GOOG) with varied `trendPercent` and `aiSummary` values.
- `GET /api/insights/market-summary/:ticker` — returns single ticker or 404.
- `POST /api/chat` — success handler returning conversational response.
- Exported `chatError503Handler` for use with `server.use()` in error tests.

### 7. Unit Tests (28 new tests across 5 files)

- `MarketSummaryCard.test.tsx` (9 tests) — Trend indicator correctness (positive/negative/null), sentiment badge visibility (present/unavailable tooltip), sparkline edge cases (0/1/2+ data points), basic rendering.
- `MarketSummaryGrid.test.tsx` (5 tests) — Skeleton during loading, error card with retry, retry calls refetch, empty state message, data renders cards per ticker.
- `ChatInterface.test.tsx` (6 tests) — User bubble on submit, assistant response on success, empty state prompt, typing indicator appears while pending and disappears after resolution, 503-specific error, generic error.
- `insights.test.ts` (4 tests) — `fetchMarketSummary` returns correct `MarketSummaryResponse` shape, values match fixture, `fetchTickerSummary` returns single ticker, throws on unknown ticker (404).
- `insights-actions.test.ts` (4 tests) — Server Action returns success state on 200, 503 error state, generic error state, 400 for empty message.

---

## Architectural Decisions

- **Server Component page** — `page.tsx` stays a Server Component with server-side session gating. `MarketSummaryGrid` and `ChatInterface` are separate `"use client"` leaf components.
- **Dual data-fetching** — TanStack Query for market summary polling (consistent with existing portfolio hooks), React 19 Server Action + `useActionState` for chat submission (keeps POST on the server, no client-side token management).
- **No setState in useEffect** — ChatInterface wraps the Server Action to append response bubbles in the action callback, avoiding the `react-hooks/set-state-in-effect` lint rule.

---

## Files Changed

| File                                                          | Change                                                     |
| ------------------------------------------------------------- | ---------------------------------------------------------- |
| `frontend/src/types/insights.ts`                              | New — TypeScript interfaces mirroring backend DTOs         |
| `frontend/src/lib/api/insights.ts`                            | New — Client-side fetch functions for market summary       |
| `frontend/src/lib/api/insights-actions.ts`                    | New — `"use server"` Server Action for chat                |
| `frontend/src/lib/hooks/useInsights.ts`                       | New — TanStack Query hooks for market summary              |
| `frontend/src/components/insights/ChatBubble.tsx`             | New — Presentational chat message component                |
| `frontend/src/components/insights/MarketSummaryCard.tsx`      | New — Presentational ticker card with sparkline            |
| `frontend/src/components/insights/MarketSummaryGrid.tsx`      | New — Client component with TanStack Query grid            |
| `frontend/src/components/insights/ChatInterface.tsx`          | New — Client component with useActionState chat            |
| `frontend/src/app/(dashboard)/ai-insights/page.tsx`           | Rewritten — Server Component with session gating           |
| `frontend/src/test/msw/handlers.ts`                           | Extended — 3 insight-service endpoints + 503 error variant |
| `frontend/src/components/insights/MarketSummaryCard.test.tsx` | New — 9 unit tests                                         |
| `frontend/src/components/insights/MarketSummaryGrid.test.tsx` | New — 5 unit tests                                         |
| `frontend/src/components/insights/ChatInterface.test.tsx`     | New — 6 unit tests                                         |
| `frontend/src/lib/api/insights.test.ts`                       | New — 4 unit tests                                         |
| `frontend/src/lib/api/insights-actions.test.ts`               | New — 4 unit tests                                         |

---

## Verification

- `npx tsc --noEmit` → clean (0 errors)
- `npx eslint src/ --max-warnings 0` → clean on all new files
- `npx vitest --run` → 12 test files, 68 tests, 0 failures
