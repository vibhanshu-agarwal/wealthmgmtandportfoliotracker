#!/usr/bin/env python3
"""Wave 10.2 Step B exit-criterion 5b production browser verifier (orchestrator).

AUTHORING ONLY. This module is never executed against production by an agent; every
production run is owner-operated. Normative contract:
docs/superpowers/plans/2026-09-18-wave10-2-step-b-5b-verifier-spec.md (sections 3-7,
Appendices A and B). The Playwright browser legs live in frontend/tests/production-e2e/
and have no verdict authority: this process derives the verdict from the ledger they append.

Procedure (serialized, no leg retried, no mutating request retried except the bounded
cleanup loop): P0 preconditions, P1 frontend binding (pre), P2 the single API login,
P3 baseline read, P3b bounded read-only warm-up of the price service, P4 browser child,
P5 independent state-based cleanup (always, once the run is armed, including for
KeyboardInterrupt), P6 post binding and the owner's post attestation, P7 verdict and evidence.
The overall deadline is the JWT's age: it runs from the start of the login call to the end of
cleanup, so the owner's typing and the blocking post-attestation wait do not count.

Secrets: the demo password comes only from a masked prompt and never reaches argv, any
child environment, the ledger, the artifact or stdout. The browser child receives an
allowlisted environment carrying only the login token.

A run killed after the login leaves a machine-readable record: the ledger holds the
orchestrator's `cleanup` event with result `cleanup_armed` (written before the child starts)
and no later `cleanup` event with result confirmed, not_needed or unconfirmed, meaning
cleanup is owed; run `--cleanup-only` to reconcile. (The spec's own `{"event": "armed"}`
line, written just before Save, is a different marker.)

Exit codes:
  0  GO (evidence written)
  1  NON_GO or INCOMPLETE (evidence written); --cleanup-only: cleanup not confirmed
  2  argument or precondition error before the login (no evidence written)
"""

from __future__ import annotations

import argparse
import base64
import contextlib
import dataclasses
import datetime
import getpass
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from pathlib import Path
from typing import (
    Any,
    Callable,
    ContextManager,
    Iterable,
    Mapping,
    Optional,
    NamedTuple,
    NoReturn,
    Protocol,
    Sequence,
)

# ---------------------------------------------------------------------------
# Literal constants (pinned by tests; none of these is a CLI flag)
# ---------------------------------------------------------------------------
EVIDENCE_SCHEMA = "wave10-5b-evidence-v1"
CLEANUP_ARTIFACT_SCHEMA = "wave10-5b-cleanup-v1"
ATTESTATION_SCHEMA = "wave10-5b-backend-attestation-v1"
GOLDEN_SCHEMA = "wave10-5b-golden-v1"
LEDGER_CONTRACT_SCHEMA = "wave10-5b-ledger-contract-v3"

FRONTEND_ORIGIN = "https://vibhanshu-ai-portfolio.dev"
API_ORIGIN = "https://api.vibhanshu-ai-portfolio.dev"
# Module-level seam: the exact origin allowlist (frontend, api). There is no CLI override.
ALLOWED_ORIGINS: tuple[str, str] = (FRONTEND_ORIGIN, API_ORIGIN)

DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110"
DEMO_EMAIL = "demo@wealthtracker.dev"

LOGIN_TIMEOUT_SECONDS = 225.0
LOGIN_GUARD_SECONDS = 165.0
OVERALL_DEADLINE_SECONDS = 2700.0
CLEANUP_MAX_ATTEMPTS = 3
STABLE_FETCH_COUNT = 3
STABLE_FETCH_MIN_SPACING_SECONDS = 5.0
MIN_BOUND_SECONDS = 300
DEFAULT_OPERATION_TIMEOUT_SECONDS = 30.0
MAX_OPERATION_TIMEOUT_SECONDS = 120.0
# Per-action timeout of the browser legs (--action-timeout-ms). A cold market-data-service can hold
# the page's price batches for close to the gateway's 55 s response timeout, so the default leaves margin.
DEFAULT_ACTION_TIMEOUT_MS = 120000
MIN_ACTION_TIMEOUT_MS = 1000
MAX_ACTION_TIMEOUT_MS = 600000
# P3b warm-up: at most this many read-only price probes, each with this timeout, all started within
# this time budget (measured on the injected monotonic clock), whichever limit comes first. After a
# failed probe the loop pauses min(WARMUP_RETRY_PAUSE_SECONDS, remaining budget), so a fast non-200 (an
# instant 503 from a restarting replica) cannot spend every probe in seconds without waking anything.
WARMUP_MAX_PROBES = 6
WARMUP_TIMEOUT_SECONDS = 90.0
WARMUP_BUDGET_SECONDS = 360.0
WARMUP_RETRY_PAUSE_SECONDS = 10.0
CHILD_POLL_SECONDS = 1.0
CHILD_KILL_GRACE_SECONDS = 10.0
ORACLE_TIMEOUT_SECONDS = 60.0
TOOL_CHECK_TIMEOUT_SECONDS = 60.0

ATTESTATION_MAX_BYTES = 65536
LEDGER_MAX_BYTES = 5 * 1024 * 1024
HTTP_BODY_MAX_BYTES = 8 * 1024 * 1024
MIN_SCAN_SECRET_LENGTH = 6
MAX_INTEGER_FACT = 10**9  # integer facts and rule literals are 0..MAX_INTEGER_FACT
MAX_HTTP_ENTRIES_PER_LEG = 200

ATTESTATION_SERVICES = ("api-gateway", "portfolio-service")
# A 429, 409, 503 or 504 on any browser leg is a failure (spec section 7).
FORBIDDEN_LEG_STATUSES = frozenset({409, 429, 503, 504})

REPO = Path(__file__).resolve().parents[1]
DEFAULT_ORACLE = REPO / "scripts" / "derive_demo_golden_state.py"
LEDGER_CONTRACT_PATH = REPO / "frontend" / "tests" / "production-e2e" / "ledger-contract.json"
PLAYWRIGHT_CONFIG_REL = "tests/production-e2e/playwright.step-b-5b.config.ts"
# The child is started as `node <cli> test ...` from frontend/: never through npm or npx, which
# contact the npm registry (update notifier) and may auto-install when non-interactive.
PLAYWRIGHT_CLI_REL = "node_modules/@playwright/test/cli.js"
# P0 proves the Chromium build Playwright will launch exists (a missing browser would otherwise surface
# only after the login and the write-ahead marker); run from frontend/ so it resolves like the child does.
BROWSER_PROBE_ARGV = (
    "node", "-e", "require('fs').accessSync(require('playwright-core').chromium.executablePath())",
)

# Environment allowlist for every child process (upper-cased names; matching is
# case-insensitive because Windows environment names are). Nothing else is inherited.
OS_ENV_ALLOWLIST = frozenset(
    {
        "PATH", "PATHEXT", "SYSTEMROOT", "WINDIR", "COMSPEC", "SYSTEMDRIVE",
        "TEMP", "TMP", "TMPDIR", "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA",
        "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "HOMEDRIVE", "HOMEPATH",
        "PLAYWRIGHT_BROWSERS_PATH", "LANG", "LC_ALL",
    }
)
CHILD_ENV_KEYS = (
    "STEP_B_5B_FRONTEND_URL",
    "STEP_B_5B_API_URL",
    "STEP_B_5B_TOKEN",
    "STEP_B_5B_EMAIL",
    "STEP_B_5B_USER_ID",
    "STEP_B_5B_WORK_DIR",
    "STEP_B_5B_GOLDEN_FILE",
    "STEP_B_5B_BASELINE_VERSION",
    "STEP_B_5B_ALLOW_ACTIVE_PRESENCE",
    "STEP_B_5B_ACTION_TIMEOUT_MS",
)
CHILD_STATIC_FLAGS = ("--reporter=null", "--workers=1", "--retries=0")

CLEANUP_TRANSPORTS = ("none", "saved_jwt", "internal_break_glass")
CLEANUP_STEP_RESULTS = (
    "cleanup_armed", "observed_golden", "observed_non_golden", "observe_failed",
    "reset_200", "reset_409", "reset_other", "reset_error",
    "break_glass_200", "break_glass_other", "break_glass_error",
    "not_needed", "confirmed", "unconfirmed",
)
CLEANUP_FINAL_RESULTS = (
    "not_needed", "confirmed", "unconfirmed", "not_reached", "skipped_no_mutation",
)

# leg -> Wave 9 route (None: not a route leg), and route -> (leg, evidence level, CI-only).
LEG_ROUTES: dict[str, Optional[str]] = {
    "L0": None, "L1": "controls", "L2": "9.5", "L3": None, "L4": "9.1",
    "L5": "9.4", "L6": "9.3", "L7": None, "L8": "9.2", "L9": "reset",
}
ROUTE_TABLE: dict[str, tuple[str, str, str]] = {
    "9.1": ("L4", "production-browser", "304/If-None-Match revalidation"),
    "9.2": ("L8", "production-browser; independent read production-API-only", "409 conflict UI"),
    "9.3": ("L6", "production-browser", "picker price fail-soft paths"),
    "9.4": ("L5", "production-browser (network status and boolean)", "presence expiry, fail-open UI"),
    "9.5": ("L2", "production-browser", "STALE/UNKNOWN/MISSING variants"),
    "reset": ("L9", "production-browser; independent read production-API-only", "409 conflict branch"),
}
# A leg's evidence level is derived from ROUTE_TABLE so the artifact can never state two different
# levels for one route; legs that are not route legs (L0, L1, L3, L7) carry the default.
DEFAULT_LEG_EVIDENCE_LEVEL = "production-browser"
LEG_EVIDENCE_LEVELS: dict[str, str] = {leg: DEFAULT_LEG_EVIDENCE_LEVEL for leg in LEG_ROUTES}
LEG_EVIDENCE_LEVELS.update({leg: level for leg, level, _ in ROUTE_TABLE.values()})
FRONTEND_CLAIM = "observed served build; source commit not observable from the site"
BACKEND_CLAIM = "owner-attested management-plane read"

_UTC_Z_RE = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?Z$"
)
_HEX40_RE = re.compile(r"^[0-9a-fA-F]{40}$")
_BUILD_ID_RE = re.compile(r"^[A-Za-z0-9_-]{6,64}$")
_IDENT_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
_QUANTITY_RE = re.compile(r"^[0-9]+\.[0-9]{8}$")
_CATALOG_SHA_RE = re.compile(r"^[0-9A-Fa-f]{64}$")
_REVISION_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
_ORIGIN_RE = re.compile(r"^https://[a-z0-9][a-z0-9.-]*[a-z0-9]$")
_LEDGER_TIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")

HttpCall = Callable[[str, str, dict[str, str], Any, float], tuple[int, Any]]
FetchRoot = Callable[[str, float], tuple[int, str, bytes]]


# ---------------------------------------------------------------------------
# Errors and small value types
# ---------------------------------------------------------------------------
class PreconditionError(Exception):
    """A failure before the login: exit 2, nothing consumed, no evidence."""

    def __init__(self, code: str, text: str = "") -> None:
        super().__init__(code)
        self.code = code
        self.text = text or code


class ObserveError(Exception):
    """An identity-checked portfolio read failed (code only; never a body)."""

    def __init__(self, code: str, status: Optional[int] = None) -> None:
        super().__init__(code)
        self.code = code
        self.status = status


@dataclasses.dataclass(frozen=True)
class CmdResult:
    returncode: int
    stdout: str = ""
    stderr: str = ""


RunCmd = Callable[
    [list[str], Optional[Path], Optional[Mapping[str, str]], float], CmdResult
]


class ChildHandle(Protocol):
    def poll(self) -> Optional[int]: ...
    def kill_tree(self) -> None: ...
    def close(self) -> None: ...


SpawnChild = Callable[[list[str], Mapping[str, str], Path, Path], ChildHandle]


class FileOps:
    """Real filesystem operations; tests point this at a temporary directory."""

    def exists(self, path: Path) -> bool:
        return path.exists()

    def is_dir(self, path: Path) -> bool:
        return path.is_dir()

    def is_file(self, path: Path) -> bool:
        return path.is_file()

    def mkdir_exclusive(self, path: Path) -> None:
        os.mkdir(path, 0o700)  # owner-only (POSIX); FileExistsError when it already exists

    def read_bytes(self, path: Path, max_bytes: int) -> bytes:
        with open(path, "rb") as handle:
            data = handle.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise OSError("file exceeds the size cap")
        return data

    def append_line(self, path: Path, text: str) -> None:
        with open(path, "ab") as handle:
            handle.write(text.encode("utf-8") + b"\n")
            handle.flush()
            os.fsync(handle.fileno())

    def write_exclusive(self, path: Path, data: bytes) -> None:
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
        descriptor = os.open(path, flags, 0o600)
        try:
            view = memoryview(data)
            while view:
                written = os.write(descriptor, view)
                view = view[written:]
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


@dataclasses.dataclass
class Seams:
    """Every side effect the orchestrator performs, injectable for offline tests."""

    http_call: HttpCall
    fetch_root_html: FetchRoot
    run_cmd: RunCmd
    spawn_child: SpawnChild
    monotonic: Callable[[], float]
    now_utc: Callable[[], datetime.datetime]
    sleep: Callable[[float], None]
    getpass_fn: Callable[[str], str]
    input_fn: Callable[[str], str]
    fs: FileOps
    out: Callable[[str], None]
    err: Callable[[str], None]
    defer_sigint: Callable[[], ContextManager[None]]
    stdin_isatty: Callable[[], bool]  # getpass echoes on a non-console stdin: refuse to prompt


class Fingerprint(NamedTuple):
    """What is kept of an owner secret form: its length, its SHA-256 and a CRC-32 that only
    serves as a cheap prefilter (a window is hashed with SHA-256 only when its CRC-32 matches)."""

    length: int
    sha256: str
    crc32: int


def make_fingerprint(form: str) -> Fingerprint:
    data = form.encode("utf-8")
    return Fingerprint(len(form), hashlib.sha256(data).hexdigest(), zlib.crc32(data))


class SecretRegistry:
    """Scan material for the final artifact and the child environment.

    The owner's secrets (demo password, break-glass key) are kept only as fingerprints (length,
    SHA-256, CRC-32 prefilter) of their raw, JSON-escaped, percent-encoded and base64 forms, so
    the raw values can be dropped as soon as they are used. The session token is kept raw: the
    API client holds it until cleanup ends anyway."""

    def __init__(self) -> None:
        self._raw: list[str] = []
        self._fingerprints: set[Fingerprint] = set()

    def add(self, value: Optional[str]) -> None:
        if value and value not in self._raw:
            self._raw.append(value)

    def add_owner_secret(self, value: Optional[str]) -> None:
        if value and len(value) >= MIN_SCAN_SECRET_LENGTH:
            for form in _secret_forms(value):
                self._fingerprints.add(make_fingerprint(form))

    def values(self) -> tuple[str, ...]:
        return tuple(self._raw)

    def owner_fingerprints(self) -> frozenset[Fingerprint]:
        return frozenset(self._fingerprints)

    def contains_owner_secret(self, text: str) -> bool:
        return fingerprint_hit(text, self._fingerprints)


class PasswordHolder:
    """Holds the demo password only until the login returns (best effort in CPython)."""

    def __init__(self, value: str) -> None:
        self._value: Optional[str] = value

    def take(self) -> str:
        if self._value is None:
            raise RuntimeError("password already released")
        return self._value

    def clear(self) -> None:
        self._value = None

    @property
    def cleared(self) -> bool:
        return self._value is None


# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------
def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def utc_z(moment: datetime.datetime) -> str:
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def utc_ms(moment: datetime.datetime) -> str:
    utc = moment.astimezone(datetime.timezone.utc)
    return utc.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc.microsecond // 1000:03d}Z"


def parse_utc_z(text: str) -> datetime.datetime:
    """Strict '...Z' UTC timestamp; ValueError on anything else."""
    match = _UTC_Z_RE.match(text) if isinstance(text, str) else None
    if match is None:
        raise ValueError("not a UTC 'Z' timestamp")
    year, month, day, hour, minute, second = (int(match.group(i)) for i in range(1, 7))
    fraction = (match.group(7) or "").ljust(6, "0")
    return datetime.datetime(
        year, month, day, hour, minute, second, int(fraction or "0"),
        tzinfo=datetime.timezone.utc,
    )


def is_strict_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def json_compact(document: Any) -> str:
    return json.dumps(document, separators=(",", ":"), ensure_ascii=True)


def os_env(environ: Mapping[str, str]) -> dict[str, str]:
    """The OS variables Node/Playwright/git need to start; nothing else is inherited."""
    return {k: v for k, v in environ.items() if k.upper() in OS_ENV_ALLOWLIST}


def _is_within(path: Path, root: Path) -> bool:
    candidate = os.path.normcase(os.path.abspath(path))
    base = os.path.normcase(os.path.abspath(root))
    try:
        return os.path.commonpath([candidate, base]) == base
    except ValueError:  # different drives
        return False


# ---------------------------------------------------------------------------
# Portfolio identity, holdings and golden-state helpers (copied from Step A's proven
# rules so this verifier does not import accepted Step A code)
# ---------------------------------------------------------------------------
def select_portfolio(payload: Any) -> dict[str, Any]:
    """Identity-checked select: a JSON list with exactly one entry whose userId is DEMO_USER_ID.
    A bare object is refused (the browser side rejects it too), so both readers agree."""
    if not isinstance(payload, list):
        raise ObserveError("NOT_A_LIST")
    matches = [r for r in payload if isinstance(r, dict) and r.get("userId") == DEMO_USER_ID]
    if len(matches) != 1:
        raise ObserveError("IDENTITY_MISMATCH")
    portfolio = matches[0]
    version = portfolio.get("version")
    if not is_strict_int(version) or version < 0:
        raise ObserveError("VERSION_INVALID")
    if not isinstance(portfolio.get("holdings"), list):
        raise ObserveError("HOLDINGS_MALFORMED")
    return portfolio


def wire_holdings_sorted(holdings: Iterable[Mapping[str, Any]]) -> list[dict[str, str]]:
    normalized: list[dict[str, str]] = []
    seen: set[str] = set()
    for row in holdings:
        if not isinstance(row, Mapping):
            raise ValueError("malformed holding")
        ticker = row.get("assetTicker", row.get("ticker"))
        quantity = row.get("quantity")
        if not isinstance(ticker, str) or not ticker or ticker in seen:
            raise ValueError("malformed or duplicate holding")
        if not isinstance(quantity, str):
            raise ValueError("quantity is not a string")
        seen.add(ticker)
        normalized.append({"assetTicker": ticker, "quantity": quantity})
    return sorted(normalized, key=lambda r: r["assetTicker"])


def holdings_equal_golden(holdings: Any, golden: Sequence[Mapping[str, str]]) -> bool:
    try:
        return wire_holdings_sorted(holdings) == wire_holdings_sorted(golden)
    except (ValueError, TypeError):
        return False


