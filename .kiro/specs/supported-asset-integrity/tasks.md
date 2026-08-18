# Implementation Plan

## Overview

Eight waves. The ordering is not stylistic — it encodes the constraint that made the design review
long: **code cannot precede the schema it reads, and enforcement cannot precede the data repair.**

Waves 0–2 build the catalog authority and are behaviour-neutral. Wave 3 adds validation and freshness
code that stays gated off. Wave 4 builds the suspension machinery. Wave 5 is the Postgres repair —
the first irreversible step. Wave 6 is the Mongo repair. Wave 7 is the cutover, expressed as discrete
stop/go checkpoints rather than prose, because every one of its irreversible actions needs an explicit
decision point.

The design is frozen at Revision 9. Where a task and the design disagree, **the design is normative**;
raise it rather than resolving it in code.

Stack: **Java 21 / Spring Boot 4.1**, a new `common-catalog` Gradle module (plain Java, no Spring),
JUnit 5 + Testcontainers (Postgres 18.4, MongoDB) for integration, **Terraform** (`azurerm 4.81.0`,
pinned) for infrastructure, **Python** for plan assertions matching the existing
`infrastructure/terraform/azure/scripts/` convention.

## Global Constraints

Negative requirements with no dedicated task. Listed so they stay traceable, reviewed at the
checkpoints (tasks 5.6, 7.x), not built.

- **1.6** — no CI check asserting duplicate manifest copies match; the duplication is removed, not policed.
- **2.5** — `insight-service` alias normalization and grounding stay in `insight-service`.
- **5.4, 5.5** — refresh cadence, ACA Job as sole production refresh path, and skip-and-keep-last-price all unchanged.
- **12.1–12.8** — no `/api/assets`, composition API, optimistic concurrency, picker UI, `assets` table, or FK.
- **4.4, 4.5** — no silent successor substitution for `TATAMOTORS.NS`; its Corporate_Action_Migration stays an open decision.
- **6.17** — the invariant is uniform across writers; only the enforcement mechanism differs.
- **9.48** — no FX timestamp persistence and no FX freshness state.

**Coverage note.** `--coverage` reports **153/174** criteria cited by a task. The residual 21 are the
prohibitions and rationale clauses listed above — reviewed at the checkpoints, not built.

That list is not commentary: `--coverage` parses it as the allowlist and **exits non-zero** if any
uncited criterion is missing from it. Adding a requirement without a task, or deleting a task that
was the sole citation, fails the check rather than passing silently. Run it after any edit:

```
python scripts/check-spec-references.py   .kiro/specs/supported-asset-integrity/tasks.md   --against .kiro/specs/supported-asset-integrity/requirements.md --coverage
```

---

## Tasks

- [x] 1. `common-catalog` module foundation
  - [x] 1.1 Create the Gradle module
    - Plain Java, no Spring imports; joins `common-dto` / `common-observability` convention
    - _Requirements: 2.1, 2.2_

  - [x] 1.2 `CatalogEntry`, `LifecycleStatus`, `SupportedCatalog`, `SeedCatalogView`
    - `basePrice` reachable **only** through `SeedCatalogView`
    - _Requirements: 2.2, 2.10, 2.11_

  - [x] 1.3 Integrity validation
    - Reject blank ticker / name / assetClass / quoteCurrency, null aliases, duplicate tickers,
      absent or unrecognised `lifecycleStatus`, `basePrice` null or non-positive; require at least
      one `ACTIVE` per asset class. Collect **all** violations into one message.
    - Assert **no** fixed total or per-class count is enforced
    - _Requirements: 2.4, 3.3, 3.4_

  - [x] 1.4 `Catalog_Version`
    - SHA-256 over ticker, name, sorted aliases, assetClass, quoteCurrency, lifecycleStatus,
      **and `basePrice`**; entries sorted by ticker; 16 hex chars
    - Test: changing only `basePrice` changes the version; changing only `lifecycleStatus` changes it
    - _Requirements: 2.7, 2.8, 2.9_

  - [x] 1.5 `CatalogLoadFailedException` and fail-closed loading
    - Throws on absent, unreadable, unparseable, integrity-failing, or zero-active-asset manifest;
      never returns empty or partial; no cached/previous fallback
    - _Requirements: 10.1, 10.6_

