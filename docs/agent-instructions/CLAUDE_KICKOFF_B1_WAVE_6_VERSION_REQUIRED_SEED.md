# B1 Wave 6 Version-Required Seed Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans`, if available, to execute this
> kickoff task by task. Claude owns implementation; Codex owns architecture review and the
> governed documentation. Checklist items below track execution, not owning-ledger completion.

**Goal:** Complete B1 Tasks 6.1–6.4 so the internal E2E seed requires the caller's observed
version, preserves portfolio identity, and uses the shared replacement transaction.

**Architecture:** `PortfolioSeedController` decodes a version-only request and keeps its compiled-in
E2E target. `PortfolioSeedService` delegates to `HoldingReplacementService` with
`GoldenStateTuplePreparer`; the existing transaction owns comparison, version arbitration,
and holdings persistence. The startup initializer passes its own eligibility observation into
the same service without creating a versionless bypass.

**Tech Stack:** Java 21, Spring Boot MVC, Jackson 3 (`tools.jackson`), Bean Validation,
JPA/Hibernate, PostgreSQL, JUnit/AssertJ/Mockito, Testcontainers, Gradle.

**Spec:** [B1 requirements](../../.kiro/specs/portfolio-composition-contract/requirements.md)
Requirements 3–5, 7, 8.13–8.41;
[B1 design](../../.kiro/specs/portfolio-composition-contract/design.md) D2–D8, Component 3,
P5/P10/P11b/P11f/P11h/P11j;
[B1 tasks](../../.kiro/specs/portfolio-composition-contract/tasks.md) 6.1–6.4.
Preserve [Spec A design](../../.kiro/specs/supported-asset-integrity/design.md)'s fixed anchor and
[Spec A requirements](../../.kiro/specs/supported-asset-integrity/requirements.md) Requirement 11's
holdings-only/global-price boundary.

## 1. Assignment and handoff status

- **Prepared:** 2026-09-02 by Codex. **Implementer:** Claude. **Complexity:** medium-high;
  transaction races, HTTP decoding, and a shared startup caller make this a single coherent bundle.
  No HTML, CSS, layout, or other frontend work is involved.
- **Source baseline inspected:** `main@48d0aba8468325b91e1bf9b84bd43cbeaacdf74a`, PR #214 merged.
- **G5 decision:** the owner requested “Please do the G5 close out” on 2026-09-02. Codex recorded
  Task 5.7 complete in local commit `d3c8b11122321a9b71799b66f32f9a7b2c54a2e6`.
  The [close-out record](../runbooks/B1_G5_INGRESS_BLOCKER.md) pins successful public Azure run
  [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271),
  all three caller markers, holdings-only seed, 9 passing tests, and reviewed evidence PR #197.
- **Publication authorized (2026-09-02):** after the initial approval-review block, the owner
  explicitly requested “Please publish.” Codex may publish the G5 close-out and this kickoff
  together as a draft documentation PR. The publication permission is resolved; verify the
  published branch/PR state at handoff. A published PR is not a merge to main, and publication
  alone does not authorize deployment or other production operations.
- The user requested this Claude kickoff after G5 close-out. Read the supplied note and close-out
  record now; start the bounded implementation when that conditional handoff is effective.
  Preparing this document does not mean implementation or deployment has happened.
- Assigned worktree:
  `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`.
  Read its `AGENTS.md` and `CLAUDE.md`; verify the worktree before each mutation.
  Preserve unrelated work, branches, worktrees, and stashes.
- Suggested implementation branch: `claude/b1-wave-6-version-required-seed`, from freshly
  fetched `origin/main`. The supplied Codex note may be read directly if it is not on main;
  it is not an instruction to cherry-pick the whole documentation branch.

### Scope and separate gates

Local source/tests, ordinary PR CI, and one draft implementation PR for Codex review are in scope
once the handoff above is effective. Include the minimal startup-caller signature adaptation and
its regressions because it currently calls the exact method being changed.

