#!/usr/bin/env python3
"""
allowance_independence_check.py — Property 6 (Allowance_Independence) check.

Computes worst-case ingestion cost at both workspace caps with zero Ingestion_Allowance,
adds the resource-group forecast and any recurring control charges, and compares against
the budget. Meter rate and forecast are required CLI arguments so a stale figure cannot
silently pass.

Formula:
    31 × (0.023 + 0.023) × meter_rate + forecast + recurring_charges

Usage:
    python allowance_independence_check.py --meter-rate <inr-per-gb> --forecast <inr>
    python allowance_independence_check.py --meter-rate <inr-per-gb> --forecast <inr> \\
        --recurring-charges <inr> --budget <inr>

Exit codes:
    0 — projected spend is below budget (PASS)
    1 — projected spend is at or above budget (FAIL), or arguments are missing/invalid

Validates: Requirements 4.3, 4.4, 4.5, 4.6, 4.7, 4.9
"""

from __future__ import annotations

import argparse
import sys

DAYS_IN_WORST_CASE_MONTH = 31
TELEMETRY_CAP_GB = 0.023
PLATFORM_CAP_GB = 0.023
DEFAULT_RECURRING_CHARGES = 0.0
DEFAULT_BUDGET = 1100.0


def projected_spend(meter_rate: float, forecast: float, recurring_charges: float) -> float:
    """Worst-case monthly spend with Ingestion_Allowance assumed zero (do not subtract 5 GB)."""
    return (
        DAYS_IN_WORST_CASE_MONTH
        * (TELEMETRY_CAP_GB + PLATFORM_CAP_GB)
        * meter_rate
        + forecast
        + recurring_charges
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Property 6 Allowance_Independence check: worst-case capped ingestion "
            "plus forecast and recurring charges, compared to budget."
        )
    )
    parser.add_argument(
        "--meter-rate",
        type=float,
        required=True,
        metavar="INR_PER_GB",
        help="Central India Analytics ingestion meter rate in ₹/GB (required; never defaulted)",
    )
    parser.add_argument(
        "--forecast",
        type=float,
        required=True,
        metavar="INR",
        help="Current resource-group cost forecast in ₹ (required; never defaulted)",
    )
    parser.add_argument(
        "--recurring-charges",
        type=float,
        default=DEFAULT_RECURRING_CHARGES,
        metavar="INR",
        help="Sum of recurring control charges in ₹ (default: 0)",
    )
    parser.add_argument(
        "--budget",
        type=float,
        default=DEFAULT_BUDGET,
        metavar="INR",
        help="Budget amount in ₹ (default: 1100)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        if exc.code in (0, None):
            return 0
        return 1

    spend = projected_spend(args.meter_rate, args.forecast, args.recurring_charges)
    margin = args.budget - spend
    passed = spend < args.budget
    status = "PASS" if passed else "FAIL"
    print(f"{status} margin={margin:.2f}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
