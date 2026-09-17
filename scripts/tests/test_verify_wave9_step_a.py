#!/usr/bin/env python3
"""Offline contract tests for scripts/verify_wave9_step_a.py.

Nothing here contacts Azure, the gateway, or a registry. Every HTTP call is
replaced by an injectable callable so the tests assert on exactly what
sequence of requests the script issues and what stop conditions it enforces.

Run:
  python -m pytest scripts/tests/test_verify_wave9_step_a.py -v
  # or:
  python scripts/tests/test_verify_wave9_step_a.py
"""

from __future__ import annotations

import sys
import time
import unittest
import unittest.mock
import urllib.error
from decimal import Decimal
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))

import verify_wave9_step_a as subject

DEMO_USER_ID = subject.DEMO_USER_ID
DEMO_EMAIL = subject.DEMO_EMAIL


# ---------------------------------------------------------------------------
# Test fixtures
# ---------------------------------------------------------------------------

GOLDEN_2 = [
    {"assetTicker": "AAPL", "quantity": "37.00000000"},
    {"assetTicker": "MSFT", "quantity": "22.00000000"},
]
GOLDEN_2_SHA = "abc123"


def _fake_load_golden(path: Path) -> tuple[list[dict[str, str]], str]:
    return GOLDEN_2, GOLDEN_2_SHA


def _portfolio(version: int, holdings: list[dict[str, str]] | None = None) -> dict[str, Any]:
    """Build a minimal portfolio response body."""
    return {
        "userId": DEMO_USER_ID,
        "version": version,
        "holdings": holdings if holdings is not None else _golden_holdings_as_response(GOLDEN_2),
    }


def _golden_holdings_as_response(golden: list[dict[str, str]]) -> list[dict[str, Any]]:
    return [{"assetTicker": r["assetTicker"], "quantity": r["quantity"]} for r in golden]


def _non_golden_holdings_as_response() -> list[dict[str, Any]]:
    """One holding with modified quantity — clearly non-golden."""
    first = GOLDEN_2[0]
    qty = format(Decimal(first["quantity"]) + Decimal("1.00000000"), ".8f")
    return [{"assetTicker": first["assetTicker"], "quantity": qty}]


def _cleanup_responses() -> list[tuple[int, Any]]:
    """Three-response cleanup sequence: obs read, reset, post-cleanup read.

    Uses generic version numbers so these can be appended to any post-ARM
    stop test regardless of the actual version in play.
    """
    return [
        (200, _portfolio(99)),   # cleanup observation read (valid portfolio, any version)
        (200, None),             # cleanup reset (body unused; only status matters)
        (200, _portfolio(100)),  # post-cleanup read (golden by default)
    ]


def _base_config(password: str = "test-password") -> subject.StepAConfig:
    return subject.StepAConfig(
        gateway_url="https://gateway.test",
        oracle_path=Path("/dev/null"),
        evidence_output=Path("evidence.json"),
        demo_password=password,
        operation_timeout_seconds=5.0,
        baseline_commit="test-commit-abc123",
    )


# ---------------------------------------------------------------------------
# HTTP call recorder
# ---------------------------------------------------------------------------

class _RecorderExhausted(BaseException):
    """Raised when CallRecorder runs out of scripted responses.

    Inherits from BaseException (not Exception) so it propagates through
    run_step_a's 'except Exception' catch-alls unchanged, surfacing as an
    explicit test failure rather than being silently converted to a STOP.
    """


class CallRecorder:
    """Replays a scripted sequence of (status, body) pairs or exceptions.

    Each response entry is either a (status, body) tuple or a BaseException
    instance.  When a BaseException is popped it is raised directly, allowing
    tests to inject network errors (urllib.error.URLError, TimeoutError, etc.)
    at any point in the call sequence.
    """

    def __init__(self, responses: list) -> None:
        self.responses = list(responses)
        self.calls: list[dict[str, Any]] = []

    def __call__(
        self,
        method: str,
        url: str,
        headers: dict[str, str],
        json_body: Any = None,
        timeout: float = 30.0,
    ) -> tuple[int, Any]:
        if not self.responses:
            raise _RecorderExhausted(f"unexpected HTTP call: {method} {url}")
        item = self.responses.pop(0)
        self.calls.append({
            "method": method,
            "url": url,
            "headers_keys": sorted(headers),
            "body": json_body,
            "timeout": timeout,
        })
        if isinstance(item, BaseException):
            raise item
        status, body = item
        return status, body

    @property
    def call_count(self) -> int:
        return len(self.calls)

    def assert_no_remaining(self) -> None:
        assert not self.responses, f"unconsumed responses: {self.responses}"


