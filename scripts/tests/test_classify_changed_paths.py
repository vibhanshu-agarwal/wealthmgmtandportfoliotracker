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

Aggregate gate (`ci-required`) contract, verified by executing the real `run:`
blocks extracted from the workflow rather than Python re-implementations:

  - The classifier's declaration determines the exact result every dependency
    must have. A skip is acceptable only where it was declared; a run only where
    it was not. Any disagreement fails the gate.
  - That equality is the only thing preventing a silent merge, because GitHub
    reports a skipped required job as Success to branch protection. Comparing
    against an exact expected value also fails closed on failure, cancellation,
    a missing dependency, and conclusions outside the known enum.
  - A `docs_only` value that is neither `true` nor `false` means the classifier
    malfunctioned and fails the gate rather than being inferred.
  - The evidence step must carry step-level `if: always()`, or it is skipped
    whenever enforcement fails -- losing the evidence on precisely the runs that
    need it. It cannot mask a failure: a failed step fails the job whatever later
    always() steps do.
  - Evidence must reach BOTH the job log and the step summary. The step summary
    is exposed by no REST endpoint and appears in no log, so redirecting there
    alone made it readable only in the web UI.

Stage B skip topology:

  - `unit-tests` carries the only skip condition in the graph. integration-tests,
    pact-consumer and docker-build-verify stay unconditional and skip by
    needs-propagation, which keeps the skip set downward-closed over the DAG by
    construction -- no job can be skipped while something downstream still runs.
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

    def test_aggregate_gate_uses_always_and_needs_exactly_the_eight(self):
        job = self._job(self.text, "ci-required:")
        self.assertIn("if: always()", job)
        for dependency in (
            "changes",
            "static-guard",
            "sanitizer-canary",
            "unit-tests",
            "azure-image-smoke-test",
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
            "azure-image-smoke-test:",
            "integration-tests:",
            "pact-consumer:",
            "docker-build-verify:",
        ):
            with self.subTest(job=heading):
                job = self._job(self.text, heading)
                job_level = re.search(r"(?m)^    if: .*$", job)
                if job_level:
                    self.assertNotIn("always()", job_level.group(0))

    def test_evidence_step_still_runs_when_enforcement_fails(self):
        # Without step-level always() the evidence step is skipped whenever the
        # enforce step above it fails -- so the evidence went missing on exactly
        # the runs where it is most diagnostic (observed on run 33457730873,
        # where steps were: enforce=failure, evidence=skipped).
        #
        # This cannot mask a failure: a failed step fails the job regardless of
        # later always() steps, so ci-required still reports red. Nor does it
        # breach the rule that only ci-required may use always() -- that rule
        # governs *job* conditions, and docker-build-verify already carries a
        # step-level always() for its Docker Compose cleanup.
        job = self._job(self.text, "ci-required:")
        parts = job.split("- name: Evidence", 1)
        self.assertEqual(2, len(parts), "evidence step missing from ci-required")
        self.assertRegex(
            parts[1],
            r"(?m)^        if: always\(\)$",
            "evidence step must carry step-level `if: always()`",
        )

    # ── Stage B: the skip is live ────────────────────────────────────────────
    def test_unit_tests_is_the_only_job_carrying_the_skip_condition(self):
        # The whole safety argument rests on this: one condition, at the top of
        # the chain. integration-tests, pact-consumer and docker-build-verify
        # must stay unconditional so they skip by needs-propagation, which keeps
        # the skip set downward-closed over the DAG by construction. A condition
        # on any of them could skip a job while something downstream still ran.
        unit = self._job(self.text, "unit-tests:")
        self.assertRegex(
            unit, r"(?m)^    if: needs\.changes\.outputs\.docs_only != 'true'$"
        )
        self.assertRegex(unit, r"(?m)^    needs: \[static-guard, changes\]$")

        for heading in ("integration-tests:", "pact-consumer:", "docker-build-verify:"):
            with self.subTest(job=heading):
                self.assertNotRegex(
                    self._job(self.text, heading),
                    r"(?m)^    if: ",
                    "downstream jobs must skip by propagation, not their own condition",
                )

    def test_azure_image_smoke_test_needs_only_unit_tests_without_condition(self):
        job = self._job(self.text, "azure-image-smoke-test:")
        self.assertRegex(job, r"(?m)^    needs: unit-tests$")
        self.assertNotRegex(job, r"(?m)^    if: ")

    def test_aggregate_enforces_declared_versus_observed(self):
        job = self._job(self.text, "ci-required:")
        self.assertNotIn(
            "SHADOW MODE", job, "Stage B replaces shadow logging with enforcement"
        )
        self.assertIn("Enforce declared vs observed", job)
        self.assertIn("mismatches", job)


