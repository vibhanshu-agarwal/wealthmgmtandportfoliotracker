# Implementation Plan

> **Revision 5 — 2026-08-16.** Incorporates the fourth tasks review (checkpoint entry [41]). Three
> P1s and two P2s, all accepted. The open PR question from entry [40] is resolved by the review
> rather than by me.
>
> 1. **7.4 asserted both that the suites test the JAR and that they do not.** The Revision 4
>    correction landed in 7.5 and never reached the procedure it corrected, so the normative text
>    still said "test that JAR" twenty-five lines above the paragraph explaining why that is
>    impossible. Replaced with **one named `candidateVerification` aggregate** — `test`,
>    `integrationTest`, `bootJar` in one invocation from a clean checkout — plus a report-based
>    assertion that every manifest class executed, and the JAR hashed **after** the graph. The suites
>    are evidence about the graph; only 7.5a is evidence about the artifact.
> 2. **7.5a permitted proving the host JAR while Azure serves an unproved image.** "The staged JAR
>    (or the built candidate image)" are not equivalent proof strengths — `java -jar` exercises
>    neither the Mariner runtime, the entrypoint, the container filesystem, nor the object Container
>    Apps pulls. It now pulls and runs the **exact ACR `repository@sha256:…`**, which closes both
>    remaining joins in one step.
> 3. **Wave P's gate never tested its own new path.** P.2 proved only that *unselected* apps are
>    untouched, so a workflow that ignored the digest, rebuilt the selected service, or updated the
>    wrong repository passed the whole gate. **Wave P splits into P-A (service selection) and P-B
>    (digest deployment)**, ordered, each with its own executable gate and abort; the release lane is
>    blocked on both. P-B.2 makes the trust boundary fail closed on ambiguous selection, tag
>    references, foreign repositories and unresolvable manifests.
> 4. **`git diff --name-only` cannot enforce forbidden symbols** — it contains no source text, so a
>    prohibited Redis or freshness change inside an allowed file is invisible. GC.5 now has a path
>    guard and a separate content/AST guard.
> 5. **"A matching ignore rule" was dangerous** in the paragraph about `.dockerignore`. The staging
>    directory belongs in `.gitignore` and must **not** be in `.dockerignore` — the precedent is
>    exact: `.gitignore:115` lists `.slim-it-artifacts/` and `.dockerignore` deliberately omits it. A
>    check now asserts the staged JAR is not excluded.
>
> **Revision 4 — 2026-08-16.** Incorporates the third tasks review (checkpoint entry [39]). The AOT
> question from entry [38] is closed — a real Gradle dry-run confirms `bootJar` already depends on
> `processAot`, `compileAotJava`, `processAotResources` and `aotClasses`, so copying the completed JAR
> retains them. Three P1s and two P2s remained, all in artifact handling.
>
> 1. **The Gradle selectors do not test the fat JAR.** `build.gradle:122-123` wires `integrationTest`
>    to `sourceSets.test.runtimeClasspath`, and `test` uses the same model; `--tests` selects test
>    classes and never substitutes the JAR for the main classpath. Calling them "tests against the
>    JAR" was simply false. Replaced with one immutable checkout and one Gradle task graph producing
>    tests and `bootJar` from the same main-class outputs, **plus** a real black-box run (7.5a) against
>    the packaged artifact. Every suite now names its task as well as its selector, because an `*IT`
>    selector applied to `test` executes nothing silently — and zero-test selections now fail.
> 2. **The proved image is not the image Azure deploys.** `deploy-azure.yml:145` rebuilds with
>    `--no-cache -f Dockerfile.azure` and `:161` updates by `${github.sha}` tag, so the serving proof
>    would describe a fresh rebuild — the original defect one step later. New **P.1a** adds a
>    prebuilt-digest input and skip-build branch; 7.9 deploys the exact **ACR manifest digest**, which
>    is not the same identifier as a local image ID.
> 3. **`.dockerignore` excludes the artifact the plan told Docker to copy.** `**/build/` is excluded
>    with a comment saying it exists to stop exactly that. Now staged to `.candidate-artifacts/` via a
>    `prepareCandidateArtifact` task modelled on the existing `prepareSlimItArtifact`
>    (`build.gradle:138`), with pre- and post-copy SHA assertions, and built on **`Dockerfile.azure`'s
>    Mariner runtime** rather than the AWS/Lambda base — a candidate proved on a different runtime
>    proves the wrong image.
> 4. **GC.5 was the wrong authority.** A portfolio-service JUnit run cannot make a monorepo-wide
>    changed-path claim or define its comparison base. It is now a CI diff over
>    `<B1-base>..<cut-C>`, pinned to the B1 base rather than the last cut, classified as
>    source-governance evidence beside the candidate bundle.
> 5. **The banned alternate-contract phrase was still in live tasks 6.1 and 6.3.** Removed; only the
>    exact `409 portfolio_version_conflict` envelope names that outcome. It survives solely in this
>    revision history.
>
> **Revision 3 — 2026-08-16.** Incorporates the second tasks review (checkpoint entry [37]). Four
> P1s and both P2s accepted; two of the P1s were introduced by Revision 2's own remediation.
>
> 1. **GC.4 forbade responses the frozen contract requires.** "No path other than the portfolio read
>    returns a Portfolio_Version" would have failed correct code: composition success returns the
>    complete `PortfolioResponse`, `409` carries the current version, and the seed reuses that
>    envelope. The guard is now about **route shape** — no dedicated version-only mapping. The
>    stale-summary class inside an executable assertion, where it fails working code rather than
>    merely misdescribing it.
> 2. **The candidate proof named no tested binary.** 7.4 offered a choice of mechanisms rather than
>    one, and 7.5 listed implementation task numbers rather than suites. The repository permits
>    exactly the gap: `Dockerfile:66` runs `bootJar` and never tests, and `ci-verification.yml` tests
>    source at 53/99 then rebuilds independently at `docker compose build`. One mechanism is now
>    chosen — build one JAR, test that JAR, `COPY` it into a `Dockerfile.candidate` without
>    recompiling, attest `JAR_SHA → IMAGE_DIGEST → commit` — and the manifest names runnable suites
>    with their selectors.
> 3. **The two lanes had no merge cut points.** CI builds the whole source tree and an image tag is a
>    commit boundary, not a selection of task numbers, so parallel work would contaminate earlier
>    artifacts. A new **Artifact Manifest** gives every release its cut and its must-not-contain set.
>    Wave 4 may be *developed* from the start but may not *merge* before cut-B2 — the distinction
>    Revision 2 asserted without enforcing.
> 4. **Task 6.3 restored the banned "typed conflict" wording**, which the frozen requirement
>    prohibits precisely because it led earlier revisions to invent a second internal contract. It now
>    uses Requirement 7's exact envelope, as 6.1 already did six lines above.
>
> Both P2s: the seven non-goals were wrongly declared as gaps — they are negative constraints
> implementation can violate, so treating them as absence of work let the equality guard ignore the
> scope creep they prohibit. 10.5 and 10.7 now have direct carriers; the rest are asserted by a new
> **GC.5 scope guard**. Coverage rises to **173/184**, with only the eleven pure-rationale criteria
> declared. Four composition behaviours covered solely by 4.1's catch-all citation gain explicit
> cases (4.20a), and aggregation is extended to semantic `400`s (4.20b).
>
> **Revision 2 — 2026-08-16.** Incorporates the tasks review (checkpoint entry [35]). Five blockers,
> all accepted.
>
> 1. **Coverage was 0/184.** Name-based references gave design traceability and no requirements
>    traceability. `_Requirements:` trailers are added alongside them — the requirements are frozen,
>    so the renumbering hazard that motivated name-only design references no longer applies here.
> 2. **No task introduced `CompositionController`.** It is now an explicit Wave 7 pre-build task, not
>    Wave 4: Wave 4's code ships inside the intermediate artifacts, and the generic
>    `/api/portfolio/**` route would have made the controller reachable before R-C's gate.
> 3. **The R-C candidate proof was narrower than the digest it proves.** Every design property is now
>    classified; `P10` (the PR #97 price regression, carried by the seed rewrite) and a new `P11i`
>    test join the candidate manifest.
> 4. **Production prerequisites blocked implementation.** Requirement 9 makes Spec A steady state a
>    production activation gate, *not* a development dependency; Revision 1's single chain
>    contradicted it. The graph is now two lanes.
> 5. **Intermediate releases had no abort actions.** Every release boundary names its go condition
>    *and* its exact abort, respecting the floor at that phase.
>
> Global Constraints are kept but made executable, per the review: each is a task-level assertion
> rather than a repeated sentence. My Revision 1 claim that "a prohibition cannot drift" was wrong —
> any duplicated normative sentence can go stale, which is what the assertions prevent.
>
> **Revision 1 — 2026-08-16.** First plan, written against `design.md` Revision 8
> (`81a36be00d4386ae4d68c1a98c6d840831e4bbd6`) and `requirements.md` Revision 6 (frozen,
> `cbba0b38741bf2358f6605ca21f5fa8912f2e2b1`).

## Overview

Nine waves. Two lanes: **implementation** (code and tests, gated only on code dependencies) and
**release** (production transitions, gated on operational evidence). Revision 1 collapsed them into
one chain, which serialised the largest implementation wave behind two operational events and
contradicted the frozen requirement that Spec A's steady state is an activation gate rather than a
development dependency.

The release ordering encodes two constraints that took nine design revisions to state:

1. **Evidence must describe the artifact that serves.** Each capability gate splits into a candidate
   proof bound to an immutable digest and a serving proof collected after rollout. A task that
   "verifies" a service and then rebuilds it has proved nothing.
2. **No release may invalidate its own evidence.** Hence the asset route ships with the gateway, the
   seed switch is its own release, and Wave P exists.

**B1's migration is V20.** Spec A owns V17–V19 in the same directory; two migrations numbered 17 do
not merge badly — Flyway refuses to start.

The design is frozen at Revision 8. Where a task and the design disagree, **the design is
normative**; raise it rather than resolving it in code.

Stack: **Java 21 / Spring Boot 4.1**, `hibernate-core 7.4.1.Final`,
`tools.jackson.core:jackson-databind 3.1.4`, JUnit 5 + Testcontainers (Postgres), Playwright, GitHub
Actions.

## Global Constraints

Four prohibitions, each carried by an executable assertion rather than by restatement alone. Revision
1 argued a prohibition cannot drift; that is false — any duplicated normative sentence can. The
assertions are what make these durable.

- [ ] **GC.1 No `OPTIMISTIC_FORCE_INCREMENT`.** One explicit parent `UPDATE … SET version = version +
  1, updated_at = GREATEST(?, updated_at + INTERVAL '1 microsecond') WHERE id = ? AND version = ?`,
  exactly one affected row, before any child DML. **Assertion:** an architecture/source check fails
  the build if the token appears in `portfolio-service`. Stacking it with a dirtying flush
  double-increments on this Hibernate version.
  _Requirements: 5.5, 5.6_
- [ ] **GC.2 Spec A's frozen body is unmodified.** **Assertion:** a snapshot test pins the existing
  single-write 422 body to `{"error": "unsupported_asset", "ticker": …, "catalogVersion": …}`
  byte-for-byte.
  _Requirements: 7.4, 7.5_
- [ ] **GC.3 The seed target stays server-fixed.** **Assertion:** a test asserts the seed request DTO
  and controller expose no caller-supplied target, so the endpoint cannot be pointed at another user.
  _Requirements: 8.39_
- [ ] **GC.4 No dedicated version-only route.** The prohibition is on **route shape**, not on
  response occurrence. **Assertion:** a route test asserts no mapping exists whose sole purpose is to
  return a Portfolio_Version — e.g. `GET /api/portfolio/version`. Responses that carry the version as
  part of a larger contract are **required** and must remain allowed: a successful composition
  returns the complete `PortfolioResponse`, a `409 portfolio_version_conflict` carries the current
  version, and the seed endpoint reuses that same envelope. Revision 2's wording — "no path other
  than the portfolio read returns a Portfolio_Version" — would have failed correct code.
  _Requirements: 5.11, 5.12, 7.2_

- [ ] **GC.5 Scope guard — source governance, not a JUnit suite.** Non-goals are **negative
  constraints implementation can violate**, not absence of work; treating them as declared gaps gave
  the equality guard permission to ignore exactly the scope creep they prohibit.

  **Assertion — two checks, because one cannot do both jobs.** Revision 4 named
  `git diff --name-only` and then asked it to enforce forbidden *symbols*; name-only output contains
  no source text, so a prohibited valuation, freshness or Redis change inside an otherwise-allowed
  file is invisible to it.

  - **Path guard:** `git diff --name-only <B1-base>..<cut-C>` with an explicit allowlist (test
    fixtures, workflow files) and forbidden production paths — no production frontend change, no
    `ReadOnlyEnforcementFilter` modification, no FX/valuation/refresh-pipeline files.
  - **Content guard:** a content or AST check over the full diff for symbol-level prohibitions — no
    presence/Redis mechanism, no per-holding freshness field — which can appear inside files the path
    guard permits.

  Both outputs are stored as source-governance evidence.

  **The base is the B1 base commit, pinned — not cut-B3.** Comparing only the last two cuts would
  miss scope creep merged into an earlier artifact.

  Classified as **source-governance evidence** stored beside the candidate bundle, not as a binary
  suite: a portfolio-service JUnit run against a JAR is the wrong authority for a monorepo-wide
  changed-path claim, and it cannot define the comparison base. Revision 3 listed it as
  `--tests '*ScopeGuardTest'`, which was that mistake.
  _Requirements: 10.1, 10.2, 10.3, 10.4, 10.6_

Two further non-goals have direct carriers rather than needing the guard: criterion 10.5 on task 7.1,
whose endpoint deliberately has no portfolio identifier or multi-portfolio selector, and criterion
10.7 on task 4.2, which explicitly refuses trade-ledger inference. They are cited, not declared —
written unbolded here because the guard parses bold numerals in this section as gap declarations.

**Declared intentional gaps — pure-rationale criteria only.** Each states *why* an adjacent
behavioural criterion is worded as it is and carries no separately implementable behaviour:
**1.15**, **1.20**, **5.3**, **5.18**, **6.19**, **7.14**, **7.19**, **8.15**, **9.4**, **9.5**,
**9.6**. Eleven, down from eighteen: the seven non-goals moved to GC.5, 7.1 and 4.2.

Seven further rationale criteria were listed here in a first pass and then found to be **cited** by a
task after all — 1.18, 6.31, 8.19, 8.26, 8.31, 8.34, 8.36. They are removed from this list rather
than from their tasks: a stale declaration is what lets a criterion later lose its only citation and
still pass the guard.

---

## Wave P — Deployment prerequisites · *release lane* · **two ordered PRs**

Split into **P-A** then **P-B**, per the review. The allowlist is safe and independently useful; the
digest path depends on it and has a different failure surface — it is a privileged deployment mode.
If P-B fails review or is rolled back, P-A remains valid. One combined PR buys no atomicity, because
the release lane cannot open until both gates are green regardless.

### P-A — service selection

- [ ] **P-A.1 Add a service allowlist to `deploy-azure.yml`.** An unselected service receives **no
  `az containerapp update` at all** — not a re-deploy at its existing digest, which can still create
  or mutate revision state. Full-deploy stays the default for `workflow_call` and ordinary dispatch.
  _Requirements: 1.17, 1.19, 1.21_
- [ ] **P-A.2 Prove non-interference.** For a filtered run naming only `portfolio-service`, assert
  every **unselected** app's revision name, image digest, and traffic weight are byte-identical before
  and after. Store the before/after capture.
  _Requirements: 1.17, 1.24_
- [ ] **P-A.3 Prove the default path is unchanged.** An ordinary dispatch with no allowlist still
  deploys all four services exactly as today.
  _Requirements: 1.21_
- [ ] **P-A.4 STOP/GO — P-A.**
  **Go:** P-A.2 and P-A.3 green.
  **Abort:** revert the allowlist; the release lane stays closed and implementation is unaffected.
  _Requirements: 1.21, 1.22, 1.23_

### P-B — digest deployment (based on P-A)

- [ ] **P-B.1 Add a prebuilt-digest deploy path.** `deploy-azure.yml` currently runs
  `docker build --no-cache --pull -f <service>/Dockerfile.azure` (line 145) and then
  `az containerapp update --image …:${{ github.sha }}` (line 161) — it **rebuilds independently and
  deploys by tag**. Without this, the serving proof would describe a fresh rebuild rather than the
  attested candidate. Add an input accepting `repository@sha256:…` and a skip-build branch updating
  the Container App to **that exact manifest digest**, without building, pushing or retagging.
  _Requirements: 9.7_
- [ ] **P-B.2 Fail closed at the trust boundary.** The mode is privileged, so every ambiguity is an
  error rather than a default. Reject before any update when:
  - the selection is not **exactly one** service (a typed per-service map would be a deliberate
    design, not an inference from a scalar input);
  - the ACR repository does not equal the selected service;
  - the reference is a tag rather than immutable `sha256:` syntax;
  - the manifest does not resolve in the expected ACR; or
  - a foreign registry or repository is named.
  Revision 4 specified one scalar digest alongside an allowlist and said nothing about zero, multiple
  or mismatched selections.
  _Requirements: 9.7_
- [ ] **P-B.3 Prove the digest path actually works.** P-A.2 proves only that *unselected* apps are
  untouched — a workflow that ignores the digest, rebuilds the selected service, or updates the wrong
  repository passes it. Assert: no build or push step executed, and the **selected** Container App
  resolves to the exact requested digest.
  _Requirements: 9.7_
- [ ] **P-B.4 Prove each rejection case fails before any update**, and that the default full-deploy
  path still works with the digest input absent.
  _Requirements: 9.7, 1.21_
- [ ] **P-B.5 STOP/GO — P-B.**
  **Go:** P-B.3 and P-B.4 green.
  **Abort:** revert P-B only. P-A survives; the release lane stays closed until a digest path exists,
  or the candidate/serving model is re-derived around the fallback activation control.
  _Requirements: 9.7, 1.21_

**Both P-A.4 and P-B.5 are hard predecessors of the release lane.** Implementation is unaffected by
either.

---

## Wave 0 — Fixture identity migration · *implementation lane*

Production-neutral. It precedes Wave 1 because Artifact 0 removes the endpoints these fixtures use.

- [ ] **0.1 Move `helpers/api.ts` to the E2E identity.**
  _Requirements: 8.3, 8.7_
- [ ] **0.2 Move `helpers/browser-auth.ts` to the E2E identity.** The second, independent identity
  path — `global.setup.ts` and `golden-path.spec.ts` install the browser session immediately before
  the API helper runs. Migrating only the API helper yields a green suite proving nothing: API
  assertions pass against the E2E portfolio while the page renders dev's empty one.
  _Requirements: 8.3, 8.7_
- [ ] **0.3 Convert `ensurePortfolioWithHoldings` to read-and-assert.** It creates a portfolio via
  `POST /api/portfolio` and adds holdings via the versionless `POST` today. It must assert the
  Golden-State setup and **fail hard** when seeding was skipped, never repair silently.
  _Requirements: 8.3, 8.7, 8.13_
- [ ] **0.4 Update ticker expectations to canonical symbols.** `golden-path.spec.ts` asserts `BTC`
  twice; after Spec A the Golden-State set carries `BTC-USD`. Update the header comment, which still
  names the V3 seed as the fixture source.
  _Requirements: 6.7_
- [ ] **0.5 Wire E2E credentials into `ci-verification.yml`**, which supplies `INTERNAL_API_KEY` and
  neither credential.
  _Requirements: 8.3_
- [ ] **0.6 Wire `frontend-e2e-integration.yml`** with the internal key and E2E credentials. It has
  neither and still runs the affected suites, so leaving it unwired leaves a known-red manual
  workflow.
  _Requirements: 8.3_
- [ ] **0.7 G0b evidence.** `golden-path` and `dashboard-data` pass against a **fresh disposable
  database** in one hermetic `ci-verification.yml` run, on the migrated identity. Requires Spec A's
  *implementation*, not its production cutover.
  _Requirements: 8.3, 8.7_

## Wave 1 — Legacy writer retirement (Artifact 0 → R-0)

- [ ] **1.1 Retire `POST /api/portfolio`.** Pin the response — normally `405` on the surviving
  collection route. A unique-constraint violation must never surface as the public error.
  _Requirements: 8.5, 8.6, 8.8, 1.13_
- [ ] **1.2 Retire the versionless `POST /api/portfolio/{portfolioId}/holdings`.**
  _Requirements: 8.1, 8.2, 8.4_
- [ ] **1.3 Quantity_Domain on any interval either path stays reachable.** If both retire together
  this is vacuous — state that explicitly rather than skipping the check.
  _Requirements: 3.3_
- [ ] **1.4 G0a evidence.** No traffic-serving portfolio digest exposes either route: revision →
  digest → traffic capture.
  _Requirements: 8.9, 1.25_
- [ ] **1.5 STOP/GO — R-0.**
  **Go:** G0a and G0b green.
  **Abort:** redeploy the prior portfolio digest, restoring both routes. Safe at this phase — no
  constraint exists yet, so a restored creator cannot produce a raw database error.
  _Requirements: 8.9, 1.25_

## Wave 2 — Gateway provisioning + asset route (Artifact 1 → R-A)

- [ ] **2.1 Provisioning insert in `SignupService`**, inside its existing `TransactionTemplate` after
  `insertCredential`. Bind `userId.toString()` explicitly — the gateway generates a `UUID` and
  `portfolios.user_id` is `VARCHAR(255)`. Name only columns present in both schemas: `INSERT INTO
  portfolios (id, user_id)`, letting both timestamps and `version` default. Failure rolls back
  signup rather than producing a user without a portfolio.
  _Requirements: 1.5, 1.6, 1.7, 5.16_
- [ ] **2.2 G1 candidate proof — dual schema, V19 → V20.** The insert runs against a database at V19
  and one at V20, exercising the `toString()` binding. A run from today's V16 or an unspecified
  baseline does not satisfy this.
  _Requirements: 1.5, 1.17_
- [ ] **2.3 Add the `/api/assets/**` gateway route.** Ships here, not with the composition endpoint,
  so R-C cannot invalidate G2.
  _Requirements: 2.8, 9.3_
- [ ] **2.4 STOP/GO — G1 before deploy.**
  **Go:** 2.2 green.
  **Abort:** switch to the signup-quiescence path, re-derive the remaining release lane, and do not
  proceed to Wave 3.
  _Requirements: 1.21, 1.22, 1.23_
- [ ] **2.5 G2 serving proof.** Every serving gateway digest provisions at signup: revision → digest,
  traffic, controlled probe.
  _Requirements: 1.16, 1.19, 1.24_
- [ ] **2.6 STOP/GO — R-A.**
  **Go:** 2.5 green.
  **Abort:** redeploy the prior gateway digest and **do not start Wave 3** — the backfill must not run
  while a non-provisioning signup writer can receive traffic.
  _Requirements: 1.16, 1.18, 1.25_

## Wave 3 — Schema (Artifact 2 → R-B)

- [ ] **3.1 Write `V20`.** In file order: add `version BIGINT NOT NULL DEFAULT 0`; add `updated_at
  TIMESTAMP NOT NULL DEFAULT now()`; backfill with `u.id::text` casts on **both** the `INSERT` and the
  `NOT EXISTS` correlation; `ALTER TABLE portfolios ADD CONSTRAINT uq_portfolios_user_id UNIQUE
  (user_id)` as a **named table constraint**; drop the `quantity` default; add
  `chk_asset_holdings_quantity_positive`.
  _Requirements: 1.1, 1.2, 1.3, 1.8, 3.5, 3.6, 3.7, 5.1, 5.14_
- [ ] **3.2 Prove backfill idempotency** under Flyway re-execution, and prove the `NOT EXISTS`
  correlation matches. A silent type mismatch treats every user as unprovisioned and inserts
  duplicates on re-run.
  _Requirements: 1.4_
- [ ] **3.3 Migration fails rather than clamps** if a violating quantity exists. The preflight found
  none across 163 holdings, but it is a point-in-time observation and the migration runs later.
  _Requirements: 3.8_
- [ ] **3.4 Add `version` and `updatedAt` to `Portfolio`; set both timestamps from one instant in
  `@PrePersist`.** Two `Instant.now()` calls can differ, making the equal-at-creation semantics false
  at database precision.
  _Requirements: 5.1, 5.14, 5.16_
- [ ] **3.5 STOP/GO — R-B preconditions.**
  **Go:** G0a, G0b and G2 green **before** the migration runs.
  **Abort:** do not run V20. No forward-repair problem exists while the migration has not executed.
  _Requirements: 1.16, 1.25, 8.9_
- [ ] **3.6 G3 evidence.** Relational postcondition after migration: no user has a portfolio count
  other than one. Assert the invariant, never a fixed total — a legitimate signup changes the number,
  and equal totals can mask one missing user against one duplicate.
  _Requirements: 1.14, 1.24_
- [ ] **3.7 STOP/GO — R-B post-migration.**
  **Go:** 3.6 green.
  **Abort — forward repair only.** V20 has committed; Flyway migrations are not reverted. Keep
  traffic safe and repair forward **without crossing below Artifact 0 + Artifact 1**: reverting the
  gateway would resume creating portfolio-less users under a live constraint. If G3 fails, identify
  the offending users and repair in a follow-on migration before Wave 5 begins.
  _Requirements: 1.1, 1.14, 1.16_

## Wave 4 — Contract implementation · *implementation lane, no public exposure*

Buildable and fully testable from the start. It has no release of its own and becomes reachable only
in Waves 5–7.

### 4a — Orchestrator and preparers

- [ ] **4.1 `HoldingReplacementService`** — the single orchestrator, in D2's exact order: version
  precondition → semantic `400` (quantity, then duplicates) → catalog/lifecycle `422` aggregated →
  materialise via the injected `TuplePreparer` against the locked snapshot → compare → single parent
  CAS → refresh → child DML. Atomic: the whole desired state persists or none of it does.
  _Requirements: 5.2, 5.4, 5.5, 5.7, 5.8, 5.9, 5.12, 5.13, 5.15, 5.17, 6.3, 6.4, 6.5, 6.11, 6.12, 6.13, 6.18, 7.16, 7.17, 7.18, 7.20, 7.21, 7.22, 7.23, 8.1_
- [ ] **4.2 `CompositionTuplePreparer`** — expands ticker/quantity, preserving retained cost-basis
  tuples and capturing new ones. Reads **only** the snapshot locked in step 1. No weighted-average
  inference and no transaction history: this is a snapshot editor, not a trade ledger.
  _Requirements: 6.14, 6.15, 6.16, 6.17, 10.7_
- [ ] **4.3 `GoldenStateTuplePreparer`** — supplies its deterministic tuple and **takes the cost-basis
  anchor as an input**. Hardcoding the moving 25-hour value would silently undo Spec A's move of the
  demo path onto its fixed `app.demo.cost-basis-anchor`.
  _Requirements: 8.11, 8.12, 8.14, 8.18_
- [ ] **4.4 Absent-aggregate path.** Reject every non-zero expected version with `409` and virtual
  current version `0` **before** validation or insert; then validate, insert, and arbitrate on the
  named `uq_portfolios_user_id` constraint only. Provisioning and composition commit together or
  neither does.
  _Requirements: 1.9, 1.10, 1.11, 6.20, 6.21, 6.22, 6.23, 6.24, 6.25, 6.26, 6.27, 6.28, 6.29, 6.30, 6.32_
- [ ] **4.5 Catalog and lifecycle validation.** Canonical tickers only, no aliases. Active assets may
  be created, changed, retained or removed; a retained deprecated position may be retained, reduced
  or removed but never introduced or increased.
  _Requirements: 6.6, 6.7, 6.8, 6.9, 6.10_

### 4b — Boundary, DTOs and error envelope

- [ ] **4.6 Quantity_Domain at the application boundary** — required, strictly positive, at most 11
  integer digits and 8 fractional digits, maximum `99999999999.99999999`. The database `CHECK` is a
  backstop, not the specification. Rejection is a typed failure with atomic rollback at the
  Application_Operation layer, not a controller check.
  _Requirements: 3.1, 3.2, 3.4, 3.9_
- [ ] **4.7 Error envelope.** `ContractError` with `error` as the machine-code field. Plural
  `UnsupportedAssetsException` for aggregation; Spec A's singular exception and handler untouched on
  their path. Stable identifiers so B2 can branch without string matching.
  _Requirements: 7.1, 7.2, 7.3, 7.6, 7.7, 7.8, 7.9, 7.10, 7.11, 7.24_
- [ ] **4.8 Envelope boundary.** `HttpMessageNotReadableException` handler for malformed JSON and
  rejected tokens; `MethodArgumentNotValidException` handler for a missing `expectedVersion`. Boxed
  `Long` with `@NotNull`, plus a **property-scoped strict deserializer accepting only an integer
  token** — Jackson 3.1.4 defaults to `TryConvert` with `ACCEPT_FLOAT_AS_INT`, so `7.9` and `"7"`
  would otherwise decode as valid versions. Non-negative domain validated at the same boundary.
  _Requirements: 7.12, 7.13, 7.15_
- [ ] **4.9 Decimal fidelity both directions.** Strict string deserializer on write;
  `toPlainString()` serializer on `HoldingResponse.quantity`, which emits a JSON number today. No
  exponential notation; trailing fractional zeros preserved as stored.
  _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7_
- [ ] **4.10 Add `version` to `PortfolioResponse`.**
  _Requirements: 5.10_
- [ ] **4.11 `GET /api/assets` controller.** Returns the Catalog_Version and the **full**
  Supported_Catalog entry set including deprecated assets — not active-only, since a retained
  deprecated position must render with its metadata. Each entry carries canonical ticker, name,
  aliases, asset class, quote currency and lifecycle status. No prices, no `basePrice`. `ETag` on
  Catalog_Version, `Cache-Control: private, no-cache`, `304` on match, no second client-side
  persistent cache. Authentication required, as on every `/api` route. Served by portfolio-service,
  which already holds the Catalog_Module in memory.
  _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.9, 2.10, 2.11, 2.12, 2.13_

### 4c — Candidate property suites

Named individually so the R-C manifest can enumerate them rather than gesture at a range.

- [ ] **4.12 P1** — four-case matrix (version match/mismatch × desired equal/differs) on **both**
  writers.
  _Requirements: 8.42_
- [ ] **4.13 P2** — child-only change advances the parent version **exactly once**. Assert the numeric
  delta, not "changed": a double increment moves it too.
  _Requirements: 5.4, 5.7_
- [ ] **4.14 P3, P4** — concurrent composition; two concurrent creators with **empty** desired sets,
  the case a pre-write version comparison cannot distinguish.
  _Requirements: 6.26, 6.27, 6.28, 6.29_
- [ ] **4.15 P5, P6** — stale-but-equal reset yields `409`; a lost reset performs no retry.
  _Requirements: 8.35, 8.40, 8.41_
- [ ] **4.16 P7, P11f** — round-trip `0.75000000` byte-identical; no-op equality decided on the
  persisted `NUMERIC(19,8)` representation, since `BigDecimal.equals` reports `0.75` and `0.75000000`
  unequal.
  _Requirements: 4.6, 8.17_
- [ ] **4.17 P8, P11c, P11h** — envelope precedence; every envelope-failure code reachable and
  distinct; float, string, boolean and negative version tokens tested **independently**, sharing the
  one `invalid_version` code.
  _Requirements: 7.12, 7.13, 7.15_
- [ ] **4.18 P9** — a quantity `CHECK` violation surfaces as its own `400`, never as `409`.
  _Requirements: 6.31, 7.10_
- [ ] **4.19 P11a, P11b** — creation binds both timestamps; the no-op path writes nothing and the
  **response** version equals the stored version.
  _Requirements: 5.13, 5.16_
- [ ] **4.20 P11i** — aggregate rejection reports **every** offender deterministically in request
  order, with the first in singular `ticker` and the full list in `tickers`, while Spec A's
  single-write body stays byte-identical. New in Revision 2: this property had no task.
  _Requirements: 7.6, 7.8, 7.20_
- [ ] **4.20a Composition behaviours that 4.1's citation does not exercise.** Four criteria were
  covered only by 4.1's catch-all and had no case stating the behaviour:
  - **request order is semantically irrelevant** — a reordered identical set is a no-op, with version
    and `updated_at` unchanged;
  - **no fixed maximum set size** — a request naming the **full active catalog** succeeds, bounded by
    catalog cardinality rather than a literal;
  - **Catalog_Version is not a write precondition** — a composition succeeds after an irrelevant
    catalog edit moves the version;
  - **the empty desired set is valid** — against an existing aggregate it removes every holding.
  _Requirements: 6.5, 6.11, 6.12, 6.13_
- [ ] **4.20b Aggregate every offender within a status class, not only tickers.** Extend the
  aggregation rule to semantic `400`s where multiple elements can fail — several out-of-domain
  quantities in one request report all of them, not the first.
  _Requirements: 7.20_
- [ ] **4.21 Monotonic `updated_at`** — supply an equal timestamp, then a **regressed** one, and
  assert `new.updated_at > old.updated_at` in both cases.
  _Requirements: 5.15_

## Wave 5 — Version-bearing read (Artifact 2a → R-B2)

- [ ] **5.1 Expose `version` on the authenticated `GET /api/portfolio`** while the old seed `POST`
  still tolerates the extra body field.
  _Requirements: 5.10, 5.11_
- [ ] **5.2 G2a serving proof.** **Every** serving portfolio digest returns the version, **before**
  any caller migration begins. One caller's successful read can otherwise hit the new revision while
  another still reaches an old response with no version.
  _Requirements: 8.32_
- [ ] **5.3 STOP/GO — R-B2.**
  **Go:** 5.2 green.
  **Abort:** redeploy the prior portfolio digest and **do not begin caller migration**. Safe: no
  caller depends on the version yet. Never cross below Artifact 0 + Artifact 1.
  _Requirements: 8.32_
- [ ] **5.4 Migrate all three seed call sites** to log in, read once, and send that exact version:
  `synthetic-monitoring.yml:170`, `global-setup.ts:191`, `api-live-smoke.spec.ts:194`. An Azure
  synthetic run reaches all three; `global-setup.ts` has no login-and-read step today.
  _Requirements: 8.32, 8.33, 8.34_
- [ ] **5.5 Add E2E email/password to `deploy-azure.yml`'s seed step**, which carries only the user id
  and internal key.
  _Requirements: 8.32_
- [ ] **5.6 `409` workflow outcome:** fail the execution once, log the body, **never retry**. Retrying
  against the newer version is the silent overwrite the contract prevents.
  _Requirements: 8.25, 8.35, 8.36, 8.37_
- [ ] **5.7 G5 evidence.** Every call site, in every execution context, sends a version. Zero
  missing-version requests — enumerated per site, not inferred from one green run.
  _Requirements: 8.32, 8.39_

## Wave 6 — Version-required seed (Artifact 2b → R-B3)

- [ ] **6.1 Seed `POST` requires `expectedVersion`** and delegates to `HoldingReplacementService`.
  Target stays compiled-in. Failure returns Requirement 7's `409` envelope with
  `portfolio_version_conflict` and the current Portfolio_Version.
  _Requirements: 8.14, 8.16, 8.20, 8.21, 8.22, 8.25, 8.37, 8.38, 8.39_
- [ ] **6.2 Remove `PortfolioSeedService.seed()`'s `deleteAll` + `flush` opening.**
  _Requirements: 8.29_
- [ ] **6.3 Collision arbitration** — symmetric compare-and-set: exactly one transition commits; a
  losing user edit gets `409` rather than `404`; a losing reset returns **Requirement 7's exact
  envelope — `409` with `portfolio_version_conflict` and the current Portfolio_Version** — and does
  not retry. No write-maintenance gate. Only the Requirement 7 envelope names this outcome; no
  alternate internal contract is introduced.
  _Requirements: 8.23, 8.24, 8.26, 8.27, 8.28, 8.33, 8.40_
- [ ] **6.4 Rewrite `PortfolioSeedServiceIT`** for identity preservation. Replace
  `EXPECTED_HOLDINGS = 160` with **active-catalog cardinality** — a literal would reintroduce the
  fixed-count defect Spec A removed. **Retain Spec A's full-table byte-identity price regression,
  sentinel rows included** (`P10`): this edits the exact writer from the PR #97 incident.
  _Requirements: 8.13, 8.19, 8.30, 8.31, 3.4_
- [ ] **6.5 STOP/GO — G5 before deploy.**
  **Go:** 5.7 green.
  **Abort:** do not deploy the version-required endpoint; unmigrated callers would fail on the first
  run.
  _Requirements: 8.32, 8.39_
- [ ] **6.6 G2b serving proof.** Every serving digest requires the version and delegates; proved by a
  controlled seed showing identity preservation, the expected version outcome, and the price
  regression.
  _Requirements: 8.14, 8.16, 8.30_
- [ ] **6.7 STOP/GO — R-B3.**
  **Go:** 6.6 green.
  **Abort:** redeploy the R-B2 digest, restoring the version-tolerant seed, and do not begin Wave 7.
  The floor is Artifact 0 + Artifact 1 until R-C activates; it rises to R-B3 afterwards.
  _Requirements: 8.4, 8.28_

## Wave 7 — Activation (Artifact 3 → R-C)

Portfolio-service only; the asset route shipped in Wave 2.

- [ ] **7.1 Introduce `CompositionController`** — `PUT /api/portfolio/holdings`, taking the expected
  version and the desired set, resolving the target from the authenticated principal with **no
  portfolio identifier on the wire**. This is a **Wave 7 pre-build task, deliberately not Wave 4**:
  Wave 4's code ships inside the intermediate artifacts, and the generic `/api/portfolio/**` route
  would make a controller placed there user-reachable before R-C's gate. No multi-portfolio selector
  is introduced.
  _Requirements: 1.12, 6.1, 6.2, 9.1, 10.5_
- [ ] **7.2 HTTP contract tests for the public endpoint** — request shape, `200`/`201` statuses,
  response body, and every error envelope. These exercise the endpoint, not just the service
  primitive.
  _Requirements: 6.1, 6.2, 7.1, 7.10, 7.11, 7.12, 7.23_
- [ ] **7.3 Build the R-C portfolio image once; capture its immutable digest.** Everything below binds
  to this digest.
  _Requirements: 9.7_
- [ ] **7.4 Bind the candidate run to that exact digest — one mechanism, chosen.** Revision 2 offered
  "run against the image **or** emit an attestation", which is a choice rather than a procedure. The
  repository currently permits precisely the gap this closes: `portfolio-service/Dockerfile:66` runs
  `:portfolio-service:bootJar` only — never tests — and `ci-verification.yml` tests source at lines 53
  and 99, then independently rebuilds the image with `docker compose build`. Green tests therefore
  describe a different compiled artifact from the one that serves.

  **Chosen: one verification graph from one immutable checkout, then package what that graph
  built.** Revision 4 said "test that JAR" here and then said the opposite in 7.5 — correctly, since
  Gradle's `test` and `integrationTest` run against source-set outputs and never substitute the fat
  JAR. The contradiction is removed rather than annotated: the suites are evidence about the **graph**,
  and only the image smoke in 7.5a is evidence about the **packaged artifact**.

  1. Register a `candidateVerification` aggregate task — one named invocation — depending on
     `:portfolio-service:test`, `:portfolio-service:integrationTest` and `:portfolio-service:bootJar`,
     with `bootJar` ordered last. One command runs the whole graph from a clean checkout.
  2. Assert from the JUnit XML reports that **every class in the 7.5 manifest executed**, and that no
     task selected zero tests. Revision 4 stated zero-test failure as an outcome; this is the check
     that produces it.
  3. `sha256sum` the `bootJar` output **after** the graph completes → record as `JAR_SHA`. Hashing
     before the graph would hash an artifact the suites never accompanied.
  4. **Stage the JAR outside `build/`.** The root `.dockerignore` excludes `**/build/` — with a
     comment saying it exists precisely to stop a host JAR entering the builder — so a
     `COPY portfolio-service/build/libs/…` cannot work from the repo context. Reuse the proven
     pattern: a `prepareCandidateArtifact` Copy task, modelled on the existing
     `prepareSlimItArtifact` (`build.gradle:138`), stages it to
     `.candidate-artifacts/portfolio-service.jar`, exactly as `Dockerfile.slim-it` consumes
     `.slim-it-artifacts/`.

     **The staging directory goes in `.gitignore` and must NOT go in `.dockerignore`.** The precedent
     is explicit: `.gitignore:115` lists `.slim-it-artifacts/` and `.dockerignore` deliberately does
     not, because Docker has to see it. Revision 4 said "a matching ignore rule", which is easiest to
     implement in exactly the file whose exclusion caused this blocker. A check asserts the staged JAR
     is **not** excluded by `.dockerignore`.
  5. Assert the staged file's SHA equals `JAR_SHA` **before** the build.
  6. Add `portfolio-service/Dockerfile.candidate`, based on **`Dockerfile.azure`'s Mariner runtime**
     — not the AWS/Lambda Dockerfile — whose builder stage `COPY`s the staged JAR instead of running
     `bootJar`. Runtime parity with production is the point; a candidate proved on a different base
     proves the wrong image.
  7. Assert the extracted `app.jar` SHA inside the built image equals `JAR_SHA` **after** the build.
  8. `docker build` → push once to ACR → record the **registry manifest digest**, not the local image
     ID. Those are different identifiers and only the manifest digest is deployable.
  9. Store the attestation `JAR_SHA → ACR manifest digest → commit SHA` alongside the suite reports.

  The AOT chain is safe under this scheme: a real dry-run confirms `bootJar` already depends on
  `processAot`, `compileAotJava`, `processAotResources` and `aotClasses`, so copying the completed
  JAR retains the AOT outputs. That was the open question in entry [38] and it is closed.

  The attestation is an output of this chain, not a substitute for it.
  _Requirements: 9.7_
- [ ] **7.5 Candidate proof manifest — two proof kinds, not one.** Revision 3 said these suites run
  "against that JAR". **They do not.** `build.gradle:122-123` wires `integrationTest` to
  `sourceSets.test.output.classesDirs` and `sourceSets.test.runtimeClasspath`, and `test` uses the
  same source-set model; `--tests` selects test **classes** and never substitutes the fat JAR for the
  main runtime classpath. Calling them JAR tests was wrong.

  The binding is instead: **one immutable checkout, one Gradle task graph** producing both the test
  results and the `bootJar` from the *same* main-class outputs — plus a genuine black-box run against
  the packaged artifact. Each entry names its Gradle **task**, not just a selector, because an `*IT`
  selector applied to `test` silently executes nothing.

  | suite | gradle task | selector |
  |---|---|---|
  | Legacy route contract (both retirements) | `test` | `--tests '*LegacyWriterRetirementTest'` |
  | Asset discovery contract | `test` | `--tests '*AssetDiscoveryContractTest'` |
  | Composition controller HTTP contract | `test` | `--tests '*CompositionControllerTest'` |
  | Composition service + four-case matrix | `test` | `--tests '*HoldingReplacementServiceTest'` |
  | Error envelope and precedence (P8, P11c, P11h, P11i) | `test` | `--tests '*ErrorContractTest'` |
  | Version read | `test` | `--tests '*PortfolioVersionReadTest'` |
  | Concurrency (P2, P3, P4, P11b) | `integrationTest` | `--tests '*ConcurrentCompositionIT'` |
  | Decimal fidelity and no-op equality (P7, P11f) | `integrationTest` | `--tests '*DecimalFidelityIT'` |
  | Seed delegation, identity, **price regression `P10`** | `integrationTest` | `--tests '*PortfolioSeedServiceIT'` |
  | Migration and repository | `integrationTest` | `--tests '*V20MigrationIT'` |

  **Zero-test selections fail the run.** A selector that matches nothing must be an error, not a
  pass — otherwise a mis-assigned suite reports green by executing nothing.

  GC.5 is deliberately absent from this table; it is source-governance evidence, not a JUnit suite —
  see GC.5.
  _Requirements: 9.7, 8.30_
- [ ] **7.5a Black-box run against the exact ACR manifest digest.** **Pull and run
  `repository@sha256:…`** — the digest recorded in 7.4 step 8 — and run an HTTP contract smoke
  covering startup, the composition endpoint, `GET /api/assets`, and the `409` envelope.

  Not the host JAR, and not a mutable local tag. Revision 4 offered "the staged JAR **or** the built
  candidate image" as equivalents; they are not. `java -jar` on the host exercises neither the Mariner
  runtime, nor the entrypoint, nor the container filesystem, nor the object Container Apps will
  actually pull. It also reintroduced a choice one paragraph after 7.4 says a mechanism was chosen.

  Running the registry digest closes both remaining joins in one step: local image → registry
  manifest, and registry manifest → deployed manifest.
  _Requirements: 9.7, 6.1, 7.1_
- [ ] **7.6 Exhaustive holdings-writer inventory.** Enumerate from the source tree every path that
  mutates `asset_holdings` and show each participates in Portfolio_Version. Store the output with the
  same digest. This is what makes G6 satisfy **P11g-2**; a conjunction of three named paths cannot
  establish a property quantified over all of them.
  _Requirements: 8.1, 8.4, 8.10_
- [ ] **7.7 Record pre-deploy serving evidence:** serving G2, G3 recollected after the latest valid
  G2, G4, and G6 (serving G0a, G2a, G2b).
  _Requirements: 9.1, 9.2, 9.7, 1.14_
- [ ] **7.8 STOP/GO — R-C pre-deploy.**
  **Go:** 7.4–7.7 green. A prohibited rollback is a policy, not evidence.
  **Abort:** do not deploy. The system remains at R-B3, which is a safe steady state.
  _Requirements: 9.1, 9.7_
- [ ] **7.9 Deploy the attested digest; collect the serving proof.** Use P-B.1's prebuilt-digest path
  to update the Container App to the **exact ACR manifest digest** recorded in 7.4 — no rebuild, no
  retag. Then: active revision → that digest, traffic, controlled probe. If the deploy invokes
  `Dockerfile.azure` again, the serving proof describes a different artifact and the chain is broken.
  _Requirements: 9.7_
- [ ] **7.10 STOP/GO — post-deploy.**
  **Go:** 7.9 green.
  **Abort:** roll back to **R-B3** and verify that safe digest is serving again. Never below the
  floor — it would restore a legacy writer under a live constraint.
  _Requirements: 9.1, 9.7, 8.4_
- [ ] **7.11 P11g-1 / P11g-2 evidence.** Transitional floor before activation; Writer_Convergence
  floor after.
  _Requirements: 8.1, 8.4_

## Property classification

Every design property, classified so none is silently absent.

| property | class | carried by |
|---|---|---|
| P1–P9, P11a–P11c, P11f, P11h, P11i | candidate | 4.12–4.21, 7.2 |
| P10 | candidate | 6.4, manifested in 7.5 |
| P11d | serving | 1.4 (G0a) |
| P11e, P11j | serving | 5.7 (G5) |
| P11g-1, P11g-2 | serving | 7.11 |
| P11 | serving | 3.6 (G3), recollected in 7.7 |

## Notes

- **`portfolios.user_id` stays `VARCHAR(255)`** (design O3). The `::text` casts bridge it. Converting
  a live identifier column is unrelated migration risk on a table already gating a production
  cutover; it is a type conversion, not a widening, and it is not deferred because the code is out of
  scope — B1 owns `Portfolio`, its repositories, and the seeder.
- **`ReadOnlyEnforcementFilter` is not modified here.** Its allowlist is path-only; method-plus-path
  matching belongs to B2.

## Artifact Manifest — merge cut points

Two lanes are boxes on a diagram until this exists. CI builds the **complete service source tree**,
and a main-branch image tag is a commit boundary — not a selection of task numbers from that commit.
So parallel implementation must stay *off the source used for an earlier artifact*, or that artifact
silently contains later code.

Three concrete contaminations this prevents: Wave 4 or the V20 entity mapping merging before the R-0
image is built would put schema-dependent code inside an artifact labelled Artifact 0; the controller
merging before R-C would activate through the generic gateway route; and any reachability-sensitive
work merging early would be served without its gate.

**Rule.** Each release names its **merge cut** — the last commit that may be in its source. Work not
listed for an artifact lives on a branch until that artifact's cut has passed. Schema- or
reachability-sensitive work may never merge ahead of its cut.

| release | may contain | must NOT contain | cut |
|---|---|---|---|
| **R-0** (Artifact 0) | Waves 0, 1 | Wave 3 entity mapping, Wave 4, Wave 5 read, Wave 6 seed, Wave 7 controller | cut-0 |
| **R-A** (Artifact 1) | + Wave 2 (gateway, asset route) | Wave 3 entity mapping, Waves 4–7 | cut-A |
| **R-B** (Artifact 2) | + Wave 3 (V20, entity mapping) | Waves 4–7 | cut-B |
| **R-B2** (Artifact 2a) | + Wave 4, Wave 5.1 | Wave 6 seed switch, Wave 7 controller | cut-B2 |
| **R-B3** (Artifact 2b) | + Wave 6 | Wave 7 controller | cut-B3 |
| **R-C** (Artifact 3) | + Wave 7 | — | cut-C |

Wave 4 may be **developed** from the start, per the two-lane graph; it may not **merge** before
cut-B2. That is the distinction Revision 2's lanes asserted without enforcing.

- [ ] **AM.1 Record each cut as a tagged commit** at the moment its artifact is built, so an image
  digest maps to an auditable source boundary rather than to "whatever was on main".
  _Requirements: 9.2, 9.7_
- [ ] **AM.2 Assert artifact composition.** For each release, a check confirms the built source
  contains none of its "must NOT contain" set — the controller mapping and the V20 entity fields are
  the two that matter most, being reachability- and schema-sensitive respectively.
  _Requirements: 9.1, 9.2_

## Task Dependency Graph

Two lanes. Implementation is gated on code; releases are gated on evidence.

```
IMPLEMENTATION LANE                          RELEASE LANE
───────────────────                          ────────────
Spec A implementation                        P-A + P-B green ──────┐
        │                                    Spec A production     │
        ▼                                    steady state ─────────┤
Wave 0 (fixtures) ─────────────────────────────────────▶ Wave 1 (R-0)
        │                                                          │
Wave 4 (contract, unexposed)                                       ▼
   4a orchestrator                                        Wave 2 (R-A)
   4b boundary/DTO                                                 │
   4c property suites                                              ▼
        │                                                 Wave 3 (R-B)
        │                                                          │
        ├──────────────────────────────────────────────▶ Wave 5 (R-B2)
        │                                                          │
        │                                                          ▼
        │                                                 Wave 6 (R-B3)
        │                                                          │
Wave 7.1–7.2 (controller + HTTP tests) ────────────────────────────┤
                                                                   ▼
                                                          Wave 7 (R-C)
```

Wave P (P-A then P-B) and Spec A's **production** steady state block the release lane only. Wave 0 and Wave 4 need
Spec A's *implementation* — for canonical tickers and the catalog module — not its cutover, per the
frozen requirement that the activation gate is not a development dependency.
