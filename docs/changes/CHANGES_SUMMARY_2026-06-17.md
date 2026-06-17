# Changes Summary — Spring Boot 4.1 + Spring AI 2.0 GA Migration

**Date:** 2026-06-17
**Spec:** `.kiro/specs/springboot-41-springai-2-migration/tasks.md` (Tasks 1–13)
**Range:** All changes since the Wave 6 dashboard data accuracy changelog (`WAVE6_DASHBOARD_DATA_ACCURACY_2026-06-10.md`)
**Merged PRs:** #66, #67, #68, #69, #70, #71, #72
**Scope:** 74 files changed, ~+3,499 / −152

---

## Summary

Migrated the five-module platform from **Spring Boot 4.0.5 / Spring AI 2.0.0-M4** to **Spring Boot 4.1.0 GA / Spring AI 2.0.0 GA**, with the major breaking change being **Jackson 2 → Jackson 3** (`com.fasterxml.jackson.*` → `tools.jackson.*`). Work followed the zero-downtime rollout order from the design — `common-dto` (contract) → leaf data services → `insight-service` (AI refactor) → `api-gateway` (last) — with distributed tracing standardization and slim-JRE containerization wired in at the end.

Every task left the full module graph compiling, and serialization/property tests were placed close to the code they protect so Jackson 3 and Spring AI breaking changes surface at build time rather than runtime.

---

## Changes by Area

### Dependency graph & build (Tasks 1–3)

- **`build.gradle` (root):** bumped platform coordinates — `springBootVersion = 4.1.0`, `springCloudVersion = 2025.1.2`, `springAiVersion = 2.0.0`, aligned Testcontainers to the Boot 4.1 BOM, and hoisted `jqwikVersion = 1.9.2` into root `ext`. BOM import order pinned Boot → Spring Cloud → Testcontainers so Jackson 3 / Framework 7.x win.
- **Jackson 2 isolation guardrail:** added a `configurations.all { resolutionStrategy.eachDependency { … } }` hook constraining any transitive `com.fasterxml.jackson.core` to runtime-only scope, annotated with `because(...)` for reviewability (Property 12).
- **Per-module `build.gradle`:** removed Jackson 2 GAVs from app modules; rely on Boot 4.1-managed Jackson 3 transitively.
- **OpenRewrite:** remains advisory-only (not in `check`/CI); `activeRecipe` reconciled to `UpgradeSpringBoot_4_0` because the community `rewrite-spring` 6.30.0 does not yet ship `UpgradeSpringBoot_4_1`. Platform bumps applied manually.

### Shared contract — `common-dto` (Tasks 4–5)

- Migrated `PriceUpdatedEvent` and shared records off Jackson 2 annotations/mappers to Jackson 3 (`JsonMapper.builder()`).
- Added `ContractJsonMapper` (structural-only mapper, documented as **not** a wire guarantee) plus a frozen ISO-8601 wire fixture.
- New tests: `PriceUpdatedEventJackson3RoundTripTest` and the jqwik `PriceUpdatedEventRoundTripPropertyTest` (Property 3: `∀ e: deserialize(serialize(e)) == e`).

### Leaf data services — `portfolio-service`, `market-data-service` (Tasks 6–7)

- Replaced direct `com.fasterxml.jackson.*` usage with `tools.jackson.*`; verified JPA/Flyway/Kafka (portfolio) and MongoDB/WebFlux/Kafka/resilience4j (market-data) compile against Framework 7.x.
- **Kafka wire contract pinned across both sides:** consumer-side fidelity (`PriceUpdatedEventBackCompatTest`, `PriceUpdatedEventConsumerPathTest`) and producer-emitted JSON body shape (`PriceUpdatedEventProducerWireContractTest`) — using Spring Kafka's internally-built mapper, not the Boot `JsonMapper` bean.
- **Serialization-boundary slice tests** (`@WebMvcTest`): `PortfolioSerializationBoundarySliceTest`, `MarketPriceSerializationBoundarySliceTest` (Property 11).
- **Testcontainers cross-service loop:** `PriceUpdatedEventKafkaRoundTripIT` proves a true producer → consumer Jackson 3 round-trip, plus DLT routing of malformed payloads after `FixedBackOff(1000, 3)` with `MalformedEventException` on the not-retryable list.

### AI refactor — `insight-service` (Tasks 8–9)

