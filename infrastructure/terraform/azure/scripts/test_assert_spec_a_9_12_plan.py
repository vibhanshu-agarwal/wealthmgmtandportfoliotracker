#!/usr/bin/env python3
"""Adversarial fixture tests for the Spec A checkpoint 9.12 plan guard."""

from __future__ import annotations

import contextlib
import copy
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_spec_a_9_12_plan as sut  # noqa: E402

EXPECTED_DIGEST = "sha256:" + "a1" * 32
OTHER_DIGEST = "sha256:" + "b2" * 32
EXPECTED_VERSION = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
OTHER_VERSION = "1" * 40
GATEWAY_VERSION = "18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17"
IMAGE = f"wealthprodacr.azurecr.io/portfolio-service@{EXPECTED_DIGEST}"
TARGET = "module.portfolio_service.azurerm_container_app.this"
SECRET = "never-print-this-secret-value"
INTERNAL_INGRESS = [{
    "external_enabled": False,
    "target_port": 8080,
    "transport": "auto",
    "traffic_weight": [{"percentage": 100, "latest_revision": True}],
}]


def _tags_map(**overrides) -> dict[str, str]:
    tags = {
        "api-gateway": GATEWAY_VERSION,
        "portfolio-service": EXPECTED_VERSION,
        "market-data-service": EXPECTED_VERSION,
        "insight-service": EXPECTED_VERSION,
    }
    tags.update(overrides)
    return tags


def _production_plain_env(demo=..., tx_diag=...):
    env = [
        {"name": "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT", "value": "grpc"},
        {"name": "MANAGEMENT_TRACING_EXPORT_ENABLED", "value": "true"},
        {"name": "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "value": "1.0"},
        {"name": "SERVICE_VERSION", "value": EXPECTED_VERSION},
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "DEPLOYMENT_ENVIRONMENT_NAME", "value": "prod"},
    ]
    if demo is not ...:
        env.append({"name": "APP_DEMO_SEED_ON_STARTUP", "value": demo})
    if tx_diag is not ...:
        env.append({"name": "APP_DEMO_TX_DIAGNOSTICS", "value": tx_diag})
    return env


def _production_secret_env():
    return [
        {"name": "INTERNAL_API_KEY", "secret_name": "internal-api-key"},
        {"name": "KAFKA_BOOTSTRAP_SERVERS", "secret_name": "kafka-bootstrap-servers"},
        {"name": "KAFKA_SASL_PASSWORD", "secret_name": "kafka-sasl-password"},
        {"name": "KAFKA_SASL_USERNAME", "secret_name": "kafka-sasl-username"},
        {"name": "REDIS_URL", "secret_name": "redis-url"},
        {"name": "SPRING_DATASOURCE_PASSWORD", "secret_name": "spring-datasource-password"},
        {"name": "SPRING_DATASOURCE_URL", "secret_name": "spring-datasource-url"},
        {"name": "SPRING_DATASOURCE_USERNAME", "secret_name": "spring-datasource-username"},
    ]


def _production_secrets():
    return [
        {"name": "internal-api-key", "value": SECRET},
        {"name": "kafka-bootstrap-servers", "value": SECRET},
        {"name": "kafka-sasl-password", "value": SECRET},
        {"name": "kafka-sasl-username", "value": SECRET},
        {"name": "redis-url", "value": SECRET},
        {"name": "spring-datasource-password", "value": SECRET},
        {"name": "spring-datasource-url", "value": SECRET},
        {"name": "spring-datasource-username", "value": SECRET},
    ]


def _production_shaped_side(demo=..., tx_diag=..., *, ingress=INTERNAL_INGRESS):
    env = _production_plain_env(demo, tx_diag) + _production_secret_env()
    return _side(
        demo,
        ingress=ingress,
        env=env,
        max_replicas=3,
        secret=_production_secrets(),
    )


def _env(demo=..., tx_diag=..., *, version=EXPECTED_VERSION, extra=()):
    result = [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "SERVICE_VERSION", "value": version},
        {"name": "SPRING_DATA_MONGODB_URI", "secret_name": "mongodb-uri"},
    ]
    if demo is not ...:
        result.append({"name": "APP_DEMO_SEED_ON_STARTUP", "value": demo})
    if tx_diag is not ...:
        result.append({"name": "APP_DEMO_TX_DIAGNOSTICS", "value": tx_diag})
    result.extend(copy.deepcopy(extra))
    return result


