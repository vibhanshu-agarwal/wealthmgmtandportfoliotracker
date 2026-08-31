#!/usr/bin/env python3
"""Adversarial fixture tests for assert_api_gateway_custom_domain_plan.py."""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_api_gateway_custom_domain_plan as sut  # noqa: E402

RESTORE = sut.RESTORE_PROFILE
REMOVE = sut.REMOVE_PROFILE
REOPEN = "spec-a-9.14-reopen-ingress"
CLOSE = "spec-a-9.14-close-ingress"
DOMAIN_ADDR = sut.CUSTOM_DOMAIN_ADDR
GATEWAY_ADDR = sut.GATEWAY_ADDR
HOSTNAME = sut.EXPECTED_HOSTNAME
GATEWAY_ID = (
    "/subscriptions/sub/resourceGroups/wealth-azure-prod-rg/providers/"
    "Microsoft.App/containerApps/api-gateway"
)
SECRET = "never-print-this-secret-value"
EXTERNAL_INGRESS = [{
    "external_enabled": True,
    "target_port": 8080,
    "transport": "auto",
    "traffic_weight": [{"percentage": 100, "latest_revision": True}],
}]


def _domain_rc(*, actions, before=None, after=None):
    return {
        "address": DOMAIN_ADDR,
        "type": "azurerm_container_app_custom_domain",
        "change": {"actions": list(actions), "before": before, "after": after},
    }


def _restore_create(**overrides):
    after = {
        "name": HOSTNAME,
        "container_app_id": GATEWAY_ID,
        **overrides,
    }
    return {"resource_changes": [_domain_rc(actions=["create"], after=after)]}


def _remove_delete(**overrides):
    before = {
        "name": HOSTNAME,
        "container_app_id": GATEWAY_ID,
        **overrides,
    }
    return {"resource_changes": [_domain_rc(actions=["delete"], before=before)]}


def _gateway_side(*, ingress):
    return {
        "identity": [{"type": "SystemAssigned"}],
        "registry": [{"server": "wealthprodacr.azurecr.io", "identity": "system"}],
        "secret": [{"name": "internal-api-key", "value": SECRET}],
        "ingress": copy.deepcopy(ingress),
        "template": [{
            "min_replicas": 0,
            "max_replicas": 3,
            "container": [{
                "name": "api-gateway",
                "image": "wealthprodacr.azurecr.io/api-gateway@sha256:" + "e5" * 32,
                "cpu": 0.5,
                "memory": "1Gi",
                "env": [{"name": "SERVICE_VERSION", "value": "18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17"}],
            }],
        }],
    }


def _gateway_rc(*, before, after):
    return {
        "address": GATEWAY_ADDR,
        "type": "azurerm_container_app",
        "change": {"actions": ["update"], "before": before, "after": after},
    }


def _reopen_plan():
    return {
        "resource_changes": [
            _gateway_rc(before=_gateway_side(ingress=[]), after=_gateway_side(ingress=EXTERNAL_INGRESS))
        ]
    }


def _close_plan():
    return {
        "resource_changes": [
            _gateway_rc(before=_gateway_side(ingress=EXTERNAL_INGRESS), after=_gateway_side(ingress=[]))
        ]
    }


