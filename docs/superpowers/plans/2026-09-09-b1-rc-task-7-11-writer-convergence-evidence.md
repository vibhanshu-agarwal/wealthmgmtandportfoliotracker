# B1 R-C Task 7.11 Writer-Convergence Evidence Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close B1 R-C Task 7.11 with reviewable evidence for both P11g-1's transitional rollback
floor and P11g-2's post-activation Writer_Convergence floor, without changing application code or
performing a new production operation.

**Architecture:** Build a documentation-only proof by joining immutable, already-tracked evidence:
the historical R-0/R-A/R-B/R-B2/R-B3 release chain, the accepted Task 7.6 exhaustive writer
inventory, Task 7.7's serving G6 and R-B3r map, Task 7.9's exact-digest R-C serving proof, and Task
7.10's post-deploy GO. Bind every input to a Git commit/blob and distinguish the transitional
P11g-1 claim from the raised-floor P11g-2 claim. The result is a machine-readable JSON record plus a
human-readable runbook, followed by independent audit/review and only then ledger closure.

**Tech Stack:** Git and PowerShell for immutable-source inspection; JSON and Markdown evidence;
Python `unittest` for governed status-propagation checks.

**Spec:** `.kiro/specs/portfolio-composition-contract/{requirements.md,design.md,tasks.md}`

## Owner Approval Callout

No owner approval is requested to execute this local, documentation-only plan. It authorizes no
cloud or secret access, live production read or write, endpoint call or wake, workflow dispatch,
traffic/configuration change, deployment, rollback, push, pull request, merge, or publication.

If later publication is requested, present the final reviewed commit and exact file inventory for a
separate owner decision. If any fresh production observation or rollback becomes necessary, stop and
request separate explicit authorization before that action.

## Global Constraints

- Work only in `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-codex-b1rc711`
  on `codex/b1-rc-task-7-11-evidence` unless the owner explicitly changes the assignment.
- Make no Java, SQL, workflow, infrastructure, deployment, or runtime-configuration change.
- Treat the Task 7.6-7.10 production records as retained historical evidence, not as fresh live
  verification on 2026-09-09.
- Do not claim Writer_Convergence for R-B or R-B2. Their versionless transitional seed is allowed by
  P11g-1 and deliberately prevents a P11g-2 claim.
- Scope P11g-2 to the activated R-C artifact and every rollback artifact at or above the raised R-B3r
  floor. Rolling below R-B3r remains prohibited after R-C activation.
- Record that historical revision `portfolio-service--0000095` was purged by Container Apps Single
  revision mode. The safe rollback artifact is the immutable R-B3r digest; a future authorized
  rollback would create and verify a newly named revision serving exactly that digest.
- Preserve the Flyway exemption in Requirement 8.10. Historical migrations are not runtime writers,
  but their data-domain invariants remain applicable.
- Preserve the disclosed `ON DELETE CASCADE` structural risk. The current proof depends on the
  accepted finding that no reachable portfolio-delete caller exists; adding such a caller invalidates
  the inventory and requires recollection.
- Any drift in application source, writer routes/callers, seed/reset contracts, gateway provisioning,
  image identity, or rollback policy invalidates the corresponding proof and prevents ledger closure.
- Use canonical bytes from committed Git objects for input hashes. Do not hash checkout bytes whose
  line endings may have been transformed. A generated evidence file must not claim a self-hash.
- Keep the four-role topology if the owner chooses subagent-driven execution: Sol coordinates, Terra
  is the sole evidence writer, Luna performs a read-only process/tooling audit, and Astra performs the
  independent final review. No reviewer edits another role's work.

---

## Task 1: Freeze the proof inputs and construct the property matrix

**Files:**

