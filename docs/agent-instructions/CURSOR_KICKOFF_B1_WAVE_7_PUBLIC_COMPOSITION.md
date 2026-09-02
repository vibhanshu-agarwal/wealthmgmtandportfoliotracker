# B1 Tasks 7.1–7.2 — Public Composition Endpoint Implementation Plan

**OWNER APPROVAL — publication and release:** The owner requested parallel implementation assignment
before Codex begins Task 6.5. Local implementation and tests in Cursor's assigned worktree are in scope.
Publishing this new branch or opening a draft PR requires explicit owner approval under AGENTS.md;
continue the local implementation and return the concrete diff/evidence before requesting it.
Merge, deployment, workflow dispatch, production access, and feature exposure are not authorized.
The controller must remain outside the R-B3 source cut even after a draft PR is approved.

> **For agentic workers:** Use superpowers:executing-plans if available. Cursor owns source and tests;
> Codex owns architecture review and governed status documents. The checklist below tracks execution,
> not completion in the owning ledger.

**Goal:** Implement B1 Tasks 7.1–7.2 on an isolated branch: the public composition HTTP boundary and
tests that exercise the existing replacement operation through that actual controller.

**Architecture:** A thin MVC controller receives the gateway-authenticated user identity, the strict
expected version and complete holding intents. A transaction-scoped response adapter invokes
HoldingReplacementService once with CompositionTuplePreparer and returns the persisted
PortfolioResponse plus the operation's created flag. Existing validation, CAS, rollback, cost basis
and error translation remain the authorities.

**Tech Stack:** Java 21, Spring Boot MVC, Jackson 3 (tools.jackson), Bean Validation, JPA/PostgreSQL,
JUnit/AssertJ/Mockito, MockMvc, Testcontainers, Gradle.

**Spec:** Read the [master plan](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md) first, then
[B1 requirements](../../.kiro/specs/portfolio-composition-contract/requirements.md) 1.12, 3–7, 9.1–9.2,
10.5; [B1 design](../../.kiro/specs/portfolio-composition-contract/design.md) D2/D5/D7, Component 2,
release/artifact boundaries; and [B1 tasks](../../.kiro/specs/portfolio-composition-contract/tasks.md)
7.1–7.2 and the two-lane dependency graph. Preserve Spec A's price-write boundary and existing B2 reset.

## Assignment and parallel isolation

- Prepared 2026-09-03. Owner: Cursor. Reviewer: Codex. Complexity: medium, backend only.
- Verified baseline: main@6a171558a0f802eadd5d7ed5bf28545ca5c91905, after PRs #217 and #215.
  No open PR overlapped this assignment at preparation time.
- Assigned worktree: D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-cursor.
  Read its AGENTS.md and local instructions. Before each mutation verify git rev-parse --show-toplevel.
  Preserve unrelated modifications, branches and stashes; do not repurpose Claude's or Codex's tree.
- Suggested branch: cursor/b1-wave-7-public-composition, based on the exact baseline above.
  If that branch exists, inspect it rather than resetting it. Read this kickoff from Codex's
  worktree if it has not been published; do not cherry-pick the entire documentation branch.
- Scheduling status: assigned/ready for Cursor handoff; no Cursor process has been launched by Codex.
- Why parallel is allowed: Requirement 9.2 and the task dependency graph explicitly place 7.1–7.2
  in the implementation lane, independently of Wave 6 release proof.
