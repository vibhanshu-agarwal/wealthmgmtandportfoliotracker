#!/usr/bin/env python3
"""Adversarial fixture tests for the Spec A checkpoint 9.13 restore-scale plan guard."""

from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_spec_a_9_13_plan as sut  # noqa: E402
import assert_spec_a_9_9_plan as guard_9_9  # noqa: E402

PROFILE = "spec-a-9.13-restore-scale"
EXPECTED_DIGEST = "sha256:" + "a1" * 32
OTHER_DIGEST = "sha256:" + "b2" * 32
MARKET_DIGEST = "sha256:" + "c3" * 32
INSIGHT_DIGEST = "sha256:" + "d4" * 32
GATEWAY_DIGEST = "sha256:" + "e5" * 32
WRONG_MARKET_DIGEST = "sha256:" + "f6" * 32
WRONG_INSIGHT_DIGEST = "sha256:" + "a7" * 32
GATEWAY_TAG = "18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17"
PORTFOLIO_TAG = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
MARKET_TAG = "1111111111111111111111111111111111111111"
INSIGHT_TAG = "2222222222222222222222222222222222222222"
SECRET = "never-print-this-secret-value"
ACR = "wealthprodacr.azurecr.io"
PORTFOLIO_ADDR = "module.portfolio_service.azurerm_container_app.this"
MARKET_ADDR = "module.market_data_service.azurerm_container_app.this"
INSIGHT_ADDR = "module.insight_service.azurerm_container_app.this"
GATEWAY_ADDR = "module.api_gateway.azurerm_container_app.this"
JOB_ADDR = "azurerm_container_app_job.market_data_refresh"
OVERRIDE_NAMES = (
    "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS",
    "APP_CATALOG_ENFORCE_HOLDING_INVARIANT",
)
INTERNAL_INGRESS = [{
    "external_enabled": False,
    "target_port": 8080,
    "transport": "auto",
    "traffic_weight": [{"percentage": 100, "latest_revision": True}],
}]


def _tags_map(**overrides) -> dict[str, str]:
    tags = {
        "api-gateway": GATEWAY_TAG,
        "portfolio-service": PORTFOLIO_TAG,
        "market-data-service": MARKET_TAG,
        "insight-service": INSIGHT_TAG,
    }
    tags.update(overrides)
    return tags


def _digests_map(**overrides) -> dict[str, str]:
    digests = {
        "api-gateway": GATEWAY_DIGEST,
        "portfolio-service": "sha256:" + "b8" * 32,
        "market-data-service": MARKET_DIGEST,
        "insight-service": INSIGHT_DIGEST,
    }
    digests.update(overrides)
    return digests


def _image(repo: str, *, digest: str | None = None, tag: str | None = None) -> str:
    if digest is not None:
        return f"{ACR}/{repo}@{digest}"
    return f"{ACR}/{repo}:{tag}"


def _env(service: str, *, version: str, extra=()):
    env = [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "SERVICE_VERSION", "value": version},
        {"name": "DEPLOYMENT_ENVIRONMENT_NAME", "value": "prod"},
        {"name": "MANAGEMENT_TRACING_EXPORT_ENABLED", "value": "true"},
    ]
    if service == "portfolio-service":
        env.extend(
            [
                {"name": "APP_DEMO_SEED_ON_STARTUP", "value": "false"},
                {"name": "APP_DEMO_TX_DIAGNOSTICS", "value": "false"},
                {"name": "SPRING_DATASOURCE_URL", "secret_name": "spring-datasource-url"},
                {"name": "REDIS_URL", "secret_name": "redis-url"},
            ]
        )
    elif service == "market-data-service":
        env.append({"name": "SPRING_DATA_MONGODB_URI", "secret_name": "mongodb-uri"})
    else:
        env.append({"name": "REDIS_URL", "secret_name": "redis-url"})
    env.extend(copy.deepcopy(list(extra)))
    return env


