# Design Document

> **Revision 3 — 2026-08-16.** Incorporates the second design review (checkpoint entry [23]), which
> did not clear the gate. Six blocking groups, all accepted after independent verification —
> including both runtime versions, re-resolved from the Gradle cache rather than taken on trust.
>
> 1. **The locking model would have incremented twice on this repository's actual runtime.** Revision
>    2 stacked `OPTIMISTIC_FORCE_INCREMENT` with a dirtying flush. Under
>    `hibernate-core:7.4.1.Final`, the flush takes the entity `N → N+1` and the force-increment's
>    before-completion callback then takes it `N+1 → N+2`. Replaced with **one** explicit parent
>    `UPDATE … SET version = version + 1 WHERE id = ? AND version = ?` requiring exactly one affected
>    row. `OPTIMISTIC_FORCE_INCREMENT` is now used nowhere.
> 2. **The absent branch accepted any expected version.** It tested only for absence, so a caller
>    sending `7` against an absent aggregate would have created one at version `1`. Now total:
>    non-zero returns the conflict with virtual current version `0`, and whole-set validation runs
>    before the insert.
> 3. **One `DesiredHolding` type could not express both callers.** The primitive was specified to
>    apply the composition cost-basis rule *and* to receive the seeder's deterministic bases — which
>    cannot both hold. Preparation is now split from persistence: two tuple adapters feed one
>    replacement primitive that accepts only fully materialised states.
> 4. **The seed-state endpoint violated a frozen requirement.** It was a separate Portfolio_Version
>    endpoint, which the requirements prohibit outright; scoping it to the E2E user does not change
>    what it is. Replaced with login plus the authenticated portfolio read — the credentials are
>    already in that job — and a post-migration compatibility artifact that Revision 2 never
>    allocated. The `409` workflow outcome is now chosen rather than deferred.
> 5. **The HTTP boundary was neither backward-compatible nor strict.** Spec A also froze the singular
>    `ticker` field, which Revision 2 replaced with `tickers`; both now coexist. Boxed `Long` detects
>    absence but not coercion — `jackson-databind:3.1.4` defaults to `TryConvert` with
>    `ACCEPT_FLOAT_AS_INT`, so `7.9` and `"7"` would have become valid versions. A property-scoped
>    strict deserializer is required. And Spec A's singular `UnsupportedAssetException` cannot report
>    every offender, so B1 adds a distinct plural exception rather than looping the singular one.
> 6. **The rollback floor could restore a forbidden writer.** Artifact 0 retired only the creator,
>    leaving the versionless holdings `POST` until Artifact 3 — so rolling back restored it. Both
>    legacy writers now retire in Artifact 0, which is what makes the floor executable rather than
>    aspirational.
>
> Also: the Architecture diagram still carried the invented `find(OPTIMISTIC_FORCE_INCREMENT, …)` and
> the wrong error label; the Data Models section still called the type conversion a "widening" and
> still claimed the affected code was out of scope, the same stale-summary pattern entry [22] said
> had been corrected; `@PrePersist` now captures one instant for both timestamps and the dual-schema
> insert omits both; no-op equality is decided on the persisted `NUMERIC(19,8)` representation, since
> `BigDecimal.equals` would report `0.75` and `0.75000000` unequal; five correctness properties
> added; the release graph is stated as five releases rather than a narrative count that disagreed
> with its own list.
>
> **Revision 2 — 2026-08-15.** Incorporates the first design review (checkpoint entry [21]), which
> did not clear the gate. Five blocking groups, all accepted after independent verification. Two
> were errors in this document rather than gaps in it.
>
> 1. **The version mechanism was mechanically wrong.** `OPTIMISTIC_FORCE_INCREMENT` does not accept
>    or compare a caller-supplied version — `find(OPTIMISTIC_FORCE_INCREMENT, expectedVersion)` was
>    not a real JPA operation — and its increment may be immediate or deferred to flush by provider.
>    Acquiring it before deciding whether anything changed, then marking a *successful* transaction
>    rollback-only for the no-op path, made the returned version provider-dependent and coupled
>    business flow to programmatic rollback. D1 is replaced with a two-phase path: ordinary
>    optimistic load, explicit version comparison, and force-increment plus parent-CAS flush only
>    when state actually changes.
> 2. **The release sequence created the window the requirements forbid.** Artifact 2 carried the
>    unique constraint while `POST /api/portfolio` was still retired only in Artifact 3 — and
>    sharing a release would not have helped, since Flyway runs at portfolio-service startup while an
>    older revision can still serve the creator. A migration-free **Artifact 0** now retires it
>    first. The rollback plan violated the invariant in both directions and is replaced with a
>    rollback floor. G4 becomes a **pre-deploy** gate, because `/api/portfolio/**` makes the
>    composition `PUT` reachable as soon as its revision takes traffic.
> 3. **The error envelope contradicted Spec A.** Spec A froze `error: "unsupported_asset"`; Revision
>    1 wrote `code: "unsupported_assets"`, changing field name and pluralization on an inherited
>    contract. Restored, with `tickers` added additively. Separately,
>    `HttpMessageNotReadableException` does not fire for a *missing* field — it deserializes to
>    `null` — so missing-version and invalid-version now come from two different framework
>    boundaries, and `expectedVersion` is a boxed `Long` with `@NotNull` because a primitive would
>    default a missing field to `0`, which is a meaningful value in this contract.
> 4. **The seed bridge was unsequenced, and widened a destructive endpoint.** Revision 1 proposed a
>    `{userId, expectedVersion}` body; the controller is deliberately fixed to the E2E user, and
>    making the target caller-supplied would have broadened a production-reachable daily writer with
>    no requirement behind it. The target stays fixed. A narrow internal `seed-state` read is added,
>    because the workflow has only `X-Internal-Api-Key` and cannot call the authenticated API, and
>    the signature change is sequenced in four steps.
> 5. **Aggregate_Creation would have flushed a null `updated_at`.** `@PrePersist` sets only
>    `createdAt`; Hibernate binds mapped fields explicitly, so the column default never applies.
>
> Also: V17 uses a named table constraint rather than a bare unique index; `PortfolioSeedServiceIT`'s
> literal `EXPECTED_HOLDINGS = 160` is replaced with active-catalog cardinality so this spec does not
> reintroduce Spec A's fixed-count defect; five correctness properties added; O1, O2 and O4 closed,
> and O3's rationale corrected — it was wrong about scope, and the real reason to defer is unrelated
> migration risk.
>
> **Revision 1 — 2026-08-15.** First design draft for B1, opened after the requirements gate cleared
> at Revision 6 (`git hash-object cbba0b38741bf2358f6605ca21f5fa8912f2e2b1`, checkpoint entry [19]).
>
> **This document uses name-based references only.** It cites requirement *concepts* by their
> glossary name, never by criterion number. Ten reference defects were found across six requirements
> revisions, eight of which resolved numerically and passed the checker — the drift is caused by
> numeric citations surviving renumbering. Spec A's design reached the same conclusion and this
> follows it. Where a specific rule must be pinned, it is quoted rather than numbered.

