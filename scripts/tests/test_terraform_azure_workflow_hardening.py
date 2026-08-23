#!/usr/bin/env python3
"""Structural contract for terraform-azure.yml's live-state hardening (Spec A checkpoint-9.9
prerequisite).

Mirrors test_deploy_pipeline_hardening.py's discipline for the Terraform pipeline: remote-plan
and apply must be dispatch-validated, must require explicit deployed_image_tag (never silently
default TF_VAR_image_tag to the dispatch commit's own SHA), and apply must sit behind the
`production` Environment. Stdlib only — no PyYAML.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github" / "workflows" / "terraform-azure.yml"
VALIDATE_SCRIPT = REPO / "infrastructure" / "terraform" / "azure" / "scripts" / "validate_dispatch.py"
PROFILE_ASSERT_SCRIPT = (
    REPO / "infrastructure" / "terraform" / "azure" / "scripts" / "assert_spec_a_9_9_plan.py"
)


class TestTerraformAzureWorkflowHardening(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    # -- inputs -----------------------------------------------------------------

    def test_action_input_has_remote_plan_option(self):
        block = self._block("action:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*remote-plan")

    def test_expected_main_sha_and_deployed_image_tag_inputs_exist(self):
        self.assertIn("expected_main_sha:", self.text)
        self.assertIn("deployed_image_tag:", self.text)

    def test_change_profile_input_has_both_9_9_profiles(self):
        block = self._block("change_profile:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*standard")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.9-enable")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.9-abort")

    # -- validate-dispatch job -----------------------------------------------------

    def test_validate_dispatch_job_exists_and_runs_for_live_state_ops(self):
        job = self._job("validate-dispatch:")
        self.assertIn("remote-plan", job)
        self.assertIn("apply", job)
        self.assertIn("validate_dispatch.py", job)

    def test_validate_dispatch_passes_ref_sha_profile_and_seed_flags(self):
        job = self._job("validate-dispatch:")
        for var in (
            "ACTUAL_REF: ${{ github.ref }}",
            "ACTUAL_SHA: ${{ github.sha }}",
            "EXPECTED_MAIN_SHA: ${{ github.event.inputs.expected_main_sha }}",
            "DEPLOYED_IMAGE_TAG: ${{ github.event.inputs.deployed_image_tag }}",
            "CHANGE_PROFILE: ${{ github.event.inputs.change_profile }}",
            "USE_SEED_IMAGE: ${{ github.event.inputs.use_seed_image }}",
            "RECREATE_MARKET_DATA_JOB: ${{ github.event.inputs.recreate_market_data_job }}",
        ):
            self.assertIn(var, job)

    def test_validate_dispatch_confirms_image_tag_in_acr(self):
        job = self._job("validate-dispatch:")
        self.assertIn("az acr manifest list-metadata", job)

    # -- apply job: needs + environment gate ----------------------------------------

    def test_apply_job_needs_validate_dispatch(self):
        job = self._job("apply:")
        self.assertIn("needs: validate-dispatch", job)

    def test_apply_job_gated_by_production_environment(self):
        job = self._job("apply:")
        self.assertIn("environment: production", job)

    def test_apply_job_is_not_a_reusable_workflow_call(self):
        # environment: is only valid directly on a job like this because it has real
        # steps, not a job-level `uses:` (a reusable-workflow call). Step-level `uses:`
        # (actions/checkout@v4 etc., at deeper indentation) is fine and expected — only
        # a job-level `uses:` would make `environment:` here invalid.
        job = self._job("apply:")
        self.assertNotRegex(job, r"(?m)^    uses:")

    def test_apply_job_overrides_image_tag_from_input(self):
        job = self._job("apply:")
        self.assertIn("TF_VAR_image_tag: ${{ github.event.inputs.deployed_image_tag }}", job)

    def test_apply_job_invokes_both_9_9_profile_assertions(self):
        job = self._job("apply:")
        self.assertIn("assert_spec_a_9_9_plan.py tfplan.json --profile enable", job)
        self.assertIn("assert_spec_a_9_9_plan.py tfplan.json --profile abort", job)

    # -- remote-plan job -----------------------------------------------------------

    def test_remote_plan_job_needs_validate_dispatch(self):
        job = self._job("remote-plan:")
        self.assertIn("needs: validate-dispatch", job)

    def test_remote_plan_uses_real_backend_not_local_override(self):
        job = self._job("remote-plan:")
        self.assertIn("backend-azure.hcl", job)
        self.assertNotIn('backend "local"', job)

    def test_remote_plan_overrides_image_tag_from_input(self):
        job = self._job("remote-plan:")
        self.assertIn("TF_VAR_image_tag: ${{ github.event.inputs.deployed_image_tag }}", job)

    def test_remote_plan_invokes_both_9_9_profile_assertions(self):
        job = self._job("remote-plan:")
        self.assertIn("assert_spec_a_9_9_plan.py tfplan.json --profile enable", job)
        self.assertIn("assert_spec_a_9_9_plan.py tfplan.json --profile abort", job)

    def test_remote_plan_never_applies(self):
        job = self._job("remote-plan:")
        self.assertNotIn("terraform apply", job)

    def test_remote_plan_does_not_upload_raw_plan_files(self):
        # tfplan/tfplan.json can contain sensitive values (TF_VAR_* secrets); only a
        # sanitized address/action summary may leave the job, via $GITHUB_STEP_SUMMARY.
        job = self._job("remote-plan:")
        self.assertNotIn("upload-artifact", job)
        self.assertIn("GITHUB_STEP_SUMMARY", job)

    # -- pr-plan job: unaffected --------------------------------------------------------

    def test_pr_plan_job_still_uses_local_backend(self):
        job = self._job("pr-plan:")
        self.assertIn('backend "local"', job)

    def test_pr_plan_job_runs_all_six_original_assertions(self):
        job = self._job("pr-plan:")
        for script in (
            "assert_plan.py",
            "test_acr_pull_property.py",
            "assert_observability_plan.py",
            "assert_job_runner_env_update.py",
            "assert_ingress_enabled_plan.py",
            "assert_mongo_repair_job_plan.py",
        ):
            self.assertIn(script, job)

    def test_pr_plan_job_never_touches_9_9_profile_assertion(self):
        job = self._job("pr-plan:")
        self.assertNotIn("assert_spec_a_9_9_plan.py", job)

    # -- scripts exist ------------------------------------------------------------

    def test_validate_dispatch_script_exists(self):
        self.assertTrue(VALIDATE_SCRIPT.is_file())

    def test_profile_assertion_script_exists(self):
        self.assertTrue(PROFILE_ASSERT_SCRIPT.is_file())

    # -- helpers ----------------------------------------------------------------

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


if __name__ == "__main__":
    unittest.main()
