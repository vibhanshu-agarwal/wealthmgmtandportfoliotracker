#!/usr/bin/env python3
"""Fixture tests for assert_job_runner_env_update.py (Spec A task 5.4)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_job_runner_env_update as aje  # noqa: E402


def _job(*, actions, before_value, after_value) -> dict:
    def side(value):
        if value is None and "delete" in actions and before_value is None:
            return None
        return {
            "template": [
                {
                    "container": [
                        {
                            "env": [
                                {"name": "MARKET_DATA_JOB_RUNNER_ENABLED", "value": value},
                                {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
                            ]
                        }
                    ]
                }
            ]
        }

    return {
        "address": aje.JOB_ADDRESS,
        "type": "azurerm_container_app_job",
        "name": "market_data_refresh",
        "change": {
            "actions": list(actions),
            "before": side(before_value),
            "after": None if after_value is None and "delete" in actions and "create" not in actions else side(after_value),
        },
    }


def _plan(*rcs: dict) -> dict:
    return {"resource_changes": list(rcs)}


class JobRunnerEnvUpdateTests(unittest.TestCase):
    def test_env_change_update_passes(self):
        plan = _plan(_job(actions=["update"], before_value="true", after_value="false"))
        self.assertEqual(aje.evaluate_plan(plan), [])

    def test_env_change_replace_fails(self):
        plan = _plan(_job(actions=["create", "delete"], before_value="true", after_value="false"))
        errors = aje.evaluate_plan(plan)
        self.assertTrue(errors)
        self.assertIn("ForceNew", errors[0])

    def test_unchanged_env_ignores_other_job_actions(self):
        plan = _plan(_job(actions=["update"], before_value="true", after_value="true"))
        self.assertEqual(aje.evaluate_plan(plan), [])

    def test_job_absent_from_plan_passes(self):
        self.assertEqual(aje.evaluate_plan({"resource_changes": []}), [])

    def test_greenfield_create_passes(self):
        """PR plans use a local empty backend; the Job is created, not replaced."""
        plan = {
            "resource_changes": [
                {
                    "address": aje.JOB_ADDRESS,
                    "type": "azurerm_container_app_job",
                    "name": "market_data_refresh",
                    "change": {
                        "actions": ["create"],
                        "before": None,
                        "after": {
                            "template": [
                                {
                                    "container": [
                                        {
                                            "env": [
                                                {
                                                    "name": "MARKET_DATA_JOB_RUNNER_ENABLED",
                                                    "value": "true",
                                                }
                                            ]
                                        }
                                    ]
                                }
                            ]
                        },
                    },
                }
            ]
        }
        self.assertEqual(aje.evaluate_plan(plan), [])

    def test_delete_only_fails_when_env_changes(self):
        plan = _plan(_job(actions=["delete"], before_value="true", after_value=None))
        errors = aje.evaluate_plan(plan)
        self.assertTrue(errors)


if __name__ == "__main__":
    unittest.main()