- [x] 2. Manifest and packaging
  - [x] 2.1 Add `lifecycleStatus` to all 160 entries
    - `TATAMOTORS.NS` → `DEPRECATED`; `USDINR=X` untouched
    - `MM.NS` → `M&M.NS` (`ACTIVE`) is intentionally deferred to task 6 due to Requirement 4.8
    - _Requirements: 3.1, 3.2, 3.5, 3.6, 3.7, 3.8, 4.1, 4.3, 4.6, 4.7, 4.8_

  - [x] 2.2 Verify `&` URL-encoding for `M&M.NS` against the provider client
    - _Requirements: 4.2_

  - [x] 2.3 Non-mutating build packaging
    - `processResources` copies `config/seed-tickers.json` → `build/resources/main/catalog/` in each
      consumer; delete the three `copySeedTickers` tasks and the three tracked
      `src/main/resources/seed/seed-tickers.json` copies
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5_

- [x] 3. Consumers adopt the module
  - [x] 3.1 Replace both `SeedTickerRegistry` implementations
    - _Requirements: 2.3_

  - [x] 3.2 `insight-service` delegates loading/integrity/versioning; remove fail-open
    - Its log-error-set-empty-continue path is deleted
    - _Requirements: 10.2, 10.3_

  - [x] 3.3 Fail-to-start in all three services, all normal profiles
    - Emit structured `catalog_load_failed` (resource path, violation list, service) before the
      failure propagates; distinct exception type; distinct from request-level `unsupported_asset`
    - Unit tests may inject fixture catalogs; no runtime fallback in application code
    - _Requirements: 10.4, 10.5, 10.7, 10.8_

  - [x] 3.4 **Remove `@Async` from `MarketPriceProjectionService` — ships in R1**
    - Projection moves onto the listener thread, stays `@Transactional`
    - Test: a database failure now reaches the Kafka error handler and DLT rather than being
      swallowed by the executor
    - Test: consumer lag reaching zero implies the projection is complete — with `@Async` it does
      not, and checkpoint 9.4 drains Kafka **before** R3a, so this cannot ship with wave 8
    - _Design: D10_ (no requirement criterion governs `@Async`; `9.30` governs `updated_at`
      receive-time semantics and belongs to 8.4)

  - [x] 3.5 Actuator exposure and startup log
    - Add `health,info` exposure to `portfolio-service` and `insight-service` (**never** a wildcard);
      emit `catalog_loaded version= entries= active= rejectUnsupportedEvents= enforceHoldingInvariant=`
    - _Requirements: 2.6, 10.9_

  - [x] 3.6 **Regression guard for the delivered price-write invariant**
    - Requirement 11 is delivered but retained as a regression boundary, and nothing else in this
      plan exercises it. Confirm `PortfolioSeedServiceIT` and `PortfolioSeedControllerTest` still
      pass after the seeder switches to `active()` enumeration in 4.5, and that both price tables
      remain byte-identical across a seed
    - Assert the **production-profile capability check**: no seed or reset capability in
      `portfolio-service` can write either price table in any profile, and the seed response carries
      no price-count field
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9, 11.10_

- [x] 4. Write-boundary validation and freshness code (gated off)
  - [x] 4.1 `SupportedAssetValidator` + `UnsupportedAssetException`
    - Canonical ticker only; no alias resolution; in-process; no cross-service call
    - _Requirements: 6.1, 6.5, 6.6_

  - [x] 4.2 `@RestControllerAdvice` → HTTP 422 `unsupported_asset` + ticker + catalogVersion
    - Applies to **every** `Http_Entry_Point` including the internal seed endpoint
    - _Requirements: 6.2, 6.4_

  - [x] 4.3 Typed failure and rollback for `Application_Operation` and `Direct_Caller`
    - Identical detection regardless of caller; no partial mutation
    - _Requirements: 6.3, 6.10_

  - [x] 4.4 Deprecated-position rules
    - Reduce/remove permitted; create/increase rejected; never retroactive
    - _Requirements: 6.8, 6.9_

  - [x] 4.5 Both seed paths enumerate `SupportedCatalog.active()` via `SeedCatalogView`
    - Without this the seeder writes `TATAMOTORS.NS` and rolls back its own repair once enforced
    - _Requirements: 6.7, 8.4_

  - [x] 4.6 Gate both enforcement properties, default `false` in this artifact
    - `app.catalog.reject-unsupported-events`, `app.catalog.enforce-holding-invariant`
    - _Requirements: 6.1_

