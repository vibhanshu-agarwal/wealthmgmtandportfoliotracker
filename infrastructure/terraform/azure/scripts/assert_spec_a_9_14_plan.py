#!/usr/bin/env python3
"""Spec A checkpoint 9.14 exact-scope Terraform plan assertion (gateway ingress reopen/close)."""

from __future__ import annotations

import argparse
import copy
import json
import sys

from assert_spec_a_9_9_plan import change_payload_errors

REOPEN_PROFILE = "spec-a-9.14-reopen-ingress"
CLOSE_PROFILE = "spec-a-9.14-close-ingress"
SCOPED_PROFILES = (REOPEN_PROFILE, CLOSE_PROFILE)
GATEWAY_ADDR = "module.api_gateway.azurerm_container_app.this"
SERVICE_VERSION_ENV = "SERVICE_VERSION"
EXPECTED_TARGET_PORT = 8080
_SCALE_SENTINEL = "__SPEC_A_9_14_MIN_REPLICAS_SENTINEL__"
_INGRESS_SENTINEL = "__SPEC_A_9_14_INGRESS_SENTINEL__"
_ENV_ALLOWED_KEYS = frozenset({"name", "value", "secret_name"})
# Provider-computed ingress fields (azurerm_container_app.ingress); all other keys are operator-controlled.
_INGRESS_COMPUTED_KEYS = frozenset({"fqdn"})
# Provider/API defaults omitted from module HCL but commonly present in plan JSON.
_INGRESS_PROVIDER_DEFAULTS = {"allow_insecure_connections": False}
# Neutral azurerm 4.81 plan/state fields equivalent to omission (strip before key compare).
_INGRESS_NEUTRAL_ARRAY_KEYS = frozenset(
    {"custom_domain", "cors", "ip_security_restriction"}
)
_INGRESS_OPERATOR_KEYS = frozenset(
    {"external_enabled", "target_port", "transport", "traffic_weight"}
)
_TRAFFIC_WEIGHT_OPERATOR_KEYS = frozenset({"percentage", "latest_revision"})
_TRAFFIC_WEIGHT_NEUTRAL_KEYS = frozenset({"label", "revision_suffix"})
KNOWN_PROFILES = (
    "standard",
    "spec-a-9.9-enable",
    "spec-a-9.9-abort",
    "spec-a-9.11-enable",
    "spec-a-9.11-abort",
    "spec-a-9.12-enable",
    "spec-a-9.12-disable",
    "spec-a-9.12-tx-diag-enable",
    "spec-a-9.12-tx-diag-disable",
    "spec-a-9.13-restore-scale",
    REOPEN_PROFILE,
    CLOSE_PROFILE,
    "api-gateway-custom-domain-restore",
    "api-gateway-custom-domain-remove",
)


def load_plan(path: str) -> dict:
    with open(path, encoding="utf-8") as plan_file:
        return json.load(plan_file)


def _is_noop(resource_change: dict) -> bool:
    change = resource_change.get("change")
    if not isinstance(change, dict):
        return False
    if list(change.get("actions") or []) != ["no-op"]:
        return False
    return isinstance(change.get("before"), dict) and isinstance(change.get("after"), dict)


def _template(side: dict | None) -> dict | None:
    if not isinstance(side, dict):
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


def _inactive(value) -> bool:
    return value is None or value == ""


def _canonical_env_entry(entry: dict) -> dict | None:
    name = entry.get("name")
    if not isinstance(name, str):
        return None
    if not set(entry.keys()).issubset(_ENV_ALLOWED_KEYS):
        return None
    secret_name = entry.get("secret_name")
    value = entry.get("value")
    if not _inactive(secret_name):
        if not _inactive(value):
            return None
        if not isinstance(secret_name, str):
            return None
        return {"name": name, "secret_name": secret_name}
    if value is None or not isinstance(value, str):
        return None
    return {"name": name, "value": value}


