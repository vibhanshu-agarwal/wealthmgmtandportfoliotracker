# Requirements Document

> **Revision 2 — 2026-08-15.** Incorporates the adversarial review in checkpoint entry [9], which
> did not clear the gate. Five blocking findings, all accepted after independent verification:
>
> 1. **The central contradiction.** R1.1 asserted every user has *exactly one* portfolio while
>    R1.5 provisioned lazily on first write, leaving every signup with none until then. D2's
>    supporting premise was also false: `api-gateway` **already** writes the `users` table owned by
>    `portfolio-service` (`UserCredentialRepository.java:49`, whose own javadoc says so), so the
>    schema-ownership boundary D2 leaned on does not exist as stated. Signup provisioning is
>    therefore cheap and consistent with established practice, and it makes the invariant true at
>    all times. Requirement 1 and D2 are rewritten; first-write provisioning survives only as a
>    recovery fallback. This reverses D2 a second time — see D17 for why the reasoning, not just
>    the conclusion, was wrong.
> 2. **A second creation path survives.** `POST /api/portfolio` still calls
>    `PortfolioService.createPortfolio` unconditionally, and the E2E helper calls it whenever the
>    list is empty. Requirement 8 retired only the versionless holdings endpoint, so the unique
>    constraint in 1.8 would have produced duplicates before cutover or a raw database error after.
> 3. **Creation had no version contract.** Requirement 6 requires an expected Portfolio_Version
>    while Requirement 1 permitted the portfolio not to exist. Requirement 6 now defines the
>    initial version, the first-request contract, the competing-creator outcome, and empty-set
>    behaviour.
> 4. **The lifecycle rejection was unreachable.** 6.10 rejects introducing or increasing a
>    Deprecated_Asset, but Requirement 7 defined `422` only for tickers absent from the catalog.
>    Requirement 7 now covers lifecycle rejection and states aggregate precedence.
> 5. **Requirement 9 named the wrong milestone.** "Spec A's Requirement 4" was a misreading:
>    Requirement 4 concerns symbol corrections, whereas **R4 is a release artifact** in Spec A's
>    cutover sequence (`design.md:605`). Corrected to the named enforcement activation.
>
> Both P2 findings accepted: 5.4 now mandates an explicit parent-version mechanism instead of
> permitting reliance on JPA's inverse collection (Q17), and Requirement 6 requires whole-set
> validation before any destructive work. One citation corrected — the frontend helper is
> `loadBackendPortfolio`, not `getPrimaryPortfolio`.
>
> **Revision 1 — 2026-08-15.** First draft of Spec B1, opened from `docs/superpowers/CHECKPOINT.md`
> entries [4] through [7]. Carries the settled decisions from that brainstorm and resolves Q11 and
> Q12, both of which were open for Codex when drafting began. One factual correction to entry [6]
> is recorded in D12: `portfolios.updated_at` does not exist.

## Introduction

Spec A (`supported-asset-integrity`) closed the ticker half of the holdings write boundary. It
established the Supported_Catalog as the authority for what may be held, and required every write
path to validate against it. It deliberately delivered no user-facing way to compose a portfolio,
and named that work as this spec's.

This spec is the **contract half** of the Asset Picker: asset discovery, the quantity invariant,
portfolio identity and versioning, the desired-state composition write, and the convergence of every
holdings writer onto one versioned operation. It delivers no UI. The picker modal, draft and conflict
UX, price loading for the selected set, freshness presentation, the demo reset trigger, and the
presence banner are specified separately in `asset-picker-composition` (B2), which depends on this
one.

The split follows checkpoint entry [5]. Spec A took thirteen requirements revisions and nine design
revisions, and roughly half of each round's findings were defects introduced by the previous round's
fix — a direct consequence of mixing persistence, API, and frontend state in one review surface. B1
is independently contract-testable; B2 traces to it rather than restating it.

**The write boundary this spec builds on is validated for tickers and unvalidated for everything
else.** `AddHoldingRequest` carries no bean validation, there is no `@Valid` on the write path, and
`asset_holdings.quantity` is `NUMERIC(19, 8) NOT NULL DEFAULT 0` (`V1__Initial_Schema.sql:24`). Zero,
negative, and nineteen-digit quantities are all accepted today. This matters more than it appears:
Spec A's holding-collision rule had to define behaviour for `q1 + q2 <= 0` precisely *because the API
permits it*. The picker is the first UI that will submit quantities at volume, so it cannot ship a
"required positive quantity" experience on top of an endpoint that accepts `-5`.