## Overview

The requirements settled *what* the composition contract guarantees. This design makes those
guarantees executable against the code that exists, and its shape is dictated by five structural
facts, each verified against `main`.

**Nothing in the aggregate carries a version today.** `Portfolio` is `(id, userId, createdAt,
holdings)`. There is no `@Version`, no `updated_at`, and `PortfolioResponse` exposes neither. Every
part of the optimistic-concurrency contract is new construction, not configuration.

**The parent-child mapping is exactly the one the requirements forbid relying on.**
`Portfolio.holdings` is `@OneToMany(mappedBy = "portfolio", cascade = ALL, orphanRemoval = true)`.
Adding `@Version` to `Portfolio` would *appear* to work in casual testing while leaving the guarantee
resting on whether a given child mutation happens to dirty the parent. The requirements mandate an
explicit parent-version mechanism for this reason, and this design implements it as a single
explicit parent-row compare-and-set issued before any child write — see D1, which also records why
the two JPA-level mechanisms tried in earlier revisions were both wrong.

**The seeder deletes the aggregate.** `PortfolioSeedService.seed()` opens with
`portfolioRepository.deleteAll(existing); flush()` and then creates a fresh `Portfolio`. Its
controller, `PostMapping("/seed")`, takes no body and no parameters. Both must change — the delete
because it destroys the identity and version the contract depends on, the signature because a reset
must carry the expected version observed at its eligibility decision.

**There is no stable error envelope to reuse.** `GlobalExceptionHandler` returns ad-hoc
`Map<String, String>` and `Map<String, Object>` bodies, with no code field and no shared shape. The
requirements demand stable machine-readable codes that B2 can branch on, so the envelope is new
work rather than an extension.

**The gateway already writes the tables it needs to write.** `GatewayAuthDataConfig` supplies a
`NamedParameterJdbcTemplate` and a `TransactionTemplate` against the same database, gated on
`spring.datasource.url`, and `UserCredentialRepository` already inserts into `users`. Signup-time
provisioning is one more statement inside a transaction that exists, which is what makes the staged
gateway-first cutover cheap enough to prefer.

### Key design decisions

#### D1 — Two-phase optimistic path: explicit version comparison, force-increment only when state changes

`Portfolio` gains `@Version private long version`. No composition or reset code path relies on JPA
noticing a child change.

**Revision 1 got the mechanics wrong and this supersedes it.** `OPTIMISTIC_FORCE_INCREMENT` does
**not** accept or compare a caller-supplied version — it operates on the version snapshot held by
the managed entity — so `find(OPTIMISTIC_FORCE_INCREMENT, expectedVersion)` was not a real JPA
operation. The forced increment may also be applied immediately or deferred to flush depending on
the provider, so acquiring it *before* deciding whether anything changed makes the returned version
depend on provider timing. And the no-op path in Revision 1 relied on marking a **successful**
transaction rollback-only, which couples ordinary business flow to programmatic rollback — an
invasive fallback rather than the normal transaction model.

**Revision 2 was also wrong, in a way that only shows up on this repository's actual runtime.** It
combined *two* independent version mechanisms on the changing path: `OPTIMISTIC_FORCE_INCREMENT`,
plus setting `updatedAt` and flushing — which dirties the versioned parent and performs an ordinary
versioned update on its own. Verified against `org.hibernate.orm:hibernate-core:7.4.1.Final`, the
version this repository resolves: `OptimisticForceIncrementLockingStrategy` registers
`EntityIncrementVersionProcess` as a **before-transaction-completion** callback. The requested flush
takes the dirty entity `N → N+1`; the callback then reads that updated entry and forces
`N+1 → N+2`. Exactly-once fails by construction, and it fails at commit rather than anywhere a
casual test would look.

There is **one** parent CAS, expressed once:

1. Load the parent under an ordinary optimistic lock.
2. Compare `portfolio.getVersion()` to the caller's `expectedVersion` explicitly — a plain field
   comparison, not a lock mode. Mismatch → `PortfolioVersionConflictException`.
3. Validate the complete desired set.
4. Compare desired state to stored state. **Equal → return normally.** No update, no timestamp
   advance. The ordinary optimistic lock still detects a concurrent update on this path, so a no-op
   is not a hole in the concurrency contract.
5. **Different →** issue one explicit parent update:

```sql
UPDATE portfolios SET version = version + 1, updated_at = ?
 WHERE id = ? AND version = ?
```

   It must affect **exactly one row**; zero rows is the concurrency conflict. The managed entity is
   then refreshed so its in-memory version matches the database. Only after that does child DML run.

