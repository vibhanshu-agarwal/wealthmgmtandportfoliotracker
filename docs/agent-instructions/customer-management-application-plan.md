# Customer Management Application — Build Plan

> Source brief: `docs/agent-instructions/Senior Full Stack Engineer - Tech Task 2026.pdf`
> Project location: `D:/Projects/Development/Java/Spring/customer-management/` (fresh `git init`).
> Independent of the wealth-management workspace.

---

## 1. Scope

### In scope

- Spring Boot 3.x backend (Java 21) exposing **Create + Read** REST endpoints for
  `Customer { firstName, lastName, dateOfBirth }`.
- H2 in-memory persistence via Spring Data JPA.
- React 19 + TypeScript + **Tailwind v3** frontend to create and list customers.
- Bean Validation on inputs; centralized `@ControllerAdvice` returning RFC 7807 `ProblemDetail`.
- Comprehensive unit and integration tests (backend + frontend).
- `README.md` with setup, architecture, trade-offs.
- `AI_USAGE.md` with concrete, non-generic AI-usage evidence.
- Git history that reflects iterative development (small, scoped commits).

### Out of scope (explicit, documented in README "Trade-offs")

- **DELETE endpoint** and any delete-related UI — excluded by requirements.
- **E2E tests (Playwright)** — excluded by requirements.
- Authentication, authorization, multi-tenancy.
- Update (PUT/PATCH) operations.
- Pagination/sorting beyond a simple ordered list.
- Docker / CI pipelines / deployment.
- Schema migrations (Flyway/Liquibase) — H2 in-memory + JPA DDL is sufficient.
- Internationalization, accessibility audit beyond basic semantic HTML + labels.

---

## 2. Tech Stack (locked)

| Layer          | Choice                                           | Version    |
|----------------|--------------------------------------------------|------------|
| Language (BE)  | Java                                             | 21 (LTS)   |
| Framework (BE) | Spring Boot                                      | 3.3.x      |
| Persistence    | Spring Data JPA + H2                             | 3.3-compat |
| Validation     | Jakarta Bean Validation                          | bundled    |
| Build (BE)     | Maven Wrapper                                    | 3.9.x      |
| Test (BE)      | JUnit 5, Mockito, AssertJ, Spring Boot Test      | bundled    |
| Coverage (BE)  | JaCoCo                                           | 0.8.12     |
| Language (FE)  | TypeScript                                       | 5.x        |
| Framework (FE) | React                                            | 19.x       |
| Bundler        | Vite                                             | 5.x        |
| Styling        | Tailwind CSS                                     | **3.x**    |
| Test (FE)      | **Vitest** + React Testing Library + MSW + jsdom | latest     |
| Coverage (FE)  | Vitest v8 provider                               | bundled    |

> **Test-runner note:** Vitest is chosen over Jest because Vite is the bundler. Vitest's API is **Jest-compatible** (
`describe`/`it`/`expect`, `vi.fn()` mirrors `jest.fn()`), so the test patterns and reviewer expectations are identical —
> without paying Jest's Vite/ESM/TS configuration tax. This deviation from the original prompt is documented in
`AI_USAGE.md`.

---

## 3. Repository Layout

```
customer-management/
├── README.md
├── AI_USAGE.md
├── .gitignore
├── .editorconfig
├── backend/
│   ├── pom.xml
│   ├── mvnw, mvnw.cmd, .mvn/
│   └── src/
│       ├── main/java/com/example/customer/
│       │   ├── CustomerManagementApplication.java
│       │   ├── domain/
│       │   │   ├── model/Customer.java                       # pure domain (no JPA)
│       │   │   └── port/CustomerRepository.java              # port (interface)
│       │   ├── application/
│       │   │   └── CustomerService.java                      # use cases
│       │   ├── adapter/
│       │   │   ├── in/web/
│       │   │   │   ├── CustomerController.java
│       │   │   │   ├── dto/CustomerRequest.java              # @Valid record
│       │   │   │   ├── dto/CustomerResponse.java
│       │   │   │   └── GlobalExceptionHandler.java           # @ControllerAdvice
│       │   │   └── out/persistence/
│       │   │       ├── CustomerJpaEntity.java
│       │   │       ├── CustomerJpaRepository.java
│       │   │       └── CustomerRepositoryAdapter.java        # implements port
│       │   ├── config/
│       │   │   └── WebConfig.java                            # CORS
│       │   └── common/exception/
│       │       ├── CustomerNotFoundException.java
│       │       └── DomainValidationException.java
│       ├── main/resources/
│       │   └── application.yml
│       └── test/java/com/example/customer/...                # see §6
└── frontend/
    ├── package.json
    ├── vite.config.ts                                         # Vitest config + coverage thresholds
    ├── tsconfig.json
    ├── tailwind.config.js, postcss.config.js
    ├── index.html
    ├── src/
    │   ├── main.tsx, App.tsx, index.css
    │   ├── api/customerApi.ts                                 # fetch wrapper
    │   ├── api/errors.ts                                      # ApiError type + parser
    │   ├── hooks/useCustomers.ts
    │   ├── components/
    │   │   ├── CustomerForm.tsx
    │   │   ├── CustomerList.tsx
    │   │   ├── ErrorBanner.tsx
    │   │   └── ui/Button.tsx, Input.tsx, FieldError.tsx
    │   ├── types/customer.ts
    │   └── test/
    │       ├── setup.ts                                        # @testing-library/jest-dom matchers
    │       └── msw/handlers.ts                                 # MSW request handlers
    └── tests/                                                  # *.test.tsx co-located OR here
```

