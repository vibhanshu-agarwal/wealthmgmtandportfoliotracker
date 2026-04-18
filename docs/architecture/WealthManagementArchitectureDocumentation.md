# Architecture Overview

## Purpose

This document describes the overall architecture of the Wealth Management & Portfolio Tracker application, including the technologies in use, how the code is organized, and how the frontend integrates with the backend.

---

## 1. Technology Stack

### Frontend
- **Next.js**
- **React 19**
- **TypeScript**
- **Tailwind CSS**
- **Radix UI**
- **TanStack React Query**
- **Recharts**
- **next-themes**
- **lucide-react**

### Backend
- **Java 25**
- **Spring Boot**
- **Spring MVC / Web**
- **Spring Data JPA**
- **Spring Data MongoDB**
- **Spring Kafka**
- **Spring Cloud Gateway**
- **Jakarta EE imports**
- **Gradle**

### Data and Infrastructure
- **PostgreSQL** for portfolio and transactional data
- **MongoDB** for market data
- **Apache Kafka** for event-driven communication
- **Redis** for gateway rate limiting
- **Docker / Docker Compose** for local development
- **GitHub Actions** for CI/CD automation

### Testing and Quality
- **JUnit**
- **Mockito**
- **Testcontainers**
- **Vitest**
- **Testing Library**
- **Playwright**
- **MSW**
- **ESLint**
- **TypeScript checks**
- **Qodana**

---

## 2. What Each Technology Is Used For

### Frontend technologies
- **Next.js / React**: Builds the user interface and application structure.
- **TypeScript**: Adds static typing and improves maintainability.
- **Tailwind CSS**: Provides utility-based styling.
- **Radix UI**: Supplies accessible UI primitives such as dialogs, dropdowns, and tooltips.
- **React Query**: Handles server state, caching, and asynchronous data loading.
- **Recharts**: Renders financial charts and portfolio visualizations.
- **next-themes**: Enables theme switching such as light and dark modes.
- **lucide-react**: Supplies icons.

### Backend technologies
- **Spring Boot**: Core framework for backend services.
- **Spring MVC / Web**: Exposes REST APIs.
- **Spring Data JPA**: Persists portfolio data in PostgreSQL.
- **Spring Data MongoDB**: Persists market data in MongoDB.
- **Spring Kafka**: Handles event publishing and consumption.
- **Spring Cloud Gateway**: Acts as the system entry point and routes requests to services.
- **Java 25**: Backend language and runtime.

### Infrastructure technologies
- **PostgreSQL**: Stores structured portfolio data and transactional records.
- **MongoDB**: Stores market price data.
- **Kafka**: Connects services asynchronously through events.
- **Redis**: Supports gateway rate limiting.
- **Docker Compose**: Runs the local infrastructure stack.
- **GitHub Actions**: Automates build, test, and deployment workflows.

### Testing and quality tools
- **JUnit / Mockito / Testcontainers**: Backend testing.
- **Vitest / Testing Library / Playwright**: Frontend testing.
- **MSW**: Mocks API responses in frontend tests.
- **Linting and type checks**: Keep code quality high and catch issues early.

---

## 3. Code Organization

The repository is organized as a **multi-module Gradle project** with a separate frontend application.

### Main modules

#### `frontend`
The Next.js application for the user interface.

**Responsibilities:**
- Render dashboards, charts, and portfolio views
- Call backend APIs through the gateway
- Handle UI state and data display

#### `api-gateway`
The public entry point for backend traffic.

**Responsibilities:**
- Route requests to backend services
- Provide a stable API boundary for the frontend
- Apply optional resilience features such as rate limiting

#### `portfolio-service`
The core portfolio domain service.

**Responsibilities:**
- Manage holdings and portfolio summaries
- Calculate valuation data
- Expose portfolio REST endpoints
- React to market updates asynchronously

**Primary data store:**
- PostgreSQL

#### `market-data-service`
The market pricing service.

**Responsibilities:**
- Store current prices
- Accept market updates
- Publish price change events to Kafka

**Primary data store:**
- MongoDB

#### `insight-service`
The insight generation service.

**Responsibilities:**
- Consume events from Kafka
- Produce analytical or derived insights
- Keep analytics logic isolated from core portfolio operations

#### `common-dto`
Shared data contracts for backend services.

**Responsibilities:**
- Define shared DTOs
- Define event payloads used in Kafka communication
- Avoid duplication of message formats across services

**Important rule:**
- Shared contracts belong here
- Domain entities should remain inside their owning services

---

## 4. How the Frontend Integrates With the Backend

The frontend does not call individual services directly. Instead, it sends requests to the **API Gateway**.

### Request flow
1. The frontend sends an HTTP request.
2. The request goes to the API Gateway.
3. The gateway routes the request to the correct backend service.
4. The backend responds with JSON data.
5. The frontend renders the response in the UI.

### Typical routing
- Portfolio requests go to **portfolio-service**
- Market requests go to **market-data-service**
- Insight requests go to **insight-service**

### Data handling
- The frontend uses a service layer and hooks to fetch data.
- React Query or similar tooling is used for caching and synchronization.
- UI components consume backend data and transform it into charts, summaries, and tables.

### Event-driven backend behavior
- When a market price changes, the market service publishes a Kafka event.
- The portfolio service consumes that event and updates valuations.
- The frontend then sees the updated data through normal API requests.

### Benefits of this integration pattern
- A single public API entry point
- Clear service boundaries
- Asynchronous backend updates
- Easier frontend integration and simpler deployment

---

## 5. Infrastructure Overview

The local development environment is designed to run the complete system with Docker Compose.

### Included services
- PostgreSQL
- MongoDB
- Kafka
- Redis

### Why this matters
- Makes setup reproducible
- Keeps local development close to production architecture
- Reduces dependency on manually installed services

---

## 6. Architecture Summary

This system uses:
- a **Next.js frontend**
- a **Spring Boot microservices backend**
- **PostgreSQL** for portfolio data
- **MongoDB** for market data
- **Kafka** for asynchronous event communication
- an **API Gateway** as the public entry point

This architecture keeps the UI simple, the backend responsibilities separated, and the system ready for scalable event-driven behavior.
