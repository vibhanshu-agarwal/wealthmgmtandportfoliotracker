# 🤖 Coding Agent Instructions: Complete Phase 2 Cloud-Native Migration

## 🎯 Context & Objective
You are an Expert Java/Spring Cloud Software Architect. We are currently on the `architecture/cloud-native-extraction` branch. We have already split our Spring Modulith into a multi-module repository (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`, `common-dto`).

Your task is to implement the core distributed architecture features: **Polyglot Persistence**, **Event-Driven Communication (Kafka)**, and **API Routing**.

**Tech Stack Context:** Java 25, Spring Boot 4+, Gradle.

## 🛠️ Execution Plan

Execute these steps sequentially. Do not move to the next step without my explicit confirmation that the current step compiles and tests pass.

### Step 1: Update Infrastructure (`docker-compose.yml`)
1. Open the root `docker-compose.yml`.
2. **Keep** the existing PostgreSQL container (used by `portfolio-service`).
3. **Add** a MongoDB container (for `market-data-service`).
4. **Add** Apache Kafka (KRaft mode is preferred to avoid Zookeeper) for event messaging.
5. Ensure all necessary ports are exposed to the host machine for local development.

### Step 2: Implement Polyglot Persistence (`market-data-service`)
The Market Data service needs to move away from PostgreSQL to handle high-throughput time-series data.
1. In `market-data-service/build.gradle`, remove JPA/PostgreSQL dependencies and add `spring-boot-starter-data-mongodb`.
2. Refactor the existing JPA `@Entity` classes in this module to use MongoDB `@Document`.
3. Update the repository interfaces to extend `MongoRepository` instead of `JpaRepository`.
4. Update `market-data-service/src/main/resources/application.yml` to connect to the local MongoDB container.

### Step 3: Event-Driven Communication (Apache Kafka)
We must replace the in-memory Spring `@ApplicationModuleListener` with distributed messaging.
1. **Dependencies:** Add `spring-kafka` to `portfolio-service` and `market-data-service`.
2. **Producer (`market-data-service`):** Find the service that updates market prices. Refactor it to use `KafkaTemplate<String, PriceUpdatedEvent>` to publish messages to a topic named `market-prices`.
3. **Consumer (`portfolio-service`):** Find the service that previously listened for internal price updates. Refactor it to use `@KafkaListener(topics = "market-prices", groupId = "portfolio-group")`.
4. **Resilience:** Ensure the `portfolio-service` updates its PostgreSQL records asynchronously when a Kafka message is received.

### Step 4: Configure the API Gateway (`api-gateway`)
1. In `api-gateway/src/main/resources/application.yml`, configure Spring Cloud Gateway routes:
    - Route `/api/portfolio/**` traffic to the `portfolio-service` port.
    - Route `/api/market/**` traffic to the `market-data-service` port.
    - Route `/api/insight/**` traffic to the `insight-service` port.
2. (Optional but recommended) Add basic Request Rate Limiting to the gateway using an in-memory configuration or Redis.

### Step 5: Update Frontend Proxy Configuration
1. Open `frontend/next.config.js` (or `next.config.ts`).
2. Update the Next.js `rewrites()` function. Ensure that calls to `/api/:path*` are proxied to the **API Gateway port**, not directly to the individual backend services.

---

## ⚠️ Agent Constraints & Circuit Breakers (STRICT ENFORCEMENT)
- **DTO Sharing:** The Kafka event payloads (e.g., `PriceUpdatedEvent`) MUST live in the `common-dto` module so both the Producer and Consumer can serialize/deserialize them properly. Do not duplicate classes.
- **Circuit Breaker (Dependencies):** Rely on the Spring Boot Dependency Management BOM. Do NOT hardcode version numbers for Spring Kafka or MongoDB dependencies in `build.gradle`.
- **Idempotency:** When writing the Kafka Consumer in the Portfolio service, ensure the logic is idempotent (it won't corrupt the database if the exact same `PriceUpdatedEvent` is received twice).
- **If a step fails:** Output the exact error log and stop. Do not attempt a multi-step hallucinated fix.