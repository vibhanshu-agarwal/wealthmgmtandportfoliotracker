# Requirements Document

## Introduction

The `insight-service` is the home for all AI-powered market analysis and conversational features in the Wealth Management & Portfolio Tracker platform. This document covers the full feature set: (1) Market Summarizer — a stateful Redis aggregation engine with REST endpoints for per-ticker and all-ticker summaries, (2) AI Insight Integration — an LLM-backed service that generates short sentiment analyses from price history, and (3) Chat Bot — a stateless conversational REST endpoint that routes user questions to the appropriate AI insight.

The `insight-service` already contains a hexagonal architecture foundation: the `InsightAdvisor` domain port in `com.wealth.insight.advisor` with `MockInsightAdvisor` (default) and `OllamaInsightAdvisor` (local inference) adapters, plus a `MarketDataService` backed by Redis for stateful price aggregation. The Market Summarizer is largely implemented (Phase 3). The AI Insight Integration and Chat Bot are not yet started.

A separate `PortfolioAdvisor` domain port exists in `portfolio-service` for portfolio-level risk analysis. That concern remains in `portfolio-service` and is outside the scope of this document except where cross-service interaction is noted.

---

## Glossary

- **Insight_Service**: The Spring Boot microservice (`com.wealth.insight`) responsible for market data aggregation, AI-powered sentiment analysis, and conversational chat. Runs on port 8083.
- **MarketDataService**: Spring `@Service` in `com.wealth.insight` that maintains stateful Redis-backed price aggregation per ticker. Stores latest price and a sliding window of the last 10 prices.
- **AiInsightService**: New Spring `@Service` in `com.wealth.insight` that acts as the integration layer with an LLM provider. Retrieves price history from MarketDataService, constructs a prompt, and returns a 2-sentence sentiment analysis.
- **InsightAdvisor**: Existing domain port (interface) in `com.wealth.insight.advisor` that abstracts AI-powered portfolio analysis. Used by `InsightService` for portfolio risk scoring. Distinct from the new AiInsightService.
- **TickerSummary**: Existing response record in `com.wealth.insight.dto` containing `ticker`, `latestPrice`, `priceHistory`, and `trendPercent`. Will be extended with an `aiSummary` field.
- **ChatController**: New REST controller exposing `POST /api/chat` for stateless conversational interaction.
- **ChatRequest**: New DTO containing a `message` (String, required) and an optional `ticker` (String) field.
- **ChatResponse**: New DTO containing a `response` (String) field with conversational plain-text wrapping the insight data.
- **PriceUpdatedEvent**: Shared Kafka event from `common-dto` containing `ticker` and `newPrice`. Published by `market-data-service`, consumed by `InsightEventListener`.
- **Redis**: In-memory data store used for stateful price aggregation. Keys: `market:latest:{ticker}` (latest price), `market:history:{ticker}` (sliding window list of last 10 prices).
- **Kafka**: Apache Kafka messaging system. Topic `market-prices` carries `PriceUpdatedEvent` messages.
- **Trend_Percent**: Percentage change calculated as `((newest - oldest) / oldest) * 100` from the sliding window. Returns null when fewer than 2 data points exist.
- **Sentiment_Category**: One of three plain-text labels — Bullish, Bearish, or Neutral — assigned by the LLM based on price history and trend data.
- **Spring_AI**: Spring framework module providing vendor-neutral abstractions for LLM integration via `ChatClient`.
- **AdvisorUnavailableException**: Existing unchecked exception in `com.wealth.insight.advisor` thrown when an AI service is unreachable or returns an unparseable response.
- **API_Gateway**: Spring Cloud Gateway service routing external traffic to backend services. Route: `/api/insights/**` → `insight-service`.

---

## Requirements

### Requirement 1: Stateful Price Aggregation Engine ✅ IMPLEMENTED

**User Story:** As an investor, I want the system to maintain up-to-date price data per ticker with a sliding window of recent prices, so that market summaries and trend calculations are always available.

#### Acceptance Criteria

