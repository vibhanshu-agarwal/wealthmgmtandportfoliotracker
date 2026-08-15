# Design Document

> **Reference convention.** This document refers to requirements by **name** — Supported_Catalog,
> New_Write_Invariant, Post_Migration_Integrity_Assertion — rather than by criterion number.
> Numeric references broke seven times across the requirements review, always after an insertion
> renumbered a list, and always resolving to a valid-but-wrong criterion that the structural check
> could not see. Names cannot drift that way. Where a number is unavoidable it is validated by
> `scripts/check-spec-references.py --pairs`, which prints each reference beside its target text.

> **Revision 9 — 2026-08-15. FINAL — design frozen after this revision.** Bounded correction to the
> refresh-suspension mechanism only. Disabling `MarketDataRefreshJobRunner` removes the **only** caller
> of `SpringApplication.exit` in `market-data-service`, so a "suspended" execution would start with no
> exit path and run to its 600-second replica timeout — a failed execution, not the clean no-op claimed.
> Suspension therefore adds an explicit **suspended-mode runner**: mutually exclusive with the refresh
> runner, writes nothing, emits `refresh_suspended`, flushes telemetry, exits `0`, and with the property
> absent neither Job runner activates in the long-running service. Requirements 7.22–7.25 are reframed
> around disabling the refresh **write capability** rather than its trigger. The controlled run now uses
> an **execution-specific template override** so the persisted Job configuration stays disabled until
> after the run succeeds. All residual "schedule suspended/restored" and cron-rollback language is
> replaced.
>
> **Revision 8 — 2026-08-15.** Incorporates seventh design review. Four P1s: refresh suspension no
> longer touches the trigger at all — in the pinned AzureRM **4.81.0** the whole
> `schedule_trigger_config` is `ForceNew`, so the impossible-date cron would have planned
> destroy/create and the "resource untouched" claim was false; instead the Job's
> `MARKET_DATA_JOB_RUNNER_ENABLED=false` env var disables the `@ConditionalOnProperty`-gated runner
> in place, and a no-op execution on the normal cadence becomes positive evidence the suspension
> holds. A zero affected-row count on a tuple CAS is now **classified by reread** rather than assumed
> to be a lost fence, which had contradicted the promised re-entrancy when a write landed but its
> `MIGRATED` transition did not. Archive reconciliation evaluates the whole candidate set **before**
> any transition, since committing ascending would trip the partial unique index and block the
> highest corroborated generation from winning; `SUPERSEDED` is added to the schema. Component 15 no
> longer restores the unsafe "already held" duplicate-key reading. Two drifts corrected: the phase
> table said "expected payload hash", and rollout evidence said the restore revision is verified "the
> same way".
>
> **Revision 7 — 2026-08-15.** Incorporates sixth design review. Five P1s: the predicate summary table
> restored absent-or-lower for mutation, contradicting the phase table two paragraphs above — corrected,
> with acquisition listed as its own row; `payloadHash` is replaced by a field-by-field comparison of the
> five-field tuple, since price documents carry only `repairGeneration` and a stored hash would be a
> second temporary field to write and clean up; a duplicate-key error on claim is now **classified** by
> reading the durable record, because `COMPLETE`, `FAILED_CONFLICT`, and an unexpired foreign lease all
> surface identically and map to different Job exit codes; archive recovery must prove the destination
> holds the expected tuple **before** any deletion, and multiple prior `PENDING` generations reconcile in
> ascending order with the highest corroborated decision winning; and schedule suspension becomes a
> never-firing `cron_expression` rather than a trigger-type switch, which under AzureRM `~> 4.0` would
> force Job replacement mid-window. Two corrections: the restore-to-zero revision is verified at
> configuration level rather than by a startup log it may never emit, and the malformed Job contract
> table is repaired.
>
> **Revision 6 — 2026-08-15.** Incorporates fifth design review. Fencing splits into **acquisition**
> (absent-or-lower) and **mutation** (`= G`) phases — the single absent-or-lower predicate blocked
> same-generation retries and permitted a stale runner to delete an unfenced source — with a lost
> fence (zero affected rows) halting the runner. The archive gains a **reconciliation matrix** for
> every `PENDING`/`COMMITTED` × source-state combination, run before the migration step so the
> absent-source shortcut cannot skip an orphaned record, plus a partial unique index enforcing at most
> one committed decision per source. The controlled refresh execution moves **before** ingress
> restoration, resolving a contradiction between the pre-ingress gate and R4's final step, and
> becoming an end-to-end enforcement test run while no user can reach the system. The repair Job gains
> a terminating application contract modelled on the existing refresh Job. Requirement 7 now states
> the general writer-quiescence invariant first, with Job suspension and gateway maintenance as the
> mechanisms satisfying it. Two superseded summaries corrected.
>
> **Revision 5 — 2026-08-15.** Incorporates fourth design review. Three P1s: the production refresh
> Job is **suspended** across the Mongo repair — it is a separate Job that ingress shutdown does not
> stop, and its Mongo write is an unfenced read-modify-save that would overwrite a fenced document
> (requirements 7.21–7.23 added); the Mongo repair becomes an explicitly invoked **one-shot ACA Job**
> rather than a startup task, since `min_replicas = 0` plus a closed ingress means a gated startup task
> could deploy into a revision that never starts, and the verification wake step gains a real actor via
> a temporary `min_replicas = 1` whose restore revision is itself verified; and disabling ingress is
> acknowledged to require an **IaC change** — the `container-app` module emits `ingress`
> unconditionally, so it gains an `ingress_enabled` input, and the claim becomes "no *application*
> code". Two P2s: the three fencing predicates are specified separately because owner, generation, and
> state do not coexist on every document, with a `PENDING`/`COMMITTED` archive schema and unique index;
> and the architecture diagram is rewritten to the corrected sequence, with fence cleanup gated on
> `COMPLETE` evidence rather than on a release.
>
> **Revision 4 — 2026-08-15.** Incorporates third design review. Write quiescence becomes a
> **maintenance window** disabling `api-gateway` ingress: the seeder uses a separate
> `/api/internal/portfolio/**` route, neither route carries a method predicate, and both are static
> YAML, so the previously proposed route switch was unexecutable and would have removed reads. The
> Mongo claim excludes `FAILED_CONFLICT` as well as `COMPLETE`; fencing moves to an
> **absent-or-lower** predicate covering **both** source and destination, since a bare `$lt` matches
> no pre-repair document and every unfenced source operation was reachable by a superseded runner. R4
> gains an explicit configuration choreography, because environment variables override packaged
> defaults and deploying R4 under Terraform-supplied `false` would have changed nothing. The log gate
> filters on Container App, revision, image digest, replica identity, and rollout boundary, verifying
> at active-revision level rather than replica cardinality, with an explicit wake step and separate
> verification of the refresh Job. Archive equality is defined semantically as
> `payload = to_jsonb(row)` with a typed round-trip, and its natural key uses full-precision identity.
> Projection-rejection rollback is reframed as degradation, and an R4→R3 artifact rollback is
> identified as implicitly restoring permissive defaults.
>
> **Revision 3 — 2026-08-15.** Incorporates second design review. Six blocker groups: the V18
> postcondition now asserts zero operational `BTC` history plus archive cardinality and byte-equality
> (requirements 7.9 qualified to match); the release boundary is corrected — tuple projection and
> freshness response ship **with** V17 in R3a, since they read a column that does not exist before it
> — and R3 splits into R3a Postgres / R3b Mongo, gated by `app.repair.mm-ns.enabled`, because the
> deploy workflow runs services as a parallel matrix and cannot sequence them; enforcement gates
> default **`true`** in the R4 artifact so a missing environment value cannot silently run
> permissively, with Terraform as the single control plane; the Mongo claim excludes `COMPLETE`,
> fences on the mutated document rather than the lease, handles duplicate-key races, bounds step
> duration, records discards to a Mongo-side archive, and treats a conflict as terminal
> `FAILED_CONFLICT`; both seed paths enumerate `SupportedCatalog.active()`, without which the seeder
> would write `TATAMOTORS.NS` and roll back its own repair once enforcement is on; V17 gains
> `repair_audit` creation and a lossy-truncation preflight before the `TIMESTAMP(3)` alteration.
> Verification moves from exec-and-curl to structured startup logs: the runtime image is
> `amazonlinux:2023-minimal` with no HTTP client, and scale-to-zero means enumeration can find no
> replicas at all.
>
> **Revision 2 — 2026-08-15.** Incorporates first design review. Seven blockers addressed: synthetic
> `BTC` history is archived and removed rather than left in operational history (requires
> requirements Revision 13); the cutover becomes a sequence of **release artifacts** with migrations
> withheld until R3, holding writes quiesced across repair and rollout, named gate properties, and
> defined rollback; the Mongo lease becomes a reclaimable conditional update with a fencing token and
> per-step re-entrancy; the demo initialiser gains `pg_advisory_xact_lock` on the acting transaction
> and a fixed cost-basis anchor, without which its "complete" comparison differs every boot; the
> holding-collision model decides currency mismatch, null basis, non-positive totals, and
> `cost_basis_as_of`; `repair_archive` and `repair_audit` are specified with idempotency keys; and
> `/actuator/info` exposure is added for the two services lacking it, with in-environment
> verification, since these services are not routable from the deployment workflow.

## Overview

This design turns an emergent, three-way-duplicated asset list into one declared authority, and
enforces that authority at every point where a holding or a price is written. It delivers no UI and no interactive feature, but it is **not** free of user-visible change: it adds
`assetPriceFreshness` to the portfolio-summary response and has already removed a field from the
internal seed endpoint. Its output is an invariant, a widened API contract, and the data repair that
makes the invariant true of existing rows.

Four structural facts shape every decision below.

**The catalog is already loaded three times.** `TickerCatalogService` (insight-service) and two
copies of `SeedTickerRegistry` (market-data-service, portfolio-service) each parse the same file.
The service that must validate writes already holds it in memory, so validation needs no
cross-service call and no cold-start penalty — a fact that removes the only serious argument for a
runtime catalog service.

