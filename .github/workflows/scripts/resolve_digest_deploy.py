#!/usr/bin/env python3
"""Validate deploy-azure.yml prebuilt-digest input (Wave P P-B).

Empty input → digest mode off (tag-based deploy). Non-empty input is a
privileged path: portfolio-service only, exactly one selection, immutable
sha256 on wealthprodacr.azurecr.io, repository name equal to the service.
Shape checks never call ACR; manifest lookup is last.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from typing import Callable

ALLOWED_SERVICE = "portfolio-service"
ALLOWED_REGISTRY = "wealthprodacr.azurecr.io"
REFERENCE_RE = re.compile(
    r"^(?P<registry>[^/]+)/(?P<repository>[^:@/]+)@sha256:(?P<hex>[0-9a-f]{64})$"
)

Lookup = Callable[[str, str, str], bool]


class DigestError(ValueError):
    pass


@dataclass(frozen=True)
class DigestDecision:
    enabled: bool
    image: str = ""
    digest: str = ""


def _acr_lookup(registry: str, repository: str, digest: str) -> bool:
    acr = registry.split(".", 1)[0]
    result = subprocess.run(
        [
            "az",
            "acr",
            "manifest",
            "show",
            "--registry",
            acr,
            "--name",
            f"{repository}@{digest}",
            "--output",
            "none",
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return result.returncode == 0


def resolve(
    raw: str | None,
    selected_services: list[str],
    lookup: Lookup | None = None,
) -> DigestDecision:
    text = "" if raw is None else raw.strip()
    if not text:
        return DigestDecision(enabled=False)

    if len(selected_services) != 1:
        raise DigestError(
            "prebuilt digest requires exactly one selected service "
            f"({ALLOWED_SERVICE}); got {selected_services!r}."
        )
    selected = selected_services[0]
    if selected != ALLOWED_SERVICE:
        raise DigestError(
            f"prebuilt digest accepts only {ALLOWED_SERVICE}, not {selected}."
        )

    if "@sha256:" not in text:
        raise DigestError(
            "prebuilt digest must be an immutable sha256 reference "
            f"({ALLOWED_REGISTRY}/{ALLOWED_SERVICE}@sha256:<64 hex>), not a tag: {text!r}."
        )

    match = REFERENCE_RE.match(text)
    if not match:
        raise DigestError(
            "prebuilt digest must match "
            f"{ALLOWED_REGISTRY}/{ALLOWED_SERVICE}@sha256:<64 lowercase hex>; got {text!r}."
        )

    registry = match.group("registry")
    repository = match.group("repository")
    digest = f"sha256:{match.group('hex')}"
    if registry != ALLOWED_REGISTRY:
        raise DigestError(
            f"prebuilt digest names a foreign registry {registry!r}; "
            f"only {ALLOWED_REGISTRY} is accepted."
        )
    if repository != selected:
        raise DigestError(
            f"prebuilt digest repository {repository!r} does not equal "
            f"the selected service {selected!r}."
        )

    image = f"{registry}/{repository}@{digest}"
    if lookup is not None and not lookup(registry, repository, digest):
        raise DigestError(
            f"prebuilt digest manifest does not resolve in {ALLOWED_REGISTRY}: {image}."
        )
    return DigestDecision(enabled=True, image=image, digest=digest)


def _write_output(name: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(f"{name}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--skip-lookup",
        action="store_true",
        help="Shape checks only; do not call ACR.",
    )
    args = parser.parse_args()
    try:
        selected = json.loads(os.environ.get("SELECTED_SERVICES", "[]"))
        lookup = None if args.skip_lookup else _acr_lookup
        decision = resolve(
            os.environ.get("PREBUILT_DIGEST", ""),
            selected,
            lookup,
        )
    except DigestError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    except json.JSONDecodeError as exc:
        print(f"::error::SELECTED_SERVICES is not JSON: {exc}", file=sys.stderr)
        return 1

    digest_mode = "true" if decision.enabled else "false"
    _write_output("digest_mode", digest_mode)
    _write_output("digest_image", decision.image)
    _write_output("digest", decision.digest)
    print(f"digest_mode={digest_mode}")
    if decision.enabled:
        print(f"digest_image={decision.image}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