@dataclasses.dataclass(frozen=True)
class Golden:
    holdings: tuple[tuple[str, str], ...]  # (assetTicker, 8-dp quantity), ASCII-sorted
    catalog_sha256: str

    @property
    def active_tickers(self) -> list[str]:
        return [ticker for ticker, _ in self.holdings]

    def as_wire(self) -> list[dict[str, str]]:
        return [{"assetTicker": t, "quantity": q} for t, q in self.holdings]

    def as_file_document(self) -> dict[str, Any]:
        return {
            "schema": GOLDEN_SCHEMA,
            "catalogSha256": self.catalog_sha256,
            "holdings": self.as_wire(),
            "activeTickers": self.active_tickers,
        }


def parse_oracle_output(stdout: str) -> Golden:
    """Validate the oracle document. Golden holds every ACTIVE asset, so activeTickers
    is exactly the sorted wire-holding tickers."""
    try:
        document = json.loads(stdout)
    except (ValueError, TypeError, RecursionError) as exc:
        # JSONDecodeError is a ValueError, and so is the error json.loads raises for an integer literal
        # longer than sys.get_int_max_str_digits(): both must be a precondition refusal, never a traceback.
        raise PreconditionError("ORACLE_OUTPUT_INVALID", "oracle output is not valid JSON") from exc
    if not isinstance(document, dict):
        raise PreconditionError("ORACLE_OUTPUT_INVALID", "oracle output is not an object")
    wire = document.get("wireHoldings")
    metadata = document.get("metadata")
    if not isinstance(wire, list) or not wire or not isinstance(metadata, dict):
        raise PreconditionError("ORACLE_OUTPUT_INVALID", "oracle returned no wireHoldings")
    try:
        normalized = wire_holdings_sorted(wire)
    except ValueError as exc:
        raise PreconditionError("ORACLE_OUTPUT_INVALID", "oracle holding malformed") from exc
    for row in normalized:
        if not _QUANTITY_RE.match(row["quantity"]):
            raise PreconditionError("ORACLE_OUTPUT_INVALID", "oracle quantity is not 8-decimal")
    digest = metadata.get("catalogSha256")
    if not isinstance(digest, str) or not _CATALOG_SHA_RE.match(digest):
        raise PreconditionError("ORACLE_OUTPUT_INVALID", "oracle catalogSha256 is malformed")
    active_count = metadata.get("activeEntryCount")
    if active_count is not None and active_count != len(normalized):
        raise PreconditionError("ORACLE_OUTPUT_INVALID", "oracle activeEntryCount mismatch")
    return Golden(
        holdings=tuple((r["assetTicker"], r["quantity"]) for r in normalized),
        catalog_sha256=digest.upper(),
    )


# ---------------------------------------------------------------------------
# Ledger contract: the machine-readable vocabulary shared with the browser child
# ---------------------------------------------------------------------------
_FACT_ENUM_KEYS = {
    "freshnessState": "freshnessStates",
    "mutationSkippedReason": "mutationSkippedReasons",
}
_FACT_SCALAR_TYPES = ("boolean", "integer", "boolean|null")
# The exact top-level key set of the contract (schema v2 added passFactRules and passFacts, v3 added
# passHttpRules and passHttp).
_CONTRACT_TOP_LEVEL_KEYS = frozenset(
    {
        "schema", "commonFields", "sources", "specEvents", "orchestratorEvents", "legs",
        "statuses", "reasons", "methods", "paths", "mutationSkippedReasons", "freshnessStates",
        "passFactRules", "passFacts", "passHttpRules", "passHttp", "legFacts",
    }
)
_PASS_RULE_FORMS = ("not", "min", "type", "equalsFact")
# Every closed list is pinned exactly (members and order), so this reader and the TypeScript reader
# accept and refuse the same contracts: a member gained, lost or reordered is a contract error.
_CONTRACT_EXACT = {
    "commonFields": ("seq", "tUtc", "src", "event"),
    "sources": ("spec", "orchestrator"),
    "specEvents": ("leg", "armed"),
    "orchestratorEvents": ("child_started", "child_done", "cleanup"),
    "legs": tuple(f"L{i}" for i in range(10)),
    "statuses": ("passed", "failed", "skipped"),
    "reasons": (
        "OK", "ASSERTION_FAILED", "TIMEOUT", "HTTP_STATUS_NOT_200", "RATE_LIMITED", "CONFLICT",
        "REQUEST_FAILED", "SKIPPED_PRIOR_FAILURE", "SKIPPED_VISITOR_PRESENT",
        "SKIPPED_BASELINE_NOT_GOLDEN", "EXCEPTION",
    ),
    "methods": ("GET", "PUT"),
    "paths": (
        "/api/portfolio", "/api/portfolio/summary", "/api/assets", "/api/presence/demo",
        "/api/market/prices", "/api/portfolio/holdings", "/api/portfolio/demo-reset",
    ),
    "mutationSkippedReasons": ("NONE", "LEG_FAILED", "VISITOR_PRESENT", "BASELINE_NOT_GOLDEN"),
    "freshnessStates": ("FRESH", "STALE", "UNKNOWN", "MISSING", "ABSENT"),
}
# passHttp: the keys a leg's http rule may carry, and where a status fact is read from.
_PASS_HTTP_KEYS = frozenset({"entries", "countFact", "statusFacts", "firstStatus", "allStatus"})
_PASS_HTTP_POSITIONS = ("first", "last")
# A skipped leg's reason code and its mutationSkippedReason fact must agree.
_SKIP_REASON_TO_FACT = {
    "SKIPPED_VISITOR_PRESENT": "VISITOR_PRESENT",
    "SKIPPED_BASELINE_NOT_GOLDEN": "BASELINE_NOT_GOLDEN",
    "SKIPPED_PRIOR_FAILURE": "LEG_FAILED",
}
# Reason codes only the orchestrator assigns while deriving artifact legs from the ledger.
ORCHESTRATOR_LEG_REASONS = (
    "LEDGER_INVALID", "LEG_MISSING", "FORBIDDEN_HTTP_STATUS", "FACTS_INCOMPLETE",
    "FACTS_CONTRADICT_PASS", "HTTP_CONTRADICT_PASS",
)


@dataclasses.dataclass(frozen=True)
class LedgerContract:
    common_fields: tuple[str, ...]
    sources: tuple[str, ...]
    spec_events: tuple[str, ...]
    orchestrator_events: tuple[str, ...]
    legs: tuple[str, ...]
    statuses: tuple[str, ...]
    reasons: tuple[str, ...]
    methods: tuple[str, ...]
    paths: tuple[str, ...]
    mutation_skipped_reasons: tuple[str, ...]
    freshness_states: tuple[str, ...]
    leg_facts: Mapping[str, Mapping[str, str]]
    # leg -> fact -> rule (a literal, or a one-key dict: not | min | type | equalsFact)
    pass_facts: Mapping[str, Mapping[str, Any]]
    # leg -> http rule (entries | countFact | statusFacts | firstStatus | allStatus), validated
    pass_http: Mapping[str, Mapping[str, Any]]


def _contract_list(document: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = document.get(key)
    if (
        not isinstance(value, list)
        or not value
        or not all(isinstance(item, str) and item for item in value)
        or len(set(value)) != len(value)
    ):
        raise PreconditionError("LEDGER_CONTRACT_INVALID", f"contract field {key} is malformed")
    return tuple(value)


def parse_ledger_contract(data: bytes) -> LedgerContract:
    try:
        document = json.loads(data.decode("utf-8"))
    except (ValueError, RecursionError) as exc:
        # UnicodeDecodeError, JSONDecodeError and the overlong-integer-literal error are all ValueErrors
        raise PreconditionError("LEDGER_CONTRACT_INVALID", "ledger contract is not valid JSON") from exc
    if not isinstance(document, dict) or document.get("schema") != LEDGER_CONTRACT_SCHEMA:
        raise PreconditionError("LEDGER_CONTRACT_INVALID", "ledger contract schema mismatch")
    if set(document) != _CONTRACT_TOP_LEVEL_KEYS:
        raise PreconditionError("LEDGER_CONTRACT_INVALID", "contract top-level keys changed")
    for text_key in ("passFactRules", "passHttpRules"):
        rules_text = document.get(text_key)
        if not isinstance(rules_text, str) or not rules_text.strip():
            raise PreconditionError("LEDGER_CONTRACT_INVALID", f"contract {text_key} is missing")
    lists = {
        key: _contract_list(document, key)
        for key in (
            "commonFields", "sources", "specEvents", "orchestratorEvents", "legs",
            "statuses", "reasons", "methods", "paths", "mutationSkippedReasons",
            "freshnessStates",
        )
    }
    for key, expected in _CONTRACT_EXACT.items():
        if lists[key] != expected:
            raise PreconditionError("LEDGER_CONTRACT_INVALID", f"contract field {key} changed")
    raw_facts = document.get("legFacts")
    if not isinstance(raw_facts, dict) or set(raw_facts) != set(lists["legs"]):
        raise PreconditionError("LEDGER_CONTRACT_INVALID", "contract legFacts do not cover the legs")
    leg_facts: dict[str, dict[str, str]] = {}
    for leg, facts in raw_facts.items():
        if not isinstance(facts, dict) or not facts:
            raise PreconditionError("LEDGER_CONTRACT_INVALID", f"contract legFacts {leg} malformed")
        for name, kind in facts.items():
            if not isinstance(name, str) or kind not in _FACT_SCALAR_TYPES + tuple(_FACT_ENUM_KEYS):
                raise PreconditionError("LEDGER_CONTRACT_INVALID", f"contract fact type in {leg} unknown")
        leg_facts[leg] = dict(facts)
    contract = LedgerContract(
        common_fields=lists["commonFields"], sources=lists["sources"],
        spec_events=lists["specEvents"], orchestrator_events=lists["orchestratorEvents"],
        legs=lists["legs"], statuses=lists["statuses"], reasons=lists["reasons"],
        methods=lists["methods"], paths=lists["paths"],
        mutation_skipped_reasons=lists["mutationSkippedReasons"],
        freshness_states=lists["freshnessStates"], leg_facts=leg_facts, pass_facts={}, pass_http={},
    )
    return dataclasses.replace(
        contract,
        pass_facts=_parse_pass_facts(document.get("passFacts"), contract),
        pass_http=_parse_pass_http(document.get("passHttp"), contract),
    )


def _literal_fits_kind(kind: str, value: Any, contract: LedgerContract) -> bool:
    """A rule literal must be a legal value of its fact (never null), so no rule is
    unsatisfiable by type. Booleans and integers are never coerced into each other."""
    if kind in ("boolean", "boolean|null"):
        return isinstance(value, bool)
    if kind == "integer":
        return is_strict_int(value) and 0 <= value <= MAX_INTEGER_FACT
    if kind == "freshnessState":
        return isinstance(value, str) and value in contract.freshness_states
    if kind == "mutationSkippedReason":
        return isinstance(value, str) and value in contract.mutation_skipped_reasons
    return False


def _parse_pass_rule(where: str, rule: Any, fact: str, kinds: Mapping[str, str],
                     contract: LedgerContract) -> Any:
    """One rule: a literal, or exactly one of {not}, {min}, {type: boolean}, {equalsFact}."""
    kind = kinds[fact]
    bad = PreconditionError("LEDGER_CONTRACT_INVALID", f"contract passFacts {where} is malformed")
    if not isinstance(rule, dict):
        if not _literal_fits_kind(kind, rule, contract):
            raise bad
        return rule
    if len(rule) != 1:
        raise bad
    ((form, argument),) = rule.items()
    if form == "not":
        ok = _literal_fits_kind(kind, argument, contract)
    elif form == "min":
        ok = kind == "integer" and is_strict_int(argument) and 0 <= argument <= MAX_INTEGER_FACT
    elif form == "type":
        ok = argument == "boolean" and kind in ("boolean", "boolean|null")
    elif form == "equalsFact":
        ok = (
            isinstance(argument, str) and argument != fact and argument in kinds
            and kinds[argument] == kind
        )
    else:
        ok = False
    if not ok:
        raise bad
    return {form: argument}


def _parse_pass_facts(raw: Any, contract: LedgerContract) -> dict[str, dict[str, Any]]:
    """The pass table must describe exactly the legs, constrain at least one fact per leg and
    name only facts the leg has; anything else fails closed (a missing table would silently
    switch enforcement off)."""
    if not isinstance(raw, dict) or set(raw) != set(contract.legs):
        raise PreconditionError("LEDGER_CONTRACT_INVALID", "contract passFacts do not cover the legs")
    parsed: dict[str, dict[str, Any]] = {}
    for leg in contract.legs:
        rules, kinds = raw[leg], contract.leg_facts[leg]
        if not isinstance(rules, dict) or not rules:
            raise PreconditionError("LEDGER_CONTRACT_INVALID", f"contract passFacts {leg} malformed")
        parsed[leg] = {}
        for fact, rule in rules.items():
            if fact not in kinds:
                raise PreconditionError(
                    "LEDGER_CONTRACT_INVALID", f"contract passFacts {leg} names an unknown fact"
                )
            parsed[leg][fact] = _parse_pass_rule(f"{leg}.{fact}", rule, fact, kinds, contract)
    return parsed


def _is_http_status(value: Any) -> bool:
    """A real HTTP status: an integer in 100..599 (never a bool, a float or a string)."""
    return is_strict_int(value) and 100 <= value <= 599


def _parse_pass_http(raw: Any, contract: LedgerContract) -> dict[str, dict[str, Any]]:
    """The http table must describe exactly the legs. Per leg: `entries` is required ("none", or
    {method, path} inside the closed vocabularies); `"none"` is exclusive (the rule then carries no
    other key); `countFact` and every `statusFacts` key must name an integer fact of that leg, a
    `statusFacts` value is "first" or "last" and a `statusFacts` map, when present, is non-empty;
    `firstStatus` and `allStatus` are integers in 100..599; any other key is unknown. These are the
    rules the TypeScript loader enforces too, so a contract edit is accepted or refused by both
    readers alike. Anything else fails closed: a missing or ignored table would silently switch the
    enforcement off."""
    if not isinstance(raw, dict) or set(raw) != set(contract.legs):
        raise PreconditionError("LEDGER_CONTRACT_INVALID", "contract passHttp does not cover the legs")
    parsed: dict[str, dict[str, Any]] = {}
    for leg in contract.legs:
        rule, kinds = raw[leg], contract.leg_facts[leg]
        bad = PreconditionError("LEDGER_CONTRACT_INVALID", f"contract passHttp {leg} is malformed")
        if not isinstance(rule, dict) or "entries" not in rule or not set(rule) <= _PASS_HTTP_KEYS:
            raise bad
        entries = rule["entries"]
        if entries == "none":
            if set(rule) != {"entries"}:  # no request is expected, so no count or status can be stated
                raise bad
        elif (
            not isinstance(entries, dict) or set(entries) != {"method", "path"}
            or entries["method"] not in contract.methods or entries["path"] not in contract.paths
        ):
            raise bad
        checked: dict[str, Any] = {"entries": entries if entries == "none" else dict(entries)}
        if "countFact" in rule:
            count_fact = rule["countFact"]
            if not isinstance(count_fact, str) or kinds.get(count_fact) != "integer":
                raise bad
            checked["countFact"] = count_fact
        if "statusFacts" in rule:
            status_facts = rule["statusFacts"]
            if not isinstance(status_facts, dict) or not status_facts:
                raise bad
            for fact, position in status_facts.items():
                if kinds.get(fact) != "integer" or position not in _PASS_HTTP_POSITIONS:
                    raise bad
            checked["statusFacts"] = dict(status_facts)
        for key in ("firstStatus", "allStatus"):
            if key in rule:
                if not _is_http_status(rule[key]):
                    raise bad
                checked[key] = rule[key]
        parsed[leg] = checked
    return parsed


def load_ledger_contract(fs: FileOps, path: Path) -> LedgerContract:
    try:
        data = fs.read_bytes(path, 256 * 1024)
    except OSError as exc:
        raise PreconditionError("LEDGER_CONTRACT_MISSING", "ledger contract could not be read") from exc
    return parse_ledger_contract(data)


# ---------------------------------------------------------------------------
# Ledger validation and parsing (fail closed on any deviation from the vocabulary)
# ---------------------------------------------------------------------------
class LedgerLineError(Exception):
    def __init__(self, code: str, leg: Optional[str] = None) -> None:
        super().__init__(code)
        self.code = code
        self.leg = leg


@dataclasses.dataclass
class LegRecord:
    leg: str
    status: str
    reason: str
    http: list[dict[str, Any]]
    facts: dict[str, Any]
    line_no: int
    poisoned: bool = False


@dataclasses.dataclass
class LedgerParse:
    legs: dict[str, LegRecord]
    invalid_lines: list[tuple[int, str]]
    armed_line_no: Optional[int]
    line_count: int
    child_done_line_no: Optional[int] = None

    @property
    def clean(self) -> bool:
        return not self.invalid_lines


def _fact_value_ok(kind: str, value: Any, contract: LedgerContract) -> bool:
    if kind == "boolean":
        return isinstance(value, bool)
    if kind == "integer":
        return is_strict_int(value) and 0 <= value <= MAX_INTEGER_FACT
    if kind == "boolean|null":
        return value is None or isinstance(value, bool)
    if kind == "freshnessState":
        return isinstance(value, str) and value in contract.freshness_states
    if kind == "mutationSkippedReason":
        return isinstance(value, str) and value in contract.mutation_skipped_reasons
    return False


def _http_status_ok(status: Any) -> bool:
    """0 (the request failed) or a real HTTP status."""
    return is_strict_int(status) and (status == 0 or _is_http_status(status))


def _validate_http(entries: Any, contract: LedgerContract, leg: str) -> list[dict[str, Any]]:
    if not isinstance(entries, list) or len(entries) > MAX_HTTP_ENTRIES_PER_LEG:
        raise LedgerLineError("HTTP_INVALID", leg)
    checked: list[dict[str, Any]] = []
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"method", "path", "status"}:
            raise LedgerLineError("HTTP_INVALID", leg)
        status = entry["status"]
        if (
            entry["method"] not in contract.methods
            or entry["path"] not in contract.paths
            or not _http_status_ok(status)
        ):
            raise LedgerLineError("HTTP_INVALID", leg)
        checked.append({"method": entry["method"], "path": entry["path"], "status": status})
    return checked


def _validate_facts(facts: Any, contract: LedgerContract, leg: str) -> dict[str, Any]:
    if not isinstance(facts, dict):
        raise LedgerLineError("FACTS_INVALID", leg)
    allowed = contract.leg_facts[leg]
    for key, value in facts.items():
        if key not in allowed:
            raise LedgerLineError("UNKNOWN_FACT_KEY", leg)
        if not _fact_value_ok(allowed[key], value, contract):
            raise LedgerLineError("FACT_VALUE_INVALID", leg)
    return dict(facts)