def _has_duplicate_or_invalid_env(side: dict | None) -> bool:
    raw = _raw_env(side)
    named = _named_env(side)
    names = [entry["name"] for entry in named]
    if len(raw) != len(named) or len(names) != len(set(names)):
        return True
    for entry in named:
        if _canonical_env_entry(entry) is None:
            return True
    return False


def _min_replicas(side: dict | None):
    template = _template(side)
    return template.get("min_replicas") if template else None


def _image(side: dict | None) -> str | None:
    container = _container(side)
    value = container.get("image") if container else None
    return value if isinstance(value, str) else None


def _literal_value(entry: dict) -> str | None:
    value = entry.get("value")
    return value if isinstance(value, str) else None


def _ingress_absent(side: dict | None) -> bool:
    if not isinstance(side, dict):
        return False
    ingress = side.get("ingress")
    return ingress is None or ingress == []


def _is_neutral_ingress_value(key: str, value) -> bool:
    if key in _INGRESS_NEUTRAL_ARRAY_KEYS:
        return value == [] or value is None
    if key == "exposed_port":
        return value == 0 or value is None
    if key == "client_certificate_mode":
        return value is None
    return False


def _canonical_traffic_weight(weight) -> dict | None:
    if not isinstance(weight, dict):
        return None
    canonical: dict = {}
    for key, value in weight.items():
        if key in _TRAFFIC_WEIGHT_NEUTRAL_KEYS and _inactive(value):
            continue
        canonical[key] = value
    if set(canonical.keys()) != _TRAFFIC_WEIGHT_OPERATOR_KEYS:
        return None
    if canonical.get("percentage") != 100 or canonical.get("latest_revision") is not True:
        return None
    return canonical


def _ingress_for_compare(ingress) -> list | None:
    if ingress is None or ingress == []:
        return []
    if not isinstance(ingress, list):
        return None
    blocks = copy.deepcopy(ingress)
    for block in blocks:
        if not isinstance(block, dict):
            return None
        traffic = block.get("traffic_weight")
        if isinstance(traffic, list):
            block["traffic_weight"] = sorted(
                traffic,
                key=lambda item: json.dumps(item, sort_keys=True, default=str),
            )
    return blocks


def _canonical_external_ingress(side: dict | None) -> dict | None:
    """Return the expected operator-controlled ingress block, or None if unsafe/invalid."""
    if not isinstance(side, dict):
        return None
    ingress = side.get("ingress")
    if ingress is None or ingress == []:
        return None
    if not isinstance(ingress, list) or len(ingress) != 1:
        return None
    block = ingress[0]
    if not isinstance(block, dict):
        return None

    effective: dict = {}
    for key, value in block.items():
        if key in _INGRESS_COMPUTED_KEYS:
            continue
        if key in _INGRESS_PROVIDER_DEFAULTS and value == _INGRESS_PROVIDER_DEFAULTS[key]:
            continue
        if _is_neutral_ingress_value(key, value):
            continue
        effective[key] = value
    if set(effective.keys()) != _INGRESS_OPERATOR_KEYS:
        return None
    if effective.get("external_enabled") is not True:
        return None
    if effective.get("target_port") != EXPECTED_TARGET_PORT:
        return None
    transport = effective.get("transport")
    if not isinstance(transport, str) or transport.lower() != "auto":
        return None

    traffic = effective.get("traffic_weight")
    if not isinstance(traffic, list) or len(traffic) != 1:
        return None
    if _canonical_traffic_weight(traffic[0]) is None:
        return None

    return {
        "external_enabled": True,
        "target_port": EXPECTED_TARGET_PORT,
        "transport": "auto",
        "traffic_weight": [{"percentage": 100, "latest_revision": True}],
    }


def _external_ingress_ok(side: dict | None) -> bool:
    return _canonical_external_ingress(side) is not None


def _after_min_ok(value) -> bool:
    return value in (0, None)


