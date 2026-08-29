#!/usr/bin/env python3
"""Fixed-vector tests for scripts/derive_demo_golden_state.py."""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import unittest
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "derive_demo_golden_state.py"
CATALOG = REPO / "config" / "seed-tickers.json"
sys.path.insert(0, str(REPO / "scripts"))

EXPECTED_CATALOG_SHA256 = "3F2EC8598DFF138E979A7EC500A1D04F7A7988002DCE8DDE79D4498C9828A306"
EXPECTED_TOTAL_ENTRIES = 160
EXPECTED_ACTIVE_ENTRIES = 159
DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110"
ANCHOR = "2020-01-01T00:00:00Z"

# Independently reviewed fixed vectors for catalog digest 3F2EC859… at anchor 2020-01-01T00:00:00Z.
# Must not be derived from run_oracle() in the test body.
REVIEWED_REPRESENTATIVE_VECTORS = {
    "AAPL": {
        "wire": {"assetTicker": "AAPL", "quantity": "37.00000000"},
        "persisted": {
            "assetTicker": "AAPL",
            "quantity": "37.00000000",
            "avgCostBasis": "209.1094",
            "costBasisCurrency": "USD",
            "costBasisSource": "SEED",
            "costBasisAsOf": ANCHOR,
        },
    },
    "BTC-USD": {
        "wire": {"assetTicker": "BTC-USD", "quantity": "15.00000000"},
        "persisted": {
            "assetTicker": "BTC-USD",
            "quantity": "15.00000000",
            "avgCostBasis": "67553.4322",
            "costBasisCurrency": "USD",
            "costBasisSource": "SEED",
            "costBasisAsOf": ANCHOR,
        },
    },
    "EURUSD=X": {
        "wire": {"assetTicker": "EURUSD=X", "quantity": "32.00000000"},
        "persisted": {
            "assetTicker": "EURUSD=X",
            "quantity": "32.00000000",
            "avgCostBasis": "1.1001",
            "costBasisCurrency": "USD",
            "costBasisSource": "SEED",
            "costBasisAsOf": ANCHOR,
        },
    },
}


def run_oracle(*extra: str) -> dict:
    cmd = [sys.executable, str(SCRIPT), "--catalog", str(CATALOG), *extra]
    completed = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return json.loads(completed.stdout)


class DeriveDemoGoldenStateTest(unittest.TestCase):
    def test_java_hash_code_negative_overflow(self) -> None:
        from derive_demo_golden_state import java_hash_code

        self.assertEqual(java_hash_code("polygenelubricants"), -2147483648)
        self.assertEqual(java_hash_code(""), 0)
        self.assertEqual(java_hash_code("Aa"), java_hash_code("BB"))

    def test_java_floor_mod_negative_dividend(self) -> None:
        from derive_demo_golden_state import java_floor_mod

        self.assertEqual(java_floor_mod(-1, 50), 49)
        self.assertEqual(java_floor_mod(-51, 50), 49)

    def test_rounding_half_up_at_scale_four(self) -> None:
        value = Decimal("1.23455")
        self.assertEqual(
            value.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP), Decimal("1.2346")
        )

    def test_representative_fiat_crypto_fx_vectors_pinned(self) -> None:
        document = run_oracle()
        wire_by_ticker = {row["assetTicker"]: row for row in document["wireHoldings"]}
        persisted_by_ticker = {
            row["assetTicker"]: row for row in document["persistedHoldings"]
        }
        for ticker, expected in REVIEWED_REPRESENTATIVE_VECTORS.items():
            self.assertEqual(wire_by_ticker[ticker], expected["wire"])
            self.assertEqual(persisted_by_ticker[ticker], expected["persisted"])

    def test_active_catalog_excludes_deprecated_entry(self) -> None:
        document = run_oracle()
        tickers = {row["assetTicker"] for row in document["wireHoldings"]}
        self.assertNotIn("TATAMOTORS.NS", tickers)
        self.assertEqual(document["metadata"]["activeEntryCount"], EXPECTED_ACTIVE_ENTRIES)

    def test_derive_golden_state_identity_not_selectable(self) -> None:
        from derive_demo_golden_state import derive_golden_state

        with self.assertRaises(TypeError):
            derive_golden_state(CATALOG, demo_user_id="00000000-0000-0000-0000-000000000001")

    def test_catalog_digest_and_counts_pinned(self) -> None:
        digest = hashlib.sha256(CATALOG.read_bytes()).hexdigest().upper()
        self.assertEqual(digest, EXPECTED_CATALOG_SHA256)
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        self.assertEqual(len(catalog), EXPECTED_TOTAL_ENTRIES)

        document = run_oracle()
        self.assertEqual(document["metadata"]["catalogSha256"], EXPECTED_CATALOG_SHA256)
        self.assertEqual(document["metadata"]["catalogTotalEntries"], EXPECTED_TOTAL_ENTRIES)
        self.assertEqual(document["metadata"]["activeEntryCount"], EXPECTED_ACTIVE_ENTRIES)

    def test_stable_canonical_output(self) -> None:
        first = subprocess.run(
            [sys.executable, str(SCRIPT), "--catalog", str(CATALOG)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        second = subprocess.run(
            [sys.executable, str(SCRIPT), "--catalog", str(CATALOG)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        self.assertEqual(first, second)
        parsed = json.loads(first)
        self.assertEqual(list(parsed.keys()), ["metadata", "persistedHoldings", "wireHoldings"])

    def test_wire_and_persisted_quantity_scales(self) -> None:
        document = run_oracle()
        sample = next(row for row in document["persistedHoldings"] if row["assetTicker"] == "AAPL")
        wire = next(row for row in document["wireHoldings"] if row["assetTicker"] == "AAPL")
        self.assertRegex(sample["quantity"], r"^\d+\.\d{8}$")
        self.assertNotIn("E", sample["avgCostBasis"])
        self.assertRegex(wire["quantity"], r"^\d+\.\d{8}$")

    def test_demo_user_id_not_selectable_from_cli(self) -> None:
        completed = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--catalog",
                str(CATALOG),
                "--demo-user-id",
                "00000000-0000-0000-0000-000000000001",
            ],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(completed.returncode, 0)

    def test_catalog_evolution_guard_has_non_empty_expected_set(self) -> None:
        document = run_oracle()
        self.assertGreaterEqual(len(document["wireHoldings"]), 150)
        self.assertEqual(
            len(document["wireHoldings"]), len(document["persistedHoldings"])
        )


if __name__ == "__main__":
    unittest.main()
