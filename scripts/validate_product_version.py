#!/usr/bin/env python3
"""Validate the root product Semantic Version contract.

One product version lives in root `VERSION`; Gradle, the private frontend package, the changelog
and release tags all derive from it. This script is the enforcement point, run by required CI in
branch mode and by the release-tag workflow in tag mode.

It fails closed. A missing file, bad encoding, malformed JSON, drifted mirror, missing changelog
entry, unknown ref type, or a tag that is not exactly `v<VERSION>` is an error, never a skip --
a validator that passes when it cannot see the thing it validates is worse than no validator.

Ref type is always explicit (`--ref-type branch|tag`). There is deliberately no environment-derived
default: an empty or renamed `GITHUB_REF_TYPE` must not be able to silently downgrade a tag check
to a branch check.

Stdlib only, so it runs on a bare `actions/setup-python` step with no install.
"""

from __future__ import annotations

import argparse
import re
import sys
from datetime import date
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?$"
)


# A changelog's fenced examples and HTML comments are documentation, not release entries.
# Leaving them in lets a dated *example* stand in for a real dated heading, which is how an
# undated release reaches a tag with a green validator.
_HTML_COMMENT = re.compile(r"(?s)<!--.*?-->")
_FENCE_OPEN = re.compile(r"^( {0,3})(`{3,}|~{3,})(.*)$")
_FENCE_CLOSE = re.compile(r"^ {0,3}(`{3,}|~{3,})[ \t]*$")


def _strip_fenced_blocks(text: str) -> tuple[str, bool]:
    """Blank out CommonMark fenced code blocks, preserving line positions.

    Returns the stripped text and whether a fence was left open at end of document.

    A scanner rather than a regex because the regex form diverged from CommonMark in ways
    that changed the verdict: it ignored `~~~` fences and indented openers, and it required
    the closer to be exactly three backticks. The dangerous direction is a closer recognised
    LATER than CommonMark recognises it -- the strip then swallows the real release heading,
    and a stale dated duplicate is left as the only match, turning a fail-closed duplicate
    into a green tag on an undated release.

    An unterminated fence is reported rather than tolerated. CommonMark runs it to end of
    document, which silently puts the real release entry inside a code block; a changelog
    that does that is malformed, and saying so beats inferring a verdict from the wreckage.
    """
    out: list[str] = []
    fence: tuple[str, int] | None = None
    for line in text.split("\n"):
        if fence is None:
            opener = _FENCE_OPEN.match(line)
            # A backtick opener's info string may not itself contain a backtick.
            if opener is not None and not (
                opener.group(2)[0] == "`" and "`" in opener.group(3)
            ):
                fence = (opener.group(2)[0], len(opener.group(2)))
                out.append("")
                continue
            out.append(line)
            continue
        closer = _FENCE_CLOSE.match(line)
        if (
            closer is not None
            and closer.group(1)[0] == fence[0]
            and len(closer.group(1)) >= fence[1]
        ):
            fence = None
        out.append("")
    return "\n".join(out), fence is not None


# The images whose builder stage evaluates root build.gradle, and therefore needs root
# VERSION in its build context. An explicit allowlist rather than a scan: it documents which
# images consume the root build, and a new service that forgets VERSION shows up as a missing
# entry during review rather than being silently excluded by a clever pattern.
#
# Dockerfile.candidate and Dockerfile.slim-it are deliberately absent. They package an
# already staged JAR (Gradle's archiveFile provider, renamed to a fixed `<project>.jar`), so
# they never evaluate root Gradle configuration and are unaffected by the product version.
GRADLE_DOCKERFILES = (
    "api-gateway/Dockerfile",
    "api-gateway/Dockerfile.azure",
    "portfolio-service/Dockerfile",
    "portfolio-service/Dockerfile.azure",
    "market-data-service/Dockerfile",
    "market-data-service/Dockerfile.azure",
    "insight-service/Dockerfile",
    "insight-service/Dockerfile.azure",
)

# Docker instruction KEYWORDS are case-insensitive and may be indented; their ARGUMENTS are
# case-sensitive, so `copy VERSION VERSION` is valid Docker but `COPY version version` is a
# different (nonexistent) file. The regexes mirror that split exactly: `(?i:...)` covers only
# the keyword. Any other spelling -- a multi-source COPY, `COPY VERSION ./`, `COPY . .` --
# fails closed rather than being guessed at.
_COPY_BUILD_GRADLE = re.compile(
    r"(?m)^[ \t]*(?i:COPY)[ \t]+build\.gradle[ \t]+build\.gradle[ \t]*$"
)
_COPY_VERSION = re.compile(r"(?m)^[ \t]*(?i:COPY)[ \t]+VERSION[ \t]+VERSION[ \t]*$")
_RUN_LINE = re.compile(r"(?i)^[ \t]*RUN\b")
_FROM_LINE = re.compile(r"(?mi)^[ \t]*FROM\s")

