#!/usr/bin/env python3
"""Unit tests for snapshot_container_apps.compare (no live Azure)."""

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
    / "snapshot_container_apps.py"
)


def _load():
    spec = importlib.util.spec_from_file_location("snapshot_container_apps", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _app(revision: str, image: str, weight: int = 100) -> dict:
    return {
        "image": image,
        "revision": revision,
        "traffic": [{"revisionName": revision, "weight": weight}],
    }


class TestCompareNonInterference(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def setUp(self):
        sha = "abc123"
        self.sha = sha
        self.baseline = {
            "api-gateway": _app("gw-1", f"wealthprodacr.azurecr.io/api-gateway:{sha}"),
            "portfolio-service": _app(
                "pf-1", f"wealthprodacr.azurecr.io/portfolio-service:{sha}"
            ),
            "market-data-service": _app(
                "md-1", f"wealthprodacr.azurecr.io/market-data-service:{sha}"
            ),
            "insight-service": _app(
                "in-1", f"wealthprodacr.azurecr.io/insight-service:{sha}"
            ),
            "market-data-refresh-job": {
                "image": f"wealthprodacr.azurecr.io/market-data-service:{sha}"
            },
        }

    def test_unselected_apps_and_job_must_stay_byte_identical(self):
        after = {
            **self.baseline,
            "api-gateway": _app(
                "gw-2", "wealthprodacr.azurecr.io/api-gateway:newsha"
            ),
        }
        errors = self.mod.compare(
            self.baseline, after, ["api-gateway"], git_sha="newsha"
        )
        self.assertEqual(errors, [])

    def test_unselected_app_change_is_a_failure(self):
        after = {
            **self.baseline,
            "api-gateway": _app(
                "gw-2", "wealthprodacr.azurecr.io/api-gateway:newsha"
            ),
            "portfolio-service": _app(
                "pf-2", "wealthprodacr.azurecr.io/portfolio-service:mutated"
            ),
        }
        errors = self.mod.compare(
            self.baseline, after, ["api-gateway"], git_sha="newsha"
        )
        self.assertTrue(any("unselected portfolio-service" in e for e in errors))

    def test_unselected_refresh_job_change_is_a_failure(self):
        after = {
            **self.baseline,
            "api-gateway": _app(
                "gw-2", "wealthprodacr.azurecr.io/api-gateway:newsha"
            ),
            "market-data-refresh-job": {
                "image": "wealthprodacr.azurecr.io/market-data-service:mutated"
            },
        }
        errors = self.mod.compare(
            self.baseline, after, ["api-gateway"], git_sha="newsha"
        )
        self.assertTrue(any("unselected market-data-refresh-job" in e for e in errors))

    def test_selecting_market_data_requires_refresh_job_to_carry_git_sha(self):
        after = {
            **self.baseline,
            "market-data-service": _app(
                "md-2", "wealthprodacr.azurecr.io/market-data-service:newsha"
            ),
            "market-data-refresh-job": {
                "image": "wealthprodacr.azurecr.io/market-data-service:oldsha"
            },
        }
        errors = self.mod.compare(
            self.baseline, after, ["market-data-service"], git_sha="newsha"
        )
        self.assertTrue(any("market-data-refresh-job" in e for e in errors))

    def test_selected_app_must_carry_requested_digest(self):
        digest = "sha256:" + "b" * 64
        image = f"wealthprodacr.azurecr.io/portfolio-service@{digest}"
        after = {
            **self.baseline,
            "portfolio-service": _app("pf-2", image),
        }
        errors = self.mod.compare(
            self.baseline,
            after,
            ["portfolio-service"],
            requested_digest=digest,
        )
        self.assertEqual(errors, [])

    def test_selected_app_missing_requested_digest_is_a_failure(self):
        after = {
            **self.baseline,
            "portfolio-service": _app(
                "pf-2",
                "wealthprodacr.azurecr.io/portfolio-service:abc123",
            ),
        }
        errors = self.mod.compare(
            self.baseline,
            after,
            ["portfolio-service"],
            requested_digest="sha256:" + "b" * 64,
        )
        self.assertTrue(any("digest" in e for e in errors))

    def test_selecting_market_data_passes_when_app_and_job_carry_git_sha(self):
        after = {
            **self.baseline,
            "market-data-service": _app(
                "md-2", "wealthprodacr.azurecr.io/market-data-service:newsha"
            ),
            "market-data-refresh-job": {
                "image": "wealthprodacr.azurecr.io/market-data-service:newsha"
            },
        }
        errors = self.mod.compare(
            self.baseline, after, ["market-data-service"], git_sha="newsha"
        )
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
