# Implementation Plan

## Overview

This bugfix addresses two independent root causes, each validated with the bug-condition
methodology (exploration-first, then fix checking and preservation checking):

- **Root Cause 1 (Seed):** `MarketDataSeedService.seed()` upserts the 160 registry tickers into
  MongoDB but publishes no `PriceUpdatedEvent`, so the chatbot's Redis is never hydrated.
- **Root Cause 2 (Resolve):** `ChatController.resolveTicker()` strips/rejects suffixed symbols
  (`-USD`, `=X`, `.NS`), so tracked symbols cannot be matched even when present in Redis.

Exploration tests (Properties 1 & 2) and preservation tests (Properties 3 & 4) are written and run
on the UNFIXED code BEFORE any fix is applied.

## Tasks

- [x] 1. Write bug condition exploration test — seed → Kafka propagation (Root Cause 1)
  - **Property 1: Bug Condition** - Golden-state seeding propagates to Redis via Kafka
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate the seeder emits zero Kafka events
  - **Module/Location**: `market-data-service` (jqwik, mock/captured `KafkaTemplate<String, PriceUpdatedEvent>`); add `net.jqwik:jqwik:1.9.2` as a `testImplementation` dependency to `market-data-service`
  - **Bug Condition (from design)**: `isBugCondition_Seed(X) = X.isGoldenStateSeed AND X.upsertedTickerCount > 0 AND eventsPublishedTo("market-prices", DURING X) is empty`
  - **Property-Based Test**: For arbitrary subsets/orderings of the seeded registry, invoke `MarketDataSeedService.seed(userId)` with a captured/mock `KafkaTemplate` and assert that for each upserted ticker with a non-null computed price, a `PriceUpdatedEvent(ticker, seededPrice)` was published to `market-prices`, keyed by ticker, where `seededPrice = DeterministicPriceCalculator.compute(basePrice, ticker, userId)` (the same value written to MongoDB)
  - The test assertions should match the Expected Behavior Properties (Property 1) from design
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct - it proves the bug exists: 0 events published)
  - Document counterexamples found (e.g., "seed('e2e-user') upserts 160 docs but publishes 0 events to market-prices")
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.1, 1.2, 2.1, 2.2_

- [x] 2. Write bug condition exploration test — suffixed symbol resolution (Root Cause 2)
  - **Property 2: Bug Condition** - Suffixed tracked symbols resolve to themselves
  - **CRITICAL**: This test MUST FAIL on unfixed code - failure confirms the bug exists
  - **DO NOT attempt to fix the test or the code when it fails**
  - **NOTE**: This test encodes the expected behavior - it will validate the fix when it passes after implementation
  - **GOAL**: Surface counterexamples that demonstrate suffixed symbols are unresolvable
  - **Module/Location**: `insight-service` (jqwik already a `testImplementation` dependency); drive `ChatController.resolveTicker` via the `MockMvc` standalone setup pattern from `ChatControllerTest`, with `MarketDataService` stubbed so the suffixed symbols are tracked (non-null `market:latest:{S}`)
  - **Bug Condition (from design)**: `isBugCondition_Resolve(X) = isTracked(S) AND hasSuffixFormat(S) AND resolveTicker(X) != S`, where `hasSuffixFormat(S)` matches one of `-USD | =X | .NS`
  - **Scoped PBT Approach**: For deterministic resolver inputs, scope the property generator to tracked symbols over the suffix alphabet `{-USD, =X, .NS}` (e.g. `ROSE-USD`, `USDCHF=X`, `RELIANCE.NS`) so failing cases are reproducible
  - **Property-Based Test**: For generated tracked suffixed symbols `S`, assert `resolveTicker(message referencing S) == S` (full symbol, suffix intact) AND the Redis lookup key equals `market:latest:{S}`
  - The test assertions should match the Expected Behavior Properties (Property 2) from design
  - Run test on UNFIXED code
  - **EXPECTED OUTCOME**: Test FAILS (this is correct - it proves the bug exists)
  - Document counterexamples found (e.g., "ROSE-USD → stripped to ROSEUSD (7 chars, rejected) → clarification response"; "USDCHF=X → USDCHF (6 chars, rejected)"; "RELIANCE.NS → RELIANCE (8 letters, rejected)")
  - Mark task complete when test is written, run, and failure is documented
  - _Requirements: 1.3, 1.4, 1.5, 1.6, 2.3, 2.4, 2.5, 2.6_

