#!/usr/bin/env python3
"""Validate terraform-azure.yml's live-state dispatch inputs before any Azure mutation.

Spec A checkpoint-9.9 hardening (following the checkpoint-9.8 incident, where deploy.yml's push
trigger deployed a production image before its Terraform override existed): remote-plan and apply
are live-state operations against real Azure infrastructure and must not run from an unintended ref,
commit, or with an unsafe combination of recovery flags. This mirrors
.github/workflows/scripts/validate_deploy_dispatch.py's discipline for the deploy pipeline, applied
here to the Terraform pipeline.

Checks (all fail-closed):
1. The dispatch must target refs/heads/main exactly.
2. github.sha must match the caller-declared expected_main_sha.
3. expected_main_sha must be a full 40-hex-character SHA.
4. deployed_image_tags_json must be a JSON object with exactly the four required service keys,
   no duplicate keys, and each value a canonical lowercase 40-hex-character SHA (no trim/case
   normalization — noncanonical input is rejected).
5. change_profile must be one of standard or the scoped 9.9 / 9.11 / 9.12 profiles.
6. For any scoped Spec A profile, use_seed_image and
   recreate_market_data_job must both be false — these recovery/bootstrap flags are unrelated
   to and unsafe to combine with a scoped Spec A production change.
7. All 9.12 profiles require an independently supplied portfolio image digest in
   sha256:<64-lowercase-hex> form.

This script does not check whether each supplied tag actually exists in its own ACR repository —
that requires a live az CLI call and is a separate workflow step, kept out of this pure/offline,
unit-testable script.
"""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass

VALID_PROFILES = (
    "standard",
    "spec-a-9.9-enable",
    "spec-a-9.9-abort",
    "spec-a-9.11-enable",
    "spec-a-9.11-abort",
    "spec-a-9.12-enable",
    "spec-a-9.12-disable",
    "spec-a-9.12-tx-diag-enable",
    "spec-a-9.12-tx-diag-disable",
)
SCOPED_SPEC_A_PROFILES = (
    "spec-a-9.9-enable",
    "spec-a-9.9-abort",
    "spec-a-9.11-enable",
    "spec-a-9.11-abort",
    "spec-a-9.12-enable",
    "spec-a-9.12-disable",
    "spec-a-9.12-tx-diag-enable",
    "spec-a-9.12-tx-diag-disable",
)
SPEC_A_9_12_PROFILES = (
    "spec-a-9.12-enable",
    "spec-a-9.12-disable",
    "spec-a-9.12-tx-diag-enable",
    "spec-a-9.12-tx-diag-disable",
)
# Backward-compatible alias for callers/tests that still name the 9.9 pair.
SPEC_A_9_9_PROFILES = ("spec-a-9.9-enable", "spec-a-9.9-abort")
REQUIRED_IMAGE_TAG_SERVICES = (
    "api-gateway",
    "portfolio-service",
    "market-data-service",
    "insight-service",
)
MAIN_REF = "refs/heads/main"
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
IMAGE_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


class DispatchValidationError(ValueError):
    pass


@dataclass(frozen=True)
class DispatchInputs:
    actual_ref: str
    actual_sha: str
    expected_main_sha: str
    deployed_image_tags_json: str
    expected_portfolio_image_digest: str
    change_profile: str
    use_seed_image: str
    recreate_market_data_job: str


def _reject_duplicate_json_keys(pairs: list[tuple[str, object]]) -> dict:
    keys = [key for key, _ in pairs]
    if len(keys) != len(set(keys)):
        duplicated = sorted({key for key in keys if keys.count(key) > 1})
        raise DispatchValidationError(
            "deployed_image_tags_json must not contain duplicate keys; found duplicates among "
            f"{duplicated}."
        )
    return dict(pairs)


def serialize_image_tags(tags: dict[str, str]) -> str:
    """Return the canonical JSON serialization consumed by Terraform and assertions."""
    return json.dumps(
        {service: tags[service] for service in REQUIRED_IMAGE_TAG_SERVICES},
        separators=(",", ":"),
    )


