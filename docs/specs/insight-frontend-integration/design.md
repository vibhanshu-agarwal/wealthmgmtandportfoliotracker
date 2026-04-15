# Design Document

## Overview

This design describes how the Next.js 16 frontend integrates with the insight-service backend to deliver a Market Summary grid and a conversational Chat interface on the AI Insights page. The architecture follows two distinct data-fetching strategies:

- **Market Summary polling** uses TanStack Query v5 (`useQuery`) with automatic refetch intervals — consistent with the existing portfolio hooks pattern.
- **Chat submission** uses a React 19 Server Action (`"use server"` module) invoked via `useActionState`, keeping the `POST /api/chat` call on the server and leveraging React 19's built-in pending state management.

The AI Insights page (`page.tsx`) remains a **Server Component** that performs server-side session checking and imports two `"use client"` leaf components: `MarketSummaryGrid` and `ChatInterface`.

## Architecture

### Component Hierarchy

```
(dashboard)/ai-insights/page.tsx          ← Server Component (session gate)
├── MarketSummaryGrid.tsx                 ← "use client" (TanStack Query)
│   └── MarketSummaryCard.tsx             ← presentational
│       └── Sparkline (Recharts)
└── ChatInterface.tsx                     ← "use client" (useActionState)
    └── ChatBubble.tsx                    ← presentational
```

### Data Flow

```
┌─────────────────────────────────────────────────────────────────┐
│  Server Component: page.tsx                                     │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ auth.api.getSession({ headers: await headers() })           ││
│  │ → unauthenticated? redirect("/login")                       ││
│  │ → authenticated? render children                            ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                 │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐│
│  │ MarketSummaryGrid    │  │ ChatInterface                    ││
│  │ "use client"         │  │ "use client"                     ││
│  │                      │  │                                  ││
│  │ useMarketSummary()   │  │ useActionState(sendChatMessage)  ││
│  │   ↓ TanStack Query   │  │   ↓ Server Action               ││
│  │ fetchMarketSummary() │  │ POST /api/chat (server-side)     ││
│  │   ↓ fetchWithAuth    │  │   ↓ fetchWithAuth (server)       ││
│  │ GET /api/insights/   │  │                                  ││
│  │   market-summary     │  │ useState([]) for ChatMessage[]   ││
│  └──────────────────────┘  └──────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

## Components

### 1. Type Definitions — `types/insights.ts`

Mirrors the insight-service Java DTOs. All numeric fields from the backend `BigDecimal` are typed as `number` on the frontend (JSON serialization produces numbers).

```typescript
export interface TickerSummary {
  ticker: string;
  latestPrice: number;
  priceHistory: number[];
  trendPercent: number | null;
  aiSummary: string | null;
}

export type MarketSummaryResponse = Record<string, TickerSummary>;

export interface ChatRequest {
  message: string;
  ticker?: string;
}

export interface ChatResponse {
  response: string;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: Date;
}
```

### 2. API Layer — `lib/api/insights.ts`

Client-side API functions for Market Summary (used by TanStack Query hooks). Uses the existing `fetchWithAuthClient` pattern.

```typescript
import { fetchWithAuthClient } from "@/lib/api/fetchWithAuth";
import type { MarketSummaryResponse, TickerSummary } from "@/types/insights";

export async function fetchMarketSummary(
  token: string,
): Promise<MarketSummaryResponse> {
  return fetchWithAuthClient<MarketSummaryResponse>(
    "/api/insights/market-summary",
    token,
  );
}

export async function fetchTickerSummary(
  ticker: string,
  token: string,
): Promise<TickerSummary> {
  return fetchWithAuthClient<TickerSummary>(
    `/api/insights/market-summary/${encodeURIComponent(ticker)}`,
    token,
  );
}
```

### 3. Server Action — `lib/api/insights-actions.ts`

A `"use server"` module for chat submission. Uses the server-side `fetchWithAuth` utility which reads the session from `headers()` automatically.

```typescript
"use server";

import { fetchWithAuth } from "@/lib/api/fetchWithAuth.server";
import type { ChatRequest, ChatResponse } from "@/types/insights";

export type ChatActionState = {
  response: string | null;
  error: string | null;
  status: number | null;
};

export async function sendChatMessage(
  _prevState: ChatActionState,
  formData: FormData,
): Promise<ChatActionState> {
  const message = formData.get("message") as string;
  const ticker = (formData.get("ticker") as string) || undefined;

  const body: ChatRequest = { message, ticker };

  try {
    const result = await fetchWithAuth<ChatResponse>("/api/chat", {
      method: "POST",
      body: JSON.stringify(body),
    });
    return { response: result.response, error: null, status: 200 };
  } catch (err) {
    const statusMatch = (err as Error).message.match(/\((\d+)\)/);
    const status = statusMatch ? parseInt(statusMatch[1], 10) : 500;
    if (status === 503) {
      return {
        response: null,
        error: "AI service is temporarily unavailable. Please try again later.",
        status: 503,
      };
    }
    return {
      response: null,
      error: "Something went wrong. Please try again.",
      status,
    };
  }
}
```

The Server Action follows the `useActionState` contract: it receives the previous state and `FormData`, and returns the next state. This keeps the HTTP call on the server (no client-side token management needed for chat).

### 4. TanStack Query Hooks — `lib/hooks/useInsights.ts`

Market Summary hooks follow the same pattern as `usePortfolio.ts`. No chat mutation hook — chat uses the Server Action instead.

```typescript
"use client";

