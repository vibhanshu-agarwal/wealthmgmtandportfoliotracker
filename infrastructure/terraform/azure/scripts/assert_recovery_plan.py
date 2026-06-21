#!/usr/bin/env python3
"""
assert_recovery_plan.py — Terraform plan assertion for the market-data Job recovery path.

Guards the `recreate_market_data_job=true` recovery flow (terraform-azure.yml). A Job
recovery must touch ONLY the refresh Job and its identity/role — it must NOT modify,
replace, or delete any of the four long-running `azurerm_container_app` resources.

Why this matters: the Container App `target_port` is driven by the global `use_seed_image`
flag (`var.use_seed_image ? 80 : 8080`) and is NOT in the module's `ignore_changes`
(only the image is). If a recovery run is ever executed with the global seed flag on — or
some other drift sneaks in — the plan would repoint live ingress from 8080 to 80 while the
real Spring Boot containers keep serving on 8080, causing an avoidable outage. This script
fails the run before `terraform apply` if any Container App would change.

Usage:
    python3 scripts/assert_recovery_plan.py tfplan.json

Exit codes:
    0 — no azurerm_container_app is created/updated/replaced/deleted (recovery is Job-scoped)
    1 — one or more Container Apps would change, or the plan file could not be loaded
"""

import json
import sys

# Any of these actions on a Container App means the plan is NOT Job-scoped.
DISALLOWED_ACTIONS = {"update", "replace", "delete", "create", "create-before-destroy"}


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def ingress_target_port(side: dict | None):
    """Best-effort extraction of ingress[0].target_port from a change before/after object."""
    if not side:
        return None
    ingress = side.get("ingress")
    if isinstance(ingress, list) and ingress and isinstance(ingress[0], dict):
        return ingress[0].get("target_port")
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_recovery_plan.py <tfplan.json>", file=sys.stderr)
        return 1

    try:
        plan = load_plan(sys.argv[1])
    except FileNotFoundError:
        print(f"ERROR: Plan file not found: '{sys.argv[1]}'", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse plan JSON: {e}", file=sys.stderr)
        return 1

    offenders = []
    for rc in plan.get("resource_changes", []):
        if rc.get("type") != "azurerm_container_app":
            continue
        change = rc.get("change", {})
        actions = set(change.get("actions", []))
        # "no-op" is the only acceptable action for a Container App during Job recovery.
        if actions - {"no-op", "read"}:
            address = rc.get("address", "<unknown>")
            before_port = ingress_target_port(change.get("before"))
            after_port = ingress_target_port(change.get("after"))
            detail = f"actions={sorted(actions)}"
            if before_port != after_port:
                detail += f", ingress target_port {before_port} -> {after_port}"
            offenders.append(f"  [{address}] {detail}")

    if offenders:
        print("=" * 70)
        print(f"RECOVERY PLAN ASSERTION FAILED — {len(offenders)} Container App change(s):")
        print("=" * 70)
        for o in offenders:
            print(o)
        print("=" * 70)
        print()
        print("A Job recovery (recreate_market_data_job=true) must change ONLY the refresh")
        print("Job, its user-assigned identity, and its AcrPull role assignment.")
        print("Container App changes here usually mean the global use_seed_image flag is set")
        print("(repointing ingress target_port 8080 -> 80 on live apps) or unrelated drift.")
        print("Re-run recovery with recreate_market_data_job=true and use_seed_image=false,")
        print("and apply any intentional Container App changes in a separate normal apply.")
        print()
        return 1

    print("PASS recovery plan is Job-scoped — no azurerm_container_app changes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