# ---------------------------------------------------------------------------
# Helpers for constructing full happy-path sequences
# ---------------------------------------------------------------------------

def _happy_path_responses(v_baseline: int = 5) -> list[tuple[int, Any]]:
    """Returns a scripted full-success sequence."""
    v_post_write = v_baseline + 1
    v_post_reset = v_post_write + 1
    return [
        (200, {"token": "test-jwt-token"}),                    # login
        (200, _portfolio(v_baseline)),                          # read 1: post-login baseline
        (200, _portfolio(v_post_write)),                        # composition write response (holdings write)
        (200, _portfolio(v_post_write)),                        # read 2: pre-reset
        (200, _portfolio(v_post_reset, _golden_holdings_as_response(GOLDEN_2))),  # demo-reset
        (200, _portfolio(v_post_reset)),                        # post-reset read
        (200, _portfolio(v_post_reset)),                        # cleanup observation read
        (200, _portfolio(v_post_reset)),                        # cleanup reset (no-op 200)
        (200, _portfolio(v_post_reset)),                        # post-cleanup read
    ]


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class LiteralConstantsTest(unittest.TestCase):
    def test_cleanup_max_attempts_is_one(self) -> None:
        """CLEANUP_MAX_ATTEMPTS must be a literal 1 in the script, not a CLI flag."""
        self.assertEqual(subject.CLEANUP_MAX_ATTEMPTS, 1)

    def test_login_timeout_exceeds_overall_timeout(self) -> None:
        """LOGIN_TIMEOUT_SECONDS must strictly exceed OVERALL_TIMEOUT_SECONDS (165s)."""
        self.assertGreater(subject.LOGIN_TIMEOUT_SECONDS, subject.OVERALL_TIMEOUT_SECONDS)
        self.assertEqual(subject.OVERALL_TIMEOUT_SECONDS, 165.0)
        self.assertGreaterEqual(subject.LOGIN_TIMEOUT_SECONDS, 225.0)

    def test_phase3_max_reads_is_four(self) -> None:
        self.assertEqual(subject.PHASE3_MAX_READS, 4)

    def test_reset_max_attempts_is_three(self) -> None:
        self.assertEqual(subject.RESET_MAX_ATTEMPTS, 3)

    def test_password_var_name(self) -> None:
        self.assertEqual(subject.STEP_A_PASSWORD_VAR, "WAVE9_STEP_A_PASSWORD")