def _ingress_transition(before: dict | None, after: dict | None) -> bool:
    before_absent = _ingress_absent(before)
    after_absent = _ingress_absent(after)
    if before_absent != after_absent:
        return True
    if before_absent:
        return False
    before_ingress = _ingress_for_compare((before or {}).get("ingress"))
    after_ingress = _ingress_for_compare((after or {}).get("ingress"))
    return before_ingress != after_ingress


def _normalize(side: dict | None) -> dict | None:
    if not isinstance(side, dict) or _has_duplicate_or_invalid_env(side):
        return None
    normalized = copy.deepcopy(side)
    template = _template(normalized)
    if template is None:
        return None
    template["min_replicas"] = _SCALE_SENTINEL
    container = _container(normalized)
    if container is None:
        return None
    env = container.get("env")
    if not isinstance(env, list):
        return None
    canonical_env: list[dict] = []
    for entry in env:
        if not isinstance(entry, dict):
            return None
        canon = _canonical_env_entry(entry)
        if canon is None:
            return None
        canonical_env.append(canon)
    canonical_env.sort(key=lambda item: item["name"])
    container["env"] = canonical_env
    normalized["ingress"] = _INGRESS_SENTINEL
    return normalized


def _collect_plan_errors(plan: dict) -> tuple[list[str], list[dict]]:
    errors: list[str] = []
    changes = plan.get("resource_changes")
    if not isinstance(changes, list):
        return ["FAIL [plan] resource changes are invalid."], []

    seen_addresses: list[str] = []
    for index, change in enumerate(changes):
        if not isinstance(change, dict):
            errors.append(
                f"FAIL [plan] resource_changes[{index}] is not a resource-change object."
            )
            continue
        address = change.get("address")
        if not isinstance(address, str) or not address:
            errors.append(
                f"FAIL [plan] resource_changes[{index}] is missing a resource address."
            )
            continue
        seen_addresses.append(address)
        errors.extend(change_payload_errors(change, index=index))

    duplicates = sorted({address for address in seen_addresses if seen_addresses.count(address) > 1})
    if duplicates:
        errors.append(f"FAIL [scope] duplicate resource address(es): {duplicates}")
    return errors, changes


