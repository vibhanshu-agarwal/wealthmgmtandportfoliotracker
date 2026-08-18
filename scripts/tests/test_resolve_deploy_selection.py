#!/usr/bin/env python3
"""Unit tests for .github/workflows/scripts/resolve_deploy_selection.py.

Wave P P-A.1: empty input is full deploy; any valid selection is scoped;
unknown names fail closed before any Container App update.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "workflows" / "scripts" / "resolve_deploy_selection.py"

KNOWN = (
    "api-gateway",
    "portfolio-service",
    "market-data-service",
    "insight-service",
)


def _load():
    spec = importlib.util.spec_from_file_location("resolve_deploy_selection", SCRIPT)
    if spec is None or spec.loader is None:
        raise FileNotFoundError(SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class TestResolveDeploySelection(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_empty_input_is_full_deploy_of_all_four_services(self):
        result = self.mod.resolve("")
        self.assertEqual(result.deploy_mode, "full")
        self.assertEqual(result.selected_services, list(KNOWN))

    def test_whitespace_only_input_is_full_deploy(self):
        result = self.mod.resolve("  \n\t  ")
        self.assertEqual(result.deploy_mode, "full")
        self.assertEqual(result.selected_services, list(KNOWN))

    def test_single_service_is_scoped(self):
        result = self.mod.resolve("api-gateway")
        self.assertEqual(result.deploy_mode, "scoped")
        self.assertEqual(result.selected_services, ["api-gateway"])

    def test_portfolio_service_only_is_scoped(self):
        result = self.mod.resolve("portfolio-service")
        self.assertEqual(result.deploy_mode, "scoped")
        self.assertEqual(result.selected_services, ["portfolio-service"])

    def test_all_four_named_explicitly_is_still_scoped(self):
        result = self.mod.resolve(",".join(KNOWN))
        self.assertEqual(result.deploy_mode, "scoped")
        self.assertEqual(result.selected_services, list(KNOWN))

    def test_whitespace_and_duplicates_are_normalized(self):
        result = self.mod.resolve(" portfolio-service , api-gateway, portfolio-service ")
        self.assertEqual(result.deploy_mode, "scoped")
        self.assertEqual(result.selected_services, ["portfolio-service", "api-gateway"])

    def test_unknown_service_is_rejected(self):
        with self.assertRaises(self.mod.SelectionError) as caught:
            self.mod.resolve("not-a-service")
        self.assertIn("not-a-service", str(caught.exception))

    def test_mixed_known_and_unknown_is_rejected(self):
        with self.assertRaises(self.mod.SelectionError):
            self.mod.resolve("api-gateway,evil-service")

    def test_comma_only_input_is_rejected(self):
        with self.assertRaises(self.mod.SelectionError):
            self.mod.resolve(",,")


if __name__ == "__main__":
    unittest.main()
