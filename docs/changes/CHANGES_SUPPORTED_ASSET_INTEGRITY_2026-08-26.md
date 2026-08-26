# Changes Summary — Supported Asset Integrity (Spec A)

**Date:** 2026-08-26

**Previous changelog:** [`CHANGES_SUPPORTED_ASSET_INTEGRITY_2026-08-19.md`](CHANGES_SUPPORTED_ASSET_INTEGRITY_2026-08-19.md)

**Coverage:** Changes after `e9e8c5e` (the 2026-08-19 changelog) through Spec A checkpoint 9.10 at `e221662`; status re-verified on `main@18693d2`

**Spec:** `.kiro/specs/supported-asset-integrity/` (`requirements.md`, `design.md`, `tasks.md`)

**Scope:** Spec A production cutover, repair artifacts, enforcement activation, controlled refresh, and the deployment safeguards required to execute those changes safely.

---

## Summary

The August 19 changelog ended with implementation complete through wave 8, enforcement still dark,
and the PostgreSQL and Mongo repairs written but deliberately unmerged. Since then, Spec A moved
through production cutover checkpoints **9.1–9.10**.

The two repairs have now executed in production. PostgreSQL Flyway migrations V17–V19 removed the
legacy `BTC` data, migrated `MM.NS` to `M&M.NS`, preserved discarded history in the repair archive,
and passed the post-migration integrity assertion. The separately fenced Mongo repair migrated the
same ticker identity and reached a durable terminal `COMPLETE` state. Catalog enforcement is now
enabled on the three catalog-consuming services, and one controlled 159-asset refresh completed
with exact reconciliation across provider results, Mongo, Kafka, and PostgreSQL.

This is **not** the end of the maintenance window. The persisted refresh Job remains disabled,
the demo portfolio has not been activated, the three catalog services remain at one minimum
replica, and API gateway ingress remains closed. Checkpoints **9.11–9.14 are pending and
unauthorized**.

---

## Cutover Chronology

| Checkpoint | Date | Outcome |
|---|---|---|
| 9.1 — R1 deployed inert | 2026-08-22 | Three services loaded the same catalog with both enforcement gates `false`; Flyway remained at V16 |
| 9.2 — R2 producer narrowed | 2026-08-22 | Scheduled refreshes targeted exactly the 159 active assets |
| 9.3 — refresh suspended | 2026-08-22 | Persisted Job runner set to `false`; a manual fire took the scheduled path and emitted `refresh_suspended` |
| 9.4 — Kafka drained | 2026-08-22; rechecked 2026-08-23 | `portfolio-group` and `insight-group` both reached lag zero on every partition |
| 9.5 — writes quiesced | 2026-08-22 | API gateway ingress removed; external connection reset confirmed the write boundary was closed |
| 9.6 — R3a PostgreSQL repair | 2026-08-23 | V17–V19 applied; integrity assertion and six direct postcondition queries passed |
| 9.7 — R3b Mongo repair | 2026-08-23 | Fenced one-shot repair completed at generation 1; source removed, destination and archive verified |
| 9.8 — R4 deployed | 2026-08-23 | Defaults changed to enforcing; an ordering incident briefly left overrides absent, then recovery restored the intended effective state |
| 9.9 — enforcement enabled | 2026-08-23 | Overrides removed deliberately; all three services converged on one enforcing catalog tuple |
| 9.10 — controlled refresh | 2026-08-24 | One digest-pinned execution processed the full active set and reconciled cleanly end to end |

---

## Changes by Area

### Production preparation and quiescence — checkpoints 9.1–9.5

The cutover began by proving the previously shipped artifacts were inert and mutually consistent.
`portfolio-service`, `market-data-service`, and `insight-service` reported the same
`catalogVersion=c3dcb95e4e09212a`, 160 entries, 159 active assets, and both enforcement flags `false`.
The live PostgreSQL Flyway history stopped at V16, proving the repair migrations had not arrived
early.

The market-data producer was then verified to refresh the active catalog rather than the old
baseline-plus-Mongo emergent set. The persisted Container Apps Job configuration was changed to
`MARKET_DATA_JOB_RUNNER_ENABLED=false`; a deliberately started execution followed the same path as
the daily schedule and exited successfully with `refresh_suspended`.

Kafka was drained after the refresh fence was active. Topic `market-prices` had end offset 24541 on
its single partition, and both `portfolio-group` and `insight-group` had committed 24541. The result
was rechecked before enforcement activation using the new
[`SPEC_A_KAFKA_LAG_CHECK.md`](../runbooks/SPEC_A_KAFKA_LAG_CHECK.md) runbook.

