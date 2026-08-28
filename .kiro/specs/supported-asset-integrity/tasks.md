# Implementation Plan

**Current program status (verified 2026-08-28 at `main@cb5af200`):** implementation tasks 1–7 are complete; task 8 is
complete except 8.8. Cutover checkpoints 9.1–9.11 are complete. Checkpoint 9.11 applied through
Terraform (`spec-a-9.11-enable`) on `main@e7fad7cb` and live-read back
`MARKET_DATA_JOB_RUNNER_ENABLED=true` with an unchanged safety tuple; evidence
[`docs/runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../../../docs/runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md).
Checkpoint 9.12 source merged via PRs #167, #169, and #170; authorized enable apply ran but failed
to converge because the startup transaction was PostgreSQL read-only; enablement was rolled back;
guarded diagnostics ran non-mutating on revision `portfolio-service--0000085` and were disabled on
`portfolio-service--0000086` (both flags `false`). Demo portfolio remains at 3 holdings. Local/source
RCA verdict `MECHANISM_REPRODUCED_SETTER_UNPROVEN` is implemented but unmerged; evidence
[`docs/runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md`](../../../docs/runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md).
The 9.12 checkbox stays open. Checkpoints 9.13–9.14 remain pending and unauthorized; the three
catalog services remain at `min_replicas=1`, gateway ingress remains closed, and B1 G5 remains blocked.
See [`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`](../../../docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md)
for the living cross-program view.

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

- [x] 6. Postgres repair (V17–V19) — **first irreversible wave**
  - [x] 6.1 V17: `repair_archive` + `repair_audit`
    - Archive: `UNIQUE (migration_version, source_table, natural_key)`, natural key **full-precision**
    - Audit: `PRIMARY KEY (migration_version, portfolio_id, asset_ticker)`
    - _Requirements: 7.2, 7.20_

  - [x] 6.2 V17: `TIMESTAMP(3)` truncation preflight
    - Group history by `(ticker, date_trunc('milliseconds', observed_at))`; identical payloads
      collapse with losers archived as `COLLISION_LOSER`; **any conflicting group aborts before the
      `ALTER`** — discovering it afterwards means the data is already merged
    - _Requirements: 9.1_

  - [x] 6.3 V17: add `market_prices.observed_at TIMESTAMP(3)`, alter history to `TIMESTAMP(3)`
    - **`USING date_trunc('milliseconds', observed_at)`** — design Rev 10: the default cast rounds,
      while the writer and the 6.2 preflight truncate; the clause makes all three share one function
    - _Requirements: 9.1_

  - [x] 6.4 V18: `BTC` → `BTC-USD` holding; archive+delete synthetic `BTC` history; drop `BTC` price row
    - Archive with reason `LEGACY_SYNTHETIC`, verbatim, before deletion
    - _Requirements: 7.1, 7.2, 7.18, 7.19, 7.20_

  - [x] 6.5 V19: `MM.NS` → `M&M.NS` across holdings, prices, history
    - Canonical_Manifest ticker rename ships in the same change (Requirement 4.8 / task 2.1 deferral)
    - _Requirements: 7.3, 7.26, 7.27_

  - [x] 6.6 Collision rules, decided outcomes
    - Holdings **combine**, quantity-weighted basis; currency mismatch → abort; either basis null →
      whole basis tuple null with both originals archived; `q1+q2 <= 0` → abort;
      `cost_basis_as_of` = **later**; `cost_basis_source = MERGED` on successful non-null merge only
    - _Requirements: 7.7, 7.8, 7.9, 7.10, 7.11, 7.12, 7.13_

  - [x] 6.7 `Post_Migration_Integrity_Assertion`
    - Checks **both**: migration-created/replaced holdings (from `repair_audit`) name Active_Assets,
      **and** the whole table satisfies the Referential_Invariant; pre-existing deprecated positions
      exempt from the first
    - Blocks startup on failure — a gate, not a diagnostic
    - Per-migration postconditions per the design's table
    - _Requirements: 6.11, 6.12, 6.13, 6.14, 6.15, 6.16, 7.30_

  - [x] 6.8 Idempotency under Flyway re-execution
    - _Requirements: 7.29_

  - [x] 6.9 **Integration tests: migration scenarios** — the Postgres equivalent of 7.7
    - Eighteen concrete Testcontainers-Postgres cases. These run **before** the maintenance window;
      only the deployment at 9.6 is irreversible, so there is no reason to discover any of this live.
    - [x] 6.9.1 precision collision, identical payloads → collapse, losers archived `COLLISION_LOSER`
    - [x] 6.9.2 precision collision, conflicting payloads → **abort before the `ALTER`**
    - [x] 6.9.3 `BTC` history archived verbatim → archive count equals pre-migration count, and
          `payload = to_jsonb(row)` round-trips to original types for every column
    - [x] 6.9.4 zero operational `BTC` history rows remain after V18
    - [x] 6.9.5 holding collision, both symbols held → quantities combined, weighted basis correct
    - [x] 6.9.6 holding collision, currency mismatch → abort
    - [x] 6.9.7 holding collision, either basis null → whole basis tuple null, both originals archived
    - [x] 6.9.8 holding collision, `q1+q2 <= 0` → abort
    - [x] 6.9.9 `MM.NS` migrated across holdings, prices, and history with continuity preserved
    - [x] 6.9.10 `market_prices` collision, **newer** `observed_at` wins
    - [x] 6.9.11 `market_prices` collision, **known beats null**
    - [x] 6.9.12 `market_prices` collision, **both null → destination retained**, source archived
    - [x] 6.9.13 `market_price_history` collision at one `(ticker, observed_at)`, identical payload → collapse
    - [x] 6.9.14 `market_price_history` collision at one `(ticker, observed_at)`, conflicting payload → **abort**
    - [x] 6.9.15 `market_prices` collision, **equal known `observed_at` + identical payload** → idempotent collapse
    - [x] 6.9.16 `market_prices` collision, **equal known `observed_at` + conflicting payload** → migration
          **aborts without deleting or altering either candidate** — both rows survive for operator resolution
    - [x] 6.9.17 `Post_Migration_Integrity_Assertion` fails a migration-created deprecated position,
          and passes a pre-existing one — the distinction the audit table exists to make
    - [x] 6.9.18 any persisted `TATAMOTORS.NS` holding is **byte-unchanged** after all migrations —
          not deleted, not reassigned, quantity and cost basis intact
    - Re-execution of the full set is idempotent
    - _Requirements: 7.1, 7.3, 7.7, 7.9, 7.10, 7.11, 7.12, 7.18, 7.19, 7.20, 7.28, 7.29, 7.30_

- [x] 7. Mongo repair Job
  - [x] 7.1 Dedicated `azurerm_container_app_job`, manual trigger
    - Same `market-data-service` image; non-web mode; bounded execution timeout
    - **Repair property `true`, refresh property ABSENT** — not `false`, which would activate the
      suspended runner alongside the repair (see 5.2)
    - Exit `0` on `COMPLETE` or already-complete; non-zero on `FAILED_CONFLICT`, lost fence, timeout,
      or unverifiable state
    - _Requirements: 7.4, 7.5, 7.24, 7.25_

  - [x] 7.2 Lease claim
    - Conditional update matching absent-or-expired, excluding **both** `COMPLETE` and
      `FAILED_CONFLICT`; `$inc` generation
    - _Requirements: 7.5, 7.6_

  - [x] 7.3 Duplicate-key classification
    - Read the durable record: `COMPLETE` → exit 0; `FAILED_CONFLICT` → non-zero; unexpired foreign
      lease → non-zero. Never "already held, skip"
    - _Requirements: 7.5_

  - [x] 7.4 Two-phase fencing on source **and** destination
    - Acquire: absent-or-lower → set `= G`. Mutate/delete: `= G` **plus the expected five-field
      tuple** (no stored hash)
    - _Requirements: 7.6, 7.14, 7.15, 7.16_

  - [x] 7.5 Zero-row classification by reread
    - generation differs → lost fence, stop; tuple equals intended result → idempotent success,
      record `MIGRATED`; generation matches and tuple equals captured input → retry CAS; else
      `FAILED_CONFLICT`. Source absence classified the same way
    - _Requirements: 7.6_

  - [x] 7.6 Archive collection with reconciliation
    - Fields: `repairId`, `generation`, `sourceCollection`, `sourceId`, `payload`, `payloadHash`,
      `decision`, `status` (`PENDING` → `COMMITTED` | `SUPERSEDED`)
    - Unique index on `(repairId, generation, sourceCollection, sourceId)`; **partial** unique index
      on `status = COMMITTED`
    - Two-phase reconciliation: evaluate all candidates → select highest corroborated → transition
    - **No recovery path deletes a source without first proving the destination holds the expected tuple**
    - _Requirements: 7.6, 7.14, 7.15, 7.16_

  - [x] 7.7 **Integration tests: crash, retry, and fencing scenarios**
    - Fifteen concrete Testcontainers MongoDB tests, not reasoning exercises:
    - [x] 7.7.1 crash after destination write, before `MIGRATED` → retry converges, no duplicate
    - [x] 7.7.2 crash after source delete, before `MIGRATED` → absence classified as success
    - [x] 7.7.3 crash between archive `PENDING` and source delete → reconciliation retries and commits
    - [x] 7.7.4 crash after source delete, before archive `COMMITTED` → `PENDING` promoted, not re-deleted
    - [x] 7.7.5 lease expiry mid-repair, reclaim by new generation, **stale runner's write rejected**
    - [x] 7.7.6 concurrent first-claim upsert race → exactly one winner, loser classified not skipped
    - [x] 7.7.7 same-generation retry against an already-fenced document → succeeds (regression: the
          absent-or-lower predicate blocked this)
    - [x] 7.7.8 conflicting `updatedAt` payloads → `FAILED_CONFLICT`, terminal, not retried on next claim
    - [x] 7.7.9 both documents exist, **newer `updatedAt` wins**
    - [x] 7.7.10 both exist, **known `updatedAt` beats null**
    - [x] 7.7.11 both exist, **both `updatedAt` null → destination retained**, source archived
    - [x] 7.7.12 both exist, same `updatedAt`, **identical field values → collapse idempotently**
    - [x] 7.7.13 the **five-field tuple moves atomically** — assert no destination document ever holds
          `currentPrice` from one source with `previousReferencePrice`/`previousReferenceAt` from the
          other, which would produce a change figure describing no real interval
    - [x] 7.7.14 multiple prior `PENDING` generations → highest corroborated wins, others `SUPERSEDED`
    - [x] 7.7.15 destination missing expected tuple → deletion refused, no data loss
    - _Requirements: 7.5, 7.6, 7.14, 7.15, 7.16_

- [ ] 8. Refresh set, freshness, demo, and projection
  - [x] 8.1 `resolveTrackedTickers()` returns Active_Assets; retire the Mongo union
    - _Requirements: 5.1, 5.2, 5.3, 5.6, 5.7_

  - [x] 8.2 Projection: currency normalization before comparison
    - null → resolve from catalog; unresolvable → reject+surface; non-null must equal catalog,
      mismatch → reject+surface; ticker absent from catalog → reject+surface regardless of currency
    - Never default to `USD`
    - _Requirements: 9.3, 9.4, 9.5, 9.6, 9.11, 9.12_

  - [x] 8.3 Projection: tuple upsert, every transition **and its outcome**
    - Rows-affected alone collapses cases that must be distinguished. Against **real Postgres**:
    - [x] 8.3.1 newer-over-known → tuple written
    - [x] 8.3.2 older-over-known → **nothing** written: not price, not currency, not timestamp
    - [x] 8.3.3 equal timestamp, **identical** payload → idempotent no-op
    - [x] 8.3.4 equal timestamp, **conflicting** payload → surfaced, not silently dropped
    - [x] 8.3.5 known-over-null → written (legacy row acquires provenance)
    - [x] 8.3.6 null-over-known → **nothing** written (no downgrade to `UNKNOWN`)
    - [x] 8.3.7 null-over-null → written, timestamp stays null, **later-received wins**, and the
          observable undated-event signal is emitted
    - [x] 8.3.8 first insert with and without a timestamp
    - A happy-path-only test would pass while the predicate permitted the downgrade
    - _Requirements: 9.2, 9.13, 9.14, 9.15, 9.16, 9.17, 9.18, 9.19, 9.20, 9.21, 9.22, 9.29_

  - [x] 8.4 Projection: one normalised observation identity
    - Normalise once at the top; bind the identical value to both statements
    - `@Async` removal is task 3.4 (R1) — it must precede checkpoint 9.4, not ship here
    - The observable undated-event signal (9.22) is implemented and asserted in 8.3.7
    - Preserve `updated_at` receive-time semantics; it is never a freshness input
    - _Requirements: 9.30_ · _Design: D9_

  - [x] 8.5 History conflict detection and single transaction
    - Insert-then-compare; identical → no-op; conflicting → surface; latest-row + history in one
      transaction so a conflict leaves both tables unchanged
    - Undated event: latest row only, **no** history row, no Receive_Time substitute, signal emitted
    - _Requirements: 9.23, 9.24, 9.25, 9.26, 9.27, 9.28_

  - [x] 8.6 Freshness pure function and summary contract
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

  - [x] 8.7 `DemoPortfolioInitializer` — **built and shipped gated off**
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

  - [x] 9.1 **CHECKPOINT — R1 deployed, inert** (verified 2026-08-22 against live Log Analytics:
    all three services — `portfolio-service--0000070`, `market-data-service--0000069`,
    `insight-service--0000069` — report identical `catalog_loaded version=c3dcb95e4e09212a
    entries=160 active=159 rejectUnsupportedEvents=false enforceHoldingInvariant=false`; Flyway
    history stops at V16, matching the working tree, confirming no repair migration in the
    artifact)
    - Go: all three services report the same `catalogVersion`; no migrations in the artifact; both
      enforcement gates `false`
    - Abort: redeploy previous artifact. Fully reversible.

  - [x] 9.2 **CHECKPOINT — R2 producer narrowed** (verified 2026-08-22: the last two scheduled
    refresh executions reported `updated=157/158, skipped=2/1` — 159 total, matching `active=159`
    exactly, not the old baseline-∪-Mongo emergent set; no execution `Running` at check time)
    - Go: refresh desired set is Active_Assets; no execution of a prior Job revision is running
    - Abort: revert R2. Reversible.

  - [x] 9.3 **CHECKPOINT — refresh write capability disabled** (applied 2026-08-22 via
    `infrastructure/terraform/azure/main.tf` + PR #133, `terraform apply` — in-place update, 0
    added/6 changed/0 destroyed, confirmed by `assert_job_runner_env_update.py`. Env var read back
    as `false`; no execution `Running`. Rather than wait ~14h for the next cron fire, verified by
    manually starting an execution — `market-data-refresh-job-yw0cnma` — which succeeded and
    emitted `c.w.m.MarketDataRefreshSuspendedJobRunner - refresh_suspended`, the identical code path
    a scheduled fire takes)
    - Go: `MARKET_DATA_JOB_RUNNER_ENABLED=false` read back from the deployed Job; no execution
      `Running`; the next scheduled fire **succeeded** and emitted `refresh_suspended`
    - **Abort the cutover if verification fails** — an execution still running, or a fire that
      refreshed instead of suspending. A property update does not stop an in-flight execution.

  - [x] 9.4 **CHECKPOINT — Kafka drained** (verified 2026-08-22, observed after 9.3 as required:
    topic `market-prices` end offset 24541 on its single partition; both `portfolio-group` and
    `insight-group` committed at 24541 — lag zero on both, no waiver needed)
    - Go: consumer lag zero on **every** partition, observed after 9.3, or retention window elapsed
    - Waivable **only here**, explicitly and on the record, with discarded events counted and surfaced
    - **Pre-9.9 re-verification (2026-08-23T19:05:10Z) — PASS, zero lag confirmed:**
      - Command: `kafka-consumer-groups --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --command-config client.properties --group <group> --describe --offsets --timeout 30000`
      - Image: `confluentinc/cp-kafka:8.2.0` (sha256:acbbf674f2ed40e5d0a8ca51beb0f00692c866fc22b5ce06f8cadbdc54cd4436)
      - Credentials: existing Aiven SASL_SSL/PLAIN + JKS truststore; ephemeral client.properties, deleted afterward
      - `portfolio-group` partition 0: committed=24541, log-end=24541, lag=0
      - `insight-group`  partition 0: committed=24541, log-end=24541, lag=0
      - No consumer joined either group; no reset/delete/execute operation used (read-only `--describe`)
      - Runbook: `docs/runbooks/SPEC_A_KAFKA_LAG_CHECK.md`; the 2026-08-22 command-gap is now closed
    - **Re-run this check immediately before the 9.9 apply and again during GO** — record new output
    - _Requirements: 9.7, 9.8, 9.9, 9.10_

  - [x] 9.5 **CHECKPOINT — holding writes quiesced** (applied 2026-08-22 via
    `infrastructure/terraform/azure/variables.tf` + PR #134, `terraform apply` — in-place update, 0
    added/6 changed/0 destroyed, confirmed by `assert_ingress_enabled_plan.py`. Live query confirms
    `properties.configuration.ingress: null` on api-gateway; an external request to
    `api.vibhanshu-ai-portfolio.dev` now gets connection reset, confirming the site is genuinely
    offline. No synthetic seed invocation was running before or after — the only invocation path is
    HTTP, now itself unreachable. **En route, `modules/container-app/outputs.tf`'s `app_fqdn`
    output had a real, unguarded `ingress[0]` index that throws once any container app's ingress is
    disabled — caught by this PR's own CI plan, fixed with `try(...,  null)` in the same PR, since
    nothing else in the module graph consumes that output**)
    - Go: `ingress_enabled = false` applied; in-flight requests drained; no synthetic seed invocation running
    - Abort: re-enable ingress. Reversible.

  - [x] 9.6 **CHECKPOINT — R3a Postgres repair — IRREVERSIBLE** (executed 2026-08-23 via PR #135,
    `portfolio-service` scoped deploy, revision `portfolio-service--0000073`, image tag
    `96a7e47b27154f013ac02f1cf360633f7d98a791`. PR #135 itself was a rebase of the stale
    `feat/supported-asset-postgres-repair` branch onto current `main` — that branch predated B1 Wave
    1's legacy-writer retirement and needed 4 conflicts plus 2 latent stale-test bugs resolved first;
    full trail in `docs/agent-instructions/CODEX_REVIEW_SPEC_A_REPAIR_BRANCH_STALENESS.md`. Backup
    precondition: Neon PITR, 6h rolling retention, confirmed via console; database verified quiescent
    since 9.5 (identical row counts across the whole gap) so the current window's restorability
    covers the full post-9.5 state in substance, even though the literal 9.5-completion instant had
    aged out of the 6h window by the time of execution)
    - Go: 9.3, 9.4, 9.5 all green
    - _Requirements: 7.17, 7.31_
    - Post: `Post_Migration_Integrity_Assertion` passes; audit records written — **verified two ways,
      not just log absence: (a) `Started PortfolioApplication in 45.405 seconds` printed, only
      reachable if the assertion's `@DependsOn("flywayInitializer")` constructor completed without
      throwing, and no `post_migration_integrity_failed` line exists; (b) independently re-ran the
      assertion's own 6 postcondition queries directly against Postgres — all zero (`BTC`/`MM.NS`
      gone from `asset_holdings`, `market_prices`, and operational `market_price_history`).
      `repair_audit`: 2 rows, one V18 (`BTC-USD`) one V19 (`M&M.NS`), matching the single BTC and
      single MM.NS holding found in 9.1's baseline exactly. `repair_archive`: 51 rows, reason
      `LEGACY_SYNTHETIC` — the fabricated BTC price history, archived per Requirement 7.18-7.20.**
    - Abort: **database restore only.** This is the point of no return; do not proceed without a
      verified backup taken after 9.5.

  - [x] 9.7 **CHECKPOINT — R3b Mongo repair — IRREVERSIBLE** (executed 2026-08-23 per
    `docs/runbooks/SPEC_A_MONGO_REPAIR.md`. Provisioned via [PR #136](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/136)
    (merge `31c4a73`) + scoped `market-data-service` deploy + `terraform-azure.yml apply` (3
    added: `market-data-repair-job`, its user-assigned identity, its ACR-pull role assignment) +
    digest pin to `market-data-service@sha256:8548199f...`. Full pre-trigger gate re-verified live
    immediately before starting: 9.6 intact, ingress closed, refresh fence `false`, zero running
    executions, Job config exact (Manual/1/1/0/300s), env vars correct, image digest matched,
    backup export re-checksummed and confirmed intact, live Mongo preflight clean (source present,
    destination absent, no lease, no archive). Started execution
    `market-data-repair-job-qz2v4rg`; log finish line `outcome=COMPLETE generation=1 exit=0`.
    **Independently re-verified against live Mongo, not just the log line**: `repair_leases/
    mm-ns-repair` durably `state: COMPLETE` at generation 1; `market_prices/MM.NS` absent;
    `market_prices/M&M.NS` exists with the exact source tuple (`currentPrice=2202.4540,
    quoteCurrency=INR, updatedAt=2026-06-19T08:51:01.627Z`); no `repairGeneration` left on the
    destination (fence cleared); exactly one `repair_archive` record, `status: COMMITTED`,
    `decision: MIGRATE_SOURCE` (no prior destination existed, so this was a non-colliding
    migration, not the equal-time collision path); execution's recorded image matches the pinned
    digest exactly. Ingress and the refresh writer deliberately left untouched — those are
    checkpoints 9.11/9.14, not this one.)
    - Go: 9.6 assertion passed; refresh still suspended
    - Run the repair Job explicitly; Go on exit `0` and durable `COMPLETE`
    - Abort: `FAILED_CONFLICT` or non-zero exit stops the cutover for operator resolution. Do **not**
      re-run blindly; the state machine is terminal by design.

  - [x] 9.8 **CHECKPOINT — R4 deployed, still overridden** — complete, executed out of order,
    recovered clean (see "What actually happened" below)
    - Go: artifact deployed with defaults `true` but Terraform overrides still `false`; behaviour
      unchanged. Reversible.
    - **Intended execution order (designed, reviewed by Codex on PR #137, but NOT what actually
      happened — see below) — apply Terraform before deploying the R4 image, never the reverse.**
      The container-app module's `lifecycle { ignore_changes = [template[0].container[0].image] }`
      (`infrastructure/terraform/azure/modules/container-app/main.tf:30-34`) means a Terraform apply
      only ever touches env vars/scaling/etc., never the running image — so applying first is safe and
      cannot itself trigger an image rollout. Deploying the R4 image first, before the override env vars
      exist on the live revision, would run portfolio-service with its `true` defaults **unshadowed** —
      real enforcement activating early, violating this checkpoint's "behaviour unchanged" contract.
      - 9.8.1 (intended) `terraform apply` (adds `APP_CATALOG_REJECT_UNSUPPORTED_EVENTS`/
        `APP_CATALOG_ENFORCE_HOLDING_INVARIANT` = `"false"` to all three Container Apps); read the
        applied env vars back on the live revision before proceeding
      - 9.8.2 (intended) Deploy the R4 image (`deploy-azure.yml`) to all three services; verify the
        new revision's image digest, and that the two override env vars are still present and `false`

    - **What actually happened, in real order (2026-08-23, all times UTC):**
      1. **09:39** — Merging PR #137 pushed to `main`. Unrecognized at design time: `deploy.yml`
         auto-triggers on `push` to `main` for any path under `portfolio-service/**`,
         `market-data-service/**`, `insight-service/**` (`.github/workflows/deploy.yml:19-33`), with
         no Terraform-override gate. Merging #137 (which touched all three service paths) therefore
         **auto-deployed the R4 image before any Terraform override existed** — the 9.8.1/9.8.2
         ordering above was never actually followed, because "deploy the image" was not the separate,
         controllable step it was designed to be. A separate direct push to `main` (`9b2cf0d`, a
         docstring-only fix, still under `portfolio-service/**`) triggered a second, identical
         auto-deploy shortly after.
      2. **09:39–~10:08** — Both auto-deploy runs were manually cancelled once discovered. All three
         Container Apps ended up running image `9b2cf0d` with **no Terraform override present** —
         `app.catalog.reject-unsupported-events`/`enforce-holding-invariant` live at their artifact
         default of `true`, unshadowed, for this window.
      3. **10:08** — Recovery: [Terraform apply run
         32632938894](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32632938894)
         (`terraform-azure.yml`, `action=apply`, triggered manually on `main`) added the two `false`
         overrides to all three services — no image change (module ignores image drift). Every
         apply-specific mandatory assertion passed.
      4. **Post-recovery, independently verified** (Azure CLI + Log Analytics, not just the apply
         run's own report): all three services run `9b2cf0d` with both overrides explicitly `false`;
         their own startup logs report `catalog_loaded ... rejectUnsupportedEvents=false
         enforceHoldingInvariant=false`; all three active revisions are healthy and scaled back to
         zero replicas; API gateway ingress remains absent/closed; refresh writes remain fenced
         (`MARKET_DATA_JOB_RUNNER_ENABLED=false`); no deployment workflow remains active.
      - **Exposure-window evidence (09:39–10:08, ~29 min):** Log Analytics searched for
        `RejectedPriceEventException`, `UnsupportedAssetException`, `gatedReject`, `TICKER_ABSENT`,
        `CURRENCY_MISMATCH`, and generic `ERROR` across all three services — zero matches. Ingress was
        closed and the refresh Job fenced throughout, so no externally- or refresh-driven traffic
        reached the unshadowed-`true` services. The live seed process reached only failed health
        `GET`s and made no writes. **No rejected catalog events, no writes, no data-integrity impact
        during the exposure window.**
      - Net effect: 9.8's intended resting state (artifact `true`, effective `false` via override,
        behaviour unchanged) was reached, but via an unplanned real-enforcement exposure window in the
        middle rather than the designed apply-then-deploy sequence. The exposure caused no observed
        harm, entirely because ingress and the refresh fence — both independent of this checkpoint —
        happened to be closed for unrelated reasons at the time.
      - **Root cause and prevention**: deploy-pipeline hardening, executed and verified below — complete
        as of 2026-08-23T12:23Z.
    - (Design reviewed by Codex, PR #137 — the intended ordering above and the Terraform-mechanism/
      gating-scope conclusions below came from that review, and held up as the *correct target state*;
      what went wrong was a deployment-pipeline gap outside the reviewed diff, not the design itself.)

  - [x] **PREREQUISITE for 9.9 — deploy-pipeline hardening designed, reviewed, merged, and bootstrapped**
    (2026-08-23, all times UTC). Two PRs, both reviewed by Codex through multiple rounds before merge:

    - **[PR #138](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/138)**
      (docs-only, checkpoint 9.8 actual chronology) — merged `e8d4e22`. No deploy triggered (path
      doesn't match the old filter; independently confirmed via `deploy.yml` run history).
    - **[PR #139](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/139)**
      (deploy-pipeline hardening) — merged `bc6492f`. Removes `deploy.yml`'s push trigger entirely
      (workflow_dispatch-only, with required `deployment_mode`/`expected_main_sha` and a
      `validate_deploy_dispatch.py` job that fails closed on SHA mismatch, non-main ref,
      mode/services/digest disagreement, or a non-`full` mode under `CLOUD_PROVIDER=aws`); removes the
      standalone `workflow_dispatch` from `deploy-azure.yml`/`deploy-aws.yml` (workflow_call-only,
      `deploy.yml` proven the sole caller — structural test covers `.yml`/`.yaml` and quoted `uses:`);
      adds a non-cancelling `production-deploy` concurrency group; adds a new `authorize-production` job
      (`environment: production`) that `route`/`deploy-azure`/`deploy-aws` all transitively depend on —
      not `environment:` directly on the `uses:` jobs, which is not a supported keyword (caught by Codex
      review round 1, confirmed independently with a pinned, checksum-verified `actionlint` v1.7.7, which
      round 2 added to `deploy-workflow-contract` itself, schema-scoped via `-shellcheck=` after CI caught
      unrelated pre-existing shellcheck noise). Final state: 90 tests across the deploy contract suite,
      `actionlint` clean.

    - **`production` GitHub Environment** — created via API (none existed before): required reviewer
      `vibhanshu-agarwal`; `deployment_branch_policy` restricted to `main` only. Two distinct properties,
      not one: **`can_admins_bypass: true`** is not a settable field on the environments API (confirmed
      against `docs.github.com`) — a genuine platform limitation for a personal-account repo, not
      something this hardening can close. **`prevent_self_review: false`**, by contrast, *is* a settable
      field on that same API — it was simply never set (left at its default) when the environment was
      created, not a platform limitation. Leaving it `false` is a reasonable, deliberate choice for a
      single-maintainer repo (the required reviewer and the person dispatching are the same person
      either way), but it should be understood as a choice, not a constraint.

    - **11-step bootstrap, executed in full** (per the corrected procedure from Codex's first review
      round on PR #139):
      1. Confirmed no queued/running Deploy runs beforehand.
      2. Captured a full pre-bootstrap production snapshot (images, revisions, override env vars,
         ingress, refresh fence).
      3. `gh workflow disable deploy.yml` — verified `disabled_manually`.
      4. Merged PR #139 → `bc6492f`.
      5. Confirmed no merge-triggered Deploy run for `bc6492f`; snapshot byte-identical to step 2
         (only the capture timestamp differed).
      6. Re-read `deploy.yml`/`deploy-azure.yml`/`deploy-aws.yml`/`ci-verification.yml` directly from
         `origin/main` (not the merged branch) — `actionlint` clean; `authorize-production` present
         with `environment: production`; `production` Environment policy confirmed live via API.
      7. `gh workflow enable deploy.yml` — verified `active`.
      8. Dispatched a deliberately wrong-SHA run
         ([32639043536](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32639043536)):
         `validate` failed with the exact expected mismatch error; `authorize-production`/`route`/
         `deploy-azure`/`deploy-aws` all `skipped` — the approval gate was never even reached.
      9. Dispatched a valid run
         ([32639079625](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32639079625)):
         `validate` passed, `authorize-production` entered `waiting` — and critically, `route`/
         `deploy-azure`/`deploy-aws` had not even been created yet at that point. Rejected via the
         pending-deployments API. Final state: `authorize-production` `failure`, the other three
         `skipped`. Direct Azure read-back confirmed `portfolio-service`'s active revision unchanged.
      10. Concurrency proof (optional step, executed): dispatched a second valid run
          ([32639141730](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32639141730))
          then, while it awaited approval, a third
          ([32639222453](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32639222453)).
          The third showed `status: pending` with **zero jobs created** — genuinely queued behind the
          non-cancelling `production-deploy` group, not run in parallel. Rejected the second, cancelled
          the third; both terminated cleanly with no deployment.
      11. Final production snapshot — byte-identical to the pre-bootstrap baseline from step 2.

    - **Final Azure state, directly read back, matching the pre-bootstrap baseline throughout**: the
      four Container Apps and `market-data-refresh-job` remain on image
      `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900`. `market-data-repair-job` is separate — still
      digest-pinned to `market-data-service@sha256:8548199f...` (ACR tag `31c4a739...`, from the 9.7
      Mongo repair, predating this bootstrap), unaffected by and unrelated to this hardening.
      `portfolio-service`/`market-data-service`/`insight-service` retain
      `APP_CATALOG_REJECT_UNSUPPORTED_EVENTS=false` / `APP_CATALOG_ENFORCE_HOLDING_INVARIANT=false`;
      `api-gateway` ingress remains absent/closed; `market-data-refresh-job`'s
      `MARKET_DATA_JOB_RUNNER_ENABLED` remains `false` (its daily cron continues to fire and exit
      cleanly under the fence — no real refresh executes).

    - **Anomalous records, noted for completeness, not a concern**: two `deploy.yml`-attributed CI run
      records appeared against commits on the hardening branch itself (`8b74ff8`, `80c4bb6`) with
      `event: push` despite that branch never matching the trigger's branch filter under any normal
      reading. Both independently confirmed inert: each contained only a zero-step, `neutral`-conclusion
      "Qodana for JVM" check apparently cross-attributed from a separate Qodana workflow run — no
      `route`, `deploy-azure`, `deploy-aws`, or any executable job/log, and no new Container App
      revisions exist anywhere near those timestamps. The exact GitHub-side attribution cause was not
      root-caused; it is structurally moot going forward since `deploy.yml` now has no push trigger at
      all to misfire.

    - **9.9 still requires its own separate plan review and explicit execution approval** — this
      prerequisite closes the deploy-pipeline gap that caused the 9.8 incident; it does not itself
      authorize 9.9.

  - [x] 9.9 **CHECKPOINT — enforcement enabled**
    - Terraform removes both overrides **and** raises `min_replicas` 0 → 1 in the same apply;
      **ingress stays disabled**
    - Go: active revision is the R4 revision, image digest matches, every startup line under that
      revision reports one distinct `(catalogVersion, rejectUnsupportedEvents=true,
      enforceHoldingInvariant=true)`
    - Only `portfolio-service` is behaviourally gated by these flags today — `market-data-service` and
      `insight-service` read them solely for the `catalog_loaded` startup log line, not for any actual
      gate (confirmed by grep + read of both services' code; no separate gating logic exists there).
      So the go-condition's startup-log check above is required on all three services, but proof of
      **actual enforcement** only needs to come from `portfolio-service`. This does not require new
      gating logic in the other two services — it's a scope fact about the existing code, not a gap.
    - Abort: re-add overrides — but **only with writes still closed**
    - **EXECUTED 2026-08-23 (UTC) — GO:**
      - Task 2 PR: [#145](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/145)
        merged as merge commit `409b706` (main SHA: `409b70676bd4bb55276a709d9b407abc3b95e6a6`)
        via `--merge` strategy; rollback ancestry verified: `git merge-base rollback/spec-a-9.9-abort main`
        → `36c9ee0532c59197624ef258512af012f9559f00` ✓
      - **Out-of-band prerequisite (2026-08-23T21:44Z):** `github-production-environment` federated
        identity credential added to the Entra App Registration (`appId: 7afa23a3-6641-40dd-af94-c9a66b782da8`)
        — subject `repo:vibhanshu-agarwal/wealthmgmtandportfoliotracker:environment:production`,
        issuer `https://token.actions.githubusercontent.com`, audience `api://AzureADTokenExchange`.
        This was not one of Terraform's three changes; it was a prerequisite for the `production`
        Environment OIDC gate added in PR #141. Documented in `docs/runbooks/AZURE_SECRETS_SETUP.md`.
      - Remote plan: run [32667213373](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32667213373)
        — `spec-a-9.9-enable`, SHA `409b70676bd4bb55276a709d9b407abc3b95e6a6`, image tag
        `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900`; plan: 0 add, **3 change**, 0 destroy;
        all mandatory assertions PASS
      - Apply: run [32668880869](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32668880869)
        — approved by `vibhanshu-agarwal` at the `production` Environment gate; all assertions PASS;
        `Apply complete! Resources: 0 added, 3 changed, 0 destroyed.`
      - Revision convergence (independently verified):
        - `portfolio-service` → `--0000079`, Healthy/Running, 1 ready replica
        - `market-data-service` → `--0000078`, Healthy/Running, 1 ready replica
        - `insight-service` → `--0000078`, Healthy/Running, 1 ready replica
      - Startup tuple on all three new revisions: catalog version `a00b32ac0267e1a9`,
        `rejectUnsupportedEvents=true`, `enforceHoldingInvariant=true`; both overrides absent; R4 image/version intact
      - Negative checks: no inconsistent tuples, no DLT events, no catalog failures; zero startup errors
      - Safety fences post-apply: ingress null; refresh flag false; no refresh/repair Job execution running
      - Kafka at `2026-08-23T22:08:06Z`: both `portfolio-group` and `insight-group` — committed 24541,
        log-end 24541, lag 0
      - ACR digests: all four unchanged from pre-apply baseline

  - [x] 9.10 **CHECKPOINT — controlled refresh execution**
    - Start **one** execution with a **full-template override** enabling refresh; persisted Job
      configuration stays disabled
    - [x] 9.10.1 Verify the override template **in full** before starting — complete template, not a
          patch; image digest matches expected
    - [x] 9.10.2 Go: execution exits 0; catalog version matches; events reached Kafka; projection drained
    - **Abort: "nothing persisted" is true only of the Job configuration.** A failed execution may
      already have written Mongo price documents, published `PriceUpdatedEvent`s, and advanced the
      Postgres projection. Before deciding whether a retry is safe: capture the execution's logs and
      exit code; determine how far the ticker loop progressed; reconcile Mongo, Kafka offsets, and
      `market_prices` / `market_price_history` against what it claims to have done. Retry **only**
      once that reconciliation shows a consistent partial state. A blind retry against a partially
      advanced projection is how a conflicting observation gets written at the same timestamp.
    - **RETRY-POLICY PREREQUISITE (Tasks 1–2) EXECUTED 2026-08-24 (UTC):**
      - This records the checkpoint 9.10 retry-policy prerequisite (`replica_retry_limit 1 → 0`).
        Task 1 PR: [#147](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/147)
        merged via `--squash` as `acf718d` (main SHA: `acf718d82d9f727f06f14d7ac53883f7fb240b48`);
        adds a fail-closed full-template builder/validator and disables unsafe refresh-Job retries.
      - Remote plan: run [32696139782](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32696139782)
        — `standard`, SHA `acf718d82d9f727f06f14d7ac53883f7fb240b48`, image tag
        `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900`; plan: 0 add, **1 change**, 0 destroy
        (`azurerm_container_app_job.market_data_refresh`: `replica_retry_limit 1 → 0`, all other
        attributes/blocks unchanged); all mandatory assertions PASS.
      - Apply: run [32706717308](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32706717308)
        — approved by `vibhanshu-agarwal` at the `production` Environment gate; all assertions PASS;
        `Apply complete! Resources: 0 added, 1 changed, 0 destroyed.`
      - Live read-back (independently verified): retry limit `0`, timeout `600`, runner `false`,
        image tag `9b2cf0d…`, schedule `0 8 * * *`, parallelism `1`, completion count `1`,
        ACR-pull UAMI `wealth-prod-mdrefresh-job-id`, no execution running; today's `0 8 * * *`
        scheduled run (`market-data-refresh-job-29792640`) had already `Succeeded` (gated no-op).
      - Follow-up remote plan: run [32708809577](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32708809577)
        — `standard`, `No changes. Your infrastructure matches the configuration.`
    - **CONTROLLED EXECUTION (Tasks 3–5) EXECUTED 2026-08-24 (UTC) — GO:**
      - Baseline (Task 3): catalog `catalog_loaded version=a00b32ac0267e1a9 entries=160 active=159
        rejectUnsupportedEvents=true enforceHoldingInvariant=true` on `market-data-service--0000078`;
        tag `9b2cf0d…` → ACR digest `sha256:ad61144b2e747a5dd1b1fc9f5b5a091916559adf7c30117beae3563123aa2256`
        (exact match). Kafka pre-run: both `portfolio-group`/`insight-group`, `market-prices`
        partition 0, `24541==24541`, lag `0`. Mongo `market_prices` 161 docs (161st is a legacy,
        off-catalog `GOOG` orphan — a distinct Alphabet share class from catalog ticker `GOOGL`,
        never fetched/modified by the refresh path; cleanup is a separate backlog item, out of
        scope here). Postgres `market_prices` 160 / `market_price_history` 15176 rows. All 159
        active catalog tickers independently confirmed present in both Mongo and Postgres;
        active-set SHA-256 `09d401ea7644826da83f4d038efd6c234c20904ee60729d2765a35e6c933103e`.
      - Template (Task 4): built via `spec_a_9_10_template.py build`/`verify` from the live template;
        sanitized diff exactly `containers[0].image` (tag → pinned digest) and
        `env[MARKET_DATA_JOB_RUNNER_ENABLED]` (`false → true`); checksum
        `b4b1267e717b1ea35c3fce74d30e3671f22d3193e2dac32d6832d82fe1e4e763`; independently re-verified
        (build + separate `verify` run, matching checksum) and manually reviewed field-by-field
        against the full sanitized template (container name, digest, cpu/memory/ephemeral, 9 plain
        env, 5 secret refs, runner, `SERVICE_VERSION`).
      - Immediately-before recheck (Task 5 Step 1, fresh T0 ≈`2026-08-24T11:03–11:05Z`): outside the
        schedule window, 0 running executions, template checksum re-verified match, Kafka lag
        unchanged (still `24541`/lag `0`), Mongo/Postgres watermarks unchanged from Task 3,
        `GOOG.updatedAt=2026-08-19T08:00:52.131Z` recorded specifically as the pre-run baseline.
      - Execution: **`market-data-refresh-job-0i08hio`**, started once via
        `az containerapp job start --yaml controlled-template.json`, digest verified exact match;
        **Succeeded** `11:05:39Z`→`11:06:48Z` (~69s of the 600s budget). Log record (Log Analytics,
        `wealth-prod-la`): exactly one start line, one completion summary
        (`updated=154, skipped=5, failed=0`; `154+5=159=active`), one clean shutdown line; zero
        error/fallback/DLT/rejection lines. Skips (5, reason "no price from provider"): `USDCAD=X`,
        `USDCHF=X`, `USDHKD=X`, `USDJPY=X`, `USDSGD=X`.
      - Reconciliation (Task 5 Step 5): Kafka log-end `24541→24695` (**+154**, exact match to `U`),
        both consumer groups drained to new log-end, lag `0`. Mongo `market_prices` (`updatedAt` in
        `[T0,T0+5m)`): 154 docs, ticker set = log-claimed set exactly (zero diff either direction).
        Postgres `market_price_history` and `market_prices` (`observed_at` in the same window): 154
        rows each, ticker sets match exactly. Price/currency tuple cross-check (Mongo vs Postgres):
        27/154 tickers showed apparent differences, all independently verified as correct rounding
        to Postgres's `current_price NUMERIC(19,4)` column — not a data-integrity issue.
        `GOOG.updatedAt` re-checked post-run: unchanged — orphan correctly untouched. Zero
        DLT/conflict/rejection/error signals in `portfolio-service`/`insight-service` logs during
        the window.
      - Control-plane reconfirm (Task 5 Step 6): persisted Job unchanged (`replicaRetryLimit=0`,
        runner `false`, image tag `9b2cf0d…`, schedule `0 8 * * *`); gateway ingress still closed;
        all three services `Running`/`Healthy` at `minReplicas=1` on the **same** active revisions
        as before execution — no redeploy triggered.
      - **Decision (Task 5 Step 7): GO.** Durable, sanitized record (commands/query boundaries,
        results, checksums, execution-readback normalization caveats):
        [`docs/runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md`](../../../docs/runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md).
        Full raw runtime evidence additionally exists locally under `.artifacts/spec-a-9.10/`
        (gitignored, not committed — no secrets, but out of scope for the repo).
      - **Review round 2 closed three additional proof obligations** before this checkpoint was
        considered verified: (a) the DLT negative-check window was extended from `T0+5m` through
        post-drain (`11:30Z`, past the `11:24:08Z` drain confirmation), and the `market-prices.DLT`
        topic was checked directly rather than inferred from consumer lag — a post-run
        `start-offset == end-offset == 80` reading alone does not rule out append-then-delete
        within the window, so this was closed with a pre-`T0` anchor: querying the explicit UTC
        window `[2026-07-25T00:00:00Z, 2026-08-24T13:30:00Z)` found exactly one
        `portfolio-group-dlt` consumption event within that 30-day window (`offset=79`, Kafka
        `CreateTime=2026-08-19T08:42:37Z`, five days before `T0`), with no higher offset logged
        within that same window — establishing end-offset was already `>= 80` before `T0`;
        combined with the post-run reading of exactly `80` and Kafka
        end-offsets being monotonically non-decreasing, end-offset was pinned at exactly `80`
        continuously across the entire execution+drain window — ruling out append-then-delete, not
        just proving currently-empty; (b) Mongo `updatedAt` vs Postgres `market_prices.observed_at`
        were compared at millisecond precision across all 154 tickers with zero mismatches, and an
        in-database SQL join proved `market_prices` and `market_price_history` agree on
        ticker/price/currency/observed-at with zero mismatches and zero orphans; (c) this durable
        runbook record was added under `docs/runbooks/` since `.artifacts/` is gitignored and
        cannot serve as the checkpoint's durable evidence.
      - Checkpoint 9.10 does not persist refresh enablement, seed the demo portfolio, restore
        scale-to-zero, or reopen ingress — none of that occurred. 9.11–9.14 remain unchecked and
        unauthorized.

  - [x] 9.11 **CHECKPOINT — persist refresh enablement**
    - Go: `MARKET_DATA_JOB_RUNNER_ENABLED=true` persisted through Terraform and read back
    - Source: PR #164 → `main@0b857f3c` (desired-state `true` + exact-scope enable/abort guards).
    - Production enable: remote-plan
      [33080741185](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33080741185)
      then apply
      [33091163222](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33091163222)
      on `main@e7fad7cb` with `change_profile=spec-a-9.11-enable`,
      `deployed_image_tag=9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900`; sanitized plan exactly
      `azurerm_container_app_job.market_data_refresh ["update"]`
      (`Plan: 0 to add, 1 to change, 0 to destroy`; `false → true` in-place).
    - Live read-back: runner `true`; retry `0`, timeout `600`, cron `0 8 * * *`, UserAssigned
      identity and image unchanged; no unexpected execution; gateway ingress still closed; peer
      revisions unchanged.
    - Follow-up standard remote-plan
      [33093260896](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33093260896):
      `No changes`.
    - Durable evidence:
      [`docs/runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md`](../../../docs/runbooks/SPEC_A_9_11_PERSIST_REFRESH_ENABLEMENT.md).
    - Does **not** authorize 9.12–9.14, refresh execution, ingress reopen, or B1 G5.

  - [ ] 9.12 **CHECKPOINT — demo portfolio activation** (while `min_replicas = 1`, ingress still closed)
    - **Source merged (PRs #167, #169, #170 on `main@cb5af200`); enable apply attempted and rolled back:**
      Terraform variable `demo_seed_on_startup` defaults `false`; portfolio-service-only
      `APP_DEMO_SEED_ON_STARTUP` wiring; exact-scope profiles `spec-a-9.12-enable` /
      `spec-a-9.12-disable` and adversarial `assert_spec_a_9_12_plan.py` guards implemented;
      dispatch/workflow fail-closed wiring complete. Enable apply run
      [33150399420](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33150399420)
      failed because the startup transaction was PostgreSQL read-only; disable/rollback run
      [33151372186](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33151372186)
      restored the gate to `false`. Guarded diagnostics ran non-mutating on
      `portfolio-service--0000085` and were disabled on `portfolio-service--0000086` (both flags
      `false`; demo still 3 holdings). Local/source RCA verdict
      `MECHANISM_REPRODUCED_SETTER_UNPROVEN` — evidence
      [`docs/runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md`](../../../docs/runbooks/SPEC_A_9_12_POOLED_READONLY_RCA.md).
      This checkbox must stay open until a reviewed fix is applied and authorized enable +
      restoring rollouts and live verification succeed. Does not authorize 9.13–9.14, ingress
      reopen, or B1 G5.
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
