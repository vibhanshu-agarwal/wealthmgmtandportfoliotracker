# B1 R-C Candidate Packaging — Architecture Review and Implementation Plan

**OWNER APPROVAL RECORDED — kickoff documentation publication, 2026-09-03:** After this review,
the owner requested a Claude kickoff and a docs-only PR. Publication of this report with the
[kickoff](../../agent-instructions/CLAUDE_KICKOFF_B1_R_C_CANDIDATE_PREPARATION.md) is authorized;
merge remains separate. The kickoff defines Claude's bounded local assignment when handed over.
No implementer has been started here. Implementation push/PR, ACR access, release image build or
publication, source-tag publication, registry-digest smoke, workflow dispatch, deployment, live
database access and live probes remain separate owner decisions on concrete packets.
Previous R-B3 approvals were fulfilled and do not authorize R-C operations.

> **For agentic workers:** Read the normative design and task ledger before this plan. Use
> superpowers:executing-plans if available. The assigned implementer owns tooling and tests;
> Codex remains the architecture reviewer. Checkboxes here track proposed work, not ledger completion.

**Goal:** Prepare an executable chain from an immutable source cut through unfiltered verification,
the resulting boot JAR, one registry image digest and an HTTP smoke of that exact image.

**Architecture:** Retain the Task 7.4 mechanism: one Gradle graph produces source-test evidence and
the boot JAR, then a separate packaging recipe copies that exact JAR into the Azure Mariner runtime.
Generated evidence validates each transition. Registry-image evidence and later serving evidence remain
distinct.

**Tech stack:** Repository Gradle wrapper 9.4.1, Java 21, Spring Boot 4.1.0, JUnit Platform,
PostgreSQL/Testcontainers, Python tooling, Docker and the existing ACR digest deployment path.

**Spec:** [B1 tasks](../../../.kiro/specs/portfolio-composition-contract/tasks.md),
[normative design Revision 11](../../../.kiro/specs/portfolio-composition-contract/design.md),
[requirements](../../../.kiro/specs/portfolio-composition-contract/requirements.md).

## Review result and source boundary

**ACCEPT the chosen architecture, with the prerequisites below. R-C build/release readiness is NO-GO.**
This is a preparation review, not a new review of Cursor's already accepted 7.1–7.2 controller changes.
The missing packaging machinery is planned Wave 7 work, not a regression in the completed R-B3 release.

