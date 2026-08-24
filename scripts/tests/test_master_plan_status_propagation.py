#!/usr/bin/env python3
"""Adversarial contract tests for the master-plan/status propagation CI guard.

Contract (PR #151 review hardening):

  - Every PR must carry exactly one canonical `Master-plan impact:` declaration.
  - Forms: `updated — <tracks>` or `none: <same-line rationale>`.
  - `updated` requires the living master plan plus each declared track's ledger.
  - `none` rejects empty, multi-line-only, HTML placeholder, and checklist rationales.
  - Duplicate or conflicting markers fail closed.
  - Feature-code / CI paths are not exempt: missing declaration fails even when no
    Spec A/B1/B2 folder is touched.

Also pins that the guard is wired into the required `static-guard` job.
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
ASSET_PICKER_UI = "frontend/src/components/AssetPicker/BrowsePanel.tsx"
COMPOSITION_CONTROLLER = (
    "portfolio-service/src/main/java/com/wealth/portfolio/CompositionController.java"
)
CI_VERIFICATION_PATH = ".github/workflows/ci-verification.yml"


class TestUniversalDeclaration(unittest.TestCase):
    def test_missing_declaration_fails_for_feature_ui_change(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[ASSET_PICKER_UI],
                pr_body="Adds browse panel scaffolding.",
            )
        self.assertIn("declaration", str(ctx.exception).lower())

    def test_missing_declaration_fails_for_composition_controller(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[COMPOSITION_CONTROLLER],
                pr_body="Implements composition write path.",
            )
        self.assertIn("declaration", str(ctx.exception).lower())

    def test_missing_declaration_fails_for_ci_verification_change(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[CI_VERIFICATION_PATH],
                pr_body="Tweaks static-guard.",
            )
        self.assertIn("declaration", str(ctx.exception).lower())

    def test_absent_pr_body_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=["README.md"],
                pr_body=None,
            )
        self.assertIn("declaration", str(ctx.exception).lower())


class TestExactlyOneCanonicalDeclaration(unittest.TestCase):
    def test_duplicate_markers_fail_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_REQS],
                pr_body=(
                    "Master-plan impact: none: first rationale about typos.\n"
                    "Master-plan impact: none: second rationale also about typos.\n"
                ),
            )
        self.assertRegex(str(ctx.exception).lower(), r"exactly one|duplicate")

    def test_conflicting_updated_and_none_fail_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN, SPEC_A_TASKS],
                pr_body=(
                    "Master-plan impact: updated — Spec A\n"
                    "Master-plan impact: none: no status change.\n"
                ),
            )
        self.assertRegex(str(ctx.exception).lower(), r"exactly one|duplicate|conflict")

    def test_none_with_master_plan_update_is_conflict(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN, SPEC_A_REQS],
                pr_body=(
                    "Master-plan impact: none: wording cleanup only; "
                    "program status unchanged."
                ),
            )
        self.assertRegex(str(ctx.exception).lower(), r"conflict")


class TestNoneRationale(unittest.TestCase):
    def test_empty_none_rationale_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_REQS],
                pr_body="Master-plan impact: none:\n",
            )
        self.assertIn("rationale", str(ctx.exception).lower())

    def test_multiline_only_none_rationale_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_REQS],
                pr_body=(
                    "Master-plan impact: none\n"
                    "Wording cleanup; program status unchanged.\n"
                ),
            )
        self.assertRegex(str(ctx.exception).lower(), r"same-line|malformed|rationale")

    def test_html_placeholder_rationale_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_REQS],
                pr_body="Master-plan impact: none: <!-- Add a description of the changes -->",
            )
        self.assertRegex(str(ctx.exception).lower(), r"placeholder|rationale")

    def test_testing_checklist_rationale_fails_closed(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_REQS],
                pr_body=(
                    "Master-plan impact: none: - [ ] Confirm required static-guard stays green"
                ),
            )
        self.assertRegex(str(ctx.exception).lower(), r"placeholder|checklist|rationale")

    def test_valid_same_line_none_passes(self):
        evaluate_status_propagation(
            changed_files=[ASSET_PICKER_UI],
            pr_body=(
                "Master-plan impact: none: typography-only tweak in browse panel; "
                "no Spec A/B1/B2 status, blocker, or next-action change."
            ),
        )


class TestUpdatedDeclaration(unittest.TestCase):
    def test_updated_without_master_plan_fails(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[SPEC_A_TASKS, SPEC_A_REQS],
                pr_body="Master-plan impact: updated — Spec A",
            )
        self.assertRegex(str(ctx.exception).lower(), r"master[ -]plan")

    def test_updated_without_declared_ledger_fails(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN, SPEC_A_REQS],
                pr_body="Master-plan impact: updated — Spec A",
            )
        self.assertIn("supported-asset-integrity/tasks.md", str(ctx.exception))

    def test_updated_spec_a_passes_with_plan_and_ledger(self):
        evaluate_status_propagation(
            changed_files=[MASTER_PLAN, SPEC_A_TASKS, SPEC_A_REQS],
            pr_body="Master-plan impact: updated — Spec A",
        )

    def test_updated_multi_track_requires_each_ledger(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN, SPEC_A_TASKS, B1_DESIGN],
                pr_body="Master-plan impact: updated — Spec A, B1",
            )
        self.assertIn("portfolio-composition-contract/tasks.md", str(ctx.exception))

    def test_updated_multi_track_passes_with_all_ledgers(self):
        evaluate_status_propagation(
            changed_files=[MASTER_PLAN, SPEC_A_TASKS, B1_TASKS, B1_DESIGN],
            pr_body="Master-plan impact: updated — Spec A, B1",
        )

    def test_updated_process_requires_only_master_plan(self):
        evaluate_status_propagation(
            changed_files=[
                MASTER_PLAN,
                "scripts/check_master_plan_status_propagation.py",
                CI_VERIFICATION_PATH,
            ],
            pr_body="Master-plan impact: updated — process",
        )

    def test_updated_process_without_master_plan_fails(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[CI_VERIFICATION_PATH],
                pr_body="Master-plan impact: updated — process",
            )
        self.assertRegex(str(ctx.exception).lower(), r"master[ -]plan")

    def test_malformed_updated_without_tracks_fails(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN],
                pr_body="Master-plan impact: updated —",
            )
        self.assertIn("malformed", str(ctx.exception).lower())

    def test_unknown_track_fails(self):
        with self.assertRaises(GuardError) as ctx:
            evaluate_status_propagation(
                changed_files=[MASTER_PLAN],
                pr_body="Master-plan impact: updated — Wave 99",
            )
        self.assertRegex(str(ctx.exception).lower(), r"unknown|track|malformed")


class TestMasterPlanMergeStableWording(unittest.TestCase):
    def test_master_plan_does_not_claim_unmerged_or_docs_only_for_this_guard(self):
        text = (REPO / MASTER_PLAN).read_text(encoding="utf-8")
        # Merge-stable: these claims become false the moment the workflow/Python PR merges.
        self.assertNotRegex(
            text,
            r"(?i)implemented but unmerged.*status propagation|status propagation.*implemented but unmerged",
        )
        # The runtime baseline blurb must not call this process-control revision docs-only.
        header = "\n".join(text.splitlines()[:20])
        self.assertNotRegex(header, r"(?i)this update is documentation-only")
        self.assertRegex(header, r"(?i)runtime|program-state code baseline")
        self.assertIn("check_master_plan_status_propagation.py", text)


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
