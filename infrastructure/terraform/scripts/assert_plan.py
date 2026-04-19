#!/usr/bin/env python3
"""
assert_plan.py — Terraform plan assertion script for CI property validation.

Validates correctness properties against the JSON output of `terraform show -json tfplan`.

Usage:
    python3 scripts/assert_plan.py plan.json

Exit codes:
    0 — all assertions passed
    1 — one or more assertions failed
"""

import json
import sys


PROHIBITED_RESOURCE_TYPES = {
    "aws_ecs_cluster",
    "aws_ecs_service",
    "aws_ecs_task_definition",
    "aws_lb",
    "aws_lb_listener",
    "aws_nat_gateway",
    "aws_internet_gateway",
    "aws_db_instance",
    "aws_rds_cluster",
    "aws_docdb_cluster",
    "aws_elasticache_cluster",
    "aws_elasticache_replication_group",
}

REQUIRED_LAMBDA_FUNCTIONS = {
    "wealth-api-gateway",
    "wealth-portfolio-service",
    "wealth-market-data-service",
    "wealth-insight-service",
}

ACTIVE_ACTIONS = {"create", "update"}


def load_plan(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def get_active_changes(plan: dict) -> list:
    """Return resource_changes entries that have create or update actions."""
    changes = []
    for rc in plan.get("resource_changes", []):
        actions = set(rc.get("change", {}).get("actions", []))
        if actions & ACTIVE_ACTIONS:
            changes.append(rc)
    return changes


def assert_no_prohibited_resources(changes: list) -> list:
    """Property 4: No prohibited (paid) resource types in the plan."""
    errors = []
    for rc in changes:
        if rc["type"] in PROHIBITED_RESOURCE_TYPES:
            errors.append(
                f"FAIL [Property 4] Prohibited resource type found: "
                f"{rc['type']} (address: {rc['address']})"
            )
    return errors


def assert_all_lambda_functions_present(plan: dict) -> list:
    """Property 3: All four Lambda functions must exist in the plan (active or no-op).

    Checks all resource_changes regardless of action — a no-op means the resource
    already exists and is stable, which is equally valid as a create/update.
    """
    found = set()
    for rc in plan.get("resource_changes", []):
        if rc["type"] == "aws_lambda_function":
            after = rc.get("change", {}).get("after", {}) or {}
            # For no-op resources, 'after' may be null — fall back to 'before'
            if not after:
                after = rc.get("change", {}).get("before", {}) or {}
            name = after.get("function_name", "")
            if name in REQUIRED_LAMBDA_FUNCTIONS:
                found.add(name)
    errors = []
    missing = REQUIRED_LAMBDA_FUNCTIONS - found
    for fn in sorted(missing):
        errors.append(
            f"FAIL [Property 3] Required Lambda function not found in plan: {fn}"
        )
    return errors


def assert_lambda_concurrency_cap(changes: list) -> list:
    """Property 7: reserved_concurrent_executions must be either unset (-1) or <= 10.

    This account has a total concurrency limit of 10, so reserved concurrency
    cannot be set without blocking all other functions. Unset (-1) is correct.
    """
    errors = []
    for rc in changes:
        if rc["type"] == "aws_lambda_function":
            after = rc.get("change", {}).get("after", {}) or {}
            rce = after.get("reserved_concurrent_executions")
            fn_name = after.get("function_name", rc["address"])
            # -1 or None means unreserved (correct for low-concurrency accounts)
            if rce is not None and rce != -1 and not (0 < rce <= 10):
                errors.append(
                    f"FAIL [Property 7] Lambda '{fn_name}' has "
                    f"reserved_concurrent_executions={rce} (must be unset or 1-10)"
                )
    return errors


def assert_spring_profiles_active(changes: list) -> list:
    """Property 3: SPRING_PROFILES_ACTIVE must start with 'prod,aws' on all Lambda env vars.

    insight-service uses 'prod,aws,bedrock' (adds the bedrock profile for Bedrock AI).
    All other services use 'prod,aws'. Both are valid — the key requirement is that
    'prod' and 'aws' are present so application-prod.yml and application-aws.yml load.

    Skips functions where environment.variables is (known after apply) — null in plan JSON.
    """
    errors = []
    for rc in changes:
        if rc["type"] == "aws_lambda_function":
            after = rc.get("change", {}).get("after", {}) or {}
            fn_name = after.get("function_name", rc["address"])
            env_list = after.get("environment", []) or []
            env_vars = {}
            all_null = True
            for env_block in env_list:
                if isinstance(env_block, dict):
                    variables = env_block.get("variables")
                    if variables is not None:
                        all_null = False
                        env_vars.update(variables)
            if all_null and env_list:
                continue
            if "SPRING_PROFILES_ACTIVE" not in env_vars:
                errors.append(
                    f"FAIL [Property 3] Lambda '{fn_name}' is missing "
                    f"SPRING_PROFILES_ACTIVE environment variable"
                )
            else:
                actual = env_vars["SPRING_PROFILES_ACTIVE"]
                # Must contain both 'prod' and 'aws' profiles.
                # insight-service adds 'bedrock' — that is intentional and valid.
                if "prod" not in actual.split(",") or "aws" not in actual.split(","):
                    errors.append(
                        f"FAIL [Property 3] Lambda '{fn_name}' has "
                        f"SPRING_PROFILES_ACTIVE='{actual}' — must include both "
                        f"'prod' and 'aws' so application-prod.yml and "
                        f"application-aws.yml load correctly."
                    )
    return errors


def assert_cloudfront_price_class(changes: list) -> list:
    """Property: CloudFront distribution must use PriceClass_100."""
    errors = []
    for rc in changes:
        if rc["type"] == "aws_cloudfront_distribution":
            after = rc.get("change", {}).get("after", {}) or {}
            price_class = after.get("price_class")
            if price_class != "PriceClass_100":
                errors.append(
                    f"FAIL [Free Tier] CloudFront distribution '{rc['address']}' "
                    f"has price_class='{price_class}' (must be 'PriceClass_100')"
                )
    return errors


def assert_route53_record_type(changes: list) -> list:
    """Property 6: Any Route 53 record in the plan must be type A."""
    errors = []
    for rc in changes:
        if rc["type"] == "aws_route53_record":
            after = rc.get("change", {}).get("after", {}) or {}
            record_type = after.get("type")
            if record_type != "A":
                errors.append(
                    f"FAIL [Property 6] Route 53 record '{rc['address']}' "
                    f"has type='{record_type}' (must be 'A')"
                )
    return errors


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python3 assert_plan.py <plan.json>", file=sys.stderr)
        return 1

    plan_path = sys.argv[1]
    try:
        plan = load_plan(plan_path)
    except (FileNotFoundError, json.JSONDecodeError) as e:
        print(f"ERROR: Failed to load plan JSON from '{plan_path}': {e}", file=sys.stderr)
        return 1

    changes = get_active_changes(plan)

    all_errors = []
    all_errors.extend(assert_no_prohibited_resources(changes))
    all_errors.extend(assert_all_lambda_functions_present(plan))
    all_errors.extend(assert_lambda_concurrency_cap(changes))
    all_errors.extend(assert_spring_profiles_active(changes))
    all_errors.extend(assert_cloudfront_price_class(changes))
    all_errors.extend(assert_route53_record_type(changes))

    if all_errors:
        print(f"\n{'='*60}")
        print(f"PLAN ASSERTION FAILED — {len(all_errors)} error(s):")
        print(f"{'='*60}")
        for err in all_errors:
            print(f"  {err}")
        print(f"{'='*60}\n")
        return 1

    lambda_count = sum(1 for rc in changes if rc["type"] == "aws_lambda_function")
    cf_count = sum(1 for rc in changes if rc["type"] == "aws_cloudfront_distribution")
    print(f"✓ All plan assertions passed")
    print(f"  Lambda functions: {lambda_count}")
    print(f"  CloudFront distributions: {cf_count}")
    print(f"  No prohibited resource types found")
    print(f"  reserved_concurrent_executions <= 10 on all Lambdas")
    print(f"  SPRING_PROFILES_ACTIVE present on all Lambdas")
    return 0


if __name__ == "__main__":
    sys.exit(main())