Keep B1 Tasks 6.5–6.7, Wave 7, G2b/R-B3 serving proof, candidate packaging, and
Writer_Convergence outside this source bundle. G5 being green does not check 6.5 automatically.
No production operations, cloud/secret access, workflow dispatch, schedule restoration,
merge/auto-merge, feature exposure, UI changes, migration, new dependency, or broad CI refactor.
Do not add `CompositionController` or expose public `PUT /api/portfolio/holdings`.
B2 Tasks 5.6/6.3 and the sidebar backlog retain their existing status.

## 2. Global constraints and source reconciliation

- HTTP target remains `E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e"`.
  Body/header/query identities never select a different user. Legacy body `userId` stays ignored.
- The body carries `expectedVersion` only as an effective input. Use boxed `Long`,
  `@NotNull`, `@Valid`, and the existing property-scoped `StrictExpectedVersionDeserializer`.
  Do not relax global Jackson coercion or default missing input to zero.
- Preserve the seed success contract: HTTP `200`, exactly `userId`, `portfolioId`,
  `holdingsInserted`; no `marketPricesUpserted`. `holdingsInserted` remains the resulting
  active-set cardinality even on a no-op, rather than an asserted SQL insert count.
- Conflict is Requirement 7's existing envelope: HTTP `409`,
  `error: "portfolio_version_conflict"`, a message string, and numeric `currentVersion`.
  No second internal error shape, automatic retry, or new expected-version read.
- Existing aggregate + changed full tuple: preserve id/createdAt, advance version exactly once,
  and advance updatedAt. Matching version + identical full tuple: no holdings DML and no
  version/updatedAt change. Stale version: conflict even if the golden tuple already matches.
- Absent aggregate + expected version `0`: create one aggregate and its golden holdings in one
  transaction, externally visible at version `1`. Nonzero expected version conflicts with
  current version `0`; concurrent absent creators arbitrate through the existing named constraint.
- Complete equality covers ticker, storage-canonical quantity, average cost basis, currency,
  source, and cost-basis timestamp. Preserve numeric scale handling and all deterministic formulas.
- Both current callers use `DemoProperties.costBasisAnchor()`, already normalized to milliseconds.
  Preserve that configured instant. Component 3's older moving-25-hour E2E example is corrected
  alongside this kickoff; do not reintroduce `Instant.now().minus(25h)`.
- `PortfolioSeedServiceIT` already uses `registry.active().size()` and exact active ticker sets.
  Preserve that implementation; Task 6.4's historical `EXPECTED_HOLDINGS = 160` wording is not
  a request to create or replace a literal that no longer exists.
- Never write `market_prices` or `market_price_history`. Preserve the full-table, all-column,
  sentinel-inclusive comparison across repeated seeds.
- Keep `desiredHoldings(String)` as the pure API used by the demo initializer/diagnostics;
  preserve its complete deterministic tuple. The startup and diagnostics flags remain off.
- Existing initializer advisory locking remains scoped to its startup coordination. Do not add a
  user-write maintenance lock or give either seed or user edit priority over the shared version CAS.

## 3. First checks and file map

- [ ] Inspect the worktree, fetch main, record the exact base, check for an overlapping PR, and
  verify the supplied G5 decision/evidence. No live G5 replay is required.
- [ ] Compare relevant caller/helper/workflow paths from evidence SHA `f66d7ab6` through the chosen
  base; run the existing inventory guard. If a caller changed, assess G5 applicability with Codex
  before proceeding. Unrelated main changes do not invalidate the proof.
- [ ] Establish focused and full portfolio test baselines using the commands in section 6.
  Record environment failures separately from assertion failures.

```powershell
git rev-parse --show-toplevel
git status --short --branch
git fetch origin main
git rev-parse origin/main
git merge-base --is-ancestor 48d0aba8468325b91e1bf9b84bd43cbeaacdf74a origin/main
gh pr list --state open --json number,title,headRefName
python -B scripts/check-b1-seed-version-callers.py
```