Reviewed on 2026-09-03 against the local origin/main reference
`ddcd88c10efee3c7f5606c62a13be7b8e33343df` (PR #220 merge).
The original Codex branch at `7dfa9ee9db9554ec6585d9469aa1b21c863d91a4` had an identical
tracked tree and was clean. This document is on a new local branch from that origin/main reference:
`codex/b1-r-c-candidate-architecture-review`, in
`D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-codex`.

Tasks 7.1–7.2 source is merged through PR #219; its ledger reconciliation remains in the separate
Cursor review task. Tasks 7.3 onward, GC.5, AM.1/AM.2 and Writer_Convergence are not completed here.
The eventual cut-C must include the reviewed tooling and any separately approved prerequisite
corrections; today's main SHA is a review baseline, not an approved candidate cut.

R-B3 remains the **last verified** serving release in the completed release packet, based on source
`6a171558a0f802eadd5d7ed5bf28545ca5c91905` and image
`wealthprodacr.azurecr.io/portfolio-service@sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023`.
This review performed no new serving-state check.
The frozen R-B3 checkout, prior B2 worktree/audit files and other agents' worktrees were not changed.

## Findings

### R1 — Build prerequisite: the candidate chain has no executable carrier yet

**Priority: P1 before Task 7.3.**
Root `build.gradle:102–132` defines the two test tasks, including
`integrationTest.mustRunAfter(test)`; `139–145` provides the slim-image staging precedent.
Neither build file defines `candidateVerification` or `prepareCandidateArtifact`, and
`portfolio-service/Dockerfile.candidate` does not exist.
`Dockerfile.azure:54` recompiles with `bootJar`, so it cannot package the host-verified artifact.

Implement Task 7.4's machinery **before** invoking Task 7.3's single candidate build. The task numbers
describe evidence ownership, not a command sequence that requires building first and repairing the
binding afterward. In the candidate graph, require both test tasks and order bootJar after both;
an aggregate dependency list alone does not order sibling tasks. Keep ordinary bootJar usage
independent of candidate-only verification. Gradle distinguishes task inclusion from ordering
([official task-ordering documentation](https://docs.gradle.org/current/userguide/controlling_task_execution.html)).

Acceptance requires a fresh graph log, fresh reports, the resolved bootJar archive path and explicit
test-success checks before staging. Reject excluded, disabled, filtered, NO-SOURCE or zero-test
required tasks, stale reports and source changes during the run. A successful historical CI run or
a dry-run graph alone cannot satisfy this.

### R2 — Two required report patterns cannot match the current test tree

**Priority: P1 before manifest acceptance.**
Task 7.5 lists `*AssetDiscoveryContractTest` and `*PortfolioVersionReadTest`
(`tasks.md:1147,1151`). Neither class exists. Implementing the literal floor correctly would fail.

Existing coverage supports a small **ledger correction**, subject to review of its exact diff;
do not add empty alias tests or silently weaken the validator:

| Current requirement | Proposed concrete carrier(s) | Source evidence |
|---|---|---|
| Asset discovery | `test: com.wealth.portfolio.AssetCatalogControllerTest` | Full catalog including deprecated assets, no prices, ETag/cache headers and conditional 304 cases, lines 45–128 |
| Version read | `test: com.wealth.portfolio.PortfolioControllerTest` **and** `test: com.wealth.portfolio.PortfolioServiceVersionMappingTest` | Authenticated persisted-version response at controller line 53; nonzero persisted version and updatedAt mapping at service-test lines 42,58 |
| Version representation/persistence support | Also retain `PortfolioResponseVersionTest` and `integrationTest: PortfolioVersionMappingIT` in the generated manifest | DTO version/decimal serialization and real persistence mapping |

All other floor patterns must be checked against their actual report task and class names.
The floor is an assertion on the complete reports, never a test-selection list.
Keep the real `CompositionControllerIT`, `CompositionWriteServiceIT`, seed-collision and
demo-reset/startup tests visible in the evidence. Source-set unit tests do not replace these
transactional tests or the later registry-image smoke.

### R3 — Writer convergence needs a disposition for persistent repair SQL

**Priority: P1 before 7.6/G6 acceptance; no production defect asserted without reachability evidence.**
The application writer paths inspected delegate to the versioned replacement operation.
However, `V17__Repair_Archive_And_Timestamp3.sql:58–156` defines
`repair_migrate_holdings(text,text,text)`, which directly updates/deletes holdings without a
portfolio-version transition. V18 and V19 invoke it for historical migrations.
No removal/revocation of this function was found in the tracked migration/scripts search.

A migration's completed execution and a persistent callable function are different inventory entries.
Do not mark every writer version-participating by excluding all SQL as “historical.”
Establish the function's lifecycle and application/operational reachability against the normative
G6 scope. A source-only review cannot establish live privileges or prove it unreachable.

The inventory must keep this item **unresolved** until reviewed evidence shows it cannot participate
in post-activation writes, or a separately approved forward change closes the bypass.
Do not amend already-applied Flyway files, silently redefine G6, or make schema/privilege changes
inside the packaging bundle. Local tooling can proceed and report this blocker without accessing
production. Resolve the disposition before freezing the one candidate if a source/schema change
would be needed.

### R4 — GC.5 still has a symbolic base and needs an explicit comparison policy

**Priority: P1 before source-governance acceptance.**
`tasks.md:342–352` requires both a path guard and a content/AST guard over
`<B1-base>..<cut-C>`, expressly rejecting cut-B3 as the base.
The governing entries and inspected tooling do not provide a resolved B1-base SHA and executable
policy for that check.

Pin a reviewed historical commit that predates B1 implementation, prove it is an ancestor of cut-C,
and record its rationale. Recover it from the repository's Wave 0/implementation history rather
than asking the owner to supply a SHA that can be derived locally.
The interval also contains other merged work: account for that with explicit, reviewed provenance
and narrow dispositions. A blanket exclusion of frontend/Redis changes, a last-release-only diff,
or “the file was already on main” would defeat the guard.

A path allowlist cannot waive forbidden content in an allowed file. Fail closed on unknown changes,
unresolved base, Git errors and missing policy entries. Store both outputs separately from JUnit
results. Any conflict with the frozen normative scope is a review decision, not an implementer's
opportunity to relax it.

### R5 — Packaged-image smoke is new work; existing smoke jobs prove other artifacts

**Priority: P1 before 7.5a acceptance.**
`ci-verification.yml:301–340` builds an **api-gateway** image and overrides its entrypoint to run
`/probe.jar` and `/replica-token.jar`. It does not start the portfolio application or exercise
composition. `Dockerfile.slim-it` uses Amazon Corretto/Amazon Linux and a Lambda adapter;
reuse its staging pattern, not its runtime.

The new smoke must pull and run the recorded
`wealthprodacr.azurecr.io/portfolio-service@sha256:…`, using its shipped entrypoint and JAR.
Supply disposable local dependencies and synthetic identities. Exercise startup, assets, a successful
composition and a stale-version 409 with unchanged persisted state. A local tag or host-JAR run is
only development feedback. Label it accordingly.

Preserve the Azure Mariner Java 21 runtime and record the resolved runtime-base digest and target
platform. Use the reviewed Azure/prod configuration with local dependency endpoints and an explicit
list of environment differences. Production profile listens on 8080
(`application-prod.yml:8–9`); the Dockerfile's EXPOSE 8081 does not set the application port.
Do not “fix” the recipe to AWS defaults to make the smoke pass.

## Proposed writer inventory seed

This table identifies concrete source paths for the implementer; it is **not** the final digest-bound
Task 7.6 attestation. Re-enumerate the entire tracked source tree at cut-C, including SQL, scripts,
indirect parent deletion/cascades and persistence helpers. Search results require call-path review.

| Entry/path | Observed behavior | Required inventory disposition |
|---|---|---|
| CompositionController → CompositionWriteService.replace (line 47) | Passes authenticated user, request version and intent to replacement in one transaction | Version-participating; bind HTTP/transactional tests |
| PortfolioSeedController (line 55) → PortfolioSeedService (line 128) | Server-fixed E2E identity; supplied expectedVersion passed to replacement | Version-participating; retain price-preservation and collision evidence |
| DemoResetService.reset (line 65) | Fixed demo identity and supplied expectedVersion passed to replacement | Version-participating; include B2 writer because it is in the same service tree |
| DemoPortfolioInitializer.convergeInTransaction (lines 150–164) → seed | Freezes the version observed during eligibility; may seed on startup | Include even when feature gate is off; bind stale-observation/no-retry tests |
| HoldingReplacementService (lines 57–193) | Shared child mutation sink; present-state no-op returns unchanged; changed/created state performs parent CAS before child DML | Inspect transaction boundary, exactly-one transition, rollback and every caller |
| Portfolio collection helpers; AssetHoldingRepository | Mutable JPA cascade/orphan-removal and repository mutation capability | Enumerate callers; “currently unused mutation method” is a classification, not automatic proof of future safety |
| V3 initial holdings inserts; V18/V19 repair calls | Historical migration writes before V20/version activation | Record ordering and completed-migration evidence; distinct from retained function reachability |
| V17 repair_migrate_holdings | Persistent SQL mutation function without version transition | R3 unresolved |
| V1 portfolio FK cascade; parent-delete paths | Deleting a portfolio can delete holdings without mentioning that table at the call site | Search repository/SQL parent deletes and their reachability |
| SpecA912StartupTransactionDiagnostics (line 35) | Diagnostic statement is DELETE FROM portfolios WHERE FALSE; probe rolls back | Record exact predicate and rollback path; no affected holdings, not a generic diagnostic exemption |
| PortfolioService/analytics/integrity assertions | Inspected holdings SQL is read-only | Retain as classified search hits, not writers |

Keep an explicit list of test-fixture exclusions with paths and reasons. The final evidence must
identify its enumeration rules/version, all hits, caller-to-sink mapping, test references and unresolved
items. Any unresolved mutation path prevents a green G6 result.

## Implementation sequence and file responsibilities

These are proposed paths for new tooling; only the build files, ignore files and normative ledger
already exist. Keep infrastructure, application behavior, production configuration and deployment
workflows outside this implementation scope.

| File | Responsibility |
|---|---|
| `portfolio-service/build.gradle` | Portfolio-only candidate aggregate, ordering and staging tasks; reuse root test tasks |
| Root `build.gradle` | Read the existing source-set/AOT/staging conventions; change only if narrowly required to integrate them |
| `.gitignore`, `.dockerignore` | Ignore candidate staging in Git; preserve Docker visibility and correct the builder-only comment |
| `portfolio-service/Dockerfile.candidate` | Copy the verified staged JAR into the Azure Mariner runtime; no Gradle/application recompilation |
| `scripts/b1_candidate_evidence.py` | Validate source/run identity, JUnit reports, discovery coverage, staged/extracted hashes and evidence joins |
| `scripts/b1-candidate-policy.json` | Reviewed floor-to-class mapping, helper exclusions and pinned source-governance policy |
| `scripts/check_b1_candidate_source.py` | GC.5 path/content outputs, artifact composition check and writer enumeration/dispositions |
| `scripts/verify_b1_candidate_image.py` | Exact-digest black-box HTTP harness with owned disposable fixtures and explicit evidence output |
| `scripts/tests/test_b1_candidate_evidence.py` | Fail-closed parser, freshness, discovery, hash and identity cases |
| `scripts/tests/test_check_b1_candidate_source.py` | Governance/inventory policy mutation fixtures and Git-error behavior |
| `scripts/tests/test_verify_b1_candidate_image.py` | Harness argument, assertion, evidence and cleanup failure cases |
| `.kiro/specs/portfolio-composition-contract/tasks.md` | Reviewed carrier-name correction and preparation links; no release-completion ticks |
| `docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md` | One supported local verification procedure and separately gated publication/smoke procedure |

### Task A — Verification graph, floor correction and generated source evidence

- [ ] Resolve R2's mapping and R4's historical base/policy from local source history. Submit any
  normative discrepancy explicitly for review. Keep R3 visible while the other tooling proceeds.
- [ ] Write negative fixtures for absent/zero/all-skipped required classes, wrong task, malformed XML,
  task failure, stale reports, filtered or excluded tests, missing new suites and source identity drift.
- [ ] Register candidateVerification and prepareCandidateArtifact for portfolio-service.
  Staging must depend on successful candidate verification, not on bootJar alone.
- [ ] Generate entries from **all** classes in both JUnit XML report sets; preserve fully qualified
  names, task, tests, skipped, failures and errors. Reconcile suite counters with testcase elements.
  Handle nested and parameterized classes explicitly; do not infer totals from console summaries.
- [ ] Reject failures/errors and unexpected rerun/flaky evidence rather than hiding failed attempts.
  Gradle's optional merged-rerun XML can contain flakyFailure elements
  ([JUnit reporting documentation](https://docs.gradle.org/current/userguide/java_testing.html)).
- [ ] Reconcile B1-added/modified test source files to report classes with path-specific abstract/helper
  exceptions. Account for deletions, renames and nested classes. A newly excluded suite must fail the
  check. Existing slim-image-tagged suites need a named evidence disposition because the chosen two
  tasks intentionally exclude them; do not claim the separate slim-image graph ran.
- [ ] Run the full graph in a clean, immutable checkout with local dependencies. Use the wrapper;
  no --tests or -x, no ignored test failures, no silent retry. Use candidate-only freshness settings
  such as --rerun-tasks --no-build-cache and reject missing required task execution.
  Preserve normal developer task behavior outside candidate mode.
- [ ] Return the graph ordering, fresh generated manifest, raw reports/hashes and staging SHA.
  **Acceptance:** both complete tasks execute with nonzero results; every required concrete carrier
  has a non-skipped case; bootJar finishes afterward; no tracked source changes occur during the run.

The full run may expose existing test failures; report them without weakening the floor or making
unrelated product changes. A dry-run is useful for ordering but cannot close this task's runtime proof.

### Task B — Packaging and digest-evidence interface

- [ ] Add candidate staging to .gitignore and keep it visible in the effective Docker context.
  Update .dockerignore's opening comment to distinguish existing in-container builders from the
  candidate copy-only recipe. Preserve the **/build/ exclusion.
- [ ] Check the effective ignore rules, including any Dockerfile-specific ignore file.
  Dockerfile-specific ignore rules take precedence over the root file
  ([Docker build-context documentation](https://docs.docker.com/build/concepts/context/)).
  Prove the candidate COPY succeeds; a substring search for a directory name is insufficient.
- [ ] Stage only the resolved bootJar archive as .candidate-artifacts/portfolio-service.jar;
  reject ambiguous glob selection and assert its SHA equals the post-graph JAR SHA.
- [ ] Add the Mariner copy-only recipe with the production entrypoint and runtime configuration
  contract. Preserve bootJar's AOT contents; require graph evidence rather than rebuilding in Docker.
- [ ] Define a one-build operator step with an explicit runtime-base digest and platform; record the
  recipe/context identity. Development recipe tests are not release candidates.
- [ ] After the authorized candidate build, extract /app.jar from that exact built image and compare
  its SHA before any push. Record local image identity for traceability, never as the deploy digest.
- [ ] After separately authorized ACR publication, resolve the registry manifest digest and use the
  immutable repository reference. Record media type/platform; if it is an image index, record the
  selected platform manifest too so smoke and deployment cannot silently select different artifacts.
- [ ] Do not rebuild to repair a failed proof. Invalidate the attempt and return for a new reviewed
  cut/build decision if source or packaging must change.
- [ ] Return local tooling tests and a complete proposed operator packet before requesting publication.
  **Acceptance:** hash mismatch, missing staged file, wrong repository/tag-only identity or incomplete
  image binding cannot emit a candidate-ready result. No registry access is needed to implement the
  validators with fixtures.

### Task C — Source-governance/writer evidence and registry-smoke harness

- [ ] Implement GC.5's two separate checks and generate writer enumeration with reviewed dispositions.
  Add fixture mutations for an unexpected Java writer, raw holdings SQL, parent deletion/cascade,
  forbidden path and forbidden symbol in an allowed file. Keep R3 unresolved until its disposition
  has evidence; do not auto-pass known historical SQL.
- [ ] Assert cut-C artifact composition from the actual complete source tree, including the public
  composition mapping and retained versioned seed/legacy retirements. Record source-tag identity at
  the approved build under AM.1; publishing a tag remains separately authorized.
- [ ] Implement startup and contract smoke on a private local Docker network with disposable
  PostgreSQL and other required local dependencies. Use no production credentials or shared database.
  Keep the application's shipped entrypoint; do not mount a replacement JAR or test-class overlay.
- [ ] Use an owned synthetic user. Prove GET /api/assets, a successful nontrivial composition, and
  replay with stale expectedVersion yielding the exact portfolio_version_conflict envelope.
  Verify currentVersion and unchanged parent/holdings after rejection; preserve request/response and
  DB assertions. Read or establish fixture state once, not a retry loop that advances expectedVersion.
- [ ] Require the immutable ACR digest as the release-smoke input, pull that digest and record the
  container's actual image/platform plus startup readiness. Recheck extracted JAR SHA after the pull.
  Local-fixture tests may validate the harness; only the authorized digest run completes 7.5a.
- [ ] Make cleanup remove only containers/networks/volumes created by this run. On any failure,
  preserve sanitized evidence and emit failure; never turn cleanup or missing dependencies into a pass.
- [ ] Return the generated source evidence, unresolved inventory entries and harness test results.
  **Acceptance:** substitution of a mutable tag, wrong image/digest, wrong HTTP body, stale-run report
  or unclassified writer is rejected.

## Evidence contract

Use one versioned JSON schema with a run ID and explicit phase/status, plus hashed evidence files.
A local verification bundle must be marked incomplete for release until registry and smoke joins exist.
Do not mint an “attested” success object by combining unrelated historical files.

| Evidence group | Minimum binding |
|---|---|
| Source | Commit SHA, tree SHA, clean-before/after checks, pinned B1 base, cut/tag identity when created |
| Verification | Command/task identities, run start/end, JDK/wrapper versions, actual task outcomes, per-class counts, report file SHA-256 values, floor and discovery results |
| JAR/stage | Resolved bootJar path, JAR SHA-256, staged path/SHA-256, successful same-run verification reference |
| Packaging | Dockerfile/context policy hashes, runtime-base digest, target platform, local image identity, extracted JAR SHA-256 |
| Registry | Exact repository@manifest-digest, manifest media type and platform mapping, publication evidence reference |
| Governance | Path/content guard outputs and hashes, policy/base, writer inventory hash, no unresolved entries for acceptance |
| Smoke | Exact pulled image/digest/platform, shipped entrypoint, environment-difference list, startup/HTTP/DB assertions and evidence hashes |

Evidence need not be a new signing service or supply-chain platform. The required property is a
machine-checked chain; reports and commit labels alone are insufficient. Never include credentials
or a full environment dump in the bundle.

## Release checkpoints after the implementation review

1. Review the completed A–C diff and resolve R2–R4, including any separate prerequisite change.
   Select a clean cut-C containing all accepted changes; reconcile source composition and tag policy.
2. Obtain the concrete owner authorization for the one candidate build/publication and exact-digest
   smoke, with registry/platform/dependency details and failure handling.
3. Execute the verification → stage → build → extract/hash → publish → pull/smoke chain.
   Store 7.4/7.5/7.5a/7.6 evidence against Task 7.3's single accepted digest.
4. For 7.7, validate currently serving G2, G3 after the latest valid G2, G4 and G6
   (serving G0a/G2a/G2b plus the exhaustive inventory). Carry forward old evidence only with a
   demonstrated uninterrupted artifact chain. The completed R-B3 packet alone is not fresh R-C GO.
5. Task 7.8 owner pre-deploy GO precedes deployment. The existing
   deploy-azure.yml:209–244 skips and checks build/push in digest mode; its resolver restricts the
   registry/repository and portfolio-only selection. Reuse this path rather than changing it.
6. Deploy only that digest, then collect serving revision/traffic/probe evidence for 7.9–7.10.
   The generic gateway route makes the composition controller reachable immediately on rollout.
   On failure, the permitted R-C rollback floor is R-B3; obtain explicit execution authority for that
   packet. The previous R-B3 attempt's conditional rollback authority is not reusable.
7. Only accepted evidence and owner decisions close the relevant ledger entries and 7.11 floors.

## Review verification and limits

Read-only source inspection covered the Gradle tasks, Docker recipes/ignore rules, CI smoke and
digest-deploy path, actual floor-test carriers, Java writer call paths, historical holdings SQL and
the governing Wave 7/gate text. A tracked-file search supplemented ripgrep to cover ignored-directory
and indirect-writer cases. The two missing test names and absent candidate Dockerfile/tasks were
confirmed; the reviewed pre-branch tree matched origin/main.

Self-review checked the graph/artifact/serving distinctions, floor/discovery completeness, runtime
parity, source-base policy, writer exceptions, task ordering and owner/worktree boundaries.
This review ran no application test suite, Docker build, ACR/cloud query, secret read, workflow,
deployment or database probe. It makes no new test-pass or live-state claim.
At the architecture-review checkpoint, only this local report was added. The later owner-requested
kickoff publication adds the Claude handoff and matching preparation links in the master plan/B1
ledger. No application code, completion checkbox or runtime state changes in that package.
