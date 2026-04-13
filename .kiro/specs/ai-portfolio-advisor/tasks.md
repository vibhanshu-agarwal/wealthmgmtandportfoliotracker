# Implementation Plan: Insight Service — AI Market Analysis & Chat

## Overview

This plan covers the full insight-service feature set: stateful Redis price aggregation (done), AI-powered sentiment analysis, and conversational chat. Tasks are ordered so each step builds on the previous — per-ticker endpoint first (foundation), then AI integration layer, then chat, then real LLM adapter, then gateway routing, then tests.

## Tasks

- [x] 1. Stateful Redis aggregation engine and market summary endpoint
  - [x] 1.1 Implement MarketDataService with Redis-backed price storage
    - `processUpdate(PriceUpdatedEvent)` stores latest price and prepends to sliding window
    - `market:latest:{ticker}` key-value, `market:history:{ticker}` capped list (10 entries)
    - `calculateTrend()` computes `((newest - oldest) / oldest) * 100`
    - Safety guards: `parsePrice()` wraps BigDecimal parsing, per-ticker isolation in summary loop
    - _Requirements: 1.1, 1.2, 1.5, 1.6_

  - [x] 1.2 Refactor InsightEventListener to delegate to MarketDataService
    - Kafka consumer calls `marketDataService.processUpdate(event)` instead of logging
    - _Requirements: 1.1_

  - [x] 1.3 Create TickerSummary record in `com.wealth.insight.dto`
    - Fields: `ticker`, `latestPrice`, `priceHistory`, `trendPercent`
    - _Requirements: 2.1_

  - [x] 1.4 Add `GET /api/insights/market-summary` endpoint to InsightController
    - Returns `Map<String, TickerSummary>` for all tracked tickers
    - Empty Redis returns HTTP 200 with `{}`
    - Try-catch with logging on unexpected errors returns HTTP 500
    - _Requirements: 2.1, 2.2, 2.5_

  - [x] 1.5 Configure Redis with env var pattern in `application.yml`
    - `spring.data.redis.host: ${SPRING_DATA_REDIS_HOST:localhost}`
    - Removed redundant `RedisConfig.java` — Spring Boot auto-configures `StringRedisTemplate`
    - _Requirements: 1.3, 1.4_

  - [x] 1.6 Fix API Gateway route predicate
    - Changed `/api/insight/**` to `/api/insights/**` to match controller mapping
    - _Requirements: 12.1_

  - [x] 1.7 Add error diagnostics to `application.yml`
    - `server.error.include-message: always`, `include-exception: true`
    - _Requirements: 9.5_

  - [x] 1.8 CI fixes (LocalStack removal, AWS OIDC, rate-limit test path, DlqIntegrationTest profile)
    - Removed unused LocalStack service and AWS credentials step from CI
    - Updated `RateLimitingIntegrationTest` path to `/api/insights/market-summary`
    - Added `@ActiveProfiles("local")` to `DlqIntegrationTest`

- [x] 2. Checkpoint — Phase 3 baseline verified
  - `./gradlew :insight-service:compileJava` → BUILD SUCCESSFUL
  - `./gradlew :insight-service:test` → BUILD SUCCESSFUL
  - GitHub Actions CI: all jobs green

- [x] 3. Per-ticker endpoint and MarketDataService public accessor
  - [x] 3.1 Add `getTickerSummary(String ticker)` public method to MarketDataService
    - Delegates to existing `buildTickerSummary(ticker)` (make it public or add wrapper)
    - Returns `TickerSummary` for a single ticker, or a summary with `null` latestPrice if no data
    - _Requirements: 3.3, 3.4_

  - [x] 3.2 Add `GET /api/insights/market-summary/{ticker}` endpoint to InsightController
    - Returns single `TickerSummary` JSON for the requested ticker
    - Returns HTTP 404 with `{"error": "Ticker not found"}` if no Redis data
    - _Requirements: 3.1, 3.2, 3.4_

- [x] 4. AiInsightService interface and mock implementation
  - [x] 4.1 Create `AiInsightService` interface in `com.wealth.insight`
    - Single method: `String getSentiment(String ticker)`
    - Throws `AdvisorUnavailableException` if LLM unreachable
    - _Requirements: 4.1, 4.7_

  - [x] 4.2 Create `MockAiInsightService` in `com.wealth.insight.infrastructure.ai`
    - `@Service`, `@Profile("!ollama & !bedrock")`
    - Returns deterministic string: `"{ticker} is showing Neutral sentiment. No significant price movement detected."`
    - Zero Spring AI dependencies — only core Java + Spring annotations
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 10.4_