- Read: `.kiro/specs/portfolio-composition-contract/design.md`
- Read: `.kiro/specs/portfolio-composition-contract/requirements.md`
- Read: `.kiro/specs/portfolio-composition-contract/tasks.md`
- Read: `docs/evidence/b1-r-c/candidate-evidence-checkpoint-20260908.json`
- Read: `docs/evidence/b1-r-c/task-7-7-completion-20260909.json`
- Read: `docs/evidence/b1-r-c/task-7-9-serving-proof-20260909.json`
- Read: `docs/evidence/b1-r-c/task-7-10-post-deploy-assessment-20260909.json`
- Read: `docs/runbooks/B1_R_B_G3_SERVING_PROOF.md`
- Read: `docs/runbooks/B1_R_B2_G2A_SERVING_PROOF.md`
- Read: `docs/runbooks/B1_TASK_6_6_G2B_SERVING_PROOF.md`
- Read: `docs/runbooks/B1_R_C_TASK_7_7_G4_AND_WRITER_MAP.md`
- Read: `docs/runbooks/B1_R_C_TASK_7_7_SERVING_EVIDENCE.md`
- Read: `docs/runbooks/B1_R_C_TASK_7_9_SERVING_PROOF.md`
- Read: `docs/runbooks/B1_R_C_TASK_7_10_POST_DEPLOY_STOP_GO.md`

- [ ] **Step 1: Confirm the isolated baseline.**

  Run:

  ```powershell
  git rev-parse --show-toplevel
  git status --short --branch
  git rev-parse HEAD
  git rev-parse origin/main
  git worktree list
  ```

  Expected: the top level is the Task 7.11 sibling worktree, the branch is
  `codex/b1-rc-task-7-11-evidence`, the starting tree is clean, and both baseline revisions are
  `62dfe938652372a35f0b87f940a892fba229fd37` before Task 7.11 changes.

- [ ] **Step 2: Extract the two property definitions and their different time ranges.**

  Build a working matrix with one row per release/floor:

  | Range | Property | Required predicates | Prohibited claim |
  |---|---|---|---|
  | R-B through pre-R-C | P11g-1 | duplicate-creating writers stay retired; signup provisioning stays present on every reachable rollback artifact | Writer_Convergence |
  | R-C activation and later rollback | P11g-2 | every reachable holdings writer participates in Portfolio_Version and preserves portfolio identity; floor includes R-B3r | validity below R-B3r |

  Cite design P11g-1/P11g-2, G6, the release table, and rollback-floor text; cite Requirements 8.1,
  8.4, and the 8.10 Flyway exemption.

- [ ] **Step 3: Reconfirm there is no application-source drift from the attested candidate cut.**

  Run:

  ```powershell
  git diff --name-status 8f1e8a36f8baa594efa8079190f87b42139fcf10..HEAD -- portfolio-service api-gateway
  git diff --name-status 8f1e8a36f8baa594efa8079190f87b42139fcf10..HEAD -- .github/workflows scripts
  git log --oneline --decorate 8f1e8a36f8baa594efa8079190f87b42139fcf10..HEAD -- portfolio-service api-gateway
  ```

  Expected: no `portfolio-service` or `api-gateway` application change. Record any tooling-only
  drift separately; never use it to imply application drift. If application drift appears, stop:
  Task 7.11 needs a fresh exhaustive writer inventory and cannot use this plan's immutable-chain
  shortcut.

- [ ] **Step 4: Bind every referenced input to canonical committed bytes.**

  For each input artifact, record:

  - repository commit containing the accepted record;
  - `git rev-parse <commit>:<path>` blob object id;
  - SHA-256 calculated over `git cat-file blob <blob-id>` bytes;
  - the release source commit, manifest digest, revision name, and review verdict carried by the
    record.

  Use a small read-only Python invocation with `subprocess.check_output(["git", "cat-file",
  "blob", blob])` and `hashlib.sha256(raw)` so checkout newline conversion cannot alter the input.
  Do not add a new helper script to the repository.

