# Requirements Document

## Introduction

This feature integrates the completed insight-service backend with the Next.js 16 frontend. The frontend must consume two API Gateway endpoints — Market Summary (`GET /api/insights/market-summary`) and Chat (`POST /api/chat`) — and present them through a Market Summary dashboard card and a conversational Chat interface on the AI Insights page. Market Summary polling uses TanStack Query v5 hooks in `lib/hooks/`. Chat submission uses a React 19 Server Action (`"use server"` module in `lib/api/`) invoked via `useActionState`. The AI Insights page remains a Server Component that performs server-side session gating and imports separate `"use client"` components for the market grid and chat panel. All shared types live in `types/`, API functions in `lib/api/`, and domain components in `components/insights/`.

## Glossary

- **Dashboard**: The authenticated `(dashboard)` route group in the Next.js App Router
- **Market_Summary_Card**: A UI card component displaying per-ticker latest price, trend, price sparkline, and optional AI sentiment
- **Chat_Interface**: A conversational UI panel on the AI Insights page where users send natural-language queries and receive AI-enriched market responses
- **Chat_Bubble**: An individual message element within the Chat_Interface, styled differently for user and assistant messages
- **Ticker_Summary**: The `TickerSummary` JSON object returned by the insight-service containing `ticker`, `latestPrice`, `priceHistory`, `trendPercent`, and `aiSummary`
- **Chat_Request**: The JSON payload sent to `POST /api/chat` containing `message` (required) and `ticker` (optional)
- **Chat_Response**: The JSON object returned by `POST /api/chat` containing a `response` string
- **Sentiment_Badge**: A small inline badge on the Market_Summary_Card that displays the AI sentiment text from `aiSummary`
- **API_Client**: The authenticated fetch utility (`fetchWithAuthClient`) used for all frontend-to-gateway HTTP calls
- **Insight_API_Module**: The `lib/api/insights.ts` file containing all insight-service API call functions
- **Insight_Hooks_Module**: The `lib/hooks/useInsights.ts` file containing TanStack Query hooks for insight data
- **Insight_Actions_Module**: The `lib/api/insights-actions.ts` `"use server"` module containing the `sendChatMessage` Server Action for chat submission
- **MarketSummaryGrid**: A `"use client"` component (`components/insights/MarketSummaryGrid.tsx`) that renders the responsive grid of Market_Summary_Card components using TanStack Query data
- **Server_Action**: A React 19 `"use server"` function that executes on the server and can be invoked from client components via `useActionState`
- **MSW_Handler**: Mock Service Worker request handler used in Vitest unit tests to simulate backend responses

## Requirements

### Requirement 1: TypeScript Type Definitions

**User Story:** As a frontend developer, I want TypeScript interfaces that mirror the insight-service API contract, so that all data flowing between the API layer and UI components is type-safe.

#### Acceptance Criteria

1. THE Insight_Type_Module SHALL define a `TickerSummary` interface with fields: `ticker` (string), `latestPrice` (number), `priceHistory` (number array), `trendPercent` (number or null), and `aiSummary` (string or null).
2. THE Insight_Type_Module SHALL define a `MarketSummaryResponse` type as `Record<string, TickerSummary>` to represent the map returned by `GET /api/insights/market-summary`.
3. THE Insight_Type_Module SHALL define a `ChatRequest` interface with fields: `message` (string, required) and `ticker` (string or undefined, optional).
4. THE Insight_Type_Module SHALL define a `ChatResponse` interface with a `response` field (string).
5. THE Insight_Type_Module SHALL define a `ChatMessage` interface with fields: `id` (string), `role` ("user" or "assistant"), `content` (string), and `timestamp` (Date).
6. THE Insight_Type_Module SHALL be located at `frontend/src/types/insights.ts` following the project convention for shared types.

### Requirement 2: Market Summary Data Fetching

**User Story:** As an investor, I want the dashboard to display live market summaries from the insight-service, so that I can see current prices and trends for all tracked tickers.

#### Acceptance Criteria

