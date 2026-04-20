# Phase 3 Stabilization — 2026-04-20

**Previous revision:** [CHANGES_PHASE3_INFRA_SUMMARY_19042026.md](./CHANGES_PHASE3_INFRA_SUMMARY_19042026.md) — Live AWS investigation, Lambda extension crashes, CA cert fixes, and Terraform alignment.

---

## Summary

This document covers the stabilization of the CI/CD pipeline and E2E test resilience following the successful deployment of the Spring Boot 4 stack to AWS. The focus was on resolving persistent 502/503 errors caused by build-time caching and synchronization race conditions in the Playwright suite.

---

## 1. Deployment Pipeline — Docker Cache Hardening

### 1.1 Symptom
Despite merging the CA certs fix into all Dockerfiles, the deployment pipeline continued to push broken images because `docker buildx` was reusing stale cached layers from failed previous runs.

### 1.2 Fix
Updated `.github/workflows/deploy.yml` to force clean builds for all microservices.
- Added the `--no-cache` flag to all `docker buildx build` commands.
- This ensures that infrastructure-critical changes (like the `cacerts` injection) are correctly incorporated into the final image layers.

---

## 2. Frontend Resilience — Hydration Crash Mitigation

### 2.1 Symptom
The `ai-insights` page would occasionally render a white screen (React unmount) when the backend returned partial or malformed data. This caused Playwright tests to timeout while searching for the chat input.

### 2.2 Fixes
- **MarketSummaryCard**: Implemented safe optional chaining for `priceHistory` and `trendPercent`.
- **MarketSummaryGrid**: Added a filter to the `Object.values(data)` map to remove `null` or invalid entries before rendering the component tree.
- **Stable Locators**: Replaced brittle regex-based placeholder locators with explicit `data-testid="chat-input"` to ensure deterministic test synchronization.

---

## 3. E2E Pipeline — Synthetic Monitoring Stabilization

### 3.1 Auth-Gate Race Condition
The `aws-synthetic` tests were racing against Next.js redirects. Navigating to `/` caused a redirect to `/overview`, then a client-side redirect to `/login`. Playwright would often check for login elements before the final redirect settled.
- **Fix**: Updated `aws-synthetic.spec.ts` to navigate **directly to `/login`**.

### 3.2 Synchronization & Timeouts
Increased the tolerance for AWS infrastructure latency (Lambda cold starts and Bedrock initialization).
- **Timeout Bump**: Increased synchronization timeouts for `chat-input` visibility from 15s to **30s** across all spec files.
- **Global Config**: Verified `playwright.config.ts` project timeouts for `aws-synthetic` are set to **120s**.

---

## 4. Configuration & Cleanup

- **application-prod.yml**: Resolved a duplicate `spring:` block that was causing the API Gateway to reject configurations on startup.
- **Terraform**: Corrected the placement of `origin_read_timeout` within the `custom_origin_config` block (moved from the cache behavior block).
- **Global Setup**: Reverted the temporary `SKIP_BACKEND_HEALTH_CHECK` bypass used during local debugging to ensure the pipeline remains contract-locked to backend health.

---

## 5. Golden State Seeder — Backend Infrastructure

> **Branch:** `feature/golden-state-seeder-backend` | **Commit:** `4a8ce45`
> **49 files changed, 3317 insertions**

The root cause of persistent E2E flakiness was the absence of a deterministic, pre-test data reset. Tests were running against whatever state was left by the previous run, causing order-dependent failures in CI. This phase implements the "Golden State Seeder" — a backend-only data-reset engine invoked by Playwright before each test suite.

### 5.1 Architecture

The seeder is a **decoupled, internal-only HTTP endpoint** on each microservice, guarded by a shared `X-Internal-Api-Key` secret. The external orchestrator (Playwright `global-setup.ts`) holds the key and calls each endpoint in sequence. No database credentials are exposed to the test runner.

