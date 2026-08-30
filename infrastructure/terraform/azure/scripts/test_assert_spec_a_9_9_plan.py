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
OTHER_GATEWAY_TAG = "18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17"
ACR = sut.ACR_LOGIN_SERVER
PORTFOLIO_DIGEST = "sha256:" + "a1" * 32
MAP_PORTFOLIO_DIGEST = "sha256:" + "b8" * 32
MARKET_DIGEST = "sha256:" + "c3" * 32
INSIGHT_DIGEST = "sha256:" + "d4" * 32
GATEWAY_DIGEST = "sha256:" + "e5" * 32


def _tags_map(**overrides) -> dict[str, str]:
    tags = {
        "api-gateway": OTHER_GATEWAY_TAG,
        "portfolio-service": EXPECTED_TAG,
        "market-data-service": EXPECTED_TAG,
        "insight-service": EXPECTED_TAG,
    }
    tags.update(overrides)
    return tags


def _digests_map(**overrides) -> dict[str, str]:
    digests = {
        "api-gateway": GATEWAY_DIGEST,
        "portfolio-service": MAP_PORTFOLIO_DIGEST,
        "market-data-service": MARKET_DIGEST,
        "insight-service": INSIGHT_DIGEST,
    }
    digests.update(overrides)
    return digests


def _expected_image(address: str, tag: str = EXPECTED_TAG) -> str:
    return f"{ACR}/{sut.SERVICE_IMAGE_REPOSITORIES[address]}:{tag}"


# Convenience alias — tests that only need one service can use this directly.
PORTFOLIO_IMAGE = _expected_image(sut.SERVICE_ADDRESSES[0])
PORTFOLIO_DIGEST_IMAGE = f"{ACR}/portfolio-service@{PORTFOLIO_DIGEST}"


def _restore_image(address: str) -> str:
    if address == sut.SERVICE_ADDRESSES[0]:
        return PORTFOLIO_DIGEST_IMAGE
    return _expected_image(address)


def _side(*, min_replicas, overrides_present, image=PORTFOLIO_IMAGE, service_version=EXPECTED_TAG):
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
            before=_side(min_replicas=0, overrides_present=True, image=_expected_image(addr)),
            after=_side(min_replicas=1, overrides_present=False, image=_expected_image(addr)),
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
                before=_side(min_replicas=1, overrides_present=False, image=_expected_image(addr)),
                after=_side(min_replicas=0, overrides_present=True, image=_expected_image(addr)),
            )
            for addr in sut.SERVICE_ADDRESSES
        ]
    }


def _evaluate(plan, profile, tags=None, digests=None, portfolio_digest=None):
    return sut.evaluate_plan(
        plan,
        profile,
        tags if tags is not None else _tags_map(),
        digests if digests is not None else _digests_map(),
        portfolio_digest if portfolio_digest is not None else PORTFOLIO_DIGEST,
    )