def _validate_leg_event(obj: dict[str, Any], contract: LedgerContract, leg_hint: Optional[str],
                        line_no: int) -> LegRecord:
    if set(obj) - set(contract.common_fields) - {"leg", "status", "reason", "http", "facts"}:
        raise LedgerLineError("UNKNOWN_KEY", leg_hint)
    if leg_hint is None:
        raise LedgerLineError("LEG_UNKNOWN")
    if "http" not in obj or "facts" not in obj:  # missing structure fails closed, as in the TS validator
        raise LedgerLineError("MISSING_KEY", leg_hint)
    status, reason = obj.get("status"), obj.get("reason")
    if status not in contract.statuses or reason not in contract.reasons:
        raise LedgerLineError("VOCABULARY_VIOLATION", leg_hint)
    is_ok = reason == "OK"
    is_skip = reason.startswith("SKIPPED_")
    if (status == "passed") != is_ok or (status == "skipped") != is_skip:
        raise LedgerLineError("STATUS_REASON_MISMATCH", leg_hint)
    http = _validate_http(obj["http"], contract, leg_hint)
    facts = _validate_facts(obj["facts"], contract, leg_hint)
    skipped_fact = facts.get("mutationSkippedReason")
    if skipped_fact is not None:
        if status == "passed" and skipped_fact != "NONE":
            raise LedgerLineError("STATUS_FACT_MISMATCH", leg_hint)
        if status == "skipped" and _SKIP_REASON_TO_FACT.get(reason) not in (None, skipped_fact):
            raise LedgerLineError("STATUS_FACT_MISMATCH", leg_hint)
    return LegRecord(leg_hint, status, reason, http, facts, line_no)


def _validate_orchestrator_event(obj: dict[str, Any], contract: LedgerContract) -> None:
    extra = set(obj) - set(contract.common_fields)
    event = obj["event"]
    if event == "child_started":
        ok = not extra
    elif event == "child_done":
        ok = extra == {"exit"} and is_strict_int(obj["exit"]) and abs(obj["exit"]) < 2**33
    else:  # cleanup
        ok = (
            extra == {"attempt", "transport", "result"}
            and is_strict_int(obj["attempt"]) and 0 <= obj["attempt"] <= 100
            and obj["transport"] in CLEANUP_TRANSPORTS
            and obj["result"] in CLEANUP_STEP_RESULTS
        )
    if not ok:
        raise LedgerLineError("ORCHESTRATOR_EVENT_INVALID")


class _DuplicateKeyError(Exception):
    """A JSON object repeats a key (json.loads would silently keep the last value)."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKeyError()
        result[key] = value
    return result


def _lenient_leg_hint(line: str, contract: LedgerContract) -> Optional[str]:
    """The leg a line names, read leniently (last duplicate wins), only so an invalid line can
    poison its leg; the line itself stays invalid whatever this returns."""
    try:
        obj = json.loads(line)
    except (ValueError, RecursionError):
        return None
    hint = obj.get("leg") if isinstance(obj, dict) else None
    return hint if isinstance(hint, str) and hint in contract.legs else None


def parse_ledger(data: bytes, contract: LedgerContract) -> LedgerParse:
    """Strictly parse the shared ledger. Unknown keys, repeated keys or out-of-vocabulary values
    make the line invalid, and so does a line that is not JSON at all (INVALID_JSON, which includes a
    JSON integer literal longer than the interpreter's int-string limit); a second final event for a
    leg is invalid (DUPLICATE_LEG) and so is a second `armed` line (DUPLICATE_ARMED); any line from
    the browser child after the orchestrator's child_done is invalid (the file is read only once the
    child is confirmed dead, so such a line comes from a still-live or foreign writer). An invalid
    line attributable to a leg poisons that leg; every invalid line makes the ledger not clean."""
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return LedgerParse({}, [(0, "NOT_UTF8")], None, 0)
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    legs: dict[str, LegRecord] = {}
    invalid: list[tuple[int, str]] = []
    poisoned: set[str] = set()
    armed_line: Optional[int] = None
    child_done_line: Optional[int] = None
    next_seq: dict[str, int] = {}
    for line_no, line in enumerate(lines, start=1):
        leg_hint: Optional[str] = None
        try:
            try:
                obj = json.loads(line, object_pairs_hook=_reject_duplicate_keys)
            except _DuplicateKeyError:
                raise LedgerLineError("DUPLICATE_KEY", _lenient_leg_hint(line, contract)) from None
            except (ValueError, RecursionError):
                # JSONDecodeError is a ValueError, and so is the error json.loads raises for an integer
                # literal longer than sys.get_int_max_str_digits(): one hostile line must make only
                # that line invalid, never abort the parse of the whole ledger.
                raise LedgerLineError("INVALID_JSON") from None
            if not isinstance(obj, dict):
                raise LedgerLineError("NOT_OBJECT")
            hint = obj.get("leg")
            leg_hint = hint if isinstance(hint, str) and hint in contract.legs else None
            if any(key not in obj for key in contract.common_fields):
                raise LedgerLineError("MISSING_COMMON_FIELD", leg_hint)
            seq, src, event = obj["seq"], obj["src"], obj["event"]
            if not is_strict_int(seq) or src not in contract.sources:
                raise LedgerLineError("COMMON_FIELD_INVALID", leg_hint)
            seq_ok = seq == next_seq.get(src, 1)
            next_seq[src] = seq + 1
            if not seq_ok:
                raise LedgerLineError("SEQ_OUT_OF_ORDER", leg_hint)
            if not isinstance(obj["tUtc"], str) or not _LEDGER_TIME_RE.match(obj["tUtc"]):
                raise LedgerLineError("TIME_INVALID", leg_hint)
            allowed = contract.spec_events if src == "spec" else contract.orchestrator_events
            if event not in allowed:
                raise LedgerLineError("EVENT_NOT_ALLOWED_FOR_SOURCE", leg_hint)
            if src == "spec" and child_done_line is not None:
                raise LedgerLineError("SPEC_AFTER_CHILD_DONE", leg_hint)
            if event == "leg":
                record = _validate_leg_event(obj, contract, leg_hint, line_no)
                if record.leg in legs:
                    raise LedgerLineError("DUPLICATE_LEG", record.leg)
                legs[record.leg] = record
            elif event == "armed":
                if set(obj) != set(contract.common_fields):
                    raise LedgerLineError("UNKNOWN_KEY")
                if armed_line is not None:  # the honest writer emits exactly one, right before Save
                    raise LedgerLineError("DUPLICATE_ARMED")
                armed_line = line_no
            else:
                _validate_orchestrator_event(obj, contract)
                if event == "child_done" and child_done_line is None:
                    child_done_line = line_no
        except LedgerLineError as exc:
            invalid.append((line_no, exc.code))
            if exc.leg is not None:
                poisoned.add(exc.leg)
    for leg in poisoned:
        record = legs.get(leg) or LegRecord(leg, "failed", "LEDGER_INVALID", [], {}, 0)
        record.poisoned = True
        legs[leg] = record
    return LedgerParse(legs, invalid, armed_line, len(lines), child_done_line)


def spec_armed_before_l8(parsed: Optional[LedgerParse]) -> bool:
    """The spec's own `armed` line is written immediately before Save: after L7's final event and
    before L8's. An `armed` anywhere else (first line, before L7, after L8) does not count."""
    if parsed is None or parsed.armed_line_no is None:
        return False
    l7, l8 = parsed.legs.get("L7"), parsed.legs.get("L8")
    return bool(
        l7 is not None and l8 is not None and l7.line_no < parsed.armed_line_no < l8.line_no
    )


@dataclasses.dataclass(frozen=True)
class LegResult:
    leg: str
    route: Optional[str]
    status: str  # passed | failed | skipped | missing
    reason: str
    http: tuple[dict[str, Any], ...]
    facts: Mapping[str, Any]


def _forbidden_status_seen(record: LegRecord) -> bool:
    """A 429/409/503/504 on a leg is a failure even if the leg reported itself passed: check
    the recorded HTTP entries and every integer '*Status' fact."""
    if any(entry["status"] in FORBIDDEN_LEG_STATUSES for entry in record.http):
        return True
    return any(
        key.endswith("Status") and is_strict_int(value) and value in FORBIDDEN_LEG_STATUSES
        for key, value in record.facts.items()
    )


def _strict_equal(left: Any, right: Any) -> bool:
    """Equality that never coerces: True is not 1, 1 is not 1.0, "200" is not 200."""
    return type(left) is type(right) and left == right


def _rule_holds(rule: Any, value: Any, facts: Mapping[str, Any]) -> bool:
    """Does one recorded fact satisfy one passFacts rule? Strict throughout (mirrors the
    TypeScript passRuleHolds); anything unrecognized fails."""
    if not isinstance(rule, dict):
        return _strict_equal(value, rule)
    if len(rule) != 1:
        return False
    ((form, argument),) = rule.items()
    if form == "not":  # same type as the literal, and different from it
        return type(value) is type(argument) and value != argument
    if form == "min":  # integers only; a bool, float or null never satisfies it
        return is_strict_int(value) and value >= argument
    if form == "type":  # {"type": "boolean"}: true or false, never null
        return isinstance(value, bool)
    if form == "equalsFact":
        return argument in facts and _strict_equal(value, facts[argument])
    return False


def pass_facts_violation(contract: LedgerContract, leg: str, facts: Mapping[str, Any]) -> Optional[str]:
    """None when a leg's recorded facts support its `passed` claim. Otherwise the orchestrator
    reason: FACTS_INCOMPLETE (a fact key of the leg is missing) or FACTS_CONTRADICT_PASS (a
    passFacts rule is violated). The browser child has no verdict authority: a bare
    `status: passed` is never believed on its own."""
    if any(key not in facts for key in contract.leg_facts[leg]):
        return "FACTS_INCOMPLETE"
    for key, rule in contract.pass_facts[leg].items():
        if not _rule_holds(rule, facts[key], facts):
            return "FACTS_CONTRADICT_PASS"
    return None


def pass_http_violation(
    contract: LedgerContract, leg: str, http: Sequence[Mapping[str, Any]], facts: Mapping[str, Any]
) -> Optional[str]:
    """None when a leg's recorded http entries support its `passed` claim and agree with its own
    count and status facts; otherwise HTTP_CONTRADICT_PASS. Without this a leg could say
    putStatus 200 while its own entry says the PUT returned 500, or say putCount 1 with no PUT (or
    two) recorded: the facts and the request evidence must tell the same story. Strict equality
    throughout (no coercion); a status rule with no entry to read fails closed."""
    rule = contract.pass_http[leg]
    entries = rule["entries"]
    if entries == "none":
        if http:
            return "HTTP_CONTRADICT_PASS"
    else:
        if not http or any(
            entry["method"] != entries["method"] or entry["path"] != entries["path"] for entry in http
        ):
            return "HTTP_CONTRADICT_PASS"
    count_fact = rule.get("countFact")
    if count_fact is not None and not _strict_equal(facts.get(count_fact), len(http)):
        return "HTTP_CONTRADICT_PASS"
    for fact, position in rule.get("statusFacts", {}).items():
        if not http:
            return "HTTP_CONTRADICT_PASS"
        entry = http[0] if position == "first" else http[-1]
        if not _strict_equal(facts.get(fact), entry["status"]):
            return "HTTP_CONTRADICT_PASS"
    if "firstStatus" in rule and (not http or not _strict_equal(http[0]["status"], rule["firstStatus"])):
        return "HTTP_CONTRADICT_PASS"
    if "allStatus" in rule and (
        not http or any(not _strict_equal(entry["status"], rule["allStatus"]) for entry in http)
    ):
        return "HTTP_CONTRADICT_PASS"
    return None


def pass_violation(contract: LedgerContract, record: LegRecord) -> Optional[str]:
    """The orchestrator reason that downgrades a `passed` record, or None. Facts are judged first
    (the http rules read them), then the http entries against the facts."""
    return pass_facts_violation(contract, record.leg, record.facts) or pass_http_violation(
        contract, record.leg, record.http, record.facts
    )


def derive_legs(
    parsed: Optional[LedgerParse],
    contract: LedgerContract,
    *,
    child_expected: bool,
    fallback_reason: str,
) -> dict[str, LegResult]:
    """One final result per leg. A leg that never reports is 'missing' (a failure) when the
    child was expected to run; otherwise it is 'skipped' with the fallback reason. A leg that
    reports `passed` is believed only if it carries every fact of its leg, satisfies every
    passFacts rule and its http entries satisfy its passHttp rule; failed and skipped legs are
    left as recorded."""
    results: dict[str, LegResult] = {}
    for leg in contract.legs:
        record = parsed.legs.get(leg) if parsed is not None else None
        route = LEG_ROUTES.get(leg)
        violation = (
            pass_violation(contract, record)
            if record is not None and not record.poisoned and record.status == "passed"
            else None
        )
        if record is None:
            status, reason = ("missing", "LEG_MISSING") if child_expected else ("skipped", fallback_reason)
            results[leg] = LegResult(leg, route, status, reason, (), {})
        elif record.poisoned:
            results[leg] = LegResult(leg, route, "failed", "LEDGER_INVALID", (), {})
        elif record.status == "passed" and _forbidden_status_seen(record):
            results[leg] = LegResult(
                leg, route, "failed", "FORBIDDEN_HTTP_STATUS", tuple(record.http), dict(record.facts)
            )
        elif violation is not None:
            results[leg] = LegResult(
                leg, route, "failed", violation, tuple(record.http), dict(record.facts)
            )
        else:
            results[leg] = LegResult(
                leg, route, record.status, record.reason, tuple(record.http), dict(record.facts)
            )
    return results


class Ledger:
    """Orchestrator-side ledger writer (write-ahead cleanup markers and child lifecycle)."""

    def __init__(
        self,
        fs: FileOps,
        path: Optional[Path],
        now_utc: Callable[[], datetime.datetime],
    ) -> None:
        self._fs = fs
        self._path = path
        self._now = now_utc
        self._seq = 0
        self.events: list[dict[str, Any]] = []
        self.write_failures = 0

    def event(self, name: str, *, critical: bool = False, **fields: Any) -> None:
        self._seq += 1
        record = {
            "seq": self._seq, "tUtc": utc_ms(self._now()), "src": "orchestrator",
            "event": name, **fields,
        }
        self.events.append(record)
        if self._path is None:
            return
        try:
            self._fs.append_line(self._path, json_compact(record))
        except OSError:
            self.write_failures += 1
            if critical:
                raise

    def cleanup(self, attempt: int, transport: str, result: str, *, critical: bool = False) -> None:
        if transport not in CLEANUP_TRANSPORTS or result not in CLEANUP_STEP_RESULTS:
            raise ValueError("cleanup event outside the closed vocabulary")
        self.event("cleanup", critical=critical, attempt=attempt, transport=transport, result=result)


