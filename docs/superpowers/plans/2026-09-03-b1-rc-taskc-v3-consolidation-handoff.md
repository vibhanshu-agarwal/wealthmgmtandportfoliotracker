# B1 R-C Task C (source-governance guard) — session handoff

Date: 2026-09-03. Contract: `gc5-contract/3`. Status: **implemented, all local tests green, awaiting
Codex re-review; NOT yet accepted.** Real repo remains `BLOCKED`.

## Where the work lives

- Worktree: `D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude`
- Branch: `claude/b1-r-c-candidate-preparation`
- Cut SHA: `ebb96f3a6a22046ff5f3d449efcb146990b57ec9`  |  B1-base: `95fcb68dc7a47f99465354ec6d7b84137851389d`
- **Everything is UNTRACKED / local. Nothing is committed or pushed.** No authorization is pending or
  needed to resume.
- Note: prior sessions ran the shell from the sibling `...-intellij` worktree but edited the
  `-claude` worktree via absolute paths. Work in `-claude`.

### Files (all untracked)
- `scripts/check_b1_candidate_source.py` (~3331 lines) — the guard.
- `scripts/tests/test_check_b1_candidate_source.py` (~2105 lines, **145 tests**).
- `scripts/b1-candidate-policy.json` — the policy.
- `docs/runbooks/B1_R_C_CANDIDATE_VERIFICATION.md` — runbook (kept in sync with the contract).
- Sibling tooling from earlier tasks (already accepted): `scripts/b1_candidate_evidence.py`,
  `scripts/verify_b1_candidate_image.py`, and their tests (`test_b1_candidate_evidence.py` 39,
  `test_verify_b1_candidate_image.py` 34).

### Running the tests
```
cd D:/Projects/Development/Java/Spring/wealthmgmtandportfoliotracker-claude
python -B -m unittest discover -s scripts/tests -p test_check_b1_candidate_source.py
```
218 total green (145 guard + 39 evidence + 34 image). The guard suite takes ~3.5 min (real-repo
smoke + a subprocess CANDIDATE run), so run it in the background and poll — a 2-min foreground call
times out. Use the existing complete Python (`python`) with `-B`.

## What this tool is

