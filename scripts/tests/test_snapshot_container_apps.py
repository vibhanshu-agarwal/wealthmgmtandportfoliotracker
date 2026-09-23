#!/usr/bin/env python3
"""Unit tests for snapshot_container_apps.compare (no live Azure)."""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
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
        before = {"market-data-service": _app("md-old", "repo/market:old"), "market-data-refresh-job": {"image": "repo/market:old"}}
        after = {"market-data-service": {"image": "repo/market@" + digest}, "market-data-refresh-job": {"image": "other/market@" + digest}}
        self.assertTrue(self.mod.compare(before, after, ["market-data-service"], digest_manifest={"market-data-service": digest}))

    def test_main_cli_manifest_and_failure_modes(self):
        digest_a, digest_b = "sha256:" + "a" * 64, "sha256:" + "b" * 64
        with tempfile.TemporaryDirectory() as root:
            manifest = Path(root, "manifest.json")
            manifest.write_text('{"api-gateway":"' + digest_a + '","portfolio-service":"' + digest_b + '"}')
            before = {"api-gateway": _app("gw-old", "repo/gateway:old"), "portfolio-service": _app("ps-old", "repo/portfolio:old")}
            after = {"api-gateway": _app("gw-new", "repo/gateway@" + digest_a), "portfolio-service": _app("ps-new", "repo/portfolio@" + digest_b)}
            bindings = {"api-gateway": _binding("gw-new", "repo/gateway@" + digest_a), "portfolio-service": _binding("ps-new", "repo/portfolio@" + digest_b)}
            with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=after), mock.patch.object(self.mod, "capture_bindings", return_value=bindings), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["api-gateway","portfolio-service"]', "--digest-manifest", str(manifest)]):
                self.assertEqual(self.mod.main(), 0)
            manifest.write_text('{"api-gateway":"' + digest_a + '","api-gateway":"' + digest_b + '"}')
            with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", side_effect=AssertionError("capture called")), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["api-gateway"]', "--digest-manifest", str(manifest)]):
                with self.assertRaises(ValueError): self.mod.main()

    def test_main_wrong_git_sha_app_returns_one(self):
        before = {"api-gateway": _app("gw-old", "repo/gateway:old")}
        after = {"api-gateway": _app("gw-new", "wrong/repo:newsha")}
        bindings = {"api-gateway": _binding("gw-new", "repo/gateway:newsha")}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=after), mock.patch.object(self.mod, "capture_bindings", return_value=bindings), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["api-gateway"]', "--git-sha", "newsha"]):
            self.assertEqual(self.mod.main(), 1)

    def test_main_requested_digest_precedence_and_job_wrong_repo_returns_one(self):
        digest = "sha256:" + "e" * 64
        before = {"market-data-service": _app("md-old", "repo/market:old"), "market-data-refresh-job": {"image": "repo/market:old"}}
        after = {"market-data-service": _app("md-new", "repo/market@" + digest), "market-data-refresh-job": {"image": "wrong/repo@" + digest}}
        bindings = {"market-data-service": _binding("md-new", "repo/market@" + digest)}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=after), mock.patch.object(self.mod, "capture_bindings", return_value=bindings), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["market-data-service"]', "--requested-digest", digest, "--git-sha", "wrongsha"]):
            self.assertEqual(self.mod.main(), 1)

    def test_main_missing_market_data_job_returns_one_and_good_app_job_zero(self):
        digest = "sha256:" + "f" * 64
        before = {"market-data-service": _app("md-old", "repo/market:old"), "market-data-refresh-job": {"image": "repo/market:old"}}
        missing = {"market-data-service": _app("md-new", "repo/market@" + digest), "market-data-refresh-job": {"missing": True}}
        md_bindings = {"market-data-service": _binding("md-new", "repo/market@" + digest)}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=missing), mock.patch.object(self.mod, "capture_bindings", return_value=md_bindings), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["market-data-service"]', "--requested-digest", digest]):
            self.assertEqual(self.mod.main(), 1)
        good = {"market-data-service": _app("md-new", "repo/market@" + digest), "market-data-refresh-job": {"image": "repo/market@" + digest}}
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=good), mock.patch.object(self.mod, "capture_bindings", return_value=md_bindings), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["market-data-service"]', "--requested-digest", digest]):
            self.assertEqual(self.mod.main(), 0)
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value=good), mock.patch.object(self.mod, "capture_bindings", return_value=md_bindings), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps(before), "--selected", '["market-data-service"]', "--requested-digest", digest, "--git-sha", "wrongsha"]):
            self.assertEqual(self.mod.main(), 0)
            with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(self.mod, "capture", return_value={"api-gateway": _app("gw-new", "wrong/repo@" + digest)}), mock.patch.object(self.mod, "capture_bindings", return_value={"api-gateway": _binding("gw-new", "repo/gateway@" + digest)}), mock.patch("sys.argv", ["snapshot", "compare", "--before", json.dumps({"api-gateway": _app("gw-old", "repo/gateway:old")}), "--selected", '["api-gateway"]', "--requested-digest", digest]):
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