class HappyPathTest(unittest.TestCase):
    def _run(self, v_baseline: int = 5) -> tuple[dict[str, Any], list[str], CallRecorder]:
        recorder = CallRecorder(_happy_path_responses(v_baseline))
        evidence, secrets = subject.run_step_a(
            _base_config(),
            http_call=recorder,
            load_golden=_fake_load_golden,
            monotonic=time.monotonic,
        )
        return evidence, secrets, recorder

    def test_go_outcome(self) -> None:
        evidence, _, _ = self._run()
        self.assertEqual(evidence["outcome"], "GO")
        self.assertIsNone(evidence["stop_reason"])

    def test_exactly_one_login(self) -> None:
        _, _, recorder = self._run()
        logins = [c for c in recorder.calls if c["url"].endswith("/api/auth/login")]
        self.assertEqual(len(logins), 1)
        self.assertEqual(logins[0]["method"], "POST")

    def test_login_timeout_is_225s(self) -> None:
        _, _, recorder = self._run()
        login_call = next(c for c in recorder.calls if c["url"].endswith("/api/auth/login"))
        self.assertGreaterEqual(login_call["timeout"], 225.0)

    def test_login_body_does_not_expose_password_key_in_ops(self) -> None:
        """The operations list must record body_keys but never the password value."""
        evidence, _, _ = self._run()
        for op in evidence["operations"]:
            if op.get("class") == "login":
                self.assertNotIn("password", op.get("request_body_keys", []))

    def test_exactly_one_composition_write(self) -> None:
        _, _, recorder = self._run()
        writes = [c for c in recorder.calls if c["url"].endswith("/api/portfolio/holdings")]
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0]["method"], "PUT")

    def test_composition_write_body_uses_expected_version(self) -> None:
        v = 5
        _, _, recorder = self._run(v_baseline=v)
        write_call = next(c for c in recorder.calls if c["url"].endswith("/api/portfolio/holdings"))
        self.assertEqual(write_call["body"]["expectedVersion"], v)

    def test_exactly_one_reset_attempt_on_happy_path(self) -> None:
        _, _, recorder = self._run()
        resets = [c for c in recorder.calls
                  if c["url"].endswith("/api/portfolio/demo-reset") and c["method"] == "PUT"]
        # 1 gate reset + 1 cleanup reset (no-op) = 2
        self.assertEqual(len(resets), 2)

    def test_cleanup_always_runs(self) -> None:
        evidence, _, _ = self._run()
        self.assertTrue(evidence["cleanup"]["armed"])
        self.assertEqual(evidence["cleanup"]["result"], "200")

    def test_version_progression_recorded(self) -> None:
        evidence, _, _ = self._run(v_baseline=5)
        vp = evidence["version_progression"]
        self.assertEqual(vp["post_login_baseline"], 5)
        self.assertIn("pre_reset", vp)
        self.assertIn("post_reset_response", vp)
        self.assertIn("post_cleanup_read", vp)

    def test_golden_assertions_recorded(self) -> None:
        evidence, _, _ = self._run()
        ga = evidence["golden_assertions"]
        self.assertTrue(ga["reset_response_holdings_match_golden"])
        self.assertTrue(ga["post_reset_read_holdings_match"])
        self.assertTrue(ga["post_cleanup_holdings_match"])

    def test_secrets_list_contains_password_and_token(self) -> None:
        _, secrets, _ = self._run()
        self.assertIn("test-password", secrets)
        self.assertIn("test-jwt-token", secrets)

    def test_no_secrets_in_evidence_json(self) -> None:
        evidence, secrets, _ = self._run()
        raw = subject.json.dumps(evidence)
        sanitized = subject._redact_evidence(raw, secrets)
        self.assertNotIn("test-password", sanitized)
        self.assertNotIn("test-jwt-token", sanitized)

    def test_baseline_commit_in_evidence(self) -> None:
        evidence, _, _ = self._run()
        self.assertEqual(evidence["baseline_commit"], "test-commit-abc123")