**The build already synchronises the copies, into the wrong place.** Three `copySeedTickers` Copy
tasks write `config/seed-tickers.json` into git-tracked `src/main/resources/seed/` directories.
Nothing is unsynchronised; the defect is that generated content is committed, so git state and
build state can disagree and a direct edit is silently reverted on next build.

**`portfolio-service` legitimately writes both price tables.** `MarketPriceProjectionService` holds
its own `JdbcTemplate` and is driven by `PriceUpdatedEventListener`. That projection is the
mechanism this design changes most heavily. It is not the seeder, and nothing here may prohibit it.

**The freshness signal does not exist yet.** `market_prices` has no observation-time column, and the
projection discards `PriceUpdatedEvent.observedAt` entirely, writing `updated_at = now()` under a
`WHERE ... IS DISTINCT FROM` guard that skips the row when the price is numerically unchanged. A
stable quote polled correctly every day reads as ancient. Freshness must be built on a new column,
not on a reinterpretation of an existing one.

### Key design decisions

#### D1 — `common-catalog` is a plain Java module with no Spring dependency

It joins `common-dto` and `common-observability` under the existing convention. It exposes loading,
validation, indexing, and versioning as ordinary classes, and holds no `@Service`, `@Component`, or
Spring imports. Consumers wrap it in whatever bean style they already use. This keeps it usable from
a Flyway callback, a test fixture, and a Spring context alike, and it keeps the module's failure
mode — an exception from a constructor — independent of Spring's lifecycle.

#### D2 — The manifest is packaged from the repo root into build output, never into tracked source

Each consuming module's `processResources` copies `config/seed-tickers.json` into
`build/resources/main/catalog/`. The three tracked `src/main/resources/seed/` copies are deleted and
the three `copySeedTickers` tasks removed. This satisfies the packaging requirement by removing the
duplication rather than policing it: there is nothing left to drift, so no CI check asserting the
copies match is needed or wanted.

#### D3 — `lifecycleStatus` is added to all entries in the same change as its validation

No entry carries the field today. Because catalog load failure is fatal in every catalog-consuming
service, shipping the validation before the data would fail startup everywhere simultaneously — a
total outage produced by the safety mechanism. The manifest edit and the validation land together,
and the ordering constraint is a correctness property below, not a deployment note.

#### D4 — The catalog version hashes a canonical projection of every behaviourally relevant field

SHA-256 over a stable serialisation of `ticker`, `name`, sorted `aliases`, `assetClass`,
`quoteCurrency`, `lifecycleStatus`, and `basePrice`, entries sorted by ticker, truncated to 16 hex
characters. `basePrice` participates despite reaching consumers only through the seed-only view,
because it determines seeded cost bases: two builds differing only in `basePrice` are behaviourally
different and must not report an identical version.

#### D5 — Seed-only access to `basePrice` is a separate interface, not a flag or a nullable field

`SupportedCatalog` exposes entries without `basePrice`. A second interface, implemented by the same
object but injected only into the seeder, exposes it. A flag on the main type would leave every
consumer able to reach the field and rely on discipline; a separate type makes the constraint
structural and reviewable at the injection site.

#### D6 — Validation lives in the application layer; controllers only map failures

`SupportedAssetValidator` is invoked by the service method that persists holdings. It raises
`UnsupportedAssetException`, which rolls back. Controllers add no validation of their own — a single
`@RestControllerAdvice` maps the exception to 422. This is what makes the seeder correct in both of
its roles: it is an application operation reached over HTTP by `PortfolioSeedController` and
directly by tests, and the detection is identical either way. Only the reporting differs.

#### D7 — `observed_at` is a new nullable column, not a reinterpretation of `updated_at`

`updated_at` keeps its receive-time meaning and its existing readers. `observed_at` is added
nullable because every existing row acquires one, and because `PriceUpdatedEvent.observedAt` is
nullable for old-shape events. Null is a first-class state meaning "age undeterminable", surfaced as
`UNKNOWN`, never silently treated as fresh and never backfilled with a receive time.

#### D8 — Tuple atomicity is enforced by the upsert's `WHERE` clause, not by application branching

A single conditional `ON CONFLICT DO UPDATE ... WHERE` expresses all four null/known transitions
correctly, in the database, without a read-modify-write race. Application code handles only the two
cases SQL cannot distinguish: an equal-timestamp write that was skipped because the payload was
identical (a no-op) versus one skipped because it conflicted (surfaced).

#### D9 — One observation identity, normalised once

The existing history writer truncates `observedAt` to milliseconds to avoid false duplicates from
sub-millisecond drift; the latest-row upsert as first drafted bound the raw value. The same event
would then compare against `market_prices` at full precision and key `market_price_history` at
millisecond precision — two identities for one observation, so a row could be judged "newer" in one
table and a duplicate in the other.

Normalisation happens **once**, at the top of the projection, and the identical value is bound to
both statements. `observed_at` is declared `TIMESTAMP(3)` so the column cannot silently retain
precision the identity does not carry. `market_price_history.observed_at` moves to the same
precision, since it is half of the same identity.

#### D10 — `@Async` is removed from the projection

`upsertLatestPrice` is currently `@Async @Transactional`. Two consequences make it unusable as
designed. First, the listener acknowledges the record and consumer lag falls to zero while the
projection is still queued in the executor, so **lag proves nothing about drainage** — the cutover's
central piece of evidence would be measuring the wrong thing. Second, a database failure inside an
async task never reaches Kafka's error handler, so today a failed projection is swallowed rather than
retried or dead-lettered.

The projection moves onto the listener thread and stays transactional. Lag then means what the
cutover needs it to mean, failures reach the existing error handler and DLT, and the conflict
exceptions this design introduces become visible rather than silently lost. Throughput is not a
concern: the production producer is a daily batch of 160 tickers.

The alternative — keeping `@Async` and additionally proving the executor queue and in-flight count
are zero, with defined shutdown and acknowledgement semantics — was rejected as more machinery to
prove a weaker property.

#### D11 — History conflicts are detected by insert-then-compare inside the same transaction

`ON CONFLICT DO NOTHING` cannot distinguish redelivery from contradiction. The design inserts, and
on zero rows affected reads the existing row and compares. Identical is a no-op; different raises,
which rolls back the latest-row write in the same transaction. The existing `@Transactional`
boundary already spans both writes, so this needs no widening.

#### D12 — The demo portfolio is seeded by the parameterised service, not by Flyway

`PortfolioSeedService.seed(userId)` is already user-parameterised and already computes deterministic
cost bases from `basePrice`. Reimplementing that determinism in SQL would duplicate it in a language
poorly suited to it. A property-gated initialiser invokes the existing method for the demo user.
The production seed endpoint keeps its hardcoded E2E user — parameterising it would reopen the
surface that made the seeder dangerous in the first place.

**A profile guard does not serialise startup.** `portfolio-service` runs up to three replicas, and
`PortfolioSeedService.seed()` opens by deleting *every* portfolio for the user before recreating one.
Three replicas booting together could interleave delete and insert and leave the demo account with
zero or duplicate portfolios.

The initialiser takes `pg_advisory_xact_lock` **on the same transaction and connection** that
performs the comparison, deletion, and recreation. A session-level lock, or one taken on a different
connection, would leave a window between the check and the write; a transaction-scoped lock is
released by commit or rollback, so a crashed replica cannot strand it. It runs only when
`app.demo.seed-on-startup=true`, defaulting **false** — the lock prevents concurrent execution, the
property prevents unintended execution, and the default means an ordinary deploy does nothing.

**The desired state must be stable, or the comparison is meaningless.** `costBasisAsOf` is currently
`Instant.now().minus(25, HOURS)`, which moves every startup, so a "complete" comparison would differ
on every boot and reseed the demo portfolio destructively each time. The anchor becomes a **fixed
configured instant**, `app.demo.cost-basis-anchor`, so the desired holding set is a pure function of
`(active catalog, userId, anchor)` — the anchor is an input, not an incidental — and re-running the
initialiser against an already-correct portfolio is a no-op.
The comparison covers ticker set, quantities, cost basis, currency, source, **and `costBasisAsOf`** —
genuinely complete, because every field in it is now deterministic. Omitting the very field the anchor
was introduced to stabilise would leave the comparison incomplete by exactly the amount of the fix.

#### D13 — The Mongo repair is leased, fenced on the document it mutates, and terminally safe

Flyway manages Postgres only, so the Mongo correction is a separate **one-shot ACA Job**, invoked
explicitly rather than run at service startup — `market-data-service` has `min_replicas = 0`, so a
startup task could deploy into a revision that never starts. The Job can nevertheless be invoked more
than once, concurrently with a service replica, or after a partial failure, so "check for the source
key, then act" is still a read-then-write race and the runner still takes a lease. Four things about
that lease are load-bearing.

**The claim must exclude every terminal state, not just success.** A conditional *insert* cannot
reclaim an expired lease — the document still exists — so one crash would deadlock the repair
permanently. The claim is an atomic conditional **update** matching absent-or-expired, excluding
**both** terminal states. Excluding only `COMPLETE` would leave a `FAILED_CONFLICT` record matching
absent-or-expired and being reclaimed on the next boot, which contradicts the terminal state it
promises:

```
findOneAndUpdate(
  filter: { _id: "mm-ns-repair",
            state: { $nin: ["COMPLETE", "FAILED_CONFLICT"] },
            $or: [ { expiresAt: { $exists: false } },
                   { expiresAt: { $lt: now } } ] },
  update: { $set: { owner: <replicaId>, state: "CLAIMED", expiresAt: now + lease },
            $inc: { generation: 1 } },
  upsert: true, returnDocument: AFTER)
```

On first claim two replicas may race the upsert; exactly one wins and the loser receives a
**duplicate-key error on `_id`**. That error is **not** self-explanatory and must not be read as
"already held": because the filter excludes both terminal states, a duplicate can equally mean the
record is `COMPLETE`, is `FAILED_CONFLICT`, or is an unexpired lease held by someone else. Those three
map to different Job exit codes, so the runner **reads the durable record and classifies it**:

