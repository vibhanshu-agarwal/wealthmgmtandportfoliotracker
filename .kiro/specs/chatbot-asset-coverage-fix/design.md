# Chatbot Asset Coverage Fix — Bugfix Design

## Overview

The insight-service chatbot can only "see" a tiny slice of the seeded asset universe. Two independent
defects combine to produce this symptom, and both must be fixed for the chatbot to cover the full
160-ticker registry:

- **Root Cause 1 — Seeding never reaches Redis.** The chatbot reads exclusively from Redis
  (`market:latest:{ticker}`, `market:history:{ticker}`, and the `market:tracked-tickers` ZSET), which
  is populated only by `InsightEventListener.onPriceUpdated` consuming `PriceUpdatedEvent` from the
  `market-prices` Kafka topic. The golden-state seeder `MarketDataSeedService.seed(userId)` bulk-upserts
  all 160 registry tickers into MongoDB but publishes **zero** Kafka events, so seeded prices never
  hydrate Redis. The fix publishes one `PriceUpdatedEvent(ticker, seededPrice)` per successfully seeded
  ticker (with a non-null price), mirroring the canonical `StartupHydrationService` publish pattern.

- **Root Cause 2 — Resolver cannot parse suffixed symbols.** The registry is dominated by suffixed
  symbols: crypto `-USD` (e.g. `ROSE-USD`), forex `=X` (e.g. `USDCHF=X`), and NSE `.NS`
  (e.g. `RELIANCE.NS`). `ChatController.resolveTicker` only recognizes plain ≤5-letter tokens via
  `DOLLAR_TICKER_PATTERN` / `UPPERCASE_TICKER_PATTERN` and a conversational extractor that strips all
  non-letters and rejects tokens longer than 5 characters. Suffixed symbols are therefore unresolvable
  even when their data is present in Redis. The fix extends candidate extraction/resolution so a
  suffixed tracked symbol resolves to its **full** symbol (suffix intact) and the Redis lookup uses the
  exact tracked symbol as the key.

The strategy keeps both fixes minimal and targeted. Root Cause 1 reuses the existing
`KafkaTemplate<String, PriceUpdatedEvent>` publish path and changes nothing about MongoDB upsert
semantics. Root Cause 2 adds suffix-aware candidate extraction without disturbing the existing
plain-symbol resolution, stop-word filtering, candidate ordering, or fallback logic. No event
contract, no Redis schema, and no downstream consumer changes.

## Glossary

- **Bug_Condition (C)**: The condition that triggers a defect. There are two: `isBugCondition_Seed`
  (a golden-state seed that upserts tickers but emits no events) and `isBugCondition_Resolve`
  (a chat message referencing a tracked symbol whose format carries a suffix `-USD` / `=X` / `.NS`).
- **Property (P)**: The desired behavior for buggy inputs — seeding propagates one event per non-null
  seeded ticker to Redis; a suffixed tracked symbol resolves to its full symbol and is looked up by the
  exact Redis key.
- **Preservation**: Existing behavior that must remain byte-identical after the fix — plain ≤5-letter
  resolution, the clarification and no-data responses, stop-word exclusion, the 160-ticker idempotent
  upsert, untouched non-registry documents, and the unchanged Kafka-consume/trend/summary pipeline.
- **F / F'**: The original (unfixed) and fixed behavior of `MarketDataSeedService.seed()` and
  `ChatController.resolveTicker()`.
- **MarketDataSeedService.seed(userId)**: `market-data-service` golden-state seeder that upserts the
  160 registry tickers into the MongoDB `market_prices` collection (`com.wealth.market.seed`).
- **StartupHydrationService**: `market-data-service` component that, on startup, republishes
  `PriceUpdatedEvent` for every MongoDB asset with a non-null price to `TOPIC = "market-prices"` keyed
  by ticker. This is the **canonical publish pattern** the seed fix mirrors.
- **DeterministicPriceCalculator.compute(basePrice, ticker, userId)**: Pure, JVM-stable price jitter
  function; the value written to MongoDB and therefore the value that must be published.
- **ChatController.resolveTicker(request)**: insight-service resolution pipeline producing a
  `ResolutionResult(ticker, source, candidates)` from explicit field, regex candidates, and
  conversational candidates.
