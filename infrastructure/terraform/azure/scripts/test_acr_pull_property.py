#!/usr/bin/env python3
"""
test_acr_pull_property.py — Terraform plan assertion script for Azure Container Apps (P5 IaC-side).

Validates correctness property P5 (ACR Pull Authorization) against the JSON output
of `terraform show -json tfplan` for the Azure Terraform root.

Property P5: Every azurerm_container_app resource in the plan must:
  1. Have identity[0].type == "SystemAssigned"
  2. Have registry[0].identity == "system"
  3. Have a corresponding azurerm_role_assignment with role_definition_name == "AcrPull"
     in the same Terraform module path.

Validates: Requirements 6.1, 6.2, 6.3, 6.5, 15.5

Usage:
    python3 scripts/test_acr_pull_property.py tfplan.json

    Typically invoked from the infrastructure/terraform/azure/ working directory:
        terraform show -json tfplan > tfplan.json
        python3 scripts/test_acr_pull_property.py tfplan.json

Exit codes:
    0 — every Container App has SystemAssigned identity, registry identity=system,
        and a matching AcrPull role assignment in the same module
    1 — one or more Container Apps fail any of the three checks,
        or the plan file could not be loaded

Plan JSON structure navigated:

  azurerm_container_app:
    plan.resource_changes[]
        .type == "azurerm_container_app"
        .address == "module.api_gateway.azurerm_container_app.this"
        .change.after.identity[0].type  == "SystemAssigned"
        .change.after.registry[0].identity == "system"

  azurerm_role_assignment (AcrPull):
    plan.resource_changes[]
        .type == "azurerm_role_assignment"
        .address == "module.api_gateway.azurerm_role_assignment.acr_pull"
        .change.after.role_definition_name == "AcrPull"
        .change.after.principal_id  — "(known after apply)" in plan JSON
                                       (computed from Container App identity)

  Matching strategy:
    Since principal_id is "(known after apply)" in plan JSON, we cannot match by
    principal_id value. Instead we match by module path prefix:
      Container App address:   module.api_gateway.azurerm_container_app.this
      Role assignment address: module.api_gateway.azurerm_role_assignment.acr_pull
    Both share the prefix "module.api_gateway". We extract the module prefix from
    the Container App address and check that an AcrPull role assignment exists
    under the same prefix.
"""

import json
import sys

# Actions that represent a resource being created, updated, or already stable.
RELEVANT_ACTIONS = {"create", "update", "no-op"}

ROLE_ACR_PULL = "AcrPull"


def load_plan(path: str) -> dict:
    """Load and parse the tfplan.json file produced by `terraform show -json`."""
    with open(path) as f:
        return json.load(f)


def get_relevant_changes(plan: dict, resource_type: str) -> list:
    """Return all resource_changes entries for the given type with relevant actions."""
    result = []
    for rc in plan.get("resource_changes", []):
        if rc.get("type") != resource_type:
            continue
        actions = set(rc.get("change", {}).get("actions", []))
        if actions & RELEVANT_ACTIONS:
            result.append(rc)
    return result


def extract_module_prefix(address: str) -> str:
    """Extract the module path prefix from a resource address.

    Examples:
        "module.api_gateway.azurerm_container_app.this"
            → "module.api_gateway"
        "module.portfolio_service.azurerm_container_app.this"
            → "module.portfolio_service"
        "azurerm_container_app.standalone"
            → ""  (root module — no prefix)

    The prefix is everything up to (but not including) the last two dot-separated
    segments (resource_type.resource_name). For nested modules the prefix may
    contain multiple "module.X" segments joined by dots.
    """
    parts = address.split(".")
    # A resource address always ends with <type>.<name> (2 segments).
    # Everything before those two segments is the module path.
    if len(parts) <= 2:
        # Root-module resource — no module prefix.
        return ""
    # Rejoin all segments except the last two.
    return ".".join(parts[:-2])


def build_acr_pull_module_set(plan: dict) -> set:
    """Build a set of module prefixes that have an AcrPull role assignment in the plan.

    We collect every azurerm_role_assignment with role_definition_name == "AcrPull"
    (create / update / no-op) and record its module prefix. This set is then used
    to verify that each Container App's module has a matching AcrPull assignment.
    """
    acr_pull_modules = set()
    for rc in plan.get("resource_changes", []):
        if rc.get("type") != "azurerm_role_assignment":
            continue
        actions = set(rc.get("change", {}).get("actions", []))
        if not (actions & RELEVANT_ACTIONS):
            continue

        after = rc.get("change", {}).get("after") or {}
        role_name = after.get("role_definition_name")
        if role_name == ROLE_ACR_PULL:
            prefix = extract_module_prefix(rc.get("address", ""))
            acr_pull_modules.add(prefix)

    return acr_pull_modules


