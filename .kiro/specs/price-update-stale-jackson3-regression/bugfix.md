# Bugfix Requirements Document

## Introduction

The dashboard has regressed to showing **stale prices** (rendered as "1 day ago") and a **24h change of "—" / +0.00%** for every holding. This is the same user-visible failure that was deliberately fixed by the `dashboard-data-accuracy` spec (Waves 4–6, 2026-06-10), which made the 24h change "honest": it derives change from a price-history reference that falls inside an ≈18–36h tolerance window and renders "—" (rather than a fabricated 0.00%) only when no in-window reference exists.

The symptom reappeared after the **Spring Boot 4.1 + Jackson 2→3 migration** landed on 2026-06-17 (PRs #66–72), which touched the cross-service price-update pipeline (`market-data-service` Kafka producer → `PriceUpdatedEvent` → `portfolio-service` / `insight-service` Kafka consumers) and its runtime configuration.

The two symptoms share a single underlying cause. In the current data model:

- The price freshness shown on the dashboard ("1 day ago") is driven by `market_prices.updated_at`, which only advances when `portfolio-service` consumes a `PriceUpdatedEvent` and projects it into its read model.
- The 24h change is computed from rows in `market_price_history`, which are only appended when `portfolio-service` consumes a `PriceUpdatedEvent` carrying a non-null `observedAt`.

So when freshly published price observations stop reaching the `portfolio-service` read model, `market_prices.updated_at` stops advancing (prices look stale) **and** no new history rows land in the ≈18–36h window (so the honest-but-now-empty reference renders every 24h change as "—"). The defect is therefore that valid, well-formed price observations published after the migration are not being projected into the portfolio read model, even though the same events were projected correctly before the migration.

This spec captures the requirements for restoring correct end-to-end behavior of the price-update pipeline, without regressing the intentional "honest —" semantics introduced by `dashboard-data-accuracy`.

## Bug Analysis

### Current Behavior (Defect)

When a well-formed price observation flows through the post-migration pipeline, the portfolio read model is not updated and the dashboard shows stale data.

1.1 WHEN `market-data-service` publishes a well-formed `PriceUpdatedEvent` (valid ticker, positive `newPrice`, non-null `observedAt`) after the 2026-06-17 migration THEN the system does not advance `market_prices.updated_at` for that ticker, so the dashboard continues to render the price age as stale (e.g. "1 day ago").

1.2 WHEN `market-data-service` publishes a well-formed `PriceUpdatedEvent` with a non-null `observedAt` after the 2026-06-17 migration THEN the system does not append a corresponding row to `market_price_history`, so no reference price lands inside the ≈18–36h tolerance window.

1.3 WHEN a holding has no `market_price_history` reference inside the ≈18–36h window because new observations are no longer being recorded THEN the system reports the holding's 24h change as unavailable ("—" / +0.00%) for every holding on the dashboard, despite live prices being expected.

1.4 WHEN the price-update pipeline fails to project observations after the migration THEN the staleness and the "—" 24h change persist indefinitely (the data never self-heals), reproducing the exact failure that `dashboard-data-accuracy` had previously resolved.

### Expected Behavior (Correct)

For the same conditions, the pipeline must deliver fresh observations end-to-end so the dashboard shows current prices and a real 24h change.

2.1 WHEN `market-data-service` publishes a well-formed `PriceUpdatedEvent` (valid ticker, positive `newPrice`, non-null `observedAt`) THEN the system SHALL project it into the `portfolio-service` read model and advance `market_prices.updated_at` for that ticker, so the dashboard renders a recent price age.

2.2 WHEN `market-data-service` publishes a well-formed `PriceUpdatedEvent` with a non-null `observedAt` THEN the system SHALL append exactly one corresponding `market_price_history` row keyed by `(ticker, observed_at)`, so a reference price is available for 24h-change computation.

2.3 WHEN a holding has a `market_price_history` reference inside the ≈18–36h tolerance window THEN the system SHALL compute and display a real (non-zero where the prices differ) 24h change for that holding rather than "—" / +0.00%.

2.4 WHEN price observations resume flowing through the pipeline THEN the system SHALL allow the dashboard's prices and 24h change values to recover automatically as new in-window history accumulates, without manual intervention.

### Unchanged Behavior (Regression Prevention)

The fix must restore delivery without undoing the intentional honesty and resilience behaviors already in place.

3.1 WHEN a holding genuinely has no `market_price_history` reference in any window (in-window or older snapshot) THEN the system SHALL CONTINUE TO render the 24h change as "—" and SHALL NOT substitute a fabricated +0.00% (the `dashboard-data-accuracy` "no silent zero" rule).

3.2 WHEN a `PriceUpdatedEvent` is malformed (null/blank ticker, null or non-positive `newPrice`) THEN the system SHALL CONTINUE TO reject it as a `MalformedEventException` and route it to `market-prices.DLT` without updating the read model.

3.3 WHEN an old-shape `PriceUpdatedEvent` is received (only `ticker` and `newPrice`, with `quoteCurrency` / `observedAt` / reference fields absent) THEN the system SHALL CONTINUE TO deserialize it successfully with the missing fields resolving to `null`, and SHALL CONTINUE TO skip the history append (no synthetic receive-time substitution).

3.4 WHEN a `PriceUpdatedEvent` carrying enrichment fields is serialized and deserialized across the Kafka wire THEN the system SHALL CONTINUE TO preserve the ISO-8601 UTC encoding of `observedAt` / `previousReferenceAt` and the scale-2 monetary values of `newPrice` / `previousReferencePrice`.

3.5 WHEN the same observation is delivered more than once (duplicate Kafka delivery of the same `(ticker, observed_at)`) THEN the system SHALL CONTINUE TO treat the projection and history append as idempotent (no duplicate history rows, no corrupted valuation state).

3.6 WHEN distributed-tracing / Kafka observation is enabled on the producer and listener THEN the system SHALL CONTINUE TO operate with observation active and SHALL NOT have message delivery depend on a tracing exporter being configured.
