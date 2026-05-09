#!/usr/bin/env python3
"""
assert_plan.py — Terraform plan assertion script for Azure Container Apps (P1 IaC-side).

Validates correctness property P1 (Profile Mutual Exclusion) against the JSON output
of `terraform show -json tfplan` for the Azure Terraform root.

Property P1 (IaC side): No azurerm_container_app resource in the plan may have both
"bedrock" AND "azure-ai" in its SPRING_PROFILES_ACTIVE environment variable.

Validates: Requirements 1.3, 1.4, 15.1

Usage:
    python3 scripts/assert_plan.py tfplan.json

    Typically invoked from the infrastructure/terraform/azure/ working directory:
        terraform show -json tfplan > tfplan.json
        python3 scripts/assert_plan.py tfplan.json

Exit codes:
    0 — all Container Apps pass the mutual-exclusion check (or have no SPRING_PROFILES_ACTIVE)
    1 — one or more Container Apps have both "bedrock" and "azure-ai" in SPRING_PROFILES_ACTIVE,
        or the plan file could not be loaded

Plan JSON structure navigated (azurerm_container_app):
    plan.resource_changes[]
        .type == "azurerm_container_app"
        .change.actions[] — "create", "update", or "no-op"
        .change.after.template[0].container[0].env[]
            .name  == "SPRING_PROFILES_ACTIVE"
            .value == "prod,azure,azure-ai"   (or null for sensitive/secret-backed vars)
"""

import json
import sys

# The two mutually exclusive AI provider profiles.
BEDROCK = "bedrock"
AZURE_AI = "azure-ai"

# Actions that represent a resource being created, updated, or already stable.
# "no-op" means the resource exists and is unchanged — still worth checking.
RELEVANT_ACTIONS = {"create", "update", "no-op"}


def load_plan(path: str) -> dict:
    """Load and parse the tfplan.json file produced by `terraform show -json`."""
    with open(path) as f:
        return json.load(f)


def get_container_app_changes(plan: dict) -> list:
    """Return all resource_changes entries for azurerm_container_app resources
    whose actions include at least one of create / update / no-op."""
    result = []
    for rc in plan.get("resource_changes", []):
        if rc.get("type") != "azurerm_container_app":
            continue
        actions = set(rc.get("change", {}).get("actions", []))
        if actions & RELEVANT_ACTIONS:
            result.append(rc)
    return result


def get_spring_profiles_active(rc: dict) -> str | None:
    """Extract the value of SPRING_PROFILES_ACTIVE from a Container App resource change.

    Navigates: change.after.template[0].container[0].env[name=SPRING_PROFILES_ACTIVE].value

    Returns:
        The string value if found and non-null.
        None if the env var is absent, the value is null (sensitive/secret-backed),
        or the template/container/env structure is missing.
    """
    after = rc.get("change", {}).get("after")
    if not after:
        # Resource is being deleted (after is null) — skip.
        return None

    # template is a list in the Terraform provider schema (always one element).
    template_list = after.get("template")
    if not template_list or not isinstance(template_list, list):
        return None
    template = template_list[0]
    if not isinstance(template, dict):
        return None

    # container is also a list (one element per container in the revision template).
    container_list = template.get("container")
    if not container_list or not isinstance(container_list, list):
        return None
    container = container_list[0]
    if not isinstance(container, dict):
        return None

    # env is a list of {name, value} objects. Sensitive vars may have value=null
    # and a secret_name field instead — skip those gracefully.
    env_list = container.get("env")
    if not env_list or not isinstance(env_list, list):
        return None

    for env_entry in env_list:
        if not isinstance(env_entry, dict):
            continue
        if env_entry.get("name") == "SPRING_PROFILES_ACTIVE":
            value = env_entry.get("value")
            # value is null when the env var is backed by a Container App secret
            # (secret_name is set instead). Skip — we cannot inspect the secret value.
            if value is None:
                return None
            return str(value)

    # SPRING_PROFILES_ACTIVE not present on this Container App — not all apps set it.
    return None


def check_profile_mutual_exclusion(rc: dict) -> str | None:
    """Check a single Container App resource change for the P1 violation.

    Returns an error message string if both "bedrock" and "azure-ai" are present
    in SPRING_PROFILES_ACTIVE, or None if the check passes (or is skipped).
    """
    address = rc.get("address", "<unknown>")
    after = rc.get("change", {}).get("after") or {}
    app_name = after.get("name", address)

    profiles_value = get_spring_profiles_active(rc)
    if profiles_value is None:
        # Missing, null, or secret-backed — skip gracefully (not a violation).
        return None

    # Split on comma and strip whitespace to get the individual profile tokens.
    active_profiles = {p.strip() for p in profiles_value.split(",") if p.strip()}

    if BEDROCK in active_profiles and AZURE_AI in active_profiles:
        return (
            f"FAIL [P1 — Profile Mutual Exclusion] Container App '{app_name}' "
            f"(address: {address}) has SPRING_PROFILES_ACTIVE='{profiles_value}' "
            f"which contains BOTH '{BEDROCK}' AND '{AZURE_AI}'. "
            f"These profiles are mutually exclusive — exactly one AI provider must be active."
        )

    return None


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_plan.py <tfplan.json>", file=sys.stderr)
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

    container_apps = get_container_app_changes(plan)

    if not container_apps:
        print("PASS No azurerm_container_app resources found in plan -- P1 check skipped.")
        return 0

    errors = []
    checked = []

    for rc in container_apps:
        after = rc.get("change", {}).get("after") or {}
        app_name = after.get("name", rc.get("address", "<unknown>"))
        error = check_profile_mutual_exclusion(rc)
        if error:
            errors.append(error)
        else:
            checked.append(app_name)

    if errors:
        print(f"\n{'=' * 65}")
        print(f"P1 PLAN ASSERTION FAILED — {len(errors)} violation(s):")
        print(f"{'=' * 65}")
        for err in errors:
            print(f"  {err}")
        print(f"{'=' * 65}")
        print()
        print("Fix: ensure SPRING_PROFILES_ACTIVE contains at most one of")
        print(f"  '{BEDROCK}' or '{AZURE_AI}' for each Container App.")
        print("  Valid examples: 'prod,azure,azure-ai'  or  'prod,aws,bedrock'")
        print("  Invalid example: 'prod,azure,bedrock,azure-ai'")
        print()
        return 1

    # All checks passed — print a summary.
    print(f"PASS P1 (Profile Mutual Exclusion) -- all checks passed")
    print(f"  Container Apps checked: {len(checked)}")
    for name in checked:
        print(f"    - {name}")
    print(f"  No Container App has both '{BEDROCK}' and '{AZURE_AI}' in SPRING_PROFILES_ACTIVE.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