1. WHEN a `PriceUpdatedEvent` is consumed from the `market-prices` Kafka topic, THE MarketDataService SHALL store the latest price in Redis under the key `market:latest:{ticker}`. ✅
2. WHEN a `PriceUpdatedEvent` is consumed, THE MarketDataService SHALL prepend the price to the sliding window list at `market:history:{ticker}` and trim the list to the most recent 10 entries. ✅
3. THE MarketDataService SHALL use `StringRedisTemplate` for all Redis operations and SHALL NOT require a custom `RedisConfig` bean. ✅
4. WHEN the Redis connection configuration is resolved, THE Insight_Service SHALL use environment variable overrides (`SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`) with localhost defaults in the base `application.yml`. ✅
5. IF a price string from Redis is null, blank, or not a valid number, THEN THE MarketDataService SHALL log a warning and skip the malformed value instead of throwing an exception. ✅
6. WHEN building a summary for a single ticker, THE MarketDataService SHALL isolate failures per ticker using a try-catch so that one bad key does not prevent summaries for other tickers. ✅

---

### Requirement 2: All-Ticker Market Summary Endpoint ✅ IMPLEMENTED

**User Story:** As an investor, I want to retrieve a summary of all tracked tickers in a single API call, so that I can see the full market picture at a glance.

#### Acceptance Criteria

1. WHEN a GET request is sent to `/api/insights/market-summary`, THE InsightController SHALL return a JSON map of all tracked tickers, each containing a TickerSummary with `ticker`, `latestPrice`, `priceHistory`, and `trendPercent`. ✅
2. WHEN no tickers are tracked in Redis, THE InsightController SHALL return HTTP 200 with an empty JSON object `{}`. ✅
3. THE Trend_Percent for each ticker SHALL be calculated as `((newest - oldest) / oldest) * 100`, rounded to 2 decimal places. ✅
4. IF a ticker has fewer than 2 data points in the sliding window, THEN THE MarketDataService SHALL return `null` for `trendPercent`. ✅
5. IF the market-summary endpoint encounters an unexpected error, THEN THE InsightController SHALL log the error and return HTTP 500. ✅

---

### Requirement 3: Per-Ticker Market Summary Endpoint

**User Story:** As a developer building the AI Insight Integration, I want to retrieve the price history and trend for a single ticker, so that the AiInsightService can fetch data for a specific ticker without loading all tickers.

#### Acceptance Criteria

1. WHEN a GET request is sent to `/api/insights/market-summary/{ticker}`, THE InsightController SHALL return a single TickerSummary JSON object for the requested ticker.
2. IF the requested ticker has no data in Redis, THEN THE InsightController SHALL return HTTP 404 with a JSON body containing `"error": "Ticker not found"`.
3. THE per-ticker endpoint SHALL reuse the existing `MarketDataService.buildTickerSummary()` method (or an equivalent public method) to construct the response.
4. THE per-ticker endpoint response SHALL include the same fields as the all-ticker response: `ticker`, `latestPrice`, `priceHistory`, `trendPercent`.

---

### Requirement 4: AI Insight Service — LLM Integration Layer

**User Story:** As an investor, I want to see a short AI-generated sentiment analysis for each ticker, so that I can quickly understand whether the market trend is bullish, bearish, or neutral without interpreting raw numbers.

#### Acceptance Criteria

1. THE AiInsightService SHALL be a Spring `@Service` class in the `com.wealth.insight` package that accepts a ticker symbol and returns a plain-text sentiment summary.
2. WHEN queried for a specific ticker, THE AiInsightService SHALL retrieve the 10-point price history and current Trend_Percent from the MarketDataService.
3. THE AiInsightService SHALL construct a prompt containing the ticker symbol, the price history list, and the Trend_Percent, and send the prompt to the configured LLM via Spring_AI `ChatClient`.
4. THE LLM prompt SHALL instruct the model to generate a 2-sentence plain-text market sentiment analysis categorizing the ticker as Bullish, Bearish, or Neutral.
5. WHEN the LLM returns a valid response, THE AiInsightService SHALL return the response text as a String.
6. IF the LLM is unreachable or returns an empty response, THEN THE AiInsightService SHALL log the error and throw an `AdvisorUnavailableException`.
7. THE AiInsightService SHALL use profile-scoped configuration so that a mock implementation can be activated for local development and CI without requiring an LLM.

---

### Requirement 5: Mock AI Insight Implementation

**User Story:** As a developer running the service locally or in CI, I want a zero-latency mock AI insight provider, so that tests and local development work without any LLM dependency.

#### Acceptance Criteria

