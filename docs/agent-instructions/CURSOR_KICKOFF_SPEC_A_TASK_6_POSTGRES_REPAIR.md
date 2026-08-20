# Cursor Kickoff — Spec A task 6: Postgres repair (V17–V19)

**Date:** 2026-08-18
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` @ `10224eb` (Rev 10 merged; latest deploy run remains `b20e68b`)
**Predecessor:** Spec A tasks 1–5 merged and live in production. Design Rev 10 is on `main`.

---

## 0. Read this before anything else: this wave does not merge like the last five did

Every previous kickoff in this series — Wave P, Spec A tasks 1–3, Spec A tasks 4–5 — ended the same way: implement, open a PR, pick a quiet window, merge, let the automatic `deploy.yml` push trigger fire, done. That pattern **does not apply here**, and applying it by habit would run an irreversible migration against live production data with no safety net.

Two facts combine to make that true:

1. **`portfolio-service` runs Flyway automatically on every boot, in every environment**, unconditionally: `flyway.enabled: true` in both `application.yml` and `application-prod.yml`, no gate, no property to defer it.
2. **`deploy.yml` full-deploys `portfolio-service` on every push to `main`** that touches its path filter — which this PR will, since `V17`–`V19` live under `portfolio-service/src/main/resources/db/migration/`.

So: merge this PR → automatic push-triggered deploy → `portfolio-service` restarts → Flyway runs `V17`, `V18`, `V19` against production Postgres **immediately**, whatever the state of `api-gateway` ingress happens to be at that moment. There is no config flag equivalent to task 4.6's enforcement gates that can hold this dark.

The design's own cutover sequence (`docs` diagram, design.md ~L580–600) is explicit that V17–V19 (labelled **R3a**) execute only *after*: the refresh Job is suspended and its suspension verified, Kafka consumer lag has drained to zero, and `api-gateway` ingress has been disabled via a **manual Terraform apply** (`ingress_enabled=false`) — a maintenance window, not a route switch. That whole sequence is **task 9's** job (Cutover — stop/go checkpoints), not task 6's. Task 6 builds R3a's payload; it does not authorize firing it.

**Therefore: this task's deliverable is code and tests on a branch. Do not open this as a mergeable PR under the usual discipline, and do not merge it, until a task 9 kickoff has walked the pre-R3a sequence above and you have explicit go-ahead for the maintenance window itself.** Push the branch, report the local evidence, and stop there. This is the one wave in the series where "PR open, awaiting merge window" is the wrong resting state — the resting state is "branch pushed, PR not yet opened."

## 1. Scope

**Task 6 only**, from `.kiro/specs/supported-asset-integrity/tasks.md`: subtasks 6.1–6.9, all eighteen cases of 6.9.

Suggested branch: `feat/supported-asset-postgres-repair`

Design is frozen at Revision 10 (bounded erratum to Section 12; the freeze stands). Where a task and the design disagree, the design is normative — and on this wave, prefer escalating over resolving, more than usual: the design's collision-outcome table (§2 below) was arrived at over those revisions specifically because early drafts got these cases wrong.

## 2. What task 6 builds

### V17 — `repair_archive`, `repair_audit`, `TIMESTAMP(3)` precision

- **`repair_archive`**: `id BIGSERIAL PK`, `migration_version VARCHAR(16)`, `source_table VARCHAR(64)`, `reason VARCHAR(32)` (`LEGACY_SYNTHETIC` | `COLLISION_LOSER` | `BASIS_UNAVAILABLE`), `natural_key TEXT`, `payload JSONB`, `archived_at TIMESTAMP(3)`. `UNIQUE (migration_version, source_table, natural_key)` — the idempotency key. **`natural_key` must be full-precision** (the original PK, e.g. the pre-truncation `market_price_history` id) — keying on the truncated timestamp would make truncation-collision losers collide with each other in the archive itself.
- **`repair_audit`**: `PRIMARY KEY (migration_version, portfolio_id, asset_ticker)`, `action VARCHAR(16)` (`CREATED` | `REPLACED` | `MERGED`), `recorded_at TIMESTAMP(3)`. This is what makes 6.7's "created-or-replaced" check possible after the fact — a deprecated holding is just a row; nothing else distinguishes one the migration made from one it left alone.
- **Preflight before the lossy `ALTER`**: group `market_price_history` by `(ticker, date_trunc('milliseconds', observed_at))`. Identical-payload groups collapse, losers archived `COLLISION_LOSER`. **Any group with conflicting payloads aborts the entire migration before the `ALTER` runs** — discovering a bad collision after truncation means the data is already merged and unrecoverable.
- Then: `market_prices.observed_at TIMESTAMP(3) NULL` added; `market_price_history.observed_at` altered to `TIMESTAMP(3)`.

> **Rev 10 is on `main` at `10224eb`.** V17's history `ALTER` **must** use `USING date_trunc('milliseconds', observed_at)`. Postgres's default cast to `TIMESTAMP(3)` **rounds**, while the preflight's `date_trunc` and the live writer (`MarketPriceProjectionService.java:74`, `truncatedTo(MILLIS)`) both **truncate**. The `USING` clause is load-bearing: it makes the preflight key, the conversion, and live writes share one function, which is the dual-identity defect D9 exists to remove. Task 6.3 already carries this line. Do not implement an unqualified `ALTER`.

### V18 — `BTC` → `BTC-USD`

- Migrate the holding, preserving quantity and cost basis.
- Archive the **synthetic** `BTC` history rows verbatim (`reason=LEGACY_SYNTHETIC`) — these are `V2__Seed_Market_Data.sql`'s fabricated April-2026 values for a symbol never tracked against a real provider — then delete them from `market_price_history`. Do not migrate them into `BTC-USD`; merging fabricated points into a real series corrupts every change figure computed across that window.
- Archive the orphaned `BTC` current-price row to `repair_archive` (reason `LEGACY_SYNTHETIC` — it is the V2-seeded synthetic price), **then** delete it. Requirement 7.9 says removed rows are "recorded and discarded," and the archive's charter is "every row a repair deletes or discards is copied here first" — an earlier draft of this note said no archive was needed; that was wrong. Where the design's prose says discarded price-row values go to "the audit record," it means `repair_archive` — `repair_audit`'s schema (PK includes `portfolio_id`, no payload column) structurally cannot hold a price row. Do not widen `repair_audit` to satisfy that prose.
- Apply the collision rules (§3) if any portfolio already holds both `BTC` and `BTC-USD`.

### V19 — `MM.NS` → `M&M.NS`

- Migrate across `asset_holdings`, `market_prices`, `market_price_history`.
- Unlike `BTC`'s history, `MM.NS` history is **real** and migrates normally — same instrument, only the symbol was wrong.
- Apply the same collision rules if any portfolio already holds both.

**`TATAMOTORS.NS` is untouched by this wave.** It stays `DEPRECATED` in the catalog (already shipped in tasks 1–3) and any existing holding must be byte-unchanged after all three migrations — 6.9.18 exists specifically to pin that.

## 3. Collision rules — decided, not left as choices

These are frozen decisions from nine design revisions. Implement exactly this table; do not re-derive it.

**Holdings**, where a portfolio holds both source and destination ticker:

| case | outcome |
|---|---|
| normal case | **combine.** Quantities sum; cost basis is the quantity-weighted average `(q1·c1 + q2·c2)/(q1+q2)` |
| `cost_basis_currency` differs between the two rows | **abort.** Averaging across currencies is meaningless |
| either `avg_cost_basis` is null | **whole basis tuple → null** (basis, currency, `as_of`, source all null). Both originals archived |
| `q1 + q2 <= 0` | **abort.** Reachable because the API currently permits zero/negative quantities |
| `cost_basis_as_of` | take the **later** (`max`) of the two |
| `cost_basis_source` | `MERGED`, but **only** on a successful non-null merge — never alongside a null basis |

**`market_prices`** (current-price table, one row per ticker): retain the row with the **newer** `observed_at`; known beats null; both null → retain destination, archive source.

**`market_price_history`** (append-only, key `(ticker, observed_at)`): identical `(ticker, observed_at, price, quote_currency)` collapses idempotently; **conflicting** payload at the same key → **abort**, leaving both rows for operator resolution. Do not silently pick one.

## 4. `Post_Migration_Integrity_Assertion` — task 6.7

Runs after the three migrations, before `portfolio-service` finishes starting. **It is a startup-blocking gate, not a diagnostic** — a failure must prevent the application from serving traffic, the same posture as task 3.3's `catalog_load_failed`.

Checks **both**:

1. Every `(portfolio_id, asset_ticker)` the migration created or replaced (per `repair_audit`) names an `ACTIVE` catalog entry. Pre-existing deprecated positions (like `TATAMOTORS.NS`) are **exempt** — the distinction is "created by this migration" vs. "merely left alone," which is exactly why `repair_audit` exists; nothing else can reconstruct that distinction after the fact.
2. The whole `asset_holdings` table still satisfies the referential invariant (every ticker resolves to some catalog entry, active or deprecated).

Checking only #2 is insufficient — a migration could create a brand-new deprecated position and pass a referential-only check.

Per-migration postconditions (design's exact table — implement these as the concrete assertions):

- **V18**: zero `asset_holdings`/`market_prices`/`market_price_history` rows named `BTC`; `repair_archive` count for `(V18, market_price_history, LEGACY_SYNTHETIC)` equals the pre-migration `BTC` history row count; every archived payload round-trips typed to the original row via `payload = to_jsonb(pre_migration_row)`.
- **V19**: zero rows named `MM.NS` anywhere; `M&M.NS` history count equals pre-migration `MM.NS` count plus any pre-existing `M&M.NS` count, less collapsed duplicates recorded in the audit.
- **All**: every ticker named in `repair_audit` is `ACTIVE` in the catalog.

## 5. The 18 integration tests (6.9) — all Testcontainers-Postgres, all pre-window

The task's own text is explicit about why these exist and when they run: *"These run before the maintenance window; only the deployment at 9.6 is irreversible, so there is no reason to discover any of this live."* Treat 100% pass on all 18 as the actual bar for this task being done — not the migrations compiling, not a happy-path smoke test.

1. Precision collision, identical payloads → collapse, losers archived `COLLISION_LOSER`
2. Precision collision, conflicting payloads → **abort before the `ALTER`**
3. `BTC` history archived verbatim → archive count equals pre-migration count; `payload = to_jsonb(row)` round-trips every column's type
4. Zero operational `BTC` history rows remain after V18
5. Holding collision, both symbols held → quantities combined, weighted basis correct
6. Holding collision, currency mismatch → abort
7. Holding collision, either basis null → whole tuple null, both originals archived
8. Holding collision, `q1+q2 <= 0` → abort
9. `MM.NS` migrated across holdings, prices, history with continuity preserved
10. `market_prices` collision, newer `observed_at` wins
11. `market_prices` collision, known beats null
12. `market_prices` collision, both null → destination retained, source archived
13. `market_price_history` collision at one `(ticker, observed_at)`, identical payload → collapse
14. `market_price_history` collision at one `(ticker, observed_at)`, conflicting payload → **abort**
15. `market_prices` collision, equal known `observed_at` + identical payload → idempotent collapse
16. `market_prices` collision, equal known `observed_at` + conflicting payload → **abort without deleting or altering either candidate** — both survive for operator resolution
17. `Post_Migration_Integrity_Assertion` fails a migration-created deprecated position, passes a pre-existing one
18. Any persisted `TATAMOTORS.NS` holding is **byte-unchanged** after all migrations — not deleted, not reassigned, quantity and cost basis intact

Also assert the `repair_archive` entry for the deleted `BTC` `market_prices` row (alongside case 3) — the 18 cases as listed in the spec pin the history archive but not the price-row archive, and Requirement 7.9 requires it.

Plus: **re-execution of the full migration set is idempotent** (task 6.8) — and the test must not run through Flyway's happy path, which is vacuous: schema history means a second `migrate` applies nothing. Execute the migration SQL bodies twice directly against the same Testcontainers instance (or reset Flyway history between runs) and assert identical final state with no duplicate archive rows. This is what forces `ON CONFLICT DO NOTHING` on the archive inserts — a plain `INSERT` would error on re-execution rather than no-op.

Determinism note for the preflight collapse (cases 1, and archive-count assertions generally): the design does not name which identical-payload row survives. Retain the **lowest original `id`**; archive the rest. A nondeterministic survivor makes archive contents unassertable.

## 6. Verified anchors (checked against `10224eb`; deploy HEAD remains `b20e68b`)

- Highest existing migration: `V16__Drop_Better_Auth_Tables.sql`. `V17`–`V19` are the next free numbers — confirmed no conflict.
- `flyway.enabled: true`, `locations: classpath:db/migration` in both `portfolio-service/src/main/resources/application.yml` and `application-prod.yml` — no environment currently defers Flyway.
- `V2__Seed_Market_Data.sql` — source of the synthetic `BTC` history rows V18 must archive and remove.
- `MarketDataRefreshJobRunner`, `JobRunnerMatrixValidator` (task 5, `market-data-service`) — the suspension/matrix pattern task 9 will invoke before R3a; not this task's concern, but useful precedent for how a startup-blocking gate is structured in this codebase if `Post_Migration_Integrity_Assertion` wants a similar shape.
- `common-catalog` module (tasks 1–3) — `SupportedCatalog.isActive(ticker)` is what the integrity assertion checks each `repair_audit` entry against.

## 7. Hard constraints

- **No enforcement gate exists for this task the way 4.6 gated task 4.** The migrations either run or they don't — there is no dark-mode equivalent. That is precisely why §0's merge discipline is different.
- **Do not touch task 9's territory.** No Terraform ingress changes, no refresh-Job suspension invocation, no cutover orchestration. Task 6 is the migration payload only.
- **Do not implement Mongo repair.** That's task 7, a separate Job, explicitly *not* a startup task (design: `market-data-service` runs `min_replicas=0`, so a startup-gated repair could deploy into a revision that never starts).
- **`natural_key` in `repair_archive` must be full-precision**, not the truncated timestamp — get this wrong and truncation-collision losers collide with each other in the archive.
- **Abort means abort.** Every "abort" outcome in §3 must stop the migration transactionally before any partial write — not log-and-continue, not best-effort.

## 8. Definition of done

- All 6.1–6.9 checkboxes ticked (6.9.1 through 6.9.18 individually) in `.kiro/specs/supported-asset-integrity/tasks.md`.
- All 18 Testcontainers-Postgres tests green, plus the idempotent-re-execution test.
- Full `:portfolio-service:test` suite green — not filtered.
- Spec reference check:

```bash
python scripts/check-spec-references.py .kiro/specs/supported-asset-integrity/tasks.md --against .kiro/specs/supported-asset-integrity/requirements.md --coverage --pairs
```

- `Post_Migration_Integrity_Assertion` has its own direct unit tests beyond 6.9.17 — the V18/V19 postcondition checks in §4 are each independently assertable against a fixture without running a full migration.

## 9. What happens after this lands

1. Report back here: branch pushed, tests green, no PR opened.
2. Task 9 kickoff (separate, not started yet) walks the pre-R3a sequence: verify refresh-Job suspension, verify Kafka drain, Terraform apply to disable `api-gateway` ingress, confirm no synthetic-monitoring seed invocation in flight.
3. Only then does this branch's PR get opened, merged, and its deploy watched — inside the maintenance window, ingress already down, by design.
4. Rollback note for later reference: rolling back past R3a is a **database restore**, not a revert. R3a is the first irreversible step in the whole spec, not the last — R3b (Mongo) is irreversible too, and a Postgres backup does not restore Mongo state. A verified Postgres backup immediately before R3a is a precondition of task 9's kickoff, not this one.

## 10. Escalate rather than decide

- Any anchor in §6 that no longer matches.
- Any collision case not covered by §3's table.
- Any pressure to open or merge the PR before a task 9 kickoff exists.
- Any ambiguity between the task text and the design's collision-outcome or postcondition tables — the design wins, but flag it so the reasoning is on record.
