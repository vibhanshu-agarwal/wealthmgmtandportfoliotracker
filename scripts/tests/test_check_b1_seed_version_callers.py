#!/usr/bin/env python3
"""Unit tests for scripts/check-b1-seed-version-callers.py."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[2]
GUARD_PATH = REPO / "scripts" / "check-b1-seed-version-callers.py"


def load_guard():
    spec = importlib.util.spec_from_file_location(
        "check_b1_seed_version_callers", GUARD_PATH
    )
    mod = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(mod)
    return mod


class CheckB1SeedVersionCallersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.guard = load_guard()

    def test_repo_inventory_passes(self) -> None:
        message = self.guard.run_guard()
        self.assertIn("exactly four governed callers", message)
        self.assertIn("three historical B1 Wave 5b callers", message)
        self.assertIn("asset-picker-cleanup", message)
        self.assertIn("synthetic-shell", message)
        self.assertIn("global-setup", message)
        self.assertIn("azure-api-smoke", message)

    def test_missing_expected_version_fails(self) -> None:
        shell = self.guard._read(self.guard.SHELL_SCRIPT).replace(
            "expectedVersion", "versionHint"
        )
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.run_guard(shell_text=shell, skip_discovery=True)
        self.assertIn("expectedVersion", str(ctx.exception))

    def test_missing_409_policy_fails(self) -> None:
        shell = self.guard._read(self.guard.SHELL_SCRIPT).replace("409", "418")
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.run_guard(shell_text=shell, skip_discovery=True)
        self.assertIn("409", str(ctx.exception))

    def test_missing_deploy_email_fails(self) -> None:
        deploy = self.guard._read(self.guard.DEPLOY_AZURE_WF).replace(
            "E2E_TEST_USER_EMAIL", "E2E_TEST_USER_MAIL"
        )
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.run_guard(deploy_azure_text=deploy, skip_discovery=True)
        self.assertIn("E2E_TEST_USER_EMAIL", str(ctx.exception))

    def test_unknown_additional_caller_fails(self) -> None:
        planted = REPO / "scripts" / "tests" / "_fixture_unexpected_seed_caller.sh"
        planted.write_text(
            'curl -X POST "$API/api/internal/portfolio/seed"\n',
            encoding="utf-8",
        )
        try:
            with self.assertRaises(self.guard.GuardError) as ctx:
                self.guard.run_guard()
            self.assertIn("unexpected seed call site", str(ctx.exception))
            self.assertIn(planted.relative_to(REPO).as_posix(), str(ctx.exception))
        finally:
            planted.unlink(missing_ok=True)

    def test_picker_cleanup_policy_mutations_fail(self) -> None:
        picker = REPO / "frontend/tests/e2e/asset-picker.spec.ts"
        source = picker.read_text(encoding="utf-8")
        mutations = [
            ("missing version", "data: { expectedVersion: observed.version }",
             "data: { versionHint: observed.version }", "expectedVersion"),
            ("stale version", "data: { expectedVersion: observed.version }",
             "data: { expectedVersion: 0 }", "expectedVersion"),
            ("missing fresh read", "const observed = await observePortfolio(request, session);",
             "const observed = { version: 7 };", "fresh"),
            ("wrong identity", "selectExactPortfolio(await response.json(), FIXED_E2E_USER_ID)",
             "selectExactPortfolio(await response.json(), session.userId)", "identity"),
            ("wrong login identity", "body.userId !== FIXED_E2E_USER_ID",
             "body.userId === FIXED_E2E_USER_ID", "identity"),
            ("missing internal key", '\"X-Internal-Api-Key\": internalApiKey()',
             '\"X-Unrelated-Key\": internalApiKey()', "internal key"),
            ("unbounded retries", "const CLEANUP_MAX_ATTEMPTS = 3;",
             "const CLEANUP_MAX_ATTEMPTS = Infinity;", "bounded"),
            ("lost conflict branch", "if (response.status() === 409) {",
             "if (response.status() === 418) {", "409"),
            ("lost conflict memory", "observedConflict = true;",
             "observedConflict = false;", "409"),
            ("forgiven conflict", "if (observedConflict) {",
             "if (false) {", "409"),
            ("missing conflict failure", 'throw new Error(\n          "[asset-picker-real] cleanup restored Golden State after an observed 409; the conflict still fails the test",\n        );',
             "return;", "409"),
            ("wrong success status", "if (response.status() === 200) {",
             "if (response.status() === 201) {", "200"),
            ("conditional cleanup", "test.afterEach(async ({ request }) => {",
             "test.afterAll(async ({ request }) => {", "afterEach"),
            ("extra seed call in approved file", "const CLEANUP_MAX_ATTEMPTS = 3;",
             'const CLEANUP_MAX_ATTEMPTS = 3;\nrequest.post("/api/internal/portfolio/seed");', "one seed"),
        ]
        original_read = self.guard._read
        for name, original, replacement, diagnostic in mutations:
            with self.subTest(name=name):
                self.assertIn(original, source)
                mutated = source.replace(original, replacement, 1)
                with patch.object(self.guard, "_read", side_effect=lambda path: (
                    mutated if path == picker else original_read(path)
                )):
                    with self.assertRaises(self.guard.GuardError) as ctx:
                        self.guard.run_guard(skip_discovery=True)
                self.assertIn(diagnostic, str(ctx.exception))

    def test_picker_cleanup_control_flow_mutations_fail(self) -> None:
        source = (REPO / "frontend/tests/e2e/asset-picker.spec.ts").read_text(encoding="utf-8")
        success_branch = "    if (response.status() === 200) {"
        hook_start = source.index("  test.afterEach(async ({ request }) => {")
        hook_end = source.index("\n  });", hook_start) + len("\n  });")
        hook = source[hook_start:hook_end]
        mutations = {
            "forgive previous 409": source.replace(
                success_branch, "    observedConflict = false;\n" + success_branch, 1),
            "reset attempt counter": source.replace(
                success_branch, "    attempt = 0;\n" + success_branch, 1),
            "disable afterEach registration": source.replace(
                hook, "  if (false) {\n" + hook + "\n  }", 1),
        }
        for name, mutated in mutations.items():
            with self.subTest(name=name):
                self.assertNotEqual(source, mutated)
                with self.assertRaises(self.guard.GuardError) as ctx:
                    self.guard.run_guard(asset_picker_text=mutated, skip_discovery=True)
                self.assertIn("canonical", str(ctx.exception))

    def test_regex_wrapper_cannot_disable_picker_fixtures(self) -> None:
        source = (REPO / "frontend/tests/e2e/asset-picker.spec.ts").read_text(encoding="utf-8")
        boundary = "async function restoreGoldenState("
        self.assertEqual(source.count(boundary), 1)
        mutated = source.replace(boundary, "if (false) {\n  void /}/;\n" + boundary, 1) + "\n}\n"
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.run_guard(asset_picker_text=mutated, skip_discovery=True)
        self.assertIn("canonical", str(ctx.exception))

    def test_picker_prefix_rejects_unreviewed_insertions(self) -> None:
        source = (REPO / "frontend/tests/e2e/asset-picker.spec.ts").read_text(encoding="utf-8")
        boundary = "async function restoreGoldenState("
        mutations = {
            "module statement": "void 0;\n" + source,
            "regex statement": source.replace(boundary, "void /}/;\n" + boundary, 1),
            "comment before cleanup": source.replace(boundary, "/* reviewed prefix changed */\n" + boundary, 1),
            "module comment": "/* reviewed prefix changed */\n" + source,
        }
        for name, mutated in mutations.items():
            with self.subTest(name=name):
                with self.assertRaises(self.guard.GuardError) as ctx:
                    self.guard.run_guard(asset_picker_text=mutated, skip_discovery=True)
                self.assertIn("canonical", str(ctx.exception))

    def test_picker_prefix_accepts_crlf(self) -> None:
        source = (REPO / "frontend/tests/e2e/asset-picker.spec.ts").read_text(encoding="utf-8")
        message = self.guard.run_guard(asset_picker_text=source.replace("\n", "\r\n"), skip_discovery=True)
        self.assertIn("exactly four governed callers", message)

    def test_scheduled_synthetic_trigger_fails(self) -> None:
        synthetic = self.guard._read(self.guard.SYNTHETIC_WF).replace(
            "  workflow_dispatch:\n",
            "  workflow_dispatch:\n  schedule:\n    - cron: '0 8 * * *'\n",
            1,
        )
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.check_synthetic_workflow(synthetic)
        self.assertIn("schedule", str(ctx.exception))

    def test_inline_scheduled_synthetic_trigger_fails(self) -> None:
        synthetic = self.guard._read(self.guard.SYNTHETIC_WF).replace(
            "  workflow_dispatch:\n",
            "  workflow_dispatch:\n  schedule: [{ cron: '0 8 * * *' }]\n",
            1,
        )
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.check_synthetic_workflow(synthetic)
        self.assertIn("schedule", str(ctx.exception))

    def test_missing_manual_synthetic_trigger_fails(self) -> None:
        synthetic = self.guard._read(self.guard.SYNTHETIC_WF).replace(
            "  workflow_dispatch:\n", "", 1
        )
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.check_synthetic_workflow(synthetic)
        self.assertIn("workflow_dispatch", str(ctx.exception))

    def test_synthetic_seed_step_credentials_required_even_if_playwright_has_them(
        self,
    ) -> None:
        synthetic = self.guard._read(self.guard.SYNTHETIC_WF)
        mutated = synthetic.replace(
            "          E2E_TEST_USER_EMAIL: ${{ secrets.E2E_TEST_USER_EMAIL }}\n"
            "          E2E_TEST_USER_PASSWORD: ${{ secrets.E2E_TEST_USER_PASSWORD }}\n"
            "        run: bash .github/workflows/scripts/seed-portfolio-with-version.sh",
            "        run: bash .github/workflows/scripts/seed-portfolio-with-version.sh",
            1,
        )
        with self.assertRaises(self.guard.GuardError) as ctx:
            self.guard.check_synthetic_workflow(mutated)
        self.assertIn("seed step: missing env E2E_TEST_USER_EMAIL", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
