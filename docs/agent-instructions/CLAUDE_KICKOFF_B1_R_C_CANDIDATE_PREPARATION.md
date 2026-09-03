# B1 R-C Candidate Preparation — Claude Kickoff

**OWNER APPROVAL RECORDED — kickoff documentation publication, 2026-09-03:** The owner requested
this Claude kickoff and a docs-only PR. Codex may publish this documentation package and open that
PR. Merge is not authorized by this request. This note defines Claude's bounded local implementation
assignment when handed to Claude; drafting or publishing it does not start an agent.

**OWNER APPROVAL REQUIRED — later implementation publication and release:** Claude must return a
concrete local diff and evidence before requesting a push/implementation PR. Approval then permits
that named publication; withholding it leaves the reviewed local work available. A merge, release
candidate build, ACR/cloud/secret access, source-tag publication, registry-digest smoke, workflow
dispatch, deployment, live probe, schema/privilege change or schedule change requires its own
applicable owner authorization. This kickoff supplies none of those release permissions.
Continue all local preparation that does not depend on a pending decision.

> **For agentic workers:** Use superpowers:executing-plans if available. Claude owns implementation
> and tests; Codex owns architecture review and governed status reconciliation. These checkboxes are
> execution notes, not completion entries in the B1 ledger.

**Goal:** Implement and locally validate the candidate packaging/evidence machinery needed before
B1 Task 7.3's single R-C build.

**Architecture:** One immutable checkout and one Gradle invocation produce complete source-test
results and the boot JAR. Stage and hash that JAR, then package it with a copy-only Azure Mariner
recipe. Generate evidence that joins source, reports, JAR, image and governance outputs; implement
the exact-registry-digest HTTP smoke harness for its later authorized run.

**Tech stack:** Java 21, Gradle wrapper 9.4.1, Spring Boot 4.1.0, JUnit Platform,
PostgreSQL/Testcontainers, Python and Docker.

**Required plan:** [Architecture review and implementation Tasks A–C](../superpowers/plans/2026-09-03-b1-r-c-candidate-architecture-review.md).
Its file map, R1–R5 findings, writer inventory seed, acceptance cases and evidence schema are part of
this assignment. This kickoff adds ownership, execution order and the return packet; it does not
replace the normative specification.

## First reads and branch setup

1. Read this worktree's `AGENTS.md` and `CLAUDE.md`, if present, then the linked review.
2. Read [B1 tasks](../../.kiro/specs/portfolio-composition-contract/tasks.md) GC.5, 7.3–7.11 and
   AM.1/AM.2; [design Revision 11](../../.kiro/specs/portfolio-composition-contract/design.md)
   D9/gates/rollback; and [requirements](../../.kiro/specs/portfolio-composition-contract/requirements.md)
   8–10. Design remains normative when task wording differs.
3. Read the [master plan](../plans/ASSET_PICKER_E2E_MASTER_PLAN.md),
   [R-B3 serving proof](../runbooks/B1_TASK_6_6_G2B_SERVING_PROOF.md) and
   [existing public-composition review packet](../../audit/b1-wave7/README.md).
4. Use Claude's assigned worktree:
   `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`.
   Verify `git rev-parse --show-toplevel` before any mutation. Preserve unrelated changes.
   If durable isolation is necessary, follow AGENTS.md's sibling-worktree naming/location rules.
5. Fetch origin/main and inspect its actual head. Suggested branch:
   `claude/b1-r-c-candidate-preparation`. The reviewed baseline is
   `ddcd88c10efee3c7f5606c62a13be7b8e33343df`; record any later drift before coding.
   Read this docs PR directly if it is not merged; do not cherry-pick unrelated documentation history
   or use the earlier R-B3 source cut as the implementation base.

R-B3 is the last verified serving release, source `6a171558`, cu4 digest
`sha256:2be727eaf4577699c783ae66073670d4984fe66c666af3e56422c934fdd0b023`.
This kickoff does not refresh that live proof. The frozen R-B3 checkout and the earlier B2 audit
worktree are out of scope.

Cursor's 7.1–7.2 source merged in PR #219. Its separate review task owns checkbox reconciliation;
do not reimplement the controller or advance those boxes here. R-C preparation does not activate it.

## Scope and file ownership

Implement Tasks A–C from the review: Gradle graph/staging, candidate recipe, generated manifest and
validators, source guards/writer inventory, isolated image-smoke harness, their tests and operator
runbook. Local disposable test containers and clearly labelled development image builds are allowed;
they are not Task 7.3 release candidates and must not be pushed or represented as release proof.
Use public development dependencies or existing local caches as needed; do not contact production.