`OPTIMISTIC_FORCE_INCREMENT` is **not used anywhere in this design.** Neither is a deliberately
dirtied-parent flush — either could serve as the single CAS in principle, but stacking them is the
defect above, and an explicit statement is the shape that cannot accidentally acquire the second
mechanism later.

Running the CAS before child DML is what makes it a real compare-and-set against the database
rather than an in-memory assumption: a concurrent writer that moved the version is detected before
this transaction has touched a single holding.

Tests must assert the **response** version as well as the stored version. A database rollback alone
would not catch an incorrectly incremented in-memory DTO — and a double increment shows up in both,
which is why the exactly-once property is asserted numerically rather than as "changed".

#### D2 — Version comparison happens at lock acquisition, before anything else touches state

The ordering the requirements mandate — precondition, then no-op detection, then destructive work —
is implemented as a single sequence in the application operation:

1. Envelope decoded and validated; a failure here never reaches the operation (see D7).
2. Load parent under an ordinary optimistic lock; compare `version` to `expectedVersion` explicitly.
   Mismatch throws.
3. Validate the complete desired set: catalog resolution, lifecycle permission, quantity domain,
   uniqueness. Any failure throws before a row is touched.
4. Compare desired state to stored state. Equal → return normally, no increment.
5. Different → force-increment parent, set `updatedAt`, flush parent CAS, then child DML.

Steps 2 and 3 are deliberately in this order. A stale request with an invalid body returns the
conflict, not the validation error, because the requirements make the version precondition
authoritative within stateful validation.

#### D3 — One `CompositionService`, three entry points, no duplicated rules

One versioned replacement primitive persists holdings, but **preparation is separate from
persistence**, because the two callers do not have the same input semantics.

Revision 2 claimed all callers pass "the same `desiredSet`" while also saying the primitive applies
the composition rule (retained tickers keep their existing cost basis, new tickers capture it) *and*
that the seeder supplies deterministic cost bases. Those cannot both hold with one
`{ticker, quantity}` type: if the primitive owns the composition rule it discards the seeder's basis
for retained tickers and can never converge on the Golden-State tuple; if it accepts a full tuple,
the public DTO does not carry enough to call it.

```
CompositionRequest {ticker, quantity}  ──▶ CompositionTupleAdapter  ─┐
                                                                    ├─▶ List<DesiredHoldingState>
Golden-State catalog derivation        ──▶ GoldenStateTupleAdapter  ─┘        (fully materialised)
                                                                              │
                                                              HoldingReplacementService
                                                              (validate, compare, CAS, persist)
```

- **`CompositionTupleAdapter`** expands ticker/quantity into a full `DesiredHoldingState` by reading
  current state: retained tickers carry their existing basis tuple forward unchanged even when
  quantity changes; new tickers capture basis under the existing add-time rule.
- **`GoldenStateTupleAdapter`** supplies its deterministic tuple — quantity and cost basis — directly.
- **`HoldingReplacementService`** accepts only fully materialised states. It validates, compares
  against stored state, performs the parent CAS, and persists. It contains no caller-specific rule.

Writer convergence is preserved — there is still exactly one thing that mutates holdings and one
place the four-case matrix is proved — without pretending the two callers supply the same input.

#### D4 — Absent-aggregate creation is a distinct path that converges immediately

When no portfolio exists, `replaceHoldings` cannot acquire a lock. The path is:

```
// The absent branch is TOTAL: every expected version has a defined outcome.
if (expectedVersion != 0L) {
    throw new PortfolioVersionConflictException(/* virtual current version */ 0L);
}
validateWholeSet(desired);                  // before any insert
try {
    portfolio = new Portfolio(userId);      // version 0; @PrePersist sets BOTH timestamps
    portfolioRepository.saveAndFlush(portfolio);   // may violate uq_portfolios_user_id
} catch (DataIntegrityViolationException e) {
    if (!isNamedConstraint(e, "uq_portfolios_user_id")) throw e;
    throw new PortfolioVersionConflictException();   // current version re-read by the handler
}
applyDesiredState(portfolio, desired);      // single parent CAS → version 1
```

**Revision 2's absent branch accepted any expected version.** It tested only for absence and then
constructed immediately, so a caller supplying `7` against an absent aggregate would have created
one and returned `201` at version `1` — contradicting the creation precondition outright. The guard
above is the fix, and it needs its own test: the two-creator race never exercises a non-zero token
against absence, so that path was untested as well as unimplemented.

Whole-set validation runs **before** the insert, so an invalid desired set cannot leave a bare
portfolio row behind.

**`@PrePersist` captures one instant and assigns it to both fields**, rather than calling the clock
twice — two `Instant.now()` calls can differ, which would make the "equal at creation" semantics
below false at database precision.

**`@PrePersist` must initialize `updatedAt`, not only `createdAt`.** It currently sets `createdAt`
alone. Hibernate includes mapped fields in the INSERT, so a non-null `updatedAt` column with no
initializer is bound as an explicit `null` and the database `DEFAULT now()` never applies — the
insert fails. Both timestamps are set in `@PrePersist`; later transitions set `updatedAt` from the
service, per the component notes below.

Initial timestamp semantics are the same for all three creation paths: `createdAt = updatedAt =`
creation instant. For signup and backfill that is a portfolio at version `0` which has never
transitioned, so the two being equal is accurate. For Aggregate_Creation the subsequent transition
to version `1` overwrites `updatedAt` in the same transaction.

The creation and the holdings application are one transaction, so the externally observable result
is a single aggregate at version `1`, satisfying the requirement that absent creation ends at `1`
rather than `0`.

#### D5 — The constraint translation is name-scoped and the re-read happens outside the transaction

