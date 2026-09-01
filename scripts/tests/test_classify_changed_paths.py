#!/usr/bin/env python3
"""Contract tests for the CI changed-path classifier.

Contract:

  - `docs_only` is true only when every changed path matches the documentation
    allowlist (`docs/**`, top-level `*.md`, `.kiro/specs/**/*.md`).
  - The allowlist is a *skip* allowlist. Anything unrecognised -- a new module, a
    new top-level directory, a binary, a dotfile -- forces the full suite. A new
    path type can therefore only cause over-testing, never under-testing.
  - Fails closed on: empty diffs, missing base/head SHAs, git failure, and every
    event that is not `pull_request` (push to main, workflow_dispatch, schedule).
  - Malfunctions emit `docs_only=false` *and* exit non-zero so the `ci-required`
    aggregate sees a failed dependency rather than a silent skip.
  - Cross-boundary paths that look documentation-adjacent but drive real jobs
    (docker-compose.yml, frontend/package-lock.json, config/seed-tickers.json,
    Gradle config, .github/**) must never classify as docs-only.

Aggregate gate (`ci-required`) contract, verified by executing the real jq
predicate extracted from the workflow rather than a Python re-implementation:

  - In Stage A every dependency is unconditional, so any result other than
    `success` must be reported as not green -- `skipped` included. Rejecting only
    `failure`/`cancelled` would let an unexpected skip reach "all green", which is
    the silent-green condition the gate exists to prevent, because GitHub reports
    a skipped required job as Success to branch protection.
  - The same rule fails closed on conclusions outside the known enum.
  - The shadow step must carry step-level `if: always()`, or it is skipped
    whenever enforcement fails -- losing Stage A's evidence on precisely the runs
    that need it. It cannot mask a failure: a failed step fails the job whatever
    later always() steps do.
  - Stage A's shadow evidence must reach BOTH the job log and the step summary.
    The step summary is exposed by no REST endpoint and appears in no log, so
    redirecting it there alone made the evidence readable only in the web UI --
    useless for the log- and API-based review Stage A exists to serve.

Stage A boundary (deliberately pinned here so Stage B is a visible change):

  - `unit-tests` must NOT yet carry the docs-only skip condition.
  - `ci-required` must NOT yet enforce declared-versus-observed skip equality.

These two assertions are expected to be *inverted* by the Stage B change. They
exist so that enabling skipping cannot happen silently or by accident.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

JQ = shutil.which("jq")


def _usable_bash() -> str | None:
    """A bash that actually inherits the environment we hand it.

    On Windows `shutil.which("bash")` commonly resolves to WSL's bash, which does
    not inherit Windows environment variables and mangles the script through
    interop -- it appears to work while silently executing something different.
    Probe for the behaviour we depend on rather than trusting the path.
    On a runner this matches /usr/bin/bash on the first try.
    """
    candidates = [
        shutil.which("bash"),
        r"C:\Program Files\Git\bin\bash.exe",
        r"C:\Program Files (x86)\Git\bin\bash.exe",
    ]
    for candidate in candidates:
        if not candidate or not Path(candidate).exists():
            continue
        try:
            probe = subprocess.run(
                [candidate, "-c", 'printf %s "$CLASSIFIER_BASH_PROBE"'],
                capture_output=True,
                text=True,
                encoding="utf-8",
                env={**os.environ, "CLASSIFIER_BASH_PROBE": "ok"},
                timeout=60,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if probe.returncode == 0 and probe.stdout.strip() == "ok":
            return candidate
    return None


BASH = _usable_bash()

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

import classify_changed_paths as classifier  # noqa: E402
from check_master_plan_status_propagation import GuardError  # noqa: E402

CI_VERIFICATION = REPO / ".github" / "workflows" / "ci-verification.yml"

# The real file set from PR #197 -- a four-file markdown PR that ran the full
# 34-minute chain four times. This is the regression anchor for the whole change.
PR_197_FILES = [
    ".kiro/specs/portfolio-composition-contract/tasks.md",
    "docs/plans/ASSET_PICKER_E2E_MASTER_PLAN.md",
    "docs/runbooks/B1_G5_INGRESS_BLOCKER.md",
    "docs/todos/backlog/api-gateway-custom-domain-binding/README.md",
]


class ClassifyTests(unittest.TestCase):
    def assert_docs_only(self, files: list[str]) -> None:
        docs_only, reason, unmatched = classifier.classify(files)
        self.assertTrue(docs_only, f"expected docs-only for {files}: {reason}")
        self.assertEqual([], unmatched)

    def assert_full_suite(self, files: list[str]) -> None:
        docs_only, _reason, _unmatched = classifier.classify(files)
        self.assertFalse(docs_only, f"expected full suite for {files}")

    # ── docs-only ────────────────────────────────────────────────────────────
    def test_pr_197_real_file_set_is_docs_only(self):
        self.assert_docs_only(PR_197_FILES)

    def test_docs_tree_is_docs_only(self):
        self.assert_docs_only(["docs/runbooks/A.md", "docs/todos/backlog/x/README.md"])

    def test_top_level_markdown_is_docs_only(self):
        self.assert_docs_only(["README.md", "ROADMAP.md", "roadmap_enhancements_v4.md"])

    def test_kiro_spec_markdown_only_is_docs_only(self):
        self.assert_docs_only(
            [
                ".kiro/specs/supported-asset-integrity/tasks.md",
                ".kiro/specs/asset-picker-composition/requirements.md",
            ]
        )

    # ── mixed and unmatched ──────────────────────────────────────────────────
    def test_mixed_docs_and_backend_runs_full_suite(self):
        self.assert_full_suite(
            ["docs/plans/X.md", "portfolio-service/src/main/java/com/wealth/A.java"]
        )

    def test_single_unmatched_path_defeats_a_large_docs_change(self):
        self.assert_full_suite([*[f"docs/n{i}.md" for i in range(50)], "build.gradle"])

    def test_unknown_top_level_directory_runs_full_suite(self):
        # A directory nobody has classified yet must over-test, never under-test.
        self.assert_full_suite(["brand-new-module/src/main/java/A.java"])

    def test_unknown_top_level_file_runs_full_suite(self):
        self.assert_full_suite(["Makefile"])

    def test_empty_diff_is_not_docs_only(self):
        docs_only, reason, _ = classifier.classify([])
        self.assertFalse(docs_only)
        self.assertIn("refusing to infer", reason)

    # ── cross-boundary paths proven to drive real jobs ───────────────────────
    def test_cross_boundary_paths_never_classify_as_docs_only(self):
        # Each of these was verified to reach a required job:
        #   docker-compose.yml        -> DockerComposeSecretForwardingTest (unit-tests)
        #   frontend/package-lock.json-> sync-canary-playwright-version (sanitizer-canary)
        #   config/seed-tickers.json  -> common-catalog + insight-service + frontend E2E
        for path in (
            "docker-compose.yml",
            "frontend/package-lock.json",
            "config/seed-tickers.json",
            "build.gradle",
            "settings.gradle",
            "gradle/wrapper/gradle-wrapper.properties",
            "portfolio-service/build.gradle",
            ".github/workflows/ci-verification.yml",
            ".github/actions/sanitize-playwright-artifacts/sanitize.js",
            "scripts/classify_changed_paths.py",
            "portfolio-service/src/main/resources/db/migration/V20__x.sql",
        ):
            with self.subTest(path=path):
                self.assert_full_suite([path])

    def test_non_markdown_under_kiro_specs_runs_full_suite(self):
        self.assert_full_suite([".kiro/specs/supported-asset-integrity/hook.py"])

    # ── deletion and rename semantics ────────────────────────────────────────
    def test_deletion_only_of_docs_is_docs_only(self):
        # --no-renames reports deletions as plain paths; deleting docs is docs-only.
        self.assert_docs_only(["docs/runbooks/OBSOLETE.md"])

    def test_rename_out_of_a_governed_directory_runs_full_suite(self):
        # With --no-renames a move reports BOTH endpoints, so the source path --
        # a backend file -- is present and defeats docs-only. Rename detection
        # would have reported only the docs destination and laundered this.
        self.assert_full_suite(
            [
                "portfolio-service/src/main/java/com/wealth/Legacy.java",
                "docs/archive/Legacy.java.md",
            ]
        )


class MainBehaviourTests(unittest.TestCase):
    """End-to-end behaviour of main(): outputs, exit codes, fail-closed paths."""

    def run_main(self, argv: list[str], changed: list[str] | None = None,
                 error: Exception | None = None) -> tuple[int, dict[str, str]]:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "github_output"
            out.touch()
            env = {
                "GITHUB_OUTPUT": str(out),
                "GITHUB_STEP_SUMMARY": str(Path(tmp) / "summary.md"),
            }
            patch_target = mock.patch.object(
                classifier,
                "list_changed_files",
                side_effect=error if error else None,
                return_value=None if error else (changed or []),
            )
            with mock.patch.dict(os.environ, env, clear=False), patch_target:
                code = classifier.main(argv)
            parsed = dict(
                line.split("=", 1)
                for line in out.read_text(encoding="utf-8").splitlines()
                if "=" in line
            )
        return code, parsed

    def test_pull_request_docs_only_emits_true_and_exits_zero(self):
        code, out = self.run_main(
            ["--event-name", "pull_request", "--base", "aaa", "--head", "bbb"],
            changed=PR_197_FILES,
        )
        self.assertEqual(0, code)
        self.assertEqual("true", out["docs_only"])

    def test_pull_request_with_code_emits_false(self):
        code, out = self.run_main(
            ["--event-name", "pull_request", "--base", "aaa", "--head", "bbb"],
            changed=["portfolio-service/src/main/java/A.java"],
        )
        self.assertEqual(0, code)
        self.assertEqual("false", out["docs_only"])

    def test_push_to_main_always_runs_full_suite(self):
        code, out = self.run_main(["--event-name", "push"])
        self.assertEqual(0, code)
        self.assertEqual("false", out["docs_only"])

    def test_workflow_dispatch_always_runs_full_suite(self):
        code, out = self.run_main(["--event-name", "workflow_dispatch"])
        self.assertEqual(0, code)
        self.assertEqual("false", out["docs_only"])

    def test_missing_shas_on_pull_request_fails_loudly_and_closed(self):
        for base, head in (("", "bbb"), ("aaa", ""), ("", "")):
            with self.subTest(base=base, head=head):
                code, out = self.run_main(
                    ["--event-name", "pull_request", "--base", base, "--head", head]
                )
                self.assertEqual(1, code, "malfunction must exit non-zero")
                self.assertEqual("false", out["docs_only"], "must also fail closed")

    def test_git_failure_fails_loudly_and_closed(self):
        code, out = self.run_main(
            ["--event-name", "pull_request", "--base", "aaa", "--head", "bbb"],
            error=GuardError("git exploded"),
        )
        self.assertEqual(1, code)
        self.assertEqual("false", out["docs_only"])

    def test_empty_diff_on_pull_request_is_not_docs_only(self):
        code, out = self.run_main(
            ["--event-name", "pull_request", "--base", "aaa", "--head", "bbb"],
            changed=[],
        )
        self.assertEqual(0, code)
        self.assertEqual("false", out["docs_only"])


class WorkflowWiringTests(unittest.TestCase):
    """The classifier is only useful if the workflow actually wires it correctly."""

    def _job(self, text: str, heading: str) -> str:
        match = re.search(rf"^  {re.escape(heading)}\n", text, re.MULTILINE)
        self.assertIsNotNone(match, f"missing job {heading}")
        start = match.start()
        nxt = re.search(r"^  [a-zA-Z0-9_-]+:\s*$", text[start + 1 :], re.MULTILINE)
        end = start + 1 + nxt.start() if nxt else len(text)
        return text[start:end]

    def setUp(self):
        self.text = CI_VERIFICATION.read_text(encoding="utf-8")

    def test_changes_job_runs_the_classifier_and_publishes_the_output(self):
        job = self._job(self.text, "changes:")
        self.assertIn("classify_changed_paths.py", job)
        self.assertIn("docs_only:", job)
        # base...head needs full history; a shallow clone would break the diff.
        self.assertIn("fetch-depth: 0", job)

    def test_classifier_tests_run_in_the_unconditional_required_guard(self):
        # static-guard is required and never skipped, so a broken classifier
        # cannot merge. deploy-workflow-contract is advisory and would not do.
        job = self._job(self.text, "static-guard:")
        self.assertIn("test_classify_changed_paths.py", job)

    def test_aggregate_gate_uses_always_and_needs_exactly_the_seven(self):
        job = self._job(self.text, "ci-required:")
        self.assertIn("if: always()", job)
        for dependency in (
            "changes",
            "static-guard",
            "sanitizer-canary",
            "unit-tests",
            "integration-tests",
            "pact-consumer",
            "docker-build-verify",
        ):
            with self.subTest(dependency=dependency):
                self.assertRegex(job, rf"(?m)^      - {re.escape(dependency)}$")
        # Advisory job stays out unless it is deliberately made required.
        self.assertNotRegex(job, r"(?m)^      - deploy-workflow-contract$")

    def test_only_the_aggregate_uses_always(self):
        # Bare always() on an ordinary required job would let it run after an
        # upstream failure. Only ci-required may carry it.
        for heading in (
            "changes:",
            "static-guard:",
            "sanitizer-canary:",
            "unit-tests:",
            "integration-tests:",
            "pact-consumer:",
            "docker-build-verify:",
        ):
            with self.subTest(job=heading):
                job = self._job(self.text, heading)
                job_level = re.search(r"(?m)^    if: .*$", job)
                if job_level:
                    self.assertNotIn("always()", job_level.group(0))

    def test_shadow_step_still_runs_when_enforcement_fails(self):
        # Without step-level always() the shadow step is skipped whenever the
        # enforce step above it fails -- so Stage A's evidence went missing on
        # exactly the runs where it is most diagnostic (observed on run
        # 33457730873, where steps were: enforce=failure, shadow=skipped).
        #
        # This cannot mask a failure: a failed step fails the job regardless of
        # later always() steps, so ci-required still reports red. Nor does it
        # breach the rule that only ci-required may use always() -- that rule
        # governs *job* conditions, and docker-build-verify already carries a
        # step-level always() for its Docker Compose cleanup.
        job = self._job(self.text, "ci-required:")
        parts = job.split("- name: Declared vs observed", 1)
        self.assertEqual(2, len(parts), "shadow step missing from ci-required")
        self.assertRegex(
            parts[1],
            r"(?m)^        if: always\(\)$",
            "shadow step must carry step-level `if: always()`",
        )

    # ── Stage A boundary: these invert when Stage B is authorised ────────────
    def test_stage_a_unit_tests_has_no_skip_condition_yet(self):
        job = self._job(self.text, "unit-tests:")
        self.assertNotRegex(
            job,
            r"(?m)^    if: ",
            "Stage B (the unit-tests skip condition) is not yet authorised",
        )

    def test_stage_a_aggregate_does_not_enforce_skip_equality_yet(self):
        job = self._job(self.text, "ci-required:")
        self.assertIn("SHADOW MODE", job)


@unittest.skipUnless(JQ, "jq not installed locally; it is present on ubuntu-latest")
class AggregateGatePredicateTests(unittest.TestCase):
    """Execute the REAL jq predicate from ci-verification.yml, not a copy of it.

    Re-implementing the rule in Python here would measure the copy rather than the
    gate that actually runs -- the evidence-oracle mismatch this repo has already
    been bitten by repeatedly. The expression is extracted from the workflow and
    fed to jq, so if the gate changes shape this test fails loudly.

    Stage A contract: every dependency is unconditional, so anything other than
    `success` -- `skipped` included -- must be reported as not green. Rejecting
    only failure/cancelled would let an unexpected skip reach "all green", which
    is the silent-green condition the gate exists to prevent.
    """

    JOBS = (
        "changes",
        "static-guard",
        "sanitizer-canary",
        "unit-tests",
        "integration-tests",
        "pact-consumer",
        "docker-build-verify",
    )

    @classmethod
    def setUpClass(cls):
        text = CI_VERIFICATION.read_text(encoding="utf-8")
        match = re.search(
            r"bad=\$\(echo \"\$NEEDS_JSON\" \| jq -r '(.*?)'\)", text, re.S
        )
        if match is None:
            raise AssertionError(
                "could not extract the ci-required jq predicate from "
                "ci-verification.yml -- the aggregate gate changed shape"
            )
        cls.program = match.group(1)

    def not_green(self, overrides: dict[str, str]) -> str:
        """Return the gate's `bad` string for a synthetic `needs` context."""
        needs = {
            job: {"result": overrides.get(job, "success")} for job in self.JOBS
        }
        proc = subprocess.run(
            [JQ, "-r", self.program],
            input=json.dumps(needs),
            capture_output=True,
            text=True,
            check=True,
        )
        return proc.stdout.strip()

    def test_all_dependencies_successful_passes(self):
        self.assertEqual("", self.not_green({}))

    def test_any_skipped_dependency_fails(self):
        for job in self.JOBS:
            with self.subTest(job=job):
                self.assertEqual(f"{job}=skipped", self.not_green({job: "skipped"}))

    def test_failure_fails(self):
        for job in ("changes", "unit-tests", "docker-build-verify"):
            with self.subTest(job=job):
                self.assertEqual(f"{job}=failure", self.not_green({job: "failure"}))

    def test_cancelled_fails(self):
        for job in ("changes", "integration-tests"):
            with self.subTest(job=job):
                self.assertEqual(f"{job}=cancelled", self.not_green({job: "cancelled"}))

    def test_conclusions_outside_the_known_enum_fail_closed(self):
        # neutral / action_required / timed_out, and anything GitHub adds later.
        for result in ("neutral", "action_required", "timed_out", "invented_later"):
            with self.subTest(result=result):
                self.assertEqual(
                    f"pact-consumer={result}",
                    self.not_green({"pact-consumer": result}),
                )

    def test_multiple_bad_results_are_all_reported(self):
        out = self.not_green({"unit-tests": "skipped", "pact-consumer": "failure"})
        self.assertIn("unit-tests=skipped", out)
        self.assertIn("pact-consumer=failure", out)


