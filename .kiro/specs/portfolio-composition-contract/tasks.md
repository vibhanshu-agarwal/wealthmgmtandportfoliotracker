# Implementation Plan

> **Revision 1 — 2026-08-16.** Written against `design.md` Revision 8
> (`git hash-object 81a36be00d4386ae4d68c1a98c6d840831e4bbd6`) and `requirements.md` Revision 6
> (frozen, `cbba0b38741bf2358f6605ca21f5fa8912f2e2b1`).
>
> **Name-based references only**, as in the design: tasks cite decisions and gates by name
> (`D1`, `G2b`, `P11g-2`), never by requirement criterion number. Ten reference defects were found
> across six requirements revisions, eight of which resolved numerically and passed the checker.

## Overview

Eight waves plus a predecessor. The ordering encodes two constraints that took nine design revisions
to state correctly:

1. **Evidence must describe the artifact that serves.** Every capability gate splits into a candidate
   proof bound to an immutable digest and a serving proof collected after rollout. A task that
   "verifies" a service and then rebuilds it has proved nothing.
2. **No release may invalidate its own evidence.** This is why the asset route ships with the
   gateway, why the seed switch is its own release, and why Wave P exists at all.

**Wave P is a separate pull request and a hard predecessor.** Nothing in Waves 0–7 may start until
its stop/go checkpoint is green.

**Spec A is the graph predecessor.** B1's migration is **V20**; Spec A owns V17–V19 in the same
directory, and two migrations numbered 17 do not merge badly — Flyway refuses to start. B1 may be
built and tested in parallel, but its releases run after Spec A reaches verified steady state.

The design is frozen at Revision 8. Where a task and the design disagree, **the design is
normative**; raise it rather than resolving it in code.

Stack: **Java 21 / Spring Boot 4.1**, `hibernate-core 7.4.1.Final`,
`tools.jackson.core:jackson-databind 3.1.4`, JUnit 5 + Testcontainers (Postgres) for integration,
Playwright for E2E, GitHub Actions for the release gates.

## Global Constraints

- **`OPTIMISTIC_FORCE_INCREMENT` is not used.** One explicit parent `UPDATE … SET version = version +
  1, updated_at = GREATEST(?, updated_at + INTERVAL '1 microsecond') WHERE id = ? AND version = ?`,
  exactly one affected row, before any child DML. Stacking it with a dirtying flush double-increments
  on this Hibernate version.