**A production preflight, run read-only against Neon on 2026-08-15, decided three open questions.**
Across 163 holdings there are **zero** zero-quantity and **zero** negative-quantity rows, so
`CHECK (quantity > 0)` is a plain constraint rather than an audited repair — none of the clamping
ambiguity that checkpoint entry [5] rightly reserved for is needed. No user holds more than one
portfolio. Maximum quantity in use is `50` against an eleven-integer-digit bound, so the proposed
domain is comfortably above real usage. And scale 8 is genuinely in use (`0.75000000`), which makes
the precision hazard in D5 concrete rather than theoretical.

**The preflight also found the defect that shapes Requirement 1.** There are seven users and two
portfolios: only the demo and E2E accounts have one. The dev user lost its portfolio when `V15`
reassigned it to demo, and **every self-signup since has had none**. Checkpoint entry [6] argued from
code that a new user cannot use the picker because nothing provisions a portfolio; the data shows
this is not hypothetical but already true of five of seven users. `SignupService` creates a `users`
row and a `user_credentials` row and nothing else; `createPortfolio` is reachable only from
`POST /api/portfolio`, which no production frontend code calls; and `loadBackendPortfolio`
(`frontend/src/lib/api/portfolio.ts:76`) returns the first element of the returned list with no
ordering. So the picker has nothing to write to for most of the user base, and no production path
would create it.

That last endpoint is not merely unused, and Requirement 8 must account for it: the E2E helper calls
`POST /api/portfolio` whenever the portfolio list comes back empty. It is a **second creation path**,
and it would defeat the exactly-one invariant — producing duplicates before the unique constraint
lands and a raw database error afterwards.

**Optimistic concurrency is a product requirement, not an engineering preference.** The owner's
requirement is that a second session editing the same demo account sees a message preventing silent
loss, and that the strategy be defensible under scrutiny. Optimistic was chosen over pessimistic
leasing because it is both more user-friendly — no lock to wait on, acquire, or leak — and simpler to
implement, needing no lease store, no renewal, and no crash-recovery path. Adding `@Version` alone is
**necessary but not sufficient**: holdings are child rows, the existing single-add endpoint has no
version input, and the current seeder deletes and recreates the portfolio. Every writer must
participate in one aggregate version, or the lock is bypassable by whichever writer ignores it.

Scope is deliberately limited to the contract. This spec delivers no frontend change.

## Glossary

- **Composition_Operation**: The single Application_Operation that replaces a portfolio's complete holding set with a caller-supplied desired state, atomically and under an optimistic version check. The only holdings writer this spec leaves generally reachable.
- **Desired_State_Write**: A write whose payload is the complete intended result rather than a delta. Omission means deletion. Contrast with the existing additive `POST /api/portfolio/{portfolioId}/holdings`.
- **Portfolio_Aggregate**: The portfolio row together with its `asset_holdings` child rows, treated as one consistency and versioning unit. The unit the Portfolio_Version protects.
- **Portfolio_Version**: A monotonically increasing integer on the Portfolio_Aggregate, incremented exactly once per holdings mutation, supplied by the client on write and compared before mutation.
- **Version_Conflict**: The condition where a supplied Portfolio_Version does not match the stored one. Yields `409` and no mutation.
- **Primary_Portfolio**: The single portfolio belonging to a user. This spec makes "exactly one per user" an invariant rather than a convention.
- **Portfolio_Provisioning**: Creation of a user's Primary_Portfolio. Occurs on first write via the Composition_Operation, and for pre-existing users via the Portfolio_Backfill.
- **Portfolio_Backfill**: The migration creating an empty Primary_Portfolio for every existing user who has none.
- **Quantity_Domain**: The permitted value range for a holding quantity: required, strictly positive, at most 11 integer digits and 8 fractional digits, maximum `99999999999.99999999`. Derived from the `NUMERIC(19, 8)` column contract.
- **Decimal_String**: A quantity represented on the wire as a JSON string in plain decimal notation, never as a JSON number and never in exponential notation.
- **Asset_Discovery_Response**: The body of `GET /api/assets` — the Catalog_Version and the full Supported_Catalog entry set, without prices and without `basePrice`.
- **Selectable_Asset**: An Active_Asset, per Spec A's Lifecycle_Status. The only kind a Composition_Operation may introduce or increase.
- **Retained_Deprecated_Position**: An existing Deprecated_Position carried unchanged or reduced through a Composition_Operation. It may be retained, reduced, or removed; it may not be introduced or increased.
- **Writer_Convergence**: The requirement that every remaining holdings write path either routes through the Composition_Operation or participates in the same Portfolio_Version.
- **Identity_Preserving_Reset**: A reset that replaces a portfolio's holdings **within** the existing portfolio row, preserving its id and advancing its Portfolio_Version. Distinct from the Golden_State_Seeder, which deletes and recreates.
- **Activation_Gate**: The production condition under which the Composition_Operation becomes user-reachable — Spec A's Requirement 4 cutover complete and verified.
- **Supported_Catalog**, **Catalog_Version**, **Catalog_Module**, **Lifecycle_Status**, **Active_Asset**, **Deprecated_Asset**, **Deprecated_Position**, **Application_Operation**, **Http_Entry_Point**, **Golden_State_Seeder**, **New_Write_Invariant**: as defined in `supported-asset-integrity/requirements.md`. Not redefined here; this spec traces to those definitions rather than restating them.

