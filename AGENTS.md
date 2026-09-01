# Repository Agent Instructions

## Scope

These rules apply to every agent working in this repository, including Claude, Codex, and Cursor.

## Assigned Worktrees

Each agent has a persistent sibling worktree for normal work:

| Agent | Assigned worktree |
|---|---|
| Claude | `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-claude` |
| Codex | `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-codex` |
| Cursor | `D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker-cursor` |

Before modifying repository state, run `git rev-parse --show-toplevel` and confirm that it resolves
to the assigned worktree. If it does not, stop and switch or hand off to the correct worktree before
editing, committing, rebasing, or running any other mutating command.

## Worktree Layout

1. Use the assigned worktree for normal agent work.
2. When durable parallel isolation is needed, create a sibling worktree named
   `wealthmgmtandportfoliotracker-<agent-name>-<id>` under
   `D:\Projects\Development\Java\Spring`.
3. For transient or time-sensitive work, use the same
   `wealthmgmtandportfoliotracker-<agent-name>-<id>` naming pattern under `C:\worktrees`.
4. Always use sibling-folder worktrees. Do not create nested worktrees under this repository or any
   other worktree. Prohibited locations include `.claude/worktrees`, `.worktrees`, and `worktrees`
   beneath a checkout. This restriction is required for IntelliJ IDEA compatibility.

## Worktree Ownership and Review

- An implementer/reviewer or drafter/reviewer pair should normally share the implementer's or
  drafter's worktree.
- A reviewer is read-only in another agent's worktree by default. Any modification requires
  explicit permission from that worktree's owner before the edit is made.
- Do not repurpose another agent's assigned worktree for unrelated work.

## Branch Names

This project does not impose a branch-name convention. Choose a descriptive branch name appropriate
for the task; no agent prefix or fixed pattern is required.
