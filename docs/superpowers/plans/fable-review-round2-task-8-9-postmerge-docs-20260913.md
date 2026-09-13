# FABLE review, round 2 — Task 8.9 post-merge docs candidate (Codex worktree)

Reviewer: Fable 5.1 (interactive session, 2026-09-13). Independent re-review of the corrected
candidate; every claim below was re-verified against the repository before the round-1 report was
consulted for disposition.
Candidate: C:/worktrees/wealthmgmtandportfoliotracker-worktrees/wealthmgmtandportfoliotracker-codex-task-8-9-postmerge-docs/ —
HEAD 80b881b5c461e1bebb210ebe1db4b911cf6a336d, branch codex/task-8-9-postmerge-docs, uncommitted.

**Verdict: ACCEPT. 0 Blocking, 0 Major, 2 Minor (both wording; neither changes any bound or gate),
1 nit. All three round-1 Majors and Minors 4–9 are closed. Codex holds acceptance; nothing here
authorizes a Production action.**

## Provenance (pinned, `git hash-object`)

| File | Candidate | Baseline @ 80b881b5 |
|---|---|---|
| docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md | 284ede2d | deb316fe |
| .kiro/specs/asset-picker-composition/tasks.md | 1150afac | 98cfc82e |
| docs/todos/backlog/task-8-9-wake-preflight-hardening/README.md | 620c201f | new |
| docs/todos/backlog/asset-picker-spec-reference-guard-zero-coverage/README.md | 4b5acadc | new |
| docs/superpowers/plans/fable-review-task-8-9-postmerge-docs-20260913.md | 73b9e933 | new |

Read against (merged, unchanged): wrapper 97c79f19; stub_curl.cmd 8d8f2f7f;
test_run_task_8_9_preflight.ps1 d6c3e1b4; run-a-attempt-20260912.md 1ac71b6b;
run-a-attempt-20260913.md de9b9c36; run-a2-operator-transcript-20260913.txt 7e82d2bb;
readiness packet d4c9fec5.

## Scope

```
$ git status --short
 M .kiro/specs/asset-picker-composition/tasks.md
 M docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md
?? docs/superpowers/plans/fable-review-task-8-9-postmerge-docs-20260913.md
?? docs/todos/backlog/asset-picker-spec-reference-guard-zero-coverage/
?? docs/todos/backlog/task-8-9-wake-preflight-hardening/
$ git diff --stat        -> 2 files changed, 74 insertions(+), 4 deletions(-)
$ git diff --check       -> exit 0 (autocrlf warnings only)
```
Exactly five files. Status identical after all runs below; no `__pycache__` written
(PYTHONDONTWRITEBYTECODE=1, `python -B`).

## Claims verified against primary evidence (local git only; no network)

- PR #268: parents a4fa8d07 + 400ac3c8; `rev-parse 80b881b5^{tree}` = `400ac3c8^{tree}` =
  e4e7c8ad…; `git diff --stat 400ac3c8 80b881b5` empty; `a4fa8d07..80b881b5` = exactly the three
  script/test paths, +1390/-273; committer `GitHub <noreply@github.com>` 2026-09-13T20:39:08+05:30
  = 15:09:08Z. The plan's "GitHub reports … 15:09:08Z" rests on that committer stamp, not a fetch.
- PR #265 = e73ab8e9 (2026-09-13 06:38Z), #266 = 6b9c70f6 (06:06Z), #267 = a4fa8d07 (08:10Z), all on
  main's first-parent chain. The "Last verified" chain lists #268, #267, #265, #266 — correct
  reverse-chronological order. tasks.md's prose orders by attempt, not by merge, and claims no
  merge order.
- Attempt 1 (run-a-attempt-20260912.md L34, L36, L51–55): HTTP 503 after 56.374 s; verifier run
  contrary to the packet stop rule; `class_2a`, `non_go`, exit 1. Its JSON is
  rehearsal-20260912.json (exists).
- Attempt 2 (run-a-attempt-20260913.md L6–8, L111–116; transcript L25–28): HTTP 503, curl exit 0,
  exit 3, replica wait not reached, verifier not started, no JSON written. Directory listing
  confirms no 20260913 rehearsal JSON.
- Packet stop rule: readiness packet L217 ("On any non-200, stop and report — do not re-issue").
  Two-decision separation: packet §0 L28–31 ("They should not be granted together").
- Wrapper limits (source lines): `for ($probe = 1; $probe -le 5` L221; `Start-Sleep -Seconds
  $IntervalSeconds` L222; curl vector `-q --noproxy '*' -sS … --max-time 30` L229; retry set L294–298
  = (exit 0 AND 503) or (exit 28 AND 000); every other outcome `exit 3` L321/L325/L346/L353;
  `'--mode', 'preflight'` L640. Cleanup failure after a 200 exits 3 before `return` (L346 precedes
  L347–350), so the antivirus-lock sentence is accurate. Interval is a validated parameter, 0–30,
  production 5, "a live run must omit the parameter or pass exactly 5" (L88–92, L115, L389–390).
