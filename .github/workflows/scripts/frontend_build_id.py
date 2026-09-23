#!/usr/bin/env python3
"""Derive the Next.js build id from the built static export the deploy job is uploading.

SCOPE. This module covers frontend build identity end to end: it derives the build id of the
artifact being uploaded, reads the id an origin is serving, and holds the predicate that
decides whether the origin serves the build a run uploaded. It does NOT establish anything
about the CONTENT of API responses (D11's fields are A4's job), and the served-side modes
make outbound requests to a public page, so the workflow steps that use them are gated on
the owner's approval for those requests.

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
import importlib.util
import json
import os
import re
import sys
import time
import urllib.request
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


SERVED_EXTRACTOR_RELPATH = ("scripts", "verify_step_b_5b.py")
# Hard ceiling on served-page requests. Callers pass a far smaller bound; this only stops a
# caller from turning the propagation wait into an unbounded poll of the public site.
MAX_FETCH_ATTEMPTS = 25
HTTP_TIMEOUT_SECONDS = 20


def load_served_extractor(repo_root: str | Path):
    """`verify_step_b_5b.extract_build_id`, so there is exactly ONE served-side extractor.

    Loaded by explicit file path rather than by import, because the deploy steps run
    `python3 -I`, which keeps the script's own directory off ``sys.path``. Fails closed on
    purpose: with no local fallback copy, there is nothing that could silently drift away
    from the reviewed implementation.
    """
    path = Path(repo_root).joinpath(*SERVED_EXTRACTOR_RELPATH)
    if not path.is_file():
        raise ValueError(
            f"shared extractor scripts/verify_step_b_5b.py not found at {path}; refusing to "
            "fall back to a second implementation"
        )
    spec = importlib.util.spec_from_file_location("_shared_served_build_id", path)
    if spec is None or spec.loader is None:
        raise ValueError(f"could not load scripts/verify_step_b_5b.py from {path}")
    module = importlib.util.module_from_spec(spec)
    # Registered BEFORE execution: @dataclasses.dataclass resolves its class's module through
    # sys.modules while the module body runs, and the shared module defines frozen dataclasses.
    sys.modules[spec.name] = module
    try:
        spec.loader.exec_module(module)
    except Exception as exc:
        sys.modules.pop(spec.name, None)
        raise ValueError(f"could not execute scripts/verify_step_b_5b.py: {exc}") from exc
    extractor = getattr(module, "extract_build_id", None)
    if not callable(extractor):
        raise ValueError("scripts/verify_step_b_5b.py does not expose extract_build_id")
    return extractor


def build_id_from_html(html: str, repo_root: str | Path) -> str:
    """The build id a page is actually serving, via the shared extractor."""
    extractor = load_served_extractor(repo_root)
    try:
        return extractor(html)
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError(f"could not read a build id from the served page: {exc}") from exc


class ServedBuildIdError(ValueError):
    """A served-page read that must not be retried, with the requests already spent."""

    def __init__(self, message: str, requests_made: int) -> None:
        super().__init__(message)
        self.requests_made = requests_made


class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    """Refuse every 3xx: one counted request must be exactly one GET of one origin.

    ``urlopen`` follows redirects by default, so a single "attempt" could issue several GETs
    and the page finally read need not even come from the origin that was asked for. That
    would make the run's stated request budget and its single-origin claim untrue.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        raise ServedBuildIdError(
            f"refusing to follow an HTTP {code} redirect to {newurl!r}: a served-build read "
            "must be one GET of the origin it names",
            1,
        )


_OPENER = urllib.request.build_opener(_RefuseRedirects)


