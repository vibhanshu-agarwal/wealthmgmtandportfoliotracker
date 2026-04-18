# Requirements Document

## Introduction

This document defines the requirements for hardening the `portfolio-service` Kafka consumer
pipeline with a Dead-Letter Topic (DLT) strategy. The feature prevents a single malformed or
poison-pill `PriceUpdatedEvent` message from blocking partition progress indefinitely by routing
failed records to `market-prices.DLT` after a fixed number of retries. The implementation uses
pure Spring Kafka primitives — no cloud-vendor SDKs are introduced, and the `market-data-service`
producer and `common-dto` contract remain untouched.

---

## Glossary

- **Consumer**: The `PriceUpdatedEventListener` bean inside `portfolio-service` that reads from the `market-prices` Kafka topic.
- **DefaultErrorHandler**: Spring Kafka's `DefaultErrorHandler` bean configured in `PortfolioKafkaConfig`, responsible for retry logic and DLT routing.
- **DeadLetterPublishingRecoverer**: Spring Kafka component that publishes a failed `ConsumerRecord` to the DLT topic after retries are exhausted.
- **DLT**: Dead-Letter Topic — the Kafka topic `market-prices.DLT` that receives records that could not be processed successfully.
- **DLT_Listener**: The `onDlt()` method on `PriceUpdatedEventListener`, annotated with `@KafkaListener(topics = "market-prices.DLT")`.
- **ErrorHandlingDeserializer**: Spring Kafka deserializer wrapper that catches deserialization exceptions and forwards them as headers rather than crashing the consumer.
- **MalformedEventException**: A `RuntimeException` subclass thrown by the Consumer when a `PriceUpdatedEvent` fails business-level validation.
- **PortfolioKafkaConfig**: The Spring `@Configuration` class in `portfolio-service` that wires the `DefaultErrorHandler`, `DeadLetterPublishingRecoverer`, and consumer factory beans.
- **PriceUpdatedEvent**: The shared event record (`common-dto`) with fields `ticker` (String) and `newPrice` (BigDecimal).
- **ProjectionService**: `MarketPriceProjectionService` — the service that persists the latest price for a ticker to the `market_prices` table.
- **Poison Pill**: A Kafka message that consistently causes processing failure and would block partition progress if not routed to the DLT.

---

## Requirements

### Requirement 1: Valid Event Processing

**User Story:** As a portfolio service operator, I want valid `PriceUpdatedEvent` messages to be
processed and persisted, so that the market price projection table stays up to date.

#### Acceptance Criteria

1. WHEN the Consumer receives a `PriceUpdatedEvent` with a non-null, non-blank `ticker` and a
   non-null `newPrice` greater than zero, THE Consumer SHALL invoke
   `ProjectionService.upsertLatestPrice(event)` exactly once.
2. WHEN a valid `PriceUpdatedEvent` is successfully processed, THE Consumer SHALL not publish any
   record to the DLT.
3. WHEN a valid `PriceUpdatedEvent` is successfully processed, THE Consumer SHALL commit the
   Kafka offset so that the message is not redelivered.

---

### Requirement 2: Business Validation and MalformedEventException

**User Story:** As a portfolio service operator, I want invalid `PriceUpdatedEvent` messages to
be identified and rejected at the listener boundary, so that corrupt data never reaches the
projection store.

#### Acceptance Criteria

1. WHEN the Consumer receives a `PriceUpdatedEvent` where `ticker` is null, THE Consumer SHALL
   throw `MalformedEventException` and SHALL NOT call `ProjectionService.upsertLatestPrice`.
2. WHEN the Consumer receives a `PriceUpdatedEvent` where `ticker` is blank (empty or
   whitespace-only), THE Consumer SHALL throw `MalformedEventException` and SHALL NOT call
   `ProjectionService.upsertLatestPrice`.
3. WHEN the Consumer receives a `PriceUpdatedEvent` where `newPrice` is null, THE Consumer SHALL
   throw `MalformedEventException` and SHALL NOT call `ProjectionService.upsertLatestPrice`.