import { useQuery } from "@tanstack/react-query";
import { fetchMarketSummary, fetchTickerSummary } from "@/lib/api/insights";
import { useAuthenticatedUserId } from "@/lib/hooks/useAuthenticatedUserId";

export const insightKeys = {
  marketSummary: (userId: string) =>
    ["insights", userId, "market-summary"] as const,
  tickerSummary: (userId: string, ticker: string) =>
    ["insights", userId, "ticker", ticker] as const,
};

export function useMarketSummary() {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: insightKeys.marketSummary(userId),
    queryFn: () => fetchMarketSummary(token),
    enabled: status === "authenticated" && !!token,
    staleTime: 30_000,
    refetchInterval: 60_000,
  });
}

export function useTickerSummary(ticker: string) {
  const { userId, token, status } = useAuthenticatedUserId();
  return useQuery({
    queryKey: insightKeys.tickerSummary(userId, ticker),
    queryFn: () => fetchTickerSummary(ticker, token),
    enabled: status === "authenticated" && !!token && ticker.length > 0,
    staleTime: 30_000,
  });
}
```

### 5. AI Insights Page — `app/(dashboard)/ai-insights/page.tsx`

Server Component that performs server-side session checking and composes the two client components.

```typescript
import { auth } from "@/lib/auth";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { MarketSummaryGrid } from "@/components/insights/MarketSummaryGrid";
import { ChatInterface } from "@/components/insights/ChatInterface";

export default async function AIInsightsPage() {
  const session = await auth.api.getSession({ headers: await headers() });
  if (!session?.user) {
    redirect("/login");
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold tracking-tight">AI Insights</h1>
      <MarketSummaryGrid />
      <ChatInterface />
    </div>
  );
}
```

### 6. MarketSummaryGrid — `components/insights/MarketSummaryGrid.tsx`

`"use client"` component that owns the TanStack Query data fetching and renders the card grid with loading/error/empty states.

```typescript
"use client";

import { useMarketSummary } from "@/lib/hooks/useInsights";
import { MarketSummaryCard } from "./MarketSummaryCard";
// Skeleton, error, and empty states rendered inline

export function MarketSummaryGrid() {
  const { data, isLoading, isError, refetch } = useMarketSummary();
  // Loading → skeleton cards
  // Error → error card with retry button (calls refetch)
  // Empty → "No market data available yet."
  // Data → responsive grid of MarketSummaryCard
}
```

### 7. MarketSummaryCard — `components/insights/MarketSummaryCard.tsx`

Presentational component receiving a `TickerSummary` as props. Renders ticker, price, trend indicator, sentiment badge, and sparkline.

- Trend color: green (positive), red (negative), neutral gray (null)
- Sentiment badge: shown when `aiSummary` is non-null; muted info icon with "Sentiment Unavailable" tooltip when null
- Sparkline: Recharts `LineChart` with no axes/labels, stroke color matching trend direction, hidden when `priceHistory.length < 2`

### 8. ChatInterface — `components/insights/ChatInterface.tsx`

`"use client"` component that manages the chat conversation using `useActionState` and local `useState` for message history.

```typescript
"use client";

import { useActionState, useState, useRef, useEffect } from "react";
import {
  sendChatMessage,
  type ChatActionState,
} from "@/lib/api/insights-actions";
import { ChatBubble } from "./ChatBubble";
import type { ChatMessage } from "@/types/insights";

const initialState: ChatActionState = {
  response: null,
  error: null,
  status: null,
};

