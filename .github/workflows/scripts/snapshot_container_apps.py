#!/usr/bin/env python3
"""Snapshot and compare Azure Container App identity for Wave P non-interference.

Captures revision name, image reference and ingress traffic for each backend
app, plus the market-data-refresh-job image. Unselected targets must be
byte-identical before and after a scoped deploy; selected targets must be
updated to the committing SHA.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import re
from typing import Any, Callable

KNOWN_SERVICES = (
    "api-gateway",
    "portfolio-service",
    "market-data-service",
    "insight-service",
)
REFRESH_JOB = "market-data-refresh-job"
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")

RunAz = Callable[[list[str]], subprocess.CompletedProcess[str]]


def _run_az(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["az", *args],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


def _canonical(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _canonical(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [_canonical(item) for item in value]
    return value


def _app_identity(name: str, resource_group: str, run_az: RunAz) -> dict[str, Any]:
    result = run_az(
        [
            "containerapp",
            "show",
            "--name",
            name,
            "--resource-group",
            resource_group,
            "--query",
            "{revision:properties.latestRevisionName,image:properties.template.containers[0].image,traffic:properties.configuration.ingress.traffic}",
            "-o",
            "json",
        ]
    )
    if result.returncode != 0:
        return {"missing": True, "error": (result.stderr or result.stdout).strip()}
    payload = json.loads(result.stdout)
    return _canonical(payload)


def _job_identity(resource_group: str, run_az: RunAz) -> dict[str, Any]:
    result = run_az(
        [
            "containerapp",
            "job",
            "show",
            "--name",
            REFRESH_JOB,
            "--resource-group",
            resource_group,
            "--query",
            "{image:properties.template.containers[0].image}",
            "-o",
            "json",
        ]
    )
    if result.returncode != 0:
        return {"missing": True, "error": (result.stderr or result.stdout).strip()}
    return _canonical(json.loads(result.stdout))


def capture(resource_group: str, run_az: RunAz = _run_az) -> dict[str, Any]:
    snapshot: dict[str, Any] = {
        name: _app_identity(name, resource_group, run_az) for name in KNOWN_SERVICES
    }
    snapshot[REFRESH_JOB] = _job_identity(resource_group, run_az)
    return snapshot


def compare(
    before: dict[str, Any],
    after: dict[str, Any],
    selected: list[str],
    git_sha: str | None = None,
    requested_digest: str | None = None,
) -> list[str]:
    errors: list[str] = []
    selected_set = set(selected)
    digest = (requested_digest or "").strip() or None
    sha = (git_sha or "").strip() or None
    if digest:
        marker, marker_label = digest, "digest"
    elif sha:
        marker, marker_label = sha, "git sha"
    else:
        marker, marker_label = None, "digest or git sha"
    for name in KNOWN_SERVICES:
        if name in selected_set:
            image = str(after.get(name, {}).get("image", ""))
            if not marker:
                errors.append(
                    f"selected {name} image {image!r} cannot be checked: "
                    "neither digest nor git sha was provided"
                )
            elif marker not in image:
                errors.append(
                    f"selected {name} image {image!r} does not contain {marker_label} {marker}"
                )
        elif before.get(name) != after.get(name):
            errors.append(
                f"unselected {name} changed: {json.dumps(before.get(name))} -> {json.dumps(after.get(name))}"
            )

    if "market-data-service" in selected_set:
        after_job = after.get(REFRESH_JOB) or {}
        if git_sha and not after_job.get("missing"):
            image = str(after_job.get("image", ""))
            if git_sha not in image:
                errors.append(
                    f"selected {REFRESH_JOB} image {image!r} does not contain git sha {git_sha}"
                )
    elif before.get(REFRESH_JOB) != after.get(REFRESH_JOB):
        errors.append(
            f"unselected {REFRESH_JOB} changed: {json.dumps(before.get(REFRESH_JOB))} -> {json.dumps(after.get(REFRESH_JOB))}"
        )
    return errors


def aggregate_digests(digest_root: str, selected: list[str], output: str | None) -> dict[str, str]:
    root = os.path.abspath(digest_root)
    selected_set = set(selected)
    if len(selected_set) != len(selected) or not selected_set:
        raise ValueError("selected services must be non-empty and unique")
    if not selected_set.issubset(set(KNOWN_SERVICES)):
        raise ValueError("selected services contain unknown entries")
    expected = selected_set | ({REFRESH_JOB} if "market-data-service" in selected_set else set())
    actual = {entry.name for entry in os.scandir(root) if entry.is_dir()}
    if actual != expected:
        raise ValueError(f"digest service set mismatch: expected {sorted(expected)}, found {sorted(actual)}")
    manifest: dict[str, str] = {}
    for service in selected:
        files = [entry for entry in os.scandir(os.path.join(root, service)) if entry.is_file()]
        if len(files) != 1 or files[0].name != "digest.txt":
            raise ValueError(f"{service} must contain exactly one digest.txt")
        with open(files[0].path, encoding="utf-8") as handle:
            digest = handle.read().strip()
        if not DIGEST_RE.fullmatch(digest):
            raise ValueError(f"invalid lowercase digest for {service}")
        manifest[service] = digest
    if output:
        with open(output, "w", encoding="utf-8") as handle:
            json.dump(manifest, handle, sort_keys=True, indent=2)
            handle.write("\n")
    return manifest


def _write_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        if "\n" in value:
            handle.write(f"{name}<<EOF\n{value}\nEOF\n")
        else:
            handle.write(f"{name}={value}\n")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("snapshot", "compare", "aggregate-digests"))
    parser.add_argument("--before", default="")
    parser.add_argument("--selected", default="[]")
    # Empty default on purpose: do not inherit GITHUB_SHA from the environment.
    # Digest mode omits --git-sha; falling back to the commit SHA would let a
    # rebuild pass the selected-app assertion.
    parser.add_argument("--git-sha", default="")
    parser.add_argument("--requested-digest", default="")
    parser.add_argument("--digest-root", default="")
    parser.add_argument("--output", default="")
    return parser


def main() -> int:
    args = _parser().parse_args()

    if args.command == "aggregate-digests":
        selected = json.loads(args.selected)
        manifest = aggregate_digests(args.digest_root, selected, args.output or None)
        print(json.dumps(manifest, indent=2, sort_keys=True))
        return 0

    resource_group = os.environ.get("AZURE_RG", "")
    if not resource_group:
        print("::error::AZURE_RG is required", file=sys.stderr)
        return 1

    if args.command == "snapshot":
        snapshot = capture(resource_group)
        encoded = json.dumps(snapshot, separators=(",", ":"))
        _write_output("snapshot", encoded)
        print(json.dumps(snapshot, indent=2))
        return 0

    before = json.loads(args.before)
    selected = json.loads(args.selected)
    after = capture(resource_group)
    errors = compare(
        before,
        after,
        selected,
        git_sha=args.git_sha or None,
        requested_digest=args.requested_digest or None,
    )
    print(json.dumps({"after": after, "errors": errors}, indent=2))
    if errors:
        for error in errors:
            print(f"::error::{error}", file=sys.stderr)
        return 1
    print("Non-interference proof passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
