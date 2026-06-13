# Implementation Plan: Spring Boot 4.1 + Spring AI 2.0.0 GA Migration

## Overview

This plan converts the design into incremental, build-verifiable coding steps that migrate the
five-module platform from Spring Boot 4.0.5 / Spring AI 2.0.0-M4 to Spring Boot 4.1.0 GA /
Spring AI 2.0.0 GA. Work is sequenced to honor the zero-downtime rollout order from the design:
`common-dto` (contract) → leaf data services → `insight-service` (AI refactor) → `api-gateway`
(last), with containerization and distributed-tracing standardization wired in at the end.

Each task ends with the whole graph still compiling, and property/serialization tests are placed
close to the code they protect so Jackson 3 and Spring AI breaking changes surface early. All
work targets Java 21 + Gradle (Groovy DSL) per the existing build.

## Tasks

- [x] 1. Establish single-generation dependency graph (BOM + plugin)
  - [x] 1.1 Bump platform coordinates in root `build.gradle`
    - Set `ext` versions: `springBootVersion = '4.1.0'`, `springCloudVersion = '2025.1.2'`, `springAiVersion = '2.0.0'`, and align `testcontainersVersion` to the Boot 4.1 BOM
    - Bump the Spring Boot plugin to `${springBootVersion}` (4.0.5 → 4.1.0) keeping `io.spring.dependency-management 1.1.7`
    - Ensure BOM import order is Boot → Spring Cloud → Testcontainers so Jackson 3 / Framework 7.x win
    - Remove no-longer-needed `repo.spring.io/milestone` only after confirming Spring AI 2.0.0 resolves from Maven Central
    - _Design: Step 1.1, 1.2, 1.3; Property 1, 9_

  - [x] 1.2 Update OpenRewrite recipe to the Boot 4.1 upgrade recipe
    - Bump `org.openrewrite.recipe:rewrite-spring` to a release shipping `UpgradeSpringBoot_4_1`
    - Change `activeRecipe` from `UpgradeSpringBoot_4_0` to `UpgradeSpringBoot_4_1`; keep `setExportDatatables(true)`
    - **Reconciled (Option A):** `activeRecipe` is pinned to `UpgradeSpringBoot_4_0` because `UpgradeSpringBoot_4_1` is not yet in community `rewrite-spring` (6.30.0). That recipe chains Framework 7 / Security 7 / Spring Cloud / Testcontainers 2, so it remains valid on the Boot 4.1 BOM; platform bumps were applied manually. Switch to `UpgradeSpringBoot_4_1` when a release ships it.
    - **OpenRewrite is advisory tooling only** — not part of `./gradlew check` or CI; BOM/platform bumps were applied manually. If running `rewriteDryRun`/`rewriteRun`, increase heap (e.g. `GRADLE_OPTS=-Xmx4g` or `org.gradle.jvmargs` in `gradle.properties`); OOM during scan is an environment limit, not a recipe defect.
    - _Design: Step 1.7_

  - [x] 1.3 Swap Spring AI starters in `insight-service/build.gradle`
    - Bump `spring-ai-bom` 2.0.0-M4 → 2.0.0; keep `spring-ai-starter-model-bedrock-converse` and `spring-ai-client-chat`
    - Remove `spring-ai-starter-model-azure-openai`; add `spring-ai-starter-model-openai` (consolidated module)
    - _Design: Step 1.4, 2.1; Property 1_

  - [x] 1.4 Remove Jackson 2 GAVs from app modules
    - Delete `com.fasterxml.jackson.core:jackson-databind` from `portfolio-service`, `market-data-service`, `insight-service` build files
    - Rely on Boot 4.1-managed Jackson 3 (`tools.jackson`) transitively; add `tools.jackson.core:jackson-databind` explicitly only where a compile-time mapper dependency is required
    - _Design: Step 1.5; Property 1, 12_

  - [x] 1.5 Verify and adjust remaining dependency-graph coordinates
    - Verify `resilience4j-spring-boot4` has a Boot 4.1/Framework 7.x-compatible build (market-data); bump if available
    - Confirm Flyway, Spring Kafka, OAuth2/Nimbus, and test libs (jjwit, wiremock-spring-boot, pact spring7, jqwik) resolve from the Boot 4.1 BOM
    - _Design: Step 1.6_

