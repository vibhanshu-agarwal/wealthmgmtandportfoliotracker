#!/usr/bin/env python3
"""Structural contract for deploy-azure.yml Wave P P-A (service allowlist).

These assertions exist so a workflow that rebuilds every service, or that
lets seed/verify run on a scoped dispatch, fails in CI rather than at the
P-A.5 STOP/GO. Stdlib only — no PyYAML.
"""

from __future__ import annotations

import re
import tempfile
import importlib.util
import unittest
from unittest import mock
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github" / "workflows" / "deploy-azure.yml"
DISPATCHER = REPO / ".github" / "workflows" / "deploy.yml"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class TestDeployAzureServiceAllowlist(unittest.TestCase):
    def test_normalized_current_attempt_artifacts_feed_real_aggregate_cli(self):
        script = REPO / ".github" / "workflows" / "scripts" / "snapshot_container_apps.py"
        spec = importlib.util.spec_from_file_location("snapshot_cli", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        digest = "sha256:" + "a" * 64
        with tempfile.TemporaryDirectory() as root:
            download = Path(root) / "downloads" / "service-digest-api-gateway"
            download.mkdir(parents=True)
            (download / "digest.txt").write_text(digest)
            (download / "run-attempt.txt").write_text("2\n")
            module.normalize_digest_artifacts(str(Path(root) / "downloads"), str(Path(root) / "stage"), ["api-gateway"], "2")
            self.assertEqual(module.aggregate_digests(str(Path(root) / "stage"), ["api-gateway"], None), {"api-gateway": digest})
            stage = Path(root) / "stage" / "api-gateway"
            (stage / "extra.txt").write_text("x")
            with self.assertRaises(ValueError): module.aggregate_digests(str(Path(root) / "stage"), ["api-gateway"], None)

    def test_normalizer_rejects_missing_extra_stale_marker_malformed_and_same_root(self):
        spec = importlib.util.spec_from_file_location("snapshot_cli", REPO / ".github" / "workflows" / "scripts" / "snapshot_container_apps.py")
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        digest = "sha256:" + "b" * 64
        cases = ("missing", "extra", "stale", "marker", "malformed", "same-root")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as root:
                download = Path(root) / "download"; download.mkdir()
                service = download / "service-digest-api-gateway"; service.mkdir()
                if case != "missing": (service / "digest.txt").write_text(digest if case != "malformed" else "SHA256:bad")
                if case != "marker": (service / "run-attempt.txt").write_text("1" if case == "stale" else "2")
                if case == "extra": (download / "service-digest-extra").mkdir()
                if case == "same-root":
                    with self.assertRaises(ValueError): module.normalize_digest_artifacts(str(download), str(download), ["api-gateway"], "2")
                else:
                    with self.assertRaises(ValueError):
                        module.normalize_digest_artifacts(str(download), str(Path(root) / "stage"), ["api-gateway"], "2")

    def test_normalize_artifacts_main_cli_valid_and_all_failures(self):
        spec = importlib.util.spec_from_file_location("snapshot_cli", REPO / ".github" / "workflows" / "scripts" / "snapshot_container_apps.py")
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        digest = "sha256:" + "c" * 64
        for case in ("valid", "missing", "extra", "stale", "marker", "malformed", "inner-extra"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as root:
                download = Path(root) / "download"; service = download / "service-digest-api-gateway"; service.mkdir(parents=True)
                if case != "missing": (service / "digest.txt").write_text(digest if case != "malformed" else "bad")
                if case != "marker": (service / "run-attempt.txt").write_text("2" if case != "stale" else "1")
                if case == "extra": (download / "service-digest-extra").mkdir()
                if case == "inner-extra": (service / "extra.txt").write_text("x")
                stage = Path(root) / "stage"
                with mock.patch("sys.argv", ["snapshot", "normalize-artifacts", "--digest-root", str(download), "--staging-root", str(stage), "--selected", '["api-gateway"]', "--run-attempt", "2"]):
                    if case == "valid":
                        self.assertEqual(module.main(), 0)
                        self.assertEqual((stage / "api-gateway" / "digest.txt").read_text(), digest + "\n")
                        self.assertEqual(module.aggregate_digests(str(stage), ["api-gateway"], None), {"api-gateway": digest})
                    else:
                        with self.assertRaises(ValueError): module.main()
    def test_scoped_graph_has_job_step_scoped_digest_contract(self):
        deploy = self._job("deploy:")
        aggregate = self._job("aggregate-digests:")
        self.assertRegex(self.text, r"concurrency:\s*\n\s*group:\s*wealth-production-azure-deploy\s*\n\s*cancel-in-progress:\s*false")
        self.assertRegex(deploy, r"name:\s*Build Docker image[\s\S]*?docker buildx build[\s\S]*?--push[\s\S]*?--metadata-file")
        self.assertIn("service-digest-${{ matrix.service }}", deploy)
        self.assertRegex(aggregate, r"needs:\s*\[preflight, deploy\]")
        self.assertIn("aggregate-digests", aggregate)
        self.assertIn("run-attempt.txt", aggregate)
        self.assertIn("Re-run all jobs", self.text)
        self.assertIn("merge-multiple: false", aggregate)
        self.assertIn("normalize-artifacts", aggregate)
        self.assertIn("--digest-root \"$RUNNER_TEMP/service-digest-downloads\"", aggregate)
        self.assertIn("--staging-root \"$RUNNER_TEMP/service-digests\"", aggregate)
        consumer = self._job("assert-scoped-non-interference:")
        self.assertIn("needs.aggregate-digests.result", consumer)
        self.assertIn("--digest-manifest", consumer)
    @classmethod
    def setUpClass(cls):
        cls.text = _read(WORKFLOW)
        cls.dispatcher = _read(DISPATCHER)

    def test_workflow_call_declares_services_input_defaulting_empty(self):
        call = self._block("workflow_call:")
        self.assertIn("services:", call)
        self.assertRegex(call, r"services:[\s\S]*?default:\s*\"\"")

    def test_has_no_standalone_workflow_dispatch(self):
        # Spec A checkpoint-9.8 incident: a standalone workflow_dispatch here was a
        # second, unvalidated entry point to production, bypassing deploy.yml's
        # expected_main_sha/deployment_mode guards and its production Environment gate.
        self.assertNotIn("workflow_dispatch:", self.text)

    def test_dispatcher_passes_services_and_prebuilt_digest_through(self):
        azure_job = re.search(
            r"deploy-azure:\s*\n(?:.*\n)*?    secrets: inherit",
            self.dispatcher,
        )
        self.assertIsNotNone(azure_job)
        self.assertIn("services: ${{ inputs.services }}", azure_job.group(0))
        self.assertIn("prebuilt_digest: ${{ inputs.prebuilt_digest }}", azure_job.group(0))

    def test_preflight_emits_deploy_mode_and_selected_services(self):
        self.assertIn("deploy_mode:", self.text)
        self.assertIn("selected_services:", self.text)

    def test_deploy_matrix_comes_from_preflight_selection_not_a_hardcoded_list(self):
        self.assertIn(
            "fromJSON(needs.preflight.outputs.selected_services)",
            self.text,
        )
        deploy = self._job("deploy:")
        hardcoded = re.search(
            r"strategy:\s*\n\s*matrix:\s*\n\s*service:\s*\n(?:\s*-\s+\S+\s*\n){4}",
            deploy,
        )
        self.assertIsNone(
            hardcoded,
            "deploy matrix must not hardcode the four-service list; "
            "unselected services must not get a job",
        )

    def test_downstream_jobs_are_explicitly_full_mode_only(self):
        for job in ("deploy-frontend:", "seed:", "verify:"):
            job_if = self._job_if(job)
            self.assertIn(
                "needs.preflight.outputs.deploy_mode == 'full'",
                job_if,
                f"{job} must skip unless deploy_mode is full",
            )
            self.assertNotIn("always()", job_if)

    def test_market_data_refresh_job_still_belongs_to_market_data_selection(self):
        self.assertIn("if: matrix.service == 'market-data-service'", self.text)
        self.assertIn("Update market-data-refresh Job image", self.text)

    def test_unselected_services_are_not_redeployed_by_tag(self):
        deploy = self._job("deploy:")
        self.assertIn("az containerapp update", deploy)
        self.assertIn("Build Docker image", deploy)

    def test_scoped_non_interference_job_records_skipped_conclusions(self):
        body = self._job("assert-scoped-non-interference:")
        self.assertIn("always()", body)
        self.assertIn("needs.preflight.outputs.deploy_mode == 'scoped'", body)
        for job in ("deploy-frontend", "seed", "verify"):
            self.assertIn(f"needs.{job}.result", body)
            self.assertIn("'skipped'", body)

    def _block(self, heading: str) -> str:
        idx = self.text.find(heading)
        self.assertGreaterEqual(idx, 0, f"missing {heading}")
        return self.text[idx : idx + 800]

    def _job(self, heading: str) -> str:
        pattern = rf"^  {re.escape(heading)}\n"
        match = re.search(pattern, self.text, re.MULTILINE)
        self.assertIsNotNone(match, f"missing job {heading}")
        start = match.start()
        nxt = re.search(r"^  [a-zA-Z0-9_-]+:\s*$", self.text[start + 1 :], re.MULTILINE)
        end = start + 1 + nxt.start() if nxt else len(self.text)
        return self.text[start:end]

    def _job_if(self, heading: str) -> str:
        body = self._job(heading)
        match = re.search(
            r"^\s+if:\s*(?:>-?\s*)?(.*?)(?=^\s+(?:env|steps|defaults|permissions|outputs|runs-on|needs|timeout-minutes|continue-on-error):)",
            body,
            re.MULTILINE | re.DOTALL,
        )
        self.assertIsNotNone(match, f"{heading} has no job-level if:")
        return match.group(1)


if __name__ == "__main__":
    unittest.main()