- [ ] **Step 5: Construct the complete release/property evidence matrix.**

  The matrix must show, with an evidence reference for every cell:

  - R-0 retired both public legacy writers before the uniqueness migration;
  - R-A retained signup provisioning;
  - R-B/R-B2 remained inside P11g-1, with the versionless seed explicitly disclosed;
  - R-B3/R-B3r introduced the version-required, identity-preserving seed and raised-floor artifact;
  - Task 7.6 inventoried every writer for the exact R-C candidate cut;
  - Task 7.7 established fresh G0a/G2a/G2b plus the accepted writer map, yielding G6;
  - Task 7.9 proved the exact candidate manifest serves as
    `portfolio-service--0000096` at 100% and exercised one authenticated same-state composition
    operation;
  - Task 7.10 recorded owner GO without rollback; and
  - current merged source has no application drift from the attested cut.

  The resulting conclusion must be conditional and time-scoped: P11g-1 holds for its transitional
  range; P11g-2 holds for the activated exact R-C artifact and rollback artifacts at or above R-B3r,
  subject to the invalidation rules above.

---

## Task 2: Author the machine-readable Task 7.11 evidence record

**Files:**

- Create: `docs/evidence/b1-r-c/task-7-11-writer-convergence-floor-20260909.json`

- [ ] **Step 1: Create the JSON skeleton.**

  Include these top-level sections:

  ```json
  {
    "schemaVersion": "b1-task-7-11-writer-convergence-floor/1",
    "generatedAtUtc": "<actual UTC timestamp>",
    "scope": {},
    "sourceBinding": {},
    "inputEvidence": [],
    "p11g1TransitionalFloor": {},
    "p11g2WriterConvergenceFloor": {},
    "writerInventoryDisposition": [],
    "rollbackFloor": {},
    "invalidationConditions": [],
    "limitations": [],
    "review": {},
    "verdict": {}
  }
  ```

  Set review fields to `PENDING` and Task 7.11 to `PENDING_REVIEW`; do not pre-accept the writer's
  own packet.

- [ ] **Step 2: Encode P11g-1 independently.**

  Record the eligible artifact range and evidence for both predicates:

  1. neither duplicate-creating public path returns; and
  2. signup provisioning remains present.

  Explicitly record the versionless R-B/R-B2 seed as permitted transitional behavior and set
  `writerConvergenceClaimed` to `false` for this section.

- [ ] **Step 3: Encode P11g-2 as a universal writer disposition.**

  Transcribe the accepted Task 7.6/7.7 inventory without silently narrowing it to three HTTP paths.
  Each candidate must be classified as one of:

  - effective runtime writer converging on `HoldingReplacementService` and Portfolio_Version;
  - delegating service/controller/DTO with no independent DML;
  - unreachable/dead method with its call-site result;
  - historical Flyway writer exempt under Requirement 8.10;
  - database cascade with its no-current-delete-caller limitation; or
  - non-writer diagnostic/query path.

  Bind the R-C source commit `8f1e8a36f8baa594efa8079190f87b42139fcf10`, candidate JAR SHA-256
  `441d252939d7333dca134b9cc1a5f6a0632cc274a53f410671212c543a85cda4`, candidate manifest
  `wealthprodacr.azurecr.io/portfolio-service@sha256:1cf372a39d17709f74aba427259548a531e3e1ba04dc64ec7750369bb8e82126`,
  and serving revision `portfolio-service--0000096`.

- [ ] **Step 4: Encode the raised rollback floor precisely.**

  Record R-B3r source `97b83e52bcbfface4377cefd69ebb111270cb21e`, immutable digest
  `sha256:fa060bf054b9c108b8b59d9e9b27845d6b707f40040a7dcba16411db7f0e8552`, and historical revision
  `portfolio-service--0000095`. State that the revision object was purged, the digest remains the
  rollback identity, and any future rollback requires new explicit owner authorization plus proof
  that a newly named revision serves exactly that digest. Set `rollbackBelowFloorPermitted` to
  `false`.

- [ ] **Step 5: Record limitations and invalidation conditions.**

  At minimum include:

  - no fresh live query was run for Task 7.11;
  - retained Task 7.10 telemetry is historical collector-reported evidence;
  - no new write/race proof is claimed from Task 7.9's same-state PUT;
  - the Flyway exemption and `ON DELETE CASCADE` limitation;
  - all source/route/caller/seed/provisioning/image/floor drift conditions;
  - no future-artifact or below-floor guarantee; and
  - no deployment, rollback, publication, or broader backlog closure.

