#!/usr/bin/env python3
"""Spec A checkpoint 9.13 exact-scope Terraform plan assertion (restore scale-to-zero)."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys

from assert_spec_a_9_9_plan import (
    canonical_image_digests,
    change_payload_errors,
    parse_expected_image_digests,
    parse_expected_image_tags,
)

PROFILE = "spec-a-9.13-restore-scale"
SERVICE_ADDRESSES = (
    "module.portfolio_service.azurerm_container_app.this",
    "module.market_data_service.azurerm_container_app.this",
    "module.insight_service.azurerm_container_app.this",
)
PORTFOLIO_ADDR = SERVICE_ADDRESSES[0]
OVERRIDE_ENV_NAMES = (
    "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS",
    "APP_CATALOG_ENFORCE_HOLDING_INVARIANT",
)
DEMO_ENV = "APP_DEMO_SEED_ON_STARTUP"
TX_DIAG_ENV = "APP_DEMO_TX_DIAGNOSTICS"
SERVICE_VERSION_ENV = "SERVICE_VERSION"
ACR_LOGIN_SERVER = "wealthprodacr.azurecr.io"
SERVICE_IMAGE_REPOSITORIES: dict[str, str] = {
    "module.portfolio_service.azurerm_container_app.this": "portfolio-service",
    "module.market_data_service.azurerm_container_app.this": "market-data-service",
    "module.insight_service.azurerm_container_app.this": "insight-service",
}
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
    PROFILE,
    "spec-a-9.14-reopen-ingress",
    "spec-a-9.14-close-ingress",
    "api-gateway-custom-domain-restore",
    "api-gateway-custom-domain-remove",
)
_DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\Z")
_VERSION_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
_ENV_ALLOWED_KEYS = frozenset({"name", "value", "secret_name"})
_SCALE_SENTINEL = "__SPEC_A_9_13_MIN_REPLICAS_SENTINEL__"
EXPECTED_TARGET_PORT = 8080


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


def _is_strict_plain_env_entry(entry: dict, env_name: str) -> bool:
    if entry.get("name") != env_name:
        return False
    if not set(entry.keys()).issubset(_ENV_ALLOWED_KEYS):
        return False
    if not _inactive(entry.get("secret_name")):
        return False
    if "value" not in entry or not isinstance(entry.get("value"), str):
        return False
    return True


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


def _internal_ingress_ok(side: dict | None) -> bool:
    if not isinstance(side, dict):
        return False
    ingress = side.get("ingress")
    if ingress is None or ingress == []:
        return False
    if not isinstance(ingress, list) or len(ingress) != 1:
        return False
    block = ingress[0]
    if not isinstance(block, dict):
        return False
    if block.get("external_enabled") is not False:
        return False
    if block.get("target_port") != EXPECTED_TARGET_PORT:
        return False
    transport = block.get("transport")
    if not isinstance(transport, str) or transport.lower() != "auto":
        return False
    traffic = block.get("traffic_weight")
    if not isinstance(traffic, list) or len(traffic) != 1:
        return False
    weight = traffic[0]
    if not isinstance(weight, dict):
        return False
    if weight.get("percentage") != 100 or weight.get("latest_revision") is not True:
        return False
    return True


def _after_min_ok(value) -> bool:
    return value in (0, None)


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
    ingress = _ingress_for_compare(normalized.get("ingress"))
    if ingress is None:
        return None
    normalized["ingress"] = ingress
    return normalized


def _digest_image_ok(image: str | None, repo: str, digest: str) -> bool:
    return image == f"{ACR_LOGIN_SERVER}/{repo}@{digest}"


def _peer_image_ok(image: str | None, repo: str, tag: str, digest: str) -> bool:
    if image == f"{ACR_LOGIN_SERVER}/{repo}:{tag}":
        return True
    return image == f"{ACR_LOGIN_SERVER}/{repo}@{digest}"


def _check_portfolio_flags(side: dict | None, label: str, errors: list[str]) -> None:
    demo = _entries(side, DEMO_ENV)
    diag = _entries(side, TX_DIAG_ENV)
    if len(demo) != 1 or not _is_strict_plain_env_entry(demo[0], DEMO_ENV) or _literal_value(demo[0]) != "false":
        errors.append(f"FAIL [demo] {PORTFOLIO_ADDR} {label} demo seed must be a single literal false.")
    if (
        len(diag) != 1
        or not _is_strict_plain_env_entry(diag[0], TX_DIAG_ENV)
        or _literal_value(diag[0]) != "false"
    ):
        errors.append(
            f"FAIL [tx-diag] {PORTFOLIO_ADDR} {label} diagnostics must be a single literal false."
        )


def _evaluate_scoped(
    plan: dict,
    expected_image_digest: str,
    expected_image_tags: dict[str, str],
    expected_image_digests: dict[str, str],
) -> list[str]:
    errors: list[str] = []
    if not _DIGEST_PATTERN.fullmatch(expected_image_digest):
        errors.append("FAIL [input] expected image digest is invalid.")
    for service, tag in expected_image_tags.items():
        if not _VERSION_PATTERN.fullmatch(tag):
            errors.append(f"FAIL [input] expected service version for {service} is invalid.")
    if errors:
        return errors

    changes = plan.get("resource_changes")
    if not isinstance(changes, list):
        return ["FAIL [plan] resource changes are invalid."]

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
    if errors:
        return errors

    duplicates = sorted({address for address in seen_addresses if seen_addresses.count(address) > 1})
    if duplicates:
        return [f"FAIL [scope] duplicate resource address(es): {duplicates}"]

    non_noop = [change for change in changes if not _is_noop(change)]
    if len(non_noop) != 3:
        errors.append(
            f"FAIL [scope] expected exactly 3 non-no-op resource changes, got {len(non_noop)}."
        )
    non_noop_addresses = {change.get("address") for change in non_noop}
    expected = set(SERVICE_ADDRESSES)
    if len(non_noop_addresses) != 3:
        errors.append(
            f"FAIL [scope] expected exactly 3 unique target addresses, got {len(non_noop_addresses)}."
        )
    unexpected = non_noop_addresses - expected
    if unexpected:
        errors.append(
            f"FAIL [scope] unexpected non-no-op resource change(s): {sorted(unexpected)}"
        )
    missing = expected - non_noop_addresses
    if missing:
        errors.append(
            f"FAIL [scope] expected a change on {sorted(missing)} but none is present."
        )
    if errors:
        return errors

    by_address = {change.get("address"): change for change in non_noop}
    for address in SERVICE_ADDRESSES:
        resource = by_address.get(address)
        if resource is None:
            continue
        change = resource.get("change") or {}
        if list(change.get("actions") or []) != ["update"]:
            errors.append(
                f"FAIL [action] {address} has actions={list(change.get('actions') or [])}; "
                "expected exactly ['update']."
            )
            continue
        before, after = change.get("before"), change.get("after")
        if _min_replicas(before) != 1:
            errors.append(
                f"FAIL [min_replicas] {address} before={_min_replicas(before)!r}, expected 1."
            )
        if not _after_min_ok(_min_replicas(after)):
            errors.append(
                f"FAIL [min_replicas] {address} after={_min_replicas(after)!r}, expected 0 or unset."
            )
        if _has_duplicate_or_invalid_env(before) or _has_duplicate_or_invalid_env(after):
            errors.append(f"FAIL [environment] {address} has invalid or duplicate environment entries.")
        before_env = {entry["name"]: entry for entry in _named_env(before)}
        after_env = {entry["name"]: entry for entry in _named_env(after)}
        for name in OVERRIDE_ENV_NAMES:
            if name in before_env or name in after_env:
                errors.append(
                    f"FAIL [override] {address} must keep {name} absent before and after."
                )
        repo = SERVICE_IMAGE_REPOSITORIES[address]
        expected_tag = expected_image_tags[repo]
        before_image, after_image = _image(before), _image(after)
        if address == PORTFOLIO_ADDR:
            for label, image in (("before", before_image), ("after", after_image)):
                if not _digest_image_ok(image, repo, expected_image_digest):
                    errors.append(
                        f"FAIL [image] {address} {label} image digest pin is invalid."
                    )
            _check_portfolio_flags(before, "before", errors)
            _check_portfolio_flags(after, "after", errors)
        else:
            expected_peer_digest = expected_image_digests[repo]
            for label, image in (("before", before_image), ("after", after_image)):
                if not _peer_image_ok(image, repo, expected_tag, expected_peer_digest):
                    errors.append(f"FAIL [image] {address} {label} image identity is invalid.")
            if before_image != after_image:
                errors.append(f"FAIL [image] {address} image must remain unchanged.")
        for label, side in (("before", before), ("after", after)):
            versions = _entries(side, SERVICE_VERSION_ENV)
            if len(versions) != 1 or _literal_value(versions[0]) != expected_tag:
                errors.append(
                    f"FAIL [service_version] {address} {label} SERVICE_VERSION pin is invalid."
                )
            if not _internal_ingress_ok(side):
                errors.append(
                    f"FAIL [ingress] {address} {label} internal ingress is not the expected shape."
                )
        normalized_before = _normalize(before)
        normalized_after = _normalize(after)
        if (
            normalized_before is None
            or normalized_after is None
            or normalized_before != normalized_after
        ):
            errors.append(f"FAIL [field] {address} changes a field other than min_replicas.")
    return errors


def _evaluate_non_scoped(plan: dict, profile: str) -> list[str]:
    errors: list[str] = []
    changes = plan.get("resource_changes")
    if not isinstance(changes, list):
        return ["FAIL [plan] resource changes are invalid."]
    for resource_change in changes:
        if not isinstance(resource_change, dict):
            continue
        address = resource_change.get("address")
        if address not in SERVICE_ADDRESSES or _is_noop(resource_change):
            continue
        change = resource_change.get("change") or {}
        before_min = _min_replicas(change.get("before"))
        after_min = _min_replicas(change.get("after"))
        before_norm = 0 if before_min is None else before_min
        after_norm = 0 if after_min is None else after_min
        if before_norm != after_norm:
            errors.append(
                f"FAIL [scale-guard] {address} changes min_replicas under "
                f"change_profile={profile}; use {PROFILE}."
            )
    return errors


def evaluate_plan(
    plan: dict,
    profile: str,
    expected_image_digest: str,
    expected_image_tags: dict[str, str],
    expected_image_digests: dict[str, str] | None = None,
) -> list[str]:
    if profile not in KNOWN_PROFILES:
        return ["FAIL [profile] unknown change profile; fail closed."]
    if profile != PROFILE:
        return _evaluate_non_scoped(plan, profile)
    try:
        expected_image_digests = canonical_image_digests(expected_image_digests)
    except ValueError:
        return ["FAIL [input] expected image digests are invalid."]
    return _evaluate_scoped(
        plan,
        expected_image_digest,
        expected_image_tags,
        expected_image_digests,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan_json")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-image-digest", required=True)
    parser.add_argument("--expected-image-tags-json", required=True)
    parser.add_argument("--expected-image-digests-json", default="")
    args = parser.parse_args()

    try:
        expected_image_tags = parse_expected_image_tags(args.expected_image_tags_json)
    except ValueError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    expected_image_digests = None
    if args.profile == PROFILE or args.expected_image_digests_json.strip():
        try:
            expected_image_digests = parse_expected_image_digests(args.expected_image_digests_json)
        except ValueError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1

    try:
        plan = load_plan(args.plan_json)
    except (OSError, json.JSONDecodeError):
        print("ERROR: Failed to load Terraform plan JSON.", file=sys.stderr)
        return 1

    errors = evaluate_plan(
        plan,
        args.profile,
        args.expected_image_digest,
        expected_image_tags,
        expected_image_digests,
    )
    if errors:
        print(f"SPEC A 9.13 PLAN ASSERTION FAILED (profile={args.profile}):")
        for error in errors:
            print(f"  {error}")
        return 1
    print(f"PASS spec-a-9.13 plan guard (profile={args.profile}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
