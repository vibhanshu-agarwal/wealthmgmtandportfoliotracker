#!/usr/bin/env python3
"""Fail-closed fixtures for the B1 R-C candidate verification-graph evidence generator.

Covers every negative case tasks.md 7.5 / the R-C architecture review name explicitly: an absent
required class, a task reporting zero tests (filtered/excluded/NO-SOURCE), an all-skipped required
class, a required class present under the wrong task, malformed XML, mismatched suite counters,
failures/errors anywhere in the manifest (including Gradle's merged-rerun flaky-failure shape), a
stale report left over from a prior run, tracked-source drift mid-run, and an unreported new suite
(discovery reconciliation against B1-added/modified test files).
"""

from __future__ import annotations

import subprocess
import sys
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

import b1_candidate_evidence as ev  # noqa: E402


def write_report(
    path: Path,
    classname: str,
    tests: int,
    skipped: int = 0,
    failures: int = 0,
    errors: int = 0,
    flaky_passing: int = 0,
    declared_override: dict | None = None,
) -> None:
    """Write a synthetic Gradle-shaped per-class JUnit XML report.

    `flaky_passing` adds N extra passing <testcase> elements that also carry a <flakyFailure> child
    -- Gradle's merged-rerun shape for a test that failed then passed on retry. These are *not*
    counted toward `failures`/`tests` in the declared attributes (matching Gradle's real behaviour),
    only in `tests`.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    cases = []
    remaining_skipped, remaining_failures, remaining_errors = skipped, failures, errors
    for i in range(tests):
        name = f"case{i}"
        if remaining_failures > 0:
            cases.append(f'<testcase name="{name}" classname="{classname}" time="0.01">'
                          f'<failure message="boom">trace</failure></testcase>')
            remaining_failures -= 1
        elif remaining_errors > 0:
            cases.append(f'<testcase name="{name}" classname="{classname}" time="0.01">'
                          f'<error message="boom">trace</error></testcase>')
            remaining_errors -= 1
        elif remaining_skipped > 0:
            cases.append(f'<testcase name="{name}" classname="{classname}" time="0.0">'
                          f'<skipped/></testcase>')
            remaining_skipped -= 1
        else:
            cases.append(f'<testcase name="{name}" classname="{classname}" time="0.01"/>')
    for i in range(flaky_passing):
        cases.append(
            f'<testcase name="flaky{i}" classname="{classname}" time="0.01">'
            f'<flakyFailure message="retried">trace</flakyFailure></testcase>'
        )
    declared = {
        "tests": tests + flaky_passing,
        "skipped": skipped,
        "failures": failures,
        "errors": errors,
    }
    if declared_override:
        declared.update(declared_override)
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<testsuite name="{classname}" tests="{declared["tests"]}" skipped="{declared["skipped"]}" '
        f'failures="{declared["failures"]}" errors="{declared["errors"]}" time="0.1">'
        + "".join(cases)
        + "</testsuite>"
    )
    path.write_text(xml, encoding="utf-8")


def run_git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise AssertionError(f"git {args} failed: {result.stderr}")
    return result.stdout


class TempGitRepo:
    """A minimal real git repo, so freshness/identity-drift/discovery tests exercise real git
    rather than a mocked subprocess -- matching this repo's existing test convention."""

    def __enter__(self) -> Path:
        self._tmp = TemporaryDirectory()
        repo = Path(self._tmp.name)
        run_git(repo, "init", "-q")
        run_git(repo, "config", "user.email", "test@example.com")
        run_git(repo, "config", "user.name", "Test")
        (repo / "portfolio-service" / "src" / "test" / "java" / "com" / "wealth" / "portfolio").mkdir(parents=True)
        readme = repo / "README.md"
        readme.write_text("base\n", encoding="utf-8")
        # Mirror the real repo's .gitignore for generated candidate-graph output, so writing
        # reports/jars into these directories during a test does not itself register as tracked
        # source drift -- matching real production behaviour (`git status` ignores them too).
        (repo / ".gitignore").write_text("build/\n.candidate-artifacts/\n", encoding="utf-8")
        run_git(repo, "add", ".")
        run_git(repo, "commit", "-q", "-m", "base")
        self.base_sha = run_git(repo, "rev-parse", "HEAD").strip()
        return repo

    def __exit__(self, *exc) -> None:
        self._tmp.cleanup()


