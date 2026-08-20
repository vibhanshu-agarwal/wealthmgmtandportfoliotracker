# Cursor Kickoff — Spec A task 8.7: `DemoPortfolioInitializer` (built, shipped gated off)

**Date:** 2026-08-19
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` @ `10224eb` (or later once #113 merges — either is fine, no conflict)
**Suggested branch:** `feat/supported-asset-demo-initializer` off `main`
**Service:** `portfolio-service` only

---

## 0. Resting state — mergeable, because it's inert

The dependency graph is explicit: `8.7 (demo initializer) ──> BUILT in wave 8, shipped gated off; ACTIVATED at 9.12`.

`app.demo.seed-on-startup` defaults `false`, and with it false the initializer **does nothing** — it doesn't read the database, doesn't take a lock, doesn't compare. So this branch can be PR'd and merged under the normal discipline once green. Merging fires the full `deploy.yml` path (`portfolio-service/**`), deploys an artifact with a dormant bean, and re-seeds the demo via the existing E2E path exactly as today.

**It does not touch R3a or R3b.** Do not build this on `feat/supported-asset-postgres-repair`. It's independent of `observed_at`, of the repair tables, and of the projection. Off `main`.

**Activation is checkpoint 9.12**, which happens *after* R4 is deployed (9.8) and *while* `min_replicas = 1` with ingress still closed. That ordering constraint belongs to task 9's runbook, not to this PR — but it's why the gate must default false and why "gated off" is not optional.

Design is frozen at Revision 10. Where a task and the design disagree, the design is normative.

## 1. Scope

**Task 8.7 only.** `DemoPortfolioInitializer` — built, gated off.
_Requirements: 8.1, 8.2, 8.3, 8.5, 8.6, 8.7_

Also in scope because it's a prerequisite and a one-line change in the same file: **the cost-basis anchor** (`Instant.now().minus(25h)` → fixed `app.demo.cost-basis-anchor`). See §3 — without it the initializer cannot work at all.

Out of scope: 8.8 (the Playwright `>= 160`), task 9, anything in `market-data-service`, any change to the E2E seed endpoint's *behaviour* (it keeps seeding the E2E user on demand, exactly as today).

## 2. What it is

A startup component in `portfolio-service` that, **when enabled**, makes the demo account's portfolio converge to a deterministic desired state derived from the catalog — and does so safely across three replicas.

Requirement 8's user story: *"As someone demonstrating this application, I want the demo account to hold a populated portfolio that CI cannot destroy, so that the showcase is reliable."* The demo user is `00000000-0000-0000-0000-0000000d3110` (`demo@wealthtracker.dev`, defined in `V15__Reconcile_Auth_Seed_Users.sql`). The E2E user is `00000000-0000-0000-0000-000000000e2e`. They are different users with different portfolio rows (8.1), and a Golden-State seed of the E2E user must never touch the demo portfolio (8.5). That independence is already true structurally — `PortfolioSeedService.seed(userId)` deletes and recreates only that user's portfolios — but nothing today ever seeds the demo user at all. 8.7 is what does.

## 3. The anchor must become fixed first — or the initializer can never converge

`PortfolioSeedService.seed()` today (`~L107`) sets `costBasisAsOf = Instant.now().minus(25, HOURS).truncatedTo(MILLIS)`. That's a **moving** value.

The initializer works by comparing the *complete* desired holding state — ticker set, quantity, cost basis, currency, source, **and `costBasisAsOf`** — against what's stored, and invoking `seed(DEMO_USER_ID)` only on a difference. With a moving anchor, the desired `costBasisAsOf` differs from the stored one on **every boot**, so the comparison always fails and the initializer destructively reseeds on every restart. That's the opposite of "CI cannot destroy."

So: `app.demo.cost-basis-anchor` — a fixed `Instant`, configurable, with a sane default (e.g. a fixed date well in the past). `seed()` uses it instead of `now() − 25h`. **This changes the E2E seed too**, which is correct — the E2E portfolio's cost basis becomes deterministic across seeds, which is what Requirement 8.2 ("reproducible deterministically from the Supported_Catalog") needs, and `PortfolioSeedServiceIT`'s existing byte-identity regression across repeated seeds (Req 11, retained by B1 GC.3 / B1 Req 6.30) becomes **stronger**, not weaker — `cost_basis_as_of` now matches byte-for-byte across seeds instead of differing by wall-clock delta.

Check `PortfolioSeedServiceIT` and `PortfolioSeedServiceTest` for any assertion that `costBasisAsOf` is "recent" or "within 25h" — those would have been encoding the moving anchor and need to flip to asserting the fixed one.

## 4. The lock — same transaction, same connection, or it's worthless

`portfolio-service` runs up to three replicas. `seed()` is delete-then-recreate inside one `@Transactional`. Without serialisation, two replicas booting together can both compare, both see a difference, both delete, and interleave their inserts — a `UNIQUE (portfolio_id, asset_ticker)` violation at best, a doubled portfolio at worst.

The design specifies `pg_advisory_xact_lock` — a **transaction-scoped** advisory lock, released automatically on commit or rollback, so a crashed replica can't strand it.

**The trap the task text flags in bold:** the lock must be acquired **on the same transaction and connection** as the comparison, delete, and recreate. Concretely:

- The initializer's compare-then-maybe-seed must run inside **one** `@Transactional` boundary, and `SELECT pg_advisory_xact_lock(<constant key>)` must be the **first** statement inside it, via the same `JdbcTemplate`/`EntityManager` connection that the subsequent `PortfolioSeedService.seed()` call uses.
- If `seed()` is called from a method on a *different* bean with its own `@Transactional(REQUIRES_NEW)`, or via a different datasource/connection, the lock is on connection A and the destructive writes are on connection B — serialising nothing. Default `REQUIRED` propagation from an outer `@Transactional` initializer method into `seed()` is what you want; **verify the propagation explicitly in a test** rather than assuming.
- Pick a fixed `bigint` lock key (a hashed constant, documented). Don't derive it from anything that varies per replica.

**The assertion at 9.12 is "exactly one mutation, N−1 serialised no-ops."** Not "one invocation." Every replica invokes the initializer; the lock makes them queue; the first one in finds a difference and seeds; the rest acquire the lock after commit, re-compare against the now-converged state, find no difference, and no-op. **Each replica must log which branch it took** (`demo_portfolio_seeded` vs `demo_portfolio_converged`) with the replica identity, because that log is the 9.12 evidence — it cannot be reconstructed afterwards.

## 5. The comparison — complete desired state, not a count

8.2/8.3: the demo portfolio holds **exactly the Active_Asset set**, with quantities and cost bases derived deterministically from `(active catalog, DEMO_USER_ID, anchor)` — the same `DeterministicPriceCalculator` / `computeDeterministicCostBasis` / `floorMod(hashCode, QUANTITY_RANGE)` path the E2E seed uses, just keyed on the demo user.

The comparison must be **total**:

- ticker set equals `active()` tickers — no extras, no missing
- per holding: quantity, `avg_cost_basis`, `cost_basis_currency`, `cost_basis_source = "SEED"`, `cost_basis_as_of = anchor` all equal

A count check (`holdings == 159`) is insufficient and is exactly what 8.8/Requirement 8.10 forbids for the live catalog — a portfolio with 159 holdings one of which is wrong would pass. Compare the set, compare the rows.

**When the comparison finds a difference, it calls `PortfolioSeedService.seed(DEMO_USER_ID)`** — the existing holdings-only path (8.6, and B1 Req 6.13's "the reset SHALL be holdings-only, per Spec A's D20"). It does **not** write `market_prices` or `market_price_history`. That's already guaranteed by `seed()` having no price-write path (Req 11, delivered); don't add one.

## 6. Things that must stay true

- **8.7 — no self-service user gets seeded.** The initializer targets `DEMO_USER_ID` and nothing else. Hard-code it; don't make the user configurable, because "configurable demo user" is one typo away from seeding a real account.
- **8.5 — E2E seed can't disturb demo.** Already structural (`seed(userId)` scopes by user). Add a test that seeds E2E then asserts demo untouched, and vice versa, so it stays structural.
- **`app.demo.seed-on-startup` default `false`, in every profile.** Add it to `application.yml` explicitly as `false` rather than relying on `@ConditionalOnProperty(matchIfMissing=false)` alone — the 8.1 kickoff found a `matchIfMissing=true` seeder that was live in two profiles nobody remembered. Make the default visible.
- **The initializer does not run in the Job runners or in test contexts by accident.** It's a `portfolio-service` startup component; `portfolio-service` has no Job variant, so this is simpler than the `market-data-service` matrix — but it does run under `@SpringBootTest`. Tests that boot the full context must not trip a real seed: either leave the property false (default) or use a fixture catalog.
- **The validator gate.** `seed()` already calls `supportedAssetValidator.requireActive()` on every ticker before deleting anything. With `app.catalog.enforce-holding-invariant=true` (R4), that throws on a non-active ticker and rolls back the whole seed — which is exactly why both seed paths enumerate `active()` (task 4.5, shipped). Nothing to do here except **don't bypass it** — the initializer goes through `seed()`, not around it.

## 7. Interaction with B1 — read-only note

B1's design (D1) introduces portfolio versioning and a monotonic `updated_at` that B2's idle-reset guard reads. That's `V20`, not yet built. `seed()`'s delete-and-recreate produces a **new portfolio row** with a new id — which B1's design explicitly accounts for (its R-B2/R-B3 sequencing discusses exactly this). Nothing for 8.7 to do about it, but: **don't add an `updated_at` column or any versioning here.** That's B1's `V20`. Two migrations with the same number fail Flyway startup, and `V17`–`V19` are already on the task-6 branch.

## 8. Definition of done

- 8.7 ticked in `tasks.md`.
- `app.demo.seed-on-startup` (default `false`) and `app.demo.cost-basis-anchor` (fixed `Instant`, documented default) in `application.yml`.
- `DemoPortfolioInitializer`: gated; `pg_advisory_xact_lock` first statement inside the same transaction as compare + `seed()`; total-state comparison; seeds only on difference; structured log of which branch each invocation took.
- Tests:
  - Gate off → no DB interaction at all (verify no query issued).
  - Gate on, demo portfolio absent → seeds exactly `active()` with deterministic values; second boot is a no-op.
  - Gate on, demo portfolio present and equal → no-op, no delete.
  - Gate on, demo portfolio present with one holding wrong (quantity / basis / extra ticker / missing ticker) → reseeds.
  - **Concurrency (Testcontainers Postgres):** N threads invoking the initializer simultaneously against an absent demo portfolio → exactly one seed, N−1 converged no-ops, final state correct, no constraint violations. This is the 9.12 assertion, proven pre-window.
  - **Lock-propagation proof:** a test asserting the advisory lock is held on the same connection the seed writes use — e.g. a second connection attempting `pg_try_advisory_xact_lock(key)` inside the seed's transaction window returns false. Without this, a refactor that breaks propagation passes every other test.
  - E2E seed then demo unchanged; demo seed then E2E unchanged.
  - Existing `PortfolioSeedServiceIT` byte-identity regression still passes with the fixed anchor.
- Full `:portfolio-service:test` and `:portfolio-service:integrationTest` green.
- Spec reference check:

```bash
python scripts/check-spec-references.py .kiro/specs/supported-asset-integrity/tasks.md --against .kiro/specs/supported-asset-integrity/requirements.md --coverage --pairs
```

- PR body states: gated off / inert on merge; the anchor change and its effect on E2E seed determinism; activation is 9.12.

## 9. Escalate rather than decide

- Any way the lock and the seed writes could end up on different connections that you can't rule out with a test.
- Any temptation to make the demo user ID configurable.
- Any reason the anchor can't be fixed (e.g. a test or consumer that genuinely depends on "acquired 25h ago" semantics).
- Any need to touch `V17`+ or add a migration.
- Any assertion you'd otherwise write as `holdings == 159` against the live catalog — Req 8.10 forbids it; use `active().size()` or, better, compare sets.
