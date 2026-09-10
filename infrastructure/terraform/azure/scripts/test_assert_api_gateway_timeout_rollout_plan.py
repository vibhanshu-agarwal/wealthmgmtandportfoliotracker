#!/usr/bin/env python3
"""Adversarial fixtures for the api-gateway timeout rollout plan guard."""

from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_api_gateway_timeout_rollout_plan as sut  # noqa: E402


PROFILE = "api-gateway-timeout-rollout"
GATEWAY_ADDR = "module.api_gateway.azurerm_container_app.this"
SECRET = "never-print-this-secret-value"
TIMEOUT_ENV = {
    "APP_DEMO_LOGIN_RESET_ELIGIBILITY_TIMEOUT": "120s",
    "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT": "30s",
    "APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT": "165s",
    "SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_RESPONSETIMEOUT": "150s",
}
GATEWAY_ID = "/subscriptions/11111111-1111-1111-1111-111111111111/resourceGroups/wealth-azure-prod-rg/providers/Microsoft.App/containerApps/api-gateway"
TAG = "a" * 40
DIGEST = "sha256:" + "a1" * 32
TAGS_JSON = json.dumps({name: TAG for name in ("api-gateway", "portfolio-service", "market-data-service", "insight-service")})
DIGESTS_JSON = json.dumps({name: DIGEST for name in ("api-gateway", "portfolio-service", "market-data-service", "insight-service")})


def _side(env):
    return {
        "id": GATEWAY_ID,
        "identity": [{"type": "SystemAssigned"}],
        "ingress": [{"external_enabled": True, "target_port": 8080}],
        "secret": [{"name": "internal-api-key", "value": SECRET}],
        "template": [{
            "min_replicas": 0,
            "max_replicas": 3,
            "container": [{
                "name": "api-gateway",
                "image": "wealthprodacr.azurecr.io/api-gateway@" + DIGEST,
                "cpu": 0.5,
                "memory": "1Gi",
                "env": env,
            }],
        }],
    }


def _plan(before_env=None, after_env=None):
    before_env = list(before_env or [{"name": "SERVICE_VERSION", "value": TAG}])
    after_env = list(after_env or [*before_env, *({"name": key, "value": value} for key, value in TIMEOUT_ENV.items())])
    return {"resource_changes": [{
        "address": GATEWAY_ADDR,
        "type": "azurerm_container_app",
        "change": {"actions": ["update"], "before": _side(before_env), "after": _side(after_env)},
    }]}


def _evaluate(plan, profile=PROFILE):
    return sut.evaluate_plan(plan, profile, GATEWAY_ID, TAGS_JSON, DIGESTS_JSON, DIGEST)


def _evaluate_with_serving_digest(plan, serving_digest, *, tag_digests=DIGESTS_JSON):
    try:
        return sut.evaluate_plan(
            plan, PROFILE, GATEWAY_ID, TAGS_JSON, tag_digests, serving_digest
        )
    except TypeError:
        return ["missing serving-digest attestation interface"]


