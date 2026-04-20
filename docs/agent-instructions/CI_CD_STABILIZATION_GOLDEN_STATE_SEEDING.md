# Requirements Specification: CI/CD Stabilization & "Golden State" Data Seeding

---

## 1. Executive Summary

The current CI/CD pipeline is **blocked** because E2E tests are failing against a cold-starting AWS environment with unpredictable data states. The previous approach of fixing these tests piecemeal (e.g., arbitrarily increasing timeouts) is masking deeper backend issues.

### Objective

1. **Temporarily disable** the failing E2E tests within the deployment workflow (`deploy.yml`) to unblock CI, preserving the testing infrastructure for a holistic repair later.
2. **Build a "Golden State" Data Seeding Engine** in Spring Boot to reliably wipe and populate a rich dataset (Stocks, Crypto, Forex) for a specific test user in Neon (PostgreSQL) and Atlas (MongoDB).
3. **Create an isolated, manually triggered Synthetic Monitoring suite** using Playwright to test the live AWS site sequentially, resetting the data state before execution.

---

## Phase 1: Pipeline Stabilization (CI/CD Infrastructure)

> **Assigned to:** Cursor / Kiro

### 1.1 Problem Statement

The `.github/workflows/deploy.yml` is failing because E2E tests run synchronously against a newly deployed AWS environment. We need to unblock deployments without destroying the existing Playwright infrastructure, allowing us to revisit and fix these tests holistically once the backend is stable.

### 1.2 Design & Implementation

**Quarantine Failing Tests** — Do not remove the Playwright installation or testing steps from `.github/workflows/deploy.yml`. Instead, selectively disable the failing tests.

**Implementation Strategy:**

- Update the Playwright test command in `deploy.yml` to exclude the flaky suites:
  ```bash
  npx playwright test --grep-invert "Live Contract|Chaos"
  ```
- *Alternatively*, append `.skip()` to the test blocks in the specific `*.spec.ts` files that are currently breaking the pipeline.

> [!IMPORTANT]
> **Goal:** The deployment pipeline should successfully build, push to ECR, update the Lambda Function Code, and publish the alias — with the remaining "safe" UI smoke tests passing quickly.

---

## Phase 2: The "Golden State" Data Seeder (Backend)

> **Assigned to:** Cursor / Kiro

### 2.1 Problem Statement

Demos and future E2E tests require a **predictable, rich dataset** to demonstrate AI capabilities (Bedrock). Relying on persistent SaaS databases (Neon/Atlas) means data gets dirty. We need an on-demand way to wipe a specific user's data and seed it with a highly diverse portfolio.

### 2.2 Design & Implementation

#### The Mechanism

Create a new secured REST endpoint in both `portfolio-service` and `market-data-service`:

```
POST /api/internal/seed
```

Protected by a static `INTERNAL_API_KEY` environment variable.

#### The Execution Flow

1. **Accepts** a `userId` parameter (e.g., `e2e-test-user@vibhanshu-ai-portfolio.dev`)
2. **Wipe** — Deletes all existing holdings, transactions, and cached insights for that specific `userId` in PostgreSQL and MongoDB
3. **Seed** — Inserts a pre-defined, rich dataset of holdings

#### The Dataset Complexity Requirement

To make the Bedrock AI insights intelligent and predictable, the seeder must insert a **diversified portfolio spanning multiple asset classes**.

##### Required Seed Data Dictionaries

| Asset Class | Exchange / Source | Count | Example Tickers |
|---|---|---|---|
| **US Equities** | Nasdaq / NYSE | Top 50 | `AAPL`, `MSFT`, `NVDA`, `GOOGL`, `AMZN`, `META`, `TSLA`, `BRK.B`, `LLY`, `AVGO`, … |
| **Indian Equities** | NSE | Top 50 | `RELIANCE.NS`, `TCS.NS`, `HDFCBANK.NS`, `ICICIBANK.NS`, `INFY.NS`, `SBIN.NS`, `BHARTIARTL.NS`, … |
| **Cryptocurrency** | — | Top 50 | `BTC-USD`, `ETH-USD`, `USDT-USD`, `BNB-USD`, `SOL-USD`, `XRP-USD`, `USDC-USD`, `ADA-USD`, … |
| **Forex** | — | Top 10 | `EURUSD=X`, `GBPUSD=X`, `USDJPY=X`, `USDCHF=X`, `AUDUSD=X`, `USDCAD=X`, … |

> [!NOTE]
> The AI agent should generate a static JSON or Java utility class (`SeedDataConstants.java`) containing these specific tickers to inject as realistic mock holdings, **randomizing quantities and purchase prices** for the test user.