- Backlog citations: stub L85–86 = single trailing-backslash strip then `%~dp6` compare (item 1
  holds); test L792 network-command regex, L1122 `$bannedCmds`, L1137/L1141 string/type network
  denies (item 2 holds; see Minor 2 for a nuance); wrapper L188–189 = "'*' is quoted so PowerShell
  passes the literal character" (item 3 holds).
- Links: every relative link in the three new files and in every added line of the two edited files
  resolves (25/25, fragments stripped).
- Sibling convention: 23 backlog READMEs; `**Status:**` 23, `**Owner:**` 15, `**Tracked in:**` 14.
  Both new READMEs use exactly those three.
- Preserved Fable report: byte-identical to the round-1 file in the prior session's scratchpad
  (sha256 a21ece16… both).
- Stale-wording sweep of both full files: no `past the exec probe`, `still has no live result`,
  `223/223`, `timeout/transport`, or handoff-index text remains. The retained 2026-09-11 paragraphs
  (plan L43–48, tasks.md L33–36) are dated blocks followed by "have since been consumed" /
  the new PR #265–#267 block, so they read as history.

```
$ python -B scripts/tests/test_master_plan_status_propagation.py     Ran 33 tests … OK  exit=0
$ python -B scripts/check-spec-references.py --self-test              self-test: PASS   exit=0
$ python -B scripts/check-spec-references.py tasks.md --against requirements.md --coverage
  0/0 criteria cited by at least one task                              exit=0 (vacuous; now documented in the new backlog)
```

## Round-1 findings, disposition

| # | Round 1 | Status |
|---|---|---|
| M1 | #265/#266/#267 and both wakes absent; Wave 8 row stale | Closed: header, "Last verified" chain, new block, Wave 8 row all record both attempts with correct figures and links |
| M2 | Minors not individually non-blocking | Closed: each item prefixed "Minor — non-blocking" |
| M3 | README provenance off-repo, no links | Closed: `**Tracked in:**` PR #268 + report; per-item line-anchored links |
| m4 | 223/223, Qodana, handoff index | Closed: removed (the remaining "Qodana" at plan L278 is pre-existing PR #259 text, untouched) |
| m5 | retry-set over-statement | Narrowed but not exact — see Minor 1 |
| m6 | Wave 8 link text → backlog | Closed: now links the wrapper source |
| m7 | Blocks:/Source: labels | Closed |
| m8 | second decision only inferable | Closed: stated explicitly in plan, tasks.md, and README |
| m9 | 33/33 nearly irrelevant | Closed: presented as limited coverage; 0/0 gap recorded in its own backlog |

## Findings

### Blocking — none
Task 8.9 reads OPEN in every changed passage; the #268 wrapper is stated to have no Production run;
the at-most-five-probe preflight approval and the credential-using execute approval are named as
separate owner decisions in all three documents that discuss them.

### Major — none

### Minor
1. **Plan retry wording is a three-way disjunction; the wrapper's is two conjunctive pairs.**
   Plan (new block, "Its curl vector…" paragraph): "retries only HTTP `503`, curl exit `28`, or
   status `000`". Source L294–298 retries only (exit 0 AND 503) or (exit 28 AND 000); status 000 with
   exit 6/7/35 stops with exit 3 (L303–313). tasks.md's "`503` or curl `28`/`000`" is the closer
   spelling. Suggest: "HTTP 503 with curl exit 0, or a curl timeout (exit 28 with status 000)".
   Non-blocking: the bound and fail-closed behaviour are stated correctly elsewhere in the same
   paragraph.
2. **Backlog item 2 omits the allowlist that already exists.** test L1123 `$allowedHeads =
   @('AzCommand','DockerCommand','PythonCommand','Curl','expr')` is a positive allowlist for
   variable-headed commands (L1127–1128). The uncovered class is string-constant heads (native
   paths, module-qualified cmdlets), which pass only the denylist at L1131. The item's premise
   holds; add L1123 to the citations and narrow the sentence to string-constant heads so the
   follow-up is scoped correctly. Non-blocking.

### Nit
- tasks.md "separated by five seconds" states the production value; the plan's "the production
  five-second interval" is exact. Optional: align tasks.md to the plan's phrasing.

### Unverified (not fetched)
GitHub's own merge timestamp and PR CI state for #268 (local committer stamp is consistent).

### Open questions for CODEX
None. Both Minors are wording-only and can be folded in without re-review; if they are, the
propagation suite and the link check are the only re-runs needed.
