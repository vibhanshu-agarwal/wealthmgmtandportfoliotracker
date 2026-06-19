# Azure Live Market-Data Feed Investigation

**Date:** 2026-06-19
**Scope:** Read-only verification of the Azure-active production stack (`wealth-azure-prod-rg`) and the portfolio Postgres read model (Neon `wealthmgmt-portfolio-db`) to identify the root cause of the dashboard regression where prices appear stale ("1 day ago") and 24h change renders as "—" / "+0.00%" for every holding.
**Status:** Findings only. This document is the foundation for a bugfix spec; it is not itself a spec, design, or task plan.
**Mutating actions performed:** None. Only `SELECT` queries against Neon and `az` / `az monitor log-analytics` reads against Azure.

> **Revision history**
> - **v2 (2026-06-19, post-audit):** Corrections #4–#6 from the Codex/Azure-diagnostics audit folded in — see "Corrections from independent audit" at the end of this doc. The headline diagnosis (cron dead, hydration no-op, seed is the only publisher) is unchanged, but the *mechanism* of day-over-day variance and the framing of the Jun 18 07:50/07:51 rows are corrected.

---

## TL;DR

The dashboard regression on Azure has nothing to do with the Spring Boot 4.1 / Jackson 2→3 / SASL jlink work in PR #74. The Azure-active stack runs the full mariner OpenJDK image (`Dockerfile.azure`), where the relevant SASL module has always been present.

The actual root cause is a **broken live data feed**, with three independent layers:

1. **`MarketDataRefreshJob` has not fired in 30 days.** The only code path that fetches live prices from yfinance never runs in production. Confirmed: 0 hits over 30 days for `MarketDataRefreshJob` log substrings in Log Analytics.
2. **The only thing publishing prices is the demo seeder.** `MarketDataSeedService` is being called on `/api/internal/market-data/seed` (by `deploy-azure.yml`, `synthetic-monitoring.yml`, or manual triggers) for the demo userId `00000000-0000-0000-0000-000000000e2e`. It produces **day-deterministic** prices via `DeterministicPriceCalculator.compute(basePrice, ticker, userId)` keyed on `LocalDate.now()`.
3. **`StartupHydrationService` cold-start republishes are silent no-ops.** Hydration uses the *stored* `AssetPrice.getUpdatedAt()` as `observedAt`, so the consumer's `ON CONFLICT (ticker, observed_at) DO NOTHING` guard discards them. Hydrating from a stale Mongo document never advances the read model.

The dashboard's "—" / "+0.00%" rendering is therefore the `dashboard-data-accuracy` "honest-empty" semantics correctly reporting that there is no real movement to display — the feed itself has no movement, and gaps between sporadic seed calls leave the analytics 18–36h reference window empty.

---

## Verifications run

### Q1 — Is the `@Scheduled` refresh cron firing? **No.**

**Code reference:** `market-data-service/src/main/java/com/wealth/market/MarketDataRefreshJob.java`

```java
@Scheduled(cron = "${market-data.refresh.cron:0 0 */1 * * *}")
void refreshAllTrackedTickers() {
    MDC.put("marketDataRefreshJobId", UUID.randomUUID().toString());
    ...
    log.info("MarketDataRefreshJob: starting refresh for {} ticker(s)", tickers.size());
```

**Production cron override (`market-data-service/src/main/resources/application-azure.yml`):** `market-data.refresh.cron: "0 0 8 * * *"` — daily at 08:00 UTC.

**Log Analytics query (workspace `83a9c3a2-…`, last 30 days):**

```kql
ContainerAppConsoleLogs_CL
| where ContainerAppName_s == 'market-data-service'
| where Log_s has_any ('MarketDataRefreshJob','starting refresh for','marketDataRefreshJobId')
| where TimeGenerated > ago(30d)
| summarize hits = count(), firstSeen = min(TimeGenerated), lastSeen = max(TimeGenerated)
```

**Result:** `hits = 0, firstSeen = None, lastSeen = None`.

**Mechanism (`az containerapp list -g wealth-azure-prod-rg`):** market-data-service runs `minReplicas: 0`, max 3. Spring `@Scheduled` only fires while the JVM is alive. Scale-to-zero between 08:00 UTC ticks means the cron never reaches a running JVM — confirmed by the absence of any scheduled-job logs.

