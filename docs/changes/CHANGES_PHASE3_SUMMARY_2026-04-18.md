# Phase 3 Change Summary (2026-04-18)

**Previous revision:** [CHANGES_PHASE3_SUMMARY_2026-04-16_v1.md](./CHANGES_PHASE3_SUMMARY_2026-04-16_v1.md) — AI Insights chat hardening, specs, and targeted tests (**`ba4b679`**).

**Infrastructure companion (excluded here by charter):** [CHANGES_PHASE3_INFRA_SUMMARY_18042026.md](./CHANGES_PHASE3_INFRA_SUMMARY_18042026.md) — Terraform, **`deploy.yml`**, shared CI/CD, Qodana, Compose **`APP_AUTH_USER_ID`**, and E2E workflow platform changes **since** [CHANGES_INFRA_SUMMARY_2026-04-17_v2.md](./CHANGES_INFRA_SUMMARY_2026-04-17_v2.md).

---

## Scope

This summary covers **application, UX, tests, and product documentation** from **`ba4b679`** through **`1278a13`** on **`architecture/cloud-native-extraction`**, **excluding** the infrastructure-only slice delegated to **`CHANGES_PHASE3_INFRA_SUMMARY_18042026.md`**.

---

## What changed

### 1. Market data service — external quotes and hydration (**`c01931f`** and follow-on specs)

- Introduced **external market data** integration (configuration, HTTP client, Yahoo Finance implementation where applicable), **refresh** scheduling, and **startup hydration** so tickers gain prices after cold start.
- Expanded **`.kiro/specs/market-data-resiliency-expansion/`** (design, requirements, tasks) and marked tasks complete (**`0989f39`**).
- Earlier spec/doc refinements on **2026-04-16** (**`901c6ff`**, **`82c8fab`**) clarified provider usage, safeguards, and **null-price** hydration behavior (still part of the same initiative relative to the Phase 3 summary v1 baseline).

### 2. Frontend auth — static export migration (**`baae86e`** and related)

- Moved **JWT issuance** toward **API Gateway** login flows; removed reliance on **Next.js** Better Auth **route handlers** for the production static story; shifted client session gating to **localStorage**-backed runtime (**`frontend/src/lib/auth/session.ts`**, **`useAuthenticatedUserId`**, page shells).
- Updated **Next** config for **`output: "export"`**; adjusted dashboard/market/portfolio/insights pages and **UserMenu** for the new auth model; added/updated **JWT** health and route tests under **`frontend/src/app/api/auth/jwt/`** where still applicable to gateway-backed tokens.
- Added formal spec bundle **`.kiro/specs/static-export-auth-migration/`**.

**Supporting commits:** **`49ecdde`** (static-export **Dockerfile** / **`.dockerignore`**, session hook refinements), **`f2feb0c`** (preserve **pending** state while hydrating local session), **`6c8c013`** (align **Jest** / component tests with local session runtime).

**CI tooling overlap:** **`62abb67`** also touched **`frontend/package.json`** and **`playwright.config.ts`** for static export; the **`.github/workflows`** portion is recorded only in the infra companion.

### 3. Client API base URL and CORS alignment (**`6f4129a`**, **`4127770`**, **`ed19abd`**)

- Centralized **browser** vs **server** API base resolution (**`frontend/src/lib/config/api.ts`**, **`apiService`**, **`insights`**, **`portfolio`**, **`insights-actions`**) so static export and Playwright runs consistently target the **gateway** where required.
- **`ed19abd`** — **CORS** updates in **`api-gateway` `SecurityConfig`**, **SummaryCards** resilience, and Playwright helpers/config so portfolio views load against the aligned origin story.

### 4. Playwright E2E — static export, gateway session, and diagnostics (**`53d9fa0`** … **`1278a13`**)

- **`53d9fa0`**, **`29101e4`**, **`c7fc989`**, **`4127770`** — **`global.setup`**, **`dashboard-*`**, **`auth-jwt-health`**, **`helpers/api`**, layout/favicon cleanups so **localStorage** session shape matches runtime and auth preflight hits the **gateway** login endpoint.
- **`81899fb`** — **`installGatewaySessionInitScript`** in **`helpers/browser-auth`**, **`fetchPortfolio`** tolerates **market-data** failures so holdings still render, golden-path and dashboard specs wired to the init script (application files only; Compose env default is infra companion).
- **`1278a13`** — Session module hardening (**`useLayoutEffect`**, **`coerceSession`**), Playwright config lifecycle (**`reuseExistingServer`** / webServer alignment), **`AuthController`** + **`application.yml`** defaults for stub **`user-001`**, and **JWT** empty-secret guardrails in **`helpers/auth`** / **`auth-jwt-health.spec.ts`**.

### 5. Documentation and roadmap (**`733773b`**, **`4fca616`**, linked **`.kiro`** updates)

- Split long-term roadmap content into **`ROADMAP.md`**; README cross-links and AI roadmap clarification.
- **`733773b`** also adjusted related **`.kiro`** market-data and AWS deployment spec pointers where they referenced roadmap placement.

