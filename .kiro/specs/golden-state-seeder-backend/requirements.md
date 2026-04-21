# Requirements Document

## Introduction

The Golden State Seeder Backend provides a deterministic data seeding system and CI/CD stabilization for the Wealth Management & Portfolio Tracker application. It consists of two phases: (1) quarantining failing E2E tests to unblock the CI pipeline immediately, and (2) exposing per-service seed endpoints behind the API Gateway so that an external orchestrator (Playwright global-setup.ts) can fan out calls and reset a test user's portfolio to a known "golden state" before E2E tests run against the live AWS deployment. The API Gateway acts as a dumb router — all fan-out orchestration is the caller's responsibility. This spec covers only the backend infrastructure — Playwright specs, synthetic monitoring workflows, and frontend changes are out of scope.

## Glossary

- **Seeder**: A backend component that deterministically inserts or upserts test data into a service's data store (Postgres, MongoDB, or Redis) for a given user
- **Golden_State**: A fully deterministic, reproducible dataset consisting of 1 portfolio, 160 asset holdings, and 160 market prices for the E2E test user
- **Seed_Tickers_JSON**: The canonical JSON file (`config/seed-tickers.json`) containing 160 ticker entries (50 US equities, 50 NSE, 50 crypto, 10 forex) with ticker, assetClass, quoteCurrency, and basePrice fields
- **Internal_Seed_Endpoint**: The `POST /api/internal/seed` REST endpoint exposed by each backend service, reachable via the API Gateway's pass-through routes
- **Portfolio_Service_Seeder**: The seeder component in portfolio-service that wipes and re-inserts portfolio data, upserts Postgres market prices, and returns the generated portfolioId in its response
- **Market_Data_Service_Seeder**: The seeder component in market-data-service that idempotently upserts MongoDB `AssetPrice` documents
- **Insight_Service_Seeder**: The seeder component in insight-service that accepts a portfolioId directly in the request body and evicts the `portfolio-analysis::<portfolioId>` Redis cache entry
- **E2E_Test_User**: The dedicated test user with id `00000000-0000-0000-0000-000000000e2e` and email `e2e-test-user@vibhanshu-ai-portfolio.dev`
- **Deterministic_Jitter**: A price calculation method that multiplies `basePrice` by a jitter factor derived from `hash(ticker + userId) modulo 5%`, producing identical prices across repeated runs with no randomness
- **CI_Pipeline**: The GitHub Actions workflow `frontend-e2e-integration.yml` that runs the full-stack E2E test suite
- **Quarantine**: The practice of skipping a failing test describe block using Playwright's `.skip()` to unblock the CI pipeline
- **Flyway_Migration**: A versioned SQL script executed by Flyway during portfolio-service startup to evolve the Postgres schema or seed data
- **Security_Bypass**: The configuration changes to `SecurityConfig`, `JwtAuthenticationFilter`, and `CloudFrontOriginVerifyFilter` that permit unauthenticated access to `/api/internal/**` paths (protected instead by `X-Internal-Api-Key`)
- **CopySeedTickers_Task**: A Gradle task (Groovy DSL) in each service's `build.gradle` that copies `config/seed-tickers.json` into `src/main/resources/seed/` before `processResources`

## Requirements

### Requirement 1: CI/CD Pipeline Quarantine

**User Story:** As a developer, I want the failing "Dashboard Data Integration Diagnostics" test suite quarantined in CI, so that the pipeline is immediately unblocked without deleting or modifying test logic.

#### Acceptance Criteria

1. WHEN the CI_Pipeline executes, THE CI_Pipeline SHALL skip the entire "Dashboard Data Integration Diagnostics" describe block in `dashboard-data.spec.ts` by applying Playwright's `.skip()` modifier to the `test.describe` call
2. WHEN the CI_Pipeline executes after quarantine, THE CI_Pipeline SHALL complete the Playwright step with a green (passing) status for the `auth-jwt-health.spec.ts` and `golden-path.spec.ts` suites
3. THE CI_Pipeline SHALL preserve all existing spec files, helpers, and Playwright configuration without deletion or structural modification beyond the `.skip()` addition
4. WHEN the `.skip()` modifier is applied, THE CI_Pipeline SHALL report the skipped tests as "skipped" in the Playwright reporter output rather than "failed"

