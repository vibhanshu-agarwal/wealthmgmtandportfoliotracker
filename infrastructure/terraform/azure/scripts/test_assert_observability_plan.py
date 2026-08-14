#!/usr/bin/env python3
"""
test_assert_observability_plan.py — Fixture tests for assert_observability_plan.py.

Validates observability plan checks against in-memory terraform show -json fragments
(no live terraform plan required):

  - both Log Analytics workspaces capped at daily_quota_gb = 0.023
  - resource-group budget amount 1100 Monthly with Actual 70 and Forecasted 100
  - no azurerm_monitor_scheduled_query_rules_alert* resources
  - no Auxiliary/Basic table-plan overrides; App Insights present; App* tables stay
    on default Analytics (absence of table-plan resources is a PASS)

Run from this directory or the repo:
    python test_assert_observability_plan.py
    python -m unittest infrastructure.terraform.azure.scripts.test_assert_observability_plan

Prefer no extra pytest dependency (stdlib unittest).
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_observability_plan as aop  # noqa: E402


def _rc(resource_type: str, name: str, after: dict | None, actions=None, address=None) -> dict:
    return {
        "address": address or f"{resource_type}.{name}",
        "type": resource_type,
        "name": name,
        "change": {
            "actions": list(actions or ["create"]),
            "after": after,
        },
    }


def _workspace(name: str, quota) -> dict:
    return _rc(
        "azurerm_log_analytics_workspace",
        name,
        {"name": f"wealth-prod-{name}-la", "daily_quota_gb": quota},
    )


def _budget(*, amount=1100, time_grain="Monthly", notifications=None, resource_group_id=True) -> dict:
    after = {
        "name": "wealth-prod-rg-budget",
        "amount": amount,
        "time_grain": time_grain,
        "notification": notifications
        if notifications is not None
        else [
            {
                "enabled": True,
                "threshold": 70,
                "threshold_type": "Actual",
                "operator": "GreaterThanOrEqualTo",
            },
            {
                "enabled": True,
                "threshold": 100,
                "threshold_type": "Forecasted",
                "operator": "GreaterThanOrEqualTo",
            },
        ],
    }
    if resource_group_id:
        after["resource_group_id"] = "/subscriptions/0000/resourceGroups/wealth-azure-prod-rg"
    return _rc("azurerm_consumption_budget_resource_group", "main", after)


def _app_insights() -> dict:
    return _rc(
        "azurerm_application_insights",
        "telemetry",
        {"name": "wealth-prod-ai", "application_type": "java", "retention_in_days": 90},
    )


def _good_resources(**overrides) -> list:
    resources = {
        "main_ws": _workspace("main", 0.023),
        "telemetry_ws": _workspace("telemetry", 0.023),
        "budget": _budget(),
        "app_insights": _app_insights(),
    }
    resources.update(overrides)
    return [r for r in resources.values() if r is not None]


def _plan(*resources) -> dict:
    return {"resource_changes": list(resources)}


def _errors(plan: dict) -> list[str]:
    return aop.evaluate_plan(plan)


class TestKnownGoodPlanPasses(unittest.TestCase):
    def test_known_good_plan_passes(self):
        errors = _errors(_plan(*_good_resources()))
        self.assertEqual(errors, [], f"expected PASS, got FAIL: {errors}")

    def test_quota_scientific_notation_passes(self):
        errors = _errors(
            _plan(
                *_good_resources(
                    main_ws=_workspace("main", 2.3e-2),
                    telemetry_ws=_workspace("telemetry", 2.3e-2),
                )
            )
        )
        self.assertEqual(errors, [], f"expected PASS for 2.3e-2 quota, got FAIL: {errors}")

    def test_threshold_as_float_passes(self):
        errors = _errors(
            _plan(
                *_good_resources(
                    budget=_budget(
                        notifications=[
                            {"threshold": 70.0, "threshold_type": "Actual"},
                            {"threshold": 100.0, "threshold_type": "Forecasted"},
                        ]
                    )
                )
            )
        )
        self.assertEqual(errors, [], f"expected PASS for 70.0/100.0 thresholds, got FAIL: {errors}")

    def test_absence_of_table_plan_resources_is_pass(self):
        plan = _plan(*_good_resources())
        types = {rc["type"] for rc in plan["resource_changes"]}
        self.assertNotIn("azurerm_log_analytics_workspace_table", types)
        self.assertEqual(_errors(plan), [])


class TestWorkspaceCapsFail(unittest.TestCase):
    def test_missing_cap_fails(self):
        missing = _rc(
            "azurerm_log_analytics_workspace",
            "main",
            {"name": "wealth-prod-main-la"},
        )
        errors = _errors(_plan(*_good_resources(main_ws=missing)))
        self.assertTrue(errors, "expected FAIL when daily_quota_gb is missing")
        self.assertTrue(any("quota" in e.lower() or "0.023" in e for e in errors), errors)

    def test_unlimited_quota_minus_one_fails(self):
        errors = _errors(_plan(*_good_resources(main_ws=_workspace("main", -1))))
        self.assertTrue(errors, "expected FAIL when daily_quota_gb is -1")

    def test_wrong_quota_fails(self):
        errors = _errors(_plan(*_good_resources(telemetry_ws=_workspace("telemetry", 0.5))))
        self.assertTrue(errors, "expected FAIL when daily_quota_gb is not 0.023")

    def test_only_one_workspace_fails(self):
        errors = _errors(
            _plan(
                _workspace("main", 0.023),
                _budget(),
                _app_insights(),
            )
        )
        self.assertTrue(errors, "expected FAIL when fewer than two workspaces exist")


class TestBudgetFails(unittest.TestCase):
    def test_subscription_budget_fails(self):
        sub = _rc(
            "azurerm_consumption_budget_subscription",
            "main",
            {
                "name": "wealth-prod-sub-budget",
                "amount": 1100,
                "time_grain": "Monthly",
                "subscription_id": "0000",
                "notification": [
                    {"threshold": 70, "threshold_type": "Actual"},
                    {"threshold": 100, "threshold_type": "Forecasted"},
                ],
            },
        )
        errors = _errors(_plan(*_good_resources(budget=sub)))
        self.assertTrue(errors, "expected FAIL for subscription-scoped budget")
        joined = " ".join(errors).lower()
        self.assertTrue("subscription" in joined or "resource-group" in joined or "resource_group" in joined, errors)

    def test_missing_budget_fails(self):
        errors = _errors(_plan(*_good_resources(budget=None)))
        self.assertTrue(errors, "expected FAIL when RG budget is missing")

    def test_wrong_amount_fails(self):
        errors = _errors(_plan(*_good_resources(budget=_budget(amount=500))))
        self.assertTrue(errors, "expected FAIL when budget amount is not 1100")

    def test_missing_actual_notification_fails(self):
        errors = _errors(
            _plan(
                *_good_resources(
                    budget=_budget(
                        notifications=[{"threshold": 100, "threshold_type": "Forecasted"}]
                    )
                )
            )
        )
        self.assertTrue(errors, "expected FAIL when Actual/70 notification is missing")

    def test_missing_forecasted_notification_fails(self):
        errors = _errors(
            _plan(
                *_good_resources(
                    budget=_budget(
                        notifications=[{"threshold": 70, "threshold_type": "Actual"}]
                    )
                )
            )
        )
        self.assertTrue(errors, "expected FAIL when Forecasted/100 notification is missing")


class TestScheduledQueryAlertFails(unittest.TestCase):
    def test_scheduled_query_alert_fails(self):
        alert = _rc(
            "azurerm_monitor_scheduled_query_rules_alert",
            "cost",
            {"name": "forbidden-alert"},
        )
        errors = _errors(_plan(*_good_resources(), alert))
        self.assertTrue(errors, "expected FAIL when a scheduled query alert exists")
        self.assertTrue(any("scheduled_query" in e.lower() or "alert" in e.lower() for e in errors), errors)

    def test_scheduled_query_alert_v2_fails(self):
        alert = _rc(
            "azurerm_monitor_scheduled_query_rules_alert_v2",
            "cost",
            {"name": "forbidden-alert-v2"},
        )
        errors = _errors(_plan(*_good_resources(), alert))
        self.assertTrue(errors, "expected FAIL when a scheduled query alert v2 exists")


class TestTablePlanFails(unittest.TestCase):
    def test_auxiliary_table_fails(self):
        table = _rc(
            "azurerm_log_analytics_workspace_table",
            "custom",
            {"name": "CustomLogs_CL", "plan": "Auxiliary"},
        )
        errors = _errors(_plan(*_good_resources(), table))
        self.assertTrue(errors, "expected FAIL for Auxiliary table plan")
        self.assertTrue(any("auxiliary" in e.lower() for e in errors), errors)

    def test_basic_table_fails(self):
        table = _rc(
            "azurerm_log_analytics_workspace_table",
            "custom",
            {"name": "CustomLogs_CL", "sku": "Basic"},
        )
        errors = _errors(_plan(*_good_resources(), table))
        self.assertTrue(errors, "expected FAIL for Basic table plan")

    def test_app_table_override_fails(self):
        table = _rc(
            "azurerm_log_analytics_workspace_table",
            "app_requests",
            {"name": "AppRequests", "plan": "Analytics"},
        )
        errors = _errors(_plan(*_good_resources(), table))
        self.assertTrue(errors, "expected FAIL for App* table-plan override")

    def test_missing_app_insights_fails(self):
        errors = _errors(_plan(*_good_resources(app_insights=None)))
        self.assertTrue(errors, "expected FAIL when App Insights is missing")


if __name__ == "__main__":
    unittest.main()
