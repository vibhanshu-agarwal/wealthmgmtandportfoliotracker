# FABLE review — Task 8.9 post-merge docs candidate (Codex worktree)

Reviewer: independent Fable 5.1 subagent (dispatched 2026-09-13; orchestrator did not review or pre-digest).
Candidate: C:/worktrees/wealthmgmtandportfoliotracker-worktrees/wealthmgmtandportfoliotracker-codex-task-8-9-postmerge-docs/ — HEAD 80b881b5c461e1bebb210ebe1db4b911cf6a336d, branch codex/task-8-9-postmerge-docs, uncommitted.
CHECKPOINT entry: Claude worktree docs/superpowers/CHECKPOINT.md, hash-object 3b7542ed3606d863d17d81651c77d289b5ef7950 (file is gitignored per docs/superpowers/.gitignore:4; nothing staged).

**Verdict: no blocking findings; 3 Major, 7 Minor. Findings only — Codex holds acceptance.**

## Provenance (pinned)

| File | Candidate hash-object | Baseline rev-parse 80b881b5: |
|---|---|---|
| docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md | 7e75cc44613200f54936452787308efc62cb9fc4 | deb316fec1750a461759b6ccbe2ef126cbbd4144 (matches stated) |
| .kiro/specs/asset-picker-composition/tasks.md | b6065a43df0ebf4878fed466b970ab717c977976 | 98cfc82ec4820bb32b72e5ae46a15b21b75018bd (matches stated) |
| docs/todos/backlog/task-8-9-wake-preflight-hardening/README.md | a9e2ae909ef6bb2150d372b57bd6ce9de6c62502 | new |

Also read against: rehearsal-20260911.json da9ecfda, rehearsal-20260912.json 27ec32b5, run-a-attempt-20260912.md 1ac71b6b, run-a-attempt-20260913.md de9b9c36, run-a2-operator-transcript-20260913.txt 7e82d2bb, readiness packet d4c9fec5 (§0), wrapper 97c79f19, stub_curl.cmd 8d8f2f7f, test_master_plan_status_propagation.py 862e4558, check-spec-references.py 3b3762a4.

## Raw tool output for the five claims

Scope (verified first):
```
$ git -C <codex> status --porcelain
 M .kiro/specs/asset-picker-composition/tasks.md
 M docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md
?? docs/todos/backlog/task-8-9-wake-preflight-hardening/
$ git -C <codex> diff --stat
 .kiro/specs/asset-picker-composition/tasks.md | 18 +++++++++++++--
 docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md    | 33 +++++++++++++++++++++++++--
 2 files changed, 47 insertions(+), 4 deletions(-)
$ git -C <codex> diff --check ; echo exit=$?
exit=0            (only "LF will be replaced by CRLF" autocrlf warnings, no check output)
```
Exactly three paths; one file inside the new directory.

PR #268 facts (all local git, no network): parents of 80b881b5 = a4fa8d07… 400ac3c8…; rev-parse 80b881b5^{tree} = rev-parse 400ac3c8^{tree} = e4e7c8ad9fbf9409e5cb1b7063bc963f44e166c7; git diff --stat 400ac3c8 80b881b5 → empty; git diff --name-status a4fa8d07 80b881b5 → exactly scripts/run_task_8_9_preflight.ps1, scripts/tests/stub_curl.cmd, scripts/tests/test_run_task_8_9_preflight.ps1, 3 files changed, 1390 insertions(+), 273 deletions(-); committer date 2026-09-13 20:39:08 +0530 = 15:09:08Z.

Python runs (from the Claude worktree, PYTHONDONTWRITEBYTECODE=1 python -B, pointed at candidate files):
```
$ python -B scripts/check-spec-references.py --self-test
... 11/11 reference-detection PASS, 5/5 sub-clause PASS, 4/4 coverage-symmetry PASS
self-test: PASS   exit=0

$ python -B scripts/check-spec-references.py <codex>/.kiro/.../tasks.md --against <codex>/.kiro/.../requirements.md --coverage
contiguous: 0 requirements {}
dangling: none (0 references checked, 0 with sub-clauses)
  0/0 criteria cited by at least one task      exit=0
   -> vacuous for this spec; identical 0/0 on baseline copies extracted to scratch. Pre-existing, not evidence.

$ python -B <codex>/scripts/tests/test_master_plan_status_propagation.py -v
Ran 33 tests in 0.010s
OK   exit=0
```
The suite resolves REPO from __file__, so this run tests the candidate's own files.

Path existence (extracted from added diff lines + README): all 10 EXISTS — ../evidence/b2-task-8-8/deployment-completion-20260910.json, ../evidence/b2-task-8-9/{deployment-provenance-20260911,rehearsal-20260910,rehearsal-20260911}.json, ../superpowers/plans/2026-09-06-b2-wave8-decision-record.md, ../todos/backlog/task-8-9-wake-preflight-hardening/README.md (from both the plan and tasks.md), scripts/run_task_8_9_preflight.ps1, scripts/tests/stub_curl.cmd, scripts/tests/test_run_task_8_9_preflight.ps1. README contains 0 links.

Wrapper limits vs. merged source 97c79f19: five-probe for L221; --max-time 30 + -q --noproxy '*' -sS L229; retry only 503 / exit-28+000 L295–298; exit 3 L321/325/353; '--mode', 'preflight' L640. The candidate's limits are accurate (one wording exception, Minor 5).

## Findings