`isNamedConstraint` inspects the `ConstraintViolationException`'s constraint name and matches
`uq_portfolios_user_id` exactly. A blanket `DataIntegrityViolationException → 409` would misreport a
`chk_asset_holdings_quantity_positive` failure — a malformed request — as a concurrency conflict,
sending the client to re-read state that is not the problem.

The loser's current-version re-read is performed by the **exception handler**, after the failed
transaction has ended, not inside the catch block. A transaction marked rollback-only cannot observe
the winner's committed row, so a re-read inside it would return the wrong version or nothing.

#### D6 — Decimal fidelity is enforced by type, on both directions

Quantities cross the wire as JSON strings in plain decimal notation. The write side takes
`@JsonDeserialize(using = StrictDecimalStringDeserializer.class) BigDecimal quantity`, which rejects
a JSON number token outright rather than coercing it — the requirements make silent coercion a
contract violation. The read side annotates `HoldingResponse.quantity` with `@JsonSerialize(using =
ToPlainStringSerializer.class)`.

Both directions matter. The read side is the one that is wrong today: `HoldingResponse.quantity` is
a bare `BigDecimal` emitting a JSON number, so a client that reads `0.75000000`, drafts it, and
writes it back has already lost fidelity before the write contract applies. Scale 8 is in live
production use, so this is a real round-trip, not a hypothetical one.

#### D7 — One error envelope, one enum, no per-site shapes

**The machine-code field is `error`, and Spec A's value is preserved verbatim.** Spec A froze
`error: "unsupported_asset"` — singular, field named `error` — as an HTTP contract requirement. A new
unified envelope cannot silently rename the field or pluralize the value; B1 extends that contract,
it does not replace it.

Spec A's frozen 422 body is `{"error": "unsupported_asset", "ticker": …, "catalogVersion": …}` —
singular value **and singular `ticker` field**. Revision 2 kept the value but replaced the field with
`tickers`, so its claim of being "purely additive" was false.

The existing single-write endpoint keeps Spec A's body byte-for-byte. The composition endpoint, which
must report every offending ticker, adds `tickers` **alongside** `ticker` rather than in place of it;
`ticker` carries the first offender in the deterministic ordering so an existing client reading that
field still works.

```json
{ "error": "portfolio_version_conflict", "message": "...", "currentVersion": 7 }
{ "error": "unsupported_asset", "message": "...", "catalogVersion": "…", "ticker": "FOO", "tickers": ["FOO","BAR"] }
{ "error": "lifecycle_not_permitted", "message": "...", "catalogVersion": "…", "ticker": "TATAMOTORS.NS", "tickers": ["TATAMOTORS.NS"] }
```

**Spec A's `UnsupportedAssetException` is singular and stays that way.** A loop calling it fails on
the first ticker, which cannot satisfy the aggregate-reporting rule. B1 adds a distinct
`UnsupportedAssetsException` (plural) carrying an ordered collection, with its own handler; Spec A's
singular exception and handler remain untouched on their existing path. Ordering is the request's
element order, deduplicated, so the same request always reports the same first offender.

A `ContractErrorCode` enum owns every stable identifier. `GlobalExceptionHandler` gains typed
handlers producing this shape. Existing ad-hoc `Map` responses on endpoints this spec does not own
are left alone.

**Envelope failures need two framework boundaries, not one.** Revision 1 routed them all through
`HttpMessageNotReadableException`, which is wrong: that fires on malformed JSON and on a type
mismatch, but a **missing** record field deserializes to `null` (or `0` for a primitive) and reaches
the controller without any exception. The required distinct codes therefore come from two places:

| condition | boundary | code |
|---|---|---|
| malformed JSON, non-integer version, quantity as JSON number | `HttpMessageNotReadableException` handler | `malformed_request`, `invalid_version`, `quantity_not_string` |
| absent `expectedVersion` field | `@Valid` + `@NotNull` on the request DTO, via `MethodArgumentNotValidException` | `missing_version` |

The request DTO declares `expectedVersion` as a **boxed** `Long` with `@NotNull`. A primitive `long`
would silently default a missing field to `0`, which is a meaningful value in this contract — it
denotes Absent_Aggregate — so a missing version would be indistinguishable from a deliberate
creation attempt. That is the specific reason the boxing is required rather than stylistic.

**Boxing detects absence but does not make decoding strict.** This repository resolves
`tools.jackson.core:jackson-databind:3.1.4`, whose default `CoercionConfigs` action is `TryConvert`
with scalar coercion and `ACCEPT_FLOAT_AS_INT` enabled — so `7.9`, `"7"`, and `true` can all become a
`Long` instead of reaching the malformed-request handler. `expectedVersion` therefore uses a
**property-scoped strict deserializer accepting only an integer token**, or an equivalently scoped
coercion rule. Its non-negative domain is validated at the same boundary, before any stateful work.

Version-token handling is proved separately for float, string, boolean, negative, missing, and
malformed inputs. Treating "invalid version" as one example is what allowed the coercion gap to
survive Revision 2.

Both boundaries fire before any controller body executes, so "envelope before stateful" remains a
property of framework dispatch rather than of our evaluation order.

#### D8 — The seed target stays fixed; a version arrives via a four-step compatibility bridge

`PortfolioSeedService.seed()` loses its `deleteAll` opening entirely and delegates to
`CompositionService.replaceHoldings` with the catalog-derived Golden-State tuple.

**The target remains server-fixed.** `PortfolioSeedController` hard-codes `E2E_USER_ID` and takes no
parameters. Revision 1 proposed a `{userId, expectedVersion}` body, which would have widened a
destructive, production-reachable, daily-invoked endpoint to arbitrary users — with no requirement
asking for it. The body carries `expectedVersion` only; the target stays the compiled-in E2E id.
(The workflow already sends `{"userId": …}` today and the controller ignores it; that field stays
ignored rather than becoming meaningful.)

