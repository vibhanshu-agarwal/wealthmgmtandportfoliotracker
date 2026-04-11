# Requirements Document

## Introduction

The `portfolio-service` needs AI-powered analysis capabilities to provide users with risk assessments, diversification scores, rebalancing suggestions, market summaries, and a context-aware chat assistant. This feature introduces a dual-strategy AI integration using a `PortfolioAdvisor` interface with profile-scoped adapters — a `MockPortfolioAdvisor` for fast local development and CI, an `OllamaPortfolioAdvisor` for local inference, and a future `BedrockPortfolioAdvisor` for production AWS deployment via Amazon Bedrock (Anthropic Claude).

The architecture mirrors the existing `FxRateProvider` interface-plus-profile pattern in the `fx` package. Core domain logic depends only on the `PortfolioAdvisor` port; all AI vendor specifics (Spring AI, Ollama, Bedrock SDKs) are confined to infrastructure adapters selected by Spring profile. The system prompt strictly constrains the model to structural portfolio analysis — no personalised financial advice or hallucinated recommendations.

Three AI capabilities are delivered: (1) Portfolio Analyzer — on-demand risk scoring and rebalancing suggestions triggered by the user, (2) Market Summarizer — event-driven market wrap-up paragraphs generated from Kafka price events and cached in Redis, and (3) Context-Aware Chat Assistant — a natural language chat interface backed by function calling or context injection for portfolio-aware responses.

---

## Glossary

- **PortfolioAdvisor**: Domain port (interface) in `com.wealth.portfolio` that abstracts AI-powered portfolio analysis. `PortfolioService` and controllers depend only on this interface.
- **MockPortfolioAdvisor**: Infrastructure adapter implementing `PortfolioAdvisor`, active under the default profile (`!ollama & !bedrock`). Returns hardcoded, deterministic JSON responses for portfolio analysis with no AI model invocation.
- **OllamaPortfolioAdvisor**: Infrastructure adapter implementing `PortfolioAdvisor`, active under the `ollama` Spring profile. Uses Spring AI `ChatClient` to invoke a locally running Ollama model (phi3 or llama3).
- **BedrockPortfolioAdvisor**: Infrastructure adapter implementing `PortfolioAdvisor`, active under the `bedrock` Spring profile. Uses Spring AI to invoke Amazon Bedrock (Anthropic Claude 3 Sonnet or Haiku).
- **AnalysisResult**: Domain DTO returned by `PortfolioAdvisor.analyze()`, containing a risk score, concentration warnings, and rebalancing suggestions.
- **RiskScore**: An integer value from 1 to 100 representing the overall risk level of a portfolio, where 1 is lowest risk and 100 is highest risk.
- **ConcentrationWarning**: A textual warning generated when a single asset class or sector exceeds a configurable weight threshold in the portfolio.
- **RebalancingSuggestion**: An actionable recommendation to adjust portfolio allocation, limited to structural analysis (not personalised financial advice).
- **MarketSummary**: A cached paragraph summarising recent market price movements, generated from batched Kafka price events.
- **ChatRequest**: A user-submitted natural language question about their portfolio, processed by the Chat Assistant.
- **ChatResponse**: A streaming or complete text response from the Chat Assistant, contextualised with the user's portfolio data.
- **SystemPrompt**: A predefined prompt template that constrains the AI model to structural portfolio analysis only, prohibiting personalised financial advice.
- **ChatClient**: Spring AI abstraction for interacting with LLM providers, used by `OllamaPortfolioAdvisor` and `BedrockPortfolioAdvisor`.
- **Portfolio**: JPA entity in `com.wealth.portfolio` with `id` (UUID), `userId` (String), `createdAt` (Instant), and `holdings` (List of AssetHolding).
- **AssetHolding**: JPA entity with `id` (UUID), `portfolio` (Portfolio), `assetTicker` (String), and `quantity` (BigDecimal).
- **PortfolioService**: Existing Spring service in `com.wealth.portfolio` responsible for portfolio CRUD and summary computation.
- **Kafka**: Apache Kafka messaging system used for streaming market price update events.
- **Redis**: In-memory data store used for caching market summaries.
- **Spring AI**: Spring framework module providing vendor-neutral abstractions for AI/LLM integration.

---

## Requirements

### Requirement 1: PortfolioAdvisor Domain Port

