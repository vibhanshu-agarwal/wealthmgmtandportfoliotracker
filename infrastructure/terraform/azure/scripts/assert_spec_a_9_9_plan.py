#!/usr/bin/env python3
"""
assert_spec_a_9_9_plan.py — Spec A checkpoint 9.9 exact-scope plan assertion.

Checkpoint 9.9 ("enforcement enabled") removes the two catalog-override env vars and raises
min_replicas 0 -> 1 on exactly three Container Apps. Its abort path (re-adding the overrides,
restoring min_replicas 0) must be equally exact-scoped. This script proves, from the plan JSON
alone, that a dispatched remote-plan/apply touches ONLY those three resources, only as in-place
updates, and does not silently also change the running image or SERVICE_VERSION identity.

Runs for EVERY remote-plan/apply, not only the two 9.9 profiles — `--profile standard` (and
later non-9.9 profiles such as 9.11 / 9.12) is a guard, not a no-op: once the 9.9 desired-state
change lands in main.tf, a dispatch left on the default `standard` profile must not be able to
apply the enforcement-flag/min_replicas change without ever going through the exact-scope proof
below. Non-scoped profiles therefore FAIL if the plan touches the protected surface (the two
override env vars or min_replicas) on any of the three catalog services at all — only
spec-a-9.9-enable/abort may touch it, and only as the complete, exact transition.

--expected-image-tags-json pins image/SERVICE_VERSION identity to per-service values from the
dispatch's deployed_image_tags_json map, not merely to itself: checking only before==after would
accept a plan where both sides already carry the same WRONG tag (e.g. stale state, or a resource
that was never on the expected image to begin with) — a real gap, since "unchanged" and "correct"
are different claims.

Usage:
    python3 scripts/assert_spec_a_9_9_plan.py tfplan.json --profile standard --expected-image-tags-json '<json>'
    python3 scripts/assert_spec_a_9_9_plan.py tfplan.json --profile spec-a-9.9-enable --expected-image-tags-json '<json>'
    python3 scripts/assert_spec_a_9_9_plan.py tfplan.json --profile spec-a-9.9-abort --expected-image-tags-json '<json>'
"""

from __future__ import annotations

import argparse
import json
import sys

from validate_dispatch import DispatchValidationError, parse_deployed_image_tags

SERVICE_ADDRESSES = (
    "module.portfolio_service.azurerm_container_app.this",
    "module.market_data_service.azurerm_container_app.this",
    "module.insight_service.azurerm_container_app.this",
)

OVERRIDE_ENV_NAMES = (
    "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS",
    "APP_CATALOG_ENFORCE_HOLDING_INVARIANT",
)

ACR_LOGIN_SERVER = "wealthprodacr.azurecr.io"

# Maps each service address to its expected image repository on the production ACR.
# The full expected image is f"{ACR_LOGIN_SERVER}/{repo}:{expected_tag_for_service}".
SERVICE_IMAGE_REPOSITORIES: dict[str, str] = {
    "module.portfolio_service.azurerm_container_app.this": "portfolio-service",
    "module.market_data_service.azurerm_container_app.this": "market-data-service",
    "module.insight_service.azurerm_container_app.this": "insight-service",
}

REQUIRED_IMAGE_TAG_SERVICES = (
    "api-gateway",
    "portfolio-service",
    "market-data-service",
    "insight-service",
)


def parse_expected_image_tags(raw: str) -> dict[str, str]:
    try:
        return parse_deployed_image_tags(raw)
    except DispatchValidationError as exc:
        raise ValueError(str(exc)) from exc


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def _is_noop(rc: dict) -> bool:
    actions = rc.get("change", {}).get("actions") or []
    return list(actions) in ([], ["no-op"])


def _template(side: dict | None) -> dict | None:
    if not side:
        return None
    templates = side.get("template")
    if not isinstance(templates, list) or not templates:
        return None
    return templates[0] if isinstance(templates[0], dict) else None


def _container(side: dict | None) -> dict | None:
    template = _template(side)
    if template is None:
        return None
    containers = template.get("container")
    if not isinstance(containers, list) or not containers:
        return None
    return containers[0] if isinstance(containers[0], dict) else None


def _env_map(side: dict | None) -> dict[str, str | None]:
    container = _container(side)
    if container is None:
        return {}
    env_list = container.get("env")
    if not isinstance(env_list, list):
        return {}
    return {
        e["name"]: (None if e.get("value") is None else str(e.get("value")))
        for e in env_list
        if isinstance(e, dict) and "name" in e
    }


def _min_replicas(side: dict | None) -> int | None:
    template = _template(side)
    if template is None:
        return None
    value = template.get("min_replicas")
    return None if value is None else int(value)


