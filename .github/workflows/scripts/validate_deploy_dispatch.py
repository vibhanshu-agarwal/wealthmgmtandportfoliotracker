#!/usr/bin/env python3
"""Validate deploy.yml's workflow_dispatch inputs before any production mutation.

Four independent guards, all fail-closed:

1. expected_main_sha must match the commit actually being deployed. Protects against
   dispatching against a `main` that moved between "I decided to deploy" and "I clicked
   dispatch" (or an API call built from a stale SHA).
2. The dispatch must target refs/heads/main exactly. workflow_dispatch lets the caller
   pick an arbitrary branch or tag to run against; github.sha then reflects whatever that
   selected ref resolves to — a SHA match alone does not prove the ref is main. A
   caller-selected branch/tag whose tip happens to match a given SHA would otherwise pass.
3. deployment_mode must be consistent with services/prebuilt_digest. Replaces silent
   "empty services = full deploy" inference at the dispatcher layer with an explicit,
   required declaration of intent — an inconsistent combination fails fast instead of
   silently doing something other than what was intended. The sentinel default
   ("select-deployment-mode") is not a valid mode, so accepting the pre-filled dropdown
   without touching it fails closed instead of silently meaning "full".
4. AWS does not support scoped/digest selection today (deploy-aws.yml has no inputs to
   receive them) — deployment_mode must be "full" whenever CLOUD_PROVIDER=aws, or a
   scoped/digest intent would silently be dropped and AWS would do a full deploy anyway.

Downstream (deploy-azure.yml's resolve_deploy_selection.py) still does its own
empty-means-full parsing of `services` — that contract is unchanged and still exercised
directly. This script only guards the new dispatcher-level `deployment_mode` declaration
against disagreeing with the inputs and provider it's paired with.
"""

from __future__ import annotations

import os
import sys
from dataclasses import dataclass

VALID_MODES = ("full", "scoped", "digest")
MAIN_REF = "refs/heads/main"


class DispatchValidationError(ValueError):
    pass


@dataclass(frozen=True)
class DispatchInputs:
    deployment_mode: str
    services: str
    prebuilt_digest: str
    expected_main_sha: str
    actual_sha: str
    actual_ref: str
    cloud_provider: str


def validate(inputs: DispatchInputs) -> None:
    mode = inputs.deployment_mode.strip()
    services = inputs.services.strip()
    digest = inputs.prebuilt_digest.strip()
    expected_sha = inputs.expected_main_sha.strip().lower()
    actual_sha = inputs.actual_sha.strip().lower()
    actual_ref = inputs.actual_ref.strip()
    provider = inputs.cloud_provider.strip()

    if not expected_sha:
        raise DispatchValidationError(
            "expected_main_sha is required — state the commit SHA you intend to deploy."
        )
    if not actual_sha:
        raise DispatchValidationError("Could not resolve the actual dispatch SHA.")
    if expected_sha != actual_sha:
        raise DispatchValidationError(
            f"expected_main_sha ({expected_sha}) does not match the commit actually being "
            f"dispatched ({actual_sha}). main may have moved since you decided to deploy — "
            "re-check and re-dispatch with the current SHA."
        )
    if actual_ref != MAIN_REF:
        raise DispatchValidationError(
            f"This workflow must be dispatched against {MAIN_REF}, got {actual_ref!r}. "
            "A matching SHA on a different branch or tag is not sufficient — re-dispatch "
            "with main selected as the run target."
        )

    if mode not in VALID_MODES:
        raise DispatchValidationError(
            f"deployment_mode must be one of {VALID_MODES}, got {mode!r}. If this is the "
            "pre-filled dropdown default, you must explicitly choose a mode."
        )

    if mode == "full":
        if services:
            raise DispatchValidationError(
                "deployment_mode=full requires an empty services input; got "
                f"{services!r}. Use deployment_mode=scoped for a service allowlist."
            )
        if digest:
            raise DispatchValidationError(
                "deployment_mode=full requires an empty prebuilt_digest input; got "
                f"{digest!r}. Use deployment_mode=digest for a prebuilt-digest deploy."
            )
    elif mode == "scoped":
        if not services:
            raise DispatchValidationError(
                "deployment_mode=scoped requires a non-empty services input."
            )
        if digest:
            raise DispatchValidationError(
                "deployment_mode=scoped requires an empty prebuilt_digest input; got "
                f"{digest!r}. Use deployment_mode=digest for a prebuilt-digest deploy."
            )
    elif mode == "digest":
        if not digest:
            raise DispatchValidationError(
                "deployment_mode=digest requires a non-empty prebuilt_digest input."
            )
        if services and services != "portfolio-service":
            raise DispatchValidationError(
                "deployment_mode=digest requires services to be empty or exactly "
                f"'portfolio-service'; got {services!r}."
            )

    if provider == "aws" and mode != "full":
        raise DispatchValidationError(
            f"deployment_mode={mode!r} is not supported for CLOUD_PROVIDER=aws — "
            "deploy-aws.yml has no services/prebuilt_digest inputs to receive a scoped or "
            "digest selection, so it would silently run a full deploy anyway. Use "
            "deployment_mode=full, or implement AWS selection support first."
        )


def _env(name: str) -> str:
    return os.environ.get(name, "")


def main() -> int:
    inputs = DispatchInputs(
        deployment_mode=_env("DEPLOYMENT_MODE"),
        services=_env("SERVICES_INPUT"),
        prebuilt_digest=_env("PREBUILT_DIGEST"),
        expected_main_sha=_env("EXPECTED_MAIN_SHA"),
        actual_sha=_env("ACTUAL_SHA"),
        actual_ref=_env("ACTUAL_REF"),
        cloud_provider=_env("CLOUD_PROVIDER"),
    )
    try:
        validate(inputs)
    except DispatchValidationError as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    print(f"deploy dispatch validated: mode={inputs.deployment_mode} sha={inputs.actual_sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