### Requirement 2: Canonical Ticker Dictionary

**User Story:** As a developer, I want a single source-of-truth ticker dictionary shared across all backend services, so that seed data is consistent and maintainable.

#### Acceptance Criteria

1. THE Seed_Tickers_JSON SHALL contain exactly 160 ticker entries: 50 with assetClass `US_EQUITY`, 50 with assetClass `NSE`, 50 with assetClass `CRYPTO`, and 10 with assetClass `FOREX`
2. THE Seed_Tickers_JSON SHALL define each entry with the fields `ticker` (string), `assetClass` (string), `quoteCurrency` (string), and `basePrice` (number) where basePrice is a realistic market snapshot value
3. THE Seed_Tickers_JSON SHALL reside at `config/seed-tickers.json` at the repository root
4. WHEN the Gradle build executes for portfolio-service, market-data-service, or insight-service, THE CopySeedTickers_Task SHALL copy `config/seed-tickers.json` into the service's `src/main/resources/seed/` directory before the `processResources` phase
5. THE CopySeedTickers_Task SHALL be implemented in Groovy DSL (`.gradle` files), consistent with the existing build system

### Requirement 3: Portfolio Service Seeder

**User Story:** As a test orchestrator, I want the portfolio-service to wipe and re-seed a user's portfolio data on demand, so that E2E tests start from a known state.

#### Acceptance Criteria

1. WHEN the Portfolio_Service_Seeder receives a valid seed request for a userId, THE Portfolio_Service_Seeder SHALL delete all rows from `asset_holdings` where the `portfolio_id` belongs to the specified userId
2. WHEN the Portfolio_Service_Seeder has deleted asset holdings, THE Portfolio_Service_Seeder SHALL delete all rows from `portfolios` for the specified userId
3. WHEN the Portfolio_Service_Seeder has cleared existing data, THE Portfolio_Service_Seeder SHALL insert exactly 1 portfolio row and 160 asset holding rows for the specified userId using tickers from the Seed_Tickers_JSON
4. WHEN the Portfolio_Service_Seeder inserts asset holdings, THE Portfolio_Service_Seeder SHALL derive each holding's quantity deterministically from `hash(ticker) modulo 50 + 1`
5. WHEN the Portfolio_Service_Seeder completes holding insertion, THE Portfolio_Service_Seeder SHALL upsert 160 rows into the Postgres `market_prices` table using `ON CONFLICT (ticker) DO UPDATE`, setting `current_price` to the Deterministic_Jitter value and `quote_currency` from the Seed_Tickers_JSON entry
6. THE Portfolio_Service_Seeder SHALL execute all database operations within a single transaction so that a failure at any step rolls back the entire seed operation
7. WHEN the Portfolio_Service_Seeder is invoked twice consecutively for the same userId, THE Portfolio_Service_Seeder SHALL produce identical row counts and data values (idempotent)
8. IF the `X-Internal-Api-Key` header is missing or does not match the configured `INTERNAL_API_KEY`, THEN THE Portfolio_Service_Seeder SHALL reject the request with HTTP 403
9. WHEN the Portfolio_Service_Seeder completes successfully, THE Portfolio_Service_Seeder SHALL include the generated `portfolioId` in the JSON response body so that the caller can pass it directly to the insight-service seed endpoint without any cross-service resolution

### Requirement 4: Market Data Service Seeder

**User Story:** As a test orchestrator, I want the market-data-service to upsert market prices into MongoDB on demand, so that E2E tests have consistent pricing data.

#### Acceptance Criteria

1. WHEN the Market_Data_Service_Seeder receives a valid seed request, THE Market_Data_Service_Seeder SHALL upsert 160 `AssetPrice` documents in MongoDB using `MongoTemplate.upsert` keyed by `ticker` as the `@Id` field
2. WHEN the Market_Data_Service_Seeder upserts an AssetPrice document, THE Market_Data_Service_Seeder SHALL set `currentPrice` to the Deterministic_Jitter value and `updatedAt` to the current timestamp
3. THE Market_Data_Service_Seeder SHALL leave all existing AssetPrice documents for tickers not in the Seed_Tickers_JSON untouched (no wipe of the collection)
4. WHEN the Market_Data_Service_Seeder is invoked twice consecutively, THE Market_Data_Service_Seeder SHALL produce identical document contents for the 160 seeded tickers (idempotent)
5. IF the `X-Internal-Api-Key` header is missing or does not match the configured `INTERNAL_API_KEY`, THEN THE Market_Data_Service_Seeder SHALL reject the request with HTTP 403

