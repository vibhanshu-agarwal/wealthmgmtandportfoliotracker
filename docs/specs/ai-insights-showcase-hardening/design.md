# Design Document

## Overview

This design hardens the AI Insights vertical slice across frontend and backend by replacing heuristic-only ticker extraction with validation-based resolution, strengthening UI state transparency, and adding observability and regression coverage.

The design principle is: **deterministic first, helpful second**.

- Deterministic symbol resolution over naive token matching.
- Explicit UI states over ambiguous "no data" rendering.
- Traceable logs/tests over ad-hoc debugging.

## Current Gaps and Planned Remedies

1. **Fragile ticker parsing**
   - Current: first 1-5 letter token not in stop words.
   - Remedy: candidate ranking + tracked-ticker validation + ambiguity handling.

2. **Opaque frontend state**
   - Current: users can see empty/error cards without knowing auth exchange failed.
   - Remedy: auth-aware state machine in hooks/components.

3. **Difficult incident diagnosis**
   - Current: sparse contextual logs.
   - Remedy: structured logs for resolution path and upstream outcomes.

## Architecture Changes

### 1) Chat Ticker Resolution Pipeline (`insight-service`)

`ChatController.resolveTicker()` will use a prioritized pipeline:

1. explicit `request.ticker` (normalized uppercase),
2. `$TICKER` candidates from regex,
3. uppercase symbol candidates,
4. conversational token candidates,
5. validate candidates against available market data (`latestPrice != null`),
6. if unresolved and ambiguous, return clarification prompt.

This keeps user experience robust even for phrases like:

- "Tell me about AAPL"
- "Can you check $MSFT?"
- "what about goog and amzn?" (clarify if ambiguous)

### 2) Frontend Insights State Model (`frontend`)

`useAuthenticatedUserId()` exposes:

- `loading`
- `authenticated`
- `unauthenticated`
- `error` (+ message)

`MarketSummaryGrid` and `ChatInterface` consume this model and render explicit, separate states for auth failure vs service failure.

### 3) Error Semantics

Define a stable user-facing error mapping:

- `401` -> sign in required
- `503` -> service temporarily unavailable
- other 5xx -> generic retry guidance
- unresolved/ambiguous ticker -> clarify ticker symbol

## Data and Control Flow

### Chat

1. User sends message from `ChatInterface`.
2. Frontend posts JSON to `/api/chat` with bearer token.
3. Gateway routes to `insight-service`.
4. Controller resolves ticker via pipeline.
5. Market data lookup + AI sentiment enrichment.
6. Response returned as single conversational payload.
7. Frontend appends assistant bubble or actionable error bubble.

### Market Summary

1. Frontend requests JWT from `/api/auth/jwt`.
2. Hook emits explicit auth state.
3. If authenticated, query `/api/insights/market-summary`.
4. Grid renders data, empty, request-error, or auth-diagnostic state.

## Observability Design

Add structured log events in `ChatController`:

- `chat.request.received`
- `chat.ticker.candidates` (source + values)
- `chat.ticker.resolved` (ticker + resolution source)
- `chat.response.outcome` (success, no-data, ai-degraded)

Constraints:

- No secrets/tokens in logs.
- Keep message text logging minimal or redacted.

## Testing Strategy

### Backend

- `ChatControllerTest` additions:
  - conversational verb regression,
  - `$`-prefixed symbol extraction,
  - ambiguous multi-symbol clarification behavior.

### Frontend

- `ChatInterface` tests:
  - lifecycle (optimistic + response),
  - 503 and generic failures,
  - auth unavailable path.
- `MarketSummaryGrid` tests:
  - auth error diagnostic,
  - unauthenticated hint,
  - normal data/error/empty paths.

### Smoke / Demo Test Plan

- Service health checks up.
- Seeded ticker exists.
- AI chat round-trip works with:
  - "Tell me about <ticker>"
  - "$<ticker>"
  - unknown ticker.

## Risks and Mitigations

1. **Risk:** Validation lookup adds extra market-data calls.
   - **Mitigation:** short-circuit on first valid candidate; cap candidate count.

2. **Risk:** Ambiguity logic may feel strict.
   - **Mitigation:** return friendly clarification prompt listing candidate symbols.

3. **Risk:** UI shows too much technical detail.
   - **Mitigation:** concise user message + optional compact diagnostic suffix.

## Rollout Plan

1. Implement backend resolution pipeline and tests.
2. Wire frontend state diagnostics and chat error mapping.
3. Run targeted frontend + backend suites.
4. Execute demo readiness checklist and document outcomes.