def _side(
    service: str,
    *,
    min_replicas,
    version: str | None = None,
    image: str | None = None,
    extra_env=(),
    cpu=0.5,
    memory="1Gi",
    max_replicas=3,
    ingress=None,
    secret=None,
):
    repo = service
    if version is None:
        version = {
            "portfolio-service": PORTFOLIO_TAG,
            "market-data-service": MARKET_TAG,
            "insight-service": INSIGHT_TAG,
        }[service]
    if image is None:
        image = (
            _image(repo, digest=EXPECTED_DIGEST)
            if service == "portfolio-service"
            else _image(repo, tag=version)
        )
    return {
        "identity": [{"type": "SystemAssigned"}],
        "registry": [{"server": ACR, "identity": "system"}],
        "secret": copy.deepcopy(secret if secret is not None else [{"name": "internal-api-key", "value": SECRET}]),
        "ingress": copy.deepcopy(INTERNAL_INGRESS if ingress is None else ingress),
        "template": [{
            "min_replicas": min_replicas,
            "max_replicas": max_replicas,
            "container": [{
                "name": service,
                "image": image,
                "cpu": cpu,
                "memory": memory,
                "env": _env(service, version=version, extra=extra_env),
            }],
        }],
    }


def _rc(address: str, *, actions=("update",), before, after):
    return {
        "address": address,
        "type": "azurerm_container_app",
        "change": {"actions": list(actions), "before": before, "after": after},
    }


def _service_pair(address: str, service: str, **side_kwargs):
    return _rc(
        address,
        before=_side(service, min_replicas=1, **side_kwargs),
        after=_side(service, min_replicas=0, **side_kwargs),
    )


def _restore_plan(*, extra_changes=(), **per_service):
    return {
        "resource_changes": [
            _service_pair(PORTFOLIO_ADDR, "portfolio-service", **per_service.get("portfolio", {})),
            _service_pair(MARKET_ADDR, "market-data-service", **per_service.get("market", {})),
            _service_pair(INSIGHT_ADDR, "insight-service", **per_service.get("insight", {})),
            *extra_changes,
        ]
    }


def _evaluate(plan, profile=PROFILE, digest=EXPECTED_DIGEST, tags=None, digests=None):
    return sut.evaluate_plan(
        plan,
        profile,
        digest,
        tags if tags is not None else _tags_map(),
        digests if digests is not None else _digests_map(),
    )