**Conclusion:** the cron-driven feed is dead. The `ExternalMarketDataClient.getLatestPrices(...)` call to yfinance — the single code path that introduces real market movement into the system — has not executed on Azure in at least 30 days.

### Q2 — Is cold-start hydration a silent no-op?

**Code reference:** `market-data-service/src/main/java/com/wealth/market/StartupHydrationService.java`

```java
// Publish enriched event — include quoteCurrency and any reference from the stored doc.
// observedAt uses the stored updatedAt (real observation time), never fabricated receive time.
var event = new PriceUpdatedEvent(
        asset.getTicker(),
        price,
        asset.getQuoteCurrency(),
        asset.getUpdatedAt(),                  // <-- stored, not Instant.now()
        asset.getPreviousReferencePrice(),
        asset.getPreviousReferenceAt());
kafkaTemplate.send(TOPIC, asset.getTicker(), event);
```

**Consumer guard (`portfolio-service/src/main/java/com/wealth/portfolio/MarketPriceProjectionService.java`):**

```sql
INSERT INTO market_price_history (ticker, quote_currency, price, observed_at)
VALUES (?, ?, ?, ?)
ON CONFLICT (ticker, observed_at) DO NOTHING
```

**And the upsert guard:**

```sql
... ON CONFLICT (ticker) DO UPDATE
SET current_price  = EXCLUDED.current_price,
    quote_currency = COALESCE(EXCLUDED.quote_currency, market_prices.quote_currency),
    updated_at     = EXCLUDED.updated_at
WHERE market_prices.current_price IS DISTINCT FROM EXCLUDED.current_price
   OR market_prices.quote_currency IS DISTINCT FROM EXCLUDED.quote_currency
```

**Hydration log entries (last 14 days, 40 occurrences):** scattered across irregular hours. Most recent: `2026-06-19T08:48:45Z`. Earlier examples include `2026-06-18T04:13`, `06:16`, `13:07`. None align with 08:00 UTC.

**Cross-check against `market_price_history` (Neon):** there are no rows landing within ±5 minutes of any of the recent hydration timestamps that don't *also* coincide with a `MarketDataSeedService: Golden-state market-data seed complete` log line.

**Mechanism, end to end:**
- Cron is dead → MongoDB `AssetPrice.updatedAt` is only ever advanced by external seed calls.
- Hydration on cold start sends events with `observedAt = AssetPrice.updatedAt` (stored).
- Consumer's `ON CONFLICT (ticker, observed_at) DO NOTHING` discards every event whose `(ticker, observed_at)` pair already exists.
- Therefore: hydration moves nothing in the read model. It cannot self-heal a stale window.

**Conclusion:** confirmed. Hydration is mechanically incapable of advancing the read model under the current data state.

### Q3 — Has the analytics 18–36h reference window been empty as a steady state?

**Analytics query (`portfolio-service/src/main/java/com/wealth/portfolio/PortfolioAnalyticsService.java`):**

```sql
WHERE mph.observed_at BETWEEN now() - INTERVAL '36 hours'
                          AND now() - INTERVAL '18 hours'
```

**Rolling check (Neon, every 6 hours over the last 7 days):**

| `check_time` (UTC)     | `rows_in_window` | `tickers_in_window` |
|------------------------|-----------------:|--------------------:|
| 2026-06-12 09:00       | 159              | 159                 |
| 2026-06-12 15:00       | 638              | 160                 |
| 2026-06-12 21:00       | 638              | 160                 |
| 2026-06-13 03:00       | 479              | 160                 |
| 2026-06-13 09:00       | 640              | 160                 |
| 2026-06-13 15:00       | 1119             | 160                 |
| 2026-06-13 21:00       | 1119             | 160                 |
| 2026-06-14 03:00       | 479              | 160                 |
| 2026-06-14 09:00       | 799              | 160                 |
| 2026-06-14 15:00       | 1117             | 160                 |
| 2026-06-14 21:00       | 1117             | 160                 |
| 2026-06-15 03:00       | 318              | 159                 |
| 2026-06-15 09:00       | 638              | 160                 |
| 2026-06-15 15:00       | 958              | 160                 |
| 2026-06-15 21:00       | 958              | 160                 |
| 2026-06-16 03:00       | 320              | 160                 |
| 2026-06-16 09:00       | 159              | 159                 |
| 2026-06-16 15:00       | 638              | 160                 |
| 2026-06-16 21:00       | 638              | 160                 |
| 2026-06-17 03:00       | 479              | 160                 |
| **2026-06-17 09:00**   | **0**            | **0**               |
| 2026-06-17 15:00       | 159              | 159                 |
| 2026-06-17 21:00       | 159              | 159                 |
| 2026-06-18 03:00       | 159              | 159                 |
| **2026-06-18 09:00**   | **0**            | **0**               |
| **2026-06-18 15:00**   | **0**            | **0**               |
| **2026-06-18 21:00**   | **0**            | **0**               |
| 2026-06-19 03:00       | 320              | 160                 |
| 2026-06-19 09:00       | 320              | 160                 |

