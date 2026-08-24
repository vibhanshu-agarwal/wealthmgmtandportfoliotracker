#!/usr/bin/env python3
"""Adversarial contract tests for the master-plan/status propagation CI guard.

Covers the required outcomes from docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md §0.2 and
docs/agent-instructions/CURSOR_HANDOFF_ASSET_PICKER_POST_SPEC_A_9_10.md §4:

  - missing master-plan update fails closed
  - missing owning ledger fails closed
  - empty "none" rationale fails closed
  - valid status update (master plan + owning ledger) passes
  - valid no-impact declaration passes

Also pins that the guard is wired into the required `static-guard` job so it cannot be
silently demoted to a non-required workflow.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

from check_master_plan_status_propagation import (  # noqa: E402
    GuardError,
    evaluate_status_propagation,
)

CI_VERIFICATION = REPO / ".github" / "workflows" / "ci-verification.yml"
GUARD_SCRIPT = REPO / "scripts" / "check_master_plan_status_propagation.py"

MASTER_PLAN = "docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md"
SPEC_A_TASKS = ".kiro/specs/supported-asset-integrity/tasks.md"
SPEC_A_REQS = ".kiro/specs/supported-asset-integrity/requirements.md"
B1_TASKS = ".kiro/specs/portfolio-composition-contract/tasks.md"
B1_DESIGN = ".kiro/specs/portfolio-composition-contract/design.md"
B2_TASKS = ".kiro/specs/asset-picker-composition/tasks.md"
DEPLOY = ".github/workflows/deploy.yml"


class TestMasterPlanStatusPropagation(unittest.TestCase):
    def test_unguarded_paths_are_not_subject_to_the_rule(self):
        evaluate_status_propagation(
            changed_files=["README.md", "frontend/package.json"],
            pr_body="",
        )

    def test_missing_master_plan_update_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_TASKS],
                pr_body="Implements Spec A evidence only.",
            )
        self.assertRegex(str(ctx.exception).lower(), r"master[ -]plan")

    def test_missing_owning_ledger_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN, SPEC_A_REQS],
                pr_body="Updates Spec A requirements and the master plan.",
            )
        message = str(ctx.exception).lower()
        self.assertIn("ledger", message)
        self.assertIn("supported-asset-integrity/tasks.md", message)

    def test_empty_none_rationale_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_REQS],
                pr_body="Master-plan impact: none\n\n",
            )
        self.assertIn("rationale", str(ctx.exception).lower())

    def test_malformed_impact_marker_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_REQS],
                pr_body="Master-plan impact:\nforgot the value",
            )
        self.assertIn("malformed", str(ctx.exception).lower())

    def test_valid_status_update_passes(self):
        evaluate_status_propagation(
            changed_files=[MASTER_PLAN, SPEC_A_TASKS, SPEC_A_REQS],
            pr_body="Records Spec A evidence.",
        )

    def test_valid_no_impact_declaration_passes(self):
        evaluate_status_propagation(
            changed_files=[SPEC_A_REQS],
            pr_body=(
                "## Summary\n"
                "Typos only in Spec A requirements.\n\n"
                "Master-plan impact: none\n"
                "Wording cleanup; program status, blockers, and next actions are unchanged.\n"
            ),
        )

    def test_inline_none_rationale_passes(self):
        evaluate_status_propagation(
            changed_files=[DEPLOY],
            pr_body=(
                "Master-plan impact: none — comment-only clarification in deploy.yml; "
                "no Asset Picker program status change."
            ),
        )

    def test_multi_track_requires_each_touched_ledger(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN, SPEC_A_TASKS, B1_DESIGN],
                pr_body="Cross-track wording.",
            )
        self.assertIn("portfolio-composition-contract/tasks.md", str(ctx.exception))

    def test_multi_track_passes_when_all_ledgers_and_master_plan_update(self):
        evaluate_status_propagation(
            changed_files=[MASTER_PLAN, SPEC_A_TASKS, B1_TASKS, B1_DESIGN],
            pr_body="Cross-track status sync.",
        )

    def test_process_only_change_passes_with_master_plan_update(self):
        # Guard/scripts/release-surface work with no Spec A/B1/B2 ledger touch still
        # needs the living dashboard updated when status changes.
        evaluate_status_propagation(
            changed_files=[
                MASTER_PLAN,
                "scripts/check_master_plan_status_propagation.py",
                "scripts/tests/test_master_plan_status_propagation.py",
            ],
            pr_body="Master-plan impact: records the CI guard as implemented but unmerged.",
        )

    def test_absent_impact_metadata_on_governed_paths_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[B2_TASKS],
                pr_body=None,
            )
        self.assertRegex(str(ctx.exception).lower(), r"impact|master plan")


class TestGuardWiredIntoRequiredCi(unittest.TestCase):
    def test_guard_script_exists(self):
        self.assertTrue(GUARD_SCRIPT.is_file())

    def test_static_guard_runs_contract_tests_and_live_pr_check(self):
        text = CI_VERIFICATION.read_text(encoding="utf-8")
        job = self._job(text, "static-guard:")
        self.assertIn("test_master_plan_status_propagation.py", job)
        self.assertIn("check_master_plan_status_propagation.py", job)
        self.assertIn("github.event.pull_request.body", job)
        self.assertIn("github.event_name == 'pull_request'", job)

    def test_static_guard_does_not_drop_existing_sanitizer_check(self):
        text = CI_VERIFICATION.read_text(encoding="utf-8")
        job = self._job(text, "static-guard:")
        self.assertIn("check-sanitizer-secret-wiring.js", job)

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