### Requirement 5: Insight Service Seeder

**User Story:** As a test orchestrator, I want the insight-service to evict stale cached analysis for the test user, so that E2E tests trigger fresh AI-generated insights.

#### Acceptance Criteria

1. WHEN the Insight_Service_Seeder receives a valid seed request, THE request body SHALL contain a `portfolioId` field supplied directly by the caller (the caller obtains this from the portfolio-service seed response — no cross-service resolution is performed by insight-service)
2. WHEN the Insight_Service_Seeder receives a valid `portfolioId`, THE Insight_Service_Seeder SHALL evict the Redis cache entry keyed by `portfolio-analysis::<portfolioId>` from the `portfolio-analysis` cache
3. THE Insight_Service_Seeder SHALL leave the `sentiment` cache untouched because sentiment entries are per-ticker and globally shared across all users
4. WHEN the Insight_Service_Seeder completes eviction, THE Insight_Service_Seeder SHALL return the count of cache keys evicted in the response body
5. IF the `X-Internal-Api-Key` header is missing or does not match the configured `INTERNAL_API_KEY`, THEN THE Insight_Service_Seeder SHALL reject the request with HTTP 403

### Requirement 6: API Gateway Internal Routes

**User Story:** As a developer, I want the API Gateway to expose pass-through routes for each service's internal seed endpoint, so that the external orchestrator can reach each service through a single public hostname without the gateway performing any fan-out logic.

#### Acceptance Criteria

1. THE API Gateway SHALL configure Spring Cloud Gateway routes to proxy `/api/internal/portfolio/**` to portfolio-service, `/api/internal/market-data/**` to market-data-service, and `/api/internal/insight/**` to insight-service
2. THE API Gateway SHALL NOT implement any programmatic scatter-gather, WebClient fan-out, or response aggregation logic — all orchestration is the caller's responsibility
3. WHEN a request arrives at `/api/internal/**`, THE API Gateway SHALL forward the `X-Internal-Api-Key` header to the downstream service unchanged so that each service can perform its own key validation
4. THE routes for `/api/internal/**` SHALL be added to the existing Spring Cloud Gateway route configuration in `application.yml`, consistent with the existing route definitions for `/api/portfolio/**`, `/api/market/**`, and `/api/insights/**`

### Requirement 7: Security Bypass for Internal Endpoints

**User Story:** As a developer, I want `/api/internal/**` paths to bypass JWT authentication and CloudFront origin verification, so that the seed endpoint is accessible from CI runners and Playwright global setup without a user session.

#### Acceptance Criteria

1. THE SecurityConfig SHALL add `/api/internal/**` to the `permitAll()` path matchers so that Spring Security does not require a JWT for internal endpoints
2. WHEN a request path starts with `/api/internal/`, THE JwtAuthenticationFilter SHALL skip JWT principal extraction and header injection, following the same bypass pattern used for `/actuator` and `/api/auth/**` paths
3. WHEN a request path starts with `/api/internal/`, THE CloudFrontOriginVerifyFilter SHALL skip the `X-Origin-Verify` header check so that requests without the CloudFront secret header are not rejected
4. WHILE the Security_Bypass is active for `/api/internal/**`, EACH downstream service's seed endpoint SHALL still validate the `X-Internal-Api-Key` header as the sole authentication mechanism for internal requests
5. THE JwtAuthenticationFilter SHALL strip any caller-supplied `X-User-Id` header on `/api/internal/**` requests to prevent header spoofing

### Requirement 8: E2E Test User Flyway Migration

**User Story:** As a developer, I want a dedicated E2E test user provisioned via Flyway migration, so that the seeder and Playwright tests have a stable, pre-existing user account.

#### Acceptance Criteria

