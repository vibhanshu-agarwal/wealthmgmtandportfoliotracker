# 🤖 Coding Agent Instructions: Phase 2 - Cloud-Native Extraction

## 🎯 Context & Objective

You are an Expert Java/Spring Cloud Software Architect. Your task is to refactor an existing Spring Boot "Modular Monolith" (`wealthmgmtandportfoliotracker`) into a distributed, cloud-native microservices architecture.

**Current State:** A single Spring Boot application using Spring Modulith, communicating via in-memory Application Events, backed entirely by PostgreSQL.
**Target State:** Three distinct microservices, an API Gateway, Event-Driven communication via Apache Kafka, and Polyglot Persistence.

## 📐 Architectural Requirements

1. **API Gateway:** Implement Spring Cloud Gateway to route external traffic and handle rate-limiting.
2. **Microservices Extraction:** Split the codebase into standalone deployable services:
   - `portfolio-service` (Core)
   - `market-data-service` (Extraction 1)
   - `insight-service` (Extraction 2)
3. **Event-Driven Communication:** Replace all Spring Application Events (`@ApplicationModuleListener`, `@EventListener`) between these bounded contexts with **Apache Kafka**.
4. **Polyglot Persistence:**
   - `portfolio-service`: Retains **PostgreSQL** (ACID compliance).
   - `market-data-service`: Migrates to **MongoDB** (or DynamoDB local) for high-throughput time-series data.

---

## 🛠️ Execution Plan

Execute the following steps sequentially. Do not proceed to the next step until the current step compiles and passes basic validation.

### Step 1: Repository Restructuring (Multi-Module)

- Convert the current repository into a multi-module project (Maven or Gradle, matching the existing build tool).
- Create the following root-level modules:
  - `api-gateway`
  - `portfolio-service`
  - `market-data-service`
  - `insight-service`
  - `common-dto` (Create this to hold shared Kafka event records, e.g., `PriceUpdatedEvent`).
- Move the existing code into their respective service modules.

### Step 2: Infrastructure Update (`docker-compose.yml`)

- Update the root `docker-compose.yml` to support local development for the new architecture.
- **Keep:** PostgreSQL (for `portfolio-service`).
- **Add:** Apache Kafka and Zookeeper (or Kafka KRaft mode).
- **Add:** MongoDB (for `market-data-service`).
- Expose ports appropriately to avoid conflicts on the host machine.

### Step 3: Extract & Refactor `market-data-service`

1. **Dependencies:** Add Spring Web, Spring Data MongoDB, and Spring Kafka.
2. **Database Migration:** Refactor existing JPA Entities in the market module to MongoDB Documents (`@Document`). Update repositories to extend `MongoRepository`.
3. **Event Publishing:** Find where the system currently publishes internal market price updates. Refactor this to use `KafkaTemplatestring,` to publish a `PriceUpdatedEvent` to a Kafka topic named `market-prices`.
4. Configure `application.yml` for MongoDB URI and Kafka Producer settings.

### Step 4: Update `portfolio-service` (Core)

1. **Dependencies:** Add Spring Web, Spring Data JPA, and Spring Kafka.
2. **Database:** Keep existing PostgreSQL JPA configurations.
3. **Event Consumption:** Find the existing `@ApplicationModuleListener` or `@EventListener` methods that react to market price changes. Refactor these to use `@KafkaListener(topics = "market-prices", groupId = "portfolio-group")`.
4. Ensure the service processes the Kafka message and updates the Portfolio valuations in PostgreSQL asynchronously.
5. Configure `application.yml` for PostgreSQL URL and Kafka Consumer settings.

### Step 5: Extract & Refactor `insight-service`

1. **Dependencies:** Add Spring Web and Spring Kafka.
2. **Event Consumption:** Set up `@KafkaListener` to consume necessary events from both Market Data and Portfolio topics to generate insights.
3. Remove any direct database lookups into the `portfolio` or `market` schemas; this service must rely purely on its own data store or incoming Kafka event payloads.

### Step 6: Create the API Gateway (`api-gateway`)

1. **Dependencies:** Add Spring Cloud Gateway and Spring Boot Actuator.
2. **Routing:** Configure `application.yml` to route traffic based on path predicates:
   - `/api/portfolio/**` -> `http://localhost:portfolio_port`
   - `/api/market/**` -> `http://localhost:market_port`
   - `/api/insight/**` -> `http://localhost:insight_port`
3. **Resilience:** Add basic Request Rate Limiting using Redis (add Redis to `docker-compose.yml` if necessary) to protect the `market-data-service` from being overwhelmed.

### Step 7: Cleanup & Verification

- Remove the `spring-modulith` dependencies as the physical boundaries are now enforced by network microservices.
- Provide a script or a set of `curl` commands to test the end-to-end flow:
  1. Post a new market price through the API Gateway to the Market Data service.
  2. Verify the Portfolio service consumes the Kafka event.
  3. Fetch the updated Portfolio via the API Gateway to confirm eventual consistency.

## ⚠️ Constraints & Best Practices for the Agent

- **Idempotency:** Ensure Kafka consumers are idempotent. Handle potential duplicate `PriceUpdatedEvent` messages gracefully in the `portfolio-service`.
- **Shared Libraries:** Do not share domain entities between microservices. Only share DTOs/Event Schemas via the `common-dto` module.
- **Logging:** Add clear logging in the Kafka Producers and Consumers so we can trace the event flow in the console.
