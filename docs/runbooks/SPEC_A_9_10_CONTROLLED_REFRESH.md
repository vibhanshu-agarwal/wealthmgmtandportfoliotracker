# Spec A checkpoint 9.10 — controlled refresh execution record

This is the durable, sanitized record of checkpoint 9.10's controlled `market-data-refresh-job`
execution: the query boundaries used, the results obtained, artifact checksums, and the
execution-readback normalization caveats discovered while verifying it. No secret values, raw
connection strings, or credentials appear anywhere in this file. The full runtime evidence
(command output, ticker-set files) was captured under `.artifacts/spec-a-9.10/` during execution
but that directory is gitignored and not committed — this file is the durable substitute.

Checkpoint 9.10 does not persist refresh enablement, seed the demo portfolio, restore
scale-to-zero, or reopen ingress. None of that happened here; see `tasks.md` 9.11–9.14.

---

## Resource map

| Thing | Value |
|---|---|
| Resource group | `wealth-azure-prod-rg` |
| Refresh Job | `market-data-refresh-job` |
| Refresh container | `market-data-refresh` |
| Image repository | `wealthprodacr.azurecr.io/market-data-service` |
| Pinned execution digest | `sha256:ad61144b2e747a5dd1b1fc9f5b5a091916559adf7c30117beae3563123aa2256` |
| `SERVICE_VERSION` / R4 tag | `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900` |
| Mongo database | `portfolio_db`, collection `market_prices` |
| Postgres tables | `market_prices`, `market_price_history` |
| Kafka topic | `market-prices` (1 partition); DLT `market-prices.DLT` (1 partition) |
| Kafka consumer groups | `portfolio-group`, `insight-group` |
| Log Analytics workspace | `wealth-prod-la` |
| Retry-policy prerequisite (Task 1–2) | PR [#147](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/147), apply run [32706717308](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32706717308) |

---

## Execution

- Command (once; not reissued):
  `az containerapp job start --name market-data-refresh-job --resource-group wealth-azure-prod-rg --yaml controlled-template.json`
- Execution: `market-data-refresh-job-0i08hio` — **Succeeded**, `2026-08-24T11:05:39Z` → `11:06:48Z` (~69s of 600s budget).
- Digest used: exact match to the pinned digest above.
- Completion summary (Log Analytics, `ContainerAppConsoleLogs_CL`, `ContainerAppName_s == 'market-data-refresh-job'`):
  `MarketDataRefreshJob: completed refresh in 12017 ms; updated=154, skipped=5, failed=0`
  (154 + 5 = 159 = active catalog count). Exactly one start line, one completion summary, one
  clean shutdown line; zero error/exception/fallback/DLT/rejection lines in the job's own log.
- Skips (5, all reason "no price from provider" — benign per-ticker data availability, not a
  failure): `USDCAD=X`, `USDCHF=X`, `USDHKD=X`, `USDJPY=X`, `USDSGD=X`.

## Template build and verification

- Built via `python infrastructure/terraform/azure/scripts/spec_a_9_10_template.py build --live-template live-template.json --output controlled-template.json --expected-tag 9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900 --expected-digest sha256:ad61144b2e747a5dd1b1fc9f5b5a091916559adf7c30117beae3563123aa2256`
- Sanitized diff: exactly `containers[0].image` (tag → pinned digest) and
  `containers[0].env[MARKET_DATA_JOB_RUNNER_ENABLED].value` (`false → true`); nothing else.
- Independently re-run `verify` against the same live/override pair: **PASS**, identical diff.
- **Checksum (SHA-256): `b4b1267e717b1ea35c3fce74d30e3671f22d3193e2dac32d6832d82fe1e4e763`** —
  reproduced identically by both `build` and a separate `verify` run, and re-verified again
  immediately before `job start` (Task 5 Step 1).
- Manual field-by-field review confirmed: single container `market-data-refresh`; CPU `0.5`,
  memory `1Gi`, ephemeralStorage `2Gi`; no init containers/volumes; 9 plain env entries with
  expected values; 5 secret env entries with exact `secretRef`s
  (`spring-data-mongodb-uri`, `kafka-bootstrap-servers`, `kafka-sasl-username`,
  `kafka-sasl-password`, `internal-api-key`); `SERVICE_VERSION` = R4 tag.

## Baseline (Task 3, immediately-before recheck at Task 5 Step 1)

- Catalog binding: `catalog_loaded version=a00b32ac0267e1a9 entries=160 active=159
  rejectUnsupportedEvents=true enforceHoldingInvariant=true` on `market-data-service--0000078`;
  tag → ACR digest resolved to the pinned digest exactly.
- Kafka pre-run (both checks, T-2.3h and immediately before start): `portfolio-group` and
  `insight-group`, `market-prices` partition 0, `CURRENT-OFFSET == LOG-END-OFFSET == 24541`,
  `LAG == 0` for both.
- Mongo `market_prices`: 161 documents. The 161st is `GOOG` — a legacy, off-catalog
  baseline-seeder orphan. **Correction on the record:** `GOOG` was never migrated to or
  superseded by `GOOGL`; they are distinct Alphabet share classes. The catalog lists `GOOGL`
  ("Alphabet"), independently backfilled into Postgres by `V12__Backfill_Market_Price_History.sql`.
  `GOOG` is unrelated to that migration and was never in the catalog. An integration test proves
  it is neither fetched nor modified by the refresh path. Cleanup is a separate product/data
  decision, out of scope for 9.10.
- Postgres: `market_prices` 160 rows, `market_price_history` 15176 rows.
- Active-set reconciliation: all 159 active catalog tickers independently confirmed present in
  both Mongo and Postgres. Active-set SHA-256 (sorted ticker list, LF-terminated):
  **`09d401ea7644826da83f4d038efd6c234c20904ee60729d2765a35e6c933103e`** — reproduced independently,
  matches. Mongo's only non-active extras: `GOOG`, `TATAMOTORS.NS` (catalog `DEPRECATED`).
  Postgres's only non-active extra: `TATAMOTORS.NS`.
- `GOOG.updatedAt` recorded specifically as the pre-run baseline: `2026-08-19T08:00:52.131Z`.

## Reconciliation (Task 5 Step 5)

Observation window used throughout: `[2026-08-24T11:05:00Z, 2026-08-24T11:10:00Z)` UTC (T0 set
just before `job start` at `11:05:39Z`; execution ended `11:06:48Z`, well inside the window).

**Kafka** (re-checked `11:24:08Z`, after consumer drain): log-end offset moved
`24541 → 24695` = **+154**, exact match to `U`. Both `portfolio-group` and `insight-group`:
committed offset `24695` = log-end `24695`, lag `0`.

**Mongo** `market_prices`, `updatedAt` in window: **154** documents. Ticker set diffed against
the log-claimed 154-ticker list: zero differences either direction. `GOOG.updatedAt` re-checked
post-run: **unchanged** at `2026-08-19T08:00:52.131Z` — the orphan was correctly untouched.

**Postgres** `market_price_history` and `market_prices`, `observed_at` in the same window: **154**
rows each; ticker sets match the log-claimed set exactly (zero diff).

**Tuple-level agreement — Mongo vs Postgres** (`ticker`/`price`/`currency`): of 154 tickers, 27
showed apparent numeric differences on a naive diff; every one independently verified as correct
rounding to Postgres's `current_price NUMERIC(19, 4)` column
(`portfolio-service/src/main/resources/db/migration/V1__Initial_Schema.sql:31`) — e.g. Mongo
`UNI-USD=0.0001626423` → Postgres `0.0002`; `SHIB-USD=0.00000542` → `0.0000`;
`USDC-USD=0.99995244` → `1.0000`. Not a data-integrity issue.

**Observation-timestamp equality — Mongo `updatedAt` vs Postgres `market_prices.observed_at`**
(millisecond precision, explicit UTC on both sides): compared across all 154 tickers —
**zero mismatches.**

**In-database agreement — `market_prices` vs `market_price_history`** (ticker + price + currency,
joined on `ticker` and exact `observed_at`, evaluated inside Postgres via SQL, not by diffing
exported files): **zero mismatches, zero orphans** (every `market_prices` row in the window has a
matching `market_price_history` row on `(ticker, observed_at)` with equal `price`/`quote_currency`).

**Negative checks — application logs**: Log Analytics query across `portfolio-service` and
`insight-service`, window extended through `2026-08-24T11:30:00Z` (past the `11:24:08Z` drain
confirmation, not stopped at `T0+5m`), for `DLT`, `unsupported_asset_event_rejected`,
`CURRENCY_MISMATCH`, `CURRENCY_UNRESOLVABLE`, `TICKER_ABSENT`, `conflict`, `ERROR`, `Exception`,
`poison`, `DeadLetter`, `onDlt`, `retry`: only benign Kafka client config-dump lines matched
(e.g. `retry.backoff.ms = 100`, printed at consumer/producer startup) — zero actual
error/DLT/conflict signals.

**Negative check — DLT topic itself, not inferred from lag or app logs**: `market-prices.DLT` has
`ReplicationFactor: 2`, `cleanup.policy=delete`. Checked at `12:42:53Z` (well after execution and
drain): `kafka-get-offsets --time -1` (log-end) = `80`; `--time -2` (log-start) = `80`.

A single post-run reading of `start == end == 80` does **not**, by itself, rule out a record being
appended during the execution and then removed by retention before the check — that would also
present as `start == end == 80`. This gap was closed with a pre-`T0` anchor: a Log Analytics
search of `portfolio-service` logs over the preceding 30 days found exactly **one**
`portfolio-group-dlt` consumption event ever recorded — `topic=market-prices.DLT partition=0
offset=79`, with the record's own Kafka-assigned `CreateTime` = **`2026-08-19T08:42:37.523Z`**
(consumer log line ingested `08:42:38.344Z`) — **five days before `T0`**, and no consumption event
at any higher offset exists in that 30-day window. Because offset `79` existing means the
partition's end-offset was already `>= 80` as of `2026-08-19T08:42:37Z`, and Kafka end-offsets are
monotonically non-decreasing (never reused or decremented), the end-offset must have been pinned
at **exactly 80 continuously** from `2026-08-19T08:42:37Z` through the `12:42:53Z` measurement —
a span that fully contains the execution+drain window (`11:05:39Z`–`11:24:08Z` on `08-24`). If a
record had been appended and later deleted during that window, the end-offset would have exceeded
80 at some point and could never have returned to exactly 80. This rules out the
append-then-delete case, not just the currently-empty case. (A message-content dump via
`kafka-console-consumer` was also attempted for completeness but is not load-bearing here — see
Tooling caveats below.)

## Control-plane reconfirm (Task 5 Step 6)

Persisted Job re-read post-execution: `replicaRetryLimit=0`, `runnerEnabled=false`, image tag
`9b2cf0d…`, schedule `0 8 * * *` — all unchanged from pre-execution. Gateway ingress: still `null`
(closed). All three catalog services (`portfolio-service`, `market-data-service`,
`insight-service`): `Running`/`Healthy`, `minReplicas=1`, **same active revisions** as before
execution — no redeploy was triggered.

## Decision: GO

Every condition required by the plan's Task 5 Step 7 agrees: correct digest, clean success tuple,
exact ticker-set and tuple-level reconciliation (log claims = Mongo = Postgres, both tables,
including observation timestamps to the millisecond), zero Kafka lag, zero DLT records, zero
application-level conflict/error signals, the off-catalog orphan proven untouched, and the control
plane unchanged. No ABORT condition present.

---

## Execution-readback normalization caveats

Verifying the started execution against the reviewed override template surfaced Azure API
read-back quirks that are worth recording so a future operator doesn't mistake them for real
divergence:

1. **Secret ref collapse.** `az containerapp job execution show --query properties.template`
   reports all 5 secret-backed `env[].secretRef` values collapsed to a single synthetic name
   (`cappjob-<job-name>`), instead of echoing the individually named refs
   (`spring-data-mongodb-uri`, etc.) that were actually submitted. Confirmed as read-back-only:
   the job-level secret store still held the correct 5 named secrets, and the running container's
   own logs proved correct resolution (Mongo writes succeeded; Kafka producer initialized and
   closed cleanly). No secret value was ever exposed to establish this — inferred entirely from
   behavior, never by inspecting the secret.
2. **`resources.ephemeralStorage` reads back empty (`''`)** on the *execution* object, though the
   persisted Job's own template (captured pre-execution) and the submitted override both correctly
   show `2Gi`. A gap in what the execution-level read API exposes, not evidence the container ran
   under-provisioned.
3. **Empty-collection representation swaps**: `initContainers`/`volumes`/`probes` alternate
   between `null` and `[]` between the submitted override and the execution read-back for
   semantically-empty fields. Cosmetic.
4. **`az containerapp job logs show` stops working once the one-shot replica is torn down**
   (expected — the replica no longer exists after the Job completes). The authoritative execution
   log must be pulled from Log Analytics (`ContainerAppConsoleLogs_CL`) once ingestion catches up
   (a few minutes' lag observed) rather than relied on live during/immediately after the run.
5. **`kafka.tools.GetOffsetShell` no longer exists** in the `confluentinc/cp-kafka` image pinned
   for these checks (`@sha256:acbbf674f2ed40e5d0a8ca51beb0f00692c866fc22b5ce06f8cadbdc54cd4436`);
   use `kafka-get-offsets` instead.
6. **`kafka-console-consumer` against `market-prices.DLT` fails with a generic
   `Error processing message, terminating consumer process`**, reproduced even with
   `print.value=false`/`print.key=false` (metadata only, no payload deserialization attempted).
   Root cause not isolated — not investigated further because the offset-based proof above
   (`start == end == 80`, unmoved since well before and through the entire execution+drain window)
   is authoritative and does not depend on this tool succeeding. Flagged here so a future operator
   doesn't waste time assuming their own setup is at fault.

## Verification commands (query boundaries, no secrets)

```powershell
# Kafka lag (per docs/runbooks/SPEC_A_KAFKA_LAG_CHECK.md), both groups, market-prices partition 0
kafka-consumer-groups --bootstrap-server $BOOT --command-config client.properties --group <group> --describe --offsets

# DLT topic offsets
kafka-get-offsets --bootstrap-server $BOOT --command-config client.properties --topic market-prices.DLT --time -1   # end
kafka-get-offsets --bootstrap-server $BOOT --command-config client.properties --topic market-prices.DLT --time -2   # start

# Pre-T0 anchor: has the DLT consumer ever logged an offset >= the post-run end-offset?
# (closes the append-then-retention-delete gap that a single post-run start==end reading leaves open)
ContainerAppConsoleLogs_CL
| where ContainerAppName_s == 'portfolio-service'
| where Log_s has 'DLT: Failed record received'
| project TimeGenerated, Log_s
| order by TimeGenerated asc

# Mongo, observation window [T0, T1)
db.market_prices.find({updatedAt: {$gte: T0, $lt: T1}}, {_id:1, currentPrice:1, quoteCurrency:1, updatedAt:1})
db.market_prices.findOne({_id: "GOOG"})   # orphan unchanged check

# Postgres, observation window [T0, T1), explicit UTC
SET TIME ZONE 'UTC';
SELECT ticker, current_price, quote_currency, observed_at FROM market_prices
  WHERE observed_at >= T0 AND observed_at < T1 ORDER BY ticker;
SELECT ticker, price, quote_currency, observed_at FROM market_price_history
  WHERE observed_at >= T0 AND observed_at < T1 ORDER BY ticker;
-- in-database agreement check
SELECT mp.ticker FROM market_prices mp JOIN market_price_history h
  ON h.ticker = mp.ticker AND h.observed_at = mp.observed_at
  WHERE mp.observed_at >= T0 AND mp.observed_at < T1
  AND (mp.current_price <> h.price OR mp.quote_currency <> h.quote_currency);

# Application-log negative check (Log Analytics), window extended through post-drain
ContainerAppConsoleLogs_CL
| where ContainerAppName_s in ('portfolio-service','insight-service')
| where TimeGenerated between (T0 .. post_drain)
| where Log_s has_any ('DLT','unsupported_asset_event_rejected','CURRENCY_MISMATCH',
    'CURRENCY_UNRESOLVABLE','TICKER_ABSENT','conflict','ERROR','Exception','poison','DeadLetter','onDlt','retry')
```