**The version is read from the portfolio itself, not from a version endpoint.** Revision 2 proposed
an internal `GET /api/internal/portfolio/seed-state`; that is a separate Portfolio_Version endpoint,
which the frozen requirements prohibit outright — the prohibition exists because a read-then-read
sequence reintroduces the race the version closes, and scoping the endpoint to the E2E user does not
change what it is.

The workflow authenticates instead. The same Azure job already holds `E2E_TEST_USER_EMAIL` and
`E2E_TEST_USER_PASSWORD`, and the repository already contains the login-then-read pattern. The seed
step logs in, reads the fixed user's complete `PortfolioResponse` **once**, and carries that
response's version into the internal `POST`. The version travels with the state the caller observed,
which is exactly what the requirement asks for. The destructive target remains server-fixed.

**The sequence spans the migration, because the version does not exist before it:**

1. **After V17**, deploy a compatibility artifact exposing the version on the existing authenticated
   read, while the old seed `POST` still tolerates the extra body field.
2. Change the workflow to log in, read once, and send that exact version. Safe in either deployment
   order relative to step 1's rollout, because the old `POST` ignores the field.
3. Verify one successful scheduled or manual execution using the new request shape.
4. Only then deploy the `POST` that **requires** the version and delegates to the versioned reset.

Revision 2 allocated no artifact for step 1 and placed the version-bearing read before the migration
that creates the column. Step 4 before step 2 breaks the daily seed on its first run.

**`409` fails the monitoring execution once, with the body logged, and is never retried.** Chosen
rather than deferred. A `409` is correct data-plane behaviour and evidence the contract worked — a
user edit won — but the dedicated E2E portfolio was not seeded, which is a real monitoring signal and
should not be swallowed. Retrying is prohibited: retrying against the newer version is precisely the
silent overwrite the contract exists to prevent.

#### D9 — Cutover is staged gateway-first, with quiescence as a proven fallback

Selected per the requirements' permission and Codex's recommendation. Three artifacts, in order:

**Artifact 0 — legacy writer retirement, migration-free.** Retires **both** legacy writers —
`POST /api/portfolio` and the versionless `POST /api/portfolio/{portfolioId}/holdings` — and migrates
their E2E callers. Contains **no migration**, so it deploys and verifies before any schema change
exists.

Revision 2 retired only the creator here and left the versionless holdings writer until Artifact 3,
which made the rollback floor unexecutable: rolling Artifact 3 back to Artifact 2 restored a writer
that bypasses Portfolio_Version entirely. Both retirements now land before V17, so **every artifact
at or above the floor is free of both legacy writers**.

If the holdings `POST` must remain reachable for any interval, it carries Quantity_Domain validation
for that whole interval — the requirements demand it for as long as the path exists, and an interval
where it is reachable but unvalidated would be a regression introduced by this cutover.

**Artifact 1 — provisioning-capable gateway.** `SignupService` gains a `portfolios` insert inside
its existing `TransactionTemplate`. The insert must work against **both** schemas, which it does
because it names only columns present before and after: `INSERT INTO portfolios (id, user_id)
VALUES (...)`. Both timestamps and the new `version` column come from database defaults in that one
statement — `created_at` is omitted too, so the two timestamps are equal by construction rather than
by two separately-supplied values. Revision 2 supplied `created_at` explicitly while letting
`updated_at` default, which would have contradicted the equal-timestamps semantics it asserted.
Deployed and verified on every traffic-serving revision before artifact 2 starts.

**Artifact 2 — migration.** Backfill, unique constraint, quantity check, version and `updated_at`
columns. May not start until Artifact 0 and Artifact 1 are both verified on every traffic-serving
revision.

**Artifact 2a — version-bearing read.** Post-migration compatibility artifact exposing
Portfolio_Version on the existing authenticated `GET /api/portfolio`, while the old seed `POST` still
tolerates the extra body field. This is D8 step 1, and it exists because the version column does not
exist before V17 — Revision 2 allocated no artifact for it.

**Artifact 3 — endpoints.** Composition `PUT`, `/api/assets`, and the version-**required** seed
`POST` (D8 step 4), after the workflow migration in D8 steps 2 and 3 is verified against Artifact 2a.

The dual-schema property is the whole basis for preferring this path, so it is a **proof
obligation**, not an assumption: an integration test runs the gateway's provisioning insert against
a pre-migration schema and a post-migration schema in the same suite. If that test cannot be made to
pass, the fallback is signup quiescence — make the signup route unreachable, run artifact 2, deploy
artifact 1, verify, reopen. Login stays available throughout either path.

#### D10 — `/api/assets` is served by portfolio-service and cached by ETag alone

The catalog is already in memory there via the Catalog_Module, so discovery needs no cross-service
call. A new gateway route `Path=/api/assets/**` joins the existing table. The response carries
`ETag: "<catalogVersion>"` and `Cache-Control: private, no-cache`, so a client revalidates every
time and normally receives `304` with no body. No second client-side persistent cache is introduced:
catalog changes are deployment events, and an application-level cache would add invalidation
machinery for no benefit.

## Architecture

### Write path

```
PUT /api/portfolio/holdings
        │
        ├── Jackson decode ─── failure ──▶ 400 envelope_* (before controller)
        │
        ▼
CompositionController                     resolves userId from principal; no portfolio id on the wire
        │
        ▼
CompositionService.replaceHoldings(userId, expectedVersion, desiredSet)
        │
        ├─ 1. locate Primary_Portfolio
        │      ├─ absent  ──▶ expectedVersion != 0 ──▶ 409 (virtual current 0)
        │      │             └─ == 0 ──▶ validate ──▶ insert ──▶ uq violation ──▶ 409
        │      └─ present ──▶ ordinary optimistic load
        │                          └─ version != expectedVersion ──▶ 409 portfolio_version_conflict
        │
        ├─ 2. validate whole desired set ──▶ 422 unsupported_asset / lifecycle_not_permitted
        │                                    400 quantity_out_of_domain / duplicate_ticker
        │
        ├─ 3. compare to stored state ──▶ equal ──▶ 200, no update at all
        │
        └─ 4. single parent CAS (UPDATE … WHERE id=? AND version=?, 1 row) ──▶ then child DML
                                                                     ──▶ 200 (or 201 on creation)
```

