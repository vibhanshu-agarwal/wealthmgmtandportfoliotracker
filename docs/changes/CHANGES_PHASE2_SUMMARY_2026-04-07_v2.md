# Phase 2 Completion Summary (2026-04-07) - v2

This file supersedes `CHANGES_PHASE2_SUMMARY_2026-04-07_v1.md`.
It includes all items from v1 plus additional fixes and follow-up work completed afterward.

## Included from v1 (unchanged)
1. Step 1 infrastructure validation (`docker-compose.yml` already had PostgreSQL, MongoDB, Kafka KRaft, ports).
2. Step 2 polyglot persistence validation in `market-data-service` (Mongo model/repository/config already in place).
3. Step 3 Kafka event-driven implementation updates:
   - typed producer (`KafkaTemplate<String, PriceUpdatedEvent>`)
   - `@KafkaListener` consumer in portfolio service
   - async + idempotent PostgreSQL projection updates
4. Step 4 gateway completion:
   - route mappings validated
   - in-memory gateway rate limiting added
5. Step 5 frontend gateway proxy validation (`/api/:path*` -> `http://localhost:8080/api/:path*`).

## Additional work after v1

### A) Frontend API route regression fix (Portfolio UI load failures)
Issue observed:
- Portfolio page showed:
  - `Failed to load portfolio data. Please try again.`
  - `Failed to load performance data.`
  - `Failed to load allocation data.`
  - `Failed to load holdings data.`

Root cause:
- Frontend called `/api/v1/portfolios/{userId}` while gateway/backend exposes `/api/portfolio/{userId}`.

Fix applied:
- Updated frontend API path to `/api/portfolio/${userId}`.

File changed:
- `frontend/src/lib/api/portfolio.ts`

Validation:
- `npm test` passed
- `npm run build` passed

### B) Timezone hardening aligned with `main` branch
Issue observed:
- Environment-specific timezone mismatch historically caused PostgreSQL startup/connection failures.

Main-branch reference:
- `main` uses explicit JVM timezone settings with `Asia/Kolkata` for Gradle task execution.

Fix applied in this branch:
- Added JVM timezone enforcement (`-Duser.timezone=Asia/Kolkata`) for:
  - all `Test` tasks in subprojects
  - `bootRun` tasks in subprojects

File changed:
- `build.gradle`

### C) Local market data seeding (Mongo) aligned with current project flow
Goal:
- Ensure Market Data / Portfolio / Overview pages have meaningful data immediately in local runs.

Approach:
- Added startup seeder in `market-data-service`.
- Seeder is idempotent: seeds only when Mongo `market_prices` collection is empty.
- Seeder uses `MarketPriceService.updatePrice(...)` (not raw repo writes), so Kafka events are also published and downstream portfolio projection can update naturally.

Seeded symbols/prices:
- `AAPL` 212.5000
- `TSLA` 276.0000
- `BTC` 70775.0000
- `MSFT` 425.3000
- `NVDA` 938.6000
- `ETH` 3540.5000

Files changed:
- `market-data-service/src/main/java/com/wealth/market/LocalMarketDataSeeder.java` (new)
- `market-data-service/src/main/resources/application.yml` (`market.seed.enabled: true`)

Validation:
- `:market-data-service:compileJava` passed
- `:market-data-service:test` passed (no test sources)

### D) README test flow and runtime verification
Executed exactly as documented in `README.md`:
1. `./gradlew test` -> `BUILD SUCCESSFUL`
2. Frontend tests:
   - `npm install`
   - `npm test` -> `1 passed`
3. Frontend E2E:
   - `npx playwright install chromium`
   - `npm run test:e2e` -> `1 passed`

Runtime diagnostics performed:
- Repeated `bootRun` verification identified and documented local port 8080 conflicts when stale Java process already occupied gateway port.
- End-to-end check after restart confirmed:
  - infra up (Postgres/Mongo/Kafka/Redis)
  - services listening on `8080/8081/8082`
  - gateway API endpoints responding successfully for portfolio/summary.

## Files changed since v1
- `frontend/src/lib/api/portfolio.ts`
- `build.gradle`
- `market-data-service/src/main/java/com/wealth/market/LocalMarketDataSeeder.java` (new)
- `market-data-service/src/main/resources/application.yml`

## Notes
- `CHANGES_PHASE2_SUMMARY_2026-04-07_v1.md` remains as historical snapshot.
- This v2 is the cumulative summary requested (v1 + post-v1 updates).
