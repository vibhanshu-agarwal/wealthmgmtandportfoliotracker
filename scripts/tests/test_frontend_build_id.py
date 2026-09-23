#!/usr/bin/env python3
"""Unit tests for frontend_build_id: derive the Next build id from a built export.

The deploy workflow must publish the build id of the artifact it is *uploading*, so the
served id can later be compared against an independent value. Reading the expected id off
the deployed site instead would compare the served page with itself.
"""

from __future__ import annotations

import importlib.util
import json
import os
import threading
import sys
import tempfile
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

SCRIPT = (
    Path(__file__).resolve().parents[2]
    / ".github"
    / "workflows"
    / "scripts"
    / "frontend_build_id.py"
)


def _load():
    spec = importlib.util.spec_from_file_location("frontend_build_id", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _export(root: str, *, build_dirs=(), reserved=(), files=()) -> str:
    static = Path(root, "out", "_next", "static")
    static.mkdir(parents=True)
    for name in (*build_dirs, *reserved):
        Path(static, name).mkdir()
    for name in files:
        Path(static, name).write_text("x", encoding="utf-8")
    return str(Path(root, "out"))


class TestBuildIdFromExport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_returns_the_single_non_reserved_directory(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(root, build_dirs=("vWPoXAj5HkPqr86nWtuE7",))
            self.assertEqual(self.mod.build_id_from_export(out), "vWPoXAj5HkPqr86nWtuE7")

    def test_ignores_reserved_sibling_directories(self):
        # A real export from this repo contains chunks/ and media/ beside the build id.
        with tempfile.TemporaryDirectory() as root:
            out = _export(
                root,
                build_dirs=("vWPoXAj5HkPqr86nWtuE7",),
                reserved=("chunks", "css", "media", "webpack", "development"),
            )
            self.assertEqual(self.mod.build_id_from_export(out), "vWPoXAj5HkPqr86nWtuE7")

    def test_ignores_files_beside_the_build_directory(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(
                root, build_dirs=("vWPoXAj5HkPqr86nWtuE7",), files=("stray.js", "x.txt")
            )
            self.assertEqual(self.mod.build_id_from_export(out), "vWPoXAj5HkPqr86nWtuE7")

    def test_fails_closed_when_the_static_directory_is_absent(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "out").mkdir()
            with self.assertRaises(ValueError) as caught:
                self.mod.build_id_from_export(str(Path(root, "out")))
            self.assertIn("_next/static", str(caught.exception))

    def test_fails_closed_when_no_build_directory_is_present(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(root, reserved=("chunks", "media"))
            with self.assertRaises(ValueError) as caught:
                self.mod.build_id_from_export(out)
            self.assertIn("no build id directory", str(caught.exception))

    def test_fails_closed_when_the_build_directory_is_ambiguous(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(root, build_dirs=("buildIdOne123", "buildIdTwo456"))
            with self.assertRaises(ValueError) as caught:
                self.mod.build_id_from_export(out)
            self.assertIn("ambiguous", str(caught.exception))

    def test_fails_closed_on_a_directory_name_that_is_not_a_build_id(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(root, build_dirs=("no",))
            with self.assertRaises(ValueError):
                self.mod.build_id_from_export(out)

    def test_main_prints_the_id_and_writes_the_step_output(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(root, build_dirs=("vWPoXAj5HkPqr86nWtuE7",), reserved=("chunks",))
            output = Path(root, "gh-output")
            argv = ["frontend_build_id.py", "--export-dir", out]
            env = {**os.environ, "GITHUB_OUTPUT": str(output)}
            with mock.patch.dict(os.environ, env, clear=True), \
                    mock.patch.object(sys, "argv", argv):
                self.assertEqual(self.mod.main(), 0)
            self.assertIn("build_id=vWPoXAj5HkPqr86nWtuE7", output.read_text(encoding="utf-8"))

    def test_main_fails_closed_on_an_unusable_export(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(root, reserved=("chunks",))
            argv = ["frontend_build_id.py", "--export-dir", out]
            with mock.patch.dict(os.environ, {}, clear=True), \
                    mock.patch.object(sys, "argv", argv):
                self.assertEqual(self.mod.main(), 1)


class TestServedBuildIdComparison(unittest.TestCase):
    """The served id must be checked against the emitted one, never against itself."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_no_errors_when_the_served_id_equals_the_emitted_id(self):
        self.assertEqual(self.mod.served_build_id_errors("abc123def", "abc123def"), [])

    def test_reports_a_served_id_that_differs(self):
        errors = self.mod.served_build_id_errors("abc123def", "zzz999yyy")
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("does not equal", errors[0])

    def test_reports_a_blank_emitted_id(self):
        # A blank emitted id would make any served id "match" a missing expectation.
        errors = self.mod.served_build_id_errors("", "abc123def")
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("emitted", errors[0])

    def test_reports_a_blank_served_id(self):
        errors = self.mod.served_build_id_errors("abc123def", "")
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("served", errors[0])



def _flight_html(build_id, static_id=None):
    """Minimal Next flight payload naming build_id in the root row's `b` key."""
    row = '0:{\\"b\\":\\"' + build_id + '\\",\\"p\\":\\"\\"}'
    script = 'self.__next_f.push([1,"' + row + '"])'
    asset = '<script src="/_next/static/' + (static_id or build_id) + '/main.js"></script>'
    return "<html><body>" + asset + "<script>" + script + "</script></body></html>"


REPO_ROOT = Path(__file__).resolve().parents[2]


class TestServedHtmlExtraction(unittest.TestCase):
    """The served-side extractor must BE verify_step_b_5b's, not a second copy of it."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_reads_the_build_id_from_served_html(self):
        html = _flight_html("xHLycg2EB2LnlAniJ-SbA")
        self.assertEqual(self.mod.build_id_from_html(html, REPO_ROOT), "xHLycg2EB2LnlAniJ-SbA")

    def test_delegates_to_verify_step_b_5b_rather_than_reimplementing(self):
        shared = self.mod.load_served_extractor(REPO_ROOT)
        self.assertEqual(shared.__name__, "extract_build_id")
        html = _flight_html("vWPoXAj5HkPqr86nWtuE7")
        self.assertEqual(self.mod.build_id_from_html(html, REPO_ROOT), shared(html))

    def test_fails_closed_when_the_shared_extractor_cannot_be_loaded(self):
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(ValueError) as caught:
                self.mod.load_served_extractor(Path(empty))
            self.assertIn("verify_step_b_5b", str(caught.exception))

    def test_fails_closed_on_html_with_no_build_id(self):
        with self.assertRaises(ValueError):
            self.mod.build_id_from_html("<html><body>nothing</body></html>", REPO_ROOT)

    def test_fails_closed_on_an_ambiguous_page(self):
        html = _flight_html("buildIdOne123", static_id="chunks") + _flight_html("buildIdTwo456", static_id="chunks")
        with self.assertRaises(ValueError):
            self.mod.build_id_from_html(html, REPO_ROOT)


class TestFrontendServingPredicate(unittest.TestCase):
    """post-deploy served id must differ from pre-deploy AND equal the emitted id."""

    EMITTED = "xHLycg2EB2LnlAniJ-SbA"
    PRE = "vWPoXAj5HkPqr86nWtuE7"

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def test_passes_when_the_origin_changed_to_the_emitted_build(self):
        self.assertEqual(self.mod.frontend_serving_errors(self.EMITTED, self.PRE, self.EMITTED), [])

    def test_rejects_a_served_build_that_is_not_the_emitted_one(self):
        errors = self.mod.frontend_serving_errors(self.EMITTED, self.PRE, "otherBuild99")
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("does not equal the emitted", errors[0])

    def test_reports_both_faults_when_nothing_changed_and_nothing_matches(self):
        errors = self.mod.frontend_serving_errors(self.EMITTED, self.PRE, self.PRE)
        self.assertEqual(len(errors), 2, errors)
        self.assertTrue(any("equals the pre-deploy" in e for e in errors), errors)

    def test_rejects_a_no_op_reupload_rather_than_passing_it_silently(self):
        errors = self.mod.frontend_serving_errors(self.EMITTED, self.EMITTED, self.EMITTED)
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("equals the pre-deploy", errors[0])

    def test_requires_each_of_the_three_ids(self):
        for emitted, pre, served, needle in (
            ("", self.PRE, self.EMITTED, "emitted"),
            (self.EMITTED, "", self.EMITTED, "pre-deploy"),
            (self.EMITTED, self.PRE, "", "post-deploy"),
        ):
            with self.subTest(needle=needle):
                errors = self.mod.frontend_serving_errors(emitted, pre, served)
                self.assertEqual(len(errors), 1, errors)
                self.assertIn(needle, errors[0])


class TestBoundedServedFetch(unittest.TestCase):
    """Retries wait for propagation; they never retry until a desired answer appears."""

    PRE = "vWPoXAj5HkPqr86nWtuE7"
    NEW = "xHLycg2EB2LnlAniJ-SbA"

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def _fetcher(self, sequence):
        calls = {"n": 0}

        def fetch(url):
            item = sequence[min(calls["n"], len(sequence) - 1)]
            calls["n"] += 1
            if isinstance(item, Exception):
                raise item
            return item

        return fetch, calls

    def _run(self, sequence, attempts=5):
        fetch, calls = self._fetcher(sequence)
        served, used = self.mod.fetch_served_build_id(
            "https://example.test/login", self.PRE, REPO_ROOT,
            attempts=attempts, delay_seconds=0, fetch=fetch, sleep=lambda _s: None,
        )
        return served, used, calls

    def test_stops_at_the_first_page_that_differs_from_the_pre_deploy_build(self):
        served, used, calls = self._run([_flight_html(self.PRE), _flight_html(self.NEW)])
        self.assertEqual((served, used, calls["n"]), (self.NEW, 2, 2))

    def test_never_exceeds_the_attempt_bound(self):
        served, used, calls = self._run([_flight_html(self.PRE)])
        self.assertEqual((served, used), (self.PRE, 5))
        self.assertEqual(calls["n"], 5, "the bound is on requests, not on successes")

    def test_a_transport_error_stops_immediately_instead_of_being_retried(self):
        # Only a positively identified OLD build id means "still propagating". Anything
        # else is a fault, and retrying it would spend the request budget hiding it.
        with self.assertRaises(self.mod.ServedBuildIdError) as caught:
            self._run([OSError("boom"), _flight_html(self.NEW)])
        self.assertEqual(caught.exception.requests_made, 1)

    def test_an_ambiguous_page_fails_immediately_and_is_never_retried(self):
        # A page naming two build ids is a parse fault, not propagation. Retrying it let a
        # later matching page mask the ambiguity entirely.
        ambiguous = _flight_html("buildIdOne123", static_id="chunks") + _flight_html(
            "buildIdTwo456", static_id="chunks"
        )
        with self.assertRaises(self.mod.ServedBuildIdError) as caught:
            self._run([ambiguous, _flight_html(self.NEW)])
        self.assertEqual(caught.exception.requests_made, 1)

    def test_a_page_with_no_build_id_fails_immediately(self):
        with self.assertRaises(self.mod.ServedBuildIdError) as caught:
            self._run(["<html>nothing</html>", _flight_html(self.NEW)])
        self.assertEqual(caught.exception.requests_made, 1)

    def test_rejects_an_unbounded_or_nonsensical_attempt_count(self):
        for attempts in (0, -1, 26):
            with self.subTest(attempts=attempts):
                with self.assertRaises(ValueError):
                    self.mod.fetch_served_build_id(
                        "https://example.test/login", self.PRE, REPO_ROOT,
                        attempts=attempts, delay_seconds=0,
                        fetch=lambda _u: "", sleep=lambda _s: None,
                    )


class TestCliServedModes(unittest.TestCase):
    """Exactly one mode per invocation; the verify mode writes run-bound evidence."""

    EMITTED = "xHLycg2EB2LnlAniJ-SbA"
    PRE = "vWPoXAj5HkPqr86nWtuE7"

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def _main(self, argv, env=None, fetch=None):
        full = {"GITHUB_RUN_ID": "1", "GITHUB_RUN_ATTEMPT": "1", **(env or {})}
        with mock.patch.dict(os.environ, full, clear=True), mock.patch.object(
            sys, "argv", ["frontend_build_id.py", *argv]
        ):
            if fetch is not None:
                with mock.patch.object(self.mod, "_http_get", fetch):
                    return self.mod.main()
            return self.mod.main()

    def test_capture_mode_prints_the_currently_served_id(self):
        with tempfile.TemporaryDirectory() as root:
            out = Path(root, "gh-output")
            code = self._main(
                ["--capture-served", "--url", "https://example.test/login",
                 "--repo-root", str(REPO_ROOT)],
                env={"GITHUB_OUTPUT": str(out)},
                fetch=lambda _u: _flight_html(self.PRE),
            )
            self.assertEqual(code, 0)
            self.assertIn(f"served_build_id={self.PRE}", out.read_text(encoding="utf-8"))

    def test_capture_mode_fails_closed_when_no_id_can_be_read(self):
        code = self._main(
            ["--capture-served", "--url", "https://example.test/login",
             "--repo-root", str(REPO_ROOT)],
            fetch=lambda _u: "<html>nothing</html>",
        )
        self.assertEqual(code, 1)

    def test_verify_mode_writes_run_bound_evidence_on_success(self):
        with tempfile.TemporaryDirectory() as root:
            evidence = Path(root, "evidence.json")
            code = self._main(
                ["--verify-served", "--url", "https://example.test/login",
                 "--emitted-id", self.EMITTED, "--pre-deploy-id", self.PRE,
                 "--attempts", "3", "--delay-seconds", "0",
                 "--evidence-out", str(evidence), "--repo-root", str(REPO_ROOT)],
                env={"GITHUB_RUN_ID": "987", "GITHUB_RUN_ATTEMPT": "2", "GITHUB_SHA": "d" * 40},
                fetch=lambda _u: _flight_html(self.EMITTED),
            )
            self.assertEqual(code, 0)
            record = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(record["emitted_build_id"], self.EMITTED)
            self.assertEqual(record["pre_deploy_served_build_id"], self.PRE)
            self.assertEqual(record["post_deploy_served_build_id"], self.EMITTED)
            self.assertEqual(record["run_id"], "987")
            self.assertEqual(record["run_attempt"], "2")
            self.assertEqual(record["commit_sha"], "d" * 40)
            self.assertEqual(record["verdict"], "PASS")
            self.assertTrue(record["usable_for_a4"])
            self.assertEqual(record["errors"], [])

    def test_verify_mode_fails_and_marks_evidence_unusable_on_mismatch(self):
        with tempfile.TemporaryDirectory() as root:
            evidence = Path(root, "evidence.json")
            code = self._main(
                ["--verify-served", "--url", "https://example.test/login",
                 "--emitted-id", self.EMITTED, "--pre-deploy-id", self.PRE,
                 "--attempts", "2", "--delay-seconds", "0",
                 "--evidence-out", str(evidence), "--repo-root", str(REPO_ROOT)],
                fetch=lambda _u: _flight_html("someOtherBuild1"),
            )
            self.assertEqual(code, 1)
            record = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(record["verdict"], "FAIL")
            self.assertFalse(record["usable_for_a4"])
            self.assertTrue(record["errors"])

    def test_verify_mode_records_the_request_count_it_actually_made(self):
        with tempfile.TemporaryDirectory() as root:
            evidence = Path(root, "evidence.json")
            self._main(
                ["--verify-served", "--url", "https://example.test/login",
                 "--emitted-id", self.EMITTED, "--pre-deploy-id", self.PRE,
                 "--attempts", "4", "--delay-seconds", "0",
                 "--evidence-out", str(evidence), "--repo-root", str(REPO_ROOT)],
                fetch=lambda _u: _flight_html(self.PRE),
            )
            record = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(record["requests_made"], 4)
            self.assertEqual(record["verdict"], "FAIL")

    def test_verify_mode_records_a_page_fault_instead_of_retrying_past_it(self):
        ambiguous = _flight_html("buildIdOne123", static_id="chunks") + _flight_html(
            "buildIdTwo456", static_id="chunks"
        )
        with tempfile.TemporaryDirectory() as root:
            evidence = Path(root, "evidence.json")
            code = self._main(
                ["--verify-served", "--url", "https://example.test/login",
                 "--emitted-id", self.EMITTED, "--pre-deploy-id", self.PRE,
                 "--attempts", "5", "--delay-seconds", "0",
                 "--evidence-out", str(evidence), "--repo-root", str(REPO_ROOT)],
                fetch=lambda _u: ambiguous,
            )
            self.assertEqual(code, 1)
            record = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(record["verdict"], "FAIL")
            self.assertFalse(record["usable_for_a4"])
            self.assertEqual(record["requests_made"], 1, "a page fault must not be retried")
            self.assertIsNone(record["post_deploy_served_build_id"])
            self.assertTrue(any("build id" in e for e in record["errors"]), record["errors"])

    def test_verify_mode_records_a_transport_fault_without_spending_the_budget(self):
        with tempfile.TemporaryDirectory() as root:
            evidence = Path(root, "evidence.json")
            code = self._main(
                ["--verify-served", "--url", "https://example.test/login",
                 "--emitted-id", self.EMITTED, "--pre-deploy-id", self.PRE,
                 "--attempts", "5", "--delay-seconds", "0",
                 "--evidence-out", str(evidence), "--repo-root", str(REPO_ROOT)],
                fetch=lambda _u: (_ for _ in ()).throw(OSError("connection reset")),
            )
            self.assertEqual(code, 1)
            record = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual((record["verdict"], record["requests_made"]), ("FAIL", 1))

    def test_rejects_more_than_one_mode_and_rejects_none(self):
        with tempfile.TemporaryDirectory() as root:
            out = _export(root, build_dirs=("vWPoXAj5HkPqr86nWtuE7",))
            for argv in (
                ["--export-dir", out, "--capture-served", "--url", "https://example.test/login"],
                [],
            ):
                with self.subTest(argv=argv):
                    with self.assertRaises(SystemExit):
                        self._main(argv)


class TestSingleOriginRequests(unittest.TestCase):
    """One counted request must be exactly one GET of one origin."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load()

    def _serve(self, handler_cls):
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler_cls)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_address[1]}"

    def test_refuses_to_follow_a_redirect(self):
        # urlopen follows redirects by default, so one "attempt" could be several GETs, and
        # the last one need not even be the origin that was asked for.
        class Redirector(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                self.send_response(302)
                self.send_header("Location", "https://example.invalid/elsewhere")
                self.end_headers()

            def log_message(self, *_args):
                pass

        origin = self._serve(Redirector)
        with self.assertRaises(Exception) as caught:
            self.mod._http_get(f"{origin}/login")
        self.assertIn("redirect", str(caught.exception).lower())

    def test_reads_a_direct_200_normally(self):
        body = _flight_html("xHLycg2EB2LnlAniJ-SbA").encode("utf-8")

        class Direct(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802
                self.send_response(200)
                self.send_header("content-type", "text/html")
                self.send_header("content-length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                pass

        origin = self._serve(Direct)
        html = self.mod._http_get(f"{origin}/login")
        self.assertIn("xHLycg2EB2LnlAniJ-SbA", html)


if __name__ == "__main__":
    unittest.main()
