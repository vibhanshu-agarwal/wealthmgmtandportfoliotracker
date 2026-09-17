#!/usr/bin/env python3
"""Wave 10.2 Go-action Step A verifier.

Phase 3-4 bounded sequence:
  Phase 3: authenticate, non-golden composition write, bounded demo-reset (<=3 attempts)
  Phase 4: validate golden state, post-reset read, cleanup (max 1 attempt, literal)

Credential env var: WAVE9_STEP_A_PASSWORD (owner-injected via launcher; never logged
or recorded by this script).

Exit codes:
  0  GO — all assertions passed, evidence written
  1  NON_GO or STOP — sequence stopped; evidence written with outcome/stop_reason
  2  Argument or precondition error (no evidence written)
"""

from __future__ import annotations

import argparse
import datetime
import importlib.util
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable, Optional

# ---------------------------------------------------------------------------
# LITERAL CONSTANTS — NOT CLI flags or defaults
# ---------------------------------------------------------------------------
CLEANUP_MAX_ATTEMPTS: int = 1          # script literal per attempt sheet; not a CLI flag
LOGIN_TIMEOUT_SECONDS: float = 225.0   # must exceed overall_timeout (165s) per attempt sheet
OVERALL_TIMEOUT_SECONDS: float = 165.0 # Azure-attested; login round-trip must not reach this
PHASE3_MAX_READS: int = 4
RESET_MAX_ATTEMPTS: int = 3

# ---------------------------------------------------------------------------
# Identity constants
# ---------------------------------------------------------------------------
STEP_A_PASSWORD_VAR: str = "WAVE9_STEP_A_PASSWORD"
DEMO_USER_ID: str = "00000000-0000-0000-0000-0000000d3110"
DEMO_EMAIL: str = "demo@wealthtracker.dev"
DEFAULT_GATEWAY_URL: str = "https://api.vibhanshu-ai-portfolio.dev"

REPO = Path(__file__).resolve().parents[1]
DEFAULT_ORACLE = REPO / "scripts" / "derive_demo_golden_state.py"


class StopError(Exception):
    """Raised to stop the sequence at any STOP/GO gate.

    evidence and secrets are attached when re-raised from run_step_a's outer
    handler, so main() can write a complete sanitised evidence document on every
    exit path — including intermediate failures.
    """

    def __init__(
        self,
        verdict: str,
        reason: str,
        evidence: dict[str, Any] | None = None,
        secrets: list[str] | None = None,
    ) -> None:
        super().__init__(reason)
        self.verdict = verdict   # "STOP" | "NON_GO"
        self.reason = reason
        self.evidence: dict[str, Any] = evidence if evidence is not None else {}
        self.secrets: list[str] = secrets if secrets is not None else []


@dataclass
class StepAConfig:
    gateway_url: str = DEFAULT_GATEWAY_URL
    oracle_path: Path = field(default_factory=lambda: DEFAULT_ORACLE)
    evidence_output: Path = field(default_factory=lambda: Path("wave9-step-a-raw.json"))
    demo_email: str = DEMO_EMAIL
    demo_password: str = ""
    operation_timeout_seconds: float = 30.0
    baseline_commit: str = ""   # supplied at Stage 2 via --baseline-commit; stored in evidence


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def _auth(token: str) -> dict[str, str]:
    return {"Authorization": "Bearer " + token}


def _make_http(
    method: str,
    url: str,
    headers: dict[str, str],
    json_body: Any = None,
    timeout: float = 30.0,
) -> tuple[int, Any]:
    data = json.dumps(json_body).encode() if json_body is not None else None
    req_headers = {**headers}
    if data:
        req_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            try:
                return resp.status, json.loads(raw) if raw else None
            except json.JSONDecodeError:
                return resp.status, None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return e.code, None


# ---------------------------------------------------------------------------
# Oracle
# ---------------------------------------------------------------------------