4. WHEN the Consumer receives a `PriceUpdatedEvent` where `newPrice` is zero, THE Consumer SHALL
   throw `MalformedEventException` and SHALL NOT call `ProjectionService.upsertLatestPrice`.
5. WHEN the Consumer receives a `PriceUpdatedEvent` where `newPrice` is negative, THE Consumer
   SHALL throw `MalformedEventException` and SHALL NOT call `ProjectionService.upsertLatestPrice`.
6. IF `MalformedEventException` is thrown, THEN THE Consumer SHALL not perform any partial write
   to the projection store.

---

### Requirement 3: Non-Retryable Routing for MalformedEventException

**User Story:** As a portfolio service operator, I want poison-pill events to be routed to the
DLT immediately without consuming retry budget, so that known-bad messages do not introduce
unnecessary delay.

#### Acceptance Criteria

1. THE PortfolioKafkaConfig SHALL register `MalformedEventException` as a non-retryable exception
   type in the `DefaultErrorHandler`.
2. WHEN the Consumer throws `MalformedEventException`, THE DefaultErrorHandler SHALL route the
   failed record to the DLT on the first failure without performing any retry attempts.
3. WHEN the Consumer throws `MalformedEventException`, THE DefaultErrorHandler SHALL invoke
   `DeadLetterPublishingRecoverer.recover(record, exception)` to publish the record to
   `market-prices.DLT`.
4. WHEN a record is routed to the DLT via `MalformedEventException`, THE Consumer SHALL commit
   the Kafka offset so that partition progress is not blocked.

---

### Requirement 4: Transient Failure Retry Policy

**User Story:** As a portfolio service operator, I want transient processing failures to be
retried a fixed number of times before the record is sent to the DLT, so that momentary
infrastructure hiccups do not cause unnecessary data loss.

#### Acceptance Criteria

1. THE PortfolioKafkaConfig SHALL configure the `DefaultErrorHandler` with a `FixedBackOff` of
   1000 milliseconds interval and a maximum of 3 retry attempts.
2. WHEN the Consumer throws a retryable exception, THE DefaultErrorHandler SHALL retry delivery
   up to 3 times with a 1000-millisecond delay between each attempt.
3. WHEN all 3 retry attempts are exhausted and the Consumer still throws a retryable exception,
   THE DefaultErrorHandler SHALL route the record to `market-prices.DLT` via
   `DeadLetterPublishingRecoverer`.
4. WHEN a record is routed to the DLT after retries are exhausted, THE Consumer SHALL commit the
   Kafka offset so that partition progress is not blocked.

---

### Requirement 5: Deserialization Failure Handling

**User Story:** As a portfolio service operator, I want Kafka messages that cannot be
deserialized into `PriceUpdatedEvent` to be routed to the DLT rather than crashing the consumer,
so that unparseable bytes do not halt partition processing.

#### Acceptance Criteria

1. THE `portfolio-service` `application-local.yml` SHALL configure the Kafka consumer
   `value-deserializer` as `ErrorHandlingDeserializer` delegating to `JsonDeserializer`, so that
   deserialization failures are captured as headers rather than thrown as uncaught exceptions.
2. WHEN the `ErrorHandlingDeserializer` fails to deserialize a Kafka message, THE
   ErrorHandlingDeserializer SHALL set a `DeserializationException` header on the record and
   deliver a null payload to the listener container.
3. WHEN the `DefaultErrorHandler` detects a `DeserializationException` header on a record, THE
   DefaultErrorHandler SHALL route the record directly to `DeadLetterPublishingRecoverer` without
   performing any retry attempts.
4. WHEN a deserialization failure is routed to the DLT, THE Consumer SHALL commit the Kafka
   offset so that the consumer does not crash or block.

---

### Requirement 6: Dead-Letter Topic Routing and Headers