- [ ] **Step 6: Validate the JSON.**

  Run:

  ```powershell
  & 'C:\Users\pc\AppData\Local\Python\pythoncore-3.14-64\python.exe' -c "import json,pathlib; p=pathlib.Path(r'docs/evidence/b1-r-c/task-7-11-writer-convergence-floor-20260909.json'); json.loads(p.read_text(encoding='utf-8')); print('JSON PASS')"
  ```

  Expected: `JSON PASS`.

---

## Task 3: Author the human-readable property proof

**Files:**

- Create: `docs/runbooks/B1_R_C_TASK_7_11_WRITER_CONVERGENCE_FLOOR.md`

- [ ] **Step 1: Put the authorization boundary first.**

  State that this is a local evidence consolidation. It performs no new production operation and
  authorizes no rollback or publication. Name the exact JSON companion record.

- [ ] **Step 2: Explain the split property without collapsing its phases.**

  Give separate verdict tables for P11g-1 and P11g-2. P11g-1 must say why R-B/R-B2 can pass while
  Writer_Convergence is false. P11g-2 must explain why the universal writer inventory, not only
  G0a/G2a/G2b, establishes convergence.

- [ ] **Step 3: Present the immutable chain.**

  Include compact tables for:

  - release/artifact, source commit, serving revision/digest, property role, and evidence record;
  - every writer-inventory disposition; and
  - every input evidence file's commit/blob/SHA-256 binding.

  Distinguish historical serving facts from the local no-application-drift check.

- [ ] **Step 4: State the rollback and invalidation rules.**

  Make the raised R-B3r floor prominent. Explain that the purged `0000095` name is historical and
  cannot itself be targeted; the exact digest is the rollback artifact. List the conditions that
  force the Task 7.11 proof to be recollected.

- [ ] **Step 5: End with a decision-ready, non-self-approved verdict.**

  The writer may recommend `ACCEPT`, but the status remains `PENDING_REVIEW`. The runbook must not
  check Task 7.11 or claim independent acceptance yet.

- [ ] **Step 6: Check links and formatting.**

  Run a bounded link-resolution check over the new Markdown file, then:

  ```powershell
  git diff --check
  git status --short
  ```

  Expected: all relative repository links resolve, `git diff --check` exits 0, and only the planned
  Task 7.11 files plus this plan are changed.

- [ ] **Step 7: Commit the review candidate locally.**

  ```powershell
  git add docs/superpowers/plans/2026-09-09-b1-rc-task-7-11-writer-convergence-evidence.md docs/evidence/b1-r-c/task-7-11-writer-convergence-floor-20260909.json docs/runbooks/B1_R_C_TASK_7_11_WRITER_CONVERGENCE_FLOOR.md
  git commit -m "docs: prepare Task 7.11 convergence evidence"
  ```

  Record the exact commit for reviewer binding. Do not push.

---

## Task 4: Perform independent audit and final review

**Files:**

- Review: the Task 3 commit and every referenced immutable input
- Modify: none during each review pass

- [ ] **Step 1: Luna performs the read-only process/tooling audit.**

  Verify:

  - canonical committed-byte hashes reproduce;
  - all links and JSON fields resolve;
  - release/source/digest/revision identities agree across inputs;
  - P11g-1 never claims Writer_Convergence;
  - P11g-2 covers the exhaustive inventory and raised floor;
  - limitations and invalidation conditions are complete; and
  - no secret, credential, token, raw customer payload, or unsupported live claim is present.

  Return findings by severity and an `ACCEPT`/`REJECT` recommendation. Do not edit files.

- [ ] **Step 2: Terra applies accepted corrections, if any.**

  The sole writer resolves each finding with an evidence-backed change or written technical
  disposition. Re-run Task 2 Step 6 and Task 3 Step 6, then commit the corrections locally. Do not
  push.

- [ ] **Step 3: Astra performs the independent final whole-packet review.**

  Review the exact final commit, not an earlier diff. Reproduce the property matrix from primary
  spec text and input evidence. Explicitly decide whether both P11g-1 and P11g-2 are supported and
  whether the evidence is sufficient to check Task 7.11. Return severity-counted findings and final
  `ACCEPT`/`REJECT`. Do not edit files.

