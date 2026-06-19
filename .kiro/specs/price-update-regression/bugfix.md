# Bugfix Requirements Document

## Introduction

On the deployed dashboard, live price-derived values stopped updating. Across the Overview,
Portfolio, Market Data, and AI Insights pages, every 24h change renders as "—" or
"+0.00% / +0.00", all-time return renders as 0.00%, 24h Profit/Loss renders as "+$0.00", and the
Market Data "Last Updated" age shows "1 day ago" (or older) for every ticker. Prices themselves
are still shown but are stale and never refresh.

This is a regression. The identical "0.00% / —" symptom was fixed by the dashboard-data-accuracy
work (Waves 4–6, 2026-06-10), which made 24h change, P&L, and freshness honest: real values when a
recent reference exists, and an explicit "—" only when one genuinely does not. The symptom
reappeared after the Spring Boot 4.0.5 → 4.1.0 / Spring AI 2.0 GA migration landed on 2026-06-17,
whose major breaking change was Jackson 2 → Jackson 3 (`com.fasterxml.jackson.*` → `tools.jackson.*`)
across the Kafka `PriceUpdatedEvent` producer (market-data-service) and consumers
(portfolio-service, insight-service), along with DLT/error-handling changes.

The visible "0.00% / —" is the downstream-correct rendering: the analytics layer honestly reports
change/P&L/trend as unavailable because no fresh price reference exists within its recent-history
window. The underlying defect is that fresh price observations stopped propagating end-to-end after
the migration, so the portfolio read model and history stop receiving new data points and every
recent-window lookup comes back empty.

The regression boundary is observable: price-derived values worked on builds before 2026-06-17 and
broke on builds at or after it. The fix must restore end-to-end propagation of price updates so that
fresh references exist again, while preserving the "no silent zero" guarantees, the malformed-event
DLT handling, and the backward-compatible event contract introduced by the prior work.

## Bug Analysis

### Current Behavior (Defect)

What currently happens on the deployed system (builds at or after the 2026-06-17 migration):

1.1 WHEN a user opens the Overview or Portfolio page THEN the system displays every holding's 24h
change as "—" or "+0.00% / +0.00" and the 24h Profit/Loss summary as "+$0.00".

1.2 WHEN a user opens the Overview page AND holdings have a real cost basis THEN the system displays
all-time return as 0.00% for every holding.

1.3 WHEN a user opens the Market Data page THEN the system displays "Last Updated" as "1 day ago"
(or older) for every ticker, and the listed prices do not refresh as time passes.

1.4 WHEN a user opens the AI Insights page THEN the system displays every ticker's trend / percentage
change as +0.00%.

1.5 WHEN market-data-service publishes a price update and portfolio-service / insight-service consume
it THEN the fresh observation does not reach the portfolio read model or the insight trend window, so
no price reference inside the recent (≈18–36h) window exists for any ticker.

1.6 WHEN time advances on the deployed system THEN no new price observations accumulate, so every
recent-window reference lookup remains empty and all downstream change/P&L/trend values stay
unavailable or zero indefinitely.

### Expected Behavior (Correct)

What should happen instead:

2.1 WHEN a user opens the Overview or Portfolio page AND fresh price observations exist THEN the
system SHALL display real 24h change and 24h Profit/Loss values, showing "—" only for the individual
tickers that genuinely have no in-window reference.

2.2 WHEN a user opens the Overview page AND holdings have a real cost basis THEN the system SHALL
display the real all-time return percentage.

2.3 WHEN a user opens the Market Data page THEN the system SHALL display a "Last Updated" age that
reflects the most recent observation, and listed prices SHALL refresh as new observations are
ingested.

2.4 WHEN a user opens the AI Insights page AND at least two observations exist for a ticker THEN the
system SHALL display the real trend / percentage change.

2.5 WHEN market-data-service publishes a `PriceUpdatedEvent` THEN portfolio-service and
insight-service SHALL successfully deserialize and process it end-to-end, updating the current price,
appending the price-history reference, and updating the insight trend window.

2.6 WHEN price updates propagate end-to-end over time THEN fresh references SHALL accumulate inside
the recent (≈18–36h) window so downstream change/P&L/trend values resolve to real numbers rather than
staying permanently unavailable.

### Unchanged Behavior (Regression Prevention)

Existing behavior — much of it introduced by the prior dashboard-data-accuracy and kafka-dlq work —
that must be preserved:

3.1 WHEN a ticker genuinely has no price reference within the recent (≈18–36h) tolerance window THEN
the system SHALL CONTINUE TO render "—" (null) rather than a fabricated +0.00% / +$0.00 ("no silent
zero").

3.2 WHEN a malformed or invalid `PriceUpdatedEvent` is received (null/blank ticker, or null /
non-positive price) THEN the system SHALL CONTINUE TO route it to the dead-letter topic and SHALL NOT
mutate the read model.

3.3 WHEN a valid `PriceUpdatedEvent` is processed THEN the system SHALL CONTINUE TO upsert the current
price idempotently and SHALL NOT create duplicate history rows for the same (ticker, observedAt)
observation.

3.4 WHEN an old-shape (two-field: ticker + newPrice) `PriceUpdatedEvent` is deserialized THEN the
system SHALL CONTINUE TO resolve the missing enrichment fields to null and treat them as
"unavailable" rather than substituting default values.

3.5 WHEN the `PriceUpdatedEvent` JSON wire contract is exercised THEN timestamps SHALL CONTINUE TO
serialize as ISO-8601 UTC strings and prices SHALL CONTINUE TO serialize as scale-2 decimal values.

3.6 WHEN a portfolio has holdings but no fresh prices are available THEN aggregate totals SHALL
CONTINUE TO exclude price-unavailable holdings symmetrically (no phantom 100% loss) as established by
the dashboard-data-accuracy fix.
