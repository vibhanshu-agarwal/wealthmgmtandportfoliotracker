#!/usr/bin/env python3
"""Unit tests for .github/workflows/scripts/validate_deploy_dispatch.py.

Deploy-pipeline hardening (post checkpoint-9.8 incident, extended after Codex review of
PR #139): expected_main_sha must match the actual dispatch SHA, the dispatch must target
refs/heads/main exactly (a matching SHA on another ref is not sufficient), deployment_mode
must be consistent with services/prebuilt_digest (including rejecting the sentinel
dropdown default), and AWS may only receive deployment_mode=full.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "workflows" / "scripts" / "validate_deploy_dispatch.py"

SHA = "9b2cf0d655b4b7ae2ce20ff7b67e4ad750df6900"
OTHER_SHA = "db1db2f8ab4e9d2291864d20490177f100e10055"
MAIN_REF = "refs/heads/main"


def _load():
    spec = importlib.util.spec_from_file_location("validate_deploy_dispatch", SCRIPT)
    if spec is None or spec.loader is None:
        raise FileNotFoundError(SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class TestValidateDeployDispatch(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def _inputs(self, **overrides):
        base = dict(
            deployment_mode="full",
            services="",
            prebuilt_digest="",
            expected_main_sha=SHA,
            actual_sha=SHA,
            actual_ref=MAIN_REF,
            cloud_provider="azure",
        )
        base.update(overrides)
        return self.mod.DispatchInputs(**base)

    # -- SHA guard -----------------------------------------------------------

    def test_matching_sha_full_mode_passes(self):
        self.mod.validate(self._inputs())

    def test_mismatched_sha_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError) as ctx:
            self.mod.validate(self._inputs(expected_main_sha=OTHER_SHA))
        self.assertIn("does not match", str(ctx.exception))

    def test_sha_comparison_is_case_insensitive(self):
        self.mod.validate(self._inputs(expected_main_sha=SHA.upper()))

    def test_empty_expected_sha_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(expected_main_sha=""))

    def test_empty_actual_sha_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(actual_sha=""))

    # -- ref guard -------------------------------------------------------------

    def test_non_main_ref_fails_closed_even_with_matching_sha(self):
        with self.assertRaises(self.mod.DispatchValidationError) as ctx:
            self.mod.validate(self._inputs(actual_ref="refs/heads/feature-branch"))
        self.assertIn("must be dispatched against", str(ctx.exception))

    def test_tag_ref_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(actual_ref="refs/tags/v1.0.0"))

    def test_main_ref_passes(self):
        self.mod.validate(self._inputs(actual_ref=MAIN_REF))

    # -- mode guard: full ------------------------------------------------------

    def test_full_mode_with_services_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(services="portfolio-service"))

    def test_full_mode_with_digest_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(
                self._inputs(prebuilt_digest="wealthprodacr.azurecr.io/portfolio-service@sha256:" + "a" * 64)
            )

    # -- mode guard: scoped ----------------------------------------------------

    def test_scoped_mode_requires_services(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(deployment_mode="scoped", services=""))

    def test_scoped_mode_with_services_passes(self):
        self.mod.validate(
            self._inputs(deployment_mode="scoped", services="portfolio-service,insight-service")
        )

    def test_scoped_mode_with_digest_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(
                self._inputs(
                    deployment_mode="scoped",
                    services="portfolio-service",
                    prebuilt_digest="wealthprodacr.azurecr.io/portfolio-service@sha256:" + "a" * 64,
                )
            )

    # -- mode guard: digest ------------------------------------------------------

    def test_digest_mode_requires_digest(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(deployment_mode="digest", prebuilt_digest=""))

    def test_digest_mode_with_empty_services_passes(self):
        self.mod.validate(
            self._inputs(
                deployment_mode="digest",
                services="",
                prebuilt_digest="wealthprodacr.azurecr.io/portfolio-service@sha256:" + "a" * 64,
            )
        )

    def test_digest_mode_with_portfolio_service_passes(self):
        self.mod.validate(
            self._inputs(
                deployment_mode="digest",
                services="portfolio-service",
                prebuilt_digest="wealthprodacr.azurecr.io/portfolio-service@sha256:" + "a" * 64,
            )
        )

    def test_digest_mode_with_other_service_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(
                self._inputs(
                    deployment_mode="digest",
                    services="insight-service",
                    prebuilt_digest="wealthprodacr.azurecr.io/portfolio-service@sha256:" + "a" * 64,
                )
            )

    # -- unknown mode / sentinel default -----------------------------------------

    def test_unknown_mode_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(self._inputs(deployment_mode="bogus"))

    def test_sentinel_default_fails_closed(self):
        # The dropdown's pre-filled first option — accepting it without choosing a real
        # mode must not silently resolve to "full".
        with self.assertRaises(self.mod.DispatchValidationError) as ctx:
            self.mod.validate(self._inputs(deployment_mode="select-deployment-mode"))
        self.assertIn("explicitly choose", str(ctx.exception))

    # -- AWS provider guard --------------------------------------------------------

    def test_aws_full_mode_passes(self):
        self.mod.validate(self._inputs(cloud_provider="aws", deployment_mode="full"))

    def test_aws_scoped_mode_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError) as ctx:
            self.mod.validate(
                self._inputs(cloud_provider="aws", deployment_mode="scoped", services="api-gateway")
            )
        self.assertIn("not supported for CLOUD_PROVIDER=aws", str(ctx.exception))

    def test_aws_digest_mode_fails_closed(self):
        with self.assertRaises(self.mod.DispatchValidationError):
            self.mod.validate(
                self._inputs(
                    cloud_provider="aws",
                    deployment_mode="digest",
                    prebuilt_digest="wealthprodacr.azurecr.io/portfolio-service@sha256:" + "a" * 64,
                )
            )

    def test_azure_scoped_mode_unaffected_by_provider_guard(self):
        self.mod.validate(
            self._inputs(cloud_provider="azure", deployment_mode="scoped", services="api-gateway")
        )


if __name__ == "__main__":
    unittest.main()
