#!/usr/bin/env python3
"""Fail-closed CI guard for Asset Picker master-plan/status propagation.

Every pull request must carry exactly one canonical declaration:

  Master-plan impact: updated — <tracks>
  Master-plan impact: none: <same-line rationale>

Tracks are a comma-separated list from {Spec A, B1, B2, process}.

  - `updated` requires `docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md` plus each
    declared Spec A/B1/B2 owning `tasks.md` ledger (`process` has no ledger).
  - `none` requires a non-empty same-line rationale and rejects HTML placeholders,
    checklist text, and concurrent master-plan/ledger edits (conflict).

This closes path-only bypasses: feature UI, composition controllers, and CI
workflow edits are not exempt. The PR body is inspected directly; if it is
unavailable, fail closed — do not degrade to a path-only approximation.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass

MASTER_PLAN = "docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md"

IMPACT_LINE = re.compile(r"(?im)^[ \t]*Master-plan impact:[ \t]*(.*?)[ \t]*$")

UPDATED_VALUE = re.compile(
    r"(?i)^updated\s*[—–-]\s*(.+)$"
)
NONE_VALUE = re.compile(r"(?i)^none\s*:\s*(.+)$")
# `none` without a same-line rationale (bare `none`, em-dash forms, etc.)
NONE_BARE = re.compile(r"(?i)^none(?:\s*[—–-]\s*)?$")

PLACEHOLDER_RATIONALE = re.compile(
    r"(?i)(<!--|-->|^\s*-\s*\[[ xX]\]|placeholder|TODO\b|TBD\b|\bN/?A\b|"
    r"add a description)"
)

TRACK_ALIASES = {
    "spec a": "spec-a",
    "spec-a": "spec-a",
    "a": "spec-a",
    "b1": "b1",
    "b2": "b2",
    "process": "process",
}

TRACK_LEDGERS = {
    "spec-a": ".kiro/specs/supported-asset-integrity/tasks.md",
    "b1": ".kiro/specs/portfolio-composition-contract/tasks.md",
    "b2": ".kiro/specs/asset-picker-composition/tasks.md",
}

TRACK_LABELS = {
    "spec-a": "Spec A",
    "b1": "B1",
    "b2": "B2",
    "process": "process",
}


@dataclass(frozen=True)
class ImpactDeclaration:
    kind: str  # "updated" | "none"
    tracks: tuple[str, ...]
    rationale: str


class GuardError(ValueError):
    """Raised when a PR lacks a valid status-propagation declaration/outcome."""


def normalize_files(changed_files: list[str] | tuple[str, ...] | None) -> list[str]:
    files: list[str] = []
    for raw in changed_files or ():
        path = raw.strip().replace("\\", "/")
        if path:
            files.append(path)
    return files


def parse_tracks(raw: str) -> tuple[str, ...]:
    parts = [part.strip() for part in raw.split(",")]
    parts = [part for part in parts if part]
    if not parts:
        raise GuardError(
            "Malformed Master-plan impact declaration: `updated — <tracks>` requires "
            "at least one track from {Spec A, B1, B2, process}."
        )
    resolved: list[str] = []
    unknown: list[str] = []
    for part in parts:
        key = TRACK_ALIASES.get(part.lower())
        if key is None:
            unknown.append(part)
            continue
        if key not in resolved:
            resolved.append(key)
    if unknown:
        raise GuardError(
            "Malformed Master-plan impact declaration: unknown track(s) "
            f"{unknown!r}. Allowed: Spec A, B1, B2, process."
        )
    return tuple(resolved)


def rationale_is_placeholder(rationale: str) -> bool:
    text = rationale.strip()
    if not text:
        return True
    if PLACEHOLDER_RATIONALE.search(text):
        return True
    # Reject rationale that is only punctuation / checkbox debris.
    alnum = re.sub(r"[^a-zA-Z0-9]+", "", text)
    return len(alnum) < 12


def parse_impact_declaration(pr_body: str | None) -> ImpactDeclaration:
    if pr_body is None:
        raise GuardError(
            "PR body is unavailable; refusing to degrade to a path-only approximation. "
            "Every PR needs exactly one `Master-plan impact:` declaration."
        )

    matches = list(IMPACT_LINE.finditer(pr_body))
    if not matches:
        raise GuardError(
            "Missing Master-plan impact declaration. Every PR must include exactly one "
            "of: `Master-plan impact: updated — <tracks>` or "
            "`Master-plan impact: none: <same-line rationale>`."
        )
    if len(matches) > 1:
        raise GuardError(
            f"Duplicate Master-plan impact declarations ({len(matches)} found). "
            "Require exactly one canonical declaration; remove extras or conflicts."
        )

    value = matches[0].group(1).strip()
    if not value:
        raise GuardError(
            "Malformed Master-plan impact declaration: empty value after the colon."
        )

    updated = UPDATED_VALUE.match(value)
    if updated:
        tracks = parse_tracks(updated.group(1))
        return ImpactDeclaration(kind="updated", tracks=tracks, rationale="")

    none = NONE_VALUE.match(value)
    if none:
        rationale = none.group(1).strip()
        if rationale_is_placeholder(rationale):
            raise GuardError(
                "Master-plan impact: none requires a non-empty same-line rationale "
                "that is not an HTML placeholder, checklist item, or stub."
            )
        return ImpactDeclaration(kind="none", tracks=(), rationale=rationale)

    if NONE_BARE.match(value) or re.match(r"(?i)^none\b", value):
        raise GuardError(
            "Malformed Master-plan impact declaration: `none` requires a same-line "
            "rationale using `Master-plan impact: none: <rationale>`."
        )

    raise GuardError(
        "Malformed Master-plan impact declaration: expected "
        "`updated — <tracks>` or `none: <same-line rationale>`."
    )


def status_paths_in(files: list[str]) -> list[str]:
    watched = {MASTER_PLAN, *TRACK_LEDGERS.values()}
    return [path for path in files if path in watched]


def evaluate_status_propagation(
    *,
    changed_files: list[str] | tuple[str, ...] | None,
    pr_body: str | None,
) -> None:
    files = normalize_files(changed_files)
    declaration = parse_impact_declaration(pr_body)

    if declaration.kind == "none":
        conflicting = status_paths_in(files)
        if conflicting:
            raise GuardError(
                "Conflicting Master-plan impact: declared `none` but also changed "
                f"status surfaces {conflicting}. Use `updated — <tracks>` when the "
                "living master plan or an owning task ledger changes."
            )
        return

    missing: list[str] = []
    if MASTER_PLAN not in files:
        missing.append(f"update `{MASTER_PLAN}`")

    for track in declaration.tracks:
        ledger = TRACK_LEDGERS.get(track)
        if ledger and ledger not in files:
            missing.append(
                f"update owning ledger `{ledger}` for declared {TRACK_LABELS[track]}"
            )

    if missing:
        raise GuardError(
            "Master-plan impact: updated requires matching file updates. Missing: "
            + "; ".join(missing)
            + "."
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
        description="Enforce Asset Picker master-plan/status propagation on every PR."
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
        f"({len(changed)} changed paths)."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except GuardError as err:
        print(f"ERROR: {err}", file=sys.stderr)
        raise SystemExit(1)
