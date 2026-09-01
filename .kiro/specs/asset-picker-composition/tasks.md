# Implementation Plan

**Current program status (verified 2026-09-01 against `main@ce6ee32c`):** this task plan and its owning
requirements/design/mockup are tracked. Wave 1 (Tasks 1.1-1.19) and Wave 2 Tasks 2.1-2.5 are merged
source-only through PR #178, entirely mock-backed and disabled by default. Wave 3 presence source
Tasks 3.1–3.6 merged source-only through PR #179 at `main@cc97a209`; Task 3.7 deployment/live proof
remains open (not deployed, not activated, not live-probed). Wave 4 Tasks 4.1–4.4a merged source-only
through PR #180 at `main@63fc058`; they are not deployed, not routed, and not user-visible. Wave 8
Task 8.1 merged source-only through PR #185 at `main@198c878d`; it is not deployed. Task 5.1a
(`InternalApiKeyProvider`) merged source-only through PR #202 at `main@64761dc2`; it is not deployed.
Task 8.2a (`CloudFrontOriginSecretProvider`) merged source-only through PR #203 at `main@addd8049`;
it is not deployed. Task 5.1b (`ReplicaTokenProvider`) is **implemented but unmerged** on
`feat/b2-task-5-1b-replica-token-provider` (not deployed). Task 8.2's open idle-threshold/self-call-timeout
decisions and Tasks 8.3 and later remain not started. Spec A task 8.6
is complete and the backend `assetPriceFreshness` response exists.
Four decisions remain open: idle threshold, manual-reset placement, login self-call timeouts, and
decimal-adapter deployment sequencing. See
[`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`](../../../docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md)
for the living cross-program view.

**Selected priority (2026-09-01):** resume Asset Picker delivery before further CI optimization.
B1 Task 5.7/G5 remains a separate owner gate and still blocks B1 Waves 6–7. Tasks 5.1a
(`InternalApiKeyProvider`) and 8.2a (`CloudFrontOriginSecretProvider`) merged source-only via PRs
#202 and #203 at `main@64761dc2` and `main@addd8049`; neither is deployed. Task 5.1b
(`ReplicaTokenProvider`) is **implemented but unmerged** on `feat/b2-task-5-1b-replica-token-provider`
via [PR #208](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/208) (not
deployed). The Wave 5 Tasks 5.1–5.6 deployable bundle remains gated on Wave 4 Task 4.5; the next
B2 implementation priority after 5.1b merges is owner-selected. Task 5.1b's ledger defined its provider, token formula, operator tool, packaging,
and Azure image-smoke extension as one bounded deliverable shared by Tasks 5.1 and 8.7. Its
implementation preserves the fail-closed docs-only CI contract already live on `main`.

**Wave 1 (Tasks 1.1-1.19) and Wave 2 Tasks 2.1-2.5 — merged source-only (2026-08-29).**
Merged through PR #178 at `main@38e3d95`; the implementation branch was
`claude/b2-wave1-frontend-foundation`, originally based on `origin/main@ed933632`. Commit history:
- `fd42df7a` contract/decimal/query foundation
- `dc7b6db7` guarded modal/draft/browse
- `800af697` mocked review/save/conflict/presence
- `52478dc0` freshness/accessibility/E2E/governance
- `58b8ef32` first review-fix round: frozen open-time save/review baseline (Critical),
  blocked invalid-quantity submission, a decimal-precision enforcement gap at the domain's own
  boundary, an async cache-invalidation race on save success, and a presence per-open regression
- `1f1c5f5` merge of `origin/main` (which had advanced during review) into the branch
- `3889e4d` status-doc reconciliation after that merge
- `0a44d0d` second review-fix round: the first round's cache-invalidation race fix removed the
  timing hazard but a second review pass correctly found it still didn't satisfy requirements.md
  4.2/Task 1.13's literal requirement — replace visible state with the successful save's own
  response body, not a subsequent read of any kind. Now uses `queryClient.setQueryData` built
  directly from the PUT response via `buildPortfolioResponseFromWireHoldings`, proven by a
  regression test whose PUT response deliberately differs from both the pre-save GET and the
  submitted draft.
- `fad5c96` final governance reconciliation; merged through PR #178 at `main@38e3d95`

Entirely mock-backed: no live `/api/assets`,
`PUT /api/portfolio/holdings`, `/api/presence/demo`, or `/api/portfolio/summary` call — MSW in unit
tests, `page.route` in the two new mocked Playwright specs
(`tests/e2e/asset-picker.spec.ts` happy/conflict paths,
`tests/e2e/asset-picker-disabled-by-default.spec.ts` verifying the default build). Both
`NEXT_PUBLIC_ENABLE_ASSET_PICKER` and `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL` default to disabled;
the only build that ever sets the former to `"true"` is
`playwright.asset-picker.mocked.config.ts`'s own local `webServer`, never a workflow or deployment
environment. **This is a source-on-`main` claim only, not a deployment, live-integration, or
production-exposure claim.** Tasks 2.6-2.7 remain open. Wave 3 source merged via PR #179 at
`main@cc97a209` (Tasks 3.1–3.6 source-only; Task 3.7 deploy/live proof open). Wave 4 Tasks
4.1–4.4a merged source-only through PR #180 at `main@63fc058`; Task 4.5 and
Waves 5–10 remain open per their own gates below.

**Review-accounting note (Azure-first consolidation, 2026-08-22):** the long numbered-round
narrative below is retained as provenance only. Future reviews record findings in git/PR history and
update the owning invariant or task; they SHALL NOT append another rolling round paragraph or try to
synchronize cumulative counters across four documents.

**Revision 1 — 2026-08-21/22.** First plan, written against `requirements.md` and `design.md`
Revision 2, frozen and committed at `docs/b2-asset-picker-composition-spec` (`1639565`, amended by
`4bab8db` — pass 22, D5's demo-reset `intent` semantics, see Task 4.2; `1bf5c9d` — pass 23,
decoupling Live Integration from login orchestration, see this document's own Overview;
`2d946cb`/`5e264b3` — pass 24, Test 2's rigor and restoring Wave 8's production-gate requirement,
see Task 4.4 and Wave 10; `eae3dc5` — pass 25, a five-agent internal self-audit, see Tasks 2.2, 5.1,
9.1, and GC.6; `6386c17` — pass 26, correcting premature exposure, GC.6's GET-count assertion,
and Test 2's oracle scope, see GC.6, Task 4.4, Task 5.1, Task 9.7, and Wave 10; `d8b7865` — pass
27, adding Wave 8's live serving proof and refining GC.6's eligibility matrix, see Task 8.9, GC.6,
and Task 4.4; and `6aec5e7` — pass 28, propagating the 8.9 serving-proof gate into the production
exposure condition, fixing 8.9's own dependency boundary, and making its live probe causally safe
against the shared demo identity with unconditional cleanup, see Wave 8's intro and Task 8.9) after
twenty-nine review passes (twenty-six Codex adversarial rounds, three internal parallel-agent
audits). Subsequent amendments are tracked in git; the rolling pass narrative is provenance, not a
current-status mechanism. The spec is architecture-reviewed, not decision-complete: four items
remain genuinely open (idle-reset threshold, manual-reset control placement, login self-call
timeouts, and the decimal-adapter rollout sequencing) and are
carried into the waves below as explicit blockers rather than resolved here — Codex round 1 on this
document found the first draft's header undercounted this list, dropping exactly the two items
(decimal sequencing, `assetPriceFreshness`) where the draft's own task coverage was itself weakest,
now fixed alongside the coverage gaps themselves. Per
direction from the spec owner, review effort now shifts to this document — a first task breakdown is
more likely to surface omissions than further requirements/design churn, mirroring how B1's own
`tasks.md` (`portfolio-composition-contract`) matured through eight review revisions after its
requirements/design were frozen.

Authority is scope-specific, not a single blanket precedence rule: `requirements.md` owns
user-visible product behavior; `design.md` owns component and wire architecture; the master plan
owns cross-spec dependencies and release ordering; this task plan owns implementation and test
mechanics. A lower layer may add detail but may not weaken an upper layer. If two documents make
incompatible claims within the same scope, stop and amend both documents before implementation.

**Change-control guardrails for the remaining work:**

1. Every live-cloud gate has exactly one executable owner, named inputs, deterministic exit
   conditions, unconditional cleanup where it mutates shared state, and a machine-readable evidence
   artifact. A prose-only operator checklist cannot earn Go.
2. A mechanism is normative in one place only. Other documents link to its owner and may retain
   historical rationale, but SHALL NOT restate commands, schemas, task counts, or release sequences.
3. Every new task or changed dependency is propagated in the same edit to its Go gate, Abort gate,
   Overview/DAG edge, master-plan row, and any consumer task. A structural cross-reference check
   fails on a missing propagation edge.
4. Workflow changes require pinned `actionlint`, the named workflow-graph tests, and a real
   current-attempt artifact contract. Text/regex tests supplement YAML validation; they never
   replace it.
5. Azure completion ignores explicitly marked AWS-only gates while AWS is disabled. Shared behavior
   may not be weakened to make Azure pass, and AWS must receive a fresh provider-specific audit
   before reactivation.

**Azure-first release scope (2026-08-22):** Azure is the only production target for this plan's
current completion and exposure gates. AWS-only Task 8.8a and the AWS half of Task 10.1 are deferred
follow-up work and SHALL NOT block Azure build, verification, or exposure. Re-enabling AWS requires
its provider-specific gates to be completed and a fresh serving proof; Azure evidence never stands
in for AWS evidence.

## Overview

B2 adds a picker UI plus a real slice of its own backend (presence tracking, demo-reset
orchestration) on top of B1's composition contract. It introduces **no new durable persistence** —
no new database table, no new Postgres writes beyond what B1's `HoldingReplacementService` already
performs. What it does add: ephemeral Redis presence state, a `jti` JWT claim, a
`ReadOnlyEnforcementFilter` allowlist change, and a new demo-reset path that calls into B1's own
persistence rather than a store of its own.

**Four tracks, not one chain** (matching the master plan's own phase table):

1. **Frontend UI against frozen contracts** — startable now; B1's request/response shapes are fixed
   by its own frozen spec regardless of which B1 release gate is open.
2. **B2-owned backend build** — presence, and demo-reset in two independently-gated stages (manual
   path; login-orchestrated path), per `design.md` D5's pass-21 rollout sequence.
3. **Live integration** — wiring the UI to real B1 endpoints. Blocked on B1 Wave 7
   (`CompositionController`, the public `PUT /api/portfolio/holdings`, gating Task 9.2) **and** B1's
   `GET /api/assets` controller (B1 task 4.11, gating Task 9.1) **actually being part of a merged,
   deployed release — not merely Wave 2's gateway route existing** (round-10 correction: B1's own
   Artifact Manifest states R-A, which ships Wave 2's route, explicitly must NOT contain Waves 4-7;
   the controller only becomes includable starting at R-B2 (+ Wave 4, Wave 5.1), itself gated on
   Wave 3/Spec A's cutover — before that, this route forwards to an endpoint that doesn't exist in
   the deployed artifact at all, the same class of gap this document already corrected once for the
   demo-reset endpoint) — **and** on this document's own Waves 1-6 (round-8 correction: "Wave 1-8"
   wrongly re-coupled this track to Wave 8's login-orchestration — no Wave 9 task calls the login
   self-call path or depends on `updatedAt`, the idle threshold, or the self-call timeouts; Wave 9's
   actual dependency is Waves 1-6 built and runnable, not production-deployed). Wave 8 remains
   unconditional at Wave 10 (production exposure), independent of this track.
4. **Production exposure** — blocked on all of the above plus B1/Spec A's own activation gates and
   the five open product/config/dependency items enumerated in this header. `updatedAt` and
   `assetPriceFreshness` are not among them: Task 8.1 owns `updatedAt`, and Spec A task 8.6
   delivered `assetPriceFreshness`.

Stack: same as the rest of the monorepo — **Java 21 / Spring Boot 4.1** (api-gateway, WebFlux;
portfolio-service, Spring MVC), **Next.js/TypeScript** frontend, Redis (presence only), JUnit 5 +
Testcontainers, Playwright.

**Hard external dependency, tracked but not owned here:** B2's `DemoResetService` (Wave 4) consumes
a five-task B1 Wave 4 cluster — `HoldingReplacementService` (4.1), `GoldenStateTuplePreparer` (4.3),
the error envelope (4.7), decimal fidelity (4.9), and `version` on `PortfolioResponse` (4.10). All
five are **verified on `main@cc97a209`** (focused unit/integration tests green on the Wave 4 base);
Wave 4.1 below records the evidence paths. B2 cannot build against any of them before they land, and
no task below reopens or duplicates them.

## Global Constraints

Executable assertions, not restated prose — each is checked once per relevant task rather than
re-asserted in every task description.

- [ ] **GC.1 The draft always opens fully seeded.** Every held ticker is present and selected in the
  draft from open, never an empty set the user builds up. **Assertion:** a test opens the picker
  against a portfolio with N holdings and asserts the draft contains exactly those N tickers,
  selected, before any user interaction.
  _Requirements: 1.2, 1.2a_
- [ ] **GC.2 Quantity is a string end-to-end, never a parsed number, except at a display boundary.**
  **Assertion:** an architecture/lint check flags any arithmetic operator applied directly to a
  `quantity` field outside an explicitly-named display-value helper; a test asserts the submit
  payload's quantity strings are byte-identical to what the input held, including trailing zeros.
  _Requirements: 8.1, 8.4_
- [ ] **GC.3 The `PUT /api/portfolio/holdings` payload always carries the complete desired set,
  never a diff.** **Assertion:** a test constructs a draft that adds one ticker to an existing
  five-holding portfolio and asserts the submitted payload contains all six, not one.
  _Requirements: 1.2a; design.md D2_
- [ ] **GC.4 No automatic reapply, merge, resubmit, or discard on `409`.** The draft stays visible,
  read-only, until the user's explicit "reload and start over" action or modal close.
  **Assertion:** a test forces a `409`, asserts no second `PUT` fires automatically, and asserts the
  draft's rows are still present in the DOM (not unmounted) until one of the two explicit actions
  fires.
  _Requirements: 4.3, 4.4_
- [ ] **GC.5 Presence never gates, delays, or alters any request.** A Redis error or unavailability
  yields no banner and no request delay. **Assertion:** a test stubs the presence endpoint to error
  and asserts the composition `PUT` still fires with unchanged latency characteristics (no added
  await on the presence call's own failure path).
  _Requirements: 6.4, 6.5, 6.6_
- [ ] **GC.6 A version used in any reset or composition call is the one observed when eligibility/
  intent was decided, never re-read inside the call itself.** Applies to the picker's own save, the
  manual reset trigger, and the login-orchestrated trigger alike, but **the three sites don't share
  one call shape, so the assertion can't either (round-11 correction: a prior draft required
  "exactly one GET per operation" uniformly, which is wrong for two of the three sites)**:
  - **Picker save (Task 1.13) and manual reset (Task 6.2) — zero `GET /api/portfolio` calls during
    the mutation itself, not zero HTTP `GET`s of any kind (round-12 correction: a blanket "zero
    `GET`s" assertion would break on unrelated, legitimate traffic — catalog, price, presence, or
    post-success reconciliation reads — that has nothing to do with the version being submitted).**
    Both capture their version at an earlier point in time (modal-open, and the browser's
    last-observed read, respectively) — the save/reset call is a pure `PUT`, never preceded by its
    own version-observing read. Assertion: capture the earlier version, reset/snapshot the HTTP
    client spy immediately before triggering the save/reset action, assert **zero**
    `GET /api/portfolio` calls occurred before the `PUT` fires (other endpoints may still be
    called), and assert the `PUT`'s own body carries that exact, earlier-captured version.
  - **Login-orchestrated trigger (Task 8.5) — a matrix, not one shape (round-12 correction: an
    earlier draft required "one `GET` then one `POST`" unconditionally, but design.md D5 is explicit
    that the `POST` fires only when the eligibility read succeeds and finds the reset eligible —
    every other outcome fails open with zero `POST`s, per GC.8).**
    - **Eligible, successful read:** exactly one eligibility `GET /api/portfolio` fires, its
      returned version is what the subsequent `POST` carries, and no second `GET` occurs between
      them.
    - **Ineligible, or the read itself fails/times out/returns a malformed shape:** exactly one
      `GET /api/portfolio` is *attempted*, and **zero** `POST`s follow — asserted together with
      GC.8's own fail-open matrix, not as a separate, redundant check.
  - **Post-success reconciliation reads are a separate concern, not covered by this constraint** —
    e.g. the picker's own cache invalidation (Task 1.13) or the Portfolio page's post-save refetch
    happen *after* the mutation already succeeded, not as part of deciding what version to submit.
  A purely behavioral test (asserting the final outcome) is insufficient on its own for any of the
  three, since a race that re-reads and still happens to pass by timing would not be caught that
  way — the call-count/ordering assertion is what catches it regardless of timing.
  _Requirements: 4.1, 7.3, 7.3c; B1 requirements.md 8.33_
- [ ] **GC.7 `DemoResetAuthorizationFilter` matches the exact `(PUT, /api/portfolio/demo-reset)`
  pair, never method alone.** **Assertion:** a test sends `PUT /api/portfolio/holdings` (the
  ordinary composition save) through the filter chain and asserts no `X-Internal-Api-Key` is
  attached.
  _Requirements: design.md D5 (filter scoping)_
