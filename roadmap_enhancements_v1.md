# Proposed ROADMAP.md Enhancements

## 1. Proposed Refinements for ROADMAP.md

Append or integrate the following sections into the **Future Architectural Goals** and create a new **User Experience & Identity** section.

### User Experience & Identity
* **Identity Management & User Onboarding:** Transition from stateless, edge-only JWT minting to a persisted user model. Implement a secure "New User Signup" flow (potentially extending the current Better Auth integration or adopting Entra ID B2C / Auth0) to support dedicated user profiles, authentication state, and session management.
* **User Profiles & Personalization Settings:** Introduce a dedicated settings pane allowing users to customize their platform experience. This includes base currency preferences, UI theming (dark/light), risk tolerance indicators, and default dashboard views.
* **Custom Asset & Portfolio Management:** Expand the domain model beyond the curated baseline of ~160 popular instruments. Allow users to define and track "custom assets" (e.g., real estate, private equity, or unlisted tokens). *Note: This requires careful integration with the AI Insight catalog to ensure custom assets do not trigger unbounded, un-cached LLM queries that spike API costs.*

### Architecture & Observability (Refined Existing Items)
* **Production Rate-Limiting (Elevated Priority):** Finalize a production-grade, profile-aware rate-limiting story for the active Azure cloud. Implement Redis-backed `RequestRateLimiter` at the `api-gateway` level to prevent abuse, manage costs (especially for the AI Insight endpoints), and ensure noisy-neighbor isolation. 
* **End-to-End Distributed Tracing & Admin Dashboard:** Complete the OpenTelemetry tracing continuity across the Kafka producer→consumer boundary. Pair this with the deployment of a centralized Observability/Admin Dashboard (e.g., Azure Monitor / Application Insights or a self-hosted Grafana instance) to visualize OTLP telemetry, trace application bottlenecks, and monitor API rate-limit hits in real-time.

---

## 2. Prioritization Matrix

This prioritization is based on Importance (value to the system/business), Usability (user-facing impact), and Ease of Implementation (factoring in cloud/LLM costs and architectural complexity).

| Feature | Importance | Usability | Ease of Implementation | Overall Priority | Rationale & Cost Considerations |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Production Rate-Limiting** | High | Low | High | **1 (Do First)** | Essential to protect your Azure OpenAI / Bedrock endpoints from abuse, directly saving you from accidental API bills. Spring Cloud Gateway makes this relatively easy to wire up with your existing Upstash Redis. |
| **New User Signup & Profile** | High | High | Medium | **2** | Foundational for retention. Since you already have PostgreSQL for the `portfolio-service`, adding a basic `users` schema and wiring up Better Auth with a database adapter is a straightforward CRUD operation. |
| **E2E Tracing & Admin Dashboard** | Medium | Low | Medium | **3** | You already have OpenTelemetry instrumentation wired up. The main effort is provisioning the Azure Application Insights workspace via Terraform and building the actual dashboard views. High value for debugging, but doesn't immediately block user features. |
| **User Settings (Personalization)** | Medium | Medium | High | **4** | Easy to implement once the User Profile DB (Priority 2) is in place. Mostly involves frontend state management and a simple JSON blob or columns in the Postgres database. |
| **Custom Asset Portfolios** | Medium | High | Low | **5 (Do Last)** | Highly complex. Requires dynamic pricing models (how do you value a custom asset without a Yahoo Finance ticker?). Furthermore, allowing the `insight-service` LLM to reason about completely custom text inputs risks bypassing your deterministic catalog validation, potentially driving up hallucination rates and LLM token costs. |