- [x] 5. Refresh suspension machinery and Terraform plan safety
  - [x] 5.1 Suspended-mode runner
    - Second `CommandLineRunner`, **mutually exclusive** with `MarketDataRefreshJobRunner`
    - Writes nothing: no Mongo, no Kafka, no Postgres
    - Emits `refresh_suspended` with the Job execution identity; flushes telemetry before exit
    - Calls `SpringApplication.exit` with code **`0`** — a suspended run is a success
    - _Requirements: 7.23_

  - [x] 5.2 Test the **full runner matrix**, not just three modes
    - `MARKET_DATA_JOB_RUNNER_ENABLED=false` activates the **suspended** runner, which calls
      `SpringApplication.exit(0)`. A repair Job configured as "repair enabled, refresh disabled"
      would therefore start **both** the repair runner and the suspended runner, and the suspended
      one could exit the context out from under the repair. The matrix must make that combination
      impossible:

    | context | refresh property | repair property | active runner |
    |---|---|---|---|
    | long-running service | absent | absent | **neither** |
    | scheduled Job, normal | `true` | absent | refresh only |
    | scheduled Job, suspended | `false` | absent | suspended only |
    | repair Job | **absent** | `true` | repair only |
    | any other combination | — | — | **fail startup** |

    - Assert exactly one runner in every valid row, and a startup failure — not a silent
      precedence rule — for every invalid one
    - Assert the suspended runner terminates with exit 0 rather than running to replica timeout
    - _Requirements: 7.22, 7.23_

  - [x] 5.3 Terraform: `ingress_enabled` input on the `container-app` module
    - `dynamic "ingress"` omitted when false; gateway wired to it
    - _Requirements: 7.21_

  - [x] 5.4 **Plan assertion: changing the runner env var must not replace the Job**
    - Python plan test asserting `azurerm_container_app_job.market_data_refresh` shows `update`,
      **not** `create`/`delete`, when only `MARKET_DATA_JOB_RUNNER_ENABLED` changes
    - Rationale: in azurerm 4.81.0 `schedule_trigger_config` is `ForceNew`; this test is what proves
      the chosen mechanism avoids it, and what will fail loudly on a provider upgrade
    - _Requirements: 7.22_

  - [x] 5.5 Plan assertion: `ingress_enabled = false` does not replace the gateway app
    - _Requirements: 7.21_

  - [x] 5.6 Global-constraints review checkpoint
    - Walk the Global Constraints list; confirm none was violated by waves 0–5

