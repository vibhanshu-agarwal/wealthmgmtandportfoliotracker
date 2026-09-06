#!/usr/bin/env python3
"""Unit tests for snapshot_container_apps.compare (no live Azure)."""

from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from unittest import mock
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


class TestAggregateDigests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_aggregate_parser_and_no_azure_environment(self):
        args = self.mod._parser().parse_args(["aggregate-digests", "--digest-root", "x", "--selected", '["api-gateway"]'])
        self.assertEqual(args.command, "aggregate-digests")
        with tempfile.TemporaryDirectory() as root:
            Path(root, "api-gateway").mkdir()
            Path(root, "api-gateway", "digest.txt").write_text("sha256:" + "a" * 64)
            output = Path(root, "manifest.json")
            self.mod.aggregate_digests(root, ["api-gateway"], str(output))
            self.assertTrue(output.exists())

    def test_aggregate_exact_services_and_lowercase_digest(self):
        with tempfile.TemporaryDirectory() as root:
            for service in ("api-gateway", "portfolio-service"):
                Path(root, service).mkdir()
                Path(root, service, "digest.txt").write_text("sha256:" + "b" * 64)
            manifest = self.mod.aggregate_digests(root, ["api-gateway", "portfolio-service"], None)
            self.assertEqual(set(manifest), {"api-gateway", "portfolio-service"})

    def test_aggregate_rejects_duplicate_missing_extra_malformed_and_missing_refresh_job(self):
        cases = [
            ({"api-gateway": ["sha256:" + "a" * 64, "sha256:" + "b" * 64]}, ["api-gateway"]),
            ({}, ["api-gateway"]),
            ({"api-gateway": ["sha256:" + "a" * 64], "extra": ["sha256:" + "a" * 64]}, ["api-gateway"]),
            ({"api-gateway": ["SHA256:" + "a" * 64]}, ["api-gateway"]),
        ]
        for entries, selected in cases:
            with self.subTest(entries=entries):
                with tempfile.TemporaryDirectory() as root:
                    for service, digests in entries.items():
                        Path(root, service).mkdir()
                        for index, digest in enumerate(digests):
                            name = "digest.txt" if index == 0 else f"digest-{index}.txt"
                            Path(root, service, name).write_text(digest)
                    with self.assertRaises(ValueError):
                        self.mod.aggregate_digests(root, selected, None)

    def test_cli_aggregate_without_azure_writes_exact_manifest(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "api-gateway").mkdir()
            digest = "sha256:" + "c" * 64
            Path(root, "api-gateway", "digest.txt").write_text(digest)
            output = Path(root, "manifest.json")
            with mock.patch.dict(os.environ, {}, clear=True):
                with mock.patch.object(self.mod, "capture", side_effect=AssertionError("Azure called")):
                    with mock.patch("sys.argv", ["snapshot", "aggregate-digests", "--digest-root", root, "--selected", '["api-gateway"]', "--output", str(output)]):
                        self.assertEqual(self.mod.main(), 0)
            self.assertEqual(output.read_text(), '{\n  "api-gateway": "' + digest + '"\n}\n')

    def test_compare_digest_manifest_round_trip_and_exact_selected_keys(self):
        args = self.mod._parser().parse_args(["compare", "--digest-manifest", "manifest.json"])
        self.assertEqual(args.digest_manifest, "manifest.json")
        with self.assertRaises(ValueError):
            self.mod.validate_manifest({"api-gateway": "sha256:" + "a" * 64}, ["api-gateway", "portfolio-service"])

    def test_market_data_manifest_has_no_refresh_artifact(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "market-data-service").mkdir()
            Path(root, "market-data-service", "digest.txt").write_text("sha256:" + "d" * 64)
            manifest = self.mod.aggregate_digests(root, ["market-data-service"], None)
            self.assertEqual(list(manifest), ["market-data-service"])

    def test_manifest_duplicate_json_keys_and_invalid_selected_fail(self):
        with self.assertRaises(ValueError):
            self.mod.load_manifest_text('{"api-gateway":"sha256:' + 'a' * 64 + '","api-gateway":"sha256:' + 'b' * 64 + '"}', ["api-gateway"])
        with self.assertRaises(ValueError):
            self.mod.validate_manifest({}, [])
        with self.assertRaises(ValueError):
            self.mod.validate_manifest({"unknown": "sha256:" + "a" * 64}, ["unknown"])

    def test_multi_service_cli_compare_uses_distinct_manifest_digests(self):
        digest_a, digest_b = "sha256:" + "a" * 64, "sha256:" + "b" * 64
        before = {"api-gateway": {"image": "repo/gateway:old"}, "portfolio-service": {"image": "repo/portfolio:old"}}
        after = {"api-gateway": {"image": "repo/gateway@" + digest_a}, "portfolio-service": {"image": "repo/portfolio@" + digest_b}}
        self.assertEqual(self.mod.compare(before, after, ["api-gateway", "portfolio-service"], digest_manifest={"api-gateway": digest_a, "portfolio-service": digest_b}), [])

    def test_market_data_job_must_equal_app_expected_image(self):
        digest = "sha256:" + "c" * 64
        before = {"market-data-service": {"image": "repo/market:old"}, "market-data-refresh-job": {"image": "repo/market:old"}}
        after = {"market-data-service": {"image": "repo/market@" + digest}, "market-data-refresh-job": {"image": "other/market@" + digest}}
        self.assertTrue(self.mod.compare(before, after, ["market-data-service"], digest_manifest={"market-data-service": digest}))

    def test_main_cli_manifest_and_failure_modes(self):
        digest_a, digest_b = "sha256:" + "a" * 64, "sha256:" + "b" * 64
        with tempfile.TemporaryDirectory() as root:
            manifest = Path(root, "manifest.json")
            manifest.write_text('{"api-gateway":"' + digest_a + '","portfolio-service":"' + digest_b + '"}')
            before = {"api-gateway": {"image": "repo/gateway:old"}, "portfolio-service": {"image": "repo/portfolio:old"}}
            after = {"api-gateway": {"image": "repo/gateway@" + digest_a}, "portfolio-service": {"image": "repo/portfolio@" + digest_b}}
            with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=after), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["api-gateway","portfolio-service"]', "--digest-manifest", str(manifest)]):
                self.assertEqual(self.mod.main(), 0)
            manifest.write_text('{"api-gateway":"' + digest_a + '","api-gateway":"' + digest_b + '"}')
            with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", side_effect=AssertionError("capture called")), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["api-gateway"]', "--digest-manifest", str(manifest)]):
                with self.assertRaises(ValueError): self.mod.main()

    def test_main_wrong_git_sha_app_returns_one(self):
        before = {"api-gateway": {"image": "repo/gateway:old"}}
        after = {"api-gateway": {"image": "wrong/repo:newsha"}}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=after), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["api-gateway"]', "--git-sha", "newsha"]):
            self.assertEqual(self.mod.main(), 1)

    def test_main_requested_digest_precedence_and_job_wrong_repo_returns_one(self):
        digest = "sha256:" + "e" * 64
        before = {"market-data-service": {"image": "repo/market:old"}, "market-data-refresh-job": {"image": "repo/market:old"}}
        after = {"market-data-service": {"image": "repo/market@" + digest}, "market-data-refresh-job": {"image": "wrong/repo@" + digest}}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=after), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["market-data-service"]', "--requested-digest", digest, "--git-sha", "wrongsha"]):
            self.assertEqual(self.mod.main(), 1)

    def test_main_missing_market_data_job_returns_one_and_good_app_job_zero(self):
        digest = "sha256:" + "f" * 64
        before = {"market-data-service": {"image": "repo/market:old"}, "market-data-refresh-job": {"image": "repo/market:old"}}
        missing = {"market-data-service": {"image": "repo/market@" + digest}, "market-data-refresh-job": {"missing": True}}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=missing), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["market-data-service"]', "--requested-digest", digest]):
            self.assertEqual(self.mod.main(), 1)
        good = {"market-data-service": {"image": "repo/market@" + digest}, "market-data-refresh-job": {"image": "repo/market@" + digest}}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=good), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["market-data-service"]', "--requested-digest", digest]):
            self.assertEqual(self.mod.main(), 0)
            with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value={"api-gateway": {"image": "wrong/repo@" + digest_a}}), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps({"api-gateway": {"image": "repo/gateway:old"}}), "--selected", '["api-gateway"]', "--requested-digest", digest_a]):
                self.assertEqual(self.mod.main(), 1)
        for selected in ("[]", '["unknown"]'):
            with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", side_effect=AssertionError("capture called")), mock.patch("sys.argv", ["snapshot", "compare", "--before", "{}", "--selected", selected]):
                with self.assertRaises(ValueError): self.mod.main()


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

    def test_requested_digest_wins_over_git_sha(self):
        digest = "sha256:" + "b" * 64
        after = {
            **self.baseline,
            "portfolio-service": _app(
                "pf-2",
                f"wealthprodacr.azurecr.io/portfolio-service@{digest}",
            ),
        }
        errors = self.mod.compare(
            self.baseline,
            after,
            ["portfolio-service"],
            git_sha=self.sha,
            requested_digest=digest,
        )
        self.assertEqual(errors, [])

    def test_git_sha_image_fails_when_requested_digest_is_set(self):
        digest = "sha256:" + "b" * 64
        after = {
            **self.baseline,
            "portfolio-service": _app(
                "pf-2",
                f"wealthprodacr.azurecr.io/portfolio-service:{self.sha}",
            ),
        }
        errors = self.mod.compare(
            self.baseline,
            after,
            ["portfolio-service"],
            git_sha=self.sha,
            requested_digest=digest,
        )
        self.assertTrue(any("digest" in e for e in errors))
        self.assertFalse(any("git sha" in e for e in errors))

    def test_selected_app_without_marker_is_a_failure(self):
        after = {
            **self.baseline,
            "portfolio-service": _app(
                "pf-2",
                f"wealthprodacr.azurecr.io/portfolio-service:{self.sha}",
            ),
        }
        errors = self.mod.compare(
            self.baseline,
            after,
            ["portfolio-service"],
        )
        self.assertTrue(any("neither digest nor git sha" in e for e in errors))

    def test_git_sha_arg_does_not_default_to_github_sha_env(self):
        previous = os.environ.get("GITHUB_SHA")
        os.environ["GITHUB_SHA"] = "should-not-leak"
        try:
            args = self.mod._parser().parse_args(["compare"])
            self.assertEqual(args.git_sha, "")
            self.assertEqual(args.requested_digest, "")
        finally:
            if previous is None:
                os.environ.pop("GITHUB_SHA", None)
            else:
                os.environ["GITHUB_SHA"] = previous

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