---

## 4. Architecture

### Style

**Layered + Ports/Adapters lite.** Full hexagonal is overkill for one entity; ports keep persistence swappable and unit
tests trivially isolated; layers keep onboarding fast for reviewers.

```
[ Web Adapter ]  →  [ Application Service ]  →  [ Domain Port ]  ←  [ JPA Adapter ]
       ▲                    │                          │
       │                    ▼                          ▼
   DTOs / Validation   Domain model `Customer`    H2 (in-memory)
```

### Key decisions

| Concern            | Decision                                                          | Rationale                                                      |
|--------------------|-------------------------------------------------------------------|----------------------------------------------------------------|
| ID                 | Server-assigned `UUID`                                            | Avoids client guessing/race; safe distributed                  |
| Date               | `LocalDate` ISO-8601                                              | Locale-independent; rejects future via `@Past`                 |
| Errors             | RFC 7807 `application/problem+json` via `ProblemDetail`           | Standard, no bespoke client coupling                           |
| Validation surface | DTO (`CustomerRequest`) + domain invariants in `Customer` factory | Belt-and-braces; domain stays valid even if web layer bypassed |
| Mapping            | Hand-rolled mappers                                               | Avoids MapStruct setup cost for 2 DTOs                         |
| CORS               | Restrict to `http://localhost:5173`                               | Never ship `*`                                                 |
| ID exposure        | Return UUID; `Location` header on `POST` 201                      | REST hygiene                                                   |
| Logging            | SLF4J; warn on 4xx, error on 5xx with stack                       | No PII (DOB) in logs at INFO                                   |
| Frontend state     | React 19 hooks + custom `useCustomers`                            | No Redux for one entity                                        |

---

## 5. REST Contract

```
POST   /api/v1/customers        → 201 + Location, body: CustomerResponse
                                  400 on validation
GET    /api/v1/customers        → 200, CustomerResponse[]
GET    /api/v1/customers/{id}   → 200 | 404
```

**Request:**

```json
{
  "firstName": "Ada",
  "lastName": "Lovelace",
  "dateOfBirth": "1815-12-10"
}
```

**Validation rules** (declarative on `CustomerRequest` record):

- `firstName`, `lastName`: `@NotBlank`, `@Size(min=1, max=100)`, `@Pattern("^[\\p{L} '\\-]+$")`
- `dateOfBirth`: `@NotNull`, `@Past`; service-level lower bound (e.g. not before `1900-01-01`)

