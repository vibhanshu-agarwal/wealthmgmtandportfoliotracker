# Implementation Plan: AI Portfolio Advisor — Phase 1 (Foundation & Mock Strategy)

## Overview

Phase 1 establishes the hexagonal architecture foundation for AI-powered portfolio analysis with zero AI dependencies. We define the `PortfolioAdvisor` domain port and `AnalysisResult` record, implement the `MockPortfolioAdvisor` default adapter, wire the `PortfolioService.analyzePortfolio()` method, expose the REST endpoint via a dedicated `PortfolioAnalyzeController`, integrate `AdvisorUnavailableException` into the `GlobalExceptionHandler`, and validate everything with unit tests and property-based tests. All tasks run via `./gradlew test` — no integration tests in this phase.

## Tasks

- [x] 1. Define domain port and DTO
  - [x] 1.1 Create `AnalysisResult` record in `com.wealth.portfolio`
    - Create `portfolio-service/src/main/java/com/wealth/portfolio/AnalysisResult.java`
    - Record with fields: `int riskScore`, `List<String> concentrationWarnings`, `List<String> rebalancingSuggestions`
    - Compact constructor with `List.copyOf()` defensive copies for both list fields
    - No infrastructure imports — only `java.util.List`
    - _Requirements: 1.3, 1.5_

  - [x] 1.2 Create `PortfolioAdvisor` interface in `com.wealth.portfolio`
    - Create `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioAdvisor.java`
    - Single method: `AnalysisResult analyze(Portfolio portfolio)`
    - Javadoc specifying: must be thread-safe, riskScore 0 for empty portfolios, throws `AdvisorUnavailableException` if AI service unreachable
    - Zero infrastructure dependencies — only imports from `com.wealth.portfolio`
    - _Requirements: 1.1, 1.2, 1.4_

  - [x] 1.3 Create `AdvisorUnavailableException` in `com.wealth.portfolio`
    - Create `portfolio-service/src/main/java/com/wealth/portfolio/AdvisorUnavailableException.java`
    - Extends `RuntimeException` (unchecked)
    - Two constructors: `(String message, Throwable cause)` and `(String message)`
    - Mirrors existing `FxRateUnavailableException` pattern
    - _Requirements: 9.1_

- [x] 2. Implement mock adapter and exception handler
  - [x] 2.1 Create `MockPortfolioAdvisor` in `com.wealth.portfolio.ai`
    - Create `portfolio-service/src/main/java/com/wealth/portfolio/ai/MockPortfolioAdvisor.java`
    - Implements `PortfolioAdvisor`, annotated with `@Service` and `@Profile("!ollama & !bedrock")`
    - Empty portfolio → `AnalysisResult(0, List.of(), List.of())`
    - Non-empty portfolio → deterministic hardcoded response: riskScore 42, one concentration warning, two rebalancing suggestions
    - No network calls, no file I/O, no AI dependencies — only core Java + Spring `@Service`/`@Profile`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 10.3_

  - [x] 2.2 Add `AdvisorUnavailableException` handler to `GlobalExceptionHandler`
    - Add `@ExceptionHandler(AdvisorUnavailableException.class)` method to existing `GlobalExceptionHandler.java`
    - Returns HTTP 503 with JSON body: `{"error": "AI advisor unavailable", "retryable": true}`
    - Mirrors existing `handleFxRateUnavailable` pattern
    - _Requirements: 9.2, 5.4_

- [x] 3. Wire service and controller
  - [x] 3.1 Add `analyzePortfolio(String userId)` method to `PortfolioService`
    - Inject `PortfolioAdvisor` via constructor (add to existing constructor parameters)
    - Method: `findByUserId(userId)` → throw `UserNotFoundException` if empty → call `portfolioAdvisor.analyze(portfolios.getFirst())`
    - Annotate with `@Transactional(readOnly = true)`
    - _Requirements: 5.1, 5.3_

  - [x] 3.2 Create `PortfolioAnalyzeController` at `/api/portfolios`
    - Create `portfolio-service/src/main/java/com/wealth/portfolio/PortfolioAnalyzeController.java`
    - `@RestController` with `@RequestMapping("/api/portfolios")`
    - Single endpoint: `@PostMapping("/{userId}/analyze")` returning `ResponseEntity<AnalysisResult>`
    - Constructor-injected `PortfolioService`
    - Separate controller from existing `PortfolioController` to avoid path conflicts (`/api/portfolios` vs `/api/portfolio`)
    - _Requirements: 5.1, 5.2_