# ---------------------------------------------------------------------------
# Artifact sanitizer: allowlist-built documents, then a fail-closed pattern scan
# ---------------------------------------------------------------------------
_TLDS = (
    "com|net|org|io|dev|app|ai|co|us|uk|de|in|info|biz|edu|gov|cloud|online|site|tech|xyz|"
    "azure|microsoft|local|internal|test|example|invalid|localhost"
)
_SCAN_PATTERNS: tuple[tuple[str, "re.Pattern[str]"], ...] = (
    ("jwt", re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}")),
    ("bearer", re.compile(r"\bbearer\b", re.IGNORECASE)),
    ("authorization", re.compile(r"authorization", re.IGNORECASE)),
    ("password", re.compile(r"passw(?:or)?d|\bpwd\b", re.IGNORECASE)),
    ("cookie", re.compile(r"cookie", re.IGNORECASE)),
    ("internal_key", re.compile(r"x-internal-api-key|internal_api_key", re.IGNORECASE)),
    ("basic", re.compile(r"\bbasic\s+[A-Za-z0-9+/=]{16,}", re.IGNORECASE)),
    ("ip_address", re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}\b")),
)
# The normalized copy (percent-decoded, JSON-unescaped, whitespace removed) is scanned only for
# credential-shaped classes: removing whitespace would glue neighbouring words into false hosts.
# "basic" is deliberately absent: its pattern needs whitespace between the scheme word and the
# credential, which the whitespace-free copy cannot contain, so an encoded or split Basic credential
# is detected in the raw text only (a known gap, pinned by a test).
_NORMALIZED_SCAN_CLASSES = frozenset(
    {"jwt", "bearer", "authorization", "password", "cookie", "internal_key"}
)
_WHITESPACE_RE = re.compile(r"\s+")
_JSON_ESCAPE_RE = re.compile(r'\\(?:u([0-9a-fA-F]{4})|(["\\/bfnrt]))')
_JSON_SIMPLE_ESCAPES = {
    '"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t",
}
# Email and URL detection is applied only at an `@` or `://` site, never retried at every text
# position: a whole-text regex over a long run of local-part or scheme characters is quadratic.
_ASCII_LETTERS = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
_SCHEME_CHARS = _ASCII_LETTERS | frozenset("0123456789+.-")
_EMAIL_LOCAL_CHARS = _ASCII_LETTERS | frozenset("0123456789._%+-")
_EMAIL_DOMAIN_AT_RE = re.compile(r"[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
_URL_HOST_AT_RE = re.compile(r"(?:[^/\s\"'\\?#@]*@)?([^/\s\"'\\?#:]+)")
_BARE_HOST_RE = re.compile(
    r"(?<![A-Za-z0-9_.\-])(?:[A-Za-z0-9\-]{1,63}\.)+(?:" + _TLDS + r")\b", re.IGNORECASE
)
_GUID_RE = re.compile(
    r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"
)
_STUB_TIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
SCAN_CLASS_ORDER = (  # append only: the scrubbed stub records 1-based indexes into this tuple
    "jwt", "bearer", "authorization", "password", "cookie", "internal_key",
    "ip_address", "email", "host", "guid", "secret_exact", "basic",
)
GATE_MAP_NOT_ALL_PASSED = {"step_a_credited": False, "run_result": "not_all_items_passed"}


def allowed_hosts() -> set[str]:
    return {(urllib.parse.urlsplit(origin).hostname or "").lower() for origin in ALLOWED_ORIGINS}


def _secret_forms(secret: str) -> set[str]:
    """Every form a secret is looked for in: raw, JSON-escaped, percent-encoded and base64. A form
    that itself contains whitespace also gets its whitespace-stripped twin, because the collapsed
    copy of the artifact has no whitespace: without it an owner secret such as a passphrase could
    never be found there in its double-percent-encoded or whitespace-split forms."""
    raw = secret.encode("utf-8")
    standard = base64.b64encode(raw).decode("ascii")
    url_safe = base64.urlsafe_b64encode(raw).decode("ascii")
    forms = {
        secret,
        json.dumps(secret)[1:-1],
        json.dumps(secret, ensure_ascii=False)[1:-1],
        urllib.parse.quote(secret, safe=""),
        urllib.parse.quote_plus(secret),
        standard, standard.rstrip("="), url_safe, url_safe.rstrip("="),
    }
    stripped = {_WHITESPACE_RE.sub("", form) for form in forms if _WHITESPACE_RE.search(form)}
    return forms | {form for form in stripped if len(form) >= MIN_SCAN_SECRET_LENGTH}


def fingerprint_hit(text: str, fingerprints: Iterable[Fingerprint]) -> bool:
    """True if any window of text matches a registered fingerprint. A window is hashed with
    SHA-256 only when its CRC-32 matches the fingerprint's, so a clean text costs one cheap
    prefilter call per window and registered length instead of a SHA-256 per window."""
    by_length: dict[int, dict[int, set[str]]] = {}
    for fingerprint in fingerprints:
        by_length.setdefault(fingerprint.length, {}).setdefault(fingerprint.crc32, set()).add(
            fingerprint.sha256
        )
    for length, by_crc in by_length.items():
        for start in range(len(text) - length + 1):
            window = text[start:start + length].encode("utf-8", "surrogatepass")
            candidates = by_crc.get(zlib.crc32(window))
            if candidates is not None and hashlib.sha256(window).hexdigest() in candidates:
                return True
    return False


def _json_unescape_char(match: "re.Match[str]") -> str:
    return chr(int(match.group(1), 16)) if match.group(1) else _JSON_SIMPLE_ESCAPES[match.group(2)]


def normalized_copy(text: str) -> str:
    """Percent-decoded (twice), JSON-unescaped, whitespace-free copy of text: an encoded or
    split credential collapses to the raw patterns and forms."""
    for _ in range(2):
        text = _JSON_ESCAPE_RE.sub(_json_unescape_char, urllib.parse.unquote(text))
    # pair up escaped surrogates (a JSON \uD83D\uDE00 pair); a lone surrogate becomes U+FFFD
    text = text.encode("utf-16-le", "surrogatepass").decode("utf-16-le", "replace")
    return _WHITESPACE_RE.sub("", text)


def _has_email(text: str) -> bool:
    """A local part, `@` and a dotted domain. Linear: the domain pattern is matched only at
    each `@`, and a local part exists iff the character before the `@` may be part of one."""
    at = text.find("@")
    while at != -1:
        if at > 0 and text[at - 1] in _EMAIL_LOCAL_CHARS and _EMAIL_DOMAIN_AT_RE.match(text, at + 1):
            return True
        at = text.find("@", at + 1)
    return False


def _url_hosts(text: str) -> list[str]:
    """The host of every `scheme://` site (userinfo skipped, port and path dropped). Sites are
    found with str.find and every one is examined, which is a superset of what a non-overlapping
    regex scan returns, so the check can only get stricter. A site needs a scheme: a run of
    scheme characters right before `://` that contains a letter."""
    hosts: list[str] = []
    site = text.find("://")
    while site != -1:
        start, has_letter = site, False
        while start > 0 and text[start - 1] in _SCHEME_CHARS:  # runs before distinct sites are disjoint
            start -= 1
            has_letter = has_letter or text[start] in _ASCII_LETTERS
        if has_letter:
            match = _URL_HOST_AT_RE.match(text, site + 3)
            if match is not None:
                hosts.append(match.group(1))
        site = text.find("://", site + 3)
    return hosts


def scan_text(
    text: str,
    *,
    secrets: Iterable[str] = (),
    owner_fingerprints: Iterable[Fingerprint] = (),
    hosts: Optional[Iterable[str]] = None,
) -> list[str]:
    """Names of the pattern classes found in text (never the offending text itself)."""
    permitted = {h.lower() for h in (allowed_hosts() if hosts is None else hosts)}
    fingerprints = tuple(owner_fingerprints)
    collapsed = normalized_copy(text)
    tripped: set[str] = set()
    for name, pattern in _SCAN_PATTERNS:
        if pattern.search(text) or (name in _NORMALIZED_SCAN_CLASSES and pattern.search(collapsed)):
            tripped.add(name)
    public_removed = text.replace(DEMO_EMAIL, "")
    if _has_email(public_removed):
        tripped.add("email")
    for host in _url_hosts(public_removed):
        if host.lower() not in permitted:
            tripped.add("host")
    for match in _BARE_HOST_RE.finditer(public_removed):
        if match.group(0).lower() not in permitted:
            tripped.add("host")
    for match in _GUID_RE.finditer(text):
        if match.group(0).lower() != DEMO_USER_ID:
            tripped.add("guid")
    for secret in secrets:
        if len(secret) >= MIN_SCAN_SECRET_LENGTH and any(
            form in text or form in collapsed for form in _secret_forms(secret)
        ):
            tripped.add("secret_exact")
    if fingerprint_hit(text, fingerprints) or (collapsed != text and fingerprint_hit(collapsed, fingerprints)):
        tripped.add("secret_exact")
    return sorted(tripped)


def _serialize(document: Mapping[str, Any]) -> bytes:
    """UTF-8 without BOM, '\\n' newlines, one trailing newline."""
    return (json.dumps(document, indent=2, ensure_ascii=True) + "\n").encode("utf-8")


def _minimal_stub(kind: str) -> dict[str, Any]:
    stop = {"code": "SANITIZER_TRIPPED"}
    if kind == "cleanup":
        return {"schema": CLEANUP_ARTIFACT_SCHEMA, "mode": "cleanup_only", "result": "UNCONFIRMED",
                "stop_reason": stop, "gate_map": dict(GATE_MAP_NOT_ALL_PASSED)}
    return {"schema": EVIDENCE_SCHEMA, "verdict": "NON_GO", "stop_reason": stop,
            "gate_map": dict(GATE_MAP_NOT_ALL_PASSED)}


def scrubbed_stub(document: Mapping[str, Any], tripped: Sequence[str], kind: str) -> dict[str, Any]:
    """A NON_GO stub carrying only enum-like values; no text from the tripped document."""
    stub = _minimal_stub(kind)
    # Class names are themselves scan words (for example "password"), so the stub carries
    # opaque 1-based codes into SCAN_CLASS_ORDER; the console shows the names.
    codes = sorted(SCAN_CLASS_ORDER.index(name) + 1 for name in tripped if name in SCAN_CLASS_ORDER)
    stub["stop_reason"] = {
        "code": "SANITIZER_TRIPPED",
        "text": "artifact pattern scan tripped; class codes: " + ", ".join(str(c) for c in codes),
        "failed_criteria": ["SANITIZER_CLEAN"],
    }
    for key in ("start_utc", "end_utc"):
        value = document.get(key)
        if isinstance(value, str) and _STUB_TIME_RE.match(value):
            stub[key] = value
    cleanup = document.get("cleanup")
    if isinstance(cleanup, Mapping):
        result = cleanup.get("result")
        confirmed = cleanup.get("golden_confirmed")
        stub["cleanup"] = {
            "result": result if result in CLEANUP_FINAL_RESULTS else "unknown",
            "golden_confirmed": confirmed if isinstance(confirmed, bool) else False,
        }
    return stub


def _unsealed_document(document: Mapping[str, Any]) -> dict[str, Any]:
    """The copy written when the requested path was taken before the write. Whatever the run
    computed, this copy was not sealed where the owner asked: it is never a GO."""
    previous = document.get("stop_reason")
    stop: dict[str, Any] = dict(previous) if isinstance(previous, Mapping) else {}
    criteria = [c for c in stop.get("failed_criteria", []) if isinstance(c, str)]
    if "ARTIFACT_PATH_UNAVAILABLE" not in criteria:
        criteria.append("ARTIFACT_PATH_UNAVAILABLE")
    stop.update(
        code="ARTIFACT_PATH_UNAVAILABLE", text=STOP_TEXT["ARTIFACT_PATH_UNAVAILABLE"],
        failed_criteria=criteria,
    )
    unsealed = dict(document)
    unsealed["verdict"] = "NON_GO"
    unsealed["stop_reason"] = stop
    unsealed["gate_map"] = dict(GATE_MAP_NOT_ALL_PASSED)
    return unsealed


@dataclasses.dataclass(frozen=True)
class WrittenArtifact:
    path: Path
    data: bytes
    tripped: tuple[str, ...]
    fallback_used: bool

    @property
    def size(self) -> int:
        return len(self.data)

    @property
    def sha256(self) -> str:
        return sha256_hex(self.data)


def finalize_artifact(
    document: Mapping[str, Any],
    *,
    kind: str,
    secrets: Iterable[str],
    fs: FileOps,
    path: Path,
    fallback_path: Optional[Path],
    owner_fingerprints: Iterable[Fingerprint] = (),
) -> WrittenArtifact:
    """Scan, substitute a scrubbed stub on a trip, and exclusive-create the file.

    A pattern trip replaces the document with a stub (which is scanned again, then with a
    constant minimal stub). The original is never written when it trips. On an exclusive-
    create collision an evidence document goes to the fallback path as an unsealed NON_GO
    copy (ARTIFACT_PATH_UNAVAILABLE, never a GO), and OSError propagates only if both fail."""
    secret_values = tuple(secrets)
    fingerprints = frozenset(owner_fingerprints)
    data = _serialize(document)
    tripped = scan_text(data.decode("utf-8"), secrets=secret_values, owner_fingerprints=fingerprints)
    if tripped:
        stub = scrubbed_stub(document, tripped, kind)
        data = _serialize(stub)
        if scan_text(data.decode("utf-8"), secrets=secret_values, owner_fingerprints=fingerprints):
            data = _serialize(_minimal_stub(kind))
    try:
        fs.write_exclusive(path, data)
        return WrittenArtifact(path, data, tuple(tripped), False)
    except OSError:
        if fallback_path is None:
            raise
        if kind == "evidence" and not tripped:  # a scrubbed stub is already a NON_GO
            data = _serialize(_unsealed_document(document))
        fs.write_exclusive(fallback_path, data)
        return WrittenArtifact(fallback_path, data, tuple(tripped), True)


# ---------------------------------------------------------------------------
# Real transports (tests inject fakes; nothing here runs at import time)
# ---------------------------------------------------------------------------
class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never follow a redirect: a 3xx surfaces as a non-200 status."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        return None


def _build_opener() -> urllib.request.OpenerDirector:
    """The one opener every real request uses. It connects directly: ProxyHandler({}) makes it
    ignore HTTP(S)_PROXY, ALL_PROXY and the operating-system proxy settings, so a proxy can
    never carry the password, the token or the internal key through a host the allowlist did
    not name (a network that needs a proxy makes the run fail closed). It never follows a
    redirect."""
    return urllib.request.build_opener(_NoRedirect, urllib.request.ProxyHandler({}))


def _assert_allowed_url(url: str) -> None:
    if not any(url.startswith(origin + "/") for origin in ALLOWED_ORIGINS):
        raise ValueError("URL outside the origin allowlist")


def _read_capped(response: Any) -> bytes:
    data = response.read(HTTP_BODY_MAX_BYTES + 1)
    if len(data) > HTTP_BODY_MAX_BYTES:
        raise ValueError("response exceeds the size cap")
    return data


def make_http(
    method: str, url: str, headers: dict[str, str], json_body: Any = None, timeout: float = 30.0
) -> tuple[int, Any]:
    _assert_allowed_url(url)
    data = json.dumps(json_body).encode("utf-8") if json_body is not None else None
    request_headers = dict(headers)
    if data is not None:
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    opener = _build_opener()
    try:
        with opener.open(request, timeout=timeout) as response:
            status, raw = response.status, _read_capped(response)
    except urllib.error.HTTPError as exc:
        status, raw = exc.code, _read_capped(exc)
    try:
        return status, (json.loads(raw) if raw else None)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return status, None


def make_fetch_root_html(url: str, timeout: float) -> tuple[int, str, bytes]:
    """Fresh (no-cache), redirect-free GET of the site root: (status, content-type, body)."""
    _assert_allowed_url(url)
    request = urllib.request.Request(
        url, method="GET",
        headers={"Cache-Control": "no-cache", "Pragma": "no-cache", "Accept": "text/html"},
    )
    opener = _build_opener()
    try:
        with opener.open(request, timeout=timeout) as response:
            return response.status, response.headers.get("Content-Type", ""), _read_capped(response)
    except urllib.error.HTTPError as exc:
        return exc.code, exc.headers.get("Content-Type", "") if exc.headers else "", _read_capped(exc)


def _env_path(env: Optional[Mapping[str, str]]) -> Optional[str]:
    if env is None:
        return None
    return next((v for k, v in env.items() if k.upper() == "PATH"), None)


def make_run_cmd(
    argv: list[str], cwd: Optional[Path], env: Optional[Mapping[str, str]], timeout: float
) -> CmdResult:
    resolved = shutil.which(argv[0], path=_env_path(env))
    if resolved is None:
        return CmdResult(127, "", "executable not found")
    try:
        completed = subprocess.run(
            [resolved, *argv[1:]], cwd=cwd, env=dict(env) if env is not None else None,
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=timeout, stdin=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired:
        return CmdResult(-1, "", "timed out")
    except OSError:
        return CmdResult(126, "", "could not start")
    return CmdResult(completed.returncode, completed.stdout or "", completed.stderr or "")


class _RealChild:
    def __init__(self, process: "subprocess.Popen[bytes]", log: Any) -> None:
        self._process = process
        self._log = log

    def poll(self) -> Optional[int]:
        return self._process.poll()

    def kill_tree(self) -> None:
        """Best effort: the child is the Node runner itself, which owns the Playwright workers
        and browsers (taskkill /T on Windows, the child's own session on POSIX)."""
        pid = self._process.pid
        try:
            if os.name == "nt":
                taskkill = shutil.which("taskkill")
                if taskkill:
                    subprocess.run([taskkill, "/PID", str(pid), "/T", "/F"], capture_output=True,
                                   stdin=subprocess.DEVNULL, timeout=30)
            else:
                os.killpg(os.getpgid(pid), signal.SIGKILL)
        except (OSError, subprocess.SubprocessError):
            pass
        with contextlib.suppress(OSError):
            self._process.kill()

    def close(self) -> None:
        with contextlib.suppress(OSError):
            self._log.close()


def _popen_platform_kwargs(windows: bool) -> dict[str, Any]:
    """A POSIX child leads its own session; a Windows child gets its own process group, so a
    console Ctrl+C reaches only the orchestrator, which owns the kill."""
    if windows:
        return {"creationflags": getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0x00000200)}
    return {"start_new_session": True}


def make_spawn_child(
    argv: list[str], env: Mapping[str, str], cwd: Path, log_path: Path
) -> ChildHandle:
    """Start the browser child with an explicit environment; stdout/stderr go to an
    owner-only log in the work directory (never the terminal, never the artifact)."""
    resolved = shutil.which(argv[0], path=_env_path(env))
    if resolved is None:
        raise FileNotFoundError(argv[0])
    flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND | getattr(os, "O_BINARY", 0)
    descriptor = os.open(log_path, flags, 0o600)
    try:
        log = os.fdopen(descriptor, "ab")
    except BaseException:
        os.close(descriptor)
        raise
    try:
        process = subprocess.Popen(
            [resolved, *argv[1:]], cwd=cwd, env=dict(env), stdin=subprocess.DEVNULL,
            stdout=log, stderr=subprocess.STDOUT, **_popen_platform_kwargs(os.name == "nt"),
        )
    except BaseException:
        log.close()
        raise
    return _RealChild(process, log)


@contextlib.contextmanager
def defer_sigint():  # type: ignore[no-untyped-def]
    """Ignore Ctrl+C while cleanup runs so a second interrupt cannot abort it."""
    previous = None
    try:
        previous = signal.signal(signal.SIGINT, signal.SIG_IGN)
    except (ValueError, OSError):  # not the main thread
        previous = None
    try:
        yield
    finally:
        if previous is not None:
            with contextlib.suppress(ValueError, OSError):
                signal.signal(signal.SIGINT, previous)


def _stdin_is_tty() -> bool:
    try:
        return bool(sys.stdin is not None and sys.stdin.isatty())
    except (ValueError, OSError, AttributeError):  # a closed or replaced stdin is not a console
        return False


def real_seams() -> Seams:
    return Seams(
        http_call=make_http, fetch_root_html=make_fetch_root_html, run_cmd=make_run_cmd,
        spawn_child=make_spawn_child, monotonic=time.monotonic,
        now_utc=lambda: datetime.datetime.now(datetime.timezone.utc), sleep=time.sleep,
        getpass_fn=getpass.getpass, input_fn=input, fs=FileOps(),
        out=lambda text: print(text, flush=True),
        err=lambda text: print(text, file=sys.stderr, flush=True),
        defer_sigint=defer_sigint, stdin_isatty=_stdin_is_tty,
    )


# ---------------------------------------------------------------------------
# API client (login, identity-checked reads, resets) over the injected http_call
# ---------------------------------------------------------------------------
@dataclasses.dataclass(frozen=True)
class LoginOutcome:
    state: str  # ok | failed | too_slow
    status: Optional[int]
    duration_seconds: float
    token: Optional[str]
    failure_code: Optional[str]


@dataclasses.dataclass(frozen=True)
class Observation:
    version: int
    golden: bool
    holdings_count: int


class ApiClient:
    def __init__(
        self,
        http_call: HttpCall,
        api_origin: str,
        operation_timeout: float,
        secrets: SecretRegistry,
    ) -> None:
        self._http = http_call
        self._api = api_origin
        self._timeout = operation_timeout
        self._secrets = secrets
        self._token: Optional[str] = None

    @property
    def has_token(self) -> bool:
        return self._token is not None

    def _auth(self) -> dict[str, str]:
        if self._token is None:
            raise RuntimeError("no session token")
        return {"Authorization": "Bearer " + self._token}

    def login(self, holder: PasswordHolder, monotonic: Callable[[], float]) -> LoginOutcome:
        """The single login. The password is released the moment the call returns."""
        started = monotonic()
        try:
            status, body = self._http(
                "POST", self._api + "/api/auth/login", {},
                {"email": DEMO_EMAIL, "password": holder.take()}, LOGIN_TIMEOUT_SECONDS,
            )
        except Exception:
            return LoginOutcome("failed", None, monotonic() - started, None, "LOGIN_TRANSPORT_ERROR")
        finally:
            holder.clear()
        duration = monotonic() - started
        if status != 200:
            return LoginOutcome("failed", status, duration, None, "LOGIN_STATUS_NOT_200")
        token = body.get("token") if isinstance(body, dict) else None
        if not isinstance(token, str) or not token:
            return LoginOutcome("failed", status, duration, None, "LOGIN_NO_TOKEN")
        self._secrets.add(token)  # registered before any use
        self._token = token
        state = "too_slow" if duration >= LOGIN_GUARD_SECONDS else "ok"
        return LoginOutcome(state, status, duration, token, None)

    def observe(self, golden: Golden) -> Observation:
        """Identity-checked GET /api/portfolio; ObserveError on anything unexpected."""
        try:
            status, body = self._http(
                "GET", self._api + "/api/portfolio", self._auth(), None, self._timeout
            )
        except Exception as exc:
            raise ObserveError("TRANSPORT_ERROR") from exc
        if status != 200:
            raise ObserveError("STATUS_NOT_200", status)
        portfolio = select_portfolio(body)
        return Observation(
            portfolio["version"],
            holdings_equal_golden(portfolio["holdings"], golden.as_wire()),
            len(portfolio["holdings"]),
        )

    def reset(self, version: int) -> tuple[int, Any]:
        return self._http(
            "PUT", self._api + "/api/portfolio/demo-reset", self._auth(),
            {"expectedVersion": version}, self._timeout,
        )

    def break_glass_reset(self, key: str, version: int) -> tuple[int, Any]:
        return self._http(
            "PUT", self._api + "/api/internal/portfolio/demo-reset", {"X-Internal-Api-Key": key},
            {"expectedVersion": version}, self._timeout,
        )

    def probe_prices(self, ticker: str) -> tuple[int, Any]:
        """One read-only warm-up probe: GET /api/market/prices for a single ticker with the saved
        JWT. Same injected http seam (so the real one is allowlisted, proxy-free and redirect-free);
        the timeout is the warm-up's own, not the operation timeout."""
        query = urllib.parse.urlencode({"tickers": ticker})
        return self._http(
            "GET", f"{self._api}/api/market/prices?{query}", self._auth(), None, WARMUP_TIMEOUT_SECONDS
        )


# ---------------------------------------------------------------------------
# P3b warm-up (read only): wake the price service before the browser child starts
# ---------------------------------------------------------------------------
@dataclasses.dataclass
class WarmupOutcome:
    """What is recorded of the warm-up: how many probes ran, the last HTTP status (None when the
    last probe failed at the transport or no probe ran) and the seconds spent. Never a body, a
    ticker or a token."""

    probes: int = 0
    last_status: Optional[int] = None
    seconds: float = 0.0
    done: bool = False  # the loop ran to its end (a success, the probe bound or the budget), not interrupted
    ok: bool = False


def run_warmup(client: ApiClient, golden: Golden, seams: Seams, outcome: WarmupOutcome) -> WarmupOutcome:
    """Up to WARMUP_MAX_PROBES probes of the first golden ticker, every one of them started within
    WARMUP_BUDGET_SECONDS of the first (measured on the injected monotonic clock), whichever limit
    comes first; stop at the first 200 whose body is a JSON array. A non-array 200, any other status
    and a transport failure each consume a probe and the loop continues, after a pause of
    min(WARMUP_RETRY_PAUSE_SECONDS, remaining budget) through `seams.sleep`, so a fast failure (an
    instant 503) cannot spend every probe in seconds without waking the price service. There is no
    pause when no further probe can follow (the probe bound or the budget is reached). The budget
    bounds when a probe may start; a probe keeps its own WARMUP_TIMEOUT_SECONDS, so the phase lasts
    at most WARMUP_BUDGET_SECONDS + WARMUP_TIMEOUT_SECONDS. `market-data-service` scales to zero and
    the page's controls only render once its price batches settle, so this is what keeps a healthy
    deployment from failing L1/L6 on a cold start. It is verifier traffic, never browser evidence.
    Mutates `outcome` as it goes so an interrupted warm-up still shows how far it got."""
    ticker = golden.holdings[0][0]
    started = seams.monotonic()
    try:
        while outcome.probes < WARMUP_MAX_PROBES:
            if seams.monotonic() - started >= WARMUP_BUDGET_SECONDS:
                break
            outcome.probes += 1
            try:
                status, body = client.probe_prices(ticker)
            except Exception:  # a timeout or a connection error: the probe is spent, try again
                outcome.last_status = None
            else:
                outcome.last_status = status
                if status == 200 and isinstance(body, list):
                    outcome.ok = True
                    break
            remaining = WARMUP_BUDGET_SECONDS - (seams.monotonic() - started)
            if outcome.probes < WARMUP_MAX_PROBES and remaining > 0:
                seams.sleep(min(WARMUP_RETRY_PAUSE_SECONDS, remaining))
        outcome.done = True
    finally:
        outcome.seconds = seams.monotonic() - started
    return outcome


# ---------------------------------------------------------------------------
# Independent cleanup (spec section 5): state-based, bounded, identity-checked
# ---------------------------------------------------------------------------
@dataclasses.dataclass
class CleanupOutcome:
    armed: bool = False
    result: str = "not_reached"
    attempts: int = 0
    transports: list[str] = dataclasses.field(default_factory=list)
    golden_confirmed: bool = False
    break_glass_used: bool = False
    detail_code: Optional[str] = None

    @property
    def transport_label(self) -> str:
        return "+".join(self.transports) if self.transports else "none"

    @property
    def ok(self) -> bool:
        return self.result in ("confirmed", "not_needed")


def run_independent_cleanup(
    client: ApiClient,
    golden: Golden,
    outcome: CleanupOutcome,
    ledger: Ledger,
    *,
    break_glass_key: Optional[str],
) -> CleanupOutcome:
    """Observe; reset only if not golden (409 => re-observe and retry, at most
    CLEANUP_MAX_ATTEMPTS); optional owner-only break-glass; final observe must equal golden.
    Mutates and returns `outcome` so a caller interrupted mid-way still has its progress."""
    outcome.armed = True
    outcome.transports.append("saved_jwt")

    def finish(result: str, code: Optional[str] = None) -> CleanupOutcome:
        outcome.result = result
        outcome.detail_code = code
        outcome.golden_confirmed = result in ("confirmed", "not_needed")
        ledger.cleanup(outcome.attempts, outcome.transports[-1], result)
        return outcome

    try:
        observation = client.observe(golden)
    except ObserveError as exc:
        ledger.cleanup(0, "saved_jwt", "observe_failed")
        return finish("unconfirmed", exc.code)
    if observation.golden:
        ledger.cleanup(0, "saved_jwt", "observed_golden")
        return finish("not_needed")
    ledger.cleanup(0, "saved_jwt", "observed_non_golden")

    while outcome.attempts < CLEANUP_MAX_ATTEMPTS:
        outcome.attempts += 1
        try:
            status, _ = client.reset(observation.version)
        except Exception:
            ledger.cleanup(outcome.attempts, "saved_jwt", "reset_error")
            break
        if status == 200:
            ledger.cleanup(outcome.attempts, "saved_jwt", "reset_200")
            break
        if status != 409:
            ledger.cleanup(outcome.attempts, "saved_jwt", "reset_other")
            break
        ledger.cleanup(outcome.attempts, "saved_jwt", "reset_409")
        if outcome.attempts >= CLEANUP_MAX_ATTEMPTS:
            break
        try:
            observation = client.observe(golden)
        except ObserveError:
            ledger.cleanup(outcome.attempts, "saved_jwt", "observe_failed")
            break
        if observation.golden:
            break

    try:
        observation = client.observe(golden)
    except ObserveError as exc:
        ledger.cleanup(outcome.attempts, "saved_jwt", "observe_failed")
        return finish("unconfirmed", exc.code)
    if observation.golden:
        return finish("confirmed")
    if not break_glass_key:
        return finish("unconfirmed", "STILL_NOT_GOLDEN")

    outcome.transports.append("internal_break_glass")
    outcome.break_glass_used = True
    try:
        status, _ = client.break_glass_reset(break_glass_key, observation.version)
        ledger.cleanup(1, "internal_break_glass", "break_glass_200" if status == 200 else "break_glass_other")
    except Exception:
        ledger.cleanup(1, "internal_break_glass", "break_glass_error")
    try:
        observation = client.observe(golden)
    except ObserveError as exc:
        ledger.cleanup(1, "internal_break_glass", "observe_failed")
        return finish("unconfirmed", exc.code)
    return finish("confirmed") if observation.golden else finish("unconfirmed", "STILL_NOT_GOLDEN")


# ---------------------------------------------------------------------------
# Frontend binding: observed served build (not a commit binding; the claim says so)
# ---------------------------------------------------------------------------
class BindingError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


_FLIGHT_PUSH_RE = re.compile(r'self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)')
_STATIC_DIR_ID_RE = re.compile(r"/_next/static/([A-Za-z0-9_-]{6,64})/")
_STATIC_RESERVED_DIRS = frozenset({"chunks", "css", "media", "webpack", "development"})
_ASSET_NAME_RE = re.compile(r"/_next/static/[A-Za-z0-9_\-./~%@+]+")
_NEXT_MARKERS = ("__next_f", "/_next/static/", "__NEXT_DATA__", 'id="__next"')


def _flight_root_build_ids(html: str) -> set[str]:
    """Build ids named by the flight payload's ROOT row: the top-level `b` key of the `0:{...}`
    object (last in current App Router output, first in older ones). A `b` key nested anywhere
    else in the payload is not a build id and is ignored."""
    chunks: list[str] = []
    for match in _FLIGHT_PUSH_RE.finditer(html):
        try:
            chunks.append(json.loads('"' + match.group(1) + '"', strict=False))
        except (ValueError, RecursionError):
            continue
    ids: set[str] = set()
    for row in "".join(chunks).split("\n"):
        if not row.startswith("0:{"):
            continue
        try:
            root = json.loads(row[2:], strict=False)
        except (ValueError, RecursionError):
            continue
        candidate = root.get("b") if isinstance(root, dict) else None
        if isinstance(candidate, str) and _BUILD_ID_RE.match(candidate):
            ids.add(candidate)
    return ids


def extract_build_id(html: str) -> str:
    """Next buildId: the `b` key of the flight payload's root row, cross-checked against the
    _next/static/<id>/ directory. Any conflict fails closed. When the root rows themselves name
    several ids, the unique static-directory id breaks the tie only if it is one of them."""
    flight = _flight_root_build_ids(html)
    static = {
        m.group(1) for m in _STATIC_DIR_ID_RE.finditer(html)
        if m.group(1) not in _STATIC_RESERVED_DIRS
    }
    if len(flight) > 1:
        if len(static) == 1 and static <= flight:
            return next(iter(static))
        raise BindingError("BUILD_ID_AMBIGUOUS")
    if flight:
        candidate = next(iter(flight))
        if static and static != {candidate}:
            raise BindingError("BUILD_ID_AMBIGUOUS")
        return candidate
    if len(static) > 1:
        raise BindingError("BUILD_ID_AMBIGUOUS")
    if static:
        return next(iter(static))
    raise BindingError("BUILD_ID_NOT_FOUND")


@dataclasses.dataclass(frozen=True)
class FrontendSnapshot:
    build_id: str
    html_sha256: str
    asset_names_sha256: str
    asset_name_count: int


def fetch_frontend_snapshot(fetch: FetchRoot, origin: str, timeout: float) -> FrontendSnapshot:
    """One fresh fetch of the site root. SWA answers 200 index.html for unknown paths, so
    a 200 proves nothing: require text/html, a Next marker and an identifiable build id."""
    try:
        status, content_type, body = fetch(origin + "/", timeout)
    except Exception as exc:
        raise BindingError("FETCH_FAILED") from exc
    if status != 200:
        raise BindingError("STATUS_NOT_200")
    if not str(content_type).strip().lower().startswith("text/html"):
        raise BindingError("NOT_HTML")
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BindingError("NOT_UTF8") from exc
    if not any(marker in text for marker in _NEXT_MARKERS):
        raise BindingError("NO_NEXT_MARKER")
    build_id = extract_build_id(text)
    names = sorted(set(_ASSET_NAME_RE.findall(text)))
    return FrontendSnapshot(
        build_id, sha256_hex(body), sha256_hex("\n".join(names).encode("utf-8")), len(names)
    )


@dataclasses.dataclass(frozen=True)
class FrontendPreBinding:
    build_id: str
    html_sha256: str
    asset_names_sha256: str
    asset_name_count: int
    stable_fetch_count: int
    differs_from_pre_deploy: bool


def collect_frontend_pre_binding(
    seams: Seams, origin: str, timeout: float, pre_deploy_build_id: str
) -> FrontendPreBinding:
    snapshots: list[FrontendSnapshot] = []
    for index in range(STABLE_FETCH_COUNT):
        if index:
            seams.sleep(STABLE_FETCH_MIN_SPACING_SECONDS)
        try:
            snapshots.append(fetch_frontend_snapshot(seams.fetch_root_html, origin, timeout))
        except BindingError as exc:
            raise PreconditionError("FRONTEND_" + exc.code, "frontend binding: " + exc.code) from exc
    first = snapshots[0]
    if any(
        s.build_id != first.build_id or s.asset_names_sha256 != first.asset_names_sha256
        for s in snapshots
    ):
        raise PreconditionError("FRONTEND_BUILD_UNSTABLE", "frontend build differs between fetches")
    if first.build_id == pre_deploy_build_id:
        raise PreconditionError(
            "FRONTEND_BUILD_ID_UNCHANGED", "served buildId equals the pre-deploy buildId"
        )
    return FrontendPreBinding(
        first.build_id, first.html_sha256, first.asset_names_sha256, first.asset_name_count,
        len(snapshots), True,
    )


def collect_frontend_post_binding(
    seams: Seams, origin: str, timeout: float, pre: FrontendPreBinding
) -> str:
    """'match' | 'mismatch' | 'fetch_failed' (one more fetch after the run)."""
    try:
        snapshot = fetch_frontend_snapshot(seams.fetch_root_html, origin, timeout)
    except BindingError:
        return "fetch_failed"
    same = (
        snapshot.build_id == pre.build_id and snapshot.asset_names_sha256 == pre.asset_names_sha256
    )
    return "match" if same else "mismatch"


# ---------------------------------------------------------------------------
# Backend attestation files (owner-produced; only the named fields are read)
# ---------------------------------------------------------------------------
class AttestationError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


@dataclasses.dataclass(frozen=True)
class Attestation:
    read_at: datetime.datetime
    services: Mapping[str, Mapping[str, str]]
    size_bytes: int
    sha256: str


def parse_attestation(data: bytes) -> Attestation:
    if len(data) > ATTESTATION_MAX_BYTES:
        raise AttestationError("TOO_LARGE")
    try:
        document = json.loads(data.decode("utf-8-sig"))
    except (ValueError, RecursionError) as exc:
        # UnicodeDecodeError, JSONDecodeError and the overlong-integer-literal error are all ValueErrors
        raise AttestationError("NOT_JSON") from exc
    if not isinstance(document, dict) or document.get("schema") != ATTESTATION_SCHEMA:
        raise AttestationError("SCHEMA_MISMATCH")
    try:
        read_at = parse_utc_z(document.get("readAtUtc"))  # type: ignore[arg-type]
    except ValueError as exc:
        raise AttestationError("TIME_INVALID") from exc
    raw_services = document.get("services")
    if not isinstance(raw_services, dict):
        raise AttestationError("SERVICES_INVALID")
    services: dict[str, dict[str, str]] = {}
    for name in ATTESTATION_SERVICES:
        entry = raw_services.get(name)
        if not isinstance(entry, dict):
            raise AttestationError("SERVICE_MISSING")
        revision, digest = entry.get("revision"), entry.get("digest")
        if not isinstance(revision, str) or not _REVISION_RE.match(revision):
            raise AttestationError("REVISION_INVALID")
        if not isinstance(digest, str) or not _DIGEST_RE.match(digest):
            raise AttestationError("DIGEST_INVALID")
        services[name] = {"revision": revision, "digest": digest}
    return Attestation(read_at, services, len(data), sha256_hex(data))


def read_attestation(fs: FileOps, path: Path) -> Attestation:
    try:
        data = fs.read_bytes(path, ATTESTATION_MAX_BYTES)
    except OSError as exc:
        raise AttestationError("UNREADABLE") from exc
    return parse_attestation(data)


def attestations_equal(pre: Attestation, post: Attestation) -> bool:
    return all(pre.services[name] == post.services[name] for name in ATTESTATION_SERVICES)


# ---------------------------------------------------------------------------
# Configuration and P0 preconditions (exit 2, nothing consumed)
# ---------------------------------------------------------------------------
@dataclasses.dataclass(frozen=True)
class RunConfig:
    evidence_output: Path
    baseline_commit: str = ""
    deploy_completed_utc: Optional[datetime.datetime] = None
    bound_seconds: Optional[int] = None
    pre_deploy_build_id: str = ""
    backend_attestation_pre: Optional[Path] = None
    deploy_run_id: str = ""
    source_head_sha: str = ""
    allow_active_presence: bool = False
    enable_internal_cleanup: bool = False
    cleanup_only: bool = False
    operation_timeout: float = DEFAULT_OPERATION_TIMEOUT_SECONDS
    action_timeout_ms: int = DEFAULT_ACTION_TIMEOUT_MS  # per-action timeout of the browser legs
    frontend_origin: Optional[str] = None  # None: the allowlisted frontend origin
    api_origin: Optional[str] = None  # None: the allowlisted API origin
    repo_root: Path = REPO
    frontend_dir: Optional[Path] = None  # None: <repo_root>/frontend
    oracle_path: Path = DEFAULT_ORACLE
    contract_path: Path = LEDGER_CONTRACT_PATH
    script_path: Path = Path(__file__).resolve()

    @property
    def work_dir(self) -> Path:
        return Path(str(self.evidence_output) + ".work")

    @property
    def frontend_path(self) -> Path:
        return self.frontend_dir if self.frontend_dir is not None else self.repo_root / "frontend"

    @property
    def playwright_config(self) -> Path:
        return self.frontend_path / PLAYWRIGHT_CONFIG_REL


@dataclasses.dataclass(frozen=True)
class PreconditionContext:
    contract: Optional[LedgerContract]  # None in --cleanup-only mode (no ledger is read)
    golden: Golden
    frontend_origin: str
    api_origin: str
    attestation_pre: Optional[Attestation]
    script_sha256: str


def resolve_targets(config: RunConfig) -> tuple[str, str]:
    """Exact origin allowlist: any other origin, scheme or port is a precondition error."""
    frontend = config.frontend_origin if config.frontend_origin is not None else ALLOWED_ORIGINS[0]
    api = config.api_origin if config.api_origin is not None else ALLOWED_ORIGINS[1]
    for value, expected in ((frontend, ALLOWED_ORIGINS[0]), (api, ALLOWED_ORIGINS[1])):
        if not isinstance(value, str) or not _ORIGIN_RE.match(value) or value != expected:
            raise PreconditionError("ORIGIN_NOT_ALLOWED", "target origin is not on the allowlist")
    return frontend, api


def _is_unc_or_device_path(text: str) -> bool:
    """\\\\server\\share, \\\\?\\C:\\..., \\\\.\\..., \\??\\... and their forward-slash forms."""
    normalized = text.replace("/", "\\")
    return normalized.startswith("\\\\") or normalized.startswith("\\??\\")


def validate_evidence_paths(config: RunConfig, fs: FileOps) -> None:
    path = config.evidence_output
    text = str(path)
    if not text or any(ord(ch) < 32 for ch in text) or ".." in path.parts or not path.name:
        raise PreconditionError("EVIDENCE_PATH_INVALID", "evidence output path is malformed")
    if _is_unc_or_device_path(text):  # the work directory would be written over the network
        raise PreconditionError(
            "EVIDENCE_PATH_UNC_OR_DEVICE", "evidence output must be a local drive path, not UNC or a device path"
        )
    if not (path.is_absolute() and os.path.isabs(text)):
        raise PreconditionError("EVIDENCE_PATH_NOT_ABSOLUTE", "evidence output must be absolute")
    if not fs.is_dir(path.parent):
        raise PreconditionError("EVIDENCE_PARENT_MISSING", "evidence output parent must exist")
    resolved = Path(os.path.realpath(path.parent)) / path.name
    if _is_within(resolved, Path(os.path.realpath(config.repo_root))):
        raise PreconditionError("EVIDENCE_PATH_INSIDE_REPO", "evidence output must be outside the repository")
    if fs.exists(path):
        raise PreconditionError("EVIDENCE_PATH_EXISTS", "evidence output already exists")
    if not config.cleanup_only and fs.exists(config.work_dir):
        raise PreconditionError("WORK_DIR_EXISTS", "the sibling .work directory already exists")


def validate_arguments(config: RunConfig) -> None:
    if not _HEX40_RE.match(config.baseline_commit or ""):
        raise PreconditionError("BASELINE_COMMIT_INVALID", "--baseline-commit must be 40 hex digits")
    timeout = config.operation_timeout
    if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not 1.0 <= timeout <= MAX_OPERATION_TIMEOUT_SECONDS:
        raise PreconditionError("OPERATION_TIMEOUT_INVALID", "--operation-timeout out of range")
    action_timeout = config.action_timeout_ms  # never echoed: a mistyped value must not reach a log
    if not is_strict_int(action_timeout) or not MIN_ACTION_TIMEOUT_MS <= action_timeout <= MAX_ACTION_TIMEOUT_MS:
        raise PreconditionError(
            "ACTION_TIMEOUT_INVALID",
            f"--action-timeout-ms must be an integer from {MIN_ACTION_TIMEOUT_MS} to {MAX_ACTION_TIMEOUT_MS}",
        )
    if config.cleanup_only:
        return
    required = {
        "--deploy-completed-utc": config.deploy_completed_utc,
        "--bound-seconds": config.bound_seconds,
        "--backend-attestation-pre": config.backend_attestation_pre,
    }
    for name, value in required.items():
        if value is None:
            raise PreconditionError("ARGUMENT_MISSING", f"{name} is required")
    if not is_strict_int(config.bound_seconds) or config.bound_seconds < MIN_BOUND_SECONDS:  # type: ignore[operator]
        raise PreconditionError("BOUND_SECONDS_INVALID", f"--bound-seconds must be at least {MIN_BOUND_SECONDS}")
    if not _BUILD_ID_RE.match(config.pre_deploy_build_id or ""):
        raise PreconditionError("PRE_DEPLOY_BUILD_ID_INVALID", "--pre-deploy-build-id is malformed")
    for name, value in (("--deploy-run-id", config.deploy_run_id), ("--source-head-sha", config.source_head_sha)):
        if not _IDENT_RE.match(value or ""):
            raise PreconditionError("OWNER_ATTESTED_ID_INVALID", f"{name} is malformed")
    # An identifier that would trip the artifact scan is refused now, before the login and the
    # mutation, instead of discarding a finished run at the final scan (which stays the backstop).
    for name, value in (
        ("--deploy-run-id", config.deploy_run_id), ("--source-head-sha", config.source_head_sha),
        ("--pre-deploy-build-id", config.pre_deploy_build_id),
    ):
        classes = scan_text(value)
        if classes:
            raise PreconditionError(
                "OWNER_ATTESTED_ID_SCAN_TRIPPED",
                f"{name} trips the artifact pattern scan ({', '.join(classes)}); refused before the login",
            )


def check_git(config: RunConfig, seams: Seams, environ: Mapping[str, str]) -> None:
    env = os_env(environ)
    head = seams.run_cmd(["git", "-C", str(config.repo_root), "rev-parse", "HEAD"], None, env, 30.0)
    if head.returncode != 0 or head.stdout.strip().lower() != config.baseline_commit.lower():
        raise PreconditionError("BASELINE_COMMIT_MISMATCH", "git HEAD does not equal --baseline-commit")
    status = seams.run_cmd(
        ["git", "-C", str(config.repo_root), "status", "--porcelain", "--untracked-files=no"],
        None, env, 60.0,
    )
    if status.returncode != 0:
        raise PreconditionError("GIT_STATUS_FAILED", "git status failed")
    if status.stdout.strip():
        raise PreconditionError("TREE_NOT_CLEAN", "tracked files are not clean")


def load_golden(config: RunConfig, seams: Seams, environ: Mapping[str, str]) -> Golden:
    result = seams.run_cmd(
        [sys.executable, "-B", str(config.oracle_path)], config.repo_root, os_env(environ),
        ORACLE_TIMEOUT_SECONDS,
    )
    if result.returncode != 0:
        raise PreconditionError("ORACLE_FAILED", f"oracle exited {result.returncode}")
    return parse_oracle_output(result.stdout)


def check_tooling(config: RunConfig, seams: Seams, environ: Mapping[str, str]) -> None:
    env = os_env(environ)
    checks = (  # node resolves on the scrubbed PATH; npm and npx are never used
        (["node", "--version"], "NODE_MISSING"),
        (["node", "-e", "require.resolve('@playwright/test')"], "PLAYWRIGHT_PACKAGE_MISSING"),
        # the Chromium build Playwright will launch (same scrubbed environment, so the same
        # PLAYWRIGHT_BROWSERS_PATH): its absence must be exit 2, not a NON_GO after the login
        (list(BROWSER_PROBE_ARGV), "BROWSER_EXECUTABLE_MISSING"),
    )
    for argv, code in checks:
        if seams.run_cmd(argv, config.frontend_path, env, TOOL_CHECK_TIMEOUT_SECONDS).returncode != 0:
            raise PreconditionError(code, f"{code}: required tool did not resolve")
    if not seams.fs.is_file(config.frontend_path / PLAYWRIGHT_CLI_REL):
        raise PreconditionError(
            "PLAYWRIGHT_CLI_MISSING", "the Playwright CLI entry (node_modules/@playwright/test/cli.js) is missing"
        )
    if not seams.fs.is_file(config.playwright_config):
        raise PreconditionError("PLAYWRIGHT_CONFIG_MISSING", "the dedicated Playwright config is missing")


def require_console(seams: Seams) -> None:
    """getpass echoes the password on a redirected stdin, so anything but a console is refused."""
    if not seams.stdin_isatty():
        raise PreconditionError(
            "STDIN_NOT_A_TTY", "the password prompt needs an interactive console (stdin is not a terminal)"
        )


def run_preconditions(
    config: RunConfig, seams: Seams, environ: Mapping[str, str], start_utc: datetime.datetime
) -> PreconditionContext:
    """P0 in order; the first failure raises PreconditionError (exit 2, nothing consumed)."""
    # First of all: a redirected stdin is refused before anything else runs (no root fetch, no git,
    # no oracle). _prompt_secrets keeps the same check as the single choke point before getpass.
    require_console(seams)
    frontend, api = resolve_targets(config)
    validate_evidence_paths(config, seams.fs)
    validate_arguments(config)
    contract = None if config.cleanup_only else load_ledger_contract(seams.fs, config.contract_path)
    check_git(config, seams, environ)
    golden = load_golden(config, seams, environ)
    attestation: Optional[Attestation] = None
    if not config.cleanup_only:
        check_tooling(config, seams, environ)
        deployed, attestation_path = config.deploy_completed_utc, config.backend_attestation_pre
        if deployed is None or attestation_path is None:
            raise PreconditionError("ARGUMENT_MISSING", "run-mode arguments are required")
        if not start_utc > deployed:
            raise PreconditionError("RUN_BEFORE_DEPLOY_COMPLETED", "run started before deploy completion")
        try:
            attestation = read_attestation(seams.fs, attestation_path)
        except AttestationError as exc:
            raise PreconditionError("ATTESTATION_PRE_INVALID", "pre attestation: " + exc.code) from exc
        if not (deployed < attestation.read_at < start_utc):
            raise PreconditionError(
                "ATTESTATION_PRE_TIME_ORDER",
                "pre attestation readAtUtc must be after deploy completion and before the run start",
            )
        for service in ATTESTATION_SERVICES:
            classes = scan_text(attestation.services[service]["revision"])
            if classes:
                raise PreconditionError(
                    "ATTESTATION_PRE_SCAN_TRIPPED",
                    f"pre attestation {service} revision trips the artifact pattern scan "
                    f"({', '.join(classes)}); refused before the login",
                )
    try:
        script_sha = sha256_hex(seams.fs.read_bytes(config.script_path, 4 * 1024 * 1024))
    except OSError as exc:
        raise PreconditionError("SCRIPT_UNREADABLE", "verifier script could not be hashed") from exc
    return PreconditionContext(contract, golden, frontend, api, attestation, script_sha)


# ---------------------------------------------------------------------------
# Browser child: explicit environment (Appendix B) and a deadline-bound runner
# ---------------------------------------------------------------------------
def build_child_env(
    environ: Mapping[str, str],
    *,
    frontend_origin: str,
    api_origin: str,
    token: str,
    work_dir: Path,
    golden_file: Path,
    baseline_version: int,
    allow_active_presence: bool,
    owner_secrets: Optional[SecretRegistry] = None,
    action_timeout_ms: int = DEFAULT_ACTION_TIMEOUT_MS,
) -> dict[str, str]:
    """OS variables plus the STEP_B_5B_* set. The password and the internal key are never
    part of it; DEBUG/PWDEBUG are absent by construction (not in the allowlist)."""
    env = os_env(environ)
    env.update(
        {
            "STEP_B_5B_FRONTEND_URL": frontend_origin,
            "STEP_B_5B_API_URL": api_origin,
            "STEP_B_5B_TOKEN": token,
            "STEP_B_5B_EMAIL": DEMO_EMAIL,
            "STEP_B_5B_USER_ID": DEMO_USER_ID,
            "STEP_B_5B_WORK_DIR": str(work_dir),
            "STEP_B_5B_GOLDEN_FILE": str(golden_file),
            "STEP_B_5B_BASELINE_VERSION": str(baseline_version),
            "STEP_B_5B_ALLOW_ACTIVE_PRESENCE": "1" if allow_active_presence else "0",
            "STEP_B_5B_ACTION_TIMEOUT_MS": str(action_timeout_ms),
        }
    )
    if owner_secrets is not None:
        for name, value in env.items():
            if name != "STEP_B_5B_TOKEN" and owner_secrets.contains_owner_secret(value):
                raise RuntimeError("an owner secret reached the child environment")
    return env


def build_child_argv() -> list[str]:
    """Static arguments only, relative to the frontend working directory: no secret and no
    absolute path is ever placed on the command line, and npm/npx are never involved."""
    return ["node", PLAYWRIGHT_CLI_REL, "test", "-c", PLAYWRIGHT_CONFIG_REL, *CHILD_STATIC_FLAGS]


@dataclasses.dataclass
class ChildOutcome:
    # not_run | exit_zero | exit_nonzero | deadline_killed | spawn_failed | interrupted |
    # kill_unconfirmed (a kill was issued but the child was not seen dead: a mutator may still run)
    state: str = "not_run"
    exit_code: Optional[int] = None
    ended_utc: Optional[datetime.datetime] = None


def _confirm_child_dead(handle: ChildHandle, seams: Seams) -> Optional[int]:
    """Poll (bounded by CHILD_KILL_GRACE_SECONDS) until the child is confirmed dead: its exit
    code, or None when it is still alive after the grace period."""
    deadline = seams.monotonic() + CHILD_KILL_GRACE_SECONDS
    while True:
        code = handle.poll()
        if code is not None:
            return code
        if seams.monotonic() >= deadline:
            return None
        seams.sleep(CHILD_POLL_SECONDS)


def run_browser_child(
    config: RunConfig,
    seams: Seams,
    ledger: Ledger,
    env: Mapping[str, str],
    deadline_monotonic: float,
    outcome: ChildOutcome,
    shield: Optional[contextlib.ExitStack] = None,
) -> None:
    """Start the child, poll until it exits or the overall deadline passes (then kill its
    tree). Any BaseException kills the child before propagating so it cannot mutate while
    cleanup runs. After every kill the child must be seen dead; otherwise the state is
    kill_unconfirmed, a hard NON_GO (a mutator may still be running).

    `shield` (an ExitStack the caller closes right after cleanup) receives the SIGINT deferral the
    moment the child phase is over (the child is dead or given up on, or it never started), before
    the child_done event is written: from then until cleanup ends a Ctrl+C is ignored, so no Python
    statement between the child's death and the cleanup runs unshielded. It is entered exactly once."""

    def hold_shield() -> None:
        if shield is not None:
            shield.enter_context(seams.defer_sigint())

    ledger.event("child_started", critical=True)
    try:
        handle = seams.spawn_child(build_child_argv(), env, config.frontend_path, config.work_dir / "child.log")
    except Exception:
        hold_shield()
        outcome.state, outcome.exit_code, outcome.ended_utc = "spawn_failed", -1, seams.now_utc()
        ledger.event("child_done", exit=-1)
        return
    exit_code: Optional[int] = None
    killed = False
    try:
        while True:
            exit_code = handle.poll()
            if exit_code is not None:
                break
            if seams.monotonic() >= deadline_monotonic:
                outcome.state = "kill_unconfirmed"  # until the child is proven dead
                killed = True
                handle.kill_tree()
                exit_code = _confirm_child_dead(handle, seams)
                break
            seams.sleep(CHILD_POLL_SECONDS)
    except BaseException:
        outcome.state = "kill_unconfirmed"  # until the child is proven dead
        handle.kill_tree()
        if _confirm_child_dead(handle, seams) is not None:
            outcome.state = "interrupted"
        raise
    finally:
        hold_shield()
        outcome.exit_code = exit_code if exit_code is not None else -1
        outcome.ended_utc = seams.now_utc()
        ledger.event("child_done", exit=outcome.exit_code)
        handle.close()
    if killed:
        outcome.state = "deadline_killed" if exit_code is not None else "kill_unconfirmed"
    else:
        outcome.state = "exit_zero" if outcome.exit_code == 0 else "exit_nonzero"


# ---------------------------------------------------------------------------
# Verdict (spec section 7): pure, derived from the ledger and the independent checks
# ---------------------------------------------------------------------------
STOP_TEXT = {
    "NONE": "all exit criterion 5b conditions held",
    "INTERRUPTED": "the run was interrupted",
    "UNEXPECTED_ERROR": "an unexpected error stopped the run",
    "CLEANUP_UNCONFIRMED": "cleanup could not confirm the golden state",
    "CLEANUP_NOT_RUN": "cleanup did not complete after the run was armed",
    "LOGIN_FAILED": "the demo login did not return a token",
    "LOGIN_NOT_REACHED": "the run stopped before the login completed",
    "LOGIN_TOO_SLOW": "the login took at least the guard duration; stopped before any mutation",
    "BASELINE_NOT_GOLDEN": "the post-login baseline is not the golden set; stopped before any mutation",
    "BASELINE_READ_FAILED": "the identity-checked baseline read failed",
    "BASELINE_NOT_REACHED": "the run stopped before the baseline was read",
    "WARMUP_FAILED": "the read-only price warm-up never got a 200 array response; stopped before any mutation",
    "WARMUP_NOT_REACHED": "the run stopped before the price warm-up finished",
    "CHILD_NOT_RUN": "the browser child did not run",
    "CHILD_EXIT_NONZERO": "the browser child exited non-zero",
    "CHILD_DEADLINE_KILLED": "the browser child was killed at the overall deadline",
    "CHILD_SPAWN_FAILED": "the browser child could not be started",
    "CHILD_INTERRUPTED": "the browser child was interrupted",
    "CHILD_KILL_UNCONFIRMED": "the browser child was not seen dead after the kill; a mutation may still run",
    "LEDGER_INVALID": "the ledger contains a line outside the contract",
    "LEDGER_WRITE_FAILED": "an orchestrator ledger write failed",
    "LEG_FAILED": "a browser leg did not pass",
    "FACTS_INCOMPLETE": "a leg reported passed without carrying every fact of its leg",
    "FACTS_CONTRADICT_PASS": "a leg reported passed but its recorded facts contradict the pass",
    "HTTP_CONTRADICT_PASS": "a leg reported passed but its recorded http entries contradict the pass or its own facts",
    "VISITOR_PRESENT": "another demo session was active; stopped before any mutation",
    "MUTATION_SKIPPED_UNEXPECTEDLY": "mutation legs were skipped without a permitted reason",
    "MUTATION_AFTER_FAILURE": "a mutation leg ran after an earlier leg failed",
    "PRESENCE_POLICY_VIOLATION": "a mutation ran while another session was active without the flag",
    "ARMED_MISSING": "no armed record precedes the mutation leg",
    "CONTROLS_NOT_RENDERED": "the two feature controls were not both rendered",
    "FRONTEND_POST_BINDING_FAILED": "the served frontend build changed or could not be re-read",
    "BACKEND_BINDING_FAILED": "the post attestation is missing, invalid or differs from the pre attestation",
    "BOUND_EXCEEDED": "the artifact was not complete within the owner's time bound",
    "DEADLINE_EXCEEDED": "the run exceeded the overall deadline",
    "ARTIFACT_PATH_UNAVAILABLE": "the requested evidence path was taken before the write; this copy is not sealed",
}
_CHILD_STATE_CODES = {
    "not_run": "CHILD_NOT_RUN", "exit_nonzero": "CHILD_EXIT_NONZERO",
    "deadline_killed": "CHILD_DEADLINE_KILLED", "spawn_failed": "CHILD_SPAWN_FAILED",
    "interrupted": "CHILD_INTERRUPTED", "kill_unconfirmed": "CHILD_KILL_UNCONFIRMED",
}


@dataclasses.dataclass(frozen=True)
class VerdictInputs:
    interrupted: Optional[str]
    unexpected_error: Optional[str]
    login_state: str  # ok | failed | too_slow | not_reached
    baseline_state: str  # golden | not_golden | read_failed | not_reached
    warmup_state: str  # ok | failed | not_reached (P3b, read only when the baseline was golden)
    child_state: str
    ledger_clean: bool
    ledger_write_failed: bool
    legs: Mapping[str, LegResult]
    spec_armed_before_l8: bool  # the spec's {"event":"armed"} line precedes L8 (not the cleanup marker)
    allow_active_presence: bool
    frontend_post_state: str  # match | mismatch | fetch_failed | not_collected
    backend_post_state: str  # valid_equal | mismatch | invalid | missing | skipped | pending
    elapsed_since_deploy_seconds: float
    bound_seconds: int
    # The JWT's age at the end of cleanup: from the start of the login call, so the owner's password
    # typing and the blocking post-attestation wait never count (--bound-seconds is the wall clock).
    session_seconds: float
    cleanup_armed: bool
    cleanup_result: str


@dataclasses.dataclass(frozen=True)
class Verdict:
    verdict: str  # GO | NON_GO | INCOMPLETE
    code: str
    failed_criteria: tuple[str, ...]

    @property
    def text(self) -> str:
        return STOP_TEXT.get(self.code, "stopped")

    @property
    def run_result(self) -> str:
        return "all_items_passed" if self.verdict == "GO" else "not_all_items_passed"


def controls_rendered(legs: Mapping[str, LegResult]) -> bool:
    leg = legs.get("L1")
    return bool(
        leg is not None and leg.status == "passed"
        and leg.facts.get("editButtonVisible") is True
        and leg.facts.get("resetButtonVisible") is True
    )


def compute_verdict(v: VerdictInputs, *, final: bool = True) -> Verdict:
    """GO only when every condition holds; INCOMPLETE only for the safe pre-mutation aborts
    (baseline not golden, visitor present, login too slow, warm-up failed) with nothing else wrong.
    final=False tolerates a still-pending backend post attestation (GO-candidate check)."""
    failures: list[tuple[str, str]] = []

    def hard(code: str) -> None:
        failures.append((code, "hard"))

    def soft(code: str) -> None:
        failures.append((code, "incomplete"))

    if v.child_state == "kill_unconfirmed":
        hard("CHILD_KILL_UNCONFIRMED")
    if v.interrupted:
        hard("INTERRUPTED")
    if v.unexpected_error:
        hard("UNEXPECTED_ERROR")
    if v.cleanup_armed:
        if v.cleanup_result == "unconfirmed":
            hard("CLEANUP_UNCONFIRMED")
        elif v.cleanup_result not in ("confirmed", "not_needed"):
            hard("CLEANUP_NOT_RUN")

    child_expected = False
    if v.login_state == "failed":
        hard("LOGIN_FAILED")
    elif v.login_state == "not_reached":
        hard("LOGIN_NOT_REACHED")
    elif v.login_state == "too_slow":
        soft("LOGIN_TOO_SLOW")
    elif v.baseline_state == "not_golden":
        soft("BASELINE_NOT_GOLDEN")
    elif v.baseline_state == "read_failed":
        hard("BASELINE_READ_FAILED")
    elif v.baseline_state == "not_reached":
        hard("BASELINE_NOT_REACHED")
    elif v.warmup_state == "failed":
        soft("WARMUP_FAILED")
    elif v.warmup_state != "ok":  # not_reached, or anything unrecognized: never assume the warm-up held
        hard("WARMUP_NOT_REACHED")
    else:
        child_expected = True

    if child_expected:
        if v.child_state != "exit_zero":
            hard(_CHILD_STATE_CODES.get(v.child_state, "CHILD_NOT_RUN"))
        if not v.ledger_clean:
            hard("LEDGER_INVALID")
        if v.ledger_write_failed:
            hard("LEDGER_WRITE_FAILED")
        l8, l9 = v.legs["L8"], v.legs["L9"]
        attempted = l8.status in ("passed", "failed") or l9.status in ("passed", "failed")
        reasons = {leg.reason for leg in v.legs.values()}
        if "FACTS_INCOMPLETE" in reasons:
            hard("FACTS_INCOMPLETE")
        if "FACTS_CONTRADICT_PASS" in reasons:
            hard("FACTS_CONTRADICT_PASS")
        if "HTTP_CONTRADICT_PASS" in reasons:
            hard("HTTP_CONTRADICT_PASS")
        if not all(v.legs[f"L{i}"].status == "passed" for i in range(8)):
            hard("LEG_FAILED")
            if attempted:
                hard("MUTATION_AFTER_FAILURE")
        elif l8.status == "skipped" and l9.status == "skipped":
            visitor = l8.reason == "SKIPPED_VISITOR_PRESENT" and l9.reason == "SKIPPED_VISITOR_PRESENT"
            if visitor and not v.allow_active_presence:
                soft("VISITOR_PRESENT")
            else:
                hard("MUTATION_SKIPPED_UNEXPECTEDLY")
        elif not (l8.status == "passed" and l9.status == "passed"):
            hard("LEG_FAILED")
        if attempted:
            if not v.allow_active_presence and v.legs["L5"].facts.get("anotherSessionActive") is not False:
                hard("PRESENCE_POLICY_VIOLATION")
            if not v.spec_armed_before_l8:
                hard("ARMED_MISSING")
        if v.legs["L1"].status == "passed" and not controls_rendered(v.legs):
            hard("CONTROLS_NOT_RENDERED")

    if v.frontend_post_state in ("mismatch", "fetch_failed"):
        hard("FRONTEND_POST_BINDING_FAILED")
    if v.backend_post_state in ("mismatch", "invalid", "missing"):
        hard("BACKEND_BINDING_FAILED")
    if v.elapsed_since_deploy_seconds > v.bound_seconds:
        hard("BOUND_EXCEEDED")
    if v.session_seconds > OVERALL_DEADLINE_SECONDS:
        hard("DEADLINE_EXCEEDED")
    if not failures:
        if v.frontend_post_state != "match":
            hard("FRONTEND_POST_BINDING_FAILED")
        if v.backend_post_state != "valid_equal" and (final or v.backend_post_state != "pending"):
            hard("BACKEND_BINDING_FAILED")

    if not failures:
        return Verdict("GO", "NONE", ())
    codes: list[str] = []
    for code, _ in failures:
        if code not in codes:
            codes.append(code)
    hard_codes = [code for code, kind in failures if kind == "hard"]
    return Verdict("NON_GO" if hard_codes else "INCOMPLETE", (hard_codes or codes)[0], tuple(codes))


# ---------------------------------------------------------------------------
# Run state and allowlist-built artifacts (spec section 6.1)
# ---------------------------------------------------------------------------
@dataclasses.dataclass
class RunState:
    config: RunConfig
    start_utc: datetime.datetime
    start_mono: float
    ctx: Optional[PreconditionContext] = None
    frontend_pre: Optional[FrontendPreBinding] = None
    login: Optional[LoginOutcome] = None
    baseline_state: str = "not_reached"
    baseline_holdings_count: Optional[int] = None
    child: ChildOutcome = dataclasses.field(default_factory=ChildOutcome)
    ledger_parse: Optional[LedgerParse] = None
    ledger_write_failures: int = 0
    cleanup: CleanupOutcome = dataclasses.field(default_factory=CleanupOutcome)
    frontend_post_state: str = "not_collected"
    attestation_post: Optional[Attestation] = None
    backend_post_state: str = "skipped"
    interrupted: Optional[str] = None
    unexpected_error: Optional[str] = None
    pending: Optional[BaseException] = None
    legs: dict[str, LegResult] = dataclasses.field(default_factory=dict)
    warmup: Optional[WarmupOutcome] = None
    # The JWT's age: monotonic time at the start of the login call and at the end of cleanup. The
    # overall deadline (child kill and DEADLINE_EXCEEDED) is measured on this span, never from the
    # run start, so the owner's typing and the blocking post-attestation wait do not count.
    login_start_mono: Optional[float] = None
    session_end_mono: Optional[float] = None
    # SIGINT deferral entered when the child phase ends; run_verifier closes it right after cleanup.
    shield: contextlib.ExitStack = dataclasses.field(default_factory=contextlib.ExitStack)

    @property
    def login_state(self) -> str:
        return self.login.state if self.login is not None else "not_reached"

    @property
    def warmup_state(self) -> str:
        if self.warmup is None or not self.warmup.done:
            return "not_reached"
        return "ok" if self.warmup.ok else "failed"

    @property
    def child_expected(self) -> bool:
        return (
            self.login_state == "ok" and self.baseline_state == "golden" and self.warmup_state == "ok"
        )

    @property
    def session_seconds(self) -> float:
        if self.login_start_mono is None or self.session_end_mono is None:
            return 0.0
        return self.session_end_mono - self.login_start_mono


def guarded(state: RunState, fn: Callable[[], Any]) -> Any:
    """Run fn; record (never propagate) any failure so later phases still run. A
    non-Exception BaseException (Ctrl+C, a test harness sentinel) is remembered and re-raised
    only after cleanup and the artifact are done."""
    try:
        return fn()
    except Exception as exc:  # the type name only: messages may carry request detail
        if state.unexpected_error is None:
            state.unexpected_error = type(exc).__name__
    except BaseException as exc:
        if state.interrupted is None:
            state.interrupted = type(exc).__name__
        if state.pending is None:
            state.pending = exc
    return None


def verdict_inputs(state: RunState, *, elapsed: float, backend_post_state: str) -> VerdictInputs:
    config = state.config
    parsed = state.ledger_parse
    return VerdictInputs(
        interrupted=state.interrupted,
        unexpected_error=state.unexpected_error,
        login_state=state.login_state,
        baseline_state=state.baseline_state,
        warmup_state=state.warmup_state,
        child_state=state.child.state,
        ledger_clean=parsed.clean if parsed is not None else False,
        ledger_write_failed=state.ledger_write_failures > 0,
        legs=state.legs,
        spec_armed_before_l8=spec_armed_before_l8(parsed),
        allow_active_presence=config.allow_active_presence,
        frontend_post_state=state.frontend_post_state,
        backend_post_state=backend_post_state,
        elapsed_since_deploy_seconds=elapsed,
        bound_seconds=config.bound_seconds if config.bound_seconds is not None else 0,
        session_seconds=state.session_seconds,
        cleanup_armed=state.cleanup.armed,
        cleanup_result=state.cleanup.result,
    )


def _safe_type_name(name: Optional[str]) -> Optional[str]:
    """Exception class names only (never messages), and only if they look like identifiers."""
    if name is None:
        return None
    return name if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,80}", name) else "unknown"


