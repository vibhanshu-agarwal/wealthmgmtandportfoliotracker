# B1 R-C Candidate — Checkpoint 1 (Task A) Handoff, Claude to Codex

**No publication action requested or needed.** This note reports local-preparation status only —
no push, PR, merge, image build, registry access, or deployment. Nothing here requires owner
authorization; it is a status report for Codex's continued review, per
[`CLAUDE_KICKOFF_B1_R_C_CANDIDATE_PREPARATION.md`](../../agent-instructions/CLAUDE_KICKOFF_B1_R_C_CANDIDATE_PREPARATION.md)
and [`AGENTS.md`](../../../AGENTS.md)'s Owner Approval Callouts.

**Goal recap:** Implement and locally validate Checkpoint 1 / Task A of the R-C candidate
preparation — the Task 7.4/7.5 verification graph, R2's floor correction, and discovery
reconciliation — per the
[architecture review](2026-09-03-b1-r-c-candidate-architecture-review.md) and kickoff.

## Status: Task A technically accepted for local preparation (Codex, this round)

Codex independently re-verified this round's deliverable across three review rounds and confirmed:
39/39 unit tests pass; all 89 real JUnit XML reports match their recorded hashes (760 tests, zero
failures/errors/skips); 69 discovered B1 test files reconcile without gaps; bootJar and staged JAR
match SHA-256 `4ee27c78…`; the later café.txt test-only correction is confined to that one test, with
the parser, Gradle configuration, and policy unchanged from the previously reviewed versions.

## What was built (Task A)

| File | Content |
|---|---|
| [`portfolio-service/build.gradle`](../../../portfolio-service/build.gradle) | `candidateVerification` (test + integrationTest, then bootJar), `candidateManifestValidation` (Exec, gates staging), `prepareCandidateArtifact` (Copy, depends on the validation task, not bootJar directly) |
| [`scripts/b1-candidate-policy.json`](../../../scripts/b1-candidate-policy.json) | Pinned B1-base commit (R4), corrected floor mapping (R2), discovery allowlist (empty, justified), R3 recorded unresolved |
| [`scripts/b1_candidate_evidence.py`](../../../scripts/b1_candidate_evidence.py) | Manifest generation, floor validation, discovery reconciliation, content-addressed freshness/source-identity checks, jar/stage hash binding; two entry points (`manifest-check` for the Gradle-internal gate, `evidence` for the full post-run bundle) sharing one pre-stage check path |
| [`scripts/tests/test_b1_candidate_evidence.py`](../../../scripts/tests/test_b1_candidate_evidence.py) | 39 tests, including explicit regressions for every issue this round's review found |
| [`docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md`](../../runbooks/B1_R_C_CANDIDATE_VERIFICATION.md) | Executable local procedure (Task A scope) + a stub for the separately-authorized release procedure (Task B/C) |

## R1–R5 resolution