- [x] 5. Extend TickerSummary and enrich controller responses with AI summary
  - [x] 5.1 Add `aiSummary` field to TickerSummary record
    - New field: `String aiSummary` (nullable)
    - Update `MarketDataService.buildTickerSummary()` to pass `null` for aiSummary (controller enriches)
    - _Requirements: 6.1, 6.5_

  - [x] 5.2 Update InsightController to enrich market-summary with AI summaries
    - Inject `AiInsightService` into InsightController
    - In `getMarketSummary()`: loop over summaries, call `aiInsightService.getSentiment(ticker)` per ticker
    - Catch `AdvisorUnavailableException` per-ticker → set `aiSummary` to `null`
    - Always return HTTP 200 with price data regardless of AI availability
    - _Requirements: 6.2, 6.4, 9.1_

  - [x] 5.3 Update per-ticker endpoint to include AI summary
    - Call `aiInsightService.getSentiment(ticker)` for the single ticker
    - Catch `AdvisorUnavailableException` → set `aiSummary` to `null`
    - _Requirements: 6.3, 6.4_

- [x] 6. Checkpoint — AI integration with mock adapter
  - Ensure `./gradlew :insight-service:compileJava` succeeds
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. ChatController, DTOs, and ticker extraction
  - [x] 7.1 Create `ChatRequest` and `ChatResponse` records in `com.wealth.insight.dto`
    - `ChatRequest(String message, String ticker)` — message required, ticker optional
    - `ChatResponse(String response)` — conversational plain-text
    - _Requirements: 7.1, 7.8_

  - [x] 7.2 Implement ticker extraction logic
    - Package-private static method `extractTicker(String message)` in `ChatController` (or utility class)
    - Scans for uppercase 1-5 letter tokens, filters stop words (I, A, THE, HOW, IS, etc.)
    - Returns first valid ticker or `null`
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

  - [x] 7.3 Create `ChatController` at `/api/chat`
    - `@RestController`, `@RequestMapping("/api/chat")`
    - `@PostMapping` accepting `ChatRequest`, returning `ChatResponse`
    - Resolution order: explicit `ticker` field → `extractTicker(message)` → "please specify" response
    - Unknown ticker (no Redis data) → "no data available" response
    - AI failure → response with price data + "AI temporarily unavailable" note
    - Stateless — no session or conversation history
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 9.2_

- [x] 8. OllamaAiInsightService — real LLM adapter
  - [x] 8.1 Add Spring AI Ollama dependencies to `insight-service/build.gradle`
    - Add `spring-ai-ollama-spring-boot-starter` dependency
    - Add Spring AI BOM to dependency management
    - _Requirements: 10.1, 10.2_

  - [x] 8.2 Create `application-ollama.yml` with Ollama config
    - Ollama base URL and model configuration
    - Must NOT appear in base `application.yml`
    - _Requirements: 10.3_

  - [x] 8.3 Create `OllamaAiInsightService` in `com.wealth.insight.infrastructure.ai`
    - `@Service`, `@Profile("ollama")`
    - Injects `ChatClient` and `MarketDataService`
    - `getSentiment(ticker)`: fetches `TickerSummary` via `marketDataService.getTickerSummary()`, builds prompt, calls LLM
    - System prompt instructs 2-sentence sentiment (Bullish/Bearish/Neutral)
    - Empty/null LLM response → throws `AdvisorUnavailableException`
    - Any other exception → wraps in `AdvisorUnavailableException`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 9. Gateway route for `/api/chat`
  - Add route predicate in `api-gateway/src/main/resources/application.yml`
    - `id: insight-chat`, `Path=/api/chat/**`, URI points to insight-service
    - _Requirements: 12.4_

- [x] 10. Checkpoint — Full feature compilation
  - Ensure `./gradlew :insight-service:compileJava` succeeds
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Unit tests for new components
  - [x] 11.1 Write unit tests for per-ticker endpoint
    - `GET /market-summary/{ticker}` → 200 with correct TickerSummary JSON for known ticker
    - `GET /market-summary/{ticker}` → 404 for unknown ticker
    - Use MockMvc standalone setup with mocked MarketDataService and AiInsightService
    - _Requirements: 11.1, 11.2_

  - [x] 11.2 Write unit tests for MockAiInsightService
    - Verify deterministic 2-sentence string containing ticker symbol and "Neutral"
    - _Requirements: 11.3_

  - [x] 11.3 Write unit tests for InsightController AI enrichment
    - Market-summary with AI available → aiSummary populated
    - Market-summary with AI failure → aiSummary null, price data intact, HTTP 200
    - _Requirements: 6.2, 6.4, 9.1_

  - [x] 11.4 Write unit tests for ChatController
    - Request with explicit `ticker` → conversational response with ticker data
    - Request with ticker in message (no `ticker` field) → extracts and responds
    - Request with no identifiable ticker → prompt to specify
    - Request with unknown ticker → "no data available" response
    - AI failure during chat → response with "temporarily unavailable" note
    - _Requirements: 11.4, 11.5, 7.2, 7.3, 7.5, 7.6, 9.2_

  - [x] 11.5 Write unit tests for ticker extraction logic
    - Stop words filtered: "How is the market" → null
    - Valid extraction: "How is AAPL doing" → "AAPL"
    - Multiple tickers: "Compare AAPL and MSFT" → "AAPL" (first wins)
    - _Requirements: 11.6, 8.1, 8.2, 8.3, 8.4_

  - [x] 11.6 Write unit test for GlobalExceptionHandler AdvisorUnavailableException
    - Verify HTTP 503 with `{"error": "AI advisor unavailable", "retryable": true}`
    - _Requirements: 9.3_

