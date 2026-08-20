# Cursor Kickoff — Spec A tasks 8.2–8.6: complete the R3a artifact

**Date:** 2026-08-19
**Prepared for:** Cursor (implementation)
**Base branch:** `feat/supported-asset-postgres-repair` @ `76e39e8` — **not `main`**
**Working branch:** continue on `feat/supported-asset-postgres-repair` (or a child branch that merges back into it before any PR). The output of this task and task 6 must be **one** artifact.

---

## 0. Why this is on top of the task-6 branch, and why the resting state is still "no PR"

The design defines release **R3a** as *"first artifact containing V17–V19, **plus the tuple projection and the freshness response, which now have their column**"* (design.md ~L1400). The task dependency graph says the same: `8.2–8.6 (projection + freshness) ──> ship with 6 (they read observed_at)`.

Task 6 as delivered adds `market_prices.observed_at` and **nothing populates it** — `MarketPriceProjectionService` is untouched on that branch. That is not a defect in task 6; its kickoff scoped it to task 6 alone. But it means the branch is not yet a shippable R3a. Merged as-is, V17 would create a column that stays `NULL` forever, Requirement 9.1 stays unsatisfied, and freshness would read `UNKNOWN` for every asset.

The merge constraint follows directly: **`deploy.yml` full-deploys `portfolio-service` on any merge, and Flyway runs on boot.** Whichever branch merges first executes V17–V19 in production. So 8.2–8.6 must land **in the same deploy as V17–V19, or before it** — never after. Building them on the task-6 branch makes R3a *that branch*, one PR, one deploy, inside the maintenance window.

**Resting state is unchanged from task 6: pushed, no PR.** R3a executes only at checkpoint 9.6, after refresh suspension, Kafka drain, ingress disable, and a verified Postgres backup. Report back with: branch pushed, all transition and freshness tests green, no PR.

Design is frozen at Revision 10. Where a task and the design disagree, the design is normative.

## 1. Scope

**Tasks 8.2, 8.3, 8.4, 8.5, 8.6 only.**

- 8.2 — projection: currency normalisation before comparison
- 8.3 — projection: tuple upsert, every transition **and its outcome**, against real Postgres
- 8.4 — projection: one normalised observation identity
- 8.5 — history conflict detection, single transaction
- 8.6 — freshness pure function and summary contract

**Out of scope, deliberately:** 8.1 (refresh set → Active_Assets — that's `market-data-service` and R2, a different artifact), 8.7 (`DemoPortfolioInitializer` — activated at 9.12, separate concern), 8.8 (hard-coded count assertions — appears already addressed by `fc7f69d` in tasks 1–3; verify and tick if so, but don't go hunting). Task 9 in its entirety.

## 2. What already exists on `main` — do not rebuild it

- `MarketPriceProjectionService.upsertLatestPrice` — `@Transactional`, `@Async` **already removed** (task 3.4). Currently upserts `(current_price, quote_currency, updated_at)` guarded by `IS DISTINCT FROM` on price/currency, then appends history keyed on `(ticker, observed_at)` truncated to millis. **This is the method you rewrite.** It has no catalog wiring and reads no gate today.
- `app.catalog.reject-unsupported-events` — declared (`application.yml:35`, default `false`), logged at startup by `PortfolioCatalogConfiguration`, and **read by nothing**. 8.2's rejection is its first consumer.
- `SupportedCatalog` bean, `find(ticker)`, `isActive(ticker)` — from tasks 1–3, injectable.
- `PortfolioSummaryDto` (`userId, portfolioCount, totalHoldings, totalValue, baseCurrency, partialValuation`) and `PortfolioService` ~L140–170 where `partialValuation` is computed. 8.6 extends this.
- `PriceUpdatedEvent(ticker, newPrice, quoteCurrency, observedAt, …)` — `quoteCurrency` and `observedAt` both nullable for old-shape events.
- On the task-6 branch: `market_prices.observed_at TIMESTAMP(3) NULL` (V17), and `market_price_history.observed_at` at `TIMESTAMP(3)`.

## 3. Task 8.3 — the tuple upsert, and the predicate the design admits it never tested