| Finding | Status | Evidence |
|---|---|---|
| R1 (no executable candidate carrier) | **Split: graph/staging implemented, packaging open** | The verification-graph half (`candidateVerification`/`candidateManifestValidation`/`prepareCandidateArtifact`, staging the verified `bootJar` to `.candidate-artifacts/`) is implemented and exercised against a real Gradle run (below). R1's full scope also includes the Task 7.4 packaging steps (`Dockerfile.candidate`, the copy-only recipe, extracted-JAR hash check) — those are **not implemented**; they are Task B. |
| R2 (two floor patterns don't exist) | **Resolved, corrected during review** | `scripts/b1-candidate-policy.json:candidate_floor`; independently re-verified against source. Citation correction: `tasks.md:1147,1151` → `1164,1168`. **Precision correction (Codex, this round): the floor is 11 required class-pattern entries covering 10 conceptual suites** — the "Version read" row splits into two required entries (`PortfolioControllerTest` + `PortfolioServiceVersionMappingTest`) because no single existing class carries both halves of that row's citation. The runbook and policy file reflect this; any future summary should say "10 conceptual suites / 11 entries," not "10 entries." |
| R3 (persistent repair SQL) | **Unresolved by design** | Recorded in `scripts/b1-candidate-policy.json:unresolved`; `candidate_ready` is hard-coded `false` partly because of this. A source-only review cannot establish live database privileges or prove `repair_migrate_holdings` unreachable post-V20 — that is a separate owner-authorized live/operational decision, out of scope for local tooling. |
| R4 (GC.5 base commit) | **Split: base pinned, comparison guards pending** | The *base-commit input* is resolved: pinned `95fcb68dc7a47f99465354ec6d7b84137851389d`, proven an ancestor of both HEAD and the R-B3 cut via `git merge-base --is-ancestor`; rationale and both proofs recorded in `scripts/b1-candidate-policy.json:b1_base_commit`. GC.5's actual comparison policy and executable guards — the path allowlist/forbidden-path check and the content/AST symbol check over `<B1-base>..<cut-C>` (`check_b1_candidate_source.py`) — are **not implemented**; they are Task C. R4 resolves what GC.5 will diff *from*, not GC.5 itself. |
| R5 (packaged-image smoke is new work) | **Open — Task C scope** | Not implemented yet; no Docker image, registry digest, or HTTP smoke exists in this checkout. |

## Review corrections applied this checkpoint (for the record)

Three rounds of independent Codex review against real fixtures found and this checkpoint fixed, in
order:

1. **Round 1:** `git_status_digest` hashed status *text*, not content (missed edits to
   already-dirty files); `check_jar_stage` never checked bootJar/report freshness; discovery used
   `--diff-filter=AM` without `--no-renames` (dropped renamed test files); `--base-sha` accepted an
   unpinned override.
2. **Round 2:** `candidateManifestValidation` (the pre-staging gate) did no freshness checking at
   all; `candidate_ready` could report `true` from this script's narrow scope alone, without Task
   B/C evidence or R3's resolution; the content-identity parser used `.strip('"')` on porcelain-v1
   *text*, which does not decode git's octal escaping for non-ASCII filenames; the rename regression
   test's base commit predated the renamed file's own existence, so it degenerated into a plain add
   and did not actually exercise the bug.
3. **Round 3:** the corrected café.txt regression test still passed against the *old* buggy parser,
   because it only exercised a clean→dirty transition (caught by entry-presence alone, not by
   correct path parsing); corrected to make the file dirty *before* marking and edit it *again*
   afterward, with a self-check proving the naive parser resolves to a nonexistent path while the
   fixed parser resolves to the real file.

Every fix has a named regression test in `scripts/tests/test_b1_candidate_evidence.py`.

## Real-graph evidence (2026-09-03, LOCAL_DEV)

Captured once, kept separate from the later café.txt test-only correction per Codex's instruction —
the graph evidence below reflects the exact snapshot Gradle ran against; the test correction is a
later, distinct change that did not require another Gradle run.

- Source: `ebb96f3a6a22046ff5f3d449efcb146990b57ec9` (branch `claude/b1-r-c-candidate-preparation`),
  worktree dirty with Task A's own uncommitted tooling → `mode: LOCAL_DEV` (never `candidate_ready`,
  by construction).
- `candidateManifestValidation` printed `PASS` and gated `prepareCandidateArtifact` inside the real
  Gradle build — the pre-staging gate fix is proven end-to-end, not only unit-tested.
- 89 manifest classes (53 `test` + 36 `integrationTest`); 760 tests total; 0 skipped/failed/errored.
- 11 required class-pattern entries (10 conceptual suites) all satisfied.
- 69 B1-added/modified test files reconciled with zero discovery gaps.
- bootJar ↔ staged SHA-256 match: `4ee27c78c55da5c9edb34bbf926c3595249d82a42b9b95c369f125605c724dc6`.
- `graph_verification_status: PASS`, `problems: []`.
- `candidate_ready: false` — `candidate_ready_blocked_by`: Task B (image/registry evidence not
  implemented), Task C (writer-governance/HTTP-smoke evidence not implemented), R3 (unresolved).
- Full bundle: `.candidate-artifacts/evidence.json` (git-ignored, in Claude's worktree).

## Proposed governed-doc wording (for Codex's review — not applied)

Per the kickoff's file-ownership table, `.kiro/specs/portfolio-composition-contract/tasks.md` and
`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` are governed docs Claude does not edit directly.
Proposed, for Codex to apply or amend when it reviews this checkpoint:

- `tasks.md:1161-1172` (task 7.5's floor table) — replace the two literal patterns that do not exist
  in source with their accepted concrete carriers, and correct R2's own citation locator
  (`1147,1151` → `1164,1168`) wherever the finding text cites it:
  - Row "Asset discovery contract" (`tasks.md:1164`): `*AssetDiscoveryContractTest` →
    `*AssetCatalogControllerTest`.
  - Row "Version read" (`tasks.md:1168`): `*PortfolioVersionReadTest` → **both**
    `*PortfolioControllerTest` **and** `*PortfolioServiceVersionMappingTest` required together
    (mirrors how R2 itself already presents this correction).
  These are **requirement-text** corrections — fixing what the floor asks for, because the literal
  text is unsatisfiable as written — not a completion claim. No 7.4–7.6 checkbox moves at this
  checkpoint; ticking those remains gated on the full return packet (Task A+B+C evidence and R3's
  disposition), independent of whether this wording correction is applied now or later.
- Master plan Wave 7 row: no change proposed yet — Task A is local tooling, not a completed 7.4/7.5
  floor, and the row should not move until B/C evidence and the full return packet exist.

## Next step

Per Codex's direction: proceed to the assigned local Task B (candidate packaging + registry-digest
evidence interface) and Task C (source-governance/writer-inventory evidence + exact-digest HTTP
smoke harness) preparation. Evidence remains `LOCAL_DEV`; `candidate_ready: false`; R3 remains
unresolved. No push, PR, image build, registry access, or live-database check is authorized by this
note.