def _image(side: dict | None) -> str | None:
    container = _container(side)
    return container.get("image") if container else None


def _evaluate_standard_guard(plan: dict, profile: str) -> list[str]:
    """Non-9.9 profiles must actively forbid touching the 9.9 protected surface
    (min_replicas, the two catalog-override env vars) on any of the three services.
    Other, unrelated changes to these resources are not this guard's concern — only
    the specific fields checkpoint 9.9 owns. Used for ``standard``, 9.11, and 9.12
    profiles (all routed here as non-scoped for the 9.9 surface)."""
    errors: list[str] = []
    resource_changes = plan.get("resource_changes", [])
    by_address = {rc.get("address"): rc for rc in resource_changes if not _is_noop(rc)}

    for address in SERVICE_ADDRESSES:
        rc = by_address.get(address)
        if rc is None:
            continue

        change = rc.get("change") or {}
        before, after = change.get("before"), change.get("after")

        before_min, after_min = _min_replicas(before), _min_replicas(after)
        # Normalize 0/None (see the enable/abort comment below) before comparing so a
        # cosmetic unset<->0 representation change isn't mistaken for a real drift.
        if (before_min or 0) != (after_min or 0):
            errors.append(
                f"FAIL [standard-guard] {address} changes min_replicas ({before_min!r} -> "
                f"{after_min!r}) under change_profile={profile}; this field belongs to "
                "checkpoint 9.9 — use change_profile=spec-a-9.9-enable/abort to touch it."
            )

        before_env, after_env = _env_map(before), _env_map(after)
        for name in OVERRIDE_ENV_NAMES:
            if before_env.get(name) != after_env.get(name):
                errors.append(
                    f"FAIL [standard-guard] {address} changes {name} "
                    f"({before_env.get(name)!r} -> {after_env.get(name)!r}) under "
                    f"change_profile={profile}; this field belongs to checkpoint 9.9 — use "
                    "change_profile=spec-a-9.9-enable/abort to touch it."
                )

    return errors


def _evaluate_9_9_transition(plan: dict, profile: str, expected_image_tags: dict[str, str]) -> list[str]:
    # profile is the literal change_profile input value here (spec-a-9.9-enable /
    # spec-a-9.9-abort), not a bare "enable"/"abort" — normalize once at the top so the
    # rest of this function reads as intent, not string comparison.
    is_enable = profile == "spec-a-9.9-enable"

    errors: list[str] = []
    resource_changes = plan.get("resource_changes", [])

    non_noop = [rc for rc in resource_changes if not _is_noop(rc)]
    non_noop_addresses = {rc.get("address") for rc in non_noop}
    expected_addresses = set(SERVICE_ADDRESSES)

    unexpected = non_noop_addresses - expected_addresses
    if unexpected:
        errors.append(
            f"FAIL [scope] unexpected non-no-op resource change(s) outside the 9.9 scope: "
            f"{sorted(unexpected)}"
        )

    missing = expected_addresses - non_noop_addresses
    if missing:
        errors.append(
            f"FAIL [scope] expected a change on {sorted(missing)} but none is present "
            "(or it is a no-op) — plan does not touch the 9.9 checkpoint's target resources."
        )

    by_address = {rc.get("address"): rc for rc in non_noop if rc.get("address") in expected_addresses}

    for address in SERVICE_ADDRESSES:
        rc = by_address.get(address)
        if rc is None:
            continue  # already reported above

        actions = list(rc.get("change", {}).get("actions") or [])
        if actions != ["update"]:
            errors.append(
                f"FAIL [action] {address} has actions={actions}; expected exactly "
                "['update'] — a create/delete/replace is not an in-place 9.9 change."
            )
            continue

        change = rc.get("change") or {}
        before, after = change.get("before"), change.get("after")

        before_min, after_min = _min_replicas(before), _min_replicas(after)
        expected_before_min, expected_after_min = (0, 1) if is_enable else (1, 0)
        # min_replicas=0 is represented as an unset/null attribute in this module (see
        # infrastructure/terraform/azure/modules/container-app/main.tf), so accept None
        # wherever 0 is expected; a genuine 1 must never be read as "unset".
        before_min_ok = (
            before_min in (0, None) if expected_before_min == 0 else before_min == expected_before_min
        )
        if not before_min_ok:
            errors.append(
                f"FAIL [min_replicas] {address} before={before_min}, expected "
                f"{expected_before_min} (or unset/null) for profile={profile}."
            )
        if after_min != expected_after_min:
            errors.append(
                f"FAIL [min_replicas] {address} after={after_min}, expected "
                f"{expected_after_min} for profile={profile}."
            )

        before_env, after_env = _env_map(before), _env_map(after)
        if is_enable:
            for name in OVERRIDE_ENV_NAMES:
                if before_env.get(name) != "false":
                    errors.append(
                        f"FAIL [override-before] {address} expected {name}='false' before "
                        f"the enable apply, got {before_env.get(name)!r}."
                    )
                if name in after_env:
                    errors.append(
                        f"FAIL [override-after] {address} still declares {name}={after_env.get(name)!r} "
                        "after the enable apply; the override must be removed, not set true."
                    )
        else:  # abort
            for name in OVERRIDE_ENV_NAMES:
                if name in before_env:
                    errors.append(
                        f"FAIL [override-before] {address} declares {name}={before_env.get(name)!r} "
                        "before the abort apply; abort should start from the removed-override state."
                    )
                if after_env.get(name) != "false":
                    errors.append(
                        f"FAIL [override-after] {address} expected {name}='false' after "
                        f"the abort apply, got {after_env.get(name)!r}."
                    )

        # Require exact registry/repository/tag equality — endswith(":{tag}") would accept
        # a wrong registry or wrong repository carrying the correct tag.
        expected_repo = SERVICE_IMAGE_REPOSITORIES[address]
        expected_image_tag = expected_image_tags[expected_repo]
        expected_image = f"{ACR_LOGIN_SERVER}/{expected_repo}:{expected_image_tag}"
        before_image, after_image = _image(before), _image(after)
        for label, image in (("before", before_image), ("after", after_image)):
            if image != expected_image:
                errors.append(
                    f"FAIL [image] {address} {label} image is {image!r}; expected exactly "
                    f"{expected_image!r} — registry, repository, and tag must all match."
                )

        before_version = before_env.get("SERVICE_VERSION")
        after_version = after_env.get("SERVICE_VERSION")
        for label, version in (("before", before_version), ("after", after_version)):
            if version != expected_image_tag:
                errors.append(
                    f"FAIL [service_version] {address} {label} SERVICE_VERSION is {version!r}; "
                    f"expected {expected_image_tag!r} (deployed_image_tags_json[{expected_repo!r}]), not merely to match "
                    "its counterpart on the other side of the diff."
                )

    return errors


