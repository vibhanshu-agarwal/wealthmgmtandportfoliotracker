# B1 GC.5 Task 6 independent coverage review

Reviewer: Codex Task 6 independent source review  
Review date: 2026-09-08  
Decision: `ACCEPTED_REVIEWED`, conclusion `UNRELATED` for the exact residual subject named by
each policy record.

This artifact is evidence for the seven source shapes that the conservative analyzer reports as
`UNSUPPORTED`. It does not suppress any recognized operation in the same file. Each policy record
separately binds this artifact by SHA-256, the exact `(path, subject_id, obligation)`, the source
blob at the reviewed commit, and the production target-environment identity. A changed source blob,
artifact, subject, environment, or malformed/duplicate record fails closed.

The reviewer identity is self-declared rather than cryptographically authenticated. The owner
explicitly accepted that residual risk on 2026-09-08 for this narrow proof route.

## Reviewed subjects

### `GatewayAuthDataConfig` — `persistence-usage/file:`

The unaccounted `DataSource`, `NamedParameterJdbcTemplate`, and `TransactionTemplate` tokens are
dependency-wiring bean declarations. The class initializes a Hikari datasource, constructs the
JDBC/transaction wrappers, and injects them into the separately scanned auth repository/service.
It contains no SQL, repository mutation, statement execution, or table selection of its own.

### `PostMigrationIntegrityAssertion` — `persistence-usage/file:`

The residual `JdbcTemplate` marker is fully accounted by three separately enumerated
`queryForList`/`queryForObject` subjects. Every supplied statement is a literal `SELECT`; none uses
`CALL`, `DO`, `PERFORM`, `INTO`, a sequence operation, DML, or DDL. The independent proof applies
only to the file-level marker residual; the three operations retain their own effect-resolution and
disposition records.

### `DemoPortfolioInitializer` — `persistence-usage/file:`

The residual `Connection` marker belongs only to the `pgAdvisoryXactLock` callback. It prepares
`SELECT pg_advisory_xact_lock(?)`, binds the fixed lock key, and executes that read/lock statement.
The prepare and execute subjects retain separate effect-resolution/disposition records. The actual
portfolio convergence path is expressed through separately scanned repositories and seed service;
the residual marker does not hide an additional DML surface.

### `SpecA912ProvenanceDataSource` — `persistence-usage/file:`

The residual `PreparedStatement` marker is proxy/interface plumbing. The wrapper captures JDBC
read-only provenance and delegates the caller's original JDBC methods unchanged. Its own snapshot
queries are `SELECT pg_backend_pid()` and two `SHOW` statements. Reflection invokes the already
selected delegate method and does not construct a database mutation. The documented `unwrap`
blind spot can bypass instrumentation, but it neither issues SQL nor hides source operations in
callers, which remain independently scanned.

### `SpecA912ProvenanceDataSourcePostProcessor` — `persistence-usage/file:`

The residual `DataSource` marker is bean post-processing only. Depending on two configuration
flags, the method returns the original datasource or wraps it in the separately reviewed
`SpecA912ProvenanceDataSource`. It contains no SQL or statement execution.

### `SpecA912StartupTransactionDiagnostics` — `persistence-usage/file:`

The residual `Connection`, `DataSource`, and `Session` markers cover transaction metadata,
`SHOW`/`SELECT` diagnostic helpers, and Hibernate `doWork` wiring. The one actual DML surface,
`DELETE FROM portfolios WHERE FALSE`, is separately enumerated and dispositioned: the predicate is
constant false, it runs only in the explicitly gated diagnostic rollback probe, and it cannot
delete a row. The independent proof clears only the remaining file-level marker residual.

### `check_b2_demo_identity.py` — `writer-inventory/script:INSERT:3575989d`

`INSERT INTO users` occurs only as a regular-expression pattern and in diagnostic/error strings in
a standard-library source-alignment guard. The script opens the three source files as text, parses
their literals, compares UUIDs, prints a result, and exits. It imports no database client, opens no
connection, and executes no SQL. The exact script subject is therefore non-runtime SQL text rather
than a writer.

## Non-authorizations

This review does not authorize a Task 7 release build, registry push, exact-digest smoke,
workflow dispatch, deployment, cloud/secret access, live database access, or publication.