Finally, Terraform removed API gateway ingress. Azure reported no ingress configuration, and an
external connection attempt reset rather than reaching the service. No synthetic seed invocation
was running. This established the quiescent boundary required before the irreversible repairs.

During the ingress change, the Terraform module's `app_fqdn` output was found to index
`ingress[0]` unconditionally. It was corrected to tolerate an ingress-disabled Container App, which
allowed the quiescence plan and apply to complete without inventing a placeholder FQDN.

### R3a — PostgreSQL V17–V19 repair

PR #135 rebased the held PostgreSQL repair work onto the then-current main branch and resolved four
merge conflicts plus two stale test defects. The repair shipped through a scoped
`portfolio-service` deployment as revision `portfolio-service--0000073`, image tag
`96a7e47b27154f013ac02f1cf360633f7d98a791`.

The production precondition used Neon point-in-time recovery with a six-hour rolling window and a
database proven unchanged throughout the quiescent interval. After Flyway applied V17–V19:

- `BTC` and `MM.NS` were absent from operational holdings, current prices, and price history.
- `repair_audit` contained exactly two rows: one for `BTC-USD` and one for `M&M.NS`, matching the
  pre-cutover holdings baseline.
- `repair_archive` contained 51 `LEGACY_SYNTHETIC` rows, preserving the fabricated BTC history
  removed from the operational table.
- The startup-bound `Post_Migration_Integrity_Assertion` completed, and its six SQL postconditions
  were independently executed against production with zero violations.

This was the first irreversible checkpoint. Its rollback is database restoration, not an
application redeploy.

### R3b — fenced Mongo `MM.NS` repair

PR #136 introduced a dedicated manual `market-data-repair-job`, user-assigned identity, and ACR
pull role. The Job was pinned to
`market-data-service@sha256:8548199f...`, configured for a single execution, and run only after
rechecking the PostgreSQL repair, closed ingress, disabled refresh writer, backup checksum, and
clean Mongo preflight.

Execution `market-data-repair-job-qz2v4rg` finished with
`outcome=COMPLETE generation=1 exit=0`. Independent live reads then proved:

- lease `repair_leases/mm-ns-repair` is durably `COMPLETE` at generation 1;
- `market_prices/MM.NS` is absent;
- `market_prices/M&M.NS` contains the exact former source tuple: price `2202.4540`, currency `INR`,
  timestamp `2026-06-19T08:51:01.627Z`;
- the destination has no residual repair-generation fence;
- exactly one committed archive record identifies `MIGRATE_SOURCE`;
- the execution used the intended pinned image digest.

The repair was not blindly retried. Its terminal state and durable evidence are documented in
[`SPEC_A_MONGO_REPAIR.md`](../runbooks/SPEC_A_MONGO_REPAIR.md).

### R4 activation and the checkpoint 9.8 ordering incident

PR #137 changed the three service artifacts so catalog enforcement defaults to `true`. The intended
sequence was to apply explicit Terraform `false` overrides first, verify them live, and only then
deploy the enforcing artifacts.

The actual sequence exposed a deployment-pipeline defect: merging a service-path change to `main`
automatically triggered deployment before the overrides existed. For roughly 29 minutes, all three
services ran with the new enforcing defaults unshadowed. API ingress was closed and refresh writes
were fenced throughout. Log Analytics found no rejected events, catalog failures, errors, or writes,
so no data-integrity impact was observed. A Terraform apply restored explicit `false` overrides and
the intended checkpoint 9.8 resting state.

The incident was recorded rather than rewritten into the planned chronology. It led directly to
the deployment-control changes below.

### Deployment pipeline hardening

PRs #138–#143 converted production deployment from an implicit consequence of merging into an
explicit, validated operation:

- removed the push-to-`main` deployment trigger;
- made `deploy-azure.yml` and `deploy-aws.yml` callable only through `deploy.yml`;
- required `deployment_mode` and `expected_main_sha`, failing closed on ref/SHA or mode mismatch;
- introduced a non-cancelling `production-deploy` concurrency group;
- routed every production deployment through a GitHub `production` Environment approval job;
- restricted that Environment to `main` and a required reviewer;
- added structural sole-caller tests covering `.yml`, `.yaml`, and quoted `uses:` forms;
- added pinned `actionlint` schema validation to the deploy workflow contract;
- hardened the Terraform workflow for live-state plans and exact image identity;
- ignored externally managed Static Web App repository metadata that otherwise caused irrelevant
  Terraform drift.

