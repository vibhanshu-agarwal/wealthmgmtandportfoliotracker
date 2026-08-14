#!/usr/bin/env python3
"""
assert_observability_plan.py — Terraform plan assertions for observability cost controls.

Validates against the JSON output of `terraform show -json tfplan`:

  1. Both Log Analytics workspaces (platform .main and telemetry .telemetry) have
     daily_quota_gb = 0.023 (Requirements 4.1, 4.2).
  2. A resource-group Cost Management budget exists at amount 1100 Monthly with
     Actual 70 and Forecasted 100 notifications (Requirements 4.15, 4.19).
  3. No azurerm_monitor_scheduled_query_rules_alert* resource exists
     (Free_Controls_Constraint — Requirement 5.4).
  4. App Insights exists; no Auxiliary/Basic table-plan override; App* tables stay
     on the default Analytics plan (Requirement 4.20).

Usage:
    python3 scripts/assert_observability_plan.py tfplan.json

    Typically invoked from the infrastructure/terraform/azure/ working directory:
        terraform show -json tfplan > tfplan.json
        python3 scripts/assert_observability_plan.py tfplan.json

Exit codes:
    0 — all observability checks passed
    1 — one or more checks failed, or the plan file could not be loaded
"""

from __future__ import annotations

import json
import sys

RELEVANT_ACTIONS = {"create", "update", "no-op"}

EXPECTED_DAILY_QUOTA_GB = 0.023
QUOTA_EPSILON = 1e-9

EXPECTED_BUDGET_AMOUNT = 1100
EXPECTED_TIME_GRAIN = "Monthly"
EXPECTED_ACTUAL_THRESHOLD = 70
EXPECTED_FORECASTED_THRESHOLD = 100

WORKSPACE_TYPE = "azurerm_log_analytics_workspace"
RG_BUDGET_TYPE = "azurerm_consumption_budget_resource_group"
SUB_BUDGET_TYPE = "azurerm_consumption_budget_subscription"
APP_INSIGHTS_TYPE = "azurerm_application_insights"
TABLE_TYPE = "azurerm_log_analytics_workspace_table"
SCHEDULED_QUERY_ALERT_PREFIX = "azurerm_monitor_scheduled_query_rules_alert"
FORBIDDEN_TABLE_PLANS = {"auxiliary", "basic"}


def load_plan(path: str) -> dict:
    """Load and parse the tfplan.json file produced by `terraform show -json`."""
    with open(path) as f:
        return json.load(f)


def _relevant(rc: dict) -> bool:
    actions = set(rc.get("change", {}).get("actions", []))
    return bool(actions & RELEVANT_ACTIONS)


def relevant_changes(plan: dict, resource_type: str | None = None) -> list:
    result = []
    for rc in plan.get("resource_changes", []):
        if resource_type is not None and rc.get("type") != resource_type:
            continue
        if _relevant(rc):
            result.append(rc)
    return result


def _after(rc: dict) -> dict:
    after = rc.get("change", {}).get("after")
    return after if isinstance(after, dict) else {}


def _address(rc: dict) -> str:
    return rc.get("address", "<unknown>")


def _float_eq(value, expected: float, epsilon: float = QUOTA_EPSILON) -> bool:
    try:
        return abs(float(value) - expected) <= epsilon
    except (TypeError, ValueError):
        return False


def _iter_notifications(after: dict):
    """Yield notification dicts from AzureRM 4.x `notification` or `notifications`."""
    for key in ("notification", "notifications"):
        raw = after.get(key)
        if raw is None:
            continue
        if isinstance(raw, list):
            for item in raw:
                if isinstance(item, dict):
                    yield item
        elif isinstance(raw, dict):
            if "threshold" in raw or "threshold_type" in raw:
                yield raw
            else:
                for item in raw.values():
                    if isinstance(item, dict):
                        yield item