def _binding(
    ready_revision: str,
    image: str,
    *,
    active: bool = True,
    provisioning_state: str = "Provisioned",
    traffic_weight: int = 100,
) -> dict:
    return {
        "readyRevision": ready_revision,
        "revisionImage": image,
        "active": active,
        "provisioningState": provisioning_state,
        "trafficWeight": traffic_weight,
    }


class TestRevisionBinding(unittest.TestCase):
    """B3: bind this run's digest to the revision that actually holds traffic.

    ``compare`` proves only that the app's *template* names the expected image, and
    ``--require-complete`` proves only that the traffic map is well formed. Neither shows
    that the revision holding 100% of traffic is the one this run made ready, nor that it
    carries this run's digest. Under ``min_replicas = 0`` Azure can keep traffic on the
    previous revision until the new one is ready, so that gap is reachable in production.
    """

    DIGEST = "sha256:" + "c" * 64
    IMAGE = "wealthprodacr.azurecr.io/portfolio-service@" + DIGEST
    OLD_IMAGE = "wealthprodacr.azurecr.io/portfolio-service@sha256:" + "d" * 64

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def setUp(self):
        self.before = {"portfolio-service": _app("ps-old", self.OLD_IMAGE)}
        self.after = {"portfolio-service": _app("ps-new", self.IMAGE)}
        self.bindings = {"portfolio-service": _binding("ps-new", self.IMAGE)}
        self.expected = {"portfolio-service": self.IMAGE}

    def _errors(self, **overrides):
        kwargs = {
            "before": self.before,
            "after": self.after,
            "bindings": self.bindings,
            "selected": ["portfolio-service"],
            "expected_images": self.expected,
        }
        kwargs.update(overrides)
        return self.mod.revision_binding_errors(**kwargs)

    def _one_error(self, needle: str, errors: list[str]) -> None:
        self.assertEqual(len(errors), 1, errors)
        self.assertIn(needle, errors[0])

    def test_new_ready_revision_holding_all_traffic_with_expected_digest_passes(self):
        self.assertEqual(self._errors(), [])

    def test_passes_while_scaled_to_zero(self):
        # portfolio-service runs min_replicas = 0, so an idle app reports no replicas and a
        # non-Running state. Requiring a live replica would fail a healthy deploy.
        self.bindings["portfolio-service"]["replicas"] = 0
        self.bindings["portfolio-service"]["runningState"] = "Stopped"
        self.assertEqual(self._errors(), [])

    def test_accepts_the_latest_revision_traffic_form(self):
        # A single-revision app reports [{"latestRevision": true, "weight": 100}] with no
        # revision name; it selects a revision only via latestRevisionName.
        self.after["portfolio-service"]["traffic"] = [
            {"latestRevision": True, "weight": 100}
        ]
        self.assertEqual(self._errors(), [])

    def test_rejects_latest_revision_traffic_form_when_latest_is_not_the_ready_revision(self):
        # latestRevisionName is the newest revision; latestReadyRevisionName is the newest
        # one that became ready. When they differ, latestRevision traffic is not proven to
        # reach the revision this run made ready.
        self.after["portfolio-service"]["traffic"] = [
            {"latestRevision": True, "weight": 100}
        ]
        self.after["portfolio-service"]["revision"] = "ps-newer-not-ready"
        self._one_error("does not route 100% of traffic", self._errors())

    def test_rejects_an_entry_that_sets_both_selectors_and_they_disagree(self):
        # revisionName and latestRevision are distinct routing forms. An entry carrying
        # both says two different things at once; reading only the first would let the
        # disagreement through.
        self.after["portfolio-service"]["traffic"] = [
            {"revisionName": "ps-new", "latestRevision": True, "weight": 100}
        ]
        self.after["portfolio-service"]["revision"] = "ps-newer-not-ready"
        self._one_error("does not route 100% of traffic", self._errors())

    def test_rejects_an_entry_that_sets_both_selectors_even_when_they_agree(self):
        # Fail closed on the contradiction itself, not only on a visible disagreement:
        # a single valid-looking reading must not rescue a malformed entry.
        self.after["portfolio-service"]["traffic"] = [
            {"revisionName": "ps-new", "latestRevision": True, "weight": 100}
        ]
        self._one_error("does not route 100% of traffic", self._errors())

    def test_accepts_an_entry_that_names_a_revision_with_latest_revision_false(self):
        # `latestRevision: false` beside a revision name is the ordinary named form.
        self.after["portfolio-service"]["traffic"] = [
            {"revisionName": "ps-new", "latestRevision": False, "weight": 100}
        ]
        self.assertEqual(self._errors(), [])

    def test_rejects_ready_revision_carrying_a_different_digest(self):
        self.bindings["portfolio-service"]["revisionImage"] = self.OLD_IMAGE
        self._one_error("ready revision image", self._errors())

    def test_rejects_traffic_still_on_the_previous_revision(self):
        self.after["portfolio-service"]["traffic"] = [
            {"revisionName": "ps-old", "weight": 100}
        ]
        self._one_error("does not route 100% of traffic", self._errors())

    def test_rejects_traffic_split_across_revisions(self):
        self.after["portfolio-service"]["traffic"] = [
            {"revisionName": "ps-new", "weight": 50},
            {"revisionName": "ps-old", "weight": 50},
        ]
        self._one_error("does not route 100% of traffic", self._errors())

    def test_rejects_empty_ready_revision_name(self):
        self.bindings["portfolio-service"]["readyRevision"] = ""
        self._one_error("has no latestReadyRevisionName", self._errors())

    def test_rejects_unreadable_binding(self):
        self.bindings["portfolio-service"] = {"missing": True, "error": "boom"}
        self._one_error("revision binding could not be read", self._errors())

    def test_rejects_absent_binding(self):
        self.bindings = {}
        self._one_error("has no revision binding", self._errors())

    def test_rejects_inactive_ready_revision(self):
        self.bindings["portfolio-service"]["active"] = False
        self._one_error("ready revision is not active", self._errors())

    def test_rejects_unprovisioned_ready_revision(self):
        self.bindings["portfolio-service"]["provisioningState"] = "Failed"
        self._one_error("ready revision provisioning state", self._errors())

    def test_rejects_ready_revision_without_full_traffic_weight(self):
        self.bindings["portfolio-service"]["trafficWeight"] = 0
        self._one_error("ready revision traffic weight", self._errors())

    def test_rejects_a_before_snapshot_that_does_not_name_the_prior_revision(self):
        # Without the prior revision name there is no before/after evidence to bind to:
        # "did not produce a new ready revision" cannot be evaluated, so any ready
        # revision would look new and the check would silently pass.
        self.before["portfolio-service"] = {"image": self.OLD_IMAGE}
        self._one_error("before snapshot does not name", self._errors())

    def test_rejects_deploy_that_produced_no_new_ready_revision(self):
        self.after["portfolio-service"] = _app("ps-old", self.IMAGE)
        self.bindings["portfolio-service"] = _binding("ps-old", self.IMAGE)
        self._one_error("did not produce a new ready revision", self._errors())

    def test_allows_a_no_op_rerun_whose_prior_image_already_matched(self):
        # Re-running a deploy that changes nothing creates no revision. That is honest only
        # when the image already in place is the one expected.
        self.before["portfolio-service"] = _app("ps-old", self.IMAGE)
        self.after["portfolio-service"] = _app("ps-old", self.IMAGE)
        self.bindings["portfolio-service"] = _binding("ps-old", self.IMAGE)
        self.assertEqual(self._errors(), [])

    def test_checks_every_selected_service(self):
        market_image = "wealthprodacr.azurecr.io/market-data-service@" + self.DIGEST
        self.before["market-data-service"] = _app("md-old", self.OLD_IMAGE)
        self.after["market-data-service"] = _app("md-new", market_image)
        self.bindings["market-data-service"] = _binding("md-new", self.OLD_IMAGE)
        self.expected["market-data-service"] = market_image
        errors = self._errors(selected=["portfolio-service", "market-data-service"])
        self._one_error("market-data-service", errors)

    def test_ignores_unselected_services(self):
        self.before["insight-service"] = _app("is-old", self.OLD_IMAGE)
        self.after["insight-service"] = _app("is-old", self.OLD_IMAGE)
        self.assertEqual(self._errors(), [])