**User Story:** As a portfolio service developer, I want a domain-level abstraction for AI-powered portfolio analysis, so that business logic remains decoupled from any specific AI provider.

#### Acceptance Criteria

1. THE PortfolioAdvisor interface SHALL be defined in the `com.wealth.portfolio` package and SHALL NOT import any class from Spring AI, Ollama, Bedrock, or any infrastructure package.
2. THE PortfolioAdvisor interface SHALL declare an `analyze(Portfolio portfolio)` method that accepts a Portfolio entity and returns an AnalysisResult.
3. THE AnalysisResult SHALL contain a `riskScore` (integer, 1–100), a list of `concentrationWarnings` (list of strings), and a list of `rebalancingSuggestions` (list of strings, maximum 3 items).
4. WHEN `analyze` is called with a Portfolio containing zero holdings, THE PortfolioAdvisor SHALL return an AnalysisResult with `riskScore` of 0, an empty `concentrationWarnings` list, and an empty `rebalancingSuggestions` list.
5. THE AnalysisResult SHALL be a record defined in the `com.wealth.portfolio` package with no infrastructure dependencies.

---

### Requirement 2: MockPortfolioAdvisor (Default Adapter)

**User Story:** As a developer running the service locally or in CI, I want a zero-latency mock AI advisor, so that tests and local development work without any AI model dependency.

#### Acceptance Criteria

1. WHILE neither the `ollama` nor the `bedrock` Spring profile is active, THE MockPortfolioAdvisor SHALL be the sole active implementation of PortfolioAdvisor.
2. THE MockPortfolioAdvisor SHALL be annotated with `@Profile("!ollama & !bedrock")`.
3. WHEN `analyze` is called with a non-empty Portfolio, THE MockPortfolioAdvisor SHALL return a hardcoded AnalysisResult with a deterministic `riskScore`, at least one `concentrationWarning`, and at least one `rebalancingSuggestion`.
4. THE MockPortfolioAdvisor SHALL return responses within 1 millisecond and SHALL NOT make any network calls, file I/O, or AI model invocations.
5. WHEN `analyze` is called with a Portfolio containing zero holdings, THE MockPortfolioAdvisor SHALL return an AnalysisResult with `riskScore` of 0, an empty `concentrationWarnings` list, and an empty `rebalancingSuggestions` list.

---

### Requirement 3: OllamaPortfolioAdvisor (Ollama Adapter)

**User Story:** As a developer running local AI inference, I want an Ollama-backed portfolio advisor, so that I can test real LLM analysis without cloud costs.

#### Acceptance Criteria

1. WHILE the `ollama` Spring profile is active, THE OllamaPortfolioAdvisor SHALL be the sole active implementation of PortfolioAdvisor.
2. THE OllamaPortfolioAdvisor SHALL be annotated with `@Profile("ollama")` and SHALL reside in an infrastructure package (e.g., `com.wealth.portfolio.ai`).
3. THE OllamaPortfolioAdvisor SHALL use Spring AI `ChatClient` to send the portfolio data to the configured Ollama model.
4. THE OllamaPortfolioAdvisor SHALL use a `SystemPromptTemplate` containing the instruction: "You are a wealth management assistant. Analyze the following holdings and provide a risk score (1-100), concentration warnings, and up to 3 rebalancing suggestions. Respond in JSON format only. Do not provide personalised financial advice."
5. WHEN the Ollama model returns a valid JSON response, THE OllamaPortfolioAdvisor SHALL parse the response into an AnalysisResult.
6. IF the Ollama model returns an unparseable response, THEN THE OllamaPortfolioAdvisor SHALL log the error and throw an `AdvisorUnavailableException`.
7. IF the Ollama service is unreachable, THEN THE OllamaPortfolioAdvisor SHALL log the error and throw an `AdvisorUnavailableException`.

---

### Requirement 4: Ollama Configuration

**User Story:** As a developer configuring local AI inference, I want Ollama connection settings isolated in a profile-specific config file, so that localhost URLs never leak into the shared `application.yml`.

#### Acceptance Criteria

1. THE Ollama base URL (`spring.ai.ollama.base-url`) SHALL be configured in `application-ollama.yml` and SHALL NOT appear in `application.yml` or any other profile-specific config file.
2. THE `application-ollama.yml` SHALL set `spring.ai.ollama.base-url` to `http://localhost:11434`.
3. THE `application-ollama.yml` SHALL set `spring.ai.ollama.chat.options.model` to `phi3` as the default model.
4. THE `portfolio-service/build.gradle` SHALL declare `implementation 'org.springframework.ai:spring-ai-ollama-spring-boot-starter'` as a dependency.