The same `CompositionService` call sits under the reset and the seeder. Only the source of
`desiredSet` and `expectedVersion` differs.

### Provisioning paths

Three paths create a portfolio, and the design closes all of them against the unique constraint:

| path | when | version after |
|---|---|---|
| `SignupService` | every new user, in the signup transaction | `0` |
| `Portfolio_Backfill` migration | existing users with none | `0` |
| `CompositionService` creation | recovery fallback only | `1` |

The asymmetry is deliberate and required: the first two create an empty aggregate no client has
acted on, while the third records a client's first desired state, which is a transition.

## Components and Interfaces

### 1. `Portfolio` — entity changes

```java
@Version
@Column(nullable = false)
private long version;

@Column(nullable = false)
private Instant updatedAt;
```

`updatedAt` is maintained by the service, not by `@PreUpdate`. A JPA lifecycle callback would fire
on any dirty parent, including the force-increment of a no-op path if that path ever changed, which
would decouple `updated_at` from "a transition happened". The requirements tie the two together, so
one place sets both.

The existing prohibition on a `@ManyToOne` association to `com.wealth.user.User` is preserved; this
design adds no cross-aggregate association.

### 2. `CompositionService` — the only holdings writer

```java
@Transactional
public CompositionResult replaceHoldings(String userId, long expectedVersion, List<DesiredHolding> desired);
```

Returns `CompositionResult(PortfolioResponse response, boolean created, boolean noOp)` so callers can
map `201` versus `200` without re-inspecting state.

Cost-basis rules are applied per ticker during step 4: retained tickers keep their existing tuple
untouched even when quantity changes; new tickers capture basis under the existing add-time rule;
removed tickers lose holding and basis. No weighted-average recomputation occurs anywhere — this is
a snapshot editor, and inferring a purchase price from a quantity edit would invent a transaction the
user never supplied.

### 3. `GoldenStateSeedService` — replaces the delete-and-recreate body

`PortfolioSeedServiceIT` declares `EXPECTED_HOLDINGS = 160` and asserts it twice. When that test is
rewritten for identity preservation, the literal is replaced with **active-catalog cardinality**
read from the Catalog_Module. Spec A removed fixed catalog counts as an invariant deliberately;
carrying a literal `160` into a new test would reintroduce the defect it removed.


Builds the desired set from the Catalog_Module's active entries with the existing deterministic
quantity and cost-basis functions, then calls `replaceHoldings`. The `deleteAll` + `flush` opening is
removed outright.

`computeDeterministicCostBasis` and the quantity derivation are unchanged, so seeded values stay
reproducible. `costBasisAsOf` remains a moving 25-hour anchor — but the no-op comparison deliberately
does **not** depend on that: it compares the complete persisted tuple, so if the anchor were ever
pinned, the concurrency contract would not silently change meaning.

### 4. `StrictDecimalStringDeserializer` / `ToPlainStringSerializer`

The deserializer accepts only `JsonToken.VALUE_STRING`, parses with `new BigDecimal(String)`, and
rejects exponential notation by pattern before parsing. The serializer emits `toPlainString()`,
preserving trailing fractional zeros as stored so a round trip is byte-stable.

### 5. `GlobalExceptionHandler` — typed additions