class AssertApiGatewayTimeoutRolloutPlanTests(unittest.TestCase):
    def test_exact_four_additions_pass(self):
        self.assertEqual(_evaluate(_plan()), [])

    def test_env_order_is_normalized(self):
        plan = _plan()
        env = plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"]
        env.reverse()
        self.assertEqual(_evaluate(plan), [])

    def test_case_only_gateway_id_difference_passes_the_timeout_baseline(self):
        plan = _plan()
        for side in ("before", "after"):
            plan["resource_changes"][0]["change"][side]["id"] = GATEWAY_ID.upper()
        self.assertEqual(_evaluate(plan), [])

    def test_verified_running_gateway_digest_wins_over_acr_tag_digest(self):
        tag_digest = "sha256:" + "b2" * 32
        tag_digests = json.dumps({
            name: tag_digest
            for name in ("api-gateway", "portfolio-service", "market-data-service", "insight-service")
        })
        self.assertEqual(_evaluate_with_serving_digest(_plan(), DIGEST, tag_digests=tag_digests), [])

    def test_missing_or_mismatched_serving_digest_fails_closed(self):
        other_digest = "sha256:" + "c3" * 32
        self.assertTrue(_evaluate_with_serving_digest(_plan(), ""))
        self.assertTrue(_evaluate_with_serving_digest(_plan(), other_digest))

    def test_missing_or_wrong_timeout_fails(self):
        for name, value in (("APP_DEMO_LOGIN_RESET_RESET_TIMEOUT", None),
                            ("APP_DEMO_LOGIN_RESET_OVERALL_TIMEOUT", "166s")):
            with self.subTest(name=name, value=value):
                plan = _plan()
                env = plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"]
                if value is None:
                    env[:] = [entry for entry in env if entry["name"] != name]
                else:
                    next(entry for entry in env if entry["name"] == name)["value"] = value
                self.assertTrue(_evaluate(plan))

    def test_duplicate_or_preexisting_timeout_fails(self):
        duplicate = _plan()
        env = duplicate["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"]
        env.append(copy.deepcopy(env[-1]))
        self.assertTrue(_evaluate(duplicate))

        preexisting = _plan()
        before = preexisting["resource_changes"][0]["change"]["before"]["template"][0]["container"][0]["env"]
        before.append({"name": "APP_DEMO_LOGIN_RESET_RESET_TIMEOUT", "value": "30s"})
        self.assertTrue(_evaluate(preexisting))

    def test_other_gateway_field_or_resource_change_fails_without_secret_leak(self):
        plan = _plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["min_replicas"] = 1
        errors = _evaluate(plan)
        self.assertTrue(errors)
        self.assertNotIn(SECRET, "\n".join(errors))

        plan = _plan()
        plan["resource_changes"].append({
            "address": "azurerm_resource_group.unrelated",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        })
        self.assertTrue(_evaluate(plan))

    def test_non_scoped_profile_rejects_timeout_transition(self):
        self.assertTrue(_evaluate(_plan(), "standard"))

    def test_rejects_untrusted_metadata_and_baseline_drift(self):
        for field, value in (("previous_address", GATEWAY_ADDR), ("deposed", "0")):
            with self.subTest(field=field):
                plan = _plan()
                plan["resource_changes"][0][field] = value
                self.assertTrue(_evaluate(plan))
        plan = _plan()
        change = plan["resource_changes"][0]["change"]
        change["after_unknown"] = {"template": [{"min_replicas": True}]}
        self.assertTrue(_evaluate(plan))
        plan = _plan()
        plan["resource_changes"][0]["change"]["after_sensitive"] = {
            "template": [{"container": [{"env": [True]}]}]
        }
        self.assertTrue(_evaluate(plan))
        plan = _plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["image"] = "wrong"
        self.assertTrue(_evaluate(plan))

    def test_all_false_metadata_masks_are_harmless(self):
        plan = _plan()
        change = plan["resource_changes"][0]["change"]
        change["after_unknown"] = {"template": [{"container": [{"env": [False, False, False, False, False]}]}]}
        change["before_sensitive"] = {"template": [{"container": [{"env": [False]}]}]}
        change["after_sensitive"] = {"template": [{"container": [{"env": [False, False, False, False, False]}]}]}
        self.assertEqual(_evaluate(plan), [])

    def test_existing_sensitive_masks_are_preserved_and_noop_masks_are_allowed(self):
        plan = _plan()
        change = plan["resource_changes"][0]["change"]
        mask = {"secret": [{"value": True}]}
        change["before_sensitive"] = copy.deepcopy(mask)
        change["after_sensitive"] = copy.deepcopy(mask)
        plan["resource_changes"].append({
            "address": "azurerm_resource_group.noop",
            "change": {"actions": ["no-op"], "before": {"secret": [{"value": "x"}]}, "after": {"secret": [{"value": "x"}]}, "before_sensitive": mask, "after_sensitive": mask},
        })
        self.assertEqual(_evaluate(plan), [])

    def test_noop_sensitivity_downgrade_new_or_malformed_path_fails(self):
        for before_mask, after_mask in (
            ({"secret": [{"value": True}]}, {}),
            ({}, {"secret": [{"value": True}]}),
            ({"missing": True}, {"missing": True}),
        ):
            with self.subTest(before_mask=before_mask, after_mask=after_mask):
                plan = _plan()
                plan["resource_changes"].append({
                    "address": "azurerm_resource_group.noop",
                    "change": {"actions": ["no-op"], "before": {"secret": [{"value": "x"}]}, "after": {"secret": [{"value": "x"}]}, "before_sensitive": before_mask, "after_sensitive": after_mask},
                })
                self.assertTrue(_evaluate(plan))

    def test_sensitive_mask_downgrade_new_value_or_misalignment_fails(self):
        plan = _plan()
        change = plan["resource_changes"][0]["change"]
        change["before_sensitive"] = {"secret": [{"value": True}]}
        change["after_sensitive"] = {}
        self.assertTrue(_evaluate(plan))
        plan = _plan()
        change = plan["resource_changes"][0]["change"]
        change["after_sensitive"] = {"template": [{"container": [{"env": [False, {"value": True}, False, False, False]}]}]}
        self.assertTrue(_evaluate(plan))
        plan = _plan()
        plan["resource_changes"][0]["change"]["after_sensitive"] = {"not_a_field": True}
        self.assertTrue(_evaluate(plan))

    def test_non_rollout_allows_unrelated_create_delete_but_unknown_profile_fails(self):
        plan = {"resource_changes": [{"address": "azurerm_resource_group.other", "change": {"actions": ["create"], "before": None, "after": {}}}]}
        self.assertEqual(_evaluate(plan, "standard"), [])
        self.assertTrue(_evaluate(plan, "not-a-profile"))


if __name__ == "__main__":
    unittest.main()
