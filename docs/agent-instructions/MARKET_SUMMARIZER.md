# Requirements Specification: Market Summarizer (Phase 2.1)

**Project:** WealthMgmt - Insight Service  
**Assigned to:** Kiro (AI Developer Agent)

## Objective
Transition `insight-service` from stateless Kafka logging to a stateful market aggregation engine using Redis to provide context for future AI inference.

---

## 1. Functional Requirements

### 1.1 Kafka Consumer Enhancement
*   **Topic:** `market-prices`
*   **Group ID:** `insight-group`
*   **Logic:** The listener must deserialize `PriceUpdatedEvent` and persist the data into Redis. It must handle `DeserializationException` gracefully using the `ErrorHandlingDeserializer` pattern.

### 1.2 Stateful Aggregation (Redis)
*   **Latest Price Store:** Maintain a Key-Value pair for the most recent price of each ticker (e.g., `ticker:BTC` -> `65000.00`).
*   **Sliding Window (Trend Data):** Maintain a Redis List or Sorted Set for each ticker containing the last 10 price points.
*   **Constraint:** Older data must be evicted (e.g., `LTRIM` at index 9) to prevent memory leaks.
*   **Trend Calculation:** Implement a utility to calculate the percentage change between the oldest and newest values in the 10-point window.

---

## 2. Technical Stack & Architecture

### 2.1 Infrastructure
*   **Persistence:** Redis (Running via Docker Compose).
*   **Spring Integration:** `spring-boot-starter-data-redis`.
*   **Serialization:** `Jackson2JsonRedisSerializer` or `StringRedisTemplate` for simplicity.

### 2.2 Data Contract (`PriceUpdatedEvent`)
```java
public record PriceUpdatedEvent(
    String ticker,
    Double price,
    Instant timestamp
) {}
```

---

## 3. Implementation Steps for Kiro

### Step 1: Redis Configuration
Create `RedisConfig.java` to configure a `RedisTemplate<String, Double>` or use the default `StringRedisTemplate`.

### Step 2: Service Layer Refactoring
Replace the existing `InsightEventListener` logic with a call to a new `MarketDataService`.

**Method `processUpdate(PriceUpdatedEvent)`:**
1.  Update `market:latest:{ticker}` key.
2.  Push price to `market:history:{ticker}` list.
3.  Trim list to size 10.

### Step 3: Insight API Extension
Update the `insight-service` REST controller to include an endpoint:

**Endpoint:** `GET /api/insights/market-summary`  
**Output:** A map of tickers and their current 10-period trend.

---

## 4. Acceptance Criteria

1.  **Resilience:** The service does not crash when receiving malformed JSON (verified by previous "broken" token issue).
2.  **Persistence:** Restarting `insight-service` does not wipe the market data (verified by checking Redis via `redis-cli`).
3.  **Correctness:** The calculated trend correctly reflects the price movement across the 10-point window.

---

> **Implementation Note for Kiro:**
> "Do not simply log the event. Use RedisTemplate to maintain a sliding window of 10 prices per ticker. Ensure the ErrorHandlingDeserializer is correctly configured in application.yml to skip 'poison pill' messages."