```
Playwright global-setup.ts
  └── POST /internal/seed  (X-Internal-Api-Key: <secret>)
        ├── portfolio-service   → DELETE + INSERT (160 holdings, Postgres)
        ├── market-data-service → DROP + INSERT (160 price documents, MongoDB)
        └── insight-service     → FLUSHDB + repopulate (Redis)
```

**Deterministic pricing:** Both `portfolio-service` and `market-data-service` derive quantities and prices from `ticker.hashCode()` and `(ticker + ":" + userId).hashCode()` respectively — no shared database, yet both services produce identical values for the same input.

### 5.2 Security Model

- `InternalApiKeyFilter` implemented in all three microservices.
- Uses `MessageDigest.isEqual` for **constant-time comparison** — immune to timing attacks.
- If `INTERNAL_API_KEY` env var is blank/missing at startup, every request **fails-closed with HTTP 503**. The key's absence is the safe state.
- The `api-gateway` whitelists `/internal/**` paths from JWT verification and CloudFront origin checks, but does **not** expose these paths to the public CDN. They are only reachable from within the VPC / Playwright CI runner.

### 5.3 Files Delivered

**portfolio-service**

| File | Purpose |
|---|---|
| `seed/DeterministicPriceCalculator.java` | Hash-based price/quantity derivation |
| `seed/InternalApiKeyFilter.java` | Constant-time key guard |
| `seed/PortfolioSeedService.java` | Transactional delete + 160-holding insert |
| `seed/PortfolioSeedController.java` | `POST /internal/seed` |
| `seed/SeedTickerRegistry.java` | Loads 160-ticker list from classpath |
| `db/migration/V10__Seed_E2E_Test_User.sql` | Seeds E2E user UUID into `users` table |

**market-data-service** — mirrors the same pattern (`DeterministicPriceCalculator`, `InternalApiKeyFilter`, `MarketDataSeedService`, `MarketDataSeedController`, `SeedTickerRegistry`).

**insight-service** — `InternalApiKeyFilter`, `InsightSeedService` (Redis `FLUSHDB` + repopulate), `InsightSeedController`.

**api-gateway** — `SecurityConfig` bypass for `/internal/**`; `JwtAuthenticationFilter` and `CloudFrontOriginVerifyFilter` updated to pass the key header through.

**Terraform** — `INTERNAL_API_KEY` wired as a secret variable through `variables.tf` → `modules/compute/variables.tf` → ECS task definition environment.

### 5.4 Test Results

| Suite | Tests | Result |
|---|---|---|
| `portfolio-service:test` (unit) | 63 / 63 | ✅ No regressions |
| `portfolio-service:integrationTest` | 35 / 35 | ✅ No regressions |
| `PortfolioSeedServiceIT` (new) | 1 / 1 | ✅ postgres:18-alpine Testcontainer |

**`PortfolioSeedServiceIT` verifies:**
- Flyway runs V10 migration (E2E user exists in `users` table)
- `PortfolioSeedService.seed()` inserts exactly 160 holdings
- A second call (idempotency check) succeeds and produces a fresh 160-holding portfolio
- All prices are in `[1.00, 1000.00]` and all quantities are positive

### 5.5 E2E User Reference

| Property | Value |
|---|---|
| User ID | `00000000-0000-0000-0000-000000000e2e` |
| Email | `e2e-test-user@vibhanshu-ai-portfolio.dev` |
| Password | `e2e-test-password-2026` |
| Scrypt params | N=16384, r=16, p=1, dkLen=64 |
| API key header | `X-Internal-Api-Key` |

### 5.6 Next Step — Phase 3 Frontend (Pending)

The Playwright `global-setup.ts` orchestration remains to be implemented. It must:
1. Authenticate as the E2E user via Better Auth and persist the session to `playwright/.auth/e2e-user.json`.
2. POST `X-Internal-Api-Key` to `/internal/seed` on all three services.
3. Assert HTTP 200 from each before allowing any spec file to run.

The `INTERNAL_API_KEY` secret must be present in the GitHub Actions environment (`secrets.INTERNAL_API_KEY`) and in the local `.env.test` file before the first `terraform apply` or Playwright run.