- [ ] 6. Postgres repair (V17–V19) — **first irreversible wave**
  - [ ] 6.1 V17: `repair_archive` + `repair_audit`
    - Archive: `UNIQUE (migration_version, source_table, natural_key)`, natural key **full-precision**
    - Audit: `PRIMARY KEY (migration_version, portfolio_id, asset_ticker)`
    - _Requirements: 7.2, 7.20_

  - [ ] 6.2 V17: `TIMESTAMP(3)` truncation preflight
    - Group history by `(ticker, date_trunc('milliseconds', observed_at))`; identical payloads
      collapse with losers archived as `COLLISION_LOSER`; **any conflicting group aborts before the
      `ALTER`** — discovering it afterwards means the data is already merged
    - _Requirements: 9.1_

  - [ ] 6.3 V17: add `market_prices.observed_at TIMESTAMP(3)`, alter history to `TIMESTAMP(3)`
    - _Requirements: 9.1_

  - [ ] 6.4 V18: `BTC` → `BTC-USD` holding; archive+delete synthetic `BTC` history; drop `BTC` price row
    - Archive with reason `LEGACY_SYNTHETIC`, verbatim, before deletion
    - _Requirements: 7.1, 7.2, 7.18, 7.19, 7.20_

  - [ ] 6.5 V19: `MM.NS` → `M&M.NS` across holdings, prices, history
    - _Requirements: 7.3, 7.26, 7.27_

  - [ ] 6.6 Collision rules, decided outcomes
    - Holdings **combine**, quantity-weighted basis; currency mismatch → abort; either basis null →
      whole basis tuple null with both originals archived; `q1+q2 <= 0` → abort;
      `cost_basis_as_of` = **later**; `cost_basis_source = MERGED` on successful non-null merge only
    - _Requirements: 7.7, 7.8, 7.9, 7.10, 7.11, 7.12, 7.13_

  - [ ] 6.7 `Post_Migration_Integrity_Assertion`
    - Checks **both**: migration-created/replaced holdings (from `repair_audit`) name Active_Assets,
      **and** the whole table satisfies the Referential_Invariant; pre-existing deprecated positions
      exempt from the first
    - Blocks startup on failure — a gate, not a diagnostic
    - Per-migration postconditions per the design's table
    - _Requirements: 6.11, 6.12, 6.13, 6.14, 6.15, 6.16, 7.30_

  - [ ] 6.8 Idempotency under Flyway re-execution
    - _Requirements: 7.29_

  - [ ] 6.9 **Integration tests: migration scenarios** — the Postgres equivalent of 7.7
    - Eighteen concrete Testcontainers-Postgres cases. These run **before** the maintenance window;
      only the deployment at 9.6 is irreversible, so there is no reason to discover any of this live.
    - [ ] 6.9.1 precision collision, identical payloads → collapse, losers archived `COLLISION_LOSER`
    - [ ] 6.9.2 precision collision, conflicting payloads → **abort before the `ALTER`**
    - [ ] 6.9.3 `BTC` history archived verbatim → archive count equals pre-migration count, and
          `payload = to_jsonb(row)` round-trips to original types for every column
    - [ ] 6.9.4 zero operational `BTC` history rows remain after V18
    - [ ] 6.9.5 holding collision, both symbols held → quantities combined, weighted basis correct
    - [ ] 6.9.6 holding collision, currency mismatch → abort
    - [ ] 6.9.7 holding collision, either basis null → whole basis tuple null, both originals archived
    - [ ] 6.9.8 holding collision, `q1+q2 <= 0` → abort
    - [ ] 6.9.9 `MM.NS` migrated across holdings, prices, and history with continuity preserved
    - [ ] 6.9.10 `market_prices` collision, **newer** `observed_at` wins
    - [ ] 6.9.11 `market_prices` collision, **known beats null**
    - [ ] 6.9.12 `market_prices` collision, **both null → destination retained**, source archived
    - [ ] 6.9.13 `market_price_history` collision at one `(ticker, observed_at)`, identical payload → collapse
    - [ ] 6.9.14 `market_price_history` collision at one `(ticker, observed_at)`, conflicting payload → **abort**
    - [ ] 6.9.15 `market_prices` collision, **equal known `observed_at` + identical payload** → idempotent collapse
    - [ ] 6.9.16 `market_prices` collision, **equal known `observed_at` + conflicting payload** → migration
          **aborts without deleting or altering either candidate** — both rows survive for operator resolution
    - [ ] 6.9.17 `Post_Migration_Integrity_Assertion` fails a migration-created deprecated position,
          and passes a pre-existing one — the distinction the audit table exists to make
    - [ ] 6.9.18 any persisted `TATAMOTORS.NS` holding is **byte-unchanged** after all migrations —
          not deleted, not reassigned, quantity and cost basis intact
    - Re-execution of the full set is idempotent
    - _Requirements: 7.1, 7.3, 7.7, 7.9, 7.10, 7.11, 7.12, 7.18, 7.19, 7.20, 7.28, 7.29, 7.30_

