# Checkpoint Protocol — Claude ↔ Codex

Claude and Codex share this workspace. Rather than the repo owner relaying messages between them —
slow, and error-prone in a way that cost a full review round once — they coordinate through a live
append-only file on disk.

**This document is the protocol and template. It is tracked.**
**The live log is `docs/superpowers/CHECKPOINT.md`, which is deliberately NOT tracked.**

## Why the live log is ignored

`docs/superpowers/brainstorm/.gitignore` is `*`, so the observability checkpoint log that
established this pattern was never committed. That was the right call and is followed here:

- A tracked always-current file makes **every agent turn produce a dirty working tree**, so no
  branch is ever clean and `git status` stops being a useful signal.
- Coordination notes **leak into unrelated branches and PRs** — a checkpoint entry about Spec B
  would show up in a diff for an unrelated bug fix.
- The log is scratch by nature. `plans/` is tracked because plans are durable artifacts;
  `brainstorm/` is not, because working logs are not.

Archiving at topic close is therefore **deliberate, not automatic**. If a particular archive is
worth versioning, change the `brainstorm/.gitignore` rule explicitly for that file and say why in
the commit — don't quietly widen it.

## Protocol

- **Append only.** Add your entry at the bottom. Never edit or delete another participant's entry.
- **Re-read immediately before appending.** Append-only prevents *conflict*, not *loss* — two
  simultaneous writes on a shared filesystem can still clobber an entry. If the last entry number
  is not what you expect, re-read rather than overwrite.
- Entry header: `## [N] <AGENT> — <short topic>`.
- **Update the Status table** at the top when you append. It is the only mutable part of the file.
- End every entry with `### Open questions for <OTHER>`. If there are none, say so explicitly.
- Disagree openly and give reasoning. The point is a genuine second opinion, not consensus theatre.
  If a question is wrongly framed, say that instead of answering it.
- Correct any fact you can verify is wrong. Mark unverified claims `[unverified]`.

## Pin the artifact you reviewed

**Every review entry states the artifact fingerprint it read.** One full round of the Spec A review
was spent on findings against a stale copy: they had already been fixed, and the cited line numbers
belonged to a file 87 lines shorter. Neither side could tell without diffing by hand.

The canonical fingerprint is **`git hash-object`**:

```
git hash-object .kiro/specs/supported-asset-integrity/design.md
```

One command, identical in PowerShell and git-bash, available wherever git is. `md5sum` and `wc -l`
are **not** used: `md5sum` is absent from PowerShell, and line counts diverge between tools on a
workspace where git normalises LF↔CRLF on checkout — two agents can honestly report different
counts for the same file.

Entry header format:

```
Reviewed: design.md — Revision 9, git hash-object 4b8c2f1e9a...
```

If the fingerprint does not match the file on disk, **stop and re-read before reviewing**.

## Cite tool output, don't assert cleanliness

`scripts/check-spec-references.py` exists because three of its own bugs were found only *after* it
reported clean — a filter that skipped the references it existed to catch, a word boundary that
dropped the last citation on every line, and a coverage guard that failed in only one direction.
Paste actual output rather than writing "checks pass":

```
python scripts/check-spec-references.py --self-test
python scripts/check-spec-references.py <spec>.md [--pairs]
python scripts/check-spec-references.py tasks.md --against requirements.md --coverage
```

The self-test must pass before any clean result means anything.

---

## Template

Copy into `docs/superpowers/CHECKPOINT.md` when opening a topic.

```markdown
# Active Checkpoint — Claude ↔ Codex

Protocol: `docs/superpowers/CHECKPOINT_PROTOCOL.md`. This file is untracked by design.

## Status

| field | value |
|---|---|
| **Topic** | <what is being worked on> |
| **Ball is with** | **claude** / **codex** / **owner** |
| **Open questions** | <count, or none> |
| **Last entry** | [N] |

---

## [0] <AGENT> — <topic>

**Reviewed:** <artifact — revision, git hash-object> · or `n/a — work entry`

<body>

### Open questions for <OTHER>

<numbered, or "None.">
```

## Closing a topic

1. Move `CHECKPOINT.md` to `docs/superpowers/brainstorm/<YYYY-MM-DD>-<topic>.md` — still ignored.
2. Start a fresh `CHECKPOINT.md` from the template above.
3. Carry forward only what the next topic must not relitigate. Everything else stays in the archive.

Keeping the active file short enough to read in full every time is the point; the observability log
reached 1248 lines, at which cost nobody re-read it.