**Conclusion:** the window was completely empty for at least four 6-hour buckets in the last week (Jun 17 09:00 plus a continuous Jun 18 09:00–21:00 UTC stretch of ≥18 hours). Anyone hitting the dashboard during those windows would correctly see "—" or "+0.00%", because:
- No row in `[now-36h, now-18h]` → no `WITHIN_24H_WINDOW` reference.
- The analytics CTE falls back to `SINCE_PREVIOUS_SNAPSHOT` (rows older than 36h). When that snapshot reference is a prior seed's **current**-price observation, it is byte-identical to today's `current_price` because `DeterministicPriceCalculator.compute(...)` is **day-invariant** (see "What's actually publishing on Azure" below) → `change = 0.0000%`.

The user's screenshots most likely correspond to one of these stretches.

---

## What's actually publishing on Azure

Cross-referencing seed-service log lines with `market_price_history` row timestamps over the last 7 days:

```
2026-06-12T18:14:19Z  Golden-state ... eventsPublished=320
2026-06-13T14:16:55Z  Golden-state ... eventsPublished=320
2026-06-13T15:22:53Z  Golden-state ... eventsPublished=320
2026-06-13T16:41:36Z  Golden-state ... eventsPublished=320
2026-06-14T10:14:44Z  Golden-state ... eventsPublished=320
2026-06-14T13:50:50Z  Golden-state ... eventsPublished=320
2026-06-15T11:19:40Z  Golden-state ... eventsPublished=320
2026-06-15T19:56:38Z  Golden-state ... eventsPublished=320
2026-06-16T18:18:57Z  Golden-state ... eventsPublished=320
2026-06-19T08:51:03Z  Golden-state ... eventsPublished=320
```

These map directly to history-row minute buckets. The userId on every entry is `00000000-0000-0000-0000-000000000e2e` — the demo E2E user — which matches `E2E_TEST_USER_ID` in `.github/workflows/deploy-azure.yml` and `.github/workflows/synthetic-monitoring.yml`.

**Implication:** in production right now, the only source of price events is `MarketDataSeedService` driven by external triggers (deploy / synthetic / manual). Per `MarketDataSeedService.seed()`, each call publishes **two** events per ticker — which is why the logs show `eventsPublished=320` for 160 tickers:

1. a synthetic **history** observation at `now − 25h` (`historyObservedAt = now.minus(25, HOURS)`), priced by `DeterministicPriceCalculator.computeHistory(seededPrice, ticker, userId)` — keyed on `ticker:userId:LocalDate.now()`, a designed ±0.10–3.00% delta from current; and
2. a **current** observation at `now`, priced by `DeterministicPriceCalculator.compute(basePrice, ticker, userId)`.

Crucial correction (per the IntelliJ doc audit, verified against `DeterministicPriceCalculator.java`): `compute(...)` is **day-invariant** — keyed only on `(ticker, userId)`, with **no date component** — so a ticker's seeded *current* price does not change across seed runs or across days. Only `computeHistory(...)` carries `LocalDate.now()`. The earlier text here ("`compute` keyed on `LocalDate.now()`; identical prices within a day; variance only across day boundaries") was wrong on the mechanism and is corrected above.

**Concrete demonstration (sampled at Jun 19 09:35 UTC):**

| ticker        | current_price | 24h-ago ref (Jun 18 07:51) | pct_change |
|---------------|---------------|-----------------------------|-----------:|
| AAPL          | 197.3396      | 196.1821                    | +0.5900    |
| BTC-USD       | 68106.32      | 66725.1102                  | +2.0700    |
| ETH-USD       | 3545.856      | 3563.6744                   | -0.5000    |
| EURUSD=X      | 1.0956        | 1.0811                      | +1.3412    |
| META          | 525.8579      | 517.5767                    | +1.6000    |
| MSFT          | 422.0649      | 417.0190                    | +1.2100    |
| **NVDA**      | 927.5128      | 927.5128                    | **0.0000** |
| RELIANCE.NS   | 2851.8603     | 2776.6141                   | +2.7100    |
| TCS.NS        | 4063.3650     | 3962.7121                   | +2.5400    |
| **TSLA**      | 179.8892      | 179.8892                    | **0.0000** |