def _side(
    demo=...,
    tx_diag=...,
    *,
    image=IMAGE,
    version=EXPECTED_VERSION,
    min_replicas=1,
    max_replicas=1,
    ingress=None,
    cpu=0.5,
    memory="1Gi",
    env=None,
    secret=None,
):
    side = {
        "identity": [{"type": "UserAssigned", "identity_ids": ["/identities/portfolio"]}],
        "registry": [{"server": "wealthprodacr.azurecr.io", "identity": "/identities/acr"}],
        "secret": copy.deepcopy(secret if secret is not None else [{"name": "mongodb-uri", "value": SECRET}]),
        "template": [{
            "min_replicas": min_replicas,
            "max_replicas": max_replicas,
            "container": [{
                "name": "portfolio-service",
                "image": image,
                "cpu": cpu,
                "memory": memory,
                "env": _env(demo, tx_diag, version=version) if env is None else copy.deepcopy(env),
            }],
        }],
    }
    if ingress is None:
        side["ingress"] = copy.deepcopy(INTERNAL_INGRESS)
    elif ingress is not ...:
        side["ingress"] = copy.deepcopy(ingress)
    return side


def _target(actions=("update",), before=None, after=None):
    return {
        "address": TARGET,
        "type": "azurerm_container_app",
        "name": "this",
        "change": {"actions": list(actions), "before": before, "after": after},
    }


def _plan(*changes):
    return {"resource_changes": list(changes)}


def _transition(before_demo=..., after_demo="true", *, before_tx_diag=..., after_tx_diag=..., before=None, after=None, extras=()):
    before = _side(before_demo, before_tx_diag) if before is None else before
    after = _side(after_demo, after_tx_diag) if after is None else after
    return _plan(_target(before=before, after=after), *extras)


def _tx_diag_transition(before_tx=..., after_tx="true", *, before_demo="false", after_demo="false"):
    return _transition(
        before_demo,
        after_demo,
        before_tx_diag=before_tx,
        after_tx_diag=after_tx,
    )


def _evaluate(plan, profile="spec-a-9.12-enable", digest=EXPECTED_DIGEST, tags=None):
    return sut.evaluate_plan(plan, profile, digest, tags if tags is not None else _tags_map())