1. THE Insight_API_Module SHALL export a `fetchMarketSummary` function that calls `GET /api/insights/market-summary` using the API_Client and returns a `MarketSummaryResponse`.
2. THE Insight_API_Module SHALL export a `fetchTickerSummary` function that calls `GET /api/insights/market-summary/{ticker}` using the API_Client and returns a single `TickerSummary`.
3. THE Insight_Hooks_Module SHALL export a `useMarketSummary` hook that wraps `fetchMarketSummary` in a TanStack Query `useQuery` call with a `staleTime` of 30 seconds and a `refetchInterval` of 60 seconds.
4. THE Insight_Hooks_Module SHALL export a `useTickerSummary` hook that accepts a `ticker` parameter and wraps `fetchTickerSummary` in a TanStack Query `useQuery` call, enabled only when the ticker is non-empty.
5. WHEN the authenticated session is unavailable, THE Insight_Hooks_Module SHALL disable all insight queries by setting `enabled` to false.

### Requirement 3: Chat Data Fetching

**User Story:** As an investor, I want to ask natural-language questions about market data, so that I can get AI-enriched conversational responses about specific tickers.

#### Acceptance Criteria

1. THE Insight_API_Module SHALL export a `sendChatMessage` Server Action defined in a `"use server"` module (`lib/api/insights-actions.ts`) that sends a `POST /api/chat` request with a `ChatRequest` body using the API_Client and returns a `ChatResponse`.
2. THE Chat_Interface SHALL invoke the `sendChatMessage` Server Action using React 19's `useActionState` hook to manage the submission lifecycle.
3. WHEN the Server Action is in-flight, THE `useActionState` hook SHALL expose a `pending` state that the Chat_Interface can use to render a loading indicator.
4. IF the `POST /api/chat` request returns a non-2xx status, THEN THE `sendChatMessage` Server Action SHALL return an error result so the Chat_Interface can display an appropriate message via the `useActionState` state.

### Requirement 4: Market Summary Card Component

**User Story:** As an investor, I want to see a card for each tracked ticker showing its latest price, trend direction, and AI sentiment, so that I can quickly assess market conditions.

#### Acceptance Criteria

1. THE Market_Summary_Card SHALL display the `ticker` symbol, `latestPrice` formatted as USD currency, and `trendPercent` formatted as a signed percentage.
2. WHEN `trendPercent` is positive, THE Market_Summary_Card SHALL render the trend value with a green color and an upward arrow indicator.
3. WHEN `trendPercent` is negative, THE Market_Summary_Card SHALL render the trend value with a red color and a downward arrow indicator.
4. WHEN `trendPercent` is null, THE Market_Summary_Card SHALL display a neutral dash indicator instead of a percentage.
5. WHEN `aiSummary` is non-null, THE Market_Summary_Card SHALL display a Sentiment_Badge containing the AI summary text.
6. WHEN `aiSummary` is null, THE Market_Summary_Card SHALL hide the Sentiment_Badge and display a tooltip reading "Sentiment Unavailable" on hover of a muted info icon.
7. THE Market_Summary_Card SHALL render a sparkline chart from the `priceHistory` array using Recharts.
8. THE Market_Summary_Card SHALL be located at `frontend/src/components/insights/MarketSummaryCard.tsx`.

### Requirement 5: Market Summary Dashboard Section

**User Story:** As an investor, I want the AI Insights page to show a grid of market summary cards for all tracked tickers, so that I have a consolidated view of the market.

#### Acceptance Criteria

1. THE AI_Insights_Page SHALL render a responsive grid of Market_Summary_Card components, one per ticker returned by `useMarketSummary`.
2. WHILE the `useMarketSummary` query is loading, THE AI_Insights_Page SHALL display skeleton placeholder cards matching the Market_Summary_Card dimensions.
3. IF the `useMarketSummary` query fails, THEN THE AI_Insights_Page SHALL display an error card with the message "Unable to load market data. Please try again later." and a retry button.
4. WHEN the retry button is clicked, THE AI_Insights_Page SHALL re-invoke the `useMarketSummary` query.
5. WHEN the `useMarketSummary` query returns an empty map, THE AI_Insights_Page SHALL display a message "No market data available yet."

### Requirement 6: Chat Interface Component

**User Story:** As an investor, I want a chat panel where I can type questions and receive AI-powered market insights in a conversational format, so that I can interact naturally with the system.

#### Acceptance Criteria