# A gradlew INVOCATION, not merely the token. `chmod +x gradlew` mentions gradlew and does not
# configure the build; treating it as the build step would put the ordering boundary in the
# wrong place and reject a correct Dockerfile. A command segment is an invocation only when
# gradlew is the command being run.
_COMMAND_SEPARATOR = re.compile(r"&&|\|\||[;|]")
_GRADLEW_COMMAND = re.compile(
    r"^(?:(?:ba)?sh[ \t]+(?:-[A-Za-z]+[ \t]+)?)?[\'\"]?(?:[\w./\-]*/)?gradlew\b"
)


class ContractError(ValueError):
    pass


def _without_dockerfile_comments(text: str) -> str:
    """Blank whole-line comments, preserving line positions.

    A commented-out `# COPY VERSION VERSION` must not satisfy the check, and a commented
    `# && ./gradlew ...` inside a RUN continuation must not move the ordering boundary.
    """
    return "\n".join(
        "" if line.lstrip().startswith("#") else line for line in text.split("\n")
    )


def _invokes_gradlew(block: str) -> bool:
    body = re.sub(r"(?i)^[ \t]*RUN[ \t]+", "", block, count=1)
    body = re.sub(r"\\\s*\n", " ", body)
    return any(
        _GRADLEW_COMMAND.match(segment.strip())
        for segment in _COMMAND_SEPARATOR.split(body)
    )


def _stages(text: str) -> list[str]:
    """Split a Dockerfile into its build stages, one string per FROM."""
    starts = [match.start() for match in _FROM_LINE.finditer(text)]
    if not starts:
        return [text]
    bounds = list(zip(starts, starts[1:] + [len(text)]))
    return [text[start:end] for start, end in bounds]


def _first_gradle_run_offset(text: str) -> int | None:
    """Offset of the first RUN block that invokes gradlew, continuations included.

    Line-by-line rather than a single regex because the real Dockerfiles split the build
    across a backslash continuation (`RUN chmod +x gradlew \\` then `&& ./gradlew ...`).
    Matching only the RUN line would put the boundary in the wrong place.
    """
    lines = text.splitlines(keepends=True)
    offset = 0
    index = 0
    while index < len(lines):
        start = offset
        block = lines[index]
        offset += len(lines[index])
        if _RUN_LINE.match(block):
            while block.rstrip("\r\n").endswith("\\") and index + 1 < len(lines):
                index += 1
                block += lines[index]
                offset += len(lines[index])
            if _invokes_gradlew(block):
                return start
        index += 1
    return None


def validate_gradle_docker_contexts(root: Path) -> None:
    """Require VERSION in every build STAGE that evaluates root Gradle configuration.

    Per stage, not per file. A Dockerfile whose first stage copies VERSION and whose second,
    independent stage runs the real build would satisfy a file-level check while Gradle
    configures without VERSION in the stage that matters -- the check would be measuring
    "the line appears somewhere in the file", not "VERSION reaches Gradle".

    Ordering is equally load-bearing: a COPY placed after the build step is in the file and
    does nothing, because Gradle has already configured.
    """
    problems: list[str] = []
    for relative in GRADLE_DOCKERFILES:
        path = root / relative
        try:
            raw = path.read_text(encoding="utf-8")
        except OSError as exc:
            problems.append(f"{relative}: cannot read: {exc}")
            continue
        except UnicodeDecodeError as exc:
            problems.append(f"{relative}: must be UTF-8: {exc}")
            continue
        text = _without_dockerfile_comments(raw)
        building_stages = 0
        for number, stage in enumerate(_stages(text), start=1):
            run_at = _first_gradle_run_offset(stage)
            if run_at is None:
                continue
            building_stages += 1
            where = f"{relative} (stage {number})"
            gradle_copy = _COPY_BUILD_GRADLE.search(stage)
            if gradle_copy is None or gradle_copy.start() > run_at:
                problems.append(
                    f"{where}: runs gradlew without 'COPY build.gradle build.gradle' "
                    f"earlier in the same stage"
                )
                continue
            version_copy = _COPY_VERSION.search(stage)
            if version_copy is None:
                problems.append(f"{where}: missing 'COPY VERSION VERSION'")
            elif version_copy.start() < gradle_copy.start():
                problems.append(
                    f"{where}: 'COPY VERSION VERSION' precedes the build.gradle copy"
                )
            elif version_copy.start() > run_at:
                problems.append(
                    f"{where}: 'COPY VERSION VERSION' occurs after the gradlew RUN block, "
                    f"so Gradle configures without it"
                )
        if building_stages == 0:
            problems.append(f"{relative}: no RUN block invoking gradlew")
    if problems:
        raise ContractError(
            "Gradle-evaluating Docker build contexts are missing root VERSION:\n  "
            + "\n  ".join(problems)
        )


