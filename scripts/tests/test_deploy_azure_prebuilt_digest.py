#!/usr/bin/env python3
"""Structural contract for deploy-azure.yml Wave P P-B (prebuilt digest)."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github" / "workflows" / "deploy-azure.yml"
DISPATCHER = REPO / ".github" / "workflows" / "deploy.yml"


class TestDeployAzurePrebuiltDigest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text(encoding="utf-8")
        cls.dispatcher = DISPATCHER.read_text(encoding="utf-8")

    def test_both_triggers_declare_prebuilt_digest_defaulting_empty(self):
        for heading in ("workflow_dispatch:", "workflow_call:"):
            block = self._block(heading)
            self.assertIn("prebuilt_digest:", block, heading)
            self.assertRegex(block, r"prebuilt_digest:[\s\S]*?default:\s*\"\"")

    def test_dispatcher_does_not_pass_a_digest_so_push_to_main_stays_tag_based(self):
        self.assertNotIn("prebuilt_digest:", self.dispatcher)
        azure_job = re.search(
            r"deploy-azure:\s*\n(?:.*\n)*?    secrets: inherit",
            self.dispatcher,
        )
        self.assertIsNotNone(azure_job)
        self.assertNotIn("with:", azure_job.group(0))

    def test_cheap_digest_validation_runs_before_azure_login(self):
        cheap = self.text.find("Validate prebuilt digest")
        login = self.text.find("Azure login (OIDC)")
        self.assertGreater(cheap, 0)
        self.assertGreater(login, cheap)

    def test_cheap_validation_skips_lookup_and_acr_step_does_not(self):
        cheap = self._step("Validate prebuilt digest")
        lookup = self._step("Resolve prebuilt digest in ACR")
        self.assertIn("--skip-lookup", cheap)
        self.assertNotIn("--skip-lookup", lookup)
        self.assertIn("resolve_digest_deploy.py", lookup)

    def test_acr_manifest_lookup_is_after_infra_check_and_before_deploy_update(self):
        infra = self.text.find("Check whether infrastructure has been provisioned")
        lookup = self.text.find("Resolve prebuilt digest in ACR")
        update = self.text.find("- name: Update Container App")
        self.assertGreater(infra, 0)
        self.assertGreater(lookup, infra)
        self.assertGreater(update, lookup)

    def test_build_and_push_are_separate_steps_gated_off_digest_mode(self):
        deploy = self._job("deploy:")
        self.assertIn("- name: Build Docker image", deploy)
        self.assertIn("- name: Push Docker image", deploy)
        self.assertGreaterEqual(
            deploy.count("needs.preflight.outputs.digest_mode != 'true'"),
            2,
        )

    def test_digest_path_asserts_build_and_push_were_skipped(self):
        deploy = self._job("deploy:")
        prove = deploy.find("Prove digest path skipped build and push")
        update = deploy.find("- name: Update Container App")
        self.assertGreater(prove, 0)
        self.assertGreater(update, prove)
        self.assertIn("steps.build.outcome", deploy)
        self.assertIn("steps.push.outcome", deploy)
        self.assertIn("skipped", deploy)

    def test_revision_wait_is_not_gated_on_digest_mode(self):
        deploy = self._job("deploy:")
        wait_at = deploy.find("Wait for revision to reach Succeeded state")
        self.assertGreater(wait_at, 0)
        window = deploy[wait_at : wait_at + 400]
        self.assertNotIn("digest_mode", window)

    def test_digest_update_uses_preflight_digest_image(self):
        deploy = self._job("deploy:")
        self.assertIn("needs.preflight.outputs.digest_image", deploy)

    def test_digest_compare_refuses_empty_digest_and_clears_git_sha(self):
        body = self._job("assert-scoped-non-interference:")
        self.assertIn("digest mode compare requires a requested digest", body)
        self.assertIn('--git-sha ""', body)

    def _step(self, name: str) -> str:
        needle = f"- name: {name}"
        start = self.text.find(needle)
        self.assertGreater(start, 0, name)
        nxt = self.text.find("\n      - name:", start + len(needle))
        end = nxt if nxt > 0 else start + 800
        return self.text[start:end]

    def _block(self, heading: str) -> str:
        idx = self.text.find(heading)
        self.assertGreaterEqual(idx, 0, heading)
        return self.text[idx : idx + 1200]

    def _job(self, heading: str) -> str:
        match = re.search(rf"^  {re.escape(heading)}\n", self.text, re.MULTILINE)
        self.assertIsNotNone(match, heading)
        start = match.start()
        nxt = re.search(r"^  [a-zA-Z0-9_-]+:\s*$", self.text[start + 1 :], re.MULTILINE)
        end = start + 1 + nxt.start() if nxt else len(self.text)
        return self.text[start:end]


if __name__ == "__main__":
    unittest.main()