class SpecA913PlanTests(unittest.TestCase):
    def test_clean_restore_plan_passes(self):
        self.assertEqual(_evaluate(_restore_plan()), [])

    def test_chained_9_9_guard_accepts_the_same_valid_restore_plan(self):
        plan = _restore_plan()
        self.assertEqual(_evaluate(plan), [])
        self.assertEqual(
            guard_9_9.evaluate_plan(
                plan,
                PROFILE,
                _tags_map(),
                _digests_map(),
                EXPECTED_DIGEST,
            ),
            [],
        )

    def test_unset_after_min_replicas_is_accepted_as_zero(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["min_replicas"] = None
        self.assertEqual(_evaluate(plan), [])

    def test_missing_one_target_fails(self):
        plan = _restore_plan()
        plan["resource_changes"].pop()
        errors = _evaluate(plan)
        self.assertTrue(any("scope" in e or "expected" in e for e in errors), errors)

    def test_unexpected_gateway_fails(self):
        extra = _rc(
            GATEWAY_ADDR,
            before=_side("insight-service", min_replicas=0),
            after=_side("insight-service", min_replicas=1),
        )
        errors = _evaluate(_restore_plan(extra_changes=(extra,)))
        self.assertTrue(any("unexpected" in e or "scope" in e for e in errors), errors)

    def test_unexpected_job_fails(self):
        extra = {
            "address": JOB_ADDR,
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        errors = _evaluate(_restore_plan(extra_changes=(extra,)))
        self.assertTrue(any("unexpected" in e or "scope" in e for e in errors), errors)

    def test_unexpected_secret_fails(self):
        extra = {
            "address": "azurerm_key_vault_secret.demo",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        errors = _evaluate(_restore_plan(extra_changes=(extra,)))
        self.assertTrue(any("unexpected" in e or "scope" in e for e in errors), errors)

    def test_noop_fourth_resource_is_ignored(self):
        extra = {
            "address": GATEWAY_ADDR,
            "change": {"actions": ["no-op"], "before": {}, "after": {}},
        }
        self.assertEqual(_evaluate(_restore_plan(extra_changes=(extra,))), [])

    def test_empty_actions_extra_record_fails(self):
        extra = {
            "address": GATEWAY_ADDR,
            "change": {"actions": [], "before": {}, "after": {}},
        }
        errors = _evaluate(_restore_plan(extra_changes=(extra,)))
        self.assertTrue(any("no-op" in e or "actions" in e or "plan" in e for e in errors), errors)

    def test_noop_with_null_before_fails(self):
        extra = {
            "address": GATEWAY_ADDR,
            "change": {"actions": ["no-op"], "before": None, "after": {}},
        }
        errors = _evaluate(_restore_plan(extra_changes=(extra,)))
        self.assertTrue(any("no-op" in e or "before" in e or "plan" in e for e in errors), errors)

    def test_noop_with_null_after_fails(self):
        extra = {
            "address": GATEWAY_ADDR,
            "change": {"actions": ["no-op"], "before": {}, "after": None},
        }
        errors = _evaluate(_restore_plan(extra_changes=(extra,)))
        self.assertTrue(any("no-op" in e or "after" in e or "plan" in e for e in errors), errors)

    def test_duplicate_portfolio_address_fails(self):
        plan = _restore_plan()
        plan["resource_changes"].append(copy.deepcopy(plan["resource_changes"][0]))
        errors = _evaluate(plan)
        self.assertTrue(any("duplicate" in e for e in errors), errors)

    def test_duplicate_market_data_address_fails(self):
        plan = _restore_plan()
        plan["resource_changes"].append(copy.deepcopy(plan["resource_changes"][1]))
        errors = _evaluate(plan)
        self.assertTrue(any("duplicate" in e for e in errors), errors)

    def test_duplicate_insight_address_fails(self):
        plan = _restore_plan()
        plan["resource_changes"].append(copy.deepcopy(plan["resource_changes"][2]))
        errors = _evaluate(plan)
        self.assertTrue(any("duplicate" in e for e in errors), errors)

    def test_non_dictionary_resource_entry_fails(self):
        plan = _restore_plan()
        plan["resource_changes"].append("not-a-resource")
        errors = _evaluate(plan)
        self.assertTrue(any("plan" in e or "resource" in e or "malformed" in e for e in errors), errors)

    def test_malformed_change_payloads_fail_closed(self):
        cases = {
            "missing_change": {"address": GATEWAY_ADDR},
            "string_change": {"address": GATEWAY_ADDR, "change": "nope"},
            "null_change": {"address": GATEWAY_ADDR, "change": None},
            "list_change": {"address": GATEWAY_ADDR, "change": []},
            "missing_actions": {"address": GATEWAY_ADDR, "change": {"before": {}, "after": {}}},
            "string_actions": {
                "address": GATEWAY_ADDR,
                "change": {"actions": "update", "before": {}, "after": {}},
            },
            "null_actions": {
                "address": GATEWAY_ADDR,
                "change": {"actions": None, "before": {}, "after": {}},
            },
            "int_actions": {
                "address": GATEWAY_ADDR,
                "change": {"actions": 1, "before": {}, "after": {}},
            },
            "missing_before": {
                "address": GATEWAY_ADDR,
                "change": {"actions": ["no-op"], "after": {}},
            },
            "missing_after": {
                "address": GATEWAY_ADDR,
                "change": {"actions": ["no-op"], "before": {}},
            },
            "string_before": {
                "address": GATEWAY_ADDR,
                "change": {"actions": ["no-op"], "before": "x", "after": {}},
            },
            "string_after": {
                "address": GATEWAY_ADDR,
                "change": {"actions": ["no-op"], "before": {}, "after": "x"},
            },
            "empty_actions": {
                "address": GATEWAY_ADDR,
                "change": {"actions": [], "before": {}, "after": {}},
            },
            "noop_null_before": {
                "address": GATEWAY_ADDR,
                "change": {"actions": ["no-op"], "before": None, "after": {}},
            },
            "noop_null_after": {
                "address": GATEWAY_ADDR,
                "change": {"actions": ["no-op"], "before": {}, "after": None},
            },
        }
        for name, extra in cases.items():
            with self.subTest(name=name):
                errors = _evaluate(_restore_plan(extra_changes=(extra,)))
                self.assertTrue(
                    any("change" in e or "plan" in e or "actions" in e for e in errors),
                    errors,
                )

    def test_replace_action_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["actions"] = ["create", "delete"]
        errors = _evaluate(plan)
        self.assertTrue(any("update" in e or "action" in e for e in errors), errors)

    def test_only_two_services_scaled_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][2]["change"]["after"]["template"][0]["min_replicas"] = 1
        errors = _evaluate(plan)
        self.assertTrue(any("min_replicas" in e for e in errors), errors)

    def test_before_scale_not_one_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["before"]["template"][0]["min_replicas"] = 0
        errors = _evaluate(plan)
        self.assertTrue(any("min_replicas" in e for e in errors), errors)

    def test_after_scale_still_one_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][1]["change"]["after"]["template"][0]["min_replicas"] = 1
        errors = _evaluate(plan)
        self.assertTrue(any("min_replicas" in e for e in errors), errors)

    def test_after_scale_greater_than_zero_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["min_replicas"] = 2
        errors = _evaluate(plan)
        self.assertTrue(any("min_replicas" in e for e in errors), errors)

    def test_enforcement_override_present_before_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["before"]["template"][0]["container"][0]["env"].append(
            {"name": OVERRIDE_NAMES[0], "value": "false"}
        )
        errors = _evaluate(plan)
        self.assertTrue(any("override" in e for e in errors), errors)

    def test_enforcement_override_present_after_even_true_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][1]["change"]["after"]["template"][0]["container"][0]["env"].append(
            {"name": OVERRIDE_NAMES[1], "value": "true"}
        )
        errors = _evaluate(plan)
        self.assertTrue(any("override" in e for e in errors), errors)

    def test_wrong_portfolio_digest_fails(self):
        wrong = _image("portfolio-service", digest=OTHER_DIGEST)
        errors = _evaluate(_restore_plan(portfolio={"image": wrong}))
        self.assertTrue(any("image" in e or "digest" in e for e in errors), errors)

    def test_wrong_market_data_digest_fails(self):
        wrong = _image("market-data-service", digest=WRONG_MARKET_DIGEST)
        errors = _evaluate(_restore_plan(market={"image": wrong}))
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_wrong_insight_digest_fails(self):
        wrong = _image("insight-service", digest=WRONG_INSIGHT_DIGEST)
        errors = _evaluate(_restore_plan(insight={"image": wrong}))
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_matching_market_data_digest_passes(self):
        image = _image("market-data-service", digest=MARKET_DIGEST)
        self.assertEqual(_evaluate(_restore_plan(market={"image": image})), [])

    def test_matching_insight_digest_passes(self):
        image = _image("insight-service", digest=INSIGHT_DIGEST)
        self.assertEqual(_evaluate(_restore_plan(insight={"image": image})), [])

    def test_wrong_registry_fails(self):
        evil = f"evil.example/portfolio-service@{EXPECTED_DIGEST}"
        errors = _evaluate(_restore_plan(portfolio={"image": evil}))
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_cross_service_tag_substitution_fails(self):
        errors = _evaluate(
            _restore_plan(market={"image": _image("portfolio-service", tag=MARKET_TAG)})
        )
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_wrong_service_version_fails(self):
        errors = _evaluate(_restore_plan(insight={"version": PORTFOLIO_TAG}))
        self.assertTrue(any("service_version" in e or "SERVICE_VERSION" in e for e in errors), errors)

    def test_demo_flag_missing_fails(self):
        plan = _restore_plan()
        env = plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"]
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"] = [
            entry for entry in env if entry.get("name") != "APP_DEMO_SEED_ON_STARTUP"
        ]
        errors = _evaluate(plan)
        self.assertTrue(any("demo" in e for e in errors), errors)

    def test_demo_flag_duplicated_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"].append(
            {"name": "APP_DEMO_SEED_ON_STARTUP", "value": "false"}
        )
        errors = _evaluate(plan)
        self.assertTrue(any("demo" in e or "duplicate" in e for e in errors), errors)

    def test_demo_flag_secret_referenced_fails(self):
        plan = _restore_plan()
        env = plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"]
        for entry in env:
            if entry.get("name") == "APP_DEMO_SEED_ON_STARTUP":
                entry.pop("value", None)
                entry["secret_name"] = "demo-seed"
        errors = _evaluate(plan)
        self.assertTrue(any("demo" in e for e in errors), errors)

    def test_demo_flag_not_literal_false_fails(self):
        plan = _restore_plan()
        env = plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"]
        for entry in env:
            if entry.get("name") == "APP_DEMO_TX_DIAGNOSTICS":
                entry["value"] = "true"
        errors = _evaluate(plan)
        self.assertTrue(any("diag" in e or "demo" in e for e in errors), errors)

    def test_ingress_mutation_fails(self):
        plan = _restore_plan()
        changed = copy.deepcopy(INTERNAL_INGRESS)
        changed[0]["external_enabled"] = True
        plan["resource_changes"][0]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e or "field" in e for e in errors), errors)

    def test_target_port_mutation_fails(self):
        plan = _restore_plan()
        changed = copy.deepcopy(INTERNAL_INGRESS)
        changed[0]["target_port"] = 80
        plan["resource_changes"][1]["change"]["after"]["ingress"] = changed
        errors = _evaluate(plan)
        self.assertTrue(any("ingress" in e or "field" in e or "port" in e for e in errors), errors)

    def test_max_replicas_mutation_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][2]["change"]["after"]["template"][0]["max_replicas"] = 5
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e or "replicas" in e for e in errors), errors)

    def test_cpu_memory_mutation_fails(self):
        plan = _restore_plan()
        container = plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]
        container["cpu"] = 1.0
        container["memory"] = "2Gi"
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e for e in errors), errors)

    def test_secret_binding_mutation_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["after"]["secret"] = [
            {"name": "other-secret", "value": SECRET}
        ]
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e or "secret" in e for e in errors), errors)

    def test_duplicate_environment_names_fail(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["env"].append(
            {"name": "SERVICE_VERSION", "value": PORTFOLIO_TAG}
        )
        errors = _evaluate(plan)
        self.assertTrue(any("duplicate" in e or "environment" in e for e in errors), errors)

    def test_unknown_field_mutation_fails(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["after"]["workload_profile_name"] = "Consumption"
        errors = _evaluate(plan)
        self.assertTrue(any("field" in e for e in errors), errors)

    def test_malformed_plan_structure_fails_closed(self):
        errors = _evaluate({"resource_changes": "nope"})
        self.assertTrue(errors)

    def test_invalid_digest_input_fails(self):
        errors = _evaluate(_restore_plan(), digest="sha256:abc")
        self.assertTrue(any("digest" in e or "input" in e for e in errors), errors)

    def test_noop_plan_under_9_13_fails(self):
        errors = _evaluate({"resource_changes": []})
        self.assertTrue(any("scope" in e or "expected" in e for e in errors), errors)

    def test_exact_transition_under_standard_fails(self):
        errors = _evaluate(_restore_plan(), profile="standard")
        self.assertTrue(errors)

    def test_exact_transition_under_9_12_enable_fails(self):
        errors = _evaluate(_restore_plan(), profile="spec-a-9.12-enable")
        self.assertTrue(errors)

    def test_errors_do_not_leak_secrets(self):
        plan = _restore_plan()
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["cpu"] = 1.0
        errors = _evaluate(plan)
        joined = "\n".join(errors)
        self.assertTrue(errors)
        self.assertNotIn(SECRET, joined)
        self.assertNotIn("never-print", joined)

    def test_parse_expected_image_tags_rejects_malformed_json(self):
        with self.assertRaises(ValueError):
            sut.parse_expected_image_tags("{not-json")

    def test_parse_expected_image_digests_rejects_malformed_json(self):
        with self.assertRaises(ValueError):
            sut.parse_expected_image_digests("{not-json")

    def test_custom_domain_profiles_are_known(self):
        self.assertIn("api-gateway-custom-domain-restore", sut.KNOWN_PROFILES)
        self.assertIn("api-gateway-custom-domain-remove", sut.KNOWN_PROFILES)


if __name__ == "__main__":
    unittest.main()