def _evaluate_scoped(plan: dict, profile: str) -> list[str]:
    errors, changes = _collect_plan_errors(plan)
    if errors:
        return errors

    non_noop = [change for change in changes if not _is_noop(change)]
    if len(non_noop) != 1:
        errors.append(
            f"FAIL [scope] expected exactly 1 non-no-op resource change, got {len(non_noop)}."
        )
    non_noop_addresses = {change.get("address") for change in non_noop}
    if non_noop_addresses != {GATEWAY_ADDR}:
        unexpected = non_noop_addresses - {GATEWAY_ADDR}
        missing = {GATEWAY_ADDR} - non_noop_addresses
        if unexpected:
            errors.append(
                f"FAIL [scope] unexpected non-no-op resource change(s): {sorted(unexpected)}"
            )
        if missing:
            errors.append(
                f"FAIL [scope] expected a change on {GATEWAY_ADDR} but none is present."
            )
    if errors:
        return errors

    resource = non_noop[0]
    change = resource.get("change") or {}
    if list(change.get("actions") or []) != ["update"]:
        errors.append(
            f"FAIL [action] {GATEWAY_ADDR} has actions={list(change.get('actions') or [])}; "
            "expected exactly ['update']."
        )
        return errors

    before, after = change.get("before"), change.get("after")
    expect_absent_before = profile == REOPEN_PROFILE
    expect_external_after = profile == REOPEN_PROFILE
    if expect_absent_before:
        if not _ingress_absent(before):
            errors.append(
                f"FAIL [ingress] {GATEWAY_ADDR} before must have ingress absent/disabled "
                f"for profile={profile}."
            )
        if not _external_ingress_ok(after):
            errors.append(
                f"FAIL [ingress] {GATEWAY_ADDR} after must expose one external ingress block "
                f"for profile={profile}."
            )
    else:
        if not _external_ingress_ok(before):
            errors.append(
                f"FAIL [ingress] {GATEWAY_ADDR} before must expose one external ingress block "
                f"for profile={profile}."
            )
        if not _ingress_absent(after):
            errors.append(
                f"FAIL [ingress] {GATEWAY_ADDR} after must have ingress absent/disabled "
                f"for profile={profile}."
            )

    if not _after_min_ok(_min_replicas(before)):
        errors.append(
            f"FAIL [min_replicas] {GATEWAY_ADDR} before={_min_replicas(before)!r}, "
            "expected 0 or unset."
        )
    if not _after_min_ok(_min_replicas(after)):
        errors.append(
            f"FAIL [min_replicas] {GATEWAY_ADDR} after={_min_replicas(after)!r}, "
            "expected 0 or unset."
        )

    before_image, after_image = _image(before), _image(after)
    if before_image != after_image:
        errors.append(
            f"FAIL [image] {GATEWAY_ADDR} image must remain byte-for-byte unchanged "
            f"({before_image!r} -> {after_image!r})."
        )

    if _has_duplicate_or_invalid_env(before) or _has_duplicate_or_invalid_env(after):
        errors.append(f"FAIL [environment] {GATEWAY_ADDR} has invalid or duplicate environment entries.")

    before_versions = _entries(before, SERVICE_VERSION_ENV)
    after_versions = _entries(after, SERVICE_VERSION_ENV)
    if len(before_versions) != 1 or len(after_versions) != 1:
        errors.append(
            f"FAIL [service_version] {GATEWAY_ADDR} must keep exactly one SERVICE_VERSION entry."
        )
    elif _literal_value(before_versions[0]) != _literal_value(after_versions[0]):
        errors.append(
            f"FAIL [service_version] {GATEWAY_ADDR} SERVICE_VERSION must remain unchanged."
        )

    normalized_before = _normalize(before)
    normalized_after = _normalize(after)
    if (
        normalized_before is None
        or normalized_after is None
        or normalized_before != normalized_after
    ):
        errors.append(
            f"FAIL [field] {GATEWAY_ADDR} changes a field other than ingress."
        )
    return errors


def _evaluate_non_scoped(plan: dict, profile: str) -> list[str]:
    errors: list[str] = []
    changes = plan.get("resource_changes")
    if not isinstance(changes, list):
        return ["FAIL [plan] resource changes are invalid."]
    for resource_change in changes:
        if not isinstance(resource_change, dict):
            continue
        if resource_change.get("address") != GATEWAY_ADDR or _is_noop(resource_change):
            continue
        change = resource_change.get("change") or {}
        before, after = change.get("before"), change.get("after")
        if _ingress_transition(before, after):
            errors.append(
                f"FAIL [ingress-guard] {GATEWAY_ADDR} changes ingress under "
                f"change_profile={profile}; use {REOPEN_PROFILE} or {CLOSE_PROFILE}."
            )
    return errors


def evaluate_plan(plan: dict, profile: str) -> list[str]:
    if profile not in KNOWN_PROFILES:
        return ["FAIL [profile] unknown change profile; fail closed."]
    if profile in SCOPED_PROFILES:
        return _evaluate_scoped(plan, profile)
    return _evaluate_non_scoped(plan, profile)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan_json")
    parser.add_argument("--profile", required=True)
    args = parser.parse_args()

    try:
        plan = load_plan(args.plan_json)
    except (OSError, json.JSONDecodeError):
        print("ERROR: Failed to load Terraform plan JSON.", file=sys.stderr)
        return 1

    errors = evaluate_plan(plan, args.profile)
    if errors:
        print(f"SPEC A 9.14 PLAN ASSERTION FAILED (profile={args.profile}):")
        for error in errors:
            print(f"  {error}")
        return 1
    print(f"PASS spec-a-9.14 plan guard (profile={args.profile}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
