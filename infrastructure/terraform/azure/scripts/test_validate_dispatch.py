#!/usr/bin/env python3
"""Unit tests for validate_dispatch.py (Spec A checkpoint-9.9 hardening)."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import validate_dispatch as sut  # noqa: E402

SHA = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
OTHER_SHA = "bc6492fabce7667b745ec181d30614142d4335c8"
MAIN_REF = "refs/heads/main"


class ValidateDispatchTests(unittest.TestCase):
    def _inputs(self, **overrides):
        base = dict(
            actual_ref=MAIN_REF,
            actual_sha=SHA,
            expected_main_sha=SHA,
            deployed_image_tag=SHA,
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

    # -- deployed_image_tag guards -----------------------------------------------

    def test_empty_deployed_image_tag_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(deployed_image_tag=""))

    def test_non_hex_deployed_image_tag_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(deployed_image_tag="not-a-sha-at-all"))

    def test_short_deployed_image_tag_fails_closed(self):
        with self.assertRaises(sut.DispatchValidationError):
            sut.validate(self._inputs(deployed_image_tag="9b2cf0d"))

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

    def test_standard_profile_ignores_seed_and_recovery_flags(self):
        # standard applies (e.g. an unrelated infra change) are not required to keep these
        # flags false — only the two 9.9 profiles carry that restriction.
        sut.validate(
            self._inputs(change_profile="standard", use_seed_image="true")
        )


if __name__ == "__main__":
    unittest.main()