Run `git switch -c claude/b1-wave-6-version-required-seed origin/main` only after the checks
and conditional handoff are satisfied. Use the existing branch if legitimately resuming it.

Paths below are repository-relative.

| Action | File | Responsibility |
|---|---|---|
| Create | `portfolio-service/src/main/java/com/wealth/portfolio/seed/PortfolioSeedRequest.java` | Version-only DTO, strict existing decoder |
| Modify | `portfolio-service/src/main/java/com/wealth/portfolio/seed/PortfolioSeedController.java` | Validate input, preserve fixed target and response shape |
| Modify | `portfolio-service/src/main/java/com/wealth/portfolio/seed/PortfolioSeedService.java` | Require observed version, delegate replacement, remove parent deletion/direct child writer |
| Modify narrowly | `portfolio-service/src/main/java/com/wealth/portfolio/seed/DemoPortfolioInitializer.java` | Carry the eligibility observation into the changed service signature |
| Modify | `portfolio-service/src/test/java/com/wealth/portfolio/seed/PortfolioSeedControllerTest.java` | HTTP decode, identity, exact response and conflict contracts |
| Modify | `portfolio-service/src/test/java/com/wealth/portfolio/seed/PortfolioSeedServiceTest.java` | Exact delegation/precondition, deterministic tuple, no compatibility overload |
| Modify | `portfolio-service/src/test/java/com/wealth/portfolio/seed/PortfolioSeedServiceIT.java` | Real identity/version/no-op/price regression |
| Create | `portfolio-service/src/test/java/com/wealth/portfolio/seed/PortfolioSeedCollisionIT.java` | Real seed-versus-edit and absent-creation races, HTTP conflict mapping |
| Adapt | `portfolio-service/src/test/java/com/wealth/portfolio/seed/DemoPortfolioInitializerTest.java` | Frozen observed version and no retry |
| Adapt/regress | `portfolio-service/src/test/java/com/wealth/portfolio/seed/DemoPortfolioInitializerIT.java` | Same transaction, identity, convergence, account isolation |
| Adapt if signature referenced | `portfolio-service/src/test/java/com/wealth/portfolio/seed/DemoPortfolioInitializerDiagnosticsTest.java` | Diagnostics remain non-mutating |
| Adapt | `portfolio-service/src/test/java/com/wealth/portfolio/PortfolioSummaryAfterSeedIT.java` | Explicit fixture observation before seed |

Reuse `composition/HoldingReplacementService.java`, `GoldenStateTuplePreparer.java`,
`CompositionResult.java`, `StrictExpectedVersionDeserializer.java`, `ContractError.java`,
and `GlobalExceptionHandler.java` under the same main Java package.
Read `demo/DemoResetRequest.java` and `demo/DemoResetService.java` as existing integration examples,
not as files to refactor. Existing composition and demo-reset tests are regression coverage.
Change shared code only for a demonstrated Task 6.1–6.4 defect with a focused failing test; report
that expanded file scope explicitly. Do not redesign the established replacement transaction.

**Interfaces to implement/reuse:**

```java
// New request record; use the same field annotations as DemoResetRequest.
PortfolioSeedRequest(Long expectedVersion)

// Replace seed(String); do not retain a versionless overload.
PortfolioSeedService.SeedResult seed(String userId, long expectedVersion)

// Existing APIs, preserved:
List<PortfolioSeedService.DesiredHolding> desiredHoldings(String userId)
CompositionResult replace(String userId, long expectedVersion,
                          List<RawIntent> intent, TuplePreparer preparer)
new GoldenStateTuplePreparer(registry, userId, demoProperties.costBasisAnchor())
```

The existing full-golden mode uses empty intent with `GoldenStateTuplePreparer`; preparation runs
inside `replace` after its version check. Do not call the preparer, a repository validation loop,
or direct holdings persistence before that boundary. Map `CompositionResult.portfolioId()` and
`holdings().size()` to the preserved `SeedResult`.