- **MarketDataService**: insight-service Redis adapter
  (`LATEST_KEY_PREFIX="market:latest:"`, `HISTORY_KEY_PREFIX="market:history:"`,
  `TRACKED_TICKERS_KEY="market:tracked-tickers"`, `STALE_TICKER_TTL=24h`).
- **Tracked symbol**: A symbol with a non-null `market:latest:{symbol}` value in Redis — i.e. one for
  which `MarketDataService.getTickerSummary(symbol).latestPrice() != null`.
- **Suffix format**: A symbol matching one of the registry suffix shapes `-USD`, `=X`, or `.NS`.

## Bug Details

### Bug Condition

The bug manifests in two independent ways.

**Root Cause 1 (Seed propagation).** The bug occurs whenever the golden-state seeder runs and upserts
registry tickers into MongoDB: it writes the documents but emits no `PriceUpdatedEvent`, so the
chatbot's Redis structures are never updated for the seeded tickers.

```
FUNCTION isBugCondition_Seed(X)
  INPUT: X = a seed invocation that upserts registry tickers into MongoDB
  OUTPUT: boolean

  RETURN X.isGoldenStateSeed
         AND X.upsertedTickerCount > 0
         AND eventsPublishedTo("market-prices", DURING X) is empty
END FUNCTION
```

**Root Cause 2 (Suffix resolution).** The bug occurs when a chat message references a tracked symbol
whose format carries a suffix (`-USD`, `=X`, `.NS`) — the resolver discards the suffix and/or rejects
the token, so the Redis lookup against the exact symbol key never occurs.

```
FUNCTION isBugCondition_Resolve(X)
  INPUT: X = a chat message referencing a tracked symbol S
  OUTPUT: boolean

  RETURN isTracked(S)
         AND hasSuffixFormat(S)        // S matches one of: -USD | =X | .NS
         AND resolveTicker(X) != S      // original resolver fails to return the exact symbol
END FUNCTION
```

### Examples

- **Seed → Redis (1.1, 1.2):** `POST /api/internal/market-data/seed` with `{"userId":"e2e-user"}`
  upserts 160 documents into `market_prices` but leaves `market:latest:*`, `market:history:*`, and
  `market:tracked-tickers` unchanged. A subsequent market-summary request returns only the small subset
  last published at boot/refresh (≈ AAPL/MSFT/NVDA), not the seeded 160.
- **Crypto -USD (1.3):** "Can you tell me about ROSE-USD pair?" → the extractor strips to `ROSEUSD`
  (7 chars, rejected) or splits into `ROSE` + `USD`, so the resolver returns
  "I couldn't identify a single ticker symbol from your message."
- **Forex =X (1.4):** "How is USDCHF=X doing?" → `USDCHF=X` is stripped to `USDCHF` (6 chars, rejected),
  producing the clarification response.
- **NSE .NS (1.5):** "Tell me about RELIANCE.NS" → `RELIANCE` is 8 letters (rejected) and the `.NS`
  suffix is discarded, producing the clarification response.
- **Data present but unreachable (1.6):** Even when `market:latest:ROSE-USD` holds a valid price, the
  resolver never issues a lookup with the exact key `ROSE-USD`, so the price is unreachable.
- **Edge case — plain symbol (preserved):** "How is AAPL doing?" resolves to `AAPL` and returns its
  summary, exactly as before the fix.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- Plain ≤5-letter symbols, with or without a leading `$`, continue to resolve and return their market
  summary (req 3.1).
- Messages with no identifiable ticker continue to return the existing clarification response
  ("I couldn't identify a single ticker symbol…") (req 3.2).
- Stop words matching the uppercase pattern (e.g. `IS`, `THE`, `DO`) continue to be excluded from
  ticker candidates (req 3.3).
- A resolved ticker with no Redis price continues to return
  "I don't have any data for {ticker} right now…" (req 3.4).
- The seeder continues to upsert exactly the 160 registry tickers, leaves non-registry documents
  untouched, and remains value-level idempotent across repeated runs (req 3.5).
- `InsightEventListener.onPriceUpdated` → `MarketDataService.processUpdate` continues to update latest
  price, append to the capped 10-item history, record the ZSET entry, and prune the 24h stale window
  (req 3.6).