- **Spec A's frozen HTTP body is not modified.** `{"error": "unsupported_asset", "ticker": …,
  "catalogVersion": …}` stays byte-identical on the existing single-write path. B1 adds `tickers`
  alongside `ticker` on the composition path only.
- **The internal seed target stays server-fixed.** No task parameterises it to accept a caller-supplied
  user id.
- **No separate version endpoint.** The version travels with the portfolio state its reader observed.
- **Every gate binds to a digest**, never to a service name or revision label.

---

## Wave P — Deployment prerequisite (separate PR)

- [ ] **P.1 Add a service allowlist to `deploy-azure.yml`.** An unselected service receives **no
  `az containerapp update` at all** — not a re-deploy at its existing digest, which can still create
  or mutate revision state. Full-deploy remains the default for `workflow_call` and ordinary
  dispatch; the cutover passes an explicit allowlist.
- [ ] **P.2 Prove non-interference.** For a filtered run naming only `portfolio-service`, assert every
  **unselected** app's revision name, image digest, and traffic weight are byte-identical before and
  after. Store the before/after capture as the artifact.
- [ ] **P.3 STOP/GO — deployment prerequisite.** P.2 green. **No wave below may begin until this
  checkpoint passes.** If it cannot pass, the cutover falls back to signup quiescence for V20 and an
  activation control for R-C, and the release graph must be re-derived before proceeding.

---

## Wave 0 — Fixture identity migration (no production change)

Behaviour-neutral in production; it changes only test fixtures. It comes first because Artifact 0
removes the two endpoints these fixtures currently depend on.

- [ ] **0.1 Move `helpers/api.ts` to the E2E identity.** Resolve the E2E user rather than `dev@local`.
- [ ] **0.2 Move `helpers/browser-auth.ts` to the E2E identity.** This is the second, independent
  identity path — `global.setup.ts` and `golden-path.spec.ts` install the browser session immediately
  before the API helper runs. Migrating only the API helper yields a green suite that proves nothing:
  API assertions pass against the E2E portfolio while the page renders dev's empty one.
- [ ] **0.3 Convert `ensurePortfolioWithHoldings` to read-and-assert.** It currently creates a
  portfolio via `POST /api/portfolio` and adds holdings via the versionless `POST`. It must instead
  assert the Golden-State setup and **fail hard** when seeding was skipped, never repair silently.
- [ ] **0.4 Update ticker expectations to canonical symbols.** `golden-path.spec.ts` asserts `BTC` in
  two places; after Spec A the Golden-State set carries `BTC-USD`. Update the file's header comment
  too — it still describes the V3 seed as the fixture source.
- [ ] **0.5 Wire E2E credentials into `ci-verification.yml`.** It supplies `INTERNAL_API_KEY` today
  and no E2E email or password.
- [ ] **0.6 Wire `frontend-e2e-integration.yml`** with both the internal key and E2E credentials. It
  has neither and still runs the affected suites, so leaving it unwired would leave a known-red
  manual workflow.
- [ ] **0.7 G0b evidence.** `golden-path` and `dashboard-data` pass against a **fresh disposable
  database** in one hermetic `ci-verification.yml` run, on the migrated identity.

## Wave 1 — Legacy writer retirement (Artifact 0 → R-0)

- [ ] **1.1 Retire `POST /api/portfolio`.** Pin the response — normally `405` on the surviving
  collection route. A unique-constraint violation must never surface as the public error.
- [ ] **1.2 Retire the versionless `POST /api/portfolio/{portfolioId}/holdings`.**
- [ ] **1.3 Apply Quantity_Domain validation to any interval either path remains reachable.** If both
  retire together this is vacuous; state that explicitly rather than skipping the check.
- [ ] **1.4 G0a evidence.** No traffic-serving portfolio digest exposes either route. Revision → digest
  → traffic capture.
- [ ] **1.5 STOP/GO — R-0.** G0a and G0b both green.

## Wave 2 — Gateway provisioning + asset route (Artifact 1 → R-A)

- [ ] **2.1 Add the provisioning insert to `SignupService`**, inside its existing
  `TransactionTemplate`, after `insertCredential`. Bind `userId.toString()` explicitly — the gateway
  generates a `UUID` and `portfolios.user_id` is `VARCHAR(255)`. Name only columns present in both
  schemas: `INSERT INTO portfolios (id, user_id)`, letting both timestamps and `version` default.
- [ ] **2.2 G1 candidate proof — dual schema, pinned to V19 → V20.** Integration test runs the insert
  against a database at V19 and one at V20, exercising the `toString()` binding. A run from today's
  V16 or an unspecified baseline does not satisfy this.
- [ ] **2.3 Add the `/api/assets/**` gateway route.** Ships here, not with the composition endpoint,
  so R-C cannot invalidate G2.
- [ ] **2.4 STOP/GO — G1 before deploy.** If 2.2 cannot pass, switch to the signup-quiescence path
  and re-derive the remaining waves before continuing.
- [ ] **2.5 G2 serving proof.** Every serving gateway digest provisions at signup: revision → digest,
  traffic, controlled probe.

## Wave 3 — Schema (Artifact 2 → R-B)

- [ ] **3.1 Write `V20`.** In file order: add `version BIGINT NOT NULL DEFAULT 0`; add `updated_at
  TIMESTAMP NOT NULL DEFAULT now()`; backfill with `u.id::text` casts on **both** the `INSERT` and the
  `NOT EXISTS` correlation; `ALTER TABLE portfolios ADD CONSTRAINT uq_portfolios_user_id UNIQUE
  (user_id)` as a **named table constraint**, not a bare index; drop the `quantity` default; add
  `chk_asset_holdings_quantity_positive`.
- [ ] **3.2 Prove backfill idempotency** under Flyway re-execution, and prove the `NOT EXISTS`
  correlation actually matches. A silent type mismatch there treats every user as unprovisioned and
  inserts duplicates on re-run.
- [ ] **3.3 Add `version` and `updatedAt` to `Portfolio`; set both timestamps from one instant in
  `@PrePersist`.** Two `Instant.now()` calls can differ, which would make the equal-at-creation
  semantics false at database precision.
- [ ] **3.4 STOP/GO — R-B preconditions.** G0a, G0b and G2 all green **before** the migration runs.
- [ ] **3.5 G3 evidence.** Relational postcondition after migration: no user has a portfolio count
  other than one. Assert the invariant, never a fixed total — a legitimate signup changes the number,
  and equal totals can mask one missing user against one duplicate.

## Wave 4 — Contract implementation (no public exposure)

Buildable and fully testable before any of it is reachable.

- [ ] **4.1 `HoldingReplacementService`** — the single orchestrator, in D2's exact order: version
  precondition → semantic `400` (quantity, then duplicates) → catalog/lifecycle `422` aggregated →
  materialise via the injected `TuplePreparer` against the locked snapshot → compare → single parent
  CAS → refresh → child DML.
- [ ] **4.2 `CompositionTuplePreparer`** — expands ticker/quantity, preserving retained cost-basis
  tuples and capturing new ones. Reads **only** the snapshot locked in step 1.
- [ ] **4.3 `GoldenStateTuplePreparer`** — supplies its deterministic tuple and **takes the cost-basis
  anchor as an input**. Hardcoding the moving 25-hour value would silently undo Spec A's move of the
  demo path onto its fixed `app.demo.cost-basis-anchor`.
- [ ] **4.4 Absent-aggregate path.** Reject every non-zero expected version with `409` and virtual
  current version `0` **before** validation or insert; then validate, insert, and arbitrate on the
  named `uq_portfolios_user_id` constraint only.
- [ ] **4.5 Error envelope.** `ContractError` shape with `error` as the machine-code field. Plural
  `UnsupportedAssetsException` for aggregation; Spec A's singular exception and handler untouched on
  their path.
- [ ] **4.6 Envelope boundary.** `HttpMessageNotReadableException` handler for malformed JSON and
  rejected tokens; `MethodArgumentNotValidException` handler for a missing `expectedVersion`. Boxed
  `Long` with `@NotNull`, plus a **property-scoped strict deserializer accepting only an integer
  token** — Jackson 3.1.4 defaults to `TryConvert` with `ACCEPT_FLOAT_AS_INT`, so `7.9` and `"7"`
  would otherwise decode as valid versions.
- [ ] **4.7 Decimal fidelity both directions.** Strict string deserializer on write;
  `toPlainString()` serializer on `HoldingResponse.quantity`, which emits a JSON number today.
- [ ] **4.8 `GET /api/assets` controller**, `ETag` on Catalog_Version, `Cache-Control: private,
  no-cache`, `304` on match. No prices, no `basePrice`.
- [ ] **4.9 Add `version` to `PortfolioResponse`.**

### Correctness properties — the candidate proof suite

Named here so the R-C evidence bundle can enumerate exactly what it contains.

- [ ] **4.10 P1** — four-case matrix (version match/mismatch × desired equal/differs) on **both**
  writers.
- [ ] **4.11 P2** — child-only change advances the parent version **exactly once**. Assert the
  numeric delta, not "changed": a double increment moves it too.
- [ ] **4.12 P3, P4** — concurrent composition, and two concurrent creators with **empty** desired
  sets, which is the case a pre-write version comparison cannot distinguish.
- [ ] **4.13 P5, P6** — stale-but-equal reset yields `409`; a lost reset performs no retry.
- [ ] **4.14 P7, P11f** — round-trip `0.75000000` byte-identical; no-op equality decided on the
  persisted `NUMERIC(19,8)` representation, since `BigDecimal.equals` reports `0.75` and `0.75000000`
  unequal.
- [ ] **4.15 P8, P11c, P11h** — envelope precedence; every envelope-failure code reachable and
  distinct; float, string, boolean and negative version tokens tested **independently**, sharing the
  one `invalid_version` code.
- [ ] **4.16 P9** — a quantity `CHECK` violation surfaces as its own `400`, never as `409`.
- [ ] **4.17 P11a, P11b** — creation binds both timestamps; the no-op path writes nothing and the
  **response** version equals the stored version.
- [ ] **4.18 Monotonic `updated_at`** — supply an equal timestamp, then a **regressed** one, and
  assert `new.updated_at > old.updated_at` in both cases.

## Wave 5 — Version-bearing read (Artifact 2a → R-B2)

- [ ] **5.1 Expose `version` on the authenticated `GET /api/portfolio`** while the old seed `POST`
  still tolerates the extra body field.
- [ ] **5.2 G2a serving proof.** **Every** serving portfolio digest returns the version, **before**
  any caller migration begins. One caller's successful read can otherwise hit the new revision while
  another still reaches an old response with no version.
- [ ] **5.3 Migrate all three seed call sites** to log in, read once, and send that exact version:
  `synthetic-monitoring.yml:170`, `global-setup.ts:191`, `api-live-smoke.spec.ts:194`. An Azure
  synthetic run reaches all three. `global-setup.ts` has no login-and-read step today.
- [ ] **5.4 Add E2E email/password to `deploy-azure.yml`'s seed step**, which carries only the user
  id and internal key.
- [ ] **5.5 Choose the `409` workflow outcome:** fail the execution once, log the body, **never
  retry**. Retrying against the newer version is the silent overwrite the contract prevents.
- [ ] **5.6 G5 evidence.** Every call site, in every execution context, sends a version. Zero
  missing-version requests — enumerated per site, not inferred from one green run.

## Wave 6 — Version-required seed (Artifact 2b → R-B3)

- [ ] **6.1 Seed `POST` requires `expectedVersion`** and delegates to `HoldingReplacementService`.
  Target stays compiled-in.
- [ ] **6.2 Remove `PortfolioSeedService.seed()`'s `deleteAll` + `flush` opening.**
- [ ] **6.3 Rewrite `PortfolioSeedServiceIT`** for identity preservation. Replace
  `EXPECTED_HOLDINGS = 160` with **active-catalog cardinality** — a literal would reintroduce the
  fixed-count defect Spec A removed. **Retain Spec A's full-table byte-identity price regression,
  sentinel rows included**: this edits the exact writer from the PR #97 incident.
- [ ] **6.4 STOP/GO — G5 before deploy.**
- [ ] **6.5 G2b serving proof.** Every serving digest requires the version and delegates; proved by a
  controlled seed showing identity preservation, the expected version outcome, and the price
  regression.

## Wave 7 — Activation (Artifact 3 → R-C)

Portfolio-service only. The asset route already shipped in Wave 2.

- [ ] **7.1 Build the R-C portfolio image once; capture its immutable digest.** Everything below binds
  to this digest.
- [ ] **7.2 Bind the candidate contract/integration run to that exact digest** — run against the image,
  or emit a provenance attestation mapping tested artifact and commit to it. Testing source and later
  rebuilding independently does not satisfy this.
- [ ] **7.3 Enumerate the candidate suites** included in the proof: tasks 4.10 through 4.18 by name.
- [ ] **7.4 Exhaustive holdings-writer inventory.** Enumerate from the source tree every path that
  mutates `asset_holdings` and show each participates in Portfolio_Version. Store the output with the
  same digest. This is what makes G6 satisfy **P11g-2**; a conjunction of three named paths cannot
  establish a property quantified over all of them.
- [ ] **7.5 Record the pre-deploy serving evidence:** serving G2, G3 recollected after the latest valid
  G2, G4, and G6 (serving G0a, G2a, G2b).
- [ ] **7.6 STOP/GO — R-C pre-deploy.** 7.2–7.5 all green. A prohibited rollback is a policy, not
  evidence.
- [ ] **7.7 Deploy; collect the serving proof.** Active revision → the 7.1 digest, traffic, controlled
  probe.
- [ ] **7.8 STOP/GO — post-deploy.** On failure, execute the documented rollback to **R-B3** and verify
  that safe digest is serving again. Never roll below the floor: it would restore a legacy writer
  under a live constraint.
- [ ] **7.9 P11g-1 / P11g-2 evidence.** Transitional floor before activation; Writer_Convergence floor
  after.

## Notes

- **`portfolios.user_id` stays `VARCHAR(255)`** (design O3). The `::text` casts bridge it. Converting
  a live identifier column is unrelated migration risk on a table already gating a production cutover;
  it is a type conversion, not a widening, and it is not deferred because the code is out of scope —
  B1 owns `Portfolio`, its repositories, and the seeder.
- **`ReadOnlyEnforcementFilter` is not modified here.** Its allowlist is path-only; the demo account
  reaching the composition `PUT` needs method-plus-path matching, which belongs to B2.
- **No per-holding freshness.** Spec A exposes an aggregate; if B2 wants row badges, the backend
  computes them there. The client never derives freshness independently.

## Task Dependency Graph

```
Wave P (separate PR) ──────────────────────────────────────────┐
                                                               │ hard predecessor
Spec A verified steady state ──────────────────────────────────┤
                                                               ▼
Wave 0 (fixtures) ──▶ Wave 1 (R-0) ──▶ Wave 2 (R-A) ──▶ Wave 3 (R-B)
                                                               │
                                     Wave 4 (contract, unexposed — parallel from Wave 0)
                                                               │
                                                               ▼
                              Wave 5 (R-B2) ──▶ Wave 6 (R-B3) ──▶ Wave 7 (R-C)
```

Wave 4 has no release of its own and may proceed in parallel from the start; it becomes reachable
only in Waves 5–7.
