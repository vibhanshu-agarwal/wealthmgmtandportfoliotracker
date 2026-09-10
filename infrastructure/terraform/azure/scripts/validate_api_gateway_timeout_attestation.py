#!/usr/bin/env python3
"""Fail-closed validation for a live api-gateway timeout-rollout attestation."""

from __future__ import annotations

import argparse
import json
import re
import sys

from api_gateway_resource_id import is_api_gateway_resource_id, same_api_gateway_resource_id

_IMAGE = re.compile(
    r"wealthprodacr\.azurecr\.io/api-gateway@(sha256:[0-9a-f]{64})",
    re.IGNORECASE | re.ASCII,
)


class AttestationValidationError(ValueError):
    """A live serving-state snapshot did not satisfy the timeout-rollout contract."""


def validate_attestation(
    app: object,
    revision: object,
    expected: tuple[str, str, str] | None = None,
) -> tuple[str, str, str]:
    if not isinstance(app, dict) or not isinstance(revision, dict):
        raise AttestationValidationError("serving state is malformed")
    gateway_id = app.get("id")
    latest = app.get("latestRevision")
    traffic = app.get("traffic")
    if (
        not is_api_gateway_resource_id(gateway_id)
        or not isinstance(latest, str)
        or not isinstance(traffic, list)
        or len(traffic) != 1
        or not isinstance(traffic[0], dict)
        or traffic[0].get("latestRevision") is not True
        or traffic[0].get("weight") != 100
    ):
        raise AttestationValidationError("serving app state is malformed")
    if (
        revision.get("name") != latest
        or revision.get("active") is not True
        or revision.get("trafficWeight") != 100
    ):
        raise AttestationValidationError("serving revision state is stale")
    containers = revision.get("containers")
    if not isinstance(containers, list) or len(containers) != 1 or not isinstance(containers[0], dict):
        raise AttestationValidationError("serving container state is malformed")
    container = containers[0]
    image = container.get("image")
    match = _IMAGE.fullmatch(image) if container.get("name") == "api-gateway" and isinstance(image, str) else None
    if match is None:
        raise AttestationValidationError("serving gateway image is malformed")
    attestation = (gateway_id, latest, match.group(1).lower())
    if expected is not None:
        expected_id, expected_revision, expected_digest = expected
        if (
            not same_api_gateway_resource_id(gateway_id, expected_id)
            or latest != expected_revision
            or attestation[2] != expected_digest
        ):
            raise AttestationValidationError("serving state changed after attestation")
    return attestation


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-json", required=True)
    parser.add_argument("--revision-json", required=True)
    parser.add_argument("--expected-gateway-id", default="")
    parser.add_argument("--expected-revision", default="")
    parser.add_argument("--expected-digest", default="")
    args = parser.parse_args()
    expected_values = (args.expected_gateway_id, args.expected_revision, args.expected_digest)
    if any(expected_values) and not all(expected_values):
        print("ERROR: complete expected attestation is required.", file=sys.stderr)
        return 1
    try:
        attestation = validate_attestation(
            json.loads(args.app_json),
            json.loads(args.revision_json),
            expected_values if all(expected_values) else None,
        )
    except (json.JSONDecodeError, AttestationValidationError):
        print("ERROR: api-gateway serving attestation is invalid.", file=sys.stderr)
        return 1
    if not all(expected_values):
        print("\t".join(attestation))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
