#!/usr/bin/env python3
"""Unit tests for scripts/check-b1-seed-version-callers.py."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

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
        self.assertIn("exactly three callers", message)
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

    def test_unknown_fourth_caller_fails(self) -> None:
        planted = REPO / "scripts" / "tests" / "_fixture_unexpected_seed_caller.sh"
        planted.write_text(
            'curl -X POST "$API/api/internal/portfolio/seed"\n',
            encoding="utf-8",
        )
        try:
            with self.assertRaises(self.guard.GuardError) as ctx:
                self.guard.run_guard()
            self.assertIn("unexpected seed call site", str(ctx.exception))
        finally:
            planted.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