1. THE Chat_Interface SHALL render a scrollable message list and a text input with a send button at the bottom.
2. WHEN the user submits a message, THE Chat_Interface SHALL append a user Chat_Bubble to the message list and invoke the `sendChatMessage` Server Action via `useActionState`.
3. WHILE the Server Action is pending (as reported by `useActionState` pending state or `useFormStatus`), THE Chat_Interface SHALL display an animated typing indicator in an assistant Chat_Bubble placeholder.
4. WHEN the Server Action succeeds, THE Chat_Interface SHALL replace the typing indicator with an assistant Chat_Bubble containing the `response` text.
5. IF the Server Action returns an error result for a network error or non-2xx status, THEN THE Chat_Interface SHALL display an error Chat_Bubble with the message "Something went wrong. Please try again." and a retry link.
6. IF the Server Action returns an error result for a 503 status, THEN THE Chat_Interface SHALL display an error Chat_Bubble with the message "AI service is temporarily unavailable. Please try again later."
7. THE Chat_Interface SHALL maintain a local array of `ChatMessage` objects as conversation history using React `useState`.
8. THE Chat_Interface SHALL auto-scroll to the latest message when a new Chat_Bubble is appended.
9. THE Chat_Interface SHALL disable the send button and input field while the Server Action is pending (using `useFormStatus` or `useActionState` pending state) to prevent duplicate submissions.
10. THE Chat_Interface SHALL be located at `frontend/src/components/insights/ChatInterface.tsx`.

### Requirement 7: Chat Bubble Component

**User Story:** As an investor, I want chat messages to be visually distinct between my questions and the AI's responses, so that the conversation is easy to follow.

#### Acceptance Criteria

1. WHEN the `role` is "user", THE Chat_Bubble SHALL render the message aligned to the right with a primary-colored background.
2. WHEN the `role` is "assistant", THE Chat_Bubble SHALL render the message aligned to the left with a muted background.
3. THE Chat_Bubble SHALL display a relative timestamp below the message content.
4. THE Chat_Bubble SHALL be located at `frontend/src/components/insights/ChatBubble.tsx`.

### Requirement 8: AI Insights Page Composition

**User Story:** As an investor, I want the AI Insights page to combine the market summary grid and the chat interface in a cohesive layout, so that I can view market data and ask questions in one place.

#### Acceptance Criteria

1. THE AI_Insights_Page SHALL render the market summary grid section above the Chat_Interface section.
2. THE AI_Insights_Page SHALL use the existing `(dashboard)/ai-insights/page.tsx` route.
3. THE AI_Insights_Page SHALL be a Server Component that performs server-side session checking using the `auth` module and redirects unauthenticated users to `/login`.
4. THE AI_Insights_Page SHALL import the `MarketSummaryGrid` as a separate `"use client"` component that encapsulates the market summary grid with its TanStack Query data fetching.
5. THE AI_Insights_Page SHALL import the `ChatInterface` as a separate `"use client"` component that encapsulates the chat panel with its Server Action submission via `useActionState`.

### Requirement 9: MSW Test Handlers and Unit Tests

**User Story:** As a frontend developer, I want MSW handlers and unit tests for the insight API layer and components, so that the integration is verified without requiring a running backend.

#### Acceptance Criteria

1. THE MSW_Handler module SHALL define handlers for `GET /api/insights/market-summary`, `GET /api/insights/market-summary/:ticker`, and `POST /api/chat` that return realistic fixture data.
2. THE MSW_Handler module SHALL include an error handler variant for `POST /api/chat` that returns a 503 status with `{ "error": "AI advisor unavailable", "retryable": true }`.
3. WHEN the `fetchMarketSummary` function is called in a test with the MSW success handler active, THE test SHALL verify that the returned data matches the `MarketSummaryResponse` structure.
4. WHEN the `sendChatMessage` function is called in a test with the MSW 503 handler active, THE test SHALL verify that the function throws an error.
5. THE unit tests for Market_Summary_Card SHALL verify that a null `aiSummary` renders the "Sentiment Unavailable" tooltip instead of the Sentiment_Badge.
6. THE unit tests for Chat_Interface SHALL verify that a loading indicator appears while a chat mutation is pending.
7. THE unit tests SHALL use Vitest 3 and Testing Library following the project's existing test patterns.

### Requirement 10: Sparkline Chart for Price History

**User Story:** As an investor, I want to see a small inline chart of recent price movements on each market summary card, so that I can visually assess the trend at a glance.

#### Acceptance Criteria

1. THE Market_Summary_Card SHALL render a Recharts `LineChart` (sparkline variant, no axes or labels) using the `priceHistory` array as data points.
2. WHEN `priceHistory` contains fewer than 2 data points, THE Market_Summary_Card SHALL hide the sparkline and display no chart.
3. WHEN `trendPercent` is positive, THE sparkline line SHALL use a green stroke color.
4. WHEN `trendPercent` is negative or null, THE sparkline line SHALL use a red stroke color for negative and a neutral gray stroke color for null.
