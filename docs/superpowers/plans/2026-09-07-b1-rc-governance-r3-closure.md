# B1 R-C Governance and R3 Closure Plan

> ## OWNER APPROVAL CALLOUT — later execution decisions are required
>
> **Current authorization:** the owner's 2026-09-07 direction to proceed with the next task covers
> this local architecture review, the two local source-governance scans recorded below, and creation
> of this plan. It does not authorize production or secret access.
>
> **Decision 1, requested only after Tasks 1–3 are implemented and independently reviewed:** authorize
> build/push of one portfolio-service remediation image, access to the live Azure/Neon configuration,
> and a scoped production deployment of the exact reviewed digest. If approved, the deployment applies
> the forward V21 cleanup while retaining R-B3 behavior. If declined or deferred, production remains
> safely at R-B3 and R-C remains NO-GO.
>
> **Decision 2, requested only after the remediation serving proof and source-governance PASS:**
> authorize the distinct R-C release-candidate build/push and exact-digest smoke. Deployment of R-C,
> rollback authority, and the pre/post-deploy GO decisions remain later, separate decisions.
>
> **Not authorized by this plan:** reading Azure or GitHub secret values, connecting to a live
> database, applying DDL, building or pushing a release image, dispatching a workflow, deploying,
> changing traffic, rollback, publishing this branch, opening/merging a PR, or checking any 7.x,
> GC.5, AM.1/AM.2, or Writer_Convergence completion box.

**Status:** architecture plan prepared; no implementation or operational action performed. R-C is
**NO-GO**.

## 1. Outcome and recommendation

The earlier R3 question is no longer an evidence gap. The accepted 2026-09-04 preflight found the
four V17 repair functions present in the inspected Neon database, owned by `neondb_owner`, executable
by `PUBLIC`, and callable by the inspected owner credential. The finding is positive for that
database/credential pair, while the relationship to the currently deployed application's effective
credential remains name-based rather than value-proven.

The recommended remediation is a **forward-only V21 migration that drops all four transient repair
functions**, followed by a **separate R-B3 remediation release** before the R-C candidate is frozen.
Do not solve R3 with a policy exception, by editing V17–V19, by changing a function to
`SECURITY DEFINER`, or by revoking only `PUBLIC` execution.

The functions are migration helpers, not application APIs:

- `repair_migrate_holdings(text,text,text)`;
- `repair_migrate_market_prices(text,text,text,text,boolean)`;
- `repair_migrate_history(text,text,text)`; and
- `repair_archive_row(text,text,text,text,jsonb)`.

V18/V19 are their only tracked callers. On a fresh database Flyway executes V17, then the historical
V18/V19 calls, then V20, and finally V21 removes the now-unused helpers. On the existing database V21
removes the same objects after V18–V20 have already succeeded. Dropping all four closes the common
PUBLIC-executable repair surface rather than leaving three equivalent helpers behind.

Use exact, schema-qualified signatures and `RESTRICT`. PostgreSQL requires the input argument types
to identify an overloaded function, and only its owner may drop it. `RESTRICT` makes an unexpected
dependency fail closed instead of cascading into another object:

```sql
DROP FUNCTION IF EXISTS public.repair_migrate_holdings(text, text, text) RESTRICT;
DROP FUNCTION IF EXISTS public.repair_migrate_market_prices(text, text, text, text, boolean) RESTRICT;
DROP FUNCTION IF EXISTS public.repair_migrate_history(text, text, text) RESTRICT;
DROP FUNCTION IF EXISTS public.repair_archive_row(text, text, text, text, jsonb) RESTRICT;
```

The order is intentional: drop the three callers before `repair_archive_row`, which they invoke.
The implementation owner must confirm the catalog dependency graph in an ephemeral database rather
than trusting that source ordering alone is sufficient.

## 2. Reconciled current state

### 2.1 Publication history