---

### Requirement 5: Portfolio Analyzer Endpoint

**User Story:** As an investor, I want to click "Analyze Portfolio" and receive a risk score, concentration warnings, and rebalancing suggestions, so that I can make informed decisions about my portfolio allocation.

#### Acceptance Criteria

1. WHEN a user sends a POST request to `/api/portfolios/{userId}/analyze`, THE PortfolioController SHALL retrieve the user's portfolio from PostgreSQL and invoke `PortfolioAdvisor.analyze()`.
2. THE PortfolioController SHALL return a JSON response containing `riskScore` (integer, 1–100), `concentrationWarnings` (array of strings), and `rebalancingSuggestions` (array of strings, maximum 3 items).
3. WHEN the user has no portfolio, THE PortfolioController SHALL return HTTP 404 with an error message.
4. IF `PortfolioAdvisor.analyze()` throws `AdvisorUnavailableException`, THEN THE PortfolioController SHALL return HTTP 503 with a JSON body containing `"error": "AI advisor unavailable"` and `"retryable": true`.
5. THE system prompt used by AI-backed adapters SHALL constrain the model to structural analysis only and SHALL explicitly prohibit personalised financial advice or specific buy/sell recommendations.

---

### Requirement 6: Market Summarizer

**User Story:** As an investor, I want to see a daily market wrap-up paragraph on the Market Data page, so that I can quickly understand recent market movements without reading raw price data.

#### Acceptance Criteria

1. WHEN a configurable number of price update events have been consumed from the `market-prices` Kafka topic, THE MarketSummarizer SHALL batch the events and send them to the active PortfolioAdvisor (or a dedicated summarization method) for summarization.
2. WHEN the AI model returns a summary paragraph, THE MarketSummarizer SHALL cache the result in Redis with a configurable TTL.
3. WHEN a GET request is sent to `/api/market/summary`, THE MarketController SHALL return the cached summary from Redis.
4. IF no cached summary exists, THEN THE MarketController SHALL return HTTP 204 (No Content).
5. IF the AI model is unavailable during summarization, THEN THE MarketSummarizer SHALL log the error and retain the previously cached summary without overwriting it.
6. THE MarketSummarizer SHALL be implemented as a Spring `@Service` that consumes Kafka events and SHALL NOT block the Kafka consumer thread during AI invocation.

---

### Requirement 7: Context-Aware Chat Assistant

**User Story:** As an investor, I want to ask natural language questions about my portfolio and receive contextualised answers, so that I can understand my financial position without navigating multiple screens.

#### Acceptance Criteria

1. WHEN a user sends a POST request to `/api/chat` with a JSON body containing `userId` and `message`, THE ChatController SHALL retrieve the user's portfolio summary and pass it alongside the user message to the AI model.
2. THE ChatController SHALL use context injection: the user's summarised portfolio data (holdings, total value, allocation percentages) SHALL be injected into the system prompt alongside the user's message.
3. WHEN the AI model returns a response, THE ChatController SHALL return it as a JSON body containing `response` (string).
4. IF the user has no portfolio data, THEN THE ChatController SHALL return a response indicating that no portfolio data is available and suggesting the user add holdings first.
5. THE system prompt for the Chat Assistant SHALL constrain the model to answering questions about the user's portfolio data only and SHALL prohibit generating financial advice, predictions, or recommendations beyond what the data shows.
6. IF the AI model is unavailable, THEN THE ChatController SHALL return HTTP 503 with a JSON body containing `"error": "Chat assistant unavailable"` and `"retryable": true`.

---

### Requirement 8: Bedrock Portfolio Advisor (AWS Adapter)

**User Story:** As a production deployment, I want the portfolio advisor backed by Amazon Bedrock (Anthropic Claude), so that users receive high-quality AI analysis in the cloud environment.

#### Acceptance Criteria