| record after duplicate | meaning | exit |
|---|---|---|
| `state = COMPLETE` | repair already done | `0` — idempotent success |
| `state = FAILED_CONFLICT` | terminal failure awaiting an operator | non-zero |
| unexpired lease, another owner | a concurrent execution is in progress | non-zero — it has **not** succeeded, and reporting `0` would let the cutover proceed on someone else's unfinished work |

Only the first is success. Treating every duplicate as "already held, skip" would have made a
concurrent or failed repair indistinguishable from a completed one at the Job's exit code, which is
the single signal the cutover gates on.

**Fencing must cover both documents, and must match the pre-repair state.** Two defects sit in the
obvious form. First, `repairGeneration: { $lt: myGeneration }` does **not** match a document lacking
the field — which is precisely the normal pre-repair state of an existing destination — so the update
matches nothing, `upsert: true` then attempts the same `_id`, and the write fails on a duplicate key.
The **acquisition** predicate must therefore be **absent-or-lower** (mutation is a different predicate — see below). Second, fencing only the destination leaves a superseded
runner free to archive or delete the **source** after a newer generation has claimed the repair.

**Acquisition and mutation are different predicates.** An earlier draft used absent-or-lower for
*both*, which is wrong in two directions at once: once acquisition has set `repairGeneration = G`, a
predicate of `< G` no longer matches, so a same-generation retry cannot make progress; and permitting
a mutation while the field is *absent* lets a stale runner delete a source the new owner has not yet
fenced. The phases are separate:

| phase | predicate | effect |
|---|---|---|
| **acquire** the fence | `repairGeneration` absent **or** `< G` | set `repairGeneration = G` |
| **retry** within the same attempt | `repairGeneration = G` | accepted — already ours, idempotent |
| **mutate** destination, **delete** source | `repairGeneration = G` plus the **expected tuple** (the five fields as read at acquisition) | proceed |

```
// acquire
{ _id: <ticker>,
  $or: [ { repairGeneration: { $exists: false } },
         { repairGeneration: { $lt: G } } ] }        -> $set { repairGeneration: G }

// mutate / delete — never absent-or-lower, and no stored hash
{ _id: <ticker>, repairGeneration: G,
  currentPrice: <as read>, quoteCurrency: <as read>, updatedAt: <as read>,
  previousReferencePrice: <as read>, previousReferenceAt: <as read> }
```

**A zero affected-row count is ambiguous and must be classified, not assumed to be a lost fence.**
The mutation predicate matches the tuple *as read before the write*, so a successful destination write
whose `MIGRATED` transition then failed leaves the document holding the **intended post-write** tuple.
A retry's pre-write predicate no longer matches, returns zero rows, and — under a naive rule — would
be reported as a lost fence, contradicting the re-entrancy this design promises. The runner therefore
**rereads the document** and classifies:

| observed on reread | meaning | action |
|---|---|---|
| `repairGeneration` differs from `G` | another generation owns the repair | lost fence — stop |
| tuple already equals the **intended result** | the write landed; only the transition failed | idempotent success — record `MIGRATED` and continue |
| `repairGeneration = G` and tuple equals the **captured input** | the write genuinely did not land | retry the CAS |
| anything else | the document changed underneath the repair | `FAILED_CONFLICT` |

Source deletion is classified the same way: an absent source with a destination carrying the intended
result is **idempotent success**, not a missing precondition and not a lost fence.

Those three fields do not coexist on every document, so "check owner, generation, and prior state"
is not one predicate — it is three, and they are specified separately:

| operation | predicate |
|---|---|
| lease renewal, state transition | `_id` = repair id, `owner` = mine, `generation` = mine, `state` = expected prior |
| fence **acquisition** on a price document | `_id` = ticker, `repairGeneration` absent **or** `< G` |
| destination write, source deletion | `_id` = ticker, `repairGeneration` **`= G`**, plus the expected tuple |
| archive write | `(repairId, generation, sourceCollection, sourceId)` unique; see below |

Acquisition and mutation are **not** interchangeable — see the phase table above. An earlier draft
listed absent-or-lower for writes and deletions, which would let a stale runner mutate an unfenced
document and would block a same-generation retry.

**The "expected tuple" is compared field-by-field, not by a stored hash.** An earlier draft wrote
`payloadHash: <expected>` into the mutation predicate, which no price document carries — the whole
point of keeping repair bookkeeping off operational documents is that they hold only
`repairGeneration`. Persisting a repair-time hash would mean writing, then later cleaning up, a second
temporary field on every affected document. Instead the predicate names the five fields the repair
read during acquisition:

```
{ _id: <ticker>, repairGeneration: G,
  currentPrice: <as read>, quoteCurrency: <as read>, updatedAt: <as read>,
  previousReferencePrice: <as read>, previousReferenceAt: <as read> }
```

Same guarantee — the document must be exactly what the decision was based on — with nothing extra to
write or remove. The price documents therefore carry only `repairGeneration`, and no owner or state
field.

**The archive collection needs its own schema, because an unfenced archive is not harmless.** A stale
runner could otherwise append a second snapshot of the same document under a superseded decision, and
a restore would not know which to believe. Archive documents carry `repairId`, `generation`,
`sourceCollection`, `sourceId`, `payload`, `payloadHash`, `decision`, and `status`
(`PENDING` → `COMMITTED`, or `PENDING` → `SUPERSEDED` when a higher corroborated generation wins
reconciliation), with a unique index on `(repairId, generation, sourceCollection, sourceId)`.
`SUPERSEDED` is terminal and non-authoritative: it preserves the audit trail of what was attempted
without competing for the single committed decision.

The archive is written `PENDING` before the source is deleted and marked `COMMITTED` after, so a crash
between the two leaves a recoverable record rather than an ambiguous gap. But "recoverable" needs an
algorithm, because a crash after deletion leaves `PENDING` with the source already gone — and the
re-entrancy rule elsewhere treats an absent source as "already migrated", which would step past the
orphaned record without resolving it.

**Every claim begins by reconciling any prior archive record for each source id:**

| archive | source | outcome |
|---|---|---|
| `PENDING` | present, unchanged | **verify the destination first** — it must carry the exact tuple the record's decision names. Only then re-fence, retry the deletion, and commit. Deleting on the strength of the archive record alone could destroy the last valid copy if the destination write never landed. |
| `PENDING` | absent, and the destination proves the recorded decision | promote the existing record to `COMMITTED` |
| `PENDING` | changed, or destination inconsistent with the record | `FAILED_CONFLICT` |
| `COMMITTED` | absent, destination valid | idempotent success, nothing to do |
| `COMMITTED` | present, or destination invalid | `FAILED_CONFLICT` |

Reconciliation runs **before** the migration step, so the absent-source shortcut can never be reached
with an unresolved `PENDING` record outstanding. **No recovery path deletes a source without first
proving the destination holds the exact expected tuple** — every row above that ends in a deletion is
gated on that check.

**Multiple earlier `PENDING` generations reconcile in two phases — evaluate everything, then act.**
An earlier draft processed candidates in ascending order applying the matrix immediately, which cannot
implement its own stated winner: committing the lowest corroborated generation first would trip the
partial unique index and *block* the highest one from winning. Selection must therefore complete
before any transition:

1. **Evaluate** every archive record for `(repairId, sourceCollection, sourceId)` against the current
   source and destination. No writes in this phase.
2. **Select** the single winner — the **highest** generation whose recorded decision the destination
   actually corroborates.
3. **Transition**: mark every other candidate `SUPERSEDED`, then perform the winner's deletion (gated
   on the destination check) and commit it.

If no candidate is corroborated the outcome is `FAILED_CONFLICT`, not a fresh attempt: contradictory
prior attempts are exactly the state an operator needs to see.

**At most one committed decision per source.** The unique index carries `generation`, so retries
across generations can legitimately produce several `PENDING` rows for one source — but two
`COMMITTED` rows would mean two conflicting decisions were both final. A **partial unique index on
`(repairId, sourceCollection, sourceId) where status = "COMMITTED"`** enforces that. A later
generation must explicitly reconcile or supersede earlier `PENDING` records rather than ignoring
them.

A Mongo multi-document transaction would also work, but requires a replica-set topology this design
would then depend on; document-level fencing is topology-independent.

**Fencing metadata is removed only after `COMPLETE` plus verification evidence** — never while a
`FAILED_CONFLICT` is outstanding, because clearing the fence would let a stale runner resume into an
unresolved conflict. Removal is gated on that evidence specifically, **not** on "the release that
removes the runner": a release can ship for unrelated reasons while a conflict is still open.

**Steps are individually re-entrant, and their partial states are defined.** The state machine is
`CLAIMED -> MIGRATED -> VERIFIED -> COMPLETE`, persisted with the lease. The migration step is an
idempotent, fenced upsert keyed by destination ticker, so:

- destination written but `MIGRATED` not recorded — re-run converges, because the upsert is keyed and
  fenced;
- source deleted but `MIGRATED` not recorded — the source's absence is treated as "already migrated"
  for that ticker rather than a missing precondition, so the step completes;
- neither done — ordinary first run.

Each step is bounded by a **maximum duration** shorter than the lease, and the lease is renewed
between steps rather than during them, so a step that overruns loses its claim deliberately instead of
racing a reclaimer.

**A conflict is terminal.** Two documents carrying the same `updatedAt` with conflicting values put
the repair into durable `FAILED_CONFLICT` and stop it. It does not expire and retry — repeated retry
of an unresolvable conflict loops forever and masks the condition. Clearing it is an operator action.

**Discards are recorded.** The losing document's full snapshot and the decision that discarded it are
written to a `repair_archive` **collection** in the same Mongo database — the Postgres archive is not
reachable from this service — using the same reason vocabulary, before the losing document is removed.

#### D14 — Strict rejection is activated last, behind the staged cutover

Enabling out-of-catalog rejection before the producer is narrowed, the repairs have landed, and the
Kafka backlog has drained would reject events the system is still expected to produce. The cutover
is a sequence, not a deploy, and its final step is a configuration flip.