- [x] 2. Gate the dependency graph before touching code
  - [x] 2.1 Add the Jackson 2 isolation guardrail to root `build.gradle`
    - Add the `configurations.all { resolutionStrategy.eachDependency { ... } }` hook that pins/constrains any transitive `com.fasterxml.jackson.core` to runtime-only scope for the offending third-party lib
    - Annotate with `because(...)` so the isolation is reviewable
    - _Design: Step 1.5 (isolation guardrail); Property 12_

  - [x] 2.2 Run the resolution-verification gate and compile the whole graph
    - Run `./gradlew :common-dto:dependencies`, `:api-gateway:dependencies`, `:insight-service:dependencies` and grep `spring-framework|spring-cloud|jackson|spring-ai`
    - Run `./gradlew build -x test`; block on any split major (Jackson 2+3, two Framework 7.x, two Cloud trains) or any `spring-ai-*` not at 2.0.0
    - _Design: Step 1.8; Property 1, 9_

- [x] 3. Checkpoint - dependency graph coherent
  - Ensure the graph resolves to one generation and the whole project compiles. Ensure all tests pass, ask the user if questions arise.

- [x] 4. Migrate the shared contract (`common-dto`) to Jackson 3
  - [x] 4.1 Migrate `common-dto` Jackson imports to Jackson 3
    - Replace any `com.fasterxml.jackson.*` imports/annotations on `PriceUpdatedEvent` and shared records with `tools.jackson.*` equivalents
    - Replace `new ObjectMapper()` usage with `JsonMapper.builder().build()` where applicable
    - _Design: Step 2.4; Property 3, 12_

  - [x]* 4.2 Write parameterized Jackson 3 round-trip ser/deser tests for `common-dto`
    - **Property 3: Event contract preserved**
    - **Validates: Step 2.4 / Testing — Jackson 3 serialization**
    - Assert serialize→deserialize equality specifically against the configured `tools.jackson.databind.JsonMapper` (not an ad-hoc mapper) for `PriceUpdatedEvent` and other shared records

  - [x]* 4.3 Write jqwik property test for event round-trip
    - **Property 3: Event contract preserved (`∀ e: deserialize(serialize(e)) == e`)**
    - **Validates: Step 2.4 / Property 3**
    - Generate arbitrary valid `PriceUpdatedEvent` instances and assert byte-stable round-trip under Jackson 3

  - [ ] 4.4 Address PR #67 architect review follow-ups (pre-6.1 gate)
    - Soften `ContractJsonMapper` Javadoc: structural mapper check only — not a wire guarantee; production fidelity lives in `portfolio-service` / `market-data-service` tests
    - Add frozen ISO-8601 wire fixture deserialized via `ContractJsonMapper` (structural only; `common-dto` must not gain Spring Kafka)
    - Document scale-2 / milli-precision as an explicit contract invariant on `PriceUpdatedEvent` (or widen jqwik generators + use `compareTo` for prices)
    - Hoist `jqwikVersion = '1.9.2'` into root `ext`; reference from `common-dto`, `market-data-service`, and `insight-service`
    - _PR review: concerns 2 (structural half), 4 (note), nits; run before Task 6.1_

- [x] 5. Checkpoint - contract stable on Jackson 3
  - Ensure all `common-dto` serialization tests pass before any consumer migrates. Ask the user if questions arise.

