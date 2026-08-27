#!/usr/bin/env python3
"""Fixture tests for assert_spec_a_9_11_plan.py (Spec A checkpoint 9.11)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_spec_a_9_11_plan as sut  # noqa: E402

EXPECTED_TAG = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
WRONG_TAG = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
ACR = sut.ACR_LOGIN_SERVER
EXPECTED_IMAGE = f"{ACR}/market-data-service:{EXPECTED_TAG}"
JOB = sut.JOB_ADDRESS
SECRET_REF_VALUE = "super-secret-should-never-appear-in-errors"


def _env(runner_value: str, *, service_version: str = EXPECTED_TAG, extra=None) -> list[dict]:
    env = [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "SPRING_MAIN_WEB_APPLICATION_TYPE", "value": "none"},
        {"name": "MARKET_DATA_JOB_RUNNER_ENABLED", "value": runner_value},
        {"name": "SERVICE_VERSION", "value": service_version},
        {"name": "SPRING_DATA_MONGODB_URI", "secret_name": "spring-data-mongodb-uri"},
        {"name": "INTERNAL_API_KEY", "secret_name": "internal-api-key"},
    ]
    if extra:
        env.extend(extra)
    return env


def _side(
    *,
    runner_value: str,
    image: str = EXPECTED_IMAGE,
    service_version: str = EXPECTED_TAG,
    retry: int = 0,
    timeout: int = 600,
    cron: str = "0 8 * * *",
    parallelism: int = 1,
    completion: int = 1,
    cpu: float = 0.5,
    memory: str = "1Gi",
    extra_env=None,
    identity_ids=None,
    registry_identity: str = "/subscriptions/sub/resourceGroups/rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/job",
) -> dict:
    if identity_ids is None:
        identity_ids = [
            "/subscriptions/sub/resourceGroups/rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/job"
        ]
    return {
        "replica_retry_limit": retry,
        "replica_timeout_in_seconds": timeout,
        "schedule_trigger_config": [
            {
                "cron_expression": cron,
                "parallelism": parallelism,
                "replica_completion_count": completion,
            }
        ],
        "identity": [{"type": "UserAssigned", "identity_ids": list(identity_ids)}],
        "registry": [{"server": ACR, "identity": registry_identity}],
        "secret": [
            {"name": "spring-data-mongodb-uri", "value": SECRET_REF_VALUE},
            {"name": "internal-api-key", "value": SECRET_REF_VALUE},
        ],
        "template": [
            {
                "container": [
                    {
                        "name": "market-data-refresh",
                        "image": image,
                        "cpu": cpu,
                        "memory": memory,
                        "env": _env(runner_value, service_version=service_version, extra=extra_env),
                    }
                ]
            }
        ],
    }


def _job_rc(*, actions, before, after) -> dict:
    return {
        "address": JOB,
        "type": "azurerm_container_app_job",
        "name": "market_data_refresh",
        "change": {
            "actions": list(actions),
            "before": before,
            "after": after,
        },
    }


def _plan(*rcs: dict) -> dict:
    return {"resource_changes": list(rcs)}


def _enable_plan(*, extra_changes=()):
    rc = _job_rc(
        actions=["update"],
        before=_side(runner_value="false"),
        after=_side(runner_value="true"),
    )
    return _plan(rc, *extra_changes)


def _abort_plan(*, extra_changes=()):
    rc = _job_rc(
        actions=["update"],
        before=_side(runner_value="true"),
        after=_side(runner_value="false"),
    )
    return _plan(rc, *extra_changes)


def _evaluate(plan, profile, expected_tag=EXPECTED_TAG):
    return sut.evaluate_plan(plan, profile, expected_tag)


class SpecA911PlanTests(unittest.TestCase):
    def test_clean_enable_plan_passes(self):
        self.assertEqual(_evaluate(_enable_plan(), "spec-a-9.11-enable"), [])

    def test_clean_abort_plan_passes(self):
        self.assertEqual(_evaluate(_abort_plan(), "spec-a-9.11-abort"), [])

    def test_standard_with_runner_unchanged_passes(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(runner_value="false", cpu=1.0),
            )
        )
        self.assertEqual(_evaluate(plan, "standard"), [])

    def test_9_9_enable_with_runner_unchanged_passes(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(runner_value="false"),
            )
        )
        self.assertEqual(_evaluate(plan, "spec-a-9.9-enable"), [])

    def test_9_9_abort_with_runner_unchanged_passes(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="true"),
                after=_side(runner_value="true"),
            )
        )
        self.assertEqual(_evaluate(plan, "spec-a-9.9-abort"), [])

    def test_standard_rejects_runner_change(self):
        errors = _evaluate(_enable_plan(), "standard")
        self.assertTrue(errors)
        self.assertTrue(any("runner" in e.lower() or "MARKET_DATA_JOB_RUNNER" in e for e in errors))

    def test_9_9_profile_rejects_runner_change(self):
        errors = _evaluate(_enable_plan(), "spec-a-9.9-enable")
        self.assertTrue(errors)

    def test_enable_reversed_transition_fails(self):
        errors = _evaluate(_abort_plan(), "spec-a-9.11-enable")
        self.assertTrue(errors)
        self.assertTrue(any("runner" in e.lower() or "direction" in e.lower() for e in errors))

    def test_abort_reversed_transition_fails(self):
        errors = _evaluate(_enable_plan(), "spec-a-9.11-abort")
        self.assertTrue(errors)

    def test_already_at_target_fails(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="true"),
                after=_side(runner_value="true"),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_missing_runner_env_fails(self):
        before = _side(runner_value="false")
        after = _side(runner_value="true")
        after["template"][0]["container"][0]["env"] = [
            e
            for e in after["template"][0]["container"][0]["env"]
            if e["name"] != "MARKET_DATA_JOB_RUNNER_ENABLED"
        ]
        plan = _plan(_job_rc(actions=["update"], before=before, after=after))
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_duplicated_runner_env_fails(self):
        before = _side(runner_value="false")
        after = _side(
            runner_value="true",
            extra_env=[{"name": "MARKET_DATA_JOB_RUNNER_ENABLED", "value": "true"}],
        )
        plan = _plan(_job_rc(actions=["update"], before=before, after=after))
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_create_action_fails(self):
        plan = _plan(
            _job_rc(
                actions=["create"],
                before=None,
                after=_side(runner_value="true"),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("update" in e for e in errors))

    def test_delete_action_fails(self):
        plan = _plan(
            _job_rc(
                actions=["delete"],
                before=_side(runner_value="false"),
                after=None,
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_replace_action_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["actions"] = ["create", "delete"]
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("update" in e for e in errors))

    def test_noop_only_fails_under_scoped_profile(self):
        plan = _plan(
            {
                "address": JOB,
                "change": {
                    "actions": ["no-op"],
                    "before": _side(runner_value="false"),
                    "after": _side(runner_value="false"),
                },
            }
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("expected a change" in e or "scope" in e for e in errors))

    def test_missing_target_fails(self):
        errors = _evaluate({"resource_changes": []}, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_additional_non_noop_resource_fails(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        errors = _evaluate(_enable_plan(extra_changes=[extra]), "spec-a-9.11-enable")
        self.assertTrue(any("unexpected non-no-op" in e for e in errors))

    def test_noop_extra_resource_is_ignored(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["no-op"], "before": {}, "after": {}},
        }
        self.assertEqual(_evaluate(_enable_plan(extra_changes=[extra]), "spec-a-9.11-enable"), [])

    def test_wrong_image_both_sides_fails(self):
        wrong = f"{ACR}/market-data-service:{WRONG_TAG}"
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false", image=wrong, service_version=WRONG_TAG),
                after=_side(runner_value="true", image=wrong, service_version=WRONG_TAG),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("[image]" in e for e in errors))
        self.assertTrue(any("service_version" in e for e in errors))

    def test_wrong_service_version_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(
            runner_value="true", service_version=WRONG_TAG
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("service_version" in e for e in errors))

    def test_retry_limit_change_fails(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(runner_value="true", retry=1),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("retry" in e.lower() or "safety" in e.lower() or "field" in e for e in errors))

    def test_timeout_change_fails(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(runner_value="true", timeout=300),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_cron_change_fails(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(runner_value="true", cron="0 9 * * *"),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_cpu_change_fails(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(runner_value="true", cpu=1.0),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("field" in e or "other" in e.lower() or "normalized" in e.lower() for e in errors))

    def test_identity_change_fails(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(
                    runner_value="true",
                    identity_ids=["/subscriptions/sub/resourceGroups/rg/providers/Microsoft.ManagedIdentity/userAssignedIdentities/other"],
                ),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)

    def test_unknown_profile_fails_closed(self):
        errors = _evaluate(_enable_plan(), "bogus-profile")
        self.assertTrue(errors)
        self.assertTrue(any("profile" in e.lower() for e in errors))

    def test_errors_do_not_include_secret_values(self):
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false"),
                after=_side(runner_value="true", cpu=1.0),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(errors)
        joined = "\n".join(errors)
        self.assertNotIn(SECRET_REF_VALUE, joined)
        self.assertNotIn("super-secret", joined)

    def test_different_expected_tag_is_honoured(self):
        other = "1111111111111111111111111111111111abcd"
        image = f"{ACR}/market-data-service:{other}"
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false", image=image, service_version=other),
                after=_side(runner_value="true", image=image, service_version=other),
            )
        )
        self.assertEqual(_evaluate(plan, "spec-a-9.11-enable", expected_tag=other), [])

    def test_wrong_registry_fails(self):
        evil = f"evil.example/market-data-service:{EXPECTED_TAG}"
        plan = _plan(
            _job_rc(
                actions=["update"],
                before=_side(runner_value="false", image=evil),
                after=_side(runner_value="true", image=evil),
            )
        )
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("[image]" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