---

## Architecture

### Catalog authority

```
config/seed-tickers.json  (single tracked manifest)
        |
        |  processResources -> build/resources/main/catalog/
        v
  common-catalog  (plain Java: load, validate, index, version)
        |
        +---------------+-------------------+
        v               v                   v
 portfolio-service  market-data-service  insight-service
   validation +       refresh desired      normalization +
   seeding            set                  grounding (local)
```

Each service constructs the catalog once at startup. A load failure throws, and because the object
is constructed during context initialisation, the service does not start.

### Write path

```
HTTP request                 direct call (tests, initialiser)
     |                                   |
     v                                   |
Http_Entry_Point                         |
  (controller)                           |
     |                                   |
     +--------------> Application_Operation <-----+
                        (service method)
                              |
                     SupportedAssetValidator
                              |
                    valid ----+---- invalid
                      |              |
                   persist      UnsupportedAssetException
                                     |
                        +------------+------------+
                        v                         v
                @RestControllerAdvice        propagates to
                  -> HTTP 422                 direct caller
```

Detection is in one place. The caller determines only how a failure is reported.

### Price projection

```
PriceUpdatedEvent (Kafka)
        |
  PriceUpdatedEventListener
        |
  MarketPriceProjectionService   [ @Transactional ]
        |
        +-- 1. resolve/validate quote currency against catalog
        |        null -> resolve from catalog; unresolvable -> reject
        |        non-null -> must equal catalog; mismatch -> reject
        |        ticker absent from catalog -> reject   (gated by cutover)
        |
        +-- 2. conditional tuple upsert on market_prices
        |        writes iff stored observed_at IS NULL OR incoming > stored
        |        0 rows + equal timestamps -> compare payload
        |                                     identical -> no-op
        |                                     conflicting -> raise
        |
        +-- 3. history append (only when observedAt present)
                 insert ON CONFLICT DO NOTHING
                 0 rows -> read existing, compare
                           identical -> no-op
                           conflicting -> raise (rolls back step 2)
```

### Repair and cutover

Ordered so nothing can recreate what the repair fixes. Migrations ship only in R3a; the Mongo repair
is an explicitly invoked Job, not a startup task.

```
R1  catalog lands, enforcement gated OFF, no migrations, no observed_at code
      |
      v
R2  refresh desired set <- active catalog; stop every old producer revision
      |
      v
    SUSPEND refresh: MARKET_DATA_JOB_RUNNER_ENABLED=false on the Job
      |          (schedule untouched — editing it is ForceNew; ingress does not stop the Job)
      |
      v
    drain: consumer lag zero on every partition (or retention elapsed)
      |
      v
    MAINTENANCE WINDOW: api-gateway ingress disabled (Terraform ingress_enabled=false)
      |          closes /api/portfolio/** and /api/internal/portfolio/** together
      v
R3a V17 repair_archive + repair_audit, TIMESTAMP(3) preflight + alter
    V18 BTC holding -> BTC-USD; archive+remove synthetic BTC history; drop BTC price row
    V19 MM.NS -> M&M.NS across holdings, prices, history
    + tuple projection and freshness response (they read observed_at)
      |
      v
    Post_Migration_Integrity_Assertion   (gate: blocks startup on failure)
      |
      v
R3b Mongo_Repair Job, invoked explicitly after the assertion passes
      |          lease + per-document fencing; refresh Job still suspended
      v
R4  deploy artifact (defaults true, still overridden false -> no behaviour change)
      |
      v
    terraform apply: remove enforcement overrides, min_replicas 0 -> 1 on consumers
      |          ingress STAYS disabled through this apply
      v
    verify active revision, image digest, gates true, per replica; verify refresh Job image
      |
      v
    RUN refresh Job once via execution-template override  (persisted config still disabled,
      |          ingress still closed)
      |          verify image, catalog version, outcome, Kafka delivery, projection drain
      v
    terraform apply: min_replicas 1 -> 0  (verify this revision too)
      |
      v
    persist MARKET_DATA_JOB_RUNNER_ENABLED=true; re-enable ingress
```

---

## Components and Interfaces

### 1. `common-catalog` (new module)

```java
public record CatalogEntry(
        String ticker,
        String name,
        List<String> aliases,
        String assetClass,
        String quoteCurrency,
        LifecycleStatus lifecycleStatus) { }

public enum LifecycleStatus { ACTIVE, DEPRECATED }

public final class SupportedCatalog {
    public static SupportedCatalog load();            // throws CatalogLoadFailedException
    public List<CatalogEntry> all();
    public List<CatalogEntry> active();
    public Optional<CatalogEntry> find(String ticker);
    public boolean isActive(String ticker);
    public List<CatalogEntry> byAssetClass(String assetClass);
    public String version();                          // 16 hex chars
}

/** Injected only into the seeder. Not reachable from validation, the asset API, or valuation. */
public interface SeedCatalogView {
    Optional<BigDecimal> basePrice(String ticker);
}

public final class CatalogLoadFailedException extends RuntimeException { }
```

Integrity validation runs inside `load()` and rejects, with every violation collected into one
message rather than failing on the first: blank ticker, blank name, null aliases, blank asset class,
blank quote currency, absent or unrecognised lifecycle status, `basePrice` null or non-positive,
duplicate tickers, and the absence of any active entry in a supported asset class. It asserts no
total or per-class count — corporate actions change those legitimately.

`load()` throws on failure and never returns an empty or partial catalog. There is no fallback to a
cached or previous version.

### 2. Manifest schema

```json
{
  "ticker": "AAPL",
  "name": "Apple",
  "aliases": ["Apple", "Apple Inc"],
  "assetClass": "US_EQUITY",
  "quoteCurrency": "USD",
  "basePrice": 195.89,
  "lifecycleStatus": "ACTIVE"
}
```

`lifecycleStatus` is added to all 160 entries in the same commit as the validation that requires it.
`MM.NS` becomes `M&M.NS` and stays `ACTIVE`; `TATAMOTORS.NS` becomes `DEPRECATED` and keeps its
ticker so existing holdings retain a referent. `USDINR=X` is untouched.

### 3. Build packaging

In each consuming module:

```gradle
processResources {
    from(rootProject.file('config/seed-tickers.json')) { into 'catalog' }
}
```

The `copySeedTickers` tasks and the three tracked `src/main/resources/seed/seed-tickers.json` copies
are deleted. Nothing writes into a tracked directory.

### 4. `SupportedAssetValidator` — portfolio-service

```java
void requireActive(String ticker);   // throws UnsupportedAssetException
```

Matches on canonical ticker only. It performs no alias resolution and no normalisation: a near-miss
that silently resolved to a different instrument would persist the wrong holding, which is worse
than a rejection. Alias handling stays in `insight-service`, where a wrong guess produces a visible
answer rather than a stored position.

Called by the service methods behind both the holdings endpoint and the seeder. Deprecated positions
may be reduced or removed but not created or increased.

### 5. `UnsupportedAssetException` and its mapping

```java
@RestControllerAdvice
class SupportedAssetExceptionHandler {
    @ExceptionHandler(UnsupportedAssetException.class)
    ResponseEntity<Map<String, Object>> handle(UnsupportedAssetException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
            "error", "unsupported_asset",
            "ticker", e.ticker(),
            "catalogVersion", e.catalogVersion()));
    }
}
```

### 6. `MarketPriceProjectionService` — rewritten

Currency resolution precedes any comparison, because a null currency cannot participate in tuple
equality against a `NOT NULL` column:

- null incoming — resolve from the catalog entry; unresolvable, reject and surface
- non-null incoming — must equal the catalog's `quoteCurrency`; mismatch, reject and surface
- ticker absent from the catalog — reject and surface, independent of currency (cutover-gated)

Never default to `USD`: doing so would silently mis-denominate every `.NS` and `=X` instrument.

Tuple upsert:

```sql
INSERT INTO market_prices (ticker, current_price, quote_currency, observed_at, updated_at)
VALUES (:ticker, :price, :currency, :observedAt, now())
ON CONFLICT (ticker) DO UPDATE
   SET current_price  = EXCLUDED.current_price,
       quote_currency = EXCLUDED.quote_currency,
       observed_at    = EXCLUDED.observed_at,
       updated_at     = EXCLUDED.updated_at
 WHERE market_prices.observed_at IS NULL
    OR EXCLUDED.observed_at > market_prices.observed_at
```

This single predicate expresses every transition. Known over stored-null writes (left disjunct).
Strictly newer writes (right disjunct). Older does not write. **Null incoming over stored known does
not write**, because `NULL > timestamp` is `NULL`, not true — the downgrade the requirements forbid
is prevented by three-valued logic rather than by a branch. Null over null writes and leaves
`observed_at` null.

That reasoning is standard three-valued SQL semantics, but it is the mechanism the whole
null-transition contract rests on and it is **not** verified here. The implementation must prove all
six transitions — null over known, older over known, equal over known, newer over known, known over
null, null over null — against a real Postgres, asserting rows-affected for each. A test that
exercises only the happy path would pass while the predicate silently permitted the downgrade.

Zero rows affected is ambiguous, so the service disambiguates: if the stored and incoming timestamps
are equal and the payload is identical, it is a no-op; if equal and conflicting, it raises. Equal
timestamps carrying different prices indicate an upstream fault that last-write-wins would conceal.

History append runs only when `observedAt` is present. An undated event updates the latest row and
appends nothing — a receive-time substitute would fabricate provenance, asserting an observation
that never occurred. The undated path emits an observable signal so a producer emitting undated
events is detectable rather than silently degrading every row it touches to `UNKNOWN`.

### 7. Freshness computation — portfolio-service

```java
enum FreshnessState { FRESH, STALE, UNKNOWN, MISSING }

static FreshnessState evaluate(boolean priceRowPresent,
                               Instant observedAt,
                               Duration threshold,
                               Instant now);
```

Row presence is an explicit parameter because `MISSING` and `UNKNOWN` both lack a timestamp and a
function taking only the timestamp could not return both. The function is pure and testable without
a database or a running refresh.