### Blocking — none
Task 8.9 reads OPEN/NON-GO in every changed passage (plan L50 "LIVE PROOF OPEN", L675 "still has no live result"; tasks.md L44 "not a wake or Task 8.9 evidence"; README L6–7, L42). Decision 1 (≤5 probes + read-only preflight) is stated with execute mode / credentials / mutation explicitly excluded (plan L68–71; tasks.md L46–49; README L37–38). The gates are not bundled. Nothing reads as progress toward GO.

### Major
1. Reconciliation skips PRs #265/#266/#267 and both consumed wakes, then introduces "attempt 3" (criterion d). tasks.md b6065a43 L3 claims "reconciled through PR #268"; plan 7e75cc44 L68 and tasks.md L46 say "before Run A attempt 3". Neither file mentions attempt 1 (09-12: 503 after 56 s, verifier run anyway, non_go), attempt 2 (09-13: PR #266 wrapper, 503, exit 3, verifier never started, gateway at zero replicas), or PRs #265/#266/#267 — grep -nE '20260912|20260913|attempt|run-a|#265|#266|#267' hits only the two new "attempt 3" lines. The candidate edited the Wave 8 row (plan L675) yet left it anchored to the 09-11 record and its next step as "A bounded gateway wake requires a separate owner decision before rehearsal can continue past the exec probe" — the pre-attempt-1 world. And L675 "it still has no live result" is true only of the #268 wrapper; the #266 predecessor did run live on 09-13 and stopped on a 503 — a reader concludes no wrapper has ever run. Pre-existing gap at baseline (deb316fe is silent too), but this candidate's purpose is the post-merge reconciliation and its header asserts it.
2. The three Minors are not individually marked non-blocking (criterion c). README a9e2ae90 L3–4 and L6–7 carry one collective statement. Item 1 (L22–24) and item 2 (L25–28) have per-item rationales; item 3 (L29–31) has none beyond "without changing invocation behavior". No item carries an explicit per-item severity/non-blocking label.
3. The README's provenance is off-repo and unlinked. **Source:** Fable review round 2 ACCEPT of accepted head 400ac3c8 (L8–10) points at nothing in the repository (grep for 400ac3c8 across docs/ in both worktrees hits only the two candidate files; no brainstorm archive exists in either). Reviewer verified each Minor independently against merged source: #3 = wrapper L188–189 comment "'*' is quoted so PowerShell passes the literal character"; #1 = stub L85–86 single-backslash strip; #2 = denylist at test L792/L1122/L1137/L1141 with no positive allowlist. The Minors are real; the entry just gives a reader no way to check them. Cite those lines and PR #268.

### Minor
4. Plan L64–66: "223/223 … suite and PR CI were green; Qodana … neutral/skipped"; "scratch evidence is indexed by the Codex handoff". None exists in-repo (git grep 223/223 → nothing; no 09-13 handoff tracked). [unverified] by the reviewer; the plan is citing an off-repo index.
5. Plan L61 "timeout/transport 28/000" over-states the retry set — DNS/connect/TLS failures stop with exit 3 (wrapper L309–311). tasks.md L43 ("curl 28/000") is accurate; align the plan to it.
6. Plan L675: link text "cold-start preflight wrapper" targets the backlog README, not the wrapper or PR #268.
7. README metadata uses **Blocks:**/**Source:**; the dominant sibling convention is **Status:**/**Owner:**/**Tracked in:** (Blocks: appears once, Source: never). Directory name and section structure (…/## Non-claims) are within the sibling range. There are 21 existing siblings, not 20 as stated in the brief.
8. Criterion (b): passes, but only by exclusion list — the candidate never names execute-mode/credentials as a second, separate owner decision (packet §0). One clause would make the sequencing explicit rather than inferable.
9. Claim 4 is true and nearly irrelevant. The only master-plan test (test_master_plan_does_not_claim_unmerged_or_docs_only_for_this_guard, L291–301) checks the first 20 lines for runtime|program-state code baseline, forbids "this update is documentation-only", and asserts two substrings. Covered changed lines: plan L3–4 only. Not covered: plan L50–75, L675, all of tasks.md, the README. No test reads tasks.md or any backlog file.
10. Housekeeping: the Claude worktree's local main is a4fa8d07 (stale vs 80b881b5). Not a candidate issue.

Unverified: GitHub's own merge timestamp (consistent with the local committer date, not fetched); 223/223; Qodana; PR CI green; the Codex handoff; the Fable round-2 review itself.

### Open questions for CODEX
1. Will this candidate record PRs #265/#267 (attempts 1 and 2, both 503) in the ledger header, the "Last verified" chain, and the Wave 8 row — or is that deliberately deferred? If deferred, should the header still claim "reconciled through PR #268"?
2. Will each of the three Minors get an explicit per-item non-blocking label plus in-repo citations (wrapper L188–189, stub L85–86, test denylist lines, PR #268), with **Tracked in:** replacing **Source:**?
3. Where do the "223/223", "Qodana neutral", and "Codex handoff index" claims get anchored in-repo — or should they be cut from the master plan?

## Housekeeping confirmations
- Candidate worktree untouched: final git -C <codex> status --porcelain shows the same three entries as at the start (re-confirmed by the orchestrator after the agent finished); no __pycache__ under its scripts/; the three candidate fingerprints unchanged (7e75cc44, b6065a43, a9e2ae90).
- CHECKPOINT entry written (append-only, new topic from template, not committed): Claude worktree docs/superpowers/CHECKPOINT.md, hash-object 3b7542ed3606d863d17d81651c77d289b5ef7950, 50 lines; gitignored via docs/superpowers/.gitignore:4, nothing staged.
