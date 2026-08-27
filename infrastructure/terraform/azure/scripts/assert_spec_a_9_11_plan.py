#!/usr/bin/env python3
"""
assert_spec_a_9_11_plan.py — Spec A checkpoint 9.11 exact-scope plan assertion.

Checkpoint 9.11 persists MARKET_DATA_JOB_RUNNER_ENABLED=true on the refresh Job (enable),
or reverts it to false (abort). The plan must touch ONLY
azurerm_container_app_job.market_data_refresh as an in-place update, with that env as the
sole meaningful delta. Image and SERVICE_VERSION are pinned to the dispatch's
deployed_image_tag. The safety tuple (retry=0, timeout=600, cron, parallelism, completion)
must hold on both sides.

Runs for every remote-plan/apply. Under standard / 9.9 profiles, any change to the runner
env fails closed — only the two 9.11 profiles may perform that transition.

Usage:
    python3 scripts/assert_spec_a_9_11_plan.py tfplan.json --profile standard --expected-image-tag <sha>
    python3 scripts/assert_spec_a_9_11_plan.py tfplan.json --profile spec-a-9.11-enable --expected-image-tag <sha>
    python3 scripts/assert_spec_a_9_11_plan.py tfplan.json --profile spec-a-9.11-abort --expected-image-tag <sha>
"""

from __future__ import annotations

import argparse
import copy
import json
import sys

JOB_ADDRESS = "azurerm_container_app_job.market_data_refresh"
RUNNER_ENV = "MARKET_DATA_JOB_RUNNER_ENABLED"
ACR_LOGIN_SERVER = "wealthprodacr.azurecr.io"
IMAGE_REPOSITORY = "market-data-service"

SCOPED_PROFILES = ("spec-a-9.11-enable", "spec-a-9.11-abort")
KNOWN_PROFILES = (
    "standard",
    "spec-a-9.9-enable",
    "spec-a-9.9-abort",
    "spec-a-9.11-enable",
    "spec-a-9.11-abort",
)

REQUIRED_RETRY = 0
REQUIRED_TIMEOUT = 600
REQUIRED_CRON = "0 8 * * *"
REQUIRED_PARALLELISM = 1
REQUIRED_COMPLETION = 1

_RUNNER_SENTINEL = "__SPEC_A_9_11_RUNNER_SENTINEL__"


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def _is_noop(rc: dict) -> bool:
    actions = rc.get("change", {}).get("actions") or []
    return list(actions) in ([], ["no-op"])


def _template(side: dict | None) -> dict | None:
    if not side:
        return None
    templates = side.get("template")
    if not isinstance(templates, list) or not templates:
        return None
    return templates[0] if isinstance(templates[0], dict) else None


def _container(side: dict | None) -> dict | None:
    template = _template(side)
    if template is None:
        return None
    containers = template.get("container")
    if not isinstance(containers, list) or not containers:
        return None
    return containers[0] if isinstance(containers[0], dict) else None


def _env_entries(side: dict | None) -> list[dict]:
    container = _container(side)
    if container is None:
        return []
    env_list = container.get("env")
    if not isinstance(env_list, list):
        return []
    return [e for e in env_list if isinstance(e, dict) and "name" in e]


def _runner_entries(side: dict | None) -> list[dict]:
    return [e for e in _env_entries(side) if e.get("name") == RUNNER_ENV]


def _runner_value(side: dict | None) -> str | None:
    entries = _runner_entries(side)
    if len(entries) != 1:
        return None
    value = entries[0].get("value")
    return None if value is None else str(value)


def _image(side: dict | None) -> str | None:
    container = _container(side)
    return container.get("image") if container else None


def _service_version(side: dict | None) -> str | None:
    for e in _env_entries(side):
        if e.get("name") == "SERVICE_VERSION":
            value = e.get("value")
            return None if value is None else str(value)
    return None


def _schedule(side: dict | None) -> dict | None:
    if not side:
        return None
    configs = side.get("schedule_trigger_config")
    if not isinstance(configs, list) or not configs:
        return None
    return configs[0] if isinstance(configs[0], dict) else None


def _normalize_runner(side: dict | None) -> dict | None:
    """Deep-copy a side and replace the runner env value with a sentinel."""
    if side is None:
        return None
    normalized = copy.deepcopy(side)
    for entry in _runner_entries(normalized):
        entry["value"] = _RUNNER_SENTINEL
    return normalized


