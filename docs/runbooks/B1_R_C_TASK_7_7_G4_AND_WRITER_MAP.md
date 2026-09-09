# B1 R-C Task 7.7 — G4 Evidence Protocol and Writer Map

> [!IMPORTANT]
> ## OWNER APPROVAL RECORDED — RECOVERY EXECUTED TRANSPARENTLY
>
> The first bounded execution correctly stopped when runtime identity was unattested and
> `insight-group` was absent. The owner subsequently authorized immutable-digest replacement and
> transparent retained-range recovery as remaining Task 7.7 work. Final results are recorded in the
> serving-evidence runbook and machine-readable completion record.

**Status:** PROTOCOL DESIGN AND FINAL WHOLE-PACKET EVIDENCE INDEPENDENTLY ACCEPTED; G4 PASS.
The original zero-mutation protocol failed closed. The recovery phase is separately disclosed and is
not represented as proof of pre-existing group offsets. The
writer mapping closes the documentation comparison between the Task 7.6 candidate inventory and
R-B3r source; fresh G0a/G2a/G2b now complete the derived G6 conjunction.

**Serving cut:** `portfolio-service--0000095` /
`wealthprodacr.azurecr.io/portfolio-service@sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552`,
source `97b83e52bcbfface4377cefd69ebb111270cb21e`.

**Candidate cut:** `8f1e8a36f8baa594efa8079190f87b42139fcf10` /
`wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`.

The current observations to which this protocol applies are recorded in
[`task-7-7-serving-gates-20260908.json`](../evidence/b1-r-c/task-7-7-serving-gates-20260908.json).

**Independent review:** Astra issued **ACCEPT** on 2026-09-08 with no Critical, Important or Minor
findings after two correction rounds. The review reproduced all eight enforcement/test blobs, the
four surviving candidate-policy production-writer blobs, the gateway parent-only insertion and the
serving-to-candidate source delta. It confirmed that both enforcement paths are covered and that
startup-line completeness, runtime identity and Kafka lag/DLT predicates correctly remain
unverified. This verdict accepts the documentation design only—not live evidence, execution,
Writer_Convergence, Task 7.7 completion or Task 7.8.

## 1. Architectural decision

R-B3r has no supported live request that can carry an arbitrary holding:

- `PortfolioSeedController` and `DemoResetController` accept only `expectedVersion`; their desired
  holdings are derived from the active catalog.
- `SupportedAssetValidator` exists as a bean but has no R-B3r production caller.
- `CompositionController` and `CompositionWriteService`, which accept public composition intent,
  are candidate-only and are absent from R-B3r.
- the Kafka consumer does not write `portfolios` or `asset_holdings`; its separate
  `rejectUnsupportedEvents` behavior protects the market-price projection tables.

A live unsupported-holding rejection cannot therefore be demonstrated without adding/restoring a
route, altering a fixture, directly mutating the database, producing a synthetic Kafka event, or
injecting an ad hoc runtime harness. Each would change the system being proved or expand production
scope. None is part of this protocol.

G4 will use the non-mutating evidence-equivalence contract already accepted for Spec A checkpoint
9.9: exact behavioral tests for the enforcement code, an artifact/source join to the serving cut,
and current read-only proof that the same catalog and effective enforcement configuration are live.
This is not a waiver. Every join below is mandatory, and any unresolved join leaves G4 open.

## 2. G4 zero-mutation protocol

### 2.1 Mutation budget and targets

| Item | Contract |
|---|---|
| Production mutation budget | Exactly zero rows, messages, offsets, revisions, replicas, configuration values and traffic weights changed |
| HTTP payload | None; no rejection request exists on R-B3r and none may be invented |
| Behavioral targets | R-B3r `HoldingReplacementService` validation boundary, which invokes `CompositionCatalogValidator` before CAS or child DML; and `MarketPriceProjectionService`, which rejects unsupported/mismatched price events before price-table DML |
| Serving-state targets | The complete current portfolio, market-data and insight serving sets; exact revisions and nonzero traffic destinations |
| Data-state target | One `REPEATABLE READ READ ONLY` PostgreSQL snapshot |
| Log target | Exact-revision, fixed-window catalog/startup and failure-marker queries |
| Expected outcome | Every predicate in §2.2 is green; otherwise `G4=OPEN` |
| Cleanup | None should be necessary. Any observed mutation is an invalidator, not something this protocol authorizes the collector to repair |

