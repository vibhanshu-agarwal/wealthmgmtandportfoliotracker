# Wave 6 — Dashboard Data Accuracy: End-to-End Verification

**Date:** 2026-06-10  
**Branch:** `feature/wave6-dashboard-data-accuracy`  
**Spec:** `.kiro/specs/dashboard-data-accuracy/tasks.md` — Task 10 (all sub-tasks)

---

## Summary

Wave 6 completes the dashboard data accuracy spec with cross-service end-to-end verification. A backend integration test exercises the full pipeline from enriched `PriceUpdatedEvent` through projection and analytics reconciliation; a frontend Vitest suite validates the analytics → allocation contract layer.

---

## Changes

### `portfolio-service/src/test/java/com/wealth/portfolio/Wave6DashboardDataAccuracyIT.java` — Task 10.1

New `@Tag("integration")` Testcontainers Postgres test covering:

1. **Enriched event → projection** — `MarketPriceProjectionService` upserts `market_prices` and appends `market_price_history` for a distinct `(ticker, observedAt)`.
2. **Analytics change/P&L** — `PortfolioAnalyticsService` returns honest 24h change (`WITHIN_24H_WINDOW`), real cost-basis P&L, and canonical `displayAssetClass`.
3. **Reconciliation invariants** — `totalValue == Σ currentValueBase`; P&L identity when basis is present.
4. **Idempotent replay** — duplicate delivery of the same observation does not duplicate history rows.

**Requirements:** R1 AC4, R2 AC1, R3 AC1, Property 1, Property 2

---

### `frontend/src/lib/api/portfolio.wave6.e2e.test.ts` — Task 10.1

New Vitest + MSW end-to-end contract tests:

- `fetchPortfolioAnalytics` consumes backend change/P&L/coverage fields without coercion.
- Holdings sum to `totalValue` (Property 1).
- P&L identity when cost basis is present (Property 2).
- `buildAllocationDtoFromAnalytics` reconciles slice values and percentages to portfolio total (Requirement 1.4).
- Nullable change fields preserved as `null`, not coerced to `0` (Requirement 10.6).

**Requirements:** R1 AC4, R2 AC1, R3 AC1, R10 AC6

---

### `.kiro/specs/dashboard-data-accuracy/tasks.md`

Task 10 and all sub-tasks marked complete.

---

## Tests Run

| Suite | Tests | Result |
|---|---|---|
| `./gradlew check` (all backend) | all modules | ✅ BUILD SUCCESSFUL |
| `Wave6DashboardDataAccuracyIT` | 2 | ✅ pass |
| `npm run test` (frontend) | 165 (incl. 5 Wave 6) | ✅ pass |

---

## Non-changes (guardrails respected)

- No infrastructure changes (`min_replicas`, Terraform, KEDA, refresh cadence).
- No production code changes — verification-only wave (tests + spec/doc updates).