**User Story:** As a portfolio service operator, I want failed records published to the DLT to
carry full diagnostic metadata, so that I can identify the root cause of failures during
incident investigation.

#### Acceptance Criteria

1. THE PortfolioKafkaConfig SHALL configure `DeadLetterPublishingRecoverer` to route failed
   records to the topic named `{source-topic}.DLT` (i.e., `market-prices.DLT`).
2. THE PortfolioKafkaConfig SHALL configure `DeadLetterPublishingRecoverer` to preserve partition
   affinity, routing each failed record to the DLT partition that matches the source partition.
3. WHEN a record is published to `market-prices.DLT`, THE DeadLetterPublishingRecoverer SHALL
   attach the following Spring Kafka headers: `kafka_dlt-exception-fqcn`,
   `kafka_dlt-exception-message`, `kafka_dlt-original-topic`, `kafka_dlt-original-partition`,
   `kafka_dlt-original-offset`, and `kafka_dlt-original-timestamp`.
4. WHEN a record is published to `market-prices.DLT`, THE DeadLetterPublishingRecoverer SHALL
   preserve the original raw bytes of the failed record as the DLT message value.

---

### Requirement 7: DLT Consumer Listener

**User Story:** As a portfolio service operator, I want a dedicated listener on the DLT that
logs failed records with their diagnostic context, so that failures are observable without
requiring manual Kafka tooling.

#### Acceptance Criteria

1. THE DLT_Listener SHALL be annotated with `@KafkaListener(topics = "market-prices.DLT",
groupId = "portfolio-group-dlt")` and SHALL consume all records published to
   `market-prices.DLT`.
2. WHEN the DLT_Listener receives a record, THE DLT_Listener SHALL log an error-level message
   that includes the received topic, partition, and offset.
3. THE DLT_Listener SHALL use a separate consumer group (`portfolio-group-dlt`) from the main
   consumer (`portfolio-group`) so that DLT consumption does not interfere with main topic
   processing.
4. IF the DLT_Listener throws an exception, THEN THE DLT_Listener SHALL not route the record to
   any further topic, as the DLT is a terminal sink requiring operator intervention.

---

### Requirement 8: Testcontainers Integration Tests

**User Story:** As a developer, I want automated integration tests that verify the full DLQ
pipeline end-to-end against a real Kafka broker, so that I can be confident the retry and
routing behaviour is correct before deploying.

#### Acceptance Criteria

1. THE `DlqIntegrationTest` SHALL be annotated with `@Tag("integration")` and SHALL use
   Testcontainers Kafka to provide a real Kafka broker for all test scenarios.
2. WHEN a `PriceUpdatedEvent` with a blank ticker is published to `market-prices`, THE
   `DlqIntegrationTest` SHALL assert that the record arrives on `market-prices.DLT` within the
   assertion timeout and that the `market_prices` projection table is not updated.
3. WHEN a well-formed `PriceUpdatedEvent` is published to `market-prices`, THE
   `DlqIntegrationTest` SHALL assert that the `market_prices` projection table is updated and
   that no record arrives on `market-prices.DLT`.
4. WHEN raw bytes that are not valid JSON are published to `market-prices`, THE
   `DlqIntegrationTest` SHALL assert that the record arrives on `market-prices.DLT` and that no
   exception escapes the consumer container.
5. THE `DlqIntegrationTest` SHALL use `Awaitility` for all asynchronous assertions and SHALL use
   `KafkaTestUtils.getRecords()` with a dedicated `KafkaConsumer` subscribed to
   `market-prices.DLT` to verify DLT delivery.

---

### Requirement 9: Build Dependency

**User Story:** As a developer, I want the Testcontainers Kafka module available in the
`portfolio-service` test classpath, so that integration tests can spin up a real Kafka broker
without a version conflict.

#### Acceptance Criteria

1. THE `portfolio-service/build.gradle` SHALL declare `testImplementation 'org.testcontainers:kafka'`
   without a version pin, relying on the BOM managed at the root `build.gradle`.
