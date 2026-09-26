# Executive Summary

> **Historical reference — classified 2026-09-26 UTC.** Earlier architecture snapshot. Use [Overview v2](WealthManagementArchitectureOverview_v2.md) for current modules, runtime and Azure demo boundaries.
>
> Start with the [architecture index](README.md) and [Current operations](../runbooks/CURRENT_OPERATIONS.md).
> The body below retains its original context; it is not fresh source/live proof and does not
> authorize cloud/secret access, dispatch, repair, state/offset deletion, deployment or cleanup.

The Wealth Management & Portfolio Tracker is a modern financial application built with a Next.js frontend and a Spring Boot backend platform.

## At a glance
- **Frontend:** Next.js, React, TypeScript
- **Backend:** Java 25, Spring Boot
- **Data stores:** PostgreSQL and MongoDB
- **Messaging:** Apache Kafka
- **Routing:** Spring Cloud Gateway
- **Local infrastructure:** Docker Compose
- **Quality and testing:** JUnit, Testcontainers, Vitest, Playwright, Testing Library

## Core idea

The platform is structured around clear service boundaries:
- **portfolio-service** handles portfolio holdings, valuations, and summaries
- **market-data-service** manages market prices
- **insight-service** processes domain events and generates insights
- **api-gateway** provides a single entry point for all backend traffic
- **common-dto** shares event and DTO contracts between services

## How it works

The frontend communicates only with the API Gateway. The gateway routes requests to the appropriate backend service. Market updates are published through Kafka, allowing portfolio and insight services to react asynchronously. PostgreSQL is used for transactional portfolio data, while MongoDB stores market data.

## Why this architecture

This design provides:
- clean separation of concerns
- scalable event-driven communication
- a simpler frontend integration model
- strong support for local development and testing
- a path toward cloud-native deployment

## Main value

The result is a modular, maintainable financial platform that can evolve from a local development stack into a distributed production architecture without rewriting the core domain logic.
