# Integration and E2E Test Inventory

**Audited:** 2026-09-26 UTC against `main@8aa4035b`. This inventories source/tests and
intended assertions; it is **not** a test execution report or proof that every proposed case
is implemented/passing. Accepted demo-run evidence is in the
[demo dashboard](../plans/ASSET_PICKER_DEMO_PREPARATION_PLAN.md). No live testing is authorized here.

## 1. Current workflow scope

| Workflow | What source configures |
|---|---|
| [CI Verification](../../.github/workflows/ci-verification.yml) | Push/PR pipeline: static contracts, selected backend unit/integration suites, Pact, image probes and assembled Compose Playwright. Docs-only PR classification skips applicable code jobs, not all checks. |
| [Frontend CI](../../.github/workflows/frontend-ci.yml) | Frontend unit/build checks plus `static-smoke` Playwright; static smoke is not the assembled backend suite. |
| [Frontend full-stack](../../.github/workflows/frontend-e2e-integration.yml) | Manual dispatch only; retained Compose-backed diagnostic specs, not automatically run after each PR. |
| [Live synthetics](../../.github/workflows/synthetic-monitoring.yml) | Manual dispatch only, with provider-selected AWS/Azure jobs; not an hourly always-on availability monitor or automatic demo acceptance. |
| [Azure Terraform](../../.github/workflows/terraform-azure.yml) | PR/structural plan and separately dispatched real-state preview/apply. Source guards are not live readiness tests. |
| [AWS Terraform](../../.github/workflows/terraform.yml) | Retained manual-only workflow; not the active Azure infrastructure path. |

Qodana, Gitleaks and the master-plan guard cover their own scopes. Secret scanning is not a
proof of all security properties; a health probe is not auth/consumer/model readiness.
CI Verification's assembled list includes auth preflight, golden path, dashboard data, mocked
chaos, live contract, deep link, unpriced holdings, picker and reset. Its local `live-contract`
name is not itself a test against today's deployed Azure revisions.

## 2. Source-linked cases and limits

**Merged follow-up evidence:** price-removal commit `83607f5d`, merged through #327 (`9c733f6d`), adds
`MarketPriceWriteRemovalIT` (real Mongo/Kafka, required control write, unchanged documents/topic,
read-only handler mappings) and `MarketPriceWriteGatewayIntegrationTest` (production route list,
auth/read-only refusal and exact forwarded subject despite a spoofed header). The recorded
service XML is red before removal, green after and red with the restored endpoint. Codex inspected
source/XML, not a new execution; full suite counts and independent reruns are reported in the
[price-write item](../todos/backlog/public-market-price-write-authorization/README.md).
These tests are on main after the `8aa4035b` audit baseline. Heavy PR CI passed, but they do not
prove live remediation and cover only one
protected spoofed-header case, not the entire [header-proof backlog](../todos/backlog/gateway-user-header-spoofing-regression-proof/README.md).