## 4. Task 6.1 — version-required HTTP/service boundary

- [ ] Add separate HTTP tests for the table below using the real production controller, configured
  Jackson/validation, and `GlobalExceptionHandler`. Reuse the established
  `CompositionEnvelopeBoundaryTest` / `DemoResetControllerTest` harness patterns.
- [ ] Run the focused controller tests and capture RED for the intended rejection/forwarding
  assertion. A compilation or environment failure alone is not behavioural RED.
- [ ] Add the DTO and thread its decoded version through the controller and service, preserving
  internal-key protection, fixed E2E identity, and the existing HTTP 200 success response.
- [ ] Prove invalid envelopes never call the service or consult stored state. Add the exact-version
  forwarding test, including a genuine integer zero.
- [ ] Run the controller tests GREEN; do not commit an intermediate implementation that still
  deletes the parent or ignores the newly accepted version. Commit the coherent boundary with
  Task 6.2 once delegation and caller adaptation pass.

| Request/case | Required result |
|---|---|
| `{}` or legacy `{"userId":"other"}` without version | `400 missing_version`; zero service calls |
| No body / malformed JSON / top-level null | `400 malformed_request`; zero service calls |
| Explicit `null`, `7.9`, `"7"`, `true`, `-1`, long overflow | Separate tests, each `400 invalid_version`; no coercion |
| `{"expectedVersion":0}` | Forward exactly `0` to the fixed E2E target |
| Valid version plus spoofed body/header/query identity | Still fixed E2E target; legacy body userId ignored |
| Service conflict with known or post-rollback resolved version | Exact Requirement 7 envelope; one invocation, no retry |
| Missing/wrong internal key | Existing authentication rejection, no mutation |

Example regression inside the controller test's configured MockMvc fixture, with
`new InternalApiKeyFilter("test-internal-key")` installed as in `DemoResetControllerTest`.
Use this same isolated test key in the request so authentication succeeds before version decoding:

```java
@Test
void missingVersionNeverReachesSeedService() throws Exception {
    mockMvc.perform(post("/api/internal/portfolio/seed")
                    .header("X-Internal-Api-Key", "test-internal-key")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("missing_version"));
    verifyNoInteractions(seedService);
}
```

## 5. Tasks 6.2–6.4 — transaction, collisions, and preservation

### Task 6.2: delegate the seed and adapt its existing caller

- [ ] Replace deletion/recreation tests with failing delegation and real identity-preservation
  expectations. Assert the original portfolio id and createdAt survive a changed seed.
- [ ] Delegate `seed(userId, expectedVersion)` once to the existing replacement service and
  golden preparer. Remove its parent `deleteAll`/flush, new-parent/direct child persistence path,
  and unused writer dependencies. Preserve pure `desiredHoldings` and cost-basis helpers.
  Do not remove the replacement service's child-delete flush, which enforces existing uniqueness.
- [ ] Retain REQUIRED transaction participation: the initializer's advisory lock and seed DML must
  share the same transaction/connection, with outer rollback undoing all writes.
- [ ] Freeze a primitive version from the initializer's already-observed portfolio at the
  eligibility decision, before calling seed; use zero only for its observed absence.
  Preserve the already-converged branch. No second version read, versionless overload, retry,
  reset-priority lock, or silent catch-and-success on conflict.
- [ ] Adapt all direct Java callers and their tests. Fixture code may observe its own isolated
  state before calling seed; production seed itself must not acquire a replacement precondition.
  Search the whole repository for `seedService.seed`, `PortfolioSeedService`, and old method refs.
- [ ] Prove the initializer's stale captured version reaches the service unchanged and conflicts
  if an edit commits afterward. Preserve default-off and diagnostics-only behaviour.
- [ ] Run focused service/initializer tests GREEN and commit the coherent 6.1/6.2 change.

### Task 6.3: prove symmetric arbitration through the real seed path