class SpecA99PlanTests(unittest.TestCase):
    def test_clean_enable_plan_passes(self):
        self.assertEqual(_evaluate(_enable_plan(), "spec-a-9.9-enable"), [])

    def test_clean_abort_plan_passes(self):
        self.assertEqual(_evaluate(_abort_plan(), "spec-a-9.9-abort"), [])

    def test_missing_service_fails(self):
        plan = _enable_plan()
        plan["resource_changes"].pop()
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("expected a change" in e for e in errors))

    def test_fourth_resource_changed_fails(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["update"], "before": {}, "after": {}},
        }
        errors = _evaluate(_enable_plan(extra_changes=[extra]), "spec-a-9.9-enable")
        self.assertTrue(any("unexpected non-no-op" in e for e in errors))

    def test_no_op_fourth_resource_is_ignored(self):
        extra = {
            "address": "module.api_gateway.azurerm_container_app.this",
            "change": {"actions": ["no-op"], "before": {}, "after": {}},
        }
        self.assertEqual(_evaluate(_enable_plan(extra_changes=[extra]), "spec-a-9.9-enable"), [])

    def test_replace_action_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["actions"] = ["create", "delete"]
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("expected exactly ['update']" in e for e in errors))

    def test_min_replicas_not_raised_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=0, overrides_present=False)
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("min_replicas" in e for e in errors))

    def test_override_still_present_after_enable_fails(self):
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=1, overrides_present=True)
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("override-after" in e for e in errors))

    def test_override_set_true_instead_of_removed_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False)
        after["template"][0]["container"][0]["env"].append(
            {"name": "APP_CATALOG_REJECT_UNSUPPORTED_EVENTS", "value": "true"}
        )
        plan["resource_changes"][0]["change"]["after"] = after
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("override-after" in e for e in errors))

    def test_image_change_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False, image=_expected_image(sut.SERVICE_ADDRESSES[0], WRONG_TAG))
        plan["resource_changes"][0]["change"]["after"] = after
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("[image]" in e for e in errors))

    def test_service_version_change_fails(self):
        plan = _enable_plan()
        after = _side(min_replicas=1, overrides_present=False, service_version=WRONG_TAG)
        plan["resource_changes"][0]["change"]["after"] = after
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("service_version" in e for e in errors))

    def test_abort_override_present_before_fails(self):
        plan = _abort_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(min_replicas=1, overrides_present=True)
        errors = _evaluate(plan, "spec-a-9.9-abort")
        self.assertTrue(any("override-before" in e for e in errors))

    def test_abort_min_replicas_not_restored_fails(self):
        plan = _abort_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(min_replicas=1, overrides_present=True)
        errors = _evaluate(plan, "spec-a-9.9-abort")
        self.assertTrue(any("min_replicas" in e for e in errors))

    def test_unset_min_replicas_before_treated_as_zero_for_enable(self):
        plan = _enable_plan()
        before = _side(min_replicas=None, overrides_present=True)
        plan["resource_changes"][0]["change"]["before"] = before
        self.assertEqual(_evaluate(plan, "spec-a-9.9-enable"), [])

    # -- identity pinned to expected_image_tag, not merely to itself ------------------

    def test_wrong_identity_unchanged_on_both_sides_fails(self):
        """Adversarial fixture: before and after both carry the SAME wrong tag, so a
        before==after-only check would wrongly pass. This is the real gap: "unchanged"
        and "correct" are different claims, and only pinning to the actual
        deployed_image_tag catches a resource that was never on the right image."""
        addr = sut.SERVICE_ADDRESSES[0]
        wrong_image = _expected_image(addr, WRONG_TAG)
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(
            min_replicas=0, overrides_present=True, image=wrong_image, service_version=WRONG_TAG
        )
        plan["resource_changes"][0]["change"]["after"] = _side(
            min_replicas=1, overrides_present=False, image=wrong_image, service_version=WRONG_TAG
        )
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(errors, "wrong-but-unchanged identity must be rejected, not accepted as []")
        self.assertTrue(any("[image]" in e for e in errors))
        self.assertTrue(any("service_version" in e for e in errors))

    def test_wrong_identity_before_only_fails(self):
        addr = sut.SERVICE_ADDRESSES[0]
        wrong_image = _expected_image(addr, WRONG_TAG)
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(
            min_replicas=0, overrides_present=True, image=wrong_image, service_version=WRONG_TAG
        )
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("[image]" in e and "before" in e for e in errors))

    def test_different_expected_tag_is_honoured(self):
        # A plan correctly pinned to a DIFFERENT expected tag than EXPECTED_TAG must
        # still pass — proves the check uses the passed-in value, not a hardcoded one.
        other_tag = "1111111111111111111111111111111111abcd"
        plan = {
            "resource_changes": [
                _service_rc(
                    addr,
                    actions=["update"],
                    before=_side(
                        min_replicas=0, overrides_present=True,
                        image=_expected_image(addr, other_tag), service_version=other_tag
                    ),
                    after=_side(
                        min_replicas=1, overrides_present=False,
                        image=_expected_image(addr, other_tag), service_version=other_tag
                    ),
                )
                for addr in sut.SERVICE_ADDRESSES
            ]
        }
        self.assertEqual(_evaluate(plan, "spec-a-9.9-enable", tags=_tags_map(
            **{
                "portfolio-service": other_tag,
                "market-data-service": other_tag,
                "insight-service": other_tag,
            }
        )), [])

    # -- adversarial registry/repository checks ----------------------------------------

    def test_wrong_registry_correct_repo_and_tag_fails(self):
        """evil.example/portfolio-service:{correct_tag} ends with the right tag but is
        from the wrong registry — the old endswith check would accept it."""
        addr = sut.SERVICE_ADDRESSES[0]
        evil_image = f"evil.example/{sut.SERVICE_IMAGE_REPOSITORIES[addr]}:{EXPECTED_TAG}"
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(
            min_replicas=0, overrides_present=True, image=evil_image
        )
        plan["resource_changes"][0]["change"]["after"] = _side(
            min_replicas=1, overrides_present=False, image=evil_image
        )
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("[image]" in e for e in errors), errors)

    def test_correct_registry_wrong_repository_fails(self):
        """The right ACR but the wrong service image (portfolio-service on the market-data
        address, for example) must be rejected."""
        addr = sut.SERVICE_ADDRESSES[1]  # market-data-service address
        wrong_repo_image = f"{ACR}/portfolio-service:{EXPECTED_TAG}"  # portfolio repo on wrong address
        plan = _enable_plan()
        plan["resource_changes"][1]["change"]["before"] = _side(
            min_replicas=0, overrides_present=True, image=wrong_repo_image
        )
        plan["resource_changes"][1]["change"]["after"] = _side(
            min_replicas=1, overrides_present=False, image=wrong_repo_image
        )
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("[image]" in e for e in errors), errors)

    def test_missing_image_fails(self):
        """A container entry with no image key at all must be rejected."""
        plan = _enable_plan()
        # Strip the image key from the container
        before = _side(min_replicas=0, overrides_present=True)
        del before["template"][0]["container"][0]["image"]
        plan["resource_changes"][0]["change"]["before"] = before
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("[image]" in e and "before" in e for e in errors), errors)

    def test_each_service_must_use_its_own_repository(self):
        """All three services using the portfolio-service image must fail for the two
        non-portfolio addresses — this is the cross-service repository masking that the
        original fixtures hid."""
        # Build a plan where every address uses the portfolio-service image regardless.
        portfolio_image = _expected_image(sut.SERVICE_ADDRESSES[0])
        plan = {
            "resource_changes": [
                _service_rc(
                    addr,
                    actions=["update"],
                    before=_side(min_replicas=0, overrides_present=True, image=portfolio_image),
                    after=_side(min_replicas=1, overrides_present=False, image=portfolio_image),
                )
                for addr in sut.SERVICE_ADDRESSES
            ]
        }
        errors = _evaluate(plan, "spec-a-9.9-enable")
        # market-data and insight addresses should fail; portfolio should be clean.
        market_errors = [e for e in errors if "[image]" in e and sut.SERVICE_ADDRESSES[1] in e]
        insight_errors = [e for e in errors if "[image]" in e and sut.SERVICE_ADDRESSES[2] in e]
        self.assertTrue(market_errors, "market-data-service with portfolio image must fail")
        self.assertTrue(insight_errors, "insight-service with portfolio image must fail")

    def test_correct_distinct_repository_per_service_passes(self):
        """Each service carrying its own correct image must all pass together."""
        self.assertEqual(_evaluate(_enable_plan(), "spec-a-9.9-enable"), [])

    # -- standard guard: forbids touching the 9.9 protected surface at all --------------

    def test_standard_with_no_service_changes_passes(self):
        self.assertEqual(_evaluate({"resource_changes": []}, "standard"), [])

    def test_standard_with_unrelated_service_change_passes(self):
        # A change to these resources that never touches min_replicas or the override
        # env vars is not this guard's concern.
        plan = {
            "resource_changes": [
                _service_rc(
                    sut.SERVICE_ADDRESSES[0],
                    actions=["update"],
                    before=_side(min_replicas=0, overrides_present=True),
                    after=_side(min_replicas=0, overrides_present=True, image=PORTFOLIO_IMAGE),
                )
            ]
        }
        self.assertEqual(_evaluate(plan, "standard"), [])

    def test_standard_rejects_min_replicas_change(self):
        plan = {
            "resource_changes": [
                _service_rc(
                    sut.SERVICE_ADDRESSES[0],
                    actions=["update"],
                    before=_side(min_replicas=0, overrides_present=True),
                    after=_side(min_replicas=1, overrides_present=True),
                )
            ]
        }
        errors = _evaluate(plan, "standard")
        self.assertTrue(any("standard-guard" in e and "min_replicas" in e for e in errors))

    def test_standard_rejects_override_removal(self):
        # This is the exact-scope 9.9 "enable" transition, dispatched under the default
        # standard profile — the scenario this guard exists to catch.
        plan = _enable_plan()
        errors = _evaluate(plan, "standard")
        self.assertTrue(errors)
        self.assertTrue(any("standard-guard" in e for e in errors))

    def test_standard_rejects_override_added_back(self):
        plan = _abort_plan()
        errors = _evaluate(plan, "standard")
        self.assertTrue(any("standard-guard" in e for e in errors))

    def test_standard_min_replicas_zero_none_equivalence_is_not_a_false_positive(self):
        # 0 and None both mean "unset" in this module — a plan showing before=None,
        # after=0 (or vice versa) is not a real change and must not trip the guard.
        plan = {
            "resource_changes": [
                _service_rc(
                    sut.SERVICE_ADDRESSES[0],
                    actions=["update"],
                    before=_side(min_replicas=None, overrides_present=True),
                    after=_side(min_replicas=0, overrides_present=True, image=PORTFOLIO_IMAGE),
                )
            ]
        }
        self.assertEqual(_evaluate(plan, "standard"), [])

    def test_mismatched_portfolio_tag_in_map_fails_even_when_gateway_tag_matches(self):
        """A correct gateway tag plus an incorrect portfolio tag in the map must fail
        for the portfolio address when the plan carries the wrong portfolio identity."""
        addr = sut.SERVICE_ADDRESSES[0]
        wrong_portfolio = _expected_image(addr, WRONG_TAG)
        plan = _enable_plan()
        plan["resource_changes"][0]["change"]["before"] = _side(
            min_replicas=0, overrides_present=True, image=wrong_portfolio, service_version=WRONG_TAG
        )
        plan["resource_changes"][0]["change"]["after"] = _side(
            min_replicas=1, overrides_present=False, image=wrong_portfolio, service_version=WRONG_TAG
        )
        errors = _evaluate(plan, "spec-a-9.9-enable")
        self.assertTrue(any("[image]" in e and addr in e for e in errors))

    def test_standard_does_not_require_expected_image_tags(self):
        # standard's guard doesn't check identity at all; an empty/irrelevant value
        # must not itself cause a failure.
        self.assertEqual(_evaluate(_enable_plan(), "standard"), _evaluate(_enable_plan(), "standard"))

    # -- 9.11 profiles: accepted but routed through the standard 9.9 surface guard -----

    def test_9_11_enable_with_unchanged_9_9_surface_passes(self):
        plan = {
            "resource_changes": [
                {
                    "address": "azurerm_container_app_job.market_data_refresh",
                    "change": {
                        "actions": ["update"],
                        "before": _side(min_replicas=1, overrides_present=False),
                        "after": _side(min_replicas=1, overrides_present=False),
                    },
                }
            ]
        }
        self.assertEqual(_evaluate(plan, "spec-a-9.11-enable"), [])

    def test_9_11_abort_with_unchanged_9_9_surface_passes(self):
        plan = {
            "resource_changes": [
                {
                    "address": "azurerm_container_app_job.market_data_refresh",
                    "change": {
                        "actions": ["update"],
                        "before": _side(min_replicas=1, overrides_present=False),
                        "after": _side(min_replicas=1, overrides_present=False),
                    },
                }
            ]
        }
        self.assertEqual(_evaluate(plan, "spec-a-9.11-abort"), [])

    def test_9_11_enable_rejects_min_replicas_change(self):
        plan = {
            "resource_changes": [
                _service_rc(
                    sut.SERVICE_ADDRESSES[0],
                    actions=["update"],
                    before=_side(min_replicas=1, overrides_present=False),
                    after=_side(min_replicas=0, overrides_present=False),
                )
            ]
        }
        errors = _evaluate(plan, "spec-a-9.11-enable")
        self.assertTrue(any("standard-guard" in e and "min_replicas" in e for e in errors))

    def test_9_11_abort_rejects_override_change(self):
        plan = {
            "resource_changes": [
                _service_rc(
                    sut.SERVICE_ADDRESSES[0],
                    actions=["update"],
                    before=_side(min_replicas=1, overrides_present=False),
                    after=_side(min_replicas=1, overrides_present=True),
                )
            ]
        }
        errors = _evaluate(plan, "spec-a-9.11-abort")
        self.assertTrue(any("standard-guard" in e for e in errors))

    # -- 9.12 profiles: accepted but routed through the standard 9.9 surface guard -----

    def test_both_9_12_profiles_allow_unchanged_9_9_surface(self):
        plan = {
            "resource_changes": [
                _service_rc(
                    sut.SERVICE_ADDRESSES[0],
                    actions=["update"],
                    before=_side(min_replicas=1, overrides_present=False),
                    after=_side(min_replicas=1, overrides_present=False, image=PORTFOLIO_IMAGE),
                )
            ]
        }
        for profile in (
            "spec-a-9.12-enable",
            "spec-a-9.12-disable",
            "spec-a-9.12-tx-diag-enable",
            "spec-a-9.12-tx-diag-disable",
        ):
            with self.subTest(profile=profile):
                self.assertEqual(_evaluate(plan, profile), [])

    def test_both_9_12_profiles_reject_9_9_surface_change(self):
        for profile in (
            "spec-a-9.12-enable",
            "spec-a-9.12-disable",
            "spec-a-9.12-tx-diag-enable",
            "spec-a-9.12-tx-diag-disable",
        ):
            with self.subTest(profile=profile):
                errors = _evaluate(_enable_plan(), profile)
                self.assertTrue(any("standard-guard" in error for error in errors), errors)

    # -- 9.13 restore-scale: dedicated 1 -> 0 path with overrides remaining absent -----

    def _restore_scale_plan(self, *, extra_changes=()):
        rcs = [
            _service_rc(
                addr,
                actions=["update"],
                before=_side(
                    min_replicas=1,
                    overrides_present=False,
                    image=_restore_image(addr),
                ),
                after=_side(
                    min_replicas=0,
                    overrides_present=False,
                    image=_restore_image(addr),
                ),
            )
            for addr in sut.SERVICE_ADDRESSES
        ]
        rcs.extend(extra_changes)
        return {"resource_changes": rcs}

    def test_9_13_restore_scale_plan_passes(self):
        self.assertEqual(_evaluate(self._restore_scale_plan(), "spec-a-9.13-restore-scale"), [])

    def test_9_13_accepts_independent_portfolio_digest(self):
        plan = self._restore_scale_plan()
        pinned = f"{ACR}/portfolio-service@{PORTFOLIO_DIGEST}"
        plan["resource_changes"][0]["change"]["before"]["template"][0]["container"][0]["image"] = pinned
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["image"] = pinned
        self.assertEqual(_evaluate(plan, "spec-a-9.13-restore-scale"), [])

    def test_9_13_rejects_tag_resolved_portfolio_digest(self):
        plan = self._restore_scale_plan()
        mapped = f"{ACR}/portfolio-service@{MAP_PORTFOLIO_DIGEST}"
        plan["resource_changes"][0]["change"]["before"]["template"][0]["container"][0]["image"] = mapped
        plan["resource_changes"][0]["change"]["after"]["template"][0]["container"][0]["image"] = mapped
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_9_13_string_change_payload_fails_closed(self):
        plan = self._restore_scale_plan()
        plan["resource_changes"].append(
            {"address": "module.api_gateway.azurerm_container_app.this", "change": "nope"}
        )
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("change" in e or "plan" in e for e in errors), errors)

    def test_9_13_empty_actions_extra_record_fails(self):
        plan = self._restore_scale_plan()
        plan["resource_changes"].append(
            {
                "address": "module.api_gateway.azurerm_container_app.this",
                "change": {"actions": [], "before": {}, "after": {}},
            }
        )
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("no-op" in e or "actions" in e or "plan" in e for e in errors), errors)

    def test_9_13_noop_with_null_before_fails(self):
        plan = self._restore_scale_plan()
        plan["resource_changes"].append(
            {
                "address": "module.api_gateway.azurerm_container_app.this",
                "change": {"actions": ["no-op"], "before": None, "after": {}},
            }
        )
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("no-op" in e or "before" in e or "plan" in e for e in errors), errors)

    def test_9_13_noop_with_null_after_fails(self):
        plan = self._restore_scale_plan()
        plan["resource_changes"].append(
            {
                "address": "module.api_gateway.azurerm_container_app.this",
                "change": {"actions": ["no-op"], "before": {}, "after": None},
            }
        )
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("no-op" in e or "after" in e or "plan" in e for e in errors), errors)

    def test_9_13_rejects_unrelated_market_data_digest(self):
        plan = self._restore_scale_plan()
        wrong = f"{ACR}/market-data-service@sha256:{'f6' * 32}"
        plan["resource_changes"][1]["change"]["before"]["template"][0]["container"][0]["image"] = wrong
        plan["resource_changes"][1]["change"]["after"]["template"][0]["container"][0]["image"] = wrong
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_9_13_rejects_unrelated_insight_digest(self):
        plan = self._restore_scale_plan()
        wrong = f"{ACR}/insight-service@sha256:{'a7' * 32}"
        plan["resource_changes"][2]["change"]["before"]["template"][0]["container"][0]["image"] = wrong
        plan["resource_changes"][2]["change"]["after"]["template"][0]["container"][0]["image"] = wrong
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("image" in e for e in errors), errors)

    def test_9_13_accepts_resolved_market_data_digest(self):
        plan = self._restore_scale_plan()
        pinned = f"{ACR}/market-data-service@{MARKET_DIGEST}"
        plan["resource_changes"][1]["change"]["before"]["template"][0]["container"][0]["image"] = pinned
        plan["resource_changes"][1]["change"]["after"]["template"][0]["container"][0]["image"] = pinned
        self.assertEqual(_evaluate(plan, "spec-a-9.13-restore-scale"), [])

    def test_9_13_unset_after_min_replicas_is_accepted_as_zero(self):
        plan = self._restore_scale_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(
            min_replicas=None, overrides_present=False, image=PORTFOLIO_DIGEST_IMAGE
        )
        self.assertEqual(_evaluate(plan, "spec-a-9.13-restore-scale"), [])

    def test_9_13_rejects_override_reintroduction(self):
        plan = self._restore_scale_plan()
        plan["resource_changes"][0]["change"]["after"] = _side(
            min_replicas=0, overrides_present=True, image=PORTFOLIO_DIGEST_IMAGE
        )
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(errors)
        self.assertTrue(any("override" in e for e in errors), errors)

    def test_9_13_rejects_override_set_true(self):
        plan = self._restore_scale_plan()
        after = _side(min_replicas=0, overrides_present=False, image=PORTFOLIO_DIGEST_IMAGE)
        after["template"][0]["container"][0]["env"].append(
            {"name": "APP_CATALOG_ENFORCE_HOLDING_INVARIANT", "value": "true"}
        )
        plan["resource_changes"][0]["change"]["after"] = after
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("override" in e for e in errors), errors)

    def test_9_13_rejects_partial_scale(self):
        plan = self._restore_scale_plan()
        plan["resource_changes"].pop()
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(errors)

    def test_9_13_rejects_wrong_direction_scale(self):
        plan = {
            "resource_changes": [
                _service_rc(
                    addr,
                    actions=["update"],
                    before=_side(min_replicas=0, overrides_present=False, image=_expected_image(addr)),
                    after=_side(min_replicas=1, overrides_present=False, image=_expected_image(addr)),
                )
                for addr in sut.SERVICE_ADDRESSES
            ]
        }
        errors = _evaluate(plan, "spec-a-9.13-restore-scale")
        self.assertTrue(any("min_replicas" in e for e in errors), errors)

    def test_standard_rejects_9_13_scale_transition(self):
        errors = _evaluate(self._restore_scale_plan(), "standard")
        self.assertTrue(any("standard-guard" in e and "min_replicas" in e for e in errors), errors)

    def test_9_12_profiles_cannot_borrow_9_13_scale_exception(self):
        for profile in (
            "spec-a-9.12-enable",
            "spec-a-9.12-disable",
            "spec-a-9.12-tx-diag-enable",
            "spec-a-9.12-tx-diag-disable",
        ):
            with self.subTest(profile=profile):
                errors = _evaluate(self._restore_scale_plan(), profile)
                self.assertTrue(any("standard-guard" in e for e in errors), errors)

    def test_9_9_abort_still_requires_override_restore(self):
        errors = _evaluate(self._restore_scale_plan(), "spec-a-9.9-abort")
        self.assertTrue(any("override" in e for e in errors), errors)


if __name__ == "__main__":
    unittest.main()