def check_container_app(rc: dict, acr_pull_modules: set) -> list:
    """Check a single Container App resource change for all P5 sub-properties.

    Returns a (possibly empty) list of error message strings describing each failure.
    """
    address = rc.get("address", "<unknown>")
    after = rc.get("change", {}).get("after") or {}
    app_name = after.get("name", address)
    errors = []

    # ── Sub-check 1: SystemAssigned identity ──────────────────────────────────
    # change.after.identity is a list; we inspect the first element.
    identity_list = after.get("identity")
    if not identity_list or not isinstance(identity_list, list):
        errors.append(
            f"  [{app_name}] FAIL: 'identity' block is missing or empty. "
            f"Expected identity[0].type == 'SystemAssigned'."
        )
    else:
        identity = identity_list[0] if isinstance(identity_list[0], dict) else {}
        identity_type = identity.get("type")
        if identity_type != "SystemAssigned":
            errors.append(
                f"  [{app_name}] FAIL: identity[0].type == '{identity_type}' "
                f"(expected 'SystemAssigned'). "
                f"The Container App must use a system-assigned managed identity "
                f"so the AcrPull role assignment can reference its principal_id."
            )

    # ── Sub-check 2: registry identity == "system" ────────────────────────────
    # change.after.registry is a list; we inspect the first element.
    registry_list = after.get("registry")
    if not registry_list or not isinstance(registry_list, list):
        errors.append(
            f"  [{app_name}] FAIL: 'registry' block is missing or empty. "
            f"Expected registry[0].identity == 'system'."
        )
    else:
        registry = registry_list[0] if isinstance(registry_list[0], dict) else {}
        registry_identity = registry.get("identity")
        if registry_identity != "system":
            errors.append(
                f"  [{app_name}] FAIL: registry[0].identity == '{registry_identity}' "
                f"(expected 'system'). "
                f"The registry block must use the system-assigned identity "
                f"(not admin credentials) to pull images from ACR."
            )

    # ── Sub-check 3: AcrPull role assignment in the same module ───────────────
    # We match by module path prefix because principal_id is "(known after apply)"
    # in plan JSON — it is computed from the Container App's identity at apply time.
    module_prefix = extract_module_prefix(address)
    if module_prefix not in acr_pull_modules:
        errors.append(
            f"  [{app_name}] FAIL: No 'AcrPull' azurerm_role_assignment found "
            f"under module prefix '{module_prefix or '<root>'}'. "
            f"Expected an azurerm_role_assignment with "
            f"role_definition_name == 'AcrPull' in the same module as this "
            f"Container App. Without it, revision activation will fail with "
            f"UNAUTHORIZED: authentication required."
        )

    return errors


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 test_acr_pull_property.py <tfplan.json>", file=sys.stderr)
        print("  tfplan.json is produced by: terraform show -json tfplan", file=sys.stderr)
        return 1

    plan_path = sys.argv[1]
    try:
        plan = load_plan(plan_path)
    except FileNotFoundError:
        print(f"ERROR: Plan file not found: '{plan_path}'", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse plan JSON from '{plan_path}': {e}", file=sys.stderr)
        return 1

    container_apps = get_relevant_changes(plan, "azurerm_container_app")

    if not container_apps:
        print("PASS No azurerm_container_app resources found in plan -- P5 check skipped.")
        return 0

    # Build the set of module prefixes that have an AcrPull role assignment.
    # This is computed once and reused for all Container App checks.
    acr_pull_modules = build_acr_pull_module_set(plan)

    all_errors = []
    passed = []

    for rc in container_apps:
        after = rc.get("change", {}).get("after") or {}
        app_name = after.get("name", rc.get("address", "<unknown>"))
        errors = check_container_app(rc, acr_pull_modules)
        if errors:
            all_errors.extend(errors)
        else:
            passed.append(app_name)

    if all_errors:
        print(f"\n{'=' * 65}")
        print(f"P5 PLAN ASSERTION FAILED — {len(all_errors)} failure(s):")
        print(f"{'=' * 65}")
        for err in all_errors:
            print(err)
        print(f"{'=' * 65}")
        print()
        print("Each Container App must satisfy all three conditions:")
        print("  1. identity[0].type == 'SystemAssigned'")
        print("  2. registry[0].identity == 'system'")
        print("  3. An azurerm_role_assignment with role_definition_name == 'AcrPull'")
        print("     must exist in the same Terraform module.")
        print()
        print("These are declared in modules/container-app/main.tf — verify the")
        print("module source path and that the module is included in the plan.")
        print()
        return 1

    # All checks passed — print a summary.
    print(f"PASS P5 (ACR Pull Authorization) -- all checks passed")
    print(f"  Container Apps checked: {len(passed)}")
    for name in passed:
        print(f"    - {name}")
    print(f"  All Container Apps have SystemAssigned identity, registry identity=system,")
    print(f"  and a matching AcrPull role assignment in their module.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
