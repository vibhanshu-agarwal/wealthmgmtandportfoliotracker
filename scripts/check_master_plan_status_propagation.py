#!/usr/bin/env python3
"""Fail-closed CI guard for Asset Picker master-plan/status propagation.

Rule (docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md §0.2):

  A PR that touches Spec A, B1, B2, their release workflows, or an Asset Picker
  blocker must do exactly one of:

  1. update the living master plan and every owning task ledger whose track was
     touched; or
  2. state `Master-plan impact: none` in the PR body with a non-empty rationale.

Process-only changes (guard scripts, release workflow text, handoff/runbook
docs, the master plan itself) require the master-plan update when they change
program status, and do not invent a false Spec A/B1/B2 ledger touch.

This script inspects the PR body directly. If a future GitHub Actions change
makes that body unavailable, fail closed — do not silently fall back to a
path-only approximation.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass

MASTER_PLAN = "docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md"

@dataclass(frozen=True)
class Track:
    name: str
    prefix: str
    ledger: str


TRACKS = (
    Track(
        "Spec A",
        ".kiro/specs/supported-asset-integrity/",
        ".kiro/specs/supported-asset-integrity/tasks.md",
    ),
    Track(
        "B1",
        ".kiro/specs/portfolio-composition-contract/",
        ".kiro/specs/portfolio-composition-contract/tasks.md",
    ),
    Track(
        "B2",
        ".kiro/specs/asset-picker-composition/",
        ".kiro/specs/asset-picker-composition/tasks.md",
    ),
)

# Release / process surfaces that govern Asset Picker program status without
# belonging to a single Spec A/B1/B2 ledger.
EXTRA_GOVERNED_PREFIXES = (
    MASTER_PLAN,
    "docs/agent-instructions/CURSOR_HANDOFF_ASSET_PICKER",
    "docs/runbooks/SPEC_A_",
    ".github/workflows/deploy.yml",
    ".github/workflows/deploy-azure.yml",
    ".github/workflows/deploy-aws.yml",
    ".github/workflows/terraform-azure.yml",
    ".github/workflows/terraform.yml",
    ".github/workflows/frontend-cd.yml",
    "scripts/check_master_plan_status_propagation.py",
    "scripts/tests/test_master_plan_status_propagation.py",
)

IMPACT_LINE = re.compile(
    r"(?im)^[ \t]*Master-plan impact:[ \t]*(.*?)[ \t]*$"
)


class GuardError(ValueError):
    """Raised when a governed PR lacks a valid status-propagation outcome."""


def normalize_files(changed_files: list[str] | tuple[str, ...] | None) -> list[str]:
    files: list[str] = []
    for raw in changed_files or ():
        path = raw.strip().replace("\\", "/")
        if path:
            files.append(path)
    return files


def is_governed(path: str) -> bool:
    for track in TRACKS:
        if path.startswith(track.prefix) or path == track.ledger:
            return True
    for prefix in EXTRA_GOVERNED_PREFIXES:
        if path == prefix or path.startswith(prefix):
            return True
    return False


def touched_tracks(changed_files: list[str]) -> list[Track]:
    found: list[Track] = []
    for track in TRACKS:
        if any(
            path.startswith(track.prefix) or path == track.ledger
            for path in changed_files
        ):
            found.append(track)
    return found


def parse_none_rationale(pr_body: str | None) -> str | None:
    """Return the non-empty none-impact rationale, or None if undeclared.

    Raises GuardError when an impact line is present but malformed, or when
    `none` is declared without a non-empty explanation.
    """
    if pr_body is None:
        return None

    matches = list(IMPACT_LINE.finditer(pr_body))
    if not matches:
        return None

    match = matches[-1]
    value = match.group(1).strip()
    if not value:
        raise GuardError(
            "Malformed Master-plan impact declaration: expected "
            "`Master-plan impact: none` plus a non-empty rationale, or omit the "
            "marker and update the master plan + owning task ledger(s)."
        )

    # Accept `none` or `none — rationale` / `none: rationale` on one line.
    if re.match(r"(?i)^none\b", value):
        inline = re.sub(r"(?i)^none\b\s*[-—–:]?\s*", "", value).strip()
        if inline:
            return inline
        after = pr_body[match.end() :]
        rationale_lines: list[str] = []
        for line in after.splitlines():
            stripped = line.strip()
            if not stripped:
                if rationale_lines:
                    break
                continue
            if IMPACT_LINE.match(stripped):
                break
            if stripped.startswith("#"):
                if rationale_lines:
                    break
                continue
            rationale_lines.append(stripped)
        rationale = " ".join(rationale_lines).strip()
        if not rationale:
            raise GuardError(
                "Master-plan impact: none requires a non-empty rationale explaining "
                "why program status, dependencies, blockers, and next actions are "
                "unchanged."
            )
        return rationale

    # A non-none impact line is reviewer documentation only; it does not satisfy
    # the mechanical none-declaration path. Fall through to the path-update rule.
    return None


def evaluate_status_propagation(
    *,
    changed_files: list[str] | tuple[str, ...] | None,
    pr_body: str | None,
) -> None:
    files = normalize_files(changed_files)
    governed = [path for path in files if is_governed(path)]
    if not governed:
        return

    none_rationale = parse_none_rationale(pr_body)
    if none_rationale is not None:
        return

    tracks = touched_tracks(files)
    missing: list[str] = []

    if MASTER_PLAN not in files:
        missing.append(
            f"update `{MASTER_PLAN}` (or declare `Master-plan impact: none` "
            "with a non-empty rationale)"
        )

    for track in tracks:
        if track.ledger not in files:
            missing.append(
                f"update owning ledger `{track.ledger}` for touched {track.name} paths"
            )

    if missing:
        touched = ", ".join(governed)
        details = "; ".join(missing)
        raise GuardError(
            "Governed Asset Picker program paths changed without a valid status "
            f"propagation outcome. Touched: {touched}. Required: {details}."
        )


def list_changed_files(base: str, head: str) -> list[str]:
    try:
        output = subprocess.check_output(
            ["git", "diff", "--name-only", f"{base}...{head}"],
            text=True,
            stderr=subprocess.STDOUT,
        )
    except subprocess.CalledProcessError as exc:
        raise GuardError(
            "Unable to list PR changed files for master-plan status propagation "
            f"guard (base={base!r}, head={head!r}): {exc.output}"
        ) from exc
    return normalize_files(output.splitlines())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Enforce Asset Picker master-plan/status propagation on governed PRs."
    )
    parser.add_argument("--base", required=True, help="PR base SHA")
    parser.add_argument("--head", required=True, help="PR head SHA")
    parser.add_argument(
        "--pr-body-file",
        help="Path to a file containing the PR body (preferred over --pr-body)",
    )
    parser.add_argument(
        "--pr-body",
        default=None,
        help="PR body text; prefer --pr-body-file to avoid shell length limits",
    )
    args = parser.parse_args(argv)

    if args.pr_body_file:
        with open(args.pr_body_file, encoding="utf-8") as handle:
            body = handle.read()
    else:
        body = args.pr_body

    if body is None:
        raise GuardError(
            "PR body is unavailable to the master-plan status propagation guard. "
            "Refusing to degrade to a path-only approximation."
        )

    changed = list_changed_files(args.base, args.head)
    evaluate_status_propagation(changed_files=changed, pr_body=body)
    print(
        "Master-plan status propagation guard passed "
        f"({len(changed)} changed paths, "
        f"{sum(1 for path in changed if is_governed(path))} governed)."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardError as err:
        print(f"ERROR: {err}", file=sys.stderr)
        raise SystemExit(1)
