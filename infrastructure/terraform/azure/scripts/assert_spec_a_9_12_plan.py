#!/usr/bin/env python3
"""Spec A checkpoint 9.12 exact-scope Terraform plan assertion."""

from __future__ import annotations

import argparse
import json
import re
import sys

TARGET_ADDRESS = "module.portfolio_service.azurerm_container_app.this"
DEMO_ENV = "APP_DEMO_SEED_ON_STARTUP"
SERVICE_VERSION_ENV = "SERVICE_VERSION"
ACR_LOGIN_SERVER = "wealthprodacr.azurecr.io"
IMAGE_REPOSITORY = "portfolio-service"
EXPECTED_TARGET_PORT = 8080

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


def _canonical_env_binding(entry: dict) -> tuple | None:
    """Semantic env binding used for non-demo field comparison."""
    name = entry.get("name")
    if not isinstance(name, str) or name == DEMO_ENV:
        return None
    secret_name = entry.get("secret_name")
    if isinstance(secret_name, str) and secret_name:
        if entry.get("value") not in (None, ""):
            return None
        return ("secret", name, secret_name)
    if "value" not in entry:
        return None
    value = entry.get("value")
    if value is None:
        return None
    if not isinstance(value, str):
        return None
    if entry.get("secret_name") not in (None, ""):
        return None
    return ("value", name, value)


def _env_fingerprint(side: dict | None) -> frozenset[tuple] | None:
    bindings: list[tuple] = []
    for entry in _raw_env(side):
        if not isinstance(entry, dict):
            return None
        binding = _canonical_env_binding(entry)
        if binding is None:
            if isinstance(entry.get("name"), str) and entry.get("name") == DEMO_ENV:
                continue
            return None
        bindings.append(binding)
    return frozenset(bindings)


def _has_duplicate_or_invalid_env(side: dict | None) -> bool:
    raw = _raw_env(side)
    named = _named_env(side)
    names = [entry["name"] for entry in named]
    if len(raw) != len(named) or len(names) != len(set(names)):
        return True
    for entry in named:
        if _canonical_env_binding(entry) is None and entry.get("name") != DEMO_ENV:
            return True
    return False


def _image(side: dict | None) -> str | None:
    container = _container(side)
    value = container.get("image") if container else None
    return value if isinstance(value, str) else None


def _min_replicas(side: dict | None):
    template = _template(side)
    return template.get("min_replicas") if template else None


def _ingress_fingerprint(ingress) -> tuple | None:
    if ingress is None or ingress == []:
        return ("absent",)
    if not isinstance(ingress, list) or len(ingress) != 1:
        return None
    block = ingress[0]
    if not isinstance(block, dict):
        return None
    if block.get("external_enabled") is not False:
        return None
    if block.get("target_port") != EXPECTED_TARGET_PORT:
        return None
    traffic = block.get("traffic_weight")
    if not isinstance(traffic, list) or len(traffic) != 1:
        return None
    weight = traffic[0]
    if not isinstance(weight, dict):
        return None
    if weight.get("percentage") != 100 or weight.get("latest_revision") is not True:
        return None
    revision_suffix = weight.get("revision_suffix")
    label = weight.get("label")
    return (
        "internal",
        EXPECTED_TARGET_PORT,
        100,
        True,
        revision_suffix,
        label,
    )


def _internal_ingress_ok(side: dict | None) -> bool:
    if not isinstance(side, dict):
        return False
    fingerprint = _ingress_fingerprint(side.get("ingress"))
    return fingerprint is not None and fingerprint[0] == "internal"


def _identity_fingerprint(side: dict | None) -> tuple | None:
    if not isinstance(side, dict):
        return None
    identity = side.get("identity")
    if not isinstance(identity, list) or len(identity) != 1:
        return None
    block = identity[0]
    if not isinstance(block, dict):
        return None
    identity_type = block.get("type")
    if identity_type == "UserAssigned":
        identity_ids = block.get("identity_ids")
        if not isinstance(identity_ids, list):
            return None
        return ("UserAssigned", tuple(sorted(str(item) for item in identity_ids)))
    if identity_type == "SystemAssigned":
        return ("SystemAssigned",)
    return None


def _registry_fingerprint(side: dict | None) -> tuple | None:
    if not isinstance(side, dict):
        return None
    registry = side.get("registry")
    if not isinstance(registry, list) or len(registry) != 1:
        return None
    block = registry[0]
    if not isinstance(block, dict):
        return None
    server = block.get("server")
    identity = block.get("identity")
    if not isinstance(server, str) or not isinstance(identity, str):
        return None
    return (server, identity)


def _secrets_fingerprint(side: dict | None) -> frozenset[tuple] | None:
    if not isinstance(side, dict):
        return None
    secrets = side.get("secret")
    if not isinstance(secrets, list):
        return None
    items: list[tuple] = []
    for secret in secrets:
        if not isinstance(secret, dict):
            return None
        name = secret.get("name")
        value = secret.get("value")
        if not isinstance(name, str) or not isinstance(value, str):
            return None
        items.append((name, value))
    return frozenset(items)


def _non_demo_fingerprint(side: dict | None) -> tuple | None:
    if not isinstance(side, dict):
        return None
    template = _template(side)
    container = _container(side)
    if template is None or container is None:
        return None
    env = _env_fingerprint(side)
    if env is None:
        return None
    ingress = _ingress_fingerprint(side.get("ingress"))
    if ingress is None:
        return None
    identity = _identity_fingerprint(side)
    registry = _registry_fingerprint(side)
    secrets = _secrets_fingerprint(side)
    if identity is None or registry is None or secrets is None:
        return None
    return (
        ingress,
        template.get("min_replicas"),
        template.get("max_replicas"),
        container.get("cpu"),
        container.get("memory"),
        identity,
        registry,
        secrets,
        env,
    )


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
        if not _internal_ingress_ok(side):
            errors.append(
                f"FAIL [ingress] {TARGET_ADDRESS} {label} internal ingress is not the expected "
                f"internal-only shape (external_enabled=false, target_port={EXPECTED_TARGET_PORT}, "
                "traffic_weight percentage=100 latest_revision=true)."
            )
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

    before_fp = _non_demo_fingerprint(before)
    after_fp = _non_demo_fingerprint(after)
    if before_fp is None or after_fp is None or before_fp != after_fp:
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