- [ ] 6. Migrate leaf data services (`portfolio-service`, `market-data-service`)
  - [ ] 6.1 Migrate `portfolio-service` Jackson 3 usage and compile on the new platform
    - Replace direct `com.fasterxml.jackson.*` imports (DTOs, `ObjectMapper`) with `tools.jackson.*`
    - Verify JPA/Flyway/Kafka consumer code compiles against Framework 7.x; address new JSpecify nullness warnings
    - _Design: Step 2.4, Step 1.6; Property 1, 3_

  - [ ]* 6.2 Write Kafka consumer wire contract test — unit level (Wave 5; PR review concern 1 + unit half of 3)
    - Deserialize via the production `ErrorHandlingDeserializer` + `JacksonJsonDeserializer` stack matching `PortfolioKafkaConfig` (`TRUSTED_PACKAGES`, `VALUE_DEFAULT_TYPE`); extend `PriceUpdatedEventBackCompatTest` / `PriceUpdatedEventConsumerPathTest`
    - Hand-crafted ISO-8601 enriched fixture: assert consumer deserializes temporal fields correctly (does **not** prove producer emits this shape — see 6.5 / 6.7)
    - Unit-level rejection: malformed JSON / non-numeric `newPrice` throws on deserialization (deserializer contract only; DLT routing is 6.7)

  - [ ]* 6.3 Write `@WebMvcTest` slice test for `portfolio-service` serialization boundary
    - **Property 11: Jackson 3 mapper at serialization boundaries**
    - **Validates: Testing — Jackson 3 serialization (slice b)**
    - Assert the autoconfigured Jackson 3 `JsonMapper` bean handles controller request/response serialization

  - [ ] 6.4 Migrate `market-data-service` Jackson 3 usage and compile on the new platform
    - Replace direct `com.fasterxml.jackson.*` imports with `tools.jackson.*`
    - Verify MongoDB, WebFlux, Kafka producer, and resilience4j code compiles against Framework 7.x
    - _Design: Step 2.4, Step 1.6; Property 1, 3_

  - [ ]* 6.5 Write Kafka producer wire contract test — emitted shape (Wave 5; PR review refinement 1)
    - Assert `PriceUpdatedEvent` serialized by production `JacksonJsonSerializer` (`application.yml` → Boot auto-configured Jackson 3 mapper) emits the expected on-wire shape for temporal fields (ISO-8601 vs numeric timestamp) and enriched fields
    - Pairs with the consumer-side hand-crafted fixture in 6.2; together they pin the contract without a cross-module dependency

  - [ ]* 6.6 Write `@WebMvcTest` slice test for `market-data-service` serialization boundary
    - **Property 11: Jackson 3 mapper at serialization boundaries**
    - **Validates: Testing — Jackson 3 serialization (slice b)**
    - Assert the autoconfigured Jackson 3 mapper bean is used on the wire
    - HTTP/controller boundary only — not Kafka; producer Kafka shape is the 6.5 subtask above

  - [ ]* 6.7 Run Testcontainers integration tests for leaf services (Wave 6 — requires both 6.1 and 6.4)
    - **Validates: Property 1, 3 / Testing — Integration**
    - Run `integrationTest` against Postgres 16 (portfolio) and MongoDB 7 + Kafka (market-data); verify cross-service Jackson 3 event payloads round-trip (true producer→consumer loop; cannot live in Wave 5 because 6.1 and 6.4 are parallel)
    - Integration-level rejection: malformed payload routes to `.DLT` after `FixedBackOff(1000, 3)` retry policy with `MalformedEventException` on the not-retryable list (`PortfolioKafkaConfig`); distinct from unit-level deserializer rejection in 6.2

- [ ] 7. Checkpoint - leaf services migrated
  - Ensure leaf-service unit, slice, and integration tests pass. Ask the user if questions arise.