- [x] 4. Checkpoint — Verify compilation and wiring
  - Ensure `./gradlew :portfolio-service:compileJava` succeeds with no errors
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Unit tests for mock adapter and controller
  - [x] 5.1 Write unit tests for `MockPortfolioAdvisor`
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/ai/MockPortfolioAdvisorTest.java`
    - Test non-empty portfolio → riskScore > 0, non-empty warnings, non-empty suggestions
    - Test empty portfolio → riskScore 0, empty lists
    - Test deterministic output: same input produces same output across multiple calls
    - Use standalone JUnit 5 + Mockito (no Spring context needed)
    - _Requirements: 11.1, 11.2, 2.3, 2.5_

  - [x] 5.2 Write unit tests for `PortfolioAnalyzeController`
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioAnalyzeControllerTest.java`
    - Use MockMvc standalone setup with `GlobalExceptionHandler` as controller advice (matches existing `PortfolioControllerTest` pattern)
    - Test `POST /api/portfolios/{userId}/analyze` → 200 with correct JSON structure (`riskScore`, `concentrationWarnings`, `rebalancingSuggestions`)
    - Test unknown userId → 404
    - Test `AdvisorUnavailableException` → 503 with `{"error": "AI advisor unavailable", "retryable": true}`
    - _Requirements: 11.3, 5.2, 5.3, 5.4_

  - [x] 5.3 Write unit tests for `AnalysisResult` record
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/AnalysisResultTest.java`
    - Test defensive copy: mutating the original list passed to constructor does not affect the record's list
    - Test that `concentrationWarnings()` and `rebalancingSuggestions()` return unmodifiable lists
    - _Requirements: 1.3, 1.5_

  - [x] 5.4 Write unit test for `AdvisorUnavailableException`
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/AdvisorUnavailableExceptionTest.java`
    - Test message-only constructor preserves message
    - Test message+cause constructor preserves both message and cause
    - _Requirements: 9.1_

- [-] 6. Architectural dependency check and property-based tests
  - [x] 6.1 Write architectural dependency check test
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/NoAiImportsTest.java`
    - Read source files for `PortfolioAdvisor.java`, `AnalysisResult.java`, and `MockPortfolioAdvisor.java`
    - Assert none contain imports from `org.springframework.ai`, `com.ollama`, `software.amazon.awssdk`, or Bedrock packages
    - _Requirements: 11.5, 1.1, 10.3, 10.4_

  - [ ] 6.2 Write property-based test for AnalysisResult invariants
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/AnalysisResultPropertyTest.java`
    - **Property 1: AnalysisResult invariants hold for any portfolio**
    - Generate random portfolios (0–10 holdings, random tickers/quantities) using `@RepeatedTest(100)` or jqwik `@Property`
    - Verify: empty portfolio → riskScore 0; non-empty → riskScore in [1, 100]; rebalancingSuggestions.size() ≤ 3; lists are non-null
    - **Validates: Requirements 1.3, 1.4, 11.4**

  - [ ] 6.3 Write property-based test for MockPortfolioAdvisor determinism
    - Create `portfolio-service/src/test/java/com/wealth/portfolio/ai/MockPortfolioAdvisorPropertyTest.java`
    - **Property 2: MockPortfolioAdvisor returns non-empty analysis for any non-empty portfolio**
    - Generate random non-empty portfolios (1–10 holdings, random tickers/quantities) using `@RepeatedTest(100)` or jqwik `@Property`
    - Verify: riskScore > 0, at least one concentrationWarning, at least one rebalancingSuggestion
    - **Validates: Requirements 2.3, 2.5**

  - [ ] 6.4 Write property-based test for AdvisorUnavailableException
    - Add to `portfolio-service/src/test/java/com/wealth/portfolio/AdvisorUnavailableExceptionTest.java` or create separate file
    - **Property 3: AdvisorUnavailableException preserves message and cause**
    - Generate random non-null message strings and Throwable causes using `@RepeatedTest(100)`
    - Verify: `getMessage()` returns the message, `getCause()` returns the cause
    - **Validates: Requirements 9.1**

- [ ] 7. Final checkpoint — Ensure all tests pass
  - Run `./gradlew :portfolio-service:test` and ensure all unit tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- All tests run via `./gradlew test` (no `@Tag("integration")` in Phase 1)
- Phase 2+ tasks (Ollama adapter, Bedrock adapter, Market Summarizer, Chat Assistant) are excluded from this plan
- Property tests use `@RepeatedTest(100)` for lightweight PBT or jqwik `@Property` if the dependency is added
- The `MockPortfolioAdvisor` lives in `com.wealth.portfolio.ai` (infrastructure adapter package) while the port interface stays in `com.wealth.portfolio` (domain package)
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
