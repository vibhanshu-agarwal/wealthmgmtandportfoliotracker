# Phase 3 Changes — 2026-04-13 v1

## AI Insight Integration, Chat Bot, and Full Test Coverage in insight-service

Implements the AI Insight Integration (AiInsightService + Ollama/Mock/Bedrock adapters), the Chat Bot (`POST /api/chat`), per-ticker market summary endpoint, TickerSummary AI enrichment, gateway routing for `/api/chat`, and comprehensive unit/property-based/integration test coverage.

---

## Summary

### 1. Spec Realignment

The existing spec at `.kiro/specs/ai-portfolio-advisor/` was completely overhauled to reflect the unified master requirements across three features (Market Summarizer, AI Insight Integration, Chat Bot), all scoped to `insight-service`. The previous spec was portfolio-service focused and covered only Phase 1 foundation work.

- `requirements.md` — 13 requirements. Requirements 1–2 and 12 marked ✅ IMPLEMENTED (Phase 3 baseline).
- `design.md` — Rewritten for insight-service architecture with Mermaid diagrams, 15 components, 9 correctness properties.
- `tasks.md` — Tasks 1–2 marked done. Tasks 3–14 are the new implementation plan.

### 2. Per-Ticker Endpoint (Task 3)

- Added `MarketDataService.getTickerSummary(String ticker)` — public accessor delegating to `buildTickerSummary()`.
- Added `GET /api/insights/market-summary/{ticker}` to `InsightController` — returns single `TickerSummary` or HTTP 404 with `{"error": "Ticker not found"}`.

### 3. AiInsightService Interface and Adapters (Task 4)

- Created `AiInsightService` interface in `com.wealth.insight` — single method `String getSentiment(String ticker)`, throws `AdvisorUnavailableException`.
- Created `MockAiInsightService` (`@Profile("!ollama & !bedrock")`) — deterministic Neutral sentiment, zero Spring AI dependencies.
- Created `MockBedrockAiInsightService` (`@Profile("bedrock")`) — logs deferral message, returns randomized dummy sentiment. Placeholder for future Bedrock integration with no actual Spring AI Bedrock dependency.

### 4. TickerSummary Extension and Controller Enrichment (Task 5)

- Added nullable `aiSummary` field to `TickerSummary` record.
- Updated all `MarketDataService.buildTickerSummary()` calls to pass `null` for `aiSummary` (controller enriches).
- Rewrote `InsightController` to inject `AiInsightService` and enrich both endpoints via `enrichWithAiSummary()`. Catches `AdvisorUnavailableException` per-ticker — sets `aiSummary` to `null` on failure, always returns HTTP 200 with price data.

### 5. OllamaAiInsightService — Real LLM Adapter (Task 8)

- Spring AI dependencies (`spring-ai-bom:2.0.0-M4`, `spring-ai-starter-model-ollama`, `spring-ai-client-chat`) and `application-ollama.yml` (phi3 model, temperature 0.2) were already in place from prior work.
- Created `OllamaAiInsightService` (`@Profile("ollama")`) — fetches `TickerSummary` from `MarketDataService`, builds prompt with ticker/prices/trend, calls Ollama via `ChatClient`, throws `AdvisorUnavailableException` on empty response or failure.
- `buildPrompt()` is package-private for testability.

### 6. Chat Bot (Task 7)

- Created `ChatRequest` and `ChatResponse` records in `com.wealth.insight.dto`.
- Created `ChatController` at `POST /api/chat` — stateless conversational endpoint.
  - Resolution order: explicit `ticker` field → `extractTicker(message)` → "please specify" response.
  - Unknown ticker → "no data available" response.
  - AI failure → response with price data + "AI temporarily unavailable" note.
- `extractTicker()` — static method scanning for uppercase 1–5 letter tokens, filtering a stop-word list (58 common English words).

### 7. Gateway Route (Task 9)

- Added `insight-chat` route predicate (`Path=/api/chat/**` → `${INSIGHT_SERVICE_URL:http://localhost:8083}`) in `api-gateway/src/main/resources/application.yml`.

### 8. Unit Tests (Task 11)

- `InsightControllerTest` — per-ticker 200/404, market-summary AI enrichment, AI failure graceful degradation (6 tests).
- `MockAiInsightServiceTest` — deterministic output, ticker inclusion, Neutral sentiment, two sentences (4 tests).
- `ChatControllerTest` — explicit ticker, extracted ticker, no ticker, unknown ticker, AI failure (5 tests).
- `TickerExtractionTest` — stop words, valid extraction, multiple tickers, null/blank, punctuation, normalization (9 tests).
- `GlobalExceptionHandlerTest` — 503 with retryable for `AdvisorUnavailableException`, 404 for `PortfolioNotFoundException` (2 tests).

### 9. Property-Based Tests (Task 12)

All use `@RepeatedTest(100)` with `ThreadLocalRandom` for input generation (consistent with existing project patterns).