## Requirements

### Requirement 1: Exactly one portfolio per user

**User Story:** As a user, I want a portfolio to exist whenever I need to compose one, so that the picker is usable on my first visit rather than failing on an absent prerequisite.

#### Acceptance Criteria

1. THE system SHALL treat "every user has exactly one Primary_Portfolio" as an invariant, not a convention, and SHALL make it true at **every** point in a user's lifecycle rather than from first write onward.
2. THE Portfolio_Backfill SHALL create an empty Primary_Portfolio for every existing user who has none.
3. THE Portfolio_Backfill SHALL create no `asset_holdings` rows, so that it cannot interact with Spec A's New_Write_Invariant.
4. THE Portfolio_Backfill SHALL be idempotent under Flyway re-execution.
5. THE signup path SHALL provision a Primary_Portfolio as part of the same transaction that creates the user, so that 1.1 holds from the moment the user exists.
6. IF user creation and Portfolio_Provisioning cannot both commit, THEN THE system SHALL commit neither, and signup SHALL fail rather than produce a user without a portfolio.
7. THE signup-time provisioning in 1.5 MAY be performed by `api-gateway`, which already writes the `users` table owned by `portfolio-service` through `UserCredentialRepository` — the schema-ownership objection recorded in Revision 1 was factually wrong. See D2 and D17.
8. THE database SHALL enforce at most one portfolio per user by unique constraint, so that the invariant does not rely on application code alone.
9. WHEN the Composition_Operation is invoked by a user with no Primary_Portfolio, THE system SHALL provision one within the same transaction as the composition write, as a **recovery fallback** rather than the primary mechanism.
10. THE fallback in 1.9 SHALL be retained even though 1.5 makes it unreachable for correctly provisioned users, because it covers a user created before 1.5 shipped whom the backfill missed, and because its absence would turn that case into an unrecoverable `404`.
11. IF provisioning and composition cannot both commit under 1.9, THEN THE system SHALL commit neither.
12. THE Composition_Operation SHALL NOT accept a portfolio identifier in its path or body; it SHALL target the caller's Primary_Portfolio, resolved from the authenticated principal.
13. THE existing multi-portfolio capability SHALL be treated as unreachable rather than merely unused: no product path creates a second portfolio and no path selects between portfolios.
14. THE Portfolio_Backfill SHALL be verified against production counts before and after, since the preflight established `users=7, portfolios=2` and expects `portfolios=7` afterwards.
15. THE ordering SHALL be: Portfolio_Backfill and the unique constraint land together; signup provisioning ships no later than the constraint; the second creation path in Requirement 8 is closed no later than the constraint. THE constraint SHALL NOT land while a path capable of creating a duplicate remains reachable.

### Requirement 2: Asset discovery

**User Story:** As a picker client, I want one endpoint returning everything I may display or select, so that I need no second source for catalog identity.

#### Acceptance Criteria

1. THE system SHALL expose `GET /api/assets` returning an Asset_Discovery_Response.
2. THE Asset_Discovery_Response SHALL contain the Catalog_Version and the **full** Supported_Catalog entry set, including Deprecated_Assets.
3. THE response SHALL NOT be restricted to Active_Assets, because a Retained_Deprecated_Position must render with its catalog metadata and be distinguishable from an unknown ticker.
4. EACH entry SHALL carry at minimum: canonical ticker, name, aliases, asset class, quote currency, and Lifecycle_Status.
5. THE response SHALL NOT contain prices.
6. THE response SHALL NOT contain `basePrice`, per Spec A's Seed_Only_Interface constraint.
7. THE endpoint SHALL be served by `portfolio-service`, which already holds the Catalog_Module in memory, so that discovery requires no cross-service call.
8. THE api-gateway SHALL route `/api/assets` to `portfolio-service`; no such route exists today.
9. THE response SHALL carry `ETag` set to the Catalog_Version.
10. THE response SHALL carry `Cache-Control: private, no-cache`, so that a client may retain the body across sessions but must conditionally revalidate.
11. WHEN a client revalidates with a matching `If-None-Match`, THE system SHALL return `304` with no body.
12. THE system SHALL NOT introduce a second client-side persistent catalog cache, because catalog changes are deployment events and an application-level cache adds invalidation machinery without value.
13. THE endpoint SHALL require authentication, consistent with every other `/api` route.

### Requirement 3: The quantity invariant

**User Story:** As a maintainer, I want quantity constrained at every layer, so that the picker's "positive quantity" experience rests on an endpoint that cannot accept otherwise.

