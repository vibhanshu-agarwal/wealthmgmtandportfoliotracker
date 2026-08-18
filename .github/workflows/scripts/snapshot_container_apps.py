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
from typing import Any, Callable

KNOWN_SERVICES = (
    "api-gateway",
    "portfolio-service",
    "market-data-service",
    "insight-service",
)
REFRESH_JOB = "market-data-refresh-job"

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
) -> list[str]:
    errors: list[str] = []
    selected_set = set(selected)
    for name in KNOWN_SERVICES:
        if name in selected_set:
            image = str(after.get(name, {}).get("image", ""))
            if git_sha and git_sha not in image:
                errors.append(
                    f"selected {name} image {image!r} does not contain git sha {git_sha}"
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


def _write_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        if "\n" in value:
            handle.write(f"{name}<<EOF\n{value}\nEOF\n")
        else:
            handle.write(f"{name}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("snapshot", "compare"))
    parser.add_argument("--before", default="")
    parser.add_argument("--selected", default="[]")
    parser.add_argument("--git-sha", default=os.environ.get("GITHUB_SHA", ""))
    args = parser.parse_args()

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
    errors = compare(before, after, selected, args.git_sha or None)
    print(json.dumps({"after": after, "errors": errors}, indent=2))
    if errors:
        for error in errors:
            print(f"::error::{error}", file=sys.stderr)
        return 1
    print("Non-interference proof passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