### 2.2 Required predicates

G4 is accepted only when all predicates are true in one reviewed packet:

1. **Complete serving set.** Enumerate every active revision, nonzero traffic destination,
   revision-label route and otherwise addressable revision for portfolio, market-data and insight.
   Re-read the same set after collection and stop on drift.
2. **Runtime identity.** Attest the already-running bytes of every enumerated revision to an
   immutable digest. Resolving a mutable registry tag at collection time is insufficient. Portfolio
   `0000095` is already configured by digest; the current market-data and insight observations do
   not yet satisfy this predicate.
3. **Serving source join.** Bind the R-B3r portfolio digest to source `97b83e52...`. Bind each other
   consumer digest to its reviewed source/catalog input. Do not infer source identity from a tag.
4. **Behavioral code identity.** At R-B3r source and candidate cut, require the following identical
   Git blobs:

   | Subject | Required blob |
   |---|---|
   | `CompositionCatalogValidator.java` | `a811017338120d4ea2307139923c6bd93fde63a9` |
   | `HoldingReplacementService.java` | `48460fc52a1a697cbd88384096e85211a1e0c275` |
   | `MarketPriceProjectionService.java` | `27692c486dc62bf8763209eaca3e96cc5a326cdc` |
   | `PostMigrationIntegrityAssertion.java` | `b42d3e44b3b596a328c4ad4caa6a9e764a7d595f` |
   | `CompositionCatalogValidatorTest.java` | `8368c3286fad16e3dd0aba3a5d5798528f2537f9` |
   | `HoldingReplacementServiceIT.java` | `6884e19d36f6350c9ab9875dae67306668478712` |
   | `MarketPriceProjectionCurrencyTest.java` | `e2c2dda3110f06017e571e82737eb25807e9a38e` |
   | `PostMigrationIntegrityAssertionTest.java` | `1beda3a05ca804cc375e5d1d89e9ef9f800b3ded` |

5. **Behavioral result.** Revalidate the candidate Task A evidence for those exact test blobs. The
   minimum rejection evidence is:
   - unknown tickers are rejected by `CompositionCatalogValidatorTest` with catalog identity;
   - `HoldingReplacementServiceIT.invalidDesiredSetLeavesAbsentUserWithoutBarePortfolio` rejects an
     unsupported holding and proves that no portfolio was created;
   - `MarketPriceProjectionCurrencyTest` proves that, with event rejection enabled, an unknown
     ticker, unresolvable currency, or catalog currency mismatch raises `RejectedPriceEventException`
     and never invokes price-table `JdbcTemplate.update`;
   - the complete Task A result is successful and bound to candidate `8f1e8a36...`.
6. **Effective live configuration and complete startup-line coverage.** Query every
   `catalog_loaded` line for each exact current consumer revision over a fixed window covering that
   revision's full lifetime; do not reduce the proof to `arg_max`. Join the result to every observed
   replica and require no observed replica without a startup line. Per revision, require
   `StartupLines >= 1` and `TupleCount = 1`, where the sole tuple has the same catalog version,
   entry/active counts, `rejectUnsupportedEvents=true`, and
   `enforceHoldingInvariant=true`. Every line across all three consumers must agree. A line from a
   superseded revision or a latest-line-only query is invalid evidence for this predicate.
7. **Startup and database enforcement.** The current portfolio revision must have no
   `post_migration_integrity_failed` marker. The read-only database snapshot must show all relevant
   constraints valid, no out-of-domain holding, no holding outside the active catalog, V21 applied
   exactly once with checksum `385711525`, and all four retired repair routines absent.