#### Acceptance Criteria

1. THE Quantity_Domain SHALL be: required, strictly positive, at most 11 integer digits, at most 8 fractional digits, maximum `99999999999.99999999`.
2. THE Composition_Operation SHALL reject any quantity outside the Quantity_Domain.
3. THE existing `POST /api/portfolio/{portfolioId}/holdings` SHALL enforce the Quantity_Domain for as long as that path exists.
4. EVERY seed and reset path SHALL satisfy the Quantity_Domain.
5. THE `asset_holdings.quantity` column SHALL gain a database `CHECK (quantity > 0)`.
6. THE `DEFAULT 0` on `asset_holdings.quantity` SHALL be dropped, because a default that violates the constraint is unreachable and misleading.
7. THE migration adding 3.5 and 3.6 SHALL be a plain constraint addition with no data repair, because the production preflight found zero violating rows across 163 holdings.
8. IF a violating row is found at migration time despite 3.7, THEN THE migration SHALL fail rather than clamp, because zero can mean "no position" and a negative may be a deliberately modelled short — different conditions needing different repairs.
9. THE rejection SHALL be a typed failure with atomic rollback at the Application_Operation layer, per Spec A's Layer_Composition_Rule, not a controller-level check.

### Requirement 4: Decimal fidelity on the wire

**User Story:** As a user, I want a quantity I never edited to survive a read-edit-save cycle unchanged, so that opening the picker cannot silently alter my holdings.

#### Acceptance Criteria

1. THE Composition_Operation request SHALL represent every quantity as a Decimal_String.
2. THE `PortfolioResponse` SHALL represent every quantity as a Decimal_String.
3. Criterion 4.2 SHALL apply to the **read** path as well as the write path, because `HoldingResponse.quantity` is a `BigDecimal` with no Jackson annotation and therefore emits a JSON number today — a client that reads `0.75000000`, drafts it, and writes it back has already lost fidelity before the write contract applies.
4. THE system SHALL NOT emit quantities in exponential notation.
5. THE system SHALL preserve trailing fractional zeros as stored, so that a round-trip is byte-stable.
6. THE requirement SHALL be verified by a round-trip test using `0.75000000`, a value the production preflight confirmed is in live use at scale 8.
7. THE system SHALL reject a quantity supplied as a JSON number rather than silently coercing it, so that a non-conforming client fails loudly at the boundary.

### Requirement 5: Portfolio versioning

**User Story:** As a user editing in one session, I want a concurrent edit from another session to be refused rather than silently overwritten, so that no change is lost without someone being told.

#### Acceptance Criteria

1. THE Portfolio_Aggregate SHALL carry a Portfolio_Version.
2. THE Portfolio_Version SHALL increment exactly once per holdings mutation, whether that mutation adds, changes, or removes holdings.
3. A mutation touching only child rows SHALL still increment the Portfolio_Version, because holdings are child rows and an unmodified parent would otherwise leave the version static.
4. THE system SHALL use an **explicit parent-version mechanism** — comparing the expected version against the parent row and forcing its increment within the same transaction as the child mutation, by `OPTIMISTIC_FORCE_INCREMENT` or an equivalent parent-row compare-and-set.
5. THE mechanism in 5.4 SHALL be mandatory. THE system SHALL NOT rely on a JPA inverse collection propagating a child mutation to the parent version, because that behaviour is mapping-dependent and would leave the concurrency contract resting on an implementation detail rather than a stated one.
6. THE mechanism in 5.4 SHALL additionally be verified by concurrent integration test, so that the guarantee is demonstrated rather than assumed from the annotation alone.
7. A request whose desired state equals the stored state SHALL be exempt from the increment in 5.2, per 5.11.
8. THE `PortfolioResponse` SHALL carry the Portfolio_Version.
9. THE system SHALL NOT expose the Portfolio_Version through a separate endpoint, because a read-then-read sequence reintroduces the race the version exists to close.
10. WHEN a Composition_Operation succeeds, THE response SHALL be the complete `PortfolioResponse` carrying the new Portfolio_Version.
11. WHEN a Composition_Operation is submitted whose desired state equals the current state, THE system SHALL return `200` idempotently with the version unchanged and no `updated_at` advance.
12. THE `portfolios` table SHALL gain an `updated_at` column, which does not exist today — see D12.
13. THE `updated_at` column SHALL advance on every holdings mutation that increments the Portfolio_Version, so that B2's idle-guard reset has a durable signal to read.
14. A newly provisioned Primary_Portfolio SHALL have Portfolio_Version `0`.

### Requirement 6: Desired-state composition

**User Story:** As a user, I want to submit my intended portfolio in one operation, so that adding, changing, and removing holdings cannot half-apply.

#### Acceptance Criteria

