#!/usr/bin/env python3
"""Structural contract for the required assembled-stack E2E CI wiring.

This guard reads tracked workflow, migration, and fixture source directly.  It
intentionally does not use ``frontend/.env.local``: a clean CI checkout must
prove that the active workflow supplies every demo credential explicitly.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
CI_WORKFLOW = REPO / ".github" / "workflows" / "ci-verification.yml"
DISABLED_E2E_WORKFLOW = REPO / ".github" / "workflows" / "frontend-e2e-integration.yml"
DEPLOY_AZURE = REPO / ".github" / "workflows" / "deploy-azure.yml"
V15 = REPO / "portfolio-service" / "src" / "main" / "resources" / "db" / "migration" / "V15__Reconcile_Auth_Seed_Users.sql"
DEMO_AUTH = REPO / "frontend" / "tests" / "e2e" / "helpers" / "demo-auth.ts"


REQUIRED_CI_ENV = {
    "NEXT_PUBLIC_ENABLE_ASSET_PICKER": "true",
    "NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL": "true",
    "DEMO_TEST_EMAIL": "demo@wealthtracker.dev",
    "DEMO_TEST_PASSWORD": "demo-wealthtracker-2026",
}


def _named_block(text: str, name: str, indent: int) -> str:
    """Return one named YAML block, stopping at the next sibling item."""
    lines = text.splitlines(keepends=True)
    heading = f"{' ' * indent}- name: {name}"
    start = next(
        (index for index, line in enumerate(lines) if line.rstrip("\r\n") == heading),
        None,
    )
    if start is None:
        raise AssertionError(f"missing named block: {name}")
    end = len(lines)
    sibling = re.compile(rf"^{' ' * indent}- ")
    for index in range(start + 1, len(lines)):
        if sibling.match(lines[index]):
            end = index
            break
    return "".join(lines[start:end])


def _job(text: str, job_name: str) -> str:
    """Return a top-level job, excluding all later jobs."""
    lines = text.splitlines(keepends=True)
    heading = f"  {job_name}:"
    start = next(
        (index for index, line in enumerate(lines) if line.rstrip("\r\n") == heading),
        None,
    )
    if start is None:
        raise AssertionError(f"missing job: {job_name}")
    end = len(lines)
    sibling = re.compile(r"^  [a-z0-9][a-z0-9_-]*:\s*$")
    for index in range(start + 1, len(lines)):
        if sibling.match(lines[index]):
            end = index
            break
    return "".join(lines[start:end])


def _job_env(job: str) -> str:
    """Return only the docker-build-verify job's four-space env mapping."""
    return _env_mapping(job, 4)


def _env_mapping(block: str, indent: int) -> str:
    """Return the env mapping whose key is at the requested YAML indentation."""
    lines = block.splitlines(keepends=True)
    spaces = " " * indent
    start = next(
        (index for index, line in enumerate(lines) if line.rstrip("\r\n") == f"{spaces}env:"),
        None,
    )
    if start is None:
        raise AssertionError(f"missing env mapping at indentation {indent}")
    end = len(lines)
    sibling = re.compile(rf"^{re.escape(spaces)}[a-zA-Z][a-zA-Z0-9_-]*:")
    for index in range(start + 1, len(lines)):
        if sibling.match(lines[index]):
            end = index
            break
    return "".join(lines[start:end])


def _quoted_env_value(mapping: str, name: str, key_indent: int = 6) -> str:
    match = re.search(
        rf"(?m)^{key_indent * ' '}{re.escape(name)}:\s*\"([^\"]*)\"\s*$", mapping
    )
    if not match:
        raise AssertionError(f"missing quoted job-level env value: {name}")
    return match.group(1)


def _env_value(mapping: str, name: str, key_indent: int) -> str:
    """Read one scalar env value from a specific mapping, quoted or bare."""
    match = re.search(
        rf"(?m)^{key_indent * ' '}{re.escape(name)}:\s*([^\s#]+)\s*$", mapping
    )
    if not match:
        raise AssertionError(f"missing env value: {name}")
    return match.group(1).strip('"\'')


def _run_body(step: str) -> str:
    """Return only the indented shell body of a multiline run block."""
    lines = step.splitlines(keepends=True)
    start = next(
        (index for index, line in enumerate(lines) if line.rstrip("\r\n") == "        run: |"),
        None,
    )
    if start is None:
        raise AssertionError("expected multiline run block")
    body = []
    for line in lines[start + 1 :]:
        if line.strip() and not line.startswith("          "):
            break
        body.append(line)
    return "".join(body)