class _FakeAz:
    """Records the az argument vectors and replays canned results in order."""

    def __init__(self, results: list[tuple[int, str]]):
        self.results = list(results)
        self.calls: list[list[str]] = []

    def __call__(self, args: list[str]):
        self.calls.append(list(args))
        code, stdout = self.results.pop(0)
        return subprocess.CompletedProcess(
            args=["az", *args], returncode=code, stdout=stdout, stderr="" if code == 0 else stdout
        )


class TestCaptureBindings(unittest.TestCase):
    """The read side: two read-only `az ... show` calls per selected app, nothing else."""

    IMAGE = "wealthprodacr.azurecr.io/portfolio-service@sha256:" + "c" * 64

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_reads_ready_revision_then_that_revisions_detail(self):
        az = _FakeAz(
            [
                (0, json.dumps({"readyRevision": "ps-new"})),
                (
                    0,
                    json.dumps(
                        {
                            "revisionImage": self.IMAGE,
                            "active": True,
                            "provisioningState": "Provisioned",
                            "trafficWeight": 100,
                        }
                    ),
                ),
            ]
        )
        bindings = self.mod.capture_bindings("rg", ["portfolio-service"], az)

        self.assertEqual(
            bindings,
            {
                "portfolio-service": {
                    "readyRevision": "ps-new",
                    "revisionImage": self.IMAGE,
                    "active": True,
                    "provisioningState": "Provisioned",
                    "trafficWeight": 100,
                }
            },
        )
        self.assertEqual(len(az.calls), 2)
        self.assertEqual(az.calls[0][:2], ["containerapp", "show"])
        # --name is the container app; --revision names the revision (verified against the
        # az CLI reference for `az containerapp revision show`).
        self.assertEqual(az.calls[1][:3], ["containerapp", "revision", "show"])
        self.assertIn("--revision", az.calls[1])
        self.assertEqual(az.calls[1][az.calls[1].index("--revision") + 1], "ps-new")
        self.assertEqual(az.calls[1][az.calls[1].index("--name") + 1], "portfolio-service")

    def test_issues_only_read_only_show_calls(self):
        az = _FakeAz(
            [
                (0, json.dumps({"readyRevision": "ps-new"})),
                (0, json.dumps({"revisionImage": self.IMAGE, "active": True,
                                "provisioningState": "Provisioned", "trafficWeight": 100})),
            ]
        )
        self.mod.capture_bindings("rg", ["portfolio-service"], az)
        for call in az.calls:
            self.assertEqual(call[-1], "json")
            self.assertTrue(
                call[:2] == ["containerapp", "show"] or call[:3] == ["containerapp", "revision", "show"],
                call,
            )

    def test_skips_the_revision_read_when_no_revision_is_ready(self):
        az = _FakeAz([(0, json.dumps({"readyRevision": None}))])
        bindings = self.mod.capture_bindings("rg", ["portfolio-service"], az)
        self.assertEqual(bindings["portfolio-service"], {"readyRevision": ""})
        self.assertEqual(len(az.calls), 1)

    def test_reports_an_unreadable_app(self):
        az = _FakeAz([(1, "app not found")])
        bindings = self.mod.capture_bindings("rg", ["portfolio-service"], az)
        self.assertTrue(bindings["portfolio-service"]["missing"])
        self.assertIn("app not found", bindings["portfolio-service"]["error"])

    def test_reports_an_unreadable_revision(self):
        az = _FakeAz([(0, json.dumps({"readyRevision": "ps-new"})), (1, "revision gone")])
        bindings = self.mod.capture_bindings("rg", ["portfolio-service"], az)
        self.assertTrue(bindings["portfolio-service"]["missing"])
        self.assertIn("revision gone", bindings["portfolio-service"]["error"])

    def test_captures_only_selected_services(self):
        az = _FakeAz(
            [
                (0, json.dumps({"readyRevision": "ps-new"})),
                (0, json.dumps({"revisionImage": self.IMAGE, "active": True,
                                "provisioningState": "Provisioned", "trafficWeight": 100})),
            ]
        )
        bindings = self.mod.capture_bindings("rg", ["portfolio-service"], az)
        self.assertEqual(set(bindings), {"portfolio-service"})

    def test_rejects_an_unknown_or_empty_selection(self):
        for selected in ([], ["nope"], ["portfolio-service", "portfolio-service"]):
            with self.subTest(selected=selected):
                with self.assertRaises(ValueError):
                    self.mod.capture_bindings("rg", selected, _FakeAz([]))