---

## Phase 3: Live Synthetic Monitoring (Frontend / Playwright)

> **Assigned to:** Google AntiGravity

### 3.1 Problem Statement

We need to test the actual live AWS site (`vibhanshu-ai-portfolio.dev`) in **"Headed" mode** locally for debugging, and **headless** in CI. The tests must not run in parallel to avoid DDoS'ing the AWS Lambda 10-concurrency limit, and they must start from a clean data state.

### 3.2 Design & Implementation

#### The Workflow

Create a new GitHub Actions file:

```
.github/workflows/synthetic-monitoring.yml
```

Triggered only via `workflow_dispatch` (manual) and optionally `schedule` (cron).

#### The Playwright Config (`playwright.config.ts`)

- **Serial Execution** — MUST enforce `--workers=1` globally for the AWS project to respect Lambda concurrency limits.
- **AWS Timeouts** — Global timeouts and navigation timeouts must be extended to `45000` (45 seconds) to accommodate Lambda cold starts and Bedrock latency.

#### The Global Setup (`global-setup.ts`)

Before launching the browser, Playwright must execute a Node.js `fetch` request to the seeding endpoint (created in Phase 2):

```typescript
await fetch("https://api.vibhanshu-ai-portfolio.dev/api/internal/seed", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "X-Internal-Api-Key": process.env.INTERNAL_API_KEY!,
  },
  body: JSON.stringify({ userId: "e2e-test-user@vibhanshu-ai-portfolio.dev" }),
});
```

> [!TIP]
> This guarantees the UI tests always run against the exact same **"Golden State"** portfolio.

#### The Test Suite (Live Contract Verification)

1. **Login** as the test user
2. **Navigate to the Portfolio page** and verify that the diverse assets (Nasdaq, NSE, Crypto) render correctly in the tables/charts
3. **Navigate to the AI Insights page**, wait for the AI to analyze the 100+ seeded assets, and verify that the Bedrock integration returns a valid response without timing out

---

## Task Checklist

### Backend Tasks — Cursor / Kiro

| # | Task | Description |
|---|---|---|
| 1 | ✅ Quarantine failing tests | Identify the specific E2E tests failing in the AWS deployment pipeline. Apply `.skip()` to those test blocks (or update the CI script to `--grep-invert` them) so `deploy.yml` completes successfully, leaving the Playwright configuration intact. |
| 2 | ⬜ `SeedDataConstants.java` | Create the class containing the lists of Top 50 US, Top 50 NSE, Top 50 Crypto, and Top 10 Forex tickers. |
| 3 | ⬜ `DataSeederService.java` (Portfolio) | Implement in `portfolio-service` to wipe/insert PostgreSQL holdings for a given `userId`. |
| 4 | ⬜ `DataSeederService.java` (Market Data) | Implement in `market-data-service` to wipe/insert MongoDB cache/pricing data for a given `userId`. |
| 5 | ⬜ Seed endpoint routing | Expose a `POST /api/internal/seed` endpoint in `api-gateway` routing to the backend seeders, secured by an `INTERNAL_API_KEY` environment variable. |

### Frontend Tasks — Google AntiGravity

| # | Task | Description |
|---|---|---|
| 6 | ⬜ Synthetic monitoring workflow | Create `.github/workflows/synthetic-monitoring.yml` configured for `workflow_dispatch` and `ubuntu-latest`. |
| 7 | ⬜ Playwright AWS project config | Update `playwright.config.ts` to include an `aws-synthetic` project configured with `workers: 1`, `timeout: 60000`, and pointing to the live production URL. |
| 8 | ⬜ Global setup seed script | Write a `global-setup.ts` script that fires an HTTP POST to `https://api.vibhanshu-ai-portfolio.dev/api/internal/seed` with the proper authentication headers to reset the test user's data before the browser launches. |
| 9 | ⬜ E2E golden state assertions | Write the E2E script to log in, navigate to the dashboard, and explicitly assert that the UI successfully loads the massive diverse dataset (stocks, crypto, forex) without crashing the browser. |
| 10 | ⬜ Local headed-mode instructions | Provide instructions on how to run this specific suite locally in `--headed` mode so the developer can watch the automation interact with the live AWS site. |

> [!WARNING]
> **Task 1 is already complete** — the 3 failing tests (`dashboard-data`, `live-contract`, `mocked-chaos`) have been skipped via `test.skip()` and pushed to `architecture/fe-mock-headed-testing`.