#!/usr/bin/env python3
"""
assert_job_runner_env_update.py — Spec A task 5.4 / checkpoint 9.11.

When MARKET_DATA_JOB_RUNNER_ENABLED changes on azurerm_container_app_job.market_data_refresh,
the plan must show an in-place update, never create/delete (replace). Direction is neutral:
false→true (9.11 enable) and true→false (9.11 abort) are both valid transitions; replacement
is outside checkpoint scope and can disrupt identity/template guarantees.

In azurerm 4.81.0, schedule_trigger_config is ForceNew. This assertion is the evidence that
runner enablement/suspension is done via a template env var (not ForceNew) and will fail
loudly on a provider upgrade that starts replacing the Job for this change.

Usage:
    python3 scripts/assert_job_runner_env_update.py tfplan.json
"""

from __future__ import annotations

import json
import sys

JOB_ADDRESS = "azurerm_container_app_job.market_data_refresh"
ENV_NAME = "MARKET_DATA_JOB_RUNNER_ENABLED"
REPLACE_ACTIONS = {"create", "delete"}


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def _job_change(plan: dict) -> dict | None:
    for rc in plan.get("resource_changes", []):
        if rc.get("address") == JOB_ADDRESS:
            return rc
    return None


def _env_value(side: dict | None, name: str) -> str | None:
    if not side:
        return None
    templates = side.get("template")
    if not isinstance(templates, list) or not templates:
        return None
    containers = templates[0].get("container") if isinstance(templates[0], dict) else None
    if not isinstance(containers, list) or not containers:
        return None
    env_list = containers[0].get("env") if isinstance(containers[0], dict) else None
    if not isinstance(env_list, list):
        return None
    for env in env_list:
        if isinstance(env, dict) and env.get("name") == name:
            value = env.get("value")
            return None if value is None else str(value)
    return None


def evaluate_plan(plan: dict) -> list[str]:
    rc = _job_change(plan)
    if rc is None:
        return []
    change = rc.get("change") or {}
    before = _env_value(change.get("before"), ENV_NAME)
    after = _env_value(change.get("after"), ENV_NAME)
    if before == after:
        return []
    actions = set(change.get("actions") or [])
    # Greenfield create (PR plans use an empty local backend) is not a replace.
    if before is None and "create" in actions and "delete" not in actions:
        return []
    if actions & REPLACE_ACTIONS:
        return [
            f"FAIL [job runner env] {JOB_ADDRESS} changes {ENV_NAME} "
            f"({before!r} → {after!r}) with actions {sorted(actions)}; "
            "expected in-place update, not create/delete. In azurerm 4.81.0 "
            "schedule_trigger_config is ForceNew; any runner-env transition must not "
            "replace the Job."
        ]
    if "update" not in actions:
        return [
            f"FAIL [job runner env] {JOB_ADDRESS} changes {ENV_NAME} "
            f"({before!r} → {after!r}) but actions={sorted(actions)} do not include update."
        ]
    return []


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_job_runner_env_update.py <tfplan.json>", file=sys.stderr)
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

    errors = evaluate_plan(plan)
    if errors:
        print("JOB RUNNER ENV PLAN ASSERTION FAILED:")
        for err in errors:
            print(f"  {err}")
        return 1
    print(
        "PASS job-runner env update — MARKET_DATA_JOB_RUNNER_ENABLED changes "
        "do not replace azurerm_container_app_job.market_data_refresh"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
