#!/usr/bin/env python3
"""Offline contract tests for scripts/verify_step_b_5b.py.

Nothing here touches the network, a browser, Azure, production, or a real clock: every
seam (HTTP, root-HTML fetch, subprocess, child process, clock, sleep, prompts, filesystem
root) is injected. Recorders raise a BaseException on an unexpected call so no
'except Exception' in the subject can swallow it, and every recorder also lists the
unexpected call so tests can assert it was never made.

Run:  python -m pytest scripts/tests/test_verify_step_b_5b.py -q
"""

from __future__ import annotations

import base64
import contextlib
import copy
import dataclasses
import datetime
import email.message
import hashlib
import io
import json
import os
import re
import signal
import stat
import subprocess
import sys
import textwrap
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable, Optional

import pytest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

import verify_step_b_5b as subject  # noqa: E402

UTC = datetime.timezone.utc


def dt(text: str) -> datetime.datetime:
    return subject.parse_utc_z(text)


# ---------------------------------------------------------------------------
# Fixtures: identities, secrets (all fake), golden set, timeline
# ---------------------------------------------------------------------------
BASELINE = "0123456789abcdef0123456789abcdef01234567"
SOURCE_SHA = "fedcba9876543210fedcba9876543210fedcba98"
OLD_BUILD_ID = "OldBuildId0000000000a"
NEW_BUILD_ID = "NewBuildId1111111111b"
# Intentionally fake secrets: the sanitizer tests need realistic-looking values. The inline `gitleaks:allow`
# markers tell the repository's Gitleaks scan these are fixtures, not credentials.
PASSWORD = "Sup3r-Secret-Demo-Pw"  # gitleaks:allow
INTERNAL_KEY = "ik-Sup3r-Internal-Key-0001"  # gitleaks:allow
TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkZW1vIn0.c2lnbmF0dXJlLXZhbHVl"  # JWT-shaped, fake  # gitleaks:allow
CATALOG_SHA = "AB" * 32
GOLDEN_ROWS = (("AAPL", "31.00000000"), ("MSFT", "22.00000000"), ("TSLA", "5.00000000"))
DEPLOY_COMPLETED = "2026-09-20T11:00:00Z"
PRE_ATTESTATION_AT = "2026-09-20T11:45:00Z"
RUN_START = "2026-09-20T12:00:00Z"
GATEWAY_DIGEST = "sha256:" + "a" * 64
PORTFOLIO_DIGEST = "sha256:" + "b" * 64
API = subject.API_ORIGIN
FRONTEND = subject.FRONTEND_ORIGIN
# The seed route literal must never appear in a scanned file; build it at runtime.
SEED_ROUTE_LITERAL = "/api/internal/portfolio/" + "se" + "ed"


def oracle_stdout(rows: tuple[tuple[str, str], ...] = GOLDEN_ROWS) -> str:
    return json.dumps(
        {
            "metadata": {"catalogSha256": CATALOG_SHA, "activeEntryCount": len(rows)},
            "wireHoldings": [{"assetTicker": t, "quantity": q} for t, q in rows],
            "persistedHoldings": [],
        }
    )


GOLDEN = subject.parse_oracle_output(oracle_stdout())


def portfolio_body(version: int, rows: tuple[tuple[str, str], ...] = GOLDEN_ROWS) -> list[dict[str, Any]]:
    return [
        {
            "id": "p-1",
            "userId": subject.DEMO_USER_ID,
            "version": version,
            "holdings": [
                {"id": f"h-{i}", "assetTicker": t, "quantity": q} for i, (t, q) in enumerate(rows)
            ],
        }
    ]


DIRTY_ROWS = (("AAPL", "32.00000000"),) + GOLDEN_ROWS[1:]


def attestation_json(
    read_at: str,
    *,
    gateway_revision: str = "api-gateway--0000100",
    gateway_digest: str = GATEWAY_DIGEST,
    portfolio_revision: str = "portfolio-service--0000200",
    portfolio_digest: str = PORTFOLIO_DIGEST,
    extra: Optional[dict[str, Any]] = None,
) -> str:
    document: dict[str, Any] = {
        "schema": subject.ATTESTATION_SCHEMA,
        "readAtUtc": read_at,
        "services": {
            "api-gateway": {"revision": gateway_revision, "digest": gateway_digest},
            "portfolio-service": {"revision": portfolio_revision, "digest": portfolio_digest},
        },
    }
    if extra:
        document.update(extra)
    return json.dumps(document)


def next_html(build_id: str = NEW_BUILD_ID, *, assets: tuple[str, ...] = ("main-1.js", "app-2.js")) -> bytes:
    scripts = "".join(f'<script src="/_next/static/chunks/{a}"></script>' for a in assets)
    flight = 'self.__next_f.push([1,"0:{\\"b\\":\\"%s\\",\\"f\\":[]}"])' % build_id
    return f"<!DOCTYPE html><html><body>{scripts}<script>{flight}</script></body></html>".encode()


def flight_html(*rows: str, assets: tuple[str, ...] = ("app-1.js",), extra: str = "") -> str:
    """HTML shaped like current App Router output: every flight row rides inside a
    self.__next_f.push([1,"..."]) string literal (json.dumps yields the same escaping)."""
    scripts = "".join(f'<script src="/_next/static/chunks/{a}" async=""></script>' for a in assets)
    pushes = "".join("<script>self.__next_f.push([1," + json.dumps(row) + "])</script>" for row in rows)
    return f"<!DOCTYPE html><html><body>{scripts}{pushes}{extra}</body></html>"


# Real shape (frontend/.next/server/app/index.html, minimal excerpt, fake id): the root row `0:{...}`
# lists P, c, q, i, f ... and carries the build id as its LAST top-level key, `b`.
REAL_ROOT_ROW = (
    '0:{"P":null,"c":["",""],"q":"","i":false,"f":[[["",{"children":["__PAGE__",{}]},"$undefined","$undefined",16],'
    '[["$","$1","c",{"children":[[["$","link","0",{"rel":"stylesheet","href":"/_next/static/chunks/app.css"}]]]}]]]],'
    '"S":true,"h":null,"s":"$undefined","l":"$undefined","p":"$undefined","d":"$undefined","b":"%s"}\n'
)


# ---------------------------------------------------------------------------
# Ledger fixtures written by hand from spec Appendix A (the cross-language interface)
# ---------------------------------------------------------------------------
def _h(method: str, path: str, status: int = 200) -> dict[str, Any]:
    return {"method": method, "path": path, "status": status}


LEG_FACTS: dict[str, dict[str, Any]] = {
    "L0": {"finalPathIsPortfolio": True, "headingPortfolio": True, "redirectedToLogin": False,
           "unauthorized401Seen": False},
    "L1": {"editButtonVisible": True, "resetButtonVisible": True, "portfolioLoadStatus": 200,
           "resetEnabled": True},
    "L2": {"freshnessState": "FRESH", "countsValid": True, "stripVisible": True},
    "L3": {"dialogOpen": True, "unavailableNoticeVisible": False},
    "L4": {"catalogStatus": 200, "noIfNoneMatch": True, "etagPresent": True, "assetsNonEmpty": True,
           "rowsRendered": True, "catalogParity": True, "activeCount": 3},
    "L5": {"presenceRequestsSinceOpen": 1, "presenceStatus": 200, "anotherSessionActive": False,
           "requestFailed": False, "corsConsoleError": False},
    "L6": {"priceRequestsAfterUncheck": 1, "allStatus200": True, "arrayShaped": True,
           "nonNullPriceSeen": True, "disjointFromPageBatches": True, "predictedBatchCount": 1},
    "L7": {"pageWriteRequestsBeforeMutation": 0},
    "L8": {"putCount": 1, "putStatus": 200, "expectedVersionMatchesObserved": True,
           "bodyMatchesExpectedDraft": True, "versionAdvanced": True, "savedStatusVisible": True,
           "independentReadVersionMatches": True, "independentReadHoldingsMatch": True,
           "mutationSkippedReason": "NONE"},
    "L9": {"putCount": 1, "putStatus": 200, "noInternalKeyHeader": True,
           "expectedVersionMatchesSaved": True, "versionPlusOne": True, "responseEqualsGolden": True,
           "resetStatusVisible": True, "independentReadEqualsGolden": True,
           "mutationSkippedReason": "NONE"},
}
# Honest per-leg request evidence: it must satisfy the contract's passHttp table (L0, L3 and L7 record
# NO http; every other leg's entries carry the leg's method and path and agree with its count and
# status facts). test_the_honest_fixtures_satisfy_the_http_table pins that.
LEG_HTTP: dict[str, list[dict[str, Any]]] = {
    "L1": [_h("GET", "/api/portfolio")],
    "L2": [_h("GET", "/api/portfolio/summary")],
    "L4": [_h("GET", "/api/assets")],
    "L5": [_h("GET", "/api/presence/demo")],
    "L6": [_h("GET", "/api/market/prices")],
    "L8": [_h("PUT", "/api/portfolio/holdings")],
    "L9": [_h("PUT", "/api/portfolio/demo-reset")],
}


class SpecWriter:
    """Builds ledger lines the way the browser child does: src 'spec', seq from 1."""

    def __init__(self) -> None:
        self.seq = 0
        self.lines: list[dict[str, Any]] = []

    def _base(self, event: str) -> dict[str, Any]:
        self.seq += 1
        return {"seq": self.seq, "tUtc": "2026-09-20T12:05:00.000Z", "src": "spec", "event": event}

    def leg(self, leg: str, status: str = "passed", reason: str = "OK", *,
            facts: Optional[dict[str, Any]] = None, http: Optional[list[dict[str, Any]]] = None) -> None:
        line = self._base("leg")
        line.update(
            {
                "leg": leg, "status": status, "reason": reason,
                "http": copy.deepcopy(LEG_HTTP.get(leg, [])) if http is None else http,
                "facts": dict(LEG_FACTS[leg]) if facts is None else facts,
            }
        )
        self.lines.append(line)

    def armed(self) -> None:
        self.lines.append(self._base("armed"))


def go_ledger() -> list[dict[str, Any]]:
    writer = SpecWriter()
    for i in range(8):
        writer.leg(f"L{i}")
    writer.armed()
    writer.leg("L8")
    writer.leg("L9")
    return writer.lines


def go_ledger_armed_after(legs_before_armed: int) -> list[dict[str, Any]]:
    """The GO ledger with the spec's `armed` event placed after the first N legs (8 is the one honest
    position: after L7's final event and before L8's; 0 is the very first line, 10 the very last)."""
    writer = SpecWriter()
    for i in range(10):
        if i == legs_before_armed:
            writer.armed()
        writer.leg(f"L{i}")
    if legs_before_armed >= 10:
        writer.armed()
    return writer.lines


def visitor_ledger() -> list[dict[str, Any]]:
    writer = SpecWriter()
    for i in range(8):
        facts = dict(LEG_FACTS[f"L{i}"])
        if i == 5:
            facts["anotherSessionActive"] = True
        writer.leg(f"L{i}", facts=facts)
    for leg in ("L8", "L9"):
        writer.leg(leg, "skipped", "SKIPPED_VISITOR_PRESENT", http=[],
                   facts={"mutationSkippedReason": "VISITOR_PRESENT"})
    return writer.lines


def ledger_bytes(lines: list[Any]) -> bytes:
    """Ledger file bytes: a dict becomes one compact JSON line, a str is written raw (a hostile line)."""
    return b"".join(
        (line if isinstance(line, str) else json.dumps(line, separators=(",", ":"))).encode() + b"\n"
        for line in lines
    )


CONTRACT = subject.load_ledger_contract(subject.FileOps(), subject.LEDGER_CONTRACT_PATH)


# ---------------------------------------------------------------------------
# Recorders and fakes
# ---------------------------------------------------------------------------
class _RecorderExhausted(BaseException):
    """An unexpected call. BaseException so 'except Exception' in the subject cannot swallow it."""


class Clock:
    def __init__(self, start: str = RUN_START) -> None:
        self.now = dt(start)
        self.mono = 1000.0
        self.sleeps: list[float] = []

    def now_utc(self) -> datetime.datetime:
        return self.now

    def monotonic(self) -> float:
        return self.mono

    def advance(self, seconds: float) -> None:
        self.now += datetime.timedelta(seconds=seconds)
        self.mono += seconds

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.advance(seconds)


class HttpRecorder:
    """Scripted (status, body) pairs or exceptions; strict about unexpected calls."""

    def __init__(self, responses: list[Any]) -> None:
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []
        self.unexpected: list[dict[str, Any]] = []

    def __call__(self, method: str, url: str, headers: dict[str, str], json_body: Any = None,
                 timeout: float = 30.0) -> tuple[int, Any]:
        call = {"method": method, "url": url, "headers": dict(headers), "body": json_body, "timeout": timeout}
        if not self.responses:
            self.unexpected.append(call)
            raise _RecorderExhausted(f"unexpected HTTP call: {method} {url}")
        self.calls.append(call)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item

    def assert_clean(self) -> None:
        assert not self.unexpected, self.unexpected
        assert not self.responses, self.responses


class FakeServer:
    """A tiny stateful stand-in for the gateway: login, portfolio, demo-reset, break-glass."""

    def __init__(self, clock: Clock, *, version: int = 5,
                 rows: tuple[tuple[str, str], ...] = GOLDEN_ROWS) -> None:
        self.clock = clock
        self.version = version
        self.rows = rows
        self.login_status = 200
        self.login_seconds = 80.0
        self.reset_status: Optional[int] = None  # forced status for demo-reset
        self.break_glass_heals = True
        self.prices_script: list[Any] = []  # warm-up answers in order: (status, body) or an exception
        self.prices_default: tuple[int, Any] = (200, [{"ticker": "AAPL", "price": "190.50"}])
        self.prices_seconds = 0.0  # clock time each warm-up probe takes
        self.login_called_at: Optional[float] = None  # the monotonic time the login call started
        self.calls: list[dict[str, Any]] = []
        self.unexpected: list[dict[str, Any]] = []

    def heal(self) -> None:
        self.rows = GOLDEN_ROWS
        self.version += 1

    def __call__(self, method: str, url: str, headers: dict[str, str], json_body: Any = None,
                 timeout: float = 30.0) -> tuple[int, Any]:
        call = {"method": method, "url": url, "headers": dict(headers), "body": json_body, "timeout": timeout}
        self.calls.append(call)
        path = url[len(API):] if url.startswith(API) else url
        if method == "POST" and path == "/api/auth/login":
            self.login_called_at = self.clock.mono
            self.clock.advance(self.login_seconds)
            return self.login_status, ({"token": TOKEN} if self.login_status == 200 else None)
        if method == "GET" and path == "/api/portfolio":
            return 200, portfolio_body(self.version, self.rows)
        if method == "GET" and path.split("?", 1)[0] == "/api/market/prices":
            self.clock.advance(self.prices_seconds)
            item = self.prices_script.pop(0) if self.prices_script else self.prices_default
            if isinstance(item, BaseException):
                raise item
            return item
        if method == "PUT" and path == "/api/portfolio/demo-reset":
            if self.reset_status is not None:
                return self.reset_status, None
            if json_body["expectedVersion"] != self.version:
                return 409, {"error": "portfolio_version_conflict", "currentVersion": self.version}
            self.heal()
            return 200, portfolio_body(self.version)[0]
        if method == "PUT" and path == "/api/internal/portfolio/demo-reset":
            if headers.get("X-Internal-Api-Key") != INTERNAL_KEY:
                return 403, None
            if self.break_glass_heals:
                self.heal()
            return 200, None
        self.calls.pop()
        self.unexpected.append(call)
        raise _RecorderExhausted(f"unexpected HTTP call: {method} {url}")

    def calls_to(self, method: str, path: str) -> list[dict[str, Any]]:
        return [c for c in self.calls if c["method"] == method and c["url"] == API + path]

    def price_probes(self) -> list[dict[str, Any]]:
        """The warm-up probes (GET /api/market/prices with a query)."""
        return [c for c in self.calls if c["method"] == "GET" and c["url"].startswith(API + "/api/market/prices?")]


class CmdRouter:
    """Stands in for git, the oracle and the Node tool checks."""

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []
        self.unexpected: list[list[str]] = []
        self.results: dict[str, subject.CmdResult] = {
            "git-head": subject.CmdResult(0, BASELINE + "\n"),
            "git-status": subject.CmdResult(0, ""),
            "oracle": subject.CmdResult(0, oracle_stdout()),
            "node": subject.CmdResult(0, "v22.0.0\n"),
            "playwright": subject.CmdResult(0, ""),
            "browser": subject.CmdResult(0, ""),
        }

    @staticmethod
    def key(argv: list[str]) -> str:
        if argv[0] == "git" and "rev-parse" in argv:
            return "git-head"
        if argv[0] == "git" and "status" in argv:
            return "git-status"
        if "-B" in argv:
            return "oracle"
        if argv[:2] == ["node", "--version"]:
            return "node"
        if argv[:2] == ["node", "-e"] and "executablePath" in argv[2]:
            return "browser"
        if argv[0] == "node" and argv[1] == "-e":
            return "playwright"
        return "unknown"

    def __call__(self, argv: list[str], cwd: Optional[Path], env: Optional[Any],
                 timeout: float) -> subject.CmdResult:
        self.calls.append({"argv": list(argv), "cwd": cwd, "env": dict(env) if env is not None else None})
        key = self.key(argv)
        if key not in self.results:
            self.unexpected.append(list(argv))
            raise _RecorderExhausted(f"unexpected command: {argv}")
        return self.results[key]


class FetchScript:
    """Scripted root-HTML fetches: (status, content-type, body) items or exceptions."""

    def __init__(self, responses: list[Any]) -> None:
        self.responses = list(responses)
        self.urls: list[str] = []
        self.unexpected: list[str] = []

    def __call__(self, url: str, timeout: float) -> tuple[int, str, bytes]:
        if not self.responses:
            self.unexpected.append(url)
            raise _RecorderExhausted(f"unexpected fetch: {url}")
        self.urls.append(url)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def html_ok(build_id: str = NEW_BUILD_ID, **kw: Any) -> tuple[int, str, bytes]:
    return 200, "text/html; charset=utf-8", next_html(build_id, **kw)


class FakeChild:
    """A child process double. interrupt=N makes the first N polls raise KeyboardInterrupt (one
    Ctrl+C is delivered once); survive_kill=True models a tree kill that does not end the child."""

    def __init__(self, clock: Clock, runtime: float, exit_code: int, *, never_exit: bool = False,
                 interrupt: int = 0, survive_kill: bool = False) -> None:
        self.clock = clock
        self.finish_at = clock.mono + runtime
        self.exit_code = exit_code
        self.never_exit = never_exit
        self.interrupts_left = int(interrupt)
        self.survive_kill = survive_kill
        self.killed = False
        self.killed_at: Optional[float] = None  # the monotonic time of the (first) tree kill
        self.kill_calls = 0
        self.closed = False

    def poll(self) -> Optional[int]:
        if self.interrupts_left:
            self.interrupts_left -= 1
            raise KeyboardInterrupt
        if self.killed and not self.survive_kill:
            return -9
        if not self.never_exit and self.clock.mono >= self.finish_at:
            return self.exit_code
        return None

    def kill_tree(self) -> None:
        if not self.killed:
            self.killed_at = self.clock.mono
        self.killed = True
        self.kill_calls += 1

    def close(self) -> None:
        self.closed = True


class FakeBrowser:
    """spawn_child seam: appends a hand-written ledger and sets the server's end state."""

    def __init__(self, harness: "Harness") -> None:
        self.h = harness
        self.lines: Optional[list[dict[str, Any]]] = None  # None: the GO ledger
        self.server_effect = "golden"  # golden | dirty | untouched
        self.exit_code = 0
        self.runtime = 600.0
        self.never_exit = False
        self.interrupt = False
        self.survive_kill = False
        self.spawn_calls: list[dict[str, Any]] = []
        self.handles: list[FakeChild] = []
        self.ledger_at_spawn = ""
        self.golden_at_spawn = ""
        self.server_calls_at_spawn: list[tuple[str, str]] = []
        self.spawn_error: Optional[BaseException] = None

    def __call__(self, argv: list[str], env: Any, cwd: Path, log_path: Path) -> FakeChild:
        if self.spawn_error is not None:
            raise self.spawn_error
        self.spawn_calls.append({"argv": list(argv), "env": dict(env), "cwd": cwd, "log_path": log_path})
        self.server_calls_at_spawn = [(c["method"], c["url"][len(API):]) for c in self.h.server.calls]
        ledger = Path(env["STEP_B_5B_WORK_DIR"]) / "ledger.jsonl"
        self.ledger_at_spawn = ledger.read_text(encoding="utf-8") if ledger.exists() else ""
        golden_file = Path(env["STEP_B_5B_GOLDEN_FILE"])
        self.golden_at_spawn = golden_file.read_text(encoding="utf-8") if golden_file.exists() else ""
        lines = self.lines if self.lines is not None else go_ledger()
        with open(ledger, "ab") as handle:
            handle.write(ledger_bytes(lines))
        if self.server_effect == "golden":
            self.h.server.rows = GOLDEN_ROWS
            self.h.server.version += 2
        elif self.server_effect == "dirty":
            self.h.server.rows = DIRTY_ROWS
            self.h.server.version += 1
        child = FakeChild(self.h.clock, self.runtime, self.exit_code, never_exit=self.never_exit,
                          interrupt=self.interrupt, survive_kill=self.survive_kill)
        self.handles.append(child)
        return child


class _ConfigPresentFs(subject.FileOps):
    def is_file(self, path: Path) -> bool:
        return True if path.name in ("playwright.step-b-5b.config.ts", "cli.js") else super().is_file(path)


class FlakyFs(subject.FileOps):
    """Fails ledger appends to prove write-ahead failures are not silent."""

    def __init__(self, *, fail_ledger_appends: bool = False) -> None:
        self.fail_ledger_appends = fail_ledger_appends

    def append_line(self, path: Path, text: str) -> None:
        if self.fail_ledger_appends and path.name == "ledger.jsonl":
            raise OSError("disk full")
        super().append_line(path, text)


class LedgerEventHookFs(subject.FileOps):
    """Calls `hook(event_name)` right after each orchestrator ledger line is appended, so a test can
    place a Ctrl+C at an exact ledger event (for example the orchestrator's child_done)."""

    def __init__(self, hook: Callable[[str], None]) -> None:
        self.hook = hook

    def append_line(self, path: Path, text: str) -> None:
        super().append_line(path, text)
        if path.name == "ledger.jsonl":
            self.hook(json.loads(text)["event"])


OS_ENVIRON = {
    "PATH": "/usr/bin", "SystemRoot": "C:\\Windows", "TEMP": "/tmp", "HOME": "/home/owner",
    "PLAYWRIGHT_BROWSERS_PATH": "/pw",
    "DEBUG": "pw:api", "PWDEBUG": "1", "NODE_OPTIONS": "--require ./x.js",
    "INTERNAL_API_KEY": "env-internal-key-should-not-propagate",
    "WAVE9_STEP_A_PASSWORD": "env-password-should-not-propagate",
}


class SigintModel:
    """Models Ctrl+C: a press is delivered as KeyboardInterrupt only while no defer_sigint context
    is active; while one is active the press is ignored, as signal.SIG_IGN does."""

    def __init__(self) -> None:
        self.depth = 0
        self.press_on: Optional[str] = None  # the output line at which a press arrives
        self.press_on_event: Optional[str] = None  # the orchestrator ledger event at which a press arrives
        self.delivered = 0
        self.ignored = 0
        self.max_depth = 0

    @contextlib.contextmanager
    def defer(self):  # type: ignore[no-untyped-def]
        self.depth += 1
        self.max_depth = max(self.max_depth, self.depth)
        try:
            yield
        finally:
            self.depth -= 1

    def _press(self) -> None:
        if self.depth:
            self.ignored += 1
        else:
            self.delivered += 1
            raise KeyboardInterrupt

    def at_output(self, text: str) -> None:
        if self.press_on is not None and text.startswith(self.press_on):
            self.press_on = None
            self._press()

    def at_event(self, name: str) -> None:
        if self.press_on_event is not None and name == self.press_on_event:
            self.press_on_event = None
            self._press()


class Harness:
    """Wires every seam to a fake; runs against a real temporary directory."""

    def __init__(self, tmp_path: Path) -> None:
        self.tmp = tmp_path
        self.clock = Clock()
        self.server = FakeServer(self.clock)
        self.cmd = CmdRouter()
        self.fetch = FetchScript([html_ok() for _ in range(4)])
        self.browser = FakeBrowser(self)
        self.fs = subject.FileOps()
        self.out: list[str] = []
        self.err: list[str] = []
        self.getpass_answers: list[str] = [PASSWORD]
        self.prompts: list[str] = []
        self.input_prompts: list[str] = []
        self.owner_delay = 30.0
        self.getpass_delay = 0.0  # seconds the owner spends typing at a masked prompt
        self.post_attestation: Optional[Callable[[Path, Clock], None]] = self._write_post_attestation
        self.http: Callable[..., tuple[int, Any]] = self.server
        self.frontend_dir = tmp_path / "frontend"
        (self.frontend_dir / "tests" / "production-e2e").mkdir(parents=True)
        (self.frontend_dir / subject.PLAYWRIGHT_CONFIG_REL).write_text("// config\n", encoding="utf-8")
        cli = self.frontend_dir / subject.PLAYWRIGHT_CLI_REL
        cli.parent.mkdir(parents=True)
        cli.write_text("// playwright cli\n", encoding="utf-8")
        self.stdin_tty = True
        self.sigint = SigintModel()
        (tmp_path / "attestation-pre.json").write_text(
            attestation_json(PRE_ATTESTATION_AT), encoding="utf-8"
        )

    # -- seams -----------------------------------------------------------
    def getpass_fn(self, prompt: str) -> str:
        self.prompts.append(prompt)
        if not self.getpass_answers:
            raise _RecorderExhausted("unexpected prompt")
        self.clock.advance(self.getpass_delay)
        return self.getpass_answers.pop(0)

    def input_fn(self, prompt: str) -> str:
        self.input_prompts.append(prompt)
        self.clock.advance(self.owner_delay)
        if self.post_attestation is not None:
            self.post_attestation(self.work_dir / "backend-attestation-post.json", self.clock)
        return ""

    @staticmethod
    def _write_post_attestation(path: Path, clock: Clock) -> None:
        read_at = subject.utc_z(clock.now - datetime.timedelta(seconds=5))
        path.write_text(attestation_json(read_at), encoding="utf-8")

    def _out(self, text: str) -> None:
        self.out.append(text)
        self.sigint.at_output(text)

    def seams(self) -> subject.Seams:
        return subject.Seams(
            http_call=self.http, fetch_root_html=self.fetch, run_cmd=self.cmd,
            spawn_child=self.browser, monotonic=self.clock.monotonic, now_utc=self.clock.now_utc,
            sleep=self.clock.sleep, getpass_fn=self.getpass_fn, input_fn=self.input_fn, fs=self.fs,
            out=self._out, err=self.err.append, defer_sigint=self.sigint.defer,
            stdin_isatty=lambda: self.stdin_tty,
        )

    def config(self, **over: Any) -> subject.RunConfig:
        base: dict[str, Any] = dict(
            evidence_output=self.tmp / "evidence.json", baseline_commit=BASELINE,
            deploy_completed_utc=dt(DEPLOY_COMPLETED), bound_seconds=7200,
            pre_deploy_build_id=OLD_BUILD_ID, backend_attestation_pre=self.tmp / "attestation-pre.json",
            deploy_run_id="1234567890", source_head_sha=SOURCE_SHA, frontend_dir=self.frontend_dir,
        )
        base.update(over)
        return subject.RunConfig(**base)

    def run(self, **over: Any) -> int:
        return subject.run_verifier(self.config(**over), self.seams(), OS_ENVIRON)

    def argv(self, *extra: str, cleanup_only: bool = False) -> list[str]:
        args = ["--evidence-output", str(self.evidence_path), "--baseline-commit", BASELINE]
        if not cleanup_only:
            args += [
                "--deploy-completed-utc", DEPLOY_COMPLETED, "--bound-seconds", "7200",
                "--pre-deploy-build-id", OLD_BUILD_ID,
                "--backend-attestation-pre", str(self.tmp / "attestation-pre.json"),
                "--deploy-run-id", "1234567890", "--source-head-sha", SOURCE_SHA,
            ]
        return args + list(extra)

    def main(self, *extra: str, cleanup_only: bool = False) -> int:
        """main(argv) uses the real repo's frontend dir, so pretend its Playwright config exists."""
        self.fs = _ConfigPresentFs()
        return subject.main(self.argv(*extra, cleanup_only=cleanup_only), seams=self.seams(), environ=OS_ENVIRON)

    # -- observations ------------------------------------------------------
    @property
    def evidence_path(self) -> Path:
        return self.tmp / "evidence.json"

    @property
    def work_dir(self) -> Path:
        return self.tmp / "evidence.json.work"

    def artifact(self) -> dict[str, Any]:
        return json.loads(self.evidence_path.read_text(encoding="utf-8"))

    def ledger_events(self) -> list[dict[str, Any]]:
        path = self.work_dir / "ledger.jsonl"
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]

    def assert_no_unexpected(self) -> None:
        assert not self.server.unexpected, self.server.unexpected
        assert not self.cmd.unexpected, self.cmd.unexpected
        assert not self.fetch.unexpected, self.fetch.unexpected

    def all_output_text(self) -> str:
        parts = list(self.out) + list(self.err) + self.prompts + self.input_prompts
        for path in (self.evidence_path, self.work_dir / "ledger.jsonl", self.work_dir / "golden.json"):
            if path.exists():
                parts.append(path.read_text(encoding="utf-8"))
        return "\n".join(parts)


@pytest.fixture
def h(tmp_path: Path) -> Harness:
    return Harness(tmp_path)


# ---------------------------------------------------------------------------
# Pinned literals
# ---------------------------------------------------------------------------
class TestLiteralConstants:
    def test_origins_are_the_two_allowlisted_hosts(self) -> None:
        assert subject.FRONTEND_ORIGIN == "https://vibhanshu-ai-portfolio.dev"
        assert subject.API_ORIGIN == "https://api.vibhanshu-ai-portfolio.dev"
        assert subject.ALLOWED_ORIGINS == (subject.FRONTEND_ORIGIN, subject.API_ORIGIN)

    def test_public_identity_constants(self) -> None:
        assert subject.DEMO_USER_ID == "00000000-0000-0000-0000-0000000d3110"
        assert subject.DEMO_EMAIL == "demo@wealthtracker.dev"

    def test_login_and_deadline_literals(self) -> None:
        assert subject.LOGIN_TIMEOUT_SECONDS == 225.0
        assert subject.LOGIN_GUARD_SECONDS == 165.0
        assert subject.LOGIN_TIMEOUT_SECONDS > subject.LOGIN_GUARD_SECONDS
        assert subject.OVERALL_DEADLINE_SECONDS == 2700.0

    def test_cleanup_and_binding_literals(self) -> None:
        assert subject.CLEANUP_MAX_ATTEMPTS == 3
        assert subject.STABLE_FETCH_COUNT == 3
        assert subject.STABLE_FETCH_MIN_SPACING_SECONDS >= 5.0
        assert subject.MIN_BOUND_SECONDS == 300
        assert subject.DEFAULT_OPERATION_TIMEOUT_SECONDS == 30.0

    def test_schema_names(self) -> None:
        assert subject.EVIDENCE_SCHEMA == "wave10-5b-evidence-v1"
        assert subject.ATTESTATION_SCHEMA == "wave10-5b-backend-attestation-v1"
        assert subject.GOLDEN_SCHEMA == "wave10-5b-golden-v1"
        assert subject.LEDGER_CONTRACT_SCHEMA == "wave10-5b-ledger-contract-v3"
        assert subject.CLEANUP_ARTIFACT_SCHEMA == "wave10-5b-cleanup-v1"

    def test_child_env_names_are_exactly_appendix_b(self) -> None:
        assert set(subject.CHILD_ENV_KEYS) == {
            "STEP_B_5B_FRONTEND_URL", "STEP_B_5B_API_URL", "STEP_B_5B_TOKEN", "STEP_B_5B_EMAIL",
            "STEP_B_5B_USER_ID", "STEP_B_5B_WORK_DIR", "STEP_B_5B_GOLDEN_FILE",
            "STEP_B_5B_BASELINE_VERSION", "STEP_B_5B_ALLOW_ACTIVE_PRESENCE",
            "STEP_B_5B_ACTION_TIMEOUT_MS",
        }

    def test_action_timeout_default_and_bounds(self) -> None:
        assert (subject.DEFAULT_ACTION_TIMEOUT_MS, subject.MIN_ACTION_TIMEOUT_MS, subject.MAX_ACTION_TIMEOUT_MS) == (
            120000, 1000, 600000)
        assert not hasattr(subject, "ACTION_TIMEOUT_MS")  # the old fixed 60000 constant is gone
        assert subject.RunConfig(evidence_output=Path("x"), baseline_commit=BASELINE).action_timeout_ms == 120000

    def test_warmup_literals(self) -> None:
        assert subject.WARMUP_MAX_PROBES == 6 and subject.WARMUP_TIMEOUT_SECONDS == 90.0
        # bounded by time as well as by count, with a pause after a failed probe
        assert subject.WARMUP_BUDGET_SECONDS == 360.0 and subject.WARMUP_RETRY_PAUSE_SECONDS == 10.0

    def test_forbidden_leg_statuses_and_services(self) -> None:
        assert subject.FORBIDDEN_LEG_STATUSES == {409, 429, 503, 504}
        assert subject.ATTESTATION_SERVICES == ("api-gateway", "portfolio-service")

    def test_route_and_evidence_level_tables(self) -> None:
        assert subject.ROUTE_TABLE["9.1"][:2] == ("L4", "production-browser")
        assert subject.ROUTE_TABLE["9.2"][:2] == ("L8", "production-browser; independent read production-API-only")
        assert subject.ROUTE_TABLE["9.3"][:2] == ("L6", "production-browser")
        assert subject.ROUTE_TABLE["9.4"][:2] == ("L5", "production-browser (network status and boolean)")
        assert subject.ROUTE_TABLE["9.5"][:2] == ("L2", "production-browser")
        assert subject.ROUTE_TABLE["reset"][:2] == ("L9", "production-browser; independent read production-API-only")
        assert subject.ROUTE_TABLE["9.1"][2] == "304/If-None-Match revalidation"

    def test_seed_route_literal_is_absent_from_both_files(self) -> None:
        for path in (Path(subject.__file__), Path(__file__)):
            assert SEED_ROUTE_LITERAL not in path.read_text(encoding="utf-8"), path.name

    def test_internal_break_glass_uses_the_demo_reset_route_only(self) -> None:
        source = Path(subject.__file__).read_text(encoding="utf-8")
        assert "/api/internal/portfolio/demo-reset" in source


# ---------------------------------------------------------------------------
# Ledger contract: the shared machine-readable vocabulary (pinned on this side)
# ---------------------------------------------------------------------------
EXPECTED_FACTS: dict[str, dict[str, str]] = {
    "L0": {"finalPathIsPortfolio": "boolean", "headingPortfolio": "boolean",
           "redirectedToLogin": "boolean", "unauthorized401Seen": "boolean"},
    "L1": {"editButtonVisible": "boolean", "resetButtonVisible": "boolean",
           "portfolioLoadStatus": "integer", "resetEnabled": "boolean"},
    "L2": {"freshnessState": "freshnessState", "countsValid": "boolean", "stripVisible": "boolean"},
    "L3": {"dialogOpen": "boolean", "unavailableNoticeVisible": "boolean"},
    "L4": {"catalogStatus": "integer", "noIfNoneMatch": "boolean", "etagPresent": "boolean",
           "assetsNonEmpty": "boolean", "rowsRendered": "boolean", "catalogParity": "boolean",
           "activeCount": "integer"},
    "L5": {"presenceRequestsSinceOpen": "integer", "presenceStatus": "integer",
           "anotherSessionActive": "boolean|null", "requestFailed": "boolean",
           "corsConsoleError": "boolean"},
    "L6": {"priceRequestsAfterUncheck": "integer", "allStatus200": "boolean", "arrayShaped": "boolean",
           "nonNullPriceSeen": "boolean", "disjointFromPageBatches": "boolean",
           "predictedBatchCount": "integer"},
    "L7": {"pageWriteRequestsBeforeMutation": "integer"},
    "L8": {"putCount": "integer", "putStatus": "integer", "expectedVersionMatchesObserved": "boolean",
           "bodyMatchesExpectedDraft": "boolean", "versionAdvanced": "boolean",
           "savedStatusVisible": "boolean", "independentReadVersionMatches": "boolean",
           "independentReadHoldingsMatch": "boolean", "mutationSkippedReason": "mutationSkippedReason"},
    "L9": {"putCount": "integer", "putStatus": "integer", "noInternalKeyHeader": "boolean",
           "expectedVersionMatchesSaved": "boolean", "versionPlusOne": "boolean",
           "responseEqualsGolden": "boolean", "resetStatusVisible": "boolean",
           "independentReadEqualsGolden": "boolean", "mutationSkippedReason": "mutationSkippedReason"},
}


class TestLedgerContract:
    def test_repo_contract_vocabulary_is_pinned(self) -> None:
        assert CONTRACT.common_fields == ("seq", "tUtc", "src", "event")
        assert CONTRACT.sources == ("spec", "orchestrator")
        assert CONTRACT.spec_events == ("leg", "armed")
        assert CONTRACT.orchestrator_events == ("child_started", "child_done", "cleanup")
        assert CONTRACT.legs == tuple(f"L{i}" for i in range(10))
        assert CONTRACT.statuses == ("passed", "failed", "skipped")
        assert CONTRACT.reasons == (
            "OK", "ASSERTION_FAILED", "TIMEOUT", "HTTP_STATUS_NOT_200", "RATE_LIMITED", "CONFLICT",
            "REQUEST_FAILED", "SKIPPED_PRIOR_FAILURE", "SKIPPED_VISITOR_PRESENT",
            "SKIPPED_BASELINE_NOT_GOLDEN", "EXCEPTION",
        )
        assert CONTRACT.methods == ("GET", "PUT")
        assert CONTRACT.paths == (
            "/api/portfolio", "/api/portfolio/summary", "/api/assets", "/api/presence/demo",
            "/api/market/prices", "/api/portfolio/holdings", "/api/portfolio/demo-reset",
        )
        assert CONTRACT.mutation_skipped_reasons == ("NONE", "LEG_FAILED", "VISITOR_PRESENT", "BASELINE_NOT_GOLDEN")
        assert CONTRACT.freshness_states == ("FRESH", "STALE", "UNKNOWN", "MISSING", "ABSENT")

    def test_repo_contract_fact_vocabulary_is_pinned(self) -> None:
        assert {leg: dict(facts) for leg, facts in CONTRACT.leg_facts.items()} == EXPECTED_FACTS

    @staticmethod
    def _contract_doc() -> dict[str, Any]:
        return json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))

    @pytest.mark.parametrize(
        "mutate",
        [
            lambda d: d.update(schema="wave10-5b-ledger-contract-v1"),
            lambda d: d.update(legs=["L0", "L1"]),
            lambda d: d.update(statuses=["passed", "failed"]),
            lambda d: d.update(reasons=["ASSERTION_FAILED"]),
            lambda d: d.update(paths=[]),
            lambda d: d.update(commonFields=["seq", "tUtc", "src"]),
            lambda d: d["legFacts"]["L4"].update(activeCount="float"),
            lambda d: d["legFacts"].pop("L9"),
            lambda d: d.pop("methods"),
            lambda d: d.update(sources=["spec", "browser"]),
        ],
    )
    def test_contract_shape_violations_are_precondition_errors(self, mutate: Callable[[dict], Any]) -> None:
        doc = self._contract_doc()
        mutate(doc)
        with pytest.raises(subject.PreconditionError) as info:
            subject.parse_ledger_contract(json.dumps(doc).encode())
        assert info.value.code == "LEDGER_CONTRACT_INVALID"

    def test_the_contract_has_exactly_the_seventeen_pinned_top_level_keys(self) -> None:
        doc = self._contract_doc()
        assert len(subject._CONTRACT_TOP_LEVEL_KEYS) == 17 and set(doc) == set(subject._CONTRACT_TOP_LEVEL_KEYS)
        assert {"passHttpRules", "passHttp"} <= set(doc) and doc["schema"] == subject.LEDGER_CONTRACT_SCHEMA

    def test_the_module_pins_for_the_closed_lists_equal_the_contract_file(self) -> None:
        doc = self._contract_doc()
        assert set(subject._CONTRACT_EXACT) == {
            "commonFields", "sources", "specEvents", "orchestratorEvents", "legs", "statuses", "reasons",
            "methods", "paths", "mutationSkippedReasons", "freshnessStates"}
        for key, expected in subject._CONTRACT_EXACT.items():
            assert tuple(doc[key]) == expected, key

    @pytest.mark.parametrize("key", ["statuses", "reasons", "methods", "paths", "mutationSkippedReasons",
                                     "freshnessStates"])
    @pytest.mark.parametrize("change", ["gain", "lose", "reorder"])
    def test_the_six_closed_lists_are_pinned_exactly_not_just_by_required_members(
            self, key: str, change: str) -> None:
        """Python and TypeScript must accept and refuse the same contracts: a list that gains a
        member (a query-string path, a new reason), loses one or is reordered is refused."""
        doc = self._contract_doc()
        if change == "gain":
            doc[key] = doc[key] + ["/api/x?y=1" if key == "paths" else "EXTRA_MEMBER"]
        elif change == "lose":
            doc[key] = doc[key][:-1]
        else:
            doc[key] = list(reversed(doc[key]))
        with pytest.raises(subject.PreconditionError) as info:
            subject.parse_ledger_contract(json.dumps(doc).encode())
        assert info.value.code == "LEDGER_CONTRACT_INVALID"

    def test_non_json_contract_is_a_precondition_error(self) -> None:
        with pytest.raises(subject.PreconditionError):
            subject.parse_ledger_contract(b"{not json")

    def test_missing_contract_file_is_a_precondition_error(self, tmp_path: Path) -> None:
        with pytest.raises(subject.PreconditionError) as info:
            subject.load_ledger_contract(subject.FileOps(), tmp_path / "absent.json")
        assert info.value.code == "LEDGER_CONTRACT_MISSING"


# ---------------------------------------------------------------------------
# Ledger parsing (fixtures are hand-written from spec Appendix A)
# ---------------------------------------------------------------------------
def parse(*items: Any) -> subject.LedgerParse:
    data = b""
    for item in items:
        text = item if isinstance(item, str) else json.dumps(item, separators=(",", ":"))
        data += text.encode() + b"\n"
    return subject.parse_ledger(data, CONTRACT)


def one_leg(leg: str = "L4", **changes: Any) -> dict[str, Any]:
    writer = SpecWriter()
    writer.leg(leg)
    line = writer.lines[0]
    line.update(changes)
    return line


def orch(seq: int, event: str, **fields: Any) -> dict[str, Any]:
    return {"seq": seq, "tUtc": "2026-09-20T12:00:00.000Z", "src": "orchestrator", "event": event, **fields}


class TestLedgerParse:
    def test_appendix_a_example_lines_parse_clean(self) -> None:
        leg = {"seq": 1, "tUtc": "2026-01-01T00:00:00.000Z", "src": "spec", "event": "leg", "leg": "L4",
               "status": "passed", "reason": "OK",
               "http": [{"method": "GET", "path": "/api/assets", "status": 200}],
               "facts": dict(LEG_FACTS["L4"])}
        armed = {"seq": 2, "tUtc": "2026-01-01T00:00:01.000Z", "src": "spec", "event": "armed"}
        parsed = parse(leg, armed)
        assert parsed.clean and parsed.armed_line_no == 2
        assert parsed.legs["L4"].facts == LEG_FACTS["L4"]
        assert parsed.legs["L4"].http == [{"method": "GET", "path": "/api/assets", "status": 200}]
        legs = subject.derive_legs(parsed, CONTRACT, child_expected=True, fallback_reason="X")
        assert legs["L4"].status == "passed"  # a full-facts pass is believed

    def test_the_go_ledger_covers_every_leg_cleanly(self) -> None:
        parsed = parse(*go_ledger())
        assert parsed.clean and set(parsed.legs) == set(CONTRACT.legs)
        assert all(rec.status == "passed" for rec in parsed.legs.values())

    def test_armed_precedes_the_l8_record_and_follows_the_l7_record(self) -> None:
        parsed = parse(*go_ledger())
        assert parsed.armed_line_no is not None
        assert parsed.legs["L7"].line_no < parsed.armed_line_no < parsed.legs["L8"].line_no
        assert subject.spec_armed_before_l8(parsed) is True

    @pytest.mark.parametrize("first,second", [("failed", "passed"), ("passed", "failed"), ("passed", "passed"),
                                              ("failed", "failed")])
    def test_a_second_final_event_for_the_same_leg_is_invalid_and_poisons_the_leg(
            self, first: str, second: str) -> None:
        """Appendix A used to say the last event wins, so a failed L8 followed by a passed L8 could
        be a GO. The honest writer can never emit two, so a second one is a non-conforming writer."""
        def variant(status: str) -> dict[str, Any]:
            return one_leg("L4") if status == "passed" else one_leg("L4", status="failed", reason="ASSERTION_FAILED")

        parsed = parse(variant(first), {**variant(second), "seq": 2})
        assert not parsed.clean and parsed.invalid_lines == [(2, "DUPLICATE_LEG")]
        assert parsed.legs["L4"].poisoned
        legs = subject.derive_legs(parsed, CONTRACT, child_expected=True, fallback_reason="X")
        assert (legs["L4"].status, legs["L4"].reason) == ("failed", "LEDGER_INVALID")

    def test_a_valid_event_after_an_invalid_one_for_the_same_leg_is_still_poisoned(self) -> None:
        broken = one_leg("L4")
        broken["facts"]["surprise"] = True
        parsed = parse(broken, {**one_leg("L4"), "seq": 2})
        assert not parsed.clean and parsed.legs["L4"].poisoned

    def test_the_failed_then_passed_l8_ledger_is_non_go_end_to_end(self) -> None:
        """Probe A7: L8 failed, L9 passed, then L8 passed again. Last-wins made that a GO."""
        writer = SpecWriter()
        for i in range(8):
            writer.leg(f"L{i}")
        writer.armed()
        writer.leg("L8", "failed", "HTTP_STATUS_NOT_200", facts={}, http=[])
        writer.leg("L9")
        writer.leg("L8")
        parsed, legs, verdict = evaluate(writer.lines)
        assert parsed.invalid_lines == [(12, "DUPLICATE_LEG")]
        assert (legs["L8"].status, legs["L8"].reason) == ("failed", "LEDGER_INVALID")
        assert (verdict.verdict, verdict.run_result) == ("NON_GO", "not_all_items_passed")
        assert "LEDGER_INVALID" in verdict.failed_criteria

    @staticmethod
    def _dup(text: str, old: str, new: str) -> str:
        assert old in text
        return text.replace(old, new, 1)

    def test_a_repeated_key_inside_one_line_is_invalid_and_poisons_the_leg(self) -> None:
        """Probe A10: json.loads keeps the LAST value of a repeated key, so a line carrying
        "status":"failed","reason":"TIMEOUT" up front and the real pair at the end read as passed."""
        text = json.dumps(one_leg("L4"), separators=(",", ":"))
        dup = self._dup(text, '{"seq"', '{"status":"failed","reason":"TIMEOUT","seq"')
        loaded = json.loads(dup)
        assert (loaded["status"], loaded["reason"]) == ("passed", "OK")  # the naive reading believes it
        parsed = parse(dup)
        assert not parsed.clean and parsed.invalid_lines == [(1, "DUPLICATE_KEY")]
        assert parsed.legs["L4"].poisoned

    @pytest.mark.parametrize(
        "old,new",
        [
            ('"facts":{', '"facts":{"catalogStatus":503,'),  # inside facts
            ('"http":[{', '"http":[{"status":503,'),  # inside an http entry
            ('"event":"leg"', '"event":"leg","event":"leg"'),  # a common field
            ('"leg":"L4"', '"leg":"L5","leg":"L4"'),  # the leg id itself
        ],
        ids=["facts", "http-entry", "common-field", "leg-id"],
    )
    def test_a_repeated_key_anywhere_in_a_leg_line_is_invalid(self, old: str, new: str) -> None:
        parsed = parse(self._dup(json.dumps(one_leg("L4"), separators=(",", ":")), old, new))
        assert not parsed.clean and parsed.invalid_lines == [(1, "DUPLICATE_KEY")]

    @pytest.mark.parametrize(
        "line",
        [
            '{"seq":1,"tUtc":"2026-09-20T12:00:00.000Z","src":"spec","event":"armed","event":"armed"}',
            '{"seq":1,"tUtc":"2026-09-20T12:00:00.000Z","src":"orchestrator","event":"child_done","exit":0,"exit":1}',
            '{"seq":1,"seq":1,"tUtc":"2026-09-20T12:00:00.000Z","src":"orchestrator","event":"child_started"}',
        ],
    )
    def test_a_repeated_key_is_invalid_on_every_event_kind(self, line: str) -> None:
        parsed = parse(line)
        assert not parsed.clean and parsed.invalid_lines == [(1, "DUPLICATE_KEY")]

    def test_a_ledger_with_a_repeated_key_in_l8_is_non_go_end_to_end(self) -> None:
        lines: list[Any] = go_ledger()
        text = json.dumps(lines[9], separators=(",", ":"))  # L8
        assert lines[9]["leg"] == "L8"
        lines[9] = self._dup(text, '{"seq"', '{"status":"failed","reason":"TIMEOUT","seq"')
        parsed, legs, verdict = evaluate(lines)
        # the unparseable line does not consume its seq, so the next spec line cascades (as any invalid line does)
        assert parsed.invalid_lines[0] == (10, "DUPLICATE_KEY")
        assert (legs["L8"].status, legs["L8"].reason) == ("failed", "LEDGER_INVALID")
        assert verdict.verdict == "NON_GO" and "LEDGER_INVALID" in verdict.failed_criteria

    def test_a_spec_line_after_the_orchestrators_child_done_is_invalid(self) -> None:
        """Probe A11: the file is read only once the child is confirmed dead, so a spec line that
        lands after child_done comes from a still-live or foreign writer."""
        writer = SpecWriter()
        for i in range(8):
            writer.leg(f"L{i}")
        writer.armed()
        writer.leg("L8")
        lines: list[Any] = [orch(1, "child_started"), *writer.lines, orch(2, "child_done", exit=0)]
        writer.leg("L9")  # appended AFTER child_done
        lines.append(writer.lines[-1])
        parsed, legs, verdict = evaluate(lines)
        assert parsed.child_done_line_no == 12
        assert parsed.invalid_lines == [(13, "SPEC_AFTER_CHILD_DONE")]
        assert (legs["L9"].status, legs["L9"].reason) == ("failed", "LEDGER_INVALID")
        assert verdict.verdict == "NON_GO" and "LEDGER_INVALID" in verdict.failed_criteria

    def test_every_spec_event_kind_after_child_done_is_invalid_but_orchestrator_lines_are_not(self) -> None:
        base_lines = [orch(1, "child_started"), orch(2, "child_done", exit=0)]
        late_armed = {"seq": 1, "tUtc": "2026-09-20T12:00:00.000Z", "src": "spec", "event": "armed"}
        parsed = parse(*base_lines, late_armed)
        assert parsed.invalid_lines == [(3, "SPEC_AFTER_CHILD_DONE")] and parsed.armed_line_no is None
        late_leg = one_leg("L4")
        assert parse(*base_lines, late_leg).invalid_lines == [(3, "SPEC_AFTER_CHILD_DONE")]
        cleanup = orch(3, "cleanup", attempt=0, transport="saved_jwt", result="observed_golden")
        assert parse(*base_lines, cleanup).clean  # the orchestrator's own later lines are normal

    def test_a_spec_line_before_child_done_is_unaffected(self) -> None:
        parsed = parse(orch(1, "child_started"), one_leg("L4"), orch(2, "child_done", exit=0))
        assert parsed.clean and parsed.child_done_line_no == 3

    @pytest.mark.parametrize("armed_after_legs,expected", [(0, False), (7, False), (8, True), (9, False), (10, False)],
                             ids=["first-line", "before-L7", "between-L7-and-L8", "after-L8", "last-line"])
    def test_armed_must_sit_between_the_l7_and_l8_records(self, armed_after_legs: int, expected: bool) -> None:
        """Probe A12: an `armed` anywhere before L8 used to count, even as the very first line."""
        parsed = parse(*go_ledger_armed_after(armed_after_legs))
        assert parsed.clean and subject.spec_armed_before_l8(parsed) is expected

    def test_spec_armed_before_l8_needs_an_armed_line_and_both_legs(self) -> None:
        def ledger_without(*skipped: str, armed: bool = True) -> subject.LedgerParse:
            writer = SpecWriter()  # consecutive seq, so only the named leg or the armed line is missing
            for i in range(10):
                if i == 8 and armed:
                    writer.armed()
                if f"L{i}" not in skipped:
                    writer.leg(f"L{i}")
            parsed = parse(*writer.lines)
            assert parsed.clean
            return parsed

        assert subject.spec_armed_before_l8(None) is False
        assert subject.spec_armed_before_l8(ledger_without()) is True  # the control: the honest ledger
        assert subject.spec_armed_before_l8(ledger_without(armed=False)) is False
        no_l7 = ledger_without("L7")
        assert no_l7.armed_line_no is not None and subject.spec_armed_before_l8(no_l7) is False
        no_l8 = ledger_without("L8")
        assert no_l8.armed_line_no is not None and subject.spec_armed_before_l8(no_l8) is False

    @pytest.mark.parametrize("armed_after_legs", [0, 7, 9, 10])
    def test_a_misplaced_armed_line_is_non_go_end_to_end(self, h: Harness, armed_after_legs: int) -> None:
        h.browser.lines = go_ledger_armed_after(armed_after_legs)
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "ARMED_MISSING")
        assert {leg["status"] for leg in art["legs"]} == {"passed"}  # only the armed position was wrong

    def test_armed_between_l7_and_l8_is_the_go_position_end_to_end(self, h: Harness) -> None:
        h.browser.lines = go_ledger_armed_after(8)
        assert h.run() == 0
        assert h.artifact()["verdict"] == "GO"

    def test_final_line_without_trailing_newline_is_still_read(self) -> None:
        raw = json.dumps(one_leg("L4"), separators=(",", ":")).encode()
        assert subject.parse_ledger(raw, CONTRACT).legs["L4"].status == "passed"

    def test_crlf_line_endings_are_tolerated(self) -> None:
        raw = json.dumps(one_leg("L4"), separators=(",", ":")).encode() + b"\r\n"
        assert subject.parse_ledger(raw, CONTRACT).clean

    @pytest.mark.parametrize(
        "mutate",
        [
            lambda l: l.__setitem__("body", "x"),
            lambda l: l.update(status="error"),
            lambda l: l.update(reason="BOOM"),
            lambda l: l.update(status="failed"),  # reason OK with status failed
            lambda l: l.update(status="skipped"),  # reason OK with status skipped
            lambda l: l["http"][0].update(method="POST"),
            lambda l: l["http"][0].update(path="/api/assets?tickers=AAPL"),
            lambda l: l["http"][0].update(path="/api/other"),
            lambda l: l["http"][0].update(headers={}),
            lambda l: l["http"][0].update(status=-1),
            lambda l: l["http"][0].update(status=True),
            lambda l: l["http"][0].update(status=1),  # 1..99 is not an HTTP status (0 means the request failed)
            lambda l: l["http"][0].update(status=50),
            lambda l: l["http"][0].update(status=99),
            lambda l: l["http"][0].update(status=600),
            lambda l: l["http"][0].update(status=200.0),
            lambda l: l["facts"].update(activeCount=-1),  # integer facts are 0..10^9
            lambda l: l["facts"].update(catalogStatus=-200),
            lambda l: l["facts"].update(activeCount=10**9 + 1),
            lambda l: l.update(http={}),
            lambda l: l.update(facts=[]),
            lambda l: l["facts"].update(extra=True),
            lambda l: l["facts"].update(etagPresent=1),
            lambda l: l["facts"].update(catalogStatus=True),
            lambda l: l["facts"].update(activeCount="3"),
            lambda l: l["facts"].update(putCount=1),  # an L8 key on L4
            lambda l: l.update(tUtc="2026-09-20T12:05:00Z"),  # no milliseconds
            lambda l: l.pop("http"),  # missing structure fails closed, as in the TypeScript validator
            lambda l: l.pop("facts"),
        ],
    )
    def test_out_of_vocabulary_leg_line_poisons_that_leg(self, mutate: Callable[[dict], Any]) -> None:
        line = one_leg("L4")
        mutate(line)
        parsed = parse(line)
        assert not parsed.clean
        assert parsed.legs["L4"].poisoned

    @pytest.mark.parametrize("status", [0, 100, 200, 429, 599])
    def test_the_http_status_bounds_are_zero_or_100_to_599(self, status: int) -> None:
        line = one_leg("L4")
        line["http"][0]["status"] = status
        assert parse(line).clean

    @pytest.mark.parametrize("value", [0, 1, 10**9])
    def test_the_integer_fact_bounds_are_zero_to_a_billion(self, value: int) -> None:
        line = one_leg("L4")
        line["facts"]["activeCount"] = value
        assert parse(line).clean

    def test_unknown_leg_id_is_an_invalid_unattributed_line(self) -> None:
        line = one_leg("L4")
        line["leg"] = "L10"
        parsed = parse(line)
        assert not parsed.clean and not parsed.legs

    def test_freshness_state_outside_the_enum_is_invalid(self) -> None:
        line = one_leg("L2")
        line["facts"]["freshnessState"] = "STALEISH"
        assert not parse(line).clean

    def test_mutation_skipped_reason_outside_the_enum_is_invalid(self) -> None:
        line = one_leg("L8", status="skipped", reason="SKIPPED_VISITOR_PRESENT", facts={"mutationSkippedReason": "MAYBE"})
        assert not parse(line).clean

    def test_skipped_reason_and_skipped_fact_must_agree(self) -> None:
        line = one_leg("L8", status="skipped", reason="SKIPPED_VISITOR_PRESENT",
                       facts={"mutationSkippedReason": "LEG_FAILED"})
        assert not parse(line).clean

    def test_a_passed_mutation_leg_must_carry_none_as_skip_reason(self) -> None:
        line = one_leg("L8")
        line["facts"]["mutationSkippedReason"] = "VISITOR_PRESENT"
        assert not parse(line).clean

    def test_anotherSessionActive_may_be_null(self) -> None:
        line = one_leg("L5")
        line["facts"]["anotherSessionActive"] = None
        assert parse(line).clean

    @pytest.mark.parametrize(
        "line",
        [
            "not json",
            "",
            "[1,2]",
            '{"seq":1,"tUtc":"2026-09-20T12:00:00.000Z","src":"spec"}',
            '{"seq":true,"tUtc":"2026-09-20T12:00:00.000Z","src":"spec","event":"armed"}',
            '{"seq":1,"tUtc":"2026-09-20T12:00:00.000Z","src":"browser","event":"armed"}',
            '{"seq":1,"tUtc":"2026-09-20T12:00:00.000Z","src":"spec","event":"child_started"}',
            '{"seq":1,"tUtc":"2026-09-20T12:00:00.000Z","src":"spec","event":"armed","note":"x"}',
        ],
    )
    def test_malformed_or_out_of_contract_lines_are_invalid(self, line: str) -> None:
        assert not parse(line).clean

    def test_a_leg_event_from_the_orchestrator_is_invalid(self) -> None:
        line = one_leg("L4")
        line["src"] = "orchestrator"
        assert not parse(line).clean

    def test_partial_trailing_line_is_invalid(self) -> None:
        good = json.dumps(one_leg("L4"), separators=(",", ":")).encode()
        parsed = subject.parse_ledger(good + b"\n" + b'{"seq":2,"tUtc"', CONTRACT)
        assert not parsed.clean and parsed.legs["L4"].status == "passed"

    def test_non_utf8_bytes_are_invalid(self) -> None:
        assert not subject.parse_ledger(b"\xff\xfe\x00", CONTRACT).clean

    def test_seq_is_per_writer_and_must_be_consecutive(self) -> None:
        clean = parse(orch(1, "child_started"), one_leg("L4"), orch(2, "child_done", exit=0))
        assert clean.clean
        gap = parse(one_leg("L4"), {**one_leg("L5"), "seq": 3})
        assert not gap.clean
        repeat = parse(one_leg("L4"), one_leg("L5"))
        assert not repeat.clean

    def test_orchestrator_events_are_validated_too(self) -> None:
        good = parse(
            orch(1, "child_started"), orch(2, "child_done", exit=1),
            orch(3, "cleanup", attempt=0, transport="none", result="cleanup_armed"),
        )
        assert good.clean
        for bad in (
            orch(1, "child_done"),
            orch(1, "child_done", exit="0"),
            orch(1, "cleanup", attempt=0, transport="none", result="dunno"),
            orch(1, "cleanup", attempt=0, transport="none", result="armed"),  # the old, ambiguous name
            orch(1, "cleanup", attempt=0, transport="carrier_pigeon", result="armed"),
            orch(1, "cleanup", attempt=0, transport="none", result="armed", note="x"),
            orch(1, "child_started", extra=1),
        ):
            assert not parse(bad).clean, bad

    def test_ledger_written_by_the_orchestrator_ledger_class_parses_clean(self, tmp_path: Path) -> None:
        path = tmp_path / "ledger.jsonl"
        ledger = subject.Ledger(subject.FileOps(), path, Clock().now_utc)
        ledger.cleanup(0, "none", "cleanup_armed")
        ledger.event("child_started")
        ledger.event("child_done", exit=0)
        for result in subject.CLEANUP_STEP_RESULTS:
            ledger.cleanup(1, "saved_jwt", result)
        assert subject.parse_ledger(path.read_bytes(), CONTRACT).clean

    def test_ledger_class_rejects_vocabulary_violations_and_reports_write_failures(self, tmp_path: Path) -> None:
        ledger = subject.Ledger(subject.FileOps(), tmp_path / "ledger.jsonl", Clock().now_utc)
        with pytest.raises(ValueError):
            ledger.cleanup(0, "none", "made-up")
        missing_dir = subject.Ledger(subject.FileOps(), tmp_path / "no" / "ledger.jsonl", Clock().now_utc)
        missing_dir.event("child_done", exit=0)
        assert missing_dir.write_failures == 1
        with pytest.raises(OSError):
            missing_dir.event("child_started", critical=True)


class TestDeriveLegs:
    def test_missing_leg_is_missing_when_the_child_was_expected(self) -> None:
        legs = subject.derive_legs(parse(one_leg("L4")), CONTRACT, child_expected=True, fallback_reason="SKIPPED_PRIOR_FAILURE")
        assert legs["L4"].status == "passed"
        assert legs["L0"].status == "missing" and legs["L0"].reason == "LEG_MISSING"

    def test_legs_are_skipped_with_the_fallback_reason_when_no_child_ran(self) -> None:
        legs = subject.derive_legs(None, CONTRACT, child_expected=False, fallback_reason="SKIPPED_BASELINE_NOT_GOLDEN")
        assert {r.status for r in legs.values()} == {"skipped"}
        assert {r.reason for r in legs.values()} == {"SKIPPED_BASELINE_NOT_GOLDEN"}

    def test_poisoned_leg_is_failed_with_ledger_invalid(self) -> None:
        bad = one_leg("L4")
        bad["facts"]["nope"] = True
        legs = subject.derive_legs(parse(bad), CONTRACT, child_expected=True, fallback_reason="X")
        assert legs["L4"].status == "failed" and legs["L4"].reason == "LEDGER_INVALID"

    @pytest.mark.parametrize("status", [409, 429, 503, 504])
    def test_passed_leg_with_a_forbidden_status_is_downgraded(self, status: int) -> None:
        line = one_leg("L4", http=[{"method": "GET", "path": "/api/assets", "status": status}])
        legs = subject.derive_legs(parse(line), CONTRACT, child_expected=True, fallback_reason="X")
        assert legs["L4"].status == "failed" and legs["L4"].reason == "FORBIDDEN_HTTP_STATUS"

    @pytest.mark.parametrize("fact,leg", [("catalogStatus", "L4"), ("presenceStatus", "L5"), ("portfolioLoadStatus", "L1"), ("putStatus", "L8")])
    def test_a_forbidden_status_fact_on_a_passed_leg_is_downgraded_too(self, fact: str, leg: str) -> None:
        line = one_leg(leg)
        line["facts"][fact] = 503
        legs = subject.derive_legs(parse(line), CONTRACT, child_expected=True, fallback_reason="X")
        assert legs[leg].status == "failed" and legs[leg].reason == "FORBIDDEN_HTTP_STATUS"

    def test_other_status_facts_and_non_status_integers_are_left_alone(self) -> None:
        line = one_leg("L4")
        line["facts"]["catalogStatus"] = 200
        line["facts"]["activeCount"] = 429  # a count, not a status
        legs = subject.derive_legs(parse(line), CONTRACT, child_expected=True, fallback_reason="X")
        assert legs["L4"].status == "passed"

    def test_route_mapping(self) -> None:
        legs = subject.derive_legs(parse(*go_ledger()), CONTRACT, child_expected=True, fallback_reason="X")
        assert {leg: r.route for leg, r in legs.items()} == subject.LEG_ROUTES
        assert legs["L4"].route == "9.1" and legs["L8"].route == "9.2" and legs["L9"].route == "reset"


# ---------------------------------------------------------------------------
# Sanitizer: pattern scan with positive controls, stub on a trip, exclusive writer
# ---------------------------------------------------------------------------
POSITIVE_CONTROLS = [
    ("jwt", "value " + TOKEN),
    ("bearer", "Bearer abcdef123456"),
    ("authorization", "Authorization: something"),
    ("password", "the password is here"),
    ("cookie", "Set-Cookie: a=b"),
    ("internal_key", "X-Internal-Api-Key: k"),
    ("ip_address", "reached 10.0.0.1 today"),
    ("email", "write to someone@example.org"),
    ("host", "see https://evil.example.net/x"),
    ("host", "see evil.example.net for more"),
    ("guid", "subscription 11111111-2222-3333-4444-555555555555"),
    ("basic", "sent Basic dXNlcjpwYXNzd29yZA== to the service"),
    ("jwt", "value " + TOKEN.replace(".", "%2E")),  # percent-encoded dots: only the normalized copy sees it
    ("jwt", TOKEN[:15] + "\n" + TOKEN[15:]),  # split by a newline
    ("jwt", "v " + json.dumps({"k": TOKEN[:15] + "\n" + TOKEN[15:]})),  # split, JSON-escaped
]


class TestSanitizerScan:
    @pytest.mark.parametrize("expected,text", POSITIVE_CONTROLS)
    def test_positive_control_trips_its_class(self, expected: str, text: str) -> None:
        assert expected in subject.scan_text(text)

    def test_exact_registered_secret_trips(self) -> None:
        assert subject.scan_text(f"note {PASSWORD}", secrets=[PASSWORD]) == ["secret_exact"]

    def test_json_escaped_secret_form_trips_when_the_raw_form_is_absent(self) -> None:
        secret = 'pa"ss\\word-1234'
        text = json.dumps({"k": secret})
        assert secret not in text
        assert "secret_exact" in subject.scan_text(text, secrets=[secret])

    def test_percent_encoded_secret_form_trips(self) -> None:
        secret = "pa ss/word-1234"
        assert "secret_exact" in subject.scan_text("q=pa%20ss%2Fword-1234", secrets=[secret])

    def test_secrets_shorter_than_the_minimum_are_not_scanned(self) -> None:
        assert subject.scan_text("abc abc abc", secrets=["abc"]) == []

    def test_clean_artifact_does_not_trip(self, h: Harness) -> None:
        assert h.run() == 0
        assert subject.scan_text(h.evidence_path.read_text(encoding="utf-8"), secrets=[PASSWORD, TOKEN]) == []

    def test_public_constants_and_allowlisted_hosts_do_not_trip(self) -> None:
        text = f"{subject.DEMO_EMAIL} {subject.DEMO_USER_ID} {FRONTEND} {API}/api/portfolio"
        assert subject.scan_text(text) == []

    def test_allowlisted_hosts_follow_the_module_seam(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setattr(subject, "ALLOWED_ORIGINS", ("https://front.test", "https://api.test"))
        assert subject.scan_text("https://api.test/x") == []
        assert "host" in subject.scan_text(API)

    def test_scan_returns_only_class_names_never_offending_text(self) -> None:
        classes = subject.scan_text(f"{TOKEN} someone@example.org Bearer xyz12345", secrets=[PASSWORD])
        assert set(classes) <= set(subject.SCAN_CLASS_ORDER)
        joined = " ".join(classes)
        assert TOKEN not in joined and "someone" not in joined

    def test_owner_secrets_are_registered_as_fingerprints_only(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        registry.add(TOKEN)
        assert PASSWORD not in registry.values() and TOKEN in registry.values()
        assert PASSWORD not in repr(vars(registry)) and PASSWORD not in repr(registry.owner_fingerprints())

    def test_fingerprints_detect_the_raw_json_escaped_and_percent_encoded_forms(self) -> None:
        secret = 'pa"ss\\word 1234'
        registry = subject.SecretRegistry()
        registry.add_owner_secret(secret)
        fingerprints = registry.owner_fingerprints()
        for text in (f"x {secret} y", json.dumps({"k": secret}), "q=pa%22ss%5Cword%201234", "q=pa%22ss%5Cword+1234"):
            assert "secret_exact" in subject.scan_text(text, owner_fingerprints=fingerprints), text
        assert subject.scan_text("nothing here at all", owner_fingerprints=fingerprints) == []
        assert registry.contains_owner_secret(f"..{secret}..") and not registry.contains_owner_secret("clean text")

    def test_fingerprints_ignore_secrets_shorter_than_the_minimum(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret("abc")
        registry.add_owner_secret("")
        registry.add_owner_secret(None)
        assert registry.owner_fingerprints() == frozenset()

    def test_a_partial_overlap_is_not_an_exact_match(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        assert not registry.contains_owner_secret(PASSWORD[:-1] + "!")
        assert not registry.contains_owner_secret(PASSWORD[1:])

    def test_every_scan_class_has_a_positive_control(self) -> None:
        covered = {name for name, _ in POSITIVE_CONTROLS} | {"secret_exact"}
        assert covered == set(subject.SCAN_CLASS_ORDER)


class TestArtifactWriter:
    def _doc(self, **extra: Any) -> dict[str, Any]:
        return {"schema": subject.EVIDENCE_SCHEMA, "verdict": "GO", "start_utc": "2026-09-20T12:00:00Z",
                "end_utc": "2026-09-20T12:30:00Z",
                "cleanup": {"result": "confirmed", "golden_confirmed": True}, **extra}

    def _write(self, tmp_path: Path, doc: dict[str, Any], **kw: Any) -> subject.WrittenArtifact:
        kwargs: dict[str, Any] = dict(kind="evidence", secrets=[PASSWORD, TOKEN], fs=subject.FileOps(),
                                      path=tmp_path / "out.json", fallback_path=None)
        kwargs.update(kw)
        return subject.finalize_artifact(doc, **kwargs)

    def test_bytes_are_utf8_without_bom_with_lf_newlines_only(self, tmp_path: Path) -> None:
        written = self._write(tmp_path, self._doc())
        raw = (tmp_path / "out.json").read_bytes()
        assert raw == written.data
        assert not raw.startswith(b"\xef\xbb\xbf") and b"\r" not in raw and raw.endswith(b"}\n")
        assert json.loads(raw)["verdict"] == "GO"

    def test_size_and_sha256_describe_the_written_bytes(self, tmp_path: Path) -> None:
        import hashlib
        written = self._write(tmp_path, self._doc())
        raw = (tmp_path / "out.json").read_bytes()
        assert written.size == len(raw) and written.sha256 == hashlib.sha256(raw).hexdigest()

    def test_exclusive_create_never_overwrites(self, tmp_path: Path) -> None:
        (tmp_path / "out.json").write_text("precious", encoding="utf-8")
        with pytest.raises(OSError):
            self._write(tmp_path, self._doc())
        assert (tmp_path / "out.json").read_text(encoding="utf-8") == "precious"

    def test_collision_falls_back_to_the_work_directory_copy(self, tmp_path: Path) -> None:
        (tmp_path / "out.json").write_text("precious", encoding="utf-8")
        written = self._write(tmp_path, self._doc(), fallback_path=tmp_path / "fallback.json")
        assert written.fallback_used and (tmp_path / "fallback.json").exists()
        assert (tmp_path / "out.json").read_text(encoding="utf-8") == "precious"

    def test_a_trip_writes_a_scrubbed_non_go_stub_without_the_offending_text(self, tmp_path: Path) -> None:
        leaked = self._doc(notes=f"raw {TOKEN} and {PASSWORD} for someone@example.org")
        written = self._write(tmp_path, leaked)
        text = (tmp_path / "out.json").read_text(encoding="utf-8")
        doc = json.loads(text)
        assert doc["verdict"] == "NON_GO" and doc["stop_reason"]["code"] == "SANITIZER_TRIPPED"
        assert doc["gate_map"] == {"step_a_credited": False, "run_result": "not_all_items_passed"}
        for offending in (TOKEN, PASSWORD, "someone@example.org", "notes"):
            assert offending not in text
        assert {"jwt", "secret_exact", "email"} <= set(written.tripped)
        assert subject.scan_text(text, secrets=[PASSWORD, TOKEN]) == []

    def test_stub_keeps_only_enum_like_facts(self, tmp_path: Path) -> None:
        doc = self._doc(notes=TOKEN)
        doc["cleanup"] = {"result": "confirmed", "golden_confirmed": True, "detail_code": TOKEN}
        self._write(tmp_path, doc)
        stub = json.loads((tmp_path / "out.json").read_text(encoding="utf-8"))
        assert stub["cleanup"] == {"result": "confirmed", "golden_confirmed": True}
        assert stub["start_utc"] == "2026-09-20T12:00:00Z"

    def test_minimal_stub_is_used_when_the_stub_itself_would_trip(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setattr(subject, "scrubbed_stub", lambda *a, **k: {"leak": TOKEN})
        self._write(tmp_path, self._doc(notes=TOKEN))
        assert json.loads((tmp_path / "out.json").read_text(encoding="utf-8")) == subject._minimal_stub("evidence")

    @pytest.mark.parametrize("kind", ["evidence", "cleanup"])
    def test_constant_minimal_stub_is_clean_and_not_a_pass(self, kind: str) -> None:
        stub = subject._minimal_stub(kind)
        assert subject.scan_text(json.dumps(stub)) == []
        assert stub["gate_map"]["run_result"] == "not_all_items_passed"

    def test_cleanup_kind_stub_shape(self, tmp_path: Path) -> None:
        doc = {"schema": subject.CLEANUP_ARTIFACT_SCHEMA, "mode": "cleanup_only", "result": "CONFIRMED_GOLDEN",
               "leak": TOKEN}
        self._write(tmp_path, doc, kind="cleanup")
        stub = json.loads((tmp_path / "out.json").read_text(encoding="utf-8"))
        assert stub["schema"] == subject.CLEANUP_ARTIFACT_SCHEMA and stub["result"] == "UNCONFIRMED"


# ---------------------------------------------------------------------------
# Target allowlist, output paths and argument validation (exit 2, nothing consumed)
# ---------------------------------------------------------------------------
def cfg(tmp_path: Path, **over: Any) -> subject.RunConfig:
    base: dict[str, Any] = dict(
        evidence_output=tmp_path / "evidence.json", baseline_commit=BASELINE,
        deploy_completed_utc=dt(DEPLOY_COMPLETED), bound_seconds=7200, pre_deploy_build_id=OLD_BUILD_ID,
        backend_attestation_pre=tmp_path / "att.json", deploy_run_id="42", source_head_sha=SOURCE_SHA,
    )
    base.update(over)
    return subject.RunConfig(**base)


class TestTargetAllowlist:
    def test_default_targets_are_the_allowlisted_origins(self, tmp_path: Path) -> None:
        assert subject.resolve_targets(cfg(tmp_path)) == (FRONTEND, API)

    @pytest.mark.parametrize(
        "field,value",
        [
            ("frontend_origin", "https://evil.example.com"),
            ("frontend_origin", "http://vibhanshu-ai-portfolio.dev"),
            ("frontend_origin", "https://vibhanshu-ai-portfolio.dev:8443"),
            ("frontend_origin", "https://vibhanshu-ai-portfolio.dev/"),
            ("frontend_origin", "https://VIBHANSHU-AI-PORTFOLIO.DEV"),
            ("api_origin", "https://api.vibhanshu-ai-portfolio.dev:444"),
            ("api_origin", "https://www.vibhanshu-ai-portfolio.dev"),
            ("api_origin", "http://localhost:8080"),
            ("api_origin", ""),
        ],
    )
    def test_any_other_origin_scheme_or_port_is_rejected(self, tmp_path: Path, field: str, value: str) -> None:
        with pytest.raises(subject.PreconditionError) as info:
            subject.resolve_targets(cfg(tmp_path, **{field: value}))
        assert info.value.code == "ORIGIN_NOT_ALLOWED"

    def test_the_module_seam_replaces_the_allowlist_for_tests(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.setattr(subject, "ALLOWED_ORIGINS", ("https://front.test", "https://api.test"))
        assert subject.resolve_targets(cfg(tmp_path)) == ("https://front.test", "https://api.test")
        with pytest.raises(subject.PreconditionError):
            subject.resolve_targets(cfg(tmp_path, frontend_origin=FRONTEND))

    def test_real_http_refuses_urls_outside_the_allowlist_before_any_network(self) -> None:
        for url in ("https://evil.example.com/x", "http://api.vibhanshu-ai-portfolio.dev/x",
                    "https://api.vibhanshu-ai-portfolio.dev.evil.com/x", API):
            with pytest.raises(ValueError):
                subject.make_http("GET", url, {}, None, 1.0)
            with pytest.raises(ValueError):
                subject.make_fetch_root_html(url, 1.0)


class TestOutputPaths:
    def test_valid_new_path_passes(self, tmp_path: Path) -> None:
        subject.validate_evidence_paths(cfg(tmp_path), subject.FileOps())

    @pytest.mark.parametrize(
        "code,path_fn",
        [
            ("EVIDENCE_PATH_NOT_ABSOLUTE", lambda t: Path("relative") / "evidence.json"),
            ("EVIDENCE_PATH_INSIDE_REPO", lambda t: subject.REPO / "evidence.json"),
            ("EVIDENCE_PATH_INSIDE_REPO", lambda t: subject.REPO / "scripts" / "evidence.json"),
            ("EVIDENCE_PARENT_MISSING", lambda t: t / "no-such-dir" / "evidence.json"),
            ("EVIDENCE_PATH_INVALID", lambda t: t / ".." / "evidence.json"),
            ("EVIDENCE_PATH_INVALID", lambda t: t / "evi\x07dence.json"),
            ("EVIDENCE_PATH_UNC_OR_DEVICE", lambda t: Path(r"\\server\share\evidence.json")),
            ("EVIDENCE_PATH_UNC_OR_DEVICE", lambda t: Path("//server/share/evidence.json")),
            ("EVIDENCE_PATH_UNC_OR_DEVICE", lambda t: Path(r"\\?\C:\out\evidence.json")),
        ],
    )
    def test_path_rules(self, tmp_path: Path, code: str, path_fn: Callable[[Path], Path]) -> None:
        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_evidence_paths(cfg(tmp_path, evidence_output=path_fn(tmp_path)), subject.FileOps())
        assert info.value.code == code

    def test_existing_evidence_file_is_refused_and_untouched(self, tmp_path: Path) -> None:
        (tmp_path / "evidence.json").write_text("keep", encoding="utf-8")
        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_evidence_paths(cfg(tmp_path), subject.FileOps())
        assert info.value.code == "EVIDENCE_PATH_EXISTS"
        assert (tmp_path / "evidence.json").read_text(encoding="utf-8") == "keep"

    def test_existing_work_directory_is_refused_in_run_mode_only(self, tmp_path: Path) -> None:
        (tmp_path / "evidence.json.work").mkdir()
        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_evidence_paths(cfg(tmp_path), subject.FileOps())
        assert info.value.code == "WORK_DIR_EXISTS"
        subject.validate_evidence_paths(cfg(tmp_path, cleanup_only=True), subject.FileOps())

    def test_the_work_directory_is_the_sibling_dot_work(self, tmp_path: Path) -> None:
        assert cfg(tmp_path).work_dir == Path(str(tmp_path / "evidence.json") + ".work")


class TestArgumentValidation:
    @pytest.mark.parametrize(
        "code,over",
        [
            ("BASELINE_COMMIT_INVALID", dict(baseline_commit="abc123")),
            ("BASELINE_COMMIT_INVALID", dict(baseline_commit="z" * 40)),
            ("BOUND_SECONDS_INVALID", dict(bound_seconds=299)),
            ("BOUND_SECONDS_INVALID", dict(bound_seconds=True)),
            ("OPERATION_TIMEOUT_INVALID", dict(operation_timeout=0.5)),
            ("OPERATION_TIMEOUT_INVALID", dict(operation_timeout=999)),
            ("PRE_DEPLOY_BUILD_ID_INVALID", dict(pre_deploy_build_id="x y")),
            ("PRE_DEPLOY_BUILD_ID_INVALID", dict(pre_deploy_build_id="")),
            ("OWNER_ATTESTED_ID_INVALID", dict(deploy_run_id="")),
            ("OWNER_ATTESTED_ID_INVALID", dict(source_head_sha="has space")),
            ("ARGUMENT_MISSING", dict(deploy_completed_utc=None)),
            ("ARGUMENT_MISSING", dict(bound_seconds=None)),
            ("ARGUMENT_MISSING", dict(backend_attestation_pre=None)),
        ],
    )
    def test_invalid_or_missing_run_arguments(self, tmp_path: Path, code: str, over: dict[str, Any]) -> None:
        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_arguments(cfg(tmp_path, **over))
        assert info.value.code == code

    def test_minimum_bound_and_maximum_timeout_are_accepted(self, tmp_path: Path) -> None:
        subject.validate_arguments(cfg(tmp_path, bound_seconds=300, operation_timeout=120.0))

    def test_cleanup_only_needs_only_commit_and_output(self, tmp_path: Path) -> None:
        only = subject.RunConfig(evidence_output=tmp_path / "e.json", baseline_commit=BASELINE, cleanup_only=True)
        subject.validate_arguments(only)


# ---------------------------------------------------------------------------
# Login handling
# ---------------------------------------------------------------------------
def login_http(clock: Clock, seconds: float, response: Any = (200, {"token": TOKEN})) -> Callable[..., Any]:
    def call(method: str, url: str, headers: dict[str, str], json_body: Any = None, timeout: float = 30.0) -> Any:
        clock.advance(seconds)
        if isinstance(response, BaseException):
            raise response
        return response
    return call


def do_login(http: Callable[..., Any], clock: Clock) -> tuple[subject.LoginOutcome, subject.PasswordHolder,
                                                              subject.SecretRegistry, subject.ApiClient]:
    secrets = subject.SecretRegistry()
    client = subject.ApiClient(http, API, 30.0, secrets)
    holder = subject.PasswordHolder(PASSWORD)
    return client.login(holder, clock.monotonic), holder, secrets, client


class TestLogin:
    def test_single_post_with_public_email_password_and_225s_timeout(self) -> None:
        clock = Clock()
        rec = HttpRecorder([(200, {"token": TOKEN})])
        outcome, _, _, _ = do_login(rec, clock)
        assert outcome.state == "ok" and len(rec.calls) == 1
        call = rec.calls[0]
        assert (call["method"], call["url"]) == ("POST", API + "/api/auth/login")
        assert call["body"] == {"email": subject.DEMO_EMAIL, "password": PASSWORD}
        assert call["timeout"] == 225.0 and call["headers"] == {}
        rec.assert_clean()

    def test_token_is_registered_as_a_secret_and_the_password_is_released(self) -> None:
        clock = Clock()
        _, holder, secrets, client = do_login(HttpRecorder([(200, {"token": TOKEN})]), clock)
        assert TOKEN in secrets.values() and client.has_token
        assert holder.cleared
        with pytest.raises(RuntimeError):
            holder.take()

    @pytest.mark.parametrize("seconds,state", [(80.0, "ok"), (164.999, "ok"), (165.0, "too_slow"), (224.0, "too_slow")])
    def test_the_165s_guard(self, seconds: float, state: str) -> None:
        clock = Clock()
        outcome, _, _, _ = do_login(login_http(clock, seconds), clock)
        assert outcome.state == state and outcome.token == TOKEN
        assert outcome.duration_seconds == pytest.approx(seconds)

    @pytest.mark.parametrize(
        "response,code",
        [
            ((401, None), "LOGIN_STATUS_NOT_200"),
            ((503, {"error": "x"}), "LOGIN_STATUS_NOT_200"),
            ((200, {"other": "field"}), "LOGIN_NO_TOKEN"),
            ((200, {"token": ""}), "LOGIN_NO_TOKEN"),
            ((200, {"token": 5}), "LOGIN_NO_TOKEN"),
            ((200, ["token"]), "LOGIN_NO_TOKEN"),
            (TimeoutError("boom"), "LOGIN_TRANSPORT_ERROR"),
            (ConnectionError("boom"), "LOGIN_TRANSPORT_ERROR"),
        ],
    )
    def test_login_failure_modes_release_the_password_and_store_no_token(self, response: Any, code: str) -> None:
        clock = Clock()
        outcome, holder, secrets, client = do_login(login_http(clock, 1.0, response), clock)
        assert outcome.state == "failed" and outcome.failure_code == code and outcome.token is None
        assert holder.cleared and not client.has_token and TOKEN not in secrets.values()

    def test_the_password_is_absent_from_the_outcome(self) -> None:
        clock = Clock()
        outcome, _, _, _ = do_login(HttpRecorder([(200, {"token": TOKEN})]), clock)
        assert PASSWORD not in repr(outcome)

    def test_a_non_exception_interrupt_still_releases_the_password(self) -> None:
        clock = Clock()
        secrets = subject.SecretRegistry()
        client = subject.ApiClient(login_http(clock, 1.0, KeyboardInterrupt()), API, 30.0, secrets)
        holder = subject.PasswordHolder(PASSWORD)
        with pytest.raises(KeyboardInterrupt):
            client.login(holder, clock.monotonic)
        assert holder.cleared


# ---------------------------------------------------------------------------
# Baseline reads: identity-checked and golden-required
# ---------------------------------------------------------------------------
def logged_in(responses: list[Any]) -> tuple[subject.ApiClient, HttpRecorder]:
    clock = Clock()
    rec = HttpRecorder([(200, {"token": TOKEN})] + list(responses))
    client = subject.ApiClient(rec, API, 30.0, subject.SecretRegistry())
    assert client.login(subject.PasswordHolder(PASSWORD), clock.monotonic).state == "ok"
    return client, rec


def get_ok(version: int, rows: tuple[tuple[str, str], ...] = GOLDEN_ROWS) -> tuple[int, Any]:
    return 200, portfolio_body(version, rows)


class TestObserve:
    def test_golden_observation(self) -> None:
        client, rec = logged_in([get_ok(7)])
        observation = client.observe(GOLDEN)
        assert observation == subject.Observation(7, True, 3)
        assert rec.calls[1]["headers"] == {"Authorization": "Bearer " + TOKEN}
        assert rec.calls[1]["method"] == "GET" and rec.calls[1]["url"] == API + "/api/portfolio"

    def test_non_golden_observation(self) -> None:
        client, _ = logged_in([get_ok(7, DIRTY_ROWS)])
        assert client.observe(GOLDEN).golden is False

    def test_row_order_does_not_matter_but_quantity_strings_do(self) -> None:
        client, _ = logged_in([get_ok(1, tuple(reversed(GOLDEN_ROWS))), get_ok(1, (("AAPL", "31.0"),) + GOLDEN_ROWS[1:])])
        assert client.observe(GOLDEN).golden is True
        assert client.observe(GOLDEN).golden is False

    def test_numeric_quantity_is_not_golden(self) -> None:
        body = portfolio_body(3)
        body[0]["holdings"][0]["quantity"] = 31
        client, _ = logged_in([(200, body)])
        assert client.observe(GOLDEN).golden is False

    @pytest.mark.parametrize(
        "response,code",
        [
            ((200, []), "IDENTITY_MISMATCH"),
            ((200, portfolio_body(1) + portfolio_body(2)), "IDENTITY_MISMATCH"),
            ((200, [{**portfolio_body(1)[0], "userId": "someone-else"}]), "IDENTITY_MISMATCH"),
            ((200, [{**portfolio_body(1)[0], "version": "3"}]), "VERSION_INVALID"),
            ((200, [{**portfolio_body(1)[0], "version": -1}]), "VERSION_INVALID"),
            ((200, [{**portfolio_body(1)[0], "version": True}]), "VERSION_INVALID"),
            ((200, [{**portfolio_body(1)[0], "holdings": None}]), "HOLDINGS_MALFORMED"),
            ((503, None), "STATUS_NOT_200"),
            (TimeoutError("t"), "TRANSPORT_ERROR"),
            ((200, portfolio_body(4)[0]), "NOT_A_LIST"),
            ((200, {"userId": subject.DEMO_USER_ID}), "NOT_A_LIST"),
            ((200, None), "NOT_A_LIST"),
            ((200, "text"), "NOT_A_LIST"),
        ],
    )
    def test_observation_failures_are_coded(self, response: Any, code: str) -> None:
        client, _ = logged_in([response])
        with pytest.raises(subject.ObserveError) as info:
            client.observe(GOLDEN)
        assert info.value.code == code

    def test_a_bare_portfolio_object_is_refused_like_the_browser_side_refuses_it(self) -> None:
        client, _ = logged_in([(200, portfolio_body(4)[0])])
        with pytest.raises(subject.ObserveError) as info:
            client.observe(GOLDEN)
        assert info.value.code == "NOT_A_LIST"


# ---------------------------------------------------------------------------
# Independent cleanup (spec section 5)
# ---------------------------------------------------------------------------
def do_cleanup(client: subject.ApiClient, key: Optional[str] = None) -> tuple[subject.CleanupOutcome, subject.Ledger]:
    ledger = subject.Ledger(subject.FileOps(), None, Clock().now_utc)
    outcome = subject.CleanupOutcome()
    subject.run_independent_cleanup(client, GOLDEN, outcome, ledger, break_glass_key=key)
    return outcome, ledger


def steps(ledger: subject.Ledger) -> list[tuple[int, str, str]]:
    return [(e["attempt"], e["transport"], e["result"]) for e in ledger.events]


def puts(rec: HttpRecorder) -> list[dict[str, Any]]:
    return [c for c in rec.calls if c["method"] == "PUT"]


class TestIndependentCleanup:
    def test_golden_state_is_a_no_op(self) -> None:
        client, rec = logged_in([get_ok(7)])
        outcome, ledger = do_cleanup(client)
        assert (outcome.result, outcome.golden_confirmed, outcome.attempts) == ("not_needed", True, 0)
        assert not puts(rec) and len(rec.calls) == 2
        assert steps(ledger) == [(0, "saved_jwt", "observed_golden"), (0, "saved_jwt", "not_needed")]
        rec.assert_clean()

    def test_non_golden_state_is_reset_with_the_observed_version_then_confirmed(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), (200, None), get_ok(6)])
        outcome, ledger = do_cleanup(client)
        assert outcome.result == "confirmed" and outcome.golden_confirmed and outcome.attempts == 1
        (reset,) = puts(rec)
        assert reset["url"] == API + "/api/portfolio/demo-reset" and reset["body"] == {"expectedVersion": 5}
        assert reset["headers"] == {"Authorization": "Bearer " + TOKEN}
        assert steps(ledger)[-1] == (1, "saved_jwt", "confirmed")
        rec.assert_clean()

    def test_409_is_reobserved_and_retried_with_the_fresh_version(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), (409, None), get_ok(6, DIRTY_ROWS), (200, None), get_ok(7)])
        outcome, _ = do_cleanup(client)
        assert outcome.result == "confirmed" and outcome.attempts == 2
        assert [p["body"]["expectedVersion"] for p in puts(rec)] == [5, 6]
        rec.assert_clean()

    def test_three_409s_are_the_bound_then_unconfirmed(self) -> None:
        client, rec = logged_in([
            get_ok(5, DIRTY_ROWS), (409, None), get_ok(6, DIRTY_ROWS), (409, None),
            get_ok(7, DIRTY_ROWS), (409, None), get_ok(8, DIRTY_ROWS),
        ])
        outcome, ledger = do_cleanup(client)
        assert outcome.result == "unconfirmed" and outcome.attempts == subject.CLEANUP_MAX_ATTEMPTS == 3
        assert len(puts(rec)) == 3 and outcome.detail_code == "STILL_NOT_GOLDEN"
        assert not outcome.golden_confirmed and not outcome.break_glass_used
        rec.assert_clean()
        assert [s[2] for s in steps(ledger)].count("reset_409") == 3

    def test_reobservation_that_finds_golden_stops_retrying(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), (409, None), get_ok(6), get_ok(6)])
        outcome, _ = do_cleanup(client)
        assert outcome.result == "confirmed" and len(puts(rec)) == 1
        rec.assert_clean()

    def test_a_status_other_than_200_or_409_stops_the_loop(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), (500, None), get_ok(5, DIRTY_ROWS)])
        outcome, ledger = do_cleanup(client)
        assert outcome.result == "unconfirmed" and len(puts(rec)) == 1
        assert "reset_other" in [s[2] for s in steps(ledger)]
        rec.assert_clean()

    def test_a_transport_error_on_reset_falls_through_to_the_confirming_read(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), ConnectionError("reset by peer"), get_ok(6)])
        outcome, ledger = do_cleanup(client)
        assert outcome.result == "confirmed" and "reset_error" in [s[2] for s in steps(ledger)]
        rec.assert_clean()

    @pytest.mark.parametrize("response", [(200, []), (503, None), (200, portfolio_body(1) + portfolio_body(2))])
    def test_an_unusable_first_observation_is_unconfirmed_and_mutates_nothing(self, response: Any) -> None:
        client, rec = logged_in([response])
        outcome, ledger = do_cleanup(client)
        assert outcome.result == "unconfirmed" and not puts(rec)
        assert steps(ledger)[0] == (0, "saved_jwt", "observe_failed")

    def test_an_unusable_confirming_read_is_unconfirmed(self) -> None:
        client, _ = logged_in([get_ok(5, DIRTY_ROWS), (200, None), (200, [])])
        outcome, _ = do_cleanup(client)
        assert outcome.result == "unconfirmed" and outcome.detail_code == "IDENTITY_MISMATCH"

    def test_break_glass_runs_only_with_a_key_and_only_when_still_not_golden(self) -> None:
        client, rec = logged_in([
            get_ok(5, DIRTY_ROWS), (409, None), get_ok(6, DIRTY_ROWS), (409, None),
            get_ok(7, DIRTY_ROWS), (409, None), get_ok(8, DIRTY_ROWS),  # bound reached, still dirty
            (200, None), get_ok(9),
        ])
        outcome, ledger = do_cleanup(client, INTERNAL_KEY)
        assert outcome.result == "confirmed" and outcome.break_glass_used
        assert outcome.transport_label == "saved_jwt+internal_break_glass"
        internal = puts(rec)[-1]
        assert internal["url"] == API + "/api/internal/portfolio/demo-reset"
        assert internal["headers"] == {"X-Internal-Api-Key": INTERNAL_KEY}
        assert internal["body"] == {"expectedVersion": 8}
        assert INTERNAL_KEY not in json.dumps(ledger.events)
        rec.assert_clean()

    def test_break_glass_is_not_attempted_without_a_key(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), (500, None), get_ok(5, DIRTY_ROWS)])
        outcome, _ = do_cleanup(client, None)
        assert not outcome.break_glass_used
        assert all("internal" not in c["url"] for c in rec.calls)

    def test_break_glass_is_not_attempted_when_the_jwt_path_already_confirmed(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), (200, None), get_ok(6)])
        outcome, _ = do_cleanup(client, INTERNAL_KEY)
        assert outcome.result == "confirmed" and not outcome.break_glass_used
        assert all("internal" not in c["url"] for c in rec.calls)

    def test_break_glass_that_does_not_restore_golden_is_unconfirmed(self) -> None:
        client, rec = logged_in([get_ok(5, DIRTY_ROWS), (500, None), get_ok(5, DIRTY_ROWS), (200, None), get_ok(6, DIRTY_ROWS)])
        outcome, _ = do_cleanup(client, INTERNAL_KEY)
        assert outcome.result == "unconfirmed" and outcome.break_glass_used
        rec.assert_clean()

    def test_break_glass_transport_error_is_recorded_and_the_final_read_decides(self) -> None:
        client, _ = logged_in([get_ok(5, DIRTY_ROWS), (500, None), get_ok(5, DIRTY_ROWS), TimeoutError("t"), get_ok(6)])
        outcome, ledger = do_cleanup(client, INTERNAL_KEY)
        assert outcome.result == "confirmed" and "break_glass_error" in [s[2] for s in steps(ledger)]

    def test_every_cleanup_ledger_event_is_inside_the_contract_vocabulary(self, tmp_path: Path) -> None:
        client, _ = logged_in([get_ok(5, DIRTY_ROWS), (409, None), get_ok(6, DIRTY_ROWS), (200, None), get_ok(7)])
        ledger = subject.Ledger(subject.FileOps(), tmp_path / "l.jsonl", Clock().now_utc)
        subject.run_independent_cleanup(client, GOLDEN, subject.CleanupOutcome(), ledger, break_glass_key=None)
        assert subject.parse_ledger((tmp_path / "l.jsonl").read_bytes(), CONTRACT).clean

    def test_keyboard_interrupt_propagates_and_leaves_the_outcome_armed(self) -> None:
        client, _ = logged_in([get_ok(5, DIRTY_ROWS), KeyboardInterrupt()])
        outcome = subject.CleanupOutcome()
        ledger = subject.Ledger(subject.FileOps(), None, Clock().now_utc)
        with pytest.raises(KeyboardInterrupt):
            subject.run_independent_cleanup(client, GOLDEN, outcome, ledger, break_glass_key=None)
        assert outcome.armed and outcome.result == "not_reached"


# ---------------------------------------------------------------------------
# Frontend binding: build-id extraction, soft-404 guard, three spaced fetches
# ---------------------------------------------------------------------------
def sha(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()


class TestBuildIdExtraction:
    def test_flight_payload_form(self) -> None:
        assert subject.extract_build_id(next_html("AbCdEfGhIjKlMnOpQrStU").decode()) == "AbCdEfGhIjKlMnOpQrStU"

    def test_root_row_carried_in_a_json_string_literal(self) -> None:
        assert subject.extract_build_id(flight_html('0:{"b":"AbCdEfGhIjKlMnOpQrStU","p":""}\n')) == "AbCdEfGhIjKlMnOpQrStU"

    def test_static_path_fallback(self) -> None:
        html = '<script src="/_next/static/AbCdEfGhIjKlMnOpQrStU/_buildManifest.js"></script>'
        assert subject.extract_build_id(html) == "AbCdEfGhIjKlMnOpQrStU"

    def test_flight_and_matching_static_path_agree(self) -> None:
        html = next_html("AbCdEfGhIjKlMnOpQrStU").decode() + '<a href="/_next/static/AbCdEfGhIjKlMnOpQrStU/_ssgManifest.js">'
        assert subject.extract_build_id(html) == "AbCdEfGhIjKlMnOpQrStU"

    @pytest.mark.parametrize(
        "html,code",
        [
            (next_html("AbCdEfGhIjKlMnOpQrStU").decode() + '/_next/static/ZzZzZzZzZzZzZzZzZzZzZ/_buildManifest.js', "BUILD_ID_AMBIGUOUS"),
            (flight_html('0:{"b":"AbCdEfGh1"}\n0:{"b":"ZzZzZzZz2"}\n'), "BUILD_ID_AMBIGUOUS"),
            ('/_next/static/AbCdEfGh1/a /_next/static/ZzZzZzZz2/b', "BUILD_ID_AMBIGUOUS"),
            ('<script src="/_next/static/chunks/main-abc.js"></script>', "BUILD_ID_NOT_FOUND"),
            ('<link href="/_next/static/css/app.css"><img src="/_next/static/media/logo.png">', "BUILD_ID_NOT_FOUND"),
            ('{"b":"short"}', "BUILD_ID_NOT_FOUND"),
            ("<html></html>", "BUILD_ID_NOT_FOUND"),
        ],
    )
    def test_ambiguous_or_absent_ids_fail_closed(self, html: str, code: str) -> None:
        with pytest.raises(subject.BindingError) as info:
            subject.extract_build_id(html)
        assert info.value.code == code


class TestFrontendSnapshot:
    def _snapshot(self, response: Any) -> subject.FrontendSnapshot:
        return subject.fetch_frontend_snapshot(FetchScript([response]), FRONTEND, 30.0)

    def test_records_the_html_hash_and_the_sorted_asset_name_hash(self) -> None:
        body = next_html(assets=("zeta.js", "alpha.js", "alpha.js"))
        snap = self._snapshot((200, "text/html", body))
        assert snap.build_id == NEW_BUILD_ID and snap.html_sha256 == sha(body)
        names = "\n".join(["/_next/static/chunks/alpha.js", "/_next/static/chunks/zeta.js"]).encode()
        assert snap.asset_names_sha256 == sha(names) and snap.asset_name_count == 2

    def test_asset_hash_ignores_order_but_html_hash_does_not(self) -> None:
        a = self._snapshot((200, "text/html", next_html(assets=("a.js", "b.js"))))
        b = self._snapshot((200, "text/html", next_html(assets=("b.js", "a.js"))))
        assert a.asset_names_sha256 == b.asset_names_sha256 and a.html_sha256 != b.html_sha256

    def test_the_fetch_targets_the_root_of_the_allowlisted_origin(self) -> None:
        script = FetchScript([html_ok()])
        subject.fetch_frontend_snapshot(script, FRONTEND, 30.0)
        assert script.urls == [FRONTEND + "/"]

    @pytest.mark.parametrize(
        "response,code",
        [
            ((200, "text/html", b"<html><body>Not Found</body></html>"), "NO_NEXT_MARKER"),
            ((200, "application/json", next_html()), "NOT_HTML"),
            ((200, "", next_html()), "NOT_HTML"),
            ((404, "text/html", next_html()), "STATUS_NOT_200"),
            ((302, "text/html", next_html()), "STATUS_NOT_200"),
            ((503, "text/html", b"unavailable"), "STATUS_NOT_200"),
            ((200, "text/html", b"\xff\xfe<html>"), "NOT_UTF8"),
            ((200, "text/html", b"<script>self.__next_f.push([])</script>"), "BUILD_ID_NOT_FOUND"),
            (TimeoutError("t"), "FETCH_FAILED"),
        ],
    )
    def test_swa_soft_404_and_other_non_builds_are_rejected(self, response: Any, code: str) -> None:
        with pytest.raises(subject.BindingError) as info:
            self._snapshot(response)
        assert info.value.code == code

    def test_content_type_match_is_case_insensitive_and_tolerates_parameters(self) -> None:
        assert self._snapshot((200, " Text/HTML; charset=UTF-8", next_html())).build_id == NEW_BUILD_ID


class TestPreBinding:
    def _seams(self, responses: list[Any]) -> tuple[subject.Seams, Clock, FetchScript]:
        harness_clock = Clock()
        fetch = FetchScript(responses)
        seams = subject.Seams(
            http_call=HttpRecorder([]), fetch_root_html=fetch, run_cmd=CmdRouter(), spawn_child=lambda *a: None,  # type: ignore[arg-type,return-value]
            monotonic=harness_clock.monotonic, now_utc=harness_clock.now_utc, sleep=harness_clock.sleep,
            getpass_fn=lambda p: "", input_fn=lambda p: "", fs=subject.FileOps(), out=lambda t: None,
            err=lambda t: None, defer_sigint=contextlib.nullcontext, stdin_isatty=lambda: True,
        )
        return seams, harness_clock, fetch

    def test_three_fetches_spaced_at_least_five_seconds_apart(self) -> None:
        seams, clock, fetch = self._seams([html_ok(), html_ok(), html_ok()])
        binding = subject.collect_frontend_pre_binding(seams, FRONTEND, 30.0, OLD_BUILD_ID)
        assert len(fetch.urls) == subject.STABLE_FETCH_COUNT == 3
        assert clock.sleeps == [5.0, 5.0] and all(s >= 5.0 for s in clock.sleeps)
        assert binding.build_id == NEW_BUILD_ID and binding.stable_fetch_count == 3
        assert binding.differs_from_pre_deploy is True
        assert binding.html_sha256 == sha(next_html()) and binding.asset_name_count == 2

    def test_unstable_build_id_is_a_precondition_failure(self) -> None:
        seams, _, _ = self._seams([html_ok(), html_ok("OtherBuildId22222222c"), html_ok()])
        with pytest.raises(subject.PreconditionError) as info:
            subject.collect_frontend_pre_binding(seams, FRONTEND, 30.0, OLD_BUILD_ID)
        assert info.value.code == "FRONTEND_BUILD_UNSTABLE"

    def test_changed_asset_set_between_fetches_is_unstable(self) -> None:
        seams, _, _ = self._seams([html_ok(), html_ok(), html_ok(assets=("x.js",))])
        with pytest.raises(subject.PreconditionError) as info:
            subject.collect_frontend_pre_binding(seams, FRONTEND, 30.0, OLD_BUILD_ID)
        assert info.value.code == "FRONTEND_BUILD_UNSTABLE"

    def test_build_id_equal_to_the_pre_deploy_id_is_a_precondition_failure(self) -> None:
        seams, _, _ = self._seams([html_ok(OLD_BUILD_ID)] * 3)
        with pytest.raises(subject.PreconditionError) as info:
            subject.collect_frontend_pre_binding(seams, FRONTEND, 30.0, OLD_BUILD_ID)
        assert info.value.code == "FRONTEND_BUILD_ID_UNCHANGED"

    @pytest.mark.parametrize("bad,code", [
        ((200, "text/html", b"<html>nope</html>"), "FRONTEND_NO_NEXT_MARKER"),
        (TimeoutError("t"), "FRONTEND_FETCH_FAILED"),
        ((200, "application/json", b"{}"), "FRONTEND_NOT_HTML"),
    ])
    def test_a_bad_fetch_stops_immediately(self, bad: Any, code: str) -> None:
        seams, _, fetch = self._seams([html_ok(), bad, html_ok()])
        with pytest.raises(subject.PreconditionError) as info:
            subject.collect_frontend_pre_binding(seams, FRONTEND, 30.0, OLD_BUILD_ID)
        assert info.value.code == code and len(fetch.urls) == 2

    @pytest.mark.parametrize(
        "response,expected",
        [
            (html_ok(), "match"),
            (html_ok("OtherBuildId22222222c"), "mismatch"),
            (html_ok(assets=("changed.js",)), "mismatch"),
            ((503, "text/html", b""), "fetch_failed"),
            (TimeoutError("t"), "fetch_failed"),
        ],
    )
    def test_post_binding_states(self, response: Any, expected: str) -> None:
        seams, _, _ = self._seams([html_ok(), html_ok(), html_ok(), response])
        pre = subject.collect_frontend_pre_binding(seams, FRONTEND, 30.0, OLD_BUILD_ID)
        assert subject.collect_frontend_post_binding(seams, FRONTEND, 30.0, pre) == expected


# ---------------------------------------------------------------------------
# Attestation files
# ---------------------------------------------------------------------------
class TestAttestation:
    def test_valid_file_records_size_hash_and_only_the_named_fields(self) -> None:
        raw = attestation_json("2026-09-20T11:45:00Z", extra={"subscription": "not-recorded", "workspace": {"id": "x"}}).encode()
        att = subject.parse_attestation(raw)
        assert att.read_at == dt("2026-09-20T11:45:00Z")
        assert att.size_bytes == len(raw) and att.sha256 == sha(raw)
        assert att.services == {
            "api-gateway": {"revision": "api-gateway--0000100", "digest": GATEWAY_DIGEST},
            "portfolio-service": {"revision": "portfolio-service--0000200", "digest": PORTFOLIO_DIGEST},
        }
        assert "not-recorded" not in repr(att)

    def test_extra_services_are_ignored(self) -> None:
        doc = json.loads(attestation_json("2026-09-20T11:45:00Z"))
        doc["services"]["market-data-service"] = {"revision": "x", "digest": "not checked"}
        assert set(subject.parse_attestation(json.dumps(doc).encode()).services) == set(subject.ATTESTATION_SERVICES)

    def test_a_utf8_bom_from_windows_tools_is_accepted(self) -> None:
        raw = b"\xef\xbb\xbf" + attestation_json("2026-09-20T11:45:00Z").encode()
        assert subject.parse_attestation(raw).read_at == dt("2026-09-20T11:45:00Z")

    @pytest.mark.parametrize(
        "mutate,code",
        [
            (lambda d: d.update(schema="wave10-5b-backend-attestation-v2"), "SCHEMA_MISMATCH"),
            (lambda d: d.pop("schema"), "SCHEMA_MISMATCH"),
            (lambda d: d.update(readAtUtc="2026-09-20 11:45:00Z"), "TIME_INVALID"),
            (lambda d: d.update(readAtUtc="2026-09-20T11:45:00+00:00"), "TIME_INVALID"),
            (lambda d: d.update(readAtUtc=5), "TIME_INVALID"),
            (lambda d: d.pop("readAtUtc"), "TIME_INVALID"),
            (lambda d: d.update(services=[]), "SERVICES_INVALID"),
            (lambda d: d["services"].pop("portfolio-service"), "SERVICE_MISSING"),
            (lambda d: d["services"].pop("api-gateway"), "SERVICE_MISSING"),
            (lambda d: d["services"]["api-gateway"].update(revision="has space"), "REVISION_INVALID"),
            (lambda d: d["services"]["api-gateway"].update(revision=""), "REVISION_INVALID"),
            (lambda d: d["services"]["api-gateway"].pop("revision"), "REVISION_INVALID"),
            (lambda d: d["services"]["api-gateway"].update(digest="a" * 64), "DIGEST_INVALID"),
            (lambda d: d["services"]["api-gateway"].update(digest="sha256:" + "A" * 64), "DIGEST_INVALID"),
            (lambda d: d["services"]["portfolio-service"].update(digest="sha256:abc"), "DIGEST_INVALID"),
        ],
    )
    def test_invalid_attestations_are_rejected_with_a_code(self, mutate: Callable[[dict], Any], code: str) -> None:
        doc = json.loads(attestation_json("2026-09-20T11:45:00Z"))
        mutate(doc)
        with pytest.raises(subject.AttestationError) as info:
            subject.parse_attestation(json.dumps(doc).encode())
        assert info.value.code == code

    def test_non_json_and_oversized_files_are_rejected(self) -> None:
        with pytest.raises(subject.AttestationError) as info:
            subject.parse_attestation(b"not json")
        assert info.value.code == "NOT_JSON"
        with pytest.raises(subject.AttestationError) as info2:
            subject.parse_attestation(b" " * (subject.ATTESTATION_MAX_BYTES + 1))
        assert info2.value.code == "TOO_LARGE"

    def test_unreadable_file_is_coded(self, tmp_path: Path) -> None:
        with pytest.raises(subject.AttestationError) as info:
            subject.read_attestation(subject.FileOps(), tmp_path / "absent.json")
        assert info.value.code == "UNREADABLE"

    def test_equality_is_on_every_revision_and_digest(self) -> None:
        base = subject.parse_attestation(attestation_json("2026-09-20T11:45:00Z").encode())
        same = subject.parse_attestation(attestation_json("2026-09-20T12:30:00Z").encode())
        assert subject.attestations_equal(base, same)
        for changes in (
            dict(gateway_revision="api-gateway--0000101"),
            dict(gateway_digest="sha256:" + "c" * 64),
            dict(portfolio_revision="portfolio-service--0000201"),
            dict(portfolio_digest="sha256:" + "d" * 64),
        ):
            other = subject.parse_attestation(attestation_json("2026-09-20T12:30:00Z", **changes).encode())
            assert not subject.attestations_equal(base, other), changes


# ---------------------------------------------------------------------------
# Child environment (Appendix B) and the child runner
# ---------------------------------------------------------------------------
def child_env(**over: Any) -> dict[str, str]:
    kwargs: dict[str, Any] = dict(
        frontend_origin=FRONTEND, api_origin=API, token=TOKEN, work_dir=Path("/w/evidence.json.work"),
        golden_file=Path("/w/evidence.json.work/golden.json"), baseline_version=5,
        allow_active_presence=False,
    )
    kwargs.update(over)
    return subject.build_child_env(OS_ENVIRON, **kwargs)


class TestChildEnvironment:
    def test_only_os_variables_and_the_step_b_set_are_present(self) -> None:
        env = child_env()
        os_names = {k for k in env if k.upper() in subject.OS_ENV_ALLOWLIST}
        assert set(env) - os_names == set(subject.CHILD_ENV_KEYS)
        assert os_names == {"PATH", "SystemRoot", "TEMP", "HOME", "PLAYWRIGHT_BROWSERS_PATH"}

    def test_values_are_the_appendix_b_values(self) -> None:
        env = child_env(baseline_version=9, allow_active_presence=True)
        assert env["STEP_B_5B_FRONTEND_URL"] == FRONTEND and env["STEP_B_5B_API_URL"] == API
        assert env["STEP_B_5B_TOKEN"] == TOKEN and env["STEP_B_5B_EMAIL"] == subject.DEMO_EMAIL
        assert env["STEP_B_5B_USER_ID"] == subject.DEMO_USER_ID
        assert env["STEP_B_5B_BASELINE_VERSION"] == "9" and env["STEP_B_5B_ALLOW_ACTIVE_PRESENCE"] == "1"
        assert env["STEP_B_5B_ACTION_TIMEOUT_MS"] == "120000"  # the default; --action-timeout-ms overrides it
        assert child_env(action_timeout_ms=45000)["STEP_B_5B_ACTION_TIMEOUT_MS"] == "45000"
        assert env["STEP_B_5B_WORK_DIR"] == str(Path("/w/evidence.json.work"))
        assert child_env()["STEP_B_5B_ALLOW_ACTIVE_PRESENCE"] == "0"

    def test_password_and_internal_key_are_absent_from_every_value(self) -> None:
        env = child_env()
        for value in env.values():
            assert PASSWORD not in value and INTERNAL_KEY not in value

    def test_debug_and_pwdebug_are_cleared_even_when_the_parent_sets_them(self) -> None:
        assert OS_ENVIRON["DEBUG"] and OS_ENVIRON["PWDEBUG"]
        env = child_env()
        assert "DEBUG" not in env and "PWDEBUG" not in env

    def test_unlisted_parent_variables_are_not_inherited(self) -> None:
        env = child_env()
        for leaked in ("INTERNAL_API_KEY", "WAVE9_STEP_A_PASSWORD", "NODE_OPTIONS"):
            assert leaked not in env
        assert "env-internal-key-should-not-propagate" not in env.values()

    def test_windows_style_mixed_case_os_names_are_matched_case_insensitively(self) -> None:
        env = subject.os_env({"Path": "p", "SYSTEMROOT": "r", "Temp": "t", "AWS_SECRET_ACCESS_KEY": "x"})
        assert set(env) == {"Path", "SYSTEMROOT", "Temp"}

    def test_owner_secrets_registered_as_fingerprints_are_refused_in_any_value_but_the_token_key(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        registry.add_owner_secret(INTERNAL_KEY)
        registry.add(TOKEN)
        assert child_env(owner_secrets=registry)["STEP_B_5B_TOKEN"] == TOKEN
        for secret in (PASSWORD, INTERNAL_KEY):
            with pytest.raises(RuntimeError):
                child_env(owner_secrets=registry, work_dir=Path("/w/" + secret))
        with pytest.raises(RuntimeError):  # a secret arriving through an inherited OS variable
            subject.build_child_env({"PATH": PASSWORD}, frontend_origin=FRONTEND, api_origin=API, token=TOKEN,
                                    work_dir=Path("/w"), golden_file=Path("/g"), baseline_version=1,
                                    allow_active_presence=False, owner_secrets=registry)
        registry2 = subject.SecretRegistry()
        registry2.add_owner_secret(TOKEN)  # a secret equal to the token is only tolerated in the token variable
        assert child_env(owner_secrets=registry2)["STEP_B_5B_TOKEN"] == TOKEN

    def test_argv_is_static_with_no_secret_and_no_path(self) -> None:
        argv = subject.build_child_argv()
        assert argv == ["node", "node_modules/@playwright/test/cli.js", "test", "-c",
                        "tests/production-e2e/playwright.step-b-5b.config.ts",
                        "--reporter=null", "--workers=1", "--retries=0"]
        assert not any(PASSWORD in a or TOKEN in a or "/" == a[:1] for a in argv)


def child_seams(harness_clock: Clock, spawn: Any) -> subject.Seams:
    return subject.Seams(
        http_call=HttpRecorder([]), fetch_root_html=FetchScript([]), run_cmd=CmdRouter(), spawn_child=spawn,
        monotonic=harness_clock.monotonic, now_utc=harness_clock.now_utc, sleep=harness_clock.sleep,
        getpass_fn=lambda p: "", input_fn=lambda p: "", fs=subject.FileOps(), out=lambda t: None,
        err=lambda t: None, defer_sigint=contextlib.nullcontext, stdin_isatty=lambda: True,
    )


class TestChildRunner:
    def _run(self, tmp_path: Path, child: Optional[FakeChild], *, clock: Optional[Clock] = None,
             deadline_in: float = 2700.0, spawn_error: Optional[Exception] = None):
        clock = clock or Clock()
        (tmp_path / "w").mkdir()
        config = subject.RunConfig(evidence_output=tmp_path / "e.json", baseline_commit=BASELINE, frontend_dir=tmp_path / "frontend")
        calls: list[Any] = []

        def spawn(argv: list[str], env: Any, cwd: Path, log_path: Path) -> Any:
            calls.append((argv, cwd, log_path))
            if spawn_error is not None:
                raise spawn_error
            return child

        ledger = subject.Ledger(subject.FileOps(), None, clock.now_utc)
        outcome = subject.ChildOutcome()
        run = lambda: subject.run_browser_child(config, child_seams(clock, spawn), ledger, {"K": "v"}, clock.mono + deadline_in, outcome)  # noqa: E731
        return clock, ledger, outcome, calls, run

    def test_exit_zero(self, tmp_path: Path) -> None:
        clock = Clock()
        child = FakeChild(clock, 10.0, 0)
        clock, ledger, outcome, calls, run = self._run(tmp_path, child, clock=clock)
        run()
        assert (outcome.state, outcome.exit_code) == ("exit_zero", 0) and child.closed and not child.killed
        assert [e["event"] for e in ledger.events] == ["child_started", "child_done"] and ledger.events[1]["exit"] == 0
        assert calls[0][1] == tmp_path / "frontend" and calls[0][2] == tmp_path / "e.json.work" / "child.log"

    def test_non_zero_exit(self, tmp_path: Path) -> None:
        clock = Clock()
        clock, _, outcome, _, run = self._run(tmp_path, FakeChild(clock, 5.0, 1), clock=clock)
        run()
        assert (outcome.state, outcome.exit_code) == ("exit_nonzero", 1)

    def test_the_child_is_killed_at_the_deadline(self, tmp_path: Path) -> None:
        clock = Clock()
        child = FakeChild(clock, 0.0, 0, never_exit=True)
        clock, ledger, outcome, _, run = self._run(tmp_path, child, clock=clock, deadline_in=120.0)
        run()
        assert child.killed and outcome.state == "deadline_killed" and outcome.exit_code == -9
        assert 120.0 <= sum(clock.sleeps) <= 121.0
        assert ledger.events[-1] == {**ledger.events[-1], "event": "child_done", "exit": -9}

    def test_spawn_failure_is_recorded(self, tmp_path: Path) -> None:
        _, ledger, outcome, _, run = self._run(tmp_path, None, spawn_error=FileNotFoundError("node"))
        run()
        assert outcome.state == "spawn_failed" and [e["event"] for e in ledger.events] == ["child_started", "child_done"]

    def test_an_interrupt_kills_the_child_before_propagating(self, tmp_path: Path) -> None:
        clock = Clock()
        child = FakeChild(clock, 10.0, 0, interrupt=True)
        clock, ledger, outcome, _, run = self._run(tmp_path, child, clock=clock)
        with pytest.raises(KeyboardInterrupt):
            run()
        assert child.killed and child.closed and outcome.state == "interrupted"
        assert ledger.events[-1]["event"] == "child_done"


# ---------------------------------------------------------------------------
# Verdict table: each single failure yields non-GO (spec section 7)
# ---------------------------------------------------------------------------
def make_legs(**changes: dict[str, Any]) -> dict[str, subject.LegResult]:
    legs = {
        leg: subject.LegResult(leg, subject.LEG_ROUTES[leg], "passed", "OK", tuple(LEG_HTTP.get(leg, [])),
                               dict(LEG_FACTS[leg]))
        for leg in CONTRACT.legs
    }
    for leg, change in changes.items():
        legs[leg] = dataclasses.replace(legs[leg], **change)
    return legs


def base(**over: Any) -> subject.VerdictInputs:
    kwargs: dict[str, Any] = dict(
        interrupted=None, unexpected_error=None, login_state="ok", baseline_state="golden",
        warmup_state="ok", child_state="exit_zero", ledger_clean=True, ledger_write_failed=False,
        legs=make_legs(), spec_armed_before_l8=True, allow_active_presence=False,
        frontend_post_state="match", backend_post_state="valid_equal",
        elapsed_since_deploy_seconds=4000.0, bound_seconds=7200,
        session_seconds=900.0, cleanup_armed=True, cleanup_result="not_needed",
    )
    kwargs.update(over)
    return subject.VerdictInputs(**kwargs)


def visitor_legs() -> dict[str, subject.LegResult]:
    facts = dict(LEG_FACTS["L5"], anotherSessionActive=True)
    skipped = dict(status="skipped", reason="SKIPPED_VISITOR_PRESENT", http=(),
                   facts={"mutationSkippedReason": "VISITOR_PRESENT"})
    return make_legs(L5=dict(facts=facts), L8=skipped, L9=dict(skipped))


class TestVerdictTable:
    def test_the_all_clear_inputs_are_go(self) -> None:
        verdict = subject.compute_verdict(base())
        assert (verdict.verdict, verdict.code, verdict.failed_criteria) == ("GO", "NONE", ())
        assert verdict.run_result == "all_items_passed"

    @pytest.mark.parametrize("leg", list(CONTRACT.legs))
    @pytest.mark.parametrize("status,reason", [("failed", "ASSERTION_FAILED"), ("missing", "LEG_MISSING"),
                                              ("skipped", "SKIPPED_PRIOR_FAILURE")])
    def test_each_leg_not_passing_is_non_go(self, leg: str, status: str, reason: str) -> None:
        verdict = subject.compute_verdict(base(legs=make_legs(**{leg: dict(status=status, reason=reason)})))
        assert verdict.verdict == "NON_GO" and "LEG_FAILED" in verdict.failed_criteria
        assert verdict.run_result == "not_all_items_passed"

    def test_a_mutation_leg_that_ran_after_an_earlier_failure_is_flagged(self) -> None:
        verdict = subject.compute_verdict(base(legs=make_legs(L3=dict(status="failed", reason="TIMEOUT"))))
        assert {"LEG_FAILED", "MUTATION_AFTER_FAILURE"} <= set(verdict.failed_criteria)

    def test_visitor_present_is_incomplete_not_a_pass_and_not_a_failure(self) -> None:
        verdict = subject.compute_verdict(base(legs=visitor_legs(), cleanup_result="not_needed"))
        assert (verdict.verdict, verdict.code) == ("INCOMPLETE", "VISITOR_PRESENT")
        assert verdict.run_result == "not_all_items_passed"

    def test_skipped_for_visitor_despite_the_allow_flag_is_a_failure(self) -> None:
        verdict = subject.compute_verdict(base(legs=visitor_legs(), allow_active_presence=True))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "MUTATION_SKIPPED_UNEXPECTEDLY")

    @pytest.mark.parametrize("reason", ["SKIPPED_BASELINE_NOT_GOLDEN", "SKIPPED_PRIOR_FAILURE"])
    def test_mutation_legs_skipped_for_any_other_reason_are_a_failure(self, reason: str) -> None:
        skipped = dict(status="skipped", reason=reason, http=(), facts={})
        verdict = subject.compute_verdict(base(legs=make_legs(L8=skipped, L9=dict(skipped))))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "MUTATION_SKIPPED_UNEXPECTEDLY")

    def test_only_one_mutation_leg_skipped_is_a_failure(self) -> None:
        skipped = dict(status="skipped", reason="SKIPPED_VISITOR_PRESENT", http=(), facts={})
        verdict = subject.compute_verdict(base(legs=make_legs(L9=skipped)))
        assert verdict.verdict == "NON_GO"

    @pytest.mark.parametrize("presence", [True, None])
    def test_mutating_while_another_session_is_active_without_the_flag_is_a_violation(self, presence: Any) -> None:
        legs = make_legs(L5=dict(facts=dict(LEG_FACTS["L5"], anotherSessionActive=presence)))
        verdict = subject.compute_verdict(base(legs=legs))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "PRESENCE_POLICY_VIOLATION")

    def test_the_allow_flag_permits_mutating_with_a_visitor_and_it_is_recorded_by_the_caller(self) -> None:
        legs = make_legs(L5=dict(facts=dict(LEG_FACTS["L5"], anotherSessionActive=True)))
        assert subject.compute_verdict(base(legs=legs, allow_active_presence=True)).verdict == "GO"

    def test_no_armed_record_before_the_mutation_is_a_failure(self) -> None:
        verdict = subject.compute_verdict(base(spec_armed_before_l8=False))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "ARMED_MISSING")

    @pytest.mark.parametrize("facts", [dict(resetButtonVisible=False), dict(editButtonVisible=False)])
    def test_both_controls_must_be_rendered(self, facts: dict[str, Any]) -> None:
        legs = make_legs(L1=dict(facts=dict(LEG_FACTS["L1"], **facts)))
        assert subject.compute_verdict(base(legs=legs)).code == "CONTROLS_NOT_RENDERED"

    def test_controls_facts_must_be_present_not_merely_uncontradicted(self) -> None:
        legs = make_legs(L1=dict(facts={"portfolioLoadStatus": 200}))
        assert subject.compute_verdict(base(legs=legs)).code == "CONTROLS_NOT_RENDERED"

    @pytest.mark.parametrize("state", ["mismatch", "fetch_failed", "not_collected"])
    def test_frontend_post_binding_must_match(self, state: str) -> None:
        verdict = subject.compute_verdict(base(frontend_post_state=state))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "FRONTEND_POST_BINDING_FAILED")

    @pytest.mark.parametrize("state", ["mismatch", "invalid", "missing", "skipped", "pending"])
    def test_backend_binding_must_be_valid_and_equal(self, state: str) -> None:
        verdict = subject.compute_verdict(base(backend_post_state=state))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "BACKEND_BINDING_FAILED")

    def test_pending_backend_state_is_tolerated_only_for_the_provisional_check(self) -> None:
        assert subject.compute_verdict(base(backend_post_state="pending"), final=False).verdict == "GO"
        assert subject.compute_verdict(base(backend_post_state="mismatch"), final=False).verdict == "NON_GO"

    def test_time_bound(self) -> None:
        assert subject.compute_verdict(base(elapsed_since_deploy_seconds=7200.0)).verdict == "GO"
        verdict = subject.compute_verdict(base(elapsed_since_deploy_seconds=7200.001))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "BOUND_EXCEEDED")

    def test_overall_deadline_is_judged_on_the_jwts_age(self) -> None:
        assert subject.compute_verdict(base(session_seconds=2700.0)).verdict == "GO"
        verdict = subject.compute_verdict(base(session_seconds=2700.001))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "DEADLINE_EXCEEDED")
        assert "run_seconds" not in {f.name for f in dataclasses.fields(subject.VerdictInputs)}  # informational only

    @pytest.mark.parametrize("state", ["failed", "not_reached", "ok "])
    def test_a_warmup_that_did_not_succeed_never_expects_the_child(self, state: str) -> None:
        quiet = dict(child_state="not_run", cleanup_armed=False, cleanup_result="skipped_no_mutation",
                     legs=subject.derive_legs(None, CONTRACT, child_expected=False, fallback_reason="SKIPPED_PRIOR_FAILURE"))
        verdict = subject.compute_verdict(base(warmup_state=state, **quiet))
        assert (verdict.verdict, verdict.code) == (
            ("INCOMPLETE", "WARMUP_FAILED") if state == "failed" else ("NON_GO", "WARMUP_NOT_REACHED"))
        assert verdict.run_result == "not_all_items_passed"

    def test_warmup_failed_is_incomplete_but_any_hard_failure_beside_it_makes_it_non_go(self) -> None:
        quiet = dict(warmup_state="failed", child_state="not_run", cleanup_armed=False,
                     cleanup_result="skipped_no_mutation")
        assert subject.compute_verdict(base(**quiet)).verdict == "INCOMPLETE"
        assert subject.compute_verdict(base(frontend_post_state="mismatch", **quiet)).verdict == "NON_GO"

    def test_the_warmup_state_is_only_consulted_after_a_golden_baseline(self) -> None:
        quiet = dict(child_state="not_run", cleanup_armed=False, cleanup_result="skipped_no_mutation",
                     warmup_state="not_reached")
        assert subject.compute_verdict(base(baseline_state="not_golden", **quiet)).code == "BASELINE_NOT_GOLDEN"
        assert subject.compute_verdict(base(login_state="too_slow", baseline_state="not_reached", **quiet)).code == "LOGIN_TOO_SLOW"

    def test_unconfirmed_cleanup_forces_non_go_and_is_the_primary_reason(self) -> None:
        verdict = subject.compute_verdict(base(cleanup_result="unconfirmed", legs=make_legs(L9=dict(status="failed", reason="TIMEOUT"))))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "CLEANUP_UNCONFIRMED")

    @pytest.mark.parametrize("result", ["skipped_no_mutation", "not_reached"])
    def test_an_armed_run_whose_cleanup_did_not_complete_is_a_failure(self, result: str) -> None:
        verdict = subject.compute_verdict(base(cleanup_result=result))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "CLEANUP_NOT_RUN")

    @pytest.mark.parametrize("result", ["confirmed", "not_needed"])
    def test_confirmed_or_not_needed_cleanup_passes(self, result: str) -> None:
        assert subject.compute_verdict(base(cleanup_result=result)).verdict == "GO"

    def test_unclean_ledger_and_failed_ledger_writes_are_failures(self) -> None:
        assert subject.compute_verdict(base(ledger_clean=False)).code == "LEDGER_INVALID"
        assert subject.compute_verdict(base(ledger_write_failed=True)).code == "LEDGER_WRITE_FAILED"

    @pytest.mark.parametrize(
        "state,code",
        [("exit_nonzero", "CHILD_EXIT_NONZERO"), ("deadline_killed", "CHILD_DEADLINE_KILLED"),
         ("spawn_failed", "CHILD_SPAWN_FAILED"), ("interrupted", "CHILD_INTERRUPTED"), ("not_run", "CHILD_NOT_RUN"),
         ("kill_unconfirmed", "CHILD_KILL_UNCONFIRMED")],
    )
    def test_a_child_exit_other_than_zero_is_non_go_even_with_an_otherwise_clean_ledger(self, state: str, code: str) -> None:
        verdict = subject.compute_verdict(base(child_state=state))
        assert (verdict.verdict, verdict.code) == ("NON_GO", code)

    def test_interrupt_and_unexpected_error_are_failures(self) -> None:
        assert subject.compute_verdict(base(interrupted="KeyboardInterrupt")).code == "INTERRUPTED"
        assert subject.compute_verdict(base(unexpected_error="ValueError")).code == "UNEXPECTED_ERROR"

    def test_login_states(self) -> None:
        assert subject.compute_verdict(base(login_state="failed")).verdict == "NON_GO"
        assert subject.compute_verdict(base(login_state="not_reached")).verdict == "NON_GO"
        slow = subject.compute_verdict(base(login_state="too_slow", child_state="not_run", cleanup_armed=False,
                                            cleanup_result="skipped_no_mutation", baseline_state="not_reached"))
        assert (slow.verdict, slow.code) == ("INCOMPLETE", "LOGIN_TOO_SLOW")

    def test_baseline_states(self) -> None:
        quiet = dict(child_state="not_run", cleanup_armed=False, cleanup_result="skipped_no_mutation")
        assert subject.compute_verdict(base(baseline_state="not_golden", **quiet)).verdict == "INCOMPLETE"
        assert subject.compute_verdict(base(baseline_state="read_failed", **quiet)).verdict == "NON_GO"
        assert subject.compute_verdict(base(baseline_state="not_reached", **quiet)).verdict == "NON_GO"

    def test_an_incomplete_stop_with_any_hard_failure_is_non_go(self) -> None:
        quiet = dict(baseline_state="not_golden", child_state="not_run", cleanup_armed=False,
                     cleanup_result="skipped_no_mutation")
        assert subject.compute_verdict(base(frontend_post_state="mismatch", **quiet)).verdict == "NON_GO"
        assert subject.compute_verdict(base(elapsed_since_deploy_seconds=99999.0, **quiet)).verdict == "NON_GO"

    def test_all_failed_criteria_are_listed_in_order_without_duplicates(self) -> None:
        verdict = subject.compute_verdict(base(ledger_clean=False, frontend_post_state="mismatch", child_state="exit_nonzero"))
        assert verdict.failed_criteria == ("CHILD_EXIT_NONZERO", "LEDGER_INVALID", "FRONTEND_POST_BINDING_FAILED")
        assert verdict.code == "CHILD_EXIT_NONZERO"

    def test_every_verdict_code_has_stop_text(self) -> None:
        source = Path(subject.__file__).read_text(encoding="utf-8")
        codes = set(re.findall(r'(?:hard|soft)\("([A-Z_]+)"\)', source)) | set(subject._CHILD_STATE_CODES.values())
        assert codes and codes <= set(subject.STOP_TEXT)

    def test_http_contradict_pass_is_a_hard_criterion_ranked_with_the_facts_criteria(self) -> None:
        legs = make_legs(L8=dict(status="failed", reason="HTTP_CONTRADICT_PASS"))
        verdict = subject.compute_verdict(base(legs=legs))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "HTTP_CONTRADICT_PASS")
        assert verdict.failed_criteria[:2] == ("HTTP_CONTRADICT_PASS", "LEG_FAILED")
        both = make_legs(L8=dict(status="failed", reason="HTTP_CONTRADICT_PASS"),
                         L4=dict(status="failed", reason="FACTS_CONTRADICT_PASS"),
                         L1=dict(status="failed", reason="FACTS_INCOMPLETE"))
        # the primary code follows the order incomplete facts, contradicting facts, contradicting http
        assert subject.compute_verdict(base(legs=both)).failed_criteria[:4] == (
            "FACTS_INCOMPLETE", "FACTS_CONTRADICT_PASS", "HTTP_CONTRADICT_PASS", "LEG_FAILED")

    def test_forbidden_http_status_in_a_leg_reaches_the_verdict_via_derive_legs(self) -> None:
        writer = SpecWriter()
        for i in range(8):
            if i == 4:
                writer.leg("L4", http=[{"method": "GET", "path": "/api/assets", "status": 429}])
            else:
                writer.leg(f"L{i}")
        writer.armed()
        writer.leg("L8")
        writer.leg("L9")
        parsed = parse(*writer.lines)
        assert parsed.clean
        legs = subject.derive_legs(parsed, CONTRACT, child_expected=True, fallback_reason="X")
        assert legs["L4"].reason == "FORBIDDEN_HTTP_STATUS"
        verdict = subject.compute_verdict(base(legs=legs))
        assert verdict.verdict == "NON_GO" and "LEG_FAILED" in verdict.failed_criteria


# ---------------------------------------------------------------------------
# Full orchestration: happy path and the evidence it writes
# ---------------------------------------------------------------------------
EXPECTED_TOP_LEVEL = {
    "schema", "verdict", "stop_reason", "start_utc", "end_utc", "baseline_commit", "tree_clean",
    "verifier_script_sha256", "oracle", "targets", "deploy_completed_utc", "deploy_completed_utc_source",
    "bound_seconds", "elapsed_since_deploy_seconds", "within_bound", "run_seconds", "login", "baseline",
    "warmup", "presence", "binding", "owner_attested", "legs", "routes", "child", "cleanup", "gate_map",
}


def server_paths(h: Harness, method: str) -> list[str]:
    return [c["url"][len(API):] for c in h.server.calls if c["method"] == method]


class TestHappyPath:
    def test_go_exit_zero_and_artifact_written(self, h: Harness) -> None:
        assert h.run() == 0
        art = h.artifact()
        assert art["schema"] == "wave10-5b-evidence-v1" and art["verdict"] == "GO"
        assert art["stop_reason"] == {"code": "NONE", "text": "all exit criterion 5b conditions held", "failed_criteria": []}
        assert art["gate_map"] == {"step_a_credited": False, "run_result": "all_items_passed"}
        h.assert_no_unexpected()

    def test_artifact_top_level_keys_are_the_allowlist(self, h: Harness) -> None:
        h.run()
        assert set(h.artifact()) == EXPECTED_TOP_LEVEL

    def test_run_identity_and_owner_attested_fields(self, h: Harness) -> None:
        import hashlib
        h.run()
        art = h.artifact()
        assert art["start_utc"] == RUN_START and art["baseline_commit"] == BASELINE and art["tree_clean"] is True
        assert art["verifier_script_sha256"] == hashlib.sha256(Path(subject.__file__).read_bytes()).hexdigest()
        assert art["oracle"] == {"catalogSha256": CATALOG_SHA, "holding_count": 3}
        assert art["targets"] == {"frontend": FRONTEND, "api": API}
        assert art["owner_attested"] == {"deploy_run_id": "1234567890", "source_head_sha": SOURCE_SHA}
        assert art["deploy_completed_utc"] == DEPLOY_COMPLETED and art["deploy_completed_utc_source"] == "owner_attested"
        assert art["bound_seconds"] == 7200 and art["within_bound"] is True
        assert 3600 < art["elapsed_since_deploy_seconds"] < 7200

    def test_login_baseline_and_presence_records(self, h: Harness) -> None:
        h.run()
        art = h.artifact()
        assert art["login"]["state"] == "ok" and art["login"]["within_timeout"] and art["login"]["within_guard"]
        assert art["login"]["duration_seconds"] == pytest.approx(80.0)
        assert art["baseline"] == {"state": "golden", "golden": True, "holdings_count": 3}
        assert art["warmup"] == {"probes": 1, "last_status": 200, "seconds": 0.0}
        assert art["presence"] == {"allow_active_presence": False, "another_session_active": False}

    def test_frontend_binding_record(self, h: Harness) -> None:
        h.run()
        front = h.artifact()["binding"]["frontend"]
        assert set(front) == {"build_id", "differs_from_pre_deploy", "stable_fetch_count", "root_html_sha256",
                              "asset_names_sha256", "asset_name_count", "post_state", "controls_rendered", "claim"}
        assert front["build_id"] == NEW_BUILD_ID and front["differs_from_pre_deploy"] is True
        assert front["stable_fetch_count"] == 3 and front["post_state"] == "match" and front["controls_rendered"] is True
        assert front["root_html_sha256"] == sha(next_html())
        assert front["claim"] == "observed served build; source commit not observable from the site"
        assert h.clock.sleeps[:2] == [5.0, 5.0]

    def test_backend_binding_record_hashes_the_files_and_ignores_extra_fields(self, h: Harness) -> None:
        pre_raw = attestation_json(PRE_ATTESTATION_AT, extra={"subscriptionId": "11111111-2222-3333-4444-555555555555",
                                                             "workspace": "law-x"}).encode()
        (h.tmp / "attestation-pre.json").write_bytes(pre_raw)
        assert h.run() == 0
        back = h.artifact()["binding"]["backend"]
        post_raw = (h.work_dir / "backend-attestation-post.json").read_bytes()
        assert back["pre"]["file_bytes"] == len(pre_raw) and back["pre"]["file_sha256"] == sha(pre_raw)
        assert back["post"]["file_bytes"] == len(post_raw) and back["post"]["file_sha256"] == sha(post_raw)
        assert back["pre"]["services"] == back["post"]["services"] == {
            "api-gateway": {"revision": "api-gateway--0000100", "digest": GATEWAY_DIGEST},
            "portfolio-service": {"revision": "portfolio-service--0000200", "digest": PORTFOLIO_DIGEST},
        }
        assert back["pre_equals_post"] is True and back["post_state"] == "valid_equal"
        assert back["claim"] == "owner-attested management-plane read"
        text = h.evidence_path.read_text(encoding="utf-8")
        assert "11111111-2222" not in text and "law-x" not in text and "subscriptionId" not in text

    def test_legs_and_routes_carry_the_evidence_levels(self, h: Harness) -> None:
        h.run()
        art = h.artifact()
        assert [leg["id"] for leg in art["legs"]] == list(CONTRACT.legs)
        by_id = {leg["id"]: leg for leg in art["legs"]}
        assert all(leg["status"] == "passed" and leg["reason_code"] == "OK" for leg in art["legs"])
        assert by_id["L4"]["route"] == "9.1" and by_id["L4"]["http"] == [{"method": "GET", "path": "/api/assets", "status": 200}]
        assert by_id["L4"]["facts"]["catalogParity"] is True
        assert by_id["L8"]["evidence_level"] == "production-browser; independent read production-API-only"
        assert by_id["L0"]["route"] is None and by_id["L0"]["evidence_level"] == "production-browser"
        assert set(art["routes"]) == {"9.1", "9.2", "9.3", "9.4", "9.5", "reset"}
        assert art["routes"]["9.4"] == {"evidence_level": "production-browser (network status and boolean)", "leg": "L5",
                                        "status": "passed", "ci_only_not_covered": "presence expiry, fail-open UI"}
        assert art["routes"]["reset"]["leg"] == "L9"

    def test_cleanup_and_child_records(self, h: Harness) -> None:
        h.run()
        art = h.artifact()
        assert art["cleanup"] == {"armed": True, "attempts": 0, "transport": "saved_jwt", "result": "not_needed",
                                  "golden_confirmed": True, "break_glass_used": False, "detail_code": None}
        assert art["child"] == {"state": "exit_zero", "exit_code": 0, "overall_deadline_seconds": 2700.0}

    def test_one_login_and_no_orchestrator_mutation_when_the_state_is_already_golden(self, h: Harness) -> None:
        h.run()
        assert server_paths(h, "POST") == ["/api/auth/login"]
        # baseline, one warm-up probe (the first golden ticker), then the cleanup observation
        assert server_paths(h, "GET") == ["/api/portfolio", "/api/market/prices?tickers=AAPL", "/api/portfolio"]
        assert server_paths(h, "PUT") == []
        login = h.server.calls_to("POST", "/api/auth/login")[0]
        assert login["timeout"] == 225.0

    def test_golden_file_exists_before_the_child_starts_and_matches_appendix_b(self, h: Harness) -> None:
        h.run()
        golden = json.loads(h.browser.golden_at_spawn)
        assert golden == {
            "schema": "wave10-5b-golden-v1", "catalogSha256": CATALOG_SHA,
            "holdings": [{"assetTicker": t, "quantity": q} for t, q in GOLDEN_ROWS],
            "activeTickers": ["AAPL", "MSFT", "TSLA"],
        }
        assert list(golden) == ["schema", "catalogSha256", "holdings", "activeTickers"]

    def test_golden_file_is_sorted_ascii_by_ticker_with_eight_decimal_quantities(self) -> None:
        shuffled = subject.parse_oracle_output(oracle_stdout((("TSLA", "5.00000000"), ("AAPL", "31.00000000"), ("MSFT", "22.00000000"))))
        doc = shuffled.as_file_document()
        assert [r["assetTicker"] for r in doc["holdings"]] == ["AAPL", "MSFT", "TSLA"]
        assert doc["activeTickers"] == ["AAPL", "MSFT", "TSLA"]
        assert all(re.fullmatch(r"\d+\.\d{8}", r["quantity"]) for r in doc["holdings"])

    def test_the_child_gets_the_allowlisted_environment_and_static_argv(self, h: Harness) -> None:
        h.run()
        (call,) = h.browser.spawn_calls
        env = call["env"]
        assert env["STEP_B_5B_TOKEN"] == TOKEN and env["STEP_B_5B_BASELINE_VERSION"] == "5"
        assert env["STEP_B_5B_WORK_DIR"] == str(h.work_dir) and env["STEP_B_5B_GOLDEN_FILE"] == str(h.work_dir / "golden.json")
        assert env["STEP_B_5B_ALLOW_ACTIVE_PRESENCE"] == "0"
        for name in ("DEBUG", "PWDEBUG", "NODE_OPTIONS", "INTERNAL_API_KEY", "WAVE9_STEP_A_PASSWORD"):
            assert name not in env
        assert all(PASSWORD not in v for v in env.values())
        assert call["argv"] == subject.build_child_argv() and call["cwd"] == h.frontend_dir
        assert call["log_path"] == h.work_dir / "child.log"

    def test_the_armed_cleanup_marker_is_on_disk_before_the_child_starts(self, h: Harness) -> None:
        h.run()
        first = [json.loads(line) for line in h.browser.ledger_at_spawn.splitlines()]
        assert [(e["event"], e.get("result")) for e in first] == [("cleanup", "cleanup_armed"), ("child_started", None)]
        assert first[0]["transport"] == "none" and first[0]["attempt"] == 0

    def test_the_final_ledger_is_clean_and_ordered(self, h: Harness) -> None:
        h.run()
        events = h.ledger_events()
        orchestrator = [e["event"] for e in events if e["src"] == "orchestrator"]
        assert orchestrator[:2] == ["cleanup", "child_started"] and "child_done" in orchestrator
        assert orchestrator[-1] == "cleanup" and events[-1]["result"] == "not_needed"
        assert subject.parse_ledger((h.work_dir / "ledger.jsonl").read_bytes(), CONTRACT).clean

    def test_the_post_attestation_prompt_prints_the_expected_path_and_waits_once(self, h: Harness) -> None:
        h.run()
        assert len(h.input_prompts) == 1
        assert any(str(h.work_dir / "backend-attestation-post.json") in line for line in h.out)

    def test_progress_output_names_each_phase_and_prints_size_and_hash(self, h: Harness) -> None:
        h.run()
        text = "\n".join(h.out)
        for marker in ("P0 ", "P1 ", "P2 ", "P3 ", "P3b ", "P4 ", "P5 ", "P6 ", "Artifact written", "SHA-256", "Verdict: GO"):
            assert marker in text, marker
        digest = sha(h.evidence_path.read_bytes())
        assert digest in text and f"{h.evidence_path.stat().st_size} bytes" in text

    def test_password_key_and_token_never_appear_in_any_output_or_file(self, h: Harness) -> None:
        h.getpass_answers = [PASSWORD, INTERNAL_KEY]
        assert h.run(enable_internal_cleanup=True) == 0
        blob = h.all_output_text()
        for secret in (PASSWORD, INTERNAL_KEY, TOKEN):
            assert secret not in blob
        env_values = " ".join(h.browser.spawn_calls[0]["env"].values())
        assert PASSWORD not in env_values and INTERNAL_KEY not in env_values

    def test_no_raw_password_or_key_survives_in_the_scan_registry_after_the_run(self, h: Harness, monkeypatch: pytest.MonkeyPatch) -> None:
        created: list[subject.SecretRegistry] = []

        class Recording(subject.SecretRegistry):
            def __init__(self) -> None:
                super().__init__()
                created.append(self)

        monkeypatch.setattr(subject, "SecretRegistry", Recording)
        h.getpass_answers = [PASSWORD, INTERNAL_KEY]
        assert h.run(enable_internal_cleanup=True) == 0
        (registry,) = created
        assert TOKEN in registry.values() and PASSWORD not in registry.values() and INTERNAL_KEY not in registry.values()
        assert PASSWORD not in repr(vars(registry)) and INTERNAL_KEY not in repr(vars(registry))
        assert registry.contains_owner_secret(f"leak {PASSWORD}") and registry.contains_owner_secret(f"leak {INTERNAL_KEY}")

    def test_the_password_only_ever_travels_in_the_login_body(self, h: Harness) -> None:
        h.run()
        carriers = [c for c in h.server.calls if PASSWORD in json.dumps(c, default=str)]
        assert [(c["method"], c["url"]) for c in carriers] == [("POST", API + "/api/auth/login")]

    def test_prompt_count_without_and_with_the_break_glass_flag(self, h: Harness) -> None:
        h.run()
        assert len(h.prompts) == 1
        h2 = Harness(h.tmp / "second")  # separate directory for a clean evidence path
        h2.getpass_answers = [PASSWORD, ""]
        assert h2.run(enable_internal_cleanup=True) == 0
        assert len(h2.prompts) == 2 and "break-glass" in h2.prompts[1]


# ---------------------------------------------------------------------------
# Safe aborts before any mutation: INCOMPLETE, never a pass
# ---------------------------------------------------------------------------
class TestIncompleteRuns:
    def test_baseline_not_golden_is_incomplete_with_no_mutation_and_no_child(self, h: Harness) -> None:
        h.server.rows = DIRTY_ROWS
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("INCOMPLETE", "BASELINE_NOT_GOLDEN")
        assert art["gate_map"]["run_result"] == "not_all_items_passed"
        assert h.browser.spawn_calls == [] and server_paths(h, "PUT") == []
        assert art["cleanup"]["result"] == "skipped_no_mutation" and art["cleanup"]["armed"] is False
        assert art["baseline"] == {"state": "not_golden", "golden": False, "holdings_count": 3}
        assert {leg["status"] for leg in art["legs"]} == {"skipped"}
        assert {leg["reason_code"] for leg in art["legs"]} == {"SKIPPED_BASELINE_NOT_GOLDEN"}
        assert h.input_prompts == [] and art["binding"]["backend"]["post_state"] == "skipped"
        assert h.work_dir.is_dir()  # the artifact and work dir exist; state is restored with --cleanup-only

    def test_baseline_read_that_fails_identity_is_a_non_go_not_a_pass(self, h: Harness) -> None:
        h.http = HttpRecorder([(200, {"token": TOKEN}), (200, [])])
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "BASELINE_READ_FAILED")
        assert h.browser.spawn_calls == []

    def test_visitor_present_yields_incomplete_and_skips_both_mutation_legs(self, h: Harness) -> None:
        h.browser.lines = visitor_ledger()
        h.browser.server_effect = "untouched"
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("INCOMPLETE", "VISITOR_PRESENT")
        by_id = {leg["id"]: leg for leg in art["legs"]}
        assert by_id["L8"]["status"] == by_id["L9"]["status"] == "skipped"
        assert by_id["L8"]["facts"] == {"mutationSkippedReason": "VISITOR_PRESENT"}
        assert art["presence"]["another_session_active"] is True
        assert server_paths(h, "PUT") == [] and art["cleanup"]["result"] == "not_needed"
        assert h.input_prompts == []  # no post attestation is requested for a run that cannot be GO
        h.assert_no_unexpected()

    def test_the_allow_flag_is_passed_to_the_child_and_recorded(self, h: Harness) -> None:
        h.run(allow_active_presence=True)
        assert h.browser.spawn_calls[0]["env"]["STEP_B_5B_ALLOW_ACTIVE_PRESENCE"] == "1"
        assert h.artifact()["presence"]["allow_active_presence"] is True

    def test_mutating_with_a_visitor_and_no_flag_is_rejected_even_if_the_child_did_it(self, h: Harness) -> None:
        writer = SpecWriter()
        for i in range(8):
            facts = dict(LEG_FACTS[f"L{i}"])
            if i == 5:
                facts["anotherSessionActive"] = True
            writer.leg(f"L{i}", facts=facts)
        writer.armed()
        writer.leg("L8")
        writer.leg("L9")
        h.browser.lines = writer.lines
        assert h.run() == 1
        assert h.artifact()["stop_reason"]["code"] == "PRESENCE_POLICY_VIOLATION"

    def test_slow_login_stops_before_any_read_or_mutation(self, h: Harness) -> None:
        h.server.login_seconds = 170.0
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("INCOMPLETE", "LOGIN_TOO_SLOW")
        assert art["login"]["state"] == "too_slow" and art["login"]["within_guard"] is False
        assert server_paths(h, "GET") == [] and server_paths(h, "PUT") == [] and h.browser.spawn_calls == []
        assert art["cleanup"]["result"] == "skipped_no_mutation"

    def test_login_failure_is_a_non_go_with_no_token_and_no_cleanup(self, h: Harness) -> None:
        h.server.login_status = 401
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "LOGIN_FAILED")
        assert art["login"]["failure_code"] == "LOGIN_STATUS_NOT_200" and art["login"]["status"] == 401
        assert server_paths(h, "GET") == [] and h.browser.spawn_calls == []
        assert art["cleanup"]["armed"] is False


# ---------------------------------------------------------------------------
# Failure paths: every one is non-GO, exits 1, writes evidence, and cleans up
# ---------------------------------------------------------------------------
class TestFailurePaths:
    def test_child_exit_non_zero_with_an_otherwise_clean_ledger_is_non_go(self, h: Harness) -> None:
        h.browser.exit_code = 1
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "CHILD_EXIT_NONZERO")
        assert {leg["status"] for leg in art["legs"]} == {"passed"}  # the ledger itself was clean
        assert art["cleanup"]["result"] == "not_needed" and art["gate_map"]["run_result"] == "not_all_items_passed"

    def test_child_killed_at_the_overall_deadline_still_runs_cleanup(self, h: Harness) -> None:
        h.browser.never_exit = True
        h.browser.lines = []
        h.browser.server_effect = "dirty"
        assert h.run() == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "CHILD_DEADLINE_KILLED" and art["child"]["state"] == "deadline_killed"
        assert h.browser.handles[0].killed and art["child"]["exit_code"] == -9
        # the deadline is the JWT's age: the kill lands 2,700 s after the login call started; the 10 s the
        # run spent before that (two spaced root fetches) show up only in the informational run_seconds
        assert h.browser.handles[0].killed_at == pytest.approx(h.server.login_called_at + subject.OVERALL_DEADLINE_SECONDS, abs=1.5)
        assert art["run_seconds"] == pytest.approx(subject.OVERALL_DEADLINE_SECONDS + 10.0, abs=1.5)
        assert art["cleanup"]["result"] == "confirmed" and art["cleanup"]["attempts"] == 1
        assert server_paths(h, "PUT") == ["/api/portfolio/demo-reset"]
        assert all(leg["status"] == "missing" for leg in art["legs"])

    def test_spawn_failure_is_recorded_and_the_armed_run_is_still_cleaned_up(self, h: Harness) -> None:
        h.browser.spawn_error = FileNotFoundError("node")
        assert h.run() == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "CHILD_SPAWN_FAILED"
        assert art["cleanup"]["armed"] is True and art["cleanup"]["result"] == "not_needed"

    def test_a_missing_leg_is_a_failure(self, h: Harness) -> None:
        h.browser.lines = [line for line in go_ledger() if line.get("leg") != "L6"]
        # renumber so only the missing leg (not a seq gap) is under test
        for index, line in enumerate(h.browser.lines, start=1):
            line["seq"] = index
        assert h.run() == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "LEG_FAILED"
        assert {leg["id"]: leg["status"] for leg in art["legs"]}["L6"] == "missing"
        assert art["routes"]["9.3"]["status"] == "missing"

    def test_a_ledger_line_outside_the_vocabulary_is_a_failure(self, h: Harness) -> None:
        lines = go_ledger()
        lines[4]["facts"]["surprise"] = True
        h.browser.lines = lines
        assert h.run() == 1
        art = h.artifact()
        assert "LEDGER_INVALID" in art["stop_reason"]["failed_criteria"]
        assert {leg["id"]: leg["reason_code"] for leg in art["legs"]}["L4"] == "LEDGER_INVALID"

    def test_no_armed_record_before_l8_is_a_failure(self, h: Harness) -> None:
        h.browser.lines = [line for line in go_ledger() if line["event"] != "armed"]
        for index, line in enumerate(h.browser.lines, start=1):
            line["seq"] = index
        assert h.run() == 1
        assert h.artifact()["stop_reason"]["code"] == "ARMED_MISSING"

    def test_a_forbidden_status_on_a_passed_leg_is_a_failure(self, h: Harness) -> None:
        lines = go_ledger()
        lines[6]["http"] = [{"method": "GET", "path": "/api/market/prices", "status": 429}]
        h.browser.lines = lines
        assert h.run() == 1
        assert {leg["id"]: leg["reason_code"] for leg in h.artifact()["legs"]}["L6"] == "FORBIDDEN_HTTP_STATUS"

    def test_dirty_state_after_a_dead_child_is_reset_by_the_independent_cleanup(self, h: Harness) -> None:
        h.browser.lines = [line for line in go_ledger() if line.get("leg") != "L9"]
        h.browser.server_effect = "dirty"
        assert h.run() == 1
        art = h.artifact()
        assert art["cleanup"]["result"] == "confirmed" and art["cleanup"]["attempts"] == 1
        (reset,) = h.server.calls_to("PUT", "/api/portfolio/demo-reset")
        assert reset["body"] == {"expectedVersion": 6} and "X-Internal-Api-Key" not in reset["headers"]
        assert art["stop_reason"]["code"] == "LEG_FAILED"

    def test_unconfirmed_cleanup_forces_non_go(self, h: Harness) -> None:
        h.browser.server_effect = "dirty"
        h.server.reset_status = 500
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "CLEANUP_UNCONFIRMED")
        assert art["cleanup"]["result"] == "unconfirmed" and art["cleanup"]["golden_confirmed"] is False
        assert art["gate_map"]["run_result"] == "not_all_items_passed"

    def test_break_glass_end_to_end_uses_the_prompted_key_and_never_records_it(self, h: Harness) -> None:
        h.getpass_answers = [PASSWORD, INTERNAL_KEY]
        h.browser.lines = [line for line in go_ledger() if line.get("leg") != "L9"]
        h.browser.server_effect = "dirty"
        h.server.reset_status = 500
        assert h.run(enable_internal_cleanup=True) == 1
        art = h.artifact()
        assert art["cleanup"]["break_glass_used"] is True and art["cleanup"]["transport"] == "saved_jwt+internal_break_glass"
        assert art["cleanup"]["result"] == "confirmed"
        (internal,) = h.server.calls_to("PUT", "/api/internal/portfolio/demo-reset")
        assert internal["headers"] == {"X-Internal-Api-Key": INTERNAL_KEY}
        assert INTERNAL_KEY not in h.all_output_text() and INTERNAL_KEY not in " ".join(h.browser.spawn_calls[0]["env"].values())

    def test_blank_key_answer_disables_break_glass(self, h: Harness) -> None:
        h.getpass_answers = [PASSWORD, ""]
        h.browser.server_effect = "dirty"
        h.server.reset_status = 500
        assert h.run(enable_internal_cleanup=True) == 1
        assert h.artifact()["cleanup"]["break_glass_used"] is False
        assert h.server.calls_to("PUT", "/api/internal/portfolio/demo-reset") == []

    def test_an_unexpected_exception_after_login_is_a_non_go_with_evidence(self, h: Harness) -> None:
        h.fs = FlakyFs(fail_ledger_appends=True)
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "UNEXPECTED_ERROR")
        assert h.browser.spawn_calls == [] and art["cleanup"]["armed"] is False  # write-ahead failed: no child
        assert art["stop_reason"]["exception_types"] == ["OSError"]  # the class name only ...
        assert "disk full" not in h.evidence_path.read_text(encoding="utf-8")  # ... never the message

    def test_keyboard_interrupt_during_the_child_kills_it_runs_cleanup_writes_evidence_then_reraises(self, h: Harness) -> None:
        h.browser.interrupt = True
        h.browser.server_effect = "dirty"
        with pytest.raises(KeyboardInterrupt):
            h.run()
        assert h.browser.handles[0].killed
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "INTERRUPTED")
        assert art["cleanup"]["result"] == "confirmed" and server_paths(h, "PUT") == ["/api/portfolio/demo-reset"]
        assert art["child"]["state"] == "interrupted"
        assert art["stop_reason"]["exception_types"] == ["KeyboardInterrupt"]
        assert subject.parse_ledger((h.work_dir / "ledger.jsonl").read_bytes(), CONTRACT).clean

    def test_main_turns_a_post_login_interrupt_into_exit_one(self, h: Harness) -> None:
        h.browser.interrupt = True
        assert h.main() == 1
        assert h.artifact()["stop_reason"]["code"] == "INTERRUPTED"
        assert any("Interrupted after the login" in line for line in h.err)

    def test_an_interrupt_during_cleanup_itself_still_writes_evidence(self, h: Harness) -> None:
        h.browser.server_effect = "dirty"
        real = h.server

        def interrupting(method: str, url: str, headers: dict[str, str], json_body: Any = None, timeout: float = 30.0) -> Any:
            if method == "PUT" and url.endswith("/api/portfolio/demo-reset"):
                raise KeyboardInterrupt
            return real(method, url, headers, json_body, timeout)

        h.http = interrupting
        with pytest.raises(KeyboardInterrupt):
            h.run()
        art = h.artifact()
        assert art["cleanup"]["result"] == "unconfirmed" and art["cleanup"]["detail_code"] == "CLEANUP_INTERRUPTED"
        assert art["verdict"] == "NON_GO"

    def test_an_unexpected_call_is_never_swallowed(self, h: Harness) -> None:
        h.http = HttpRecorder([(200, {"token": TOKEN})])  # baseline read is unscripted
        with pytest.raises(_RecorderExhausted):
            h.run()
        assert h.http.unexpected  # type: ignore[attr-defined]
        assert h.artifact()["verdict"] == "NON_GO"  # evidence was still written before the sentinel re-raised


# ---------------------------------------------------------------------------
# P6: post binding, the owner's post attestation, and the time bound
# ---------------------------------------------------------------------------
class TestPostBinding:
    def test_mismatched_post_revision_is_non_go(self, h: Harness) -> None:
        h.post_attestation = lambda path, clock: path.write_text(
            attestation_json(subject.utc_z(clock.now - datetime.timedelta(seconds=5)), gateway_revision="api-gateway--0000999"),
            encoding="utf-8")
        assert h.run() == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "BACKEND_BINDING_FAILED"
        assert art["binding"]["backend"]["post_state"] == "mismatch" and art["binding"]["backend"]["pre_equals_post"] is False

    def test_mismatched_post_digest_is_non_go(self, h: Harness) -> None:
        h.post_attestation = lambda path, clock: path.write_text(
            attestation_json(subject.utc_z(clock.now - datetime.timedelta(seconds=5)), portfolio_digest="sha256:" + "e" * 64),
            encoding="utf-8")
        assert h.run() == 1
        assert h.artifact()["binding"]["backend"]["post_state"] == "mismatch"

    def test_missing_post_attestation_file_is_non_go(self, h: Harness) -> None:
        h.post_attestation = None
        assert h.run() == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "BACKEND_BINDING_FAILED" and art["binding"]["backend"]["post_state"] == "missing"

    def test_invalid_post_attestation_is_non_go(self, h: Harness) -> None:
        h.post_attestation = lambda path, clock: path.write_text("{}", encoding="utf-8")
        assert h.run() == 1
        assert h.artifact()["binding"]["backend"]["post_state"] == "invalid"

    def test_post_attestation_read_before_the_child_ended_is_invalid(self, h: Harness) -> None:
        h.post_attestation = lambda path, clock: path.write_text(attestation_json("2026-09-20T12:00:30Z"), encoding="utf-8")
        assert h.run() == 1
        assert h.artifact()["binding"]["backend"]["post_state"] == "invalid"

    def test_post_attestation_from_the_future_is_invalid(self, h: Harness) -> None:
        h.post_attestation = lambda path, clock: path.write_text(
            attestation_json(subject.utc_z(clock.now + datetime.timedelta(hours=1))), encoding="utf-8")
        assert h.run() == 1
        assert h.artifact()["binding"]["backend"]["post_state"] == "invalid"

    def test_a_frontend_that_changed_during_the_run_is_non_go_and_needs_no_owner_action(self, h: Harness) -> None:
        h.fetch = FetchScript([html_ok(), html_ok(), html_ok(), html_ok("Swapped111111111111x")])
        assert h.run() == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "FRONTEND_POST_BINDING_FAILED"
        assert art["binding"]["frontend"]["post_state"] == "mismatch" and h.input_prompts == []

    def test_a_post_fetch_failure_is_non_go(self, h: Harness) -> None:
        h.fetch = FetchScript([html_ok(), html_ok(), html_ok(), TimeoutError("t")])
        assert h.run() == 1
        assert h.artifact()["binding"]["frontend"]["post_state"] == "fetch_failed"

    def test_time_bound_exceeded_by_a_slow_owner_is_non_go_but_never_a_deadline_failure(self, h: Harness) -> None:
        h.owner_delay = 3000.0  # pushes completion past the 7200 s bound (the wall clock)
        assert h.run() == 1
        art = h.artifact()
        assert art["within_bound"] is False and art["elapsed_since_deploy_seconds"] > 7200
        assert art["stop_reason"]["code"] == "BOUND_EXCEEDED"
        assert "DEADLINE_EXCEEDED" not in art["stop_reason"]["failed_criteria"]  # the owner wait is not the JWT's age
        assert art["run_seconds"] > subject.OVERALL_DEADLINE_SECONDS  # ... although the wall clock did pass 2,700 s

    def test_a_run_already_past_the_bound_does_not_ask_the_owner(self, h: Harness) -> None:
        assert h.run(bound_seconds=300) == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "BOUND_EXCEEDED" and h.input_prompts == []
        assert art["binding"]["backend"]["post_state"] == "skipped" and art["binding"]["backend"]["post"] is None

    def test_an_owner_wait_after_cleanup_can_only_trip_the_bound_never_the_deadline(self, h: Harness) -> None:
        """Was test_overall_deadline_counts_the_owner_wait. The deadline is the JWT's age (login start to
        the end of cleanup); the blocking post-attestation wait comes after cleanup and needs no token."""
        h.owner_delay = 2200.0  # a long wait that a run-start deadline would have failed
        assert h.run(bound_seconds=20000) == 0  # inside the wall-clock bound: nothing trips
        assert h.artifact()["verdict"] == "GO" and h.artifact()["run_seconds"] > subject.OVERALL_DEADLINE_SECONDS

    def test_a_long_password_prompt_trips_nothing(self, h: Harness) -> None:
        h.getpass_delay = 3000.0  # the owner takes 50 minutes to type the password
        assert h.run(bound_seconds=20000) == 0
        art = h.artifact()
        assert art["verdict"] == "GO" and art["run_seconds"] > subject.OVERALL_DEADLINE_SECONDS

    def test_a_long_password_prompt_can_only_trip_the_wall_clock_bound(self, h: Harness) -> None:
        h.getpass_delay = 3000.0
        assert h.run() == 1  # bound_seconds=7200 counts the prompt wait on the wall clock
        art = h.artifact()
        assert art["stop_reason"]["code"] == "BOUND_EXCEEDED"
        assert "DEADLINE_EXCEEDED" not in art["stop_reason"]["failed_criteria"]

    def test_the_child_deadline_is_anchored_at_the_login_start_not_the_run_start(self, h: Harness) -> None:
        h.getpass_delay = 1000.0  # 1,000 s at the prompt before the login begins
        h.browser.never_exit = True
        h.browser.lines = []
        assert h.run(bound_seconds=20000) == 1
        child = h.browser.handles[0]
        assert child.killed_at == pytest.approx(h.server.login_called_at + subject.OVERALL_DEADLINE_SECONDS, abs=1.5)
        # a run-start anchor would have killed the child 1,000 s earlier
        assert child.killed_at > h.server.login_called_at + subject.OVERALL_DEADLINE_SECONDS - 5.0
        assert h.artifact()["stop_reason"]["code"] == "CHILD_DEADLINE_KILLED"

    def test_a_run_whose_login_to_cleanup_span_passes_the_deadline_is_a_deadline_failure(
            self, h: Harness) -> None:
        """The child exits in time (login 80 s + 2,600 s), but the cleanup observation is slow: the JWT is
        2,780 s old when cleanup ends, so the run is non-GO although the child itself was never killed."""
        h.browser.runtime = 2600.0
        real = h.server

        def slow_cleanup_read(method: str, url: str, headers: dict[str, str], json_body: Any = None,
                              timeout: float = 30.0) -> Any:
            if method == "GET" and url.endswith("/api/portfolio") and h.browser.spawn_calls:
                h.clock.advance(100.0)
            return real(method, url, headers, json_body, timeout)

        h.http = slow_cleanup_read
        assert h.run(bound_seconds=20000) == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "DEADLINE_EXCEEDED" and art["child"]["state"] == "exit_zero"
        assert h.browser.handles[0].killed is False

    def test_interrupt_while_waiting_for_the_owner_still_writes_evidence(self, h: Harness) -> None:
        def interrupted(prompt: str) -> str:
            raise KeyboardInterrupt
        h.input_fn = interrupted  # type: ignore[method-assign]
        with pytest.raises(KeyboardInterrupt):
            h.run()
        art = h.artifact()
        assert art["stop_reason"]["code"] == "INTERRUPTED" and art["cleanup"]["result"] == "not_needed"


# ---------------------------------------------------------------------------
# Sanitizer and artifact writing end to end
# ---------------------------------------------------------------------------
class TestArtifactEndToEnd:
    def test_an_identifier_that_would_trip_the_scan_is_refused_at_p0_and_no_longer_after_the_run(self, h: Harness) -> None:
        assert h.run(deploy_run_id="evil.example.com") == 2
        assert any("[OWNER_ATTESTED_ID_SCAN_TRIPPED]" in line for line in h.err)
        assert h.server.calls == [] and h.prompts == [] and not h.evidence_path.exists() and not h.work_dir.exists()

    def test_a_registered_secret_that_leaks_into_the_artifact_trips_the_final_scan_backstop(self, h: Harness) -> None:
        # the password is deliberately reused as the owner-attested id: shape-valid and scan-clean at P0
        # (nothing yet knows it is the password), so only the final scan can catch it
        h.getpass_answers = ["Leak-Value-Secret-42"]
        assert h.run(deploy_run_id="Leak-Value-Secret-42") == 1
        text = h.evidence_path.read_text(encoding="utf-8")
        stub = json.loads(text)
        assert "Leak-Value-Secret-42" not in text
        assert stub["verdict"] == "NON_GO" and stub["stop_reason"]["code"] == "SANITIZER_TRIPPED"
        assert stub["gate_map"] == {"step_a_credited": False, "run_result": "not_all_items_passed"}
        assert "legs" not in stub and "binding" not in stub
        assert any("SANITIZER TRIPPED" in line and "secret_exact" in line for line in h.err)
        assert any("Verdict: NON_GO" in line for line in h.out)

    def test_if_the_target_path_appears_mid_run_the_copy_goes_to_the_work_directory(self, h: Harness) -> None:
        original_input = h.input_fn

        def create_then_answer(prompt: str) -> str:
            h.evidence_path.write_text("someone else's file", encoding="utf-8")
            return original_input(prompt)

        h.input_fn = create_then_answer  # type: ignore[method-assign]
        assert h.run() == 1
        assert h.evidence_path.read_text(encoding="utf-8") == "someone else's file"
        fallback = json.loads((h.work_dir / "evidence.fallback.json").read_text(encoding="utf-8"))
        assert fallback["verdict"] == "NON_GO"  # an unsealed copy is never a GO, even though the run itself was clean
        assert fallback["stop_reason"]["code"] == "ARTIFACT_PATH_UNAVAILABLE"
        assert fallback["gate_map"]["run_result"] == "not_all_items_passed"
        assert any("ERROR" in line for line in h.err)

    def test_artifact_bytes_are_utf8_lf_without_bom(self, h: Harness) -> None:
        h.run()
        raw = h.evidence_path.read_bytes()
        assert not raw.startswith(b"\xef\xbb\xbf") and b"\r" not in raw and raw.endswith(b"\n")
        raw.decode("utf-8")


# ---------------------------------------------------------------------------
# P0/P1 precondition failures: exit 2, nothing consumed, no evidence
# ---------------------------------------------------------------------------
def _cmd(h: Harness, key: str, returncode: int, stdout: str = "") -> None:
    h.cmd.results[key] = subject.CmdResult(returncode, stdout, "")


def _oracle_doc(**changes: Any) -> str:
    doc = json.loads(oracle_stdout())
    doc.update(changes)
    return json.dumps(doc)


def _write_pre(h: Harness, read_at: str) -> None:
    (h.tmp / "attestation-pre.json").write_text(attestation_json(read_at), encoding="utf-8")


PRECONDITION_CASES: list[tuple[str, Callable[[Harness], Optional[dict[str, Any]]]]] = [
    ("ORIGIN_NOT_ALLOWED", lambda h: dict(frontend_origin="https://evil.example.com")),
    ("ORIGIN_NOT_ALLOWED", lambda h: dict(api_origin="http://api.vibhanshu-ai-portfolio.dev")),
    ("EVIDENCE_PATH_NOT_ABSOLUTE", lambda h: dict(evidence_output=Path("rel.json"))),
    ("EVIDENCE_PATH_INSIDE_REPO", lambda h: dict(evidence_output=subject.REPO / "x-evidence.json")),
    ("EVIDENCE_PARENT_MISSING", lambda h: dict(evidence_output=h.tmp / "nodir" / "e.json")),
    ("EVIDENCE_PATH_EXISTS", lambda h: h.evidence_path.write_text("keep", encoding="utf-8")),
    ("WORK_DIR_EXISTS", lambda h: h.work_dir.mkdir()),
    ("BOUND_SECONDS_INVALID", lambda h: dict(bound_seconds=100)),
    ("BASELINE_COMMIT_INVALID", lambda h: dict(baseline_commit="abc")),
    ("BASELINE_COMMIT_MISMATCH", lambda h: _cmd(h, "git-head", 0, "f" * 40 + "\n")),
    ("BASELINE_COMMIT_MISMATCH", lambda h: _cmd(h, "git-head", 128)),
    ("GIT_STATUS_FAILED", lambda h: _cmd(h, "git-status", 1)),
    ("TREE_NOT_CLEAN", lambda h: _cmd(h, "git-status", 0, " M scripts/x.py\n")),
    ("ORACLE_FAILED", lambda h: _cmd(h, "oracle", 1)),
    ("ORACLE_OUTPUT_INVALID", lambda h: _cmd(h, "oracle", 0, "not json")),
    ("ORACLE_OUTPUT_INVALID", lambda h: _cmd(h, "oracle", 0, _oracle_doc(wireHoldings=[]))),
    ("ORACLE_OUTPUT_INVALID", lambda h: _cmd(h, "oracle", 0, _oracle_doc(metadata={"catalogSha256": "xyz"}))),
    ("ORACLE_OUTPUT_INVALID", lambda h: _cmd(h, "oracle", 0, _oracle_doc(wireHoldings=[{"assetTicker": "AAPL", "quantity": "31"}]))),
    ("ORACLE_OUTPUT_INVALID", lambda h: _cmd(h, "oracle", 0, _oracle_doc(metadata={"catalogSha256": CATALOG_SHA, "activeEntryCount": 99}))),
    ("NODE_MISSING", lambda h: _cmd(h, "node", 127)),
    ("PLAYWRIGHT_CLI_MISSING", lambda h: (h.frontend_dir / subject.PLAYWRIGHT_CLI_REL).unlink()),
    ("STDIN_NOT_A_TTY", lambda h: setattr(h, "stdin_tty", False)),
    ("EVIDENCE_PATH_UNC_OR_DEVICE", lambda h: dict(evidence_output=Path(r"\\server\share\e.json"))),
    ("PLAYWRIGHT_PACKAGE_MISSING", lambda h: _cmd(h, "playwright", 1)),
    ("BROWSER_EXECUTABLE_MISSING", lambda h: _cmd(h, "browser", 1)),
    ("BROWSER_EXECUTABLE_MISSING", lambda h: _cmd(h, "browser", 127)),
    ("BROWSER_EXECUTABLE_MISSING", lambda h: _cmd(h, "browser", -1)),  # the probe timed out
    ("ACTION_TIMEOUT_INVALID", lambda h: dict(action_timeout_ms=999)),
    ("ACTION_TIMEOUT_INVALID", lambda h: dict(action_timeout_ms=600001)),
    ("ACTION_TIMEOUT_INVALID", lambda h: dict(action_timeout_ms=0)),
    ("PLAYWRIGHT_CONFIG_MISSING", lambda h: (h.frontend_dir / subject.PLAYWRIGHT_CONFIG_REL).unlink()),
    ("ATTESTATION_PRE_INVALID", lambda h: (h.tmp / "attestation-pre.json").write_text("{}", encoding="utf-8")),
    ("ATTESTATION_PRE_INVALID", lambda h: dict(backend_attestation_pre=h.tmp / "absent.json")),
    ("ATTESTATION_PRE_TIME_ORDER", lambda h: _write_pre(h, "2026-09-20T10:00:00Z")),
    ("ATTESTATION_PRE_TIME_ORDER", lambda h: _write_pre(h, DEPLOY_COMPLETED)),
    ("ATTESTATION_PRE_TIME_ORDER", lambda h: _write_pre(h, RUN_START)),
    ("ATTESTATION_PRE_TIME_ORDER", lambda h: _write_pre(h, "2026-09-20T12:30:00Z")),
    ("RUN_BEFORE_DEPLOY_COMPLETED", lambda h: dict(deploy_completed_utc=dt("2026-09-20T13:00:00Z"))),
    ("LEDGER_CONTRACT_MISSING", lambda h: dict(contract_path=h.tmp / "absent-contract.json")),
    ("FRONTEND_BUILD_ID_UNCHANGED", lambda h: setattr(h, "fetch", FetchScript([html_ok(OLD_BUILD_ID)] * 3))),
    ("FRONTEND_BUILD_UNSTABLE", lambda h: setattr(h, "fetch", FetchScript([html_ok(), html_ok("Other22222222222222d"), html_ok()]))),
    ("FRONTEND_NOT_HTML", lambda h: setattr(h, "fetch", FetchScript([(200, "application/json", b"{}")]))),
    ("FRONTEND_NO_NEXT_MARKER", lambda h: setattr(h, "fetch", FetchScript([(200, "text/html", b"<html>404</html>")]))),
    ("PASSWORD_EMPTY", lambda h: setattr(h, "getpass_answers", [""])),
]


class TestPreconditionFailures:
    @pytest.mark.parametrize("index", range(len(PRECONDITION_CASES)),
                             ids=[f"{i}-{c}" for i, (c, _) in enumerate(PRECONDITION_CASES)])
    def test_exit_two_and_nothing_consumed(self, h: Harness, index: int) -> None:
        code, setup = PRECONDITION_CASES[index]
        over = setup(h) if callable(setup) else None
        preexisting_evidence = h.evidence_path.exists()
        assert h.run(**(over if isinstance(over, dict) else {})) == 2
        assert any(f"[{code}]" in line for line in h.err), (code, h.err)
        assert h.server.calls == [] and h.browser.spawn_calls == []  # no login, no child
        assert h.evidence_path.exists() == preexisting_evidence  # no evidence created
        if code == "EVIDENCE_PATH_EXISTS":
            assert h.evidence_path.read_text(encoding="utf-8") == "keep"
        if code != "WORK_DIR_EXISTS":
            assert not h.work_dir.exists()
        else:
            assert list(h.work_dir.iterdir()) == []
        if code != "PASSWORD_EMPTY":
            assert h.prompts == []  # P0/P1 failures never ask the owner for a password
        h.assert_no_unexpected()

    def test_the_frontend_binding_is_checked_before_the_password_prompt(self, h: Harness) -> None:
        h.fetch = FetchScript([html_ok(OLD_BUILD_ID)] * 3)
        assert h.run() == 2 and h.prompts == []
        assert h.clock.sleeps == [5.0, 5.0]

    def test_interrupt_at_the_password_prompt_exits_two_and_consumes_nothing(self, h: Harness) -> None:
        def interrupted(prompt: str) -> str:
            raise KeyboardInterrupt
        h.getpass_fn = interrupted  # type: ignore[method-assign]
        assert h.run() == 2
        assert h.server.calls == [] and not h.evidence_path.exists() and not h.work_dir.exists()
        assert any("nothing was consumed" in line for line in h.err)


# ---------------------------------------------------------------------------
# --cleanup-only: reconcile a crashed run; small non-gate artifact; never a pass
# ---------------------------------------------------------------------------
EXPECTED_CLEANUP_KEYS = {
    "schema", "mode", "result", "stop_reason", "start_utc", "end_utc", "baseline_commit", "tree_clean",
    "verifier_script_sha256", "oracle", "targets", "login", "cleanup", "gate_map",
}


def run_cleanup_only(h: Harness, **over: Any) -> int:
    config = subject.RunConfig(evidence_output=h.evidence_path, baseline_commit=BASELINE, cleanup_only=True,
                               frontend_dir=h.frontend_dir, **over)
    return subject.run_verifier(config, h.seams(), OS_ENVIRON)


class TestCleanupOnly:
    def test_golden_state_confirms_with_no_mutation(self, h: Harness) -> None:
        assert run_cleanup_only(h) == 0
        art = h.artifact()
        assert set(art) == EXPECTED_CLEANUP_KEYS
        assert (art["schema"], art["mode"], art["result"]) == ("wave10-5b-cleanup-v1", "cleanup_only", "CONFIRMED_GOLDEN")
        assert art["cleanup"]["result"] == "not_needed" and art["cleanup"]["golden_confirmed"] is True
        assert server_paths(h, "POST") == ["/api/auth/login"] and server_paths(h, "GET") == ["/api/portfolio"]
        assert server_paths(h, "PUT") == []

    def test_it_is_never_a_pass(self, h: Harness) -> None:
        run_cleanup_only(h)
        assert h.artifact()["gate_map"] == {"step_a_credited": False, "run_result": "not_all_items_passed"}
        assert "verdict" not in h.artifact()

    def test_it_creates_no_work_directory_and_starts_no_browser_or_fetch(self, h: Harness) -> None:
        run_cleanup_only(h)
        assert not h.work_dir.exists() and h.browser.spawn_calls == [] and h.fetch.urls == []

    def test_non_golden_state_is_reset_and_confirmed(self, h: Harness) -> None:
        h.server.rows = DIRTY_ROWS
        assert run_cleanup_only(h) == 0
        art = h.artifact()
        assert art["cleanup"]["result"] == "confirmed" and art["cleanup"]["attempts"] == 1
        (reset,) = h.server.calls_to("PUT", "/api/portfolio/demo-reset")
        assert reset["body"] == {"expectedVersion": 5}

    def test_unconfirmed_cleanup_exits_one(self, h: Harness) -> None:
        h.server.rows = DIRTY_ROWS
        h.server.reset_status = 500
        assert run_cleanup_only(h) == 1
        art = h.artifact()
        assert art["result"] == "UNCONFIRMED" and art["cleanup"]["result"] == "unconfirmed"
        assert art["stop_reason"]["code"] == "STILL_NOT_GOLDEN"

    def test_login_failure_exits_one_without_touching_the_portfolio(self, h: Harness) -> None:
        h.server.login_status = 401
        assert run_cleanup_only(h) == 1
        art = h.artifact()
        assert art["result"] == "UNCONFIRMED" and art["stop_reason"]["code"] == "LOGIN_STATUS_NOT_200"
        assert art["cleanup"]["armed"] is False and server_paths(h, "GET") == []

    def test_a_slow_login_does_not_stop_a_deliberate_cleanup(self, h: Harness) -> None:
        h.server.login_seconds = 170.0
        h.server.rows = DIRTY_ROWS
        assert run_cleanup_only(h) == 0
        assert h.artifact()["login"]["state"] == "too_slow"

    def test_break_glass_prompts_for_the_key_and_records_only_that_it_was_used(self, h: Harness) -> None:
        h.getpass_answers = [PASSWORD, INTERNAL_KEY]
        h.server.rows = DIRTY_ROWS
        h.server.reset_status = 500
        assert run_cleanup_only(h, enable_internal_cleanup=True) == 0
        art = h.artifact()
        assert art["cleanup"]["break_glass_used"] is True and len(h.prompts) == 2
        assert INTERNAL_KEY not in h.all_output_text() and PASSWORD not in h.all_output_text()

    def test_it_needs_no_deploy_arguments_no_tooling_and_no_ledger_contract(self, h: Harness) -> None:
        _cmd(h, "node", 127)
        assert run_cleanup_only(h, contract_path=h.tmp / "absent.json") == 0

    @pytest.mark.parametrize(
        "setup,code",
        [
            (lambda h: _cmd(h, "git-head", 0, "f" * 40), "BASELINE_COMMIT_MISMATCH"),
            (lambda h: _cmd(h, "git-status", 0, " M x\n"), "TREE_NOT_CLEAN"),
            (lambda h: _cmd(h, "oracle", 1), "ORACLE_FAILED"),
            (lambda h: h.evidence_path.write_text("keep", encoding="utf-8"), "EVIDENCE_PATH_EXISTS"),
            (lambda h: setattr(h, "getpass_answers", [""]), "PASSWORD_EMPTY"),
        ],
    )
    def test_precondition_failures_exit_two_before_any_login(self, h: Harness, setup: Callable[[Harness], Any], code: str) -> None:
        setup(h)
        assert run_cleanup_only(h) == 2
        assert any(f"[{code}]" in line for line in h.err) and h.server.calls == []

    def test_interrupt_during_cleanup_writes_the_artifact_then_reraises(self, h: Harness) -> None:
        h.server.rows = DIRTY_ROWS
        real = h.server

        def interrupting(method: str, url: str, headers: dict[str, str], json_body: Any = None, timeout: float = 30.0) -> Any:
            if method == "PUT":
                raise KeyboardInterrupt
            return real(method, url, headers, json_body, timeout)

        h.http = interrupting
        with pytest.raises(KeyboardInterrupt):
            run_cleanup_only(h)
        art = h.artifact()
        assert art["result"] == "UNCONFIRMED" and art["cleanup"]["detail_code"] == "CLEANUP_INTERRUPTED"

    def test_no_secret_reaches_the_artifact_or_output(self, h: Harness) -> None:
        h.server.rows = DIRTY_ROWS
        run_cleanup_only(h)
        blob = h.all_output_text()
        assert PASSWORD not in blob and TOKEN not in blob


# ---------------------------------------------------------------------------
# main(argv)
# ---------------------------------------------------------------------------
class TestMain:
    def test_a_full_go_run_through_main(self, h: Harness) -> None:
        assert h.main() == 0
        assert h.artifact()["verdict"] == "GO"

    def test_cleanup_only_through_main(self, h: Harness) -> None:
        h.server.rows = DIRTY_ROWS
        assert h.main("--cleanup-only", cleanup_only=True) == 0
        assert h.artifact()["mode"] == "cleanup_only"

    def test_unknown_arguments_exit_two_without_echoing_their_values(self, h: Harness) -> None:
        assert h.main("--password=SUPER-SECRET-VALUE") == 2
        assert "SUPER-SECRET-VALUE" not in "\n".join(h.err + h.out)
        assert h.prompts == [] and h.server.calls == []

    def test_missing_required_arguments_exit_two(self, h: Harness) -> None:
        h.fs = _ConfigPresentFs()
        assert subject.main(["--baseline-commit", BASELINE], seams=h.seams(), environ=OS_ENVIRON) == 2
        assert subject.main(["--evidence-output", str(h.evidence_path)], seams=h.seams(), environ=OS_ENVIRON) == 2
        assert h.prompts == [] and not h.evidence_path.exists()

    def test_option_abbreviations_are_rejected(self, h: Harness) -> None:
        assert h.main("--cleanup") == 2

    @pytest.mark.parametrize(
        "args,code",
        [
            (["--deploy-completed-utc", "2026-09-20 11:00:00"], "DEPLOY_COMPLETED_INVALID"),
            (["--deploy-completed-utc", "2026-09-20T11:00:00+00:00"], "DEPLOY_COMPLETED_INVALID"),
            (["--bound-seconds", "abc"], "BOUND_SECONDS_INVALID"),
            (["--bound-seconds", "12.5"], "BOUND_SECONDS_INVALID"),
            (["--operation-timeout", "fast"], "OPERATION_TIMEOUT_INVALID"),
            (["--bound-seconds", "299"], "BOUND_SECONDS_INVALID"),
            (["--operation-timeout", "0"], "OPERATION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", "fast"], "ACTION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", "12.5"], "ACTION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", "1e5"], "ACTION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", "-5000"], "ACTION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", ""], "ACTION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", "999"], "ACTION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", "600001"], "ACTION_TIMEOUT_INVALID"),
            (["--action-timeout-ms", "9999999999"], "ACTION_TIMEOUT_INVALID"),
        ],
    )
    def test_malformed_values_exit_two_with_a_code(self, h: Harness, args: list[str], code: str) -> None:
        h.fs = _ConfigPresentFs()
        argv = ["--evidence-output", str(h.evidence_path), "--baseline-commit", BASELINE] + args
        # remaining run-mode arguments are valid so only the bad value can fail
        for name, value in (("--deploy-completed-utc", DEPLOY_COMPLETED), ("--bound-seconds", "7200"),
                            ("--pre-deploy-build-id", OLD_BUILD_ID),
                            ("--backend-attestation-pre", str(h.tmp / "attestation-pre.json")),
                            ("--deploy-run-id", "42"), ("--source-head-sha", SOURCE_SHA)):
            if name not in args:
                argv += [name, value]
        assert subject.main(argv, seams=h.seams(), environ=OS_ENVIRON) == 2
        assert any(f"[{code}]" in line for line in h.err), h.err
        assert h.prompts == [] and h.server.calls == []

    def test_flags_and_values_reach_the_config(self, h: Harness, monkeypatch: pytest.MonkeyPatch) -> None:
        seen: list[subject.RunConfig] = []
        monkeypatch.setattr(subject, "run_verifier", lambda config, seams, environ: seen.append(config) or 0)
        h.main("--allow-active-presence", "--enable-internal-cleanup", "--operation-timeout", "45",
               "--action-timeout-ms", "90000")
        (config,) = seen
        assert config.allow_active_presence and config.enable_internal_cleanup and not config.cleanup_only
        assert config.operation_timeout == 45.0 and config.bound_seconds == 7200 and config.action_timeout_ms == 90000
        assert config.deploy_completed_utc == dt(DEPLOY_COMPLETED) and config.baseline_commit == BASELINE
        assert config.pre_deploy_build_id == OLD_BUILD_ID and config.deploy_run_id == "1234567890"
        assert config.source_head_sha == SOURCE_SHA and config.evidence_output == h.evidence_path
        assert config.frontend_origin is None and config.api_origin is None  # there is no target override flag

    def test_defaults(self, h: Harness, monkeypatch: pytest.MonkeyPatch) -> None:
        seen: list[subject.RunConfig] = []
        monkeypatch.setattr(subject, "run_verifier", lambda config, seams, environ: seen.append(config) or 0)
        h.main()
        assert seen[0].operation_timeout == 30.0 and not seen[0].allow_active_presence
        assert seen[0].action_timeout_ms == 120000 and isinstance(seen[0].action_timeout_ms, int)
        assert not seen[0].enable_internal_cleanup

    def test_there_is_no_flag_for_targets_password_or_key(self) -> None:
        help_text = subject.build_parser().format_help()
        for forbidden in ("--password", "--api-url", "--frontend-url", "--internal-key", "--gateway-url", "--token"):
            assert forbidden not in help_text

    def test_help_exits_zero_and_prints_only_to_the_captured_stream(self, capsys: pytest.CaptureFixture[str]) -> None:
        with pytest.raises(SystemExit) as info:
            subject.main(["--help"])
        assert info.value.code == 0
        captured = capsys.readouterr()
        assert "--evidence-output" in captured.out and captured.err == ""


# ---------------------------------------------------------------------------
# Real (non-network) building blocks and copied helpers
# ---------------------------------------------------------------------------
class TestRealBuildingBlocks:
    def test_write_exclusive_never_overwrites_and_read_bytes_is_capped(self, tmp_path: Path) -> None:
        fs = subject.FileOps()
        fs.write_exclusive(tmp_path / "a", b"one")
        with pytest.raises(FileExistsError):
            fs.write_exclusive(tmp_path / "a", b"two")
        assert (tmp_path / "a").read_bytes() == b"one"
        with pytest.raises(OSError):
            fs.read_bytes(tmp_path / "a", 2)
        assert fs.read_bytes(tmp_path / "a", 3) == b"one"

    def test_append_line_appends_lf_terminated_utf8(self, tmp_path: Path) -> None:
        fs = subject.FileOps()
        fs.append_line(tmp_path / "l", "one")
        fs.append_line(tmp_path / "l", "two")
        assert (tmp_path / "l").read_bytes() == b"one\ntwo\n"

    def test_mkdir_exclusive_refuses_an_existing_directory(self, tmp_path: Path) -> None:
        fs = subject.FileOps()
        fs.mkdir_exclusive(tmp_path / "d")
        with pytest.raises(FileExistsError):
            fs.mkdir_exclusive(tmp_path / "d")

    def test_defer_sigint_ignores_then_restores(self) -> None:
        before = signal.getsignal(signal.SIGINT)
        with subject.defer_sigint():
            assert signal.getsignal(signal.SIGINT) == signal.SIG_IGN
        assert signal.getsignal(signal.SIGINT) == before

    def test_run_cmd_reports_a_missing_executable_without_raising(self) -> None:
        result = subject.make_run_cmd(["definitely-not-a-real-executable-xyz"], None, {"PATH": ""}, 5.0)
        assert result.returncode == 127

    def test_spawn_child_raises_for_a_missing_executable(self, tmp_path: Path) -> None:
        with pytest.raises(FileNotFoundError):
            subject.make_spawn_child(["definitely-not-a-real-executable-xyz"], {"PATH": str(tmp_path)}, tmp_path, tmp_path / "log")
        assert not (tmp_path / "log").exists()

    def test_real_seams_are_built_without_side_effects(self) -> None:
        seams = subject.real_seams()
        assert seams.http_call is subject.make_http and seams.fetch_root_html is subject.make_fetch_root_html
        assert seams.defer_sigint is subject.defer_sigint and callable(seams.getpass_fn) and callable(seams.input_fn)

    def test_is_within(self, tmp_path: Path) -> None:
        assert subject._is_within(tmp_path / "a" / "b", tmp_path)
        assert not subject._is_within(tmp_path.parent / "elsewhere", tmp_path)
        assert not subject._is_within(tmp_path, tmp_path / "sub")

    def test_time_helpers_are_strict(self) -> None:
        assert subject.utc_z(dt("2026-09-20T12:00:00.123456Z")) == "2026-09-20T12:00:00Z"
        assert subject.utc_ms(dt("2026-09-20T12:00:00.123456Z")) == "2026-09-20T12:00:00.123Z"
        for bad in ("2026-09-20T12:00:00", "2026-09-20T12:00:00+00:00", "2026-13-20T12:00:00Z", "", None):
            with pytest.raises(ValueError):
                subject.parse_utc_z(bad)  # type: ignore[arg-type]

    def test_select_portfolio_is_identity_checked(self) -> None:
        assert subject.select_portfolio(portfolio_body(3))["version"] == 3
        with pytest.raises(subject.ObserveError):
            subject.select_portfolio([])

    def test_wire_holdings_are_sorted_ascii_and_reject_duplicates_and_non_string_quantities(self) -> None:
        rows = [{"ticker": "b", "quantity": "1.00000000"}, {"assetTicker": "A", "quantity": "2.00000000"}]
        assert [r["assetTicker"] for r in subject.wire_holdings_sorted(rows)] == ["A", "b"]
        with pytest.raises(ValueError):
            subject.wire_holdings_sorted(rows + [{"assetTicker": "A", "quantity": "3.00000000"}])
        with pytest.raises(ValueError):
            subject.wire_holdings_sorted([{"assetTicker": "A", "quantity": 2}])

    def test_the_oracle_is_run_as_python_dash_b_with_a_scrubbed_environment(self, h: Harness) -> None:
        h.run()
        oracle_call = next(c for c in h.cmd.calls if "-B" in c["argv"])
        assert oracle_call["argv"] == [sys.executable, "-B", str(subject.DEFAULT_ORACLE)]
        assert "INTERNAL_API_KEY" not in oracle_call["env"] and "DEBUG" not in oracle_call["env"]
        assert set(oracle_call["env"]) <= {"PATH", "SystemRoot", "TEMP", "HOME", "PLAYWRIGHT_BROWSERS_PATH"}

    def test_git_is_asked_for_head_and_the_tracked_tree_only(self, h: Harness) -> None:
        h.run()
        git_calls = [c["argv"] for c in h.cmd.calls if c["argv"][0] == "git"]
        assert ["git", "-C", str(subject.REPO), "rev-parse", "HEAD"] in git_calls
        assert ["git", "-C", str(subject.REPO), "status", "--porcelain", "--untracked-files=no"] in git_calls


# ===========================================================================
# Independent-review fixes (P1-P19): the tests below fail without their fix
# ===========================================================================
EXPECTED_PASS_FACTS: dict[str, dict[str, Any]] = {
    "L0": {"finalPathIsPortfolio": True, "headingPortfolio": True, "redirectedToLogin": False,
           "unauthorized401Seen": False},
    "L1": {"editButtonVisible": True, "resetButtonVisible": True, "portfolioLoadStatus": 200},
    "L2": {"freshnessState": {"not": "ABSENT"}, "countsValid": True, "stripVisible": True},
    "L3": {"dialogOpen": True, "unavailableNoticeVisible": False},
    "L4": {"catalogStatus": 200, "noIfNoneMatch": True, "etagPresent": True, "assetsNonEmpty": True,
           "rowsRendered": True, "catalogParity": True, "activeCount": {"min": 1}},
    "L5": {"presenceRequestsSinceOpen": 1, "presenceStatus": 200, "anotherSessionActive": {"type": "boolean"},
           "requestFailed": False, "corsConsoleError": False},
    "L6": {"priceRequestsAfterUncheck": {"equalsFact": "predictedBatchCount"}, "allStatus200": True,
           "arrayShaped": True, "nonNullPriceSeen": True, "disjointFromPageBatches": True,
           "predictedBatchCount": {"min": 1}},
    "L7": {"pageWriteRequestsBeforeMutation": 0},
    "L8": {"putCount": 1, "putStatus": 200, "expectedVersionMatchesObserved": True,
           "bodyMatchesExpectedDraft": True, "versionAdvanced": True, "savedStatusVisible": True,
           "independentReadVersionMatches": True, "independentReadHoldingsMatch": True,
           "mutationSkippedReason": "NONE"},
    "L9": {"putCount": 1, "putStatus": 200, "noInternalKeyHeader": True, "expectedVersionMatchesSaved": True,
           "versionPlusOne": True, "responseEqualsGolden": True, "resetStatusVisible": True,
           "independentReadEqualsGolden": True, "mutationSkippedReason": "NONE"},
}
MISSING = object()  # an override value that deletes the fact


def _enum_members(kind: str) -> tuple[str, ...]:
    return {"freshnessState": CONTRACT.freshness_states,
            "mutationSkippedReason": CONTRACT.mutation_skipped_reasons}[kind]


def contract_passing_facts(leg: str) -> dict[str, Any]:
    """A complete, passing facts object synthesized from the contract itself (legFacts gives the
    keys, passFacts the values), not copied from LEG_FACTS. An unconstrained fact gets a neutral
    value; a {"type": "boolean"} fact gets False, which is what compute_verdict needs for a run
    that may mutate (anotherSessionActive must then be exactly false)."""
    kinds, rules = CONTRACT.leg_facts[leg], CONTRACT.pass_facts[leg]

    def value_for(fact: str) -> Any:
        kind, rule = kinds[fact], rules.get(fact)
        if rule is None:
            if kind in ("freshnessState", "mutationSkippedReason"):
                return _enum_members(kind)[0]
            return {"boolean": True, "integer": 0, "boolean|null": False}[kind]
        if not isinstance(rule, dict):
            return rule
        ((form, argument),) = rule.items()
        if form == "min":
            return argument
        if form == "type":
            return False
        if form == "equalsFact":
            return value_for(argument)
        assert form == "not"
        if kind in ("freshnessState", "mutationSkippedReason"):
            return next(m for m in _enum_members(kind) if m != argument)
        return (not argument) if isinstance(argument, bool) else argument + 1

    return {fact: value_for(fact) for fact in kinds}


def violating_value(leg: str, fact: str) -> Any:
    """A vocabulary-valid value that breaks exactly the rule of `fact`."""
    rule, kind = CONTRACT.pass_facts[leg][fact], CONTRACT.leg_facts[leg][fact]
    if not isinstance(rule, dict):
        if isinstance(rule, bool):
            return not rule
        if isinstance(rule, int):
            return rule + 1
        return next(m for m in _enum_members(kind) if m != rule)
    ((form, argument),) = rule.items()
    if form == "not":
        return argument
    if form == "min":
        return argument - 1
    if form == "type":
        return None
    assert form == "equalsFact"
    return contract_passing_facts(leg)[argument] + 1


def ledger_with_facts(overrides: Optional[dict[str, dict[str, Any]]] = None, *,
                      empty: tuple[str, ...] = (),
                      http: Optional[dict[str, list[dict[str, Any]]]] = None) -> list[dict[str, Any]]:
    """The GO ledger built from contract-derived facts, with per-leg fact overrides (MISSING
    deletes a fact), legs whose `passed` line carries `facts: {}` and per-leg http overrides (a leg
    not named keeps its honest LEG_HTTP entries)."""
    overrides = overrides or {}
    http = http or {}

    def facts_for(leg: str) -> dict[str, Any]:
        if leg in empty:
            return {}
        facts = contract_passing_facts(leg)
        for key, value in overrides.get(leg, {}).items():
            if value is MISSING:
                facts.pop(key)
            else:
                facts[key] = value
        return facts

    writer = SpecWriter()
    for i in range(8):
        writer.leg(f"L{i}", facts=facts_for(f"L{i}"), http=http.get(f"L{i}"))
    writer.armed()
    writer.leg("L8", facts=facts_for("L8"), http=http.get("L8"))
    writer.leg("L9", facts=facts_for("L9"), http=http.get("L9"))
    return writer.lines


def evaluate(lines: list[dict[str, Any]], **over: Any):
    """parse -> derive_legs -> compute_verdict, as the orchestrator does."""
    parsed = parse(*lines)
    legs = subject.derive_legs(parsed, CONTRACT, child_expected=True, fallback_reason="X")
    return parsed, legs, subject.compute_verdict(base(legs=legs, ledger_clean=parsed.clean, **over))


PASS_RULE_CASES = [(leg, fact) for leg in CONTRACT.legs for fact in CONTRACT.pass_facts[leg]]
LEG_FACT_CASES = [(leg, fact) for leg in CONTRACT.legs for fact in CONTRACT.leg_facts[leg]]


class TestPassFactsContract:
    def test_the_repo_pass_table_is_pinned(self) -> None:
        assert {leg: dict(rules) for leg, rules in CONTRACT.pass_facts.items()} == EXPECTED_PASS_FACTS

    def test_every_leg_has_rules_and_every_rule_names_a_fact_of_its_leg(self) -> None:
        assert set(CONTRACT.pass_facts) == set(CONTRACT.legs)
        for leg, rules in CONTRACT.pass_facts.items():
            assert rules and set(rules) <= set(CONTRACT.leg_facts[leg])

    @pytest.mark.parametrize(
        "mutate",
        [
            lambda d: d.update(schema="wave10-5b-ledger-contract-v1"),
            lambda d: d.update(schema="wave10-5b-ledger-contract-v2"),  # the previous schema: no passHttp
            lambda d: d.update(schema="wave10-5b-ledger-contract-v4"),
            lambda d: d.update(unexpected="x"),
            lambda d: d.pop("passFacts"),
            lambda d: d.pop("passFactRules"),
            lambda d: d.update(passFactRules="  "),
            lambda d: d.update(passFacts=[]),
            lambda d: d["passFacts"].pop("L9"),
            lambda d: d["passFacts"].update(L10={"x": True}),
            lambda d: d["passFacts"].update(L7={}),
            lambda d: d["passFacts"].update(L7=[]),
            lambda d: d["passFacts"]["L4"].update(notAFact=True),
            lambda d: d["passFacts"]["L4"].update(catalogStatus="200"),
            lambda d: d["passFacts"]["L4"].update(catalogStatus=True),
            lambda d: d["passFacts"]["L4"].update(catalogStatus=None),
            lambda d: d["passFacts"]["L4"].update(catalogStatus=200.0),
            lambda d: d["passFacts"]["L4"].update(catalogStatus=-1),
            lambda d: d["passFacts"]["L4"].update(catalogStatus=2_000_000_000),  # above the fact bound: no fact can satisfy it
            lambda d: d["passFacts"]["L4"].update(catalogStatus=10**9 + 1),
            lambda d: d["passFacts"]["L4"].update(activeCount={"min": 2_000_000_000}),
            lambda d: d["passFacts"]["L4"].update(activeCount={"min": 10**9 + 1}),
            lambda d: d["passFacts"]["L4"].update(catalogParity=1),
            lambda d: d["passFacts"]["L4"].update(catalogParity=[True]),
            lambda d: d["passFacts"]["L2"].update(freshnessState="STALEISH"),
            lambda d: d["passFacts"]["L2"].update(freshnessState={"not": "STALEISH"}),
            lambda d: d["passFacts"]["L8"].update(mutationSkippedReason="MAYBE"),
            lambda d: d["passFacts"]["L4"].update(activeCount={"min": True}),
            lambda d: d["passFacts"]["L4"].update(activeCount={"min": 1.5}),
            lambda d: d["passFacts"]["L4"].update(activeCount={"min": -1}),
            lambda d: d["passFacts"]["L4"].update(catalogParity={"min": 1}),
            lambda d: d["passFacts"]["L4"].update(activeCount={"type": "boolean"}),
            lambda d: d["passFacts"]["L5"].update(anotherSessionActive={"type": "integer"}),
            lambda d: d["passFacts"]["L6"].update(priceRequestsAfterUncheck={"equalsFact": "priceRequestsAfterUncheck"}),
            lambda d: d["passFacts"]["L6"].update(priceRequestsAfterUncheck={"equalsFact": "nope"}),
            lambda d: d["passFacts"]["L6"].update(priceRequestsAfterUncheck={"equalsFact": "allStatus200"}),
            lambda d: d["passFacts"]["L6"].update(allStatus200={"between": [1, 2]}),
            lambda d: d["passFacts"]["L4"].update(activeCount={"min": 1, "not": 0}),
            lambda d: d["passFacts"]["L4"].update(activeCount={}),
        ],
    )
    def test_a_malformed_pass_table_is_a_precondition_error(self, mutate: Callable[[dict], Any]) -> None:
        doc = json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))
        mutate(doc)
        with pytest.raises(subject.PreconditionError) as info:
            subject.parse_ledger_contract(json.dumps(doc).encode())
        assert info.value.code == "LEDGER_CONTRACT_INVALID"

    def test_the_integer_bound_is_inclusive_for_literals_and_min(self) -> None:
        doc = json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))
        doc["passFacts"]["L4"].update(catalogStatus=10**9, activeCount={"min": 10**9})
        contract = subject.parse_ledger_contract(json.dumps(doc).encode())
        assert contract.pass_facts["L4"]["catalogStatus"] == 10**9
        assert subject.MAX_INTEGER_FACT == 10**9


EXPECTED_PASS_HTTP: dict[str, dict[str, Any]] = {
    "L0": {"entries": "none"},
    "L1": {"entries": {"method": "GET", "path": "/api/portfolio"}, "statusFacts": {"portfolioLoadStatus": "last"}},
    "L2": {"entries": {"method": "GET", "path": "/api/portfolio/summary"}, "firstStatus": 200},
    "L3": {"entries": "none"},
    "L4": {"entries": {"method": "GET", "path": "/api/assets"}, "statusFacts": {"catalogStatus": "first"}},
    "L5": {"entries": {"method": "GET", "path": "/api/presence/demo"}, "countFact": "presenceRequestsSinceOpen",
           "statusFacts": {"presenceStatus": "first"}},
    "L6": {"entries": {"method": "GET", "path": "/api/market/prices"}, "countFact": "priceRequestsAfterUncheck",
           "allStatus": 200},
    "L7": {"entries": "none"},
    "L8": {"entries": {"method": "PUT", "path": "/api/portfolio/holdings"}, "countFact": "putCount",
           "statusFacts": {"putStatus": "first"}},
    "L9": {"entries": {"method": "PUT", "path": "/api/portfolio/demo-reset"}, "countFact": "putCount",
           "statusFacts": {"putStatus": "first"}},
}


class TestPassHttpContract:
    """The http table (schema v3) is parsed defensively: anything unrecognised is a contract error,
    never ignored, because an ignored table would silently switch the reconciliation off."""

    def test_the_repo_http_table_is_pinned(self) -> None:
        assert {leg: dict(rule) for leg, rule in CONTRACT.pass_http.items()} == EXPECTED_PASS_HTTP

    def test_every_leg_has_an_http_rule_and_every_rule_names_only_integer_facts_of_its_leg(self) -> None:
        assert set(CONTRACT.pass_http) == set(CONTRACT.legs)
        for leg, rule in CONTRACT.pass_http.items():
            kinds = CONTRACT.leg_facts[leg]
            named = ([rule["countFact"]] if "countFact" in rule else []) + list(rule.get("statusFacts", {}))
            assert all(kinds[fact] == "integer" for fact in named), leg

    @pytest.mark.parametrize(
        "mutate",
        [
            lambda d: d.pop("passHttp"),
            lambda d: d.pop("passHttpRules"),
            lambda d: d.update(passHttpRules="   "),
            lambda d: d.update(passHttpRules=None),
            lambda d: d.update(passHttp=[]),
            lambda d: d.update(passHttp=None),
            lambda d: d.update(passHttp={}),
            lambda d: d["passHttp"].pop("L9"),  # a missing leg
            lambda d: d["passHttp"].pop("L0"),
            lambda d: d["passHttp"].update(L10={"entries": "none"}),  # an unknown leg
            lambda d: d["passHttp"].update(L7=[]),
            lambda d: d["passHttp"].update(L7="none"),
            lambda d: d["passHttp"].update(L7={}),  # no entries
            lambda d: d["passHttp"]["L7"].update(surprise=1),  # an unknown table key
            lambda d: d["passHttp"]["L4"].update(count=1),
            lambda d: d["passHttp"]["L4"].pop("entries"),
            lambda d: d["passHttp"]["L4"].update(entries="any"),  # neither "none" nor {method,path}
            lambda d: d["passHttp"]["L4"].update(entries=None),
            lambda d: d["passHttp"]["L4"].update(entries=["GET", "/api/assets"]),
            lambda d: d["passHttp"]["L4"].update(entries={"method": "GET"}),
            lambda d: d["passHttp"]["L4"].update(entries={"path": "/api/assets"}),
            lambda d: d["passHttp"]["L4"].update(entries={"method": "GET", "path": "/api/assets", "status": 200}),
            lambda d: d["passHttp"]["L4"].update(entries={"method": "POST", "path": "/api/assets"}),  # outside the vocabulary
            lambda d: d["passHttp"]["L4"].update(entries={"method": "get", "path": "/api/assets"}),
            lambda d: d["passHttp"]["L4"].update(entries={"method": "GET", "path": "/api/other"}),
            lambda d: d["passHttp"]["L4"].update(entries={"method": "GET", "path": "/api/assets?x=1"}),
            lambda d: d["passHttp"]["L4"].update(entries={"method": None, "path": None}),
            lambda d: d["passHttp"]["L5"].update(countFact="notAFact"),  # not a fact of the leg
            lambda d: d["passHttp"]["L5"].update(countFact="putCount"),  # a fact of another leg
            lambda d: d["passHttp"]["L5"].update(countFact="requestFailed"),  # a boolean, not an integer, fact
            lambda d: d["passHttp"]["L5"].update(countFact=5),
            lambda d: d["passHttp"]["L5"].update(countFact=None),
            lambda d: d["passHttp"]["L5"].update(countFact=["presenceRequestsSinceOpen"]),
            lambda d: d["passHttp"]["L5"]["statusFacts"].update(notAFact="first"),
            lambda d: d["passHttp"]["L5"]["statusFacts"].update(requestFailed="first"),  # not an integer fact
            lambda d: d["passHttp"]["L5"]["statusFacts"].update(putStatus="first"),  # another leg's fact
            lambda d: d["passHttp"]["L5"]["statusFacts"].update(presenceStatus="middle"),  # not first/last
            lambda d: d["passHttp"]["L5"]["statusFacts"].update(presenceStatus="FIRST"),
            lambda d: d["passHttp"]["L5"]["statusFacts"].update(presenceStatus=1),
            lambda d: d["passHttp"]["L5"]["statusFacts"].update(presenceStatus=None),
            lambda d: d["passHttp"]["L5"].update(statusFacts={}),
            lambda d: d["passHttp"]["L5"].update(statusFacts=[]),
            lambda d: d["passHttp"]["L5"].update(statusFacts=["presenceStatus"]),
            lambda d: d["passHttp"]["L5"].update(statusFacts="presenceStatus"),
            lambda d: d["passHttp"]["L2"].update(firstStatus="200"),  # not an integer
            lambda d: d["passHttp"]["L2"].update(firstStatus=200.0),
            lambda d: d["passHttp"]["L2"].update(firstStatus=True),
            lambda d: d["passHttp"]["L2"].update(firstStatus=None),
            lambda d: d["passHttp"]["L6"].update(allStatus="200"),
            lambda d: d["passHttp"]["L6"].update(allStatus=200.5),
            lambda d: d["passHttp"]["L6"].update(allStatus=False),
            lambda d: d["passHttp"]["L6"].update(allStatus=[200]),
        ],
    )
    def test_a_malformed_http_table_is_a_precondition_error(self, mutate: Callable[[dict], Any]) -> None:
        doc = json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))
        mutate(doc)
        with pytest.raises(subject.PreconditionError) as info:
            subject.parse_ledger_contract(json.dumps(doc).encode())
        assert info.value.code == "LEDGER_CONTRACT_INVALID"

    def test_a_valid_edited_table_is_accepted_so_the_negative_cases_above_are_not_vacuous(self) -> None:
        doc = json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))
        doc["passHttp"]["L4"].update(countFact="activeCount", statusFacts={"catalogStatus": "last"}, firstStatus=200)
        contract = subject.parse_ledger_contract(json.dumps(doc).encode())
        assert contract.pass_http["L4"]["statusFacts"] == {"catalogStatus": "last"}
        assert contract.pass_http["L4"]["countFact"] == "activeCount" and contract.pass_http["L4"]["firstStatus"] == 200


class TestPassRuleSemantics:
    """No rule coerces: True is not 1, "200" is not 200, and a wrong-typed value never satisfies."""

    @pytest.mark.parametrize(
        "rule,value,facts,expected",
        [
            (True, True, {}, True), (True, 1, {}, False), (True, False, {}, False),
            (True, "true", {}, False), (True, None, {}, False),
            (False, False, {}, True), (False, 0, {}, False), (False, None, {}, False),
            (200, 200, {}, True), (200, "200", {}, False), (200, 200.0, {}, False),
            (200, True, {}, False), (200, 201, {}, False), (200, None, {}, False),
            (1, True, {}, False), (1, 1, {}, True), (0, False, {}, False), (0, 0, {}, True),
            ("NONE", "NONE", {}, True), ("NONE", "none", {}, False), ("NONE", 0, {}, False),
            ({"not": "ABSENT"}, "FRESH", {}, True), ({"not": "ABSENT"}, "ABSENT", {}, False),
            ({"not": "ABSENT"}, 1, {}, False), ({"not": "ABSENT"}, None, {}, False),
            ({"not": False}, True, {}, True), ({"not": False}, False, {}, False),
            ({"not": False}, 0, {}, False),
            ({"min": 1}, 1, {}, True), ({"min": 1}, 5, {}, True), ({"min": 1}, 0, {}, False),
            ({"min": 1}, -1, {}, False), ({"min": 1}, True, {}, False), ({"min": 1}, 1.0, {}, False),
            ({"min": 1}, "1", {}, False), ({"min": 1}, None, {}, False),
            ({"min": 0}, False, {}, False), ({"min": 0}, 0, {}, True),
            ({"type": "boolean"}, True, {}, True), ({"type": "boolean"}, False, {}, True),
            ({"type": "boolean"}, None, {}, False), ({"type": "boolean"}, 0, {}, False),
            ({"type": "boolean"}, 1, {}, False), ({"type": "boolean"}, "true", {}, False),
            ({"equalsFact": "b"}, 3, {"a": 3, "b": 3}, True),
            ({"equalsFact": "b"}, 3, {"a": 3, "b": 4}, False),
            ({"equalsFact": "b"}, True, {"b": 1}, False), ({"equalsFact": "b"}, 1, {"b": True}, False),
            ({"equalsFact": "b"}, 3, {"a": 3}, False),
            ({}, 1, {}, False), ({"min": 1, "not": 0}, 1, {}, False), ({"between": 1}, 1, {}, False),
        ],
    )
    def test_rule_semantics(self, rule: Any, value: Any, facts: dict, expected: bool) -> None:
        assert subject._rule_holds(rule, value, facts) is expected


class TestPassFactsEnforcement:
    def test_hand_written_and_contract_derived_fixtures_both_satisfy_the_table(self) -> None:
        for leg in CONTRACT.legs:
            derived = contract_passing_facts(leg)
            assert set(derived) == set(LEG_FACTS[leg]) == set(CONTRACT.leg_facts[leg])
            assert subject.pass_facts_violation(CONTRACT, leg, derived) is None, leg
            assert subject.pass_facts_violation(CONTRACT, leg, LEG_FACTS[leg]) is None, leg

    def test_the_contract_derived_ledger_is_go(self) -> None:
        parsed, legs, verdict = evaluate(ledger_with_facts())
        assert parsed.clean and all(r.status == "passed" for r in legs.values())
        assert (verdict.verdict, verdict.code) == ("GO", "NONE")

    def test_the_demonstrated_contradictory_ledger_is_non_go(self) -> None:
        """All ten legs say passed, but their own facts say 9.1 parity failed, presence was asked
        twice, the save PUT was sent twice and returned 500, and the reset never verified."""
        lines = ledger_with_facts({
            "L4": {"catalogParity": False},
            "L5": {"presenceRequestsSinceOpen": 2},
            "L8": {"putCount": 2, "putStatus": 500, "versionAdvanced": False,
                   "independentReadVersionMatches": False, "independentReadHoldingsMatch": False},
            "L9": {"putCount": 0, "versionPlusOne": False, "responseEqualsGolden": False,
                   "independentReadEqualsGolden": False},
        })
        assert {line["status"] for line in lines if line["event"] == "leg"} == {"passed"}
        parsed, legs, verdict = evaluate(lines)
        assert parsed.clean  # in-vocabulary: only the pass table can catch it
        for leg in ("L4", "L5", "L8", "L9"):
            assert (legs[leg].status, legs[leg].reason) == ("failed", "FACTS_CONTRADICT_PASS"), leg
        assert all(legs[leg].status == "passed" for leg in ("L0", "L1", "L2", "L3", "L6", "L7"))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "FACTS_CONTRADICT_PASS")
        assert {"FACTS_CONTRADICT_PASS", "LEG_FAILED"} <= set(verdict.failed_criteria)

    def test_the_demonstrated_empty_facts_ledger_is_non_go(self) -> None:
        """Eight legs say passed with `facts: {}` (L1 and L5 keep theirs so the two facts the
        verdict happens to read stay valid)."""
        lines = ledger_with_facts(empty=("L0", "L2", "L3", "L4", "L6", "L7", "L8", "L9"))
        parsed, legs, verdict = evaluate(lines)
        assert parsed.clean and all(line["status"] == "passed" for line in lines if line["event"] == "leg")
        for leg in ("L0", "L2", "L3", "L4", "L6", "L7", "L8", "L9"):
            assert (legs[leg].status, legs[leg].reason) == ("failed", "FACTS_INCOMPLETE"), leg
        assert legs["L1"].status == legs["L5"].status == "passed"
        assert (verdict.verdict, verdict.code) == ("NON_GO", "FACTS_INCOMPLETE")
        assert verdict.run_result == "not_all_items_passed"

    @pytest.mark.parametrize("leg,fact", PASS_RULE_CASES, ids=[f"{l}.{f}" for l, f in PASS_RULE_CASES])
    def test_a_single_violated_fact_turns_the_verdict_non_go(self, leg: str, fact: str) -> None:
        facts = contract_passing_facts(leg)
        facts[fact] = violating_value(leg, fact)
        expected = "FACTS_CONTRADICT_PASS"
        assert subject.pass_facts_violation(CONTRACT, leg, facts) == expected  # the pure rule check
        parsed, legs, verdict = evaluate(ledger_with_facts({leg: {fact: facts[fact]}}))
        assert (verdict.verdict, verdict.run_result) == ("NON_GO", "not_all_items_passed")
        assert legs[leg].status == "failed"
        # a passed L8/L9 that names a skip reason is already refused at the ledger-line level
        assert legs[leg].reason == ("LEDGER_INVALID" if fact == "mutationSkippedReason" else expected)
        assert all(legs[other].status == "passed" for other in CONTRACT.legs if other != leg)

    @pytest.mark.parametrize("leg,fact", LEG_FACT_CASES, ids=[f"{l}.{f}" for l, f in LEG_FACT_CASES])
    def test_a_passed_leg_missing_any_single_fact_is_incomplete(self, leg: str, fact: str) -> None:
        facts = contract_passing_facts(leg)
        del facts[fact]
        assert subject.pass_facts_violation(CONTRACT, leg, facts) == "FACTS_INCOMPLETE"
        parsed, legs, verdict = evaluate(ledger_with_facts({leg: {fact: MISSING}}))
        assert parsed.clean and (legs[leg].status, legs[leg].reason) == ("failed", "FACTS_INCOMPLETE")
        assert verdict.verdict == "NON_GO" and "FACTS_INCOMPLETE" in verdict.failed_criteria

    def test_failed_and_skipped_legs_are_not_constrained_by_the_pass_table(self) -> None:
        failed = one_leg("L4", status="failed", reason="ASSERTION_FAILED")
        failed["facts"] = {}
        legs = subject.derive_legs(parse(failed), CONTRACT, child_expected=True, fallback_reason="X")
        assert (legs["L4"].status, legs["L4"].reason) == ("failed", "ASSERTION_FAILED")
        skipped = parse(*visitor_ledger())
        legs = subject.derive_legs(skipped, CONTRACT, child_expected=True, fallback_reason="X")
        assert (legs["L8"].status, legs["L8"].reason) == ("skipped", "SKIPPED_VISITOR_PRESENT")

    def test_the_two_of_seven_facts_appendix_example_parses_but_is_not_believed(self) -> None:
        leg = {"seq": 1, "tUtc": "2026-01-01T00:00:00.000Z", "src": "spec", "event": "leg", "leg": "L4",
               "status": "passed", "reason": "OK",
               "http": [{"method": "GET", "path": "/api/assets", "status": 200}],
               "facts": {"catalogStatus": 200, "etagPresent": True}}
        parsed = parse(leg)
        assert parsed.clean  # vocabulary-valid ...
        legs = subject.derive_legs(parsed, CONTRACT, child_expected=True, fallback_reason="X")
        assert (legs["L4"].status, legs["L4"].reason) == ("failed", "FACTS_INCOMPLETE")  # ... but a pass needs every fact

    def test_a_downgraded_leg_keeps_its_closed_vocabulary_evidence_for_the_reader(self) -> None:
        lines = ledger_with_facts({"L4": {"catalogParity": False}})
        _, legs, _ = evaluate(lines)
        assert legs["L4"].facts["catalogParity"] is False and legs["L4"].http == tuple(LEG_HTTP["L4"])

    def test_orchestrator_reasons_include_the_two_new_codes_and_every_derived_reason_is_known(self) -> None:
        assert {"FACTS_INCOMPLETE", "FACTS_CONTRADICT_PASS"} <= set(subject.ORCHESTRATOR_LEG_REASONS)
        for code in ("FACTS_INCOMPLETE", "FACTS_CONTRADICT_PASS", "CHILD_KILL_UNCONFIRMED", "ARTIFACT_PATH_UNAVAILABLE"):
            assert code in subject.STOP_TEXT
        reasons = set(CONTRACT.reasons) | set(subject.ORCHESTRATOR_LEG_REASONS)
        for lines in (ledger_with_facts({"L4": {"catalogParity": False}}), ledger_with_facts(empty=("L0",))):
            _, legs, _ = evaluate(lines)
            assert {r.reason for r in legs.values()} <= reasons

    def test_contradictory_ledger_end_to_end_is_non_go_and_recorded_with_the_new_code(self, h: Harness) -> None:
        h.browser.lines = ledger_with_facts({"L8": {"putCount": 2, "putStatus": 500, "versionAdvanced": False}})
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "FACTS_CONTRADICT_PASS")
        assert {leg["id"]: leg["reason_code"] for leg in art["legs"]}["L8"] == "FACTS_CONTRADICT_PASS"
        assert art["gate_map"]["run_result"] == "not_all_items_passed" and h.input_prompts == []

    def test_empty_facts_ledger_end_to_end_is_non_go_and_recorded_with_the_new_code(self, h: Harness) -> None:
        h.browser.lines = ledger_with_facts(empty=("L0", "L2", "L3", "L4", "L6", "L7", "L8", "L9"))
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "FACTS_INCOMPLETE")
        assert {leg["id"]: leg["reason_code"] for leg in art["legs"]}["L8"] == "FACTS_INCOMPLETE"

    def test_a_leg_line_without_http_or_facts_is_refused_not_defaulted(self) -> None:
        for key in ("http", "facts"):
            line = one_leg("L4")
            del line[key]
            parsed = parse(line)
            assert not parsed.clean and parsed.legs["L4"].poisoned and parsed.invalid_lines[0][1] == "MISSING_KEY"
            legs = subject.derive_legs(parsed, CONTRACT, child_expected=True, fallback_reason="X")
            assert (legs["L4"].status, legs["L4"].reason) == ("failed", "LEDGER_INVALID")


# ---------------------------------------------------------------------------
# Round 2, finding Q1: a passed leg's http entries must agree with its own facts (passHttp)
# ---------------------------------------------------------------------------
def contract_passing_http(leg: str) -> list[dict[str, Any]]:
    """Honest http entries synthesized from the contract's passHttp rule and the leg's passing facts
    (not copied from LEG_HTTP): the rule says which method, path, count and status."""
    rule, facts = CONTRACT.pass_http[leg], contract_passing_facts(leg)
    entries = rule["entries"]
    if entries == "none":
        return []
    count = facts[rule["countFact"]] if "countFact" in rule else 1
    statuses = {rule[key] for key in ("firstStatus", "allStatus") if key in rule}
    statuses |= {facts[fact] for fact in rule.get("statusFacts", {})}
    assert len(statuses) == 1, leg  # one status satisfies every status rule of the leg
    (status,) = statuses
    return [{"method": entries["method"], "path": entries["path"], "status": status} for _ in range(count)]


def _other_method(method: str) -> str:
    return next(m for m in CONTRACT.methods if m != method)


def _other_path(path: str) -> str:
    return next(p for p in CONTRACT.paths if p != path)


def http_violation_cases() -> list[tuple[str, str, list[dict[str, Any]], dict[str, Any]]]:
    """(case id, leg, http entries, fact overrides): every aspect of every leg's passHttp rule,
    violated once and on its own. Each case keeps every passFacts rule satisfied, so the http table is
    the only thing that can catch it."""
    cases: list[tuple[str, str, list[dict[str, Any]], dict[str, Any]]] = []
    for leg in CONTRACT.legs:
        rule, honest = CONTRACT.pass_http[leg], contract_passing_http(leg)
        entries = rule["entries"]
        if entries == "none":
            cases.append((f"{leg}.entries.none-but-recorded", leg, [_h("GET", "/api/portfolio")], {}))
            continue
        method, path = entries["method"], entries["path"]
        cases.append((f"{leg}.entries.empty", leg, [], {}))
        cases.append((f"{leg}.entries.method", leg, [dict(honest[0], method=_other_method(method)), *honest[1:]], {}))
        cases.append((f"{leg}.entries.path", leg, [dict(honest[0], path=_other_path(path)), *honest[1:]], {}))
        if "countFact" not in rule:  # no count to keep consistent: a later entry with another path
            cases.append((f"{leg}.entries.later-entry-path", leg, [*honest, dict(honest[0], path=_other_path(path))], {}))
        else:  # a count that stays consistent needs the count and its passFacts partner raised together
            count_fact = rule["countFact"]
            pinned = CONTRACT.pass_facts[leg][count_fact]
            if isinstance(pinned, dict):  # L6: equalsFact predictedBatchCount, so both are raised to 2
                cases.append((f"{leg}.entries.later-entry-path", leg,
                              [*honest, dict(honest[0], path=_other_path(path))],
                              {count_fact: 2, pinned["equalsFact"]: 2}))
            cases.append((f"{leg}.countFact.extra-entry", leg, [*honest, dict(honest[0])], {}))
        for fact, position in rule.get("statusFacts", {}).items():
            cases.append((f"{leg}.statusFacts.{fact}.{position}", leg,
                          [dict(honest[0], status=500), *honest[1:]] if position == "first"
                          else [*honest[:-1], dict(honest[-1], status=500)], {}))
        if "firstStatus" in rule:
            cases.append((f"{leg}.firstStatus", leg, [dict(honest[0], status=500), *honest[1:]], {}))
        if "allStatus" in rule:
            count_fact = rule.get("countFact")
            cases.append((f"{leg}.allStatus", leg, [*honest, dict(honest[0], status=500)],
                          {count_fact: 2, CONTRACT.pass_facts[leg][count_fact]["equalsFact"]: 2}
                          if count_fact else {}))
    return cases


HTTP_CASES = http_violation_cases()


class TestPassHttpEnforcement:
    def test_the_honest_fixtures_satisfy_the_http_table(self) -> None:
        for leg in CONTRACT.legs:
            recorded = LEG_HTTP.get(leg, [])
            assert subject.pass_http_violation(CONTRACT, leg, recorded, LEG_FACTS[leg]) is None, leg
            derived = contract_passing_http(leg)
            assert subject.pass_http_violation(CONTRACT, leg, derived, contract_passing_facts(leg)) is None, leg
            assert (recorded == []) == (CONTRACT.pass_http[leg]["entries"] == "none"), leg
        assert all(LEG_HTTP.get(leg, []) == [] for leg in ("L0", "L3", "L7"))  # these three record NO http

    def test_the_honest_ledgers_are_still_go(self) -> None:
        assert evaluate(go_ledger())[2].verdict == "GO"
        assert evaluate(ledger_with_facts())[2].verdict == "GO"
        assert evaluate(ledger_with_facts(http={leg: contract_passing_http(leg) for leg in CONTRACT.legs}))[2].verdict == "GO"

    def test_the_case_table_covers_every_aspect_of_every_rule(self) -> None:
        aspects = {leg: {case.split(".", 1)[1] for case, l, _, _ in HTTP_CASES if l == leg} for leg in CONTRACT.legs}
        for leg, rule in CONTRACT.pass_http.items():
            if rule["entries"] == "none":
                assert aspects[leg] == {"entries.none-but-recorded"}, leg
                continue
            expected = {"entries.empty", "entries.method", "entries.path"}
            expected |= {"countFact.extra-entry"} if "countFact" in rule else {"entries.later-entry-path"}
            expected |= {f"statusFacts.{fact}.{pos}" for fact, pos in rule.get("statusFacts", {}).items()}
            expected |= {"firstStatus"} if "firstStatus" in rule else set()
            expected |= {"allStatus"} if "allStatus" in rule else set()
            if "countFact" in rule and isinstance(CONTRACT.pass_facts[leg][rule["countFact"]], dict):
                expected |= {"entries.later-entry-path"}
            assert aspects[leg] == expected, leg
        assert len(HTTP_CASES) == 39  # 3 legs with no entries + the aspects above: pins the table size

    @pytest.mark.parametrize("case,leg,http,overrides", HTTP_CASES, ids=[c[0] for c in HTTP_CASES])
    def test_a_single_violated_http_aspect_turns_the_verdict_non_go(
            self, case: str, leg: str, http: list[dict[str, Any]], overrides: dict[str, Any]) -> None:
        facts = {**contract_passing_facts(leg), **overrides}
        assert subject.pass_facts_violation(CONTRACT, leg, facts) is None, case  # only the http table can catch it
        assert subject.pass_http_violation(CONTRACT, leg, http, facts) == "HTTP_CONTRADICT_PASS", case
        parsed, legs, verdict = evaluate(ledger_with_facts({leg: overrides}, http={leg: http}))
        assert parsed.clean, case
        assert (legs[leg].status, legs[leg].reason) == ("failed", "HTTP_CONTRADICT_PASS"), case
        assert (verdict.verdict, verdict.code, verdict.run_result) == (
            "NON_GO", "HTTP_CONTRADICT_PASS", "not_all_items_passed"), case
        assert {"HTTP_CONTRADICT_PASS", "LEG_FAILED"} <= set(verdict.failed_criteria)
        assert all(legs[other].status == "passed" for other in CONTRACT.legs if other != leg), case

    @pytest.mark.parametrize("leg", ["L5", "L8", "L9"])
    def test_a_later_entry_with_another_path_is_caught_on_count_pinned_legs_too(self, leg: str) -> None:
        """L5/L8/L9 pin their count fact to 1 in passFacts, so at the ledger level a second entry also
        breaks the count; the pure rule still names the path of every entry, not just the first."""
        rule, facts = CONTRACT.pass_http[leg], contract_passing_facts(leg)
        facts[rule["countFact"]] = 2
        honest = contract_passing_http(leg)[0]
        both_right = [honest, dict(honest)]
        assert subject.pass_http_violation(CONTRACT, leg, both_right, facts) is None  # the control: count 2 is consistent
        second_wrong = [honest, dict(honest, path=_other_path(honest["path"]))]
        assert subject.pass_http_violation(CONTRACT, leg, second_wrong, facts) == "HTTP_CONTRADICT_PASS"
        second_method = [honest, dict(honest, method=_other_method(honest["method"]))]
        assert subject.pass_http_violation(CONTRACT, leg, second_method, facts) == "HTTP_CONTRADICT_PASS"

    ATTACKS = [
        # (probe, leg, http, fact overrides), each of which the previous reader believed (GO)
        ("A1-L8-put-500-but-putStatus-200", "L8", [_h("PUT", "/api/portfolio/holdings", 500)], {}),
        ("A2-L8-no-put-but-putCount-1", "L8", [], {}),
        ("A21-L8-two-puts-but-putCount-1", "L8",
         [_h("PUT", "/api/portfolio/holdings"), _h("PUT", "/api/portfolio/holdings")], {}),
        ("A5-two-presence-but-count-1", "L5", [_h("GET", "/api/presence/demo"), _h("GET", "/api/presence/demo")], {}),
        ("A3-L4-assets-500-but-catalogStatus-200", "L4", [_h("GET", "/api/assets", 500)], {}),
        ("A6-L6-three-prices-one-a-500-but-count-1", "L6",
         [_h("GET", "/api/market/prices"), _h("GET", "/api/market/prices", 500), _h("GET", "/api/market/prices")], {}),
        ("A20-L9-http-on-the-holdings-path", "L9", [_h("PUT", "/api/portfolio/holdings")], {}),
        ("A22-L2-summary-500", "L2", [_h("GET", "/api/portfolio/summary", 500)], {}),
    ]

    @pytest.mark.parametrize("probe,leg,http,overrides", ATTACKS, ids=[a[0] for a in ATTACKS])
    def test_the_demonstrated_http_vs_facts_ledgers_are_non_go(
            self, probe: str, leg: str, http: list[dict[str, Any]], overrides: dict[str, Any]) -> None:
        lines = ledger_with_facts({leg: overrides}, http={leg: http})
        parsed, legs, verdict = evaluate(lines)
        # the previous reader's checks (facts against the pass table, forbidden statuses) all said fine ...
        record = parsed.legs[leg]
        assert parsed.clean and subject.pass_facts_violation(CONTRACT, leg, record.facts) is None, probe
        assert not subject._forbidden_status_seen(record), probe
        # ... yet the leg's own request evidence contradicts it
        assert (legs[leg].status, legs[leg].reason) == ("failed", "HTTP_CONTRADICT_PASS"), probe
        assert (verdict.verdict, verdict.code) == ("NON_GO", "HTTP_CONTRADICT_PASS"), probe
        assert legs[leg].http == tuple(http)  # the evidence stays visible to the reader

    def test_a_forbidden_status_in_the_http_is_still_named_as_such_first(self) -> None:
        _, legs, verdict = evaluate(ledger_with_facts(http={"L4": [_h("GET", "/api/assets", 503)]}))
        assert legs["L4"].reason == "FORBIDDEN_HTTP_STATUS" and verdict.code != "HTTP_CONTRADICT_PASS"

    def test_facts_are_judged_before_http(self) -> None:
        """With both wrong the leg reports the facts reason: the http rule reads the facts."""
        lines = ledger_with_facts({"L8": {"putStatus": 201}}, http={"L8": [_h("PUT", "/api/portfolio/holdings", 500)]})
        _, legs, _ = evaluate(lines)
        assert legs["L8"].reason == "FACTS_CONTRADICT_PASS"
        incomplete = ledger_with_facts({"L8": {"putCount": MISSING}}, http={"L8": []})
        assert evaluate(incomplete)[1]["L8"].reason == "FACTS_INCOMPLETE"

    def test_failed_and_skipped_legs_are_not_constrained_by_the_http_table(self) -> None:
        failed = one_leg("L8", status="failed", reason="ASSERTION_FAILED", http=[], facts={})
        legs = subject.derive_legs(parse(failed), CONTRACT, child_expected=True, fallback_reason="X")
        assert (legs["L8"].status, legs["L8"].reason) == ("failed", "ASSERTION_FAILED")
        skipped = subject.derive_legs(parse(*visitor_ledger()), CONTRACT, child_expected=True, fallback_reason="X")
        assert (skipped["L8"].status, skipped["L8"].reason) == ("skipped", "SKIPPED_VISITOR_PRESENT")
        assert skipped["L8"].http == ()

    def test_first_and_last_status_facts_read_different_entries(self) -> None:
        ok = LEG_FACTS
        # L1 reads the LAST /api/portfolio entry: a failed first read that the page retried is fine ...
        assert subject.pass_http_violation(CONTRACT, "L1", [_h("GET", "/api/portfolio", 500), _h("GET", "/api/portfolio")],
                                           ok["L1"]) is None
        assert subject.pass_http_violation(CONTRACT, "L1", [_h("GET", "/api/portfolio"), _h("GET", "/api/portfolio", 500)],
                                           ok["L1"]) == "HTTP_CONTRADICT_PASS"
        # ... L4 reads the FIRST entry and L2 requires the first to be 200
        assert subject.pass_http_violation(CONTRACT, "L4", [_h("GET", "/api/assets"), _h("GET", "/api/assets", 500)],
                                           ok["L4"]) is None
        assert subject.pass_http_violation(CONTRACT, "L4", [_h("GET", "/api/assets", 500), _h("GET", "/api/assets")],
                                           ok["L4"]) == "HTTP_CONTRADICT_PASS"
        two = [_h("GET", "/api/portfolio/summary"), _h("GET", "/api/portfolio/summary", 502)]
        assert subject.pass_http_violation(CONTRACT, "L2", two, ok["L2"]) is None
        assert subject.pass_http_violation(CONTRACT, "L2", two[::-1], ok["L2"]) == "HTTP_CONTRADICT_PASS"

    def test_no_coercion_in_the_count_or_status_comparisons(self) -> None:
        put = [_h("PUT", "/api/portfolio/holdings")]
        assert subject.pass_http_violation(CONTRACT, "L8", put, LEG_FACTS["L8"]) is None
        for bad_count in (True, 1.0, "1", None):
            assert subject.pass_http_violation(CONTRACT, "L8", put, {**LEG_FACTS["L8"], "putCount": bad_count}) \
                == "HTTP_CONTRADICT_PASS", bad_count
        for bad_status in (200.0, "200", True, None):
            assert subject.pass_http_violation(CONTRACT, "L8", put, {**LEG_FACTS["L8"], "putStatus": bad_status}) \
                == "HTTP_CONTRADICT_PASS", bad_status
        assert subject.pass_http_violation(CONTRACT, "L8", put, {k: v for k, v in LEG_FACTS["L8"].items() if k != "putCount"}) \
            == "HTTP_CONTRADICT_PASS"  # a missing count fact fails closed

    def test_every_request_leg_with_no_recorded_entry_fails_closed(self) -> None:
        for leg in ("L1", "L2", "L4", "L5", "L6", "L8", "L9"):
            assert subject.pass_http_violation(CONTRACT, leg, [], LEG_FACTS[leg]) == "HTTP_CONTRADICT_PASS", leg

    def test_an_entries_only_rule_still_demands_a_recorded_entry_and_the_right_method_and_path(self) -> None:
        """In the repo contract every request leg also carries a count or status rule that fails on an
        empty list, so the entries rule is exercised alone here: the parser allows a table that only
        says which method and path a leg's requests must have."""
        doc = json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))
        doc["passHttp"]["L4"] = {"entries": {"method": "GET", "path": "/api/assets"}}
        contract = subject.parse_ledger_contract(json.dumps(doc).encode())
        facts = LEG_FACTS["L4"]
        assert subject.pass_http_violation(contract, "L4", [_h("GET", "/api/assets")], facts) is None
        assert subject.pass_http_violation(contract, "L4", [], facts) == "HTTP_CONTRADICT_PASS"  # nothing recorded
        assert subject.pass_http_violation(contract, "L4", [_h("PUT", "/api/assets")], facts) == "HTTP_CONTRADICT_PASS"
        assert subject.pass_http_violation(contract, "L4", [_h("GET", "/api/portfolio")], facts) == "HTTP_CONTRADICT_PASS"
        assert subject.pass_http_violation(
            contract, "L4", [_h("GET", "/api/assets"), _h("GET", "/api/portfolio")], facts) == "HTTP_CONTRADICT_PASS"

    @pytest.mark.parametrize(
        "rule",
        [
            {"entries": "none", "statusFacts": {"pageWriteRequestsBeforeMutation": "first"}},
            {"entries": "none", "statusFacts": {"pageWriteRequestsBeforeMutation": "last"}},
            {"entries": "none", "firstStatus": 200},
            {"entries": "none", "allStatus": 200},
        ],
        ids=["first", "last", "firstStatus", "allStatus"],
    )
    def test_a_status_rule_with_no_entry_to_read_fails_closed(self, rule: dict[str, Any]) -> None:
        """A table that (oddly) demands a status from a leg that records no entries can never be
        satisfied: the evaluator must not treat 'nothing to compare' as 'nothing wrong'. The parser now
        refuses such a table outright ('none' is exclusive; TestPassHttpParserParity), so the evaluator's
        own guard is exercised on a contract built in memory: it stays defence in depth."""
        contract = dataclasses.replace(CONTRACT, pass_http={**CONTRACT.pass_http, "L7": rule})
        assert subject.pass_http_violation(contract, "L7", [], LEG_FACTS["L7"]) == "HTTP_CONTRADICT_PASS"
        doc = json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))
        doc["passHttp"]["L7"] = rule
        with pytest.raises(subject.PreconditionError):  # ... and no contract file can carry it to the evaluator
            subject.parse_ledger_contract(json.dumps(doc).encode())

    def test_the_new_reason_is_known_everywhere_a_reason_must_be(self) -> None:
        assert "HTTP_CONTRADICT_PASS" in subject.ORCHESTRATOR_LEG_REASONS
        assert "HTTP_CONTRADICT_PASS" in subject.STOP_TEXT
        assert subject.scan_text(subject.STOP_TEXT["HTTP_CONTRADICT_PASS"]) == []
        assert "HTTP_CONTRADICT_PASS" not in CONTRACT.reasons  # orchestrator-assigned, never written by the child

    def test_the_contradicting_http_ledger_end_to_end_is_non_go_and_recorded_with_the_new_code(self, h: Harness) -> None:
        h.browser.lines = ledger_with_facts(http={"L8": [_h("PUT", "/api/portfolio/holdings", 500)]})
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "HTTP_CONTRADICT_PASS")
        by_id = {leg["id"]: leg for leg in art["legs"]}
        assert by_id["L8"]["reason_code"] == "HTTP_CONTRADICT_PASS" and by_id["L8"]["status"] == "failed"
        assert by_id["L8"]["http"] == [_h("PUT", "/api/portfolio/holdings", 500)]
        assert art["gate_map"]["run_result"] == "not_all_items_passed" and h.input_prompts == []
        assert {leg["status"] for leg in art["legs"] if leg["id"] != "L8"} == {"passed"}


# ---------------------------------------------------------------------------
# P1: direct-only transport (no proxy can carry the password, the token or the key)
# ---------------------------------------------------------------------------
class CannedResponse(io.BytesIO):
    """A response object for a handler that answers without opening a socket."""

    def __init__(self, url: str, code: int = 200, body: bytes = b"{}", headers: Optional[dict[str, str]] = None) -> None:
        super().__init__(body)
        self.code = self.status = code
        self.msg, self.url = "canned", url
        self.headers = email.message.Message()
        for name, value in (headers or {}).items():
            self.headers[name] = value

    def info(self) -> email.message.Message:
        return self.headers

    def geturl(self) -> str:
        return self.url


class RecordingHttpsHandler(urllib.request.BaseHandler):
    """Sits behind urllib's ProxyHandler (order 100) and in front of the real HTTPSHandler (order
    500): records where the request was routed and answers itself, so no socket is ever opened."""

    handler_order = 200

    def __init__(self, respond: Callable[[urllib.request.Request], CannedResponse]) -> None:
        self.hosts: list[str] = []
        self.urls: list[str] = []
        self.bodies: list[Any] = []
        self._respond = respond

    def https_open(self, req: urllib.request.Request) -> CannedResponse:
        self.hosts.append(req.host)
        self.urls.append(req.full_url)
        self.bodies.append(req.data)
        return self._respond(req)


PROXY_HOST = "proxy.invalid:3128"


@pytest.fixture
def proxy_everywhere(monkeypatch: pytest.MonkeyPatch) -> None:
    """Every place urllib looks for a proxy says: use one (environment and operating system)."""
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy",
                 "all_proxy", "no_proxy"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("HTTPS_PROXY", "http://" + PROXY_HOST)
    monkeypatch.setenv("HTTP_PROXY", "http://" + PROXY_HOST)
    monkeypatch.setattr(urllib.request, "getproxies",
                        lambda: {"https": "http://" + PROXY_HOST, "http": "http://" + PROXY_HOST})


def _install_recorder(monkeypatch: pytest.MonkeyPatch, respond: Callable[..., CannedResponse]) -> RecordingHttpsHandler:
    recorder = RecordingHttpsHandler(respond)
    real = subject._build_opener

    def build() -> urllib.request.OpenerDirector:
        opener = real()
        opener.add_handler(recorder)
        return opener

    monkeypatch.setattr(subject, "_build_opener", build)
    return recorder


class TestDirectOnlyTransport:
    def test_the_control_default_opener_would_route_through_the_proxy(
            self, proxy_everywhere: None) -> None:
        """Positive control: with the default handlers a proxy is in the https chain and takes the
        request, which is the bypass the fix closes."""
        opener = urllib.request.build_opener(subject._NoRedirect)
        assert any(isinstance(h, urllib.request.ProxyHandler) and h.proxies for h in opener.handle_open["https"])
        recorder = RecordingHttpsHandler(lambda req: CannedResponse(req.full_url))
        opener.add_handler(recorder)
        opener.open(urllib.request.Request(API + "/api/portfolio"), timeout=1.0)
        assert recorder.hosts == [PROXY_HOST]

    def test_the_verifiers_opener_has_no_proxy_handler_in_the_https_chain(self, proxy_everywhere: None) -> None:
        opener = subject._build_opener()
        assert not any(isinstance(h, urllib.request.ProxyHandler) and h.proxies for h in opener.handlers)
        assert not any(isinstance(h, urllib.request.ProxyHandler) for h in opener.handle_open.get("https", []))
        assert not any(isinstance(h, urllib.request.ProxyHandler) for h in opener.handle_open.get("http", []))

    def test_make_http_reaches_the_allowlisted_host_with_the_login_body_even_when_a_proxy_is_configured(
            self, proxy_everywhere: None, monkeypatch: pytest.MonkeyPatch) -> None:
        recorder = _install_recorder(monkeypatch, lambda req: CannedResponse(req.full_url, body=b'{"token":"x"}'))
        status, body = subject.make_http(
            "POST", API + "/api/auth/login", {}, {"email": subject.DEMO_EMAIL, "password": PASSWORD}, 1.0)
        assert (status, body) == (200, {"token": "x"})
        assert recorder.hosts == ["api.vibhanshu-ai-portfolio.dev"]
        assert PROXY_HOST not in recorder.hosts and PASSWORD.encode() in recorder.bodies[0]

    def test_make_fetch_root_html_reaches_the_allowlisted_host_even_when_a_proxy_is_configured(
            self, proxy_everywhere: None, monkeypatch: pytest.MonkeyPatch) -> None:
        recorder = _install_recorder(
            monkeypatch, lambda req: CannedResponse(req.full_url, body=b"<html></html>", headers={"Content-Type": "text/html"}))
        status, content_type, body = subject.make_fetch_root_html(FRONTEND + "/", 1.0)
        assert (status, content_type, body) == (200, "text/html", b"<html></html>")
        assert recorder.hosts == ["vibhanshu-ai-portfolio.dev"]

    def test_a_redirect_is_never_followed_by_either_transport(
            self, proxy_everywhere: None, monkeypatch: pytest.MonkeyPatch) -> None:
        recorder = _install_recorder(
            monkeypatch,
            lambda req: CannedResponse(req.full_url, code=302, body=b"",
                                       headers={"Location": "https://evil.example.net/x", "Content-Type": "text/html"}))
        assert subject.make_http("GET", API + "/api/portfolio", {"Authorization": "Bearer x"}, None, 1.0) == (302, None)
        status, _, _ = subject.make_fetch_root_html(FRONTEND + "/", 1.0)
        assert status == 302
        assert recorder.hosts == ["api.vibhanshu-ai-portfolio.dev", "vibhanshu-ai-portfolio.dev"]  # evil.example.net never contacted

    def test_the_opener_only_carries_the_refusing_redirect_handler(self) -> None:
        opener = subject._build_opener()
        redirect_handlers = [h for h in opener.handlers if isinstance(h, urllib.request.HTTPRedirectHandler)]
        assert redirect_handlers and all(isinstance(h, subject._NoRedirect) for h in redirect_handlers)
        request = urllib.request.Request(API + "/api/portfolio")
        assert subject._NoRedirect().redirect_request(request, None, 302, "Found", {}, "https://evil.example.net/") is None

    def test_both_transports_build_their_opener_through_the_single_factory(self, monkeypatch: pytest.MonkeyPatch) -> None:
        def boom() -> Any:
            raise _RecorderExhausted("factory used")
        monkeypatch.setattr(subject, "_build_opener", boom)
        with pytest.raises(_RecorderExhausted):
            subject.make_http("GET", API + "/api/portfolio", {}, None, 1.0)
        with pytest.raises(_RecorderExhausted):
            subject.make_fetch_root_html(FRONTEND + "/", 1.0)


# ---------------------------------------------------------------------------
# P3: the child is `node <cli>` from frontend/, never npm or npx
# ---------------------------------------------------------------------------
class TestNodeDirectLaunch:
    def test_argv_is_node_plus_the_playwright_cli_relative_to_frontend(self) -> None:
        argv = subject.build_child_argv()
        assert argv == ["node", "node_modules/@playwright/test/cli.js", "test", "-c",
                        "tests/production-e2e/playwright.step-b-5b.config.ts",
                        "--reporter=null", "--workers=1", "--retries=0"]
        assert argv[0] not in ("npx", "npm") and not any(a.startswith(("/", "\\")) or ":" in a for a in argv)

    def test_a_full_run_never_runs_npm_or_npx_and_spawns_node_in_frontend(self, h: Harness) -> None:
        assert h.run() == 0
        assert all(call["argv"][0] not in ("npx", "npm") for call in h.cmd.calls)
        (spawn,) = h.browser.spawn_calls
        assert spawn["argv"][:2] == ["node", "node_modules/@playwright/test/cli.js"] and spawn["cwd"] == h.frontend_dir
        assert (h.frontend_dir / spawn["argv"][1]).is_file()  # the relative path resolves from the working directory
        h.assert_no_unexpected()

    def test_a_missing_cli_entry_is_a_precondition_error(self, h: Harness) -> None:
        (h.frontend_dir / subject.PLAYWRIGHT_CLI_REL).unlink()
        assert h.run() == 2
        assert any("[PLAYWRIGHT_CLI_MISSING]" in line for line in h.err)
        assert h.server.calls == [] and h.prompts == []

    def test_the_tooling_check_runs_only_node_and_asserts_the_cli_file_on_disk(self, h: Harness) -> None:
        subject.check_tooling(h.config(), h.seams(), OS_ENVIRON)
        assert [c["argv"] for c in h.cmd.calls] == [
            ["node", "--version"], ["node", "-e", "require.resolve('@playwright/test')"],
            ["node", "-e", "require('fs').accessSync(require('playwright-core').chromium.executablePath())"],
        ]
        (h.frontend_dir / subject.PLAYWRIGHT_CLI_REL).unlink()
        with pytest.raises(subject.PreconditionError) as info:
            subject.check_tooling(h.config(), h.seams(), OS_ENVIRON)
        assert info.value.code == "PLAYWRIGHT_CLI_MISSING"
        assert "NPX_MISSING" not in Path(subject.__file__).read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# P4: the password prompt needs a console; EOF is exit 2 with a fixed message
# ---------------------------------------------------------------------------
class TestPasswordPromptSafety:
    def test_a_non_console_stdin_is_refused_before_getpass_is_ever_called(self, h: Harness) -> None:
        h.stdin_tty = False
        assert h.run() == 2
        assert any("[STDIN_NOT_A_TTY]" in line for line in h.err)
        assert h.prompts == [] and h.server.calls == [] and h.browser.spawn_calls == []
        assert not h.evidence_path.exists() and not h.work_dir.exists()

    def test_a_non_console_stdin_is_refused_before_anything_else_runs_not_only_before_getpass(self, h: Harness) -> None:
        """The refusal is the very first P0 check: no production root fetch (and none of the two 5 s
        sleeps between them), no git, no oracle, no tooling probe. Only then does the run stop."""
        h.stdin_tty = False
        assert h.run() == 2
        assert h.fetch.urls == [] and h.clock.sleeps == []  # no P1 fetch, no spacing sleep
        assert h.cmd.calls == []  # no git, oracle or node call either
        assert len(h.fetch.responses) == 4  # the scripted fetches were never consumed
        h.assert_no_unexpected()

    def test_the_refusal_is_raised_by_run_preconditions_itself(self, h: Harness) -> None:
        h.stdin_tty = False
        with pytest.raises(subject.PreconditionError) as info:
            subject.run_preconditions(h.config(), h.seams(), OS_ENVIRON, dt(RUN_START))
        assert info.value.code == "STDIN_NOT_A_TTY" and h.cmd.calls == []

    def test_the_prompt_helper_keeps_its_own_check_as_the_choke_point_before_getpass(self, h: Harness) -> None:
        h.stdin_tty = False
        with pytest.raises(subject.PreconditionError) as info:
            subject._prompt_secrets(h.config(), h.seams())
        assert info.value.code == "STDIN_NOT_A_TTY" and h.prompts == []

    def test_the_same_refusal_applies_to_cleanup_only(self, h: Harness) -> None:
        h.stdin_tty = False
        assert run_cleanup_only(h) == 2
        assert any("[STDIN_NOT_A_TTY]" in line for line in h.err) and h.prompts == [] and h.server.calls == []
        assert h.cmd.calls == []

    @pytest.mark.parametrize("mode", ["run", "cleanup_only"])
    @pytest.mark.parametrize("prompt_index", [0, 1])
    def test_eof_at_a_prompt_exits_two_with_a_fixed_message_and_consumes_nothing(
            self, h: Harness, mode: str, prompt_index: int) -> None:
        answers = iter([PASSWORD])

        def closed(prompt: str) -> str:
            h.prompts.append(prompt)
            if prompt_index == 0:
                raise EOFError
            try:
                return next(answers)
            except StopIteration:
                raise EOFError from None

        h.getpass_fn = closed  # type: ignore[method-assign]
        code = h.run(enable_internal_cleanup=True) if mode == "run" else run_cleanup_only(h, enable_internal_cleanup=True)
        assert code == 2
        assert h.err[-1] == "Input was closed before the login; nothing was consumed."
        assert h.server.calls == [] and h.browser.spawn_calls == [] and not h.evidence_path.exists()

    def test_the_real_stdin_seam_is_false_for_anything_that_is_not_a_console(self, monkeypatch: pytest.MonkeyPatch) -> None:
        class Console:
            def isatty(self) -> bool:
                return True

        class Closed:
            def isatty(self) -> bool:
                raise ValueError("I/O operation on closed file")

        monkeypatch.setattr(sys, "stdin", Console())
        assert subject._stdin_is_tty() is True
        monkeypatch.setattr(sys, "stdin", io.StringIO(""))
        assert subject._stdin_is_tty() is False
        monkeypatch.setattr(sys, "stdin", Closed())
        assert subject._stdin_is_tty() is False
        monkeypatch.setattr(sys, "stdin", None)
        assert subject._stdin_is_tty() is False
        assert subject.real_seams().stdin_isatty is subject._stdin_is_tty


# ---------------------------------------------------------------------------
# P5: UNC and device paths are refused
# ---------------------------------------------------------------------------
class TestUncAndDevicePaths:
    @pytest.mark.parametrize(
        "text",
        [
            r"\\server\share\evidence.json", "//server/share/evidence.json", r"\\?\C:\out\evidence.json",
            r"\\.\C:\out\evidence.json", "//?/C:/out/evidence.json", "//./C:/out/evidence.json",
            r"\??\C:\out\evidence.json",
        ],
    )
    def test_unc_and_device_forms_are_refused_with_a_fixed_code(self, tmp_path: Path, text: str) -> None:
        class AnyDirFs(subject.FileOps):
            def is_dir(self, path: Path) -> bool:  # even a "parent exists" answer must not save it
                return True

        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_evidence_paths(cfg(tmp_path, evidence_output=Path(text)), AnyDirFs())
        assert info.value.code == "EVIDENCE_PATH_UNC_OR_DEVICE"

    def test_a_local_drive_path_is_not_mistaken_for_unc(self, tmp_path: Path) -> None:
        assert not subject._is_unc_or_device_path(str(tmp_path / "evidence.json"))
        assert not subject._is_unc_or_device_path("C:\\out\\evidence.json")


# ---------------------------------------------------------------------------
# P6: sanitizer hardening
# ---------------------------------------------------------------------------
class Sha256Counter:
    def __init__(self, real: Callable[..., Any]) -> None:
        self.real, self.calls = real, 0

    def __call__(self, *args: Any, **kwargs: Any) -> Any:
        self.calls += 1
        return self.real(*args, **kwargs)


class TestSanitizerHardening:
    def test_basic_credentials_trip_their_own_class(self) -> None:
        assert "basic" in subject.scan_text("sent Basic dXNlcjpwYXNzd29yZA== to it")
        assert "basic" in subject.scan_text("BASIC   dXNlcjpwYXNzd29yZDoxMjM0NTY3")
        assert "basic" not in subject.scan_text("this is basic text and nothing else")

    def test_a_percent_encoded_jwt_collapses_to_the_raw_pattern(self) -> None:
        jwt_pattern = dict(subject._SCAN_PATTERNS)["jwt"]
        encoded = TOKEN.replace(".", "%2E")
        assert not jwt_pattern.search(encoded)  # the raw pattern alone misses it ...
        assert "jwt" in subject.scan_text("v " + encoded)  # ... the normalized copy catches it
        assert "jwt" in subject.scan_text("v " + TOKEN.replace(".", "%252E"))  # double encoding

    def test_a_newline_split_jwt_collapses_to_the_raw_pattern(self) -> None:
        jwt_pattern = dict(subject._SCAN_PATTERNS)["jwt"]
        split = TOKEN[:15] + "\n  " + TOKEN[15:]
        assert not jwt_pattern.search(split)
        assert "jwt" in subject.scan_text(split)
        assert "jwt" in subject.scan_text(json.dumps({"k": split}))  # the JSON-escaped newline form

    def test_a_json_unicode_escaped_dot_collapses_to_the_raw_pattern(self) -> None:
        escaped = TOKEN.replace(".", "\\u002e")
        assert not dict(subject._SCAN_PATTERNS)["jwt"].search(escaped)
        assert "jwt" in subject.scan_text('{"k": "' + escaped + '"}')

    @pytest.mark.parametrize("encoder", [base64.b64encode, base64.urlsafe_b64encode])
    def test_a_registered_token_is_found_in_its_base64_forms(self, encoder: Callable[[bytes], bytes]) -> None:
        encoded = encoder(TOKEN.encode()).decode()
        assert TOKEN not in encoded and "jwt" not in subject.scan_text(encoded)
        assert "secret_exact" in subject.scan_text("blob " + encoded, secrets=[TOKEN])
        assert "secret_exact" in subject.scan_text("blob " + encoded.rstrip("="), secrets=[TOKEN])

    def test_an_owner_secret_fingerprint_finds_the_base64_form_too(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        blob = "x " + base64.b64encode(PASSWORD.encode()).decode() + " y"
        assert "secret_exact" in subject.scan_text(blob, owner_fingerprints=registry.owner_fingerprints())

    def test_an_owner_secret_split_by_whitespace_in_the_text_is_found_in_the_collapsed_copy(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        split = PASSWORD[:8] + "\n" + PASSWORD[8:]
        assert "secret_exact" in subject.scan_text(split, owner_fingerprints=registry.owner_fingerprints())

    def test_basic_is_detected_in_raw_text_only_and_is_not_a_normalized_class(self) -> None:
        """The pattern needs whitespace between the scheme word and the credential, which the
        whitespace-free collapsed copy cannot contain, so the class is scanned on the raw text alone.
        The limitation is pinned so the claim (spec section 10.16) and the behaviour agree."""
        assert "basic" not in subject._NORMALIZED_SCAN_CLASSES
        basic = dict(subject._SCAN_PATTERNS)["basic"]
        credential = "dXNlcjpwYXNzd29yZA=="  # gitleaks:allow
        assert "basic" in subject.scan_text(f"sent Basic {credential} to it")  # raw text: detected
        for hidden in (f"Basic%20{credential.replace('=', '%3D')}", f"Basic\\n{credential}", f"Basic%0A{credential}"):
            assert "basic" not in subject.scan_text(hidden), hidden  # encoded or split: a documented gap
            assert basic.search(subject.normalized_copy(hidden)) is None, hidden  # the collapsed copy cannot match it

    SPACED = "Sup3r Secret Pw"  # a passphrase containing whitespace (fake)

    def _spaced_registry(self, secret: Optional[str] = None) -> subject.SecretRegistry:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(secret if secret is not None else self.SPACED)
        return registry

    @pytest.mark.parametrize(
        "make",
        [
            lambda s: "q=" + urllib.parse.quote(urllib.parse.quote(s, safe=""), safe=""),  # double percent-encoded
            lambda s: s.replace(" ", "\n"),  # split by a newline
            lambda s: s.replace(" ", "\r\n    "),  # split by a wrapped, indented line
            lambda s: s.replace(" ", "%20\n"),  # percent-encoded, then split
            lambda s: s.replace(" ", "\t"),
            lambda s: json.dumps({"k": s.replace(" ", "\t")}),  # JSON-escaped tab: only the collapsed copy sees it
        ],
        ids=["double-percent", "newline-split", "wrapped-split", "percent-then-split", "tab-split", "json-escaped-tab"],
    )
    def test_a_whitespace_containing_owner_secret_is_found_in_its_collapsed_forms(
            self, make: Callable[[str], str]) -> None:
        """The collapsed copy has no whitespace, so the secret's own spaces must not be what hides it."""
        text = make(self.SPACED)
        assert self.SPACED not in text
        assert "secret_exact" in subject.scan_text(text, owner_fingerprints=self._spaced_registry().owner_fingerprints())
        assert "secret_exact" in subject.scan_text(text, secrets=[self.SPACED])  # the raw-secret path too

    @pytest.mark.parametrize(
        "make",
        [
            lambda s: s,
            lambda s: urllib.parse.quote(s, safe=""),  # percent-encoded
            lambda s: urllib.parse.quote_plus(s),
            lambda s: base64.b64encode(s.encode()).decode(),  # base64
            lambda s: base64.urlsafe_b64encode(s.encode()).decode().rstrip("="),
            lambda s: "\n".join(textwrap.wrap(base64.b64encode(s.encode()).decode(), 6)),  # base64, line-wrapped
            lambda s: json.dumps({"k": s}),
        ],
        ids=["raw", "percent", "plus", "base64", "urlsafe-base64", "wrapped-base64", "json"],
    )
    def test_a_whitespace_containing_owner_secret_is_still_found_in_its_ordinary_forms(
            self, make: Callable[[str], str]) -> None:
        text = make(self.SPACED)
        assert "secret_exact" in subject.scan_text(text, owner_fingerprints=self._spaced_registry().owner_fingerprints())

    def test_whitespace_stripped_forms_are_fingerprints_only_and_respect_the_minimum_length(self) -> None:
        registry = self._spaced_registry()
        stripped = self.SPACED.replace(" ", "")
        assert stripped not in repr(vars(registry)) and stripped not in repr(registry.owner_fingerprints())
        assert subject.make_fingerprint(stripped) in registry.owner_fingerprints()
        short = self._spaced_registry("a b c d")  # 7 characters, but only 4 once stripped: below the minimum
        assert {f.length for f in short.owner_fingerprints()} >= {7}
        assert all(f.length >= subject.MIN_SCAN_SECRET_LENGTH for f in short.owner_fingerprints())
        assert subject.make_fingerprint("abcd") not in short.owner_fingerprints()

    def test_a_secret_without_whitespace_registers_no_whitespace_derived_form(self) -> None:
        forms = subject._secret_forms(PASSWORD)
        assert not any(re.search(r"\s", form) for form in forms) and PASSWORD in forms
        spaced = subject._secret_forms(self.SPACED)
        assert self.SPACED.replace(" ", "") in spaced and self.SPACED in spaced

    def test_near_misses_of_a_whitespace_containing_secret_do_not_trip(self) -> None:
        fingerprints = self._spaced_registry().owner_fingerprints()
        for near in ("Sup3rSecretPX", "Sup3r Secret Px", "Sup3r Secret P", "Sup3r  Secret", "sup3rsecretpw"):
            assert subject.scan_text(near, owner_fingerprints=fingerprints) == [], near

    def test_the_normalized_copy_is_only_used_for_credential_classes_so_words_never_glue_into_hosts(self) -> None:
        assert subject.scan_text(f"{FRONTEND} {API}") == []
        assert subject.scan_text("the site. In the run. App started. Dev mode. Co op.") == []
        collapsed = subject.normalized_copy("a b\n\tc %41 \\u0042 \\n")
        assert collapsed == "abcAB"

    def test_every_constant_artifact_text_scans_clean(self) -> None:
        for code, text in subject.STOP_TEXT.items():
            assert subject.scan_text(text) == [], code

    def test_a_large_clean_text_costs_no_sha256_computations(self, monkeypatch: pytest.MonkeyPatch) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        registry.add_owner_secret(INTERNAL_KEY)
        fingerprints = registry.owner_fingerprints()
        counter = Sha256Counter(hashlib.sha256)
        monkeypatch.setattr(subject.hashlib, "sha256", counter)
        assert subject.fingerprint_hit("a" * 50_000, fingerprints) is False
        # a window is hashed only when its CRC-32 matches: a clean text is ~0, never one per window
        assert counter.calls <= 3, counter.calls

    @pytest.mark.parametrize("position", ["start", "middle", "end"])
    def test_a_secret_is_still_found_anywhere_in_a_large_text_with_few_hashes(
            self, monkeypatch: pytest.MonkeyPatch, position: str) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        registry.add_owner_secret(INTERNAL_KEY)
        fingerprints = registry.owner_fingerprints()
        filler = "a" * 25_000
        text = {"start": PASSWORD + filler * 2, "middle": filler + PASSWORD + filler, "end": filler * 2 + INTERNAL_KEY}[position]
        counter = Sha256Counter(hashlib.sha256)
        monkeypatch.setattr(subject.hashlib, "sha256", counter)
        assert subject.fingerprint_hit(text, fingerprints) is True
        assert counter.calls <= 3, counter.calls

    def test_fingerprint_hit_ignores_a_near_miss_with_the_same_crc_bucket_shape(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        near = PASSWORD[:-1] + ("X" if PASSWORD[-1] != "X" else "Y")
        assert subject.fingerprint_hit("zz" + near + "zz", registry.owner_fingerprints()) is False

    def test_fingerprints_carry_only_length_hashes_and_never_the_secret(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        for fingerprint in registry.owner_fingerprints():
            assert isinstance(fingerprint, subject.Fingerprint)
            assert PASSWORD not in repr(fingerprint) and len(fingerprint.sha256) == 64
        assert {f.length for f in registry.owner_fingerprints()} >= {len(PASSWORD)}


class CountingMatch:
    """Wraps a compiled pattern and counts how often it is applied."""

    def __init__(self, pattern: "re.Pattern[str]") -> None:
        self.pattern, self.calls = pattern, 0

    def match(self, *args: Any) -> Any:
        self.calls += 1
        return self.pattern.match(*args)


class TestLinearTimeScanning:
    """The reviewer's ~70 s probe (a 200 KB run of one character) was dominated by two whole-text
    regexes (email and URL host) that retry at every position; they are now applied only at `@`
    and `://` sites. Pinned structurally (how often the pattern runs), never by wall clock."""

    def test_the_site_patterns_run_once_per_site_and_never_per_character(self, monkeypatch: pytest.MonkeyPatch) -> None:
        url, email = CountingMatch(subject._URL_HOST_AT_RE), CountingMatch(subject._EMAIL_DOMAIN_AT_RE)
        monkeypatch.setattr(subject, "_URL_HOST_AT_RE", url)
        monkeypatch.setattr(subject, "_EMAIL_DOMAIN_AT_RE", email)
        assert subject.scan_text("a" * 20_000) == [] and (url.calls, email.calls) == (0, 0)
        assert subject.scan_text("1a" * 10_000) == [] and (url.calls, email.calls) == (0, 0)
        # three URL sites (every one is examined) and three '@' sites none of which is an email
        # (so none short-circuits the scan): one pattern application per site
        tripped = subject.scan_text("x https://a.example.org/1 y https://b.example.org/2 z https://c.example.org/3 m1@ m2@ m3@")
        assert (url.calls, email.calls) == (3, 3) and "host" in tripped and "email" not in tripped

    def test_the_reviewers_probe_shape_scans_clean_with_two_owner_secrets_registered(self) -> None:
        registry = subject.SecretRegistry()
        registry.add_owner_secret(PASSWORD)
        registry.add_owner_secret(INTERNAL_KEY)
        assert subject.scan_text("a" * 60_000, owner_fingerprints=registry.owner_fingerprints()) == []

    @pytest.mark.parametrize(
        "text,expected",
        [
            ("someone@example.org", True), ("write to someone@example.org now", True), ("@example.org", False),
            (" @example.org", False), ("someone@example", False), ("someone@example.o", False),
            ("a@b@example.org", True), ("some one@example.org", True), ("x@" + "a" * 5000, False),
            ("a" * 5000 + "@", False), ("no at sign here", False), ("%+-@a.bc", True),
        ],
    )
    def test_email_detection(self, text: str, expected: bool) -> None:
        assert subject._has_email(text) is expected

    @pytest.mark.parametrize(
        "text,hosts",
        [
            ("see https://evil.example.net/x", ["evil.example.net"]),
            ("https://user:pw@evil.example.net:8443/p?q#f", ["evil.example.net"]),  # userinfo, port, path dropped
            ("https://vibhanshu-ai-portfolio.dev@evil.com/x", ["evil.com"]),  # the userinfo trick
            ("https://evil.com@vibhanshu-ai-portfolio.dev/x", ["vibhanshu-ai-portfolio.dev"]),
            ("1abc://evil.com", ["evil.com"]),  # a scheme whose run starts with a digit but has a letter
            ("a+b.c-d://h.example.org", ["h.example.org"]),
            ("x://a.example.org y://b.example.org", ["a.example.org", "b.example.org"]),
            ("https://a.example.org://b.example.org", ["a.example.org", "b.example.org"]),  # overlapping site: stricter
            ("://evil.com", []), ("11://evil.com", []), ("no url", []), ("https://", []),
        ],
    )
    def test_url_host_extraction(self, text: str, hosts: list[str]) -> None:
        assert subject._url_hosts(text) == hosts

    def test_the_userinfo_trick_and_foreign_hosts_still_trip_the_scan(self) -> None:
        assert "host" in subject.scan_text("https://vibhanshu-ai-portfolio.dev@evil.example.net/x")
        assert "host" in subject.scan_text("see 1abc://evil.example.net/x")
        assert "email" in subject.scan_text("write to someone@example.org")
        assert subject.scan_text(f"{FRONTEND}/x {API}/api/portfolio {subject.DEMO_EMAIL}") == []


# ---------------------------------------------------------------------------
# P7: the whole post-child phase is shielded from a second Ctrl+C
# ---------------------------------------------------------------------------
class TestSigintShield:
    def test_a_second_ctrl_c_between_the_child_ending_and_cleanup_starting_cannot_skip_cleanup(
            self, h: Harness) -> None:
        h.browser.interrupt = True  # first Ctrl+C: during the child wait
        h.browser.server_effect = "dirty"
        h.sigint.press_on = "P5 independent cleanup"  # second Ctrl+C: the instant cleanup begins
        with pytest.raises(KeyboardInterrupt):  # the FIRST interrupt is still re-raised at the very end
            h.run()
        assert h.sigint.ignored == 1 and h.sigint.delivered == 0
        art = h.artifact()
        assert art["cleanup"]["result"] == "confirmed" and server_paths(h, "PUT") == ["/api/portfolio/demo-reset"]
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "INTERRUPTED")

    def test_the_model_would_deliver_that_press_if_cleanup_were_not_shielded(self, h: Harness) -> None:
        """Control for the model itself: outside a deferral the same press does interrupt."""
        h.sigint.press_on = "hello"
        with pytest.raises(KeyboardInterrupt):
            h.sigint.at_output("hello world")
        h.sigint.press_on = "hello"
        with h.sigint.defer():
            h.sigint.at_output("hello world")
        assert h.sigint.delivered == 1 and h.sigint.ignored == 1

    def test_the_shield_is_released_after_cleanup_so_the_owner_wait_stays_interruptible(self, h: Harness) -> None:
        depths: list[int] = []
        original = h.input_fn

        def spying_input(prompt: str) -> str:
            depths.append(h.sigint.depth)
            return original(prompt)

        h.input_fn = spying_input  # type: ignore[method-assign]
        assert h.run() == 0
        assert depths == [0]

    @pytest.mark.parametrize(
        "where,name",
        [("output", "P4 browser child finished"), ("ledger", "child_done")],
        ids=["first-statement-after-the-child-phase-returns", "the-child_done-ledger-event"],
    )
    def test_a_press_at_the_instant_the_child_phase_ends_is_ignored(self, h: Harness, where: str, name: str) -> None:
        """Round 2 (Q6): the shield used to start only after `guarded(...)` returned, leaving the
        instants between the child's death and the deferral unshielded. Both hooks below sit inside that
        gap: the orchestrator's child_done ledger line and the first statement after the child phase
        returns. A press there must now be ignored and must not disturb the run."""
        if where == "output":
            h.sigint.press_on = name
        else:
            h.fs = LedgerEventHookFs(h.sigint.at_event)
            h.sigint.press_on_event = name
        try:
            code = h.run()
        except KeyboardInterrupt:  # a delivered press must fail THIS test, not abort the whole pytest session
            pytest.fail("the Ctrl+C was delivered instead of being ignored by the deferral")
        assert code == 0  # nothing was interrupted: the press was swallowed by the deferral
        assert h.sigint.ignored == 1 and h.sigint.delivered == 0 and h.sigint.depth == 0
        art = h.artifact()
        assert (art["verdict"], art["child"]["state"]) == ("GO", "exit_zero")
        assert h.browser.handles[0].closed  # the finally block ran to its end (the handle was closed)

    def test_a_press_while_the_child_runs_still_reaches_the_child_loop(self, h: Harness) -> None:
        """The control: the deferral must NOT be held during the child phase, or the owner could not stop it."""
        h.browser.interrupt = True
        with pytest.raises(KeyboardInterrupt):
            h.run()
        assert h.browser.handles[0].killed and h.artifact()["child"]["state"] == "interrupted"
        assert h.sigint.depth == 0

    def test_the_deferral_is_held_from_child_done_through_cleanup_and_released_afterwards(
            self, h: Harness) -> None:
        events: list[tuple[str, int]] = []
        h.fs = LedgerEventHookFs(lambda name: events.append((name, h.sigint.depth)))
        assert h.run() == 0
        # the orchestrator's events in order: the write-ahead cleanup_armed marker, child_started, child_done, then cleanup
        assert [name for name, _ in events[:3]] == ["cleanup", "child_started", "child_done"]
        assert [depth for _, depth in events[:3]] == [0, 0, 1]  # unshielded while the child may run, shielded from child_done
        assert len(events) >= 5 and all(depth >= 1 for _, depth in events[3:])  # every cleanup event is inside the deferral
        assert h.sigint.depth == 0  # ... and everything is released once cleanup has finished

    def _phase(self, tmp_path: Path, child: Optional[FakeChild], *, deadline_in: float = 2700.0,
               spawn_error: Optional[Exception] = None, clock: Optional[Clock] = None):
        clock = clock or Clock()
        (tmp_path / "w").mkdir(exist_ok=True)
        model = SigintModel()
        depths: list[tuple[str, int]] = []

        class DepthLedger(subject.Ledger):
            def event(self, name: str, *, critical: bool = False, **fields: Any) -> None:
                depths.append((name, model.depth))
                super().event(name, critical=critical, **fields)

        def spawn(argv: list[str], env: Any, cwd: Path, log_path: Path) -> Any:
            if spawn_error is not None:
                raise spawn_error
            return child

        seams = dataclasses.replace(child_seams(clock, spawn), defer_sigint=model.defer)
        config = subject.RunConfig(evidence_output=tmp_path / "e.json", baseline_commit=BASELINE,
                                   frontend_dir=tmp_path / "frontend")
        ledger, outcome, stack = DepthLedger(subject.FileOps(), None, clock.now_utc), subject.ChildOutcome(), contextlib.ExitStack()

        def run() -> None:
            subject.run_browser_child(config, seams, ledger, {"K": "v"}, clock.mono + deadline_in, outcome, stack)

        return model, depths, outcome, stack, run

    @pytest.mark.parametrize(
        "scenario",
        ["exit_zero", "exit_nonzero", "deadline_kill", "kill_unconfirmed", "interrupted", "interrupted_unconfirmed", "spawn_failed"],
    )
    def test_run_browser_child_enters_the_deferral_once_when_the_child_phase_ends_on_every_path(
            self, tmp_path: Path, scenario: str) -> None:
        clock = Clock()
        child: Optional[FakeChild] = {
            "exit_zero": FakeChild(clock, 10.0, 0), "exit_nonzero": FakeChild(clock, 5.0, 1),
            "deadline_kill": FakeChild(clock, 0.0, 0, never_exit=True),
            "kill_unconfirmed": FakeChild(clock, 0.0, 0, never_exit=True, survive_kill=True),
            "interrupted": FakeChild(clock, 10.0, 0, interrupt=1),
            "interrupted_unconfirmed": FakeChild(clock, 10.0, 0, interrupt=1, never_exit=True, survive_kill=True),
            "spawn_failed": None,
        }[scenario]
        model, depths, outcome, stack, run = self._phase(
            tmp_path, child, deadline_in=30.0, clock=clock,
            spawn_error=FileNotFoundError("node") if scenario == "spawn_failed" else None)
        if scenario.startswith("interrupted"):
            with pytest.raises(KeyboardInterrupt):
                run()
        else:
            run()
        assert depths[0] == ("child_started", 0) and depths[-1][0] == "child_done"
        assert depths[-1][1] == 1  # the deferral was entered before child_done was written, exactly once
        assert model.depth == 1 and model.max_depth == 1  # ... and is still held: the caller closes it after cleanup
        if child is not None:
            assert child.closed
        stack.close()
        assert model.depth == 0

    def test_a_press_at_cleanup_start_is_ignored_even_when_the_child_phase_failed_before_it_could_shield(
            self, h: Harness, monkeypatch: pytest.MonkeyPatch) -> None:
        """An armed run whose child never started (here: the child environment could not be built) has no
        child_done to hang the deferral on, so run_verifier enters it itself before cleanup."""
        def refuse(*args: Any, **kwargs: Any) -> Any:
            raise RuntimeError("an owner secret reached the child environment")

        monkeypatch.setattr(subject, "build_child_env", refuse)
        h.sigint.press_on = "P5 independent cleanup"
        try:
            code = h.run()
        except KeyboardInterrupt:  # fail this test, never abort the pytest session
            pytest.fail("the Ctrl+C was delivered at cleanup start instead of being ignored")
        assert code == 1  # NON_GO (UNEXPECTED_ERROR), not an escaped KeyboardInterrupt
        assert h.sigint.ignored == 1 and h.sigint.delivered == 0 and h.sigint.depth == 0
        art = h.artifact()
        assert art["stop_reason"]["code"] == "UNEXPECTED_ERROR" and art["cleanup"]["result"] == "not_needed"
        assert h.browser.spawn_calls == []

    def test_without_a_shield_stack_run_browser_child_defers_nothing(self, tmp_path: Path) -> None:
        clock, model = Clock(), SigintModel()
        child = FakeChild(clock, 5.0, 0)
        seams = dataclasses.replace(child_seams(clock, lambda *a: child), defer_sigint=model.defer)
        config = subject.RunConfig(evidence_output=tmp_path / "e.json", baseline_commit=BASELINE,
                                   frontend_dir=tmp_path / "frontend")
        subject.run_browser_child(config, seams, subject.Ledger(subject.FileOps(), None, clock.now_utc), {"K": "v"},
                                  clock.mono + 100.0, subject.ChildOutcome())
        assert model.max_depth == 0 and child.closed

    def test_deferrals_on_one_exit_stack_restore_the_original_signal_handler_in_lifo_order(self) -> None:
        """No hang and no double-shield: the child phase, the run-with-no-child fallback and the cleanup's own
        deferral all sit on (or inside) one ExitStack, so the original SIGINT handler always comes back."""
        before = signal.getsignal(signal.SIGINT)
        stack = contextlib.ExitStack()
        stack.enter_context(subject.defer_sigint())  # run_browser_child, the moment the child phase ends
        stack.enter_context(subject.defer_sigint())  # run_verifier, for runs that never started a child
        with subject.defer_sigint():  # _run_cleanup's own shield
            assert signal.getsignal(signal.SIGINT) == signal.SIG_IGN
        assert signal.getsignal(signal.SIGINT) == signal.SIG_IGN  # still held until the stack closes
        stack.close()
        assert signal.getsignal(signal.SIGINT) == before

    def test_the_stack_is_closed_even_when_something_escapes_between_the_child_phase_and_cleanup(
            self, h: Harness, monkeypatch: pytest.MonkeyPatch) -> None:
        real, calls = subject.guarded, {"n": 0}

        def guarded_that_lets_the_cleanup_call_escape(state: Any, fn: Any) -> Any:
            calls["n"] += 1
            if calls["n"] == 2:  # the _run_cleanup call
                raise _RecorderExhausted("escaped between the child phase and cleanup")
            return real(state, fn)

        monkeypatch.setattr(subject, "guarded", guarded_that_lets_the_cleanup_call_escape)
        with pytest.raises(_RecorderExhausted):
            h.run()
        assert h.sigint.max_depth >= 1 and h.sigint.depth == 0  # the finally closed the stack: no lingering SIG_IGN

    @pytest.mark.parametrize("mode", ["go", "child_interrupt", "unexpected_error", "no_child_slow_login"])
    def test_the_deferral_is_always_balanced_when_the_run_returns(self, h: Harness, mode: str) -> None:
        if mode == "child_interrupt":
            h.browser.interrupt = True
        elif mode == "unexpected_error":
            h.fs = FlakyFs(fail_ledger_appends=True)
        elif mode == "no_child_slow_login":
            h.server.login_seconds = 170.0
        try:
            h.run()
        except KeyboardInterrupt:
            assert mode == "child_interrupt"
        assert h.sigint.depth == 0


# ---------------------------------------------------------------------------
# P8: owner-only work directory and child log
# ---------------------------------------------------------------------------
class TestOwnerOnlyWorkFiles:
    def test_the_work_directory_is_created_with_mode_0700(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        calls: list[tuple[Path, int]] = []
        real = os.mkdir

        def recording(path: Any, mode: int = 0o777, **kwargs: Any) -> None:
            calls.append((Path(path), mode))
            real(path, mode, **kwargs)

        monkeypatch.setattr(os, "mkdir", recording)
        subject.FileOps().mkdir_exclusive(tmp_path / "w")
        assert calls == [(tmp_path / "w", 0o700)]
        if os.name != "nt":
            assert stat.S_IMODE((tmp_path / "w").stat().st_mode) == 0o700

    def test_the_child_log_is_created_owner_only_and_the_child_is_isolated(
            self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        opened: list[tuple[Path, int, int]] = []
        real_open = os.open

        def recording_open(path: Any, flags: int, mode: int = 0o777, **kwargs: Any) -> int:
            opened.append((Path(path), flags, mode))
            return real_open(path, flags, mode, **kwargs)

        popen_calls: list[dict[str, Any]] = []

        class FakePopen:
            def __init__(self, argv: list[str], **kwargs: Any) -> None:
                popen_calls.append({"argv": argv, **kwargs})
                self.pid = 4242

            def poll(self) -> None:
                return None

        monkeypatch.setattr(os, "open", recording_open)
        monkeypatch.setattr(subject.subprocess, "Popen", FakePopen)
        monkeypatch.setattr(subject.shutil, "which", lambda name, path=None: "/fake/node")
        log_path = tmp_path / "child.log"
        handle = subject.make_spawn_child(["node", "cli.js"], {"PATH": "p"}, tmp_path, log_path)
        try:
            assert [(p, m) for p, _, m in opened if p == log_path] == [(log_path, 0o600)]
            flags = next(f for p, f, _ in opened if p == log_path)
            assert flags & os.O_CREAT and flags & os.O_APPEND and flags & os.O_WRONLY
            if os.name != "nt":
                assert stat.S_IMODE(log_path.stat().st_mode) == 0o600
            (call,) = popen_calls
            platform_kwargs = subject._popen_platform_kwargs(os.name == "nt")
            assert all(call[key] == value for key, value in platform_kwargs.items())
            assert call["stdin"] is subprocess.DEVNULL and call["stderr"] == subprocess.STDOUT
            assert not call["stdout"].closed and call["stdout"].mode == "ab"
        finally:
            handle.close()

    def test_platform_kwargs_isolate_the_child(self) -> None:
        assert subject._popen_platform_kwargs(True) == {"creationflags": 0x00000200}  # CREATE_NEW_PROCESS_GROUP
        assert subject._popen_platform_kwargs(False) == {"start_new_session": True}
        if hasattr(subprocess, "CREATE_NEW_PROCESS_GROUP"):
            assert subprocess.CREATE_NEW_PROCESS_GROUP == 0x00000200


# ---------------------------------------------------------------------------
# P9: a kill must be confirmed; otherwise a hard NON_GO (a mutator may still run)
# ---------------------------------------------------------------------------
class TestKillConfirmation:
    def _run(self, tmp_path: Path, child: FakeChild, clock: Clock, *, deadline_in: float = 2700.0):
        return TestChildRunner()._run(tmp_path, child, clock=clock, deadline_in=deadline_in)

    def test_a_child_that_survives_the_deadline_kill_is_kill_unconfirmed(self, tmp_path: Path) -> None:
        clock = Clock()
        child = FakeChild(clock, 0.0, 0, never_exit=True, survive_kill=True)
        clock, ledger, outcome, _, run = self._run(tmp_path, child, clock, deadline_in=120.0)
        run()
        assert child.kill_calls == 1 and outcome.state == "kill_unconfirmed" and outcome.exit_code == -1
        assert sum(clock.sleeps) == pytest.approx(120.0 + subject.CHILD_KILL_GRACE_SECONDS, abs=1.5)  # polled the grace out
        assert child.closed and ledger.events[-1]["event"] == "child_done" and ledger.events[-1]["exit"] == -1

    def test_a_child_that_dies_during_the_grace_period_is_an_ordinary_deadline_kill(self, tmp_path: Path) -> None:
        clock = Clock()

        class SlowToDie(FakeChild):
            def poll(self) -> Optional[int]:
                if self.killed and self.clock.mono >= self.died_at:
                    return -9
                return None

            def kill_tree(self) -> None:
                super().kill_tree()
                self.died_at = self.clock.mono + 4.0

        child = SlowToDie(clock, 0.0, 0, never_exit=True)
        clock, _, outcome, _, run = self._run(tmp_path, child, clock, deadline_in=60.0)
        run()
        assert outcome.state == "deadline_killed" and outcome.exit_code == -9

    def test_an_interrupt_with_a_child_that_survives_the_kill_is_kill_unconfirmed(self, tmp_path: Path) -> None:
        clock = Clock()
        child = FakeChild(clock, 10.0, 0, interrupt=1, never_exit=True, survive_kill=True)
        clock, ledger, outcome, _, run = self._run(tmp_path, child, clock)
        with pytest.raises(KeyboardInterrupt):
            run()
        assert outcome.state == "kill_unconfirmed" and child.killed and child.closed
        assert ledger.events[-1]["event"] == "child_done"

    def test_a_second_interrupt_while_confirming_the_kill_never_reads_as_dead(self, tmp_path: Path) -> None:
        clock = Clock()
        child = FakeChild(clock, 10.0, 0, interrupt=2)  # Ctrl+C in the wait loop, then again in the confirmation
        clock, _, outcome, _, run = self._run(tmp_path, child, clock)
        with pytest.raises(KeyboardInterrupt):
            run()
        assert child.killed and outcome.state == "kill_unconfirmed"

    def test_kill_unconfirmed_is_a_hard_non_go_even_when_everything_else_is_clean(self) -> None:
        verdict = subject.compute_verdict(base(child_state="kill_unconfirmed"))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "CHILD_KILL_UNCONFIRMED")
        assert "CHILD_KILL_UNCONFIRMED" in subject.STOP_TEXT and "CHILD_KILL_UNCONFIRMED" in subject._CHILD_STATE_CODES.values()

    def test_it_is_a_failure_even_without_an_expected_child(self) -> None:
        quiet = dict(child_state="kill_unconfirmed", baseline_state="not_golden", cleanup_armed=False,
                     cleanup_result="skipped_no_mutation")
        assert subject.compute_verdict(base(**quiet)).verdict == "NON_GO"

    def test_end_to_end_the_run_is_non_go_and_cleanup_is_not_reported_clean(self, h: Harness) -> None:
        h.browser.never_exit = True
        h.browser.survive_kill = True
        h.browser.lines = []
        h.browser.server_effect = "untouched"  # the portfolio is golden when cleanup looks
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "CHILD_KILL_UNCONFIRMED")
        assert art["child"]["state"] == "kill_unconfirmed" and art["gate_map"]["run_result"] == "not_all_items_passed"
        assert art["cleanup"]["result"] == "unconfirmed" and art["cleanup"]["golden_confirmed"] is False
        assert art["cleanup"]["detail_code"] == "CHILD_KILL_UNCONFIRMED"
        assert "CLEANUP_UNCONFIRMED" in art["stop_reason"]["failed_criteria"]
        assert subject.parse_ledger((h.work_dir / "ledger.jsonl").read_bytes(), CONTRACT).clean
        assert h.ledger_events()[-1]["result"] == "unconfirmed"


# ---------------------------------------------------------------------------
# P12: build id from the flight payload's ROOT row
# ---------------------------------------------------------------------------
class TestBuildIdRootAnchor:
    def test_the_real_page_shape_yields_the_root_rows_trailing_b_key(self) -> None:
        html = flight_html('1:"$Sreact.fragment"\n2:I[18359,["/_next/static/chunks/a.js"],"default"]\n', REAL_ROOT_ROW % "RealShapeBuildId12345")
        assert subject.extract_build_id(html) == "RealShapeBuildId12345"

    def test_a_b_key_nested_elsewhere_is_not_a_build_id_and_cannot_cause_ambiguity(self) -> None:
        row = '0:{"P":null,"f":[[{"b":"DecoyDecoy123"}]],"S":true,"b":"TheRootBuildId1"}\n'
        assert subject.extract_build_id(flight_html(row)) == "TheRootBuildId1"

    def test_a_b_key_in_a_non_root_row_is_ignored(self) -> None:
        with pytest.raises(subject.BindingError) as info:
            subject.extract_build_id(flight_html('6:{"b":"NotTheRootRow1"}\n'))
        assert info.value.code == "BUILD_ID_NOT_FOUND"

    def test_a_bare_json_b_key_outside_the_payload_is_not_a_build_id(self) -> None:
        with pytest.raises(subject.BindingError) as info:
            subject.extract_build_id('<script>{"b":"AbCdEfGhIjKlMnOpQrStU","p":""}</script>')
        assert info.value.code == "BUILD_ID_NOT_FOUND"

    def test_a_root_row_split_across_two_pushes_is_reassembled(self) -> None:
        first, second = '0:{"P":null,"b":"SplitAcrossChunk1"', ',"c":[]}\n'
        assert subject.extract_build_id(flight_html(first, second)) == "SplitAcrossChunk1"

    def test_a_short_or_malformed_root_id_is_not_a_candidate(self) -> None:
        for row in ('0:{"b":"short"}\n', '0:{"b":5}\n', '0:{"b":"has space in it"}\n', '0:{"b":\n'):
            with pytest.raises(subject.BindingError):
                subject.extract_build_id(flight_html(row))

    def test_ambiguous_root_ids_fall_back_to_the_unique_static_directory_id_that_is_one_of_them(self) -> None:
        html = flight_html('0:{"b":"AbCdEfGh1"}\n0:{"b":"ZzZzZzZz2"}\n', extra='<script src="/_next/static/ZzZzZzZz2/_buildManifest.js"></script>')
        assert subject.extract_build_id(html) == "ZzZzZzZz2"

    @pytest.mark.parametrize(
        "extra",
        [
            "",  # no static directory candidate at all
            '<script src="/_next/static/AbCdEfGh1/a.js"></script><script src="/_next/static/ZzZzZzZz2/b.js"></script>',
            '<script src="/_next/static/QqQqQqQq3/a.js"></script>',  # unique, but neither of the root ids
        ],
        ids=["no-static", "two-static", "static-names-neither"],
    )
    def test_ambiguous_root_ids_without_a_usable_tiebreak_fail_closed(self, extra: str) -> None:
        html = flight_html('0:{"b":"AbCdEfGh1"}\n0:{"b":"ZzZzZzZz2"}\n', extra=extra)
        with pytest.raises(subject.BindingError) as info:
            subject.extract_build_id(html)
        assert info.value.code == "BUILD_ID_AMBIGUOUS"

    def test_a_single_root_id_that_disagrees_with_the_static_directory_still_fails_closed(self) -> None:
        html = flight_html('0:{"b":"AbCdEfGh1"}\n', extra='<script src="/_next/static/ZzZzZzZz2/a.js"></script>')
        with pytest.raises(subject.BindingError) as info:
            subject.extract_build_id(html)
        assert info.value.code == "BUILD_ID_AMBIGUOUS"

    def test_the_snapshot_path_uses_the_root_anchored_id(self) -> None:
        html = flight_html(REAL_ROOT_ROW % "RealShapeBuildId12345").encode()
        snapshot = subject.fetch_frontend_snapshot(FetchScript([(200, "text/html", html)]), FRONTEND, 30.0)
        assert snapshot.build_id == "RealShapeBuildId12345"


# ---------------------------------------------------------------------------
# P14 / P11 / P18: derived evidence levels, the attestation literal, distinct 'armed' names
# ---------------------------------------------------------------------------
class TestSmallContractFixes:
    def test_every_route_level_equals_its_legs_level_so_the_artifact_cannot_disagree_with_itself(self) -> None:
        for route, (leg, level, _) in subject.ROUTE_TABLE.items():
            assert subject.LEG_EVIDENCE_LEVELS[leg] == level, route
        for leg, route in subject.LEG_ROUTES.items():
            if route not in subject.ROUTE_TABLE:
                assert subject.LEG_EVIDENCE_LEVELS[leg] == subject.DEFAULT_LEG_EVIDENCE_LEVEL, leg
        assert subject.LEG_EVIDENCE_LEVELS["L5"] == "production-browser (network status and boolean)"
        assert set(subject.LEG_EVIDENCE_LEVELS) == set(subject.LEG_ROUTES)

    def test_the_artifact_states_one_evidence_level_per_route(self, h: Harness) -> None:
        h.run()
        art = h.artifact()
        by_id = {leg["id"]: leg for leg in art["legs"]}
        for route, entry in art["routes"].items():
            assert by_id[entry["leg"]]["evidence_level"] == entry["evidence_level"], route

    def test_the_deploy_time_provenance_literal_is_owner_attested(self, h: Harness) -> None:
        h.run()
        assert h.artifact()["deploy_completed_utc_source"] == "owner_attested"

    def test_the_orchestrator_marker_is_named_cleanup_armed_and_the_specs_armed_event_keeps_its_name(self) -> None:
        assert "cleanup_armed" in subject.CLEANUP_STEP_RESULTS and "armed" not in subject.CLEANUP_STEP_RESULTS
        assert "armed" in CONTRACT.spec_events
        assert not parse(orch(1, "cleanup", attempt=0, transport="none", result="armed")).clean
        assert parse(orch(1, "cleanup", attempt=0, transport="none", result="cleanup_armed")).clean
        assert "armed_before_mutation" not in {f.name for f in dataclasses.fields(subject.VerdictInputs)}
        assert "spec_armed_before_l8" in {f.name for f in dataclasses.fields(subject.VerdictInputs)}

    def test_the_spec_armed_line_alone_gates_the_verdict_the_cleanup_marker_cannot_stand_in_for_it(self) -> None:
        # cleanup is armed and confirmed in base(); only the spec's own armed line is missing
        verdict = subject.compute_verdict(base(spec_armed_before_l8=False, cleanup_armed=True, cleanup_result="confirmed"))
        assert (verdict.verdict, verdict.code) == ("NON_GO", "ARMED_MISSING")


# ---------------------------------------------------------------------------
# P16: owner-supplied identifiers are scanned at P0
# ---------------------------------------------------------------------------
class TestOwnerIdentifierScan:
    @pytest.mark.parametrize(
        "over,cls",
        [
            (dict(deploy_run_id="evil.example.com"), "host"),
            (dict(source_head_sha="10.20.30.40"), "ip_address"),
            (dict(source_head_sha="11111111-2222-3333-4444-555555555555"), "guid"),
            (dict(pre_deploy_build_id="password-build-1"), "password"),
        ],
    )
    def test_an_identifier_that_would_trip_the_final_scan_is_refused_before_the_login(
            self, h: Harness, over: dict[str, Any], cls: str) -> None:
        assert h.run(**over) == 2
        (line,) = [x for x in h.err if "[OWNER_ATTESTED_ID_SCAN_TRIPPED]" in x]
        assert cls in line
        for value in over.values():
            assert value not in "\n".join(h.err + h.out)  # the offending value is never echoed
        assert h.server.calls == [] and h.prompts == [] and h.browser.spawn_calls == []
        assert not h.evidence_path.exists() and not h.work_dir.exists()

    def test_an_attestation_revision_that_would_trip_the_scan_is_refused_before_the_login(self, h: Harness) -> None:
        (h.tmp / "attestation-pre.json").write_text(
            attestation_json(PRE_ATTESTATION_AT, portfolio_revision="rev.evil.example.com"), encoding="utf-8")
        assert h.run() == 2
        assert any("[ATTESTATION_PRE_SCAN_TRIPPED]" in x and "portfolio-service" in x for x in h.err)
        assert "rev.evil.example.com" not in "\n".join(h.err + h.out)
        assert h.server.calls == [] and h.prompts == [] and not h.work_dir.exists()

    def test_ordinary_identifiers_pass_the_scan(self, h: Harness) -> None:
        assert h.run(deploy_run_id="1234567890", source_head_sha=SOURCE_SHA) == 0


# ---------------------------------------------------------------------------
# P17: an unsealed fallback copy is never a GO
# ---------------------------------------------------------------------------
class TestUnsealedFallback:
    def _go_document(self) -> dict[str, Any]:
        return {"schema": subject.EVIDENCE_SCHEMA, "verdict": "GO",
                "stop_reason": {"code": "NONE", "text": "all exit criterion 5b conditions held", "failed_criteria": []},
                "start_utc": "2026-09-20T12:00:00Z", "end_utc": "2026-09-20T12:30:00Z",
                "cleanup": {"result": "confirmed", "golden_confirmed": True},
                "gate_map": {"step_a_credited": False, "run_result": "all_items_passed"}}

    def test_the_fallback_copy_is_non_go_with_the_path_unavailable_code(self, tmp_path: Path) -> None:
        (tmp_path / "out.json").write_text("precious", encoding="utf-8")
        document = self._go_document()
        written = subject.finalize_artifact(document, kind="evidence", secrets=[PASSWORD], fs=subject.FileOps(),
                                            path=tmp_path / "out.json", fallback_path=tmp_path / "fb.json")
        copy_ = json.loads((tmp_path / "fb.json").read_text(encoding="utf-8"))
        assert written.fallback_used and written.data == (tmp_path / "fb.json").read_bytes()
        assert copy_["verdict"] == "NON_GO" and copy_["stop_reason"]["code"] == "ARTIFACT_PATH_UNAVAILABLE"
        assert copy_["stop_reason"]["failed_criteria"] == ["ARTIFACT_PATH_UNAVAILABLE"]
        assert copy_["stop_reason"]["text"] == subject.STOP_TEXT["ARTIFACT_PATH_UNAVAILABLE"]
        assert copy_["gate_map"] == {"step_a_credited": False, "run_result": "not_all_items_passed"}
        assert document["verdict"] == "GO"  # the caller's document is never mutated
        assert (tmp_path / "out.json").read_text(encoding="utf-8") == "precious"

    def test_other_stop_reason_details_and_criteria_are_kept(self, tmp_path: Path) -> None:
        (tmp_path / "out.json").write_text("precious", encoding="utf-8")
        document = self._go_document()
        document["verdict"] = "NON_GO"
        document["stop_reason"] = {"code": "LEG_FAILED", "text": "x", "failed_criteria": ["LEG_FAILED"],
                                   "exception_types": ["OSError"]}
        subject.finalize_artifact(document, kind="evidence", secrets=[], fs=subject.FileOps(),
                                  path=tmp_path / "out.json", fallback_path=tmp_path / "fb.json")
        stop = json.loads((tmp_path / "fb.json").read_text(encoding="utf-8"))["stop_reason"]
        assert stop["failed_criteria"] == ["LEG_FAILED", "ARTIFACT_PATH_UNAVAILABLE"] and stop["exception_types"] == ["OSError"]

    def test_a_tripped_document_keeps_its_scrubbed_stub_in_the_fallback(self, tmp_path: Path) -> None:
        (tmp_path / "out.json").write_text("precious", encoding="utf-8")
        document = self._go_document()
        document["notes"] = TOKEN
        written = subject.finalize_artifact(document, kind="evidence", secrets=[TOKEN], fs=subject.FileOps(),
                                            path=tmp_path / "out.json", fallback_path=tmp_path / "fb.json")
        text = (tmp_path / "fb.json").read_text(encoding="utf-8")
        assert TOKEN not in text and json.loads(text)["stop_reason"]["code"] == "SANITIZER_TRIPPED"
        assert json.loads(text)["verdict"] == "NON_GO" and written.tripped

    def test_a_cleanup_artifact_is_not_rewritten(self, tmp_path: Path) -> None:
        (tmp_path / "out.json").write_text("precious", encoding="utf-8")
        document = {"schema": subject.CLEANUP_ARTIFACT_SCHEMA, "mode": "cleanup_only", "result": "CONFIRMED_GOLDEN",
                    "gate_map": dict(subject.GATE_MAP_NOT_ALL_PASSED)}
        subject.finalize_artifact(document, kind="cleanup", secrets=[], fs=subject.FileOps(),
                                  path=tmp_path / "out.json", fallback_path=tmp_path / "fb.json")
        assert json.loads((tmp_path / "fb.json").read_text(encoding="utf-8")) == document


# ---------------------------------------------------------------------------
# P10 / P15 small behaviours
# ---------------------------------------------------------------------------
class TestSmallBehaviours:
    @pytest.mark.parametrize("payload", [portfolio_body(3)[0], None, "text", 5, {"userId": subject.DEMO_USER_ID}])
    def test_select_portfolio_requires_a_json_list(self, payload: Any) -> None:
        with pytest.raises(subject.ObserveError) as info:
            subject.select_portfolio(payload)
        assert info.value.code == "NOT_A_LIST"

    def test_a_list_with_the_demo_entry_is_still_accepted(self) -> None:
        assert subject.select_portfolio(portfolio_body(3))["version"] == 3

    def test_the_child_handle_seam_carries_no_dead_wait(self) -> None:
        """The orchestrator confirms death by polling (_confirm_child_dead) and never calls wait(), so
        the Protocol, the real child and the fake no longer carry it."""
        import inspect
        assert not hasattr(subject.ChildHandle, "wait") and not hasattr(subject._RealChild, "wait")
        assert not hasattr(FakeChild(Clock(), 1.0, 0), "wait")
        assert {"poll", "kill_tree", "close"} <= set(dir(subject._RealChild))
        source = Path(subject.__file__).read_text(encoding="utf-8")
        assert ".wait(" not in source and "def wait" not in source
        assert "wait" not in inspect.getsource(subject.ChildHandle)

    def test_build_child_env_has_no_dead_forbidden_values_parameter(self) -> None:
        import inspect
        assert "forbidden_values" not in inspect.signature(subject.build_child_env).parameters
        assert "forbidden_values" not in Path(subject.__file__).read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# Round 2, finding Q8 (feasibility): P3b warm-up of the price service, and --action-timeout-ms
# ---------------------------------------------------------------------------
def _price_urls(h: Harness) -> list[str]:
    return [c["url"] for c in h.server.price_probes()]


class TestWarmup:
    def test_the_first_200_array_stops_the_warmup_and_the_run_proceeds(self, h: Harness) -> None:
        h.server.prices_script = [(504, None), (503, {"error": "x"}), (200, [{"ticker": "AAPL", "price": "1.00"}])]
        h.server.prices_seconds = 20.0
        assert h.run() == 0
        art = h.artifact()
        # three probes of 20 s plus the two 10 s pauses that follow the two failed ones
        assert art["verdict"] == "GO" and art["warmup"] == {"probes": 3, "last_status": 200, "seconds": 80.0}
        assert len(h.server.price_probes()) == 3 and h.server.prices_script == []  # it stopped at the first success

    def test_one_probe_is_enough_when_the_service_is_already_warm(self, h: Harness) -> None:
        assert h.run() == 0
        assert len(h.server.price_probes()) == 1 and h.artifact()["warmup"]["probes"] == 1

    def test_the_probe_is_a_read_only_get_of_the_first_golden_ticker_with_the_jwt_and_a_90_second_timeout(
            self, h: Harness) -> None:
        h.server.prices_script = [(503, None), (200, [])]
        h.run()
        first, second = h.server.price_probes()
        for call in (first, second):
            assert call["method"] == "GET" and call["body"] is None
            assert call["url"] == API + "/api/market/prices?tickers=AAPL"  # the alphabetically first golden ticker
            assert call["headers"] == {"Authorization": "Bearer " + TOKEN} and call["timeout"] == 90.0
        assert [c["method"] for c in h.server.calls if c["method"] not in ("GET", "POST")] == []  # no mutation at all

    def test_a_ticker_with_reserved_characters_is_query_encoded(self) -> None:
        for ticker, encoded in (("M&M.NS", "M%26M.NS"), ("EURUSD=X", "EURUSD%3DX"), ("^GSPC", "%5EGSPC")):
            client, rec = logged_in([(200, [])])
            golden = subject.Golden(holdings=((ticker, "1.00000000"),), catalog_sha256="AB" * 32)
            outcome = subject.run_warmup(client, golden, child_seams(Clock(), None), subject.WarmupOutcome())
            assert outcome.ok and rec.calls[-1]["url"] == API + "/api/market/prices?tickers=" + encoded

    def test_the_probe_count_is_bounded_at_six(self, h: Harness) -> None:
        h.server.prices_script = [(503, None)] * 20
        assert h.run() == 1
        art = h.artifact()
        assert len(h.server.price_probes()) == 6 == subject.WARMUP_MAX_PROBES
        # instant 503s still take time: five 10 s pauses (none after the sixth, no probe follows it)
        assert art["warmup"] == {"probes": 6, "last_status": 503, "seconds": 50.0}
        assert len(h.server.prices_script) == 14  # the other 14 scripted answers were never asked for

    def test_a_warmup_that_never_succeeds_is_incomplete_with_no_child_no_mutation_and_no_armed_marker(
            self, h: Harness) -> None:
        h.server.prices_script = [(504, None)] * 6
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("INCOMPLETE", "WARMUP_FAILED")
        assert art["stop_reason"]["failed_criteria"] == ["WARMUP_FAILED"]
        assert art["gate_map"] == {"step_a_credited": False, "run_result": "not_all_items_passed"}
        assert h.browser.spawn_calls == [] and server_paths(h, "PUT") == []  # no browser, no mutation
        assert art["cleanup"]["armed"] is False and art["cleanup"]["result"] == "skipped_no_mutation"
        assert h.ledger_events() == []  # not even the write-ahead cleanup_armed marker: nothing is owed
        assert {leg["status"] for leg in art["legs"]} == {"skipped"}
        assert {leg["reason_code"] for leg in art["legs"]} == {"SKIPPED_PRIOR_FAILURE"}
        assert art["baseline"]["golden"] is True and art["child"]["state"] == "not_run"
        assert h.input_prompts == [] and art["binding"]["backend"]["post_state"] == "skipped"
        assert server_paths(h, "GET").count("/api/portfolio") == 1  # the baseline only: no cleanup observation either
        h.assert_no_unexpected()

    def test_the_warmup_runs_after_the_baseline_and_before_the_armed_marker_and_the_child(self, h: Harness) -> None:
        h.run()
        prefix = h.browser.server_calls_at_spawn
        assert prefix[:2] == [("POST", "/api/auth/login"), ("GET", "/api/portfolio")]
        assert prefix[2:] == [("GET", "/api/market/prices?tickers=AAPL")]  # everything before the child spawn
        first = [json.loads(line) for line in h.browser.ledger_at_spawn.splitlines()]
        assert [e.get("result") for e in first if e["event"] == "cleanup"] == ["cleanup_armed"]

    def test_a_200_whose_body_is_not_a_json_array_keeps_probing(self, h: Harness) -> None:
        h.server.prices_script = [(200, {"prices": []}), (200, None), (200, "text"), (200, 5), (200, [])]
        assert h.run() == 0
        assert h.artifact()["warmup"] == {"probes": 5, "last_status": 200, "seconds": 40.0}  # four pauses of 10 s

    def test_six_non_array_200s_never_count_as_warm(self, h: Harness) -> None:
        h.server.prices_script = [(200, {"prices": []})] * 6
        assert h.run() == 1
        art = h.artifact()
        assert art["stop_reason"]["code"] == "WARMUP_FAILED" and art["warmup"]["last_status"] == 200
        assert h.browser.spawn_calls == []

    def test_a_non_200_array_is_not_warm_either(self, h: Harness) -> None:
        h.server.prices_script = [(500, []), (429, []), (200, [])]
        assert h.run() == 0 and h.artifact()["warmup"]["probes"] == 3

    def test_a_timeout_or_connection_error_consumes_a_probe_and_the_loop_continues(self, h: Harness) -> None:
        h.server.prices_script = [TimeoutError("t"), ConnectionError("c"), (200, [])]
        assert h.run() == 0
        assert h.artifact()["warmup"] == {"probes": 3, "last_status": 200, "seconds": 20.0}  # two pauses of 10 s

    def test_six_transport_failures_leave_no_last_status(self, h: Harness) -> None:
        h.server.prices_script = [TimeoutError("t")] * 6
        assert h.run() == 1
        assert h.artifact()["warmup"] == {"probes": 6, "last_status": None, "seconds": 50.0}
        assert h.artifact()["stop_reason"]["code"] == "WARMUP_FAILED"

    def test_the_seconds_are_the_clock_time_the_probes_took(self, h: Harness) -> None:
        h.server.prices_script = [(503, None)] * 6
        h.server.prices_seconds = 55.0
        h.run()
        # six probes of 55 s and five 10 s pauses; the sixth probe starts at 325 s, inside the 360 s budget
        assert h.artifact()["warmup"]["seconds"] == 380.0

    def test_the_ticker_the_response_and_the_jwt_never_reach_an_output_or_the_artifact(self, h: Harness) -> None:
        rows = (("QZXTICKA", "31.00000000"), ("QZXTICKB", "22.00000000"))
        h.cmd.results["oracle"] = subject.CmdResult(0, oracle_stdout(rows))
        h.server.rows = rows
        h.browser.server_effect = "untouched"
        h.server.prices_default = (200, [{"ticker": "QZXTICKA", "price": "TELLTALE-BODY-VALUE"}])
        assert h.run() == 0
        assert _price_urls(h) == [API + "/api/market/prices?tickers=QZXTICKA"]  # the request itself does carry it
        recorded = "\n".join(
            h.out + h.err + h.prompts + h.input_prompts
            + [h.evidence_path.read_text(encoding="utf-8"), json.dumps(h.ledger_events())]
        )
        for secret in ("QZXTICKA", "QZXTICKB", "TELLTALE-BODY-VALUE", TOKEN, PASSWORD):
            assert secret not in recorded, secret
        assert set(h.artifact()["warmup"]) == {"probes", "last_status", "seconds"}  # allowlist-built

    def test_the_warmup_is_not_run_when_the_login_or_baseline_stops_the_run(self, h: Harness) -> None:
        h.server.rows = DIRTY_ROWS
        assert h.run() == 1
        assert h.server.price_probes() == [] and h.artifact()["warmup"] is None
        h2 = Harness(h.tmp / "slow")
        h2.server.login_seconds = 170.0
        h2.run()
        assert h2.server.price_probes() == [] and h2.artifact()["warmup"] is None
        h3 = Harness(h.tmp / "denied")
        h3.server.login_status = 401
        h3.run()
        assert h3.server.price_probes() == [] and h3.artifact()["warmup"] is None

    def test_it_is_not_part_of_cleanup_only(self, h: Harness) -> None:
        assert run_cleanup_only(h) == 0
        assert h.server.price_probes() == []

    def test_an_interrupt_during_the_warmup_is_recorded_cleanup_is_not_owed_and_evidence_is_written(
            self, h: Harness) -> None:
        h.server.prices_script = [(503, None), KeyboardInterrupt()]
        with pytest.raises(KeyboardInterrupt):
            h.run()
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "INTERRUPTED")
        assert {"INTERRUPTED", "WARMUP_NOT_REACHED"} <= set(art["stop_reason"]["failed_criteria"])
        assert art["warmup"]["probes"] == 2 and h.browser.spawn_calls == [] and server_paths(h, "PUT") == []
        assert art["cleanup"]["armed"] is False and art["cleanup"]["result"] == "skipped_no_mutation"

    def test_any_exception_from_the_seam_is_just_a_spent_probe(self, h: Harness) -> None:
        h.server.prices_script = [ValueError("boom")]
        assert h.run() == 0
        assert h.artifact()["warmup"]["probes"] == 2  # the second probe (the default answer) succeeded

    def test_the_real_transport_carries_the_warmup_url_to_the_allowlisted_host_even_behind_a_proxy(
            self, proxy_everywhere: None, monkeypatch: pytest.MonkeyPatch) -> None:
        recorder = _install_recorder(monkeypatch, lambda req: CannedResponse(req.full_url, body=b"[]"))
        status, body = subject.make_http(
            "GET", API + "/api/market/prices?tickers=AAPL", {"Authorization": "Bearer x"}, None, 90.0)
        assert (status, body) == (200, []) and recorder.hosts == ["api.vibhanshu-ai-portfolio.dev"]
        assert recorder.urls == [API + "/api/market/prices?tickers=AAPL"] and PROXY_HOST not in recorder.hosts
        with pytest.raises(ValueError):  # the same allowlist refuses any other host for a warm-up URL
            subject.make_http("GET", "https://evil.example.net/api/market/prices?tickers=AAPL", {}, None, 90.0)
        with pytest.raises(ValueError):
            subject.make_http("GET", "https://api.vibhanshu-ai-portfolio.dev.evil.net/api/market/prices?tickers=A", {}, None, 90.0)

    def test_the_warmup_goes_through_the_injected_http_seam_only(self, h: Harness, monkeypatch: pytest.MonkeyPatch) -> None:
        def real_http_must_not_run(*args: Any, **kwargs: Any) -> Any:
            raise _RecorderExhausted("the real transport was used")

        monkeypatch.setattr(subject, "make_http", real_http_must_not_run)
        assert h.run() == 0  # the harness seam served everything

    def test_run_warmup_unit_outcome_fields(self) -> None:
        client, _ = logged_in([(503, None), (200, [])])
        golden = subject.Golden(holdings=(("AAPL", "1.00000000"),), catalog_sha256="AB" * 32)
        clock = Clock()
        outcome = subject.run_warmup(client, golden, child_seams(clock, None), subject.WarmupOutcome())
        assert (outcome.probes, outcome.last_status, outcome.ok, outcome.done) == (2, 200, True, True)
        client2, _ = logged_in([(503, None)] * 6)
        failed = subject.run_warmup(client2, golden, child_seams(clock, None), subject.WarmupOutcome())
        assert (failed.probes, failed.last_status, failed.ok, failed.done) == (6, 503, False, True)

    def test_an_interrupted_warmup_is_not_done_and_keeps_its_progress(self) -> None:
        client, _ = logged_in([(503, None), KeyboardInterrupt()])
        golden = subject.Golden(holdings=(("AAPL", "1.00000000"),), catalog_sha256="AB" * 32)
        outcome = subject.WarmupOutcome()
        with pytest.raises(KeyboardInterrupt):
            subject.run_warmup(client, golden, child_seams(Clock(), None), outcome)
        assert (outcome.probes, outcome.done, outcome.ok) == (2, False, False)

    def test_the_run_state_only_expects_the_child_after_a_successful_warmup(self, tmp_path: Path) -> None:
        state = subject.RunState(cfg(tmp_path), dt(RUN_START), 0.0)
        state.login = subject.LoginOutcome("ok", 200, 1.0, TOKEN, None)
        state.baseline_state = "golden"
        assert state.warmup_state == "not_reached" and state.child_expected is False
        state.warmup = subject.WarmupOutcome(probes=1, done=True, ok=True)
        assert state.warmup_state == "ok" and state.child_expected is True
        state.warmup = subject.WarmupOutcome(probes=6, done=True, ok=False)
        assert state.warmup_state == "failed" and state.child_expected is False
        state.warmup = subject.WarmupOutcome(probes=1, done=False, ok=True)  # never trust an unfinished warm-up
        assert state.warmup_state == "not_reached" and state.child_expected is False


class TestActionTimeoutKnob:
    @pytest.mark.parametrize("value", [1000, 1001, 60000, 120000, 599999, 600000])
    def test_values_from_1000_to_600000_are_accepted(self, tmp_path: Path, value: int) -> None:
        subject.validate_arguments(cfg(tmp_path, action_timeout_ms=value))

    @pytest.mark.parametrize("value", [0, 1, 999, 600001, 10**9, -1, -120000, True, False, 1500.0, "120000", None])
    def test_anything_else_is_a_precondition_error(self, tmp_path: Path, value: Any) -> None:
        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_arguments(cfg(tmp_path, action_timeout_ms=value))
        assert info.value.code == "ACTION_TIMEOUT_INVALID"

    def test_the_error_text_names_only_the_bounds_never_the_value(self, tmp_path: Path) -> None:
        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_arguments(cfg(tmp_path, action_timeout_ms=123456789))
        assert "123456789" not in info.value.text and "1000" in info.value.text and "600000" in info.value.text

    def test_it_is_validated_in_cleanup_only_mode_too(self, tmp_path: Path) -> None:
        only = subject.RunConfig(evidence_output=tmp_path / "e.json", baseline_commit=BASELINE, cleanup_only=True,
                                 action_timeout_ms=999)
        with pytest.raises(subject.PreconditionError) as info:
            subject.validate_arguments(only)
        assert info.value.code == "ACTION_TIMEOUT_INVALID"

    def test_the_default_run_passes_120000_to_the_child(self, h: Harness) -> None:
        assert h.run() == 0
        assert h.browser.spawn_calls[0]["env"]["STEP_B_5B_ACTION_TIMEOUT_MS"] == "120000"

    @pytest.mark.parametrize("value", [1000, 45000, 600000])
    def test_a_configured_value_reaches_the_child_environment(self, h: Harness, value: int) -> None:
        assert h.run(action_timeout_ms=value) == 0
        assert h.browser.spawn_calls[0]["env"]["STEP_B_5B_ACTION_TIMEOUT_MS"] == str(value)

    def test_the_flag_reaches_the_child_through_main(self, h: Harness) -> None:
        assert h.main("--action-timeout-ms", "45000") == 0
        assert h.browser.spawn_calls[0]["env"]["STEP_B_5B_ACTION_TIMEOUT_MS"] == "45000"

    @pytest.mark.parametrize("value", ["7777abc", "123456789", "0x10", "1e5"])
    def test_a_bad_flag_value_exits_two_and_is_never_echoed(self, h: Harness, value: str) -> None:
        assert h.main("--action-timeout-ms", value) == 2
        assert any("[ACTION_TIMEOUT_INVALID]" in line for line in h.err)
        assert value not in "\n".join(h.err + h.out)
        assert h.server.calls == [] and h.prompts == [] and h.browser.spawn_calls == [] and h.fetch.urls == []

    def test_the_flag_is_documented_in_the_help_and_the_old_fixed_value_is_gone(self) -> None:
        help_text = subject.build_parser().format_help()
        assert "--action-timeout-ms" in help_text and "1000-600000" in help_text
        assert not hasattr(subject, "ACTION_TIMEOUT_MS")  # the old fixed constant is gone, not aliased


class TestBrowserExecutableProbe:
    def test_the_probe_is_a_scrubbed_env_node_call_from_frontend(self, h: Harness) -> None:
        subject.check_tooling(h.config(), h.seams(), OS_ENVIRON)
        node_e = [c for c in h.cmd.calls if c["argv"][:2] == ["node", "-e"] and "executablePath" in c["argv"][2]]
        (call,) = node_e
        assert call["argv"] == list(subject.BROWSER_PROBE_ARGV) and call["cwd"] == h.frontend_dir
        assert call["argv"][2] == "require('fs').accessSync(require('playwright-core').chromium.executablePath())"
        env = call["env"]
        assert env["PLAYWRIGHT_BROWSERS_PATH"] == "/pw"  # the very browsers directory the child will use
        for name in ("DEBUG", "PWDEBUG", "NODE_OPTIONS", "INTERNAL_API_KEY", "WAVE9_STEP_A_PASSWORD"):
            assert name not in env

    def test_it_runs_after_the_package_check_and_before_the_file_checks(self, h: Harness) -> None:
        _cmd(h, "playwright", 1)
        _cmd(h, "browser", 1)
        with pytest.raises(subject.PreconditionError) as info:
            subject.check_tooling(h.config(), h.seams(), OS_ENVIRON)
        assert info.value.code == "PLAYWRIGHT_PACKAGE_MISSING"  # the earlier check speaks first
        _cmd(h, "playwright", 0)
        (h.frontend_dir / subject.PLAYWRIGHT_CLI_REL).unlink()
        with pytest.raises(subject.PreconditionError) as info:
            subject.check_tooling(h.config(), h.seams(), OS_ENVIRON)
        assert info.value.code == "BROWSER_EXECUTABLE_MISSING"  # ... and before the cli.js file check

    def test_a_missing_browser_is_exit_two_and_consumes_nothing(self, h: Harness) -> None:
        _cmd(h, "browser", 1)
        assert h.run() == 2
        assert any("[BROWSER_EXECUTABLE_MISSING]" in line for line in h.err)
        assert h.server.calls == [] and h.prompts == [] and h.browser.spawn_calls == [] and h.fetch.urls == []
        assert not h.evidence_path.exists() and not h.work_dir.exists()

    def test_it_is_not_part_of_cleanup_only(self, h: Harness) -> None:
        _cmd(h, "browser", 1)
        assert run_cleanup_only(h) == 0
        assert not any("executablePath" in " ".join(c["argv"]) for c in h.cmd.calls)

    def test_the_default_run_probes_the_browser_exactly_once(self, h: Harness) -> None:
        assert h.run() == 0
        assert sum("executablePath" in " ".join(c["argv"]) for c in h.cmd.calls) == 1
        assert all(c["argv"][0] not in ("npx", "npm") for c in h.cmd.calls)


# ---------------------------------------------------------------------------
# Round 3 (final), finding R1: a JSON integer literal longer than sys.get_int_max_str_digits() makes
# json.loads raise a plain ValueError (not a JSONDecodeError). One hostile ledger line used to abort the
# parse of the whole ledger (the run then sealed UNEXPECTED_ERROR), and the contract, the oracle output
# and the attestation files escaped as a traceback instead of a coded refusal.
# ---------------------------------------------------------------------------
HUGE_INT = "9" * 5000  # a JSON integer literal well past the default 4300-digit interpreter limit
DEEP_JSON = "[" * 30000  # nesting far past the recursion limit, yet under the 64 KiB attestation size cap


@pytest.fixture
def int_digit_limit() -> Any:
    """Pin the interpreter's integer-string limit at its default (4300) for one test, so the 5000-digit
    literals are overlong whatever PYTHONINTMAXSTRDIGITS says; skipped where there is no such limit."""
    getter = getattr(sys, "get_int_max_str_digits", None)
    setter = getattr(sys, "set_int_max_str_digits", None)
    if getter is None or setter is None:
        pytest.skip("this interpreter has no integer string conversion limit")
    previous = getter()
    setter(4300)
    try:
        yield 4300
    finally:
        setter(previous)


def hostile_leg_line(digits: str = HUGE_INT, seq: int = 5) -> str:
    """A well-formed L4 leg line whose only defect is the size of one integer literal."""
    return (
        '{"seq":%d,"tUtc":"2026-09-20T12:05:00.000Z","src":"spec","event":"leg","leg":"L4","status":"passed",'
        '"reason":"OK","http":[],"facts":{"activeCount":%s}}' % (seq, digits)
    )


def ledger_with_hostile_line() -> list[Any]:
    """The GO ledger whose L4 line is replaced, in place, by one raw hostile line. The honest writer's seq
    chain skips it, so every other line is consecutive and the hostile line is the only invalid one."""
    writer = SpecWriter()
    lines: list[Any] = []
    for i in range(8):
        if i == 4:
            lines.append(hostile_leg_line())
            continue
        writer.leg(f"L{i}")
        lines.append(writer.lines[-1])
    writer.armed()
    lines.append(writer.lines[-1])
    for leg in ("L8", "L9"):
        writer.leg(leg)
        lines.append(writer.lines[-1])
    return lines


def hostile_attestation(read_at: str) -> str:
    text = attestation_json(read_at)
    assert text.endswith("}")
    return text[:-1] + ', "padding": ' + HUGE_INT + "}"


def hostile_contract_text() -> str:
    text = subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8")
    assert '{ "min": 1 }' in text
    return text.replace('{ "min": 1 }', '{ "min": ' + HUGE_INT + " }", 1)


def hostile_oracle_text() -> str:
    text = oracle_stdout()
    assert '"activeEntryCount": 3' in text
    return text.replace('"activeEntryCount": 3', '"activeEntryCount": ' + HUGE_INT, 1)


@pytest.mark.usefixtures("int_digit_limit")
class TestOverlongIntegerLiterals:
    def test_the_premise_python_raises_a_plain_value_error_not_a_json_decode_error(self) -> None:
        with pytest.raises(ValueError) as info:
            json.loads('{"a":' + HUGE_INT + "}")
        assert not isinstance(info.value, json.JSONDecodeError)  # the handlers that named only that class missed it

    # -- the ledger: one hostile line must not abort the parse of the others -----------------------
    def test_one_overlong_ledger_line_is_only_that_line_invalid_and_every_other_line_is_still_parsed(self) -> None:
        parsed = parse(*ledger_with_hostile_line())  # must not raise
        assert parsed.invalid_lines == [(5, "INVALID_JSON")] and not parsed.clean
        assert set(parsed.legs) == set(CONTRACT.legs) - {"L4"}  # the hostile line never names a leg
        assert all(record.status == "passed" and not record.poisoned for record in parsed.legs.values())
        assert parsed.armed_line_no == 9 and subject.spec_armed_before_l8(parsed) is True

    @pytest.mark.parametrize("digits,code", [(4300, "FACT_VALUE_INVALID"), (4301, "INVALID_JSON")])
    def test_a_literal_at_the_limit_is_parsed_and_judged_by_range_one_digit_over_is_invalid_json(
            self, digits: int, code: str) -> None:
        """The pair that keeps the negative case honest: 4300 digits still parse (and are simply out of
        the 0..10^9 fact range, poisoning L4), 4301 digits are not a JSON value this reader will accept."""
        parsed = parse(hostile_leg_line("9" * digits, seq=1))
        assert parsed.invalid_lines == [(1, code)] and not parsed.clean

    def test_the_hostile_ledger_is_non_go_with_ledger_invalid_and_never_an_unexpected_error(self, h: Harness) -> None:
        h.browser.lines = ledger_with_hostile_line()
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "LEDGER_INVALID")
        criteria = art["stop_reason"]["failed_criteria"]
        assert "LEDGER_INVALID" in criteria and "LEG_FAILED" in criteria and "UNEXPECTED_ERROR" not in criteria
        assert "exception_types" not in art["stop_reason"]  # nothing escaped: the ledger was judged, not aborted
        by_id = {leg["id"]: (leg["status"], leg["reason_code"]) for leg in art["legs"]}
        assert by_id["L4"] == ("missing", "LEG_MISSING")  # the hostile line never reported a leg
        assert all(by_id[f"L{i}"] == ("passed", "OK") for i in (0, 1, 2, 3, 5, 6, 7, 8, 9))  # the rest was judged
        assert art["cleanup"]["result"] == "not_needed" and art["gate_map"]["run_result"] == "not_all_items_passed"

    # -- the contract, the oracle output and the attestation files: a coded refusal, not a traceback --
    def test_an_overlong_literal_in_the_contract_is_a_precondition_error(self, h: Harness) -> None:
        subject.parse_ledger_contract(subject.LEDGER_CONTRACT_PATH.read_bytes())  # the control: the file itself loads
        with pytest.raises(subject.PreconditionError) as info:
            subject.parse_ledger_contract(hostile_contract_text().encode())
        assert info.value.code == "LEDGER_CONTRACT_INVALID"
        path = h.tmp / "hostile-contract.json"
        path.write_text(hostile_contract_text(), encoding="utf-8")
        assert h.run(contract_path=path) == 2  # exit 2 through main's precondition handler: no traceback
        assert any("[LEDGER_CONTRACT_INVALID]" in line for line in h.err), h.err
        assert h.server.calls == [] and h.browser.spawn_calls == [] and h.prompts == [] and h.fetch.urls == []
        assert not h.evidence_path.exists() and not h.work_dir.exists()

    def test_an_overlong_literal_in_the_oracle_output_is_a_precondition_error(self, h: Harness) -> None:
        subject.parse_oracle_output(oracle_stdout())  # the control: the honest output loads
        with pytest.raises(subject.PreconditionError) as info:
            subject.parse_oracle_output(hostile_oracle_text())
        assert info.value.code == "ORACLE_OUTPUT_INVALID"
        _cmd(h, "oracle", 0, hostile_oracle_text())
        assert h.run() == 2
        assert any("[ORACLE_OUTPUT_INVALID]" in line for line in h.err), h.err
        assert h.server.calls == [] and h.browser.spawn_calls == [] and h.prompts == []
        assert not h.evidence_path.exists() and not h.work_dir.exists()
        assert run_cleanup_only(h) == 2  # --cleanup-only reads the same oracle output: same refusal, no login
        assert sum("[ORACLE_OUTPUT_INVALID]" in line for line in h.err) == 2
        assert h.server.calls == [] and h.prompts == [] and not h.evidence_path.exists()

    def test_an_overlong_literal_in_an_attestation_file_is_a_coded_refusal(self, h: Harness) -> None:
        subject.parse_attestation(attestation_json("2026-09-20T11:45:00Z").encode())  # the control
        with pytest.raises(subject.AttestationError) as info:
            subject.parse_attestation(hostile_attestation("2026-09-20T11:45:00Z").encode())
        assert info.value.code == "NOT_JSON"
        (h.tmp / "attestation-pre.json").write_text(hostile_attestation(PRE_ATTESTATION_AT), encoding="utf-8")
        assert h.run() == 2  # the pre attestation is a P0 precondition
        assert any("[ATTESTATION_PRE_INVALID]" in line for line in h.err), h.err
        assert h.server.calls == [] and h.browser.spawn_calls == [] and h.prompts == []
        assert not h.evidence_path.exists() and not h.work_dir.exists()

    def test_an_overlong_literal_in_the_post_attestation_is_non_go_never_an_unexpected_error(self, h: Harness) -> None:
        h.post_attestation = lambda path, clock: path.write_text(
            hostile_attestation(subject.utc_z(clock.now - datetime.timedelta(seconds=5))), encoding="utf-8")
        assert h.run() == 1
        art = h.artifact()
        assert art["binding"]["backend"]["post_state"] == "invalid"
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "BACKEND_BINDING_FAILED")
        assert "UNEXPECTED_ERROR" not in art["stop_reason"]["failed_criteria"]
        assert "exception_types" not in art["stop_reason"]

    # -- RecursionError joined the same handlers: pin it on all four readers -----------------------
    def test_a_deeply_nested_document_is_refused_by_all_four_readers(self) -> None:
        with pytest.raises(subject.PreconditionError) as contract_error:
            subject.parse_ledger_contract(DEEP_JSON.encode())
        assert contract_error.value.code == "LEDGER_CONTRACT_INVALID"
        with pytest.raises(subject.PreconditionError) as oracle_error:
            subject.parse_oracle_output(DEEP_JSON)
        assert oracle_error.value.code == "ORACLE_OUTPUT_INVALID"
        with pytest.raises(subject.AttestationError) as attestation_error:
            subject.parse_attestation(DEEP_JSON.encode())
        assert attestation_error.value.code == "NOT_JSON"
        assert parse(DEEP_JSON).invalid_lines == [(1, "INVALID_JSON")]


# ---------------------------------------------------------------------------
# Round 3 (final), finding R2: a second {"event":"armed"} line is invalid (DUPLICATE_ARMED)
# ---------------------------------------------------------------------------
def ledger_with_armed_after(*positions: int) -> list[dict[str, Any]]:
    """The GO ledger with one `armed` line after each named count of legs (8 is the honest position:
    after L7's final event and before L8's; 0 is the very first line, 10 the very last)."""
    writer = SpecWriter()
    for i in range(10):
        for _ in range(positions.count(i)):
            writer.armed()
        writer.leg(f"L{i}")
    for _ in range(positions.count(10)):
        writer.armed()
    return writer.lines


def judge(lines: list[dict[str, Any]]):
    """parse -> derive_legs -> compute_verdict with the ledger's real armed-position flag (evaluate() leaves it
    at its all-clear default): (parsed, legs, verdict)."""
    parsed = parse(*lines)
    legs = subject.derive_legs(parsed, CONTRACT, child_expected=True, fallback_reason="X")
    verdict = subject.compute_verdict(base(
        legs=legs, ledger_clean=parsed.clean, spec_armed_before_l8=subject.spec_armed_before_l8(parsed)))
    return parsed, legs, verdict


class TestDuplicateArmed:
    def test_the_control_one_honest_armed_line_is_clean_and_go(self) -> None:
        parsed, _, verdict = judge(ledger_with_armed_after(8))
        assert parsed.clean and parsed.armed_line_no == 9 and verdict.verdict == "GO"

    def test_a_second_armed_line_after_l9_is_invalid_and_the_ledger_is_non_go(self) -> None:
        """Honest armed between L7 and L8, then a second one after L9: the first still counts for the
        position rule, but the ledger as a whole is no longer clean."""
        parsed, legs, verdict = judge(ledger_with_armed_after(8, 10))
        assert parsed.invalid_lines == [(12, "DUPLICATE_ARMED")] and not parsed.clean
        assert parsed.armed_line_no == 9  # the first one is kept, the second never replaces it
        assert all(leg.status == "passed" for leg in legs.values())  # no leg is poisoned: armed names none
        assert (verdict.verdict, verdict.code) == ("NON_GO", "LEDGER_INVALID")
        assert "ARMED_MISSING" not in verdict.failed_criteria  # the position was honest; the duplicate is the fault

    def test_an_armed_line_first_and_then_the_honest_one_is_invalid_too(self) -> None:
        parsed, _, verdict = judge(ledger_with_armed_after(0, 8))
        assert parsed.invalid_lines == [(10, "DUPLICATE_ARMED")] and parsed.armed_line_no == 1
        assert subject.spec_armed_before_l8(parsed) is False  # the kept first one is misplaced
        assert verdict.verdict == "NON_GO" and {"LEDGER_INVALID", "ARMED_MISSING"} <= set(verdict.failed_criteria)

    @pytest.mark.parametrize("positions", [(8, 9), (8, 8), (7, 8), (0, 10), (8, 10, 10)], ids=str)
    def test_any_second_armed_line_wherever_it_sits_is_invalid(self, positions: tuple[int, ...]) -> None:
        parsed = parse(*ledger_with_armed_after(*positions))
        assert len(parsed.invalid_lines) == len(positions) - 1
        assert {code for _, code in parsed.invalid_lines} == {"DUPLICATE_ARMED"} and not parsed.clean

    @pytest.mark.parametrize("positions", [(8, 10), (0, 8)], ids=["honest-then-after-l9", "first-line-then-honest"])
    def test_the_duplicate_armed_ledger_is_non_go_end_to_end(self, h: Harness, positions: tuple[int, ...]) -> None:
        h.browser.lines = ledger_with_armed_after(*positions)
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "LEDGER_INVALID")
        assert art["gate_map"]["run_result"] == "not_all_items_passed" and art["cleanup"]["result"] == "not_needed"
        assert {leg["status"] for leg in art["legs"]} == {"passed"}  # only the ledger as a whole is at fault


# ---------------------------------------------------------------------------
# Round 3 (final), finding R3: the passHttp parser accepts and refuses what the TypeScript loader does
# ---------------------------------------------------------------------------
class TestPassHttpParserParity:
    @staticmethod
    def _doc() -> dict[str, Any]:
        return json.loads(subject.LEDGER_CONTRACT_PATH.read_text(encoding="utf-8"))

    def _parse(self, mutate: Callable[[dict], Any]) -> subject.LedgerContract:
        doc = self._doc()
        mutate(doc)
        return subject.parse_ledger_contract(json.dumps(doc).encode())

    def _refused(self, mutate: Callable[[dict], Any]) -> None:
        with pytest.raises(subject.PreconditionError) as info:
            self._parse(mutate)
        assert info.value.code == "LEDGER_CONTRACT_INVALID"

    NONE_WITH_OTHER_KEYS = [
        ("L7", "countFact", {"countFact": "pageWriteRequestsBeforeMutation"}),
        ("L7", "statusFacts-first", {"statusFacts": {"pageWriteRequestsBeforeMutation": "first"}}),
        ("L7", "statusFacts-last", {"statusFacts": {"pageWriteRequestsBeforeMutation": "last"}}),
        ("L7", "firstStatus", {"firstStatus": 200}),
        ("L7", "allStatus", {"allStatus": 200}),
        ("L0", "firstStatus", {"firstStatus": 200}),
        ("L0", "allStatus", {"allStatus": 200}),
        ("L3", "firstStatus", {"firstStatus": 200}),
    ]

    @pytest.mark.parametrize("leg,label,extra", NONE_WITH_OTHER_KEYS, ids=[f"{c[0]}-{c[1]}" for c in NONE_WITH_OTHER_KEYS])
    def test_none_entries_is_exclusive_the_rule_then_carries_no_other_key(
            self, leg: str, label: str, extra: dict[str, Any]) -> None:
        self._refused(lambda d: d["passHttp"].__setitem__(leg, {"entries": "none", **extra}))
        # the pair: the very same keys are legal once the leg does expect requests, so the refusal above
        # is about 'none' being exclusive and about nothing else
        method_path = {"method": "GET", "path": "/api/portfolio"}
        contract = self._parse(lambda d: d["passHttp"].__setitem__(leg, {"entries": method_path, **extra}))
        assert all(contract.pass_http[leg][key] == value for key, value in extra.items())

    def test_the_shipped_none_legs_carry_no_other_key(self) -> None:
        assert {leg for leg, rule in CONTRACT.pass_http.items() if rule["entries"] == "none"} == {"L0", "L3", "L7"}
        assert all(set(CONTRACT.pass_http[leg]) == {"entries"} for leg in ("L0", "L3", "L7"))

    @pytest.mark.parametrize("key", ["firstStatus", "allStatus"])
    @pytest.mark.parametrize("value", [0, 1, 99, 600, 700, -1, -200, 2**53, 10**9, 10**12, True, False],
                             ids=lambda v: repr(v))
    def test_a_status_rule_that_is_not_a_status_in_100_to_599_is_a_contract_error(self, key: str, value: Any) -> None:
        self._refused(lambda d: d["passHttp"]["L8"].__setitem__(key, value))

    @pytest.mark.parametrize("key", ["firstStatus", "allStatus"])
    @pytest.mark.parametrize("value", [100, 101, 200, 299, 404, 500, 599])
    def test_a_status_rule_in_100_to_599_is_accepted_so_the_refusals_are_not_vacuous(self, key: str, value: int) -> None:
        contract = self._parse(lambda d: d["passHttp"]["L8"].__setitem__(key, value))
        assert contract.pass_http["L8"][key] == value

    @pytest.mark.parametrize("leg", ["L1", "L4", "L5", "L8", "L9"])
    def test_an_empty_status_facts_map_is_refused(self, leg: str) -> None:
        self._refused(lambda d: d["passHttp"][leg].__setitem__("statusFacts", {}))

    def test_the_shipped_contract_still_loads_and_meets_every_parity_rule(self) -> None:
        reloaded = subject.parse_ledger_contract(subject.LEDGER_CONTRACT_PATH.read_bytes())
        assert {leg: dict(rule) for leg, rule in reloaded.pass_http.items()} == EXPECTED_PASS_HTTP
        for leg, rule in reloaded.pass_http.items():
            for key in ("firstStatus", "allStatus"):
                assert key not in rule or (subject.is_strict_int(rule[key]) and 100 <= rule[key] <= 599), (leg, key)
            assert "statusFacts" not in rule or rule["statusFacts"], leg

    def test_the_contract_prose_states_the_three_rules(self) -> None:
        """passHttpRules is what a second implementer reads: it must say what both readers enforce."""
        prose = self._doc()["passHttpRules"]
        assert '"none" is exclusive' in prose and "100..599" in prose and "non-empty" in prose

    @pytest.mark.parametrize("key", ["commonFields", "sources", "specEvents", "orchestratorEvents"])
    @pytest.mark.parametrize("change", ["gain", "lose", "reorder"])
    def test_the_four_event_and_field_lists_are_pinned_exactly(self, key: str, change: str) -> None:
        """The TypeScript loader pins only some lists; this reader pins all eleven exactly, so a member
        gained, lost or reordered in these four is a contract error here (and must be on both sides)."""
        def mutate(doc: dict) -> None:
            if change == "gain":
                doc[key] = doc[key] + ["EXTRA_MEMBER"]
            elif change == "lose":
                doc[key] = doc[key][:-1]
            else:
                doc[key] = list(reversed(doc[key]))

        self._refused(mutate)
        assert key in subject._CONTRACT_EXACT and tuple(self._doc()[key]) == subject._CONTRACT_EXACT[key]


# ---------------------------------------------------------------------------
# Round 3 (final), finding R4: the P3b warm-up is bounded by time (360 s) as well as by count (6), and
# pauses min(10 s, remaining budget) after a failed probe through the injected sleep seam
# ---------------------------------------------------------------------------
class TimedProbes:
    """The HTTP seam of a warm-up unit test. The login answers at once; each probe costs `seconds` of
    fake-clock time. `started_at` keeps the time (from the warm-up's start) at which each probe began, and an
    exhausted script raises a BaseException, so a probe the loop should not have made cannot go unnoticed."""

    def __init__(self, clock: Clock, script: list[Any], seconds: float = 0.0) -> None:
        self.clock = clock
        self.script = list(script)
        self.seconds = seconds
        self.origin = clock.mono
        self.started_at: list[float] = []
        self.timeouts: list[float] = []

    def __call__(self, method: str, url: str, headers: dict[str, str], json_body: Any = None,
                 timeout: float = 30.0) -> tuple[int, Any]:
        if method == "POST":
            return 200, {"token": TOKEN}
        if not self.script:
            raise _RecorderExhausted("an unexpected warm-up probe")
        self.started_at.append(self.clock.mono - self.origin)
        self.timeouts.append(timeout)
        self.clock.advance(self.seconds)
        item = self.script.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def warm(script: list[Any], *, seconds: float = 0.0, clock: Optional[Clock] = None):
    """Run run_warmup against a scripted, clock-advancing price seam: (outcome, probes, clock)."""
    clock = clock or Clock()
    probes = TimedProbes(clock, script, seconds)
    client = subject.ApiClient(probes, API, 30.0, subject.SecretRegistry())
    assert client.login(subject.PasswordHolder(PASSWORD), clock.monotonic).state == "ok"
    outcome = subject.run_warmup(client, GOLDEN, child_seams(clock, None), subject.WarmupOutcome())
    return outcome, probes, clock


class TestWarmupBudget:
    def test_six_instant_503s_do_not_exhaust_the_phase_in_zero_time(self) -> None:
        outcome, probes, clock = warm([(503, None)] * 6)
        assert (outcome.probes, outcome.last_status, outcome.ok, outcome.done) == (6, 503, False, True)
        # five pauses of 10 s through seams.sleep, none after the sixth probe (no probe can follow it)
        assert clock.sleeps == [10.0] * 5 and outcome.seconds == 50.0
        assert probes.started_at == [0.0, 10.0, 20.0, 30.0, 40.0, 50.0]

    def test_every_kind_of_failed_probe_is_followed_by_a_pause_and_a_success_by_none(self) -> None:
        outcome, _, clock = warm([(503, None), (200, {"prices": []}), TimeoutError("t"), (200, [])])
        assert outcome.ok and outcome.probes == 4 and outcome.last_status == 200
        assert clock.sleeps == [10.0] * 3 and outcome.seconds == 30.0  # a 503, a non-array 200, a transport error

    def test_a_success_on_the_first_probe_never_sleeps(self) -> None:
        outcome, _, clock = warm([(200, [])])
        assert outcome.ok and outcome.probes == 1 and clock.sleeps == [] and outcome.seconds == 0.0

    def test_the_360_second_budget_stops_the_loop_before_the_probe_bound(self) -> None:
        outcome, probes, clock = warm([(503, None)] * 6, seconds=100.0)
        assert (outcome.probes, outcome.ok, outcome.done) == (4, False, True)  # fewer than the 6 probes
        assert probes.started_at == [0.0, 110.0, 220.0, 330.0] and len(probes.script) == 2
        assert clock.sleeps == [10.0] * 3 and outcome.seconds == 430.0  # no pause after the probe that ended it

    def test_a_probe_that_would_succeed_after_the_budget_is_never_made(self) -> None:
        outcome, probes, _ = warm([(503, None)] * 4 + [(200, [])], seconds=100.0)
        assert not outcome.ok and outcome.probes == 4 and probes.script == [(200, [])]

    def test_the_pause_is_the_remaining_budget_when_that_is_under_ten_seconds(self) -> None:
        outcome, probes, clock = warm([(503, None)] * 6, seconds=355.0)
        assert clock.sleeps == [5.0] and outcome.probes == 1 and outcome.seconds == 360.0
        assert len(probes.script) == 5  # the budget was used up by the pause: no second probe

    def test_a_probe_ending_exactly_at_the_budget_is_followed_by_neither_a_pause_nor_a_probe(self) -> None:
        outcome, probes, clock = warm([(503, None)] * 6, seconds=360.0)
        assert clock.sleeps == [] and outcome.probes == 1 and outcome.seconds == 360.0 and len(probes.script) == 5

    def test_no_probe_starts_at_or_after_the_budget_and_the_phase_is_bounded_even_when_every_probe_times_out(self) -> None:
        outcome, probes, _ = warm([(504, None)] * 6, seconds=subject.WARMUP_TIMEOUT_SECONDS)
        assert probes.started_at and all(t < subject.WARMUP_BUDGET_SECONDS for t in probes.started_at)
        assert outcome.seconds <= subject.WARMUP_BUDGET_SECONDS + subject.WARMUP_TIMEOUT_SECONDS
        assert outcome.probes == 4 and outcome.seconds == 390.0

    def test_a_success_on_a_late_probe_still_passes(self) -> None:
        outcome, probes, clock = warm([(503, None)] * 5 + [(200, [])])
        assert outcome.ok and outcome.probes == 6 and clock.sleeps == [10.0] * 5 and outcome.seconds == 50.0
        # and a slow one: the sixth probe starts at 325 s, inside the budget, and is honoured though it ends at 380 s
        slow, slow_probes, _ = warm([(503, None)] * 5 + [(200, [])], seconds=55.0)
        assert slow.ok and slow.probes == 6 and slow_probes.started_at[-1] == 325.0 and slow.seconds == 380.0

    def test_the_per_probe_timeout_is_still_90_seconds(self) -> None:
        _, probes, _ = warm([(503, None)] * 3 + [(200, [])])
        assert probes.timeouts == [90.0] * 4 == [subject.WARMUP_TIMEOUT_SECONDS] * 4

    def test_the_pause_goes_through_the_injected_sleep_seam_never_a_real_sleep(self, monkeypatch: pytest.MonkeyPatch) -> None:
        def real_sleep(seconds: float) -> None:
            raise _RecorderExhausted("a real time.sleep was used")

        monkeypatch.setattr(subject.time, "sleep", real_sleep)
        _, _, clock = warm([(503, None)] * 6)
        assert clock.sleeps == [10.0] * 5

    def test_an_interrupt_during_a_pause_leaves_the_warmup_unfinished_with_its_progress(self) -> None:
        clock = Clock()

        def interrupted(seconds: float) -> None:
            raise KeyboardInterrupt

        clock.sleep = interrupted  # type: ignore[method-assign]
        probes = TimedProbes(clock, [(503, None)] * 6)
        client = subject.ApiClient(probes, API, 30.0, subject.SecretRegistry())
        client.login(subject.PasswordHolder(PASSWORD), clock.monotonic)
        outcome = subject.WarmupOutcome()
        with pytest.raises(KeyboardInterrupt):
            subject.run_warmup(client, GOLDEN, child_seams(clock, None), outcome)
        assert (outcome.probes, outcome.done, outcome.ok) == (1, False, False)

    # -- end to end through the harness ---------------------------------------------------------
    def test_six_instant_503s_are_an_incomplete_run_that_still_spent_fifty_seconds(self, h: Harness) -> None:
        h.server.prices_script = [(503, None)] * 6
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("INCOMPLETE", "WARMUP_FAILED")
        assert art["warmup"] == {"probes": 6, "last_status": 503, "seconds": 50.0}
        assert h.clock.sleeps.count(subject.WARMUP_RETRY_PAUSE_SECONDS) == 5  # the pauses went through the harness sleep
        assert h.browser.spawn_calls == [] and server_paths(h, "PUT") == [] and h.ledger_events() == []

    def test_the_budget_ends_a_slow_failing_warmup_as_incomplete_after_four_probes(self, h: Harness) -> None:
        h.server.prices_script = [(503, None)] * 6
        h.server.prices_seconds = 100.0
        assert h.run() == 1
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("INCOMPLETE", "WARMUP_FAILED")
        assert art["warmup"] == {"probes": 4, "last_status": 503, "seconds": 430.0}
        assert len(h.server.price_probes()) == 4 and len(h.server.prices_script) == 2
        assert h.browser.spawn_calls == [] and server_paths(h, "PUT") == [] and h.ledger_events() == []
        assert art["cleanup"]["armed"] is False and art["cleanup"]["result"] == "skipped_no_mutation"

    def test_a_late_success_after_five_pauses_is_still_a_go(self, h: Harness) -> None:
        h.server.prices_script = [(503, None)] * 5  # the sixth answer is the default: a 200 array
        assert h.run() == 0
        art = h.artifact()
        assert art["verdict"] == "GO" and art["warmup"] == {"probes": 6, "last_status": 200, "seconds": 50.0}

    def test_a_budget_stopped_warmup_records_only_counts_a_status_and_seconds(self, h: Harness) -> None:
        rows = (("QZXTICKA", "31.00000000"), ("QZXTICKB", "22.00000000"))
        h.cmd.results["oracle"] = subject.CmdResult(0, oracle_stdout(rows))
        h.server.rows = rows
        h.server.prices_script = [(503, {"error": "TELLTALE-BODY-VALUE"})] * 6
        h.server.prices_seconds = 100.0
        assert h.run() == 1
        recorded = "\n".join(
            h.out + h.err + h.prompts + h.input_prompts
            + [h.evidence_path.read_text(encoding="utf-8"), json.dumps(h.ledger_events())]
        )
        for secret in ("QZXTICKA", "QZXTICKB", "TELLTALE-BODY-VALUE", TOKEN, PASSWORD):
            assert secret not in recorded, secret
        assert set(h.artifact()["warmup"]) == {"probes", "last_status", "seconds"}  # allowlist-built, nothing new

    def test_an_interrupt_during_a_pause_is_recorded_like_one_during_a_probe(self, h: Harness) -> None:
        h.server.prices_script = [(503, None)] * 6
        real_sleep = h.clock.sleep

        def interrupting(seconds: float) -> None:
            if seconds == subject.WARMUP_RETRY_PAUSE_SECONDS:
                raise KeyboardInterrupt
            real_sleep(seconds)

        h.clock.sleep = interrupting  # type: ignore[method-assign]
        with pytest.raises(KeyboardInterrupt):
            h.run()
        art = h.artifact()
        assert (art["verdict"], art["stop_reason"]["code"]) == ("NON_GO", "INTERRUPTED")
        assert {"INTERRUPTED", "WARMUP_NOT_REACHED"} <= set(art["stop_reason"]["failed_criteria"])
        assert art["warmup"]["probes"] == 1 and h.browser.spawn_calls == [] and server_paths(h, "PUT") == []
        assert art["cleanup"]["armed"] is False and art["cleanup"]["result"] == "skipped_no_mutation"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