def parse_deployed_image_tags(raw: str) -> dict[str, str]:
    text = raw.strip()
    if not text:
        raise DispatchValidationError(
            "deployed_image_tags_json is required for remote-plan/apply — state the "
            "currently deployed SERVICE_VERSION tag for each Container App so identity "
            "cannot silently drift from the running image."
        )
    try:
        parsed = json.loads(text, object_pairs_hook=_reject_duplicate_json_keys)
    except DispatchValidationError:
        raise
    except json.JSONDecodeError as exc:
        raise DispatchValidationError(
            f"deployed_image_tags_json must be valid JSON: {exc}"
        ) from exc
    if not isinstance(parsed, dict):
        raise DispatchValidationError(
            "deployed_image_tags_json must be a JSON object mapping service names to tags."
        )

    required = set(REQUIRED_IMAGE_TAG_SERVICES)
    keys = set(parsed.keys())
    missing = required - keys
    if missing:
        raise DispatchValidationError(
            "deployed_image_tags_json is missing required service(s): "
            f"{sorted(missing)}."
        )
    extra = keys - required
    if extra:
        raise DispatchValidationError(
            "deployed_image_tags_json contains unexpected service(s): "
            f"{sorted(extra)}."
        )

    canonical: dict[str, str] = {}
    for service in REQUIRED_IMAGE_TAG_SERVICES:
        value = parsed[service]
        if not isinstance(value, str):
            raise DispatchValidationError(
                f"deployed_image_tags_json[{service!r}] must be a string tag value."
            )
        if value != value.strip():
            raise DispatchValidationError(
                f"deployed_image_tags_json[{service!r}] must not contain leading or trailing "
                f"whitespace; got {value!r}."
            )
        if not FULL_SHA.match(value):
            raise DispatchValidationError(
                f"deployed_image_tags_json[{service!r}] must be a canonical lowercase "
                f"40-character hex SHA, got {value!r}."
            )
        canonical[service] = value
    return canonical


def validate(inputs: DispatchInputs) -> dict[str, str]:
    actual_ref = inputs.actual_ref.strip()
    actual_sha = inputs.actual_sha.strip().lower()
    expected_sha = inputs.expected_main_sha.strip().lower()
    portfolio_digest = inputs.expected_portfolio_image_digest.strip()
    profile = inputs.change_profile.strip()
    use_seed_image = inputs.use_seed_image.strip().lower()
    recreate_job = inputs.recreate_market_data_job.strip().lower()

    if actual_ref != MAIN_REF:
        raise DispatchValidationError(
            f"This operation must be dispatched against {MAIN_REF}, got {actual_ref!r}."
        )

    if not expected_sha:
        raise DispatchValidationError(
            "expected_main_sha is required for remote-plan/apply — state the commit SHA you "
            "intend to run against."
        )
    if not actual_sha:
        raise DispatchValidationError("Could not resolve the actual dispatch SHA.")
    if actual_sha != expected_sha:
        raise DispatchValidationError(
            f"expected_main_sha ({expected_sha}) does not match the commit actually being "
            f"dispatched ({actual_sha}). main may have moved — re-check and re-dispatch."
        )
    if not FULL_SHA.match(expected_sha):
        raise DispatchValidationError(
            f"expected_main_sha must be a full 40-character hex SHA, got {expected_sha!r}."
        )

    image_tags = parse_deployed_image_tags(inputs.deployed_image_tags_json)

    if profile not in VALID_PROFILES:
        raise DispatchValidationError(
            f"change_profile must be one of {VALID_PROFILES}, got {profile!r}."
        )

    if profile in SPEC_A_9_12_PROFILES and not IMAGE_DIGEST.match(portfolio_digest):
        raise DispatchValidationError(
            "expected_portfolio_image_digest is required for any 9.12 profile and must "
            "match sha256:<64 lowercase hexadecimal characters>; it is independent of "
            "deployed_image_tags_json."
        )

    if profile in SCOPED_SPEC_A_PROFILES:
        if use_seed_image != "false":
            raise DispatchValidationError(
                f"change_profile={profile!r} requires use_seed_image=false; got "
                f"{use_seed_image!r}. Seed-image bootstrap is unrelated to and unsafe to combine "
                "with a scoped Spec A production change."
            )
        if recreate_job != "false":
            raise DispatchValidationError(
                f"change_profile={profile!r} requires recreate_market_data_job=false; got "
                f"{recreate_job!r}. Job recovery is unrelated to and unsafe to combine with a "
                "scoped Spec A production change."
            )

    return image_tags


def _write_github_output(name: str, value: str) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return
    with open(output_path, "a", encoding="utf-8") as handle:
        handle.write(f"{name}<<EOF\n{value}\nEOF\n")


def _env(name: str) -> str:
    return os.environ.get(name, "")


def main() -> int:
    inputs = DispatchInputs(
        actual_ref=_env("ACTUAL_REF"),
        actual_sha=_env("ACTUAL_SHA"),
        expected_main_sha=_env("EXPECTED_MAIN_SHA"),
        deployed_image_tags_json=_env("DEPLOYED_IMAGE_TAGS_JSON"),
        expected_portfolio_image_digest=_env("EXPECTED_PORTFOLIO_IMAGE_DIGEST"),
        change_profile=_env("CHANGE_PROFILE"),
        use_seed_image=_env("USE_SEED_IMAGE"),
        recreate_market_data_job=_env("RECREATE_MARKET_DATA_JOB"),
    )
    try:
        image_tags = validate(inputs)
    except DispatchValidationError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    canonical_json = serialize_image_tags(image_tags)
    _write_github_output("canonical_image_tags_json", canonical_json)
    print(
        "terraform dispatch validated: profile="
        f"{inputs.change_profile} sha={inputs.actual_sha} "
        f"image_tags={','.join(REQUIRED_IMAGE_TAG_SERVICES)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