- [ ] 7. Mongo repair Job
  - [ ] 7.1 Dedicated `azurerm_container_app_job`, manual trigger
    - Same `market-data-service` image; non-web mode; bounded execution timeout
    - **Repair property `true`, refresh property ABSENT** — not `false`, which would activate the
      suspended runner alongside the repair (see 5.2)
    - Exit `0` on `COMPLETE` or already-complete; non-zero on `FAILED_CONFLICT`, lost fence, timeout,
      or unverifiable state
    - _Requirements: 7.4, 7.5, 7.24, 7.25_

  - [ ] 7.2 Lease claim
    - Conditional update matching absent-or-expired, excluding **both** `COMPLETE` and
      `FAILED_CONFLICT`; `$inc` generation
    - _Requirements: 7.5, 7.6_

  - [ ] 7.3 Duplicate-key classification
    - Read the durable record: `COMPLETE` → exit 0; `FAILED_CONFLICT` → non-zero; unexpired foreign
      lease → non-zero. Never "already held, skip"
    - _Requirements: 7.5_

  - [ ] 7.4 Two-phase fencing on source **and** destination
    - Acquire: absent-or-lower → set `= G`. Mutate/delete: `= G` **plus the expected five-field
      tuple** (no stored hash)
    - _Requirements: 7.6, 7.14, 7.15, 7.16_

  - [ ] 7.5 Zero-row classification by reread
    - generation differs → lost fence, stop; tuple equals intended result → idempotent success,
      record `MIGRATED`; generation matches and tuple equals captured input → retry CAS; else
      `FAILED_CONFLICT`. Source absence classified the same way
    - _Requirements: 7.6_

  - [ ] 7.6 Archive collection with reconciliation
    - Fields: `repairId`, `generation`, `sourceCollection`, `sourceId`, `payload`, `payloadHash`,
      `decision`, `status` (`PENDING` → `COMMITTED` | `SUPERSEDED`)
    - Unique index on `(repairId, generation, sourceCollection, sourceId)`; **partial** unique index
      on `status = COMMITTED`
    - Two-phase reconciliation: evaluate all candidates → select highest corroborated → transition
    - **No recovery path deletes a source without first proving the destination holds the expected tuple**
    - _Requirements: 7.6, 7.14, 7.15, 7.16_

  - [ ] 7.7 **Integration tests: crash, retry, and fencing scenarios**
    - Fifteen concrete Testcontainers MongoDB tests, not reasoning exercises:
    - [ ] 7.7.1 crash after destination write, before `MIGRATED` → retry converges, no duplicate
    - [ ] 7.7.2 crash after source delete, before `MIGRATED` → absence classified as success
    - [ ] 7.7.3 crash between archive `PENDING` and source delete → reconciliation retries and commits
    - [ ] 7.7.4 crash after source delete, before archive `COMMITTED` → `PENDING` promoted, not re-deleted
    - [ ] 7.7.5 lease expiry mid-repair, reclaim by new generation, **stale runner's write rejected**
    - [ ] 7.7.6 concurrent first-claim upsert race → exactly one winner, loser classified not skipped
    - [ ] 7.7.7 same-generation retry against an already-fenced document → succeeds (regression: the
          absent-or-lower predicate blocked this)
    - [ ] 7.7.8 conflicting `updatedAt` payloads → `FAILED_CONFLICT`, terminal, not retried on next claim
    - [ ] 7.7.9 both documents exist, **newer `updatedAt` wins**
    - [ ] 7.7.10 both exist, **known `updatedAt` beats null**
    - [ ] 7.7.11 both exist, **both `updatedAt` null → destination retained**, source archived
    - [ ] 7.7.12 both exist, same `updatedAt`, **identical field values → collapse idempotently**
    - [ ] 7.7.13 the **five-field tuple moves atomically** — assert no destination document ever holds
          `currentPrice` from one source with `previousReferencePrice`/`previousReferenceAt` from the
          other, which would produce a change figure describing no real interval
    - [ ] 7.7.14 multiple prior `PENDING` generations → highest corroborated wins, others `SUPERSEDED`
    - [ ] 7.7.15 destination missing expected tuple → deletion refused, no data loss
    - _Requirements: 7.5, 7.6, 7.14, 7.15, 7.16_

