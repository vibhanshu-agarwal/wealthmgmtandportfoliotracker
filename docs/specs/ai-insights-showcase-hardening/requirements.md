# Requirements Document

## Introduction

This spec defines the stabilization and product-hardening work for the AI Insights experience (`/ai-insights`) so it is reliable, explainable, and interview-ready as a portfolio showpiece. It addresses current pain points (chat failures, ticker extraction fragility, ambiguous empty states) and adds engineering-quality practices: deterministic behavior, observability, clear error UX, and traceable test coverage.

## Problem Statement

Current behavior shows multiple reliability gaps:

- Chat interaction previously failed due to frontend submission path issues.
- Ticker extraction is fragile and can misinterpret conversational words as symbols.
- Market Summary can appear empty without clearly indicating auth/token-exchange failures.
- End-to-end diagnostics are insufficient when user reports "not working."

## Goals

- Make chat responses reliable for realistic user phrasing.
- Make market-summary and chat failures explicit and actionable in the UI.
- Ensure backend and frontend contracts are tested with realistic scenarios.
- Establish production-style operational visibility suitable for demos/interviews.

## Non-Goals

- Building a full NLP pipeline for intent/entity extraction.
- Adding a new external market provider.
- Re-architecting gateway or auth services from scratch.

## Requirements

### Requirement 1: Robust Ticker Resolution in Chat

**User Story:** As a user, I want conversational prompts (for example "Tell me about AAPL") to resolve to the correct ticker so chat answers the intended question.

#### Acceptance Criteria

1. THE ChatController SHALL resolve ticker symbols using prioritized candidate strategies instead of stop words alone.
2. THE resolution strategy SHALL prefer candidates that are confirmed as tracked/known by market data.
3. WHEN multiple candidates exist and none can be validated, THE controller SHALL ask for clarification instead of selecting an arbitrary token.
4. THE explicit `ticker` request field SHALL remain highest priority over message extraction.
5. THE extraction logic SHALL be covered by tests for conversational phrases, punctuation, `$` prefixes, and ambiguous prompts.

### Requirement 2: Predictable Chat Failure Modes

**User Story:** As a user, I want clear and stable responses when data is unavailable or AI is degraded so I know what failed and what to do next.

#### Acceptance Criteria

1. THE chat endpoint SHALL return clear user-safe messages for:
   - unknown/untracked ticker,
   - no ticker identified,
   - AI temporarily unavailable.
2. THE frontend chat panel SHALL map HTTP failures (including 503) to specific user-facing guidance.
3. THE system SHALL avoid silent failures where request succeeds but no assistant message is rendered.

### Requirement 3: Market Summary Auth Diagnostics

**User Story:** As a user, I want to know whether missing data is caused by auth/session issues versus true lack of market data.

#### Acceptance Criteria

1. THE auth hook SHALL expose explicit states for `loading`, `authenticated`, `unauthenticated`, and `error`.
2. THE market summary UI SHALL render distinct states for:
   - auth exchange failure,
   - unauthenticated user,
   - request error,
   - empty data.
3. THE auth error state SHALL include a short technical hint (for example token exchange status) suitable for debugging demos.

### Requirement 4: Contract and Regression Test Coverage

**User Story:** As a maintainer, I want tests that lock in expected behavior so fixes do not regress during future changes.

#### Acceptance Criteria

1. THE insight-service test suite SHALL include regression tests for conversational ticker extraction cases.
2. THE frontend test suite SHALL include chat lifecycle/error-state tests and market-summary auth-diagnostic tests.
3. Cross-service contract assumptions for `POST /api/chat` and `GET /api/insights/market-summary` SHALL be codified in tests.
4. CI SHALL execute the affected frontend and insight-service test targets.

### Requirement 5: Observability and Debuggability for Demo Use

**User Story:** As a presenter, I want quick diagnosis when something breaks during an interview demo.

#### Acceptance Criteria

1. THE chat and insights flows SHALL emit structured logs with correlation-friendly context (route, ticker candidate, resolution source, upstream status).
2. THE UI error messages SHALL include concise, non-sensitive troubleshooting hints.
3. THE project docs SHALL define a "demo readiness" verification checklist (services, seed data, health endpoints, smoke flow).

### Requirement 6: Documentation Quality and Traceability

**User Story:** As an interviewer, I want to see professional engineering process with clear requirements, design rationale, and execution tasks.

#### Acceptance Criteria

1. THIS feature SHALL maintain `requirements.md`, `design.md`, and `tasks.md` in the spec folder.
2. Each implementation task SHALL map to one or more requirements.
3. Completed work SHALL be demonstrable with explicit test evidence.
