#!/usr/bin/env python3
"""
assert_mongo_repair_job_plan.py — Spec A task 7.1.

The Mongo repair Job must be a manual azurerm_container_app_job using the
market-data-service image, with repair=true and the refresh property absent
(not false).

Usage:
    python3 scripts/assert_mongo_repair_job_plan.py tfplan.json
"""

from __future__ import annotations

import json
import sys

JOB_ADDRESS = "azurerm_container_app_job.market_data_repair"
REPAIR_ENV = "MARKET_DATA_REPAIR_ENABLED"
REFRESH_ENV = "MARKET_DATA_JOB_RUNNER_ENABLED"
WEB_ENV = "SPRING_MAIN_WEB_APPLICATION_TYPE"


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def _walk_modules(module: dict | None) -> list[dict]:
    if not module:
        return []
    found = list(module.get("resources") or [])
    for child in module.get("child_modules") or []:
        found.extend(_walk_modules(child))
    return found


def _planned_job(plan: dict) -> dict | None:
    root = (plan.get("planned_values") or {}).get("root_module") or {}
    for resource in _walk_modules(root):
        if resource.get("address") == JOB_ADDRESS:
            return resource
    return None


def _values(resource: dict | None) -> dict:
    if not resource:
        return {}
    return resource.get("values") or {}


def _env_map(values: dict) -> dict[str, str]:
    templates = values.get("template") or []
    if not templates:
        return {}
    containers = templates[0].get("container") or []
    if not containers:
        return {}
    env_list = containers[0].get("env") or []
    mapped: dict[str, str] = {}
    for env in env_list:
        if isinstance(env, dict) and env.get("name"):
            mapped[str(env["name"])] = "" if env.get("value") is None else str(env.get("value"))
    return mapped


def evaluate_plan(plan: dict) -> list[str]:
    job = _planned_job(plan)
    if job is None:
        return [f"FAIL [mongo repair job] {JOB_ADDRESS} missing from planned_values"]
    values = _values(job)
    errors: list[str] = []
    env = _env_map(values)
    if env.get(REPAIR_ENV) != "true":
        errors.append(
            f"FAIL [mongo repair job] {REPAIR_ENV} must be 'true', got {env.get(REPAIR_ENV)!r}"
        )
    if REFRESH_ENV in env:
        errors.append(
            f"FAIL [mongo repair job] {REFRESH_ENV} must be absent, got {env.get(REFRESH_ENV)!r}. "
            "false activates the suspended runner and can exit the process under the repair."
        )
    if env.get(WEB_ENV) != "none":
        errors.append(
            f"FAIL [mongo repair job] {WEB_ENV} must be 'none', got {env.get(WEB_ENV)!r}"
        )
    if not values.get("manual_trigger_config"):
        errors.append("FAIL [mongo repair job] manual_trigger_config is required")
    if values.get("schedule_trigger_config"):
        errors.append("FAIL [mongo repair job] schedule_trigger_config must be absent")
    image = ""
    templates = values.get("template") or []
    if templates:
        containers = templates[0].get("container") or []
        if containers:
            image = str(containers[0].get("image") or "")
    if image and "market-data-service" not in image and "containerapps-helloworld" not in image:
        errors.append(
            f"FAIL [mongo repair job] image must be market-data-service (or bootstrap seed), got {image!r}"
        )
    timeout = values.get("replica_timeout_in_seconds")
    if timeout is None or int(timeout) <= 0:
        errors.append("FAIL [mongo repair job] replica_timeout_in_seconds must be a positive bound")
    return errors


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_mongo_repair_job_plan.py <tfplan.json>", file=sys.stderr)
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
        print("MONGO REPAIR JOB PLAN ASSERTION FAILED:")
        for err in errors:
            print(f"  {err}")
        return 1
    print(
        "PASS mongo repair job — manual trigger, repair=true, refresh absent, non-web, bounded timeout"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
