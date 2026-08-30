#!/usr/bin/env python3
"""Spec A checkpoint 9.12 exact-scope Terraform plan assertion."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys

from assert_spec_a_9_9_plan import parse_expected_image_tags

TARGET_ADDRESS = "module.portfolio_service.azurerm_container_app.this"
DEMO_ENV = "APP_DEMO_SEED_ON_STARTUP"
TX_DIAG_ENV = "APP_DEMO_TX_DIAGNOSTICS"
SERVICE_VERSION_ENV = "SERVICE_VERSION"
ACR_LOGIN_SERVER = "wealthprodacr.azurecr.io"
IMAGE_REPOSITORY = "portfolio-service"
EXPECTED_TARGET_PORT = 8080

DEMO_SCOPED_PROFILES = ("spec-a-9.12-enable", "spec-a-9.12-disable")
TX_DIAG_SCOPED_PROFILES = ("spec-a-9.12-tx-diag-enable", "spec-a-9.12-tx-diag-disable")
SCOPED_PROFILES = DEMO_SCOPED_PROFILES + TX_DIAG_SCOPED_PROFILES
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
)

_DEMO_SENTINEL = "__SPEC_A_9_12_DEMO_SENTINEL__"
_TX_DIAG_SENTINEL = "__SPEC_A_9_12_TX_DIAG_SENTINEL__"
_DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\Z")
_VERSION_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
_ENV_ALLOWED_KEYS = frozenset({"name", "value", "secret_name"})


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


def _is_strict_plain_demo_entry(entry: dict) -> bool:
    return _is_strict_plain_env_entry(entry, DEMO_ENV)


def _is_strict_plain_tx_diag_entry(entry: dict) -> bool:
    return _is_strict_plain_env_entry(entry, TX_DIAG_ENV)


def _canonical_env_entry(entry: dict) -> dict | None:
    name = entry.get("name")
    if not isinstance(name, str) or name in (DEMO_ENV, TX_DIAG_ENV):
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


def _has_duplicate_or_invalid_env(side: dict | None) -> bool:
    raw = _raw_env(side)
    named = _named_env(side)
    names = [entry["name"] for entry in named]
    if len(raw) != len(named) or len(names) != len(set(names)):
        return True
    for entry in named:
        name = entry.get("name")
        if name == DEMO_ENV:
            if not _is_strict_plain_demo_entry(entry):
                return True
        elif name == TX_DIAG_ENV:
            if not _is_strict_plain_tx_diag_entry(entry):
                return True
        elif _canonical_env_entry(entry) is None:
            return True
    return False


def _image(side: dict | None) -> str | None:
    container = _container(side)
    value = container.get("image") if container else None
    return value if isinstance(value, str) else None


def _min_replicas(side: dict | None):
    template = _template(side)
    return template.get("min_replicas") if template else None


def _literal_value(entry: dict) -> str | None:
    value = entry.get("value")
    return value if isinstance(value, str) else None


def _canonical_env_entry_tuple(entry: dict) -> tuple:
    return tuple(sorted((key, entry[key]) for key in entry))


def _demo_false(side: dict | None) -> bool:
    entries = _entries(side, DEMO_ENV)
    return len(entries) == 0 or (
        len(entries) == 1 and _literal_value(entries[0]) == "false"
    )


def _tx_diag_false(side: dict | None) -> bool:
    entries = _entries(side, TX_DIAG_ENV)
    return len(entries) == 0 or (
        len(entries) == 1 and _literal_value(entries[0]) == "false"
    )


def _normalize(
    side: dict | None,
    *,
    allow_missing_demo: bool,
    allow_missing_tx_diag: bool,
) -> dict | None:
    if not isinstance(side, dict):
        return None
    if _has_duplicate_or_invalid_env(side):
        return None

    normalized = copy.deepcopy(side)
    container = _container(normalized)
    if container is None:
        return None
    env = container.get("env")
    if not isinstance(env, list):
        return None

    canonical_env: list[dict] = []
    demo_seen = False
    tx_diag_seen = False
    for entry in env:
        if not isinstance(entry, dict):
            return None
        name = entry.get("name")
        if name == DEMO_ENV:
            if not _is_strict_plain_demo_entry(entry):
                return None
            demo_seen = True
            canonical_env.append({"name": DEMO_ENV, "value": _DEMO_SENTINEL})
            continue
        if name == TX_DIAG_ENV:
            if not _is_strict_plain_tx_diag_entry(entry):
                return None
            tx_diag_seen = True
            canonical_env.append({"name": TX_DIAG_ENV, "value": _TX_DIAG_SENTINEL})
            continue
        canon = _canonical_env_entry(entry)
        if canon is None:
            return None
        canonical_env.append(canon)

    if demo_seen:
        pass
    elif allow_missing_demo:
        canonical_env.append({"name": DEMO_ENV, "value": _DEMO_SENTINEL})
    else:
        return None

    if tx_diag_seen:
        pass
    elif allow_missing_tx_diag:
        canonical_env.append({"name": TX_DIAG_ENV, "value": _TX_DIAG_SENTINEL})
    else:
        return None

    canonical_env.sort(key=lambda item: item["name"])
    container["env"] = canonical_env

    ingress = _ingress_for_compare(normalized.get("ingress"))
    if ingress is None:
        return None
    normalized["ingress"] = ingress

    return normalized


def _check_demo_transition(
    before: dict | None,
    after: dict | None,
    profile: str,
    errors: list[str],
) -> None:
    before_entries = _entries(before, DEMO_ENV)
    after_entries = _entries(after, DEMO_ENV)
    if any(not _is_strict_plain_demo_entry(entry) for entry in before_entries + after_entries):
        errors.append(f"FAIL [demo-binding] {TARGET_ADDRESS} demo env must be plain-valued only.")
        return
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


def _check_tx_diag_transition(
    before: dict | None,
    after: dict | None,
    profile: str,
    errors: list[str],
) -> None:
    if not _demo_false(before) or not _demo_false(after):
        errors.append(
            f"FAIL [demo-invariant] {TARGET_ADDRESS} demo seed must remain false during tx-diag profile."
        )

    before_entries = _entries(before, TX_DIAG_ENV)
    after_entries = _entries(after, TX_DIAG_ENV)
    if any(not _is_strict_plain_tx_diag_entry(entry) for entry in before_entries + after_entries):
        errors.append(
            f"FAIL [tx-diag-binding] {TARGET_ADDRESS} tx-diag env must be plain-valued only."
        )
        return
    if profile == "spec-a-9.12-tx-diag-enable":
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
        errors.append(
            f"FAIL [tx-diag-direction] {TARGET_ADDRESS} has an invalid tx-diag transition."
        )


def _check_transition(
    before: dict | None,
    after: dict | None,
    profile: str,
    errors: list[str],
) -> None:
    if profile in TX_DIAG_SCOPED_PROFILES:
        _check_tx_diag_transition(before, after, profile, errors)
        return

    before_tx = _entries(before, TX_DIAG_ENV)
    after_tx = _entries(after, TX_DIAG_ENV)
    if len(before_tx) > 1 or len(after_tx) > 1:
        errors.append(
            f"FAIL [tx-diag-guard] {TARGET_ADDRESS} has duplicate tx-diag env entries."
        )
    elif not _tx_diag_false(before) or not _tx_diag_false(after):
        errors.append(
            f"FAIL [tx-diag-invariant] {TARGET_ADDRESS} tx-diag must remain false during demo profile."
        )

    _check_demo_transition(before, after, profile, errors)


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
                "transport=auto, traffic_weight percentage=100 latest_revision=true)."
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


def _guard_env_mutations(plan: dict, env_name: str, guard_label: str) -> list[str]:
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
        before_entries = _entries(before, env_name)
        after_entries = _entries(after, env_name)
        before_sigs = [_canonical_env_entry_tuple(entry) for entry in before_entries]
        after_sigs = [_canonical_env_entry_tuple(entry) for entry in after_entries]
        if (
            len(before_entries) > 1
            or len(after_entries) > 1
            or before_sigs != after_sigs
        ):
            errors.append(
                f"FAIL [{guard_label}] {TARGET_ADDRESS} changes the {env_name} environment "
                "outside checkpoint 9.12."
            )
    return errors


def _evaluate_non_scoped(plan: dict) -> list[str]:
    errors = _guard_env_mutations(plan, DEMO_ENV, "demo-guard")
    errors.extend(_guard_env_mutations(plan, TX_DIAG_ENV, "tx-diag-guard"))
    return errors


def _normalize_flags(profile: str) -> tuple[bool, bool]:
    if profile == "spec-a-9.12-enable":
        return True, True
    if profile == "spec-a-9.12-disable":
        return False, True
    if profile == "spec-a-9.12-tx-diag-enable":
        return False, True
    if profile == "spec-a-9.12-tx-diag-disable":
        return False, False
    raise ValueError(f"unsupported scoped profile: {profile}")


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

    allow_missing_demo, allow_missing_tx_diag = _normalize_flags(profile)
    normalized_before = _normalize(
        before,
        allow_missing_demo=allow_missing_demo,
        allow_missing_tx_diag=allow_missing_tx_diag,
    )
    normalized_after = _normalize(
        after,
        allow_missing_demo=False,
        allow_missing_tx_diag=profile not in TX_DIAG_SCOPED_PROFILES,
    )
    if (
        normalized_before is None
        or normalized_after is None
        or normalized_before != normalized_after
    ):
        errors.append(
            f"FAIL [field] {TARGET_ADDRESS} changes a non-scoped field."
        )
    return errors


def _evaluate_9_13_portfolio_scale(
    plan: dict,
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
    portfolio_changes = [
        change
        for change in changes
        if isinstance(change, dict)
        and change.get("address") == TARGET_ADDRESS
        and not _is_noop(change)
    ]
    if len(portfolio_changes) != 1:
        return [f"FAIL [replicas] {TARGET_ADDRESS} is not an in-place 9.13 scale restore."]

    target = portfolio_changes[0]
    change = target.get("change") or {}
    if list(change.get("actions") or []) != ["update"]:
        return [f"FAIL [action] {TARGET_ADDRESS} is not an in-place update."]

    before = change.get("before")
    after = change.get("after")
    if _min_replicas(before) != 1 or _min_replicas(after) not in (0, None):
        errors.append(f"FAIL [replicas] {TARGET_ADDRESS} min_replicas must move 1 -> 0.")

    for label, side in (("before", before), ("after", after)):
        demo_entries = _entries(side, DEMO_ENV)
        if (
            len(demo_entries) != 1
            or not _is_strict_plain_demo_entry(demo_entries[0])
            or _literal_value(demo_entries[0]) != "false"
        ):
            errors.append(
                f"FAIL [demo] {TARGET_ADDRESS} {label} demo seed must remain literal false."
            )
        diag_entries = _entries(side, TX_DIAG_ENV)
        if (
            len(diag_entries) != 1
            or not _is_strict_plain_tx_diag_entry(diag_entries[0])
            or _literal_value(diag_entries[0]) != "false"
        ):
            errors.append(
                f"FAIL [tx-diag] {TARGET_ADDRESS} {label} diagnostics must remain literal false."
            )
        if not _internal_ingress_ok(side):
            errors.append(
                f"FAIL [ingress] {TARGET_ADDRESS} {label} internal ingress is not the expected "
                f"internal-only shape (external_enabled=false, target_port={EXPECTED_TARGET_PORT}, "
                "transport=auto, traffic_weight percentage=100 latest_revision=true)."
            )
        expected_image = f"{ACR_LOGIN_SERVER}/{IMAGE_REPOSITORY}@{expected_image_digest}"
        if _image(side) != expected_image:
            errors.append(f"FAIL [image] {TARGET_ADDRESS} {label} image digest pin is invalid.")
        versions = _entries(side, SERVICE_VERSION_ENV)
        if len(versions) != 1 or _literal_value(versions[0]) != expected_service_version:
            errors.append(
                f"FAIL [service_version] {TARGET_ADDRESS} {label} SERVICE_VERSION pin is invalid."
            )
        if _has_duplicate_or_invalid_env(side):
            errors.append(
                f"FAIL [environment] {TARGET_ADDRESS} {label} has invalid or duplicate environment entries."
            )

    normalized_before = _normalize(
        before, allow_missing_demo=False, allow_missing_tx_diag=False
    )
    normalized_after = _normalize(
        after, allow_missing_demo=False, allow_missing_tx_diag=False
    )
    if normalized_before is not None:
        template = _template(normalized_before)
        if template is not None:
            template["min_replicas"] = "__SPEC_A_9_13_MIN_REPLICAS_SENTINEL__"
    if normalized_after is not None:
        template = _template(normalized_after)
        if template is not None:
            template["min_replicas"] = "__SPEC_A_9_13_MIN_REPLICAS_SENTINEL__"
    if (
        normalized_before is None
        or normalized_after is None
        or normalized_before != normalized_after
    ):
        errors.append(f"FAIL [field] {TARGET_ADDRESS} changes a non-scoped field.")
    return errors


def evaluate_plan(
    plan: dict,
    profile: str,
    expected_image_digest: str,
    expected_image_tags: dict[str, str],
) -> list[str]:
    if profile not in KNOWN_PROFILES:
        return ["FAIL [profile] unknown change profile; fail closed."]
    expected_service_version = expected_image_tags["portfolio-service"]
    if profile == "spec-a-9.13-restore-scale":
        return _evaluate_9_13_portfolio_scale(
            plan,
            expected_image_digest,
            expected_service_version,
        )
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
    parser.add_argument("--expected-image-tags-json", required=True)
    args = parser.parse_args()

    try:
        expected_image_tags = parse_expected_image_tags(args.expected_image_tags_json)
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