The bootstrap exercised a wrong-SHA rejection, approval-gate rejection, and queued-concurrency case.
All three remained inert, and the production snapshot before and after the exercise was
byte-identical. The deploy contract finished with 90 tests.

The GitHub Environment retains two known personal-repository properties: administrators can bypass
protection, and self-review remains allowed. The former is not configurable through the environment
API; the latter is a deliberate single-maintainer choice.

### Checkpoint 9.9 — enforcement enabled

PR #145 removed the temporary `false` overrides and raised `min_replicas` from zero to one for the
three catalog services in the same Terraform apply. API ingress remained closed and the refresh Job
remained disabled.

The apply changed three resources and triggered revision convergence:

- `portfolio-service--0000079`;
- `market-data-service--0000078`;
- `insight-service--0000078`.

Every active revision reported the same tuple:
`catalogVersion=a00b32ac0267e1a9`, `rejectUnsupportedEvents=true`, and
`enforceHoldingInvariant=true`. No override remained. There were no inconsistent tuples, catalog
load failures, DLT events, startup errors, or image-digest changes. Kafka was still drained at
offset 24541 for both consumers.

Only `portfolio-service` has behavior controlled by these flags today. The other two services emit
the flags in their startup identity line, so their tuple is still part of the cross-service catalog
proof, but Spec A did not add duplicate enforcement logic to them.

### Checkpoint 9.10 — controlled active-catalog refresh

Before the controlled run, PR #147 set the refresh Job's `replica_retry_limit` from 1 to 0 and added
a fail-closed full-template builder and verifier. This prevents Azure from automatically rerunning a
partially successful data-plane operation. The persisted Job remained disabled, and a subsequent
Terraform plan reported no drift.

One execution—`market-data-refresh-job-0i08hio`—was started from a complete sanitized template whose
only intended differences were the digest-pinned image and
`MARKET_DATA_JOB_RUNNER_ENABLED: false → true`. It succeeded in about 69 seconds:

- active assets: 159;
- updated: 154;
- skipped: 5;
- failed: 0;
- skipped tickers: `USDCAD=X`, `USDCHF=X`, `USDHKD=X`, `USDJPY=X`, `USDSGD=X` because the provider
  returned no price.

End-to-end reconciliation matched the execution exactly:

- Kafka advanced by 154 records, from 24541 to 24695;
- both consumer groups drained to 24695 with zero lag;
- Mongo contained exactly 154 documents updated in the execution window;
- PostgreSQL current-price and history tables each contained exactly 154 matching rows in the
  execution window;
- Mongo and PostgreSQL ticker sets matched the log-claimed set with no extras or omissions;
- all Mongo/PostgreSQL observation timestamps matched at millisecond precision;
- current-price and history tuples matched in PostgreSQL with no orphan rows;
- the DLT proof ruled out both new retained records and append-then-delete during the run;
- the off-catalog legacy `GOOG` document retained its pre-run `updatedAt`, confirming the
  catalog-driven refresh did not touch it.

Twenty-seven Mongo/PostgreSQL price representations initially appeared different; each was explained
by the intentional PostgreSQL `NUMERIC(19,4)` rounding boundary rather than a data-integrity defect.

After execution, the persisted Job still had retry limit 0, runner `false`, and its ordinary image
tag and schedule. Gateway ingress stayed closed, service revisions were unchanged, and no deployment
occurred. The durable evidence is in
[`SPEC_A_9_10_CONTROLLED_REFRESH.md`](../runbooks/SPEC_A_9_10_CONTROLLED_REFRESH.md).

---

## Production State After This Update

| Property | Current state |
|---|---|
| Canonical catalog | 160 entries, 159 active |
| Catalog identity on the three services | `a00b32ac0267e1a9` |
| Unsupported-event / holding enforcement | Enabled (`true`) with no Terraform override |
| PostgreSQL repair | V17–V19 applied and verified |
| Mongo `MM.NS` repair | Generation 1 `COMPLETE`, verified independently |
| Refresh Job persisted runner | `false` |
| Refresh Job retry limit | `0` |
| Controlled refresh | One successful execution; 154 updates + 5 provider skips = 159 active assets |
| Kafka | Both consumers drained to offset 24695 after the controlled run |
| Catalog-service scale | `min_replicas=1` |
| API gateway ingress | Closed |
| Demo portfolio activation | Not run |

---

## Verification and Evidence

