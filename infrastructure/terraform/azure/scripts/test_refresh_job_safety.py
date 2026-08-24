#!/usr/bin/env python3
"""
Structural tests for the market-data-refresh-job safety properties in main.tf.

Asserts that the persisted HCL encodes the three invariants required for
checkpoint 9.10's capture-and-reconcile safety contract:

  - replica_retry_limit = 0  (no automatic retry after a partial write)
  - replica_timeout_in_seconds = 600
  - persisted MARKET_DATA_JOB_RUNNER_ENABLED = "false" (refresh stays gated off)

These tests run against the source HCL file, not a plan fixture, so they fail
if the file diverges from what was merged — no fixture staleness risk.
"""
from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
MAIN_TF = SCRIPTS_DIR.parent / "main.tf"

_RESOURCE_HEADER = re.compile(
    r'resource\s+"azurerm_container_app_job"\s+"market_data_refresh"\s*\{'
)


def _extract_job_block(text: str) -> str:
    """Return the full content of the azurerm_container_app_job.market_data_refresh block."""
    m = _RESOURCE_HEADER.search(text)
    if m is None:
        raise ValueError("azurerm_container_app_job.market_data_refresh block not found in main.tf")
    start = m.end()
    depth = 1
    pos = start
    while pos < len(text) and depth > 0:
        ch = text[pos]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
        pos += 1
    return text[start : pos - 1]


def _int_attr(block: str, name: str) -> int:
    """Extract an integer top-level attribute from an HCL block."""
    m = re.search(rf"^\s*{re.escape(name)}\s*=\s*(\d+)", block, re.MULTILINE)
    if m is None:
        raise ValueError(f"Attribute '{name}' not found in job block")
    return int(m.group(1))


def _runner_enabled_value(text: str) -> str:
    """
    Extract the persisted MARKET_DATA_JOB_RUNNER_ENABLED value from the file.

    Uses a 600-character window starting from the name string because a multi-line
    comment appears between the name and value lines in the HCL.
    """
    idx = text.find('"MARKET_DATA_JOB_RUNNER_ENABLED"')
    if idx == -1:
        raise ValueError("MARKET_DATA_JOB_RUNNER_ENABLED not found in main.tf")
    window = text[idx : idx + 600]
    m = re.search(r'\bvalue\s*=\s*"([^"]*)"', window)
    if m is None:
        raise ValueError("value for MARKET_DATA_JOB_RUNNER_ENABLED not found within 600 chars")
    return m.group(1)


class RefreshJobSafetyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not MAIN_TF.exists():
            raise FileNotFoundError(f"main.tf not found at {MAIN_TF}")
        content = MAIN_TF.read_text(encoding="utf-8")
        cls.job_block = _extract_job_block(content)
        cls.full_content = content

    def test_replica_retry_limit_is_zero(self) -> None:
        """replica_retry_limit must be 0 so failed replicas do not auto-retry."""
        limit = _int_attr(self.job_block, "replica_retry_limit")
        self.assertEqual(
            limit,
            0,
            f"replica_retry_limit is {limit}; must be 0 — automatic retry after a partial "
            "Mongo/Kafka write bypasses the required capture-and-reconcile procedure.",
        )

    def test_replica_timeout_is_600(self) -> None:
        """replica_timeout_in_seconds must remain 600."""
        timeout = _int_attr(self.job_block, "replica_timeout_in_seconds")
        self.assertEqual(timeout, 600, f"replica_timeout_in_seconds is {timeout}; expected 600")

    def test_persisted_runner_disabled(self) -> None:
        """MARKET_DATA_JOB_RUNNER_ENABLED must be 'false' in the persisted Job template."""
        value = _runner_enabled_value(self.job_block)
        self.assertEqual(
            value,
            "false",
            f"MARKET_DATA_JOB_RUNNER_ENABLED persisted value is '{value}'; must be 'false' — "
            "only the checkpoint 9.10 execution template carries 'true'.",
        )


if __name__ == "__main__":
    unittest.main()
