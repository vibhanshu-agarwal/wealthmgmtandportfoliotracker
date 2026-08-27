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
3. expected_main_sha and deployed_image_tag must both be full 40-hex-character SHAs.
4. change_profile must be one of standard / spec-a-9.9-enable / spec-a-9.9-abort /
   spec-a-9.11-enable / spec-a-9.11-abort.
5. For any scoped Spec A profile (9.9 or 9.11 enable/abort), use_seed_image and
   recreate_market_data_job must both be false — these recovery/bootstrap flags are unrelated
   to and unsafe to combine with a scoped Spec A production change.

This script does not check whether deployed_image_tag actually exists in ACR — that requires a live
az CLI call and is a separate workflow step, kept out of this pure/offline, unit-testable script.
"""

from __future__ import annotations

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
)
SCOPED_SPEC_A_PROFILES = (
    "spec-a-9.9-enable",
    "spec-a-9.9-abort",
    "spec-a-9.11-enable",
    "spec-a-9.11-abort",
)
# Backward-compatible alias for callers/tests that still name the 9.9 pair.
SPEC_A_9_9_PROFILES = ("spec-a-9.9-enable", "spec-a-9.9-abort")
MAIN_REF = "refs/heads/main"
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")


class DispatchValidationError(ValueError):
    pass


@dataclass(frozen=True)
class DispatchInputs:
    actual_ref: str
    actual_sha: str
    expected_main_sha: str
    deployed_image_tag: str
    change_profile: str
    use_seed_image: str
    recreate_market_data_job: str


def validate(inputs: DispatchInputs) -> None:
    actual_ref = inputs.actual_ref.strip()
    actual_sha = inputs.actual_sha.strip().lower()
    expected_sha = inputs.expected_main_sha.strip().lower()
    deployed_tag = inputs.deployed_image_tag.strip().lower()
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

    if not deployed_tag:
        raise DispatchValidationError(
            "deployed_image_tag is required for remote-plan/apply — state the image tag "
            "currently deployed, so SERVICE_VERSION cannot silently drift from the running image."
        )
    if not FULL_SHA.match(deployed_tag):
        raise DispatchValidationError(
            f"deployed_image_tag must be a full 40-character hex SHA, got {deployed_tag!r}."
        )

    if profile not in VALID_PROFILES:
        raise DispatchValidationError(
            f"change_profile must be one of {VALID_PROFILES}, got {profile!r}."
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


def _env(name: str) -> str:
    return os.environ.get(name, "")


def main() -> int:
    inputs = DispatchInputs(
        actual_ref=_env("ACTUAL_REF"),
        actual_sha=_env("ACTUAL_SHA"),
        expected_main_sha=_env("EXPECTED_MAIN_SHA"),
        deployed_image_tag=_env("DEPLOYED_IMAGE_TAG"),
        change_profile=_env("CHANGE_PROFILE"),
        use_seed_image=_env("USE_SEED_IMAGE"),
        recreate_market_data_job=_env("RECREATE_MARKET_DATA_JOB"),
    )
    try:
        validate(inputs)
    except DispatchValidationError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    print(
        f"terraform dispatch validated: profile={inputs.change_profile} "
        f"sha={inputs.actual_sha} image_tag={inputs.deployed_image_tag}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