| Claim | Evidence |
|---|---|
| R1/R2 identity and active-set behavior | Live startup logs and scheduled refresh summaries recorded at checkpoints 9.1–9.2 |
| Refresh fence | Terraform read-back plus successful `refresh_suspended` Job execution at 9.3 |
| Kafka quiescence | Direct consumer-group offsets at 9.4 and pre-9.9 recheck; [`SPEC_A_KAFKA_LAG_CHECK.md`](../runbooks/SPEC_A_KAFKA_LAG_CHECK.md) |
| PostgreSQL repair | Startup-bound assertion, six direct SQL postconditions, two audit rows, 51 archive rows |
| Mongo repair | Terminal lease, exact destination tuple, one committed archive, pinned execution digest |
| 9.8 exposure impact | Log Analytics negative searches plus independently closed ingress and refresh fence |
| Pipeline hardening | 90 contract tests, pinned `actionlint`, inert wrong-SHA/approval/concurrency bootstrap runs |
| Enforcement | Three converged revisions with one enforcing catalog tuple and unchanged image digests |
| Controlled refresh | Exact 154-record reconciliation across logs, Kafka, Mongo, and both PostgreSQL projections |

Primary production evidence remains in
`.kiro/specs/supported-asset-integrity/tasks.md` and the three linked runbooks. Raw checkpoint 9.10
artifacts remain local under the gitignored `.artifacts/spec-a-9.10/` directory and are not part of
the repository record.

---

## Known Gaps and Follow-ups

- **9.11 — persist refresh enablement:** pending and unauthorized. Terraform still stores
  `MARKET_DATA_JOB_RUNNER_ENABLED=false`.
- **9.12 — activate the demo portfolio:** pending and unauthorized. It must run while the services
  are held at one minimum replica and ingress remains closed, followed by a second rollout that
  disables the initializer again.
- **9.13 — restore scale-to-zero:** pending and unauthorized. Verification must inspect revision
  configuration rather than rely on a startup log from a replica that may never wake.
- **9.14 — reopen ingress:** pending and unauthorized. This is the final public reopening gate and
  depends on 9.9–9.13 remaining green.
- **Task 8.8 remains unchecked in the authoritative ledger.** Fixed-size catalog assertions must not
  be reintroduced; live checks must derive the active cardinality.
- **Legacy Mongo `GOOG` remains outside the catalog.** It is a separate Alphabet share-class orphan,
  not an alias for catalog ticker `GOOGL`; checkpoint 9.10 deliberately left it unchanged.
- **`TATAMOTORS.NS` remains deprecated without an automatic successor allocation.** The corporate
  action needs an explicit product/accounting decision and is not repaired away by Spec A.
- **Production Environment limitations remain documented.** Administrator bypass is available, and
  self-review is allowed for this single-maintainer repository.

---

## Guardrails Preserved

- The two irreversible repairs were executed only after refresh suspension, Kafka drain, ingress
  closure, and recovery evidence.
- Discarded PostgreSQL history was archived rather than silently deleted.
- The Mongo repair used a fenced, terminal, single-execution state machine and was not blindly
  retried.
- Production deployment no longer runs automatically on merge.
- The controlled refresh used a complete validated template, a pinned digest, and zero automatic
  retries.
- Persisted refresh enablement was not changed by checkpoint 9.10.
- Gateway ingress remained closed throughout repair, enforcement activation, and controlled refresh.
- No frontend product behavior or Asset Picker UI was introduced by Spec A.
- `portfolio-service` retained the invariant that its seed path never writes market prices.
- Deprecated holdings were retained; no corporate-action allocation was guessed.

---

## Merge and Execution Trail

| PR | Purpose |
|---|---|
| #133 | Suspend the market-data refresh Job (9.3) |
| #134 | Close API gateway ingress (9.5) |
| #135 | Rebase, ship, and execute PostgreSQL V17–V19 repair (9.6) |
| #136 | Add and execute the fenced Mongo repair Job (9.7) |
| #137 | Ship R4 enforcement defaults (9.8) |
| #138–#140 | Record the 9.8 chronology, harden deployment, and record bootstrap evidence |
| #141–#143 | Harden live-state Terraform operations and close workflow/path/drift defects |
| #144 | Add reproducible Kafka lag verification evidence |
| #145–#146 | Enable enforcement and record checkpoint 9.9 completion |
| #147–#148 | Disable automatic refresh retry, execute checkpoint 9.10, and record reconciliation evidence |

---

## What Ships Next

The next change is not another repair. Checkpoint 9.11 persists normal refresh execution through
Terraform, after which checkpoint 9.12 activates and verifies the demo portfolio while the services
are guaranteed awake. Checkpoint 9.13 restores scale-to-zero, and checkpoint 9.14 reopens ingress.

These remain four separate stop/go decisions. This changelog records completed work through 9.10;
it does not authorize any of them.
