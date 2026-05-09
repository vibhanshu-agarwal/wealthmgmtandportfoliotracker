# Implementation Tasks

## Commit 1: `chore: project scaffolding (Spring Boot + Vite)` [Vibhanshu]

- [ ] 1. Project scaffolding and monorepo setup
  - [ ] 1.1 Create root project directory (`allica-tech-test/`) and initialize git repository
  - [ ] 1.2 Generate Spring Boot 3.5.x project (Java 21, Gradle, dependencies: Spring Web, Spring Data JPA, H2, Validation) and place in `backend/` directory
  - [ ] 1.3 Configure `backend/build.gradle` with JaCoCo plugin, java-uuid-generator dependency, and test/integrationTest task separation
  - [ ] 1.4 Create Vite React TypeScript project in `frontend/` directory (`npm create vite@latest frontend -- --template react-ts`)
  - [ ] 1.5 Install frontend dependencies: Tailwind CSS 3.x, Axios, React Router, Zod, Zod
  - [ ] 1.6 Configure Vitest + React Testing Library + jsdom in `vite.config.ts`
  - [ ] 1.7 Configure Tailwind CSS (tailwind.config.js, postcss.config.js, base styles)
  - [ ] 1.8 Add root `.gitignore` covering both backend (build/, .gradle/) and frontend (node_modules/, dist/)
  - [ ] 1.9 Verify both projects build cleanly: `cd backend && ./gradlew build` and `cd frontend && npm run build`

## Commit 2: `feat(backend): implement domain entities, DTOs, and repository` [Kiro generates]

- [ ] 2. Backend data layer — entity, DTOs, repository
  - [ ] 2.1 Create `Customer` JPA entity with UUID v7 primary key, firstName, lastName, dateOfBirth (LocalDate), createdAt (Instant), and appropriate column constraints
  - [ ] 2.2 Implement UUID v7 generation using `com.fasterxml.uuid:java-uuid-generator` (`Generators.timeBasedEpochGenerator()`)
  - [ ] 2.3 Create `CreateCustomerRequest` record with Bean Validation annotations (`@NotBlank`, `@Size(max=100)`, `@NotNull`, `@Past`)
  - [ ] 2.4 Create `CustomerResponse` record (id, firstName, lastName, dateOfBirth, createdAt)
  - [ ] 2.5 Create `CustomerRepository` interface extending `JpaRepository<Customer, UUID>` with search query method (`findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase`)
  - [ ] 2.6 Create `CustomerNotFoundException` extending `RuntimeException`
  - [ ] 2.7 Configure `application.yml` with H2 datasource (file mode), JPA/Hibernate settings, and server port
  - [ ] 2.8 Create frontend TypeScript interfaces in `src/types/customer.ts` (Customer, CreateCustomerRequest, PageResponse, ApiError, FieldError) — matching the backend DTOs exactly
  - [ ] 2.9 Verify entity auto-creates table on startup: `./gradlew bootRun` starts without errors

## Commit 3: `feat(backend): implement core service logic and custom age validation` [Vibhanshu implements]

- [ ] 3. Backend service layer, custom validation, and unit tests
  - [ ] 3.1 Create custom `@MinimumAge` constraint annotation with configurable value (default 18) and message
  - [ ] 3.2 Implement `MinimumAgeValidator` (ConstraintValidator) — calculates age from DOB to current date using `Period.between()`
  - [ ] 3.3 Add `@MinimumAge` annotation to `dateOfBirth` field in `CreateCustomerRequest`
  - [ ] 3.4 Implement `CustomerService` with `create()`, `findById()`, and `list(search, pageable)` methods
  - [ ] 3.5 Implement entity-to-DTO mapping in service (set UUID v7 + createdAt on create, map to CustomerResponse)
  - [ ] 3.6 Write `MinimumAgeValidatorTest` — 7 test cases: exactly 18, one day before 18th, well over 18, future DOB, null DOB, leap year boundary, exact birthday
  - [ ] 3.7 Write `CustomerServiceTest` (mocks CustomerRepository) — 9 test cases: create valid, create maps fields, create repo throws, findById exists, findById not found, list no search, list with search, list empty, list pagination metadata

## Commit 4: `feat(backend): implement REST controller with RFC 9457 error handling` [Vibhanshu implements]

- [ ] 4. Backend controller, exception handling, CORS, and logging
  - [ ] 4.1 Implement `CustomerController` with POST `/api/customers` (returns 201 + Location header), GET `/api/customers` (paginated list with search/sort), GET `/api/customers/{id}`
  - [ ] 4.2 Implement `GlobalExceptionHandler` extending `ResponseEntityExceptionHandler` — handles MethodArgumentNotValidException (400), MethodArgumentTypeMismatchException (400), CustomerNotFoundException (404), HttpMessageNotReadableException (400), and generic Exception (500)
  - [ ] 4.3 Ensure all error responses conform to RFC 9457 `application/problem+json` with type, title, status, detail, instance fields + fieldErrors extension for validation errors
  - [ ] 4.4 Implement `CorsConfig` — allow origin `http://localhost:5173`, methods GET/POST/OPTIONS, expose Location header, maxAge 3600
  - [ ] 4.5 Configure PII masking in logging — dateOfBirth must not appear unmasked at INFO level or below; WARN for 4xx, ERROR with stack for 5xx
  - [ ] 4.6 Write `GlobalExceptionHandlerTest` — 7 test cases: validation 400, not found 404, malformed JSON 400, invalid UUID 400, unexpected 500, RFC 9457 fields present, no internals exposed

## Commit 5: `test(backend): add MockMvc integration tests for core flows` [Kiro generates]

