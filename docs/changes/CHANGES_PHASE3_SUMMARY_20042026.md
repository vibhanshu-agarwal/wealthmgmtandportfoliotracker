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