**Error envelope** (RFC 7807):

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request body has invalid fields",
  "instance": "/api/v1/customers",
  "errors": [
    {
      "field": "firstName",
      "message": "must not be blank"
    },
    {
      "field": "dateOfBirth",
      "message": "must be a past date"
    }
  ]
}
```

---

## 6. Test Strategy — Comprehensive

> **Pyramid (no E2E):** unit tests dominate; thin slice tests for web/persistence; one full-context integration test for
> happy + critical sad paths.
> **Coverage targets enforced via JaCoCo + Vitest:** **≥70% lines on backend and frontend** (per brief).

### 6.1 Backend — Unit tests (JUnit 5 + Mockito + AssertJ)

**`domain/model/CustomerTest`**

- factory accepts valid inputs → returns object with correct fields
- rejects null/blank firstName / lastName (parameterized)
- rejects DOB null
- rejects DOB in the future
- rejects DOB before 1900-01-01
- normalizes whitespace (trims) on names
- equals/hashCode contract (UUID-based)

**`application/CustomerServiceTest`** (mocks `CustomerRepository` port)

- `create`: persists and returns response with generated UUID; verifies repository called once
- `create`: bubbles `DomainValidationException` if domain rejects
- `findAll`: returns mapped list; empty list when repo empty
- `findAll`: preserves repository ordering (insertion or sort spec)
- `findById`: returns customer when present
- `findById`: throws `CustomerNotFoundException` when absent

**`adapter/out/persistence/CustomerRepositoryAdapterTest`**

- domain → entity mapping (all fields)
- entity → domain mapping (all fields)
- null/optional handling
- delegates `save`, `findAll`, `findById` to JPA repo (verify with Mockito)

**`adapter/in/web/GlobalExceptionHandlerTest`**

- `MethodArgumentNotValidException` → 400 ProblemDetail with field list
- `ConstraintViolationException` → 400
- `CustomerNotFoundException` → 404
- `DomainValidationException` → 400
- `HttpMessageNotReadableException` (malformed JSON) → 400
- `Exception` (catch-all) → 500, generic detail (no stack leak)

### 6.2 Backend — Slice tests

**`@WebMvcTest CustomerControllerTest`** (mocks `CustomerService`)

- `POST` 201 with valid body; asserts JSON shape and `Location` header
- `POST` 400 — missing firstName, blank firstName, oversized firstName, regex fail
- `POST` 400 — missing lastName variants
- `POST` 400 — missing dateOfBirth
- `POST` 400 — future dateOfBirth
- `POST` 400 — malformed JSON body
- `POST` 415 — unsupported content type
- `POST` 400 — empty body
- `GET /api/v1/customers` returns array
- `GET /{id}` 200 happy
- `GET /{id}` 404 when service throws
- `GET /{id}` 400 when path id not a UUID

**`@DataJpaTest CustomerJpaRepositoryTest`** (Spring slice with H2)

- `save` persists and assigns generated id
- `findById` returns saved row
- `findAll` returns inserted rows
- query ordering (if a `findAllByOrderBy...` finder is added)

### 6.3 Backend — Full integration

**`CustomerManagementApplicationIT`** (`@SpringBootTest` + `MockMvc`, real H2)

- Bootstraps full context (smoke)
- POST then GET roundtrip — created customer appears in list
- POST then GET-by-id — retrieves the same row
- Validation failure produces RFC 7807 body with `application/problem+json` content type
- 404 on unknown id returns ProblemDetail
- CORS preflight from allowed origin succeeds; from disallowed origin denied

### 6.4 Frontend — Unit tests (Vitest + RTL)

**`api/customerApi.test.ts`** (mock `fetch`)

- `listCustomers` parses array
- `createCustomer` POSTs JSON, returns response
- non-2xx response throws typed `ApiError` with status + parsed problem body
- network error throws `ApiError` with `kind: 'network'`
- non-JSON error body handled gracefully

**`hooks/useCustomers.test.tsx`** (with `renderHook`)

- initial state: loading, empty list, no error
- after successful load: `loading=false`, list populated
- after `add`: optimistic insert then reconcile with server response
- after failed `add`: rolls back optimistic state, surfaces error
- after failed load: error set, list empty

**`components/CustomerForm.test.tsx`**

- renders all fields and submit button
- shows field-level errors when submitting empty form (client-side validation parity with API)
- shows error for future DOB
- disables submit while submitting
- calls `onSubmit` with trimmed values on valid submit
- displays server validation errors mapped to fields when API returns 400

**`components/CustomerList.test.tsx`**

- renders empty state copy when list is empty
- renders rows for each customer (firstName, lastName, formatted DOB)
- renders skeleton/loading state
- renders error banner when error provided

**`components/ErrorBanner.test.tsx`**

- shows message; dismiss button calls handler
- not rendered when no error

### 6.5 Frontend — Integration (Vitest + RTL + MSW)

**`App.integration.test.tsx`**

- Mounts `<App/>` against MSW handlers serving the real REST contract
- Initial render: list loads via `GET /api/v1/customers`
- Filling form + submit triggers `POST` and the new customer appears in list without manual reload
- Server returns 400 with field errors → form shows them
- Server returns 500 → error banner visible, list unchanged

### 6.6 Coverage gates

- **Backend:** JaCoCo plugin in `pom.xml`, `verify` goal fails if line coverage < 70%.
- **Frontend:** Vitest coverage thresholds in `vite.config.ts` (lines ≥ 70%).

---

## 7. Phases & Commit Plan

Each item below is intended as **one commit** (some may split into 2 if large). Conventional Commits style.

### Phase 1 — Scaffolding, domain, H2 config

1. `chore: init repo, add .gitignore, .editorconfig, README skeleton`
2. `chore(backend): scaffold Spring Boot 3.3 / Java 21 with H2 + JPA`
3. `feat(domain): Customer model with invariants + repository port`
4. `chore(frontend): scaffold Vite + React 19 + TS + Tailwind v3`

### Phase 2 — Backend RESTful API (POST + GET)

5. `feat(persistence): JPA entity, JpaRepository, repository adapter`
6. `feat(api): create + list endpoints with DTOs and mapping`
7. `feat(api): get-by-id endpoint`
8. `feat(api): bean validation + @ControllerAdvice with RFC 7807`
9. `feat(config): restrict CORS to local Vite dev origin`

### Phase 3 — Backend Test Suite

10. `test(domain): Customer factory invariants`
11. `test(application): CustomerService unit tests with Mockito`
12. `test(persistence): @DataJpaTest for repository + adapter mapping tests`
13. `test(web): @WebMvcTest controller + GlobalExceptionHandler`
14. `test(it): @SpringBootTest end-to-end via MockMvc`
15. `chore(build): JaCoCo plugin with 70% line threshold`

### Phase 4 — Frontend (React 19 + state + Tailwind v3 + API integration)

16. `feat(ui): typed API client and ApiError model`
17. `feat(ui): useCustomers hook (load, optimistic add, error rollback)`
18. `feat(ui): CustomerForm with client-side validation`
19. `feat(ui): CustomerList + ErrorBanner + loading states`
20. `feat(ui): wire App; map server validation errors to form fields`

### Phase 5 — Frontend Test Suite + Documentation

21. `test(ui): api client unit tests`
22. `test(ui): useCustomers hook tests`
23. `test(ui): component tests (Form, List, ErrorBanner)`
24. `test(ui): MSW integration tests for App flows`
25. `chore(frontend): vitest coverage thresholds (70%)`
26. `docs: README — setup, architecture, decisions, trade-offs`
27. `docs: AI_USAGE.md — tools, delegation, validation, corrections, time`

> Avoid a single "initial commit" — reviewers explicitly inspect history.

---

## 8. README Outline

1. **What it is** (one paragraph)
2. **Quickstart** — `cd backend && ./mvnw spring-boot:run` · `cd frontend && npm i && npm run dev`
3. **Run tests** — `./mvnw verify` · `npm run test -- --coverage`
4. **API reference** — table of endpoints with sample requests/responses (POST + GET only)
5. **Architecture** — diagram + layer responsibilities
6. **Design decisions** — table from §4
7. **Trade-offs (time-boxed)** — explicit list of what was deliberately skipped (DELETE, Playwright, auth, pagination,
   Docker, Flyway, MapStruct, etc.) with one-line rationale each
8. **Project layout** — tree
9. **Coverage report locations** — `backend/target/site/jacoco/index.html`, `frontend/coverage/index.html`

---

## 9. AI_USAGE.md Outline

1. **Tools used** — names + role (e.g., "Augment Agent / Claude Opus 4.7 — primary pair")
2. **What I delegated to AI** — bullets with concrete examples (e.g., "JaCoCo plugin block in `pom.xml`", "
   Tailwind-styled form skeleton")
3. **What I wrote myself** — domain invariants, error contract, commit plan
4. **Validation approach** — running tests, reading diffs, manual API smoke via `curl`/HTTPie
5. **Concrete corrections of AI mistakes** — at least 3 specific examples (e.g., "AI suggested `@FutureOrPresent` for
   DOB — wrong, replaced with `@Past`"; "AI generated `LocalDateTime` for DOB — corrected to `LocalDate`"; "AI emitted
   `*` CORS — restricted to localhost:5173")
6. **Deviations from original prompt** — explain Vitest chosen over Jest (Vite-native, Jest-API-compatible, saves config
   tax) and any other deliberate scope decisions
7. **Time breakdown** — table: task / with-AI minutes / without-AI estimate / delta
8. **Net impact** — qualitative paragraph on where AI helped vs slowed down

---

## 10. Definition of Done

- [ ] `./mvnw verify` passes locally with JaCoCo line coverage ≥ 70%.
- [ ] `npm run test -- --coverage` passes with line coverage ≥ 70%.
- [ ] Manual smoke: backend up, frontend up, create a customer in the UI, see it in the list, refresh page → still there
  until JVM restart.
- [ ] All endpoints return RFC 7807 `ProblemDetail` on errors.
- [ ] No `console.error` / unhandled promise rejections in browser console during smoke.
- [ ] `README.md` and `AI_USAGE.md` complete with the sections above.
- [ ] Commit history reflects the phased plan (no monolithic initial commit).
- [ ] `git bundle create <your-name>-tech-test.bundle --all` produces a valid bundle.

---

## 11. Open Questions / Decisions Pending

1. **Duplicate policy** — should `(firstName, lastName, dateOfBirth)` be unique? **Default plan: no** (people can share
   names + DOBs); document in README.
2. **Sort order on `GET /customers`** — by creation timestamp DESC? **Default plan: yes** (newest first); add`createdAt`
   audit column to entity.

```
</markdown>
