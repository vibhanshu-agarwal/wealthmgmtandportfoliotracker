# Interim Claude-Internal Review Loop — Design

**Status:** design, approved for planning
**Date:** 2026-09-11
**Track:** process
**Lifetime:** INTERIM — see [Revert Procedure](#11-revert-procedure)

---

## OWNER APPROVAL CALLOUT

Two actions in this design are the owner's to authorize and are **not** taken by any agent:

| Blocked action | Decision requested | Consequence |
|---|---|---|
| Push of the setup branch and creation of its PR | Authorize publication of the `process`-track setup bundle | Without it the loop exists only in the local worktree; Codex cannot see agent definitions, hooks, or ledgers, and the arrangement cannot be used across worktrees |
| The three documentation edits in [§10](#10-documentation-changes-owned-by-codex) | Direct Codex to make them | `AGENTS.md` continues to describe Claude-implements/Codex-reviews, which this arrangement temporarily contradicts. The repo's stated protocol would disagree with its actual one |

A third item requires no approval but is recorded because it changes a tracked file outside the
new subsystem: `.gitignore:103` must be narrowed (§9.2). Without it none of the subsystem is
committable.

---

## 1. Why

Codex plan limits have made the standing arrangement — Claude implements, Codex reviews —
temporarily unavailable. For a short period Claude performs **both** implementation and review,
and Codex performs documentation updates, status tracking, and final task reconciliation in the
Asset Picker master plan.

Compressing implementation and review into one tool removes the structural guarantee that made the
previous arrangement trustworthy: the reviewer was a different system with no stake in the
implementation. This design restores that guarantee **inside** Claude Code by separating models and
making the separation machine-enforced rather than conventional.

The single rule the whole design serves:

> **No model reviews its own work, and no model closes a finding raised against its own work.**

Everything below is mechanism for that sentence.

## 2. Scope

**In scope.** Agent definitions, the findings ledger, the enforcement hooks, the routing rubric,
and the protocol document describing the arrangement.

**Out of scope.** The Asset Picker master plan and its status reconciliation (Codex's, unchanged).
Spec authoring. The production approval gate, which remains the owner's under `AGENTS.md`
§ Owner Approval Callouts and is untouched by this design. The cross-tool worktree rules, which
continue to apply as written.

**Non-goal.** This does not replace Codex review permanently and is not designed to. It is built to
be reverted in one commit.

## 3. Topology

The **orchestrator is the main Claude Code session** running Opus 5. It is not an agent definition
file. This is a deliberate architectural choice, not an omission: `Stop` hooks fire only for the
main session, so an orchestrator implemented as a subagent would sit *outside* the very gate this
design exists to impose. Putting the orchestrator in the main thread makes it the gate's subject.

The orchestrator owns task intake, complexity classification, routing, dispatch, ledger lifecycle,
and driving findings to closed. It delegates to three subagents:

| Agent | Model | Tool posture | Role |
|---|---|---|---|
| `implementer-sonnet` | `sonnet` | full | Default implementer |
| `implementer-opus` | `opus` | full | Escalation implementer |
| `fable-reviewer` | `fable`, `effort: high` | read-only: `disallowedTools: Write, Edit, NotebookEdit`; no `Agent` | Reviewer of record |

`fable-reviewer` holds no write tools at all. It cannot edit code, and it cannot write the ledger
that records its own findings — the capture hook does that from its output (§6.1). A reviewer that
cannot edit the artifact under review, and cannot edit the record of its review, has no mechanism
by which to soften either.

`model: fable` is a documented, supported frontmatter value, as are `sonnet` and `opus`; project
`.claude/agents/` takes precedence over user scope. Verified against
`https://code.claude.com/docs/en/sub-agents` on 2026-09-11.

## 4. Routing

**Sonnet 5 by default.** The orchestrator escalates to Opus 5 on any of:

1. Cross-module or cross-cutting change
2. Concurrency or security surface
3. Ambiguous or contested spec
4. Deploy, infrastructure, or CI-workflow touch
5. **Automatic:** any task Fable has returned findings on twice

Trigger 5 is not a judgment call. Two failed review rounds is evidence the task was misclassified,
and the rubric corrects itself rather than waiting for the orchestrator to notice. The escalation
trigger is recorded in the ledger as `escalationReason`.

## 5. Review assignment

The orchestrator classifies each task `simple` or `complex` and records the classification with a
rationale. Classification determines who may review:

| Implementer | Classification | Permitted reviewer |
|---|---|---|
| `sonnet` | `simple` | `fable` **or** `opus` |
| `sonnet` | `complex` | `fable` |
| `opus` | either | **`fable` only** |

Opus-implemented work always goes to Fable, because the only other candidate reviewer is Opus
itself. The general invariant, checked mechanically, is:

```
reviewer != implementer
```

That expression is the whole of "no self-review" in machine-checkable form, and the gate refuses
any ledger that violates it.

### 5.1 The orchestrator-edit stamp

The loophole this closes: the orchestrator implements a change itself, classifies it `simple`,
nominates itself as reviewer, and closes its own findings — all while every individual step looks
legal.

A `PreToolUse` hook on `Edit`, `Write`, and `NotebookEdit` in the main session stamps the active
ledger with `implementer: opus` and `orchestratorEdited: true`. The reviewer matrix then forces
Fable, unconditionally.

The stamp applies to every main-session edit **except** an allowlist of coordination paths, which
are recorded process artifacts rather than implementation:

```
docs/superpowers/CHECKPOINT.md
docs/superpowers/plans/**
docs/reviews/ledger/**
```

Edits to any other path — source, tests, infrastructure, and all other documentation — count as
implementing. A doc-only task written by the orchestrator therefore still requires Fable review,
which is the intended behaviour: the gate tracks authorship, not file type.

The orchestrator never hand-writes ledgers; it calls the ledger CLI (§6.3). This keeps the
allowlist small and keeps `Write` unambiguous as an implementation signal.

## 6. The ledger

One JSON file per task at `docs/reviews/ledger/<task-slug>.json`. It is the single source of truth
for review state and the sync surface Codex reads.

```json
{
  "taskId": "b2-task-9-6-demo-auth-fixture",
  "title": "Demo-authenticated Playwright fixture",
  "state": "in-progress",
  "classification": "complex",
  "classificationRationale": "Touches auth surface and CI workflow",
  "implementer": "sonnet",
  "reviewer": "fable",
  "escalationReason": null,
  "orchestratorEdited": false,
  "reviewRounds": 0,
  "findings": [],
  "updatedAt": "2026-09-11T00:00:00Z"
}
```

`state` is one of `in-progress`, `in-review`, `complete`, `parked`.

A finding:

```json
{
  "id": "F1",
  "severity": "blocker",
  "file": "frontend/src/lib/presence.ts",
  "line": 112,
  "summary": "Cache is not discarded on unmount",
  "detail": "...",
  "status": "open",
  "raisedRound": 1,
  "resolvedRound": null,
  "waiverReason": null
}
```

`status` is `open`, `resolved`, or `waived`.

### 6.1 Findings are written by the hook, never transcribed

A `SubagentStop` hook matched on agent type `fable-reviewer` parses the reviewer's
`last_assistant_message` for a structured findings block and writes it into the ledger itself. If
the block is absent or malformed, the hook exits 2 and the reviewer continues until it produces
one.

`SubagentStop` receives `agent_type` and `last_assistant_message` and can block on exit 2, verified
against `https://code.claude.com/docs/en/hooks` on 2026-09-11.

The orchestrator never transcribes findings. There is no step at which the implementing side
handles the reviewing side's words.

### 6.2 Mechanised checkpoint conventions

`CHECKPOINT_PROTOCOL.md` records that one full Spec A review round was lost to findings filed
against a stale copy, and mandates pinning `git hash-object`. The capture hook enforces both
standing conventions rather than trusting them:

- **Pin the artifact.** Every reviewed file in the structured block carries a `git hash-object`
  fingerprint. The hook recomputes each one against disk and rejects the review on mismatch — the
  stale-copy round becomes unrepeatable rather than merely discouraged.
- **Cite tool output.** Any finding asserting that a check passes must carry the command and its
  output. Bare cleanliness claims are rejected.

### 6.3 Ledger CLI

`node .claude/hooks/ledger.mjs <open|classify|route|show|park> ...`. The orchestrator's only
sanctioned way to touch a ledger. Two fields are **never** writable through it:

- `findings` — written by the capture hook only
- `state: complete` — written by the gate only, on a review round that returns zero open findings

The orchestrator cannot mark its own work complete. Completion is a fact the gate observes, not a
claim the orchestrator makes.

## 7. Closure

A finding closes only through the reviewer that raised it:

- **Fable path.** The orchestrator re-dispatches `fable-reviewer` with the open finding IDs. Fable
  returns a per-finding verdict (`resolved` / `still-open`), and the capture hook applies it.
- **Opus path.** Opus reviewed Sonnet's work, so Opus verifies Sonnet's fix and closes. Legal under
  the invariant: the reviewer is closing the implementer's work, not its own. If the orchestrator
  fixes the finding itself, §5.1 stamps the ledger and the task is forced to Fable.

`waived` exists for findings the owner overrules. It requires a written `waiverReason`, is
documented as the owner's call alone, and is reported prominently by the gate. The hook cannot
verify who authorised a waiver; this is stated rather than papered over.

## 8. The Stop gate

A `Stop` hook on the main session exits 2 — blocking the turn from ending — when any active ledger:

1. has one or more findings with `status: open`; or
2. is marked `complete` with `reviewRounds == 0`; or
3. has `reviewer == implementer`; or
4. has `implementer: opus` with any reviewer other than `fable`.

Conditions 3 and 4 fire on an invalid *route*, before any work is reviewed, so a misrouted task
fails early rather than after implementation.

`state: parked` exempts a ledger. Parking is documented as owner-authorised.

### 8.1 Loop guard, and what the gate does not promise

A hook that blocks unconditionally can trap a session: each blocked `Stop` returns the turn to the
orchestrator, which can do nothing but end the turn again. After three consecutive blocks with an
unchanged ledger hash, the gate permits the stop and emits a prominent warning naming every open
finding.

The guarantee is therefore precise, and narrower than "the loop cannot be exited":

> Work cannot be **silently** declared complete while findings are open.

The ledger still shows open findings, the warning is loud, and `complete` is still unwritten. What
the gate cannot do is force a wedged session to keep running forever, and pretending otherwise
would be the same class of error as an evidence check whose measured signal drifts from its claimed
one.

## 9. Files

### 9.1 New

```
.claude/settings.json                     Stop, SubagentStop, PreToolUse registrations
.claude/agents/implementer-sonnet.md
.claude/agents/implementer-opus.md
.claude/agents/fable-reviewer.md
.claude/hooks/review-gate.mjs             Stop gate (§8)
.claude/hooks/capture-review.mjs          SubagentStop capture (§6.1)
.claude/hooks/stamp-orchestrator-edit.mjs PreToolUse stamp (§5.1)
.claude/hooks/ledger.mjs                  Orchestrator CLI (§6.3)
.claude/hooks/lib/ledger-core.mjs         Schema, validation, gate predicates
docs/reviews/ledger/.gitkeep
docs/agent-instructions/MULTI_AGENT_INTERIM_PROTOCOL.md
```

Hooks are Node ESM. Node 26.5.0 and npm 11.17.0 are present; Node avoids PowerShell quoting
fragility and parses JSON without a dependency.

### 9.2 Modified

**`.gitignore:103`** currently reads `.claude/`. Git cannot re-include a file whose parent
directory is excluded, so the wholesale ignore must become a contents-level ignore with explicit
re-inclusions:

```gitignore
.claude/*
!.claude/agents/
!.claude/hooks/
!.claude/settings.json
```

`.claude/launch.json` and `.claude/settings.local.json` stay ignored. Without this change nothing in
§9.1 is committable, and Codex cannot see the arrangement at all.

## 10. Documentation changes (owned by Codex)

Requires owner direction per the approval callout. None are made by Claude.

1. **`AGENTS.md`** — new `## Interim Review Arrangement` section: the Claude-internal
   orchestrator/implementer/reviewer roles, Codex's temporary scope (documentation, status
   tracking, master-plan reconciliation), and the revert trigger. Must be marked INTERIM, because
   the file currently describes an arrangement this design temporarily contradicts.
2. **`docs/superpowers/CHECKPOINT_PROTOCOL.md`** — recognise `FABLE` as a participant in
   `## [N] <AGENT>` entry headers; note that a review ledger is a citable artifact whose
   fingerprint may be pinned like any other.
3. **`docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md`** — record the interim arrangement under the
   process track. Final task reconciliation remains Codex's, unchanged.

The setup PR body must carry `Master-plan impact: updated — process`, or `none:` with a same-line
rationale, per `scripts/check_master_plan_status_propagation.py`. That guard is fail-closed and
rejects bare `none`.

## 11. Revert procedure

The arrangement ends when Codex plan limits lift. Everything lands in **one commit**, additive
except the `.gitignore` narrowing, so reverting is `git revert` of that commit plus Codex undoing
§10.

There is deliberately **no on/off switch**. A bypass flag is the mechanism by which a temporary
gate becomes a permanent formality — the reversion path is a revert, which is visible in history,
rather than a setting, which is not.

## 12. Testing

Per `AGENTS.md` and the project's standing practice, the gates are tested at two levels.

**Unit** — `node --test` over `lib/ledger-core.mjs` and each gate predicate, against fixture
ledgers: each of the four block conditions in §8 fires; a valid ledger does not block; a
`reviewer == implementer` ledger blocks; `opus` + non-Fable blocks; a stale `git hash-object`
fingerprint is rejected; malformed findings blocks are rejected; the loop guard releases on the
fourth consecutive unchanged block; `complete` and `findings` are unwritable through the CLI.

**Live** — unit tests do not validate a gate, only its predicate. A throwaway task is driven
through the real loop end to end: dispatch to `implementer-sonnet`, dispatch to `fable-reviewer`,
capture a genuine finding, observe the `Stop` gate **actually block**, fix, re-review, observe the
clean stop and the hook-written `state: complete`. The captured block is the evidence of record.
A gate that has never been observed blocking has not been shown to be a gate.

## 13. Risks

| Risk | Mitigation |
|---|---|
| Strict closure costs a Fable round per fix cycle | Opus may review Sonnet's `simple` work (§5), which is the pressure valve; auto-escalation (§4, trigger 5) keeps misclassification self-correcting |
| Hook bug blocks all work | Loop guard (§8.1) releases after three unchanged blocks; unit + live tests (§12) precede use |
| `waived` becomes routine | Requires written rationale, reported prominently by the gate, owner-authorised only |
| Interim arrangement quietly becomes permanent | No bypass switch; revert is one commit; INTERIM marking in `AGENTS.md` (§10, item 1) |
| Ledger and master plan drift | Disjoint ownership: Claude never edits the master plan, Codex never edits ledgers |