def _attestation_record(att: Optional[Attestation]) -> Optional[dict[str, Any]]:
    if att is None:
        return None
    return {
        "read_at_utc": utc_z(att.read_at),
        "services": {n: dict(att.services[n]) for n in ATTESTATION_SERVICES},
        "file_bytes": att.size_bytes,
        "file_sha256": att.sha256,
    }


def _cleanup_record(cleanup: CleanupOutcome) -> dict[str, Any]:
    return {
        "armed": cleanup.armed,
        "attempts": cleanup.attempts,
        "transport": cleanup.transport_label,
        "result": cleanup.result,
        "golden_confirmed": cleanup.golden_confirmed,
        "break_glass_used": cleanup.break_glass_used,
        "detail_code": cleanup.detail_code,
    }


def _warmup_record(warmup: Optional[WarmupOutcome]) -> Optional[dict[str, Any]]:
    """Allowlist-built: counts, one status and a duration. Never a body, a ticker or a token."""
    if warmup is None:
        return None
    return {
        "probes": warmup.probes,
        "last_status": warmup.last_status,
        "seconds": round(warmup.seconds, 3),
    }


def _login_record(login: Optional[LoginOutcome]) -> dict[str, Any]:
    if login is None:
        return {"state": "not_reached"}
    return {
        "state": login.state,
        "status": login.status,
        "duration_seconds": round(login.duration_seconds, 3),
        "within_timeout": login.duration_seconds <= LOGIN_TIMEOUT_SECONDS,
        "within_guard": login.duration_seconds < LOGIN_GUARD_SECONDS,
        "failure_code": login.failure_code,
    }