- `StartupHydrationService`, `LocalMarketDataSeeder`, and `MarketDataRefreshJob` continue to publish to
  `market-prices` with the existing event contract and behavior (req 3.7).
- Trend percentages and ticker summaries are computed exactly as before for non-null prices (req 3.8).

**Scope:**
All inputs that do NOT satisfy a bug condition must be completely unaffected by this fix. This includes:
- Chat resolution for any non-suffixed token (plain symbols, `$`-prefixed symbols, stop words,
  ticker-free messages, ambiguous multi-ticker messages).
- Any consumer of `PriceUpdatedEvent` (event shape, topic name, and per-ticker keying are unchanged).
- Any MongoDB document outside the 160-ticker registry set.

The actual expected correct behavior for buggy inputs is defined in the Correctness Properties section
(Property 1 and Property 2). This section enumerates what must NOT change.

## Hypothesized Root Cause

Based on the bug analysis, the defects are:

1. **Missing event emission in the seeder (Root Cause 1).** `MarketDataSeedService.seed()` performs an
   unordered MongoDB bulk upsert and returns, with no `KafkaTemplate` interaction. Unlike
   `StartupHydrationService` / `MarketDataRefreshJob`, it never injects or invokes
   `KafkaTemplate<String, PriceUpdatedEvent>`, so the only path that hydrates the chatbot's Redis
   (the `market-prices` topic) is never exercised by seeding.
   - The exact seeded value is recomputed via `DeterministicPriceCalculator.compute(basePrice, ticker,
     userId)` and is the same value written to `currentPrice` — so the event payload can be derived
     without an extra DB read.
   - The bulk result distinguishes upserts vs. modified counts, but every registry entry with a
     non-null computed price should be published regardless (an upsert and a value-identical re-run both
     leave the same desired state in Redis).

2. **Suffix-blind candidate extraction (Root Cause 2).** Resolution rejects suffixed symbols at three
   points:
   - `DOLLAR_TICKER_PATTERN = \$([A-Za-z]{1,5})` and `UPPERCASE_TICKER_PATTERN = \b([A-Z]{1,5})\b`
     only capture 1–5 letter runs and cannot represent suffixes (`-`, `=X`, `.NS`) or names longer than
     5 letters (e.g. `RELIANCE`).
   - `extractTickerCandidates` / `extractTicker` call `replaceAll("[^A-Za-z]", "")`, deleting the
     suffix punctuation and merging letters (`ROSE-USD` → `ROSEUSD`), then reject anything longer than
     5 characters.
   - Consequently `findFirstTrackedTicker` is never offered the exact tracked symbol, so the
     `market:latest:{exactSymbol}` lookup never happens.

3. **Lookup key mismatch (Root Cause 2, downstream).** Because candidates are normalized/truncated, the
   key passed to `MarketDataService.getTickerSummary` would not equal the tracked symbol even if a
   candidate survived — the resolved value must be the exact symbol used as the Redis key.

## Correctness Properties

Property 1: Bug Condition (Seed) — Golden-state seeding propagates to Redis via Kafka

_For any_ seed invocation where the bug condition holds (`isBugCondition_Seed` is true — a golden-state
seed that upserts one or more registry tickers), the fixed `MarketDataSeedService.seed(userId)` SHALL
publish exactly one `PriceUpdatedEvent(ticker, seededPrice)` to the `market-prices` topic, keyed by
ticker, for each seeded registry ticker whose computed price is non-null, where
`seededPrice = DeterministicPriceCalculator.compute(basePrice, ticker, userId)` — the same value
written to MongoDB.

**Validates: Requirements 2.1, 2.2**

Property 2: Bug Condition (Resolve) — Suffixed tracked symbols resolve to themselves

_For any_ chat message where the bug condition holds (`isBugCondition_Resolve` is true — the message
references a tracked symbol `S` with a suffix format `-USD`, `=X`, or `.NS`), the fixed
`ChatController.resolveTicker` SHALL resolve to the full tracked symbol `S` (suffix intact) and the
Redis lookup SHALL use the exact tracked symbol as the key, i.e. `market:latest:{S}`.

**Validates: Requirements 2.3, 2.4, 2.5, 2.6**

Property 3: Preservation — Plain-symbol resolution and resolver responses unchanged