1. THE system SHALL expose `PUT /api/portfolio/holdings` accepting a Portfolio_Version and a complete desired holding set.
2. EACH element SHALL carry a canonical ticker and a Decimal_String quantity, and SHALL NOT carry a holding identifier, because identifiers would express rename semantics the operation does not need.
3. THE operation SHALL apply atomically: either the whole desired state is persisted or none of it is.
4. Omission of a ticker present in the stored state SHALL mean deletion.
5. THE order of elements SHALL be semantically irrelevant.
6. THE system SHALL reject duplicate tickers within one request rather than resolving them last-one-wins.
7. THE system SHALL accept only canonical tickers, not aliases, per Spec A's D5.
8. THE operation SHALL permit an Active_Asset to be created, changed, retained, or removed.
9. THE operation SHALL permit a Retained_Deprecated_Position to be retained unchanged, reduced, or removed.
10. THE operation SHALL reject introducing a Deprecated_Asset not already held, and SHALL reject increasing the quantity of a Retained_Deprecated_Position.
11. THE operation SHALL NOT impose a fixed maximum set size; uniqueness plus catalog resolution already bounds the request by Supported_Catalog cardinality, and a literal bound would reintroduce the fixed-count defect Spec A removed.
12. THE Catalog_Version SHALL NOT be a precondition of the write, because Spec A's version changes for metadata and `basePrice` as well as membership, so exact equality would reject a valid save after an irrelevant catalog edit.
13. THE empty desired set SHALL be valid and SHALL mean "remove all holdings".
14. FOR a retained ticker whose quantity changes, THE system SHALL preserve the existing cost-basis tuple unchanged.
15. FOR a newly added ticker, THE system SHALL capture cost basis under the existing add-time rule.
16. FOR a removed ticker, THE system SHALL discard its holding and its cost basis.
17. THE system SHALL NOT infer a weighted purchase price from a quantity change, because that would invent a transaction the user never supplied — this is a snapshot editor, not a trade ledger.
18. THE system SHALL validate the **complete** desired set — every ticker's catalog resolution, lifecycle permission, quantity domain, and uniqueness — before performing any destructive replacement or cost-basis capture, so that an invalid desired set cannot leave partial state behind.
19. Criterion 6.18 SHALL hold independently of transactional rollback, because ordering validation first makes the atomicity in 6.3 a property of the operation rather than solely of the transaction manager.
20. WHEN a user has no Primary_Portfolio, THE Composition_Operation SHALL require an expected Portfolio_Version of `0`.
21. WHEN a first Composition_Operation carrying expected version `0` succeeds against a non-empty desired set, THE system SHALL provision the Primary_Portfolio, apply the desired state, and return `201` with Portfolio_Version `1`.
22. WHEN two concurrent first requests race, THE system SHALL allow exactly one to create the Primary_Portfolio; the loser SHALL receive `409` per Requirement 7 and SHALL NOT create a second portfolio.
23. Criterion 6.22 SHALL be enforced by the unique constraint in 1.8, not by application-level check-then-act, which cannot exclude the race.
24. WHEN a first Composition_Operation carries an **empty** desired set and the user has no Primary_Portfolio, THE system SHALL provision an empty Primary_Portfolio and return `201` with Portfolio_Version `1`, rather than rejecting — this is the same end state the Portfolio_Backfill produces, so rejecting it would make the two provisioning paths disagree.
25. WHEN the expected Portfolio_Version is `0` but a Primary_Portfolio already exists with a non-zero version, THE system SHALL return `409` and SHALL NOT create or mutate anything.

### Requirement 7: Error contract

**User Story:** As a picker client, I want each failure distinguishable by code, so that I can show the right message without parsing prose.

#### Acceptance Criteria

1. WHEN the supplied Portfolio_Version does not match the stored one, THE system SHALL return `409` with a machine-readable code `portfolio_version_conflict`.
2. THE `409` SHALL carry the current Portfolio_Version.
3. THE `409` SHALL NOT instruct the client to reload or discard, and the server SHALL NOT reapply the request against the current version, because reapplying a desired-state payload silently overwrites the other session — last-write-wins with extra steps.
4. WHEN a request names a ticker absent from the Supported_Catalog, THE system SHALL return `422` per Spec A's Unsupported_Asset_Rejection.
5. THE `422` SHALL carry the current Catalog_Version, so that a client knows when to invalidate and refetch `GET /api/assets`.
6. THE `422` SHALL identify every offending ticker, not only the first.
7. WHEN a request introduces a Deprecated_Asset not already held, or increases the quantity of a Retained_Deprecated_Position, THE system SHALL return `422` with a code distinct from the absent-from-catalog case of 7.4.
8. THE `422` of 7.7 SHALL carry the current Catalog_Version and SHALL identify every offending ticker, on the same terms as 7.5 and 7.6.
9. THE two `422` classes SHALL be distinguishable by code, because they have different remedies: an absent ticker means the client's catalog is stale and it should refetch, whereas a lifecycle rejection means the catalog is current and the requested change is not permitted.
10. WHEN a quantity violates the Quantity_Domain, THE system SHALL return `400` with a code distinguishing it from an unsupported asset.
11. WHEN a request contains duplicate tickers, THE system SHALL return `400` with a distinct code.
12. WHEN a single request contains more than one class of error, THE system SHALL apply this precedence: Version_Conflict `409` first, then malformed input `400`, then catalog and lifecycle rejection `422`.
13. THE precedence in 7.12 SHALL be deterministic and specified rather than an artifact of implementation order, so that a caller receives the same status for the same request regardless of internal evaluation sequence.
14. Version_Conflict SHALL outrank the others because a stale request's other contents describe an intent formed against state the caller can no longer see; re-reading is the necessary first step regardless of what else is wrong.
15. WITHIN one status class, THE system SHALL aggregate every offending element rather than reporting only the first, so that a caller can correct a request in one pass.
16. EVERY rejection in this requirement SHALL leave stored state unmutated.
17. THE error codes SHALL be stable identifiers, so that B2 can branch on them without string matching on human-readable text.