class StopConditionsTest(unittest.TestCase):
    def _run_with_responses(self, responses) -> dict[str, Any]:
        recorder = CallRecorder(responses)
        try:
            evidence, secrets = subject.run_step_a(
                _base_config(),
                http_call=recorder,
                load_golden=_fake_load_golden,
                monotonic=time.monotonic,
            )
        except subject.StopError as exc:
            # Use exc.evidence so that post-ARM stops surface their full evidence,
            # including cleanup result and the complete operations list.
            evidence = exc.evidence
        recorder.assert_no_remaining()
        return evidence

    def test_stop_if_login_not_200(self) -> None:
        evidence = self._run_with_responses([(503, None)])
        self.assertEqual(evidence["outcome"], "STOP")
        self.assertIn("503", evidence["stop_reason"])

    def test_stop_if_login_no_token(self) -> None:
        evidence = self._run_with_responses([(200, {"other": "field"})])
        self.assertEqual(evidence["outcome"], "STOP")
        self.assertIn("token", evidence["stop_reason"].lower())

    def test_stop_if_version_null_after_login(self) -> None:
        """Version absent/null after login -> STOP before write."""
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, {"userId": DEMO_USER_ID, "version": None, "holdings": []}),
        ])
        self.assertEqual(evidence["outcome"], "STOP")
        self.assertIn("version", evidence["stop_reason"].lower())

    def test_stop_if_version_non_integer_after_login(self) -> None:
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, {"userId": DEMO_USER_ID, "version": "not-an-int", "holdings": []}),
        ])
        self.assertEqual(evidence["outcome"], "STOP")
        self.assertIn("version", evidence["stop_reason"].lower())

    def test_stop_if_zero_portfolio_matches(self) -> None:
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, []),  # no portfolios
        ])
        self.assertEqual(evidence["outcome"], "NON_GO")

    def test_stop_if_multiple_portfolio_matches(self) -> None:
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, [_portfolio(1), _portfolio(2)]),  # two matches
        ])
        self.assertEqual(evidence["outcome"], "NON_GO")

    def test_stop_if_composition_write_not_200(self) -> None:
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(5)),      # read 1
            (409, None),               # composition write -> NON_GO (deferred)
        ] + _cleanup_responses())
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertIn("409", evidence["stop_reason"])
        self.assertEqual(evidence["cleanup"]["result"], "200")  # C2: cleanup ran

    def test_stop_if_composition_write_did_not_advance_version(self) -> None:
        """Pre-reset version must be strictly greater than baseline."""
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(5)),      # read 1: baseline v=5
            (200, _portfolio(5)),      # composition write 200 ok
            (200, _portfolio(5)),      # read 2: pre-reset — still v=5 (no advance)
        ] + _cleanup_responses())
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertIn("advance", evidence["stop_reason"].lower())
        self.assertEqual(evidence["cleanup"]["result"], "200")

    def test_stop_if_all_3_reset_attempts_return_409(self) -> None:
        v = 5
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),         # read 1
            (200, _portfolio(v + 1)),     # composition write
            (200, _portfolio(v + 1)),     # read 2 pre-reset
            (409, None),                  # reset attempt 1
            (200, _portfolio(v + 1)),     # read 3 re-observe
            (409, None),                  # reset attempt 2
            (200, _portfolio(v + 1)),     # read 4 re-observe (last allowed)
            (409, None),                  # reset attempt 3 -> NON_GO
        ] + _cleanup_responses())
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertEqual(evidence["cleanup"]["result"], "200")

    def test_stop_if_reset_returns_unexpected_status(self) -> None:
        v = 5
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v + 1)),
            (500, None),                  # unexpected 5xx
        ] + _cleanup_responses())
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertIn("500", evidence["stop_reason"])
        self.assertEqual(evidence["cleanup"]["result"], "200")

    def test_stop_if_reset_response_version_wrong(self) -> None:
        v = 5
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v + 1)),
            # Reset 200 but version is wrong (v+3 instead of v+2)
            (200, _portfolio(v + 3, _golden_holdings_as_response(GOLDEN_2))),
        ] + _cleanup_responses())
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertEqual(evidence["cleanup"]["result"], "200")

    def test_stop_if_reset_response_holdings_not_golden(self) -> None:
        v = 5
        bad_holdings = [{"assetTicker": "FAKE", "quantity": "1.00000000"}]
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v + 2, bad_holdings)),   # wrong holdings
        ] + _cleanup_responses())
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertEqual(evidence["cleanup"]["result"], "200")

    def test_stop_if_post_reset_read_not_golden(self) -> None:
        v = 5
        v_pr = v + 2
        bad_holdings = [{"assetTicker": "FAKE", "quantity": "1.00000000"}]
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v_pr, _golden_holdings_as_response(GOLDEN_2))),  # reset ok
            (200, _portfolio(v_pr, bad_holdings)),                             # post-reset read: bad
        ] + _cleanup_responses())
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertEqual(evidence["cleanup"]["result"], "200")

    def test_stop_if_cleanup_returns_409(self) -> None:
        v = 5
        v_pr = v + 2
        evidence = self._run_with_responses([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v + 1)),
            (200, _portfolio(v_pr, _golden_holdings_as_response(GOLDEN_2))),
            (200, _portfolio(v_pr)),                   # post-reset read
            (200, _portfolio(v_pr)),                   # cleanup observation read
            (409, None),                               # cleanup reset -> NON_GO
        ])
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertIn("409", evidence["stop_reason"])


