# ADR 001: Selection of Evolutionary Tech Stack (Phase 1)

**Status:** Proposed  
**Date:** 2026-03-31  
**Decider:** Vibhanshu Agarwal (Principal Architect)

## 1. Context and Problem Statement
The "Wealth Management & Portfolio Tracker" requires a foundation that supports rapid local development while ensuring a low-friction migration path to a distributed AWS cloud-native environment. We need to avoid "Legacy Lock-in" while preventing "Microservice Overkill" in the early stages.

## 2. Decision Drivers
* **Scalability:** Must support high-throughput market data ingestion.
* **Maintainability:** Strict domain boundaries to prevent "Big Ball of Mud."
* **Cost Efficiency:** Development must be possible on a single machine using Docker.
* **Modernity:** Use "Latest + 1" versions to ensure a 5-year shelf life.

## 3. Considered Options
* **Option A:** Traditional Microservices (Spring Cloud + Eureka + Gateway)
* **Option B:** Modular Monolith (Spring Modulith + JUnit 6 + Next.js Standalone)
* **Option C:** Serverless-First (AWS Lambda + AppSync)

## 4. Decision Outcome
**Chosen Option: Option B**

### Technical Justification:
* **Backend:** Java 25 + Spring Modulith. We gain the logical isolation of microservices with the deployment simplicity of a monolith.
* **Testing:** JUnit 6 + Mockito. Leveraging 2025/26 enhancements for parallel test execution to maintain a <2 min CI pipeline.
* **Frontend:** Next.js 15+ (Standalone Mode). The standalone output drastically reduces Docker image size for future EKS/ECS deployment.
* **CI/CD:** GitHub Actions. Native integration with GHCR for OCI-compliant artifacts.

## 5. Pros and Cons of the Choice
* **Pros:** Zero network latency between modules; Unified transaction management via JDBC Outbox; Extremely fast developer inner-loop.
* **Cons:** Requires discipline to not bypass Modulith boundaries; Single point of failure (resolved in Phase 2).

## 6. Validation Strategy
- **Modulith Verification:** Enforced via `ApplicationModules.verify()`.
- **Performance:** Benchmarked via Testcontainers during the CI build.