### Requirement 8: Writer convergence

**User Story:** As a maintainer, I want one versioned holdings writer, so that the concurrency contract cannot be bypassed by a path that ignores it.

#### Acceptance Criteria

1. EVERY holdings write path SHALL either route through the Composition_Operation or participate in the same Portfolio_Version.
2. THE versionless `POST /api/portfolio/{portfolioId}/holdings` SHALL be retired in this spec, not deferred — see D11.
3. THE E2E consumers of that endpoint SHALL be migrated as part of this spec; `frontend/tests/e2e/helpers/api.ts` is the only live consumer, and no production frontend code calls it.
4. THE retirement SHALL leave no holdings writer that accepts a mutation without a version check, except the paths explicitly exempted in 8.5.
5. THE second creation path `POST /api/portfolio`, which calls `PortfolioService.createPortfolio` unconditionally, SHALL be closed in this spec — either retired, or made idempotent so that it resolves and returns the caller's existing Primary_Portfolio instead of creating another.
6. THE E2E helper that calls `POST /api/portfolio` whenever the portfolio list is empty SHALL be migrated as part of this spec.
7. THE system SHALL NOT surface a unique-constraint violation as the public error for a duplicate creation attempt; the outcome of 8.5 SHALL be a specified response, not a database exception escaping the boundary.
8. Criterion 8.5 SHALL be sequenced no later than the unique constraint in 1.8, per 1.15, because the constraint would otherwise convert a reachable duplicate-creation path into a raw database error.
9. THE Golden_State_Seeder and Flyway migrations SHALL be exempt from the version check, because they are not concurrent user edits; they SHALL nonetheless satisfy the Quantity_Domain and Spec A's New_Write_Invariant.
10. THE system SHALL provide an Identity_Preserving_Reset that replaces holdings within the existing portfolio row, preserving its id and advancing its Portfolio_Version.
11. THE Identity_Preserving_Reset SHALL NOT reuse `PortfolioSeedService.seed()`, which opens by deleting every portfolio for the user, making identity preservation impossible with that implementation.
12. THE Identity_Preserving_Reset SHALL be delivered here as a mechanism; its trigger and schedule belong to B2.
13. THE reset SHALL be holdings-only, per Spec A's D20.
14. WHEN a concurrent edit collides with a reset, THE user SHALL receive the settled `409`, not a `404` — which is what a delete-and-recreate implementation would produce.
15. THE Golden_State_Seeder's delete-and-recreate behaviour SHALL be reconciled with the unique constraint in 1.8 and the exactly-one invariant, since it deletes every portfolio for a user and creates a fresh one.

### Requirement 9: Activation gate

**User Story:** As an operator, I want the composition write to become reachable only once its preconditions actually hold in production, so that it cannot fail on data it did not create.

#### Acceptance Criteria

1. THE Composition_Operation SHALL NOT become user-reachable before Spec A's **enforcement activation and verified steady state** — the milestone Spec A's cutover sequence labels the R4 release artifact, not its Requirement 4, which concerns symbol corrections. THE numeric shorthand SHALL NOT be used here, because the same token names both a release stage and a requirement in the source spec.
2. Criterion 9.1 SHALL be a **production activation gate**, not a development dependency: B1 may be implemented and tested in full while Spec A proceeds.
3. `GET /api/assets` MAY deploy dark once the Catalog_Module is available in `portfolio-service`, being read-only and side-effect free.
4. THE gate exists because, before the Postgres repair, a desired-state request can carry a legacy `BTC` that does not resolve to the catalog and fail even though the user never edited it.
5. THE gate also exists because, before the repairs and enforcement flip, the global invariant the picker relies on is not yet true.
6. THE gate also exists because, while a permissive versionless writer remains, the composition lock is bypassable.
7. THE activation SHALL be verified by evidence that Spec A reached its final steady state, not by deployment order alone.

