#!/usr/bin/env python3
"""Structural contract for deploy-azure.yml Wave P P-A (service allowlist).

These assertions exist so a workflow that rebuilds every service, or that
lets seed/verify run on a scoped dispatch, fails in CI rather than at the
P-A.5 STOP/GO. Stdlib only — no PyYAML.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github" / "workflows" / "deploy-azure.yml"
DISPATCHER = REPO / ".github" / "workflows" / "deploy.yml"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class TestDeployAzureServiceAllowlist(unittest.TestCase):
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
            r"deploy-azure:\s*\n(?:.*\n)*?    environment: production",
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
