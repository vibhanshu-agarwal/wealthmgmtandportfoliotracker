# AI Insights Demo Readiness Runbook

## Purpose

Quick pre-demo checklist for the AI Insights flow so failures are caught before interview sessions.

## Required Services

- `api-gateway` running and healthy on `:8080`
- `insight-service` running and healthy on `:8083`
- `frontend` running (Next.js app)
- Redis reachable by `insight-service` (for market price state)
- Auth token exchange route operational: `GET /api/auth/jwt`

## Health Checks

1. Open frontend and confirm login succeeds.
2. Verify token exchange endpoint returns `200` with `token` and `userId`.
3. Verify market summary endpoint:
   - `GET /api/insights/market-summary`
4. Verify chat endpoint:
   - `POST /api/chat` with payload `{"message":"Tell me about AAPL"}`

## Pre-Demo Smoke Scenarios

1. **Happy path chat**
   - Prompt: `Tell me about AAPL`
   - Expect: assistant response references `AAPL` and current price/trend.

2. **Dollar-prefixed ticker**
   - Prompt: `$MSFT outlook`
   - Expect: resolves `MSFT` correctly.

3. **Ambiguous prompt**
   - Prompt: `Compare AAPL and MSFT`
   - Expect: clarification prompt asking user to specify ticker.

4. **Unknown ticker**
   - Prompt: `Tell me about ZZZZ`
   - Expect: "don't have any data" message (no crash).

5. **AI degraded mode**
   - Simulate AI unavailability.
   - Expect: response still includes market data + "AI analysis is temporarily unavailable."

6. **Auth exchange failure UX**
   - Simulate `/api/auth/jwt` returning `503`.
   - Expect: market summary auth diagnostic card and chat session-unavailable guidance.

## Troubleshooting Quick Reference

- **No market cards, auth diagnostic shown**
  - Check `AUTH_JWT_SECRET`, session cookies, `/api/auth/jwt` response code.

- **Chat returns no data for wrong ticker**
  - Check `insight-service` logs for `chat.ticker.candidates` and `chat.ticker.resolved`.

- **Chat generic error**
  - Check gateway route to `/api/chat/**` and upstream `insight-service` availability.

- **AI unavailable suffix**
  - Expected when AI advisor provider fails; market data portion should still render.

## Verification Commands

- Backend chat tests:
  - `.\gradlew.bat :insight-service:test --tests com.wealth.insight.ChatControllerTest`
- Frontend insights tests:
  - `npm test -- src/components/insights/ChatInterface.test.tsx src/components/insights/MarketSummaryGrid.test.tsx src/lib/hooks/useAuthenticatedUserId.test.ts src/lib/api/insights.test.ts`