### Requirement 10: Non-goals

1. THIS spec SHALL deliver no frontend change. The picker modal, draft and conflict UX, selected-price loading, freshness presentation, demo reset trigger, and presence banner belong to B2.
2. THIS spec SHALL NOT implement the presence mechanism. Its decisions are recorded in the checkpoint and belong to B2.
3. THIS spec SHALL NOT modify `ReadOnlyEnforcementFilter`. Its allowlist is path-only and must become method-plus-path before the demo account may reach the composition write — B2 owns that, and D14 records why it cannot be a path-only addition.
4. THIS spec SHALL NOT add per-holding freshness state. Spec A exposes aggregate `assetPriceFreshness`; if B2's product design requires row-level badges, that is a backend addition specified there — the client SHALL NOT derive freshness independently.
5. THIS spec SHALL NOT introduce multi-portfolio selection, which Requirement 1 establishes as unreachable.
6. THIS spec SHALL NOT change FX handling, valuation, or the refresh pipeline.
7. THIS spec SHALL NOT introduce a trade ledger or transaction history, per 6.17.

## Recorded Decisions and Constraints

### D1 — Composition targets the caller's portfolio, with no identifier on the wire
Taking no portfolio id resolves three problems at once: "which portfolio" leaves the wire contract
entirely, the new-user case needs no separate create step, and the atomic create-composition versus
update-composition split disappears. Owner decision, checkpoint entry [7]: "Every user must have one
portfolio. For simplicity, only one is fine."

### D2 — Signup-time provisioning, with first-write retained as a recovery fallback
**Superseded Revision 1's decision, which was wrong.** Revision 1 chose first-write provisioning on
the grounds that signup provisioning would require the gateway to write another service's table.
That premise is false: `api-gateway` **already** writes `users`, a table owned by
`portfolio-service`, through `UserCredentialRepository.insertUser`
(`UserCredentialRepository.java:49`), whose own javadoc describes the tables as "owned by"
portfolio-service. The boundary D2 invoked does not exist in practice, and provisioning a portfolio
in the same `TransactionTemplate` that already creates the user is neither a cross-service call nor
a new failure mode — it is one more insert in a transaction that already spans these tables.

The decisive argument, though, is not cost but correctness. The owner's requirement is that **every
user has a portfolio**. Lazy provisioning cannot satisfy that: between signup and first composition,
a user has none. Revision 1's claim that signup provisioning "adds no guarantee" was simply false —
it guarantees exactly the thing the invariant asserts. First-write provisioning survives as a
recovery fallback for users the backfill missed, which is a genuinely different and much narrower
role than being the primary mechanism.

### D17 — Why this decision was wrong twice, and what that implies
Q11 has now been answered three times: signup provisioning (entry [7]), first-write provisioning
(Revision 1), and signup provisioning again (Revision 2). Both reversals turned on a **verifiable
code fact that had not been checked** — first where `SignupService` lives, then what it is already
permitted to write. Neither turned on a change of judgement about the design.

The lesson is recorded because it generalises: an argument resting on an asserted architectural
boundary should not be accepted, including from its own author, until the boundary is shown to be
observed in the code. The "schema owner" language in `V14` is a comment, and comments describe
intent rather than enforcement. Reviewers should treat the remaining architectural claims in this
document as carrying the same risk.

### D3 — The backfill is required, and is not merely forward provisioning
"Every user must have one" is a statement about existing rows as well as future ones. The preflight
found `users=7, portfolios=2`: five users have none. Forward provisioning alone would leave them
dependent on the first-write path, which is a fallback rather than a guarantee.

### D4 — The quantity constraint is free, which is why it is in scope
Checkpoint entry [5] reserved judgement pending data, correctly: a clamping decision on live
quantities would be an audited repair, not a constraint addition. The preflight returned
`zero=0, negative=0` across 163 rows, so the ambiguity never materialises. Requirement 3.8 keeps the
fail-closed behaviour anyway, because the preflight is a point-in-time observation and the migration
runs later.

### D5 — Decimal fidelity is a read-side problem first
The database permits 19 significant digits; JavaScript `number` cannot round-trip that domain
exactly. Entry [5] specified Decimal_Strings for the write contract, which is necessary but not
sufficient: today's **read** already emits a JSON number, so a draft is created from a lossy value
before the write contract applies. Scale 8 is in live use, so this is concrete.

### D6 — The version lives in `PortfolioResponse`
A separate version endpoint invites a read-then-read race — the client reads holdings, then reads a
version that may already have moved. Verified: `PortfolioResponse` is
`(id, userId, createdAt, holdings)` and carries no version today, so this is a read-side change, not
only an `@Version` column.