class SpecA912PlanTests(unittest.TestCase):
    def assertFails(self, plan, profile="spec-a-9.12-enable", **kwargs):
        self.assertTrue(_evaluate(plan, profile, **kwargs))

    def test_enable_absent_to_true_passes(self):
        self.assertEqual(_evaluate(_transition()), [])

    def test_enable_false_to_true_passes(self):
        self.assertEqual(_evaluate(_transition("false", "true")), [])

    def test_disable_true_to_false_passes(self):
        self.assertEqual(_evaluate(_transition("true", "false"), "spec-a-9.12-disable"), [])

    def test_tx_diag_enable_absent_to_true_passes(self):
        self.assertEqual(
            _evaluate(_tx_diag_transition(), "spec-a-9.12-tx-diag-enable"),
            [],
        )

    def test_tx_diag_enable_false_to_true_passes(self):
        self.assertEqual(
            _evaluate(_tx_diag_transition("false", "true"), "spec-a-9.12-tx-diag-enable"),
            [],
        )

    def test_tx_diag_disable_true_to_false_passes(self):
        self.assertEqual(
            _evaluate(_tx_diag_transition("true", "false"), "spec-a-9.12-tx-diag-disable"),
            [],
        )

    def test_tx_diag_enable_rejects_demo_seed_change(self):
        self.assertFails(
            _transition("false", "true", before_tx_diag="false", after_tx_diag="true"),
            "spec-a-9.12-tx-diag-enable",
        )

    def test_demo_enable_rejects_tx_diag_change(self):
        self.assertFails(
            _transition("false", "true", before_tx_diag="false", after_tx_diag="true"),
            "spec-a-9.12-enable",
        )

    def test_non_scoped_rejects_tx_diag_change(self):
        plan = _tx_diag_transition("false", "true")
        self.assertFails(plan, "standard", digest="")

    def test_known_non_scoped_profiles_allow_unchanged_demo(self):
        profiles = (
            "standard",
            "spec-a-9.9-enable",
            "spec-a-9.9-abort",
            "spec-a-9.11-enable",
            "spec-a-9.11-abort",
        )
        plan = _transition("false", "false", after=_side("false", cpu=1.0))
        for profile in profiles:
            with self.subTest(profile=profile):
                self.assertEqual(_evaluate(plan, profile, digest=""), [])

    def test_non_scoped_profiles_reject_demo_add_remove_duplicate_or_change(self):
        plans = (
            _transition(..., "false"),
            _transition("false", ...),
            _transition("false", "true"),
            _transition("false", "false", after=_side(env=_env("false", extra=[
                {"name": "APP_DEMO_SEED_ON_STARTUP", "value": "false"}
            ]))),
        )
        for plan in plans:
            with self.subTest(plan=plan):
                self.assertFails(plan, "standard", digest="")

    def test_non_scoped_rejects_demo_secret_reference_change(self):
        """Non-9.12 profiles must catch secret_name-only demo entry mutations.

        Comparing only literal ``value`` misses ``secret_name: demo-a → demo-b``
        (both sides yield None for value).
        """
        before = _side(env=[
            {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
            {"name": "SERVICE_VERSION", "value": EXPECTED_VERSION},
            {"name": "SPRING_DATA_MONGODB_URI", "secret_name": "mongodb-uri"},
            {"name": "APP_DEMO_SEED_ON_STARTUP", "secret_name": "demo-a"},
        ])
        after = copy.deepcopy(before)
        after["template"][0]["container"][0]["env"][3]["secret_name"] = "demo-b"
        errors = _evaluate(
            _plan(_target(before=before, after=after)),
            "standard",
            digest="",
        )
        self.assertTrue(errors, "secret_name-only demo change must fail under standard")
        self.assertTrue(
            any("demo-guard" in error for error in errors),
            errors,
        )
        # Same secret reference with an unrelated non-demo field change still passes.
        unchanged = copy.deepcopy(before)
        unchanged["template"][0]["container"][0]["cpu"] = 1.0
        self.assertEqual(
            _evaluate(
                _plan(_target(before=before, after=unchanged)),
                "standard",
                digest="",
            ),
            [],
        )

    def test_service_version_pin_is_checked_independently_on_both_sides(self):
        self.assertFails(_transition(before=_side(..., version=OTHER_VERSION)))
        self.assertFails(_transition(after=_side("true", version=OTHER_VERSION)))
    def test_enable_rejects_bad_directions_values_and_counts(self):
        plans = (
            _transition("true", "false"),
            _transition("true", "true"),
            _transition("false", ...),
            _transition("false", "TRUE"),
            _transition("false", True),
            _transition("false", "true", after=_side(env=_env("true", extra=[
                {"name": "APP_DEMO_SEED_ON_STARTUP", "value": "true"}
            ]))),
            _transition("false", "true", before=_side(env=_env("false", extra=[
                {"name": "APP_DEMO_SEED_ON_STARTUP", "value": "false"}
            ]))),
        )
        for plan in plans:
            with self.subTest(plan=plan):
                self.assertFails(plan)

    def test_disable_rejects_bad_directions_values_and_counts(self):
        plans = (
            _transition("false", "true"),
            _transition("false", "false"),
            _transition(..., "false"),
            _transition("true", ...),
            _transition("true", "false", after=_side(env=_env("false", extra=[
                {"name": "APP_DEMO_SEED_ON_STARTUP", "value": "false"}
            ]))),
        )
        for plan in plans:
            with self.subTest(plan=plan):
                self.assertFails(plan, "spec-a-9.12-disable")

    def test_scoped_profile_rejects_non_update_actions(self):
        for actions in (("create",), ("delete",), ("delete", "create"), ("no-op",)):
            plan = _transition()
            plan["resource_changes"][0]["change"]["actions"] = list(actions)
            with self.subTest(actions=actions):
                self.assertFails(plan)

    def test_scoped_profile_rejects_missing_target_and_extra_change(self):
        self.assertFails(_plan())
        extra = {
            "address": "azurerm_container_app_job.market_data_refresh",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        self.assertFails(_transition(extras=(extra,)))

    def test_scoped_profile_ignores_extra_noop(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["no-op"], "before": {}, "after": {}},
        }
        self.assertEqual(_evaluate(_transition(extras=(extra,))), [])

    def test_min_replicas_must_be_one_on_both_sides(self):
        self.assertFails(_transition(before=_side(..., min_replicas=0)))
        self.assertFails(_transition(after=_side("true", min_replicas=2)))

    def test_ingress_internal_shape_must_remain_unchanged(self):
        stable = copy.deepcopy(INTERNAL_INGRESS)
        self.assertEqual(
            _evaluate(_transition(before=_side(..., ingress=stable), after=_side("true", ingress=stable))),
            [],
        )

    def test_external_ingress_fails(self):
        external = [{"external_enabled": True, "target_port": 8080, "traffic_weight": [{"percentage": 100, "latest_revision": True}]}]
        self.assertFails(_transition(after=_side("true", ingress=external)))
        self.assertFails(_transition(before=_side(..., ingress=external), after=_side("true", ingress=INTERNAL_INGRESS)))

    def test_ingress_removal_fails(self):
        removed = _side("true")
        removed.pop("ingress", None)
        self.assertFails(_transition(before=_side(..., ingress=INTERNAL_INGRESS), after=removed))
        self.assertFails(_transition(before=_side(..., ingress=INTERNAL_INGRESS), after=_side("true", ingress=[])))

    def test_target_port_change_fails(self):
        changed = copy.deepcopy(INTERNAL_INGRESS)
        changed[0]["target_port"] = 80
        self.assertFails(_transition(after=_side("true", ingress=changed)))

    def test_traffic_weight_change_fails(self):
        changed = copy.deepcopy(INTERNAL_INGRESS)
        changed[0]["traffic_weight"] = [{"percentage": 50, "latest_revision": True}]
        self.assertFails(_transition(after=_side("true", ingress=changed)))

    def test_realistic_enable_fixture_tolerates_env_reindexing(self):
        before = _production_shaped_side(...)
        after = _production_shaped_side("true")
        # Terraform provider diffs often reindex env across plain and secret entries.
        after["template"][0]["container"][0]["env"] = list(reversed(after["template"][0]["container"][0]["env"]))
        for entry in after["template"][0]["container"][0]["env"]:
            if "secret_name" in entry:
                entry["value"] = None
        self.assertEqual(_evaluate(_plan(_target(before=before, after=after))), [])

    def test_secret_reference_substitution_fails(self):
        after = _production_shaped_side("true")
        for entry in after["template"][0]["container"][0]["env"]:
            if entry.get("name") == "SPRING_DATASOURCE_URL":
                entry["secret_name"] = "other-secret"
        self.assertFails(_transition(before=_production_shaped_side(...), after=after))

    def test_duplicate_env_names_fail(self):
        duplicate = _production_shaped_side("true")
        duplicate["template"][0]["container"][0]["env"].append(
            {"name": "SERVICE_VERSION", "value": EXPECTED_VERSION}
        )
        self.assertFails(_transition(after=duplicate))

    def test_transport_change_fails(self):
        changed = copy.deepcopy(INTERNAL_INGRESS)
        changed[0]["transport"] = "tcp"
        self.assertFails(_transition(after=_side("true", ingress=changed)))

    def test_missing_transport_fails(self):
        missing = copy.deepcopy(INTERNAL_INGRESS)
        del missing[0]["transport"]
        self.assertFails(_transition(after=_side("true", ingress=missing)))
        self.assertFails(_transition(before=_side(..., ingress=missing), after=_side("true", ingress=INTERNAL_INGRESS)))

    def test_unrelated_none_to_empty_string_transition_fails(self):
        before = _production_shaped_side(...)
        after = _production_shaped_side("true")
        before["dapr"] = None
        after["dapr"] = ""
        self.assertFails(_transition(before=before, after=after))

    def test_demo_plain_and_secret_binding_fails(self):
        hybrid = _production_shaped_side("true")
        for entry in hybrid["template"][0]["container"][0]["env"]:
            if entry.get("name") == "APP_DEMO_SEED_ON_STARTUP":
                entry["secret_name"] = "demo-secret"
        self.assertFails(_transition(before=_production_shaped_side(...), after=hybrid))

    def test_unlisted_container_field_change_fails(self):
        mutated = _production_shaped_side("true")
        mutated["template"][0]["container"][0]["command"] = ["/bin/sh"]
        self.assertFails(_transition(before=_production_shaped_side(...), after=mutated))

    def test_genuine_non_demo_plain_env_mutation_fails(self):
        mutated = _production_shaped_side("true")
        for entry in mutated["template"][0]["container"][0]["env"]:
            if entry.get("name") == "DEPLOYMENT_ENVIRONMENT_NAME":
                entry["value"] = "staging"
        self.assertFails(_transition(before=_production_shaped_side(...), after=mutated))

    def test_other_resource_fields_cannot_change(self):
        mutations = {
            "max_replicas": _side("true", max_replicas=2),
            "cpu": _side("true", cpu=1.0),
            "memory": _side("true", memory="2Gi"),
            "identity": _side("true"),
            "registry": _side("true"),
            "secret": _side("true"),
            "secret_ref": _side("true"),
            "non_demo_env": _side("true"),
        }
        mutations["identity"]["identity"][0]["identity_ids"] = ["/identities/other"]
        mutations["registry"]["registry"][0]["identity"] = "/identities/other"
        mutations["secret"]["secret"][0]["value"] = "changed-secret"
        mutations["secret_ref"]["template"][0]["container"][0]["env"][2]["secret_name"] = "other"
        mutations["non_demo_env"]["template"][0]["container"][0]["env"][0]["value"] = "other"
        for field, after in mutations.items():
            with self.subTest(field=field):
                self.assertFails(_transition(after=after))

    def test_image_digest_pin_is_checked_independently_on_both_sides(self):
        wrong = f"wealthprodacr.azurecr.io/portfolio-service@{OTHER_DIGEST}"
        self.assertFails(_transition(before=_side(..., image=wrong)))
        self.assertFails(_transition(after=_side("true", image=wrong)))
        tag_image = f"wealthprodacr.azurecr.io/portfolio-service:{EXPECTED_VERSION}"
        self.assertFails(_transition(before=_side(..., image=tag_image), after=_side("true", image=tag_image)))

    def test_service_version_pin_uses_portfolio_service_map_entry(self):
        self.assertFails(
            _transition(),
            tags=_tags_map(**{"portfolio-service": OTHER_VERSION}),
        )

    def test_correct_gateway_tag_with_wrong_portfolio_plan_identity_fails(self):
        wrong_side = _side("true", version=GATEWAY_VERSION)
        self.assertFails(_transition(after=wrong_side))

    def test_cross_guard_adversarial_changes_fail_scope_or_sole_delta(self):
        runner = {
            "address": "azurerm_container_app_job.market_data_refresh",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        self.assertFails(_transition(extras=(runner,)))
        changed_99_field = _side("true")
        changed_99_field["template"][0]["container"][0]["env"].append(
            {"name": "APP_SUPPORTED_ASSET_INTEGRITY_MODE", "value": "repair"}
        )
        self.assertFails(_transition(after=changed_99_field))

    def test_unknown_profile_fails_closed(self):
        self.assertFails(_transition(), "spec-a-unknown")

    def test_scoped_profiles_reject_invalid_digest_or_version_inputs(self):
        bad_digests = ("", "a" * 64, "sha256:abc", "sha256:" + "g" * 64)
        bad_versions = ("", "abc", "g" * 40, "a" * 39, "a" * 41)
        for digest in bad_digests:
            with self.subTest(digest=digest):
                self.assertFails(_transition(), digest=digest)
        for version in bad_versions:
            with self.subTest(version=version):
                self.assertFails(_transition(), tags=_tags_map(**{"portfolio-service": version}))

    def test_errors_do_not_leak_secrets_or_plan_fragments(self):
        after = _side("true", cpu=1.0)
        plan = _transition(after=after)
        errors = _evaluate(plan)
        joined = "\n".join(errors)
        self.assertTrue(errors)
        self.assertNotIn(SECRET, joined)
        self.assertNotIn("never-print", joined)
        self.assertNotIn(str(after), joined)

        with tempfile.TemporaryDirectory() as tmpdir:
            plan_path = os.path.join(tmpdir, "plan.json")
            with open(plan_path, "w", encoding="utf-8") as plan_file:
                json.dump(plan, plan_file)
            argv = [
                "assert_spec_a_9_12_plan.py",
                plan_path,
                "--profile",
                "spec-a-9.12-enable",
                "--expected-image-digest",
                EXPECTED_DIGEST,
                "--expected-image-tags-json",
                json.dumps(_tags_map()),
            ]
            stdout_buf = io.StringIO()
            stderr_buf = io.StringIO()
            with patch.object(sys, "argv", argv):
                with contextlib.redirect_stdout(stdout_buf), contextlib.redirect_stderr(stderr_buf):
                    exit_code = sut.main()
            self.assertEqual(exit_code, 1)
            combined = stdout_buf.getvalue() + stderr_buf.getvalue()
            self.assertNotIn(SECRET, combined)
            self.assertNotIn("never-print", combined)
            self.assertNotIn(str(after), combined)

    def test_env_order_is_canonical_but_duplicate_names_fail(self):
        after = _side("true")
        after["template"][0]["container"][0]["env"].reverse()
        self.assertEqual(_evaluate(_transition(after=after)), [])
        duplicate = _side("true")
        duplicate["template"][0]["container"][0]["env"].append(
            {"name": "SERVICE_VERSION", "value": EXPECTED_VERSION}
        )
        self.assertFails(_transition(after=duplicate))

    # -- 9.13 restore-scale: portfolio 1 -> 0 with both demo flags remaining false -----

    def _9_13_portfolio_plan(self, *, before=None, after=None, extras=()):
        before = _side("false", "false", min_replicas=1) if before is None else before
        after = _side("false", "false", min_replicas=0) if after is None else after
        return _plan(_target(before=before, after=after), *extras)

    def test_9_13_portfolio_scale_restore_passes(self):
        self.assertEqual(
            _evaluate(self._9_13_portfolio_plan(), "spec-a-9.13-restore-scale"),
            [],
        )

    def test_9_13_allows_peer_catalog_app_updates(self):
        extras = (
            {
                "address": "module.market_data_service.azurerm_container_app.this",
                "change": {"actions": ["update"], "before": {}, "after": {}},
            },
            {
                "address": "module.insight_service.azurerm_container_app.this",
                "change": {"actions": ["update"], "before": {}, "after": {}},
            },
        )
        self.assertEqual(
            _evaluate(self._9_13_portfolio_plan(extras=extras), "spec-a-9.13-restore-scale"),
            [],
        )

    def test_9_13_rejects_demo_flag_not_literal_false(self):
        errors = _evaluate(
            self._9_13_portfolio_plan(after=_side("true", "false", min_replicas=0)),
            "spec-a-9.13-restore-scale",
        )
        self.assertTrue(any("demo" in e for e in errors), errors)

    def test_9_13_rejects_diagnostics_flag_not_literal_false(self):
        errors = _evaluate(
            self._9_13_portfolio_plan(after=_side("false", "true", min_replicas=0)),
            "spec-a-9.13-restore-scale",
        )
        self.assertTrue(any("tx-diag" in e or "diag" in e for e in errors), errors)

    def test_9_13_rejects_min_replicas_staying_one(self):
        errors = _evaluate(
            self._9_13_portfolio_plan(after=_side("false", "false", min_replicas=1)),
            "spec-a-9.13-restore-scale",
        )
        self.assertTrue(any("replicas" in e for e in errors), errors)

    def test_9_13_rejects_non_scale_portfolio_field_change(self):
        errors = _evaluate(
            self._9_13_portfolio_plan(after=_side("false", "false", min_replicas=0, cpu=1.0)),
            "spec-a-9.13-restore-scale",
        )
        self.assertTrue(any("field" in e or "cpu" in e for e in errors), errors)

    def test_9_12_enable_cannot_borrow_9_13_scale_exception(self):
        errors = _evaluate(self._9_13_portfolio_plan(), "spec-a-9.12-enable")
        self.assertTrue(errors)
        self.assertTrue(any("replicas" in e or "demo" in e or "scope" in e for e in errors), errors)


if __name__ == "__main__":
    unittest.main()