Task C GC.5 source-governance guard + writer-inventory re-check. It evidences **source governance
only** — it is NOT a release verdict. `source_governance_status` (PASS/BLOCKED) drives the exit code;
`candidate_ready` is **always false** (this tool cannot establish the exact-digest HTTP smoke or Task
B's registry portion). Contract details are documented at length in the runbook and the module
docstring.

### Contract v3 architecture (what to preserve)
- **Envelopes (Tier 1):** per-deployable source snapshots; roots **derived from the Gradle build
  graph**; git-backed validation (reviewed_commit must exist, be an ancestor, and recompute);
  **content-addressed `envelope_record_id` folds ALL claim-bearing fields** (attestation, reviewer,
  delta, affected_claims, previous link); **renewal lifecycle** (revision>1 names
  `previous_envelope_record_id`, `reviewed_delta` validated against predecessor→this-review R_old→R_new,
  R_new→cut must be unchanged, `affected_claims` explicit).
- **Tier 0 fingerprint:** normalized statement + enclosing-method digest + resolved receiver
  persistence type + entity mapping digest. Closure/caller lists are `review_aids`, NOT validity.
- **ONE validation path** (`validate_claim_record`) for writer/setter/SQL/effect-resolution; path
  exceptions share the same provenance/status rules. It reconstructs the subject at the claim's own
  `reviewed_commit` (a claim cannot predate its code) and the Tier-0 fingerprint is mandatory
  (setters included — never `None`).
- **Deployables enumerated from the tree** (`governed_modules`, fail-closed): a module with any
  RELEVANT or UNRESOLVED writer is governed regardless of policy. Real repo = **4 governed**
  (api-gateway, portfolio-service, market-data-service, insight-service).
- **Effects operation-specific:** `ON DELETE CASCADE` indirect targets attach only to DELETE/TRUNCATE.
- **Automatic effect clearance inactive** unless `effect_based_automatic_clearance` is non-empty;
  UNRELATED writes in governed deployables still need explicit review.
- **Evidence is a `--run-input` manifest**, never in the committed policy (avoids build→commit→rebuild
  cycle); full Task A/B semantic validation + durable input hashes.
- **Per-usage persistence coverage:** each call on a persistence receiver must be a recognized write,
  a recognized read-only method, or `UNSUPPORTED`.
- **CANDIDATE mode:** byte-identical committed analyzer+policy + clean working tree. A clean CANDIDATE
  run is exercisable in a disposable committed repo (verifier copies as `TEST_CORPUS`, fixture policy
  pinned to base, evidence as run inputs) — `CandidateEndToEndTests` does exactly this via the real
  CLI (positive + negative).

## Review history (Codex is the reviewer)

Four review rounds this session; each found subtle false-passes. **Expect another round.**
1. R1 (6): mixed snapshots, incomplete enumeration, filename-trust, quoted paths, path policy,
   content coverage.
2. R2 (6): unsupported→PASS, unresolved-clears, envelope-not-global, incomplete roots, renewal rebind,
   evidence binding → produced contract v3 + envelope model.
3. R3 "independent review" (6 P1): evidence not validated / candidate_ready, per-run binding cycle,
   review provenance, envelope immutability, coverage gaps, auto-clearance active.
4. R4 "consolidation review" (5 P1, three WPs): renewal delta bug, evidence semantics, claim-vs-subject,
   record identity, per-usage. **All fixed this round** with negatives + positive controls
   (`ConsolidationReviewNegatives` 13, `CandidateEndToEndTests` 2, plus `IndependentReviewNegatives` 10).

Reviewer artifacts (files, not in repo):
- Addendum: `C:/Users/pc/Downloads/B1_RC_TaskC_addendum_evidence_contract.md`
- Reviews: `C:/Users/pc/.codex/visualizations/2026/09/03/01a065fd-9ffa-73a0-a909-91d88e16e5a2/`
  `B1_RC_TaskC_v3_independent_review.md` and `B1_RC_TaskC_consolidation_review.md`

## Hard rules (learned this session)

- **Do NOT author envelope attestations or dispositions to make the real repo green.** That is an
  owner/human review act. The signup disposition (`insertPortfolio`) was REMOVED because v3 requires
  a git-validated envelope record that must not be fabricated; `insertPortfolio` is now honestly
  UNREVIEWED.
- **Historical Task A/B evidence in `.candidate-artifacts/` must stay byte-for-byte unchanged** (it
  describes its own run). Verify with `git status --porcelain .candidate-artifacts/` (empty).
- No commits/pushes; live-DB/registry/deploy/PR/merge unauthorized. **R3** (V17 `repair_migrate_holdings`
  still callable) needs a separate owner-authorized operational check and remains blocking.
- CI wiring is out of bundle (kickoff line 81); the `fetch-depth: 0` requirement is recorded in the
  runbook for a later proposal.
- The three tracked `M` files (`.dockerignore`, `.gitignore`, `portfolio-service/build.gradle`) were
  dirty at session start — NOT ours; leave them.
- **Editing gotcha:** bash heredocs repeatedly corrupted Python regex/docstrings (literal backspace
  bytes, stray newlines). Prefer Write-to-scratch + Python splice, or the Edit tool, over heredocs for
  code containing backslashes.

## Open / next

1. Await Codex re-review of this consolidation round; be ready for another round of subtle findings.
2. Still unimplemented (out of this tool's scope): exact-digest HTTP smoke harness (Task C), Task B
   registry/release portion. R3 operational closure.
3. Real-repo envelope records + dispositions require owner review — do not fabricate.
4. Real repo currently: `BLOCKED`, `candidate_ready: false`, 4 governed (all UNREVIEWED — no records),
   ~490 findings (139 content CONFIRMED, 84 path CONFIRMED + 131 path UNREVIEWED, 37 persistence-usage
   UNSUPPORTED, writer-inventory UNRESOLVED/UNREVIEWED, R3). This is the intended honest state.