class AssertApiGatewayCustomDomainPlanTests(unittest.TestCase):
    def test_exact_restore_create_passes(self):
        self.assertEqual(sut.evaluate_plan(_restore_create(), RESTORE, GATEWAY_ID), [])

    def test_exact_remove_delete_passes(self):
        self.assertEqual(sut.evaluate_plan(_remove_delete(), REMOVE, GATEWAY_ID), [])

    def test_wrong_hostname_fails(self):
        plan = _restore_create()
        plan["resource_changes"][0]["change"]["after"]["name"] = "evil.example.com"
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("hostname" in e for e in errors))

    def test_wrong_app_id_fails(self):
        plan = _restore_create()
        plan["resource_changes"][0]["change"]["after"]["container_app_id"] = "wrong"
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("gateway" in e for e in errors))

    def test_explicit_uploaded_certificate_id_fails(self):
        plan = _restore_create(container_app_environment_certificate_id=SECRET)
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("certificate" in e for e in errors))
        joined = "\n".join(errors)
        self.assertNotIn(SECRET, joined)

    def test_explicit_binding_type_fails(self):
        plan = _restore_create(certificate_binding_type="SniEnabled")
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("certificate" in e for e in errors))

    def test_managed_certificate_create_fails(self):
        plan = _restore_create()
        plan["resource_changes"].append({
            "address": "azurerm_container_app_environment_managed_certificate.api_gateway",
            "change": {"actions": ["create"], "after": {}},
        })
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("certificate" in e for e in errors))

    def test_gateway_change_during_restore_fails(self):
        plan = _restore_create()
        plan["resource_changes"].append({
            "address": GATEWAY_ADDR,
            "change": {"actions": ["update"], "before": {}, "after": {}},
        })
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("scope" in e for e in errors))

    def test_extra_changed_resource_fails(self):
        plan = _restore_create()
        plan["resource_changes"].append({
            "address": "azurerm_resource_group.main",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        })
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("scope" in e for e in errors))

    def test_replacement_action_fails(self):
        plan = _restore_create()
        plan["resource_changes"][0]["change"]["actions"] = ["delete", "create"]
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        self.assertTrue(any("action" in e for e in errors))

    def test_restore_under_standard_fails(self):
        errors = sut.evaluate_plan(_restore_create(), "standard")
        self.assertTrue(any("domain-guard" in e for e in errors))

    def test_remove_under_standard_fails(self):
        errors = sut.evaluate_plan(_remove_delete(), "standard")
        self.assertTrue(any("domain-guard" in e for e in errors))

    def test_profile_typo_fails_closed(self):
        errors = sut.evaluate_plan(_restore_create(), "api-gateway-custom-domain-restor")
        self.assertTrue(any("profile" in e for e in errors))

    def test_malformed_resource_changes_fails_closed(self):
        errors = sut.evaluate_plan({"resource_changes": "nope"}, RESTORE, GATEWAY_ID)
        self.assertTrue(any("plan" in e for e in errors))

    def test_missing_resource_changes_fails_closed(self):
        errors = sut.evaluate_plan({}, RESTORE, GATEWAY_ID)
        self.assertTrue(any("plan" in e for e in errors))

    def test_secret_like_fixture_values_never_appear_in_error_text(self):
        plan = _restore_create(container_app_environment_certificate_id=SECRET)
        errors = sut.evaluate_plan(plan, RESTORE, GATEWAY_ID)
        joined = "\n".join(errors)
        self.assertTrue(errors)
        self.assertNotIn(SECRET, joined)

    def test_known_profiles_include_restore_and_remove(self):
        self.assertIn(RESTORE, sut.KNOWN_PROFILES)
        self.assertIn(REMOVE, sut.KNOWN_PROFILES)

    def test_valid_9_14_reopen_passes_universal_guard(self):
        self.assertEqual(sut.evaluate_plan(_reopen_plan(), REOPEN), [])

    def test_valid_9_14_close_passes_universal_guard(self):
        self.assertEqual(sut.evaluate_plan(_close_plan(), CLOSE), [])

    def test_9_14_reopen_with_custom_domain_create_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"].append(_domain_rc(actions=["create"], after={"name": HOSTNAME}))
        errors = sut.evaluate_plan(plan, REOPEN)
        self.assertTrue(any("domain-guard" in e for e in errors))

    def test_9_14_close_with_custom_domain_delete_fails(self):
        plan = _close_plan()
        plan["resource_changes"].append(_domain_rc(actions=["delete"], before={"name": HOSTNAME}))
        errors = sut.evaluate_plan(plan, CLOSE)
        self.assertTrue(any("domain-guard" in e for e in errors))

    def test_9_14_reopen_with_certificate_resource_change_fails(self):
        plan = _reopen_plan()
        plan["resource_changes"].append({
            "address": "azurerm_container_app_environment_managed_certificate.api_gateway",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        })
        errors = sut.evaluate_plan(plan, REOPEN)
        self.assertTrue(any("certificate" in e for e in errors))

    def test_9_14_close_with_certificate_resource_change_fails(self):
        plan = _close_plan()
        plan["resource_changes"].append({
            "address": "azurerm_container_app_environment_certificate.api_gateway",
            "change": {"actions": ["delete"], "before": {}, "after": {}},
        })
        errors = sut.evaluate_plan(plan, CLOSE)
        self.assertTrue(any("certificate" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