def _check_runner_count(side: dict | None, label: str, errors: list[str]) -> str | None:
    entries = _runner_entries(side)
    if len(entries) == 0:
        errors.append(
            f"FAIL [runner] {JOB_ADDRESS} {label} is missing {RUNNER_ENV} "
            "(exactly one entry required)."
        )
        return None
    if len(entries) > 1:
        errors.append(
            f"FAIL [runner] {JOB_ADDRESS} {label} declares {RUNNER_ENV} "
            f"{len(entries)} times; exactly one entry is required."
        )
        return None
    value = entries[0].get("value")
    return None if value is None else str(value)


def _check_safety_tuple(side: dict | None, label: str, errors: list[str]) -> None:
    if side is None:
        errors.append(f"FAIL [safety] {JOB_ADDRESS} {label} side is missing.")
        return
    retry = side.get("replica_retry_limit")
    timeout = side.get("replica_timeout_in_seconds")
    if retry != REQUIRED_RETRY:
        errors.append(
            f"FAIL [safety] {JOB_ADDRESS} {label} replica_retry_limit is outside the "
            f"required tuple (expected {REQUIRED_RETRY})."
        )
    if timeout != REQUIRED_TIMEOUT:
        errors.append(
            f"FAIL [safety] {JOB_ADDRESS} {label} replica_timeout_in_seconds is outside "
            f"the required tuple (expected {REQUIRED_TIMEOUT})."
        )
    schedule = _schedule(side)
    if schedule is None:
        errors.append(
            f"FAIL [safety] {JOB_ADDRESS} {label} schedule_trigger_config is missing."
        )
        return
    if schedule.get("cron_expression") != REQUIRED_CRON:
        errors.append(
            f"FAIL [safety] {JOB_ADDRESS} {label} cron_expression is outside the "
            "required tuple."
        )
    if schedule.get("parallelism") != REQUIRED_PARALLELISM:
        errors.append(
            f"FAIL [safety] {JOB_ADDRESS} {label} parallelism is outside the "
            "required tuple."
        )
    if schedule.get("replica_completion_count") != REQUIRED_COMPLETION:
        errors.append(
            f"FAIL [safety] {JOB_ADDRESS} {label} replica_completion_count is outside "
            "the required tuple."
        )


def _check_identity(side: dict | None, label: str, expected_image_tag: str, errors: list[str]) -> None:
    expected_image = f"{ACR_LOGIN_SERVER}/{IMAGE_REPOSITORY}:{expected_image_tag}"
    image = _image(side)
    if image != expected_image:
        errors.append(
            f"FAIL [image] {JOB_ADDRESS} {label} image does not match "
            f"{ACR_LOGIN_SERVER}/{IMAGE_REPOSITORY}:<expected-tag>."
        )
    version = _service_version(side)
    if version != expected_image_tag:
        errors.append(
            f"FAIL [service_version] {JOB_ADDRESS} {label} SERVICE_VERSION does not "
            "match the expected deployed_image_tag."
        )


def _evaluate_non_scoped(plan: dict) -> list[str]:
    """standard / 9.9 profiles: forbid any runner-env transition on the Job."""
    errors: list[str] = []
    for rc in plan.get("resource_changes", []):
        if rc.get("address") != JOB_ADDRESS or _is_noop(rc):
            continue
        change = rc.get("change") or {}
        before, after = change.get("before"), change.get("after")
        before_entries = _runner_entries(before)
        after_entries = _runner_entries(after)
        # Count mismatches or value drift both count as a forbidden transition.
        if len(before_entries) != len(after_entries):
            errors.append(
                f"FAIL [runner-guard] {JOB_ADDRESS} changes {RUNNER_ENV} entry count "
                "under a non-9.11 profile; use change_profile=spec-a-9.11-enable/abort."
            )
            continue
        before_val = _runner_value(before)
        after_val = _runner_value(after)
        if before_val != after_val:
            errors.append(
                f"FAIL [runner-guard] {JOB_ADDRESS} changes {RUNNER_ENV} under a "
                "non-9.11 profile; use change_profile=spec-a-9.11-enable/abort."
            )
    return errors


