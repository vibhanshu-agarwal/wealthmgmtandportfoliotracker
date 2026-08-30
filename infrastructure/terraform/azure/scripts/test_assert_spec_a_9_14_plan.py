#!/usr/bin/env python3
"""Adversarial fixture tests for the Spec A checkpoint 9.14 gateway ingress plan guard."""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_spec_a_9_14_plan as sut  # noqa: E402

REOPEN = "spec-a-9.14-reopen-ingress"
CLOSE = "spec-a-9.14-close-ingress"
GATEWAY_ADDR = sut.GATEWAY_ADDR
PORTFOLIO_ADDR = "module.portfolio_service.azurerm_container_app.this"
JOB_ADDR = "azurerm_container_app_job.market_data_refresh"
SECRET = "never-print-this-secret-value"
ACR = "wealthprodacr.azurecr.io"
SERVICE_VERSION = "18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17"
IMAGE_DIGEST = "sha256:" + "e5" * 32
OTHER_DIGEST = "sha256:" + "f6" * 32
EXTERNAL_INGRESS = [{
    "external_enabled": True,
    "target_port": 8080,
    "transport": "auto",
    "traffic_weight": [{"percentage": 100, "latest_revision": True}],
}]


def _env(*, version=SERVICE_VERSION):
    return [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "SERVICE_VERSION", "value": version},
        {"name": "DEPLOYMENT_ENVIRONMENT_NAME", "value": "prod"},
        {"name": "PORTFOLIO_SERVICE_URL", "value": "http://portfolio-service"},
        {"name": "REDIS_URL", "secret_name": "redis-url"},
    ]


def _side(*, min_replicas=0, image=None, ingress=None, extra_env=(), cpu=0.5, memory="1Gi", max_replicas=3):
    if image is None:
        image = f"{ACR}/api-gateway@{IMAGE_DIGEST}"
    if ingress is None:
        ingress = []
    return {
        "identity": [{"type": "SystemAssigned"}],
        "registry": [{"server": ACR, "identity": "system"}],
        "secret": [{"name": "internal-api-key", "value": SECRET}],
        "ingress": copy.deepcopy(ingress),
        "template": [{
            "min_replicas": min_replicas,
            "max_replicas": max_replicas,
            "container": [{
                "name": "api-gateway",
                "image": image,
                "cpu": cpu,
                "memory": memory,
                "env": _env(),
            }],
        }],
    }


def _rc(*, actions=("update",), before, after):
    return {
        "address": GATEWAY_ADDR,
        "type": "azurerm_container_app",
        "change": {"actions": list(actions), "before": before, "after": after},
    }


def _reopen_plan(**kwargs):
    before = _side(ingress=[], **kwargs.get("before_kwargs", {}))
    after = _side(ingress=EXTERNAL_INGRESS, **kwargs.get("after_kwargs", {}))
    return {"resource_changes": [_rc(before=before, after=after)]}


def _close_plan(**kwargs):
    before = _side(ingress=EXTERNAL_INGRESS, **kwargs.get("before_kwargs", {}))
    after = _side(ingress=[], **kwargs.get("after_kwargs", {}))
    return {"resource_changes": [_rc(before=before, after=after)]}


def _evaluate(plan, profile=REOPEN):
    return sut.evaluate_plan(plan, profile)


