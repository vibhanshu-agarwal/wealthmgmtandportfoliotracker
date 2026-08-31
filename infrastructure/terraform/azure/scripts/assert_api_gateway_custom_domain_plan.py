#!/usr/bin/env python3
"""Universal exact-scope guard for api-gateway custom-domain Terraform plans."""

from __future__ import annotations

import argparse
import json
import sys

RESTORE_PROFILE = "api-gateway-custom-domain-restore"
REMOVE_PROFILE = "api-gateway-custom-domain-remove"
SCOPED_PROFILES = (RESTORE_PROFILE, REMOVE_PROFILE)
SPEC_A_9_14_PROFILES = (
    "spec-a-9.14-reopen-ingress",
    "spec-a-9.14-close-ingress",
)
CUSTOM_DOMAIN_ADDR = "azurerm_container_app_custom_domain.api_gateway[0]"
GATEWAY_ADDR = "module.api_gateway.azurerm_container_app.this"
EXPECTED_HOSTNAME = "api.vibhanshu-ai-portfolio.dev"
CERTIFICATE_RESOURCE_TYPES = (
    "azurerm_container_app_environment_managed_certificate",
    "azurerm_container_app_environment_certificate",
)
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
    "spec-a-9.14-reopen-ingress",
    "spec-a-9.14-close-ingress",
    RESTORE_PROFILE,
    REMOVE_PROFILE,
)


def load_plan(path: str) -> dict:
    with open(path, encoding="utf-8") as plan_file:
        return json.load(plan_file)


def _is_noop(resource_change: dict) -> bool:
    change = resource_change.get("change")
    if not isinstance(change, dict):
        return False
    return list(change.get("actions") or []) == ["no-op"]


def _certificate_resource(address: str) -> bool:
    return any(token in address for token in CERTIFICATE_RESOURCE_TYPES)


def _inactive(value) -> bool:
    return value is None or value == "" or value == "(known after apply)"


def _same_azure_resource_id(actual: object, expected: str) -> bool:
    return isinstance(actual, str) and actual.casefold() == expected.casefold()


def _collect_changes(plan: dict) -> tuple[list[str], list[dict]]:
    errors: list[str] = []
    changes = plan.get("resource_changes")
    if not isinstance(changes, list):
        return ["FAIL [plan] resource_changes are invalid."], []
    return errors, changes


def _reject_certificate_resources(changes: list[dict]) -> list[str]:
    errors: list[str] = []
    for change in changes:
        if not isinstance(change, dict) or _is_noop(change):
            continue
        address = change.get("address")
        if isinstance(address, str) and _certificate_resource(address):
            actions = list((change.get("change") or {}).get("actions") or [])
            errors.append(
                f"FAIL [certificate] {address} has actions={actions}; certificate resources "
                "must remain outside Terraform for this task."
            )
    return errors


def _non_noop(changes: list[dict]) -> list[dict]:
    return [change for change in changes if isinstance(change, dict) and not _is_noop(change)]


def _evaluate_restore(plan: dict, expected_gateway_id: str) -> list[str]:
    errors, changes = _collect_changes(plan)
    if errors:
        return errors
    errors.extend(_reject_certificate_resources(changes))
    non_noop = _non_noop(changes)
    if len(non_noop) != 1:
        errors.append(
            f"FAIL [scope] expected exactly 1 non-no-op resource change, got {len(non_noop)}."
        )
        return errors

    resource = non_noop[0]
    address = resource.get("address")
    if address != CUSTOM_DOMAIN_ADDR:
        errors.append(
            f"FAIL [scope] expected change on {CUSTOM_DOMAIN_ADDR}, got {address!r}."
        )
        return errors

    change = resource.get("change") or {}
    actions = list(change.get("actions") or [])
    if actions != ["create"]:
        errors.append(
            f"FAIL [action] {CUSTOM_DOMAIN_ADDR} has actions={actions}; expected ['create']."
        )
        return errors

    after = change.get("after")
    if not isinstance(after, dict):
        errors.append(f"FAIL [field] {CUSTOM_DOMAIN_ADDR} after-side is missing.")
        return errors
    if after.get("name") != EXPECTED_HOSTNAME:
        errors.append(f"FAIL [hostname] expected {EXPECTED_HOSTNAME!r}.")
    if not _same_azure_resource_id(after.get("container_app_id"), expected_gateway_id):
        errors.append("FAIL [gateway] container_app_id does not match preflight gateway id.")
    if not _inactive(after.get("certificate_binding_type")):
        errors.append("FAIL [certificate] certificate_binding_type must be absent on create.")
    cert_id = after.get("container_app_environment_certificate_id")
    if not _inactive(cert_id):
        errors.append(
            "FAIL [certificate] container_app_environment_certificate_id must be absent on create."
        )
    return errors