The design (§6, "MarketPriceProjectionService — rewritten") gives this SQL and then says of its own reasoning: *"That reasoning is standard three-valued SQL semantics, but it is the mechanism the whole null-transition contract rests on and it is **not** verified here."*

```sql
INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at)
VALUES (:ticker, :price, :currency, :observedAt, now())
ON CONFLICT (ticker) DO UPDATE
   SET current_price  = EXCLUDED.current_price,
       quote_currency = EXCLUDED.quote_currency,
       observed_at    = EXCLUDED.observed_at,
       updated_at     = EXCLUDED.updated_at
 WHERE market_prices.observed_at IS NULL
    OR EXCLUDED.observed_at > market_prices.observed_at
```

**It has now been verified — against Postgres 18, before this kickoff was written.** All eight 8.3 sub-cases behave as the design claims:

| case | incoming vs stored | rows | verified result |
|---|---|---|---|
| 8.3.1 | newer over known | **1** | tuple written |
| 8.3.2 | older over known | **0** | nothing written — price, currency, timestamp all retained |
| 8.3.3 | equal, identical payload | **0** | (SQL cannot distinguish from 8.3.4 — see below) |
| 8.3.4 | equal, conflicting payload | **0** | (same — application must disambiguate) |
| 8.3.5 | known over stored-null | **1** | written, legacy row acquires provenance |
| **8.3.6** | **null over known** | **0** | **not written** — `NULL > ts` is `NULL`, not true. The downgrade is prevented by three-valued logic, exactly as designed |
| 8.3.7 | null over null | **1** | written, `observed_at` stays null, later-received wins |
| 8.3.8 | first insert, dated / undated | **1 / 1** | both insert |

So: use the design's SQL as written. **Do not** add application branching to "help" the null cases — D8 says tuple atomicity is enforced by the `WHERE` clause, not by branching, precisely to avoid a read-modify-write race.

**Your 8.3 tests must still run against real Postgres (Testcontainers), asserting rows-affected for each of the eight cases** — the spec's notes are explicit that a happy-path-only test would pass while the predicate silently permitted the downgrade. The pre-verification above tells you the tests *will* pass; it does not replace them.

### The two cases SQL cannot see — 8.3.3 vs 8.3.4

Both return 0 rows. The service must disambiguate **in application code**, by re-reading after a zero-row result:

- stored timestamp equals incoming **and** payload identical → idempotent no-op, log at debug
- stored timestamp equals incoming **and** payload conflicts → **raise and surface** — equal timestamps carrying different prices are an upstream fault that last-write-wins would conceal

Do **not** re-read on every call — only when the upsert reports 0 rows and the incoming timestamp is non-null. (0 rows with a null incoming timestamp is 8.3.6, the correct downgrade refusal, not a conflict.)

## 4. Task 8.2 — currency normalisation, and its gate

Order matters: normalisation happens **before** any tuple comparison, because a null currency cannot participate in equality against a `NOT NULL` column.

- **null incoming** → resolve from `SupportedCatalog.find(ticker).quoteCurrency()`; unresolvable → reject and surface
- **non-null incoming** → must equal the catalog's `quoteCurrency`; mismatch → reject and surface
- **ticker absent from catalog** → reject and surface, regardless of currency
- **Never default to `USD`.** The current code does exactly that (`COALESCE(?, 'USD')`). Remove it. Defaulting would silently mis-denominate every `.NS` and `=X` instrument.

### The gate — read this carefully

Requirement 9.7 says these rejection rules **SHALL NOT be activated** until R2 has narrowed the producer, both repairs have completed, and the Kafka backlog has drained. That is checkpoint 9.9 territory. So 8.2 ships the *code*, gated behind `app.catalog.reject-unsupported-events` (already declared, default `false`, currently read by nothing).

Behaviour when the gate is `false` — the state R3a will actually deploy in — must be **specified and tested, not assumed:**