- Why merge is held: the generic gateway /api/portfolio/** route would expose this controller when
  its image serves. R-B3 may contain Wave 6, but must NOT contain the Wave 7 controller. No merge
  into main, cherry-pick into the R-B3 cut, automatic deployment, or branch-derived R-B3 image.
  Codex prepares 6.5 against the frozen controller-free baseline. Release gating remains separate
  from source review; green local/PR tests do not release this merge hold.
- Read-only review of this worktree is allowed. Cursor must explicitly authorize Codex to edit
  only the master plan and B1 owning ledger when governed evidence is ready for the same PR.

## Scope and constraints

Implement only 7.1/7.2 and their necessary response projection adapter. Do not implement 7.3 onward,
candidate packaging, gateway routes/filters, B2 Wave 8/9, UI, migrations, new dependencies or new
application flags. Shared replacement/preparer mutation logic stays unchanged. If an actual defect
requires changing it, return the failing evidence to Codex before expanding this bundle.

1. Public path/method: PUT /api/portfolio/holdings. The body has expectedVersion and holdings.
   No portfolioId or userId in body/query/path chooses the owner.
2. Preserve the existing trust boundary: the gateway validates JWT, strips supplied X-User-Id,
   then injects its authenticated subject. Portfolio-service consumes that required header as
   its existing read controller does; it does not acquire a second JWT/security implementation.
   Direct MockMvc header injection is service-boundary evidence, not proof of gateway authentication.
3. Forward expectedVersion verbatim, including legitimate zero. No preflight read, default zero,
   latest-version lookup, retry, force increment, or automatic conflict resolution.
4. Reuse CompositionHoldingsRequest, strict token deserializers, RawIntent, CompositionTuplePreparer,
   HoldingReplacementService, PortfolioResponse and GlobalExceptionHandler.
5. Preserve precedence: malformed/token-invalid envelope -> 400 before service; once decoded,
   version conflict -> 409 before semantic 400 before catalog/lifecycle 422. Do not add controller
   quantity validation that overrides the operation's version precedence.
6. Holdings are the complete desired set; omission removes an existing holding. Empty is valid.
   Canonical active assets and retained-deprecated lifecycle rules remain enforced by the service.
7. Return 200 for existing aggregate, including no-op; 201 only for aggregate creation, including
   empty creation. Use result.created(), never list emptiness or a second existence/version query.
8. Quantity is a plain decimal JSON string in the response; retain 0.75000000. Return persisted
   holding identifiers, id, userId, createdAt, updatedAt and version via PortfolioResponse.
   Never fabricate child IDs or serialize DesiredHoldingState as the public body.
9. The response must describe this operation's snapshot. Build it before its transaction ends.
   Let conflicts escape to the existing advice after rollback; do not resolve them inside a
   failed transaction or turn them into success.
10. Existing no-op/CAS/identity/cost-basis/global-price guarantees remain unchanged. Preserve the
    stable legacy-route retirement, authenticated read/catalog routes, seed contract and demo reset.
11. Keep all production flags and schedules unchanged/off as currently recorded. Use local
    Testcontainers only. Do not run Azure synthetics, live seed, or production smoke.
12. Global-constraint and owning-task checkboxes stay unchanged until Codex reviews the evidence.

## File map and the existing response-shape gap

Create under portfolio-service/src/main/java/com/wealth/portfolio/composition/:
- CompositionController.java — HTTP binding and 200/201 mapping.
- CompositionWriteService.java — a small transaction-scoped boundary adapter, not another writer.

Create under portfolio-service/src/test/java/com/wealth/portfolio/composition/:
- CompositionControllerTest.java — real controller, real decoders/advice, mocked adapter.
- CompositionWriteServiceTest.java — single delegation, projection integrity, rollback propagation.
- CompositionControllerIT.java — real HTTP/controller/adapter/replacement/preparer/Postgres.

Read/reuse:
- composition/CompositionHoldingsRequest.java, RawIntent.java, CompositionResult.java,
  CompositionTuplePreparer.java, HoldingReplacementService.java.
- PortfolioResponse.java and PortfolioService.toPortfolioResponse(Portfolio).
- demo/DemoResetService.java for the existing transaction-scoped projection pattern.
- composition/CompositionEnvelopeBoundaryTest.java, CompositionErrorContractTest.java,
  CompositionErrorEnvelopeTest.java and ConcurrentCompositionIT.java.
- demo/DemoResetIntegrationTest.java for current Spring HTTP/Testcontainers conventions.
- api-gateway JwtAuthenticationFilter.java and its chain tests for the identity trust boundary.

The design's illustrative CompositionResult(response, created, noOp) is not the current Java record:
the actual result contains portfolioId/userId/timestamps/version/DesiredHoldingState list and flags,
but no persisted child IDs. Do not code against a nonexistent result.response() or return invented IDs.

Chosen adapter: after replace returns, while the outer transaction remains open, load its returned
portfolioId through PortfolioRepository and project with the existing public toPortfolioResponse
method, following DemoResetService. This is response hydration, never a replacement precondition or
a retry. Check response id/userId/version/timestamps and full ticker/quantity set against the returned
operation snapshot; compare quantities numerically, preserve stored serializer scale. A disagreement
is an internal consistency failure and must roll back, not silently return a newer body.
The HTTP status still comes exclusively from result.created(). Do not refactor the shared result
record or existing callers to suit this endpoint.

The adapter's explicit interface is:

~~~java
public Outcome replace(String userId, CompositionHoldingsRequest request);
public record Outcome(PortfolioResponse response, boolean created) {}
~~~

It passes a mapped List<RawIntent> and the injected CompositionTuplePreparer to:

~~~java
replacementService.replace(userId, request.expectedVersion(), intents, compositionTuplePreparer);
~~~

## Task A — Establish the real public HTTP boundary

- [ ] Verify assigned worktree, clean base, current instructions and no overlapping branch.
- [ ] Record a baseline of the focused existing controller/envelope tests below.
- [ ] Write CompositionControllerTest against the production CompositionController, not a new
  __test__ probe. First RED must demonstrate the missing production controller/mapping.
- [ ] Define the success and malformed-envelope cases below using the existing Jackson stack/advice.
- [ ] Implement the thin controller and adapter interfaces with the exact mapping and @Valid body.
- [ ] Run tests; capture actual RED/GREEN results, not just a final total.

Representative HTTP contract assertion, with the adapter mocked to return an existing result:

~~~java
mockMvc.perform(put("/api/portfolio/holdings")
        .header("X-User-Id", USER_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"expectedVersion":7,"holdings":[{"ticker":"AAPL","quantity":"0.75000000"}]}
            """))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.userId").value(USER_ID))
    .andExpect(jsonPath("$.holdings[0].quantity").value("0.75000000"));
~~~

Use actual fixtures of Outcome/PortfolioResponse in the test; assert the adapter receives the
same user, exact expected version, ticker order and decimal values. The first creation fixture
returns 201; an existing empty/no-op fixture returns 200.

Required envelope cases, individually named/parameterized with reported case counts:
- missing version -> missing_version; explicit null, fractional/string/boolean/negative/overflow
  version -> invalid_version; absent body, JSON null and malformed JSON -> malformed_request.
- numeric JSON quantity -> quantity_not_string. Missing/null quantity remains semantic validation
  after the version precondition, not a controller-level @NotNull.
- required holdings/ticker envelope failures follow the existing decoder/advice contract.
- every decoder/binding failure has zero adapter calls.
- valid 0, 1, 42 and Long.MAX_VALUE reach the adapter unchanged.
- missing gateway identity fails through the existing missing-header behaviour with no adapter call.
- body/query spoofed identities cannot redirect the authenticated target; a portfolio-ID URL variant
  cannot invoke the replacement path.
- serialize real GlobalExceptionHandler exceptions for every applicable 400/409/422 contract code,
  retaining message/currentVersion/catalogVersion/ticker/tickers fields where specified.

## Task B — Transactional response projection and delegation

- [ ] Write adapter tests for exactly one replace call, the actual CompositionTuplePreparer instance,
  raw intents unchanged, and no pre-operation repository lookup.
- [ ] Assert successful projection has persisted child IDs and exact operation identity/version/
  timestamps/ticker-quantity set. Assert mismatch rejects, not response repair.
- [ ] Assert a conflict causes no projection lookup and propagates unchanged; no retry.
- [ ] Implement the transaction adapter using the existing projection pattern.
- [ ] Verify both created=true and created=false statuses derive from the replacement outcome.
- [ ] Run focused tests and existing seed/demo-reset regressions. Commit only the intended paths.

## Task C — Prove the endpoint over real HTTP and PostgreSQL

Tag CompositionControllerIT with @Tag("integration"), so the existing integrationTest task discovers
it. Use a random HTTP port, real production controller/advice/adapter/replacement/preparer, and the
existing PostgreSQL fixture. Do not mock the operation or use real cloud endpoints.

- [ ] RED/GREEN: existing replacement -> 200, stable parent identity, exactly one version advance,
  full requested set persisted, omitted holding removed, retained cost basis preserved.
- [ ] First creation with version 0 -> 201/version 1; test nonempty AND empty desired sets.
  An existing empty/no-op result is 200, so list emptiness cannot drive creation status.
- [ ] Exact no-op -> 200 with unchanged version/updatedAt and stored decimal string/holding IDs.
- [ ] Stale version -> real 409 envelope with currentVersion and unchanged database, including
  stale-but-equal input and stale plus semantically invalid input.
- [ ] Current-version semantic 400 and catalog/lifecycle 422 leave all portfolio state unchanged;
  unsupported/deprecated/duplicate offender lists are complete and deterministic.
- [ ] Missing aggregate with nonzero version -> 409/currentVersion 0 and no bare aggregate.
- [ ] Two-user target test: user A's valid request cannot mutate user B through body/query identity.
- [ ] Assert the response body matches the operation's persisted snapshot after request completion,
  not just shape/status. Use deliberately different fixture and desired tuples.
- [ ] Retain existing ConcurrentCompositionIT/PortfolioSeedCollisionIT as concurrency proof;
  do not rebuild those harnesses just to increase this task's count.
- [ ] Run the existing gateway identity-chain tests separately. Report service-boundary and
  gateway-boundary evidence distinctly; no gateway source change is part of this bundle.

## Verification commands and review packet

From Cursor's assigned worktree, using the repository-supported JDK and a running local Docker:

~~~powershell
.\gradlew.bat :portfolio-service:test --tests "*Composition*Test" --tests "*PortfolioControllerTest"
.\gradlew.bat :portfolio-service:integrationTest --tests "*CompositionControllerIT"
.\gradlew.bat :portfolio-service:test :portfolio-service:integrationTest :portfolio-service:bootJar
.\gradlew.bat :api-gateway:test --tests "*JwtAuthenticationFilterChainTest"
python -B scripts/check-b1-seed-version-callers.py
python -B scripts/tests/test_check_b1_seed_version_callers.py
python -B scripts/tests/test_master_plan_status_propagation.py
git diff --check
~~~

The new class filter is expected to fail before it exists; baseline only uses already-existing
classes. Full suite figures for the reviewed baseline were 516 unit + 189 integration, with no
failures/skips; those are reference evidence, not this branch's future counts. Read fresh XML and
report tests/failures/errors/skips separately. Distinguish infrastructure failures from assertions.

Return base/branch/head, changed files, RED/GREEN evidence, fresh full counts, build result, guards,
and the real response/identity/error matrix. Keep Tasks 7.1/7.2 unchecked pending Codex review.
State the R-B3 merge hold prominently. If owner approves publication, open one draft implementation
PR with exactly one declaration: Master-plan impact: updated — B1. Supply explicit permission for
Codex to edit only the master plan and B1 ledger in this worktree in that same PR; do not weaken the
declaration to none to bypass governance. CI/source ACCEPT is not release approval.

## Self-review and completion boundary

The bundle covers 7.1's identity/path/status/transaction requirements and 7.2's actual HTTP contract.
It reuses Wave 4 mechanisms and does not claim to complete candidate tests/packaging/serving proof
in Tasks 7.3 onward. Current merged response-shape and gateway-identity conventions are addressed
explicitly above, rather than relying on the illustrative design signature.

No implementation code has been changed by this kickoff. No process is running in Cursor yet.
