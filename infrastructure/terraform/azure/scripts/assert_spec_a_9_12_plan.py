#!/usr/bin/env python3
"""Spec A checkpoint 9.12 exact-scope Terraform plan assertion."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys

TARGET_ADDRESS = "module.portfolio_service.azurerm_container_app.this"
DEMO_ENV = "APP_DEMO_SEED_ON_STARTUP"
SERVICE_VERSION_ENV = "SERVICE_VERSION"
ACR_LOGIN_SERVER = "wealthprodacr.azurecr.io"
IMAGE_REPOSITORY = "portfolio-service"

SCOPED_PROFILES = ("spec-a-9.12-enable", "spec-a-9.12-disable")
KNOWN_PROFILES = (
    "standard",
    "spec-a-9.9-enable",
    "spec-a-9.9-abort",
    "spec-a-9.11-enable",
    "spec-a-9.11-abort",
    "spec-a-9.12-enable",
    "spec-a-9.12-disable",
)

_DEMO_SENTINEL = "__SPEC_A_9_12_DEMO_SENTINEL__"
_DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\Z")
_VERSION_PATTERN = re.compile(r"[0-9a-f]{40}\Z")


def load_plan(path: str) -> dict:
    with open(path, encoding="utf-8") as plan_file:
        return json.load(plan_file)


def _is_noop(resource_change: dict) -> bool:
    actions = resource_change.get("change", {}).get("actions") or []
    return list(actions) in ([], ["no-op"])


def _template(side: dict | None) -> dict | None:
    if not isinstance(side, dict):
        return None
    templates = side.get("template")
    if not isinstance(templates, list) or len(templates) != 1:
        return None
    return templates[0] if isinstance(templates[0], dict) else None


def _container(side: dict | None) -> dict | None:
    template = _template(side)
    if template is None:
        return None
    containers = template.get("container")
    if not isinstance(containers, list) or len(containers) != 1:
        return None
    return containers[0] if isinstance(containers[0], dict) else None


def _raw_env(side: dict | None) -> list:
    container = _container(side)
    if container is None:
        return []
    env = container.get("env")
    return env if isinstance(env, list) else []


def _named_env(side: dict | None) -> list[dict]:
    return [
        entry
        for entry in _raw_env(side)
        if isinstance(entry, dict) and isinstance(entry.get("name"), str)
    ]


def _entries(side: dict | None, name: str) -> list[dict]:
    return [entry for entry in _named_env(side) if entry.get("name") == name]


def _literal_value(entry: dict) -> str | None:
    value = entry.get("value")
    return value if isinstance(value, str) else None


def _canonical_env_entry(entry: dict) -> tuple:
    """Comparable form of a full env entry, including value vs secret_name shape."""
    return tuple(sorted((key, entry[key]) for key in entry))


def _has_duplicate_or_invalid_env(side: dict | None) -> bool:
    raw = _raw_env(side)
    named = _named_env(side)
    names = [entry["name"] for entry in named]
    return len(raw) != len(named) or len(names) != len(set(names))


def _image(side: dict | None) -> str | None:
    container = _container(side)
    value = container.get("image") if container else None
    return value if isinstance(value, str) else None


def _min_replicas(side: dict | None):
    template = _template(side)
    return template.get("min_replicas") if template else None


def _ingress_disabled(side: dict | None) -> bool:
    if not isinstance(side, dict):
        return False
    ingress = side.get("ingress")
    return ingress is None or ingress == []


def _normalize(side: dict | None, *, allow_missing_demo: bool) -> dict | None:
    if not isinstance(side, dict) or _has_duplicate_or_invalid_env(side):
        return None
    normalized = copy.deepcopy(side)
    container = _container(normalized)
    if container is None:
        return None
    env = container.get("env")
    if not isinstance(env, list):
        return None
    demo_entries = [entry for entry in env if entry.get("name") == DEMO_ENV]
    if demo_entries:
        # Replace the whole entry so value-vs-secret_name forms do not leave residue.
        env[env.index(demo_entries[0])] = {"name": DEMO_ENV, "value": _DEMO_SENTINEL}
    elif allow_missing_demo:
        env.append({"name": DEMO_ENV, "value": _DEMO_SENTINEL})
    env.sort(key=lambda entry: entry["name"])
    return normalized


def _check_transition(
    before: dict | None,
    after: dict | None,
    profile: str,
    errors: list[str],
) -> None:
    before_entries = _entries(before, DEMO_ENV)
    after_entries = _entries(after, DEMO_ENV)
    if profile == "spec-a-9.12-enable":
        before_ok = (
            len(before_entries) == 0
            or (
                len(before_entries) == 1
                and _literal_value(before_entries[0]) == "false"
            )
        )
        after_ok = (
            len(after_entries) == 1
            and _literal_value(after_entries[0]) == "true"
        )
    else:
        before_ok = (
            len(before_entries) == 1
            and _literal_value(before_entries[0]) == "true"
        )
        after_ok = (
            len(after_entries) == 1
            and _literal_value(after_entries[0]) == "false"
        )
    if not before_ok or not after_ok:
        errors.append(f"FAIL [demo-direction] {TARGET_ADDRESS} has an invalid demo transition.")


def _check_fixed_invariants(
    before: dict | None,
    after: dict | None,
    expected_image_digest: str,
    expected_service_version: str,
    errors: list[str],
) -> None:
    for label, side in (("before", before), ("after", after)):
        if _min_replicas(side) != 1:
            errors.append(f"FAIL [replicas] {TARGET_ADDRESS} {label} min_replicas is invalid.")
        if not _ingress_disabled(side):
            errors.append(f"FAIL [ingress] {TARGET_ADDRESS} {label} ingress is not disabled.")
        expected_image = (
            f"{ACR_LOGIN_SERVER}/{IMAGE_REPOSITORY}@{expected_image_digest}"
        )
        if _image(side) != expected_image:
            errors.append(f"FAIL [image] {TARGET_ADDRESS} {label} image digest pin is invalid.")
        versions = _entries(side, SERVICE_VERSION_ENV)
        if (
            len(versions) != 1
            or _literal_value(versions[0]) != expected_service_version
        ):
            errors.append(
                f"FAIL [service_version] {TARGET_ADDRESS} {label} SERVICE_VERSION pin is invalid."
            )


def _evaluate_non_scoped(plan: dict) -> list[str]:
    errors: list[str] = []
    changes = plan.get("resource_changes", [])
    if not isinstance(changes, list):
        return [f"FAIL [plan] {TARGET_ADDRESS} resource changes are invalid."]
    for resource_change in changes:
        if not isinstance(resource_change, dict) or resource_change.get("address") != TARGET_ADDRESS:
            continue
        change = resource_change.get("change") or {}
        before = change.get("before")
        after = change.get("after")
        before_entries = _entries(before, DEMO_ENV)
        after_entries = _entries(after, DEMO_ENV)
        before_sigs = [_canonical_env_entry(entry) for entry in before_entries]
        after_sigs = [_canonical_env_entry(entry) for entry in after_entries]
        if (
            len(before_entries) > 1
            or len(after_entries) > 1
            or before_sigs != after_sigs
        ):
            errors.append(
                f"FAIL [demo-guard] {TARGET_ADDRESS} changes the demo environment outside checkpoint 9.12."
            )
    return errors


def _evaluate_scoped(
    plan: dict,
    profile: str,
    expected_image_digest: str,
    expected_service_version: str,
) -> list[str]:
    errors: list[str] = []
    if not _DIGEST_PATTERN.fullmatch(expected_image_digest):
        errors.append(f"FAIL [input] {TARGET_ADDRESS} expected image digest is invalid.")
    if not _VERSION_PATTERN.fullmatch(expected_service_version):
        errors.append(f"FAIL [input] {TARGET_ADDRESS} expected service version is invalid.")
    if errors:
        return errors

    changes = plan.get("resource_changes", [])
    if not isinstance(changes, list):
        return [f"FAIL [plan] {TARGET_ADDRESS} resource changes are invalid."]
    non_noop = [
        change for change in changes
        if isinstance(change, dict) and not _is_noop(change)
    ]
    if len(non_noop) != 1 or non_noop[0].get("address") != TARGET_ADDRESS:
        return [f"FAIL [scope] {TARGET_ADDRESS} is not the sole changed resource."]

    target = non_noop[0]
    change = target.get("change") or {}
    if list(change.get("actions") or []) != ["update"]:
        return [f"FAIL [action] {TARGET_ADDRESS} is not an in-place update."]

    before = change.get("before")
    after = change.get("after")
    _check_transition(before, after, profile, errors)
    if _has_duplicate_or_invalid_env(before) or _has_duplicate_or_invalid_env(after):
        errors.append(f"FAIL [environment] {TARGET_ADDRESS} has invalid or duplicate environment entries.")
    _check_fixed_invariants(
        before,
        after,
        expected_image_digest,
        expected_service_version,
        errors,
    )

    normalized_before = _normalize(
        before,
        allow_missing_demo=profile == "spec-a-9.12-enable",
    )
    normalized_after = _normalize(after, allow_missing_demo=False)
    if (
        normalized_before is None
        or normalized_after is None
        or normalized_before != normalized_after
    ):
        errors.append(
            f"FAIL [field] {TARGET_ADDRESS} changes a non-demo field."
        )
    return errors


def evaluate_plan(
    plan: dict,
    profile: str,
    expected_image_digest: str,
    expected_service_version: str,
) -> list[str]:
    if profile not in KNOWN_PROFILES:
        return ["FAIL [profile] unknown change profile; fail closed."]
    if profile in SCOPED_PROFILES:
        return _evaluate_scoped(
            plan,
            profile,
            expected_image_digest,
            expected_service_version,
        )
    return _evaluate_non_scoped(plan)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan_json")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-image-digest", required=True)
    parser.add_argument("--expected-service-version", required=True)
    args = parser.parse_args()

    try:
        plan = load_plan(args.plan_json)
    except (OSError, json.JSONDecodeError):
        print("ERROR: Failed to load Terraform plan JSON.", file=sys.stderr)
        return 1

    errors = evaluate_plan(
        plan,
        args.profile,
        args.expected_image_digest,
        args.expected_service_version,
    )
    if errors:
        print(f"SPEC A 9.12 PLAN ASSERTION FAILED (profile={args.profile}):")
        for error in errors:
            print(f"  {error}")
        return 1
    print(f"PASS spec-a-9.12 plan guard (profile={args.profile}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