- null currency, resolvable from catalog → resolve and proceed (this is safe and non-rejecting; do it regardless of gate)
- null currency, unresolvable / ticker absent from catalog / currency mismatch → **when gate is `false`:** do not reject; log the would-be rejection at WARN with a structured field, apply the pre-existing behaviour for that case, and **count it** (Requirement 9.9 requires discarded events be counted and surfaced when early-activated; the counter should exist from day one so 9.9's waiver is exercisable). **When gate is `true`:** reject, surface, do not write.

Escalate if "pre-existing behaviour" for the gate-off unresolvable-currency case is ambiguous to you — the current code's `USD` default is exactly what we're removing, so the honest gate-off answer for a null-and-unresolvable currency is probably "skip the write and count it," not "invent a currency." Raise it rather than guess.

## 5. Task 8.4 — one identity, normalised once

`observedAt` is truncated to milliseconds **once**, at the top of `upsertLatestPrice`, and that single value is bound to **both** the `market_prices` upsert and the `market_price_history` insert. Today the history path truncates and the upsert (which doesn't yet carry `observed_at`) doesn't — the design's D9 calls that "two identities for one observation."

Preserve `updated_at = now()` receive-time semantics. It is never a freshness input.

## 6. Task 8.5 — history conflict, one transaction

- Insert-then-compare on `(ticker, observed_at)`: identical → no-op; conflicting payload → **surface**
- Latest-row upsert and history insert in **one transaction**, so a history conflict leaves **both** tables unchanged
- **Undated event** (`observedAt == null`): update the latest row only (subject to 8.3.6/8.3.7), **no history row, no receive-time substitute** — a synthesised timestamp would fabricate provenance. Emit the observable undated-event signal (asserted in 8.3.7)

## 7. Task 8.6 — freshness, pure function + summary contract

```java
enum FreshnessState { FRESH, STALE, UNKNOWN, MISSING }
```

- **Pure function** of `(row presence, observation timestamp, threshold, evaluation time)`. Directly testable with no database.
- **Threshold** `(N × 24h) + grace`, defaults **N = 2, grace = 2h → 50h**. Configurable. Must **not** report stale for a normal single daily refresh (9.43).
- **Precedence** `MISSING > UNKNOWN > STALE > FRESH` for the portfolio-level state.
- **Summary contract additions:** `assetPriceFreshness` (state), `oldestKnownAssetPriceObservationTimestamp` (absent when no held asset has a known timestamp — never a sentinel date, and absence is not freshness), and three counts: stale / unknown / missing.
- **Empty portfolio:** `FRESH`, all three counts zero, timestamp absent, `partialValuation` false.
- **Valuation rules, tested explicitly:** stale **and** unknown holdings stay **included** in `totalValue` at last known price. Missing holdings are **excluded** and set `partialValuation = true`. The existing unavailable-FX exclusion behaviour is **unchanged** (9.49).
- **Naming** must make scope explicit — this is *asset-price* freshness, not whole-valuation freshness. FX rate age is out of scope (9.46–9.48).

`partialValuation`'s meaning does not change: "a holding was excluded from the total." Do not extend it to mean staleness (9.38).

## 8. Definition of done

- 8.2, 8.3 (all eight sub-cases), 8.4, 8.5, 8.6 ticked in `tasks.md`; 8.8 ticked only if you confirm `fc7f69d` already covered it.
- 8.3's eight cases green under `:portfolio-service:integrationTest` against Testcontainers Postgres, each asserting rows-affected.
- 8.6's pure function unit-tested with no database; the summary contract asserted at the controller level including the empty-portfolio case and the FX-unchanged case.
- Gate-off behaviour for 8.2 tested explicitly (see §4).
- Full `:portfolio-service:test` and `:portfolio-service:integrationTest` green — including the 18 task-6 repair cases, which must not regress. Task 6's `PostgresRepairMigrationIT` and this task's projection tests share the schema; run both.
- Spec reference check:

```bash
python scripts/check-spec-references.py .kiro/specs/supported-asset-integrity/tasks.md --against .kiro/specs/supported-asset-integrity/requirements.md --coverage --pairs
```

## 9. Escalate rather than decide

- Any temptation to branch in application code where D8 says the `WHERE` clause decides.
- The gate-off behaviour for unresolvable currency (§4) if it isn't obvious.
- Any change to `partialValuation`'s meaning.
- Any need to touch `market-data-service` — that's 8.1, a different artifact.
- Anything that would make the 18 task-6 repair tests fail.
