#!/usr/bin/env python3
"""Fixture tests for summarize_plan.py (Spec A checkpoint 9.9 hardening)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import summarize_plan as sut  # noqa: E402


class SummarizePlanTests(unittest.TestCase):
    def test_no_changes_is_empty(self):
        self.assertEqual(sut.summarize({"resource_changes": []}), [])

    def test_no_op_is_excluded(self):
        plan = {
            "resource_changes": [
                {"address": "module.x.y", "change": {"actions": ["no-op"], "before": {}, "after": {}}}
            ]
        }
        self.assertEqual(sut.summarize(plan), [])

    def test_update_is_included_as_address_and_actions_only(self):
        plan = {
            "resource_changes": [
                {
                    "address": "module.portfolio_service.azurerm_container_app.this",
                    "change": {
                        "actions": ["update"],
                        "before": {"template": [{"min_replicas": 0}]},
                        "after": {"template": [{"min_replicas": 1}]},
                    },
                }
            ]
        }
        lines = sut.summarize(plan)
        self.assertEqual(lines, ["module.portfolio_service.azurerm_container_app.this ['update']"])

    def test_sensitive_values_never_appear_in_output(self):
        # The whole point: even if before/after carry secret-derived values, only the
        # address and action list are ever emitted.
        plan = {
            "resource_changes": [
                {
                    "address": "module.portfolio_service.azurerm_container_app.this",
                    "change": {
                        "actions": ["update"],
                        "before": {
                            "template": [
                                {"container": [{"env": [{"name": "SOME_SECRET", "value": "sk-super-secret-value"}]}]}
                            ]
                        },
                        "after": {
                            "template": [
                                {"container": [{"env": [{"name": "SOME_SECRET", "value": "sk-different-secret"}]}]}
                            ]
                        },
                    },
                }
            ]
        }
        lines = sut.summarize(plan)
        joined = "\n".join(lines)
        self.assertNotIn("sk-super-secret-value", joined)
        self.assertNotIn("sk-different-secret", joined)
        self.assertNotIn("SOME_SECRET", joined)

    def test_multiple_resources_all_listed(self):
        plan = {
            "resource_changes": [
                {"address": "a", "change": {"actions": ["update"], "before": {}, "after": {}}},
                {"address": "b", "change": {"actions": ["no-op"], "before": {}, "after": {}}},
                {"address": "c", "change": {"actions": ["create"], "before": None, "after": {}}},
            ]
        }
        lines = sut.summarize(plan)
        self.assertEqual(lines, ["a ['update']", "c ['create']"])


if __name__ == "__main__":
    unittest.main()
