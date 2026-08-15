# Backlog: Demo Portfolio Regression and Untracked-Ticker Price Staleness

**Status:** Open — 2026-08-15
**Owner:** unassigned
**Tracked in:** Found during a live production check of the Market Data page; no changelog entry
(the regression shipped silently inside `new-user-signup-profile`, PR
[#85](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/85))

---

## Status & Decision

**Open, nothing fixed yet.** Two symptoms were reported — BTC showing `$0.00` on Market Data, and
the demo account showing 3 holdings where it used to show ~160. Both were investigated against
live production. They share one origin (V15's demo-account reassignment) and the investigation
surfaced a third, more serious defect nobody had noticed: **the demo portfolio total is valued
using a four-month-old price and reports itself as complete.**

The market-data refresh pipeline itself is **healthy** and is not the cause of any of this.

Deliberately written as one entry rather than four: the items below were found in a single audit,
share the same evidence, and interlock (item 1 depends on item 2 being fixed first, item 4 changes
how item 3 is applied). Splitting them would duplicate the context four times.

---

## What Was Reported vs What Is True

| Reported | Reality |
|---|---|
| "BTC shows $0.00" | True on Market Data. **But the portfolio total values BTC at $70,775** — a stale April-2026 seed price. Two subsystems disagree. |
| "Demo used to have ~160 holdings" | Correct. The pre-signup demo login pointed at a *different user* that still has 160 holdings today. |

---

## Root Cause (evidence, not speculation)

### 1. The demo account was pointed at the wrong portfolio (V15)

Before `new-user-signup-profile`, the single hardcoded demo login resolved to
`app.auth.user-id` = `00000000-0000-0000-0000-000000000e2e` — the **E2E test user**, whose
portfolio the Golden-State seeder fills with **160 assets**.

`V15__Reconcile_Auth_Seed_Users.sql` created a new demo account
(`demo@wealthtracker.dev` → `00000000-0000-0000-0000-0000000d3110`) and reassigned **V3's**
showcase portfolio to it — a 3-asset seed from April 2026 (`AAPL`, `TSLA`, `BTC`) that belonged to
`user-001`. The 160-asset portfolio was never copied or moved.

Verified live (2026-08-15):

| Account | Holdings |
|---|---|
| Demo `…0d3110` | **3** — AAPL 12, TSLA 8, BTC 0.75 |
| E2E `…0e2e` | **160** — canonical tickers throughout (`BTC-USD`, `BRK-B`, …) |

No data was lost. V15 only runs `UPDATE portfolios SET user_id = …`; `asset_holdings` follows via
`portfolio_id` FK. The 160-asset portfolio is intact on the E2E user.

### 2. `BTC` is not in the tracked ticker set, so its price can never refresh

`MarketDataRefreshService.resolveTrackedTickers()` returns **configured baseline ∪ tickers already
in Mongo**. `BTC` is in neither — only `BTC-USD` is. It is therefore not "failing" to refresh; it
is invisible to the pipeline and cannot be a candidate for refresh at all.

`V12__Backfill_Market_Price_History.sql` already flagged this divergence in its own comment
("canonical symbol is BTC-USD (not legacy BTC used in V2)") when it canonicalised market data —
but V3's **holding** was never migrated to match.

Verified live:

| Ticker | Price | Observed |
|---|---|---|
| `BTC` | **null** — no row in market-data-service | — |
| `BTC-USD` | **$62,944.49** | fresh, same day |

### 3. The portfolio total is silently valued on a stale price — and claims it is complete

`GET /api/portfolio/summary` for the demo account returns `totalValue: 59490.57` with
`partialValuation: false`. Decomposed:

```
AAPL  12 × 305.93   =  3,671.16
TSLA   8 × 342.27   =  2,738.16
BTC 0.75 × 70,775.00 = 53,081.25   ← V2's April-2026 seed price
                       ---------
                        59,490.57  ✓ exact match
```

`70,775.00` is precisely the value seeded by `V2__Seed_Market_Data.sql`. Against the live
`BTC-USD` price of `62,944.49`, the demo portfolio is **overstated by $5,872.88 (~10%)**.

`PortfolioService` (~L134–146) sets `partialValuation = true` **only** when an FX rate is
unavailable. There is no staleness check on `current_price`/`observed_at` anywhere — a price from
April 2026 is treated identically to one from two minutes ago. This is the most serious of the
three defects: it is invisible (no error, no log, no flag), it affects a financial total, and it
survives fixing items 1 and 2.

Note this is **not** the same as the accepted behaviour for a skipped ticker (below). Falling back
to the last known price when a provider call fails is intended. Valuing on a price that can *never*
update, while asserting completeness, is not.

---

## The Refresh Pipeline Is Healthy — Do Not "Fix" It

Established during the audit, recorded here to prevent a wrong turn:

- **Cadence is daily at 08:00 UTC**, via `azurerm_container_app_job.market_data_refresh`
  (`cron_expression = "0 8 * * *"`). The in-service `@Scheduled` adapter's `0 0 */1 * * *`
  (hourly) default is **disabled in Azure** — `application-azure.yml` sets
  `market-data.refresh.enabled: false`, and Terraform documents the Job as "the sole production
  refresh path". Up to ~24h price age is expected and acceptable by design.
- Steady state: `updated=158, skipped=3, failed=0`, consistent across days.
- On a provider miss the job **skips, keeps the last known price, and does not throw**. That is
  intended behaviour and should be preserved.

### The three skipped tickers

All three are in `config/seed-tickers.json` but **not** in `application.yml`'s `baseline.tickers`
(55) — they reach the refresh only via Mongo. Verified against Yahoo directly on 2026-08-15:

| Ticker | Verdict | Evidence |
|---|---|---|
| `MM.NS` | **Our bug — wrong symbol** | `MM.NS` → no match. **`M&M.NS`** → "MAHINDRA & MAHINDRA LTD". The `&` was dropped, plausibly to avoid URL-encoding; the entry's own `aliases` field already contains `"M&M"`. |
| `TATAMOTORS.NS` | **Real symbol drift** | No longer resolves. Tata Motors demerged into **`TMCV.NS`** (Tata Motors Limited, commercial) and **`TMPV.NS`** (Tata Motors Passenger Vehicles). |
| `USDINR=X` | **Transient — not broken** | Quote endpoint returns a live price (95.415 at time of check). Succeeded 08:00 Aug-14 and 00:51 Aug-15; failed only at 01:09, immediately after two full 161-ticker sweeps 18 minutes apart. Most likely provider throttling induced by manual test runs. **No action needed.** |

Suffix handling is **not** the problem: 17 other `.NS` and 5 other `=X` tickers refresh normally.

---

## Required Work

### 1. Give the demo account its own 160-asset portfolio

- Seed a copy independent of the E2E user — Playwright's Golden-State seeder **wipes and re-seeds**
  `…0e2e` on every run, so sharing that portfolio would let CI clobber the demo.
- Prefer sourcing tickers from data that is provably in the tracked set, so every seeded holding is
  refreshable by construction rather than by review discipline.
- Blocked on item 2: do not seed a demo portfolio that contains an untracked ticker.

### 2. Eliminate the untracked-ticker class

- Migrate the `BTC` holding to `BTC-USD`.
- Delete the orphaned `BTC` row from `market_prices` so nothing can read it again.
- Add a check that fails when any holding references a ticker outside the tracked set. This is the
  actual bug class; `BTC` is one instance of it.

### 3. Fix the two genuinely broken symbols

- `MM.NS` → `M&M.NS`. Unambiguous. Confirm the client URL-encodes `&` correctly (this is the most
  likely reason the symbol was mangled in the first place).
- `TATAMOTORS.NS` → needs a decision, see Open Decisions.
- Leave `USDINR=X` alone.

### 4. Consolidate the asset universe before the asset picker lands

`config/seed-tickers.json` is duplicated in **four** locations, currently byte-identical
(`md5 a6caba55`, 32,045 bytes):

```
config/seed-tickers.json
insight-service/src/main/resources/seed/seed-tickers.json
market-data-service/src/main/resources/seed/seed-tickers.json
portfolio-service/src/main/resources/seed/seed-tickers.json
```

> **Corrected 2026-08-15.** The claim below that "nothing enforces that they stay in sync" was
> **wrong**, and the remedy it proposed would have made things worse. Each of the three services
> registers a `copySeedTickers` Copy task wired as `dependsOn` of `processResources`
> (e.g. `portfolio-service/build.gradle:15`), so **any build re-synchronises all four copies**.
>
> The actual defect is different: the task copies into **git-tracked** `src/main/resources/seed/`
> directories. Generated content is therefore committed, git state and build state can disagree,
> and a direct edit to a service copy is silently reverted by the next build.
>
> A CI check asserting the copies match would have *enforced a redundancy that should not exist*.
> The fix is to remove the duplication: package the manifest from the repo root into build output
> and delete the three tracked copies. That is Requirement 1 of
> `.kiro/specs/supported-asset-integrity/` (see PR #98), which supersedes this item.

~~Nothing enforces that they stay in sync.~~ Item 3 requires editing the same entry in all four,
and — because the copies are tracked — a change committed without a build leaves the repository
inconsistent with itself until someone rebuilds.

Also worth resolving: the tracked set is `baseline (55) ∪ Mongo`, so the *effective* universe
depends on what was historically seeded into Mongo rather than on a declared source of truth. That
is exactly why `TATAMOTORS.NS` and `MM.NS` persist despite being absent from the baseline.

---

## Open Decisions

- **`TATAMOTORS.NS` successor:** `TMCV.NS`, `TMPV.NS`, or both? The demerger splits one holding
  into two, so any existing quantity needs an allocation rule — this is a data-migration decision,
  not just a symbol swap.
- **Staleness visibility:** the Market Data page already has a *Last Updated* column, so a stale
  asset honestly reads "57 days ago" with its last price still shown — consistent with the stated
  preference for fallback over exceptions. Decide whether that is sufficient, or whether
  `partialValuation` should also reflect staleness (noting that excluding stale holdings from
  `totalValue` was explicitly **rejected** — the requirement is "show the older price", not "drop
  the holding").
- **Demo portfolio composition:** all 160 assets, or a curated subset? Related: the planned asset
  picker would let different demo users hold different assets, which may make a hand-seeded 160
  redundant.

---

## Notes

- The asset picker (next planned feature) makes item 2's invariant self-enforcing: if the picker's
  options come from the tracked universe, a user cannot create a holding the refresh job does not
  fetch. That is a structural fix for this bug class, which is a good reason to sequence item 4
  before the picker rather than after.
- `config/seed-tickers.json` is already well-shaped for a picker: 160 assets (50 `US_EQUITY`,
  50 `NSE`, 50 `CRYPTO`, 10 `FOREX`), each with `ticker`, `name`, `aliases`, `assetClass`,
  `quoteCurrency` — `name`/`aliases` give searchable text with no new data modelling.
- New users currently sign up with an empty portfolio, which is correct behaviour and not part of
  this entry.
