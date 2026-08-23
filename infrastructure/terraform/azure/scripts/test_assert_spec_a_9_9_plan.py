#!/usr/bin/env python3
"""Fixture tests for assert_spec_a_9_9_plan.py (Spec A checkpoint 9.9)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_spec_a_9_9_plan as sut  # noqa: E402

IMAGE = "wealthprodacr.azurecr.io/portfolio-service:9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"


def _side(*, min_replicas, overrides_present, service_version=IMAGE.split(":")[-1]):
    env = [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "SERVICE_VERSION", "value": service_version},
    ]
    if overrides_present:
        env.append({"name": "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS", "value": "false"})
        env.append({"name": "APP_CATALOG_ENFORCE_HOLDING_INVARIANT", "value": "false"})
    return {
        "template": [
            {
                "min_replicas": min_replicas,
                "container": [{"image": IMAGE, "env": env}],
            }
        ]
    }


def _service_rc(address, *, actions, before, after):
    return {"address": address, "change": {"actions": list(actions), "before": before, "after": after}}


def _enable_plan(*, extra_changes=()):
    rcs = [
        _service_rc(
            addr,
            actions=["update"],
            before=_side(min_replicas=0, overrides_present=True),
            after=_side(min_replicas=1, overrides_present=False),
        )
        for addr in sut.SERVICE_ADDRESSES
    ]
    rcs.extend(extra_changes)
    return {"resource_changes": rcs}


def _abort_plan():
    return {
        "resource_changes": [
            _service_rc(
                addr,
                actions=["update"],
                before=_side(min_replicas=1, overrides_present=False),
                after=_side(min_replicas=0, overrides_present=True),
            )
            for addr in sut.SERVICE_ADDRESSES
        ]
    }


class SpecA99PlanTests(unittest.TestCase):
    def test_clean_enable_plan_passes(self):
        self.assertEqual(sut.evaluate_plan(_enable_plan(), "enable"), [])

    def test_clean_abort_plan_passes(self):
        self.assertEqual(sut.evaluate_plan(_abort_plan(), "abort"), [])

    def test_missing_service_fails(self):
        plan = _enable_plan()
        plan["resource_changes"].pop()
        errors = sut.evaluate_plan(plan, "enable")
        self.assertTrue(any("expected a change" in e for e in errors))

    def test_fourth_resource_changed_fails(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        errors = sut.evaluate_plan(_enable_plan(extra_changes=[extra]), "enable")
        self.assertTrue(any("unexpected non-no-op" in e for e in errors))

    def test_no_op_fourth_resource_is_ignored(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["no-op"], "before": {}, "after": {}},
        }
        self.assertEqual(sut.evaluate_plan(_enable_plan(extra_changes=[extra]), "enable"), [])

    def test_replace_action_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["actions"] = ["create", "delete"]
        errors = sut.evaluate_plan(plan, "enable")
        self.assertTrue(any("expected exactly ['update']" in e for e in errors))

    def test_min_replicas_not_raised_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=0, overrides_present=False)
        errors = sut.evaluate_plan(plan, "enable")
        self.assertTrue(any("min_replicas" in e for e in errors))

    def test_override_still_present_after_enable_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=1, overrides_present=True)
        errors = sut.evaluate_plan(plan, "enable")
        self.assertTrue(any("override-after" in e for e in errors))

    def test_override_set_true_instead_of_removed_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False)
        after["template"][0]["container"][0]["env"].append(
            {"name": "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS", "value": "true"}
        )
        plan["resource_changes"][0]["change"]["after"] = after
        errors = sut.evaluate_plan(plan, "enable")
        self.assertTrue(any("override-after" in e for e in errors))

    def test_image_change_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False)
        after["template"][0]["container"][0]["image"] = IMAGE.rsplit(":", 1)[0] + ":deadbeef" * 5
        plan["resource_changes"][0]["change"]["after"] = after
        errors = sut.evaluate_plan(plan, "enable")
        self.assertTrue(any("[image]" in e for e in errors))

    def test_service_version_change_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False, service_version="deadbeef" * 5)
        plan["resource_changes"][0]["change"]["after"] = after
        errors = sut.evaluate_plan(plan, "enable")
        self.assertTrue(any("service_version" in e for e in errors))

    def test_abort_override_present_before_fails(self):
        plan = _abort_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(min_replicas=1, overrides_present=True)
        errors = sut.evaluate_plan(plan, "abort")
        self.assertTrue(any("override-before" in e for e in errors))

    def test_abort_min_replicas_not_restored_fails(self):
        plan = _abort_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=1, overrides_present=True)
        errors = sut.evaluate_plan(plan, "abort")
        self.assertTrue(any("min_replicas" in e for e in errors))

    def test_unset_min_replicas_before_treated_as_zero_for_enable(self):
        plan = _enable_plan()
        before = _side(min_replicas=None, overrides_present=True)
        plan["resource_changes"][0]["change"]["before"] = before
        self.assertEqual(sut.evaluate_plan(plan, "enable"), [])


if __name__ == "__main__":
    unittest.main()