ALL_JOBS = (
    "changes",
    "static-guard",
    "sanitizer-canary",
    "unit-tests",
    "azure-image-smoke-test",
    "integration-tests",
    "pact-consumer",
    "docker-build-verify",
)
# The five that skip together on a docs-only PR, by needs-propagation from the
# single condition on unit-tests. Nothing outside this set may ever skip.
CHAIN_JOBS = (
    "unit-tests",
    "azure-image-smoke-test",
    "integration-tests",
    "pact-consumer",
    "docker-build-verify",
)
GUARD_JOBS = ("changes", "static-guard", "sanitizer-canary")


@unittest.skipUnless(JQ and BASH, "needs bash and jq; both present on ubuntu-latest")
class AggregateEnforcementTests(unittest.TestCase):
    """Execute the shipped enforce block from ci-verification.yml, not a copy.

    Re-implementing the rule in Python would measure the copy rather than the
    gate that actually blocks merges -- the evidence-oracle mismatch this repo
    has been bitten by repeatedly. The `run:` body is extracted from the
    workflow and executed, so if the gate changes shape these fail loudly.

    Stage B contract: the classifier's declaration determines the exact result
    every dependency must have. A skip is acceptable only where it was declared;
    a run is acceptable only where it was not. Anything else fails closed.
    """

    def enforce(
        self,
        docs_only: str,
        overrides: dict[str, str] | None = None,
        omit: tuple[str, ...] = (),
    ) -> subprocess.CompletedProcess:
        block = extract_run_block(
            CI_VERIFICATION.read_text(encoding="utf-8"), "Enforce declared vs observed"
        )
        chain_default = "skipped" if docs_only == "true" else "success"
        needs = {}
        for job in ALL_JOBS:
            if job in omit:
                continue
            expected = chain_default if job in CHAIN_JOBS else "success"
            needs[job] = {"result": (overrides or {}).get(job, expected)}
        env = {**os.environ, "NEEDS_JSON": json.dumps(needs), "DOCS_ONLY": docs_only}
        return subprocess.run(
            [BASH, "-c", block],
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=env,
        )

    # ── the two legitimate shapes ────────────────────────────────────────────
    def test_full_suite_with_docs_only_false_passes(self):
        proc = self.enforce("false")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("Declared and observed job results agree", proc.stdout)

    def test_docs_only_true_with_the_chain_skipped_passes(self):
        proc = self.enforce("true")
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn("Declared and observed job results agree", proc.stdout)

    # ── disagreement, in both directions ─────────────────────────────────────
    def test_declared_skip_but_the_job_actually_ran_fails(self):
        for job in CHAIN_JOBS:
            with self.subTest(job=job):
                proc = self.enforce("true", {job: "success"})
                self.assertEqual(1, proc.returncode)
                self.assertIn(f"{job}: expected skipped, observed success", proc.stdout)

    def test_declared_full_suite_but_a_job_skipped_fails(self):
        # The silent-green case this gate exists for: GitHub reports a skipped
        # required job as Success to branch protection, so without equality an
        # unexpected skip merges green with no signal anywhere.
        for job in ALL_JOBS:
            with self.subTest(job=job):
                proc = self.enforce("false", {job: "skipped"})
                self.assertEqual(1, proc.returncode)
                self.assertIn(f"{job}: expected success, observed skipped", proc.stdout)

    def test_guard_jobs_may_not_skip_even_on_a_docs_only_pr(self):
        for job in GUARD_JOBS:
            with self.subTest(job=job):
                proc = self.enforce("true", {job: "skipped"})
                self.assertEqual(1, proc.returncode)
                self.assertIn(f"{job}: expected success, observed skipped", proc.stdout)

    # ── failure, cancellation, unknown conclusions ───────────────────────────
    def test_failure_and_cancellation_fail_in_both_modes(self):
        for docs_only in ("true", "false"):
            for result in ("failure", "cancelled"):
                with self.subTest(docs_only=docs_only, result=result):
                    proc = self.enforce(docs_only, {"changes": result})
                    self.assertEqual(1, proc.returncode)
                    self.assertIn(
                        f"changes: expected success, observed {result}", proc.stdout
                    )

    def test_conclusions_outside_the_known_enum_fail_closed(self):
        for result in ("neutral", "action_required", "timed_out", "invented_later"):
            with self.subTest(result=result):
                proc = self.enforce("false", {"pact-consumer": result})
                self.assertEqual(1, proc.returncode)
                self.assertIn(
                    f"pact-consumer: expected success, observed {result}", proc.stdout
                )

    def test_a_missing_dependency_fails_rather_than_passing_silently(self):
        proc = self.enforce("false", omit=("docker-build-verify",))
        self.assertEqual(1, proc.returncode)
        self.assertIn(
            "docker-build-verify: expected success, observed missing", proc.stdout
        )

    # ── classifier malfunction ───────────────────────────────────────────────
    def test_unusable_docs_only_value_fails_closed(self):
        # Anything but the two known values means the changes job malfunctioned.
        # A malfunction must never read as "nothing left to verify".
        for value in ("", "TRUE", "True", "yes", "1", "unset", "maybe"):
            with self.subTest(value=value):
                proc = self.enforce(value)
                self.assertEqual(1, proc.returncode)
                self.assertIn("no usable docs_only value", proc.stdout)

    def test_every_mismatch_is_reported_not_only_the_first(self):
        proc = self.enforce(
            "false", {"unit-tests": "skipped", "pact-consumer": "failure"}
        )
        self.assertEqual(1, proc.returncode)
        self.assertIn("unit-tests: expected success, observed skipped", proc.stdout)
        self.assertIn("pact-consumer: expected success, observed failure", proc.stdout)


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
class EvidenceStepTests(unittest.TestCase):
    """The gate's evidence must be readable without opening the web UI.

    The step summary is exposed by no REST endpoint and appears in no job log, so
    redirecting the block there with `>>` made the gate's own output unreadable
    to log- and API-based review -- the exact artefact it exists to produce.
    These tests execute the real block and prove it reaches BOTH sinks.
    """

    JOBS = ALL_JOBS

    def run_evidence_block(self, docs_only: str) -> tuple[str, str]:
        """Execute the shipped evidence step; return (stdout, step-summary)."""
        block = extract_run_block(
            CI_VERIFICATION.read_text(encoding="utf-8"), "Evidence"
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
            self.assertEqual(0, proc.returncode, f"evidence block failed: {proc.stderr}")
            return proc.stdout, summary.read_text(encoding="utf-8")

    def test_evidence_reaches_both_stdout_and_step_summary(self):
        stdout, summary = self.run_evidence_block("false")
        for marker in (
            "Aggregate gate",
            "classifier declared",
            "expected the full suite to run",
            "any disagreement",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, stdout, "evidence must reach the job log")
                self.assertIn(marker, summary, "evidence must reach the step summary")

    def test_both_sinks_receive_identical_content(self):
        stdout, summary = self.run_evidence_block("false")
        self.assertEqual(stdout, summary)

    def test_declared_value_and_expected_skip_set_are_reported(self):
        stdout, _ = self.run_evidence_block("true")
        self.assertIn("**true**", stdout)
        self.assertIn("expected skipped", stdout)
        for job in CHAIN_JOBS:
            with self.subTest(job=job):
                self.assertIn(job, stdout)

    def test_observed_results_table_is_present(self):
        stdout, _ = self.run_evidence_block("false")
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


@unittest.skipUnless(BASH, "needs bash; both present on ubuntu-latest")
class AzureImageSmokeProbeOutputTests(unittest.TestCase):
    """Regression for the probe stdout oracle in azure-image-smoke-test.

    `$(cat file)` strips trailing newlines, so comparing cat output to a
    newline-bearing expected value rejects valid probe output. The job must
    compare bytes with cmp instead.
    """

    @staticmethod
    def _write(path: Path, content: str) -> None:
        path.write_bytes(content.encode("utf-8"))

    def _cmp(self, observed: str, expected: str) -> int:
        with tempfile.TemporaryDirectory() as tmp:
            observed_path = Path(tmp) / "observed"
            expected_path = Path(tmp) / "expected"
            self._write(observed_path, observed)
            self._write(expected_path, expected)
            proc = subprocess.run(
                [BASH, "-c", 'cmp -s "$1" "$2"', "cmp", observed_path.as_posix(), expected_path.as_posix()],
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            return proc.returncode

    def test_exact_probe_output_passes_cmp(self):
        for expected in ("blank\n", "nonblank\n"):
            with self.subTest(expected=expected):
                self.assertEqual(0, self._cmp(expected, expected))

    def test_missing_trailing_newline_fails_cmp(self):
        self.assertNotEqual(0, self._cmp("blank", "blank\n"))

    def test_extra_trailing_newline_fails_cmp(self):
        self.assertNotEqual(0, self._cmp("blank\n\n", "blank\n"))

    def test_broken_cat_oracle_rejects_valid_newline_output(self):
        with tempfile.NamedTemporaryFile(delete=False) as tmp:
            stdout_file = Path(tmp.name)
        try:
            proc = subprocess.run(
                [
                    BASH,
                    "-c",
                    r"""
                    set -euo pipefail
                    stdout_file="$1"
                    expected=$'blank\n'
                    printf '%s' "$expected" >"$stdout_file"
                    if [ "$(cat "$stdout_file")" = "$expected" ]; then
                      exit 0
                    fi
                    exit 1
                    """,
                    "cat-oracle-regression",
                    stdout_file.as_posix(),
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
        finally:
            stdout_file.unlink(missing_ok=True)
        # The cat-based oracle must not accept newline-bearing probe output.
        self.assertNotEqual(
            0,
            proc.returncode,
            "cat oracle incorrectly treated blank\\n as matching",
        )

    def test_workflow_uses_cmp_not_cat_equality(self):
        job = WorkflowWiringTests()._job(
            CI_VERIFICATION.read_text(encoding="utf-8"), "azure-image-smoke-test:"
        )
        self.assertRegex(job, r"cmp -s \"\$stdout_file\"")
        self.assertNotRegex(job, r'\$\(cat "\$stdout_file"\)" != "\$expected"')

    def test_workflow_replica_token_case_uses_cmp_and_fixed_vector(self):
        job = WorkflowWiringTests()._job(
            CI_VERIFICATION.read_text(encoding="utf-8"), "azure-image-smoke-test:"
        )
        self.assertIn("/replica-token.jar", job)
        self.assertIn("api-gateway--0000000-abcdefg", job)
        self.assertIn("95ca17821ade", job)
        self.assertRegex(job, r"cmp -s \"\$replica_stdout_file\"")
        self.assertRegex(job, r"replica-token case: tool exited non-zero")
        self.assertRegex(job, r"replica_stderr_file")
        self.assertRegex(job, r"if \[ -s \"\$replica_stderr_file\" \]")


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
