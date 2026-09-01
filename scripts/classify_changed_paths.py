#!/usr/bin/env python3
"""Classify a pull request's changed paths for CI job selection.

Contract:

  - A PR is `docs_only` only when *every* changed path matches the documentation
    allowlist. The allowlist is a **skip** allowlist: a match means "safe to skip
    the expensive verification chain", and anything unrecognised forces the full
    suite.

    This is the opposite polarity to the trigger allowlist removed from deploy.yml
    in 8b74ff82 (checkpoint-9.8 incident), where a path *match* caused an action
    (a production deploy) and an unlisted new module silently got nothing. Here a
    non-match causes the safe action, so an unlisted new path over-tests rather
    than under-tests.

  - Fails closed. Empty diffs, unmatched paths, and non-`pull_request` events all
    classify as NOT docs-only. Malfunctions (git failure, missing SHAs on a PR
    event) emit `docs_only=false` *and* exit non-zero, so the `ci-required`
    aggregate observes a failed dependency rather than a silent skip.

  - Changed files come from `list_changed_files()` in the master-plan guard --
    three-dot `base...head` with `--no-renames`, so a file moved out of a governed
    directory reports its source path too and cannot be laundered into docs-only.
    That rationale is documented at the source; it is reused here rather than
    reimplemented so the two guards cannot drift.

  - The GitHub pull-request *files* API is deliberately not used: it truncates at
    3000 files, which would silently shrink a large diff into a docs-only verdict.

Allowlist safety notes:

  - `docs/**` -- verified that no Gradle build file, Java/TypeScript source or
    test, workflow, or script reads this tree.
  - top-level `*.md` -- README, ROADMAP, roadmap_enhancements_v*, and the loose
    root specs. Same verification.
  - `.kiro/specs/**/*.md` -- spec ledgers. Safe to drop the expensive chain for
    because the gate that actually governs these files,
    `master-plan-status-propagation`, is a separate required workflow that stays
    unconditional, and `static-guard` still runs its contract tests.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))

from check_master_plan_status_propagation import (  # noqa: E402
    GuardError,
    list_changed_files,
)

DOCS_ONLY_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"^docs/.+$"),
    re.compile(r"^[^/]+\.md$"),
    re.compile(r"^\.kiro/specs/.+\.md$"),
)


def is_docs_path(path: str) -> bool:
    """True when a single changed path is safe to skip the expensive chain for."""
    return any(pattern.match(path) for pattern in DOCS_ONLY_PATTERNS)


def classify(changed_files: list[str]) -> tuple[bool, str, list[str]]:
    """Return (docs_only, reason, unmatched_paths).

    Fails closed: an empty change set is *not* docs-only, because "we could not
    resolve any paths" and "this PR only touches documentation" are different
    claims and only the second one justifies skipping.
    """
    if not changed_files:
        return False, "no changed paths resolved; refusing to infer docs-only", []

    unmatched = [path for path in changed_files if not is_docs_path(path)]
    if unmatched:
        return (
            False,
            f"{len(unmatched)} of {len(changed_files)} changed path(s) fall outside "
            "the documentation allowlist",
            unmatched,
        )

    return (
        True,
        f"all {len(changed_files)} changed path(s) matched the documentation allowlist",
        [],
    )


def emit_output(name: str, value: str) -> None:
    """Write a job output, and echo it so the raw log records the decision too."""
    target = os.environ.get("GITHUB_OUTPUT")
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(f"{name}={value}\n")
    print(f"{name}={value}")


def write_summary(lines: list[str]) -> None:
    """Append a human-readable rationale to the workflow step summary."""
    target = os.environ.get("GITHUB_STEP_SUMMARY")
    body = "\n".join(lines) + "\n"
    if target:
        with open(target, "a", encoding="utf-8") as handle:
            handle.write(body)
    print(body, end="")


def report(docs_only: bool, reason: str, unmatched: list[str], changed: int) -> None:
    lines = [
        "### Changed-path classification",
        "",
        f"- `docs_only`: **{'true' if docs_only else 'false'}**",
        f"- changed paths: {changed}",
        f"- reason: {reason}",
    ]
    if unmatched:
        shown = unmatched[:20]
        lines.append("- paths outside the allowlist:")
        lines.extend(f"  - `{path}`" for path in shown)
        if len(unmatched) > len(shown):
            lines.append(f"  - ...and {len(unmatched) - len(shown)} more")
    write_summary(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Classify PR changed paths so CI can skip the expensive verification "
            "chain on documentation-only pull requests."
        )
    )
    parser.add_argument(
        "--event-name",
        required=True,
        help="github.event_name; anything other than pull_request runs the full suite",
    )
    parser.add_argument("--base", default="", help="PR base SHA")
    parser.add_argument("--head", default="", help="PR head SHA")
    args = parser.parse_args(argv)

    if args.event_name != "pull_request":
        emit_output("docs_only", "false")
        report(
            False,
            f"event is {args.event_name!r}, not pull_request; full suite always runs",
            [],
            0,
        )
        return 0

    base, head = args.base.strip(), args.head.strip()
    if not base or not head:
        emit_output("docs_only", "false")
        print(
            "ERROR: pull_request event reached the classifier without both base and "
            f"head SHAs (base={base!r}, head={head!r}). Refusing to classify.",
            file=sys.stderr,
        )
        return 1

    try:
        changed = list_changed_files(base, head)
    except GuardError as err:
        emit_output("docs_only", "false")
        print(f"ERROR: {err}", file=sys.stderr)
        return 1

    docs_only, reason, unmatched = classify(changed)
    emit_output("docs_only", "true" if docs_only else "false")
    report(docs_only, reason, unmatched, len(changed))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
