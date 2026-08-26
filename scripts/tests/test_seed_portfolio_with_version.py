#!/usr/bin/env python3
"""Request-capture tests for seed-portfolio-with-version.sh."""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / ".github" / "workflows" / "scripts" / "seed-portfolio-with-version.sh"

E2E_USER_ID = "00000000-0000-0000-0000-000000000e2e"
EMAIL = "e2e@example.com"
PASSWORD = "secret"


class FixtureState:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict | None, str | None]] = []
        self.portfolio_payload: object = [
            {"userId": E2E_USER_ID, "version": 7},
        ]
        self.seed_status = 200
        self.seed_body = {"ok": True}


STATE = FixtureState()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format: str, *args) -> None:  # noqa: A003
        return

    def _read_json(self) -> dict | None:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return None
        raw = self.rfile.read(length)
        return json.loads(raw.decode("utf-8"))

    def do_POST(self) -> None:  # noqa: N802
        body = self._read_json()
        auth = self.headers.get("Authorization")
        STATE.calls.append(("POST", self.path, body, auth))
        if self.path == "/api/auth/login":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(
                json.dumps({"token": "tok", "userId": E2E_USER_ID}).encode("utf-8")
            )
            return
        if self.path == "/api/internal/portfolio/seed":
            self.send_response(STATE.seed_status)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(STATE.seed_body).encode("utf-8"))
            return
        self.send_response(404)
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        auth = self.headers.get("Authorization")
        STATE.calls.append(("GET", self.path, None, auth))
        if self.path == "/api/portfolio":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(STATE.portfolio_payload).encode("utf-8"))
            return
        self.send_response(404)
        self.end_headers()


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])



def _to_bash_path(path: Path) -> str:
    """Convert a Windows path to a Git-Bash-friendly absolute path."""
    resolved = path.resolve()
    as_posix = resolved.as_posix()
    if os.name != "nt":
        return as_posix
    # D:/foo/bar -> /d/foo/bar
    drive, rest = os.path.splitdrive(str(resolved))
    rest = rest.replace("\\", "/")
    if not rest.startswith("/"):
        rest = "/" + rest
    return f"/{drive[0].lower()}{rest}"

class SeedPortfolioWithVersionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not SCRIPT.is_file():
            raise unittest.SkipTest(f"missing script: {SCRIPT}")
        # Ensure executable bit for bash
        SCRIPT.chmod(SCRIPT.stat().st_mode | 0o111)

    def setUp(self) -> None:
        STATE.calls.clear()
        STATE.portfolio_payload = [{"userId": E2E_USER_ID, "version": 7}]
        STATE.seed_status = 200
        STATE.seed_body = {"ok": True}
        port = free_port()
        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def _run(self) -> subprocess.CompletedProcess[str]:
        import shutil

        script = SCRIPT.relative_to(REPO).as_posix()
        git_bash = Path(r"C:\Program Files\Git\bin\bash.exe")
        bash = str(git_bash) if git_bash.is_file() else "bash"
        jq_dir = REPO / "tools" / "jq"
        drive, rest = os.path.splitdrive(str(jq_dir.resolve()))
        jq_posix = f"/{drive[0].lower()}{rest.replace(chr(92), '/')}"
        command = (
            f'export PATH="{jq_posix}:$PATH"; '
            "export "
            f"API_BASE={self.base!r} "
            "INTERNAL_API_KEY='internal-key' "
            f"E2E_USER_ID={E2E_USER_ID!r} "
            f"E2E_TEST_USER_EMAIL={EMAIL!r} "
            f"E2E_TEST_USER_PASSWORD={PASSWORD!r}; "
            "command -v jq >/dev/null || command -v jq.exe >/dev/null || "
            "{ echo 'jq is required' >&2; exit 127; }; "
            f"bash {script!r}"
        )
        return subprocess.run(
            [bash, "-lc", command],
            capture_output=True,
            text=True,
            check=False,
            cwd=str(REPO),
        )

    def test_success_login_read_seed_with_frozen_version(self) -> None:
        result = self._run()
        self.assertEqual(result.returncode, 0, result.stderr)
        methods = [(m, p) for (m, p, _b, _a) in STATE.calls]
        self.assertEqual(
            methods,
            [
                ("POST", "/api/auth/login"),
                ("GET", "/api/portfolio"),
                ("POST", "/api/internal/portfolio/seed"),
            ],
        )
        seed_body = STATE.calls[2][2]
        self.assertEqual(seed_body, {"expectedVersion": 7})
        self.assertIn("[b1-g5][synthetic-shell] expectedVersion=7", result.stdout)

    def test_malformed_portfolio_does_not_seed(self) -> None:
        STATE.portfolio_payload = {"not": "an-array"}
        result = self._run()
        self.assertNotEqual(result.returncode, 0)
        methods = [(m, p) for (m, p, _b, _a) in STATE.calls]
        self.assertEqual(
            methods,
            [
                ("POST", "/api/auth/login"),
                ("GET", "/api/portfolio"),
            ],
        )

    def test_409_is_terminal_and_prints_body_once(self) -> None:
        STATE.seed_status = 409
        STATE.seed_body = {
            "error": "portfolio_version_conflict",
            "currentVersion": 9,
        }
        result = self._run()
        self.assertNotEqual(result.returncode, 0)
        seed_posts = [c for c in STATE.calls if c[0] == "POST" and c[1].endswith("/seed")]
        self.assertEqual(len(seed_posts), 1)
        self.assertIn("portfolio_version_conflict", result.stderr)
        self.assertIn("currentVersion", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