New handlers for `PortfolioVersionConflictException`, the **plural** `UnsupportedAssetsException`
(B1's aggregate form), `LifecycleNotPermittedException`, `QuantityOutOfDomainException`, and
`DuplicateTickerException`.

Two envelope-boundary handlers, not one: the `HttpMessageNotReadableException` override for
malformed JSON and rejected tokens, **and** a `MethodArgumentNotValidException` handler for the
`@NotNull` absence of `expectedVersion`. Revision 2 relied on the second in D7 and omitted it here.

Spec A's singular `UnsupportedAssetException` and its handler are left untouched on their existing
single-write path, emitting Spec A's exact body.

### 6. `SignupService` — provisioning insert

One statement added inside the existing `transactionTemplate.execute` block, after
`insertCredential`. If it throws, the existing rollback path applies and signup fails rather than
producing a user without a portfolio.

### 7. Gateway route

```yaml
- id: asset-catalog
  uri: ${app.routes.portfolio-url}
  predicates:
    - Path=/api/assets/**
```

### 8. `ReadOnlyEnforcementFilter` — noted, not changed

The demo account must eventually reach the composition `PUT`, and the filter's `aiAllowlistPatterns`
is matched by `AntPathMatcher` against the **path only**, with no method component. Adding the
holdings path there would silently permit every mutating method on it, including ones added later.
That change belongs to B2; it is named here so the constraint is not rediscovered.

## Data Models

### `portfolios` after V17

| column | type | note |
|---|---|---|
| `id` | `UUID` | unchanged |
| `user_id` | `VARCHAR(255)` | unchanged; gains `uq_portfolios_user_id`. **Not a UUID column** — see below |
| `created_at` | `TIMESTAMP` | unchanged |
| `version` | `BIGINT NOT NULL DEFAULT 0` | new |
| `updated_at` | `TIMESTAMP NOT NULL DEFAULT now()` | new |

### Migration V17 — ordering within the file matters

1. `ALTER TABLE portfolios ADD COLUMN version BIGINT NOT NULL DEFAULT 0`
2. `ALTER TABLE portfolios ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT now()`
3. Backfill:
   ```sql
   INSERT INTO portfolios (id, user_id, created_at)
   SELECT gen_random_uuid(), u.id::text, now()
   FROM users u
   WHERE NOT EXISTS (SELECT 1 FROM portfolios p WHERE p.user_id = u.id::text);
   ```
4. `ALTER TABLE portfolios ADD CONSTRAINT uq_portfolios_user_id UNIQUE (user_id)` — a named
   **table constraint**, not a bare unique index, because the exception translator matches on
   constraint name and a plain index does not reliably surface one.
5. `ALTER TABLE asset_holdings ALTER COLUMN quantity DROP DEFAULT`
6. `ALTER TABLE asset_holdings ADD CONSTRAINT chk_asset_holdings_quantity_positive CHECK (quantity > 0)`

Step 3 before step 4 is required: creating the unique index first would succeed against current data
but the backfill is what guarantees no user is left at zero, and the ordering makes the migration
self-consistent if it is ever run against a database with duplicates. Step 6 will fail the migration
rather than clamp if a violating row exists — the production preflight found none across 163
holdings, but the preflight is a point-in-time observation and the migration runs later.

The backfill is idempotent under Flyway re-execution by the `NOT EXISTS` guard.

**The `::text` casts are load-bearing.** `users.id` is `UUID` while `portfolios.user_id` is
`VARCHAR(255)` — V1's original type, which no later migration changes. `V7__Fix_Portfolio_User_Id_To_UUID.sql`
updates a *value* (`'user-001'` to a UUID string) despite its filename; it does not alter the column
type. Without the casts both the `INSERT` and the `NOT EXISTS` correlation fail on type mismatch, and
the `NOT EXISTS` failure is the dangerous one: an implementation that silently compared incompatible
types would treat every user as unprovisioned and insert duplicates on re-run, defeating the
idempotency this step claims.

The mismatch is deferred, not disowned — see O3. Converting the column is a **type conversion**, not
a widening, and the reason to defer is unrelated migration risk on a table whose constraint and
backfill already gate a production cutover. It is **not** that the affected code is out of scope:
this spec directly owns `Portfolio`, its repositories, and the seeder, and already modifies all
three.

## Correctness Properties

Each is stated as a property a test must demonstrate, not as a description of the implementation.

**P1 — The four-case matrix is total, on both writers.** For composition and for reset, each
combination of {version matches, version mismatches} × {desired equals stored, desired differs} has
exactly one outcome, and no combination is untested.

**P2 — A child-only change advances the parent version exactly once.** Adding, changing, or removing
a holding without touching any `portfolios` column moves `version` by exactly one.

**P3 — Concurrent composition: exactly one commits.** Two transactions loading the same expected
version and writing different desired states produce one success and one `409`, never two successes.

**P4 — Concurrent creation: exactly one aggregate exists.** Two requests with expected version `0`
against an absent aggregate produce one `201` at version `1` and one `409`, and exactly one row in
`portfolios`. This holds when both desired sets are empty, which is the case a pre-write version
comparison cannot distinguish.

**P5 — A stale request with a coincidentally-equal body is a conflict.** Eligibility freezes `N`; a
user commits `N+1` whose tuple equals the Golden-State tuple; the reset arrives with expected `N` and
receives `409`, not `200`.

**P6 — A lost reset does not retry.** After a `409`, the reset performs no second attempt against the
newer version.

**P7 — Round-trip fidelity.** `0.75000000` read from the API, submitted unchanged, is stored
byte-identical.

**P8 — Envelope failures precede stateful ones.** A body with a JSON-number quantity **and** a stale
version returns the envelope `400`, not `409`.

**P9 — Constraint translation is narrow.** A `CHECK` violation on quantity surfaces as its own `400`
code, never as `409`.

**P10 — Spec A's price-write boundary is intact.** `PortfolioSeedServiceIT` still asserts full-table
byte-identity of `market_prices` and `market_price_history` across repeated seeds, sentinel rows
included. This is the PR #97 regression guard and it survives the seeder rewrite unchanged in intent.

**P11a — Creation binds both timestamps.** A portfolio created by any of the three paths inserts
with non-null `createdAt` and `updatedAt`; no path relies on a database default to replace an
explicitly bound null.

**P11f — No-op equality is decided on the persisted representation.** A desired quantity of `0.75`
against a stored `0.75000000` is **equal**. Comparison canonicalises to the storage scale
`NUMERIC(19,8)` before comparing; `BigDecimal.equals` is scale-sensitive and would report these
unequal, advancing the version and then persisting an unchanged child value — a spurious transition
that also breaks the reset's eligibility semantics. Quantity-domain validation already bounds scale
to 8, so canonicalisation is total.

**P11g — Rollback from any release at or above the floor restores no legacy writer.** After R-B, for
every artifact reachable by rollback, neither `POST /api/portfolio` nor the versionless
`POST /api/portfolio/{portfolioId}/holdings` is reachable.

**P11h — Version tokens are decoded strictly.** A float (`7.9`), a string (`"7"`), a boolean, and a
negative version each produce their own stable code rather than coercing to a `Long`, verified
against the resolved Jackson runtime rather than assumed from defaults.

**P11i — Aggregate rejection reports every offender deterministically.** A composition request with
three unsupported tickers returns all three in `tickers`, in request order, with `ticker` carrying
the first — and Spec A's single-write path still returns its exact singular body.

**P11b — The no-op path performs no write and no increment.** A matching-version request whose
desired state equals stored state leaves `version` and `updated_at` byte-identical, and the
**response** version equals the stored version — asserted on the DTO, not only on the row, since an
in-memory over-increment survives a database rollback.

**P11c — Every envelope-failure code is reachable and distinct.** Malformed JSON, a non-integer
version, an absent `expectedVersion`, and a quantity sent as a JSON number each produce their own
stable code, and each precedes any stateful check.

**P11d — Artifact 0 closes the creator before the constraint exists.** No traffic-serving revision
exposes `POST /api/portfolio` at the moment V17 runs.

**P11e — The seed bridge is order-safe.** The old seed `POST` accepts the new request shape
(ignoring the version) and the new `POST` rejects a request without one, so steps 2 and 4 of D8 are
safe in either deployment order relative to each other.

**P11 — Every user has exactly one portfolio.** After cutover, `SELECT user_id FROM portfolios GROUP
BY user_id HAVING COUNT(*) <> 1` is empty, and no user in `users` is absent from `portfolios`.

## Sequencing and Cutover

### Gates

**G0 — creator retired.** No traffic-serving portfolio-service revision exposes `POST
/api/portfolio`, and its E2E caller is migrated. Verified by revision listing plus traffic weights.

**G1 — dual-schema proof.** The gateway provisioning insert passes against both pre- and
post-migration schemas. If this fails, switch to the quiescence path before proceeding.

**G2 — gateway reachability.** Every traffic-serving gateway revision provisions. Verified by
revision listing plus traffic weights, not by deployment ordering — `deploy-azure.yml` runs
`api-gateway` and `portfolio-service` as parallel matrix entries, so ordering within a release
guarantees nothing.

**G3 — relational postcondition.** `P11` holds in production, checked after G2, not at migration
commit time.

**G4 — Spec A steady state.** Composition endpoints do not become user-reachable until Spec A's
enforcement activation is complete and verified. `/api/assets` may deploy dark before this, being
read-only.

### Releases

| release | artifact | gates |
|---|---|---|
| **R-0** | 0 — both legacy writers retired, migration-free | G0 after |
| **R-A** | 1 — provisioning-capable gateway | G1 before deploy, G2 after |
| **R-B** | 2 — V17 migration | G0 and G2 before; G3 after |
| **R-B2** | 2a — version-bearing authenticated read | G3 before; workflow migrated and verified after |
| **R-C** | 3 — composition `PUT`, `/api/assets`, version-required seed | G4 **before deploy** |

Five releases, not the "three artifacts" Revision 2's prose claimed while listing four. No release
exists in which a duplicate-creating path is reachable while the unique constraint is present,
because R-0 completes and is verified before R-B starts.

**G4 is a pre-deploy gate, not a post-deploy one.** The existing `Path=/api/portfolio/**` gateway
route means the composition `PUT` is reachable the moment its controller revision receives traffic —
there is no dark-deploy switch in this design, and adding one would be more machinery than
sequencing the release correctly. `/api/assets` is exempt: it is read-only and side-effect free, so
it may ship earlier.

### Rollback

Revision 1's rollback plan violated the invariant in both directions and is replaced.

**The rollback floor is Artifact 0 + Artifact 1 together**, and it is executable precisely because
Artifact 0 retires *both* legacy writers. Once R-B has run, no rollback may cross below that floor.

Because every artifact at or above the floor already lacks both legacy writers, rolling R-C back to
R-B2 — or R-B2 back to R-B — cannot restore a versionless writer. That is what makes the floor a
real artifact boundary rather than a statement of intent; Revision 2 named a "B1-compatible artifact
with new capabilities disabled" that did not exist in its own release list. Two specific errors this prevents:

- **Reverting the gateway after the backfill** stops signup provisioning, so new users are created
  with no portfolio. The composition fallback creates one on first write, but the invariant is
  "every user has exactly one portfolio", not "every user who writes gets one" — a signed-up user
  who never opens the picker would sit outside it indefinitely.
- **Reverting to a pre-B1 artifact after activation** restores both forbidden writers — the
  duplicate creator and the versionless holdings `POST` — underneath a live unique constraint and a
  live concurrency contract.

Within the floor: R-C is reverted by redeploying a **B1-compatible** artifact with the new
capabilities disabled, not by redeploying a pre-B1 one. R-B is a Flyway migration and is corrected
forward, never reverted. If gateway provisioning itself must be rolled back, signup is quiesced
until a forward fix is serving traffic — the same mechanism the cutover already permits as its
fallback, reused rather than invented.

## Open Decisions

**O1 — CLOSED on the authenticated read.** Revision 2 closed this on a narrow internal
`seed-state` endpoint, reasoning that the workflow carries only `X-Internal-Api-Key`. That
observation was right and the conclusion was wrong twice over: a separate Portfolio_Version endpoint
is prohibited by a frozen requirement, and the workflow does not in fact need one — the same Azure
job already holds `E2E_TEST_USER_EMAIL` and `E2E_TEST_USER_PASSWORD`. The seed step logs in and reads
the fixed user's complete `PortfolioResponse` once, carrying that response's version. See D8.

**O2 — CLOSED as internal-only.** `CompositionResult.noOp` is useful for controller-to-service
mapping but adds no wire field. An unchanged version already expresses the public result.

**O3 — `portfolios.user_id` is `VARCHAR(255)`, not `UUID`; deferred, with the rationale corrected.**
`users.id` is `UUID`, and the mismatch is bridged with `::text` casts here.

Revision 1 justified deferring this on the grounds that the affected code is out of scope. **That
was wrong** — B1 directly owns `Portfolio`, its repositories, and the seeder, all of which it
already modifies. The correct reason to defer is that converting a live identifier column is
migration risk unrelated to anything B1 needs: it rewrites every row of a table whose constraint and
backfill are already gating a production cutover, for no benefit to this spec. It is also a type
*conversion*, not a "widening". Worth its own change, on its own risk budget.

`V7__Fix_Portfolio_User_Id_To_UUID.sql` does not do this despite its filename: it updates a value.

**O4 — CLOSED with `now()`.** The portfolio is genuinely created by the backfill at migration time.
Reusing the user's `created_at` would claim the aggregate existed when it did not.
