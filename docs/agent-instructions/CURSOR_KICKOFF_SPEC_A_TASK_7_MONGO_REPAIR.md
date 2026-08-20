# Cursor Kickoff — Spec A task 7: Mongo repair Job

**Date:** 2026-08-19
**Prepared for:** Cursor (implementation)
**Baseline:** `origin/main` @ `10224eb` (design Rev 10 merged)
**Suggested branch:** `feat/supported-asset-mongo-repair` off `main`

---

## 0. Resting state and merge discipline

Same as task 6, for the same reason: **implement, test, push the branch, do not open a PR.**

Task 7 builds the repair Job that rewrites production Mongo documents. Its execution is checkpoint **9.7** — the *second* irreversible step in the cutover — and it runs only after 9.6 (the Postgres repair) has passed its integrity assertion, with the refresh Job still suspended and ingress still closed.

Unlike task 6, merging this branch does **not** itself execute anything: the repair runs as an explicitly-invoked `azurerm_container_app_job`, not on startup. That is deliberate (`market-data-service` runs `min_replicas = 0`, so a startup-gated repair could deploy into a revision that never starts). But the branch still stays unmerged until the cutover is scoped, because it ships alongside R3a/R3b sequencing that doesn't exist yet.

**Report back with: branch pushed, all 15 scenarios green, no PR.**

## 1. Scope

**Task 7 only** — subtasks 7.1–7.6 plus all fifteen cases of 7.7. Source: `.kiro/specs/supported-asset-integrity/tasks.md`.

Out of scope: task 8 (refresh set, projection, freshness, demo initializer), task 9 (cutover), anything in `portfolio-service`.

## 2. Read this before writing code

The spec's own notes say it plainly:

> **Where the risk actually is.** Wave 7 is disproportionately hard. Ten of the design review's P1s landed in the Mongo repair, and task 7.7's fifteen scenarios exist because each was a specific defect found in review — 7.7.7 in particular is a regression test for a predicate that would have blocked its own retries. Do not treat that list as exhaustive optimism; treat it as the minimum.

Every one of the fifteen scenarios is a bug someone already found by reasoning. They are not coverage padding. If a scenario looks redundant, that is the signal to re-read the design section it came from, not to merge it with its neighbour.

## 3. What this actually does

Migrate `MM.NS` → `M&M.NS` in `market-data-service`'s Mongo `market_prices` collection — the same rename V19 performs in Postgres. Same instrument, real observations, only the symbol was wrong.

Why it needs this much machinery: a Mongo multi-document transaction would work but requires a replica-set topology the design refuses to depend on. So correctness comes from **document-level fencing** instead, which is topology-independent but has to defend every crash point explicitly.

## 4. The state machine

`CLAIMED → MIGRATED → VERIFIED → COMPLETE`, persisted with the lease.

Each step is individually re-entrant, bounded by a maximum duration **shorter than the lease**, and the lease is renewed **between** steps, never during them — so a step that overruns loses its claim deliberately rather than racing a reclaimer.

Defined partial states:

- destination written but `MIGRATED` not recorded → re-run converges (the upsert is keyed and fenced)
- source deleted but `MIGRATED` not recorded → the source's absence counts as "already migrated"
- neither → ordinary first run

**A conflict is terminal.** Two documents with the same `updatedAt` and conflicting values put the repair into durable `FAILED_CONFLICT` and stop it. It does **not** expire and retry — repeated retry of an unresolvable conflict loops forever and masks the condition. Clearing it is an operator action.

## 5. Predicates — acquisition and mutation are NOT interchangeable

This is where the review found the sharpest defects. Get this table exactly right:

| operation | predicate |
|---|---|
| lease renewal, state transition | `_id` = repair id, `owner` = mine, `generation` = mine, `state` = expected prior |
| fence **acquisition** on a price document | `_id` = ticker, `repairGeneration` **absent or `< G`** |
| destination write, source deletion | `_id` = ticker, `repairGeneration` **`= G`**, plus the expected tuple |
| archive write | `(repairId, generation, sourceCollection, sourceId)` unique |