1. THE Flyway_Migration `V10__Seed_E2E_Test_User.sql` SHALL insert a `ba_user` row with id `00000000-0000-0000-0000-000000000e2e`, email `e2e-test-user@vibhanshu-ai-portfolio.dev`, and `emailVerified` set to TRUE
2. THE Flyway_Migration SHALL insert a `ba_account` row with `providerId` set to `credential` and `userId` referencing the E2E_Test_User's id, containing a scrypt-hashed password
3. THE Flyway_Migration SHALL use scrypt parameters N=16384, r=16, p=1, dkLen=64 for the password hash, matching the format used in V9 (`{salt_hex}:{derived_key_hex}`)
4. THE Flyway_Migration SHALL use `ON CONFLICT DO NOTHING` on all INSERT statements for idempotency so that re-running the migration on a database where the user already exists produces no errors
5. THE Flyway_Migration SHALL reside at `portfolio-service/src/main/resources/db/migration/V10__Seed_E2E_Test_User.sql`

### Requirement 9: Scrypt Hash Generation Helper

**User Story:** As a developer, I want a TypeScript helper script to regenerate the scrypt password hash, so that the E2E test user password can be rotated without manual hash computation.

#### Acceptance Criteria

1. THE generate-scrypt-hash helper SHALL reside at `frontend/scripts/generate-scrypt-hash.ts`
2. WHEN invoked with a password argument, THE generate-scrypt-hash helper SHALL output a scrypt hash in the format `{salt_hex}:{derived_key_hex}` using parameters N=16384, r=16, p=1, dkLen=64
3. THE generate-scrypt-hash helper SHALL generate a cryptographically random 16-byte salt for each invocation
4. THE generate-scrypt-hash helper SHALL include usage instructions in a header comment referencing the V10 Flyway migration

### Requirement 10: Terraform Environment Variable Configuration

**User Story:** As a DevOps engineer, I want the `INTERNAL_API_KEY` injected into all four Lambda functions via Terraform, so that the seed endpoint is secured in the AWS deployment.

#### Acceptance Criteria

1. THE Terraform configuration SHALL add `INTERNAL_API_KEY` to the environment variables block of the `wealth-api-gateway`, `wealth-portfolio-service`, `wealth-market-data-service`, and `wealth-insight-service` Lambda function resources in `infrastructure/terraform/modules/compute/main.tf`
2. THE Terraform configuration SHALL source the `INTERNAL_API_KEY` value from a new Terraform variable `var.internal_api_key`
3. THE Terraform configuration SHALL add the `internal_api_key` variable declaration to the module's `variables.tf` with a `sensitive = true` marker and a descriptive comment
4. IF `var.internal_api_key` is not provided, THEN THE Terraform configuration SHALL default to an empty string so that local development without the variable does not break `terraform plan`

### Requirement 11: Deterministic Pricing

**User Story:** As a test author, I want seeded market prices to be deterministic and reproducible, so that E2E test assertions can rely on exact values.

#### Acceptance Criteria

1. WHEN the Portfolio_Service_Seeder or Market_Data_Service_Seeder calculates a seeded price, THE Seeder SHALL compute it as `basePrice × (1 + jitter)` where jitter is derived from `hash(ticker + userId) modulo 5%`
2. WHEN the same ticker and userId combination is seeded on two separate invocations, THE Seeder SHALL produce identical price values with no randomness
3. THE Seeder SHALL use a stable hash function (such as Java's `String.hashCode()`) so that the jitter is consistent across JVM restarts

### Requirement 12: Backend Integration Tests

**User Story:** As a developer, I want comprehensive integration tests for all seeder components, so that regressions are caught before deployment.

#### Acceptance Criteria

1. THE portfolio-service test suite SHALL include a Testcontainers-based integration test that verifies the Portfolio_Service_Seeder inserts exactly 1 portfolio and 160 holdings, upserts 160 market prices, returns the portfolioId, and produces identical results on a second invocation
2. THE market-data-service test suite SHALL include a Testcontainers-based integration test that verifies the Market_Data_Service_Seeder upserts 160 AssetPrice documents and produces identical results on a second invocation
3. THE api-gateway test suite SHALL include a test that verifies `POST /api/internal/portfolio/seed` is reachable without a JWT but is rejected with HTTP 403 when the `X-Internal-Api-Key` header is missing, confirming the security bypass and key validation work correctly
4. THE insight-service test suite SHALL include a test that verifies the Insight_Service_Seeder evicts only the `portfolio-analysis` cache entry for the supplied portfolioId and does not touch the `sentiment` cache
