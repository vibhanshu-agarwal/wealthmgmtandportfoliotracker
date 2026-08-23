#!/usr/bin/env python3
"""Structural contract for the deploy-pipeline hardening (Spec A checkpoint-9.8 incident).

2026-08-23: merging a PR that touched service source paths auto-triggered deploy.yml's
push-based deploy, deploying enforcement-flag code before the Terraform override that
was supposed to shadow it existed — with no gate in between. These assertions exist so
regressions on any of the four fixes fail in CI, not in production:

  1. deploy.yml has no push trigger — workflow_dispatch is the only way in.
  2. deploy-azure.yml / deploy-aws.yml have no standalone workflow_dispatch, AND no other
     workflow file calls them via `uses:` — deploy.yml is their only entry point (proven,
     not just assumed by removing their own dispatch triggers), so its guards cover every
     path to production.
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

    def test_deploy_yml_is_the_only_caller_of_the_cloud_workflows(self):
        # "workflow_call-only" only closes the gap if nothing else can reach these files.
        # No alternate caller exists in this repo today, but the contract should assert
        # that structurally, not rely on it staying true by absence.
        workflow_dir = REPO / ".github" / "workflows"
        callers = {
            path
            for path in workflow_dir.glob("*.yml")
            if path.name not in ("deploy.yml", "deploy-azure.yml", "deploy-aws.yml")
            and re.search(
                r"uses:\s*\./\.github/workflows/deploy-(azure|aws)\.yml",
                path.read_text(encoding="utf-8"),
            )
        }
        self.assertEqual(
            callers,
            set(),
            f"deploy-azure.yml/deploy-aws.yml must only be called from deploy.yml; "
            f"found an additional caller: {callers}",
        )
        self.assertRegex(
            self.dispatcher, r"uses:\s*\./\.github/workflows/deploy-azure\.yml"
        )
        self.assertRegex(self.dispatcher, r"uses:\s*\./\.github/workflows/deploy-aws\.yml")

    # -- (3) environment gate + concurrency ------------------------------------

    def test_dispatcher_has_non_cancelling_production_concurrency_group(self):
        match = re.search(
            r"^concurrency:\s*\n\s+group:\s*(\S+)\s*\n\s+cancel-in-progress:\s*(\S+)",
            self.dispatcher,
            re.MULTILINE,
        )
        self.assertIsNotNone(match, "deploy.yml must declare a top-level concurrency group")
        self.assertEqual(match.group(2), "false")

    def test_authorize_production_job_gates_on_the_production_environment(self):
        # A job calling a reusable workflow with `uses:` cannot itself declare
        # `environment:` — not a supported keyword (confirmed with actionlint, not
        # assumed). The gate must live on a normal job instead.
        job = self._job(self.dispatcher, "authorize-production:")
        self.assertIn("environment: production", job)
        self.assertNotIn("uses:", job)

    def test_deploy_jobs_do_not_declare_environment_directly(self):
        for heading in ("deploy-azure:", "deploy-aws:"):
            job = self._job(self.dispatcher, heading)
            self.assertNotIn("environment:", job)

    def test_route_and_deploy_jobs_transitively_require_authorization(self):
        self.assertIn("needs: authorize-production", self._job(self.dispatcher, "route:"))
        self.assertIn("needs: route", self._job(self.dispatcher, "deploy-azure:"))
        self.assertIn("needs: route", self._job(self.dispatcher, "deploy-aws:"))

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

    def test_deployment_mode_defaults_to_a_rejected_sentinel(self):
        # type: choice pre-fills the dropdown with the first option. If that option were
        # a real mode (e.g. "full"), clicking Run without touching the dropdown would
        # silently deploy. The first option must be a sentinel the validator rejects.
        block = self._block(self.dispatcher, "deployment_mode:")
        options = re.search(r"options:\s*\n((?:\s+-\s+\S+\s*\n)+)", block)
        self.assertIsNotNone(options)
        first_option = options.group(1).strip().splitlines()[0].strip().lstrip("- ").strip()
        self.assertEqual(first_option, "select-deployment-mode")

    def test_validate_job_runs_before_authorize_production(self):
        self.assertIn("validate:", self.dispatcher)
        authorize_job = self._job(self.dispatcher, "authorize-production:")
        self.assertIn("needs: validate", authorize_job)

    def test_validate_job_invokes_the_validation_script_with_actual_sha_ref_and_provider(self):
        validate_job = self._job(self.dispatcher, "validate:")
        self.assertIn("validate_deploy_dispatch.py", validate_job)
        self.assertIn("ACTUAL_SHA: ${{ github.sha }}", validate_job)
        self.assertIn("ACTUAL_REF: ${{ github.ref }}", validate_job)
        self.assertIn("EXPECTED_MAIN_SHA: ${{ inputs.expected_main_sha }}", validate_job)
        self.assertIn("CLOUD_PROVIDER: ${{ vars.CLOUD_PROVIDER }}", validate_job)

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
