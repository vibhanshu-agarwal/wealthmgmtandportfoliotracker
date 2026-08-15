# Changes Summary — Golden-State Seeder Overwriting Production Market Prices

**Date:** 2026-08-15
**PR:** [#97](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/97) (merged `b49ed01`, deployed)
**Branch:** `fix/portfolio-seeder-overwrites-production-prices`
**Scope:** `portfolio-service` (seeder + tests), `.github/workflows/synthetic-monitoring.yml`, `frontend/tests/e2e/azure-synthetic`

---

## Summary

`PortfolioSeedService.seed()` batch-upserted one `market_prices` row and one `market_price_history`
row per catalogue ticker, using synthetic values derived from `basePrice` and the seeded user's id.
Both tables are **global** — keyed by ticker, no user scoping — and the upsert carried an
unconditional `ON CONFLICT DO UPDATE` with no freshness guard. Seeding any user therefore replaced
the live refreshed price of every ticker, for every user.

That endpoint is reachable in production and was invoked there on a schedule. The Azure job in
`synthetic-monitoring.yml` runs under `vars.CLOUD_PROVIDER == 'azure'` — the current repository
variable — against `https://api.vibhanshu-ai-portfolio.dev` on `cron: '0 8 * * *'`. That is the
**same cron** as the market-data refresh ACA Job, so the two raced every morning and the winner
determined whether production carried real or synthetic prices for the next 24 hours.

The near-miss is instructive. The same workflow sets `SKIP_MARKET_DATA_SEED: "true"` and its step
comment read "Market-data seed is gated off in prod/azure (ACA Job)" — that seeder was correctly
identified as a price writer and gated. The **portfolio** seeder wrote prices as an undeclared side
effect of seeding holdings, and was not.

Found while drafting `.kiro/specs/supported-asset-integrity/`, which specifies the fix as
Requirement 11 and retains it as a regression boundary.

---

## Changes by Area

### Seeder — `com.wealth.portfolio.seed.PortfolioSeedService`

- Removed the `market_prices` upsert and the `market_price_history` anchor-row insert, along with
  both SQL constants and the `NamedParameterJdbcTemplate` collaborator.
- `SeedResult` drops `marketPricesUpserted`. The field is **removed, not zeroed**, so a stale
  consumer fails loudly rather than reading a plausible `0`.
- Cost basis is unaffected: it is derived in-memory from the catalogue's `basePrice` and was never
  read back from `market_prices`.

Three callers reach this endpoint — the workflow step, Playwright global setup, and the Azure API
smoke test — so the fix is applied at the **endpoint boundary**. Removing any single call site would
have left two production writers.

### Controller — `PortfolioSeedController`

- Response body is now `{userId, portfolioId, holdingsInserted}`.

### Tests

- `PortfolioSeedServiceIT` snapshots **every column of every row** of both price tables before
  seeding and asserts them identical afterwards, across two invocations. Sentinel rows under a
  ticker absent from the catalogue catch a wholesale wipe-and-rewrite that per-ticker comparison
  would miss.
- `PortfolioSeedControllerTest` (new) pins the response body and asserts the price-count field
  **absent**, not zero.
- `PortfolioSeedServiceTest` replaces its history-batch assertions with cost-basis determinism —
  the behaviour that had to survive.
- `api-live-smoke.spec.ts` carries the same absence assertion against production.

### Workflow

- Step renamed to "Re-seed E2E portfolio holdings", with a comment recording the old behaviour and
  an instruction not to reintroduce a price write.

---

## Tests Run

| Suite | Result |
|---|---|
| `:portfolio-service:test` (full) | BUILD SUCCESSFUL |
| `:portfolio-service:integrationTest` (full, Testcontainers) | BUILD SUCCESSFUL, 6m 19s |
| `tsc --noEmit` (frontend) | clean |
| CI on PR #97 — unit, integration, build, e2e-smoke, pact, qodana, gitleaks | all green |

---

## Production Verification

Closure rests on a chain, no link of which is sufficient alone:

1. **Deployed image SHA** — revision `portfolio-service--0000054`, active, 100% traffic, image tag
   `b49ed010698abf8e04fda7bbc66bd3a5dfd7f8b8`, exactly the merge commit.
2. **Source review** — no price-write path reachable from the seed endpoint or the seeder in that
   artifact.
3. **Production log** — a real seed executed on the new artifact at `06:20:37.952Z`:
   `Golden-state seed complete (holdings only): userId=…0e2e portfolioId=6b46… holdings=160`.
   The old code emitted `… holdings=160 marketPrices=160 historyRows=160`; those fields are gone
   because the writes that produced them are gone.
4. **Regression test** — the full-table integration guard above, against a real database.

A real refresh was then triggered and verified: `updated=156, skipped=5, failed=0`, with genuine
provider prices persisted (`BTC-USD 62,988.42`, `AAPL 305.93`, `MSFT 495.40`), none matching their
catalogue `basePrice`.

---

## Erratum

The PR description and commit message state that `portfolio-service` "has no JDBC collaborator at
all." That is true of **`PortfolioSeedService`** only. `MarketPriceProjectionService` holds its own
`JdbcTemplate` and legitimately writes both price tables from the Kafka listener — it is the correct
production writer of price state and was never in scope. The deployed fix is unaffected; only the
prose overclaims. A dated erratum was added to PR #97's description rather than amending history.

---

## Known Gaps

- The two symbols that persistently fail refresh — `MM.NS` (wrong symbol) and `TATAMOTORS.NS`
  (demerged) — are unaddressed here. See
  `docs/todos/backlog/demo-portfolio-and-ticker-integrity/` and
  `.kiro/specs/supported-asset-integrity/` Requirement 4.
- The synthetic-monitoring job and the refresh ACA Job still share the `0 8 * * *` cron. The race
  is now harmless, because the seeder cannot write prices — defused, not resolved.
- Historical synthetic rows written before this fix are **not** retro-deleted: there is no
  provenance marker to identify them safely.
