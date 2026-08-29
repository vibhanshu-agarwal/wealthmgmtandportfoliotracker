# Spec A checkpoint 9.12 — pooled read-only session RCA

This is the durable, sanitized record of the Spec A 9.12 demo-enable failure, guarded diagnostics
cycle, current safe production state, and deterministic source RCA for the pooled PostgreSQL session
that entered the startup transaction with `default_transaction_read_only=on` while Spring and JDBC
reported writable. No secret values, connection strings, or credentials appear here.

Checkpoint 9.12 remains **incomplete**. This record does **not** authorize a production fix, demo
re-enable, scale-to-zero restore, or ingress reopen.

---

## Production timeline

| Phase | Run | Outcome |
|---|---|---|
| 9.12 enable apply | [33150399420](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33150399420) | Demo gate applied; `DemoPortfolioInitializer` failed on `DELETE` with `cannot execute DELETE in a read-only transaction` |
| 9.12 disable/rollback | [33151372186](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33151372186) | Demo gate restored to `false`; demo portfolio remained at 3 holdings |
| Diagnostics artifact deploy | [33163735788](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33163735788) | Revision `portfolio-service--0000084`; both flags `false` |
| Diagnostics enable remote-plan | [33164354902](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33164354902) | Authorized plan only |
| Diagnostics enable apply | [33164724059](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33164724059) | Revision `portfolio-service--0000085`; diagnostics `true`, demo seed `false` |
| Diagnostics disable remote-plan | [33165382021](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33165382021) | Authorized plan only |
| Diagnostics disable apply | [33165574715](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33165574715) | Revision `portfolio-service--0000086`; both flags `false` |

Source merged via PRs #167, #169, and #170 (`main@cb5af200`). Diagnostic artifact digest:
`sha256:6026e906587e1df710f6301314335c11b9d44b7f91c9bdc920708f310952113f`.

### Production setter-provenance cycle (`0000087`–`0000089`)

Setter-provenance cycle source baseline: `0887a309fe12f49ca37585e5a594661727cf4936` (includes PR #172 at
`9fbac4d2ada2240a980d5d7c9c2bd9dedc91de01` and implementation commit
`b51bb49a4c22a82c8ce557750b1f03ecd2cc0212`).

| Phase | Run | Outcome |
|---|---|---|
| Flags-false baseline deploy | [33240756821](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33240756821) | Revision `portfolio-service--0000087`; both flags `false` |
| Diagnostics enable plan | [33241128381](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33241128381) | Authorized plan only; one-change Terraform scope |
| Diagnostics enable apply | [33241578205](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33241578205) | Revision `portfolio-service--0000088`; diagnostics `true`, demo seed `false` |
| Diagnostics disable plan | [33241980847](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33241980847) | Authorized plan only |
| Diagnostics disable apply | [33242076369](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33242076369) | Revision `portfolio-service--0000089`; both flags `false` |

| Boundary | Authoritative value |
|---|---|
| Diagnostic artifact tag | `spec-a-912-provenance-0887a309fe12-20260829` |
| Diagnostic artifact digest | `sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a` |
| `SERVICE_VERSION` | `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900` |

---

## Current safe production state

| Boundary | Value |
|---|---|
| Active revision | `portfolio-service--0000089` |
| Traffic | 100% on revision `0000089`; Healthy/Running |
| `APP_DEMO_SEED_ON_STARTUP` | `false` |
| `APP_DEMO_TX_DIAGNOSTICS` | `false` |
| Peers | market-data `0000078`, insight `0000078`, gateway `0000076`; gateway ingress closed |
| Demo portfolio | 1 portfolio, 3 holdings (unchanged from pre-enable baseline) |
| Checkpoint 9.12 | Incomplete — 11 of 14 cutover checkpoints complete |

### Data/non-interference baseline (after provenance disable on `0000089`)

| Data set | Verified state |
|---|---|
| Demo user `00000000-0000-0000-0000-0000000d3110` | 1 portfolio, 3 holdings, checksum `c976c71beb4f14d54d641fc5551b682123239a27b05c7f24c821331f71265d7d` |
| E2E user `00000000-0000-0000-0000-000000000e2e` | 1 portfolio, 159 holdings, checksum `544c1ffa057918bf65760f96fb1e241cffb9d8a96ce5cfbd291b7ad6381c480f` |
| `market_prices` | 160 rows, aggregate checksum `169246513197` |
| `market_price_history` | 15648 rows, aggregate checksum `16689931365895` |
| Kafka | `portfolio-group` and `insight-group` current/log-end `25013`, lag `0` |

