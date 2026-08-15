# Design Document

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
explicit parent-version mechanism for this reason, and this design implements it as
`OPTIMISTIC_FORCE_INCREMENT` acquired before any child write.

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

#### D1 — The version is a JPA `@Version` column, but every write acquires it explicitly

`Portfolio` gains `@Version private long version`. No composition or reset code path relies on JPA
noticing a child change. Each mutating operation begins by loading the parent with
`LockModeType.OPTIMISTIC_FORCE_INCREMENT`, which both asserts the expected version and guarantees
exactly one increment at flush, regardless of whether the parent's own columns changed.

This satisfies the "child-only mutation still increments" rule structurally rather than by
convention. It also gives the no-op rule a clean implementation: the operation acquires the lock,
compares desired state to stored state, and if they are equal it marks the transaction rollback-only
and returns the unchanged aggregate — so no increment is written, while the precondition has already
been enforced by the lock acquisition.

#### D2 — Version comparison happens at lock acquisition, before anything else touches state

The ordering the requirements mandate — precondition, then no-op detection, then destructive work —
is implemented as a single sequence in the application operation:

1. Envelope decoded by Jackson; a failure here never reaches the operation.
2. Load parent with `OPTIMISTIC_FORCE_INCREMENT` at the expected version. Mismatch throws.
3. Validate the complete desired set: catalog resolution, lifecycle permission, quantity domain,
   uniqueness. Any failure throws before a row is touched.
4. Compare desired state to stored state. Equal → no-op return.
5. Apply.

Steps 2 and 3 are deliberately in this order. A stale request with an invalid body returns the
conflict, not the validation error, because the requirements make the version precondition
authoritative within stateful validation.

#### D3 — One `CompositionService`, three entry points, no duplicated rules

`CompositionService.replaceHoldings(userId, expectedVersion, desiredSet)` is the only code that
mutates holdings. The composition endpoint, the identity-preserving reset, and the Golden-State
seeder all call it. They differ only in how they obtain `desiredSet` — client body, catalog-derived
Golden-State tuple — and in who supplies `expectedVersion`.

This is what makes writer convergence structural. There is no second replacement implementation to
keep in step, so the four-case matrix is proved once against the service and then exercised through
each entry point rather than reimplemented per caller.

#### D4 — Absent-aggregate creation is a distinct path that converges immediately

When no portfolio exists, `replaceHoldings` cannot acquire a lock. The path is:

```
try {
    portfolio = new Portfolio(userId);      // version 0 at construction
    portfolioRepository.saveAndFlush(portfolio);   // may violate uq_portfolios_user_id
} catch (DataIntegrityViolationException e) {
    if (!isNamedConstraint(e, "uq_portfolios_user_id")) throw e;
    throw new PortfolioVersionConflictException(/* current version re-read by caller */);
}
applyDesiredState(portfolio, desiredSet);   // force-increment → version 1
```

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

```json
{ "code": "portfolio_version_conflict", "message": "...", "currentVersion": 7 }
{ "code": "unsupported_assets", "message": "...", "catalogVersion": "…", "tickers": ["FOO"] }
```

A `ContractErrorCode` enum owns every stable identifier. `GlobalExceptionHandler` gains typed
handlers that all produce this shape. The existing ad-hoc `Map` responses for unrelated errors are
left alone — rewriting them is out of scope and would touch endpoints this spec does not own.

Envelope failures are handled by overriding `ResponseEntityExceptionHandler`'s
`HttpMessageNotReadableException` hook, which fires before any controller method is entered. That is
what makes "envelope before stateful" implementable rather than aspirational: at that point there is
no decoded version to compare, so the ordering is a property of the framework's dispatch, not of our
evaluation sequence.

#### D8 — The seed endpoint takes a version; the daily caller reads one observation

`POST /api/internal/portfolio/seed` becomes:

```json
{ "userId": "…", "expectedVersion": 7 }
```

`PortfolioSeedService.seed()` loses its `deleteAll` opening entirely and delegates to
`CompositionService.replaceHoldings` with the catalog-derived Golden-State tuple.

The existing daily caller — the `synthetic-monitoring.yml` step — must read the portfolio once,
carry that exact version into the seed call, and treat `409` as a lost race that is **not** retried.
This is the B1 half of the reset contract: B2 will eventually own the eligibility decision, but the
already-running caller has to supply a version before B2 exists, so the migration cannot wait.

#### D9 — Cutover is staged gateway-first, with quiescence as a proven fallback

Selected per the requirements' permission and Codex's recommendation. Three artifacts, in order:

