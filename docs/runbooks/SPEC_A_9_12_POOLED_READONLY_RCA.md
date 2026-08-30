# Spec A checkpoint 9.12 — pooled read-only session RCA

This is the durable, sanitized record of the Spec A 9.12 demo-enable failure, guarded diagnostics
cycle, current safe production state, and deterministic source RCA for the pooled PostgreSQL session
that entered the startup transaction with `default_transaction_read_only=on` while Spring and JDBC
reported writable. No secret values, connection strings, or credentials appear here.

Checkpoint 9.12 is **operationally complete**. Historical setter attribution remains
`MECHANISM_REPRODUCED_SETTER_UNPROVEN`. This record does **not** authorize a 9.13 remote-plan, apply,
scale-to-zero live verification, or ingress reopen.

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
| Active revision | `portfolio-service--0000091` |
| Traffic | 100% on revision `0000091`; Healthy/Running |
| Image digest | `sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a` |
| `APP_DEMO_SEED_ON_STARTUP` | `false` |
| `APP_DEMO_TX_DIAGNOSTICS` | `false` |
| `min_replicas` / `max_replicas` | `1` / `3` (scale-to-zero remains a later 9.13 live gate) |
| Peers | remained on prior revisions through the 9.12 retry cycle; gateway ingress closed |
| Demo portfolio | 1 portfolio, 159 holdings (Active_Asset set) |
| Checkpoint 9.12 | Operationally complete — 12 of 14 cutover checkpoints complete; historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN` |

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

## Authorized successful retry (2026-08-30)

Source baseline: `main@d29f67083109086de4ed00d38589267609e24265`.

| Phase | Run | Outcome |
|---|---|---|
| Enable remote-plan | [33295372571](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33295372571) | Successful; exact portfolio-only in-place change; apply skipped |
| Enable apply | [33295859015](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33295859015) | Successful after Production Environment approval; created `portfolio-service--0000090`; 100% traffic; digest `sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a`; `APP_DEMO_SEED_ON_STARTUP=true`; diagnostics `false`; one running replica |
| Disable remote-plan | [33296129216](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33296129216) | Successful; apply skipped |
| Restoring apply | [33296204759](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33296204759) | Successful after Production Environment approval; created `portfolio-service--0000091`; latest and latest-ready; Healthy/Running; 100% traffic; same reviewed digest; demo and diagnostics both `false`; `min_replicas=1`, `max_replicas=3` |

### Activation evidence on `0000090`

- Application started.
- `Golden-state seed complete (holdings only)` for compiled demo UUID `00000000-0000-0000-0000-0000000d3110`, portfolio `759cf4f6-04d2-4c04-ad36-d0ea09bc843d`, holdings `159`.
- Exactly one `event=demo_portfolio_seeded`.
- With one replica, the required `N-1` serialized no-op count is zero.

### Direct production Neon verification

Executed in an explicit `REPEATABLE READ READ ONLY` transaction with `ON_ERROR_STOP` and `ROLLBACK`.
Credentials were not printed or persisted.

| Check | Result |
|---|---|
| Demo portfolios | `1` |
| Demo holdings | `159` |
| Independent-oracle tuple MD5 | `6e436f24fa2b31d14aff77fe5d1a05c9` |
| Invalid demo tuple rows | `0` |
| Minimum/maximum cost-basis anchor | `2020-01-01 00:00:00` |
| E2E portfolios | `1` |
| E2E holdings | `159` |
| E2E row transaction IDs | all predated the demo seed transaction |

Independent oracle catalog digest:
`CFA5E6B7317E922C07452359B851E55EE0A2A5AE9014224665244F9C2264DE8B`; active asset count `159`.

Peer services remained on their prior revisions during the 9.12 cycle; gateway ingress remained
closed; no unexpected market-data refresh execution was observed.

### Dual verdict

```text
Operational checkpoint verdict: PASS — authorized enable and restoring rollouts plus live
data/configuration verification succeeded.

Historical RCA verdict: MECHANISM_REPRODUCED_SETTER_UNPROVEN — the successful retry did not
identify the actor that established the old read-only session default.
```

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

- Operational checkpoint 9.12 is complete; historical setter attribution is **not**.
- The successful retry did **not** identify the actor that established the old read-only session
  default. Top-level RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`.
- Native session poisoning is the **mechanism** reproduced locally; it is **not** claimed as the
  production root cause unless a repository-owned path that performs it is proven.
- `FIRST_OBSERVED_ON` is **not** a named-actor attribution.
- Checkpoint 9.13 is **not** live-complete. Source readiness for scale-to-zero is a later, separately
  authorized remote-plan/apply plus configuration read-back.

---

## Recommended next architecture question

