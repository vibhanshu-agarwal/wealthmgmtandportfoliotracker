# Phase 1: Quality & Integration Mission

**Objective:** Implement a robust testing suite and a data-seeding strategy for the Modular Monolith before extracting services to AWS.

---

## 1. Backend Testing Stack (Modern Java)
- **Framework:** JUnit 6 (Platform) + Mockito 5.x
- **Architecture Enforcement:** Spring Modulith `@ApplicationModuleTest`
- **Database Testing:** Testcontainers (PostgreSQL Module)
### Execution Steps for Agent:
1. Update `build.gradle` to include JUnit 6 dependencies and `org.springframework.modulith:spring-modulith-starter-test`.
2. Create `src/test/java/com/wealth/ArchitectureTest.java` using `ApplicationModules.of(WealthApplication.class).verify()` to ensure zero circular dependencies.
3. Implement a `@ParameterizedTest` in the `portfolio` module to test various asset price fluctuations using JUnit 6's new `@ValueSource` improvements.

---

## 2. Frontend Testing Stack (Next.js 16+)
- **Unit/Component:** Vitest + React Testing Library
- **E2E/Integration:** Playwright
- **Mocking:** MSW (Mock Service Worker) for API interception.

### Execution Steps for Agent:
1. Install `@vitest/ui`, `@testing-library/react`, and `playwright`.
2. Configure `vitest.config.ts` to recognize the Next.js alias paths (`@/*`).
3. Create a "Smoke Test" in Playwright to verify the `standalone` build starts and renders the dashboard.

---

## 3. Persistent Data & Seeding
- **Tool:** Flyway Migrations
- **Location:** `src/main/resources/db/migration/`

### Execution Steps for Agent:
1. Create `V1__Initial_Schema.sql` reflecting the current JPA entities.
2. Create `V2__Seed_Market_Data.sql` with a baseline of 50 historical price points for AAPL, TSLA, and BTC/USD.
3. Ensure `spring.docker.compose.enabled=true` is active in `application-dev.yml` to auto-link the DB.

---

## 4. UI-to-Backend Bridge (The Proxy)
- **Pattern:** Typesafe Fetch Clients

### Execution Steps for Agent:
1. Define `frontend/types/portfolio.d.ts` based on Java Records in `com.wealth.portfolio.dto`.
2. Implement `apiService.ts` using the `/api` prefix (leveraging the `next.config.js` rewrites).
3. Connect the "Portfolio Total" UI component to the `GET /api/portfolio/summary` endpoint.