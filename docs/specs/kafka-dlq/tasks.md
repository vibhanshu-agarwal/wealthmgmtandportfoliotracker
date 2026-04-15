# Implementation Plan: Kafka Dead-Letter Queue (DLQ) — portfolio-service

## Overview

Harden the `portfolio-service` Kafka consumer pipeline with a Dead-Letter Topic strategy using
pure Spring Kafka primitives. Failed records are routed to `market-prices.DLT` after retries,
preventing poison pills from blocking partition progress.

## Tasks

- [x] 1. Add Testcontainers Kafka dependency to build.gradle
  - Add `testImplementation 'org.testcontainers:kafka'` to `portfolio-service/build.gradle`
  - No version pin needed — BOM is managed at root `build.gradle`
  - _Requirements: 9.1_

- [x] 2. Create MalformedEventException
  - [x] 2.1 Create `MalformedEventException` in `portfolio-service/src/main/java/com/wealth/portfolio/kafka/`
    - `final class` extending `RuntimeException`
    - Two constructors: `(String message)` and `(String message, Throwable cause)`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1_

  - [ ]\* 2.2 Write unit test for MalformedEventException constructors
    - Verify message and cause are preserved
    - _Requirements: 2.1_

- [x] 3. Register MalformedEventException as non-retryable in PortfolioKafkaConfig
  - [x] 3.1 Update `PortfolioKafkaConfig.priceUpdatedErrorHandler()` to call `handler.addNotRetryableExceptions(MalformedEventException.class)`
    - Add after `DefaultErrorHandler` is constructed, before returning
    - _Requirements: 3.1, 3.2, 3.3_

  - [x]\* 3.2 Write unit test `PortfolioKafkaConfigTest` verifying non-retryable registration
    - Instantiate the bean, publish a `MalformedEventException`-throwing listener, assert zero retries and DLT delivery
    - _Requirements: 3.1, 3.2_

- [x] 4. Update PriceUpdatedEventListener with validation and DLT logging
  - [x] 4.1 Add validation logic to `PriceUpdatedEventListener.on()`
    - Throw `MalformedEventException` when `event` is null
    - Throw `MalformedEventException` when `event.ticker()` is null or blank
    - Throw `MalformedEventException` when `event.newPrice()` is null, zero, or negative
    - Validation must precede any call to `marketPriceProjectionService.upsertLatestPrice()`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [x]\* 4.2 Write unit tests `PriceUpdatedEventListenerTest` for all validation cases
    - Null event → `MalformedEventException`, no projection call
    - Null ticker → `MalformedEventException`, no projection call
    - Blank ticker → `MalformedEventException`, no projection call
    - Null newPrice → `MalformedEventException`, no projection call
    - Zero newPrice → `MalformedEventException`, no projection call
    - Negative newPrice → `MalformedEventException`, no projection call
    - Valid event → `upsertLatestPrice` called exactly once
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 1.1_

- [x] 5. Checkpoint — Ensure unit tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 6. Configure ErrorHandlingDeserializer in application-local.yml
  - [x] 6.1 Create/update `portfolio-service/src/main/resources/application-local.yml`
    - Set `value-deserializer` to `org.springframework.kafka.support.serializer.ErrorHandlingDeserializer`
    - Set `spring.deserializer.value.delegate.class` to `org.springframework.kafka.support.serializer.JsonDeserializer`
    - Set `spring.json.trusted.packages` to `com.wealth.market.events`
    - _Requirements: 5.1_

- [x] 7. Write integration tests DlqIntegrationTest
  - [x] 7.1 Create `DlqIntegrationTest` with `@Tag("integration")` using Testcontainers Kafka
    - Use `@SpringBootTest` + `@Testcontainers` with a `KafkaContainer`
    - Wire a `KafkaProducer` for publishing test messages and a `KafkaConsumer` subscribed to `market-prices.DLT`
    - Use `Awaitility` for all async assertions
    - Use `JdbcTemplate` to assert `market_prices` table state
    - _Requirements: 8.1_

  - [x] 7.2 Test: malformed message (blank ticker) → DLT, projection not updated
    - Publish `{"ticker":"","newPrice":100}` to `market-prices`
    - Assert record arrives on `market-prices.DLT` within timeout
    - Assert `market_prices` table has no row for the blank ticker
    - _Requirements: 8.2_

  - [x] 7.3 Test: valid message → projection updated, nothing on DLT
    - Publish `{"ticker":"AAPL","newPrice":150.00}` to `market-prices`
    - Assert `market_prices` row for `AAPL` is updated
    - Assert no record arrives on `market-prices.DLT`
    - _Requirements: 8.3_

  - [x] 7.4 Test: deserialization failure (raw non-JSON bytes) → DLT, consumer survives
    - Publish raw bytes `not-valid-json` to `market-prices`
    - Assert record arrives on `market-prices.DLT`
    - Assert no exception escapes the consumer container
    - _Requirements: 8.4, 8.5_

- [x] 8. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- `MalformedEventException` lives in `com.wealth.portfolio.kafka` package (new sub-package)
- `PortfolioKafkaConfig` and `PriceUpdatedEventListener` remain in `com.wealth.portfolio`
- Integration tests use `@Tag("integration")` and run via `./gradlew integrationTest`
- No AWS SDKs, no jqwik, no property-based testing — standard JUnit 5 only