- [x] 12. Property-based tests
  - [x] 12.1 Write property test for sliding window size invariant
    - **Property 1: Sliding window never exceeds configured size**
    - For any sequence of PriceUpdatedEvents (1-50 events, random prices), history list ≤ 10 entries, newest at head
    - Use jqwik `@Property` with embedded Redis (or mocked StringRedisTemplate)
    - **Validates: Requirements 1.2**

  - [x] 12.2 Write property test for price parsing safety
    - **Property 2: Price parsing safety — malformed values never throw**
    - For any string (null, blank, non-numeric, unicode), `parsePrice()` returns BigDecimal or null, never throws
    - **Validates: Requirements 1.5**

  - [x] 12.3 Write property test for trend calculation correctness
    - **Property 3: Trend calculation correctness**
    - For any list of ≥2 BigDecimal prices with non-zero oldest, result equals `((first - last) / last) * 100` rounded to 2dp
    - For <2 elements or zero oldest → null
    - **Validates: Requirements 2.3, 2.4**

  - [x] 12.4 Write property test for prompt construction
    - **Property 4: Prompt construction includes all required data**
    - For any ticker and TickerSummary with non-null data, prompt contains ticker, at least one price, and trend percent
    - **Validates: Requirements 4.3**

  - [x] 12.5 Write property test for mock sentiment output
    - **Property 5: Mock sentiment contains ticker and Neutral category**
    - For any non-null ticker string, `MockAiInsightService.getSentiment()` returns string containing ticker and "Neutral"
    - **Validates: Requirements 5.2**

  - [x] 12.6 Write property test for graceful degradation
    - **Property 6: Graceful degradation preserves price data on AI failure**
    - For any TickerSummary with valid data, when AI throws, enriched response has original price fields and aiSummary=null, HTTP 200
    - **Validates: Requirements 6.4, 9.1**

  - [x] 12.7 Write property test for ticker extraction — known tickers
    - **Property 7: Ticker extraction finds known tickers in arbitrary text**
    - For any known ticker (1-5 uppercase, not stop-word) embedded in arbitrary surrounding text, `extractTicker()` returns that ticker
    - **Validates: Requirements 8.1, 8.2, 11.7**

  - [x] 12.8 Write property test for ticker extraction — first ticker wins
    - **Property 8: Ticker extraction selects the first valid ticker**
    - For any message with 2+ valid tickers, returns the first one
    - **Validates: Requirements 8.3**

  - [x] 12.9 Write property test for stop-word filtering
    - **Property 9: Stop-word filtering prevents false ticker matches**
    - For any message composed entirely of stop-list words, `extractTicker()` returns null
    - **Validates: Requirements 8.4**

- [x] 13. Integration tests
  - [x] 13.1 Write integration test for market-summary endpoint with Redis
    - `@SpringBootTest` + Testcontainers (Redis)
    - Seed Redis with test data, verify `GET /api/insights/market-summary` returns correct structure
    - Annotate with `@Tag("integration")`
    - _Requirements: 2.1, 11.8_

  - [x] 13.2 Write integration test for per-ticker endpoint with Redis
    - Seed Redis, verify `GET /api/insights/market-summary/{ticker}` returns correct TickerSummary
    - Verify unknown ticker returns 404
    - Annotate with `@Tag("integration")`
    - _Requirements: 3.1, 3.2, 11.8_

  - [x] 13.3 Write integration test for chat endpoint
    - `@SpringBootTest` with mock profile (MockAiInsightService active)
    - Seed Redis, verify `POST /api/chat` with ticker returns conversational response
    - Annotate with `@Tag("integration")`
    - _Requirements: 7.1, 11.8_

- [x] 14. Final checkpoint — Ensure all tests pass
  - Run `./gradlew :insight-service:test` and ensure all unit tests pass
  - Run `./gradlew :insight-service:integrationTest` and ensure all integration tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Tasks 1.x and 2 are already completed (Phase 3 implementation)
- The design uses Java — no language selection needed
- Property tests use jqwik (JUnit 5 compatible PBT library)
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The existing `InsightAdvisor` port (portfolio risk analysis) is NOT modified by these tasks