def _http_get(url: str) -> str:
    with _OPENER.open(url, timeout=HTTP_TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8", errors="replace")


def fetch_served_build_id(
    url: str,
    pre_deploy_build_id: str,
    repo_root: str | Path,
    *,
    attempts: int,
    delay_seconds: float,
    fetch=None,
    sleep=None,
) -> tuple[str | None, int]:
    """Read the origin's build id, waiting a bounded time for the upload to propagate.

    Returns ``(build id, requests made)``. A retry means exactly one thing: the origin
    positively served the *pre-deploy* build id, so the upload has not propagated yet. Every
    other outcome — a transport error, a redirect, an unreadable page, an ambiguous page —
    raises :class:`ServedBuildIdError` on the spot rather than consuming the budget, because
    retrying a fault lets a later well-formed page hide it.

    The loop never judges the result: the verdict is :func:`frontend_serving_errors` applied
    once to whatever came back. Exhausting the bound returns the last id seen, which then
    fails that predicate rather than passing quietly.
    """
    if isinstance(attempts, bool) or not isinstance(attempts, int) or not 1 <= attempts <= MAX_FETCH_ATTEMPTS:
        raise ValueError(
            f"attempts must be an integer between 1 and {MAX_FETCH_ATTEMPTS}; got {attempts!r}"
        )
    fetch = fetch or _http_get
    sleep = sleep or time.sleep
    extractor = load_served_extractor(repo_root)
    pre = (pre_deploy_build_id or "").strip()
    served: str | None = None
    used = 0
    for index in range(attempts):
        used = index + 1
        try:
            html = fetch(url)
        except ServedBuildIdError as exc:
            raise ServedBuildIdError(str(exc), used) from exc
        except Exception as exc:
            raise ServedBuildIdError(
                f"could not read {url}: {type(exc).__name__}: {exc}", used
            ) from exc
        try:
            candidate = extractor(html)
        except Exception as exc:
            # An unreadable or ambiguous page is a fault in what the origin served, not a
            # sign that the upload is still propagating. Retrying it would spend the
            # request budget and let a later well-formed page mask the fault entirely.
            raise ServedBuildIdError(
                f"could not read a build id from {url}: {type(exc).__name__}: {exc}", used
            ) from exc
        served = candidate
        if candidate != pre:
            break
        if used < attempts:
            sleep(delay_seconds)
    return served, used


def frontend_serving_errors(emitted: str, pre_deploy: str, served: str) -> list[str]:
    """Why the origin is not serving the build this run uploaded, or [] if it is.

    Both halves are required. ``served == emitted`` alone passes trivially when the origin
    already served that build before the deploy, so the change from the pre-deploy id is
    what shows the deploy took effect. A no-op re-upload is reported rather than passed.
    """
    emitted = (emitted or "").strip()
    pre = (pre_deploy or "").strip()
    served = (served or "").strip()
    if not emitted:
        return [
            "no emitted build id: the deploy run published no build id for the served "
            "frontend to be compared against"
        ]
    if not pre:
        return [
            "no pre-deploy served build id: without it this run cannot show that what the "
            "origin serves actually changed"
        ]
    if not served:
        return ["no post-deploy served build id could be read from the frontend origin"]
    errors: list[str] = []
    if served != emitted:
        errors.append(
            f"post-deploy served build id {served!r} does not equal the emitted build id "
            f"{emitted!r}: the origin is not serving the build this run uploaded"
        )
    if served == pre:
        errors.append(
            f"post-deploy served build id {served!r} equals the pre-deploy served build id: "
            "the deploy did not change what the origin serves (either it did not take "
            "effect, or the same build was re-uploaded)"
        )
    return errors


def _write_output(name: str, value: str) -> None:
    """Append a step output when running under Actions; a no-op locally."""
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(f"{name}={value}\n")


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
    # Exactly one mode per invocation: a call that names none, or names two, is a caller
    # error rather than something to resolve by precedence.
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--export-dir",
        help="emit the build id of this built Next static export (the one being uploaded)",
    )
    mode.add_argument(
        "--capture-served",
        action="store_true",
        help="read the build id the origin serves right now (one request)",
    )
    mode.add_argument(
        "--verify-served",
        action="store_true",
        help="check the origin now serves the emitted build, and write run-bound evidence",
    )
    parser.add_argument("--url", default="", help="frontend page to read, e.g. <origin>/login")
    parser.add_argument("--emitted-id", default="", help="build id this run uploaded")
    parser.add_argument("--pre-deploy-id", default="", help="build id served before the upload")
    parser.add_argument("--attempts", type=int, default=5, help="bounded requests while the origin still serves the pre-deploy build")
    parser.add_argument("--delay-seconds", type=float, default=10.0)
    parser.add_argument("--evidence-out", default="", help="path for the JSON evidence record")
    parser.add_argument("--repo-root", default=".", help="repo root holding scripts/verify_step_b_5b.py")
    return parser


def _require(args, names: tuple[str, ...]) -> str | None:
    missing = [f"--{name.replace('_', '-')}" for name in names if not getattr(args, name)]
    return f"missing required argument(s): {', '.join(missing)}" if missing else None


def _capture_served(args) -> int:
    problem = _require(args, ("url",))
    if problem:
        print(f"::error::--capture-served {problem}", file=sys.stderr)
        return 1
    try:
        # pre-deploy id is empty here, so the first id read differs from it and the loop
        # stops immediately: exactly one request, and no redirect.
        served, _ = fetch_served_build_id(
            args.url, "", args.repo_root, attempts=1, delay_seconds=0
        )
    except ValueError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    if not served:
        print(
            f"::error::no build id could be read from {args.url}; refusing to report an "
            "unknown pre-deploy build",
            file=sys.stderr,
        )
        return 1
    print(served)
    _write_output("served_build_id", served)
    return 0


def _verify_served(args) -> int:
    problem = _require(args, ("url", "emitted_id", "pre_deploy_id", "evidence_out"))
    if problem:
        print(f"::error::--verify-served {problem}", file=sys.stderr)
        return 1
    fetch_fault: str | None = None
    try:
        served, requests_made = fetch_served_build_id(
            args.url,
            args.pre_deploy_id,
            args.repo_root,
            attempts=args.attempts,
            delay_seconds=args.delay_seconds,
        )
    except ServedBuildIdError as exc:
        # Recorded, not swallowed: the evidence must say why no served id was established.
        served, requests_made, fetch_fault = None, exc.requests_made, str(exc)
    except ValueError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    errors = frontend_serving_errors(args.emitted_id, args.pre_deploy_id, served or "")
    if fetch_fault:
        errors = [fetch_fault, *errors]
    record = {
        "schema": "b3-frontend-build-id/1",
        "frontend_url": args.url,
        "emitted_build_id": args.emitted_id,
        "pre_deploy_served_build_id": args.pre_deploy_id,
        "post_deploy_served_build_id": served,
        "requests_made": requests_made,
        "attempt_bound": args.attempts,
        "run_id": os.environ.get("GITHUB_RUN_ID", ""),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
        "commit_sha": os.environ.get("GITHUB_SHA", ""),
        "verdict": "FAIL" if errors else "PASS",
        # Only a PASS may feed A4. The record is uploaded even on failure, so its EXISTENCE
        # proves nothing: A4 must require verdict == PASS, this run's own conclusion to be
        # success, and the separate backend deployment proof.
        "usable_for_a4": not errors,
        "errors": errors,
    }
    out = Path(args.evidence_out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for error in errors:
        print(f"::error::{error}", file=sys.stderr)
    if errors:
        return 1
    print(f"origin serves the emitted build {args.emitted_id} after {requests_made} request(s)")
    _write_output("served_build_id", served or "")
    return 0


def main() -> int:
    args = _parser().parse_args()
    if args.capture_served:
        return _capture_served(args)
    if args.verify_served:
        return _verify_served(args)
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