- [ ] 8. Refresh set, freshness, demo, and projection
  - [ ] 8.1 `resolveTrackedTickers()` returns Active_Assets; retire the Mongo union
    - _Requirements: 5.1, 5.2, 5.3, 5.6, 5.7_

  - [ ] 8.2 Projection: currency normalization before comparison
    - null → resolve from catalog; unresolvable → reject+surface; non-null must equal catalog,
      mismatch → reject+surface; ticker absent from catalog → reject+surface regardless of currency
    - Never default to `USD`
    - _Requirements: 9.3, 9.4, 9.5, 9.6, 9.11, 9.12_

  - [ ] 8.3 Projection: tuple upsert, every transition **and its outcome**
    - Rows-affected alone collapses cases that must be distinguished. Against **real Postgres**:
    - [ ] 8.3.1 newer-over-known → tuple written
    - [ ] 8.3.2 older-over-known → **nothing** written: not price, not currency, not timestamp
    - [ ] 8.3.3 equal timestamp, **identical** payload → idempotent no-op
    - [ ] 8.3.4 equal timestamp, **conflicting** payload → surfaced, not silently dropped
    - [ ] 8.3.5 known-over-null → written (legacy row acquires provenance)
    - [ ] 8.3.6 null-over-known → **nothing** written (no downgrade to `UNKNOWN`)
    - [ ] 8.3.7 null-over-null → written, timestamp stays null, **later-received wins**, and the
          observable undated-event signal is emitted
    - [ ] 8.3.8 first insert with and without a timestamp
    - A happy-path-only test would pass while the predicate permitted the downgrade
    - _Requirements: 9.2, 9.13, 9.14, 9.15, 9.16, 9.17, 9.18, 9.19, 9.20, 9.21, 9.22, 9.29_

  - [ ] 8.4 Projection: one normalised observation identity
    - Normalise once at the top; bind the identical value to both statements
    - `@Async` removal is task 3.4 (R1) — it must precede checkpoint 9.4, not ship here
    - The observable undated-event signal (9.22) is implemented and asserted in 8.3.7
    - Preserve `updated_at` receive-time semantics; it is never a freshness input
    - _Requirements: 9.30_ · _Design: D9_

  - [ ] 8.5 History conflict detection and single transaction
    - Insert-then-compare; identical → no-op; conflicting → surface; latest-row + history in one
      transaction so a conflict leaves both tables unchanged
    - Undated event: latest row only, **no** history row, no Receive_Time substitute, signal emitted
    - _Requirements: 9.23, 9.24, 9.25, 9.26, 9.27, 9.28_

  - [ ] 8.6 Freshness pure function and summary contract
    - Inputs: row presence, observation timestamp, threshold, evaluation time
    - Threshold `(N × 24h) + grace`, defaults **N = 2, grace = 2h → 50h**
    - `assetPriceFreshness` with state, `oldestKnownAssetPriceObservationTimestamp`, and stale /
      unknown / missing counts; precedence `MISSING > UNKNOWN > STALE > FRESH`; empty portfolio
      `FRESH`, all three counts zero, timestamp absent, `partialValuation` false
    - **Valuation rules, tested explicitly:** stale **and** unknown holdings remain **included** in
      `totalValue` at last known price; missing holdings are **excluded** and set `partialValuation`;
      the existing unavailable-FX exclusion behaviour is **unchanged** (9.49)
    - `oldestKnown…` computed over known timestamps only; absent when none known, and its absence is
      not read as freshness
    - _Requirements: 9.31, 9.32, 9.33, 9.34, 9.35, 9.36, 9.37, 9.38, 9.39, 9.40, 9.41, 9.42, 9.43, 9.44, 9.45, 9.46, 9.47, 9.49_

  - [ ] 8.7 `DemoPortfolioInitializer` — **built and shipped gated off**
    - `pg_advisory_xact_lock` on the **same transaction and connection** as compare/delete/recreate
    - `app.demo.seed-on-startup` default `false`; `app.demo.cost-basis-anchor` fixed
    - Compares complete desired state **including `costBasisAsOf`**
    - _Requirements: 8.1, 8.2, 8.3, 8.5, 8.6, 8.7_

  - [ ] 8.8 Replace hard-coded catalog-size assertions
    - `api-live-smoke.spec.ts` asserts count **equal to** `Active_Asset` cardinality; catalog version
      asserted separately. Fixture catalogs may still assert their own known size
    - _Requirements: 8.8, 8.9, 8.10_

