# Changes Summary - 2026-04-01 (v2)

## Objective
Complete end-to-end wiring so the frontend renders real backend data (not mock-only responses), and document the additional changes beyond v1.

## 1. Backend Wiring for UI Data

### 1.1 Added market price API endpoint
- Added `GET /api/market/prices` to expose current prices from `market_prices`.
- Supports optional `tickers` query parameter (comma-separated).
- Files:
  - `src/main/java/com/wealth/market/MarketPriceController.java`
  - `src/main/java/com/wealth/market/MarketPriceDto.java`

### 1.2 Improved portfolio summary valuation
- Updated portfolio summary total valuation to use:
  - `asset_holdings.quantity * market_prices.current_price`
- Implemented via SQL in `PortfolioService` using `JdbcTemplate`.
- File:
  - `src/main/java/com/wealth/portfolio/PortfolioService.java`

### 1.3 Seeded demo portfolio data for visible UI output
- Added migration to create demo holdings for `user-001`.
- This ensures the UI has actual holdings to display.
- File:
  - `src/main/resources/db/migration/V3__Seed_Portfolio_Data.sql`

## 2. Frontend Real API Integration

### 2.1 Replaced mock portfolio API implementation
- Reworked `frontend/src/lib/api/portfolio.ts` to fetch real data from backend:
  - Loads portfolios via `/api/v1/portfolios/{userId}`
  - Loads current prices via `/api/market/prices?tickers=...`
  - Builds holdings, weights, summary, allocation, and performance objects for UI components.
- File:
  - `frontend/src/lib/api/portfolio.ts`

### 2.2 Standardized hooks to user-based IDs
- Updated defaults from `p-001` to `user-001`.
- File:
  - `frontend/src/lib/hooks/usePortfolio.ts`

### 2.3 Updated performance chart usage
- Performance chart now requests data with `user-001` default.
- File:
  - `frontend/src/components/charts/PerformanceChart.tsx`

## 3. Test & Build Stability Updates

### 3.1 Updated service unit test for new constructor dependency
- Added `JdbcTemplate` mock in `PortfolioServiceTest`.
- File:
  - `src/test/java/com/wealth/portfolio/PortfolioServiceTest.java`

## 4. Validation Executed

### Backend
- Command:
```bash
./gradlew build --no-daemon
```
- Result: `BUILD SUCCESSFUL`

### Frontend
- Commands:
```bash
cd frontend
npm test
npx tsc --noEmit
```
- Result: all successful

## 5. Important Local Runtime Note
- If local DB was initialized before migration rename/changes, Flyway validation can still fail until DB history is repaired/reset.
- Fast dev reset path:
```bash
docker compose down -v
docker compose up -d
./gradlew bootRun --console=plain --no-daemon
```
- After reset, frontend should display seeded holdings and backend-driven portfolio totals.