def _table_plan_value(after: dict):
    for key in ("plan", "sku"):
        value = after.get(key)
        if value is not None and value != "":
            return value
    return None


def _is_table_plan_resource(rc: dict) -> bool:
    rtype = str(rc.get("type") or "")
    return rtype == TABLE_TYPE or "log_analytics_workspace_table" in rtype


def _is_app_star_table(name: str) -> bool:
    return bool(name) and str(name).startswith("App")


def check_workspace_caps(plan: dict) -> list[str]:
    errors = []
    workspaces = relevant_changes(plan, WORKSPACE_TYPE)
    if len(workspaces) < 2:
        errors.append(
            "FAIL [workspace cap] expected at least two azurerm_log_analytics_workspace "
            f"resources (platform .main and telemetry .telemetry); found {len(workspaces)}."
        )
        return errors

    for rc in workspaces:
        after = _after(rc)
        address = _address(rc)
        if "daily_quota_gb" not in after or after.get("daily_quota_gb") is None:
            errors.append(
                f"FAIL [workspace cap] {address} is missing daily_quota_gb "
                f"(expected {EXPECTED_DAILY_QUOTA_GB})."
            )
            continue
        quota = after.get("daily_quota_gb")
        if _float_eq(quota, -1.0):
            errors.append(
                f"FAIL [workspace cap] {address} has daily_quota_gb={quota} "
                f"(unlimited); expected {EXPECTED_DAILY_QUOTA_GB}."
            )
            continue
        if not _float_eq(quota, EXPECTED_DAILY_QUOTA_GB):
            errors.append(
                f"FAIL [workspace cap] {address} has daily_quota_gb={quota} "
                f"(expected {EXPECTED_DAILY_QUOTA_GB})."
            )
    return errors


def check_rg_budget(plan: dict) -> list[str]:
    errors = []
    sub_budgets = relevant_changes(plan, SUB_BUDGET_TYPE)
    if sub_budgets:
        addresses = ", ".join(_address(rc) for rc in sub_budgets)
        errors.append(
            "FAIL [budget] subscription-scoped azurerm_consumption_budget_subscription "
            f"is present ({addresses}); budget must be resource-group scoped "
            f"({RG_BUDGET_TYPE})."
        )

    rg_budgets = relevant_changes(plan, RG_BUDGET_TYPE)
    if not rg_budgets:
        errors.append(
            f"FAIL [budget] no {RG_BUDGET_TYPE} found; expected a resource-group "
            f"budget with amount {EXPECTED_BUDGET_AMOUNT} and time_grain {EXPECTED_TIME_GRAIN}."
        )
        return errors

    for rc in rg_budgets:
        after = _after(rc)
        address = _address(rc)
        rg_id = after.get("resource_group_id") or after.get("resource_group_name")
        if not rg_id:
            errors.append(
                f"FAIL [budget] {address} is missing resource_group_id "
                f"(resource-group scope is required)."
            )
        amount = after.get("amount")
        if not _float_eq(amount, float(EXPECTED_BUDGET_AMOUNT)):
            errors.append(
                f"FAIL [budget] {address} amount={amount} "
                f"(expected {EXPECTED_BUDGET_AMOUNT})."
            )
        time_grain = after.get("time_grain")
        if time_grain != EXPECTED_TIME_GRAIN:
            errors.append(
                f"FAIL [budget] {address} time_grain={time_grain!r} "
                f"(expected {EXPECTED_TIME_GRAIN!r})."
            )

        notifications = list(_iter_notifications(after))
        has_actual_70 = any(
            n.get("threshold_type") == "Actual"
            and _float_eq(n.get("threshold"), float(EXPECTED_ACTUAL_THRESHOLD))
            for n in notifications
        )
        has_forecasted_100 = any(
            n.get("threshold_type") == "Forecasted"
            and _float_eq(n.get("threshold"), float(EXPECTED_FORECASTED_THRESHOLD))
            for n in notifications
        )
        if not has_actual_70:
            errors.append(
                f"FAIL [budget] {address} is missing Actual notification at threshold "
                f"{EXPECTED_ACTUAL_THRESHOLD}."
            )
        if not has_forecasted_100:
            errors.append(
                f"FAIL [budget] {address} is missing Forecasted notification at threshold "
                f"{EXPECTED_FORECASTED_THRESHOLD}."
            )
    return errors