export function ChatInterface() {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [state, formAction, isPending] = useActionState(
    sendChatMessage,
    initialState,
  );
  const scrollRef = useRef<HTMLDivElement>(null);

  // On state change (new response or error), append assistant/error bubble
  useEffect(() => {
    if (state.response) {
      // Append assistant message
    } else if (state.error) {
      // Append error message
    }
  }, [state]);

  // Auto-scroll on new messages
  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  // Form submission handler: append user bubble, then submit via formAction
  // Send button and input disabled while isPending
}
```

The `<form action={formAction}>` pattern with a hidden or named input for `message` integrates naturally with `useActionState`. The `isPending` boolean from `useActionState` drives the typing indicator and input disabling.

### 9. ChatBubble — `components/insights/ChatBubble.tsx`

Presentational component for a single message. Props: `ChatMessage`.

- User messages: right-aligned, primary background
- Assistant messages: left-aligned, muted background
- Relative timestamp below content (e.g., "2 minutes ago")

### 10. MSW Handlers — `test/msw/handlers.ts`

Extend the existing handlers array with insight-service endpoints:

```typescript
// Added to existing handlers array:
http.get("/api/insights/market-summary", ({ request }) => { /* fixture data */ }),
http.get("/api/insights/market-summary/:ticker", ({ request, params }) => { /* single ticker */ }),
http.post("/api/chat", async ({ request }) => { /* chat response */ }),
```

Error variant handler for 503:

```typescript
http.post("/api/chat", () =>
  HttpResponse.json(
    { error: "AI advisor unavailable", retryable: true },
    { status: 503 },
  ),
);
```

## Correctness Properties

### Property 1: MarketSummaryCard trend indicator correctness

- **Requirement:** 4.2, 4.3, 4.4
- **Type:** Example-based
- **Description:** Given a `TickerSummary` with positive `trendPercent`, the card renders a green upward arrow. Given negative `trendPercent`, it renders a red downward arrow. Given null `trendPercent`, it renders a neutral dash.
- **Tested via:** Three example renders with positive, negative, and null `trendPercent` values, asserting the correct indicator element and color class.

### Property 2: MarketSummaryCard sentiment badge visibility

- **Requirement:** 4.5, 4.6
- **Type:** Example-based
- **Description:** When `aiSummary` is non-null, the Sentiment_Badge is visible with the summary text. When null, the badge is hidden and a "Sentiment Unavailable" tooltip is present.
- **Tested via:** Two example renders toggling `aiSummary` between a string and null.

### Property 3: Sparkline rendering edge case

- **Requirement:** 10.1, 10.2
- **Type:** Edge-case
- **Description:** The sparkline chart renders only when `priceHistory` has 2 or more data points. With 0 or 1 points, no chart element is rendered.
- **Tested via:** Example renders with `priceHistory` arrays of length 0, 1, and 5.

### Property 4: Chat submission lifecycle via useActionState

- **Requirement:** 3.2, 3.3, 6.2, 6.3, 6.4, 6.9
- **Type:** Example-based
- **Description:** When a user submits a message: (1) a user bubble appears immediately, (2) the input and send button become disabled, (3) a typing indicator appears, (4) on success the typing indicator is replaced with an assistant bubble containing the response text.
- **Tested via:** Render ChatInterface, submit a message, assert the sequence of UI states.

### Property 5: Chat error handling — 503 vs generic

- **Requirement:** 3.4, 6.5, 6.6
- **Type:** Example-based
- **Description:** When the Server Action returns a 503 error, the error bubble shows "AI service is temporarily unavailable. Please try again later." For other errors, it shows "Something went wrong. Please try again."
- **Tested via:** Two example tests with mocked Server Action returning 503 and 500 error states.

### Property 6: MarketSummaryGrid loading/error/empty states

- **Requirement:** 5.2, 5.3, 5.4, 5.5
- **Type:** Example-based
- **Description:** The grid renders skeleton cards during loading, an error card with retry button on failure, and an empty message when the response is an empty map.
- **Tested via:** Three example renders with mocked `useMarketSummary` returning loading, error, and empty states.

### Property 7: Server-side session gating on AI Insights page

- **Requirement:** 8.3
- **Type:** Example-based
- **Description:** The `page.tsx` Server Component calls `auth.api.getSession()` with request headers and redirects to `/login` when no session exists. When a session exists, it renders the MarketSummaryGrid and ChatInterface components.
- **Tested via:** Example test verifying the page is not marked `"use client"` and uses the server-side auth pattern.

### Property 8: fetchMarketSummary returns correct structure

- **Requirement:** 2.1, 9.3
- **Type:** Example-based
- **Description:** When called with the MSW success handler active, `fetchMarketSummary` returns a `MarketSummaryResponse` where each value has the expected `TickerSummary` fields.
- **Tested via:** Unit test calling `fetchMarketSummary` with MSW handler and asserting response shape.

### Property 9: sendChatMessage Server Action error propagation

- **Requirement:** 3.4, 9.4
- **Type:** Example-based
- **Description:** When the `POST /api/chat` endpoint returns a 503 status, the Server Action returns a `ChatActionState` with `status: 503` and the appropriate error message.
- **Tested via:** Unit test invoking the Server Action with a mocked 503 response.

## File Structure

```
frontend/src/
├── types/insights.ts                              # TypeScript interfaces
├── lib/
│   ├── api/
│   │   ├── insights.ts                            # Client-side API functions (market summary)
│   │   └── insights-actions.ts                    # "use server" Server Action (chat)
│   └── hooks/
│       └── useInsights.ts                         # TanStack Query hooks (market summary only)
├── components/insights/
│   ├── MarketSummaryGrid.tsx                      # "use client" — grid with TanStack Query
│   ├── MarketSummaryCard.tsx                      # Presentational — single ticker card
│   ├── ChatInterface.tsx                          # "use client" — chat with useActionState
│   └── ChatBubble.tsx                             # Presentational — single message bubble
├── app/(dashboard)/ai-insights/
│   └── page.tsx                                   # Server Component — session gate + composition
└── test/msw/
    └── handlers.ts                                # Extended with insight-service handlers
```