def read_product_version(root: Path) -> str:
    path = root / "VERSION"
    try:
        data = path.read_bytes()
    except OSError as exc:
        raise ContractError(f"{path}: cannot read VERSION: {exc}") from exc
    if data.startswith(b"\xef\xbb\xbf"):
        raise ContractError(f"{path}: UTF-8 BOM is forbidden")
    if b"\r" in data:
        raise ContractError(f"{path}: CR is forbidden; use LF")
    if not data.endswith(b"\n") or data.count(b"\n") != 1:
        raise ContractError(f"{path}: require exactly one line ending in one LF")
    try:
        version = data[:-1].decode("ascii")
    except UnicodeDecodeError as exc:
        raise ContractError(f"{path}: VERSION must be ASCII") from exc
    if not SEMVER.fullmatch(version):
        raise ContractError(
            f"{path}: {version!r} is not canonical SemVer without build metadata"
        )
    return version


def validate_changelog(root: Path, version: str, require_released: bool) -> None:
    path = root / "CHANGELOG.md"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ContractError(f"{path}: cannot read changelog: {exc}") from exc
    except UnicodeDecodeError as exc:
        raise ContractError(f"{path}: changelog must be UTF-8: {exc}") from exc
    stripped, unterminated_fence = _strip_fenced_blocks(text)
    if unterminated_fence:
        raise ContractError(
            f"{path}: unterminated code fence; the release entry may be inside it"
        )
    prose = _HTML_COMMENT.sub("", stripped)
    # Two counts over the same text: every line that *looks* like this version's heading, and
    # every line that actually conforms. Comparing them is what separates "the entry is
    # absent" from "the entry is there but malformed" -- reporting the latter as "missing"
    # sends a release operator hunting for a heading they can plainly see.
    heading_like = len(
        re.findall(rf"(?m)^[ \t]*#{{1,6}}[ \t]*\[{re.escape(version)}\]", prose)
    )
    # Every conforming heading, not the first one found. `re.search` would accept a stale
    # duplicate sitting above the real `Unreleased` entry; requiring exactly one occurrence
    # makes the check measure the release entry rather than "some dated-looking line exists".
    # `[0-9]` rather than `\d`: `\d` also admits Unicode digits, which `date.fromisoformat`
    # would then reject with a confusing message.
    matches = list(
        re.finditer(
            rf"(?m)^## \[{re.escape(version)}\] - "
            rf"(Unreleased|[0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}})$",
            prose,
        )
    )
    if heading_like != len(matches):
        raise ContractError(
            f"{path}: malformed release heading for {version}: {heading_like} heading-like "
            f"line(s) but {len(matches)} conforming to "
            f"'## [{version}] - <Unreleased|YYYY-MM-DD>'"
        )
    if not matches:
        raise ContractError(f"{path}: missing release heading for {version}")
    if len(matches) != 1:
        raise ContractError(
            f"{path}: expected exactly one release heading for {version}, "
            f"found {len(matches)}"
        )
    state = matches[0].group(1)
    if state == "Unreleased":
        if require_released:
            raise ContractError(f"{path}: {version} must be dated before tagging")
        return
    # Checked in both modes: `2026-13-45` has the right shape and is not a date.
    try:
        date.fromisoformat(state)
    except ValueError as exc:
        raise ContractError(
            f"{path}: {version} heading date {state!r} is not a real calendar date"
        ) from exc


def validate_release_ref(version: str, ref_type: str, ref_name: str) -> None:
    if ref_type not in {"branch", "tag"}:
        raise ContractError(f"ref type must be exactly 'branch' or 'tag', got {ref_type!r}")
    if ref_type == "tag" and not ref_name:
        raise ContractError("tag validation requires a non-empty ref name")
    if ref_type == "tag" and ref_name != f"v{version}":
        raise ContractError(f"release tag must be exactly v{version}, got {ref_name!r}")


def validate_repository(root: Path, ref_type: str, ref_name: str = "") -> str:
    version = read_product_version(root)
    validate_release_ref(version, ref_type, ref_name)
    validate_changelog(root, version, require_released=ref_type == "tag")
    validate_gradle_docker_contexts(root)
    return version


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=REPO)
    parser.add_argument("--ref-type", choices=("branch", "tag"), required=True)
    parser.add_argument("--ref-name", default="")
    args = parser.parse_args(argv)
    try:
        version = validate_repository(args.root.resolve(), args.ref_type, args.ref_name)
    except ContractError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: product version {version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
