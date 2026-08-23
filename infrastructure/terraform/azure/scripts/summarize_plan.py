#!/usr/bin/env python3
"""
summarize_plan.py — emit a sanitized "<address> <actions>" summary from tfplan.json.

Spec A checkpoint-9.9 hardening: `terraform show -no-color tfplan | grep -E '^\\s*(#|~|\\+|-) '`
is unsafe as a "sanitized" summary — that pattern also matches human-readable changed-attribute
lines (e.g. `~ value = "some-secret"`), not just resource addresses/actions, so config values
(including anything sourced from TF_VAR_* secrets) could land in a step summary meant to be safe
to share. This script reads structured JSON instead and prints only the resource address and its
action list — never attribute paths, before/after objects, or values.

Usage:
    python3 scripts/summarize_plan.py tfplan.json
"""

from __future__ import annotations

import json
import sys


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def _is_noop(rc: dict) -> bool:
    actions = rc.get("change", {}).get("actions") or []
    return list(actions) in ([], ["no-op"])


def summarize(plan: dict) -> list[str]:
    lines = []
    for rc in plan.get("resource_changes", []):
        if _is_noop(rc):
            continue
        address = rc.get("address", "<unknown address>")
        actions = list(rc.get("change", {}).get("actions") or [])
        lines.append(f"{address} {actions}")
    return lines


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 summarize_plan.py <tfplan.json>", file=sys.stderr)
        return 1
    path = sys.argv[1]
    try:
        plan = load_plan(path)
    except FileNotFoundError:
        print(f"ERROR: Plan file not found: '{path}'", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse plan JSON from '{path}': {e}", file=sys.stderr)
        return 1

    lines = summarize(plan)
    if not lines:
        print("(no non-no-op resource changes)")
        return 0
    for line in lines:
        print(line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