_For any_ input where neither bug condition holds (`NOT isBugCondition_Seed AND NOT
isBugCondition_Resolve`) — in particular any plain ≤5-letter token (with or without leading `$`),
stop-word-only or ticker-free message, ambiguous multi-ticker message, or resolved ticker with no Redis
price — the fixed `ChatController.resolveTicker` and the surrounding `chat` response SHALL produce
exactly the same result as the original, preserving plain resolution, the clarification response, the
no-data response, stop-word exclusion, and candidate ordering/fallback.

**Validates: Requirements 3.1, 3.2, 3.3, 3.4**

Property 4: Preservation — Seeder MongoDB behavior unchanged

_For any_ seed invocation, the fixed `MarketDataSeedService.seed(userId)` SHALL continue to upsert
exactly the 160 registry tickers into the `market_prices` collection, leave documents outside the
registry set untouched, and remain value-level idempotent across repeated runs (running twice yields
byte-identical `currentPrice` for each `(ticker, userId)` pair). Event publication is additive and SHALL
NOT alter the MongoDB end state.

**Validates: Requirements 3.5, 3.7, 3.8**

## Fix Implementation

### Root Cause 1 — Publish seeded prices to Kafka

**File:** `market-data-service/src/main/java/com/wealth/market/seed/MarketDataSeedService.java`

**Function:** `seed(String userId)`

**Specific Changes:**
1. **Inject the canonical publisher.** Add a constructor dependency on
   `KafkaTemplate<String, PriceUpdatedEvent>` and a `private static final String TOPIC =
   "market-prices";` constant, mirroring `StartupHydrationService` exactly (same template type, same
   topic, same per-ticker key). Keep `MongoTemplate` and `SeedTickerRegistry` unchanged.
2. **Compute the price once, reuse it.** During the upsert loop, the code already computes
   `DeterministicPriceCalculator.compute(t.basePrice(), t.ticker(), userId)`. Capture that value into a
   local so the identical `BigDecimal` is both written to MongoDB and used as the event payload (no
   second DB read, no recomputation drift).
3. **Publish after a successful bulk execute.** After `bulk.execute()` returns successfully, iterate the
   registry tickers and `kafkaTemplate.send(TOPIC, ticker, new PriceUpdatedEvent(ticker, seededPrice))`
   for each ticker whose computed price is non-null. Publishing **after** the upsert ensures Redis is
   not hydrated for documents that failed to persist. (Registry base prices are always non-null today;
   the null guard mirrors `StartupHydrationService` and protects against future registry changes.)
4. **Preserve the contract and return value.** Do not change the `SeedResult` shape, the
   `BulkMode.UNORDERED` upsert, the `_id = ticker` criteria, or the
   `currentPrice` / `quoteCurrency` / `updatedAt` fields. The return value remains
   `new SeedResult(registry.all().size())`.
5. **Hexagonal / multi-cloud note.** `KafkaTemplate` is the same Spring abstraction already used by the
   other publishers in this module; this change introduces no new vendor coupling and stays consistent
   with the existing messaging seam. Publishing is best-effort hydration (consistent with
   `StartupHydrationService`); a send failure is logged and does not roll back the MongoDB seed.

### Root Cause 2 — Recognize suffixed symbols in the resolver

**File:** `insight-service/src/main/java/com/wealth/insight/ChatController.java`

**Function:** `resolveTicker` and its candidate extractors

**Specific Changes:**
1. **Add a suffix-aware candidate extractor.** Introduce a new extraction step that tokenizes the
   message on whitespace and, for each raw token, recognizes the registry suffix shapes while
   preserving the suffix:
   - crypto: `^[A-Za-z]{1,15}-USD$`
   - forex: `^[A-Za-z]{3}[A-Za-z]{3}=X$` (6-letter pair + `=X`)
   - NSE: `^[A-Za-z]{1,15}\.NS$`

   Strip only surrounding conversational punctuation (e.g. a trailing `?`, `,`, or `.` that is not part
   of the `.NS` suffix), then upper-case the alphabetic portion while keeping the suffix delimiter and
   suffix token exactly as the registry stores them (e.g. `ROSE-USD`, `USDCHF=X`, `RELIANCE.NS`). These
   suffixed candidates are NOT subjected to the `[^A-Za-z]` strip or the ≤5-letter cap.
