#!/usr/bin/env python3
"""
test_allowance_independence_check.py — Fixture tests for allowance_independence_check.py.

Property 6 (Allowance_Independence): worst-case ingestion at both workspace caps,
priced at the full paid INR rate with zero Ingestion_Allowance, plus forecast and
recurring charges, stays below the budget.

Known-good arithmetic uses the spec-cached fixture (not live Cost Analysis):
    --meter-rate 303.9479 --forecast 551.78  →  PASS, margin ≈ 114.79

Run from this directory or the repo:
    python test_allowance_independence_check.py
    python -m unittest infrastructure.terraform.azure.scripts.test_allowance_independence_check

Prefer no extra pytest dependency (stdlib unittest).
"""

from __future__ import annotations

import re
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
SCRIPT = SCRIPTS_DIR / "allowance_independence_check.py"

# Spec-cached fixture — not the live 2026-08-14 forecast 549.42.
KNOWN_GOOD_METER = "303.9479"
KNOWN_GOOD_FORECAST = "551.78"
KNOWN_GOOD_MARGIN = 114.79
MARGIN_TOLERANCE = 0.01


def _run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )


def _margin(output: str) -> float:
    match = re.search(r"margin\s*=\s*(-?\d+(?:\.\d+)?)", output)
    if not match:
        raise AssertionError(f"no margin= value in output: {output!r}")
    return float(match.group(1))


class TestKnownGoodFixturePasses(unittest.TestCase):
    def test_spec_cached_meter_and_forecast_pass_with_default_budget(self):
        result = _run("--meter-rate", KNOWN_GOOD_METER, "--forecast", KNOWN_GOOD_FORECAST)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS", result.stdout)
        self.assertNotIn("FAIL", result.stdout)
        self.assertAlmostEqual(_margin(result.stdout), KNOWN_GOOD_MARGIN, delta=MARGIN_TOLERANCE)


class TestKnownBadFixtureFails(unittest.TestCase):
    def test_budget_below_projected_spend_fails(self):
        # Projected spend is ≈ 985.21, so a ₹900 budget must FAIL.
        # `--budget 1000` still PASSes (margin ≈ 14.79); that is the old ceiling's
        # remaining headroom, not a FAIL case.
        result = _run(
            "--meter-rate",
            KNOWN_GOOD_METER,
            "--forecast",
            KNOWN_GOOD_FORECAST,
            "--budget",
            "900",
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("FAIL", result.stdout)
        self.assertNotIn("PASS", result.stdout)
        self.assertLess(_margin(result.stdout), 0)


class TestRequiredArguments(unittest.TestCase):
    def test_missing_meter_rate_exits_nonzero(self):
        result = _run("--forecast", KNOWN_GOOD_FORECAST)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("PASS", result.stdout)

    def test_missing_forecast_exits_nonzero(self):
        result = _run("--meter-rate", KNOWN_GOOD_METER)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("PASS", result.stdout)

    def test_missing_both_meter_rate_and_forecast_exits_nonzero(self):
        result = _run()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("PASS", result.stdout)


class TestInputsAreNotHardcoded(unittest.TestCase):
    def test_implementation_does_not_hardcode_meter_rate_or_forecast(self):
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn(
            "303.9479",
            source,
            "meter-rate must be a required CLI argument, never a hardcoded constant",
        )
        self.assertNotIn(
            "551.78",
            source,
            "forecast must be a required CLI argument, never a hardcoded constant",
        )
        self.assertNotIn(
            "549.42",
            source,
            "live forecast must not be hardcoded either",
        )


if __name__ == "__main__":
    unittest.main()
