#!/usr/bin/env python3
"""Derive the Next.js build id from the built static export the deploy job is uploading.

SCOPE: emission only. This module derives and publishes the build id of the artifact being
uploaded, and provides the comparison predicate. It does NOT fetch the deployed origin, and
no workflow here wires the comparison: reading the served id requires a separately approved
request to the public site. Until that consumer exists, this establishes build identity at
the point of upload and nothing about what the origin is serving.

The deployed frontend can only be proven to be *this* build if the expected build id comes
from somewhere other than the deployed page. Reading it off the served HTML and then
comparing it with the served HTML proves nothing. So the build job publishes the id of the
artifact it uploads, for the served id to be checked against later.

`scripts/verify_step_b_5b.py` extracts the build id from the SERVED html (its
``extract_build_id``). This module extracts it from the BUILT artifact, using the same
charset and reserved-directory set, so neither value is derived from the other.

Next generates the build id at build time (`frontend/next.config.ts` sets no
``generateBuildId``), so it cannot be predicted from the commit and has to be read out of
the export. Every failure is fail-closed: an unreadable or ambiguous export yields no id
rather than a guess.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

# Mirrors scripts/verify_step_b_5b.py: _STATIC_DIR_ID_RE's charset/length and
# _STATIC_RESERVED_DIRS. `out/_next/static/` holds the build id directory beside these
# fixed-name asset directories.
BUILD_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,64}$")
RESERVED_DIRS = frozenset({"chunks", "css", "media", "webpack", "development"})


def build_id_from_export(export_dir: str) -> str:
    """The build id of a Next static export, or ValueError if it is not unambiguous.

    Only directories count: a stray file in ``_next/static`` is not a build id. Exactly one
    non-reserved directory must be present — none means the export is not a Next export (or
    the layout changed), and more than one means the id cannot be attributed to this build.
    """
    static = Path(export_dir) / "_next" / "static"
    if not static.is_dir():
        raise ValueError(f"no _next/static directory in the export at {export_dir}")
    candidates = sorted(
        entry.name
        for entry in static.iterdir()
        if entry.is_dir() and entry.name not in RESERVED_DIRS
    )
    if not candidates:
        raise ValueError(
            f"no build id directory under _next/static in the export at {export_dir}"
        )
    if len(candidates) > 1:
        raise ValueError(
            "ambiguous build id: _next/static holds more than one non-reserved directory "
            f"{candidates} in the export at {export_dir}"
        )
    build_id = candidates[0]
    if not BUILD_ID_RE.fullmatch(build_id):
        raise ValueError(f"{build_id!r} is not a valid Next build id")
    return build_id


def served_build_id_errors(emitted: str, served: str) -> list[str]:
    """Why the served frontend is not the build this run uploaded, or [] if it is.

    A blank emitted id is an error in its own right: with no independent expectation, any
    served id would trivially "match".
    """
    emitted = (emitted or "").strip()
    served = (served or "").strip()
    if not emitted:
        return [
            "no emitted build id: the deploy run published no build id for the served "
            "frontend to be compared against"
        ]
    if not served:
        return ["no served build id could be read from the frontend origin"]
    if served != emitted:
        return [
            f"served build id {served!r} does not equal emitted build id {emitted!r}: "
            "the origin is not serving the build this run uploaded"
        ]
    return []


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--export-dir",
        required=True,
        help="the built Next static export directory (the one being uploaded)",
    )
    return parser


def main() -> int:
    args = _parser().parse_args()
    try:
        build_id = build_id_from_export(args.export_dir)
    except ValueError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    print(build_id)
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(f"build_id={build_id}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
