#!/usr/bin/env python3
"""
assert_job_identity_migration.py — Guard against an IN-PLACE identity/registry
migration of the market-data-refresh Job.

Runs on EVERY apply (not just recovery). Switching the Job between identity types
(SystemAssigned <-> UserAssigned), changing its registry identity, or changing its set
of user-assigned identity IDs must be done by REPLACING the Job, never by an in-place
update: an in-place ACA Job identity migration can stall on revision provisioning — the
exact failure this whole change addresses. A forced replacement (`-replace`, via
`recreate_market_data_job=true`) destroys the stuck Job and creates a clean one after the
UAMI and its AcrPull grant exist.

This is the control that stops a *plain* `action=apply` (recreate flag false) from
quietly planning the SystemAssigned -> UserAssigned in-place migration when the live
Job in state still has the old shape.

Usage:
    python3 scripts/assert_job_identity_migration.py tfplan.json

Exit codes:
    0 — Job is no-op / created / replaced, OR an in-place update that does NOT change
        identity.type, registry.identity, or identity.identity_ids
    1 — Job would be migrated IN PLACE (identity.type, registry.identity, or
        identity_ids change without replacement), or the plan file could not be loaded
"""

import json
import sys

JOB_TYPE = "azurerm_container_app_job"
JOB_ADDRESS = "azurerm_container_app_job.market_data_refresh"


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def identity_type(side: dict | None):
    if not side:
        return None
    ids = side.get("identity")
    if isinstance(ids, list) and ids and isinstance(ids[0], dict):
        return ids[0].get("type")
    return None


def registry_identity(side: dict | None):
    if not side:
        return None
    regs = side.get("registry")
    if isinstance(regs, list) and regs and isinstance(regs[0], dict):
        return regs[0].get("identity")
    return None


def identity_ids(side: dict | None):
    """Return the set of user-assigned identity IDs (order-independent so a harmless
    reordering is not flagged as a migration). None when no identity block is present."""
    if not side:
        return None
    ids = side.get("identity")
    if isinstance(ids, list) and ids and isinstance(ids[0], dict):
        val = ids[0].get("identity_ids")
        if isinstance(val, list):
            return set(val)
    return None


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_job_identity_migration.py <tfplan.json>", file=sys.stderr)
        return 1
    try:
        plan = load_plan(sys.argv[1])
    except FileNotFoundError:
        print(f"ERROR: Plan file not found: '{sys.argv[1]}'", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse plan JSON: {e}", file=sys.stderr)
        return 1

    job = None
    for rc in plan.get("resource_changes", []):
        if rc.get("type") == JOB_TYPE and rc.get("address") == JOB_ADDRESS:
            job = rc
            break

    if job is None:
        print(f"PASS no change planned for {JOB_ADDRESS}.")
        return 0

    change = job.get("change", {})
    actions = set(change.get("actions", []))

    # A forced replacement (delete+create) is the SAFE migration path — allow it.
    # no-op and create are also fine. Only a bare in-place "update" is risky here.
    if actions != {"update"}:
        print(f"PASS {JOB_ADDRESS} actions={sorted(actions)} — not an in-place update.")
        return 0

    before = change.get("before") or {}
    after = change.get("after") or {}
    bt, at = identity_type(before), identity_type(after)
    br, ar = registry_identity(before), registry_identity(after)
    bids, aids = identity_ids(before), identity_ids(after)

    problems = []
    if bt != at:
        problems.append(f"identity.type {bt!r} -> {at!r}")
    if br != ar:
        problems.append(f"registry.identity {br!r} -> {ar!r}")
    if bids != aids:
        problems.append(f"identity.identity_ids {sorted(bids) if bids else bids} -> {sorted(aids) if aids else aids}")

    if problems:
        print("=" * 70)
        print("JOB IDENTITY MIGRATION ASSERTION FAILED")
        print("=" * 70)
        print(f"  {JOB_ADDRESS} would be migrated IN PLACE:")
        for p in problems:
            print(f"    - {p}")
        print()
        print("An in-place identity/registry migration of an ACA Job can stall on revision")
        print("provisioning. Force a clean replacement instead — rerun this workflow with:")
        print("    -f action=apply -f recreate_market_data_job=true")
        print("(leave use_seed_image=false). That destroys the old Job and creates a clean")
        print("one after the user-assigned identity and its AcrPull grant exist.")
        print("=" * 70)
        return 1

    print(f"PASS {JOB_ADDRESS} in-place update does not change identity/registry.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