An earlier draft used absent-or-lower for writes and deletions. That would let a stale runner mutate an unfenced document, **and** would block a same-generation retry — which is exactly what 7.7.7 regression-tests.

### The expected tuple is compared field-by-field, never by stored hash

Price documents carry **only** `repairGeneration` — no owner, no state, no hash. The mutation predicate names the five fields read during acquisition:

```
{ _id: <ticker>, repairGeneration: G,
  currentPrice: <as read>, quoteCurrency: <as read>, updatedAt: <as read>,
  previousReferencePrice: <as read>, previousReferenceAt: <as read> }
```

An earlier draft wrote a `payloadHash` into the predicate — a field no price document carries. Persisting one would mean writing, then later cleaning up, a temporary field on every affected document. The field-by-field predicate gives the same guarantee with nothing to remove.

**Verified against the code:** `AssetPrice` (`@Document(collection = "market_prices")`, `@Id String ticker`) carries exactly `currentPrice`, `quoteCurrency`, `updatedAt`, `previousReferencePrice`, `previousReferenceAt`. The five-field tuple is the whole document minus the id.

## 6. Zero rows affected is a classification problem, not a failure

Never treat "0 modified" as an error. Reread and classify (7.5):

- generation differs → **lost fence**, stop
- tuple equals the intended result → **idempotent success**, record `MIGRATED`
- generation matches and tuple equals captured input → **retry the CAS**
- otherwise → `FAILED_CONFLICT`

Source *absence* is classified the same way. Never "already held, skip" (7.3) — read the durable record: `COMPLETE` → exit 0; `FAILED_CONFLICT` → non-zero; unexpired foreign lease → non-zero.

## 7. Archive collection and two-phase reconciliation

Fields: `repairId`, `generation`, `sourceCollection`, `sourceId`, `payload`, `payloadHash`, `decision`, `status` (`PENDING` → `COMMITTED` | `SUPERSEDED`).

Indexes — **both** are required:
- unique on `(repairId, generation, sourceCollection, sourceId)`
- **partial** unique on `(repairId, sourceCollection, sourceId) where status = "COMMITTED"`

The partial index is what enforces "at most one committed decision per source." Retries across generations legitimately produce several `PENDING` rows; two `COMMITTED` rows would mean two conflicting decisions were both final.

The archive is written `PENDING` before the source is deleted and `COMMITTED` after, so a crash between leaves a recoverable record rather than an ambiguous gap.

### Reconciliation runs BEFORE the migration step, in two phases

Running it after would let the absent-source shortcut step past an unresolved `PENDING` record.

1. **Evaluate** every archive record for `(repairId, sourceCollection, sourceId)` against current source and destination. **No writes in this phase.**
2. **Select** the single winner — the **highest** generation whose recorded decision the destination actually corroborates.
3. **Transition** — mark every other candidate `SUPERSEDED`, then perform the winner's deletion (gated on the destination check) and commit it.

Two phases are mandatory, not stylistic: an earlier draft processed candidates in ascending order applying the matrix immediately, which **cannot implement its own stated winner** — committing the lowest corroborated generation first trips the partial unique index and blocks the highest from winning.

If no candidate is corroborated → `FAILED_CONFLICT`, not a fresh attempt.

### The reconciliation matrix

| archive | source | outcome |
|---|---|---|
| `PENDING` | present, unchanged | **verify destination first** — it must carry the exact tuple the record's decision names. Only then re-fence, retry deletion, commit. |
| `PENDING` | absent, destination proves the decision | promote to `COMMITTED` |
| `PENDING` | changed, or destination inconsistent | `FAILED_CONFLICT` |
| `COMMITTED` | absent, destination valid | idempotent success |
| `COMMITTED` | present, or destination invalid | `FAILED_CONFLICT` |

**The overriding rule: no recovery path deletes a source without first proving the destination holds the exact expected tuple.** Deleting on the strength of an archive record alone could destroy the last valid copy if the destination write never landed.

## 8. Collision outcomes (same shape as Postgres, different store)