1. WHILE neither the `ollama` nor the `bedrock` Spring profile is active, THE mock AiInsightService implementation SHALL be the active provider of AI sentiment summaries.
2. WHEN queried for a ticker, THE mock implementation SHALL return a deterministic 2-sentence string that includes the ticker symbol and a hardcoded Neutral sentiment category.
3. THE mock implementation SHALL return responses within 1 millisecond and SHALL NOT make any network calls or LLM invocations.
4. THE mock implementation SHALL follow the same interface contract as the real implementation so that callers are unaware of the substitution.

---

### Requirement 6: Extended TickerSummary with AI Summary Field

**User Story:** As an investor viewing market data, I want the ticker summary response to include an AI-generated sentiment analysis alongside the price data, so that I get both quantitative and qualitative information in one response.

#### Acceptance Criteria

1. THE TickerSummary record SHALL include a new `aiSummary` field of type String that contains the AI-generated sentiment text.
2. WHEN the market-summary endpoint is called, THE InsightController SHALL populate the `aiSummary` field for each ticker by invoking the AiInsightService.
3. WHEN the per-ticker endpoint is called, THE InsightController SHALL populate the `aiSummary` field for the requested ticker.
4. IF the AiInsightService throws an `AdvisorUnavailableException`, THEN THE InsightController SHALL set the `aiSummary` field to `null` and still return the price data with HTTP 200.
5. THE `aiSummary` field SHALL be nullable — a `null` value indicates that AI analysis is temporarily unavailable.

---

### Requirement 7: Chat Bot — Stateless Conversational Endpoint

**User Story:** As an investor, I want to ask natural language questions like "How is AAPL doing?" and receive a conversational response with the latest AI insight, so that I can interact with market data without navigating the dashboard.

#### Acceptance Criteria

1. WHEN a POST request is sent to `/api/chat` with a JSON body containing a `message` field (String, required) and an optional `ticker` field (String), THE ChatController SHALL process the request and return a ChatResponse.
2. WHEN the `ticker` field is provided in the ChatRequest, THE ChatController SHALL use the provided ticker to fetch the AI insight summary from the AiInsightService.
3. WHEN the `ticker` field is not provided, THE ChatController SHALL attempt to extract a ticker symbol from the `message` text using pattern matching (uppercase 1-5 letter words matching known ticker patterns).
4. WHEN a ticker is identified (from the field or extracted from the message), THE ChatController SHALL fetch the latest TickerSummary including the `aiSummary` and wrap the data in a conversational plain-text response.
5. IF no ticker can be identified from the request, THEN THE ChatController SHALL return a ChatResponse with a message asking the user to specify a ticker symbol.
6. IF the identified ticker has no data in the system, THEN THE ChatController SHALL return a ChatResponse indicating that no data is available for the requested ticker.
7. THE ChatController SHALL be stateless — each request is independent with no session or conversation history maintained.
8. THE ChatResponse JSON SHALL contain a single `response` field (String) with conversational plain-text wrapping the insight data.

---

### Requirement 8: Chat Bot — Ticker Extraction from Natural Language

**User Story:** As an investor, I want to type casual questions without specifying a ticker field, so that the chat experience feels natural and conversational.

#### Acceptance Criteria

1. WHEN the `ticker` field is absent from the ChatRequest, THE ChatController SHALL scan the `message` text for potential ticker symbols.
2. THE ticker extraction logic SHALL identify uppercase alphabetic tokens of 1 to 5 characters that appear in the message (e.g., "AAPL", "MSFT", "GOOG").
3. WHEN multiple potential tickers are found in the message, THE ChatController SHALL use the first identified ticker.
4. THE ticker extraction logic SHALL ignore common English words that match the ticker pattern (e.g., "I", "A", "THE", "HOW", "IS") by maintaining a stop-word list.
5. IF no valid ticker is extracted after filtering, THEN THE ChatController SHALL return a response asking the user to specify a ticker.

---

### Requirement 9: Resilience and Error Handling

**User Story:** As a system operator, I want all AI and data failures handled gracefully, so that the system degrades without returning 500 errors to users.

#### Acceptance Criteria

