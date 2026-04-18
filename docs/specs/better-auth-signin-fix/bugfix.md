# Bugfix Requirements Document

## Introduction

The Better Auth sign-in endpoint (`POST /api/auth/sign-in/email`) returns HTTP 500 for all authentication requests. This breaks both browser-based sign-in and the Playwright E2E test global setup, which authenticates via the Better Auth API before running any tests.

The root cause is that the Better Auth database tables (`ba_user`, `ba_session`, `ba_account`, `ba_verification`) do not exist in PostgreSQL. When Better Auth queries `ba_user` during sign-in, PostgreSQL throws a "relation does not exist" error, which surfaces as a 500 response. The schema SQL file (`frontend/scripts/better-auth-schema.sql`) and dev user seed script (`frontend/scripts/seed-dev-user.ts`) already exist but are never executed during the Docker Compose startup or any automated pipeline. Flyway migrations in portfolio-service only create the `users`, `portfolios`, `asset_holdings`, and `market_prices` tables — no `ba_*` tables.

## Bug Analysis

### Current Behavior (Defect)

1.1 WHEN a user or E2E test sends a `POST /api/auth/sign-in/email` request THEN the system returns HTTP 500 because the `ba_user` table does not exist in PostgreSQL

1.2 WHEN a user or E2E test sends a `POST /api/auth/sign-up/email` request THEN the system returns HTTP 500 because the `ba_user` table does not exist in PostgreSQL

1.3 WHEN Docker Compose starts the `postgres` service THEN the system does not create the `ba_user`, `ba_session`, `ba_account`, or `ba_verification` tables, leaving Better Auth without its required schema

1.4 WHEN the Playwright global setup runs and calls the Better Auth sign-in API THEN the setup fails with a 500 error, preventing all E2E tests from executing

1.5 WHEN a developer runs `docker compose up` followed by `npm run dev` in the frontend THEN the system is not usable for authentication because the `ba_*` tables have not been created and no dev user has been seeded

### Expected Behavior (Correct)

2.1 WHEN a user or E2E test sends a `POST /api/auth/sign-in/email` request with valid credentials THEN the system SHALL authenticate the user and return HTTP 200 with session cookies

2.2 WHEN a user or E2E test sends a `POST /api/auth/sign-up/email` request with valid data THEN the system SHALL create the user account and return HTTP 200 with session details

2.3 WHEN Docker Compose starts the `postgres` service THEN the system SHALL automatically create the `ba_user`, `ba_session`, `ba_account`, and `ba_verification` tables (idempotently, using `CREATE TABLE IF NOT EXISTS`)

2.4 WHEN the Playwright global setup runs and calls the Better Auth sign-in API THEN the setup SHALL succeed, saving authentication state for dependent test projects

2.5 WHEN a developer runs `docker compose up` followed by `npm run dev` in the frontend THEN the system SHALL have the `ba_*` tables created and a dev user seeded, enabling authentication without manual SQL execution

### Unchanged Behavior (Regression Prevention)

3.1 WHEN Flyway runs migrations for portfolio-service THEN the system SHALL CONTINUE TO create and manage the `users`, `portfolios`, `asset_holdings`, and `market_prices` tables without interference from the `ba_*` schema

3.2 WHEN the `postgres` service restarts with existing data in the `postgres-data` volume THEN the system SHALL CONTINUE TO preserve all existing data in both Flyway-managed and `ba_*` tables (idempotent schema creation must not drop or truncate existing tables)

3.3 WHEN portfolio-service, market-data-service, insight-service, or api-gateway start THEN the system SHALL CONTINUE TO function normally with no changes to their startup behavior, health checks, or database interactions

3.4 WHEN the `ba_*` schema SQL is executed multiple times (e.g., on repeated `docker compose up` cycles) THEN the system SHALL CONTINUE TO be idempotent — no errors, no duplicate tables, no data loss

3.5 WHEN the dev user seed runs and the dev user already exists THEN the system SHALL CONTINUE TO handle the duplicate gracefully (skip or no-op) without errors