def _common_header(state: RunState, ctx: PreconditionContext, end_utc: datetime.datetime) -> dict[str, Any]:
    config = state.config
    return {
        "start_utc": utc_z(state.start_utc),
        "end_utc": utc_z(end_utc),
        "baseline_commit": config.baseline_commit.lower(),
        "tree_clean": True,
        "verifier_script_sha256": ctx.script_sha256,
        "oracle": {"catalogSha256": ctx.golden.catalog_sha256, "holding_count": len(ctx.golden.holdings)},
        "targets": {"frontend": ctx.frontend_origin, "api": ctx.api_origin},
    }


def build_evidence(
    state: RunState, verdict: Verdict, *, end_utc: datetime.datetime, elapsed: float,
    run_seconds: float,
) -> dict[str, Any]:
    """The wave10-5b-evidence-v1 document, built only from allowlisted values."""
    config, ctx = state.config, state.ctx
    if ctx is None or state.frontend_pre is None or ctx.attestation_pre is None:
        raise RuntimeError("evidence prerequisites missing")
    pre = state.frontend_pre
    document: dict[str, Any] = {"schema": EVIDENCE_SCHEMA, "verdict": verdict.verdict}
    document["stop_reason"] = {
        "code": verdict.code, "text": verdict.text, "failed_criteria": list(verdict.failed_criteria),
    }
    exception_types = [
        t for t in (_safe_type_name(state.unexpected_error), _safe_type_name(state.interrupted)) if t
    ]
    if exception_types:
        document["stop_reason"]["exception_types"] = exception_types
    document.update(_common_header(state, ctx, end_utc))
    document["deploy_completed_utc"] = (
        utc_z(config.deploy_completed_utc) if config.deploy_completed_utc is not None else None
    )
    document["deploy_completed_utc_source"] = "owner_attested"
    document["bound_seconds"] = config.bound_seconds
    document["elapsed_since_deploy_seconds"] = round(elapsed, 3)
    document["within_bound"] = config.bound_seconds is not None and elapsed <= config.bound_seconds
    document["run_seconds"] = round(run_seconds, 3)
    document["login"] = _login_record(state.login)
    document["baseline"] = {
        "state": state.baseline_state, "golden": state.baseline_state == "golden",
        "holdings_count": state.baseline_holdings_count,
    }
    document["warmup"] = _warmup_record(state.warmup)
    l5 = state.legs.get("L5")
    document["presence"] = {
        "allow_active_presence": config.allow_active_presence,
        "another_session_active": l5.facts.get("anotherSessionActive") if l5 is not None else None,
    }
    equal = (
        attestations_equal(ctx.attestation_pre, state.attestation_post)
        if state.attestation_post is not None else None
    )
    document["binding"] = {
        "frontend": {
            "build_id": pre.build_id, "differs_from_pre_deploy": pre.differs_from_pre_deploy,
            "stable_fetch_count": pre.stable_fetch_count, "root_html_sha256": pre.html_sha256,
            "asset_names_sha256": pre.asset_names_sha256, "asset_name_count": pre.asset_name_count,
            "post_state": state.frontend_post_state,
            "controls_rendered": controls_rendered(state.legs) if state.legs else False,
            "claim": FRONTEND_CLAIM,
        },
        "backend": {
            "pre": _attestation_record(ctx.attestation_pre),
            "post": _attestation_record(state.attestation_post),
            "post_state": state.backend_post_state,
            "pre_equals_post": equal,
            "claim": BACKEND_CLAIM,
        },
    }
    document["owner_attested"] = {
        "deploy_run_id": config.deploy_run_id, "source_head_sha": config.source_head_sha,
    }
    document["legs"] = [
        {
            "id": leg, "route": result.route, "evidence_level": LEG_EVIDENCE_LEVELS[leg],
            "status": result.status, "reason_code": result.reason,
            "http": [dict(entry) for entry in result.http], "facts": dict(result.facts),
        }
        for leg, result in state.legs.items()
    ]
    document["routes"] = {
        route: {
            "evidence_level": level, "leg": leg,
            "status": state.legs[leg].status if leg in state.legs else "missing",
            "ci_only_not_covered": ci_only,
        }
        for route, (leg, level, ci_only) in ROUTE_TABLE.items()
    }
    document["child"] = {
        "state": state.child.state, "exit_code": state.child.exit_code,
        "overall_deadline_seconds": OVERALL_DEADLINE_SECONDS,
    }
    document["cleanup"] = _cleanup_record(state.cleanup)
    document["gate_map"] = {"step_a_credited": False, "run_result": verdict.run_result}
    return document