def check_no_scheduled_query_alerts(plan: dict) -> list[str]:
    errors = []
    for rc in relevant_changes(plan):
        rtype = str(rc.get("type") or "")
        if rtype.startswith(SCHEDULED_QUERY_ALERT_PREFIX):
            errors.append(
                "FAIL [Free_Controls_Constraint] scheduled query alert "
                f"{_address(rc)} (type {rtype}) must not exist in the plan."
            )
    return errors


def check_analytics_plan(plan: dict) -> list[str]:
    errors = []
    insights = relevant_changes(plan, APP_INSIGHTS_TYPE)
    if not insights:
        errors.append(
            f"FAIL [App Insights] no {APP_INSIGHTS_TYPE} resource found in the plan."
        )

    for rc in relevant_changes(plan):
        if not _is_table_plan_resource(rc):
            continue
        after = _after(rc)
        address = _address(rc)
        name = str(after.get("name") or "")
        plan_value = _table_plan_value(after)
        if plan_value is not None and str(plan_value).lower() in FORBIDDEN_TABLE_PLANS:
            errors.append(
                f"FAIL [table plan] {address} name={name!r} has plan/sku {plan_value!r}; "
                f"Auxiliary and Basic are forbidden (tables must remain Analytics)."
            )
        if _is_app_star_table(name):
            errors.append(
                f"FAIL [table plan] {address} introduces a table-plan override on App* "
                f"table {name!r}; App Insights tables must stay on the default Analytics plan."
            )
    return errors


def evaluate_plan(plan: dict) -> list[str]:
    """Return FAIL messages for a terraform show -json document. Empty list is PASS."""
    errors: list[str] = []
    errors.extend(check_workspace_caps(plan))
    errors.extend(check_rg_budget(plan))
    errors.extend(check_no_scheduled_query_alerts(plan))
    errors.extend(check_analytics_plan(plan))
    return errors


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_observability_plan.py <tfplan.json>", file=sys.stderr)
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

    errors = evaluate_plan(plan)
    if errors:
        print(f"\n{'=' * 65}")
        print(f"OBSERVABILITY PLAN ASSERTION FAILED — {len(errors)} violation(s):")
        print(f"{'=' * 65}")
        for err in errors:
            print(f"  {err}")
        print(f"{'=' * 65}")
        print()
        print("Required:")
        print(f"  - both Log Analytics workspaces daily_quota_gb = {EXPECTED_DAILY_QUOTA_GB}")
        print(f"  - {RG_BUDGET_TYPE} amount={EXPECTED_BUDGET_AMOUNT} time_grain={EXPECTED_TIME_GRAIN}")
        print("    with Actual 70 and Forecasted 100 notifications")
        print("  - no azurerm_monitor_scheduled_query_rules_alert* resources")
        print("  - App Insights present; no Auxiliary/Basic or App* table-plan overrides")
        print()
        return 1

    print("PASS observability plan checks -- all checks passed")
    print(f"  Log Analytics workspaces: daily_quota_gb = {EXPECTED_DAILY_QUOTA_GB}")
    print(
        f"  Resource-group budget: amount={EXPECTED_BUDGET_AMOUNT} "
        f"time_grain={EXPECTED_TIME_GRAIN} Actual {EXPECTED_ACTUAL_THRESHOLD} / "
        f"Forecasted {EXPECTED_FORECASTED_THRESHOLD}"
    )
    print("  No scheduled query alerts")
    print("  App Insights present; tables remain on default Analytics (no Auxiliary override)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