| Boundary / case | Representative existing source | Correct assertion and limit |
|---|---|---|
| Signup/login and auth errors | [AuthIntegrationTest](../../api-gateway/src/test/java/com/wealth/gateway/auth/AuthIntegrationTest.java), [AuthControllerUniformErrorTest](../../api-gateway/src/test/java/com/wealth/gateway/AuthControllerUniformErrorTest.java) | Gateway-owned credentials, uniform rejected login and empty-portfolio signup; local tests are not a new real account lifecycle run. |
| JWT/user headers/origin | [JwtFilterIntegrationTest](../../api-gateway/src/test/java/com/wealth/gateway/JwtFilterIntegrationTest.java), [chain test](../../api-gateway/src/test/java/com/wealth/gateway/JwtAuthenticationFilterChainTest.java), [origin filter test](../../api-gateway/src/test/java/com/wealth/gateway/CloudFrontOriginVerifyFilterTest.java) | JWT rejection/non-401 cases and a unit assertion for injected subject. The named spoofing case does **not** inspect forwarded headers; direct spoof-removal regression proof remains OPEN. Origin checks depend on secret/profile, not every controller. |
| Rate limits/demo writes | [ProductionRateLimitingIntegrationTest](../../api-gateway/src/test/java/com/wealth/gateway/ProductionRateLimitingIntegrationTest.java), [read-only properties](../../api-gateway/src/test/java/com/wealth/gateway/ReadOnlyEnforcementFilterPropertyTest.java) | Named ordinary/AI/auth buckets and exact B2 PUT exceptions, not a blanket "demo cannot save" rule. |
| Whole-set composition/conflicts | [CompositionControllerIT](../../portfolio-service/src/test/java/com/wealth/portfolio/composition/CompositionControllerIT.java), [ConcurrentCompositionIT](../../portfolio-service/src/test/java/com/wealth/portfolio/composition/ConcurrentCompositionIT.java) | Decimal desired-set write with expected version; 400/409 contract, transaction/no-op behavior and user isolation in scoped cases. |
| Stored-price read response / event serialization | [MarketPriceControllerTest](../../market-data-service/src/test/java/com/wealth/market/MarketPriceControllerTest.java), [wire contract](../../market-data-service/src/test/java/com/wealth/market/PriceUpdatedEventProducerWireContractTest.java) | Controller GET fixtures assert caps, unavailable rows and change fields; the wire test asserts serialized event fields. Neither is an HTTP manual-write test or broker-delivery proof. Stored reads/no Yahoo and persist/send ordering are source facts in the market guide, not extra assertions credited to these tests. |
| Refresh/job/provider failure | [MarketDataRefreshServiceIT](../../market-data-service/src/test/java/com/wealth/market/MarketDataRefreshServiceIT.java), [runner process IT](../../market-data-service/src/test/java/com/wealth/market/MarketDataRefreshJobRunnerProcessIT.java) | Controlled provider/producer/job behavior; not proof today's Azure scheduled execution updated all tickers. |
| Projection/history/DLT | [tuple IT](../../portfolio-service/src/test/java/com/wealth/portfolio/MarketPriceProjectionTupleIT.java), [consumer-path test](../../portfolio-service/src/test/java/com/wealth/portfolio/PriceUpdatedEventConsumerPathTest.java), [Kafka config test](../../portfolio-service/src/test/java/com/wealth/portfolio/PortfolioKafkaConfigTest.java) | Timestamp/currency/tuple guards and handler configuration; duplicates, older/equal-time conflicts and DLT are not general exactly-once processing. |
| FX/freshness/chart history | [FX integration](../../portfolio-service/src/test/java/com/wealth/portfolio/fx/EcbFxRateProviderIntegrationTest.java), [freshness test](../../portfolio-service/src/test/java/com/wealth/portfolio/freshness/AssetPriceFreshnessTest.java), [history dedup IT](../../portfolio-service/src/test/java/com/wealth/portfolio/AnalyticsHistoryDedupIT.java) | Missing FX remains unavailable; stale only above 50 hours; latest ticker/day history. Mock/provider fixtures do not prove real-data completeness. |
| Bulk/cache/chat/source | [InsightControllerTest](../../insight-service/src/test/java/com/wealth/insight/InsightControllerTest.java), [MarketDataServicePropertyTest](../../insight-service/src/test/java/com/wealth/insight/MarketDataServicePropertyTest.java), [ChatControllerTest](../../insight-service/src/test/java/com/wealth/insight/ChatControllerTest.java), [source declaration test](../../insight-service/src/test/java/com/wealth/insight/infrastructure/ai/SentimentSourceDeclarationTest.java) | Controller tests assert bulk price data with no AI-summary field; source uses stored facts without invoking sentiment for bulk. Property cases cover market/cache calculations, not that controller boundary. Chat/source contracts and cached attribution do not prove fresh invocation or text correctness. |
| HTTP contracts | [portfolio Pact](../../portfolio-service/src/test/java/com/wealth/portfolio/pact/PortfolioPactVerificationTest.java), [insight Pact](../../insight-service/src/test/java/com/wealth/insight/pact/InsightPactVerificationTest.java), [frontend Pact config](../../frontend/vitest.pact.config.ts) | Provider/consumer HTTP replay for declared interactions, not exhaustive API or Kafka schema coverage. |
| Trace propagation/redaction | [shared tests](../../common-observability/src/test/), [Kafka trace IT](../../insight-service/src/test/java/com/wealth/insight/trace/KafkaTraceContextPropagationIT.java) | Controlled propagation and sanitization assertions; not a new live exporter/privacy audit. |

Read the actual annotations/task filters before choosing a command: class existence does not
prove a class ran in a particular job. [build.gradle](../../build.gradle) separates test tasks
and tags; workflow selection remains authoritative.

## 3. Local testing versus operational execution

Ordinary backend unit/integration tasks are `./gradlew test` and `./gradlew integrationTest`.
Frontend unit/HTTP contracts use `npm test` and `npm run test:pact`. These are local/CI entry
points, not an instruction to start a live suite or publish secrets.

Compose-backed Playwright requires approved local fixture/setup prerequisites. Internal seeding
is a write, not a harmless health check. Do not use historical certification accounts, old live
tokens, captured secrets or arbitrary prices as fixtures against the deployed demo.
Post-#320 acceptance is a separate pinned multi-user run, not automatically reproduced by
running the default Playwright config.

## 4. Gaps that remain separate

The accepted live limitations remain those in the dashboard. Source tests alone do not close
unknown-currency rendering, header-strip non-USD coverage, analytics-unavailable fallback,
FX-pair semantics or model-text reliability. The source-confirmed
[advisor IDOR](../todos/backlog/portfolio-advisor-cross-user-authorization/README.md) and
[public price-write hole](../todos/backlog/public-market-price-write-authorization/README.md)
are OPEN defects with owner disposition pending, not hypothetical improvements or covered by
the prior isolation suite. The [header-sanitization proof gap](../todos/backlog/gateway-user-header-spoofing-regression-proof/README.md)
is OPEN without a confirmed current bypass. Insight cache atomicity, outbox delivery, event-ID
dedup, sustained load/failover and cross-cloud recovery remain separate proof/design work.
This inventory authorizes no new tests, changes or live run and reopens no original closed item.



