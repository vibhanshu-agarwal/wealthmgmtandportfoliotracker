# Implementation Plan

**OWNER DECISION — Task 6.7 R-B3 GO and local ledger closure:** Task 6.6 has technical ACCEPT:
the approved cu4 deployment and single same-state seed passed the complete serving/data proof.
See the [completed report](../../../docs/runbooks/B1_TASK_6_6_G2B_SERVING_PROOF.md). Approving the recommendation permits local
6.6 completion and 6.7 GO ticks; withholding approval leaves both unchecked and R-B3 open.
The candidate is already serving. Publication, further production action, Wave 7 activation
and Writer_Convergence closure are not included.

**B1 Wave 6 source completion (verified 2026-09-03):** Tasks **6.1–6.4 are source-complete**
after owner-approved [PR #217](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/pull/217)
merged at `main@d66bb23d5ef3606373c15d9ee02fda27c62df5c2` on `2026-09-02T19:28:09Z`.
Codex ACCEPT applies to reviewed head `1bdb1d31c5f775983a78b892ea8fec4871ec1f41`; R1/R2 were
closed by test fix `b1d33171` and governed close-out `1bdb1d31`. The merge parents are
`1f3eaf5834cb1a5e0c065d9c4d316100bdea837d` and that reviewed head. The only tree difference
from the accepted head is the 17-line `AGENTS.md` update already merged through PR #216;
application source and tests are identical. This reconciliation changes no runtime baseline.

The [accepted-head PR-event run](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33669373190)
completed successfully: 15 successful checks, one neutral Qodana alias, `ci-required=success`,
and explicit `docs_only=false` (12 of 14 paths outside the documentation allowlist).
Azure image smoke actually executed the blank/nonblank key and replica-token cases. Inspected
local reports contained 516 unit and 189 integration tests, zero failures/errors/skips, including
5 collision tests; the boot jar was 97,882,843 bytes. The caller inventory remains exactly three;
9 caller-guard and 33 governance self-tests passed.

The seed requires the caller's strict version and fixed E2E target, delegates once to the shared
replacement transaction, and preserves identity and complete no-op semantics. Both forced races
compare the full winning tuple and require exactly two attempts. The absent-creation loser is
asserted unresolved by user before real post-rollback advice reports the committed version.
The initializer forwards its own observation; global-price snapshot/sentinel coverage is retained.

Tasks 6.1–6.4 are checked for merged source completion. **Task 6.5 is GO by owner decision on
2026-09-03; Tasks 6.6/6.7 remain unchecked.** Read-only metadata preflight is recorded in the
[6.5 readiness record](../../../docs/runbooks/B1_TASK_6_5_PRE_DEPLOY_READINESS.md). The separately approved candidate build is recorded in §6.1 of that record. Deployment and
G2b proof subsequently passed under its separate execution approval; Task 6.6 is technically
ACCEPT. R-B3 owner closure, Wave 7 activation and Writer_Convergence remain open. No schedule
restoration, B2 gate decision or feature exposure follows from the 6.5 decision.

**Current program status (verified 2026-09-03 against `main@d66bb23d`; historical runtime baseline `e221662`; R-A / R-B / R-B2 serving digests below):**
Waves `P`, `0`, and `1` are complete. Wave 2 tasks **2.1–2.6 and R-A are complete**: G2 serving
proof is green on gateway revision `api-gateway--0000076` /
`sha256:2da5b303fd15772792167f2b26dc62250b2d9858270db315eab1d6d1a1554aec` (deploy run
[32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627);
evidence [`docs/runbooks/B1_R_A_G2_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_A_G2_SERVING_PROOF.md)).
Wave 3 tasks **3.1–3.7 and R-B are complete**: Artifact 2 cut `25aa730` was applied/served; V20 is
live; G3 green; evidence
[`docs/runbooks/B1_R_B_G3_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_B_G3_SERVING_PROOF.md).
Wave 4a–4c tasks **4.1–4.21 are merged on `main@2673f40`** (PR #153). Wave 5 Task **5.1 is merged
on `main@f22e2ff`** (PR #155). Tasks **5.2–5.3 / R-B2 are complete**: Artifact 2a cut `f22e2ff` is
serving on `portfolio-service--0000081` /
`sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` (deploy run
[32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866);
G2a green; evidence
[`docs/runbooks/B1_R_B2_G2A_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_B2_G2A_SERVING_PROOF.md)).
Tasks **5.4–5.6 are merged on `main@0b5d60d1`** (PR #161, caller migration).
**Task 5.7 / G5 is complete — owner close-out recorded 2026-09-02.** The owner requested,
“Please do the G5 close out,” after the successful three-caller evidence had been independently
reviewed and merged via PR #197 at `main@b6c0da3`. Authorized public Azure synthetic
[33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
ran from `main@f66d7ab6a4db1a327fd030ba9897bfc431104945`: actual Azure suite success,
holdings-only seed, 9 passing tests, and `expectedVersion=0` for each of `synthetic-shell`,
`global-setup`, and `azure-api-smoke`. At close-out, the three callers, shared helper, workflow
wiring, and focused tests have no source drift through `main@48d0aba8`; the inventory guard
still finds exactly three callers. The historical ingress/custom-domain failures and recovery
remain in the [G5 record](../../../docs/runbooks/B1_G5_INGRESS_BLOCKER.md).
G5's prerequisite for B1 Wave 6 is satisfied; Tasks 6.1–6.4 subsequently merged through PR #217
and are source-complete. Task 6.5 has its separate 2026-09-03 owner GO and approved read-only
preflight. The separately approved build cu4 succeeded; R-B3 deployment/serving proof and Wave 7 activation remain open. Unattended synthetics remain suspended;
further dispatch or schedule restoration requires separate authorization.
Candidate packaging / R-C (task 7.5)
is **not** complete. Public `PUT /api/portfolio/holdings` remains Wave 7. The deployed seed remains
on its prior version-tolerant serving cut; the version-required rewrite is merged source only,
and Writer_Convergence remains unproven. Dependent proof branch
[`proof/b1-wave-2-g1-v20@e6a98c5`](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/tree/proof/b1-wave-2-g1-v20)
remains historical and unmerged. Spec A V17–V19 were applied and verified at checkpoint 9.6; V20 is
applied under R-B and unchanged by R-B2. Wave checkboxes record implementation evidence, not merged
delivery unless stated. See
[`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`](../../../docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md)
for the living cross-program view.

> **Revision 8 — 2026-08-19.** One bounded correction, tracking design Revision 11. The V20 note
> covered a migration-number collision but not migration *ordering*: applying V20 before Spec A's
> V17-V19 leaves them unapplied and fails startup validation under Flyway's default
> `outOfOrder=false`, so `portfolio-service` does not start when they land. Wave 3 therefore cannot ship until Spec A's R3a
> is applied to production, and 3.5's go condition now requires reading `flyway_schema_history`
> rather than inferring it. This sharpens the release-lane predecessor already recorded in the
> dependency graph below — it does not narrow it to Wave 3; R-0 and R-A wait on Spec A's cutover for
> their own independent reasons. Task 2.2 also gains task 3.1 as an explicit predecessor, since its
> `V20` fixture cannot exist before the migration is written. No requirement is touched and
> Requirement 9.2 still holds: all of B1 may be implemented and tested meanwhile.
>
> **Revision 7 — 2026-08-16.** Three bounded corrections from checkpoint entry [45]. The GitHub
> Actions skip question from entry [44] is closed: a skipped prerequisite skips its dependants unless
> a condition such as `always()` overrides it, and neither `seed` nor `verify` uses one — so the
> chain skips cleanly with no new mechanism.
>
> 1. **D9's pre-existing proof paragraph still stated the superseded model** — one PR, and a proof
>    consisting only of unselected Container App equality. Since the design is normative, tasks could
>    not repair it; `design.md` is now **Revision 10** with the two-PR model and both complete proof
>    obligations.
> 2. **The release predecessor cited P-A.4** after Revision 6 renumbered the STOP/GO to P-A.5. Read
>    literally, that opened the release lane on the default-path check alone, without the
>    non-interference proof or selector coverage. Another wrong-but-existing reference, invisible to
>    the checker because it does not parse task IDs.
> 3. **The floor table still held `--tests` selectors** despite the column being renamed and the
>    prose saying they participate in no invocation. Replaced with plain class patterns, and the floor
>    now requires each class to have **at least one non-skipped test case** — XML presence plus a
>    nonzero task total would otherwise let a required class be entirely skipped while unrelated
>    classes kept the task green.
>
> P-A.2 also now records each downstream job's conclusion as `skipped` rather than inferring it from
> a green workflow, since GitHub reports a skipped job as a successful check.
>
> **Revision 6 — 2026-08-16.** Incorporates the fifth tasks review (checkpoint entry [43]), which
> read the **whole workflow** rather than the backend matrix. Two P1s and one P2, all accepted. One
> required a bounded design erratum — `design.md` is now Revision 9.
>
> 1. **A filtered backend deploy still shipped the frontend and reset production data.**
>    `deploy-frontend` (line 230) has no service-filter condition, `seed` (line 314) chains off it,
>    and `verify` (line 353) chains off `seed` — and the seed POSTs to
>    `/api/internal/portfolio/seed`, a production data-plane writer, not an observation. So "service
>    scoped" was true of the matrix and false of the workflow, and P-A.2 snapshotted only unselected
>    Container Apps, making all of it invisible. D9 now defines **full** and **scoped backend**
>    modes; P-A.2 asserts the downstream jobs were skipped as well as comparing app state.
> 2. **P-A proved one selector; P-B was accidentally generic.** B1's R-A needs an
>    **`api-gateway`-only** deploy, so a portfolio-only run cannot establish the selection contract —
>    P-A.3 now exercises every declared value, including that `market-data-service` also updates
>    `market-data-refresh-job` (lines 163–180). And P-B is narrowed to **`portfolio-service` only**:
>    generically, a `market-data-service` digest deploy would pin the Container App while its
>    companion Job still moved by tag, breaking the exact-artifact invariant inside one service.
> 3. **The manifest is now generated from the JUnit XML**, with 7.5's table as a required floor
>    rather than the selection. This closes the hole named in entry [42] *without* the tag or package
>    selector I suggested there — the review is right that an opt-in tag recreates the same omission
>    risk one boundary later, since `candidateVerification` already runs both tasks unfiltered.
>    Discovery failures are caught by reconciling B1-changed test sources against the generated
>    manifest.
>
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

**And V20 must not be *applied* before V17–V19 are** (design Rev 11). That is a separate failure from
the number collision above. `spring.flyway.out-of-order` is unset, so it defaults `false`, and Spring
Boot runs `validateOnMigrate=true` — applying V20 first leaves V17–V19 **unapplied and fails startup
validation**, so `portfolio-service` does not start when they land. The gap is recoverable only by
explicitly enabling `outOfOrder`, a deliberate configuration change with its own review, not a
property of the current setup. Verified against PostgreSQL 18.6 / Flyway 11.20.3:
`Detected resolved migration not applied to database: 17. … 18. … 19.`, exit 1. **Current status:**
Spec A's R3a (V17–V19) was applied and verified in production at checkpoint 9.6 on 2026-08-23, so
that deployment predecessor is now satisfied. B1 Wave 3 still cannot apply V20 until its own Wave 2
candidate and serving gates are green. Waves 0–2 and 4 remain unaffected; Requirement 9.2 permits
implementing and testing all of B1 meanwhile.

The design was frozen at Revision 8; **Revision 11 is the current normative design**, reached through
three bounded errata (9, 10, 11) that the freeze permits. Where a task and the design disagree,
**the design is normative**; raise it rather than resolving it in code.

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
  `*ScopeGuardTest`, which was that mistake.
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

- [x] **P-A.1 Add a service allowlist and two explicit workflow modes.** Scoping is a property of
  the **whole workflow**, not the backend matrix: `deploy-frontend` (line 230) has no service-filter
  condition, `seed` (line 314) chains off it, and `verify` (line 353) chains off `seed`. Per design
  Revision 9:
  - **full deploy** (no selection) — today's chain unchanged: four backends, frontend, seed, verify;
  - **scoped backend deploy** — the selected backend only; `deploy-frontend`, `seed` and the chained
    `verify` are **skipped**.

  An unselected service receives **no `az containerapp update` at all** — not a re-deploy at its
  existing digest, which can still create or mutate revision state.
  _Requirements: 1.17, 1.19, 1.21_
- [x] **P-A.2 Prove non-interference — Container Apps *and* downstream jobs.** For each scoped run,
  assert every **unselected** app's revision name, image digest and traffic weight are byte-identical
  before and after, **and** that `deploy-frontend`, `seed` and `verify` each report a conclusion of
  **`skipped`**. Record the conclusions explicitly: GitHub reports a skipped job as a *successful*
  check, so a green workflow does not distinguish "did not run" from "ran" — `needs.<job>.result`
  does. Skip propagation itself needs no new mechanism: a skipped prerequisite skips its dependants
  unless a condition such as `always()` overrides it, and neither `seed` nor `verify` uses one. Revision 5
  snapshotted only unselected Container Apps, so a run that redeployed the Static Web App and reset
  the portfolio data plane would have passed its non-interference proof. The seed is a production
  writer — `global-setup.ts:172-195` POSTs to `/api/internal/portfolio/seed` — not an observation.
  _Requirements: 1.17, 1.24_
- [x] **P-A.3 Prove every declared selection shape**, not one. B1's R-A needs an
  **`api-gateway`-only** deployment and its later releases need **`portfolio-service`-only**, so a
  single portfolio run cannot establish the selection contract. Exercise both B1-used values, and
  cover all four structurally — including that selecting `market-data-service` also updates
  **`market-data-refresh-job`** (lines 163–180), which is a service-specific auxiliary target rather
  than a uniform one.
  _Requirements: 1.17, 1.21_
- [x] **P-A.4 Prove the default path is unchanged.** An ordinary dispatch with no allowlist still
  deploys all four backends, the frontend, the seed and the verify chain exactly as today.
  _Requirements: 1.21_
- [x] **P-A.5 STOP/GO — P-A.**
  **Go:** P-A.2, P-A.3 and P-A.4 green.
  **Abort:** revert the allowlist; the release lane stays closed and implementation is unaffected.
  **Outcome (2026-08-18): GO.** Shipped in #105 (`500a8c5`). Spec text in #106 (`70486b4`).
  Gate order inverted: Azure OIDC is `ref:refs/heads/main` only, so live proofs ran after merge.
  Abort remains a revert of #105. Scoped mode is unreachable except by deliberate dispatch.

  | Task | Run | What it showed |
  |---|---|---|
  | P-A.4 | [32099171495](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32099171495) | Merge-triggered full path: four `deploy (…)` matrix entries (api-gateway, portfolio-service, insight-service, market-data-service), plus frontend, seed, verify all `success`; `assert-scoped-non-interference` `skipped` |
  | P-A.2 / P-A.3 | [32099750088](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32099750088) | `services=api-gateway`: exactly one matrix entry; frontend/seed/verify `skipped`; assert `success` |
  | P-A.2 / P-A.3 | [32100281322](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32100281322) | `services=portfolio-service`: exactly one matrix entry; frontend/seed/verify `skipped`; assert `success` |

  `market-data-service` → `market-data-refresh-job` is covered structurally (local positive and
  negative comparator tests), not with a third live roll. After the scoped rebuilds, prod is
  serving three distinct `--no-cache` digests of the same commit (`api-gateway` and
  `portfolio-service` from their scoped runs; `insight-service` and `market-data-service` from
  the merge deploy) — identical source, non-reproducible images.
  _Requirements: 1.21, 1.22, 1.23_

### P-B — digest deployment (based on P-A)

- [x] **P-B.1 Add a prebuilt-digest deploy path.** `deploy-azure.yml` currently runs
  `docker build --no-cache --pull -f <service>/Dockerfile.azure` (line 145) and then
  `az containerapp update --image …:${{ github.sha }}` (line 161) — it **rebuilds independently and
  deploys by tag**. Without this, the serving proof would describe a fresh rebuild rather than the
  attested candidate. Add an input accepting `repository@sha256:…` and a skip-build branch updating
  the Container App to **that exact manifest digest**, without building, pushing or retagging.
  _Requirements: 9.7_
- [x] **P-B.2 Fail closed at the trust boundary — and accept `portfolio-service` only.** The mode is
  privileged, so every ambiguity is an error rather than a default.

  **The digest path is deliberately narrow.** B1 needs it only for R-C. A generic form would accept
  `market-data-service`, whose Container App would take the supplied digest while
  `market-data-refresh-job` still moved by `${github.sha}` tag — breaking the exact-artifact invariant
  inside one logical service deployment. Any service other than `portfolio-service` is rejected.
  Generalising later needs a design covering every service-specific target; a half-generic mode is
  worse than a narrow honest one.

  Reject before any update when:
  - the selected service is not `portfolio-service`;
  - the selection is not **exactly one** service;
  - the ACR repository does not equal the selected service;
  - the reference is a tag rather than immutable `sha256:` syntax;
  - the manifest does not resolve in the expected ACR; or
  - a foreign registry or repository is named.
  Revision 4 specified one scalar digest alongside an allowlist and said nothing about zero, multiple
  or mismatched selections.
  _Requirements: 9.7_
- [x] **P-B.3 Prove the digest path actually works.** P-A.2 proves only that *unselected* apps are
  untouched — a workflow that ignores the digest, rebuilds the selected service, or updates the wrong
  repository passes it. Assert: no build or push step executed, the **selected** Container App
  resolves to the exact requested digest, and the scoped-mode skips from P-A.2 still hold.
  _Requirements: 9.7_
- [x] **P-B.4 Prove each rejection case fails before any update**, and that the default full-deploy
  path still works with the digest input absent.
  _Requirements: 9.7, 1.21_
- [x] **P-B.5 STOP/GO — P-B.**
  **Go:** P-B.3 and P-B.4 green.
  **Abort:** revert P-B only. P-A survives; the release lane stays closed until a digest path exists,
  or the candidate/serving model is re-derived around the fallback activation control.
  **Outcome (2026-08-18): GO.** Shipped in #108 (`5b9156d`).
  Gate order inverted: Azure OIDC is `ref:refs/heads/main` only, so live proofs ran after merge.
  Abort remains a revert of #108. Digest mode is unreachable except by deliberate dispatch of
  `deploy-azure.yml` — `deploy.yml` does not forward `prebuilt_digest`.
  Candidate: `wealthprodacr.azurecr.io/portfolio-service@sha256:abaaa97c8da97c800e911b2d4e98c6ab1d51dda2ea4f56d00290bb811da75145`
  (purpose-built from `main` @ `5b9156d`, not a salvaged serving image).

  | Task | Run | What it showed |
  |---|---|---|
  | P-B.4b | [32117991737](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32117991737) | Merge-triggered full path with digest input absent: four `deploy (…)` matrix entries (api-gateway, portfolio-service, insight-service, market-data-service), plus frontend, seed, verify all `success`; `assert-scoped-non-interference` `skipped` |
  | P-B.3 | [32123580730](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32123580730) | `services=portfolio-service` with that digest: Update Container App `success`; `market-data-refresh-job` `skipped`; revision Succeeded; `assert-scoped-non-interference` `success` (requested digest, three-layer `GITHUB_SHA` close); frontend/seed/verify `skipped` |

  Load-bearing P-B.3 evidence is **step-level**, not job-level. On that run,
  `Build Docker image` and `Push Docker image` were `skipped`, and the dedicated
  **Prove digest path skipped build and push** step succeeded. A green job conclusion
  does not distinguish a skipped rebuild from a silent rebuild; that assertion is
  what the digest model rests on.

  P-B.4a (every rejection fails before any update) is unit-tested in #108, not a live roll.
  Residual coupling: the comparator's `market-data-refresh-job` assertion is inert in digest
  mode (`--git-sha ""`). Safety lives in `resolve_digest_deploy.py` rejecting
  `market-data-service` before any update. Do not generalise the digest allowlist without a
  design covering every service-specific target.
  _Requirements: 9.7, 1.21_

**Both P-A.5 and P-B.5 are hard predecessors of the release lane.** Implementation is unaffected by
either.

---

## Wave 0 — Fixture identity migration · *implementation lane*

Production-neutral. It precedes Wave 1 because Artifact 0 removes the endpoints these fixtures use.

- [x] **0.1 Move `helpers/api.ts` to the E2E identity.**
  _Requirements: 8.3, 8.7_
- [x] **0.2 Move `helpers/browser-auth.ts` to the E2E identity.** The second, independent identity
  path — `global.setup.ts` and `golden-path.spec.ts` install the browser session immediately before
  the API helper runs. Migrating only the API helper yields a green suite proving nothing: API
  assertions pass against the E2E portfolio while the page renders dev's empty one.
  _Requirements: 8.3, 8.7_
- [x] **0.3 Convert `ensurePortfolioWithHoldings` to read-and-assert.** It creates a portfolio via
  `POST /api/portfolio` and adds holdings via the versionless `POST` today. It must assert the
  Golden-State setup and **fail hard** when seeding was skipped, never repair silently.
  _Requirements: 8.3, 8.7, 8.13_
- [x] **0.4 Update ticker expectations to canonical symbols.** `golden-path.spec.ts` asserts `BTC`
  twice; after Spec A the Golden-State set carries `BTC-USD`. Update the header comment, which still
  names the V3 seed as the fixture source.
  _Requirements: 6.7_
- [x] **0.5 Wire E2E credentials into `ci-verification.yml`**, which supplies `INTERNAL_API_KEY` and
  neither credential.
  _Requirements: 8.3_
- [x] **0.6 Wire `frontend-e2e-integration.yml`** with the internal key and E2E credentials. It has
  neither and still runs the affected suites, so leaving it unwired leaves a known-red manual
  workflow.
  _Requirements: 8.3_
- [x] **0.7 G0b evidence.** `golden-path` and `dashboard-data` pass against a **fresh disposable
  database** in one hermetic `ci-verification.yml` run, on the migrated identity. Requires Spec A's
  *implementation*, not its production cutover.
  Evidence: [run 32399211853](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32399211853)
  job `docker-build-verify` ([96530029529](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32399211853/job/96530029529)),
  conclusion `success`, commit `dd25e8b64f90a51c4c9a9c73fe14e05cc1c29b97`.
  _Requirements: 8.3, 8.7_

## Wave 1 — Legacy writer retirement (Artifact 0 → R-0)

- [x] **1.1 Retire `POST /api/portfolio`.** Pin the response — normally `405` on the surviving
  collection route. A unique-constraint violation must never surface as the public error.
  _Requirements: 8.5, 8.6, 8.8, 1.13_
- [x] **1.2 Retire the versionless `POST /api/portfolio/{portfolioId}/holdings`.**
  _Requirements: 8.1, 8.2, 8.4_
- [x] **1.3 Quantity_Domain on any interval either path stays reachable.** If both retire together
  this is vacuous — state that explicitly rather than skipping the check.
  Vacuous by construction: 1.1 and 1.2 retire both routes in the same change, so there is no
  interval where the versionless holdings POST stays reachable without the version check.
  _Requirements: 3.3_
- [x] **1.4 G0a evidence.** No traffic-serving portfolio digest exposes either route: revision →
  digest → traffic capture.
  Evidence — R-0 deploy fired by PR #125's merge:
  - Revision `portfolio-service--0000069`, digest
    `sha256:783c27e17d19d5d0027816e73feb2faea8d3492fc2890247101728a09698eba0`, built from commit
    `e27762c039de5861ca2444ab2e093730b1a75020`. `activeRevisionsMode: Single`, ingress traffic
    weight `100` to that revision — [deploy run 32408275319](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32408275319),
    job `deploy-azure / deploy (portfolio-service)`.
  - Source verified independently, not inferred from the deploy succeeding: rebuilt and tested
    commit `e27762c` in an isolated worktree — `createPortfolio`/`addHolding`/`AddHoldingRequest`
    absent from the compiled artifact, 175/175 tests green including `LegacyWriterRetirementTest`'s
    405/404 pins.
  - Live confirmation the digest is genuinely serving, not just deployed: `GET
    https://api.vibhanshu-ai-portfolio.dev/api/portfolio/health` → `200
    {"service":"portfolio-service","status":"UP"}`, captured live against this exact revision.
  - Gap, flagged rather than closed silently: a live *authenticated* probe of the two retired
    routes was not completed manually. Unauthenticated attempts against
    `POST /api/portfolio` and `POST /api/portfolio/{id}/holdings` got `401` with
    `WWW-Authenticate: Bearer` from the gateway, and obtaining a session requires entering a
    password, which was declined. PR #126 added assertions (e)/(f) to the `verify` job's live
    demo check for exactly this — `POST /api/portfolio` → 405, `POST
    /api/portfolio/{id}/holdings` → 404 — but merging #126 did not itself trigger a deploy
    (`.github/workflows/scripts/**` is outside `deploy.yml`'s path filter), so those assertions
    have not yet executed against production. They will self-verify on the next deploy.
  _Requirements: 8.9, 1.25_
- [x] **1.5 STOP/GO — R-0.**
  **Go:** G0a and G0b green.
  **Abort:** redeploy the prior portfolio digest, restoring both routes. Safe at this phase — no
  constraint exists yet, so a restored creator cannot produce a raw database error.
  **Decision: GO — R-0 kept.** Authorized by the repo owner on 2026-08-21. G0a (1.4) and G0b (0.7)
  were both green at decision time per the evidence cited above, including 1.4's flagged gap: the
  automated live authenticated route probe added in PR #126 had not yet executed against
  production (no deploy had run since it merged) — the owner authorized keeping R-0 with that gap
  known, not blind to it, on the strength of the revision/digest/traffic-weight and independent
  source verification already in hand. No abort/redeploy performed.
  _Requirements: 8.9, 1.25_

## Wave 2 — Gateway provisioning + asset route (Artifact 1 → R-A)

**Current status:** PR #131 merged to `main@fb115898` — tasks **2.1–2.6 and R-A are complete**.
G2 serving proof is green on the sole active gateway revision after scoped deploy
[32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627).
Durable evidence: [`docs/runbooks/B1_R_A_G2_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_A_G2_SERVING_PROOF.md).
G1 dual-schema proof (`SignupProvisioningDualSchemaIT`) remains green on the Wave 3 source lineage.
Historical proof branch
[`proof/b1-wave-2-g1-v20@e6a98c5`](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/tree/proof/b1-wave-2-g1-v20)
remains unmerged. **R-B / V20 production apply is complete** on Artifact 2 (see Wave 3 / tasks 3.5–3.7).

- [x] **2.1 Provisioning insert in `SignupService`**, inside its existing `TransactionTemplate` after
  `insertCredential`. Bind `userId.toString()` explicitly — the gateway generates a `UUID` and
  `portfolios.user_id` is `VARCHAR(255)`. Name only columns present in both schemas: `INSERT INTO
  portfolios (id, user_id)`, letting both timestamps and `version` default. Failure rolls back
  signup rather than producing a user without a portfolio.
  _Requirements: 1.5, 1.6, 1.7, 5.16_
- [x] **2.2 G1 candidate proof — dual schema, V19 → V20.** The insert runs against a database at V19
  and one at V20, exercising the `toString()` binding. A run from today's V16 or an unspecified
  baseline does not satisfy this.
  **Predecessor: task 3.1.** `V19` exists only on Spec A's repair branch and `V20` does not exist at
  all until 3.1 writes it, so both schemas must be present on this branch before the proof can run.
  Authoring `V20` is implementation work under requirement 9.2; **applying** it to production stays
  gated at 3.5.
  **Evidence:** `SignupProvisioningDualSchemaIT` is green against both schema targets on
  `cursor/b1-wave3-v20-schema` (also historically at proof commit `e6a98c5`). V20 remains excluded
  from R-A and unapplied in production.
  _Requirements: 1.5, 1.17_
- [x] **2.3 Add the `/api/assets/**` gateway route.** Ships here, not with the composition endpoint,
  so R-C cannot invalidate G2.
  _Requirements: 2.8, 9.3_
- [x] **2.4 STOP/GO — G1 before deploy.**
  **Go:** 2.2 green.
  **Abort:** switch to the signup-quiescence path, re-derive the remaining release lane, and do not
  proceed to Wave 3.
  **Decision: GO for the candidate proof only.** The V19→V20 proof is green on
  `cursor/b1-wave3-v20-schema`; this is not deployment authority. PR #131 is merged at `fb115898`
  but undeployed/unserved; tasks 2.5 and 2.6 remain incomplete, and no V20 application or gateway
  deployment is authorized by this decision.
  _Requirements: 1.21, 1.22, 1.23_
- [x] **2.5 G2 serving proof.** Every serving gateway digest provisions at signup: revision → digest,
  traffic, controlled probe.
  **Evidence — scoped `api-gateway` deploy after owner authorization (2026-08-26):**
  - Deploy run [32952197627](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32952197627):
    `deployment_mode=scoped`, `services=api-gateway`; `deploy-frontend` / `seed` / `verify` skipped;
    `assert-scoped-non-interference` success. Dispatch SHA / image tag
    `18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17` (Wave 2 `api-gateway/src/main` unchanged since
    `fb115898`).
  - Serving revision `api-gateway--0000076`, digest
    `sha256:2da5b303fd15772792167f2b26dc62250b2d9858270db315eab1d6d1a1554aec`, mode `Single`,
    sole active revision `Running`. Ingress remains `null`; traffic weight `0` while ingress is
    omitted. Peer services unchanged.
  - Controlled in-revision signup (`POST http://127.0.0.1:8080/api/auth/signup`) → **201**;
    probe user `b1-ra-g2-20260826152512@example.com` / `381e8203-1b2c-4c94-99d3-7c1fb365967a`.
  - Exactly-one SQL: `users` by email **1**, by id **1**, `portfolios` for that `user_id` **1**.
  - Runbook: [`docs/runbooks/B1_R_A_G2_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_A_G2_SERVING_PROOF.md).
  _Requirements: 1.16, 1.19, 1.24_
- [x] **2.6 STOP/GO — R-A.**
  **Go:** 2.5 green.
  **Abort:** redeploy the prior gateway digest and **do not start Wave 3** — the backfill must not run
  while a non-provisioning signup writer can receive traffic.
  **Decision: GO — R-A.** Authorized and verified 2026-08-26. G2 green on the sole serving
  revision; abort digest
  `sha256:ff80395eecaef731a089697dda50f34064612d478ac329e872631364082b7d0a` was recorded and not
  used. **Do not treat this as authority for Tasks 3.5–3.7 / V20 production apply.**
  _Requirements: 1.16, 1.18, 1.25_

## Wave 3 — Schema (Artifact 2 → R-B)

**Current status:** tasks **3.1–3.7 and R-B are complete**. Artifact 2 cut `25aa730` is serving on
`portfolio-service--0000080` /
`sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535` (deploy run
[32969683640](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32969683640);
V20 applied in production; G3 green). Evidence:
[`docs/runbooks/B1_R_B_G3_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_B_G3_SERVING_PROOF.md).
G2a/R-B2 and later waves remain separately gated.

- [x] **3.1 Write `V20`.** In file order: add `version BIGINT NOT NULL DEFAULT 0`; add `updated_at
  TIMESTAMP NOT NULL DEFAULT now()`; backfill with `u.id::text` casts on **both** the `INSERT` and the
  `NOT EXISTS` correlation; `ALTER TABLE portfolios ADD CONSTRAINT uq_portfolios_user_id UNIQUE
  (user_id)` as a **named table constraint**; drop the `quantity` default; add
  `chk_asset_holdings_quantity_positive`.
  **Evidence (on `main@25aa730`):** `V20__Portfolio_Composition_Contract.sql`;
  `V20MigrationIT` (structural defaults/constraints, `column_default IS NULL` on
  `asset_holdings.quantity`, fail-rather-than-clamp).
  _Requirements: 1.1, 1.2, 1.3, 1.8, 3.5, 3.6, 3.7, 5.1, 5.14_
- [x] **3.2 Prove backfill idempotency** under Flyway re-execution, and prove the `NOT EXISTS`
  correlation matches. A silent type mismatch treats every user as unprovisioned and inserts
  duplicates on re-run.
  **Evidence (on `main@25aa730`):** `V20MigrationIT.v19ToV20BackfillsUserWithoutPortfolioAndIsIdempotentOnRerun`
  (backfill to exactly one portfolio with `version = 0`, non-null `updated_at`, zero holdings;
  Flyway re-run leaves count unchanged); `AuthSchemaMigrationIntegrationTest.reRunningMigrateIsIdempotent`.
  _Requirements: 1.4_
- [x] **3.3 Migration fails rather than clamps** if a violating quantity exists. The preflight found
  none across 163 holdings, but it is a point-in-time observation and the migration runs later.
  **Evidence (on `main@25aa730`):** `V20MigrationIT.v20FailsRatherThanClampingWhenNonPositiveQuantityAlreadyExists`.
  _Requirements: 3.8_
- [x] **3.4 Add `version` and `updatedAt` to `Portfolio`; set both timestamps from one instant in
  `@PrePersist`.** Two `Instant.now()` calls can differ, making the equal-at-creation semantics false
  at database precision.
  **Evidence (on `main@25aa730`):** `Portfolio.java`; `PortfolioVersionMappingIT`
  (version `0`, equal `createdAt`/`updatedAt`, live holdings collection hydration).
  _Requirements: 5.1, 5.14, 5.16_
- [x] **3.5 STOP/GO — R-B preconditions.**
  **Go:** G0a, G0b and G2 green **before** the migration runs, **and Spec A's R3a applied to
  production** — verify by reading `flyway_schema_history` and confirming V17, V18 and V19 are
  present and successful, not by inferring it from a merge or a deploy (design Rev 11).
  **Abort:** do not run V20. No forward-repair problem exists while the migration has not executed.
  **Decision: GO — R-B preconditions.** 2026-08-26: G0a authenticated loopback 405/`Allow: GET` and
  404; G0b run `32399211853` / job `96530029529` success; G2 still on `api-gateway--0000076` /
  `sha256:2da5b303…` with ingress `null`; V17–V19 successful and V20 absent; duplicate groups and
  non-positive holdings `0`. Evidence:
  [`docs/runbooks/B1_R_B_G3_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_B_G3_SERVING_PROOF.md).
  _Requirements: 1.16, 1.25, 8.9_
- [x] **3.6 G3 evidence.** Relational postcondition after migration: no user has a portfolio count
  other than one. Assert the invariant, never a fixed total — a legitimate signup changes the number,
  and equal totals can mask one missing user against one duplicate.
  **Evidence:** after Artifact 2 digest deploy
  [32969683640](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32969683640)
  on `portfolio-service--0000080` /
  `sha256:d111132f576780fa5fec67dfc26ada3324153794746d21fe84b93b6822be3535`, `violating_users = 0`
  with distribution only `portfolio_count = 1`; holdings checksum preserved
  (`162` / `d6b344a1fca6ed11b59a146e5fb8825d`). Evidence:
  [`docs/runbooks/B1_R_B_G3_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_B_G3_SERVING_PROOF.md).
  _Requirements: 1.14, 1.24_
- [x] **3.7 STOP/GO — R-B post-migration.**
  **Go:** 3.6 green.
  **Abort — forward repair only.** V20 has committed; Flyway migrations are not reverted. Keep
  traffic safe and repair forward **without crossing below Artifact 0 + Artifact 1**: reverting the
  gateway would resume creating portfolio-less users under a live constraint. If G3 fails, identify
  the offending users and repair in a follow-on migration before Wave 5 begins.
  **Decision: GO — R-B.** 2026-08-26: V20 successful; schema/preservation checks green; G3 = 0;
  serving digest exact; peers unchanged. Forward-only boundary recorded in
  [`docs/runbooks/B1_R_B_G3_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_B_G3_SERVING_PROOF.md).
  _Requirements: 1.1, 1.14, 1.16_

## Wave 4 — Contract implementation · *implementation lane, no public exposure*

Buildable and fully testable from the start. It has no release of its own and becomes reachable only
in Waves 5–7.

### 4a — Orchestrator and preparers

**Current status:** tasks **4.1–4.21 (Wave 4a–4c) are merged on `main@2673f40`** (PR #153; undeployed/unexposed). Candidate packaging / task 7.5 / R-C remain incomplete. Public `PUT` remains Wave 7. **No Wave 4/5 runtime deployment is authorized** (V20/R-B already applied via Artifact 2 cut `25aa730`). No gateway route change, seed rewrite, or public `PUT` exposure is authorized by this status.

- [x] **4.1 `HoldingReplacementService`** — the single orchestrator, in D2's exact order: version
  precondition → semantic `400` (quantity, then duplicates) → catalog/lifecycle `422` aggregated →
  materialise via the injected `TuplePreparer` against the locked snapshot → compare → single parent
  CAS → refresh → child DML. Atomic: the whole desired state persists or none of it does.
  **Evidence (on `main@2673f40`):** `HoldingReplacementService`; unit tests for precedence
  and no-op CAS skip; `HoldingReplacementServiceIT` for version increment and persistence.
  _Requirements: 5.2, 5.4, 5.5, 5.7, 5.8, 5.9, 5.12, 5.13, 5.15, 5.17, 6.3, 6.4, 6.5, 6.11, 6.12, 6.13, 6.18, 7.16, 7.17, 7.18, 7.20, 7.21, 7.22, 7.23, 8.1_
- [x] **4.2 `CompositionTuplePreparer`** — expands ticker/quantity, preserving retained cost-basis
  tuples and capturing new ones. Reads **only** the snapshot locked in step 1. No weighted-average
  inference and no transaction history: this is a snapshot editor, not a trade ledger.
  **Evidence (on `main@2673f40`):** `CompositionTuplePreparer` + `CompositionTuplePreparerTest`;
  retained-basis IT in `HoldingReplacementServiceIT`.
  _Requirements: 6.14, 6.15, 6.16, 6.17, 10.7_
- [x] **4.3 `GoldenStateTuplePreparer`** — supplies its deterministic tuple and **takes the cost-basis
  anchor as an input**. Hardcoding the moving 25-hour value would silently undo Spec A's move of the
  demo path onto its fixed `app.demo.cost-basis-anchor`.
  **Evidence (on `main@2673f40`):** `GoldenStateTuplePreparer` + `GoldenStateTuplePreparerTest`
  (caller-supplied anchor; deterministic basis matches `PortfolioSeedService`).
  _Requirements: 8.11, 8.12, 8.14, 8.18_
- [x] **4.4 Absent-aggregate path.** Reject every non-zero expected version with `409` and virtual
  current version `0` **before** validation or insert; then validate, insert, and arbitrate on the
  named `uq_portfolios_user_id` constraint only. Provisioning and composition commit together or
  neither does.
  **Evidence (on `main@2673f40`):** absent path in `HoldingReplacementService`; IT coverage for
  empty creation at version `1`, non-zero expected rejection without insert, concurrent creators on
  `uq_portfolios_user_id`, and invalid set leaving no bare portfolio.
  _Requirements: 1.9, 1.10, 1.11, 6.20, 6.21, 6.22, 6.23, 6.24, 6.25, 6.26, 6.27, 6.28, 6.29, 6.30, 6.32_
- [x] **4.5 Catalog and lifecycle validation.** Canonical tickers only, no aliases. Active assets may
  be created, changed, retained or removed; a retained deprecated position may be retained, reduced
  or removed but never introduced or increased.
  **Evidence (on `main@2673f40`):** `CompositionCatalogValidator` +
  `CompositionCatalogValidatorTest` (aggregation, introduce/increase rejection, retain/reduce/remove).
  _Requirements: 6.6, 6.7, 6.8, 6.9, 6.10_

### 4b — Boundary, DTOs and error envelope

- [x] **4.6 Quantity_Domain at the application boundary** — required, strictly positive, at most 11
  integer digits and 8 fractional digits, maximum `99999999999.99999999`. The database `CHECK` is a
  backstop, not the specification. Rejection is a typed failure with atomic rollback at the
  Application_Operation layer, not a controller check.
  **Evidence (on `main@2673f40`):** `QuantityDomain` (Wave 4a) remains the application-layer
  authority; `StrictDecimalStringDeserializer` + `StrictDecimalFidelityTest` cover the wire boundary.
  _Requirements: 3.1, 3.2, 3.4, 3.9_
- [x] **4.7 Error envelope.** `ContractError` with `error` as the machine-code field. Plural
  `UnsupportedAssetsException` for aggregation; Spec A's singular exception and handler untouched on
  their path. Stable identifiers so B2 can branch without string matching.
  **Evidence (on `main@2673f40`):** `ContractError` + extended `ContractErrorCode`; typed
  `GlobalExceptionHandler` mappings; every `409` includes `currentVersion` (known value or D5
  after-rollback re-read via lookup identity on uniqueness-race / failed-CAS paths);
  `CompositionErrorEnvelopeTest` (machine codes, Spec A singular body preservation, plural ordered
  `tickers`, both unresolved re-read paths).
  _Requirements: 7.1, 7.2, 7.3, 7.6, 7.7, 7.8, 7.9, 7.10, 7.11, 7.24_
- [x] **4.8 Envelope boundary.** `HttpMessageNotReadableException` handler for malformed JSON and
  rejected tokens; `MethodArgumentNotValidException` handler for a missing `expectedVersion`. Boxed
  `Long` with `@NotNull`, plus a **property-scoped strict deserializer accepting only an integer
  token** — Jackson 3.1.4 defaults to `TryConvert` with `ACCEPT_FLOAT_AS_INT`, so `7.9` and `"7"`
  would otherwise decode as valid versions. Non-negative domain validated at the same boundary.
  **Evidence (on `main@2673f40`):** `CompositionHoldingsRequest` (quantity not `@NotNull` —
  required quantity stays in `QuantityDomain` after the version check) +
  `StrictExpectedVersionDeserializer` (`getAbsentValue` → `missing_version`; explicit null /
  overflow → `invalid_version`); `CompositionEnvelopeBoundaryTest` via test-only probe (no
  production `PUT`).
  _Requirements: 7.12, 7.13, 7.15_
- [x] **4.9 Decimal fidelity both directions.** Strict string deserializer on write;
  `toPlainString()` serializer on `HoldingResponse.quantity`, which emits a JSON number today. No
  exponential notation; trailing fractional zeros preserved as stored.
  **Evidence (on `main@2673f40`):** `ToPlainStringSerializer` on
  `PortfolioResponse.HoldingResponse.quantity`; `StrictDecimalFidelityTest` round-trip of
  `0.75000000`; consumer pact updated to string quantity.
  _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.7_
- [x] **4.10 Add `version` to `PortfolioResponse`.**
  **Evidence (on `main@2673f40`):** `PortfolioResponse.version` + `PortfolioService.toResponse`;
  `PortfolioResponseVersionTest`; `PortfolioServiceVersionMappingTest` (nonzero persisted version
  through `getByUserId`).
  _Requirements: 5.10_
- [x] **4.11 `GET /api/assets` controller.** Returns the Catalog_Version and the **full**
  Supported_Catalog entry set including deprecated assets — not active-only, since a retained
  deprecated position must render with its metadata. Each entry carries canonical ticker, name,
  aliases, asset class, quote currency and lifecycle status. No prices, no `basePrice`. `ETag` on
  Catalog_Version, `Cache-Control: private, no-cache`, `304` on match, no second client-side
  persistent cache. Authentication required, as on every `/api` route. Served by portfolio-service,
  which already holds the Catalog_Module in memory.
  **Evidence (on `main@2673f40`):** `AssetCatalogController` + `AssetCatalogResponse`;
  `AssetCatalogControllerTest` (full catalog incl. deprecated, no prices, auth, strong/weak/list/`*`
  If-None-Match → 304 with ETag/cache headers). Gateway route unchanged (Wave 2).
  _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.9, 2.10, 2.11, 2.12, 2.13_

### 4c — Candidate property suites

Named individually so the R-C manifest can enumerate them rather than gesture at a range.

- [x] **4.12 P1** — four-case matrix (version match/mismatch × desired equal/differs) on **both**
  writers.
  **Evidence (on `main@2673f40`):** `HoldingReplacementServiceTest.p1FourCaseMatrixOnBothWriters` (eight cells; counting delegates; stale cells assert zero materialise + no catalog/CAS/flush/refresh/save); `staleInvalidSemanticDoesNotReachRealPreparer` (both writer modes).
  _Requirements: 8.42_
- [x] **4.13 P2** — child-only change advances the parent version **exactly once**. Assert the numeric
  delta, not "changed": a double increment moves it too.
  **Evidence (on `main@2673f40`):** `ConcurrentCompositionIT.childOnlyMutationIncrementsVersionExactlyOnce`.
  _Requirements: 5.4, 5.7_
- [x] **4.14 P3, P4** — concurrent composition; two concurrent creators with **empty** desired sets,
  the case a pre-write version comparison cannot distinguish.
  **Evidence (on `main@2673f40`):** `ConcurrentCompositionIT.concurrentPresentMutationsExactlyOneWinsParentCasRace` (complete winner tuples); `concurrentAbsentCreatorsExactlyOneWinsNamedConstraintRace` (barrier after absence observe); `symmetricArbitrationCompositionVsGoldenStateExactlyOneTransition` (per-preparer AtomicInteger no-retry + winner tuples).
  _Requirements: 6.26, 6.27, 6.28, 6.29_
- [x] **4.15 P5, P6** — stale-but-equal reset yields `409`; a lost reset performs no retry.
  **Evidence (on `main@2673f40`):** stale-equal P1 cells; `HoldingReplacementServiceTest.resetLossHarnessInvokesOnceWithoutRetry`.
  _Requirements: 8.35, 8.40, 8.41_
- [x] **4.16 P7, P11f** — round-trip `0.75000000` byte-identical; no-op equality decided on the
  persisted `NUMERIC(19,8)` representation, since `BigDecimal.equals` reports `0.75` and `0.75000000`
  unequal.
  **Evidence (on `main@2673f40`):** `DecimalFidelityIT` requires response quantity exactly `0.75000000` on no-op; production no-op returns locked persisted tuples.
  _Requirements: 4.6, 8.17_
- [x] **4.17 P8, P11c, P11h** — envelope precedence; every envelope-failure code reachable and
  distinct; float, string, boolean and negative version tokens tested **independently**, sharing the
  one `invalid_version` code.
  **Evidence (on `main@2673f40`):** `CompositionErrorContractTest` including `quantityAsJsonNumberAgainstStaleVersionMapsToQuantityNotStringBeforeStatefulWork`.
  _Requirements: 7.12, 7.13, 7.15_
- [x] **4.18 P9** — a quantity `CHECK` violation surfaces as its own `400`, never as `409`.
  **Evidence (on `main@2673f40`):** `ConcurrentCompositionIT.namedQuantityCheckRejectsInvalidPreparerOutputWithoutMutatingAggregate`; named-CHECK translation in `HoldingReplacementService`.
  _Requirements: 6.31, 7.10_
- [x] **4.19 P11a, P11b** — creation binds both timestamps; the no-op path writes nothing and the
  **response** version equals the stored version.
  **Evidence (on `main@2673f40`):** creation/no-op ITs; `V20MigrationIT` +
  `SignupProvisioningDualSchemaIT` V19/V20 `created_at`/`updated_at`.
  _Requirements: 5.13, 5.16_
- [x] **4.20 P11i** — aggregate rejection reports **every** offender deterministically in request
  order, with the first in singular `ticker` and the full list in `tickers`, while Spec A's
  single-write body stays byte-identical. New in Revision 2: this property had no task.
  **Evidence (on `main@2673f40`):** plural envelopes; synthetic multi-lifecycle catalog; `multipleDuplicatedTickersAggregateInFirstOffendingRequestOrder`.
  _Requirements: 7.6, 7.8, 7.20_
- [x] **4.20a Composition behaviours that 4.1's citation does not exercise.** Four criteria were
  covered only by 4.1's catch-all and had no case stating the behaviour:
  - **request order is semantically irrelevant** — a reordered identical set is a no-op, with version
    and `updated_at` unchanged;
  - **no fixed maximum set size** — a request naming the **full active catalog** succeeds, bounded by
    catalog cardinality rather than a literal;
  - **Catalog_Version is not a write precondition** — a composition succeeds after an irrelevant
    catalog edit moves the version;
  - **the empty desired set is valid** — against an existing aggregate it removes every holding.
  **Evidence (on `main@2673f40`):** order/full-catalog/empty-set ITs; catalog-version not a write precondition unit cases.
  _Requirements: 6.5, 6.11, 6.12, 6.13_
- [x] **4.20b Aggregate every offender within a status class, not only tickers.** Extend the
  aggregation rule to semantic `400`s where multiple elements can fail — several out-of-domain
  quantities in one request report all of them, not the first.
  **Evidence (on `main@2673f40`):** `multipleDistinctQuantityOffendersAggregateInRequestOrder`.
  _Requirements: 7.20_
- [x] **4.21 Monotonic `updated_at`** — supply an equal timestamp, then a **regressed** one, and
  assert `new.updated_at > old.updated_at` in both cases.
  **Evidence (on `main@2673f40`):** equal/regressed clock ITs in `ConcurrentCompositionIT`.
  _Requirements: 5.15_

## Wave 5 — Version-bearing read (Artifact 2a → R-B2)

- [x] **5.1 Expose `version` on the authenticated `GET /api/portfolio`** while the old seed `POST`
  still tolerates the extra body field.
  **Evidence (on `main@f22e2ff`, PR #155):**
  `PortfolioControllerTest.authenticatedPortfolioReadReturnsPersistedVersion`; existing
  `PortfolioResponseVersionTest` and `PortfolioServiceVersionMappingTest`.
  Wave 4 already supplied `PortfolioResponse.version` and controller passthrough; this task adds the
  authenticated MVC boundary proof only. No production code change in the Task 5.1 merge itself.
  Serving proof is Task 5.2 / R-B2.
  _Requirements: 5.10, 5.11_
- [x] **5.2 G2a serving proof.** **Every** serving portfolio digest returns the version, **before**
  any caller migration begins. One caller's successful read can otherwise hit the new revision while
  another still reaches an old response with no version.
  **Evidence:** Artifact 2a cut `f22e2ff` digest
  `sha256:d544649f5b67baec8b563016882d239d3ecb9c5672399586e0bc656c78961d4f` serving on sole active
  revision `portfolio-service--0000081` (traffic `100`). Authenticated gateway read and direct
  revision probe both return unquoted numeric `version` matching Postgres. Deploy run
  [32982880866](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/32982880866).
  Evidence [`docs/runbooks/B1_R_B2_G2A_SERVING_PROOF.md`](../../../docs/runbooks/B1_R_B2_G2A_SERVING_PROOF.md).
  Any future portfolio rollout invalidates G2a until re-proven.
  _Requirements: 8.32_
- [x] **5.3 STOP/GO — R-B2.**
  **Go:** 5.2 green — **GO recorded 2026-08-26**.
  **Abort:** redeploy the prior portfolio digest and **do not begin caller migration**. Safe: no
  caller depends on the version yet. Never cross below Artifact 0 + Artifact 1.
  Abort path not used. Tasks 5.4–5.6 merged on `main@0b5d60d1` (PR #161).
  Task 5.7 / G5 closed by the owner's separate decision on 2026-09-02, using run `33411410271`
  and the reviewed evidence merged via PR #197. Restoration alone did not close G5; the owner
  decision below now does. Wave 6 implementation and R-B3 deployment remain separate work.
  _Requirements: 8.32_
- [x] **5.4 Migrate all three seed call sites** to log in, read once, and send that exact version:
  `synthetic-monitoring.yml` -> `.github/workflows/scripts/seed-portfolio-with-version.sh`,
  `frontend/tests/e2e/global-setup.ts`, `frontend/tests/e2e/azure-synthetic/api-live-smoke.spec.ts`.
  Shared helper freezes `expectedVersion`; Azure synthetic reaches all three. **Merged source-only
  on `main@0b5d60d1` (PR #161); that source merge itself did not close G5. See 5.7 for the
  later live evidence and owner close-out.**
  _Requirements: 8.32, 8.33, 8.34_
- [x] **5.5 Add E2E email/password to `deploy-azure.yml`'s seed step**, which carries only the user id
  and internal key.
  Seed job now supplies `E2E_TEST_USER_EMAIL` / `E2E_TEST_USER_PASSWORD` plus an `if: always()`
  Playwright sanitizer (`mode: live-secret`). **Merged source-only on `main@0b5d60d1` (PR #161).**
  _Requirements: 8.32_
- [x] **5.6 `409` workflow outcome:** fail the execution once, log the body, **never retry**. Retrying
  against the newer version is the silent overwrite the contract prevents.
  Shell, global-setup, and api-live-smoke treat 409 as terminal; request-capture tests cover
  one-attempt failure. **Merged source-only on `main@0b5d60d1` (PR #161).**
  _Requirements: 8.25, 8.35, 8.36, 8.37_
- [x] **5.7 G5 evidence.** Every call site, in every execution context, sends a version. Zero
  missing-version requests — enumerated per site, not inferred from one green run.
  Static inventory guard + unit/request-capture tests green on `main@0b5d60d1`. **Historical live
  public Azure synthetic failures** — authorized runs
  [33046987880](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33046987880)
  and
  [33047168136](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33047168136)
  failed at login with TLS reset / HTTP `000000` before any seed POST (not a 409). Evidence
  [`docs/runbooks/B1_G5_INGRESS_BLOCKER.md`](../../../docs/runbooks/B1_G5_INGRESS_BLOCKER.md).
  **Corrected 2026-08-31:** that failure was originally attributed solely to the Spec A ingress
  fence. There were two causes: 9.14 reopened ingress, and the later separately authorized recovery
  restored the previously absent custom-domain binding. The guarded apply/bind workflow's final
  immediate default-host health observation was non-`200`, but independent subsequent read-back
  observed `200` from both the default and custom public health endpoints. The binding uses the
  expected succeeded managed certificate; see
  [`api-gateway-custom-domain-binding`](../../../docs/todos/backlog/api-gateway-custom-domain-binding/README.md)
  and [`API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md`](../../../docs/runbooks/API_GATEWAY_CUSTOM_DOMAIN_RECOVERY.md).
  This is restoration evidence, **not** G5 evidence. PR #194 independently reviewed and merged that
  evidence at `main@98371587`. **Executed three-caller evidence (2026-08-31):** authorized public
  Azure synthetic run
  [33411410271](https://github.com/vibhanshu-agarwal/wealthmgmtandportfoliotracker/actions/runs/33411410271)
  from `main@f66d7ab6a4db1a327fd030ba9897bfc431104945` concluded **success** (Azure suite success;
  holdings-only re-seed; Playwright 9 passed) and logged:
  `[b1-g5][synthetic-shell] expectedVersion=0`,
  `[b1-g5][global-setup] expectedVersion=0`,
  `[b1-g5][azure-api-smoke] expectedVersion=0`.
  That live run is holdings-only executed evidence; documenting PRs remain source-only. Durable
  sanitized record:
  [`docs/runbooks/B1_G5_INGRESS_BLOCKER.md`](../../../docs/runbooks/B1_G5_INGRESS_BLOCKER.md).
  **Owner decision — GO / complete (2026-09-02):** “Please do the G5 close out.”
  PR #197 independently reviewed and merged the durable evidence at
  `main@b6c0da3f98a4a59bd810dbb77f273a1751946220`; that earlier documentation merge did not
  itself check this task. Today's explicit decision closes the remaining gate and checks 5.7.
  Codex reverified successful run/job conclusions and all three per-caller markers; source
  comparison from `f66d7ab6` through `48d0aba8` found no caller/helper/workflow/test drift,
  and the current inventory guard passes with exactly three callers. No live replay was needed.
  G5's Wave 6 prerequisite is satisfied. Tasks 6.1–6.4 later merged and 6.5 received owner GO;
  Tasks 6.6/6.7 and Wave 7 remain unchecked; no R-B3
  deploy, public `PUT`, Writer_Convergence, backlog closure, further dispatch, or schedule
  restoration is authorized. Unattended synthetics remain suspended in `synthetic-monitoring.yml`.
  _Requirements: 8.32, 8.39_

## Wave 6 — Version-required seed (Artifact 2b → R-B3)

**Tasks 6.1–6.4 source-complete:** merged PR #217 / `main@d66bb23d`; Codex ACCEPT at
`1bdb1d31`, R1/R2 closed, and final PR-event CI successful. The completion record above pins
the evidence. The [Claude kickoff](../../../docs/agent-instructions/CLAUDE_KICKOFF_B1_WAVE_6_VERSION_REQUIRED_SEED.md)
is retained as historical execution scope. Task 6.5 has the separate owner GO recorded below;
Tasks 6.6/6.7 remain unchecked. The read-only metadata snapshot is not G2b serving proof.

- [x] **6.1 Seed `POST` requires `expectedVersion`** and delegates to `HoldingReplacementService`.
  Target stays compiled-in. Failure returns Requirement 7's `409` envelope with
  `portfolio_version_conflict` and the current Portfolio_Version.
  _Requirements: 8.14, 8.16, 8.20, 8.21, 8.22, 8.25, 8.37, 8.38, 8.39_
- [x] **6.2 Remove `PortfolioSeedService.seed()`'s `deleteAll` + `flush` opening.**
  _Requirements: 8.29_
- [x] **6.3 Collision arbitration** — symmetric compare-and-set: exactly one transition commits; a
  losing user edit gets `409` rather than `404`; a losing reset returns **Requirement 7's exact
  envelope — `409` with `portfolio_version_conflict` and the current Portfolio_Version** — and does
  not retry. No write-maintenance gate. Only the Requirement 7 envelope names this outcome; no
  alternate internal contract is introduced.
  _Requirements: 8.23, 8.24, 8.26, 8.27, 8.28, 8.33, 8.40_
- [x] **6.4 Rewrite `PortfolioSeedServiceIT`** for identity preservation. Replace
  `EXPECTED_HOLDINGS = 160` with **active-catalog cardinality** — a literal would reintroduce the
  fixed-count defect Spec A removed. **Retain Spec A's full-table byte-identity price regression,
  sentinel rows included** (`P10`): this edits the exact writer from the PR #97 incident.
  _Requirements: 8.13, 8.19, 8.30, 8.31, 3.4_
- [x] **6.5 STOP/GO — G5 before deploy.**
  **GO — owner decision 2026-09-03:** “Yes Task 6.5 GO and read-only preflight approved.”
  [Readiness record](../../../docs/runbooks/B1_TASK_6_5_PRE_DEPLOY_READINESS.md) records the existing
  G5 technical evidence and the approved Azure/ACR metadata preflight. Sole portfolio revision
  0000093/digest 9a1d5533 still has 100% internal traffic; flags remain false. ACR confirms the
  existing digest and successful build cu3. This closes the decision, not a new deployment.
  The separately approved single build cu4 succeeded from controller-free 6a171558, producing
  digest 2be727ea…; readiness §6.1 and its JSON evidence record the full immutable reference,
  run/tag/manifest agreement and local cut tag. The prior 0000093/9a1d5533 image remains active
  at 100% traffic. Execution stopped after digest capture. The existing serving digest remains
  the proposed rollback target. Tasks 6.6/6.7 and aggregate AM.1/AM.2 stay unchecked.
  **Go:** 5.7 green.
  **Abort:** do not deploy the version-required endpoint; unmigrated callers would fail on the first
  run.
  _Requirements: 8.32, 8.39_
- [ ] **6.6 G2b serving proof.** Every serving digest requires the version and delegates; proved by a
  controlled seed showing identity preservation, the expected version outcome, and the price
  regression.
  **Preparation 2026-09-03:** [Execution packet](../../../docs/runbooks/B1_TASK_6_6_G2B_EXECUTION_PACKET.md)
  fixes cu4's digest, the guarded deployment, secure SQL/HTTP channel, complete tuple/price
  snapshots, one frozen-version E2E seed, and pre-seed-only conditional rollback. The independent
  E2E reference is offline expected data. The owner approved the bundle with “Please proceed”.
  The shared .env.secrets resolves all required client parameters; the earlier process-variable
  blocker was incorrect. Read-only database capture and authenticated readiness passed after the
  documented client correction and transient 504. The production gate was subsequently approved;
  dispatch 33718062217 succeeded and candidate revision 0000094 serves alone at 100% traffic.
  **Technical ACCEPT, 2026-09-03:** the [completed proof](../../../docs/runbooks/B1_TASK_6_6_G2B_SERVING_PROOF.md)
  records one HTTP 200 seed at N=0 with exact SAME_STATE preservation: parent/child rows, schema,
  demo, all 160 prices and 16,284 history rows are byte-identical BEFORE/AFTER. No retries or
  rollback. The ledger tick is proposed with the separate 6.7 owner decision below.
  The existing demo oracle cannot supply E2E cost-basis expectations.
  _Requirements: 8.14, 8.16, 8.30_
- [ ] **6.7 STOP/GO — R-B3.**
  **Go:** 6.6 green. Technical proof is now ACCEPT; owner decision pending.
  **Abort:** redeploy the R-B2 digest, restoring the version-tolerant seed, and do not begin Wave 7.
  **Execution boundary:** the completed seed ended the packet's pre-seed conditional rollback
  authority. Any later rollback requires a new explicit owner decision; no automatic redeploy
  is authorized by this checkbox or the technical ACCEPT.
  The floor is Artifact 0 + Artifact 1 until R-C activates; it rises to R-B3 afterwards.
  _Requirements: 8.4, 8.28_

## Wave 7 — Activation (Artifact 3 → R-C)

Portfolio-service only; the asset route shipped in Wave 2.


**Parallel source assignment — 2026-09-03:** Tasks 7.1–7.2 are assigned to Cursor under the
[public-composition kickoff](../../../docs/agent-instructions/CURSOR_KICKOFF_B1_WAVE_7_PUBLIC_COMPOSITION.md),
originally based on main@6a171558. Cursor reconfirmed readiness; it may start from the verified
docs-only descendant origin/main@9c2ebc12 under the existing local implementation authorization.
No execution or completion is reported; both boxes remain unchecked. Requirement 9.2 and the
implementation-lane graph permit development alongside the remaining R-B3 release work. The controller remains isolated on Cursor's branch and excluded from
R-B3. Tasks 7.3 onward, merge, deployment, and exposure remain separate. Publication of the new
implementation branch/PR needs owner approval under AGENTS.md.


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
  2. **Generate** the evidence manifest from **all classes present in both JUnit XML report sets**,
     carrying per-class **test, skipped, failure and error counts**, and assert that every class in
     7.5's required floor appears in it **with at least one non-skipped test case**. Presence in the
     XML plus a nonzero task total is not sufficient: a required class can be entirely skipped while
     unrelated classes keep the task green. `candidateVerification` runs
     the complete, unfiltered `test` and `integrationTest` tasks, so a normally-discovered new suite
     executes automatically and lands in the generated manifest without anyone remembering to list
     it. Assert also that no task reported zero tests.
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

  | suite | gradle task | required report class pattern |
  |---|---|---|
  | Legacy route contract (both retirements) | `test` | `*LegacyWriterRetirementTest` |
  | Asset discovery contract | `test` | `*AssetDiscoveryContractTest` |
  | Composition controller HTTP contract | `test` | `*CompositionControllerTest` |
  | Composition service + four-case matrix | `test` | `*HoldingReplacementServiceTest` |
  | Error envelope and precedence (P8, P11c, P11h, P11i) | `test` | `*ErrorContractTest` |
  | Version read | `test` | `*PortfolioVersionReadTest` |
  | Concurrency (P2, P3, P4, P11b) | `integrationTest` | `*ConcurrentCompositionIT` |
  | Decimal fidelity and no-op equality (P7, P11f) | `integrationTest` | `*DecimalFidelityIT` |
  | Seed delegation, identity, **price regression `P10`** | `integrationTest` | `*PortfolioSeedServiceIT` |
  | Migration and repository | `integrationTest` | `*V20MigrationIT` |

  **This table is a required minimum, not the selection.** `candidateVerification` runs both tasks
  unfiltered; these are **report class patterns** matched against the generated manifest, not
  command-line arguments — no `--tests` selector participates in any invocation.

  **Three failure conditions:** a task reporting zero tests; a required pattern absent from the
  generated manifest; and a required class present but with **no non-skipped test case**. The third
  stops a mis-tagged or fully-skipped suite from reporting green by executing nothing.

  **Discovery reconciliation.** A suite can still be written and then excluded or mis-tagged so it
  never runs at all. Compare B1-added and B1-modified `*Test.java` / `*IT.java` files against the
  generated manifest, with an explicit allowlist for abstract and helper classes. Entry [42] floated
  an opt-in tag or package as the completeness authority; that would recreate the omission risk at a
  new boundary, since a suite could then be written without the tag.

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