### D7 — On conflict the draft is lost, deliberately
No reapply, no merge, no auto-reload. Reapplying a desired-state payload against a newer version
silently overwrites the other session, which is last-write-wins with extra steps — the exact
behaviour the owner rejected when choosing this strategy. The user-facing reload action is what
discards the draft, and the user takes it knowingly.

### D8 — `catalogVersion` is not a write precondition
Spec A's Catalog_Version changes for metadata and `basePrice`, not membership alone. Exact equality
would reject a valid composition save after a catalog edit irrelevant to the tickers being written.
The `422` already returns the current version, which is the signal a client needs.

### D9 — No fixed size cap
Uniqueness plus catalog resolution bounds the request by Supported_Catalog cardinality. A literal
`160` would reintroduce precisely the fixed-count defect Spec A's D6 removed.

### D10 — Discovery returns the whole catalog, not only active entries
Spec A retains `TATAMOTORS.NS` as a Deprecated_Position. A picker receiving only active assets could
neither render that legitimate holding with its metadata nor distinguish it from an unknown ticker.
The browse pane offers only Active_Assets; the selected pane resolves and labels deprecated ones.

### D11 — The versionless writer is retired in B1, not B2
Deferring leaves a versionless writer alive across the whole of B1, so the concurrency contract is
bypassable for the entire period it is supposed to hold. The migration cost is low and was verified:
`frontend/tests/e2e/helpers/api.ts` is the only live consumer, plus one already-skipped spec. Gateway
tests reference `/api/portfolio/holdings` as a generic protected path, not as consumers of this
endpoint.

### D12 — `portfolios.updated_at` does not exist — correction to checkpoint entry [6]
Entry [6] answered Q9 by proposing an idle guard on `portfolios.updated_at` and asserted the column
already exists, citing `V1__Initial_Schema.sql:32`. That line is `market_prices.updated_at`. The
`portfolios` table is `(id, user_id, created_at)` and no later migration adds to it. The column is
therefore new work, placed here in Requirement 5.9 because this spec owns portfolio identity and
versioning, even though its only consumer is B2's reset trigger.

### D13 — Freshness is portfolio-level, and B1 adds nothing for it
Spec A exposes aggregate `assetPriceFreshness`, not per-holding state. A row badge would require the
client to duplicate the backend threshold and precedence from raw `observedAt`, which would drift.
If row badges are wanted, the backend must compute per-holding state first — specified in B2, not
derived client-side.

### D14 — The read-only allowlist must match method plus path
Verified: `aiAllowlistPatterns` is a `List<String>` matched by `AntPathMatcher` against the path
only, with no method component. Adding the holdings path there would silently permit **every**
mutating method on that path, including ones added later. B2 owns the change; it is named here so
the constraint is not rediscovered.

### D15 — B1 and B2 split to keep the review surface small
Spec A mixed persistence, API, gateway, and frontend state in one document and spent roughly half of
each review round fixing defects introduced by the previous round's fix. B1 is independently
contract-testable and is the stable seam B2 consumes.

### D16 — Review history
Revision 1 resolved Q11 and Q12 rather than leaving the spec blocked. The adversarial review in
checkpoint entry [9] did not clear the gate: five blocking findings, every one accepted after
independent verification, plus both secondary findings. Revision 2 is the result. D12's correction
held up on audit; one further citation was wrong (`loadBackendPortfolio`, not `getPrimaryPortfolio`)
and is fixed.

### D18 — The exactly-one invariant needs three paths closed, not one
Requirement 1's unique constraint is only safe once **every** creation path is accounted for. There
are three: signup (1.5), the composition fallback (1.9), and `POST /api/portfolio` (8.5). Revision 1
specified the constraint while leaving the third reachable, which would have produced duplicates
before the constraint landed and an unhandled database error after. The ordering in 1.15 exists to
make that sequencing explicit rather than incidental.

### D19 — Error precedence is specified because it is otherwise emergent
A request can be simultaneously stale, malformed, and lifecycle-invalid. Without a stated
precedence, the returned status depends on internal evaluation order, so the same request could
yield different statuses across refactors. `409` outranks the rest because a stale request's other
contents describe an intent formed against state the caller can no longer see — re-reading is the
necessary first step regardless of what else is wrong.

### D20 — Open for review
D2 has now reversed twice (see D17), so it warrants scrutiny rather than acceptance, and this
revision's other architectural claims should be treated as carrying the same unverified-boundary
risk. Requirement 6's creation contract (6.20 to 6.25) is new in this revision and has had no
adversarial pass. The Golden_State_Seeder reconciliation in 8.15 is stated as a requirement without
a proposed mechanism, which is deliberate — it belongs to design — but it may prove to conflict with
the exactly-one invariant in a way that forces a requirements change.