Market-data row and checksum movement relative to the earlier diagnostics-disable baseline is explained
by the independently scheduled refresh execution `market-data-refresh-job-29799840`
(08:00:00Z–08:01:07Z; updated 159, skipped 0, failed 0), not by the read-only rollback probe.

---

## Live boundary values (revision `0000085`)

| Boundary | Evidence |
|---|---|
| `run-entry` | `actualTransactionActive=false`, `currentTransactionReadOnly=false`, no EntityManager join, no datasource resource bound |
| `probe-before-transaction-template` | still no active transaction |
| `probe-inside-transaction-template` | `actualTransactionActive=true`, `currentTransactionReadOnly=false`, `statusNewTransaction=true`, EntityManager joined, datasource bound |
| First JDBC capture after advisory lock | `jdbcConnectionReadOnly=false`, `SHOW transaction_read_only=on`, `SHOW default_transaction_read_only=on`, `pg_is_in_recovery=false` |
| Before DML probe | same state; `DELETE FROM portfolios WHERE FALSE` failed with PostgreSQL read-only-transaction error |
| Completion | `dmlProbeOutcome=FAIL`, `transactionRollbackOnly=true`; application startup continued |

This disproves the earlier ambient-read-only-outer-transaction hypothesis. A new Spring transaction
was created, but its enlisted PostgreSQL session already had a read-only default.

---

## Deterministic source RCA matrix

Test class: `SpecA912PooledSessionReadOnlyProvenanceIT` (Testcontainers PostgreSQL 18.4, Hikari pool
shrunk to one connection per test after Flyway startup). The suite runs **10 tests** covering **9
distinct matrix scenarios**; the normal read-only commit path is asserted twice (`ordinarySpringReadOnlyTemplateDoesNotPoisonNextTransaction` and `readOnlyTransactionTemplatePreservesPidWithoutPoisoningNextTransaction`).

| Case | Backend PID continuity | Spring read-only | JDBC read-only | `default_transaction_read_only` | `transaction_read_only` | DML probe |
|---|---|---|---|---|---|---|
| Fresh pooled session | n/a | false | false | off | off | writable |
| Ordinary `TransactionTemplate(readOnly=true)` then default template | same PID | false (following) | false | off | off | writable |
| Native `SET SESSION … READ ONLY`, then default template | same PID | false | false | on | on | FAIL (`25006`) |
| `connection.setReadOnly(false)` after native poison | n/a | n/a | false | on (unchanged) | n/a | n/a |
| `PortfolioService.getByUserId` then default template | same PID | false (following) | false | off | off | writable |
| Read-only template + rollback-only completion | same PID | false (following) | false | off | off | writable |
| Read-only template + runtime exception after enlisted `SELECT 1` | same PID | false (following) | false | off | off | writable |
| Initializer-shaped sequence, clean session | n/a | false | false | off | off | PASS |
| Initializer-shaped sequence, poisoned session | same PID | false | false | on | on | FAIL |

---

## Decision-table verdict

**`MECHANISM_REPRODUCED_SETTER_UNPROVEN`**

| Criterion | Result |
|---|---|
| Live mismatch reproduced locally? | Yes — native session poison yields `Spring false / JDBC false / PostgreSQL on/on` with same backend PID |
| Repository-owned Spring read-only leak? | No — ordinary `TransactionTemplate(readOnly=true)`, `PortfolioService.getByUserId`, commit, rollback-only, and exception completions all leave the following transaction `off/off` |
| Initializer creates the state? | No — initializer-shaped sequence observes poisoned state but does not create it on a clean session |
| Production setter identified? | No — no application code sets `default_transaction_read_only`; `application-prod.yml` configures only Hikari pool size and connection timeout |

### Ruled out

- Ambient read-only outer transaction joining the initializer (live revision `0000085` disproved).
- Ordinary Spring/JPA read-only transaction cleanup leaving `default_transaction_read_only=on` in
  this Testcontainers/Hikari environment.
- Initializer sequence itself setting the session GUC.

### Not yet proven

- Which production actor issued `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` or
  equivalent on the Neon pooler connection before the 9.12 startup transaction.
- Whether the setter is external to the repository (pooler, role default, driver reset gap, or
  out-of-band session mutation).

---

## Production setter-provenance result

**Merged source:** PR #172 at `main@9fbac4d2ada2240a980d5d7c9c2bd9dedc91de01`
(implementation commit `b51bb49a4c22a82c8ce557750b1f03ecd2cc0212`), now on
`main@0887a309fe12f49ca37585e5a594661727cf4936`.