Portfolio-level reduction takes the most severe state under `MISSING` > `UNKNOWN` > `STALE` >
`FRESH`. An empty portfolio is `FRESH` with all three counts zero, the timestamp absent, and
`partialValuation` false — the reduction has no result over an empty set, so it is defined outright.

Threshold is `(N × Refresh_Cycle) + grace`, both configurable, with concrete defaults **N = 2** and
**grace = 2h**, giving **50 hours** against a 24-hour cycle. `N = 1` was rejected: a price refreshed at
08:00 is 24 hours old at 07:59 the next morning, so the boundary would sit exactly where the normal
cycle lands and a few minutes of job delay would flip healthy prices to `STALE`. At `N = 2` a single
missed refresh is tolerated and two consecutive misses report stale, which is the condition worth
surfacing. The grace absorbs job start-time jitter. These values are the test fixtures' inputs, so
they are stated here rather than left to configuration discovery.

### 8. Summary response

```jsonc
{
  "totalValue": 53650.64,
  "partialValuation": false,           // unchanged meaning: a holding was excluded
  "assetPriceFreshness": {
    "state": "STALE",
    "oldestKnownAssetPriceObservationTimestamp": "2026-08-14T08:00:12Z",
    "staleHoldings": 1,
    "unknownPriceHoldings": 0,
    "missingPriceHoldings": 0
  }
}
```

The field is named for what it covers. It describes asset-price age only; FX rate age is out of
scope and has no timestamp persistence today. A name implying whole-valuation freshness would
reproduce, in a new field, the overclaim `partialValuation: false` makes now.

Stale and unknown holdings stay in `totalValue` at their last known price. Missing ones are excluded
and set `partialValuation`.

### 9. `market-data-service` refresh

`resolveTrackedTickers()` returns the active catalog entries. The Mongo union is removed. Cadence,
the ACA Job's role as sole production refresh path, the disabled in-service scheduler, and
skip-and-keep-last-price on a provider miss are all unchanged. Deprecated entries are not fetched,
and their existing price rows are not deleted — deprecated positions still need valuation.

### 10. `insight-service`

`TickerCatalogService` keeps its normalization and grounding view and delegates loading, integrity,
and versioning to `common-catalog`. Its fail-open behaviour — log, set empty, continue — is removed:
an empty catalog at a validation boundary rejects every write while presenting as a validation
outcome rather than an outage.

### 11. Catalog version and load-failure observability

A Java `version()` method is not remotely verifiable, and the requirement is that every deployed
service can be *observed* to agree — per replica, since a fleet in disagreement is exactly what this
proves absent.

**Verification is by structured startup log, not by HTTP.** Each catalog-consuming service emits one
line at startup:

```
catalog_loaded version=a6caba55d3ea047b entries=160 active=159
  rejectUnsupportedEvents=true enforceHoldingInvariant=true
```

Both gate values are included because they are rollout evidence: a catalog version alone proves
agreement about the catalog and nothing about whether enforcement is on. `entries` and `active`
accompany the hash so a disagreement is diagnosable rather than merely detected.

**The evidence must identify the exact fleet being reopened.** Querying a time window and comparing
against an "expected replica count" is unstable under `min_replicas = 0`, autoscaling, replica churn,
and the temporary replicas a deployment itself creates. The gate therefore filters on identity, not
on cardinality:

| filter | source |
|---|---|
| Container App name | `ContainerAppName_s` |
| revision — the R4 revision specifically | `RevisionName_s` / `CONTAINER_APP_REVISION` |
| image tag or digest | deployment output, cross-checked against the revision's configured image |
| replica identity | `ContainerGroupName_g` / `CONTAINER_APP_REPLICA_NAME` |
| rollout time boundary | query window bounded by the revision's creation time |

**Verification is at the active-revision level, not the replica-cardinality level.** In single-revision
mode every replica of a revision runs one immutable configuration, so proving that the active revision
is the R4 revision, and that every replica *observed* under it reported the expected tuple, is a
stronger and more stable claim than asserting a replica count that autoscaling is free to change
between check and reopen. The assertion is: the active revision equals the R4 revision, its configured
image matches the deployed digest, and every startup line observed under that revision reports one
distinct `(version, rejectUnsupportedEvents, enforceHoldingInvariant)`.

**Waking the services needs a mechanism, and during the maintenance window there isn't one.**
`min_replicas = 0` on the catalog consumers, and with gateway ingress disabled no caller can reach
them, so "explicitly woken" was an instruction without an actor. For the verification window the three
catalog consumers are temporarily set to `min_replicas = 1` by the same Terraform apply that removes
the enforcement overrides. They start, emit their startup lines, and are verified.

Restoring `min_replicas = 0` afterwards **creates another revision**, so that final revision is itself
subject to the same identity and image verification — otherwise the verified revision is not the one
left running. The restore apply is therefore part of the cutover, not cleanup after it.

**The refresh Job must be verified separately.** `market-data-refresh-job` is a distinct
`azurerm_container_app_job`, and it — not the long-running `market-data-service` replica — is the
production producer whose narrowing R2 depends on. A healthy service replica proves nothing about the
Job. Verification confirms the Job's configured image is the expected R2/R4 image, that no execution
of a prior revision remains running, and that its catalog version is observed in the logs of one
controlled execution.

Three reasons this beats the obvious alternative of `exec`-ing into each replica and curling
`/actuator/info`:

- **The runtime image has no HTTP client.** The final stage is
  `amazonlinux:2023-minimal` installing only `ca-certificates`; the `curl-minimal` note in these
  Dockerfiles belongs to a *builder* stage. An exec-and-curl design would require adding curl to the
  production image purely for a deployment check.
- **Scale-to-zero.** `min_replicas = 0`, so enumeration can find no replicas at all and would need a
  wake-and-stabilise step whose own success is unverifiable. Logs record what each replica reported
  when it *did* start.
- **It is already proven here.** This is the mechanism that verified the seeder fix in production
  earlier in this work, including per-replica attribution.

`/actuator/info` still carries the same values for interactive debugging, and exposure is added for
`portfolio-service` and `insight-service` — scoped to `health,info`, never a wildcard, which would
publish `env` and `configprops` on services holding database and API credentials. It is not the
deployment gate.

`catalog_load_failed` is emitted as a structured event carrying the resource path, the violation
list, and the service name, immediately before the startup failure propagates. It is deliberately
distinct from the request-level `unsupported_asset` error: the two have different causes and
different operator responses. Because the failure aborts startup, the event is the only artefact that
will exist — there is no healthy process left to query.

### 12. Migrations

- **V17** — creates **both** `repair_archive` and `repair_audit` (below), then
  `ALTER TABLE market_prices ADD COLUMN IF NOT EXISTS observed_at TIMESTAMP(3) NULL` and
  `ALTER TABLE market_price_history ALTER COLUMN observed_at TYPE TIMESTAMP(3)`. Both halves of one
  observation identity must carry the same precision, or the normalisation in D9 is undone by the
  schema.

  **The history alteration is lossy and needs a preflight.** Truncating to milliseconds can collapse
  two rows whose `observed_at` differed only below the millisecond, and `(ticker, observed_at)` is a
  uniqueness key — so the `ALTER` would either fail on a constraint or silently merge two distinct
  observations. Before altering, V17 groups history by `(ticker, date_trunc('milliseconds',
  observed_at))`: groups whose rows carry identical payloads are collapsed, with the discarded rows
  archived as `COLLISION_LOSER`; any group with conflicting payloads **aborts the migration** before
  the type change. Discovering this after the `ALTER` would mean the data is already merged.
- **V18** — `BTC` holding to `BTC-USD` preserving quantity and cost basis; archive the synthetic
  `BTC` history rows verbatim to `repair_archive` with reason `LEGACY_SYNTHETIC` and delete them from
  `market_price_history`; delete the orphaned `BTC` `market_prices` row; collision rules applied
- **V19** — `MM.NS` to `M&M.NS` across `asset_holdings`, `market_prices`, `market_price_history`

**Collision outcomes are decided here, not left as alternatives.**

*Holdings.* Where a portfolio holds both source and destination, quantities are **combined** — not
refused. The two rows denote the same instrument under two names, so refusing would leave the
portfolio permanently violating the invariant with no automatic path out.

The merged cost basis is the **quantity-weighted average**, the only merge preserving total book
cost: `(q1·c1 + q2·c2) / (q1 + q2)`. Four cases that formula does not cover are decided here, because
each one makes it either invalid or undefined:

| case | outcome |
|---|---|
| `cost_basis_currency` differs between the two rows | **surface and abort.** Weighted averaging across currencies is meaningless — it would add rupees to dollars. A currency mismatch on one instrument is a data fault needing a human. |
| either `avg_cost_basis` is null | the **entire basis tuple** becomes unavailable — basis, currency, `as_of`, and source all null. Substituting the surviving side would present one lot's cost as the merged position's. Both originals are archived. |
| `q1 + q2 <= 0` | **surface and abort.** The formula divides by the total, and the current API permits zero and negative quantities, so this is reachable. A non-positive merged position is not something a migration should invent a rule for. |
| `cost_basis_as_of` | take the **later** of the two (`max`). The column records when the basis was *captured*, not when the asset was acquired, and the merged basis is only as well-founded as its most recently captured input. Taking the earlier value would assert the complete merged position had a known basis from a date when half of it did not. |

`cost_basis_source` becomes `MERGED` **on a successful non-null merge only**, so the provenance is
visible in the row itself and not only in the archive. Where the basis tuple is unavailable per the
table above, every basis field including `cost_basis_source` is null — a `MERGED` source alongside a
null basis would assert a merge that produced nothing.

*`market_prices`.* Retain the row with the newer `observed_at`; known beats null; where both are
null retain the destination. The discarded row's values are written to the migration audit record
before deletion.

*`market_price_history`.* Identical `(ticker, observed_at, price, quote_currency)` collapses;
the same key with a conflicting payload is surfaced and the migration aborts rather than choosing.

