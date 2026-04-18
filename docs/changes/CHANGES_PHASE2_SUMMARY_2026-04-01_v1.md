# Phase 2 Changes Summary - 2026-04-01 (v1)

## Objective
Refactor the Spring Modulith monolith into a distributed, cloud-native microservices architecture with an API Gateway, event-driven communication via Kafka, and polyglot persistence.

## 1. Repository Restructuring (Multi-Module)
- Converted the monolithic project into a multi-module Gradle project.
- Created root-level modules:
  - `api-gateway`: Spring Cloud Gateway for routing and resilience.
  - `portfolio-service`: Core service for portfolio management (PostgreSQL).
  - `market-data-service`: Service for market price updates (MongoDB).
  - `insight-service`: Standalone consumer for generating insights.
  - `common-dto`: Shared DTOs and Kafka event records (e.g., `PriceUpdatedEvent`).
- Distributed existing code and resources into their respective modules.

## 2. Infrastructure Update
- Updated `docker-compose.yml` to support the new microservices architecture.
- **PostgreSQL**: Retained for `portfolio-service` (Database: `portfolio_db`).
- **MongoDB (v7.0)**: Added for high-throughput market data in `market-data-service`.
- **Apache Kafka (Bitnami 3.7)**: Added in KRaft mode for event-driven communication.
- **Redis**: Added for API Gateway rate limiting.
- Configured persistent volumes for all data stores.

## 3. Service Extraction & Refactoring

### 3.1 market-data-service
- Migrated from PostgreSQL/JPA to **MongoDB**.
- Refactored `AssetPrice` as a `@Document`.
- Implemented `MarketPriceService` to publish `PriceUpdatedEvent` to Kafka topic `market-prices` using `KafkaTemplate`.
- Added `MarketPriceController` for updating and fetching prices.

### 3.2 portfolio-service
- Retained **PostgreSQL** for ACID compliance.
- Refactored internal event listeners to use `@KafkaListener` for consuming `PriceUpdatedEvent` from Kafka.
- Configured Kafka consumer group `portfolio-group`.

### 3.3 insight-service
- Implemented as a standalone service consuming market updates via Kafka.

### 3.4 api-gateway
- Implemented using **Spring Cloud Gateway**.
- Configured path-based routing:
  - `/api/portfolio/**` -> `portfolio-service`
  - `/api/market/**` -> `market-data-service`
  - `/api/insight/**` -> `insight-service`
- Added **Redis-based Rate Limiting** for the market service.

## 4. Cleanup & Enforcement
- Removed `spring-modulith` dependencies.
- Replaced all internal `ApplicationEvent` mechanisms between bounded contexts with Kafka messaging.
- Enforced physical boundaries through network communication.

## 5. Verification & Validation
- **Build**: Successfully compiled and assembled all modules using `./gradlew assemble`.
- **API Flow Test**:
  1. Updated market price via Gateway: `POST /api/market/prices/AAPL`.
  2. Verified Kafka event production and consumption logs.
  3. Fetched updated portfolio via Gateway: `GET /api/portfolio/user-001`.
- **Resilience**: Verified Redis-based rate limiting on market endpoints.