8. **Bounded logs and consumer steady state.** Fixed, retained query text must show no catalog-load
   failure, post-migration-integrity failure or unexpected enforcement error for the exact revisions
   over the declared window. Separately, use reviewed metadata-only Kafka commands against exact
   topic `market-prices` and groups `portfolio-group` and `insight-group`: enumerate every partition
   and require each group's committed offset to equal that partition's log-end offset with lag zero.
   Anchor every `market-prices.DLT` partition's log-end offset before the observation window and
   re-read it after the consumer-drain check; every end offset must be unchanged. Kafka end offsets
   are monotonic, so equal before/after values prove no append even if retention advances the start
   offset. Do not consume payloads, join a consumer group, commit/reset offsets or produce a record.
   Application logs and a successful refresh job cannot substitute for these topic/group predicates;
   a stalled consumer can otherwise produce no error marker. Refresh execution/state must remain
   consistent with the catalog and must not be used as a substitute for either rejection behavior.
9. **Independent review.** A reviewer other than the collector must reproduce the source/blob map,
   inspect the exact predicates and evidence, and issue an explicit ACCEPT. Ambiguity is a STOP, not
   a human waiver.

### 2.3 Failure handling and invalidators

Stop without changing state when any required artifact is missing, a mutable tag cannot be joined
to already-running bytes, a query is inconclusive, or logs have not arrived. G4 remains open.

Invalidate the affected evidence and restart collection from the complete serving-set snapshot if
any revision, traffic destination, label route, image reference/digest, catalog tuple, effective flag,
source/test blob, migration state or relevant database invariant changes. Any unplanned request that
can write, any Kafka production, any database DML/DDL, or any configuration/deployment action
invalidates the zero-mutation claim. This protocol grants no rollback or cleanup authority.

### 2.4 Earlier pre-execution protocol evaluation

This is the 2026-09-08 snapshot before the authorized execution in §2.5. Those observations satisfy
the portfolio digest/source join, code/test blob equality,
latest-observed catalog tuples, V21/repair state, relational invariants, refresh state and bounded
marker checks. The retained catalog query uses `arg_max`, so it does not establish complete
startup-line/replica coverage or `TupleCount = 1`. The observations also do **not** attest the
already-running market-data and insight bytes because those revisions are configured with mutable
tags. Finally, no current all-partition lag-zero or bounded DLT non-growth evidence was collected;
application marker counts do not establish consumer steady state. These predicates require
separately approved read-only recollection. Therefore this design does not change the current result:
`G4=PARTIAL_CURRENT_OBSERVATIONS_GREEN` and Task 7.7 remains open.

### 2.5 Authorized execution result

The later owner authorization reached the read-only gates only. A fixed-window query covered all
nine replicas observed for the exact three consumer revisions, with one startup line and one
identical true/true catalog tuple per replica. DLT partition 0 did not grow (`80 → 80`) and
`portfolio-group` was at `26602/26602`, lag zero. The same reviewed metadata-only command returned
`GroupIdNotFoundException` for required group `insight-group`.

Runtime identity also remains independently unverified for gateway, market-data and insight. The
observed pull byte counts equal current OCI manifest/config/layer byte sums, but the tags are
write-enabled and independent review rejected byte-length/timestamp equality as immutable content
attestation. Only portfolio is configured by digest.

The group absence fails predicate 8. It cannot be repaired inside this evidence protocol: starting or
waking the consumer would join a group and could create/commit new offsets, while the predicate is
about the pre-existing committed position. No signup, authenticated read, legacy-route POST, seed,
database mutation or Kafka state change was attempted. G4 remains open and the downstream Task 7.7
write-bearing proofs were correctly skipped. Sanitized evidence is in
[`task-7-7-authorized-execution-20260908.json`](../evidence/b1-r-c/task-7-7-authorized-execution-20260908.json).

## 3. Task 7.6 candidate inventory → R-B3r serving map

The Task 7.6 candidate inventory is the source of subjects. The serving comparison uses the exact
R-B3r source, not the later candidate tree.