| Area | Permitted change |
|---|---|
| Build | `portfolio-service/build.gradle`; narrow root `build.gradle` integration only if required |
| Packaging | `.gitignore`, `.dockerignore`, new `portfolio-service/Dockerfile.candidate` |
| Evidence tooling | New `scripts/b1_candidate_evidence.py`, `scripts/b1-candidate-policy.json`, `scripts/check_b1_candidate_source.py`, `scripts/verify_b1_candidate_image.py` |
| Tooling tests | Matching `scripts/tests/test_*.py` files named in the review, with fixture data confined to test/evidence locations |
| Runbook | New `docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md` with executable local steps and separate release steps |
| Governed docs | Propose the R2 carrier correction and preparation status to Codex; coordinate the final master-plan/B1-ledger patch in the implementation review |

Application Java behavior, production configuration, applied migrations, database privileges,
frontend, gateway, Redis/presence behavior, and CI/deployment workflow changes are outside the bundle.
If a local test exposes a product defect or R3 needs a forward migration, return the finding and
smallest separate proposal. Keep independent tooling work moving.

## Checkpoint 1 — Resolve policy inputs and build the verification graph

- [ ] Confirm actual report carriers. Propose replacing `*AssetDiscoveryContractTest` with
  `*AssetCatalogControllerTest`; replace the missing `*PortfolioVersionReadTest` requirement
  with **both** `*PortfolioControllerTest` and `*PortfolioServiceVersionMappingTest`.
  Preserve DTO/version-mapping support and the rest of the floor. Include a coverage-to-test table
  for Codex's review; do not add empty aliases or silently accept an absent required pattern.
- [ ] Derive a concrete pre-B1 base from local history, with full SHA, ancestry proof and rationale.
  Prepare a GC.5 path/content policy that accounts explicitly for interleaved non-B1 work. No
  blanket directory exemption or automatic “already merged” exception may hide a prohibited change.
- [ ] Make discovery boundaries explicit: the two required tasks report portfolio-service classes.
  Reconcile B1-added/modified portfolio test files, including renames/nested classes and named helper
  exclusions, against those reports. List B1 tests in other modules and their separate evidence
  carriers; do not pretend they ran in the portfolio graph. If the normative completeness rule needs
  additional task coverage, return that precise proposal to Codex before marking discovery green.
- [ ] Write the review's negative manifest/discovery fixtures, then implement the smallest validators
  that pass them. Include absent/zero/all-skipped classes, wrong task, stale/malformed XML, failures,
  filtered/excluded tasks, unreported new suites and source identity drift.
- [ ] Register `candidateVerification` and `prepareCandidateArtifact`. The aggregate includes full
  test and integrationTest, with bootJar ordered after both. Manifest validation must succeed before
  staging; invoking staging alone must not bypass verification. Avoid a dependency cycle.
- [ ] Use one Gradle invocation, such as the command below, so shared tasks execute once. Freshness
  options belong to this candidate procedure; ordinary bootJar use must retain its existing behavior.
- [ ] Return policy decisions, any unresolved normative conflicts, graph ordering and fresh report
  counts. Continue independent packaging/harness work while those decisions are reviewed.

The required suites use source-set outputs. Their evidence accompanies the JAR-producing graph;
it is not a claim that JUnit executed the fat JAR. A dry-run proves ordering only.

## Checkpoint 2 — Package exactly the graph's JAR

- [ ] Use the resolved bootJar archive, hash it after verification, and copy it to
  `.candidate-artifacts/portfolio-service.jar`; require staged SHA equality.
- [ ] Git-ignore staging while preserving its effective Docker-context visibility. Keep the existing
  **/build/ exclusion and check any Dockerfile-specific ignore rules. Verify the actual COPY path.
- [ ] Implement the copy-only recipe using the Azure Mariner Java 21 runtime and shipped entrypoint.
  Preserve AOT contents. Do not run Gradle inside it or reuse the AWS/Lambda runtime.
- [ ] Validate extraction/hash checks and missing/wrong-artifact failures using local development
  images. Record recipe, platform and runtime-base identity in the development evidence.
- [ ] Prepare the future one-build/push operator procedure with fixed source cut, runtime-base digest,
  platform, extracted-JAR equality and registry-manifest lookup. Validate its inputs with fixtures;
  do not perform the release build/push or log in to ACR.
- [ ] Keep a local image ID separate from a registry manifest digest. If a registry reference resolves
  to an image index, bind the selected platform manifest as well.
- [ ] Return tooling results and an evidence example marked `LOCAL_PREPARATION` or equivalent.
  Missing registry/smoke joins must prevent a candidate-ready/attested result.