- [x] 3. Write preservation property tests — resolver behavior unchanged (BEFORE implementing fix)
  - **Property 3: Preservation** - Plain-symbol resolution and resolver responses unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - **Module/Location**: `insight-service` (jqwik), `ChatControllerTest` `MockMvc` standalone pattern
  - **Non-bug condition (from design)**: `NOT isBugCondition_Resolve(X)` — any non-suffixed input: plain ≤5-letter tokens (with/without leading `$`), stop-word-only or ticker-free messages, ambiguous multi-ticker messages, and resolved tickers with no Redis price
  - Observe behavior on UNFIXED code and record outcomes, for example:
    - Observe: "How is AAPL doing?" resolves to `AAPL` and returns its summary (req 3.1)
    - Observe: "$MSFT" resolves to `MSFT` (req 3.1)
    - Observe: a ticker-free / stop-word-only message ("IS THE DO") returns the clarification response "I couldn't identify a single ticker symbol from your message" (req 3.2, 3.3)
    - Observe: a resolved ticker with a null-price summary returns "I don't have any data for {ticker} right now…" (req 3.4)
  - Write property-based tests capturing observed behavior patterns: for all generated non-suffixed inputs, the resolution source/value and the response category produced by `F'` equal the observed `F` outcome (plain resolution, clarification, no-data, stop-word exclusion, candidate ordering/fallback)
  - Property-based testing generates many test cases for stronger preservation guarantees
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms the baseline resolver behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [x] 4. Write preservation property tests — seeder MongoDB behavior unchanged (BEFORE implementing fix)
  - **Property 4: Preservation** - Seeder MongoDB behavior unchanged
  - **IMPORTANT**: Follow observation-first methodology
  - **Module/Location**: `market-data-service`, Testcontainers MongoDB (`@Tag("integration")`)
  - **Scope (from design)**: Event publication is additive and MUST NOT alter the MongoDB end state; documents outside the 160-ticker registry set must be untouched
  - Observe behavior on UNFIXED code and record outcomes, for example:
    - Observe: a single `seed(userId)` upserts exactly the 160 registry tickers into `market_prices`
    - Observe: a pre-seeded non-registry document is left untouched after seeding
    - Observe: running `seed(userId)` twice yields byte-identical `currentPrice` per `(ticker, userId)` pair (value-level idempotency)
  - Write tests capturing the observed MongoDB end state: exactly 160 registry docs, non-registry docs untouched, and run-twice value-level idempotency
  - Run tests on UNFIXED code
  - **EXPECTED OUTCOME**: Tests PASS (this confirms the baseline seeder MongoDB behavior to preserve)
  - Mark task complete when tests are written, run, and passing on unfixed code
  - _Requirements: 3.5, 3.7, 3.8_

