# Core Architectural Guardrails & Project State

## 1. Project Context

- **Active Roadmap:** Refer to `docs/agent-instructions/ROADMAP_AI_POWERED_WEALTH_TRACKER.md` for current feature planning. Disregard older roadmaps unless explicitly instructed.
- **Documentation:** Always update `docs/changes` after significant implementations. Review `docs/adr` before proposing new system designs.
- **Immediate Priorities:** Resolve pending items in `docs/todos`, specifically:
  1. Implement Redis-backed distributed rate limiting (replacing local in-memory).
  2. Implement a Dead-Letter Queue (DLQ) strategy for malformed/poison Kafka events.

## 2. Infrastructure & Cost (STRICT)

- **Zero-Cost Free Tier:** You MUST prioritize AWS Free Tier eligible resources. Do not provision NAT Gateways, Multi-AZ RDS, or Provisioned IOPS.
- **Managed vs. Standard:** Where running a "standard" service (like Kafka or Redis) on an EC2 instance would violate Free Tier constraints or cause memory limits, use the closest AWS Serverless equivalent (e.g., SQS for messaging) for deployment, BUT keep the application code abstracted.

## 3. Application Code & Multi-Cloud Agnosticism

- **Hexagonal Architecture:** The core domain logic MUST remain pure.
- **No Cloud Lock-in:** Do NOT use AWS-specific SDKs (e.g., `software.amazon.awssdk`) inside the core business logic or domain layers.
- **Abstractions:** Use framework-level abstractions (like Spring Cloud Stream or Spring Data JPA/Interfaces) so the application can be seamlessly ported to Azure or GCP in the future.

## 4. Testing Strategy

- **Layered Testing:** Every layer must have automated tests (Unit, Integration, Architecture).
- **Local Validation:** The application must be fully testable locally without deploying to real AWS. Use Testcontainers (specifically the LocalStack module) for integration testing infrastructure components locally before any cloud deployment.
- **Infrastructure Testing:** Provide tests for infrastructure-as-code configurations (e.g., Terraform validations or CDK assertions).

## 5. Specific Architectural Nuances to Enforce

- **Rate Limiting & Caching (Redis vs. AWS Native):** While Redis is approved for local development (via Docker/Testcontainers), AWS ElastiCache's Free Tier is restrictive (12 months only). When implementing rate limiting, heavily utilize Spring Profiles (`application-local.yml` vs `application-aws.yml`). Ensure the architecture allows us to swap Redis for AWS API Gateway native usage plans or DynamoDB in the AWS environment if ElastiCache becomes unviable.
- **The Cold-Start Mitigation:** For AWS deployments (Phase 3/4), Spring Boot applications deployed as serverless functions must be optimized for cold starts. Prepare the build configurations to eventually support GraalVM Native Images or AWS SnapStart.
- **Strict Profile Isolation:** Never mix local infrastructure credentials or URLs (e.g., `localhost:6379`) into the main or AWS profiles.
