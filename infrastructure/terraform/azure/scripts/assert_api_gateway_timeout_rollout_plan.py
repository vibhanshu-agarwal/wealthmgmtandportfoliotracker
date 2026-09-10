#!/usr/bin/env python3
"""Fail-closed exact-scope guard for the api-gateway timeout rollout."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys

PROFILE = "api-gateway-timeout-rollout"
GATEWAY_ADDR = "module.api_gateway.azurerm_container_app.this"
TIMEOUT_ENV = {
    "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT": "120s",
    "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT": "30s",
    "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT": "165s",
    "SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT": "150s",
}
_ENV_KEYS = frozenset({"name", "value", "secret_name"})
KNOWN_PROFILES = ("standard", "spec-a-9.9-enable", "spec-a-9.9-abort", "spec-a-9.11-enable", "spec-a-9.11-abort", "spec-a-9.12-enable", "spec-a-9.12-disable", "spec-a-9.12-tx-diag-enable", "spec-a-9.12-tx-diag-disable", "spec-a-9.13-restore-scale", "spec-a-9.14-reopen-ingress", "spec-a-9.14-close-ingress", "api-gateway-custom-domain-restore", "api-gateway-custom-domain-remove", PROFILE)
_TAG = re.compile(r"^[0-9a-f]{40}$")
_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


def load_plan(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def _canonical_env(entry: object) -> dict | None:
    if not isinstance(entry, dict) or not isinstance(entry.get("name"), str):
        return None
    if not set(entry).issubset(_ENV_KEYS):
        return None
    name, value, secret_name = entry["name"], entry.get("value"), entry.get("secret_name")
    if secret_name not in (None, ""):
        if isinstance(secret_name, str) and value in (None, ""):
            return {"name": name, "secret_name": secret_name}
        return None
    if not isinstance(value, str):
        return None
    return {"name": name, "value": value}


def _container(side: object) -> dict | None:
    if not isinstance(side, dict):
        return None
    templates = side.get("template")
    if not isinstance(templates, list) or len(templates) != 1 or not isinstance(templates[0], dict):
        return None
    containers = templates[0].get("container")
    if not isinstance(containers, list) or len(containers) != 1 or not isinstance(containers[0], dict):
        return None
    return containers[0]


def _env_map(side: object) -> dict[str, dict] | None:
    container = _container(side)
    if container is None or not isinstance(container.get("env"), list):
        return None
    env: dict[str, dict] = {}
    for entry in container["env"]:
        canonical = _canonical_env(entry)
        if canonical is None or canonical["name"] in env:
            return None
        env[canonical["name"]] = canonical
    return env


def _normalized_side(side: object) -> dict | None:
    env = _env_map(side)
    if env is None or not isinstance(side, dict):
        return None
    normalized = copy.deepcopy(side)
    container = _container(normalized)
    if container is None:
        return None
    container["env"] = [env[name] for name in sorted(env)]
    return normalized


def _timeout_transition(before: object, after: object) -> bool:
    before_env, after_env = _env_map(before), _env_map(after)
    if before_env is None or after_env is None:
        return True
    return any(before_env.get(name) != after_env.get(name) for name in TIMEOUT_ENV)


def _metadata_has_risk(value: object) -> bool:
    """Only a true/unknown mask is material; false/null/empty shape is harmless."""
    if value is None or value is False or value == "":
        return False
    if isinstance(value, dict):
        return any(_metadata_has_risk(item) for item in value.values())
    if isinstance(value, list):
        return any(_metadata_has_risk(item) for item in value)
    return True


def _sensitive_paths(mask: object, value: object, path: tuple = ()) -> set[tuple] | None:
    if mask is None or mask is False or mask == "":
        return set()
    if mask is True:
        return {path}
    if isinstance(mask, dict):
        if not isinstance(value, dict) or not set(mask).issubset(value):
            return None
        paths: set[tuple] = set()
        for key, child in mask.items():
            nested = _sensitive_paths(child, value[key], path + (key,))
            if nested is None:
                return None
            paths.update(nested)
        return paths
    if isinstance(mask, list):
        if not isinstance(value, list) or len(mask) != len(value):
            return None
        paths: set[tuple] = set()
        env_list = bool(path and path[-1] == "env")
        names: set[str] = set()
        for index, child in enumerate(mask):
            segment: object = index
            if env_list:
                entry = value[index]
                if not isinstance(entry, dict) or not isinstance(entry.get("name"), str) or entry["name"] in names:
                    return None
                names.add(entry["name"])
                segment = entry["name"]
            nested = _sensitive_paths(child, value[index], path + (segment,))
            if nested is None:
                return None
            paths.update(nested)
        return paths
    return None


def _sensitivity_errors(change: dict, before: dict, after: dict, *, prohibit_timeout: bool = False) -> list[str]:
    before_paths = _sensitive_paths(change.get("before_sensitive", {}), before)
    after_paths = _sensitive_paths(change.get("after_sensitive", {}), after)
    if before_paths is None or after_paths is None:
        return ["FAIL [metadata] sensitivity metadata is malformed or contradictory."]
    if before_paths != after_paths:
        return ["FAIL [metadata] sensitivity paths must be preserved exactly."]
    timeout_prefixes = {("template", 0, "container", 0, "env", name) for name in TIMEOUT_ENV}
    if prohibit_timeout and any(any(path[:len(prefix)] == prefix or prefix[:len(path)] == path for prefix in timeout_prefixes) for path in after_paths):
        return ["FAIL [metadata] new timeout entries must not be sensitive."]
    return []


def _plan_changes(plan: object, *, strict: bool) -> tuple[list[str], list[dict]]:
    if not isinstance(plan, dict) or not isinstance(plan.get("resource_changes"), list):
        return ["FAIL [plan] resource_changes are invalid."], []
    errors: list[str] = []
    changes: list[dict] = []
    addresses: set[str] = set()
    for index, resource in enumerate(plan["resource_changes"]):
        if not isinstance(resource, dict) or not isinstance(resource.get("address"), str):
            errors.append(f"FAIL [plan] resource_changes[{index}] is malformed.")
            continue
        address = resource["address"]
        if strict and address in addresses:
            errors.append("FAIL [scope] duplicate resource address in plan.")
        addresses.add(address)
        change = resource.get("change")
        if not isinstance(change, dict) or not isinstance(change.get("actions"), list):
            errors.append(f"FAIL [plan] resource_changes[{index}] has malformed change payload.")
            continue
        actions = change["actions"]
        if strict and any(resource.get(key) for key in ("previous_address", "deposed")):
            errors.append("FAIL [metadata] moved or deposed resources are not allowed.")
        if strict and change.get("importing"):
            errors.append("FAIL [metadata] importing resources are not allowed.")
        if strict and _metadata_has_risk(change.get("after_unknown")):
            errors.append("FAIL [metadata] unknown after-state fields are not allowed.")
        if strict and actions == ["no-op"]:
            if not isinstance(change.get("before"), dict) or change.get("before") != change.get("after"):
                errors.append("FAIL [plan] no-op resource has inconsistent before/after state.")
        elif strict and not (isinstance(change.get("before"), dict) and isinstance(change.get("after"), dict)):
            errors.append(f"FAIL [plan] resource_changes[{index}] is missing before/after state.")
        if strict and isinstance(change.get("before"), dict) and isinstance(change.get("after"), dict):
            errors.extend(_sensitivity_errors(change, change["before"], change["after"]))
        changes.append(resource)
    return errors, changes


def _expected(tags_json: str, digests_json: str) -> tuple[str, str] | None:
    try:
        tags, digests = json.loads(tags_json), json.loads(digests_json)
    except (TypeError, json.JSONDecodeError):
        return None
    tag = tags.get("api-gateway") if isinstance(tags, dict) else None
    digest = digests.get("api-gateway") if isinstance(digests, dict) else None
    return (tag, digest) if isinstance(tag, str) and _TAG.fullmatch(tag) and isinstance(digest, str) and _DIGEST.fullmatch(digest) else None


def _baseline_errors(before: dict, after: dict, gateway_id: str, tag: str, digest: str) -> list[str]:
    errors: list[str] = []
    for side in (before, after):
        container = _container(side)
        if side.get("id") != gateway_id or container is None:
            return ["FAIL [baseline] api-gateway identity or container shape does not match the trusted baseline."]
        template = side["template"][0]
        if (container.get("name") != "api-gateway" or container.get("image") != f"wealthprodacr.azurecr.io/api-gateway@{digest}" or template.get("min_replicas") != 0 or template.get("max_replicas") != 3 or container.get("cpu") != 0.5 or container.get("memory") != "1Gi"):
            return ["FAIL [baseline] api-gateway immutable deployment baseline does not match."]
        versions = [entry for entry in _env_map(side).values() if entry["name"] == "SERVICE_VERSION"]
        if versions != [{"name": "SERVICE_VERSION", "value": tag}]:
            return ["FAIL [baseline] SERVICE_VERSION does not match the trusted image tag."]
    return errors


def _evaluate_rollout(plan: dict, gateway_id: str, tags_json: str, digests_json: str) -> list[str]:
    expected = _expected(tags_json, digests_json)
    if not isinstance(gateway_id, str) or not gateway_id or expected is None:
        return ["FAIL [input] trusted gateway id, image tag, and image digest are required."]
    errors, changes = _plan_changes(plan, strict=True)
    if errors:
        return errors
    changed = [item for item in changes if item["change"]["actions"] != ["no-op"]]
    if len(changed) != 1 or changed[0].get("address") != GATEWAY_ADDR:
        return ["FAIL [scope] expected exactly one update to the api-gateway Container App."]
    change = changed[0]["change"]
    if change.get("actions") != ["update"]:
        return ["FAIL [action] api-gateway timeout rollout must be an in-place update."]
    before, after = change["before"], change["after"]
    sensitivity = _sensitivity_errors(change, before, after, prohibit_timeout=True)
    if sensitivity:
        return sensitivity
    before_env, after_env = _env_map(before), _env_map(after)
    if before_env is None or after_env is None:
        return ["FAIL [environment] api-gateway environment entries are invalid or duplicated."]
    baseline = _baseline_errors(before, after, gateway_id, *expected)
    if baseline:
        return baseline
    if any(name in before_env for name in TIMEOUT_ENV):
        return ["FAIL [environment] timeout entries must be absent before this rollout."]
    expected_after = {**before_env, **{name: {"name": name, "value": value} for name, value in TIMEOUT_ENV.items()}}
    if after_env != expected_after:
        return ["FAIL [environment] api-gateway must add exactly the four approved timeout entries."]
    normalized_before, normalized_after = _normalized_side(before), _normalized_side(after)
    if normalized_after is not None:
        after_container = _container(normalized_after)
        assert after_container is not None
        after_container["env"] = [before_env[name] for name in sorted(before_env)]
    if normalized_before is None or normalized_after is None or normalized_before != normalized_after:
        return ["FAIL [field] api-gateway changes a field other than the approved timeout entries."]
    return []


def _evaluate_non_rollout(plan: dict) -> list[str]:
    errors, changes = _plan_changes(plan, strict=False)
    if errors:
        return errors
    for resource in changes:
        if resource.get("address") != GATEWAY_ADDR or resource["change"]["actions"] == ["no-op"]:
            continue
        change = resource["change"]
        if _timeout_transition(change.get("before"), change.get("after")):
            return ["FAIL [timeout-guard] api-gateway timeout entries require api-gateway-timeout-rollout."]
    return []


def evaluate_plan(plan: dict, profile: str, gateway_id: str = "", tags_json: str = "", digests_json: str = "") -> list[str]:
    if profile not in KNOWN_PROFILES:
        return ["FAIL [profile] unknown change profile; fail closed."]
    return _evaluate_rollout(plan, gateway_id, tags_json, digests_json) if profile == PROFILE else _evaluate_non_rollout(plan)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan_json")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-gateway-id", default="")
    parser.add_argument("--expected-image-tags-json", default="")
    parser.add_argument("--expected-image-digests-json", default="")
    args = parser.parse_args()
    try:
        plan = load_plan(args.plan_json)
    except (OSError, json.JSONDecodeError):
        print("ERROR: Failed to load Terraform plan JSON.", file=sys.stderr)
        return 1
    errors = evaluate_plan(plan, args.profile, args.expected_gateway_id, args.expected_image_tags_json, args.expected_image_digests_json)
    if errors:
        print(f"API GATEWAY TIMEOUT ROLLOUT ASSERTION FAILED (profile={args.profile}):")
        for error in errors:
            print(f"  {error}")
        return 1
    print(f"PASS api-gateway timeout rollout plan guard (profile={args.profile}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