- [ ] Create `PortfolioSeedCollisionIT` with `@Tag("integration")`, real PostgreSQL and separate
  committed transactions. Use bounded latches/barriers from `ConcurrentCompositionIT`; no sleeps
  or probabilistic loops as the race oracle.
- [ ] In the changed-tuple present-aggregate case, both writers observe N. Exercise both forced
  outcomes: seed commits first and edit loses; edit commits first and seed loses. Assert exactly
  one committed transition, stable portfolio id, stored version N+1, winner's complete tuple,
  and no partial loser holdings. Both attempted desired tuples must differ from the starting tuple.
- [ ] One contender must invoke the real `PortfolioSeedService.seed`; two bare replacement-service
  calls only repeat Wave 4's proof and cannot prove the new adapter. A test-only spy may wrap its
  supplied preparer with barriers while delegating to the real transaction and persistence.
  Keep all coordination instrumentation under test sources.
- [ ] Prove the losing seed through actual HTTP dispatch to `/api/internal/portfolio/seed`
  with real service/DB, obtaining `409`, correct `error`, message, and committed `currentVersion`.
  For a losing user-edit service operation, exercise the existing HTTP advice using a test-only
  controller/harness; no production Wave 7 route. Neither loser may become `404` or raw SQL/500.
- [ ] Separately reproduce frozen N, user commits N+1 whose full tuple equals golden, then seed
  arrives with N: it must return 409, not no-op success. A post-rollback read to report
  `currentVersion` is allowed; re-reading to retry a write is not.
- [ ] Exercise two absent creators with expected zero: one aggregate at version 1, one winner,
  one 409 after the named uniqueness conflict is resolved following rollback. Do not translate
  unrelated integrity failures into portfolio-version conflicts.
- [ ] Count real seed/replacement attempts independently of the barriers to prove no retry.
  Assert losers leave state untouched. Run the new race suite GREEN and commit the proofs and
  any narrowly necessary fixes.

### Task 6.4: preserve identity, complete no-op semantics, and global prices

- [ ] Rewrite `PortfolioSeedServiceIT.seederEstablishesGoldenStateAndIsIdempotent` so its second
  call uses the previously observed version and expects the same portfolio, rather than the
  old portfolio's absence. Preserve active-catalog cardinality and exact ticker-set assertions.
- [ ] Cover the matrix below in real DB tests, with each fixture independently established.
  A fixed anchor makes a true no-op reproducible; do not change the anchor to force a transition.
- [ ] Preserve `insertSentinelPriceRows`, `snapshotMarketPrices`,
  `snapshotMarketPriceHistory`, and `assertPriceTablesUnchanged` (or equally strong helpers):
  compare every column of every row of both global tables before/after the first and second seed.
  Retain sentinel tickers outside the active catalog and all cost-basis/summary regressions.
- [ ] Re-run demo initializer account-isolation and transaction-lock tests, plus existing B2
  demo-reset integration tests because both paths share the replacement primitive.
- [ ] Run the complete portfolio suites, inspect actual counts, and commit the completed bundle.

| Initial state and request | Persisted outcome |
|---|---|
| No portfolio, expected 0 | One aggregate + full active set at version 1; non-null timestamps |
| No portfolio, expected nonzero | 409/currentVersion 0; no aggregate or holdings created |
| Existing non-golden, matching N | Same id/createdAt; complete golden tuple; version N+1; updatedAt advances once |
| Existing golden, matching N | Same id, version N, updatedAt, and holding rows; zero DML |
| Existing golden, stale N | 409 before equality can produce success; no mutation |
| Quantity scale differs but canonical value matches | No spurious transition |
| Only average basis / currency / source / anchor differs | Separate cases proving full-tuple comparison and exactly one transition |
| Rejected/failed operation | Existing state survives rollback; neither price table changes |
| E2E seed versus demo account | Only the selected internal service target changes; the other account remains byte-identical |

