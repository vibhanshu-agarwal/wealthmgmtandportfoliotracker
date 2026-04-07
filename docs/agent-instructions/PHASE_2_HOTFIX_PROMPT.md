# 🤖 Coding Agent Instructions: Phase 2 Infrastructure Hotfixes

## 🎯 Context & Objective
You are an Expert Java/Spring Cloud Software Architect. We are currently on the `architecture/cloud-native-extraction` branch.

Your task is to implement two critical infrastructure TODOs from our backlog. **DO NOT** implement any UI, frontend, or feature-related TODOs. Focus purely on these two distributed system resilience features.

**Tech Stack Context:** Java 25, Spring Boot 4+, Gradle.

## 🛠️ Execution Plan

Execute these steps sequentially. Stop and ask for confirmation after each step.

### Step 1: Redis-backed Rate Limiting (`api-gateway`)
The API Gateway is currently using a local in-memory rate limiter, which will fail in a multi-instance cloud environment. We must migrate it to Redis.

1. **Infrastructure:** Add a `redis` container (using the `redis:alpine` image) to the root `docker-compose.yml` and expose port `6379`.
2. **Dependencies:** Add `spring-boot-starter-data-redis-reactive` to the `api-gateway/build.gradle` file. *(Note: Spring Cloud Gateway is built on WebFlux, so it must be the reactive Redis driver).*
3. **Configuration:** Update the Gateway's `application.yml` to use Redis for rate limiting on the `/api/market/**` route. Set a reasonable `replenishRate` and `burstCapacity`.
4. **Code:** Implement a `@Bean` of type `KeyResolver` in the Gateway to rate-limit by the user's IP address or a generic principal. Remove the old in-memory limiter.

### Step 2: Kafka Dead-Letter Queue (DLQ) (`portfolio-service`)
If the Portfolio service receives a malformed `PriceUpdatedEvent`, it will infinitely retry and crash. We need a Dead-Letter strategy.

1. **Configuration:** In the `portfolio-service`, create a Kafka configuration class.
2. **Error Handler:** Define a `DefaultErrorHandler` bean that uses a `DeadLetterPublishingRecoverer`. Configure it to retry a failed message 3 times (with a short backoff) before routing it to a `.DLT` (Dead Letter Topic).
3. **Listener Update:** Ensure the existing `@KafkaListener` in the `portfolio-service` is wired to use this new error-handling container factory.
4. **Logging:** Add a secondary `@KafkaListener` that listens explicitly to the `market-prices.DLT` topic and simply logs the failed payload as an `ERROR` so we have visibility into poisoned messages.

---

## ⚠️ Agent Constraints & Circuit Breakers (STRICT ENFORCEMENT)
- **Dependency Rule:** DO NOT hardcode version numbers for the Redis or Kafka dependencies. Rely on the Spring Boot BOM inherited from the root project.
- **Reactive Stack:** The `api-gateway` is Reactive (WebFlux). Do NOT import `spring-webmvc` or standard blocking Redis drivers into the gateway module, or the application will fail to start.
- **No Feature Creep:** Ignore all TODOs regarding the frontend, UI charts, Unrealized P&L, or Local Market Data Seeders.
- **If a build fails:** Stop, output the error, and wait for instruction. Do not loop.