1. IF the AiInsightService encounters an LLM failure while populating `aiSummary` for the market-summary endpoint, THEN THE InsightController SHALL return the price data with `aiSummary` set to `null` instead of failing the entire response.
2. IF the AiInsightService encounters an LLM failure while serving a chat request, THEN THE ChatController SHALL return a ChatResponse with a message indicating that AI analysis is temporarily unavailable.
3. WHEN the `GlobalExceptionHandler` handles an `AdvisorUnavailableException`, THE GlobalExceptionHandler SHALL return HTTP 503 with a JSON body containing `"error"` and `"retryable": true`.
4. IF the Redis connection is unavailable, THEN THE MarketDataService SHALL allow the exception to propagate so that Spring Boot health checks reflect the degraded state. ✅
5. THE Insight_Service `application.yml` SHALL include `server.error.include-message: always` for diagnostic purposes. ✅

---

### Requirement 10: Configuration and Dependencies

**User Story:** As a developer maintaining the build, I want AI dependencies cleanly declared and profile-isolated, so that the mock implementation works with zero additional dependencies and LLM adapters only activate with their respective profiles.

#### Acceptance Criteria

1. THE `insight-service/build.gradle` SHALL declare `spring-ai-ollama-spring-boot-starter` for Ollama LLM support.
2. THE `insight-service/build.gradle` SHALL declare the Spring AI BOM in the dependency management section to align Spring AI versions.
3. THE Ollama base URL and model configuration SHALL be defined in `application-ollama.yml` and SHALL NOT appear in the base `application.yml`.
4. THE mock AI insight implementation SHALL have zero dependencies on Spring AI or Ollama libraries — only core Java and Spring Framework annotations.
5. THE `application.yml` SHALL configure Kafka consumer properties including `ErrorHandlingDeserializer` for poison-pill resilience. ✅
6. THE `application.yml` SHALL configure Redis connection using environment variable overrides with localhost defaults. ✅

---

### Requirement 11: Testing Coverage

**User Story:** As a developer maintaining this feature, I want comprehensive automated tests at unit and integration levels, so that regressions are caught before deployment.

#### Acceptance Criteria

1. THE unit test suite SHALL include tests for the per-ticker endpoint verifying HTTP 200 with correct TickerSummary JSON structure for a known ticker.
2. THE unit test suite SHALL include tests for the per-ticker endpoint verifying HTTP 404 for an unknown ticker.
3. THE unit test suite SHALL include tests for the AiInsightService verifying that the mock implementation returns a deterministic 2-sentence sentiment string containing the ticker symbol.
4. THE unit test suite SHALL include tests for the ChatController verifying that a request with an explicit `ticker` field returns a conversational response containing the ticker data.
5. THE unit test suite SHALL include tests for the ChatController verifying that a request with no identifiable ticker returns a prompt asking the user to specify one.
6. THE unit test suite SHALL include tests for the ticker extraction logic verifying that common stop words are filtered and valid tickers are extracted.
7. THE unit test suite SHALL include a property-based test for the ticker extraction logic: FOR ALL strings containing a known ticker symbol surrounded by arbitrary text, the extraction logic SHALL identify the ticker.
8. ALL integration tests SHALL be annotated with `@Tag("integration")` so they run under the `integrationTest` Gradle task and not the standard `test` task.

---

### Requirement 12: Gateway Routing ✅ IMPLEMENTED

**User Story:** As a frontend developer, I want all insight-service endpoints accessible through the API Gateway, so that the frontend only communicates with a single entry point.

#### Acceptance Criteria

1. THE API_Gateway route configuration SHALL map `/api/insights/**` to the Insight_Service. ✅
2. THE API_Gateway route SHALL use environment variable overrides for the Insight_Service URI to support both local and Docker networking. ✅
3. WHEN the Insight_Service adds new endpoints under `/api/insights/` or `/api/chat`, THE API_Gateway SHALL route them without requiring gateway configuration changes (wildcard routing). ✅ (for `/api/insights/**`; `/api/chat` may need a new route)
4. IF the `/api/chat` endpoint path is outside the existing `/api/insights/**` route, THEN THE API_Gateway SHALL add a new route predicate for `/api/chat/**` pointing to the Insight_Service.

---

### Requirement 13: Known Issue — Gateway Networking Mismatch (Documentation)

**User Story:** As a developer running the system locally, I want the known gateway networking issue documented, so that I do not waste time debugging 404 errors when running mixed local/Docker setups.

#### Acceptance Criteria

1. WHEN the Insight_Service runs on the host machine and the API_Gateway runs in Docker, THE system documentation SHALL note that the gateway container cannot resolve `localhost` to the host machine, resulting in 404 errors.
2. THE documentation SHALL recommend running both services in the same context (both in Docker or both locally) as the workaround.
