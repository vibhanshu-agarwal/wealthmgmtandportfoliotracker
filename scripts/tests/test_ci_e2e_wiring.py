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


# B2 Task 10.1 - the static export inlines NEXT_PUBLIC_* at build time, so the only
# place these two flags can be set is the named `npm run build` step's own env mapping.
# They must read GitHub Actions *repository variables*: an unset variable expands to the
# empty string, which `parseFeatureFlag` treats as disabled.  That absence is the safety
# property keeping both controls hidden in production.
REQUIRED_AZURE_BUILD_ENV = {
    "NEXT_PUBLIC_ENABLE_ASSET_PICKER": "${{ vars.ENABLE_ASSET_PICKER }}",
    "NEXT_PUBLIC_ENABLE_DEMO_RESET_CONTROL": "${{ vars.ENABLE_DEMO_RESET_CONTROL }}",
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


def _env_expression(mapping: str, name: str, key_indent: int) -> str:
    """Read one env value that is a GitHub Actions ``${{ }}`` expression.

    ``_env_value`` matches a single whitespace-free token, so it structurally cannot
    read ``${{ vars.X }}``.  This reader takes the whole value, which lets the caller
    compare against the exact required expression -- a literal, a ``secrets``
    reference, or a ``||`` default is read successfully and then fails the equality
    assertion with a legible diff instead of a confusing "missing value" error.
    """
    match = re.search(
        rf"(?m)^{key_indent * ' '}{re.escape(name)}:[ \t]*(\S.*?)[ \t]*$", mapping
    )
    if not match:
        raise AssertionError(f"missing env value: {name}")

    # The pattern is line-anchored, so on its own it would read only the first line of
    # a YAML plain scalar that continues onto the next, more-indented line. That form
    # folds to "<value> <continuation>", which is how an unset variable could still be
    # made to expand to " true". A deeper-indented comment is safe: "#" terminates the
    # scalar rather than continuing it.
    for line in mapping[match.end() :].splitlines():
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent > key_indent and not line.lstrip().startswith("#"):
            raise AssertionError(
                f"{name} continues onto a folded line; its value is not a single scalar"
            )
        break

    return match.group(1)


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
        self.assertNotRegex(
            command,
            r"(?m)^\s*(?:if|then|elif|else|fi|case|esac|for|while|until|do|done)\b",
            "required Playwright invocation must be unconditional",
        )

    def test_multiline_shell_conditional_cannot_make_required_specs_advisory(self) -> None:
        step = _named_block(self.job, "Run Playwright E2E tests", 6)
        command = _run_body(step)
        wrapped_command = "          if false; then\n" + command + "          fi\n"
        wrapped_step = step.replace(command, wrapped_command)
        wrapped_job = self.job.replace(step, wrapped_step)

        original_job = self.job
        self.job = wrapped_job
        try:
            with self.assertRaisesRegex(AssertionError, "unconditional"):
                self.test_exact_playwright_invocation_requires_both_specs()
        finally:
            self.job = original_job

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

    def test_azure_static_export_build_maps_both_flags_from_repository_variables(self) -> None:
        azure_env = _env_mapping(self.azure_build_step, 8)
        for name, expression in REQUIRED_AZURE_BUILD_ENV.items():
            self.assertEqual(
                expression,
                _env_expression(azure_env, name, key_indent=10),
                f"{name} must be built from its repository variable inside the named "
                "static-export build step",
            )

    def test_exactly_one_static_export_build_step_owns_the_flag_mappings(self) -> None:
        # _named_block returns the FIRST match, so a decoy step with the same name
        # could shadow the real one and leave the guard inspecting the wrong block.
        headings = re.findall(
            r"(?m)^      - name: Build Next\.js static export\s*$",
            self.deploy_frontend_job,
        )
        self.assertEqual(
            1, len(headings), "exactly one named static-export build step must exist"
        )
        self.assertIn("run: npm run build", self.azure_build_step)

    def test_flag_wiring_cannot_be_satisfied_by_a_literal_secret_or_default(self) -> None:
        original = self.azure_build_step
        rejected = (
            ("${{ vars.ENABLE_ASSET_PICKER }}", '"true"'),
            ("${{ vars.ENABLE_ASSET_PICKER }}", "${{ secrets.ENABLE_ASSET_PICKER }}"),
            (
                "${{ vars.ENABLE_ASSET_PICKER }}",
                "${{ vars.ENABLE_ASSET_PICKER || 'true' }}",
            ),
            ("NEXT_PUBLIC_ENABLE_ASSET_PICKER", "NEXT_PUBLIC_ASSET_PICKER"),
            # A second expression concatenated onto the same line: with the variable
            # unset this expands to "true" and enables the flag.
            (
                "${{ vars.ENABLE_ASSET_PICKER }}",
                "${{ vars.ENABLE_ASSET_PICKER }}${{ 'true' }}",
            ),
            # The same attack spread over a YAML plain-scalar continuation line. YAML
            # folds it to "<var> ${{ 'true' }}", which expands to " true" while the
            # variable is unset -- and parseFeatureFlag trims before comparing.
            (
                "NEXT_PUBLIC_ENABLE_ASSET_PICKER: ${{ vars.ENABLE_ASSET_PICKER }}\n",
                "NEXT_PUBLIC_ENABLE_ASSET_PICKER: ${{ vars.ENABLE_ASSET_PICKER }}\n"
                "            ${{ 'true' }}\n",
            ),
        )
        try:
            for old, new in rejected:
                self.azure_build_step = original.replace(old, new)
                with self.assertRaises(AssertionError):
                    self.test_azure_static_export_build_maps_both_flags_from_repository_variables()

            # A mapping that is absent from the build step -- commented out, or moved to
            # the job level or an upload step -- must fail closed, not pass by proximity.
            self.azure_build_step = "\n".join(
                line
                for line in original.splitlines()
                if "ENABLE_ASSET_PICKER" not in line
            )
            with self.assertRaises(AssertionError):
                self.test_azure_static_export_build_maps_both_flags_from_repository_variables()
        finally:
            self.azure_build_step = original


if __name__ == "__main__":
    unittest.main(verbosity=2)