class ParseJunitReportTests(unittest.TestCase):
    def test_happy_path(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-com.wealth.portfolio.FooTest.xml"
            write_report(path, "com.wealth.portfolio.FooTest", tests=3, skipped=1)
            result = ev.parse_junit_report(path, task="test")
            self.assertEqual(result.classname, "com.wealth.portfolio.FooTest")
            self.assertEqual(result.tests, 3)
            self.assertEqual(result.non_skipped, 2)
            self.assertEqual(result.failures, 0)
            self.assertTrue(result.report_sha256)

    def test_malformed_xml_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-broken.xml"
            path.write_text("<testsuite this is not valid xml", encoding="utf-8")
            with self.assertRaises(ev.EvidenceError):
                ev.parse_junit_report(path, task="test")

    def test_wrong_root_element_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-wrong-root.xml"
            path.write_text('<?xml version="1.0"?><notASuite/>', encoding="utf-8")
            with self.assertRaises(ev.EvidenceError):
                ev.parse_junit_report(path, task="test")

    def test_suite_counter_mismatch_fails_closed(self) -> None:
        """A report claiming more tests than it actually contains <testcase> elements for."""
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-lying.xml"
            write_report(path, "com.wealth.portfolio.LiarTest", tests=2, declared_override={"tests": 5})
            with self.assertRaises(ev.EvidenceError):
                ev.parse_junit_report(path, task="test")

    def test_declared_failures_mismatch_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-lying2.xml"
            write_report(path, "com.wealth.portfolio.LiarTest2", tests=2, declared_override={"failures": 1})
            with self.assertRaises(ev.EvidenceError):
                ev.parse_junit_report(path, task="test")

    def test_flaky_rerun_case_counts_as_failure(self) -> None:
        """Gradle's merged-rerun XML: an outwardly passing <testcase> that carries a
        <flakyFailure> child must not report green."""
        with TemporaryDirectory() as tmp:
            path = Path(tmp) / "TEST-flaky.xml"
            write_report(path, "com.wealth.portfolio.FlakyTest", tests=1, flaky_passing=1)
            result = ev.parse_junit_report(path, task="test")
            self.assertEqual(result.failures, 1)


class CollectTaskManifestTests(unittest.TestCase):
    def test_missing_report_dir_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.collect_task_manifest(Path(tmp) / "does-not-exist", "integrationTest")
            self.assertIn("did not produce reports", str(ctx.exception))

    def test_empty_report_dir_fails_closed(self) -> None:
        """An existing-but-empty directory looks exactly like a filtered/excluded/NO-SOURCE task."""
        with TemporaryDirectory() as tmp:
            report_dir = Path(tmp) / "test-results" / "test"
            report_dir.mkdir(parents=True)
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.collect_task_manifest(report_dir, "test")
            self.assertIn("zero tests", str(ctx.exception))

    def test_collects_all_classes(self) -> None:
        with TemporaryDirectory() as tmp:
            report_dir = Path(tmp) / "test-results" / "test"
            write_report(report_dir / "TEST-A.xml", "com.wealth.portfolio.ATest", tests=1)
            write_report(report_dir / "TEST-B.xml", "com.wealth.portfolio.BTest", tests=2)
            results = ev.collect_task_manifest(report_dir, "test")
            self.assertEqual({r.classname for r in results}, {"com.wealth.portfolio.ATest", "com.wealth.portfolio.BTest"})
            self.assertTrue(all(r.task == "test" for r in results))


FLOOR = [
    {"suite": "Asset discovery", "task": "test", "report_class_suffix": "AssetCatalogControllerTest"},
    {"suite": "Concurrency", "task": "integrationTest", "report_class_suffix": "ConcurrentCompositionIT"},
]


class ValidateFloorTests(unittest.TestCase):
    def _class(self, task: str, classname: str, tests: int = 1, skipped: int = 0) -> ev.ClassResult:
        return ev.ClassResult(
            task=task, classname=classname, report_file="x", tests=tests, skipped=skipped,
            failures=0, errors=0, report_sha256="deadbeef",
        )

    def test_all_present_and_non_skipped_passes(self) -> None:
        manifest = [
            self._class("test", "com.wealth.portfolio.AssetCatalogControllerTest"),
            self._class("integrationTest", "com.wealth.portfolio.composition.ConcurrentCompositionIT"),
        ]
        self.assertEqual(ev.validate_floor(manifest, FLOOR), [])

    def test_absent_required_pattern_fails(self) -> None:
        manifest = [self._class("integrationTest", "com.wealth.portfolio.composition.ConcurrentCompositionIT")]
        failures = ev.validate_floor(manifest, FLOOR)
        self.assertEqual(len(failures), 1)
        self.assertIn("AssetCatalogControllerTest", failures[0])

    def test_all_skipped_required_class_fails(self) -> None:
        manifest = [
            self._class("test", "com.wealth.portfolio.AssetCatalogControllerTest", tests=3, skipped=3),
            self._class("integrationTest", "com.wealth.portfolio.composition.ConcurrentCompositionIT"),
        ]
        failures = ev.validate_floor(manifest, FLOOR)
        self.assertEqual(len(failures), 1)
        self.assertIn("fully-skipped", failures[0])

    def test_required_class_under_wrong_task_is_treated_as_absent(self) -> None:
        """A suite written under the wrong Gradle task (e.g. missing @Tag("integration")) must not
        satisfy the floor just because a same-named class ran somewhere."""
        manifest = [
            self._class("integrationTest", "com.wealth.portfolio.AssetCatalogControllerTest"),
            self._class("integrationTest", "com.wealth.portfolio.composition.ConcurrentCompositionIT"),
        ]
        failures = ev.validate_floor(manifest, FLOOR)
        self.assertEqual(len(failures), 1)
        self.assertIn("AssetCatalogControllerTest", failures[0])


class ValidateNoFailuresTests(unittest.TestCase):
    def test_reports_every_failing_class(self) -> None:
        manifest = [
            ev.ClassResult("test", "com.wealth.portfolio.OkTest", "x", 1, 0, 0, 0, "h"),
            ev.ClassResult("test", "com.wealth.portfolio.BadTest", "y", 2, 0, 1, 0, "h"),
            ev.ClassResult("integrationTest", "com.wealth.portfolio.WorseIT", "z", 2, 0, 0, 1, "h"),
        ]
        problems = ev.validate_no_failures(manifest)
        self.assertEqual(len(problems), 2)
        self.assertTrue(any("BadTest" in p for p in problems))
        self.assertTrue(any("WorseIT" in p for p in problems))


class FileToFqcnTests(unittest.TestCase):
    def test_unix_path(self) -> None:
        self.assertEqual(
            ev.file_to_fqcn("portfolio-service/src/test/java/com/wealth/portfolio/FooTest.java"),
            "com.wealth.portfolio.FooTest",
        )

    def test_windows_path(self) -> None:
        self.assertEqual(
            ev.file_to_fqcn(r"portfolio-service\src\test\java\com\wealth\portfolio\FooTest.java"),
            "com.wealth.portfolio.FooTest",
        )

    def test_non_test_source_rejected(self) -> None:
        with self.assertRaises(ev.EvidenceError):
            ev.file_to_fqcn("portfolio-service/src/main/java/com/wealth/portfolio/Foo.java")


class ReconcileDiscoveryTests(unittest.TestCase):
    def test_unreported_new_suite_is_flagged(self) -> None:
        manifest = [ev.ClassResult("test", "com.wealth.portfolio.KnownTest", "x", 1, 0, 0, 0, "h")]
        b1_files = ["portfolio-service/src/test/java/com/wealth/portfolio/NewSuiteTest.java"]
        missing = ev.reconcile_discovery(manifest, b1_files, allowlist=[])
        self.assertEqual(len(missing), 1)
        self.assertIn("NewSuiteTest", missing[0])

    def test_known_suite_not_flagged(self) -> None:
        manifest = [ev.ClassResult("test", "com.wealth.portfolio.KnownTest", "x", 1, 0, 0, 0, "h")]
        b1_files = ["portfolio-service/src/test/java/com/wealth/portfolio/KnownTest.java"]
        self.assertEqual(ev.reconcile_discovery(manifest, b1_files, allowlist=[]), [])

    def test_allowlisted_helper_not_flagged(self) -> None:
        b1_files = ["portfolio-service/src/test/java/com/wealth/portfolio/AbstractBaseTest.java"]
        missing = ev.reconcile_discovery([], b1_files, allowlist=["com.wealth.portfolio.AbstractBaseTest"])
        self.assertEqual(missing, [])


class JarStageTests(unittest.TestCase):
    def test_matching_hash_passes(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            libs = repo / "portfolio-service" / "build" / "libs"
            libs.mkdir(parents=True)
            (libs / "portfolio-service.jar").write_bytes(b"jar-bytes")
            staged = repo / ".candidate-artifacts"
            staged.mkdir()
            (staged / "portfolio-service.jar").write_bytes(b"jar-bytes")
            policy = {"bootjar_dir": "portfolio-service/build/libs", "bootjar_glob": "*.jar",
                      "staged_path": ".candidate-artifacts/portfolio-service.jar"}
            result = ev.check_jar_stage(repo, policy)
            self.assertEqual(result["sha256"], ev.sha256_file(libs / "portfolio-service.jar"))

    def test_mismatched_hash_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            libs = repo / "portfolio-service" / "build" / "libs"
            libs.mkdir(parents=True)
            (libs / "portfolio-service.jar").write_bytes(b"jar-bytes")
            staged = repo / ".candidate-artifacts"
            staged.mkdir()
            (staged / "portfolio-service.jar").write_bytes(b"different-bytes")
            policy = {"bootjar_dir": "portfolio-service/build/libs", "bootjar_glob": "*.jar",
                      "staged_path": ".candidate-artifacts/portfolio-service.jar"}
            with self.assertRaises(ev.EvidenceError):
                ev.check_jar_stage(repo, policy)

    def test_ambiguous_glob_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            libs = repo / "portfolio-service" / "build" / "libs"
            libs.mkdir(parents=True)
            (libs / "portfolio-service.jar").write_bytes(b"a")
            (libs / "portfolio-service-plain.jar").write_bytes(b"b")
            policy = {"bootjar_dir": "portfolio-service/build/libs", "bootjar_glob": "*.jar",
                      "staged_path": ".candidate-artifacts/portfolio-service.jar"}
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.check_jar_stage(repo, policy)
            self.assertIn("ambiguous", str(ctx.exception))

    def test_missing_staged_file_fails_closed(self) -> None:
        with TemporaryDirectory() as tmp:
            repo = Path(tmp)
            libs = repo / "portfolio-service" / "build" / "libs"
            libs.mkdir(parents=True)
            (libs / "portfolio-service.jar").write_bytes(b"jar-bytes")
            policy = {"bootjar_dir": "portfolio-service/build/libs", "bootjar_glob": "*.jar",
                      "staged_path": ".candidate-artifacts/portfolio-service.jar"}
            with self.assertRaises(ev.EvidenceError):
                ev.check_jar_stage(repo, policy)


class FreshnessAndIdentityTests(unittest.TestCase):
    def test_stale_report_is_flagged(self) -> None:
        with TempGitRepo() as repo:
            # Report predates the marker -- as if Gradle considered the task UP-TO-DATE and never
            # rewrote it, even though --rerun-tasks was supposed to force that.
            report = repo / "build" / "TEST-Old.xml"
            write_report(report, "com.wealth.portfolio.OldTest", tests=1)
            manifest = [ev.ClassResult("test", "com.wealth.portfolio.OldTest", str(report), 1, 0, 0, 0, "h")]
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            problems = ev.check_freshness_and_identity(manifest, marker, repo)
            self.assertEqual(len(problems), 1)
            self.assertIn("stale", problems[0])

    def test_fresh_report_and_unchanged_source_passes(self) -> None:
        with TempGitRepo() as repo:
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            report = repo / "build" / "TEST-Fresh.xml"
            write_report(report, "com.wealth.portfolio.FreshTest", tests=1)
            manifest = [ev.ClassResult("test", "com.wealth.portfolio.FreshTest", str(report), 1, 0, 0, 0, "h")]
            self.assertEqual(ev.check_freshness_and_identity(manifest, marker, repo), [])

    def test_source_identity_drift_is_flagged(self) -> None:
        with TempGitRepo() as repo:
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            report = repo / "build" / "TEST-Fresh.xml"
            write_report(report, "com.wealth.portfolio.FreshTest", tests=1)
            manifest = [ev.ClassResult("test", "com.wealth.portfolio.FreshTest", str(report), 1, 0, 0, 0, "h")]
            # Mutate tracked source after the marker was written -- simulates a source change
            # sneaking in mid-run.
            (repo / "README.md").write_text("changed\n", encoding="utf-8")
            problems = ev.check_freshness_and_identity(manifest, marker, repo)
            self.assertTrue(any("drift" in p for p in problems))


class DiscoverB1TestFilesTests(unittest.TestCase):
    def test_finds_added_test_file_since_base(self) -> None:
        with TempGitRepo() as repo:
            new_file = repo / "portfolio-service/src/test/java/com/wealth/portfolio/NewTest.java"
            new_file.write_text("package com.wealth.portfolio;\nclass NewTest {}\n", encoding="utf-8")
            run_git(repo, "add", ".")
            run_git(repo, "commit", "-q", "-m", "add NewTest")
            found = ev.discover_b1_test_files(
                repo, self._base_sha_for(repo),
                ["portfolio-service/src/test/java/**/*Test.java", "portfolio-service/src/test/java/**/*IT.java"],
            )
            self.assertTrue(any(f.endswith("NewTest.java") for f in found))

    @staticmethod
    def _base_sha_for(repo: Path) -> str:
        log = run_git(repo, "log", "--reverse", "--format=%H")
        return log.splitlines()[0]


class RunEvidenceEndToEndTests(unittest.TestCase):
    """Exercises run_evidence() as a whole: the happy path, and a run that hits several
    independent problems at once (so the caller sees every reason, not just the first)."""

    def _policy(self, base_sha: str) -> dict:
        return {
            "b1_base_commit": {"sha": base_sha},
            "report_dirs": {
                "test": "portfolio-service/build/test-results/test",
                "integrationTest": "portfolio-service/build/test-results/integrationTest",
            },
            "candidate_floor": {"entries": FLOOR},
            "discovery": {
                "test_file_globs": [
                    "portfolio-service/src/test/java/**/*Test.java",
                    "portfolio-service/src/test/java/**/*IT.java",
                ],
                "helper_class_allowlist": [],
            },
            "staging": {
                "bootjar_dir": "portfolio-service/build/libs",
                "bootjar_glob": "*.jar",
                "staged_path": ".candidate-artifacts/portfolio-service.jar",
            },
        }

    def _seed_reports(self, repo: Path) -> None:
        write_report(
            repo / "portfolio-service/build/test-results/test/TEST-AssetCatalogControllerTest.xml",
            "com.wealth.portfolio.AssetCatalogControllerTest", tests=1,
        )
        write_report(
            repo / "portfolio-service/build/test-results/integrationTest/TEST-ConcurrentCompositionIT.xml",
            "com.wealth.portfolio.composition.ConcurrentCompositionIT", tests=1,
        )

    def _seed_jar(self, repo: Path) -> None:
        libs = repo / "portfolio-service/build/libs"
        libs.mkdir(parents=True)
        (libs / "portfolio-service.jar").write_bytes(b"jar-bytes")
        staged = repo / ".candidate-artifacts"
        staged.mkdir(exist_ok=True)
        (staged / "portfolio-service.jar").write_bytes(b"jar-bytes")

    def test_happy_path_passes(self) -> None:
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            self._seed_reports(repo)
            self._seed_jar(repo)
            evidence = ev.run_evidence(repo, self._policy(base_sha), marker)
            self.assertEqual(evidence["graph_verification_status"], "PASS")
            self.assertEqual(evidence["problems"], [])
            self.assertEqual(evidence["run"]["mode"], "CANDIDATE")
            # This tool only evidences the Task 7.4/7.5 graph -- it can never assert overall
            # release/candidate readiness (that needs Task B/C evidence this script doesn't
            # produce, plus R3's resolution), so candidate_ready must stay False even on a clean
            # PASS, with the outstanding items named explicitly.
            self.assertFalse(evidence["candidate_ready"])
            self.assertTrue(evidence["candidate_ready_blocked_by"])

    def test_multiple_independent_problems_all_surface(self) -> None:
        with TempGitRepo() as repo:
            new_file = repo / "portfolio-service/src/test/java/com/wealth/portfolio/UnreportedIT.java"
            new_file.parent.mkdir(parents=True, exist_ok=True)
            new_file.write_text("package com.wealth.portfolio;\nclass UnreportedIT {}\n", encoding="utf-8")
            run_git(repo, "add", ".")
            run_git(repo, "commit", "-q", "-m", "add UnreportedIT")
            base_sha = run_git(repo, "log", "--reverse", "--format=%H").splitlines()[0]
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            # Only seed the `test` report dir with a FAILING class and no floor match; leave
            # integrationTest reports missing entirely; don't stage a jar.
            write_report(
                repo / "portfolio-service/build/test-results/test/TEST-Unrelated.xml",
                "com.wealth.portfolio.UnrelatedTest", tests=1, failures=1,
            )
            (repo / "portfolio-service/build/test-results/integrationTest").mkdir(parents=True)
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.run_evidence(repo, self._policy(base_sha), marker)
            message = str(ctx.exception)
            self.assertIn("UnrelatedTest", message)  # failure
            self.assertIn("AssetCatalogControllerTest", message)  # missing floor pattern
            self.assertIn("ConcurrentCompositionIT", message)  # missing floor pattern (empty dir)
            self.assertIn("UnreportedIT", message)  # discovery reconciliation
            self.assertIn("bootJar", message)  # stage problem present (build/libs never created)

    def test_stale_bootjar_with_fresh_reports_fails(self) -> None:
        """Reviewer-reported regression: a bootJar Gradle left untouched (UP-TO-DATE, or a stale
        file dropped in by hand) must fail even though the JUnit reports next to it are fresh and
        the staged copy's SHA matches it byte-for-byte -- SHA equality alone only proves the copy
        step was faithful, not that this run produced the JAR being copied."""
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            # The "old" bootJar and its staged copy exist *before* the marker is written.
            self._seed_jar(repo)
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            # Reports are fresh (written after the marker) -- everything else about this run looks
            # legitimate.
            self._seed_reports(repo)
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.run_evidence(repo, self._policy(base_sha), marker)
            message = str(ctx.exception)
            self.assertIn("stale", message)
            self.assertIn("portfolio-service.jar", message)

    def test_content_drift_on_already_dirty_file_is_caught(self) -> None:
        """Reviewer-reported regression: editing a file that was *already* dirty at mark time must
        still be caught. A status-text digest cannot see this -- the porcelain category ('M path')
        is identical before and after; only the file's bytes changed."""
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            dirty_file = repo / "README.md"
            dirty_file.write_text("already dirty at mark time\n", encoding="utf-8")
            marker = ev.write_marker(
                repo, repo / ".candidate-artifacts" / "marker.json", allow_dirty=True
            )
            self.assertEqual(marker["mode"], "LOCAL_DEV")
            self._seed_reports(repo)
            self._seed_jar(repo)
            # Edit the SAME already-dirty file again. Its git-status category ('M README.md') does
            # not change, only its content.
            dirty_file.write_text("edited again mid-run\n", encoding="utf-8")
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.run_evidence(repo, self._policy(base_sha), marker)
            self.assertIn("drift", str(ctx.exception))

    def test_renamed_test_file_is_still_discovered(self) -> None:
        """Reviewer-reported regression: a renamed *Test.java/*IT.java file must not disappear from
        discovery. `--name-only` never prints the old side of a detected rename, so plain
        `--diff-filter=AM` (without --no-renames) drops it from the output under either path.

        The base commit must be set *after* OldNameTest.java already exists. Setting it earlier (so
        the file is both added and renamed within base..HEAD) makes git's diff between the two
        *trees* see a plain addition of RenamedTest.java -- there is no old path in the base tree to
        pair it with as a rename -- which every filter variant (buggy or fixed) finds equally well
        and would not have caught the reviewer-reported bug at all.
        """
        globs = ["portfolio-service/src/test/java/**/*Test.java", "portfolio-service/src/test/java/**/*IT.java"]
        old_rel = "portfolio-service/src/test/java/com/wealth/portfolio/OldNameTest.java"
        new_rel = "portfolio-service/src/test/java/com/wealth/portfolio/RenamedTest.java"
        with TempGitRepo() as repo:
            old_path = repo / old_rel
            old_path.parent.mkdir(parents=True, exist_ok=True)
            old_path.write_text(
                "package com.wealth.portfolio;\n"
                "class OldNameTest {\n"
                "  @org.junit.jupiter.api.Test\n"
                "  void t() { int x = 1; int y = 2; int z = x + y; System.out.println(z); }\n"
                "}\n",
                encoding="utf-8",
            )
            run_git(repo, "add", ".")
            run_git(repo, "commit", "-q", "-m", "add OldNameTest")
            base_sha = ev.git_head(repo)  # base is captured AFTER OldNameTest already exists

            run_git(repo, "mv", old_rel, new_rel)
            (repo / new_rel).write_text(
                (repo / new_rel).read_text(encoding="utf-8").replace("OldNameTest", "RenamedTest"),
                encoding="utf-8",
            )
            run_git(repo, "add", ".")
            run_git(repo, "commit", "-q", "-m", "rename to RenamedTest")

            # Self-check: confirm this fixture actually exercises the bug in this git installation.
            status_out = run_git(repo, "diff", "--name-status", f"{base_sha}..HEAD")
            self.assertTrue(
                status_out.strip().startswith("R"),
                f"fixture does not produce a git-detected rename, so it cannot reproduce the bug: {status_out!r}",
            )
            buggy_out = run_git(
                repo, "diff", "--name-only", "--diff-filter=AM", f"{base_sha}..HEAD", "--", *globs
            )
            self.assertNotIn(
                "RenamedTest.java", buggy_out,
                "the pre-fix command (no --no-renames) unexpectedly found the file; this fixture no "
                "longer reproduces the reviewer-reported bug",
            )

            found = ev.discover_b1_test_files(repo, base_sha, globs)
            self.assertTrue(any(f.endswith("RenamedTest.java") for f in found), found)

    def test_base_sha_override_must_match_policy(self) -> None:
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            self._seed_reports(repo)
            self._seed_jar(repo)
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.run_evidence(repo, self._policy(base_sha), marker, base_sha_override="0" * 40)
            self.assertIn("does not match the policy-pinned", str(ctx.exception))

    def test_dirty_tree_refused_without_allow_dirty(self) -> None:
        with TempGitRepo() as repo:
            (repo / "README.md").write_text("dirty\n", encoding="utf-8")
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            self.assertIn("not clean", str(ctx.exception))

    def test_quoted_unicode_filename_content_drift_is_caught(self) -> None:
        """Reviewer-reported regression, corrected: the file must already be dirty *at mark time*,
        then edited *again* afterward. A clean-to-dirty transition is caught by the mere appearance
        of a new status entry regardless of whether its path is parsed correctly -- that alone does
        not exercise the quoting bug and passed even against the old, naive `.strip('"')` parser.
        The real failure mode (mirroring the already-dirty-file regression) is: café.txt is already
        dirty when marked, so the pre-fix parser mis-resolves its path to something that does not
        exist on disk and hashes it as a constant 'ABSENT' placeholder; a *second* edit leaves the
        status *category* unchanged ('?? café.txt' both times), so only that placeholder -- which
        never changes -- would be compared, and the edit goes undetected.
        """
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            cafe_file = repo / "café.txt"
            cafe_file.write_text("dirty at mark time\n", encoding="utf-8")  # already dirty, untracked

            marker = ev.write_marker(
                repo, repo / ".candidate-artifacts" / "marker.json", allow_dirty=True
            )
            self.assertEqual(marker["mode"], "LOCAL_DEV")

            # Self-check: confirm this fixture actually exercises the quoting bug in this git
            # installation, the same way the rename regression confirms git detects the rename.
            # The naive pre-fix parser (strip quotes off porcelain-v1 *text*, no escape decoding)
            # must resolve to a path that does NOT exist on disk; the fixed NUL-delimited parser
            # must resolve to the real file.
            text_status = subprocess.run(
                ["git", "-C", str(repo), "status", "--porcelain=v1", "--untracked-files=all"],
                capture_output=True, text=True, check=True,
            ).stdout
            cafe_line = next(line for line in text_status.splitlines() if "caf" in line)
            naive_path = cafe_line[3:].strip().strip('"')
            self.assertFalse(
                (repo / naive_path).is_file(),
                f"fixture no longer reproduces the quoting bug -- naive path {naive_path!r} "
                "resolves to a real file",
            )
            fixed_records = ev._git_status_records(repo)
            fixed_path = next(path for _, path in fixed_records if "caf" in path)
            self.assertTrue(
                (repo / fixed_path).is_file(),
                f"fixed parser path {fixed_path!r} does not resolve to the real file",
            )

            self._seed_reports(repo)
            self._seed_jar(repo)
            # Edit the SAME already-dirty file again -- its status category ('?? café.txt') does
            # not change, only its content.
            cafe_file.write_text("edited again mid-run\n", encoding="utf-8")
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.run_evidence(repo, self._policy(base_sha), marker)
            self.assertIn("drift", str(ctx.exception))


class RunManifestCheckTests(unittest.TestCase):
    """`run_manifest_check` backs `candidateManifestValidation`, the Gradle task that gates
    `prepareCandidateArtifact`. It must apply the *same* marker-based freshness/identity checks as
    the full post-staging `evidence` bundle -- otherwise a stale report or an untouched bootJar
    would satisfy every content-only check here and still get staged, only to be caught (too late)
    by `evidence` afterward."""

    def _policy(self, base_sha: str) -> dict:
        return {
            "b1_base_commit": {"sha": base_sha},
            "report_dirs": {
                "test": "portfolio-service/build/test-results/test",
                "integrationTest": "portfolio-service/build/test-results/integrationTest",
            },
            "candidate_floor": {"entries": FLOOR},
            "discovery": {
                "test_file_globs": [
                    "portfolio-service/src/test/java/**/*Test.java",
                    "portfolio-service/src/test/java/**/*IT.java",
                ],
                "helper_class_allowlist": [],
            },
            "staging": {
                "bootjar_dir": "portfolio-service/build/libs",
                "bootjar_glob": "*.jar",
                "staged_path": ".candidate-artifacts/portfolio-service.jar",
            },
        }

    def _seed_reports(self, repo: Path) -> None:
        write_report(
            repo / "portfolio-service/build/test-results/test/TEST-AssetCatalogControllerTest.xml",
            "com.wealth.portfolio.AssetCatalogControllerTest", tests=1,
        )
        write_report(
            repo / "portfolio-service/build/test-results/integrationTest/TEST-ConcurrentCompositionIT.xml",
            "com.wealth.portfolio.composition.ConcurrentCompositionIT", tests=1,
        )

    def _seed_bootjar(self, repo: Path) -> None:
        libs = repo / "portfolio-service/build/libs"
        libs.mkdir(parents=True)
        (libs / "portfolio-service.jar").write_bytes(b"jar-bytes")

    def test_passes_before_staging_when_everything_is_fresh(self) -> None:
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            self._seed_reports(repo)
            self._seed_bootjar(repo)  # no staged copy yet -- manifest-check must not require one
            result = ev.run_manifest_check(repo, self._policy(base_sha), marker)
            self.assertEqual(result["status"], "PASS")

    def test_stale_report_fails_before_staging_can_run(self) -> None:
        """Reviewer-reported regression: this gate previously did no freshness checking at all and
        accepted a stale-report fixture, letting staging run on a graph that was never actually
        re-executed this run."""
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            # Reports predate the marker -- as if the task was considered UP-TO-DATE.
            self._seed_reports(repo)
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            self._seed_bootjar(repo)
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.run_manifest_check(repo, self._policy(base_sha), marker)
            self.assertIn("stale", str(ctx.exception))

    def test_stale_bootjar_fails_before_staging_can_run(self) -> None:
        with TempGitRepo() as repo:
            base_sha = ev.git_head(repo)
            self._seed_bootjar(repo)  # predates the marker
            marker = ev.write_marker(repo, repo / ".candidate-artifacts" / "marker.json")
            self._seed_reports(repo)
            with self.assertRaises(ev.EvidenceError) as ctx:
                ev.run_manifest_check(repo, self._policy(base_sha), marker)
            message = str(ctx.exception)
            self.assertIn("stale", message)
            self.assertIn("portfolio-service.jar", message)


if __name__ == "__main__":
    unittest.main()
