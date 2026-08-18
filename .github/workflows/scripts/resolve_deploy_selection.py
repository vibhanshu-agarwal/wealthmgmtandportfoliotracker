#!/usr/bin/env python3
"""Resolve deploy-azure.yml service allowlist into a workflow mode and matrix.

Empty / whitespace input → full deploy (all four backends, plus frontend/seed/verify).
Any non-empty valid selection → scoped backend deploy (selected services only).
Unknown names fail closed. Writes GitHub Actions outputs when GITHUB_OUTPUT is set.
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass

KNOWN_SERVICES = (
    "api-gateway",
    "portfolio-service",
    "market-data-service",
    "insight-service",
)


class SelectionError(ValueError):
    pass


@dataclass(frozen=True)
class Selection:
    deploy_mode: str
    selected_services: list[str]


def resolve(raw: str | None) -> Selection:
    text = "" if raw is None else raw.strip()
    if not text:
        return Selection("full", list(KNOWN_SERVICES))

    seen: list[str] = []
    unknown: list[str] = []
    empty_token = False
    for token in text.split(","):
        name = token.strip()
        if not name:
            empty_token = True
            continue
        if name not in KNOWN_SERVICES:
            unknown.append(name)
            continue
        if name not in seen:
            seen.append(name)

    if unknown:
        allowed = ", ".join(KNOWN_SERVICES)
        raise SelectionError(
            "Unknown deploy service(s): "
            + ", ".join(unknown)
            + f". Allowed: {allowed}."
        )
    if empty_token and not seen:
        raise SelectionError(
            "Service selection contained only empty tokens. "
            "Pass a comma-separated allowlist, or leave the input empty for a full deploy."
        )
    if not seen:
        raise SelectionError("Service selection resolved to an empty allowlist.")
    return Selection("scoped", seen)


def _write_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(f"{name}={value}\n")


def main() -> int:
    try:
        selection = resolve(os.environ.get("SERVICES_INPUT", ""))
    except SelectionError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    services_json = json.dumps(selection.selected_services, separators=(",", ":"))
    _write_output("deploy_mode", selection.deploy_mode)
    _write_output("selected_services", services_json)
    print(f"deploy_mode={selection.deploy_mode}")
    print(f"selected_services={services_json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