## Checkpoint 3 — Writer/source evidence and HTTP smoke harness

- [ ] Re-enumerate writers from the entire tracked source tree. Include Java call paths, raw SQL,
  migration-created functions, startup seeding, demo reset, parent deletion/cascades and scripts.
  Preserve classified non-writer hits and path-specific fixture exclusions.
- [ ] Keep R3's `repair_migrate_holdings` disposition explicit. V18/V19's completed historical calls
  do not establish that V17's retained function is unreachable after activation. Investigate source
  and existing sanitized evidence locally; live privilege checks require a separate decision.
  An unresolved entry must block writer/G6 acceptance without blocking unrelated tooling tests.
- [ ] Implement separate GC.5 path and content outputs, plus artifact-composition evidence.
  Add negative fixtures for a forbidden symbol inside an allowed file, an unexpected writer,
  parent cascade/deletion and Git/base-resolution errors.
- [ ] Implement smoke on a private local network with disposable PostgreSQL and any other required
  dependencies, owned synthetic identities and the shipped entrypoint. Record prod/Azure profile
  differences explicitly; prod uses port 8080.
- [ ] Assert startup, GET /api/assets, a nontrivial successful composition and a real stale-version
  409 envelope/currentVersion with unchanged parent/holdings. Preserve precise assertions and
  sanitized evidence; do not retry by refreshing expectedVersion.
- [ ] The release-smoke entrypoint must require and pull the exact ACR repository@sha256 reference,
  check the image/platform and extracted JAR, then run the contract assertions. Its fixture/local
  harness tests may run now; the actual ACR pull/run belongs to later approval and Task 7.5a.
- [ ] Cleanup only resources created by the run. Failures, missing dependencies and cleanup errors
  must not be recast as successful proof.
- [ ] Return the local harness results, generated inventory and unresolved items. Do not tick 7.6 or G6.

## Verification and return packet

After the named tooling exists, run from Claude's worktree with the repository-supported Java/Python
and local Docker. Implement the scripts with standard-library unittest discovery support:

~~~powershell
python -B -m unittest discover -s scripts/tests -p test_b1_candidate_evidence.py -v
python -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py -v
python -B -m unittest discover -s scripts/tests -p test_verify_b1_candidate_image.py -v
.\gradlew.bat :portfolio-service:candidateVerification :portfolio-service:prepareCandidateArtifact --rerun-tasks --no-build-cache
python -B scripts/check-b1-seed-version-callers.py
python -B scripts/tests/test_check_b1_seed_version_callers.py
python -B scripts/tests/test_master_plan_status_propagation.py
git diff --check
~~~

The Gradle command is a future command, not an existing task today. The runbook must define exact
local recipe/extraction/harness commands once implemented; include their output in the packet.
Do not pass --tests or -x to the candidate invocation. Use the complete fresh XML counts, not earlier
Wave 6/7 totals. Classify infrastructure failures separately without relaxing acceptance conditions.

Return:

1. Actual base/branch/head, clean/dirty status, changed-file list and scoped commits.
2. R1–R5 resolution table with exact file/line evidence. Identify pending policy decisions first.
3. Meaningful RED/GREEN tooling tests, fresh unit/IT totals and per-floor non-skipped counts.
4. One graph log, generated full manifest, discovery reconciliation, JAR/staged/extracted hashes,
   recipe/runtime/platform details, and clearly labelled local smoke results.
5. Source guard outputs, pinned B1 base/provenance, exhaustive writer inventory and unresolved SQL
   disposition. A parser passing tests does not make its real-source findings green.
6. Runbook and proposed future release packet. Keep registry/serving fields incomplete until those
   phases execute with authorization.
7. Proposed master-plan/ledger wording for Codex review, preserving completion boxes. If Codex is to
   edit those two governed files in Claude's worktree, include explicit worktree-owner permission.

Before publication, present the local diff/evidence and ask for the named implementation push/PR.
An authorized implementation PR should contain one declaration:
`Master-plan impact: updated — B1`, with matching master-plan and B1-ledger updates.
Do not bypass governance using a “none” declaration for a changed readiness/assignment state.

## Completion boundary

Local preparation is reviewable when tooling/tests and the runbook are complete, all findings have
explicit outcomes, and any remaining source-policy/SQL blocker is represented as a failure/pending
state. Codex may accept the tooling while R-C readiness remains NO-GO.

This bundle does not complete 7.3–7.11, GC.5, AM.1/AM.2 or Writer_Convergence. Their actual evidence
and owner decisions remain necessary. It does not dispatch Claude, merge any PR, alter the serving
R-B3 image, restore a schedule or authorize rollback. Return the bounded packet to Codex for review.