def _evaluate_scoped(plan: dict, profile: str, expected_image_tag: str) -> list[str]:
    is_enable = profile == "spec-a-9.11-enable"
    expected_before = "false" if is_enable else "true"
    expected_after = "true" if is_enable else "false"

    errors: list[str] = []
    resource_changes = plan.get("resource_changes", [])
    non_noop = [rc for rc in resource_changes if not _is_noop(rc)]
    non_noop_addresses = {rc.get("address") for rc in non_noop}

    unexpected = non_noop_addresses - {JOB_ADDRESS}
    if unexpected:
        errors.append(
            f"FAIL [scope] unexpected non-no-op resource change(s) outside the 9.11 "
            f"scope: {sorted(unexpected)}"
        )

    if JOB_ADDRESS not in non_noop_addresses:
        errors.append(
            f"FAIL [scope] expected a change on {JOB_ADDRESS} but none is present "
            "(or it is a no-op) — plan does not touch the 9.11 checkpoint target."
        )
        return errors

    rc = next(rc for rc in non_noop if rc.get("address") == JOB_ADDRESS)
    actions = list(rc.get("change", {}).get("actions") or [])
    if actions != ["update"]:
        errors.append(
            f"FAIL [action] {JOB_ADDRESS} has actions={actions}; expected exactly "
            "['update'] — a create/delete/replace is not an in-place 9.11 change."
        )
        return errors

    change = rc.get("change") or {}
    before, after = change.get("before"), change.get("after")

    before_val = _check_runner_count(before, "before", errors)
    after_val = _check_runner_count(after, "after", errors)
    if before_val is not None and before_val != expected_before:
        errors.append(
            f"FAIL [runner-direction] {JOB_ADDRESS} before {RUNNER_ENV} is not the "
            f"expected start value for profile={profile}."
        )
    if after_val is not None and after_val != expected_after:
        errors.append(
            f"FAIL [runner-direction] {JOB_ADDRESS} after {RUNNER_ENV} is not the "
            f"expected target value for profile={profile}."
        )

    _check_safety_tuple(before, "before", errors)
    _check_safety_tuple(after, "after", errors)
    _check_identity(before, "before", expected_image_tag, errors)
    _check_identity(after, "after", expected_image_tag, errors)

    # Sole-delta proof: after normalizing the runner value, before and after must match.
    if before is not None and after is not None:
        if _normalize_runner(before) != _normalize_runner(after):
            errors.append(
                f"FAIL [field] {JOB_ADDRESS} changes a field other than {RUNNER_ENV}; "
                "checkpoint 9.11 permits only the runner env transition."
            )

    return errors


def evaluate_plan(plan: dict, profile: str, expected_image_tag: str) -> list[str]:
    if profile not in KNOWN_PROFILES:
        return [f"FAIL [profile] unknown change_profile={profile!r}; fail closed."]
    if profile in SCOPED_PROFILES:
        return _evaluate_scoped(plan, profile, expected_image_tag)
    return _evaluate_non_scoped(plan)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plan_json")
    parser.add_argument(
        "--profile",
        choices=KNOWN_PROFILES,
        required=True,
        help="The literal change_profile dispatch input value.",
    )
    parser.add_argument(
        "--expected-image-tag",
        required=True,
        help="The deployed_image_tag this plan must show before AND after on the Job.",
    )
    args = parser.parse_args()

    try:
        plan = load_plan(args.plan_json)
    except FileNotFoundError:
        print(f"ERROR: Plan file not found: '{args.plan_json}'", file=sys.stderr)
        return 1
    except json.JSONDecodeError as e:
        print(f"ERROR: Failed to parse plan JSON from '{args.plan_json}': {e}", file=sys.stderr)
        return 1

    errors = evaluate_plan(plan, args.profile, args.expected_image_tag)
    if errors:
        print(f"SPEC A 9.11 PLAN ASSERTION FAILED (profile={args.profile}):")
        for err in errors:
            print(f"  {err}")
        return 1
    if args.profile in SCOPED_PROFILES:
        print(
            f"PASS spec-a-9.11 plan (profile={args.profile}) — exactly the refresh Job "
            "updates in place; runner transition and safety tuple verified."
        )
    else:
        print(
            f"PASS spec-a-9.11 guard (profile={args.profile}) — the plan does not change "
            f"{RUNNER_ENV} on {JOB_ADDRESS}."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