- [ ] **Step 4: Stop on any unresolved finding.**

  Do not close the ledger while any Critical, Important, or Minor finding remains. Documentation
  corrections return to Terra and both relevant verification/review steps repeat against a new exact
  commit.

---

## Task 5: Close Task 7.11 and propagate governed status

**Files:**

- Modify: `docs/evidence/b1-r-c/task-7-11-writer-convergence-floor-20260909.json`
- Modify: `docs/runbooks/B1_R_C_TASK_7_11_WRITER_CONVERGENCE_FLOOR.md`
- Modify: `.kiro/specs/portfolio-composition-contract/tasks.md`
- Modify: `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`

- [ ] **Step 1: Bind the independent acceptance.**

  Only after Astra accepts the exact review commit, update the JSON/runbook with reviewer identity,
  reviewed commit, UTC review time, zero unresolved finding counts, and `ACCEPT`. Avoid implying
  that the reviewer reviewed the later ledger-only commit; describe the boundary exactly.

- [ ] **Step 2: Check Task 7.11 and update the local status narratives.**

  Change only Task 7.11's checkbox and the directly affected B1 status summaries. State:

  - Tasks 7.1-7.11 are locally complete;
  - P11g-1 is established for the transitional range;
  - Writer_Convergence/P11g-2 is established for the activated R-C artifact and the at-or-above-R-B3r
    rollback floor;
  - publication remains separate until explicitly authorized; and
  - no additional production operation or rollback occurred during Task 7.11.

  Do not close AM.1/AM.2, B2 work, production E2E, or any unrelated backlog item.

- [ ] **Step 3: Run the final verification suite.**

  ```powershell
  & 'C:\Users\pc\AppData\Local\Python\pythoncore-3.14-64\python.exe' -c "import json,pathlib; json.loads(pathlib.Path(r'docs/evidence/b1-r-c/task-7-11-writer-convergence-floor-20260909.json').read_text(encoding='utf-8')); print('JSON PASS')"
  & 'C:\Users\pc\AppData\Local\Python\pythoncore-3.14-64\python.exe' -B -m unittest scripts.tests.test_master_plan_status_propagation
  git diff --check
  git status --short
  ```

  Expected: JSON parses, all 33 status-propagation tests pass, `git diff --check` exits 0, and only
  the five enumerated Task 7.11 files differ from the starting baseline.

- [ ] **Step 4: Inspect the final diff and secret boundary.**

  Review `git diff --stat`, `git diff --name-status`, and the complete diff. Search the Task 7.11
  files for credential-bearing URLs, tokens, passwords, secrets, and raw customer payloads. Exact
  resource/revision/digest metadata may remain only when already present in the authorized tracked
  evidence and necessary for the immutable proof.

- [ ] **Step 5: Commit the local closeout.**

  ```powershell
  git add .kiro/specs/portfolio-composition-contract/tasks.md docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md docs/evidence/b1-r-c/task-7-11-writer-convergence-floor-20260909.json docs/runbooks/B1_R_C_TASK_7_11_WRITER_CONVERGENCE_FLOOR.md
  git commit -m "docs: close Task 7.11 writer convergence"
  ```

  Re-run the final verification suite against the committed tree and record its output in the handoff.
  Do not push, open a pull request, merge, deploy, or roll back without a separate explicit owner
  authorization.

## Completion Criteria

Task 7.11 is locally complete only when all of the following hold:

- the JSON and runbook independently prove the two phase-specific properties;
- every proof input is immutably bound to committed bytes and its release identity;
- the exhaustive writer inventory remains applicable to the exact serving R-C source;
- the raised R-B3r rollback floor and purge nuance are explicit;
- limitations and invalidation conditions prevent future or below-floor overclaim;
- Luna's audit and Astra's independent final review have no unresolved findings;
- Task 7.11 alone is checked and the master plan is consistently propagated;
- the final validation suite is green on the committed tree; and
- all publication, deployment, rollback, cloud, workflow, and broader-scope gates remain closed.
