#!/usr/bin/env python3
"""Structural contract for the deploy-pipeline hardening (Spec A checkpoint-9.8 incident).

2026-08-23: merging a PR that touched service source paths auto-triggered deploy.yml's
push-based deploy, deploying enforcement-flag code before the Terraform override that
was supposed to shadow it existed — with no gate in between. These assertions exist so
regressions on any of the four fixes fail in CI, not in production:

  1. deploy.yml has no push trigger — workflow_dispatch is the only way in.
  2. deploy-azure.yml / deploy-aws.yml have no standalone workflow_dispatch — deploy.yml
     is their only entry point, so its guards cover every path to production.
  3. Every production-mutating call is gated behind the `production` GitHub Environment
     and a non-cancelling concurrency group (two dispatches queue, never overlap).
  4. deploy.yml requires an explicit deployment_mode and an expected_main_sha, both
     validated (validate_deploy_dispatch.py) before routing to any cloud workflow.

Stdlib only — no PyYAML, matching the sibling contract tests in this file.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DISPATCHER = REPO / ".github" / "workflows" / "deploy.yml"
DEPLOY_AZURE = REPO / ".github" / "workflows" / "deploy-azure.yml"
DEPLOY_AWS = REPO / ".github" / "workflows" / "deploy-aws.yml"
VALIDATE_SCRIPT = REPO / ".github" / "workflows" / "scripts" / "validate_deploy_dispatch.py"


class TestDeployPipelineHardening(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dispatcher = DISPATCHER.read_text(encoding="utf-8")
        cls.deploy_azure = DEPLOY_AZURE.read_text(encoding="utf-8")
        cls.deploy_aws = DEPLOY_AWS.read_text(encoding="utf-8")

    # -- (1) no push trigger on the dispatcher --------------------------------

    def test_dispatcher_has_no_push_trigger(self):
        on_block = self._on_block(self.dispatcher)
        self.assertNotIn("push:", on_block)
        self.assertIn("workflow_dispatch:", on_block)

    # -- (2) cloud workflows are workflow_call-only ---------------------------

    def test_deploy_azure_has_no_standalone_workflow_dispatch(self):
        on_block = self._on_block(self.deploy_azure)
        self.assertNotIn("workflow_dispatch:", on_block)
        self.assertIn("workflow_call:", on_block)

    def test_deploy_aws_has_no_standalone_workflow_dispatch(self):
        on_block = self._on_block(self.deploy_aws)
        self.assertNotIn("workflow_dispatch:", on_block)
        self.assertIn("workflow_call:", on_block)

    # -- (3) environment gate + concurrency ------------------------------------

    def test_dispatcher_has_non_cancelling_production_concurrency_group(self):
        match = re.search(
            r"^concurrency:\s*\n\s+group:\s*(\S+)\s*\n\s+cancel-in-progress:\s*(\S+)",
            self.dispatcher,
            re.MULTILINE,
        )
        self.assertIsNotNone(match, "deploy.yml must declare a top-level concurrency group")
        self.assertEqual(match.group(2), "false")

    def test_deploy_azure_job_requires_production_environment(self):
        job = self._job(self.dispatcher, "deploy-azure:")
        self.assertIn("environment: production", job)

    def test_deploy_aws_job_requires_production_environment(self):
        job = self._job(self.dispatcher, "deploy-aws:")
        self.assertIn("environment: production", job)

    # -- (4) required, validated dispatch inputs -------------------------------

    def test_workflow_dispatch_requires_deployment_mode_and_expected_sha(self):
        block = self._block(self.dispatcher, "workflow_dispatch:")
        self.assertRegex(
            block,
            r"deployment_mode:[\s\S]*?required:\s*true[\s\S]*?type:\s*choice",
        )
        self.assertRegex(
            block,
            r"expected_main_sha:[\s\S]*?required:\s*true",
        )

    def test_validate_job_runs_before_route_and_deploy_jobs(self):
        self.assertIn("validate:", self.dispatcher)
        route_job = self._job(self.dispatcher, "route:")
        self.assertIn("needs: validate", route_job)
        self.assertIn("needs: route", self._job(self.dispatcher, "deploy-azure:"))

    def test_validate_job_invokes_the_validation_script_with_actual_sha(self):
        validate_job = self._job(self.dispatcher, "validate:")
        self.assertIn("validate_deploy_dispatch.py", validate_job)
        self.assertIn("ACTUAL_SHA: ${{ github.sha }}", validate_job)
        self.assertIn("EXPECTED_MAIN_SHA: ${{ inputs.expected_main_sha }}", validate_job)

    def test_validation_script_exists(self):
        self.assertTrue(VALIDATE_SCRIPT.is_file())

    # -- helpers ----------------------------------------------------------------

    def _on_block(self, text: str) -> str:
        match = re.search(r"^on:\s*\n(?:(?:  .*)?\n)*", text, re.MULTILINE)
        self.assertIsNotNone(match, "missing top-level on: block")
        return match.group(0)

    def _block(self, text: str, heading: str) -> str:
        idx = text.find(heading)
        self.assertGreaterEqual(idx, 0, f"missing {heading}")
        return text[idx : idx + 1200]

    def _job(self, text: str, heading: str) -> str:
        pattern = rf"^  {re.escape(heading)}\n"
        match = re.search(pattern, text, re.MULTILINE)
        self.assertIsNotNone(match, f"missing job {heading}")
        start = match.start()
        nxt = re.search(r"^  [a-zA-Z0-9_-]+:\s*$", text[start + 1 :], re.MULTILINE)
        end = start + 1 + nxt.start() if nxt else len(text)
        return text[start:end]


if __name__ == "__main__":
    unittest.main()