2. **Prefer suffixed candidates, then fall through unchanged.** In `resolveTicker`, evaluate the new
   suffixed candidates against `findFirstTrackedTicker` (which already verifies a non-null Redis price)
   before the existing plain-resolution chain, or as an additional ordered source. Crucially, the
   existing dollar → uppercase → conversational candidate ordering and the single-candidate fallback
   branches are left intact so plain-symbol behavior and ambiguity handling do not regress.
3. **Lookup by exact symbol.** Because the suffixed candidate IS the exact tracked symbol,
   `findFirstTrackedTicker` calls `marketDataService.getTickerSummary(S)`, which reads
   `market:latest:{S}` verbatim — satisfying req 2.6 with no change to `MarketDataService`.
4. **Do not weaken stop-word filtering.** Suffix recognition only matches tokens that carry an actual
   suffix delimiter; bare words (including stop words like `IS`, `DO`) cannot match `-USD` / `=X` /
   `.NS`, so `STOP_WORDS` exclusion in the plain path is untouched. Add an explicit guard so a token
   whose alphabetic stem is a stop word is still ignored.
5. **No change to MarketDataService or the event/consume pipeline.** The Redis key prefixes, ZSET
   handling, trend math, and `InsightEventListener` remain exactly as-is.

## Testing Strategy

### Validation Approach

Two-phase: first surface counterexamples that demonstrate each bug on the UNFIXED code (confirming the
root-cause analysis), then verify the fix produces the desired behavior (fix checking) and leaves all
non-buggy inputs unchanged (preservation checking). Unit tests run under `./gradlew test`; integration
tests are annotated `@Tag("integration")` and run under `./gradlew integrationTest` via Testcontainers.
Property-based tests use jqwik (already a `testImplementation` dependency in insight-service; add the
same `net.jqwik:jqwik:1.9.2` to `market-data-service` for the seed properties).

### Exploratory Bug Condition Checking

**Goal:** Surface counterexamples that demonstrate both bugs BEFORE implementing the fix. Confirm or
refute the root-cause analysis; if refuted, re-hypothesize.

**Test Plan:** For Root Cause 1, invoke `MarketDataSeedService.seed()` with a captured/mock
`KafkaTemplate` (or embedded Kafka) and assert events were published — this fails on unfixed code. For
Root Cause 2, drive `ChatController.resolveTicker` (via `MockMvc` standalone setup, as in
`ChatControllerTest`) with suffixed messages and assert resolution — this fails on unfixed code.

**Test Cases:**
1. **Seed emits events** — seed and assert at least one `PriceUpdatedEvent` reaches `market-prices`
   (will fail on unfixed code: zero events).
2. **Crypto -USD resolves** — "Can you tell me about ROSE-USD pair?" resolves to `ROSE-USD`
   (will fail on unfixed code: clarification response).
3. **Forex =X resolves** — "How is USDCHF=X doing?" resolves to `USDCHF=X`
   (will fail on unfixed code).
4. **NSE .NS resolves** — "Tell me about RELIANCE.NS" resolves to `RELIANCE.NS`
   (will fail on unfixed code).

**Expected Counterexamples:**
- Seeder upserts 160 docs but publishes 0 events; Redis structures stay frozen at the boot/refresh
  subset.
- Suffixed symbols are stripped/rejected (`ROSE-USD` → `ROSEUSD` rejected, or split into `ROSE`+`USD`),
  so the exact-key lookup never runs. Possible causes: regex caps at 5 letters; `[^A-Za-z]` strip
  deletes suffixes; length cap rejects long stems.

### Fix Checking

**Goal:** Verify that for all inputs where a bug condition holds, the fixed function produces the
expected behavior.

```
// Root Cause 1
FOR ALL X WHERE isBugCondition_Seed(X) DO
  events := eventsPublishedTo("market-prices", DURING seed_fixed(X))
  FOR EACH ticker IN X.upsertedTickers WITH nonNullPrice DO
    ASSERT events CONTAINS PriceUpdatedEvent(ticker, compute(basePrice, ticker, userId))
  END FOR
END FOR

// Root Cause 2
FOR ALL X WHERE isBugCondition_Resolve(X) DO
  resolved := resolveTicker_fixed(X)
  ASSERT resolved = X.referencedSymbol                       // exact symbol, suffix intact
  ASSERT redisKeyUsed(resolved) = "market:latest:" + X.referencedSymbol
END FOR
```