def _evaluate_remove(plan: dict, expected_gateway_id: str) -> list[str]:
    errors, changes = _collect_changes(plan)
    if errors:
        return errors
    errors.extend(_reject_certificate_resources(changes))
    non_noop = _non_noop(changes)
    if len(non_noop) != 1:
        errors.append(
            f"FAIL [scope] expected exactly 1 non-no-op resource change, got {len(non_noop)}."
        )
        return errors

    resource = non_noop[0]
    address = resource.get("address")
    if address != CUSTOM_DOMAIN_ADDR:
        errors.append(
            f"FAIL [scope] expected delete on {CUSTOM_DOMAIN_ADDR}, got {address!r}."
        )
        return errors

    change = resource.get("change") or {}
    actions = list(change.get("actions") or [])
    if actions != ["delete"]:
        errors.append(
            f"FAIL [action] {CUSTOM_DOMAIN_ADDR} has actions={actions}; expected ['delete']."
        )
        return errors

    before = change.get("before")
    if not isinstance(before, dict):
        errors.append(f"FAIL [field] {CUSTOM_DOMAIN_ADDR} before-side is missing.")
        return errors
    if before.get("name") != EXPECTED_HOSTNAME:
        errors.append(f"FAIL [hostname] expected {EXPECTED_HOSTNAME!r}.")
    if not _same_azure_resource_id(before.get("container_app_id"), expected_gateway_id):
        errors.append("FAIL [gateway] container_app_id does not match expected gateway id.")
    return errors


def _reject_custom_domain_resources(changes: list[dict], profile: str) -> list[str]:
    errors: list[str] = []
    for change in changes:
        if not isinstance(change, dict) or _is_noop(change):
            continue
        address = change.get("address")
        if address == CUSTOM_DOMAIN_ADDR:
            actions = list((change.get("change") or {}).get("actions") or [])
            errors.append(
                f"FAIL [domain-guard] {CUSTOM_DOMAIN_ADDR} has actions={actions} under "
                f"change_profile={profile}; use {RESTORE_PROFILE} or {REMOVE_PROFILE}."
            )
    return errors


def _reject_gateway_resources(changes: list[dict], profile: str) -> list[str]:
    errors: list[str] = []
    for change in changes:
        if not isinstance(change, dict) or _is_noop(change):
            continue
        address = change.get("address")
        if address == GATEWAY_ADDR:
            change_body = change.get("change") or {}
            actions = list(change_body.get("actions") or [])
            if actions and actions != ["no-op"]:
                errors.append(
                    f"FAIL [gateway-guard] {GATEWAY_ADDR} changes under "
                    f"change_profile={profile}."
                )
    return errors


def _evaluate_non_scoped(plan: dict, profile: str) -> list[str]:
    errors, changes = _collect_changes(plan)
    if errors:
        return errors
    errors.extend(_reject_certificate_resources(changes))
    errors.extend(_reject_custom_domain_resources(changes, profile))
    if profile in SPEC_A_9_14_PROFILES:
        if errors:
            return errors
        import assert_spec_a_9_14_plan as spec_a_9_14

        return spec_a_9_14.evaluate_plan(plan, profile)
    errors.extend(_reject_gateway_resources(changes, profile))
    return errors


def evaluate_plan(plan: dict, profile: str, expected_gateway_id: str | None = None) -> list[str]:
    if profile not in KNOWN_PROFILES:
        return ["FAIL [profile] unknown change profile; fail closed."]
    if profile in SCOPED_PROFILES:
        if not expected_gateway_id:
            return ["FAIL [input] expected gateway id is required for scoped custom-domain profiles."]
        if profile == RESTORE_PROFILE:
            return _evaluate_restore(plan, expected_gateway_id)
        return _evaluate_remove(plan, expected_gateway_id)
    return _evaluate_non_scoped(plan, profile)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan_json")
    parser.add_argument("--profile", required=True)
    parser.add_argument("--expected-gateway-id", default="")
    args = parser.parse_args()

    try:
        plan = load_plan(args.plan_json)
    except (OSError, json.JSONDecodeError):
        print("ERROR: Failed to load Terraform plan JSON.", file=sys.stderr)
        return 1

    gateway_id = args.expected_gateway_id.strip() or None
    errors = evaluate_plan(plan, args.profile, gateway_id)
    if errors:
        print(f"API GATEWAY CUSTOM DOMAIN PLAN ASSERTION FAILED (profile={args.profile}):")
        for error in errors:
            print(f"  {error}")
        return 1
    print(f"PASS api-gateway custom-domain plan guard (profile={args.profile}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