- [x] 5. Fix Root Cause 1 — Publish seeded prices to Kafka

  - [x] 5.1 Implement the seed propagation fix
    - File: `market-data-service/src/main/java/com/wealth/market/seed/MarketDataSeedService.java`, function `seed(String userId)`
    - Inject `KafkaTemplate<String, PriceUpdatedEvent>` and add `private static final String TOPIC = "market-prices";`, mirroring `StartupHydrationService` exactly (same template type, same topic, same per-ticker key); keep `MongoTemplate` and `SeedTickerRegistry` unchanged
    - Capture the computed price `DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), userId)` into a local so the identical `BigDecimal` is both written to MongoDB and used as the event payload (no second DB read, no recomputation drift)
    - After `bulk.execute()` returns successfully, iterate the registry tickers and `kafkaTemplate.send(TOPIC, ticker, new PriceUpdatedEvent(ticker, seededPrice))` for each ticker whose computed price is non-null (null guard mirrors `StartupHydrationService`)
    - Publish best-effort (log send failures; do not roll back the MongoDB seed); preserve the `SeedResult` shape, `BulkMode.UNORDERED` upsert, `_id = ticker` criteria, and `currentPrice`/`quoteCurrency`/`updatedAt` fields
    - _Bug_Condition: isBugCondition_Seed(X) — golden-state seed that upserts tickers but publishes no events_
    - _Expected_Behavior: For each seeded registry ticker with a non-null price, publish exactly one PriceUpdatedEvent(ticker, seededPrice) to "market-prices" keyed by ticker (Property 1 from design)_
    - _Preservation: Upsert exactly 160 registry tickers, leave non-registry docs untouched, value-level idempotent; event publication is additive (Property 4 / Preservation Requirements from design)_
    - _Requirements: 2.1, 2.2_

  - [x] 5.2 Verify seed exploration test now passes
    - **Property 1: Expected Behavior** - Golden-state seeding propagates to Redis via Kafka
    - **IMPORTANT**: Re-run the SAME test from task 1 - do NOT write a new test
    - The test from task 1 encodes the expected behavior; when it passes it confirms the expected behavior is satisfied
    - Run the bug condition exploration test from task 1
    - **EXPECTED OUTCOME**: Test PASSES (confirms the seed→Kafka propagation bug is fixed)
    - _Requirements: 2.1, 2.2_

  - [x] 5.3 Verify seeder preservation tests still pass
    - **Property 4: Preservation** - Seeder MongoDB behavior unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 4 - do NOT write new tests
    - Run the seeder preservation property tests from task 4
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions — MongoDB end state, 160-ticker upsert, idempotency, and non-registry isolation are unchanged)
    - _Requirements: 3.5, 3.7, 3.8_

- [x] 6. Fix Root Cause 2 — Recognize suffixed symbols in the resolver

  - [x] 6.1 Implement the suffix-aware resolver fix
    - File: `insight-service/src/main/java/com/wealth/insight/ChatController.java`, function `resolveTicker` and its candidate extractors
    - Add a suffix-aware candidate extractor that tokenizes on whitespace and recognizes the registry suffix shapes while preserving the suffix: crypto `^[A-Za-z]{1,15}-USD$`, forex `^[A-Za-z]{3}[A-Za-z]{3}=X$`, NSE `^[A-Za-z]{1,15}\.NS$`
    - Strip only surrounding conversational punctuation (e.g. trailing `?`, `,`, or `.` that is not part of the `.NS` suffix), upper-case the alphabetic portion, and keep the suffix delimiter/token exactly as the registry stores them (e.g. `ROSE-USD`, `USDCHF=X`, `RELIANCE.NS`); do NOT apply the `[^A-Za-z]` strip or the ≤5-letter cap to suffixed candidates
    - Evaluate suffixed candidates against `findFirstTrackedTicker` (which verifies a non-null Redis price) before/alongside the existing plain-resolution chain, leaving the dollar → uppercase → conversational ordering and single-candidate fallback intact
    - Resolve to the exact tracked symbol so `findFirstTrackedTicker` calls `marketDataService.getTickerSummary(S)`, reading `market:latest:{S}` verbatim (no change to `MarketDataService`)
    - Add an explicit guard so a token whose alphabetic stem is a stop word is still ignored; do not weaken `STOP_WORDS` filtering on the plain path
    - _Bug_Condition: isBugCondition_Resolve(X) — message references a tracked symbol S with suffix format -USD / =X / .NS_
    - _Expected_Behavior: resolveTicker returns the full tracked symbol S (suffix intact) and the lookup uses market:latest:{S} (Property 2 from design)_
    - _Preservation: Plain ≤5-letter resolution, clarification response, no-data response, stop-word exclusion, and candidate ordering/fallback unchanged (Property 3 / Preservation Requirements from design)_
    - _Requirements: 2.3, 2.4, 2.5, 2.6_

  - [x] 6.2 Verify suffix-resolution exploration test now passes
    - **Property 2: Expected Behavior** - Suffixed tracked symbols resolve to themselves
    - **IMPORTANT**: Re-run the SAME test from task 2 - do NOT write a new test
    - The test from task 2 encodes the expected behavior; when it passes it confirms the expected behavior is satisfied
    - Run the bug condition exploration test from task 2
    - **EXPECTED OUTCOME**: Test PASSES (confirms suffixed symbols resolve to the exact tracked symbol and use `market:latest:{S}`)
    - _Requirements: 2.3, 2.4, 2.5, 2.6_

  - [x] 6.3 Verify resolver preservation tests still pass
    - **Property 3: Preservation** - Plain-symbol resolution and resolver responses unchanged
    - **IMPORTANT**: Re-run the SAME tests from task 3 - do NOT write new tests
    - Run the resolver preservation property tests from task 3
    - **EXPECTED OUTCOME**: Tests PASS (confirms no regressions — plain resolution, clarification, no-data, stop-word exclusion, and candidate ordering/fallback are unchanged)
    - _Requirements: 3.1, 3.2, 3.3, 3.4_

