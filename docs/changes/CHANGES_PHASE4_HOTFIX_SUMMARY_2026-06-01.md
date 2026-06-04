# Phase 4 Hotfix — Chatbot Asset Coverage Fix & CI Hardening
**Date:** 2026-06-01
**Preceding changelog:** `docs/changes/CHANGES_INFRA_SUMMARY_2026-05-31.md`
**Branches:** `fix/chatbot-asset-coverage` (merged → `main` as PR #41), `fix/chatbot-coverage-phase2` (open as PR #42), `perf/playwright-use-system-chrome`
**Spec:** `.kiro/specs/chatbot-asset-coverage-fix/`
**Audit:** `docs/audit/chatbot-asset-coverage-fix-audit.md`

---

## Overview

This session resolved two independent production bugs that together limited the AI chatbot to
roughly 3 visible assets despite 160 tickers being seeded, hardened the test suite against the
audit findings raised during PR review, and fixed a CI build hang that was blocking the integration
test pipeline.

Work split into three tracks:

1. **Production bug fixes (PR #41, merged)** — two root causes in `MarketDataSeedService` and
   `ChatController` that prevented seeded prices from reaching Redis and prevented suffixed symbols
   from being resolved.
2. **Test hardening and integration coverage (PR #42, open)** — Phase 2 test work addressing the
   remaining audit findings: exactly-once publication assertions, `bulk.execute()` failure path,
   Testcontainers Redis integration, real Kafka → listener → Redis pipeline, and Redis-backed chat
   endpoint tests.
3. **CI performance (branch `perf/playwright-use-system-chrome`)** — eliminated the
   `npx playwright install` step from CI by switching to the pre-installed system Chrome on
   GitHub Actions runners.

---

## Track 1 — Production Bug Fixes (PR #41, commit `9eaebd3`)

### Root Cause 1 — `MarketDataSeedService` never published to Kafka

**File:** `market-data-service/src/main/java/com/wealth/market/seed/MarketDataSeedService.java`

`seed(userId)` bulk-upserted 160 registry tickers into MongoDB but never injected or invoked a
`KafkaTemplate`, so `insight-service`'s Redis structures (`market:latest:*`,
`market:history:*`, `market:tracked-tickers`) were never hydrated by the seeder. The chatbot
could only see the small subset published at boot/refresh time.

**Fix:**
- Injected `KafkaTemplate<String, PriceUpdatedEvent>` into `MarketDataSeedService`.
- Computed price captured once into `seededPrice` so MongoDB and Kafka use the identical value.
- Events collected in a `pendingEvents` list during the upsert loop; published only **after**
  `bulk.execute()` returns successfully — prevents Redis hydration for documents that never
  persisted to MongoDB.
- Null-price guard mirrors `StartupHydrationService`: tickers with a null computed price are
  skipped with a `log.debug`.
- Each send wrapped in try/catch for best-effort publish (consistent with
  `StartupHydrationService`); a broker failure is logged and does not roll back the seed.
- `eventsPublished` counter in the log now reflects actual sends, not registry size.

### Root Cause 2 — `ChatController` resolver stripped/rejected suffixed symbols

**File:** `insight-service/src/main/java/com/wealth/insight/ChatController.java`

The resolver's `DOLLAR_TICKER_PATTERN` and `UPPERCASE_TICKER_PATTERN` only captured 1–5 letter
runs. The conversational extractor applied `replaceAll("[^A-Za-z]", "")` and rejected tokens
longer than 5 characters. Suffixed symbols (`ROSE-USD`, `USDCHF=X`, `RELIANCE.NS`) were
therefore stripped or rejected before `findFirstTrackedTicker` was ever called, making the
`market:latest:{S}` lookup unreachable even when the data was present in Redis.

**Fix:**
- Added `extractSuffixCandidates()` recognising three registry suffix shapes:
  - crypto: `^[A-Za-z]{1,15}-USD$`
  - forex: `^[A-Za-z]{3}[A-Za-z]{3}=X$`
  - NSE: `^[A-Za-z]{1,15}\.NS$`
- Suffix candidates bypass the `[^A-Za-z]` strip and the ≤5-letter cap; the full symbol is
  preserved verbatim (e.g. `ROSE-USD`, `USDCHF=X`, `RELIANCE.NS`).
- Resolution order changed to: dollar → **suffix-aware-tracked** → uppercase → conversational.
  Suffix candidates are evaluated before plain uppercase candidates so a tracked `ABC-USD` cannot
  lose to a tracked plain `ABC` extracted from the same token.
- `suffix-aware-fallback` branch removed: untracked suffixed symbols fall through to the
  clarification response, preserving the pre-fix behavior under Property 3.
- Stop-word filtering intentionally kept off the suffix extraction path — the suffix patterns are
  specific enough discriminators; applying the guard would incorrectly block legitimate tracked
  symbols whose stem is a stop word (e.g. `BE-USD`).

### Tests added (bug-condition methodology)

All tests were written on the unfixed code first (exploration), then verified to pass after the fix.

**`market-data-service`:**
- `MarketDataSeedServicePropagationPropertyTest` (jqwik, Property 1) — asserts one
  `PriceUpdatedEvent` per non-null seeded ticker is published to `market-prices` keyed by ticker.
  Was FAIL on unfixed code (0 events), now PASS.
- `MarketDataSeedServicePreservationIT` (Testcontainers MongoDB, Property 4) — pins the seeder's
  MongoDB end state: exactly 160 registry docs, non-registry isolation, value-level idempotency.
  Passes on both unfixed and fixed code.

**`insight-service`:**
- `ChatControllerSuffixResolutionPropertyTest` (jqwik, Property 2) — for generated tracked
  suffixed symbols, asserts `resolveTicker` returns the exact symbol and uses
  `market:latest:{S}`. Was FAIL on unfixed code, now PASS.
- `ChatControllerPreservationPropertyTest` (jqwik, Property 3) — for non-suffixed inputs,
  asserts plain resolution, clarification, no-data, and stop-word exclusion are unchanged.
  Passes on both unfixed and fixed code.
- `ChatControllerSuffixPrecedenceTest` — 7 focused unit tests for the precedence fix (Finding 3)
  and untracked suffix clarification (Finding 4). Each precedence test stubs both the suffixed
  symbol and the competing plain uppercase candidate as tracked, making the tests genuine
  regression guards.

---

## Track 2 — Test Hardening and Integration Coverage (PR #42, commits `f12fd8b` + `9e25740`)

### Build fix — `InfrastructureHealthLoggerProfileTest` CI hang

**File:** `market-data-service/src/test/java/com/wealth/market/InfrastructureHealthLoggerProfileTest.java`

The `aws` and `azure` profile test classes mocked `KafkaTemplate` but not `KafkaAdmin`. When
those contexts started, `InfrastructureHealthLogger.probeKafka()` called
`kafkaAdmin.describeTopics("market-prices")` which blocked for the full Kafka client connection
timeout (~60 s per context) against `localhost:9094`, causing the entire `integrationTest` task
to hang indefinitely in CI.

**Fix:**
- Added `@MockitoBean KafkaAdmin` to the `aws` and `azure` profile test classes so the probe
  returns immediately.
- Added `spring.kafka.bootstrap-servers=localhost:0` as a belt-and-suspenders fast-fail override.

### Gradle test timeouts

**File:** `build.gradle`

Added task-level timeouts (Gradle's `Test.timeout` is a task deadline, not a per-test timeout):
- `test` task: 10 minutes (unit tests).
- `integrationTest` task: 20 minutes (Testcontainers suites).

### `MarketDataSeedServicePropagationHardeningTest` (audit Finding 6)

**File:** `market-data-service/src/test/java/com/wealth/market/seed/MarketDataSeedServicePropagationHardeningTest.java`

Strengthened the existing propagation property test with:
- **Exactly-once publication:** asserts each expected ticker produces exactly one
  `PriceUpdatedEvent`, no duplicates, no unexpected tickers. Total event count equals the number
  of non-null-priced tickers.
- **Failure path:** asserts zero Kafka sends when `bulk.execute()` throws, directly validating
  the Phase 1 ordering fix (events collected in `pendingEvents` before `bulk.execute()`, published
  only after success).
- Both as `@Example` (full 160-ticker golden state) and `@Property` (arbitrary subsets).

### `ChatbotAssetCoverageIT` (audit Finding 7, spec Task 7)

**File:** `insight-service/src/test/java/com/wealth/insight/ChatbotAssetCoverageIT.java`

Testcontainers integration test (real Redis container + embedded Kafka) covering three layers:

**Layer 1 — Redis data layer (req 3.6, 2.1, 2.2):**
- `consumerPipeline_plainSymbol_populatesLatestHistoryAndZset` — latest price, capped 10-item
  history, ZSET membership for a plain symbol.
- `consumerPipeline_historyCapAt10Items` — 11th push trims the oldest entry.
- `consumerPipeline_suffixedSymbols_populatesExactRedisKeys` — exact key preservation for
  `ROSE-USD`, `USDCHF=X`, `RELIANCE.NS` (no stripping or normalization).
- `consumerPipeline_staleTickerPruning_removesOldEntries` — ZSET prunes entries older than 24 h.
- `marketSummary_returnsAllSeededTickers_withNonNullPrices` — market summary includes suffixed
  symbols with non-null prices.

**Layer 2 — Real Kafka → `InsightEventListener` → Redis (req 2.1, 2.2, 3.6):**
- `@EmbeddedKafka` wires an in-process Kafka broker. A JSON-serializing `KafkaTemplate` is built
  from `KafkaTestUtils.producerProps` (insight-service is consumer-only; its auto-configured
  template uses `StringSerializer`, so a separate producer is constructed).
- `kafkaToRedis_plainSymbol_listenerConsumesAndPopulatesRedis` — publishes to `market-prices`,
  Awaitility polls until `market:latest:NVDA` is populated.
- `kafkaToRedis_suffixedSymbol_listenerPreservesExactKey` — same for `BTC-USD`, validates exact
  key preservation through the full Kafka → listener path.

**Layer 3 — Redis-backed chat endpoint (req 2.3–2.6, 3.1):**
- `chatEndpoint_cryptoSuffix_resolvesAndReturnsSummary` — `POST /api/chat` with `ROSE-USD` seeded
  in Redis resolves to the exact suffixed symbol and returns its price. Full path: HTTP request →
  suffix-aware resolver → `MarketDataService.getTickerSummary("ROSE-USD")` → response.
- `chatEndpoint_forexSuffix_resolvesAndReturnsSummary` — same for `USDCHF=X`.
- `chatEndpoint_nseSuffix_resolvesAndReturnsSummary` — same for `RELIANCE.NS`.
- `chatEndpoint_plainSymbol_stillResolvesAfterSuffixChanges` — `AAPL` still resolves correctly
  in the same context.
- `chatEndpoint_untrackedSuffixedSymbol_returnsClarification` — untracked `ROSE-USD` returns
  clarification (not no-data), validating the `suffix-aware-fallback` removal.

`spring-kafka-test` added to `insight-service` `testImplementation` dependencies.

---

## Track 3 — CI Performance (branch `perf/playwright-use-system-chrome`, commit `1347c33`)

**Files:** `.github/workflows/ci-verification.yml`, `.github/workflows/frontend-ci.yml`,
`frontend/playwright.config.ts`

The `npx playwright install --with-deps chromium` step was downloading Playwright's bundled
Chromium and running an `apt` dependency pass on every CI runner, contributing to slow build
times.

**Fix:**
- Removed the `npx playwright install --with-deps chromium` step from both `ci-verification.yml`
  and `frontend-ci.yml`.
- Added a `ciChannel` constant in `playwright.config.ts` that sets `channel: 'chrome'` when
  `CI=true` (auto-set by GitHub Actions), directing Playwright to use the Google Chrome binary
  already present on `ubuntu-latest` runners (`/usr/bin/google-chrome`).
- Local developer behavior is unchanged: `ciChannel` resolves to `{}` when `CI` is unset, so
  Playwright continues to use its own Chromium build locally.

---

## Commit Log

| Commit | Branch | Summary |
|--------|--------|---------|
| `9eaebd3` | `main` (PR #41) | fix(chatbot): fix chatbot asset coverage — seed Kafka propagation + suffix symbol resolution |
| `f12fd8b` | `fix/chatbot-coverage-phase2` | fix(build) + test(chatbot): integration test timeout fix and Phase 2 coverage |
| `9e25740` | `fix/chatbot-coverage-phase2` | fix(review): address all 4 PR #42 review findings |
| `1347c33` | `perf/playwright-use-system-chrome` | perf(ci): use preinstalled system Chrome instead of playwright install |

---

## Files Changed Summary

| Area | Files |
|------|-------|
| Production fix — seed | `market-data-service/src/main/java/com/wealth/market/seed/MarketDataSeedService.java` |
| Production fix — resolver | `insight-service/src/main/java/com/wealth/insight/ChatController.java` |
| Build config | `build.gradle`, `insight-service/build.gradle` |
| New tests — market-data-service | `MarketDataSeedServicePropagationPropertyTest.java`, `MarketDataSeedServicePreservationIT.java`, `MarketDataSeedServicePropagationHardeningTest.java` |
| New tests — insight-service | `ChatControllerSuffixResolutionPropertyTest.java`, `ChatControllerPreservationPropertyTest.java`, `ChatControllerSuffixPrecedenceTest.java`, `ChatbotAssetCoverageIT.java` |
| Modified tests | `market-data-service/.../InfrastructureHealthLoggerProfileTest.java` |
| CI workflows | `.github/workflows/ci-verification.yml`, `.github/workflows/frontend-ci.yml` |
| Frontend config | `frontend/playwright.config.ts` |
| Spec / audit | `.kiro/specs/chatbot-asset-coverage-fix/tasks.md`, `docs/audit/chatbot-asset-coverage-fix-audit.md` |

---

## Open Items

- **PR #42** (`fix/chatbot-coverage-phase2`) — pending review and merge. Contains the build fix,
  Phase 2 test hardening, and Testcontainers integration coverage.
- **`perf/playwright-use-system-chrome`** — pending review and merge. CI performance improvement;
  no production code changes.
- **Audit doc** (`docs/audit/chatbot-asset-coverage-fix-audit.md`) — at v3. Findings 1–5 resolved,
  Finding 6 addressed by `MarketDataSeedServicePropagationHardeningTest`, Finding 7 addressed by
  `ChatbotAssetCoverageIT`. Audit status: Request Changes → pending PR #42 merge.