Where both `MM.NS` and `M&M.NS` documents exist: order by `updatedAt`, **known beats null**, newer wins; both null → **destination retained**, source archived. Same `updatedAt` + identical values → collapse idempotently. Same `updatedAt` + conflicting values → `FAILED_CONFLICT`.

**The five-field tuple moves atomically or not at all** (7.7.13). A destination document must never hold `currentPrice` from one source with `previousReferencePrice`/`previousReferenceAt` from the other — that produces a change figure describing no real interval.

## 9. The Job itself (7.1)

- Dedicated `azurerm_container_app_job`, **manual trigger**, same `market-data-service` image, non-web mode, bounded execution timeout.
- **Repair property `true`, refresh property ABSENT** — not `false`. `false` activates the suspended runner alongside the repair, and its `SpringApplication.exit(0)` could kill an in-flight repair. Task 5's `JobRunnerMatrixValidator` already makes that combination a startup failure; do not weaken it to accommodate the Job config.
- Exit `0` on `COMPLETE` or already-complete. Non-zero on `FAILED_CONFLICT`, lost fence, timeout, or unverifiable state.

**Existing scaffolding to build on:** `MarketDataRepairJobRunner` already exists as a marker bean (`@ConditionalOnProperty(prefix = "market-data.repair", name = "enabled", havingValue = "true")`) with an empty `run()`. Fill it in; do not create a parallel runner. `JobRunnerMatrixValidator.REPAIR_PROPERTY` is `market-data.repair.enabled`.

## 10. The fifteen scenarios (7.7) — all Testcontainers MongoDB

`org.testcontainers:testcontainers-mongodb` is already a `testImplementation` dependency. Tag them `integration` so they run under `:market-data-service:integrationTest`, matching the convention `PostgresRepairMigrationIT` used.

1. Crash after destination write, before `MIGRATED` → retry converges, no duplicate
2. Crash after source delete, before `MIGRATED` → absence classified as success
3. Crash between archive `PENDING` and source delete → reconciliation retries and commits
4. Crash after source delete, before archive `COMMITTED` → `PENDING` promoted, **not re-deleted**
5. Lease expiry mid-repair, reclaim by new generation, **stale runner's write rejected**
6. Concurrent first-claim upsert race → exactly one winner, loser **classified**, not skipped
7. Same-generation retry against an already-fenced document → **succeeds** *(regression: the absent-or-lower predicate blocked this)*
8. Conflicting `updatedAt` payloads → `FAILED_CONFLICT`, terminal, **not retried on next claim**
9. Both documents exist, newer `updatedAt` wins
10. Both exist, known `updatedAt` beats null
11. Both exist, both null → destination retained, source archived
12. Both exist, same `updatedAt`, identical values → collapse idempotently
13. **Five-field tuple moves atomically** — assert no destination ever mixes fields from both sources
14. Multiple prior `PENDING` generations → highest corroborated wins, others `SUPERSEDED`
15. Destination missing expected tuple → **deletion refused**, no data loss

## 11. Definition of done

- 7.1–7.6 and all 7.7.1–7.7.15 checkboxes ticked in `tasks.md`.
- All 15 scenarios green under `:market-data-service:integrationTest`.
- Full `:market-data-service:test` green, unfiltered — the runner-matrix tests from task 5 must still pass.
- Terraform plan assertion for the new Job, with a unit test, matching the `infrastructure/terraform/azure/scripts/assert_*.py` + `test_assert_*.py` convention task 5 established.
- Spec reference check:

```bash
python scripts/check-spec-references.py .kiro/specs/supported-asset-integrity/tasks.md --against .kiro/specs/supported-asset-integrity/requirements.md --coverage --pairs
```

## 12. Escalate rather than decide

- Any predicate ambiguity between acquisition and mutation — §5 is exact; if the code seems to need something else, raise it.
- Any scenario in §10 that cannot be constructed without weakening a predicate.
- Any temptation to make `FAILED_CONFLICT` retryable, or to clear fencing metadata while one is outstanding.
- Any need to depend on a Mongo replica set or multi-document transaction — the design explicitly rejects that dependency.
- Any change to `JobRunnerMatrixValidator`'s accepted combinations.