**Deployed diagnostic cycle:** artifact tag `spec-a-912-provenance-0887a309fe12-20260829`, digest
`sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a`,
`SERVICE_VERSION=9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900`. Provenance observed on revision
`portfolio-service--0000088`; production returned to `portfolio-service--0000089` with both flags
`false`.

**Top-level RCA verdict remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`.**

### Source files

| File | Role |
|---|---|
| `SpecA912PooledSessionProvenance.java` | SQL classifier, PID transition tracker, sanitized event model |
| `SpecA912ProvenanceDataSource.java` | Delegating DataSource + JDBC proxies |
| `SpecA912ProvenanceDataSourcePostProcessor.java` | `dataSource` bean gate on existing `APP_DEMO_TX_DIAGNOSTICS` |
| `SpecA912PooledSessionProvenanceTest.java` | Classifier, transition, sanitization unit tests |
| `SpecA912ProvenanceDataSourceTest.java` | Gate matrix, proxy transparency, Spring context proofs |
| `SpecA912PooledSessionSetterProvenanceIT.java` | Testcontainers one-connection attribution matrix |

### Activation gate (reuses existing flag; no Terraform/workflow expansion)

| `APP_DEMO_TX_DIAGNOSTICS` | `app.demo.seed-on-startup` | Behavior |
|---|---|---|
| `false` | any | Original `dataSource` identity; zero observer SQL |
| `true` | `true` | Fail-closed; original bean; fixed rejection warning; no observer SQL |
| `true` | `false` | Wrap `dataSource` once; observe by `pg_backend_pid()` |

### Testcontainers outcomes (Hikari `maximumPoolSize=1`)

| Scenario | Same backend PID | Expected transition | Setter event |
|---|---|---|---|
| Wrapper executes `SET SESSION … READ ONLY` | yes | `ATTRIBUTED_OFF_TO_ON` | `SESSION_DEFAULT_READ_ONLY` |
| Raw Hikari delegate poisons below wrapper | yes | `UNATTRIBUTED_OFF_TO_ON` | none |
| Poison before first wrapper borrow | n/a | `FIRST_OBSERVED_ON` | none |

### Production observation (revision `0000088`)

- Exactly one `event=spec_a912_pool_session_provenance` transition.
- First wrapped checkout, `checkoutId=1`, backend PID `19916`.
- `jdbcReadOnly=false`, `autoCommit=true`, `defaultTransactionReadOnly=on`, `transactionReadOnly=on`.
- `setterKind=NONE`, `transition=FIRST_OBSERVED_ON`.
- Bounded call path:
  `JdbcUtils#openConnection <- JdbcConnectionFactory#<init> <- FlywayExecutor#init <- FlywayExecutor#execute <- Flyway#migrate <- FlywayMigrationInitializer#afterPropertiesSet <- AbstractAutowireCapableBeanFactory#invokeInitMethods <- AbstractAutowireCapableBeanFactory#initializeBean`.
- `ATTRIBUTED_OFF_TO_ON=0`, `UNATTRIBUTED_OFF_TO_ON=0`, provenance failures/skips `0`.
- Eight `event=spec_a912_tx_diag` events and one successful `event=spec_a912_tx_probe_complete`; every
  JDBC observation used PID `19916`.
- The no-op delete failed with the expected read-only condition and the transaction rolled back;
  production application data was unchanged.

`FIRST_OBSERVED_ON` proves the session was already read-only before the first application wrapper
checkout. The repository wrapper did not observe an attributed or unattributed off-to-on transition.
This moves the next question upstream to connection origin/defaults. It does **not** prove Neon, the
pooler, a role default, a database default, a client startup option, or any named operator.

### Event fields and sanitization

Structured log prefix: `event=spec_a912_pool_session_provenance`. Allowed fields: fixed phase name,
checkout id, backend PID, transition enum, setter kind enum, JDBC/GUC booleans and `on/off` strings,
bounded `class#method` call path (max eight entries), SQLSTATE. Observation failures log
`event=spec_a912_pool_session_provenance_failed` with phase, exception class, and SQLSTATE only — no
exception messages, raw SQL, URLs, credentials, bind values, or application data.

Exclusions from call path: `java.lang.reflect`, `jdk.proxy`, and internal diag wrapper classes.

Explicit vendor `Connection.unwrap(...)` to the delegate remains an acknowledged
below-wrapper blind spot: JDBC issued through an unwrapped vendor handle is not instrumented.

### Setter attribution model