### Preservation Checking

**Goal:** Verify that for all inputs where neither bug condition holds, the fixed function produces the
same result as the original.

```
FOR ALL X WHERE NOT isBugCondition_Seed(X) AND NOT isBugCondition_Resolve(X) DO
  ASSERT F(X) = F'(X)
END FOR
```

**Testing Approach:** Property-based testing is recommended for preservation because it generates many
inputs across the domain (plain symbols, `$`-prefixed symbols, stop words, ticker-free and ambiguous
messages) and catches edge cases manual tests miss, giving strong assurance the non-suffixed paths are
unchanged. For the seeder, a Testcontainers MongoDB run-twice comparison proves value-level idempotency
and that non-registry documents are untouched.

**Test Plan:** Capture behavior on UNFIXED code for plain resolution and the seeder's MongoDB end state,
then encode those observations as preservation properties/tests that must still pass after the fix.

**Test Cases:**
1. **Plain resolution preserved** — generate random 1–5 letter symbols (with/without `$`) that are
   tracked; assert resolution and response match the original (req 3.1).
2. **Clarification preserved** — generate ticker-free / stop-word-only messages; assert the
   clarification response (req 3.2, 3.3).
3. **No-data preserved** — resolved ticker (plain or suffixed) with a null-price summary returns the
   existing no-data message (req 3.4).
4. **Seeder idempotency preserved** — seed twice against Testcontainers MongoDB; assert identical
   `currentPrice` per ticker, exactly 160 registry docs, and a pre-seeded non-registry document left
   untouched (req 3.5).

### Unit Tests

- `MarketDataSeedService`: with a mock `KafkaTemplate`, assert one `PriceUpdatedEvent` per non-null
  registry ticker is sent to `market-prices` keyed by ticker, with the payload equal to
  `DeterministicPriceCalculator.compute(...)`; assert events are sent only after `bulk.execute()`.
- `ChatController` (extending `ChatControllerTest` `MockMvc` standalone pattern): suffixed resolution
  for `-USD`, `=X`, `.NS`; plain-symbol regression cases (`AAPL`, `$MSFT`); stop-word and ambiguous
  cases; no-data case.
- Edge cases: out-of-registry suffixed symbol that is NOT tracked falls through to clarification;
  suffixed token with trailing `?`/punctuation; mixed-case suffix input normalized to the tracked form.

### Property-Based Tests

- **Seed propagation property (jqwik, market-data-service):** for arbitrary subsets/orderings of the
  registry, every non-null-priced ticker produces a matching published event.
- **Suffix resolution property (jqwik, insight-service):** for generated tracked symbols over the
  suffix alphabet `{-USD, =X, .NS}`, `resolveTicker` returns the exact symbol and the lookup key equals
  `market:latest:{symbol}`.
- **Preservation property (jqwik, insight-service):** for generated non-suffixed inputs (plain symbols,
  `$`-prefixed, stop words, ticker-free, ambiguous), `F'` equals the observed `F` outcome (resolution
  source/value and response category).

### Integration Tests (`@Tag("integration")`, Testcontainers)

- **Seed → Kafka → Redis propagation:** Testcontainers MongoDB + embedded/Testcontainers Kafka; invoke
  the seed path, let `InsightEventListener.onPriceUpdated` consume, and assert `market:latest:*` /
  `market:tracked-tickers` are populated for the seeded set, then a market-summary request returns all
  seeded tickers with non-null prices (req 2.1, 2.2).
- **Seeder idempotency + isolation:** seed twice and assert exactly 160 registry docs, identical values,
  and an untouched pre-existing non-registry document (req 3.5).
- **End-to-end suffix chat:** with Redis seeded for `ROSE-USD` / `USDCHF=X` / `RELIANCE.NS`, assert the
  chat endpoint returns each ticker's summary, and that plain-symbol chat still works (req 2.3–2.6, 3.1).
- **Consumer pipeline preserved:** assert `processUpdate` still maintains latest price, capped 10-item
  history, ZSET membership, and 24h pruning for both plain and suffixed tickers (req 3.6).