- `SlidingWindowPropertyTest` — LTRIM always caps at WINDOW_SIZE; leftPush puts newest at head (mocked Redis).
- `MarketDataServicePropertyTest` — `parsePrice` never throws for any random input; `calculateTrend` matches formula for random price lists.
- `OllamaAiInsightServicePropertyTest` — `buildPrompt` always contains ticker, price data, and trend.
- `MockAiInsightServicePropertyTest` — `getSentiment` always contains ticker and "Neutral".
- `GracefulDegradationPropertyTest` — AI failure returns HTTP 200 with price data intact and null `aiSummary`.
- `TickerExtractionPropertyTest` — known tickers found in arbitrary text; first ticker wins; stop-word-only messages return null.

### 10. Integration Tests (Task 13)

- `MarketSummaryIntegrationTest` (`@Tag("integration")`, Testcontainers Redis) — round-trip `processUpdate` → `getMarketSummary`, empty Redis, sliding window trims to 10, per-ticker known/unknown, mock AI sentiment, chat flow.
- Fixed `insight-service/build.gradle` — wired `integrationTest` task to test source set (`testClassesDirs`, `classpath`), matching portfolio-service and api-gateway patterns.
- `MarketDataService.parsePrice()` visibility changed from `private` to package-private for property test access.

---

## Files Changed

| File                                                                                          | Change                                                                            |
| --------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `.kiro/specs/ai-portfolio-advisor/requirements.md`                                            | Rewritten — 13 requirements covering Market Summarizer, AI Insight, Chat Bot      |
| `.kiro/specs/ai-portfolio-advisor/design.md`                                                  | Rewritten — insight-service architecture, 15 components, 9 correctness properties |
| `.kiro/specs/ai-portfolio-advisor/tasks.md`                                                   | Rewritten — Tasks 1–2 done, Tasks 3–14 pending → all now complete                 |
| `insight-service/build.gradle`                                                                | Added `integrationTest` task wiring (`testClassesDirs`, `classpath`)              |
| `insight-service/src/main/java/.../AiInsightService.java`                                     | New — domain port interface for market sentiment                                  |
| `insight-service/src/main/java/.../ChatController.java`                                       | New — `POST /api/chat` with ticker extraction                                     |
| `insight-service/src/main/java/.../MarketDataService.java`                                    | Added `getTickerSummary()` public method; `parsePrice()` → package-private        |
| `insight-service/src/main/java/.../InsightController.java`                                    | Rewritten — injected `AiInsightService`, added per-ticker endpoint, AI enrichment |
| `insight-service/src/main/java/.../dto/TickerSummary.java`                                    | Added `aiSummary` field (nullable)                                                |
| `insight-service/src/main/java/.../dto/ChatRequest.java`                                      | New — `record ChatRequest(String message, String ticker)`                         |
| `insight-service/src/main/java/.../dto/ChatResponse.java`                                     | New — `record ChatResponse(String response)`                                      |
| `insight-service/src/main/java/.../infrastructure/ai/MockAiInsightService.java`               | New — default mock (`@Profile("!ollama & !bedrock")`)                             |
| `insight-service/src/main/java/.../infrastructure/ai/MockBedrockAiInsightService.java`        | New — Bedrock placeholder (`@Profile("bedrock")`)                                 |
| `insight-service/src/main/java/.../infrastructure/ai/OllamaAiInsightService.java`             | New — Ollama LLM adapter (`@Profile("ollama")`)                                   |
| `api-gateway/src/main/resources/application.yml`                                              | Added `insight-chat` route for `/api/chat/**`                                     |
| `insight-service/src/test/java/.../InsightControllerTest.java`                                | New — 6 unit tests                                                                |
| `insight-service/src/test/java/.../ChatControllerTest.java`                                   | New — 5 unit tests                                                                |
| `insight-service/src/test/java/.../TickerExtractionTest.java`                                 | New — 9 unit tests                                                                |
| `insight-service/src/test/java/.../GlobalExceptionHandlerTest.java`                           | New — 2 unit tests                                                                |
| `insight-service/src/test/java/.../SlidingWindowPropertyTest.java`                            | New — 2 × 100 property tests                                                      |
| `insight-service/src/test/java/.../MarketDataServicePropertyTest.java`                        | New — 2 × 100 property + 5 example tests                                          |
| `insight-service/src/test/java/.../GracefulDegradationPropertyTest.java`                      | New — 1 × 100 property tests                                                      |
| `insight-service/src/test/java/.../TickerExtractionPropertyTest.java`                         | New — 3 × 100 property tests                                                      |
| `insight-service/src/test/java/.../infrastructure/ai/MockAiInsightServiceTest.java`           | New — 4 unit tests                                                                |
| `insight-service/src/test/java/.../infrastructure/ai/MockAiInsightServicePropertyTest.java`   | New — 1 × 100 property tests                                                      |
| `insight-service/src/test/java/.../infrastructure/ai/OllamaAiInsightServicePropertyTest.java` | New — 2 × 100 property tests                                                      |
| `insight-service/src/test/java/.../MarketSummaryIntegrationTest.java`                         | New — 7 integration tests (Testcontainers Redis)                                  |

---

## Verification

- `./gradlew compileJava` → BUILD SUCCESSFUL (all modules)
- `./gradlew test` → BUILD SUCCESSFUL (all unit + property tests across all modules)
- `./gradlew :insight-service:integrationTest` → BUILD SUCCESSFUL (7 integration tests with Testcontainers Redis)