Setter-shaped calls emit a `setter-attempt` event before delegation using **no observer
SQL** (null snapshot). After successful direct or batch execution, exactly one guarded
`captureAndResolveSetter` call checks `autoCommit`, captures once when safe,
confirms attribution, resolves the transition, and clears pending state in `finally`.
Batch `addBatch` queues intent only after successful delegate `addBatch`;
`clearBatch` clears the queue only after successful delegate `clearBatch`;
prepared no-argument `addBatch` uses the prepare-time SQL hint; batch attribution uses
the **last session-state-affecting** queued setter, not merely the last setter of any kind.

### Explicit non-claims

Production setter-provenance observation does **not** identify who established the read-only default.
Both application flags are again `false` on `portfolio-service--0000089`. The later connection-origin
live matrix did not reproduce the read-only state; that result does not retroactively attribute the
historical `FIRST_OBSERVED_ON` session or prove that the startup failure is fixed.

---

## Explicit non-claims

- Checkpoint 9.12 is **not** complete.
- No production fix has been applied or validated.
- Native session poisoning is the **mechanism** reproduced locally; it is **not** claimed as the
  production root cause unless a repository-owned path that performs it is proven.
- `FIRST_OBSERVED_ON` is **not** a named-actor attribution.

---

## Recommended next architecture question

The fresh manual connection matrix is clean while the historical startup capture was not. The next
bounded question is therefore historical: did PostgreSQL statement statistics retain any known
read-only setter shape in statistics whose reset boundary covers the incident? Architecture review
should first define a
source-only, fixed-output collector over `pg_stat_statements`, `pg_stat_statements_info`, and the
relevant tracking settings. The aggregate has no per-execution timestamp, so a retained count cannot
be bound to the incident. Current tracking settings are capability observations, not proof of their
historical values. Absence is meaningful only when the statistics reset predates the incident, current
statement tracking is `top`/`all`, utility tracking is `on`, query-ID calculation is `auto`/`on`, no
entry eviction occurred, and statement-text visibility is immediately usable and explicitly scoped;
without usable `pg_read_all_stats`/superuser visibility, a negative can cover only the current database
role. Raw query text, actor identity, and arbitrary statement search are forbidden.
Source implementation and any later live execution are separate gates. Do not design or apply a remedy
from the clean manual matrix alone.

---

## Connection-origin live probe result

**`CONNECTION_ORIGIN_LIVE_NOT_REPRODUCED`**

| Item | Value |
|---|---|
| Source merge | PR [#174](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/174); source `e63fa5bb70982734dfcc7eada5e592970e5bed67`; merge `4ac264054f872132e88706ef535c80db17885754` |
| Executable | `portfolio-service/src/test/java/com/wealth/portfolio/seed/rca/SpecA912ConnectionOriginProbe.java` |
| Manual entry point | Gradle task `specA912ConnectionOriginProbe` (group `diagnostics`; not on `check`/`build`/`bootJar`) |
| Authorized live matrix | 2026-08-29; endpoints `POOLED` then `DIRECT`; exactly five fresh `DriverManager` attempts each; process exit `0`; `BUILD SUCCESSFUL` |
| Allow-list | exactly one composite read-only `SELECT` per attempt (PID, recovery, both GUCs, `pg_settings` source/reset, catalog scopes); no `SET`/`RESET`/`DISCARD`/DML/DDL in the collector |
| Pooled result | five of five: JDBC writable, auto-commit on, both transaction read-only GUCs `off`, `pg_settings.setting=off`, `reset_val=off`, source `DEFAULT`, no current catalog defaults, not in recovery; the pooler reused one backend PID |
| Direct result | five of five: same writable/default evidence; five distinct backend PIDs |
| Verdict | `NOT_REPRODUCED_IN_MANUAL_MATRIX` |
| Non-interference | before/after counts and MD5 snapshots were identical: demo `1/3`, E2E `1/159`, portfolios `10` / `1b78e7b91ec736f6c5bce8c2a67a1b6f`, holdings `162` / `9dceb19f517f9aaf81ceeee4280375a1`, prices `160` / `d9a2fe2cb7b35ae0cc4d12ffd86c2476`, history `15648` / `fcf2f26a926e17d14c1a5b41e758d43d` |
| Production after-check | `portfolio-service--0000089` Healthy/Running on digest `sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a`; demo seed `false`; diagnostics `false`; no refresh execution running |
| Architectural meaning | no current persistent role/database default, client-startup default, or manual pooled/direct divergence was evidenced; the historical production startup session remains unexplained |
| Next gate | architecture review and source-only implementation of the bounded statement-history-window collector; live execution remains separately unauthorized |

Checkpoint 9.12 remains incomplete. Top-level RCA verdict remains
`MECHANISM_REPRODUCED_SETTER_UNPROVEN`; the clean manual matrix narrows the search but neither proves a
setter nor authorizes a remedy.
