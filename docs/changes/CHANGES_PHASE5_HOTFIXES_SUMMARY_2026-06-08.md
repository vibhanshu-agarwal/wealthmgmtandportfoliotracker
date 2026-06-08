# Phase 5 — LLM-Driven Natural-Language Asset Resolution
**Date range:** 2026-06-01 → 2026-06-07
**Preceding changelog:** `docs/changes/CHANGES_PHASE4_HOTFIX_SUMMARY_2026-06-01.md`
**Spec:** `.kiro/specs/chatbot-natural-language-resolution/`
**PRs merged:** #41, #42, #43, #44, #45, #46, #47, #48, #49, #50, #51, #53, #54, #55
**Latest commit:** `f725c27` (`test(wave9)`) on `main`

---

## Overview

This period covers two distinct bodies of work that landed on `main` between 2026-06-01 and
2026-06-06.

**Track A — Phase 4 carry-over (2026-06-01, PRs #41–#43):** Production bug fixes for the AI
chatbot's asset coverage, integration test hardening, and a CI performance improvement. These were
already in progress when the prior changelog was written; they are documented here for
completeness.

**Track B — Phase 5: LLM-driven natural-language resolution (2026-06-02–2026-06-06, PRs
#44–#55):** Nine waves of implementation across insight-service that replace the regex-based
`ChatController` with a fully LLM-grounded, catalog-validated, seven-step resolution pipeline.
Covers the complete `chatbot-natural-language-resolution` spec from data-model scaffolding through
end-to-end correctness-property tests and a post-deploy verification suite.

---

## Track A — Phase 4 Carry-over (2026-06-01)

### PR #41 — Chatbot asset coverage: seed propagation + suffix resolution (`9eaebd3`)

Two independent production bugs limited the chatbot to ~3 visible assets despite 160 tickers
being seeded.

**`MarketDataSeedService` — Root Cause 1:**
- `seed(userId)` never published to Kafka, so `insight-service`'s Redis structures were never
  hydrated by the seeder.
- Fix: injected `KafkaTemplate<String, PriceUpdatedEvent>`; events collected in `pendingEvents`
  before `bulk.execute()`, published only after success; null-price guard added; best-effort
  per-send try/catch; `eventsPublished` counter corrected.

**`ChatController` — Root Cause 2:**
- The resolver stripped `[^A-Za-z]` from tokens and capped candidates at ≤5 letters, making
  `ROSE-USD`, `USDCHF=X`, and `RELIANCE.NS` unreachable.
- Fix: `extractSuffixCandidates()` added for three suffix patterns (crypto `-USD`, forex `=X`,
  NSE `.NS`). Evaluation order changed to: dollar → suffix-aware-tracked → uppercase →
  conversational. `suffix-aware-fallback` branch removed (untracked suffixes fall through to
  clarification).

Tests added: `MarketDataSeedServicePropagationPropertyTest` (jqwik, was FAIL on unfixed code),
`MarketDataSeedServicePreservationIT` (Testcontainers MongoDB), `ChatControllerSuffixResolutionPropertyTest`
(jqwik, was FAIL), `ChatControllerPreservationPropertyTest` (jqwik), `ChatControllerSuffixPrecedenceTest`
(7 targeted unit tests, genuine regression guards).

### PR #42 — Build fix + Phase 2 test coverage (`789fed0`, `9eaebd3` carry-over review)

**CI hang fix — `InfrastructureHealthLoggerProfileTest` (`market-data-service`):**
- `KafkaAdmin` was not mocked in `aws`/`azure` profile test classes, causing
  `probeKafka()` to block for ~60 s per context against `localhost:9094`.
- Fix: `@MockitoBean KafkaAdmin` added; `spring.kafka.bootstrap-servers=localhost:0` added as
  fast-fail override.

**Gradle task-level timeouts (`build.gradle`):**
- `test` task: 10-minute deadline.
- `integrationTest` task: 20-minute deadline.
- Removed a misplaced `tasks.withType(Test).configureEach` block that applied to all Test tasks.

**`MarketDataSeedServicePropagationHardeningTest` (audit Finding 6):**
- Exactly-once publication assertion: each non-null-priced ticker produces exactly one
  `PriceUpdatedEvent`, no duplicates, no unexpected tickers.
- Failure path: zero Kafka sends when `bulk.execute()` throws.
- Both as `@Example` (full 160-ticker golden state) and `@Property` (arbitrary subsets).

**`ChatbotAssetCoverageIT` (audit Finding 7, spec Task 7):**
- Three-layer Testcontainers suite (real Redis + embedded Kafka):
  - Layer 1 — Redis data layer: `consumerPipeline_*` tests for plain symbols, suffixed symbols,
    capped history (10 items), ZSET membership, and 24-hour stale-entry pruning.
  - Layer 2 — Real Kafka → `InsightEventListener` → Redis: `@EmbeddedKafka`-wired with a
    separate JSON-serializing `KafkaTemplate`; `kafkaToRedis_*` tests for plain and suffixed
    symbols with Awaitility polling.
  - Layer 3 — Redis-backed chat endpoint: `chatEndpoint_*` tests for `ROSE-USD`, `USDCHF=X`,
    `RELIANCE.NS`, plain `AAPL`, and untracked suffixed clarification.
- `spring-kafka-test` added to `insight-service` testImplementation.

### PR #43 — CI: system Chrome for Playwright (`5acd5c2`)

- Removed `npx playwright install --with-deps chromium` from `ci-verification.yml` and
  `frontend-ci.yml`.
- `playwright.config.ts`: `ciChannel` constant sets `channel: 'chrome'` when `CI=true`,
  directing Playwright to the pre-installed Google Chrome on `ubuntu-latest` runners.
- Local dev unchanged: `ciChannel` resolves to `{}` when `CI` is unset.

---

## Track B — Phase 5: LLM-Driven Natural-Language Resolution (2026-06-02–2026-06-06)

### PR #44 — CI: restore deploy dispatcher push trigger (`6c3ea77`, 2026-06-02)

`deploy.yml` had been set to `workflow_dispatch`-only on 2026-05-13 (Azure Demo Readiness Phase 2)
with a note to re-enable after the demo. The restore never happened, so no merge to `main`
auto-deployed since. Restored the original `push: branches: [main]` + paths filter. PRs #41/#42
were merged green but had never been built/pushed to ACR or rolled out to the ACA
`insight-service` revision.

### Wave 1 — PR #45 — Ticker catalog enrichment + data models (`5ea870`)

**Task 1 — `config/seed-tickers.json` enrichment:**
- `name` (string) and `aliases` (string array) added to all 160 entries.
- Preserves exact count and asset-class distribution (50 US_EQUITY / 50 NSE / 50 CRYPTO / 10 FOREX).
- All existing fields (`ticker`, `assetClass`, `quoteCurrency`, `basePrice`) unchanged.

**Fix — `SeedTicker` schema tolerance:**
- `market-data-service` and `portfolio-service` `SeedTickerRegistry.SeedTicker` records annotated
  with `@JsonIgnoreProperties(ignoreUnknown = true)` to tolerate the enriched JSON schema
  without `UnrecognizedPropertyException`.

**Task 3 — Catalog and resolution data models (`insight-service`):**
- `CatalogEntry`: immutable record (ticker, name, aliases, assetClass, quoteCurrency — no basePrice,
  prices are Redis-only per Property 2).
- `CompactCatalog`: price-free grounding payload DTO for the LLM client.
- `Intent` enum: `ASSET_QUERY`, `DISCOVERY`, `COMPARISON`, `GREETING_HELP`, `UNKNOWN`.
- `Outcome` enum: `RESOLVED`, `CLARIFICATION`, `NO_DATA`, `DISCOVERY`, `COMPARISON_REDIRECT`,
  `GREETING_HELP`.
- `LlmResolution`: untrusted LLM proposal DTO with `@JsonIgnoreProperties`.
- `ResolutionOutcome`: catalog-validated trusted result with factory helpers.
- Spec files added: `.kiro/specs/chatbot-natural-language-resolution/` (requirements, design, tasks).

### Wave 2 — PR #46 — Catalog service + resolution port (`331fe6`)

**Task 2 — Backward-compatible `SeedTicker` extension:**
- `name` and `aliases` fields added to `SeedTicker` in `market-data-service` and
  `portfolio-service`. `ObjectMapper` configured with `FAIL_ON_UNKNOWN_PROPERTIES=false` in
  both `SeedTickerRegistry` beans.
- `SeedTickerRegistryTest` added to both services: count = 160, asset-class distribution,
  non-blank names, non-null aliases.

**Task 4 — `TickerCatalogService` (`insight-service`):**
- Loads catalog at startup (`@PostConstruct`), builds in-memory index.
- `isSupported`, `find`, `byCategory`, `groundingView`, `catalogVersion` methods.
- Deterministic `normalize()`: exact match passthrough; `BTC`/`BTCUSD`/`BTC/USD` → `BTC-USD`;
  `USDCHF`/`USD/CHF` → `USDCHF=X`; unknown → `Optional.empty()`.
- Integrity checks at startup: blank names, null aliases, duplicate tickers.
- `catalogVersion`: stable 8-char SHA-256 hex over sorted ticker set (Req 8.3).
- `groundingView()`: price-free `CompactCatalog` (Req 7.4).
- `TickerCatalogServiceTest`: 30 deterministic tests.

**Task 5 — `AssetResolutionClient` port + test double:**
- `AssetResolutionClient` interface: `resolve(String, CompactCatalog) → LlmResolution`.
- `StubAssetResolutionClient` (test sources): canned responses by exact message, custom
  `BiFunction` handler, throw mode, call counting, factory helpers.
- `StubAssetResolutionClientTest`: 12 tests.

**CI fix (bundled):** `deploy.yml` push-to-main trigger restored (see PR #44 — bundled in the
Wave 1/2 PRs as the same commit was included in the squash).

### Wave 3 — PR #48 — Azure OpenAI adapter + `ChatResponseBuilder` (`53147f`)

**Task 6 — `AzureOpenAiAssetResolutionClient` (`@Profile azure-ai`):**
- Implements `AssetResolutionClient` port.
- System prompt with prompt-injection resistance and catalog-only constraint (Req 2.6, 2.7).
- Compact catalog (no prices) sent inline for grounding (Req 7.4, 8.3).
- Deserializes `LlmResolution` via `.entity()` (Spring AI structured output).
- `LlmResolutionException` with three `Kind` values: `UNAVAILABLE`, `TIMEOUT`, `MALFORMED`.
- `isTimeout()` helper maps `SocketTimeoutException` to `Kind.TIMEOUT` (Req 6.6).
- `AzureOpenAiAssetResolutionClientTest`: happy-path, null-entity MALFORMED,
  runtime-exception UNAVAILABLE, prompt content, injection resistance language, no-price constraint.

**Task 7 — `ChatResponseBuilder`:**
- Currency-aware price formatting: INR for `*.NS`, USD for `US_EQUITY`/`CRYPTO`, bare price for FOREX.
- `RESOLVED`: fetches `TickerSummary`; null `latestPrice` → `NO_DATA`.
- `CLARIFICATION`: lists named candidates (name + ticker) (Req 2.6).
- `COMPARISON_REDIRECT`: both display-name and ticker for each candidate (Req 5.1, Property 5).
- `DISCOVERY`: prefers active Redis universe; falls back to catalog with unavailability wording;
  bounded ~20 total / ~5 per category with "and more" indicator (Req 4.2, 4.4). `TreeMap`
  collector for deterministic asset-class ordering.
- `GREETING_HELP`: capability overview.
- Property 6 (never-empty): every branch returns non-blank response.
- `max-tokens: 500` added to `application-azure-ai.yml` (Req 8.4).
- `ChatResponseBuilderTest`: all 6 outcome types, per-asset-class currency, no-data, sentiment
  integration, discovery bounded/grouped/fallback, comparison redirect.

**Hotfix — duplicate `SeedTicker` record (#47, `2d198ca`):**
- Both the 4-field (Wave 1) and 6-field (Wave 2) `SeedTicker` records coexisted in
  `SeedTickerRegistry` after Wave 2 merged, causing a compile error
  (`record SeedTicker is already defined`).
- Removed the stale 4-field records from `market-data-service` and `portfolio-service`.

### Wave 4 — PR #49 — `ChatResolutionService` seven-step pipeline (`40168f9`)

**Core implementation (`ChatResolutionService`):**
1. Explicit ticker short-circuit (`$` / bare uppercase).
2. Preflight catalog normalization of all uppercase tokens.
3. Discovery shortcut (`list stocks` / `show crypto`) — bypasses LLM.
4. LLM resolution for natural-language asset names.
5. Catalog validation — every LLM candidate grounded in `TickerCatalogService`.
6. Intent branching: comparison redirect / discovery / single-asset.
7. Response building via `ChatResponseBuilder`.

`ChatController` reduced to a thin delegate over `ChatResolutionService`.
`MockAssetResolutionClient` added as deterministic AI stub for unit tests.

**Bug fixes:**
- `isDiscoveryQuery` / `extractCategoryFilter`: replaced `Set.of()` with `HashSet` to tolerate
  duplicate tokens (`IAE` on repeated words).
- `COMPARISON_CUES`: removed bare `vs` to prevent substring collision with the `VS` ticker
  symbol; kept `vs.`, `compare`, `versus`.

**PR review fixes (bundled in PR #50):**
- Discovery shortcut precision (Req 4.1): `isDiscoveryQuery()` now skips the
  listing-word + asset-class-word check when a capitalized mid-sentence token is present
  (entity guard). `'what is Apple stock?'` no longer resolves as DISCOVERY.
- Explicit ticker normalization (Req 1.5): Step 1 calls `catalog.normalize(t)` instead of
  `isSupported(t)`, restoring crypto/forex stem resolution.
- `ChatResolutionServiceTest` grew from 37 to 45 cases (8 new).

**Test suite:**
- `ChatResolutionServiceTest`: 20+ unit tests covering all 7 steps, comparison guard, discovery
  shortcuts, LLM fallback, and `responseNeverEmpty` property test.
- `ChatControllerSliceTest`: end-to-end controller slice with mocked deps.
- `ChatControllerPreservationPropertyTest`: jqwik property tests for legacy behaviour.
- Updated: `ChatControllerTest`, `ChatControllerSuffixPrecedenceTest`,
  `ChatControllerSuffixResolutionPropertyTest`, `TickerExtractionTest`,
  `TickerExtractionPropertyTest`, `ChatbotAssetCoverageIT`.
- Kafka Awaitility timeout increased to 30 s for CI stability.

### Wave 5 — PR #50 — Fallback, controller wiring, structured logging (`e4c895e`)

**Tasks 9–12:**
- LLM fallback path wired into `ChatResolutionService.handle()` via catch block; `fallback()`
  produces `fallback-exact` source (not reachable from the try block — dead branch removed).
- Structured logging via `logOutcome()`: fields `intent`, `entities`, `fallbackReason`,
  `resolverLatencyMs`, `responsePath` per Req 9.1.
- `ResolveResult` carrier record added for `llmEntities` propagation.
- `inferIntent()` helper extracted.
- `ChatControllerSliceTest` extended with 78-line coverage section.

**CI fixes (bundled):**
- `ci-verification.yml`: `feature/**` added to push trigger (previously only `main` and
  `architecture/**`).
- `gitleaks.yml`, `qodana_code_quality.yml`, `frontend-ci.yml`: `feature/**` added to push
  triggers.
- `frontend-e2e-integration.yml` left as `workflow_dispatch` only (intentional).

**Chore:**
- Accidentally staged `.tmp_kafka_src/` and `.tmp_kafka_src2/` (379 Spring Kafka library source
  files + 2 binary zips) untracked and removed from the repository.
- `.gitignore` tightened: `.tmp*/` and `.tmp_kafka_src*/` rules added.

**Conflict fix (PR #51, `958b4c7`):**
- The merge of `main` into the feature branch resolved conflicts by taking `main`'s older
  `ChatResolutionService`, stripping the `ResolveResult` record, `logOutcome()` fields, and
  `inferIntent()` helper, and deleting 78 lines from `ChatControllerSliceTest`.
- Both files restored from `ca812cb` (last clean Wave 5 commit). Full suite: 1736 tests,
  1 skipped, 0 failures.

### Wave 6 — PR #53 — End-to-end resolution tests (`4f13195`)

**`Wave6ResolutionEndToEndTest` — 27 tests covering the full
`ChatController` → `ChatResolutionService` → `ChatResponseBuilder` stack (zero live LLM calls):**

- **Task 13 — Natural-language paths:** exact symbol preflight (AAPL, RELIANCE.NS), crypto
  normalization (BTC / BTCUSD / BTC-USD), forex normalization (USDCHF / USD/CHF=X), alias LLM
  resolution (Apple→AAPL, Bitcoin→BTC-USD, HDFC Bank→HDFCBANK.NS), explicit ticker field,
  catalog-rejection of invented tickers (P1), Redis no-data response (P2).
- **Task 14 — Comparison guard:** two-ticker preflight triggers `COMPARISON_REDIRECT` naming both
  full display names with canonical tickers; single-symbol inputs resolve normally (P5).
- **Task 15 — Discovery:** grouped listing with names+tickers, CRYPTO-scoped and NSE-scoped
  filters, "and more" truncation at `DISCOVERY_MAX_PER_CATEGORY=5`, catalog-fallback wording
  when Redis is empty (Req 4.1–4.5).
- **Task 16 — Controller slice / never-empty (P6):** HTTP 200 + non-empty response across all
  outcome paths; zero LLM calls for deterministic paths; `LlmResolutionException` fallback still
  returns non-empty response.

**Production change:** `TickerCatalogService.load()` widened from package-private to `public`
so `@PostConstruct` can be invoked directly in test `setUp()` without a full Spring context.

### Azure SWA fix — PR #54 (`868db99`, 2026-06-05)

Two issues with the Azure Static Web Apps deployment:

1. `staticwebapp.config.json` was at `frontend/` but `app_location` is `frontend/out`. The SWA
   action never received the routing/header config. Moved to `frontend/public/` so Next.js static
   export copies it to `out/` automatically on every build.
2. Azure SWA Free tier occasionally times out (~600 s) during server-side deployment processing.
   A single retry step wired with `if: steps.swa_deploy.outcome != success` handles the transient
   case.

Files changed: `.github/workflows/deploy-azure.yml`, `frontend/public/staticwebapp.config.json`
(moved from `frontend/staticwebapp.config.json`).

### Wave 7 — PR #55 — Correctness-property tests P1–P8 (`470ace2`)

**`CorrectnessPropertyTest` — 29 tests, machine-checkable assertions for all eight design
properties:**

- **P1 — Catalog-bounded:** LLM-invented tickers dropped at catalog gate (single invented,
  partial valid/invalid mix, unsupported explicit, valid passthrough).
- **P2 — Redis-only facts:** `LlmResolution` has no price fields (structural invariant); prices
  fetched from `MarketDataService` (Redis); null Redis price → no-data without querying
  `AiInsightService`.
- **P3 — Exact-symbol preservation:** 5 parameterised cases (BTC-USD, USDCHF=X, HDFCBANK.NS,
  RELIANCE.NS, AAPL) with zero LLM calls.
- **P4 — Single LLM call:** explicit, preflight, and discovery-shortcut paths make 0 calls;
  LLM path makes exactly 1.
- **P5 — No silent wrong pick:** two preflight symbols → `COMPARISON_REDIRECT`; LLM ambiguous →
  `CLARIFICATION`; unambiguous → `RESOLVED`.
- **P6 — Never-empty:** all six `ResolutionOutcome` types, empty message, and LLM failure all
  produce non-blank responses.
- **P7 — Fallback safety:** preflight resolves even when LLM is unavailable; LLM timeout with
  name-only query falls back to `CLARIFICATION` via fallback-exact; comparison guard survives
  LLM malformed response.
- **P8 — Determinism:** preflight, fallback, and explicit-ticker paths each produce identical
  outcomes across three independent invocations.

**Race fix (`ChatbotAssetCoverageIT`):** moved ZSET assertion inside `untilAsserted()` lambda so
Awaitility retries until both `market:latest:{S}` key and ZSET membership are visible,
eliminating the inter-write OS context-switch race.

### Wave 9 — PR #55 bundle — Post-deploy verification (`f725c27`, 2026-06-06)

**`PostDeployVerificationIT` — 443-line integration test suite (Task 19):**

Covers Req 1.1, 1.2, 1.3, 4.1, and 9.1 as a post-deploy smoke harness:
- Validates the full deployed stack end-to-end against a running environment.
- Structured log output verification (Req 9.1 observability schema).
- Discovery endpoint correctness (Req 4.1).
- Resolves Tasks 1.1 (basic resolution), 1.2 (suffix symbols), and 1.3 (catalog grounding) as
  live smoke assertions.

---

## Test Suite Summary

| Milestone | Test count | Notes |
|-----------|-----------|-------|
| After PR #41 | ~1800 | Property tests added for seed propagation + suffix resolution |
| After PR #42 | ~1830 | `ChatbotAssetCoverageIT` + hardening tests |
| After Wave 2 (PR #46) | ~1940 | `TickerCatalogServiceTest` + `SeedTickerRegistryTest` |
| After Wave 3 (PR #48) | ~1964 | `AzureOpenAiAssetResolutionClientTest` + `ChatResponseBuilderTest` |
| After Wave 4–5 (PR #50) | ~1736 (post-conflict-clean) | Full resolution pipeline suite |
| After Wave 6 (PR #53) | +27 | `Wave6ResolutionEndToEndTest` |
| After Wave 7 (PR #55) | +29 | `CorrectnessPropertyTest` |
| After Wave 9 (PR #55) | +443 lines | `PostDeployVerificationIT` |

All builds: `BUILD SUCCESSFUL`, 1 skipped test (Bedrock live smoke, intentional).

---

## Commit Log

| Commit | Date | PR | Summary |
|--------|------|----|---------|
| `9eaebd3` | 2026-06-01 | #41 | fix(chatbot): seed Kafka propagation + suffix symbol resolution |
| `789fed0` | 2026-06-01 | #42 | fix(build) + test(chatbot): CI hang fix + Phase 2 coverage |
| `5acd5c2` | 2026-06-01 | #43 | perf(ci): use preinstalled system Chrome |
| `6c3ea77` | 2026-06-02 | #44 | fix(ci): restore push-to-main trigger on deploy dispatcher |
| `5ea8709` | 2026-06-03 | #45 | feat(wave1): enrich ticker catalog + catalog/resolution data models |
| `331fe65` | 2026-06-04 | #46 | feat(wave2): catalog service + resolution port |
| `2d198ca` | 2026-06-04 | #47 | fix: remove duplicate SeedTicker record definitions |
| `53147fc` | 2026-06-04 | #48 | feat(wave3): Azure OpenAI adapter + ChatResponseBuilder |
| `40168f9` | 2026-06-04 | #49 | feat(wave4): LLM-driven natural-language asset resolution |
| `e4c895e` | 2026-06-05 | #50 | feat(wave5): fallback, controller wiring, structured logging |
| `958b4c7` | 2026-06-05 | #51 | fix: restore Wave 5 structured logging lost in merge conflict |
| `4f13195` | 2026-06-05 | #53 | test(wave6): end-to-end resolution tests for Tasks 13-16 |
| `868db99` | 2026-06-05 | #54 | fix(swa): move staticwebapp.config.json to public/ + deploy retry |
| `470ace2` | 2026-06-06 | #55 | feat(wave7): correctness-property assertions P1–P8 |
| `f725c27` | 2026-06-06 | #55 | test(wave9): Task 19 — post-deploy demo verification |

---

## Files Changed Summary

| Area | Representative files |
|------|---------------------|
| Seed config | `config/seed-tickers.json` (all 160 entries enriched) |
| market-data-service — production | `seed/MarketDataSeedService.java`, `seed/SeedTickerRegistry.java` |
| portfolio-service — production | `seed/SeedTickerRegistry.java` |
| insight-service — catalog | `catalog/CatalogEntry.java`, `catalog/CompactCatalog.java`, `catalog/TickerCatalogService.java` |
| insight-service — resolution models | `resolution/Intent.java`, `resolution/Outcome.java`, `resolution/LlmResolution.java`, `resolution/ResolutionOutcome.java`, `resolution/LlmResolutionException.java` |
| insight-service — LLM adapter | `AzureOpenAiAssetResolutionClient.java`, `AssetResolutionClient.java` |
| insight-service — response | `ChatResponseBuilder.java` |
| insight-service — orchestration | `ChatResolutionService.java`, `ChatController.java` |
| insight-service — config | `src/main/resources/application-azure-ai.yml` |
| insight-service — tests | `TickerCatalogServiceTest.java`, `ChatResolutionServiceTest.java`, `ChatResponseBuilderTest.java`, `AzureOpenAiAssetResolutionClientTest.java`, `Wave6ResolutionEndToEndTest.java`, `CorrectnessPropertyTest.java`, `PostDeployVerificationIT.java`, `ChatbotAssetCoverageIT.java` |
| market-data-service — tests | `MarketDataSeedServicePropagationPropertyTest.java`, `MarketDataSeedServicePropagationHardeningTest.java`, `MarketDataSeedServicePreservationIT.java`, `SeedTickerRegistryTest.java`, `InfrastructureHealthLoggerProfileTest.java` |
| portfolio-service — tests | `SeedTickerRegistryTest.java` |
| Spec | `.kiro/specs/chatbot-natural-language-resolution/` (requirements.md, design.md, tasks.md) |
| CI workflows | `.github/workflows/ci-verification.yml`, `deploy.yml`, `deploy-azure.yml`, `frontend-ci.yml`, `gitleaks.yml`, `qodana_code_quality.yml` |
| Frontend | `playwright.config.ts`, `public/staticwebapp.config.json` (moved from root) |
| Build | `build.gradle`, `insight-service/build.gradle` |
| Repo hygiene | `.gitignore` |

---

## Open Items at 2026-06-07

- Validate `PostDeployVerificationIT` against a live Azure environment (Task 19 smoke tests are
  structural but have not run against a deployed revision of this wave's code).
- Wave 8 (Tasks not yet listed in the spec) — not yet started.
- `chatbot-asset-coverage-fix` audit doc — at v3, findings 1–7 all resolved; archive or close.
- Redis-backed rate limiting (high-priority TODO from tech.md) — not yet actioned.
- Kafka Dead-Letter Queue for `PriceUpdatedEventListener` (high-priority TODO from tech.md) — not yet actioned.