- [ ] 8. Refactor `insight-service` Spring AI M4 → GA
  - **Prerequisite (before 8.1):** complete `spike-azure-openai-auth.md` — Azure auth + routing de-risk for the consolidated `spring-ai-openai` module. Does not block Task 4.1 / Wave 3.
  - [ ] 8.1 Rewire Azure OpenAI adapters onto the consolidated `spring-ai-openai` module
    - Keep the Java type names of `AzureOpenAiInsightService`, `AzureOpenAiInsightAdvisor`, `AzureOpenAiAssetResolutionClient`; update autoconfig wiring to the consolidated module
    - Remove the hand-rolled AAD-token bridge / custom `RestClient` interceptor (native auth replaces it)
    - Verify `AiConfig.chatClientBuilder(ChatModel)` with `@ConditionalOnBean(ChatModel.class)` still compiles and behaves identically
    - _Design: Step 2.1, 2.6; Property 4_

  - [ ] 8.2 Migrate `azure-ai` config keys to native `spring.ai.openai.*` properties
    - Move `application-azure-ai.yml` keys from `spring.ai.azure.openai.*` to native `spring.ai.openai.*` (endpoint, deployment, auth)
    - Prefer Managed Identity / Entra ID over static API keys; do not introduce a static OpenAI API key
    - _Design: Step 2.1, Security; Property 4_

  - [ ] 8.3 Pin chat temperature per profile
    - Set `spring.ai.bedrock.converse.chat.options.temperature=0.7` (bedrock) and `spring.ai.openai.chat.options.temperature=0.7` (azure-ai)
    - _Design: Step 2.2; Performance_

  - [ ] 8.4 Harden structured-output records against the schema-generation change
    - Re-verify `.entity(AnalysisResult.class)` / `.entity(LlmResolution.class)` deserialize representative output; enforce required-ness via non-null record components + validation; keep existing null-guards
    - Migrate any `com.fasterxml.jackson.*` annotations on these records to `tools.jackson.*`
    - _Design: Step 2.3, 2.4; Property 5, 12_

  - [ ] 8.5 Add the ChatClient options-builder guardrail (latent)
    - Where any options are set programmatically, pass `ChatOptions.Builder` (not `.build()`); add a comment documenting the 2.0 requirement
    - _Design: Step 2.5_

  - [ ] 8.6 Remove `com.azure:azure-identity` if unused
    - Confirm no remaining code path depends on the Azure SDK after native auth; remove the dependency if unused, otherwise leave a documented note
    - _Design: Step 1.4, 2.1; Dependencies_

  - [ ]* 8.7 Run mock-profile unit tests for `insight-service`
    - **Property 4: AI behavior parity (mock profile)**
    - **Validates: Step 2 / Property 4**
    - Verify `ChatResolutionService`, `ChatResponseBuilder`, and advisor-unavailability paths behave as before with `spring.ai.model.chat=none`

  - [ ]* 8.8 Write jqwik property test for risk-score clamping
    - **Property 6: Risk-score invariant (`riskScore ∈ [1,100]`)**
    - **Validates: Step 2 / Property 6**
    - Generate arbitrary advisor inputs and assert `analyze(...)` output stays within `[1,100]`

  - [ ]* 8.9 Write jqwik property test for AI response non-emptiness
    - **Property 4: AI behavior parity (non-blank response)**
    - **Validates: Step 2 / existing P6 non-emptiness**
    - Assert all outcome paths produce a non-blank `ChatResponse`

  - [ ]* 8.10 Write jqwik contamination-guard property test for structured output
    - **Property 12: No Jackson 2 contamination of app DTOs**
    - **Validates: Testing — Jackson 3 serialization (item c)**
    - Generate arbitrary valid `AnalysisResult` / `LlmResolution` and assert all required fields map correctly through the Spring AI 2.0 Jackson 3 schema generator

  - [ ]* 8.11 Write prompt-leak guardrail test
    - **Property 8: No prompt leakage**
    - **Validates: Step 3.2, Security**
    - Assert raw user messages/prompts never reach logs with `log-prompt=false`

- [ ] 9. Checkpoint - insight-service on Spring AI GA
  - Ensure mock-profile unit tests and property tests pass; run the opt-in bedrock smoke test if credentials are available. Ask the user if questions arise.

- [ ] 10. Migrate `api-gateway` (last) on Boot 4.1 + Spring Cloud 2025.1.2
  - [ ] 10.1 Compile and wire the gateway on the new platform
    - Verify `spring-cloud-starter-gateway-server-webflux`, reactive Redis, and OAuth2 resource-server config compile on Framework 7.x; resolve any `NoSuchMethodError`/`ClassNotFoundException` surfaced by the train pairing
    - Preserve route table, JWT validation, and rate-limit semantics (profile-aware backing store)
    - _Design: Step 1.6, Components §2; Property 2_

  - [ ]* 10.2 Write `@WebFluxTest` slice test for gateway serialization boundary
    - **Property 11: Jackson 3 mapper at serialization boundaries**
    - **Validates: Testing — Jackson 3 serialization (slice b)**
    - Assert the autoconfigured Jackson 3 mapper handles gateway request/response serialization

  - [ ]* 10.3 Write gateway boot/contract test (F2 safety gate)
    - **Property 2: Gateway boots on the pinned train**
    - **Validates: Step 1/Step 3, Migration-specific gates**
    - Assert the gateway context starts cleanly with routing, JWT validation, and rate limiting intact on Boot 4.1 + Spring Cloud 2025.1.2

