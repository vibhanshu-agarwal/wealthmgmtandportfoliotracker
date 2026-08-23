#!/usr/bin/env python3
"""Fixture tests for assert_spec_a_9_9_plan.py (Spec A checkpoint 9.9)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import assert_spec_a_9_9_plan as sut  # noqa: E402

EXPECTED_TAG = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
WRONG_TAG = "deadbeef" * 5
IMAGE = f"wealthprodacr.azurecr.io/portfolio-service:{EXPECTED_TAG}"


def _side(*, min_replicas, overrides_present, image=IMAGE, service_version=EXPECTED_TAG):
    env = [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod,azure"},
        {"name": "SERVICE_VERSION", "value": service_version},
    ]
    if overrides_present:
        env.append({"name": "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS", "value": "false"})
        env.append({"name": "APP_CATALOG_ENFORCE_HOLDING_INVARIANT", "value": "false"})
    return {
        "template": [
            {
                "min_replicas": min_replicas,
                "container": [{"image": image, "env": env}],
            }
        ]
    }


def _service_rc(address, *, actions, before, after):
    return {"address": address, "change": {"actions": list(actions), "before": before, "after": after}}


def _enable_plan(*, extra_changes=()):
    rcs = [
        _service_rc(
            addr,
            actions=["update"],
            before=_side(min_replicas=0, overrides_present=True),
            after=_side(min_replicas=1, overrides_present=False),
        )
        for addr in sut.SERVICE_ADDRESSES
    ]
    rcs.extend(extra_changes)
    return {"resource_changes": rcs}


def _abort_plan():
    return {
        "resource_changes": [
            _service_rc(
                addr,
                actions=["update"],
                before=_side(min_replicas=1, overrides_present=False),
                after=_side(min_replicas=0, overrides_present=True),
            )
            for addr in sut.SERVICE_ADDRESSES
        ]
    }


def _evaluate(plan, profile, expected_tag=EXPECTED_TAG):
    return sut.evaluate_plan(plan, profile, expected_tag)


class SpecA99PlanTests(unittest.TestCase):
    def test_clean_enable_plan_passes(self):
        self.assertEqual(_evaluate(_enable_plan(), "enable"), [])

    def test_clean_abort_plan_passes(self):
        self.assertEqual(_evaluate(_abort_plan(), "abort"), [])

    def test_missing_service_fails(self):
        plan = _enable_plan()
        plan["resource_changes"].pop()
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("expected a change" in e for e in errors))

    def test_fourth_resource_changed_fails(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        errors = _evaluate(_enable_plan(extra_changes=[extra]), "enable")
        self.assertTrue(any("unexpected non-no-op" in e for e in errors))

    def test_no_op_fourth_resource_is_ignored(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["no-op"], "before": {}, "after": {}},
        }
        self.assertEqual(_evaluate(_enable_plan(extra_changes=[extra]), "enable"), [])

    def test_replace_action_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["actions"] = ["create", "delete"]
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("expected exactly ['update']" in e for e in errors))

    def test_min_replicas_not_raised_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=0, overrides_present=False)
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("min_replicas" in e for e in errors))

    def test_override_still_present_after_enable_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=1, overrides_present=True)
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("override-after" in e for e in errors))

    def test_override_set_true_instead_of_removed_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False)
        after["template"][0]["container"][0]["env"].append(
            {"name": "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS", "value": "true"}
        )
        plan["resource_changes"][0]["change"]["after"] = after
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("override-after" in e for e in errors))

    def test_image_change_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False, image=f"wealthprodacr.azurecr.io/portfolio-service:{WRONG_TAG}")
        plan["resource_changes"][0]["change"]["after"] = after
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("[image]" in e for e in errors))

    def test_service_version_change_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False, service_version=WRONG_TAG)
        plan["resource_changes"][0]["change"]["after"] = after
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("service_version" in e for e in errors))

    def test_abort_override_present_before_fails(self):
        plan = _abort_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(min_replicas=1, overrides_present=True)
        errors = _evaluate(plan, "abort")
        self.assertTrue(any("override-before" in e for e in errors))

    def test_abort_min_replicas_not_restored_fails(self):
        plan = _abort_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=1, overrides_present=True)
        errors = _evaluate(plan, "abort")
        self.assertTrue(any("min_replicas" in e for e in errors))

    def test_unset_min_replicas_before_treated_as_zero_for_enable(self):
        plan = _enable_plan()
        before = _side(min_replicas=None, overrides_present=True)
        plan["resource_changes"][0]["change"]["before"] = before
        self.assertEqual(_evaluate(plan, "enable"), [])

    # -- identity pinned to expected_image_tag, not merely to itself ------------------

    def test_wrong_identity_unchanged_on_both_sides_fails(self):
        """Adversarial fixture: before and after both carry the SAME wrong tag, so a
        before==after-only check would wrongly pass. This is the real gap: "unchanged"
        and "correct" are different claims, and only pinning to the actual
        deployed_image_tag catches a resource that was never on the right image."""
        wrong_image = f"wealthprodacr.azurecr.io/portfolio-service:{WRONG_TAG}"
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(
            min_replicas=0, overrides_present=True, image=wrong_image, service_version=WRONG_TAG
        )
        plan["resource_changes"][0]["change"]["after"] = _side(
            min_replicas=1, overrides_present=False, image=wrong_image, service_version=WRONG_TAG
        )
        errors = _evaluate(plan, "enable")
        self.assertTrue(errors, "wrong-but-unchanged identity must be rejected, not accepted as []")
        self.assertTrue(any("[image]" in e for e in errors))
        self.assertTrue(any("service_version" in e for e in errors))

    def test_wrong_identity_before_only_fails(self):
        wrong_image = f"wealthprodacr.azurecr.io/portfolio-service:{WRONG_TAG}"
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(
            min_replicas=0, overrides_present=True, image=wrong_image, service_version=WRONG_TAG
        )
        errors = _evaluate(plan, "enable")
        self.assertTrue(any("[image]" in e and "before" in e for e in errors))

    def test_different_expected_tag_is_honoured(self):
        # A plan correctly pinned to a DIFFERENT expected tag than EXPECTED_TAG must
        # still pass — proves the check uses the passed-in value, not a hardcoded one.
        other_tag = "1111111111111111111111111111111111abcd"
        other_image = f"wealthprodacr.azurecr.io/portfolio-service:{other_tag}"
        plan = {
            "resource_changes": [
                _service_rc(
                    addr,
                    actions=["update"],
                    before=_side(
                        min_replicas=0, overrides_present=True, image=other_image, service_version=other_tag
                    ),
                    after=_side(
                        min_replicas=1, overrides_present=False, image=other_image, service_version=other_tag
                    ),
                )
                for addr in sut.SERVICE_ADDRESSES
            ]
        }
        self.assertEqual(_evaluate(plan, "enable", expected_tag=other_tag), [])


if __name__ == "__main__":
    unittest.main()