def evaluate_plan(plan: dict, profile: str, expected_image_tags: dict[str, str]) -> list[str]:
    if profile in (
        "standard",
        "spec-a-9.11-enable",
        "spec-a-9.11-abort",
        "spec-a-9.12-enable",
        "spec-a-9.12-disable",
        "spec-a-9.12-tx-diag-enable",
        "spec-a-9.12-tx-diag-disable",
    ):
        # Later checkpoint profiles must not touch the 9.9 protected surface.
        return _evaluate_standard_guard(plan, profile)
    return _evaluate_9_9_transition(plan, profile, expected_image_tags)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan_json")
    parser.add_argument(
        "--profile",
        choices=(
            "standard",
            "spec-a-9.9-enable",
            "spec-a-9.9-abort",
            "spec-a-9.11-enable",
            "spec-a-9.11-abort",
            "spec-a-9.12-enable",
            "spec-a-9.12-disable",
            "spec-a-9.12-tx-diag-enable",
            "spec-a-9.12-tx-diag-disable",
        ),
        required=True,
        help="The literal change_profile dispatch input value.",
    )
    parser.add_argument(
        "--expected-image-tags-json",
        required=True,
        help="JSON object with api-gateway, portfolio-service, market-data-service, and insight-service tags.",
    )
    args = parser.parse_args()

    try:
        expected_image_tags = parse_expected_image_tags(args.expected_image_tags_json)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    try:
        plan = load_plan(args.plan_json)
    except FileNotFoundError:
        print(f"ERROR: Plan file not found: '{args.plan_json}'", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse plan JSON from '{args.plan_json}': {e}", file=sys.stderr)
        return 1

    errors = evaluate_plan(plan, args.profile, expected_image_tags)
    if errors:
        print(f"SPEC A 9.9 PLAN ASSERTION FAILED (profile={args.profile}):")
        for err in errors:
            print(f"  {err}")
        return 1
    if args.profile in (
        "standard",
        "spec-a-9.11-enable",
        "spec-a-9.11-abort",
        "spec-a-9.12-enable",
        "spec-a-9.12-disable",
        "spec-a-9.12-tx-diag-enable",
        "spec-a-9.12-tx-diag-disable",
    ):
        print(
            f"PASS spec-a-9.9 guard (profile={args.profile}) — the plan does not touch "
            "min_replicas or the catalog override env vars on any of the three service "
            "Container Apps."
        )
    else:
        print(
            f"PASS spec-a-9.9 plan (profile={args.profile}) — exactly the three service Container "
            "Apps change, in-place only, image/SERVICE_VERSION pinned to deployed_image_tags_json."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