def build_cleanup_artifact(
    state: RunState, *, end_utc: datetime.datetime, ok: bool, detail_code: Optional[str]
) -> dict[str, Any]:
    """The small non-gate wave10-5b-cleanup-v1 record: never a pass."""
    ctx = state.ctx
    if ctx is None:
        raise RuntimeError("cleanup artifact prerequisites missing")
    document: dict[str, Any] = {"schema": CLEANUP_ARTIFACT_SCHEMA, "mode": "cleanup_only"}
    document["result"] = "CONFIRMED_GOLDEN" if ok else "UNCONFIRMED"
    document["stop_reason"] = {
        "code": "NONE" if ok else (detail_code or "CLEANUP_UNCONFIRMED"),
        "text": "golden state confirmed" if ok else "cleanup could not confirm the golden state",
    }
    document.update(_common_header(state, ctx, end_utc))
    document["login"] = _login_record(state.login)
    document["cleanup"] = _cleanup_record(state.cleanup)
    document["gate_map"] = dict(GATE_MAP_NOT_ALL_PASSED)
    return document


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------
def _prompt_secrets(config: RunConfig, seams: Seams) -> tuple[str, Optional[str]]:
    """Masked prompts only: the password and the optional break-glass key never touch argv,
    the environment, the ledger or the artifact."""
    require_console(seams)
    password = seams.getpass_fn("Demo account password (input hidden): ")
    if not password:
        raise PreconditionError("PASSWORD_EMPTY", "no password was entered")
    key: Optional[str] = None
    if config.enable_internal_cleanup:
        entered = seams.getpass_fn(
            "Internal API key for owner-only break-glass cleanup (input hidden; blank disables): "
        )
        key = entered or None
    return password, key


def _prepare_work_dir(config: RunConfig, seams: Seams, golden: Golden) -> Path:
    golden_file = config.work_dir / "golden.json"
    try:
        seams.fs.mkdir_exclusive(config.work_dir)
        seams.fs.write_exclusive(golden_file, (json_compact(golden.as_file_document()) + "\n").encode("utf-8"))
    except OSError as exc:
        raise PreconditionError("WORK_DIR_UNAVAILABLE", "could not create the .work directory") from exc
    return golden_file


def _read_ledger(seams: Seams, path: Path, contract: LedgerContract) -> LedgerParse:
    try:
        return parse_ledger(seams.fs.read_bytes(path, LEDGER_MAX_BYTES), contract)
    except OSError:
        return LedgerParse({}, [(0, "LEDGER_UNREADABLE")], None, 0)


