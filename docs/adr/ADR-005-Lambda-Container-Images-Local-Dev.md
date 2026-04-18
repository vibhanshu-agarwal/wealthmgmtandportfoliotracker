# ADR 005: Transition to AWS Lambda Container Images for Local Development Parity

**Status:** Proposed  
**Date:** 2026-04-14  
**Decider:** Vibhanshu Agarwal (Principal Architect)

## 1. Context and Problem Statement

The current local development environment for the wealthmgmtandportfoliotracker project uses a heavy Docker Compose setup. This has led to:

- **Performance degradation** on developer machines due to running multiple full-fat service containers alongside infrastructure dependencies (Postgres, MongoDB, Kafka, Redis).
- **"Success Illusion" in CI/CD** — green builds that pass in CI but fail locally with 400/404 errors, caused by subtle differences between the Docker Compose environment and the actual AWS Lambda deployment target.

These issues erode developer confidence and slow down the feedback loop.

## 2. Decision Drivers

- **Environment Parity:** Local execution must mirror the AWS Lambda runtime as closely as possible.
- **Resource Efficiency:** Reduce the memory and CPU footprint of the local dev stack.
- **Java 25 Support:** Must accommodate custom runtimes and AOT optimizations without ZIP size constraints.
- **Developer Experience:** Faster startup, fewer "works in CI but not locally" surprises.

## 3. Considered Options

- **Option A:** Continue with current Docker Compose setup (status quo).
- **Option B:** Use LocalStack to emulate AWS services locally.
- **Option C:** Transition to AWS Lambda Container Images with Runtime Interface Emulator (RIE) + Pact for contract testing.

## 4. Decision Outcome

**Chosen Option: Option C — AWS Lambda Container Images with RIE + Pact**

### Technical Justification:

- **Parity:** Local Docker containers built as Lambda container images mirror the AWS Lambda environment exactly, eliminating the "Success Illusion" gap.
- **Efficiency:** The Runtime Interface Emulator (RIE) is significantly lighter than LocalStack, reducing local resource consumption while still providing a faithful Lambda invocation model.
- **Java 25 Support:** Container images allow us to bake in custom runtimes and AOT optimizations (GraalVM Native Image, AWS SnapStart) without the 250 MB ZIP deployment package constraint.
- **Contract Testing:** Pact consumer-driven contract tests validate service interactions without requiring all services to run simultaneously, further reducing local resource needs.

## 5. Pros and Cons of the Choice

### Pros

- True local-to-cloud parity — what runs locally is what deploys to Lambda.
- RIE is lightweight and officially maintained by AWS.
- Container images support arbitrary runtime versions and native compilation.
- Pact decouples integration testing from a running service mesh.

### Cons

- Requires reworking existing Dockerfiles to target the Lambda container image base.
- Developers need to learn RIE invocation patterns and Pact contract authoring.
- Cold-start behavior differs slightly between RIE and real Lambda (RIE does not enforce memory/timeout limits).

## 6. Validation Strategy

- **Parity Check:** Compare HTTP response codes and payloads between RIE-local and deployed Lambda for a representative set of API calls.
- **Contract Coverage:** All inter-service interactions must have Pact contracts before the migration is considered complete.
- **Performance Baseline:** Measure local startup time and memory usage before and after the transition.