class RetryLogicTest(unittest.TestCase):
    def test_success_on_second_reset_attempt(self) -> None:
        v = 5
        v_post_write = v + 1
        v_post_reset = v_post_write + 1
        recorder = CallRecorder([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(v_post_write)),
            (200, _portfolio(v_post_write)),        # pre-reset read
            (409, None),                            # reset attempt 1 -> 409
            (200, _portfolio(v_post_write)),        # re-observe
            (200, _portfolio(v_post_reset, _golden_holdings_as_response(GOLDEN_2))),  # attempt 2 -> 200
            (200, _portfolio(v_post_reset)),        # post-reset read
            (200, _portfolio(v_post_reset)),        # cleanup observation read
            (200, _portfolio(v_post_reset)),        # cleanup reset
            (200, _portfolio(v_post_reset)),        # post-cleanup read
        ])
        evidence, _ = subject.run_step_a(
            _base_config(),
            http_call=recorder,
            load_golden=_fake_load_golden,
            monotonic=time.monotonic,
        )
        self.assertEqual(evidence["outcome"], "GO")

    def test_success_on_third_reset_attempt(self) -> None:
        v = 5
        vw = v + 1
        vr = vw + 1
        recorder = CallRecorder([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(vw)),
            (200, _portfolio(vw)),     # read 2
            (409, None),               # attempt 1
            (200, _portfolio(vw)),     # read 3
            (409, None),               # attempt 2
            (200, _portfolio(vw)),     # read 4
            (200, _portfolio(vr, _golden_holdings_as_response(GOLDEN_2))),  # attempt 3 -> 200
            (200, _portfolio(vr)),
            (200, _portfolio(vr)),
            (200, _portfolio(vr)),
            (200, _portfolio(vr)),
        ])
        evidence, _ = subject.run_step_a(
            _base_config(),
            http_call=recorder,
            load_golden=_fake_load_golden,
            monotonic=time.monotonic,
        )
        self.assertEqual(evidence["outcome"], "GO")

    @unittest.mock.patch.object(subject, "RESET_MAX_ATTEMPTS", 4)
    def test_phase3_read_cap_enforced(self) -> None:
        """Phase 3 read cap fires before attempt 4 would need a 5th read.

        With RESET_MAX_ATTEMPTS=4 patched in, attempts 1 and 2 each consume a
        re-observe read (reads 3 and 4, read_count becomes 2→3→4). When attempt
        3 returns 409, the code tries to read before attempt 4 but
        phase3_read_count==4 >= PHASE3_MAX_READS==4 so the cap fires.
        Total reads: 1 (baseline) + 1 (pre_reset) + 1 (re_obs_2) + 1 (re_obs_3)
        = 4. Cap check triggers before re_obs_4 is issued.
        """
        v, vw = 5, 6
        try:
            subject.run_step_a(
                _base_config(),
                http_call=CallRecorder([
                    (200, {"token": "jwt"}),
                    (200, _portfolio(v)),     # read 1 (baseline)
                    (200, _portfolio(vw)),    # write ok
                    (200, _portfolio(vw)),    # read 2 pre-reset
                    (409, None),              # attempt 1
                    (200, _portfolio(vw)),    # read 3 re-observe (attempt 2)
                    (409, None),              # attempt 2
                    (200, _portfolio(vw)),    # read 4 re-observe (attempt 3)
                    (409, None),              # attempt 3 -> tries re_observe_attempt_4 -> cap
                ] + _cleanup_responses()),
                load_golden=_fake_load_golden,
                monotonic=time.monotonic,
            )
            self.fail("expected StopError")
        except subject.StopError as exc:
            evidence = exc.evidence
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertIn("cap", evidence["stop_reason"].lower())
        self.assertEqual(evidence["cleanup"]["result"], "200")  # C2: cleanup ran


class LoginSlowTest(unittest.TestCase):
    def test_stop_if_login_round_trip_exceeds_165s(self) -> None:
        """If login takes >= 165s, STOP before any write."""
        call_count = [0]
        def slow_http(method, url, headers, json_body=None, timeout=30.0):
            call_count[0] += 1
            if method == "POST" and url.endswith("/api/auth/login"):
                return 200, {"token": "jwt"}
            raise _RecorderExhausted(f"unexpected call after slow login: {method} {url}")

        # Monotonic that jumps 170s after login call
        times = iter([0.0, 170.0, 170.0])
        def fast_monotonic():
            return next(times)

        try:
            subject.run_step_a(
                _base_config(),
                http_call=slow_http,
                load_golden=_fake_load_golden,
                monotonic=fast_monotonic,
            )
            self.fail("expected StopError")
        except subject.StopError as exc:
            self.assertEqual(exc.verdict, "STOP")
            self.assertIn("165", exc.reason)
        self.assertEqual(call_count[0], 1)  # only login; no portfolio read


