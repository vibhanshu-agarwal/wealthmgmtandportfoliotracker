#!/usr/bin/env python3
"""Fixture tests for assert_mongo_repair_job_plan.py (Spec A task 7.1)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_mongo_repair_job_plan as amr  # noqa: E402


def _job_values(*, repair="true", refresh=None, web="none", manual=True, scheduled=False, image="example.azurecr.io/market-data-service:abc", timeout=300):
    env = [
        {"name": "SPRING_MAIN_WEB_APPLICATION_TYPE", "value": web},
        {"name": "MARKET_DATA_REPAIR_ENABLED", "value": repair},
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
    ]
    if refresh is not None:
        env.append({"name": "MARKET_DATA_JOB_RUNNER_ENABLED", "value": refresh})
    return {
        "manual_trigger_config": [{}] if manual else [],
        "schedule_trigger_config": [{}] if scheduled else [],
        "replica_timeout_in_seconds": timeout,
        "template": [{"container": [{"image": image, "env": env}]}],
    }


def _plan(values: dict | None) -> dict:
    resources = []
    if values is not None:
        resources.append({"address": amr.JOB_ADDRESS, "values": values})
    return {"planned_values": {"root_module": {"resources": resources}}}


class MongoRepairJobPlanTests(unittest.TestCase):
    def test_valid_job_passes(self):
        self.assertEqual(amr.evaluate_plan(_plan(_job_values())), [])

    def test_missing_job_fails(self):
        errors = amr.evaluate_plan(_plan(None))
        self.assertTrue(errors)
        self.assertIn("missing", errors[0])

    def test_refresh_false_fails(self):
        errors = amr.evaluate_plan(_plan(_job_values(refresh="false")))
        self.assertTrue(any("MARKET_DATA_JOB_RUNNER_ENABLED" in e for e in errors))

    def test_repair_not_true_fails(self):
        errors = amr.evaluate_plan(_plan(_job_values(repair="false")))
        self.assertTrue(any("MARKET_DATA_REPAIR_ENABLED" in e for e in errors))

    def test_web_not_none_fails(self):
        errors = amr.evaluate_plan(_plan(_job_values(web="servlet")))
        self.assertTrue(any("SPRING_MAIN_WEB_APPLICATION_TYPE" in e for e in errors))

    def test_schedule_trigger_fails(self):
        errors = amr.evaluate_plan(_plan(_job_values(scheduled=True)))
        self.assertTrue(any("schedule_trigger_config" in e for e in errors))

    def test_seed_image_passes(self):
        self.assertEqual(
            amr.evaluate_plan(
                _plan(_job_values(image="mcr.microsoft.com/azuredocs/containerapps-helloworld:latest"))
            ),
            [],
        )


if __name__ == "__main__":
    unittest.main()