class TestCompareCommandBindsTraffic(unittest.TestCase):
    """The `compare` command must fail a deploy whose traffic never moved.

    Without this, the scoped job passes on the template image alone — the gap that made
    the earlier B3 "serving proof" a provisioning proof.
    """

    DIGEST = "sha256:" + "c" * 64
    IMAGE = "repo/portfolio-service@" + DIGEST
    OLD_IMAGE = "repo/portfolio-service@sha256:" + "d" * 64

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_expected_images_resolves_manifest_digest_and_sha_forms(self):
        before = {"portfolio-service": _app("ps-old", self.OLD_IMAGE)}
        selected = ["portfolio-service"]
        self.assertEqual(
            self.mod.expected_images(
                before, selected, None, None, {"portfolio-service": self.DIGEST}
            ),
            {"portfolio-service": self.IMAGE},
        )
        self.assertEqual(
            self.mod.expected_images(before, selected, None, self.DIGEST, None),
            {"portfolio-service": self.IMAGE},
        )
        self.assertEqual(
            self.mod.expected_images(before, selected, "abc123", None, None),
            {"portfolio-service": "repo/portfolio-service:abc123"},
        )
        self.assertEqual(self.mod.expected_images(before, selected, None, None, None), {})

    def _run_compare(self, after, bindings):
        before = {"portfolio-service": _app("ps-old", self.OLD_IMAGE)}
        argv = [
            "snapshot_container_apps.py",
            "compare",
            "--before",
            json.dumps(before),
            "--selected",
            '["portfolio-service"]',
            "--requested-digest",
            self.DIGEST,
        ]
        with mock.patch.dict(os.environ, {"AZURE_RG": "rg"}), mock.patch.object(
            self.mod, "capture", return_value=after
        ), mock.patch.object(
            self.mod, "capture_bindings", return_value=bindings
        ), mock.patch.object(
            sys, "argv", argv
        ):
            return self.mod.main()

    def test_fails_when_the_template_is_updated_but_traffic_stayed_on_the_old_revision(self):
        # Exactly what `compare` alone cannot see: the app's template names this run's
        # digest, so the existing image assertion passes, yet requests still reach ps-old.
        after = {"portfolio-service": {**_app("ps-old", self.IMAGE), "revision": "ps-new"}}
        bindings = {"portfolio-service": _binding("ps-new", self.IMAGE)}
        self.assertEqual(self._run_compare(after, bindings), 1)

    def test_passes_when_the_new_ready_revision_holds_all_traffic(self):
        after = {"portfolio-service": _app("ps-new", self.IMAGE)}
        bindings = {"portfolio-service": _binding("ps-new", self.IMAGE)}
        self.assertEqual(self._run_compare(after, bindings), 0)


if __name__ == "__main__":
    unittest.main()
