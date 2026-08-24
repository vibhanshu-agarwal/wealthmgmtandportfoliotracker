#!/usr/bin/env python3
"""
spec_a_9_10_template.py — Build and verify the checkpoint 9.10 controlled-refresh execution template.

build:  Deep-copies the live Job template, changes only the two approved paths, runs verify,
        writes canonical JSON, and prints a sanitized diff plus SHA-256 checksum.
        No secret values are printed or accepted.

verify: Validates an override template against the live template.
        Requires exactly two paths to differ; everything else must be identical and must
        satisfy the full structural contract.

Usage:
    python3 spec_a_9_10_template.py build \\
        --live-template live.json --output override.json \\
        --expected-tag 9b2cf0d... --expected-digest sha256:ad61144b...

    python3 spec_a_9_10_template.py verify \\
        --live-template live.json --override-template override.json \\
        --expected-tag 9b2cf0d... --expected-digest sha256:ad61144b...
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from typing import Any, NoReturn

# ── Constants ────────────────────────────────────────────────────────────────

ACR = "wealthprodacr.azurecr.io"
REPOSITORY = "market-data-service"
CONTAINER_NAME = "market-data-refresh"

# Plain env entries present in both live and override templates.
# Values listed are the expected live values; the override replaces only RUNNER.
PLAIN_ENV_LIVE: dict[str, str] = {
    "SPRING_PROFILES_ACTIVE": "prod,azure",
    "SPRING_MAIN_WEB_APPLICATION_TYPE": "none",
    "MARKET_DATA_JOB_RUNNER_ENABLED": "false",
    "MANAGEMENT_TRACING_EXPORT_ENABLED": "true",
    "MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_TRANSPORT": "grpc",
    "MANAGEMENT_TRACING_SAMPLING_PROBABILITY": "1.0",
    "SERVICE_VERSION": None,          # checked against expected_tag at runtime
    "DEPLOYMENT_ENVIRONMENT_NAME": "prod",
    "OTEL_SERVICE_NAME": "market-data-refresh-job",
}

# Secret-ref env entries: env-var name → required secretRef value.
SECRET_REF_MAP: dict[str, str] = {
    "SPRING_DATA_MONGODB_URI": "spring-data-mongodb-uri",
    "KAFKA_BOOTSTRAP_SERVERS": "kafka-bootstrap-servers",
    "KAFKA_SASL_USERNAME": "kafka-sasl-username",
    "KAFKA_SASL_PASSWORD": "kafka-sasl-password",
    "INTERNAL_API_KEY": "internal-api-key",
}

ALLOWED_SECRET_REFS = set(SECRET_REF_MAP.values())
EXPECTED_PLAIN_NAMES = set(PLAIN_ENV_LIVE.keys())
EXPECTED_SECRET_NAMES = set(SECRET_REF_MAP.keys())
ALL_EXPECTED_ENV_NAMES = EXPECTED_PLAIN_NAMES | EXPECTED_SECRET_NAMES


# ── Helpers ──────────────────────────────────────────────────────────────────

def _fail(*msgs: str) -> NoReturn:
    for msg in msgs:
        print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def _load(path: str) -> Any:
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        _fail(f"cannot load {path}: {exc}")


def _expected_tag_image(tag: str) -> str:
    return f"{ACR}/{REPOSITORY}:{tag}"


def _expected_digest_image(digest: str) -> str:
    return f"{ACR}/{REPOSITORY}@{digest}"


def _env_by_name(env_list: list[dict]) -> dict[str, dict]:
    """Return {name: entry} mapping, detecting duplicates."""
    seen: dict[str, dict] = {}
    for entry in env_list:
        name = entry.get("name")
        if not name:
            _fail("env entry missing 'name' field")
        if name in seen:
            _fail(f"duplicate env entry: {name}")
        seen[name] = entry
    return seen


# ── Structural validation (shared between live and override) ─────────────────

def _validate_structure(template: dict, label: str) -> tuple[dict, dict[str, dict]]:
    """
    Validate top-level template structure.

    Returns (container_dict, env_by_name_dict).
    Calls _fail on any violation.
    """
    errors: list[str] = []

    # No init containers
    init = template.get("initContainers")
    if init:
        errors.append(f"{label}: initContainers must be absent/null/empty; got {init}")

    # No volumes
    vols = template.get("volumes")
    if vols:
        errors.append(f"{label}: volumes must be absent/null/empty; got {vols}")

    # Exactly one container
    containers = template.get("containers")
    if not isinstance(containers, list) or len(containers) != 1:
        errors.append(f"{label}: must have exactly 1 container; got {containers}")
        if errors:
            _fail(*errors)

    container = containers[0]

    # Correct container name
    if container.get("name") != CONTAINER_NAME:
        errors.append(
            f"{label}: container name must be '{CONTAINER_NAME}'; got '{container.get('name')}'"
        )

    # Resources
    resources = container.get("resources", {})
    cpu = resources.get("cpu")
    memory = resources.get("memory")
    if cpu != 0.5:
        errors.append(f"{label}: cpu must be 0.5; got {cpu!r}")
    if memory != "1Gi":
        errors.append(f"{label}: memory must be '1Gi'; got {memory!r}")

    # Env entries must be a list
    env_list = container.get("env")
    if not isinstance(env_list, list) or not env_list:
        errors.append(f"{label}: 'env' must be a non-empty list")
        if errors:
            _fail(*errors)

    if errors:
        _fail(*errors)

    return container, _env_by_name(env_list)


def _validate_env_entries(
    env_map: dict[str, dict],
    expected_tag: str,
    runner_value: str,
    label: str,
) -> None:
    """
    Validate env entries for either the live or override template.

    runner_value: "false" for live, "true" for override.
    """
    errors: list[str] = []

    # No unexpected entries
    unexpected = set(env_map.keys()) - ALL_EXPECTED_ENV_NAMES
    if unexpected:
        errors.append(f"{label}: unexpected env entries: {sorted(unexpected)}")

    # No missing entries
    missing = ALL_EXPECTED_ENV_NAMES - set(env_map.keys())
    if missing:
        errors.append(f"{label}: missing env entries: {sorted(missing)}")

    if errors:
        _fail(*errors)

    # Validate plain entries
    for name, expected_value in PLAIN_ENV_LIVE.items():
        entry = env_map[name]
        # Reject if plaintext secret value present where secretRef expected
        if "secretRef" in entry:
            errors.append(
                f"{label}: plain env '{name}' has 'secretRef'; must use 'value'"
            )
            continue
        actual = entry.get("value")
        if name == "MARKET_DATA_JOB_RUNNER_ENABLED":
            if actual != runner_value:
                errors.append(
                    f"{label}: MARKET_DATA_JOB_RUNNER_ENABLED must be "
                    f"'{runner_value}'; got '{actual}'"
                )
        elif name == "SERVICE_VERSION":
            if actual != expected_tag:
                errors.append(
                    f"{label}: SERVICE_VERSION must be '{expected_tag}'; got '{actual}'"
                )
        else:
            if actual != expected_value:
                errors.append(
                    f"{label}: env '{name}' must be '{expected_value}'; got '{actual}'"
                )

    # Validate secret entries: must use secretRef, never value
    for name, required_ref in SECRET_REF_MAP.items():
        entry = env_map[name]
        if "value" in entry:
            errors.append(
                f"{label}: secret env '{name}' must use 'secretRef', not 'value' "
                "(plaintext secret rejected)"
            )
            continue
        actual_ref = entry.get("secretRef")
        if actual_ref not in ALLOWED_SECRET_REFS:
            errors.append(
                f"{label}: env '{name}' secretRef '{actual_ref}' is not in the "
                f"allowed set {sorted(ALLOWED_SECRET_REFS)}"
            )
        elif actual_ref != required_ref:
            errors.append(
                f"{label}: env '{name}' secretRef must be '{required_ref}'; got '{actual_ref}'"
            )

    if errors:
        _fail(*errors)


# ── verify ───────────────────────────────────────────────────────────────────

def verify(
    live: dict,
    override: dict,
    expected_tag: str,
    expected_digest: str,
) -> str:
    """
    Verify the override template is a valid checkpoint 9.10 execution template.

    Returns the two-path diff string on success.
    Calls _fail on any violation.
    """
    # Validate structure of both templates
    live_container, live_env = _validate_structure(live, "live")
    override_container, override_env = _validate_structure(override, "override")

    # Live: runner must be false (do not start execution from an already-enabled template)
    _validate_env_entries(live_env, expected_tag, runner_value="false", label="live")

    # Override: runner must be true
    _validate_env_entries(override_env, expected_tag, runner_value="true", label="override")

    # Override image must be digest-pinned to expected_digest
    expected_live_image = _expected_tag_image(expected_tag)
    expected_override_image = _expected_digest_image(expected_digest)

    live_image = live_container.get("image", "")
    override_image = override_container.get("image", "")

    if live_image != expected_live_image:
        _fail(
            f"live image must be '{expected_live_image}'; got '{live_image}'"
        )
    if override_image != expected_override_image:
        _fail(
            f"override image must be '{expected_override_image}'; got '{override_image}'"
        )

    # Diff check: reverse the two approved changes on override and compare with live.
    # This proves no other path differs.
    reversed_override = copy.deepcopy(override)
    rev_container = reversed_override["containers"][0]

    # Restore image
    rev_container["image"] = live_image

    # Restore MARKET_DATA_JOB_RUNNER_ENABLED
    for entry in rev_container["env"]:
        if entry.get("name") == "MARKET_DATA_JOB_RUNNER_ENABLED":
            entry["value"] = "false"
            break

    if reversed_override != live:
        # Compute the actual diff for the error message (sanitized: no secret values)
        _fail(
            "override template differs from live in more than the two approved paths "
            "(image and MARKET_DATA_JOB_RUNNER_ENABLED). "
            "No other field may change."
        )

    # Build the human-readable diff (no secret values printed)
    diff_lines = [
        "containers[0].image:",
        f"  {live_image} ->",
        f"  {override_image}",
        f"containers[0].env[MARKET_DATA_JOB_RUNNER_ENABLED].value: false -> true",
    ]
    return "\n".join(diff_lines)


# ── build ────────────────────────────────────────────────────────────────────

def build(
    live: dict,
    expected_tag: str,
    expected_digest: str,
) -> dict:
    """
    Build the override template from the live template.

    Deep-copies live, changes only the two approved paths, runs verify.
    Returns the validated override dict.
    """
    override = copy.deepcopy(live)
    container = override["containers"][0]

    # Change 1: digest-pin the image
    container["image"] = _expected_digest_image(expected_digest)

    # Change 2: enable the runner
    runner_changed = False
    for entry in container["env"]:
        if entry.get("name") == "MARKET_DATA_JOB_RUNNER_ENABLED":
            entry["value"] = "true"
            runner_changed = True
            break
    if not runner_changed:
        _fail("MARKET_DATA_JOB_RUNNER_ENABLED not found in live template env")

    # verify raises on any violation
    verify(live, override, expected_tag, expected_digest)
    return override


# ── CLI ──────────────────────────────────────────────────────────────────────

def _checksum(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def cmd_build(args: argparse.Namespace) -> None:
    live = _load(args.live_template)
    override = build(live, args.expected_tag, args.expected_digest)
    canonical = json.dumps(override, indent=2, sort_keys=False, ensure_ascii=False).encode()
    with open(args.output, "wb") as fh:
        fh.write(canonical)
    diff = verify(live, override, args.expected_tag, args.expected_digest)
    csum = _checksum(canonical)
    print("Diff (sanitized — no secret values):")
    print(diff)
    print(f"\nOutput: {args.output}")
    print(f"SHA-256: {csum}")


def cmd_verify(args: argparse.Namespace) -> None:
    live = _load(args.live_template)
    override = _load(args.override_template)
    diff = verify(live, override, args.expected_tag, args.expected_digest)
    with open(args.override_template, "rb") as fh:
        csum = _checksum(fh.read())
    print("PASS")
    print(diff)
    print(f"\nSHA-256: {csum}")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Build and verify the checkpoint 9.10 Job execution template."
    )
    sub = parser.add_subparsers(dest="command", required=True)

    build_p = sub.add_parser("build", help="Build override template from live template")
    build_p.add_argument("--live-template", required=True, metavar="LIVE.json")
    build_p.add_argument("--output", required=True, metavar="OVERRIDE.json")
    build_p.add_argument("--expected-tag", required=True)
    build_p.add_argument("--expected-digest", required=True)

    verify_p = sub.add_parser("verify", help="Verify override template against live template")
    verify_p.add_argument("--live-template", required=True, metavar="LIVE.json")
    verify_p.add_argument("--override-template", required=True, metavar="OVERRIDE.json")
    verify_p.add_argument("--expected-tag", required=True)
    verify_p.add_argument("--expected-digest", required=True)

    args = parser.parse_args(argv)
    if args.command == "build":
        cmd_build(args)
    else:
        cmd_verify(args)


if __name__ == "__main__":
    main()