- **Consolidated module swap:** removed `spring-ai-starter-model-azure-openai`, moved onto the consolidated `spring-ai-starter-model-openai`; bumped `spring-ai-bom` M4 → 2.0.0.
- **Native auth:** removed the hand-rolled AAD-token bridge / custom `RestClient` interceptor; `AzureOpenAiAuthConfig` now uses `DefaultAzureCredentialBuilder` → `BearerTokenCredential` (Entra ID / Managed Identity preferred over static keys). `com.azure:azure-identity` retained (still required by the auth config).
- **Config keys** migrated from `spring.ai.azure.openai.*` to native `spring.ai.openai.*` in `application-azure-ai.yml`.
- **Chat temperature** pinned to **0.2** on both bedrock and azure-ai profiles for structured-output determinism (reconciled down from the design's 0.7).
- **Structured-output hardening:** re-verified `AnalysisResult` / `LlmResolution` deserialization against the new schema generator; `jackson-annotations` stays `com.fasterxml.jackson.annotation` per Jackson 3 rules (only databind moves).
- New tests: `MockProfileAiParityTest`, `RiskScoreClampingPropertyTest` (risk ∈ [1,100]), `AiResponseNonEmptinessPropertyTest`, `StructuredOutputJackson3PropertyTest`, `PromptLeakGuardrailTest` (`log-prompt=false`), and `AzureOpenAiLiveSmokeTest` (Entra wire smoke — passed 2026-06-14, deployment `gpt-4o-mini`).

### Gateway — `api-gateway` (Task 10, migrated last)

- Verified `spring-cloud-starter-gateway-server-webflux`, reactive Redis, and OAuth2 resource-server config compile/boot on Framework 7.x + Spring Cloud 2025.1.2; route table, JWT validation, and rate-limit semantics preserved.
- New tests: `GatewaySerializationBoundarySliceTest` (`@WebFluxTest`, confirms `tools.jackson.*` mapper on the wire) and `GatewayBootContractTest` (context boots with routing, JWT decoder, rate-limit key resolver, health, auth paths).

### Distributed tracing (Task 11)

- Added OpenTelemetry instrumentation (`spring-boot-starter-opentelemetry`) to all four services, with OTLP trace/metrics export **gated off by default** via env (`MANAGEMENT_TRACING_EXPORT_ENABLED`, `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED`, `OTEL_EXPORTER_OTLP_ENDPOINT`).
- `management.tracing.propagation.type=w3c` on all services; Kafka observation enabled (template on market-data; listener on portfolio + insight) via custom factories routed through `ConcurrentKafkaListenerContainerFactoryConfigurer`.
- **Property 10a (HTTP):** `HttpTraceContextPropagationIT` proves unbroken `traceparent` across the reactive gateway boundary.
- **Property 10b (Kafka):** listener active-span ITs verify observation fires at consume time; `KafkaTemplateTracePropagationIT` asserts template observation binding. **Deferred:** full end-to-end producer→consumer trace-ID continuity (Task 11.2 remains open — the only incomplete sub-task).

### Containerization & slim-JRE (Task 12)

- All four Dockerfiles updated for Boot 4.1: `GRADLE_VERSION=9.4.1`, `jlink` adds `java.instrument,java.logging,jdk.crypto.ec` (+ `jdk.naming.dns` on market-data); AOT + slim JRE preserved for Lambda cold-start. insight-service Dockerfile notes the GraalVM `SpanExporters` native-image forward-risk.
- Added `*.slim-it` Dockerfiles and `SlimImageHealthIT` / `SlimJreTlsProbe` per service, plus a `slimImageTest` Gradle task (`@Tag("slim-image")`, excluded from default `check`; run via `./gradlew slimImageCheck` or `./gradlew migrationCheck` — requires Docker).

---

## Tests Run

| Suite | Result |
|---|---|
| `./gradlew check` (unit + integration, all modules) | ✅ BUILD SUCCESSFUL |
| `common-dto` Jackson 3 round-trip + jqwik property tests | ✅ pass |
| Leaf-service wire-contract, slice, and Testcontainers ITs | ✅ pass |
| `insight-service` mock-profile + property tests; Azure live wire smoke | ✅ pass |
| Gateway boot + serialization-boundary slice tests | ✅ pass |
| HTTP trace propagation IT (10a) | ✅ pass |
| Slim-image health/TLS (`./gradlew slimImageCheck`, Docker required) | ✅ pass |

---

## Known Gaps / Follow-ups

- **Task 11.2 (Kafka trace-ID continuity)** — end-to-end producer→consumer `traceparent` continuity remains deferred; listener observation and W3C wiring are in place, but full continuity needs auto-configured `KafkaTemplate` header injection verified under `@SpringBootTest` (`PropagatingSenderTracingObservationHandler` registration).
- **OpenRewrite** — switch `activeRecipe` to `UpgradeSpringBoot_4_1` once a community `rewrite-spring` release ships it.
- **GraalVM native image** — runtime-toggling trace export may need an empty `SpanExporters` bean workaround for future Lambda/native builds.

---

## Guardrails Respected

- Single-generation dependency graph enforced (no split Jackson 2+3, no dual Framework 7.x, single Spring Cloud train); all `spring-ai-*` at 2.0.0.
- Profile isolation preserved — no `localhost`/local credentials leaked into `application.yml` or AWS/Azure profiles.
- OTLP export off by default (config/env-switchable); no production tracing endpoints hard-coded.
- No infrastructure (CDK/Terraform) provisioning changes introduced by the migration.