Core identity/no-op assertions to add to the existing integration fixture after capturing a fresh
golden portfolio `before` and its holding rows:

```java
long observedVersion = before.getVersion();
UUID observedId = before.getId();
Instant observedUpdatedAt = before.getUpdatedAt();
SeedResult second = seedService.seed(E2E_USER_ID, observedVersion);
Portfolio after = portfolioRepository.findById(observedId).orElseThrow();
assertThat(second.portfolioId()).isEqualTo(observedId);
assertThat(after.getVersion()).isEqualTo(observedVersion);
assertThat(after.getUpdatedAt()).isEqualTo(observedUpdatedAt);
assertThat(second.holdingsInserted()).isEqualTo(registry.active().size());
```

Also compare the persisted holding rows and full price snapshots; identity/version assertions
alone do not detect delete/reinsert child churn or the PR #97 price regression.

## 6. Verification and review packet

Run from the assigned Claude worktree. Use the Unix wrapper equivalent under that shell.
Docker/Testcontainers and Java 21 are required. Keep external services confined to the isolated
test configuration; do not run startup seeding against a shared environment.

```powershell
.\gradlew.bat :portfolio-service:test --tests "*PortfolioSeed*Test" --tests "*DemoPortfolioInitializer*Test" --tests "*GoldenStateTuplePreparerTest" --no-daemon
.\gradlew.bat :portfolio-service:integrationTest --tests "*PortfolioSeedServiceIT" --tests "*PortfolioSeedCollisionIT" --tests "*DemoPortfolioInitializerIT" --tests "*PortfolioSummaryAfterSeedIT" --tests "*ConcurrentCompositionIT" --tests "*DemoResetIntegrationTest" --no-daemon
.\gradlew.bat :portfolio-service:test :portfolio-service:integrationTest :portfolio-service:bootJar --no-daemon
python -B scripts/check-b1-seed-version-callers.py
python -B scripts/tests/test_check_b1_seed_version_callers.py -v
python -B scripts/tests/test_master_plan_status_propagation.py -v
git diff --check
git status --short
```

Before the new collision class exists, omit only its filter from the baseline command.
Integration tests share `src/test/java` and are selected by the integration tag; a test-task
NO-SOURCE result, zero selected tests, or skips is not evidence. Full-suite runs need no frontend
visual check because this scope has no UI changes. Existing static guards/CI remain unchanged.

- [ ] Record focused RED/GREEN assertions, full unit/integration counts, failures/skips,
  artifact build, and caller inventory results. Retain existing request-capture coverage;
  if caller code changes unexpectedly, stop and reassess G5 rather than weakening its guard.
- [ ] Open one draft implementation PR with exactly one declaration:

```text
Master-plan impact: updated — B1
```

- [ ] Return evidence to Codex for the master-plan and B1 owning-ledger updates in the same PR.
  Give Codex explicit permission to edit only those two governed documents in Claude's worktree.
  If a justified contract correction touches another governed track, agree the scope/declaration
  with Codex first. Do not edit requirements/design to make a failing implementation pass.
- [ ] Tasks 6.1–6.4 stay unchecked while unmerged; report implemented-but-unmerged when warranted.
  Codex reconciles source completion after merge. Tasks 6.5–6.7 remain separate even after CI green.
- [ ] Require final PR-event CI for the actual final head: `docs_only=false`, all required checks
  successful, `ci-required=success`, and the existing Azure image smoke actually executed.
  Preserve the blank/nonblank key probes and replica-token vector; this smoke does not prove
  R-B3 serving or replace the real seed tests.
- [ ] Return base/branch/head, PR URL, changed files, test evidence, HTTP envelope matrix,
  exact-version forwarding, both race outcomes, absent creation, price snapshots/sentinels,
  initializer compatibility, and final CI links/results.
- [ ] Stop at Codex review. No merge, deployment, live seed, R-B3/G2b completion,
  Writer_Convergence claim, B2 gate decision, or public-write exposure is implied.