class TestCiE2eWiring(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.ci = CI_WORKFLOW.read_text(encoding="utf-8")
        cls.disabled_e2e = DISABLED_E2E_WORKFLOW.read_text(encoding="utf-8")
        cls.deploy = DEPLOY_AZURE.read_text(encoding="utf-8")
        cls.v15 = V15.read_text(encoding="utf-8")
        cls.demo_auth = DEMO_AUTH.read_text(encoding="utf-8")
        cls.job = _job(cls.ci, "docker-build-verify")
        cls.deploy_frontend_job = _job(cls.deploy, "deploy-frontend")
        cls.azure_build_step = _named_block(
            cls.deploy_frontend_job, "Build Next.js static export", 6
        )

    def test_required_specs_are_wired_to_active_workflow(self) -> None:
        self.assertIn("docker-build-verify:", self.ci)
        self.assertIn("manual-only", self.disabled_e2e)
        self.assertNotIn("frontend-e2e-integration.yml", self.ci)
        self.assertNotIn(".env.local", self.ci)

    def test_required_values_are_job_level_env_on_active_docker_job(self) -> None:
        env = _job_env(self.job)
        for name, expected in REQUIRED_CI_ENV.items():
            self.assertEqual(expected, _quoted_env_value(env, name), name)

        # A step-level copy cannot satisfy this contract.  The four values must
        # be inherited by the Playwright step and its child webServer build.
        playwright_step = _named_block(self.job, "Run Playwright E2E tests", 6)
        for name in REQUIRED_CI_ENV:
            self.assertNotRegex(
                playwright_step,
                rf"(?m)^\s+{re.escape(name)}:",
                f"{name} must be supplied by job env, not step env",
            )

    def test_exact_playwright_invocation_requires_both_specs(self) -> None:
        step = _named_block(self.job, "Run Playwright E2E tests", 6)
        command = _run_body(step)

        self.assertRegex(command, r"(?m)^          npx playwright test \\\s*$")
        for spec in ("tests/e2e/asset-picker.spec.ts", "tests/e2e/demo-reset.spec.ts"):
            # Require a real continuation argument line.  A path in a comment or
            # arbitrary prose must not satisfy the required coverage contract.
            self.assertRegex(command, rf"(?m)^            {re.escape(spec)} \\\s*$")
            self.assertNotRegex(command, rf"(?m)^\s*#.*{re.escape(spec)}")
        self.assertEqual(1, command.count("npx playwright test"))

        # Both specs belong to one required command; no shell or Actions
        # forgiveness can turn either one into advisory coverage.
        self.assertNotRegex(
            self.job,
            r"(?m)^\s+continue-on-error\s*:",
        )
        self.assertNotRegex(self.job, r"(?m)^    if\s*:")
        self.assertNotRegex(step, r"(?m)^\s+if\s*:")
        self.assertNotRegex(self.job, r"(?m)^\s+shell:\s*[^#\s].*$")
        self.assertNotRegex(command, r"(?m)^\s*set\s+\+e\b")
        self.assertNotRegex(command, r"(?m)^\s*set\s+\+o\s+(?:errexit|pipefail)\b")
        self.assertNotIn("||", command)
        self.assertNotRegex(command, r"(?m)^\s*exit\s+0\s*$")
        self.assertNotRegex(command, r"\b(?:if|else)\s+npx playwright test\b")

    def test_demo_literals_and_identity_match_tracked_sources_without_env_fallback(self) -> None:
        env = _job_env(self.job)
        ci_email = _quoted_env_value(env, "DEMO_TEST_EMAIL")
        ci_password = _quoted_env_value(env, "DEMO_TEST_PASSWORD")
        azure_env = _env_mapping(self.azure_build_step, 8)
        self.assertEqual(
            ci_email,
            _env_value(azure_env, "NEXT_PUBLIC_DEMO_EMAIL", key_indent=10),
        )
        self.assertEqual(
            ci_password,
            _env_value(azure_env, "NEXT_PUBLIC_DEMO_PASSWORD", key_indent=10),
        )

        v15_match = re.search(
            r"(?is)INSERT\s+INTO\s+users\s*\([^)]*\)\s*VALUES\s*\(\s*'([^']+)'\s*,\s*'demo@wealthtracker\.dev'",
            self.v15,
        )
        self.assertIsNotNone(v15_match, "V15 must contain the tracked demo users row")
        assert v15_match is not None
        fixture_match = re.search(
            r'(?m)^export const DEMO_USER_ID = "([^"]+)";', self.demo_auth
        )
        self.assertIsNotNone(fixture_match, "demo-auth.ts must declare DEMO_USER_ID")
        assert fixture_match is not None
        self.assertEqual(v15_match.group(1), fixture_match.group(1))

        credentials_fn = self.demo_auth[
            self.demo_auth.index("export function demoLoginCredentials") : self.demo_auth.index(
                "/** Strips trailing slashes"
            )
        ]
        self.assertIn("process.env", credentials_fn)
        self.assertIn("DEMO_TEST_EMAIL", credentials_fn)
        self.assertIn("DEMO_TEST_PASSWORD", credentials_fn)
        self.assertIn("throw new Error", credentials_fn)
        self.assertNotIn(".env.local", self.demo_auth)


if __name__ == "__main__":
    unittest.main(verbosity=2)
