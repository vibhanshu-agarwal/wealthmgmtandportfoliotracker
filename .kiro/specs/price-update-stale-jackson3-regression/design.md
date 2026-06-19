# Price Update Stale (Jackson 3 / Spring Boot 4.1 Migration Regression) Bugfix Design

## Overview

After the 2026-06-17 Spring Boot 4.1 + Jackson 2→3 migration (PRs #66–72), the dashboard
regressed to showing **stale prices** ("1 day ago") and a **24h change of "—" / +0.00%** for
every holding — re-opening the exact failure that the `dashboard-data-accuracy` spec had
deliberately fixed.

Both symptoms have a single root: well-formed `PriceUpdatedEvent`s published by
`market-data-service` are no longer being projected into the `portfolio-service` read model.
When projection stops:

- `market_prices.updated_at` stops advancing → the dashboard renders prices as stale.
- `market_price_history` stops accumulating in the ≈18–36h tolerance window → the
  honest-but-now-empty reference makes every 24h change render as "—".

The naive "Jackson 3 serialization regression" explanation is **not supported**. The production
serializer/deserializer config is exercised by green tests
(`PriceUpdatedEventProducerWireContractTest`, `PriceUpdatedEventBackCompatTest`, and the
cross-service Testcontainers IT `PriceUpdatedEventKafkaRoundTripIT`). All of these run under the
`local` profile against a **PLAINTEXT** Confluent broker on a **full JRE**, and they all pass —
including projection, history append, DLT routing, and listener observation.

The defect therefore lives in something the green path never exercises: the **production runtime**
(prod[,azure] profile, **SASL_SSL / SASL `PLAIN`** transport to Aiven, and the **slim jlink custom
JRE** baked into the service container image). The migration shifted the runtime module/classpath
graph (Boot 4 split the Kafka auto-config into `org.springframework.boot.kafka.autoconfigure.*`,
and Jackson 2→3 changed the resolved dependency set), which in turn changed what
`jdeps --print-module-deps --ignore-missing-deps` resolves for the custom JRE. The fix approach is
to **pin the exact failing link with a reproduction that runs over the production transport / module
set**, correct that link, and add a regression guard — without disturbing any of the intentional
"honest —", DLT, back-compat, encoding, idempotency, or observation-resilience behaviors.

## Glossary

- **Bug_Condition (C)**: The condition that triggers the bug — a well-formed `PriceUpdatedEvent`
  is published while `portfolio-service` runs under the **production runtime** (prod/azure profile,
  SASL_SSL transport, slim custom JRE), and the consumer fails to project it into the read model.
- **Property (P)**: The desired behavior — every well-formed event is projected:
  `market_prices.updated_at` advances and exactly one `market_price_history` row per
  `(ticker, observed_at)` is appended, so the dashboard can show a recent price age and a real 24h
  change.
- **Preservation**: All intentional behaviors that must remain unchanged — honest "—" (no silent
  zero), DLT routing of malformed events, old-shape back-compat, ISO-8601/scale-2 wire encoding,
  idempotent projection/history, and observation-without-exporter resilience.
- **Production runtime**: The combination of the `prod` (and `prod,azure`) Spring profile,
  the SASL_SSL / SASL `PLAIN` Kafka transport defined in
  `common-dto/src/main/resources/config/application-prod-kafka.yml`, and the **jlink-generated slim
  custom JRE** produced by each service `Dockerfile` (jdeps + jlink stages).
- **Green local path**: `local` profile, PLAINTEXT Confluent broker (Testcontainers / Docker
  Compose), full Amazon Corretto JRE. This is what every existing automated test exercises.
- **`PriceUpdatedEventListener`**: `portfolio-service/.../PriceUpdatedEventListener.java` —
  the `@KafkaListener` that validates the event and delegates to the projection service.
- **`MarketPriceProjectionService`**: `portfolio-service/.../MarketPriceProjectionService.java` —
  performs the idempotent `market_prices` upsert and `market_price_history` append.
- **`PortfolioKafkaConfig`**: `portfolio-service/.../PortfolioKafkaConfig.java` — builds the
  consumer factory, the DLT error handler, and the listener container factory (now wired through
  `ConcurrentKafkaListenerContainerFactoryConfigurer.configure(...)` after the migration).
- **jlink module set**: The `--add-modules "${DEPS},..."` list in each service `Dockerfile`.
  `${DEPS}` is discovered by `jdeps --ignore-missing-deps --print-module-deps`; the remainder is a
  hardcoded fallback list.

## Bug Details

### Bug Condition

The bug manifests when `market-data-service` publishes a well-formed `PriceUpdatedEvent` (valid
ticker, positive `newPrice`, non-null `observedAt`) and `portfolio-service` is running under the
production runtime. The consumer either never establishes/authenticates its broker connection over
SASL_SSL, or fails at the transport/observation boundary, so the listener's `on(...)` projection
delegate is never invoked. As a result `market_prices.updated_at` does not advance and no
`market_price_history` row is appended — even though the same event round-trips correctly on the
green local PLAINTEXT path.

**Formal Specification:**
```
FUNCTION isBugCondition(input)
  INPUT: input of type PublishedPriceObservation
         { event: PriceUpdatedEvent,
           runtime: { profile, transport, jre } }
  OUTPUT: boolean

  // Well-formed event (would be accepted by the listener's validation).
  isWellFormed := event.ticker IS NOT null
                  AND NOT event.ticker.isBlank()
                  AND event.newPrice IS NOT null
                  AND event.newPrice > 0

  // Production runtime: prod/azure profile, SASL_SSL transport, slim jlink JRE.
  isProductionRuntime := input.runtime.profile CONTAINS 'prod'
                         AND input.runtime.transport == 'SASL_SSL'
                         AND input.runtime.jre == 'jlink-custom'

  // The defect: a well-formed event under the production runtime is NOT projected.
  notProjected := NOT marketPriceUpdatedAtAdvanced(event.ticker)
                  AND NOT historyRowAppended(event.ticker, event.observedAt)

  RETURN isWellFormed
         AND isProductionRuntime
         AND notProjected
END FUNCTION
```

### Examples

- **Stale price (Req 1.1 / 2.1)**: `market-data-service` publishes
  `PriceUpdatedEvent("AAPL", 231.40, "USD", 2026-06-18T09:00:00Z, 229.10, 2026-06-17T09:00:00Z)`
  to Aiven over SASL_SSL. *Expected:* `market_prices.updated_at` for `AAPL` advances to now and the
  dashboard shows "just now". *Actual:* `updated_at` is unchanged; the dashboard shows "1 day ago".
- **Missing history → "—" (Req 1.2 / 1.3 / 2.2 / 2.3)**: The same event carries a non-null
  `observedAt`. *Expected:* exactly one `market_price_history` row keyed `(AAPL, 2026-06-18T09:00:00Z)`
  is appended, landing inside the ≈18–36h window, so the holding shows a real (non-zero) 24h change.
  *Actual:* no history row is appended; with no in-window reference the change renders as "—" / +0.00%.
- **No self-heal (Req 1.4 / 2.4)**: Hours of fresh observations are published over SASL_SSL while the
  defect persists. *Expected:* prices and 24h change recover automatically as in-window history
  accumulates. *Actual:* staleness and "—" persist indefinitely; the data never self-heals.
- **Green path stays green (contrast)**: The identical event published on the `local` PLAINTEXT path
  with a full JRE (as in `PriceUpdatedEventKafkaRoundTripIT`) **is** projected correctly — confirming
  the defect is in the production transport / module set, not the serialization contract.

## Expected Behavior

### Preservation Requirements

**Unchanged Behaviors:**
- **Honest "—" (Req 3.1)**: When a holding genuinely has no `market_price_history` reference in any
  window, the 24h change must continue to render as "—" — never a fabricated +0.00%
  (the `dashboard-data-accuracy` "no silent zero" rule).
- **DLT routing (Req 3.2)**: Malformed events (null/blank ticker, null or non-positive `newPrice`)
  must continue to throw `MalformedEventException` (registered non-retryable) and route to
  `market-prices.DLT` without updating the read model.
- **Old-shape back-compat (Req 3.3)**: Old-shape events (only `ticker` + `newPrice`) must continue
  to deserialize with missing fields resolving to `null`, and the history append must continue to be
  skipped (no synthetic receive-time substitution).
- **Wire encoding (Req 3.4)**: ISO-8601 UTC encoding of `observedAt` / `previousReferenceAt` and the
  scale-2 monetary encoding of `newPrice` / `previousReferencePrice` must be preserved across the
  Kafka wire.
- **Idempotent projection/history (Req 3.5)**: Duplicate delivery of the same `(ticker, observed_at)`
  must remain a no-op for history (unique index) and must not corrupt valuation state.
- **Observation without exporter (Req 3.6)**: With `template.observation-enabled` /
  `listener.observation-enabled: true`, message delivery must continue to work and must not depend on
  a tracing exporter being configured (export is off by default).

**Scope:**
All inputs that do NOT satisfy the bug condition must be completely unaffected by this fix. This
includes:
- Events processed on the `local` / PLAINTEXT path (already green — must stay green).
- Malformed events (continue to DLT).
- Old-shape events (continue to deserialize and skip history).
- Duplicate deliveries (continue to be idempotent).
- Holdings with no in-window reference (continue to render "—").

**Note:** The actual expected correct behavior for buggy inputs is defined in the Correctness
Properties section (Property 1). This section focuses on what must NOT change.

## Hypothesized Root Cause

The migration introduced exactly two functional production changes on the price path (the serializer
classes and the projection/analytics SQL are unchanged in `git diff e1c741d..HEAD`):

1. both consumer container factories were rerouted through
   `ConcurrentKafkaListenerContainerFactoryConfigurer.configure(factory, consumerFactory)`, and
2. `template.observation-enabled` / `listener.observation-enabled: true` plus OTLP/tracing config
   (export off by default) were added; `application-local.yml` switched the delegate from
   `JsonDeserializer` → `JacksonJsonDeserializer`.

Crucially, the migration also shifted the **runtime module/classpath graph** (Boot 4 split the Kafka
auto-config into `org.springframework.boot.kafka.autoconfigure.*`; Jackson 2→3 changed resolved
dependencies), which changes what `jdeps --print-module-deps --ignore-missing-deps` resolves into the
slim jlink JRE. Candidate causes, most to least likely:

1. **Missing JDK module for the SASL_SSL transport (PRIMARY)**: The jlink fallback list in every
   service `Dockerfile` is
   `${DEPS},jdk.unsupported,java.security.jgss,java.sql,java.naming,java.instrument,java.logging,jdk.crypto.ec`
   — it includes `java.security.jgss` (GSSAPI/Kerberos) but **omits `java.security.sasl`**. The
   production transport (`application-prod-kafka.yml`) uses `security.protocol: SASL_SSL` with
   `sasl.mechanism: PLAIN`, and the Kafka client's SASL handshake requires `javax.security.sasl.*`
   (module `java.security.sasl`). With `--ignore-missing-deps`, jdeps suppresses unresolved deps; the
   post-migration classpath shift means `${DEPS}` no longer transitively pulls `java.security.sasl`,
   and the fallback list never had it. On the green path (PLAINTEXT + full JRE) this is never
   exercised. In prod over Aiven SASL_SSL the consumer's SASL client cannot be created
   (`Sasl.createSaslClient` returns null / `NoClassDefFoundError` for `javax.security.sasl.SaslClient`),
   so the consumer never joins the group and never fetches `market-prices` records → projection never
   runs → `updated_at` stalls and history stops. This single cause explains both symptoms, the
   "never self-heals" behavior, and why every PLAINTEXT/full-JRE test stays green.
   - Defensive co-suspects in the same family: `jdk.security.auth` (JAAS `LoginModule` /
     `Subject`), `jdk.crypto.cryptoki`, or `jdk.net` — each only reachable on the SASL_SSL path.

2. **Producer-side observation / trace-header injection (Task 11.2, DEFERRED)**: With
   `template.observation-enabled: true`, the auto-configured `KafkaTemplate` may inject W3C
   `traceparent` headers. Migration Task 11.2 ("Kafka trace-ID continuity via auto-configured
   `KafkaTemplate` header injection") is explicitly deferred/incomplete. If header injection or the
   consumer observation handler misbehaves over the real wire it could error every record. Lower
   likelihood: `PriceUpdatedEventKafkaRoundTripIT` exercises listener observation under PLAINTEXT and
   passes, and a per-record error would route to DLT (loud), not silently stall projection.

3. **`ConcurrentKafkaListenerContainerFactoryConfigurer.configure(...)` reroute**: The configurer now
   applies Boot's auto-config (consumer props, observation registry, default container settings) to
   the factory before the custom error handler is set. A prod-only property interaction could change
   container behavior. Lower likelihood: the IT uses this exact production factory and passes on the
   local path.

The exploratory test phase below is designed to **confirm or refute** hypothesis 1 first (by
exercising the SASL transport and the slim module set), then fall through to 2 and 3 if refuted.

## Correctness Properties

Property 1: Bug Condition — Well-formed events are projected under the production runtime

_For any_ well-formed `PriceUpdatedEvent` (valid ticker, positive `newPrice`, non-null `observedAt`)
published while `portfolio-service` runs under the production runtime (prod/azure profile, SASL_SSL
transport, slim custom JRE) — i.e. where `isBugCondition` would have returned true on the unfixed
build — the fixed system SHALL project the event into the read model: it SHALL advance
`market_prices.updated_at` for that ticker and SHALL append exactly one `market_price_history` row
keyed `(ticker, observed_at)`, so the dashboard can render a recent price age and a real (non-zero
where prices differ) 24h change, and SHALL allow the dashboard to recover automatically as in-window
history accumulates.

**Validates: Requirements 2.1, 2.2, 2.3, 2.4**

Property 2: Preservation — All non-buggy behavior is unchanged

_For any_ input where the bug condition does NOT hold (events on the local/PLAINTEXT path, malformed
events, old-shape events, duplicate deliveries, holdings with no in-window reference, and observation
enabled without an exporter), the fixed system SHALL produce the same result as the original system:
it SHALL continue to render "—" rather than a fabricated +0.00% when no reference exists (3.1),
continue to route malformed events to `market-prices.DLT` without updating the read model (3.2),
continue to deserialize old-shape events with `null` fields and skip the history append (3.3),
continue to preserve ISO-8601 UTC and scale-2 wire encoding (3.4), continue to treat duplicate
`(ticker, observed_at)` delivery idempotently (3.5), and continue to deliver messages with
observation active and no dependency on a tracing exporter (3.6).

**Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6**

## Fix Implementation

### Changes Required

Assuming the primary root-cause hypothesis is confirmed (jlink module gap on the SASL_SSL path):

**File**: `portfolio-service/Dockerfile` (and, for the same shared defect, `insight-service/Dockerfile`,
`market-data-service/Dockerfile`, `api-gateway/Dockerfile`)

**Location**: the `jlink --add-modules` line in the "JRE Builder" stage (≈ line 93–96).

**Specific Changes**:
1. **Add the missing transport module**: Add `java.security.sasl` to the hardcoded fallback module
   list so the slim custom JRE always contains the SASL client classes required by the Aiven
   SASL_SSL / `PLAIN` transport, regardless of what `jdeps --ignore-missing-deps` resolves.
   - Apply to every service whose runtime opens a SASL_SSL Kafka connection
     (`portfolio-service` and `insight-service` are consumers; `market-data-service` is the producer;
     `api-gateway` if it touches Kafka — otherwise leave unchanged to keep its image minimal).
2. **Defensive co-modules (only if the exploratory phase shows they are needed)**: Add
   `jdk.security.auth` (JAAS `LoginModule`/`Subject`) and, if TLS/PKCS#11 paths require it,
   `jdk.crypto.cryptoki` / `jdk.net` to the same fallback list. Keep the additions minimal and
   evidence-driven to preserve the Free-Tier-friendly slim image size.
3. **Do not weaken `--ignore-missing-deps`**: Keep jdeps as-is; the fallback list is the deterministic
   safety net. The fix makes the SASL module set explicit rather than relying on transitive
   resolution that the migration perturbed.
4. **No production Java changes for the primary hypothesis**: `PortfolioKafkaConfig`,
   `PriceUpdatedEventListener`, `MarketPriceProjectionService`, and the serializer config stay
   untouched — the green tests prove they are correct. (If the exploratory phase instead points to
   hypothesis 2 or 3, the fix would shift to the `KafkaTemplate` trace-header injection / container
   configurer wiring; that path is held in reserve pending exploratory results.)
5. **Regression guard**: Add an automated check (see Testing Strategy) that fails if the produced
   custom JRE / configured module set lacks `java.security.sasl`, so a future jdeps drift cannot
   silently re-open this regression.

## Testing Strategy

### Validation Approach

The strategy is two-phase: first surface counterexamples that demonstrate the bug over the
**production transport / module set** (not the green PLAINTEXT path), confirming or refuting the
root-cause hypotheses; then verify the fix projects buggy-condition events correctly and preserves
all non-buggy behavior. Because the failing link only manifests in the production runtime, the
exploratory tests must use a SASL-enabled broker and/or the slim module set — a PLAINTEXT/full-JRE
test cannot reproduce it.

### Exploratory Bug Condition Checking

**Goal**: Surface counterexamples that demonstrate the bug BEFORE implementing the fix, and confirm or
refute the root-cause analysis. If refuted, re-hypothesize (fall through hypotheses 1 → 2 → 3).

**Test Plan**: Reproduce over a production-like transport and module set rather than the green local
path. Run on the UNFIXED build to observe failure.

**Test Cases**:
1. **Module-set counterexample (H1 direct)**: Assert the jlink module set in the service `Dockerfile`
   resolves a custom JRE that contains `java.security.sasl`. On the unfixed Dockerfile this FAILS
   (module absent). This is the cheapest, most direct counterexample for the primary hypothesis.
   (Implementable as a build-time assertion over the `--add-modules` list, or a CI step running
   `jlink ... && java --list-modules | grep java.security.sasl` against the produced image.)
2. **Simulated stripped-JRE SASL client (H1, JVM-level)**: Launch a JVM with the unfixed module set
   simulated via `--limit-modules <fallback-list>` and attempt to create a Kafka SASL `PLAIN` client
   / connect to a SASL broker. On the unfixed set this FAILS with a missing
   `javax.security.sasl.SaslClient` (will fail on unfixed code).
3. **SASL transport round-trip (H2/H3 guard, integration)**: Stand up a Testcontainers Kafka
   configured for SASL (e.g. SASL_PLAINTEXT/SASL_SSL with mechanism `PLAIN`) under a prod-like Kafka
   profile, publish a well-formed event through the production listener stack
   (`PortfolioKafkaConfig` + `PriceUpdatedEventListener`) with `observation-enabled: true`, and assert
   `market_prices.updated_at` advances and one `market_price_history` row is appended. On a **full
   JRE** this PASSES even unfixed (full JRE has `java.security.sasl`), so it does not reproduce H1 by
   itself — but it isolates H2/H3 and guards the SASL auth/observation config. Reproducing H1 requires
   running this same round-trip on the **slim custom JRE** (containerized smoke test, case 4).
4. **Containerized prod smoke test (H1 end-to-end)**: Run the actual service container image (slim
   custom JRE) against a SASL broker and assert a published event reaches the read model. On the
   unfixed image this FAILS (SASL handshake cannot initialize); on the fixed image it PASSES.

**Expected Counterexamples**:
- Custom JRE / module set lacks `java.security.sasl`; Kafka SASL client cannot be created over
  SASL_SSL → consumer never fetches `market-prices` → no projection.
- Possible causes (in priority order): missing `java.security.sasl` jlink module (primary); deferred
  producer trace-header injection (Task 11.2); container-factory configurer reroute interaction.

### Fix Checking

**Goal**: Verify that for all inputs where the bug condition holds, the fixed system produces the
expected behavior (Property 1).

**Pseudocode:**
```
FOR ALL input WHERE isBugCondition(input) DO
  projectUnderProductionRuntime(input.event)   // SASL transport + fixed module set
  ASSERT marketPriceUpdatedAtAdvanced(input.event.ticker)
  ASSERT exactlyOneHistoryRow(input.event.ticker, input.event.observedAt)
END FOR
```

### Preservation Checking

**Goal**: Verify that for all inputs where the bug condition does NOT hold, the fixed system produces
the same result as the original system (Property 2).

**Pseudocode:**
```
FOR ALL input WHERE NOT isBugCondition(input) DO
  ASSERT originalBehavior(input) = fixedBehavior(input)
END FOR
```

**Testing Approach**: Property-based testing is recommended for preservation checking because:
- It generates many inputs automatically across the event domain (tickers, prices, currencies,
  presence/absence of `observedAt`, duplicate deliveries).
- It catches edge cases manual unit tests miss (e.g. old-shape vs enriched, sub-millisecond
  `observedAt` drift).
- It gives strong guarantees that behavior is unchanged for all non-buggy inputs.

**Test Plan**: The green PLAINTEXT tests already capture the pre-fix behavior for non-buggy inputs;
re-run them unchanged after the fix to prove preservation, and add property-based generators for the
projection/idempotency surface.

**Test Cases**:
1. **Local-path preservation**: `PriceUpdatedEventKafkaRoundTripIT` (PLAINTEXT) continues to pass —
   projection, history append, and listener observation unchanged.
2. **Malformed → DLT preservation**: A null/blank-ticker or non-positive-price event still throws
   `MalformedEventException` and lands on `market-prices.DLT` with no read-model update (3.2).
3. **Old-shape preservation**: An event with only `ticker` + `newPrice` still deserializes with
   `null` enrichment fields and skips the history append (3.3).
4. **Encoding preservation**: Round-trip still preserves ISO-8601 UTC `observedAt` /
   `previousReferenceAt` and scale-2 `newPrice` / `previousReferencePrice` (3.4) —
   `PriceUpdatedEventProducerWireContractTest` / `PriceUpdatedEventBackCompatTest` stay green.
5. **Idempotency preservation**: Duplicate delivery of the same `(ticker, observed_at)` yields no
   duplicate history rows and no corrupted valuation (3.5).
6. **Honest "—" preservation**: A holding with no in-window reference still renders "—", never a
   fabricated +0.00% (3.1).
7. **Observation-without-exporter preservation**: With observation enabled and export disabled,
   delivery still succeeds (3.6).

### Unit Tests

- Assert the jlink fallback module list for each Kafka-connected service includes
  `java.security.sasl` (regression guard for H1).
- Listener validation unit tests (malformed → `MalformedEventException`) remain green.
- `MarketPriceProjectionService` idempotency unit tests (duplicate `(ticker, observed_at)` → no-op;
  null `observedAt` → history skipped) remain green.

### Property-Based Tests

- Generate well-formed events (random valid ticker, positive `newPrice`, currency, `observedAt`) and
  assert projection advances `updated_at` and appends exactly one history row (Property 1 surface, run
  over a SASL-enabled transport where feasible).
- Generate non-buggy events (malformed, old-shape, duplicates) and assert behavior is identical to the
  original (Property 2 — preservation), including idempotency under repeated delivery.
- Generate `observedAt` values near millisecond boundaries to assert truncation-based dedup is stable.

### Integration Tests

- **SASL transport round-trip (`@Tag("integration")`)**: Testcontainers Kafka with SASL `PLAIN`,
  prod-like Kafka profile, full production listener stack with observation enabled → assert end-to-end
  projection + history append (guards H2/H3 and the SASL auth/observation config).
- **Containerized slim-JRE smoke test**: Run the built service image (custom JRE) against a SASL
  broker and assert a published event reaches `market_prices` / `market_price_history` (end-to-end
  guard for H1; fails on unfixed image, passes on fixed).
- **DLT integration**: Malformed event over the SASL transport still routes to `market-prices.DLT`
  with no read-model update.
- **Recovery integration**: After projection resumes, accumulating in-window history restores a real
  24h change automatically (Req 2.4) without manual intervention.