**`MM.NS` history migrates; `BTC` history is archived and removed.** `MM.NS` and `M&M.NS` denote one
instrument whose observations are real, so its series moves to the destination key and continuity is
preserved. The legacy `BTC` history rows are **synthetic V2 seed values** at April-2026 timestamps
for a symbol this system never tracked against a provider. Merging them into `BTC-USD` would inject
fabricated points into a series of genuine observations and corrupt every change figure computed
across that window.

Leaving them under the `BTC` key would be the same defect wearing a different ticker: they would stay
in `market_price_history`, which is operational data every consumer of that table can query, not an
archive. Calling them an "audit record" while they sit in the live table does not make them one.

They are therefore **copied verbatim into `repair_archive` with reason `LEGACY_SYNTHETIC`, then
deleted from `market_price_history`**. The archive copy is exact — every column, unaltered — so the
removal is reversible and auditable. The `market_prices` current row for `BTC` is deleted outright,
because that is the row valuation reads.

Removed rows are exempt from preservation: the orphaned `BTC` current-price row and collision losers
are discarded by design, with their prior values captured in the audit record first.

### 13. `Post_Migration_Integrity_Assertion`

Runs after migrations, before write paths are enabled. Asserts both that holdings the migration
created or replaced name active assets, and that every persisted holding resolves to some catalog
entry. Checking only referential integrity would be too weak: it admits deprecated assets, so a
migration could create a new deprecated position and pass its own gate. Failure blocks startup. It is
a gate, not a diagnostic.

**The first check needs evidence the assertion cannot reconstruct.** After the fact, a deprecated
holding is just a row — nothing distinguishes one a migration created from one it deliberately left
alone, so an assertion running afterwards could only check the weaker property. Each repair therefore
writes an **audit record** naming the `(portfolio_id, asset_ticker)` rows it created or replaced, the
prior values of anything it discarded, and the migration version. The assertion evaluates the active
check against exactly that set and the referential check against the whole table.

Concrete postconditions, per migration:

| migration | postcondition |
|---|---|
| V18 | no `asset_holdings` row has `asset_ticker = 'BTC'`; no `market_prices` row has `ticker = 'BTC'`; **zero** operational `market_price_history` rows have `ticker = 'BTC'`; `repair_archive` cardinality for `(V18, market_price_history, LEGACY_SYNTHETIC)` equals the pre-migration `BTC` history row count captured by the migration; and every archived `payload` satisfies `payload = to_jsonb(pre_migration_row)` with a proven typed round-trip for every column |
| V19 | no row in `asset_holdings`, `market_prices`, or `market_price_history` has ticker `MM.NS`; `M&M.NS` history count equals the pre-migration `MM.NS` count plus any pre-existing `M&M.NS` rows, less collapsed duplicates recorded in the audit |
| all | every ticker named in the audit record is `ACTIVE` in the Supported_Catalog |

### 14. `DemoPortfolioInitializer`

**The seed operation enumerates active entries.** `PortfolioSeedService` currently iterates
`registry.all()`. Left unchanged, it would write `TATAMOTORS.NS` — and once enforcement is on, the
validator would reject that row and roll back the *entire* seed, so the operation meant to repair the
demo portfolio could never succeed. Worse, the initialiser's comparison expects the active set while
the operation writes the full set, so it would diverge on every run and never converge. Both the E2E
and demo seed paths therefore enumerate `SupportedCatalog.active()` and obtain `basePrice` through the
seed-only view.

Runs only when `app.demo.seed-on-startup=true` (default false). Acquires `pg_advisory_xact_lock` on
the same transaction and connection that performs the comparison, delete, and recreate — the existing
seed operation is transactional and destructive, and `portfolio-service` runs up to three replicas.

Compares the **complete desired holding state** — ticker set, quantities, cost basis, currency, and
source — against the active catalog, and invokes `PortfolioSeedService.seed(DEMO_USER_ID)` only on a
difference. That comparison is only meaningful because `costBasisAsOf` moves from
`Instant.now().minus(25h)` to the fixed `app.demo.cost-basis-anchor`; with a moving anchor the
desired state differs on every boot and the initialiser would destructively reseed each restart.

Independent of the E2E portfolio, so a Golden-State seed cannot disturb it. Holdings only, no price
writes.

### 15. `Mongo_Repair` runner

A dedicated **manual-trigger** `azurerm_container_app_job`, invoked explicitly after R3a's
`Post_Migration_Integrity_Assertion` passes. It follows the contract the existing refresh Job already
proves works — that Job terminates because it sets `SPRING_MAIN_WEB_APPLICATION_TYPE`, runs a
`CommandLineRunner`, and calls `SpringApplication.exit` with a propagated code. Without that shape a
Spring Boot web app started as a Job never exits and the execution hangs until timeout.

| aspect | contract |
|---|---|
| trigger | manual only — never scheduled |
| image | the same `market-data-service` image, not a separate artifact |
| application mode | non-web (`SPRING_MAIN_WEB_APPLICATION_TYPE=none`) |
| runners | repair property `true`, refresh property **ABSENT** — *not* `false`. `false` activates the suspended-mode runner, which calls `SpringApplication.exit(0)` and could terminate the context out from under the repair. Exactly one runner must be created; any other combination fails startup. Full matrix below. |
| exit `0` | `COMPLETE`, or already complete on a re-invocation |
| exit non-zero | `FAILED_CONFLICT`, lost fence, timeout, or any state it cannot verify |
| timeout | bounded execution timeout, shorter than the maintenance window |
| evidence | completion state and generation recorded durably, and asserted before the cutover proceeds |

**Runner activation matrix — normative.** `MARKET_DATA_JOB_RUNNER_ENABLED=false` activates the
*suspended* runner, so "disabled" is not a safe way to describe the repair Job's refresh setting:

| context | refresh property | repair property | active runner |
|---|---|---|---|
| long-running service | absent | absent | **neither** |
| scheduled Job, normal | `true` | absent | refresh only |
| scheduled Job, suspended | `false` | absent | suspended only |
| repair Job | **absent** | `true` | repair only |
| any other combination | — | — | **fail startup** |

Not a service startup task: `market-data-service` has
`min_replicas = 0` and the maintenance window removes the traffic that would wake it, so a
property-gated startup task could deploy into a revision that never runs. An explicit Job also
supplies the ordering the pipeline cannot — the deploy workflow runs services as a parallel matrix,
so the Postgres and Mongo repairs cannot be sequenced by deployment order.

`market-data-refresh-job` remains suspended for the whole of this repair; its unfenced
read-modify-save would otherwise overwrite a fenced document.

Claims a reclaimable lease by atomic conditional update matching absent-or-expired and excluding
**both** terminal states, `COMPLETE` and `FAILED_CONFLICT`, incrementing a `generation`. A duplicate-key
error on `_id` is **not** self-explanatory and is never treated as "already held": because the filter
excludes both terminal states, it can equally mean `COMPLETE`, `FAILED_CONFLICT`, or a live lease held
by another owner. The runner reads the durable record and classifies — `COMPLETE` exits `0`,
`FAILED_CONFLICT` and an unexpired foreign lease both exit non-zero, since neither is this execution's
success.

Fencing covers **both** source and destination, in two phases with different predicates — absent-or-
lower to *acquire* (a bare `$lt` matches no pre-repair document), then `= G` to *mutate* or *delete*.
Using the acquisition predicate for mutation would block same-generation retries and would permit a
stale runner to delete an unfenced source. Predicates differ by document type, because owner,
generation, and state do not coexist on all three: lease operations match owner, generation, and
expected prior state; price documents carry only `repairGeneration`; archive writes are keyed by
`(repairId, generation, sourceCollection, sourceId)`. A zero affected-row count is **classified by rereading the document**, not assumed to be a lost fence — see D13 for the four-way table, and for the archive reconciliation that selects a winner across generations before any transition. This component is a summary; D13 is normative. Fencing metadata is removed only after `COMPLETE` and verification, never
while a `FAILED_CONFLICT` stands.

State machine `CLAIMED -> MIGRATED -> VERIFIED -> COMPLETE`, each step individually re-entrant and
bounded by a maximum duration shorter than the lease, with renewal between steps. A conflicting
`updatedAt` pair is recorded as durable `FAILED_CONFLICT` and stops the repair for operator
resolution rather than expiring and retrying forever.

Migrates `MM.NS` documents to `M&M.NS`, ordering candidates by `updatedAt` with known beating null and
destination retained on a tie of nulls. The five-field tuple — `currentPrice`, `quoteCurrency`,
`updatedAt`, `previousReferencePrice`, `previousReferenceAt` — moves whole; assembling a document
field-by-field from two sources would produce change figures describing no real interval. Discarded
documents are snapshotted with their decision into a `repair_archive` collection in the same Mongo
database before removal, since the Postgres archive is not reachable from this service.

The `repairGeneration` field is removed from destination documents only after `COMPLETE` and its
verification evidence — not merely in the release that removes the runner, since a release may ship
while a `FAILED_CONFLICT` is still outstanding.

---

## Data Models

### `market_prices` after V17

| column | type | meaning |
|---|---|---|
| `ticker` | `VARCHAR(20)` PK | canonical symbol |
| `current_price` | `NUMERIC(19,4)` | last accepted price |
| `quote_currency` | `VARCHAR(10)` NOT NULL | must equal the catalog's |
| `observed_at` | `TIMESTAMP(3)` NULL | **new** — producer processing time; null means undeterminable |
| `updated_at` | `TIMESTAMP` NOT NULL | receive time; not a freshness input |

### `repair_archive` — verbatim copies of removed rows

Every row a repair deletes or discards is copied here first, unaltered, so removal is reversible and
auditable.