- [ ] 9. Cutover — stop/go checkpoints
  Every irreversible action is its own checkpoint. **Do not batch them.** Each states its go
  condition and its abort action; if the go condition is unmet, abort rather than proceed.

  - [ ] 9.1 **CHECKPOINT — R1 deployed, inert**
    - Go: all three services report the same `catalogVersion`; no migrations in the artifact; both
      enforcement gates `false`
    - Abort: redeploy previous artifact. Fully reversible.

  - [ ] 9.2 **CHECKPOINT — R2 producer narrowed**
    - Go: refresh desired set is Active_Assets; no execution of a prior Job revision is running
    - Abort: revert R2. Reversible.

  - [ ] 9.3 **CHECKPOINT — refresh write capability disabled**
    - Go: `MARKET_DATA_JOB_RUNNER_ENABLED=false` read back from the deployed Job; no execution
      `Running`; the next scheduled fire **succeeded** and emitted `refresh_suspended`
    - **Abort the cutover if verification fails** — an execution still running, or a fire that
      refreshed instead of suspending. A property update does not stop an in-flight execution.

  - [ ] 9.4 **CHECKPOINT — Kafka drained**
    - Go: consumer lag zero on **every** partition, observed after 9.3, or retention window elapsed
    - Waivable **only here**, explicitly and on the record, with discarded events counted and surfaced
    - _Requirements: 9.7, 9.8, 9.9, 9.10_

  - [ ] 9.5 **CHECKPOINT — holding writes quiesced**
    - Go: `ingress_enabled = false` applied; in-flight requests drained; no synthetic seed invocation running
    - Abort: re-enable ingress. Reversible.

  - [ ] 9.6 **CHECKPOINT — R3a Postgres repair — IRREVERSIBLE**
    - Go: 9.3, 9.4, 9.5 all green
    - _Requirements: 7.17, 7.31_
    - Post: `Post_Migration_Integrity_Assertion` passes; audit records written
    - Abort: **database restore only.** This is the point of no return; do not proceed without a
      verified backup taken after 9.5.

  - [ ] 9.7 **CHECKPOINT — R3b Mongo repair — IRREVERSIBLE**
    - Go: 9.6 assertion passed; refresh still suspended
    - Run the repair Job explicitly; Go on exit `0` and durable `COMPLETE`
    - Abort: `FAILED_CONFLICT` or non-zero exit stops the cutover for operator resolution. Do **not**
      re-run blindly; the state machine is terminal by design.

  - [ ] 9.8 **CHECKPOINT — R4 deployed, still overridden**
    - Go: artifact deployed with defaults `true` but Terraform overrides still `false`; behaviour
      unchanged. Reversible.

  - [ ] 9.9 **CHECKPOINT — enforcement enabled**
    - Terraform removes both overrides **and** raises `min_replicas` 0 → 1 in the same apply;
      **ingress stays disabled**
    - Go: active revision is the R4 revision, image digest matches, every startup line under that
      revision reports one distinct `(catalogVersion, rejectUnsupportedEvents=true,
      enforceHoldingInvariant=true)`
    - Abort: re-add overrides — but **only with writes still closed**

  - [ ] 9.10 **CHECKPOINT — controlled refresh execution**
    - Start **one** execution with a **full-template override** enabling refresh; persisted Job
      configuration stays disabled
    - [ ] 9.10.1 Verify the override template **in full** before starting — complete template, not a
          patch; image digest matches expected
    - [ ] 9.10.2 Go: execution exits 0; catalog version matches; events reached Kafka; projection drained
    - **Abort: "nothing persisted" is true only of the Job configuration.** A failed execution may
      already have written Mongo price documents, published `PriceUpdatedEvent`s, and advanced the
      Postgres projection. Before deciding whether a retry is safe: capture the execution's logs and
      exit code; determine how far the ticker loop progressed; reconcile Mongo, Kafka offsets, and
      `market_prices` / `market_price_history` against what it claims to have done. Retry **only**
      once that reconciliation shows a consistent partial state. A blind retry against a partially
      advanced projection is how a conflicting observation gets written at the same timestamp.

  - [ ] 9.11 **CHECKPOINT — persist refresh enablement**
    - Go: `MARKET_DATA_JOB_RUNNER_ENABLED=true` persisted through Terraform and read back

  - [ ] 9.12 **CHECKPOINT — demo portfolio activation** (while `min_replicas = 1`, ingress still closed)
    - Must run **before** scale is restored to zero. With ingress closed and `min_replicas = 0`
      there is no traffic to wake a replica, so an initializer rollout could deploy and never
      execute — the same dormant-startup problem the Mongo repair Job already solved.
    - Set `app.demo.seed-on-startup=true`, roll the service, then set it back to `false` and roll
      again — **both rollouts happen here**, while replicas are guaranteed to start
    - Go: **exactly one** mutation occurred and every other replica logged a serialized no-op. The
      advisory lock serializes invocations; it does not stop each replica from invoking the
      initializer, so "one mutation, N-1 no-ops" is the assertion, not "one invocation"
    - Go: the demo portfolio holds exactly the Active_Asset set, quantities and cost bases match the
      deterministic expectation from `(active catalog, userId, anchor)`, and the E2E portfolio is untouched
    - _Requirements: 8.1, 8.2, 8.3, 8.5, 8.6_

  - [ ] 9.13 **CHECKPOINT — restore scale, verify at configuration level**
    - Terraform restores `min_replicas` → 0, creating another revision
    - Go: `az containerapp revision show` confirms it is active, image digest expected, no enforcement
      override present. **Not** verified by startup log — it may legitimately never start.

  - [ ] 9.14 **CHECKPOINT — reopen ingress**
    - Go: 9.9 through 9.13 all green
    - Rollback rule from here on: disabling the holding validator, **or rolling back to an R3
      artifact whose defaults are permissive**, requires quiescing writes first and keeping them
      closed until the forward fix deploys

