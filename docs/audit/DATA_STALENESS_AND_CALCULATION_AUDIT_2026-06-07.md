# Data Staleness & Calculation Audit

**Date:** 2026-06-07
**Scope:** Why the deployed dashboard (https://vibhanshu-ai-portfolio.dev) shows stale/static data, `+0.00%` everywhere, `+$0.00` P&L, and never refreshes.
**Method:** Static code audit only — no changes made, no live logs pulled. Confidence levels are noted per finding; items needing log/DB confirmation are flagged.

**Revision history:**
- *2026-06-07 rev2* — Incorporated a second validation pass against the code. Corrections: ticker overlap is AAPL+TSLA only (`BTC-USD` ≠ seeded `BTC`) → **158/160** holdings lack history (was 157/160); the backend performance series is *partial*, not "almost always synthetic"; `PerformanceChart.tsx` actually **prefers** the backend analytics series (§4.5 corrected). New root causes added: the **25-ticker price-API cap** (§4.7, with screenshot-level evidence), the **Azure no-TTL analytics cache** (§3.6), **frontend mixed-currency math** (§4.8), and Kafka-consumer dormancy + insight Redis edge cases (§3.2/§3.5). Stale Azure YAML comments flagged.

---

## 1. Executive Summary

The symptoms are not caused by a single bug. They are the combined result of **several independent gaps**, most structural (the data needed to compute the values is never produced, is truncated in transit, or is cached without expiry) and a few frontend wiring/mock issues.

The headline conclusions:

1. **There is no working "previous price" / history pipeline at runtime.** `market_price_history` (the table the 24h-change and performance-chart calculations read from) is **only ever written once, by a Flyway seed migration, for exactly 3 tickers (AAPL, TSLA, legacy `BTC`)**. Nothing writes to it during normal operation. In the 160-ticker golden-state portfolio, only **AAPL and TSLA** overlap (the registry uses `BTC-USD`, not `BTC`), so 24h change is structurally `0` for **158 of 160** holdings, and the 2 overlapping tickers are frozen at migration-time values.

2. **A 25-ticker cap on the price API truncates the portfolio.** `GET /api/market/prices?tickers=...` silently caps filtered requests at 25 tickers (`MAX_TICKERS_PER_REQUEST`), but the frontend sends **all** holding tickers (up to 160) in a single call. The other ~135 holdings come back with no price → `currentPrice = 0` on the client. This is visible in the screenshots: **Asset Allocation total ($242,489.41) is a fraction of Portfolio Total ($1,545,969.73)** — the allocation/holdings math runs on the truncated client-side prices, while the Portfolio Total comes from a complete backend SQL sum. (New in rev2; high confidence.)

3. **The live refresh job almost certainly never runs on Azure — but this is a consequence of an intentional cost setting, not an infra bug to "fix" now.** `market-data-service` is deployed to Azure Container Apps with `min_replicas = 0` (scale-to-zero), and its price refresh is a `@Scheduled` cron. A container scaled to zero has no process to fire the cron, so the scheduled Yahoo Finance fetch effectively never executes. The same scale-to-zero also leaves the **Kafka consumers** in `portfolio-service` and `insight-service` dormant. **`min_replicas = 0` is a deliberate budget control (target ~$5–$10/mo; a prior spike hit ~$20) and is to be left unchanged for now.** (High confidence from config; **confirm via ACA logs** — see §7.)

4. **24h change cannot be computed in `market-data-service` at all.** The price entity (`AssetPrice`), its DTO (`MarketPriceDto`), and the Kafka event (`PriceUpdatedEvent`) carry **only a current price** — no `previousClose`/`change` field anywhere in that path. The Market Data page therefore has no source of truth for change and renders a hardcoded `0`.

5. **Unrealized P&L is `0` by design.** Cost basis is a placeholder set equal to current price (`avgCostBasis = currentPrice`) because there is no trade-ledger. `unrealizedPnL = currentValue − costBasis = 0` for every holding, on both the backend and the frontend.

6. **Analytics is cached without expiry on Azure.** `PortfolioAnalyticsService.getAnalytics` is `@Cacheable`, but `CacheConfig` only defines TTL'd cache managers for `local/default` (Caffeine) and `aws` (Redis). On `prod,azure`, `spring.cache.type: simple` yields a `ConcurrentMapCacheManager` with **no eviction** — so a once-computed analytics payload can persist for the warm life of the container even after underlying data is repaired. (New in rev2.)

7. **Several frontend values are mocked, mis-wired, or currency-naive.** The scrolling top ticker is 100% hardcoded mock data (which is why it shows believable non-zero `%` while the rest of the app shows `0`); the "24h Profit/Loss" card reads from a synthetic object that is always `0`; the allocation donut shows "100% Stocks" because ticker metadata only knows 3 symbols; and the frontend portfolio adapter does **no FX conversion**, so non-USD holdings (INR/JPY/etc.) are summed as if USD.

Net effect: the UI renders a mix of **static seed data**, **truncated client-side prices**, **placeholders**, and **mock decorations**, with polling that re-fetches the same unchanging (and cached) numbers.

---

## 2. How the data is supposed to flow (as designed)

```
Yahoo Finance ──(scheduled hourly/daily fetch)──> market-data-service
     │                                                  │
     │                                          AssetPrice (MongoDB: ticker, currentPrice, updatedAt)
     │                                                  │
     │                                       PriceUpdatedEvent(ticker, newPrice) ──> Kafka "market-prices"
     │                                                  │
        ┌─────────────────────────────────────────────┼───────────────────────────────┐
        ▼                                               ▼                               ▼
 portfolio-service                              insight-service                   (no other consumer)
 MarketPriceProjectionService                  MarketDataService
 → Postgres market_prices (current only)        → Redis latest + 10-pt history
 → Postgres market_price_history (??? NOBODY)   → trendPercent from window
```

The break points are marked below.

---

## 3. Root Causes — Backend

### 3.1 `market_price_history` is never populated at runtime — **(primary cause of `+0.00%` 24h change & synthetic chart)**

The analytics 24h-change and the performance series both read from `market_price_history`:

- `portfolio-service/.../PortfolioAnalyticsService.java` — the `ANALYTICS_SQL` CTE `price_24h` selects from `market_price_history WHERE observed_at <= now() - INTERVAL '24 hours'`, and the `HISTORY` rows also come from `market_price_history`.

But the **only writer** of that table is a one-time Flyway seed:

- `portfolio-service/src/main/resources/db/migration/V2__Seed_Market_Data.sql` — inserts 50 synthetic points each for **only `AAPL`, `TSLA`, `BTC`**, timestamped relative to migration-apply time.

A repo-wide search for `market_price_history` confirms **no INSERT/UPDATE anywhere in `src/main/java`**. The Kafka projection in `portfolio-service` writes only the current-price table:

- `portfolio-service/.../MarketPriceProjectionService.java#upsertLatestPrice` writes `market_prices` (current price), **not** `market_price_history`.

**Consequences:**
- For any holding that is **not** AAPL/TSLA/BTC, `price_24h_ago` is `NULL` → `computeChange24hPercent(...)` returns `BigDecimal.ZERO` (guard: `if (price24hAgo == null ...) return ZERO`).
- **Ticker-symbol mismatch (rev2 correction):** V2 seeds history for `AAPL`, `TSLA`, and legacy `BTC`. The 160-ticker golden-state registry (`config/seed-tickers.json`) uses **`BTC-USD`**, not `BTC`. So only **AAPL and TSLA** overlap with the seeded history; `BTC-USD` has none. The earlier "~157/160 have no history" figure is corrected to **158/160**.
- For the 2 overlapping tickers, the history is frozen at migration time and never advances, so even their "24h change" is a static artifact of seed math, not a live value. (This is why "Worst: TSLA -33.59%" appears — computed from the static seed ramp vs. the seeded current price.)
- The performance series: `buildPerformanceSeries` falls back to `generateSyntheticSeries(...)` only when fewer than 7 distinct history dates exist (`SYNTHETIC_THRESHOLD = 7`). **Correction (rev2):** because AAPL and TSLA each have 50 seeded daily points, a golden-state portfolio containing them will usually have ≥7 distinct dates, so the backend will **not** synthesize. The real defect is subtler: the series sums only the tickers that *have* history rows on each date, so the curve reflects **just AAPL/TSLA** and silently omits the other 158 holdings. The result is a **partial, economically misleading "real" series**, not necessarily a synthetic one. (The frontend can still fall back to its own synthetic curve when analytics is empty — see §4.5.)

**Note on seed states:** conclusions differ slightly by which seed is active. `V3__Seed_Portfolio_Data.sql` creates a legacy demo portfolio with `AAPL`/`TSLA`/`BTC`; the golden-state seeder (`PortfolioSeedService` + `SeedTickerRegistry`) wipes and recreates 160 holdings using `BTC-USD`. The deployed dashboard reflects the golden-state seed (160 of 160 assets).

**Confidence:** High (code-confirmed).

---

### 3.2 The live refresh job does not run under Azure scale-to-zero — **(explains "data never refreshes"; the `min_replicas=0` setting is intentional and stays)**

> **Constraint:** `min_replicas = 0` on `market-data-service` is a deliberate cost control (monthly budget ~$5–$10; a prior configuration pushed cost to ~$20). It is **to be left as-is**. This section explains the data-freshness consequence of that setting; it is **not** a recommendation to change replica counts now. Any future change here requires a separate impact analysis (§8).

`market-data-service/.../MarketDataRefreshJob.java` is the only thing that pulls live prices and publishes updates:

```java
@Scheduled(cron = "${market-data.refresh.cron:0 0 */1 * * *}")
void refreshAllTrackedTickers() { ... externalMarketDataClient.getLatestPrices(...) ... }
```

Two compounding factors in production:

1. **Cadence is daily on Azure (intentional cost control).**
   `market-data-service/src/main/resources/application-azure.yml` overrides the cron to `0 0 8 * * *` (daily 08:00) with an explicit comment that prices may be up to 24h stale. Deliberate.

2. **The container is scaled to zero, so the cron has no process to fire it (intentional setting, side effect on freshness).**
   `infrastructure/terraform/azure/main.tf` provisions `market-data-service` with `min_replicas = 0`. A `@Scheduled` task requires a running JVM; ACA scale-to-zero means that between HTTP requests there is **no replica alive to run the scheduler**. The daily cron will only fire if a replica happens to be awake at exactly 08:00 for an unrelated reason. In practice the scheduled Yahoo fetch is effectively dormant in production. This is an accepted trade-off of the budget setting, not a defect to patch in this round.

Additionally, on Azure the bootstrap seeders are disabled:
- `application-azure.yml`: `market.seed.enabled: false` and `market-data.baseline-seed.enabled: false`.

So on Azure, the current-price data comes **only** from the manual "golden state" seed endpoint (§3.4), and is never refreshed afterward. `AssetPrice.updatedAt` is set at seed time, which is why "Last Updated" is a single static date (e.g. "Jun 6, 2026") across all rows.

**Implication for bug-fixing:** because continuous scheduling is off the table for now, the most durable fixes are ones that make the *existing* seed/hydration data internally consistent (e.g. seeding a real history series and a real previous-close at seed time, §3.1/§3.3), rather than ones that assume a live poller is running. A refresh mechanism that works under scale-to-zero (e.g. an external scheduled trigger) is a later, separately-analyzed decision.

**Kafka consumers are also dormant (rev2).** The same `min_replicas = 0` applies to `portfolio-service` and `insight-service` (`infrastructure/terraform/azure/main.tf`), and no KEDA Kafka/cron scale rule is configured in the container-app module. Their `@KafkaListener` consumers run only inside a live JVM, so "publish event" does **not** imply "projection/Redis updated" under scale-to-zero — consumption happens only while a replica is awake from HTTP traffic. This compounds the freshness gap and is relevant to any future event-driven fix.

**Stale config comments (rev2).** Both `market-data-service/.../application-azure.yml` and `portfolio-service/.../application-azure.yml` contain comments asserting that ACA containers are "long-lived" and that scheduled refresh is "reliable here, unlike Lambda." Under the current Terraform (`min_replicas = 0`) that is **not** true. These comments should be treated as misleading documentation drift, not as evidence the scheduler runs.

**Confidence:** High on config; the scale-to-zero/scheduler interaction should be **confirmed from ACA logs** — look for absence of `MarketDataRefreshJob: starting refresh ...` and presence/absence of `StartupHydrationService: published N ...`.

---

### 3.3 24h change is not representable in the `market-data-service` model — **(cause of Market Data page `+0.00%`)**

The entire market-data write/read path carries only a single scalar price:

- `market-data-service/.../AssetPrice.java` — fields: `ticker`, `currentPrice`, `updatedAt`. No previous close, no change.
- `market-data-service/.../MarketPriceDto.java` — `(ticker, currentPrice, updatedAt)`.
- `common-dto/.../PriceUpdatedEvent.java` — `(ticker, newPrice)` only.

The Market Data page calls `GET /api/market/prices`, which returns `MarketPriceDto` with no change field. The frontend then **cannot** derive a real 24h change for that page (see §4.2). Even if the refresh job ran, the Market Data screen would still show `+0.00%` because change is never carried on this path.

**Confidence:** High (code-confirmed).

---

### 3.4 Unrealized P&L and "all-time return" are structurally `0` — **(cause of `+$0.00` P&L everywhere)**

`portfolio-service/.../PortfolioAnalyticsService.java#getAnalytics`:

```java
// Placeholder: avgCostBasis = currentPrice until trade ledger is available
BigDecimal avgCostBasis = row.currentPrice();
BigDecimal costBasisBase = quantity × avgCostBasis × rate;
BigDecimal unrealizedPnL = currentValueBase.subtract(costBasisBase); // == 0 by construction
```

Because cost basis is defined as equal to the current price, `unrealizedPnL` is mathematically `0` for every holding, and `totalUnrealizedPnLPercent` (used for the "all-time return" line on the Portfolio Total card) is `0`. There is no trade/transaction ledger to derive a real average cost. The DTO even documents this: `HoldingAnalyticsDto.unrealizedPnL` comment says "Always 0 with placeholder."

The frontend mirrors the same placeholder in `frontend/src/lib/api/portfolio.ts#fetchPortfolio` (`unrealizedPnL: 0`, `avgCostBasis: currentPrice`).

**Confidence:** High (code-confirmed). This is a known/intentional placeholder, but it is the direct cause of the `+$0.00` P&L the user is seeing.

---

### 3.5 insight-service trend is `+0.00%` because the Redis window has ≤1 point — **(cause of AI Insights `+0.00%`)**

`insight-service/.../MarketDataService.java` builds `trendPercent` from a sliding window of the last 10 prices in Redis, populated by `InsightEventListener` consuming `PriceUpdatedEvent`. `calculateTrend(...)` returns `null` if fewer than 2 points exist, and the card renders `+0.00%` for a null/zero trend.

Because the only events published on Azure come from `StartupHydrationService` (one event per ticker when market-data-service starts) and the refresh job doesn't run (§3.2), each ticker accumulates **at most one price point** in its Redis window → `trendPercent` is `null` → `+0.00%`. The prices shown on the AI Insights cards are real (last hydrated value) but the change is always zero.

Also note the AI sentiment text is intentionally omitted from the list endpoint (`/api/insights/market-summary`) to avoid Bedrock/OpenAI fan-out; sentiment is only fetched per-ticker. That part is by design.

**Edge cases the trend logic exposes (rev2):**
- **"At most one point" is too strong.** Each market-data-service cold start republishes one *latest* price per ticker. Multiple cold starts within Kafka/Redis retention can push several **identical** prices into `market:history:{ticker}`, giving `priceHistory.size() ≥ 2` with `trendPercent = 0.00%` (not `null`). Same UI symptom, different code path — worth stating both.
- **History is event-count based, not time-based.** The list stores prices with no timestamps; trend is "oldest of last ≤10 events → newest," which is **not** a true 24h window.
- **Stale tickers are pruned only on write.** `processUpdate(...)` removes ZSET entries older than `STALE_TICKER_TTL` (24h), but `getMarketSummary()` does **not** filter by score or prune on read. If writes stop entirely (the scale-to-zero reality), stale tickers can remain visible indefinitely — the 24h TTL is not passively enforced.

**Confidence:** High on the trend logic; **confirm Redis window contents** via logs/CLI (`LRANGE market:history:AAPL 0 -1`, `ZRANGE market:tracked-tickers 0 -1`) to determine whether windows hold one point or several identical points.

---

### 3.6 Azure analytics cache has no TTL — **(can keep analytics stale even after data is repaired)**

`PortfolioAnalyticsService.getAnalytics` is annotated `@Cacheable(value = "portfolio-analytics", key = "#userId")`. But `portfolio-service/.../CacheConfig.java` only registers TTL'd cache managers for two profile sets:
- `@Profile({"local","default"})` → Caffeine, 30s `expireAfterWrite`.
- `@Profile("aws")` → Redis, 30s `entryTtl`.

There is **no `azure`-profile cache-manager bean.** On `SPRING_PROFILES_ACTIVE=prod,azure`, neither bean activates, and `portfolio-service/.../application-azure.yml` sets `spring.cache.type: simple`. Spring Boot then autoconfigures a `ConcurrentMapCacheManager`, which is a plain `ConcurrentHashMap` with **no eviction/TTL**. (The Azure YAML comment scopes `simple` to "FX rate caching," but the same default manager also backs the `portfolio-analytics` cache.)

**Consequence:** once analytics is computed for a user, it can persist for the **entire warm life of the container**. Even after history/prices are repaired, the analytics endpoint may keep returning the stale payload until the container is restarted or scaled to zero (which, given scale-to-zero, does happen between traffic bursts — partially masking the issue, but unreliably). This is an independent staleness vector from the market-data scheduler.

**Confidence:** High (code-confirmed). Worth a quick check that no other `azure` cache-manager bean exists elsewhere on the classpath.

---

## 4. Root Causes — Frontend

### 4.1 The top scrolling ticker is hardcoded mock data — **(why it alone shows believable non-zero %)**

`frontend/src/components/layout/PortfolioTicker.tsx` renders a constant `MOCK_TICKER` array (`AAPL +0.83`, `BTC -2.14`, `S&P 500 +0.42`, …). It is not wired to any API. This is misleading because it makes the app look "live" while every real data surface shows `0`. It also explains the screenshot mismatch (ticker shows `AAPL $189.72` while the AI Insights card shows `AAPL $197.34`).

### 4.2 Market Data page derives change from a hardcoded `0`

`frontend/src/components/market/MarketDataPageContent.tsx` uses `usePortfolio()` (not `useMarketSummary()`), and `fetchPortfolio` in `frontend/src/lib/api/portfolio.ts` sets every holding's `change24hPercent: 0` and `change24hAbsolute: 0` with a `TODO`. So the Market Data table's "24h Change" column is always `+0.00% (+$0.00)`, independent of backend state. It also reads `lastUpdatedAt` from the market price row's `updatedAt`, which is the static seed timestamp.

### 4.3 "24h Profit/Loss" card is wired to the synthetic summary, not analytics

`frontend/src/components/portfolio/SummaryCards.tsx`:
- The "24h Profit / Loss" card uses `summary.change24hAbsolute` / `summary.change24hPercent`, where `summary` comes from `portfolio?.summary` — i.e. the `fetchPortfolio` object that **hardcodes both to `0`**. The richer `usePortfolioAnalytics()` result (which at least computes best/worst) is **not** used for this card. So 24h P&L is always `+$0.00 / +0.00%`.
- "all-time return" uses `analytics.totalUnrealizedPnLPercent`, which is `0` per §3.4.
- Best/Worst performer *do* come from analytics, which is why "Worst: TSLA -33.59%" shows a real (but static-seed-derived) number while most others are `0`.

### 4.4 Allocation donut shows "100% Stocks" because ticker metadata only knows 3 symbols

`frontend/src/lib/api/portfolio.ts` defines `TICKER_META` for only `AAPL`, `TSLA`, `BTC`. `getTickerMeta` defaults everything else to `{ name: ticker, assetClass: "STOCK" }`. With the 160-ticker golden-state seed (which includes crypto and FX), every unmapped ticker is bucketed as `STOCK`, so `buildAllocationDtoFromPortfolio` produces a single 100% "Stocks" slice. The backend `quoteCurrency`/asset-class data is not surfaced to the allocation builder.

### 4.5 Performance chart prefers backend analytics, but the backend series is itself partial — **(rev2 correction)**

**Correction:** the earlier claim that the chart "uses the synthetic frontend generator" is inaccurate for the current code. `frontend/src/components/charts/PerformanceChart.tsx` does:

```ts
const dataPoints = analytics?.performanceSeries ?? performance?.dataPoints ?? [];
```

So it **prefers the backend analytics `performanceSeries`** and only falls back to the frontend synthetic series (`usePortfolioPerformance` → `buildPerformanceSeries`) when analytics is missing/empty. The accurate finding is therefore:
- If backend analytics is present, the chart shows the **backend** series — which per §3.1 is **partial** (reflects only AAPL/TSLA history, omitting 158 holdings), so the "7-day return" headline is economically misleading even though it is "real" code-wise.
- If analytics is unavailable/empty, the chart falls back to the **frontend synthetic curve** (`buildPerformanceSeries` in `portfolio.ts`, sine-wave + drift from `totalValue`).

Both layers can still produce a non-representative chart, but for different reasons than originally stated.

### 4.6 Polling is configured, but re-fetches identical static data

`frontend/src/lib/hooks/usePortfolio.ts` and `useInsights.ts` set `staleTime: 30_000` and `refetchInterval: 60_000`. So the client *does* poll every 60s — but because the backing data never changes (§3.1–3.2), every poll returns the same numbers. The "doesn't refresh at all" symptom is upstream of the frontend; the polling itself is healthy.

### 4.7 The 25-ticker price-API cap truncates the portfolio — **(major, new in rev2)**

`market-data-service/.../MarketPriceController.java` caps the filtered price query:

```java
static final int MAX_TICKERS_PER_REQUEST = 25;   // filtered path
static final int MAX_UNFILTERED_RESULTS  = 100;   // no-filter path
```

The frontend (`frontend/src/lib/api/portfolio.ts#loadMarketPrices`) sends **all** holding tickers in one request: `?tickers=<up to 160 symbols joined>`. The controller silently keeps only the first 25 (`.limit(MAX_TICKERS_PER_REQUEST)`) and logs a warning server-side; the client receives prices for ≤25 tickers. `fetchPortfolio` then assigns `currentPrice = 0` (and `totalValue = 0`) to every holding whose price is missing.

**Observable evidence:** in the supplied screenshots, the **Asset Allocation card total is $242,489.41** while the **Portfolio Total card is $1,545,969.73**. The allocation/holdings figures are computed client-side from the truncated price set, whereas the Portfolio Total comes from `GET /api/portfolio/summary` (a complete server-side SQL join over all 160 `market_prices` rows). The ~6× gap is the signature of the cap biting in production.

**Downstream impact:** understated client-side totals, distorted allocation donut, many `$0` holdings/market rows (likely sorted to the bottom so they're not obvious at a glance), and misleading `lastUpdatedAt` (see §4.8). Note the no-filter path is also capped at 100, so even an unfiltered fetch could not cover 160 tickers.

**Confidence:** High (code-confirmed + screenshot-consistent).

### 4.8 Frontend portfolio adapter does no FX conversion — **(new in rev2)**

`fetchPortfolio` computes `totalValue = quantity × currentPrice` in each asset's **quote currency** and hardcodes `currency: "USD"` on the response, with no FX step. The golden-state registry includes non-USD assets (NSE tickers in INR, plus JPY/CAD/etc. FX pairs), so the client-side summary fallback, holdings values, and allocation slices **mix currencies while labelling the total as USD**. The backend (`PortfolioService.getSummary` and `PortfolioAnalyticsService`) *does* FX-convert via `FxRateProvider`; the frontend adapter does not. This distorts any value that is computed client-side rather than read from the backend summary/analytics.

Minor related hardcodings worth cataloguing: `fetchPortfolio` also hardcodes `name: "Main Portfolio"`, and sets `lastUpdatedAt: new Date().toISOString()` when a price is **missing** — making absent/truncated price data appear freshly updated (interacts with §4.7).

**Confidence:** High (code-confirmed).

---

## 5. Symptom → Root-Cause Map

| Observed symptom | Primary cause(s) |
|---|---|
| Market Data page: every ticker `+0.00% (+$0.00)` | §3.3 (no change field on market path) + §4.2 (frontend hardcodes 0) |
| Holdings table: every row `+$0.00` Unr. P&L | §3.4 (cost basis = current price placeholder) |
| Holdings table: every row `0.00%` 24h change | §3.1 (no runtime history for these tickers) |
| "24h Profit / Loss" card always `+$0.00 / +0.00%` | §4.3 (card reads synthetic summary, not analytics) |
| "all-time return" `+0.00%` | §3.4 |
| Best `AAVE-USD +0.00%`, Worst `TSLA -33.59%` | §3.1 (only seeded tickers have history; others `0`); analytics best/worst works partially |
| Performance chart "+8.58% 7-day" curve | §3.1 + §4.5 (synthetic series, fabricated) |
| Allocation donut "100% Stocks" | §4.4 (TICKER_META only knows 3 symbols) |
| AI Insights cards: all `+0.00%` | §3.5 (Redis window has ≤1 point per ticker) |
| "Last Updated" a single static date | §3.2 (`updatedAt` set at seed time; refresh dormant under intentional scale-to-zero) |
| Top ticker shows lively non-zero % | §4.1 (hardcoded mock, not real data) |
| Nothing ever changes on reload | §3.2 (refresh job dormant under intentional scale-to-zero) — polling works but data is frozen |
| Asset Allocation total ($242K) ≪ Portfolio Total ($1.5M) | §4.7 (25-ticker price cap; client prices only ~25 holdings, backend sums all 160) |
| Many holdings show `$0` value / understated client totals | §4.7 (truncated price set → `currentPrice = 0`) |
| Allocation donut numerically distorted even after asset-class fix | §4.8 (client-side math mixes quote currencies, no FX) |
| Performance chart reflects only AAPL/TSLA, not whole portfolio | §3.1 (history rows exist for 2 tickers only) + §4.5 (chart prefers that partial backend series) |
| Analytics unchanged after data repairs while container warm | §3.6 (Azure `spring.cache.type=simple`, no-TTL `@Cacheable`) |
| AI Insights still lists tickers after 24h silence | §3.5 (ZSET TTL pruned on write only, not on read) |
| AI trend `0.00%` despite multiple Redis points | §3.5 (duplicate same-price hydration events) |
| Missing-price rows show a fresh "Last Updated" | §4.8 (`lastUpdatedAt` defaults to now() when price absent) |

---

## 6. Intentional vs. Unintentional Staleness

To respect the "some staleness is deliberate for cost" note, here's the split:

**Deliberate / acceptable (cost control) — leave as-is:**
- **`min_replicas = 0` on `market-data-service` (scale-to-zero).** Confirmed intentional budget control (~$5–$10/mo target). Its side effect is that the scheduled refresh is dormant in production (§3.2). **Not to be changed in this bug-fix round.** A revisit requires a separate impact analysis.
- Daily refresh cadence on Azure (`application-azure.yml` cron `0 0 8 * * *`).
- Serving last-known prices from the DB rather than live-fetching on each request.
- AI sentiment omitted from the bulk market-summary endpoint.
- Disabling the bootstrap seeders on Azure in favor of a manual golden-state seed.

**Unintentional (these are the actual bugs to fix):**
- `market_price_history` has no runtime writer → 24h change and real performance history are impossible for all but 2 overlapping seed tickers (AAPL/TSLA). (§3.1) **— top priority; fixable without touching infra.**
- **25-ticker price-API cap vs. frontend sending all 160** → truncated client prices, understated allocation/holdings totals (the visible $242K vs $1.5M gap). (§4.7) **— major; fixable without touching infra.**
- No `previousClose`/change on the market-data path → Market Data page can never show change. (§3.3)
- **Azure `@Cacheable` analytics has no TTL** (`spring.cache.type: simple`, no azure cache-manager bean) → analytics can stay stale while the container is warm. (§3.6)
- **Frontend portfolio adapter does no FX conversion** → non-USD holdings mis-summed as USD in client-side totals/allocation. (§4.8)
- Frontend mocks/mis-wiring: hardcoded ticker tape, 24h P&L card bound to the always-zero synthetic summary, allocation limited to 3 known symbols. (§4.1–4.4)
- Backend performance series is **partial** (only tickers with history) rather than synthetic — a misleading "real" curve. (§3.1, §4.5)
- Insight Redis edge cases: duplicate-hydration zero-trend, and stale ZSET tickers not pruned on read. (§3.5)
- Unrealized P&L placeholder presented as a real value with no "not available" affordance. (§3.4)
- Kafka consumers dormant under scale-to-zero (a *consequence* of the intentional setting, but relevant to any event-driven fix). (§3.2)

> Note: the "prices never update after seed" symptom is a *consequence* of the deliberate scale-to-zero setting, so it is classified as accepted-for-now rather than a bug. The fixes above are scoped to work **within** that constraint — i.e. make the seeded/hydrated dataset internally consistent and correctly calculated/transported/cached, rather than relying on a continuously-running poller.

---

## 7. Recommended Verification (no changes yet)

To confirm the high-confidence inferences before any fix:

1. **ACA logs for `market-data-service`** — confirm whether `MarketDataRefreshJob: starting refresh for N ticker(s)` ever appears. Expectation: it does not (scale-to-zero). Also check `StartupHydrationService: published N ...` count.
2. **MongoDB `market_prices`** — inspect `updatedAt` across tickers. Expectation: all equal to the golden-state seed time.
3. **Postgres `market_price_history`** — `SELECT ticker, count(*), min(observed_at), max(observed_at) FROM market_price_history GROUP BY ticker;`. Expectation: only AAPL/TSLA/BTC, with `max(observed_at)` near the migration date and nothing recent.
4. **Postgres `market_prices`** — confirm 160 rows from the golden-state seed, with `updated_at` static.
5. **Redis (Upstash)** — `ZRANGE market:tracked-tickers 0 -1` and `LRANGE market:history:AAPL 0 -1`. Expectation: history lists have length 1 → null trend.
6. **`event_publication` table (outbox)** — check for undispatched rows, in case Kafka delivery to Aiven is failing (would also starve insight-service Redis).
7. **Reproduce the 25-ticker cap (§4.7)** — call `GET /api/market/prices?tickers=<all 160>` and confirm ≤25 rows return; compare the client-computed allocation total against `GET /api/portfolio/summary` (expect the $242K-vs-$1.5M-style gap).
8. **Confirm the Azure no-TTL cache (§3.6)** — verify no `azure`-profile `CacheManager` bean exists and that `spring.cache.type=simple` is active on `prod,azure`; check whether a repaired analytics value persists across repeated calls on a warm container.

---

## 8. Remediation Themes (for a follow-up change, not done here)

Grouped by the gap they close. **All items below are scoped to work within the current `min_replicas = 0` budget constraint** — none of them require changing replica counts or other infra. The infra option is listed separately at the end as explicitly deferred.

**In-scope now (application/data/frontend only):**

1. **Persist price history at seed/hydration time so calculations are real.** Seed `market_price_history` for all 160 golden-state tickers (not just 3, and align the symbol `BTC` → `BTC-USD`), and/or have the `portfolio-service` Kafka projection append each received `PriceUpdatedEvent` to `market_price_history`. This makes 24h change and the performance series real for all tickers using data that already exists, with no dependence on a live poller. **Highest impact, no infra change.**
2. **Remove/raise the 25-ticker price cap mismatch (§4.7).** Either batch the frontend request into ≤25-ticker chunks, or raise/remove the server cap for the internal call, so all 160 holdings receive prices. This directly fixes the $242K-vs-$1.5M allocation gap, the `$0` holdings, and the distorted donut. **Major impact, no infra change.**
3. **Carry change on the market-data path.** Add `previousClose`/`change`/`changePercent` to `AssetPrice`, `MarketPriceDto`, and (if needed) `PriceUpdatedEvent`, populated at seed time (and by Yahoo's `regularMarketPreviousClose` whenever a refresh does occur). Then wire the Market Data page to real change instead of hardcoded `0`.
4. **Give the Azure analytics cache a TTL (§3.6).** Add an `azure`-profile cache-manager bean (or point `@Cacheable` at a TTL'd manager) so analytics expires like it does on `local`/`aws`. Otherwise data repairs won't surface until container restart.
5. **FX-normalize client-side math or stop computing it client-side (§4.8).** Either apply FX in the frontend adapter or have the frontend rely solely on backend summary/analytics (which already FX-convert) for totals and allocation.
6. **Introduce a cost-basis source.** A minimal trade-ledger (or a per-holding `avgCostBasis` captured at add-time / at seed time) so unrealized P&L is non-trivial. Until then, the UI should label P&L as "—/Not available" rather than `+$0.00`.
7. **Fix frontend wiring/mocks.** Replace the `PortfolioTicker` mock with live data (or label it clearly as illustrative), bind the "24h Profit/Loss" card to the analytics endpoint, expand/replace `TICKER_META` with backend-provided asset class so allocation is correct, and stop defaulting `lastUpdatedAt` to now() for missing prices.
8. **Ensure insight-service window fills and prunes correctly.** Have `StartupHydrationService` republish enough recent history per ticker (not just a single latest point) so the Redis sliding window holds ≥2 *distinct* points, and prune/filter stale ZSET entries on read as well as write — no continuous poller required.

**Deferred — requires impact analysis before any action (per owner's instruction):**

9. **A refresh mechanism that works under scale-to-zero.** Options such as an external scheduled trigger (ACA Job, or an uptime pinger hitting a refresh endpoint), or KEDA cron/Kafka scale rules to wake consumers, would restore genuine intraday freshness without setting `min_replicas ≥ 1`. **Do not implement until the major bugs above are done and a cost/impact analysis is approved.** Leave `min_replicas = 0` untouched in the meantime.

---

*End of audit. No source files were modified.*
