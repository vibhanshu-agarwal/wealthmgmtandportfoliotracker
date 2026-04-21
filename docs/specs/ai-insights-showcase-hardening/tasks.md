# Implementation Plan: AI Insights Showcase Hardening

## Overview

This plan closes outstanding AI Insights reliability issues and packages the feature as interview-ready engineering work with traceability, tests, and operational clarity.

## Tasks

- [x] 1. Harden backend ticker resolution logic
  - [x] 1.1 Refactor `ChatController.resolveTicker()` to use ranked candidate extraction (`explicit` -> `$ticker` -> uppercase -> conversational tokens)
  - [x] 1.2 Add tracked-symbol validation step against `MarketDataService` before selecting candidate
  - [x] 1.3 Add ambiguity handling (ask for clarification when multiple unresolved candidates exist)
  - [x] 1.4 Preserve explicit `ticker` override behavior
  - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 2. Add backend regression tests for extraction and ambiguity
  - [x] 2.1 Add test: "Tell me about AAPL" resolves AAPL, not conversational verb
  - [x] 2.2 Add test: "$MSFT outlook" resolves MSFT
  - [x] 2.3 Add test: ambiguous multi-symbol prompt returns clarification response
  - [x] 2.4 Add test: explicit `ticker` field still takes precedence
  - _Requirements: 1.5, 4.1_

- [x] 3. Standardize chat failure semantics
  - [x] 3.1 Ensure unknown ticker/no data messages remain clear and stable
  - [x] 3.2 Ensure AI degradation path returns clear fallback response
  - [x] 3.3 Verify frontend maps 503 and generic errors consistently
  - _Requirements: 2.1, 2.2, 2.3_

- [x] 4. Complete frontend auth-state diagnostics for insights
  - [x] 4.1 Verify `useAuthenticatedUserId` emits `error` state with message for token exchange failures
  - [x] 4.2 Ensure `MarketSummaryGrid` has distinct renders for auth error vs unauthenticated vs data error vs empty
  - [x] 4.3 Ensure `ChatInterface` handles auth-unavailable state with actionable guidance
  - _Requirements: 3.1, 3.2, 3.3_

- [x] 5. Expand frontend regression tests
  - [x] 5.1 Add/verify tests for auth diagnostic states in `MarketSummaryGrid`
  - [x] 5.2 Add/verify tests for chat auth-unavailable and error mapping paths
  - [x] 5.3 Keep existing lifecycle tests green after hardening changes
  - _Requirements: 4.2, 4.3_

- [x] 6. Add observability for demo debugging
  - [x] 6.1 Add structured log events in chat flow for candidate extraction/resolution and outcome
  - [x] 6.2 Include non-sensitive diagnostic context (resolution source, status category)
  - [x] 6.3 Validate logs are readable and useful during local demo runs
  - _Requirements: 5.1, 5.2_

- [x] 7. Create demo-readiness checklist documentation
  - [x] 7.1 Add short runbook for required services and health checks
  - [x] 7.2 Add pre-demo smoke scenario list (happy path + degraded paths)
  - [x] 7.3 Add troubleshooting quick-reference (auth exchange, missing data, AI unavailable)
  - _Requirements: 5.3, 6.1, 6.2, 6.3_

- [x] 8. Verification and closure
  - [x] 8.1 Run targeted backend tests: `:insight-service:test --tests com.wealth.insight.ChatControllerTest`
  - [x] 8.2 Run targeted frontend tests for insights/auth hooks/components
  - [x] 8.3 Capture pass/fail evidence and map completion back to requirements
  - _Requirements: 4.4, 6.3_

## Execution Notes

- Prioritize Tasks 1-3 first to remove root-cause chat fragility.
- Keep each PR scoped and traceable to requirement IDs for interview storytelling.
- For demo confidence, do at least one full-stack manual smoke pass after automated tests.

## Verification Evidence

- Backend: `.\gradlew.bat :insight-service:test --tests com.wealth.insight.ChatControllerTest` passed.
- Frontend: `npm test -- src/components/insights/ChatInterface.test.tsx src/components/insights/MarketSummaryGrid.test.tsx src/lib/hooks/useAuthenticatedUserId.test.ts src/lib/api/insights.test.ts` passed.