The fresh manual connection matrix is clean while the historical startup capture was not. The
bounded historical question was whether PostgreSQL statement statistics retained any known
read-only setter shape in statistics whose reset boundary covers the incident. The collector source
merged at PR #176 (`main@cdf23737`); one authorized live execution reached JDBC on 2026-08-29 and
returned `STATEMENT_HISTORY_UNAVAILABLE` because `pg_stat_statements` was not installed. The seven
canonical formatter zeroes are sanitized output for unavailable history and must not be described as
observed zero counts, a negative result, or proof of setter absence. Installing the extension later
cannot reconstruct statements executed before installation. Raw query text, actor identity, and
arbitrary statement search are forbidden. Do not design or apply a remedy from the clean manual
matrix or from unavailable-history output alone.

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
| Next gate | senior architecture review and merge of this evidence-only reconciliation; any production DDL to install `pg_stat_statements` is a separately authorized future-observability change |

Checkpoint 9.12 is operationally complete. Top-level RCA verdict remains
`MECHANISM_REPRODUCED_SETTER_UNPROVEN`; the clean manual matrix narrows the search but neither proves a
setter nor is closed by the successful retry.

---

## Statement-history probe live result

**`STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE`**

### Source and verification history (superseded readiness)

| Phase | Status |
|---|---|
| Source readiness | PR [#176](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/176) merged at `main@cdf2373776ad98457f07caf63d0e426c0e2fe988`; source commit `c81d78e8` |
| Prior status (superseded) | `STATEMENT_HISTORY_PROBE_READY_LIVE_EXECUTION_UNAUTHORIZED` — collector source merged and locally verified; live execution was separately unauthorized |
| Current status | `STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE` — one authorized live execution reached JDBC; fail-closed verdict `STATEMENT_HISTORY_UNAVAILABLE` |
| Top-level RCA | unchanged — `MECHANISM_REPRODUCED_SETTER_UNPROVEN` |
| Checkpoint 9.12 | operationally complete after the later authorized retry; this probe did not close historical attribution |

### Executable and entry point

| Item | Value |
|---|---|
| Executable | `portfolio-service/src/test/java/com/wealth/portfolio/seed/rca/SpecA912StatementHistoryProbe.java` |
| Unit tests | `SpecA912StatementHistoryProbeTest.java` |
| Integration tests | `SpecA912StatementHistoryProbeIT.java` (`@Tag("integration")`, disposable PostgreSQL 18.4) |
| Manual entry point | Gradle task `:portfolio-service:specA912StatementHistoryProbe` (group `diagnostics`; not on `test`/`integrationTest`/`check`/`build`/`bootRun`/`bootJar`/image/deployment) |
| Execution source | `main@cdf2373776ad98457f07caf63d0e426c0e2fe988` |
| Execution date | 2026-08-29 |
| Authorized live executions reaching JDBC | exactly one |
| Process result | exit 1 / Gradle task failed by the collector's intentional fail-closed contract |
| Fail-closed live verdict | `STATEMENT_HISTORY_UNAVAILABLE` |

### Sanitized execution and interpretation

| Field | Value | Interpretation |
|---|---|---|
| `incidentStart` | `2026-08-28T07:08:59Z` | enable run `33150399420` creation time; source constant, not environment input |
| `statsReset` | `null` | not evaluated — canonicalized because `HistoryEvidence` was absent; history unavailable |
| `coveringIncident` | `false` | not evaluated — canonicalized because `HistoryEvidence` was absent; history unavailable |
| `dealloc` | `0` | not evaluated — canonicalized because `HistoryEvidence` was absent; history unavailable |
| `extensionInstalled` | `false` | `pg_stat_statements` absent — statement history unavailable |
| `extensionAccessible` | `true` | capability path itself was readable; does not mean history exists |
| `statementTrack` | `TOP` | current tracking GUC observation only |
| `trackUtility` | `ON` | current tracking GUC observation only |
| `computeQueryId` | `AUTO` | current tracking GUC observation only |
| `canReadAllStatementText` | `true` | immediately usable all-statement visibility confirmed |

Because `extensionInstalled=false`, only the fixed capability catalog `SELECT` ran; the aggregate
statement-history `SELECT` did not run. With `HistoryEvidence` absent, the collector synthesized
`statsReset=null`, `coveringIncident=false`, and `dealloc=0`; coverage and eviction were not
evaluated and those values are not database observations. The formatter also emitted canonical
zeroes for all seven setter shapes. Those zeroes are sanitized output for unavailable history and
must not be described as observed zero counts, a negative result, or proof of setter absence.

| Setter shape (canonical formatter output) | Count |
|---|---|
| `SET_DEFAULT_TRANSACTION_READ_ONLY` | 0 |
| `SET_SESSION_CHARACTERISTICS_READ_ONLY` | 0 |
| `SET_TRANSACTION_READ_ONLY` | 0 |
| `RESET_DEFAULT_TRANSACTION_READ_ONLY` | 0 |
| `ALTER_ROLE_DEFAULT_TRANSACTION_READ_ONLY` | 0 |
| `ALTER_DATABASE_DEFAULT_TRANSACTION_READ_ONLY` | 0 |
| `DISCARD_ALL` | 0 |

These seven zeroes are **not** database observations and cannot support any absence verdict.

### Explicit non-claims (retained verbatim)

```text
RETAINED_SHAPE_NOT_TEMPORALLY_BOUND_TO_INCIDENT
HISTORICAL_TRACKING_CONFIGURATION_NOT_PROVEN
NORMALIZED_SET_CONFIG_TARGET_UNOBSERVABLE
BACKEND_PID_AND_ACTOR_UNBOUND
```

No actor, PID, historical time, or setter is identified. Installing `pg_stat_statements` later
begins a future statistics window and cannot recover statements executed before installation.

### Credential resolution and preflight

One credential-resolution preparation attempt stopped before the JVM/JDBC because Azure's list
response withheld required values. It was not a live probe execution and executed zero database
statements. The subsequent supported secret-detail path supplied the existing pooled credentials
without printing them, and the credentials were cleared immediately after the one actual run.

### Production preflight and after-check (unchanged safe state)

Combined control-plane evidence. Values marked **preflight-only** were captured before the run and
were not re-read after. Values marked **after-check** were re-verified after the run.

| Item | Value | Capture |
|---|---|---|
| `portfolio-service` revision | `portfolio-service--0000089` | after-check |
| Revision health | `Healthy` | preflight-only |
| Application running status | `Running` | after-check |
| Traffic | 100% | preflight-only |
| Image | `sha256:d5693e29c68fd3665366fbd83586b9e8b8a266b993cdd66a761ea17d9312092a` | after-check |
| `SERVICE_VERSION` | `9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900` | after-check |
| `APP_DEMO_SEED_ON_STARTUP` | `false` | after-check |
| `APP_DEMO_TX_DIAGNOSTICS` | `false` | after-check |
| Peers | `market-data-service--0000078`, `insight-service--0000078`, `api-gateway--0000076` | preflight-only |
| Refresh job running count | 0 | after-check |
| Latest refresh execution | `market-data-refresh-job-29799840`, Succeeded, ended 2026-08-29T08:01:07Z | after-check |

Non-interference: the executed collector path contained one fixed catalog/capability `SELECT` and
no DML/DDL or application-table query. The after-check reconfirmed portfolio revision, image,
`SERVICE_VERSION`, both application flags, Running state, and refresh-job state. Peer identities,
traffic, and revision health were preflight-only and were not re-read after the run. This run did
not collect a new production data checksum.

### Terraform live-state image identity (source-only repair)

Production no longer shares one common image tag across all Container Apps: gateway, market-data,
insight, and portfolio can carry distinct `SERVICE_VERSION` values, and portfolio is digest-pinned
in live assertions. The Terraform Azure workflow therefore requires `deployed_image_tags_json`, a
four-key JSON map (`api-gateway`, `portfolio-service`, `market-data-service`, `insight-service`),
each verified in its own ACR repository before remote-plan/apply. This restores truthful planning
only; it does **not** authorize a 9.13 apply, ingress reopening, or a gateway live
probe. Historical RCA remains `MECHANISM_REPRODUCED_SETTER_UNPROVEN`.

### Next gate

Senior architecture review and merge of the 9.13 guarded source batch. After merge, a separately
authorized 9.13 remote-plan; only after that plan review, a separately authorized apply plus GitHub
Production Environment reviewer approval; then configuration-level live verification. Do not wait
for a startup log from the future scale-to-zero revision. Checkpoint 9.14, ingress reopen, B1 G5,
and installing `pg_stat_statements` remain separately gated. Explicit authorization must come from
Vibhanshu/the repository owner. A GitHub Production Environment approval or Neon owner interaction,
if required by the chosen operation, is a platform execution gate and must be named separately.

Operational checkpoint 9.12 is complete. Top-level RCA verdict remains
`MECHANISM_REPRODUCED_SETTER_UNPROVEN`. Demo seed and diagnostics remain `false` on
`portfolio-service--0000091`. Live connection-origin verdict remains
`NOT_REPRODUCED_IN_MANUAL_MATRIX`. Statement-history live verdict remains
`STATEMENT_HISTORY_PROBE_EXECUTED_HISTORY_UNAVAILABLE`.