| column | type | meaning |
|---|---|---|
| `id` | `BIGSERIAL` PK | |
| `migration_version` | `VARCHAR(16)` | `V18`, `V19` |
| `source_table` | `VARCHAR(64)` | `market_price_history`, `market_prices`, `asset_holdings` |
| `reason` | `VARCHAR(32)` | `LEGACY_SYNTHETIC`, `COLLISION_LOSER`, `BASIS_UNAVAILABLE` |
| `natural_key` | `TEXT` | the source row's **full-precision** identity — for `market_price_history`, the original primary key, not the millisecond-truncated timestamp |
| `payload` | `JSONB` | the complete original row, every column |
| `archived_at` | `TIMESTAMP(3)` | |

`UNIQUE (migration_version, source_table, natural_key)` is the **idempotency key**: Flyway
re-execution re-inserts nothing, and the archive cannot silently accumulate duplicates that would
make a restore ambiguous.

The key must be **full-precision**. V17's truncation preflight archives rows that collide *because*
their timestamps differ only below the millisecond; keying the archive on the truncated value would
make those losers collide with each other, and the archive would silently retain one of them. The
original primary key is used instead.

"Byte-equal" is not a defined relation between a typed relational row and a `JSONB` document — they
share no byte representation. The assertion is **semantic**: `payload = to_jsonb(pre_migration_row)`,
with a test proving every column round-trips back to its original type and value. A typed archive
table per source shape would give representation-level fidelity, and was rejected as three more tables
to maintain for a one-off repair; the round-trip proof is what makes `JSONB` sufficient.

### `repair_audit` — holdings a migration created or replaced

The `Post_Migration_Integrity_Assertion` cannot reconstruct this after the fact — a deprecated
holding is just a row, with nothing to distinguish one a migration created from one it left alone.

| column | type | meaning |
|---|---|---|
| `migration_version` | `VARCHAR(16)` | |
| `portfolio_id` | `UUID` | |
| `asset_ticker` | `VARCHAR(20)` | the ticker as written |
| `action` | `VARCHAR(16)` | `CREATED`, `REPLACED`, `MERGED` |
| `recorded_at` | `TIMESTAMP(3)` | |

`PRIMARY KEY (migration_version, portfolio_id, asset_ticker)` — same idempotency property. The
assertion evaluates the active-asset check against exactly this set, and the referential check
against the whole holdings table.

### `Observation_Timestamp` semantics

Producer processing time: assigned as `Instant.now()` per ticker inside the refresh loop, after the
batch fetch of all quotes succeeds and before that ticker's price is persisted and its event
published. It is not provider-reported — the Yahoo response carries only `symbol` and
`regularMarketPrice`, so no upstream market timestamp exists — and it trails actual retrieval by the
batch duration and the loop position.

---

## Correctness Properties

**P1 — An undated event never downgrades a dated row.** Given a stored known `observed_at`, an event
with null `observedAt` leaves price, currency, and timestamp unchanged.

**P2 — An older observation changes nothing.** No field is written when the incoming timestamp
precedes the stored one; a partial write is what would let an old price sit under a new timestamp.

**P3 — Equal timestamps are idempotent or surfaced, never last-write-wins.** Identical payload is a
no-op; conflicting payload raises.

**P4 — Latest row and history move together.** A surfaced history conflict leaves both tables
unchanged.

**P5 — No holding is created or increased for a non-active asset**, through any writer: HTTP,
application, or migration.

**P6 — Every persisted holding resolves to a catalog entry** after the repairs, and the assertion
that proves it blocks startup on failure.

**P7 — No seed or reset capability writes price state, in any profile**, while the Kafka projection
continues to write both tables.

**P8 — All deployed services report the same catalog version.**

**P9 — A catalog that fails to load prevents startup**, in every catalog-consuming service and every
normal profile, with a distinct `catalog_load_failed` signal that a request-level `unsupported_asset`
error can never represent.

**P10 — Freshness never overstates.** A null `observed_at` reports `UNKNOWN`, never `FRESH`; an
absent oldest-timestamp is not evidence of freshness; asset-price freshness never implies FX
freshness.

**P11 — No test, monitor, or verification step encodes the live catalog's size as a literal.**
Fixture catalogs may assert their own known size.

---

## Sequencing and Cutover

Three facts make this a sequence of **release artifacts**, not configuration flips.

**Flyway runs automatically at startup** (`spring.flyway.enabled: true`), so a migration file present
in a deployed jar executes on boot. Migration files are withheld from earlier artifacts entirely.

**Code cannot precede the schema it reads.** The tuple projection and the freshness response both
reference `market_prices.observed_at`, which does not exist until V17. They ship **with** V17, not
before it. Only `@Async` removal, the catalog module, the loaders, and the gated validator can land
earlier, because none of them touches the new column.

**Draining Kafka quiets only the price pipeline.** Every holding writer must be quiesced, not just
`POST /holdings`: the internal seed endpoint and the demo initialiser write holdings too.

### Gates

Each gate has an **artifact default** and, during the cutover, an explicit **Terraform override**.
The two are distinct, and conflating them is what made an earlier draft's R4 step a no-op — see the
choreography under R4.

| property | artifact default (R1–R3) | artifact default (R4) | Terraform override during cutover |
|---|---|---|---|
| `app.catalog.reject-unsupported-events` | `false` | **`true`** | explicit `false` through R3b, then **removed** |
| `app.catalog.enforce-holding-invariant` | `false` | **`true`** | explicit `false` through R3b, then **removed** |
| `app.repair.mm-ns.enabled` | `false` | removed with the repair | explicit `true` only after R3a's assertion passes |
| `app.demo.seed-on-startup` | `false` | `false` | none |

The effective value during R1–R3 is `false` from both sides, so the override is belt-and-braces rather
than load-bearing until R4 — at which point removing it, not setting it, is what turns enforcement on.

**Enforcement defaults flip to `true` in the R4 artifact.** These are temporary safety controls, and
leaving them defaulted `false` afterwards would mean a missing environment value — a new environment,
a lost Terraform variable — silently runs permissively, which is the failure this whole spec exists
to prevent. After R4, `false` is an emergency override that must be set explicitly.

**One control plane: Terraform-managed environment variables.** The gates are declared alongside the
other container-app settings, so their values are reviewable in the repository and applied by the
same mechanism as everything else. No operational workflow, no runtime toggle. The cost is that a
change requires the manual `terraform apply` this project already depends on — which is a known,
documented step, and preferable to a second control plane nobody audits.

### Releases

**R1 — catalog, inert.** `common-catalog`, manifest packaging, `lifecycleStatus` on all entries,
symbol corrections in the manifest, fail-to-start loading, three loaders replaced, `@Async` removed,
seeder switched to `active()` enumeration, validator present but gated off. **No migrations, no
`observed_at` code.**

**R2 — narrow the producer.** Refresh desired set becomes the active catalog. Every producer revision
capable of emitting the emergent set is stopped — for the ACA Job, confirming no execution of a prior
revision is *running*, not merely that a newer revision exists.

**Suspend the refresh Job.** Before draining. `market-data-refresh-job` is a separate
`azurerm_container_app_job` that does not traverse the gateway, so closing ingress does not stop it —
it keeps firing on its cron. Its Mongo write is an unfenced read-modify-save
(`findById` → `recordNewObservation` → `save`), so an execution during R3b would overwrite a fenced
document without observing `repairGeneration`, invalidating both the repair's collision decision and
its fencing guarantee. Disable the write capability and prove no execution remains running.

Suspending is preferred over making every normal Mongo writer participate in temporary repair fencing,
which would spread repair-specific logic across the service's ordinary write paths for a one-off
migration.

**"Suspend" needs an executable mechanism, and two obvious ones are wrong.** Terraform declares an
unconditional `schedule_trigger_config` on `market-data-refresh-job`. Switching to
`manual_trigger_config` forces replacement — and so does editing the cron: in the pinned AzureRM
**4.81.0** the whole `schedule_trigger_config` block is `ForceNew`, so changing its child
`cron_expression` plans destroy/create. An earlier draft proposed an impossible-date cron
(`0 0 30 2 *`) on the claim that the resource would be untouched. That claim was false.

The mechanism is instead to **disable the refresh write capability, not the trigger** — but simply
switching the existing runner off is not enough. `MarketDataRefreshJobRunner` is the **only** caller of
`SpringApplication.exit` in `market-data-service`. Removing it via `@ConditionalOnProperty` leaves a
started context with no exit path, so the execution runs to its 600-second replica timeout and is
recorded as a failure — the opposite of the clean no-op this design claims, and a direct contradiction
of its own Job-termination rationale.

Suspension therefore needs a **suspended-mode runner**: a second `CommandLineRunner`, active exactly
when the refresh runner is not, which terminates properly.

| aspect | contract |
|---|---|
| activation | mutually exclusive with the refresh runner. **When the property is explicitly present**, exactly one is created, never both. When it is **absent** — the ordinary long-running service — **neither** activates. |
| writes | none: no Mongo write, no Kafka publish, no Postgres write |
| signal | emits `refresh_suspended` with the Job execution identity, so a suspended run is positively observable |
| telemetry | flushes before exit, so the signal is not lost with the context |
| exit | `SpringApplication.exit` with code `0` — a suspended run is a **success**, not a failure |
| long-running service | the property being absent must activate **neither** Job runner, so the ordinary `market-data-service` deployment is unaffected |

`MARKET_DATA_JOB_RUNNER_ENABLED=false` on the Job then means "start, write nothing, say so, exit 0" —
and because the schedule still fires, each cadence produces positive evidence the suspension is
holding. A suppressed schedule would produce silence, indistinguishable from a broken Job.

Environment variables live in `template.container`, which is **not** `ForceNew`: the Job is updated in
place. No replacement, no re-created identity binding, no image reference to re-verify, and the
rollback is the same one-line change in reverse.

| step | action | verification |
|---|---|---|
| suspend | `MARKET_DATA_JOB_RUNNER_ENABLED=false` on the Job (in-place template update) | read back the deployed env var; confirm no execution is `Running`; after the next scheduled fire, confirm it **succeeded** and emitted `refresh_suspended` rather than a refresh |
| hold | unchanged for the whole repair | each scheduled no-op is positive evidence the suspension holds |
| controlled run | start **one execution with a template override** that enables refresh, leaving the persisted Job configuration disabled | the execution's image, catalog version, outcome, Kafka delivery, and projection drain |
| restore | persist `MARKET_DATA_JOB_RUNNER_ENABLED=true` through Terraform | read back the env var |

