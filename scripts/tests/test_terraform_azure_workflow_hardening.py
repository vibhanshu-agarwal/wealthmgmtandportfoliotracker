#!/usr/bin/env python3
"""Structural contract for terraform-azure.yml's live-state hardening (Spec A checkpoint-9.9
prerequisite).

Mirrors test_deploy_pipeline_hardening.py's discipline for the Terraform pipeline: remote-plan
and apply must be dispatch-validated, must require explicit deployed_image_tags_json (never silently
default TF_VAR_image_tags to the dispatch commit's own SHA), and apply must sit behind the
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
PROFILE_9_11_ASSERT_SCRIPT = (
    REPO / "infrastructure" / "terraform" / "azure" / "scripts" / "assert_spec_a_9_11_plan.py"
)
PROFILE_9_12_ASSERT_SCRIPT = (
    REPO / "infrastructure" / "terraform" / "azure" / "scripts" / "assert_spec_a_9_12_plan.py"
)
PROFILE_9_13_ASSERT_SCRIPT = (
    REPO / "infrastructure" / "terraform" / "azure" / "scripts" / "assert_spec_a_9_13_plan.py"
)


class TestTerraformAzureWorkflowHardening(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")

    # -- inputs -----------------------------------------------------------------

    def test_action_input_has_remote_plan_option(self):
        block = self._block("action:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*remote-plan")

    def test_expected_main_sha_and_deployed_image_tags_json_inputs_exist(self):
        self.assertIn("expected_main_sha:", self.text)
        self.assertIn("deployed_image_tags_json:", self.text)
        self.assertNotIn("deployed_image_tag:", self.text)

    def test_change_profile_input_has_both_9_9_profiles(self):
        block = self._block("change_profile:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*standard")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.9-enable")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.9-abort")

    def test_change_profile_input_has_both_9_11_profiles(self):
        block = self._block("change_profile:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.11-enable")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.11-abort")

    def test_change_profile_input_has_both_9_12_profiles(self):
        block = self._block("change_profile:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.12-enable")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.12-disable")

    def test_change_profile_input_has_both_9_12_tx_diag_profiles(self):
        block = self._block("change_profile:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.12-tx-diag-enable")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.12-tx-diag-disable")

    def test_change_profile_input_has_9_13_restore_scale_profile(self):
        block = self._block("change_profile:")
        self.assertRegex(block, r"options:[\s\S]*?-\s*spec-a-9\.13-restore-scale")
        self.assertIn("spec-a-9.13-restore-scale", block)

    def test_expected_portfolio_image_digest_input_exists_as_optional_string(self):
        block = self._block("expected_portfolio_image_digest:")
        self.assertIn("required: false", block)
        self.assertIn("type: string", block)

    # -- validate-dispatch job -----------------------------------------------------

    def test_validate_dispatch_job_exists_and_runs_for_live_state_ops(self):
        job = self._job("validate-dispatch:")
        self.assertIn("remote-plan", job)
        self.assertIn("apply", job)
        self.assertIn("validate_dispatch.py", job)

    def test_validate_dispatch_job_exports_canonical_image_tags_json(self):
        job = self._job("validate-dispatch:")
        self.assertIn("outputs:", job)
        self.assertIn(
            "canonical_image_tags_json: ${{ steps.validate-dispatch.outputs.canonical_image_tags_json }}",
            job,
        )
        self.assertIn("id: validate-dispatch", job)

    def test_validate_dispatch_job_exports_canonical_image_digests_json(self):
        job = self._job("validate-dispatch:")
        self.assertIn(
            "canonical_image_digests_json: ${{ steps.confirm-image-tags.outputs.canonical_image_digests_json }}",
            job,
        )
        self.assertIn("id: confirm-image-tags", job)

    def test_validate_dispatch_passes_ref_sha_profile_and_seed_flags(self):
        job = self._job("validate-dispatch:")
        for var in (
            "ACTUAL_REF: ${{ github.ref }}",
            "ACTUAL_SHA: ${{ github.sha }}",
            "EXPECTED_MAIN_SHA: ${{ github.event.inputs.expected_main_sha }}",
            "DEPLOYED_IMAGE_TAGS_JSON: ${{ github.event.inputs.deployed_image_tags_json }}",
            "EXPECTED_PORTFOLIO_IMAGE_DIGEST: ${{ github.event.inputs.expected_portfolio_image_digest }}",
            "CHANGE_PROFILE: ${{ github.event.inputs.change_profile }}",
            "USE_SEED_IMAGE: ${{ github.event.inputs.use_seed_image }}",
            "RECREATE_MARKET_DATA_JOB: ${{ github.event.inputs.recreate_market_data_job }}",
        ):
            self.assertIn(var, job)

    def test_validate_dispatch_acr_check_uses_canonical_output_not_raw_input(self):
        job = self._job("validate-dispatch:")
        self.assertIn("CANONICAL_IMAGE_TAGS_JSON: ${{ steps.validate-dispatch.outputs.canonical_image_tags_json }}", job)
        acr_step = job[job.find("Confirm deployed_image_tags_json resolves in ACR") :]
        self.assertNotIn("github.event.inputs.deployed_image_tags_json", acr_step[acr_step.find("CANONICAL_IMAGE_TAGS_JSON") :])

    def test_validate_dispatch_confirms_image_tags_in_acr_per_service(self):
        job = self._job("validate-dispatch:")
        self.assertIn("az acr manifest list-metadata", job)
        for repo in (
            "api-gateway",
            "portfolio-service",
            "market-data-service",
            "insight-service",
        ):
            self.assertIn(repo, job)

    def test_acr_tag_check_exports_resolved_manifest_digest_map(self):
        job = self._job("validate-dispatch:")
        acr_step = job[job.find("Confirm deployed_image_tags_json resolves in ACR") :]
        self.assertIn("[?tags != null && contains(tags, '{tag}')].digest", acr_step)
        self.assertIn("canonical_image_digests_json<<EOF", acr_step)
        self.assertIn("GITHUB_OUTPUT", acr_step)
        self.assertNotIn("length([?tags != null && contains(tags, '{tag}')])", acr_step)

    def test_acr_check_skipped_only_for_standard_seed_bootstrap(self):
        # standard + use_seed_image=true is the first-provisioning path where
        # deployed_image_tags_json is meaningless — every other combination (both 9.9
        # profiles, ordinary non-seed applies, Job-only recovery) must still require it.
        job = self._job("validate-dispatch:")
        idx = job.find("Confirm deployed_image_tags_json resolves in ACR")
        self.assertGreaterEqual(idx, 0)
        if_line = re.search(r"if:\s*'([^\n]*)'\n", job[idx:])
        self.assertIsNotNone(if_line, "expected a quoted if: condition on this step")
        condition = if_line.group(1)
        self.assertIn("change_profile", condition)
        self.assertIn("'standard'", condition)
        self.assertIn("use_seed_image", condition)
        self.assertIn("'true'", condition)

    def test_9_12_digest_is_resolved_exactly_once_after_azure_login(self):
        job = self._job("validate-dispatch:")
        login_index = job.find("Azure Login (OIDC)")
        digest_index = job.find("Confirm expected_portfolio_image_digest resolves in ACR")
        self.assertGreater(digest_index, login_index)
        step = job[digest_index : digest_index + 1200]
        self.assertIn("spec-a-9.12-enable", step)
        self.assertIn("spec-a-9.12-disable", step)
        self.assertIn("spec-a-9.12-tx-diag-enable", step)
        self.assertIn("spec-a-9.12-tx-diag-disable", step)
        self.assertIn("spec-a-9.13-restore-scale", step)
        self.assertIn("az acr manifest list-metadata", step)
        self.assertIn("--registry wealthprodacr", step)
        self.assertIn("--name portfolio-service", step)
        self.assertIn('if [ "$COUNT" != "1" ]', step)
        self.assertNotIn("show-password", step)

    def test_demo_seed_mapping_is_profile_derived_and_fail_closed(self):
        self.assertIn(
            "TF_VAR_demo_seed_on_startup: ${{ github.event.inputs.change_profile == 'spec-a-9.12-enable' && 'true' || 'false' }}",
            self.text,
        )
        self.assertIn(
            "TF_VAR_demo_tx_diagnostics: ${{ github.event.inputs.change_profile == 'spec-a-9.12-tx-diag-enable' && 'true' || 'false' }}",
            self.text,
        )
        self.assertNotRegex(
            self.text,
            r"(?m)^\s+demo_seed_on_startup:\s*$",
        )

    def test_structural_plan_does_not_set_live_state_image_tags(self):
        self.assertNotIn("TF_VAR_image_tag:", self.text)
        pr_plan = self._job("pr-plan:")
        self.assertNotIn("TF_VAR_image_tags:", pr_plan)

    # -- apply job: needs + environment gate ----------------------------------------

    def test_apply_job_needs_validate_dispatch(self):
        job = self._job("apply:")
        self.assertIn("needs: validate-dispatch", job)

    def test_apply_job_gated_by_production_environment(self):
        job = self._job("apply:")
        self.assertIn("environment: production", job)

    def test_apply_job_is_not_a_reusable_workflow_call(self):
        job = self._job("apply:")
        self.assertNotRegex(job, r"(?m)^    uses:")

    def test_apply_job_overrides_image_tags_from_canonical_output(self):
        job = self._job("apply:")
        self.assertIn(
            "TF_VAR_image_tags: ${{ needs.validate-dispatch.outputs.canonical_image_tags_json }}",
            job,
        )
        self.assertNotIn("github.event.inputs.deployed_image_tags_json", job.split("TF_VAR_image_tags")[1][:120])

    def test_apply_job_invokes_profile_guard_unconditionally_for_every_profile(self):
        job = self._job("apply:")
        self.assertIn(
            'assert_spec_a_9_9_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" --expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"',
            job,
        )
        self.assertIn("EXPECTED_IMAGE_TAGS_JSON: ${{ needs.validate-dispatch.outputs.canonical_image_tags_json }}", job)
        self.assertIn('--expected-image-digest "$EXPECTED_PORTFOLIO_IMAGE_DIGEST"', job)
        self.assertIn(
            "EXPECTED_PORTFOLIO_IMAGE_DIGEST: ${{ github.event.inputs.expected_portfolio_image_digest }}",
            job,
        )

    def test_apply_job_invokes_9_11_profile_guard(self):
        job = self._job("apply:")
        self.assertIn(
            'assert_spec_a_9_11_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" --expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"',
            job,
        )

    def test_apply_job_invokes_9_12_guard_after_earlier_guards(self):
        job = self._job("apply:")
        guard_9_9 = job.find("assert_spec_a_9_9_plan.py")
        guard_9_11 = job.find("assert_spec_a_9_11_plan.py")
        guard_9_12 = job.find(
            'assert_spec_a_9_12_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" '
            '--expected-image-digest "$EXPECTED_PORTFOLIO_IMAGE_DIGEST" '
            '--expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"'
        )
        self.assertGreater(guard_9_9, -1)
        self.assertGreater(guard_9_11, guard_9_9)
        self.assertGreater(guard_9_12, guard_9_11)
        self.assertNotIn("deployed_image_tags_json }}'", job)

    def test_apply_job_invokes_9_13_guard_after_earlier_guards(self):
        job = self._job("apply:")
        guard_9_12 = job.find("assert_spec_a_9_12_plan.py")
        guard_9_13 = job.find(
            'assert_spec_a_9_13_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" '
            '--expected-image-digest "$EXPECTED_PORTFOLIO_IMAGE_DIGEST" '
            '--expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"'
        )
        self.assertGreater(guard_9_12, -1)
        self.assertGreater(guard_9_13, guard_9_12)
        self.assertIn("EXPECTED_IMAGE_TAGS_JSON: ${{ needs.validate-dispatch.outputs.canonical_image_tags_json }}", job)
        self.assertIn("EXPECTED_IMAGE_DIGESTS_JSON: ${{ needs.validate-dispatch.outputs.canonical_image_digests_json }}", job)
        self.assertIn('--expected-image-digests-json "$EXPECTED_IMAGE_DIGESTS_JSON"', job)

    def test_job_import_step_is_read_only_for_9_9_profiles(self):
        job = self._job("apply:")
        import_step = job[job.find("Import existing market-data refresh Job") :]
        import_step = import_step[: import_step.find("\n\n      - name:")]
        self.assertIn("spec-a-9.9-enable", import_step)
        self.assertIn("spec-a-9.9-abort", import_step)
        self.assertIn("exit 1", import_step)
        self.assertIn("terraform import", import_step)

    def test_job_import_step_is_read_only_for_9_11_profiles(self):
        job = self._job("apply:")
        import_step = job[job.find("Import existing market-data refresh Job") :]
        import_step = import_step[: import_step.find("\n\n      - name:")]
        self.assertIn("spec-a-9.11-enable", import_step)
        self.assertIn("spec-a-9.11-abort", import_step)
        self.assertIn("exit 1", import_step)

    def test_job_import_step_is_read_only_for_9_12_profiles(self):
        job = self._job("apply:")
        import_step = job[job.find("Import existing market-data refresh Job") :]
        import_step = import_step[: import_step.find("\n\n      - name:")]
        self.assertIn("spec-a-9.12-enable", import_step)
        self.assertIn("spec-a-9.12-disable", import_step)
        self.assertIn("spec-a-9.12-tx-diag-enable", import_step)
        self.assertIn("spec-a-9.12-tx-diag-disable", import_step)
        self.assertIn("exit 1", import_step)

    def test_job_import_step_is_read_only_for_9_13_profile(self):
        job = self._job("apply:")
        import_step = job[job.find("Import existing market-data refresh Job") :]
        import_step = import_step[: import_step.find("\n\n      - name:")]
        self.assertIn("spec-a-9.13-restore-scale", import_step)
        self.assertIn("exit 1", import_step)

    # -- remote-plan job -----------------------------------------------------------

    def test_remote_plan_job_needs_validate_dispatch(self):
        job = self._job("remote-plan:")
        self.assertIn("needs: validate-dispatch", job)

    def test_remote_plan_uses_real_backend_not_local_override(self):
        job = self._job("remote-plan:")
        self.assertIn("backend-azure.hcl", job)
        self.assertNotIn('backend "local"', job)

    def test_remote_plan_overrides_image_tags_from_canonical_output(self):
        job = self._job("remote-plan:")
        self.assertIn(
            "TF_VAR_image_tags: ${{ needs.validate-dispatch.outputs.canonical_image_tags_json }}",
            job,
        )

    def test_remote_plan_invokes_profile_guard_unconditionally_for_every_profile(self):
        job = self._job("remote-plan:")
        self.assertIn(
            'assert_spec_a_9_9_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" --expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"',
            job,
        )
        self.assertIn("EXPECTED_IMAGE_TAGS_JSON: ${{ needs.validate-dispatch.outputs.canonical_image_tags_json }}", job)
        self.assertIn('--expected-image-digest "$EXPECTED_PORTFOLIO_IMAGE_DIGEST"', job)
        self.assertIn(
            "EXPECTED_PORTFOLIO_IMAGE_DIGEST: ${{ github.event.inputs.expected_portfolio_image_digest }}",
            job,
        )

    def test_remote_plan_invokes_9_11_profile_guard(self):
        job = self._job("remote-plan:")
        self.assertIn(
            'assert_spec_a_9_11_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" --expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"',
            job,
        )

    def test_remote_plan_invokes_9_12_guard_after_earlier_guards(self):
        job = self._job("remote-plan:")
        guard_9_9 = job.find("assert_spec_a_9_9_plan.py")
        guard_9_11 = job.find("assert_spec_a_9_11_plan.py")
        guard_9_12 = job.find(
            'assert_spec_a_9_12_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" '
            '--expected-image-digest "$EXPECTED_PORTFOLIO_IMAGE_DIGEST" '
            '--expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"'
        )
        self.assertGreater(guard_9_9, -1)
        self.assertGreater(guard_9_11, guard_9_9)
        self.assertGreater(guard_9_12, guard_9_11)
        self.assertNotIn("deployed_image_tags_json }}'", job)

    def test_remote_plan_invokes_9_13_guard_after_earlier_guards(self):
        job = self._job("remote-plan:")
        guard_9_12 = job.find("assert_spec_a_9_12_plan.py")
        guard_9_13 = job.find(
            'assert_spec_a_9_13_plan.py tfplan.json --profile "${{ github.event.inputs.change_profile }}" '
            '--expected-image-digest "$EXPECTED_PORTFOLIO_IMAGE_DIGEST" '
            '--expected-image-tags-json "$EXPECTED_IMAGE_TAGS_JSON"'
        )
        self.assertGreater(guard_9_12, -1)
        self.assertGreater(guard_9_13, guard_9_12)
        self.assertIn("EXPECTED_IMAGE_DIGESTS_JSON: ${{ needs.validate-dispatch.outputs.canonical_image_digests_json }}", job)
        self.assertIn('--expected-image-digests-json "$EXPECTED_IMAGE_DIGESTS_JSON"', job)

    def test_remote_plan_never_applies(self):
        job = self._job("remote-plan:")
        self.assertNotIn("terraform apply", job)

    def test_remote_plan_does_not_upload_raw_plan_files(self):
        job = self._job("remote-plan:")
        self.assertNotIn("upload-artifact", job)
        self.assertIn("GITHUB_STEP_SUMMARY", job)

    def test_remote_plan_summary_uses_summarize_plan_not_raw_terraform_show(self):
        job = self._job("remote-plan:")
        self.assertIn("summarize_plan.py", job)
        self.assertNotIn("terraform show -no-color tfplan | grep", job)

    def test_summarize_plan_script_exists(self):
        script = REPO / "infrastructure" / "terraform" / "azure" / "scripts" / "summarize_plan.py"
        self.assertTrue(script.is_file())

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

    def test_pr_plan_job_never_touches_9_11_profile_assertion(self):
        job = self._job("pr-plan:")
        self.assertNotIn("assert_spec_a_9_11_plan.py", job)

    def test_pr_plan_job_never_touches_9_12_profile_assertion(self):
        job = self._job("pr-plan:")
        self.assertNotIn("assert_spec_a_9_12_plan.py", job)

    def test_pr_plan_job_never_touches_9_13_profile_assertion(self):
        job = self._job("pr-plan:")
        self.assertNotIn("assert_spec_a_9_13_plan.py", job)

    # -- scripts exist ------------------------------------------------------------

    def test_validate_dispatch_script_exists(self):
        self.assertTrue(VALIDATE_SCRIPT.is_file())

    def test_profile_assertion_script_exists(self):
        self.assertTrue(PROFILE_ASSERT_SCRIPT.is_file())

    def test_9_11_profile_assertion_script_exists(self):
        self.assertTrue(PROFILE_9_11_ASSERT_SCRIPT.is_file())

    def test_9_12_profile_assertion_script_exists(self):
        self.assertTrue(PROFILE_9_12_ASSERT_SCRIPT.is_file())

    def test_9_13_profile_assertion_script_exists(self):
        self.assertTrue(PROFILE_9_13_ASSERT_SCRIPT.is_file())

    def test_run_blocks_do_not_interpolate_raw_portfolio_digest(self):
        raw = "${{ github.event.inputs.expected_portfolio_image_digest }}"
        scripts = self._run_scripts()
        self.assertTrue(scripts, "expected at least one run: script")
        for script in scripts:
            self.assertNotIn(raw, script)

    # -- helpers ----------------------------------------------------------------

    def _block(self, heading: str) -> str:
        idx = self.text.find(heading)
        self.assertGreaterEqual(idx, 0, f"missing {heading}")
        return self.text[idx : idx + 2000]

    def _job(self, heading: str) -> str:
        pattern = rf"^  {re.escape(heading)}\n"
        match = re.search(pattern, self.text, re.MULTILINE)
        self.assertIsNotNone(match, f"missing job {heading}")
        start = match.start()
        nxt = re.search(r"^  [a-zA-Z0-9_-]+:\s*$", self.text[start + 1 :], re.MULTILINE)
        end = start + 1 + nxt.start() if nxt else len(self.text)
        return self.text[start:end]

    def _run_scripts(self) -> list[str]:
        scripts: list[str] = []
        for match in re.finditer(r"(?m)^        run: (.+)$", self.text):
            value = match.group(1)
            if value == "|":
                start = match.end()
                block_lines: list[str] = []
                for line in self.text[start:].splitlines(keepends=True):
                    if line.startswith("          ") or line.strip() == "":
                        block_lines.append(line)
                        continue
                    break
                scripts.append("".join(block_lines))
            else:
                scripts.append(value)
        return scripts


if __name__ == "__main__":
    unittest.main()