1. WHILE the `bedrock` Spring profile is active, THE BedrockPortfolioAdvisor SHALL be the sole active implementation of PortfolioAdvisor.
2. THE BedrockPortfolioAdvisor SHALL be annotated with `@Profile("bedrock")` and SHALL reside in an infrastructure package (e.g., `com.wealth.portfolio.ai`).
3. THE BedrockPortfolioAdvisor SHALL use Spring AI `ChatClient` to invoke Amazon Bedrock with the configured model (Anthropic Claude 3 Sonnet or Haiku).
4. THE BedrockPortfolioAdvisor SHALL use the same system prompt template as OllamaPortfolioAdvisor to ensure consistent analysis constraints across providers.
5. IF the Bedrock service returns an error or is unreachable, THEN THE BedrockPortfolioAdvisor SHALL log the error and throw an `AdvisorUnavailableException`.
6. THE Bedrock configuration (`spring.ai.bedrock.*`) SHALL be defined in `application-bedrock.yml` and SHALL NOT appear in `application.yml`.

---

### Requirement 9: Error Handling and Safety Constraints

**User Story:** As a system operator, I want AI failures handled gracefully and model outputs constrained to structural analysis, so that the system never produces hallucinated financial advice or causes 500 errors.

#### Acceptance Criteria

1. THE `AdvisorUnavailableException` SHALL be an unchecked exception (`RuntimeException`) carrying a descriptive message and the originating `Throwable` cause.
2. WHEN `GlobalExceptionHandler` handles an `AdvisorUnavailableException`, THE GlobalExceptionHandler SHALL return HTTP 503 with a JSON body containing `"error"` and `"retryable": true`.
3. WHEN an AI-backed adapter receives a response that does not conform to the expected JSON schema (missing `riskScore`, out-of-range values, or malformed JSON), THE adapter SHALL log the raw response at WARN level and throw `AdvisorUnavailableException`.
4. THE system prompt for all AI-backed adapters SHALL include the constraint: "Do not provide personalised financial advice. Do not recommend specific securities to buy or sell. Limit analysis to portfolio structure, concentration, and diversification."
5. WHEN an AI-backed adapter returns a `riskScore` outside the 1–100 range, THE adapter SHALL clamp the value to the nearest boundary (1 or 100) and log a warning.

---

### Requirement 10: Dependency and Build Configuration

**User Story:** As a developer maintaining the build, I want AI dependencies cleanly declared and isolated, so that the mock adapter works with zero additional dependencies and AI adapters only activate with their respective profiles.

#### Acceptance Criteria

1. THE `portfolio-service/build.gradle` SHALL declare `implementation 'org.springframework.ai:spring-ai-ollama-spring-boot-starter'` for Ollama support.
2. THE `portfolio-service/build.gradle` SHALL declare the Spring AI BOM in the dependency management section to align Spring AI versions.
3. THE MockPortfolioAdvisor SHALL have zero dependencies on Spring AI, Ollama, or Bedrock libraries — it SHALL use only core Java and Spring Framework annotations.
4. THE PortfolioAdvisor interface and AnalysisResult record SHALL have zero dependencies on Spring AI, Ollama, or Bedrock libraries.
5. THE `application-ollama.yml` SHALL be the only file containing Ollama-specific configuration (base URL, model name).
6. THE `application-bedrock.yml` SHALL be the only file containing Bedrock-specific configuration (region, model ID).

---

### Requirement 11: Testing Coverage

**User Story:** As a developer maintaining this feature, I want comprehensive automated tests at unit and integration levels, so that regressions are caught before deployment.

#### Acceptance Criteria

1. THE unit test suite SHALL include tests for `MockPortfolioAdvisor` verifying that it returns a valid AnalysisResult with `riskScore` between 1 and 100, non-empty `concentrationWarnings`, and non-empty `rebalancingSuggestions` for a non-empty portfolio.
2. THE unit test suite SHALL include tests for `MockPortfolioAdvisor` verifying that it returns `riskScore` of 0 and empty lists for a portfolio with zero holdings.
3. THE unit test suite SHALL include tests for the `/api/portfolios/{userId}/analyze` endpoint using MockMvc with the MockPortfolioAdvisor, verifying the JSON response structure.
4. THE unit test suite SHALL verify that `AnalysisResult.riskScore` is always within the 1–100 range (or 0 for empty portfolios) across all adapter implementations.
5. THE unit test suite SHALL verify that the PortfolioAdvisor interface, AnalysisResult record, and MockPortfolioAdvisor do not import any class from Spring AI, Ollama, or Bedrock packages.
6. ALL integration tests SHALL be annotated with `@Tag("integration")` so they run under the `integrationTest` Gradle task and not the standard `test` task.