**Artifact 1 — provisioning-capable gateway.** `SignupService` gains a `portfolios` insert inside
its existing `TransactionTemplate`. The insert must work against **both** schemas, which it does
because it names only columns present before and after: `INSERT INTO portfolios (id, user_id,
created_at) VALUES (...)`. The new `version` and `updated_at` columns take their defaults.
Deployed and verified on every traffic-serving revision before artifact 2 starts.

**Artifact 2 — migration.** Backfill, unique constraint, quantity check, version and `updated_at`
columns.

**Artifact 3 — endpoints.** Composition `PUT`, `/api/assets`, retirement of `POST /api/portfolio`
and the versionless holdings `POST`, seed signature change.

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
        │      ├─ absent  ──▶ create + flush ──▶ uq violation ──▶ 409 (re-read outside txn)
        │      └─ present ──▶ find(OPTIMISTIC_FORCE_INCREMENT, expectedVersion)
        │                          └─ mismatch ──▶ 409 portfolio_version_conflict
        │
        ├─ 2. validate whole desired set ──▶ 422 unsupported_assets / lifecycle_not_permitted
        │                                    400 quantity_out_of_domain / duplicate_ticker
        │
        ├─ 3. compare to stored state ──▶ equal ──▶ 200, no increment, no updated_at advance
        │
        └─ 4. apply ──▶ version + 1, updated_at = now(), 200 (or 201 on creation)
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

New handlers for `PortfolioVersionConflictException`, `UnsupportedAssetException` (raised by Spec A's
validator), `LifecycleNotPermittedException`, `QuantityOutOfDomainException`, and
`DuplicateTickerException`, plus the `HttpMessageNotReadableException` override for envelope
failures. All emit the `ContractError` shape.

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
4. `CREATE UNIQUE INDEX uq_portfolios_user_id ON portfolios (user_id)`
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

The mismatch is left in place rather than corrected here. Widening `portfolios.user_id` to `UUID`
would touch `Portfolio.userId` (a `String`), every repository method keyed on it, and the seeder —
none of which this spec owns, and all of which would enlarge a migration that already gates a
production cutover. It is recorded as a defect for its own change.

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

**P11 — Every user has exactly one portfolio.** After cutover, `SELECT user_id FROM portfolios GROUP
BY user_id HAVING COUNT(*) <> 1` is empty, and no user in `users` is absent from `portfolios`.

## Sequencing and Cutover

### Gates

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

**R-A — gateway provisioning.** Artifact 1. Gate: G1 before deploy, G2 after.
**R-B — migration.** Artifact 2. Gate: G3 after.
**R-C — endpoints.** Artifact 3. Gate: G4 before making composition reachable.

Retirement of `POST /api/portfolio` and the versionless holdings `POST` lands in R-C together with
their E2E consumer migration, so no release exists in which a duplicate-creating path is reachable
while the unique constraint is present.

### Rollback

R-A is independently revertible: the provisioning insert is additive and a reverted gateway simply
stops provisioning, which the composition fallback covers. R-B is a Flyway migration and is not
reverted; a defect there is corrected forward. R-C is revertible by redeploying the prior artifact,
which restores the old endpoints — acceptable only before G4, because after activation the picker
depends on them.

## Open Decisions

**O1 — Where the reset's eligibility observation is read in B1.** The daily caller must read the
portfolio version before calling seed. Whether that read is a new lightweight endpoint or a reuse of
`GET /api/portfolio` is unresolved; B2 will replace this caller anyway, so the cheaper option is
probably right, but it should not be chosen without checking what the workflow step can call.

**O2 — Whether `CompositionResult.noOp` is exposed to clients.** The requirements demand `200` with
an unchanged version for a no-op, which a client can already detect by comparing versions. An
explicit flag may be redundant.

**O3 — `portfolios.user_id` is `VARCHAR(255)`, not `UUID`.** Found while verifying this design's
backfill SQL. `users.id` is `UUID`; the mismatch is bridged with `::text` casts here rather than
corrected, because widening the column touches `Portfolio.userId`, every repository method keyed on
it, and the seeder — none of which this spec owns, and all of which would enlarge a migration that
gates a production cutover. Worth its own change. Note that `V7__Fix_Portfolio_User_Id_To_UUID.sql`
does **not** do this despite its filename: it updates a value, not a type.

**O4 — Backfill portfolio `created_at`.** The migration sets `now()`, which claims these portfolios
were created at migration time rather than at user signup. Using the user's `created_at` would be
more truthful but implies a portfolio existed when it did not. Neither is clearly right; `now()` is
chosen provisionally.