def _post_login_phase(
    state: RunState, seams: Seams, client: ApiClient, holder: PasswordHolder, ledger: Ledger,
    environ: Mapping[str, str], secrets: SecretRegistry, golden_file: Path,
) -> None:
    """P2 login, P3 baseline, P3b warm-up, then arm and run the browser child (P4)."""
    config, ctx = state.config, state.ctx
    if ctx is None:
        raise RuntimeError("preconditions missing")
    seams.out("P2 login (the only one; may take over a minute on a cold start)")
    state.login_start_mono = seams.monotonic()  # the JWT's age starts here (the overall deadline anchor)
    login = client.login(holder, seams.monotonic)
    state.login = login
    if login.state != "ok" or login.token is None:
        seams.out(f"P2 login stopped the run: {login.state}")
        return
    try:
        observation = client.observe(ctx.golden)
    except ObserveError:
        state.baseline_state = "read_failed"
        return
    state.baseline_holdings_count = observation.holdings_count
    if not observation.golden:
        state.baseline_state = "not_golden"
        seams.out("P3 baseline is not the golden set: stopping before any mutation (use --cleanup-only)")
        return
    state.baseline_state = "golden"
    seams.out("P3 baseline equals the golden set")
    seams.out("P3b warm-up (read-only price probes; verifier traffic, not browser evidence)")
    state.warmup = WarmupOutcome()
    run_warmup(client, ctx.golden, seams, state.warmup)
    if not state.warmup.ok:  # before the write-ahead marker: nothing mutated, nothing owed
        seams.out(
            f"P3b warm-up did not succeed after {state.warmup.probes} probe(s): "
            "stopping before any mutation"
        )
        return
    seams.out(f"P3b warm-up succeeded after {state.warmup.probes} probe(s)")
    ledger.cleanup(0, "none", "cleanup_armed", critical=True)  # write-ahead: cleanup is owed from here
    state.cleanup.armed = True
    env = build_child_env(
        environ, frontend_origin=ctx.frontend_origin, api_origin=ctx.api_origin, token=login.token,
        work_dir=config.work_dir, golden_file=golden_file, baseline_version=observation.version,
        allow_active_presence=config.allow_active_presence,
        owner_secrets=secrets, action_timeout_ms=config.action_timeout_ms,
    )
    seams.out("P4 browser child started (no output is streamed; see the work directory log)")
    run_browser_child(
        config, seams, ledger, env, state.login_start_mono + OVERALL_DEADLINE_SECONDS, state.child,
        state.shield,
    )
    seams.out(f"P4 browser child finished: {state.child.state}")


def _run_cleanup(
    state: RunState, seams: Seams, client: ApiClient, ledger: Ledger, key: Optional[str]
) -> None:
    """P5. Mutation is only possible once the run is armed; otherwise nothing to undo."""
    cleanup, ctx = state.cleanup, state.ctx
    if not cleanup.armed or ctx is None:
        cleanup.result = "skipped_no_mutation"
        return
    seams.out("P5 independent cleanup (state-based, saved session)")

    def go() -> None:
        with seams.defer_sigint():
            run_independent_cleanup(client, ctx.golden, cleanup, ledger, break_glass_key=key)

    guarded(state, go)
    if cleanup.result not in ("confirmed", "not_needed", "unconfirmed"):
        cleanup.result = "unconfirmed"
        cleanup.detail_code = cleanup.detail_code or "CLEANUP_INTERRUPTED"
        cleanup.golden_confirmed = False
    elif state.child.state == "kill_unconfirmed" and cleanup.ok:
        # A child that was not seen dead may still mutate after this read: never report it clean.
        cleanup.result, cleanup.golden_confirmed = "unconfirmed", False
        cleanup.detail_code = "CHILD_KILL_UNCONFIRMED"
        ledger.cleanup(cleanup.attempts, cleanup.transports[-1] if cleanup.transports else "none", "unconfirmed")
    seams.out(f"P5 cleanup result: {cleanup.result}")


def _collect_legs(state: RunState, seams: Seams, ledger: Ledger) -> None:
    ctx = state.ctx
    if ctx is None or ctx.contract is None:
        raise RuntimeError("ledger contract missing")
    state.ledger_write_failures = ledger.write_failures
    if state.cleanup.armed or state.child.state != "not_run":
        state.ledger_parse = _read_ledger(seams, state.config.work_dir / "ledger.jsonl", ctx.contract)
    fallback = (
        "SKIPPED_BASELINE_NOT_GOLDEN" if state.baseline_state == "not_golden" else "SKIPPED_PRIOR_FAILURE"
    )
    state.legs = derive_legs(
        state.ledger_parse if state.child_expected else None, ctx.contract,
        child_expected=state.child_expected, fallback_reason=fallback,
    )


def _ensure_legs(state: RunState) -> None:
    """Every verdict path needs a full leg map, even after an earlier internal failure."""
    ctx = state.ctx
    if state.legs or ctx is None or ctx.contract is None:
        return
    state.legs = derive_legs(
        None, ctx.contract, child_expected=state.child_expected, fallback_reason="SKIPPED_PRIOR_FAILURE"
    )


def _elapsed(state: RunState, seams: Seams) -> tuple[datetime.datetime, float, float]:
    """(now, seconds since deploy completion, wall-clock seconds since the run start). The first
    two feed --bound-seconds and the artifact; the third is informational (run_seconds): the
    overall deadline is the JWT's age (state.session_seconds), not this."""
    end_utc = seams.now_utc()
    deployed = state.config.deploy_completed_utc
    since_deploy = (end_utc - deployed).total_seconds() if deployed is not None else float("inf")
    return end_utc, since_deploy, seams.monotonic() - state.start_mono


def _await_post_attestation(state: RunState, seams: Seams) -> None:
    ctx = state.ctx
    ended = state.child.ended_utc
    if ctx is None or ctx.attestation_pre is None or ended is None:
        raise RuntimeError("post attestation prerequisites missing")
    path = state.config.work_dir / "backend-attestation-post.json"
    seams.out("POST-RUN BACKEND ATTESTATION NEEDED (owner action).")
    seams.out(f"  Save the owner-captured management-plane read of both services to: {path}")
    seams.out(f"  Its readAtUtc must be after {utc_z(ended)} (the browser child ended) and it must")
    seams.out("  match the pre-run attestation on every service revision and digest.")
    seams.input_fn("Press Enter when the file is saved: ")
    try:
        attestation = read_attestation(seams.fs, path)
    except AttestationError as exc:
        state.backend_post_state = "missing" if exc.code == "UNREADABLE" else "invalid"
        return
    if not ended < attestation.read_at <= seams.now_utc():
        state.backend_post_state = "invalid"
        return
    state.attestation_post = attestation
    state.backend_post_state = (
        "valid_equal" if attestations_equal(ctx.attestation_pre, attestation) else "mismatch"
    )


def _post_binding(state: RunState, seams: Seams) -> None:
    """P6: re-read the served build; ask the owner for the post attestation only when the
    run could still be GO (a failed run needs no management-plane read)."""
    ctx, pre, config = state.ctx, state.frontend_pre, state.config
    if ctx is None or pre is None:
        raise RuntimeError("preconditions missing")
    seams.out("P6 post binding")
    result = guarded(
        state,
        lambda: collect_frontend_post_binding(seams, ctx.frontend_origin, config.operation_timeout, pre),
    )
    state.frontend_post_state = result if isinstance(result, str) else "not_collected"
    _ensure_legs(state)
    _, since_deploy, _ = _elapsed(state, seams)
    provisional = compute_verdict(
        verdict_inputs(state, elapsed=since_deploy, backend_post_state="pending"), final=False,
    )
    if provisional.verdict != "GO":
        state.backend_post_state = "skipped"
        return
    state.backend_post_state = "pending"
    guarded(state, lambda: _await_post_attestation(state, seams))


def _write_artifact(
    state: RunState, seams: Seams, secrets: SecretRegistry, document: Mapping[str, Any],
    *, kind: str, success: bool, headline: str, failed_headline: str,
) -> int:
    config = state.config
    fallback = config.work_dir / "evidence.fallback.json" if (kind == "evidence" and seams.fs.is_dir(config.work_dir)) else None
    written: Optional[WrittenArtifact] = None
    try:
        written = finalize_artifact(
            document, kind=kind, secrets=secrets.values(), fs=seams.fs, path=config.evidence_output,
            fallback_path=fallback, owner_fingerprints=secrets.owner_fingerprints(),
        )
    except OSError:
        seams.err("ERROR: the artifact could not be written (fail-closed: not a pass)")
    code = 1
    if written is not None:
        if written.tripped:
            seams.err("SANITIZER TRIPPED (classes: " + ", ".join(written.tripped) + "): a scrubbed NON_GO stub was written")
        if written.fallback_used:
            seams.err("ERROR: the requested artifact path was unavailable; the copy is in the work directory")
        seams.out(f"Artifact written: {written.path}")
        seams.out(f"Artifact size: {written.size} bytes; SHA-256: {written.sha256}")
        if success and not written.tripped and not written.fallback_used:
            code = 0
    seams.out(headline if code == 0 or not success else failed_headline)
    if state.pending is not None:
        raise state.pending
    return code


def _finalize(state: RunState, seams: Seams, secrets: SecretRegistry) -> int:
    _ensure_legs(state)
    end_utc, since_deploy, run_seconds = _elapsed(state, seams)
    verdict = compute_verdict(
        verdict_inputs(state, elapsed=since_deploy, backend_post_state=state.backend_post_state)
    )
    document = build_evidence(state, verdict, end_utc=end_utc, elapsed=since_deploy, run_seconds=run_seconds)
    headline = f"Verdict: {verdict.verdict} ({verdict.code}: {verdict.text})"
    return _write_artifact(
        state, seams, secrets, document, kind="evidence", success=verdict.verdict == "GO",
        headline=headline, failed_headline="Verdict: NON_GO (the artifact could not be sealed as a GO)",
    )


def run_verifier(
    config: RunConfig,
    seams: Optional[Seams] = None,
    environ: Optional[Mapping[str, str]] = None,
) -> int:
    """The single entry point. Exit 2 before the login (no evidence); after the login every
    path writes an artifact and returns 0 (GO) or 1."""
    seams = seams if seams is not None else real_seams()
    env: Mapping[str, str] = environ if environ is not None else os.environ
    if config.cleanup_only:
        return run_cleanup_only(config, seams, env)
    start_utc, start_mono = seams.now_utc(), seams.monotonic()
    secrets = SecretRegistry()
    try:
        ctx = run_preconditions(config, seams, env, start_utc)
        seams.out("P0 preconditions: OK")
        frontend_pre = collect_frontend_pre_binding(
            seams, ctx.frontend_origin, config.operation_timeout, config.pre_deploy_build_id
        )
        seams.out(f"P1 frontend binding (pre): stable over {frontend_pre.stable_fetch_count} spaced fetches, differs from pre-deploy")
        password, break_glass_key = _prompt_secrets(config, seams)
        secrets.add_owner_secret(password)
        secrets.add_owner_secret(break_glass_key)
        golden_file = _prepare_work_dir(config, seams, ctx.golden)
    except PreconditionError as exc:
        seams.err(f"PRECONDITION FAILED [{exc.code}]: {exc.text}")
        return 2
    except KeyboardInterrupt:
        seams.err("Interrupted before the login; nothing was consumed.")
        return 2
    except EOFError:
        seams.err("Input was closed before the login; nothing was consumed.")
        return 2

    state = RunState(config, start_utc, start_mono, ctx=ctx, frontend_pre=frontend_pre)
    ledger = Ledger(seams.fs, config.work_dir / "ledger.jsonl", seams.now_utc)
    client = ApiClient(seams.http_call, ctx.api_origin, config.operation_timeout, secrets)
    holder = PasswordHolder(password)
    del password  # the holder (cleared at login) is the only reference; the registry keeps fingerprints
    # Ctrl+C is ignored from the moment the child phase ends until cleanup has finished, so a second
    # interrupt landing after the child died (or was killed) cannot skip the cleanup. The deferral
    # is entered by run_browser_child the instant the child is confirmed dead (state.shield), so
    # nothing runs unshielded between the child's death and here; it is entered again below for the
    # runs that never started a child. Every deferral sits on the one ExitStack (LIFO restore) and
    # the stack is closed in `finally` right after cleanup, so the owner's blocking post-attestation
    # wait stays interruptible and the original handler always comes back.
    try:
        guarded(state, lambda: _post_login_phase(state, seams, client, holder, ledger, env, secrets, golden_file))
        state.shield.enter_context(seams.defer_sigint())
        holder.clear()
        guarded(state, lambda: _run_cleanup(state, seams, client, ledger, break_glass_key))
        state.session_end_mono = seams.monotonic()  # the JWT's age ends with cleanup
    finally:
        state.shield.close()
    break_glass_key = None
    guarded(state, lambda: _collect_legs(state, seams, ledger))
    guarded(state, lambda: _post_binding(state, seams))
    return _finalize(state, seams, secrets)


def run_cleanup_only(config: RunConfig, seams: Seams, environ: Mapping[str, str]) -> int:
    """Reconcile a crashed run: login, observe, reset if needed, confirm golden. Writes a
    small non-gate artifact and is never a pass. 0 confirmed, 1 unconfirmed, 2 precondition."""
    start_utc, start_mono = seams.now_utc(), seams.monotonic()
    secrets = SecretRegistry()
    try:
        ctx = run_preconditions(config, seams, environ, start_utc)
        seams.out("P0 preconditions: OK (cleanup-only)")
        password, break_glass_key = _prompt_secrets(config, seams)
        secrets.add_owner_secret(password)
        secrets.add_owner_secret(break_glass_key)
    except PreconditionError as exc:
        seams.err(f"PRECONDITION FAILED [{exc.code}]: {exc.text}")
        return 2
    except KeyboardInterrupt:
        seams.err("Interrupted before the login; nothing was consumed.")
        return 2
    except EOFError:
        seams.err("Input was closed before the login; nothing was consumed.")
        return 2
    state = RunState(config, start_utc, start_mono, ctx=ctx)
    ledger = Ledger(seams.fs, None, seams.now_utc)
    client = ApiClient(seams.http_call, ctx.api_origin, config.operation_timeout, secrets)
    holder = PasswordHolder(password)
    del password

    def go() -> None:
        seams.out("Login (the only one)")
        login = client.login(holder, seams.monotonic)
        state.login = login
        if login.state == "failed":
            state.cleanup.detail_code = login.failure_code
            return
        with seams.defer_sigint():
            run_independent_cleanup(client, ctx.golden, state.cleanup, ledger, break_glass_key=break_glass_key)

    guarded(state, go)
    holder.clear()
    break_glass_key = None
    cleanup = state.cleanup
    if cleanup.armed and cleanup.result not in ("confirmed", "not_needed", "unconfirmed"):
        cleanup.result, cleanup.golden_confirmed = "unconfirmed", False
        cleanup.detail_code = cleanup.detail_code or "CLEANUP_INTERRUPTED"
    end_utc = seams.now_utc()
    document = build_cleanup_artifact(state, end_utc=end_utc, ok=cleanup.ok, detail_code=cleanup.detail_code)
    headline = "Cleanup: golden state confirmed (never a pass)" if cleanup.ok else "Cleanup: NOT confirmed"
    return _write_artifact(
        state, seams, secrets, document, kind="cleanup", success=cleanup.ok, headline=headline,
        failed_headline="Cleanup: NOT confirmed (the artifact could not be sealed)",
    )


# ---------------------------------------------------------------------------
# Command line
# ---------------------------------------------------------------------------
class _Parser(argparse.ArgumentParser):
    def error(self, message: str) -> NoReturn:
        # Never echo argument values (a mistyped secret must not reach a log).
        raise PreconditionError("ARGUMENT_ERROR", "invalid or unrecognized command-line arguments")


def build_parser() -> argparse.ArgumentParser:
    parser = _Parser(description=__doc__, allow_abbrev=False,
                     formatter_class=argparse.RawDescriptionHelpFormatter)
    add = parser.add_argument
    add("--evidence-output", required=True, help="absolute path outside the repository; must not exist")
    add("--baseline-commit", required=True, help="40-hex commit equal to git HEAD (tracked files clean)")
    add("--deploy-completed-utc", help="owner-attested deploy completion, e.g. 2026-01-01T00:00:00Z")
    add("--bound-seconds", help="time bound from deploy completion to artifact completion (>= 300)")
    add("--pre-deploy-build-id", help="Next buildId captured before the deploy")
    add("--backend-attestation-pre", help="owner-captured pre-run attestation JSON")
    add("--deploy-run-id", help="owner-attested deploy run id (recorded verbatim)")
    add("--source-head-sha", help="owner-attested source head SHA (recorded verbatim)")
    add("--allow-active-presence", action="store_true", help="mutate even if another demo session is active")
    add("--enable-internal-cleanup", action="store_true", help="prompt for the internal key (break-glass only)")
    add("--cleanup-only", action="store_true", help="reconcile a crashed run; never a pass")
    add("--operation-timeout", default=str(DEFAULT_OPERATION_TIMEOUT_SECONDS), help="seconds per API call")
    add("--action-timeout-ms", default=str(DEFAULT_ACTION_TIMEOUT_MS),
        help=f"per-action timeout of the browser legs in milliseconds ({MIN_ACTION_TIMEOUT_MS}-{MAX_ACTION_TIMEOUT_MS})")
    return parser


def build_config(args: argparse.Namespace) -> RunConfig:
    deployed: Optional[datetime.datetime] = None
    if args.deploy_completed_utc is not None:
        try:
            deployed = parse_utc_z(args.deploy_completed_utc)
        except ValueError as exc:
            raise PreconditionError("DEPLOY_COMPLETED_INVALID", "--deploy-completed-utc must be UTC ending in Z") from exc
    bound: Optional[int] = None
    if args.bound_seconds is not None:
        if not re.fullmatch(r"[0-9]{1,9}", args.bound_seconds):
            raise PreconditionError("BOUND_SECONDS_INVALID", "--bound-seconds must be an integer")
        bound = int(args.bound_seconds)
    try:
        timeout = float(args.operation_timeout)
    except ValueError as exc:
        raise PreconditionError("OPERATION_TIMEOUT_INVALID", "--operation-timeout must be a number") from exc
    if not re.fullmatch(r"[0-9]{1,9}", args.action_timeout_ms):  # the value is never echoed
        raise PreconditionError("ACTION_TIMEOUT_INVALID", "--action-timeout-ms must be an integer")
    return RunConfig(
        evidence_output=Path(args.evidence_output),
        baseline_commit=args.baseline_commit,
        deploy_completed_utc=deployed,
        bound_seconds=bound,
        pre_deploy_build_id=args.pre_deploy_build_id or "",
        backend_attestation_pre=Path(args.backend_attestation_pre) if args.backend_attestation_pre else None,
        deploy_run_id=args.deploy_run_id or "",
        source_head_sha=args.source_head_sha or "",
        allow_active_presence=args.allow_active_presence,
        enable_internal_cleanup=args.enable_internal_cleanup,
        cleanup_only=args.cleanup_only,
        operation_timeout=timeout,
        action_timeout_ms=int(args.action_timeout_ms),
    )


def main(
    argv: Optional[list[str]] = None,
    *,
    seams: Optional[Seams] = None,
    environ: Optional[Mapping[str, str]] = None,
) -> int:
    seams = seams if seams is not None else real_seams()
    try:
        config = build_config(build_parser().parse_args(argv))
    except PreconditionError as exc:
        seams.err(f"PRECONDITION FAILED [{exc.code}]: {exc.text}")
        return 2
    try:
        return run_verifier(config, seams, environ)
    except KeyboardInterrupt:
        seams.err("Interrupted after the login: cleanup and the artifact were completed first.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