### 6. Frontend test typing (**`c5d9eb8`**)

- Fixed **TypeScript** typing in **`ChatInterface.test.tsx`** and **`usePortfolio.test.ts`** after auth hook changes (between v1 summary and the larger static-export series).

### 7. Spring Boot skill assets (**`523a563`**)

- Added **`.junie/skills/creating-springboot-projects`** resilience sample (**`resilience-service.java`**) and **`spring-boot-4-features.md`** reference updates (native **`@Retryable`** / **`@ConcurrencyLimit`** demos for authors using the skill pack).

### 8. Merge from **`main`** (**`da46d48`**)

- Brought **`main`** into **`architecture/cloud-native-extraction`** so shared automation (Qodana, branch filters) and feature work converge; **non-merge** substantive rows above reference the meaningful child commits.

---

## Behavior / UX impact

- **Market data** is more realistic in **prod-like** profiles: external quotes, refresh, and hydration reduce “all null prices” after startup.
- **Static export** frontends no longer depend on Next server **auth API routes** for the deployed bundle; users authenticate against the **gateway**-centric flow with **localStorage** session continuity.
- **Playwright** and **manual** static runs see fewer **CORS** / **wrong-origin** failures; portfolio views tolerate **partial market-data** outages without blanking the whole page.
- **Gateway stub login** and Flyway demo data consistently use **`user-001`** in full-stack runs (gateway defaults in **`application.yml`** / **`AuthController`**; Compose default **`APP_AUTH_USER_ID`** is noted in the infra companion).

---

## Verification evidence (representative)

- **Market data (backend):** targeted Gradle tests under **`market-data-service`** as invoked in the **`c01931f`** series (WireMock / integration suites added or extended there).
- **Frontend unit tests:** **`npm test`** for updated session, overview, and market components (**`6c8c013`** paths).
- **Playwright (local):** from **`frontend`**, **`npx playwright test`** for **`tests/e2e/auth-jwt-health.spec.ts`**, **`dashboard-data.spec.ts`**, **`golden-path.spec.ts`** with **`docker compose`** up and gateway on **`:8080`**.
- **CI:** **`frontend-ci.yml`** and **`frontend-e2e-integration.yml`** (see infra companion for workflow-level deltas).

---

## Git record

- **Branch:** `architecture/cloud-native-extraction`
- **Tip at authoring:** `1278a13`
- **Baseline (previous Phase 3 app summary):** `ba4b679`
- **Remote:** [github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker)

---

## Addendum (2026-04-18 — Playwright smoke and gateway auth integrity)

This addendum records a **follow-up** on the same branch after the **`1278a13`** tip described above. It is versioned in git in the same commit as the workflow and Playwright changes below (search history for the subject *fix(ci): require real gateway login in Playwright*). It tightens CI so green pipelines do not rely on **synthetic JWTs** or **partial** Playwright runs that could mask broken gateway authentication.

### Playwright and helpers

- **`installGatewaySessionInitScript`** (`frontend/tests/e2e/helpers/browser-auth.ts`) **always** calls **`POST /api/auth/login`** on the gateway. The previous behaviour under **`SKIP_BACKEND_HEALTH_CHECK`** minted a token locally and never validated the real login path; that bypass was removed.
- **`frontend/playwright.config.ts`** — Added a **`static-smoke`** project (only **`dashboard-smoke.spec.ts`**, no setup project). The main **`chromium`** project **`testIgnore`**s that file so it is not executed twice when running the full suite.

### Frontend CI smoke job

- **`.github/workflows/frontend-ci.yml`** — The **`e2e-smoke`** job now runs **`--project=static-smoke`** only (static export serves **`/login`** with HTML). It no longer runs **`dashboard-data`** tests with **`grep-invert`**, which previously kept tests 2/4/5 off while still running misleading auth-adjacent checks without a live gateway. Postgres, Better Auth schema creation, and dev user seeding were removed from this job because they are not required for the static smoke path. **`SKIP_BACKEND_HEALTH_CHECK`** remains **only** to skip the **gateway readiness poll** in **`global-setup.ts`** when no stack is started, not to fake credentials.

### **`global-setup.ts`**

- Log message clarifies that skipping the health poll is for **stack-less** runs; full E2E must wait for the gateway.

### Shared workflows and JWT secret alignment

- **`.github/workflows/frontend-e2e-integration.yml`** and **`.github/workflows/ci-verification.yml`** — **`AUTH_JWT_SECRET`** uses **`${{ secrets.AUTH_JWT_SECRET || 'local-dev-secret-change-me-min-32-chars' }}`** so an empty or unset repository secret still matches the **Docker Compose** default and the API Gateway can sign tokens (avoids failing or misleading auth when secrets are not configured on forks or new environments).

### Where gateway auth is still asserted in CI

- **`frontend-e2e-integration.yml`** (full Docker Compose stack) and **`ci-verification.yml`** (Compose-backed Playwright) remain the workflows that exercise **real** gateway login, JWT acceptance, and portfolio flows.
