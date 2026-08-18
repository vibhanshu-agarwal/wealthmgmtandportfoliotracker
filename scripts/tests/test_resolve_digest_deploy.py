#!/usr/bin/env python3
"""Unit tests for resolve_digest_deploy.py — Wave P P-B.2 / P-B.4a.

Every rejection must fail before any ACR lookup (the stand-in for
az containerapp update). Empty input is not digest mode.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT = (
    Path(__file__).resolve().parents[2]
    / ".github"
    / "workflows"
    / "scripts"
    / "resolve_digest_deploy.py"
)

HEX = "a" * 64
VALID = f"wealthprodacr.azurecr.io/portfolio-service@sha256:{HEX}"


def _load():
    spec = importlib.util.spec_from_file_location("resolve_digest_deploy", SCRIPT)
    if spec is None or spec.loader is None:
        raise FileNotFoundError(SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class RecordingLookup:
    def __init__(self, exists: bool = True):
        self.calls: list[tuple[str, str, str]] = []
        self.exists = exists

    def __call__(self, registry: str, repository: str, digest: str) -> bool:
        self.calls.append((registry, repository, digest))
        return self.exists


class TestResolveDigestDeploy(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_empty_input_disables_digest_mode_without_lookup(self):
        lookup = RecordingLookup()
        result = self.mod.resolve("", ["portfolio-service"], lookup)
        self.assertFalse(result.enabled)
        self.assertEqual(lookup.calls, [])

    def test_whitespace_input_disables_digest_mode(self):
        lookup = RecordingLookup()
        result = self.mod.resolve("  \n", ["portfolio-service"], lookup)
        self.assertFalse(result.enabled)
        self.assertEqual(lookup.calls, [])

    def test_valid_portfolio_digest_looks_up_manifest_last(self):
        lookup = RecordingLookup(exists=True)
        result = self.mod.resolve(VALID, ["portfolio-service"], lookup)
        self.assertTrue(result.enabled)
        self.assertEqual(result.image, VALID)
        self.assertEqual(
            lookup.calls,
            [("wealthprodacr.azurecr.io", "portfolio-service", f"sha256:{HEX}")],
        )

    def test_unresolved_manifest_is_rejected_after_shape_checks(self):
        lookup = RecordingLookup(exists=False)
        with self.assertRaises(self.mod.DigestError) as caught:
            self.mod.resolve(VALID, ["portfolio-service"], lookup)
        self.assertEqual(len(lookup.calls), 1)
        self.assertIn("does not resolve", str(caught.exception).lower())

    def test_valid_digest_succeeds_without_lookup_when_skip_lookup(self):
        result = self.mod.resolve(VALID, ["portfolio-service"], lookup=None)
        self.assertTrue(result.enabled)
        self.assertEqual(result.image, VALID)
        self.assertEqual(result.digest, f"sha256:{HEX}")

    def test_empty_selection_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError):
            self.mod.resolve(VALID, [], lookup)
        self.assertEqual(lookup.calls, [])

    def test_full_deploy_selection_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        four = [
            "api-gateway",
            "portfolio-service",
            "market-data-service",
            "insight-service",
        ]
        with self.assertRaises(self.mod.DigestError):
            self.mod.resolve(VALID, four, lookup)
        self.assertEqual(lookup.calls, [])

    def test_multiple_selected_services_are_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError):
            self.mod.resolve(
                VALID, ["portfolio-service", "api-gateway"], lookup
            )
        self.assertEqual(lookup.calls, [])

    def test_wrong_service_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError) as caught:
            self.mod.resolve(VALID, ["api-gateway"], lookup)
        self.assertEqual(lookup.calls, [])
        self.assertIn("portfolio-service", str(caught.exception))

    def test_tag_reference_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError) as caught:
            self.mod.resolve(
                "wealthprodacr.azurecr.io/portfolio-service:500a8c5",
                ["portfolio-service"],
                lookup,
            )
        self.assertEqual(lookup.calls, [])
        self.assertRegex(str(caught.exception).lower(), r"sha256|tag")

    def test_short_digest_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError):
            self.mod.resolve(
                "wealthprodacr.azurecr.io/portfolio-service@sha256:abcd",
                ["portfolio-service"],
                lookup,
            )
        self.assertEqual(lookup.calls, [])

    def test_foreign_registry_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError) as caught:
            self.mod.resolve(
                f"ghcr.io/portfolio-service@sha256:{HEX}",
                ["portfolio-service"],
                lookup,
            )
        self.assertEqual(lookup.calls, [])
        self.assertIn("wealthprodacr.azurecr.io", str(caught.exception))

    def test_repository_mismatch_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError) as caught:
            self.mod.resolve(
                f"wealthprodacr.azurecr.io/api-gateway@sha256:{HEX}",
                ["portfolio-service"],
                lookup,
            )
        self.assertEqual(lookup.calls, [])
        self.assertRegex(str(caught.exception).lower(), r"repositor")

    def test_foreign_repository_on_allowed_registry_is_rejected_without_lookup(self):
        lookup = RecordingLookup()
        with self.assertRaises(self.mod.DigestError):
            self.mod.resolve(
                f"wealthprodacr.azurecr.io/insight-service@sha256:{HEX}",
                ["portfolio-service"],
                lookup,
            )
        self.assertEqual(lookup.calls, [])


if __name__ == "__main__":
    unittest.main()