NVDA and TSLA show exactly `0.0000%` because the deterministic seed produced byte-identical prices for them across Jun 18 → Jun 19. This is consistent with `DeterministicPriceCalculator` having low-variance day-over-day output for some inputs.

---

## Open questions and unverified claims

I want to be explicit about what is NOT yet proven:

1. **The Jun 18 07:50:52 / 07:51:01 history rows have no matching log entry** in any container in that 30-minute window. The most likely explanation is a Log Analytics ingestion gap (logs were generated but not ingested), but this is unconfirmed. The rows themselves are present and price-identical, consistent with a seed call.
2. **The frontend rendering rule for "—" vs "+0.00%" was not inspected.** The analytics SQL falls back to `SINCE_PREVIOUS_SNAPSHOT` and emits a real change; whether the frontend displays that fallback as a value, "—", or "+0.00%" is a separate question that should be checked when scoping the fix.
3. **Why the May-2026 cost-spike fix throttled the cron to daily** is documented (`docs/changes/CHANGES_AZURE_COST_SPIKE_FIX_2026-05-17.md`), but the assumption that hourly was the cost driver may need re-examination if the alternative is "no live data at all." The `cron + minReplicas: 0` combination was not flagged at the time.
4. **No deploy or synthetic-monitoring workflow runs were inspected** to attribute each seed call to its trigger. The seed log entries above include both deploy- and synthetic-driven calls; distinguishing them was not necessary to reach the diagnosis.

---

## Implications

### For PR #74

PR #74 hardens the AWS-standby slim jlink path with `java.security.sasl`. It is correct in scope and value as **standby hardening + a regression guard**. It is **not** the fix for this incident. The diagnosis the PR description was originally based on (H1 — missing `java.security.sasl` on the production runtime) does not apply to the Azure-active runtime, which uses the full mariner OpenJDK image where the module has always been present.

### What the H4 bugfix spec must address

The bugfix spec built on this investigation needs to handle three interacting layers, not just the cron:

1. **Restore a live data feed on Azure.** Options likely include: an Azure Container Apps Job triggered at 08:00 UTC that runs the refresh and exits (purpose-built for scheduled scale-from-zero); `minReplicas: 1` on market-data (cost concern from May 2026 reapplies); or moving the refresh out of the long-running container entirely. The choice should account for the May cost-spike constraint.
2. **Decide the role of `MarketDataSeedService` in production.** Today it is masquerading as a refresh path because the real refresh is dead. Either it should be explicitly demoted to "demo seed only, never on schedule" and the live refresh restored, or its determinism should be made cross-day-distinguishing if it is to remain the primary publish path on Azure.
3. **Make `StartupHydrationService` either correct or harmless.** As implemented it is mechanically a no-op once MongoDB is stale. Options: drop the Kafka republish and treat hydration as in-memory cache warming only (recommended), or stamp `observedAt = Instant.now()` (changes contract semantics — needs more thought).

The "—" / "+0.00%" rendering is not itself a bug — it is the `dashboard-data-accuracy` honest-empty semantics correctly reporting absence of movement. Once a real feed is restored, those symptoms will resolve without changes to the analytics SQL or frontend.

---

## Reproducibility

All queries and commands above were executed on `2026-06-19` from the workspace root with read-only credentials.

- Azure CLI: `azure-cli 2.85.0`, subscription `Azure subscription 1` (`ee625b3f-7cb1-4482-be3c-4363c5d76d23`), user `vibhanshu.agarwal@outlook.com`.
- Log Analytics workspace customer ID: `83a9c3a2-659e-40ca-9784-1ff871b9d5ea`.
- Neon project: `wealthmgmt-portfolio-db` (`fancy-flower-98795390`), default branch.
- All `az` invocations used `--query` projections and `-o jsonc`; no resource-modifying verbs (`create`, `update`, `delete`, `restart`) were used.
- All Neon SQL was `SELECT`-only.