def _load_golden_from_oracle(oracle_path: Path) -> tuple[list[dict[str, str]], str]:
    """Invoke the oracle subprocess and return (wireHoldings, catalogSha256).

    The oracle is always run via subprocess so the catalogue path resolution is
    isolated from the current working directory.
    """
    import subprocess
    result = subprocess.run(
        [sys.executable, "-B", str(oracle_path)],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode != 0:
        raise StopError(
            "STOP",
            f"oracle exited {result.returncode}: {result.stderr.strip()[:200]}",
        )
    try:
        doc = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise StopError("STOP", f"oracle output is not valid JSON: {exc}") from exc
    holdings = doc.get("wireHoldings")
    catalog_sha256 = doc.get("metadata", {}).get("catalogSha256", "")
    if not isinstance(holdings, list) or not holdings:
        raise StopError("STOP", "oracle returned no wireHoldings")
    normalized = []
    for row in holdings:
        if not isinstance(row, dict) or "assetTicker" not in row or "quantity" not in row:
            raise StopError("STOP", f"oracle returned malformed holding: {row!r}")
        normalized.append({"assetTicker": str(row["assetTicker"]), "quantity": str(row["quantity"])})
    return normalized, str(catalog_sha256)


# ---------------------------------------------------------------------------
# Portfolio helpers
# ---------------------------------------------------------------------------

def _select_portfolio(payload: Any) -> dict[str, Any]:
    """Identity-checked select: exactly one match on DEMO_USER_ID required."""
    rows: list[Any] = payload if isinstance(payload, list) else ([payload] if isinstance(payload, dict) else [])
    matches = [r for r in rows if isinstance(r, dict) and r.get("userId") == DEMO_USER_ID]
    if len(matches) != 1:
        raise StopError(
            "NON_GO",
            f"identity-checked read found {len(matches)} match(es) for DEMO_USER_ID",
        )
    portfolio = matches[0]
    version = portfolio.get("version")
    if not isinstance(version, int) or isinstance(version, bool) or version < 0:
        raise StopError(
            "STOP",
            f"portfolio version is absent, non-integer, or negative: {version!r}",
        )
    if not isinstance(portfolio.get("holdings"), list):
        raise StopError("STOP", "portfolio has malformed holdings field")
    return portfolio


def _wire_holdings_sorted(holdings: list[dict[str, Any]]) -> list[dict[str, str]]:
    normalized: list[dict[str, str]] = []
    seen: set[str] = set()
    for row in holdings:
        ticker = row.get("assetTicker", row.get("ticker"))
        quantity = row.get("quantity")
        if not isinstance(ticker, str) or not ticker or ticker in seen:
            raise ValueError(f"malformed or duplicate holding: {row!r}")
        if not isinstance(quantity, str):
            raise ValueError(f"quantity is not a string in holding: {row!r}")
        seen.add(ticker)
        normalized.append({"assetTicker": ticker, "quantity": quantity})
    return sorted(normalized, key=lambda r: r["assetTicker"])


def _is_golden(portfolio: dict[str, Any], golden: list[dict[str, str]]) -> bool:
    try:
        return _wire_holdings_sorted(portfolio.get("holdings", [])) == _wire_holdings_sorted(golden)
    except ValueError:
        return False


def _non_golden_composition(version: int, golden: list[dict[str, str]]) -> dict[str, Any]:
    """Non-golden write: all oracle-derived holdings with the first quantity incremented by 1."""
    holdings = []
    for i, row in enumerate(golden):
        qty = row["quantity"]
        if i == 0:
            qty = format(Decimal(qty) + Decimal("1.00000000"), ".8f")
        # PUT /api/portfolio/holdings uses "ticker" (not "assetTicker") in the request body
        holdings.append({"ticker": row["assetTicker"], "quantity": qty})
    return {"expectedVersion": version, "holdings": holdings}


# ---------------------------------------------------------------------------
# Evidence sanitization
# ---------------------------------------------------------------------------

def _redact_evidence(evidence_json: str, secrets: list[str]) -> str:
    for secret in secrets:
        if secret:
            evidence_json = evidence_json.replace(secret, "[REDACTED]")
    return evidence_json


# ---------------------------------------------------------------------------
# Core sequence
# ---------------------------------------------------------------------------

def run_step_a(
    config: StepAConfig,
    *,
    http_call: Callable[..., tuple[int, Any]] = _make_http,
    load_golden: Callable[[Path], tuple[list[dict[str, str]], str]] = _load_golden_from_oracle,
    monotonic: Callable[[], float] = time.monotonic,
) -> tuple[dict[str, Any], list[str]]:
    """Execute the Phase 3-4 Step A sequence.

    Returns (evidence_dict, secrets_list) on GO. Raises StopError on any gate
    failure; the exception carries evidence and secrets so main() can write a
    complete sanitised document on every exit path.

    Structure:
      [pre-ARM]  load oracle → login → baseline read
      [ARM]      evidence["cleanup"]["armed"] = True
      [deferred] write → resets → phase-4 validations (StopError → _deferred)
      [cleanup]  always runs after ARM regardless of _deferred; a cleanup failure
                 that is the sole failure becomes the stop reason
      [exit]     raises StopError(evidence, secrets) if any stop; returns GO
    """
    secrets: list[str] = [config.demo_password]

    evidence: dict[str, Any] = {
        "schema": "wave9-step-a-v1",
        "outcome": "STOP",
        "stop_reason": "sequence did not complete",
        "baseline_commit": config.baseline_commit,
        "demo_email_public": config.demo_email,
        "gateway_url": config.gateway_url,
        "operations": [],
        "version_progression": {},
        "cleanup": {"armed": False, "result": "not_reached"},
        "golden_assertions": {},
        "gate_map": {
            "wave10_2_go_action": "step_a",
            "condition_5_status": "open_owner_question",
        },
    }

    def _utc() -> str:
        return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    def _append_op(op: dict[str, Any]) -> None:
        op["seq"] = len(evidence["operations"]) + 1
        evidence["operations"].append(op)

    try:
        # === Pre-ARM: oracle, login, baseline read ===

        golden, catalog_sha256 = load_golden(config.oracle_path)
        evidence["golden_assertions"]["catalog_sha256"] = catalog_sha256
        evidence["golden_assertions"]["golden_holdings_count"] = len(golden)

        login_start = monotonic()
        status, body = http_call(
            "POST",
            config.gateway_url + "/api/auth/login",
            {},
            {"email": config.demo_email, "password": config.demo_password},
            LOGIN_TIMEOUT_SECONDS,
        )
        login_duration = monotonic() - login_start
        _append_op({
            "phase": "phase3", "class": "login",
            "utc": _utc(),
            "status": status,
            "login_round_trip_seconds": round(login_duration, 3),
            "timeout_seconds": LOGIN_TIMEOUT_SECONDS,
            "request_body_keys": ["email"],
        })

        if status != 200:
            raise StopError("STOP", f"login returned HTTP {status}; expected 200")

        token = body.get("token") if isinstance(body, dict) else None
        if not isinstance(token, str) or not token:
            raise StopError("STOP", "login response did not contain a token field")

        # JWT is a secret — add before any subsequent raise so it is always redacted
        secrets.append(token)

        if login_duration >= OVERALL_TIMEOUT_SECONDS:
            raise StopError(
                "STOP",
                f"login round-trip {login_duration:.1f}s >= {OVERALL_TIMEOUT_SECONDS}s overall deadline; "
                "portfolio may be in-flight; not proceeding to write",
            )

        r1_status, r1_body = http_call(
            "GET",
            config.gateway_url + "/api/portfolio",
            _auth(token),
            None,
            config.operation_timeout_seconds,
        )
        _append_op({"phase": "phase3", "class": "portfolio_read", "read_seq": 1, "utc": _utc(), "status": r1_status})
        if r1_status != 200:
            raise StopError("STOP", f"post-login portfolio read returned HTTP {r1_status}")
        portfolio_r1 = _select_portfolio(r1_body)
        v_baseline = portfolio_r1["version"]
        evidence["version_progression"]["post_login_baseline"] = v_baseline

        # === ARM: cleanup runs unconditionally from this point forward ===
        evidence["cleanup"]["armed"] = True

        # --- post-ARM deferred section ---
        phase3_read_count = 1  # Read 1 already consumed above

        def _read_phase3(label: str) -> dict[str, Any]:
            nonlocal phase3_read_count
            if phase3_read_count >= PHASE3_MAX_READS:
                raise StopError(
                    "NON_GO",
                    f"Phase 3 read cap ({PHASE3_MAX_READS}) reached before completing {label}",
                )
            s, b = http_call(
                "GET",
                config.gateway_url + "/api/portfolio",
                _auth(token),
                None,
                config.operation_timeout_seconds,
            )
            phase3_read_count += 1
            _append_op({
                "phase": "phase3", "class": "portfolio_read",
                "read_seq": phase3_read_count, "label": label,
                "utc": _utc(), "status": s,
            })
            if s != 200:
                raise StopError("NON_GO", f"Phase 3 portfolio read ({label}) returned HTTP {s}")
            return _select_portfolio(b)

        _deferred: Optional[StopError] = None
        try:
            write_body = _non_golden_composition(v_baseline, golden)
            w_status, w_resp_body = http_call(
                "PUT",
                config.gateway_url + "/api/portfolio/holdings",
                _auth(token),
                write_body,
                config.operation_timeout_seconds,
            )
            _append_op({
                "phase": "phase3", "class": "composition_write",
                "utc": _utc(), "status": w_status,
                "expected_version": v_baseline,
                "holdings_count": len(write_body["holdings"]),
            })
            if w_status != 200:
                raise StopError("NON_GO", f"non-golden composition write returned HTTP {w_status}; expected 200")

            portfolio_pre = _read_phase3("pre_reset")
            v_pre_reset = portfolio_pre["version"]
            evidence["version_progression"]["pre_reset"] = v_pre_reset
            if v_pre_reset <= v_baseline:
                raise StopError(
                    "NON_GO",
                    f"composition write did not advance version: baseline={v_baseline}, pre_reset={v_pre_reset}",
                )

            reset_attempts = 0
            reset_status: Optional[int] = None
            reset_resp_body: Any = None

            while reset_attempts < RESET_MAX_ATTEMPTS:
                reset_attempts += 1
                r_status, r_body = http_call(
                    "PUT",
                    config.gateway_url + "/api/portfolio/demo-reset",
                    _auth(token),
                    {"expectedVersion": v_pre_reset},
                    config.operation_timeout_seconds,
                )
                _append_op({
                    "phase": "phase3", "class": "demo_reset",
                    "attempt": reset_attempts, "utc": _utc(),
                    "status": r_status,
                    "expected_version": v_pre_reset,
                })
                reset_status = r_status
                reset_resp_body = r_body

                if r_status == 200:
                    break
                elif r_status == 409:
                    if reset_attempts >= RESET_MAX_ATTEMPTS:
                        raise StopError(
                            "NON_GO",
                            f"reset did not produce genuine HTTP 200 in {RESET_MAX_ATTEMPTS} attempts",
                        )
                    portfolio_pre = _read_phase3(f"re_observe_attempt_{reset_attempts + 1}")
                    v_pre_reset = portfolio_pre["version"]
                    evidence["version_progression"][f"pre_reset_retry_{reset_attempts + 1}"] = v_pre_reset
                else:
                    raise StopError(
                        "NON_GO",
                        f"reset returned unexpected HTTP {r_status}; expected 200 or 409",
                    )

            if reset_status != 200:
                raise StopError(
                    "NON_GO",
                    f"reset never returned genuine HTTP 200 (last status: {reset_status})",
                )

            expected_post_reset_version = v_pre_reset + 1
            if isinstance(reset_resp_body, dict):
                resp_version = reset_resp_body.get("version")
                if resp_version != expected_post_reset_version:
                    raise StopError(
                        "NON_GO",
                        f"reset response version {resp_version!r} != expected {expected_post_reset_version}",
                    )
                if not _is_golden(reset_resp_body, golden):
                    raise StopError("NON_GO", "reset response holdings do not match Task 4.4a golden state")
                resp_holdings_count = len(reset_resp_body.get("holdings", []))
            else:
                raise StopError("NON_GO", "reset response body is missing or not JSON")

            evidence["version_progression"]["post_reset_response"] = resp_version
            evidence["golden_assertions"]["reset_response_version_correct"] = True
            evidence["golden_assertions"]["reset_response_holdings_match_golden"] = True
            evidence["golden_assertions"]["reset_response_holdings_count"] = resp_holdings_count

            pr_status, pr_body = http_call(
                "GET",
                config.gateway_url + "/api/portfolio",
                _auth(token),
                None,
                config.operation_timeout_seconds,
            )
            _append_op({"phase": "phase4", "class": "post_reset_read", "utc": _utc(), "status": pr_status})
            if pr_status != 200:
                raise StopError("NON_GO", f"post-reset read returned HTTP {pr_status}")
            portfolio_post_reset = _select_portfolio(pr_body)
            evidence["version_progression"]["post_reset_read"] = portfolio_post_reset["version"]
            if not _is_golden(portfolio_post_reset, golden):
                raise StopError("NON_GO", "post-reset read: holdings do not match golden state")
            evidence["golden_assertions"]["post_reset_read_holdings_match"] = True

        except StopError as exc:
            _deferred = exc
        except Exception as exc:
            _deferred = StopError("STOP", f"unexpected error in post-ARM section: {type(exc).__name__}: {exc}")

        # === Cleanup: unconditional after ARM ===
        # Runs whether the deferred section succeeded or failed.
        # A cleanup failure becomes the stop reason only when there is no prior stop.
        try:
            cl_obs_status, cl_obs_body = http_call(
                "GET",
                config.gateway_url + "/api/portfolio",
                _auth(token),
                None,
                config.operation_timeout_seconds,
            )
            _append_op({
                "phase": "phase4", "class": "cleanup_observation_read",
                "utc": _utc(), "status": cl_obs_status,
            })
            if cl_obs_status != 200:
                raise StopError("NON_GO", f"cleanup observation read returned HTTP {cl_obs_status}")
            portfolio_cleanup_obs = _select_portfolio(cl_obs_body)
            v_cleanup = portfolio_cleanup_obs["version"]
            evidence["version_progression"]["pre_cleanup"] = v_cleanup

            cl_reset_count = 0
            cl_reset_status: Optional[int] = None

            while cl_reset_count < CLEANUP_MAX_ATTEMPTS:
                cl_reset_count += 1
                cl_status, _cl_body = http_call(
                    "PUT",
                    config.gateway_url + "/api/portfolio/demo-reset",
                    _auth(token),
                    {"expectedVersion": v_cleanup},
                    config.operation_timeout_seconds,
                )
                _append_op({
                    "phase": "phase4", "class": "cleanup_reset",
                    "attempt": cl_reset_count, "utc": _utc(),
                    "status": cl_status,
                    "expected_version": v_cleanup,
                })
                cl_reset_status = cl_status
                if cl_status == 200:
                    break
                elif cl_status == 409:
                    raise StopError(
                        "NON_GO",
                        "cleanup reset returned 409; portfolio left non-golden; "
                        "see recovery path (Wave 8 login-reset on next idle demo login or manual reset)",
                    )
                else:
                    raise StopError(
                        "NON_GO",
                        f"cleanup reset returned HTTP {cl_status}; portfolio state uncertain",
                    )

            if cl_reset_status != 200:
                raise StopError("NON_GO", "cleanup did not return HTTP 200")

            evidence["cleanup"]["result"] = "200"

            pcl_status, pcl_body = http_call(
                "GET",
                config.gateway_url + "/api/portfolio",
                _auth(token),
                None,
                config.operation_timeout_seconds,
            )
            _append_op({"phase": "phase4", "class": "post_cleanup_read", "utc": _utc(), "status": pcl_status})
            if pcl_status != 200:
                raise StopError("NON_GO", f"post-cleanup read returned HTTP {pcl_status}")
            portfolio_post_cleanup = _select_portfolio(pcl_body)
            evidence["version_progression"]["post_cleanup_read"] = portfolio_post_cleanup["version"]
            if not _is_golden(portfolio_post_cleanup, golden):
                raise StopError("NON_GO", "post-cleanup read: portfolio not in golden state")
            evidence["golden_assertions"]["post_cleanup_holdings_match"] = True

        except StopError as cl_exc:
            evidence["cleanup"]["result"] = f"error: {cl_exc.reason}"
            if _deferred is None:
                _deferred = cl_exc  # cleanup failure becomes the primary stop
        except Exception as cl_exc:
            msg = f"unexpected error in cleanup: {type(cl_exc).__name__}: {cl_exc}"
            evidence["cleanup"]["result"] = f"error: {msg}"
            if _deferred is None:
                _deferred = StopError("STOP", msg)

        if _deferred is not None:
            raise _deferred  # caught by the outer except; evidence is attached there

    except StopError as exc:
        # Attach the current (fully-populated) evidence and secrets to every StopError
        # that leaves this function, so main() can write a complete document.
        evidence["outcome"] = exc.verdict
        evidence["stop_reason"] = exc.reason
        raise StopError(exc.verdict, exc.reason, evidence, secrets) from exc
    except Exception as exc:
        evidence["outcome"] = "STOP"
        evidence["stop_reason"] = f"unexpected error: {type(exc).__name__}: {exc}"
        raise StopError("STOP", evidence["stop_reason"], evidence, secrets) from exc

    evidence["outcome"] = "GO"
    evidence["stop_reason"] = None
    return evidence, secrets


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--gateway-url",
        default=DEFAULT_GATEWAY_URL,
        help="Base URL for the API gateway",
    )
    parser.add_argument(
        "--oracle",
        type=Path,
        default=DEFAULT_ORACLE,
        help="Path to derive_demo_golden_state.py",
    )
    parser.add_argument(
        "--evidence-output",
        type=Path,
        required=True,
        help="Where to write the sanitized evidence JSON (must not already exist)",
    )
    parser.add_argument(
        "--baseline-commit",
        required=True,
        help="Git commit of the attempt's baseline (e.g. main@abc123...) for evidence traceability",
    )
    parser.add_argument(
        "--operation-timeout",
        type=float,
        default=30.0,
        help="Timeout in seconds for non-login HTTP operations",
    )
    args = parser.parse_args(argv)

    evidence_path: Path = args.evidence_output

    # Pre-flight: parent directory must already exist (never create directories)
    if not evidence_path.parent.is_dir():
        print(
            f"ERROR: parent directory of evidence output path does not exist: {evidence_path.parent}\n"
            "Create the directory first (e.g. docs/evidence/b2-wave-9/) before running.",
            file=sys.stderr,
        )
        return 2

    if evidence_path.exists():
        print(
            f"ERROR: evidence output path already exists: {evidence_path}\n"
            "Evidence is never overwritten; pass a new path.",
            file=sys.stderr,
        )
        return 2

    demo_password = os.environ.get(STEP_A_PASSWORD_VAR, "")
    if not demo_password:
        print(
            f"ERROR: env var {STEP_A_PASSWORD_VAR} is not set or empty.\n"
            "The Step A credential must be injected by the launcher via ProcessStartInfo.",
            file=sys.stderr,
        )
        return 2

    config = StepAConfig(
        gateway_url=args.gateway_url,
        oracle_path=args.oracle,
        evidence_output=evidence_path,
        demo_password=demo_password,
        operation_timeout_seconds=args.operation_timeout,
        baseline_commit=args.baseline_commit,
    )

    exit_code = 1
    evidence: dict[str, Any] = {}
    secrets: list[str] = [demo_password]

    try:
        evidence, secrets = run_step_a(config)
        exit_code = 0
    except StopError as exc:
        # exc.evidence is always the full dict (populated by run_step_a's outer handler)
        evidence = exc.evidence if exc.evidence else {"outcome": exc.verdict, "stop_reason": exc.reason}
        secrets = exc.secrets if exc.secrets else [demo_password]
        exit_code = 1
    except Exception as exc:
        evidence = {
            "schema": "wave9-step-a-v1",
            "outcome": "STOP",
            "stop_reason": f"unexpected error: {type(exc).__name__}: {exc}",
        }
        secrets = [demo_password]
        exit_code = 1

    # Write sanitized evidence — redact all secrets before writing
    raw_json = json.dumps(evidence, indent=2, default=str)
    sanitized = _redact_evidence(raw_json, secrets)
    try:
        evidence_path.write_text(sanitized, encoding="utf-8")
        print(f"Evidence written to: {evidence_path}", file=sys.stderr)
    except OSError as exc:
        print(f"ERROR: could not write evidence: {exc}", file=sys.stderr)
        exit_code = 1  # fail-closed: GO without evidence is treated as STOP

    verdict = evidence.get("outcome", "STOP")
    print(f"Outcome: {verdict}", file=sys.stderr)
    if verdict != "GO":
        reason = evidence.get("stop_reason", "")
        if reason:
            print(f"Reason: {reason}", file=sys.stderr)

    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