## Notes

**Where the risk actually is.** Wave 7 is disproportionately hard. Ten of the design review's P1s
landed in the Mongo repair, and task 7.7's fifteen scenarios exist because each was a specific defect
found in review — 7.7.7 in particular is a regression test for a predicate that would have blocked
its own retries. Do not treat that list as exhaustive optimism; treat it as the minimum.

**Task 5.4 is a provider-upgrade tripwire.** It asserts a property of azurerm 4.81.0 that a future
version may change. If it fails after a provider bump, the suspension mechanism needs redesign — not
a test update.

**Task 8.3 must run against real Postgres.** The six transitions depend on three-valued logic
(`NULL > timestamp` is `NULL`, not false). This was never verified empirically during design; a
happy-path test would pass while the predicate silently permitted the forbidden downgrade.

**Checkpoints 9.6 and 9.7 are the irreversible ones — 9.6 is the *first*, not the last.** Everything
before is a redeploy; everything after is a property flip. Take a verified Postgres backup between 9.5
and 9.6, **and** capture verified Mongo recovery evidence before 9.7 — a Postgres backup does not
restore the Mongo collection the second repair rewrites.

## Task Dependency Graph

```
1 (common-catalog)
 └─> 2 (manifest + packaging)
      └─> 3 (consumers adopt, fail-to-start)
           ├─> 4 (validation + freshness code, gated)
           ├─> 5 (suspension machinery + plan assertions)
           └─> 8.1 (refresh set)   [code only; not enabled until 9.2]

3.4 (@Async removed)  ──> MUST precede 9.4, or consumer lag proves nothing

4, 5 ──> 9.1 ─> 9.2 ─> 9.3 ─> 9.4 ─> 9.5
                                       └─> 6 + 6.9 tests ══> 9.6  IRREVERSIBLE (first)
                                                              └─> 7 + 7.7 tests ══> 9.7  IRREVERSIBLE
                                                                                     └─> 9.8 ─> 9.9
                                                                                               └─> 9.10 ─> 9.11 ─> 9.12 (demo) ─> 9.13 ─> 9.14

8.2–8.6 (projection + freshness) ──> ship with 6 (they read observed_at)
8.7 (demo initializer)           ──> BUILT in wave 8, shipped gated off; ACTIVATED at 9.12
8.8 (count assertions)           ──> after 2 (needs lifecycleStatus)
6.9 / 7.7 (test matrices)        ──> written and green BEFORE the maintenance window opens
```

Waves 1–5 and 8.1 can be developed in parallel with each other. **No R3a/R3b production execution
starts until checkpoint 9.5 is green** — the migrations and the repair Job are written and their
test matrices (6.9, 7.7) are green well before the maintenance window; only their execution
against production is gated.