- [ ] **GC.8 The login-orchestrated self-call is fail-open on any outcome other than a clean success,
  on both legs, by outcome class rather than enumerated status code.** **Assertion:** a
  parameterized test drives the eligibility read and the reset call each through timeout, connection
  failure, every 4xx/5xx, and (for the eligibility read) zero- and multiple-entry response shapes,
  asserting login always proceeds and nothing is surfaced to the browser in any case. **Also asserts
  8.7's `demo_reset_self_call_skipped` event fires correctly per induced branch, using 8.7's own
  shared `reason` vocabulary verbatim (round-18 addition, vocabulary unified round-19 — a real gap:
  Task 8.9's Abort logic now branches on this event's exact `reason`, and 8.7a structurally cannot
  exercise it, since 8.7a's own stub must return success on both legs for the reset call to fire at
  all per GC.6's matrix; this parameterized test is the only place that actually induces the failure
  branches the event exists to report on):** for each induced branch, exactly one such event fires,
  carrying the exact `reason` value 8.7 defines for that branch (`eligibility_timeout`,
  `eligibility_connection_failure`, `eligibility_non_2xx_status`, `eligibility_shape_failure`,
  `reset_timeout`, `reset_connection_failure`, `reset_non_2xx_status`, or `overall_timeout`) — never
  `gateway_orchestration_error` for any of these induced-from-the-network branches, since none of
  them originate inside the orchestration code itself; never on the clean-success branch; never more
  than once per induced failure. **Each induced case also sends a known inbound `traceparent` on the
  triggering login request and asserts the emitted event's own trace id equals it exactly (round-19
  addition — a real gap: this test previously verified event count and `reason` but never the trace
  correlation Task 8.9's rollback decision actually depends on; 8.7a only tests successful outbound
  propagation, never a failure branch, so nothing previously proved a *failed* leg still tags its
  skip event with the *correct* inbound trace id, as opposed to a stale or absent one).**
  **The configured/attached boolean pairs are asserted per case against *independently observed*
  header presence, never against the provider state the booleans are derived from (round-23
  addition, made non-tautological and state-split round-24 — Task 8.9's Class 1 rollback
  attribution rests entirely on these fields. An earlier draft asserted "the provider holds a key
  and the wrapper attached it, so expect `true`," which an implementation could satisfy by
  computing the boolean straight from provider state while omitting the actual header: the
  load-bearing evidence would report success precisely when the thing it exists to detect had
  happened):** in every case *where a dispatch occurs* **and `configured`/`required=true`**
  (round-27 scoping — see below for the `attached=null`-despite-dispatch case), capture the
  finalized outbound request at the downstream stub (or inspect the finalized `ClientRequest`
  directly) and assert **the emitted boolean equals actual header presence on the wire** — that
  equality is the assertion, not the boolean's own value. **The wire comparison applies only where
  a wire exists AND `attached` is a genuine boolean, never `null` (round-25 correction, scope
  extended round-27 — round-24's blanket "in every case" demanded observations that structurally
  cannot exist in two of its own cases: a `reset_key_not_configured` skip performs no dispatch, so
  there is no outbound request to capture, and the ordinary `required=false` eligibility path
  *succeeds*, so no skip event — and therefore no emitted boolean — exists to compare; round-25's
  fix stopped there, but a third case the same blanket wording still mis-handled survived through
  round-26: the eligibility leg's `required=false` skip-with-unrelated-downstream-failure case
  below genuinely dispatches (`eligibilityDispatchAttempted=true`) yet `attached=null`, because the
  header was never applicable — not because attachment failed. `null` is not a wire-comparable
  boolean; asserting it "equals" a wire-observed `false` would silently pass regardless of what the
  wire actually showed, defeating the whole point of this rule. Wherever `attached=null` despite a
  dispatch occurring, assert three independent facts instead of an equality: dispatch occurred
  (the leg's own dispatch field `true`), `attached=null`, and the header is genuinely absent from
  the wire — a positive confirmation stated on its own, not derived from comparing against
  `null`).**
  Every case below also independently asserts **both** `eligibilityDispatchAttempted` **and**
  `resetDispatchAttempted` **as their own signals, never
  inferred from `attached`'s nullness (round-26 addition, fields split per leg round-28 —
  round-25's `attached=null` conflated two
  different things on the eligibility leg specifically: "not dispatched" and "dispatched but not
  applicable"; asserting dispatch directly, per case, is what actually distinguishes
  them). The two fields' values follow from each case's own topology — the eligibility leg always
  dispatches before the reset leg ever can, so every case whose induced failure is on the
  eligibility leg asserts `resetDispatchAttempted=false` (the flow never got there), and every case
  whose induced failure is on the reset leg asserts `eligibilityDispatchAttempted=true` (the flow
  had to pass through a successful eligibility read to reach it)**.
  **Every induced case additionally asserts 8.7's complete both-legs field set — both
  `configured`/`required` values, both dispatch fields, and both `attached` values, with the
  diagnostic fields the induced branch defines: **`timeoutScope` asserted for its exact value —
  `per-leg` on the eligibility-/reset-leg timeout cases, `overall` on all three overall-timeout
  phase cases — and `elapsedMillis` asserted against a deterministic band derived from each case's
  own test-configured timeout value, never merely "populated" (round-32 correction — a real gap:
  "populated" is satisfied by any non-null value, including a constant or a wrongly-scoped one,
  while 8.9's Diagnosed tier branches on the exact scope and compares the exact elapsed value
  against a tolerance band; a GC.8 that only checks presence cannot catch an implementation that
  emits `timeoutScope=per-leg` on an `overall_timeout` event, or a hardcoded `elapsedMillis`, even
  though both would corrupt a live rollback decision). **Each induced timeout case asserts
  `elapsedMillis` equals an exact value it controls through 8.6's injectable monotonic-clock seam
  (round-33 correction — an earlier round-32 draft asserted only that `elapsedMillis` fell in a
  band around the case's configured deadline `D`, which is satisfied *perfectly* by an
  implementation that emits `D` itself and never reads any clock: the fabricated value sits at the
  band's own centre. The band is the correct instrument for judging a real production timeout,
  where genuine jitter exists — Task 8.9 keeps it for exactly that — but it cannot tell a
  measurement from a fabrication, which is what a test must do):** inject a clock double whose
  successive readings yield a computed elapsed value deliberately unrelated to `D` — far outside
  any tolerance band around it, and not derivable from any configured value the implementation
  could read — and assert `elapsedMillis` equals precisely that. An implementation emitting `D`,
  a constant, or any config-derived number fails; only one that genuinely brackets the seam
  passes. This also removes the real-time dependence from these assertions entirely, making them
  deterministic rather than timing-sensitive.**
  The eligibility- and reset-leg cases measure through 8.7's per-call bracket,
  `overall_timeout` through 8.6's separate orchestration-entry bracket — round-31, see 8.6/8.7,
  **with the `reset_in_flight` case additionally proving `elapsedMillis` is genuinely sourced from
  8.6's orchestration-entry bracket and not the reset leg's own per-call one, via the exact
  expected value its injected clock readings imply (round-32 addition, made exact round-33 — see
  that case's own text below for the mechanism, not repeated here to avoid the two descriptions
  drifting apart;
  the distinguishing insight is that the two candidate sourcings only diverge when the eligibility
  leg's own duration is non-trivial, which the band comparison alone never forced)**,
  `attemptedTarget` populated (and equal to the construction pattern
  8.3/8.5 specify) on every connection-failure and *per-leg* timeout case, **and explicitly `null`
  on the `between_legs` overall-timeout case specifically (round-31 — no call is in flight at that
  phase; the other two overall-timeout cases populate it with whichever leg's target was in
  flight, per 8.7's phase-sensitive rule)**, **`replicaToken` asserted to equal the exact non-blank
  value resolved by an injected `ReplicaTokenProvider` double (5.1b), in at least one case per
  induced branch family, plus one case with the double resolving blank asserting the canonical
  blank (round-33 correction — round-30 specified only "present and blank," since the test
  platform never sets `CONTAINER_APP_REPLICA_NAME`; but blank is the value a hard-coded-blank
  implementation also emits, so that assertion passes while Azure correlation — the field's only
  purpose — fails permanently in production, where nothing else checks it. Injecting the provider
  double is what makes the non-blank path testable at all, and is why 5.1b exists)** (round-30 self-audit correction — 8.7 has required
  both legs' full field sets on every skip event since round-28, but that rule's only executable
  enforcement was scoped inside the `gateway_orchestration_error` block below, and four diagnostic
  fields — `timeoutScope`, `attemptedTarget`, `elapsedMillis`, `replicaToken` — plus the reset-`409`
  evidence quartet were asserted nowhere at all, three of them fields Task 8.9's Diagnosed tier
  actively branches on: an implementation emitting none of them would pass GC.8 while starving
  8.9's classification of its inputs — the recurring evidence-oracle mismatch, caught this time by
  self-audit before review. The per-case bullets below highlight each case's load-bearing values;
  this governing rule supplies the rest of the shape).** **The reset-leg `409` case in the
  parameterized 4xx sweep additionally asserts 8.7's `409` evidence quartet: `observedVersion` and
  `submittedExpectedVersion` present and equal (GC.6), `downstreamCurrentVersion` equal to the
  stubbed `409` body's own `currentVersion`, and `selfCallCount=1` (round-30 self-audit — Task
  8.9's `409` bullet branches on all four, and none had an executable assertion).** Cover,
  separately, for each secret:
  - **configured/required and attached** — provider double holds a value, header present at the
    stub; assert the acting leg's dispatch field `true` (`resetDispatchAttempted=true` for the
    reset-leg variant, `eligibilityDispatchAttempted=true` for the eligibility-leg variant),
    `configured=true, attached=true` (reset leg) /
    `required=true, attached=true` (eligibility leg), with the induced failure coming from
    downstream. Wire comparison applies.
  - **configured/required but attach suppressed** — provider double holds a value, but the attach
    is defeated (the simulated wiring bug); assert the acting leg's dispatch field `true`,
    `configured=true,
    attached=false` / `required=true, attached=false`, and that no header reached the stub. This is
    the one combination Task 8.9 treats as Class 1, so it gets its own explicit case per leg. Wire
    comparison applies.
  - **not configured (reset leg)** — 5.1a's provider double resolves blank. Assert no reset
    dispatch happened at all (zero reset requests at the stub — an *absence* assertion, not a
    header comparison),
    `reason=reset_key_not_configured`, **`httpStatus=null`, `resetDispatchAttempted=false`,
    `eligibilityDispatchAttempted=true` (the eligibility read succeeded to reach the reset
    decision), `configured=false`, `attached=null`/absent — this case's load-bearing values, with
    the governing both-legs rule above supplying the rest of the shape (rescoped round-30 — the
    round-26 "full event shape" phrase predated `replicaToken` and the origin-verify fields'
    inclusion, and had become literally false of the enumeration; round-26
    addition — this reason had no explicit `httpStatus` value defined anywhere; `null` follows
    directly from no HTTP call ever being attempted, matching the existing null convention for
    `*_timeout`/`*_connection_failure`/`gateway_orchestration_error`)**.
  - **not required (eligibility leg)** — 8.2a's provider double resolves blank, **plus an induced
    *unrelated* downstream failure — a stubbed `500` on the eligibility read — so a skip event
    exists to carry the booleans at all (round-25 correction — round-24's version of this case
    asserted field values on the ordinary *successful* Azure/local path, which emits no skip event
    and therefore has nothing to assert against)**: assert **`eligibilityDispatchAttempted=true`
    (round-26 — the
    eligibility read itself was sent; only the header was inapplicable)**,
    `resetDispatchAttempted=false` (the failed read means the flow never reached the reset leg),
    `required=false`,
    `attached=null`/absent (no attach was ever applicable), no `X-Origin-Verify` header at the
    stub, **and the exact expected classification inputs pinned rather than gestured at (round-28
    correction — this case previously asked 8.9's rules to classify "this event's `403`-adjacent
    shape," but the induced failure is deliberately a `5xx`, not a `403`, and with
    `required=false` origin verification is a no-op end-to-end, so nothing about this case is
    `403`-shaped; the vague wording obscured what the test proves, which is boolean *carriage* on
    an origin-verify-irrelevant failure, not any origin-verification behavior):**
    `reason=eligibility_non_2xx_status`, `httpStatus=500` — and that Task 8.9's classification
    treats exactly that event as Class 2d
    territory, never Class 1. **A separate companion case asserts the clean path directly:** provider
    blank, downstream healthy — login succeeds, the reset proceeds normally, and **no skip event is
    emitted at all**; this, not a field value, is what "the ordinary Azure/local shape is a passing,
    non-defect state" actually means, and a regression that starts emitting skip events (or failing)
    on that path is caught here rather than in production.
  **The two blank-provider cases remain separate tests with opposite meanings and SHALL NOT be
  collapsed into one "blank the secret or suppress the attach" case (round-24 correction — an
  earlier draft merged them, which would have let either behavior satisfy the other's assertion).**
  Fail-open toward login holds in every case above, asserted as everywhere else in this constraint.
  **Separate, additional cases induce `gateway_orchestration_error` itself on *each* leg — not just
  assert its absence elsewhere (round-20 addition — a real gap: every case above proves the
  network-induced branches never *misfire* as `gateway_orchestration_error`, but nothing previously
  induced that branch's own trigger and proved it actually fires correctly; Class 1's one
  *immediately* rollback-eligible path (Task 8.9) therefore had zero executable proof; **split into
  one case per leg round-27, asserting the complete event shape, not just `reason` — round-26's
  dispatch derivation rules were both wrong specifically on this pre-dispatch path, and
  nothing previously exercised it to catch that**; **each leg's case given its own, genuinely
  reachable fault seam round-28 — round-27's shared "invalid loopback URI via test configuration"
  example cannot exercise the reset leg at all: 8.5 builds its target from 8.3's same call-time
  port seam, so corrupting that shared construction fails the *eligibility* leg first and
  execution never reaches the reset leg's own construction; "or an equivalent fault" handed the
  implementer no owned mechanism for the leg the shared example can't reach**):**
  - **Eligibility-leg case:** force the eligibility read's own request construction to throw
    before any network call is attempted — the invalid/unresolvable loopback URI injected via test
    configuration works here, since this leg's construction runs first.
  - **Reset-leg case:** leave the eligibility leg fully healthy — the shared port seam intact, the
    downstream stub returning a single-entry, idle-eligible response — so the orchestration
    genuinely passes the eligibility read and the idle check and reaches the reset leg; then fault
    the reset leg's *own* construction alone, through 8.5's leg-specific construction seam
    (round-28, see 8.5): override that seam in test configuration with an implementation that
    throws (or yields an unconstructible target) before dispatch. Corrupting the shared port seam
    is explicitly NOT a valid mechanism for this case, for the reachability reason above.
  - **Post-response case, reset leg (round-29 addition — 8.7's scope for this reason has included
    "after a successful downstream response, if the orchestration's own success-handling code is
    what throws" since round-21, but both cases above are pre-dispatch construction faults, so the
    post-response form — the one whose evidence shape is *opposite* on three fields, and the only
    one where a legitimate `demo_reset_succeeded` can coexist — had no coverage at all; without it
    an implementation that emits the pre-dispatch shape unconditionally, or fails to emit the event
    at all when a success handler throws, passes GC.8 while violating 8.7):** leave both legs fully
    healthy through dispatch — eligibility returns a single-entry, idle-eligible response, and the
    reset call genuinely goes out and returns a real success — then fault the orchestration's own
    handling of that successful reset response (a test-scoped fault injected into the success-path
    handler, after the response is received and before the orchestration completes). **This case
    runs against the real portfolio-service chain — the same Task 4.4-standard real-chain,
    in-process topology the timeout-boundary case below uses (that case's round-44 topology
    correction applies here identically) — not an api-gateway-only stub (round-30
    correction — round-29 wrote "where the downstream chain is the real one... may coexist,"
    which made the case's most distinctive assertion optional and its topology a choice: a stubbed
    downstream can return a success *status* but cannot commit real state or emit the real
    `demo_reset_succeeded` log line, so a stub-topology version of this case would prove the event
    shape while silently skipping the one property only this case can prove — that Class 1's
    dual-event coherence claim (a legitimate success alongside `gateway_orchestration_error`) is
    *reachable*, the exact standard the timeout-boundary case was held to in round-21).** Assert
    the **post-response evidence shape**, which differs from the two cases above exactly where 8.7
    says it should: `reason=gateway_orchestration_error`, `leg=reset`,
    `eligibilityDispatchAttempted=true`, **`resetDispatchAttempted=true`** (the call did go out),
    **`internalApiKeyAttached` a genuine boolean, not `null`** (a real request existed to inspect —
    `true` on the ordinary configured path this case runs), with the usual wire comparison applying
    since `configured=true`; **and — REQUIRED, not optional — that portfolio-service's real
    `demo_reset_succeeded` fires for this same trace id (with its resulting version) and coexists
    with the skip event**, which per 8.7 and Task 8.9's Class 1
    is coherent, not contradictory evidence. Fail-open still holds: login proceeds untouched.
  Each case asserts the **complete six-field shape — both legs' `configured`/`required`, both
  dispatch fields, and both `attached` values — never only the failing leg's (round-29 correction:
  round-27/28 pinned both dispatch fields but only the *failed* leg's `attached`, leaving the other
  leg's `configured`/`required` and `attached` unasserted, so an implementation that omitted them
  entirely — violating 8.7's own round-28 rule that both legs' full field sets appear on every skip
  event regardless of `leg` — would still pass GC.8; the normative contract and its only executable
  check had drifted apart in the same round the contract was written)**: exactly one
  `demo_reset_self_call_skipped` event
  fires, `reason=gateway_orchestration_error`, `leg` set to whichever leg's own code threw,
  `httpStatus=null` for the two pre-dispatch cases and **the actually received success status for
  the post-response case (round-30 self-audit — 8.7's null convention is for branches that never
  reached a status; this one did, and discarding it would throw away the one field that shows the
  downstream leg was healthy when the wrapper's own handler failed)**, **the failed leg's dispatch
  field `false`** for the two pre-dispatch cases
  (round-27 — the network call that
  leg would have
  made never went out; verify this explicitly rather than assuming it from `configured`/`required`,
  which is exactly the derivation round-26 got wrong here — with the other leg's field matching
  the topology per the governing rule above: the eligibility-leg case also asserts
  `resetDispatchAttempted=false`, the reset-leg construction case also asserts
  `eligibilityDispatchAttempted=true`), **the failed leg's `attached=null`** for those same two
  cases (round-27 — no
  request existed to check for the credential/header, so neither leg's attach boolean is `false`
  here despite `configured`/`required` potentially being `true`), **and the non-failing leg's own
  three fields pinned to what that leg actually did (round-29): in the eligibility-leg case the
  reset leg never ran, so `internalApiKeyConfigured` reflects the provider double's state,
  `resetDispatchAttempted=false`, and `internalApiKeyAttached=null`; in the reset-leg construction
  case the eligibility leg completed normally, so `originVerifyRequired` reflects 8.2a's double,
  `eligibilityDispatchAttempted=true`, and `originVerifyHeaderAttached` is `null` when
  `required=false` or a genuine boolean when `required=true` — a real request existed to inspect,
  so the same wire comparison the ordinary cases use applies to it here too.** Plus
  a sanitized `exceptionClass`, and
  the correct inbound trace id (same mechanism as every other case above); login still proceeds and
  nothing is surfaced to the browser — fail-open holds for this reason exactly as it does for every
  network-induced one, on both legs and in both evidence shapes. **A further case tests the
  timeout-boundary race between the two events themselves, against the *real* chain, not a stub
  (round-20 addition, topology corrected round-21 — an earlier draft stubbed the reset call's
  downstream response and merely asserted what a delayed call "would itself have produced," which
  proves nothing: an api-gateway-only stub cannot commit real portfolio-service state or emit
  portfolio-service's real `demo_reset_succeeded` log line, so it never actually demonstrated both
  *real* events coexisting; mechanism and timing decoupled from 8.2 round-21):** a composed test
  wiring api-gateway's self-call through to portfolio-service's genuine `DemoResetService →
  HoldingReplacementService → GoldenStateTuplePreparer → persistence` chain (Task 4.4's own "real
  chain, not mocked" standard, reused here), both running in-process as dual Spring test contexts —
  never docker-compose for *this* case (round-44 correction: an earlier draft offered
  "in-process/docker-compose like GC.8's other cases" as interchangeable topologies, but this
  case's own delay decorator, test-property timeout override, and injected doubles below are all
  in-process Spring-test constructs that cannot reach inside a composed container built from the
  production image — only the in-process form implements the case as specified) — and **not** a
  live-cloud test. **Delay mechanism named explicitly (round-21 addition — an
  earlier draft said only "a test-only delay," naming no actual insertion point, unlike every other
  task in this document): a test-scoped decorator/advice wrapping the call to
  `DemoResetService.reset(...)` itself, sleeping immediately after that call returns (the commit and
  its log line have already happened by then) and before the controller method returns — this avoids
  relying on servlet response-buffering behavior, which is not a guaranteed contract for when bytes
  are actually flushed.** **Timing decoupled from 8.2's real, still-open resolved value (round-21
  correction — an earlier draft calibrated the delay "to exceed 8.2's resolved overall-timeout
  value," but that value is explicitly still an OPEN item elsewhere in this document (Task 8.2), so
  this test couldn't be fully specified until it resolves, and a delay calibrated against a
  multi-second real value would be exactly the slow, margin-dependent flakiness pattern Task 8.9's
  own bounded-polling design exists to avoid): override the overall timeout to a small, test-only
  value (e.g. 100ms) via Spring test property override, independent of whatever 8.2 eventually
  resolves to in production, and set the decorator's sleep to a generous, deterministic multiple of
  that test-only value (e.g. 500ms) — this makes the test fast and non-flaky by construction, not by
  tuning a margin against a real-world duration.** **A second, independent, deterministic delay is
  also inserted on the eligibility leg's own response — a small, bounded value comfortably inside
  the eligibility leg's own per-leg timeout so it still completes as a genuine success, e.g. 30ms
  against a per-leg timeout an order of magnitude larger (round-32 addition — needed so this
  case's `elapsedMillis` can prove which bracket produced it: see the governing both-legs-field-set
  rule above).** Assert: portfolio-service's
  real `demo_reset_succeeded` event fires (with the correct trace id and resulting version) at the
  commit point; api-gateway's real `demo_reset_self_call_skipped(reason=overall_timeout)` fires
  independently once its own (test-overridden) deadline elapses, never having seen the (delayed)
  response — **carrying `eligibilityDispatchAttempted=true` AND `resetDispatchAttempted=true`
  (round-28 addition — this is the one induced case where a `leg=overall` event exists with both
  calls having genuinely left the process, so it is where the per-leg dispatch pair's
  overall-timeout semantics (8.7) get their executable proof: a single generic dispatch boolean
  could not have said which legs' calls were in flight when the deadline hit)**, **and
  `overallTimeoutPhase=reset_in_flight` (round-29)**; **and `elapsedMillis` equal to the exact
  value implied by the injected clock seam's orchestration-entry reading — which, because the
  seam's readings are chosen so the orchestration-entry-sourced and reset-leg-sourced elapsed
  values are *different known numbers*, is simultaneously the proof that the measurement is real
  (round-33, the fabrication check above) and the proof it is sourced from 8.6's bracket rather
  than the reset leg's own (round-32's purpose, now established by exact equality rather than the
  `>= 30ms` lower bound that draft used — a bound the fabricated value `D` also satisfied, so it
  never actually distinguished the two sourcings it was written to distinguish).** The eligibility
  leg's own induced delay (above) stays as the topological reason the two sourcings diverge at
  all; the clock seam is what makes the divergence observable as an exact expected number instead
  of an inequality; both events exist
  under the same trace id — confirming outcome (e) (both events, same
  trace id) is reachable, not hypothetical.
  **A companion overall-timeout case covers the other dispatch-pair branch (round-29 addition — the
  case above is the `true/true` branch; nothing exercised `true/false`, which is precisely the
  branch whose ambiguity 8.7's `overallTimeoutPhase` was added to resolve, so the field's own
  discriminating value went untested in the round that introduced it):** with the same test-only
  overall-timeout override, delay the *eligibility* stub's response past that deadline instead, so
  the deadline fires while the read is still in flight and the reset leg is never reached. Assert:
  `reason=overall_timeout`, `leg=overall`, `eligibilityDispatchAttempted=true`,
  **`resetDispatchAttempted=false`**, **`overallTimeoutPhase=eligibility_in_flight`**, the reset
  leg's `internalApiKeyAttached=null` (no reset request existed), no `demo_reset_succeeded` for
  this trace id (nothing downstream was ever asked to reset), and login proceeding untouched.
  **A second companion case induces the third phase, `between_legs` — the one phase carrying its
  own distinct classification consequence, and the only one round-29 left untested (round-30
  addition — Task 8.9's Diagnosed tier gives `between_legs` a reproduction-gated Class 1 path
  that neither other phase has, so the single most load-bearing phase value was the single
  untested one):** eligibility stub healthy and fast, returning a single-entry idle-eligible
  response; then stall the orchestration *between* the legs deterministically, by overriding
  8.5's own construction seam (the same owned seam the reset-leg construction-fault case uses)
  with an implementation that sleeps past the test-only overall deadline **and then completes
  construction normally** — the deadline fires during the sleep, with the read complete and no
  reset call yet dispatched, which is precisely `between_legs`. **The seam's own sleep is timed to
  finish, and construction to complete, comfortably after the test-only deadline has already
  fired — the whole point being to give a broken cancellation implementation a real window to
  leak a late dispatch — and the test explicitly waits for that window to close (round-31 addition
  — without an explicit wait, a fast assertion could observe the pre-cancellation state and pass
  regardless of whether cancellation actually works, proving nothing about 8.6's guarantee).**
  Assert, **independently at the downstream stub, not merely from the emitted event fields
  (round-31 correction — the event's own `resetDispatchAttempted=false` is the code under test
  self-reporting its own behavior; an implementation with a broken or racy cancellation path could
  emit that field correctly while a reset request still reaches the stub late, exactly the wire-vs
  -self-report gap this same constraint's own governing rule requires closing everywhere else — a
  gap this case had left open for the one guarantee, 8.6's cancellation, that only this case
  exercises): zero reset requests were ever received at the stub**, checked after the wait above,
  not merely absent at assertion time before the seam has finished running. Then assert the event
  shape: `reason=overall_timeout`,
  `leg=overall`, `eligibilityDispatchAttempted=true`, **`resetDispatchAttempted=false`**,
  **`overallTimeoutPhase=between_legs`**, the reset leg's `internalApiKeyAttached=null`, no
  `demo_reset_succeeded` for this trace id, and login proceeding untouched.
  **These three overall-timeout cases together prove the pair-plus-phase agreement rule 8.7 states
  by construction is actually honored at runtime** — every phase value induced and asserted, not
  only stated in prose. **This test exercises one specific ordering — success
  logged before the skip fires, from a fast commit whose *response* is delayed (round-22 correction:
  an earlier draft claimed this confirms outcome (e) "exactly as... Class 2f assume[d]," but Class
  2f's own narrative describes the opposite ordering — slow *processing* delaying the commit itself
  past the gateway's deadline. Both orderings are real, physically realizable races; this test proves
  one of them is reachable, not that it is the only one, and Task 8.9's own classification does not
  depend on which order occurred — see Class 2f/2g and step 5 outcome (e)).**
  _Requirements: 7.3c; design.md D5_
- [ ] **GC.9 No blocking HTTP client in the login path.** `.block()` and `RestTemplate` SHALL NOT
  appear anywhere in `AuthController`'s call graph. **Assertion:** a source/architecture check fails
  the build if either token appears in a file reachable from `AuthController.login()`.
  _Requirements: design.md D5 (non-blocking execution)_
- [ ] **GC.10 The manual-reset gateway bundle ships as one deployable unit; portfolio-service's
  internal endpoint is a separate, earlier prerequisite, not bundled with it.** **Assertion:** a
  task-level checklist item (Wave 5's STOP/GO) requires Wave 4's live-verification evidence before
  Wave 5 opens, structurally preventing the two from being conflated into one PR.
  _Requirements: design.md D5 (cross-service rollout sequencing, pass 19-21)_
- [ ] **GC.11 The login-orchestration self-call is a separately gated gateway deployment from the
  manual-reset bundle, gated on Task 8.1 plus the idle-threshold and timeout decisions.** It may
  deploy before or after Wave 5; neither branch depends on the other, and both converge only at
  Wave 10 exposure. **Assertion:** Wave 8's STOP/GO requires its three prerequisites and never
  requires Wave 5/6, while Wave 10 requires both branches complete.
  _Requirements: design.md D5 (pass 21 correction)_
- [ ] **GC.12 The modal implements the WAI-ARIA APG Dialog pattern; picker controls carry real
  semantics, not visual-only affordances.** **Assertion:** an automated accessibility check (axe, or
  a Testing-Library focus-order assertion) runs in CI against the built modal — not verified by
  visual review of the mockup alone.
  _Requirements: 1.7, 1.8_
- [ ] **GC.13 Cross-document dependency integrity is executable, not a pass-count convention.**
  `scripts/check_asset_picker_spec_consistency.py` SHALL parse this plan and the three owning B2
  artifacts and fail on duplicate task/GC identifiers, missing `_Requirements:` trailers, a task
  referenced by a Go/Abort gate that does not exist, an AWS-only gate treated as Azure-blocking,
  `updatedAt` described as ownerless, or a release sequence that contradicts the master-plan DAG.
  The check runs in active CI whenever any of the four documents changes. It deliberately does not
  compare historical review-pass counts.
  _Requirements: cross-cutting change-control guardrail_

**Scope guard.** B2 introduces no multi-portfolio selector or portfolio id on the wire, no trade
ledger or weighted-average cost inference, no per-holding freshness backend addition, and does not
implement account-settings "Profile changes." **Assertion:** a source-diff/path guard, modeled on B1
`tasks.md` GC.5, flags any new portfolio-identifier parameter, any new persistence table, or any file
under an account-settings path.
_Requirements: Non-goals 1-4_

---

## Wave 1 — Picker UI shell, against frozen contracts · *frontend, startable now*

Buildable and fully testable without a live backend: mock `GET /api/assets` and
`PUT /api/portfolio/holdings` against B1's frozen response/request shapes (`design.md` D2).

- [x] **1.1 Build-time feature flags for B2's user-facing entry points — round-3 correction: these
  are NOT runtime configuration, and one flag doesn't cover every B2 control.** Verified directly:
  `frontend/next.config.ts` sets `output: "export"` (a static export, no server), and
  `deploy-azure.yml`'s own `Build Next.js static export` step documents that `NEXT_PUBLIC_*` values
  are "injected at build time so the static export embeds" them — there is **no runtime
  configuration mechanism for this frontend at all**. "Enabling a flag" is therefore always a new
  build-and-deploy, never a config toggle; Wave 10 owns that mechanics, not this task. Two
  independent flags, since the picker and the manual-reset control are gated on different,
  independent conditions (Wave 2.2's decimal write-safety gate vs. Wave 6's B1-5.1 gate) and
  requirements.md 7.6 hasn't decided whether the reset control even lives inside the picker:
  - `NEXT_PUBLIC_ENABLE_ASSET_PICKER` (unset/false by default) gates `EditHoldingsButton` (1.4) and
    everything it opens.
  - `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL` (unset/false by default) gates Wave 6's manual-reset
    control specifically, wherever 7.6 ultimately places it — inside or outside the picker — so that
    placement decision never needs to re-plumb this gate.
  Both default to disabled whenever unset, so any environment that hasn't been told about them (a
  fresh local checkout, a workflow not yet updated) stays safe by omission. **Parsed by exact string
  match, never truthiness (round-4 addition — a `NEXT_PUBLIC_*` value is always a string at
  runtime).** Enabled iff the normalized value is exactly `"true"`; every other value —
  `undefined`, `""`, `"false"`, `"0"`, or anything else — is disabled. A truthiness check
  (`if (flagValue)`) would treat the rollback value `"false"` as a non-empty, therefore truthy,
  string — defeating Wave 10's own rollback mechanism the moment it tried to use it. Tests cover
  `undefined`, `""`, `"false"`, `"0"`, and `"true"` explicitly for each flag, independently of the
  other flag.
  _Requirements: 1.1_
- [x] **1.2 Frontend portfolio-adapter prerequisite — depends on Wave 2 Task 2.1 (round-5 addition:
  stated explicitly rather than left implicit, since round 4 attributed `quantityFidelityUnverified`
  to this task while Task 2.1 actually defines it — Wave 1 is not independently completable without
  this dependency being named).** Verified directly against current source
  (`frontend/src/lib/api/portfolio.ts`, `frontend/src/types/portfolio.ts`) that every later task in
  this wave otherwise silently assumes: `BackendPortfolio`/`PortfolioResponseDTO` carry no `version`
  field today, and `loadBackendPortfolio` selects `portfolios[0]` — the first element of the list B1
  actually returns — rather than the entry matching the caller's own `userId`, discarding list
  identity exactly where `design.md` D5 warns against it (its list-selection rule for
  `List<PortfolioResponse>` applies to this picker's own initial read too, not only the demo-login
  path). Add `version: number` to both types; fix the selection to distinguish three cases, not two
  (round-2 correction — collapsing them lost the actual new-user path): an **empty list** is the
  valid no-portfolio state, producing `expectedVersion: 0` (requirements.md 4.5); **exactly one**
  entry matching the caller's `userId` is the normal case; **zero matches in a non-empty list, or
  more than one match**, is a contract failure — B1's own Primary_Portfolio invariant promises
  exactly one portfolio per user, so this case SHALL surface as an error state, never silently
  proceed with an arbitrary element (this is distinct from, and not the same defensive treatment as,
  D5's login-eligibility-read fail-open rule, which governs a different call site with a
  skip-and-proceed option this one doesn't have). **This task's implementation SHALL run Wave 2 Task
  2.1's ingestion boundary as part of building `PortfolioResponseDTO`** — both tasks edit the same
  file (`portfolio.ts`) and are naturally implemented together, not a genuine cross-wave release
  gate (unlike Wave 2.7 below): a developer picking up this document implements 2.1 first, or
  alongside 1.2, before 1.5's preflight has anything to check.
  _Requirements: 4.1, 4.5; design.md D2, D5_
- [x] **1.3 `AssetPickerModal` shell** — WAI-ARIA Dialog pattern (role, `aria-modal`,
  `aria-labelledby`, focus trap, focus return, `Escape` discard). No existing Dialog primitive to
  reuse; new component.
  _Requirements: 1.1, 1.7; design.md D1_
- [x] **1.4 `EditHoldingsButton`** on the Portfolio page, behind 1.1's flag, opening the modal with
  the current portfolio and version (now correctly sourced per 1.2) as initial state.
  _Requirements: 1.1_
- [x] **1.5 Data-integrity preflight for `quantityFidelityUnverified` — one policy, chosen
  explicitly: strict preflight with immutable provenance, not editable in-modal remediation
  (round-5 correction: round 4 specified both policies at once — 1.5 refused to open the modal
  whenever ANY holding was unverified, while 1.6 required a test that opens an unverified draft,
  edits the row, and submits — a state 1.5's own refusal makes unreachable through the application;
  editable remediation is a real UX alternative but needs its own product decision this document
  isn't positioned to make unilaterally, so strict preflight is what's specified).** This check runs
  entirely inside `EditHoldingsButton`'s (1.4) own click handler, **before** `AssetPickerModal`
  (1.3) is ever invoked: if any holding 1.2's adapter would seed carries
  `quantityFidelityUnverified: true`, the button SHALL NOT open the modal at all. **The refusal rule
  itself is normative and fixed. Its exact presentation is an implementation choice, not a tracked
  OPEN product decision (round-7 correction: round 6 called it "OPEN" and "its own open item"
  without adding it to the header's count, requirements.md's Open items list, or Wave 10's gate —
  inconsistent with how every other item this document calls OPEN is actually tracked, e.g.
  requirements.md 7.6's manual-reset placement, which genuinely does block Wave 10).** Unlike 7.6,
  this presentation decision does not need product sign-off to be safe to ship, is expected to be
  exercised rarely (only during a legacy-numeric rollback window), and carries no ambiguity that
  could compound with other undecided items — so it is scoped here as an implementation choice
  bounded by minimum accessibility constraints: the notice SHALL be programmatically associated
  with the button (e.g. `aria-describedby`) so assistive technology announces it, and SHALL NOT
  rely on color alone. A small, non-modal inline notice next to the button is one reasonable shape;
  the specific wording used above ("Editing is temporarily unavailable") is illustrative, not
  mandated copy — an implementer may choose different wording or placement within those
  constraints without raising it as a blocking decision. **The modal's own
  contract stays untouched by this case:** whenever
  `AssetPickerModal` does open, GC.1 holds unconditionally, exactly as frozen — there is no new
  modal state to reconcile against requirements.md, because the modal simply never opens when it
  can't be honestly seeded, and by construction **every row in an opened draft is already verified**
  (1.6 below). Independent of the feature flag (a backend rollback or stale environment can
  reintroduce unverified data regardless of flag state) — this preflight is the guard, not the flag.
  **This is the sole enforcement point** — see 1.13 for why no separate submit-time recheck is
  needed under this policy.
  _Requirements: 8.1, 8.3; design.md D3_
- [x] **1.6 Draft state** — `Map<ticker, {quantity: string, meta}>`, seeded per GC.1 at open time.
  **No per-row fidelity tracking is carried into the draft (round-5 simplification, reverting round
  4's addition):** under 1.5's strict-preflight policy, the modal only ever opens once every seeded
  holding has already passed the all-verified check — so every row present in an opened draft is
  unconditionally verified by construction, and there is no reachable state where a draft row is
  both rendered and unverified. A ticker added during Browse (1.7) never carries the flag at all:
  its quantity comes from the user's own text input (1.8's validator), not from a portfolio read, so
  fidelity is not a question for it. Test: assert a draft can only be constructed from an
  all-verified source read — attempting to seed it from a read containing any unverified holding is
  1.5's own responsibility to have already refused, before this state is ever reached.
  _Requirements: 1.2, 1.2a; design.md D1_
- [x] **1.7 `BrowseStep`** — `AssetSearchBar` (client-side filter by ticker/name), `AssetList`
  (active-only for new selection; a held `Deprecated_Asset` rendered distinctly, per 2.2-2.3),
  `DraftRow` with full control semantics: native checkbox or `role="checkbox"` + live
  `aria-checked` + `aria-label`, quantity `<input>` with its own `aria-label`, and a
  `RetainedDeprecatedRow` variant whose checkbox stays fully operable while its quantity input is
  reduce-or-remove-only (client-side rejection on an increase attempt, `aria-describedby`-linked
  explanation, `max` as a supplementary hint only).
  _Requirements: 2.1, 2.2, 2.3, 2.4, 1.8; design.md D1_
- [x] **1.8 Quantity-domain validator.** A dedicated string-domain validator wired into 1.7's
  quantity input, enforcing B1's Quantity_Domain client-side before a value can enter the draft:
  required, strictly positive, at most 11 integer digits and 8 fractional digits, malformed decimal
  text rejected. Blocks progression/submission on failure; failure text is associated via
  `aria-describedby` on the offending input, not color alone. Table-driven component tests (valid
  boundary values at exactly 11/8 digits, one-over on each, non-numeric text, empty, zero, negative).
  _Requirements: 2.5, 1.8_
- [x] **1.9 Duplicate-ticker prevention in the browse UI** — selecting an already-drafted ticker
  edits its existing row rather than adding a second one.
  _Requirements: 2.6_
- [x] **1.10 Selected-asset pricing and estimated value (mocked).** Fetches prices only for tickers
  currently in the draft, against a mocked `/api/market/prices?tickers=` at this stage — never the
  full browse list. Computes a display-only estimated value from price × the draft's string
  quantity, converting to a number only at this display boundary (GC.2); the underlying draft state
  and submit payload are never touched by this conversion.
  _Requirements: 3.1; design.md D3_
- [x] **1.11 Catalog conditional-revalidation (mocked) — no task previously owned this (round-3
  addition).** `design.md` D2 requires `GET /api/assets` to carry an `ETag` on `catalogVersion`,
  conditionally revalidated (`If-None-Match` → `304`), with **no second, persistent client-side
  cache** on top of it. Mock coverage: an initial `200` with an `ETag` header; a subsequent request
  carrying that `ETag` as `If-None-Match` returning `304` with no body; the client re-uses its
  already-held catalog on `304` rather than re-fetching or persisting a separate copy (no
  `localStorage`/`IndexedDB` involved at any point).
  _Requirements: 2.1; design.md D2_
- [x] **1.12 `ReviewStep`** — pure derivation `diff(initialHoldings, draftHoldings)` →
  added/changed/removed/unchanged, with `aria-current="step"` on the step indicator and an
  `aria-live="polite"` draft-count summary.
  _Requirements: 1.8; design.md D1_
- [x] **1.13 Composition-save mutation (mocked), including the full success transition, not just the
  data response.** The save state machine Wave 9 later rewires to a real endpoint, built and fully
  tested here first: **no submit-time fidelity recheck is needed (round-5 simplification, replacing
  round 4's per-row recheck)** — under 1.5's strict-preflight policy, the draft is populated once,
  entirely from a source read 1.5 already confirmed all-verified, and no path exists for an
  unverified value to enter it afterward (1.6); constructs the complete `holdings` payload from the
  full draft (GC.3, never a diff) plus
  the `expectedVersion` observed at modal-open time (1.2), including the first-time case
  (`expectedVersion: 0` when 1.2's no-portfolio state applies) and the empty-desired-set case (a
  valid, submittable draft meaning "remove every holding," per requirements.md 1.6). On a mocked
  `200`: replaces visible portfolio state with the response body's actual holdings and version —
  never the client's own draft (requirements.md 4.2); closes the modal; returns focus to
  `EditHoldingsButton` (1.4), by the same mechanism 1.3's `Escape` path already uses (requirements.md
  1.7); renders the post-save confirmation as a `role="status"`/`aria-live="polite"` region
  (requirements.md 1.8); and invalidates/reconciles the existing portfolio and portfolio-summary
  query caches (`usePortfolio` and friends) so the Portfolio page reflects the save without a manual
  refresh. On a mocked `409`, transitions into the frozen, read-only, non-resubmittable state 1.14's
  `ConflictPanel` renders (GC.4) — this task owns producing that state, not merely reacting to a
  panel that already has it.
  _Requirements: 1.6, 1.7, 1.8, 4.1, 4.2, 4.3, 4.5, 8.3; design.md D2_
- [x] **1.14 `ConflictPanel`** — rendered alongside a read-only, keyboard-scrollable draft summary
  (`role="region"`, `aria-label`, `tabindex="0"` on the region; rows carry no individual
  `tabindex`/`role="checkbox"`/`aria-disabled`), with the two explicit exits (reload-and-start-over,
  close) per GC.4, consuming the frozen state 1.13 produces on `409`.
  _Requirements: 4.3, 4.4; design.md D1_
- [x] **1.15 `PresenceBanner`** — queried once on mount against a mocked presence endpoint at this
  stage; renders the persistent advisory on `anotherSessionActive: true`, renders nothing on
  error/absence (GC.5).
  _Requirements: 6.3, 6.4, 6.5; design.md D4_
- [x] **1.16 Compact portfolio-level freshness status (mocked), full contract — not a
  trimmed shape.** Round 1's mock (`{ state, staleHoldings }`) understated Spec A's actual response
  contract (Spec A `design.md`, the Summary response shape): `state`,
  `oldestKnownAssetPriceObservationTimestamp`, `staleHoldings`, `unknownPriceHoldings`, and
  `missingPriceHoldings`. **The timestamp field is optional/omitted when there is no known
  observation (an empty portfolio, or one entirely in `MISSING` state), never present as JSON
  `null`** (round-3 correction: Spec A's own wording is "the timestamp absent," which is a key-omitted
  contract, not a nullable one — the two require different frontend types (`timestamp?: string`, not
  `timestamp: string | null`) and different test assertions (property absence, not a null-value
  check)). The mock and the frontend response-adapter type SHALL reproduce all five fields under this
  exact optionality, since Task 1.17's popover cannot implement its required unknown/missing counts
  or its absent-timestamp behavior against a narrower or differently-typed shape. This is the compact
  status itself, distinct from 1.17's drill-down popover.
  _Requirements: 3.2_
- [x] **1.17 `FreshnessDetailsPopover`** — anchored popover on 1.16's "Details" control (both
  pre- and post-save), full content/keyboard contract per requirements.md 3a: per-state counts
  omitting zero rows, all-`FRESH` single line, the absent-timestamp case per 1.16's optional
  `oldestKnownAssetPriceObservationTimestamp` (property omitted, not tested as `null`),
  `aria-haspopup="dialog"` + `aria-expanded` + `aria-controls`, focus moves into the popover on open
  and returns to the button on close.
  _Requirements: 3.3, 3a; design.md D1_
- [x] **1.18 Post-save freshness is re-read, never assumed.** The Portfolio page re-reads
  `assetPriceFreshness` after a successful save rather than inferring a fresh state the write path
  cannot produce.
  _Requirements: 3.4_
- [x] **1.19 Accessibility check wired into CI** (GC.12) against the built modal.
  _Requirements: 1.7, 1.8_

**Live-integration dependency status:** Spec A task 8.6 is complete and
`PortfolioSummaryDto.assetPriceFreshness` exists in `portfolio-service`. This wave still mocks the
shape; Wave 9 owns wiring it to the real response.
_Requirements: 3.2, 3.4_

## Wave 2 — Decimal adapter migration, display-compatible now, write-safe only once B1 ships

**Round 1's "canonical decimal string" framing was itself wrong, not just under-specified —
corrected here rather than patched.** A legacy JSON *number* has already lost its exact wire
formatting by the time JavaScript parses it: `"0.75000000"` becomes the numeric value `0.75`, and
nothing in the runtime remembers there were eight fractional digits, not two, in what
portfolio-service actually persisted. Re-serializing that number back into a string cannot
reconstruct the original digit count — this is not an implementation-carefulness problem Task 2.1
can solve by being more precise, it is an information loss that already happened in JSON parsing,
before any B2 code runs. B1's own `ToPlainStringSerializer` (B1 `design.md`, "the serializer emits
`toPlainString()`, preserving trailing fractional zeros as stored so a round trip is byte-stable")
is exactly the guarantee a legacy-numeric read cannot honor. Two distinct types, not one shape-union
field, and one hard rule about where each may be used:

- **`WireHolding.quantity: number | string`** — the raw transport shape only, read directly off
  `GET /api/portfolio`'s JSON, used nowhere outside Task 2.1's own parsing function.
- **`AssetHoldingDTO.quantity: string`** — the domain type every other consumer sees, unchanged
  from Round 1's plan.
- [x] **2.1 Ingestion boundary, honest about which values are trustworthy.** Parse `WireHolding` and
  produce `AssetHoldingDTO`: a **string** wire value is preserved **verbatim**, byte-for-byte — this
  is the only case with any fidelity guarantee. A **number** wire value (today's shape, until B1
  task 4.9 ships) is converted via `String(value)` for **display compatibility only** and is never
  claimed byte-faithful to what portfolio-service stored; each such holding additionally carries
  `quantityFidelityUnverified: true` on the domain type, so downstream code can tell the two cases
  apart without re-deriving it.
  _Requirements: 8.1_
- [x] **2.2 The picker's write path SHALL refuse any holding carrying
  `quantityFidelityUnverified: true`.** Concretely: `EditHoldingsButton` (Wave 1) stays behind its
  feature flag (Wave 1's new flag task) until B1 task 4.9 is confirmed live — the existing, read-only
  Portfolio page may keep displaying legacy-numeric-sourced values indefinitely via 2.1's
  compatibility branch, but the picker never seeds a draft (GC.1) or constructs a save payload (Wave
  1's composition-save task) from an unverified value. **This protects the picker's own writes only
  — it is NOT what satisfies requirements.md 8.3 (round-4 correction: round 3 over-claimed this).**
  8.3 protects the **existing** Portfolio page, which reads `quantity` today assuming it is always a
  JSON number and is not gated by either feature flag at all — a picker-specific write guard does
  nothing for that unrelated, always-on read path. Task 2.7 below is the actual mechanism.
  _Requirements: 8.1 (round-10 correction — this task's own body disclaims 8.3; the footer citation
  had never been updated to match, contradicting it); design.md D3_
- [x] **2.3 Migrate the domain type surface** (`AssetHoldingDTO`, `PortfolioResponseDTO`, and every
  other type deriving from `WireHolding`/`AssetHoldingDTO`) to carry `quantity: string`, per 2.1's
  boundary — this only ever touches the domain type, never `WireHolding` itself.
  _Requirements: 8.2_
- [x] **2.4 Audit every consumer of `quantity`** for direct arithmetic; convert any found to an
  explicit string→number conversion at a display boundary only (GC.2).
  _Requirements: 8.2_
- [x] **2.5 Draft quantity edits operate on the string representation** (append/replace digits); a
  derived numeric value is computed only for estimated-value display, never fed back into the draft
  or the submit payload.
  _Requirements: 8.4_
- [ ] **2.6 Cleanup follow-up, non-blocking, tied to a real retirement gate — not one deploy cycle
  (round-3 correction: one successful cycle proves nothing about rollback safety).** A single
  successful production deploy after B1 task 4.9 ships does not prove every serving environment is
  on the string producer, that CDN/client-cached rollback artifacts are gone, that B1's own rollback
  window has closed, or that the backend won't later be rolled back to the numeric serializer for an
  unrelated reason. This branch is cheap to keep and expensive to remove prematurely, so removal
  waits for an explicit retirement decision — B1's `ToPlainStringSerializer` confirmed live and
  stable across every environment this app deploys to, with no rollback window open — not a fixed
  cycle count. Until that decision, 2.1's `number` branch and 2.2's fidelity gate stay in place
  indefinitely; they cost nothing while dormant.
  _Requirements: 8.3_
- [ ] **2.7 Proposed cross-spec deployment gate — pending cross-spec approval, not settled `SHALL`
  language (round-5 correction: round 4 stated this as a normative `SHALL` on B1's own release
  process, which overstepped — requirements.md's own Open items list still calls this sequencing
  "needs explicit coordination... not just a statement that it must happen first," meaning the
  *coordination itself*, not only the ordering fact, remains genuinely unresolved between the two
  specs).** Requirement 8.3 protects `frontend/src/lib/api/portfolio.ts`'s **existing** consumer —
  the Portfolio page, unrelated to the picker, gated by neither feature flag — from B1's
  `quantity: number → string` change reaching production before this frontend can tolerate it. Task
  2.1's boundary is itself correct under either shape from the moment it ships, but it still has to
  actually **be deployed** before B1 task 4.9 is, or a real window exists where the
  currently-deployed, unmodified frontend receives strings its current code doesn't parse as
  quantities at all. **Proposed mechanism, awaiting B1-side agreement:** B1 task 4.9 would not
  deploy to production until Tasks 2.1/2.3/2.4 have already deployed — verified by confirming Wave
  2's own frontend deploy predates B1 4.9's, the same predecessor-ordering discipline this document
  already applies (there, uncontroversially, since B2 owns both sides) to B1 task 5.1 (Wave 4.5,
  Wave 6.3). **This document can propose the mechanism but cannot unilaterally bind B1's release
  process** — B2 does not own B1's deploy decisions. This item therefore stays counted among the
  header's five open items until B1's owners (or the master plan, as the cross-spec release
  authority) actually adopt this or an equivalent mechanism; adoption, not this task's existence, is
  what would close it.
  _Requirements: 8.3_

## Wave 3 — Presence (Redis-backed) · *B2-owned backend* · **source merged via PR #179 at `main@cc97a209`; Task 3.7 deploy/live proof open**

- [x] **3.1 Add a random `jti` claim to issued JWTs**, hashed one-way (`sha256`) as the session key
  at the gateway. *(Merged source-only via PR #179 at `main@cc97a209`; not deployed/live-verified.)*
  _Requirements: 6.1; design.md D4_
- [x] **3.1a Legacy no-`jti` tokens during rollout — explicit, not left to hashing behavior.**
  *(Merged source-only via PR #179.)*
  Verified directly: `JwtSigner.java:43` sets a one-hour expiry
  (`.expirationTime(Date.from(now.plusSeconds(3600)))`), so a rolling deploy of 3.1 leaves tokens
  issued *before* it live and authenticating demo traffic for up to an hour afterward, and none of
  them carry `jti`. A missing or blank `jti` claim SHALL be treated as **fail-open**: skip the
  presence write entirely (3.2) and, if that same request reaches 3.3, return
  `anotherSessionActive: false` — never hash a missing claim into a shared/placeholder key, which
  would either error or silently conflate every pre-rollout session into one entry.
  _Requirements: 6.1_
- [x] **3.2 Presence write on authenticated demo traffic, with one owned execution point.**
  *(Merged source-only via PR #179.)* Add a
  `DemoPresenceService` and invoke it from `JwtAuthenticationFilter` only after Spring Security has
  supplied a validated `JwtAuthenticationToken` and the filter has extracted usable `sub` and
  `jti` claims. Do not add an independently ordered `GlobalFilter`: that would create a new
  ordering edge between JWT authentication (`HIGHEST_PRECEDENCE + 2`) and read-only enforcement
  (`+3`) and could silently run before the principal exists. The service performs `ZADD
  presence:demo <now> <sessionKey>` plus a whole-set `EXPIRE` safety net, per the sorted-set shape
  in `design.md` D4 (not independent per-key `SETEX` entries). It is skipped per 3.1a when `jti` is
  absent/blank and for non-demo subjects.
  _Requirements: 6.2; design.md D4_
- [x] **3.3 `GET /api/presence/demo`** — a gateway-local `PresenceController`, authenticated by the
  *(Merged source-only via PR #179.)*
  existing Spring Security resource-server chain and reading its `JwtAuthenticationToken`
  directly (local controllers do not traverse Gateway `GlobalFilter`s). It is open to any
  authenticated caller (no JWT-subject restriction — unlike D5's reset endpoints). Sweeps stale members
  through one `DemoPresenceService.touchAndCheckAnother(sessionKey)` operation: touch the current
  session first (`ZADD` plus the whole-set expiry), sweep stale members, count, and exclude the
  caller's own member. This explicit touch is load-bearing because the local controller bypasses
  `JwtAuthenticationFilter`; without it, two users whose first action is opening the picker can
  both remain absent. It short-circuits to
  `false` for a non-demo caller without doing Redis work, and per 3.1a for a legacy no-`jti` caller.
  _Requirements: 6.3; design.md D4_
- [x] **3.4 Fail-open on Redis error/unavailability** (GC.5). *(Merged source-only via PR #179.)*
  _Requirements: 6.5_
- [x] **3.5 Presence is never read by, or given any effect on, the demo-reset mechanism.**
  *(Merged source-only via PR #179.)*
  **Assertion:** a source check confirms no import/call from Wave 4/Wave 8's demo-reset code into
  the presence module.
  _Requirements: 6.6_
- [x] **3.6 Requirement 6.1's core presence tests, made explicit.** *(Merged source-only via PR #179.)*
  In addition to service-level
  Redis tests, run a real gateway-chain integration test with signed JWTs and Testcontainers Redis;
  do not inject a principal directly into the presence component and thereby bypass the ordering
  contract in 3.2. Two tabs authenticating with the
  same token (same `jti`) count as one session; two independent logins (two distinct `jti` values)
  whose first post-login request is the gateway-local presence endpoint are recorded by 3.3, with
  the first response `false` and the second `true`; a request carrying a legacy no-`jti` token never fails or errors — it is treated
  exactly per 3.1a, and the request it rides on proceeds normally regardless.
  _Requirements: 6.1_

- [ ] **3.7 STOP/GO — deploy and live-probe presence before Wave 10 can cite it.** **Go:** 3.1-3.6
  green; the resolved TTL is supplied as configuration; api-gateway is deployed to Azure; two
  independent demo logins produce distinct `jti` values; after authenticated traffic from both,
  an identity-bearing call to `GET /api/presence/demo` returns `200` and
  `anotherSessionActive:true` for either caller while excluding its own session. The probe also
  confirms a non-demo caller returns `false` without a Redis write. Legacy-no-`jti` and
  Redis-unavailable fail-open behavior remain deterministic integration-test obligations in 3.6,
  not destructive production fault injections. Record the gateway revision and configured TTL.
  **Abort:** do not let Wave 10 describe Wave 3 as deployed/live-verified without this evidence.
  _Requirements: 6.1, 6.2, 6.3, 6.5_

**TTL decision settled (2026-08-29):** default **150 seconds** via `APP_DEMO_PRESENCE_TTL` /
`app.demo-presence.ttl`; whole-set key expiry adds **30 seconds** for orphan cleanup only (design.md
D4). Wave 3 backend source merged via PR #179 at `main@cc97a209` — **not** deployed, live-probed, or
flagged complete. Task 3.7 STOP/GO deploy/live evidence remains a later owner action.

## Wave 4 — Demo-reset, portfolio-service side · *design.md D5 Stage 1* · **Tasks 4.1–4.4a merged source-only via PR #180 at `main@63fc058`; Task 4.5 open**

Purely additive and safe to verify at length: nothing yet routes real user traffic to this endpoint
until Wave 5 ships.

- [x] **4.1 Track the full B1 Wave 4 cluster this wave consumes — not one task, five (round-2
  correction: naming only 4.1 under-scoped what Task 4.4 below actually asserts; round-3 fixed this
  heading's own count against the five items actually listed).** All five **verified on
  `main@cc97a209`** before Wave 4 implementation (focused unit/integration tests green); none
  built here, all hard predecessors:
  - **B1 4.1 `HoldingReplacementService`** — `portfolio-service/.../HoldingReplacementService.java`;
    `HoldingReplacementServiceTest`, `HoldingReplacementServiceIT`.
  - **B1 4.3 `GoldenStateTuplePreparer`** — `GoldenStateTuplePreparer.java`; `GoldenStateTuplePreparerTest`.
  - **B1 4.7 error envelope** — `ContractError.java`, `ContractErrorCode.java`,
    `GlobalExceptionHandler.handlePortfolioVersionConflict`; `CompositionErrorContractTest`,
    `CompositionErrorEnvelopeTest`.
  - **B1 4.9 decimal fidelity** — `ToPlainStringSerializer` on `PortfolioResponse.HoldingResponse.quantity`;
    `StrictDecimalFidelityTest`, `DecimalFidelityIT`, `PortfolioResponseVersionTest`.
  - **B1 4.10 `version` on `PortfolioResponse`** — `PortfolioResponse.version` plus
    `PortfolioService.toResponse` mapping; `PortfolioResponseVersionTest`,
    `PortfolioServiceVersionMappingTest`.
  _Requirements: 7.2, 7.3_
- [x] **4.2 `DemoResetService.reset(expectedVersion)`** — *(Merged source-only via PR #180 at
  `main@63fc058`; not deployed or routed.)* calls B1's `replace(DEMO_USER_ID,
  expectedVersion, intent: [], preparer: GoldenStateTuplePreparer(app.demo.cost-basis-anchor))`.
  Target is a compiled-in constant, mirroring B1's `E2E_USER_ID` pattern — never a caller-supplied
  id (GC.6-adjacent: this is the "no re-read, server-fixed target" half of D5's design).
  **`intent: []` is correct as written, not a placeholder to fill in (round-7 clarification — a
  P0-severity misreading surfaced during review, resolved by clarifying design.md D5 in place, see
  its pass-22 note): given this deliberately empty, D2-validation-passing raw-intent list,
  `GoldenStateTuplePreparer` derives its own full desired-holdings tuple internally from the active
  catalog and ignores current stored state (B1 `design.md` D3) — the catalog-derivation logic lives
  inside the preparer, not in a list this task constructs. `intent` still passes through D2's
  earlier semantic/catalog validation steps unconditionally; an arbitrary or malformed intent would
  still fail there, this call site's is simply vacuously valid. Do not "fix" this by building a
  separate desired-holdings list and passing it as `intent`; that would duplicate logic B1's own
  preparer already owns and risk the two diverging.**
  Test 2 (4.4) SHALL assert the resulting holdings match the exact golden set, which is what proves
  this in practice, not a re-derivation of the mechanism.
  **Emits a structured success event, and only on success (round-15 addition — no prior task
  assigned ownership of this, and Task 8.9's live causal-correlation proof depends on it existing):**
  after `HoldingReplacementService.replace` returns successfully, log one structured event at `INFO`
  (`event=demo_reset_succeeded`) carrying the resulting `version` — no explicit correlation field
  needs adding, since the active span's trace id is already attached via Boot's default MDC
  correlation pattern (round-14, present whenever a `Tracer` bean exists) whenever tracing is active
  for the request. Emit nothing on `409`/conflict or any other failure path — an event logged on
  every attempt regardless of outcome would make the trace-id correlation Task 8.9 depends on
  ambiguous instead of proof.
  _Requirements: 7.2, 7.3; design.md D5 (pass 22)_
- [x] **4.3 New internal controller** — *(Merged source-only via PR #180 at `main@63fc058`; not
  deployed or routed.)* `/api/internal/portfolio/demo-reset`, one method mapped to
  **both** `POST` and `PUT` from the start (there is no `POST`-only predecessor to widen), protected
  by the existing `InternalApiKeyFilter`.
  _Requirements: 7.3; design.md D5_
- [x] **4.4 Test 2 — a real Testcontainers integration test through the actual chain, not an MVC
  slice that can fabricate the proof (round-8 correction: an MVC-slice test can mock
  `DemoResetService` itself, return a hand-built golden-looking response, and never touch
  `HoldingReplacementService`, `GoldenStateTuplePreparer`, the catalog, or persistence at all — every
  assertion below would still pass against a controller that does nothing real).** *(Merged source-only
  via PR #180 at `main@63fc058`; `DemoResetIntegrationTest` green; not deployed or routed.)* Spring +
  Testcontainers (Postgres), exercising the genuine
  `DemoResetService → HoldingReplacementService → GoldenStateTuplePreparer → Catalog_Module →
  persistence` chain end to end — **no mocking of any component in that chain; a spy on
  `DemoResetService`, not a stub, if call-count verification is needed**, so the spy still delegates
  to the real implementation rather than replacing it. **Configure a non-blank
  `app.internal.api-key` test property and send the matching `X-Internal-Api-Key` explicitly
  (round-9 addition — a real gap, not a nicety: `InternalApiKeyFilter`'s default is blank, which
  fails every `/api/internal/**` request with `503` before the controller is ever reached
  (`InternalApiKeyFilter.java:41-46`); without this, no assertion below runs against anything).**
  **`POST` and `PUT` each get their own fresh non-golden fixture, observed version, and reset spy —
  not one shared seed and one shared spy across both calls (round-12 correction: seeding once and
  calling both verbs in sequence means the second call's starting state is whatever the first
  call's reset already produced — golden, not the original non-golden seed — so the two verb
  proofs aren't actually equivalent, and a spy shared across both calls reporting "invoked twice"
  doesn't establish "invoked exactly once *per verb*," which is what this test claims to prove).**
  Either parameterize the whole test over `{POST, PUT}`, or duplicate the fixture/spy/assertions
  once per verb: **seed the demo portfolio to a deliberately non-golden state**
  (different tickers/quantities than the target), then call the internal endpoint. Authenticated
  `POST` and `PUT`, each against its own fresh fixture, each invoke the
  same controller method and the same `DemoResetService.reset(...)` call exactly once (the spy's
  call count, not a stub's). Asserts the full observable contract: a matching `expectedVersion`
  returns `200` with a `PortfolioResponse` whose holdings match the golden set — **split by what's
  actually wire-visible, not one blanket "response and persisted rows" claim (round-11 correction:
  `PortfolioResponse.HoldingResponse` currently declares only `id`, `assetTicker`, `quantity` —
  verified directly, `PortfolioResponse.java:33-37` — no cost-basis, currency, source, or
  anchor-derived field crosses the HTTP boundary at all; B1 adds `version` and decimal-string
  serialization to this response, not cost-basis fields).** Response-body assertion is scoped to
  ticker set and quantity only, matching what the wire type actually carries; **the complete
  tuple — quantity, cost basis, currency/source, and the anchor-derived value — is asserted only
  against the persisted repository rows**, which do carry it. **Checked against an
  independently-derived oracle, not catalog membership alone** (querying `Catalog_Module` alone
  proves the ticker *set* matches, not that each value was computed correctly): the oracle SHALL
  compute the complete expected tuple from raw catalog data, the demo UUID, and
  `app.demo.cost-basis-anchor`, **reproducing B1's frozen deterministic formulas as separate,
  test-only code — never by calling the production implementations that compute them (round-11
  correction: an earlier draft prohibited `GoldenStateTuplePreparer`/`DemoResetService`/
  `HoldingReplacementService` but left `PortfolioSeedService.computeDeterministicCostBasis` and
  `DeterministicPriceCalculator.compute` — both real, existing classes — unprohibited; calling
  either from the oracle would make the assertion tautological just as surely as calling the
  preparer would, since both are exactly the functions B1's design commits `GoldenStateTuplePreparer`
  to reusing internally)**. Every production transformation/helper in that chain is off-limits to
  the oracle, full stop — not only the three named in the earlier draft. A stale `expectedVersion`
  returns `409` with
  `error: "portfolio_version_conflict"`, a `message` string, and `currentVersion` equal to the
  actual current version — asserted as structured field values against B1's documented envelope
  shape (`design.md` D2's citation of B1 `design.md` D7 — semantic field equality, not byte-for-byte
  JSON comparison against a specific B1 test, since B1's own contract-test task is itself unchecked);
  and **both price tables are byte-identical before and after, sentinel rows included — matching
  B1's own `P10` regression discipline (`portfolio-composition-contract/tasks.md` task 6.4), not the
  weaker "writes no row" this task previously specified (round-9 correction)**. **A separate, thin
  MVC slice may additionally cover just the dual-verb-routing shape** (both `POST` and `PUT` resolve
  to the same controller method) if useful for fast feedback, but it does not substitute for this
  integration test — the golden-set/catalog assertions live here, not there.
  **Also asserts the log-event contract Task 8.9 depends on (round-15 addition):** a successful
  reset (either verb's fixture above) emits exactly one `demo_reset_succeeded` event (4.2) carrying
  the correct resulting version; the `409` fixture emits none. Captured via a test log appender
  (e.g. Logback's `ListAppender`) attached for the duration of each case, not inferred from the
  service's return value. **The success case's request carries a known, test-minted `traceparent`
  header, and the captured event's own `ILoggingEvent.getMDCPropertyMap().get("traceId")` SHALL equal
  that header's trace id (round-16 addition — 8.7a proves 8.3/8.5 propagate trace context *out* of
  api-gateway, and this proves portfolio-service's own log statement correctly captures whatever
  trace id arrives *in*; neither previously proved the other, and 8.9's live query depends on both
  halves of that chain holding, not just the event existing). This assertion requires the real
  observation/tracing topology, not a bare service call or a `MockMvc`-only slice (round-17 addition
  — a minted `traceparent` only reaches the log MDC if tracing is genuinely active and the request
  actually passes through the instrumented filter chain): `@SpringBootTest(webEnvironment =
  WebEnvironment.RANDOM_PORT)` with `@AutoConfigureTracing(export = false)` (Spring Boot 4.1's
  `org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing`, the
  documented mechanism for enabling tracing in a test context; **`export = false` explicit, round-18
  addition — the annotation's own default is `export = true`, which would attempt a real OTLP export
  this test neither needs nor has a collector for; the goal here is spans/MDC being genuinely
  created, not exported. Requires `testImplementation
  'org.springframework.boot:spring-boot-micrometer-tracing-test'` in portfolio-service's
  `build.gradle` (round-18 gap, exact coordinate round-20 — verified directly: this module supplies
  the annotation and neither service currently resolves it).**) explicitly applied, and the request
  issued as a real HTTP call against the random port (`TestRestTemplate`/`WebTestClient`), not via
  `MockMvc` or a direct method call on the controller/service.**
  _Requirements: 7.1, 7.3b; design.md D5 (Test 2)_
- [x] **4.4a One shared, independent golden-state verification oracle for every later probe.**
  *(Merged source-only via PR #180 at `main@63fc058`; `scripts/derive_demo_golden_state.py` +
  `scripts/tests/test_derive_demo_golden_state.py` green; not deployed or live-probed.)*
  Add
  `scripts/derive_demo_golden_state.py`, a stdlib-only test/operations tool that reads the raw active
  catalog, fixed demo UUID, and configured cost-basis anchor and independently reproduces B1's
  frozen deterministic formulas. It SHALL NOT import or invoke production Java helpers. Its
  canonical JSON output contains both the wire-visible `{assetTicker, quantity}` set and the full
  persisted tuple used by 4.4. Task 4.4 tests this tool against fixed vectors and uses its output;
  Tasks 8.9, 9.8, and 10.2 use the same executable for their wire-visible golden-set assertions.
  This is test tooling, not a production source of truth: production continues to derive golden
  state through `GoldenStateTuplePreparer`. CI fails if catalog evolution changes the expected set
  without the independent oracle and its fixed-vector tests being updated together.
  _Requirements: 7.1, 7.3b; design.md D5_
- [ ] **4.5 STOP/GO — verify directly against the deployed environment, with an explicit probe
  sequence (round-3 addition — the previous draft didn't specify where `expectedVersion` for the
  probe itself would come from).** Requirements.md 7.3 prohibits observing the version any way other
  than a real `GET /api/portfolio` call — never a dedicated version endpoint, never a re-read inside
  the reset call itself. B1 task 4.10 (adding `version` to the `PortfolioResponse` DTO) does **not**
  by itself put that field on the wire — B1 Wave 4's own heading says its code "becomes reachable
  only in Waves 5-7"; **B1 task 5.1 specifically** is what exposes `version` on the live authenticated
  `GET /api/portfolio` route. This probe is therefore also gated on B1 task 5.1, not only the Wave
  4.1 cluster. **Probe sequence:** (1) authenticate as the demo user; (2) call the real, deployed
  `GET /api/portfolio`; (3) extract `version` from the response for the demo user's own portfolio;
  (4) call the deployed internal endpoint with `X-Internal-Api-Key` and that exact observed version;
  (5) assert the response matches Task 4.4's contract. **Go:** 4.4 green, B1 task 5.1 confirmed live,
  plus this exact probe sequence executed successfully against the deployed environment. **Abort:**
  do not proceed to Wave 5; nothing yet forwards user traffic here, so no rollback is needed beyond
  not deploying Wave 5.
  _Requirements: 7.3; design.md D5 (rollout stage 1-3)_

## Wave 5 — Demo-reset, manual-reset gateway bundle · *design.md D5 Stage 4, gated on Wave 4*

One deployable unit (GC.10): the filter, the route, and the allowlist entry ship together, or the
demo user's own click 403s before the new filter ever runs.

- [ ] **5.1 `DemoResetAuthorizationFilter`** (`GlobalFilter`, `Ordered.HIGHEST_PRECEDENCE + 4`) —
  reads the JWT principal directly (not via `JwtAuthenticationFilter`'s injection); confirms the
  matched route id is exactly `demo-reset-manual` via `GATEWAY_ROUTE_ATTR` (never
  `GATEWAY_PREDICATE_ROUTE_ATTR`, always null by dispatch time); on a JWT-subject match, strips any
  caller-supplied `Authorization`/`X-User-Id`, obtains `INTERNAL_API_KEY` from the **shared
  `InternalApiKeyProvider` bean (Task 5.1a — round-22 introduced the shared read, round-23 moved its
  ownership into a standalone task, since a 5.1-owned provider consumed by Task 8.5 silently made
  Wave 8's build depend on Wave 5, contradicting Wave 8's own declared independence)** via
  constructor injection, never a filter-local read. Fails closed with
  `503 internal_api_key_not_configured` — requirements 7.3a's pinned two-field body, `error` plus
  `message`, exactly (round-44: the body shape is load-bearing, not incidental — Task 8.9's probe
  diagnosis discriminates this gateway-emitted `503` from portfolio-service's single-field
  analogue by exactly this field, so 5.3a/5.5 assert it) — and no downstream call if the provider's
  value is null/blank, otherwise strips any caller-supplied `X-Internal-Api-Key` and sets the
  authoritative value (replace, never append); on a mismatch, returns the pinned `403
  demo_reset_forbidden` body before the route is reached. **Sets an `X-Gateway-Replica-Token` response
  header — the opaque `replicaToken` from the shared `ReplicaTokenProvider` bean (Task 5.1b,
  round-33; **never the raw `CONTAINER_APP_REPLICA_NAME` value itself, round-34 — this route is
  reachable by anyone who can authenticate as the demo account, which this feature treats as
  effectively public, so the header carries the derived token 5.1b's own accessor returns, not
  Azure's internal replica identifier**; blank where
  the platform doesn't provide the underlying variable), injected via the constructor, never
  a filter-local `System.getenv` read, so this header and 8.7's `replicaToken` event field are
  guaranteed to be the same normalized token that Task 8.9's diagnosis compares —
  on every response this filter itself produces — the `503`, the `403`, **the missing-route-attribute
  and wrong-route-id rejections**, and the ordinary
  proxied/downstream-status path alike (round-31 addition, provider ownership and the rejection
  branches added round-33 — Task 8.9's manual-reset-probe
  diagnosis needs to know which replica served a given probe call, and nothing in this task
  previously created any observable record of that at all: no log statement is specified here, so
  the round-30 diagnosis's assumption that "the log backend" would hold a trace-correlated record
  of the probe's outcome had no actual emitter behind it. A response header sidesteps that gap
  entirely — it needs no logging pipeline, no query, no ingestion delay, and is exactly the kind
  of synchronous, always-available signal this document already prefers over log-backend queries
  elsewhere (see the documented Azure Log Analytics ingestion-gap precedent this task's own step 6
  cites repeatedly): the caller reads its own probe response directly, at the instant it's
  received).** **On the proxied path, this header is set authoritatively — the filter's own
  value SHALL replace, never merge or append to, anything already present once the downstream
  response headers are available, and the response carries exactly one `X-Gateway-Replica-Token` value
  (round-32 correction — a real gap: "sets a header" alone doesn't establish ordering or
  singularity against Spring Cloud Gateway's default header-copy behavior on the proxied path,
  and this value directly authorizes production rollback via Task 8.9, so an unauthoritative
  write — one that could be silently overwritten *by* the downstream copy step, or duplicated
  into two header instances if a downstream response coincidentally carried the same header name
  — would corrupt the one signal that diagnosis depends on).** Needs its own literal copy of
  `DEMO_USER_ID` (api-gateway cannot import portfolio-service's constant across the process
  boundary) — flagged so the two don't drift silently. **Not a JUnit "contract test" (round-10
  correction: `api-gateway/build.gradle` depends only on `common-dto`/`common-observability`, not
  portfolio-service, so a same-process equality assertion cannot compile — it would collapse into
  comparing two hardcoded literals written in the same file by the same author, proving nothing
  about drift). Instead, a text-level CI check** — a small script (or a Gradle task reading both
  files as plain text) that greps `DemoPortfolioInitializer.java` and this filter's own source for
  their respective UUID literals and asserts the strings are equal — which needs no cross-module
  Java dependency at all, since it never compiles or loads either class. **Three-way, not two-way
  (round-11 correction: comparing only api-gateway's and portfolio-service's own literals lets both
  drift together, silently, away from the identity that actually gets issued — the check would stay
  green while being wrong).** The same script SHALL also grep
  `portfolio-service/src/main/resources/db/migration/V15__Reconcile_Auth_Seed_Users.sql` for the
  literal it inserts into `users.id` for the demo account and assert all three match — V15 is the
  actual source of truth for the identity a real login issues a JWT `sub` claim for; the other two
  are downstream copies of it, not independent facts. Run this check in CI, not merely documented
  as a manual step. design.md's own text already named "a single source both build-time-inject
  from" as an alternative to a comparison entirely; this three-way check is the more conservative
  fix, closer to what was already specified, rather than a larger restructuring.
  _Requirements: 7.3a; design.md D5; GC.6, GC.7_
- [x] **5.1a `InternalApiKeyProvider`** — **merged source-only via PR #202 at `main@64761dc2`**
  (not deployed). A
  standalone api-gateway `@Component` that resolves
  `System.getenv("INTERNAL_API_KEY")` exactly once and exposes the resolved value (plus a
  `isConfigured()` non-blank check) to every consumer via constructor injection, package-visible for
  test doubles — mirroring `CloudFrontOriginVerifyFilter`'s own resolve-once pattern. **Owned here as
  its own task, deliberately outside both 5.1's filter and 8.5's self-call (round-23 correction —
  round-22 introduced the shared read but put its ownership inside 5.1, which silently made Wave 8's
  build depend on Wave 5 while Wave 8's own intro still declared independence from it: an impossible
  build graph. This task has no dependencies of its own — it is a single env-reading class, mergeable
  immediately — so both 5.1 and 8.5 can list it as a build prerequisite without either wave
  depending on the other's bundle or deployment; api-gateway deployments are cumulative, so whichever
  wave deploys first carries it).** Consumed by: 5.1 (`DemoResetAuthorizationFilter`) and 8.5 (the
  login self-call's reset leg). Unit-tested here directly for the null/blank/set resolution states —
  the consumers' own tests then inject this provider (or a test double of it), never a raw value
  (see 5.5).
  **This task also ships a separate, non-disclosing presence probe that Task 8.9's rollback
  diagnosis invokes in the deployed container (round-33 addition — 8.9 must compare what the raw
  environment actually holds against what this provider concluded, and round-32's shell
  approximation of the blank test cannot do that correctly: POSIX `[:space:]` in `tr` is
  locale-dependent and byte-oriented, while `String.isBlank()` is defined over Unicode code points
  via `Character.isWhitespace`, so the two genuinely disagree on exotic whitespace — and a
  disagreement there would authorize a production rollback of correct code):** a tiny
  `main`-bearing class in this same module, `InternalApiKeyPresenceProbe`, that reads
  `System.getenv("INTERNAL_API_KEY")` itself and prints exactly one word — `blank` or `nonblank` —
  by applying `String.isBlank()` (with `null` treated as blank), then exits. It prints no value, no
  length, and no hash, satisfying the non-disclosure procedure (Task 8.9) by construction rather
  than by operator discipline. **It SHALL NOT call, import, or share resolution code with
  `InternalApiKeyProvider`**: its entire diagnostic value is being an *independent* second read
  that happens to apply the identical JDK predicate — sharing code would make it agree with the
  provider by construction, which is exactly the tautology 8.9's diagnosis exists to avoid.
  **Packaged as its own plain, non-Boot executable jar — never invoked through the Spring Boot fat
  jar 8.9 otherwise never touches (round-34 correction — a real gap: api-gateway's Azure image
  (`api-gateway/Dockerfile.azure:65,69`) contains exactly one jar, `app.jar`, produced by
  `bootJar` and launched via Spring Boot's own loader, whose `Main-Class` is a launcher that
  unpacks `BOOT-INF/classes`/`BOOT-INF/lib` at runtime — a probe class placed in that same source
  tree is *inside* `BOOT-INF/classes` inside the fat jar, and a plain `java -cp app.jar
  ProbeClass` cannot find it there: `-cp` treats the jar as an ordinary flat classpath entry and
  never unpacks the nested layout, so the command this task previously specified would fail with
  `ClassNotFoundException` on every invocation).** A dedicated Gradle `Jar` task, **named
  `probeJar`** (not `bootJar`) in
  this module builds `probe.jar` — a minimal, standard-layout jar (`InternalApiKeyPresenceProbe`
  at its root, a plain `Main-Class` manifest entry, zero dependencies, since this class touches
  only `System.getenv` and `String.isBlank()`) — kept independent of the fat jar specifically so
  this task depends on no Boot-loader internal, version-specific detail at all. **Both jars are
  given fixed, deterministic filenames via each task's own `archiveFileName`
  (`bootJar { archiveFileName = 'app.jar' }`, `probeJar { archiveFileName = 'probe.jar' }`), and
  the build explicitly invokes both — round-35 correction, a two-part build-graph defect: (a)
  `Dockerfile.azure`'s builder stage (line 52) runs only `./gradlew :api-gateway:bootJar
  --no-daemon`, so a `probeJar` task nothing invokes would simply never run, and `probe.jar` would
  not exist to copy; (b) the round-34 text also left the existing `COPY --from=builder
  /workspace/api-gateway/build/libs/*.jar app.jar` (line 65) untouched, but that wildcard would,
  once both jars exist in the same `build/libs/` directory, match *both* — and Docker's `COPY`
  rejects multiple source matches against a single-file destination, failing the build the moment
  `probe.jar` starts existing alongside it, which is the opposite of "stays exactly as it is."
  Fixed names remove the wildcard's ambiguity and let both problems be fixed together**:**
  `Dockerfile.azure`'s builder `RUN` line (52) invokes
  `:api-gateway:bootJar :api-gateway:probeJar --no-daemon` at minimum, and
  the single wildcard `COPY` (line 65) is replaced by exact-path copies for `app.jar` and
  `probe.jar` — in the same
  runtime stage; the image's `ENTRYPOINT` is unaffected. 5.1b extends this same `RUN` line and
  `COPY` set with a third task and artifact, `replicaTokenJar`/`replica-token.jar` — see 5.1b for
  the complete, final three-task form; this task establishes the mechanism, 5.1b's own addition is
  not a second, independent build-graph fix.** 8.9 invokes the probe as `java -jar
  /probe.jar` in the
  target replica — a separate JVM from api-gateway's own, but the same container image, JDK, and
  container-scoped environment the provider itself reads (round-35 correction below fills in the
  precise claim; see 8.9's own invocation text), with no shell string semantics and no
  Boot-loader involved anywhere.
  **A named CI job, `azure-image-smoke-test`, added to `.github/workflows/ci-verification.yml`
  and required by Wave 5's own Go gate (5.6, below), builds the actual Azure image and runs this
  exact command against it, asserting the two known outputs (round-34 addition, given an owner
  round-36 — a real gap: naming a smoke test in prose, with no Gradle task or workflow job
  actually invoking it, and no gate requiring that job to be green, is precisely "specified only
  in prose, never exercised against the real built artifact" — the exact defect class this
  sentence already exists to warn about, just one level removed: nothing currently builds
  `Dockerfile.azure` anywhere in CI at all — `ci-verification.yml`'s own `docker-build-verify` job
  runs `docker compose build`, which `docker-compose.yml` wires to `api-gateway/Dockerfile`, the
  AWS variant, never `Dockerfile.azure` — so this task's entire packaging fix could be
  implemented, merged, and silently regress later with nothing in CI positioned to catch it):**
  `docker build -f api-gateway/Dockerfile.azure -t probe-smoke-test .` (repo root, matching the
  file's own documented build context), then, **for each of the two cases, a single self-contained
  invocation with no separate lifecycle to manage (round-36 correction — an earlier draft ran the
  *complete* gateway via `docker run -d` merely to reach the unrelated probe inside it: needlessly
  coupling this test to the gateway's own startup — which needs a full profile of unrelated env
  vars (`DATABASE_URL`, `AUTH_JWT_SECRET`, Redis/Kafka/MongoDB connectivity, per
  `ci-verification.yml`'s own `docker-build-verify` job) to boot cleanly at all — with no
  running-state check, no container-ID capture, and no unconditional cleanup specified; a gateway
  startup failure would then read as a probe-packaging failure, and a successful run could leave
  containers behind. Task 8.9's own `az containerapp exec` procedure already covers the
  production-realistic, already-running-replica case at higher fidelity, against a real live
  replica — this test's only job is proving the packaging and launch mechanism, which needs
  neither the gateway's dependencies nor persistent container state)**. Pin two literal commands,
  not an angle-bracket shell placeholder: `docker run --rm --entrypoint java -e
  INTERNAL_API_KEY= probe-smoke-test -jar /probe.jar` expects `blank`; `docker run --rm
  --entrypoint java -e INTERNAL_API_KEY=smoke-test-value probe-smoke-test -jar /probe.jar` expects
  `nonblank`. `--entrypoint java` overrides the image's own `["java", "-jar", "/app.jar"]`
  entrypoint directly rather than appending arguments to it (the exact ambiguity round-35
  corrected for this test's *invocation*, now closed by construction rather than by a two-step
  run/exec sequence), and `--rm` removes the container unconditionally on exit, on either case,
  with no separate cleanup step.** Assert stdout equals exactly `blank`/`nonblank` and exit code
  `0`, for the blank and non-blank cases respectively. **Task 5.1b later adds a third invocation to
  this same job; that invocation is not part of Task 5.1a's initial PR.** Once 5.1b is implemented,
  the third case proves `ReplicaTokenTool` actually exists in the built image and computes
  correctly, not merely that its own unit tests pass in isolation (round-37 correction — a real
  gap: 5.1b claimed this
  tool was "covered by the same `azure-image-smoke-test` job," but nothing in this job's own
  description ever invoked or asserted anything about it; the job as written proved only the
  original presence probe's packaging, leaving a missing jar, a missing `ReplicaTokenFormula`
  class, a wrong manifest, or broken argument/stdout handling all able to pass this gate
  regardless):** `docker run --rm --entrypoint java probe-smoke-test -jar /replica-token.jar
  api-gateway--0000000-abcdefg` — 5.1b's own literal test vector, round-38, not an unpinned
  placeholder — with stdout and stderr captured separately, asserting stdout matches exactly `^[0-9a-f]{12}\n$` **and equals the literal value
  `95ca17821ade`**, **stderr is byte-empty** (round-39 — 5.1b's own contract says "nothing else
  on stdout or stderr on success," but this job previously asserted only stdout and exit code,
  silently leaving the stderr half of that same contract unchecked), and
  exit code `0` — the identical fixture value both this job and 5.1b's unit test assert against,
  so a mismatch between them is itself detectable rather than two independent guesses at "the
  right answer."**
  **Tested at two levels, since neither alone proves the whole executable's behavior (round-34
  correction — "unit-tested... for the same four states" previously implied ordinary in-process
  JUnit tests, but `System.getenv()` is effectively immutable within a running JVM (no public
  mutator; reflection-based workarounds are unreliable and increasingly blocked by the module
  system from JDK 17 onward), so an in-process test cannot actually set the four input states at
  all — it could only unit-test a same-process string predicate and *assume* `main` wires
  `System.getenv` and stdout to it correctly, which is precisely the untested assumption a
  probe whose entire job is reading the real process environment cannot afford):** (a) the
  classification logic is factored into a package-visible, pure static method (`String → "blank"`
  or `"nonblank"`, `null` treated as blank) and unit-tested directly for the four states, no
  environment involved; (b) `main`'s actual behavior — that it reads `System.getenv` and prints
  the classification to stdout, nothing else — is proven by launching the compiled class as a
  genuine child process (`ProcessBuilder`, `.environment()` explicitly set per case, `.environment()
  .remove(...)` for the unset case) for each of the four states, asserting stdout equals exactly
  the expected word plus a single literal `\n` and nothing else — emitted via
  `System.out.print(word + "\n")`, never `println` (round-44, the same Windows-`\r\n` rationale
  5.1b's output contract states) — and exit code `0` — this is the standard, always-available
  way to test environment-reading code, since it controls a fresh process's environment rather
  than attempting to mutate the current one. **Level (b) proves the wiring in-process, fast; the
  image smoke test above proves the same wiring survives packaging into the actual deployed
  artifact — deliberately redundant with different failure modes, not the same check twice.**
  _Requirements: 7.3, 7.3a; design.md D5 (round-23 amendment)_
- [ ] **5.1b `ReplicaTokenProvider` — implemented but unmerged on `feat/b2-task-5-1b-replica-token-provider`
  (not deployed). The single owned source of the replica identity both emitters
  publish** (round-33 addition — a real gap, and precisely the defect class Tasks 5.1a and 8.2a
  were created to fix, reintroduced by round-31's own header work: 5.1's `X-Gateway-Replica-Token`
  response header and 8.7's `replicaToken` event field were each described as reading
  `CONTAINER_APP_REPLICA_NAME`, with no shared owner — two independent reads free to normalize
  differently (trimming, blank handling, fallback), while Task 8.9's entire probe-correlation
  diagnosis rests on the two being *comparable strings*. Round-31 even had 5.3a inject "a test
  double for the replica-name source" that no task defined). **Renamed from `ReplicaNameProvider`,
  its own header field from `X-Gateway-Replica` to `X-Gateway-Replica-Token`, and 8.7's event
  field from `replicaName` to `replicaToken` (round-36 correction — since round-34 this class,
  header, and field have carried a derived hash, never Azure's raw replica name; keeping
  "Replica-Name"/"replicaName" in their own identifiers recreated, inside this document's own
  vocabulary, exactly the operator ambiguity — "is this the real name or not?" — that the
  token-recovery step two tasks later exists to resolve. Nothing has shipped yet, so this is a
  plain rename, not a compatibility-aliasing decision: no consumer exists to preserve
  compatibility for.)** A standalone api-gateway
  `@Component` mirroring 5.1a's shape: resolves
  `System.getenv("CONTAINER_APP_REPLICA_NAME")` exactly once, and exposes **not that raw value,
  but a derived, one-way `replicaToken` — the only thing this component's public accessor ever
  returns (round-34 correction — a real gap: the raw value this component resolves is Azure's own
  internal replica identifier, and both consumers publish whatever this accessor returns
  externally — 5.1's response header on a route reachable by anyone who can authenticate as the
  demo account, which this feature's own design treats as effectively public credentials, and
  8.7's event, an internal log line never publicly reachable. The raw form was fine for the
  event; publishing it verbatim through the public header discloses Azure Container Apps'
  internal replica-naming convention to that entire public audience for a value whose only actual
  need is an equality check, not a legible identifier)**. Normalizes in exactly one place — a
  null or blank environment value resolves
  to one canonical blank (never hashed — hashing an empty string would produce a specific
  non-empty token, breaking the "blank means not on this platform" convention both consumers and
  Task 8.9 rely on throughout), so that "blank" means the identical thing in the header and in the
  event; a non-blank raw value resolves to **`replicaToken` = the first 12 hex characters of the
  SHA-256 digest of the raw value's UTF-8 bytes, matching `^[0-9a-f]{12}$` exactly, never a longer
  or differently-cased string** — unkeyed (no new secret to provision or
  rotate), stable for the process's lifetime, and computable by anyone who separately holds the
  raw value (an authorized operator, never a public caller, per Task 8.9 below) but not invertible
  from the token alone. **One literal vector, fixed here and reused everywhere this formula's
  correctness is checked — this provider's own test, `ReplicaTokenTool`'s test, and the
  `azure-image-smoke-test` job's third case (5.1a) — so no two of them can silently disagree
  about what "correct" means (round-38 addition — a real gap: "fixed input/output test vectors"
  and "a fixed, known raw replica name" were both left abstract, leaving each site free to pick
  its own value with no guarantee of agreement): raw name `api-gateway--0000000-abcdefg` →
  `replicaToken` = `95ca17821ade` (`SHA256("api-gateway--0000000-abcdefg")` =
  `95ca17821ade327267eaa7312e5655ade1e5a84a08eaf1a3c9f909c033835609`, first 12 hex characters —
  independently computed and verified via two separate tools before being recorded here, not
  asserted from memory).** Published byte-identically by both consumers. Package-visible for test
  doubles, which return a canned token string directly — nothing that consumes this bean, in
  production or in a test, ever sees or needs to reproduce the hash. **The hash itself lives in
  one shared, standalone static method, `ReplicaTokenFormula.compute(String rawName)` — a
  zero-dependency utility class in this same module, called by this provider and by nothing
  else in production, plus one operator-facing consumer (round-36 correction — Task 8.9's
  original text told the operator to "locally compute the identical formula" by hand, in a shell
  one-liner reimplementing UTF-8 encoding and SHA-256 truncation from a prose description; any
  divergence — a stray newline from `echo` vs `printf`, a locale-dependent encoding, a
  copy-paste slip in the truncation length — silently makes every future token comparison fail,
  and the fail-closed rule this same task adds means that failure mode is indistinguishable from
  a genuine zero-match observation: permanently blocking exposure with no way to tell "the
  formula was reimplemented wrong" from "this really is unattributable." A shared method removes
  the reimplementation entirely — there is exactly one formula, called by both the runtime and
  the operator, so they cannot drift apart by definition, not merely by agreement under test).**
  **`ReplicaTokenTool` executable contract.** Package a second minimal artifact that accepts one
  raw replica name, calls the shared `ReplicaTokenFormula.compute`, and writes exactly one
  lowercase 12-hex token plus literal `\n` to stdout, nothing to stderr, exit `0`; invalid
  invocation is nonzero. A dedicated `replicaTokenJar` task produces `replica-token.jar`.
  `Dockerfile.azure` explicitly invokes `bootJar`, 5.1a's `probeJar`, and `replicaTokenJar`, then
  copies `/app.jar`, `/probe.jar`, and `/replica-token.jar` by exact paths—no wildcard.
  Unit tests reuse the single fixed vector `api-gateway--0000000-abcdefg → 95ca17821ade`, cover
  unset/blank/set provider states, and assert the packaged tool's exact stdout/stderr/exit contract.
  `ReplicaTokenFormula` produces for a known raw input. **Build dependency: Task 5.1a must merge
  first.** This task's final Docker `RUN` and `COPY` contract invokes 5.1a-owned `probeJar` and
  copies 5.1a-owned `probe.jar`, and it extends 5.1a's `azure-image-smoke-test`; claiming independent
  mergeability would make those references unresolved when 5.1b lands first. The provider class is
  logically standalone, but the task's packaged deliverable is not. Consumed by
  5.1 (Wave 5, the response header) and 8.7 (Wave 8, the event field), so it SHALL NOT live inside
  either, for the same build-graph reason 5.1a was extracted round-23. Both consumers' tests
  inject this provider or a double
  of it, never their own `System.getenv` call.
  _Requirements: 7.3a, 7.3c; design.md D5 (round-33)_
- [ ] **5.2 `demo-reset-manual` route** — `Path=/api/portfolio/demo-reset` + `Method=PUT`, explicit
  `order: -1` (never relying on list position), `RewritePath` to
  `/api/internal/portfolio/demo-reset`, in both `application.yml` and `application-prod.yml` (the
  prod file redefines the whole route list, not merges it — the prod entry needs its own
  `RequestRateLimiter` block, not just the dev shape).
  _Requirements: design.md D5 (route definitions)_
- [ ] **5.3 D6 — `ReadOnlyEnforcementFilter` allowlist becomes method-plus-path, preserving the
  existing configurable entries.** Current source (`ReadOnlyEnforcementFilter.java:36-41`) already
  has a live, configurable allowlist — `aiAllowlistPatterns`, bound from
  `app.read-only.ai-allowlist` (default `/api/chat/**,/api/insights/generate/**`) — matched on path
  only, for every mutating method. Migrating to method-plus-path is not "two entries" replacing that
  list; it is **adding exactly two new B2 entries** — `(PUT, /api/portfolio/holdings)` and
  `(PUT, /api/portfolio/demo-reset)` — **while migrating each existing AI-route pattern to match
  every mutating method it matched before** (`POST`, `PUT`, `PATCH`, `DELETE` — i.e. "any method" at
  that path, not narrowed to one), so today's AI-route exemption behaves identically under the new
  representation. The existing overlapping-path preservation test SHALL stay green, unmodified in
  intent, against the migrated representation.
  _Requirements: 5.1, 5.2_
- [ ] **5.3a Filter-behavior tests, made explicit and auditable — not left implicit inside 5.1's
  description.** `DemoResetAuthorizationFilterTest` (or equivalent) SHALL separately assert: a
  request with no `GATEWAY_ROUTE_ATTR` present, and one with a route id other than
  `demo-reset-manual`, are both rejected (the fail-safe named in 5.1, exercised here rather than
  only described); an authenticated non-demo JWT subject on `(PUT, /api/portfolio/demo-reset)`
  receives the pinned `403` body exactly (`{"error":"demo_reset_forbidden","message":"Only the demo
  account may reset the demo portfolio."}`); the fail-closed `503` body is asserted exactly as
  requirements 7.3a pins it — two fields, `error` plus `message` (round-44: Task 8.9's
  gateway-vs-portfolio-service discrimination depends on this two-field shape, and no test
  previously asserted it, so a single-field gateway body — mirroring portfolio-service's own — would
  have passed every specified test while collapsing that discriminator); and the filter's declared
  order equals
  `Ordered.HIGHEST_PRECEDENCE + 4`, asserted as a value, not only exercised indirectly through
  chain-ordering behavior. **The `X-Gateway-Replica-Token` header (5.1, round-31) is asserted on
  every outcome this filter can produce — the fail-closed `503`, the subject-mismatch `403`,
  **the missing-`GATEWAY_ROUTE_ATTR` rejection, the wrong-route-id rejection (round-33 — 5.1's
  contract says "every response this filter itself produces," but round-31/32 listed only three
  outcomes while this very task separately exercises two more rejection branches, which could
  therefore violate the normative header contract with the suite still green)**, and
  the ordinary proxied path — injecting a **`ReplicaTokenProvider` double (5.1b, round-33 — the
  collaborator round-31 assumed without any task defining it)** so the
  assertion doesn't depend on actually running inside a Container App (round-31 addition — the
  header exists specifically so Task 8.9's probe diagnosis can trust it; an implementation that
  sets it on only some outcomes would silently break that diagnosis on exactly the outcomes GC.8's
  own probe classification needs it for, with nothing here to catch the gap).** **The double
  resolves a known *non-blank* value, and every outcome above is asserted to carry that exact
  value — not merely a present header (round-33 — a blank-only expectation is satisfied by an
  implementation that hard-codes blank, which passes locally and makes Azure correlation fail
  permanently, exactly where it cannot be caught before production).** **On the proxied
  path specifically, asserted for exact value and exactly-one occurrence against a stub that
  itself returns a conflicting `X-Gateway-Replica-Token` value (round-32 addition — presence alone, as
  round-31 left it, is satisfied by an implementation that merges or fails to overwrite a
  downstream-supplied header of the same name; only a case that deliberately supplies a
  *different* value from downstream and asserts the gateway's own value wins, singularly, actually
  tests the authoritative-write requirement 5.1 now states).**
  _Requirements: 7.3a; design.md D5_
- [ ] **5.4 Test 1 — gateway routed integration test** (stubbed portfolio-service, full filter
  chain). Asserts: route is `demo-reset-manual` not the generic route; downstream path is
  `/api/internal/portfolio/demo-reset`; downstream method is still `PUT`; `Authorization` absent;
  `X-User-Id` absent; exactly one `X-Internal-Api-Key` value, even when the original request carried
  a caller-supplied duplicate.
  _Requirements: design.md D5 (Test 1)_
- [ ] **5.5 Filter-level unit test for the fail-closed branch** — construct
  `DemoResetAuthorizationFilter` with an injected `InternalApiKeyProvider` test double (5.1a)
  resolving to `null` and to `""`, asserting `503` with the pinned two-field 7.3a body (round-44,
  same discriminator rationale as 5.3a) and zero downstream calls for each **(round-23
  correction — an earlier draft constructed the filter through a raw value-accepting constructor,
  a leftover of the pre-provider design that would have reintroduced a second value path alongside
  5.1a's shared read; the provider's own null/blank resolution behavior is unit-tested once in 5.1a,
  and every consumer's test injects the provider or a double of it, never a raw value)**.
  _Requirements: design.md D5 (pass 20, provider round-23)_
- [ ] **5.6 STOP/GO — Wave 5 as one deployable unit** (GC.10). **Go, all of (round-2 correction:
  naming only 5.4/5.5 let this gate pass while 5.3's regression test, 5.3a's tests, and 5.1's own
  promised contract test were still red):**
  1. Tasks 5.1-5.5 all present and merged together — the filter, the route, and the allowlist entry,
     never a subset. **5.1a (`InternalApiKeyProvider`) and 5.1b (`ReplicaTokenProvider`) merged too
     — though they, uniquely, may merge
     ahead of the rest, since Wave 8's build also consumes both and neither wave's bundle owns
     them (round-23; **5.1b round-33 — 5.1's response header consumes it, so this gate requires it
     for the same reason, and Wave 8's 8.7 consumes the same bean**); this gate requires their
     presence, not their bundling.**
  2. 5.1's own promised `DEMO_USER_ID` text-level alignment check (comparing api-gateway's literal
     copy against portfolio-service's directly in source, not a cross-module JUnit test) green.
  3. 5.3's existing-allowlist preservation regression test green, unmodified in intent.
  4. 5.3a's route-attribute/non-demo-subject/order tests green.
  5. 5.4 and 5.5 green.
  6. **The `azure-image-smoke-test` CI job (5.1a, round-36 addition) green — proving the probe
     packaging that lets Task 8.9's diagnosis run at all actually survives the real build, not
     merely that its source compiles.**
  7. Wave 4's live verification (4.5) already complete.
  **Abort:** do not ship any subset of 5.1-5.5 without the rest in the same change, **nor with the
  `azure-image-smoke-test` job red — a broken probe artifact is exactly the class of regression
  this gate exists to catch before it reaches a deployed replica, where Task 8.9 would only
  discover it mid-incident.**
  _Requirements: 5.1; design.md D5, D6_

## Wave 6 — Manual-reset control (frontend) · *design.md D5 Stage 5, gated on Wave 5 AND B1 task 5.1*

- [ ] **6.1 Manual reset control**, behind 1.1's `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL` flag —
  location per requirements.md 7.6, **OPEN**; build against a placeholder location and relocate
  without re-plumbing the call once decided. Buildable and testable against a mock now, same as
  Wave 1; **the flag, not a merge/deploy decision, is what keeps it hidden (round-3 correction of
  round-2's own "do not deploy" framing, which repeated the exact unenforceable-merge-hold mistake
  Wave 2 already fixed once — `deploy.yml` deploys `frontend/**` on every merge regardless of this
  wave's own readiness).** 6.1/6.2 may merge and deploy at any time; 6.3 below is about backend
  readiness, not about whether this code reaches production infrastructure.
  _Requirements: 7.5, 7.6 (OPEN)_
- [ ] **6.2 Wire to `PUT /api/portfolio/demo-reset`**, carrying the `expectedVersion` last observed
  by the browser (GC.6) — never re-read inside the call. `200` replaces visible state with the
  response body; `409` surfaces per 7.3b's placement-conditional presentation (in-picker
  `ConflictPanel` if 7.6 places the control inside the picker, a standalone draft-free notice
  otherwise — same envelope, same no-retry rule either way).
  _Requirements: 7.3b_
- [ ] **6.3 Backend-readiness gate — not a deployment gate (round-3 reframe: 6.1's flag is what
  makes deployment safe regardless of this gate's state; this gate instead answers "does the hidden
  control have a working backend to call yet").** The `expectedVersion` 6.2 sends can only come from
  a real `GET /api/portfolio` response that actually carries `version` — `design.md`'s own
  "Sequencing, restated precisely" note states the manual trigger "is implementable once B1 Wave 5
  task 5.1 ... lands," a B1 dependency independent of anything B2's own Wave 5 gates. **Go:** Wave
  5.6 green AND B1 task 5.1 (`version` on `GET /api/portfolio`) confirmed live in production. Once
  green, this control is *eligible* for Wave 10 to expose (by enabling its flag) whenever Wave 10's
  own conditions are also met — this gate does not itself flip anything user-facing. **Not
  satisfied:** the control stays hidden behind its flag; 6.1/6.2 remain deployed and inert, which
  needs no abort action of its own.
  _Requirements: 4.1; design.md D5 (sequencing)_

**The deployed-path integration test moved to Wave 9 (round-6 correction — it depends on Task 9.6's
demo-authenticated fixture and belongs thematically with "Live integration," not with this wave's
own build tasks; see Wave 9.8, which now precedes rather than follows the CI wiring it used to wait
on, round-9 reordering).** **Three distinct states, not two — named explicitly since round 2's
"deploy" language conflated them (round-3 addition):** *hidden deployment* (6.1/6.2 live on
production infrastructure, flag off — safe immediately, needs no gate); *assembled-stack
verification* (Wave 9.8's CI test against the real, docker-composed backend — proves the control
works against real code, not stubs, without needing production infrastructure at all); and *public
exposure* (both flags left on for all traffic, Wave 10's own decision, verified live there). **This
repository has no staging tier distinct from production** — verified against both `deploy-azure.yml`
and `deploy-aws.yml`, each of which builds and serves the same static export directly to production
infrastructure — so the only genuinely live-cloud check of this control happens inside Wave 10.2's
own Go action, not earlier.
**The manual-reset path is complete end to end once Wave 10 exposes it**, independent of Wave 8
below.

## Wave 7 — Decimal-adapter rollout note (informational — no gate of its own)

**Two separate sequencing concerns, not one — round-4 correction of round-2/3's own conflation
here.** Round 1 closed this wave on the theory that Task 2.1's shape-tolerant parsing removed the
sequencing hazard entirely; round 3 corrected that to "Wave 2.2's flag-based write guard is the real
mechanism" — also wrong, since that guard only protects the **picker's** writes, and requirements.md
8.3 is actually about the **existing, unrelated Portfolio page**. The real mechanism is Wave 2.7's
cross-spec deployment gate: Tasks 2.1/2.3/2.4 deployed before B1 task 4.9 is. Nothing needs a
standalone gate *here* because 2.7 already states the condition directly; this wave number stays
purely as a pointer so nothing downstream needs renumbering.
_Requirements: 8.3_

## Wave 8 — Login-orchestrated reset self-call · *design.md D5 Stage 6, separately gated*

**Build and deploy (8.1-8.8, including 8.7a's own test) does not block, and is not blocked by, Wave
5's bundle or Wave 6 — but IS blocked by Wave 4 (round-2 correction: the first draft's independence
claim over-reached) and by Tasks 5.1a, 5.1b, and 8.2a specifically (round-23 correction, 8.2a joined
round-25, **5.1b joined round-33**: 8.5 consumes 5.1a's shared `InternalApiKeyProvider`, 8.3
consumes 8.2a's
`CloudFrontOriginSecretProvider`, **and 8.7 consumes 5.1b's shared `ReplicaTokenProvider` — the same
one 5.1's response header publishes, which is what makes Task 8.9's replica correlation compare
like with like**; round-22 had placed the first provider inside 5.1, which
contradicted this very paragraph's independence claim — an impossible build graph. The provider
classes are logically standalone, but Task 5.1b's Azure image packaging and smoke gate explicitly
depend on Task 5.1a's packaging work; these are code/build prerequisites, not dependencies on Wave
5's bundle or deployment).** 8.5's reset self-call targets the internal endpoint Wave 4 builds; without Wave 4
live, that call has nothing to reach. Gated on Wave 4 plus the standalone 5.1a, 5.1b, and 8.2a
provider
classes (round-25: 8.2a added alongside 5.1a everywhere this boundary is stated, not only in 8.8's
Go; **round-33: 5.1b likewise added everywhere, in the same round that created it — the round-25
lesson applied pre-emptively rather than after a review round caught the omission**) plus three
items independent of Wave 5/6.
**8.9 specifically — the live serving proof — has its own, additional dependencies Wave 8's build
does not (round-13 correction, see 8.9 itself): on Azure it first needs Task 8.8b's deployment-
evidence foundation, then Wave 5 and B1 Wave 7 because its setup performs a real composition write;
on whichever run targets AWS it instead also needs 8.8a (round-15
addition — a CloudFront transport fix 8.9's causal-correlation proof depends on, but the reset
mechanism's actual runtime behavior does not).**

- [x] **8.1 B2-owned additive `updatedAt` read contract.** After B1 Wave 3/V20 has added
  `portfolios.updated_at` and B1 Task 5.1 has landed its `PortfolioResponse` changes, add
  `updatedAt` to `PortfolioResponse`, map it from the portfolio entity, and serialize it as the same
  ISO-8601 timestamp shape used by `createdAt`, one value on every element of the existing
  `List<PortfolioResponse>`. Add a real controller/serialization contract test covering a known
  timestamp and list cardinality. This closes the former cross-spec ownership gap; it is an
  implementation dependency now, not an owner-selection blocker.
  **Complete on `main`; not deployed.** Merged via
  [PR #185](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/185), merge
  commit `main@198c878d` (source commit `412733c8`, senior-review-passed). Built TDD-first in an
  isolated worktree off `origin/main@458813f` (replayed from an initial submission on
  `origin/main@dc3e2c6` after PR #183 merged; that PR's Spec A/9.13 content was preserved as-is):
  `PortfolioResponse` gained the additive `updatedAt` component (after `createdAt`),
  `PortfolioService.toResponse(...)` maps it from `Portfolio.getUpdatedAt()`, and
  `PortfolioControllerTest.getPortfoliosReturnsCreatedAtAndUpdatedAtForEveryElement` proves the exact
  ISO-8601 `updatedAt`/`createdAt` strings on both elements of a real two-element MockMvc response;
  `PortfolioServiceVersionMappingTest.getByUserIdMapsPersistedUpdatedAtThroughToResponseUnchanged`
  proves the entity-to-DTO mapping. Every direct `new PortfolioResponse(...)` call site (5 test
  fixtures plus the production mapping) was updated with deterministic fixture timestamps. Full
  `:portfolio-service:test` and `:portfolio-service:integrationTest` were green in PR #185's CI,
  including a transient Maven Central 429 that a job re-run cleared (unrelated to this change).
  **8 implementation/test files plus 2 status documents changed; no excluded surface touched.**
  Deployment (an Artifact cut, revision serving, live verification) remains separately gated and
  has not happened.
  _Requirements: 7.3d; design.md D7_
- [ ] **8.2 Blocker tracking, not resolved here: idle-reset threshold** (requirements.md 7.6, OPEN)
  and **login self-call timeouts** (2s/leg, 4s overall, design.md D5, OPEN) — product/operational
  decisions, not implementation unknowns; do not pick a default in code without raising them.
  _Requirements: Open items — idle threshold, self-call timeouts_
- [x] **8.2a `CloudFrontOriginSecretProvider`** — **merged source-only via PR #203 at
  `main@addd8049`** (not deployed). Extracting the origin secret's single read, so 8.3
  and the existing filter provably share one value (round-24 addition — a real ownership gap: round
  23 asserted 8.3 and `CloudFrontOriginVerifyFilter` share "the same resolve-once source" and built
  a `structurally impossible` mismatch claim on it, but no task actually created that provider,
  defined its API, owned the existing-filter refactor, or tested it — the claim was stronger than
  the plan supporting it).** A standalone api-gateway `@Component` mirroring 5.1a's shape: resolves
  `System.getenv("CLOUDFRONT_ORIGIN_SECRET")` exactly once, exposes the resolved value plus an
  `isRequired()` non-blank check (the same predicate that decides whether origin verification is
  live at all), constructor-injected into consumers, package-visible for test doubles.
  **Includes refactoring the existing `CloudFrontOriginVerifyFilter` to consume it** — that class
  currently resolves the variable in its own constructor
  (`CloudFrontOriginVerifyFilter.java:38-41`, verified directly) and keeps its exact current
  behavior, including treating null/blank as "no-op, accept everything" (lines 54-56); this task
  changes *where the value comes from*, never what the filter does with it. **This refactor SHALL
  bring its own full regression matrix, written as part of this task — not lean on existing filter
  tests, because there are none (round-25 correction — round-24 said "its existing tests SHALL
  still pass unchanged in intent," but a search of `api-gateway/src/test` finds zero references to
  `CloudFrontOriginVerifyFilter`: the clause was protecting a security boundary with tests that do
  not exist, and a lone no-op-preservation test is not adequate cover for refactoring the filter
  that stands between the public internet and a direct-function-URL bypass).** The matrix, with the
  provider double supplying the configured/unconfigured states:
  - configured + matching `X-Origin-Verify` header → request passes to the chain;
  - configured + missing header → `403`, response completed, chain never invoked;
  - configured + wrong header value → `403`, same shape;
  - configured + matching header → the header is **stripped** before downstream forwarding
    (the anti-leakage mutation, `CloudFrontOriginVerifyFilter.java:74-79`);
  - configured + a path under `/api/internal/**` → bypassed entirely, header untouched
    (lines 58-64's E2E-seeder exemption preserved);
  - unconfigured (provider blank) → no-op, every request passes, no header inspection at all;
  - `getOrder()` still returns `Ordered.HIGHEST_PRECEDENCE` — the before-JWT positioning is
    load-bearing (line 21-22's own documented contract) and a refactor that touched the class
    declaration could silently lose it.
  Consumed by: that filter, and 8.3's eligibility self-call
  (which sources both the header value and its `originVerifyRequired` evidence field from it).
  The provider itself is additionally unit-tested for the null/blank/set resolution states.
  **No dependency of its own — mergeable independently, like 5.1a; not gated on Wave 5, Wave 4, or
  the rest of Wave 8.**
  _Requirements: 7.3c; design.md D5 (round-24 amendment)_
- [ ] **8.3 Eligibility-read self-call** — `WebClient` (non-blocking, GC.9), loopback target
  `http://localhost:{port}/api/portfolio` (not the public CloudFront URL), **where `{port}` is
  resolved through a testable seam, not baked in as `${server.port}` at bean-construction time
  (round-18 correction: `${server.port}` resolves eagerly via normal property binding, but under
  `RANDOM_PORT` — 8.7a's own test topology — that property is literally `0`; the real bound port
  is only known after the embedded server starts, via `WebServerInitializedEvent`/
  `@LocalServerPort`, so a self-call bean built from the raw property would target `localhost:0`
  and fail to connect under test).** Resolve the port via Spring Boot's own existing
  `org.springframework.boot.web.server.context.ServerPortInfoApplicationContextInitializer`
  (main `spring-boot-web-server` module, not a hand-rolled listener — round-18 addition: this class
  already does exactly what a custom `ApplicationListener<WebServerInitializedEvent>` would
  reinvent, publishing `local.server.port` to the `Environment` once the server actually binds).
  **No manual registration needed (round-19 correction — round-18's own
  `SpringApplication.addInitializers(...)` instruction was redundant: verified directly against the
  `spring-boot-web-server-4.1.0.jar` artifact, this initializer is already wired via that module's
  own `META-INF/spring.factories` as a Spring-Boot-auto-registered `ApplicationContextInitializer`,
  active on every Spring Boot app automatically, api-gateway included).** Simply read
  `Environment.getProperty("local.server.port")` at *call* time, not construction time — this makes
  the identical code correct in production (a fixed configured port) and under `RANDOM_PORT` in
  tests (the actual allocated port) without a test-only code branch. The
  freshly-minted JWT as `Authorization`, `X-Origin-Verify` attached only when the shared
  **`CloudFrontOriginSecretProvider` (Task 8.2a) reports `isRequired()`** — i.e. the secret is
  non-blank in this process (AWS) — and omitted entirely otherwise (Azure/local), **sourcing both
  the header value and 8.7's `originVerifyRequired` evidence field from that one provider rather
  than a second independent env read (round-23 addition, given an owning task round-24: same
  process, same variable, one read — so the only failure mode this leg can have is the attach
  itself not happening, which 8.7's `originVerifyHeaderAttached` boolean observes against
  `originVerifyRequired`)**. Selects
  the single `PortfolioResponse` list entry whose `userId` equals `DEMO_USER_ID`; an empty or
  multi-entry result is treated as an eligibility-read failure (GC.8), never a `NullPointerException`
  or arbitrary-element pick. Per-leg timeout per 8.2's resolved value. **Built from the
  auto-configured, observation-enabled `WebClient.Builder` bean (Spring Boot's own
  `WebClientObservationAutoConfiguration` in the `spring-boot-webclient` module — round-44 name
  correction: round-14 wrote `WebClientObservationConfiguration`, the Boot 3.x
  actuator-autoconfigure name, which does not exist in the Boot 4.1.0 jars this project resolves —
  active because `spring-boot-starter-opentelemetry` is already
  a build dependency here — round-14 addition), never a bare `WebClient.create()`** — so an inbound
  `traceparent` on the triggering login request propagates onto this leg automatically; this is the
  mechanism Task 8.9's causal-correlation proof depends on.
  _Requirements: 7.3c, 7.4; design.md D5_
- [ ] **8.4 Idle check** — reset eligible iff `updated_at` age exceeds the resolved threshold (8.2).
  _Requirements: 7.4_
- [ ] **8.5 Reset self-call** — `POST /api/internal/portfolio/demo-reset`, **target is the
  gateway's own loopback address (`http://localhost:{port}/api/internal/portfolio/demo-reset`,
  `{port}` resolved through 8.3's same call-time seam, round-18 — not a second, independently
  hardcoded `${server.port}` reference),
  never the public CloudFront URL — the same pattern 8.3 already uses, not a second transport
  (round-16 correction: round-15's "loopback address or `PORTFOLIO_SERVICE_URL` directly" offered two
  genuinely different paths — different routing, filtering, and timeout behavior — without choosing
  between them, leaving the ambiguity it meant to close still open; loopback wins for consistency
  with 8.3 and because it reuses the gateway's own existing route table (`app.routes.portfolio-url`)
  rather than requiring new direct-service-client wiring)**, **with the reset target's own assembly
  — this leg's path applied to the shared port seam — performed in its own overridable
  construction step, a small injectable collaborator rather than inline string concatenation
  (round-28 addition — GC.8's reset-leg `gateway_orchestration_error` case needs a fault seam this
  leg owns exclusively: the port seam is shared with 8.3, so faulting it fails the *eligibility*
  leg first and execution never reaches this leg's construction at all; only a reset-leg-specific
  seam lets that case induce a pre-dispatch failure here while eligibility stays healthy — see
  GC.8)**, `WebClient`
  (non-blocking), `INTERNAL_API_KEY` attached from the **shared `InternalApiKeyProvider` bean (Task
  5.1a — the same bean 5.1's filter injects; round-22 correction, ownership moved to 5.1a round-23:
  an earlier draft left this as "`System.getenv` directly (or an added YAML mapping)," an open choice
  independent of 5.1's own read; that ambiguity, and the resulting two-independent-reads
  architecture, is exactly what made Task 8.9's manual-reset-probe diagnostic unable to vouch for
  this leg specifically — see 8.9). No new secret provisioning or Terraform change either way — the
  variable already reaches this process; only the *in-process* read is now shared, not duplicated.
  This is Wave 8's one cross-wave build prerequisite beyond Wave 4 — 5.1a, the standalone class, not
  Wave 5's bundle (round-23, see this wave's intro).** Carries the exact version observed in 8.3
  (GC.6). Per-leg timeout
  per 8.2. **Built from the same auto-configured, observation-enabled `WebClient.Builder` as 8.3
  (round-14 addition)**, so trace context propagates onto this leg too.
  _Requirements: 7.3, 7.3c; design.md D5 (round-23 amendment)_
- [ ] **8.6 Overall orchestration deadline** across both legs combined, per 8.2's resolved value.
  **The deadline, when it fires, cancels the in-progress orchestration chain — the downstream
  subscription is disposed (standard Reactor `timeout` operator semantics), so a leg whose call
  has not yet gone out when the deadline fires never dispatches afterward (round-30 self-audit
  addition — GC.8's between-legs overall-timeout case asserts zero reset requests at the stub
  after a stall that completes construction post-deadline, an expectation that depends on exactly
  this cancellation behavior, which was previously nowhere stated).**
  **This deadline needs its own explicit elapsed-time bracket, separate from 8.7's per-leg one
  (round-31 correction — an earlier round-31 draft assumed the overall `.timeout()` operator
  already exposes this, but it does not: the same round-21 finding that motivated 8.7's per-leg
  `System.nanoTime()` bracket — a plain `WebClient`/Reactor `.timeout(Duration)` operator does not
  hand elapsed duration to its own handler — applies identically here, one level up): capture
  the monotonic reading once at orchestration entry, before the eligibility leg's own call
  dispatches, and compute the elapsed nanos in the overall-deadline handler itself when it fires —
  this is what makes 8.7's `overall_timeout` `elapsedMillis` value (a distinct measurement from
  any single leg's own per-call bracket) actually obtainable.**
  **Both brackets — this one and 8.7's per-leg one — read the monotonic clock through an
  injectable seam (a `LongSupplier`/`NanoClock` bean defaulting to `System::nanoTime`), not by
  calling `System.nanoTime()` inline (round-33 addition — GC.8 previously could only assert
  `elapsedMillis` fell in a band around the configured deadline `D`, which an implementation that
  simply emits `D` — reading no clock at all — satisfies perfectly on every timeout case; the band
  is the right tool for judging a *real* production timeout (Task 8.9), where genuine jitter
  exists, but it cannot distinguish a measurement from a fabrication, because the fabricated value
  is the band's own centre. A seam lets GC.8 supply readings whose computed elapsed value is
  deliberately far from `D`, so only an implementation that actually reads the clock can produce
  it).** The seam is production-inert — the default binding is the same `System.nanoTime()` call
  the round-22 correction requires, and its wall-clock-immunity rationale is unchanged.
  _Requirements: design.md D5 (overall timeout)_
- [ ] **8.7 Fail-open wrapper** (GC.8) — any outcome other than a clean success on either leg skips
  the reset and lets login proceed, un-logged as a user-facing error. **Also emits a structured,
  trace-correlated event on that catch path (round-18 addition — `event=demo_reset_self_call_skipped`,
  INFO level, plus the active trace id), mirroring 4.2's success-event pattern but for the failure
  path** — internal-only, still never surfaced to the user (fail-open is unaffected).
  **One shared, coarse `reason` vocabulary for the stable *behavioral* rule — plus separate,
  non-policy diagnostic fields the coarse reason deliberately doesn't encode (round-19 introduced the
  shared vocabulary; round-20 adds the diagnostic fields — an earlier draft conflated "the policy
  treats these uniformly" with "nothing else is worth recording," but Task 8.9's Abort Class 2d
  cannot actually diagnose a `409` vs. a `429` vs. a downstream `5xx` vs. an internal-key rejection
  from `reason=reset_non_2xx_status` alone; recording status is not the same as re-enumerating status
  in the behavioral rule design.md D5 (line 941-955, pass 6; line numbers re-pinned round-45 —
round-44 re-pinned this citation to 926-940 after design.md's own pass-29–32 amendments shifted
it, but 926-940 is the non-blocking-execution/OPEN-timeout-values bullets, not the fail-open
classification itself, which is the very next bullet, 941-955 — "Fail-open, defined by outcome
class, not by enumeration" through "operational signals only") deliberately keeps coarse):**
  - **`reason`** (the stable, coarse policy field — matches design.md's own "not a clean success"
    principle, never re-fragmented into per-status-code values): `eligibility_timeout`,
    `eligibility_connection_failure`, `eligibility_non_2xx_status`, `eligibility_shape_failure`
    (zero- or multi-entry response), `reset_timeout`, `reset_connection_failure`,
    `reset_non_2xx_status`, `overall_timeout`, `reset_key_not_configured`, or
    `gateway_orchestration_error` (see below).
  - **`reset_key_not_configured` — a pre-dispatch configuration outcome, distinct from every
    dispatch-failure reason above (round-24 addition, closing a real gap: the vocabulary previously
    had no way to express "we never dispatched, because the key wasn't configured," so that state
    could only surface as a `false` attach-boolean on some *other* reason, where Task 8.9 would
    misread it as a code defect).** When 5.1a's provider resolves null/blank, the reset leg **SHALL
    NOT dispatch at all** — mirroring `DemoResetAuthorizationFilter`'s own fail-closed-before-any-
    downstream-call rule (design.md D5) rather than sending an empty credential that
    portfolio-service would reject as `403 invalid_internal_api_key`, which would misreport a
    gateway configuration gap as a per-request authorization failure. Fail-open toward login still
    holds (GC.8): login proceeds untouched. **This reason is always a configuration outcome, never
    a wiring defect — but whether the blank itself is attributable to the deployed revision is a
    separate question 8.9 settles by diagnosis, not by the reason string (round-28 correction — on
    Azure the `INTERNAL_API_KEY` env *reference* is revision-scoped template configuration while
    the secret *value* is app-scoped, so a template regression in a newly deployed revision is
    revision-attributable and genuinely rollback-fixable; see Task 8.9's Abort clause, this
    reason's own Diagnosed-tier bullet and Class 2h).**
  - **`leg`** (`eligibility`/`reset`/`overall`), **`httpStatus`** (the actual status code, when the
    branch reached one — `null` for timeout/connection-failure/`reset_key_not_configured`/the
    *pre-dispatch* form of `gateway_orchestration_error`; the *post-response* form (below) records
    the status the leg actually received before the handler threw, and `eligibility_shape_failure`
    records the read's actual status — a `200` whose body shape was wrong, which is exactly what
    distinguishes it from the non-2xx reason (round-30 self-audit refinement — the blanket null
    list predated the post-response form and silently discarded a received status the diagnosis
    could use, and the shape-failure reason had no defined value at all; round-27 addition — this
    field's own normative definition still
    only named the first three, even after `reset_key_not_configured` (round-24) was given a
    `null` value in GC.8's test; the test and the schema it's supposed to verify had drifted apart)),
    **`timeoutScope`** (`per-leg`/`overall`, when `reason` is a timeout), and a **sanitized
    `exceptionClass`/category** (when `reason=gateway_orchestration_error` — never the raw message or
    stack trace, which could leak internal detail into a log line this task's own live query later
    reads) — round-20 additions, diagnostic only, never load-bearing for the coarse `reason` itself.
    **Plus `replicaToken` (round-30 addition — **sourced from the shared `ReplicaTokenProvider` bean
    (Task 5.1b), injected, never a second independent `System.getenv` read here (round-33
    correction — 5.1's response header and this field were each separately described as reading
    `CONTAINER_APP_REPLICA_NAME`, and Task 8.9's diagnosis compares the two as strings: two
    unowned reads are free to normalize differently — trim, blank-vs-null, fallback — and any
    divergence silently breaks that comparison, the identical defect Tasks 5.1a and 8.2a were
    extracted to prevent)**; **carries 5.1b's derived `replicaToken`, never the raw platform
    value (round-34 — this event is internal-only, never publicly reachable, but 5.1b publishes
    one accessor to both consumers and this field is that accessor's return value by definition;
    see 5.1b for why the raw value isn't what either consumer gets)**; blank where the platform
    doesn't provide the underlying variable,
    e.g. local/AWS): api-gateway runs up to three replicas on Azure
    (`infrastructure/terraform/azure/main.tf:217`) and Task 8.9's manual-reset-probe diagnosis is
    only same-process-valid when the probe's serving replica can be compared against the replica
    that emitted the skip event — without this field that comparison has nothing to compare on the
    event side.** Diagnostic only, like the rest of this bullet.
  - **Further round-21 additions, without which the Diagnosed tier below has nothing concrete to
    compare (a real gap: naming a diagnostic field is not the same as giving it an authoritative
    source to compare against):** **`attemptedTarget`** (the loopback URI/host:port actually dialed,
    for `*_connection_failure` and *per-leg* `*_timeout` — comparable directly against the fixed,
    known
    construction pattern 8.3/8.5 specify, not a mutable config value, so this comparison needs no
    external lookup); **`elapsedMillis`** (the actual duration observed before a timeout branch
    fired — comparable directly against 8.2's own resolved value, already recorded in this run's
    evidence manifest, step 6). **A plain `WebClient.timeout(Duration)` operator does not expose
    elapsed duration to its own error handler (round-21 correction — verified directly: Reactor's
    `.elapsed()` wraps `onNext` signals only, so it never fires on a timeout, and the
    observation-enabled `WebClient.Builder`'s own timing goes into Micrometer meters, not an inline
    variable readable at the catch site): bracket each call explicitly, using a monotonic clock, not
    wall-clock instants (round-22 correction — `Instant.now()` reads the system wall clock, which can
    jump on NTP correction, exactly when this measurement is deciding whether timeout enforcement
    itself is defective; a monotonic reading is the standard idiom for elapsed-time measurement
    specifically because it is immune to wall-clock adjustment): capture the clock's reading
    immediately before dispatch, compute the elapsed nanos in the timeout/error handler itself,
    **through the same injectable monotonic-clock seam 8.6 defines for its own bracket — a
    `LongSupplier`/`NanoClock` bean, defaulting to `System::nanoTime`, injected here rather than
    called inline (round-34 correction — this example still called `System.nanoTime()` directly on
    both ends, the exact inline pattern round-33 introduced the seam specifically to replace one
    task earlier; an implementer following this example as written would bypass 8.6's seam
    entirely and fail GC.8's exact-value clock assertions, which only an injected reading can
    satisfy)**, e.g.
    `Mono.defer(() -> { long start = clock.getAsLong(); return call.timeout(d)
    .doOnError(e -> recordElapsed(Duration.ofNanos(clock.getAsLong() - start))); })` — this is what
    makes `elapsedMillis` actually obtainable, not merely named, and — with both brackets reading
    the one seam — actually testable per GC.8's exact-value requirement.** **Both fields have distinct,
    phase-sensitive semantics for `overall_timeout` specifically, which the per-leg mechanism above
    does not by itself cover (round-31 addition — a real gap: the text above describes bracketing
    *one WebClient call*, but `overall_timeout` spans the whole orchestration across both legs and
    the gap between them, and `attemptedTarget` names *a* target as if exactly one always exists):**
    **`attemptedTarget` on an `overall_timeout` event is the in-flight leg's target when
    `overallTimeoutPhase` is `eligibility_in_flight` or `reset_in_flight`, and explicitly `null`
    when the phase is `between_legs`** — no call is in flight at the moment the deadline fires in
    that phase, and recording the eligibility leg's already-completed target would misleadingly
    suggest that leg was still being dialed when the timeout actually caught the orchestration's
    own inter-leg processing. **`elapsedMillis` on an `overall_timeout` event is read from a
    second, independent monotonic timer, bracketed at 8.6's own orchestration-entry point** (see
    8.6 — `System.nanoTime()` captured once before the eligibility leg's own call dispatches, read
    at the moment the overall deadline fires) — never derived from, or confused with, any single
    leg's own
    per-call bracket, which measures a different, shorter interval and would understate the true
    elapsed time whenever the deadline fires during `between_legs` (where no per-call bracket is
    even open) or partway into either leg.**
  - **Attach-evidence booleans — always a *pair* per secret, `configured` and `attached`, never one
    combined boolean (round-22 introduced the first as a `409`-paragraph aside; round-23 promoted
    both to general fields; round-24 split each into two, a correctness fix rather than a
    refinement: a single boolean silently merged "the environment wasn't configured" with "the code
    failed to attach a configured value," and Task 8.9's Abort clause was reading that merged
    `false` as proof of a Wave 8 code defect — so a blank environment variable, or an ordinary
    Azure deployment where one secret is deliberately never provisioned, would have authorized a
    production rollback of perfectly correct code).** Never a secret value; **both legs' full field
    sets are recorded on every skip event regardless of `reason` *and regardless of `leg`* —
    including `leg=overall` (round-28 correction — the fields were previously recorded "for their
    leg," but `leg` permits `overall`, and an overall timeout can fire while either leg is active
    and after the other already dispatched, so a single-leg field set keyed to the event's `leg`
    value structurally cannot record what actually happened; with `attached`'s `null` meaning
    "not applicable" and the dispatch fields below being explicit runtime facts, every field is
    well-defined on every event, so recording all six costs nothing and removes the ambiguity).**
    **Dispatch and applicability are two separate
    signals, not one — explicit, leg-named dispatch booleans, **`eligibilityDispatchAttempted`**
    and **`resetDispatchAttempted`** (round-28 renaming of round-26's single generic
    `dispatchAttempted` — a name that only worked while each event carried one leg's fields; on a
    `leg=overall` event a bare `dispatchAttempted` is unanswerable, since "which dispatch?" has two
    different answers), alongside `configured`/
    `required` (plain booleans) and a tri-state `attached` whose `null` means "not applicable" for
    whatever leg-specific reason, never inferred from the dispatch fields alone (round-26 correction
    — round-25's "attached is non-null iff a dispatch was attempted" is false on the eligibility leg:
    8.3 dispatches the eligibility read *unconditionally* on every login, regardless of whether origin
    verification is required — only the header's attachment is conditional. On Azure,
    `dispatchAttempted=true` and `required=false` coexist with `attached=null`, directly violating
    the claimed invariant. The reset leg's dispatch genuinely is gated on `configured`, so its
    invariant happened to hold — but stating it as a document-wide rule broke the leg where it
    doesn't).** **Each dispatch field is set only by the orchestration code's own record of whether
    it actually issued that leg's network call — never derived from `configured`/`required` or from
    which leg is running (round-27 correction: round-26's own per-leg rules — "false iff
    `configured=false`" for the reset leg, "always `true`" for the eligibility leg — are themselves
    both wrong on the one path neither accounted for: `gateway_orchestration_error` (below) has a
    *pre-dispatch* form — a throw during construction, before that leg's call ever goes out
    (round-30 note: that is one of its two evidence shapes, not its whole scope — see the reason's
    own bullet) — reachable on *either* leg regardless of whether its secret is configured/required,
    and GC.8 already induces it via a construction fault. A reset-leg orchestration error with
    `configured=true` would read as dispatched under round-26's rule despite no call ever going
    out; an eligibility-leg orchestration error would read as dispatched under the "always true"
    rule for the same reason. Both derivation rules are collapsed below into a single, correct one:
    dispatch is a runtime fact, not an inference.)** **On a `leg=overall` event
    (`reason=overall_timeout`), the pair narrows where the deadline landed (round-28 — this
    is the case the single generic field could not express), but does not fully determine it, so it
    is accompanied by an explicit **`overallTimeoutPhase`** naming the phase in flight when the
    deadline fired (round-29 addition — the pair alone is ambiguous exactly where diagnosis needs it
    to be precise: round-28's own wording defined `eligibility=true, reset=false` as "the deadline
    expired during the eligibility read **or between the legs**," and those are different defects —
    downstream eligibility latency in the first case, the orchestration's own post-read processing
    stalling in the second, which is a *gateway* problem this revision could have introduced. A
    field pair whose one value covers both cannot tell 2f's "8.2 is tuned too tight" tuning verdict
    apart from a genuine orchestration stall):** `eligibility_in_flight` (dispatched, awaiting the
    read's response), `between_legs` (the read completed; the deadline fired during the idle check,
    reset construction, or any orchestration step before the reset dispatch), or
    `reset_in_flight` (both calls out, awaiting the reset's response). The phase is recorded from
    the orchestration's own progress state at the moment the overall deadline fires, the same
    runtime-fact discipline the dispatch fields follow — never reconstructed from them afterward.
    **The pair and the phase are consistent by construction and SHALL agree**:
    `eligibility_in_flight`/`between_legs` both carry `eligibility=true, reset=false`;
    `reset_in_flight` carries `true, true`. **GC.8 induces and asserts both branches — the
    `true/false` one at `eligibility_in_flight` and the `true/true` one at `reset_in_flight`
    (round-29 — round-28 added an assertion only for the latter, leaving the branch whose
    ambiguity motivated this field entirely unexercised).**
    - **Reset leg:** `internalApiKeyConfigured` (5.1a's provider resolved a non-blank value),
      `resetDispatchAttempted` (whether the reset call was actually sent, observed directly —
      `false` whenever `configured=false`, whenever a pre-dispatch `gateway_orchestration_error`
      fires regardless of `configured`'s value, **or whenever the flow never reached this leg at
      all — an eligibility-leg failure, a not-idle-eligible verdict... any path that ends before
      the reset decision (round-28, a consequence of the field now appearing on every event rather
      than only reset-leg ones)**; `true` only once the network call genuinely goes out), and
      `internalApiKeyAttached` (tri-state: whether the key was actually present on this specific
      outbound request; `null` whenever `resetDispatchAttempted=false`, for any cause above,
      otherwise always boolean). `configured=false` carries `reason=reset_key_not_configured`
      (above), `resetDispatchAttempted=false`, `attached=null` — a configuration outcome, never a
      wiring defect (whether it is nonetheless *deployment-attributable* is settled by 8.9's
      round-28 diagnosis bullet, see the Abort clause). Only
      **`configured=true && attached=false`** is a Wave 8 wiring defect — and requires
      `resetDispatchAttempted=true` to mean anything, since a `configured=true` orchestration-error
      case also has `attached=null`, not `false` (there was no request to check).
    - **Eligibility leg:** `originVerifyRequired` (this deployment actually enforces origin
      verification — i.e. `CLOUDFRONT_ORIGIN_SECRET` is non-blank in this process, which is exactly
      the same condition that makes `CloudFrontOriginVerifyFilter` a live check rather than a no-op),
      `eligibilityDispatchAttempted` (whether the eligibility read was actually sent, observed
      directly — `true`
      on every ordinary path, since nothing but a pre-dispatch `gateway_orchestration_error` prevents
      this leg's own call from going out; `false` specifically when that reason fires **before**
      this leg's own request construction completes), and `originVerifyHeaderAttached`
      (tri-state: the header was actually present on this specific outbound request; `null` when
      `required=false` — an attach that was never applicable, independent of dispatch — **and also
      `null` when `eligibilityDispatchAttempted=false`**, for the same reason the reset leg's does).
      **`required=false`
      makes a missing header the correct, expected state, not a defect
      (round-24 correction — verified directly against `CloudFrontOriginVerifyFilter.java:38-41,
      54-56`: the filter nulls a blank secret at construction and returns
      `chain.filter(exchange)` unconditionally when it is null, so on Azure and local — where
      `infrastructure/terraform/azure/` never provisions this variable at all — origin verification
      is deliberately disabled end-to-end. The round-23 rule would have read that normal, correct
      Azure state as a rollback-worthy Wave 8 defect on every single run).** Only
      **`required=true && attached=false`** implicates Wave 8.
    Both providers resolve from the same in-process source their validating counterpart reads
    (5.1a; 8.2a), so on the loopback legs a value *mismatch* is not a reachable failure mode — the
    reachable one is the attach not happening, which is what these pairs observe.
  - **For the reset leg's `409` specifically:**
    `observedVersion` (the version 8.3's eligibility read actually returned), `submittedExpectedVersion`
    (the version 8.5 actually sent — these two SHALL be equal per GC.6; if they are not, that
    inequality is itself evidence of a version-capture bug in *this* revision, not legitimate
    concurrency), `downstreamCurrentVersion` (from the `409` response body's own `currentVersion`
    field — confirmed present on every B1 `409 portfolio_version_conflict` response,
    `portfolio-composition-contract/design.md:602`), and `selfCallCount` (how many times this trace
    id's own reset call was attempted — more than one is evidence of a duplicate-call bug in this
    revision). **`rateLimitKeyCategory` removed (round-22 correction — round-21's own field is not
    observable as specified: verified directly against
    `api-gateway/src/main/java/com/wealth/gateway/GatewayRateLimitConfig.java:99-116`,
    `userOrIpKeyResolver` computes its key entirely inside a private lambda with no mechanism to
    surface which key/category was used back to the calling orchestration when Spring Cloud Gateway's
    `RequestRateLimiter` filter rejects a request with `429` — this is the second round in a row a
    diagnostic field in this checklist turned out unobservable as specified; rather than patch a
    third time, `429` on the eligibility leg is left operationally unresolved: stays Class 2d
    unconditionally, no Diagnosed-tier path, unless filter-side telemetry surfacing the resolved key
    is added as its own, explicit, separately-scoped task).**
  - `gateway_orchestration_error` — an unexpected exception thrown by this wrapper's own
    orchestration code, **at any point in the orchestration's own execution: request construction
    and URI building before a call ever goes out, or the handling of a response after one returns
    — including a successful one, when the success-handling code itself is what throws (round-21
    clarification, see Task 8.9's outcome (e) handling; governing sentence rewritten round-30 —
    since round-21 it had read "before or independent of a network response ever being attempted,
    including after a successful downstream response," whose second clause flatly contradicts its
    first: a throw *after* a response is neither before nor independent of one. What defines this
    reason was never the timing — it is *whose code threw*: the wrapper's own, never the
    downstream's response as such)**. **This reason therefore has two distinct evidence shapes, and every
    consumer of it — the Abort clause's Class 1 wording, GC.8's induced cases — SHALL account for
    both (round-29 addition — the reason's scope sentence above has said "including after a
    successful downstream response" since round-21, but everything downstream of it described and
    tested only the pre-dispatch form, so the post-response form was normatively in scope and
    operationally invisible):**
    - **Pre-dispatch form** — the throw happens during construction, before that leg's call goes
      out: the failing leg's dispatch field is `false` and its `attached` is `null` (no request
      existed to inspect), and no `demo_reset_succeeded` can exist for this trace id.
    - **Post-response form** — the leg's call went out and *succeeded*; the orchestration's own
      handling of that success is what throws: the leg's dispatch field is `true`, its `attached`
      is a genuine boolean (a real request was inspected), and on the reset leg a legitimate
      `demo_reset_succeeded` may coexist for the same trace id — the commit already happened
      downstream before the gateway-side handler failed. This is precisely the combination the
      round-21 clarification above calls coherent rather than contradictory.
    Both forms are equally, immediately rollback-eligible — the classification does not depend on
    which form occurred, only the evidence shape does. **This reason alone is *immediately* rollback-eligible,
    no further diagnosis needed (round-19), regardless of what else is found alongside it — including
    a co-occurring `demo_reset_succeeded` (round-21 clarification).** Every other reason is only
    *diagnosis-eligible* — it can still justify a rollback, but only once follow-up evidence ties its
    specific cause to this revision, not on the reason string alone (round-20 correction — see Task
    8.9's Abort clause, which no longer treats this as the sole rollback path). **This includes `409`
    on the reset leg and `429` on the eligibility leg** (round-21 correction — an earlier draft called
    these structurally incapable of indicating a deployment defect; they are not: a version-capture
    bug or a duplicate-call bug introduced by this revision can produce either, indistinguishable
    from legitimate concurrency/rate-limiting at the `reason` string alone — the fields above exist
    specifically to tell them apart).
  **The trace id enters the log line via Reactor's automatic context propagation, not a manual
  `ContextView` read (round-19 correction — narrowing round-18's "either/or," which left the
  architecture unresolved for a rollback-bearing event): set
  `spring.reactor.context-propagation: auto` in api-gateway's config (Boot 4.1's own documented,
  explicit opt-in for exactly this — Reactor then restores MDC around every operator, including
  inside `.onErrorResume()`, via the same `ThreadLocalAccessor` hooks Micrometer Tracing already
  registers). With this enabled, the event's trace id is obtained through an ordinary
  `MDC.get("traceId")` read at the emission point — no `ContextView`/`deferContextual` code needed;
  this single, documented config change is what closes round-18's gap, not a second, narrower
  code-level mechanism competing with it.**
  _Requirements: 7.3c_
- [ ] **8.7a Integration test — trace propagation through the login orchestration's *own* `WebClient`
  calls, not the gateway's route-level proxy client (round-15 addition: `HttpTraceContextPropagationIT`
  only proves Spring Cloud Gateway's built-in proxy forwards `traceparent` on its route-level
  forwarding to insight-service — it exercises no code this wave writes at all; citing it for 8.3/8.5's
  own propagation was a mismatch).** Send a known `traceparent` on a request to the real login
  handler (`AuthController`), **against the gateway's own random port, not a stub wired directly into
  8.3/8.5's `WebClient` beans (round-17 correction — round-15's original "both pointed at in-process
  HTTP stubs" contradicts round-16's own fix pinning 8.5 to the gateway's loopback address; pointing
  the reset `WebClient` straight at a stub would test a transport the deployed code no longer uses,
  bypassing exactly the loopback-then-route-table path 8.5 selects):** start the real gateway
  (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, **`@AutoConfigureTracing(export = false)`
  explicitly applied (round-18 addition — this test's entire purpose is observing propagation
  through 8.3/8.5's own `WebClient` calls, so it needs the same explicit, deterministic tracing
  activation Task 4.4 requires, and `export = false` for the same reason: the annotation's own
  default is `export = true`, which would attempt a real OTLP export neither test needs or has a
  collector for — and the same `testImplementation
  'org.springframework.boot:spring-boot-micrometer-tracing-test'` dependency 4.4 needs, added to
  api-gateway's own `build.gradle` (round-20 exact coordinate), verified not to resolve it today
  either)**); this is also the concrete exercise of 8.3/8.5's call-time port-resolution seam
  (round-18, see 8.3) — under `RANDOM_PORT`, `${server.port}` alone would resolve to `0` at bean
  construction, so this test only passes if that seam genuinely resolves the real bound port at call
  time, not merely if trace headers happen to propagate; 8.3 and 8.5 both call the gateway's own loopback
  address as they do in production; set `app.routes.portfolio-url` (the gateway's own route-table
  target both routes forward through) to an in-process HTTP stub, so the stub sits where live
  portfolio-service normally would, at the outer boundary — never wired directly into either
  `WebClient`. Independently capture the `traceparent` header the stub receives on each of the two
  downstream requests (eligibility read, reset call) and assert both carry the same trace id as the
  inbound login request. This is what proves 8.3/8.5's observation-enabled-`WebClient` requirement
  (round-14) actually propagates trace context through the *actual* loopback-plus-routing path 8.5
  selects — Task 8.9's live causal-correlation proof depends on this contract holding, not merely
  being asserted.
  _Requirements: 7.3c, 7.4; design.md D5_
- [ ] **8.8 STOP/GO — Wave 8 deployment** (GC.11). **Go:** Wave 4.5 (portfolio-service's internal
  endpoint, live-verified) complete, AND Task 5.1a (`InternalApiKeyProvider`) merged (round-23
  addition — 8.5's build consumes it; this gate previously omitted the dependency the round-22
  shared-provider fix created), AND Task 8.2a (`CloudFrontOriginSecretProvider`, with its
  existing-filter refactor) merged (round-24 addition, same reason on the eligibility leg), **AND
  Task 5.1b (`ReplicaTokenProvider`) merged (round-33 addition — 8.7's `replicaToken` field consumes
  it, and Task 8.9's replica-correlation diagnosis is only sound when this field and 5.1's
  response header publish the same normalized value from the same owner)**, AND 8.1
  complete and Task 8.2 resolved, plus 8.3-8.7 **and 8.7a** green
  (round-15 addition to the gate). **Abort:** do
  not deploy this wave's code ahead of Wave 4.5, 5.1a, **5.1b**, 8.2a, Task 8.1 completion, or
  Task 8.2 resolution (round-25
  correction — the Abort sentence and the closing summary below had both dropped 8.2a in the same
  round that added it to Go, recreating exactly the gate-propagation ambiguity round-24 existed to
  close; **round-33 adds 5.1b to Go, Abort, and the closing summary in the same edit, per that
  lesson**) — it is not gated with Wave 5's bundle or Wave 6, and does not need to wait for either;
  only for Wave 4, the standalone 5.1a, 5.1b, and 8.2a classes, and its own two open items.
  _Requirements: 7.3c, 7.4; design.md D5_
- [ ] **8.8a AWS-only: CloudFront forwards `traceparent` on `/api/*` (round-15 addition — verified
  gap: `infrastructure/terraform/aws/modules/cdn/main.tf`'s `/api/*` `ordered_cache_behavior`
  forwards only `Authorization`, `Content-Type`, `Accept`, `Origin`, and `X-Internal-Api-Key`
  (line 146); a client-supplied `traceparent` on the public login URL is stripped before ever
  reaching the gateway, so on AWS the gateway starts a *new*, unrelated trace and Task 8.9's
  exact-trace-id log query can never match).** Add `traceparent` to that `forwarded_values.headers`
  list; apply via Terraform; then verify live against AWS specifically — CloudFront distribution
  changes are not instant, so live verification SHALL wait for the distribution's `Deployed` status
  (not just a successful `terraform apply`) before treating this task as done. **Verification is
  config-level only, not a runtime probe through 8.9 (round-16 correction — an earlier draft said to
  confirm via "the same log-query mechanism 8.9 itself uses," but that mechanism only exists inside
  8.9's own setup (a deliberate non-golden write, idle-threshold aging, a real login), which is
  itself blocked on this task completing first on AWS: a genuine circular dependency).** Confirm via
  a live `aws cloudfront get-distribution-config` call against the deployed distribution — **not
  Terraform state as a substitute (round-17 correction: an earlier draft accepted "the equivalent
  Terraform-tracked state" as an alternative, but state only proves what Terraform last intended to
  apply, not what CloudFront is actually currently serving — a failed or partial apply, an
  in-progress distribution update, or an out-of-band manual change would all diverge from it
  silently). Terraform state may be checked as a supplementary cross-check, never as the completion
  evidence itself.** — that the live `/api/*` behavior's forwarded-headers list now
  includes `traceparent`, and record the distribution's resulting live ETag/config version as this
  task's own completion evidence — Task 8.9 is what provides the actual end-to-end runtime proof that the
  header physically arrives, using this task's completion as its own prerequisite, not the other way
  around. **Azure needs no equivalent change** — its
  Container Apps ingress has no header allowlist in front of the gateway (verified directly: no Front
  Door/Application Gateway/CDN resource exists anywhere in `infrastructure/terraform/azure`).
  **Blocks Task 8.9 specifically on whichever run targets AWS; does not block Wave 8's own build
  (8.1-8.8) or the reset mechanism's actual runtime behavior**, neither of which ever depended on a
  client-supplied trace id. Adds infrastructure scope beyond what the master plan's B2-owned-backend
  list previously described — reflected there too.
  _Requirements: design.md D5 (8.9's causal-correlation proof)_
- [ ] **8.8b Azure deployment-evidence foundation — a prerequisite of 8.9, not work deferred to
  Wave 10.** This task owns the `deploy-azure.yml`/verification-script changes that make an exact
  serving revision reproducible. The longer rationale currently embedded in Task 10.2 Step A is
  explanatory only; implementation and completion belong here, before any 8.9 serving proof.

  **One Azure run order:** add workflow-level `concurrency` with the fixed group
  `wealth-production-azure-deploy` and `cancel-in-progress: false` in `deploy-azure.yml`, so direct
  dispatch and reusable-workflow callers serialize against the same production target. A valid
  digest from each Buildx push does not otherwise stop an older concurrent run from deploying
  after a newer one or leaving a mixed-service production state.

  **One immutable image contract:** on the normal build path, replace the separate Docker build and
  push steps with one `docker buildx build --push --no-cache --pull --metadata-file metadata.json`
  step, validate `containerimage.digest` against `^sha256:[0-9a-f]{64}$`, publish it as
  `steps.build.outputs.digest`, and update both each selected Container App and
  `market-data-refresh-job` (when market data is selected) by `repository@digest`. A selected
  `market-data-service` deployment SHALL fail if the paired refresh Job is absent; the update and
  `snapshot_container_apps.py compare` both reject that missing selected Job rather than warning
  and silently skipping it. Preserve the existing prebuilt-digest branch and its proof that the
  merged build/push step was skipped.

  **One artifact namespace and mode matrix:** the producer and upload steps both run only when
  `deploy_mode == 'scoped' && digest_mode != 'true'`. Each matrix instance writes `digest.txt` and
  uploads `service-digest-${{ matrix.service }}` with `if-no-files-found: error`. The separate
  `aggregate-digests` job declares `needs: [preflight, deploy]`, checks out the repository, downloads
  `pattern: service-digest-*` to `digests/` without `merge-multiple`, and executes exactly:
  ```yaml
  env:
    SELECTED_SERVICES: ${{ needs.preflight.outputs.selected_services }}
  run: >-
    python3 .github/workflows/scripts/snapshot_container_apps.py aggregate-digests
    --artifacts-dir digests --selected "$SELECTED_SERVICES"
    --output digest-manifest.json
  ```
  The command validates exact selected-service coverage, service-name uniqueness, one
  `digest.txt` per artifact, and lowercase digest shape before writing `digest-manifest.json` and
  uploading the named artifact `digest-manifest` with `if-no-files-found: error`.
  `assert-scoped-non-interference` separately verifies aggregation succeeded, downloads that named
  artifact to `manifest/digest-manifest.json`, and passes it to `compare --digest-manifest`; its
  prebuilt-digest branch continues to use `--requested-digest`. Full mode runs neither aggregation
  nor scoped comparison. The named manifest download is required and fails closed if absent; the
  producer-side pattern's zero-match case is instead caught by the aggregator's exact-coverage
  validation. The two artifact namespaces SHALL remain disjoint.

  **Rerun contract:** artifact retrieval is current-attempt scoped. Partial reruns of only an
  aggregator or consumer are unsupported and SHALL fail closed with instructions to use **Re-run
  all jobs**; no task may silently mix artifacts from different attempts. Namespace separation
  prevents a prior manifest from ever matching the per-service input pattern if cross-attempt
  retrieval is introduced later.

  **Executable owners and gates:** `.github/workflows/scripts/snapshot_container_apps.py` owns the
  new positional command and handles it without calling Azure capture;
  `scripts/tests/test_snapshot_container_apps.py` owns CLI-level success/failure tests for parser wiring,
  directory layout, output writing, missing/extra/duplicate services, malformed digests, and the
  selected-missing-refresh-Job case. `scripts/tests/test_deploy_azure_service_allowlist.py` owns
  named graph assertions for the exact interpreter/path, `selected_services` output, checkout,
  job needs/conditions, producer and consumer gates, artifact names/paths, mode branches,
  concurrency group, and missing-Job fail-closed behavior; the prebuilt-digest suite is updated for
  the merged step. Add `actionlint` to active CI, pinned to v1.7.12's Linux-amd64 archive and verify
  SHA-256 `8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8`
  before execution, so valid substrings cannot hide invalid workflow YAML or expressions. **Go:** all three
  suites plus actionlint green, then a normal scoped Azure deployment proves the selected Apps (and
  refresh Job when selected) are digest-qualified and the manifest comparison passes. Record the
  workflow run and revision images. **Abort:** 8.9 cannot start without this evidence.
  _Requirements: 7.3c; master plan Azure-first release evidence_
- [ ] **8.9 STOP/GO — live serving proof, not merely "8.1-8.7 green" (round-12 addition, corrected
  round-13 on four separate points — dependency, causality, cleanup, and an overclaim; corrected
  again round-14 on four further points — the causal-proof mechanism, binding the evidence to the
  deployment it certifies, a missing Abort action, and cleanup's own postcondition verification;
  corrected again round-15 on three further points — an AWS transport gap in the causal-proof
  mechanism itself, the missing owner of the log event it queries for, and the evidence manifest's
  own configuration blind spots; corrected again round-16 on two further points — 8.8a's own
  verification circularly depended on this task, and this task's Abort clause rolled back working
  application code on a log-query tooling failure that proves nothing about it; corrected again
  round-17 on two further points — the Abort clause still classed unrelated setup failures and an
  already-observed log-ingestion-gap outcome as evidence of a broken deployment, and its retry path
  ran into state cleanup already having destroyed the preconditions it assumed; corrected again
  round-18 on five further points — 8.3/8.5's loopback target couldn't resolve its own port under
  8.7a's RANDOM_PORT test topology, an override-backed run could never satisfy Wave 10.2 since its
  own required restoration step invalidates the manifest it produces, Class 1's own two conditions
  still didn't attribute a failure specifically to Wave 8's code, the evidence manifest didn't retain
  enough to reproduce a Class-2b retry precisely, and 8.7a's tracing config was unspecified while
  4.4's own default risked a real OTLP export; corrected again round-19 on three further points —
  Class 1 treated the mere presence of the new skip event as gateway-defect evidence when
  design.md D5 already classifies everything GC.8 induces (`409`, `429`, `5xx`, timeouts, connection
  failures) as undifferentiated operational signals, Class 2c assumed a benign race on a non-golden
  post-success state without checking the available version evidence, and the skip event's own trace
  correlation was never actually tested; corrected again round-20 on three further points — round-19's
  own fix swung too far and made the canonical deployment defects this task exists to catch
  permanently non-rollback-eligible, the one *immediately* rollback-eligible reason
  (`gateway_orchestration_error`) had zero test coverage of its own, and the two trace-correlated
  events were wrongly assumed mutually exclusive across the timeout boundary; corrected again
  round-21 on four further points from external review — the Diagnosed tier named comparison
  categories without an authoritative source for any of them, `409`/`429` were wrongly treated as
  structurally incapable of reflecting a deployment defect, the dual-event GC.8 test stubbed
  portfolio-service's own response instead of exercising its real commit-and-log chain, and outcome
  (e) collapsed every accompanying skip reason into one benign-race narrative that only actually fits
  `overall_timeout` — then five further points from a parallel self-audit dispatched the same round:
  `elapsedMillis` wasn't obtainable from a plain reactive timeout handler without explicit bracketing,
  `rateLimitKeyCategory` was invented for a reset-leg route that has no rate limiter at all,
  `eligibility_shape_failure` was diagnosis-eligible with no checklist bullet, outcome (e) crossed
  with `gateway_orchestration_error` was claimed by two contradictory classes, and the manual-secret
  cloud-CLI commands were wrong for Azure while a cheaper, already-available probe went unused;
  corrected again round-22 on six further points — the manual-reset probe couldn't validate 8.5's own
  key reader since 5.1 and 8.5 read `INTERNAL_API_KEY` independently (now a shared
  `InternalApiKeyProvider` bean, 5.1/8.5), the fallback secret diagnostic instructed printing raw
  production secrets with no non-disclosure procedure, the `CLOUDFRONT_ORIGIN_SECRET` diagnosis read
  only the gateway side (now a live CloudFront-side functional probe that never touches the secret),
  `rateLimitKeyCategory` was removed entirely as unobservable from the actual rate-limiter code,
  the dual-event race was wrongly asserted to have one fixed ordering when both are physically
  real (classification no longer depends on order, and query-return order was removed as unsound
  evidence), and `elapsedMillis`/timeout attribution used the wrong clock and an undefined tolerance
  (now `System.nanoTime()` with an explicit band); corrected again round-23 on seven further points
  — round-22's shared provider, owned by 5.1, silently made Wave 8's build depend on Wave 5 against
  its own declared independence (provider extracted to standalone Task 5.1a), contradicted the
  frozen design.md/master-plan two-reads architecture (both now amended), the CloudFront public
  probe tested the distribution's own header rather than the loopback dispatch that actually fails
  (replaced by 8.7's `originVerifyHeaderAttached` call-site evidence; the probe demoted to
  ingress-health), a manual-reset `403` was routed to Class 1 rollback though rolling back the
  gateway changes neither side's environment values (now configuration repair, never rollback),
  `internalApiKeyAttached` was defined only inside a `409` aside while its load-bearing use is
  `403`s (both attach booleans now general per-dispatch fields with GC.8 assertions), 5.5 retained
  the pre-provider raw-value constructor seam, and step 6 overclaimed the CloudFront ETag as
  origin-secret alignment evidence (now separated: ETag = config fingerprint, the run's own
  successful login = alignment proof, either side's change invalidates); corrected again round-24 on
  five further points — the two attach booleans each conflated "the environment is not configured"
  with "the code failed to attach a configured value," and this Abort clause was reading that merged
  `false` as Class 1 rollback evidence, so a blank variable, or an ordinary Azure deployment where
  the origin secret is deliberately never provisioned and its filter is a deliberate no-op, would
  have authorized rolling back correct code (each is now a `configured`/`required` plus `attached`
  pair, with a new `reset_key_not_configured` pre-dispatch reason, and only
  `configured`/`required=true` together with `attached=false` reaching Class 1); the origin-secret
  provider both 8.3 and round-23's "structurally impossible" claim leaned on had no owning task,
  API, refactor assignment, or tests (now Task 8.2a); design.md's and the master plan's earlier
  normative passages still mandated the superseded filter-local `System.getenv` read and raw-value
  test seam alongside the new provider architecture, leaving two contradictory architectures in the
  frozen documents (all amended in place, not merely supplemented); and GC.8 could satisfy the
  attach assertions tautologically from provider state while the header never reached the wire (now
  asserted against independently observed header presence at the downstream stub); corrected again
  round-25 on five further points, all downstream of round-24's own fixes — GC.8's wire-comparison
  rule demanded observations that structurally cannot exist in two of its own cases (a no-dispatch
  skip has no outbound request to inspect, and the clean `required=false` path emits no skip event
  to carry a boolean at all — the former now asserts absence-of-dispatch with `attached=null`, the
  latter is split into an induced-unrelated-failure case plus a no-event-on-clean-path companion);
  8.2a leaned on "existing tests" for `CloudFrontOriginVerifyFilter` that a search of
  `api-gateway/src/test` proves do not exist (replaced with an explicit seven-case regression
  matrix written as part of the refactor); the `attached` fields were declared boolean while
  undefined on the one reason whose point is that no request exists (now tri-state, `null` = no
  dispatch, its non-nullness doubling as the dispatch-attempted signal); the Diagnosed tier's "any
  other `reason`" header was contradicted by its own first bullet (the exception is now in the
  header, and `reset_key_not_configured` has its own Class 2h); and 8.8's Abort sentence plus both
  dependency summaries dropped 8.2a in the same round that added it to Go); corrected again round-26
  (Azure-first scope; two AWS-only findings on Lambda revision/config rollback classification and
  CloudFront origin-secret sanitization deferred to AWS enablement) on three further points — the
  eligibility leg's `attached=null` was claimed to mean "not dispatched," but that leg always
  dispatches regardless of origin-verification requirement, so Azure's normal
  `dispatchAttempted=true, required=false` state directly violated the stated invariant (now a
  separate, explicitly-asserted `dispatchAttempted` field per leg, with `attached`'s `null`
  meaning "not applicable" for whatever leg-specific reason, never inferred from dispatch alone);
  `reset_key_not_configured` was still structurally captured by the Abort clause's outer framing
  sentence and by Class 2d's own condition, both unqualified, despite the inner Class 1 — Diagnosed
  bullet and Class 2h correctly excluding it (all three now qualified, and a co-occurring success
  event routed to its own Class 2i as a logical-impossibility signal, direct Class 1, rather than
  through 2g's checklist delegation, which has no bullet for that reason); and the event schema
  left `httpStatus` undefined for the new pre-dispatch reason (now explicit, `null`, alongside the
  rest of that reason's full asserted shape in GC.8); corrected again round-27 (Azure-first scope;
  the two AWS-only findings remain deferred) on three further points — both new `dispatchAttempted`
  invariants failed specifically on the pre-dispatch `gateway_orchestration_error` path, since that
  failure can hit either leg regardless of the leg's own `configured`/`required` state, and GC.8's
  induced case for that reason never asserted `dispatchAttempted` or `attached` at all (now split
  into one induced case per leg, each asserting the full event shape including both fields, GC.8);
  GC.8's wire-equality rule still demanded comparing a `null` `attached` value against a
  wire-observed `false` on the eligibility leg's `required=false`-with-dispatch case, which
  round-25's blanket "in every case where a dispatch occurs" wording had missed (now scoped to
  `configured`/`required=true`, with the `null` case asserted as three independent facts rather
  than an equality); and Class 2i sat under Class 2's own no-rollback governing header while itself
  mandating immediate rollback, a structural contradiction (now relocated beside Class 1 —
  Immediate as a second immediate condition, restoring Class 2 to eight sub-cases); corrected again
  round-28 (Azure-first scope; AWS-only findings still deferred) on four further points — the event
  schema defined one `dispatchAttempted` "per leg" while permitting `leg=overall`, whose deadline
  can expire while either leg is active and after the other already dispatched, so a single field
  keyed to the event's `leg` structurally could not record what happened (now two explicit fields,
  `eligibilityDispatchAttempted`/`resetDispatchAttempted`, with both legs' full field sets on every
  event and the overall-timeout pair semantics asserted in GC.8's timeout-boundary case); GC.8's
  reset-leg orchestration-error case was unexecutable through its own specified seam, since 8.5
  builds its target from 8.3's shared port seam and corrupting it fails the eligibility leg first
  (8.5 now owns a leg-specific construction seam, and the two cases name their own distinct fault
  mechanisms); Class 2h's "a rollback can never fix a blank key" premise was factually wrong on
  Azure — the env *reference* is revision-scoped template configuration
  (`container-app/main.tf:87-93`, single-revision mode at :16) while only the secret *value* is
  app-scoped (:99-105), so a template regression in the new revision is revision-attributable and
  rollback-fixable (the reason is again diagnosis-eligible with its own names-only
  template-comparison bullet resolving template-regression to Class 1, and 2h narrowed to the
  diagnosed app-scoped case); and GC.8's `required=false` eligibility case asked the classifier to
  treat a deliberately induced `5xx` as a "`403`-adjacent shape" (now pinned:
  `reason=eligibility_non_2xx_status`, `httpStatus=500`, Class 2d); corrected again round-29
  (Azure-first scope; AWS-only findings still deferred) on four further points — round-28's own
  dispatch pair was still phase-ambiguous on `leg=overall`, its `true/false` value defined as "during
  the eligibility read **or between the legs**," two states implying opposite diagnoses (downstream
  latency vs. this revision's own orchestration stalling after a completed read), so an explicit
  `overallTimeoutPhase` (`eligibility_in_flight`/`between_legs`/`reset_in_flight`) now records the
  phase in flight, with `between_legs` made Class 1 attribution in the Diagnosed tier's timeout
  bullet regardless of the elapsed band, Class 2f's benign-race reading conditioned on
  `reset_in_flight`, and GC.8 induced for the previously untested `true/false` branch; the round-28
  `reset_key_not_configured` diagnosis concluded "templates equal ⇒ app-scoped secret value," which
  does not follow (a provider/wiring regression, an effective-environment problem, or a mis-emitted
  event produce the same equal template text), so the already-specified manual-reset probe — same
  `InternalApiKeyProvider`, same process, no secret value touched — is now run before any class is
  assigned, with `503` corroborating the blank (Class 2h), `200` contradicting it (Class 1), and
  `403` routed to the misalignment bullet; `gateway_orchestration_error` had contradictory scope,
  8.7 including post-success-handler throws since round-21 while the Abort clause called it
  "independent of any network response" and GC.8 tested only pre-dispatch construction faults (the
  reason now carries two explicitly-defined evidence shapes, the Class 1 wording covers both, and
  GC.8 gained a post-response success-handler fault case where dispatch is `true`, `attached` is a
  genuine boolean, and a legitimate `demo_reset_succeeded` may coexist); and GC.8's
  orchestration-error cases pinned both dispatch fields but only the failing leg's `attached`,
  leaving the non-failing leg's `configured`/`required` and `attached` unasserted, so a partial
  implementation could pass GC.8 while violating 8.7's own round-28 both-legs-every-event rule (all
  six fields now pinned per case); corrected again round-30 (Azure-first scope; AWS-only findings
  still deferred) on five reviewer findings — the manual-reset probe was claimed to hit "the same
  process" while api-gateway runs up to three load-balanced replicas (`azure/main.tf:217`), so the
  event gained a `replicaToken` field (`CONTAINER_APP_REPLICA_NAME`) and the diagnosis a
  trace-correlated replica-matching discipline with bounded fleet repetition; the probe had no
  legitimate `409` outcome despite the route requiring `expectedVersion`, so the probe now takes a
  fresh GC.6-disciplined version read and classifies `409` as conclusive non-blank evidence
  alongside `200`; `between_legs` was made immediate Class 1 on localization alone, though CPU
  starvation, JVM pauses, or scheduler delay stall the same stretch without any code defect — now
  reproduction-gated (bounded re-run, at most twice; reproduced = Class 1, one-off = Class 2d);
  the load-bearing `between_legs` phase got its own deterministic GC.8 case (a seam-injected
  post-read stall); and the orchestration-error governing sentence still said "before or
  independent of a network response" while including post-response throws (rewritten — the
  defining fact is whose code threw, not timing — with GC.8's post-response case made
  real-chain-required, its success-event coexistence a REQUIRED assertion); then a three-agent
  parallel self-audit of these changes (per explicit request) found and fixed eight further
  points before external review — four schema fields (`timeoutScope`, `attemptedTarget`,
  `elapsedMillis`, `replicaToken`) and the reset-`409` evidence quartet had zero executable GC.8
  coverage while 8.9's Diagnosed tier branches on three of them, and the round-28 both-legs rule
  was enforced only inside the orchestration-error block (all now asserted via a promoted
  governing rule); the probe's `503` discriminator conflated two emitters of the identical
  status+code — portfolio-service's own `InternalApiKeyFilter.java:59-62` returns the same
  `503 internal_api_key_not_configured` when *its* secret is blank, which a healthy gateway
  relays, falsely corroborating a gateway blank (now discriminated by body shape or
  trace-correlated emitter — the sixth confirmed instance of the tracked evidence-oracle
  mismatch); the probe tree had dead ends (non-enumerated outcomes, a standing `403`
  contradiction never assigned a class, the emitter-existing-but-unobserved arm) — all given
  terminal classifications; the impossible dual-event phase combinations could terminate as
  benign Class 2d through the solo band rules (now an explicit terminal rule: attribution, Class
  1, mirroring the dual-`reset_key_not_configured` precedent); 2d's and 2h's summaries were stale
  against the round-30 probe paths (updated, with 2h widened to the stale-replica state and its
  revision-restart repair); the overall deadline's chain-cancellation semantics GC.8's
  between-legs case depends on were nowhere stated (now in 8.6); the `httpStatus` null convention
  silently discarded the post-response form's received status and left `eligibility_shape_failure`
  undefined (both pinned); and Class 2's governing header now names 2f/2g as triage sub-cases
  whose diagnosis outcome may resolve out of Class 2, with the outer framing sentence naming both
  immediate conditions.** corrected again round-31 (Azure-first scope; AWS-only findings still
  deferred) on five further points — the replica-correlation mechanism cited "the log backend" for
  a trace-correlated record of the probe's own outcome, but Task 5.1 specified no logging
  statement at all to produce one, so the diagnosis pointed at an emitter that didn't exist (5.1
  now sets an `X-Gateway-Replica-Token` response header on every outcome it produces — a synchronous
  signal needing no log backend, query, or ingestion delay, tested in 5.3a; 8.9's probe procedure
  reads it directly); a same-replica gateway `503` was read as sufficient to conclude an app-scoped
  secret-value gap, but it equally fits a provider-implementation regression in 5.1a's own
  resolution/normalization code, which a rollback *would* fix (a bounded, non-disclosing raw-env
  presence check on the correlated replica — boolean-only, mirroring the existing secret-comparison
  discipline — now discriminates the two before Class 2h is assigned); `between_legs` reproduction
  on a fresh run was treated as proof against a platform cause, but a chronically degraded single
  replica reproduces the same stall on every run that lands on it — reproduction now requires at
  least one cross-replica observation to attribute, with same-replica-only reproduction staying
  Class 2d with the specific replica flagged for platform-health follow-up; `attemptedTarget` and
  `elapsedMillis` had only a per-call bracketing mechanism, which cannot express `between_legs`
  (no call in flight to name a target for) or the whole-orchestration span an `overall_timeout`
  actually measures (`attemptedTarget=null` on `between_legs`, phase-sensitive otherwise;
  `elapsedMillis` now sourced from a second bracket at 8.6's own orchestration-entry point — a
  self-caught correction mid-round: an earlier draft claimed this second timer "already" existed
  as a side effect of the overall deadline operator, which is false for the identical round-21
  reason the per-leg bracket was needed in the first place, so 8.6 now specifies it explicitly);
  and the `between_legs` GC.8 case asserted only the emitted `resetDispatchAttempted=false` field,
  trusting the code under test to self-report the one guarantee — 8.6's cancellation — that case
  exists to exercise, with no independent wire-level check and no explicit wait for the seam's
  post-deadline window to actually close (now an independent stub assertion of zero reset
  requests, checked after that window closes).** corrected again round-32 (Azure-first scope;
  AWS-only findings still deferred) on five further points — the new `X-Gateway-Replica-Token` header
  was specified as "set" with no ordering or singularity guarantee against Spring Cloud Gateway's
  own header-copy behavior on the proxied path, so an unauthoritative write could be silently
  overwritten or duplicated for the one signal that authorizes rollback (5.1 now requires the
  filter's own value to replace, never merge, once downstream headers are available, exactly one
  occurrence, tested in 5.3a against a stub returning a conflicting value); the raw-environment
  presence check used bash's bare `-n`, which reports a whitespace-only value as present while
  5.1a's own `.isBlank()`-style predicate would correctly call it blank — the mismatch would have
  misclassified a correctly-behaving provider as a code regression (the check now strips
  whitespace before testing length, matching the provider's exact predicate on all four states:
  unset, empty, whitespace-only, nonblank); the `az containerapp exec` diagnostic assumed it
  always succeeds and targeted only `--replica`, when Azure's own multi-replica invocation needs
  revision and container identified too, and the replica can be gone or inaccessible (now resolves
  the full target and treats an exec failure as a failed observation — Class 2d's discipline, never
  authorizing rollback on an unobtainable check); cross-replica `between_legs` reproduction was
  read as sufficient on its own for Class 1, but a distinct `replicaToken` doesn't establish a
  distinct host — shared node-level or environment-wide contention can affect multiple replicas
  identically (now requires corroborating platform-health signals showing no shared pressure
  during the reproduction windows, or stays Class 2d pending that evidence or a controlled
  reproduction under verified healthy capacity); and GC.8's promoted governing rule required
  `timeoutScope`/`elapsedMillis` merely populated, which an implementation could satisfy with a
  wrongly-scoped or constant value despite 8.9 branching on the exact ones (now asserts exact
  scope and a deterministic elapsed-time band per case, with the `reset_in_flight` case additionally
  proving `elapsedMillis`'s bracket source via an eligibility-leg-delay lower bound only the
  correct sourcing can satisfy).** **Corrected again round-33 (Azure-first scope; AWS-only findings
  still deferred) on five further points, three of them defects in round-32's own repairs — the
  shell blank-predicate round-32 introduced to replace bash's `-n` claimed to reproduce
  `String.isBlank()` exactly and does not (POSIX `[:space:]` is locale-dependent and
  byte-oriented; `.isBlank()` is Unicode code-point based via `Character.isWhitespace`), so the
  fix moved the misclassification rather than removing it — 5.1a now ships an independent
  `InternalApiKeyPresenceProbe` (a `main` class in the same module, forbidden from sharing
  resolution code with the provider it checks) that 8.9 runs in the target replica via `java -cp`,
  applying the real JDK predicate in the same JVM and locale; round-32's "cross-replica recurrence
  plus healthy CPU/memory ⇒ Class 1" over-claimed what those two metrics exclude (JVM pauses,
  Reactor scheduler starvation, network latency, shared-node contention and throttling all stall
  the inter-leg stretch while CPU and memory read normal), so `between_legs` now stays Class 2d
  until either application-level blocking evidence (thread dump/JFR/BlockHound on the reactive
  path) or a controlled reproduction isolating the gateway code path exists; round-32's
  elapsed-time band assertion was satisfiable by an implementation that emits the configured
  deadline `D` and reads no clock at all — the fabricated value sits at the band's own centre — so
  8.6/8.7's brackets now read through an injectable monotonic-clock seam and GC.8 asserts an exact
  value derived from injected readings deliberately unrelated to `D` (which also subsumes and
  strengthens round-32's `>= 30ms` bracket-source proof, a bound the same fabricated value also
  satisfied); the replica identity had no owned source — 5.1's header and 8.7's event each read
  `CONTAINER_APP_REPLICA_NAME` independently while 8.9's diagnosis compares them as strings, and
  5.3a injected a "replica-name source" double no task defined, the exact defect class 5.1a and
  8.2a were extracted to prevent, reintroduced by round-31's own header work (now Task 5.1b,
  `ReplicaTokenProvider`, consumed by both emitters and propagated in the same edit to Wave 8's
  intro, 8.8's Go/Abort, and 5.6's bundle list, per round-25's propagation lesson), with GC.8 and
  5.3a now asserting an exact non-blank value rather than the locally-blank one a hard-coded blank
  implementation also satisfies; and 5.1's "every response this filter produces" header contract
  was tested on only three of five outcomes, leaving the two route-rejection branches 5.3a
  separately exercises free to violate it while green (both now covered).**
  **Corrected again round-34 (Azure-first scope; AWS-only findings still deferred) on four
  further points, all defects in round-33's own repairs — the presence-probe launch command,
  `java -cp <app-jar> InternalApiKeyPresenceProbe`, cannot work: api-gateway's Azure image
  (`Dockerfile.azure:65,69`) contains one Spring Boot fat jar whose application classes sit
  inside `BOOT-INF/classes`, invisible to a plain `-cp` classpath entry, which never unpacks
  that layout (5.1a's probe now ships as its own minimal, dependency-free `probe.jar`, copied
  alongside `app.jar` in the same Docker stage, launched via `java -jar /probe.jar`, with a CI
  smoke test running that exact command against the actual built image — the second, stale
  reference to the old command inside 8.9's own probe-invocation step was also caught and
  synced); 8.7's own `elapsedMillis` code example still called `System.nanoTime()` directly on
  both ends, the exact inline pattern round-33 introduced 8.6's injectable clock seam specifically
  to replace one task earlier — an implementer following the example as written would bypass the
  seam and fail GC.8's exact-value assertions (the example now reads through the same seam);
  the probe's "unit-tested... for the same four states" claim had no achievable seam, since
  `System.getenv()` cannot be set from within a running JVM at all, so an in-process test could at
  best cover a same-process string predicate and merely assume `main` wires environment and
  stdout to it correctly (now two levels: a pure classification method unit-tested directly, and
  the compiled class launched as a real child process with an explicit environment per state,
  proving the actual wiring); and `X-Gateway-Replica-Token` disclosed Azure's raw internal replica
  identifier through a route reachable by anyone holding the demo account's credentials, which
  this feature's own login page pre-fills for every visitor (`frontend/src/app/(auth)/login/
  page.tsx:62,81`, verified directly) — 5.1b now exposes a derived, unkeyed `replicaToken` (first
  12 hex characters of `SHA-256(rawName)`) to both consumers instead of the raw value, with the
  one place that still needs the real name for `az containerapp exec --replica` — introduced by
  round-32/33's own diagnosis — recovering it by locally recomputing the same formula against
  `az containerapp replica list`'s output, adding no new endpoint or secret.** **Self-caught
  mid-fix: the token-recovery insertion itself initially split a single continuous bold span
  into pieces with an odd number of new markers, orphaning the original span's pre-existing
  close — found via the same Wave-heading bisection method established round 30, fixed before
  reporting by folding the new content into the existing span rather than giving it a separate
  one, matching this document's own established convention for stacked round-N asides.**
  **Corrected again round-35 (Azure-first scope; AWS-only findings still deferred) on two P1s and
  two P2s, all defects in round-34's own repairs, none touching files this cycle — the probe
  packaging had two independent build-graph defects: `Dockerfile.azure`'s builder invoked only
  `bootJar`, so a `probeJar` task nothing calls would never actually run, and the existing wildcard
  `COPY .../*.jar app.jar` — left untouched by round-34's own "stays exactly as it is" claim —
  would match both jars once `probe.jar` existed alongside `app.jar`, which Docker's `COPY`
  rejects against a single-file destination (both jars now get fixed `archiveFileName`s, the
  builder invokes both tasks explicitly, and the wildcard copy is replaced by two exact-path
  ones); the token-to-replica recovery's `az containerapp replica list` call was claimed
  "already scoped to the current revision" with no revision flag at all, but Azure's own CLI
  contract defaults an unqualified call to the *latest* revision, which can differ from the
  evidence manifest's recorded one during a deployment or rollback, and the recovery defined no
  outcome for zero matches (expected after replica recycling) or multiple matches (a truncation
  collision) — both now handled: `--revision` explicitly pinned to the recorded revision, and
  zero or multiple token matches routed to a failed observation, Class 2d's discipline, never an
  operator-selected guess; the CI smoke test's `docker run <image> java -jar /probe.jar` does not
  override the image's exec-form `ENTRYPOINT` as intended — it appends those tokens as arguments
  to it, actually launching `java -jar /app.jar java -jar /probe.jar` rather than the probe (now
  `docker run -d` to boot the container normally, then `docker exec` to run the probe inside it —
  which is also the closer analog to `az containerapp exec`'s own already-running-replica model);
  and the probe's own evidentiary claim overclaimed "the same JVM ... as the provider," when
  `java -jar /probe.jar` genuinely launches a separate JVM process — corrected to what the
  diagnosis actually relies on: the same container, image, JDK, and container-scoped
  environment, two independent reads of one fixed environment, never one process vouching for
  itself.**
  **Corrected again round-36 (Azure-first scope; AWS-only findings still deferred) on one P1 and
  three P2s, all defects in round-35's own repairs — the CI smoke test round-35 specified had no
  actual owner: nothing named a Gradle task or workflow job to invoke it, `ci-verification.yml`'s
  existing `docker-build-verify` job builds `api-gateway/Dockerfile` (the AWS variant) via `docker
  compose build`, never `Dockerfile.azure`, and no gate required the test to be green — meaning
  the packaging regression it exists to catch could ship, merge, and regress again with nothing in
  CI positioned to notice (now a named `azure-image-smoke-test` job, required by Wave 5's own Go
  gate); that same smoke test unnecessarily booted the *entire* gateway via `docker run -d` merely
  to reach an unrelated, dependency-free probe inside it, with no profile for the gateway's own
  startup env vars, no running-state check, no container-ID capture, and no unconditional cleanup
  specified (now a single `docker run --rm --entrypoint java` invocation per case, sidestepping
  the entrypoint directly rather than via a two-step run/exec sequence, needing no lifecycle
  management at all); the token-to-replica recovery told the operator to hand-reimplement 5.1b's
  UTF-8-encode-then-SHA-256-then-truncate formula in a shell one-liner, with no way to detect a
  silent divergence — which the fail-closed rule the same round added would misreport as a genuine
  zero-match observation rather than a broken reimplementation (now one shared static method,
  `ReplicaTokenFormula`, called by both 5.1b's provider and a new packaged operator tool,
  `ReplicaTokenTool`, so the two cannot drift apart by construction, with shared test vectors);
  and `ReplicaNameProvider`/`replicaName`/`X-Gateway-Replica` all carried a derived hash since
  round-34 while still being named as if they carried Azure's real replica identifier — recreating,
  inside this document's own vocabulary, the exact name-vs-value ambiguity the token-recovery step
  exists to resolve for operators (renamed throughout to `ReplicaTokenProvider`/`replicaToken`/
  `X-Gateway-Replica-Token`, a plain rename rather than a compatibility-aliasing decision, since
  nothing has shipped yet to preserve compatibility for).**
  **Corrected again round-37 (Azure-first scope; no AWS-specific issues considered) on two P1s and
  one P2, all defects in round-36's own repairs — `ReplicaTokenTool` was introduced with only
  "`probeJar`-style" packaging, no named Gradle task, `archiveFileName`, builder invocation, or
  Docker `COPY` line of its own, recreating the exact build-graph gap round-35 had just closed for
  the presence probe one task earlier — as written, the tool Task 8.9 depends on had no guarantee
  of existing in the built image at all (now a fully-specified third artifact, `replicaTokenJar`/
  `replica-token.jar`, with 5.1a's own build-graph text updated to point at 5.1b's extended
  three-task form rather than silently contradicting it); the `azure-image-smoke-test` job round-36
  added tested only the original presence probe, despite 5.1b's own claim that `ReplicaTokenTool`
  was "covered by the same job" — nothing in the job actually invoked or asserted anything about
  it, so a missing jar, missing `ReplicaTokenFormula` class, wrong manifest, or broken
  argument/stdout handling could all pass Wave 5's gate undetected (now a third invocation in the
  same job, asserting the exact token for a fixed input shared with 5.1b's own unit-test fixture,
  so the two can't silently disagree about what "correct" means); and Task 8.9's own consumption
  of `ReplicaTokenTool` left the artifact source, exact command, output format, and failure
  handling all unstated, while also implying the tool might need to run inside each candidate
  replica — unnecessary, since the formula is pure and needs only the candidate name as input, not
  any replica's own runtime state (now pinned: the jar extracted once, locally, from the recorded
  revision's own deployed image — never a different or newer build — `java -jar
  replica-token.jar <name>` per candidate, one line of lowercase hex plus exit `0` as the tool's
  own stated contract, and any deviation from that shape treated as a failed observation on that
  specific candidate, explicitly distinguished from a genuine non-match, so a broken invocation is
  never misread as conclusive evidence about the replica).**
  **Corrected again round-38 (Azure-first scope; no AWS-specific issues considered) on one P1 and
  two P2s, all defects in round-37's own repairs — Task 8.9's token-recovery pull still could not
  reliably obtain the image it named: "the recorded revision's exact image reference, from the
  evidence manifest" assumed the manifest already recorded one, but it and Step A (Wave 10.2) both
  only ever recorded "revisions/digests," never a complete, registry-qualified reference `docker
  pull` actually accepts, and neither step authenticated to the private ACR the real deploy
  workflow explicitly logs into before every pull (now both Step A and the evidence manifest
  record each service's exact `image` field read back from its own container template via `az
  containerapp revision show`, and the recovery step authenticates with `az acr login` before
  pulling, with reference-resolution, authentication, or pull failure all folded into the existing
  failed-observation surface); the extraction step assumed a `cat` binary exists in the runtime
  layer, when `Dockerfile.azure`'s own `RUNTIME_BASE` build arg explicitly supports swapping in a
  distroless base with no shell utilities at all (now the extraction step is removed entirely —
  `ReplicaTokenTool` runs directly via `docker run --rm --entrypoint java` against the pulled
  image, needing only `java`, the one binary the image is guaranteed to have); and the smoke-test
  fixture and the output-format checks both still fell short of their own stated precision — "a
  fixed, known raw replica name" was never actually pinned to a literal value, and "one line of
  lowercase hex" would accept a malformed, un-truncated 64-character digest as if it were a valid
  12-character token, misclassifying a broken tool as a mere non-match (now one literal
  input/output vector — `api-gateway--0000000-abcdefg` → `95ca17821ade`, independently computed
  and verified via two separate tools before being recorded — fixed once in 5.1b and reused by
  `ReplicaTokenTool`'s own test and the smoke test's third case, with every consumer checking the
  exact pattern `^[0-9a-f]{12}\n$` rather than a looser shape).**
  **Corrected again round-39 (Azure-first scope; no AWS-specific issues considered) on one P1 and
  two P2s — the recorded `image` field, though now pullable, was not immutable on this pipeline's
  own mainline path: `deploy-azure.yml` pushes by mutable Git-SHA tag, not digest, so re-running a
  deployment at the same commit can repoint that tag, confirmed by a live query against the actual
  deployment showing the served tag and its current digest already diverging (Step A now also
  resolves and records the ACR manifest digest immediately after reading the template's tag, and
  every later diagnosis step pulls only the digest-qualified reference, never the tag alone);
  three separate `az containerapp` commands across this task — revision show, replica list, and
  exec — all omitted `--resource-group`, and replica list also omitted `--name`, with nothing
  establishing a default resource group for the operator's session; live verification confirmed
  the commands as written did not resolve (now all three cite the same fixed constants,
  `wealth-azure-prod-rg`/`wealthprodacr` per `deploy-azure.yml:70-71`, recorded once at Step A and
  reused rather than restated); and `ReplicaTokenTool`'s own "nothing else on stdout or stderr"
  contract was checked at neither consumption point — the smoke test asserted only stdout and
  exit code, and Task 8.9's failed-observation list never mentioned stderr at all (both now treat
  any nonempty stderr as a tooling failure under Class 2d, never a silent pass).**
  **Corrected again round-40 (Azure-first scope; no AWS-specific issues considered) on one P1 and
  one P2, both requiring a fix at the deploy pipeline itself rather than another patch to Step A's
  own reading of it — round-39's "resolve the tag immediately after reading it" only closed the
  race between two reads *inside Step A*, not the much larger window between when the revision
  was actually created and whenever Step A later runs; if the pushed tag moved in between — which
  re-running the deploy workflow at the same commit does, rebuilding `--no-cache --pull` and
  re-pushing the identical tag — Step A would resolve and record the *replacement* digest,
  misreporting it as what served the run, with nothing at Step-A time able to detect the
  substitution, confirmed by a live query showing exactly this divergence against the actual
  deployment. No retrospective read can fix this; only binding the digest once, at deploy time,
  can — `deploy-azure.yml`'s "Update Container App" step is now amended to resolve the digest of
  the image it just pushed and deploy by `@sha256:...` on the mainline path (the existing
  `digest_mode` path for portfolio-service already deployed by digest and needed no change),
  making every revision's template `image` field digest-qualified by construction, permanently,
  with no window for later drift — Container Apps revisions are themselves immutable once
  created. Step A's own job collapses to reading that field back and asserting it already matches
  `@sha256:[0-9a-f]{64}$`, never resolving anything itself; a template that *isn't*
  digest-qualified is now a finding in its own right, not something to patch around. This also
  resolves the second finding for free: with every production deployment digest-qualified by the
  same mechanism regardless of which path deployed it, Step A needs no branch between tag- and
  digest-qualified references at all — the tag-resolution logic round-39 added is removed
  entirely rather than conditionally scoped.**
  **Corrected again round-41 (Azure-first scope; no AWS-specific issues considered) on two P1s and
  one P2, all defects in round-40's own repair — the digest-binding fix still queried a *tag*
  right after pushing to it, and with no `concurrency:` guard anywhere in `deploy-azure.yml`
  (verified directly — none exists), two runs of the same commit can interleave: run A pushes,
  run B overwrites the tag, run A's own "resolve what I just pushed" query reads run B's digest
  instead — moving the mutable lookup earlier in the job never removed its mutability (now the
  digest is captured directly from the push operation itself, via `docker buildx build --push
  --metadata-file` and its `containerimage.digest`, sourced from the push response, never a
  subsequent read any concurrent run could contaminate, with the result validated against
  `^sha256:[0-9a-f]{64}$` before anything trusts it); the scoped-deploy verifier,
  `snapshot_container_apps.py compare --git-sha`, checks that each selected app's image contains
  the git SHA as a substring, which a digest-qualified image never does — the digest-binding fix,
  left otherwise unaccompanied, would have made every scoped deployment's own safety check fail
  the moment it started passing for the reason it exists (now each matrix job instance uploads
  its resolved digest as its own artifact, a new `aggregate-digests` job merges them into one
  manifest keyed by service, and the verifier gains a `--digest-manifest` path checking each
  selected service's own digest instead of one shared git-sha scalar); and the "every matrix
  service" claim never actually covered `market-data-refresh-job`, which shares a matrix job
  instance with `market-data-service`'s own Container App update but still deployed by tag
  separately, and which the verifier already deliberately treats as a pair with that Container
  App (now the Job reuses the identical digest already resolved earlier in the same job run, and
  the verifier's own Job-pairing check compares against that manifest entry instead of the git
  SHA too).**
  **Corrected again round-42 (Azure-first scope; no AWS-specific issues considered) on one P1 and
  two P2s, all defects in round-41's own repair — the manifest-consuming job was named "verify,"
  but `verify` is the unrelated post-seed live-HTTP demo check with no Azure login and no call to
  `compare` anywhere in it; the job that actually owns the snapshot comparison is
  `assert-scoped-non-interference`, wired in instead, with `aggregate-digests` gated to run only
  for the mainline scoped path — never full deploys or the already-correct `digest_mode` path —
  and its own `needs: aggregate-digests` addition relying on `assert-scoped-non-interference`'s
  existing `if: always()` condition, which already tolerates a legitimately-skipped dependency,
  rather than inventing new skip-handling; the Buildx replacement silently dropped the existing
  build's `--no-cache --pull` flags, which digest-binding work was never in scope to change (both
  restored); and the manifest schema contradicted itself between its own two descriptions — one
  added a redundant `"market-data-refresh-job"` key the verifier-side text never read, which
  already correctly reused the `"market-data-service"` entry for the Job pairing — collapsed to
  the one schema both sides actually agree on, with exact selected-service coverage, lowercase
  `sha256:[0-9a-f]{64}` validation, duplicate-artifact rejection, and test coverage for each of
  those failure shapes now pinned rather than left to an implementer's guess.**
  **Corrected again round-43 (Azure-first scope; no AWS-specific issues considered) on one P1 and
  two P2s, all defects in round-42's own repair — `aggregate-digests` declared `needs: deploy`
  while its own `if:` read `needs.preflight.outputs.deploy_mode`/`digest_mode`, but GitHub Actions
  only exposes `needs.<job>` for jobs listed directly in the current job's own `needs:` key, never
  transitively through a job that itself depends on them — `preflight` was never in scope for it
  to read, so the gate could not evaluate as intended (now `needs: [preflight, deploy]`, both,
  with the condition also checking `needs.preflight.result == 'success'`); the mode-topology
  description claimed `assert-scoped-non-interference` "does not execute" whenever
  `aggregate-digests` is skipped, which is false — the job's own condition checks only
  `deploy_mode == 'scoped'`, never excluding `digest_mode`, so it genuinely runs for both, taking
  the unaffected `--requested-digest` branch when `aggregate-digests` alone is skipped (now stated
  correctly, with the three mode cases — full, normal-scoped, `prebuilt_digest`-scoped — spelled
  out explicitly, and the digest-artifact upload step gated identically to the Build/Push steps it
  depends on, since a `digest_mode` run's matrix instance never computes a Buildx digest to upload
  in the first place); and the workflow-structure test coverage stopped at the data layer —
  round-42 tested `snapshot_container_apps.py`'s own behavior but nothing exercised the YAML graph
  itself, including the exact `needs.preflight` omission this round's own P1 was caught by
  (`test_deploy_azure_service_allowlist.py` now gains six graph assertions, and its existing
  `test_unselected_services_are_not_redeployed_by_tag` — which only ever checked that two
  unrelated strings appear somewhere in the `deploy` job's text, never the actual `--image`
  construction, and so never actually prohibited tag deployment — is renamed and strengthened to
  assert the digest-qualified form directly). Self-caught mid-fix: the test-coverage edit itself
  initially deleted the single close marker the surrounding continuous span depended on while
  adding an unrelated self-contained pair elsewhere, found via the same structural check
  immediately after the edit landed and fixed by restoring the original close rather than trusting
  the new pair's own even count.**
  **Corrected again round-44 (internal three-agent parallel audit — CI/CD ground truth,
  cross-document consistency, implementability/precision, every finding re-verified against real
  source before acceptance) on two P1s and a set of P2s. P1s: Step A's Buildx merge never
  inventoried the existing consumers of the `build`/`push` step ids it deletes — the workflow's
  own digest-mode proof step (`deploy-azure.yml:238-256`) and two CI-required tests in
  `scripts/tests/test_deploy_azure_prebuilt_digest.py` would both break, on exactly the
  `prebuilt_digest` path rounds 40–43 kept promising was untouched (both now amended in the same
  change, the proof step's single-outcome form sound by construction); and this task's own 503
  discriminator offered a trace-log fallback querying a log line no emitter produces — the seventh
  evidence-oracle-mismatch instance — replaced by anchoring the body-shape discrimination to
  requirements 7.3a's pinned two-field body, now asserted by 5.3a/5.5. P2s: the `replica list`
  default re-attributed (the app's own `latestRevisionName`, not anything environment-scoped),
  `$DIGEST` step-output plumbing made explicit, a named `needs.aggregate-digests.result` failure
  check added, the refresh-Job verifier's guard described accurately (never "unconditionally"),
  three stale design.md line citations re-pinned (905-919 → 926-940 after that document's own
  +21-line amendments — corrected again round-45, see below: 926-940 was itself the wrong span),
  the GC.8 timeout-boundary topology corrected to in-process-only,
  separator-independent stdout emission pinned for both packaged tools, and the round-30–43
  propagation gap closed outside this document: requirements.md 7.3a (pass-33) finally drops the
  superseded filter-local `System.getenv` architecture, design.md D5 gains the
  `X-Gateway-Replica-Token` header duty it omitted, and the master plan's stale "seven-item"
  references and missing rounds-30–43 scope note are amended in place.**
  **Corrected again round-45 (Azure-first scope) on one P1 and two P2s, all in round-44's own
  repair. P1: round-44's `$DIGEST` step-output export was never actually consumed — the Container
  App and refresh-Job update steps still read a bare `$DIGEST` in their own separate `run:` shells,
  which never received it, since a GitHub Actions step output has to be bound into a later step via
  `env:`/expression interpolation, not inherited as a shell variable; both consuming steps now
  declare that binding explicitly. P2s: `aggregate-digests`'s `needs: deploy` declaration and its
  later, correct `needs: [preflight, deploy]` restatement had been left standing side by side in
  the same passage — technically consistent in reading order, but the earlier, wrong form remained
  independently copyable; collapsed to one normative declaration, stated once. And round-44's own
  D5 fail-open re-pin (905-919 → 926-940) landed on the wrong bullet: design.md's pass-29–32
  amendments shifted the fail-open classification to 941-955, one bullet past 926-940's
  non-blocking-execution/OPEN-timeout-values text — re-pinned to 941-955 at all three citation
  sites.**
  **Corrected again round-46 (Azure-first scope) on one P1 and two P2s, again in round-44/45's own
  repair — the digest-binding mechanism itself, not new territory. P1: the per-service digest
  artifact this whole aggregation mechanism depends on never had a defined producer — no step
  named, no file path pinned, and `upload-artifact`'s default behavior on a missing path is to
  warn, not fail, so an entire matrix instance could complete green having uploaded nothing. Fixed
  with a named `Write digest artifact` step writing `digest.txt` ahead of the upload step, and
  `if-no-files-found: error` added to the upload step's own `with:` block, converting the silent
  default into a hard failure — plus graph-test coverage for the producer/path/flag triad, not
  only the `needs:`/`if:` shape already covered. P2s: round-45's own `env:` fix was written as
  `env: { DIGEST: ... }` nested under `run:` — neither is real GitHub Actions YAML, since
  step-level `env:` is a sibling key of `run:`, never nested inside its shell text; re-pinned to
  the literal block form at both citation sites. And round-44's manifest-download failure guard
  checked only `result == 'failure'`, silently passing `cancelled` and an unexpected `skipped` on
  the one path (normal-scoped) where the check actually runs; tightened to require
  `result == 'success'` exactly, naming the actual conclusion on any other value — the
  `prebuilt_digest` path's own legitimate `skipped` result is unaffected, since that path never
  reaches this step at all.**
  **Corrected again round-47 (Azure-first scope) on two P1s and a P2, all in the digest-binding
  mechanism's own repair, rounds 40–46. P1s: (1) the aggregation algorithm — download, merge,
  validate exact selected-service coverage, reject duplicate artifact inputs — had no executable
  owner; `compare()`'s own `digest_manifest` parameter only ever consumes an already-built dict, it
  performs none of this. Fixed by giving `snapshot_container_apps.py` a third `command` choice,
  `aggregate-digests` (matching the script's existing single-positional-`command` shape, not
  subparsers), invoked by its own workflow step with `--artifacts-dir`/`--selected`/`--output`. (2)
  The "manifest-download step" was described as one step both result-checking and downloading —
  impossible, since `actions/download-artifact` is a `uses:` action step that cannot also run the
  shell diagnostic the result-check needs. Split into two explicitly mode-gated steps: a `run:`
  guard (`Verify digest aggregation succeeded`) and the actual `uses: actions/download-artifact@v4`
  step (`Download digest manifest`), both carrying the identical `deploy_mode`/`digest_mode`
  condition, both added to graph-coverage. P2: the manifest's transport contract was never named —
  "uploaded as its own artifact," "downloads that manifest," with no name, filename, or path
  connecting the two sides. Pinned: artifact name `digest-manifest`, file `digest-manifest.json`,
  downloaded to `manifest/digest-manifest.json`, `compare` invoked with
  `--digest-manifest manifest/digest-manifest.json`; noted that `download-artifact@v4` already
  fails closed by default on a missing named artifact, unlike `upload-artifact`'s silent-warn
  default, so no extra flag was needed on the download side (asymmetric with round-46's
  `if-no-files-found: error`, which *is* needed on the upload side — the two actions default
  oppositely, worth stating explicitly rather than assuming symmetry).**
  8.8's own conditions can all be true in CI/mocked environments; nothing previously proves the
  deployed fail-open chain actually works against real cloud infrastructure — and because login is
  deliberately fail-open (GC.8), a broken deployment is **invisible by design**: login just quietly
  "succeeds" without ever resetting anything, whether the failure is a bad loopback target, a wrong
  `X-Origin-Verify` value, a timeout misconfiguration, or a missing `INTERNAL_API_KEY` read in the
  deployed process — the same class of gap Wave 10.2's own Step A closed for the manual route
  (round-15 correction: an earlier draft misattributed Step A to Wave 6, which defines no such step
  — Step A/Step B are both defined in Wave 10.2's Go action), still open here.
  **Blocked on Task 8.8b, Wave 5, and B1 Wave 7.** Task 8.8b must already have made the serving
  revision reproducible. Wave 5's holdings allowlist and B1 Wave 7's `CompositionController` must
  exist because 8.9's setup performs a real composition write as the read-only demo account; the
  rest of Wave 8's build does not. These are dependencies of 8.9 specifically, not of build tasks
  8.1-8.8. **On whichever run currently targets AWS, also blocked
  on 8.8a (round-15 addition) — without it, this proof's own trace-id correlation mechanism cannot
  work on that cloud at all, since CloudFront strips the header before the gateway ever sees it; no
  equivalent blocker exists on Azure.**
  **Executable Azure owner and preflight.** `scripts/verify_demo_reset_azure.py` owns this entire
  sequence, cleanup, evidence capture, and its machine-readable result; the task SHALL not be
  reconstructed as an operator checklist of unrelated shell snippets. Before mutating demo state it
  SHALL: verify `az account show --query id -o tsv` equals the explicitly supplied production
  subscription; resolve and require exactly one serving revision for `api-gateway` and
  `portfolio-service` in resource group `wealth-azure-prod-rg`; verify both template image fields
  are digest-qualified and agree with Task 8.8b's current-attempt manifest; resolve workspace
  `wealth-prod-la` to its `customerId`; and prove the invoking principal can read Container App
  revisions/replicas, execute the narrowly-scoped presence probe, pull the recorded ACR image, and
  query the workspace. Missing identity, zero/multiple serving revisions, missing RBAC, ACR login
  failure, or a manifest mismatch is a failed observation under Class 2d — never evidence that
  authorizes application rollback. The script queries `ContainerAppConsoleLogs_CL` with a bounded
  UTC window and escaped exact trace-id/event substrings, polls at a pinned interval/deadline, emits
  one JSON evidence manifest with the schema in step 6, and exits nonzero for every non-Go outcome.
  Its tests own command construction, KQL escaping/window bounds, zero/multiple revision handling,
  RBAC/tool failures, polling outcomes, unconditional cleanup, and manifest validation using stubbed
  Azure CLI responses; one Azure staging-safe probe proves the real CLI/RBAC surface before the
  production gate. Required least privilege is documented: Container Apps Reader, Log Analytics
  Reader on `wealth-prod-la`, ACR pull access, and the existing narrowly-authorized Container Apps
  exec permission used only for the non-disclosing probe.

  **Sequence, causally attributed to *this* login via a probe-controlled trace id, not merely timing
  (round-14 correction — round-13's "immediately before/after" narrowing only shrank the
  false-positive window from a concurrent public-demo-account visitor; it never closed it, and this
  repository already has the infrastructure to close it properly. `management.tracing.propagation.
  type: w3c` and the `spring-boot-starter-opentelemetry` dependency are already real, already-live
  in both services (`api-gateway/build.gradle:28-29`, `portfolio-service/build.gradle:30`) —
  independent of the separate, not-yet-deployed `observability-app-insights` OTLP-export spec (0/16
  tasks checked there): trace *creation and propagation* is not gated by
  `management.tracing.export.enabled`, only OTLP forwarding to Application Insights is.
  `HttpTraceContextPropagationIT` already proves this repository's W3C tracing dependency is real
  and wired at the gateway — though only for Spring Cloud Gateway's own built-in proxy client
  forwarding to insight-service, not for the custom `WebClient` calls 8.3/8.5 write (round-15
  correction: an earlier draft cited it as proving *their* propagation, which it does not exercise
  at all; 8.7a closes that specific gap, and 8.8a closes the separate AWS-transport gap below). And
  both clouds already capture service stdout, where Spring Boot's default MDC
  correlation pattern places the trace id regardless of export status: Azure via
  `azurerm_container_app_environment.main`'s existing `log_analytics_workspace_id` wiring
  (`infrastructure/terraform/azure/main.tf:106-111`), AWS via Lambda's built-in CloudWatch Logs
  capture for both the gateway and portfolio-service functions
  (`infrastructure/terraform/aws/modules/compute/main.tf`)):**
  1. Use Task 4.4a's independent golden-state oracle to construct a deliberate composition write
     advancing the demo portfolio to a known non-golden state,
     asserting the write's response `version` strictly exceeds the version observed immediately
     before it (proving the setup itself, not a coincidence, produced this state).
  2. Age the portfolio past the resolved idle threshold (8.2) — wait out the real threshold once (a
     one-time verification cost) or use a diagnostic-only, tightly-scoped threshold override for
     this run; eligibility SHALL be genuinely earned either way, never asserted without actually
     aging past it. **An override-backed run is diagnostic-only and can never be the gate-earning run
     for Wave 10.2 (round-18 correction — a genuine self-invalidation trap in the prior wording: this
     task requires an override's restoration verified before completion, and Wave 10.2 item 4 treats
     that same restoration as invalidating the evidence manifest it produced, so an override-backed
     run's own required completion step always disqualifies itself before Wave 10.2 can ever consult
     it).** Use an override freely to validate the mechanism early and often; the run that actually
     satisfies Wave 10.2 item 4 SHALL execute under the real, unmodified production threshold — no
     override, even if that means waiting out the real duration. **Record whether an override was
     used — part of this run's evidence manifest, step 6.**
  3. Mint a fresh, probe-controlled `traceparent` — a trace id used nowhere else, before or after,
     in this run.
  4. Perform the probe's own login as the demo account against the deployed gateway, **with that
     `traceparent` header attached to the login request**, and immediately re-observe via an
     identity-checked `GET /api/portfolio`.
  5. Assert the post-login state is golden, **and** confirm the causal link via the trace id —
     **bounded polling for either of Wave 8's two trace-correlated events, not the success event
     alone (round-18 addition — polling for `demo_reset_succeeded` only could not distinguish "Wave
     8's own code ran and failed" from "login failed before Wave 8's code ever ran"; both look
     identical as a bare absence. 8.7's new `demo_reset_self_call_skipped` event (round-18) closes
     that gap by giving Wave 8's own failure path a positive signal, not just its success path):**
     poll the live provider's captured stdout logs (Azure Log Analytics against the Container Apps
     environment's workspace; AWS CloudWatch Logs against the gateway's and portfolio-service's own
     log groups) over a pinned time window bounded by this run's own start/end timestamps, at a
     bounded interval, up to a bounded deadline, for **both** portfolio-service's
     `demo_reset_succeeded` event (4.2, exact trace id, expected post-reset version) **and**
     api-gateway's `demo_reset_self_call_skipped` event (8.7, exact trace id, its `reason` field) —
     **not either/or (round-20 correction — see outcome (e) below): both queries always run, since
     the two are not mutually exclusive.** Query for both regardless of what step 4's login HTTP
     response looked like, since Wave 8's own
     orchestration can begin executing even when the overall response never returns cleanly. **This
     is a text/substring or regex match over an unstructured log line, not a query against an indexed
     field (round-15 clarification — this codebase uses plain SLF4J/Logback console output
     everywhere, verified directly; no JSON/structured encoder exists in either service), which both
     providers' query languages support natively (KQL `contains`; CloudWatch Logs Insights `filter
     @message like`), but is worth naming explicitly since it is slightly more failure-prone than an
     indexed field — a query-implementation detail, not a design gap.** **Distinguish, and record
     separately, five outcomes (round-21 correction — this count drifted to "four" while a fifth,
     (e), was already defined below; the same propagation-drift class this whole review keeps
     catching):** (a) `demo_reset_succeeded` found, correct version — the deployed
     chain worked for *this* login; if step 5's own state check is nonetheless non-golden, **do not
     assume a later, unrelated write without checking the available version evidence (round-19
     correction — an earlier draft assumed a race on sight, which would misclassify a real
     reset/persistence/read defect as harmless traffic): compare the identity-checked read's own
     `version` against the event's resulting `version`** — see the Abort clause's Class 2c/2e split
     for the three-way comparison this drives, never Class 1 either way, since the success event
     itself already rules out an orchestration defect; (b) `demo_reset_self_call_skipped` found —
     Wave-8's own orchestration executed its catch path, but **this alone does not implicate the
     deployed gateway revision (round-19 correction — an earlier draft treated any occurrence of this
     event as rollback-eligible; design.md D5 (line 941-955, re-pinned round-45; round-44's own 926-940 re-pin was itself wrong — see 5.1's round-45 note) explicitly classifies timeout, connection
     failure, every non-2xx status, and shape failures as undifferentiated operational signals, not
     gateway defects, and GC.8 intentionally induces all of them): the event's own `reason` field is
     what the Abort clause actually branches on** — see Class 1 vs. Class 2d; (c) the deadline elapses, neither event found, no query
     error — genuinely ambiguous (round-17, extended round-18): could mean Wave 8's own code never
     ran at all (login failed upstream of it, in CloudFront, networking, or the auth store — none of
     which this task can distinguish from here) or that a real event was lost to an already-observed
     Azure Log Analytics ingestion gap in this environment
     (`azure-market-data-feed-broken/investigation.md:207`) — not evidence of a Wave-8 defect either
     way; (d) any query itself errors (authentication, throttling, malformed query) — a tooling
     failure, proves nothing about whether either event happened; (e) **both events found, matching
     this run's trace id (round-20 addition — a real, previously unhandled case: `demo_reset_succeeded`
     and `demo_reset_self_call_skipped` are not mutually exclusive across the timeout boundary —
     portfolio-service's commit and api-gateway's own overall-deadline firing are independent events
     that can occur in **either** order — a fast commit whose response is delayed, or slow processing
     that delays the commit past the deadline, are both real races **(round-22 clarification: an
     earlier draft asserted one specific order as "the" scenario; classification below depends only
     on trace id and the skip event's `reason`, never on which order occurred or was observed)**).**
     **The trace-id correlation itself is what makes (a)/(b)/(e) attributable at all (round-14
     correction, unaffected by this round: a concurrent visitor's login carries its own, different
     trace id, so their reset or their failure, if any, cannot produce a matching event)** — closing
     the false-positive gap round-13 could only narrow. Scheduling the run during a low-traffic window
     remains sensible defense-in-depth, but is no longer the mechanism this correctness claim depends
     on.
  6. Assemble and record this run's **evidence manifest** — fields expanded round-15 to fingerprint
     the configuration that can silently break this specific path even while application revisions
     and threshold values stay unchanged, and deliberately scoped to mechanisms that already exist
     rather than new diagnostic surface area **(round-15 correction — an earlier draft of this step
     assumed two things that don't exist: a version/rotation identifier for `CLOUDFRONT_ORIGIN_SECRET`,
     which is a plain environment variable, not an AWS-Secrets-Manager-backed secret with any such
     concept; and a hash-comparison endpoint for `INTERNAL_API_KEY` alignment, which neither service
     exposes and no task builds):** the serving gateway and portfolio-service revisions and their
     exact pullable image references this
     proof actually hit — **consuming Task 8.8b's already-deployed digest-manifest contract and
     rejecting any mismatch, never reimplementing or retrospectively resolving image identity in
     this task**; which cloud/provider served this run (`CLOUD_PROVIDER`); on AWS, the CloudFront
     distribution's config version/ETag with confirmation `traceparent` is present in its
     forwarded-headers list (8.8a) — **the ETag fingerprints *which* CloudFront configuration this
     run exercised; it does not itself prove `CLOUDFRONT_ORIGIN_SECRET` equality with the gateway's
     environment (round-23 correction — an earlier draft said the ETag "evidences" the secret, an
     overclaim: an ETag identifies a config revision, not value equality across two systems). The
     functional alignment proof for a successful run is the run itself: step 4's public login passed
     through the real distribution and the gateway's `CloudFrontOriginVerifyFilter` accepted it,
     which is only possible if the pair currently agree. Both facts are recorded separately — the
     ETag as the config fingerprint, the login's success as the alignment evidence — and the manifest
     is invalidated by *either* a CloudFront origin-configuration change (new ETag) *or* a gateway
     `CLOUDFRONT_ORIGIN_SECRET`/function-configuration change, since either side can break the pair
     while the other's fingerprint stays unchanged (see Wave 10.2 item 4)**; confirmation the
     gateway's and portfolio-service's `INTERNAL_API_KEY` reads currently agree — **evidenced by this
     run's own reset call (steps 4-5) having succeeded at all: a mismatch 403s that leg via
     `InternalApiKeyFilter`, so a completed run is itself the alignment proof**; whether a threshold
     override was in effect per step 2, and the resolved idle-threshold/timeout values from 8.2; and
     the trace id used together with its query evidence from step 5 — **recorded before cleanup runs,
     with enough detail to reproduce the exact query, not merely its verdict (round-18 addition — Class
     2b's retry, above, needs to re-run the *same* historical query, and cleanup's own state changes
     make that impossible to reconstruct approximately after the fact):** the matched event's full
     payload on outcome (a)/(b); on outcome (c) (neither event found), the exact UTC start/end
     timestamps of the polled window, the polling deadline, and the provider query string/parameters
     used, so Class 2b can re-issue the identical query rather than approximate it; on outcome (d), the
     last query error together with those same window/deadline/query coordinates, for the same
     reason; **on outcome (e) (round-21 addition — a real gap: nothing previously required recording
     what Class 2f/2g actually need), both events' full payloads, each event's own emitted timestamp,
     and the skip event's `reason` and its diagnostic fields (8.7).** **Do not record which of the two
     the log backend returned first as ordering evidence (round-22 correction — an earlier draft
     treated query-return order as part of the causal-ordering evidence Class 2f/2g need; it is not:
     both providers are eventually consistent, per this same document's own already-established
     ingestion-gap finding, so query-return order reflects backend ingestion/indexing timing, not
     emission or causal order. Each event's own emitted timestamp, recorded above, is the only
     ordering-adjacent evidence with any real meaning, and Class 2f/2g's own classification does not
     depend on it either way — see both.)** **This manifest is what Wave 10.2 item 4
     compares against the then-currently-serving configuration** before treating this proof as still
     valid for that specific exposure decision (round-14 addition, fields expanded round-15 —
     recording only application revisions left this proof blind to a CloudFront forwarding change or
     a key mismatch happening independently of any code deploy); see Wave 10.2.
  **Does not itself prove fail-open behavior (round-13 correction, unaffected by this round: the
  sequence above is a happy-path proof — successful eligibility read, successful reset — which
  demonstrates nothing about login surviving a *failing* leg. Fail-open under an injected failure is
  already covered at the unit/integration level by 8.3-8.7/GC.8; deliberately breaking a leg against
  live production to re-prove it here would be a bad trade for the proof it would add).**
  **Unconditional cleanup, including on failure, with its own postcondition verified rather than
  merely attempted (round-13 addition, strengthened round-14 to match Task 9.8's own cleanup pattern
  exactly — a failed run previously could leave the production demo portfolio stuck deliberately
  non-golden, which a real visitor would then see, and a cleanup call that fires without checking its
  result could silently fail the same way):** in a `finally` block, re-observe via an
  identity-checked read and call the real `PUT /api/portfolio/demo-reset` route (already live per
  Wave 5, reachable directly regardless of the frontend flag) with that freshly-observed version;
  **require `200`**; bounded retry on conflict, each attempt re-observing first; a conflict is still
  a test failure, not silently forgiven, matching Tasks 9.7/9.8's own cleanup discipline. **Then a
  separate, freshly identity-checked `GET /api/portfolio` SHALL confirm the persisted holdings
  actually match Task 4.4a's independently-derived exact golden set** — the same oracle Task 9.8's own cleanup uses, not
  merely trusting the reset response's stated success (round-14 addition: a `200` with an unpersisted
  or partially-applied write would otherwise still read as clean). **If a threshold override was
  used (step 2), it SHALL be restored and its restoration verified before this task is considered
  complete** — and since the override changes serving configuration, this proof's own Go/Abort
  below is evaluated against the *final*, restored production configuration, not the
  temporarily-overridden one. **This restoration is exactly why an override-backed run is
  diagnostic-only (step 2, round-18) — Go/Abort below is still evaluated for this run's own record,
  but Wave 10.2 item 4 treats that same restoration as invalidating the manifest for exposure
  purposes, so completing this task after an override never satisfies Wave 10.2 on its own.**
  **Abort — split by failure class, gated on evidence attributable to *the deployed gateway
  revision specifically*, not merely to Wave 8's orchestration code having run (round-14 addition,
  corrected round-16, corrected again round-17, corrected again round-18, corrected again round-19,
  corrected again round-20: round-19 corrected round-18's "any skip event rolls back" overreach by
  gating Class 1 on `reason=gateway_orchestration_error` alone — but that swung too far the other
  way: the canonical deployment defects this whole task exists to catch (this section's own opening
  paragraph — a bad loopback target, a wrong `X-Origin-Verify` value, a timeout misconfiguration, a
  missing `INTERNAL_API_KEY` read) manifest as `*_connection_failure`, `*_non_2xx_status`, and
  `*_timeout` — reasons round-19 made permanently non-rollback-eligible, defeating the task's own
  purpose. Design.md's fail-open classification (D5, line 941-955, re-pinned round-45; see 5.1's round-45 note) governs *runtime behavior*
  — login must never block or surface an error, regardless of cause — it does not settle whether a
  *specific occurrence* of one of these reasons, in *this specific deployment*, reflects a defect in
  what was just deployed. Rollback eligibility now follows completed attribution, not reason-string
  equality: `gateway_orchestration_error` is immediately eligible without further work **(as is the
  dual-event `reset_key_not_configured` impossibility — Class 1 — Immediate's second condition
  below; round-30 self-audit, this framing sentence named only one of the two immediate conditions,
  the same governing-sentence-vs-fine-print drift round-26 fixed here once already)**; every other
  reason — **including `reset_key_not_configured` (round-28 reversal of the round-25/26 exclusion;
  round-26 had aligned this framing sentence with the inner bullets' then-shared exclusion, but the
  exclusion's own premise — "a blank key is a pure configuration outcome no rollback can ever fix"
  — is factually wrong on Azure: the `INTERNAL_API_KEY` env *reference* lives in the
  revision-scoped container template (verified directly —
  `infrastructure/terraform/azure/modules/container-app/main.tf:87-93` places the secret-env
  blocks inside `template`, with `revision_mode = "Single"` at line 16), so a newly deployed
  revision that omitted or mis-named that reference is exactly a revision-attributable cause that
  restoring the prior revision genuinely repairs; only an *app-scoped* secret-value problem — the
  values live outside the template, `main.tf:99-105` — necessarily survives rollback, and telling
  those two apart is precisely a diagnosis job: see this reason's own checklist bullet below and
  Class 2h)** — remains diagnosis-eligible: it can still justify
  rollback, but only once evidence ties it to this revision specifically.)**
  - **Class 1 — Immediate: `reason=gateway_orchestration_error`, matching this run's trace id.** The
    one reason that originates inside Wave 8's own orchestration code itself — an unexpected
    exception in that code's own execution, **whether in request construction before a call goes
    out or in the handling of a downstream response that already succeeded (round-29 correction —
    this sentence read "in request construction, independent of any network response," which
    contradicted 8.7's own scope for this reason, in force since round-21, that explicitly includes
    the post-success-handler throw; the narrower wording would have led an operator reading the
    runbook top-down to treat a post-response occurrence as outside Class 1, or worse, as evidence
    the event was mis-emitted — see 8.7's two evidence shapes)** — never a downstream,
    network, or legitimate-operational condition. **Both forms are Class 1 on the reason alone; the
    evidence shape differs (dispatch `false`/`attached` `null` for the pre-dispatch form, dispatch
    `true`/`attached` boolean, possibly alongside a legitimate `demo_reset_succeeded`, for the
    post-response form) but the verdict does not, and neither shape requires diagnosis.** No further
    diagnosis needed. Roll back api-gateway
    to the last revision independently confirmed serving correctly (round-14, unaffected by this
    correction) — the pre-Wave-8 revision on a first deployment attempt, or the most recent revision
    that itself passed this same 8.9 proof on a prior run — then verify via a fresh, identity-checked
    probe that the rolled-back revision is actually serving, not merely that a rollback was triggered
    (matching Task 10.2's own "not considered complete until verified" discipline).
  - **Class 1 — Immediate, second condition: both `demo_reset_self_call_skipped` and
    `demo_reset_succeeded` found for the same trace id (outcome (e)), the skip event's reason
    `reset_key_not_configured` specifically** (round-26 addition, relocated here round-27 — round-26
    originally placed this case as Class 2i, but Class 2's own governing header defines that class
    by the *absence* of rollback-attributable evidence, and this case is not that: it is a logical
    impossibility under correct behavior, not merely a suspicious pairing like 2g's below.
    `reset_key_not_configured` means the code deliberately never dispatched the reset call at all
    (5.1a resolved blank, fail-closed before any network call — see 8.7), so a co-occurring
    `demo_reset_succeeded` for the same trace id cannot be explained by any legitimate race, unlike
    2g's reasons, all of which involve a real dispatch that merely failed. Every explanation for
    this combination — the fail-closed check being bypassed in some code path, or the skip/success
    events being mis-emitted or cross-attributed — is itself a Wave 8 code defect, never a
    configuration gap; this cannot resolve to Class 2h, which requires the reason to mean what it
    says).** No further diagnosis needed (matching Class 1 — Immediate's own standard above, since
    every explanation here already implicates this revision's code) — not routed through the
    Diagnosed-tier checklist below: its `reset_key_not_configured` bullet (round-28) diagnoses
    where a *solo* skip's blank came from, a question that is moot here, since no legitimate
    explanation of this dual-event *combination* exists regardless of the blank's origin.
    Roll back exactly as the first Class 1 condition above.
  - **Class 1 — Diagnosed (round-20 addition, given a concrete evidence checklist round-21 — an
    earlier draft named categories to compare without naming what to compare them against, which
    left this tier subjective and, for the one case it did specify, pointed at evidence that doesn't
    exist when the reset call itself fails): any other `reason` — **including
    `reset_key_not_configured` as of round-28 (round-25 excluded it here as "a pure configuration
    outcome" rollback could never fix; that premise is false for the revision-template-regression
    case, see the outer framing sentence above — this header and that sentence changed together,
    per round-26's own lesson that a rollback runbook's governing sentences must not contradict
    their fine print)** — once evidence from the
    checklist below specifically attributes its cause to this deployed revision.** Per `reason`:
    - **`reset_key_not_configured`** (round-28 addition — the one reason whose Class 1 rollback
      repairs *deployment configuration* rather than code): determine which of two states produced
      the blank, by inspecting env-reference *names* only — never secret values, preserving the
      non-disclosure rule below. Compare the currently-serving revision's container template
      env list (Azure: `az containerapp revision show` on the current revision — the env entries'
      names and `secretRef` names are template metadata, not secret payloads) against the
      last-known-good revision's same list. **If the `INTERNAL_API_KEY` reference is missing or
      mis-named in the newly deployed template but present in the prior one: attribution, Class 1
      — the deployment itself regressed the revision-scoped template, and restoring the prior
      revision genuinely restores the reference (`main.tf:87-93`, revision-scoped; single-revision
      mode, `main.tf:16`).** **If the reference is intact and identical in both templates, that
      does NOT by itself establish an app-scoped secret-value failure — run the manual-reset probe
      before assigning any class (round-29 correction — round-28 jumped straight from "templates
      equal" to "therefore the secret value is missing," which does not follow: equal template
      metadata is consistent with a secret-value gap, but equally with a provider/code-wiring
      regression in 5.1a's own resolution, an effective-environment problem the template text
      cannot show, or a mis-emitted event. Template names are static text; the probe is the
      strongest *runtime* observation available that touches no secret value, and it was already
      specified two bullets below for the `403` case — it simply was never wired into this one).**
      Probe `PUT /api/portfolio/demo-reset` (Wave 5, live and reachable regardless of the frontend
      flag) fresh, right now, **with two disciplines the probe's evidentiary weight depends on
      (round-30 corrections):**
      **(i) Replica correlation — the probe does NOT necessarily hit the process that emitted the
      skip event (round-30 — the round-29 wording claimed "the same provider instance in the same
      process," which is false under Azure's actual topology: api-gateway runs up to three replicas
      (`infrastructure/terraform/azure/main.tf:217`, `max_replicas = 3`) and ingress load-balances
      across healthy replicas, so a `200` served by replica B contradicts nothing observed on
      replica A — replicas of the same revision share the same template and secret store, but env
      values resolve at container start, so replicas started at different times can legitimately
      hold different values if the secret changed in between, without any code defect at all).**
      The skip event carries its own **`replicaToken`** (8.7, round-30 addition — **carries 5.1b's
      derived `replicaToken`, not Azure's raw replica identifier, as of round-34; see 5.1b**,
      blank where the platform doesn't provide the underlying variable); read the probe's own
      **`X-Gateway-Replica-Token` response header** (5.1, round-31 addition —
      corrects round-30's assumption that a trace-correlated log record of the probe's outcome
      would exist to query: nothing in Task 5.1 specified any such log statement, so that record
      had no actual emitter. The response header needs no log backend, no query, and no ingestion
      delay — the probe's own HTTP response carries the answer synchronously, at the moment it's
      received — **the same opaque token, round-34, never the raw replica name; see 5.1b**) and
      compare it directly against the skip event's `replicaToken`. Only a probe
      outcome whose header **matches** the skip event's `replicaToken` is
      direct evidence about that provider instance; outcomes from other replicas are evidence about
      the *fleet*, classified below. **Every correlation step below is a token-equality check —
      it needs no reversal, and the two tokens are as directly comparable as the raw names would
      have been (round-34).** Only where a step below needs to *act on a specific real replica*
      (the raw-environment presence check further down, or the `az containerapp exec` targeting
      it performs) does the raw Azure identifier itself become necessary, and that lone step
      recovers it separately — see that step for how; nothing about the correlation logic itself
      changes.
      **(ii) Version discipline — the probe is a real state-changing call with a `409` outcome of
      its own (round-30 — the manual route requires `{"expectedVersion": <long>}` (design.md's D5
      contract) and concurrent demo traffic can legitimately conflict; round-29's three-outcome
      list left a normal `409` unclassified, as if it were a query failure):** obtain
      `expectedVersion` from a fresh identity-checked read (the same GC.6-disciplined read this
      task's other steps use) immediately before probing. A `409` needs no retry for *this*
      diagnosis — it is already conclusive on the question being asked (see below); re-read and
      re-probe at most once only if a clean `200` is separately wanted for the fleet-repetition
      step (round-30 self-audit — the earlier unconditional "re-probe on `409`" changed no
      classification and had no stated purpose).
      The probe's status code then discriminates:
      - **`503` with error `internal_api_key_not_configured` — but identify WHICH service's
        fail-closed branch emitted it before concluding anything (round-30 self-audit, the sixth
        instance of the evidence-oracle mismatch pattern this review keeps catching: design.md D5
        deliberately reuses the same status and machine code for portfolio-service's own analogous
        check — verified directly, `portfolio-service/.../seed/InternalApiKeyFilter.java:59-62`
        returns an identical `503 {"error":"internal_api_key_not_configured"}` when
        *portfolio-service's* secret is blank. A gateway replica whose provider resolves non-blank
        forwards the probe downstream and relays that identical 503 back, so an undiscriminated
        503 would falsely "corroborate" a gateway-side blank and send the repair to the wrong
        service).** Discriminate by response body shape — the gateway's 5.1 body is requirements
        7.3a's pinned two-field `error`-plus-`message` shape, asserted by 5.3a/5.5;
        portfolio-service's is single-field (`{"error":"..."}`, `InternalApiKeyFilter.java:82`).
        **Round-44 correction, the seventh instance of the evidence-oracle mismatch pattern this
        review keeps catching — one bullet above the sixth: this bullet previously also offered
        "trace-correlating which service logged the rejection" as the more robust alternative, but
        neither emitter logs the rejection at all. Portfolio-service's `writeError` path is
        log-free (its only log is a constructor-time warn, `InternalApiKeyFilter.java:43`) and
        5.1's filter deliberately logs nothing — the very gap the `X-Gateway-Replica-Token` header
        exists to cover; nor can that header discriminate here, since 5.1 sets it on relayed
        proxied responses too. The offered fallback queried a log line no service produces. Body
        shape — normatively pinned in requirements 7.3a and test-asserted since round-44 — is the
        discriminator, alone.**
        **Gateway-emitted 503:** some serving gateway replica's provider genuinely resolves blank
        right now, independently corroborating the skip event — but a same-replica `503` proves
        only that 5.1a's `isConfigured()` returned false; it does not by itself distinguish "the
        raw environment variable is genuinely absent" from "the raw environment variable is
        present but 5.1a's own resolution/normalization logic in *this deployed revision*
        incorrectly reports it blank" — a code regression a rollback would fix, not a
        configuration gap (round-31 addition — the template-name comparison already ruled out a
        *wrong reference name in the template*; this rules out a *wrong read of a correctly
        referenced value in the deployed code*, the one remaining gap between "templates agree"
        and "therefore Class 2h").
        **Before finalizing Class 2h, run one more non-disclosing check on the correlated
        replica — but first recover which real replica the correlated *token* actually names
        (round-34 addition — since round-34, `replicaToken` above is 5.1b's opaque `replicaToken`,
        not the raw Azure identifier `az containerapp exec --replica` requires; this recovery
        step is the one place in this whole diagnosis that genuinely needs the raw value, and it
        is deliberately manual rather than adding any new endpoint or secret to provision it):
        list live replicas of **the exact recorded revision, explicitly pinned via
        `az containerapp replica list --name api-gateway --resource-group wealth-azure-prod-rg
        --revision <the current revision — the earlier template
        comparison already fixed which one>`, never the bare, unqualified form (round-35
        correction, attribution fixed round-44 — `az containerapp replica list` without
        `--revision` defaults to the *app's own* latest revision (its `latestRevisionName`), not
        anything environment-scoped as round-35 wrote, and either way not "the current one";
        during an in-progress deployment or a rollback already underway, latest and the
        evidence-manifest's recorded revision can differ, and listing the wrong revision's
        replicas makes every subsequent token comparison meaningless against replicas that were
        never in play; `--name`/`--resource-group` were also absent until round-39 — the command
        as previously written had no default resource group to fall back on and would not resolve
        without one)**,
        and for each candidate name run 5.1b's own **`ReplicaTokenTool`** — **once per candidate,
        directly against the pulled image, never via `az containerapp exec` and never by
        extracting the jar first (round-37 correction on running it live, round-38 correction on
        extraction — the formula is
        pure: it depends only on the candidate name string, not on any replica's own runtime
        state, so nothing is gained by running it inside a live container, and doing so would
        multiply exec-access dependencies across every candidate for no benefit; and extracting
        the jar via `docker run --entrypoint cat` assumed a `cat` binary exists in the runtime
        layer, which `Dockerfile.azure`'s own `RUNTIME_BASE` build arg explicitly allows replacing
        with a distroless variant — a base image class defined specifically to contain no shell
        utilities at all. Running `java` directly needs no such assumption: it is the one binary
        the image is guaranteed to have, being how `app.jar` itself runs). Authenticate to the
        registry first — `az acr login --name wealthprodacr` (the fixed constant, see Step A) —
        then `docker pull <the image reference recorded in Step A/the evidence
        manifest — `<registry>/<repository>@sha256:<digest>` — Step A's own recorded value
        directly, since `deploy-azure.yml` now binds every revision to a digest at deploy time
        (round-40; see Step A), never a tag Step A would have had to resolve after the fact and
        could never have proven immutable>`, once per
        diagnosis. For each candidate name: `docker run --rm --entrypoint java <that pulled image>
        -jar /replica-token.jar <candidateName>`,
        expecting exactly the tool's own contract (5.1b) — stdout matching `^[0-9a-f]{12}\n$`
        exactly (round-38 — "one line of lowercase hex" alone would accept a 64-character
        un-truncated SHA-256 digest as if it were a valid token, misclassifying a genuinely broken
        tool as a mere non-match instead of the tooling failure it actually is; the formula's own
        normative output is exactly 12 hex characters, so the check should say exactly that), and
        exit
        `0`, with byte-empty stderr** (round-39 — 5.1b's own contract already says "nothing else
        on stdout or stderr on success"; this consumption point previously checked only stdout
        and exit code, silently dropping the stderr half of the very contract it claims to
        enforce). **Any deviation is a failed observation on that candidate, not a non-match:
        the recorded reference not matching `@sha256:[0-9a-f]{64}$` (round-40 — Step A's own
        digest-qualification check should already have caught this at capture time, but a
        diagnosis reading a manifest written before that check existed, or by a path that
        bypassed it, gets no benefit of the doubt here either), ACR authentication failure, `docker pull` failure, artifact
        missing inside the pulled image, `docker run`/`java` exiting nonzero, stdout not
        matching `^[0-9a-f]{12}\n$` exactly, or any nonempty stderr — a tooling failure by 5.1b's
        own contract, never treated as a pass merely because stdout happened to look right —
        block exposure without authorizing rollback, treat per
        Class 2d's discipline, and record which candidate and which deviation, rather than
        silently treating a broken invocation as "this candidate's token didn't match" (round-37
        addition, failure surface widened round-38 to cover reference/auth/pull, not only the
        tool's own execution — those are different failures with different remedies: a genuine
        non-match is
        evidence about the replica; every other deviation is evidence about the tooling or the
        pipeline around it, and conflating either with a non-match would misattribute a broken
        command or a broken pull as if it were conclusive)** —
        **requiring exactly one genuine match before proceeding (round-35
        addition — a real gap: the original wording assumed some match always exists and is
        unique, but replica recycling since the run can leave zero live replicas answering to the
        recorded token, expected and not unusual, and a 12-hex-character truncation can in
        principle collide across the small set of live replicas, however unlikely).** **Zero
        matches or more than one match is itself a failed observation, exactly like an
        unreachable exec target below — block exposure without authorizing rollback, treat per
        Class 2d's discipline, and record the token, the recorded revision, and the full replica
        list observed. Never let the operator pick a candidate by inference or convenience: an
        unverified guess at *which* replica is the correlated one would corrupt the very identity
        this whole recovery step exists to pin down.** Only an
        exact, single match's
        real name is the exec target below (this recomputation needs no access beyond what
        `az containerapp exec` itself already requires, and discloses nothing beyond what an
        operator with that access already has — `az containerapp replica list` returns the real
        names directly — it only reproduces, locally, the same one-way step 5.1b performs in the
        deployed process). Then confirm the operator can actually reach the resolved replica
        (round-32 correction —
        `az containerapp exec` is itself a real operational dependency this diagnosis previously
        assumed always succeeds: the target replica may have been recycled since the run, the
        operator may lack `Microsoft.App/containerApps/exec/action` on this environment, or the
        container's runtime shell may be unavailable. Fully resolve the target the way Azure's own
        multi-replica invocation requires — `--name api-gateway --resource-group
        wealth-azure-prod-rg` (round-39 — absent until now, and this command has no default
        resource group to fall back on), `--revision` (the current revision — the earlier
        template comparison already fixed which one) and `--container` (the single api-gateway
        container in this pod, `infrastructure/terraform/azure/modules/container-app/main.tf`'s
        one `container` block) alongside `--replica <the recovered real name>`, not `--replica`
        alone, since a
        replica name is not unique across revisions or, in principle, across containers within a
        pod. If the exec itself fails for any reason — connection refused, access denied, replica
        no longer found, no shell in the image — that is a failed *observation*, exactly like a
        failed probe elsewhere in this bullet: block exposure without authorizing rollback, treat
        per Class 2d's discipline, and record the specific exec failure alongside the replica name
        for whoever investigates access/tooling next**):** using the same ephemeral,
        boolean-only-result discipline this document already
        requires for secret comparisons (see the non-disclosure procedure below), exec into that
        specific replica/revision/container and run **5.1a's own `InternalApiKeyPresenceProbe`
        (round-33, launch corrected round-34 — `java -jar /probe.jar`, its own dedicated
        non-Boot artifact, not the fat jar; see 5.1a — printing exactly `blank` or `nonblank`)**,
        rather than
        approximating the predicate in shell. **A shell test cannot reproduce `.isBlank()`
        correctly, and round-32's `tr -d '[:space:]'` version does not (round-33 correction — that
        draft claimed exactness it did not have: POSIX `[:space:]` is locale- and
        implementation-dependent and byte-oriented, while `String.isBlank()` is defined over
        Unicode code points via `Character.isWhitespace`, and the two genuinely disagree on
        control and non-ASCII whitespace — precisely the boundary this check exists to adjudicate,
        where a disagreement authorizes a production rollback. Round-32 correctly identified that
        bash's bare `-n` was wrong for the whitespace-only case; its replacement moved the error
        rather than removing it. Running the real JDK predicate **removes the class of error
        entirely — precisely because it runs in its own JVM, not the provider's (round-35
        correction — "the same JVM ... as the provider" overclaimed what `java -jar /probe.jar`
        actually shares: a fresh JVM process, launched separately, cannot be "the same JVM" as
        api-gateway's own already-running one. What it genuinely shares is the same container —
        image, JDK, and the container-scoped environment variables both the probe and the
        provider read, which is the only sameness this diagnosis actually depends on: two
        independent reads of the identical environment, not two observers inside one process)** —
        and the probe is a separate class that
        SHALL NOT share resolution code with `InternalApiKeyProvider`, so it remains an
        independent second read rather than the provider vouching for itself).** The probe prints
        no value, length, or hash, so the non-disclosure requirement holds by construction.
        **`blank`:** the raw environment
        genuinely has
        nothing (by 5.1a's own definition of nothing) under that name in this replica — no code
        path can be blamed for reading a value that isn't there — **Class 2h** stands, configuration
        repair; where probe and skip event
        correlate to *different* replicas and
        other probes return `200`, the repair is specifically a revision restart to re-propagate
        the secret to every replica, and the mixed fleet is itself the recorded evidence.
        **`nonblank`:** the raw environment holds a value 5.1a's own predicate would accept, yet
        the same provider, in the same replica, reports it blank — a direct contradiction inside
        5.1a's own resolution code: **attribution, Class 1**, not Class 2h — this is precisely the
        provider-implementation-regression case a code rollback exists to fix.
        **Portfolio-service-emitted 503:** the gateway replica's provider resolved *non-blank* (it
        forwarded) — treat exactly as the `200`/`409` branch below for the gateway-blank question,
        and separately repair portfolio-service's own `INTERNAL_API_KEY` provisioning
        (configuration, its own service, never a gateway rollback).
      - **`200` or `409` — both prove the serving replica's provider resolved non-blank AND
        portfolio-service accepted the key's value (a `409` fails on version, after key validation
        — it is evidence, not a failed observation).** If that replica is the **same** one that
        emitted `reset_key_not_configured` for this run (the probe's own `X-Gateway-Replica-Token`
        header matches the event's `replicaToken`, per (i) above): the two observations contradict
        each other in one process, and
        every explanation is revision-attributable — 8.5's own consumption of the shared provider
        is broken while 5.1's works (a Wave 8 wiring defect), or the event was emitted on a path
        that never consulted the provider correctly. **Attribution, Class 1.** If it is a
        **different** replica: no contradiction yet — repeat the probe a bounded number of times
        (up to twice the configured replica maximum), reading each response's own
        `X-Gateway-Replica-Token` header. Three outcomes (round-30
        self-audit — the original two arms left a gap): **(a)** the emitter's replica is observed
        answering non-blank — the contradiction stands, **Class 1**; **(b)** the emitting replica
        no longer exists (replaced/restarted since the run) — the runtime observation is simply no
        longer obtainable: attribution not established on this evidence, treat per Class 2d's
        discipline (resolve what is repairable, re-run from step 1 fresh) rather than forcing
        either verdict; **(c)** the bound is exhausted with the emitter's replica still existing
        but never observed (ingress routing does not guarantee coverage) — same as (b):
        attribution not established, Class 2d's discipline, recording which replicas were observed.
      - **`403 invalid_internal_api_key`** — the serving replica's provider holds a value that
        portfolio-service disagrees with; that is the *misalignment* case, not a blank, and it
        contradicts this run's `reset_key_not_configured` for that replica exactly as `200`/`409`
        do — apply the same same-replica/different-replica correlation logic above, **with the
        same terminal classes (round-30 self-audit — this branch previously told the operator to
        "decide whether the contradiction stands" without ever saying what a standing contradiction
        yields): a standing same-replica contradiction is Attribution, Class 1, exactly as in the
        `200`/`409` branch — the event misreported the provider state either way, and which wrong
        state it misreported does not change whose defect that is.** Independently of that verdict,
        resolve the value misalignment itself through the `403`
        bullet below (which already handles gateway/portfolio-service value drift as configuration
        repair, never itself a rollback justification), and record the blank-vs-mismatch
        contradiction — both cannot be true of the same provider at the same time.
      - **Any other probe outcome — a timeout, connection failure, or any status not enumerated
        above (round-30 self-audit — the tree previously assumed the probe always yields one of
        the enumerated codes; a probe is a real network call and can fail like any other):** a
        failed *observation*, not evidence about the provider. Retry once; if still unusable,
        attribution not established on this evidence — Class 2d's discipline, recording the probe
        failure itself.
      (AWS-side classification of the analogous Lambda
      configuration-vs-revision question stays deferred with the standing AWS-only findings.)
    - **`*_connection_failure`**: compare `attemptedTarget` (8.7) against the fixed, known
      construction pattern 8.3/8.5 specify — a mismatch is attribution.
    - **`*_timeout`**: compare `elapsedMillis` (8.7) against 8.2's own resolved timeout value
      (already in this run's evidence manifest, step 6), with an explicit tolerance band, not a
      vague "materially exceeding" judgment call (round-22 correction): `elapsedMillis` within the
      configured budget plus the greater of 10% or 200ms is design working as intended, not a
      defect (ordinary JVM/network scheduling jitter around the `.timeout(Duration)` operator's own
      firing point) — stays Class 2d. `elapsedMillis` at twice the configured budget or more is
      attribution (the enforced timeout itself is not doing its job in this revision). Anything
      between those two bands is genuinely inconclusive on this evidence alone — treat as attribution
      not established (Class 2d), not forced into either bucket.
      **For `overall_timeout` specifically, read `overallTimeoutPhase` (8.7) before applying the
      band comparison at all (round-29 addition — the band answers "did the deadline fire roughly
      when it should have," which is the wrong question when the deadline fired while the
      orchestration was doing its *own* work rather than waiting on anyone):**
      `eligibility_in_flight` or `reset_in_flight` means the budget was spent waiting on a
      downstream response — the band comparison applies as written, and a within-band result is
      genuine downstream latency, Class 2d. **`between_legs` means the deadline fired with no call
      in flight: the read had returned and the reset had not yet gone out, so the budget was
      consumed inside the gateway process itself — but that localizes the stall without
      establishing its cause (round-30 correction — round-29 made this phase immediate Class 1
      attribution on the reasoning that "no downstream latency can explain time spent while
      nothing was awaited," which is true but proves only *where*, not *why*: a replica under CPU
      starvation, a long JVM pause, scheduler delay, or platform contention stalls this same
      inter-leg stretch with zero code defect, and the phase field cannot tell that apart from a
      genuine blocking bug — say, a GC.9-violating `.block()` — in this revision's own inter-leg
      code).** Attribution therefore requires reproduction, not the phase alone: block exposure
      (as every abort path does), record the event including its `replicaToken` (8.7, round-30),
      and re-run from step 1 — **bounded, at most twice**. **Reproduction alone is not
      sufficient — it must include a cross-replica observation, not merely a repeated symptom
      (round-31 correction — round-30 treated "reproduces on a fresh run" as proof the platform
      causes above are ruled out because they're "transient" and "do not recur on demand," but
      that assumption is exactly backwards for a *chronically* under-provisioned or degraded
      replica: a persistently CPU-starved instance stalls the same inter-leg stretch on every run
      that happens to land on it, indistinguishable from a deterministic code bug by symptom alone
      — reproduction only rules out a one-off transient, not a sustained platform condition tied
      to one host):** at least one of the (up to two) reproductions SHALL land on a **different**
      `replicaToken` than the original occurrence — ingress load-balancing across up to three
      replicas (`azure/main.tf:217`) makes this achievable within the existing bound without a new
      mechanism. **Cross-replica reproduction** (the stall recurs on a genuinely different
      replica) **is strong, but not by itself sufficient, evidence — a distinct `replicaToken` does
      not establish a distinct physical host or an independent resource domain (round-32
      correction — round-31's "a persistent single-host condition cannot explain a stall that
      follows the request across hosts" assumed host-level isolation between replicas that Azure
      does not document: replicas of the same Container App can be scheduled onto the same
      underlying node, and node-pool-level or environment-wide scheduler contention affects every
      replica scheduled there regardless of name, so cross-replica recurrence is equally explained
      by shared platform pressure as by a code defect).** **Cross-replica recurrence therefore
      stays Class 2d on its own, and CPU/memory metrics do not lift it (round-33 correction —
      round-32 promoted "cross-replica recurrence plus healthy CPU/memory" straight to Class 1,
      which over-claims what those two metrics can exclude: a JVM safepoint or GC pause, Reactor
      scheduler starvation on a saturated event-loop group, network-stack latency, noisy-neighbour
      contention on a shared node, and throttling invisible to app-level CPU counters all stall
      the inter-leg stretch while CPU and memory read normal. Absence of *those two* signals is
      not absence of platform causes; treating it as such is the same "localized, therefore
      attributed" step round-30 and round-31 each corrected one layer further out).** Record the
      cross-replica recurrence and whatever platform signals were checked, and block exposure.
      **Class 1 attribution requires evidence that points at the gateway's own code path
      specifically, not merely at the absence of some platform explanations** — either
      **application-level blocking evidence** (a thread dump or async-profiler/JFR capture taken
      during a reproduction showing the orchestration blocked in this revision's own inter-leg
      code, or a BlockHound-style detection of a GC.9-violating blocking call on the reactive
      path — the GC.9 constraint exists precisely because that class of defect is what would
      produce this signature), or **a controlled reproduction that isolates the code path** (the
      same orchestration exercised against the deployed revision under independently verified
      healthy, uncontended capacity — or, more cheaply, the same inter-leg stretch reproduced
      locally against the same revision's image, where no Azure platform condition is present at
      all). Neither is something this runbook can force to happen; it can only decline to
      authorize a rollback until one of them exists. **Same-replica-only reproduction** (every
      occurrence, including both re-runs, lands on the one replica): consistent with either a code
      defect *or* one chronically degraded host — attribution not established on this evidence
      alone; block exposure and treat as Class 2d, but flag the specific replica for platform-side
      health investigation (CPU/memory pressure history, if the operator has access to it) before
      the next attempt, rather than closing the loop as a transient. A single non-reproducing
      occurrence is treated as a platform transient: attribution not established, Class 2d, with
      the phase and replica evidence recorded for whoever watches the trend. The elapsed-band
      comparison stays inapplicable to this phase either way — no band on a wait that wasn't a
      wait says anything. **Terminal rule for the impossible dual-event combinations (round-30
      self-audit — the round-29 text noted that neither `between_legs` nor `eligibility_in_flight`
      can legitimately accompany a `demo_reset_succeeded`, but routed the case back through this
      bullet's own solo-oriented rules, under which a logically impossible pair could terminate as
      "genuine downstream latency, Class 2d" — benign — while the analogous impossibility, dual
      `reset_key_not_configured`, is immediate Class 1; the two pointers at 2f and here were
      circular, with no bullet owning the contradiction):** when a `demo_reset_succeeded` co-occurs
      for the same trace id and `overallTimeoutPhase` is `eligibility_in_flight` or `between_legs`,
      do NOT apply this bullet's band or reproduction rules — the pair is impossible under correct
      behavior (the phase says the reset was never dispatched; the success event proves a reset
      completed), and every explanation — a mis-recorded phase, a mis-emitted or cross-attributed
      event — is itself a Wave 8 code defect, mirroring the dual-`reset_key_not_configured` logic
      at Class 1 — Immediate's second condition: **attribution, Class 1**. Only
      `reset_in_flight` coexists legitimately with success — that case is Class 2f's benign race.
    - **Reset-leg `409`** (round-21 — no longer excluded, see 8.7): compare `observedVersion`
      against `submittedExpectedVersion` — inequality is attribution (a version-capture bug per
      GC.6); `selfCallCount > 1` for this trace id is also attribution (a duplicate-call bug),
      independent of the version comparison. Absent both, a `409` with matching versions and a call
      count of 1 is legitimate concurrency — stays Class 2d.
    - **Eligibility-leg `429`**: no diagnostic mechanism (round-22 correction — round-21's
      `rateLimitKeyCategory`-based attribution is not observable as specified, see 8.7). Stays Class
      2d unconditionally — a `429` here is treated as the rate limiter operating, not diagnosed
      further, until filter-side telemetry exists to do so. (The reset leg's own route has no rate
      limiter at all, so `429` cannot occur there — round-21.)
    - **`eligibility_shape_failure`** (round-21 addition — a real gap: this reason was
      diagnosis-eligible by the tier's own header but had no checklist bullet at all): the gateway
      does not filter or shape this response itself — it forwards the eligibility read and returns
      whatever list portfolio-service produces, so a zero/multi-entry result is attribution only if
      *this revision's own request* was malformed (e.g. the wrong identity/auth context attached,
      producing a different filter than intended — compare the request's own recorded identity
      context, if captured, against what it should be); if the request was correctly formed, this
      reflects portfolio-service's own data state (a missing or duplicated demo-account row), not a
      defect in this revision — stays Class 2d.
    - **`reset_non_2xx_status` where a `403` on the reset leg suggests `INTERNAL_API_KEY` misalignment
      specifically**: check the skip event's own **`internalApiKeyConfigured`/`internalApiKeyAttached`
      pair (8.7) first, as a pair — never the attach boolean alone (round-24 correction: a lone
      `attached=false` merges an unconfigured environment with a failed attach of a configured
      value, and only the second is a code defect; the first is a configuration gap this rollback
      could not possibly fix)**. `configured=true && attached=false` is direct, call-site-local
      evidence Wave 8's own wiring failed to attach an available value: **attribution, Class 1 — the
      only evidence in this bullet that actually implicates the Wave 8 revision**.
      `configured=false` cannot appear on a `403` at all, since that state does not dispatch (it
      carries `reason=reset_key_not_configured` instead, handled at that reason's own round-28
      bullet above and Class 2h below);
      if it somehow does, that inconsistency is itself a Wave 8 defect — Class 1. If
      `configured=true && attached=true`, probe the manual-reset route
      (`PUT /api/portfolio/demo-reset`, Wave 5, already live and reachable directly regardless of the
      frontend flag) fresh, right now (round-21 addition, factual basis corrected round-22 —
      `InternalApiKeyFilter` lives in **portfolio-service**, not api-gateway; the probe works because
      5.1 and 8.5 both attach the identical resolved value from 5.1a's shared provider, which
      portfolio-service's filter then checks). A manual-reset `200` rules out key *value*
      misalignment (attribution not established — check other causes, stays Class 2d); the same
      `403 invalid_internal_api_key` proves the shared gateway value and portfolio-service's
      configured value currently disagree — **but that is a configuration/environment defect, not a
      Wave 8 code defect, and rolling back api-gateway would change neither side's environment values
      (round-23 correction — an earlier draft routed this to Class 1 rollback, which could not
      possibly fix it, and the mismatch doesn't even identify which side's value is wrong): block
      exposure, repair the configuration (re-provision/redeploy the correct secret to whichever side
      drifted), verify via a fresh manual-reset probe, then re-run — never a Class 1 rollback.**
    - **`eligibility_non_2xx_status` (`403`) suggesting an origin-verification failure on the
      loopback eligibility read**: check the skip event's
      **`originVerifyRequired`/`originVerifyHeaderAttached` pair (8.7) — as a pair, never the attach
      boolean alone (round-24 correction: on Azure and local, `CLOUDFRONT_ORIGIN_SECRET` is
      deliberately never provisioned and `CloudFrontOriginVerifyFilter` is a deliberate no-op
      (`CloudFrontOriginVerifyFilter.java:38-41, 54-56`, verified directly), so a missing header —
      `attached=null` under the round-25 tri-state rule, the attach never being applicable — is
      the correct, expected state on every ordinary Azure run — the round-23 rule read exactly that
      normal state as rollback-worthy)**. This pair is the load-bearing evidence (round-23
      correction — an earlier draft used a public CloudFront probe here, which tests the wrong
      transport boundary entirely: the failing call is 8.3's *loopback* request, which attaches
      `X-Origin-Verify` from the same provider-resolved value, in the same
      process, that `CloudFrontOriginVerifyFilter` validates against, so a value mismatch is not a
      reachable failure mode on this leg — the reachable one is the attach not happening.
      And a genuine CloudFront-to-gateway mismatch would have rejected Task 8.9's own public login at
      the distribution *before* Wave 8's code could ever emit this event, so this reason cannot even
      be reached by that scenario). **`required=true && attached=false` = Wave 8 wiring defect:
      attribution, Class 1.** `required=false` (Azure/local, verification disabled end-to-end) means
      this `403` did not come from origin verification at all — check other sources, stays Class 2d,
      **never Class 1 regardless of the attach boolean**. `required=true && attached=true`
      with a `403` anyway = the `403` did not come from origin verification either (same process,
      same provider-resolved value — check other sources, stays Class 2d). **The public-CloudFront probe (send one
      unauthenticated `GET` through the real distribution URL; `403`-no-body = CloudFront↔gateway
      origin-secret mismatch, `401` = that pair is aligned; AWS only — Azure never provisions this
      secret, verified directly) is retained as an optional ingress-health check, useful when Task
      8.9's own login itself failed to reach the gateway at all, but it attributes nothing about this
      skip reason and never feeds this bullet's Class 1/2d resolution (round-23 reclassification).**
    - **`*_non_2xx_status` on either leg where none of the above apply** (a portfolio-service `5xx`,
      or an AWS `403`/Azure equivalent the two probes above didn't resolve): this is the one category
      with **no available in-repo diagnostic mechanism** (round-21 addition, scope narrowed round-22
      now that the two probes above cover the cases that previously fell here) — attribution cannot
      be established from this document's own mechanisms; **stays Class 2d unconditionally**, not an
      open-ended investigation. **If a raw-secret comparison is ever genuinely needed as a last
      resort beyond what the two probes above cover, it SHALL follow a strict non-disclosure
      procedure (round-22 addition — a real gap: an earlier draft instructed reading
      `aws lambda get-function-configuration`/`az containerapp secret show`/`terraform show -json`
      directly, all of which return raw secret plaintext, with no handling instruction at all — a
      genuine operational-safety issue, since this document elsewhere treats terminal output and the
      evidence manifest as things to record and retain):** performed in a short-lived, ephemeral
      process by a human operator; compute a comparison (e.g. a SHA-256 digest of each side) entirely
      within that process; the raw value and any derived digest are discarded when the process ends;
      **only the boolean match/mismatch result is ever recorded** — never the raw value, never a
      hash, in any terminal output, CI log, file, or this task's own evidence manifest (step 6).
    This is inherently an investigative step, not a script's boolean; it blocks exposure while it
    runs, and only resolves to rollback (this tier) or Class 2d (attribution not established) once
    complete — never left open-ended.
  - **Class 2 — no evidence attributable to the deployed gateway revision (immediately, or after
    diagnosis); no rollback.** Exposure stays blocked (this task is not green) and api-gateway is
    left exactly as it was. Eight distinct sub-cases, since what to retry (or whether to retry at all)
    differs. **Two of them — 2f's contradictory-phase else-branch and 2g — are triage sub-cases,
    not terminal homes: their conditions route into the Diagnosed tier (or its terminal rules),
    whose outcome may resolve OUT of Class 2 to Class 1 (round-30 self-audit — stated here in the
    governing header so a case binned under this class ending in rollback reads as the designed
    path, not as the round-27 Class-2i contradiction recurring; the terminal sub-cases 2a-2e and
    2h resolve within Class 2 as before):**
    - **2a — a setup/dependency step (1-3) failed before the login was ever attempted** (e.g. the
      composition write itself failed) — nothing about Wave 8's own code was exercised. No partial
      state is worth preserving: retry with a genuinely fresh end-to-end run from step 1.
    - **2b — the login was attempted, but neither event was found, or the query itself errored**
      (outcomes (c)/(d)). Ambiguous whether Wave 8's code ran at all; this task is still not green.
      **Retry the same historical trace id against the same time window first (round-17 correction —
      an earlier draft said to re-run from step 3, but cleanup already runs unconditionally and
      restores golden holdings plus the production threshold before Abort is even evaluated, so a
      fresh login at that point is neither idle-eligible nor has anything non-golden to reset; the
      original event, if logged at all, is still sitting in the log backend and only the query needs
      retrying — this requires step 6's own evidence manifest to have retained the exact query
      coordinates, not just the outcome, see step 6)** — repair the query (fix auth/throttling/malformed
      syntax for outcome (d); simply re-poll for outcome (c), since ingestion can lag past the
      original deadline) before falling back to a genuinely fresh end-to-end run from step 1 only if
      the original window has aged out of the log backend's queryable retention.
    - **2c — `demo_reset_succeeded` found (outcome (a)), state non-golden, and the identity-checked
      read's `version` is strictly greater than the event's own resulting `version`** (round-19
      narrowing — this is the *only* one of three version comparisons that actually proves a later
      writer, per the analysis 2e now covers): proof that **a later writer** touched the shared demo
      account after this run's own reset — not proof of *who* (round-20 correction: the version
      comparison alone cannot distinguish a real visitor from a concurrent probe run or internal
      automation; asserting "another public visitor" claimed an actor the data cannot identify). Not
      a Wave 8 defect either way — record both versions and whatever request/trace evidence is
      available for whoever investigates, rather than asserting a cause. Retrying is pointless without
      addressing the race itself: re-run from step 1 during as low-traffic a window as practical, and
      treat repeated occurrences as a signal to revisit the shared-account design (e.g. narrowing the
      observation window further, or checking for another automated process reaching this account),
      not as a code defect.
    - **2d — `demo_reset_self_call_skipped` found with a reason other than
      `gateway_orchestration_error` **or `reset_key_not_configured`** (round-26 correction — this
      bullet's own condition structurally still matched the latter, since it *is* "a reason other
      than `gateway_orchestration_error`"; that reason follows its own round-28 Diagnosed-tier
      bullet, resolving to Class 1 (template regression, or a standing probe contradiction —
      round-29/30), Class 2h (probe-corroborated app-scoped secret value), or — where the runtime
      observation is unobtainable (the emitting replica gone or never observed within the bound,
      or the probe itself failing as an observation — round-30) — this bullet's own discipline,
      though never through its general checklist delegation), and diagnosis (Class 1 — Diagnosed,
      above)
      does not attribute
      the cause to this deployed revision** (round-19 addition, scope corrected round-20 — an earlier
      draft treated every non-`gateway_orchestration_error` reason as automatically, permanently
      excluded from rollback regardless of what investigation found; this made Task 8.9 structurally
      incapable of ever catching the exact deployment defects — bad loopback target, wrong secret,
      timeout misconfiguration — it exists to catch, since all of them manifest through these same
      reasons): Wave 8's own code executed correctly and reported a downstream/operational condition
      exactly as fail-open design requires, *and* the Diagnosed-tier checklist above rules out a
      revision-specific cause — a `409`/`429` with matching versions/expected key/single call count
      (round-21 — no longer automatically excluded, but the checklist found nothing), or a
      `*_non_2xx_status`/`*_timeout`/`*_connection_failure` traced to portfolio-service's own health,
      genuine network conditions, or rate-limiter tuning unrelated to this deployment. Do not roll
      back and do not blindly retry — resolve the diagnosed condition first (downstream service
      health, rate-limiter tuning, network/infra), then re-run from step 1 once it is believed
      resolved.
    - **2e — `demo_reset_succeeded` found (outcome (a)) and state non-golden, but the version
      comparison rules out a benign race** (round-19 addition, replacing round-18's Class 2c, which
      assumed a race without checking version evidence at all): if the identity-checked read's
      `version` **equals** the event's resulting `version` — the response and the event both claim
      success at that exact version, yet persisted state is not golden, a real reset/persistence/read
      correctness defect, not traffic; if the read's `version` is **less than** the event's resulting
      version — a stale or inconsistent read, itself a real defect. Neither case is a race, and
      neither should be silently retried: a defect that produced this once will reproduce identically
      on a blind rerun. Block exposure and raise this as a defect requiring investigation (which
      component owns the fix — `HoldingReplacementService`/`GoldenStateTuplePreparer`'s persistence,
      or the read path's own consistency — is not something this task alone can attribute; record the
      full version/state evidence for whoever investigates) rather than looping this task until it
      happens to pass.
    - **2f — both events found for this trace id (outcome (e)), skip reason is `overall_timeout`
      specifically (round-21 narrowing — split from a single Class 2f that previously covered *any*
      skip reason alongside success, below):** a genuinely plausible late-success race against the
      gateway's own timeout budget, not a gateway code defect — the orchestration correctly gave up
      per its configured deadline, and the downstream call happened to complete anyway, **in either
      order (round-22 correction — an earlier draft narrated one specific order, portfolio-service
      committing after the gateway had already logged its timeout; the opposite order — a slow
      commit itself delaying past the deadline, with the response merely arriving even later — is
      equally real and equally lands here; classification is by trace id and `reason` alone, GC.8's
      own dual-event test only demonstrates one order is reachable, not that it is the only one, see
      GC.8)**. Block exposure; do not automatically roll back. Preserve both events' own emitted
      timestamps (not which the log backend happened to return first when queried — round-22
      correction, see step 6) and `reason`, plus `demo_reset_succeeded`'s own resulting `version`
      **(round-21 correction — the skip event carries no "resulting version" of its own, since no
      reset it reports on ever completed; only the success event has one)**, and diagnose whether the
      *overall* timeout value (8.2) is simply tuned too tight relative to genuine downstream latency
      at this deployment's scale — a tuning question for 8.2's own resolved value, not evidence
      against this revision's code. **This benign reading holds only when
      `overallTimeoutPhase=reset_in_flight` (round-29 — the phase field makes the previously
      implicit precondition checkable): a success event exists, so the reset call must have
      dispatched and portfolio-service must have committed, which is consistent with
      `reset_in_flight` and with nothing else. `eligibility_in_flight` or `between_legs` alongside a
      `demo_reset_succeeded` for the same trace id is a contradiction — the reset was reportedly
      never dispatched, yet a reset demonstrably completed — resolved by the Diagnosed-tier
      `*_timeout` bullet's own terminal rule for exactly this impossible combination: attribution,
      Class 1 (round-30 self-audit correction — the round-29 wording routed it generically
      "through the Diagnosed-tier checklist," whose solo-oriented band rules could then terminate
      a logical impossibility as benign Class 2d; the terminal rule now owns it, mirroring the
      dual-`reset_key_not_configured` precedent).** Re-run once resolved.
    - **2g — both events found for this trace id (outcome (e)), skip reason is anything other than
      `overall_timeout`, `gateway_orchestration_error`, or `reset_key_not_configured`** (round-21
      addition, scope corrected round-21, corrected again round-26 — `gateway_orchestration_error`
      is excluded because it is *unconditionally* Class 1 — Immediate regardless of what else
      co-occurs; `reset_key_not_configured` is excluded because that combination is not "contradictory
      terminal evidence *to investigate*" the way this bullet's own reasons are — it is a stronger,
      already-resolved signal, see Class 1 — Immediate's second condition above (relocated there
      round-27; and though the Diagnosed tier gained a bullet for that reason round-28, it
      diagnoses a *solo* skip's blank, not this dual-event combination, which stays settled outright
      at Class 1 — Immediate))**: this is **not** the
      late-success race 2f describes — a success event alongside, say, `reset_non_2xx_status` is
      contradictory terminal evidence (the reset call cannot simultaneously have failed with a
      non-timeout reason *and* completed successfully for the same trace id under normal operation).
      Treat this as its own defect signal: run the Diagnosed-tier checklist above against the
      accompanying `reason` exactly as outcome (b) alone would require, since the contradiction
      itself is suspicious. Block exposure; resolve to Class 1 — Diagnosed or Class 2d per that
      checklist's outcome, never assumed benign by default.
    - **2h — `reason=reset_key_not_configured`, with no co-occurring `demo_reset_succeeded` for
      this trace id, AND the Diagnosed-tier bullet found the revision template's
      `INTERNAL_API_KEY` reference intact **and** a fresh manual-reset probe returned a
      **gateway-emitted** `503 internal_api_key_not_configured` (discriminated from
      portfolio-service's identical code per that bullet's round-30 rule, as corrected round-44 —
      body shape alone), corroborating the blank
      at runtime (round-29 — the
      probe precondition is new; round-28 reached this bullet on the template comparison alone,
      which cannot distinguish a secret-value gap from a provider-wiring regression or a
      mis-emitted event, both of which are revision-attributable and now resolve to Class 1 at that
      bullet instead)** (round-25 addition, co-occurrence qualifier round-26 —
      see Class 1 — Immediate's second condition above (relocated there round-27) for the
      dual-event case this bullet does not cover; **template-diagnosis precondition round-28 —
      this bullet previously declared the reason "never diagnosis-eligible and never Class 1"
      because "a code rollback cannot fix it," a premise that is simply false when the blank came
      from the newly deployed revision's own template omitting or mis-naming the env reference:
      that reference is revision-scoped configuration (`main.tf:87-93`), so restoring the prior
      revision restores it — that case now resolves at the Diagnosed tier's own
      `reset_key_not_configured` bullet, to Class 1**): the diagnosis showed the template
      reference present and correctly named in both the current and last-good revisions, and the
      probe corroborated the blank — one of two configuration states, neither of which a code
      rollback touches (round-30 self-audit — this bullet previously named only the first, while
      the probe bullet routes both here): **a missing/blank secret value** in the Container App's
      app-scoped secret store (`main.tf:99-105`) — the gap would persist identically on the
      rolled-back revision — or **a stale replica** holding an outdated resolution of a since-set
      value (env values resolve at container start and secret changes do not restart running
      replicas). Block exposure; repair per the diagnosed state — provision/redeploy
      `INTERNAL_API_KEY` to api-gateway for the missing-value case, or restart the revision to
      re-propagate the already-set value for the stale-replica case (a mixed `503`/`200` fleet in
      the probe repetition is the signature of the latter); verify the
      repair via a fresh manual-reset probe (which exercises the same shared 5.1a value); then
      re-run from step 1. **Note the deliberate asymmetry with `gateway_orchestration_error`: both
      are pre-dispatch outcomes, but one is the code failing at something it could do, the other
      the environment not giving it anything to do.**
  **Wave 5's manual-reset gateway bundle is a separate deployable unit
  (established in Wave 8's own intro) and is explicitly NOT rolled back by any class above** — the
  manual trigger has no dependency on Wave 8's login self-call and stays live and hidden throughout.
  **Go for Wave 10 Stage 7 exposure requires this proof green against the still-currently-serving
  deployment (Wave 10.2 item 4), not merely 8.8** — propagated into `design.md` D5 and the master
  plan's own production-exposure gate, not stated only here (see Wave 10.2 item 4).
  _Requirements: 7.4, 7.3c; design.md D5_

## Wave 9 — Live integration · *Track 3*

- [ ] **9.1 Wire `BrowseStep`/`AssetList` to real `GET /api/assets`**, preserving Task 1.11's
  conditional-revalidation contract against the live response — the real `ETag`/`304` behavior,
  never a second persistent cache layered on top of it. **Blocked on B1's `GET /api/assets`
  controller (B1 task 4.11) actually shipping in a merged, deployed release, not merely Wave 2's
  gateway route existing (round-10 addition — this task previously carried no B1 dependency at
  all, unlike its sibling 9.2).** B1's own Artifact Manifest is explicit: R-A (Wave 2's release,
  which ships the gateway route) must NOT contain Waves 4-7; the controller (Wave 4) only becomes
  includable at R-B2 (+ Wave 4, Wave 5.1), gated on Wave 3/Spec A's cutover. Before R-B2, the
  gateway route this task wires against forwards to an endpoint that doesn't exist in the deployed
  artifact — verified directly: no `GET /api/assets` mapping exists anywhere in
  `portfolio-service` source today.
  _Requirements: 2.1_
- [ ] **9.2 Wire Task 1.13's composition-save mutation to the real `PUT /api/portfolio/holdings`** —
  transport only; the state machine (payload construction, 200/409 handling, first-time and
  empty-set cases, success transition) was already built and tested against a mock in 1.13,
  including the open-time strict-preflight invariant (Task 1.5/1.6) rather than any submit-time
  recheck (round-6 correction: a stale reference here still described the recheck Task 1.13 no
  longer performs).
  **Blocked on B1 Wave 7** (`CompositionController` — the public endpoint does not exist before
  B1 Wave 7, regardless of how complete B1 Wave 4's orchestrator is) **and** this document's Wave 1-2.
  _Requirements: 4.1, 4.2_
- [ ] **9.3 Wire Task 1.10's price fetch** to the real `/api/market/prices?tickers=` for drafted
  tickers only.
  _Requirements: 3.1_
- [ ] **9.4 Wire presence** to the real `GET /api/presence/demo` (Wave 3).
  _Requirements: 6.3_
- [ ] **9.5 Wire Task 1.16's freshness status to a real `assetPriceFreshness`.** Spec A task 8.6 is
  complete and the backend field exists. This task remains undone because B2's frontend adapter and
  live integration have not been implemented; no additional Spec A work blocks it.
  _Requirements: 3.2, 3.4_
- [ ] **9.6 Demo-authenticated Playwright fixture — authored first, so 9.7/9.8 don't
  forward-reference each other (round-9 restructure, breaking a real cycle: round 8's version of
  this task said the reset-control render check reuses "the fixture Task 9.8 already establishes,"
  while 9.8 said it "depends forward on 9.6/9.7's CI wiring" — each task waited on the other, so
  neither could actually be authored first).** A reusable fixture/helper (e.g. a `test.extend`
  context) that logs in as the seeded demo account using `DEMO_TEST_EMAIL` and
  `DEMO_TEST_PASSWORD` supplied by Task 9.9 (the intentionally public values currently wired as
  `demo@wealthtracker.dev` / `demo-wealthtracker-2026` in `deploy-azure.yml`; seeded by migration `V15`, which
  runs identically against this stack's own fresh database) in its own isolated browser context —
  never the suite's shared, writable `storageState` (`playwright.config.ts:51-56`'s `chromium`
  project inherits `E2E_TEST_USER_*`, seeded subject `00000000-0000-0000-0000-000000000e2e`
  (`PortfolioSeedController.java:23`), a different subject than `DemoResetAuthorizationFilter`
  accepts). This fixture has no dependency on any other task in this wave; 9.8 imports it.
  _Requirements: 7.3a_
- [ ] **9.7 Author `tests/e2e/asset-picker.spec.ts`.** **Blocked on B1 Wave 7** (round-12 addition —
  this task exercises the real `PUT /api/portfolio/holdings`, the same public endpoint 9.2 needs;
  stated explicitly rather than left implicit, matching how 9.2/9.8 already state it). **This gate
  transitively guarantees B1 task 6.1 (the version-required seed) has already shipped too — not a
  separate condition to track**: per B1's own Artifact Manifest, R-C (Wave 7's release) is defined
  as R-B3's manifest (already "+ Wave 6") plus Wave 7 — there is no release containing Wave 7 that
  doesn't already contain all of Wave 6. **Its own first assertion confirms `EditHoldingsButton`
  renders, under the ordinary, default E2E session** (nothing about the composition picker is
  demo-specific) — owned by this spec directly, not a separate shared render-check task. Targets a
  CI-only, flag-on build (9.9's job-level env) — never Wave 10's production build, which this test
  does not block on and does not need to wait for. **Two deterministic cases, and a real
  composition `409`, not just a mocked one:** Tasks 1.13/1.14 only exercise `409` against a mock,
  and Task 9.8 only exercises the demo-reset route's own `409` — a different endpoint and
  potentially different UI presentation than the composition save's own `ConflictPanel` — so
  nothing in this document previously proved the *composition* `409` path against the real
  gateway/backend at all.
  - **Success case — the picker's own save advances state, not just the setup (round-12
    correction: an earlier draft asserted only that a direct API setup write advanced the version,
    then let the picker's own save merely return `200` — a picker save that silently no-ops,
    resubmitting the loaded state unchanged, would still pass that check, which is the exact
    weakness this case exists to close).** Before opening the picker, make a deliberate,
    known-different composition write through the API directly, asserting its response `version` is
    strictly greater than the version observed immediately before it. Open the picker (it now loads
    that state); **make a pinned edit relative to what's loaded** (e.g. change one drafted
    quantity to a specific new value), browse/review as needed, and save. Assert the save response
    `version` is strictly greater than the version the picker observed on open, and that the
    persisted holdings equal the edited draft exactly — not merely that a `200` came back.
  - **Stale-version case.** Load the picker (capturing its observed `version`), then — through a
    second, independent composition write — advance the real version past what the picker
    observed; save from the picker and assert a real `409`: the draft stays visible, read-only,
    with editing and resubmission disabled (GC.4), and no second `PUT` fires automatically.
  - **Clean-up — identity-checked and version-bearing, unconditionally (round-12 correction: an
    earlier draft kept a legacy "the seed endpoint isn't version-aware yet" branch, but this task's
    own B1 Wave 7 gate above already guarantees B1 6.1 shipped by the time this test can run at
    all — that branch describes a state that can never actually occur here).** In a
    `finally`/afterEach hook: re-observe the E2E user's current version (identity-checked — the
    seed endpoint is fixed to `E2E_USER_ID`, so no list-selection ambiguity exists here the way it
    does for the demo user, but the version itself must still be freshly read, never assumed);
    call the version-bearing internal seed endpoint with that version to restore the golden catalog
    state; require `200`. A `409` here is retried to restore hygiene (bounded, e.g. three attempts,
    each beginning with another fresh observation) but SHALL still fail the test, never silently
    forgiven — the same discipline Task 9.8's cleanup already uses.
  _Requirements: 4.1, 4.2, 4.3, 4.4; design.md D1_
- [ ] **9.8 Author `tests/e2e/demo-reset.spec.ts`, using Task 9.6's fixture.** **Blocked on B1 Wave 7
  (round-9 addition — this task's own setup writes through the demo-authenticated composition
  `PUT`, the same public endpoint 9.2 needs, so B1 Wave 7 gates this task too, not "9.2
  specifically" as an earlier draft implied).** Nothing else exercises the real public route or its
  UI control together: Wave 4.5 probes the **internal** endpoint directly, bypassing the gateway
  entirely; Wave 5's Test 1 (5.4) runs against a **stubbed** portfolio-service; Wave 5.3a/4.4 are
  unit/integration-level but not through the gateway or the UI. **Its own first assertion confirms
  the manual-reset control renders, under Task 9.6's demo-authenticated context specifically** —
  not the default session, so this render-check and the rest of this test agree on what "the
  control, for the account it's built for" means. **Two separate, deterministic cases — not one
  test that merely "accepts either" outcome** (accepting `200` OR `409` indiscriminately means an
  implementation that always 409s would pass this gate without ever proving a reset actually
  succeeds), **and each case's setup SHALL provably change the version, not merely reach "a known
  state" that might already equal the target** ("seed to a known state" and "a second call" were
  both non-deterministic — either could be a same-state no-op, which B1 returns as `200` with the
  version unchanged, so a broken reset that never actually reset anything could still pass Case 1,
  and an unrelated no-op could masquerade as Case 2's conflict). Both cases open with the same
  deliberate, deterministic setup: **every version read, in setup and cleanup alike, SHALL select
  the single `GET /api/portfolio` entry whose `userId` equals `DEMO_USER_ID` and fail the test
  immediately on zero or multiple matches** (the same unstated list-identity risk Step A and Task
  1.2 were already fixed for; this test does not get a pass just because it runs in CI). Through
  the demo-authenticated composition `PUT` (Requirement 4, the same endpoint Wave 9.2 wires, not a
  database fixture), write a holding set to the demo portfolio that is **known to differ from the
  golden reset target** — assert that write's response `version` is strictly greater than the
  version observed immediately before it (via the identity-checked read above), proving the setup
  itself actually advanced state, not merely resubmitted the same thing.
  - **Case 1 — success.** After the deterministic setup above, load the UI, observe its `version`,
    click the control, assert the request reaches the real gateway, passes the real filter chain,
    reaches the real internal endpoint, returns `200`, the response holdings match Task 4.4a's
    independently-derived exact golden set, and the UI reflects that fresh state.
  - **Case 2 — conflict.** After the same deterministic setup, load the UI (capturing its observed
    `version`), then perform **another** deliberately different, successful composition write
    through the demo-authenticated `PUT` — assert *that* write's returned version strictly exceeds
    the UI's already-observed version, proving a genuine race exists, not a no-op — then click the
    control and require exactly `409` and the UI's no-retry conflict presentation (7.3b).
  - **Clean-up, order-independent, through an independent transport from the public route under
    test.** `PortfolioSeedController`
    is permanently hard-coded to `E2E_USER_ID`, `PortfolioSeedController.java:23` — it cannot target
    the demo portfolio at all, and a composition `PUT` cannot reconstruct the preparer-owned golden
    cost-basis tuple either, since that construction lives inside `GoldenStateTuplePreparer`, not
    something a caller can replicate from the wire. In a `finally`/afterEach hook: re-read the
    version through the same identity-checked selection above (never assume the version setup last
    observed is still current); call `POST /api/internal/portfolio/demo-reset` through the gateway's
    existing internal route with the CI-provided `INTERNAL_API_KEY` and that freshly observed
    version. This deliberately bypasses the public route/filter/rewrite under test, so a defect in
    that path cannot make the claimed unconditional cleanup impossible. Require `200`; verify the
    resulting holdings match Task 4.4a's independently-derived exact golden set, the same oracle
    Case 1 uses.
    `/api/internal/portfolio/seed` remains explicitly prohibited here. **A `409` here is retried to
    restore hygiene, but SHALL still fail the test — never silently forgiven (round-9 addition:
    with one worker against a fresh, isolated database, a cleanup conflict means an unplanned
    writer or a stale read actually happened, a real defect this suite should surface, not paper
    over just because cleanup eventually succeeded).** Retry is bounded (e.g. three attempts), each
    attempt beginning with another identity-checked re-observation, not a blind resubmission of the
    previous version; the test records and re-raises the unexpected conflict after cleanup
    completes.
  _Requirements: 7.3a, 7.3b; design.md D5_
- [ ] **9.9 CI/CD wiring for the full-stack E2E workflow — job-level env, the correct active
  workflow, authored last since it only needs the file names 9.7/9.8 already establish (round-9
  reordering of round-5/7's own fixes).** Two compounding errors an earlier draft of this task had:
  (1) it set the flags on a *build* step, but Playwright's own `webServer.command`
  (`playwright.config.ts:91`, `npm run build && npm run start:export`) performs its **own, second**
  build — spawned as a child of whichever step runs `npx playwright test`, inheriting *that* step's
  `process.env`, not an earlier step's `env:` block at all; flags set only on an earlier build step
  never reach the build Playwright actually serves. (2) it targeted `frontend-e2e-integration.yml`,
  which that workflow's own header comment marks **disabled** ("manual-only... Use
  `ci-verification.yml` for full-stack E2E") — the active, automatically-run full-stack E2E
  workflow is `ci-verification.yml`'s `docker-build-verify` job. **Fix:** add
  `NEXT_PUBLIC_ENABLE_ASSET_PICKER: "true"`, `NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL: "true"`,
  `DEMO_TEST_EMAIL: "demo@wealthtracker.dev"`, and
  `DEMO_TEST_PASSWORD: "demo-wealthtracker-2026"` to
  that job's own **job-level** `env:` block (`ci-verification.yml:263-281`, the same block already
  carrying `E2E_TEST_USER_EMAIL` etc.) — job-level env applies to every step in the job, including
  whichever one invokes `npx playwright test`. This job is CI-only (docker-composed local
  containers, never real traffic) and entirely independent of Task 10.1's two *production* deploy
  workflows. **Add the new spec files to the explicit invocation itself:**
  `ci-verification.yml:377-387` names five spec files explicitly in its `npx playwright test`
  command — it does not glob the `tests/e2e/` directory, so a new file the command doesn't name
  never executes in CI regardless of anything else in this document. `tests/e2e/asset-picker.spec.ts`
  (9.7) and `tests/e2e/demo-reset.spec.ts` (9.8) SHALL both be added to that exact command list, and
  both SHALL be required (not `continue-on-error`) for the job to pass.
  Add a structural parity assertion that the two CI fixture literals equal the tracked Azure
  frontend demo literals and V15 identity; never fall back to untracked `frontend/.env.local` on a
  clean runner.
  _Requirements: 1.1_

## Wave 10 — Production exposure gate · *Track 4, design.md D5 Stage 7*

- [ ] **10.1 CI/CD wiring for both build-time flags (round-3 addition — Wave 10 previously assumed
  a runtime "enable" action that this static-export frontend cannot perform).** Add
  `ENABLE_ASSET_PICKER` and `ENABLE_DEMO_RESET_CONTROL` as GitHub Actions repository variables;
  thread both into `deploy-azure.yml`'s `Build Next.js static export` step's `env:` block (alongside
  the existing `NEXT_PUBLIC_API_BASE_URL`/`NEXT_PUBLIC_DEMO_EMAIL` pattern) as
  `NEXT_PUBLIC_ENABLE_ASSET_PICKER`/`NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL`. AWS workflow wiring is
  explicitly deferred by the Azure-first scope note and is not part of this task's Azure completion
  criterion. Both variables default unset — no workflow change is required to
  keep both flags off; this task only makes flipping them *possible*, it does not flip either one.
  _Requirements: 1.1_
- [ ] **10.2 STOP/GO — production exposure.** **Corrected from the first draft, which could pass
  while Requirement 7's own mechanism was still unbuilt — round 21 made login-orchestration
  independently gated from the manual bundle; it did not make either optional for production.**
  **Go, all of:**
  1. B1/Spec A's own activation gates (B1's R-C and everything R-C depends on).
  2. B1 task 4.9 (decimal fidelity) confirmed live, **with evidence that Tasks 2.1/2.3/2.4 were
     already live first — round-6 correction: the settled ordering *outcome* is not optional even
     though Wave 2.7's coordination *mechanism* is still only proposed.** "4.9 is currently live" by
     itself does not rule out a real, already-happened breakage window (B1 ships 4.9, the
     unmodified Portfolio page breaks for a stretch, Wave 2 deploys after) — that window can't be
     undone by 4.9 merely being live *now*. Check the actual deploy timestamps/revision history on
     Azure revision/workflow history for both changes and confirm Wave 2's frontend deploy
     predates B1 4.9's; treat a violated order as a defect to remediate (verify no user was actually
     affected, or accept the incident), never as something this gate can silently pass around.
  3. Waves 3 (presence, **Task 3.7's Azure live probe green**) and 4 (portfolio-service endpoint, gate 4.5, itself
     requiring B1 task 5.1 per its own round-3 fix) **deployed to production and live-verified**, per
     their own STOP/GOs satisfied in the deployed environment — not merely green in CI. Wave 5
     (manual-reset gateway bundle, gate 5.6) and Wave 6 (frontend control, gate 6.3) **deployed
     hidden** (flag off) **with Task 9.8's assembled-stack CI verification of the control itself
     green** — round-6 correction, precision tightened round 8: no earlier task live-verifies Wave 6
     against **cloud** infrastructure; that specific gap is closed by Step A below (the backend
     route, in the cloud) together with Step B (the deployed control itself, live) — see the Go
     action's own step labels for the precise division, not restated here.
  4. Wave 8 (login-orchestrated self-call) **deployed to production, with its Azure deployment-
     evidence foundation and live serving proof green (gates 8.8, 8.8b, AND 8.9 — round-12 addition:
     8.8 alone only proves 8.1-8.7 and 8.7a's
     own tests pass (round-15 update — 8.7a added since round-12), which can all be true without the
     deployed chain ever having worked against real cloud
     infrastructure; login's fail-open design means that gap is otherwise invisible)** (8.8 itself
     requiring Wave 4.5) — meaning `updatedAt` has actually landed on `PortfolioResponse` (8.1), not
     merely been assigned an owner, and the idle threshold and self-call timeouts (8.2) are resolved
     and reflected in that deployed code. **Unconditional — round-3 correction: round 2 allowed an
     "explicit, recorded decision" to bypass this item; that is not this document's call to make.**
     Requirement 7 mandates both the manual and the login-orchestrated reset mechanisms; a task
     plan cannot waive a frozen `SHALL` by noting an exception here. Launching the picker without
     Wave 8 requires an approved revision to `requirements.md`, `design.md`, and the master plan
     *first* — only then would this item's condition legitimately change, and this document would be
     updated to match, not silently overridden. **8.9's evidence must describe the deployment being
     exposed right now, not a historical one (round-14 addition — recording revisions in 8.9 with
     nothing to compare them against left this gate unable to detect a stale proof):** compare 8.9's
     evidence manifest — its recorded gateway/portfolio-service revisions (reusing Step A's own
     mechanism above), the cloud/provider that served that run, on AWS the CloudFront config
     version/ETag and `traceparent`-forwarding confirmation (which also stands in for
     `CLOUDFRONT_ORIGIN_SECRET`'s own state, since that secret has no separate version identifier —
     round-15 correction), the `INTERNAL_API_KEY` alignment evidenced by that run's own successful
     reset call (round-15 correction — not a separately-computed fingerprint, which would need
     diagnostic infrastructure neither service has), and the threshold/timeout config fingerprint
     (fields expanded round-15, see 8.9 step 6) — against the artifacts and configuration actually
     serving production at the moment this Go/Abort decision is evaluated. **An override-backed
     manifest is never eligible here at all (round-18 clarification, see 8.9 step 2 — this is not one
     more invalidation trigger alongside the others below, it is a standing precondition: only a
     manifest recorded under the real, unmodified production threshold can be compared in the first
     place).** Any api-gateway or
     portfolio-service redeploy after 8.9 last passed, any provider switch (`CLOUD_PROVIDER` changing
     which cloud serves production), any CloudFront distribution change, any `INTERNAL_API_KEY`
     rotation, or any gateway `CLOUDFRONT_ORIGIN_SECRET`/function-configuration change (round-23
     addition — either side of the CloudFront↔gateway origin-secret pair can break alignment while
     the other's fingerprint stays unchanged, so both sides' changes invalidate, not just the
     distribution's) invalidates that manifest —
     8.9 SHALL be rerun (under the real threshold, no override) against the currently-serving
     deployment before this item is satisfied again.
  5. Wave 9 (Live integration) actually completed, not merely unblocked.
  6. The still-open UI product decision resolved: manual-reset control placement. The presence TTL
     is settled at 150 seconds; the idle threshold and self-call timeouts are covered by item 4
     above, since Wave 8 cannot deploy without them.
  **Go action — a real deployment, not a configuration flip, and both flags together, not one
  independently of the other (round-4 correction: round 3's "independently" framing permitted
  launching the picker while requirements.md 7.5's manual control stayed hidden indefinitely — the
  Go conditions above already require Wave 6 deployed hidden with 9.8 green, so by the time this
  action runs, both are ready; there is no legitimate reason left to stagger them). Two steps, not
  one — round-5 correction: round 4 folded a single post-deploy API probe into this action,
  satisfying "the public route was reached" but never "the deployed control itself works," and
  requiring exposure to already be live before any live check ran at all.**
  - **Step A — pre-exposure, before either flag moves. Backend-route verification only — not
    verification of the deployed control itself (round-8 correction: Step A is API-only, so it
    proves the public route works in the cloud, nothing about whether the actual frontend button
    does anything; Task 9.8 already proves the control against the assembled stack, just not in the
    cloud; Step B below is the only point in this document that live-verifies the deployed control,
    and it is exactly why Step B, not Step A, closes that gap).** Independent of any frontend flag:
    authenticate
    as the seeded demo account against the real deployed gateway. Before calling reset, use
    Task 4.4a's independent oracle to submit and verify a deliberately non-golden composition
    write, including a strictly advanced version; a reset probe that starts from an already-golden
    portfolio cannot earn this gate. Then call the real, deployed
    `GET /api/portfolio`, which returns `List<PortfolioResponse>` (design.md D5's own list-shape
    note — the exact response type that produced the `portfolios[0]` bug Task 1.2 fixed
    client-side). **Select the single entry whose `userId` equals `DEMO_USER_ID`; zero matches or
    more than one SHALL fail the probe immediately** (round-7 addition — this step had silently
    reintroduced the same list-identity risk in a new call site) — **on every re-observation after a
    `409`, not only the first**, since a retry that re-reads the list carelessly could reintroduce
    the same defect on attempt two even with attempt one written correctly. Then call the real,
    deployed `PUT /api/portfolio/demo-reset` with that entry's exact `version` and a real JWT. **SHALL
    obtain at least one genuine `200` — not merely accept whichever of `200`/`409` happens to come
    back (round-6 correction: accepting either indiscriminately means an always-`409` implementation
    would pass this gate having never actually reset anything).** If the first attempt races real
    demo traffic and returns `409`, the probe SHALL re-observe `version` (through the same
    list-identity selection above) and retry, bounded (e.g. three attempts) — this is the
    *verification script's* own bounded retry to obtain a clean observation, not the application
    auto-retrying a user's request, so it does not weaken GC.4/requirements.md 4.3's no-automatic-retry
    rule, which governs the picker's own UI behavior, not a deployment check. **The `200` itself
    SHALL be validated, not merely its status code (round-9 addition — a broken deployment that
    returns bare `200` with garbage or stale data would otherwise still pass this gate):** the
    response holdings match Task 4.4a's independently-derived exact golden set and the response
    `version` is the expected
    post-reset value; **then a second, separately identity-checked `GET /api/portfolio` SHALL
    confirm the same golden state is actually persisted** — proving the write landed, not merely
    that the response looked right.
    **Does not assert "no price-table writes"** (round-6 correction: a public HTTP probe has no
    database access to check that; Task 4.4's repository-level test already covers it, pre-production
    **Consume, do not redefine, Task 8.8b's Azure deployment evidence.** Read the exact serving
    gateway and portfolio-service revisions and digest-qualified template image references; require
    them to match Task 8.8b's validated current-attempt `digest-manifest.json`; and record those
    values in Step A's result. A missing manifest, non-digest image, revision mismatch, provider
    change, or artifact from another workflow attempt fails Step A closed. This gate does not
    resolve tags, rebuild manifests, or restate workflow commands. Record every attempt, including
    bounded conflict retries, in the evidence output.
    follows would otherwise have nothing to point at). This step can run, and fail closed
    with nothing user-facing changed, entirely before Step B — the public route working is a
    precondition for exposure, not a consequence of it.
  - **Step B — the actual exposure.** Only after Step A passes: set **both** `ENABLE_ASSET_PICKER`
    and `ENABLE_DEMO_RESET_CONTROL` to `true` in the same change, then trigger a **new Azure frontend
    build and deploy** — the flags are baked into the static export at `npm run build`, so nothing
    changes in already-served files until this new build ships. **Immediately after that deploy, a
    real browser-based smoke test against the live public URL** — not another API call: visit the
    deployed Portfolio page, log in as the seeded demo account, confirm `EditHoldingsButton` and the
    manual-reset control both actually render, and click through the manual-reset control once,
    confirming the UI reflects a genuine `200` (the success path — 409 handling is already proven
    deterministically by Task 9.8's Case 2 in CI, this smoke test's job is confirming the *deployed
    frontend* specifically, not re-proving the conflict branch live). This is the one point in this
    document that verifies the deployed frontend, not just the API behind it — Task 9.8 covers the
    same UI-through-real-backend exercise in CI/docker-compose, this is its live-cloud counterpart.
  **Abort/rollback — differentiated by which step failed (round-6 correction: a single rollback
  description conflated two different situations), and not considered complete until verified
  (round-9 addition — a triggered rollback build is not the same fact as a finished, served
  rollback):** if **Step A** fails, abort with **no rollback deployment** — neither flag has moved
  yet, so there is nothing to undo, only something to fix before retrying Step A. If **Step B**'s
  smoke test fails (or the decision is otherwise reversed after Step B), set both variables back to
  `false`/unset and trigger **another** frontend build and deploy — rollback is itself a
  build-and-deploy action here, never instant, since both flags' values are compiled into whichever
  static bundle is currently being served. **Wait for that rollback deploy to actually complete
  (not just dispatched), then verify from a fresh, uncached browser session that both controls are
  absent from the served page, and record the served revision** — a triggered rebuild that hasn't
  finished, or a stale CDN edge still serving the flag-on bundle, would otherwise leave the exposed
  state live while this document believes it's already been rolled back. Every wave above may still
  be merged and deployed with both flags off at any time; only Step B changes what's user-visible.
  _Requirements: cross-cutting — see master plan's own production-exposure row_
