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

---

## Current safe production state

| Boundary | Value |
|---|---|
| Active revision | `portfolio-service--0000086` |
| Traffic | 100% on revision `0000086` |
| `APP_DEMO_SEED_ON_STARTUP` | `false` |
| `APP_DEMO_TX_DIAGNOSTICS` | `false` |
| Demo portfolio | 1 portfolio, 3 holdings (unchanged from pre-enable baseline) |
| Checkpoint 9.12 | Incomplete — 11 of 14 cutover checkpoints complete |

### Data/non-interference baseline (after diagnostics disable)

| Data set | Verified state |
|---|---|
| Demo user `00000000-0000-0000-0000-0000000d3110` | 1 portfolio, 3 holdings, checksum `c976c71beb4f14d54d641fc5551b682123239a27b05c7f24c821331f71265d7d` |
| E2E user `00000000-0000-0000-0000-000000000e2e` | 1 portfolio, 159 holdings, checksum `544c1ffa057918bf65760f96fb1e241cffb9d8a96ce5cfbd291b7ad6381c480f` |
| `market_prices` | 160 rows, aggregate checksum `171085644570` |
| `market_price_history` | 15489 rows, aggregate checksum `16521852172235` |
| Kafka | `portfolio-group` and `insight-group` lag 0 at offset 24854 |

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

## Source instrumentation readiness (merged, undeployed)

**Merged source:** PR #172 at `main@9fbac4d2ada2240a980d5d7c9c2bd9dedc91de01`
(implementation commit `b51bb49a4c22a82c8ce557750b1f03ecd2cc0212`).

**Verdict (source only):** `PROVENANCE_INSTRUMENTATION_READY_SETTER_UNPROVEN`. Top-level RCA verdict remains **`MECHANISM_REPRODUCED_SETTER_UNPROVEN`**.

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

Source instrumentation readiness does **not** identify the production setter. No artifact was built,
no revision deployed, and production remains on `portfolio-service--0000086` with both flags
`false`. Architecture review and source merge are complete. Next step — only if separately
authorized — is diagnostic artifact construction followed by a gated diagnostic revision deploy.

---

## Explicit non-claims

- Checkpoint 9.12 is **not** complete.
- No production fix has been applied or validated.
- Native session poisoning is the **mechanism** reproduced locally; it is **not** claimed as the
  production root cause unless a repository-owned path that performs it is proven.

---

## Recommended next architecture question

Continue **production-shaped provenance investigation** to identify **who sets
`default_transaction_read_only=on` on the pooled Neon session** checked out by `portfolio-service`
at startup. Only after that setter is proven should architects design and review a narrow remedy with
explicit blast-radius analysis among candidate classes (transaction-local correction, pool checkout
sanitation, configuration correction). Do not apply Hikari `connection-init-sql`, URL `options=-c`,
or global session resets as an unreviewed workaround.
