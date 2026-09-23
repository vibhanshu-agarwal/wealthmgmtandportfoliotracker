#!/usr/bin/env python3
"""Unit tests for frontend_build_id: derive the Next build id from a built export.

The deploy workflow must publish the build id of the artifact it is *uploading*, so the
served id can later be compared against an independent value. Reading the expected id off
the deployed site instead would compare the served page with itself.
"""

from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
