#!/usr/bin/env python3
"""Unit tests for validate_dispatch.py (Spec A checkpoint-9.9 hardening)."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import validate_dispatch as sut  # noqa: E402

SHA = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
OTHER_SHA = "bc6492fabce7667b745ec181d30614142d4335c8"
GATEWAY_SHA = "18693d2defa3dcc34d1a508e03ed4a3c7e0b0f17"
PORTFOLIO_SHA = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
MARKET_SHA = "1111111111111111111111111111111111111111"
INSIGHT_SHA = "2222222222222222222222222222222222222222"
DIGEST = "sha256:" + "a1" * 32
MAIN_REF = "refs/heads/main"


def _valid_tags_json(**overrides) -> str:
    tags = {
        "api-gateway": GATEWAY_SHA,
        "portfolio-service": PORTFOLIO_SHA,
        "market-data-service": MARKET_SHA,
        "insight-service": INSIGHT_SHA,
    }
    tags.update(overrides)
    return json.dumps(tags)


class ValidateDispatchTests(unittest.TestCase):
    def _inputs(self, **overrides):
        base = dict(
            actual_ref=MAIN_REF,
            actual_sha=SHA,
            expected_main_sha=SHA,
            deployed_image_tags_json=_valid_tags_json(),
            expected_portfolio_image_digest="",
            change_profile="standard",
            use_seed_image="false",
            recreate_market_data_job="false",
        )
        base.update(overrides)
        return sut.DispatchInputs(**base)

    # -- ref guard -----------------------------------------------------------

    def test_main_ref_passes(self):
        sut.validate(self._inputs())

    def test_non_main_ref_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(actual_ref="refs/heads/feature-branch"))
        self.assertIn("must be dispatched against", str(ctx.exception))

    def test_tag_ref_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(actual_ref="refs/tags/v1.0.0"))

    # -- SHA guards ------------------------------------------------------------

    def test_mismatched_sha_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(expected_main_sha=OTHER_SHA))
        self.assertIn("does not match", str(ctx.exception))

    def test_sha_comparison_is_case_insensitive(self):
        sut.validate(self._inputs(expected_main_sha=SHA.upper()))

    def test_empty_expected_sha_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(expected_main_sha=""))

    def test_empty_actual_sha_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(actual_sha=""))

    def test_short_expected_sha_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(expected_main_sha="9b2cf0d", actual_sha="9b2cf0d"))

    # -- deployed_image_tags_json guards ---------------------------------------

    def test_empty_deployed_image_tags_json_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(deployed_image_tags_json=""))

    def test_malformed_json_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(deployed_image_tags_json="{not-json"))
        self.assertIn("valid JSON", str(ctx.exception))

    def test_non_object_json_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(deployed_image_tags_json=json.dumps([SHA])))
        self.assertIn("JSON object", str(ctx.exception))

    def test_missing_required_service_fails_closed(self):
        tags = json.loads(_valid_tags_json())
        del tags["portfolio-service"]
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(deployed_image_tags_json=json.dumps(tags)))
        self.assertIn("missing required service", str(ctx.exception))

    def test_extra_service_fails_closed(self):
        tags = json.loads(_valid_tags_json())
        tags["other-service"] = SHA
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(deployed_image_tags_json=json.dumps(tags)))
        self.assertIn("unexpected service", str(ctx.exception))

    def test_short_tag_value_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(
                self._inputs(deployed_image_tags_json=_valid_tags_json(**{"api-gateway": "9b2cf0d"}))
            )

    def test_non_hex_tag_value_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(
                self._inputs(
                    deployed_image_tags_json=_valid_tags_json(
                        **{"market-data-service": "not-a-sha-at-all-zzzzzzzzzzzzzzzzzzzz"}
                    )
                )
            )

    def test_uppercase_tag_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(
                self._inputs(deployed_image_tags_json=_valid_tags_json(**{"api-gateway": GATEWAY_SHA.upper()}))
            )

    def test_duplicate_json_key_fails_closed(self):
        payload = (
            '{"api-gateway":"'
            + ("a" * 40)
            + '","api-gateway":"'
            + GATEWAY_SHA
            + '","portfolio-service":"'
            + PORTFOLIO_SHA
            + '","market-data-service":"'
            + MARKET_SHA
            + '","insight-service":"'
            + INSIGHT_SHA
            + '"}'
        )
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(deployed_image_tags_json=payload))
        self.assertIn("duplicate keys", str(ctx.exception))

    def test_padded_tag_value_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(
                self._inputs(
                    deployed_image_tags_json=_valid_tags_json(
                        **{"portfolio-service": " " + PORTFOLIO_SHA + " "}
                    )
                )
            )

    def test_distinct_per_service_tags_pass(self):
        result = sut.validate(self._inputs())
        self.assertEqual(result["api-gateway"], GATEWAY_SHA)
        self.assertEqual(result["portfolio-service"], PORTFOLIO_SHA)
        self.assertEqual(result["market-data-service"], MARKET_SHA)
        self.assertEqual(result["insight-service"], INSIGHT_SHA)

    # -- change_profile guard --------------------------------------------------

    def test_unknown_profile_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(change_profile="bogus"))

    def test_standard_profile_passes(self):
        sut.validate(self._inputs(change_profile="standard"))

    # -- 9.9 profile seed/recovery-flag guard --------------------------------------

    def test_enable_profile_with_seed_image_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(change_profile="spec-a-9.9-enable", use_seed_image="true")
            )
        self.assertIn("use_seed_image=false", str(ctx.exception))

    def test_enable_profile_with_recreate_job_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.9-enable", recreate_market_data_job="true"
                )
            )
        self.assertIn("recreate_market_data_job=false", str(ctx.exception))

    def test_abort_profile_with_seed_image_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(change_profile="spec-a-9.9-abort", use_seed_image="true"))

    def test_enable_profile_clean_passes(self):
        sut.validate(self._inputs(change_profile="spec-a-9.9-enable"))

    def test_abort_profile_clean_passes(self):
        sut.validate(self._inputs(change_profile="spec-a-9.9-abort"))

    def test_9_11_enable_profile_clean_passes(self):
        sut.validate(self._inputs(change_profile="spec-a-9.11-enable"))

    def test_9_11_abort_profile_clean_passes(self):
        sut.validate(self._inputs(change_profile="spec-a-9.11-abort"))

    def test_9_11_enable_with_seed_image_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(change_profile="spec-a-9.11-enable", use_seed_image="true")
            )
        self.assertIn("use_seed_image=false", str(ctx.exception))
        self.assertIn("scoped Spec A", str(ctx.exception))

    def test_9_11_abort_with_recreate_job_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.11-abort", recreate_market_data_job="true"
                )
            )
        self.assertIn("recreate_market_data_job=false", str(ctx.exception))
        self.assertIn("scoped Spec A", str(ctx.exception))

    # -- 9.12 profile digest and recovery-flag guards -------------------------------

    def test_9_12_enable_with_independent_digest_passes(self):
        sut.validate(
            self._inputs(
                change_profile="spec-a-9.12-enable",
                expected_portfolio_image_digest=DIGEST,
            )
        )

    def test_9_12_disable_with_independent_digest_passes(self):
        sut.validate(
            self._inputs(
                change_profile="spec-a-9.12-disable",
                expected_portfolio_image_digest=DIGEST,
            )
        )

    def test_9_12_tx_diag_enable_with_independent_digest_passes(self):
        sut.validate(
            self._inputs(
                change_profile="spec-a-9.12-tx-diag-enable",
                expected_portfolio_image_digest=DIGEST,
            )
        )

    def test_9_12_tx_diag_disable_with_independent_digest_passes(self):
        sut.validate(
            self._inputs(
                change_profile="spec-a-9.12-tx-diag-disable",
                expected_portfolio_image_digest=DIGEST,
            )
        )

    def test_both_9_12_profiles_require_digest(self):
        for profile in sut.SPEC_A_9_12_PROFILES:
            with self.subTest(profile=profile):
                with self.assertRaises(sut.DispatchValidationError) as ctx:
                    sut.validate(self._inputs(change_profile=profile))
                self.assertIn("expected_portfolio_image_digest", str(ctx.exception))

    def test_both_9_12_profiles_reject_malformed_digest(self):
        for profile in sut.SPEC_A_9_12_PROFILES:
            for digest in ("sha256:abc", "sha256:" + "A" * 64, SHA):
                with self.subTest(profile=profile, digest=digest):
                    with self.assertRaises(sut.DispatchValidationError):
                        sut.validate(
                            self._inputs(
                                change_profile=profile,
                                expected_portfolio_image_digest=digest,
                            )
                        )

    def test_non_9_12_profiles_do_not_require_digest(self):
        for profile in (
            "standard",
            "spec-a-9.9-enable",
            "spec-a-9.9-abort",
            "spec-a-9.11-enable",
            "spec-a-9.11-abort",
        ):
            with self.subTest(profile=profile):
                sut.validate(self._inputs(change_profile=profile))

    def test_nonempty_malformed_digest_rejected_regardless_of_profile(self):
        for profile in (
            "standard",
            "spec-a-9.9-enable",
            "spec-a-9.9-abort",
            "spec-a-9.11-enable",
            "spec-a-9.11-abort",
        ):
            for digest in ("$(whoami)", "`id`", "sha256:abc", "sha256:" + "A" * 64):
                with self.subTest(profile=profile, digest=digest):
                    with self.assertRaises(sut.DispatchValidationError) as ctx:
                        sut.validate(
                            self._inputs(
                                change_profile=profile,
                                expected_portfolio_image_digest=digest,
                            )
                        )
                    self.assertIn("expected_portfolio_image_digest", str(ctx.exception))

    def test_optional_profiles_accept_canonical_digest_when_supplied(self):
        sut.validate(
            self._inputs(
                change_profile="standard",
                expected_portfolio_image_digest=DIGEST,
            )
        )

    def test_both_9_12_profiles_reject_seed_and_recovery_flags(self):
        for profile in sut.SPEC_A_9_12_PROFILES:
            for flag in ("use_seed_image", "recreate_market_data_job"):
                with self.subTest(profile=profile, flag=flag):
                    with self.assertRaises(sut.DispatchValidationError):
                        sut.validate(
                            self._inputs(
                                change_profile=profile,
                                expected_portfolio_image_digest=DIGEST,
                                **{flag: "true"},
                            )
                        )

    def test_standard_profile_ignores_seed_and_recovery_flags(self):
        # standard applies (e.g. an unrelated infra change) are not required to keep these
        # flags false — only the scoped Spec A profiles carry that restriction.
        sut.validate(
            self._inputs(change_profile="standard", use_seed_image="true")
        )

    # -- 9.13 restore-scale profile -----------------------------------------------------

    def test_9_13_restore_scale_with_independent_digest_passes(self):
        sut.validate(
            self._inputs(
                change_profile="spec-a-9.13-restore-scale",
                expected_portfolio_image_digest=DIGEST,
            )
        )

    def test_9_13_requires_independent_digest(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(self._inputs(change_profile="spec-a-9.13-restore-scale"))
        self.assertIn("expected_portfolio_image_digest", str(ctx.exception))

    def test_9_13_rejects_malformed_digest(self):
        for digest in ("sha256:abc", "sha256:" + "A" * 64, SHA, ""):
            with self.subTest(digest=digest):
                with self.assertRaises(sut.DispatchValidationError):
                    sut.validate(
                        self._inputs(
                            change_profile="spec-a-9.13-restore-scale",
                            expected_portfolio_image_digest=digest,
                        )
                    )

    def test_9_13_rejects_seed_image(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.13-restore-scale",
                    expected_portfolio_image_digest=DIGEST,
                    use_seed_image="true",
                )
            )
        self.assertIn("use_seed_image=false", str(ctx.exception))

    def test_9_13_rejects_job_recreate(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.13-restore-scale",
                    expected_portfolio_image_digest=DIGEST,
                    recreate_market_data_job="true",
                )
            )
        self.assertIn("recreate_market_data_job=false", str(ctx.exception))

    def test_9_13_rejects_malformed_image_tag_map_through_shared_parser(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.13-restore-scale",
                    expected_portfolio_image_digest=DIGEST,
                    deployed_image_tags_json="{not-json",
                )
            )
        self.assertIn("valid JSON", str(ctx.exception))

    def test_9_13_rejects_noncanonical_image_tag_map(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.13-restore-scale",
                    expected_portfolio_image_digest=DIGEST,
                    deployed_image_tags_json=_valid_tags_json(
                        **{"portfolio-service": PORTFOLIO_SHA.upper()}
                    ),
                )
            )

    def test_unknown_9_13_like_spelling_fails_closed(self):
        for profile in (
            "spec-a-9.13",
            "spec-a-9.13-restore",
            "spec-a-9.13-restore-scale-zero",
            "spec-a-913-restore-scale",
        ):
            with self.subTest(profile=profile):
                with self.assertRaises(sut.DispatchValidationError):
                    sut.validate(
                        self._inputs(
                            change_profile=profile,
                            expected_portfolio_image_digest=DIGEST,
                        )
                    )

    # -- 9.14 ingress profiles -----------------------------------------------------

    def test_9_14_reopen_passes_without_portfolio_digest(self):
        sut.validate(self._inputs(change_profile="spec-a-9.14-reopen-ingress"))

    def test_9_14_close_passes_without_portfolio_digest(self):
        sut.validate(self._inputs(change_profile="spec-a-9.14-close-ingress"))

    def test_9_14_reopen_rejects_seed_image(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.14-reopen-ingress",
                    use_seed_image="true",
                )
            )
        self.assertIn("use_seed_image=false", str(ctx.exception))

    def test_9_14_close_rejects_job_recreate(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="spec-a-9.14-close-ingress",
                    recreate_market_data_job="true",
                )
            )
        self.assertIn("recreate_market_data_job=false", str(ctx.exception))

    def test_unknown_9_14_like_spelling_fails_closed(self):
        for profile in (
            "spec-a-9.14",
            "spec-a-9.14-reopen",
            "spec-a-914-reopen-ingress",
        ):
            with self.subTest(profile=profile):
                with self.assertRaises(sut.DispatchValidationError):
                    sut.validate(self._inputs(change_profile=profile))

    def test_custom_domain_restore_profile_passes(self):
        sut.validate(self._inputs(change_profile="api-gateway-custom-domain-restore"))

    def test_custom_domain_remove_profile_passes(self):
        sut.validate(self._inputs(change_profile="api-gateway-custom-domain-remove"))

    def test_custom_domain_restore_rejects_seed_image(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="api-gateway-custom-domain-restore",
                    use_seed_image="true",
                )
            )
        self.assertIn("use_seed_image=false", str(ctx.exception))

    def test_custom_domain_remove_rejects_job_recreate(self):
        with self.assertRaises(sut.DispatchValidationError) as ctx:
            sut.validate(
                self._inputs(
                    change_profile="api-gateway-custom-domain-remove",
                    recreate_market_data_job="true",
                )
            )
        self.assertIn("recreate_market_data_job=false", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