class EvidenceStructureTest(unittest.TestCase):
    def test_cleanup_armed_flag_set_before_write(self) -> None:
        """cleanup.armed is True and cleanup runs even when the write fails (500).

        This verifies C1 (StopError carries full evidence) and C2 (cleanup
        runs unconditionally after ARM regardless of deferred stops).
        """
        recorder = CallRecorder([
            (200, {"token": "jwt"}),
            (200, _portfolio(5)),   # read 1: baseline
            (500, None),            # composition write fails -> deferred stop
            # cleanup runs unconditionally after ARM:
            (200, _portfolio(5)),   # cleanup observation read
            (200, None),            # cleanup reset (body unused)
            (200, _portfolio(6)),   # post-cleanup read (golden by default)
        ])
        try:
            subject.run_step_a(
                _base_config(),
                http_call=recorder,
                load_golden=_fake_load_golden,
                monotonic=time.monotonic,
            )
            self.fail("expected StopError")
        except subject.StopError as exc:
            evidence = exc.evidence
        self.assertTrue(evidence["cleanup"]["armed"])
        self.assertEqual(evidence["cleanup"]["result"], "200")
        self.assertEqual(evidence["outcome"], "NON_GO")
        self.assertIn("gate_map", evidence)

    def test_schema_field_present(self) -> None:
        recorder = CallRecorder(_happy_path_responses())
        evidence, _ = subject.run_step_a(
            _base_config(),
            http_call=recorder,
            load_golden=_fake_load_golden,
            monotonic=time.monotonic,
        )
        self.assertEqual(evidence["schema"], "wave9-step-a-v1")

    def test_gate_map_present(self) -> None:
        recorder = CallRecorder(_happy_path_responses())
        evidence, _ = subject.run_step_a(
            _base_config(),
            http_call=recorder,
            load_golden=_fake_load_golden,
            monotonic=time.monotonic,
        )
        gm = evidence["gate_map"]
        self.assertEqual(gm["wave10_2_go_action"], "step_a")
        self.assertEqual(gm["condition_5_status"], "open_owner_question")


class NonGoldenCompositionTest(unittest.TestCase):
    def test_non_golden_uses_all_oracle_holdings(self) -> None:
        """The write sends all oracle holdings (one modified), not a subset."""
        body = subject._non_golden_composition(5, GOLDEN_2)
        self.assertEqual(len(body["holdings"]), len(GOLDEN_2))
        self.assertEqual(body["expectedVersion"], 5)

    def test_non_golden_modifies_first_holding_only(self) -> None:
        body = subject._non_golden_composition(5, GOLDEN_2)
        first = body["holdings"][0]
        oracle_first = GOLDEN_2[0]
        orig_qty = Decimal(oracle_first["quantity"])
        written_qty = Decimal(first["quantity"])
        self.assertEqual(written_qty, orig_qty + Decimal("1.00000000"))
        # Other holdings are unchanged
        for oracle_row, written_row in zip(GOLDEN_2[1:], body["holdings"][1:]):
            self.assertEqual(Decimal(written_row["quantity"]), Decimal(oracle_row["quantity"]))

    def test_non_golden_uses_ticker_key_in_request_body(self) -> None:
        """PUT /api/portfolio/holdings uses 'ticker', not 'assetTicker'."""
        body = subject._non_golden_composition(5, GOLDEN_2)
        for holding in body["holdings"]:
            self.assertIn("ticker", holding)
            self.assertNotIn("assetTicker", holding)