The controlled run uses an **execution-specific override** rather than flipping the persisted
configuration first. Azure supports starting a Job execution with its own template, so the verified
run happens while the Job's stored configuration is still disabled — a failure therefore persists
nothing to the **Job configuration**, though it may well have advanced the data plane (see below).
Only after it succeeds does Terraform write `true`. The
override must supply the **complete** template, not a patch, so the overridden template is itself
verified against the expected image before the run.

**A failed controlled run is not consequence-free.** Nothing is persisted to the *Job* configuration, but the execution may already have written Mongo documents, published events, and advanced the Postgres projection. Recovery is capture-and-reconcile — logs, exit code, how far the ticker loop progressed, then Mongo, Kafka offsets, and both price tables against what it claims — before deciding a retry is safe. A blind retry against a partially advanced projection is how a conflicting observation lands at the same timestamp.

This is strictly better than suppressing the trigger: a no-op execution on the normal cadence proves
the suspension is in force, whereas a suppressed schedule produces silence, which is indistinguishable
from a broken Job.

Updating the property does **not** stop an execution already in flight, which is why confirming no
running execution is a separate check rather than an implication of the first. **If suspension
verification fails before the repair begins — an execution still running, or a scheduled fire that
refreshed rather than emitting `refresh_suspended` — abort the cutover.** Proceeding with an
unverified suspension is what would let an unfenced writer race the repair.

**Drain.** Consumer lag zero on every partition of the projection's consumer group, observed after the
Job is suspended — draining before suspension would let a subsequent execution refill the topic — or
the retention window elapsed. Meaningful only because R1 removed `@Async`.

**Quiesce all holding writers — a maintenance window, not a route switch.** An earlier draft proposed
disabling the `/api/portfolio/**` gateway route. That is not executable and would not have worked:

- the seeder uses a **separate** route, `/api/internal/portfolio/**`, so closing the portfolio route
  leaves it open;
- neither route carries a `Method=` predicate, so disabling one removes **reads** as well as writes;
- both routes are static YAML and the repository has no dynamic route-control mechanism, so there is
  nothing to switch at runtime.

The mechanism is therefore a **maintenance window**: `api-gateway` ingress is disabled at the
Container App level, in-flight requests drain, and the operator confirms no synthetic-monitoring seed
invocation is running before proceeding. With ingress down the seed endpoint is unreachable whichever
route it uses, and `app.demo.seed-on-startup` is already `false`.

**This requires an infrastructure change, not zero change.** The `container-app` module emits its
`ingress` block unconditionally; `external_ingress` selects external versus internal and cannot
disable it. The module gains an `ingress_enabled` input and a `dynamic "ingress"` block that is
omitted when false. The claim is therefore "**no application code**" — an IaC change is required, and
the alternative (`az containerapp ingress disable`) is rejected precisely because it creates the
second control plane and the Terraform drift this design already refuses elsewhere.

The maintenance value stays disabled **through the R4 override-removal apply** and returns to enabled
only at the final step. An apply that re-enabled ingress as a side effect of removing the enforcement
overrides would reopen writes before verification.

The cost is a full API outage for the window, reads included. That is acceptable here: traffic is a
handful of recruiter visits, and a brief planned outage is cheaper than a code-bearing filter with
its own gate, its own tests, and its own failure mode. If a read-available window is ever required,
the alternative is an explicit maintenance filter matching mutating methods across **both** route
prefixes — but that is code, and it is not needed for this cutover.

**R3a — Postgres repair.** First artifact containing V17–V19, plus the tuple projection and the
freshness response, which now have their column. Flyway executes on boot against a drained,
write-closed system. Then the audit records and the `Post_Migration_Integrity_Assertion`.

**R3b — Mongo repair, as an explicitly invoked one-shot Job.** Not a startup task.
`market-data-service` has `min_replicas = 0`, and during the maintenance window there is no traffic to
wake it, so a repair gated behind a property on a startup task could deploy into a revision that never
starts and therefore never runs. A dedicated `azurerm_container_app_job`, started explicitly after
R3a's assertion passes, executes on demand and reports its own exit status.

That also supplies the ordering the deployment cannot: the deploy workflow runs services as a parallel
matrix, so `portfolio-service` and `market-data-service` boot simultaneously and pipeline sequencing
between the two repairs is not available.

The refresh Job stays suspended until this repair reaches `COMPLETE`.

**R4 — enforce.** Environment variables **override** packaged Spring configuration, so shipping an
artifact whose defaults are `true` while Terraform still supplies `false` would leave enforcement
off — the deploy would look successful and change nothing. The transition is therefore choreographed,
fail-closed at every step:

1. Explicit Terraform `false` overrides remain in force through R3a and R3b.
2. With writers closed and both repairs verified, deploy the R4 artifact — still overridden to
   `false`, so this step changes no behaviour and is safe to roll back.
3. Terraform **removes** the temporary overrides **and** raises `min_replicas` from `0` to `1` on the
   three catalog consumers in the same apply. New revisions take the R4 defaults of `true`, and they
   actually start — with ingress down there is no traffic to wake a scale-to-zero revision, so
   without this they would never emit the evidence step 4 requires. **Ingress stays disabled through
   this apply.**
4. Verify those exact revisions (below) — not "the fleet", but the specific revisions created in
   step 3 — and separately verify `market-data-refresh-job`'s configured image.
5. **Run the refresh Job once via an execution-template override, while its persisted configuration
   is still disabled and ingress is still closed.** Verify its image, its catalog version, its outcome, that its events reached Kafka, and
   that the projection drained them. This is the end-to-end enforcement test: the producer is
   narrowed, rejection is active, and the whole path is exercised **before** any user can reach it.
   The pre-ingress gate requires this evidence, so it cannot be deferred until after ingress returns.
6. Terraform restores `min_replicas` to `0`. This creates **another** revision, which must be verified
   — but **not** by startup log: a scale-to-zero revision may legitimately never start, so waiting for
   an evidence record could block forever and accepting its absence would be vacuous. This revision is
   verified at **configuration level** instead: `az containerapp revision show` confirms it is active,
   carries the expected image digest, and carries no enforcement override. That is sufficient because
   the gate values live in the revision's configuration, so any replica that later starts inherits
   them; the startup-log check on the step-3 revision already proved the same configuration produces
   the expected runtime behaviour.
7. Persist `MARKET_DATA_JOB_RUNNER_ENABLED=true` through Terraform, then re-enable ingress. The
   schedule was never altered, so nothing about the trigger needs restoring.

That ordering also resolves the apparent tension between "artifact default" and "Terraform is the
control plane": Terraform remains the plane, and what it manages is the *presence or absence of an
override*, not the steady-state value. The steady state lives in the artifact, so a new environment
that forgets the variable inherits enforcement rather than permissiveness.

Emergency rollback adds explicit `false` overrides — **only after writes are quiesced**.

### Rollout evidence

Before re-enabling ingress, verified by **structured startup log** rather than HTTP — see the
verification component for why, and for the exact filters:

- the **active revision** of each catalog-consuming service is the R4 revision, and its configured
  image matches the deployed digest;
- every startup line observed under that revision reports one distinct
  `(catalogVersion, rejectUnsupportedEvents, enforceHoldingInvariant)`, with both gates `true`;
- each service is running because the same apply raised `min_replicas` to `1`, so an empty result set
  from a scaled-to-zero service cannot be mistaken for agreement — and the later restore to `0`
  produces a further revision verified at **configuration level** (`az containerapp revision show`),
  not by startup log, since that revision may legitimately never start;
- `market-data-refresh-job` is separately confirmed: configured image is the expected one, no prior
  revision's execution remains running, and its catalog version is observed in the controlled
  execution of step 5 — which runs **while ingress is still closed**, so this evidence exists before
  the gate it feeds.

This is deliberately **not** an assertion about replica count. Cardinality is unstable under
`min_replicas = 0` and autoscaling, and a revision-level claim is both stronger and stable: in
single-revision mode every replica of a revision runs one immutable configuration.

### Rollback

Disabling the holding validator **requires quiescing writes first**, and writes stay closed until the
forward fix deploys. Re-opening writes against a permissive fleet is what produced the original
defect class; a rollback that restores permissiveness while writes are live would recreate it
deliberately. Disabling projection rejection is **degradation, not a safe operation**. It re-admits exactly the
out-of-catalog and currency-conflicting events the boundary exists to stop. R2 constrains the
*intended* producer; it does not make a malformed, stale, or unexpected producer impossible, which is
the case rejection defends against. It is available as an emergency measure, and while it is off the
rejection counters must be alerting so the re-admitted volume is visible rather than silent.

**Rolling from R4 back to an R3 artifact is not a lesser action than disabling the validator — it is
the same action.** The R3 artifact's packaged defaults are permissive, so deploying it restores
permissiveness implicitly, without anyone setting a property. That rollback therefore falls under the
same rule: quiesce writes first, and keep them closed until the forward fix deploys.

Rolling back past R3a is a database restore, not a flip. R3a is the **first** irreversible step, not
the last — R3b rewrites Mongo documents and is irreversible too. A Postgres backup does not restore
the Mongo collection, so verified Mongo recovery evidence is required before R3b, separately from
the Postgres backup taken before R3a.

Only the drainage condition is waivable, explicitly and on the record, with discarded events counted
and surfaced. R2's narrowing and R3a's repair are not.

## Open Decisions

- **`TATAMOTORS.NS` successor allocation.** `TMCV.NS`, `TMPV.NS`, or both, and how an existing
  quantity splits. Deliberately unresolved: it is a corporate-action decision, not a symbol swap.
  Until it is made, the entry stays `DEPRECATED` and its positions are retained untouched.
- **`Fx_Rate_Age`.** Out of scope here and recorded as a follow-up. Covering it requires FX timestamp
  persistence, which does not exist today, plus a second dimension in the freshness reduction.
