#!/usr/bin/env python3
"""Independent golden-state oracle for the demo portfolio reset path.

Reproduces B1's deterministic formulas using Python stdlib only. Must not import or invoke any
production Java helper.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any

DEMO_USER_ID = "00000000-0000-0000-0000-0000000d3110"
DEFAULT_ANCHOR = "2020-01-01T00:00:00Z"
DEFAULT_CATALOG = Path(__file__).resolve().parents[1] / "config" / "seed-tickers.json"

QUANTITY_RANGE = 50
SEED_JITTER_RANGE_BPS = 500
COST_BASIS_JITTER_RANGE = 400
COST_BASIS_CENTRE = 200
PRICE_SCALE = 4
PERSISTED_QUANTITY_SCALE = 8


def java_hash_code(value: str) -> int:
    """Java String.hashCode() with signed 32-bit overflow semantics."""
    h = 0
    for ch in value:
        h = 31 * h + ord(ch)
        h = ((h + 2**31) % 2**32) - 2**31
    return h


def java_floor_mod(x: int, y: int) -> int:
    """Java Math.floorMod(x, y)."""
    r = x % y
    if (r > 0 and y < 0) or (r < 0 and y > 0):
        r -= y
    return r


def quantity_for(ticker: str) -> int:
    return java_floor_mod(java_hash_code(ticker), QUANTITY_RANGE) + 1


def seed_price(base_price: Decimal, ticker: str) -> Decimal:
    seed = java_hash_code(f"{ticker}:{DEMO_USER_ID}")
    jitter_bps = java_floor_mod(seed, SEED_JITTER_RANGE_BPS)
    multiplier = Decimal(1) + Decimal(jitter_bps).scaleb(-4)
    return (base_price * multiplier).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def cost_basis(seed_price_value: Decimal, ticker: str) -> Decimal:
    seed = java_hash_code(f"{ticker}:{DEMO_USER_ID}")
    jitter_bps = java_floor_mod(seed, COST_BASIS_JITTER_RANGE) - COST_BASIS_CENTRE
    multiplier = Decimal(1) + Decimal(jitter_bps).scaleb(-4)
    return (seed_price_value * multiplier).quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)


def decimal_to_plain(value: Decimal) -> str:
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def persisted_quantity(quantity: int) -> str:
    return format(Decimal(quantity).quantize(Decimal("1." + "0" * PERSISTED_QUANTITY_SCALE)), "f")


def load_catalog(path: Path) -> list[dict[str, Any]]:
    raw = path.read_text(encoding="utf-8")
    catalog = json.loads(raw, parse_float=Decimal)
    if not isinstance(catalog, list):
        raise ValueError("catalog must be a JSON array")
    return catalog


def catalog_digest(path: Path) -> str:
    data = path.read_bytes()
    return hashlib.sha256(data).hexdigest().upper()


def derive_golden_state(
    catalog_path: Path,
    *,
    cost_basis_anchor: str = DEFAULT_ANCHOR,
) -> dict[str, Any]:
    catalog = load_catalog(catalog_path)
    active = sorted(
        (entry for entry in catalog if entry.get("lifecycleStatus") == "ACTIVE"),
        key=lambda entry: entry["ticker"],
    )

    wire_holdings: list[dict[str, str]] = []
    persisted_holdings: list[dict[str, str]] = []

    for entry in active:
        ticker = entry["ticker"]
        quote_currency = entry["quoteCurrency"]
        base_price = entry["basePrice"]
        if not isinstance(base_price, Decimal):
            base_price = Decimal(str(base_price))

        qty = quantity_for(ticker)
        seed = seed_price(base_price, ticker)
        basis = cost_basis(seed, ticker)

        wire_holdings.append(
            {
                "assetTicker": ticker,
                "quantity": persisted_quantity(qty),
            }
        )
        persisted_holdings.append(
            {
                "assetTicker": ticker,
                "quantity": persisted_quantity(qty),
                "avgCostBasis": format(basis, "f"),
                "costBasisCurrency": quote_currency,
                "costBasisSource": "SEED",
                "costBasisAsOf": cost_basis_anchor,
            }
        )

    return {
        "metadata": {
            "demoUserId": DEMO_USER_ID,
            "costBasisAnchor": cost_basis_anchor,
            "catalogPath": str(catalog_path),
            "catalogSha256": catalog_digest(catalog_path),
            "catalogTotalEntries": len(catalog),
            "activeEntryCount": len(active),
        },
        "wireHoldings": wire_holdings,
        "persistedHoldings": persisted_holdings,
    }


def canonical_json(document: dict[str, Any]) -> str:
    return json.dumps(document, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog",
        type=Path,
        default=DEFAULT_CATALOG,
        help="Path to config/seed-tickers.json",
    )
    parser.add_argument(
        "--cost-basis-anchor",
        default=DEFAULT_ANCHOR,
        help="Configured app.demo.cost-basis-anchor value",
    )
    args = parser.parse_args(argv)

    document = derive_golden_state(
        args.catalog,
        cost_basis_anchor=args.cost_basis_anchor,
    )
    sys.stdout.write(canonical_json(document))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