class VersionContractTest(unittest.TestCase):
    def test_expected_post_reset_version_is_pre_reset_plus_one(self) -> None:
        """B1 contract: SET version = version + 1 (HoldingReplacementService.java:163)."""
        v = 10
        v_post_write = v + 1
        v_post_reset = v_post_write + 1
        recorder = CallRecorder([
            (200, {"token": "jwt"}),
            (200, _portfolio(v)),
            (200, _portfolio(v_post_write)),
            (200, _portfolio(v_post_write)),
            # Reset response with correct version
            (200, _portfolio(v_post_reset, _golden_holdings_as_response(GOLDEN_2))),
            (200, _portfolio(v_post_reset)),
            (200, _portfolio(v_post_reset)),
            (200, _portfolio(v_post_reset)),
            (200, _portfolio(v_post_reset)),
        ])
        evidence, _ = subject.run_step_a(
            _base_config(),
            http_call=recorder,
            load_golden=_fake_load_golden,
            monotonic=time.monotonic,
        )
        self.assertEqual(evidence["outcome"], "GO")
        vp = evidence["version_progression"]
        self.assertEqual(vp["post_reset_response"], v_post_reset)
        self.assertEqual(v_post_reset - vp["pre_reset"], 1)


class UnexpectedExceptionTest(unittest.TestCase):
    """Regression tests for I-new-2: non-StopError exceptions after ARM must
    still run cleanup and attach full evidence (via the except Exception clauses
    added to the deferred section, cleanup section, and outer try).
    """

    def test_urlerror_pre_arm_exits_stop_with_evidence(self) -> None:
        """URLError at login (pre-ARM) -> STOP; evidence populated, secrets has password."""
        try:
            subject.run_step_a(
                _base_config(),
                http_call=CallRecorder([urllib.error.URLError("network")]),
                load_golden=_fake_load_golden,
                monotonic=time.monotonic,
            )
            self.fail("expected StopError")
        except subject.StopError as exc:
            self.assertEqual(exc.verdict, "STOP")
            self.assertIn("URLError", exc.evidence["stop_reason"])
            self.assertFalse(exc.evidence["cleanup"]["armed"])
            self.assertIn("test-password", exc.secrets)

    def test_unexpected_error_post_arm_cleanup_still_runs(self) -> None:
        """TimeoutError on composition write (post-ARM) -> cleanup runs; result==200."""
        try:
            subject.run_step_a(
                _base_config(),
                http_call=CallRecorder([
                    (200, {"token": "jwt"}),
                    (200, _portfolio(5)),            # read 1 baseline
                    TimeoutError("write timed out"), # composition write raises
                    # cleanup runs unconditionally:
                    (200, _portfolio(5)),            # cleanup obs read
                    (200, None),                     # cleanup reset
                    (200, _portfolio(6)),            # post-cleanup read (golden)
                ]),
                load_golden=_fake_load_golden,
                monotonic=time.monotonic,
            )
            self.fail("expected StopError")
        except subject.StopError as exc:
            self.assertEqual(exc.verdict, "STOP")
            self.assertIn("post-arm", exc.evidence["stop_reason"].lower())
            self.assertEqual(exc.evidence["cleanup"]["result"], "200")

    def test_unexpected_error_in_cleanup_captured_in_result(self) -> None:
        """URLError in cleanup obs read -> cleanup.result records the error."""
        v = 5
        v_pr = v + 2
        try:
            subject.run_step_a(
                _base_config(),
                http_call=CallRecorder([
                    (200, {"token": "jwt"}),
                    (200, _portfolio(v)),
                    (200, _portfolio(v + 1)),    # composition write
                    (200, _portfolio(v + 1)),    # read 2 pre-reset
                    (200, _portfolio(v_pr, _golden_holdings_as_response(GOLDEN_2))),  # reset
                    (200, _portfolio(v_pr)),     # post-reset read (golden)
                    urllib.error.URLError("cleanup network"),  # cleanup obs read raises
                ]),
                load_golden=_fake_load_golden,
                monotonic=time.monotonic,
            )
            self.fail("expected StopError")
        except subject.StopError as exc:
            self.assertEqual(exc.verdict, "STOP")
            self.assertIn("URLError", exc.evidence["cleanup"]["result"])
            self.assertIn("cleanup", exc.evidence["cleanup"]["result"].lower())


if __name__ == "__main__":
    unittest.main()