def extract_run_block(text: str, step_name_prefix: str) -> str:
    """Return the dedented `run: |` body of the named step, as it will execute."""
    lines = text.splitlines()
    start = next(
        (
            i
            for i, line in enumerate(lines)
            if line.strip().startswith(f"- name: {step_name_prefix}")
        ),
        None,
    )
    if start is None:
        raise AssertionError(f"step {step_name_prefix!r} not found in the workflow")
    body_start = next(
        (j + 1 for j in range(start, len(lines)) if lines[j].strip() == "run: |"), None
    )
    if body_start is None:
        raise AssertionError(f"step {step_name_prefix!r} has no `run: |` block")

    indent = " " * 10
    body: list[str] = []
    for line in lines[body_start:]:
        if not line.strip():
            body.append("")
            continue
        if not line.startswith(indent):
            break
        body.append(line[len(indent) :])
    return "\n".join(body)


@unittest.skipUnless(JQ and BASH, "needs bash and jq; both present on ubuntu-latest")
class ShadowEvidenceTests(unittest.TestCase):
    """Stage A's evidence must be readable without opening the web UI.

    The step summary is exposed by no REST endpoint and appears in no job log, so
    redirecting the shadow block there with `>>` made Stage A's own output
    unreadable to log- and API-based review -- the exact artefact this stage
    exists to produce. These tests execute the real block and prove it reaches
    BOTH sinks.
    """

    JOBS = AggregateGatePredicateTests.JOBS

    def run_shadow_block(self, docs_only: str) -> tuple[str, str]:
        """Execute the shipped shadow step; return (stdout, step-summary content)."""
        block = extract_run_block(
            CI_VERIFICATION.read_text(encoding="utf-8"), "Declared vs observed"
        )
        needs = {job: {"result": "success"} for job in self.JOBS}
        with tempfile.TemporaryDirectory() as tmp:
            summary = Path(tmp) / "summary.md"
            summary.touch()
            env = {
                **os.environ,
                "GITHUB_STEP_SUMMARY": summary.as_posix(),
                "NEEDS_JSON": json.dumps(needs),
                "DOCS_ONLY": docs_only,
            }
            proc = subprocess.run(
                [BASH, "-c", block],
                capture_output=True,
                text=True,
                # Explicit utf-8: the block emits an em-dash, and decoding stdout
                # with the Windows locale codec would corrupt it and break the
                # stdout-equals-summary comparison for the wrong reason.
                encoding="utf-8",
                env=env,
            )
            self.assertEqual(0, proc.returncode, f"shadow block failed: {proc.stderr}")
            return proc.stdout, summary.read_text(encoding="utf-8")

    def test_shadow_evidence_reaches_both_stdout_and_step_summary(self):
        stdout, summary = self.run_shadow_block("false")
        for marker in (
            "Aggregate gate",
            "classifier declared",
            "Stage B would have run the full suite",
            "not enforced in Stage A",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, stdout, "evidence must reach the job log")
                self.assertIn(marker, summary, "evidence must reach the step summary")

    def test_both_sinks_receive_identical_content(self):
        stdout, summary = self.run_shadow_block("false")
        self.assertEqual(stdout, summary)

    def test_declared_value_and_skip_set_are_reported(self):
        stdout, _ = self.run_shadow_block("true")
        self.assertIn("**true**", stdout)
        for job in ("unit-tests", "integration-tests", "pact-consumer",
                    "docker-build-verify"):
            with self.subTest(job=job):
                self.assertIn(job, stdout)

    def test_observed_results_table_is_present(self):
        stdout, _ = self.run_shadow_block("false")
        for job in self.JOBS:
            with self.subTest(job=job):
                self.assertIn(f"| {job} | success |", stdout)

    def test_summary_is_not_redirected_away_from_stdout(self):
        # Regression guard: reverting tee to `>>` would silently restore the
        # UI-only defect, and every assertion above would still be reachable
        # only by someone opening the browser.
        job = re.search(
            r"^  ci-required:\n(?:(?!^  [a-zA-Z0-9_-]+:\s*$).*\n)*",
            CI_VERIFICATION.read_text(encoding="utf-8"),
            re.M,
        )
        self.assertIsNotNone(job)
        self.assertNotIn('} >> "$GITHUB_STEP_SUMMARY"', job.group(0))
        self.assertIn('| tee -a "$GITHUB_STEP_SUMMARY"', job.group(0))


class ToolingAvailabilityTests(unittest.TestCase):
    def test_jq_and_bash_are_present_when_running_on_a_runner(self):
        # AggregateGatePredicateTests and ShadowEvidenceTests skip without these.
        # That must never happen in CI, or the gate's contract would go
        # unverified and silently green.
        if os.environ.get("GITHUB_ACTIONS") == "true":
            self.assertIsNotNone(JQ, "jq must be available to verify the aggregate gate")
            self.assertIsNotNone(BASH, "bash must be available to verify shadow output")


if __name__ == "__main__":
    unittest.main()