- PR #221 merged the architecture/kickoff at `ebb96f3a`.
- PR #222 merged the R-C candidate tooling at `9c3add3c`.
- PR #224 merged the GC.5 coverage correction and accepted R3 preflight at `f17c9029`.
- PR #225 deliberately parked the full governance review until the Asset Picker production-E2E
  trigger, without classifying or clearing any finding.
- The old assigned worktree `codex/taskc-sql-comment-guard` contains historical dirty/untracked
  copies of files that are already on `main`; it must not be used for new work or cleaned as part of
  this task.

### 2.2 Fresh local scan at current main

The merged fail-closed analyzer was run locally from a clean checkout:

```powershell
python -X utf8 -B scripts/check_b1_candidate_source.py `
  --repo . `
  --mode LOCAL_PREPARATION `
  --out .candidate-artifacts/rc-current-main-governance.json
```

Evidence identity:

| Field | Value |
|---|---|
| B1 base | `95fcb68dc7a47f99465354ec6d7b84137851389d` |
| Current source cut | `a52ec1ef45f9627a2795357f0ac5a31f087ca082` |
| Changed paths / tracked files | 531 / 1743 |
| Analyzer SHA-256 (checkout bytes) | `9b03c3ea0d423f7c1cfc00122682576e423abb163870286869cb9c253b801b24` |
| Policy SHA-256 | `78bf8596d90761791028549db54db48db0865f103f07dd8fb84ebde1fb0f29c0` |
| Report SHA-256 | `282aff41ae66c0409a98d811e11703298c85e6da0c0436873d96ea0d4383e203` |
| Result | `BLOCKED`; `candidate_ready=false` |

Current queue:

| Obligation | State | Count |
|---|---|---:|
| Content governance | `CONFIRMED_MATCH` | 189 |
| Path governance | `CONFIRMED_MATCH` | 87 |
| Path governance | `UNREVIEWED` | 153 |
| Writer inventory | `UNREVIEWED` | 73 |
| Writer inventory | `UNRESOLVED` | 25 |
| Writer/persistence coverage | `UNSUPPORTED` | 7 |
| Per-holding state | `UNREVIEWED` | 9 |
| Deployable envelopes | `UNREVIEWED` | 4 |
| R3 policy | `UNRESOLVED` | 1 |
| **Total** | 276 confirmed, 239 unreviewed, 26 unresolved, 7 unsupported | **548** |

The same evaluator reproduced the preserved PR #224 cut at exactly 508 findings. Current main has
55 added and 15 removed finding identities (net +40): 36/15 are content subjects whose line-based
identities changed or were added, 3 are confirmed paths, and 16 are unreviewed paths. There is **no
writer, unsupported-coverage, per-holding, envelope-count, or R3-count delta**. The api-gateway
envelope grew from 69 to 200 members; the other envelope membership counts remain 86, 77, and 145.

This is a provisional current-main inventory, not the final candidate evidence. The scan must be
repeated after V21 and all other selected source work land, at the immutable candidate cut.

## 3. Why remediation precedes bulk governance review

The backlog's existing order puts coverage and effect review before R3. For execution, R3's **source
change** must move earlier because adding V21 changes the portfolio-service migration subset and
envelope. If reviewers author envelope records and dispositions first, V21 invalidates those records
and forces avoidable renewal work.

The order is therefore:

1. design and test V21 locally;
2. independently review V21 and the R-B3 remediation cut;
3. deploy and prove V21 under a separately authorized remediation packet;
4. freeze the final R-C source cut;
5. complete unsupported coverage, effect resolution, dispositions, per-holding records, and final
   envelopes against that cut;
6. run the source guard in `CANDIDATE` mode and require `PASS`;
7. only then request the R-C build/push/smoke decision.

This pulls only R3 remediation design ahead of the owner-deferred review queue. It does not claim the
Asset Picker production-E2E trigger is met, and it does not waive the full GC.5 backlog.

## 4. Implementation plan for the next agents

### Task 1 — Specify the V21 contract in tests

**Files:**

- Modify: `portfolio-service/src/test/java/com/wealth/portfolio/repair/PostgresRepairMigrationIT.java`
  or the closest existing migration harness after inspecting it.
- Modify only if necessary: `portfolio-service/src/test/java/com/wealth/portfolio/FlywayPreservationTest.java`.

Required failing tests before the migration is added:

1. A full fresh migration ends at V21 with all four exact `to_regprocedure(...)` values null.
2. The fresh migration still proves the V18 BTC and V19 MM.NS data postconditions; absence after V21
   must not replace the historical behavior assertions.
3. An upgrade fixture stopped at V20 proves all four functions exist, then migrating to V21 proves
   all four are absent and `flyway_schema_history.version='21'` is successful exactly once.
4. A dependency negative creates an object depending on one helper and proves V21 fails rather than
   cascading; clean up only the disposable test database.
5. Re-running Flyway after V21 is a no-op and all existing holdings/version constraints remain.

Do not test by editing historical migration checksums or by applying V21 to any shared database.

### Task 2 — Add the forward migration

**File:**

- Create: `portfolio-service/src/main/resources/db/migration/V21__Drop_Transient_Repair_Functions.sql`.

Constraints:

- only the four `DROP FUNCTION IF EXISTS ... RESTRICT` statements shown in §1, with explanatory
  comments;
- no DML, no table/column/constraint changes, no privilege/role changes, no `CASCADE`;
- do not alter V17, V18, V19, or V20;
- preserve one byte-identical V21 file for both the R-B3 remediation branch and final `main` so
  Flyway checksums cannot diverge.

### Task 3 — Prepare two immutable source cuts

The deployment lane needs two distinct cuts:

1. **R-B3r remediation cut:** start from the exact last safe R-B3 source cut recorded by the accepted
   G2b packet, add only the reviewed V21 commit and any test-only files excluded from the image.
   Verify by diff that the public `CompositionController` and all later runtime changes are absent.
2. **R-C candidate cut:** current `main` after the exact same V21 bytes and all accepted source work.
   Do not derive this cut until source scope is frozen.

The V21 implementation commit should be isolated so it can be cherry-picked without hand-copying.
If cherry-pick changes the migration blob, stop. The migration's Git blob identity and SHA-256 must be
identical in both histories.

Required local evidence for R-B3r:

- clean worktree and exact source SHA;
- diff from the accepted R-B3 cut contains no runtime source other than V21;
- candidate migration tests pass;
- complete portfolio-service test and integration-test tasks pass;
- local image packages the reviewed bytes;
- route inventory proves `PUT /api/portfolio/holdings` remains absent;
- generated manifest identifies the expected Flyway head as V21.

Stop after local evidence and independent review. Commit/push/PR and release operations keep their
separate owner gates.

### Task 4 — Prepare the owner-gated R-B3r execution packet

The packet must name one exact commit, JAR hash, ACR manifest digest, platform, current R-B3 serving
digest/revision, and rollback digest. It must request the following as one bounded operation:

1. build and push the R-B3r image once;
2. read the **effective live portfolio-service datasource secret references and values into an
   ephemeral process only**;
3. connect using those effective values, recording no secret value;
4. preflight the target server/database, current/effective role, V17–V20 history, four function OIDs,
   function owners/ACLs, and dependency graph;
5. deploy the exact R-B3r manifest digest to portfolio-service only;
6. verify V21 in Flyway history, all four `to_regprocedure` results null, and ordinary R-B3 read/seed
   contracts still pass while the public composition route remains unavailable;
7. bind revision, digest, traffic, server/database identity, query/result artifact hashes, operator,
   time, reviewed commit, and migration-subset digest into the operational record.

The secret-handling helper must emit only non-secret identity fields and equality/validation booleans.
No connection string, username credential payload, password, token, or environment dump may enter
logs or committed evidence.

Abort rules:

- preflight mismatch or dependency: do not deploy;
- V21 not applied or any function still present: do not advance traffic/GO;
- application failure after V21 applies: redeploy the recorded R-B3 digest. V21 remains forward-only;
  the older application must be verified to start and serve with the helpers absent;
- never recreate a repair function during rollback and never roll below the accepted R-B3 floor.

### Task 5 — Close R3 in policy only after live proof

**Files:**

- Modify: `scripts/b1-candidate-policy.json`.
- Add: immutable query/result evidence under a reviewed, non-secret evidence path.
- Modify: the R-C runbook and governed status documents.

After Task 4 succeeds and is independently accepted:

- add a schema-valid operational record bound to the exact production environment identity, evidence
  hashes, reviewed commit, and portfolio-service migration-subset digest;
- remove the top-level R3 unresolved entry only in the same reviewed change that cites the successful
  V21 proof;
- retain V17–V19 as historical migration subjects with their ordering and postconditions;
- do not classify the old positive-risk preflight as proof of remediation;
- run analyzer regression tests and verify a new migration or changed migration subset invalidates a
  stale operational record.

R3 is closed only when both source lifecycle and observed production state agree: V21 is present in
the immutable candidate history, V21 is successful in the target database, and all four functions are
absent there.

### Task 6 — Complete the remaining GC.5 queue at the final cut

Resume `docs/todos/backlog/gc5-governance-review-post-asset-picker-e2e/README.md`, adjusted only for
the final cut and the new api-gateway envelope:

1. design supported coverage or contract-valid independent proof for the seven `UNSUPPORTED` rows;
2. resolve the 25 receiver/store/table effects;
3. review the 276 confirmed and 239 unreviewed current subjects in provenance families, regenerating
   first if the final cut differs;
4. review nine per-holding-state families;
5. author all four envelope records last, after their membership is frozen;
6. rerun `LOCAL_PREPARATION`, reconcile every semantic delta, then rerun from the exact cut in
   `CANDIDATE` mode with external Task A/B inputs;
7. require `source_governance_status=PASS`, no unresolved/unsupported/unreviewed residue, valid
   envelopes, and no unverified coverage before 7.6/G6 can be accepted.

An ordinary disposition may not clear `UNRESOLVED` or `UNSUPPORTED`. “Already merged,” “belongs to
B2,” and “test-only” are not dispositions unless the policy contract and exact subject evidence make
them valid.

### Task 7 — Resume release evidence, still stopping before deploy

Only after Tasks 1–6 pass:

- freeze/tag cut-C and record AM.1/AM.2 evidence;
- execute the single Task 7.3 candidate build under explicit owner authorization;
- push once and resolve the ACR manifest/platform digest;
- run the exact-digest HTTP smoke with `--authorized-release-run`;
- recollect pre-deploy serving G2/G3/G4/G6 evidence;
- prepare the 7.8 pre-deploy STOP/GO packet.

Task 7 ends at the owner decision. It does not deploy R-C.

## 5. Review checkpoints

Checkpoint A — V21 design:

- exact signatures and `RESTRICT` verified against an ephemeral catalog;
- V18/V19 fresh-install behavior retained;
- no application/runtime source change;
- rollback-with-V21-applied proven locally.

Checkpoint B — R-B3r release packet:

- safe cut contains V21 but not Wave 7 activation;
- exact digest chain and rollback digest complete;
- live secret/database access called out explicitly;
- no action taken before the owner decision.

Checkpoint C — R3 acceptance:

- live effective configuration identifies the target database without relying on a stale local file;
- V21 success and global function absence observed;
- non-secret operational artifacts hash-bound;
- independent review accepts the policy change.

Checkpoint D — source-governance acceptance:

- final-cut scan is reproducible and PASS;
- every envelope is final-cut-bound;
- no unreviewed, unresolved, unsupported, invalid, changed-envelope, or unverified-coverage state;
- no candidate/readiness claim is inferred from a local-only run.

## 6. Completion boundary

This planning task is complete when the plan is locally committed and verified. The implementation
task is complete at Checkpoint A. R3 is complete only at Checkpoint C. GC.5/7.6 is complete only at
Checkpoint D. R-C remains NO-GO until Task 7 produces a separately approved pre-deploy decision, and
Writer_Convergence remains unclaimed until the exact R-C digest is deployed and its post-deploy proof
is accepted.