- [~] 7. Integration validation (Testcontainers, `@Tag("integration")`)
  - Seed → Kafka → Redis propagation: invoke the seed path, let `InsightEventListener.onPriceUpdated` consume, assert `market:latest:*` / `market:tracked-tickers` are populated for the seeded set, and a market-summary request returns all seeded tickers with non-null prices (validates Property 1, req 2.1, 2.2)
  - End-to-end suffix chat: with Redis seeded for `ROSE-USD` / `USDCHF=X` / `RELIANCE.NS`, assert the chat endpoint returns each ticker's summary, and that plain-symbol chat still works (validates Property 2 + Property 3, req 2.3–2.6, 3.1)
  - Consumer pipeline preserved: assert `processUpdate` still maintains latest price, the capped 10-item history, ZSET membership, and 24h pruning for both plain and suffixed tickers (req 3.6)
  - Run via `./gradlew integrationTest`
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.6_

- [~] 8. Checkpoint - Ensure all tests pass
  - Run `./gradlew test` (unit + property-based) and `./gradlew integrationTest` (Testcontainers)
  - Confirm exploration tests (Properties 1 & 2) now PASS, preservation tests (Properties 3 & 4) still PASS, and no regressions remain
  - Ensure all tests pass, ask the user if questions arise

## Task Dependency Graph

```json
{
  "waves": [
    {
      "wave": 1,
      "description": "Write and run exploration + preservation tests on UNFIXED code (independent, parallelizable)",
      "tasks": ["1", "2", "3", "4"]
    },
    {
      "wave": 2,
      "description": "Fix Root Cause 1 (seed publish) and Root Cause 2 (suffix resolver); each re-runs its own exploration + preservation tests",
      "tasks": ["5", "6"],
      "dependsOn": ["1", "2", "3", "4"]
    },
    {
      "wave": 3,
      "description": "Integration validation across both fixes (Testcontainers)",
      "tasks": ["7"],
      "dependsOn": ["5", "6"]
    },
    {
      "wave": 4,
      "description": "Final checkpoint — all unit, property-based, and integration tests pass",
      "tasks": ["8"],
      "dependsOn": ["7"]
    }
  ]
}
```

Dependency detail:
- Task 5 (Root Cause 1 fix) depends on Task 1 (seed exploration) and Task 4 (seeder preservation).
- Task 6 (Root Cause 2 fix) depends on Task 2 (resolve exploration) and Task 3 (resolver preservation).
- Tasks 5 and 6 are independent of each other and may be done in either order.

## Notes

- **Exploration-first:** Tasks 1–4 are written and executed on the UNFIXED code before any fix.
  Exploration tests (1, 2) MUST FAIL to confirm each bug; preservation tests (3, 4) MUST PASS to
  capture the baseline behavior to preserve.
- **Property hover status:** PBT tasks use the `**Property N:**` format. Property 1 (seed) and
  Property 2 (resolve) are the Bug Condition / Expected Behavior properties; Property 3 (resolver)
  and Property 4 (seeder) are the Preservation properties.
- **Same tests re-run after fix:** Verification sub-tasks (5.2, 5.3, 6.2, 6.3) re-run the SAME tests
  authored in tasks 1–4 — no new tests are written for verification.
- **Tooling:** jqwik `1.9.2` is already a `testImplementation` dependency in insight-service; add the
  same dependency to market-data-service for the seed properties. Integration tests are annotated
  `@Tag("integration")` and run via `./gradlew integrationTest` (Testcontainers).
- **Two independent fixes:** Root Cause 1 (task 5) and Root Cause 2 (task 6) are decoupled and can be
  implemented in either order; both are required for the chatbot to cover the full 160-ticker set.