- [ ] 11. Standardize distributed tracing across all services
  - [ ] 11.1 Add tracer + OTLP exporter to all services
    - Add `micrometer-tracing-bridge-otel` (or `-brave`) and `opentelemetry-exporter-otlp` to all four services; ensure reactive context propagation on the WebFlux gateway
    - Re-verify Micrometer/Observation API usage and `management.*` property names against Boot 4.1
    - Use Spring AI 2.0 log-based observation keys (`log-prompt`/`log-completion`) keeping `log-prompt=false`
    - _Design: Step 3.2; Property 8, 10_

  - [ ]* 11.2 Write trace-context propagation test
    - **Property 10: Trace-context propagation**
    - **Validates: Step 3.2, Migration-specific gates**
    - Assert a single trace spans `api-gateway → insight-service` (and other leaf services) with the W3C `traceparent` header propagated unbroken across the reactive gateway boundary

- [ ] 12. Containerization and slim-JRE validation
  - [ ] 12.1 Update Dockerfiles for the Boot 4.1 platform
    - Keep `amazoncorretto:21`; optionally bump `GRADLE_VERSION` in the `gradle-dist` stage in sync with `gradle/wrapper`
    - Re-run `jdeps`/`jlink`; explicitly add `tools.jackson.core`, `tools.jackson.databind`, and (for records) `tools.jackson.module.paramnames` to `--add-modules` when serialization fails on the slim image
    - Confirm the `insight-service` build/image path after the azure-module swap; preserve AOT + slim JRE for Lambda cold start
    - _Design: Step 3.1; Property 7_

  - [ ]* 12.2 Add slim-image boot/health verification
    - **Property 7: Image boots slim**
    - **Validates: Step 3 verification**
    - Build each service slim image and assert `/actuator/health = UP` and successful outbound TLS to backends (Kafka/Mongo/OpenAI/Bedrock)

- [ ] 13. Final checkpoint - full migration green
  - Run `./gradlew check` (unit + integration) across all modules; confirm the dependency gate, gateway boot gate, Jackson 3 serialization tests, AI parity tests, and tracing/slim-image checks all pass. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP, but the
  Jackson 3 serialization and gateway boot tests directly protect zero-downtime guarantees and
  are strongly recommended.
- Each task references specific design steps and correctness properties for traceability.
- Migration order (`common-dto` → leaf services → `insight-service` → `api-gateway`) preserves
  the zero-downtime rollout contract from the design; the gateway migrates last.
- Property tests use jqwik 1.9.2 (hoist to `jqwikVersion` in root `ext` per Task 4.4).
- Compilation checks cannot catch Jackson 3 serialization failures — runtime/serialization tests
  are mandatory per the design's Testing Strategy.
- **PR #67 review follow-ups (Task 4.4 + 6.2/6.5/6.7 subtasks):** `common-dto` tests prove
  structural Jackson 3 compatibility only. Consumer fidelity (6.2), producer-emitted shape (6.5),
  and producer→consumer + DLT routing (6.7) close the wire contract. Wave 5 runs 4.4 then the
  leaf migrations `{6.1, 6.4}` with their wire-contract unit tests `{6.2, 6.5}` in parallel;
  cross-service tests must wait for Wave 6 (`6.7`).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4", "1.5"] },
    { "id": 1, "tasks": ["2.1"] },
    { "id": 2, "tasks": ["2.2"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.2", "4.3"] },
    { "id": 5, "tasks": ["4.4", "6.1", "6.2", "6.4", "6.5"] },
    { "id": 6, "tasks": ["6.3", "6.6", "6.7"] },
    { "id": 7, "tasks": ["8.1", "8.2", "8.3", "8.4", "8.5", "8.6"] },
    { "id": 8, "tasks": ["8.7", "8.8", "8.9", "8.10", "8.11"] },
    { "id": 9, "tasks": ["10.1"] },
    { "id": 10, "tasks": ["10.2", "10.3", "11.1"] },
    { "id": 11, "tasks": ["11.2", "12.1"] },
    { "id": 12, "tasks": ["12.2"] }
  ]
}
```