class SpecA914PlanTests(unittest.TestCase):
    def test_valid_reopen_plan_passes(self):
        self.assertEqual(_evaluate(_reopen_plan()), [])

    def test_valid_close_plan_passes(self):
        self.assertEqual(_evaluate(_close_plan(), CLOSE), [])

    def test_unset_min_replicas_accepted(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["before"]["template"][0]["min_replicas"] = None
        plan["resource_changes"][0]["change"]["after"]["template"][0]["min_replicas"] = None
        self.assertEqual(_evaluate(plan), [])

    def test_wrong_direction_reopen_fails(self):
        plan = _close_plan()
        errors = _evaluate(plan, REOPEN)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_wrong_direction_close_fails(self):
        plan = _reopen_plan()
        errors = _evaluate(plan, CLOSE)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_noop_plan_under_reopen_fails(self):
        errors = _evaluate({"resource_changes": []}, REOPEN)
        self.assertTrue(any("scope" in e for e in errors), errors)

    def test_partial_transition_still_closed_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["ingress"] = []
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_partial_transition_still_open_fails(self):
        plan = _close_plan()
        plan["resource_changes"][0]["change"]["after"]["ingress"] = EXTERNAL_INGRESS
        errors = _evaluate(plan, CLOSE)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_replace_action_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["actions"] = ["create", "delete"]
        errors = _evaluate(plan)
        self.assertTrue(any("update" in e or "action" in e for e in errors), errors)

    def test_duplicate_gateway_address_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"].append(copy.deepcopy(plan["resource_changes"][0]))
        errors = _evaluate(plan)
        self.assertTrue(any("duplicate" in e for e in errors), errors)

    def test_malformed_resource_entry_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"].append("not-a-resource")
        errors = _evaluate(plan)
        self.assertTrue(any("plan" in e or "resource" in e for e in errors), errors)

    def test_malformed_change_payloads_fail_closed(self):
        cases = {
            "missing_change": {"address": GATEWAY_ADDR},
            "string_change": {"address": GATEWAY_ADDR, "change": "nope"},
            "missing_actions": {"address": GATEWAY_ADDR, "change": {"before": {}, "after": {}}},
            "empty_actions": {
                "address": GATEWAY_ADDR,
                "change": {"actions": [], "before": {}, "after": {}},
            },
        }
        for name, extra in cases.items():
            with self.subTest(name=name):
                plan = _reopen_plan()
                plan["resource_changes"].append(extra)
                errors = _evaluate(plan)
                self.assertTrue(errors, errors)

    def test_unexpected_portfolio_change_fails(self):
        extra = {
            "address": PORTFOLIO_ADDR,
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        plan = _reopen_plan()
        plan["resource_changes"].append(extra)
        errors = _evaluate(plan)
        self.assertTrue(any("unexpected" in e or "scope" in e for e in errors), errors)

    def test_unexpected_job_change_fails(self):
        extra = {
            "address": JOB_ADDR,
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        plan = _reopen_plan()
        plan["resource_changes"].append(extra)
        errors = _evaluate(plan)
        self.assertTrue(any("unexpected" in e or "scope" in e for e in errors), errors)

    def test_noop_extra_resource_is_ignored(self):
        extra = {
            "address": PORTFOLIO_ADDR,
            "change": {"actions": ["no-op"], "before": {}, "after": {}},
        }
        plan = _reopen_plan()
        plan["resource_changes"].append(extra)
        self.assertEqual(_evaluate(plan), [])

    def test_image_change_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["image"] = (
            f"{ACR}/api-gateway@{OTHER_DIGEST}"
        )
        errors = _evaluate(plan)
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_service_version_change_fails(self):
        plan = _reopen_plan()
        env = plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"]
        for entry in env:
            if entry.get("name") == "SERVICE_VERSION":
                entry["value"] = "1111111111111111111111111111111111111111"
        errors = _evaluate(plan)
        self.assertTrue(any("service_version" in e or "field" in e for e in errors), errors)

    def test_image_tag_divergence_from_service_version_is_allowed(self):
        plan = _reopen_plan(
            before_kwargs={"image": f"{ACR}/api-gateway@{IMAGE_DIGEST}"},
            after_kwargs={"image": f"{ACR}/api-gateway@{IMAGE_DIGEST}"},
        )
        self.assertEqual(_evaluate(plan), [])

    def test_scaling_mutation_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["max_replicas"] = 5
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e for e in errors), errors)

    def test_min_replicas_floor_one_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["min_replicas"] = 1
        errors = _evaluate(plan)
        self.assertTrue(any("min_replicas" in e for e in errors), errors)

    def test_target_port_mutation_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["target_port"] = 80
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_internal_ingress_after_reopen_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["external_enabled"] = False
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_allow_insecure_connections_false_after_reopen_passes(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["allow_insecure_connections"] = False
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        self.assertEqual(_evaluate(plan), [])

    def test_allow_insecure_connections_false_before_close_passes(self):
        plan = _close_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["allow_insecure_connections"] = False
        plan["resource_changes"][0]["change"]["before"]["ingress"] = changed
        self.assertEqual(_evaluate(plan, CLOSE), [])

    def test_allow_insecure_connections_after_reopen_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["allow_insecure_connections"] = True
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_allow_insecure_connections_before_close_fails(self):
        plan = _close_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["allow_insecure_connections"] = True
        plan["resource_changes"][0]["change"]["before"]["ingress"] = changed
        errors = _evaluate(plan, CLOSE)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_client_certificate_mode_after_reopen_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["client_certificate_mode"] = "require"
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_ip_security_restriction_after_reopen_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["ip_security_restriction"] = [{
            "action": "Allow",
            "name": "office",
            "ip_address_range": "203.0.113.0/24",
        }]
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_cors_after_reopen_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["cors"] = [{"allowed_origins": ["https://evil.example"]}]
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_exposed_port_after_reopen_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["exposed_port"] = 8443
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_provider_computed_fqdn_after_reopen_passes(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["fqdn"] = "api-gateway.example.azurecontainerapps.io"
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        self.assertEqual(_evaluate(plan), [])

    def test_extra_traffic_weight_fields_after_reopen_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["traffic_weight"] = [{
            "percentage": 100,
            "latest_revision": True,
            "revision_suffix": "canary",
        }]
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e for e in errors), errors)

    def test_traffic_split_mutation_fails(self):
        plan = _reopen_plan()
        changed = copy.deepcopy(EXTERNAL_INGRESS)
        changed[0]["traffic_weight"] = [
            {"percentage": 50, "latest_revision": True},
            {"percentage": 50, "revision_suffix": "old"},
        ]
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e or "field" in e for e in errors), errors)

    def test_environment_mutation_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"].append(
            {"name": "NEW_FLAG", "value": "true"}
        )
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e for e in errors), errors)

    def test_secret_binding_mutation_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["secret"] = [{"name": "other-secret", "value": SECRET}]
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e for e in errors), errors)

    def test_identity_mutation_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["identity"] = [{"type": "UserAssigned"}]
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e for e in errors), errors)

    def test_duplicate_environment_names_fail(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"].append(
            {"name": "SERVICE_VERSION", "value": SERVICE_VERSION}
        )
        errors = _evaluate(plan)
        self.assertTrue(any("duplicate" in e or "environment" in e or "field" in e for e in errors), errors)

    def test_reopen_under_standard_fails_non_scoped(self):
        errors = _evaluate(_reopen_plan(), "standard")
        self.assertTrue(any("ingress-guard" in e for e in errors), errors)

    def test_close_under_9_13_fails_non_scoped(self):
        errors = _evaluate(_close_plan(), "spec-a-9.13-restore-scale")
        self.assertTrue(any("ingress-guard" in e for e in errors), errors)

    def test_use_seed_image_profile_rejects_ingress_via_dispatch_tests(self):
        # Covered in test_validate_dispatch.py; keep a local reminder that 9.14 is scoped.
        self.assertIn(REOPEN, sut.KNOWN_PROFILES)
        self.assertIn(CLOSE, sut.KNOWN_PROFILES)

    def test_errors_do_not_leak_secrets(self):
        plan = _reopen_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["cpu"] = 1.0
        errors = _evaluate(plan)
        joined = "\n".join(errors)
        self.assertTrue(errors)
        self.assertNotIn(SECRET, joined)
        self.assertNotIn("never-print", joined)

    def test_unknown_profile_fails_closed(self):
        errors = sut.evaluate_plan(_reopen_plan(), "spec-a-9.14-reopen")
        self.assertTrue(any("profile" in e for e in errors), errors)


if __name__ == "__main__":
    unittest.main()
