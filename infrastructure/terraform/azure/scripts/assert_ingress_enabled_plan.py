#!/usr/bin/env python3
"""
assert_ingress_enabled_plan.py — Spec A task 5.5.

Disabling gateway ingress (ingress_enabled = false) must update
module.api_gateway.azurerm_container_app.this, not replace it.

Usage:
    python3 scripts/assert_ingress_enabled_plan.py tfplan.json
"""

from __future__ import annotations

import json
import sys

GATEWAY_ADDRESS = "module.api_gateway.azurerm_container_app.this"
REPLACE_ACTIONS = {"create", "delete"}


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def _gateway_change(plan: dict) -> dict | None:
    for rc in plan.get("resource_changes", []):
        if rc.get("address") == GATEWAY_ADDRESS:
            return rc
    return None


def _has_ingress(side: dict | None) -> bool:
    if not side:
        return False
    ingress = side.get("ingress")
    if isinstance(ingress, list):
        return len(ingress) > 0
    return bool(ingress)


def evaluate_plan(plan: dict) -> list[str]:
    rc = _gateway_change(plan)
    if rc is None:
        return []
    change = rc.get("change") or {}
    before_has = _has_ingress(change.get("before"))
    after_has = _has_ingress(change.get("after"))
    if not (before_has and not after_has):
        return []
    actions = set(change.get("actions") or [])
    if actions & REPLACE_ACTIONS:
        return [
            f"FAIL [ingress_enabled] {GATEWAY_ADDRESS} would disable ingress with "
            f"actions {sorted(actions)}; expected in-place update, not replace."
        ]
    if "update" not in actions:
        return [
            f"FAIL [ingress_enabled] {GATEWAY_ADDRESS} disables ingress but "
            f"actions={sorted(actions)} do not include update."
        ]
    return []


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_ingress_enabled_plan.py <tfplan.json>", file=sys.stderr)
        return 1
    path = sys.argv[1]
    try:
        plan = load_plan(path)
    except FileNotFoundError:
        print(f"ERROR: Plan file not found: '{path}'", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse plan JSON from '{path}': {e}", file=sys.stderr)
        return 1

    errors = evaluate_plan(plan)
    if errors:
        print("INGRESS_ENABLED PLAN ASSERTION FAILED:")
        for err in errors:
            print(f"  {err}")
        return 1
    print(
        "PASS ingress_enabled — disabling api-gateway ingress does not replace "
        "module.api_gateway.azurerm_container_app.this"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