| Candidate inventory subject | R-B3r status | Portfolio_Version participation | Serving disposition |
|---|---|---|---|
| `HoldingReplacementService` | Present; identical blob `48460fc5...` | Defines the mechanism: new portfolio insertion, parent version CAS, then same-transaction child delete/insert | Only effective portfolio-service holdings DML mechanism |
| `CompositionWriteService` | Absent; candidate-only blob `3d4f5b46...` | Candidate delegates completely to `HoldingReplacementService` | No R-B3r serving participation |
| `CompositionHoldingsRequest` | Present but changed from serving blob `706e9d5d...` to candidate blob `a783dcf6...` | None; DTO validation only | Candidate adds null-element validation; no DML and no change to an R-B3r writer mechanism |
| `PortfolioSeedService` | Present; identical blob `219a504c...` | Passes caller-supplied `expectedVersion` unchanged to `HoldingReplacementService` | Serving delegate; desired state is active-catalog-derived |
| `DemoResetService` | Present; identical blob `01730812...` | Passes caller-supplied `expectedVersion` to `HoldingReplacementService` | Serving delegate; fixed demo identity and catalog-derived desired state |
| `DemoPortfolioInitializer` | Present; identical blob `c748bbad...` | Freezes its observed version, then calls `PortfolioSeedService`; diagnostics probe rolls back and never seeds | Serving automatic caller when enabled; no second writer mechanism |
| `CompositionController` | Absent; candidate-only blob `7864e2c2...` | Candidate caller supplies public intent and expected version | No R-B3r route or serving participation |
| `Portfolio.replaceAllHoldings` | Present but zero call sites | None while unreachable | Dead code, not a current writer; re-open inventory if a caller appears |
| V3/V7/V15/V18/V19/V20 statements | Historical migrations | One-time schema/data transitions, not a runtime CAS path | Retained as historical writers, not serving application writers |
| V17 repair functions | Historical persistent helpers | Not a runtime Portfolio_Version participant | Retired by V21; current database proves global absence |
| V21 | Present in R-B3r | Drops four functions; does not write holdings | Forward-only retirement postcondition |
| `ON DELETE CASCADE` from `portfolios` to `asset_holdings` | Present structural mechanism | No version gate of its own | Explicit residual risk; current production source has no portfolio delete caller |
| diagnostics `DELETE FROM portfolios WHERE FALSE` | Present, diagnostics-gated | Matches zero rows and enclosing probe rolls back | Classified non-writer |
| API-gateway `UserCredentialRepository` portfolio insert | Present; identical blob `25fe4c3e...` | Creates a version-0 portfolio during signup; never writes `asset_holdings` | Outside Task 7.6's holdings-writer quantifier; remains governed by G2/G3 |
| market-price, market-data and insight matches | Present outside table scope | None for portfolio holdings | Other table/store; excluded only after explicit classification |

The R-B3r-to-candidate source delta adds the public controller and delegating write service and
changes the request DTO's null-element validation, but does not change any serving writer mechanism
above. Candidate and R-B3r share the four production-writer
blobs from the candidate policy that survive in serving source. All reachable R-B3r
portfolio-service holdings writes converge on `HoldingReplacementService`; none of the
candidate-only entrypoints is implied to be serving. API-gateway signup separately inserts the
version-0 parent portfolio and is not misclassified as an `asset_holdings` writer.

## 4. G6 boundary

This map supplies the Task 7.6 inventory-to-serving-cut comparison. The final Task 7.7 collection
adds fresh G0a, G2a and G2b evidence, so the derived G6 conjunction is technically green. The ungated
FK cascade remains a documented structural risk even though no current source caller exercises it.
Astra independently ACCEPTed the final whole packet with no findings. Task 7.8 remains a separate
owner STOP/GO and no Writer_Convergence claim is made.

Final results are in
[`B1_R_C_TASK_7_7_SERVING_EVIDENCE.md`](B1_R_C_TASK_7_7_SERVING_EVIDENCE.md) and
[`task-7-7-completion-20260909.json`](../evidence/b1-r-c/task-7-7-completion-20260909.json).
