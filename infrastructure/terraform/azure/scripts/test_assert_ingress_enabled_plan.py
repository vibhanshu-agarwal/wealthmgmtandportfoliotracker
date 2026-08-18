#!/usr/bin/env python3
"""Fixture tests for assert_ingress_enabled_plan.py (Spec A task 5.5)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_ingress_enabled_plan as aie  # noqa: E402


def _gateway(*, actions, before_ingress, after_ingress) -> dict:
    def side(has_ingress):
        if has_ingress is None:
            return None
        after = {}
        if has_ingress:
            after["ingress"] = [{"external_enabled": True, "target_port": 8080}]
        else:
            after["ingress"] = []
        return after

    return {
        "address": aie.GATEWAY_ADDRESS,
        "type": "azurerm_container_app",
        "name": "this",
        "change": {
            "actions": list(actions),
            "before": side(before_ingress),
            "after": side(after_ingress),
        },
    }


def _plan(*rcs: dict) -> dict:
    return {"resource_changes": list(rcs)}


class IngressEnabledPlanTests(unittest.TestCase):
    def test_disable_ingress_update_passes(self):
        plan = _plan(_gateway(actions=["update"], before_ingress=True, after_ingress=False))
        self.assertEqual(aie.evaluate_plan(plan), [])

    def test_disable_ingress_replace_fails(self):
        plan = _plan(
            _gateway(actions=["create", "delete"], before_ingress=True, after_ingress=False)
        )
        errors = aie.evaluate_plan(plan)
        self.assertTrue(errors)
        self.assertIn("replace", errors[0])

    def test_unrelated_update_passes(self):
        plan = _plan(_gateway(actions=["update"], before_ingress=True, after_ingress=True))
        self.assertEqual(aie.evaluate_plan(plan), [])

    def test_gateway_absent_passes(self):
        self.assertEqual(aie.evaluate_plan({"resource_changes": []}), [])


if __name__ == "__main__":
    unittest.main()