- [ ] 5. Backend integration tests (MockMvc + H2)
  - [ ] 5.1 Write `CustomerControllerIntegrationTest` with `@SpringBootTest` + `MockMvc` — create valid customer (201 + Location header + body)
  - [ ] 5.2 Add validation error tests: missing firstName, missing lastName, missing DOB, all fields missing, under 18, future DOB, firstName exceeds 100 chars
  - [ ] 5.3 Add malformed JSON test and RFC 9457 response structure verification
  - [ ] 5.4 Add GET by ID tests: existing customer (200), non-existent ID (404), invalid UUID format (400)
  - [ ] 5.5 Add list endpoint tests: empty database, paginated results, page 1 size 5, search by firstName, search by lastName (case-insensitive), search no match, sort by lastName asc, sort by createdAt desc
  - [ ] 5.6 Add CORS tests: preflight returns expected headers, disallowed origin rejected
  - [ ] 5.7 Add roundtrip tests: POST then GET-by-ID (same data), POST then GET list (new customer present), POST 3 then list (all present, totalElements = 3)
  - [ ] 5.8 Run full test suite and verify ≥ 70% backend coverage with JaCoCo: `./gradlew check jacocoTestReport`

## Commit 6: `feat(frontend): implement API client and custom state hooks` [Vibhanshu implements]

- [ ] 6. Frontend API client and custom hooks with race condition handling
  - [ ] 6.1 Implement `src/api/customerApi.ts` with Axios — `createCustomer()`, `getCustomers()`, `getCustomer()` with AbortSignal support and 0-indexed page offset translation (UI page 1 → API page 0)
  - [ ] 6.2 Implement Axios response interceptor to transform errors into typed `ApiError` objects (RFC 9457 structure)
  - [ ] 6.3 Implement `useCustomers` hook with AbortController for race condition prevention, cancelled flag for memory leak guard, useEffect cleanup
  - [ ] 6.4 Implement `useCreateCustomer` hook with useRef<AbortController> for double-submit prevention, unmount cleanup
  - [ ] 6.5 Write `useCustomers.test.ts` — 7 test cases: initial state, successful fetch, API error, params change cancels in-flight, unmount aborts, race condition (only latest response), refetch
  - [ ] 6.6 Write `useCreateCustomer.test.ts` — 6 test cases: success, validation error, network error, double-submit aborts first, unmount aborts, reset clears error

## Commit 7: `feat(frontend): build customer list and creation forms with Zod validation` [Kiro generates shells, Vibhanshu implements logic]

- [ ] 7. Frontend UI components with Zod client-side validation
  - [ ] 7.1 Define Zod schema in `src/schemas/customerSchema.ts` — firstName (non-empty, max 100), lastName (non-empty, max 100), dateOfBirth (not in future, age ≥ 18). Use `z.string().min(1).max(100)` for names and custom `.refine()` for age validation
  - [ ] 7.2 Implement `CustomerTable` component — columns for first name, last name, DOB, created date; empty state; loading state
  - [ ] 7.3 Implement `SearchInput` component with 300ms debounce
  - [ ] 7.4 Implement `Pagination` component — 1-indexed display, previous/next buttons, disabled states, page info text
  - [ ] 7.5 Implement `ErrorNotification` component (toast/banner for API errors)
  - [ ] 7.6 Implement `CustomerListPage` — integrates CustomerTable, SearchInput, Pagination, useCustomers hook, "Add Customer" navigation
  - [ ] 7.7 Implement `CustomerFormPage` — controlled inputs, Zod schema validation on submit (parse → display ZodError field issues inline), submit with loading state, server error mapping to form fields, navigation on success
  - [ ] 7.8 Set up React Router in `App.tsx` — routes for list (`/`) and create (`/customers/new`)
  - [ ] 7.9 Write `CustomerFormPage.test.tsx` — 9 test cases: renders inputs, empty submit shows Zod errors, blank firstName error, under 18 error, future DOB error, valid submit calls API and navigates, in-progress disables button, server errors mapped to fields, generic error shows toast
  - [ ] 7.10 Write `CustomerTable.test.tsx` — 4 test cases: correct columns, renders data, empty state, loading state
  - [ ] 7.11 Write `SearchInput.test.tsx` — 4 test cases: renders input, debounces 300ms, rapid typing only final value, clear emits empty
  - [ ] 7.12 Write `Pagination.test.tsx` — 7 test cases: page info (1-indexed), first page previous disabled, last page next disabled, click next, click previous, single page both disabled, page offset API receives 0-indexed
  - [ ] 7.13 Write `customerSchema.test.ts` — 9 test cases: valid input parses, blank firstName fails, blank lastName fails, exceeds 100 fails, missing DOB fails, future DOB fails, under 18 fails, exactly 18 passes, multiple invalid returns all issues
  - [ ] 7.14 Run full frontend test suite and verify ≥ 70% coverage: `npm run test -- --coverage`

## Commit 8: `docs: add comprehensive README and AI_USAGE documentation` [Vibhanshu]

- [ ] 8. Documentation
  - [ ] 8.1 Write `README.md` — project overview, tech stack table, prerequisites (Java 21, Node 18+), setup instructions (backend + frontend), how to run the application, how to run tests (both), API endpoint documentation (all 3 endpoints with examples), key design decisions (UUID v7, RFC 9457, layered architecture, custom hooks with AbortController, Zod validation, PII masking)
  - [ ] 8.2 Write `AI_USAGE.md` — which AI tools were used, how they were used (spec generation, shell generation, test generation, review), what value they provided, what was manually written/reviewed
  - [ ] 8.3 Final review: verify both projects build and all tests pass end-to-end
